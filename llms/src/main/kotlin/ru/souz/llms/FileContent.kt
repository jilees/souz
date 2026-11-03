package ru.souz.llms

/** Durable host-owned identity. Never a provider upload ID, URL, or filesystem path. */
data class FileReference(val fileId: String)

enum class FilePresentation { document, image, voice, audio }

/** Provider-neutral metadata shared by messages, tool results and hosts. */
data class FileContent(
    /** Immutable host-owned identity, never a URL, provider upload ID or filesystem path. */
    val fileId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val presentation: FilePresentation = FilePresentation.document,
)

data class ImageContent(val file: FileReference, val detail: String = "auto") {
    init { require(detail in setOf("auto", "low", "high")) }
}
/** Per-message limits shared by agent selection and host commit validation. */
const val MAX_MESSAGE_FILES: Int = 10
const val MAX_MESSAGE_FILE_BYTES: Long = 100_000_000L
