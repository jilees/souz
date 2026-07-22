package ru.souz.backend.salute

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SaluteDeviceConnectionRegistryTest {
    @Test
    fun `recordDeviceOwner survives unregister`() {
        val registry = SaluteDeviceConnectionRegistry()
        val session = mockk<DefaultWebSocketServerSession>()
        registry.register("device-1", session)
        registry.recordDeviceOwner("device-1", "user-1")

        registry.unregister("device-1", session)

        assertFalse(registry.isConnected("device-1"))
        assertEquals(setOf("device-1"), registry.boundDeviceIdsForUser("user-1"))
    }

    @Test
    fun `boundDeviceIdsForUser returns all devices owned by a user`() {
        val registry = SaluteDeviceConnectionRegistry()
        val sessionA = mockk<DefaultWebSocketServerSession>()
        val sessionB = mockk<DefaultWebSocketServerSession>()
        registry.register("device-a", sessionA)
        registry.register("device-b", sessionB)
        registry.recordDeviceOwner("device-a", "user-1")
        registry.recordDeviceOwner("device-b", "user-1")

        assertEquals(setOf("device-a", "device-b"), registry.boundDeviceIdsForUser("user-1"))
        assertTrue(registry.isConnected("device-a"))
        assertTrue(registry.isConnected("device-b"))
    }

    @Test
    fun `unknown user has no bound devices`() {
        val registry = SaluteDeviceConnectionRegistry()

        assertEquals(emptySet(), registry.boundDeviceIdsForUser("stranger"))
    }
}
