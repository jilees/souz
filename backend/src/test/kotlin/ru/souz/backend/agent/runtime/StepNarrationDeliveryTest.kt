package ru.souz.backend.agent.runtime

import io.mockk.coVerify
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import ru.souz.backend.events.service.AgentEventService

class StepNarrationDeliveryTest {
    private val userId = "user-1"
    private val chatId = UUID.randomUUID()
    private val executionId = UUID.randomUUID()

    @Test
    fun `deliver fans out to both the telegram and vk senders when configured`() = runTest {
        val eventService = mockk<AgentEventService>(relaxed = true)
        val telegramSender = mockk<StepNarrationTelegramSender>(relaxed = true)
        val vkSender = mockk<StepNarrationVkSender>(relaxed = true)
        val delivery = StepNarrationDelivery(
            eventService = eventService,
            telegramSender = telegramSender,
            vkSender = vkSender,
        )

        delivery.deliver(userId, chatId, executionId, "step 1")

        coVerify(exactly = 1) { telegramSender.trySend(userId, chatId, "step 1") }
        coVerify(exactly = 1) { vkSender.trySend(userId, chatId, "step 1") }
    }

    @Test
    fun `deliver still publishes the live event when no channel sender is configured`() = runTest {
        val eventService = mockk<AgentEventService>(relaxed = true)
        val delivery = StepNarrationDelivery(eventService = eventService)

        delivery.deliver(userId, chatId, executionId, "step 1")

        coVerify(exactly = 1) { eventService.publishLive(any(), any(), any(), any(), any(), any(), any()) }
    }
}
