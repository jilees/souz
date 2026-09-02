package ru.souz.tool.web.internal

enum class WebSearchProviderFailureKind {
    BLOCKED,
    UNAVAILABLE,
}

/**
 * Signals that a search provider could not serve a query for a reason the caller should surface
 * (rather than silently treating as "no results"): the provider blocked automated access, or it is
 * timing out / erroring.
 */
class WebSearchProviderException(
    val kind: WebSearchProviderFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * A single web-search backend (DuckDuckGo scraping, Brave Web Search API, ...).
 *
 * [WebResearchClient] owns query planning, result aggregation and pacing; a provider only turns one
 * concrete query string into ranked [WebSearchResult]s.
 */
interface WebSearchProvider {
    /** Stable short identifier for logs and metrics, e.g. `"brave"`, `"duckduckgo"`. */
    val id: String

    /**
     * Whether this provider can run right now. Providers that need credentials (Brave) report
     * `false` until configured; [WebResearchClient] skips unconfigured providers entirely.
     */
    val isConfigured: Boolean

    /**
     * Returns up to [limit] results for [query].
     *
     * - An empty list means "no usable results, try the next provider".
     * - Throwing [WebSearchProviderException] means "hard failure" (blocked / unavailable); the
     *   engine remembers it and surfaces it if no provider produces results.
     * - [kotlinx.coroutines.CancellationException] must be allowed to propagate.
     */
    suspend fun search(query: String, limit: Int): List<WebSearchResult>
}
