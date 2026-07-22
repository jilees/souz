package ru.souz.backend.salute.sandbox

import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.backend.salute.SaluteDeviceDisconnectedException
import ru.souz.backend.salute.SaluteDeviceMessage
import ru.souz.backend.salute.SaluteDevicePusher
import ru.souz.backend.salute.SaluteExecRequestRegistry
import ru.souz.runtime.sandbox.SandboxCommandExecutor
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.tool.BadInputException

/**
 * Executes commands by pushing an `exec` frame to a connected Salute device and awaiting the
 * matching `exec_result` via [execRequestRegistry]. Unlike Local/Docker/Android, the device
 * shares no filesystem with the backend: `scriptPath` is never sent as a path — its content is
 * read here (from [contentFileSystem], the same backend-local store `LOCAL` mode uses) and
 * embedded as an inline script. `workingDirectory` and `environment` (including
 * `SOUZ_SKILL_ROOT`/`SOUZ_SKILL_ID`/`SOUZ_SKILL_SUPPORTING_FILES`) are silently dropped — the
 * device wire protocol (`SaluteDeviceMessage.exec`) has no fields for them, and extending it
 * would require a matching change in the separate `souz-thin-client` deployable.
 */
class SaluteSandboxCommandExecutor(
    private val deviceId: String,
    private val contentFileSystem: SandboxFileSystem,
    private val devicePusher: SaluteDevicePusher,
    private val execRequestRegistry: SaluteExecRequestRegistry,
) : SandboxCommandExecutor {
    override suspend fun execute(request: SandboxCommandRequest): SandboxCommandResult {
        val argv = request.toDeviceArgv()
        val timeoutMillis = request.timeoutMillis ?: DEFAULT_TIMEOUT_MILLIS
        val id = UUID.randomUUID().toString()
        val deferred = execRequestRegistry.beginRequest(deviceId, id)
        val sent = devicePusher.sendExec(deviceId, SaluteDeviceMessage.exec(id = id, argv = argv, timeoutMs = timeoutMillis))
        if (!sent) {
            execRequestRegistry.discard(id)
            return SandboxCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Salute device $deviceId is not connected.",
                timedOut = false,
            )
        }
        return try {
            withTimeoutOrNull(timeoutMillis) { deferred.await() }
                ?: SandboxCommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Salute device $deviceId did not respond in time.",
                    timedOut = true,
                )
        } catch (e: SaluteDeviceDisconnectedException) {
            SandboxCommandResult(exitCode = -1, stdout = "", stderr = e.message.orEmpty(), timedOut = false)
        } finally {
            execRequestRegistry.discard(id)
        }
    }

    private fun SandboxCommandRequest.toDeviceArgv(): List<String> {
        val inlineContent = scriptPath
            ?.let { path -> contentFileSystem.readText(contentFileSystem.resolveExistingFile(path)) }
            ?: script
        return when (runtime) {
            SandboxCommandRuntime.PROCESS ->
                command.ifEmpty { throw BadInputException("command must not be empty for PROCESS runtime.") }

            SandboxCommandRuntime.BASH -> listOf(
                DEVICE_SHELL,
                "-c",
                requireNotNull(inlineContent) { "script or scriptPath is required for BASH runtime." },
            ) + args

            SandboxCommandRuntime.PYTHON -> listOf(
                "python3",
                "-c",
                requireNotNull(inlineContent) { "script or scriptPath is required for PYTHON runtime." },
            ) + args

            SandboxCommandRuntime.NODE -> listOf(
                "node",
                "-e",
                requireNotNull(inlineContent) { "script or scriptPath is required for NODE runtime." },
            ) + args
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        // POSIX sh, not bash: StarOS/embedded speaker firmware is not guaranteed to ship bash
        // (same reasoning as AndroidSkillCommandExecutor's /system/bin/sh, and the tv-control
        // skill's explicit "sh, не bash" note). Scripts targeting Salute must stay POSIX-only.
        const val DEVICE_SHELL = "sh"
    }
}
