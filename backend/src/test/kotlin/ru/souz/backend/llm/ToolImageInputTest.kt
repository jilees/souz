package ru.souz.backend.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.openai.OpenAICompatibleChatAPI
import ru.souz.llms.restJsonMapper

class ToolImageInputTest {
    private val tool = LLMRequest.Message(LLMMessageRole.function, "image", attachments = listOf("file:old-image"))

    @Test
    fun `latest image follows tool results through the unchanged adapter without changing history`() = runTest {
        val url = "https://images.example.test/current.png"
        val image = "data:image/png;base64,aW1hZ2U="
        val request = LLMRequest.Chat(model = "test-model", messages = listOf(
            LLMRequest.Message(LLMMessageRole.assistant, "", functionsStateId = "a", functionCall = LLMRequest.FunctionCall("read", "{}")),
            LLMRequest.Message(LLMMessageRole.assistant, "", functionsStateId = "b", functionCall = LLMRequest.FunctionCall("capture", "{}")),
            tool.copy(functionsStateId = "a", name = "read"),
            tool.copy(functionsStateId = "b", name = "capture", attachments = listOf(url)),
        ))
        val downloads = mutableListOf<String>()
        val prepared = request.inlineToolImages { downloads += it; image }
        assertEquals(listOf(url), downloads)
        assertEquals(request.messages.map { if (it.role == LLMMessageRole.function) it.copy(attachments = null) else it },
            prepared.messages.dropLast(1))
        assertEquals(LLMMessageRole.user, prepared.messages.last().role)
        assertEquals(listOf(image), prepared.messages.last().attachments)
        assertEquals(4, request.messages.size)
        assertEquals(listOf("file:old-image"), request.messages[2].attachments)
        assertEquals(listOf(url), request.messages.last().attachments)

        val engine = MockEngine {
            respond("""{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
            val api = OpenAICompatibleChatAPI(LlmProvider.OPENAI, LlmSettingsStub(), client, apiKey = "test-key")
            assertIs<LLMResponse.Chat.Ok>(api.message(prepared))
        }
        val messages = restJsonMapper.readTree(engine.requestHistory.single().body.toByteArray())["messages"]
        assertEquals(listOf("assistant", "tool", "tool", "user"), messages.map { it["role"].asText() })
        assertEquals("a", messages[1]["tool_call_id"].asText())
        assertEquals("b", messages[2]["tool_call_id"].asText())
        assertEquals(image, messages.last()["content"][1]["image_url"]["url"].asText())
    }

    @Test
    fun `later result without image never reuses an earlier tool image`() = runTest {
        val request = LLMRequest.Chat(messages = listOf(tool, tool.copy(content = "capture failed", attachments = null)))
        assertEquals(listOf(null, null), request.inlineToolImages(::downloadToolImage).messages.map { it.attachments })
        assertEquals(listOf("file:old-image"), request.messages.first().attachments)
        val nextTurn = LLMRequest.Chat(messages = listOf(tool, LLMRequest.Message(LLMMessageRole.user, "new request")))
        assertNull(nextTurn.inlineToolImages(::downloadToolImage).messages.first().attachments)
    }

    @Test
    fun `summarization omits tool images and preserves ordinary user attachments`() = runTest {
        val user = tool.copy(role = LLMMessageRole.user)
        val prepared = LLMRequest.Chat(messages = listOf(user, tool), isSummarization = true).inlineToolImages(::downloadToolImage)
        assertEquals(user, prepared.messages.first())
        assertNull(prepared.messages.last().attachments)
    }

    @Test
    fun `invalid current tool image stops dispatch`() = runTest {
        for (url in listOf("http://example.com/image.png", "https://user:password@example.com/image.png", "file:///image.png")) {
            assertFailsWith<IllegalArgumentException> {
                LLMRequest.Chat(messages = listOf(tool.copy(attachments = listOf(url)))).inlineToolImages(::downloadToolImage)
            }
        }
    }
}
