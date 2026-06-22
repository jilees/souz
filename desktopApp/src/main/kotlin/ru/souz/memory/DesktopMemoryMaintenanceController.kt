package ru.souz.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.souz.db.ConfigStore
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DesktopMemoryMaintenanceController(
    private val dbPath: Path,
    private val configStore: ConfigStore = ConfigStore,
) : MemoryMaintenanceController {
    override suspend fun status(): MemoryMaintenanceStatus =
        statusFor(loadPreferences())

    override suspend fun savePreferences(preferences: MemoryMaintenancePreferences): MemoryMaintenanceStatus {
        configStore.put(PREFERENCES_KEY, preferences)
        return statusFor(preferences)
    }

    override suspend fun runNow(): MemoryMaintenanceStatus {
        val preferences = loadPreferences()
        val now = Instant.now()
        configStore.put(LAST_ATTEMPTED_AT_KEY, now.toString())
        if (preferences.mode != MemoryMaintenanceMode.OFF) {
            configStore.put(LAST_COMPLETED_AT_KEY, now.toString())
        }
        return statusFor(preferences, attemptedAt = now)
    }

    private fun loadPreferences(): MemoryMaintenancePreferences =
        configStore.get(PREFERENCES_KEY) ?: MemoryMaintenancePreferences()

    private suspend fun statusFor(
        preferences: MemoryMaintenancePreferences,
        attemptedAt: Instant? = readInstant(LAST_ATTEMPTED_AT_KEY),
    ): MemoryMaintenanceStatus {
        val disabled = preferences.mode == MemoryMaintenanceMode.OFF
        val pendingClusters = countMaintenanceJobs("PENDING")
        val blockedClusters = countMaintenanceJobs("BLOCKED")
        val usage = loadCloudUsageForToday()
        return MemoryMaintenanceStatus(
            preferences = preferences,
            pendingClusters = pendingClusters,
            blockedClusters = blockedClusters,
            cloudTokensUsedToday = usage.tokens,
            cloudCallsUsedToday = usage.calls,
            lastAttemptedAt = attemptedAt,
            lastCompletedAt = readInstant(LAST_COMPLETED_AT_KEY),
            blockedReason = if (disabled) {
                MemoryMaintenanceBlockReason.DREAMER_DISABLED
            } else if (pendingClusters == 0) {
                MemoryMaintenanceBlockReason.NO_PENDING_CLUSTERS
            } else {
                null
            },
        )
    }

    private fun readInstant(key: String): Instant? =
        configStore.get<String>(key)?.let { raw ->
            runCatching { Instant.parse(raw) }.getOrNull()
        }

    private suspend fun countMaintenanceJobs(status: String): Int = withContext(Dispatchers.IO) {
        queryInt(
            sql = "select count(*) from memory_maintenance_jobs where status = ?",
            params = listOf(status),
        )
    }

    private suspend fun loadCloudUsageForToday(): CloudUsage = withContext(Dispatchers.IO) {
        val periodKey = LocalDate.now(ZoneOffset.UTC).toString()
        val calls = queryInt(
            sql = "select coalesce(sum(call_count), 0) from memory_budget_reservations where period_key = ? and status != 'CANCELLED'",
            params = listOf(periodKey),
        )
        val tokens = queryInt(
            sql = """
                select coalesce(sum(coalesce(actual_input_tokens, estimated_input_tokens) +
                    coalesce(actual_output_tokens, reserved_output_tokens)), 0)
                from memory_budget_reservations
                where period_key = ? and status != 'CANCELLED'
            """.trimIndent(),
            params = listOf(periodKey),
        )
        CloudUsage(tokens = tokens, calls = calls)
    }

    private fun queryInt(sql: String, params: List<String>): Int =
        runCatching {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.getInt(1) else 0
                    }
                }
            }
        }.getOrDefault(0)

    private data class CloudUsage(
        val tokens: Int,
        val calls: Int,
    )

    private companion object {
        const val PREFERENCES_KEY = "MEMORY_MAINTENANCE_PREFERENCES"
        const val LAST_ATTEMPTED_AT_KEY = "MEMORY_MAINTENANCE_LAST_ATTEMPTED_AT"
        const val LAST_COMPLETED_AT_KEY = "MEMORY_MAINTENANCE_LAST_COMPLETED_AT"
    }
}
