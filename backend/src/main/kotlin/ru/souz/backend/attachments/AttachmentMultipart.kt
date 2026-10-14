package ru.souz.backend.attachments

import java.io.ByteArrayOutputStream
import java.util.UUID
import ru.souz.llms.FileContent

internal class AttachmentMultipart(field: String, file: FileContent, bytes: ByteArray, fields: Map<String, String> = emptyMap()) {
    private val boundary = "souz-${UUID.randomUUID()}"
    val contentType = "multipart/form-data; boundary=$boundary"
    val body = ByteArrayOutputStream().apply {
        for ((key, value) in fields) {
            write("--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$value\r\n".toByteArray())
        }
        val name = file.name.replace(Regex("[\\r\\n\"\\\\]"), "_")
        write("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$name\"\r\nContent-Type: ${file.mimeType}\r\n\r\n".toByteArray())
        write(bytes)
        write("\r\n--$boundary--\r\n".toByteArray())
    }.toByteArray()
}
