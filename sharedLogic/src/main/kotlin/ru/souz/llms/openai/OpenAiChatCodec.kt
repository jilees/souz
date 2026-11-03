package ru.souz.llms.openai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.restJsonMapper

/** Shared DTO conversion for Chat Completions and the Codex Responses bridge. */
internal class OpenAiChatCodec(private val provider: LlmProvider) {
    private val l = LoggerFactory.getLogger(OpenAiChatCodec::class.java)

    fun buildMessages(messages: List<LLMRequest.Message>): List<Map<String, Any?>> {
        val pendingToolCallIdsByName = mutableMapOf<String, ArrayDeque<String>>()
        val remainingToolResultIds = mutableMapOf<String, Int>()
        val remainingToolResultNames = mutableMapOf<String, Int>()
        val result = mutableListOf<MutableMap<String, Any?>>()
        val delayed = mutableListOf<MutableMap<String, Any?>>()
        val pendingToolCallIds = linkedSetOf<String>()
        var currentToolCallAssistant: MutableMap<String, Any?>? = null

        messages.forEach { msg ->
            if (msg.role != LLMMessageRole.function) return@forEach
            when {
                !msg.functionsStateId.isNullOrBlank() -> {
                    val id = msg.functionsStateId ?: return@forEach
                    remainingToolResultIds[id] = (remainingToolResultIds[id] ?: 0) + 1
                }
                !msg.name.isNullOrBlank() -> {
                    val name = msg.name ?: return@forEach
                    remainingToolResultNames[name] = (remainingToolResultNames[name] ?: 0) + 1
                }
            }
        }

        fun emit(message: MutableMap<String, Any?>) {
            if (pendingToolCallIds.isEmpty()) {
                result.add(message)
            } else {
                delayed.add(message)
            }
        }

        fun flushDelayedIfPossible() {
            if (pendingToolCallIds.isNotEmpty() || delayed.isEmpty()) return
            result.addAll(delayed)
            delayed.clear()
        }

        fun appendAssistantContent(message: MutableMap<String, Any?>, content: String) {
            val previous = message["content"] as? String
            message["content"] = if (previous.isNullOrEmpty()) content else "$previous\n$content"
        }

        fun parseAssistantToolCall(msg: LLMRequest.Message): Map<String, Any?>? {
            val functionsStateId = msg.functionsStateId ?: return null
            return try {
                val requestFunctionCall = msg.functionCall ?: run {
                    val contentJson = restJsonMapper.readTree(msg.content)
                    val name = contentJson["name"]?.asText()
                    val argumentsNode = contentJson["arguments"]
                    if (name != null && argumentsNode != null) {
                        LLMRequest.FunctionCall(name, restJsonMapper.writeValueAsString(argumentsNode))
                    } else {
                        null
                    }
                }
                if (requestFunctionCall != null) {
                    val idBudget = remainingToolResultIds[functionsStateId] ?: 0
                    val nameBudget = remainingToolResultNames[requestFunctionCall.name] ?: 0
                    if (idBudget <= 0 && nameBudget <= 0) {
                        return null
                    }
                    if (idBudget > 0) {
                        remainingToolResultIds[functionsStateId] = idBudget - 1
                    } else {
                        remainingToolResultNames[requestFunctionCall.name] = nameBudget - 1
                    }
                    pendingToolCallIdsByName.getOrPut(requestFunctionCall.name) { ArrayDeque() }.addLast(functionsStateId)
                    buildMap {
                        put("id", functionsStateId)
                        put("type", "function")
                        put("function", buildMap {
                            put("name", requestFunctionCall.name)
                            put("arguments", requestFunctionCall.arguments)
                        })
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                l.warn(
                    "Failed to parse tool call content for $provider: ${msg.content}. " +
                            "Falling back to standard content.",
                    e,
                )
                null
            }
        }

        messages.forEach { msg ->
            when (msg.role) {
                LLMMessageRole.function -> {
                    val toolCallId = msg.functionsStateId ?: msg.name?.let { name ->
                        pendingToolCallIdsByName[name]?.removeFirstOrNull()
                    }
                    if (toolCallId != null) {
                        result.add(
                            mutableMapOf(
                                "role" to "tool",
                                "content" to msg.content,
                                "tool_call_id" to toolCallId,
                            )
                        )
                        val resolvedPendingCall = pendingToolCallIds.remove(toolCallId)
                        if (resolvedPendingCall && pendingToolCallIds.isEmpty()) {
                            currentToolCallAssistant = null
                            flushDelayedIfPossible()
                        }
                    } else {
                        emit(
                            mutableMapOf<String, Any?>(
                                "role" to "function",
                                "content" to msg.content,
                            ).apply {
                                msg.name?.let { put("name", it) }
                            }
                        )
                    }
                }

                LLMMessageRole.assistant, LLMMessageRole.function_in_progress -> {
                    val toolCall = parseAssistantToolCall(msg)
                    if (toolCall != null) {
                        val assistantMessage = currentToolCallAssistant?.takeIf { pendingToolCallIds.isNotEmpty() }
                            ?: mutableMapOf<String, Any?>(
                                "role" to "assistant",
                                "content" to null,
                                "tool_calls" to mutableListOf<Map<String, Any?>>(),
                            ).also {
                                result.add(it)
                                currentToolCallAssistant = it
                            }
                        @Suppress("UNCHECKED_CAST")
                        val toolCalls = assistantMessage["tool_calls"] as MutableList<Map<String, Any?>>
                        toolCalls.add(toolCall)
                        pendingToolCallIds.add(toolCall["id"].toString())
                        // Legacy content can contain the call envelope instead of assistant text.
                        if (msg.functionCall != null) {
                            msg.content.toNormalizedAssistantContent()?.let { appendAssistantContent(assistantMessage, it) }
                        }
                    } else {
                        val normalizedContent = msg.content.toNormalizedAssistantContent()
                        if (normalizedContent == null && msg.name == null) {
                            return@forEach
                        }
                        // A provider response can be split into tool choices followed by text.
                        // Keep that text in the same assistant turn, before its tool results.
                        val pendingAssistant = currentToolCallAssistant
                        if (pendingAssistant != null && pendingToolCallIds.isNotEmpty() &&
                            delayed.isEmpty() && msg.name == null && normalizedContent != null) {
                            appendAssistantContent(pendingAssistant, normalizedContent)
                            return@forEach
                        }
                        emit(
                            mutableMapOf<String, Any?>(
                                "role" to "assistant",
                                "content" to normalizedContent,
                            ).apply {
                                msg.name?.let { put("name", it) }
                            }
                        )
                    }
                }

                else -> emit(
                    mutableMapOf<String, Any?>(
                        "role" to msg.role.name,
                        "content" to buildContent(msg),
                    ).apply {
                        msg.name?.let { put("name", it) }
                    }
                )
            }
        }

        if (pendingToolCallIds.isNotEmpty()) {
            l.warn(
                "$provider payload has unresolved tool calls {}. Delaying {} messages until resolved.",
                pendingToolCallIds,
                delayed.size,
            )
            val normalized = normalizeUnresolvedToolCalls(result, pendingToolCallIds)
            if (delayed.isNotEmpty()) {
                normalized.addAll(delayed)
            }
            return normalized
        }

        flushDelayedIfPossible()
        return result
    }

    fun buildTools(functions: List<LLMRequest.Function>): List<Map<String, Any>> {
        return functions.map { fn ->
            val properties = fn.parameters.properties.mapValues { (_, prop) ->
                buildMap {
                    put("type", prop.type)
                    prop.description?.let { put("description", it) }
                    prop.enum?.let { put("enum", it) }
                    if (prop.type == "array") {
                        // OpenAI tool schemas require `items` for every array type.
                        // Keep items unconstrained so existing tools can pass arrays of strings, objects, or mixed payloads.
                        put("items", emptyMap<String, Any>())
                    }
                }
            }
            buildMap {
                put("type", "function")
                put(
                    "function",
                    buildMap {
                        put("name", fn.name)
                        put("description", fn.description)
                        put(
                            "parameters",
                            buildMap {
                                put("type", fn.parameters.type)
                                put("properties", properties)
                                if (fn.parameters.required.isNotEmpty()) {
                                    put("required", fn.parameters.required)
                                }
                            }
                        )
                    }
                )
            }
        }
    }

    private fun buildContent(message: LLMRequest.Message): Any =
        message.attachments
            ?.takeIf { it.isNotEmpty() }
            ?.mapTo(mutableListOf<Map<String, Any?>>()) { attachment ->
                mapOf(
                    "type" to "image_url",
                    "image_url" to mapOf("url" to requireOpenAiImageUrl(attachment)),
                )
            }
            ?.also { content ->
                if (message.content.isNotBlank()) {
                    content.add(
                        0,
                        mapOf(
                            "type" to "text",
                            "text" to message.content,
                        )
                    )
                }
                if (content.isEmpty()) {
                    content += mapOf("type" to "text", "text" to "")
                }
            }
            ?: message.content

    private fun requireOpenAiImageUrl(attachment: String): String =
        attachment.takeIf(::isSupportedOpenAiImageUrl)
            ?: throw IllegalArgumentException(
                "$provider chat attachments must be image URLs (http(s)://...) or image data URLs (data:image/...). Unsupported value: $attachment",
            )

    private fun isSupportedOpenAiImageUrl(attachment: String): Boolean =
        attachment.startsWith("data:image/", ignoreCase = true) ||
            attachment.startsWith("https://", ignoreCase = true) ||
            attachment.startsWith("http://", ignoreCase = true)

    fun parseCompletionsResponse(text: String, requestModel: String): LLMResponse.Chat {
        val node = restJsonMapper.readTree(text)
        val choices = prepareChoices(parseChoices(node["choices"], isStream = false))
        val usage = parseUsage(node["usage"])
        return LLMResponse.Chat.Ok(
            choices = choices,
            created = node["created"]?.asLong() ?: (System.currentTimeMillis() / 1000),
            model = node["model"]?.asText() ?: requestModel,
            usage = usage,
        )
    }

    fun prepareChoices(chunks: List<LLMResponse.Choice>): List<LLMResponse.Choice> = chunks.map { msg ->
        when (msg.message.content) {
            "null" -> msg.copy(message = msg.message.copy(content = ""))
            else -> msg
        }
    }

    fun parseChoices(
        choicesNode: JsonNode?,
        isStream: Boolean
    ): List<LLMResponse.Choice> {
        if (choicesNode == null || !choicesNode.isArray) {
            return emptyList()
        }

        val choices = mutableListOf<LLMResponse.Choice>()
        choicesNode.forEachIndexed { idx, choiceNode ->
            val messageField = if (isStream) "delta" else "message"
            val messageNode = choiceNode[messageField]

            val messageContent = messageNode?.get("content").toOpenAiMessageContent()
            val role = messageNode?.get("role")?.asText().toOpenAiRole()
            val finishReason = choiceNode["finish_reason"]?.asText().toCompatibleFinishReason()
            val choiceIndex = choiceNode["index"]?.asInt() ?: idx
            val toolCallsNode = messageNode?.get("tool_calls")

            // Non-stream or complete parsing
            if (toolCallsNode != null && toolCallsNode.isArray && toolCallsNode.size() > 0) {
                toolCallsNode.forEach { toolCallNode ->
                    val functionNode = toolCallNode["function"]
                    val name = functionNode?.get("name")?.asText().orEmpty()
                    val argsText = functionNode?.get("arguments")?.asText() ?: ""
                    val args = if (argsText.isNotEmpty()) parseFunctionArguments(argsText) else emptyMap()

                    val functionsStateId = toolCallNode["id"]?.asText()
                    val toolIndex = toolCallNode["index"]?.asInt() ?: choiceIndex

                    choices += LLMResponse.Choice(
                        message = LLMResponse.Message(
                            content = "",
                            role = role,
                            functionCall = LLMResponse.FunctionCall(name, args),
                            functionsStateId = functionsStateId,
                        ),
                        index = toolIndex,
                        finishReason = LLMResponse.FinishReason.function_call,
                    )
                }
            }

            if (messageContent.isNotEmpty()) {
                choices += LLMResponse.Choice(
                    message = LLMResponse.Message(
                        content = messageContent,
                        role = role,
                        functionCall = null,
                        functionsStateId = null,
                    ),
                    index = choiceIndex,
                    finishReason = if (toolCallsNode != null && toolCallsNode.size() > 0) null else finishReason,
                )
            } else if (toolCallsNode == null || toolCallsNode.size() == 0) {
                choices += LLMResponse.Choice(
                    message = LLMResponse.Message(
                        content = "",
                        role = role,
                        functionCall = null,
                        functionsStateId = null,
                    ),
                    index = choiceIndex,
                    finishReason = finishReason,
                )
            }
        }

        return choices
    }

    fun parseUsage(node: JsonNode?): LLMResponse.Usage {
        val prompt = node?.get("prompt_tokens")?.asInt()
            ?: node?.get("input_tokens")?.asInt()
            ?: 0
        val completion = node?.get("completion_tokens")?.asInt()
            ?: node?.get("output_tokens")?.asInt()
            ?: 0
        val total = node?.get("total_tokens")?.asInt() ?: (prompt + completion)
        val cached = node
            ?.get("prompt_tokens_details")
            ?.get("cached_tokens")
            ?.asInt()
            ?: 0
        return LLMResponse.Usage(prompt, completion, total, cached)
    }

    private fun parseFunctionArguments(argsText: String): Map<String, Any> {
        if (argsText.isBlank()) return emptyMap()
        return runCatching { restJsonMapper.readValue<Map<String, Any>>(argsText) }
            .getOrElse {
                l.warn("Failed to parse $provider tool arguments: $argsText")
                emptyMap()
            }
    }

    private fun normalizeUnresolvedToolCalls(
        messages: List<MutableMap<String, Any?>>,
        unresolvedToolCallIds: Set<String>,
    ): MutableList<MutableMap<String, Any?>> {
        if (unresolvedToolCallIds.isEmpty()) return messages.toMutableList()

        val normalized = mutableListOf<MutableMap<String, Any?>>()
        messages.forEach { message ->
            val role = message["role"] as? String
            @Suppress("UNCHECKED_CAST")
            val toolCalls = message["tool_calls"] as? List<Map<String, Any?>>

            if (role != "assistant" || toolCalls.isNullOrEmpty()) {
                normalized.add(message)
                return@forEach
            }

            val resolvedCalls = mutableListOf<Map<String, Any?>>()
            val unresolvedCalls = mutableListOf<Map<String, Any?>>()
            toolCalls.forEach { toolCall ->
                val id = toolCall["id"]?.toString()
                if (id != null && id in unresolvedToolCallIds) {
                    unresolvedCalls.add(toolCall)
                } else {
                    resolvedCalls.add(toolCall)
                }
            }

            if (resolvedCalls.isNotEmpty()) {
                val resolvedMessage = message.toMutableMap().apply {
                    put("tool_calls", resolvedCalls)
                }
                normalized.add(resolvedMessage)
            }

            if (unresolvedCalls.isNotEmpty()) {
                val fallbackContent = unresolvedCalls.joinToString("\n") { toolCall ->
                    unresolvedToolCallToAssistantContent(toolCall)
                }
                if (fallbackContent.isNotBlank()) {
                    normalized.add(
                        mutableMapOf(
                            "role" to "assistant",
                            "content" to fallbackContent,
                        )
                    )
                }
            }
        }

        return normalized
    }

    private fun unresolvedToolCallToAssistantContent(toolCall: Map<String, Any?>): String {
        val function = toolCall["function"] as? Map<*, *> ?: return ""
        val name = function["name"]?.toString().orEmpty()
        val rawArguments = function["arguments"]?.toString().orEmpty()
        val parsedArguments: Any = runCatching {
            restJsonMapper.readTree(rawArguments)
        }.getOrElse {
            if (rawArguments.isBlank()) emptyMap<String, Any>() else rawArguments
        }
        return runCatching {
            restJsonMapper.writeValueAsString(
                mapOf(
                    "name" to name,
                    "arguments" to parsedArguments,
                )
            )
        }.getOrDefault("")
    }

}
