create table salute_device_bindings (
  id uuid primary key,
  device_id text not null,
  user_id text not null,
  chat_id uuid not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  last_seen_at timestamptz,
  constraint salute_device_bindings_device_id_key unique (device_id),
  constraint salute_device_bindings_chat_id_key unique (chat_id)
);

create index salute_device_bindings_user_idx
on salute_device_bindings(user_id);
