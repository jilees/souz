package ru.souz.llms.openai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import ru.souz.tool.web.internal.WebHttpSupport
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.util.Base64
import kotlinx.coroutines.CancellationException
import ru.souz.db.SettingsProvider
import ru.souz.llms.restJsonMapper
import ru.souz.llms.runtime.GeneratedImage
import ru.souz.llms.runtime.ImageFileFormats
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.runtime.ImageGenerationInput

class OpenAIImageGenerationGateway(
    private val settingsProvider: SettingsProvider,
    private val client: HttpClient,
    private val apiKeyProvider: suspend () -> String = {
        settingsProvider.openaiKey
            ?: System.getenv("OPENAI_API_KEY")
            ?: System.getProperty("OPENAI_API_KEY")
            ?: throw IllegalStateException("OPENAI_API_KEY is not set")
    },
    private val baseUrl: String? = null,
    private val maxImageBytes: Int = 20_000_000,
) : ImageGenerationGateway {
    override suspend fun generate(input: ImageGenerationInput): GeneratedImage = try {
        require(maxImageBytes in 1..100_000_000)
        val outputFormat = (input.outputFormat ?: OpenAIImageGenerationRequestBuilder.DEFAULT_OUTPUT_FORMAT).lowercase()
        client.preparePost(imagesUrl) {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(HttpHeaders.Accept, ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${apiKeyProvider()}")
            timeout { requestTimeoutMillis = settingsProvider.requestTimeoutMillis }
            setBody(buildRequestPayload(input))
        }.execute { response ->
            val text = readResponse(response)
            if (!response.status.isSuccess()) {
                throw IllegalStateException("OpenAI image generation failed: ${response.status.value}. $text")
            }
            parseResponse(text, outputFormat, input.model ?: DEFAULT_IMAGE_MODEL)
        }
    } catch (e: ClientRequestException) {
        val text = readResponse(e.response)
        throw IllegalStateException("OpenAI image generation failed: ${e.response.status.value}. $text")
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (t: Throwable) {
        throw IllegalStateException("OpenAI image generation failed: ${t.message}", t)
    }

    private suspend fun readResponse(response: io.ktor.client.statement.HttpResponse): String =
        WebHttpSupport(sharedWebHttpClient = client).readLimitedBinaryBody(response.bodyAsChannel(),
            ((maxImageBytes.toLong() + 2) / 3 * 4 + 1_000_000).toInt(), url = "image generation response").decodeToString()

    fun buildRequestPayload(input: ImageGenerationInput): Map<String, Any> =
        OpenAIImageGenerationRequestBuilder.build(input, defaultModel = DEFAULT_IMAGE_MODEL)

    private fun parseResponse(text: String, outputFormat: String, requestedModel: String): GeneratedImage {
        val node = restJsonMapper.readTree(text)
        val item = node["data"]?.firstOrNull()
            ?: throw IllegalStateException("OpenAI image generation returned no image data.")
        val base64 = item["b64_json"]?.asText()
            ?: throw IllegalStateException("OpenAI image generation response did not contain b64_json.")
        check(base64.length.toLong() <= (maxImageBytes.toLong() + 2) / 3 * 4) { "Generated image is too large." }
        val bytes = Base64.getDecoder().decode(base64)
        check(bytes.size <= maxImageBytes) { "Generated image is too large." }
        return GeneratedImage(
            bytes = bytes,
            mimeType = ImageFileFormats.mimeTypeForExtension(outputFormat)
                ?: throw IllegalStateException("Unsupported OpenAI image output format: $outputFormat"),
            provider = "OPENAI",
            model = node["model"]?.asText() ?: requestedModel,
        )
    }

    private companion object {
        const val IMAGES_PATH = "images/generations"
        const val DEFAULT_IMAGE_MODEL = "gpt-image-1"
    }

    private val imagesUrl: String
        get() = settingsProvider.openAIEndpoint(baseUrl).endpoint(IMAGES_PATH)
}
