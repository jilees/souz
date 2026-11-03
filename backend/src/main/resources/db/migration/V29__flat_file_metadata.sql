-- Preserve released migration checksums and convert only structured FileContent arrays.
-- ImageContent.file, user text, arbitrary tool arguments and public objectKey descriptors keep their shape.
create function souz_flatten_file_metadata(value jsonb) returns jsonb
language sql immutable as $$
    select case when jsonb_typeof(value) = 'array' then (
        select coalesce(jsonb_agg(
            case when item ?& array['file', 'name', 'mimeType', 'sizeBytes']
                and not item ? 'fileId'
                and jsonb_typeof(item->'file'->'fileId') = 'string'
            then (item - 'file') || jsonb_build_object('fileId', item->'file'->'fileId')
            else item end order by ordinal), '[]'::jsonb)
        from jsonb_array_elements(value) with ordinality as files(item, ordinal)
    ) else value end;
$$;

update messages set attachments = souz_flatten_file_metadata(attachments)
where attachments <> '[]'::jsonb;

with converted as (
    select user_id, chat_id, context_json
        || case when context_json ? 'replyAttachments' then jsonb_build_object(
            'replyAttachments', souz_flatten_file_metadata(context_json->'replyAttachments')) else '{}'::jsonb end
        || case when jsonb_typeof(context_json->'history') = 'array' then jsonb_build_object('history', (
            select coalesce(jsonb_agg(message
                || case when message ? 'files' then jsonb_build_object(
                    'files', souz_flatten_file_metadata(message->'files')) else '{}'::jsonb end
                || case when message ? 'replyFiles' then jsonb_build_object(
                    'replyFiles', souz_flatten_file_metadata(message->'replyFiles')) else '{}'::jsonb end
                order by ordinal), '[]'::jsonb)
            from jsonb_array_elements(context_json->'history') with ordinality as history(message, ordinal)
        )) else '{}'::jsonb end as context
    from agent_conversation_state
)
update agent_conversation_state as state set context_json = converted.context
from converted where state.user_id = converted.user_id and state.chat_id = converted.chat_id;

drop function souz_flatten_file_metadata(jsonb);
