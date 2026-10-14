# Channel attachments

Backend Telegram and VK accept files in linked private chats and can return files selected by agent tools. The public Client-Souz WebSocket contract remains text-only. Attachment support is available to every user when the server's S3 connection is configured.

## Storage and ownership

The backend creates a private bucket per user, named with `SOUZ_ATTACHMENTS_BUCKET_PREFIX` and an opaque UUID. S3 credentials belong to the backend. Buckets are not mounted into containers. PostgreSQL stores file ownership, immutable metadata, SHA-256, input deduplication keys, lifecycle state, message references and delivery-part receipts. Provider download URLs, Telegram file tokens and binary data URLs are not durable file identities.

Files expire 30 days after their first successful storage commit. Reading or sending does not extend that date. Incomplete uploads expire after 24 hours. The minute cleanup worker denies expired files before deleting bytes and retries failed deletions. Late writes after account deletion restore cleanup intent. Account deletion also makes files unavailable and schedules their removal. Metadata tombstones retain input deduplication identity; chat deletion does not drive reference counting.

`PrepareAttachment(fileId)` restores an immutable S3 original into `workspace/attachments/<id>/` through the existing sandbox filesystem. Repeating it replaces an edited working copy. `AttachFileToReply` snapshots a completed sandbox path or selects an owned file ID; it does not send anything immediately. It supports `document`, `image`, `voice` and `audio` presentation. A path mentioned in prose does not attach a file. File references survive agent tools, composite Skills, subagents, summarization and persisted sessions; subagent output does not select a parent reply implicitly.

Session readers accept versions 1 and 2; writers produce version 2. Deploy all readers before relying on new references. Rolling back to an older reader requires handling version-2 sessions explicitly.

## Configuration

Set these variables on the backend process, never in a Skill or the user's sandbox:

| Variable | Value / default |
| --- | --- |
| `SOUZ_ATTACHMENTS_S3_ENDPOINT` | Required to enable attachments; S3-compatible origin |
| `SOUZ_ATTACHMENTS_S3_ACCESS_KEY`, `SOUZ_ATTACHMENTS_S3_SECRET_KEY` | Backend-only credentials |
| `SOUZ_ATTACHMENTS_S3_REGION` | `us-east-1` |
| `SOUZ_ATTACHMENTS_S3_PATH_STYLE` | `true` |
| `SOUZ_ATTACHMENTS_BUCKET_PREFIX` | `souz-files-`; reserve exclusively for this backend |
| `SOUZ_ATTACHMENTS_MAX_FILE_BYTES` | `20000000` |
| `SOUZ_ATTACHMENTS_VISION_ROUTES` | Comma-separated exact `PROVIDER:connectionId:modelId` routes; defaults to CODEX Sol 6.1, 6 and 5.6 with an empty connection ID |
| `SOUZ_ATTACHMENTS_VISION_MODEL` | Optional explicit fallback model selector |
| `SOUZ_ATTACHMENTS_MEDIA_API_KEY` | Separate platform API key for STT and image generation; Codex OAuth is not substituted |
| `SOUZ_ATTACHMENTS_STT_ENDPOINT`, `SOUZ_ATTACHMENTS_STT_MODEL` | OpenAI `/v1/audio/transcriptions`, `gpt-transcribe` |
| `SOUZ_ATTACHMENTS_IMAGE_ENDPOINT`, `SOUZ_ATTACHMENTS_IMAGE_MODEL` | OpenAI `/v1/images/generations`, `gpt-image-2.5-flare` |
| `SOUZ_ATTACHMENTS_IMAGE_PROVIDER` | `OPENAI` or `CODEX`; default `OPENAI`. With `CODEX`, `IMAGE_MODEL` selects the orchestration model (default `gpt-6.1-sol`), and `IMAGE_ENDPOINT` is unused. |
| `SOUZ_ATTACHMENTS_TTS_ENDPOINT` | Explicit Orion speech URL, e.g. `http://127.0.0.1:8092/v1/audio/speech`; disabled when omitted |
| `SOUZ_ATTACHMENTS_TTS_VOICE` | `joy`; also `sber`, `afina`, `eva` |

