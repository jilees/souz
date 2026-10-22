package ru.souz.backend.telegram

import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlinx.coroutines.runBlocking
import ru.souz.llms.FileContent
import ru.souz.llms.FilePresentation
import ru.souz.llms.FileReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TelegramPhotoTest {
    @Test
    fun `PNG and WebP photos become bounded JPEG previews while documents retain original bytes`() = runBlocking {
        val image = BufferedImage(2560, 1280, BufferedImage.TYPE_INT_ARGB)
        val buffer = ByteArrayOutputStream()
        MemoryCacheImageOutputStream(buffer).use { ImageIO.write(image, "png", it) }
        val originals = listOf("png" to buffer.toByteArray(), "webp" to
            Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA"))
        val requests = LinkedBlockingQueue<Pair<String, ByteArray>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { call ->
            requests.put(call.requestURI.path to call.requestBody.readAllBytes())
            val result = """{"ok":true,"result":{"message_id":23}}""".toByteArray()
            call.sendResponseHeaders(200, result.size.toLong())
            call.responseBody.use { it.write(result) }
        }
        server.start()
        try {
            val api = HttpTelegramBotApi("http://127.0.0.1:${server.address.port}")
            for ((format, original) in originals) {
                val file = FileContent(FileReference("original"), "cat.$format", "image/$format", original.size.toLong(), FilePresentation.image)
                api.sendFile("test", 42, file, original)
                val (path, payload) = requests.remove()
                assertEquals("/bottest/sendPhoto", path)
                val body = payload.toString(Charsets.ISO_8859_1)
                assertTrue(body.contains("filename=\"cat.jpg\"\r\nContent-Type: image/jpeg"))
                val jpeg = body.substringAfter("Content-Type: image/jpeg\r\n\r\n").substringBeforeLast("\r\n--").toByteArray(Charsets.ISO_8859_1)
                val preview = ImageIO.read(jpeg.inputStream())
                assertEquals(if (format == "png") 1280 else 1, preview.width)
                assertEquals(if (format == "png") 640 else 1, preview.height)
                if (format == "png") assertEquals(0xffffff, preview.getRGB(0, 0) and 0xffffff)
                api.sendFile("test", 42, file.copy(presentation = FilePresentation.document), original)
                val (documentPath, documentPayload) = requests.remove()
                assertEquals("/bottest/sendDocument", documentPath)
                val document = documentPayload.toString(Charsets.ISO_8859_1)
                assertContentEquals(original, document.substringAfter("Content-Type: image/$format\r\n\r\n")
                    .substringBeforeLast("\r\n--").toByteArray(Charsets.ISO_8859_1))
                assertEquals("image/$format", file.mimeType)
            }
        } finally { server.stop(0) }
    }

    @Test
    fun `invalid images and extreme aspect ratios fail before sending`() {
        assertFailsWith<IllegalStateException> { telegramPhoto("invalid".toByteArray()) }
        val bytes = ByteArrayOutputStream()
        MemoryCacheImageOutputStream(bytes).use { ImageIO.write(BufferedImage(100, 1, BufferedImage.TYPE_INT_RGB), "png", it) }
        assertFailsWith<IllegalArgumentException> { telegramPhoto(bytes.toByteArray()) }
    }
}
