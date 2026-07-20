package ru.souz.backend.salute

import java.time.Instant
import java.util.UUID

interface SaluteDeviceBindingRepository {
    suspend fun getByDeviceId(deviceId: String): SaluteDeviceBinding?

    /**
     * Inserts a new binding for [deviceId] pointing at a freshly created [chatId], unless a
     * binding for this device already exists (race-safe via `on conflict (device_id) do
     * nothing`) — in that case the existing binding is returned instead, and the caller-supplied
     * [chatId] is discarded (the chat backing it becomes orphaned, an acceptable edge case for
     * the rare race between two concurrent first-contacts from the same device).
     */
    suspend fun insertIfAbsent(
        deviceId: String,
        userId: String,
        chatId: UUID,
        now: Instant,
    ): SaluteDeviceBinding

    suspend fun touchLastSeen(
        id: UUID,
        now: Instant,
    )
}
