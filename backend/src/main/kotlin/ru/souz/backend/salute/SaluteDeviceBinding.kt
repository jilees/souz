package ru.souz.backend.salute

import java.time.Instant
import java.util.UUID

data class SaluteDeviceBinding(
    val id: UUID,
    val deviceId: String,
    val userId: String,
    val chatId: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastSeenAt: Instant?,
)
