package ru.souz.backend.salute

import java.time.Clock
import java.util.UUID
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.ChatRepository

sealed interface SaluteDeviceBindOutcome {
    data class Bound(val binding: SaluteDeviceBinding) : SaluteDeviceBindOutcome

    data class AlreadyBoundToYou(val binding: SaluteDeviceBinding) : SaluteDeviceBindOutcome

    data object BoundToAnotherUser : SaluteDeviceBindOutcome
}

/**
 * Claims a Salute device for a user, from a channel where the user is already identified
 * (currently: their own linked Telegram bot). Unlike [TelegramBotBindingService]'s `/start`
 * flow there is no per-claim secret — first claim on an unclaimed `deviceId` wins, and any
 * later claim by a different user is rejected. Acceptable for now: [deviceId] is a fixed
 * hardware id, not a guessable-but-otherwise-meaningless secret, and the risk is scoped to a
 * small, trusted set of Telegram-linked users.
 */
class SaluteDeviceBindingService(
    private val bindingRepository: SaluteDeviceBindingRepository,
    private val chatRepository: ChatRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun bind(
        userId: String,
        deviceId: String,
    ): SaluteDeviceBindOutcome {
        bindingRepository.getByDeviceId(deviceId)?.let { existing ->
            return if (existing.userId == userId) {
                SaluteDeviceBindOutcome.AlreadyBoundToYou(existing)
            } else {
                SaluteDeviceBindOutcome.BoundToAnotherUser
            }
        }
        val now = clock.instant()
        val chat = chatRepository.create(
            Chat(
                id = UUID.randomUUID(),
                userId = userId,
                title = "Salute: $deviceId",
                archived = false,
                createdAt = now,
                updatedAt = now,
            )
        )
        val binding = bindingRepository.insertIfAbsent(
            deviceId = deviceId,
            userId = userId,
            chatId = chat.id,
            now = now,
        )
        // insertIfAbsent is race-safe: if another user's claim won the race for the same
        // deviceId between our getByDeviceId check and this insert, it returns their row.
        return if (binding.userId == userId) {
            SaluteDeviceBindOutcome.Bound(binding)
        } else {
            SaluteDeviceBindOutcome.BoundToAnotherUser
        }
    }
}
