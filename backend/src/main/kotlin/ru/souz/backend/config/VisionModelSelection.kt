package ru.souz.backend.config

import ru.souz.backend.common.BackendConfigurationException
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import ru.souz.llms.findLLMModel

/** Dedicated vision uses inline pixels through OpenAI-compatible or Codex routes. */
internal fun validateVisionModel(selection: String?, config: NamedLlmConfig): String? {
    if (selection == null) return null
    val targets = config.cascades[selection] ?: listOf(selection)
    for (target in targets) {
        val named = config.models[target]
        val builtin = findLLMModel(target)
        val provider = named?.provider ?: builtin?.provider
        if (provider != LlmProvider.OPENAI && provider != LlmProvider.CODEX || builtin == LLMModel.OpenAICompatibleCustom) {
            throw BackendConfigurationException(
                "SOUZ_ATTACHMENTS_VISION_MODEL must select an OpenAI-compatible or Codex model, or a cascade of those models.",
            )
        }
    }
    return selection
}
