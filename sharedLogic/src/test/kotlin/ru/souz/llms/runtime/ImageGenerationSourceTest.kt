package ru.souz.llms.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImageGenerationSourceTest {
    @Test
    fun `unsupported empty oversized sources and excess source counts fail before provider calls`() {
        for (bytes in listOf(byteArrayOf(), "GIF89a".toByteArray(), ByteArray(ImageGenerationSource.MAX_BYTES + 1))) {
            assertFailsWith<IllegalArgumentException> { ImageGenerationSource.read(bytes.inputStream()) }
        }
        val source = ImageGenerationSource(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))
        assertEquals("data:image/jpeg;base64,/9j/", source.dataUrl())
        assertFailsWith<IllegalArgumentException> { ImageGenerationInput("edit", images = List(6) { source }) }
    }
}
