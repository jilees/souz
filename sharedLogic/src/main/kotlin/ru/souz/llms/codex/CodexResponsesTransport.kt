package ru.souz.llms.codex

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.llms.http.readProviderSse
import ru.souz.llms.restJsonMapper

class CodexFailure(val status: Int, val code: String, override val message: String) : RuntimeException(message)

internal fun codexObject(vararg values: Pair<String, Any?>): ObjectNode = restJsonMapper.valueToTree(values.toMap())

/** One request-local OAuth/HTTP/SSE path for chat, external completions and image generation. */
class CodexResponsesTransport(
    private val client: HttpClient,
    private val settings: SettingsProvider,
    private val oauth: CodexOAuthService,
    private val endpoints: CodexEndpoints = CodexEndpoints(),
) {
    fun events(
        request: ObjectNode,
        timeoutMillis: Long,
        maxFrameChars: Int = 8 * 1024 * 1024,
        maxResponseChars: Long = Long.MAX_VALUE,
    ): Flow<JsonNode> = flow {
        val token = oauth.refreshTokenIfNeeded()
        client.preparePost(endpoints.responsesUrl) {
            timeout { requestTimeoutMillis = timeoutMillis }
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            header("Chatgpt-Account-Id", settings.codexAccountId.orEmpty())
            header("originator", "codex_cli_rs")
            header("OpenAI-Beta", "responses=experimental")
            setBody(request.toString())
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val bytes = response.bodyAsChannel().readRemaining(8_192).use { it.readByteArray() }
                val error = try { restJsonMapper.readTree(bytes)?.path("error") } catch (_: com.fasterxml.jackson.core.JsonProcessingException) { null }
                throw CodexFailure(response.status.value, error?.path("code")?.asText()?.takeIf { it.isNotBlank() } ?: "codex_http_error",
                    error?.path("message")?.asText()?.takeIf { it.isNotBlank() } ?: "Codex request failed (HTTP ${response.status.value}).")
            }
            var complete = false
            var responseId: String? = null
            readProviderSse(response.bodyAsChannel(), maxFrameChars, maxResponseChars) { event, data ->
                if (data == "[DONE]") return@readProviderSse false
                val node = restJsonMapper.readTree(data) as? ObjectNode
                    ?: throw CodexFailure(502, "codex_invalid_response", "Expected a Codex event object.")
                if (event != null) node.put("type", event)
                responseId = node.path("response").get("id")?.asText() ?: responseId
                when (node.path("type").asText()) {
                    "error", "response.failed", "response.incomplete" -> {
                        logFailure(node, request.path("model").asText(), response.headers["x-request-id"], responseId, response.status.value)
                        throw streamFailure(node)
                    }
                    "response.completed" -> {
                        val status = node.path("response").path("status").asText()
                        if (status.isNotEmpty() && status != "completed") throw streamFailure(node)
                        complete = true
                    }
                }
                emit(node)
                !complete
            }
            if (!complete) throw CodexFailure(502, "codex_stream_incomplete", "Codex response did not complete.")
        }
    }

    private fun streamFailure(node: JsonNode): CodexFailure {
        val error = node.path("response").get("error")?.takeUnless { it.isNull }
            ?: node.get("error")?.takeUnless { it.isNull } ?: node
        val code = error.path("code").asText().ifBlank { "codex_response_failed" }
        val message = error.path("message").asText().ifBlank {
            if (error.isTextual) error.asText() else node.path("response").path("incomplete_details").path("reason").asText()
        }.ifBlank { "Codex response failed or was incomplete." }
        return CodexFailure(if (code == "server_is_overloaded") 503 else 502, code, message)
    }

    private fun logFailure(node: JsonNode, model: String, requestId: String?, responseId: String?, status: Int) {
        val response = node.path("response")
        val error = response.get("error")?.takeUnless { it.isNull } ?: node.get("error")?.takeUnless { it.isNull } ?: node
        fun scalar(value: JsonNode?): String? = value?.takeIf { it.isValueNode && !it.isNull }
            ?.asText()?.replace('\n', ' ')?.replace('\r', ' ')?.take(2_000)
        LoggerFactory.getLogger(CodexResponsesTransport::class.java).atWarn()
            .addKeyValue("event", "codex.stream_failure").addKeyValue("model", model)
            .addKeyValue("http_status", status).addKeyValue("request_id", requestId).addKeyValue("response_id", responseId)
            .addKeyValue("sse_event", node.path("type").asText()).addKeyValue("error_code", scalar(error["code"]))
            .addKeyValue("error_type", scalar(error["type"])).addKeyValue("error_message", scalar(error["message"]) ?: scalar(error))
            .addKeyValue("error_param", scalar(error["param"]))
            .addKeyValue("incomplete_reason", scalar(response.path("incomplete_details").get("reason")))
            .log("Codex stream failure")
    }
}
