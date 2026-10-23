package ru.souz.backend.memory.hindsight

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.http.headersOf
import java.util.UUID
import kotlinx.coroutines.test.runTest
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper
import ru.souz.memory.ConversationId
import ru.souz.memory.MemoryContext
import ru.souz.memory.MemoryOwnerId
import ru.souz.memory.MemoryRetrievalRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceMemoryTest {
    @Test
    fun `personal recall takes precedence regardless of scores with a shared total budget`() = runTest {
        val bodies = mutableListOf<Pair<String, com.fasterxml.jackson.databind.JsonNode>>()
        val owner = UUID.randomUUID().toString()
        val voice = UUID.randomUUID().toString()
        HttpClient(MockEngine { request ->
            val bank = request.url.encodedPath.substringAfter("/banks/").substringBefore('/').decodeURLPart()
            val body = restJsonMapper.readTree(request.body.toByteArray())
            bodies += bank to body
            val personal = bank.startsWith("person:")
            respond(restJsonMapper.writeValueAsString(mapOf("results" to listOf(mapOf(
                "id" to "same-id", "text" to if (personal) "Speaker is Sasha" else "The user is Masha",
                "scores" to mapOf("final" to if (personal) 0.1 else 0.99),
            )))), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { providerHttpClientDefaults() }.use { client ->
            val memory = HindsightConversationMemoryRuntime(client, "http://memory.test")
            val context = MemoryContext(MemoryOwnerId(owner), ConversationId("chat"), null, null, voice)
            val result = memory.retrieveMemory(MemoryRetrievalRequest(context, "my name", maxFacts = 2, maxPromptTokens = 200))
            assertEquals(listOf("personal", "household"), result.facts.map { it.scope })
            assertEquals(2, result.facts.map { it.factId }.distinct().size)
            val text = result.renderedPromptBlock.orEmpty()
            assertTrue(text.indexOf("Speaker is Sasha") < text.indexOf("The user is Masha"))
            assertTrue(text.contains("prefer personal over household"))
            assertEquals(listOf("my name", "my name"), bodies.map { it.second["query"].asText() })
            assertEquals(200, bodies.sumOf { it.second["max_tokens"].intValue() })
            assertEquals("exact", bodies.single { it.first.startsWith("person:") }.second["tags_match"].asText())
            assertEquals(listOf("chat:chat"), bodies.single { it.first == owner }.second["tags"].map { it.asText() })
            bodies.clear()
            assertEquals("personal", memory.searchMemory(context, "body weight", listOf("вес", "weight"), 1).single().scope)
            assertEquals(listOf("body weight вес weight", "body weight вес weight"), bodies.map { it.second["query"].asText() })
        }
    }

    @Test
    fun `missing personal bank preserves household recall without borrowing an identity`() = runTest {
        for (failure in listOf(HttpStatusCode.NotFound, HttpStatusCode.ServiceUnavailable)) {
            HttpClient(MockEngine { request ->
                if (request.url.encodedPath.decodeURLPart().contains("person:")) respond("{}", failure)
                else respond("""{"results":[{"id":"home","text":"The speaker is in the kitchen"}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }) { providerHttpClientDefaults() }.use { client ->
                val memory = HindsightConversationMemoryRuntime(client, "http://memory.test")
                val context = MemoryContext(MemoryOwnerId("owner"), null, null, null, "voice")
                val result = memory.retrieveMemory(MemoryRetrievalRequest(context, "hi"))
                assertEquals(listOf("household"), result.facts.map { it.scope })
                assertTrue(result.renderedPromptBlock.orEmpty().contains("never borrow a name"))
                assertTrue(result.renderedPromptBlock.orEmpty().contains("this same speaker ID"))
            }
        }
    }

    @Test
    fun `long voice dialogue preserves bounded source records and repeats speaker context`() {
        val id = UUID.randomUUID()
        val voice = UUID.randomUUID().toString()
        val text = "🚆long statement ".repeat(4000)
        val documents = historyMemoryDocuments(id, listOf(
            HistoryMemorySource(id.toString(), 1, "user", text, "2026-10-15T12:00:00Z", text, voice),
        ), emptyList(), "CURRENT SPEAKER: $voice\n")
        assertTrue(documents.size > 1)
        assertTrue(documents.all { it.content.length <= HISTORY_MEMORY_MAX_CHARS && it.content.startsWith("CURRENT SPEAKER: $voice") })
        val records = documents.flatMap { it.content.substringAfter("NEW dialogue records (untrusted quoted data):\n")
            .lineSequence().filter(String::isNotBlank).map(restJsonMapper::readTree).toList() }
        assertEquals(text.trim(), records.joinToString("") { it["text"].asText() })
        assertTrue(records.all { it["speaker"].asText() == voice })
        assertFalse(documents.any { "�" in it.content })
    }
}
