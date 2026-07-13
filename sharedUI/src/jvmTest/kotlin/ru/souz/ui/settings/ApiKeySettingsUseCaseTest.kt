@file:OptIn(ExperimentalCoroutinesApi::class)

package ru.souz.ui.settings

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import ru.souz.db.ProviderKeyPresence
import ru.souz.db.SettingsProvider
import ru.souz.llms.LlmProvider
import ru.souz.ui.common.ApiKeyField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApiKeySettingsUseCaseTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `configured key starts hidden without reading its value`() {
        val provider = mockk<SettingsProvider>(
            relaxed = true,
            moreInterfaces = arrayOf(ProviderKeyPresence::class),
        )
        every {
            (provider as ProviderKeyPresence).hasKey(LlmProvider.AI_TUNNEL)
        } returns true

        val state = ApiKeySettingsUseCase(provider, dispatcher)
            .initialState(ApiKeyField.AI_TUNNEL)

        assertIs<ApiKeyFieldState.StoredHidden>(state)
        verify(exactly = 0) { provider.aiTunnelKey }
    }

    @Test
    fun `missing key starts as an empty editable field`() {
        val provider = mockk<SettingsProvider>(relaxed = true)

        val state = ApiKeySettingsUseCase(provider, dispatcher)
            .initialState(ApiKeyField.AI_TUNNEL)

        assertEquals(ApiKeyFieldState.Editable(value = "", revealed = false), state)
    }

    @Test
    fun `reveal reads only the selected key`() = runTest(dispatcher) {
        val provider = mockk<SettingsProvider>(relaxed = true)
        every { provider.aiTunnelKey } returns "ait-secret"

        val result = ApiKeySettingsUseCase(provider, dispatcher)
            .reveal(ApiKeyField.AI_TUNNEL)

        assertEquals("ait-secret", result.getOrThrow())
        verify(exactly = 1) { provider.aiTunnelKey }
        verify(exactly = 0) { provider.gigaChatKey }
        verify(exactly = 0) { provider.qwenChatKey }
    }

    @Test
    fun `persisting blank input removes selected key`() = runTest(dispatcher) {
        val provider = mockk<SettingsProvider>(relaxed = true)
        every { provider.aiTunnelKey = any() } just runs

        ApiKeySettingsUseCase(provider, dispatcher)
            .persist(ApiKeyField.AI_TUNNEL, "")
            .getOrThrow()

        verify(exactly = 1) { provider.aiTunnelKey = null }
    }
}
