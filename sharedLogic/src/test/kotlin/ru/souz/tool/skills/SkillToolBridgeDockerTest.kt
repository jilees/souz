package ru.souz.tool.skills

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.docker.DockerRuntimeSandbox
import ru.souz.runtime.sandbox.docker.DockerSandboxIds
import ru.souz.tool.ToolCategory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises [SkillToolBridgeServer] through the actual Docker sandbox — the path most likely to
 * hide a subtle bug, since the bridge socket is bound on the host but must be reachable at a
 * different (container-space) path from inside the sandboxed process. See
 * `DockerRuntimeSandboxIntegrationTest` for the equivalent non-bridge Docker sandbox coverage.
 */
class SkillToolBridgeDockerTest {
    private val createdPaths = mutableListOf<Path>()
    private val sandboxes = mutableListOf<DockerRuntimeSandbox>()
    private val containerNames = mutableSetOf<String>()

    @BeforeEach
    fun checkEnv() {
        assumeTrue(System.getenv("SOUZ_TEST_DOCKER") == "1", "Run with SOUZ_TEST_DOCKER=1 to enable Docker integration tests.")
    }

    @AfterTest
    fun cleanup() {
        sandboxes.asReversed().forEach { sandbox -> runCatching { sandbox.close() } }
        sandboxes.clear()
        containerNames.forEach { name -> runCatching { docker("rm", "-f", name) } }
        containerNames.clear()
        createdPaths.asReversed().forEach { path -> runCatching { path.toFile().deleteRecursively() } }
        createdPaths.clear()
    }

    @Test
    fun `bridge round-trips a tool call through the container's bind-mounted socket`() = runTest {
        ensureDockerImage()
        val containerName = DockerSandboxIds.defaultContainerName(
            SandboxScope(userId = "docker-bridge-user", conversationId = "case-${System.nanoTime()}"),
        )
        containerNames += containerName
        val sandbox = DockerRuntimeSandbox(
            scope = SandboxScope(userId = "docker-bridge-user", conversationId = "case-${System.nanoTime()}"),
            hostRoot = createTempDirectory("docker-bridge-sandbox-"),
            imageName = TEST_IMAGE_NAME,
            containerName = containerName,
            removeContainerOnClose = true,
        )
        sandboxes += sandbox
        sandbox.fileSystem.createDirectory(sandbox.fileSystem.resolvePath("${sandbox.runtimePaths.skillsDirPath}/bridge-skill"))

        val target = RecordingTool("target-tool")
        val catalog = object : AgentToolCatalog {
            override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> =
                mapOf(ToolCategory.FILES to mapOf("target-tool" to target))
        }
        val filter = object : AgentToolsFilter {
            override fun applyFilter(
                toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
            ): Map<ToolCategory, Map<String, LLMToolSetup>> = toolsByCategory
        }
        val executor = SkillCommandExecutor(
            sandboxResolver = ToolInvocationRuntimeSandboxResolver.fixed(sandbox),
            toolCatalog = catalog,
            toolsFilter = filter,
        )
        val skillBundle = SkillBundle.fromFiles(
            skillId = SkillId("bridge-skill"),
            files = listOf(
                SkillFile(
                    normalizedPath = "SKILL.md",
                    content = """
                        ---
                        name: bridge-skill
                        description: Docker bridge test skill.
                        metadata:
                          "souz.bridge-tools": "target-tool"
                        ---
                        Body.
                    """.trimIndent().toByteArray(),
                )
            ),
        )
        val meta = ToolInvocationMeta(userId = "docker-bridge-user")

        val result = executor.execute(
            bundle = skillBundle,
            bundleHash = SkillBundleHasher.hash(skillBundle),
            arguments = SkillCommandExecutor.Args(
                runtime = SandboxCommandRuntime.PYTHON,
                script = bridgeCallScript("target-tool", """{"value": 9}"""),
                timeoutMillis = 15_000,
            ),
            meta = meta,
        )

        assertEquals(0, result.exitCode, "stdout=${result.stdout} stderr=${result.stderr}")
        assertEquals("delegated-content", result.stdout.trim())
        assertEquals(mapOf("value" to 9), target.lastArguments)
        assertEquals(meta, target.lastMeta)
    }

    private class RecordingTool(name: String) : LLMToolSetup {
        override val fn = LLMRequest.Function(
            name = name,
            description = "description for $name",
            parameters = LLMRequest.Parameters(type = "object"),
        )
        var lastArguments: Map<String, Any>? = null
        var lastMeta: ToolInvocationMeta? = null

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            invoke(functionCall, ToolInvocationMeta.localDefault())

        override suspend fun invoke(
            functionCall: LLMResponse.FunctionCall,
            meta: ToolInvocationMeta,
        ): LLMRequest.Message {
            lastArguments = functionCall.arguments
            lastMeta = meta
            return LLMRequest.Message(role = LLMMessageRole.function, content = "delegated-content", name = functionCall.name)
        }
    }

    // Colima/Docker Desktop file-sharing in this environment only exposes $HOME into the VM (not
    // the OS tmp dir java.io.tmpdir resolves to) — bind-mount source paths outside it are rejected
    // by the daemon before the container ever starts.
    private fun createTempDirectory(prefix: String): Path =
        Files.createTempDirectory(Path.of(System.getProperty("user.home")), ".$prefix").also(createdPaths::add)

    private fun ensureDockerImage() {
        val inspect = docker(
            "image", "inspect", "--format",
            "{{ index .Config.Labels \"ru.souz.runtime-sandbox.fixture\" }}",
            TEST_IMAGE_NAME,
        )
        if (inspect.exitCode == 0 && inspect.stdout.trim() == TEST_IMAGE_LABEL) return
        val contextDir = repositoryRoot().resolve("sharedLogic")
        val build = docker("build", "-t", TEST_IMAGE_NAME, contextDir.toString())
        check(build.exitCode == 0) {
            "Failed to build Docker sandbox test image.\nstdout:\n${build.stdout}\nstderr:\n${build.stderr}"
        }
    }

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Failed to locate repository root from ${System.getProperty("user.dir")}")
        }
        return current
    }

    private fun docker(vararg args: String): DockerTestProcessResult {
        val process = ProcessBuilder(listOf("docker") + args)
            .directory(Path.of(".").toFile())
            .redirectErrorStream(false)
            .start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutReader = thread(start = true, name = "docker-bridge-test-stdout") {
            process.inputStream.bufferedReader().use { reader -> stdout.append(reader.readText()) }
        }
        val stderrReader = thread(start = true, name = "docker-bridge-test-stderr") {
            process.errorStream.bufferedReader().use { reader -> stderr.append(reader.readText()) }
        }
        val exitCode = process.waitFor()
        stdoutReader.join()
        stderrReader.join()
        return DockerTestProcessResult(exitCode, stdout.toString(), stderr.toString())
    }

    private data class DockerTestProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

    private companion object {
        const val TEST_IMAGE_NAME = "souz-runtime-sandbox:test"
        const val TEST_IMAGE_LABEL = "paper-summarize-academic"
    }
}
