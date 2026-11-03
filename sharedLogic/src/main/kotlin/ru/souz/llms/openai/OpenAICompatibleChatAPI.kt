package ru.souz.llms.openai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.restJsonMapper
import ru.souz.llms.toFinishReason
import java.io.File

class OpenAICompatibleChatAPI(
    private val provider: LlmProvider,
    private val settingsProvider: SettingsProvider,
    private val client: HttpClient,
    apiKey: String? = null,
    private val baseUrl: String? = null,
    private val requestParameters: String? = null,
) : LLMChatAPI {
    init {
        require(provider == LlmProvider.OPENAI || provider == LlmProvider.AI_TUNNEL || provider == LlmProvider.QWEN) {
            "$provider is not an OpenAI-compatible provider"
        }
    }

    private val l = LoggerFactory.getLogger(OpenAICompatibleChatAPI::class.java)
    private val codec = OpenAiChatCodec(provider)
    private val immutableApiKey = apiKey

    private val apiKey: String
        get() = immutableApiKey ?: when (provider) {
            LlmProvider.OPENAI -> settingsProvider.openaiKey
                ?: System.getenv("OPENAI_API_KEY")
                ?: System.getProperty("OPENAI_API_KEY")
                ?: throw IllegalStateException("OPENAI_API_KEY is not set")
            LlmProvider.AI_TUNNEL -> settingsProvider.aiTunnelKey
                ?: System.getenv("AITUNNEL_KEY")
                ?: System.getProperty("AITUNNEL_KEY")
                ?: throw IllegalStateException("AITUNNEL_KEY is not set")
            LlmProvider.QWEN -> settingsProvider.qwenChatKey
                ?: System.getenv("QWEN_KEY")
                ?: System.getProperty("QWEN_KEY")
                ?: throw IllegalStateException("QWEN_KEY is not set")
            else -> error("Unsupported provider: $provider")
        }

    private val defaultEmbeddingsModel: String
        get() = when (provider) {
            LlmProvider.OPENAI -> System.getenv("OPENAI_EMBEDDINGS_MODEL")
                ?: System.getProperty("OPENAI_EMBEDDINGS_MODEL")
                ?: "text-embedding-3-small"
            LlmProvider.AI_TUNNEL -> System.getenv("AITUNNEL_EMBEDDINGS_MODEL")
                ?: System.getProperty("AITUNNEL_EMBEDDINGS_MODEL")
                ?: "text-embedding-3-small"
            LlmProvider.QWEN -> System.getenv("QWEN_EMBEDDINGS_MODEL")
                ?: System.getProperty("QWEN_EMBEDDINGS_MODEL")
                ?: "text-embedding-v3"
            else -> error("Unsupported provider: $provider")
        }

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat = try {
        val response = client.post(chatCompletionsUrl) {
            applyRequestDefaults()
            setBody(buildChatRequest(body, stream = false))
        }
        val text = response.bodyAsText()
        if (response.status.isSuccess()) {
            codec.parseCompletionsResponse(text, body.model).also {
                l.info("Model: ${body.model}. Response received")
            }
        } else {
            LLMResponse.Chat.Error(response.status.value, text)
        }
    } catch (e: ClientRequestException) {
        val text = e.response.bodyAsText()
        LLMResponse.Chat.Error(e.response.status.value, text)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (t: Throwable) {
        l.error("Model: ${body.model}. Error in chat", t)
        LLMResponse.Chat.Error(-1, "Connection error: ${t.message}")
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = channelFlow {
        try {
            val accumulator = OpenAiStreamAccumulator()

            client.preparePost(chatCompletionsUrl) {
                applyRequestDefaults()
                setBody(buildChatRequest(body, stream = true))
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val text = response.bodyAsText()
                    send(LLMResponse.Chat.Error(response.status.value, text))
                    return@execute
                }

                val channel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.startsWith("data:")) {
                        val data = line.removePrefix("data:").trimStart()
                        if (data == "[DONE]") break

                        try {
                            val chunkNode: JsonNode = restJsonMapper.readTree(data)
                            val model = chunkNode["model"]?.asText() ?: body.model
                            val created = chunkNode["created"]?.asLong() ?: (System.currentTimeMillis() / 1000)

                            val chunks = accumulator.processChunk(chunkNode)
                            val usageNode = chunkNode["usage"]?.takeUnless { it.isNull }

                            if (chunks.isNotEmpty() || usageNode != null) {
                                val usage = codec.parseUsage(usageNode)
                                val choices = codec.prepareChoices(chunks)
                                send(LLMResponse.Chat.Ok(choices, created, model, usage))
                            }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (e: Exception) {
                            l.warn("Model: ${body.model}. Failed to parse stream chunk: $data", e)
                        }
                    }
                }
            }
        } catch (e: ClientRequestException) {
            val text = e.response.bodyAsText()
            send(LLMResponse.Chat.Error(e.response.status.value, text))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            l.error("Provider: $provider. Model: ${body.model}. Error in stream chat", t)
            send(LLMResponse.Chat.Error(-1, "Connection error: ${t.message}"))
        }
    }

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings = try {
        val response = client.post(embeddingsUrl) {
            applyRequestDefaults()
            setBody(buildEmbeddingsRequest(body))
        }
        val text = response.bodyAsText()
        if (response.status.isSuccess()) {
            parseEmbeddingsResponse(text)
        } else {
            LLMResponse.Embeddings.Error(response.status.value, text)
        }
    } catch (e: ClientRequestException) {
        val text = e.response.bodyAsText()
        LLMResponse.Embeddings.Error(e.response.status.value, text)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (t: Throwable) {
        l.error("Provider: $provider. Model: ${body.model}. Error in embeddings", t)
        LLMResponse.Embeddings.Error(-1, "Connection error: ${t.message}")
    }

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        throw UnsupportedOperationException("$provider file upload is not supported")

    override suspend fun downloadFile(fileId: String): String? = null

    override suspend fun balance(): LLMResponse.Balance =
        LLMResponse.Balance.Error(-1, "Balance check not implemented for $provider")

    private fun io.ktor.client.request.HttpRequestBuilder.applyRequestDefaults() {
        header(HttpHeaders.ContentType, ContentType.Application.Json)
        header(HttpHeaders.Accept, ContentType.Application.Json)
        header(HttpHeaders.Authorization, "Bearer $apiKey")
        timeout { requestTimeoutMillis = settingsProvider.requestTimeoutMillis }
    }

    private fun buildChatRequest(body: LLMRequest.Chat, stream: Boolean): Map<String, Any> {
        val tools = codec.buildTools(body.functions)
        return buildMap {
            requestParameters?.let { putAll(restJsonMapper.readValue<Map<String, Any>>(it)) }
            put("model", body.model)
            put("messages", codec.buildMessages(body.messages))
            put("stream", stream)
            body.reasoningEffort?.takeIf { provider == LlmProvider.OPENAI }?.let { put("reasoning_effort", it) }
            if (stream && shouldIncludeStreamUsage()) {
                put("stream_options", mapOf("include_usage" to true))
            }

            if (provider != LlmProvider.OPENAI) {
                body.temperature?.let { put("temperature", it) }
            }
            if (body.maxTokens > 0) {
                remove("max_tokens")
                remove("max_completion_tokens")
                val field = when (provider) {
                    LlmProvider.OPENAI -> "max_completion_tokens"
                    LlmProvider.AI_TUNNEL,
                    LlmProvider.QWEN,
                    -> "max_tokens"
                    else -> error("Unsupported provider: $provider")
                }
                put(field, body.maxTokens)
            }
            if (provider == LlmProvider.QWEN) {
                put("parallel_tool_calls", true)
            }
            if (tools.isNotEmpty()) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }
    }

    private fun buildEmbeddingsRequest(body: LLMRequest.Embeddings): Map<String, Any> = buildMap {
        put("model", resolveEmbeddingsModel(body.model))
        if (body.input.size == 1) {
            put("input", body.input.first())
        } else {
            put("input", body.input)
        }
        if (provider == LlmProvider.OPENAI) {
            put("encoding_format", "float")
        }
    }

    private fun parseEmbeddingsResponse(text: String): LLMResponse.Embeddings {
        val node = restJsonMapper.readTree(text)
        val data = node["data"]?.mapIndexed { index, item ->
            LLMResponse.Embedding(
                embedding = item["embedding"]?.map { it.asDouble() } ?: emptyList(),
                index = item["index"]?.asInt() ?: index,
                objectType = item["object"]?.asText(),
            )
        } ?: emptyList()

        return LLMResponse.Embeddings.Ok(
            data = data,
            model = node["model"]?.asText() ?: "",
            objectType = node["object"]?.asText() ?: "list",
        )
    }

    private fun resolveEmbeddingsModel(model: String): String {
        val normalized = model.trim()
        if (normalized.equals(ru.souz.llms.DEFAULT_EMBEDDINGS_MODEL, ignoreCase = true)) {
            return defaultEmbeddingsModel
        }
        return normalized
    }

    companion object {
        private const val CHAT_COMPLETIONS_PATH = "chat/completions"
        private const val EMBEDDINGS_PATH = "embeddings"
        private const val AI_TUNNEL_BASE_URL = "https://api.aitunnel.ru/v1"
        private const val QWEN_BASE_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
    }

    private val chatCompletionsUrl: String
        get() = endpoint(CHAT_COMPLETIONS_PATH)

    private val embeddingsUrl: String
        get() = endpoint(EMBEDDINGS_PATH)

    private fun endpoint(path: String): String = when (provider) {
        LlmProvider.OPENAI -> settingsProvider.openAIEndpoint(baseUrl).endpoint(path)
        LlmProvider.AI_TUNNEL -> "$AI_TUNNEL_BASE_URL/$path"
        LlmProvider.QWEN -> "$QWEN_BASE_URL/$path"
        else -> error("Unsupported provider: $provider")
    }

    private fun shouldIncludeStreamUsage(): Boolean = when (provider) {
        LlmProvider.OPENAI -> settingsProvider.openAIEndpoint(baseUrl).isOfficial
        LlmProvider.AI_TUNNEL,
        LlmProvider.QWEN,
        -> true
        else -> error("Unsupported provider: $provider")
    }
}

internal fun String.toNormalizedAssistantContent(): String? {
    if (this.isBlank()) return null
    if (this.equals("null", ignoreCase = true)) return null
    return this
}

internal fun JsonNode?.toOpenAiMessageContent(): String {
    if (this == null || this.isNull) return ""
    val text = this.asText()
    return if (text.equals("null", ignoreCase = true)) "" else text
}


internal fun String?.toCompatibleFinishReason(): LLMResponse.FinishReason? =
    when {
        isNullOrBlank() || equals("null", ignoreCase = true) -> null
        this == "tool_calls" -> LLMResponse.FinishReason.function_call
        this == "length" || this == "max_tokens" -> LLMResponse.FinishReason.length
        this == "stop" -> LLMResponse.FinishReason.stop
        else -> toFinishReason()
    }

internal fun String?.toOpenAiRole(): LLMMessageRole {
    return runCatching { LLMMessageRole.valueOf(this ?: "") }
        .getOrDefault(LLMMessageRole.assistant)
}

// Helper class to buffer tool call arguments in streaming
private class OpenAiStreamAccumulator {
    private val choicesState = mutableMapOf<Int, ChoiceState>()
    private val toolChoiceIndexByPair = mutableMapOf<Pair<Int, Int>, Int>()
    private var nextToolChoiceIndex = -1

    data class ChoiceState(
        val role: LLMMessageRole? = null,
        val toolCalls: Map<Int, ToolCallState> = emptyMap(),
    )

    data class ToolCallState(
        val id: String? = null,
        val name: String? = null,
        // The collecting coroutine owns this buffer; metadata copies preserve it without recopying prior chunks.
        val arguments: StringBuilder = StringBuilder(),
    )

    fun processChunk(chunkNode: JsonNode): List<LLMResponse.Choice> {
        val choicesNode = chunkNode["choices"] ?: return emptyList()
        if (!choicesNode.isArray) return emptyList()

        val resultChoices = mutableListOf<LLMResponse.Choice>()

        choicesNode.forEach { choiceNode ->
            val index = choiceNode["index"]?.asInt() ?: 0
            var state = choicesState[index] ?: ChoiceState()

            val delta = choiceNode["delta"]
            val finishReasonText = choiceNode["finish_reason"]?.asText()
            val finishReason = finishReasonText.toCompatibleFinishReason()

            // Update Role
            delta?.get("role")?.asText()?.let {
                if (it.isNotBlank()) state = state.copy(role = LLMMessageRole.valueOf(it))
            }

            // Append Content
            val contentOrNull = delta?.get("content")?.asText()
            if (!contentOrNull.isNullOrEmpty()) {
                // Emit content immediately as stream
                resultChoices.add(
                    LLMResponse.Choice(
                        message = LLMResponse.Message(
                            content = contentOrNull,
                            role = state.role ?: LLMMessageRole.assistant,
                            functionCall = null,
                            functionsStateId = null
                        ),
                        index = index,
                        finishReason = null
                    )
                )
            }

            // Process Tool Calls
            val toolCallsNode = delta?.get("tool_calls")
            if (toolCallsNode != null && toolCallsNode.isArray) {
                val toolCalls = state.toolCalls.toMutableMap()
                toolCallsNode.forEach { tcNode ->
                    val tcIndex = tcNode["index"]?.asInt() ?: 0
                    val tcState = toolCalls[tcIndex] ?: ToolCallState()
                    val function = tcNode["function"]
                    function?.get("arguments")?.asText()?.let { tcState.arguments.append(it) }
                    toolCalls[tcIndex] = tcState.copy(
                        id = tcNode["id"]?.asText() ?: tcState.id,
                        name = function?.get("name")?.asText() ?: tcState.name,
                    )
                }
                state = state.copy(toolCalls = toolCalls)
            }

            // Handle Finish Reason
            if (finishReason != null) {
                // If finish reason implies tool calls, emit them now
                if (state.toolCalls.isNotEmpty()) {
                    state.toolCalls.forEach { (toolCallIndex, tcState) ->
                        val argsMap = parseFunctionArguments(tcState.arguments.toString())
                        // Emit the full tool call
                        if (tcState.name != null) {
                            val syntheticChoiceIndex = syntheticToolChoiceIndex(index, toolCallIndex)
                            resultChoices.add(
                                LLMResponse.Choice(
                                    message = LLMResponse.Message(
                                        content = "",
                                        role = state.role ?: LLMMessageRole.assistant,
                                        functionCall = LLMResponse.FunctionCall(
                                            name = tcState.name,
                                            arguments = argsMap
                                        ),
                                        functionsStateId = tcState.id
                                    ),
                                    index = syntheticChoiceIndex,
                                    finishReason = finishReason
                                )
                            )
                        }
                    }
                    // Clear tool calls after emitting to avoid duplicate emissions if multiple finish reasons (unlikely)
                    state = state.copy(toolCalls = emptyMap())
                } else if (finishReason != LLMResponse.FinishReason.function_call) {
                    // Signal end of stream
                    resultChoices.add(
                        LLMResponse.Choice(
                            message = LLMResponse.Message(
                                content = "",
                                role = state.role ?: LLMMessageRole.assistant,
                                functionCall = null,
                                functionsStateId = null
                            ),
                            index = index,
                            finishReason = finishReason
                        )
                    )
                }
            }
            choicesState[index] = state
        }
        return resultChoices
    }

    private fun parseFunctionArguments(argsText: String): Map<String, Any> {
        if (argsText.isBlank()) return emptyMap()
        return runCatching { restJsonMapper.readValue<Map<String, Any>>(argsText) }
            .getOrElse {
                emptyMap()
            }
    }

    private fun syntheticToolChoiceIndex(choiceIndex: Int, toolCallIndex: Int): Int {
        val key = choiceIndex to toolCallIndex
        return toolChoiceIndexByPair.getOrPut(key) {
            nextToolChoiceIndex--
            nextToolChoiceIndex + 1
        }
    }
}
