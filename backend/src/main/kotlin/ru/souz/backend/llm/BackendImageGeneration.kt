package ru.souz.backend.llm

import ru.souz.backend.config.NamedLlmModel
import ru.souz.backend.hooks.HookLlmBudget
import ru.souz.db.SettingsProvider
import ru.souz.llms.LlmProvider
import ru.souz.llms.ModelResolution
import ru.souz.llms.codex.CodexImageGenerationGateway
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.openai.OpenAIImageGenerationGateway
import ru.souz.llms.resolveChatModel
import ru.souz.llms.runtime.ImageGenerationGateway

internal class BackendImageModel(
    val modelId: String,
    val provider: LlmProvider,
    val connection: NamedLlmModel? = null,
)

internal fun resolveImageModel(selection: String, namedModels: Map<String, NamedLlmModel>): BackendImageModel {
    val name = selection.trim()
    namedModels[name]?.let { return BackendImageModel(it.modelId, LlmProvider.OPENAI, it) }
    val imageId = setOf("gpt-image-1", "gpt-image-2.5-flare").firstOrNull { it.equals(name, ignoreCase = true) }
    if (imageId != null) return BackendImageModel(imageId, LlmProvider.OPENAI)
    return when (val resolved = resolveChatModel(name, supportedProviders = setOf(LlmProvider.CODEX))) {
        is ModelResolution.Resolved -> BackendImageModel(resolved.value.alias, resolved.value.provider)
        is ModelResolution.Ambiguous -> error("Ambiguous image model; use an explicit model selector: $name")
        is ModelResolution.UnsupportedProvider -> error("Model does not support image generation: $name")
        is ModelResolution.Unknown -> error("Unknown image model: $name")
    }
}

/** Both image tools share this execution-scoped gateway, including credentials and hook admission. */
internal fun imageGenerationGateway(
    model: BackendImageModel,
    settings: SettingsProvider,
    clients: ProviderHttpClients,
    oauth: CodexOAuthService,
    openAiCredential: suspend () -> String,
    hookBudget: HookLlmBudget?,
): ImageGenerationGateway {
    val delegate = when (model.provider) {
        LlmProvider.CODEX -> CodexImageGenerationGateway(settings, oauth, clients.openAi)
        LlmProvider.OPENAI -> OpenAIImageGenerationGateway(
            settings, clients.openAi,
            apiKeyProvider = { model.connection?.apiKey ?: openAiCredential() },
            baseUrl = model.connection?.baseUrl,
        )
        else -> error("Unsupported image provider: ${model.provider}")
    }
    return ImageGenerationGateway { input ->
        val prompt = input.prompt.trim()
        require(prompt.isNotEmpty() && prompt.length <= 32_000) { "Image prompt must contain 1 to 32000 characters." }
        HookLlmBudget.withProviderCall(hookBudget, model.provider) {
            delegate.generate(input.copy(prompt = prompt, model = model.modelId,
                size = "1024x1024", quality = "medium", outputFormat = "png"))
        }
    }
}
