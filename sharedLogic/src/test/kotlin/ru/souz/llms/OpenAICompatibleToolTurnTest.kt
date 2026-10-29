package ru.souz.llms

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.openai.OpenAICompatibleChatAPI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAICompatibleToolTurnTest {
    @Test
    fun `mixed responses round trip before successful and failed tool results for compatible providers`() = runTest {
        for (provider in providers) for (stream in listOf(false, true)) {
            for (count in listOf(1, 2)) for (failed in listOf(false, true)) {
                val calls = (1..count).map { i ->
                    mapOf("index" to i - 1, "id" to "call_$i", "type" to "function",
                        "function" to mapOf("name" to "tool_$i", "arguments" to "{\"x\":$i}"))
                }
                var requests = 0
                val engine = MockEngine {
                    requests++
                    val message = if (requests == 1) {
                        mapOf("role" to "assistant", "content" to "Running tools", "tool_calls" to calls)
                    } else mapOf("role" to "assistant", "content" to "Done")
                    val choice = mapOf("index" to 0, (if (stream) "delta" else "message") to message,
                        "finish_reason" to if (requests == 1) "tool_calls" else "stop")
                    val reply = restJsonMapper.writeValueAsString(mapOf("choices" to listOf(choice)))
                    respond(if (stream) "data: $reply\n\ndata: [DONE]\n\n" else reply,
                        headers = headersOf(HttpHeaders.ContentType,
                            (if (stream) ContentType.Text.EventStream else ContentType.Application.Json).toString()))
                }
                HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
                    val api = api(client, provider)
                    val first = LLMRequest.Chat("test-model", listOf(LLMRequest.Message(LLMMessageRole.user, "Run tools")))
                    val responses = if (stream) api.messageStream(first).toList() else listOf(api.message(first))
                    val choices = responses.flatMap { assertIs<LLMResponse.Chat.Ok>(it).choices }
                    val history = first.messages + choices.mapNotNull { it.toMessage() } +
                        choices.filter { it.message.functionCall != null }.map { choice ->
                            LLMRequest.Message(LLMMessageRole.function,
                                if (failed) "{\"error\":\"invalid_arguments\"}" else "{\"ok\":true}",
                                name = choice.message.functionCall!!.name,
                                functionsStateId = choice.message.functionsStateId)
                        }
                    val next = first.copy(messages = history)
                    if (stream) api.messageStream(next).toList() else assertIs<LLMResponse.Chat.Ok>(api.message(next))
                }
                val request = restJsonMapper.readTree(engine.requestHistory.last().body.toByteArray())
                val messages = request["messages"].toList()
                assertEquals("tool", messages.last()["role"].asText(), "$provider stream=$stream failed=$failed")
                val assistant = messages.single { it.has("tool_calls") }
                assertEquals((1..count).map { "call_$it" }, assistant["tool_calls"].map { it["id"].asText() })
                assertEquals((1..count).map { "call_$it" }, messages.filter { it["role"].asText() == "tool" }
                    .map { it["tool_call_id"].asText() })
                val firstResult = messages.indexOfFirst { it["role"].asText() == "tool" }
                assertTrue(messages.take(firstResult).any { it["content"]?.asText() == "Running tools" })
                assertTrue(messages.drop(firstResult).all { it["role"].asText() == "tool" })
            }
        }
    }

    @Test
    fun `combined call text is retained without moving completed assistant or later user turns`() = runTest {
        for (provider in providers) {
            val engine = MockEngine {
                respond("""{"choices":[{"message":{"role":"assistant","content":"Done"},"finish_reason":"stop"}]}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
            HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
                api(client, provider).message(LLMRequest.Chat("test-model", listOf(
                    LLMRequest.Message(LLMMessageRole.assistant, "Running tool", functionsStateId = "call_a",
                        functionCall = LLMRequest.FunctionCall("tool_a", "{}")),
                    LLMRequest.Message(LLMMessageRole.function, "ok", functionsStateId = "call_a"),
                    LLMRequest.Message(LLMMessageRole.assistant, "Finished tool"),
                    LLMRequest.Message(LLMMessageRole.user, "Next task"),
                )))
            }
            val request = restJsonMapper.readTree(engine.requestHistory.single().body.toByteArray())
            assertEquals(listOf("assistant", "tool", "assistant", "user"), request["messages"].map { it["role"].asText() })
            assertEquals(listOf("Running tool", "ok", "Finished tool", "Next task"), request["messages"].map { it["content"].asText() })
        }
    }

    private fun api(client: HttpClient, provider: LlmProvider): OpenAICompatibleChatAPI {
        val settings = mockk<SettingsProvider>(relaxed = true)
        every { settings.requestTimeoutMillis } returns 5_000L
        return OpenAICompatibleChatAPI(provider, settings, client, apiKey = "test-key")
    }

    private val providers = listOf(LlmProvider.OPENAI, LlmProvider.AI_TUNNEL, LlmProvider.QWEN)
}
