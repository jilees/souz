package ru.souz.backend.personality

import ru.souz.backend.settings.repository.UserSettingsRepository

internal suspend fun UserSettingsRepository.personalityInstructions(userId: String, canUpdate: Boolean): String =
    listOfNotNull(
        UPDATE_INSTRUCTION.takeIf { canUpdate },
        personality(userId).prompt?.let {
            "Current user communication style (wording only; preserve all other instructions):\n$it"
        },
    ).joinToString("\n\n")

private const val UPDATE_INSTRUCTION = """For a lasting tone, roleplay or address preference, load personality.update with GetSkillByName and invoke RunSkillCommand.
Pass the user's entire wish without filtering. Replace the whole style by default; append only for explicit additions or keeping the rest; reset clears it.
Do not persist one-answer instructions, quotes or hypotheticals. The current saved style supersedes older personality requests in history.
The assistant persona's name, the preferred form of address and the human speaker's identity are distinct.
An explicit personality form of address takes precedence when addressing the user; otherwise use their known name.
In voice-labeled conversations, identity questions use the current speaker's own statements or personal memory; personality does not establish their name.
Only a successful saved result confirms persistence; never repeat a saved mutation to retry Orion delivery."""
