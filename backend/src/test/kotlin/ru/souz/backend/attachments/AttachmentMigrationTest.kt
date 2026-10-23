package ru.souz.backend.attachments

import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import ru.souz.backend.storage.postgres.PostgresAttachmentRepository
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.PostgresUserSettingsRepository
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig

class AttachmentMigrationTest {
    @Test
    fun `released fork database upgrades without losing files or personality`() = runBlocking {
        val config = postgresAppConfig(newPostgresSchema("attachment_upgrade")).postgres
        Flyway.configure().dataSource(config.jdbcUrl(), config.user, config.password)
            .locations("classpath:db/migration").schemas(config.schema).defaultSchema(config.schema)
            .target("24").load().migrate()
        val ids = listOf("ready", "pending", "deleting", "deleted").associateWith { UUID.randomUUID() }
        val created = Instant.parse("2026-10-01T00:00:00Z")
        DriverManager.getConnection(config.jdbcUrl(), config.user, config.password).use { connection ->
            connection.schema = config.schema
            connection.createStatement().use {
                it.execute("insert into users(id, created_at, last_seen_at) values ('alice', now(), now())")
                it.execute("""insert into user_settings(user_id, settings_json, created_at, updated_at, personality_prompt, personality_revision)
                    values ('alice', '{}'::jsonb, now(), now(), 'Be brief', 7)""")
            }
            for ((state, id) in ids) connection.prepareStatement("""
                insert into stored_attachments(id, user_id, source_key, name, mime_type, size_bytes, sha256,
                    state, created_at, stored_at, expires_at, cleanup_attempt_at)
                values (?, 'alice', ?, 'kept.txt', 'text/plain', 4, 'checksum', ?, ?, ?, ?, ?)
            """.trimIndent()).use {
                it.setObject(1, id)
                it.setString(2, state)
                it.setString(3, state)
                it.setTimestamp(4, java.sql.Timestamp.from(created))
                it.setTimestamp(5, java.sql.Timestamp.from(created))
                it.setTimestamp(6, java.sql.Timestamp.from(created.plusSeconds(30 * 86400)))
                it.setTimestamp(7, java.sql.Timestamp.from(created.plusSeconds(60)))
                it.executeUpdate()
            }
        }
        repeat(2) {
            PostgresDataSourceFactory.create(config).use { dataSource ->
                val repository = PostgresAttachmentRepository(dataSource)
                val ready = requireNotNull(repository.get("alice", ids.getValue("ready")))
                assertEquals("ready", ready.state)
                assertEquals("checksum", ready.sha256)
                assertEquals(4L, ready.sizeBytes)
                assertEquals(created.plusSeconds(30 * 86400), ready.expiresAt)
                assertEquals(ready.expiresAt, ready.cleanupDueAt)
                assertNull(repository.get("bob", ready.id))
                assertEquals(created.plusSeconds(600), repository.get("alice", ids.getValue("pending"))?.cleanupDueAt)
                for (state in listOf("deleting", "deleted")) {
                    val file = requireNotNull(repository.get("alice", ids.getValue(state)))
                    assertEquals("deleting", file.state)
                    assertEquals(created.plusSeconds(60), file.cleanupDueAt)
                }
                val personality = PostgresUserSettingsRepository(dataSource).personality("alice")
                assertEquals("Be brief", personality.prompt)
                assertEquals(7L, personality.revision)
                dataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("select discovered_client_tools from chats limit 0").close()
                        statement.executeQuery("select count(*) from flyway_schema_history where success and version is not null").use { rows ->
                            assertTrue(rows.next())
                            assertEquals(26, rows.getInt(1))
                        }
                    }
                }
            }
        }
    }
}
