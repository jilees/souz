package ru.souz.backend.channels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield

class ChannelProviderRegistryTest {
    private class FakeProvider(
        override val channelType: String,
        var channels: List<ChannelDescriptor> = emptyList(),
        var result: ChannelSendResult = ChannelSendResult.Delivered("ok"),
    ) : ChannelProvider {
        var sendCalls: Int = 0
            private set
        var listCalls: Int = 0
            private set
        var beforeSend: suspend () -> Unit = {}

        override suspend fun listChannels(userId: String): List<ChannelDescriptor> {
            listCalls++
            yield()
            return channels
        }

        override suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult {
            beforeSend()
            sendCalls += 1
            return result
        }
    }

    @Test
    fun `listAll flattens channels from all providers`() = runTest {
        val telegram = FakeProvider("telegram", listOf(ChannelDescriptor("telegram", "1", "Telegram")))
        val vk = FakeProvider("vk", listOf(ChannelDescriptor("vk", "2", "VK")))
        val mobile = FakeProvider("mobile_app", listOf(ChannelDescriptor("mobile_app", "chat-1", "Mobile")))
        val registry = ChannelProviderRegistry(listOf(telegram, vk, mobile))

        val channels = registry.listAll("user-1")

        assertEquals(
            setOf(
                ChannelDescriptor("telegram", "1", "Telegram"),
                ChannelDescriptor("vk", "2", "VK"),
                ChannelDescriptor("mobile_app", "chat-1", "Mobile"),
            ),
            channels.toSet(),
        )
    }

    @Test
    fun `send routes to the provider that supports the channel type`() = runTest {
        val telegram = FakeProvider("telegram")
        val mobile = FakeProvider("mobile_app", listOf(ChannelDescriptor("mobile_app", "chat-1", "Mobile")))
        val registry = ChannelProviderRegistry(listOf(telegram, mobile))

        registry.send("user-1", "mobile_app", "chat-1", "hi")

        assertEquals(0, telegram.sendCalls)
        assertEquals(1, mobile.sendCalls)
    }

    @Test
    fun `constructor rejects duplicate channel types`() {
        assertFailsWith<IllegalArgumentException> {
            ChannelProviderRegistry(listOf(FakeProvider("telegram"), FakeProvider("telegram")))
        }
    }

    @Test
    fun `send fails when no provider supports the channel type`() = runTest {
        val registry = ChannelProviderRegistry(listOf(FakeProvider("telegram")))

        val result = registry.send("user-1", "unknown", "id", "hi")

        assertIs<ChannelSendResult.Failed>(result)
    }

    @Test
    fun `concurrent sends load once per user and reuse routes until refresh or restart`() = runTest {
        val provider = FakeProvider("public_client", listOf(ChannelDescriptor("public_client", "chat", "Telegram")))
        val registry = ChannelProviderRegistry(listOf(provider))
        assertEquals(0, provider.listCalls)
        (1..10).map { async { registry.send("owner", "telegram", "chat", "message $it") } }.awaitAll()
            .forEach { assertIs<ChannelSendResult.Delivered>(it) }
        assertEquals(1, provider.listCalls)
        assertEquals(10, provider.sendCalls)
        registry.send("another-owner", "telegram", "chat", "separate cache")
        assertEquals(2, provider.listCalls)
        registry.listAll("owner")
        registry.send("owner", "telegram", "chat", "refreshed")
        assertEquals(3, provider.listCalls)
        ChannelProviderRegistry(listOf(provider)).send("owner", "telegram", "chat", "restarted")
        assertEquals(4, provider.listCalls)
    }

    @Test
    fun `empty catalogues are reused and explicit listing discovers new destinations`() = runTest {
        val provider = FakeProvider("public_client")
        val registry = ChannelProviderRegistry(listOf(provider))
        repeat(2) { assertIs<ChannelSendResult.Failed>(registry.send("owner", "telegram", "chat", "hello")) }
        assertEquals(1, provider.listCalls)
        provider.channels = listOf(ChannelDescriptor("public_client", "chat", "Telegram"))
        registry.listAll("owner")
        assertIs<ChannelSendResult.Delivered>(registry.send("owner", "telegram", "chat", "hello"))
        assertEquals(2, provider.listCalls)
    }

    @Test
    fun `failed delivery expires only its snapshot without retrying`() = runTest {
        val provider = FakeProvider("public_client", listOf(ChannelDescriptor("public_client", "chat", "Telegram")),
            ChannelSendResult.Failed("Some parts may have been sent"))
        val registry = ChannelProviderRegistry(listOf(provider))
        assertIs<ChannelSendResult.Failed>(registry.send("owner", "telegram", "chat", "hello"))
        assertEquals(1, provider.sendCalls)
        assertEquals(1, provider.listCalls)
        provider.result = ChannelSendResult.Delivered("ok")
        assertIs<ChannelSendResult.Delivered>(registry.send("owner", "telegram", "chat", "next message"))
        assertEquals(2, provider.listCalls)
        assertEquals(2, provider.sendCalls)

        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        provider.beforeSend = { started.complete(Unit); release.await() }
        provider.result = ChannelSendResult.Failed("Uncertain delivery")
        val pending = async { registry.send("owner", "telegram", "chat", "concurrent send") }
        started.await()
        registry.listAll("owner")
        release.complete(Unit)
        assertIs<ChannelSendResult.Failed>(pending.await())
        provider.beforeSend = {}
        provider.result = ChannelSendResult.Delivered("ok")
        assertIs<ChannelSendResult.Delivered>(registry.send("owner", "telegram", "chat", "after refresh"))
        assertEquals(3, provider.listCalls)
    }
}
