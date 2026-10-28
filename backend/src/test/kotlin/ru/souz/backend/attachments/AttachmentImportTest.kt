package ru.souz.backend.attachments

import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttachmentImportTest {
    @Test
    fun `external objects retain their UUID and metadata across concurrent imports without renewing expiry`() = attachmentTest {
        val bytes = "# Uploaded by a bot".toByteArray()
        val uploaded = AttachmentMinio.upload(config, "alice", "report.md", bytes)
        assertNull(repository.get("alice", uploaded.id))
        val files = coroutineScope {
            List(2) { async { storage.importUpload("alice", uploaded.id, "../report.md") } }.map { it.await() }
        }
        val saved = files.first()
        assertEquals(saved, files.last())
        assertEquals(uploaded.id, saved.id)
        assertEquals(uploaded.objectKey, saved.objectKey)
        assertEquals("report.md", saved.name)
        assertEquals("text/markdown", saved.mimeType)
        assertEquals(bytes.size.toLong(), saved.sizeBytes)
        assertContentEquals(bytes, storage.read("alice", saved.id))
        clock.now = saved.expiresAt!!.minusSeconds(1)
        assertEquals(saved, storage.importUpload("alice", saved.id, saved.name))
        assertAttachmentError("attachment_idempotency_conflict") { storage.importUpload("alice", saved.id, "renamed.md") }
        clock.now = saved.expiresAt
        assertAttachmentError("attachment_expired") { storage.importUpload("alice", saved.id, saved.name) }
        assertEquals(1, storage.cleanup())
        assertAttachmentError("attachment_not_found") { storage.importUpload("alice", saved.id, saved.name) }
    }

    @Test
    fun `imports reject missing oversized and foreign objects without replacing owned metadata`() = attachmentTest {
        val id = UUID.randomUUID()
        assertAttachmentError("attachment_not_found") { storage.importUpload("alice", id, "missing.txt") }
        assertNull(repository.get("alice", id))
        AttachmentMinio.upload(config, "alice", "large.bin", ByteArray(config.maxFileBytes + 1), id)
        assertAttachmentError("attachment_too_large") { storage.importUpload("alice", id, "large.bin") }
        assertNull(repository.get("alice", id))
        val original = save("alice", "existing", "a.txt", "original")
        assertAttachmentError("attachment_not_found") { storage.importUpload("bob", original.id, original.name) }
        // Even a valid object at the other owner's prefix cannot claim an already registered UUID.
        AttachmentMinio.upload(config, "bob", original.name, "other".toByteArray(), original.id)
        assertAttachmentError("attachment_not_found") { storage.importUpload("bob", original.id, original.name) }
        assertEquals(original, storage.get("alice", original.id))
        assertNull(repository.get("bob", original.id))
        assertContentEquals("original".toByteArray(), storage.read("alice", original.id))
    }
}
