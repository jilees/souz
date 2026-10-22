package ru.souz.backend.telegram

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.slf4j.LoggerFactory

interface TelegramBotApi {
    suspend fun downloadFile(token: String, fileId: String, maxBytes: Int): ByteArray =
        error("Telegram file download is unavailable.")

    suspend fun sendFile(token: String, chatId: Long, file: ru.souz.llms.FileContent, bytes: ByteArray, text: String = ""): Long =
        error("Telegram file delivery is unavailable.")
    suspend fun getMe(token: String): TelegramGetMeResponse

    suspend fun getUpdates(
        token: String,
        offset: Long?,
        timeoutSeconds: Int = 30,
        allowedUpdates: List<String> = listOf("message"),
    ): TelegramUpdatesResponse

    /** Sends the supplied Markdown through Telegram sendRichMessage. */
    suspend fun sendMessage(
        token: String,
        chatId: Long,
        text: String,
    )

    /** Fire-and-forget "typing…" indicator (`sendChatAction`); Telegram expires it after ~5s. */
    suspend fun sendChatAction(
        token: String,
        chatId: Long,
        action: String = "typing",
    )

    suspend fun deleteWebhook(
        token: String,
        dropPendingUpdates: Boolean = true,
    )
}

internal const val TELEGRAM_RICH_TEXT_LIMIT = 32_768
internal const val TELEGRAM_DOCUMENT_TEXT_LIMIT = TELEGRAM_RICH_TEXT_LIMIT - 64

