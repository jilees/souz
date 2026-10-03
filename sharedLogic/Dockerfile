FROM node:22-bookworm

LABEL ru.souz.runtime-sandbox.fixture="paper-summarize-academic"

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash coreutils findutils python3 ca-certificates curl git \
        python3-requests \
    && rm -rf /var/lib/apt/lists/*

# Lightpanda provides the per-user browser in the Docker sandbox.
# Linux x86-64 nightly; use LIGHTPANDA_URL to select a compatible binary.
ARG LIGHTPANDA_URL=https://github.com/lightpanda-io/browser/releases/download/nightly/lightpanda-x86_64-linux
RUN curl -fsSL -o /usr/local/bin/lightpanda "$LIGHTPANDA_URL" \
    && chmod +x /usr/local/bin/lightpanda \
    && /usr/local/bin/lightpanda version

# Keep one daemon attached to the CDP endpoint across tool calls.
# Chrome installation is unnecessary: the supervisor starts Lightpanda.
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
