package ru.souz.backend.agent.runtime

import java.util.UUID
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.AssistantStepPayload
import ru.souz.backend.events.service.AgentEventService

/**
 * Fire-and-forget delivery of one intermediate agent-loop narration line into the chat it
 * originated from. The chat has a single real listener, so both sinks are attempted and the
 * irrelevant one no-ops:
 *  - WebSocket: a live-only [AgentEventType.ASSISTANT_STEP] event (never persisted, absent from
 *    durable replay — like `message.delta`).
 *  - Telegram/VK: a plain message straight to the linked chat, best-effort, not persisted.
 */
class StepNarrationDelivery(
    private val eventService: AgentEventService,
    private val telegramSender: StepNarrationTelegramSender? = null,
    private val vkSender: StepNarrationVkSender? = null,
) {
    suspend fun deliver(userId: String, chatId: UUID, executionId: UUID, text: String) {
        eventService.publishLive(
            userId = userId,
            chatId = chatId,
            executionId = executionId,
            type = AgentEventType.ASSISTANT_STEP,
            payload = AssistantStepPayload(text),
        )
        telegramSender?.trySend(userId, chatId, text)
        vkSender?.trySend(userId, chatId, text)
    }
}

/** Sends a narration line straight to a linked Telegram chat without persisting it. Best-effort. */
interface StepNarrationTelegramSender {
    suspend fun trySend(userId: String, chatId: UUID, text: String)
}

/** Sends a narration line straight to a linked VK chat without persisting it. Best-effort. */
interface StepNarrationVkSender {
    suspend fun trySend(userId: String, chatId: UUID, text: String)
}
