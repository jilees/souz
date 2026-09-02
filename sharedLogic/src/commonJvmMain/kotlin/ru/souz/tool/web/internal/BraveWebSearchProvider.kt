package ru.souz.tool.web.internal

import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import ru.souz.llms.restJsonMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.min

/**
 * [WebSearchProvider] backed by the Brave Web Search API (https://api.search.brave.com).
 *
 * [isConfigured] only when a subscription token is present ([WebToolSupport.braveSearchApiKey], read
 * from `BRAVE_SEARCH_API_KEY`). Non-2xx responses are logged and returned as "no results" so the
 * engine falls through to the next provider rather than failing the whole search.
 */
class BraveWebSearchProvider(
    private val http: PacedWebHttpClient = PacedWebHttpClient(),
    private val webToolSupport: WebToolSupport = WebToolSupport(),
    private val mapper: ObjectMapper = restJsonMapper,
) : WebSearchProvider {
    private val logger = LoggerFactory.getLogger(BraveWebSearchProvider::class.java)

    override val id: String = "brave"
    override val isConfigured: Boolean
        get() = !webToolSupport.braveSearchApiKey.isNullOrBlank()

    override suspend fun search(query: String, limit: Int): List<WebSearchResult> {
        val apiKey = webToolSupport.braveSearchApiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val count = limit.coerceIn(1, MAX_COUNT)
        val url = buildString {
            append(ENDPOINT)
            append("?q=").append(URLEncoder.encode(query, StandardCharsets.UTF_8))
            append("&count=").append(count)
            append("&result_filter=web")
        }
        val response = http.getResponse(
            url = url,
            timeoutMillis = SEARCH_TIMEOUT_MILLIS,
            retry = false,
            headers = mapOf(
                "X-Subscription-Token" to apiKey,
                "Accept" to "application/json",
            ),
        )
        if (response.statusCode !in 200..299) {
            val detail = response.body.take(min(300, response.body.length)).replace(Regex("\\s+"), " ").trim()
            logger.warn("Brave Search API returned HTTP {} for '{}': {}", response.statusCode, query, detail)
            return emptyList()
        }
        return parseResults(response.body, count)
    }

    private fun parseResults(body: String, limit: Int): List<WebSearchResult> {
        val webResults = runCatching { mapper.readTree(body) }
            .getOrNull()
            ?.path("web")
            ?.path("results")
            ?: return emptyList()
        if (!webResults.isArray) return emptyList()

        val results = LinkedHashMap<String, WebSearchResult>()
        for (node in webResults) {
            val url = node.path("url").asText().orEmpty().trim()
            if (!(url.startsWith("http://") || url.startsWith("https://"))) continue
            val title = stripHighlightMarkup(node.path("title").asText().orEmpty())
            if (title.isBlank()) continue
            val snippet = stripHighlightMarkup(node.path("description").asText().orEmpty())
            results.putIfAbsent(url, WebSearchResult(title = title, url = url, snippet = snippet))
            if (results.size >= limit) break
        }
        return results.values.toList()
    }

    /** Brave wraps matched terms in `<strong>` tags inside titles/descriptions; render them as plain text. */
    private fun stripHighlightMarkup(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return Jsoup.parse(trimmed).text().replace(Regex("\\s+"), " ").trim()
    }

    companion object {
        private const val ENDPOINT = "https://api.search.brave.com/res/v1/web/search"
        private const val SEARCH_TIMEOUT_MILLIS = 8_000L
        private const val MAX_COUNT = 20
    }
}
