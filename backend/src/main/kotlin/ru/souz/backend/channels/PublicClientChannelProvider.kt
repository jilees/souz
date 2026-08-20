package ru.souz.backend.channels

import java.util.UUID
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.events.service.AgentEventService

/**
 * Channel provider for chats reached through the public Client–Souz WebSocket contract, covering
 * every `clientType` (`backend`, `mobile_app`, and future WS-onboarded types alike) not already
 * claimed by a more specific provider — registered last in `ChannelProviderRegistry` as the
 * catch-all fallback.
 */
class PublicClientChannelProvider(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val eventService: AgentEventService,
    /** True if [chatId] is already advertised by a more specific provider (e.g. Telegram). */
    private val isClaimedByAnotherProvider: suspend (chatId: UUID) -> Boolean,
) : ChannelProvider {
    override fun supports(channelType: String): Boolean = true

    override suspend fun listChannels(userId: String): List<ChannelDescriptor> =
        chatRepository.list(userId, includeArchived = false)
            .filter { !isClaimedByAnotherProvider(it.id) }
            .map { chat -> ChannelDescriptor(chat.clientType, chat.id.toString(), chat.title ?: chat.clientType) }

    override suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult {
        val chatId = runCatching { UUID.fromString(channelId) }.getOrNull()
            ?: return ChannelSendResult.Failed("Invalid channel id.")
        val chat = chatRepository.get(userId, chatId)
            ?.takeIf { !isClaimedByAnotherProvider(chatId) }
            ?: return ChannelSendResult.Failed("Channel not found for this user.")
        persistChannelMessage(messageRepository, eventService, userId, chat.id, text)
        return ChannelSendResult.Delivered("Sent to ${chat.title ?: chat.clientType}.")
    }
}
