---
name: orion-tools-list
description: Discover available Orion device functions and their input schemas, including capabilities not yet known to this conversation.
metadata:
  souz.skill-id: orion.tools.list
  souz.transport: client-websocket
  souz.category: APPLICATIONS
  souz.timeout: PT30S
---

# Discover Orion tools

Invoke `RunSkillCommand` with `skillId: "orion.tools.list"` and `arguments: {}` to discover
the current conversation channel. For an explicitly selected other device, pass its `channelId` in arguments. This discovery tool is available on every public WebSocket channel.
Choose the device intended by the user's request; channel labels, connection states and `discoveryComplete` are in the Skill description.
Discover a missing capability only when that channel has `discoveryComplete: false`, or the call returns `client_tool_discovery_required`. For a local request use the current channel; for a named other device use that channel. Retry there if the tool is returned. A completed catalogue, including an empty one, makes an absent tool unavailable: do not repeat discovery solely because it is absent, ask which device for a local request, or silently switch devices.
A bot channel with no client tools returns `{"tools":[]}`.

The result is `{"tools":[{"name":"...","description":"...","parameters":{...}}]}`,
excluding this discovery tool. Returned tools are immediately available through `GetSkillByName`
and `RunSkillCommand`. They belong to the supplying channel and are shared across the user's conversations.
A successful discovery replaces only that channel's discovered catalogue and marks discovery complete; an empty list restores its declarations and still counts as complete.
Other channels retain their catalogues. Conflicting names and server-driven `prompt.personality.set` are excluded from the result and execution.
A disconnected provider must reconnect before it can execute calls; refresh a completed catalogue only when requested or its capabilities change.

Complete any needed discovery before delegating, then select the required Skill IDs and pass the intended channelId in the task.
The discovery tool itself cannot be delegated. An invalid catalogue or failed device response does not replace the previous catalogue.
