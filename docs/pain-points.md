# Pain points

Pain-point topics document non-obvious invariants, common failure modes, and the safe way to change fragile areas. They are current-state maintenance guides, not feature overviews or change history.

Before editing a module, open its index below and read only the topics related to the code you plan to change.

## Module indexes

- [`:agent`](../agent/AGENTS.md) — includes [Skill discovery and approval](../agent/docs/skill-activation.md) and [subagent lifecycle and isolation](../agent/docs/subagents.md).
- [`:graph-engine`](../graph-engine/AGENTS.md)
- [`:llms`](../llms/AGENTS.md)
- [`:native`](../native/AGENTS.md)
- [`:ambientAgent`](../ambientAgent/AGENTS.md)
- [`:sharedLogic`](../sharedLogic/AGENTS.md) — includes [sandbox and composite Skill execution](../sharedLogic/docs/runtime-sandbox-and-skills.md) and [provider/classifier HTTP lifecycle](../sharedLogic/docs/provider-http-lifecycle.md).
- [`:sharedUI`](../sharedUI/AGENTS.md)
- [`:skill-oauth-api`](../skill-oauth-api/AGENTS.md)
- [`:skill-oauth-impl`](../skill-oauth-impl/AGENTS.md)
- [`:job-api`](../job-api/AGENTS.md)
- [`:job-impl`](../job-impl/AGENTS.md)
- [`:desktopApp`](../desktopApp/AGENTS.md)
- [`:backend`](../backend/AGENTS.md) — includes [personality](personality-settings.md) and [scheduled-task ownership and partial failures](../backend/docs/scheduled-tasks.md).

## Topic format

Each topic should state:

1. The invariant that must remain true.
2. Why the area is fragile or costly to break.
3. The safe path for changing it.
4. Focused verification commands or scenarios.

Keep exact constants, route inventories, file trees, and historical narratives in their canonical source rather than duplicating them here.

## Repository-wide topics

Repository-wide runtime constraints are covered by [deployment and sandbox operations](backend-docker-sandbox-rollout.md), [hooks](hooks.md) and [scheduled tasks](scheduled-tasks.md).
