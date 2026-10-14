package ru.souz.llms

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImageInputEncodingTest {
    @Test
    fun `large valid image is resized and malformed or excessive dimensions are rejected`() {
        val encoded = encodeImageInput(png(3000, 1500))
        val image = ImageIO.read(Base64.getDecoder().decode(encoded.substringAfter(',')).inputStream())
        assertEquals(2048, image.width)
        assertEquals(1024, image.height)
        assertFailsWith<IllegalArgumentException> { encodeImageInput(png(16385, 1)) }
        assertFailsWith<IllegalStateException> { encodeImageInput("not an image".toByteArray()) }
    }

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also { output ->
        MemoryCacheImageOutputStream(output).use {
            ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it)
        }
    }.toByteArray()
}
