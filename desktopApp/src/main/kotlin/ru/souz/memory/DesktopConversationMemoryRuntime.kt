package ru.souz.memory

import ru.souz.db.ConfigStore
import java.util.UUID

interface DesktopMemoryProjectContextProvider {
    fun currentProjectId(): ProjectId?
}

object NoopDesktopMemoryProjectContextProvider : DesktopMemoryProjectContextProvider {
    override fun currentProjectId(): ProjectId? = null
}

class DesktopMemoryContextProvider(
    private val projectContextProvider: DesktopMemoryProjectContextProvider = NoopDesktopMemoryProjectContextProvider,
) {
    fun current(conversationId: String?): MemoryContext = MemoryContext(
        ownerId = MemoryOwnerId(stableOwnerId()),
        surface = MemorySurface.DESKTOP,
        conversationId = conversationId?.let(::ConversationId),
        sessionId = MemorySessionId(activeSessionId()),
        projectId = projectContextProvider.currentProjectId(),
    )

    fun rotateSession(): MemorySessionId {
        val id = UUID.randomUUID().toString()
        ConfigStore.put(ACTIVE_SESSION_KEY, id)
        return MemorySessionId(id)
    }

    private fun stableOwnerId(): String {
        ConfigStore.get<String>(OWNER_KEY)?.trim()?.takeIf(String::isNotBlank)?.let { return it }
        val generated = UUID.randomUUID().toString()
        ConfigStore.put(OWNER_KEY, generated)
        return generated
    }

    private fun activeSessionId(): String {
        ConfigStore.get<String>(ACTIVE_SESSION_KEY)?.trim()?.takeIf(String::isNotBlank)?.let { return it }
        return rotateSession().value
    }

    private companion object {
        private const val OWNER_KEY = "MEMORY_LOCAL_OWNER_ID"
        private const val ACTIVE_SESSION_KEY = "MEMORY_ACTIVE_SESSION_ID"
    }
}

class DesktopConversationMemoryRuntime(
    private val memoryService: MemoryService,
    private val captureService: MemoryCaptureService,
    private val contextProvider: DesktopMemoryContextProvider = DesktopMemoryContextProvider(),
) : ConversationMemoryRuntime {
    override suspend fun retrieveMemory(request: MemoryRetrievalRequest): MemoryRetrievalResult {
        val context = contextProvider.current(request.context.conversationId?.value)
        return memoryService.retrieveMemory(
            request.copy(context = context),
            overrideScopes = context.allowedRetrievalScopes(includeChat = false),
        )
    }

    override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) {
        val context = contextProvider.current(input.conversationId)
        captureService.captureAfterTurn(
            MemoryCaptureInput(
                context = context,
                scopes = context.allowedRetrievalScopes(includeChat = false),
                userMessage = input.userMessage,
                assistantMessage = input.assistantMessage,
                conversationId = input.conversationId,
                userMessageId = input.userMessageId,
                assistantMessageId = input.assistantMessageId,
            )
        )
    }
}
