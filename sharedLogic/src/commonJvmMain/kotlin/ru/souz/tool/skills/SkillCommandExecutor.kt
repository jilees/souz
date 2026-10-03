package ru.souz.tool.skills

import java.nio.file.Path
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.SandboxMode
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.BadInputException
import ru.souz.tool.InputParamDescription

/**
 * Env var whose value is an allowlist (comma / whitespace separated) of host env names to
 * forward into DOCKER-mode skill commands. LOCAL skills are `ProcessBuilder` children and
 * already inherit the backend's full environment, so this is a no-op there. Keep the list
 * narrow — LLM keys and their base URLs, not "everything" — it exists to hand a skill's own
 * internal loop the couple of secrets it needs, not to re-open the LOCAL-mode firehose.
 */
const val SANDBOX_FORWARD_ENV_SPEC = "SOUZ_SANDBOX_FORWARD_ENV"

/**
 * Resolves [SANDBOX_FORWARD_ENV_SPEC] against [hostEnv] into the `{name -> value}` pairs to
 * forward. Names unset or blank in [hostEnv] are dropped; the spec var never forwards itself.
 */
fun resolveForwardedSandboxEnv(hostEnv: Map<String, String>): Map<String, String> =
    hostEnv[SANDBOX_FORWARD_ENV_SPEC]
        ?.split(',', ' ', '\t', '\n')
        ?.map(String::trim)
        ?.filter { it.isNotEmpty() && it != SANDBOX_FORWARD_ENV_SPEC }
        ?.distinct()
        ?.mapNotNull { name -> hostEnv[name]?.takeIf(String::isNotBlank)?.let { name to it } }
        ?.toMap()
        .orEmpty()

