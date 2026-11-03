package ru.souz.llms.codex

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper

/** Chat Completions bridge used by both the agent DTO adapter and the external HTTP API. */
class CodexCompletionClient(private val transport: CodexResponsesTransport) {
    fun completion(
        chat: ObjectNode,
        model: String,
        effort: String? = null,
        timeoutMillis: Long,
        onUsage: (LLMResponse.Usage) -> Unit = {},
    ): Flow<String> = flow {
        val request = codexCompletionRequest(chat, model, effort)
        val output = CodexCompletionOutput(model, chat.path("stream").asBoolean(),
            chat.path("stream_options").path("include_usage").asBoolean())
        transport.events(request, timeoutMillis).collect { event ->
            if (event.path("type").asText() == "response.completed") {
                val usage = event.path("response").path("usage")
                val input = usage.path("input_tokens").asInt()
                val generated = usage.path("output_tokens").asInt()
                onUsage(LLMResponse.Usage(input, generated, usage.path("total_tokens").asInt(input + generated),
                    usage.path("input_tokens_details").path("cached_tokens").asInt()))
            }
            for (chunk in output.accept(event)) emit(chunk)
        }
    }
}

private class CodexCompletionOutput(private val model: String, private val streaming: Boolean, private val includeUsage: Boolean) {
    private val id = "chatcmpl-${UUID.randomUUID()}"
    private val created = System.currentTimeMillis() / 1000
    private val toolIndexes = linkedMapOf<Int, Int>()
    private val items = mutableListOf<JsonNode>()
    private var emitted = false

    fun accept(event: JsonNode): List<String> = when (event.path("type").asText()) {
        "response.output_item.done" -> { items.add(event.path("item")); emptyList() }
        "response.output_text.delta" -> if (streaming) listOf(chunk(codexObject("content" to event.path("delta").asText()))) else emptyList()
        "response.output_item.added" -> toolAdded(event)
        "response.function_call_arguments.delta" -> if (streaming) listOf(chunk(codexObject("tool_calls" to listOf(
            codexObject("index" to toolIndexes.getOrPut(event.path("output_index").asInt()) { toolIndexes.size },
                "function" to codexObject("arguments" to event.path("delta").asText())),
        )))) else emptyList()
        "response.completed" -> finish(event.path("response"))
        else -> emptyList()
    }

    private fun toolAdded(event: JsonNode): List<String> {
        val item = event.path("item")
        if (!streaming || item.path("type").asText() != "function_call") return emptyList()
        val index = toolIndexes.getOrPut(event.path("output_index").asInt()) { toolIndexes.size }
        return listOf(chunk(codexObject("tool_calls" to listOf(codexObject(
            "index" to index, "id" to item.path("call_id").asText(), "type" to "function",
            "function" to codexObject("name" to item.path("name").asText(), "arguments" to ""),
        )))))
    }

    private fun finish(response: JsonNode): List<String> {
        val output = response.path("output").takeIf { it.isArray && !it.isEmpty }?.toList() ?: items
        val tools = output.filter { it.path("type").asText() == "function_call" }.map { item ->
            codexObject("id" to item.path("call_id").asText(), "type" to "function", "function" to codexObject(
                "name" to item.path("name").asText(), "arguments" to item.path("arguments").asText(),
            ))
        }
        val content = output.filter { it.path("type").asText() == "message" }.flatMap { it.path("content").toList() }
            .filter { it.path("type").asText() == "output_text" }.joinToString("") { it.path("text").asText() }
        val message = codexObject("role" to "assistant", "content" to content.ifEmpty { null })
        if (tools.isNotEmpty()) message.set<JsonNode>("tool_calls", restJsonMapper.valueToTree(tools))
        val finish = if (tools.isNotEmpty() || toolIndexes.isNotEmpty()) "tool_calls" else "stop"
        val rawUsage = response.path("usage")
        val usage = codexObject("prompt_tokens" to rawUsage.path("input_tokens").asInt(),
            "completion_tokens" to rawUsage.path("output_tokens").asInt(), "total_tokens" to rawUsage.path("total_tokens").asInt(rawUsage.path("input_tokens").asInt() + rawUsage.path("output_tokens").asInt()),
            "prompt_tokens_details" to rawUsage.path("input_tokens_details"),
            "completion_tokens_details" to rawUsage.path("output_tokens_details"))
        if (!streaming) return listOf(codexObject("id" to id, "object" to "chat.completion", "created" to created,
            "model" to model, "choices" to listOf(codexObject("index" to 0, "message" to message, "finish_reason" to finish)),
            "usage" to usage).toString())
        return buildList {
            if (!emitted) add(chunk(message))
            add(chunk(codexObject(), finish))
            if (includeUsage) add(codexObject("id" to id, "object" to "chat.completion.chunk", "created" to created,
                "model" to model, "choices" to emptyList<Any>(), "usage" to usage).toString())
            add("[DONE]")
        }
    }

    private fun chunk(delta: ObjectNode, finish: String? = null): String {
        if (!emitted) delta.put("role", "assistant")
        emitted = true
        return codexObject("id" to id, "object" to "chat.completion.chunk", "created" to created, "model" to model,
            "choices" to listOf(codexObject("index" to 0, "delta" to delta, "finish_reason" to finish))).toString()
    }
}
