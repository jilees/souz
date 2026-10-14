package ru.souz.backend.attachments

import ru.souz.backend.config.BackendConfigSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AttachmentImageConfigTest {
    @Test
    fun `Codex generation defaults to Sol without requiring or replacing the STT key`() {
        val config = load(mapOf("IMAGE_PROVIDER" to "CODEX"))
        assertEquals("CODEX", config.imageProvider)
        assertEquals("gpt-6.1-sol", config.imageModel)
        assertNull(config.mediaApiKey)
        val withStt = load(mapOf("IMAGE_PROVIDER" to "CODEX", "MEDIA_API_KEY" to "cloud-key", "STT_MODEL" to "openai/whisper-large-v3"))
        assertEquals("cloud-key", withStt.mediaApiKey)
        assertEquals("openai/whisper-large-v3", withStt.sttModel)
        assertEquals("gpt-6.1-sol", withStt.imageModel)
    }

    @Test
    fun `existing image provider defaults and explicit models are preserved`() {
        assertEquals("OPENAI", load(emptyMap()).imageProvider)
        assertEquals("gpt-image-2.5-flare", load(emptyMap()).imageModel)
        assertEquals("gpt-6-sol", load(mapOf("IMAGE_PROVIDER" to "CODEX", "IMAGE_MODEL" to "gpt-6-sol")).imageModel)
        assertFailsWith<IllegalArgumentException> { load(mapOf("IMAGE_PROVIDER" to "unsupported")) }
    }

    private fun load(extra: Map<String, String>): AttachmentStorageConfig {
        val values = mapOf("S3_ENDPOINT" to "http://127.0.0.1:9000", "S3_BUCKET" to "attachments-test", "S3_ACCESS_KEY" to "test", "S3_SECRET_KEY" to "test") + extra
        return requireNotNull(AttachmentStorageConfig.load(object : BackendConfigSource {
            override fun env(key: String): String? = values[key.removePrefix("SOUZ_ATTACHMENTS_")]
            override fun property(key: String): String? = null
        }))
    }
}
