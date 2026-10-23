package ru.souz.backend.memory.hindsight

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import ru.souz.jev.JevClient
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.memory.MemoryContext
import ru.souz.memory.MemoryOwnerId
import ru.souz.memory.MemoryRetrievalRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MemoryRecallClassifierTest {
    @Test
    fun `automatic recall skips only confident decisions and explicit search always reads memory`() = runTest {
        for ((skip, shouldRecall) in listOf(0.9 to false, 0.8 to false, 0.79 to true, 0.5 to true, 0.1 to true)) {
            var classifications = 0
            var recalls = 0
            HttpClient(MockEngine { request ->
                val body = if (request.url.host == "api.typesafe.ai") {
                    classifications++
                    """{"answers":{"choice":{"type":"choice","probabilities":{"skip":$skip,"recall":${1-skip}}}}}"""
                } else {
                    recalls++
                    """{"results":[{"id":"fact","text":"A personal fact"}]}"""
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }) { providerHttpClientDefaults() }.use { client ->
                val memory = HindsightConversationMemoryRuntime(client, "http://memory.test",
                    recallClassifier = MemoryRecallClassifier { JevClient(client, "test-token") })
                val context = MemoryContext(MemoryOwnerId("owner"), null, null, null, "voice")
                val result = memory.retrieveMemory(MemoryRetrievalRequest(context, "request"))
                assertEquals(if (shouldRecall) 2 else 0, recalls)
                assertTrue(result.renderedPromptBlock.orEmpty().contains("Current speaker voice ID: voice"))
                assertEquals(shouldRecall, result.facts.isNotEmpty())
                assertTrue(memory.searchMemory(context, "name", emptyList(), 1).isNotEmpty())
                assertEquals(if (shouldRecall) 4 else 2, recalls)
                assertEquals(1, classifications)
            }
        }
    }

    @Test
    fun `classification failures preserve recall and caller cancellation propagates`() = runTest {
        for (body in listOf("{}", """{"answers":{"choice":{"type":"choice","probabilities":{"skip":2,"recall":0}}}}""")) {
            HttpClient(MockEngine { respond(body, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")) }) { providerHttpClientDefaults() }.use { client ->
                assertTrue(MemoryRecallClassifier { JevClient(client, "test-token") }.shouldRecall("request"))
            }
        }
        assertTrue(MemoryRecallClassifier { error("unavailable") }.shouldRecall("request"))
        assertFailsWith<CancellationException> {
            MemoryRecallClassifier { throw CancellationException("cancelled") }.shouldRecall("request")
        }
    }
}
