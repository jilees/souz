package ru.souz.backend.attachments

import java.io.InputStream
import java.util.Base64
import ru.souz.backend.crypto.sha256Hex
import org.apache.tika.Tika

private val tika = Tika()

internal const val MAX_TOOL_IMAGE_BYTES = 5_000_000
internal val toolImageMimeTypes = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

internal fun toolImageDataUrl(bytes: ByteArray): String {
    require(bytes.size <= MAX_TOOL_IMAGE_BYTES) { "Tool image is too large." }
    val mime = attachmentMime(bytes, "tool-image")
    require(mime in toolImageMimeTypes) { "Tool image is not a supported image." }
    return "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
}

internal fun InputStream.readAttachmentBytes(limit: Int): ByteArray {
    val bytes = readNBytes(limit + 1)
    if (bytes.size > limit) throw AttachmentException("attachment_too_large")
    return bytes
}

internal fun attachmentName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
    .filterNot { it.isISOControl() }.trim().take(180).dropLastWhile { it.isHighSurrogate() }
    .takeUnless { it.isBlank() || it in setOf(".", "..") }
    ?: "attachment"

internal fun attachmentMime(bytes: ByteArray, name: String): String {
    val detected = tika.detect(bytes)
    // Filename only refines already-detected text; it cannot turn executable bytes into a document.
    return when {
        detected == "text/plain" && name.endsWith(".md", ignoreCase = true) -> "text/markdown"
        detected == "application/ogg" && bytes.take(256).toByteArray().toString(Charsets.ISO_8859_1).contains("OpusHead") -> "audio/ogg"
        else -> detected
    }
}

internal fun ByteArray.attachmentChecksum(): String = sha256Hex()
