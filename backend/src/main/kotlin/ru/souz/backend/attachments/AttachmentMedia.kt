package ru.souz.backend.attachments

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import ru.souz.backend.llm.BackendImageModel
import ru.souz.llms.FileContent
import ru.souz.llms.FilePresentation
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.runtime.ImageGenerationInput
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.web.internal.WebHttpSupport

/** Server-owned media credentials; conversion uses the invoking user's existing sandbox. */
class AttachmentMedia(
    private val config: AttachmentStorageConfig,
    private val storage: AttachmentStorage,
    private val client: HttpClient,
    private val sandboxResolver: ToolInvocationRuntimeSandboxResolver,
    private val operations: AttachmentMediaOperations,
) {
    suspend fun cachedTranscript(userId: String, fileId: String): String? {
        val id = attachmentId(fileId)
        storage.get(userId, id)
        return storage.find(userId, "transcript:$id:${config.sttModel}")?.let { storage.read(userId, it.id).decodeToString() }
    }

    suspend fun transcribe(meta: ToolInvocationMeta, fileId: String): String = safeMedia {
        val id = attachmentId(fileId)
        val file = storage.get(meta.userId, id)
        if (!file.mimeType.startsWith("audio/")) throw AttachmentException("attachment_audio_required")
        val key = "transcript:$id:${config.sttModel}"
        storage.find(meta.userId, key)?.let { return@safeMedia storage.read(meta.userId, it.id).decodeToString() }
        val apiKey = config.mediaApiKey ?: throw AttachmentException("attachment_stt_unconfigured")
        operations.run(meta.userId, "$key:${meta.requestId ?: "automatic"}") {
            val sandbox = sandboxResolver.resolve(meta)
            val fs = sandbox.fileSystem
            val source = storage.prepare(meta.userId, id, fs)
            val directory = fs.resolvePath("${fs.runtimePaths.workspaceRootPath}/attachment-work/${java.util.UUID.randomUUID()}")
            fs.createDirectory(directory)
            try {
                val probe = sandbox.commandExecutor.execute(SandboxCommandRequest(command = listOf(
                    "ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", "-protocol_whitelist", "file,pipe", "-format_whitelist", "wav,mp3,ogg,flac,mov,matroska,webm,aac", source,
                ), timeoutMillis = 30_000))
                val seconds = probe.stdout.trim().toDoubleOrNull()
                if (probe.exitCode != 0 || seconds == null || !seconds.isFinite() || seconds <= 0) {
                    throw AttachmentException("attachment_audio_invalid")
                }
                if (seconds > 600) throw AttachmentException("attachment_audio_too_long")
                val target = "${directory.path}/speech.wav"
                val converted = sandbox.commandExecutor.execute(SandboxCommandRequest(command = listOf(
                    "ffmpeg", "-nostdin", "-v", "error", "-threads", "1", "-protocol_whitelist", "file,pipe", "-format_whitelist", "wav,mp3,ogg,flac,mov,matroska,webm,aac", "-i", source, "-t", "601", "-vn",
                    "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-fs", "20000000", target,
                ), timeoutMillis = 60_000))
                if (converted.exitCode != 0 || converted.timedOut) throw AttachmentException("attachment_audio_conversion_failed")
                // Ten minutes of mono 16 kHz PCM fits 20 MB even when the compressed-input limit is lower.
                // Applying that lower limit to conversion would silently truncate otherwise valid audio.
                val bytes = fs.openInputStream(fs.resolveExistingFile(target)).use { it.readAttachmentBytes(20_000_000) }
                javax.sound.sampled.AudioSystem.getAudioInputStream(bytes.inputStream()).use { audio ->
                    val duration = audio.frameLength.toDouble() / audio.format.frameRate
                    if (!duration.isFinite() || duration <= 0 || duration > 600) throw AttachmentException("attachment_audio_too_long")
                }
                val response = post(config.sttEndpoint, MultiPartFormDataContent(formData {
                    append("model", config.sttModel)
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentType, "audio/wav")
                        append(HttpHeaders.ContentDisposition, "filename=\"speech.wav\"")
                    })
                }), apiKey = apiKey, limit = 1_000_000)
                val text = restJsonMapper.readTree(response)["text"]?.asText()?.trim().orEmpty()
                if (text.isEmpty()) throw AttachmentException("attachment_transcript_empty")
                val saved = storage.save(meta.userId, key, "transcript.txt", text.byteInputStream())
                storage.read(meta.userId, saved.id).decodeToString()
            } finally {
                fs.delete(directory, recursively = true)
            }
        }
    }

    suspend fun synthesize(meta: ToolInvocationMeta, text: String): FileContent = safeMedia {
        val endpoint = config.ttsEndpoint ?: throw AttachmentException("attachment_tts_unconfigured")
        if (text.isBlank() || text.length > 4000) throw AttachmentException("attachment_tts_text_limit")
        val body = restJsonMapper.writeValueAsBytes(mapOf("model" to "orion-tts", "voice" to config.ttsVoice,
            "input" to text, "response_format" to "opus"))
        if (body.size > 65_536) throw AttachmentException("attachment_tts_text_limit")
        val key = "tts:${requireNotNull(meta.requestId)}:${body.attachmentChecksum()}"
        storage.find(meta.userId, key)?.let { return@safeMedia it.toContent(FilePresentation.voice) }
        require(meta.userId.isNotBlank())
        operations.run(meta.userId, key) {
            val bytes = post(endpoint, body, timeoutMs = 75_000, expectedTypes = setOf("audio/ogg", "application/ogg", "audio/opus"))
            if (bytes.size < 4 || !bytes.copyOfRange(0, 4).contentEquals("OggS".toByteArray()) ||
                !bytes.take(256).toByteArray().toString(Charsets.ISO_8859_1).contains("OpusHead")) {
                throw AttachmentException("attachment_tts_invalid_audio")
            }
            storage.save(meta.userId, key, "voice.ogg", bytes.inputStream()).toContent(FilePresentation.voice)
        }
    }

    internal suspend fun generateImage(
        meta: ToolInvocationMeta, prompt: String, model: BackendImageModel, gateway: ImageGenerationGateway,
    ): FileContent = safeMedia {
        val normalizedPrompt = prompt.trim()
        if (normalizedPrompt.isEmpty() || normalizedPrompt.length > 32_000) throw AttachmentException("attachment_image_prompt_limit")
        // Preserve existing built-in route claims across upgrades; named endpoints have distinct identities.
        val body = restJsonMapper.writeValueAsBytes(buildMap {
            put("model", model.modelId)
            put("prompt", prompt)
            put("size", "1024x1024")
            put("output_format", "png")
            model.connection?.let { put("endpoint", it.baseUrl) }
        })
        val key = "image:${model.provider.name}:${requireNotNull(meta.requestId)}:${body.attachmentChecksum()}"
        storage.find(meta.userId, key)?.let { return@safeMedia it.toContent(FilePresentation.image) }
        require(meta.userId.isNotBlank())
        operations.run(meta.userId, key) {
            val bytes = gateway.generate(ImageGenerationInput(prompt = normalizedPrompt)).bytes
            if (!attachmentMime(bytes, "image.png").startsWith("image/")) throw AttachmentException("attachment_image_invalid")
            storage.save(meta.userId, key, "image.png", bytes.inputStream()).toContent(FilePresentation.image)
        }
    }

    private suspend fun post(endpoint: String, body: Any, apiKey: String? = null,
        timeoutMs: Long = 180_000, limit: Int = config.maxFileBytes,
        expectedTypes: Set<String> = setOf("application/json")): ByteArray =
        client.preparePost(endpoint) {
            timeout { requestTimeoutMillis = timeoutMs }
            if (apiKey != null) header(HttpHeaders.Authorization, "Bearer $apiKey")
            if (body is ByteArray) header(HttpHeaders.ContentType, "application/json")
            setBody(body)
        }.execute { response ->
            if (response.status.value !in 200..299) throw AttachmentException("attachment_media_http_${response.status.value}")
            val type = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
            if (type !in expectedTypes) throw AttachmentException("attachment_media_invalid_type")
            WebHttpSupport(sharedWebHttpClient = client).readLimitedBinaryBody(response.bodyAsChannel(), limit, url = "media response")
        }
}

internal suspend fun <T> safeMedia(block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: AttachmentException) {
    throw error
} catch (_: Exception) {
    throw AttachmentException("attachment_media_failed")
}
