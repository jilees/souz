package ru.souz.backend.attachments

import java.io.InputStream
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.backend.storage.postgres.PostgresAttachmentRepository
import ru.souz.runtime.sandbox.SandboxFileSystem

class AttachmentStorage(
    private val config: AttachmentStorageConfig,
    private val repository: PostgresAttachmentRepository,
    private val objects: S3AttachmentObjects,
    private val clock: Clock,
) {
    suspend fun ensureOwner(userId: String) {
        repository.bucket(userId, config.bucketPrefix)
    }

    suspend fun save(userId: String, sourceKey: String, name: String, input: InputStream): StoredAttachment {
        require(userId.isNotBlank() && sourceKey.isNotBlank() && sourceKey.length <= 512)
        val bytes = runInterruptible(Dispatchers.IO) { input.use { it.readAttachmentBytes(config.maxFileBytes) } }
        val safeName = attachmentName(name)
        val mime = attachmentMime(bytes, safeName)
        val (bucket, ready) = repository.bucket(userId, config.bucketPrefix)
        if (!ready) {
            objects.ensureBucket(bucket)
            repository.bucketReady(userId)
        }
        val file = repository.reserve(userId, sourceKey, safeName, mime, bytes.size.toLong(), bytes.attachmentChecksum(), clock.instant())
        if (file.state == "ready") return available(file)
        if (file.state != "pending" || !file.createdAt.plus(Duration.ofHours(24)).isAfter(clock.instant())) {
            throw AttachmentException("attachment_expired")
        }
        currentCoroutineContext().ensureActive()
        var committed = false
        try {
            objects.put(file, bytes)
            val completed = repository.complete(file, clock.instant())
            if (completed.state != "ready") {
                repository.retireLateWrite(file)
                objects.delete(file)
                throw AttachmentException("attachment_expired")
            }
            return get(userId, completed.id).also { committed = true }
        } finally {
            // A cancelled/late writer may finish after cleanup has already left a tombstone.
            // Keep that object retryable even if its user was removed before complete().
            if (!committed) withContext(NonCancellable) {
                try { repository.retireLateWrite(file) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { logger.warn("Late attachment write cleanup scheduling failed for file {}", file.id) }
            }
        }
    }

    suspend fun get(userId: String, id: UUID): StoredAttachment {
        return available(repository.get(userId, id) ?: throw AttachmentException("attachment_not_found"))
    }

    suspend fun find(userId: String, sourceKey: String): StoredAttachment? {
        return repository.find(userId, sourceKey)?.let(::available)
    }

    suspend fun read(userId: String, id: UUID): ByteArray {
        val file = get(userId, id)
        val bytes = objects.read(file, config.maxFileBytes)
        available(file)
        return bytes
    }

    suspend fun saveWorkingFile(userId: String, sourceKey: String, path: String, fs: SandboxFileSystem): StoredAttachment {
        val resolved = fs.resolveExistingFile(path)
        if (resolved.isSymbolicLink) throw AttachmentException("attachment_unsafe_path")
        if ((resolved.sizeBytes ?: 0) > config.maxFileBytes) throw AttachmentException("attachment_too_large")
        return save(userId, sourceKey, resolved.name, fs.openInputStream(resolved))
    }

    suspend fun prepare(userId: String, id: UUID, fs: SandboxFileSystem): String {
        val file = get(userId, id)
        val bytes = read(userId, id)
        // Rebuild from immutable S3 bytes even if a previous working copy has been edited.
        val root = fs.runtimePaths.workspaceRootPath ?: throw AttachmentException("attachment_workspace_unavailable")
        return runInterruptible(Dispatchers.IO) {
            val directory = fs.resolvePath("$root/attachments/$id")
            if (!fs.isPathSafe(directory) || directory.isSymbolicLink) throw AttachmentException("attachment_unsafe_path")
            fs.createDirectory(directory)
            val target = fs.resolvePath("${directory.path}/${file.name}")
            if (!fs.isPathSafe(target) || target.isSymbolicLink) throw AttachmentException("attachment_unsafe_path")
            fs.writeBytes(target, bytes)
            target.path
        }
    }

    suspend fun cleanup(): Int {
        var removed = 0
        for (file in repository.expire(clock.instant())) {
            try {
                objects.delete(file)
                repository.deleted(file)
                removed++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Never log S3 exception messages: they can carry signed request details.
                logger.warn("Attachment cleanup failed for file {}", file.id)
            }
        }
        return removed
    }

    private fun available(file: StoredAttachment): StoredAttachment {
        if (file.state in setOf("deleting", "deleted") || file.expiresAt?.isAfter(clock.instant()) == false) {
            throw AttachmentException("attachment_expired")
        }
        if (file.state != "ready") throw AttachmentException("attachment_not_ready")
        return file
    }

    private companion object {
        val logger = LoggerFactory.getLogger(AttachmentStorage::class.java)
    }
}
