package ru.souz.memory

import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper

data class MemoryConsolidationInput(
    val ownerId: MemoryOwnerId,
    val scope: MemoryScope,
    val facts: List<MemoryFactDetails>,
)

data class MemoryConsolidationCandidate(
    val kind: MemoryFactKind,
    val title: String,
    val body: String,
    val canonicalKey: String?,
    val confidence: Float,
    val importance: Float = confidence.coerceIn(0f, 1f),
    val evidenceSourceEventIds: List<String> = emptyList(),
)

data class MemoryConsolidationEvaluation(
    val accepted: Boolean,
    val rewardScore: Float,
    val reason: String,
)

interface MemoryConsolidator {
    suspend fun consolidate(input: MemoryConsolidationInput): List<MemoryConsolidationCandidate>
}

interface MemoryConsolidationQualityGate {
    fun evaluate(
        input: MemoryConsolidationInput,
        candidates: List<MemoryConsolidationCandidate>,
    ): MemoryConsolidationEvaluation
}

object NoopMemoryConsolidator : MemoryConsolidator {
    override suspend fun consolidate(input: MemoryConsolidationInput): List<MemoryConsolidationCandidate> =
        emptyList()
}

object DefaultMemoryConsolidationQualityGate : MemoryConsolidationQualityGate {
    override fun evaluate(
        input: MemoryConsolidationInput,
        candidates: List<MemoryConsolidationCandidate>,
    ): MemoryConsolidationEvaluation {
        if (input.facts.size < 2) return reject("insufficient_facts")
        if (candidates.isEmpty()) return reject("no_candidates")
        if (candidates.any { it.title.isBlank() || it.body.isBlank() }) return reject("blank_candidate")
        if (candidates.size >= input.facts.size) return reject("not_compact")

        val knownEvidenceIds = input.facts
            .flatMap { details -> details.evidence.map { it.sourceEvent.id } }
            .toSet()
        val unknownEvidence = candidates
            .flatMap { it.evidenceSourceEventIds }
            .any { it !in knownEvidenceIds }
        if (unknownEvidence) return reject("unknown_evidence")

        val sourceChars = input.facts.sumOf { it.fact.title.length + it.fact.body.length }.coerceAtLeast(1)
        val candidateChars = candidates.sumOf { it.title.length + it.body.length }
        val countCompression = 1f - (candidates.size.toFloat() / input.facts.size.toFloat())
        val textCompression = 1f - (candidateChars.toFloat() / sourceChars.toFloat())
        val confidence = candidates.map { it.confidence.coerceIn(0f, 1f) }.average().toFloat()
        val reward = (countCompression * 0.5f + textCompression.coerceAtLeast(0f) * 0.25f + confidence * 0.25f)
            .coerceIn(0f, 1f)
        return MemoryConsolidationEvaluation(
            accepted = true,
            rewardScore = reward,
            reason = "accepted",
        )
    }

    private fun reject(reason: String): MemoryConsolidationEvaluation =
        MemoryConsolidationEvaluation(
            accepted = false,
            rewardScore = 0f,
            reason = reason,
        )
}

