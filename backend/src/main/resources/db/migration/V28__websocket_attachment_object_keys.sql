-- Rewrite public attachment descriptors once. Internal message/tool/session UUIDs remain unchanged.
with rewritten as (
    select e.id, jsonb_agg(
        case when item.value ? 'id' and not item.value ? 'objectKey'
            then (item.value - 'id') || jsonb_build_object('objectKey', coalesce(f.object_key,
                'files/' || encode(sha256(convert_to(e.user_id, 'UTF8')), 'hex') || '/' || (item.value->>'id')))
            else item.value end
        order by item.ordinality
    ) as attachments
    from agent_events e
    cross join lateral jsonb_array_elements(
        case when jsonb_typeof(e.payload->'attachments') = 'array' then e.payload->'attachments' else '[]'::jsonb end
    ) with ordinality as item(value, ordinality)
    left join stored_attachments f on f.id::text = item.value->>'id' and f.user_id = e.user_id
    where e.type in ('thread.completed', 'message.created')
    group by e.id
)
update agent_events e
set payload = jsonb_set(e.payload, '{attachments}', r.attachments)
from rewritten r
where e.id = r.id and e.payload->'attachments' is distinct from r.attachments;
