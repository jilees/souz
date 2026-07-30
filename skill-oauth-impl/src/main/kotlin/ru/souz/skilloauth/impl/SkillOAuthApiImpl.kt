package ru.souz.skilloauth.impl

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import ru.souz.skilloauth.ApiCallRequest
import ru.souz.skilloauth.ApiCallResponse
import ru.souz.skilloauth.AuthorizationUrl
import ru.souz.skilloauth.OAuthStatus
import ru.souz.skilloauth.SkillOAuthApi
import ru.souz.skilloauth.SkillOAuthException

/**
 * Provider-agnostic: this class knows nothing about Yandex or any other specific provider. It is
 * generic over [OAuthProviderClient], resolved from [providers] by name — "yandex" only ever
 * appears as a map key supplied at DI-wiring time in `:backend`, not in any logic here. Adding a
 * second provider means adding another `providers` entry, not touching this class.
 *
 * `apiRequest.path` is treated as a full URL supplied by the skill (a single provider can expose
 * multiple API hosts, e.g. Yandex's login.yandex.ru vs. cloud-api.yandex.net) — this class only
 * injects the Authorization header and forwards the call.
 */
class SkillOAuthApiImpl(
    private val credentialRepository: SkillOAuthCredentialRepository,
    private val pendingStateRepository: SkillOAuthPendingStateRepository,
    private val crypto: SkillOAuthTokenCrypto,
    private val providers: Map<String, OAuthProviderClient>,
    private val httpClient: HttpClient = HttpClient(CIO),
    private val clock: Clock = Clock.systemUTC(),
) : SkillOAuthApi {

    override suspend fun status(userId: String, provider: String): OAuthStatus {
        requireProviderClient(provider)
        val credential = credentialRepository.find(userId, provider)
            ?: return OAuthStatus(connected = false)
        return OAuthStatus(connected = true, grantedScopes = credential.grantedScopes)
    }

    override suspend fun startAuthorization(
        userId: String,
        provider: String,
        skillId: String,
        scopes: List<String>,
    ): AuthorizationUrl {
        val providerClient = requireProviderClient(provider)
        val state = generateState()
        pendingStateRepository.create(
            SkillOAuthPendingState(
                state = state,
                userId = userId,
                skillId = skillId,
                provider = provider,
                requestedScopes = scopes,
                expiresAt = clock.instant().plusSeconds(PENDING_STATE_TTL_SECONDS),
            )
        )
        return AuthorizationUrl(providerClient.buildAuthorizeUrl(state = state, scopes = scopes))
    }

    override suspend fun callAuthorizedApi(
        userId: String,
        provider: String,
        skillId: String,
        request: ApiCallRequest,
    ): ApiCallResponse {
        val providerClient = requireProviderClient(provider)
        val credential = credentialRepository.find(userId, provider)
            ?: throw SkillOAuthException(
                "Skill '$skillId' is not connected to '$provider'. Use ConnectOAuthProvider first."
            )
        val accessToken = ensureFreshAccessToken(credential, providerClient)
        val apiRequest = request
        val response = httpClient.request(apiRequest.path) {
            method = HttpMethod.parse(apiRequest.method.uppercase())
            header("Authorization", "Bearer $accessToken")
            apiRequest.body?.let { setBody(it) }
        }
        return ApiCallResponse(statusCode = response.status.value, body = response.bodyAsText())
    }

    /**
     * Handles the provider redirect: exchanges `code` for tokens and stores them.
     * Intentionally not part of [SkillOAuthApi] — called only by the route installer in
     * [installSkillOAuthRoutes], never by tool/agent code, since the callback is triggered by the
     * provider's redirect rather than requested by any caller of this API.
     */
    internal suspend fun handleCallback(code: String, state: String): CallbackResult {
        val pending = pendingStateRepository.consume(state, clock.instant())
            ?: return CallbackResult.InvalidOrExpiredState
        val providerClient = providers[pending.provider]
            ?: return CallbackResult.ExchangeFailed("Unknown OAuth provider: ${pending.provider}")
        val tokenResult = try {
            providerClient.exchangeCode(code)
        } catch (e: SkillOAuthException) {
            return CallbackResult.ExchangeFailed(e.message ?: "OAuth token exchange failed.")
        }
        val now = clock.instant()
        credentialRepository.upsert(
            SkillOAuthCredential(
                userId = pending.userId,
                provider = pending.provider,
                accessTokenEncrypted = crypto.encrypt(tokenResult.accessToken),
                refreshTokenEncrypted = tokenResult.refreshToken?.let(crypto::encrypt),
                grantedScopes = tokenResult.scopes.ifEmpty { pending.requestedScopes },
                expiresAt = tokenResult.expiresInSeconds?.let { now.plusSeconds(it) },
                createdAt = now,
                updatedAt = now,
            )
        )
        return CallbackResult.Connected(pending.provider)
    }

    private suspend fun ensureFreshAccessToken(
        credential: SkillOAuthCredential,
        providerClient: OAuthProviderClient,
    ): String {
        val expiresAt = credential.expiresAt
        if (expiresAt == null || expiresAt.isAfter(clock.instant().plusSeconds(EXPIRY_SAFETY_MARGIN_SECONDS))) {
            return crypto.decrypt(credential.accessTokenEncrypted)
        }
        val refreshTokenEncrypted = credential.refreshTokenEncrypted
            ?: return crypto.decrypt(credential.accessTokenEncrypted)
        val refreshed = providerClient.refresh(crypto.decrypt(refreshTokenEncrypted))
        val now = clock.instant()
        credentialRepository.upsert(
            credential.copy(
                accessTokenEncrypted = crypto.encrypt(refreshed.accessToken),
                refreshTokenEncrypted = refreshed.refreshToken?.let(crypto::encrypt)
                    ?: credential.refreshTokenEncrypted,
                grantedScopes = refreshed.scopes.ifEmpty { credential.grantedScopes },
                expiresAt = refreshed.expiresInSeconds?.let { now.plusSeconds(it) },
                updatedAt = now,
            )
        )
        return refreshed.accessToken
    }

    private fun requireProviderClient(provider: String): OAuthProviderClient =
        providers[provider] ?: throw SkillOAuthException(
            "Unsupported OAuth provider: '$provider'. Configured providers: ${providers.keys.ifEmpty { setOf("none") }}"
        )

    private fun generateState(): String {
        val bytes = ByteArray(STATE_BYTES).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val PENDING_STATE_TTL_SECONDS = 600L
        const val EXPIRY_SAFETY_MARGIN_SECONDS = 60L
        const val STATE_BYTES = 32
    }
}

internal sealed interface CallbackResult {
    data class Connected(val provider: String) : CallbackResult
    data object InvalidOrExpiredState : CallbackResult
    data class ExchangeFailed(val reason: String) : CallbackResult
}
