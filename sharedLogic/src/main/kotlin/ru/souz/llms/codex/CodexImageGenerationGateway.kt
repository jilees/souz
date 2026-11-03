package ru.souz.llms.codex

import io.ktor.client.HttpClient
import java.util.Base64
import kotlinx.coroutines.flow.collect
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
        val tool = buildMap<String, Any> {
            put("type", "image_generation")
            put("output_format", "png")
            if (input.images.isNotEmpty()) put("action", "edit")
            input.size?.let { put("size", it) }
            input.quality?.let { put("quality", it) }
        }
        val body = mapOf(
            "model" to (input.model ?: "gpt-6.1-sol"),
            "instructions" to "Generate or edit the requested image using the image generation tool. Use supplied images as sources and preserve details unless the user asks to change them. Do not substitute SVG or code.",
            "input" to listOf(mapOf("role" to "user", "content" to if (input.images.isEmpty()) input.prompt else buildList {
                add(mapOf("type" to "input_text", "text" to input.prompt))
                input.images.forEach { add(mapOf("type" to "input_image", "image_url" to it.dataUrl())) }
            })),
            "tools" to listOf(tool),
            "tool_choice" to mapOf("type" to "image_generation"),
            "store" to false,
            "stream" to true,
        )
        val encodedLimit = ((maxImageBytes.toLong() + 2) / 3 * 4).toInt()
        var image: ByteArray? = null
        var generator: String? = null
        val transport = CodexResponsesTransport(client, settings, oauth, endpoints)
        try {
            transport.events(restJsonMapper.valueToTree(body), timeoutMillis = 180_000,
                maxFrameChars = encodedLimit + 65_536, maxResponseChars = encodedLimit.toLong() * 2 + 1_000_000,
            ).collect { node ->
                when (node["type"]?.asText()) {
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
                }
            }
        } catch (failure: CodexFailure) {
            throw IllegalStateException(if (failure.code == "codex_http_error") "codex_image_http_${failure.status}" else "codex_image_generation_failed")
        }
        return GeneratedImage(image ?: error("codex_image_missing_result"), "image/png", "CODEX", generator)
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
