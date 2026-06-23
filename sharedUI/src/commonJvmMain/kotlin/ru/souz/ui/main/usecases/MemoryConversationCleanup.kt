package ru.souz.ui.main.usecases

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.souz.memory.MemoryOwnerProvider
import ru.souz.memory.MemoryScope
import ru.souz.memory.MemoryService

interface MemoryConversationCleanup {
    fun cleanupConversation(conversationId: String)
}

object NoopMemoryConversationCleanup : MemoryConversationCleanup {
    override fun cleanupConversation(conversationId: String) = Unit
}

class MemoryServiceConversationCleanup(
    private val scope: CoroutineScope,
    private val memoryService: MemoryService,
    private val ownerProvider: MemoryOwnerProvider,
) : MemoryConversationCleanup {
    override fun cleanupConversation(conversationId: String) {
        if (conversationId.isBlank()) return
        scope.launch {
            runCatching {
                val ownerId = ownerProvider.currentOwnerId()
                memoryService.deleteFactsByScope(ownerId, MemoryScope("session", conversationId))
                memoryService.deleteFactsByScope(ownerId, MemoryScope("chat", conversationId))
            }
        }
    }
}
