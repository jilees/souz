package ru.souz.llms

import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.codex.CodexChatAPI
import ru.souz.llms.codex.CodexCompletionClient
import ru.souz.llms.codex.CodexFailure
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.codex.CodexResponsesTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodexCompletionClientTest {
    @Test
    fun `native and streaming completions preserve the same tools text and usage for arbitrary model IDs`() = runTest {
        val settings = mockk<SettingsProvider>(relaxed = true)
        every { settings.requestTimeoutMillis } returns 1_000L
        val oauth = mockk<CodexOAuthService>()
        coEvery { oauth.refreshTokenIfNeeded() } returns "token"
        val tool = """{"type":"function_call","call_id":"call_a","name":"lookup","arguments":"{\"ids\":[1,2]}"}"""
        val message = """{"type":"message","content":[{"type":"output_text","text":"Working"}]}"""
        val stream = listOf(
            "response.output_item.added" to """{"output_index":1,"item":{"type":"function_call","call_id":"call_a","name":"lookup"}}""",
            "response.function_call_arguments.delta" to """{"output_index":1,"delta":"{\"ids\":["}""",
            "response.function_call_arguments.delta" to """{"output_index":1,"delta":"1,2]}"}""",
            "response.output_text.delta" to """{"delta":"Working"}""",
            "response.completed" to """{"response":{"status":"completed","output":[$tool,$message],"usage":{"input_tokens":3,"output_tokens":2,"input_tokens_details":{"cached_tokens":1}}}}""",
        ).joinToString("") { (event, data) -> "event: $event\ndata: $data\n\n" }
        val captured = mutableListOf<ObjectNode>()
        HttpClient(MockEngine { request ->
            captured += restJsonMapper.readTree(request.body.toByteArray()) as ObjectNode
            respond(stream)
        }).use { client ->
            val native = assertIs<LLMResponse.Chat.Ok>(CodexChatAPI(settings, oauth, client).message(
                LLMRequest.Chat(model = "future-model", maxTokens = 42,
                    messages = listOf(LLMRequest.Message(LLMMessageRole.user, "help")), functions = emptyList()),
            ))
            assertEquals("future-model", native.model)
            assertEquals(mapOf("ids" to listOf(1, 2)), native.choices.first().message.functionCall?.arguments)
            assertEquals("call_a", native.choices.first().message.functionsStateId)
            assertEquals("Working", native.choices.last().message.content)
            assertEquals(LLMResponse.Usage(3, 2, 5, 1), native.usage)
            val raw = restJsonMapper.readTree("""{"stream":true,"stream_options":{"include_usage":true},"messages":[{"role":"user","content":"help"}],"tools":[{"type":"function","function":{"name":"lookup","parameters":{"type":"object","properties":{"ids":{"type":"array","items":{"type":"integer"}}}}}}]}""") as ObjectNode
            val usages = mutableListOf<LLMResponse.Usage>()
            val chunks = CodexCompletionClient(CodexResponsesTransport(client, settings, oauth))
                .completion(raw, "future-model", timeoutMillis = 1_000, onUsage = { usages += it }).toList()
            assertEquals("[DONE]", chunks.last())
            val parsed = chunks.dropLast(1).map { restJsonMapper.readTree(it) }
            val deltas = parsed.flatMap { it.path("choices").toList() }.map { it.path("delta") }
            assertEquals("Working", deltas.joinToString("") { it.path("content").asText() })
            assertEquals("{\"ids\":[1,2]}", deltas.flatMap { it.path("tool_calls").toList() }
                .joinToString("") { it.path("function").path("arguments").asText() })
            assertEquals(listOf(native.usage), usages)
            assertEquals(5, parsed.last().path("usage").path("total_tokens").asInt())
            assertEquals("integer", captured.last().path("tools").first().path("parameters").path("properties").path("ids").path("items").path("type").asText())
            assertEquals(42, captured.first().path("max_output_tokens").asInt())
            assertTrue(captured.all { it.path("model").asText() == "future-model" && !it.path("store").asBoolean() })
        }
    }

    @Test
    fun `truncated streams and errors after a delta never produce successful completion`() = runTest {
        val settings = mockk<SettingsProvider>(relaxed = true)
        val oauth = mockk<CodexOAuthService>()
        coEvery { oauth.refreshTokenIfNeeded() } returns "token"
        for (terminal in listOf("", "event: error\ndata: {\"code\":\"server_is_overloaded\"}\n\n")) {
            HttpClient(MockEngine { respond("event: response.output_text.delta\ndata: {\"delta\":\"partial\"}\n\n" + terminal) }).use { client ->
                val published = mutableListOf<String>()
                val request = restJsonMapper.readTree("""{"stream":true,"messages":[{"role":"user","content":"help"}]}""") as ObjectNode
                assertFailsWith<CodexFailure> {
                    CodexCompletionClient(CodexResponsesTransport(client, settings, oauth))
                        .completion(request, "future-model", timeoutMillis = 1_000).collect { published += it }
                }
                assertEquals(1, published.size)
                assertTrue("[DONE]" !in published)
            }
        }
    }
}
