package ru.souz.backend.salute

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import ru.souz.backend.chat.service.ChatService
import ru.souz.backend.testutil.repository.MemoryChatRepository
import ru.souz.backend.testutil.repository.MemoryMessageRepository
import ru.souz.backend.testutil.repository.MemorySaluteDeviceBindingRepository

class SaluteDeviceBindingServiceTest {
    @Test
    fun `binding an unclaimed device creates a chat and claims it for the caller`() = runTest {
        val bindingRepository = MemorySaluteDeviceBindingRepository()
        val service = service(bindingRepository)

        val outcome = service.bind(userId = "alice", deviceId = "device-1")

        val bound = assertIs<SaluteDeviceBindOutcome.Bound>(outcome)
        assertEquals("alice", bound.binding.userId)
        assertEquals("device-1", bound.binding.deviceId)
        assertEquals(bound.binding, bindingRepository.getByDeviceId("device-1"))
    }

    @Test
    fun `binding a device you already own is idempotent`() = runTest {
        val bindingRepository = MemorySaluteDeviceBindingRepository()
        val service = service(bindingRepository)
        service.bind(userId = "alice", deviceId = "device-1")

        val outcome = service.bind(userId = "alice", deviceId = "device-1")

        val already = assertIs<SaluteDeviceBindOutcome.AlreadyBoundToYou>(outcome)
        assertEquals("alice", already.binding.userId)
    }

    @Test
    fun `binding a device already claimed by someone else is rejected`() = runTest {
        val bindingRepository = MemorySaluteDeviceBindingRepository()
        val service = service(bindingRepository)
        service.bind(userId = "alice", deviceId = "device-1")

        val outcome = service.bind(userId = "bob", deviceId = "device-1")

        assertEquals(SaluteDeviceBindOutcome.BoundToAnotherUser, outcome)
        assertEquals("alice", bindingRepository.getByDeviceId("device-1")?.userId)
    }

    private fun service(
        bindingRepository: SaluteDeviceBindingRepository,
    ): SaluteDeviceBindingService = SaluteDeviceBindingService(
        bindingRepository = bindingRepository,
        chatService = ChatService(MemoryChatRepository(), MemoryMessageRepository()),
        clock = Clock.fixed(Instant.parse("2026-07-23T10:00:00Z"), ZoneOffset.UTC),
    )
}
