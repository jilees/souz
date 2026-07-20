package ru.souz.backend.testutil.repository

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import ru.souz.backend.salute.SaluteDeviceBinding
import ru.souz.backend.salute.SaluteDeviceBindingRepository

class MemorySaluteDeviceBindingRepository : SaluteDeviceBindingRepository {
    private val bindingsByDeviceId = ConcurrentHashMap<String, SaluteDeviceBinding>()

    override suspend fun getByDeviceId(deviceId: String): SaluteDeviceBinding? = bindingsByDeviceId[deviceId]

    override suspend fun insertIfAbsent(
        deviceId: String,
        userId: String,
        chatId: UUID,
        now: Instant,
    ): SaluteDeviceBinding =
        bindingsByDeviceId.computeIfAbsent(deviceId) {
            SaluteDeviceBinding(
                id = UUID.randomUUID(),
                deviceId = deviceId,
                userId = userId,
                chatId = chatId,
                createdAt = now,
                updatedAt = now,
                lastSeenAt = now,
            )
        }

    override suspend fun touchLastSeen(id: UUID, now: Instant) {
        bindingsByDeviceId.replaceAll { _, binding ->
            if (binding.id == id) binding.copy(lastSeenAt = now, updatedAt = now) else binding
        }
    }
}
