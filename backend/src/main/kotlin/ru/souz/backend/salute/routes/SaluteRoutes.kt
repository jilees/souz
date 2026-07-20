package ru.souz.backend.salute.routes

import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.receiveOrBadRequest
import ru.souz.backend.http.requireV1Service
import ru.souz.backend.salute.SaluteDeviceMessage
import ru.souz.backend.salute.SaluteDeviceMessageType
import ru.souz.backend.salute.SaluteWebhookRequest
import ru.souz.backend.salute.saluteWireMapper

private const val REGISTER_TIMEOUT_MS = 5_000L

internal fun Route.saluteRoutes(deps: BackendHttpDependencies) {
    post(BackendHttpRoutes.SALUTE_WEBHOOK) {
        val service = requireV1Service(deps.saluteWebhookService, "Salute")
        val request = call.receiveOrBadRequest<SaluteWebhookRequest>()
        call.respond(service.handleWebhook(request))
    }

    webSocket(BackendHttpRoutes.SALUTE_WS) {
        val service = requireV1Service(deps.saluteWebhookService, "Salute")
        val registry = requireV1Service(deps.saluteDeviceConnectionRegistry, "Salute")

        var deviceId: String? = null
        try {
            val registerFrame: Frame? = withTimeoutOrNull(REGISTER_TIMEOUT_MS) {
                incoming.receiveCatching().getOrNull()
            }
            val registration: SaluteDeviceMessage? = parseDeviceMessage(registerFrame)
            val registeredDeviceId = registration?.userId?.trim()?.takeIf { name -> name.isNotEmpty() }
            if (registration?.type != SaluteDeviceMessageType.REGISTER || registeredDeviceId == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "register required"))
                return@webSocket
            }
            deviceId = registeredDeviceId
            registry.register(deviceId, this)

            for (frame in incoming) {
                val message = parseDeviceMessage(frame) ?: continue
                when (message.type) {
                    SaluteDeviceMessageType.PING ->
                        send(Frame.Text(saluteWireMapper.writeValueAsString(SaluteDeviceMessage.pong())))
                    SaluteDeviceMessageType.EXEC_RESULT -> service.handleExecResult(deviceId, message)
                    SaluteDeviceMessageType.SESSION_END -> service.markEndSession(deviceId)
                    else -> Unit
                }
            }
        } finally {
            deviceId?.let { registry.unregister(it, this) }
        }
    }
}

private fun parseDeviceMessage(frame: Frame?): SaluteDeviceMessage? {
    if (frame !is Frame.Text) return null
    return runCatching { saluteWireMapper.readValue<SaluteDeviceMessage>(frame.readText()) }.getOrNull()
}
