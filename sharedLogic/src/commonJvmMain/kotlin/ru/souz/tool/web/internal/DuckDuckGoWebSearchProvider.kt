package ru.souz.tool.web.internal

import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * [WebSearchProvider] backed by scraping DuckDuckGo's HTML endpoints. Always [isConfigured]; used as
 * the fallback when no API-based provider is configured or available.
 */
class DuckDuckGoWebSearchProvider(
    private val http: PacedWebHttpClient = PacedWebHttpClient(),
    private val webToolSupport: WebToolSupport = WebToolSupport(),
) : WebSearchProvider {
    private val logger = LoggerFactory.getLogger(DuckDuckGoWebSearchProvider::class.java)

    override val id: String = "duckduckgo"
    override val isConfigured: Boolean = true

    override suspend fun search(query: String, limit: Int): List<WebSearchResult> {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        var lastFailure: Exception? = null
        var lastDiagnostics: Pair<String, String>? = null
        for (endpoint in ENDPOINTS) {
            val url = "$endpoint?q=$encodedQuery"
            val html = try {
                http.getText(url, SEARCH_TIMEOUT_MILLIS, retry = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                classifyFailure(url, e)?.let { failureKind ->
                    if (failureKind == WebSearchProviderFailureKind.BLOCKED) {
                        throw WebSearchProviderException(
                            kind = failureKind,
                            message = "DuckDuckGo blocked automated search requests.",
                            cause = e,
                        )
                    }
                    lastFailure = e
                    continue
                }
                lastFailure = e
                continue
            }
            challengeReason(html, url)?.let { reason ->
                throw WebSearchProviderException(
                    kind = WebSearchProviderFailureKind.BLOCKED,
                    message = "DuckDuckGo blocked automated search requests: $reason",
                )
            }
            val parsed = parseResults(html, url, limit)
            if (parsed.isNotEmpty()) return parsed
            lastDiagnostics = diagnostics(html, url)
        }
        if (lastDiagnostics != null) {
            val (title, preview) = lastDiagnostics
            logger.warn(
                "DuckDuckGo returned HTML without parsable results for '{}'; title='{}', preview='{}'",
                query,
                title,
                preview,
            )
        }
        if (lastFailure != null) {
            throw WebSearchProviderException(
                kind = WebSearchProviderFailureKind.UNAVAILABLE,
                message = "DuckDuckGo is temporarily unavailable for automated search.",
                cause = lastFailure,
            )
        }
        return emptyList()
    }

    private fun decodeRedirect(rawHref: String): String {
        if (rawHref.isBlank()) return ""
        if (rawHref.startsWith("http://") || rawHref.startsWith("https://")) return rawHref

        val normalized = when {
            rawHref.startsWith("//") -> "https:$rawHref"
            rawHref.startsWith("/") -> "https://duckduckgo.com$rawHref"
            else -> rawHref
        }
        if (normalized.contains("/y.js?", ignoreCase = true) || normalized.contains("ad_domain=", ignoreCase = true)) {
            return ""
        }

        return runCatching {
            val query = URI.create(webToolSupport.toSafeHttpUrl(normalized)).rawQuery ?: return@runCatching normalized
            query.split('&').asSequence().mapNotNull { part ->
                val key = part.substringBefore('=', "")
                val value = part.substringAfter('=', "")
                if (key == "uddg") URLDecoder.decode(value, StandardCharsets.UTF_8) else null
            }.firstOrNull().orEmpty().ifBlank { normalized }
        }.getOrDefault(normalized)
    }

    private fun parseResults(html: String, baseUrl: String, limit: Int): List<WebSearchResult> {
        val doc = Jsoup.parse(html, baseUrl)
        val results = LinkedHashMap<String, WebSearchResult>()
        val containers = doc.select(
            "div.result, article[data-testid=result], .result.results_links, .result.results_links_deep"
        )

        containers.forEach { result ->
            val link = result.selectFirst("a.result__a, .result__title a, a[data-testid=result-title-a], h2 a")
                ?: return@forEach
            val title = link.text().trim()
            val rawHref = link.attr("href").trim()
            val url = decodeRedirect(rawHref)
            if (title.isBlank() || url.isBlank()) return@forEach

            val snippet = result.selectFirst(".result__snippet, .result-snippet, [data-result=snippet]")
                ?.text()
                ?.trim()
                .orEmpty()
            results.putIfAbsent(url, WebSearchResult(title = title, url = url, snippet = snippet))
        }

        if (results.isEmpty()) {
            doc.select("a.result__a, .result__title a, a[data-testid=result-title-a]").forEach { link ->
                val title = link.text().trim()
                val url = decodeRedirect(link.attr("href").trim())
                if (title.isBlank() || url.isBlank()) return@forEach
                results.putIfAbsent(url, WebSearchResult(title = title, url = url, snippet = ""))
            }
        }

        return results.values.take(limit).toList()
    }

    private fun diagnostics(html: String, baseUrl: String): Pair<String, String> {
        val doc = Jsoup.parse(html, baseUrl)
        val title = doc.title().trim().ifBlank { "no-title" }
        val preview = doc.text()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(PREVIEW_LENGTH)
        return title to preview
    }

    private fun challengeReason(html: String, baseUrl: String): String? {
        val text = Jsoup.parse(html, baseUrl)
            .text()
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
        return when {
            "bots use duckduckgo too" in text -> "anti-bot challenge page"
            "select all squares containing a duck" in text -> "captcha challenge page"
            "please complete the following challenge" in text -> "human verification challenge"
            else -> null
        }
    }

    private fun classifyFailure(url: String, error: Exception): WebSearchProviderFailureKind? {
        val message = error.message.orEmpty().lowercase()
        if ("duckduckgo" !in url.lowercase()) return null
        return when {
            "http 403" in message || "http 429" in message -> WebSearchProviderFailureKind.BLOCKED
            "timed out" in message ||
                "http 500" in message ||
                "http 502" in message ||
                "http 503" in message ||
                "http 504" in message -> WebSearchProviderFailureKind.UNAVAILABLE
            else -> null
        }
    }

    companion object {
        private const val SEARCH_TIMEOUT_MILLIS = 8_000L
        private const val PREVIEW_LENGTH = 240
        private val ENDPOINTS = listOf(
            "https://duckduckgo.com/html/",
            "https://html.duckduckgo.com/html/",
        )
    }
}
