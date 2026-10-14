package ru.souz.backend.attachments

import java.net.URI
import ru.souz.backend.config.BackendConfigSource

/** Optional backend-owned S3 connection; secrets must never appear in diagnostics. */
class AttachmentStorageConfig(
    val endpoint: String,
    val region: String = "us-east-1",
    val bucketPrefix: String = "souz-files-",
    val accessKey: String,
    val secretKey: String,
    val maxFileBytes: Int = 20_000_000,
    val pathStyleAccess: Boolean = true,
    val imageRoutes: Set<String> = setOf("CODEX::gpt-6.1-sol", "CODEX::gpt-6-sol", "CODEX::gpt-5.6-sol"),
    val visionModel: String? = null,
    val mediaApiKey: String? = null,
    val sttEndpoint: String = "https://api.openai.com/v1/audio/transcriptions",
    val sttModel: String = "gpt-transcribe",
    val imageEndpoint: String = "https://api.openai.com/v1/images/generations",
    val imageModel: String = "gpt-image-2.5-flare",
    val ttsEndpoint: String? = null,
    val ttsVoice: String = "joy",
    val imageProvider: String = "OPENAI",
) {
    init {
        require(imageProvider in setOf("OPENAI", "CODEX")) { "Invalid attachment image provider." }
        val uri = URI(endpoint)
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in setOf("", "/")) {
            "Attachment S3 endpoint must be an HTTP(S) origin without credentials."
        }
        for (address in listOfNotNull(sttEndpoint, imageEndpoint, ttsEndpoint)) {
            val media = URI(address)
            require(media.scheme in setOf("http", "https") && media.host != null && media.rawUserInfo == null &&
                media.rawQuery == null && media.rawFragment == null) { "Invalid attachment media endpoint." }
        }
        require(ttsVoice in setOf("joy", "sber", "afina", "eva")) { "Invalid Orion voice." }
        require(region.isNotBlank() && accessKey.isNotBlank() && secretKey.isNotBlank()) {
            "Attachment S3 region and credentials are required."
        }
        require(bucketPrefix.matches(Regex("[a-z][a-z0-9-]{0,29}-"))) { "Invalid attachment bucket prefix." }
        require(maxFileBytes in 1..100_000_000) { "Attachment file limit must be between 1 and 100000000 bytes." }
    }

    override fun toString(): String = "AttachmentStorageConfig(configured=true)"

    companion object {
        fun load(source: BackendConfigSource): AttachmentStorageConfig? {
            fun value(suffix: String) = source.value("SOUZ_ATTACHMENTS_$suffix", "souz.attachments.${suffix.lowercase()}")
                ?.trim()?.takeIf(String::isNotEmpty)
            val endpoint = value("S3_ENDPOINT") ?: return null
            return AttachmentStorageConfig(
                endpoint = endpoint,
                region = value("S3_REGION") ?: "us-east-1",
                bucketPrefix = value("BUCKET_PREFIX") ?: "souz-files-",
                accessKey = requireNotNull(value("S3_ACCESS_KEY")) { "Attachment S3 access key is required." },
                secretKey = requireNotNull(value("S3_SECRET_KEY")) { "Attachment S3 secret key is required." },
                maxFileBytes = value("MAX_FILE_BYTES")?.let {
                    requireNotNull(it.toIntOrNull()) { "Invalid attachment file limit." }
                } ?: 20_000_000,
                pathStyleAccess = value("S3_PATH_STYLE")?.let {
                    requireNotNull(it.toBooleanStrictOrNull()) { "Invalid attachment S3 path-style flag." }
                } ?: true,
                imageRoutes = value("VISION_ROUTES")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
                    ?: setOf("CODEX::gpt-6.1-sol", "CODEX::gpt-6-sol", "CODEX::gpt-5.6-sol"),
                visionModel = value("VISION_MODEL"),
                mediaApiKey = value("MEDIA_API_KEY"),
                sttEndpoint = value("STT_ENDPOINT") ?: "https://api.openai.com/v1/audio/transcriptions",
                sttModel = value("STT_MODEL") ?: "gpt-transcribe",
                imageEndpoint = value("IMAGE_ENDPOINT") ?: "https://api.openai.com/v1/images/generations",
                imageModel = value("IMAGE_MODEL") ?: if (value("IMAGE_PROVIDER") == "CODEX") "gpt-6.1-sol" else "gpt-image-2.5-flare",
                imageProvider = value("IMAGE_PROVIDER") ?: "OPENAI",
                ttsEndpoint = value("TTS_ENDPOINT"),
                ttsVoice = value("TTS_VOICE") ?: "joy",
            )
        }
    }
}
