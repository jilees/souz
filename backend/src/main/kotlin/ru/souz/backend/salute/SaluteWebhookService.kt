package ru.souz.backend.salute

import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.souz.backend.chat.service.ChatService
import ru.souz.backend.chat.service.SendMessageResult
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.settings.service.UserSettingsOverrides

/** Mirrors `TelegramTurnExecutor` — decouples the Salute flow from the concrete execution kernel so tests don't need to stand up a full `AgentExecutionService`. */
fun interface SaluteTurnExecutor {
    suspend fun execute(
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String,
        requestOverrides: UserSettingsOverrides,
    ): SendMessageResult
}

private class AgentExecutionSaluteTurnExecutor(
    private val executionService: AgentExecutionService,
) : SaluteTurnExecutor {
    override suspend fun execute(
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String,
        requestOverrides: UserSettingsOverrides,
    ): SendMessageResult =
        executionService.executeChatTurn(
            userId = userId,
            chatId = chatId,
            content = content,
            clientMessageId = clientMessageId,
            requestOverrides = requestOverrides,
        )
}

/**
 * Handles the Salute (Sber SmartApp) webhook and orchestrates the async agent turn + exec push
 * to the thin client. Mirrors the 4-branch flow of the original picoclaw-voice-scenario-backend
 * prototype (`app.py`), but runs the agent turn on this backend instead of relaying raw text to
 * a device-local agent — see the plan's Context section for why.
 */
class SaluteWebhookService(
    private val bindingRepository: SaluteDeviceBindingRepository,
    private val chatService: ChatService,
    private val connectionRegistry: SaluteDevicePusher,
    private val turnExecutor: SaluteTurnExecutor,
    private val applicationScope: CoroutineScope,
    private val defaultUserId: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    constructor(
        bindingRepository: SaluteDeviceBindingRepository,
        chatService: ChatService,
        connectionRegistry: SaluteDeviceConnectionRegistry,
        executionService: AgentExecutionService,
        applicationScope: CoroutineScope,
        defaultUserId: String,
        clock: Clock = Clock.systemUTC(),
    ) : this(
        bindingRepository = bindingRepository,
        chatService = chatService,
        connectionRegistry = connectionRegistry,
        turnExecutor = AgentExecutionSaluteTurnExecutor(executionService),
        applicationScope = applicationScope,
        defaultUserId = defaultUserId,
        clock = clock,
    )
    private val logger = LoggerFactory.getLogger(SaluteWebhookService::class.java)
    private val pendingEndSessions = ConcurrentHashMap.newKeySet<String>()

    suspend fun handleWebhook(request: SaluteWebhookRequest): SaluteWebhookResponse {
        val deviceId = request.payload.device?.deviceId?.trim()
        if (deviceId.isNullOrEmpty()) {
            return textResponse(request, NOT_CONNECTED_MESSAGE, finished = true)
        }
        if (request.payload.newSession) {
            return textResponse(request, GREETING_MESSAGE, finished = false)
        }
        if (pendingEndSessions.remove(deviceId)) {
            return textResponse(request, SESSION_ENDED_MESSAGE, finished = true)
        }
        if (!connectionRegistry.isConnected(deviceId)) {
            return textResponse(request, NOT_CONNECTED_MESSAGE, finished = true)
        }
        val text = request.payload.message?.originalText?.trim().orEmpty()
        if (text.isNotEmpty()) {
            applicationScope.launch {
                runTurn(deviceId = deviceId, sessionId = request.sessionId, messageId = request.messageId, text = text)
            }
        }
        return silentResponse(request)
    }

    fun markEndSession(deviceId: String) {
        pendingEndSessions.add(deviceId)
    }

    fun handleExecResult(deviceId: String, message: SaluteDeviceMessage) {
        logger.debug(
            "Salute device {} exec_result id={} exitCode={} timedOut={}",
            deviceId,
            message.id,
            message.exitCode,
            message.timedOut,
        )
    }

    private suspend fun runTurn(deviceId: String, sessionId: String, messageId: Long, text: String) {
        val binding = try {
            getOrCreateBinding(deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to resolve Salute binding for device {}: {}", deviceId, e.message)
            return
        }

        SaluteDeviceCommands.waitingIndicatorOn()?.let { argv ->
            connectionRegistry.sendExec(deviceId, execMessage(argv))
        }
        connectionRegistry.sendExec(deviceId, execMessage(SaluteDeviceCommands.speak(randomThinkingPhrase())))

        val result = try {
            turnExecutor.execute(
                userId = binding.userId,
                chatId = binding.chatId,
                content = text,
                clientMessageId = "salute:${binding.id}:$messageId",
                requestOverrides = UserSettingsOverrides(streamingMessages = false),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Salute agent turn failed for device {}: {}", deviceId, e.message)
            null
        }

        SaluteDeviceCommands.waitingIndicatorOff()?.let { argv ->
            connectionRegistry.sendExec(deviceId, execMessage(argv))
        }
        val answer = result?.assistantMessage?.content?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_REPLY
        connectionRegistry.sendExec(deviceId, execMessage(SaluteDeviceCommands.speak(answer)))
    }

    private suspend fun getOrCreateBinding(deviceId: String): SaluteDeviceBinding {
        bindingRepository.getByDeviceId(deviceId)?.let { existing ->
            bindingRepository.touchLastSeen(existing.id, clock.instant())
            return existing
        }
        val chat = chatService.create(userId = defaultUserId, title = "Salute: $deviceId")
        return bindingRepository.insertIfAbsent(
            deviceId = deviceId,
            userId = defaultUserId,
            chatId = chat.id,
            now = clock.instant(),
        )
    }

    private fun execMessage(argv: List<String>): SaluteDeviceMessage =
        SaluteDeviceMessage.exec(id = UUID.randomUUID().toString(), argv = argv, timeoutMs = EXEC_TIMEOUT_MS)

    private fun randomThinkingPhrase(): String = THINKING_PHRASES[Random.nextInt(THINKING_PHRASES.size)]

    private fun silentResponse(request: SaluteWebhookRequest): SaluteWebhookResponse =
        SaluteWebhookResponse(
            sessionId = request.sessionId,
            messageId = request.messageId,
            uuid = request.uuid,
            payload = SaluteResponsePayload(pronounceText = "", finished = false),
        )

    private fun textResponse(
        request: SaluteWebhookRequest,
        text: String,
        finished: Boolean,
    ): SaluteWebhookResponse =
        SaluteWebhookResponse(
            sessionId = request.sessionId,
            messageId = request.messageId,
            uuid = request.uuid,
            payload = SaluteResponsePayload(
                pronounceText = text,
                finished = finished,
                items = listOf(SaluteResponseItem(SaluteBubble(text))),
            ),
        )

    private companion object {
        const val EXEC_TIMEOUT_MS: Long = 10_000L
        const val GREETING_MESSAGE = "Навык связи с Souz запущен. Задайте вопрос."
        const val NOT_CONNECTED_MESSAGE = "Устройство не подключено. Попробуйте позже."
        const val SESSION_ENDED_MESSAGE = "Сеанс завершён."
        const val FALLBACK_REPLY = "Готово."
        val THINKING_PHRASES = listOf(
            "Секунду...",
            "Сейчас посмотрю.",
            "Момент, думаю.",
        )
    }
}
