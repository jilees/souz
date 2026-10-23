# Souz

Souz is a Kotlin Multiplatform AI assistant with desktop and backend hosts over shared agent and runtime modules.

Backend client Skills are bundled under `backend/src/main/resources/skills/client/`. Orion command tools come from chat declarations or discovery. Chat declarations override matching defaults; discovered Orion tools override both and remain bound to their supplying channel until refreshed in that conversation.

Backend uses one startup-validated `CONTEXT_SIZE` (minimum 24,000 tokens) for all models, including summaries. Compaction and input validation share an 80% limit independent of output limits; context size is not a user or execution setting.

Named OpenAI-compatible models accept provider-specific JSON `parameters`; built-in Codex/OpenAI models accept parameter-only entries for model-scoped reasoning effort. Request structure and runtime token limits remain host-owned.

Automatically routed simple replies have no tools and skip Skill inventory and host-context enrichment; uncertain routing uses the strong model.

Backend initial waiting uses live `assistant.message` independently of `narrateSteps`; simple turns without memory skip it.

Backend [personality settings](backend/docs/personality-settings.md) are separate from system prompts and synchronize revisioned snapshots to Orion.

- Read and maintain this file before changing the repository. Before editing a module, read its linked `AGENTS.md`, any applicable nested instructions, and relevant topics linked in its `Pain points` section.
- Keep runtime documentation and `docs/public-souz-contract/` concise and current-state only. Do not write change-history phrases such as “now we do”.
- Keep pre-implementation client agreements under `docs/proposals/`, explicitly marked as proposed and not implemented; exclude them from the implemented public API schemas.

## VPS access

- For VPS work, use direct SSH to `root@212.19.134.54` with the local key `~/.ssh/vps-private-key-2026`. This connection is not listed in `~/.ssh/config`; an unavailable VPS connector does not mean SSH is unavailable.
- Use `ssh -i ~/.ssh/vps-private-key-2026 -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=10 root@212.19.134.54`. Reuse one task-specific multiplexed connection (`ControlMaster`/`ControlPersist`/`ControlPath`) for subsequent commands and close it when finished. Do not attempt password authentication; the server runs fail2ban.
- Before asking the user for access details, check these instructions and the project memory at `~/.claude/projects/-Users-jiles-Projects-souz/memory/reference_vps_access.md`. Read only relevant entries and redact secrets from any output.
- Souz runs as `souz-backend.service`, Orion as `orion-emulator.service`, and Hindsight in the `hindsight` Docker container. Their configuration files are `/opt/souz-backend/config/backend.env`, `/opt/souz-backend/config/llm.json`, `/opt/orion-emulator/config/orion.env`, and `/opt/hindsight/hindsight.env`.
- Inspect model IDs, endpoint URLs and credential presence without printing private keys, API keys, passwords or full service environment files. An audit does not require restarting services or changing configuration.

## UI architecture principles

- UI layers (Screens and Composables) should not do neither business logic, nor IO operations.
- UI-logic should be coordinated from ViewModels. ViewModel may delegate business logic to UseCases.

## Development principles

- Prefer composition to inheritance.
- When you see that something can be done simpler, in less lines of code, removing the unnecessary abstractions, note the developer and ask questions on that.
- Abstractions only pay off, if we need the flexibility in the future. You don't know the future, developer does. Aks developer when choosing abstractions. 
- Treat JVM `ThreadLocal` state as review-only. Every declaration requires an explicit
  `@Suppress("ThreadLocalInCoroutineCode")`; coroutine access must also propagate it with
  `asContextElement`.
- Prefer coroutine coordination inside suspend execution. Keep JVM monitors and atomics at explicit non-suspending JVM or native boundaries when they are required.

## Module Map

