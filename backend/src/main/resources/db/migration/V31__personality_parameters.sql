alter table user_settings
    add column personality_voice text not null default 'joy'
        check (personality_voice in ('sber', 'afina', 'joy')),
    add column personality_appeal text not null default 'no_official'
        check (personality_appeal in ('official', 'no_official')),
    add column personality_style text not null default 'informal'
        check (personality_style in ('informal', 'neutral', 'formal')),
    add column personality_initialized boolean not null default true;

-- Existing users retain their prompt/revision; only new users accept initial client settings.
alter table user_settings alter column personality_initialized set default false;
alter table chats add column your_name text;
