# Provider HTTP lifecycle

## Invariants

- Remote provider adapters receive host-owned `HttpClient` instances. They do not create, derive, or close clients.
- Credentials, selected models, endpoints, sessions, and timeouts are request state and are applied explicitly to each request.
- Desktop may support Giga through its application-scoped transport and token state. Backend composition excludes Giga entirely.
- The host that creates a provider transport closes it exactly once after in-flight application work is stopped.
- OpenAI base URLs are normalized by `OpenAIEndpoint`. Only the semantically official HTTPS endpoint sends `stream_options.include_usage`; compatible custom endpoints omit that request option but still parse usage chunks they return.

- Codex Responses requests map `LLMRequest.Chat.reasoningEffort` to `reasoning.effort`; omit the object when unset. Backend user settings persist the optional effort and pass it into each turn's agent settings.
- Hosts supply one immutable `CodexEndpoints` configuration to chat, image generation and OAuth adapters. [Base URL overrides](../../README.md#codex-endpoint-routing) preserve fixed API paths; OAuth client ID and redirect identity remain provider-defined.

- Codex SSE `error`, `response.failed`, and `response.incomplete` events emit `codex.stream_failure` at WARN with the model, HTTP request/response IDs, error code/type/message/parameter and incomplete reason. Diagnostic scalar values are bounded to 2,000 characters; request bodies, response output and authorization headers are excluded. Use the host's execution context to correlate failures across chat and tool calls.
- Codex maps SSE `server_is_overloaded` to `LLMResponse.Chat.Error(503, message)`, preserving object/string error messages. A standalone SSE error also prevents partial-output fallback success. Backend retries cover 429 and Codex 503 within the existing retry budget; incomplete output and unknown errors are not retryable.
- The Codex image gateway collects `image_generation_call.result` from SSE output-item events and requires terminal completion. Empty final text/output does not negate an image event. Errors or a truncated stream never yield a successful image; no automatic retry follows a possibly billed operation. Keep encoded/decoded image limits and exclude image bytes from logs and persisted chat history.

## Why this is fragile

Each Ktor client owns an engine, connection pool, plugins, and coroutine lifecycle. Constructing one in a provider adapter makes a cheap execution object retain expensive resources with no visible owner. Putting credentials or timeouts in shared client defaults also allows concurrent users and requests to affect each other. Giga token state is credential-specific and must never be stored in process-global system properties.

## Safe changes

- Add transport behavior to the smallest existing shared client profile that satisfies its TLS requirements; keep OpenAI and Giga trust profiles distinct.
- Set authorization and request timeout in the request builder, not in `defaultRequest` from mutable settings.
- Keep provider parsing and lightweight per-execution metadata in adapters; keep engines, TLS configuration, and plugins in host-owned resources.
- Use coroutine synchronization for token refresh and credential caches. Propagate cancellation and avoid JVM thread-local or monitor state.
- Keep token accounting at the `LLMChatAPI` boundary, where normalized usage is available, rather than in HTTP middleware or provider adapters.
- Keep custom OpenAI-compatible behavior explicit. Do not retry a rejected request with a different payload.
- Resolve internal model selections with `executionModelId` when constructing requests and include their explicit provider. The desktop router and remote adapters forward exact IDs in streaming and ordinary calls; they do not guess models from prefixes or replace them with settings defaults. Classifiers pass typed requests to preserve routing metadata.
- Jev uses a dedicated, host-owned OkHttp HTTP/1.1 transport with 60-second idle retention and connection-failure retries disabled. Reuse its pool across users and graph executions; close it with provider resources after application work stops. Credentials and model are fixed at adapter construction and applied per request. Resolve the adapter only when needed; the default LLM classifier requires no Jev configuration. Its probabilities are independent of conversational model routing and aggregate confidence. Validate every requested probability before applying category thresholds. Early turn classification batches independent complexity and memory-necessity choice questions in one request; a missing or invalid answer rejects the entire decision. Routing places bounded chronological history before a separate full current-message block and ignores unrelated history. It admits a simple reply only at `simple >= 0.9` and without attachments; the question requires a self-contained text answer without tools, external checks or actions.

## Verification

Run `./gradlew :sharedLogic:jvmTest`. Cover concurrent credential and timeout isolation, shared-client reuse, Giga token invalidation, and exactly-once host shutdown.

Jev live verification is opt-in with `SOUZ_TEST_JEV=1` and `JEV_TOKEN`; see [setup and usage](../README.md#jev-classification).
