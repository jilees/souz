alter table history_memory_fragments
  add column voice_embedding_id uuid,
  add column target_banks text[],
  add column delivered_banks text[] not null default '{}';

-- Voice turns use a frozen payload and their submitted user message as the source.
-- A null target_banks means classification has not succeeded; an empty array means skip.
alter table history_memory_fragments add constraint voice_memory_routing_scope
  check (voice_embedding_id is not null or (target_banks is null and cardinality(delivered_banks) = 0));
