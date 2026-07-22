package ru.souz.backend.salute.sandbox

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import ru.souz.backend.salute.SaluteDevicePusher
import ru.souz.backend.salute.SaluteExecRequestRegistry
import ru.souz.db.SettingsProvider
import ru.souz.paths.DefaultSouzPaths
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxCommandExecutor
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxMode
import ru.souz.runtime.sandbox.SandboxRuntimePaths
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.local.createLocalSandboxFileSystem

class SaluteRuntimeSandbox(
    override val scope: SandboxScope,
    val deviceId: String,
    override val runtimePaths: SandboxRuntimePaths,
    contentFileSystem: SandboxFileSystem,
    devicePusher: SaluteDevicePusher,
    execRequestRegistry: SaluteExecRequestRegistry,
) : RuntimeSandbox {
    override val mode: SandboxMode = SandboxMode.SALUTE
    override val fileSystem: SandboxFileSystem = SaluteSandboxFileSystem(contentFileSystem)
    override val commandExecutor: SandboxCommandExecutor = SaluteSandboxCommandExecutor(
        deviceId = deviceId,
        contentFileSystem = contentFileSystem,
        devicePusher = devicePusher,
        execRequestRegistry = execRequestRegistry,
    )
}

/**
 * Builds/caches [SaluteRuntimeSandbox] instances per (userId, deviceId), mirroring
 * `FactoryBackedToolInvocationRuntimeSandboxResolver`'s cache (same "no eviction yet" caveat —
 * intentionally not solved here). Uses the same backend-local paths LOCAL mode uses by default
 * (`runtimeToolsDiModule`), so skill bundles resolve to the same on-disk locations
 * `ToolRunSkillCommand` already writes to.
 */
class SaluteRuntimeSandboxProvider(
    private val settingsProvider: SettingsProvider,
    private val devicePusher: SaluteDevicePusher,
    private val execRequestRegistry: SaluteExecRequestRegistry,
    private val localHomePath: Path = DefaultSouzPaths.homeDirectory(),
    private val localStateRoot: Path = DefaultSouzPaths.defaultStateRoot(),
) {
    private val runtimePaths: SandboxRuntimePaths = SandboxRuntimePaths(
        homePath = localHomePath.toAbsolutePath().normalize().toString(),
        workspaceRootPath = null,
        stateRootPath = localStateRoot.toAbsolutePath().normalize().toString(),
        sessionsDirPath = localStateRoot.resolve("sessions").toAbsolutePath().normalize().toString(),
        vectorIndexDirPath = localStateRoot.resolve("vector-index").toAbsolutePath().normalize().toString(),
        logsDirPath = localStateRoot.resolve("logs").toAbsolutePath().normalize().toString(),
        modelsDirPath = localStateRoot.resolve("models").toAbsolutePath().normalize().toString(),
        nativeLibsDirPath = localStateRoot.resolve("native").toAbsolutePath().normalize().toString(),
        skillsDirPath = localStateRoot.resolve("skills").toAbsolutePath().normalize().toString(),
        skillValidationsDirPath = localStateRoot.resolve("skill-validations").toAbsolutePath().normalize().toString(),
    )
    private val contentFileSystem: SandboxFileSystem = createLocalSandboxFileSystem(settingsProvider, runtimePaths)
    private val sandboxes = ConcurrentHashMap<Pair<String, String>, SaluteRuntimeSandbox>()

    fun get(userId: String, deviceId: String): SaluteRuntimeSandbox =
        sandboxes.computeIfAbsent(userId to deviceId) {
            SaluteRuntimeSandbox(
                scope = SandboxScope(userId = userId),
                deviceId = deviceId,
                runtimePaths = runtimePaths,
                contentFileSystem = contentFileSystem,
                devicePusher = devicePusher,
                execRequestRegistry = execRequestRegistry,
            )
        }
}
