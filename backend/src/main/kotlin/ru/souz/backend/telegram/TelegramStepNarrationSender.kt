package ru.souz.backend.telegram

import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.backend.agent.runtime.StepNarrationTelegramSender
import ru.souz.backend.channels.channelTextChunks

/**
 * Pushes an agent-loop narration line to the linked Telegram chat via a bare `sendMessage`,
 * without the [ru.souz.backend.channels.ChannelDeliveryService] persistence that
 * [ru.souz.backend.channels.TelegramChannelProvider] applies — step narration is transient.
 */
class TelegramStepNarrationSender(
    private val bindingRepository: TelegramBotBindingRepository,
    private val telegramBotApi: TelegramBotApi,
    private val tokenCrypto: TelegramBotTokenCrypto,
) : StepNarrationTelegramSender {
    private val logger = LoggerFactory.getLogger(TelegramStepNarrationSender::class.java)

    override suspend fun trySend(userId: String, chatId: UUID, text: String) {
        val binding = bindingRepository.getByUserAndChat(userId, chatId)?.takeIf { it.active } ?: return
        val telegramChatId = binding.telegramChatId ?: return
        val token = try {
            tokenCrypto.decrypt(binding.botTokenEncrypted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Step narration: Telegram token decrypt failed for chat {}", chatId)
            return
        }
        for (chunk in channelTextChunks(text)) {
            try {
                telegramBotApi.sendMessage(token, telegramChatId, chunk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Step narration: Telegram send failed for chat {}: {}", chatId, e.message)
                return
            }
        }
    }
}
