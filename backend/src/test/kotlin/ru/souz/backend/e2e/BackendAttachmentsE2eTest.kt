package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.websocket.Frame
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.souz.backend.attachments.AttachmentMinio
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.http.providerHttpClientDefaults

class BackendAttachmentsE2eTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "SOUZ_TEST_DOCKER", matches = "1")
    fun `WebSocket voice is converted inside Docker and transcribed once`() {
        val config = AttachmentMinio.config(mediaApiKey = "media-test-key")
        val bytes = javaClass.getResourceAsStream("/attachments/tone.ogg")!!.use { it.readBytes() }
        val root = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("build"), "attachment-stt-").toAbsolutePath()
        val sandboxes = mutableListOf<ru.souz.runtime.sandbox.docker.DockerRuntimeSandbox>()
        var calls = 0
        var userMessage = ""
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            calls++
            assertEquals("/v1/audio/transcriptions", request.url.encodedPath)
            assertEquals("Bearer media-test-key", request.headers[io.ktor.http.HttpHeaders.Authorization])
            assertEquals("multipart", request.body.contentType?.contentType)
            respond("""{"text":"Привет из голосового"}""", headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
        }) { providerHttpClientDefaults() }
        try {
            backendE2eTest("socket_stt", featureFlags = BackendFeatureFlags(wsEvents = true),
                attachments = config,
                providerClients = ru.souz.llms.http.ProviderHttpClients(http, http),
                sandboxFactory = { _ -> ru.souz.runtime.sandbox.RuntimeSandboxFactory { scope ->
                    ru.souz.runtime.sandbox.docker.DockerRuntimeSandbox(scope, root.resolve(scope.userId),
                        removeContainerOnClose = true).also(sandboxes::add)
                } }, llm = E2eLlmApi { request ->
                    userMessage = request.messages.last { it.role == LLMMessageRole.user }.content
                    if (request.messages.last().role == LLMMessageRole.function) reply(request, "Услышал")
                    else toolCallReply(request, "GetSkillByName", mapOf("skillId" to "ReadFile"))
                },
            ) {
                val user = UUID.randomUUID().toString()
                val chat = createPublicChat(user)
                val file = AttachmentMinio.upload(config, user, "voice.ogg", bytes)
                val frame = json.readTree(messageFrame(chat, user, "voice")) as ObjectNode
                val payload = frame["payload"] as ObjectNode
                payload.remove("content")
                payload.putArray("attachments").addObject().put("objectKey", file.objectKey)
                    .put("name", "voice.ogg").put("presentation", "voice")
                withPublicSocket(chat) { socket ->
                    socket.send(Frame.Text(frame.toString()))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    assertEquals("thread.status", readJson(socket)["type"].asText())
                    assertEquals("Услышал", readJson(socket)["payload"]["response"].asText())
                }
                assertTrue("Привет из голосового" in userMessage, userMessage)
                assertEquals(1, calls)
            }
        } finally {
            http.close()
            sandboxes.forEach { it.close() }
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `image tools share model credentials and parameters while attachment retries reuse the saved file`() {
        for (route in listOf("voice", "openai", "codex", "named")) {
            val voice = route == "voice"
            val tool = if (voice) "SynthesizeVoice" else "GenerateAttachmentImage"
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
                    put("CODEX_BASE_URL", "https://codex-images.test")
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
                        "codex" -> "https://codex-images.test/backend-api/codex/responses"
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
            try {
                backendE2eTest("image_routes", featureFlags = BackendFeatureFlags(wsEvents = true),
                    attachments = AttachmentMinio.config("http://orion.test/v1/audio/speech", "stt-only-key"),
                    settingsSource = settings,
                    sandboxFactory = { provider -> ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory(provider,
                        ru.souz.runtime.sandbox.RuntimeSandboxModeResolver { "local" }, workspace, workspace.resolve("state"), workspace) },
                    providerClients = ru.souz.llms.http.ProviderHttpClients(http, http), llm = E2eLlmApi { request ->
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
                    client.patch(BackendHttpRoutes.SETTINGS) {
                        trusted(user); jsonBody("""{"defaultModel":"${E2E_MODEL.alias}","enabledTools":["GenerateImage","$tool","AttachFileToReply"]}""")
                    }
                    withPublicSocket(chat) { socket ->
                        socket.send(Frame.Text(messageFrame(chat, user, "generate", text = "Скажи привет")))
                        assertEquals("accepted", readJson(socket)["status"].asText())
                        assertEquals("thread.status", readJson(socket)["type"].asText())
                        val terminal = readJson(socket)
                        assertEquals("thread.completed", terminal["type"].asText(), terminal.toString())
                        val file = terminal["payload"]["attachments"].single()
                        val saved = backend.attachmentStorage.get(user, UUID.fromString(fileId))
                        assertEquals(saved.objectKey, file["objectKey"].asText())
                        assertEquals(if (voice) "voice" else "image", file["presentation"].asText())
                        assertContentEquals(asset, backend.attachmentStorage.read(user, saved.id))
                    }
                    assertEquals(if (voice) 1 else 2, calls, route)
                    if (!voice) assertContentEquals(asset, java.nio.file.Files.readAllBytes(output))
                }
            } finally { http.close(); workspace.toFile().deleteRecursively() }
        }
    }
}
