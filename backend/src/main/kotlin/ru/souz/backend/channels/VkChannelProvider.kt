package ru.souz.backend.channels

import kotlinx.coroutines.CancellationException
import ru.souz.backend.vk.VkMarkdown
import ru.souz.backend.vk.VkBotApi
import ru.souz.backend.storage.postgres.PostgresVkBotBindingRepository
import ru.souz.backend.vk.VkBotTokenCrypto

class VkChannelProvider(
    private val bindingRepository: PostgresVkBotBindingRepository,
    private val deliveryService: ChannelDeliveryService,
    private val vkBotApi: VkBotApi,
    private val tokenCrypto: VkBotTokenCrypto,
    private val attachmentStorage: ru.souz.backend.attachments.AttachmentStorage? = null,
    private val attachmentDelivery: ru.souz.backend.attachments.AttachmentDelivery? = null,
) : ChannelProvider {
    override val channelType: String = "vk"

    override suspend fun sendAttachments(userId: String, channelId: String, text: String, fileIds: List<String>, deliveryKey: String?): ChannelSendResult {
        if (fileIds.isEmpty()) return sendMessage(userId, channelId, text)
        val storage = attachmentStorage ?: return ChannelSendResult.Failed("Attachment storage is unavailable.")
        val delivery = attachmentDelivery ?: return ChannelSendResult.Failed("Attachment delivery is unavailable.")
        val chatId = channelId.toChannelUuidOrNull() ?: return ChannelSendResult.Failed("Invalid channel id.")
        val binding = bindingRepository.getByUserAndChat(userId, chatId)?.takeIf { it.active }
            ?: return ChannelSendResult.Failed("VK channel not found or not linked.")
        deliveryService.resolveTarget(userId, chatId) ?: return ChannelSendResult.Failed("Target chat is unavailable.")
        return try {
            val token = tokenCrypto.decrypt(binding.groupTokenEncrypted)
            val target = requireNotNull(binding.vkPeerId)
            deliveryService.sendFiles(userId, chatId, text, fileIds, "VK", 4_000, storage, delivery, deliveryKey,
                sendText = { chunk -> for (part in VkMarkdown(chunk).chunks()) vkBotApi.sendMessage(token, target, part.text, part.format) },
                sendFile = { file, bytes -> vkBotApi.sendFile(token, target, file, bytes).toString() })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ChannelSendResult.Failed("VK attachment delivery failed; some parts may have been sent.") }
    }

    override suspend fun listChannels(userId: String): List<ChannelDescriptor> {
        val bindings = bindingRepository.listForUser(userId).filter { it.active }
        val chatsById = deliveryService.resolveTargets(userId, bindings.map { it.chatId })
        return bindings.mapNotNull { binding ->
            val chat = chatsById[binding.chatId] ?: return@mapNotNull null
            ChannelDescriptor(
                channelType = channelType,
                channelId = binding.chatId.toString(),
                label = chat.title ?: binding.vkFirstName ?: binding.vkGroupName ?: "VK",
            )
        }
    }

    override suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult {
        val chatId = channelId.toChannelUuidOrNull()
            ?: return ChannelSendResult.Failed("Invalid channel id.")
        val binding = bindingRepository.getByUserAndChat(userId, chatId)?.takeIf { it.active }
            ?: return ChannelSendResult.Failed("VK channel not found or not linked.")
        deliveryService.resolveTarget(userId, chatId)
            ?: return ChannelSendResult.Failed("VK channel not found or not linked.")
        val peerId = binding.vkPeerId
            ?: return ChannelSendResult.Failed("VK channel not found or not linked.")
        val token = try {
            tokenCrypto.decrypt(binding.groupTokenEncrypted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ChannelSendResult.Failed("VK delivery failed.")
        }
        return deliveryService.sendChunks(
            userId, chatId, text, "VK", VkMarkdown(text).chunks(), { it.text },
        ) { chunk ->
            vkBotApi.sendMessage(token, peerId, chunk.text, chunk.format)
        }
    }
}
