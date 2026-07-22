package ru.souz.backend.salute.sandbox

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import ru.souz.backend.salute.SaluteDeviceConnectionRegistry

class RegistryBackedSaluteConnectedDeviceResolverTest {
    @Test
    fun `unknown user resolves to not a salute user`() {
        val resolver = RegistryBackedSaluteConnectedDeviceResolver(SaluteDeviceConnectionRegistry())

        assertEquals(SaluteDeviceResolution.NotASaluteUser, resolver.resolveForUser("stranger"))
    }

    @Test
    fun `bound but disconnected device resolves to not connected`() {
        val registry = SaluteDeviceConnectionRegistry()
        val session = mockk<DefaultWebSocketServerSession>()
        registry.register("device-1", session)
        registry.recordDeviceOwner("device-1", "user-1")
        registry.unregister("device-1", session)
        val resolver = RegistryBackedSaluteConnectedDeviceResolver(registry)

        assertEquals(SaluteDeviceResolution.NotConnected(setOf("device-1")), resolver.resolveForUser("user-1"))
    }

    @Test
    fun `single connected bound device resolves`() {
        val registry = SaluteDeviceConnectionRegistry()
        val session = mockk<DefaultWebSocketServerSession>()
        registry.register("device-1", session)
        registry.recordDeviceOwner("device-1", "user-1")
        val resolver = RegistryBackedSaluteConnectedDeviceResolver(registry)

        assertEquals(SaluteDeviceResolution.Resolved("device-1"), resolver.resolveForUser("user-1"))
    }

    @Test
    fun `two connected bound devices resolve as ambiguous`() {
        val registry = SaluteDeviceConnectionRegistry()
        val sessionA = mockk<DefaultWebSocketServerSession>()
        val sessionB = mockk<DefaultWebSocketServerSession>()
        registry.register("device-a", sessionA)
        registry.register("device-b", sessionB)
        registry.recordDeviceOwner("device-a", "user-1")
        registry.recordDeviceOwner("device-b", "user-1")
        val resolver = RegistryBackedSaluteConnectedDeviceResolver(registry)

        assertIs<SaluteDeviceResolution.Ambiguous>(resolver.resolveForUser("user-1"))
    }
}
