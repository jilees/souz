package ru.souz.backend.client

import ru.souz.backend.attachments.AttachmentException
import ru.souz.backend.attachments.AttachmentStorage
import ru.souz.backend.attachments.attachmentId
import ru.souz.backend.attachments.toContent
import ru.souz.backend.attachments.validatePresentation
import ru.souz.llms.FileContent
import ru.souz.llms.FilePresentation

/** Wire reference to an immutable object in the shared private bucket. */
data class ClientAttachment(
    val id: String,
    val name: String,
    val presentation: FilePresentation = FilePresentation.document,
)

internal fun FileContent.toClientAttachment() = ClientAttachment(file.fileId, name, presentation)

internal fun List<ClientAttachment>.validateReferences() {
    if (size > 10 || distinctBy { it.id }.size != size) {
        throw ClientContractException("invalid_request", "attachments must contain at most 10 distinct files.")
    }
    for (file in this) {
        try { attachmentId(file.id) } catch (_: AttachmentException) {
            throw ClientContractException("invalid_request", "attachments.id must be a canonical lowercase UUID.")
        }
        if (file.name.isBlank()) throw ClientContractException("invalid_request", "attachments.name must not be blank.")
    }
}

internal suspend fun List<ClientAttachment>.importFiles(userId: String, storage: AttachmentStorage?): List<FileContent> {
    if (isEmpty()) return emptyList()
    if (storage == null) throw ClientContractException("feature_disabled", "Attachment storage is not configured.")
    val files = map { reference ->
        storage.importUpload(userId, attachmentId(reference.id), reference.name)
            .toContent(reference.presentation).also { it.validatePresentation() }
    }
    if (files.sumOf { it.sizeBytes } > 100_000_000) throw AttachmentException("attachment_message_too_large")
    return files
}
