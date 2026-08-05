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
            "yandex" to AuthorizationCodeOAuthClient(
                config = AuthorizationCodeOAuthConfig(
                    name = "yandex",
                    authorizeEndpoint = "https://oauth.yandex.ru/authorize",
                    tokenEndpoint = "https://oauth.yandex.ru/token",
                    clientId = "client-1",
                    clientSecret = "secret-1",
                    redirectUri = "https://backend.example/oauth/callback",
                    allowedApiHosts = setOf("login.yandex.ru"),
                ),
            )
        ),
        clock = fixedClock,
    )

    private suspend fun connectedCredentialRepository(
        grantedScopes: List<String> = listOf("login:info"),
    ): SkillOAuthCredentialRepository {
        val repository = InMemorySkillOAuthCredentialRepository()
        repository.upsert(
            SkillOAuthCredential(
                userId = "user-1",
                provider = "yandex",
                accessTokenEncrypted = "enc",
                refreshTokenEncrypted = null,
                grantedScopes = grantedScopes,
                expiresAt = null,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        )
        return repository
    }

    @Test
    fun `status reports not connected when no credential is stored`() = runTest {
        val api = newApi()

        val status = api.status(userId = "user-1", provider = "yandex")

        assertFalse(status.connected)
        assertTrue(status.grantedScopes.isEmpty())
    }

    @Test
    fun `status reports connected with granted scopes once a credential exists`() = runTest {
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("login:info")))

        val status = api.status(userId = "user-1", provider = "yandex")

        assertTrue(status.connected)
        assertEquals(listOf("login:info"), status.grantedScopes)
    }

    @Test
    fun `status reports not connected when granted scopes do not cover requiredScopes`() = runTest {
        // Regression test for a shared (userId, provider) credential accumulating scopes for one
        // skill that another, more narrowly-declared skill must not silently ride on.
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("login:info")))

        val status = api.status(userId = "user-1", provider = "yandex", requiredScopes = listOf("login:info", "iot:control"))

        assertFalse(status.connected)
        assertEquals(listOf("login:info"), status.grantedScopes)
        assertEquals(listOf("iot:control"), status.missingScopes)
    }

    @Test
    fun `status reports connected when requiredScopes are a subset of grantedScopes`() = runTest {
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("login:info", "iot:control", "iot:view")))

        val status = api.status(userId = "user-1", provider = "yandex", requiredScopes = listOf("iot:view"))

        assertTrue(status.connected)
        assertTrue(status.missingScopes.isEmpty())
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
                requiredScopes = emptyList(),
                request = ApiCallRequest(method = "GET", path = "https://login.yandex.ru/info"),
            )
        }
    }

    @Test
    fun `callAuthorizedApi rejects a connected skill whose requiredScopes exceed grantedScopes`() = runTest {
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("login:info")))

        assertFailsWith<SkillOAuthException> {
            api.callAuthorizedApi(
                userId = "user-1",
                provider = "yandex",
                skillId = "skill-1",
                requiredScopes = listOf("login:info", "iot:control"),
                request = ApiCallRequest(method = "GET", path = "https://login.yandex.ru/info"),
            )
        }
    }

    @Test
    fun `callAuthorizedApi rejects a URL host outside the provider's allowlist`() = runTest {
        // Regression test: without this check, a hijacked model turn could redirect the real
        // bearer token to an attacker-controlled or internal host via the model-supplied `path`.
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("login:info")))

        assertFailsWith<SkillOAuthException> {
            api.callAuthorizedApi(
                userId = "user-1",
                provider = "yandex",
                skillId = "skill-1",
                requiredScopes = listOf("login:info"),
                request = ApiCallRequest(method = "GET", path = "https://attacker.example/exfil"),
            )
        }
    }

    @Test
    fun `callAuthorizedApi rejects a non-HTTPS URL even on an allowed host`() = runTest {
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("login:info")))

        assertFailsWith<SkillOAuthException> {
            api.callAuthorizedApi(
                userId = "user-1",
                provider = "yandex",
                skillId = "skill-1",
                requiredScopes = listOf("login:info"),
                request = ApiCallRequest(method = "GET", path = "http://login.yandex.ru/info"),
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
            api.callAuthorizedApi("user-1", "github", "skill-1", emptyList(), ApiCallRequest("GET", "https://example.com"))
        }
    }
}
