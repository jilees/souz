package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.llm.NAMED_LLM_CONFIG
import ru.souz.backend.llm.withNamedLlmConfig
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper
import ru.souz.backend.http.BackendHttpRoutes
import java.util.UUID

private const val GATEWAY_KEY = "gateway-test-key-0123456789abcdef0123456789"
private const val COMPLETIONS = "/llm/v1/chat/completions"
private const val MODEL_LIST = "/llm/v1/models"
private val GATEWAY_ENV = mapOf("SOUZ_LLM_GATEWAY_API_KEY" to GATEWAY_KEY)
private val CASCADE_CONFIG = NAMED_LLM_CONFIG.replace("\"connection\":\"experiment\"", "\"fallbacks\":[\"tv-agent\"],\"connection\":\"experiment\"")
    .replace("\"modelId\":\"shared-model\"", "\"modelId\":\"shared-model\",\"parameters\":{\"top_p\":0.8}")
    .replace("\"models\":", "\"cascades\":{\"fast\":[\"test-chat\",\"tv-agent\"]},\"models\":")

class BackendLlmGatewayE2eTest {
    @Test
    fun `gateway authenticates independently and preserves payloads across a configured cascade`() {
        val requests = CopyOnWriteArrayList<Pair<String, JsonNode>>()
        val upstream = HttpClient(MockEngine { request ->
            val body = restJsonMapper.readTree(request.body.toByteArray())
            requests += request.url.host to body
            assertEquals("shared-model", body.path("model").asText())
            val primary = request.url.host == "experiment.test"
            assertEquals("Bearer ${if (primary) "experiment-secret" else "router-secret"}", request.headers["Authorization"])
            if (primary) respond("{}", HttpStatusCode.ServiceUnavailable) else respond(
                """{"id":"completion","object":"chat.completion","model":"shared-model","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3}}""",
                headers = headersOf("Content-Type", "application/json"),
            )
        }) { providerHttpClientDefaults() }
        withNamedLlmConfig(CASCADE_CONFIG, GATEWAY_ENV) { source ->
            backendE2eTest("e2e_gateway", settingsSource = source, providerClients = ProviderHttpClients(upstream, upstream)) {
                assertEquals(HttpStatusCode.Unauthorized, client.get(MODEL_LIST).status)
                assertEquals(HttpStatusCode.Unauthorized, client.get(MODEL_LIST) { header("Authorization", "Bearer wrong") }.status)
                assertEquals(listOf("fast", "test-chat", "tv-agent"), client.get(MODEL_LIST) {
                    header("Authorization", "Bearer $GATEWAY_KEY")
                }.jsonBody()["data"].map { it["id"].asText() })
                val body = """{"model":"test-chat","messages":[{"role":"user","content":[{"type":"text","text":"Hi"}]}],"tools":[{"type":"function","function":{"name":"test","parameters":{"type":"object","properties":{"x":{"type":"array","items":{"type":"integer"}}}}}}],"response_format":{"type":"json_object"},"top_p":0.4}"""
                repeat(4) {
                    val response = client.post(COMPLETIONS) { header("Authorization", "Bearer $GATEWAY_KEY"); jsonBody(body) }
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertEquals("OK", response.jsonBody()["choices"][0]["message"]["content"].asText())
                }
                assertEquals(listOf("experiment.test", "router.test", "experiment.test", "router.test", "experiment.test", "router.test", "router.test"), requests.map { it.first })
                for ((_, sent) in requests) {
                    val original = restJsonMapper.readTree(body)
                    assertEquals(original["messages"], sent["messages"])
                    assertEquals(original["tools"], sent["tools"])
                    assertEquals(original["response_format"], sent["response_format"])
                    assertEquals(0.4, sent["top_p"].asDouble())
                }
                for ((invalid, status) in listOf("{}" to HttpStatusCode.BadRequest,
                    "{" to HttpStatusCode.BadRequest,
                    """{"model":"missing","messages":[{"role":"user","content":"Hi"}]}""" to HttpStatusCode.NotFound)) {
                    assertEquals(status, client.post(COMPLETIONS) { header("Authorization", "Bearer $GATEWAY_KEY"); jsonBody(invalid) }.status)
                }
                val openApi = client.get("/docs/openapi.json").jsonBody()
                assertTrue(openApi["paths"][COMPLETIONS]["post"]["security"].toString().contains("llmGatewayBearer"))
                val userId = UUID.randomUUID().toString()
                val chatId = createPublicChat(userId)
                assertEquals(HttpStatusCode.OK, client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(userId); jsonBody("""{"defaultModel":"fast"}""")
                }.status)
                val turn = client.post(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userId); jsonBody("""{"content":"Hi","options":{"model":"fast"}}""")
                }
                assertEquals(HttpStatusCode.OK, turn.status)
                eventually("agent answer through the same cascade") {
                    client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
                        .takeIf { it.last()["content"].asText() == "OK" }
                }
                assertEquals("router.test", requests.last().first)
            }
        }
    }

    @Test
    fun `primary timeout allows gateway and native agent to use the configured fallback`() {
        val calls = CopyOnWriteArrayList<String>()
        val upstream = HttpClient(MockEngine { request ->
            calls += request.url.host
            if (request.url.host == "experiment.test") kotlinx.coroutines.delay(60_000)
            respond("""{"model":"shared-model","choices":[{"message":{"role":"assistant","content":"Timeout fallback"},"finish_reason":"stop"}]}""",
                headers = headersOf("Content-Type", "application/json"))
        }) { providerHttpClientDefaults() }
        val config = CASCADE_CONFIG.replace("\"connection\":\"experiment\"", "\"timeoutMs\":100,\"connection\":\"experiment\"")
        withNamedLlmConfig(config, GATEWAY_ENV) { source ->
            backendE2eTest("e2e_gateway_timeout", settingsSource = source, providerClients = ProviderHttpClients(upstream, upstream)) {
                assertEquals(HttpStatusCode.OK, client.post(COMPLETIONS) {
                    header("Authorization", "Bearer $GATEWAY_KEY")
                    jsonBody("""{"model":"fast","messages":[{"role":"user","content":"Hi"}]}""")
                }.status)
                val userId = UUID.randomUUID().toString()
                val chatId = createPublicChat(userId)
                assertEquals(HttpStatusCode.OK, client.post(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userId); jsonBody("""{"content":"Hi","options":{"model":"fast"}}""")
                }.status)
                eventually("native fallback after primary timeout") {
                    client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
                        .takeIf { it.last()["content"].asText() == "Timeout fallback" }
                }
                assertEquals(listOf("experiment.test", "router.test", "experiment.test", "router.test"), calls.toList())
            }
        }
    }

    @Test
    fun `gateway stream fails over before output and never replays a partial response`() {
        val calls = CopyOnWriteArrayList<String>()
        var partial = false
        var allDown = false
        val upstream = HttpClient(MockEngine { request ->
            calls += request.url.host
            if (allDown || (request.url.host == "experiment.test" && !partial)) respond("{}", HttpStatusCode.ServiceUnavailable)
            else respond("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n" + if (partial) "" else "data: [DONE]\n\n",
                headers = headersOf("Content-Type", "text/event-stream"))
        }) { providerHttpClientDefaults() }
        withNamedLlmConfig(CASCADE_CONFIG, GATEWAY_ENV) { source ->
            backendE2eTest("e2e_gateway_stream", settingsSource = source, providerClients = ProviderHttpClients(upstream, upstream)) {
                suspend fun complete() = client.post(COMPLETIONS) {
                    header("Authorization", "Bearer $GATEWAY_KEY")
                    jsonBody("""{"model":"test-chat","messages":[{"role":"user","content":"Hi"}],"stream":true}""")
                }
                val result = complete()
                assertEquals(HttpStatusCode.OK, result.status)
                val streamText = result.bodyAsText()
                assertTrue(streamText.contains("data: [DONE]"), streamText)
                assertEquals(listOf("experiment.test", "router.test"), calls.toList())
                calls.clear()
                partial = true
                val broken = complete().bodyAsText()
                assertTrue(broken.contains("hello"))
                assertTrue(broken.contains("upstream_stream_incomplete"))
                assertFalse(broken.contains("[DONE]"))
                assertEquals(listOf("experiment.test"), calls.toList())
                allDown = true
                val unavailable = complete()
                assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
                assertEquals("upstream_error", unavailable.jsonBody()["error"]["code"].asText())
            }
        }
    }

    @Test
    fun `Codex uses server OAuth and converts schemas tool history and completion events`() {
        val calls = CopyOnWriteArrayList<JsonNode>()
        val upstream = HttpClient(MockEngine { request ->
            assertEquals("codex.test", request.url.host)
            assertEquals("/backend-api/codex/responses", request.url.encodedPath)
            assertEquals("Bearer codex-access", request.headers["Authorization"])
            assertEquals("codex-account", request.headers["Chatgpt-Account-Id"])
            calls.add(restJsonMapper.readTree((request.body as TextContent).text))
            respond(CODEX_GATEWAY_EVENTS, headers = headersOf("Content-Type", "text/event-stream"))
        }) { providerHttpClientDefaults() }
        val environment = GATEWAY_ENV + mapOf("CODEX_BASE_URL" to "https://codex.test", "CODEX_ACCESS_TOKEN" to "codex-access",
            "CODEX_REFRESH_TOKEN" to "codex-refresh", "CODEX_ACCOUNT_ID" to "codex-account", "CODEX_EXPIRES_AT" to "9999999999")
        val dynamic = NAMED_LLM_CONFIG.replace("\"models\": {", "\"reasoningEfforts\":{\"next-codex\":\"low\"},\"models\": {\"next-codex\":{\"provider\":\"codex\",\"modelId\":\"future-model-without-enum\"},")
        withNamedLlmConfig(dynamic, environment) { source ->
            backendE2eTest("e2e_gateway_codex", settingsSource = source, providerClients = ProviderHttpClients(upstream, upstream)) {
                val listing = client.get(MODEL_LIST) { header("Authorization", "Bearer $GATEWAY_KEY") }.jsonBody()
                assertTrue(listing["data"].any { it["id"].asText() == "gpt-6.1-sol" })
                assertTrue(listing["data"].any { it["id"].asText() == "next-codex" })
                val capability = client.get(BackendHttpRoutes.BOOTSTRAP) { trusted(UUID.randomUUID().toString()) }
                    .jsonBody()["capabilities"]["models"].single { it["model"].asText() == "next-codex" }
                assertEquals("codex", capability["provider"].asText())
                for (stream in listOf(false, true)) {
                    val response = client.post(COMPLETIONS) {
                        header("Authorization", "Bearer $GATEWAY_KEY")
                        jsonBody("""{"model":"next-codex","messages":[{"role":"developer","content":"Be helpful"},{"role":"assistant","content":null,"tool_calls":[{"id":"old","type":"function","function":{"name":"test","arguments":"{}"}}]},{"role":"tool","tool_call_id":"old","content":"done"}],"tools":[{"type":"function","function":{"name":"test","parameters":{"type":"object","properties":{"items":{"type":"array","items":{"type":"integer"}}},"additionalProperties":false}}}],"response_format":{"type":"json_schema","json_schema":{"name":"result","strict":true,"schema":{"type":"object"}}},"stream":$stream,"stream_options":{"include_usage":true}}""")
                    }
                    val text = response.bodyAsText()
                    assertEquals(HttpStatusCode.OK, response.status, text)
                    if (stream) {
                        assertTrue(text.contains("chat.completion.chunk"))
                        assertTrue(text.contains("\"arguments\":\"{}\""))
                        assertTrue(text.contains("[DONE]"))
                    } else {
                        val body = restJsonMapper.readTree(text)
                        assertEquals("tool_calls", body["choices"][0]["finish_reason"].asText())
                        assertEquals("call_new", body["choices"][0]["message"]["tool_calls"][0]["id"].asText())
                        assertEquals(3, body["usage"]["total_tokens"].asInt())
                    }
                }
                for (request in calls) {
                    assertEquals("future-model-without-enum", request["model"].asText())
                    assertEquals("low", request["reasoning"]["effort"].asText())
                    assertEquals("Be helpful", request["instructions"].asText())
                    assertEquals("function_call", request["input"][0]["type"].asText())
                    assertEquals("function_call_output", request["input"][1]["type"].asText())
                    assertEquals("integer", request["tools"][0]["parameters"]["properties"]["items"]["items"]["type"].asText())
                    assertEquals("json_schema", request["text"]["format"]["type"].asText())
                }
                assertEquals(4.0, backend.dependencies.metrics.registry.get("souz.llm.tokens")
                    .tag("direction", "input").tag("provider", "codex").tag("model", "other").counter().count())
            }
        }
    }
    @Test
    fun `missing Codex model advances the same configured chain in gateway and native agent`() {
        val calls = CopyOnWriteArrayList<String>()
        val upstream = HttpClient(MockEngine { request ->
            calls += request.url.host
            if (request.url.host == "codex.test") {
                respond("""{"error":{"code":"model_not_found","message":"Unavailable model"}}""", HttpStatusCode.BadRequest)
            } else {
                assertEquals("router.test", request.url.host)
                respond("""{"model":"shared-model","choices":[{"message":{"role":"assistant","content":"Backup answer"},"finish_reason":"stop"}]}""",
                    headers = headersOf("Content-Type", "application/json"))
            }
        }) { providerHttpClientDefaults() }
        val environment = GATEWAY_ENV + mapOf("CODEX_BASE_URL" to "https://codex.test", "CODEX_ACCESS_TOKEN" to "codex-access",
            "CODEX_REFRESH_TOKEN" to "codex-refresh", "CODEX_ACCOUNT_ID" to "codex-account", "CODEX_EXPIRES_AT" to "9999999999")
        val config = NAMED_LLM_CONFIG.replace("\"models\": {",
            "\"cascades\":{\"reasoning\":[\"missing-codex\",\"tv-agent\"]},\"models\": {\"missing-codex\":{\"provider\":\"codex\",\"modelId\":\"removed-model\"},")
        withNamedLlmConfig(config, environment) { source ->
            backendE2eTest("e2e_codex_fallback", settingsSource = source, providerClients = ProviderHttpClients(upstream, upstream)) {
                val external = client.post(COMPLETIONS) {
                    header("Authorization", "Bearer $GATEWAY_KEY")
                    jsonBody("""{"model":"reasoning","messages":[{"role":"user","content":"Hi"}]}""")
                }
                assertEquals(HttpStatusCode.OK, external.status)
                assertEquals("Backup answer", external.jsonBody()["choices"][0]["message"]["content"].asText())
                val userId = UUID.randomUUID().toString()
                val chatId = createPublicChat(userId)
                assertEquals(HttpStatusCode.OK, client.post(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userId); jsonBody("""{"content":"Hi","options":{"model":"reasoning"}}""")
                }.status)
                eventually("native agent fallback after a Codex model error") {
                    client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
                        .takeIf { it.last()["content"].asText() == "Backup answer" }
                }
                assertEquals(listOf("codex.test", "router.test", "codex.test", "router.test"), calls.toList())
            }
        }
    }

}

private const val CODEX_GATEWAY_EVENTS = """data: {"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"call_new","name":"test"}}

data: {"type":"response.function_call_arguments.delta","output_index":0,"delta":"{}"}

data: {"type":"response.completed","response":{"output":[{"type":"function_call","call_id":"call_new","name":"test","arguments":"{}"}],"usage":{"input_tokens":2,"output_tokens":1,"total_tokens":3}}}

"""
