package ru.souz.backend.app

import kotlin.test.Test
import kotlin.test.assertIs
import com.zaxxer.hikari.HikariDataSource
import org.kodein.di.DI
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.backend.agent.session.AgentStateRepository
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.options.repository.OptionRepository
import ru.souz.backend.events.repository.AgentEventRepository
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.keys.repository.UserProviderKeyRepository
import ru.souz.backend.keys.service.UserProviderKeyService
import ru.souz.backend.llm.LlmClientFactory
import ru.souz.backend.llm.quota.ExecutionQuotaManager
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.backend.storage.postgres.PostgresAgentEventRepository
import ru.souz.backend.storage.postgres.PostgresAgentExecutionRepository
import ru.souz.backend.storage.postgres.PostgresAgentStateRepository
import ru.souz.backend.storage.postgres.PostgresChatRepository
import ru.souz.backend.storage.postgres.PostgresMessageRepository
import ru.souz.backend.storage.postgres.PostgresOptionRepository
import ru.souz.backend.storage.postgres.PostgresTelegramBotBindingRepository
import ru.souz.backend.storage.postgres.PostgresUserRepository
import ru.souz.backend.storage.postgres.PostgresUserProviderKeyRepository
import ru.souz.backend.storage.postgres.PostgresUserSettingsRepository
import ru.souz.backend.telegram.TelegramBotBindingRepository
import ru.souz.backend.user.repository.UserRepository
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.skills.registry.FileSystemSkillRegistryRepository

class BackendDiModuleTest {
    @Test
    fun `backend binds only postgres repositories and keeps filesystem skill registry`() {
        val appConfig = BackendAppConfig(
            featureFlags = BackendFeatureFlags(),
            postgres = BackendPostgresConfig(
                host = "127.0.0.1",
                port = 5432,
                database = "souz",
                user = "souz",
                password = null,
                schema = "public",
                maxPoolSize = 4,
                connectionTimeoutMs = 30_000L,
            ),
            proxyToken = null,
            masterKey = "test-master-key",
        )
        val dataSource = HikariDataSource()
        val di = DI {
            import(
                backendDiModule(
                    systemPrompt = "test-system-prompt",
                    appConfig = appConfig,
                    dataSourceFactory = { dataSource },
                )
            )
        }

        try {
            assertIs<HikariDataSource>(di.direct.instance<HikariDataSource>())
            assertIs<PostgresUserRepository>(di.direct.instance<UserRepository>())
            assertIs<PostgresChatRepository>(di.direct.instance<ChatRepository>())
            assertIs<PostgresMessageRepository>(di.direct.instance<MessageRepository>())
            assertIs<PostgresAgentStateRepository>(di.direct.instance<AgentStateRepository>())
            assertIs<PostgresAgentExecutionRepository>(di.direct.instance<AgentExecutionRepository>())
            assertIs<PostgresOptionRepository>(di.direct.instance<OptionRepository>())
            assertIs<PostgresAgentEventRepository>(di.direct.instance<AgentEventRepository>())
            assertIs<PostgresUserSettingsRepository>(di.direct.instance<UserSettingsRepository>())
            assertIs<PostgresUserProviderKeyRepository>(di.direct.instance<UserProviderKeyRepository>())
            assertIs<PostgresTelegramBotBindingRepository>(di.direct.instance<TelegramBotBindingRepository>())
            assertIs<UserProviderKeyService>(di.direct.instance<UserProviderKeyService>())
            assertIs<ExecutionQuotaManager>(di.direct.instance<ExecutionQuotaManager>())
            assertIs<LlmClientFactory>(di.direct.instance<LlmClientFactory>())
            assertIs<FileSystemSkillRegistryRepository>(di.direct.instance<SkillRegistryRepository>())
        } finally {
            dataSource.close()
        }
    }
}