- [`:graph-engine`](graph-engine/AGENTS.md) — framework-free typed graph execution.
- [`:llms`](llms/AGENTS.md) — provider-agnostic LLM contracts and model identities.
- [`:agent`](agent/AGENTS.md) — graph-based agent and subagent execution, sessions, Skill discovery/approval, and host SPIs.
- [`:native`](native/AGENTS.md) — local llama.cpp runtime and native bridge.
- [`:ambientAgent`](ambientAgent/AGENTS.md) — ambient transcription semantics and local task analysis.
- [`:sharedLogic`](sharedLogic/AGENTS.md) — shared JVM runtime logic, providers, classifiers, tools, skills, memory, and sandboxes.
- [`:skill-oauth-api`](skill-oauth-api/AGENTS.md) — provider-neutral Skill OAuth contracts (`SkillOAuthGateway`) with no host or persistence dependencies.
- [`:skill-oauth-impl`](skill-oauth-impl/AGENTS.md) — Postgres-backed `SkillOAuthGateway` implementation, provider token exchange, and the OAuth callback route, consumed only by `:backend`.
- [`:job-api`](job-api/AGENTS.md) — owner-scoped job management and execution contracts.
- [`:job-impl`](job-impl/AGENTS.md) — PostgreSQL job persistence, scheduling, leased workers, and retries, composed by `:backend`.
- [`:sharedUI`](sharedUI/AGENTS.md) — shared desktop UI logic, ViewModels, host ports, and Compose UI.
- [`:desktopApp`](desktopApp/AGENTS.md) — desktop composition root, OS integrations, persistence, and packaging.
- [`:backend`](backend/AGENTS.md) — trusted-proxy HTTP host and PostgreSQL-backed conversation runtime.
- [`build-logic`](build-logic/AGENTS.md) — included build for repository quality checks, outside the production module graph.

## Production dependency boundaries

Only these direct production project dependencies are allowed. Standard test-source-set dependencies are outside this policy. An unclassified configuration with a direct project dependency is rejected until its production or test role is explicit, and unlisted production source sets have no project dependencies.

- `:graph-engine` `main` → none.
- `:llms` `main` → none.
- `:agent` `main` → `:graph-engine`, `:llms`.
- `:native` `main` → `:llms`.
- `:skill-oauth-api` `main` → none.
- `:skill-oauth-impl` `main` → `:skill-oauth-api`.
- `:job-api` `main` → none.
- `:job-impl` `main` → `:job-api`.
- `:sharedLogic` `commonJvmMain` → `:agent`, `:llms`, `:skill-oauth-api`; `jvmMain` → `:native`.
- `:ambientAgent` `jvmMain` → `:sharedLogic`.
- `:sharedUI` `commonJvmMain` → `:ambientAgent`, `:sharedLogic`, `:agent`, `:llms`; `jvmMain` → `:native`.
- `:backend` `main` → `:agent`, `:llms`, `:native`, `:sharedLogic`, `:skill-oauth-api`, `:skill-oauth-impl`, `:job-api`, `:job-impl`.
- `:desktopApp` `main` → `:ambientAgent`, `:sharedLogic`, `:sharedUI`, `:agent`, `:llms`, `:native`.

## Optional backend capabilities

- [Voice memory](docs/voice-memory.md) uses per-speaker Hindsight banks and durable JEV routing alongside household memory.
- [Channel attachments](docs/channel-attachments.md) use owner-scoped private S3 storage and explicit file selection for Telegram/VK replies.
- The Docker runtime provides a per-user browser and explicit Skill environment forwarding; see [the runtime guide](docs/backend-docker-sandbox-rollout.md).
- [Scheduled tasks](backend/docs/scheduled-tasks.md) use the backend's PostgreSQL-backed JobService.
- [User personality](backend/docs/personality-settings.md) is independent of the system prompt and synchronizes to Orion.

## Verification

- Use the Gradle wrapper and the Java 21 toolchain configured by the build.
- Run `./gradlew souzGateFast` for repository policy, production module-boundary, and coroutine checks.
- Run `npm ci --prefix quality && ./gradlew souzDuplicationCheck` for the exact-checkout duplicate-code ratchet.
- Run `./gradlew test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel` for the CI JVM test-and-coverage graph.
- When changing quality tooling, run `./gradlew :build-logic:check`.
- Run the affected module's command from its `AGENTS.md`; use `./gradlew check` for repository-wide verification when the change warrants it.
- Desktop entry point: `./gradlew :desktopApp:run`.
- Backend entry point: `./gradlew :backend:run`.
- For documentation-only changes, validate local links and run `git diff --check`; runtime tests are unnecessary unless source behavior also changes.

## User file storage

- [User file storage](backend/docs/s3-storage.md) provides one private, operator-provisioned S3 bucket and ownership-scoped file access. Read [storage invariants](docs/pain-points.md) before changing it.
