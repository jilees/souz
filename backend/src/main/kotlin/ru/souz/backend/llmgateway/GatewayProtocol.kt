package ru.souz.backend.llmgateway

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import ru.souz.llms.restJsonMapper
import ru.souz.backend.llm.isModelProviderFailure

internal const val GATEWAY_BASE = "/llm/v1"
internal const val MAX_GATEWAY_BYTES = 8 * 1024 * 1024

internal fun gatewayObject(vararg values: Pair<String, Any?>): ObjectNode =
    restJsonMapper.valueToTree(values.toMap())

internal class GatewayFailure(
    val status: Int,
    val code: String,
    override val message: String,
) : RuntimeException(message) {
    val retryable: Boolean get() = isModelProviderFailure(status, code)
    fun body(): ObjectNode = gatewayObject("error" to gatewayObject(
        "message" to message, "type" to if (status in 400..499) "invalid_request_error" else "server_error",
        "param" to null, "code" to code,
    ))
}

internal fun parseGatewayObject(text: String): ObjectNode = try {
    restJsonMapper.factory.createParser(text).use { parser ->
        parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        val result = restJsonMapper.readTree<JsonNode>(parser) as? ObjectNode
            ?: throw GatewayFailure(400, "invalid_request", "Expected a JSON object.")
        if (parser.nextToken() != null) throw GatewayFailure(400, "invalid_json", "Expected one JSON object.")
        result
    }
} catch (_: com.fasterxml.jackson.core.JsonProcessingException) {
    throw GatewayFailure(400, "invalid_json", "Invalid JSON request.")
}

/** Only configured destinations receive traffic. Input cannot provide upstream URLs or credentials. */
internal fun validateGatewayRequest(body: ObjectNode) {
    if (!body.path("model").isTextual || body.path("model").asText().isBlank()) {
        throw GatewayFailure(400, "invalid_model", "model must be a nonblank string.")
    }
    if (!body.path("messages").isArray || body.path("messages").isEmpty) {
        throw GatewayFailure(400, "invalid_messages", "messages must be a nonempty array.")
    }
    if (body.has("stream") && !body.path("stream").isBoolean) {
        throw GatewayFailure(400, "invalid_stream", "stream must be boolean.")
    }
}

/** SSE frames can span data lines. Bound both lines and complete frames. */
internal suspend fun readGatewaySse(channel: ByteReadChannel, consume: suspend (String) -> Boolean) {
    ru.souz.llms.http.readProviderSse(channel, MAX_GATEWAY_BYTES) { _, data -> consume(data) }
}

internal fun upstreamGatewayFailure(status: Int, body: JsonNode? = null): GatewayFailure {
    val code = body?.path("error")?.path("code")?.takeIf { it.isTextual }?.asText()?.take(100) ?: "upstream_error"
    return GatewayFailure(status, code, "Upstream model request failed (HTTP $status).")
}

internal suspend fun gatewayHttpFailure(response: HttpResponse): GatewayFailure {
    val bytes = response.bodyAsChannel().readRemaining(8_192L).use { it.readByteArray() }
    val body = try { restJsonMapper.readTree(bytes) } catch (_: com.fasterxml.jackson.core.JsonProcessingException) { null }
    return upstreamGatewayFailure(response.status.value, body)
}
