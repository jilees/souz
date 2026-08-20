-- The Salute voice channel moved out of backend into a standalone "Orion emulator" service,
-- which owns device bindings locally (deviceId -> userId/chatId) and never asks backend to
-- resolve deviceId. The existing rows were exported into the emulator's local store as a
-- one-time migration before this table was dropped.
drop table salute_device_bindings;
