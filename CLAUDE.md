# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Run desktop app
./gradlew :desktopApp:run

# Run backend
./gradlew :backend:run

# Run all checks
./gradlew check

# Run desktop UI/ViewModel tests
./gradlew :sharedUI:cleanJvmTest :sharedUI:jvmTest

# Run a single test
./gradlew :sharedUI:jvmTest --tests "agent.GraphAgentComplexScenarios"

# Run backend tests
./gradlew :backend:test

# Run graph-engine README example
./gradlew :graph-engine:test --tests ru.souz.graph.GraphReadmeExampleTest

# Run sharedLogic tests
./gradlew :sharedLogic:test

# Docker sandbox tests (opt-in)
SOUZ_TEST_DOCKER=1 ./gradlew :sharedLogic:test --tests 'ru.souz.runtime.sandbox.docker.*'

# Build Docker runtime sandbox image
./gradlew :sharedLogic:buildRuntimeSandboxImage

# Run with Docker sandbox mode
SOUZ_SANDBOX_MODE=docker ./gradlew :desktopApp:run

# Build macOS universal app bundle
scripts/kmp-build-macos-universal.sh

# Rebuild native llama.cpp bridge binaries
desktopApp/src/main/resources/scripts/build-llama-bridge.sh
```

## Module Dependency Graph

```
:desktopApp  →  :sharedUI  →  :agent  →  :graph-engine
     ↓               ↓           ↓
:sharedLogic  ←─────┘       :sharedLogic  →  :llms
     ↓                            ↓
  :native                      :native
```

- `:graph-engine` — pure typed suspendable node/graph runtime; zero agent/LLM/tool knowledge
- `:llms` — provider-agnostic LLM DTOs, model profiles, provider enums
- `:agent` — `GraphBasedAgent` built on top of `:graph-engine`; skill activation, session persistence
- `:sharedLogic` — JVM runtime shared by desktop and backend: sandbox, provider clients, backend-safe tools, skill storage
- `:native` — llama.cpp JNA bridge for local model inference (macOS arm64/x64)
- `:sharedUI` — Compose Desktop screens, ViewModels, host-port interfaces; only `:desktopApp` should depend on this
- `:desktopApp` — runnable entry point, OS integrations, DI composition root, desktop-only tools
- `:backend` — Ktor HTTP server; reuses the same `:agent` execution kernel without desktop/Compose

## Architecture: Agent Execution

`GraphBasedAgent` runs an explicit graph: classify → inject MCP tools → enrich context → LLM call → tool loop → summarize. The graph nodes live in `agent/src/main/kotlin/ru/souz/agent/nodes/`. Classification narrows tool exposure before each LLM call; skill activation runs between classification and MCP injection on every turn.

`AgentFacade` is the stateful desktop entry point. `AgentExecutor` is the stateless graph execution entry point. The backend uses `AgentExecutionService` + `BackendConversationRuntimeFactory` to drive the same kernel without a UI.

## Architecture: Sandbox

All tool filesystem and command access goes through `RuntimeSandbox`. Default mode is `LOCAL`. Docker mode (`SOUZ_SANDBOX_MODE=docker`) requires `souz-runtime-sandbox:latest`. State layout lives under `~/.local/state/souz/`.

## Architecture: Backend

`/v1/**` trusts user identity only from proxy headers `X-User-Id` and `X-Souz-Proxy-Auth`. Never from request bodies. Storage modes: `memory` (default dev), `filesystem`, `postgres`. Telegram bot bindings require `ENABLE_BACKEND_TG_FEATURE=true` and `TELEGRAM_TOKEN_ENCRYPTION_KEY`.

## Architecture: Skills

Skills are ClawHub/OpenClaw bundles validated through a structural → static → LLM pipeline with cached verdicts keyed by `userId + skillId + bundleHash + policyVersion`. Desktop skill state lives under `~/.local/state/souz/skills/` and `skill-validations/`.

## Development Principles

- Read the nearest `AGENTS.md` before editing a module or nested package — each module has one.
- UI composables (`:sharedUI`) must be presentation-only. Business logic belongs in ViewModels or use cases.
- Keep Compose/UI dependencies out of `:sharedLogic`; keep `:backend` independent from `:sharedUI`.
- Do not mix coroutines with JVM low-level concurrency primitives (`Volatile`, `synchronized`, `ThreadLocal`).
- `third_party/llama.cpp` and `native/llama-bridge/build-*` are local-only paths and out of scope unless explicitly working on the native bridge.
- Desktop-only tools (browser, Mail, Calendar, Notes, desktop Telegram, audio) must not leak into `:sharedLogic` or `:backend`.
