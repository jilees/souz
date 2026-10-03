#!/usr/bin/env bash
# Runs `lightpanda serve` for the container's lifetime and pre-warms the
# agent-browser daemon against it.
#
# Why we launch it ourselves instead of letting agent-browser do it: agent-browser's
# own lightpanda auto-launch passes flags (--timeout, an incompatible
# --storage-engine combination) that this nightly build rejects. Driving it
# ourselves and pointing agent-browser at the fixed port via
# AGENT_BROWSER_CONFIG (see entrypoint.sh) sidesteps that entirely.
#
# Why one long-lived `serve` process matters: it drops all page/DOM/cookie/JS
# state the instant its one CDP connection closes. As long as this process and
# agent-browser's daemon both stay up for the container's life, state persists
# across `docker exec` calls and across agent turns — that's the whole point of
# a browser bound to the user rather than the conversation.
set -u

LOG="${SOUZ_BROWSER_LOG:-/souz/state/logs/browser.log}"
STATE_DIR="${SOUZ_BROWSER_STATE_DIR:-/souz/state/browser}"
COOKIES="$STATE_DIR/cookies.json"
mkdir -p "$STATE_DIR" "$(dirname "$LOG")"
log() { echo "$(date -Is) supervisor: $*" >>"$LOG"; }

# --storage-engine/--storage-sqlite-path are documented as common flags but
# this nightly's `serve` rejects them at runtime (UnknownOption) — only
# --cookie/--cookie-jar are actually supported in serve mode. localStorage
# does not survive a process restart; cookies do.
args=(serve --host 127.0.0.1 --port 9222 --cookie-jar "$COOKIES")
# --cookie is read-only and errors on a missing/invalid file — only pass it
# once a previous run has actually written one.
[ -s "$COOKIES" ] && args+=(--cookie "$COOKIES")

log "starting: lightpanda ${args[*]}"
lightpanda "${args[@]}" >>"$LOG" 2>&1 &
LP_PID=$!

# Tried making --cookie-jar flush on a graceful shutdown (SIGTERM, SIGINT) so
# login state would survive a container restart — neither signal flushes it in
# this nightly's `serve` mode (verified: cookie set, signalled, jar file never
# written). So in practice cookies (and everything else — DOM, JS, localStorage)
# are memory-only for this process's life: a container restart or recreation
# always means every session logged out. Still forward the signal so we don't
# leave an orphaned lightpanda process behind on shutdown.
trap 'log "forwarding TERM to lightpanda ($LP_PID)"; kill -TERM "$LP_PID" 2>/dev/null; wait "$LP_PID"; exit 0' TERM INT

for _i in $(seq 1 50); do
  (exec 3<>/dev/tcp/127.0.0.1/9222) 2>/dev/null && { exec 3>&- 3<&-; break; }
  sleep 0.2
done

log "pre-warming agent-browser daemon"
if timeout 30 agent-browser open about:blank >>"$LOG" 2>&1; then
  log "browser session ready"
else
  log "pre-warm failed (rc=$?) — first tool call will cold-start the daemon"
fi

wait "$LP_PID"
log "lightpanda exited ($?)"
