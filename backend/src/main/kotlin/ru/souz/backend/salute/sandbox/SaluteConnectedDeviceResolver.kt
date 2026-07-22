package ru.souz.backend.salute.sandbox

import ru.souz.backend.salute.SaluteDeviceConnectionRegistry

sealed interface SaluteDeviceResolution {
    data class Resolved(val deviceId: String) : SaluteDeviceResolution
    data object NotASaluteUser : SaluteDeviceResolution
    data class NotConnected(val boundDeviceIds: Set<String>) : SaluteDeviceResolution
    data class Ambiguous(val connectedDeviceIds: Set<String>) : SaluteDeviceResolution
}

/**
 * Synchronous userId -> Salute device resolution, backed entirely by in-memory connection
 * registry state (no DB call on the tool-call hot path — see [SaluteDeviceConnectionRegistry]'s
 * `userIdByDeviceId` doc for why this is safe/fresh enough).
 */
interface SaluteConnectedDeviceResolver {
    fun resolveForUser(userId: String): SaluteDeviceResolution
    fun isConnected(deviceId: String): Boolean
}

class RegistryBackedSaluteConnectedDeviceResolver(
    private val registry: SaluteDeviceConnectionRegistry,
) : SaluteConnectedDeviceResolver {
    override fun resolveForUser(userId: String): SaluteDeviceResolution {
        val bound = registry.boundDeviceIdsForUser(userId)
        if (bound.isEmpty()) return SaluteDeviceResolution.NotASaluteUser
        val connected = bound.filter(registry::isConnected).toSet()
        return when (connected.size) {
            0 -> SaluteDeviceResolution.NotConnected(bound)
            1 -> SaluteDeviceResolution.Resolved(connected.single())
            else -> SaluteDeviceResolution.Ambiguous(connected)
        }
    }

    override fun isConnected(deviceId: String): Boolean = registry.isConnected(deviceId)
}
