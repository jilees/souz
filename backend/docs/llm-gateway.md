# LLM gateway

The backend's optional OpenAI-compatible gateway serves `GET /llm/v1/models` and `POST /llm/v1/chat/completions`. It uses the existing `SOUZ_LLM_CONFIG_FILE` model catalogue, server Codex OAuth session, and application-owned HTTP transports. Calls are stateless: they do not provision users, create chats, run agents, execute tools, or access Hindsight. No database migration is required.

Set `SOUZ_LLM_GATEWAY_API_KEY` to a separate service secret of 32–512 non-whitespace characters; without it the routes are absent. Clients use `Authorization: Bearer <secret>`, without trusted-proxy or user headers. Configure the client's OpenAI `base_url` as `http://<backend>:8080/llm/v1`. Keep the listener on the trusted network; the gateway can spend server provider credentials, including Codex. `SOUZ_LLM_GATEWAY_TIMEOUT_MS` is the positive per-provider request timeout (default 30,000 ms). Gateway concurrency is bounded by the configured `globalProviderConcurrency` in a separate pool from agent executions. Requests are limited to 8 MiB.

## Models and cascades

All named connections in `llm.json` are callable by name. Built-in Codex aliases and enum selectors are callable through the server OAuth session; the model list includes Codex aliases when complete credentials are available. Provider availability remains account-dependent. Per-user keys are never used. Upstream destinations and credentials cannot be supplied by a caller.

Codex IDs do not need enum entries. Register any upstream ID in the shared `models` map:

```json
{
  "models": {
    "codex-main": {"provider": "codex", "modelId": "gpt-6.1-sol"},
    "codex-terra": {"provider": "codex", "modelId": "gpt-6.1-terra"}
  },
  "reasoningEfforts": {"codex-main": "low"},
  "cascades": {"reasoning": ["codex-main", "cloudru-gemini-3.1-flash-lite"]}
}
```

The selection works in the agent, subagents, complexity routing, gateway and image generation through the existing server OAuth session. New IDs require a configuration edit and backend restart, not a source change or rebuild. Codex registrations do not take `connection` or OpenAI-specific `parameters`; set default effort through `reasoningEfforts` using the registered name. Legacy enum selectors remain accepted. Dedicated summarization requires its existing OpenAI-compatible connection.

The optional top-level `cascades` map registers virtual model names shared by the agent and gateway:

```json
{
  "cascades": {
    "fast": ["deepseek-flash", "cloudru-gemini-3.1-flash-lite"],
    "reasoning": ["gpt-6.1-sol", "cloudru-gemini-3.1-flash-lite"],
    "resilient": ["deepseek-flash", "cloudru-gemini-3.1-flash-lite", "gpt-6.1-sol"]
  }
}
```

Each target must be a configured named model or a built-in Codex selector. Arrays are nonempty, ordered and distinct. Cascade names cannot collide with models; cascades cannot reference other cascades. The client sends `"model":"fast"` and receives the actual provider model ID in the response. The same aliases are selectable in the agent's settings, request overrides, subagent selections and complexity-routing targets. Ordinary and streaming agent chat calls follow the same explicit order, including Codex in any position. Dedicated summarization retains its separately configured connection.

A named model can also declare `"fallbacks":["backup-model"]` in its own model definition. This applies when calling that named model directly, and replaces the agent's regional fallback list for ordinary non-streaming calls. `[]` disables that list; omission retains the agent's existing regional policy. Gateway calls without an explicit chain use only the selected model. Chains are flat: a target's own fallback list is not expanded.

Connection errors, provider timeouts, `5xx`, `401–403`, `408`, `429` and explicit upstream `model_not_found` errors advance the chain. Other request errors, including generic `400`, are returned without failover. The gateway tries each candidate once per request. After three consecutive provider failures, it skips that model for five minutes before allowing one trial. If every candidate is cooling down, it tries the first one. This state is process-local and shared across gateway callers. Each candidate uses its own credentials, model ID and model-specific default parameters.

## Wire behavior

Named OpenAI-compatible connections preserve request and response JSON, including tool schemas, tool history, multimodal content, `response_format`, and provider-specific fields. Configured model parameters are shallow defaults; explicit request values win. Gateway routing always replaces `model` with the selected upstream ID.

Codex calls convert Chat Completions messages into Responses input, including system/developer instructions, function calls and results, image parts, nested function schemas and structured output. The agent, gateway and image generator share `CodexResponsesTransport` for HTTP, OAuth credentials, bounded SSE decoding and terminal errors. Agent chat and gateway completions also share `CodexCompletionClient` for request conversion and response interpretation. Native chat retains its single finalized DTO result; HTTP streaming forwards live deltas. The server OAuth service handles token refresh. Sampling options and output-limit fields are forwarded in Responses form; support is determined by the Codex endpoint and model. Unsupported gateway fields and multiple completions (`n != 1`) are rejected explicitly. The gateway does not execute functions returned by any model.

`stream:true` returns SSE Chat Completion chunks followed by `[DONE]`. Failover is allowed only before the first response item is emitted. A provider error or truncated stream after output produces an OpenAI-shaped SSE error without a successful `[DONE]`; the request is never replayed on another model. Client cancellation closes upstream work and releases capacity. Failures before output use an HTTP error with an OpenAI `error` object. Provider error bodies, keys and prompt bodies are not logged or exposed in gateway errors.

## Verification

Run `./gradlew :backend:test --tests '*BackendLlmGatewayE2eTest'` with Docker available, then `./gradlew souzGateFast`. The production-wired tests cover independent authentication, model discovery, explicit failover, parameter isolation, schemas and tool history, Codex OAuth, SSE and partial-response failures.
