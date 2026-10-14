package ru.souz.backend.attachments

import javax.sql.DataSource
import ru.souz.backend.channels.channelTextChunks
import ru.souz.backend.storage.postgres.write
import ru.souz.backend.storage.postgres.read
import ru.souz.llms.FileContent
import ru.souz.llms.FilePresentation

/** Durable part receipts. A send with an unknown outcome is never silently replayed. */
class AttachmentDelivery(private val storage: AttachmentStorage, private val dataSource: DataSource) {
    suspend fun deliver(
        userId: String,
        key: String,
        text: String,
        files: List<FileContent>,
        textLimit: Int,
        checkOwnership: suspend () -> Unit = {},
        onAccepted: suspend (text: String?, file: FileContent?) -> Unit = { _, _ -> },
        sendText: suspend (String) -> Unit,
        sendFile: suspend (FileContent, ByteArray) -> String,
        documentTextLimit: Int = textLimit,
        sendDocumentWithText: (suspend (FileContent, ByteArray, String) -> String)? = null,
    ) {
        require(files.size <= 10 && files.sumOf { it.sizeBytes } <= 100_000_000)
        val prepared = files.map { file ->
            val saved = storage.get(userId, attachmentId(file.file.fileId))
            saved.toContent(file.presentation) to storage.read(userId, saved.id)
        }
        var index = 0
        val chunks = channelTextChunks(text, textLimit).filter { it.isNotEmpty() }
        // Preserve the layout of a delivery already started by an older instance.
        val combine = sendDocumentWithText != null && prepared.firstOrNull()?.first?.presentation == FilePresentation.document &&
            chunks.isNotEmpty() && firstPartHash(userId, key) != chunks.first().toByteArray().attachmentChecksum()
        val combinedText = if (combine) channelTextChunks(text, documentTextLimit).first() else ""
        if (combine) {
            val (file, bytes) = prepared.first()
            val hash = "document-text:${file.file.fileId}:${bytes.attachmentChecksum()}:$combinedText".toByteArray().attachmentChecksum()
            sendPart(userId, key, index++, hash, checkOwnership) { requireNotNull(sendDocumentWithText)(file, bytes, combinedText) }
            onAccepted(combinedText, file)
        }
        for (chunk in if (combine) channelTextChunks(text.substring(combinedText.length), textLimit).filter { it.isNotEmpty() } else chunks) {
            val hash = chunk.toByteArray().attachmentChecksum()
            sendPart(userId, key, index++, hash, checkOwnership) { sendText(chunk); "accepted" }
            onAccepted(chunk, null)
        }
        for ((file, bytes) in if (combine) prepared.drop(1) else prepared) {
            val hash = "${file.file.fileId}:${file.presentation}:${bytes.attachmentChecksum()}".toByteArray().attachmentChecksum()
            sendPart(userId, key, index++, hash, checkOwnership) { sendFile(file, bytes) }
            onAccepted(null, file)
        }
    }

    private suspend fun firstPartHash(userId: String, key: String): String? = dataSource.read { connection ->
        connection.prepareStatement("select payload_hash from attachment_delivery_parts where user_id = ? and delivery_key = ? and part = 0").use {
            it.setString(1, userId); it.setString(2, key)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    private suspend fun sendPart(
        userId: String, key: String, index: Int, hash: String,
        checkOwnership: suspend () -> Unit, send: suspend () -> String,
    ) {
        checkOwnership()
        val claimed = dataSource.write { connection ->
            connection.prepareStatement("""
                insert into attachment_delivery_parts(user_id, delivery_key, part, payload_hash, state)
                values (?, ?, ?, ?, 'sending') on conflict do nothing
            """.trimIndent()).use {
                it.setString(1, userId); it.setString(2, key); it.setInt(3, index); it.setString(4, hash)
                if (it.executeUpdate() == 1) return@write true
            }
            connection.prepareStatement("""
                select state, payload_hash from attachment_delivery_parts where user_id = ? and delivery_key = ? and part = ?
            """.trimIndent()).use {
                it.setString(1, userId); it.setString(2, key); it.setInt(3, index)
                it.executeQuery().use { rows ->
                    check(rows.next())
                    if (rows.getString(2) != hash) throw AttachmentException("attachment_delivery_conflict")
                    if (rows.getString(1) != "delivered") throw AttachmentException("attachment_delivery_uncertain")
                }
            }
            false
        }
        if (!claimed) return
        checkOwnership()
        val receipt = send()
        // Persist receipt after remote acceptance even when cancellation/lease loss followed it.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            dataSource.write { connection ->
                connection.prepareStatement("""
                    update attachment_delivery_parts set state = 'delivered', receipt = ?
                    where user_id = ? and delivery_key = ? and part = ? and payload_hash = ?
                """.trimIndent()).use {
                    it.setString(1, receipt); it.setString(2, userId); it.setString(3, key); it.setInt(4, index); it.setString(5, hash)
                    it.executeUpdate()
                }
            }
        }
    }
}
