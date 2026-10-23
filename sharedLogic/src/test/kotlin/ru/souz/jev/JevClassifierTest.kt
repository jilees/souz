package ru.souz.jev

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.SkillId
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper
import ru.souz.llms.runtime.JevClassifier
import ru.souz.llms.runtime.jevState
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JevClassifierTest {
    private val request = LLMRequest.Chat(
        model = "conversational-model",
        messages = listOf(
            LLMRequest.Message(LLMMessageRole.system, "LLM formatting instructions"),
            LLMRequest.Message(LLMMessageRole.user, "Prepare for today's meetings"),
            LLMRequest.Message(LLMMessageRole.user, "Summarize them"),
            LLMRequest.Message(LLMMessageRole.assistant, "", functionCall = LLMRequest.FunctionCall("ListEvents", "{}")),
            LLMRequest.Message(LLMMessageRole.function, "private tool result"),
            LLMRequest.Message(LLMMessageRole.assistant, " You have two meetings. " + "x".repeat(400)),
            LLMRequest.Message(LLMMessageRole.user, "Check my calendar and email the agenda"),
        ),
    )
    private val turnState = "Previous discussion (use only if relevant):\nuser: Summarize them\nassistant: " +
        (" You have two meetings. " + "x".repeat(400)).take(300).trim() +
        "\n\nCurrent user message to classify:\nCheck my calendar and email the agenda"
    private val categories = mapOf(
        ToolCategory.CALENDAR to "Read and edit calendar events",
        ToolCategory.MAIL to "Read and send email",
        ToolCategory.FILES to "Read and edit files",
        ToolCategory.DESKTOP to "Capture the screen",
        ToolCategory.IMAGE to "Analyze images",
    )
    private val sides = mapOf(
        "true" to "the request or at least one of its steps needs it",
        "false" to "no step of the request needs this; a mentioned topic alone does not count",
    )

    @Test
    fun `state puts the new message first and earlier non-empty ones newest first, cut to 300 characters`() {
        val assistant = ("You have two meetings. " + "x".repeat(400)).take(299)
        assertEquals(
            "New user message:\nCheck my calendar and email the agenda\n\nPrevious messages, newest first:\n" +
                "Assistant: $assistant\nUser: Summarize them\nUser: Prepare for today's meetings",
            jevState(request.messages),
        )
        assertEquals(
            "New user message:\nCheck my calendar and email the agenda\n\nPrevious messages, newest first:\n" +
                "Assistant: $assistant\nUser: Summarize them",
            jevState(request.messages, historyLimit = 2),
        )
        assertEquals("Check my calendar", jevState(listOf(request.messages[0], LLMRequest.Message(LLMMessageRole.user, "Check my calendar"))))
    }

    @Test
    fun `categories are Noul questions selected strictly above the threshold`() = runTest {
        val screen = "; understanding the current screen needs both screen capture and image analysis, " +
            "unless a path to an existing image was supplied"
        val expectedQuestions = categories.entries.associate { (category, description) ->
            val rule = if (category in setOf(ToolCategory.DESKTOP, ToolCategory.IMAGE)) screen else ""
            category.name to mapOf(
                "instructions" to "Does fulfilling the latest user request require this tool category: ${category.name} ($description$rule)?",
                "type" to "noul",
                "criteria" to sides,
            )
        }
        val engine = MockEngine { httpRequest ->
            assertEquals("https://api.typesafe.ai/v1/systemone", httpRequest.url.toString())
            assertEquals(HttpMethod.Post, httpRequest.method)
            assertEquals("Bearer test-token", httpRequest.headers[HttpHeaders.Authorization])
            assertEquals(2_000L, httpRequest.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis)
            val payload = restJsonMapper.readTree(httpRequest.body.toByteArray())
            assertEquals("jev-configured", payload["model"].asText())
            assertEquals(jevState(request.messages), payload["state"].asText())
            assertEquals(restJsonMapper.valueToTree<JsonNode>(expectedQuestions), payload["questions"])
            respond(
                """{"answers":{"CALENDAR":{"type":"noul","noul":0.9},"MAIL":{"type":"noul","noul":0.5},""" +
                    """"FILES":{"type":"noul","noul":0.51},"DESKTOP":{"type":"noul","noul":0.2},"IMAGE":{"type":"noul","noul":0}}}""",
            )
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { http ->
            val client = JevClient(http, "test-token", "jev-configured")
            val cases = mapOf(
                0.0 to listOf(ToolCategory.CALENDAR, ToolCategory.MAIL, ToolCategory.FILES, ToolCategory.DESKTOP),
                0.5 to listOf(ToolCategory.CALENDAR, ToolCategory.FILES),
                0.9 to emptyList(),
            )
            cases.forEach { (threshold, expected) ->
                val result = JevClassifier(client, threshold).classify(request, categories)
                assertEquals(expected, result.categories)
                assertNull(result.confidence)
            }
            assertEquals(emptyList(), JevClassifier(client).classify(request, emptyMap()).categories)
            assertEquals(cases.size, engine.requestHistory.size)
        }
    }

    @Test
    fun `Skills are Noul questions over the last two messages`() = runTest {
        val paper = SkillId("Paper.S17")
        val music = SkillId("music")
        val descriptions = mapOf(paper to "Summarize papers.", music to "Compose music.")
        val engine = MockEngine { httpRequest ->
            val payload = restJsonMapper.readTree(httpRequest.body.toByteArray())
            assertEquals(jevState(request.messages, historyLimit = 2), payload["state"].asText())
            assertEquals(
                restJsonMapper.valueToTree<JsonNode>(descriptions.entries.associate { (id, description) ->
                    id.value to mapOf(
                        "instructions" to "Does fulfilling the latest user request require this Skill: ${id.value} ($description)?",
                        "type" to "noul",
                        "criteria" to sides,
                    )
                }),
                payload["questions"],
            )
            respond("""{"answers":{"Paper.S17":{"type":"noul","noul":0.9},"music":{"type":"noul","noul":0.5}}}""")
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { http ->
            val client = JevClient(http, "test-token")
            assertEquals(setOf(paper), JevClassifier(client).selectSkills(request, descriptions))
            assertEquals(emptySet(), JevClassifier(client).selectSkills(request, emptyMap()))
            assertEquals(1, engine.requestHistory.size)
        }
    }

    @Test
    fun `simple route requires ninety percent confidence over the last two messages`() = runTest {
        var complex = 0.0
        val engine = MockEngine { httpRequest ->
            val payload = restJsonMapper.readTree(httpRequest.body.toByteArray())
            assertEquals(turnState, payload["state"].asText())
            val question = payload["questions"]["choice"]
            assertEquals("choice", question["type"].asText())
            assertEquals(listOf("simple", "complex"), question["criteria"].fieldNames().asSequence().toList())
            respond("""{"answers":{"choice":{"type":"choice","probabilities":{"simple":${1 - complex},"complex":$complex}}}}""")
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { http ->
            val classifier = JevClassifier(JevClient(http, "test-token"))
            assertEquals(listOf(false, false, true, true), listOf(0.0, 0.1, 0.11, 0.9).map { complex = it; classifier.isComplex(request) })
        }
    }

    @Test
    fun `early complexity and memory decisions share one request and independent thresholds`() = runTest {
        var complex = 0.1
        var skip = 0.9
        var malformedMemory = false
        val engine = MockEngine { httpRequest ->
            val payload = restJsonMapper.readTree(httpRequest.body.toByteArray())
            assertEquals(turnState, payload["state"].asText())
            assertEquals(setOf("complexity", "memory"), payload["questions"].fieldNames().asSequence().toSet())
            assertTrue(payload["questions"].all { it["type"].asText() == "choice" })
            respond("""{"answers":{
              "complexity":{"type":"choice","probabilities":{"simple":${1-complex},"complex":$complex}},
              "memory":${if (malformedMemory) "{}" else """{"type":"choice","probabilities":{"skip":$skip,"recall":${1-skip}}}"""}
            }}""")
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { http ->
            val classifier = JevClassifier(JevClient(http, "test-token"))
            for ((complexity, memorySkip) in listOf(0.1 to 0.8, 0.11 to 0.9, 0.0 to 0.79, 0.9 to 0.1)) {
                complex = complexity; skip = memorySkip
                val result = classifier.classify(request)
                assertEquals(complex > 0.1, result.complex)
                assertEquals(skip < 0.8, result.recallMemory)
                assertEquals(complex <= 0.1 && skip >= 0.8, result.expectedFast)
            }
            malformedMemory = true
            assertFailsWith<IllegalStateException> { classifier.classify(request) }
            assertEquals(5, engine.requestHistory.size)
        }
    }

    @Test
    fun `routing preserves the entire current message when there is no preceding dialogue`() = runTest {
        val current = "Translate the supplied text: " + "a".repeat(1000)
        HttpClient(MockEngine { httpRequest ->
            val payload = restJsonMapper.readTree(httpRequest.body.toByteArray())
            assertEquals(current, payload["state"].asText())
            respond("""{"answers":{"choice":{"type":"choice","probabilities":{"simple":1.0,"complex":0.0}}}}""")
        }) { providerHttpClientDefaults() }.use { http ->
            assertEquals(false, JevClassifier(JevClient(http, "test-token")).isComplex(request.copy(messages = listOf(
                request.messages.first(), LLMRequest.Message(LLMMessageRole.user, current),
            ))))
        }
    }

    @Test
    fun `attachments always require the strong model even with a confidently simple text decision`() = runTest {
        HttpClient(MockEngine { respond("""{"answers":{
          "choice":{"type":"choice","probabilities":{"simple":1.0,"complex":0.0}},
          "complexity":{"type":"choice","probabilities":{"simple":1.0,"complex":0.0}},
          "memory":{"type":"choice","probabilities":{"skip":1.0,"recall":0.0}}
        }}""") }) { providerHttpClientDefaults() }.use { http ->
            val classifier = JevClassifier(JevClient(http, "test-token"))
            val file = ru.souz.llms.FileContent(ru.souz.llms.FileReference("owned"), "report.txt", "text/plain", 4)
            val withFile = request.copy(messages = listOf(LLMRequest.Message(LLMMessageRole.user, "Привет", files = listOf(file))))
            val withImage = request.copy(messages = listOf(LLMRequest.Message(LLMMessageRole.user, "Что тут?",
                images = listOf(ru.souz.llms.ImageContent(ru.souz.llms.FileReference("image"))))))
            for (attached in listOf(withFile, withImage)) {
                assertTrue(classifier.isComplex(attached))
                assertTrue(classifier.classify(attached).complex)
            }
        }
    }

    @Test
    fun `missing malformed and failed responses reject classification`() = runTest {
        var body = ""
        var status = HttpStatusCode.OK
        HttpClient(MockEngine { respond(body, status) }) { providerHttpClientDefaults() }.use { http ->
            val client = JevClient(http, "test-token")
            val state = restJsonMapper.valueToTree<JsonNode>("private message")
            val questions = mapOf("calendar" to "Does this request require calendar access?")
            val invalid = listOf("", "null", "not json", "{}", """{"answers":{"calendar":{"type":"choice","noul":0.9}}}""") +
                listOf("null", "\"0.9\"", "-0.1", "1.1", "1e999").map {
                    """{"answers":{"calendar":{"type":"noul","noul":$it}}}"""
                }
            for (response in invalid) {
                body = response
                assertFailsWith<IllegalStateException>(response) { client.evaluate(state, questions) }
            }
            // Probability values share the Noul validation above.
            val invalidChoice = listOf("""{"type":"noul","probabilities":{"A":0.5,"B":0.5}}""", """{"type":"choice","probabilities":{"A":1.0}}""")
            for (response in invalidChoice) {
                body = """{"answers":{"choice":$response}}"""
                assertFailsWith<IllegalStateException>(response) { client.choose(state, "Which one?", mapOf("A" to "A", "B" to "B")) }
            }
            status = HttpStatusCode.Unauthorized
            body = "test-token and private message"
            val failure = assertFailsWith<IllegalStateException> { client.evaluate(state, questions) }
            assertEquals("Jev request failed (HTTP 401)", failure.message)
        }
    }

    @Test
    fun `invalid configuration fails before IO`() {
        HttpClient(MockEngine { error("HTTP must not be called") }).use { http ->
            assertFailsWith<IllegalArgumentException> { JevClient(http, " ") }
            assertFailsWith<IllegalArgumentException> { JevClient(http, "test-token", " ") }
            val client = JevClient(http, "test-token")
            for (threshold in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1)) {
                assertFailsWith<IllegalArgumentException> { JevClassifier(client, threshold) }
            }
        }
    }
}
