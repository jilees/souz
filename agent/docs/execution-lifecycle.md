# Execution lifecycle

## Invariant

`AgentFacade` is the long-lived, stateful entry point for one conversation and one active execution. Starting a turn or changing its agent/context cancels the previous graph job. `GraphSessionService` is thread-safe for callbacks but records only one task at a time.

`ToolLoopGraphBasedAgent` implements the same `Agent` contract with a bounded model/tool loop. It preserves the caller's context and exposes streaming, step callbacks, and cancellation through the shared execution delegate. Its turn counter resets per execution; concurrent executions require separate agent instances. Delegation and context isolation belong to `SubagentTool`.

`submitToActiveRun` is an explicit continuation path for an open agent execution. It does not start a second facade task or alter the existing new-turn cancellation behavior. The steerable agent owns its execution-scoped `ActiveRunInputController`; direct UI input remains a string, while a host can reserve the controller and publish a role-preserving history-plus-execute batch after durable commit. Submission returns `false` when no run is open or the run has sealed before finalization.

The continuation controller guards its mailbox and pending-submission count with one coroutine mutex. Producers prepare or commit input outside that mutex, allowing queued input and LLM/tool boundaries to progress. Each group of pending submissions shares one completion signal that gates closure. An empty final boundary waits for either published input or completion of the group, then rechecks the mailbox. Publication and reservation release are non-cancellable; caller cancellation propagates afterward. `SteerableChatNode` separately owns the active LLM child: it selects between child completion and execute notification, cancels only that child when execute wins, and lets parent or provider cancellation propagate. Started tools remain non-interruptible, so queued batches follow their results. Queued work returns directly to the main LLM without repeating turn setup. Host-provided message attributes replace the previous message ID and voice identity at consumption, before the next model or tool call. Voice inputs and transitions to an unidentified speaker refresh memory and remove prior injections, including when ordinary automatic recall is disabled. Speaker IDs persist in structural message names; model-bound labels are rendered without changing raw history or capture text. Unattributed messages stay unknown in a labeled dialogue, including after summarization. Already-started tools retain their original invocation identity. Explicit agent cancellation closes the controller before cancelling the graph job.

Each accepted continuation advances an execution-scoped stream revision under the mailbox mutex. Every replacement LLM request captures that revision before its provider child starts, and every streamed text chunk carries the captured value. Consumers discard chunks from older revisions; they do not infer chunk ownership from collection time.

Server-side and other concurrent callers must create an `AgentExecutionKernel` per request or isolated execution scope instead of sharing a facade, graph agent, or session service. The request-scoped kernel exposes one steerable skills graph under `AgentId.SKILLS_GRAPH`; unsupported persisted agent IDs normalize to the first configured agent. The graph advertises and executes only its fixed core Skill tools. Those tools discover capabilities from the request-scoped catalog through the Skill inventory, and queued input keeps the same core-tool boundary for the remainder of the execution.

### Model routing

With a `TurnClassifier`, the skills graph runs `Turn classification` immediately after history input, before memory, Skill inventory and LLM execution. One result carries independent complexity and memory-necessity decisions. Pending `AgentSettings.routing` selects the simple or complex model and rebinds core tools before subsequent work. Explicit model selections have no pending routing and remain unchanged. No conversational model request starts during classification. Failure selects the pending complex route, or preserves an explicit model selection, with full history and recall; cancellation propagates.

The initial graph emits `TurnClassified(expectedFast)` after classification and before memory. Unknown classification reports `false`. Tool loops and voiced continuations do not repeat this notification. Hosts own any user-facing waiting text.

The graph branches to `Memory recall` or `Memory context only`. The latter requests attribution notices without searching stored facts. Both remove stale injections. The decision survives tool loops but is not persisted; a new turn resets it. A voiced continuation refreshes classification before scoped memory, retaining the execution's already chosen model. Logs expose `forecast=fast` only for simple requests without memory, `slow` otherwise, and `unknown` on classification failure. This is a work estimate, not a latency guarantee; provider delays and tool calls can add time.

