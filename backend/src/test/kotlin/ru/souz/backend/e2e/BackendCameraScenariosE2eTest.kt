package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.client.request.patch
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import ru.souz.backend.hooks.HookConfig
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.db.SettingsProvider
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxModeResolver

class BackendCameraScenariosE2eTest {
    @TempDir lateinit var workspace: Path

    @Test
    fun `bundled composites create activate and delete a scenario without persisting source-channel credentials`() {
        for (name in listOf("SKILL.md", "scripts/state.py")) {
            val file = workspace.resolve("state/skills/camera-scenarios/$name")
            Files.createDirectories(file.parent)
            val resource = javaClass.classLoader.getResourceAsStream("skills/runtime/camera-scenarios/$name")!!
            resource.use { Files.copy(it, file) }
        }
        val owner = UUID.randomUUID().toString()
        val scenario = "cam-${UUID.randomUUID()}"
        val llm = E2eLlmApi()
        backendE2eTest("e2e_camera_scenario", llm = llm,
            hookConfig = HookConfig(setOf(owner), publicBaseUrl = "https://hooks.test/souz"),
            sandboxFactory = ::localSandbox) {
            val chat = createPublicChat(owner, tools = clientToolsJson("device.mcp.call_tool"))
            client.patch(BackendHttpRoutes.SETTINGS) { trusted(owner)
                jsonBody("""{"defaultModel":"${E2E_MODEL.alias}","streamingMessages":false}""") }
            val base = mapOf("scenarioId" to scenario, "channelId" to chat, "target" to "", "cameraId" to "camera-1",
                "condition" to """{"classes":["cat"],"min_confidence":0.5,"dwell_ms":1000}""",
                "cooldownMs" to "30000", "prompt" to "Check this image and notify the owner only when the requested cat condition holds.")
            var revision = 0
            var rule: ObjectNode? = null
            var secret: String? = null
            var calls = 0
            var rejectFirstDelete = true
            withPublicSocket(chat) { socket ->
                suspend fun command(command: String, inputs: Map<String, String>, success: Boolean = true): JsonNode {
                    val prompt = "camera-$command-${UUID.randomUUID()}"
                    llm.requestSkillForPrompt(prompt, "camera-scenarios", mapOf("composite" to command, "inputs" to inputs))
                    socket.send(Frame.Text(messageFrame(chat, owner, prompt, text = prompt)))
                    var execution: String? = null
                    withTimeout(20.seconds) {
                        while (true) {
                            val frame = readJson(socket)
                            if (frame.path("kind").asText() == "ack" && frame.has("thread")) execution = frame["thread"]["id"].asText()
                            if (frame.path("type").asText() == "thread.completed") break
                            if (frame.path("type").asText() != "tool.call.started") continue
                            calls++
                            val args = frame["payload"]["arguments"]
                            if (args["name"].asText() == "camera_detector_rule_set") {
                                assertTrue(frame["seq"].isNull, frame.toString())
                                assertNotEquals(execution, frame["threadId"].asText())
                            }
                            assertEquals("device.mcp.call_tool", frame["payload"]["name"].asText())
                            val result: Map<String, Any> = when (args["name"].asText()) {
                                "camera_detector_rules_list" -> mapOf("ok" to true, "rules" to listOfNotNull(rule),
                                    "capabilities" to mapOf("runtime_state" to "ready", "camera_ids" to listOf("camera-1"), "active_classes" to listOf("cat")))
                                "camera_detector_rule_set" -> {
                                    val next = args["arguments"]["rule"].deepCopy<ObjectNode>()
                                    assertEquals(revision, args["arguments"]["expected_revision"].asInt())
                                    val received = next["webhook"]["bearer_token"].asText()
                                    if (secret == null) secret = received else assertEquals(secret, received)
                                    assertTrue(received.length >= 32)
                                    assertEquals("https://hooks.test/souz/hooks/$scenario", next["webhook"]["url"].asText())
                                    (next["webhook"] as ObjectNode).remove("bearer_token")
                                    next.put("revision", ++revision).put("application_state", if (next["enabled"].asBoolean()) "monitoring" else "disabled")
                                    rule = next
                                    mapOf("ok" to true, "rule" to next)
                                }
                                "camera_detector_rule_delete" -> {
                                    assertFalse(Files.exists(workspace.resolve("hooks/$scenario/hook.yaml")), "Hook must be removed before device cleanup")
                                    assertEquals(revision, args["arguments"]["expected_revision"].asInt())
                                    if (rejectFirstDelete) {
                                        rejectFirstDelete = false
                                        mapOf("ok" to false, "error" to "device_busy")
                                    } else {
                                        rule = null
                                        mapOf("ok" to true, "deleted" to true)
                                    }
                                }
                                else -> error("Unexpected MCP tool")
                            }
                            // A thin relay can omit structuredContent; text-only results must still work.
                            socket.send(Frame.Text(toolResultFrame(frame, "succeeded", mapOf("isError" to (result["ok"] == false),
                                "content" to listOf(mapOf("type" to "text", "text" to json.writeValueAsString(result)))))))
                        }
                    }
                    val raw = json.readTree(llm.requests.last { it.conversationPrompt() == prompt }.messages.last { it.name == "RunSkillCommand" }.content)
                    assertEquals(success, raw["exitCode"].asInt() == 0, raw.toString())
                    return if (success) json.readTree(raw["stdout"].asText()) else raw
                }
                val created = command("create", base)
                assertEquals("configured_disabled", created["state"].asText())
                assertFalse(rule!!["enabled"].asBoolean())
                val active = command("activate", mapOf("scenarioId" to scenario, "detectorRevision" to revision.toString()))
                assertTrue(rule!!["enabled"].asBoolean())
                val deleting = base.filterKeys { it == "scenarioId" || it == "channelId" || it == "target" } + mapOf(
                    "detectorRevision" to revision.toString(), "hookRevision" to active["hook"]["revision"].asText())
                val partial = command("delete", deleting, success = false)
                assertTrue("detector_rejected" in partial["stderr"].asText())
                assertTrue(rule != null, "Device cleanup is still pending")
                assertTrue(Files.walk(workspace.resolve("state/skill-data")).use { paths -> paths.anyMatch { it.fileName.toString() == "token" } })
                val deleted = command("delete", deleting + ("hookRevision" to "0".repeat(64)))
                assertEquals("deleted", deleted["state"].asText())
            }
            assertEquals(7, calls)
            assertTrue(secret != null)
            assertFalse(secret!! in json.writeValueAsString(llm.requests), "Intermediate credentials must never reach the model")
            val stored = sql { c -> c.createStatement().use { s ->
                s.executeQuery("select coalesce(string_agg(payload::text, ''), '') from agent_events").use { it.next(); it.getString(1) }
            } }
            assertFalse(secret!! in stored, "Transient source-channel arguments must not enter durable replay")
            assertFalse(Files.walk(workspace.resolve("state/skill-data")).use { paths -> paths.anyMatch { it.fileName.toString() == "token" } })
        }
    }

    private fun localSandbox(settings: SettingsProvider) = DefaultRuntimeSandboxFactory(settings,
        RuntimeSandboxModeResolver { "local" }, workspace, workspace.resolve("state"), workspace)
}
