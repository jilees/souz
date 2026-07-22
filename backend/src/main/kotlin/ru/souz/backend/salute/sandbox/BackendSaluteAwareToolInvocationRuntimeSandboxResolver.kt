package ru.souz.backend.salute.sandbox

import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.BadInputException

/**
 * Decorates the shared Local/Docker resolver with Salute routing, but is wired ONLY into the
 * `RunSkillCommand` tool binding — never into the shared [ToolInvocationRuntimeSandboxResolver]
 * singleton, which is also used by general-purpose file tools and skill bundle storage that must
 * never be routed to a device with no shared filesystem.
 */
class BackendSaluteAwareToolInvocationRuntimeSandboxResolver(
    private val fallback: ToolInvocationRuntimeSandboxResolver,
    private val deviceResolver: SaluteConnectedDeviceResolver,
    private val saluteSandboxes: SaluteRuntimeSandboxProvider,
) : ToolInvocationRuntimeSandboxResolver {
    override fun resolve(meta: ToolInvocationMeta): RuntimeSandbox {
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
