package ru.souz.llms

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/** Bounds dimensions before decoding, and creates a request-only image without filesystem IO. */
fun encodeImageInput(bytes: ByteArray, maxEdge: Int = 2048): String = MemoryCacheImageInputStream(bytes.inputStream()).use { input ->
    val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("attachment_image_format_unsupported")
    try {
        reader.input = input
        val width = reader.getWidth(0)
        val height = reader.getHeight(0)
        require(width in 1..16_384 && height in 1..16_384 && width.toLong() * height <= 40_000_000) {
            "attachment_image_too_large"
        }
        val original = reader.read(0)
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(width, height))
        val image = if (scale == 1.0) original else BufferedImage(
            (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_RGB,
        ).apply {
            createGraphics().let { graphics ->
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    graphics.drawImage(original, 0, 0, this.width, this.height, null)
                } finally { graphics.dispose() }
            }
        }
        val output = ByteArrayOutputStream()
        javax.imageio.stream.MemoryCacheImageOutputStream(output).use { encoded ->
            check(ImageIO.write(image, "png", encoded))
        }
        "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray())
    } finally { reader.dispose() }
}
