package ru.souz.backend.attachments

import javax.sql.DataSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.souz.backend.storage.postgres.write

/** A crash or timeout may have consumed provider quota; replay must not silently run it again. */
class AttachmentMediaOperations(private val dataSource: DataSource) {
    suspend fun <T> run(userId: String, key: String, block: suspend () -> T): T {
        val claimed = dataSource.write { connection ->
            connection.prepareStatement("""
                insert into attachment_media_operations(user_id, operation_key, state) values (?, ?, 'started')
                on conflict do nothing
            """.trimIndent()).use {
                it.setString(1, userId); it.setString(2, key)
                it.executeUpdate() == 1
            }
        }
        if (!claimed) throw AttachmentException("attachment_media_previously_attempted")
        var succeeded = false
        try {
            return block().also { succeeded = true }
        } finally {
            withContext(NonCancellable) {
                dataSource.write { connection ->
                    connection.prepareStatement("update attachment_media_operations set state = ? where user_id = ? and operation_key = ?").use {
                        it.setString(1, if (succeeded) "succeeded" else "failed")
                        it.setString(2, userId); it.setString(3, key); it.executeUpdate()
                    }
                }
            }
        }
    }
}
