# Personality settings

`personalityPrompt` and `personalityRevision` are user settings exposed in settings GET and bootstrap.
Ordinary settings PATCH cannot change them. The enabled `personality.update` Skill accepts a whole
`request` and an optional `action`: `update` (default) replaces the style, `append` preserves unrelated
preferences only for explicit additions, and `reset` clears it without a request.

A separate call through the execution model validates the entire wish and normalizes a style suitable
for both text and voice. Rejection or malformed output leaves settings unchanged. The call uses the
execution's credentials, context limit and usage accounting. Only conversational requests receive the
latest saved style, including subsequent calls in the same turn; base prompts, persisted history,
classification, summaries and subagents remain independent.

Personality instructions count toward conversational context budgeting before every model call.
Each call reads one snapshot, reused if history needs compaction; the next call reads fresh settings.
If necessary, completed history is summarized without the personality before sending; the current
user/tool exchange stays intact. If that exchange and the style still cannot fit, the call returns a
context-budget error. The style never enters persisted history or auxiliary summary requests.

Separate PostgreSQL columns prevent ordinary settings writes from overwriting personality. Revisions
start at 1 and increase on every update/reset, up to 9,007,199,254,740,991. Prompts are limited to 16,384
UTF-16 units. Validation runs outside transactions; a competing write triggers revalidation, at most
three attempts. After commit, delivery belongs to the application scope and survives turn cancellation.

Orion receives `prompt.personality.set` through live `tool.call.started` / `tool.result` transport, with
`seq:null` and exactly `{"prompt":"...","revision":2}` as arguments. Empty prompt clears the style.
Before validation or mutation, `personality.update` checks connected channels for synchronization
support. If none declares the command and an owned connected channel has an unknown catalogue,
it returns `saved:false` with `client_tool_discovery_required`, identifying the source channel when
available, otherwise another unknown connected channel. The parent discovers that channel through
`orion.tools.list` and retries the original operation; no revision or prompt was changed by the
rejected attempt. The error explicitly asks to retry `personality.update` because the server control
is hidden from model-facing discovery output. A completed catalogue without the command does not
require another discovery.
Offline channels do not block saving. Connection synchronization does not request model discovery.

Only owned, unarchived, connected channels declaring `prompt.personality.set` through creation or
current-instance discovery are eligible, regardless of client type. The initiating channel is preferred;
otherwise any eligible channel is used, with fallback after rejected publication. One accepted snapshot
suffices because Orion's prompt storage is user-scoped. With no eligible channel, delivery is `skipped`
without an error and the saved Souz style remains effective.
After durable replay, subscription synchronization uses creation declarations and any current-instance
discovery already available. It does not trigger discovery. Reconnect resends the current personality
snapshot to an eligible channel. Host composition registers the personality command among server-only
client controls. The generic catalogue uses that exclusion set for inventory, discovery results and
execution revalidation; the personality synchronizer owns the command name and provider eligibility.
The thread ID is the source execution or a technical UUID on connection. These calls create no target
execution, durable result receipt or replay event.

`applied`/`unchanged` acknowledgements must echo the revision; `stale` is accepted only when it matches a
newer local revision. Transient failures retry the same snapshot up to three times with fresh call IDs
and ten-second deadlines. Permanent protocol errors stop retries. Results distinguish storage (`saved`,
`revision`) from delivery (`sync`: `synced`, `skipped`, `pending`, `superseded`, `conflict`, `failed`). Only `synced`
confirms delivery. Offline changes synchronize on reconnect; never repeat a saved mutation to retry delivery.

Existing style text embedded in system prompts is not automatically migrated. Move identified style
text explicitly, increment the revision atomically, and reconnect Orion after any direct database change.

Verify with `./gradlew :backend:test --tests '*BackendPersonalityE2eTest'` and the backend/agent suites.
