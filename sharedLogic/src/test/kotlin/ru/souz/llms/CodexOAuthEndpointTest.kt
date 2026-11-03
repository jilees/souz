package ru.souz.llms

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.codex.CodexEndpoints
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.codex.CodexOAuthState
import ru.souz.llms.http.providerHttpClientDefaults

class CodexOAuthEndpointTest {
    private val proxy = CodexEndpoints(
        baseUrl = "https://relay.test/souz-llm/chatgpt",
        authBaseUrl = "https://relay.test/souz-llm/auth",
    )
    private val accessToken = "header." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"chatgpt_account_id":"test-account"}""".toByteArray()) + ".signature"

    @Test
    fun `device login routes all steps while preserving provider client and redirect identity`() = runTest {
        for (endpoints in listOf(CodexEndpoints(), proxy)) {
            val settings = mockk<SettingsProvider>(relaxed = true)
            var accountId: String? = null
            every { settings.codexAccountId } answers { accountId }
            every { settings.codexAccountId = any() } answers { accountId = firstArg() }
            val engine = MockEngine { request ->
                val body = request.body.toByteArray().decodeToString()
                val response = when (request.url.toString()) {
                    endpoints.deviceUserCodeUrl -> {
                        assertEquals(CodexOAuthService.CLIENT_ID, restJsonMapper.readTree(body)["client_id"].asText())
                        """{"device_auth_id":"device-id","user_code":"test-code","interval":1}"""
                    }
                    endpoints.deviceTokenUrl -> {
                        assertEquals("device-id", restJsonMapper.readTree(body)["device_auth_id"].asText())
                        assertEquals("test-code", restJsonMapper.readTree(body)["user_code"].asText())
                        """{"authorization_code":"auth-code","code_verifier":"verifier"}"""
                    }
                    endpoints.oauthTokenUrl -> {
                        val form = parseQueryString(body)
                        assertEquals("authorization_code", form["grant_type"])
                        assertEquals("auth-code", form["code"])
                        assertEquals("verifier", form["code_verifier"])
                        assertEquals(CodexOAuthService.CLIENT_ID, form["client_id"])
                        assertEquals("https://auth.openai.com/deviceauth/callback", form["redirect_uri"])
                        """{"access_token":"$accessToken","refresh_token":"new-refresh","expires_in":3600}"""
                    }
                    else -> error("Unexpected OAuth destination")
                }
                respond(response, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
            HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
                val service = CodexOAuthService(settings, client, endpoints)
                service.startDeviceFlow()
                assertEquals("test-account", assertIs<CodexOAuthState.Success>(service.oauthState.value).accountId)
                assertEquals(3, engine.requestHistory.size)
                verify { settings.codexAccessToken = accessToken; settings.codexRefreshToken = "new-refresh" }
            }
        }
    }

    @Test
    fun `expired tokens refresh through configured endpoint and retain token rotation`() = runTest {
        val settings = mockk<SettingsProvider>(relaxed = true)
        every { settings.codexAccessToken } returns "expired-token"
        every { settings.codexRefreshToken } returns "old-refresh"
        every { settings.codexExpiresAt } returns 0L
        val engine = MockEngine { request ->
            assertEquals(proxy.oauthTokenUrl, request.url.toString())
            val form = parseQueryString(request.body.toByteArray().decodeToString())
            assertEquals("refresh_token", form["grant_type"])
            assertEquals("old-refresh", form["refresh_token"])
            assertEquals(CodexOAuthService.CLIENT_ID, form["client_id"])
            respond("""{"access_token":"$accessToken","refresh_token":"new-refresh","expires_in":3600}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
            assertEquals(accessToken, CodexOAuthService(settings, client, proxy).refreshTokenIfNeeded())
            assertEquals(1, engine.requestHistory.size)
            verify { settings.codexAccessToken = accessToken; settings.codexRefreshToken = "new-refresh" }
        }
    }
}
