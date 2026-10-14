create table attachment_media_operations (
    user_id text not null,
    operation_key text not null,
    state text not null check (state in ('started', 'succeeded', 'failed')),
    started_at timestamptz not null default now(),
    primary key (user_id, operation_key)
);
