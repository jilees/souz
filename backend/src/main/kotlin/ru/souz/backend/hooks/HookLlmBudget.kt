package ru.souz.backend.hooks

import ru.souz.backend.llm.quota.ExecutionQuotaManager
import ru.souz.backend.storage.postgres.postgresStorageMapper
import ru.souz.llms.LLMRequest
import ru.souz.llms.LlmProvider

/** Applied at the execution API, including retries, nested tools and resumed options. */
internal class HookLlmBudget(
    private val receipt: HookReceipt,
    private val store: HookStore,
    private val quotas: ExecutionQuotaManager,
) {
    fun limitRequest(request: LLMRequest.Chat): LLMRequest.Chat {
        checkRequestSize(request)
        return request.copy(maxTokens = request.maxTokens.takeIf { it > 0 }?.coerceAtMost(4096) ?: 4096)
    }

    suspend fun recordUsage(tokens: Int) {
        store.recordTokens(receipt.id, tokens)
        quotas.recordTokenUsage(receipt.userId, tokens)
    }

    companion object {
        /** Canonical PNG data URLs are produced by attachment preparation, independently of text. */
        internal fun checkRequestSize(request: LLMRequest.Chat) {
            var imageBytes = 0L
            val control = request.copy(messages = request.messages.map { message ->
                message.copy(attachments = message.attachments?.map { attachment ->
                    if (attachment.startsWith(INLINE_PNG_PREFIX)) {
                        imageBytes += attachment.toByteArray(Charsets.UTF_8).size
                        if (imageBytes > MAX_INLINE_IMAGE_BYTES) throw hookError(429, "hook_llm_input_limit")
                        INLINE_PNG_PREFIX
                    } else attachment
                })
            })
            if (postgresStorageMapper.writeValueAsBytes(control).size > MAX_CONTROL_BYTES) {
                throw hookError(429, "hook_llm_input_limit")
            }
        }

        private const val INLINE_PNG_PREFIX = "data:image/png;base64,"
        private const val MAX_CONTROL_BYTES = 131_072
        private const val MAX_INLINE_IMAGE_BYTES = 14_000_000L

        suspend fun <T> withProviderCall(budget: HookLlmBudget?, provider: LlmProvider, call: suspend () -> T): T {
            if (budget == null) return call()
            return budget.quotas.withProviderPermit(provider) {
                budget.quotas.checkRequestRate(budget.receipt.userId)
                budget.quotas.checkTokenQuota(budget.receipt.userId)
                budget.store.reserveLlmCall(budget.receipt)
                call()
            }
        }
    }
}
