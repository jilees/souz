package ru.souz.llms.openai

import io.mockk.every
import io.mockk.mockk
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import ru.souz.llms.http.providerHttpClientDefaults
import org.junit.jupiter.api.AfterEach
import ru.souz.db.SettingsProvider
import ru.souz.llms.runtime.ImageGenerationInput
import ru.souz.llms.runtime.ImageGenerationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAIImageGenerationGatewayTest {
    @Test
    fun `editing uploads original source files and options to the selected connection`() = runTest {
        val settings = mockk<SettingsProvider>()
        every { settings.requestTimeoutMillis } returns 1_000L
        val source = ImageGenerationSource(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 42))
        val sources = listOf(source, ImageGenerationSource("RIFF1234WEBP5678".toByteArray()))
        HttpClient(MockEngine { request ->
            assertEquals("https://images.test/v1/images/edits", request.url.toString())
            assertEquals("Bearer edit-key", request.headers[HttpHeaders.Authorization])
            assertEquals("multipart", request.body.contentType?.contentType)
            val body = request.body.toByteArray().toString(Charsets.ISO_8859_1)
            for (image in sources) {
                assertTrue(body.contains("Content-Type: ${image.mimeType}"))
                assertTrue(body.contains(image.bytes.toString(Charsets.ISO_8859_1)))
            }
            assertTrue(body.contains("name=\"image[]\""))
            assertTrue(body.contains("custom-image"))
            assertTrue(body.contains("Change the background"))
            respond("""{"data":[{"b64_json":"AQID"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { providerHttpClientDefaults() }.use { client ->
            val result = OpenAIImageGenerationGateway(settings, client, { "edit-key" }, "https://images.test/v1")
                .generate(ImageGenerationInput("Change the background", model = "custom-image", images = sources))
            assertContentEquals(byteArrayOf(1, 2, 3), result.bytes)
        }
    }

    @Test
    fun `provider error bodies cannot expose source pixels`() = runTest {
        val settings = mockk<SettingsProvider>()
        every { settings.requestTimeoutMillis } returns 1_000L
        HttpClient(MockEngine { respond("private source data", HttpStatusCode.BadRequest) }) {
            providerHttpClientDefaults()
        }.use { client ->
            val error = assertFailsWith<IllegalStateException> {
                OpenAIImageGenerationGateway(settings, client, { "key" }).generate(ImageGenerationInput("cat"))
            }
            assertFalse(error.toString().contains("private source data"))
        }
    }

    @AfterEach
    fun cleanup() {
        System.clearProperty("OPENAI_IMAGE_MODEL")
    }

    @Test
    fun `buildRequestPayload ignores external image model overrides`() {
        System.setProperty("OPENAI_IMAGE_MODEL", "custom-image-model")
        val gateway = createGateway()

        val payload = gateway.buildRequestPayload(ImageGenerationInput(prompt = "Draw a cat"))

        assertEquals("gpt-image-1", payload["model"])
    }

    @Test
    fun `explicit connection preserves the requested model and bounds decoded images`() = runTest {
        val settings = mockk<SettingsProvider>()
        every { settings.requestTimeoutMillis } returns 1_000L
        for (size in listOf(3, 4)) {
            val bytes = ByteArray(size) { 42 }
            var requests = 0
            val client = HttpClient(MockEngine { request ->
                requests++
                assertEquals("https://images.test/v1/images/generations", request.url.toString())
                assertEquals("Bearer image-key", request.headers[HttpHeaders.Authorization])
                respond("""{"data":[{"b64_json":"${java.util.Base64.getEncoder().encodeToString(bytes)}"}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }) { providerHttpClientDefaults() }
            try {
                val gateway = OpenAIImageGenerationGateway(settings, client, { "image-key" }, "https://images.test/v1", 3)
                val input = ImageGenerationInput("cat", model = "custom-image")
                if (size == 3) {
                    val result = gateway.generate(input)
                    assertContentEquals(bytes, result.bytes)
                    assertEquals("custom-image", result.model)
                } else {
                    assertFailsWith<IllegalStateException> { gateway.generate(input) }
                }
                assertEquals(1, requests)
            } finally { client.close() }
        }
    }

    private fun createGateway(): OpenAIImageGenerationGateway {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.requestTimeoutMillis } returns 1_000L
        every { settingsProvider.openaiKey } returns "test-key"
        return OpenAIImageGenerationGateway(settingsProvider, mockk<HttpClient>())
    }
}
