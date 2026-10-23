package ru.souz.agent.spi

import ru.souz.llms.LLMRequest

/** Independent decisions made before memory recall and conversational model execution. */
data class TurnClassification(val complex: Boolean, val recallMemory: Boolean) {
    val expectedFast: Boolean get() = !complex && !recallMemory
}

fun interface TurnClassifier {
    suspend fun classify(request: LLMRequest.Chat): TurnClassification
}
