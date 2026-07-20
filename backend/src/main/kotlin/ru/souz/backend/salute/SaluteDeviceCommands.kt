package ru.souz.backend.salute

/**
 * Builds argv lists for the `exec` messages the backend pushes to a thin client. Every command
 * here is backend-authored (never user input or LLM tool-call output) — see the plan's "Known
 * risks" section for why that boundary matters while the channel is unauthenticated.
 */
object SaluteDeviceCommands {
    private const val BOX_BINARY = "/vendor/staros/box"
    private const val STAR_JSON = "/vendor/staros/star.json"
    private const val REQUEST_ID = "souz-backend"

    fun speak(text: String): List<String> =
        listOf(BOX_BINARY, "--app", "client", "-f", STAR_JSON, "-v", "1", buildStarTtsProto(text))

    /**
     * Waiting-indicator (LED) control — the exact local mechanism (another `box` command vs. a
     * sysfs/GPIO write) needs to be confirmed against a physical device. Returns null until then;
     * callers must treat null as "skip, no indicator support yet" rather than fail the turn.
     */
    fun waitingIndicatorOn(): List<String>? = null

    fun waitingIndicatorOff(): List<String>? = null

    private fun buildStarTtsProto(text: String): String {
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
        return "process_star_command{assistant_text_to_speech{base_command{source{local{" +
            "request_id:\"$REQUEST_ID\"}}}text_to_pronounce:\"$escaped\"}}"
    }
}
