package ru.souz.backend.salute

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/** Narrow view of the connection registry that `SaluteWebhookService` needs — kept separate from `register`/`unregister` (WS-route-only concerns) so tests can fake it without a real Ktor WS session. */
interface SaluteDevicePusher {
    fun isConnected(deviceId: String): Boolean

    suspend fun sendExec(deviceId: String, message: SaluteDeviceMessage): Boolean
}

/**
 * In-memory device_id -> live WS session map, the sole join point between the Salute webhook
 * and the thin client's WebSocket connection. Single-process by design (mirrors the original
 * picoclaw-voice-scenario-backend prototype) — no cross-instance fan-out, connections are lost
 * on restart and the device is expected to reconnect.
 */
class SaluteDeviceConnectionRegistry : SaluteDevicePusher {
    private val logger = LoggerFactory.getLogger(SaluteDeviceConnectionRegistry::class.java)
    private val connections = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

    fun register(deviceId: String, session: DefaultWebSocketServerSession) {
        connections[deviceId] = session
    }

    /** Removes the mapping only if it still points at [session], avoiding races on reconnect. */
    fun unregister(deviceId: String, session: DefaultWebSocketServerSession) {
        connections.remove(deviceId, session)
    }

    override fun isConnected(deviceId: String): Boolean = connections.containsKey(deviceId)

    override suspend fun sendExec(deviceId: String, message: SaluteDeviceMessage): Boolean {
        val session = connections[deviceId] ?: return false
        return try {
            session.send(Frame.Text(saluteWireMapper.writeValueAsString(message)))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to push exec message to Salute device {}: {}", deviceId, e.message)
            false
        }
    }
}
