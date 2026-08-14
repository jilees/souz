package ru.souz.backend.salute

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.agent.runtime.BackendConversationTurnOutcome
import ru.souz.backend.agent.runtime.BackendConversationTurnRunner
import ru.souz.backend.agent.runtime.BackendNoopAgentToolCatalog
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.agent.session.AgentStateRepository
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.service.AgentExecutionFinalizer
import ru.souz.backend.execution.service.AgentExecutionLauncher
import ru.souz.backend.execution.service.AgentExecutionRequestFactory
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.settings.service.EffectiveSettingsResolver
import ru.souz.backend.settings.service.UserSettingsOverrides
import ru.souz.backend.testutil.repository.MemoryAgentEventRepository
import ru.souz.backend.testutil.repository.MemoryAgentExecutionRepository
import ru.souz.backend.testutil.repository.MemoryAgentStateRepository
import ru.souz.backend.testutil.repository.MemoryChatRepository
import ru.souz.backend.testutil.repository.MemoryClientRequestRepository
import ru.souz.backend.testutil.repository.MemoryMessageRepository
import ru.souz.backend.testutil.repository.MemoryOptionRepository
import ru.souz.backend.testutil.repository.MemoryToolCallRepository
import ru.souz.backend.testutil.repository.MemoryUserProviderKeyRepository
import ru.souz.backend.testutil.repository.MemoryUserSettingsRepository
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LocalModelAvailability

/**
 * Regression coverage for the "instant Готово, device acts seconds later" bug: the Salute
 * webhook must await the agent turn (like Telegram does) instead of returning immediately with
 * a null assistant message, which would force [SaluteWebhookService]'s hardcoded fallback reply
 * to be spoken before the real answer — or the real device command — exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentExecutionSaluteTurnExecutorTest {
    @Test
    fun `execute awaits the turn and returns the real assistant message, not null`() = runTest {
        val runner = CompletedTurnRunner(output = "Свет в туалете включил.")
        val context = executionServiceContext(runner)
        try {
            val executor = AgentExecutionSaluteTurnExecutor(context.service)

            val result = executor.execute(
                userId = context.chat.userId,
                chatId = context.chat.id,
                content = "включи свет в туалете",
                clientMessageId = "salute:test-binding:1",
                requestOverrides = UserSettingsOverrides(streamingMessages = false),
                attributes = mapOf("deviceId" to "device-1"),
            )

            assertEquals(1, runner.startedCount, "the turn must have actually run before execute() returns")
            assertEquals("Свет в туалете включил.", result.assistantMessage?.content)
        } finally {
            context.close()
        }
    }
}

private suspend fun TestScope.executionServiceContext(
    turnRunner: BackendConversationTurnRunner,
): SaluteExecutorTestContext {
    val chatRepository = MemoryChatRepository()
    val messageRepository = MemoryMessageRepository()
    val executionRepository = MemoryAgentExecutionRepository()
    val optionRepository = MemoryOptionRepository()
    val eventRepository = MemoryAgentEventRepository()
    val userSettingsRepository = MemoryUserSettingsRepository()
    val userProviderKeyRepository = MemoryUserProviderKeyRepository()
    val stateRepository: AgentStateRepository = MemoryAgentStateRepository()
    val eventService = AgentEventService(
        chatRepository = chatRepository,
        eventRepository = eventRepository,
        eventBus = AgentEventBus(),
    )
    val settingsProvider = TestSettingsProvider().apply {
        gigaChatKey = "giga-key"
        contextSize = 24_000
        temperature = 0.6f
        useStreaming = true
    }
    val featureFlags = BackendFeatureFlags(
        wsEvents = true,
        streamingMessages = true,
        toolEvents = true,
    )
    val executionScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
    val effectiveSettingsResolver = EffectiveSettingsResolver(
        baseSettingsProvider = settingsProvider,
        userSettingsRepository = userSettingsRepository,
        userProviderKeyRepository = userProviderKeyRepository,
        featureFlags = featureFlags,
        toolCatalog = BackendNoopAgentToolCatalog,
        localModelAvailability = unavailableLocalModels(),
    )
    val toolCallRepository = MemoryToolCallRepository()
    val finalizer = AgentExecutionFinalizer(
        agentStateRepository = stateRepository,
        chatRepository = chatRepository,
        executionRepository = executionRepository,
        turnRunner = turnRunner,
    )
    val service = AgentExecutionService(
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        executionRepository = executionRepository,
        clientRequestRepository = MemoryClientRequestRepository(executionRepository),
        optionRepository = optionRepository,
        eventService = eventService,
        toolCallRepository = toolCallRepository,
        requestFactory = AgentExecutionRequestFactory(
            effectiveSettingsResolver = effectiveSettingsResolver,
            featureFlags = featureFlags,
        ),
        finalizer = finalizer,
        launcher = AgentExecutionLauncher(
            executionScope = executionScope,
        ),
    )
    val chat = Chat(
        id = UUID.randomUUID(),
        userId = "user-salute",
        title = "salute-turn-executor",
        archived = false,
        createdAt = Instant.parse("2026-08-14T20:19:00Z"),
        updatedAt = Instant.parse("2026-08-14T20:19:00Z"),
    )
    chatRepository.create(chat)
    return SaluteExecutorTestContext(service = service, chat = chat, executionScope = executionScope)
}

private data class SaluteExecutorTestContext(
    val service: AgentExecutionService,
    val chat: Chat,
    val executionScope: CoroutineScope,
) : AutoCloseable {
    override fun close() {
        executionScope.cancel()
    }
}

private class CompletedTurnRunner(private val output: String) : BackendConversationTurnRunner {
    var startedCount: Int = 0
        private set

    override suspend fun run(
        conversationKey: AgentConversationKey,
        request: BackendConversationTurnRequest,
        eventSink: AgentRuntimeEventSink,
        initialUsage: LLMResponse.Usage,
    ): BackendConversationTurnOutcome {
        startedCount += 1
        return BackendConversationTurnOutcome.Completed(
            output = output,
            usage = LLMResponse.Usage(
                promptTokens = 3,
                completionTokens = 4,
                totalTokens = 7,
                precachedTokens = 0,
            ),
            session = completedSession(),
        )
    }
}

private fun completedSession(): AgentConversationSession =
    AgentConversationSession(
        history = listOf(
            LLMRequest.Message(
                role = LLMMessageRole.user,
                content = "updated-state",
            )
        ),
        temperature = 0.6f,
        locale = "ru-RU",
        timeZone = "Europe/Moscow",
        basedOnMessageSeq = 1L,
        rowVersion = 0L,
    )

private fun unavailableLocalModels(): LocalModelAvailability =
    object : LocalModelAvailability {
        override fun isProviderAvailable(): Boolean = false

        override fun availableGigaModels(): List<LLMModel> = emptyList()

        override fun defaultGigaModel(): LLMModel? = null
    }
