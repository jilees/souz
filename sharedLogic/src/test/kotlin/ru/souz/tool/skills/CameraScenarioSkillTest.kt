package ru.souz.tool.skills

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir
import ru.souz.agent.skills.bundle.SkillBundleParser

class CameraScenarioSkillTest {
    @TempDir lateinit var state: Path
    private val root = Path.of("docker/skills/camera-scenarios")

    @Test
    fun `camera commands parse and install credentials only through transient steps`() {
        val manifest = SkillBundleParser.parseManifest(Files.readString(root.resolve("SKILL.md")))
        assertEquals(setOf("inspect", "create", "activate", "delete"), manifest.commands.keys)
        for (name in listOf("create", "activate")) {
            val setter = manifest.commands.getValue(name).steps.single { it.tool == "device.mcp.call_tool" && it.arguments["name"] == "camera_detector_rule_set" }
            assertTrue(setter.transientClientCall)
        }
    }

    @Test
    fun `private credentials survive concurrent prepare and partial failure but are absent from listings`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val id = "cam-${UUID.randomUUID()}"
        val args = arrayOf("prepare", id, "channel", "", "camera", """{"classes":["cat"]}""", "30000", "Notify owner.")
        val first = process(*args)
        val second = process(*args)
        val token = success(first)
        assertTrue(token == success(second), "Concurrent retries must retain the same private credential")
        assertTrue(token.length >= 32)
        assertFalse(token in success(process("list")))
        val error = process("record", id, "enabled", """{"status":"succeeded","result":{"isError":true,"content":[{"type":"text","text":"private value"}]}}""")
        assertTrue(error.waitFor(5, TimeUnit.SECONDS))
        assertEquals(1, error.exitValue())
        assertFalse(token in error.errorStream.bufferedReader().readText())
        assertTrue(token == success(process("token", id)))
        assertEquals("", success(process("forget", id)))
        assertEquals("[]", success(process("list")).trim())
    }

    private fun process(vararg args: String): Process = ProcessBuilder(listOf("python3", root.resolve("scripts/state.py").toString()) + args)
        .apply { environment()["SOUZ_SKILL_STATE_DIR"] = state.toString() }.start()

    private fun success(process: Process): String {
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.exitValue(), process.errorStream.bufferedReader().readText())
        return output
    }
}
