package ru.souz.tool.web

import kotlinx.coroutines.test.runTest
import ru.souz.tool.BadInputException
import ru.souz.tool.web.internal.BraveWebSearchProvider
import ru.souz.tool.web.internal.PacedWebHttpClient
import ru.souz.tool.web.internal.WebToolSupport
import ru.souz.tool.web.internal.WebTextResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BraveWebSearchProviderTest {
    @Test
    fun `is not configured without a subscription token`() {
        val provider = BraveWebSearchProvider(webToolSupport = WebToolSupport(braveSearchApiKey = null))
        assertFalse(provider.isConfigured)
    }

    @Test
    fun `parses web results and sends the subscription token`() = runTest {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val provider = provider("brave-token") { url, headers ->
            calls += url to headers
            WebTextResponse(
                statusCode = 200,
                body = """
                    {
                      "web": {
                        "results": [
                          {
                            "title": "Total <strong>solar eclipse</strong> over Russia",
                            "url": "https://example.com/eclipse",
                            "description": "The next <strong>total</strong> eclipse visible from Russia."
                          },
                          {
                            "title": "Second source",
                            "url": "https://example.org/second",
                            "description": "More detail"
                          }
                        ]
                      }
                    }
                """.trimIndent(),
                headers = emptyMap(),
            )
        }

        val results = provider.search("total solar eclipse Russia", limit = 2)

        assertEquals(
            listOf("https://example.com/eclipse", "https://example.org/second"),
            results.map { it.url },
        )
        assertEquals("Total solar eclipse over Russia", results.first().title)
        assertEquals("The next total eclipse visible from Russia.", results.first().snippet)
        assertTrue(provider.isConfigured)
        assertEquals(1, calls.size)
        assertTrue(calls.single().first.startsWith("https://api.search.brave.com/res/v1/web/search?q="))
        assertEquals("brave-token", calls.single().second["X-Subscription-Token"])
        assertEquals("application/json", calls.single().second["Accept"])
    }

    @Test
    fun `returns no results on a non-2xx response`() = runTest {
        val provider = provider("brave-token") { _, _ ->
            WebTextResponse(statusCode = 429, body = """{"error":"rate limited"}""", headers = emptyMap())
        }

        assertEquals(emptyList(), provider.search("rate limited", limit = 3))
    }

    @Test
    fun `returns no results when the payload has no web results`() = runTest {
        val provider = provider("brave-token") { _, _ ->
            WebTextResponse(statusCode = 200, body = """{"query":{"original":"x"}}""", headers = emptyMap())
        }

        assertEquals(emptyList(), provider.search("empty", limit = 3))
    }

    @Test
    fun `lets IO errors propagate for the engine to handle`() = runTest {
        val provider = provider("brave-token") { _, _ -> throw BadInputException("Brave Search API timed out") }

        assertFailsWith<BadInputException> { provider.search("io error", limit = 3) }
    }

    private fun provider(
        token: String?,
        handler: (url: String, headers: Map<String, String>) -> WebTextResponse,
    ): BraveWebSearchProvider = BraveWebSearchProvider(
        http = PacedWebHttpClient(httpGet = { url, _, _, headers -> handler(url, headers) }),
        webToolSupport = WebToolSupport(braveSearchApiKey = token),
    )
}
