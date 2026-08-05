package ru.souz.backend.app

import org.kodein.di.DI
import org.kodein.di.direct
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import kotlinx.coroutines.runBlocking
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.telegram.TelegramBotPollingService
import ru.souz.backend.client.ClientThreadRecoveryService
import ru.souz.db.SettingsProvider
import ru.souz.llms.local.LocalLlamaRuntime

private val log = LoggerFactory.getLogger("SouzBackendRuntime")

/** Process-wide backend runtime container with shared services and LLM resources. */
class BackendRuntime private constructor(
    private val di: DI,
) : AutoCloseable {
    internal val httpDependencies: BackendHttpDependencies by lazy { di.direct.instance() }
    private val telegramBotPollingService: TelegramBotPollingService? by lazy {
        if (httpDependencies.featureFlags.telegramBot) di.direct.instance() else null
    }
    private val resources: BackendRuntimeResources by lazy { di.direct.instance() }
    private val applicationScope: BackendApplicationScope by lazy { di.direct.instance() }
    private val clientThreadRecoveryService: ClientThreadRecoveryService by lazy { di.direct.instance() }
    private val localRuntime: LocalLlamaRuntime by lazy { di.direct.instance() }

    fun startBackgroundServices() {
        if (httpDependencies.featureFlags.wsEvents) {
            runBlocking { clientThreadRecoveryService.recover() }
            clientThreadRecoveryService.start(applicationScope)
        }
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
