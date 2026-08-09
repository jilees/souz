package ru.souz.backend.channels

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.salute.SaluteDeviceMessage
import ru.souz.backend.salute.SaluteDevicePusher
import ru.souz.backend.testutil.repository.MemoryAgentEventRepository
import ru.souz.backend.testutil.repository.MemoryChatRepository
import ru.souz.backend.testutil.repository.MemoryMessageRepository
import ru.souz.backend.testutil.repository.MemorySaluteDeviceBindingRepository

class SaluteChannelProviderTest {
    private val userId = "user-1"

    private class FakeSaluteDevicePusher(
        private val connected: MutableSet<String> = mutableSetOf(),
        private val shouldFailExec: Boolean = false,
    ) : SaluteDevicePusher {
        val execCallsByDevice = mutableMapOf<String, MutableList<SaluteDeviceMessage>>()

        fun connect(deviceId: String) {
            connected += deviceId
        }

        override fun isConnected(deviceId: String): Boolean = deviceId in connected

        override suspend fun sendExec(deviceId: String, message: SaluteDeviceMessage): Boolean {
            if (shouldFailExec) return false
            execCallsByDevice.getOrPut(deviceId) { mutableListOf() } += message
            return true
        }
    }

    private fun provider(
        bindingRepository: MemorySaluteDeviceBindingRepository,
        chatRepository: MemoryChatRepository = MemoryChatRepository(),
        messageRepository: MemoryMessageRepository = MemoryMessageRepository(),
        eventRepository: MemoryAgentEventRepository = MemoryAgentEventRepository(),
        devicePusher: FakeSaluteDevicePusher = FakeSaluteDevicePusher(),
    ): Triple<SaluteChannelProvider, MemoryMessageRepository, MemoryAgentEventRepository> {
        val eventService = AgentEventService(chatRepository, eventRepository, AgentEventBus())
        val provider = SaluteChannelProvider(
            bindingRepository = bindingRepository,
            chatRepository = chatRepository,
            messageRepository = messageRepository,
            eventService = eventService,
            devicePusher = devicePusher,
        )
        return Triple(provider, messageRepository, eventRepository)
    }

    @Test
    fun `listChannels reflects configured bindings regardless of connectivity`() = runTest {
        val bindingRepository = MemorySaluteDeviceBindingRepository()
        bindingRepository.insertIfAbsent("dev-1", userId, UUID.randomUUID(), Instant.now())
        val (provider, _, _) = provider(bindingRepository, devicePusher = FakeSaluteDevicePusher())

        val channels = provider.listChannels(userId)

        assertEquals(listOf("dev-1"), channels.map { it.channelId })
    }

    @Test
    fun `sendMessage fails when device is not connected`() = runTest {
        val bindingRepository = MemorySaluteDeviceBindingRepository()
        bindingRepository.insertIfAbsent("dev-1", userId, UUID.randomUUID(), Instant.now())
        val pusher = FakeSaluteDevicePusher()
        val (provider, messageRepository, _) = provider(bindingRepository, devicePusher = pusher)

        val result = provider.sendMessage(userId, "dev-1", "hello")

        assertIs<ChannelSendResult.Failed>(result)
        assertEquals(0, pusher.execCallsByDevice.size)
        assertEquals(emptyList(), messageRepository.list(userId, bindingRepository.getByDeviceId("dev-1")!!.chatId, null, null, 10))
    }

    @Test
    fun `sendMessage fails for a device owned by a different user`() = runTest {
        val bindingRepository = MemorySaluteDeviceBindingRepository()
        bindingRepository.insertIfAbsent("dev-1", "other-user", UUID.randomUUID(), Instant.now())
        val pusher = FakeSaluteDevicePusher().apply { connect("dev-1") }
        val (provider, _, _) = provider(bindingRepository, devicePusher = pusher)

        val result = provider.sendMessage(userId, "dev-1", "hello")

        assertIs<ChannelSendResult.Failed>(result)
    }

    @Test
    fun `sendMessage success speaks the message and persists a durable event`() = runTest {
        val bindingRepository = MemorySaluteDeviceBindingRepository()
        val binding = bindingRepository.insertIfAbsent("dev-1", userId, UUID.randomUUID(), Instant.now())
        val pusher = FakeSaluteDevicePusher().apply { connect("dev-1") }
        val (provider, messageRepository, eventRepository) = provider(bindingRepository, devicePusher = pusher)

        val result = provider.sendMessage(userId, "dev-1", "hello")

        assertIs<ChannelSendResult.Delivered>(result)
        assertEquals(1, pusher.execCallsByDevice.getValue("dev-1").size)
        val messages = messageRepository.list(userId, binding.chatId, null, null, 10)
        assertEquals(ChatRole.ASSISTANT, messages.single().role)
        assertEquals(1, eventRepository.listByChat(userId, binding.chatId).size)
    }

    @Test
    fun `multiple connected devices are addressed independently`() = runTest {
        val bindingRepository = MemorySaluteDeviceBindingRepository()
        bindingRepository.insertIfAbsent("dev-a", userId, UUID.randomUUID(), Instant.now())
        bindingRepository.insertIfAbsent("dev-b", userId, UUID.randomUUID(), Instant.now())
        val pusher = FakeSaluteDevicePusher().apply {
            connect("dev-a")
            connect("dev-b")
        }
        val (provider, _, _) = provider(bindingRepository, devicePusher = pusher)

        val channels = provider.listChannels(userId)
        assertEquals(setOf("dev-a", "dev-b"), channels.map { it.channelId }.toSet())

        val result = provider.sendMessage(userId, "dev-a", "hello")

        assertIs<ChannelSendResult.Delivered>(result)
        assertEquals(1, pusher.execCallsByDevice.getValue("dev-a").size)
        assertEquals(null, pusher.execCallsByDevice["dev-b"])
    }
}
