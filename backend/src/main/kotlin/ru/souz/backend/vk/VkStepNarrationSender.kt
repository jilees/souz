package ru.souz.backend.vk

import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.backend.agent.runtime.StepNarrationVkSender
import ru.souz.backend.channels.channelTextChunks
import ru.souz.backend.storage.postgres.PostgresVkBotBindingRepository

/**
 * Pushes an agent-loop narration line to the linked VK chat via a bare `sendMessage`, without the
 * [ru.souz.backend.channels.ChannelDeliveryService] persistence that
 * [ru.souz.backend.channels.VkChannelProvider] applies — step narration is transient.
 */
class VkStepNarrationSender(
    private val bindingRepository: PostgresVkBotBindingRepository,
    private val vkBotApi: VkBotApi,
    private val tokenCrypto: VkBotTokenCrypto,
) : StepNarrationVkSender {
    private val logger = LoggerFactory.getLogger(VkStepNarrationSender::class.java)

    override suspend fun trySend(userId: String, chatId: UUID, text: String) {
        val binding = bindingRepository.getByUserAndChat(userId, chatId)?.takeIf { it.active } ?: return
        val peerId = binding.vkPeerId ?: return
        val token = try {
            tokenCrypto.decrypt(binding.groupTokenEncrypted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Step narration: VK token decrypt failed for chat {}", chatId)
            return
        }
        for (chunk in channelTextChunks(text)) {
            try {
                vkBotApi.sendMessage(token, peerId, chunk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Step narration: VK send failed for chat {}: {}", chatId, e.message)
                return
            }
        }
    }
}
