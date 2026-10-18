package ru.souz.backend.e2e

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.put
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
import ru.souz.backend.telegram.TelegramFile
import ru.souz.backend.telegram.TelegramMessage
import ru.souz.backend.telegram.TelegramUpdate
import ru.souz.backend.telegram.TelegramUser
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.backend.vk.*
import ru.souz.llms.LLMMessageRole

class BackendAttachmentsE2eTest {
    @Test
    fun `Telegram album preserves captions and polls without acknowledging an unfinished group`() {
        val api = FakeTelegramBotApi()
        api.downloads["first"] = "first file".toByteArray()
        api.downloads["second"] = "second file".toByteArray()
        var turns = 0
        backendE2eTest("telegram_album", featureFlags = BackendFeatureFlags(wsEvents = true, telegramBot = true),
            telegramApi = api, attachments = AttachmentMinio.config(), llm = E2eLlmApi { request ->
                turns++
                val input = request.messages.last().content
                assertTrue(input.contains("Первый\nВторой"), input)
                assertTrue(input.indexOf("first.txt") < input.indexOf("second.txt"), input)
                assertEquals(2, Regex("\"fileId\":").findAll(input).count())
                reply(request, "Получено два файла")
            },
        ) {
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user)
            val secret = client.put(BackendHttpRoutes.chatTelegramBot(chat)) {
                trusted(user); jsonBody("""{"token":"123456:album-token"}""")
            }.jsonBody()["pendingLinkCommand"].asText()
            val sender = TelegramUser(701, firstName = "Album")
            val peer = TelegramChat(701, "private")
            api.enqueue(TelegramUpdate(1, TelegramMessage(1, sender, peer, secret)))
            backend.pollTelegramOnce()
            client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(user); jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}"}""")
            }
            api.requestedOffsets.clear()
            listOf("first" to "Первый", "second" to "Второй").forEachIndexed { index, (id, caption) ->
                val messageId = index + 2L
                api.enqueue(TelegramUpdate(messageId, TelegramMessage(messageId, sender, peer,
                    caption = caption, document = TelegramFile(id, fileName = "$id.txt"), mediaGroupId = "album-1")))
            }
            backend.pollTelegramOnce()
            assertEquals(1, turns)
            assertTrue(api.requestedOffsets.size > 1)
            assertTrue(api.requestedOffsets.all { it == 2L }, api.requestedOffsets.toString())
            backend.pollTelegramOnce()
            assertEquals(1, turns)
            assertEquals(4L, api.requestedOffsets.last())
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "SOUZ_TEST_DOCKER", matches = "1")
    fun `Telegram voice is converted inside Docker and transcribed once`() {
        val api = FakeTelegramBotApi()
        api.downloads["voice"] = javaClass.getResourceAsStream("/attachments/tone.ogg")!!.use { it.readBytes() }
        val root = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("build"), "attachment-stt-").toAbsolutePath()
        val sandboxes = mutableListOf<ru.souz.runtime.sandbox.docker.DockerRuntimeSandbox>()
        var calls = 0
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            calls++
            assertEquals("/v1/audio/transcriptions", request.url.encodedPath)
            assertEquals("Bearer media-test-key", request.headers[io.ktor.http.HttpHeaders.Authorization])
            assertEquals("multipart", request.body.contentType?.contentType)
            respond("""{"text":"Привет из голосового"}""", headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
        }) { providerHttpClientDefaults() }
        try {
            backendE2eTest("telegram_stt", featureFlags = BackendFeatureFlags(wsEvents = true, telegramBot = true),
                telegramApi = api, attachments = AttachmentMinio.config(mediaApiKey = "media-test-key"),
                providerClients = ru.souz.llms.http.ProviderHttpClients(http, http),
                sandboxFactory = { _ -> ru.souz.runtime.sandbox.RuntimeSandboxFactory { scope ->
                    ru.souz.runtime.sandbox.docker.DockerRuntimeSandbox(scope, root.resolve(scope.userId),
                        removeContainerOnClose = true).also(sandboxes::add)
                } }, llm = E2eLlmApi { request ->
                    assertTrue(request.messages.last { it.role == LLMMessageRole.user }.content.contains("Привет из голосового"))
                    if (request.messages.last().role == LLMMessageRole.function) reply(request, "Услышал")
                    else toolCallReply(request, "GetSkillByName", mapOf("skillId" to "ReadFile"))
                },
            ) {
                val user = UUID.randomUUID().toString()
                val chat = createPublicChat(user)
                val secret = client.put(BackendHttpRoutes.chatTelegramBot(chat)) {
                    trusted(user); jsonBody("""{"token":"123456:stt-token"}""")
                }.jsonBody()["pendingLinkCommand"].asText()
                api.enqueue(TelegramUpdate(1, TelegramMessage(1, TelegramUser(701, firstName = "STT"), TelegramChat(701, "private"), secret)))
                backend.pollTelegramOnce()
                client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(user); jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}"}""")
                }
                api.enqueue(TelegramUpdate(2, TelegramMessage(2, TelegramUser(701, firstName = "STT"), TelegramChat(701, "private"),
                    voice = TelegramFile("voice", duration = 1))))
                backend.pollTelegramOnce()
                assertEquals("Услышал", api.sentMessages.last().text)
                assertEquals(1, calls)
            }
        } finally {
            http.close()
            sandboxes.forEach { it.close() }
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `VK document without text is returned as a saved attachment`() {
        val api = ScriptedVkApi()
        val bytes = "# VK document".toByteArray()
        api.downloads["https://vk.com/file"] = bytes
        backendE2eTest("vk_files", featureFlags = BackendFeatureFlags(wsEvents = true, vkBot = true),
            vkApi = api, attachments = AttachmentMinio.config(), llm = E2eLlmApi { request ->
                if (request.messages.last().role == LLMMessageRole.function) reply(request, "")
                else {
                    val id = Regex("\"fileId\":\"([^\"]+)\"").find(request.messages.last().content)!!.groupValues[1]
                    toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "AttachFileToReply", "arguments" to mapOf("fileId" to id)))
                }
            },
        ) {
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user)
            val secret = client.put(BackendHttpRoutes.chatVkBot(chat)) {
                trusted(user); jsonBody("""{"token":"vk-file-token"}""")
            }.jsonBody()["pendingLinkCommand"].asText()
            api.responses.add(VkLongPollResponse("2", listOf(VkLongPollUpdate("message_new",
                VkMessageObjectWrapper(VkMessage(1, 701, 701, secret))))))
            backend.pollVkOnce()
            client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(user); jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}","enabledTools":["AttachFileToReply"]}""")
            }
            val document = VkAttachment("doc", doc = VkFile(23, "document", "md", "https://vk.com/file"))
            api.responses.add(VkLongPollResponse("3", listOf(VkLongPollUpdate("message_new",
                VkMessageObjectWrapper(VkMessage(2, 701, 701, attachments = listOf(document)))))))
            backend.pollVkOnce()
            assertEquals(1, api.files.size)
            assertContentEquals(bytes, api.files.single().second)
            assertEquals("document.md", api.files.single().first.name)
        }
    }

    @Test
    fun `image tools share model credentials and parameters while attachment retries reuse the saved file`() {
        for (route in listOf("voice", "openai", "codex", "named")) {
            val voice = route == "voice"
            val tool = if (voice) "SynthesizeVoice" else "GenerateAttachmentImage"
            val api = FakeTelegramBotApi()
            val workspace = java.nio.file.Files.createTempDirectory("souz-images-")
            val output = workspace.resolve("result.png")
            val asset = if (voice) javaClass.getResourceAsStream("/attachments/tone.ogg")!!.use { it.readBytes() }
                else java.io.ByteArrayOutputStream().also { outputStream ->
                    javax.imageio.stream.MemoryCacheImageOutputStream(outputStream).use {
                        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
                    }
                }.toByteArray()
            val model = when (route) { "codex" -> "gpt-6.1-sol"; "named" -> "custom-image"; else -> "gpt-image-1" }
            val configFile = workspace.resolve("llm.json")
            java.nio.file.Files.writeString(configFile, """{"openaiConnections":{"images":{"baseUrl":"https://images.test/v1","apiKeyEnv":"IMAGE_KEY"}},"models":{"illustrator":{"connection":"images","modelId":"custom-image"}}}""")
            val env = buildMap {
                put("SOUZ_IMAGE_MODEL", when (route) { "codex" -> "CodexGpt61Sol"; "named" -> "illustrator"; else -> model })
                if (route == "openai") put("OPENAI_API_KEY", "openai-image-key")
                if (route == "codex") {
                    put("CODEX_ACCESS_TOKEN", "codex-token")
                    put("CODEX_ACCOUNT_ID", "codex-account")
                }
                if (route == "named") {
                    put("IMAGE_KEY", "named-image-key")
                    put("SOUZ_LLM_CONFIG_FILE", configFile.toString())
                }
            }
            val settings = object : ru.souz.backend.config.BackendConfigSource {
                override fun env(key: String): String? = env[key]
                override fun property(key: String): String? = null
            }
            var calls = 0
            val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
                calls++
                val body = ru.souz.llms.restJsonMapper.readTree(request.body.toByteArray())
                if (voice) {
                    assertEquals("http://orion.test/v1/audio/speech", request.url.toString())
                    assertEquals(null, request.headers[io.ktor.http.HttpHeaders.Authorization])
                    assertEquals("orion-tts", body["model"].asText())
                    respond(asset, headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "audio/ogg"))
                } else {
                    assertEquals(model, body["model"].asText())
                    val expectedUrl = when (route) {
                        "codex" -> "https://chatgpt.com/backend-api/codex/responses"
                        "named" -> "https://images.test/v1/images/generations"
                        else -> "https://api.openai.com/v1/images/generations"
                    }
                    assertEquals(expectedUrl, request.url.toString())
                    val key = when (route) { "codex" -> "codex-token"; "named" -> "named-image-key"; else -> "openai-image-key" }
                    assertEquals("Bearer $key", request.headers[io.ktor.http.HttpHeaders.Authorization])
                    val options = if (route == "codex") body["tools"][0] else body
                    assertEquals("png", options["output_format"].asText())
                    assertEquals("1024x1024", options["size"].asText())
                    assertEquals("medium", options["quality"].asText())
                    assertEquals("Привет", if (route == "codex") body["input"][0]["content"].asText() else body["prompt"].asText())
                    val encoded = java.util.Base64.getEncoder().encodeToString(asset)
                    val response = if (route == "codex") {
                        assertEquals("codex-account", request.headers["Chatgpt-Account-Id"])
                        "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"image_generation_call\",\"status\":\"completed\",\"result\":\"$encoded\"}}\n\n" +
                            "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"
                    } else """{"data":[{"b64_json":"$encoded"}]}"""
                    respond(response, headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, if (route == "codex") "text/event-stream" else "application/json"))
                }
            }) { providerHttpClientDefaults() }
            var phase = if (voice) 1 else 0
            var fileId = ""
            val trace = mutableListOf<String>()
            try {
                backendE2eTest("image_routes", featureFlags = BackendFeatureFlags(wsEvents = true, telegramBot = true),
                    telegramApi = api, attachments = AttachmentMinio.config("http://orion.test/v1/audio/speech", "stt-only-key"),
                    settingsSource = settings,
                    sandboxFactory = { provider -> ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory(provider,
                        ru.souz.runtime.sandbox.RuntimeSandboxModeResolver { "local" }, workspace, workspace.resolve("state"), workspace) },
                    providerClients = ru.souz.llms.http.ProviderHttpClients(http, http), llm = E2eLlmApi { request ->
                        trace += request.messages.last().content.orEmpty()
                        when (phase++) {
                            0 -> toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "GenerateImage",
                                "arguments" to mapOf("prompt" to " Привет ", "outputPath" to output.toString())))
                            1, 2 -> toolCallReply(request, "RunSkillCommand", mapOf("skillId" to tool,
                                "arguments" to mapOf((if (voice) "text" else "prompt") to "Привет")))
                            3 -> {
                                fileId = Regex("\"fileId\":\"([^\"]+)\"").find(request.messages.last().content)!!.groupValues[1]
                                toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "AttachFileToReply",
                                    "arguments" to mapOf("fileId" to fileId, "presentation" to if (voice) "voice" else "image")))
                            }
                            else -> reply(request, "")
                        }
                    },
                ) {
                    val user = UUID.randomUUID().toString()
                    val chat = createPublicChat(user)
                    val secret = client.put(BackendHttpRoutes.chatTelegramBot(chat)) {
                        trusted(user); jsonBody("""{"token":"123456:voice-token"}""")
                    }.jsonBody()["pendingLinkCommand"].asText()
                    fun update(id: Long, text: String) = TelegramUpdate(id, TelegramMessage(id,
                        TelegramUser(701, firstName = "Voice"), TelegramChat(701, "private"), text))
                    api.enqueue(update(1, secret))
                    backend.pollTelegramOnce()
                    client.patch(BackendHttpRoutes.SETTINGS) {
                        trusted(user); jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}","enabledTools":["GenerateImage","$tool","AttachFileToReply"]}""")
                    }
                    api.enqueue(update(2, "Скажи привет"))
                    backend.pollTelegramOnce()
                    assertEquals(if (voice) 1 else 2, calls, route)
                    assertEquals(1, api.sentFiles.size, "route=$route phase=$phase fileId=$fileId trace=$trace")
                    val sent = api.sentFiles.single()
                    assertEquals(if (voice) ru.souz.llms.FilePresentation.voice else ru.souz.llms.FilePresentation.image, sent.first.presentation)
                    assertContentEquals(asset, sent.second)
                    assertEquals(fileId, sent.first.file.fileId)
                    if (!voice) assertContentEquals(asset, java.nio.file.Files.readAllBytes(output))
                }
            } finally { http.close(); workspace.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `file-only Telegram input returns a document with accompanying text without replay`() {
        val api = FakeTelegramBotApi()
        val bytes = "# Document\nПривет из вложения".toByteArray()
        api.downloads["remote-document"] = bytes
        var selectedId: String? = null
        backendE2eTest("telegram_files", featureFlags = BackendFeatureFlags(wsEvents = true, telegramBot = true),
            telegramApi = api, attachments = AttachmentMinio.config(), llm = E2eLlmApi { request ->
                assertFalse(request.toString().contains("secret-token"))
                if (request.messages.last().role == LLMMessageRole.function) {
                    assertTrue(request.messages.last().content.contains(selectedId!!))
                    reply(request, "**Готово** — документ прикреплён.")
                } else {
                    selectedId = Regex("\"fileId\":\"([^\"]+)\"").find(request.messages.last().content)!!.groupValues[1]
                    toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "AttachFileToReply",
                        "arguments" to mapOf("fileId" to selectedId!!)))
                }
            },
        ) {
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user)
            val secret = client.put(BackendHttpRoutes.chatTelegramBot(chat)) {
                trusted(user); jsonBody("""{"token":"123456:secret-token"}""")
            }.jsonBody()["pendingLinkCommand"].asText()
            fun message(id: Long, text: String? = null, document: TelegramFile? = null) = TelegramUpdate(id,
                TelegramMessage(id, TelegramUser(701, firstName = "Attachment"), TelegramChat(701, "private"),
                    text = text, document = document))
            api.enqueue(message(1, secret))
            backend.pollTelegramOnce()
            client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(user); jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}","enabledTools":["AttachFileToReply"]}""")
            }
            api.sentMessages.clear()
            api.enqueue(message(2, document = TelegramFile(fileId = "remote-document", fileUniqueId = "unique",
                fileName = "notes.md", fileSize = bytes.size.toLong())))
            backend.pollTelegramOnce()
            assertEquals(1, api.sentFiles.size)
            assertContentEquals(bytes, api.sentFiles.single().second)
            assertEquals(selectedId, api.sentFiles.single().first.file.fileId)
            assertEquals(listOf("**Готово** — документ прикреплён."), api.fileTexts.toList())
            assertTrue(api.sentMessages.isEmpty(), api.sentMessages.toString())
            val callCount = llm.requests.size
            backend.pollTelegramOnce()
            assertEquals(callCount, llm.requests.size)
            assertEquals(1, api.sentFiles.size)
            val messages = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody()["items"]
            assertEquals(2, messages.size())
            assertEquals("", messages[0]["content"].asText())
        }
    }
}
