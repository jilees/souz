package ru.souz.skilloauth.impl

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import ru.souz.skilloauth.SkillOAuthException

/**
 * [OAuthProviderClient] for Yandex — the reference/pilot provider for this subsystem — per
 * https://oauth.yandex.ru/authorize and https://oauth.yandex.ru/token (standard
 * authorization-code OAuth2). Not PKCE — Yandex's classic web flow relies on a confidential
 * client (client_id + client_secret held only by the backend). Nothing outside this file and its
 * DI wiring in `:backend` knows this is Yandex specifically; [SkillOAuthApiImpl] only sees an
 * [OAuthProviderClient] registered under the name "yandex".
 */
class YandexOAuthConfig(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
)

class YandexOAuthClient(
    private val config: YandexOAuthConfig,
    private val httpClient: HttpClient = HttpClient(CIO),
) : OAuthProviderClient {
    private val mapper = jacksonObjectMapper()

    override fun buildAuthorizeUrl(state: String, scopes: List<String>): String {
        val query = buildString {
            append("response_type=code")
            append("&client_id=${config.clientId.encodeURLParameter()}")
            append("&redirect_uri=${config.redirectUri.encodeURLParameter()}")
            append("&state=${state.encodeURLParameter()}")
            if (scopes.isNotEmpty()) {
                append("&scope=${scopes.joinToString(" ").encodeURLParameter()}")
            }
        }
        return "$AUTHORIZE_ENDPOINT?$query"
    }

    override suspend fun exchangeCode(code: String): OAuthTokenResult =
        httpClient.submitForm(
            url = TOKEN_ENDPOINT,
            formParameters = Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("client_id", config.clientId)
                append("client_secret", config.clientSecret)
                append("redirect_uri", config.redirectUri)
            },
        ).bodyAsText().toTokenResult()

    override suspend fun refresh(refreshToken: String): OAuthTokenResult =
        httpClient.submitForm(
            url = TOKEN_ENDPOINT,
            formParameters = Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", config.clientId)
                append("client_secret", config.clientSecret)
            },
        ).bodyAsText().toTokenResult()

    private fun String.toTokenResult(): OAuthTokenResult {
        val parsed = mapper.readValue(this, YandexTokenResponse::class.java)
        val accessToken = parsed.access_token
            ?: throw SkillOAuthException(
                "Yandex OAuth token request failed: ${parsed.error ?: "unknown_error"} ${parsed.error_description.orEmpty()}"
            )
        return OAuthTokenResult(
            accessToken = accessToken,
            refreshToken = parsed.refresh_token,
            expiresInSeconds = parsed.expires_in,
            scopes = parsed.scope?.split(" ")?.filter(String::isNotBlank) ?: emptyList(),
        )
    }

    private companion object {
        const val AUTHORIZE_ENDPOINT = "https://oauth.yandex.ru/authorize"
        const val TOKEN_ENDPOINT = "https://oauth.yandex.ru/token"
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class YandexTokenResponse(
    val access_token: String? = null,
    val refresh_token: String? = null,
    val expires_in: Long? = null,
    val scope: String? = null,
    val error: String? = null,
    val error_description: String? = null,
)
