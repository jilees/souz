package ru.souz.backend.salute

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/** Jackson mapper shared by the Salute HTTP webhook and the device WS wire protocol. */
internal val saluteWireMapper = jacksonObjectMapper()
    .registerKotlinModule()
    .setSerializationInclusion(JsonInclude.Include.NON_NULL)

// --- Salute (Sber) MESSAGE_TO_SKILL / ANSWER_TO_USER envelope ---------------------------------

@JsonIgnoreProperties(ignoreUnknown = true)
data class SaluteWebhookRequest(
    val sessionId: String,
    val messageId: Long,
    val uuid: JsonNode? = null,
    val payload: SaluteWebhookPayload,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SaluteWebhookPayload(
    val device: SaluteDevice? = null,
    val message: SaluteMessage? = null,
    @param:JsonProperty("new_session")
    val newSession: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SaluteDevice(
    val deviceId: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SaluteMessage(
    @param:JsonProperty("original_text")
    val originalText: String? = null,
)

data class SaluteWebhookResponse(
    val sessionId: String,
    val messageId: Long,
    val uuid: JsonNode?,
    val payload: SaluteResponsePayload,
    val messageName: String = "ANSWER_TO_USER",
)

data class SaluteResponsePayload(
    val pronounceText: String,
    val finished: Boolean,
    val items: List<SaluteResponseItem> = emptyList(),
)

data class SaluteResponseItem(
    val bubble: SaluteBubble,
)

data class SaluteBubble(
    val text: String,
)

// --- Device WS wire protocol (backend <-> thin client on the speaker) ------------------------

object SaluteDeviceMessageType {
    const val REGISTER = "register"
    const val PING = "ping"
    const val PONG = "pong"
    const val EXEC = "exec"
    const val EXEC_RESULT = "exec_result"
    const val SESSION_END = "session_end"
    const val ERROR = "error"
}

/**
 * Single flat frame shape for the whole device protocol (mirrors the tiny wire format used by
 * the original picoclaw sberboom channel) — a `type` discriminator plus every field any message
 * kind might carry, left null when not applicable to that type.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SaluteDeviceMessage(
    val type: String,
    val id: String? = null,
    @param:JsonProperty("user_id")
    val userId: String? = null,
    val token: String? = null,
    val argv: List<String>? = null,
    @param:JsonProperty("timeout_ms")
    val timeoutMs: Long? = null,
    @param:JsonProperty("exit_code")
    val exitCode: Int? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    @param:JsonProperty("timed_out")
    val timedOut: Boolean? = null,
    val code: String? = null,
    val message: String? = null,
    val timestamp: Long? = null,
) {
    companion object {
        fun exec(
            id: String,
            argv: List<String>,
            timeoutMs: Long,
        ): SaluteDeviceMessage = SaluteDeviceMessage(
            type = SaluteDeviceMessageType.EXEC,
            id = id,
            argv = argv,
            timeoutMs = timeoutMs,
            timestamp = System.currentTimeMillis(),
        )

        fun pong(): SaluteDeviceMessage = SaluteDeviceMessage(
            type = SaluteDeviceMessageType.PONG,
            timestamp = System.currentTimeMillis(),
        )
    }
}
