package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.souz.llms.LLMMessageRole

class BackendMemoryForgettingE2eTest {
    private val hindsight = HistoryHindsightStub()

    @Test
    fun `forget all discovers every owned personal bank before deleting and keeps other accounts`() {
        for (pageSize in listOf(null, 1)) {
            val remote = HistoryHindsightStub().apply { bankPageSize = pageSize }
            val results = CopyOnWriteArrayList<JsonNode>()
            backendE2eTest("memory_forget_all_banks", hindsightUrl = HINDSIGHT_TEST_URL,
                providerClients = remote.clients(), llm = forgetLlm(results)) {
                val owner = UUID.randomUUID().toString()
                val other = UUID.randomUUID().toString()
                val personal = List(2) { "person:$owner:${UUID.randomUUID()}" }
                remote.banks += listOf(other, personal[0], "person:$other:${UUID.randomUUID()}", personal[1],
                    "person:$owner:invalid", "person:${owner}extra:${UUID.randomUUID()}")
                val chat = createPublicChat(owner)
                suspend fun requestConfirmation(): String {
                    submit(owner, chat, "ForgetAllMemory")
                    assertEquals(false, results.last()["forgotten"].asBoolean())
                    return results.last()["confirmationId"].asText()
                }
                val first = requestConfirmation()
                assertTrue(remote.paths.none { it.endsWith("/banks") })
                assertTrue(remote.deletedBanks.isEmpty())
                remote.listBanksStatus = HttpStatusCode.ServiceUnavailable
                submit(owner, chat, "ForgetAllMemory $first")
                assertEquals("forget_failed", results.last()["error"]["code"].asText())
                assertTrue(remote.deletedBanks.isEmpty())
                remote.listBanksStatus = HttpStatusCode.OK
                val confirmation = requestConfirmation()
                submit(owner, chat, "ForgetAllMemory $confirmation")
                assertTrue(results.last()["forgotten"].asBoolean())
                assertEquals(listOf(owner) + personal, remote.deletedBanks)
                submit(owner, chat, "ForgetAllMemory $confirmation")
                assertEquals("confirmation_invalid", results.last()["error"]["code"].asText())
            }
        }
    }

    @Test
    fun `voice SearchMemory fact IDs can be forgotten in their own banks`() {
        val ids = mutableListOf<String>()
        val results = mutableListOf<JsonNode>()
        hindsight.recalledText = "A saved preference"
        backendE2eTest("voice_forget_search", hindsightUrl = HINDSIGHT_TEST_URL,
            providerClients = hindsight.clients(), llm = E2eLlmApi { request ->
                val turn = request.messages.takeLastWhile { it.role != LLMMessageRole.user }
                    .filter { it.role == LLMMessageRole.function }
                when (turn.size) {
                    0 -> toolCallReply(request, "SearchMemory", mapOf("semanticQuery" to "preferences", "lexicalHints" to listOf("preference")))
                    1 -> {
                        ids += jacksonObjectMapper().readTree(turn.single().content)["facts"].map { it["factId"].asText() }
                        toolCallReply(request, "ForgetMemoryFact", mapOf("factId" to ids[0]))
                    }
                    2 -> {
                        results.add(jacksonObjectMapper().readTree(turn.last().content))
                        toolCallReply(request, "ForgetMemoryFact", mapOf("factId" to ids[1]))
                    }
                    else -> {
                        results.add(jacksonObjectMapper().readTree(turn.last().content))
                        reply(request, "Done")
                    }
                }
            }) {
            val owner = UUID.randomUUID().toString()
            val voice = UUID.randomUUID().toString()
            val personal = "person:$owner:$voice"
            hindsight.facts += listOf(personal to "stored-fact", owner to "stored-fact")
            hindsight.pendingRetainBanks += personal
            val chat = createPublicChat(owner)
            withPublicSocket(chat) { socket ->
                val frame = json.readTree(messageFrame(chat, owner, "forget", text = "Forget my saved preference"))
                (frame["payload"] as ObjectNode).set<JsonNode>("voice", json.valueToTree(mapOf("embeddingId" to voice)))
                socket.send(Frame.Text(frame.toString()))
                assertEquals("accepted", readJson(socket)["status"].asText())
                assertEquals("thread.status", readJson(socket)["type"].asText())
                val completed = readJson(socket)
                assertEquals("thread.completed", completed["type"].asText(), completed.toString())
            }
            assertEquals(listOf("$personal:stored-fact", "$owner:stored-fact"), ids)
            assertEquals(listOf(personal to "stored-fact", owner to "stored-fact"), hindsight.forgotten)
            assertEquals(listOf(true, false), results.map { it["pendingCapture"].asBoolean() })
            assertTrue(results.all { it["forgotten"].asBoolean() })
        }
    }

    @Test
    fun `forget rejects foreign or malformed bank prefixes and preserves raw household IDs`() {
        val results = CopyOnWriteArrayList<JsonNode>()
        backendE2eTest("memory_forget_banks", hindsightUrl = HINDSIGHT_TEST_URL,
            providerClients = hindsight.clients(), llm = forgetLlm(results)) {
            val owner = UUID.randomUUID().toString()
            val other = UUID.randomUUID().toString()
            val voice = UUID.randomUUID().toString()
            val personal = "person:$owner:$voice"
            val chat = createPublicChat(owner)
            hindsight.facts += listOf(owner to "fact", personal to "fact")
            for (id in listOf("fact", "$owner:fact", "$personal:fact")) {
                submit(owner, chat, "ForgetMemoryFact $id")
                assertTrue(results.last()["forgotten"].asBoolean())
            }
            val requests = hindsight.paths.size
            for (id in listOf("$other:fact", "person:$other:$voice:fact", "person:$owner:invalid:fact",
                "$personal:extra:fact", "$owner:")) {
                submit(owner, chat, "ForgetMemoryFact $id")
                assertEquals("not_found", results.last()["error"]["code"].asText())
            }
            assertEquals(requests, hindsight.paths.size)
            assertEquals(listOf(owner to "fact", owner to "fact", personal to "fact"), hindsight.forgotten)
        }
    }

}
