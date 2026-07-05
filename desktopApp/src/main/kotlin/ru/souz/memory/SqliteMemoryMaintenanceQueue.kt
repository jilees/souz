package ru.souz.memory

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

internal fun Connection.enqueueDreamerRegionIfNeeded(
    ownerId: MemoryOwnerId,
    scope: MemoryScope,
    status: MemoryFactStatus,
    retention: MemoryRetention,
    createdBy: String,
    dirtyAt: Instant,
) {
    if (status != MemoryFactStatus.ACTIVE || createdBy == "dreamer") return
    if (retention != MemoryRetention.DURABLE || scope.type !in DREAMER_DURABLE_SCOPE_TYPES) return
    val now = Instant.now().toString()
    prepareStatement(
        """
        insert into memory_maintenance_jobs(
            cluster_key,
            owner_id,
            status,
            priority,
            latest_dirty_at,
            reasons,
            next_attempt_at,
            created_at,
            updated_at
        ) values (?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)
        on conflict(cluster_key) do update set
            owner_id = excluded.owner_id,
            status = 'PENDING',
            priority = max(memory_maintenance_jobs.priority, excluded.priority),
            latest_dirty_at = max(memory_maintenance_jobs.latest_dirty_at, excluded.latest_dirty_at),
            reasons = case
                when instr(memory_maintenance_jobs.reasons, excluded.reasons) > 0 then memory_maintenance_jobs.reasons
                when trim(memory_maintenance_jobs.reasons) = '' then excluded.reasons
                else memory_maintenance_jobs.reasons || ',' || excluded.reasons
            end,
            next_attempt_at = excluded.next_attempt_at,
            lease_owner = null,
            lease_expires_at = null,
            updated_at = excluded.updated_at
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, dreamerRegionMaintenanceClusterKey(ownerId, scope))
        statement.setString(2, ownerId.value)
        statement.setInt(3, dreamerPriority(scope))
        statement.setString(4, dirtyAt.toString())
        statement.setString(5, MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE)
        statement.setString(6, now)
        statement.setString(7, now)
        statement.setString(8, now)
        statement.executeUpdate()
    }
}

internal fun Connection.enqueueDreamerRegionsForRetrievedFacts(
    factIds: List<String>,
    retrievedAt: Instant,
) {
    if (factIds.isEmpty()) return
    prepareStatement(
        """
        select owner_id, scope_type, scope_id, status, retention, created_by
        from memory_facts
        where id in (${placeholders(factIds.size)})
        """.trimIndent()
    ).use { statement ->
        factIds.forEachIndexed { index, factId -> statement.setString(index + 1, factId) }
        statement.executeQuery().use { rs ->
            while (rs.next()) {
                enqueueDreamerRegionIfNeeded(
                    ownerId = MemoryOwnerId(rs.getString("owner_id") ?: LEGACY_OWNER_ID),
                    scope = MemoryScope(
                        type = rs.getString("scope_type"),
                        id = rs.getString("scope_id"),
                    ).normalized(),
                    status = enumValueOrDefault(
                        rs.getStringOrDefault("status", MemoryFactStatus.ACTIVE.name),
                        MemoryFactStatus.ACTIVE,
                    ),
                    retention = enumValueOrDefault(
                        rs.getStringOrDefault("retention", MemoryRetention.DURABLE.name),
                        MemoryRetention.DURABLE,
                    ),
                    createdBy = rs.getString("created_by"),
                    dirtyAt = retrievedAt,
                )
            }
        }
    }
}

private fun dreamerPriority(scope: MemoryScope): Int = when (scope.type) {
    "project" -> 8
    "global" -> 6
    else -> 0
}

private fun placeholders(size: Int): String = List(size) { "?" }.joinToString(", ")

private fun ResultSet.getStringOrDefault(name: String, default: String): String =
    getString(name) ?: default

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default

private val DREAMER_DURABLE_SCOPE_TYPES = setOf("global", "project")
