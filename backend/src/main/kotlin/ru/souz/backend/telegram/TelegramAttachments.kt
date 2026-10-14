package ru.souz.backend.telegram

import ru.souz.backend.attachments.AttachmentException
import ru.souz.backend.attachments.AttachmentStorage
import ru.souz.backend.attachments.AttachmentStorageConfig
import ru.souz.backend.attachments.toContent
import ru.souz.llms.FileContent
import ru.souz.llms.FilePresentation

class TelegramAttachments(
    private val storage: AttachmentStorage,
    private val api: TelegramBotApi,
    private val config: AttachmentStorageConfig,
) {
    suspend fun receive(binding: TelegramBotBinding, token: String, message: TelegramMessage): List<FileContent> {
        val candidates = buildList {
            message.document?.let { add(it to FilePresentation.document) }
            message.photo.maxByOrNull { (it.width ?: 0).toLong() * (it.height ?: 0) }?.let { add(it to FilePresentation.image) }
            message.voice?.let { add(it to FilePresentation.voice) }
            message.audio?.let { add(it to FilePresentation.audio) }
        }
        if (candidates.size > 10) throw AttachmentException("attachment_message_too_large")
        return candidates.map { (file, presentation) ->
            if ((file.fileSize ?: 0) > config.maxFileBytes) throw AttachmentException("attachment_too_large")
            if ((file.duration ?: 0) > 600) throw AttachmentException("attachment_audio_too_long")
            val bytes = api.downloadFile(token, file.fileId, config.maxFileBytes)
            val name = file.fileName ?: when (presentation) {
                FilePresentation.image -> "image.jpg"
                FilePresentation.voice -> "voice.ogg"
                FilePresentation.audio -> "audio"
                else -> "document"
            }
            val saved = storage.save(binding.userId,
                "telegram:${binding.id}:${message.chat.id}:${message.messageId}:${file.fileUniqueId ?: file.fileId}", name, bytes.inputStream())
            if (saved.mimeType !in SUPPORTED_DOCUMENTS && !saved.mimeType.startsWith("image/") && !saved.mimeType.startsWith("audio/")) {
                throw AttachmentException("attachment_unsupported_type")
            }
            saved.toContent(presentation)
        }
    }

    private companion object { val SUPPORTED_DOCUMENTS = setOf("text/plain", "text/markdown", "text/html", "application/pdf", "application/xhtml+xml") }
}

internal val TelegramMessage.hasAttachments: Boolean
    get() = document != null || photo.isNotEmpty() || voice != null || audio != null
