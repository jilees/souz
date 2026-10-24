package ru.souz.agent.nodes

import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest

private const val SPEAKER_NAME_PREFIX = "souz_speaker_"
internal const val SPEAKER_SUMMARY_NAME = "souz_speaker_summary"

/** Stored in structural metadata; render only at the model boundary, never into user text or capture. */
internal fun speakerMessageName(attributes: Map<String, String>, history: List<LLMRequest.Message>): String? =
    attributes["voiceEmbeddingId"]?.let { "$SPEAKER_NAME_PREFIX$it" }
        ?: "${SPEAKER_NAME_PREFIX}unknown".takeIf { history.hasSpeakerContext() }

internal fun List<LLMRequest.Message>.hasSpeakerContext(): Boolean =
    any { it.name?.startsWith(SPEAKER_NAME_PREFIX) == true }

internal fun renderSpeakerContext(
    history: List<LLMRequest.Message>,
    speakerContext: Boolean = history.hasSpeakerContext(),
): List<LLMRequest.Message> {
    if (!speakerContext) return history
    return history.map { message ->
        if (message.role != LLMMessageRole.user || message.isInjectedMemoryContextMessage() ||
            message.isInjectedContextMessage() || message.name == "souz_summarization_request") message
        else {
            val speaker = message.name?.takeIf { it.startsWith(SPEAKER_NAME_PREFIX) }
                ?.removePrefix(SPEAKER_NAME_PREFIX) ?: "unknown"
            message.copy(content = "[souz_speaker id=\"$speaker\"]\n${message.content}", name = null)
        }
    }
}
