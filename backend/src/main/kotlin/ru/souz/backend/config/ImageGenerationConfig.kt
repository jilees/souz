package ru.souz.backend.config

/** One deployment-wide image selection, independent of the chat model and attachment storage. */
internal fun loadImageModel(source: BackendConfigSource): String {
    val model = source.value("SOUZ_IMAGE_MODEL", "souz.image.model")?.trim()?.takeIf(String::isNotEmpty)
    val legacy = listOf("IMAGE_PROVIDER", "IMAGE_MODEL", "IMAGE_ENDPOINT").any {
        !source.value("SOUZ_ATTACHMENTS_$it", "souz.attachments.${it.lowercase()}").isNullOrBlank()
    }
    require(model != null || !legacy) {
        "Set SOUZ_IMAGE_MODEL / souz.image.model; attachment-specific image configuration is no longer used. " +
            "Use a named OpenAI connection for a custom image endpoint."
    }
    return model ?: "gpt-image-1"
}
