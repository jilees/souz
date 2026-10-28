package ru.souz.backend.e2e

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.websocket.Frame
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import ru.souz.backend.attachments.ATTACHMENT_RETENTION
import ru.souz.backend.attachments.AttachmentClock
import ru.souz.backend.attachments.AttachmentMinio
import ru.souz.backend.config.BackendConfigSource
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxModeResolver

class BackendImageEditingE2eTest {
    @Test
    fun `both image tools edit original sources and attachment edits cache by ordered files and accept previous results`() {
        for (codex in listOf(true, false)) {
            val config = AttachmentMinio.config()
            val sources = listOf(png(0xff0000), png(0x00ff00))
            val generated = png(0x0000ff)
            val workspace = Files.createTempDirectory("souz-edit-")
            val paths = sources.mapIndexed { index, bytes -> Files.write(workspace.resolve("source-$index.png"), bytes).toString() }
            val output = workspace.resolve("edited.png")
            var calls = 0
            val http = HttpClient(MockEngine { request ->
                val expected = when (calls++) { 2 -> sources.reversed(); 3 -> listOf(generated); else -> sources }
                assertEquals("Bearer image-test-key", request.headers[HttpHeaders.Authorization])
                if (codex) {
                    assertEquals("https://edit.test/backend-api/codex/responses", request.url.toString())
                    val body = restJsonMapper.readTree(request.body.toByteArray())
                    assertEquals("edit", body["tools"][0]["action"].asText())
                    assertEquals(expected.map { dataUrl(it) }, body["input"][0]["content"].drop(1).map { it["image_url"].asText() })
                    respond("data: " + restJsonMapper.writeValueAsString(mapOf("type" to "response.output_item.done",
                        "item" to mapOf("type" to "image_generation_call", "status" to "completed", "result" to encoded(generated)))) +
                        "\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n")
                } else {
                    assertEquals("https://api.openai.com/v1/images/edits", request.url.toString())
                    val body = request.body.toByteArray().toString(Charsets.ISO_8859_1)
                    val offsets = expected.map { body.indexOf(it.toString(Charsets.ISO_8859_1)) }
                    assertTrue(offsets.all { it >= 0 })
                    assertEquals(offsets.sorted(), offsets)
                    respond("""{"data":[{"b64_json":"${encoded(generated)}"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }) { providerHttpClientDefaults() }
            val originals = mutableListOf<String>()
            var first = ""
            var reversed = ""
            var edited = ""
            var step = 0
            try {
                backendE2eTest("image_edit", attachments = config, settingsSource = imageSettings(codex),
                    providerClients = ProviderHttpClients(http, http),
                    sandboxFactory = { provider -> DefaultRuntimeSandboxFactory(provider,
                        RuntimeSandboxModeResolver { "local" }, workspace, workspace.resolve("state"), workspace) },
                    llm = E2eLlmApi { request ->
                        fun call(tool: String, arguments: Map<String, Any>) = toolCallReply(request, "RunSkillCommand",
                            mapOf("skillId" to tool, "arguments" to arguments))
                        fun resultId() = Regex("\"fileId\":\"([^\"]+)\"").find(request.messages.last().content)?.groupValues?.get(1)
                            ?: error("Missing edited file at step $step: ${request.messages.last().content}")
                        // Register through the existing tool flow; no client-upload protocol is needed.
                        if (originals.size < paths.size) {
                            if (request.messages.last().role == LLMMessageRole.function) originals += resultId()
                            if (originals.size < paths.size) return@E2eLlmApi call("AttachFileToReply",
                                mapOf("path" to paths[originals.size]))
                        }
                        when (step++) {
                            0 -> toolCallReply(request, "GetSkillByName", mapOf("skillId" to "GenerateAttachmentImage"))
                            1 -> {
                                assertTrue(request.messages.last().content.contains("fileIds"))
                                call("GenerateImage", mapOf("prompt" to "Change background", "imagePaths" to paths, "outputPath" to output.toString()))
                            }
                            2 -> call("GenerateAttachmentImage", mapOf("prompt" to "Change background", "fileIds" to originals))
                            3 -> {
                                first = resultId()
                                call("GenerateAttachmentImage", mapOf("prompt" to "Change background", "fileIds" to originals))
                            }
                            4 -> {
                                assertEquals(first, resultId())
                                call("GenerateAttachmentImage", mapOf("prompt" to "Change background", "fileIds" to originals.reversed()))
                            }
                            5 -> {
                                reversed = resultId()
                                assertNotEquals(first, reversed)
                                call("GenerateAttachmentImage", mapOf("prompt" to "Change background", "fileIds" to listOf(first)))
                            }
                            6 -> {
                                edited = resultId()
                                assertNotEquals(first, edited)
                                call("AttachFileToReply", mapOf("fileId" to edited, "presentation" to "image"))
                            }
                            else -> reply(request, "Ready")
                        }
                    },
                ) {
                    val user = UUID.randomUUID().toString()
                    val chat = createPublicChat(user)
                    client.patch(BackendHttpRoutes.SETTINGS) {
                        trusted(user); jsonBody("""{"enabledTools":["GenerateImage","GenerateAttachmentImage","AttachFileToReply"]}""")
                    }
                    withPublicSocket(chat) { socket ->
                        socket.send(Frame.Text(messageFrame(chat, user, "edit")))
                        assertEquals("accepted", readJson(socket)["status"].asText())
                        readJson(socket)
                        val done = readJson(socket)
                        assertEquals("thread.completed", done["type"].asText(), done.toString())
                        assertTrue(edited.isNotBlank())
                    }
                    assertEquals(4, calls)
                    assertContentEquals(generated, Files.readAllBytes(output))
                    val history = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody().toString()
                    assertFalse(history.contains("data:image/"))
                    assertFalse(history.contains(encoded(generated)))
                }
            } finally { http.close(); workspace.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `source ownership expiry types and argument validation apply before provider calls and cached results`() {
        val config = AttachmentMinio.config()
        val clock = AttachmentClock(Instant.now())
        val image = png(0x123456)
        val workspace = Files.createTempDirectory("souz-edit-access-")
        val imagePath = Files.write(workspace.resolve("source.png"), image).toString()
        val textPath = Files.writeString(workspace.resolve("source.txt"), "text").toString()
        var registeredId = ""
        var owned = ""
        var foreign = ""
        var document = ""
        var calls = 0
        var step = 0
        val http = HttpClient(MockEngine {
            calls++
            respond("""{"data":[{"b64_json":"${encoded(image)}"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { providerHttpClientDefaults() }
        try {
            backendE2eTest("image_edit_access", attachments = config, clock = clock, settingsSource = imageSettings(false),
                sandboxFactory = { provider -> DefaultRuntimeSandboxFactory(provider,
                    RuntimeSandboxModeResolver { "local" }, workspace, workspace.resolve("state"), workspace) },
                providerClients = ProviderHttpClients(http, http), llm = E2eLlmApi { request ->
                    val input = request.messages.last { it.role == LLMMessageRole.user }.content
                    if (input.startsWith("register ")) {
                        if (request.messages.last().role == LLMMessageRole.function) {
                            registeredId = Regex("\"fileId\":\"([^\"]+)\"").find(request.messages.last().content)!!.groupValues[1]
                            return@E2eLlmApi reply(request, "Registered")
                        }
                        return@E2eLlmApi toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "AttachFileToReply",
                            "arguments" to mapOf("path" to if (input.contains("document")) textPath else imagePath)))
                    }
                    val invalid = listOf(
                        "not-an-array" to "attachment_invalid_arguments",
                        listOf(42) to "attachment_invalid_arguments",
                        List(6) { owned } to "attachment_invalid_arguments",
                        listOf("not-a-uuid") to "attachment_not_found",
                        listOf(foreign) to "attachment_not_found",
                        listOf(document) to "attachment_image_required",
                    )
                    if (step in 1..invalid.size) {
                        assertTrue(request.messages.last().content.contains(invalid[step - 1].second), request.messages.last().content)
                    }
                    val ids = if (step < invalid.size) invalid[step].first else listOf(owned)
                    if (step == invalid.size + 2) clock.now = clock.now.plus(ATTACHMENT_RETENTION).plusSeconds(1)
                    if (step++ == invalid.size + 3) {
                        assertTrue(request.messages.last().content.contains("attachment_expired"))
                        reply(request, "Expired")
                    } else toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "GenerateAttachmentImage",
                        "arguments" to mapOf("prompt" to "Edit", "fileIds" to ids)))
                },
            ) {
                val owner = UUID.randomUUID().toString()
                val other = UUID.randomUUID().toString()
                val chat = createPublicChat(owner)
                val foreignChat = createPublicChat(other)
                for ((user, channel, source) in listOf(Triple(owner, chat, "image"), Triple(owner, chat, "document"), Triple(other, foreignChat, "image"))) {
                    withMultiChatSocket { socket ->
                        socket.send(Frame.Text(messageFrame(channel, user, "register-$source", text = "register $source")))
                        assertEquals("accepted", readJson(socket)["status"].asText())
                        readJson(socket)
                        assertEquals("thread.completed", readJson(socket)["type"].asText())
                    }
                    if (user == other) foreign = registeredId else if (source == "image") owned = registeredId else document = registeredId
                }
                client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(owner); jsonBody("""{"enabledTools":["GenerateAttachmentImage"]}""")
                }
                withMultiChatSocket { socket ->
                    socket.send(Frame.Text(messageFrame(chat, owner, "edit")))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    readJson(socket)
                    val done = readJson(socket)
                    assertEquals("thread.completed", done["type"].asText(), done.toString())
                }
                assertEquals(1, calls)
            }
        } finally { http.close(); workspace.toFile().deleteRecursively() }
    }

    private fun imageSettings(codex: Boolean) = object : BackendConfigSource {
        override fun property(key: String): String? = null
        override fun env(key: String): String? = when (key) {
            "SOUZ_IMAGE_MODEL" -> if (codex) "CodexGpt61Sol" else "gpt-image-1"
            "CODEX_BASE_URL" -> "https://edit.test"
            "CODEX_ACCESS_TOKEN", "OPENAI_API_KEY" -> "image-test-key"
            "CODEX_ACCOUNT_ID" -> "image-test-account"
            else -> null
        }
    }

    private fun png(color: Int): ByteArray = ByteArrayOutputStream().also { output ->
        MemoryCacheImageOutputStream(output).use {
            val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, color) }
            ImageIO.write(image, "png", it)
        }
    }.toByteArray()

    private fun encoded(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun dataUrl(bytes: ByteArray): String = "data:image/png;base64," + encoded(bytes)
}
