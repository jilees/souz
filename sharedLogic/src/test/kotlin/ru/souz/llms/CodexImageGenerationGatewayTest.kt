package ru.souz.llms

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.codex.CodexImageGenerationGateway
import ru.souz.llms.codex.CodexEndpoints
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.runtime.ImageGenerationInput
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodexImageGenerationGatewayTest {
    @Test
    fun `custom Responses URL also routes image generation`() = runTest {
        val endpoints = CodexEndpoints(baseUrl = "https://relay.test/souz-llm/chatgpt")
        val settings = mockk<SettingsProvider>(relaxed = true)
        val oauth = mockk<CodexOAuthService>()
        coEvery { oauth.refreshTokenIfNeeded() } returns "fresh-token"
        HttpClient(MockEngine { request ->
            assertEquals(endpoints.responsesUrl, request.url.toString())
            assertEquals("Bearer fresh-token", request.headers[HttpHeaders.Authorization])
            respond(item() + done)
        }).use { client ->
            assertContentEquals(png, CodexImageGenerationGateway(settings, oauth, client, endpoints = endpoints)
                .generate(ImageGenerationInput("cat")).bytes)
        }
    }

    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
    private val done = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"
    private fun item(bytes: ByteArray = png) = "data: " + restJsonMapper.writeValueAsString(mapOf(
        "type" to "response.output_item.done", "item" to mapOf("type" to "image_generation_call",
            "status" to "completed", "result" to Base64.getEncoder().encodeToString(bytes)),
    )) + "\n\n"

    @Test
    fun `image event survives empty final output and uses Codex authorization`() = runTest {
        val settings = mockk<SettingsProvider>()
        every { settings.codexAccountId } returns "test-account"
        val oauth = mockk<CodexOAuthService>()
        coEvery { oauth.refreshTokenIfNeeded() } returns "fresh-token"
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            assertEquals("https://chatgpt.com/backend-api/codex/responses", request.url.toString())
            assertEquals("Bearer fresh-token", request.headers[HttpHeaders.Authorization])
            assertEquals("test-account", request.headers["Chatgpt-Account-Id"])
            val body = restJsonMapper.readTree((request.body as OutgoingContent.ByteArrayContent).bytes())
            assertEquals("gpt-6.1-sol", body["model"].asText())
            assertEquals("image_generation", body["tools"][0]["type"].asText())
            assertEquals("image_generation", body["tool_choice"]["type"].asText())
            assertTrue(body["stream"].asBoolean())
            assertEquals(false, body["store"].asBoolean())
            respond("event: response.created\ndata: {\"response\":{\"tools\":[{\"type\":\"image_generation\",\"model\":\"gpt-image-2-codex\"}]}}\n\n" + item() + done)
        })
        try {
            val gateway = CodexImageGenerationGateway(settings, oauth, client)
            repeat(2) {
                val result = gateway.generate(ImageGenerationInput("Draw a cat"))
                assertContentEquals(png, result.bytes)
                assertEquals("gpt-image-2-codex", result.model)
                assertEquals("image/png", result.mimeType)
            }
            assertEquals(2, requests) // The application-owned client remains usable.
        } finally { client.close() }
    }

    @Test
    fun `failed incomplete missing oversized and invalid image responses never succeed or retry`() = runTest {
        val cases = listOf(
            item() to 1000,
            done to 1000,
            item() + "event: error\ndata: {\"message\":\"private upstream details\"}\n\n" + done to 1000,
            item() + "data: {\"type\":\"response.incomplete\"}\n\n" to 1000,
            item() + item() + done to 1000,
            item("not a PNG".toByteArray()) + done to 1000,
            item() + done to 8,
        )
        val settings = mockk<SettingsProvider>()
        every { settings.codexAccountId } returns "test-account"
        val oauth = mockk<CodexOAuthService>()
        coEvery { oauth.refreshTokenIfNeeded() } returns "fresh-token"
        for ((stream, limit) in cases) {
            var requests = 0
            val client = HttpClient(MockEngine { requests++; respond(stream) })
            try {
                val error = assertFailsWith<IllegalStateException> {
                    CodexImageGenerationGateway(settings, oauth, client, limit).generate(ImageGenerationInput("cat"))
                }
                assertTrue(!error.message.orEmpty().contains("private"))
                assertEquals(1, requests)
            } finally { client.close() }
        }
        val client = HttpClient(MockEngine { respond("private", HttpStatusCode.Unauthorized, headersOf()) })
        try {
            assertEquals("codex_image_http_401", assertFailsWith<IllegalStateException> {
                CodexImageGenerationGateway(settings, oauth, client).generate(ImageGenerationInput("cat"))
            }.message)
        } finally { client.close() }
        coEvery { oauth.refreshTokenIfNeeded() } throws CancellationException("cancelled")
        val unusedClient = HttpClient(MockEngine { error("must not be called") })
        try {
            assertFailsWith<CancellationException> {
                CodexImageGenerationGateway(settings, oauth, unusedClient).generate(ImageGenerationInput("cat"))
            }
        } finally { unusedClient.close() }
    }
}
