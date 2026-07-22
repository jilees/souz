package ru.souz.backend.salute.sandbox

import java.io.InputStream
import java.nio.file.Path
import org.slf4j.Logger
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.SandboxRuntimePaths
import ru.souz.tool.BadInputException

/**
 * Restricted [SandboxFileSystem] view exposed as [ru.souz.runtime.sandbox.RuntimeSandbox.fileSystem]
 * for Salute-targeted sandboxes. The backend and the speaker do not share a filesystem, so only
 * resolution/existence-check/listing operations (needed by `ToolRunSkillCommand` to validate
 * `scriptPath`/`workingDirectory` against the real backend-local skill bundle) are delegated to
 * [delegate]; every byte-moving operation is rejected. Reading a resolved script's own content to
 * embed it in an outgoing exec message is a separate, narrower concern handled directly by
 * [SaluteSandboxCommandExecutor] against the same [delegate] instance, not through this type.
 */
class SaluteSandboxFileSystem(
    private val delegate: SandboxFileSystem,
) : SandboxFileSystem {
    override val runtimePaths: SandboxRuntimePaths get() = delegate.runtimePaths

    override fun resolvePath(rawPath: String): SandboxPathInfo = delegate.resolvePath(rawPath)

    override fun resolveExistingFile(rawPath: String): SandboxPathInfo = delegate.resolveExistingFile(rawPath)

    override fun resolveExistingDirectory(rawPath: String): SandboxPathInfo = delegate.resolveExistingDirectory(rawPath)

    override fun isPathSafe(path: SandboxPathInfo): Boolean = delegate.isPathSafe(path)

    override fun forbiddenPaths(): List<String> = delegate.forbiddenPaths()

    override fun listDescendants(root: SandboxPathInfo, maxDepth: Int, includeHidden: Boolean): List<SandboxPathInfo> =
        delegate.listDescendants(root, maxDepth, includeHidden)

    override fun localPathOrNull(path: SandboxPathInfo): Path? = null

    override fun readBytes(path: SandboxPathInfo): ByteArray = unsupported("readBytes")

    override fun readText(path: SandboxPathInfo): String = unsupported("readText")

    override fun openInputStream(path: SandboxPathInfo): InputStream = unsupported("openInputStream")

    override fun writeBytes(path: SandboxPathInfo, content: ByteArray): Unit = unsupported("writeBytes")

    override fun writeText(path: SandboxPathInfo, content: String): Unit = unsupported("writeText")

    override fun writeTextAtomically(path: SandboxPathInfo, content: String, logger: Logger): Unit =
        unsupported("writeTextAtomically")

    override fun createDirectory(path: SandboxPathInfo): Unit = unsupported("createDirectory")

    override fun delete(path: SandboxPathInfo, recursively: Boolean): Unit = unsupported("delete")

    override fun move(
        source: SandboxPathInfo,
        destination: SandboxPathInfo,
        replaceExisting: Boolean,
        createParents: Boolean,
        logger: Logger?,
    ): Unit = unsupported("move")

    override fun moveToTrash(path: SandboxPathInfo, logger: Logger?): SandboxPathInfo = unsupported("moveToTrash")

    private fun unsupported(operation: String): Nothing = throw BadInputException(
        "$operation is not supported for the Salute sandbox: the backend and the speaker do not share a filesystem."
    )
}
