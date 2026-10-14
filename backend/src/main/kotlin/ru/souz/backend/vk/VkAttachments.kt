package ru.souz.backend.vk

import ru.souz.backend.attachments.AttachmentException
import ru.souz.backend.attachments.AttachmentStorage
import ru.souz.backend.attachments.AttachmentStorageConfig
import ru.souz.backend.attachments.toContent
import ru.souz.llms.FileContent
import ru.souz.llms.FilePresentation

class VkAttachments(private val storage: AttachmentStorage, private val api: VkBotApi, private val config: AttachmentStorageConfig) {
    suspend fun receive(binding: VkBotBinding, message: VkMessage): List<FileContent> {
        if (message.attachments.size > 10) throw AttachmentException("attachment_message_too_large")
        var total = 0L
        return message.attachments.mapIndexed { index, attachment ->
            val file = when (attachment.type) {
                "doc" -> attachment.doc
                "photo" -> attachment.photo
                "audio_message" -> attachment.audioMessage
                "audio" -> attachment.audio
                else -> null
            } ?: throw AttachmentException("attachment_unsupported_type")
            if ((file.size ?: 0) > config.maxFileBytes) throw AttachmentException("attachment_too_large")
            if ((file.duration ?: 0) > 600) throw AttachmentException("attachment_audio_too_long")
            val url = file.linkOgg ?: file.url ?: file.sizes.maxByOrNull { it.width.toLong() * it.height }?.url
                ?: throw AttachmentException("attachment_download_unavailable")
            val bytes = api.downloadFile(url, config.maxFileBytes)
            total += bytes.size
            if (total > 100_000_000) throw AttachmentException("attachment_message_too_large")
            val presentation = when (attachment.type) {
                "photo" -> FilePresentation.image
                "audio_message" -> FilePresentation.voice
                "audio" -> FilePresentation.audio
                else -> FilePresentation.document
            }
            val name = file.title ?: when (presentation) {
                FilePresentation.image -> "image.jpg"
                FilePresentation.voice -> "voice.ogg"
                else -> "attachment"
            }
            val saved = storage.save(binding.userId, "vk:${binding.id}:${message.peerId}:${message.id}:$index:${file.id}",
                if (file.ext != null && !name.endsWith(".${file.ext}", true)) "$name.${file.ext}" else name, bytes.inputStream())
            if (saved.mimeType !in setOf("text/plain", "text/markdown", "text/html", "application/pdf", "application/xhtml+xml") &&
                !saved.mimeType.startsWith("image/") && !saved.mimeType.startsWith("audio/")) {
                throw AttachmentException("attachment_unsupported_type")
            }
            saved.toContent(presentation)
        }
    }
}
