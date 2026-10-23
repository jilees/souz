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

internal fun InputStream.readAttachmentBytes(limit: Int): ByteArray = readNBytes(limit + 1).also {
    if (it.size > limit) throw AttachmentException("attachment_too_large")
}

internal fun attachmentName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
    .filterNot { it.isISOControl() }.trim().take(180).dropLastWhile { it.isHighSurrogate() }
    .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"

internal fun attachmentMime(bytes: ByteArray, name: String): String {
    val detected = tika.detect(bytes)
    // A filename may refine text, but cannot disguise binary content as a document.
    return if (detected == "text/plain" && name.endsWith(".md", ignoreCase = true)) "text/markdown" else if (detected == "application/ogg" && bytes.take(256).toByteArray().toString(Charsets.ISO_8859_1).contains("OpusHead")) "audio/ogg" else detected
}

internal fun ByteArray.attachmentChecksum(): String = sha256Hex()
