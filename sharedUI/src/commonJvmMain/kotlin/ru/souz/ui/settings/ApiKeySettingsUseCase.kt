package ru.souz.ui.settings

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.souz.db.SettingsProvider
import ru.souz.db.hasKey
import ru.souz.llms.LlmProvider
import ru.souz.llms.VoiceRecognitionProvider
import ru.souz.ui.common.ApiKeyField

class ApiKeySettingsUseCase(
    private val settingsProvider: SettingsProvider,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    fun initialState(field: ApiKeyField): ApiKeyFieldState =
        if (isConfigured(field)) {
            ApiKeyFieldState.StoredHidden
        } else {
            ApiKeyFieldState.Editable(value = "", revealed = false)
        }

    suspend fun reveal(field: ApiKeyField): Result<String> = runCatching {
        withContext(dispatcher) {
            when (field) {
                ApiKeyField.GIGA_CHAT -> settingsProvider.gigaChatKey
                ApiKeyField.QWEN_CHAT -> settingsProvider.qwenChatKey
                ApiKeyField.AI_TUNNEL -> settingsProvider.aiTunnelKey
                ApiKeyField.ANTHROPIC -> settingsProvider.anthropicKey
                ApiKeyField.OPENAI -> settingsProvider.openaiKey
                ApiKeyField.SALUTE_SPEECH -> settingsProvider.saluteSpeechKey
                ApiKeyField.CODEX -> throw UnsupportedOperationException("Codex credentials are OAuth-controlled")
            }.orEmpty()
        }
    }

    suspend fun persist(field: ApiKeyField, value: String): Result<Unit> = runCatching {
        withContext(dispatcher) {
            val storedValue = value.takeUnless(String::isBlank)
            when (field) {
                ApiKeyField.GIGA_CHAT -> settingsProvider.gigaChatKey = storedValue
                ApiKeyField.QWEN_CHAT -> settingsProvider.qwenChatKey = storedValue
                ApiKeyField.AI_TUNNEL -> settingsProvider.aiTunnelKey = storedValue
                ApiKeyField.ANTHROPIC -> settingsProvider.anthropicKey = storedValue
                ApiKeyField.OPENAI -> settingsProvider.openaiKey = storedValue
                ApiKeyField.SALUTE_SPEECH -> settingsProvider.saluteSpeechKey = storedValue
                ApiKeyField.CODEX -> throw UnsupportedOperationException("Codex credentials are OAuth-controlled")
            }
        }
    }

    private fun isConfigured(field: ApiKeyField): Boolean = when (field) {
        ApiKeyField.GIGA_CHAT -> settingsProvider.hasKey(LlmProvider.GIGA)
        ApiKeyField.QWEN_CHAT -> settingsProvider.hasKey(LlmProvider.QWEN)
        ApiKeyField.AI_TUNNEL -> settingsProvider.hasKey(LlmProvider.AI_TUNNEL)
        ApiKeyField.ANTHROPIC -> settingsProvider.hasKey(LlmProvider.ANTHROPIC)
        ApiKeyField.OPENAI -> settingsProvider.hasKey(LlmProvider.OPENAI)
        ApiKeyField.SALUTE_SPEECH -> settingsProvider.hasKey(VoiceRecognitionProvider.SALUTE_SPEECH)
        ApiKeyField.CODEX -> settingsProvider.hasKey(LlmProvider.CODEX)
    }
}
