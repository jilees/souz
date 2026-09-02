package ru.souz.tool.web.internal

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.tool.BadInputException
import java.net.URI
import kotlin.math.min

private const val WEB_HTTP_MIN_REQUEST_INTERVAL_MILLIS = 1_200L
private const val WEB_HTTP_MAX_PACED_HOSTS = 64

/**
 * Shared HTTP entry point for all web-research IO ([WebResearchClient] and every [WebSearchProvider]).
 *
 * Serialises requests per host to at most one every [WEB_HTTP_MIN_REQUEST_INTERVAL_MILLIS], which
 * keeps scrapers polite and stays within the Brave API free-tier rate limit. A single instance must
 * be shared across the client and its providers so the pacing window is global per host.
 *
 * The [httpGet] seam is the only place real network IO happens, so tests can drive the whole stack
 * deterministically.
 */
class PacedWebHttpClient(
    private val httpGet: suspend (url: String, timeoutMillis: Long, retry: Boolean, headers: Map<String, String>) -> WebTextResponse =
        { url, timeoutMillis, retry, headers ->
            WebHttpSupport().getText(url, timeoutMillis, retry = retry, extraHeaders = headers)
        },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val sleepMillis: suspend (Long) -> Unit = { delay(it) },
    private val webToolSupport: WebToolSupport = WebToolSupport(),
) {
    private val requestPacingMutex = Mutex()
    private val nextRequestAtMillisByHost = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > WEB_HTTP_MAX_PACED_HOSTS
        }
    }

    /** Paced GET returning the raw response for any status code. */
    suspend fun getResponse(
        url: String,
        timeoutMillis: Long,
        retry: Boolean = true,
        headers: Map<String, String> = emptyMap(),
    ): WebTextResponse {
        val normalizedUrl = webToolSupport.requireHttpUrl(url)
        awaitRequestSlot(normalizedUrl)
        return httpGet(normalizedUrl, timeoutMillis, retry, headers)
    }

    /** Paced GET returning the body, throwing [BadInputException] on HTTP >= 400. */
    suspend fun getText(
        url: String,
        timeoutMillis: Long,
        retry: Boolean = true,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val normalizedUrl = webToolSupport.requireHttpUrl(url)
        val response = getResponse(normalizedUrl, timeoutMillis, retry, headers)
        if (response.statusCode < 400) {
            return response.body
        }
        val bodyPreview = response.body.take(min(600, response.body.length))
        throw BadInputException("HTTP ${response.statusCode} for $normalizedUrl: $bodyPreview")
    }

    private suspend fun awaitRequestSlot(url: String) {
        val uri = URI.create(webToolSupport.toSafeHttpUrl(url))
        val hostKey = uri.host?.lowercase().orEmpty()
            .ifBlank { uri.authority?.lowercase().orEmpty() }
            .ifBlank { return }
        val delayMillis = requestPacingMutex.withLock {
            val now = currentTimeMillis()
            val scheduledAt = maxOf(now, nextRequestAtMillisByHost[hostKey] ?: 0L)
            nextRequestAtMillisByHost[hostKey] = scheduledAt + WEB_HTTP_MIN_REQUEST_INTERVAL_MILLIS
            scheduledAt - now
        }
        if (delayMillis > 0) sleepMillis(delayMillis)
    }
}
