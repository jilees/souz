package ru.souz.backend.agent.runtime

import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.test.runTest
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.backend.execution.model.AgentExecution
import kotlin.test.Test

class BackendAgentRuntimeEventSinkStepNarrationTest {
    private val userId = "user-1"
    private val chatId = UUID.randomUUID()
    private val executionId = UUID.randomUUID()

    private fun sink(
        delivery: StepNarrationDelivery?,
        enabled: Boolean = true,
        publicClientThread: Boolean = true,
    ) = BackendAgentRuntimeEventSink(
        userId = userId,
        chatId = chatId,
        executionId = executionId,
        messageRepository = mockk(relaxed = true),
        optionRepository = mockk(relaxed = true),
        executionRepository = mockk(relaxed = true),
        eventService = mockk(relaxed = true),
        toolCallRepository = mockk(relaxed = true),
        streamingMessagesEnabled = false,
        toolEventsEnabled = false,
        publicClientThread = publicClientThread,
        stepNarrationEnabled = enabled,
        stepNarrationDelivery = delivery,
    )

    private suspend fun BackendAgentRuntimeEventSink.narrate(text: String) =
        emit(AgentRuntimeEvent.AssistantStepNarration(text))

    @Test
    fun `every step is delivered immediately, in order, trimmed`() = runTest {
        val delivery = mockk<StepNarrationDelivery>(relaxed = true)
        val sink = sink(delivery)

        sink.narrate("  step 1  ")
        sink.narrate("step 2")
        sink.narrate("step 3")

        coVerifyOrder {
            delivery.deliver(userId, chatId, executionId, "step 1")
            delivery.deliver(userId, chatId, executionId, "step 2")
            delivery.deliver(userId, chatId, executionId, "step 3")
        }
    }

    @Test
    fun `the last step before completion is still delivered - no server-side drop`() = runTest {
        val delivery = mockk<StepNarrationDelivery>(relaxed = true)
        val sink = sink(delivery)

        sink.narrate("only step")
        sink.emitExecutionFinished(mockk<AgentExecution>(relaxed = true))

        coVerify(exactly = 1) { delivery.deliver(userId, chatId, executionId, "only step") }
    }

    @Test
    fun `blank narration is skipped`() = runTest {
        val delivery = mockk<StepNarrationDelivery>(relaxed = true)
        val sink = sink(delivery)

        sink.narrate("   ")
        sink.narrate("")

        coVerify(exactly = 0) { delivery.deliver(any(), any(), any(), any()) }
    }

    @Test
    fun `nothing is delivered when narration is disabled`() = runTest {
        val delivery = mockk<StepNarrationDelivery>(relaxed = true)
        val sink = sink(delivery, enabled = false)

        sink.narrate("step 1")
        sink.narrate("step 2")

        coVerify(exactly = 0) { delivery.deliver(any(), any(), any(), any()) }
    }

    @Test
    fun `no delivery configured is a no-op`() = runTest {
        val sink = sink(delivery = null)

        sink.narrate("step 1")
        sink.emitExecutionCancelled()
    }

    @Test
    fun `steps are delivered for non-public (Telegram-origin) turns too`() = runTest {
        val delivery = mockk<StepNarrationDelivery>(relaxed = true)
        val sink = sink(delivery, publicClientThread = false)

        sink.narrate("step 1")

        coVerify(exactly = 1) { delivery.deliver(userId, chatId, executionId, "step 1") }
    }
}
