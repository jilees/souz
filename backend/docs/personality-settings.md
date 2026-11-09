# Personality settings

User settings expose `personalityParameters` (`voice`, `appeal`, `style`, `personality`),
`personalityRevision`, and the compatible `personalityPrompt` read field. Ordinary settings PATCH
cannot change them. `personality.update` accepts a whole `request` and an optional `action`:
`update` replaces the custom style, `append` preserves unrelated preferences only for explicit
additions, and `reset` clears the prompt and restores Joy defaults. Every successful mutation
saves all four user parameters under one revision.

Supported voices are `sber` (male, calm, restrained, confident), `afina` (female, calm, composed,
businesslike), and `joy` (female, lively, light, playful). These descriptions guide voice selection;
all three support either appeal and every style. SberKot is excluded. `appeal` is `official` (вы)
or `no_official` (ты); `style` is `informal` (friendly), `neutral`, or `formal` (businesslike/strict).
Defaults are `joy`, `no_official`, `informal`, and empty custom personality. An explicit voice
request takes priority; otherwise the validator selects delivery matching the requested persona.
It never derives the voice from the human user's gender.

HTTP `POST /v1/chats` and WS `chat.create.payload` accept optional `parameters`, a free dictionary
of strings. Unknown keys are accepted but not stored. Keys are at most 256 UTF-16 units, values
at most 16,384, with at most 128 entries; nulls and nonstrings are rejected. Known voice/appeal/style
values must be supported. Omission and `{}` are equivalent. The entire dictionary participates
in creation idempotency, including unknown keys. A changed payload under the same request ID conflicts.
Creation initializes a new user's four settings once, in the chat insertion transaction; later
chats and creation retries never overwrite them. Legacy user prompts/revisions survive migration,
with Joy defaults for missing fields.

`your_name` is an activation word, not the assistant's name. Store it unchanged only on the created
chat, including whitespace/empty strings, and include it only when known for the selected delivery
chat. It is never exposed to the validator or conversation instructions, changed by the tool, reset,
or copied from another chat. Legacy chats leave it unknown. Subsequent physical spotter changes
remain authoritative on Orion; this contract has no spotter-update operation.

A separate call through the execution model validates the entire wish and selects all four coherent parameters suitable
for both text and voice. Rejection or malformed output leaves settings unchanged. The call uses the
execution's credentials, context limit and usage accounting. Only conversational requests receive the
latest saved voice, appeal, standard style and custom prompt, including subsequent calls in the same turn; base prompts, persisted history,
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
`seq:null` and exactly `{"parameters":{"voice":"joy","appeal":"no_official","style":"informal","personality":"..."},"revision":2}` as arguments. An empty `parameters.personality` disables the custom style. The optional `parameters.your_name` is copied from the destination chat.
Before validation or mutation, `personality.update` checks connected channels for synchronization
support. An unknown connected source requires discovery even when another channel declares the
command, so user-scoped storage synchronization does not bypass the originating speaker. Otherwise,
if none declares the command and an owned connected channel has an unknown catalogue,
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
