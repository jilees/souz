package ru.souz.db

import ru.souz.llms.ChatModelRoute

/** Resolved server configuration. Secrets are deliberately excluded from toString(). */
class OpenAiConnection(val baseUrl: String, val apiKey: String)

data class NamedLlmModels(
    val connections: Map<String, OpenAiConnection> = emptyMap(),
    val models: Map<String, ChatModelRoute> = emptyMap(),
    val subagentModels: Map<String, ChatModelRoute> = emptyMap(),
    val summarization: ChatModelRoute? = null,
    val summarizationContextSize: Int? = null,
) {
    companion object {
        val EMPTY = NamedLlmModels()
    }
}
