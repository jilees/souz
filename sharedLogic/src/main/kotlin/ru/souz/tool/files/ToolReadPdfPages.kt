package ru.souz.tool.files

import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.text.PDFTextStripper
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.files.FilesToolUtil
import java.io.IOException

class ToolReadPdfPages(private val filesToolUtil: FilesToolUtil) : ToolSetup<ToolReadPdfPages.Input> {

    data class Input(
        @InputParamDescription("Absolute path to the PDF file")
        val filePath: String,
        @InputParamDescription("Start page number (1-based index). Default is 1.")
        val startPage: Int = 1,
        @InputParamDescription("End page number (inclusive). If null, reads only the start page.")
        val endPage: Int? = null
    )

    override val name: String = "ReadPdfPages"
    override val description: String = "Reads text from a specific page range in a PDF file. Returns text and total page count."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Прочитай первые 5 страниц книги",
            params = mapOf("filePath" to "/docs/book.pdf", "startPage" to 1, "endPage" to 5)
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "text" to ReturnProperty("string", "Extracted content"),
            "info" to ReturnProperty("string", "Debug info if text is empty")
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        val file = filesToolUtil.resolvePath(input.filePath, meta)
        if (!file.exists) return "Error: File not found at ${input.filePath}"

        if (file.name.substringAfterLast('.', "").lowercase() != "pdf") return "Error: Expecting .pdf file"

        val requestedEnd = input.endPage ?: input.startPage
        if (input.startPage < 1 || requestedEnd < input.startPage || requestedEnd - input.startPage >= 10) {
            return "Error: Request between 1 and 10 pages, starting at page 1 or later."
        }
        if ((file.sizeBytes ?: 0) > 20_000_000) return "Error: PDF exceeds 20 MB."
        return try {
            filesToolUtil.withReadableLocalPath(file, meta, prefix = "pdf-read-", suffix = ".pdf") { localPath ->
                val document = Loader.loadPDF(
                    localPath.toFile(),
                    MemoryUsageSetting.setupTempFileOnly()
                        .setTempDir(filesToolUtil.scratchDirectory(meta).toFile())
                        .streamCache,
                )
                try {
                    // 1. Проверка на шифрование (частая причина "пустоты")
                    if (document.isEncrypted) {
                        // Пытаемся снять защиту (для стандартных прав доступа)
                        document.isAllSecurityToBeRemoved = true
                    }

                    val totalPages = document.numberOfPages
                    if (totalPages > 500) return@withReadableLocalPath "Error: PDF exceeds 500 pages."
                    val start = input.startPage.coerceAtLeast(1)
                    val end = (input.endPage ?: start).coerceAtMost(totalPages)

                    if (start > totalPages) {
                        return@withReadableLocalPath "Error: Requested page $start but document only has $totalPages pages."
                    }

                    // Настройка извлекателя
                    val stripper = PDFTextStripper()
                    stripper.startPage = start
                    stripper.endPage = end

                    // ВАЖНО: Сортировка по позиции помогает читать сложную верстку (колонки, врезки)
                    stripper.sortByPosition = true

                    // Настройка: не пытаться читать текст, скрытый слоями (иногда мешает)
                    stripper.suppressDuplicateOverlappingText = true

                    val output = object : java.io.Writer() {
                        val text = StringBuilder()
                        override fun write(buffer: CharArray, offset: Int, length: Int) {
                            if (text.length + length > 50_000) throw IOException("PDF text exceeds 50000 characters; request fewer pages.")
                            text.append(buffer, offset, length)
                        }
                        override fun flush() = Unit
                        override fun close() = Unit
                    }
                    stripper.writeText(document, output)
                    val text = output.text.toString().trim()

                    if (text.isBlank()) {
                        """
                        Warning: Output is empty.
                        - Requested pages: $start to $end
                        - Total pages in file: $totalPages
                        - Encryption: ${document.isEncrypted}

                        Possible reasons:
                        1. These specific pages contain only images/scans without text layer.
                        2. The PDF has strict DRM protection.
                        """.trimIndent()
                    } else {
                        """
                        === PDF CONTENT (Pages $start-$end of $totalPages) ===
                        $text
                        """
                    }
                } finally {
                    document.close()
                }
            }
        } catch (e: IOException) {
            "IO Error reading PDF: ${e.message}"
        } catch (e: Exception) {
            "Unexpected error: ${e.toString()}"
        }
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String = invoke(input, meta)
}
