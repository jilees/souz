package ru.souz.backend.storage.postgres

import com.zaxxer.hikari.HikariDataSource
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import ru.souz.jobs.CreateJobRequest
import ru.souz.jobs.impl.PostgresJobService

class PostgresDataSourceFactoryTest {
    @Test
    fun `personality migration preserves legacy prompts revisions and isolates each chat spotter`() = runBlocking {
        val config = postgresAppConfig(newPostgresSchema("personality_migration")).postgres
        val owner = "legacy-personality-owner"
        val schema = config.schema
        org.flywaydb.core.Flyway.configure().dataSource(config.jdbcUrl(), config.user, config.password)
            .locations("classpath:db/migration").defaultSchema(schema).schemas(schema).target("29").load().migrate()
        java.sql.DriverManager.getConnection(config.jdbcUrl(), config.user, config.password).use { connection ->
            connection.schema = schema
            connection.createStatement().use { statement ->
                statement.execute("insert into users(id) values ('$owner')")
                statement.execute("insert into user_settings(user_id, settings_json, personality_prompt, personality_revision) " +
                    "values ('$owner', '{}'::jsonb, 'Legacy custom style', 42)")
            }
        }
        PostgresDataSourceFactory.create(config).use { dataSource ->
            val repository = PostgresUserSettingsRepository(dataSource)
            val migrated = repository.personality(owner)
            assertEquals(ru.souz.backend.settings.model.PersonalitySnapshot("Legacy custom style", 42), migrated)
            val now = java.time.Instant.now()
            val chat = ru.souz.backend.chat.model.Chat(java.util.UUID.randomUUID(), owner, "New column", false, now, now,
                yourName = "  Пятница  ")
            PostgresChatRepository(dataSource).create(chat, emptyList(),
                ru.souz.backend.settings.model.PersonalitySnapshot(voice = "sber"))
            assertEquals(migrated, repository.personality(owner))
            assertEquals("  Пятница  ", PostgresChatRepository(dataSource).getById(chat.id)?.yourName)
            repository.compareAndSetPersonality(owner, 42, migrated.copy(voice = "afina", appeal = "official", style = "formal"))
            repository.save(ru.souz.backend.settings.model.UserSettings(owner, temperature = 0.2f))
            assertEquals(migrated.copy(revision = 43, voice = "afina", appeal = "official", style = "formal"), repository.personality(owner))
        }
    }

    @Test
    fun `host datasource applies job migrations and preserves jobs across restart`() = runBlocking {
        val config = postgresAppConfig(newPostgresSchema("job_migrations")).postgres
        val owner = "standalone-job-owner"
        val job = PostgresDataSourceFactory.create(config).use { dataSource ->
            PostgresJobService(dataSource).createJob(
                owner, CreateJobRequest("Persisted job", jacksonObjectMapper().readTree("{\"value\":42}")),
            )
        }

        PostgresDataSourceFactory.create(config).use { dataSource ->
            assertEquals(job, PostgresJobService(dataSource).listJobs(owner).items.single())
        }
    }

    @Test
    fun `preserves initialization failure when close also fails`() {
        val dataSource = mockk<HikariDataSource>()
        val migrationFailure = IllegalStateException("migration failed")
        val closeFailure = IllegalArgumentException("close failed")
        every { dataSource.close() } throws closeFailure

        val thrown = assertFailsWith<IllegalStateException> {
            initializeDataSource(dataSource) { throw migrationFailure }
        }

        assertSame(migrationFailure, thrown)
        assertEquals(listOf(closeFailure), thrown.suppressed.toList())
        verify(exactly = 1) { dataSource.close() }
    }
}