Hosts supplying only `TaskComplexityClassifier` route at the first LLM call: `NodesLLM` starts the simple model speculatively and holds streamed output until the decision. A complex decision cancels that call; classifier failure uses the turn's own model.

The simple route sends the last 20 prior user turns with plain assistant replies, all system messages and the entire active exchange. Previous tool calls/results, injected memory/host context and summaries are excluded from this request-only view. Fresh memory and mid-run inputs stay intact. Early-routed simple turns clear advertised and executable tools, restore the configured system prompt without a previous Skill inventory, and skip Skill inventory and host-context enrichment. Explicit model selections retain the ordinary tool-capable path. Compaction of this view is transient; canonical history remains complete. Complex and classifier-fallback calls use full history. Selected settings and core tools serve the rest of the turn, so a `SpawnSubagent` child defaults to the chosen model.

## Why this is fragile

The facade owns mutable context, execution state, active-agent routing, and session start/finish. Overlapping work can attach steps to the wrong session, overwrite newer context, or clear `isExecuting` early. Completed-turn memory capture is graph-owned and uses successful graph finalization as its boundary rather than facade acceptance.

## Safe changes

- Agent graph retries require `Node(retryable = true)` and an `LLMException`; opt in only provider operations. Tool execution and enclosing coordination nodes must remain non-retryable. Steerable requests run through a nested graph with the active runtime so retries retain consumed input without restarting the mailbox loop. History compaction belongs to request preparation in the retryable LLM node.
- Keep `executeForResult` cancellation, generation capture, session start, and session finish as one lifecycle.
- Route mid-run input through the active controller owned by the steerable agent; do not reinterpret `executeForResult` as enqueueing.
- Keep continuation state execution-scoped. Reserve and publish under the mailbox mutex, but run producer callbacks and wait for notifications outside it. Release every reservation even when its producer fails or is cancelled. Hosts must protect accepted durable commits from cancellation until their input is returned; publication and reservation release must also finish before cancellation propagates.
- Bundle durable history with the execute input that claims it. Preserve stored roles and do not add a passive history wake-up path.
- Keep a completed LLM response provisional until the controller accepts the tool or final boundary. Replan from the pre-attempt context when queued input wins, and seal before memory-aware finalization.
- Publish `AssistantMessage` only after accepting an assembled tool response, before its tools execute and outside provider retries. Preserve nonblank assistant blocks, ordering and whitespace; exclude reasoning, rejected attempts and final answers. Stream accumulators belong to the collecting coroutine and need no concurrent containers.
- Advance the stream revision with accepted input and attach the captured revision where `NodesLLM` produces each chunk.
- Keep controller closure suspending and serialized before explicit graph cancellation. Do not reintroduce a separate job-based acceptance gate.
- Update facade context only from the current execution and restore the facade's base invocation metadata after per-call overrides.
- Preserve cancellation propagation through `AgentExecutor`, `Agent`, and `GraphExecutionDelegate`.
- Tool telemetry records one completion in `finally`, retaining the typed failure for host accounting even when cancellation interrupts runtime failure-event delivery.
- Classification receives descriptions from the same filtered, nonempty catalog used for its LLM prompt. Keep the focused history and typed model/provider route intact. A null reply confidence is a completed provider selection decision, so do not apply LLM confidence or regex agreement to it. Empty selections and `HELP` retain the all-tools fallback; remote exceptions get two attempts before regex fallback.
- Do not make `GraphSessionService` multi-task by adding more shared mutable state. Introduce an explicit execution/session object if parallel tracing becomes a requirement.
- Keep memory handling after history input and optional early turn classification, before graph-specific tool setup. Identify injected memory through its structural provenance marker and remove the previous turn's injection even when the host disables `automaticMemoryRecall`. When enabled, insert fresh recall before classification or core-tool installation. The flag does not disable explicit memory tools or completed-turn capture. Keep capture inside graph finalization: snapshot the completed exchange, schedule only after finalization succeeds, and isolate capture failures from the returned turn.

## Verification

Run `./gradlew :agent:test`. Cover cancellation by a new turn or context change, invocation-metadata overrides, session finalization on failure, memory finalization failure/cancellation, and isolation between request-scoped kernels.
