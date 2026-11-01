package ru.souz.backend.client

import ru.souz.backend.attachments.AttachmentException
import ru.souz.backend.attachments.AttachmentStorage
import ru.souz.backend.attachments.attachmentId
import ru.souz.backend.attachments.toContent
import ru.souz.backend.attachments.validateAttachmentObjectKey
import ru.souz.backend.attachments.requireMatchingPresentation
import ru.souz.backend.storage.postgres.PostgresAttachmentRepository
import ru.souz.llms.FileContent
import ru.souz.llms.FilePresentation

/** Opaque key to an immutable object inside the configured private bucket. */
data class ClientAttachment(
    val objectKey: String,
    val name: String,
    val presentation: FilePresentation = FilePresentation.document,
)

internal suspend fun FileContent.toClientAttachment(userId: String, repository: PostgresAttachmentRepository?): ClientAttachment {
    val stored = repository?.get(userId, attachmentId(fileId)) ?: throw AttachmentException("attachment_not_found")
    return ClientAttachment(stored.objectKey, name, presentation)
}

internal fun List<ClientAttachment>.validateReferences() {
    if (size > 10 || distinctBy { it.objectKey }.size != size) {
        throw ClientContractException("invalid_request", "attachments must contain at most 10 distinct files.")
    }
    for (file in this) {
        try { validateAttachmentObjectKey(file.objectKey) } catch (_: AttachmentException) {
            throw ClientContractException("invalid_request", "Invalid attachment objectKey.")
        }
        if (file.name.isBlank()) throw ClientContractException("invalid_request", "attachments.name must not be blank.")
    }
}

internal suspend fun List<ClientAttachment>.importFiles(userId: String, storage: AttachmentStorage?): List<FileContent> {
    if (isEmpty()) return emptyList()
    if (storage == null) throw ClientContractException("feature_disabled", "Attachment storage is not configured.")
    val files = map { reference ->
        storage.importUpload(userId, reference.objectKey, reference.name)
            .toContent(reference.presentation).also { it.requireMatchingPresentation() }
    }
    if (files.sumOf { it.sizeBytes } > 100_000_000) throw AttachmentException("attachment_message_too_large")
    return files
}
