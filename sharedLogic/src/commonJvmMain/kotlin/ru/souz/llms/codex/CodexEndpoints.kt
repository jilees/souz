package ru.souz.llms.codex

import java.net.URI

/** Host-owned base URLs; API paths and OAuth identity remain provider-defined. */
data class CodexEndpoints(
    val baseUrl: String = "https://chatgpt.com",
    val authBaseUrl: String = "https://auth.openai.com",
) {
    val responsesUrl: String get() = "${baseUrl.trimEnd('/')}/backend-api/codex/responses"
    val oauthTokenUrl: String get() = "${authBaseUrl.trimEnd('/')}/oauth/token"
    val deviceUserCodeUrl: String get() = "${authBaseUrl.trimEnd('/')}/api/accounts/deviceauth/usercode"
    val deviceTokenUrl: String get() = "${authBaseUrl.trimEnd('/')}/api/accounts/deviceauth/token"

    init {
        for ((name, address) in listOf(
            "CODEX_BASE_URL" to baseUrl,
            "CODEX_AUTH_BASE_URL" to authBaseUrl,
        )) {
            val uri = runCatching { URI(address) }.getOrNull()
            require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
                (uri.port == -1 || uri.port in 1..65535) && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null) {
                "$name must be an absolute HTTP(S) URL without credentials, query or fragment."
            }
        }
    }

    companion object {
        fun load(value: (String) -> String? = { System.getenv(it) ?: System.getProperty(it) }): CodexEndpoints {
            val defaults = CodexEndpoints()
            fun address(name: String, default: String) = value(name)?.trim()?.takeIf(String::isNotEmpty)?.trimEnd('/') ?: default
            return CodexEndpoints(
                baseUrl = address("CODEX_BASE_URL", defaults.baseUrl),
                authBaseUrl = address("CODEX_AUTH_BASE_URL", defaults.authBaseUrl),
            )
        }
    }
}
