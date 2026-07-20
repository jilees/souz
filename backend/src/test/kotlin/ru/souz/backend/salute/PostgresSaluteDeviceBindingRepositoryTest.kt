package ru.souz.backend.salute

import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.PostgresSaluteDeviceBindingRepository
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig
import kotlin.test.Test

class PostgresSaluteDeviceBindingRepositoryTest {
    @Test
    fun `getByDeviceId returns null for an unknown device`() = runTest {
        val schema = newPostgresSchema("postgres_salute_binding_unknown")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            val repository = PostgresSaluteDeviceBindingRepository(it)
            assertNull(repository.getByDeviceId("unknown-device"))
        }
    }

    @Test
    fun `insertIfAbsent creates a binding and getByDeviceId returns it`() = runTest {
        val schema = newPostgresSchema("postgres_salute_binding_create")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            val repository = PostgresSaluteDeviceBindingRepository(it)
            val now = Instant.parse("2026-07-20T10:00:00Z")
            val chatId = UUID.randomUUID()

            val created = repository.insertIfAbsent(
                deviceId = "device-1",
                userId = "owner",
                chatId = chatId,
                now = now,
            )

            assertEquals("device-1", created.deviceId)
            assertEquals("owner", created.userId)
            assertEquals(chatId, created.chatId)

            val fetched = repository.getByDeviceId("device-1")
            assertNotNull(fetched)
            assertEquals(created, fetched)
        }
    }

    @Test
    fun `insertIfAbsent is a no-op race-safe upsert keyed by device id`() = runTest {
        val schema = newPostgresSchema("postgres_salute_binding_race")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            val repository = PostgresSaluteDeviceBindingRepository(it)
            val now = Instant.parse("2026-07-20T10:00:00Z")
            val firstChatId = UUID.randomUUID()
            val secondChatId = UUID.randomUUID()

            val first = repository.insertIfAbsent(
                deviceId = "device-1",
                userId = "owner",
                chatId = firstChatId,
                now = now,
            )
            val second = repository.insertIfAbsent(
                deviceId = "device-1",
                userId = "someone-else",
                chatId = secondChatId,
                now = now.plusSeconds(60),
            )

            assertEquals(first.id, second.id)
            assertEquals(firstChatId, second.chatId)
            assertEquals("owner", second.userId)
        }
    }

    @Test
    fun `touchLastSeen updates last seen and updated timestamps`() = runTest {
        val schema = newPostgresSchema("postgres_salute_binding_touch")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            val repository = PostgresSaluteDeviceBindingRepository(it)
            val now = Instant.parse("2026-07-20T10:00:00Z")
            val created = repository.insertIfAbsent(
                deviceId = "device-1",
                userId = "owner",
                chatId = UUID.randomUUID(),
                now = now,
            )

            val touchedAt = now.plusSeconds(120)
            repository.touchLastSeen(created.id, touchedAt)

            val fetched = repository.getByDeviceId("device-1")
            assertNotNull(fetched)
            assertEquals(touchedAt, fetched.lastSeenAt)
            assertEquals(touchedAt, fetched.updatedAt)
        }
    }
}
