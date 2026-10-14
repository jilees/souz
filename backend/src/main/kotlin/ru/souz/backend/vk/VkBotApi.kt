package ru.souz.backend.vk

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import ru.souz.tool.web.internal.WebToolSupport

interface VkBotApi {
    suspend fun downloadFile(url: String, maxBytes: Int): ByteArray = error("VK file download is unavailable.")
    suspend fun sendFile(groupToken: String, peerId: Long, file: ru.souz.llms.FileContent, bytes: ByteArray): Long =
        error("VK file delivery is unavailable.")
    suspend fun getGroupInfo(groupToken: String): VkGroup
    suspend fun getLongPollServer(groupToken: String, groupId: Long): VkLongPollServer
    suspend fun getUserInfo(groupToken: String, userId: Long): VkUser?
    suspend fun pollLongPoll(server: String, key: String, ts: String, waitSeconds: Int = 25): VkLongPollResponse
    suspend fun sendMessage(groupToken: String, peerId: Long, text: String, format: List<VkFormatItem> = emptyList())
    suspend fun setActivity(groupToken: String, peerId: Long, groupId: Long)
}

internal class HttpVkBotApi(
    private val baseUrl: String = "https://api.vk.com/method",
) : VkBotApi, AutoCloseable {
    private val mapper = jacksonObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val userAgent = WebToolSupport().userAgent

    override suspend fun getGroupInfo(groupToken: String): VkGroup {
        val response = method(groupToken, "groups.getById")
        val group = (if (response.isArray) response else response.path("groups")).firstOrNull()
            ?: throw VkBotApiException(5)
        return mapper.treeToValue(group, VkGroup::class.java)
    }

    override suspend fun getLongPollServer(groupToken: String, groupId: Long): VkLongPollServer =
        mapper.treeToValue(
            method(groupToken, "groups.getLongPollServer", "group_id" to groupId.toString()),
            VkLongPollServer::class.java,
        )

    override suspend fun getUserInfo(groupToken: String, userId: Long): VkUser? =
        method(groupToken, "users.get", "user_ids" to userId.toString()).firstOrNull()
            ?.let { mapper.treeToValue(it, VkUser::class.java) }

    override suspend fun pollLongPoll(server: String, key: String, ts: String, waitSeconds: Int): VkLongPollResponse =
        mapper.treeToValue(
            request(HttpRequest.newBuilder(URI.create("$server?${form(
                "act" to "a_check", "key" to key, "ts" to ts, "wait" to waitSeconds.toString(),
            )}")).GET()),
            VkLongPollResponse::class.java,
        )

    override suspend fun sendMessage(groupToken: String, peerId: Long, text: String, format: List<VkFormatItem>) {
        val parameters = buildList {
            add("peer_id" to peerId.toString())
            add("message" to text)
            add("random_id" to Random.nextInt(1, Int.MAX_VALUE).toString())
            if (format.isNotEmpty()) {
                add("format_data" to mapper.writeValueAsString(mapOf("version" to 1, "items" to format)))
            }
        }
        method(groupToken, "messages.send", *parameters.toTypedArray())
    }

    override suspend fun setActivity(groupToken: String, peerId: Long, groupId: Long) {
        method(
            groupToken, "messages.setActivity", "peer_id" to peerId.toString(),
            "group_id" to groupId.toString(), "type" to "typing",
        )
    }

    override suspend fun downloadFile(url: String, maxBytes: Int): ByteArray = kotlinx.coroutines.withTimeout(120_000) {
        require(maxBytes in 1..100_000_000)
        runInterruptible(Dispatchers.IO) {
            val response = client.send(HttpRequest.newBuilder(vkMediaUri(url)).timeout(Duration.ofSeconds(120))
                .GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            response.body().use {
                if (response.statusCode() != 200) throw IOException("VK file download failed.")
                val bytes = it.readNBytes(maxBytes + 1)
                if (bytes.size > maxBytes) throw ru.souz.backend.attachments.AttachmentException("attachment_too_large")
                bytes
            }
        }
    }

    override suspend fun sendFile(groupToken: String, peerId: Long, file: ru.souz.llms.FileContent, bytes: ByteArray): Long {
        val photo = file.presentation == ru.souz.llms.FilePresentation.image
        val type = if (file.presentation == ru.souz.llms.FilePresentation.voice) "audio_message" else "doc"
        val server = if (photo) method(groupToken, "photos.getMessagesUploadServer", "peer_id" to peerId.toString())
            else method(groupToken, "docs.getMessagesUploadServer", "peer_id" to peerId.toString(), "type" to type)
        val uri = vkMediaUri(server.path("upload_url").asText())
        val multipart = ru.souz.backend.attachments.AttachmentMultipart(if (photo) "photo" else "file", file, bytes)
        val uploaded = request(HttpRequest.newBuilder(uri).header("Content-Type", multipart.contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body)))
        val saved = if (photo) {
            if (!uploaded.hasNonNull("photo")) throw IOException("Unsupported VK photo upload response.")
            method(groupToken, "photos.saveMessagesPhoto", "photo" to uploaded.path("photo").asText(),
                "server" to uploaded.path("server").asText(), "hash" to uploaded.path("hash").asText()).firstOrNull()
        } else {
            if (!uploaded.hasNonNull("file")) throw IOException("Missing VK uploaded document.")
            method(groupToken, "docs.save", "file" to uploaded.path("file").asText(), "title" to file.name).get(type)
        } ?: throw IOException("Missing VK saved attachment.")
        val prefix = if (photo) "photo" else "doc"
        val owner = saved.path("owner_id").asLong()
        val id = saved.path("id").asLong()
        if (owner == 0L || id <= 0L) throw IOException("Invalid VK saved attachment.")
        val access = saved.path("access_key").asText("").takeIf { it.isNotEmpty() }?.let { "_$it" }.orEmpty()
        return method(groupToken, "messages.send", "peer_id" to peerId.toString(),
            "random_id" to Random.nextInt(1, Int.MAX_VALUE).toString(), "attachment" to "$prefix${owner}_$id$access").asLong().also {
                if (it <= 0) throw IOException("VK delivery receipt is missing.")
            }
    }

    private suspend fun method(token: String, name: String, vararg parameters: Pair<String, String>): JsonNode {
        val body = request(
            HttpRequest.newBuilder(URI.create("$baseUrl/$name"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(*parameters, "access_token" to token, "v" to "5.199"))),
        )
        // VK error bodies may echo credentials; only retain the numeric code.
        if (body.hasNonNull("error")) throw VkBotApiException(body.path("error").path("error_code").asInt())
        return body.get("response") ?: throw IOException("Missing VK response.")
    }

    private suspend fun request(builder: HttpRequest.Builder): JsonNode = runInterruptible(Dispatchers.IO) {
        val request = builder.header("User-Agent", userAgent).timeout(Duration.ofSeconds(35)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw IOException("VK HTTP ${response.statusCode()}.")
        mapper.readTree(response.body()) ?: throw IOException("Empty VK response.")
    }

    override fun close() = client.close()
}

internal class VkBotApiException(val code: Int) : RuntimeException("VK API error $code.")

data class VkGroup(val id: Long, val name: String? = null)
data class VkLongPollServer(val key: String, val server: String, val ts: String)
data class VkUser(
    val id: Long,
    @param:JsonProperty("first_name") val firstName: String? = null,
    @param:JsonProperty("last_name") val lastName: String? = null,
)
data class VkLongPollResponse(
    val ts: String? = null,
    val updates: List<VkLongPollUpdate> = emptyList(),
    val failed: Int? = null,
)
data class VkLongPollUpdate(
    val type: String,
    @param:JsonProperty("object") val obj: VkMessageObjectWrapper? = null,
)
data class VkMessageObjectWrapper(val message: VkMessage? = null)
data class VkMessage(
    val id: Long,
    @param:JsonProperty("from_id") val fromId: Long,
    @param:JsonProperty("peer_id") val peerId: Long,
    val text: String? = null,
    val out: Int = 0,
    val attachments: List<VkAttachment> = emptyList(),
)

private fun form(vararg values: Pair<String, String>): String = values.joinToString("&") { (key, value) ->
    "${URLEncoder.encode(key, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
}

// Download/upload URLs are live transport data, never durable attachment identifiers.
data class VkAttachment(val type: String, val doc: VkFile? = null, val photo: VkFile? = null,
    @param:JsonProperty("audio_message") val audioMessage: VkFile? = null, val audio: VkFile? = null)
data class VkFile(val id: Long = 0, val title: String? = null, val ext: String? = null,
    val url: String? = null, val size: Long? = null, val duration: Int? = null,
    @param:JsonProperty("link_ogg") val linkOgg: String? = null, val sizes: List<VkPhotoSize> = emptyList())
data class VkPhotoSize(val url: String, val width: Int = 0, val height: Int = 0)

internal fun vkMediaUri(raw: String): URI {
    val uri = URI(raw)
    val host = uri.host?.lowercase().orEmpty()
    val allowed = listOf("vk.com", "userapi.com", "vkuseraudio.net", "vkuseraudio.ru", "vkuserphoto.ru", "vk-cdn.net")
    require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null && uri.port in setOf(-1, 443) &&
        allowed.any { host == it || host.endsWith(".$it") }) { "Untrusted VK media URL." }
    return uri
}
