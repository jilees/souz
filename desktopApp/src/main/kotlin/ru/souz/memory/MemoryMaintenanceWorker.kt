package ru.souz.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

data class MemoryMaintenanceRunResult(
    val processedJobs: Int,
    val inspectedJobs: Int = processedJobs,
    val blockedJobs: Int = 0,
)

private data class MaintenanceJob(
    val clusterKey: String,
    val ownerId: String,
    val latestDirtyAt: String,
    val createdAt: String,
    val reasons: String,
)

private data class DreamerRegion(
    val factIds: List<String>,
    val facts: List<MemoryFactDetails>,
)

private data class InsertedDreamerFact(
    val id: String,
    val contentHash: String,
)

class MemoryMaintenanceWorker(
    private val dbPath: Path,
    private val consolidator: MemoryConsolidator = NoopMemoryConsolidator,
    private val qualityGate: MemoryConsolidationQualityGate = DefaultMemoryConsolidationQualityGate,
    private val embeddingModel: String? = null,
) {
    suspend fun runOnce(preferences: MemoryMaintenancePreferences): MemoryMaintenanceRunResult {
        val normalizedPreferences = preferences.normalizedForSupportedMaintenance()
        if (normalizedPreferences.mode == MemoryMaintenanceMode.OFF) {
            return MemoryMaintenanceRunResult(processedJobs = 0)
        }
        initializeSqliteMemoryRepository(dbPath)
        val jobs = pendingJobs(normalizedPreferences.maxClustersPerRun)
        if (jobs.isEmpty()) {
            return MemoryMaintenanceRunResult(processedJobs = 0)
        }
        var processed = 0
        var blocked = 0
        jobs.forEach { job ->
            when {
                job.isLegacyChatMigration() -> {
                    processed += withConnection { connection ->
                        connection.inTransaction { processLegacyChatMigration(job, Instant.now().toString()) }
                    }
                }
                job.isDreamerRegionRewrite() -> {
                    val result = processDreamerRegionRewrite(job)
                    processed += result.processedJobs
                    blocked += result.blockedJobs
                }
                else -> {
                    blocked += withConnection { connection ->
                        connection.inTransaction { blockUnsupportedJob(job, Instant.now().toString()) }
                    }
                }
            }
        }
        return MemoryMaintenanceRunResult(processedJobs = processed, inspectedJobs = jobs.size, blockedJobs = blocked)
    }

    private suspend fun pendingJobs(limit: Int): List<MaintenanceJob> = withConnection { connection ->
        connection.prepareStatement(
            """
            select cluster_key, owner_id, latest_dirty_at, created_at, reasons from memory_maintenance_jobs
            where status = 'PENDING'
            order by priority desc, latest_dirty_at desc
            limit ?
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            MaintenanceJob(
                                clusterKey = rs.getString("cluster_key"),
                                ownerId = rs.getString("owner_id"),
                                latestDirtyAt = rs.getString("latest_dirty_at"),
                                createdAt = rs.getString("created_at"),
                                reasons = rs.getString("reasons").orEmpty(),
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun <T> withConnection(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use(block)
    }

    private suspend fun processDreamerRegionRewrite(job: MaintenanceJob): MemoryMaintenanceRunResult {
        val parsed = parseDreamerRegionMaintenanceClusterKey(job.clusterKey)
            ?: return blockJobResult(job)
        if (consolidator === NoopMemoryConsolidator) {
            return blockJobResult(job)
        }
        val (ownerId, scope) = parsed
        if (scope.type !in DREAMER_DURABLE_SCOPE_TYPES) {
            return blockJobResult(job)
        }
        val region = loadDreamerRegion(ownerId, scope, job)
        if (region.facts.size < 2) {
            withConnection { connection ->
                connection.inTransaction {
                    completeJob(
                        job,
                        "done:${job.clusterKey}:$MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE:insufficient_facts",
                        Instant.now().toString(),
                    )
                }
            }
            return MemoryMaintenanceRunResult(processedJobs = 0, inspectedJobs = 1)
        }

        val input = MemoryConsolidationInput(
            ownerId = ownerId,
            scope = scope,
            facts = region.facts,
        )
        val candidates = consolidator.consolidate(input).mapNotNull { it.normalizedCandidate() }
        if (candidates.isEmpty()) {
            withConnection { connection ->
                connection.inTransaction {
                    completeJob(
                        job,
                        "done:${job.clusterKey}:$MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE:no_replacements",
                        Instant.now().toString(),
                    )
                }
            }
            return MemoryMaintenanceRunResult(processedJobs = 0, inspectedJobs = 1)
        }
        val evaluation = qualityGate.evaluate(input, candidates)
        if (!evaluation.accepted) {
            withConnection { connection ->
                connection.inTransaction {
                    completeJob(
                        job,
                        "done:${job.clusterKey}:$MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE:quality_rejected:${evaluation.reason}:reward=${evaluation.rewardScore}",
                        Instant.now().toString(),
                    )
                }
            }
            return MemoryMaintenanceRunResult(processedJobs = 0, inspectedJobs = 1)
        }

        val inserted = withConnection { connection ->
            connection.inTransaction {
                commitDreamerReplacement(job, ownerId, scope, region, candidates, Instant.now().toString())
            }
        }
        return MemoryMaintenanceRunResult(processedJobs = if (inserted > 0) 1 else 0, inspectedJobs = 1)
    }

    private suspend fun blockJobResult(job: MaintenanceJob): MemoryMaintenanceRunResult =
        withConnection { connection ->
            MemoryMaintenanceRunResult(
                processedJobs = 0,
                inspectedJobs = 1,
                blockedJobs = connection.inTransaction { blockUnsupportedJob(job, Instant.now().toString()) },
            )
        }

    private suspend fun loadDreamerRegion(
        ownerId: MemoryOwnerId,
        scope: MemoryScope,
        job: MaintenanceJob,
    ): DreamerRegion {
        val factIds = withConnection { connection ->
            connection.prepareStatement(
                """
                select f.id
                from memory_facts f
                left join memory_fact_stats s on s.fact_id = f.id
                where f.owner_id = ?
                  and f.scope_type = ?
                  and f.scope_id = ?
                  and f.status = ?
                  and f.retention = ?
                  and f.updated_at <= ?
                order by case
                           when f.updated_at >= ? then 2
                           when s.last_retrieved_at is not null then 1
                           else 0
                         end desc,
                         coalesce(s.last_retrieved_at, f.updated_at) desc,
                         f.importance desc,
                         f.pinned desc,
                         f.updated_at desc
                limit ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, ownerId.value)
                statement.setString(2, scope.type)
                statement.setString(3, scope.id)
                statement.setString(4, MemoryFactStatus.ACTIVE.name)
                statement.setString(5, MemoryRetention.DURABLE.name)
                statement.setString(6, job.latestDirtyAt)
                statement.setString(7, job.createdAt)
                statement.setInt(8, DREAMER_REGION_FACT_LIMIT)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.getString("id"))
                    }
                }
            }
        }
        val repository = SqliteMemoryRepository(dbPath)
        val facts = factIds.mapNotNull { factId -> repository.getFactDetails(factId) }
        return DreamerRegion(factIds = factIds, facts = facts)
    }

    private fun MemoryConsolidationCandidate.normalizedCandidate(): MemoryConsolidationCandidate? {
        val title = MemorySanitizer.redact(title.trim()).takeIf(String::isNotBlank)
        val body = MemorySanitizer.redact(body.trim()).takeIf(String::isNotBlank)
        return if (title == null || body == null) {
            null
        } else {
            copy(
                title = title,
                body = body,
                canonicalKey = normalizeCanonicalKey(canonicalKey),
                confidence = confidence.coerceIn(0f, 1f),
                importance = importance.coerceIn(0f, 1f),
                evidenceSourceEventIds = evidenceSourceEventIds.distinct(),
            )
        }
    }

    private fun Connection.commitDreamerReplacement(
        job: MaintenanceJob,
        ownerId: MemoryOwnerId,
        scope: MemoryScope,
        region: DreamerRegion,
        candidates: List<MemoryConsolidationCandidate>,
        now: String,
    ): Int {
        val jobUpdated = completeJob(
            job = job,
            reason = "done:${job.clusterKey}:$MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE:replacements=${candidates.size}:retired=${region.factIds.size}",
            now = now,
        )
        if (jobUpdated == 0) return 0
        retireFacts(region.factIds, now)
        val sourceEventIds = region.facts
            .flatMap { details -> details.evidence.map { it.sourceEvent.id } }
            .distinct()
        val insertedFacts = candidates.map { candidate ->
            insertDreamerFact(
                ownerId = ownerId,
                scope = scope,
                candidate = candidate,
                fallbackSourceEventIds = sourceEventIds,
                supersedesFactId = region.factIds.firstOrNull(),
                now = now,
            )
        }
        insertedFacts.forEach { fact ->
            insertSupersedes(
                replacementFactId = fact.id,
                supersededFactIds = region.factIds,
                ownerId = ownerId,
                now = now,
            )
            enqueueEmbeddingJob(
                factId = fact.id,
                ownerId = ownerId,
                contentHash = fact.contentHash,
                now = now,
            )
        }
        recordMaintenanceOperation(
            ownerId = ownerId.value,
            reason = "done:${job.clusterKey}:$MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE:inserted=${insertedFacts.size}",
            now = now,
        )
        return insertedFacts.size
    }

    private fun Connection.retireFacts(factIds: List<String>, now: String) {
        if (factIds.isEmpty()) return
        prepareStatement(
            """
            update memory_facts
            set status = ?,
                updated_at = ?
            where id = ? and status = ?
            """.trimIndent()
        ).use { statement ->
            factIds.distinct().forEach { factId ->
                statement.setString(1, MemoryFactStatus.RETIRED.name)
                statement.setString(2, now)
                statement.setString(3, factId)
                statement.setString(4, MemoryFactStatus.ACTIVE.name)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.insertDreamerFact(
        ownerId: MemoryOwnerId,
        scope: MemoryScope,
        candidate: MemoryConsolidationCandidate,
        fallbackSourceEventIds: List<String>,
        supersedesFactId: String?,
        now: String,
    ): InsertedDreamerFact {
        candidate.canonicalKey?.let { canonicalKey ->
            retireCanonicalConflicts(ownerId, scope, canonicalKey, now)
        }
        val factId = UUID.randomUUID().toString()
        val contentHash = stableMemoryContentHash(candidate.title, candidate.body, candidate.kind, candidate.canonicalKey)
        prepareStatement(
            """
            insert into memory_facts(
                id, owner_id, scope_type, scope_id, kind, title, body, slot_key, canonical_key, status,
                validity, retention, sensitivity, confidence, importance, pinned, created_by, version,
                content_hash, created_at, updated_at, last_observed_at, supersedes_fact_id
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, factId)
            statement.setString(2, ownerId.value)
            statement.setString(3, scope.type)
            statement.setString(4, scope.id)
            statement.setString(5, candidate.kind.name)
            statement.setString(6, candidate.title)
            statement.setString(7, candidate.body)
            statement.setString(8, candidate.canonicalKey)
            statement.setString(9, candidate.canonicalKey)
            statement.setString(10, MemoryFactStatus.ACTIVE.name)
            statement.setString(11, MemoryFactValidity.VALID.name)
            statement.setString(12, retentionForScope(scope).name)
            statement.setString(13, MemorySensitivity.NORMAL.name)
            statement.setFloat(14, candidate.confidence)
            statement.setFloat(15, candidate.importance)
            statement.setInt(16, 0)
            statement.setString(17, "dreamer")
            statement.setLong(18, 1L)
            statement.setString(19, contentHash)
            statement.setString(20, now)
            statement.setString(21, now)
            statement.setString(22, now)
            statement.setString(23, supersedesFactId)
            statement.executeUpdate()
        }
        val evidenceSourceEventIds = candidate.evidenceSourceEventIds
            .filter { it in fallbackSourceEventIds }
            .ifEmpty { fallbackSourceEventIds }
            .distinct()
        insertEvidence(factId, evidenceSourceEventIds)
        return InsertedDreamerFact(id = factId, contentHash = contentHash)
    }

    private fun Connection.insertSupersedes(
        replacementFactId: String,
        supersededFactIds: List<String>,
        ownerId: MemoryOwnerId,
        now: String,
    ) {
        if (supersededFactIds.isEmpty()) return
        prepareStatement(
            """
            insert or ignore into memory_fact_supersedes(
                replacement_fact_id, superseded_fact_id, owner_id, reason, created_at
            ) values (?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            supersededFactIds.distinct().forEach { supersededFactId ->
                statement.setString(1, replacementFactId)
                statement.setString(2, supersededFactId)
                statement.setString(3, ownerId.value)
                statement.setString(4, MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE)
                statement.setString(5, now)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.enqueueEmbeddingJob(
        factId: String,
        ownerId: MemoryOwnerId,
        contentHash: String,
        now: String,
    ) {
        val model = embeddingModel ?: return
        prepareStatement(
            """
            insert into memory_index_jobs(
                fact_id, owner_id, embedding_model, content_hash, status, attempt_count,
                next_attempt_at, created_at, updated_at
            ) values (?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
            on conflict(fact_id, embedding_model, content_hash) do update set
                status = case when status = 'COMPLETED' then status else 'PENDING' end,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, factId)
            statement.setString(2, ownerId.value)
            statement.setString(3, model)
            statement.setString(4, contentHash)
            statement.setString(5, now)
            statement.setString(6, now)
            statement.setString(7, now)
            statement.executeUpdate()
        }
    }

    private fun Connection.retireCanonicalConflicts(
        ownerId: MemoryOwnerId,
        scope: MemoryScope,
        canonicalKey: String,
        now: String,
    ) {
        prepareStatement(
            """
            update memory_facts
            set status = ?,
                updated_at = ?
            where owner_id = ?
              and scope_type = ?
              and scope_id = ?
              and canonical_key = ?
              and status = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, MemoryFactStatus.RETIRED.name)
            statement.setString(2, now)
            statement.setString(3, ownerId.value)
            statement.setString(4, scope.type)
            statement.setString(5, scope.id)
            statement.setString(6, canonicalKey)
            statement.setString(7, MemoryFactStatus.ACTIVE.name)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertEvidence(factId: String, sourceEventIds: List<String>) {
        if (sourceEventIds.isEmpty()) return
        prepareStatement(
            """
            insert or ignore into memory_fact_evidence(fact_id, source_event_id, evidence_text)
            values (?, ?, null)
            """.trimIndent()
        ).use { statement ->
            sourceEventIds.forEach { sourceEventId ->
                statement.setString(1, factId)
                statement.setString(2, sourceEventId)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.completeJob(
        job: MaintenanceJob,
        reason: String,
        now: String,
    ): Int {
        val updated = prepareStatement(
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
            statement.setString(1, now)
            statement.setString(2, job.clusterKey)
            statement.executeUpdate()
        }
        if (updated > 0) {
            recordMaintenanceOperation(
                ownerId = job.ownerId,
                reason = reason,
                now = now,
            )
        }
        return updated
    }

    private fun Connection.processLegacyChatMigration(
        job: MaintenanceJob,
        now: String,
    ): Int {
        val scopeId = job.legacyChatScopeId() ?: return blockUnsupportedJob(job, now)
        val jobUpdated = prepareStatement(
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
            statement.setString(1, now)
            statement.setString(2, job.clusterKey)
            statement.executeUpdate()
        }
        if (jobUpdated == 0) return 0

        val factIds = prepareStatement(
            """
            select id from memory_facts
            where owner_id = ?
              and scope_type in ('chat', 'thread')
              and scope_id = ?
              and status = ?
              and updated_at <= ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, job.ownerId)
            statement.setString(2, scopeId)
            statement.setString(3, MemoryFactStatus.ACTIVE.name)
            statement.setString(4, job.latestDirtyAt)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.getString("id"))
                }
            }
        }

        prepareStatement(
            """
            update memory_facts
            set status = ?,
                updated_at = ?
            where id = ? and status = ?
            """.trimIndent()
        ).use { statement ->
            factIds.forEach { factId ->
                statement.setString(1, MemoryFactStatus.RETIRED.name)
                statement.setString(2, now)
                statement.setString(3, factId)
                statement.setString(4, MemoryFactStatus.ACTIVE.name)
                statement.addBatch()
            }
            statement.executeBatch()
        }

        recordMaintenanceOperation(
            ownerId = job.ownerId,
            reason = "done:${job.clusterKey}:$MEMORY_MAINTENANCE_REASON_LEGACY_CHAT_MIGRATION:retired=${factIds.size}",
            now = now,
        )
        return 1
    }

    private fun Connection.blockUnsupportedJob(
        job: MaintenanceJob,
        now: String,
    ): Int {
        val blockReason = "no_deterministic_action"
        val updated = prepareStatement(
            """
            update memory_maintenance_jobs
            set status = 'BLOCKED',
                attempt_count = attempt_count + 1,
                lease_owner = null,
                lease_expires_at = null,
                reasons = case
                    when instr(reasons, ?) > 0 then reasons
                    when trim(reasons) = '' then ?
                    else reasons || ',' || ?
                end,
                updated_at = ?
            where cluster_key = ? and status = 'PENDING'
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, blockReason)
            statement.setString(2, blockReason)
            statement.setString(3, blockReason)
            statement.setString(4, now)
            statement.setString(5, job.clusterKey)
            statement.executeUpdate()
        }
        if (updated > 0) {
            recordMaintenanceOperation(
                ownerId = job.ownerId,
                reason = "blocked:${job.clusterKey}:$blockReason",
                now = now,
            )
        }
        return updated
    }

    private fun Connection.recordMaintenanceOperation(
        ownerId: String,
        reason: String,
        now: String,
    ) {
        prepareStatement(
            """
            insert into memory_operation_log(id, fact_id, owner_id, type, reason, created_at)
            values (?, null, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, UUID.randomUUID().toString())
            statement.setString(2, ownerId)
            statement.setString(3, MemoryOperationType.MAINTENANCE.name)
            statement.setString(4, reason)
            statement.setString(5, now)
            statement.executeUpdate()
        }
    }

    private fun MaintenanceJob.isLegacyChatMigration(): Boolean =
        reasonsList().contains(MEMORY_MAINTENANCE_REASON_LEGACY_CHAT_MIGRATION) &&
            legacyChatScopeId() != null

    private fun MaintenanceJob.isDreamerRegionRewrite(): Boolean =
        reasonsList().contains(MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE) &&
            parseDreamerRegionMaintenanceClusterKey(clusterKey) != null

    private fun MaintenanceJob.reasonsList(): List<String> =
        reasons.split(',').map(String::trim)

    private fun MaintenanceJob.legacyChatScopeId(): String? {
        val raw = clusterKey.removePrefix("legacy-chat:").takeIf { it != clusterKey } ?: return null
        return raw.substringAfter(':', missingDelimiterValue = "").takeIf(String::isNotBlank)
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

    private companion object {
        const val DREAMER_REGION_FACT_LIMIT = 24
        val DREAMER_DURABLE_SCOPE_TYPES = setOf("global", "project")
    }
}
