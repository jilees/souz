package ru.souz.backend.salute.sandbox

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.backend.salute.SaluteDeviceMessage
import ru.souz.backend.salute.SaluteDevicePusher
import ru.souz.backend.salute.SaluteExecRequestRegistry
import ru.souz.db.SettingsProvider
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.SandboxRuntimePaths
import ru.souz.runtime.sandbox.local.createLocalSandboxFileSystem

class SaluteSandboxCommandExecutorTest {
    private val createdPaths = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        createdPaths.asReversed().forEach { runCatching { it.toFile().deleteRecursively() } }
        createdPaths.clear()
    }

    @Test
    fun `resolves with the result delivered by a fast device reply`() = runTest {
        val registry = SaluteExecRequestRegistry()
        val pusher = ImmediateReplyPusher(registry) { id ->
            SandboxCommandResult(exitCode = 0, stdout = "hi", stderr = "", timedOut = false)
        }
        val executor = executor(pusher, registry)

        val result = executor.execute(
            SandboxCommandRequest(runtime = SandboxCommandRuntime.BASH, script = "echo hi", timeoutMillis = 1_000)
        )

        assertEquals(SandboxCommandResult(exitCode = 0, stdout = "hi", stderr = "", timedOut = false), result)
    }

    @Test
    fun `times out when the device never replies`() = runTest {
        val registry = SaluteExecRequestRegistry()
        val pusher = NeverRepliesPusher()
        val executor = executor(pusher, registry)

        val result = executor.execute(
            SandboxCommandRequest(runtime = SandboxCommandRuntime.BASH, script = "echo hi", timeoutMillis = 1_000)
        )

        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
    }

    @Test
    fun `returns immediate failure when the device is not connected`() = runTest {
        val registry = SaluteExecRequestRegistry()
        val pusher = NotConnectedPusher()
        val executor = executor(pusher, registry)

        val result = executor.execute(
            SandboxCommandRequest(runtime = SandboxCommandRuntime.BASH, script = "echo hi", timeoutMillis = 1_000)
        )

        assertEquals(-1, result.exitCode)
        assertTrue(!result.timedOut)
        assertTrue(result.stderr.contains("not connected"))
    }

    @Test
    fun `disconnect mid-flight fails the request without waiting for the full timeout`() = runTest {
        val registry = SaluteExecRequestRegistry()
        val pusher = object : SaluteDevicePusher {
            override fun isConnected(deviceId: String) = true
            override suspend fun sendExec(deviceId: String, message: SaluteDeviceMessage): Boolean {
                registry.failAllForDevice(deviceId, "disconnected")
                return true
            }
        }
        val executor = executor(pusher, registry)

        val result = executor.execute(
            SandboxCommandRequest(runtime = SandboxCommandRuntime.BASH, script = "echo hi", timeoutMillis = 60_000)
        )

        assertEquals(-1, result.exitCode)
        assertTrue(!result.timedOut)
        assertEquals("disconnected", result.stderr)
    }

    @Test
    fun `scriptPath is read on the backend and shipped as inline content, never as a path`() = runTest {
        val registry = SaluteExecRequestRegistry()
        val capturedArgv = mutableListOf<List<String>>()
        val pusher = ImmediateReplyPusher(registry, onArgv = { capturedArgv += it }) {
            SandboxCommandResult(exitCode = 0, stdout = "", stderr = "", timedOut = false)
        }
        val home = createTempDirectory("salute-executor-home-")
        val scriptsDir = home.resolve("scripts").createDirectories()
        val script = scriptsDir.resolve("run.sh")
        script.writeText("echo from-script")
        val runtimePaths = runtimePaths(home)
        val settingsProvider = mockk<SettingsProvider> { every { forbiddenFolders } returns emptyList() }
        val contentFileSystem = createLocalSandboxFileSystem(settingsProvider, runtimePaths)
        val executor = SaluteSandboxCommandExecutor(
            deviceId = "device-1",
            contentFileSystem = contentFileSystem,
            devicePusher = pusher,
            execRequestRegistry = registry,
        )

        executor.execute(
            SandboxCommandRequest(
                runtime = SandboxCommandRuntime.BASH,
                scriptPath = script.toString(),
                timeoutMillis = 1_000,
            )
        )

        val argv = capturedArgv.single()
        assertTrue(argv.any { it.contains("echo from-script") }, "argv should embed the script content: $argv")
        assertTrue(argv.none { it.contains(script.toString()) }, "argv must never reference the backend-local path")
    }

    private fun executor(pusher: SaluteDevicePusher, registry: SaluteExecRequestRegistry): SaluteSandboxCommandExecutor {
        val home = createTempDirectory("salute-executor-home-")
        val settingsProvider = mockk<SettingsProvider> { every { forbiddenFolders } returns emptyList() }
        val contentFileSystem = createLocalSandboxFileSystem(settingsProvider, runtimePaths(home))
        return SaluteSandboxCommandExecutor(
            deviceId = "device-1",
            contentFileSystem = contentFileSystem,
            devicePusher = pusher,
            execRequestRegistry = registry,
        )
    }

    private fun runtimePaths(home: Path): SandboxRuntimePaths {
        val stateRoot = home.resolve("state").createDirectories()
        return SandboxRuntimePaths(
            homePath = home.toString(),
            workspaceRootPath = null,
            stateRootPath = stateRoot.toString(),
            sessionsDirPath = stateRoot.resolve("sessions").toString(),
            vectorIndexDirPath = stateRoot.resolve("vector-index").toString(),
            logsDirPath = stateRoot.resolve("logs").toString(),
            modelsDirPath = stateRoot.resolve("models").toString(),
            nativeLibsDirPath = stateRoot.resolve("native").toString(),
            skillsDirPath = stateRoot.resolve("skills").toString(),
            skillValidationsDirPath = stateRoot.resolve("skill-validations").toString(),
        )
    }

    private fun createTempDirectory(prefix: String): Path =
        Files.createTempDirectory(prefix).also(createdPaths::add)

    private class ImmediateReplyPusher(
        private val registry: SaluteExecRequestRegistry,
        private val onArgv: (List<String>) -> Unit = {},
        private val result: (String) -> SandboxCommandResult,
    ) : SaluteDevicePusher {
        override fun isConnected(deviceId: String) = true
        override suspend fun sendExec(deviceId: String, message: SaluteDeviceMessage): Boolean {
            onArgv(message.argv.orEmpty())
            val id = requireNotNull(message.id)
            registry.complete(id, result(id))
            return true
        }
    }

    private class NeverRepliesPusher : SaluteDevicePusher {
        val sent = ConcurrentHashMap.newKeySet<String>()
        override fun isConnected(deviceId: String) = true
        override suspend fun sendExec(deviceId: String, message: SaluteDeviceMessage): Boolean {
            sent += message.id.orEmpty()
            return true
        }
    }

    private class NotConnectedPusher : SaluteDevicePusher {
        override fun isConnected(deviceId: String) = false
        override suspend fun sendExec(deviceId: String, message: SaluteDeviceMessage): Boolean = false
    }
}