class SkillCommandExecutor(
    private val sandboxResolver: ToolInvocationRuntimeSandboxResolver,
    /**
     * Host env pairs to forward into DOCKER-mode skill commands, from
     * [resolveForwardedSandboxEnv]. Lowest precedence — never shadows a fixed `SOUZ_SKILL_*`
     * var or a caller-supplied [Args.environment] value.
     */
    private val forwardedSandboxEnv: Map<String, String> = emptyMap(),
) {
    internal data class Args(
        @InputParamDescription("Runtime to execute: BASH, PYTHON, NODE, or PROCESS. Use BASH for shell scripts and PROCESS for argv commands.")
        val runtime: SandboxCommandRuntime = SandboxCommandRuntime.BASH,
        @InputParamDescription("Required argv for PROCESS runtime, for example [\"bash\", \"scripts/run.sh\"]. PROCESS ignores scriptPath and args. Leave empty for BASH/PYTHON/NODE.")
        val command: List<String> = emptyList(),
        @InputParamDescription("Inline script for BASH/PYTHON/NODE runtimes. For bundled scripts, call them by relative path, for example: bash scripts/run.sh")
        val script: String? = null,
        @InputParamDescription("Path to a bundled script inside the active Skill root for BASH/PYTHON/NODE. Prefer this over inline script when running supporting scripts.")
        val scriptPath: String? = null,
        @InputParamDescription("Arguments to pass to scriptPath, or to the inline script process as positional arguments.")
        val args: List<String> = emptyList(),
        @InputParamDescription("Working directory relative to the selected skill bundle root. Defaults to the skill bundle root.")
        val workingDirectory: String? = null,
        @InputParamDescription("Environment variables to pass to the process.")
        val environment: Map<String, String> = emptyMap(),
        @InputParamDescription("Optional stdin passed to the command.")
        val stdin: String? = null,
        @InputParamDescription("Timeout in milliseconds. Defaults to 60000 and is capped at 300000.")
        val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        @InputParamDescription("Name of a command declared in this Skill's manifest. Runs its steps instead of scriptPath/script/command.")
        val composite: String? = null,
        @InputParamDescription("Named inputs declared by the composite command. JSON-encoded values are decoded; other values remain text.")
        val inputs: Map<String, String> = emptyMap(),
    )

    internal companion object {
        const val BUNDLES_DIRECTORY_NAME = "bundles"
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        const val MAX_TIMEOUT_MILLIS = 300_000L
        const val SKILL_MARKDOWN_PATH = "SKILL.md"
    }

    internal suspend fun execute(
        bundle: SkillBundle,
        bundleHash: String,
        arguments: Args,
        meta: ToolInvocationMeta,
    ): SandboxCommandResult {
        val sandbox = sandboxResolver.resolve(meta)
        val skillRoot = resolveSkillRoot(sandbox, bundle.skillId, bundleHash)
        val workingDirectory = resolveWorkingDirectory(skillRoot, arguments.workingDirectory)
        val scriptPath = arguments.scriptPath.takeUnless { arguments.runtime == SandboxCommandRuntime.PROCESS }
        // LOCAL skills are ProcessBuilder children and already inherit the backend's full env;
        // DOCKER skills see only what `docker exec` is told, so hand them the narrow forwarded
        // allowlist as the lowest precedence layer.
        val forwardedEnvironment =
            if (sandbox.mode == SandboxMode.DOCKER) forwardedSandboxEnv else emptyMap()
        return sandbox.commandExecutor.execute(
            SandboxCommandRequest(
                runtime = arguments.runtime,
                command = arguments.command,
                script = arguments.script,
                scriptPath = resolveScriptPath(sandbox, skillRoot, scriptPath),
                args = arguments.args,
                workingDirectory = workingDirectory,
                environment = forwardedEnvironment + mapOf(
                    "SOUZ_SKILL_ID" to bundle.skillId.value,
                    "SOUZ_SKILL_ROOT" to skillRoot,
                    "SOUZ_SKILL_SUPPORTING_FILES" to bundle.files
                        .asSequence()
                        .map { it.normalizedPath }
                        .filterNot { it == SKILL_MARKDOWN_PATH }
                        .joinToString(","),
                ) + arguments.environment,
                stdin = arguments.stdin,
                timeoutMillis = arguments.timeoutMillis.coerceIn(1L, MAX_TIMEOUT_MILLIS),
            )
        )
    }

    private fun resolveSkillRoot(
        sandbox: RuntimeSandbox,
        skillId: SkillId,
        bundleHash: String,
    ): String {
        val fileSystem = sandbox.fileSystem
        val bundleRoot = skillRootPath(sandbox.runtimePaths.skillsDirPath, skillId)
            .resolve(BUNDLES_DIRECTORY_NAME)
            .resolve(bundleHash)
        val storedBundle = fileSystem.resolvePath(bundleRoot.toString())
        if (storedBundle.exists && storedBundle.isDirectory) {
            return fileSystem.resolveExistingDirectory(storedBundle.path).path
        }

        val looseRoot = skillRootPath(sandbox.runtimePaths.skillsDirPath, skillId)
        val looseBundle = fileSystem.resolvePath(looseRoot.toString())
        if (looseBundle.exists && looseBundle.isDirectory) {
            return fileSystem.resolveExistingDirectory(looseBundle.path).path
        }

        throw BadInputException("Skill bundle root is unavailable for active skill: ${skillId.value}")
    }

    private fun resolveWorkingDirectory(skillRoot: String, rawWorkingDirectory: String?): String {
        val skillRootPath = Path.of(skillRoot).normalize()
        val relative = rawWorkingDirectory
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: "."
        val workingDirectory = skillRootPath.resolve(relative).normalize()
        if (!workingDirectory.startsWith(skillRootPath)) {
            throw BadInputException("workingDirectory must stay inside the selected skill bundle root.")
        }
        return workingDirectory.toString()
    }

    private fun resolveScriptPath(
        sandbox: RuntimeSandbox,
        skillRoot: String,
        rawScriptPath: String?,
    ): String? {
        val scriptPath = rawScriptPath
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        val skillRootPath = Path.of(skillRoot).normalize()
        val resolved = skillRootPath.resolve(scriptPath).normalize()
        if (!resolved.startsWith(skillRootPath)) {
            throw BadInputException("scriptPath must stay inside the selected skill bundle root.")
        }
        return sandbox.fileSystem.resolveExistingFile(resolved.toString()).path
    }

    private fun skillRootPath(
        skillsRootPath: String,
        skillId: SkillId,
    ): Path = Path.of(skillsRootPath).resolve(skillId.value)
}
