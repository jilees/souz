create table attachment_delivery_parts (
    user_id text not null,
    delivery_key text not null,
    part integer not null,
    payload_hash text not null,
    state text not null check (state in ('sending', 'delivered')),
    receipt text,
    primary key (user_id, delivery_key, part)
);
