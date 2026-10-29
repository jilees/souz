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
    fun `event migration replaces historical UUID descriptors once including removed files and UTF8 owners`() = runBlocking {
        val config = postgresAppConfig(newPostgresSchema("object_keys_upgrade")).postgres
        fun migrate(target: String? = null) {
            val flyway = Flyway.configure().dataSource(config.jdbcUrl(), config.user, config.password)
                .locations("classpath:db/migration").schemas(config.schema).defaultSchema(config.schema)
            if (target != null) flyway.target(target)
            flyway.load().migrate()
        }
        migrate("26")
        val user = "Владелец 😀"
        val id = UUID.randomUUID(); val missing = UUID.randomUUID(); val chat = UUID.randomUUID()
        val event = UUID.randomUUID()
        DriverManager.getConnection(config.jdbcUrl(), config.user, config.password).use { connection ->
            connection.schema = config.schema
            connection.prepareStatement("insert into users(id) values (?)").use {
                it.setString(1,user); it.executeUpdate()
            }
            connection.prepareStatement("insert into chats(id,user_id,client_type,request_id,payload_hash) values (?,?,'backend','create','hash')").use {
                it.setObject(1,chat); it.setString(2,user); it.executeUpdate()
            }
            connection.prepareStatement("""insert into stored_attachments(id,user_id,source_key,name,mime_type,size_bytes,sha256,state,created_at,stored_at,expires_at,cleanup_due_at)
                values (?,?,'upload','screen.png','image/png',4,'checksum','ready',now(),now(),now()+interval '30 days',now()+interval '30 days')""").use {
                it.setObject(1,id); it.setString(2,user); it.executeUpdate()
            }
            connection.prepareStatement("insert into agent_events(id,user_id,chat_id,seq,type,payload,created_at) values (?,?,?,17,'thread.completed',?::jsonb,'2026-10-01T00:00:00Z')").use {
                it.setObject(1,event); it.setString(2,user); it.setObject(3,chat)
                it.setString(4,"""{"response":"kept","attachments":[{"id":"$id","name":"screen.png","presentation":"image"},{"id":"$missing","name":"removed.txt","presentation":"document"}]}""")
                it.executeUpdate()
            }
        }
        migrate("27")
        DriverManager.getConnection(config.jdbcUrl(), config.user, config.password).use { connection ->
            connection.schema = config.schema
            connection.prepareStatement("select object_key from stored_attachments where id=?").use {
                it.setObject(1,id); it.executeQuery().use { rows -> rows.next(); assertEquals(attachmentObjectKey(user,id),rows.getString(1)) }
            }
            connection.prepareStatement("update stored_attachments set object_key='custom/original.bin' where id=?").use {
                it.setObject(1,id); it.executeUpdate()
            }
        }
        repeat(2) {
            migrate()
            DriverManager.getConnection(config.jdbcUrl(), config.user, config.password).use { connection ->
                connection.schema = config.schema
                connection.prepareStatement("select seq,payload,created_at from agent_events where id=?").use {
                    it.setObject(1,event); it.executeQuery().use { rows ->
                        assertTrue(rows.next()); assertEquals(17L,rows.getLong(1))
                        val payload = ru.souz.llms.restJsonMapper.readTree(rows.getString(2))
                        assertEquals("kept",payload["response"].asText())
                        assertEquals(listOf("custom/original.bin",attachmentObjectKey(user,missing)),payload["attachments"].map { f -> f["objectKey"].asText() })
                        assertTrue(payload["attachments"].none { f -> f.has("id") })
                        assertEquals(listOf("image","document"),payload["attachments"].map { f -> f["presentation"].asText() })
                        assertEquals(Instant.parse("2026-10-01T00:00:00Z"),rows.getTimestamp(3).toInstant())
                    }
                }
            }
        }
    }

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
                assertEquals(attachmentObjectKey("alice", ready.id), ready.objectKey)
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
                            assertEquals(28, rows.getInt(1))
                        }
                    }
                }
            }
        }
    }
}
