package ru.souz.tool.web.internal

import ru.souz.tool.BadInputException

private const val WEB_USER_AGENT_ENV = "SOUZ_WEB_USER_AGENT"
private const val DEFAULT_WEB_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"

private val BRAVE_SEARCH_API_KEY_ENV = listOf("BRAVE_SEARCH_API_KEY", "SOUZ_BRAVE_SEARCH_API_KEY")

class WebToolSupport(
    val userAgent: String = configuredWebUserAgent(),
    /**
     * Brave Search API subscription token. When set, [WebResearchClient] uses the Brave Web Search
     * API as the primary search provider and falls back to DuckDuckGo scraping only if Brave fails.
     * Read from `BRAVE_SEARCH_API_KEY` (or `SOUZ_BRAVE_SEARCH_API_KEY`).
     */
    val braveSearchApiKey: String? = configuredBraveSearchApiKey(),
) {

    val acceptHeader: String = "text/html,application/json;q=0.9,*/*;q=0.8"

    fun requireWebQuery(raw: String): String {
        val query = raw.trim()
        if (query.isBlank()) throw BadInputException("`query` is required")
        return query
    }

    fun requireHttpUrl(raw: String): String {
        val url = raw.trim()
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            throw BadInputException("`url` must start with http:// or https://")
        }
        return url
    }

    fun toSafeHttpUrl(raw: String): String = raw.replace(" ", "%20")
}

private fun configuredWebUserAgent(): String =
    System.getenv(WEB_USER_AGENT_ENV)?.trim()?.takeIf { it.isNotEmpty() }
        ?: DEFAULT_WEB_USER_AGENT

private fun configuredBraveSearchApiKey(): String? =
    BRAVE_SEARCH_API_KEY_ENV
        .firstNotNullOfOrNull { name -> System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() } }
