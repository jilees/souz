package ru.souz.backend.hooks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.SandboxScope

/** Locks cover only this process's hook mutations; operator edits are checked before each write. */
internal class HookWorkspaceFiles(
    private val sandboxes: RuntimeSandboxFactory,
    private val config: HookConfig,
    private val definitions: HookDefinitions,
    private val hooks: () -> HookService,
) {
    // Hook IDs share one intake namespace across owner workspaces.
    private val mutation = Mutex()

    suspend fun read(owner: String): Map<String, LoadedHook> {
        hookFileCheck(owner in config.owners, "hooks_not_enabled_for_user")
        val loaded = definitions.load(owner)
        hookFileCheck(loaded.map { it.definition.hookId }.distinct().size == loaded.size, "hook_conflict")
        return loaded.associateBy { it.definition.hookId }
    }

    suspend fun <T> mutate(owner: String, block: suspend (Workspace) -> T): T {
        hookFileCheck(owner in config.owners, "hooks_not_enabled_for_user")
        val sandbox = withContext(Dispatchers.IO) { sandboxes.create(SandboxScope(userId = owner)) }
        val root = sandbox.runtimePaths.workspaceRootPath ?: throw HookFileFailure("hook_workspace_unavailable")
        return mutation.withLock { block(Workspace(owner, sandbox, root)) }
    }

    inner class Workspace(private val owner: String, sandbox: RuntimeSandbox, root: String) {
        private val fs = sandbox.fileSystem
        private val hooksPath = "$root/hooks"
        private val log = LoggerFactory.getLogger(HookWorkspaceFiles::class.java)

        suspend fun find(id: String): LoadedHook? {
            val matches = definitions.load(owner).filter { it.definition.hookId == id }
            hookFileCheck(matches.size <= 1, "hook_conflict")
            if (matches.isEmpty()) {
                val path = withContext(Dispatchers.IO) { fs.resolvePath("$hooksPath/$id/hook.yaml") }
                // A foreign or renamed definition at the managed path is not a missing hook.
                hookFileCheck(!path.exists && !path.isSymbolicLink, "hook_configuration_changed")
            }
            return matches.singleOrNull()
        }

        suspend fun create(definition: HookDefinition, beforeWrite: () -> Unit): LoadedHook {
            read(owner) // Fail before writing when any existing definition is invalid.
            hookFileCheck(hooks().availableToOwner(owner, definition.hookId), "hook_conflict")
            val content = definitions.serialize(definition)
            withContext(Dispatchers.IO) {
                var root = fs.resolvePath(hooksPath)
                hookFileCheck(!root.isSymbolicLink && fs.isPathSafe(root), "hook_workspace_unavailable")
                if (!root.exists) fs.createDirectory(root)
                root = fs.resolveExistingDirectory(hooksPath)
                hookFileCheck(fs.listDescendants(root, maxDepth = 1).count { it.isDirectory } < 100, "hook_limit_exceeded")
                val directory = fs.resolvePath("${root.path}/${definition.hookId}")
                hookFileCheck(!directory.exists && !directory.isSymbolicLink && directory.parentPath == root.path && fs.isPathSafe(directory), "hook_conflict")
                beforeWrite()
                fs.createDirectory(directory)
                val path = fs.resolvePath("${directory.path}/hook.yaml")
                hookFileCheck(!path.exists && fs.isPathSafe(path) && path.parentPath == directory.path, "hook_conflict")
                fs.writeTextAtomically(path, content, log)
            }
            hooks().reload(owner)
            hookFileCheck(hooks().definition(owner, definition.hookId) == definition, "hook_reload_failed")
            return find(definition.hookId) ?: throw HookFileFailure("hook_reload_failed")
        }

        suspend fun replace(expected: LoadedHook, replacement: HookDefinition): LoadedHook {
            val current = unchanged(expected)
            withContext(Dispatchers.IO) {
                fs.writeTextAtomically(fs.resolveExistingFile(requireNotNull(current.sourcePath)), definitions.serialize(replacement), log)
            }
            hooks().reload(owner)
            hookFileCheck(hooks().definition(owner, replacement.hookId) == replacement, "hook_reload_failed")
            return find(replacement.hookId) ?: throw HookFileFailure("hook_reload_failed")
        }

        suspend fun remove(expected: LoadedHook) {
            val current = unchanged(expected)
            withContext(Dispatchers.IO) {
                val path = fs.resolveExistingFile(requireNotNull(current.sourcePath))
                val directory = fs.resolveExistingDirectory(requireNotNull(path.parentPath))
                fs.delete(path)
                if (fs.listDescendants(directory, maxDepth = 1, includeHidden = true).isEmpty()) fs.delete(directory)
            }
            hooks().reload(owner)
            hookFileCheck(hooks().definition(owner, current.definition.hookId) == null, "hook_reload_failed")
        }

        private suspend fun unchanged(expected: LoadedHook): LoadedHook {
            val current = find(expected.definition.hookId) ?: throw HookFileFailure("hook_configuration_changed")
            hookFileCheck(current.definition == expected.definition && current.revision == expected.revision && current.sourcePath == expected.sourcePath, "hook_configuration_changed")
            return current
        }
    }
}

internal class HookFileFailure(val code: String) : RuntimeException(code)
internal fun hookFileCheck(condition: Boolean, code: String = "invalid_arguments") {
    if (!condition) throw HookFileFailure(code)
}
