# Voice and personal memory

The Client-Souz `message.submit` payload accepts optional `voice.embeddingId`, a canonical UUID stable within a Souz account across devices and chats. Missing/null `voice` means unidentified for that message. Voice identity is metadata, never an authentication or authorization mechanism. See the [public contract](public-souz-contract/README.md#voice-identity).

## Banks and recall

- Household bank: the existing trusted `userId`. Its settings and historical facts are unchanged.
- Personal bank: `person:<userId>:<embeddingId>`. Personal captures are untagged and available across chats.
- Household captures retain the existing scope rule: ordinary turns use `chat:<chatId>`, explicit remember requests are global.

Identified voice messages automatically recall both banks even when `SOUZ_FEATURE_WS_AUTOMATIC_MEMORY_RECALL` is false. Requests run concurrently and share the recall token budget. Personal results precede household results within the total fact limit. The prompt and `SearchMemory` results identify the source scope; personal facts about the current speaker take precedence over conflicting household facts. An unspecified household “user” cannot establish the speaker's name. A missing personal bank is normal; a failure of one bank does not discard the other's results.

Each accepted message carries its own voice and durable message ID. Active-run input updates those values when consumed and refreshes memory before another LLM attempt. Missing voice clears the prior identity. In-flight tools keep the identity with which they started. Only the latest submitted input and accepted final response are new capture evidence; superseded inputs are not independently completed turns. Names use ordinary memory capture and recall; there are no name-specific tools or profile tables.

## Speaker attribution in conversation

Identified input stores its speaker ID in structural message metadata in the agent snapshot. At the model boundary, each conversational user message in a voice-labeled dialogue gets a leading `[souz_speaker id="<embeddingId>"]` label. Old, imported and unidentified messages are labeled `unknown`; raw user text and memory capture remain unchanged. Mid-run inputs retain their individual labels, and snapshots preserve them across restarts. Summarization preserves speaker IDs beside personal facts and retains the labeled-dialogue mode.

The backend appends the same conditional speaker instruction to every effective system prompt, including custom overrides. Personal facts belong only to the identified speaker; assistant replies, unattributed history and another person's statements cannot establish the current speaker's name. Explicit self-corrections override old personal memory. Earlier self-introductions under the same ID remain usable while extraction is pending. The assistant persona name and an explicit form of address in personality are distinct from the human speaker's identity. Explicit personality addressing preferences take precedence when addressing the user; otherwise use the speaker's known name. Personality is not evidence when answering identity questions. Channels without voice-labeled history, including Telegram and VK, keep ordinary behavior. This guides the model and does not make identity inference deterministic.

## Capture and JEV

`HINDSIGHT_API_URL` enables memory. Voice routing also requires `JEV_TOKEN`; the existing application-owned JEV HTTP client and `JEV_MODEL` are reused lazily. A missing token or classifier failure leaves the turn queued for retry, without falling back to the household bank.

After successful graph finalization, asynchronous capture redacts and enqueues the voice turn in PostgreSQL. The small interval before enqueue commits has the same process-crash limitation as the existing asynchronous capture boundary. Once enqueued, payloads survive restart. Opt-out/forget/delete turns are excluded before enqueue. The previous user/assistant exchange is context only, with opt-out filtering; tool results and recalled memory are excluded.

JEV answers two independent questions: does the new dialogue contain personal information about the speaker, and does it contain shared household information? Each probability must exceed `0.5` to select that bank. Both false means no retain. Large turns are split into bounded documents; JEV evaluates each document and the union of selected destinations applies to the whole turn. The decision is frozen before delivery.

Selecting both sends the entire turn to both banks. This classifies turns, not individual facts, and does not provide privacy isolation between household members. Hindsight extracts facts; Souz does not modify bank configuration. The retain input explicitly identifies the current speaker and separates context from new evidence. The deployed extraction mission must respect those markers and preserve attribution.

The existing memory worker records acceptance separately for each bank and retries only destinations not acknowledged. Frozen document IDs and bank-specific operation IDs cover an uncertain HTTP result. Permanent retain errors stop after 12 attempts; transient errors and classifier failures retry for up to 24 hours. Completion clears the queued dialogue payload. Hindsight asynchronous acceptance does not mean extraction has finished; names can become searchable with a delay.

Unidentified inputs and imported `history.append` text use the existing household path. No historical-bank migration or per-person redistribution is performed. Personal recall priority is conveyed to the conversational model; it is not an authoritative name lookup or deterministic conflict resolver.

## Verification

Run `:agent:test`, backend `BackendVoiceMemoryE2eTest`, `BackendHistoryMemoryE2eTest`, `VoiceMemoryTest`, `BackendDiModuleTest`, and shared `ToolSearchMemoryTest` / `MemoryRulesTest` with the Gradle wrapper. Database-backed tests require Docker. Run `souzGateFast` and the duplicate-code check.

The automated tests use real PostgreSQL and controlled JEV/Hindsight HTTP responses. Actual extraction and classification quality requires a live pilot: self-introduction, correction, another person's name, short replies, mixed household/personal facts, switching speakers, and recognition in another chat after extraction completes.
