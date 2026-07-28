package ru.souz.backend.salute.sandbox

import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SkillCommandSandboxAttributes
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.BadInputException

/**
 * Decorates the shared Local/Docker resolver with Salute routing, but is wired ONLY into the
 * `RunSkillCommand` tool binding — never into the shared [ToolInvocationRuntimeSandboxResolver]
 * singleton, which is also used by general-purpose file tools and skill bundle storage that must
 * never be routed to a device with no shared filesystem.
 *
 * The active skill's manifest declaring `runsOnDevice: true` (surfaced via
 * [SkillCommandSandboxAttributes.RUNS_ON_DEVICE]) is the ONLY signal that routes execution to a
 * Salute device — this is checked first and is absolute. In particular, a call that originates
 * from the device itself (voice channel, explicit device id attribute) does NOT by itself force
 * device routing: a skill that doesn't declare `runsOnDevice` still runs on Local/Docker even when
 * the user is talking directly to their colonka. The explicit device id only ever matters as a
 * *device selection* hint (which of possibly several connected devices to target) once a skill has
 * already opted into device execution.
 */
class BackendSaluteAwareToolInvocationRuntimeSandboxResolver(
    private val fallback: ToolInvocationRuntimeSandboxResolver,
    private val deviceResolver: SaluteConnectedDeviceResolver,
    private val saluteSandboxes: SaluteRuntimeSandboxProvider,
) : ToolInvocationRuntimeSandboxResolver {
    override fun resolve(meta: ToolInvocationMeta): RuntimeSandbox {
        val skillRunsOnDevice = meta.attributes[SkillCommandSandboxAttributes.RUNS_ON_DEVICE] == "true"
        if (!skillRunsOnDevice) {
            return fallback.resolve(meta)
        }
        val userId = meta.userId.trim()
        val explicitDeviceId = meta.attributes[SaluteToolAttributes.DEVICE_ID]?.trim()?.takeIf(String::isNotEmpty)
        if (explicitDeviceId != null) {
            if (!deviceResolver.isConnected(explicitDeviceId)) {
                throw BadInputException("Salute device $explicitDeviceId is not connected.")
            }
            return saluteSandboxes.get(userId, explicitDeviceId)
        }
        return when (val resolution = deviceResolver.resolveForUser(userId)) {
            is SaluteDeviceResolution.Resolved -> saluteSandboxes.get(userId, resolution.deviceId)
            SaluteDeviceResolution.NotASaluteUser -> fallback.resolve(meta)
            is SaluteDeviceResolution.NotConnected -> throw BadInputException(
                "Salute device(s) ${resolution.boundDeviceIds.joinToString()} bound to user $userId, " +
                    "none currently connected."
            )
            is SaluteDeviceResolution.Ambiguous -> throw BadInputException(
                "Multiple Salute devices connected for user $userId: " +
                    "${resolution.connectedDeviceIds.joinToString()}. Specify which device to target."
            )
        }
    }
}
