package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.websocket.Frame
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.attachments.ATTACHMENT_RETENTION
import ru.souz.backend.attachments.AttachmentClock
import ru.souz.backend.attachments.AttachmentMinio
import ru.souz.backend.attachments.S3AttachmentObjects
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.restJsonMapper

class BackendPublicAttachmentsE2eTest {
    @Test
    fun `media presentations enter the ordinary model input path and survive reply selection`() {
        val config = AttachmentMinio.config()
        val presentations = listOf("image", "voice", "audio")
        val ids = mutableListOf<String>()
        var selected = 0
        backendE2eTest("ws_media", attachments = config, llm = E2eLlmApi { request ->
            val input = request.messages.last { it.role == LLMMessageRole.user }.content
            assertTrue(ids.all(input::contains))
            assertTrue(input.contains("vision_unavailable"))
            assertTrue(input.contains("attachment_stt_unconfigured"))
            if (selected == ids.size) reply(request, "Media received") else {
                val index = selected++
                toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "AttachFileToReply",
                    "arguments" to mapOf("fileId" to ids[index], "presentation" to presentations[index])))
            }
        }) {
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user)
            val image = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aBNkAAAAASUVORK5CYII=")
            val audio = javaClass.getResourceAsStream("/attachments/tone.ogg")!!.use { it.readBytes() }
            for (bytes in listOf(image, audio, audio)) {
                ids += AttachmentMinio.upload(config, user, "report.md", bytes).id.toString()
            }
            client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(user); jsonBody("""{"enabledTools":["AttachFileToReply"]}""")
            }
            val frame = restJsonMapper.readTree(attachmentFrame(chat, user, "media", ids)) as ObjectNode
            frame["payload"]["attachments"].forEachIndexed { index, file ->
                (file as ObjectNode).put("presentation", presentations[index])
            }
            withPublicSocket(chat) { session ->
                session.send(Frame.Text(frame.toString()))
                assertEquals("accepted", readJson(session)["status"].asText())
                readJson(session)
                val terminal = readJson(session)
                assertEquals("thread.completed", terminal["type"].asText(), terminal.toString())
                assertEquals(ids, terminal["payload"]["attachments"].map { it["id"].asText() })
                assertEquals(presentations, terminal["payload"]["attachments"].map { it["presentation"].asText() })
            }
        }
    }

    @Test
    fun `attachment submission requires configured storage`() = backendE2eTest("ws_files_disabled") {
        val user = UUID.randomUUID().toString()
        val chat = createPublicChat(user)
        withPublicSocket(chat) { session ->
            session.send(Frame.Text(attachmentFrame(chat, user, "file", listOf(UUID.randomUUID().toString()))))
            val rejected = readJson(session)
            assertEquals("rejected", rejected["status"].asText())
            assertEquals("feature_disabled", rejected["error"]["code"].asText())
        }
        assertTrue(llm.requests.isEmpty())
    }

    @Test
    fun `files-only submit imports S3 files and persists a minimal reply and replay with retry after expiry`() {
        val config = AttachmentMinio.config()
        val clock = AttachmentClock(Instant.now())
        var fileId = ""
        backendE2eTest("ws_file_roundtrip", attachments = config, clock = clock, llm = E2eLlmApi { request ->
            if (request.messages.last().role == LLMMessageRole.function) reply(request, "")
            else {
                assertTrue(request.messages.last().content.contains(fileId))
                toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "AttachFileToReply",
                    "arguments" to mapOf("fileId" to fileId)))
            }
        }) {
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user)
            val upload = AttachmentMinio.upload(config, user, "report.md", "# Shared file".toByteArray())
            fileId = upload.id.toString()
            client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(user); jsonBody("""{"enabledTools":["AttachFileToReply"]}""")
            }
            val frame = attachmentFrame(chat, user, "file-only", listOf(fileId))
            lateinit var terminal: JsonNode
            withMultiChatSocket { session ->
                session.send(Frame.Text(frame))
                val ack = readJson(session)
                assertEquals("accepted", ack["status"].asText(), ack.toString())
                assertEquals("thread.status", readJson(session)["type"].asText())
                terminal = readJson(session)
                assertEquals("thread.completed", terminal["type"].asText(), terminal.toString())
                assertEquals("", terminal["payload"]["response"].asText())
                val file = terminal["payload"]["attachments"].single()
                assertEquals(setOf("id", "name", "presentation"), file.fieldNames().asSequence().toSet())
                assertEquals(fileId, file["id"].asText())
                assertEquals("report.md", file["name"].asText())
                assertEquals("document", file["presentation"].asText())
                // A stored receipt must not depend on a still-available S3 object or live metadata.
                S3AttachmentObjects(config).use { it.delete(upload) }
                clock.now = clock.now.plus(ATTACHMENT_RETENTION).plusSeconds(1)
                session.send(Frame.Text(frame))
                val retry = readJson(session)
                assertEquals("accepted", retry["status"].asText())
                assertTrue(retry["duplicate"].asBoolean())
                assertEquals(ack["thread"], retry["thread"])
                assertEquals("thread.status", readJson(session)["type"].asText())
                session.send(Frame.Text(attachmentFrame(chat, user, "file-only", listOf(fileId), "changed")))
                assertEquals("idempotency_conflict", readJson(session)["error"]["code"].asText())
            }
            withPublicSocket(chat) { session -> assertEquals(terminal, readJson(session)) }
            val messages = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody()["items"]
            assertEquals(listOf("", ""), messages.map { it["content"].asText() })
        }
    }

    @Test
    fun `active threads accept attachment follow ups with and without text and keep file order`() {
        val config = AttachmentMinio.config()
        backendE2eTest("ws_file_followup", attachments = config, llm = E2eLlmApi().apply { pauseUntilReleased() }) {
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user)
            val ids = List(2) { AttachmentMinio.upload(config, user, "report.md", "file $it".toByteArray()).id.toString() }
            withPublicSocket(chat) { session ->
                session.send(Frame.Text(messageFrame(chat, user, "first", text = "wait for files")))
                val ack = readJson(session)
                readJson(session)
                llm.awaitPrompt("wait for files")
                val textRetry = restJsonMapper.readTree(messageFrame(chat, user, "first", text = "wait for files")) as ObjectNode
                (textRetry["payload"] as ObjectNode).putArray("attachments")
                session.send(Frame.Text(textRetry.toString()))
                assertTrue(readJson(session)["duplicate"].asBoolean())
                readJson(session)
                val follow = attachmentFrame(chat, user, "second", ids)
                session.send(Frame.Text(follow))
                val second = readJson(session)
                assertEquals(ack["thread"]["id"], second["thread"]["id"])
                assertFalse(second["thread"]["created"].asBoolean())
                readJson(session)
                session.send(Frame.Text(follow))
                assertTrue(readJson(session)["duplicate"].asBoolean())
                readJson(session)
                session.send(Frame.Text(attachmentFrame(chat, user, "third", ids.reversed(), "review both",
                    ack["thread"]["id"].asText())))
                assertEquals("accepted", readJson(session)["status"].asText())
                readJson(session)
                llm.release()
                assertEquals("thread.completed", readJson(session)["type"].asText())
            }
            val messages = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody()["items"]
                .filter { it["role"].asText() == "user" }
            assertEquals(listOf("wait for files", "", "review both"), messages.map { it["content"].asText() })
            val inputs = llm.requests.last().messages.filter { it.role == LLMMessageRole.user && ids.all(it.content::contains) }
            assertEquals(2, inputs.size)
            assertTrue(inputs[0].content.indexOf(ids[0]) < inputs[0].content.indexOf(ids[1]))
            assertTrue(inputs[1].content.indexOf(ids[1]) < inputs[1].content.indexOf(ids[0]))
        }
    }

    @Test
    fun `invalid attachment frames are rejected before execution and do not relax history text validation`() {
        val config = AttachmentMinio.config()
        backendE2eTest("ws_file_validation", attachments = config) {
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user)
            val file = AttachmentMinio.upload(config, user, "report.md", "text".toByteArray())
            val foreign = AttachmentMinio.upload(config, UUID.randomUUID().toString(), "report.md", "private".toByteArray())
            val valid = restJsonMapper.readTree(attachmentFrame(chat, user, "invalid", listOf(file.id.toString()))) as ObjectNode
            val mutations: List<(ObjectNode) -> Unit> = listOf(
                { it.remove("attachments") },
                { it.putArray("attachments") },
                { it.putNull("content") },
                { it.set<JsonNode>("content", restJsonMapper.readTree("""{"type":"text","source":"text","text":" "}""")) },
                { it.putNull("attachments") },
                { it.putArray("attachments").addNull() },
                { (it["attachments"][0] as ObjectNode).put("id", "1-1-1-1-1") },
                { (it["attachments"][0] as ObjectNode).put("id", file.id.toString().uppercase()) },
                { (it["attachments"][0] as ObjectNode).put("name", " ") },
                { (it["attachments"][0] as ObjectNode).put("presentation", "video") },
                { (it["attachments"][0] as ObjectNode).put("presentation", 0) },
                { (it["attachments"][0] as ObjectNode).putNull("presentation") },
                { (it["attachments"][0] as ObjectNode).put("presentation", "image") },
                { (it["attachments"][0] as ObjectNode).put("mimeType", "text/plain") },
                { (it["attachments"][0] as ObjectNode).put("id", UUID.randomUUID().toString()) },
                { (it["attachments"][0] as ObjectNode).put("id", foreign.id.toString()) },
                { payload -> val item = payload["attachments"][0]; payload.putArray("attachments").add(item).add(item) },
                { payload -> payload.putArray("attachments").also { array -> repeat(11) {
                    array.addObject().put("id", UUID.randomUUID().toString()).put("name", "report.md")
                } } },
            )
            withPublicSocket(chat) { session ->
                for ((index, mutate) in mutations.withIndex()) {
                    val frame = valid.deepCopy().put("requestId", "invalid-$index")
                    mutate(frame["payload"] as ObjectNode)
                    session.send(Frame.Text(frame.toString()))
                    val rejection = readJson(session)
                    assertEquals("rejected", rejection["status"].asText(), "case $index: $rejection")
                    assertEquals("invalid_request", rejection["error"]["code"].asText(), "case $index: $rejection")
                }
                session.send(Frame.Text(historyFrame(chat, "empty-history", "user", "")))
                assertEquals("rejected", readJson(session)["status"].asText())
                session.send(Frame.Text(attachmentFrame(chat, user, "valid-after-errors", listOf(file.id.toString()), "with caption")))
                assertEquals("accepted", readJson(session)["status"].asText())
                readJson(session)
                assertEquals("thread.completed", readJson(session)["type"].asText())
            }
            val messages = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody()["items"]
            assertEquals(1, messages.count { it["role"].asText() == "user" })
        }
    }
}

private fun attachmentFrame(chat: String, user: String, request: String, ids: List<String>, text: String? = null,
    thread: String? = null): String {
    val frame = restJsonMapper.readTree(messageFrame(chat, user, request, thread)) as ObjectNode
    val payload = frame["payload"] as ObjectNode
    if (text == null) payload.remove("content") else (payload["content"] as ObjectNode).put("text", text)
    payload.putArray("attachments").also { files -> ids.forEach { files.addObject().put("id", it).put("name", "report.md") } }
    return frame.toString()
}
