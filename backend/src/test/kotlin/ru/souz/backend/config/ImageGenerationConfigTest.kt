package ru.souz.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import ru.souz.backend.common.BackendConfigurationException
import ru.souz.backend.llm.resolveImageModel
import ru.souz.llms.LlmProvider

class ImageGenerationConfigTest {
    @Test
    fun `image and vision models select providers independently of S3 STT and chat`() {
        for (name in listOf("CodexGpt61Sol", "gpt-6.1-sol", " CodexGpt6Sol ")) {
            assertEquals(LlmProvider.CODEX, resolveImageModel(loadImageModel(source(mapOf("SOUZ_IMAGE_MODEL" to name))), emptyMap()).provider)
        }
        assertEquals("gpt-image-1", loadImageModel(source(mapOf("SOUZ_ATTACHMENTS_MEDIA_API_KEY" to "stt-only"))))
        assertEquals(LlmProvider.OPENAI, resolveImageModel("gpt-image-2.5-flare", emptyMap()).provider)
        val connection = NamedLlmModel("custom-image", "https://images.test/v1", "image-key")
        val resolved = resolveImageModel("illustrator", mapOf("illustrator" to connection))
        assertEquals(connection, resolved.connection)
        assertEquals("custom-image", resolved.modelId)
        val codex = resolveImageModel("new-codex", mapOf("new-codex" to NamedLlmModel("future-model-without-enum", provider = LlmProvider.CODEX)))
        assertEquals(LlmProvider.CODEX, codex.provider)
        assertEquals("future-model-without-enum", codex.modelId)
        assertFailsWith<IllegalStateException> { resolveImageModel("unknown-model", emptyMap()) }

        val vision = NamedLlmConfig(models = mapOf("reader" to connection),
            cascades = mapOf("visual" to listOf("reader", "CodexGpt61Sol"), "invalid" to listOf("QwenMax")))
        for (selection in listOf(null, "reader", "visual", "CodexGpt61Sol")) {
            assertEquals(selection, validateVisionModel(selection, vision))
        }
        for (selection in listOf("", "unknown", "QwenMax", "invalid", "openai-compatible-custom")) {
            assertFailsWith<BackendConfigurationException> { validateVisionModel(selection, vision) }
        }
    }

    @Test
    fun `legacy image configuration requires explicit migration rather than a provider fallback`() {
        assertFailsWith<IllegalArgumentException> { loadImageModel(source(mapOf("SOUZ_ATTACHMENTS_IMAGE_PROVIDER" to "CODEX"))) }
        assertEquals("CodexGpt61Sol", loadImageModel(source(mapOf(
            "SOUZ_ATTACHMENTS_IMAGE_PROVIDER" to "CODEX", "SOUZ_IMAGE_MODEL" to "CodexGpt61Sol",
        ))))
        assertEquals("CodexGpt61Sol", loadImageModel(object : BackendConfigSource {
            override fun env(key: String): String? = null
            override fun property(key: String): String? = "CodexGpt61Sol".takeIf { key == "souz.image.model" }
        }))
    }

    private fun source(values: Map<String, String>) = object : BackendConfigSource {
        override fun env(key: String): String? = values[key]
        override fun property(key: String): String? = null
    }
}
