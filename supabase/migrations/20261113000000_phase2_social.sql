-- BroBehave — Phase 2: onboarding details, notification settings, friendship and
-- post RPCs, and the push-notification outbox.

create extension if not exists pg_net with schema extensions;

-- ---------------------------------------------------------------------------
-- Profiles: 18+ confirmation
-- ---------------------------------------------------------------------------
alter table public.profiles add column age_confirmed_at timestamptz;

-- ---------------------------------------------------------------------------
-- Notification settings (per-type toggles)
-- ---------------------------------------------------------------------------
create table public.notification_settings (
    user_id uuid primary key references public.profiles (id) on delete cascade,
    friend_requests boolean not null default true,
    milestones boolean not null default true,
    tier_changes boolean not null default true,
    reactions boolean not null default true
);
alter table public.notification_settings enable row level security;

create policy notification_settings_select_own on public.notification_settings for select to authenticated
    using (user_id = auth.uid());
create policy notification_settings_update_own on public.notification_settings for update to authenticated
    using (user_id = auth.uid()) with check (user_id = auth.uid());

insert into public.notification_settings (user_id)
select p.id from public.profiles p
on conflict do nothing;

create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    insert into public.profiles (id) values (new.id);
    insert into public.privacy_settings (user_id) values (new.id);
    insert into public.notification_settings (user_id) values (new.id);
    return new;
end;
$$;

-- ---------------------------------------------------------------------------
-- Onboarding: one call sets everything the onboarding flow collects
-- ---------------------------------------------------------------------------
drop function public.complete_onboarding(text, text, timestamptz);

create function public.complete_onboarding(
    p_username text,
    p_timezone text,
    p_age_confirmed boolean,
    p_streak_start timestamptz default null,
    p_display_name text default null,
    p_self_report boolean default false,
    p_show_streak public.visibility default 'friends',
    p_show_relapses public.visibility default 'friends',
    p_show_tier public.visibility default 'friends'
) returns void
language plpgsql security definer set search_path = '' as $$
declare
    v_user uuid := auth.uid();
    v_start timestamptz := coalesce(p_streak_start, now());
begin
    if v_user is null then
        raise exception 'Not signed in' using errcode = '28000';
    end if;
    if p_age_confirmed is not true then
        raise exception 'BroBehave is for adults (18+) only' using errcode = '22023';
    end if;
    if exists (select 1 from public.profiles p where p.id = v_user and p.onboarded_at is not null) then
        raise exception 'Onboarding already completed' using errcode = '22023';
    end if;
    if v_start > now() + interval '1 minute' or v_start < now() - interval '366 days' then
        raise exception 'Streak start must be within the last 365 days' using errcode = '22023';
    end if;

    update public.profiles p
    set username = lower(p_username),
        display_name = coalesce(nullif(btrim(p_display_name), ''), lower(p_username)),
        timezone = p_timezone,
        streak_start = least(v_start, now()),
        self_report_enabled = coalesce(p_self_report, false),
        age_confirmed_at = now(),
        onboarded_at = now()
    where p.id = v_user;

    update public.privacy_settings ps
    set show_streak = p_show_streak, show_relapses = p_show_relapses, show_tier = p_show_tier
    where ps.user_id = v_user;

    insert into public.events (user_id, type, created_at, local_date)
    values (v_user, 'streak_started', now(), current_date);

    -- Set the starting rank quietly: no rank-up posts for a backdated streak.
    perform public.compute_tiers(v_user, false);
end;
$$;

-- Lets the username field show "taken" before submitting. Reveals only whether a name exists.
create function public.username_available(p_username text) returns boolean
language sql stable security definer set search_path = '' as $$
    select lower(p_username) ~ '^[a-z0-9_]{3,20}$'
       and not exists (
           select 1 from public.profiles p
           where p.username = lower(p_username) and p.id <> auth.uid()
       );
$$;

-- ---------------------------------------------------------------------------
-- Friendships with the other person's identity (friends + incoming + outgoing)
-- ---------------------------------------------------------------------------
create function public.my_friendships() returns table (
    friendship_id uuid,
    user_id uuid,
    username text,
    display_name text,
    avatar_url text,
    -- 'friend' | 'incoming' | 'outgoing'
    relation text,
    created_at timestamptz
)
language sql stable security definer set search_path = '' as $$
    select
        f.id, p.id, p.username, p.display_name, p.avatar_url,
        case
            when f.status = 'accepted' then 'friend'
            when f.addressee_id = auth.uid() then 'incoming'
            else 'outgoing'
        end,
        f.created_at
    from public.friendships f
    join public.profiles p
        on p.id = case when f.requester_id = auth.uid() then f.addressee_id else f.requester_id end
    where auth.uid() in (f.requester_id, f.addressee_id)
      and not public._is_blocked_between(f.requester_id, f.addressee_id)
    order by f.status, f.created_at desc;
