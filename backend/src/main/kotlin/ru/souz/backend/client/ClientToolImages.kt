package ru.souz.backend.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.URI
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import ru.souz.backend.attachments.AttachmentException
import ru.souz.backend.attachments.AttachmentStorage
import ru.souz.backend.attachments.MAX_TOOL_IMAGE_BYTES
import ru.souz.backend.attachments.attachmentMime
import ru.souz.backend.attachments.downloadToolImageBytes
import ru.souz.backend.attachments.toolImageMimeTypes
import ru.souz.llms.restJsonMapper

/** Save MCP images before persisting a client result or releasing its waiter. */
internal suspend fun ClientToolOutcome.storeImages(
    userId: String, sourceKey: String, storage: AttachmentStorage?,
): ClientToolOutcome {
    val content = result?.get("content")?.takeIf { it.isArray } ?: return this
    val images = content.withIndex().filter {
        it.value["type"]?.asText() == "image" || it.value.imageResourceUri()?.startsWith("attachment:") == false
    }
    if (images.isEmpty()) return this
    return try {
        val normalized = result.deepCopy<ObjectNode>()
        val items = normalized["content"] as ArrayNode
        if (result["isError"]?.asBoolean() == true) {
            images.forEach { (index, _) -> items.set(index, restJsonMapper.createObjectNode()
                .put("type", "text").put("text", "Image omitted because the tool reported an error.")) }
        } else {
            var remaining = MAX_TOOL_IMAGE_BYTES
            val decoded = images.map { (index, item) ->
                val inline = item["type"]?.asText() == "image"
                val bytes = if (inline) {
                    val data = item["data"]?.takeIf { it.isTextual }?.asText()
                    require(data != null && data.length <= 4 * ((remaining + 2) / 3)) { "Invalid tool image size." }
                    Base64.getDecoder().decode(data)
                } else {
                    if (storage == null) throw AttachmentException("attachment_storage_disabled")
                    downloadToolImageBytes(requireNotNull(item.imageResourceUri()), remaining)
                }
                require(bytes.size <= remaining) { "Tool images are too large." }
                remaining -= bytes.size
                val mime = attachmentMime(bytes, "tool-image")
                val declaredMime = item["mimeType"]?.takeIf { it.isTextual }?.asText()
                require(mime in toolImageMimeTypes && ((!inline && declaredMime == null) || mime == declaredMime)) { "Invalid tool image format." }
                Triple(index, bytes, mime)
            }
            val files = storage ?: throw AttachmentException("attachment_storage_disabled")
            for ((index, bytes, mime) in decoded) {
                val saved = files.save(userId, "$sourceKey:$index", "tool-image-${index}.${mime.substringAfter('/')}", bytes.inputStream())
                items.set(index, restJsonMapper.createObjectNode().put("type", "resource_link")
                    .put("uri", "attachment:${saved.id}").put("fileId", saved.id.toString())
                    .put("name", saved.name).put("mimeType", saved.mimeType))
            }
        }
        copy(result = normalized)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        val error = if (failure is IllegalArgumentException || failure is AttachmentException && failure.code == "attachment_too_large")
            ClientError("invalid_tool_image", "Client tool returned an invalid or oversized image.")
        else ClientError("tool_image_unavailable", "Client tool image could not be stored; image content is unavailable.")
        ClientToolOutcome("failed", null, error)
    }
}

internal fun JsonNode.imageResourceUri(): String? {
    if (get("type")?.asText() != "resource_link") return null
    val url = get("uri")?.takeIf { it.isTextual }?.asText() ?: return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val image = get("mimeType")?.asText()?.startsWith("image/") == true ||
        listOf(".png", ".jpg", ".jpeg", ".webp", ".gif").any { uri.path.orEmpty().lowercase().endsWith(it) }
    val stored = uri.scheme == "attachment" && runCatching { UUID.fromString(uri.schemeSpecificPart) }.isSuccess
    return url.takeIf { image && it.length <= 8192 && (stored ||
        ((uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank() && uri.userInfo == null)) }
}
