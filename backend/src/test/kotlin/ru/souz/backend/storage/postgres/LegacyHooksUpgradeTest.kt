package ru.souz.backend.storage.postgres

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.souz.backend.hooks.HookConfig
import ru.souz.backend.hooks.HookDefinition
import ru.souz.backend.hooks.HookStore
import ru.souz.backend.hooks.LoadedHook

class LegacyHooksUpgradeTest {
    @Test
    fun `legacy database upgrades without losing receipts and accepts upstream hook inserts`() = runTest {
        val schema = legacySchema()
        val receiptId = UUID.randomUUID()
        val chatId = UUID.randomUUID()
        connection(schema).use { c ->
            c.createStatement().use { s ->
                s.execute("insert into users(id) values ('upgrade-owner')")
                s.execute("""
                    insert into chats(id, user_id, client_type, request_id, payload_hash)
                    values ('$chatId', 'upgrade-owner', 'hook', 'legacy-request', 'hash')
                """.trimIndent())
                s.execute("""
                    insert into hook_receipts(id, hook_id, user_id, chat_id, event_key, payload_hash,
                        payload, prompt, revision, dispatched_at, llm_calls, total_tokens)
                    values ('$receiptId', 'legacy-hook', 'upgrade-owner', '$chatId', 'legacy-event',
                        'hash', 'legacy-payload', 'legacy-prompt', 'legacy-revision', now(), 3, 42)
                """.trimIndent())
                s.execute("insert into hook_daily_usage values ('upgrade-owner', current_date, 3)")
                s.execute(upgradeSql)
                s.execute(upgradeSql) // Repeated operator invocation is harmless.
                s.executeQuery("select prompt, dispatched_at from hook_receipts where id = '$receiptId'").use {
                    assertTrue(it.next())
                    assertEquals("legacy-prompt", it.getString(1))
                    assertNotNull(it.getTimestamp(2))
                }
                s.executeQuery("select llm_calls from hook_daily_usage where user_id = 'upgrade-owner'").use {
                    assertTrue(it.next())
                    assertEquals(3, it.getInt(1))
                }
            }
        }
        // Real host initialization validates V14's checksum and applies V15 and Skill OAuth.
        PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres).use { dataSource ->
            val store = HookStore(dataSource, HookConfig(owners = setOf("upgrade-owner")))
            val old = assertNotNull(store.find("upgrade-owner", receiptId))
            assertEquals("legacy-payload", old.payload)
            assertEquals("legacy-revision", old.revision)
            assertEquals("pending", old.status)
            assertEquals(3, old.llmCalls)
            assertEquals(42L, old.totalTokens)
            val hook = LoadedHook(HookDefinition(1, "fresh-hook", "upgrade-owner", prompt = "Handle event"))
            val accepted = store.accept(hook, "new-payload", "new-event")
            assertEquals("new-payload", store.find("upgrade-owner", accepted.receiptId)?.payload)
            assertTrue(store.accept(hook, "new-payload", "new-event").duplicate)
            dataSource.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("select prompt from hook_receipts where id = '${accepted.receiptId}'").use {
                        assertTrue(it.next())
                        assertNull(it.getString(1))
                    }
                }
            }
        }
    }

    @Test
    fun `unknown migration checksum is rejected without changing legacy schema`() {
        val schema = legacySchema()
        connection(schema).use { c ->
            c.createStatement().use { s ->
                s.execute("update flyway_schema_history set checksum = 123 where version = '14'")
                assertFailsWith<SQLException> { s.execute(upgradeSql) }
                s.executeQuery("""
                    select is_nullable from information_schema.columns
                    where table_schema = '$schema' and table_name = 'hook_receipts' and column_name = 'prompt'
                """.trimIndent()).use {
                    assertTrue(it.next())
                    assertEquals("NO", it.getString(1))
                }
                s.executeQuery("select checksum from flyway_schema_history where version = '14'").use {
                    assertTrue(it.next())
                    assertEquals(123, it.getInt(1))
                }
            }
        }
    }

    private fun legacySchema(): String {
        val schema = newPostgresSchema("legacy_hooks")
        val postgres = SharedPostgresContainer.instance
        fun flyway(location: String) = Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas(schema).defaultSchema(schema).locations(location)
        flyway("classpath:db/migration").target("13").load().migrate()
        flyway("classpath:legacy-hooks").validateOnMigrate(false).load().migrate()
        return schema
    }

    private fun connection(schema: String): Connection {
        val postgres = SharedPostgresContainer.instance
        return DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).apply {
            this.schema = schema
        }
    }

    private val upgradeSql: String
        get() {
            val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.exists(it.resolve("settings.gradle.kts")) }
            return Files.readString(root.resolve("deploy/upgrade-hooks-verify.sql"))
        }
}
