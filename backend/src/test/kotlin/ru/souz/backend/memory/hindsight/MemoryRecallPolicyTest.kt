package ru.souz.backend.memory.hindsight

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.memory.MemoryContext
import ru.souz.memory.MemoryOwnerId
import ru.souz.memory.MemoryRetrievalRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryRecallPolicyTest {
    @Test
    fun `skipping facts preserves attribution and explicit search still reads both scoped banks`() = runTest {
        var recalls = 0
        HttpClient(MockEngine {
            recalls++
            respond("""{"results":[{"id":"fact","text":"A personal fact"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { providerHttpClientDefaults() }.use { client ->
            val memory = HindsightConversationMemoryRuntime(client, "http://memory.test")
            val context = MemoryContext(MemoryOwnerId("owner"), null, null, null, "voice")
            val skipped = memory.retrieveMemory(MemoryRetrievalRequest(context, "greeting", recallFacts = false))
            assertEquals(0, recalls)
            assertTrue(skipped.facts.isEmpty())
            assertTrue(skipped.renderedPromptBlock.orEmpty().contains("Current speaker voice ID: voice"))
            assertTrue(memory.searchMemory(context, "name", emptyList(), 1).isNotEmpty())
            assertEquals(2, recalls)
            assertTrue(memory.retrieveMemory(MemoryRetrievalRequest(context, "name")).facts.isNotEmpty())
            assertEquals(4, recalls)
        }
    }
}
