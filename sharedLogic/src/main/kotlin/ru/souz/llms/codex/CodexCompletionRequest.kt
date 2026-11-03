package ru.souz.llms.codex

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Chat Completions to Responses conversion, preserving nested tool and structured-output schemas. */
internal fun codexCompletionRequest(chat: ObjectNode, model: String, effort: String?): ObjectNode {
    val supported = setOf("model", "messages", "stream", "stream_options", "tools", "tool_choice", "parallel_tool_calls",
        "response_format", "reasoning_effort", "max_tokens", "max_completion_tokens", "temperature", "top_p", "n", "user")
    val unsupported = chat.fieldNames().asSequence().firstOrNull { it !in supported }
    if (unsupported != null || chat.path("n").asInt(1) != 1) {
        throw CodexFailure(400, "unsupported_parameter", "Unsupported Codex parameter: ${unsupported ?: "n"}.")
    }
    val input = codexObject().putArray("input")
    val instructions = mutableListOf<String>()
    for (message in chat.path("messages")) {
        val content = message.get("content")
        when (message.path("role").asText()) {
            "system", "developer" -> instructions += codexText(content)
            "tool" -> input.add(codexObject("type" to "function_call_output",
                "call_id" to message.path("tool_call_id").asText(), "output" to codexText(content)))
            "assistant", "user" -> {
                if (content != null && !content.isNull) input.add(codexObject(
                    "type" to "message", "role" to message.path("role").asText(),
                    "content" to codexContent(content, message.path("role").asText() == "assistant"),
                ))
                for (call in message.path("tool_calls")) {
                    if (call.path("type").asText() != "function") throw CodexFailure(400, "unsupported_tool", "Only function tools are supported.")
                    input.add(codexObject("type" to "function_call", "call_id" to call.path("id").asText(),
                        "name" to call.path("function").path("name").asText(),
                        "arguments" to call.path("function").path("arguments").asText()))
                }
            }
            else -> throw CodexFailure(400, "invalid_role", "Unsupported message role.")
        }
    }
    val result = codexObject("model" to model, "stream" to true, "store" to false, "input" to input,
        "instructions" to instructions.joinToString("\n\n"))
    val requestedEffort = chat.path("reasoning_effort").takeIf { it.isTextual }?.asText() ?: effort
    if (requestedEffort != null) result.set<ObjectNode>("reasoning", codexObject("effort" to requestedEffort))
    chat.get("response_format")?.let { format ->
        val value = if (format.path("type").asText() == "json_schema") {
            (format.path("json_schema").deepCopy<JsonNode>() as? ObjectNode
                ?: throw CodexFailure(400, "invalid_response_format", "json_schema must be an object.")).put("type", "json_schema")
        } else format
        result.set<ObjectNode>("text", codexObject("format" to value))
    }
    chat.get("tools")?.let { tools ->
        result.set<JsonNode>("tools", codexObject().putArray("tools").also { target ->
            for (tool in tools) {
                if (tool.path("type").asText() != "function" || !tool.path("function").isObject) {
                    throw CodexFailure(400, "unsupported_tool", "Only function tools are supported.")
                }
                target.add((tool["function"].deepCopy<JsonNode>() as ObjectNode).put("type", "function"))
            }
        })
    }
    chat.get("tool_choice")?.let { choice ->
        result.set<JsonNode>("tool_choice", if (choice.isTextual) choice else codexObject(
            "type" to "function", "name" to choice.path("function").path("name").asText(),
        ))
    }
    for (key in listOf("parallel_tool_calls", "temperature", "top_p")) chat.get(key)?.let { result.set<JsonNode>(key, it) }
    (chat.get("max_completion_tokens") ?: chat.get("max_tokens"))?.let { result.set<JsonNode>("max_output_tokens", it) }
    return result
}

private fun codexText(content: JsonNode?): String = when {
    content == null || content.isNull -> ""
    content.isTextual -> content.asText()
    content.isArray -> content.joinToString("\n") {
        if (it.path("type").asText() != "text") throw CodexFailure(400, "unsupported_content", "Expected text content.")
        it.path("text").asText()
    }
    else -> throw CodexFailure(400, "invalid_content", "Invalid message content.")
}

private fun codexContent(content: JsonNode, assistant: Boolean): JsonNode {
    if (content.isTextual) return content
    if (!content.isArray) throw CodexFailure(400, "invalid_content", "Expected text or content parts.")
    return codexObject().putArray("content").also { target ->
        for (part in content) when (part.path("type").asText()) {
            "text" -> target.add(codexObject("type" to if (assistant) "output_text" else "input_text", "text" to part.path("text").asText()))
            "image_url" -> target.add(codexObject("type" to "input_image", "image_url" to part.path("image_url").path("url").asText()))
            else -> throw CodexFailure(400, "unsupported_content", "Unsupported Codex content part.")
        }
    }
}
