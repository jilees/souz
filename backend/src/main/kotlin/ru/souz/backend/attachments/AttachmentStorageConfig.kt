package ru.souz.backend.attachments

import java.net.URI
import java.net.URISyntaxException
import ru.souz.backend.config.BackendConfigSource

class AttachmentStorageConfig(
    val endpoint: String,
    val bucket: String,
    val accessKey: String,
    val secretKey: String,
    val region: String = "us-east-1",
    val pathStyleAccess: Boolean = true,
    val maxFileBytes: Int = 20_000_000,
    val maxConcurrentUploads: Int = 16,
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
        for (address in listOfNotNull(sttEndpoint, imageEndpoint, ttsEndpoint)) {
            val media = URI(address)
            require(media.scheme in setOf("http", "https") && media.host != null && media.rawUserInfo == null &&
                media.rawQuery == null && media.rawFragment == null) { "Invalid attachment media endpoint." }
        }
        require(ttsVoice in setOf("joy", "sber", "afina", "eva")) { "Invalid Orion voice." }

        val uri = try { URI(endpoint) } catch (_: URISyntaxException) {
            throw IllegalArgumentException("Invalid attachment S3 endpoint.")
        }
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in setOf("", "/")) {
            "Attachment S3 endpoint must be an HTTP(S) origin without credentials."
        }
        require(region.isNotBlank() && accessKey.isNotBlank() && secretKey.isNotBlank()) {
            "Attachment S3 region and credentials are required."
        }
        require(bucket.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) && !bucket.contains("..")) {
            "Invalid attachment bucket name."
        }
        require(maxFileBytes in 1..100_000_000) { "Attachment file limit must be between 1 and 100000000 bytes." }
        require(maxConcurrentUploads in 1..1_000) { "Attachment transfer concurrency must be between 1 and 1000." }
    }

    override fun toString(): String = "AttachmentStorageConfig(configured=true)"

    companion object {
        fun load(source: BackendConfigSource): AttachmentStorageConfig? {
            fun value(key: String) = source.value("SOUZ_ATTACHMENTS_$key", "souz.attachments.${key.lowercase()}")
                ?.trim()?.takeIf(String::isNotEmpty)
            fun required(key: String) = requireNotNull(value(key)) { "Attachment $key is required." }
            fun <T : Any> parsed(key: String, default: T, parse: (String) -> T?): T =
                value(key)?.let { requireNotNull(parse(it)) { "Invalid attachment $key." } } ?: default
            return AttachmentStorageConfig(
                endpoint = value("S3_ENDPOINT") ?: return null,
                bucket = required("S3_BUCKET"),
                accessKey = required("S3_ACCESS_KEY"),
                secretKey = required("S3_SECRET_KEY"),
                region = value("S3_REGION") ?: "us-east-1",
                pathStyleAccess = parsed("S3_PATH_STYLE", true, String::toBooleanStrictOrNull),
                maxFileBytes = parsed("MAX_FILE_BYTES", 20_000_000, String::toIntOrNull),
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
                maxConcurrentUploads = parsed("MAX_CONCURRENT_UPLOADS", 16, String::toIntOrNull),
            )
        }
    }
}
