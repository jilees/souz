package ru.souz.runtime.sandbox

/**
 * Well-known [ru.souz.llms.ToolInvocationMeta.attributes] key set by `RunSkillCommand` (`:sharedLogic`)
 * from the active skill's manifest, and read by device-aware [ToolInvocationRuntimeSandboxResolver]
 * decorators (e.g. the Salute one in `:backend`) to decide whether to route execution to the user's
 * physical device instead of the default Local/Docker sandbox.
 */
object SkillCommandSandboxAttributes {
    const val RUNS_ON_DEVICE = "skillRunsOnDevice"
}
