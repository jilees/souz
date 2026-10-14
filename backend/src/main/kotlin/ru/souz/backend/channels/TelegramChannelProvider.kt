package ru.souz.backend.channels

import kotlinx.coroutines.CancellationException
import ru.souz.backend.telegram.TELEGRAM_RICH_TEXT_LIMIT
import ru.souz.backend.telegram.TelegramBotApi
import ru.souz.backend.telegram.TelegramBotBindingRepository
import ru.souz.backend.telegram.TelegramBotTokenCrypto

class TelegramChannelProvider(
    private val bindingRepository: TelegramBotBindingRepository,
    private val deliveryService: ChannelDeliveryService,
    private val telegramBotApi: TelegramBotApi,
    private val tokenCrypto: TelegramBotTokenCrypto,
    private val attachmentStorage: ru.souz.backend.attachments.AttachmentStorage? = null,
    private val attachmentDelivery: ru.souz.backend.attachments.AttachmentDelivery? = null,
) : ChannelProvider {
    override val channelType: String = "telegram"

    override suspend fun sendAttachments(userId: String, channelId: String, text: String, fileIds: List<String>, deliveryKey: String?): ChannelSendResult {
        if (fileIds.isEmpty()) return sendMessage(userId, channelId, text)
        val storage = attachmentStorage ?: return ChannelSendResult.Failed("Attachment storage is unavailable.")
        val delivery = attachmentDelivery ?: return ChannelSendResult.Failed("Attachment delivery is unavailable.")
        val chatId = channelId.toChannelUuidOrNull() ?: return ChannelSendResult.Failed("Invalid channel id.")
        val binding = bindingRepository.getByUserAndChat(userId, chatId)?.takeIf { it.active }
            ?: return ChannelSendResult.Failed("Telegram channel not found or not linked.")
        deliveryService.resolveTarget(userId, chatId) ?: return ChannelSendResult.Failed("Target chat is unavailable.")
        return try {
            val token = tokenCrypto.decrypt(binding.botTokenEncrypted)
            val target = requireNotNull(binding.telegramChatId)
            deliveryService.sendFiles(userId, chatId, text, fileIds, "Telegram", TELEGRAM_RICH_TEXT_LIMIT, storage, delivery, deliveryKey,
                sendText = { telegramBotApi.sendMessage(token, target, it) },
                sendFile = { file, bytes -> telegramBotApi.sendFile(token, target, file, bytes).toString() },
                documentTextLimit = ru.souz.backend.telegram.TELEGRAM_DOCUMENT_TEXT_LIMIT,
                sendDocumentWithText = { file, bytes, caption -> telegramBotApi.sendFile(token, target, file, bytes, caption).toString() })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ChannelSendResult.Failed("Telegram attachment delivery failed; some parts may have been sent.") }
    }

    override suspend fun listChannels(userId: String): List<ChannelDescriptor> {
        val bindings = bindingRepository.listForUser(userId).filter { it.active }
        val chatsById = deliveryService.resolveTargets(userId, bindings.map { it.chatId })
        return bindings.mapNotNull { binding ->
            val chat = chatsById[binding.chatId] ?: return@mapNotNull null
            ChannelDescriptor(
                channelType = channelType,
                channelId = binding.chatId.toString(),
                label = chat.title ?: binding.telegramUsername ?: binding.telegramFirstName ?: "Telegram",
            )
        }
    }

    override suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult {
        val chatId = channelId.toChannelUuidOrNull()
            ?: return ChannelSendResult.Failed("Invalid channel id.")
        val binding = bindingRepository.getByUserAndChat(userId, chatId)?.takeIf { it.active }
            ?: return ChannelSendResult.Failed("Telegram channel not found or not linked.")
        deliveryService.resolveTarget(userId, chatId)
            ?: return ChannelSendResult.Failed("Telegram channel not found or not linked.")
        val telegramChatId = binding.telegramChatId
            ?: return ChannelSendResult.Failed("Telegram channel not found or not linked.")
        val token = try {
            tokenCrypto.decrypt(binding.botTokenEncrypted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ChannelSendResult.Failed("Telegram delivery failed.")
        }
        return deliveryService.sendChunks(
            userId, chatId, text, "Telegram", channelTextChunks(text, TELEGRAM_RICH_TEXT_LIMIT), { it },
        ) { chunk ->
            telegramBotApi.sendMessage(token, telegramChatId, chunk)
        }
    }
}
