package ru.souz.backend.client

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.souz.llms.restJsonMapper

class ClientImageAttachmentsTest {
    @Test
    fun `only successful client image resources become model attachments`() {
        for ((result, expected) in listOf(
            """{"content":[{"type":"resource_link","uri":"https://screen.test/current.webp"}]}""" to listOf("https://screen.test/current.webp"),
            """{"content":[{"type":"resource_link","uri":"attachment:4f4330f4-0730-41bc-9bc2-02a494d15925","mimeType":"image/png"}]}""" to listOf("attachment:4f4330f4-0730-41bc-9bc2-02a494d15925"),
            """{"content":[{"type":"resource_link","uri":"attachment:invalid","mimeType":"image/png"}]}""" to emptyList(),
            """{"isError":true,"content":[{"type":"resource_link","uri":"https://screen.test/current.webp"}]}""" to emptyList(),
            """{"content":[{"type":"resource_link","uri":"file:///private/screen.png"}]}""" to emptyList(),
            """{"content":[{"type":"resource_link","uri":"https://screen.test/file.txt"}]}""" to emptyList(),
            """{"content":[{"type":"text","uri":"https://screen.test/current.webp"}]}""" to emptyList(),
        )) {
            val outcome = ClientToolOutcome("succeeded", restJsonMapper.readTree(result), null)
            assertEquals(expected, clientImageAttachments(outcome))
            assertEquals(emptyList(), clientImageAttachments(outcome.copy(status = "failed")))
        }
    }
}
