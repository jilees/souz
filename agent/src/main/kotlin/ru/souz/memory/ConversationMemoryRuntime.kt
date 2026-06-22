package ru.souz.memory

@JvmInline
value class MemoryOwnerId(val value: String)

@JvmInline
value class MemorySessionId(val value: String)

@JvmInline
value class ConversationId(val value: String)

@JvmInline
value class ProjectId(val value: String)

enum class MemorySurface {
    DESKTOP,
    BACKEND,
}

data class MemoryContext(
    val ownerId: MemoryOwnerId,
    val surface: MemorySurface,
    val conversationId: ConversationId?,
    val sessionId: MemorySessionId?,
    val projectId: ProjectId?,
)

fun legacyMemoryContext(): MemoryContext = MemoryContext(
    ownerId = MemoryOwnerId(LEGACY_OWNER_ID),
    surface = MemorySurface.DESKTOP,
    conversationId = null,
    sessionId = null,
    projectId = null,
)

enum class RequestedMemoryScope {
    GLOBAL,
    PROJECT,
    CHAT,
    SESSION,
}

const val LEGACY_OWNER_ID: String = "local-legacy-owner"

/**
 * Completed conversation turn passed to memory capture after the assistant responds.
 */
data class CompletedTurnMemoryInput(
    val context: MemoryContext = legacyMemoryContext(),
    val conversationId: String?,
    val userMessageId: String?,
    val assistantMessageId: String?,
    val userMessage: String,
    val assistantMessage: String,
)

/**
 * Single fact reference included into the prompt augmentation.
 */
data class MemoryPromptFact(
    val factId: String,
    val scope: String,
    val score: Float,
)

/**
 * Rendered memory block plus referenced fact metadata for tracing and UI.
 */
data class MemoryPromptAugmentationResult(
    val renderedBlock: String,
    val facts: List<MemoryPromptFact> = emptyList(),
)

data class MemoryRetrievalRequest(
    val context: MemoryContext,
    val query: String,
    val maxFacts: Int? = null,
    val maxPromptTokens: Int? = null,
)

data class MemoryRetrievalTrace(
    val candidateCountBySource: Map<String, Int> = emptyMap(),
    val selectedFactIds: List<String> = emptyList(),
    val semanticRerankerUsed: Boolean = false,
    val promptTokenEstimate: Int = 0,
    val exclusionReasons: Map<String, Int> = emptyMap(),
)

data class MemoryRetrievalResult(
    val renderedPromptBlock: String?,
    val facts: List<MemoryPromptFact> = emptyList(),
    val trace: MemoryRetrievalTrace = MemoryRetrievalTrace(),
)

/**
 * Conversation-scoped entry point for prompt memory retrieval and post-turn capture.
 */
interface ConversationMemoryRuntime {
    suspend fun retrieveMemory(
        request: MemoryRetrievalRequest,
    ): MemoryRetrievalResult {
        val legacy = retrieveMemory(request.query, request.context.conversationId?.value)
        return MemoryRetrievalResult(
            renderedPromptBlock = legacy.renderedBlock,
            facts = legacy.facts,
        )
    }

    @Deprecated("Use typed MemoryRetrievalRequest")
    suspend fun retrieveMemory(
        userMessage: String,
        conversationId: String?,
    ): MemoryPromptAugmentationResult = MemoryPromptAugmentationResult(renderedBlock = "")

    suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput)
}

/**
 * No-op runtime used when memory integration is disabled.
 */
object NoopConversationMemoryRuntime : ConversationMemoryRuntime {
    override suspend fun retrieveMemory(request: MemoryRetrievalRequest): MemoryRetrievalResult =
        MemoryRetrievalResult(renderedPromptBlock = null)

    override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) = Unit
}