$$;

-- ---------------------------------------------------------------------------
-- Posts: one query shape for the feed, "My posts" and a friend's profile
-- ---------------------------------------------------------------------------
-- p_owner null  -> all friends' posts (the feed)
-- p_owner given -> that user's posts (own, or a friend's as far as privacy allows)
create function public._posts(
    p_viewer uuid,
    p_owner uuid,
    p_cursor_created_at timestamptz,
    p_cursor_id uuid,
    p_limit integer
) returns table (
    id uuid,
    user_id uuid,
    username text,
    display_name text,
    avatar_url text,
    type public.event_type,
    payload jsonb,
    local_date date,
    created_at timestamptz,
    tier public.tier,
    tier_verified boolean,
    reaction_counts jsonb,
    reaction_total integer,
    my_reaction public.reaction_type,
    allow_haha boolean
)
language sql stable security definer set search_path = '' as $$
    select
        e.id, e.user_id, p.username, p.display_name, p.avatar_url,
        e.type, e.payload, e.local_date, e.created_at,
        case when e.user_id = p_viewer or ps.show_tier = 'friends' then p.tier end,
        case when e.user_id = p_viewer or ps.show_tier = 'friends' then p.tier_verified else false end,
        coalesce(rc.counts, '{}'::jsonb),
        coalesce(rc.total, 0)::integer,
        mine.type,
        ps.allow_haha
    from public.events e
    join public.profiles p on p.id = e.user_id
    join public.privacy_settings ps on ps.user_id = e.user_id
    left join lateral (
        select jsonb_object_agg(t.type, t.n) as counts, sum(t.n) as total
        from (
            select r.type, count(*) as n
            from public.reactions r
            where r.event_id = e.id
            group by r.type
        ) t
    ) rc on true
    left join public.reactions mine on mine.event_id = e.id and mine.user_id = p_viewer
    where (
            (p_owner is null and e.user_id in (select public._friend_ids(p_viewer)))
            or (p_owner is not null and e.user_id = p_owner)
          )
      and public._can_view_event(p_viewer, e.user_id, e.type)
      and (p_cursor_created_at is null or (e.created_at, e.id) < (p_cursor_created_at, p_cursor_id))
    order by e.created_at desc, e.id desc
    limit least(greatest(coalesce(p_limit, 20), 1), 50);
$$;

create or replace function public.feed(
    p_cursor_created_at timestamptz default null,
    p_cursor_id uuid default null,
    p_limit integer default 20
) returns table (
    id uuid,
    user_id uuid,
    username text,
    display_name text,
    avatar_url text,
    type public.event_type,
    payload jsonb,
    local_date date,
    created_at timestamptz,
    tier public.tier,
    tier_verified boolean,
    reaction_counts jsonb,
    reaction_total integer,
    my_reaction public.reaction_type,
    allow_haha boolean
)
language sql stable security definer set search_path = '' as $$
    select * from public._posts(auth.uid(), null, p_cursor_created_at, p_cursor_id, p_limit);
$$;

create function public.user_posts(
    p_user uuid,
    p_cursor_created_at timestamptz default null,
    p_cursor_id uuid default null,
    p_limit integer default 20
) returns table (
    id uuid,
    user_id uuid,
    username text,
    display_name text,
    avatar_url text,
    type public.event_type,
    payload jsonb,
    local_date date,
    created_at timestamptz,
    tier public.tier,
    tier_verified boolean,
    reaction_counts jsonb,
    reaction_total integer,
    my_reaction public.reaction_type,
    allow_haha boolean
)
language sql stable security definer set search_path = '' as $$
    select * from public._posts(auth.uid(), p_user, p_cursor_created_at, p_cursor_id, p_limit);
$$;

-- ---------------------------------------------------------------------------
-- Push notifications: database triggers fill an outbox; the `push` Edge Function
-- turns outbox rows into FCM messages. Clients never see this table.
-- ---------------------------------------------------------------------------
create table public.notification_outbox (
    id uuid primary key default gen_random_uuid(),
    recipient_id uuid not null references public.profiles (id) on delete cascade,
    -- friend_request | milestone | tier_change | reaction
    kind text not null check (kind in ('friend_request', 'milestone', 'tier_change', 'reaction')),
    actor_id uuid references public.profiles (id) on delete cascade,
    event_id uuid references public.events (id) on delete cascade,
    payload jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    sent_at timestamptz
);
create index notification_outbox_batch_idx on public.notification_outbox (recipient_id, event_id, kind, created_at desc);
alter table public.notification_outbox enable row level security;  -- no policies: service role only

