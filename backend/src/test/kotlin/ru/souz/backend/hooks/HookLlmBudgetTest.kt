package ru.souz.backend.hooks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import ru.souz.backend.http.BackendV1Exception
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest

class HookLlmBudgetTest {
    @Test
    fun `inline camera image has its own bounded budget across messages`() {
        val image = "data:image/png;base64," + "A".repeat(200_000)
        val request = request("Describe this frame", listOf(image))
        HookLlmBudget.checkRequestSize(request)
        assertEquals(image, request.messages.single().attachments?.single())

        val large = "data:image/png;base64," + "A".repeat(7_000_000)
        reject(request.copy(messages = listOf(
            LLMRequest.Message(LLMMessageRole.user, "first", attachments = listOf(large)),
            LLMRequest.Message(LLMMessageRole.user, "second", attachments = listOf(large)),
        )))
    }

    @Test
    fun `images do not relax text or ordinary attachment limits`() {
        val image = "data:image/png;base64," + "A".repeat(200_000)
        reject(request("x".repeat(131_072), listOf(image)))
        reject(request("😀".repeat(33_000), listOf(image)))
        reject(request("describe", listOf("x".repeat(131_072))))
        reject(request("describe", listOf(image, "x".repeat(131_072))))
    }

    private fun request(text: String, attachments: List<String>) = LLMRequest.Chat(
        messages = listOf(LLMRequest.Message(LLMMessageRole.user, text, attachments = attachments)),
    )

    private fun reject(request: LLMRequest.Chat) {
        assertEquals("hook_llm_input_limit", assertFailsWith<BackendV1Exception> {
            HookLlmBudget.checkRequestSize(request)
        }.code)
    }
}
