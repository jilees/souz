package ru.souz.tool.skills

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.SandboxMode
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.BadInputException
import ru.souz.tool.InputParamDescription
import kotlin.io.path.deleteIfExists


class SkillCommandExecutor(
    private val sandboxResolver: ToolInvocationRuntimeSandboxResolver,
    private val toolCatalog: AgentToolCatalog? = null,
    private val toolsFilter: AgentToolsFilter? = null,
) {
    internal data class Args(
        @InputParamDescription("Runtime to execute: BASH, PYTHON, NODE, or PROCESS. Use BASH for shell scripts and PROCESS for argv commands.")
        val runtime: SandboxCommandRuntime = SandboxCommandRuntime.BASH,
        @InputParamDescription("Command argv for PROCESS runtime, for example [\"bash\", \"scripts/run.sh\"]. Leave empty for BASH/PYTHON/NODE.")
        val command: List<String> = emptyList(),
        @InputParamDescription("Inline script for BASH/PYTHON/NODE runtimes. For bundled scripts, call them by relative path, for example: bash scripts/run.sh")
        val script: String? = null,
        @InputParamDescription("Path to a bundled script inside the active Skill root. Prefer this over inline script when running supporting scripts.")
        val scriptPath: String? = null,
        @InputParamDescription("Arguments to pass to scriptPath, or to the inline script process as positional arguments.")
        val args: List<String> = emptyList(),
        @InputParamDescription("Working directory relative to the selected skill bundle root. Defaults to the skill bundle root.")
        val workingDirectory: String? = null,
        @InputParamDescription("Environment variables to pass to the process.")
        val environment: Map<String, String> = emptyMap(),
        @InputParamDescription("Optional stdin passed to the command.")
        val stdin: String? = null,
        @InputParamDescription("Timeout in milliseconds. Defaults to 60000 and is capped at 300000.")
        val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    )

    private companion object {
        const val BUNDLES_DIRECTORY_NAME = "bundles"
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        const val MAX_TIMEOUT_MILLIS = 300_000L
        const val SKILL_MARKDOWN_PATH = "SKILL.md"
        const val BRIDGE_TOOLS_METADATA = "souz.bridge-tools"
        const val MAX_TIMEOUT_METADATA = "souz.max-timeout"
        const val TOOL_BRIDGE_SOCKET_ENV = "SOUZ_TOOL_BRIDGE_SOCK"
        const val BRIDGE_SOCKET_DIRECTORY_NAME = ".br"
        const val LOCAL_BRIDGE_TMP_DIRECTORY = "/tmp"
        const val LOCAL_BRIDGE_TMP_PREFIX = "souz-bridge-"
    }

    internal suspend fun execute(
        bundle: SkillBundle,
        bundleHash: String,
        arguments: Args,
        meta: ToolInvocationMeta,
    ): SandboxCommandResult {
        val sandbox = sandboxResolver.resolve(meta)
        val skillRoot = resolveSkillRoot(sandbox, bundle.skillId, bundleHash)
        val workingDirectory = resolveWorkingDirectory(skillRoot, arguments.workingDirectory)
        val scriptPath = resolveScriptPath(sandbox, skillRoot, arguments.scriptPath)
        val timeoutCeiling = resolveTimeoutCeiling(bundle)
        val bridge = startToolBridge(sandbox, bundle, meta)
        try {
            val fixedEnvironment = buildMap {
                put("SOUZ_SKILL_ID", bundle.skillId.value)
                put("SOUZ_SKILL_ROOT", skillRoot)
                put(
                    "SOUZ_SKILL_SUPPORTING_FILES",
                    bundle.files
                        .asSequence()
                        .map { it.normalizedPath }
                        .filterNot { it == SKILL_MARKDOWN_PATH }
                        .joinToString(","),
                )
                bridge?.let { put(TOOL_BRIDGE_SOCKET_ENV, it.sandboxSocketPath) }
            }
            return sandbox.commandExecutor.execute(
                SandboxCommandRequest(
                    runtime = arguments.runtime,
                    command = arguments.command,
                    script = arguments.script,
                    scriptPath = scriptPath,
                    args = arguments.args,
                    workingDirectory = workingDirectory,
                    environment = fixedEnvironment + arguments.environment,
                    stdin = arguments.stdin,
                    timeoutMillis = arguments.timeoutMillis.coerceIn(1L, timeoutCeiling),
                )
            )
        } finally {
            bridge?.server?.stop()
            bridge?.hostCleanupDirectory?.let { runCatching { it.deleteIfExists() } }
        }
    }

    private fun resolveTimeoutCeiling(bundle: SkillBundle): Long {
        val declared = bundle.manifest.metadata[MAX_TIMEOUT_METADATA]?.trim()?.takeIf(String::isNotEmpty)
            ?: return MAX_TIMEOUT_MILLIS
        return runCatching { Duration.parse(declared).toMillis() }
            .getOrNull()
            ?.takeIf { it > 0 }
            ?: MAX_TIMEOUT_MILLIS
    }

    private class ActiveBridge(
        val server: SkillToolBridgeServer,
        val sandboxSocketPath: String,
        val hostCleanupDirectory: Path? = null,
    )

    /**
     * Starts the tool-call bridge only when the Skill declares a non-empty [BRIDGE_TOOLS_METADATA]
     * allowlist — absent or empty means no bridge at all (fail closed), never "everything the
     * model can call." See `SkillToolBridgeServer` for the transport and lookup itself.
     *
     * The socket is deliberately *not* nested under the (potentially very deep, hash-suffixed)
     * skill bundle root — `sockaddr_un.sun_path` is capped at roughly 100 bytes on both macOS and
     * Linux, and a path like `.../skills/<id>/bundles/<64-hex-hash>/...` blows through that on its
     * own before any socket-specific suffix is even added.
     */
    private fun startToolBridge(
        sandbox: RuntimeSandbox,
        bundle: SkillBundle,
        meta: ToolInvocationMeta,
    ): ActiveBridge? {
        val catalog = toolCatalog ?: return null
        val filter = toolsFilter ?: return null
        val allowedToolNames = parseBridgeToolAllowlist(bundle)
        if (allowedToolNames.isEmpty()) return null

        val socketId = UUID.randomUUID().toString().replace("-", "").take(10)
        val (hostSocketPath, sandboxSocketPath, cleanupDirectory) = when (sandbox.mode) {
            SandboxMode.LOCAL -> {
                // Local sandbox scripts share the JVM host's filesystem directly — no bind mount
                // to piggyback on, so bind under the real (short) OS temp root instead of wherever
                // the skill root happens to live.
                val directory = Files.createTempDirectory(Path.of(LOCAL_BRIDGE_TMP_DIRECTORY), LOCAL_BRIDGE_TMP_PREFIX)
                val socketPath = directory.resolve("$socketId.sock")
                Triple(socketPath, socketPath.toString(), directory)
            }

            SandboxMode.DOCKER -> {
                // Docker sandbox scripts only see paths under the container's bind-mounted root,
                // so the socket has to live there — anchored at the short state root rather than
                // the skill bundle root.
                //
                // KNOWN LIMITATION: confirmed by hand (host binds a UDS under the mounted root,
                // `docker exec` sees a valid socket special file but gets ECONNREFUSED connecting
                // to it) that this does not work when the Docker daemon runs inside a VM relative
                // to this JVM process — true for Docker Desktop and colima on macOS. The bind
                // mount only proxies file metadata across that boundary, not live socket I/O. This
                // is expected to work on a Docker daemon running natively on the same Linux kernel
                // (no VM layer) but that is unverified — treat Docker-mode bridge support as
                // unproven until confirmed there. LOCAL mode is unaffected (no VM boundary).
                val socketInfo = sandbox.fileSystem.resolvePath(
                    "${sandbox.runtimePaths.stateRootPath}/$BRIDGE_SOCKET_DIRECTORY_NAME/$socketId.sock"
                )
                val hostPath = sandbox.fileSystem.localPathOrNull(socketInfo) ?: return null
                Triple(hostPath, socketInfo.path, null)
            }
        }

        val server = SkillToolBridgeServer.start(
            socketPath = hostSocketPath,
            toolCatalog = catalog,
            toolsFilter = filter,
            allowedToolNames = allowedToolNames,
            skillId = bundle.skillId.value,
            meta = meta,
        )
        return ActiveBridge(server = server, sandboxSocketPath = sandboxSocketPath, hostCleanupDirectory = cleanupDirectory)
    }

    private fun parseBridgeToolAllowlist(bundle: SkillBundle): Set<String> =
        bundle.manifest.metadata[BRIDGE_TOOLS_METADATA]
            ?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            ?: emptySet()

    private fun resolveSkillRoot(
        sandbox: RuntimeSandbox,
        skillId: SkillId,
        bundleHash: String,
    ): String {
        val fileSystem = sandbox.fileSystem
        val bundleRoot = skillRootPath(sandbox.runtimePaths.skillsDirPath, skillId)
            .resolve(BUNDLES_DIRECTORY_NAME)
            .resolve(bundleHash)
        val storedBundle = fileSystem.resolvePath(bundleRoot.toString())
        if (storedBundle.exists && storedBundle.isDirectory) {
            return fileSystem.resolveExistingDirectory(storedBundle.path).path
        }

        val looseRoot = skillRootPath(sandbox.runtimePaths.skillsDirPath, skillId)
        val looseBundle = fileSystem.resolvePath(looseRoot.toString())
        if (looseBundle.exists && looseBundle.isDirectory) {
            return fileSystem.resolveExistingDirectory(looseBundle.path).path
        }

        throw BadInputException("Skill bundle root is unavailable for active skill: ${skillId.value}")
    }

    private fun resolveWorkingDirectory(skillRoot: String, rawWorkingDirectory: String?): String {
        val skillRootPath = Path.of(skillRoot).normalize()
        val relative = rawWorkingDirectory
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: "."
        val workingDirectory = skillRootPath.resolve(relative).normalize()
        if (!workingDirectory.startsWith(skillRootPath)) {
            throw BadInputException("workingDirectory must stay inside the selected skill bundle root.")
        }
        return workingDirectory.toString()
    }

    private fun resolveScriptPath(
        sandbox: RuntimeSandbox,
        skillRoot: String,
        rawScriptPath: String?,
    ): String? {
        val scriptPath = rawScriptPath
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        val skillRootPath = Path.of(skillRoot).normalize()
        val resolved = skillRootPath.resolve(scriptPath).normalize()
        if (!resolved.startsWith(skillRootPath)) {
            throw BadInputException("scriptPath must stay inside the selected skill bundle root.")
        }
        return sandbox.fileSystem.resolveExistingFile(resolved.toString()).path
    }

    private fun skillRootPath(
        skillsRootPath: String,
        skillId: SkillId,
    ): Path = Path.of(skillsRootPath).resolve(skillId.value)
}