class LlmMemoryConsolidator(
    private val api: LLMChatAPI,
    private val settingsProvider: SettingsProvider,
) : MemoryConsolidator {
    private val logger = LoggerFactory.getLogger(LlmMemoryConsolidator::class.java)

    override suspend fun consolidate(input: MemoryConsolidationInput): List<MemoryConsolidationCandidate> {
        if (input.facts.size < 2) return emptyList()
        val response = api.message(
            LLMRequest.Chat(
                model = settingsProvider.gigaModel.alias,
                messages = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.system,
                        content = CONSOLIDATOR_SYSTEM_PROMPT,
                    ),
                    LLMRequest.Message(
                        role = LLMMessageRole.user,
                        content = buildUserPrompt(input),
                    ),
                ),
                temperature = 0f,
                maxTokens = 1_200,
            )
        )

        return when (response) {
            is LLMResponse.Chat.Ok -> parseCandidates(response.choices.firstOrNull()?.message?.content.orEmpty())
            is LLMResponse.Chat.Error -> error("Memory consolidator failed: ${response.status} ${response.message}")
        }
    }

    private fun parseCandidates(raw: String): List<MemoryConsolidationCandidate> {
        val json = raw.trim().extractJsonArray()
        if (json.isEmpty()) return emptyList()
        return runCatching {
            restJsonMapper.readValue<List<ConsolidatorCandidate>>(json)
                .mapNotNull { candidate ->
                    val title = MemorySanitizer.redact(candidate.title.trim()).takeIf(String::isNotBlank)
                    val body = MemorySanitizer.redact(candidate.body.trim()).takeIf(String::isNotBlank)
                    if (title == null || body == null) {
                        null
                    } else {
                        MemoryConsolidationCandidate(
                            kind = candidate.kind,
                            title = title,
                            body = body,
                            canonicalKey = normalizeCanonicalKey(candidate.canonicalKey),
                            confidence = candidate.confidence.coerceIn(0f, 1f),
                            importance = (candidate.importance ?: candidate.confidence).coerceIn(0f, 1f),
                            evidenceSourceEventIds = candidate.evidenceSourceEventIds.distinct(),
                        )
                    }
                }
        }.onFailure { logger.warn("Failed to parse memory consolidator output: {}", it.message) }
            .getOrDefault(emptyList())
    }

    private fun buildUserPrompt(input: MemoryConsolidationInput): String = buildString {
        appendLine("Scope: ${input.scope.type}")
        appendLine()
        appendLine("Current memory region:")
        input.facts.forEachIndexed { index, details ->
            val fact = details.fact
            appendLine("${index + 1}. factId=${fact.id}")
            appendLine("kind=${fact.kind}")
            appendLine("title=${MemorySanitizer.redact(fact.title)}")
            appendLine("body=${MemorySanitizer.redact(fact.body)}")
            appendLine("canonicalKey=${fact.canonicalKey.orEmpty()}")
            val evidenceIds = details.evidence.joinToString(", ") { it.sourceEvent.id }
            appendLine("evidenceSourceEventIds=$evidenceIds")
            appendLine()
        }
        appendLine("Evidence snippets:")
        input.facts
            .flatMap { it.evidence }
            .distinctBy { it.sourceEvent.id }
            .forEach { detail ->
                appendLine("[${detail.sourceEvent.id}] ${MemorySanitizer.redact(detail.sourceEvent.text).take(MAX_EVIDENCE_CHARS)}")
            }
    }

    private fun String.extractJsonArray(): String {
        val trimmed = trim()
        if (trimmed.startsWith('[') && trimmed.endsWith(']')) return trimmed
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1)
        return ""
    }

    private data class ConsolidatorCandidate(
        val kind: MemoryFactKind = MemoryFactKind.SEMANTIC,
        val title: String = "",
        val body: String = "",
        val canonicalKey: String? = null,
        val confidence: Float = 0f,
        val importance: Float? = null,
        val evidenceSourceEventIds: List<String> = emptyList(),
    )

    private companion object {
        private const val MAX_EVIDENCE_CHARS = 1_200
        private const val CONSOLIDATOR_SYSTEM_PROMPT = """
You are the offline memory consolidator for a desktop AI agent.

Rewrite the provided memory region into a smaller replacement set.
Use only the listed evidence. Do not invent user preferences, project facts, dates, IDs, or decisions.
Prefer one compact fact when several facts describe the same durable rule, decision, preference, or project constraint.
Keep independent facts separate when merging would lose important detail.
Return [] when the region is already compact or evidence is insufficient.

Return JSON array only.

Each item:
{
  "kind": "PREFERENCE|PROCEDURE|PROJECT_RULE|PROJECT_DECISION|SEMANTIC|EPISODE_NOTE",
  "title": "...",
  "body": "...",
  "canonicalKey": "controlled.semantic.key.or_null",
  "confidence": 0.0,
  "importance": 0.0,
  "evidenceSourceEventIds": ["source-event-id"]
}

Every evidenceSourceEventIds value must be copied from the prompt.
"""
    }
}
