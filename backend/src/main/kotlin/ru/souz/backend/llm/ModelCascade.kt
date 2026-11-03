package ru.souz.backend.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Upstream's ordered fallback/cooldown policy, shared by native DTO and HTTP streaming calls. */
internal fun <Target, Chunk> modelCascade(
    targets: List<Target>,
    failures: ModelFailureMemory,
    failureKey: suspend (Target) -> Any,
    isProviderFailure: (Throwable) -> Boolean,
    onFallback: (Target, Throwable) -> Unit,
    attempt: suspend (Target) -> Flow<Chunk>,
): Flow<Chunk> = flow {
    require(targets.isNotEmpty())
    if (targets.size == 1) {
        attempt(targets.first()).collect { emit(it) }
        return@flow
    }
    var lastFailure: Throwable? = null
    for ((index, target) in targets.withIndex()) {
        val key = failureKey(target)
        if (!failures.shouldTry(key)) continue
        var emitted = false
        try {
            attempt(target).collect { emitted = true; emit(it) }
            failures.record(key, failed = false)
            return@flow
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val providerFailed = isProviderFailure(failure)
            failures.record(key, failed = providerFailed)
            if (emitted || !providerFailed) throw failure
            lastFailure = failure
            if (index < targets.lastIndex) onFallback(target, failure)
        }
    }
    // Preserve upstream's trial of the requested model when every candidate is cooling down.
    lastFailure?.let { throw it }
    attempt(targets.first()).collect { emit(it) }
}

/** Upstream provider-failure classification plus explicit model availability errors. */
internal fun isModelProviderFailure(status: Int, code: String? = null): Boolean =
    status !in 400..499 || status in 401..403 || status == 408 || status == 429 ||
        ((status == 400 || status == 404) && code == "model_not_found")
