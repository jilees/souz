package ru.souz.tool.web.internal

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import ru.souz.llms.restJsonMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.min

private const val WEB_RESEARCH_MAX_SEARCH_VARIANTS = 2
private const val WEB_RESEARCH_MAX_IMAGE_VARIANTS = 2
private const val WEB_RESEARCH_MAX_UNAVAILABLE_VARIANTS_BEFORE_ABORT = 2

/**
 * Shared web research engine used by:
 * - [ToolInternetSearch]
 * - [ToolInternetResearch]
 * - [ToolWebImageSearch]
 * - [ToolWebPageText]
 *
 * It owns query planning, result aggregation and page-text extraction. Turning one concrete query
 * into ranked results is delegated to [searchProviders] (Brave first when configured, then
 * DuckDuckGo); all HTTP goes through the shared [http] client so pacing stays global per host.
 */
class WebResearchClient(
    private val mapper: ObjectMapper = restJsonMapper,
    private val webToolSupport: WebToolSupport = WebToolSupport(),
    private val http: PacedWebHttpClient = PacedWebHttpClient(webToolSupport = webToolSupport),
    private val searchProviders: List<WebSearchProvider> = defaultSearchProviders(http, webToolSupport, mapper),
) {
    private val logger = LoggerFactory.getLogger(WebResearchClient::class.java)

    suspend fun searchWeb(query: String, limit: Int): List<WebSearchResult> {
        val normalizedQuery = webToolSupport.requireWebQuery(query)
        val targetCount = limit.coerceIn(1, 20)
        val aggregated = LinkedHashMap<String, WebSearchResult>()
        var unavailableFailures = 0
        for (variant in buildQueryVariants(normalizedQuery, imageIntent = false).take(WEB_RESEARCH_MAX_SEARCH_VARIANTS)) {
            val results = try {
                searchProviderResults(variant, targetCount)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WebSearchProviderException) {
                logger.warn("Web search failed for query '{}': {}", variant, e.message)
                when (e.kind) {
                    WebSearchProviderFailureKind.BLOCKED -> throw e
                    WebSearchProviderFailureKind.UNAVAILABLE -> {
                        unavailableFailures += 1
                        if (unavailableFailures >= WEB_RESEARCH_MAX_UNAVAILABLE_VARIANTS_BEFORE_ABORT) {
                            throw e
                        }
                        emptyList()
                    }
                }
            } catch (e: Exception) {
                unavailableFailures += 1
                logger.warn("Web search failed for query '{}': {}", variant, e.message)
                if (unavailableFailures >= WEB_RESEARCH_MAX_UNAVAILABLE_VARIANTS_BEFORE_ABORT) {
                    throw WebSearchProviderException(
                        WebSearchProviderFailureKind.UNAVAILABLE,
                        "Web search is currently unavailable for automated search.",
                        e,
                    )
                }
                emptyList()
            }
            results.forEach { result -> aggregated.putIfAbsent(result.url, result) }
            if (results.isNotEmpty()) unavailableFailures = 0
            if (aggregated.size >= targetCount) break
        }
        return aggregated.values.take(targetCount).toList()
    }

    suspend fun searchImages(query: String, limit: Int): List<WebImageResult> {
        val normalizedQuery = webToolSupport.requireWebQuery(query)
        val targetCount = limit.coerceIn(1, 20)
        val aggregated = LinkedHashMap<String, WebImageResult>()
        val fetchLimit = min(16, maxOf(targetCount * 2, 8))
        for (variant in buildQueryVariants(normalizedQuery, imageIntent = true).take(WEB_RESEARCH_MAX_IMAGE_VARIANTS)) {
            try {
                searchCommonsImages(variant, fetchLimit).forEach { candidate ->
                    addImageCandidate(aggregated, candidate)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug("Commons image search failed for query '{}': {}", variant, e.message)
            }
            if (aggregated.size < targetCount) {
                try {
                    searchPageImageCandidates(variant, fetchLimit).forEach { candidate ->
                        addImageCandidate(aggregated, candidate)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.debug("Page image search failed for query '{}': {}", variant, e.message)
                }
            }
            if (aggregated.size >= targetCount) break
        }
        return aggregated.values.take(targetCount).toList()
    }

    suspend fun extractPageText(url: String, maxChars: Int): String {
        val normalizedUrl = webToolSupport.requireHttpUrl(url)
        val html = http.getText(normalizedUrl, timeoutMillis = 6_000L)
        val doc = Jsoup.parse(html)
        doc.select("script, style, noscript, svg").remove()
        val normalized = doc.text().replace(Regex("\\s+"), " ").trim()
        return normalized.take(maxChars.coerceIn(500, 20_000))
    }

    /**
     * Runs one concrete query through the configured providers in order. The first provider that
     * returns results wins. Provider errors are non-fatal (the next provider is tried); if every
     * provider fails and at least one raised a [WebSearchProviderException], the last such error is
     * surfaced so callers can distinguish "blocked / unavailable" from "no results".
     */
    private suspend fun searchProviderResults(query: String, limit: Int): List<WebSearchResult> {
        var lastProviderError: WebSearchProviderException? = null
        for (provider in searchProviders) {
            if (!provider.isConfigured) continue
            val results = try {
                provider.search(query, limit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WebSearchProviderException) {
                lastProviderError = e
                logger.warn("Search provider '{}' failed for query '{}': {}", provider.id, query, e.message)
                continue
            } catch (e: Exception) {
                logger.warn("Search provider '{}' errored for query '{}': {}", provider.id, query, e.message)
                continue
            }
            if (results.isNotEmpty()) {
                logger.info("Search provider '{}' served query '{}' with {} result(s)", provider.id, query, results.size)
                return results
            }
            logger.info("Search provider '{}' returned no results for query '{}'", provider.id, query)
        }
        lastProviderError?.let { throw it }
        return emptyList()
    }

    private suspend fun searchCommonsImages(query: String, limit: Int): List<WebImageResult> {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val url = buildString {
            append("https://commons.wikimedia.org/w/api.php")
            append("?action=query")
            append("&generator=search")
            append("&gsrnamespace=6")
            append("&gsrsearch=")
            append(encodedQuery)
            append("&gsrlimit=")
            append(limit.coerceIn(1, 50))
            append("&prop=imageinfo%7Cpageimages")
            append("&iiprop=url%7Cextmetadata")
            append("&pithumbsize=1200")
            append("&format=json")
            append("&origin=%2A")
        }

        val body = http.getText(url, timeoutMillis = 8_000L)
        val root = mapper.readTree(body)
        val pages = root.path("query").path("pages")
        if (!pages.isObject) return emptyList()

        return pages.fields().asSequence().mapNotNull { (_, page) ->
            val imageInfoNode = page.path("imageinfo")
            val imageInfo = if (imageInfoNode.isArray && imageInfoNode.size() > 0) imageInfoNode[0] else null
            val imageUrl = imageInfo?.path("url")?.asText().orEmpty()
            if (!isValidImageUrl(imageUrl)) return@mapNotNull null

            WebImageResult(
                title = page.path("title").asText().removePrefix("File:").trim(),
                imageUrl = imageUrl,
                pageUrl = imageInfo?.path("descriptionurl")?.asText(null),
                thumbnailUrl = page.path("thumbnail").path("source").asText(null),
                license = imageInfo?.path("extmetadata")?.path("LicenseShortName")?.path("value")?.asText(null),
                localPath = null,
            )
        }.toList()
    }

    private suspend fun searchPageImageCandidates(query: String, limit: Int): List<WebImageResult> {
        val pageSeeds = LinkedHashMap<String, WebSearchResult>()
        val seedQueries = buildQueryVariants(query, imageIntent = true).take(WEB_RESEARCH_MAX_IMAGE_VARIANTS)
        for (seedQuery in seedQueries) {
            searchProviderResults(seedQuery, min(MAX_IMAGE_SEED_RESULTS, limit)).forEach { result ->
                if (!isLikelyHtmlPageUrl(result.url)) return@forEach
                pageSeeds.putIfAbsent(result.url, result)
            }
            if (pageSeeds.size >= limit) break
        }

        val results = mutableListOf<WebImageResult>()
        for (page in pageSeeds.values.take(min(limit, MAX_IMAGE_PAGE_FETCHES))) {
            val html = try {
                http.getText(page.url, timeoutMillis = 5_000L)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            val doc = Jsoup.parse(html, page.url)

            val metaUrls = doc.select(
                "meta[property=og:image], meta[property=og:image:url], meta[name=twitter:image], meta[name=twitter:image:src]"
            ).mapNotNull { meta ->
                meta.absUrl("content").takeIf { isValidImageUrl(it) }
            }

            metaUrls.distinct().take(2).forEach { imageUrl ->
                results += WebImageResult(
                    title = page.title,
                    imageUrl = imageUrl,
                    pageUrl = page.url,
                    thumbnailUrl = imageUrl,
                    license = null,
                    localPath = null,
                )
            }

            if (results.size < limit * 2) {
                val inlineUrls = doc.select("img[src]").asSequence().mapNotNull { image ->
                    val src = image.absUrl("src").takeIf { isValidImageUrl(it) } ?: return@mapNotNull null
                    val width = image.attr("width").toIntOrNull() ?: 0
                    val height = image.attr("height").toIntOrNull() ?: 0
                    val score = width * height
                    if (score in 1 until 60_000) return@mapNotNull null
                    src to if (score > 0) score else src.length
                }.sortedByDescending { it.second }.map { it.first }.distinct().take(2).toList()

                inlineUrls.forEach { imageUrl ->
                    results += WebImageResult(
                        title = page.title,
                        imageUrl = imageUrl,
                        pageUrl = page.url,
                        thumbnailUrl = imageUrl,
                        license = null,
                        localPath = null,
                    )
                }
            }

            if (results.size >= limit) break
        }
        return results
    }

    private fun addImageCandidate(
        bucket: LinkedHashMap<String, WebImageResult>,
        candidate: WebImageResult,
    ) {
        val key = candidate.imageUrl.trim().lowercase()
        if (!isValidImageUrl(key)) return
        if (bucket.containsKey(key)) return
        bucket[key] = candidate
    }

    private fun isValidImageUrl(url: String): Boolean {
        if (url.isBlank()) return false
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return false
        if (url.startsWith("data:", ignoreCase = true)) return false
        val extension = extensionFromUrl(url)
        if (extension in blockedDocumentExtensions) return false
        if (extension == "svg") return false
        return true
    }

    private fun isLikelyHtmlPageUrl(url: String): Boolean {
        if (url.isBlank()) return false
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return false
        return extensionFromUrl(url) !in blockedDocumentExtensions
    }

    private fun extensionFromUrl(url: String): String {
        return runCatching {
            java.net.URI.create(webToolSupport.toSafeHttpUrl(url)).path.substringAfterLast('.', "").lowercase()
        }.getOrDefault("")
    }

    private fun buildQueryVariants(query: String, imageIntent: Boolean): List<String> {
        val normalized = query
            .replace(Regex("[\"'`]+"), " ")
            .replace(Regex("[()\\[\\]{}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return emptyList()

        val variants = LinkedHashSet<String>()
        variants += normalized

        val simplified = normalized.replace(Regex("""\s*[:|,;/]\s*"""), " ").replace(Regex("\\s+"), " ").trim()
        if (simplified.isNotBlank()) variants += simplified

        val tokens = simplified.split(' ').filter { it.isNotBlank() }
        val coreTokens = tokens.filterNot { it.lowercase() in commonSearchNoiseWords }
        if (coreTokens.isNotEmpty()) {
            variants += coreTokens.joinToString(" ")
        }
        if (coreTokens.size > 4) {
            variants += coreTokens.take(4).joinToString(" ")
        }

        if (imageIntent) {
            variants += "$normalized photo"
            variants += "$normalized image"
            variants += "$normalized official"
            if (coreTokens.isNotEmpty()) {
                val core = coreTokens.joinToString(" ")
                variants += "$core photo"
                variants += "$core event"
            }
        } else {
            variants += "$normalized overview"
            if (coreTokens.isNotEmpty()) {
                variants += coreTokens.take(5).joinToString(" ")
            }
        }

        return variants
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)
    }

    companion object {
        private val blockedDocumentExtensions = setOf("pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx")
        private const val MAX_IMAGE_SEED_RESULTS = 6
        private const val MAX_IMAGE_PAGE_FETCHES = 5
        private val commonSearchNoiseWords = setOf(
            "the", "and", "for", "with", "from", "into", "about", "overview",
            "это", "как", "что", "для", "про", "или", "обзор", "стратегия", "инновации"
        )

        /** Provider order: API-based Brave first (when a token is set), DuckDuckGo scraping as fallback. */
        fun defaultSearchProviders(
            http: PacedWebHttpClient,
            webToolSupport: WebToolSupport,
            mapper: ObjectMapper,
        ): List<WebSearchProvider> = listOf(
            BraveWebSearchProvider(http, webToolSupport, mapper),
            DuckDuckGoWebSearchProvider(http, webToolSupport),
        )
    }
}
