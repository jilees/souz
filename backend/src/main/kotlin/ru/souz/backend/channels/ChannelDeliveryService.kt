package ru.souz.backend.channels

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.backend.attachments.toContent
import ru.souz.backend.crypto.sha256Hex
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.model.CROSS_CHANNEL_MESSAGE_METADATA_KEY
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.MessageCreatedPayload
import ru.souz.backend.events.service.AgentEventService

/**
 * Shared by every [ChannelProvider]: resolves/validates forward targets and persists a delivered
 * cross-channel push into the target chat, so providers themselves only discover targets and do
 * the external delivery.
 */
class ChannelDeliveryService(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val eventService: AgentEventService,
) {
    private val logger = LoggerFactory.getLogger(ChannelDeliveryService::class.java)

    /** An owned, unarchived chat — the only kind of target a message can be forwarded into. */
    suspend fun resolveTarget(userId: String, chatId: UUID): Chat? =
        chatRepository.get(userId, chatId)?.takeUnless { it.archived }

    /** Batch form of [resolveTarget]; a missing or unowned/archived id is simply absent from the result. */
    suspend fun resolveTargets(userId: String, chatIds: List<UUID>): Map<UUID, Chat> =
        chatRepository.getByIds(chatIds)
            .filter { it.userId == userId && !it.archived }
            .associateBy { it.id }

    /** Preserve source Markdown on complete delivery, and only accepted text on partial delivery. */
    internal suspend fun <T> sendChunks(
        userId: String,
        chatId: UUID,
        sourceText: String,
        channelName: String,
        chunks: List<T>,
        textOf: (T) -> String,
        send: suspend (T) -> Unit,
    ): ChannelSendResult {
        val sent = StringBuilder()
        var sentCount = 0
        return try {
            for (chunk in chunks) {
                send(chunk)
                sent.append(textOf(chunk))
                sentCount++
            }
            ChannelSendResult.Delivered("Sent via $channelName.")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ChannelSendResult.Failed("$channelName delivery failed after $sentCount/${chunks.size} part(s).")
        } finally {
            if (sent.isNotEmpty()) {
                withContext(NonCancellable) {
                    deliver(userId, chatId, if (sentCount == chunks.size) sourceText else sent.toString())
                }
            }
        }
    }

    internal suspend fun sendFiles(
        userId: String, chatId: UUID, text: String, fileIds: List<String>, channel: String, textLimit: Int,
        storage: ru.souz.backend.attachments.AttachmentStorage,
        delivery: ru.souz.backend.attachments.AttachmentDelivery,
        deliveryKey: String?, sendText: suspend (String) -> Unit,
        sendFile: suspend (ru.souz.llms.FileContent, ByteArray) -> String,
        documentTextLimit: Int = textLimit,
        sendDocumentWithText: (suspend (ru.souz.llms.FileContent, ByteArray, String) -> String)? = null,
    ): ChannelSendResult {
        val sentText = StringBuilder()
        val sentFiles = mutableListOf<ru.souz.llms.FileContent>()
        val key = "forward:$channel:$chatId:${deliveryKey ?: UUID.randomUUID()}:" +
            ru.souz.llms.restJsonMapper.writeValueAsString(listOf(text, fileIds)).sha256Hex()
        val historyId = UUID.nameUUIDFromBytes(key.toByteArray())
        var complete = false
        return try {
            require(fileIds.size <= 10)
            val files = fileIds.distinct().map {
                val saved = storage.get(userId, ru.souz.backend.attachments.attachmentId(it))
                val presentation = if (channel == "Telegram" && saved.mimeType in setOf("image/png", "image/jpeg", "image/webp")) {
                    ru.souz.llms.FilePresentation.image
                } else ru.souz.llms.FilePresentation.document
                saved.toContent(presentation)
            }
            delivery.deliver(userId, key, text, files, textLimit,
                checkOwnership = { check(resolveTarget(userId, chatId) != null) },
                onAccepted = { chunk, file -> if (chunk != null) sentText.append(chunk); if (file != null) sentFiles += file },
                sendText = sendText, sendFile = sendFile, documentTextLimit = documentTextLimit,
                sendDocumentWithText = sendDocumentWithText)
            complete = true
            ChannelSendResult.Delivered("Sent via $channel.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            logger.warn("Channel attachment delivery failed channel={} chatId={} deliveryId={} acceptedFiles={} errorType={}",
                channel, chatId, historyId, sentFiles.size, error.javaClass.simpleName)
            ChannelSendResult.Failed("$channel delivery failed; ${sentFiles.size} file(s) were accepted. Do not automatically resend an uncertain part.")
        }
        finally {
            if (sentText.isNotEmpty() || sentFiles.isNotEmpty()) withContext(NonCancellable) {
                if (messageRepository.getById(userId, chatId, historyId) == null) {
                    deliver(userId, chatId, if (complete) text else sentText.toString(), sentFiles, historyId)
                }
            }
        }
    }

    suspend fun deliver(userId: String, chatId: UUID, text: String, attachments: List<ru.souz.llms.FileContent> = emptyList(), id: UUID = UUID.randomUUID()) {
        val message = messageRepository.append(
            userId,
            chatId,
            ChatRole.ASSISTANT,
            text,
            metadata = mapOf(CROSS_CHANNEL_MESSAGE_METADATA_KEY to "true"),
            attachments = attachments,
            id = id,
        )
        chatRepository.touchUpdatedAt(userId, chatId, message.createdAt)
        eventService.append(
            userId = userId,
            chatId = chatId,
            executionId = null,
            type = AgentEventType.MESSAGE_CREATED,
            payload = MessageCreatedPayload(message.id, message.seq, message.role.value, message.content),
        )
    }
}