Provision S3 permissions for creating this prefix's buckets and putting, heading, reading and deleting their objects. Do not reuse anonymous/public bucket policies from unrelated Skills. The backend and MinIO must agree on network reachability; loopback addresses refer to the backend process's network namespace.

Users with an explicit `enabledTools` list must enable the desired new tools. Core Skill discovery exposes them through the existing request-scoped catalog; they are not extra permanently advertised LLM functions.

## Media and documents

Incoming images are decoded with dimension checks and resized to at most 2048 pixels on the long edge. At most five image occurrences per model request receive pixels. The CODEX adapter sends `input_image` blocks alongside text. Other routes require explicit vision configuration; a transport error does not trigger an implicit provider switch. Durable history retains file references, while binary data URLs exist only in the provider request. Invalid or unsupported image formats require conversion to a supported raster format.

Audio transcription checks the decoded duration with ffprobe and converts to mono PCM at 16 kHz with ffmpeg inside the user's sandbox. Ready transcripts are cached in S3. Unavailable STT produces an explicit status for the model; it must not invent audio contents. `TranscribeAttachment` exposes the same operation to Skills.

Cloud.ru Foundation Models supports the existing multipart STT adapter with `SOUZ_ATTACHMENTS_STT_ENDPOINT=https://foundation-models.api.cloud.ru/v1/audio/transcriptions` and `SOUZ_ATTACHMENTS_STT_MODEL=openai/whisper-large-v3`. Set `SOUZ_ATTACHMENTS_MEDIA_API_KEY` to the server-owned Cloud.ru key. The endpoint accepts `model` and a WAV `file`, and returns JSON with `text`. Its authenticated `/v1/models` metadata identifies the transcription endpoint.

The OpenAI-compatible adapter shares one credential between STT and image generation. With a Cloud.ru key, also bind `SOUZ_ATTACHMENTS_IMAGE_ENDPOINT` to `https://foundation-models.api.cloud.ru/v1/images/generations` so a future accidental invocation cannot send that key to the default OpenAI origin. This is a credential-routing guard, not a verified image-generation integration; keep `GenerateAttachmentImage` disabled until a supported model and its API are verified. Independent OpenAI-compatible STT/image providers require separate credentials in the adapter before enabling both. The `CODEX` image route uses backend Codex OAuth instead of this key, so it can run alongside Cloud.ru STT.

`SynthesizeVoice` calls Orion with `model=orion-tts` and `response_format=opus`, limits text to 4000 UTF-16 units and JSON to 64 KiB, and validates the returned audio. Its deadline is 75 seconds. It sends no cloud/device authorization to Orion and never wakes a physical speaker. A successful saved result is reused within the execution.

`GenerateAttachmentImage` uses the explicitly configured image provider and saves the resulting image. It is separate from chat vision and from the existing sandbox-path `GenerateImage` tool. STT and image calls have a 180-second HTTP deadline. New media operations are not retried automatically inside the adapters. Ready results are reused. A PostgreSQL operation claim prevents concurrent calls and automatic replay after a failure or unknown outcome. A new explicit execution can retry generation; manual TranscribeAttachment can retry a failed automatic transcription under its execution identity.

`SOUZ_ATTACHMENTS_IMAGE_PROVIDER=CODEX` selects the Codex Responses image-generation gateway. It refreshes the existing backend OAuth credentials, calls `https://chatgpt.com/backend-api/codex/responses` with the configured Sol model and a forced `image_generation` tool, and reads the PNG from `response.output_item.done`. Empty final text and an empty final `output` array are valid when the image event was received. A completed terminal event is required; errors, incomplete streams, multiple image results and oversized payloads fail without retries. Base64 remains transient and only the saved attachment reference reaches the agent. Requested size is a hint; downstream code must use actual image dimensions. Image operations retain the existing owner checks and operation claims.

`ReadPdfPages` reads at most 10 pages and 50,000 characters per invocation from a PDF of at most 500 pages / 20 MB. `RenderPdfPages` renders at most five pages to bounded PNG working files. `ViewImage` accepts either one `imagePath` or an ordered `imagePaths` list of up to five files and sends them together in one model request. Each file retains sandbox path and size checks; temporary local copies remain available until the request completes. For PDF review, the question maps image order to page numbers and asks for concise defects per page. `OcrImage` runs Tesseract with Russian and English; `DecodeQrCode` returns decoded data without opening URLs.

