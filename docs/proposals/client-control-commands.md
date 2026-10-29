# Client control command declarations

Status: implemented. Runtime details are in [personality settings](../../backend/docs/personality-settings.md).

Orion declares `prompt.personality.set` through the existing `tools` catalogue only when its handler is configured. Bot channels do not declare it. Souz keeps the definition for server-driven routing and excludes it from model inventory, discovery output and direct or delegated execution.

A committed personality snapshot goes to the current eligible channel, otherwise another owned connected channel declaring support. Publication rejection can fall back to another eligible channel. With no recipient, synchronization is skipped without error. Subscription/reconnection sends the current stored revision to that declaring channel. Orion's user-scoped storage and active-dialog-only voice selection are unchanged.

The parent requests `orion.tools.list` lazily when capabilities are needed. Completed empty or nonempty results are current for that instance; a backend restart makes them stale until rediscovery. Connection and runtime construction do not initiate discovery. No WS fields or PostgreSQL schema changes are required; existing creation receipts and stored catalogue JSON stay rollback-compatible.
