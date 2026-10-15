package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.http.headersOf
import io.ktor.websocket.Frame
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BackendVoiceMemoryE2eTest {
    @Test
    fun `JEV routes completed voice turns to personal household both or neither without changing banks`() {
        val remote = VoiceMemoryRemote()
        backendE2eTest("voice_routes", hindsightUrl = VOICE_MEMORY_URL, providerClients = remote.clients()) {
            val owner = UUID.randomUUID().toString()
            val voice = UUID.randomUUID().toString()
            val personalBank = "person:$owner:$voice"
            for ((personal, household) in listOf(true to false, false to true, true to true, false to false)) {
                remote.personal = personal
                remote.household = household
                val chat = createPublicChat(owner, UUID.randomUUID().toString())
                submitVoice(chat, owner, voice, "request", "My name is Sasha and our cat is Musya")
                awaitVoiceQueue(chat)
                val before = remote.retains.size
                assertTrue(backend.captureHistoryMemory())
                assertEquals(listOfNotNull(personalBank.takeIf { personal }, owner.takeIf { household }),
                    remote.retains.drop(before).map { it.bank })
                assertFalse(backend.captureHistoryMemory())
            }
            assertEquals(4, remote.classifications.size)
            assertTrue(remote.classifications.all { it["questions"].size() == 2 && it["state"]["currentSpeaker"].asText() == voice })
            remote.retains.forEach { retained ->
                val item = retained.body["items"].single()
                assertTrue(item["content"].asText().contains("My name is Sasha"))
                assertTrue(item["content"].asText().contains("\"speaker\":\"$voice\""))
                assertEquals(retained.bank == personalBank, item["tags"].isEmpty)
                assertTrue(retained.body.has("operation_id"))
            }
            // Default recall flag is off, but identified voice turns still receive scoped recall.
            assertTrue(llm.requests.all { request -> request.messages.any { it.name == "souz_injected_memory" && voice in it.content } })
        }
    }

    @Test
    fun `personal facts are recalled across chats and isolated by both owner and voice`() {
        val remote = VoiceMemoryRemote()
        backendE2eTest("voice_recall", hindsightUrl = VOICE_MEMORY_URL, providerClients = remote.clients()) {
            val owner = UUID.randomUUID().toString(); val voice = UUID.randomUUID().toString()
            val first = createPublicChat(owner)
            submitVoice(first, owner, voice, "name", "My name is Sasha")
            awaitVoiceQueue(first)
            assertTrue(backend.captureHistoryMemory())
            assertEquals("person:$owner:$voice", remote.retains.single().bank)
            // Simulate completed Hindsight extraction at the external service boundary.
            remote.recalled["person:$owner:$voice"] = "The current speaker is named Sasha"
            remote.recalled[owner] = "The user is named Masha"
            val second = createPublicChat(owner, "second")
            submitVoice(second, owner, voice, "recall", "What is my name")
            val prompt = llm.requests.last().messages.single { it.name == "souz_injected_memory" }.content
            assertTrue(prompt.contains("[personal] The current speaker is named Sasha"))
            assertTrue(prompt.contains("[household] The user is named Masha"))
            assertTrue(prompt.contains("prefer personal over household"))
            val otherVoice = createPublicChat(owner, "other-voice")
            submitVoice(otherVoice, owner, UUID.randomUUID().toString(), "recall", "What is my name")
            assertFalse(llm.requests.last().messages.any { "Sasha" in it.content })
            val otherOwner = UUID.randomUUID().toString()
            submitVoice(createPublicChat(otherOwner), otherOwner, voice, "recall", "What is my name")
            assertFalse(llm.requests.last().messages.any { "Sasha" in it.content || "Masha" in it.content })
            val textChat = createPublicChat(owner, "text")
            submitVoice(textChat, owner, null, "text", "hello")
            assertTrue(llm.requests.last().messages.any { it.name == "souz_current_speaker" && "No voice identified" in it.content })
        }
    }

    @Test
    fun `same chat retains distinct speaker labels across restart without rewriting user text`() {
        val schema = newPostgresSchema("voice_labels")
        val owner = UUID.randomUUID().toString()
        val alice = UUID.randomUUID().toString()
        val bob = UUID.randomUUID().toString()
        var chat = ""
        backendE2eTest("voice_labels_first", schema = schema) {
            chat = createPublicChat(owner)
            submitVoice(chat, owner, null, "legacy", "Call me Old")
            submitVoice(chat, owner, alice, "alice", "Call me Alice")
            assertTrue(llm.requests.last().messages.any { it.content == "[souz_speaker id=\"unknown\"]\nCall me Old" })
        }
        backendE2eTest("voice_labels_restored", schema = schema) {
            submitVoice(chat, owner, bob, "bob", "What is my name?")
            val request = llm.requests.last()
            assertTrue(request.messages.first().content.contains("Different IDs are different speakers"))
            assertTrue(request.messages.any { it.content == "[souz_speaker id=\"$alice\"]\nCall me Alice" })
            assertTrue(request.messages.any { it.content == "[souz_speaker id=\"$bob\"]\nWhat is my name?" })
            submitVoice(chat, owner, null, "unknown", "And me?")
            assertTrue(llm.requests.last().messages.any { it.content == "[souz_speaker id=\"unknown\"]\nAnd me?" })
            sql { connection ->
                connection.prepareStatement("select content from messages where user_id = ? and role = 'user'").use {
                    it.setString(1, owner)
                    it.executeQuery().use { rows -> while (rows.next()) assertFalse(rows.getString(1).contains("souz_speaker")) }
                }
            }
        }
    }

    @Test
    fun `routing and independent delivery survive restart without repeating the successful bank`() {
        val remote = VoiceMemoryRemote().apply { personal = true; household = true; failHousehold = true }
        val clock = VoiceTestClock()
        val schema = newPostgresSchema("voice_restart")
        var chat = ""
        var owner = ""
        var voice = ""
        backendE2eTest("voice_first", schema = schema, hindsightUrl = VOICE_MEMORY_URL, providerClients = remote.clients(), clock = clock) {
            owner = UUID.randomUUID().toString(); voice = UUID.randomUUID().toString()
            chat = createPublicChat(owner)
            submitVoice(chat, owner, voice, "request", "Remember that I am Sasha and our cat is Musya")
            awaitVoiceQueue(chat)
            assertTrue(backend.captureHistoryMemory())
            assertEquals(listOf("person:$owner:$voice", owner), remote.retains.map { it.bank })
            assertFalse(backend.captureHistoryMemory())
        }
        remote.personal = false; remote.household = false; remote.failHousehold = false
        clock.advance(6)
        backendE2eTest("voice_second", schema = schema, hindsightUrl = VOICE_MEMORY_URL, providerClients = remote.clients(), clock = clock) {
            assertTrue(backend.captureHistoryMemory())
            assertEquals(1, remote.classifications.size)
            assertEquals(listOf("person:$owner:$voice", owner, owner), remote.retains.map { it.bank })
            assertEquals(remote.retains[1], remote.retains[2])
            assertNotEquals(remote.retains[0].body["operation_id"], remote.retains[1].body["operation_id"])
            assertFalse(backend.captureHistoryMemory())
            sql { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select payload, completed_at, delivered_banks from history_memory_fragments").use {
                        assertTrue(it.next()); assertEquals(null, it.getString("payload"))
                        assertTrue(it.getObject("completed_at") != null)
                        assertEquals(2, (it.getArray("delivered_banks").array as Array<*>).size)
                    }
                }
            }
        }
    }

    @Test
    fun `classifier failure retries without retaining and opt out never queues a voice turn`() {
        val remote = VoiceMemoryRemote().apply { failJev = true }
        val clock = VoiceTestClock()
        backendE2eTest("voice_jev_retry", hindsightUrl = VOICE_MEMORY_URL, providerClients = remote.clients(), clock = clock) {
            val owner = UUID.randomUUID().toString(); val voice = UUID.randomUUID().toString()
            val chat = createPublicChat(owner)
            submitVoice(chat, owner, voice, "name", "My name is Sasha")
            awaitVoiceQueue(chat)
            assertTrue(backend.captureHistoryMemory())
            assertTrue(remote.retains.isEmpty())
            remote.failJev = false
            clock.advance(6)
            assertTrue(backend.captureHistoryMemory())
            assertEquals(2, remote.classifications.size)
            assertEquals(listOf("person:$owner:$voice"), remote.retains.map { it.bank })
            val other = createPublicChat(owner, "other")
            submitVoice(other, owner, voice, "private", "Do not save my private name")
            assertFalse(backend.captureHistoryMemory())
        }
    }

    @Test
    fun `active speaker changes refresh memory and final capture belongs to the submitted message`() {
        val remote = VoiceMemoryRemote()
        val started = listOf(CompletableDeferred<Unit>(), CompletableDeferred(), CompletableDeferred())
        var attempt = 0
        val llm = E2eLlmApi { request ->
            val call = attempt++
            if (call < started.size) { started[call].complete(Unit); awaitCancellation() }
            reply(request, "done")
        }
        backendE2eTest("voice_steer", hindsightUrl = VOICE_MEMORY_URL, providerClients = remote.clients(), llm = llm) {
            val owner = UUID.randomUUID().toString()
            val alice = UUID.randomUUID().toString(); val bob = UUID.randomUUID().toString()
            val chat = createPublicChat(owner)
            withPublicSocket(chat) { socket ->
                for ((index, voice) in listOf(alice, bob, null, bob).withIndex()) {
                    socket.send(Frame.Text(voiceFrame(chat, owner, voice, "input-$index", "My name is Bob $index")))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    assertEquals("thread.status", readJson(socket)["type"].asText())
                    if (index < started.size) withTimeout(5_000) { started[index].await() }
                }
                assertEquals("thread.completed", readJson(socket)["type"].asText())
            }
            val injections = llm.requests.map { it.messages.filter { message -> message.name in setOf("souz_injected_memory", "souz_current_speaker") }.joinToString("\n") { it.content } }
            assertTrue(alice in injections[0])
            assertTrue(bob in injections[1] && alice !in injections[1])
            assertTrue("No voice identified" in injections[2] && bob !in injections[2])
            assertTrue(bob in injections[3])
            val userLabels = llm.requests.last().messages.filter { it.content.startsWith("[souz_speaker") }
            assertEquals(listOf(alice, bob, "unknown", bob), userLabels.map { it.content.substringAfter("id=\"").substringBefore('"') })
            awaitVoiceQueue(chat)
            assertTrue(backend.captureHistoryMemory())
            assertEquals(listOf("person:$owner:$bob"), remote.retains.map { it.bank })
            val records = remote.retains.single().body["items"].single()["content"].asText().substringAfter("NEW dialogue records")
            assertTrue("My name is Bob 3" in records)
            assertFalse(alice in records)
        }
    }

    @Test
    fun `voice validation and receipts distinguish changed speakers and preserve missing voice retries`() {
        backendE2eTest("voice_contract") {
            val owner = UUID.randomUUID().toString(); val chat = createPublicChat(owner)
            val voice = UUID.randomUUID().toString()
            withPublicSocket(chat) { socket ->
                for (value in listOf("", "bad-id", "1-1-1-1-1")) {
                    socket.send(Frame.Text(voiceFrame(chat, owner, value, "invalid-$value", "hello")))
                    assertEquals("invalid_request", readJson(socket)["error"]["code"].asText())
                }
                for (id in listOf<String?>(null, voice)) {
                    val frame = voiceFrame(chat, owner, id, "request-$id", "hello")
                    socket.send(Frame.Text(frame))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    readJson(socket); readJson(socket)
                    socket.send(Frame.Text(frame))
                    assertTrue(readJson(socket)["duplicate"].asBoolean())
                    readJson(socket)
                    socket.send(Frame.Text(voiceFrame(chat, owner, UUID.randomUUID().toString(), "request-$id", "hello")))
                    assertEquals("rejected", readJson(socket)["status"].asText())
                }
            }
        }
    }
}

