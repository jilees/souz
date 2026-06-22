package ru.souz.memory

class MemoryCaptureService(
    private val memoryService: MemoryService,
    private val writer: MemoryWriter,
) {
    @Suppress("DEPRECATION")
    suspend fun captureAfterTurn(input: MemoryCaptureInput): List<MemoryFact> {
        val intent = parseExplicitMemoryIntent(input.userMessage)
        if (intent == ExplicitMemoryIntent.DO_NOT_CAPTURE_THIS_TURN) return emptyList()
        if (intent == ExplicitMemoryIntent.FORGET_EXISTING || intent == ExplicitMemoryIntent.DELETE_EXISTING) {
            memoryService.forgetFromText(
                context = input.context,
                text = input.userMessage,
                hardDelete = intent == ExplicitMemoryIntent.DELETE_EXISTING,
            )
            return emptyList()
        }

        val isExplicitPositive = intent == ExplicitMemoryIntent.REMEMBER_SIGNAL
        val candidates = writer.extractCandidates(input)
        val validCandidates = candidates.filter { candidate -> isValidCandidate(candidate, isExplicitPositive) }
        val processedCandidates = validCandidates.mapNotNull { candidate ->
            val allowedScopes = (input.scopes + input.context.allowedRetrievalScopes(includeChat = input.context.surface == MemorySurface.BACKEND))
                .map { it.normalized() }
                .toSet()
            val legacyScope = candidate.scope?.normalized()?.takeIf { it in allowedScopes }
            val targetScope = legacyScope
                ?: input.context.resolveRequestedScope(candidate.requestedScope, candidate.kind)?.normalized()
            if (targetScope != null && targetScope in allowedScopes) {
                candidate to targetScope
            } else {
                null
            }
        }
        if (processedCandidates.isEmpty()) return emptyList()

        val redactedCombinedText = processedCandidates
            .joinToString("\n---\n") { MemorySanitizer.redact(it.first.evidenceText) }
            .trim()

        val sourceEventId = memoryService.saveRedactedSourceEvent(input, redactedCombinedText)
        return try {
            processedCandidates.map { (candidate, targetScope) ->
                    memoryService.createCapturedFact(
                        CreateCapturedFactInput(
                            ownerId = input.context.ownerId,
                            scope = targetScope,
                            kind = candidate.kind,
                            title = candidate.title.trim(),
                            body = candidate.body.trim(),
                            canonicalKey = normalizeCanonicalKey(candidate.canonicalKey ?: candidate.slotKey),
                            confidence = candidate.confidence,
                            importance = candidate.importance,
                            evidenceText = candidate.evidenceText.trim(),
                            sourceEventId = sourceEventId,
                        )
                )
            }
        } catch (error: Exception) {
            runCatching { memoryService.deleteSourceEventIfUnused(sourceEventId) }
            throw error
        }
    }

    private fun isValidCandidate(
        candidate: MemoryFactCandidate,
        explicitRemember: Boolean,
    ): Boolean {
        val threshold = if (explicitRemember) 0.4f else 0.6f
        val evidence = candidate.evidenceText.trim()
        return candidate.shouldSave &&
            candidate.title.isNotBlank() &&
            candidate.body.isNotBlank() &&
            evidence.isNotBlank() &&
            candidate.confidence in threshold..1f &&
            candidate.importance in 0f..1f &&
            !MemorySanitizer.looksSecret(candidate.title) &&
            !MemorySanitizer.looksSecret(candidate.body) &&
            !MemorySanitizer.looksSecret(evidence)
    }
}
