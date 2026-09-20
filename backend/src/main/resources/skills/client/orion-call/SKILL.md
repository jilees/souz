---
name: orion-call
description: Invoke Orion's own built-in voice-assistant intents on the user's active client device over the public Souz WebSocket — for ANY request to play music (by title, artist, genre, or mood), control the media player (stop, next/previous, volume), or set timers and alarms. This is the default and only tool for that whole surface. Orion's own recognizer only understands direct commands, not trivia or indirect references — resolve those to a concrete artist/track yourself before calling. Returns Orion's own spoken reply text.
metadata:
  souz.skill-id: orion.call
  souz.transport: client-websocket
  souz.category: APPLICATIONS
  souz.timeout: PT1M
---

# Call an Orion intent

Invoke `RunSkillCommand` with `skillId` set to `orion.call` and one nonblank `utterance` string —
phrase it exactly as the user would say it to a smart speaker's own assistant, in the user's own
language:

```json
{"skillId":"orion.call","arguments":{"utterance":"включи Pink Floyd"}}
```

## Resolve first, then send a direct command

Orion's own recognizer is a smart-speaker command parser, not a research assistant: it can match
"play `<artist>`", "play `<track>`", a genre/mood, or a player/timer/alarm transport phrase, but it
has no world knowledge to resolve *what* a vague or referential request actually points to.

- **Direct requests** — the user already names the artist, track, genre, or mood, or gives a
  player/timer/alarm command — pass the request through close to verbatim as `utterance`:
  "включи Pink Floyd", "поставь плейлист для тренировки", "останови", "следующий трек", "сделай
  тише", "поставь таймер на 10 минут", "разбуди меня в 7 утра".
- **Indirect/referential requests** — anything that needs outside knowledge to pin down an actual
  artist and track (a show's theme song, "that song from the car ad", a lyric fragment, an artist's
  "most popular song") — resolve it **yourself first** (from your own knowledge, or a web search if
  you're not sure) into a concrete artist + track, and send *that* as a plain, direct command.
  Example: user asks «найди и включи опенинг сериала Извне» → you determine the actual
  opening-theme artist and title → `utterance`: "Включи Pixies — Where Is My Mind?" — not the
  user's original indirect phrasing verbatim, and not a bare artist/title pair without "включи".
  If you can't resolve it confidently, say so instead of guessing and sending a wrong title to
  Orion.

Souz sends a durable `tool.call.started` event named `orion.call` to the active client device and
waits up to one minute for `tool.result`. On success it returns:

```json
{"reply":"Включаю Pink Floyd"}
```

`reply` is the exact text Orion itself would have spoken back to the user for that command — treat
it as the outcome of the command and relay or paraphrase it in your own answer, rather than
inventing a separate confirmation. Orion's own intent recognizer decides what the phrase means; a
phrase it doesn't recognize still comes back as an ordinary `reply` (e.g. an apology or "I didn't
understand"), not as an error. Only transport-level failures count as errors here:
`orion_call_failed` (Orion couldn't run the command at all) and `client_tool_timed_out`.

If the Skill reports missing client context, no active public WebSocket device is available for
this execution.

## Don't check `device.mcp.*` for this

For anything about playing/finding music, the media player, timers, or alarms, call `orion.call`
directly — never call `device.mcp.list_tools` / `device.mcp.list_devices` first to check whether
the device "supports" media control. `device.mcp.*` discovers a separate surface (the device's own
OS/app functions, e.g. a network or system setting) and will never list Orion's built-in voice
intents, so probing it here only wastes a turn and can produce a false "this device doesn't support
that" answer. If the request is phrased the way someone would speak to a smart speaker's own
assistant — including asking it to find something to play — `orion.call` is the whole answer by
itself.
