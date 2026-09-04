#!/usr/bin/env bash
set -euo pipefail

mkdir -p /souz/state/skills /souz/state/logs /souz/state/browser

if [ -d /opt/souz/skills ]; then
  cp -Rn /opt/souz/skills/. /souz/state/skills/
fi

# Browser: agent-browser attaches to a self-managed `lightpanda serve` over
# plain local CDP (see Dockerfile). agent-browser reads its config from
# AGENT_BROWSER_CONFIG on every invocation, including the backend's
# `docker exec agent-browser …` calls, so pointing it at the daemon is a
# one-time file write here, not a per-call flag. Opt out with
# SOUZ_SANDBOX_BROWSER=0 (default: on).
echo '{}' > /opt/souz/agent-browser.json

if [ "${SOUZ_SANDBOX_BROWSER:-1}" = "1" ]; then
  echo '{"cdp":"9222"}' > /opt/souz/agent-browser.json

  if [ -x /opt/souz/browser-supervisor.sh ]; then
    (
      while true; do
        /opt/souz/browser-supervisor.sh || true
        echo "$(date -Is) browser-supervisor exited, restart in 2s" >>/souz/state/logs/browser.log
        sleep 2
      done
    ) &
  fi
fi

exec "$@"
