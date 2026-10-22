package ru.souz.backend.e2e

import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.websocket.Frame
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.attachments.AttachmentMinio
import ru.souz.backend.attachments.MAX_TOOL_IMAGE_BYTES
import ru.souz.backend.attachments.downloadToolImageBytes
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.telegram.TelegramChat
import ru.souz.backend.telegram.TelegramMessage
import ru.souz.backend.telegram.TelegramUpdate
import ru.souz.backend.telegram.TelegramUser
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.restJsonMapper

class BackendClientImagesE2eTest {
    @Test
    fun `resource link is saved once and its file ID delivers image presentation to Telegram`() {
        val url = "https://screens.example.test/tv/screenshot.webp?token=private-download-token"
        val bytes = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA")
        val api = FakeTelegramBotApi()
        var telegramChat = ""
        var fileId = ""
        var step = 0
        val llm = E2eLlmApi { request ->
            when (step++) {
                0 -> toolCallReply(request, "GetSkillByName", mapOf("skillId" to "device.mcp.call_tool"))
                1 -> toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "device.mcp.call_tool", "arguments" to mapOf("name" to "get_screenshot")))
                2 -> {
                    val result = restJsonMapper.readTree(request.messages.last { it.role == LLMMessageRole.function }.content)
                    fileId = result["content"][0]["fileId"].asText()
                    assertEquals("attachment:$fileId", result["content"][0]["uri"].asText())
                    assertEquals("image/webp", result["content"][0]["mimeType"].asText())
                    assertTrue(request.messages.none { "private-download-token" in it.content })
                    assertEquals(listOf("data:image/webp;base64," + Base64.getEncoder().encodeToString(bytes)), request.messages.last().attachments)
                    toolCallReply(request, "GetSkillByName", mapOf("skillId" to "SendMessageToChannel"))
                }
                3 -> toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "SendMessageToChannel",
                    "arguments" to mapOf("channelType" to "telegram", "channelId" to telegramChat, "text" to "Screenshot", "fileIds" to listOf(fileId))))
                else -> reply(request, "Sent")
            }
        }
        mockkStatic(::downloadToolImageBytes)
        try {
            coEvery { downloadToolImageBytes(url, MAX_TOOL_IMAGE_BYTES) } returns bytes
            backendE2eTest("e2e_resource_image", featureFlags = BackendFeatureFlags(wsEvents = true, telegramBot = true),
                telegramApi = api, attachments = AttachmentMinio.config(), llm = llm,
            ) {
                val owner = UUID.randomUUID().toString()
                val source = createPublicChat(owner, "source")
                telegramChat = createPublicChat(owner, "telegram")
                val link = client.put(BackendHttpRoutes.chatTelegramBot(telegramChat)) {
                    trusted(owner); jsonBody("""{"token":"123456:resource-image-test"}""")
                }.jsonBody()["pendingLinkCommand"].asText()
                api.enqueue(TelegramUpdate(1, TelegramMessage(1, TelegramUser(701, firstName = "Images"), TelegramChat(701, "private"), link)))
                backend.pollTelegramOnce()
                client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(owner); jsonBody("""{"enabledTools":["SendMessageToChannel"]}""")
                }
                withMultiChatSocket { socket ->
                    socket.send(Frame.Text(messageFrame(source, owner, "screen", text = "send screen")))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    readJson(socket)
                    val started = readJson(socket)
                    val frame = """{"kind":"tool.result","chatId":"$source","threadId":${started["threadId"]},"toolCallId":${started["payload"]["toolCallId"]},"status":"succeeded","result":{"content":[{"type":"resource_link","uri":"$url","mimeType":null}]}}"""
                    socket.send(Frame.Text(frame))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    assertEquals("thread.completed", readJson(socket)["type"].asText())
                    assertEquals(fileId, api.sentFiles.single().first.file.fileId)
                    assertEquals(ru.souz.llms.FilePresentation.image, api.sentFiles.single().first.presentation)
                    assertContentEquals(bytes, api.sentFiles.single().second)
                    socket.send(Frame.Text(frame))
                    assertTrue(readJson(socket)["duplicate"].asBoolean())
                }
                coVerify(exactly = 1) { downloadToolImageBytes(url, MAX_TOOL_IMAGE_BYTES) }
            }
        } finally { unmockkStatic(::downloadToolImageBytes) }
    }

    @Test
    fun `inline images from a different client channel reach the requesting agent`() =
        backendE2eTest("e2e_channel_image", attachments = AttachmentMinio.config()) {
            val owner = UUID.randomUUID().toString()
            val source = createPublicChat(owner, "source")
            val target = createPublicChat(owner, "target")
            val data = pngBase64()
            llm.requestSkillForPrompt("remote screen", "device.mcp.call_tool", mapOf("channelId" to target, "name" to "captureScreen"))
            withPublicSocket(target) { targetSocket ->
                withMultiChatSocket { sourceSocket ->
                    sourceSocket.send(Frame.Text(messageFrame(source, owner, "remote", text = "remote screen")))
                    assertEquals("accepted", readJson(sourceSocket)["status"].asText())
                    readJson(sourceSocket)
                    val started = readJson(targetSocket)
                    assertEquals("device.mcp.call_tool", started["payload"]["name"].asText())
                    targetSocket.send(Frame.Text("""{"kind":"tool.result","chatId":"$target","threadId":${started["threadId"]},"toolCallId":${started["payload"]["toolCallId"]},"status":"succeeded","result":{"content":[{"type":"image","data":"$data","mimeType":"image/png"}]}}"""))
                    assertEquals("accepted", readJson(targetSocket)["status"].asText())
                    assertEquals("thread.completed", readJson(sourceSocket)["type"].asText())
                    assertEquals(listOf("data:image/png;base64,$data"), llm.requests.last().messages.last().attachments)
                }
            }
        }

    @Test
    fun `inline MCP images reach the model while persisted results and retries retain only file references`() =
        backendE2eTest("e2e_client_images", attachments = AttachmentMinio.config()) {
            val owner = UUID.randomUUID().toString()
            val chat = createPublicChat(owner)
            val data = pngBase64()
            llm.requestSkillForPrompt("screen", "device.mcp.call_tool", mapOf("name" to "captureScreen"))
            withMultiChatSocket { socket ->
                socket.send(Frame.Text(messageFrame(chat, owner, "screen", text = "screen")))
                assertEquals("accepted", readJson(socket)["status"].asText())
                readJson(socket)
                val started = readJson(socket)
                val result = """{"content":[{"type":"text","text":"current screen"},{"type":"image","data":"$data","mimeType":"image/png"}]}"""
                val frame = """{"kind":"tool.result","chatId":"$chat","threadId":${started["threadId"]},"toolCallId":${started["payload"]["toolCallId"]},"status":"succeeded","result":$result}"""
                socket.send(Frame.Text(frame))
                assertEquals("accepted", readJson(socket)["status"].asText())
                assertEquals("thread.completed", readJson(socket)["type"].asText())
                val prepared = llm.requests.last()
                assertEquals(LLMMessageRole.user, prepared.messages.last().role)
                assertEquals(listOf("data:image/png;base64,$data"), prepared.messages.last().attachments)
                assertTrue(prepared.messages.filter { it.role == LLMMessageRole.function }.all { it.attachments.isNullOrEmpty() && data !in it.content })

                val stored = sql { connection ->
                    connection.prepareStatement("select result_json::text from tool_calls where chat_id = ? and target = 'client'").use { statement ->
                        statement.setObject(1, UUID.fromString(chat))
                        statement.executeQuery().use { rows -> check(rows.next()); rows.getString(1) }
                    }
                }
                assertFalse(data in stored)
                val reference = json.readTree(stored)["content"][1]["uri"].asText()
                assertTrue(reference.startsWith("attachment:"))
                val history = sql { connection ->
                    connection.prepareStatement("select context_json::text from agent_conversation_state where chat_id = ?").use { statement ->
                        statement.setObject(1, UUID.fromString(chat))
                        statement.executeQuery().use { rows -> check(rows.next()); rows.getString(1) }
                    }
                }
                assertTrue(reference in history)
                assertFalse(data in history)
                assertTrue(json.readTree(history)["history"].filter { it["role"].asText() == "user" }
                    .none { it["content"].asText().startsWith("Image returned by tool") })
                socket.send(Frame.Text(frame))
                val duplicate = readJson(socket)
                assertEquals("accepted", duplicate["status"].asText())
                assertTrue(duplicate["duplicate"].asBoolean())
                socket.send(Frame.Text(frame.replace("current screen", "changed result")))
                assertEquals("idempotency_conflict", readJson(socket)["error"]["code"].asText())
                socket.send(Frame.Text(messageFrame(chat, owner, "next", text = "next turn")))
                assertEquals("accepted", readJson(socket)["status"].asText())
                readJson(socket)
                assertEquals("thread.completed", readJson(socket)["type"].asText())
                assertTrue(llm.requests.last().messages.all { it.attachments.isNullOrEmpty() })
            }
        }

    @Test
    fun `inline image without configured storage reports unavailability without retaining base64`() =
        backendE2eTest("e2e_client_image_disabled") {
            val owner = UUID.randomUUID().toString()
            val chat = createPublicChat(owner)
            val data = pngBase64()
            llm.requestSkillForPrompt("screen", "device.mcp.call_tool", mapOf("name" to "captureScreen"))
            withMultiChatSocket { socket ->
                socket.send(Frame.Text(messageFrame(chat, owner, "screen", text = "screen")))
                readJson(socket)
                readJson(socket)
                val started = readJson(socket)
                socket.send(Frame.Text("""{"kind":"tool.result","chatId":"$chat","threadId":${started["threadId"]},"toolCallId":${started["payload"]["toolCallId"]},"status":"succeeded","result":{"content":[{"type":"image","data":"$data","mimeType":"image/png"}]}}"""))
                assertEquals("accepted", readJson(socket)["status"].asText())
                assertEquals("thread.completed", readJson(socket)["type"].asText())
                val output = llm.requests.last().messages.last { it.role == LLMMessageRole.function }
                assertEquals("tool_image_unavailable", json.readTree(output.content)["error"]["code"].asText())
                assertFalse(data in output.content)
                assertTrue(output.attachments.isNullOrEmpty())
            }
        }
}

private fun pngBase64(): String = ByteArrayOutputStream().use { output ->
    ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output)
    Base64.getEncoder().encodeToString(output.toByteArray())
}
