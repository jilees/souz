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

    // Deployed alongside the souz-thin-client binary (souz-thin-client/hooks/, pushed by its
    // deploy.sh) — a triangular 12-step scarlet pulse over the standard Linux LED sysfs class,
    // ported from souz-agent's own SOUZ_HOOK_TURN_START/END hooks (themselves recolored from
    // picoclaw's original coral pulse) so both products render the same "thinking" animation.
    // Kept as standalone files rather than an inline `sh -c` string — no dependency on
    // souz-agent being deployed on the same device, and the animation is easy to read/tweak
    // on its own.
    private const val LED_TURN_START_SCRIPT = "/data/souz-thin-client/hooks/led-turn-start.sh"
    private const val LED_TURN_END_SCRIPT = "/data/souz-thin-client/hooks/led-turn-end.sh"

    fun speak(text: String): List<String> =
        listOf(BOX_BINARY, "--app", "client", "-f", STAR_JSON, "-v", "1", buildStarTtsProto(text))

    fun waitingIndicatorOn(): List<String> = listOf(LED_TURN_START_SCRIPT)

    fun waitingIndicatorOff(): List<String> = listOf(LED_TURN_END_SCRIPT)

    private fun buildStarTtsProto(text: String): String {
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
        return "process_star_command{assistant_text_to_speech{base_command{source{local{" +
            "request_id:\"$REQUEST_ID\"}}}text_to_pronounce:\"$escaped\"}}"
    }
}
