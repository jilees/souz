package ru.souz.llms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import ru.souz.llms.codex.CodexEndpoints

class CodexEndpointsTest {
    @Test
    fun `missing and blank overrides retain official endpoints`() {
        for (value in listOf(null, "", "  ")) {
            val endpoints = CodexEndpoints.load { value }
            assertEquals("https://chatgpt.com/backend-api/codex/responses", endpoints.responsesUrl)
            assertEquals("https://auth.openai.com/oauth/token", endpoints.oauthTokenUrl)
            assertEquals("https://auth.openai.com/api/accounts/deviceauth/usercode", endpoints.deviceUserCodeUrl)
            assertEquals("https://auth.openai.com/api/accounts/deviceauth/token", endpoints.deviceTokenUrl)
        }
    }

    @Test
    fun `base overrides preserve proxy prefixes and append fixed API paths`() {
        val values = mapOf(
            "CODEX_BASE_URL" to " https://relay.test/souz-llm/chatgpt/// ",
            "CODEX_AUTH_BASE_URL" to "http://localhost:8080/souz-llm/auth/",
        )
        val endpoints = CodexEndpoints.load(values::get)
        assertEquals("https://relay.test/souz-llm/chatgpt", endpoints.baseUrl)
        assertEquals("https://relay.test/souz-llm/chatgpt/backend-api/codex/responses", endpoints.responsesUrl)
        assertEquals("http://localhost:8080/souz-llm/auth/oauth/token", endpoints.oauthTokenUrl)
        assertEquals("http://localhost:8080/souz-llm/auth/api/accounts/deviceauth/usercode", endpoints.deviceUserCodeUrl)
        assertEquals("http://localhost:8080/souz-llm/auth/api/accounts/deviceauth/token", endpoints.deviceTokenUrl)
        assertEquals(CodexEndpoints(baseUrl = "https://relay.test"),
            CodexEndpoints.load { if (it == "CODEX_BASE_URL") "https://relay.test" else null })
    }

    @Test
    fun `invalid endpoints fail without echoing sensitive URL contents`() {
        for (name in listOf("CODEX_BASE_URL", "CODEX_AUTH_BASE_URL")) {
            for (address in listOf("/", "/relative", "https://", "ftp://relay.test/path", "https://relay.test:0/path",
                "https://relay.test:65536/path", "https://user:private@relay.test/path",
                "https://relay.test/path?token=private", "https://relay.test/path#private", "https://bad host/path")) {
                val error = assertFailsWith<IllegalArgumentException> {
                    CodexEndpoints.load { if (it == name) address else null }
                }
                assertEquals("$name must be an absolute HTTP(S) URL without credentials, query or fragment.", error.message)
                assertFalse(error.message.orEmpty().contains("private"))
            }
        }
    }
}