private const val VOICE_MEMORY_URL = "http://voice-memory.test"

private fun BackendE2eScope.voiceFrame(chat: String, owner: String, voice: String?, request: String, text: String): String {
    val frame = json.readTree(messageFrame(chat, owner, request, text = text))
    if (voice != null) (frame["payload"] as ObjectNode).set<JsonNode>("voice", json.valueToTree(mapOf("embeddingId" to voice)))
    return frame.toString()
}

private suspend fun BackendE2eScope.submitVoice(chat: String, owner: String, voice: String?, request: String, text: String) {
    withPublicSocket(chat) { socket ->
        socket.send(Frame.Text(voiceFrame(chat, owner, voice, request, text)))
        // Reconnecting to this chat may replay completed events before the new request ACK.
        var ack = readJson(socket)
        while (ack.path("kind").asText() != "ack" || ack.path("requestId").asText() != request) ack = readJson(socket)
        assertEquals("accepted", ack["status"].asText())
        assertEquals("thread.status", readJson(socket)["type"].asText())
        assertEquals("thread.completed", readJson(socket)["type"].asText())
    }
}

private suspend fun BackendE2eScope.awaitVoiceQueue(chat: String) {
    eventually("durable voice memory turn") {
        sql { connection -> connection.prepareStatement("select 1 from history_memory_fragments where chat_id = ?::uuid").use {
            it.setString(1, chat)
            it.executeQuery().use { rows -> true.takeIf { rows.next() } }
        } }
    }
}

