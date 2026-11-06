# Attachments

File IDs are opaque references scoped to the authenticated user, never S3 URLs or filesystem authority. Resolve ownership and expiry on every read, preparation, selection and delivery. Recreating a Docker container with its existing `/souz` bind mount preserves workspace files and installed Skills. Deleting that mounted storage removes working copies; immutable originals/results in S3 have their independent retention policy. See the [runtime guide](../../docs/channel-attachments.md) for configuration and bounds.

Configured attachment storage is available to every user. Attachment registration and selection tools follow the user’s enabled-tool snapshot for both parent and child agents, independently of the source transport. Destination channel support is checked when sending; public Client-Souz replies and cross-channel deliveries carry ordered objectKey/name/presentation descriptors. Channel account linking, file ownership and expiry remain mandatory boundaries; availability does not grant access to another user’s files.

Provider request preparation must operate on a copy of persisted LLM history. Binary image URLs, signed download URLs and credentials must not enter continuation state, message metadata or tool output. Summarization must retain stable references. Tool output files and files selected for a final reply are distinct: a subagent returning a file does not authorize its delivery by the parent.

`SOUZ_ATTACHMENTS_VISION_MODEL` / `souz.attachments.vision_model` selects independent analysis for ordinary image attachments, MCP tool images and sandbox `ViewImage`. It accepts an OpenAI-compatible or Codex model selector, a named connection, or a dedicated cascade whose targets use those protocols. The configured models must support image input. Unknown, blank or unsupported selections reject startup. `ViewImage` needs no S3 configuration; without a selection it uses the current execution model/provider.

MCP images from the latest tool result are downloaded/resolved with the existing ownership and download checks, analyzed together in order, and supplied to the main model as request-local text. Main-model retries and cascade fallback reuse that analysis. Routes explicitly listed in `SOUZ_ATTACHMENTS_VISION_ROUTES`, or explicitly selected targets of the configured vision route, receive pixels directly; this preserves direct observation by visual subagents. Without a vision selection, MCP images go to the current model. The analysis request uses its own connection, credentials, reasoning defaults and optional cascade, while sharing execution usage and hook budgets. Vision failures propagate; the main dialogue's cascade is not used for analysis. Model choice for generation/editing remains separate.

Validate every output before sending the first text part. A remote send and a PostgreSQL commit cannot be atomic: record acceptance when known, preserve uncertain outcomes and never blindly replay a part. Polling leases still fence sends; file delivery does not replace account-linking or cursor invariants. Successful cross-channel parts belong in target history even if a later part fails.

Telegram may combine a document with text in one remote message. Hash and acknowledge both together; do not mark the text accepted before the upload succeeds. Preserve the separate-part layout on retries of deliveries started before combined sending was enabled.

Telegram photo conversion belongs at the upload boundary and uses memory-backed ImageIO streams to detect source bytes, resize to at most 1280 pixels and encode JPEG. The ImageIO WebP plugin is a backend runtime dependency. Preserve original S3 metadata and bytes, and do not convert explicitly selected documents. Cross-channel PNG/JPEG/WebP forwarding selects image presentation rather than the document default of `toContent`. Verify with `TelegramPhotoTest` and `HttpTelegramBotApiTest` without a database.

Removing or recreating a local user row does not delete files or reset retention. Cleanup leases fence concurrent object deletion; successful deletion removes metadata. Configure the bucket lifecycle described in [storage](s3-storage.md) for exceptionally late uploads. Expiry is checked before S3 access and before cache restoration; PrepareAttachment must not return an existing cache path without rechecking the file ID. Ordinary copies created by the user or a Skill remain workspace files and have the workspace retention policy. Failed cleanup attempts must not starve other expired objects.

Verify against real PostgreSQL and MinIO, faking only channel/provider HTTP boundaries. Cover foreign IDs, expiry, interrupted PUT/commit, edited working copies, file-only turns, repeated delivery, unknown remote outcomes and unchanged text-only WebSocket behavior.

Image generation is bound per execution for both `GenerateImage` and `GenerateAttachmentImage`. Both belong to `IMAGE_GENERATION` for category discovery and follow the user's enabled-tool snapshot. Their gateway, model selection, credentials and hook admission are shared; only sandbox/S3 result persistence differs. Process tool metadata must be rebound before execution. Keep STT credentials separate and retain the S3 operation claim and cache on attachment generation.

Optional ordered source images select editing: `GenerateImage.imagePaths` reads through the invoking sandbox; `GenerateAttachmentImage.fileIds` resolves owned, unexpired S3 files before checking the result cache. Both accept up to five PNG/JPEG/WebP files of at most 20 MB each. Attachment operation identities include ordered source IDs and checksums; source-free identities remain compatible. Source bytes/data URLs stay in the provider request. Results are new files and require explicit reply selection, including when editing a previous result.

## Tools and selection

With S3 configured, the backend exposes `PrepareAttachment(fileId)`, `AttachFileToReply(path | fileId, presentation)`, and `DetachFileFromReply(fileId)` through the enabled-tool catalog. Preparation and path registration use the configured sandbox, including trusted LOCAL deployments; LOCAL workspaces are not a cross-tenant isolation boundary. Selection by ID and detachment need no filesystem access.

Preparation rebuilds a sandbox working copy from immutable storage. Attachment accepts exactly one source; saving the same bytes and name within an execution reuses the object. Detaching only changes reply selection. Prepared copies survive S3 expiry; workspace cleanup and save quotas are separate policies.

Message `files` describe available artifacts; trusted tool `replyFiles` and `detachedReplyFiles` change selection in call order. Subagents return files without selecting the parent's reply. Knowledge offloading preserves structured metadata. [Composite commands](../../docs/composite-skill-commands.md) define success/failure handling.

A reply allows 10 files and 100 MB total. An over-limit tool result returns `attachment_reply_limit_exceeded` and leaves selection unchanged; saved artifacts remain available. Tools validate ownership, presentation and sandbox paths, return safe error codes, and propagate cancellation. Unexpected failures are logged without exception messages or transport secrets.

## History and persistence

Before chat and summarization, non-tool message files are rendered into request text so models can use their IDs. Stored history retains structured references. Compaction keeps recent unique references in last-occurrence order, bounded by positive `SOUZ_ATTACHMENTS_HISTORY_LIMIT` / `souz.attachments.historyLimit` (default 20) for parent and child loops. Active exchanges and reply selection remain intact; older files remain usable by known ID but cannot be enumerated.

Selection lives in context/session and resumes only for a matching non-null execution ID. A different or missing ID starts empty. Persist the resulting selection without merging the old snapshot back in.

The final transaction validates file ownership, availability, metadata, presentation and limits, then commits text, attachments, session and execution status together. Unavailable input files fail admission; unavailable final reply files are dropped without losing the answer. Trusted message reads expose metadata; channel transports and provider vision consume the same references.

Session schema 2 reads schema 1. Internal file metadata uses a flat `fileId`; V29 converts legacy nested references in message attachments and session history while preserving image references. Trusted HTTP message reads expose flat metadata; public WebSocket descriptors retain `objectKey`, `name` and `presentation`. Empty attachment fields are omitted. Rollback to binaries that do not support session schema 2 or flat file metadata requires a compatible build or a reviewed data conversion.
