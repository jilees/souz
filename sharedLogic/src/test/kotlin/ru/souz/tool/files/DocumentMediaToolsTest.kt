package ru.souz.tool.files

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox

class DocumentMediaToolsTest {
    @Test
    fun `QR returns the embedded URL without following it and PDF page ranges are bounded`() = runBlocking {
        val root = Files.createTempDirectory("document-media")
        try {
            val settings = mockk<SettingsProvider>(relaxed = true)
            every { settings.forbiddenFolders } returns emptyList()
            val sandbox = LocalRuntimeSandbox(SandboxScope("test"), settings, root, root.resolve("state"), root)
            val tools = DocumentMediaTools(FilesToolUtil(sandbox)).catalog.toolsByCategory.values.flatMap { it.values }.associateBy { it.fn.name }
            val qr = root.resolve("qr.png")
            val payload = "https://127.0.0.1/private-not-to-be-fetched"
            MatrixToImageWriter.writeToPath(MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 512, 512), "png", qr)
            val decoded = tools.getValue("DecodeQrCode").invoke(LLMResponse.FunctionCall("DecodeQrCode", mapOf("path" to qr.toString())), ToolInvocationMeta("test"))
            assertEquals(payload, restJsonMapper.readTree(decoded.content)["text"].asText())
            val pdf = root.resolve("test.pdf")
            PDDocument().use { doc -> repeat(6) { doc.addPage(PDPage()) }; doc.save(pdf.toFile()) }
            suspend fun render(end: Int) = restJsonMapper.readTree(tools.getValue("RenderPdfPages").invoke(
                LLMResponse.FunctionCall("RenderPdfPages", mapOf("path" to pdf.toString(), "startPage" to 1, "endPage" to end)),
                ToolInvocationMeta("test")).content)
            val result = render(2)
            assertEquals(6, result["totalPages"].asInt())
            assertEquals(2, result["paths"].size())
            for (path in result["paths"]) {
                val image = ImageIO.read(java.io.File(path.asText()))
                assertTrue(image.width <= 2048 && image.height <= 2048)
            }
            assertTrue(render(6).has("error"))
        } finally { root.toFile().deleteRecursively() }
    }
}
