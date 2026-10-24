package ru.souz.llms.codex

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import java.util.Base64
import ru.souz.db.SettingsProvider
import ru.souz.llms.restJsonMapper
import ru.souz.llms.runtime.GeneratedImage
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.runtime.ImageGenerationInput

/** Uses the host's Codex credentials and HTTP client; image bytes never enter chat history. */
class CodexImageGenerationGateway(
    private val settings: SettingsProvider,
    private val oauth: CodexOAuthService,
    private val client: HttpClient,
    private val maxImageBytes: Int = 20_000_000,
    private val endpoints: CodexEndpoints = CodexEndpoints(),
) : ImageGenerationGateway {
    override suspend fun generate(input: ImageGenerationInput): GeneratedImage {
        require(input.prompt.isNotBlank() && input.prompt.length <= 32_000)
        require(input.outputFormat == null || input.outputFormat == "png")
        require(maxImageBytes in 1..100_000_000)
        val token = oauth.refreshTokenIfNeeded()
        val tool = buildMap<String, Any> {
            put("type", "image_generation")
            put("output_format", "png")
            input.size?.let { put("size", it) }
            input.quality?.let { put("quality", it) }
        }
        val body = mapOf(
            "model" to (input.model ?: "gpt-6.1-sol"),
            "instructions" to "Generate the requested image using the image generation tool. Do not substitute SVG or code.",
            "input" to listOf(mapOf("role" to "user", "content" to input.prompt)),
            "tools" to listOf(tool),
            "tool_choice" to mapOf("type" to "image_generation"),
            "store" to false,
            "stream" to true,
        )
        return client.preparePost(endpoints.responsesUrl) {
            timeout { requestTimeoutMillis = 180_000 }
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            header("Chatgpt-Account-Id", settings.codexAccountId.orEmpty())
            header("originator", "codex_cli_rs")
            header("OpenAI-Beta", "responses=experimental")
            setBody(restJsonMapper.writeValueAsBytes(body))
        }.execute { response ->
            check(response.status.value in 200..299) { "codex_image_http_${response.status.value}" }
            val channel = response.bodyAsChannel()
            val encodedLimit = ((maxImageBytes.toLong() + 2) / 3 * 4).toInt()
            var remaining = encodedLimit.toLong() * 2 + 1_000_000
            var event: String? = null
            var image: ByteArray? = null
            var generator: String? = null
            var completed = false
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line(max = encodedLimit + 65_536) ?: break
                remaining -= line.length + 1
                check(remaining >= 0) { "codex_image_response_too_large" }
                if (line.startsWith("event: ")) event = line.removePrefix("event: ").trim()
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break
                val node = restJsonMapper.readTree(data)
                when (event ?: node["type"]?.asText()) {
                    "error", "response.failed", "response.incomplete" -> error("codex_image_generation_failed")
                    "response.created" -> generator = node["response"]?.get("tools")
                        ?.firstOrNull { it["type"]?.asText() == "image_generation" }?.get("model")?.asText()
                    "response.output_item.done" -> {
                        val item = node["item"]
                        if (item?.get("type")?.asText() == "image_generation_call") {
                            check(image == null && item["status"]?.asText() == "completed") { "codex_image_invalid_result" }
                            val encoded = item["result"]?.takeIf { it.isTextual }?.asText()
                                ?: error("codex_image_missing_result")
                            check(encoded.length <= encodedLimit) { "codex_image_too_large" }
                            image = Base64.getDecoder().decode(encoded)
                            check(image.size <= maxImageBytes && image.size >= 8 &&
                                image.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) { "codex_image_invalid_png" }
                        }
                    }
                    "response.completed" -> {
                        check(node["response"]?.get("status")?.asText() == "completed") { "codex_image_generation_failed" }
                        completed = true
                    }
                }
                event = null
                if (completed) break
            }
            check(completed) { "codex_image_incomplete_stream" }
            GeneratedImage(checkNotNull(image) { "codex_image_missing_result" }, "image/png", "CODEX", generator)
        }
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
