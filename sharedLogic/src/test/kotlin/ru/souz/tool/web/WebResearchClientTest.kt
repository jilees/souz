package ru.souz.tool.web

import kotlinx.coroutines.test.runTest
import ru.souz.tool.web.internal.PacedWebHttpClient
import ru.souz.tool.web.internal.WebResearchClient
import ru.souz.tool.web.internal.WebSearchProvider
import ru.souz.tool.web.internal.WebSearchProviderException
import ru.souz.tool.web.internal.WebSearchProviderFailureKind
import ru.souz.tool.web.internal.WebSearchResult
import ru.souz.tool.web.internal.WebTextResponse
import ru.souz.tool.web.internal.WebToolSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebResearchClientTest {
    @Test
    fun `spaces sequential requests to one host by the minimum interval`() = runTest {
        var nowMillis = 0L
        val sleeps = mutableListOf<Long>()
        val client = WebResearchClient(
            http = PacedWebHttpClient(
                httpGet = { _, _, _, _ -> WebTextResponse(200, "<html><body>Page body</body></html>", emptyMap()) },
                currentTimeMillis = { nowMillis },
                sleepMillis = { delayMillis -> sleeps += delayMillis; nowMillis += delayMillis },
            ),
        )

        client.extractPageText("https://example.com/one", maxChars = 500)
        client.extractPageText("https://example.com/two", maxChars = 500)

        assertEquals(listOf(1_200L), sleeps)
    }

    @Test
    fun `does not rate limit different hosts together`() = runTest {
        var nowMillis = 0L
        val sleeps = mutableListOf<Long>()
        val client = WebResearchClient(
            http = PacedWebHttpClient(
                httpGet = { _, _, _, _ -> WebTextResponse(200, "<html><body>Page body</body></html>", emptyMap()) },
                currentTimeMillis = { nowMillis },
                sleepMillis = { delayMillis -> sleeps += delayMillis; nowMillis += delayMillis },
            ),
        )

        client.extractPageText("https://example.com/one", maxChars = 500)
        client.extractPageText("https://other.example/two", maxChars = 500)

        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `returns the first provider that produces results and skips the rest`() = runTest {
        val primary = FakeProvider("primary") { _, _ ->
            listOf(WebSearchResult("Primary", "https://example.com/primary", ""))
        }
        val secondary = FakeProvider("secondary") { _, _ -> error("must not be queried") }
        val client = WebResearchClient(searchProviders = listOf(primary, secondary))

        val results = client.searchWeb("query", limit = 3)

        assertEquals(listOf("https://example.com/primary"), results.map { it.url })
        assertEquals(0, secondary.calls)
    }

    @Test
    fun `skips unconfigured providers`() = runTest {
        val disabled = FakeProvider("disabled", isConfigured = false) { _, _ -> error("must not be queried") }
        val fallback = FakeProvider("fallback") { _, _ ->
            listOf(WebSearchResult("Fallback", "https://example.com/fallback", ""))
        }
        val client = WebResearchClient(searchProviders = listOf(disabled, fallback))

        val results = client.searchWeb("query", limit = 3)

        assertEquals(listOf("https://example.com/fallback"), results.map { it.url })
        assertEquals(0, disabled.calls)
    }

    @Test
    fun `falls through to the next provider when one fails, and surfaces the failure if none recover`() = runTest {
        val flaky = FakeProvider("flaky") { _, _ ->
            throw WebSearchProviderException(WebSearchProviderFailureKind.BLOCKED, "blocked")
        }
        val alsoEmpty = FakeProvider("also-empty") { _, _ -> emptyList() }
        val client = WebResearchClient(searchProviders = listOf(flaky, alsoEmpty))

        val error = assertFailsWith<WebSearchProviderException> { client.searchWeb("query", limit = 3) }

        assertEquals(WebSearchProviderFailureKind.BLOCKED, error.kind)
        assertEquals(1, alsoEmpty.calls)
    }

    @Test
    fun `uses Brave as the primary provider end to end when a token is configured`() = runTest {
        val client = WebResearchClient(
            webToolSupport = WebToolSupport(braveSearchApiKey = "brave-token"),
            http = PacedWebHttpClient(httpGet = { url, _, _, _ ->
                if ("duckduckgo" in url) error("DuckDuckGo must not be queried when Brave answers")
                WebTextResponse(
                    statusCode = 200,
                    body = """{"web":{"results":[{"title":"Brave hit","url":"https://example.com/brave","description":"d"}]}}""",
                    headers = emptyMap(),
                )
            }),
        )

        val results = client.searchWeb("eclipse", limit = 3)

        assertEquals(listOf("https://example.com/brave"), results.map { it.url })
    }

    @Test
    fun `falls back to DuckDuckGo end to end when Brave yields nothing`() = runTest {
        val client = WebResearchClient(
            webToolSupport = WebToolSupport(braveSearchApiKey = "brave-token"),
            http = PacedWebHttpClient(httpGet = { url, _, _, _ ->
                when {
                    "api.search.brave.com" in url ->
                        WebTextResponse(429, """{"error":"rate limited"}""", emptyMap())
                    else -> WebTextResponse(
                        statusCode = 200,
                        body = """
                            <html><body>
                              <div class="result">
                                <a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com%2Fddg">DDG fallback</a>
                              </div>
                            </body></html>
                        """.trimIndent(),
                        headers = emptyMap(),
                    )
                }
            }),
        )

        val results = client.searchWeb("eclipse", limit = 3)

        assertEquals(listOf("https://example.com/ddg"), results.map { it.url })
    }

    @Test
    fun `does not query Brave end to end without a token`() = runTest {
        val client = WebResearchClient(
            webToolSupport = WebToolSupport(braveSearchApiKey = null),
            http = PacedWebHttpClient(httpGet = { url, _, _, _ ->
                if ("api.search.brave.com" in url) error("Brave must not be queried without a token")
                WebTextResponse(
                    statusCode = 200,
                    body = """
                        <html><body>
                          <div class="result">
                            <a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com%2Fddg-only">DDG only</a>
                          </div>
                        </body></html>
                    """.trimIndent(),
                    headers = emptyMap(),
                )
            }),
        )

        val results = client.searchWeb("plain query", limit = 3)

        assertEquals(listOf("https://example.com/ddg-only"), results.map { it.url })
    }

    private class FakeProvider(
        override val id: String,
        override val isConfigured: Boolean = true,
        private val behavior: suspend (query: String, limit: Int) -> List<WebSearchResult>,
    ) : WebSearchProvider {
        var calls = 0
            private set

        override suspend fun search(query: String, limit: Int): List<WebSearchResult> {
            calls += 1
            return behavior(query, limit)
        }
    }
}
