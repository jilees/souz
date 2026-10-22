package ru.souz.backend.telegram

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

/** Detect the source format from bytes and make a bounded JPEG preview; preserve the S3 original. */
internal fun telegramPhoto(bytes: ByteArray): ByteArray = MemoryCacheImageInputStream(bytes.inputStream()).use { input ->
    val decoder = ImageIO.getImageReaders(input).asSequence().firstOrNull()
        ?: error("telegram_photo_unsupported")
    val source = try {
        decoder.input = input
        val w = decoder.getWidth(0)
        val h = decoder.getHeight(0)
        require(w in 1..16_384 && h in 1..16_384 && w.toLong() * h <= 40_000_000) { "telegram_photo_dimensions" }
        require(maxOf(w, h).toLong() <= minOf(w, h).toLong() * 20) { "telegram_photo_aspect_ratio" }
        val sample = (maxOf(w, h) / 1280).coerceAtLeast(1)
        decoder.read(0, decoder.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) })
    } finally { decoder.dispose() }
    val ratio = minOf(1.0, 1280.0 / maxOf(source.width, source.height))
    val photo = BufferedImage((source.width * ratio).toInt().coerceAtLeast(1),
        (source.height * ratio).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
    val graphics = photo.createGraphics()
    try {
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, photo.width, photo.height)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.drawImage(source, 0, 0, photo.width, photo.height, null)
    } finally { graphics.dispose(); source.flush() }
    val output = ByteArrayOutputStream()
    val encoder = ImageIO.getImageWritersByFormatName("jpeg").next()
    try {
        MemoryCacheImageOutputStream(output).use { target ->
            encoder.output = target
            val options = encoder.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.85f
            }
            encoder.write(null, IIOImage(photo, null, null), options)
        }
    } finally { encoder.dispose(); photo.flush() }
    output.toByteArray().also { require(it.size <= 10_000_000) { "telegram_photo_too_large" } }
}
