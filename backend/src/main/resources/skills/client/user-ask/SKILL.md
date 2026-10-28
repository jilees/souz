---
name: user-ask
description: Ask the user a concise clarification question over a declared provider's public Souz WebSocket and wait for their answer. Use when required information is missing or the user's preference must be confirmed.
metadata:
  souz.skill-id: user.ask
  souz.transport: client-websocket
  souz.category: CHAT
  souz.timeout: PT5M
---

# Ask the user

Invoke `RunSkillCommand` with `skillId` set to `user.ask` and a `question` string in `arguments`:

```json
{"skillId":"user.ask","arguments":{"question":"Какие фильмы и жанры тебе нравятся?"}}
```

Souz sends a `tool.call.started` event named `user.ask` to the selected client device and waits up to five minutes for `tool.result`. Calls in the active conversation are durable; cross-channel calls are live-only. Use the returned `answer`; do not invent the user's response.

Omit routing `channelId` for the current conversation channel when it provides this tool. To ask on an explicitly selected other channel, pass its `channelId` in `arguments`. This Skill is available only when a channel declares or discovers `user.ask`; it is not injected automatically.

If the Skill reports missing client context, no active public WebSocket device is available for this execution.
