FROM node:22-bookworm

LABEL ru.souz.runtime-sandbox.fixture="paper-summarize-academic"

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash coreutils findutils python3 ca-certificates curl git \
        python3-requests \
    && rm -rf /var/lib/apt/lists/*

# Browser stack, decided architecture: Lightpanda as the default and only
# engine — no attempt to impersonate a "real" browser. Chrome (headful, under
# Xvfb, UA-wrapped) and a Bright Data CDP relay were both explored and
# abandoned: expensive (~1.3-1.5 GB/session, one concurrent session on this
# box), and neither guaranteed passage on the sites that matter most anyway —
# even a real residential Bright Data browser still hit Ozon's own captcha.
# Lightpanda is ~30-40 MB/session, honest about what it is (User-Agent,
# Sec-Ch-Ua and navigator.userAgent all say "Lightpanda/1.0" — the engine
# hardcodes this and refuses any UA containing "Mozilla", CLI or CDP, see
# docs/backend-docker-sandbox-rollout.md §10), and good enough for JS-rendered
# pages that a plain fetch can't handle. Sites with serious anti-bot (WB, Ozon,
# Yandex-protected logins) are out of reach for ANY engine we tried without a
# captcha-solving step — that's a separate, deliberate concern (see docs), not
# something the browser engine choice fixes.
#
# Known limits, by design trade-off: no WebGL/mediaDevices; `fill` is a no-op
# on React-controlled/masked inputs (ToolBrowser already avoids it — see
# ToolBrowser.kt); `serve` segfaults on some heavy pages (video players);
# nightly-only channel, no stable releases upstream.
ARG LIGHTPANDA_URL=https://github.com/lightpanda-io/browser/releases/download/nightly/lightpanda-x86_64-linux
RUN curl -fsSL -o /usr/local/bin/lightpanda "$LIGHTPANDA_URL" \
    && chmod +x /usr/local/bin/lightpanda \
    && /usr/local/bin/lightpanda version

# agent-browser CLI/daemon: accessibility-tree @eN-ref snapshots, one
# long-lived daemon per container holds the CDP session across `docker exec`
# calls. `agent-browser install` (which downloads Chrome) is deliberately
# skipped — we drive our own `lightpanda serve` (started by
# browser-supervisor.sh) and point every invocation at it via
# AGENT_BROWSER_CONFIG's "cdp" key, because agent-browser's own auto-launch of
# lightpanda passes flags this nightly rejects.
RUN npm install -g agent-browser@0.27.0

ENV AGENT_BROWSER_ENGINE=lightpanda \
    AGENT_BROWSER_CONFIG=/opt/souz/agent-browser.json

COPY docker/browser-supervisor.sh /opt/souz/browser-supervisor.sh
RUN chmod +x /opt/souz/browser-supervisor.sh

COPY docker/entrypoint.sh /usr/local/bin/souz-runtime-entrypoint
COPY docker/skills /opt/souz/skills

RUN chmod +x /usr/local/bin/souz-runtime-entrypoint

WORKDIR /souz/home

ENTRYPOINT ["/usr/local/bin/souz-runtime-entrypoint"]
CMD ["bash", "-lc", "while true; do sleep 3600; done"]
