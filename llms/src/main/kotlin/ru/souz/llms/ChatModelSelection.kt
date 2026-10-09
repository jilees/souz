package ru.souz.llms

/** A built-in model or a host-configured model name, persisted using [alias]. */
sealed interface ChatModelSelection {
    val alias: String
    val provider: LlmProvider

    data class Named(override val alias: String) : ChatModelSelection {
        override val provider: LlmProvider get() = LlmProvider.OPENAI
    }
}

/** Host-resolved route; connection IDs never become part of the provider's JSON payload. */
data class ChatModelRoute(
    val modelId: String,
    val provider: LlmProvider,
    val connectionId: String? = null,
)
