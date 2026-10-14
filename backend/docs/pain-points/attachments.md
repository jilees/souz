# Attachments

File IDs are opaque references scoped to the authenticated user, never S3 URLs or filesystem authority. Resolve ownership and expiry on every read, preparation, selection and delivery. Recreating a Docker container with its existing `/souz` bind mount preserves workspace files and installed Skills. Deleting that mounted storage removes working copies; immutable originals/results in S3 have their independent retention policy. See the [runtime guide](../../../docs/channel-attachments.md) for configuration and bounds.

Configured attachment storage is available to every user. Channel account linking, file ownership and expiry remain mandatory boundaries; availability does not grant access to another user’s files.

Provider request preparation must operate on a copy of persisted LLM history. Binary image URLs, signed download URLs and credentials must not enter continuation state, message metadata or tool output. Summarization must retain stable references. Tool output files and files selected for a final reply are distinct: a subagent returning a file does not authorize its delivery by the parent.

Validate every output before sending the first text part. A remote send and a PostgreSQL commit cannot be atomic: record acceptance when known, preserve uncertain outcomes and never blindly replay a part. Polling leases still fence sends; file delivery does not replace account-linking or cursor invariants. Successful cross-channel parts belong in target history even if a later part fails.

Telegram may combine a document with text in one remote message. Hash and acknowledge both together; do not mark the text accepted before the upload succeeds. Preserve the separate-part layout on retries of deliveries started before combined sending was enabled.

Telegram photo conversion belongs at the upload boundary and uses memory-backed ImageIO streams. Preserve original S3 metadata and bytes, and do not convert explicitly selected documents. Cross-channel PNG/JPEG forwarding must select image presentation rather than relying on the document default of `toContent`. Verify with `TelegramPhotoTest` and `HttpTelegramBotApiTest` without a database.

Keep storage tombstones after account deletion until cleanup can remove objects. Expiry is checked before S3 access and before cache restoration; PrepareAttachment must not return an existing cache path without rechecking the file ID. Ordinary copies created by the user or a Skill remain workspace files and have the workspace retention policy. Failed cleanup attempts must not starve other expired objects.

Verify against real PostgreSQL and MinIO, faking only channel/provider HTTP boundaries. Cover foreign IDs, expiry, interrupted PUT/commit, edited working copies, file-only turns, repeated delivery, unknown remote outcomes and unchanged text-only WebSocket behavior.
