package ru.souz.llms.runtime

import java.io.InputStream
import java.util.Base64

/** Request-only source pixels; never include the encoded value in history or tool results. */
class ImageGenerationSource(val bytes: ByteArray) {
    init {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Image source must contain 1 to $MAX_BYTES bytes" }
    }

    val mimeType: String = when {
        bytes.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
        bytes.startsWith(0xff, 0xd8, 0xff) -> "image/jpeg"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
        else -> throw IllegalArgumentException("Image source must be PNG, JPEG or WebP")
    }

    fun dataUrl(): String = "data:$mimeType;base64," + Base64.getEncoder().encodeToString(bytes)

    companion object {
        const val MAX_BYTES: Int = 20_000_000
        const val MAX_IMAGES: Int = 5

        fun read(input: InputStream): ImageGenerationSource = ImageGenerationSource(input.readNBytes(MAX_BYTES + 1))

        private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
    }
}
