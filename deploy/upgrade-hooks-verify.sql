-- Run with the backend stopped and search_path set to SOUZ_BACKEND_DB_SCHEMA.
-- One atomic statement; unknown migration histories fail without modifications.
do $$
declare
    applied_checksum integer;
begin
    lock table flyway_schema_history in exclusive mode;
    select checksum into strict applied_checksum from flyway_schema_history
        where version = '14' and script = 'V14__hooks.sql' and success;
    if applied_checksum = -1856568482 then
        return;
    end if;
    if applied_checksum is distinct from -789050753 then
        raise exception 'Unsupported hooks V14 checksum: %', applied_checksum;
    end if;
    if not exists (
        select 1 from information_schema.columns
        where table_schema = current_schema() and table_name = 'hook_receipts'
            and column_name = 'prompt' and data_type = 'text' and is_nullable = 'NO'
    ) or not exists (
        select 1 from information_schema.columns
        where table_schema = current_schema() and table_name = 'hook_receipts'
            and column_name = 'dispatched_at' and data_type = 'timestamp with time zone'
    ) then
        raise exception 'Unexpected legacy hook_receipts schema';
    end if;
    -- Keep legacy prompt/dispatch data and the harmless ordinal uniqueness constraint.
    alter table hook_receipts alter column prompt drop not null;
    drop index hook_receipts_pending_idx;
    create index hook_receipts_pending_idx on hook_receipts(hook_id, ordinal)
        where status in ('pending', 'running');
    -- Only this known V14 is reconciled, after its schema has been adapted.
    update flyway_schema_history set checksum = -1856568482 where version = '14';
end $$;
