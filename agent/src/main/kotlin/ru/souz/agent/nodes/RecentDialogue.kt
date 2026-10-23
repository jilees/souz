package ru.souz.agent.nodes

import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest

/** Keep recent conversational turns and the entire active exchange without changing stored history. */
internal fun recentDialogueHistory(
    history: List<LLMRequest.Message>,
    activeExchangeStartIndex: Int,
): List<LLMRequest.Message> {
    val previous = history.take(activeExchangeStartIndex)
    val turns = ArrayDeque<MutableList<LLMRequest.Message>>()
    for (message in previous) {
        if (message.isInjectedMemoryContextMessage() || message.isInjectedContextMessage()) continue
        when (message.role) {
            LLMMessageRole.user -> {
                if (turns.size == RECENT_DIALOGUE_TURNS) turns.removeFirst()
                turns.addLast(mutableListOf(message))
            }
            LLMMessageRole.assistant -> if (
                turns.isNotEmpty() && message.content.isNotBlank() && message.functionCall == null &&
                message.functionsStateId == null && message.name != SPEAKER_SUMMARY_NAME
            ) {
                turns.last().add(message)
            }
            else -> Unit
        }
    }
    return previous.filter { it.role == LLMMessageRole.system } +
        turns.flatten() + history.drop(activeExchangeStartIndex)
}

private const val RECENT_DIALOGUE_TURNS = 20
