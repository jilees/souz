package ru.souz.backend.llmgateway

import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import ru.souz.backend.config.NamedLlmConfig
import ru.souz.backend.config.NamedLlmModel
import ru.souz.backend.llm.ModelFailureMemory
import ru.souz.backend.llm.hasCompleteCodexOAuthCredentials
import ru.souz.backend.metrics.BackendMetrics
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import ru.souz.llms.codex.CodexCompletionClient
import ru.souz.llms.codex.CodexResponsesTransport
import ru.souz.llms.codex.CodexFailure
import ru.souz.llms.codex.CodexEndpoints
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.findLLMModel
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.restJsonMapper

/** Stateless model calls over process-owned transports; no user, chat, agent, or memory runtime. */
internal class LlmGateway(
    val config: LlmGatewayConfig,
    private val models: NamedLlmConfig,
    private val settings: SettingsProvider,
    private val clients: ProviderHttpClients,
    oauth: CodexOAuthService,
    endpoints: CodexEndpoints,
    concurrency: Int,
    private val metrics: BackendMetrics,
) {
    private val permits = Semaphore(concurrency)
    private val failures = ModelFailureMemory()
    private val codex = CodexCompletionClient(CodexResponsesTransport(clients.standard, settings, oauth, endpoints))
    private val log = LoggerFactory.getLogger(LlmGateway::class.java)

    fun modelNames(): List<String> = models.cascades.keys.toList() + models.models.keys.toList() + if (settings.hasCompleteCodexOAuthCredentials()) {
        LLMModel.entries.filter { it.provider == LlmProvider.CODEX }.map { it.alias }
    } else emptyList()

    fun completion(body: ObjectNode): Flow<String> = flow {
        validateGatewayRequest(body)
        val selection = body.path("model").asText()
        requireModel(selection)
        val chain = (models.cascades[selection] ?: listOf(selection)).distinctBy(::failureKey)
        if (!permits.tryAcquire()) throw GatewayFailure(429, "gateway_busy", "LLM gateway concurrency limit reached.")
        try {
            var lastFailure: GatewayFailure? = null
            var tried = false
            for (name in chain) {
                val key = failureKey(name)
                if (chain.size > 1 && !failures.shouldTry(key)) continue
                tried = true
                var emitted = false
                var failed: GatewayFailure? = null
                attempt(name, body).catch { failure ->
                    failed = asGatewayFailure(failure)
                    log.warn("LLM gateway upstream failure for {}: {}", name, failure.javaClass.simpleName)
                }.collect {
                    emitted = true
                    emit(it)
                }
                failures.record(key, failed?.retryable == true)
                if (failed == null) return@flow
                if (emitted || !failed.retryable) throw failed
                if (name != chain.last()) log.warn("LLM gateway model {} failed with status {}; trying fallback", name, failed.status)
                lastFailure = failed
            }
            if (tried) throw requireNotNull(lastFailure)
            // Keep a fully cooling-down chain usable, matching the backend's primary trial policy.
            attempt(chain.first(), body).collect { emit(it) }
        } finally {
            permits.release()
        }
    }

    private fun requireModel(name: String) {
        if (name in models.models || name in models.cascades) return
        if (findLLMModel(name)?.provider == LlmProvider.CODEX) return
        throw GatewayFailure(404, "model_not_found", "Unknown or unavailable configured model.")
    }

    private fun failureKey(name: String): Any {
        val named = models.models[name]
        return if (named?.provider == LlmProvider.OPENAI) named else "codex:${named?.modelId ?: findLLMModel(name)?.alias}"
    }

    // Classify the metric before rethrowing cancellation; the catch performs no suspending work.
    @Suppress("SuspendFunSwallowedCancellation")
    private fun attempt(name: String, request: ObjectNode): Flow<String> = flow {
        requireModel(name)
        val named = models.models[name]
        val provider = named?.provider ?: LlmProvider.CODEX
        if (provider == LlmProvider.CODEX && !settings.hasCompleteCodexOAuthCredentials()) {
            throw GatewayFailure(503, "codex_unavailable", "Server Codex credentials are unavailable.")
        }
        val upstream = named?.modelId ?: requireNotNull(findLLMModel(name)).alias
        val start = System.nanoTime()
        var outcome = "error"
        try {
            val source = if (provider == LlmProvider.OPENAI) compatible(requireNotNull(named), request) else {
                codex.completion(request, upstream, models.reasoningEfforts[name] ?: models.reasoningEfforts[upstream], named?.timeoutMillis ?: config.timeoutMillis) {
                    metrics.llmUsage(provider, upstream, it)
                }
            }
            source.collect { data ->
                if (provider != LlmProvider.CODEX && data != "[DONE]") recordUsage(provider, upstream, data)
                emit(data)
            }
            outcome = "success"
        } catch (cancelled: CancellationException) {
            outcome = "cancelled"
            throw cancelled
        } finally {
            metrics.llmAttempt(provider, upstream, outcome, System.nanoTime() - start)
        }
    }

    private fun compatible(model: NamedLlmModel, input: ObjectNode): Flow<String> = flow {
        val body = model.parameters?.let { restJsonMapper.readTree(it) as ObjectNode } ?: gatewayObject()
        body.setAll<ObjectNode>(input)
        body.put("model", model.modelId)
        clients.openAi.preparePost(requireNotNull(model.baseUrl).trimEnd('/') + "/chat/completions") {
            timeout { requestTimeoutMillis = model.timeoutMillis ?: config.timeoutMillis }
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${model.apiKey}")
            setBody(body.toString())
        }.execute { response ->
            if (!response.status.isSuccess()) throw gatewayHttpFailure(response)
            if (!input.path("stream").asBoolean()) {
                val text = response.bodyAsText()
                val result = restJsonMapper.readTree(text)
                if (result?.path("choices")?.isArray != true || result.path("choices").isEmpty) {
                    throw GatewayFailure(502, "upstream_empty_response", "Upstream returned no completion choices.")
                }
                emit(text)
            } else {
                var done = false
                readGatewaySse(response.bodyAsChannel()) { data ->
                    if (data != "[DONE]" && restJsonMapper.readTree(data)?.has("error") == true) {
                        throw upstreamGatewayFailure(502)
                    }
                    emit(data)
                    done = data == "[DONE]"
                    !done
                }
                if (!done) throw GatewayFailure(502, "upstream_stream_incomplete", "Upstream stream ended without completion.")
            }
        }
    }

    private fun recordUsage(provider: LlmProvider, model: String, data: String) {
        val usage = restJsonMapper.readTree(data)?.get("usage") ?: return
        metrics.llmUsage(provider, model, ru.souz.llms.LLMResponse.Usage(
            usage.path("prompt_tokens").asInt(), usage.path("completion_tokens").asInt(), usage.path("total_tokens").asInt(),
            usage.path("prompt_tokens_details").path("cached_tokens").asInt(),
        ))
    }
}

internal fun asGatewayFailure(failure: Throwable): GatewayFailure = when (failure) {
    is CancellationException -> throw failure
    is GatewayFailure -> failure
    is CodexFailure -> GatewayFailure(failure.status, failure.code, "Codex model request failed.")
    is io.ktor.client.plugins.HttpRequestTimeoutException -> GatewayFailure(504, "upstream_timeout", "Upstream model timed out.")
    else -> GatewayFailure(502, "upstream_connection_error", "Upstream model connection failed.")
}
