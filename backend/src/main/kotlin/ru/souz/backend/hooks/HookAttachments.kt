package ru.souz.backend.hooks

import io.ktor.http.cio.CIOMultipartDataBase
import io.ktor.http.content.PartData
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.io.readByteArray
import ru.souz.backend.attachments.attachmentName
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.storage.postgres.postgresStorageMapper

internal const val MAX_HOOK_MULTIPART_BYTES = 10_000_000
internal const val MAX_HOOK_FILES = 5

internal data class HookUpload(val name: String, val bytes: ByteArray)
internal data class HookMultipart(val payload: String, val files: List<HookUpload>) {
    // Independent of MIME boundaries and generated storage IDs, but sensitive to file order and bytes.
    val hash: String get() = sha256(postgresStorageMapper.writeValueAsBytes(listOf(
        payload, files.map { listOf(it.name, sha256(it.bytes)) },
    )))
}

@OptIn(InternalAPI::class)
internal suspend fun parseHookMultipart(body: ByteArray, contentType: String): HookMultipart = coroutineScope {
    try {
        val parts = CIOMultipartDataBase(coroutineContext, ByteReadChannel(body), contentType, body.size.toLong(), MAX_HOOK_MULTIPART_BYTES.toLong())
        var payload: String? = null
        val files = mutableListOf<HookUpload>()
        while (true) {
            val part = parts.readPart() ?: break
            try {
                when (part) {
                    is PartData.FormItem -> {
                        if (part.name != "payload" || payload != null) throw hookError(400, "invalid_hook_multipart")
                        if (part.value.toByteArray().size > HookDefinitions.MAX_BODY_BYTES) throw hookError(413, "hook_payload_too_large")
                        payload = part.value.also(::parseHookJson)
                    }
                    is PartData.FileItem -> {
                        if (part.name != "files" || files.size >= MAX_HOOK_FILES) throw hookError(400, "invalid_hook_files")
                        val bytes = part.provider().readRemaining().use { it.readByteArray() }
                        if (bytes.isEmpty()) throw hookError(400, "empty_hook_file")
                        files += HookUpload(attachmentName(part.originalFileName.orEmpty()), bytes)
                    }
                    else -> throw hookError(400, "invalid_hook_multipart")
                }
            } finally { part.release() }
        }
        HookMultipart(payload ?: throw hookError(400, "hook_payload_required"), files.also {
            if (it.isEmpty()) throw hookError(400, "hook_files_required")
        })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (known: BackendV1Exception) {
        throw known
    } catch (_: Exception) {
        throw hookError(400, "invalid_hook_multipart")
    }
}
