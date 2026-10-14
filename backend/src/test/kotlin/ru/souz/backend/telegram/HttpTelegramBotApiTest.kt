package ru.souz.backend.telegram

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class HttpTelegramBotApiTest {
    @Test
    fun `files use bounded downloads and native multipart methods`() = runBlocking {
        val uploads = LinkedBlockingQueue<Triple<String, String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { call ->
            val path = call.requestURI.path
            val bytes = when {
                path.endsWith("/getFile") -> """{"ok":true,"result":{"file_id":"remote","file_path":"documents/readme.txt"}}""".toByteArray()
                path.contains("/file/") -> "payload".toByteArray()
                else -> {
                    uploads.put(Triple(path, call.requestHeaders.getFirst("Content-Type"), call.requestBody.reader().readText()))
                    """{"ok":true,"result":{"message_id":17}}""".toByteArray()
                }
            }
            call.sendResponseHeaders(200, bytes.size.toLong())
            call.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val api = HttpTelegramBotApi("http://127.0.0.1:${server.address.port}")
            assertEquals("payload", api.downloadFile("test", "remote", 7).decodeToString())
            assertFailsWith<ru.souz.backend.attachments.AttachmentException> { api.downloadFile("test", "remote", 6) }
            for ((presentation, part) in listOf(
                ru.souz.llms.FilePresentation.document to "Document",
                ru.souz.llms.FilePresentation.voice to "Voice",
                ru.souz.llms.FilePresentation.audio to "Audio",
            )) {
                val file = ru.souz.llms.FileContent(ru.souz.llms.FileReference("saved"), "отчёт.txt", "text/plain", 7, presentation)
                assertEquals(17L, api.sendFile("test", 42, file, "payload".toByteArray()))
                val (path, type, body) = uploads.remove()
                assertEquals("/bottest/send$part", path)
                kotlin.test.assertTrue(type.startsWith("multipart/form-data; boundary="))
                kotlin.test.assertTrue(body.contains("name=\"${part.lowercase()}\"; filename=\"отчёт.txt\""))
                kotlin.test.assertTrue(body.contains("name=\"chat_id\"\r\n\r\n42"))
                kotlin.test.assertTrue(body.endsWith("--\r\n"))
            }
            val document = ru.souz.llms.FileContent(ru.souz.llms.FileReference("html"), "page.html", "text/html", 7)
            val text = "**Готово** — [страница](https://example.com). 😀" + "я".repeat(2000)
            assertEquals(17L, api.sendFile("test", 42, document, "payload".toByteArray(), text))
            val (path, _, body) = uploads.remove()
            assertEquals("/bottest/sendRichMessage", path)
            val rich = jacksonObjectMapper().readTree(body.substringAfter("name=\"rich_message\"\r\n\r\n").substringBefore("\r\n--"))
            assertEquals("$text\n\n![](tg://document?id=attachment)", rich["markdown"].asText())
            assertEquals("attachment", rich["media"][0]["id"].asText())
            assertEquals("document", rich["media"][0]["media"]["type"].asText())
            assertEquals("attach://document", rich["media"][0]["media"]["media"].asText())
            kotlin.test.assertTrue(body.contains("name=\"document\"; filename=\"page.html\""))
            assertFailsWith<IllegalArgumentException> {
                api.sendFile("test", 42, document, "payload".toByteArray(), "x".repeat(TELEGRAM_DOCUMENT_TEXT_LIMIT + 1))
            }
            assertEquals(0, uploads.size)
        } finally { server.stop(0) }
    }

    @Test
    fun `rich message preserves Markdown and reports Telegram rejection`() = runBlocking {
        val requests = LinkedBlockingQueue<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.put(exchange.requestURI.path to exchange.requestBody.reader().readText())
            val reply = if (exchange.requestURI.path.contains("rejected")) {
                """{"ok":false,"error_code":400,"description":"Invalid rich message"}"""
            } else """{"ok":true,"result":{"message_id":1}}"""
            val bytes = reply.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val api = HttpTelegramBotApi("http://127.0.0.1:${server.address.port}")
            val markdown = "**Сапсан** [Билеты](https://example.com?a=1&b=2)\n`<code>` 😀 \"quote\"" + "я".repeat(5_000)
            api.sendMessage("token", 42, markdown)
            val (path, body) = requests.remove()
            assertEquals("/bottoken/sendRichMessage", path)
            val form = body.split('&').associate {
                val (key, value) = it.split('=', limit = 2)
                URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
            }
            assertEquals(setOf("chat_id", "rich_message"), form.keys)
            assertEquals("42", form["chat_id"])
            val content = jacksonObjectMapper().readTree(form.getValue("rich_message"))
            assertEquals(markdown, content.path("markdown").asText())
            assertEquals(1, content.size())
            val failure = assertFailsWith<TelegramBotApiHttpException> {
                api.sendMessage("rejected", 42, "**bad")
            }
            assertEquals("sendRichMessage", failure.methodName)
            assertEquals(400, failure.telegramErrorCode)
        } finally {
            server.stop(0)
        }
    }
}
