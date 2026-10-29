package ru.souz.backend.channels

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A user-facing communication channel a message can be forwarded to (Telegram, a public-client chat, ...). */
data class ChannelDescriptor(
    val channelType: String,
    val channelId: String,
    val label: String,
)

sealed interface ChannelSendResult {
    data class Delivered(val detail: String) : ChannelSendResult
    data class Failed(val reason: String) : ChannelSendResult
}

/** One implementation per channel type — see `backend/src/main/kotlin/ru/souz/backend/channels/` siblings. */
interface ChannelProvider {
    val channelType: String

    suspend fun listChannels(userId: String): List<ChannelDescriptor>

    suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult

    suspend fun sendAttachments(userId: String, channelId: String, text: String, fileIds: List<String>, deliveryKey: String? = null): ChannelSendResult =
        if (fileIds.isEmpty()) sendMessage(userId, channelId, text)
        else ChannelSendResult.Failed("Attachments are not supported by this channel.")
}

/** Aggregates registered [ChannelProvider]s. Providers list and address their own destinations. */
class ChannelProviderRegistry(providers: List<ChannelProvider>) {
    private class Catalogue(val mutex: Mutex = Mutex(), var channels: List<ChannelDescriptor>? = null)
    private val mutex = Mutex()
    private val catalogues = mutableMapOf<String, Catalogue>()

    private val providersByType: Map<String, ChannelProvider> =
        providers.associateBy { it.channelType }.also { indexed ->
            require(indexed.size == providers.size) {
                "Duplicate channel provider type."
            }
        }

    private suspend fun catalogue(userId: String): Catalogue =
        mutex.withLock { catalogues.getOrPut(userId) { Catalogue() } }

    /** An explicit listing refreshes this user's cache; an empty result is also complete. */
    suspend fun listAll(userId: String): List<ChannelDescriptor> {
        val catalogue = catalogue(userId)
        return catalogue.mutex.withLock { load(userId).also { catalogue.channels = it } }
    }

    private suspend fun load(userId: String): List<ChannelDescriptor> =
        providersByType.values.flatMap { it.listChannels(userId) }

    suspend fun send(
        userId: String,
        channelType: String,
        channelId: String,
        text: String,
        fileIds: List<String> = emptyList(),
        deliveryKey: String? = null,
    ): ChannelSendResult {
        val catalogue = catalogue(userId)
        val channels = catalogue.mutex.withLock {
            catalogue.channels ?: load(userId).also { catalogue.channels = it }
        }
        val candidates = channels.filter { it.channelId == channelId }
        // A migrated chat keeps its ID. Resolve only that destination, never another chat by label.
        val target = candidates.singleOrNull { it.channelType == channelType } ?: candidates.singleOrNull()
            ?: return ChannelSendResult.Failed("Channel is unavailable or ambiguous. Refresh ListActiveChannels before choosing a destination.")
        val result = providersByType.getValue(target.channelType)
            .sendAttachments(userId, channelId, text, fileIds, deliveryKey)
        if (result is ChannelSendResult.Failed) catalogue.mutex.withLock {
            // Do not discard a newer explicit refresh or retry a possibly accepted delivery.
            if (catalogue.channels === channels) catalogue.channels = null
        }
        return result
    }
}
