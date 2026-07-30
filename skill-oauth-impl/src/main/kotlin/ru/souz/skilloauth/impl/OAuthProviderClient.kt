package ru.souz.skilloauth.impl

/**
 * Adapter for one external OAuth2 provider. [SkillOAuthApiImpl] is generic over this interface —
 * it knows nothing about Yandex specifically. A provider's name (e.g. "yandex") only ever appears
 * as the key under which its [OAuthProviderClient] is registered in the `providers` map passed to
 * [SkillOAuthApiImpl], and in that provider's own config/env var naming (see [YandexOAuthClient]).
 * Adding a second provider means adding another map entry, not touching the core service logic.
 */
interface OAuthProviderClient {
    fun buildAuthorizeUrl(state: String, scopes: List<String>): String

    suspend fun exchangeCode(code: String): OAuthTokenResult

    suspend fun refresh(refreshToken: String): OAuthTokenResult
}

data class OAuthTokenResult(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long?,
    val scopes: List<String>,
)
