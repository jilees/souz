package ru.souz.memory

import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.souz.db.ConfigStore
import ru.souz.llms.restJsonMapper
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

interface MemoryMaintenanceSettingsStore {
    fun put(key: String, value: String)

    fun get(key: String): String?
}

object ConfigStoreMemoryMaintenanceSettingsStore : MemoryMaintenanceSettingsStore {
    override fun put(key: String, value: String) {
        ConfigStore.put(key, value)
    }

    override fun get(key: String): String? = ConfigStore.get<String>(key)
}

data class MemoryMaintenanceRunResult(
    val processedJobs: Int,
)

class MemoryMaintenanceWorker(
    private val dbPath: Path,
) {
    suspend fun runOnce(preferences: MemoryMaintenancePreferences): MemoryMaintenanceRunResult = withContext(Dispatchers.IO) {
        if (preferences.mode == MemoryMaintenanceMode.OFF) {
            return@withContext MemoryMaintenanceRunResult(processedJobs = 0)
        }
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            val now = Instant.now().toString()
            val jobKeys = connection.prepareStatement(
                """
                select cluster_key from memory_maintenance_jobs
                where status = 'PENDING'
                order by priority desc, latest_dirty_at desc
                limit ?
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, preferences.maxClustersPerRun.coerceAtLeast(1))
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.getString("cluster_key"))
                    }
                }
            }
            if (jobKeys.isEmpty()) {
                return@withContext MemoryMaintenanceRunResult(processedJobs = 0)
            }
            connection.inTransaction {
                prepareStatement(
                    """
                    update memory_maintenance_jobs
                    set status = 'DONE',
                        attempt_count = attempt_count + 1,
                        lease_owner = null,
                        lease_expires_at = null,
                        updated_at = ?
                    where cluster_key = ? and status = 'PENDING'
                    """.trimIndent()
                ).use { statement ->
                    jobKeys.forEach { clusterKey ->
                        statement.setString(1, now)
                        statement.setString(2, clusterKey)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
            MemoryMaintenanceRunResult(processedJobs = jobKeys.size)
        }
    }

    private inline fun <T> Connection.inTransaction(block: Connection.() -> T): T {
        autoCommit = false
        return try {
            block().also { commit() }
        } catch (error: Exception) {
            rollback()
            throw error
        }
    }
}

class DesktopMemoryMaintenanceController(
    private val dbPath: Path,
    private val settingsStore: MemoryMaintenanceSettingsStore = ConfigStoreMemoryMaintenanceSettingsStore,
    private val worker: MemoryMaintenanceWorker = MemoryMaintenanceWorker(dbPath),
) : MemoryMaintenanceController {
    override suspend fun status(): MemoryMaintenanceStatus {
        val preferences = loadPreferences()
        return runCatching { statusFor(preferences) }
            .getOrElse { error -> statusForError(preferences, error) }
    }

    override suspend fun savePreferences(preferences: MemoryMaintenancePreferences): MemoryMaintenanceStatus {
        settingsStore.put(PREFERENCES_KEY, restJsonMapper.writeValueAsString(preferences))
        return runCatching { statusFor(preferences) }
            .getOrElse { error -> statusForError(preferences, error) }
    }

    override suspend fun runNow(): MemoryMaintenanceStatus {
        val preferences = loadPreferences()
        val now = Instant.now()
        settingsStore.put(LAST_ATTEMPTED_AT_KEY, now.toString())
        if (preferences.mode == MemoryMaintenanceMode.OFF) {
            return runCatching { statusFor(preferences, attemptedAt = now) }
                .getOrElse { error -> statusForError(preferences, error, attemptedAt = now) }
        }
        val result = runCatching { worker.runOnce(preferences) }
        result.onSuccess { run ->
            settingsStore.put(LAST_ERROR_CODE_KEY, "")
            if (run.processedJobs > 0) {
                settingsStore.put(LAST_COMPLETED_AT_KEY, Instant.now().toString())
            }
        }.onFailure { error ->
            settingsStore.put(LAST_ERROR_CODE_KEY, error.errorCode())
        }
        return result.fold(
            onSuccess = {
                runCatching { statusFor(preferences, attemptedAt = now) }
                    .getOrElse { error -> statusForError(preferences, error, attemptedAt = now) }
            },
            onFailure = { error -> statusForError(preferences, error, attemptedAt = now) },
        )
    }

    private fun loadPreferences(): MemoryMaintenancePreferences =
        settingsStore.get(PREFERENCES_KEY)
            ?.takeIf(String::isNotBlank)
            ?.let { raw -> runCatching { restJsonMapper.readValue<MemoryMaintenancePreferences>(raw) }.getOrNull() }
            ?: MemoryMaintenancePreferences()

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
            lastErrorCode = readString(LAST_ERROR_CODE_KEY),
            blockedReason = if (disabled) {
                MemoryMaintenanceBlockReason.DREAMER_DISABLED
            } else if (pendingClusters == 0) {
                MemoryMaintenanceBlockReason.NO_PENDING_CLUSTERS
            } else {
                null
            },
        )
    }

    private fun statusForError(
        preferences: MemoryMaintenancePreferences,
        error: Throwable,
        attemptedAt: Instant? = readInstant(LAST_ATTEMPTED_AT_KEY),
    ): MemoryMaintenanceStatus =
        MemoryMaintenanceStatus(
            preferences = preferences,
            workerState = MemoryMaintenanceWorkerState.BLOCKED,
            lastAttemptedAt = attemptedAt,
            lastCompletedAt = readInstant(LAST_COMPLETED_AT_KEY),
            lastErrorCode = error.errorCode(),
        )

    private fun readInstant(key: String): Instant? =
        readString(key)?.let { raw ->
            runCatching { Instant.parse(raw) }.getOrNull()
        }

    private fun readString(key: String): String? =
        settingsStore.get(key)?.trim()?.takeIf(String::isNotBlank)

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
        run {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        }.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }

    private fun Throwable.errorCode(): String =
        this::class.simpleName ?: this::class.qualifiedName?.substringAfterLast('.') ?: "MemoryMaintenanceError"

    private data class CloudUsage(
        val tokens: Int,
        val calls: Int,
    )

    private companion object {
        const val PREFERENCES_KEY = "MEMORY_MAINTENANCE_PREFERENCES"
        const val LAST_ATTEMPTED_AT_KEY = "MEMORY_MAINTENANCE_LAST_ATTEMPTED_AT"
        const val LAST_COMPLETED_AT_KEY = "MEMORY_MAINTENANCE_LAST_COMPLETED_AT"
        const val LAST_ERROR_CODE_KEY = "MEMORY_MAINTENANCE_LAST_ERROR_CODE"
    }
}
