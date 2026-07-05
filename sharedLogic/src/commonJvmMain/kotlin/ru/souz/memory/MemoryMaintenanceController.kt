package ru.souz.memory

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64

const val MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE = "dreamer_region_rewrite"
const val MEMORY_MAINTENANCE_REASON_LEGACY_CHAT_MIGRATION = "legacy_chat_fact_migration"

fun dreamerRegionMaintenanceClusterKey(ownerId: MemoryOwnerId, scope: MemoryScope): String {
    val normalized = scope.normalized()
    return listOf(
        DREAMER_REGION_CLUSTER_PREFIX,
        ownerId.value.toBase64Url(),
        normalized.type.toBase64Url(),
        normalized.id.toBase64Url(),
    ).joinToString(":")
}

fun parseDreamerRegionMaintenanceClusterKey(clusterKey: String): Pair<MemoryOwnerId, MemoryScope>? {
    val parts = clusterKey.split(':')
    if (parts.size != 4 || parts.first() != DREAMER_REGION_CLUSTER_PREFIX) return null
    val owner = parts[1].fromBase64Url() ?: return null
    val type = parts[2].fromBase64Url() ?: return null
    val id = parts[3].fromBase64Url() ?: return null
    return MemoryOwnerId(owner) to MemoryScope(type, id).normalized()
}

enum class MemoryMaintenanceWorkerState {
    IDLE,
    RUNNING,
    BLOCKED,
}

enum class MemoryMaintenanceBlockReason {
    DREAMER_DISABLED,
    NO_PENDING_CLUSTERS,
    NO_DETERMINISTIC_ACTIONS,
}

data class MemoryMaintenanceStatus(
    val preferences: MemoryMaintenancePreferences = MemoryMaintenancePreferences(),
    val workerState: MemoryMaintenanceWorkerState = MemoryMaintenanceWorkerState.IDLE,
    val pendingClusters: Int = 0,
    val blockedClusters: Int = 0,
    val lastAttemptedAt: Instant? = null,
    val lastCompletedAt: Instant? = null,
    val blockedReason: MemoryMaintenanceBlockReason? = null,
    val lastErrorCode: String? = null,
)

interface MemoryMaintenanceController {
    suspend fun status(): MemoryMaintenanceStatus

    suspend fun savePreferences(preferences: MemoryMaintenancePreferences): MemoryMaintenanceStatus

    suspend fun runNow(): MemoryMaintenanceStatus
}

object NoopMemoryMaintenanceController : MemoryMaintenanceController {
    override suspend fun status(): MemoryMaintenanceStatus =
        MemoryMaintenanceStatus(blockedReason = MemoryMaintenanceBlockReason.DREAMER_DISABLED)

    override suspend fun savePreferences(preferences: MemoryMaintenancePreferences): MemoryMaintenanceStatus {
        val normalized = preferences.normalizedForSupportedMaintenance()
        return MemoryMaintenanceStatus(
            preferences = normalized,
            blockedReason = if (normalized.mode == MemoryMaintenanceMode.OFF) {
                MemoryMaintenanceBlockReason.DREAMER_DISABLED
            } else {
                MemoryMaintenanceBlockReason.NO_PENDING_CLUSTERS
            },
        )
    }

    override suspend fun runNow(): MemoryMaintenanceStatus =
        status()
}

private const val DREAMER_REGION_CLUSTER_PREFIX = "dreamer-region"

private fun String.toBase64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(UTF_8))

private fun String.fromBase64Url(): String? =
    runCatching { String(Base64.getUrlDecoder().decode(this), UTF_8) }.getOrNull()