private class VoiceTestClock : Clock() {
    @Volatile private var now = Instant.parse("2026-10-15T12:00:00Z")
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
}

private class VoiceMemoryRemote {
    data class Retain(val bank: String, val body: JsonNode)
    val retains = CopyOnWriteArrayList<Retain>()
    val classifications = CopyOnWriteArrayList<JsonNode>()
    val recalled = java.util.concurrent.ConcurrentHashMap<String, String>()
    var personal = true
    var household = false
    var failJev = false
    var failHousehold = false

    fun clients(): ProviderHttpClients {
        val client = HttpClient(MockEngine { request ->
            val payload = restJsonMapper.readTree(request.body.toByteArray())
            val path = request.url.encodedPath.decodeURLPart()
            var status = HttpStatusCode.OK
            val body = when {
                request.url.host == "api.typesafe.ai" -> {
                    classifications.add(payload)
                    if (failJev) { status = HttpStatusCode.ServiceUnavailable; "{}" }
                    else restJsonMapper.writeValueAsString(mapOf("answers" to mapOf(
                        "personal" to mapOf("type" to "noul", "noul" to if (personal) 0.9 else 0.1),
                        "household" to mapOf("type" to "noul", "noul" to if (household) 0.9 else 0.1),
                    )))
                }
                path.endsWith("/recall") -> restJsonMapper.writeValueAsString(mapOf("results" to listOfNotNull(
                    recalled[path.substringAfter("/banks/").substringBefore('/')]?.let { mapOf("id" to "fact", "text" to it) },
                )))
                path.endsWith("/memories") -> {
                    val bank = path.substringAfter("/banks/").substringBefore('/')
                    retains += Retain(bank, payload)
                    if (failHousehold && !bank.startsWith("person:")) status = HttpStatusCode.ServiceUnavailable
                    """{"success":true,"async":true}"""
                }
                else -> error("Unexpected bank mutation $path")
            }
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { providerHttpClientDefaults() }
        return ProviderHttpClients(client, client, client)
    }
}
