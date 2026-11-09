package ru.souz.backend.settings.model

/** User-wide settings; the device's spotter belongs to its chat. */
data class PersonalitySnapshot(
    val prompt: String? = null,
    val revision: Long = 1,
    val voice: String = "joy",
    val appeal: String = "no_official",
    val style: String = "informal",
) {
    fun parameters(): Map<String, String> = mapOf(
        "voice" to voice, "appeal" to appeal, "style" to style, "personality" to prompt.orEmpty(),
    )

    companion object {
        const val MAX_PROMPT_LENGTH = 16_384
        const val MAX_REVISION = 9_007_199_254_740_991L
        val VOICES = setOf("sber", "afina", "joy")
        val APPEALS = setOf("official", "no_official")
        val STYLES = setOf("informal", "neutral", "formal")

        fun fromParameters(parameters: Map<String, String>): PersonalitySnapshot {
            val snapshot = PersonalitySnapshot(
                prompt = parameters["personality"]?.takeIf(String::isNotEmpty),
                voice = parameters["voice"] ?: "joy",
                appeal = parameters["appeal"] ?: "no_official",
                style = parameters["style"] ?: "informal",
            )
            require(snapshot.voice in VOICES && snapshot.appeal in APPEALS && snapshot.style in STYLES)
            require(snapshot.prompt.orEmpty().length <= MAX_PROMPT_LENGTH)
            return snapshot
        }
    }
}