internal class HttpTelegramBotApi(
    private val apiBaseUrl: String = "https://api.telegram.org",
) : TelegramBotApi {
    private val mapper = jacksonObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

    override suspend fun downloadFile(token: String, fileId: String, maxBytes: Int): ByteArray {
        require(maxBytes in 1..100_000_000)
        val response = request(token, "getFile", mapOf("file_id" to fileId)).bodyAs<TelegramFileResponse>()
        if (!response.ok) throw TelegramBotApiTransportException("Telegram file lookup failed.")
        val file = response.result ?: throw TelegramBotApiTransportException("Telegram file is missing.")
        if ((file.fileSize ?: 0) > maxBytes) throw ru.souz.backend.attachments.AttachmentException("attachment_too_large")
        val path = file.filePath ?: throw TelegramBotApiTransportException("Telegram file path is missing.")
        require(path.matches(Regex("[a-zA-Z0-9_./-]+")) && !path.startsWith('/') && path.split('/').none { it == ".." })
        return kotlinx.coroutines.withTimeout(120_000) {
            runInterruptible(Dispatchers.IO) {
                val download = client.send(HttpRequest.newBuilder(URI("$apiBaseUrl/file/bot$token/$path"))
                    .timeout(Duration.ofSeconds(120)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                download.body().use { input ->
                    if (download.statusCode() != 200) throw TelegramBotApiTransportException("Telegram file download failed.")
                    val bytes = input.readNBytes(maxBytes + 1)
                    if (bytes.size > maxBytes) throw ru.souz.backend.attachments.AttachmentException("attachment_too_large")
                    bytes
                }
            }
        }
    }

    override suspend fun sendFile(token: String, chatId: Long, file: ru.souz.llms.FileContent, bytes: ByteArray, text: String): Long {
        val part = when (file.presentation) {
            ru.souz.llms.FilePresentation.image -> "photo"
            ru.souz.llms.FilePresentation.voice -> "voice"
            ru.souz.llms.FilePresentation.audio -> "audio"
            else -> "document"
        }
        require(text.isEmpty() || part == "document" && text.length <= TELEGRAM_DOCUMENT_TEXT_LIMIT)
        val method = if (text.isNotEmpty()) "sendRichMessage" else "send" + part.replaceFirstChar { it.uppercase() }
        val fields = mutableMapOf("chat_id" to chatId.toString())
        if (text.isNotEmpty()) fields["rich_message"] = mapper.writeValueAsString(mapOf(
            "markdown" to "$text\n\n![](tg://document?id=attachment)",
            "media" to listOf(mapOf("id" to "attachment", "media" to mapOf("type" to "document", "media" to "attach://document"))),
        ))
        val uploadBytes = if (part == "photo") runInterruptible(Dispatchers.Default) { telegramPhoto(bytes) } else bytes
        val uploadFile = if (part == "photo") file.copy(
            name = file.name.substringBeforeLast('.', file.name) + ".jpg", mimeType = "image/jpeg", sizeBytes = uploadBytes.size.toLong(),
        ) else file
        val multipart = ru.souz.backend.attachments.AttachmentMultipart(part, uploadFile, uploadBytes, fields)
        return runInterruptible(Dispatchers.IO) {
            var httpStatus: Int? = null
            try {
                val response = client.send(HttpRequest.newBuilder(URI("$apiBaseUrl/bot$token/$method"))
                    .timeout(Duration.ofSeconds(120)).header("Content-Type", multipart.contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body)).build(), HttpResponse.BodyHandlers.ofString())
                httpStatus = response.statusCode()
                val node = mapper.readTree(response.body())
                if (httpStatus != 200 || node?.get("ok")?.asBoolean() != true) {
                    throw TelegramBotApiHttpException(method, httpStatus, node?.get("error_code")?.intValue(),
                        node?.get("description")?.asText()?.safeDeliveryDescription(token), null)
                }
                node["result"]?.get("message_id")?.longValue()
                    ?: throw TelegramBotApiTransportException("Telegram delivery receipt is missing.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val rejection = error as? TelegramBotApiHttpException
                logger.warn("Telegram file delivery failed method={} httpStatus={} telegramErrorCode={} description={} mimeType={} sizeBytes={} errorType={}",
                    method, httpStatus, rejection?.telegramErrorCode, rejection?.description,
                    uploadFile.mimeType, uploadBytes.size, error.javaClass.simpleName)
                throw error
            }
        }
    }

    override suspend fun getMe(token: String): TelegramGetMeResponse =
        request(
            token = token,
            methodName = "getMe",
            formParameters = emptyMap(),
        ).bodyAs<TelegramGetMeResponse>()
            .normalizeHttpStatus()

    override suspend fun getUpdates(
        token: String,
        offset: Long?,
        timeoutSeconds: Int,
        allowedUpdates: List<String>,
    ): TelegramUpdatesResponse =
        request(
            token = token,
            methodName = "getUpdates",
            formParameters = buildMap {
                offset?.let { put("offset", it.toString()) }
                put("timeout", timeoutSeconds.toString())
                put("allowed_updates", mapper.writeValueAsString(allowedUpdates))
            },
        ).bodyAs<TelegramUpdatesResponse>()
            .normalizeHttpStatus()

    override suspend fun sendMessage(
        token: String,
        chatId: Long,
        text: String,
    ) {
        val response = request(
            token = token,
            methodName = "sendRichMessage",
            formParameters = mapOf(
                "chat_id" to chatId.toString(),
                "rich_message" to mapper.writeValueAsString(mapOf("markdown" to text)),
            ),
        ).bodyAs<TelegramMethodAckResponse>()
            .normalizeHttpStatus()
        if (!response.ok) {
            throw TelegramBotApiHttpException(
                methodName = "sendRichMessage",
                statusCode = response.errorCode ?: 500,
                telegramErrorCode = response.errorCode,
                description = response.description,
                parameters = response.parameters,
            )
        }
    }

    override suspend fun sendChatAction(
        token: String,
        chatId: Long,
        action: String,
    ) {
        val response = request(
            token = token,
            methodName = "sendChatAction",
            formParameters = mapOf(
                "chat_id" to chatId.toString(),
                "action" to action,
            ),
        ).bodyAs<TelegramMethodAckResponse>()
            .normalizeHttpStatus()
        if (!response.ok) {
            throw TelegramBotApiHttpException(
                methodName = "sendChatAction",
                statusCode = response.errorCode ?: 500,
                telegramErrorCode = response.errorCode,
                description = response.description,
                parameters = response.parameters,
            )
        }
    }

    override suspend fun deleteWebhook(
        token: String,
        dropPendingUpdates: Boolean,
    ) {
        val response = request(
            token = token,
            methodName = "deleteWebhook",
            formParameters = mapOf(
                "drop_pending_updates" to dropPendingUpdates.toString(),
            ),
        ).bodyAs<TelegramMethodAckResponse>()
            .normalizeHttpStatus()
        if (!response.ok) {
            throw TelegramBotApiHttpException(
                methodName = "deleteWebhook",
                statusCode = response.errorCode ?: 500,
                telegramErrorCode = response.errorCode,
                description = response.description,
                parameters = response.parameters,
            )
        }
    }

    private suspend fun request(
        token: String,
        methodName: String,
        formParameters: Map<String, String>,
    ): TelegramRawResponse =
        try {
            runInterruptible(Dispatchers.IO) {
                val request = buildRequest(token, methodName, formParameters)
                client.send(request, HttpResponse.BodyHandlers.ofString()).let { response ->
                    TelegramRawResponse(
                        httpStatus = response.statusCode(),
                        body = response.body(),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpTimeoutException) {
            throw TelegramBotApiTransportException("Telegram request timed out.", e)
        } catch (e: IOException) {
            throw TelegramBotApiTransportException("Telegram network request failed.", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TelegramBotApiTransportException("Telegram request was interrupted.", e)
        }

    private fun buildRequest(
        token: String,
        methodName: String,
        formParameters: Map<String, String>,
    ): HttpRequest {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("$apiBaseUrl/bot$token/$methodName"))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))

        return if (formParameters.isEmpty()) {
            requestBuilder.GET().build()
        } else {
            requestBuilder
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formParameters.toFormBody()))
                .build()
        }
    }

    private inline fun <reified T> TelegramRawResponse.bodyAs(): T =
        try {
            mapper.readValue(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TelegramBotApiTransportException("Telegram response could not be parsed.", e)
        }

    private companion object {
        val logger = LoggerFactory.getLogger(HttpTelegramBotApi::class.java)
        const val CONNECT_TIMEOUT_SECONDS: Long = 10L
        const val REQUEST_TIMEOUT_SECONDS: Long = 35L
    }
}

private fun String.safeDeliveryDescription(token: String): String =
    replace(token, "[REDACTED]")
        .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "[URL]")
        .replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ")
        .take(512)

internal open class TelegramBotApiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class TelegramBotApiTransportException(
    message: String,
    cause: Throwable? = null,
) : TelegramBotApiException(message, cause)

internal class TelegramBotApiHttpException(
    val methodName: String,
    val statusCode: Int,
    val telegramErrorCode: Int?,
    val description: String?,
    val parameters: TelegramResponseParameters?,
) : TelegramBotApiException(description ?: "Telegram API request failed.")

private data class TelegramRawResponse(
    val httpStatus: Int,
    val body: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TelegramMethodAckResponse(
    val ok: Boolean,
    val description: String? = null,
    @param:JsonProperty("error_code")
    val errorCode: Int? = null,
    val parameters: TelegramResponseParameters? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramGetMeResponse(
    val ok: Boolean,
    val result: TelegramUser? = null,
    val description: String? = null,
    @param:JsonProperty("error_code")
    val errorCode: Int? = null,
    val parameters: TelegramResponseParameters? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUser(
    val id: Long,
    @param:JsonProperty("is_bot")
    val isBot: Boolean? = null,
    @param:JsonProperty("first_name")
    val firstName: String? = null,
    @param:JsonProperty("last_name")
    val lastName: String? = null,
    val username: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUpdatesResponse(
    val ok: Boolean,
    val result: List<TelegramUpdate> = emptyList(),
    val description: String? = null,
    @param:JsonProperty("error_code")
    val errorCode: Int? = null,
    val parameters: TelegramResponseParameters? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUpdate(
    @param:JsonProperty("update_id")
    val updateId: Long,
    val message: TelegramMessage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramMessage(
    @param:JsonProperty("message_id")
    val messageId: Long,
    val from: TelegramUser? = null,
    val chat: TelegramChat,
    val text: String? = null,
    val caption: String? = null,
    val document: TelegramFile? = null,
    val photo: List<TelegramFile> = emptyList(),
    val voice: TelegramFile? = null,
    val audio: TelegramFile? = null,
    @param:JsonProperty("media_group_id") val mediaGroupId: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramFile(
    @param:JsonProperty("file_id") val fileId: String,
    @param:JsonProperty("file_unique_id") val fileUniqueId: String? = null,
    @param:JsonProperty("file_size") val fileSize: Long? = null,
    @param:JsonProperty("file_name") val fileName: String? = null,
    @param:JsonProperty("mime_type") val mimeType: String? = null,
    @param:JsonProperty("file_path") val filePath: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val duration: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TelegramFileResponse(val ok: Boolean, val result: TelegramFile? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramChat(
    val id: Long,
    val type: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramResponseParameters(
    @param:JsonProperty("retry_after")
    val retryAfter: Int? = null,
)

private fun Map<String, String>.toFormBody(): String =
    entries.joinToString(separator = "&") { (key, value) ->
        "${key.urlEncode()}=${value.urlEncode()}"
    }

private fun String.urlEncode(): String =
    URLEncoder.encode(this, Charsets.UTF_8)

private fun TelegramGetMeResponse.normalizeHttpStatus(): TelegramGetMeResponse =
    if (!ok && errorCode == null) copy(errorCode = 400) else this

private fun TelegramUpdatesResponse.normalizeHttpStatus(): TelegramUpdatesResponse =
    if (!ok && errorCode == null) copy(errorCode = 400) else this

private fun TelegramMethodAckResponse.normalizeHttpStatus(): TelegramMethodAckResponse =
    if (!ok && errorCode == null) copy(errorCode = 400) else this
