package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import ru.souz.backend.hooks.HookConfig
import ru.souz.db.SettingsProvider
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxModeResolver

class BackendHookManagementE2eTest {
    @TempDir lateinit var workspace: Path
    private val owner = UUID.randomUUID().toString()
    private val other = UUID.randomUUID().toString()
    private val token = "credential-" + "x".repeat(48)

    @Test
    fun `core tools create reload execute and delete owned hooks without returning supplied credentials`() {
        backendE2eTest("e2e_hook_management", hookConfig = HookConfig(setOf(owner, other), publicBaseUrl = "https://hooks.test/souz"),
            sandboxFactory = ::localSandbox) {
            val chat = scheduledTaskSetup(owner)
            val foreign = scheduledTaskSetup(other)
            val id = "hook-${UUID.randomUUID()}"
            val input = mapOf("hookId" to id, "prompt" to "Observe this event.", "bearerToken" to token, "enabled" to false)
            val created = invokeCompiledSkill(owner, chat, "hook.create", input)
            assertEquals("succeeded", created["status"].asText(), created.toString())
            assertEquals("https://hooks.test/souz/hooks/$id", created["url"].asText())
            assertEquals("files", created["request"]["multipart"]["fileField"].asText())
            assertFalse(token in created.toString())
            val file = workspace.resolve("hooks/$id/hook.yaml")
            assertFalse(token in Files.readString(file))
            assertEquals(false, invokeCompiledSkill(owner, chat, "hook.create", input)["created"].asBoolean())
            assertEquals("failed", invokeCompiledSkill(other, foreign, "hook.get", mapOf("hookId" to id))["status"].asText())
            val listed = invokeCompiledSkill(owner, chat, "hook.list")
            assertEquals(listOf(id), listed["hooks"].map { it["hookId"].asText() })
            assertFalse(token in listed.toString())
            val bad = invokeCompiledSkill(owner, chat, "hook.update", mapOf("hookId" to id, "expectedRevision" to "0".repeat(64), "enabled" to true))
            assertEquals("hook_revision_conflict", bad["error"]["code"].asText())
            val enabled = invokeCompiledSkill(owner, chat, "hook.update", mapOf("hookId" to id, "expectedRevision" to created["revision"].asText(), "enabled" to true))
            assertEquals("succeeded", enabled["status"].asText())
            assertNotEquals(created["revision"], enabled["revision"])
            val accepted = client.post("/hooks/$id") {
                header("Authorization", "Bearer $token")
                header("Content-Type", "application/json")
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Accepted, accepted.status)
            val receipt = accepted.jsonBody()["receiptId"].asText()
            eventually("managed hook completion") {
                client.get("/v1/hooks/receipts/$receipt") { trusted(owner) }.jsonBody()
                    .takeIf { it["status"].asText() == "completed" }
            }
            val deleted = invokeCompiledSkill(owner, chat, "hook.delete", mapOf("hookId" to id, "expectedRevision" to enabled["revision"].asText()))
            assertTrue(deleted["deleted"].asBoolean(), deleted.toString())
            assertFalse(Files.exists(file))
            assertEquals(HttpStatusCode.NotFound, client.post("/hooks/$id") { header("Authorization", "Bearer $token"); setBody("{}") }.status)
            assertEquals(HttpStatusCode.OK, client.get("/v1/hooks/receipts/$receipt") { trusted(owner) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/v1/hooks/receipts/$receipt") { trusted(other) }.status)
        }
    }

    @Test
    fun `generated token is returned only by create and owner allowlist is enforced before writes`() {
        backendE2eTest("e2e_hook_secret", hookConfig = HookConfig(setOf(owner), publicBaseUrl = "https://hooks.test"),
            sandboxFactory = ::localSandbox) {
            val chat = scheduledTaskSetup(owner)
            val foreign = scheduledTaskSetup(other)
            val created = invokeCompiledSkill(owner, chat, "hook.create", mapOf("prompt" to "Handle event.", "enabled" to false))
            val secret = created["auth"]["token"].asText()
            assertTrue(secret.length >= 32)
            val id = created["hookId"].asText()
            assertFalse(secret in invokeCompiledSkill(owner, chat, "hook.get", mapOf("hookId" to id)).toString())
            assertFalse(secret in invokeCompiledSkill(owner, chat, "hook.list").toString())
            val denied = invokeCompiledSkill(other, foreign, "hook.create", mapOf("prompt" to "No."))
            assertEquals("hooks_not_enabled_for_user", denied["error"]["code"].asText())
            val unsafe = invokeCompiledSkill(owner, chat, "hook.create", mapOf("hookId" to "../outside", "prompt" to "No."))
            assertEquals("invalid_arguments", unsafe["error"]["code"].asText())
        }
    }

    private fun localSandbox(settings: SettingsProvider) = DefaultRuntimeSandboxFactory(settings,
        RuntimeSandboxModeResolver { "local" }, workspace, workspace.resolve("state"), workspace)
}
