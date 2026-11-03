package ru.souz.backend.llmgateway

import java.security.MessageDigest
import ru.souz.backend.config.BackendConfigSource

/** Service credentials never participate in generated toString output. No key means no routes. */
class LlmGatewayConfig(
    private val apiKey: String? = null,
    val timeoutMillis: Long = 30_000,
) {
    val enabled: Boolean get() = apiKey != null

    init {
        require(apiKey == null || (apiKey.length in 32..512 && apiKey.none(Char::isWhitespace))) {
            "SOUZ_LLM_GATEWAY_API_KEY must contain 32 to 512 non-whitespace characters."
        }
        require(timeoutMillis > 0) { "SOUZ_LLM_GATEWAY_TIMEOUT_MS must be positive." }
    }

    fun authenticates(authorization: String?): Boolean {
        val supplied = authorization?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7) ?: return false
        return apiKey != null && MessageDigest.isEqual(apiKey.toByteArray(), supplied.toByteArray())
    }

    companion object {
        fun load(source: BackendConfigSource): LlmGatewayConfig = LlmGatewayConfig(
            apiKey = source.value("SOUZ_LLM_GATEWAY_API_KEY", "souz.llmGateway.apiKey"),
            timeoutMillis = source.value("SOUZ_LLM_GATEWAY_TIMEOUT_MS", "souz.llmGateway.timeoutMs")?.let {
                requireNotNull(it.toLongOrNull()) { "SOUZ_LLM_GATEWAY_TIMEOUT_MS must be an integer." }
            } ?: 30_000,
        )
    }
}
