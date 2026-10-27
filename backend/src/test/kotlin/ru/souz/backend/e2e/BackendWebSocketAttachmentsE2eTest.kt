package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.websocket.Frame
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.attachments.AttachmentMinio
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.telegram.TelegramChat
import ru.souz.backend.telegram.TelegramMessage
import ru.souz.backend.telegram.TelegramUpdate
import ru.souz.backend.telegram.TelegramUser
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxModeResolver

class BackendWebSocketAttachmentsE2eTest {
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
                                assertTrue(restJsonMapper.readTree(last.content)["file"]["fileId"].asText().isNotBlank())
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
                                fileId = file["file"]["fileId"].asText()
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
                                        "text" to "Must not be delivered", "fileIds" to listOf(fileId),
                                    ),
                                ))
                            }
                            else -> {
                                val failed = restJsonMapper.readTree(last.content)
                                assertFalse(failed["success"].asBoolean(), last.content)
                                assertEquals("Attachments are not supported by this channel.", failed["reason"].asText())
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
                    assertEquals(fileId, api.sentFiles.single().first.file.fileId)
                    assertContentEquals(bytes, api.sentFiles.single().second)
                    assertTrue(api.sentMessages.isEmpty())
                    val untouched = client.get(BackendHttpRoutes.chatMessages(publicChatId)) { trusted(user) }.jsonBody()["items"]
                    assertEquals(0, untouched.size())
                }
            } finally { workspace.toFile().deleteRecursively() }
        }
    }
}
