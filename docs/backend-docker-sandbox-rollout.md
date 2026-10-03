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

## Verification

```sh
SOUZ_TEST_DOCKER=1 ./gradlew :sharedLogic:jvmTest --tests 'ru.souz.runtime.sandbox.docker.*'
./gradlew :backend:test :agent:test
```

Verify browser navigation, DOM snapshots and state across calls against a controlled page. Scheduler tests simulate Orion; wall-clock scheduling also requires the external Orion implementation.
