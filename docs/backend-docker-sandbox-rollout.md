# Backend Docker sandbox and browser

Set `SOUZ_SANDBOX_MODE=docker` and build the runtime image before starting the backend:

```sh
./gradlew :sharedLogic:buildRuntimeSandboxImage
```

The backend needs access to the Docker daemon and uses one long-lived sandbox per user. The image contains Lightpanda and agent-browser; its entrypoint starts their shared browser session. Recreate existing sandbox containers after rebuilding the image to use the new runtime.

## Browser

The compiled `browser` tool is available through Skill discovery in the backend's `BROWSER` category, subject to `enabledTools`. It resolves the invoking user's sandbox for every call and requires Docker mode. Its actions are `snapshot`, `navigate`, `click`, `type`, `read`, `back`, and `reset`. Element references belong to the latest snapshot and must be refreshed after page changes.

The browser session is shared across that user's conversations. Page state and logins last only while the browser processes remain alive; container or browser restarts lose them. Lightpanda does not support every browser API or protected website. Captchas require a separate user-assisted flow. Concurrent multi-step flows for the same user are not isolated.

Browser diagnostics are written to `/souz/state/logs/browser.log`. `SOUZ_SANDBOX_BROWSER=0` in the container environment disables automatic browser startup. Lightpanda uses its nightly release; override the image build argument `LIGHTPANDA_URL` to select a specific compatible Linux x86-64 binary. The agent-browser version is fixed in the Dockerfile.

## Skill environment

Set an explicit allowlist on the backend to pass selected host environment values into Docker skill commands:

```sh
SOUZ_SANDBOX_FORWARD_ENV=OPENAI_SUMMARIZATION_API_KEY,OPENAI_SUMMARIZATION_BASE_URL,OPENAI_SUMMARIZATION_MODEL
```

Names are separated by commas or whitespace. Missing or blank values are ignored. The allowlist is read when Skill runtime DI is constructed; restart the backend after changing it. Forwarded values have lower precedence than `SOUZ_SKILL_*` metadata and the invocation's explicit environment. LOCAL commands retain ordinary host-environment inheritance.

## Scheduled tasks

[Scheduled tasks](scheduled-tasks.md) require an enabled hook owner and an active Orion scheduler connection. Orion owns timing and delivery; Souz owns hook prompts and execution. Configure `SOUZ_HOOK_OWNERS` as described in the [hook contract](hooks.md).

When publishing `/hooks/` through nginx, preserve `Authorization`, disable interactive Basic Auth for that location, and clear proxy identity headers. Hook authentication happens inside the backend. Apply ingress body-size, timeout and rate limits appropriate to the hook contract.

## Manual VPS deployment

Reference [systemd](../deploy/souz-backend.service), [nginx](../deploy/nginx-souz-backend.conf), [WebSocket map](../deploy/nginx-websocket-map.conf), and [environment](../deploy/backend.env.example) files live in `deploy/`. Build the backend distribution with `./gradlew :backend:installDist`, install `backend/build/install/backend/` under `/opt/souz-backend/app`, and keep the environment file under `/opt/souz-backend/config/`. Install the nginx map in its `http` context and replace domain, certificate, owner and proxy-token placeholders before validating and reloading nginx. Deployment is manual.

Codex settings accept `CODEX_ACCESS_TOKEN`, `CODEX_REFRESH_TOKEN`, `CODEX_ACCOUNT_ID`, `CODEX_EXPIRES_AT` and `APP_LANGUAGE`. The legacy `SOUZ_BACKEND_CODEX_*` and `SOUZ_BACKEND_REGION_PROFILE` names remain valid fallbacks. Stored refreshed credentials take precedence over deployment values. Replace all four credentials together when recovering from a rejected refresh token.

### Database transition from `codex/hooks-verify`

That branch's V14 migration has a different checksum and requires `hook_receipts.prompt`; upstream inserts omit that field. The [transition script](../deploy/upgrade-hooks-verify.sql) accepts only the known legacy V14, makes `prompt` nullable, aligns the pending-queue index, and updates that one checksum atomically. Receipt data, legacy prompt/dispatch fields and usage counters remain intact. Other Flyway versions are validated normally at startup. Already reconciled V14 is a no-op; unknown checksums or legacy shapes fail without modifications.

1. Stop the backend and back up its PostgreSQL database and user sandbox state. Keep the prior distribution for rollback.
2. Run the script against the configured database, as its owner, with `search_path` set to `SOUZ_BACKEND_DB_SCHEMA`. For the example `public` schema:

   ```sh
   PGOPTIONS='-c search_path=public' psql -X --dbname=souz --set=ON_ERROR_STOP=1 --file=deploy/upgrade-hooks-verify.sql
   ```

3. Install the prepared distribution, rebuild the runtime image, recreate the per-user sandbox containers while preserving their mounted data, and start the backend. Startup applies the remaining upstream migrations.
4. Check health, a Codex chat, browser navigation, a verified hook, and an Orion scheduled task. Keep external Skill bundles and their allowlisted environment values available in each owner's workspace.

Do not run a blanket Flyway repair. A rollback to `codex/hooks-verify` requires the matching database backup, because the migration history and subsequent receipts belong to the new code. The migration test uses a disposable PostgreSQL instance; it does not inspect or modify the deployed VPS.

## Verification

```sh
SOUZ_TEST_DOCKER=1 ./gradlew :sharedLogic:jvmTest --tests 'ru.souz.runtime.sandbox.docker.*'
./gradlew :backend:test :agent:test
```

Verify browser navigation, DOM snapshots and state across calls against a controlled page. Scheduler tests simulate Orion; wall-clock scheduling also requires the external Orion implementation.
