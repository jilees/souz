package ru.souz.tool.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import ru.souz.tool.BadInputException
import ru.souz.tool.web.internal.DuckDuckGoWebSearchProvider
import ru.souz.tool.web.internal.PacedWebHttpClient
import ru.souz.tool.web.internal.WebSearchProviderException
import ru.souz.tool.web.internal.WebSearchProviderFailureKind
import ru.souz.tool.web.internal.WebTextResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DuckDuckGoWebSearchProviderTest {
    @Test
    fun `parses duckduckgo redirects into search results`() = runTest {
        val provider = providerReturning(
            WebTextResponse(
                statusCode = 200,
                body = """
                    <html><body>
                      <div class="result">
                        <a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com%2Freport%3Fref%3Dabc%26lang%3Den">Example report</a>
                        <div class="result__snippet">Useful snippet</div>
                      </div>
                    </body></html>
                """.trimIndent(),
                headers = emptyMap(),
            ),
        )

        val results = provider.search("example report", limit = 1)

        assertEquals(1, results.size)
        assertEquals("Example report", results.single().title)
        assertEquals("https://example.com/report?ref=abc&lang=en", results.single().url)
        assertEquals("Useful snippet", results.single().snippet)
    }

    @Test
    fun `falls back to the html endpoint when the primary one fails`() = runTest {
        val responses = ArrayDeque(
            listOf(
                Result.failure(BadInputException("primary endpoint failed")),
                Result.success(
                    WebTextResponse(
                        statusCode = 200,
                        body = """
                            <html><body>
                              <article data-testid="result">
                                <h2><a data-testid="result-title-a" href="/l/?uddg=https%3A%2F%2Fexample.com%2Ffallback">Fallback result</a></h2>
                                <div class="result-snippet">Fallback snippet</div>
                              </article>
                            </body></html>
                        """.trimIndent(),
                        headers = emptyMap(),
                    )
                ),
            )
        )
        val requestedUrls = mutableListOf<String>()
        val provider = DuckDuckGoWebSearchProvider(
            http = PacedWebHttpClient(httpGet = { url, _, _, _ ->
                requestedUrls += url
                responses.removeFirst().getOrThrow()
            }),
        )

        val results = provider.search("fallback result", limit = 1)

        assertEquals(listOf("https://example.com/fallback"), results.map { it.url })
        assertEquals(2, requestedUrls.size)
        assertTrue(requestedUrls.first().startsWith("https://duckduckgo.com/html/"))
        assertTrue(requestedUrls.last().startsWith("https://html.duckduckgo.com/html/"))
    }

    @Test
    fun `challenge page aborts immediately with provider blocked`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val provider = DuckDuckGoWebSearchProvider(
            http = PacedWebHttpClient(httpGet = { url, _, _, _ ->
                requestedUrls += url
                WebTextResponse(
                    statusCode = 200,
                    body = """
                        <html><head><title>DuckDuckGo</title></head><body>
                          Unfortunately, bots use DuckDuckGo too.
                          Please complete the following challenge to confirm this search was made by a human.
                          Select all squares containing a duck.
                        </body></html>
                    """.trimIndent(),
                    headers = emptyMap(),
                )
            }),
        )

        val error = assertFailsWith<WebSearchProviderException> { provider.search("blocked", limit = 1) }

        assertEquals(WebSearchProviderFailureKind.BLOCKED, error.kind)
        assertEquals(1, requestedUrls.size)
    }

    @Test
    fun `timeouts on every endpoint surface provider unavailable`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val provider = DuckDuckGoWebSearchProvider(
            http = PacedWebHttpClient(httpGet = { url, _, _, _ ->
                requestedUrls += url
                throw BadInputException("HTTP request timed out for $url")
            }),
        )

        val error = assertFailsWith<WebSearchProviderException> { provider.search("timeout burst", limit = 1) }

        assertEquals(WebSearchProviderFailureKind.UNAVAILABLE, error.kind)
        assertEquals(2, requestedUrls.size)
    }

    @Test
    fun `propagates cancellation instead of returning empty results`() = runTest {
        val provider = DuckDuckGoWebSearchProvider(
            http = PacedWebHttpClient(httpGet = { _, _, _, _ -> throw CancellationException("cancel search") }),
        )

        assertFailsWith<CancellationException> { provider.search("cancelled", limit = 1) }
    }

    private fun providerReturning(vararg responses: WebTextResponse): DuckDuckGoWebSearchProvider {
        val queue = ArrayDeque(responses.toList())
        return DuckDuckGoWebSearchProvider(
            http = PacedWebHttpClient(httpGet = { _, _, _, _ -> queue.removeFirst() }),
        )
    }
}
