-- Upgrade the fork's released V20 storage schema to the upstream cleanup contract.
-- Recheck legacy tombstones through S3 deletion before removing their deduplication rows.
alter table stored_attachments add column cleanup_due_at timestamptz;
update stored_attachments
set cleanup_due_at = case
    when state = 'ready' then expires_at
    when state = 'pending' then created_at + interval '10 minutes'
    else coalesce(cleanup_attempt_at, created_at)
end,
state = case when state = 'deleted' then 'deleting' else state end;
alter table stored_attachments alter column cleanup_due_at set not null;
alter table stored_attachments drop constraint stored_attachments_state_check;
alter table stored_attachments add constraint stored_attachments_state_check
    check (state in ('pending', 'ready', 'deleting'));
drop index stored_attachments_ready_expiry_idx;
drop index stored_attachments_pending_created_idx;
drop index stored_attachments_deleting_queue_idx;
alter table stored_attachments drop column cleanup_attempt_at;
create index stored_attachments_cleanup_due_idx on stored_attachments(cleanup_due_at, id);
