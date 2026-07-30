package ru.souz.skilloauth

/**
 * Host-owned OAuth connection/token service consumed by skill tools.
 *
 * Implementations own the whole OAuth lifecycle (authorization, token storage,
 * refresh, and the actual authorized HTTP call) so that a raw access token never
 * has to cross into a skill's sandboxed process or the LLM's context. The
 * inbound OAuth redirect callback is deliberately not part of this interface —
 * it is triggered by the provider, not by any caller of this API — see the
 * route-installer function in `:skill-oauth-impl` instead.
 *
 * All methods are `suspend` so that a local, in-process implementation can later
 * be swapped for a thin HTTP/gRPC client talking to an extracted microservice
 * without any change to callers.
 */
interface SkillOAuthApi {
    suspend fun status(userId: String, provider: String): OAuthStatus

    suspend fun startAuthorization(
        userId: String,
        provider: String,
        skillId: String,
        scopes: List<String>,
    ): AuthorizationUrl

    suspend fun callAuthorizedApi(
        userId: String,
        provider: String,
        skillId: String,
        request: ApiCallRequest,
    ): ApiCallResponse
}

data class OAuthStatus(
    val connected: Boolean,
    val grantedScopes: List<String> = emptyList(),
)

data class AuthorizationUrl(
    val url: String,
)

data class ApiCallRequest(
    val method: String,
    val path: String,
    val body: String? = null,
)

data class ApiCallResponse(
    val statusCode: Int,
    val body: String,
)

class SkillOAuthException(message: String) : Exception(message)
