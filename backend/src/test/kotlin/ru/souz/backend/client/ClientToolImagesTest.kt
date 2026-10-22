package ru.souz.backend.client

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import ru.souz.backend.attachments.MAX_TOOL_IMAGE_BYTES
import ru.souz.backend.attachments.downloadToolImageBytes
import ru.souz.llms.restJsonMapper

class ClientToolImagesTest {
    private fun outcome(data: String, mime: String = "image/png", isError: Boolean = false) = ClientToolOutcome(
        "succeeded", restJsonMapper.valueToTree(mapOf("isError" to isError,
            "content" to listOf(mapOf("type" to "image", "mimeType" to mime, "data" to data)))), null,
    )

    @Test
    fun `invalid image bytes MIME and sizes fail without retaining binary data`() = runTest {
        for (input in listOf(
            outcome("not base64!"),
            outcome(Base64.getEncoder().encodeToString("plain text".toByteArray())),
            outcome("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a8p8AAAAASUVORK5CYII=", "image/jpeg"),
            outcome("A".repeat(4 * ((MAX_TOOL_IMAGE_BYTES + 2) / 3) + 4)),
            outcome(""),
        )) {
            val result = input.storeImages("owner", "call", null)
            assertEquals("failed", result.status)
            assertEquals("invalid_tool_image", result.error?.code)
            assertNull(result.result)
        }
    }

    @Test
    fun `tool errors omit image bytes without requiring storage or mutating the received result`() = runTest {
        val original = outcome("binary-image-data", isError = true)
        val normalized = original.storeImages("owner", "call", null)
        assertEquals("succeeded", normalized.status)
        assertEquals(true, normalized.result!!["isError"].asBoolean())
        assertFalse(normalized.result.toString().contains("binary-image-data"))
        assertEquals("binary-image-data", original.result!!["content"][0]["data"].asText())
    }

    @Test
    fun `ordinary results are unchanged when storage is disabled`() = runTest {
        val original = ClientToolOutcome("succeeded", restJsonMapper.readTree("""{"content":[{"type":"text","text":"ok"}]}"""), null)
        assertEquals(original, original.storeImages("owner", "call", null))
    }

    @Test
    fun `resource links require storage and failed MCP results never download them`() = runTest {
        val original = ClientToolOutcome("succeeded", restJsonMapper.readTree("""{"content":[{"type":"resource_link","uri":"https://screen.test/current.webp","mimeType":null}]}"""), null)
        val unavailable = original.storeImages("owner", "call", null)
        assertEquals("tool_image_unavailable", unavailable.error?.code)
        assertNull(unavailable.result)
        val failed = original.copy(result = original.result!!.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("isError", true))
        val omitted = failed.storeImages("owner", "call", null)
        assertEquals("succeeded", omitted.status)
        assertFalse(omitted.result.toString().contains("https://screen.test"))
    }

    @Test
    fun `resource downloads reject nonpublic origins and credentials`() = runTest {
        for (url in listOf("http://example.com/image.png", "https://user:password@example.com/image.png", "file:///image.png",
            "https://127.0.0.1/image.png", "https://[::1]/image.png", "https://169.254.169.254/image.png", "https://10.0.0.1/image.png")) {
            assertFailsWith<IllegalArgumentException> { downloadToolImageBytes(url, MAX_TOOL_IMAGE_BYTES) }
        }
    }
}
