package ru.souz.backend.app

import org.kodein.di.DI
import org.kodein.di.direct
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.souz.backend.bootstrap.BackendBootstrapService
import ru.souz.backend.chat.service.ChatService
import ru.souz.backend.chat.service.MessageService
import ru.souz.backend.options.service.OptionService
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.keys.service.UserProviderKeyService
import ru.souz.backend.onboarding.BackendOnboardingService
import ru.souz.backend.settings.service.UserSettingsService
import ru.souz.backend.telegram.TelegramBotBindingService
import ru.souz.backend.telegram.TelegramBotPollingService
import ru.souz.backend.user.repository.UserRepository
import ru.souz.db.SettingsProvider
import ru.souz.llms.local.LocalLlamaRuntime

private val log = LoggerFactory.getLogger("SouzBackendRuntime")

/** Process-wide backend runtime container with shared services and LLM resources. */
class BackendRuntime private constructor(
    private val di: DI,
) : AutoCloseable {
    val bootstrapService: BackendBootstrapService by lazy { di.direct.instance() }
    val onboardingService: BackendOnboardingService by lazy { di.direct.instance() }
    val userSettingsService: UserSettingsService by lazy { di.direct.instance() }
    val userProviderKeyService: UserProviderKeyService by lazy { di.direct.instance() }
    val chatService: ChatService by lazy { di.direct.instance() }
    val messageService: MessageService by lazy { di.direct.instance() }
    val executionService: AgentExecutionService by lazy { di.direct.instance() }
    val optionService: OptionService by lazy { di.direct.instance() }
    val eventService: AgentEventService by lazy { di.direct.instance() }
    val featureFlags: BackendFeatureFlags by lazy { di.direct.instance() }
    val telegramBotBindingService: TelegramBotBindingService? by lazy {
        if (featureFlags.telegramBot) di.direct.instance() else null
    }
    val telegramBotPollingService: TelegramBotPollingService? by lazy {
        if (featureFlags.telegramBot) di.direct.instance() else null
    }
    val userRepository: UserRepository by lazy { di.direct.instance() }
    private val resources: BackendRuntimeResources by lazy { di.direct.instance() }
    private val settingsProvider: SettingsProvider by lazy { di.direct.instance() }
    private val localRuntime: LocalLlamaRuntime by lazy { di.direct.instance() }

    fun selectedModel(): String = settingsProvider.gigaModel.alias

    fun startBackgroundServices() {
        telegramBotPollingService?.start()
    }

    override fun close() {
        localRuntime.close()
        resources.close()
    }

    companion object {
        fun create(
            appConfig: BackendAppConfig = BackendAppConfig.load().validate(),
        ): BackendRuntime {
            val di = DI {
                import(
                    backendDiModule(
                        systemPrompt = backendSystemPrompt(),
                        appConfig = appConfig,
                    )
                )
            }
            val settingsProvider = di.direct.instance<SettingsProvider>()
            seedCodexCredentialsFromEnv(settingsProvider)
            applyRegionProfileFromEnv(settingsProvider)
            return BackendRuntime(di = di)
        }

        private fun backendSystemPrompt(): String =
            System.getenv("SOUZ_BACKEND_SYSTEM_PROMPT")
                ?: System.getProperty("souz.backend.systemPrompt")
                ?: "You are Souz AI backend assistant. Answer directly and concisely in the user's language."

        /**
         * One-time import path for Codex OAuth credentials obtained elsewhere (the
         * device-code flow itself is desktop-UI-only and not exposed by the backend).
         * Only seeds when ConfigStore doesn't already hold a token, so a later refresh
         * persisted by CodexOAuthService is never clobbered by a stale env value on restart.
         */
        private fun seedCodexCredentialsFromEnv(settingsProvider: SettingsProvider) {
            if (!settingsProvider.codexAccessToken.isNullOrBlank()) return
            val accessToken = configValue("SOUZ_BACKEND_CODEX_ACCESS_TOKEN", "souz.backend.codex.accessToken")
                ?.trim()?.takeIf { it.isNotEmpty() } ?: return
            settingsProvider.codexAccessToken = accessToken
            settingsProvider.codexRefreshToken =
                configValue("SOUZ_BACKEND_CODEX_REFRESH_TOKEN", "souz.backend.codex.refreshToken")
            settingsProvider.codexAccountId =
                configValue("SOUZ_BACKEND_CODEX_ACCOUNT_ID", "souz.backend.codex.accountId")
            settingsProvider.codexExpiresAt =
                configValue("SOUZ_BACKEND_CODEX_EXPIRES_AT", "souz.backend.codex.expiresAt")?.toLongOrNull()
            log.info("Seeded Codex credentials from environment on first boot.")
        }

        private fun configValue(envKey: String, propertyKey: String): String? =
            System.getenv(envKey) ?: System.getProperty(propertyKey)

        /**
         * Deployment-wide region/edition choice (RU vs EN provider defaults and
         * priorities, see LlmBuildProfile). Requests without an explicit locale
         * (e.g. Telegram-originated turns) fall back to this. Always applied from
         * env on boot, unlike the Codex seed, since it's a deployment setting rather
         * than runtime-refreshed credential state.
         */
        private fun applyRegionProfileFromEnv(settingsProvider: SettingsProvider) {
            val region = configValue("SOUZ_BACKEND_REGION_PROFILE", "souz.backend.regionProfile")
                ?.trim()?.takeIf { it.isNotEmpty() } ?: return
            settingsProvider.regionProfile = region
            log.info("Backend region profile set to '{}' from environment.", settingsProvider.regionProfile)
        }
    }
}
