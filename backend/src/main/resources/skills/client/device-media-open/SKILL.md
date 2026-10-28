---
name: device-media-open
description: Open media on the selected provider's device over the public Souz WebSocket. Use for movies, shows and video.
metadata:
  souz.skill-id: device.media.open
  souz.transport: client-websocket
  souz.category: APPLICATIONS
  souz.timeout: PT1M
---

# Open media

Invoke `RunSkillCommand` with `skillId` set to `device.media.open`. Pass the media title or search text as `query`; include `genre` only when useful:

```json
{"skillId":"device.media.open","arguments":{"channelId":"<provider-chat-uuid>","query":"Нечто","genre":"horror"}}
```

Omit routing `channelId` for the current conversation channel. For an explicitly requested other
device, add that provider's `channelId` to `arguments`.

Souz sends a `tool.call.started` event named `device.media.open` to the selected client device and waits up to one minute for `tool.result`. Claim success only when the returned result reports that the media was opened.

If the Skill reports missing client context, no active public WebSocket device is available for this execution.
