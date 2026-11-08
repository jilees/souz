package ru.souz.backend.hooks

import com.fasterxml.jackson.databind.JsonNode
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper

/** Generic owner-scoped hook management; device/scenario orchestration belongs to Skills. */
internal class HookManagement(private val files: HookWorkspaceFiles, private val config: HookConfig) {
    private val random = SecureRandom()

    suspend fun invoke(operation: String, arguments: Map<String, Any>, meta: ToolInvocationMeta): Map<String, Any?> {
        var changed = false
        var id: String? = null
        return try {
            hookFileCheck(meta.userId in config.owners, "hooks_not_enabled_for_user")
            val input = parse(operation, arguments)
            id = input.id
            if (operation == "list") {
                return mapOf("status" to "succeeded", "hooks" to files.read(meta.userId).values.map(::view))
            }
            files.mutate(meta.userId) { workspace ->
                if (operation == "create") {
                    hookFileCheck(config.publicBaseUrl != null, "hook_public_url_not_configured")
                    val generated = input.token == null
                    val token = input.token ?: Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(ByteArray(32).also(random::nextBytes))
                    val hookId = input.id ?: "hook-${UUID.randomUUID()}"
                    id = hookId
                    val definition = HookDefinition(1, hookId, meta.userId, enabled = input.enabled ?: true,
                        auth = HookAuth("bearer", sha256(token.toByteArray())), prompt = input.prompt!!)
                    val existing = workspace.find(hookId)
                    if (existing != null) {
                        // A caller-supplied secret allows recovery of a lost create response without rotation.
                        hookFileCheck(!generated && existing.definition == definition, "hook_conflict")
                        return@mutate view(existing) + mapOf("status" to "succeeded", "created" to false,
                            "auth" to mapOf("type" to "bearer", "credentialProvided" to true), "request" to requestContract)
                    }
                    val created = workspace.create(definition) { changed = true }
                    view(created) + mapOf("status" to "succeeded", "created" to true,
                        "auth" to if (generated) mapOf("type" to "bearer", "token" to token, "returnedOnce" to true)
                        else mapOf("type" to "bearer", "credentialProvided" to true),
                        "request" to requestContract)
                } else {
                    val found = workspace.find(input.id!!)
                    if (operation == "get") {
                        hookFileCheck(found != null, "hook_not_found")
                        return@mutate view(found!!) + mapOf("status" to "succeeded", "prompt" to found.definition.prompt)
                    }
                    if (found == null && operation == "delete") {
                        return@mutate mapOf("status" to "succeeded", "hookId" to input.id, "deleted" to false)
                    }
                    var current = found ?: throw HookFileFailure("hook_not_found")
                    hookFileCheck(input.revision == revision(current), "hook_revision_conflict")
                    if (operation == "update") {
                        val definition = current.definition
                        hookFileCheck(input.token == null || definition.auth != null, "hook_verifier_auth_conflict")
                        val replacement = definition.copy(prompt = input.prompt ?: definition.prompt,
                            enabled = input.enabled ?: definition.enabled,
                            auth = input.token?.let { HookAuth("bearer", sha256(it.toByteArray())) } ?: definition.auth)
                        if (replacement != definition) { changed = true; current = workspace.replace(current, replacement) }
                        view(current) + mapOf("status" to "succeeded", "changed" to changed)
                    } else {
                        // Disable intake and pending dispatch before deleting the definition. Running agents remain.
                        if (current.definition.enabled) {
                            changed = true
                            current = workspace.replace(current, current.definition.copy(enabled = false))
                        }
                        changed = true
                        workspace.remove(current)
                        mapOf("status" to "succeeded", "hookId" to input.id, "deleted" to true)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // YAML, parser and remote errors can contain credentials. Never return their messages.
            mapOf("status" to if (changed) "partial" else "failed", "hookId" to id,
                "error" to mapOf("code" to if (error is HookFileFailure) error.code else "hook_operation_failed"))
        }
    }

    private fun view(hook: LoadedHook): Map<String, Any?> = mapOf(
        "hookId" to hook.definition.hookId, "enabled" to hook.definition.enabled,
        "revision" to revision(hook), "url" to config.publicBaseUrl?.let { "$it/hooks/${hook.definition.hookId}" },
        "authType" to if (hook.definition.auth != null) "bearer" else "verifier",
    )

    private fun parse(operation: String, arguments: Map<String, Any>): Input {
        val node = restJsonMapper.valueToTree<JsonNode>(arguments)
        val allowed = when (operation) {
            "create" -> setOf("hookId", "prompt", "bearerToken", "enabled")
            "update" -> setOf("hookId", "expectedRevision", "prompt", "bearerToken", "enabled")
            "get" -> setOf("hookId")
            "delete" -> setOf("hookId", "expectedRevision")
            "list" -> emptySet()
            else -> throw HookFileFailure("invalid_arguments")
        }
        hookFileCheck(node.fieldNames().asSequence().all { it in allowed } && node.none { it.isNull })
        fun text(key: String) = node[key]?.let { hookFileCheck(it.isTextual); it.textValue() }
        val id = text("hookId")?.also { hookFileCheck(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }
        hookFileCheck(operation == "create" || operation == "list" || id != null)
        val prompt = text("prompt")?.also { hookFileCheck(it.isNotBlank() && it.length <= 16_384) }
        val token = text("bearerToken")?.also { value ->
            hookFileCheck(value.length in 32..512 && value.all { it.code in 33..126 })
        }
        val revision = text("expectedRevision")?.also { hookFileCheck(it.matches(Regex("[a-f0-9]{64}"))) }
        hookFileCheck((operation != "update" && operation != "delete") || revision != null)
        hookFileCheck(!node.has("enabled") || node["enabled"].isBoolean)
        hookFileCheck(operation != "create" || prompt != null)
        hookFileCheck(operation != "update" || prompt != null || token != null || node.has("enabled"))
        return Input(id, prompt, token, node["enabled"]?.booleanValue(), revision)
    }

    private data class Input(val id: String?, val prompt: String?, val token: String?, val enabled: Boolean?, val revision: String?)

    private fun revision(hook: LoadedHook): String = sha256(
        (restJsonMapper.writeValueAsString(hook.definition) + "\n" + hook.revision).toByteArray(),
    )

    private val requestContract = mapOf(
        "method" to "POST", "authorization" to "Bearer <token>", "idempotencyHeader" to "Idempotency-Key",
        "json" to mapOf("contentType" to "application/json", "maxBytes" to 65_536),
        "multipart" to mapOf("contentType" to "multipart/form-data", "payloadField" to "payload",
            "fileField" to "files", "maxFiles" to 5, "maxBodyBytes" to 10_000_000),
        "acceptedStatus" to 202, "duplicateStatus" to 200, "execution" to "asynchronous receiptId",
    )
}