create function public.enqueue_friend_request_push() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    if new.status = 'pending'
       and exists (select 1 from public.notification_settings ns where ns.user_id = new.addressee_id and ns.friend_requests) then
        insert into public.notification_outbox (recipient_id, kind, actor_id)
        values (new.addressee_id, 'friend_request', new.requester_id);
    end if;
    return new;
end;
$$;
create trigger friendships_enqueue_push
    after insert on public.friendships
    for each row execute function public.enqueue_friend_request_push();

-- A friend's milestone or tier change goes to every friend who is allowed to see it.
create function public.enqueue_event_push() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
    v_kind text := case when new.type = 'milestone' then 'milestone' else 'tier_change' end;
begin
    insert into public.notification_outbox (recipient_id, kind, actor_id, event_id, payload)
    select f.friend_id, v_kind, new.user_id, new.id, jsonb_build_object('type', new.type) || new.payload
    from (select public._friend_ids(new.user_id) as friend_id) f
    join public.notification_settings ns on ns.user_id = f.friend_id
    where public._can_view_event(f.friend_id, new.user_id, new.type)
      and case when v_kind = 'milestone' then ns.milestones else ns.tier_changes end;
    return new;
end;
$$;
create trigger events_enqueue_push
    after insert on public.events
    for each row when (new.type in ('milestone', 'tier_up', 'tier_down'))
    execute function public.enqueue_event_push();

-- Reactions: at most one notification per post per 15 minutes. The Edge Function words
-- it as "Rafi and 4 others reacted…" from the reactions on the post at send time.
create function public.enqueue_reaction_push() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid;
begin
    select e.user_id into v_owner from public.events e where e.id = new.event_id;
    if v_owner is null or v_owner = new.user_id then
        return new;
    end if;
    if not exists (select 1 from public.notification_settings ns where ns.user_id = v_owner and ns.reactions) then
        return new;
    end if;
    if exists (
        select 1 from public.notification_outbox o
        where o.recipient_id = v_owner and o.event_id = new.event_id and o.kind = 'reaction'
          and o.created_at > now() - interval '15 minutes'
    ) then
        return new;
    end if;
    insert into public.notification_outbox (recipient_id, kind, actor_id, event_id, payload)
    values (v_owner, 'reaction', new.user_id, new.event_id, jsonb_build_object('reaction', new.type));
    return new;
end;
$$;
create trigger reactions_enqueue_push
    after insert on public.reactions
    for each row execute function public.enqueue_reaction_push();

-- Hands each new outbox row to the `push` Edge Function. The function URL and a shared
-- secret live in Supabase Vault (never in the repo); until both are set this is a no-op.
--   select vault.create_secret('https://<project>.supabase.co/functions/v1/push', 'push_function_url');
--   select vault.create_secret('<random secret, same as PUSH_WEBHOOK_SECRET>', 'push_function_secret');
create function public.dispatch_push() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
    v_url text;
    v_secret text;
begin
    select s.decrypted_secret into v_url from vault.decrypted_secrets s where s.name = 'push_function_url';
    select s.decrypted_secret into v_secret from vault.decrypted_secrets s where s.name = 'push_function_secret';
    if v_url is null or v_secret is null then
        return new;
    end if;
    perform net.http_post(
        url := v_url,
        headers := jsonb_build_object('Content-Type', 'application/json', 'Authorization', 'Bearer ' || v_secret),
        body := jsonb_build_object('outbox_id', new.id)
    );
    return new;
exception when others then
    -- Push delivery must never break the write that triggered it.
    return new;
end;
$$;
create trigger notification_outbox_dispatch
    after insert on public.notification_outbox
    for each row execute function public.dispatch_push();

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
revoke all on public.notification_settings, public.notification_outbox from anon, authenticated;
grant select on public.notification_settings to authenticated;
grant update (friend_requests, milestones, tier_changes, reactions) on public.notification_settings to authenticated;

revoke execute on all functions in schema public from public, anon, authenticated;
grant execute on function
    public.is_blocked_with(uuid),
    public.my_local_today(),
    public.my_self_report_enabled(),
    public.can_react(uuid, public.reaction_type),
    public.complete_onboarding(
        text, text, boolean, timestamptz, text, boolean,
        public.visibility, public.visibility, public.visibility
    ),
    public.username_available(text),
    public.feed(timestamptz, uuid, integer),
    public.user_posts(uuid, timestamptz, uuid, integer),
    public.friends_ranks(),
    public.my_friendships(),
    public.get_profile(uuid),
    public.search_users(text),
    public.event_reactors(uuid)
to authenticated;
