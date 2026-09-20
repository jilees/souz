package ru.souz.backend.client

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Correlates an out-of-band client-tool push (sent via [ru.souz.backend.events.service.AgentEventService.publishLive]
 * into a chat that has no execution of its own) with the `tool.result` the target device sends back.
 * Purely in-memory — a push is only ever meaningful while the target device is live right now, so
 * unlike [ClientThreadRuntimeRegistry] there is no execution/DB row backing it and nothing to recover
 * after a restart: a pending push simply times out, same as "device not connected".
 */
internal class PushToolCallRegistry {
    private data class Pending(val toolCallId: String, val result: CompletableDeferred<ClientToolOutcome>)

    private val mutex = Mutex()
    private val pending = mutableMapOf<UUID, Pending>()

    suspend fun begin(threadId: UUID, toolCallId: String): CompletableDeferred<ClientToolOutcome> {
        val deferred = CompletableDeferred<ClientToolOutcome>()
        mutex.withLock { pending[threadId] = Pending(toolCallId, deferred) }
        return deferred
    }

    /** Completes the pending push, if any is still waiting under this exact (threadId, toolCallId). */
    suspend fun resolve(threadId: UUID, toolCallId: String, outcome: ClientToolOutcome): Boolean {
        val entry = mutex.withLock { pending[threadId]?.takeIf { it.toolCallId == toolCallId } } ?: return false
        return entry.result.complete(outcome)
    }

    suspend fun discard(threadId: UUID) {
        mutex.withLock { pending.remove(threadId) }
    }
}
