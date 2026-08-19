---
name: device-exec
description: Run a shell command or inline script directly on the user's active client device (e.g. a smart speaker) over the public Souz WebSocket. Use only when a task explicitly requires executing on the device itself rather than on Souz's own backend.
metadata:
  souz.skill-id: device.exec
  souz.transport: client-websocket
  souz.category: APPLICATIONS
  souz.timeout: PT1M
---

# Run a command on the device

Invoke `RunSkillCommand` with `skillId` set to `device.exec`. Arguments:
- `runtime`: one of `PROCESS`, `SH` — the device is a buildroot-based embedded target with only
  POSIX `sh` guaranteed present, not bash, not python3, not node. `SH` runs via `sh -c`: stick to
  POSIX syntax only (no arrays, no `[[ ]]`, no bash-only builtins).
- `command`: argv list — required for `PROCESS`, ignored otherwise
- `script`: inline POSIX shell text — required for `SH`, ignored for `PROCESS`
- `args`: optional extra arguments appended after the script/command
- `timeoutMs`: optional, defaults to 60000

```json
{"skillId":"device.exec","arguments":{"runtime":"SH","script":"echo hello","timeoutMs":10000}}
```

Souz sends a durable `tool.call.started` event named `device.exec` to the active client device
and waits up to the declared timeout for `tool.result`. The device shares no filesystem with
Souz's backend — always pass a script's full inline text, never a file path. The result reports
`exitCode`, `stdout`, `stderr`, and `timedOut`; treat a nonzero `exitCode` as the script's own
failure, not a tool-call failure.

If the Skill reports missing client context, no active public WebSocket device is available for
this execution.
