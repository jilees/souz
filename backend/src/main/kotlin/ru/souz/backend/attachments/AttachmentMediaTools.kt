package ru.souz.backend.attachments

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory

class AttachmentMediaTools(private val media: AttachmentMedia) : AgentToolCatalog {
    override val toolsByCategory = mapOf(ToolCategory.FILES to listOf(
        tool("TranscribeAttachment", "fileId", "Transcribe an audio attachment to text."),
        tool("SynthesizeVoice", "text", "Create an Orion voice file, up to 4000 UTF-16 units. Select the returned file with AttachFileToReply to send it."),
        tool("GenerateAttachmentImage", "prompt", "Generate a saved image using the configured media provider. Select the returned file with AttachFileToReply to send it."),
    ).associateBy { it.fn.name })

    private fun tool(name: String, parameter: String, description: String) = object : LLMToolSetup {
        override val fn = LLMRequest.Function(name = name, description = description,
            parameters = LLMRequest.Parameters("object", mapOf(parameter to LLMRequest.Property("string")), listOf(parameter)))
        override suspend fun invoke(functionCall: LLMResponse.FunctionCall) = invoke(functionCall, ToolInvocationMeta.localDefault())
        override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message = try {
            val value = functionCall.arguments[parameter] as? String ?: throw AttachmentException("attachment_invalid_arguments")
            if (name == "TranscribeAttachment") {
                LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(mapOf(
                    "fileId" to value, "transcript" to media.transcribe(meta, value))), name = name)
            } else {
                val file = if (name == "SynthesizeVoice") media.synthesize(meta, value) else media.generateImage(meta, value)
                LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(file), name = name, files = listOf(file))
            }
        } catch (error: AttachmentException) {
            LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(mapOf("error" to error.code)), name = name)
        }
    }
}
