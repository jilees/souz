package ru.souz.backend.channels

import java.util.UUID
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.salute.SaluteDeviceBindingRepository
import ru.souz.backend.salute.SaluteDeviceCommands
import ru.souz.backend.salute.SaluteDeviceMessage
import ru.souz.backend.salute.SaluteDevicePusher

/**
 * Channel provider for Salute voice devices. Unlike Telegram/PublicClient, `channelId` here is the
 * raw `deviceId`, not a chat id — device push (`SaluteDevicePusher`) addresses by device, and a user
 * may have several bound devices, each independently listed and independently addressable (never
 * resolved via [ru.souz.backend.salute.sandbox.SaluteConnectedDeviceResolver], which was built to
 * resolve the single device a Salute-originated turn is already running on and returns `Ambiguous`
 * for more than one connected device — exactly wrong for a tool that always receives an explicit
 * target `channelId` chosen from `ListActiveChannels`' output).
 */
class SaluteChannelProvider(
    private val bindingRepository: SaluteDeviceBindingRepository,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val eventService: AgentEventService,
    private val devicePusher: SaluteDevicePusher,
) : ChannelProvider {
    override fun supports(channelType: String): Boolean = channelType == CHANNEL_TYPE

    override suspend fun listChannels(userId: String): List<ChannelDescriptor> =
        bindingRepository.listForUser(userId).map { binding ->
            val title = chatRepository.getById(binding.chatId)?.title
            ChannelDescriptor(CHANNEL_TYPE, binding.deviceId, title ?: "Salute: ${binding.deviceId}")
        }

    override suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult {
        val binding = bindingRepository.getByDeviceId(channelId)?.takeIf { it.userId == userId }
            ?: return ChannelSendResult.Failed("Salute device not found for this user.")
        if (!devicePusher.isConnected(channelId)) {
            return ChannelSendResult.Failed("Salute device is not currently connected.")
        }
        val execMessage = SaluteDeviceMessage.exec(
            id = UUID.randomUUID().toString(),
            argv = SaluteDeviceCommands.speak(text),
            timeoutMs = EXEC_TIMEOUT_MS,
        )
        if (!devicePusher.sendExec(channelId, execMessage)) {
            return ChannelSendResult.Failed("Failed to deliver message to Salute device.")
        }
        persistChannelMessage(messageRepository, eventService, userId, binding.chatId, text)
        return ChannelSendResult.Delivered("Sent via Salute (spoken).")
    }

    private companion object {
        const val CHANNEL_TYPE = "salute"
        const val EXEC_TIMEOUT_MS: Long = 10_000L
    }
}
