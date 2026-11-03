package ru.souz.backend.config

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import ru.souz.agent.spi.ModelRouting
import ru.souz.agent.spi.RoutedModel
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import ru.souz.llms.ModelResolution
import ru.souz.llms.findLLMModel
import ru.souz.llms.resolveChatModel
import ru.souz.llms.restJsonMapper

/** Backend deployment selections; credentials are deliberately excluded from generated toString methods. */
class NamedLlmModel(
    val modelId: String,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val parameters: String? = null,
    val provider: LlmProvider = LlmProvider.OPENAI,
    val timeoutMillis: Long? = null,
)

class NamedLlmConfig(
    val models: Map<String, NamedLlmModel> = emptyMap(),
    val subagentModels: Set<String> = emptySet(),
    val summarization: NamedLlmModel? = null,
    val routing: ModelRouting? = null,
    val reasoningEfforts: Map<String, String> = emptyMap(),
    val cascades: Map<String, List<String>> = emptyMap(),
) {
    val selectionNames: Set<String> = models.keys + cascades.keys

    fun providerForSelection(name: String): LlmProvider {
        val target = cascades[name]?.firstOrNull() ?: name
        return models[target]?.provider ?: findLLMModel(target)?.provider ?: LlmProvider.OPENAI
    }
}

/** Resolve and validate deployment routes once, before accepting requests. */
internal fun BackendConfigSource.loadNamedModels(): NamedLlmConfig {
    val path = value("SOUZ_LLM_CONFIG_FILE", "SOUZ_LLM_CONFIG_FILE") ?: return NamedLlmConfig()
    val root = restJsonMapper.factory.createParser(Files.readString(Path.of(path))).use { parser ->
        parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        val node = restJsonMapper.readTree<JsonNode>(parser)
        require(node != null && parser.nextToken() == null) { "Expected one LLM configuration object." }
        node
    }
    root.fieldsOnly("openaiConnections", "models", "subagentModels", "summarization", "routing", "reasoningEfforts", "cascades")
    val connections = root.entries("openaiConnections").mapValues { (id, node) ->
        node.fieldsOnly("baseUrl", "apiKeyEnv")
        val url = node.text("baseUrl")
        val uri = URI(url)
        require(when (uri.scheme) { "http", "https" -> true; else -> false } && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.port == -1 || uri.port in 1..65535)) { "Invalid baseUrl for connection $id." }
        val keyEnv = node.text("apiKeyEnv")
        url to requireNotNull(env(keyEnv)?.trim()?.takeIf(String::isNotEmpty)) {
            "Missing environment variable $keyEnv for connection $id."
        }
    }
    val models = root.entries("models").mapValues { (name, node) ->
        require(resolveChatModel(name) is ModelResolution.Unknown) { "Model name $name conflicts with a built-in model." }
        node.fieldsOnly("connection", "modelId", "parameters", "provider", "timeoutMs")
        require(name != value("OPENAI_MODEL", "OPENAI_MODEL")?.trim()) { "OPENAI_MODEL must not overlap named model selections." }
        val provider = when (node.get("provider")?.let { node.text("provider") }) {
            null, "openai" -> LlmProvider.OPENAI
            "codex" -> LlmProvider.CODEX
            else -> throw IllegalArgumentException("Named model provider must be openai or codex.")
        }
        val connection = if (provider == LlmProvider.OPENAI) {
            requireNotNull(connections[node.text("connection")]) { "Unknown connection for model $name." }
        } else {
            require(!node.has("connection") && !node.has("parameters")) { "Codex models use server OAuth and reasoningEfforts; connection and parameters are not supported." }
            null
        }
        val parameters = node["parameters"]?.also {
            require(it.isObject && it.properties().none { field -> field.key in RESERVED_MODEL_PARAMETERS }) {
                "Model parameters must be an object without request structure or token-limit overrides."
            }
        }?.toString()
        val timeout = node.get("timeoutMs")?.let {
            require(it.isIntegralNumber && it.canConvertToLong() && it.asLong() in 1..300_000L) {
                "Model timeoutMs must be an integer between 1 and 300000."
            }
            it.asLong()
        }
        NamedLlmModel(node.text("modelId"), connection?.first, connection?.second, parameters, provider, timeout)
    }
    val cascades = root.entries("cascades").mapValues { (name, targets) ->
        require(name !in models && resolveChatModel(name) is ModelResolution.Unknown) { "Cascade name conflicts with a model." }
        require(name != value("OPENAI_MODEL", "OPENAI_MODEL")?.trim()) { "OPENAI_MODEL must not overlap cascade selections." }
        require(targets.isArray && !targets.isEmpty && targets.all { it.isTextual }) { "Cascade must be a nonempty model-name array." }
        targets.map { it.textValue() }.also { names ->
            require(names.distinct().size == names.size && names.all {
                it in models || findLLMModel(it)?.provider == LlmProvider.CODEX
            } && names.all { it.isNotBlank() && it == it.trim() }) { "Cascade targets must be distinct configured or Codex models." }
        }
    }
    val reasoningEfforts = buildMap {
        root.entries("reasoningEfforts").forEach { (name, _) ->
            val builtin = findLLMModel(name)
            val codex = models[name]?.provider == LlmProvider.CODEX
            require(codex || (builtin != null && (builtin.provider == LlmProvider.OPENAI || builtin.provider == LlmProvider.CODEX) &&
                builtin != LLMModel.OpenAICompatibleCustom)) { "Model $name does not support configured reasoning effort." }
            val key = if (codex) name else requireNotNull(builtin).alias
            require(put(key, root["reasoningEfforts"].text(name)) == null) {
                "Duplicate reasoning effort for $key."
            }
        }
    }
    fun model(name: String): NamedLlmModel = requireNotNull(models[name]) { "Unknown configured model $name." }
    val subagents = root["subagentModels"]?.let { node ->
        require(node.isArray && node.all { it.isTextual }) { "subagentModels must be an array of model names." }
        node.mapTo(linkedSetOf()) { it.textValue().also { name ->
            require(name in models || name in cascades) { "Unknown subagent model $name." }
        } }
    }.orEmpty()
    val summary = root["summarization"]?.also {
        it.fieldsOnly("model")
        require(model(it.text("model")).provider == LlmProvider.OPENAI) { "Dedicated summarization requires an OpenAI-compatible connection." }
    }
    val routing = root["routing"]?.let { node ->
        node.fieldsOnly("simple", "complex")
        fun target(name: String): RoutedModel {
            val target = requireNotNull(node[name]) { "routing.$name is required." }
            target.fieldsOnly("model")
            val id = target.text("model")
            val builtin = if (id in models || id in cascades) null else requireNotNull(findLLMModel(id)) { "Unknown routing.$name model $id." }
            // A routed call sends the model name as is, so the custom selector would never reach OPENAI_MODEL.
            require(builtin != LLMModel.OpenAICompatibleCustom) { "routing.$name cannot use $id; name a configured model instead." }
            return RoutedModel(builtin?.alias ?: id, builtin?.provider ?: models[id]?.provider ?: LlmProvider.OPENAI)
        }
        ModelRouting(target("simple"), target("complex"))
    }
    return NamedLlmConfig(models, subagents, summary?.text("model")?.let(::model), routing, reasoningEfforts, cascades)
}

private val RESERVED_MODEL_PARAMETERS = setOf(
    "model", "messages", "stream", "tools", "tool_choice", "max_tokens", "max_completion_tokens", "context_size",
)

private fun JsonNode.fieldsOnly(vararg names: String) {
    require(isObject && properties().all { it.key in names }) { "Expected LLM configuration fields ${names.toList()}." }
}

private fun JsonNode.entries(name: String): Map<String, JsonNode> = get(name)?.let { node ->
    require(node.isObject && node.properties().all { it.key.isNotBlank() && it.key == it.key.trim() }) {
        "$name must be an object with nonblank, trimmed names."
    }
    node.properties().associate { it.key to it.value }
}.orEmpty()

private fun JsonNode.text(name: String): String {
    val node = get(name)
    require(node != null && node.isTextual && node.textValue().isNotBlank() && node.textValue() == node.textValue().trim()) {
        "$name must be a nonblank, trimmed string."
    }
    return node.textValue()
}
