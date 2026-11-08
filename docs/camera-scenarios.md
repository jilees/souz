# Camera scenario Skill

`camera-scenarios` is a bundled file-backed Skill with composite commands. Generic
`hook.*` tools own webhook configuration; a selected client MCP provider owns the
camera and detector rules. The backend has no camera-specific Kotlin workflow.

## Connection and capabilities

The MCP provider and the hook owner must be available to the same Souz instance.
The parent discovers the intended provider with the existing lazy client discovery
flow, then selects `channelId` explicitly and discovers its MCP tools. `inspect`
returns cameras and actual detector capabilities. Supported classes come from
`active_classes` of a ready runtime, not a hardcoded catalogue.

The Skill supports the local rule's classes, confidence, normalized ROI, optional
motion, dwell and cooldown. It does not add count, age or action-recognition fields
to the device schema. The cloud hook prompt supplies the final assessment and the
user-authorized action, including the exact channel when a notification is needed.

## Commands and state

| Command | Result |
|---|---|
| `inspect` | Read cameras and detector capabilities/rules |
| `create` | Validate capabilities, create a disabled hook and disabled device rule |
| `activate` | Enable the hook and replace the rule using its current revision; read actual status |
| `delete` | Disable/remove the hook, remove the device rule, then remove private state |

One stable `cam-<UUID>` identifies the scenario, hook and rule. The parent supplies
JSON-encoded condition/numeric values through composite inputs. The Skill reads
native results from either structuredContent or text-only MCP content; read the
returned rule revision between create and activate. `monitoring`, saved configuration,
HTTP receipt acceptance, agent completion and delivered notifications are distinct.

Private recovery records and credentials use `SOUZ_SKILL_STATE_DIR`, outside the
immutable bundle. Files are mode 0600, scenario directories are 0700, updates use
atomic replacement, and per-scenario file locks retain one credential across retries.
The token passes only through intermediate composite values and a transient MCP
step. Model output, ordinary replies and durable client events must not contain it.

Commands are linear, not a distributed transaction. Error-checking scripts stop the
chain when a hook/client/MCP result indicates failure. On an uncertain mutation,
inspect existing IDs and revisions before another write; do not create a replacement
ID. A failed cleanup preserves private records and reports the remaining resource.
An unavailable device is not an absent rule. Disable the hook while cleanup waits.
A retry can complete device deletion after the hook is already confirmed absent.
Already-running cloud work and in-flight device POSTs may complete.

## Installation and checks

The Docker runtime seeds bundled Skills into user state without overwriting existing
records. An existing container can receive this new Skill in its ordinary owner-scoped
Skill directory; changing the bundle does not require camera code in the backend.
Python 3 on the POSIX runtime supplies the bundled state script.

[Skill and composites](../sharedLogic/docker/skills/camera-scenarios/SKILL.md) ·
[Core hook tools](hooks.md) · [Composite semantics](composite-skill-commands.md)

Production-wired tests cover the core tools and complete Skill create/activate/delete,
including text-only MCP results, a rejected device deletion followed by recovery,
and secret-free model context/durable replay on the source channel. Run
`./gradlew :backend:test --tests 'ru.souz.backend.e2e.BackendHookManagementE2eTest' --tests 'ru.souz.backend.e2e.BackendCameraScenariosE2eTest'`
with Docker, plus the agent/sharedLogic suites.
