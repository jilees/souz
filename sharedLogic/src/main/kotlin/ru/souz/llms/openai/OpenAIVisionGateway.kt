package ru.souz.llms.openai

import java.nio.file.Files
import java.util.Base64
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.runtime.DEFAULT_MAX_VISION_IMAGE_BYTES
import ru.souz.llms.runtime.VisionGateway
import ru.souz.llms.runtime.VisionImage
import ru.souz.llms.runtime.VisionInput
import ru.souz.llms.runtime.requestVisionText

class OpenAIVisionGateway(
    private val settingsProvider: SettingsProvider,
    private val chatApi: LLMChatAPI,
    private val maxImageBytes: Long = DEFAULT_MAX_VISION_IMAGE_BYTES,
) : VisionGateway {

    override suspend fun analyze(input: VisionInput): String {
        val images = input.images
        images.forEach(::requireSizeWithinLimit)
        val dataUrls = images.map { image ->
            "data:${image.mimeType};base64,${Base64.getEncoder().encodeToString(Files.readAllBytes(image.imagePath))}"
        }
        return requestVisionText(
            settingsProvider = settingsProvider,
            input = input,
            attachments = dataUrls,
            failurePrefix = "OpenAI image understanding failed",
            chatRequest = chatApi::message,
        )
    }

    private fun requireSizeWithinLimit(input: VisionImage) {
        if (input.sizeBytes > maxImageBytes) {
            throw IllegalArgumentException(
                "OpenAI image input is too large: ${input.sizeBytes} bytes exceeds limit of $maxImageBytes bytes",
            )
        }
    }
}
