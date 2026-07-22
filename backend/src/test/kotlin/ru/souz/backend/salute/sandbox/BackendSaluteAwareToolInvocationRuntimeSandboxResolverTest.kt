package ru.souz.backend.salute.sandbox

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import ru.souz.backend.salute.SaluteDeviceConnectionRegistry
import ru.souz.backend.salute.SaluteDevicePusher
import ru.souz.backend.salute.SaluteExecRequestRegistry
import ru.souz.db.SettingsProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxMode
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.BadInputException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class BackendSaluteAwareToolInvocationRuntimeSandboxResolverTest {
    private val createdPaths = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        createdPaths.asReversed().forEach { runCatching { it.toFile().deleteRecursively() } }
        createdPaths.clear()
    }

    @Test
    fun `not a salute user delegates to fallback`() {
        val fallbackSandbox = mockk<RuntimeSandbox> { every { mode } returns SandboxMode.LOCAL }
        val resolver = resolver(
            fallback = ToolInvocationRuntimeSandboxResolver.fixed(fallbackSandbox),
            deviceResolver = fakeDeviceResolver { SaluteDeviceResolution.NotASaluteUser },
        )

        val result = resolver.resolve(ToolInvocationMeta(userId = "user-1"))

        assertEquals(fallbackSandbox, result)
    }

    @Test
    fun `resolved device returns a salute sandbox`() {
        val resolver = resolver(
            deviceResolver = fakeDeviceResolver { SaluteDeviceResolution.Resolved("device-1") },
        )

        val result = resolver.resolve(ToolInvocationMeta(userId = "user-1"))

        assertIs<SaluteRuntimeSandbox>(result)
        assertEquals(SandboxMode.SALUTE, result.mode)
        assertEquals("device-1", result.deviceId)
    }

    @Test
    fun `not connected throws bad input`() {
        val resolver = resolver(
            deviceResolver = fakeDeviceResolver { SaluteDeviceResolution.NotConnected(setOf("device-1")) },
        )

        assertFailsWith<BadInputException> { resolver.resolve(ToolInvocationMeta(userId = "user-1")) }
    }

    @Test
    fun `ambiguous devices throws bad input`() {
        val resolver = resolver(
            deviceResolver = fakeDeviceResolver { SaluteDeviceResolution.Ambiguous(setOf("device-1", "device-2")) },
        )

        assertFailsWith<BadInputException> { resolver.resolve(ToolInvocationMeta(userId = "user-1")) }
    }

    @Test
    fun `explicit device id attribute takes precedence but must be connected`() {
        val resolver = resolver(
            deviceResolver = object : SaluteConnectedDeviceResolver {
                override fun resolveForUser(userId: String) = SaluteDeviceResolution.Resolved("device-other")
                override fun isConnected(deviceId: String) = deviceId == "device-explicit-connected"
            },
        )

        val result = resolver.resolve(
            ToolInvocationMeta(
                userId = "user-1",
                attributes = mapOf(SaluteToolAttributes.DEVICE_ID to "device-explicit-connected"),
            )
        )
        assertIs<SaluteRuntimeSandbox>(result)
        assertEquals("device-explicit-connected", result.deviceId)

        assertFailsWith<BadInputException> {
            resolver.resolve(
                ToolInvocationMeta(
                    userId = "user-1",
                    attributes = mapOf(SaluteToolAttributes.DEVICE_ID to "device-explicit-disconnected"),
                )
            )
        }
    }

    private fun resolver(
        fallback: ToolInvocationRuntimeSandboxResolver = ToolInvocationRuntimeSandboxResolver.fixed(
            mockk<RuntimeSandbox> { every { mode } returns SandboxMode.LOCAL }
        ),
        deviceResolver: SaluteConnectedDeviceResolver,
    ): BackendSaluteAwareToolInvocationRuntimeSandboxResolver =
        BackendSaluteAwareToolInvocationRuntimeSandboxResolver(
            fallback = fallback,
            deviceResolver = deviceResolver,
            saluteSandboxes = provider(),
        )

    private fun provider(): SaluteRuntimeSandboxProvider {
        val settingsProvider = mockk<SettingsProvider> { every { forbiddenFolders } returns emptyList() }
        val home = createTempDirectory("salute-resolver-home-")
        val stateRoot = home.resolve("state")
        return SaluteRuntimeSandboxProvider(
            settingsProvider = settingsProvider,
            devicePusher = mockk<SaluteDeviceConnectionRegistry>(relaxed = true) as SaluteDevicePusher,
            execRequestRegistry = SaluteExecRequestRegistry(),
            localHomePath = home,
            localStateRoot = stateRoot,
        )
    }

    private fun fakeDeviceResolver(
        resolveForUser: (String) -> SaluteDeviceResolution,
    ): SaluteConnectedDeviceResolver = object : SaluteConnectedDeviceResolver {
        override fun resolveForUser(userId: String): SaluteDeviceResolution = resolveForUser(userId)
        override fun isConnected(deviceId: String): Boolean = true
    }

    private fun createTempDirectory(prefix: String): Path =
        Files.createTempDirectory(prefix).also(createdPaths::add)
}
