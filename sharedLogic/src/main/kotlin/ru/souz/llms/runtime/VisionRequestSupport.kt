package ru.souz.llms.runtime

import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.findLLMModel

internal suspend fun requestVisionText(
    settingsProvider: SettingsProvider,
    input: VisionInput,
    attachments: List<String>,
    failurePrefix: String,
    modelSelection: String? = null,
    chatRequest: suspend (LLMRequest.Chat) -> LLMResponse.Chat,
): String {
    val model = if (modelSelection == null) settingsProvider.gigaModel else findLLMModel(modelSelection)
    return chatRequest(
        LLMRequest.Chat(
            model = if (modelSelection == null) settingsProvider.executionModelId(requireNotNull(model)) else model?.alias ?: modelSelection,
            provider = model?.provider ?: LlmProvider.OPENAI,
            messages = listOf(
                LLMRequest.Message(
                    role = LLMMessageRole.user,
                    content = input.question,
                    attachments = attachments,
                )
            ),
        ),
    ).requireAssistantText(failurePrefix)
}
