package ru.souz.backend.hooks

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory

/** Lazy service resolution avoids the runtime/catalogue/execution-service composition cycle. */
internal class HookManagementTools(service: () -> HookManagement) {
    val catalog = AgentToolCatalog(mapOf(ToolCategory.HOOKS to
        listOf("create", "get", "list", "update", "delete").map { operation ->
            val name = "hook.$operation"
            object : LLMToolSetup {
                override val fn = LLMRequest.Function(name, descriptions.getValue(operation), parameters(operation))
                override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
                    result(functionCall.name, mapOf("status" to "failed", "error" to mapOf("code" to "hook_context_missing")))
                override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message =
                    result(functionCall.name, service().invoke(operation, functionCall.arguments, meta))
            }
        }))
}

private fun result(name: String, value: Any) = LLMRequest.Message(LLMMessageRole.function,
    restJsonMapper.writeValueAsString(value), name = name)

private val descriptions = mapOf(
    "create" to "Create a Bearer webhook that runs prompt in a fresh owner-scoped chat for each event. " +
        "prompt must contain the complete event condition/action and delivery destination; a final technical-chat answer is not a notification. " +
        "Returns hookId, public HTTPS url and HTTP request contract. Default enabled=true; use false while provisioning a source. " +
        "Without bearerToken, generates a secret returned once in auth.token; never expose it in ordinary messages. " +
        "For recoverable composite setup, generate/store the secret privately and supply bearerToken and a stable hookId. " +
        "An exact repeated create with the same supplied credential returns created=false; mismatches are conflicts. " +
        "Requires server hook-owner allowlist and configured public base URL. Only status=succeeded confirms completion; inspect partial outcomes before retrying.",
    "get" to "Read an owned hook's prompt, public URL, enabled state and revision. Never returns credentials.",
    "list" to "List this owner's workspace hooks, including disabled hooks and their revisions. Never returns credentials.",
    "update" to "Change an owned hook using expectedRevision from get/list. Omitted fields remain unchanged. " +
        "May change prompt, enabled or bearerToken; credentials are never returned. Pending events with changed prompts are cancelled; running agents remain.",
    "delete" to "Disable and remove an owned hook using expectedRevision from get/list. Missing is idempotent deleted=false. " +
        "Receipts/history and already-running executions remain. For device scenarios disable the hook, remove the device rule, then delete the hook. " +
        "A partial result requires inspection, not a blind replacement create.",
)

private fun parameters(operation: String): LLMRequest.Parameters {
    val properties = linkedMapOf<String, LLMRequest.Property>()
    if (operation != "list") properties["hookId"] = LLMRequest.Property("string",
        "Owned hook ID; optional for create. For scenarios use a stable hook-<UUID> ID also accepted as the device rule ID.")
    if (operation == "create" || operation == "update") {
        properties["prompt"] = LLMRequest.Property("string", "Self-contained instruction, 1..16384 characters, including condition, permitted action and exact reply channel.")
        properties["bearerToken"] = LLMRequest.Property("string", "Optional private Bearer credential, 32..512 printable ASCII characters without whitespace. Persist only its hash in hook YAML.")
        properties["enabled"] = LLMRequest.Property("boolean", "Whether new events may run. Default true on create.")
    }
    if (operation == "update" || operation == "delete") properties["expectedRevision"] =
        LLMRequest.Property("string", "Exact revision from get/list; prevents overwriting a changed definition.")
    val required = when (operation) {
        "create" -> listOf("prompt")
        "get" -> listOf("hookId")
        "list" -> emptyList()
        else -> listOf("hookId", "expectedRevision")
    }
    return LLMRequest.Parameters("object", properties, required, additionalProperties = false)
}
