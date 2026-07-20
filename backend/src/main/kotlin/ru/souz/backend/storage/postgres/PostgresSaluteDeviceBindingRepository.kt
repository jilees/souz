package ru.souz.backend.storage.postgres

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.salute.SaluteDeviceBinding
import ru.souz.backend.salute.SaluteDeviceBindingRepository

class PostgresSaluteDeviceBindingRepository(
    private val dataSource: DataSource,
) : SaluteDeviceBindingRepository {
    override suspend fun getByDeviceId(deviceId: String): SaluteDeviceBinding? = dataSource.read { connection ->
        connection.prepareStatement(
            "select * from salute_device_bindings where device_id = ?"
        ).use { statement ->
            statement.setString(1, deviceId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toSaluteDeviceBinding() else null
            }
        }
    }

    override suspend fun insertIfAbsent(
        deviceId: String,
        userId: String,
        chatId: UUID,
        now: Instant,
    ): SaluteDeviceBinding = dataSource.write { connection ->
        val inserted = connection.prepareStatement(
            """
            insert into salute_device_bindings(
                id, device_id, user_id, chat_id, created_at, updated_at, last_seen_at
            )
            values (?, ?, ?, ?, ?, ?, ?)
            on conflict (device_id) do nothing
            returning *
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setString(2, deviceId)
            statement.setString(3, userId)
            statement.setObject(4, chatId)
            statement.setInstant(5, now)
            statement.setInstant(6, now)
            statement.setInstant(7, now)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toSaluteDeviceBinding() else null
            }
        }
        inserted ?: connection.prepareStatement(
            "select * from salute_device_bindings where device_id = ?"
        ).use { statement ->
            statement.setString(1, deviceId)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.toSaluteDeviceBinding()
            }
        }
    }

    override suspend fun touchLastSeen(
        id: UUID,
        now: Instant,
    ) {
        dataSource.write { connection ->
            connection.prepareStatement(
                "update salute_device_bindings set last_seen_at = ?, updated_at = ? where id = ?"
            ).use { statement ->
                statement.setInstant(1, now)
                statement.setInstant(2, now)
                statement.setObject(3, id)
                statement.executeUpdate()
            }
        }
    }
}
