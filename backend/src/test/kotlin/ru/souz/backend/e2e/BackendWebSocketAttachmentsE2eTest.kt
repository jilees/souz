package ru.souz.backend.e2e

import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.websocket.Frame
import java.nio.file.Files
import java.util.UUID
import ru.souz.backend.attachments.attachmentObjectKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.souz.backend.attachments.AttachmentMinio
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.telegram.TelegramChat
import ru.souz.backend.telegram.TelegramMessage
import ru.souz.backend.telegram.TelegramUpdate
import ru.souz.backend.telegram.TelegramUser
import ru.souz.llms.restJsonMapper
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxModeResolver

class BackendWebSocketAttachmentsE2eTest {
    @Test
    fun `image generation discovery honors attachment-only permissions and returns a file over WebSocket`() {
        val image = java.io.ByteArrayOutputStream().also { output ->
            javax.imageio.stream.MemoryCacheImageOutputStream(output).use {
                javax.imageio.ImageIO.write(java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
            }
        }.toByteArray()
        var generations = 0
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            generations++
            assertEquals("/v1/images/generations", request.url.encodedPath)
            assertEquals("Bearer image-test-key", request.headers[io.ktor.http.HttpHeaders.Authorization])
            respond(
                """{"data":[{"b64_json":"${java.util.Base64.getEncoder().encodeToString(image)}"}]}""",
                headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"),
            )
        }) { providerHttpClientDefaults() }
        var phase = 0
        var fileId = ""
        try {
            backendE2eTest("ws_image_discovery", attachments = AttachmentMinio.config(),
                settingsSource = object : ru.souz.backend.config.BackendConfigSource {
                    override fun env(key: String): String? = if (key == "OPENAI_API_KEY") "image-test-key" else null
                    override fun property(key: String): String? = null
                },
                providerClients = ru.souz.llms.http.ProviderHttpClients(http, http),
                llm = E2eLlmApi { request ->
                    when (phase++) {
                        0 -> toolCallReply(request, "GetSkillsByCategory", mapOf("category" to "IMAGE_GENERATION"))
                        1 -> {
                            val discovery = restJsonMapper.readTree(request.messages.last().content)
                            assertEquals(listOf("GenerateAttachmentImage"), discovery["skills"].map { it["skillId"].asText() })
                            assertEquals(0, discovery["errors"].size())
                            toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "GenerateAttachmentImage",
                                "arguments" to mapOf("prompt" to "Test image")))
                        }
                        2 -> {
                            fileId = restJsonMapper.readTree(request.messages.last().content)["fileId"].asText()
                            toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "AttachFileToReply",
                                "arguments" to mapOf("fileId" to fileId, "presentation" to "image")))
                        }
                        else -> reply(request, "")
                    }
                },
            ) {
                val user = UUID.randomUUID().toString()
                val chat = createPublicChat(user)
                client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(user); jsonBody("""{"enabledTools":["GenerateAttachmentImage","AttachFileToReply"]}""")
                }
                withPublicSocket(chat) { socket ->
                    socket.send(Frame.Text(messageFrame(chat, user, "generate", text = "Generate an image")))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    assertEquals("thread.status", readJson(socket)["type"].asText())
                    val terminal = readJson(socket)
                    assertEquals("thread.completed", terminal["type"].asText())
                    val file = terminal["payload"]["attachments"].single()
                    assertEquals(attachmentObjectKey(user, UUID.fromString(fileId)), file["objectKey"].asText())
                    assertEquals("image", file["presentation"].asText())
                }
                assertEquals(1, generations)
                assertEquals(4, phase)
            }
        } finally { http.close() }
    }

    @Test
    fun `voice socket registers files directly or in a child and checks the destination at delivery`() {
        for (delegated in listOf(false, true)) {
            val workspace = Files.createTempDirectory("souz-ws-attachments-")
            val bytes = "Document from a voice request".toByteArray()
            val output = workspace.resolve("document.txt")
            Files.write(output, bytes)
            val api = FakeTelegramBotApi()
            var parentPhase = 0
            var fileId = ""
            var telegramChatId = ""
            var publicChatId = ""
            var childCalls = 0
            try {
                backendE2eTest("ws_attachments",
                    featureFlags = BackendFeatureFlags(wsEvents = true, telegramBot = true),
                    telegramApi = api, attachments = AttachmentMinio.config(),
                    sandboxFactory = { provider -> DefaultRuntimeSandboxFactory(provider,
                        RuntimeSandboxModeResolver { "local" }, workspace, workspace.resolve("state"), workspace) },
                    llm = E2eLlmApi { request ->
                        val last = request.messages.last()
                        if (request.functions.map { it.name } == listOf("AttachFileToReply")) {
                            childCalls++
                            if (last.name == "AttachFileToReply") {
                                assertTrue(restJsonMapper.readTree(last.content)["fileId"].asText().isNotBlank())
                                reply(request, "File registered")
                            } else {
                                toolCallReply(request, "AttachFileToReply", mapOf("path" to output.toString()))
                            }
                        } else when (parentPhase++) {
                            0 -> toolCallReply(request, "GetSkillByName", mapOf("skillId" to "AttachFileToReply"))
                            1 -> {
                                assertEquals("AttachFileToReply", restJsonMapper.readTree(last.content)["skill"]["skillId"].asText())
                                if (delegated) toolCallReply(request, "SpawnSubagent", mapOf(
                                    "task" to "Register the completed document and return it.",
                                    "skillIds" to listOf("AttachFileToReply"), "maxTurns" to 4,
                                )) else toolCallReply(request, "RunSkillCommand", mapOf(
                                    "skillId" to "AttachFileToReply", "arguments" to mapOf("path" to output.toString()),
                                ))
                            }
                            2 -> {
                                val result = restJsonMapper.readTree(last.content)
                                val file = if (delegated) result["files"].single() else result
                                fileId = file["fileId"].asText()
                                assertTrue(fileId.isNotBlank())
                                toolCallReply(request, "RunSkillCommand", mapOf(
                                    "skillId" to "SendMessageToChannel", "arguments" to mapOf(
                                        "channelType" to "telegram", "channelId" to telegramChatId,
                                        "text" to "Here is the document", "fileIds" to listOf(fileId),
                                    ),
                                ))
                            }
                            3 -> {
                                assertTrue(restJsonMapper.readTree(last.content)["success"].asBoolean(), last.content)
                                toolCallReply(request, "RunSkillCommand", mapOf(
                                    "skillId" to "SendMessageToChannel", "arguments" to mapOf(
                                        "channelType" to "public_client", "channelId" to publicChatId,
                                        "text" to "Here is the document", "fileIds" to listOf(fileId),
                                    ),
                                ))
                            }
                            else -> {
                                assertTrue(restJsonMapper.readTree(last.content)["success"].asBoolean(), last.content)
                                reply(request, "Document sent to Telegram")
                            }
                        }
                    },
                ) {
                    val user = UUID.randomUUID().toString()
                    val source = createPublicChat(user, "source")
                    telegramChatId = createPublicChat(user, "telegram")
                    publicChatId = createPublicChat(user, "public")
                    val secret = client.put(BackendHttpRoutes.chatTelegramBot(telegramChatId)) {
                        trusted(user); jsonBody("""{"token":"123456:ws-attachment-token"}""")
                    }.jsonBody()["pendingLinkCommand"].asText()
                    api.enqueue(TelegramUpdate(1, TelegramMessage(1, TelegramUser(701, firstName = "Files"),
                        TelegramChat(701, "private"), secret)))
                    backend.pollTelegramOnce()
                    api.sentMessages.clear()
                    client.patch(BackendHttpRoutes.SETTINGS) {
                        trusted(user); jsonBody("""{"enabledTools":["AttachFileToReply","SendMessageToChannel"]}""")
                    }
                    withPublicSocket(source) { session ->
                        session.send(Frame.Text(messageFrame(source, user, "voice-file", text = "Send the document to Telegram")))
                        assertEquals("accepted", readJson(session)["status"].asText())
                        assertEquals("thread.status", readJson(session)["type"].asText())
                        val terminal = readJson(session)
                        assertEquals("thread.completed", terminal["type"].asText(), terminal.toString())
                        assertEquals("Document sent to Telegram", terminal["payload"]["response"].asText())
                        assertEquals(null, terminal.findValue("fileId"))
                    }
                    assertEquals(if (delegated) 2 else 0, childCalls)
                    assertEquals(1, api.sentFiles.size)
                    assertEquals(fileId, api.sentFiles.single().first.fileId)
                    assertContentEquals(bytes, api.sentFiles.single().second)
                    assertTrue(api.sentMessages.isEmpty())
                    val forwarded = client.get(BackendHttpRoutes.chatMessages(publicChatId)) { trusted(user) }.jsonBody()["items"].single()
                    assertEquals("Here is the document", forwarded["content"].asText())
                    withPublicSocket(publicChatId) { session ->
                        val push = readJson(session)
                        assertEquals("message.created", push["type"].asText())
                        assertTrue(push["threadId"].isNull)
                        val file = push["payload"]["attachments"].single()
                        assertEquals(attachmentObjectKey(user, UUID.fromString(fileId)), file["objectKey"].asText())
                        assertEquals(setOf("objectKey", "name", "presentation"), file.fieldNames().asSequence().toSet())
                    }
                }
            } finally { workspace.toFile().deleteRecursively() }
        }
    }
}
