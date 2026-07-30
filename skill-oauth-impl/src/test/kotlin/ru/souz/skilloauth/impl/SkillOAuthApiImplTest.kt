package ru.souz.skilloauth.impl

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import ru.souz.skilloauth.ApiCallRequest
import ru.souz.skilloauth.SkillOAuthException

/**
 * Covers the parts of [SkillOAuthApiImpl] that do not require a live network call
 * (status/startAuthorization use no HTTP; `buildAuthorizeUrl` is a pure string builder).
 * Token-exchange/refresh paths (which call the real Yandex endpoints) are not covered
 * here — see the plan's noted gap around HTTP-mocked provider tests.
 */
class SkillOAuthApiImplTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

    private fun newApi(
        credentialRepository: SkillOAuthCredentialRepository = InMemorySkillOAuthCredentialRepository(),
        pendingStateRepository: SkillOAuthPendingStateRepository = InMemorySkillOAuthPendingStateRepository(),
    ) = SkillOAuthApiImpl(
        credentialRepository = credentialRepository,
        pendingStateRepository = pendingStateRepository,
        crypto = SkillOAuthTokenCrypto(
            java.util.Base64.getEncoder().encodeToString(ByteArray(32))
        ),
        providers = mapOf(
            "yandex" to YandexOAuthClient(
                config = YandexOAuthConfig(clientId = "client-1", clientSecret = "secret-1", redirectUri = "https://backend.example/oauth/callback"),
            )
        ),
        clock = fixedClock,
    )

    @Test
    fun `status reports not connected when no credential is stored`() = runTest {
        val api = newApi()

        val status = api.status(userId = "user-1", provider = "yandex")

        assertFalse(status.connected)
        assertTrue(status.grantedScopes.isEmpty())
    }

    @Test
    fun `status reports connected with granted scopes once a credential exists`() = runTest {
        val credentialRepository = InMemorySkillOAuthCredentialRepository()
        credentialRepository.upsert(
            SkillOAuthCredential(
                userId = "user-1",
                provider = "yandex",
                accessTokenEncrypted = "enc",
                refreshTokenEncrypted = null,
                grantedScopes = listOf("login:info"),
                expiresAt = null,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        )
        val api = newApi(credentialRepository = credentialRepository)

        val status = api.status(userId = "user-1", provider = "yandex")

        assertTrue(status.connected)
        assertEquals(listOf("login:info"), status.grantedScopes)
    }

    @Test
    fun `startAuthorization stores a pending state and returns an authorize URL`() = runTest {
        val pendingStateRepository = InMemorySkillOAuthPendingStateRepository()
        val api = newApi(pendingStateRepository = pendingStateRepository)

        val result = api.startAuthorization(
            userId = "user-1",
            provider = "yandex",
            skillId = "skill-1",
            scopes = listOf("login:info"),
        )

        assertTrue(result.url.startsWith("https://oauth.yandex.ru/authorize?"))
        assertTrue(result.url.contains("client_id=client-1"))
        assertTrue(result.url.contains("scope=login%3Ainfo"))
    }

    @Test
    fun `callAuthorizedApi rejects a skill that is not connected yet`() = runTest {
        val api = newApi()

        assertFailsWith<SkillOAuthException> {
            api.callAuthorizedApi(
                userId = "user-1",
                provider = "yandex",
                skillId = "skill-1",
                request = ApiCallRequest(method = "GET", path = "https://login.yandex.ru/info"),
            )
        }
    }

    @Test
    fun `unsupported providers are rejected across all entry points`() = runTest {
        val api = newApi()

        assertFailsWith<SkillOAuthException> { api.status("user-1", "github") }
        assertFailsWith<SkillOAuthException> {
            api.startAuthorization("user-1", "github", "skill-1", emptyList())
        }
        assertFailsWith<SkillOAuthException> {
            api.callAuthorizedApi("user-1", "github", "skill-1", ApiCallRequest("GET", "https://example.com"))
        }
    }
}
