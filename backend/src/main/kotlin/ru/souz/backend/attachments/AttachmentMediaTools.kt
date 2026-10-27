package ru.souz.backend.attachments

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.llm.BackendImageModel
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory

class AttachmentMediaTools(private val media: AttachmentMedia) {
    val catalog = AgentToolCatalog(mapOf(ToolCategory.FILES to listOf(
        tool("TranscribeAttachment", "fileId", "Transcribe an audio attachment to text."),
        tool("SynthesizeVoice", "text", "Create an Orion voice file, up to 4000 UTF-16 units. Select the returned file with AttachFileToReply to send it."),
        // The process catalog declares availability; the runtime binds generation per execution.
        imageTool(),
    )))

    internal fun imageTool(gateway: ImageGenerationGateway? = null, model: BackendImageModel? = null) = tool(
        "GenerateAttachmentImage", "prompt",
        "Generate an image using the configured image model and save it as an attachment. Select the returned file with AttachFileToReply to send it.",
        gateway, model,
    )

    private fun tool(name: String, parameter: String, description: String,
        imageGateway: ImageGenerationGateway? = null, model: BackendImageModel? = null,
    ) = object : LLMToolSetup {
        override val fn = LLMRequest.Function(name = name, description = description,
            parameters = LLMRequest.Parameters("object", mapOf(parameter to LLMRequest.Property("string")), listOf(parameter)))
        override suspend fun invoke(functionCall: LLMResponse.FunctionCall) = invoke(functionCall, ToolInvocationMeta.localDefault())
        override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message = try {
            val value = functionCall.arguments[parameter] as? String ?: throw AttachmentException("attachment_invalid_arguments")
            if (name == "TranscribeAttachment") {
                LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(mapOf(
                    "fileId" to value, "transcript" to media.transcribe(meta, value))), name = name)
            } else {
                val file = if (name == "SynthesizeVoice") media.synthesize(meta, value) else media.generateImage(meta, value,
                    model ?: throw AttachmentException("attachment_image_generation_unconfigured"),
                    imageGateway ?: throw AttachmentException("attachment_image_generation_unconfigured"))
                LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(file), name = name, files = listOf(file))
            }
        } catch (error: AttachmentException) {
            LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(mapOf("error" to error.code)), name = name)
        }
    }
}
