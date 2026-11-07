alter table hook_receipts add column attachments jsonb not null default '[]'::jsonb;
