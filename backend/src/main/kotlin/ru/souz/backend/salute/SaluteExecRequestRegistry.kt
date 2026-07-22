package ru.souz.backend.salute

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import ru.souz.runtime.sandbox.SandboxCommandResult

class SaluteDeviceDisconnectedException(message: String) : Exception(message)

/**
 * Correlates fire-and-forget `exec`/`exec_result` WebSocket frames into suspend request/response
 * pairs, keyed by the message `id`. A late `exec_result` arriving after [discard] (timeout) or a
 * disconnect is always a silent no-op — never a crash — since the sender has already stopped
 * waiting by then.
 */
class SaluteExecRequestRegistry {
    private data class Pending(
        val deviceId: String,
        val deferred: CompletableDeferred<SandboxCommandResult>,
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    fun beginRequest(deviceId: String, id: String): CompletableDeferred<SandboxCommandResult> {
        val deferred = CompletableDeferred<SandboxCommandResult>()
        pending[id] = Pending(deviceId, deferred)
        return deferred
    }

    fun complete(id: String, result: SandboxCommandResult) {
        pending.remove(id)?.deferred?.complete(result)
    }

    fun discard(id: String) {
        pending.remove(id)
    }

    fun failAllForDevice(deviceId: String, reason: String) {
        pending.entries.removeAll { (_, entry) ->
            val matches = entry.deviceId == deviceId
            if (matches) {
                entry.deferred.completeExceptionally(SaluteDeviceDisconnectedException(reason))
            }
            matches
        }
    }
}