The sandbox image includes ffmpeg, Tesseract and a separate WeasyPrint environment. The bundled `pdf-document` Skill creates PDF from HTML and requires content and visual checks before selecting the latest verified result for delivery. Its instructions require viewing every page for PDFs of up to 10 pages, and the first, last, middle and distinct layouts for longer PDFs, in batches of up to five images per model request. Readability and layout findings, including low contrast, require correction, bounded to three correction cycles. Local edits with unchanged pagination require rechecking affected pages and relevant boundaries; shared style or pagination changes require the initial review coverage again. Optional aesthetic suggestions do not trigger further correction cycles. This is agent guidance; the renderer itself does not enforce visual acceptance. Its renderer rejects network/file resources and permits bounded inline raster images. HTML and Markdown outputs use ordinary file writing and attachment selection.

Install PDF dependencies when building the image, not during agent execution. Recreate an existing user container with the same `/souz` bind mount to retain workspace files and installed Skills. The entrypoint seeds missing bundled Skill files without overwriting existing files; rebuilding an image alone does not update running containers or replace an installed Skill. Keep a backup and verify existing file hashes when switching a live container.

## Transport limits and delivery

Inputs are limited to 10 files / 100 MB per message and 20 MB per file by default; audio is limited to 10 minutes. Image decoding rejects dimensions above 16,384 pixels or 40 megapixels. Channel/provider limits can be stricter.

Telegram waits for an album to settle for 1.5 seconds, capped at five seconds, while polling the same unacknowledged offset. Consecutive album items from the same sender/chat are one turn. Interleaved or later-arriving items form separate turns. VK keeps a Long Poll message's attachment list together. Account linking and sender ownership checks precede downloads.

Outbound delivery validates and loads all files before sending text. PostgreSQL receipts track each text/file part. Accepted parts are skipped on replay; an uncertain remote outcome is reported rather than blindly resent. Cross-channel history retains accepted parts on partial failure. The public-client channel rejects file delivery before sending any accompanying text.

When the first attachment is a document, Telegram sends it together with accompanying Markdown in one [rich message](https://core.telegram.org/bots/api#inputrichmessagemedia), uploading the file as multipart data. Text exceeding the rich-message budget continues in subsequent messages without truncation; additional files follow separately. File-only replies retain native document delivery. Combined text/document acceptance is recorded as one part; replays of deliveries begun with separate text/file parts preserve their existing layout.

Telegram image presentation uses `sendPhoto` with an in-memory JPEG preview: at most 1280 pixels on the longest side, no upscaling, quality 85%, transparency flattened onto white. The S3 original remains unchanged. `SendMessageToChannel` selects image presentation for PNG/JPEG file IDs sent to Telegram; explicitly selecting document presentation for a reply retains the original bytes. Codex generation requests 1024×1024, but the returned dimensions can differ; the Telegram preview limit is enforced independently.

VK uses HTTPS media-host allowlisting and no redirects for file transfers, `docs.getMessagesUploadServer`/`docs.save` for documents and voice, and `photos.getMessagesUploadServer`/`photos.saveMessagesPhoto` for photos, following the [published VK schema](https://github.com/VKCOM/vk-api-schema). Unsupported upload response variants fail explicitly. Actual photo upload and voice presentation require verification against the configured community before enabling production use.

## Verification and rollout

Use the PostgreSQL/MinIO-backed `AttachmentStorageTest` and `BackendAttachmentsE2eTest`, both bot workflow suites, provider image tests, document-media tests and the repository gates. `SOUZ_TEST_DOCKER=1` enables voice conversion through a real user sandbox; build the sandbox image first.

There is no per-user attachment rollout switch. User tool selections and provider/runtime prerequisites still apply. Image/document acceptance requires private S3 access, the configured Sol route receiving pixels, and actual channel downloads/uploads. Verify STT, image generation and Orion synthesis separately before enabling their tools; TTS readiness does not gate image/document input. OCR, audio conversion and PDF generation also require the updated sandbox image. Keep each capability disabled when its prerequisites are absent. The [proposal](proposals/channel-attachments.md) records the target scope and remaining acceptance work; it is not an implemented public API contract.
