package ru.souz.llms.openai

import io.mockk.every
import io.mockk.mockk
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import ru.souz.llms.http.providerHttpClientDefaults
import org.junit.jupiter.api.AfterEach
import ru.souz.db.SettingsProvider
import ru.souz.llms.runtime.ImageGenerationInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class OpenAIImageGenerationGatewayTest {

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
