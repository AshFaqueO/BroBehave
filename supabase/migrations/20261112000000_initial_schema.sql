-- BroBehave — initial schema.
--
-- Principles
--   * Row Level Security on every table. Clients read only their own rows directly;
--     anything about other users comes from SECURITY DEFINER RPCs that apply privacy
--     settings in SQL (feed, friends_ranks, get_profile, search_users, event_reactors).
--   * Tier columns, tier events and verification are computed server-side only.
--   * Timestamps are UTC; "local dates" use the user's IANA timezone.
--   * flagged_days stores a date and a source. Never domains, counts or times.

create extension if not exists pg_cron with schema pg_catalog;

-- ---------------------------------------------------------------------------
-- Enums
-- ---------------------------------------------------------------------------
create type public.tier as enum (
    'clown', 'noob', 'novice', 'average', 'advanced', 'sigma', 'chad', 'absolute_chad', 'giga_chad'
);
create type public.visibility as enum ('friends', 'private');
create type public.friendship_status as enum ('pending', 'accepted');
create type public.event_type as enum ('streak_started', 'relapse', 'milestone', 'tier_up', 'tier_down');
create type public.reaction_type as enum ('haha', 'impressive', 'fire', 'respect', 'strong');
create type public.flag_source as enum ('auto', 'self');

-- ---------------------------------------------------------------------------
-- Tier config (mirrored by com.brobehave.core.model.Rank / TierConfig in Kotlin)
-- ---------------------------------------------------------------------------
create table public.tier_config (
    tier public.tier primary key,
    ordinal smallint not null unique,
    min_streak_days integer not null unique
);

insert into public.tier_config (tier, ordinal, min_streak_days) values
    ('clown', 0, 0),
    ('noob', 1, 1),
    ('novice', 2, 3),
    ('average', 3, 7),
    ('advanced', 4, 15),
    ('sigma', 5, 30),
    ('chad', 6, 45),
    ('absolute_chad', 7, 60),
    ('giga_chad', 8, 120);

-- Single-row settings table.
create table public.tier_settings (
    id boolean primary key default true check (id),
    penalty_per_flagged_day integer not null default 1 check (penalty_per_flagged_day >= 0),
    window_days integer not null default 7 check (window_days between 1 and 31)
);
insert into public.tier_settings default values;

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------
create table public.profiles (
    id uuid primary key references auth.users (id) on delete cascade,
    -- Null until onboarding is completed.
    username text unique check (username ~ '^[a-z0-9_]{3,20}$'),
    display_name text check (char_length(display_name) between 1 and 40),
    avatar_url text,
    timezone text not null default 'UTC',
    streak_start timestamptz not null default now(),
    longest_streak_days integer not null default 0,
    tier public.tier not null default 'clown',
    streak_tier public.tier not null default 'clown',
    tier_verified boolean not null default false,
    self_report_enabled boolean not null default false,
    -- Set once by complete_onboarding(); afterwards streak_start only changes through a relapse.
    onboarded_at timestamptz,
    tier_updated_at timestamptz not null default now(),
    created_at timestamptz not null default now()
);
create index profiles_username_prefix_idx on public.profiles (username text_pattern_ops);

create table public.privacy_settings (
    user_id uuid primary key references public.profiles (id) on delete cascade,
    show_streak public.visibility not null default 'friends',
    show_relapses public.visibility not null default 'friends',
    show_tier public.visibility not null default 'friends',
    allow_haha boolean not null default true
);

create table public.friendships (
    id uuid primary key default gen_random_uuid(),
    requester_id uuid not null references public.profiles (id) on delete cascade,
    addressee_id uuid not null references public.profiles (id) on delete cascade,
    status public.friendship_status not null default 'pending',
    created_at timestamptz not null default now(),
    check (requester_id <> addressee_id)
);
-- One friendship per pair, regardless of who asked.
create unique index friendships_unique_pair_idx
    on public.friendships (least(requester_id, addressee_id), greatest(requester_id, addressee_id));
create index friendships_addressee_idx on public.friendships (addressee_id);

create table public.blocks (
    blocker_id uuid not null references public.profiles (id) on delete cascade,
    blocked_id uuid not null references public.profiles (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (blocker_id, blocked_id),
    check (blocker_id <> blocked_id)
);
create index blocks_blocked_idx on public.blocks (blocked_id);

-- Insert-only. Clients may insert only their own 'relapse'; everything else is written
-- by database functions.
create table public.events (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles (id) on delete cascade,
    type public.event_type not null,
    payload jsonb not null default '{}'::jsonb,
    local_date date not null,
    created_at timestamptz not null default now()
);
create index events_user_created_idx on public.events (user_id, created_at desc, id desc);

create table public.reactions (
    event_id uuid not null references public.events (id) on delete cascade,
    user_id uuid not null references public.profiles (id) on delete cascade,
    type public.reaction_type not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    -- One reaction per user per post; changing it updates the row.
    primary key (event_id, user_id)
);

-- Insert-only: users cannot delete their own flagged days.
create table public.flagged_days (
    user_id uuid not null references public.profiles (id) on delete cascade,
    local_date date not null,
    source public.flag_source not null,
    created_at timestamptz not null default now(),
    primary key (user_id, local_date)
);

-- One row per local day while automatic detection is running.
create table public.detection_heartbeats (
    user_id uuid not null references public.profiles (id) on delete cascade,
    local_date date not null,
    created_at timestamptz not null default now(),
    primary key (user_id, local_date)
);

create table public.device_tokens (
    user_id uuid not null references public.profiles (id) on delete cascade,
    fcm_token text not null,
    updated_at timestamptz not null default now(),
    primary key (user_id, fcm_token)
);

create table public.reports (
    id uuid primary key default gen_random_uuid(),
    reporter_id uuid not null references public.profiles (id) on delete cascade,
    target_user_id uuid references public.profiles (id) on delete cascade,
    target_event_id uuid references public.events (id) on delete cascade,
    reason text not null check (char_length(reason) between 1 and 500),
    created_at timestamptz not null default now(),
    check (target_user_id is not null or target_event_id is not null)
);

-- ---------------------------------------------------------------------------
-- Internal helpers (never granted to clients)
-- ---------------------------------------------------------------------------
create function public._are_friends(p_a uuid, p_b uuid) returns boolean
language sql stable security definer set search_path = '' as $$
    select exists (
        select 1 from public.friendships f
        where f.status = 'accepted'
          and ((f.requester_id = p_a and f.addressee_id = p_b)
            or (f.requester_id = p_b and f.addressee_id = p_a))
    );
$$;

create function public._is_blocked_between(p_a uuid, p_b uuid) returns boolean
language sql stable security definer set search_path = '' as $$
    select exists (
        select 1 from public.blocks b
        where (b.blocker_id = p_a and b.blocked_id = p_b)
           or (b.blocker_id = p_b and b.blocked_id = p_a)
    );
$$;

create function public._friend_ids(p_user uuid) returns setof uuid
language sql stable security definer set search_path = '' as $$
    select case when f.requester_id = p_user then f.addressee_id else f.requester_id end
    from public.friendships f
    where f.status = 'accepted'
      and p_user in (f.requester_id, f.addressee_id)
      and not public._is_blocked_between(f.requester_id, f.addressee_id);
$$;

create function public._local_date(p_user uuid, p_at timestamptz) returns date
language sql stable security definer set search_path = '' as $$
    select (p_at at time zone p.timezone)::date from public.profiles p where p.id = p_user;
$$;

-- Whether p_viewer may see p_owner's events of type p_type, per the owner's privacy.
create function public._can_view_event(p_viewer uuid, p_owner uuid, p_type public.event_type) returns boolean
language sql stable security definer set search_path = '' as $$
    select p_viewer = p_owner
        or (
            public._are_friends(p_viewer, p_owner)
            and not public._is_blocked_between(p_viewer, p_owner)
            and coalesce((
                select case
                    when p_type = 'relapse' then ps.show_relapses = 'friends'
                    when p_type in ('tier_up', 'tier_down') then ps.show_tier = 'friends'
                    else ps.show_streak = 'friends'
                end
                from public.privacy_settings ps where ps.user_id = p_owner
            ), false)
        );
$$;

-- ---------------------------------------------------------------------------
-- Tier computation (section 5 of the spec; refined and fully tested in Phase 3)
-- ---------------------------------------------------------------------------
-- effective rank = streak rank - (flagged days in the rolling window * penalty), floored at Clown.
-- tier_verified  = a detection heartbeat exists for every local day in the window.
create function public.compute_tiers(
    p_user uuid,
    p_emit_events boolean default true,
    p_now timestamptz default now()
) returns void
language plpgsql security definer set search_path = '' as $$
declare
    v_profile public.profiles%rowtype;
    v_settings public.tier_settings%rowtype;
    v_today date;
    v_window_start date;
    v_streak_days integer;
    v_streak_tier public.tier;
    v_streak_ord integer;
    v_old_streak_ord integer;
    v_flagged integer;
    v_new_ord integer;
    v_new_tier public.tier;
    v_old_ord integer;
    v_verified boolean;
begin
    select * into v_profile from public.profiles where id = p_user for update;
    if not found then
        return;
    end if;
    select * into v_settings from public.tier_settings;

    v_today := (p_now at time zone v_profile.timezone)::date;
    v_window_start := v_today - (v_settings.window_days - 1);
    v_streak_days := greatest(0, v_today - (v_profile.streak_start at time zone v_profile.timezone)::date);

    select tc.tier, tc.ordinal into v_streak_tier, v_streak_ord
    from public.tier_config tc
    where tc.min_streak_days <= v_streak_days
    order by tc.ordinal desc
    limit 1;

    select count(*) into v_flagged
    from public.flagged_days fd
    where fd.user_id = p_user and fd.local_date between v_window_start and v_today;

    v_new_ord := greatest(0, v_streak_ord - v_flagged * v_settings.penalty_per_flagged_day);
    select tc.tier into v_new_tier from public.tier_config tc where tc.ordinal = v_new_ord;

    select count(*) = v_settings.window_days into v_verified
    from public.detection_heartbeats h
    where h.user_id = p_user and h.local_date between v_window_start and v_today;

    select tc.ordinal into v_old_ord from public.tier_config tc where tc.tier = v_profile.tier;
    select tc.ordinal into v_old_streak_ord from public.tier_config tc where tc.tier = v_profile.streak_tier;

    update public.profiles p
    set tier = v_new_tier,
        streak_tier = v_streak_tier,
        tier_verified = v_verified,
        longest_streak_days = greatest(p.longest_streak_days, v_streak_days),
        tier_updated_at = case when p.tier <> v_new_tier then p_now else p.tier_updated_at end
    where p.id = p_user;

    if p_emit_events then
        if v_streak_ord > v_old_streak_ord then
            -- The streak itself earned a new rank.
            insert into public.events (user_id, type, payload, local_date)
            values (
                p_user, 'milestone',
                jsonb_build_object('streak_days', v_streak_days, 'rank', v_streak_tier, 'tier', v_new_tier),
                v_today
            );
        elsif v_new_ord > v_old_ord then
            insert into public.events (user_id, type, payload, local_date)
            values (p_user, 'tier_up', jsonb_build_object('from', v_profile.tier, 'to', v_new_tier), v_today);
        elsif v_new_ord < v_old_ord then
            insert into public.events (user_id, type, payload, local_date)
            values (p_user, 'tier_down', jsonb_build_object('from', v_profile.tier, 'to', v_new_tier), v_today);
        end if;
    end if;
end;
$$;

-- Hourly (pg_cron): recompute for users whose local midnight just passed, so streak days
-- increase and old flagged days leave the window.
create function public.recompute_tiers_after_local_midnight() returns void
language plpgsql security definer set search_path = '' as $$
declare
    r record;
begin
    for r in
        select p.id from public.profiles p
        where p.onboarded_at is not null
          and extract(hour from (now() at time zone p.timezone)) = 0
    loop
        perform public.compute_tiers(r.id);
    end loop;
end;
$$;

select cron.schedule(
    'recompute-tiers-hourly',
    '5 * * * *',
    $$select public.recompute_tiers_after_local_midnight()$$
);

-- ---------------------------------------------------------------------------
-- Triggers
-- ---------------------------------------------------------------------------
-- New auth user -> profile + privacy settings.
create function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    insert into public.profiles (id) values (new.id);
    insert into public.privacy_settings (user_id) values (new.id);
    return new;
end;
$$;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

create function public.validate_timezone() returns trigger
language plpgsql set search_path = '' as $$
begin
    if not exists (select 1 from pg_catalog.pg_timezone_names tz where tz.name = new.timezone) then
        raise exception 'Unknown timezone: %', new.timezone using errcode = '22023';
    end if;
    return new;
end;
$$;
create trigger profiles_validate_timezone
    before insert or update of timezone on public.profiles
    for each row execute function public.validate_timezone();

-- local_date is always derived on the server from created_at and the owner's timezone.
create function public.events_set_local_date() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    new.local_date := public._local_date(new.user_id, new.created_at);
    return new;
end;
$$;
create trigger events_set_local_date
    before insert on public.events
    for each row execute function public.events_set_local_date();

-- A relapse resets the streak (-> Clown) and starts a new one.
create function public.handle_relapse() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    update public.profiles p
    set longest_streak_days = greatest(
            p.longest_streak_days,
            (new.created_at at time zone p.timezone)::date - (p.streak_start at time zone p.timezone)::date
        ),
        streak_start = new.created_at
    where p.id = new.user_id;

    insert into public.events (user_id, type, created_at, local_date)
    values (new.user_id, 'streak_started', new.created_at + interval '1 millisecond', new.local_date);

    -- The relapse post already tells the story; no extra tier_down post.
    perform public.compute_tiers(new.user_id, false);
    return new;
end;
$$;
create trigger events_handle_relapse
    after insert on public.events
    for each row when (new.type = 'relapse') execute function public.handle_relapse();

create function public.recompute_tiers_trigger() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    perform public.compute_tiers(new.user_id);
    return new;
end;
$$;
create trigger flagged_days_recompute
    after insert on public.flagged_days
    for each row execute function public.recompute_tiers_trigger();
create trigger detection_heartbeats_recompute
    after insert on public.detection_heartbeats
    for each row execute function public.recompute_tiers_trigger();

-- Blocking someone ends any friendship or pending request between the two.
create function public.handle_block() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    delete from public.friendships f
    where (f.requester_id = new.blocker_id and f.addressee_id = new.blocked_id)
       or (f.requester_id = new.blocked_id and f.addressee_id = new.blocker_id);
    return new;
end;
$$;
create trigger blocks_handle_block
    after insert on public.blocks
    for each row execute function public.handle_block();

create function public.touch_updated_at() returns trigger
language plpgsql set search_path = '' as $$
begin
    new.updated_at := now();
    return new;
end;
$$;
create trigger reactions_touch_updated_at
    before update on public.reactions
    for each row execute function public.touch_updated_at();
create trigger device_tokens_touch_updated_at
    before update on public.device_tokens
    for each row execute function public.touch_updated_at();

-- ---------------------------------------------------------------------------
-- Helpers used inside RLS policies. They only ever answer about the caller
-- (auth.uid()), so they leak nothing about third parties.
-- ---------------------------------------------------------------------------
create function public.is_blocked_with(p_other uuid) returns boolean
language sql stable security definer set search_path = '' as $$
    select public._is_blocked_between(auth.uid(), p_other);
$$;

create function public.my_local_today() returns date
language sql stable security definer set search_path = '' as $$
    select public._local_date(auth.uid(), now());
$$;

create function public.my_self_report_enabled() returns boolean
language sql stable security definer set search_path = '' as $$
    select coalesce((select p.self_report_enabled from public.profiles p where p.id = auth.uid()), false);
$$;

-- May the caller put reaction p_type on event p_event?
create function public.can_react(p_event uuid, p_type public.reaction_type) returns boolean
language sql stable security definer set search_path = '' as $$
    select exists (
        select 1
        from public.events e
        join public.privacy_settings ps on ps.user_id = e.user_id
        where e.id = p_event
          and e.user_id <> auth.uid()                       -- not your own post
          and public._can_view_event(auth.uid(), e.user_id, e.type)
          and (p_type <> 'haha' or ps.allow_haha)           -- owner may switch HAHA off
    );
$$;

-- ---------------------------------------------------------------------------
-- Row Level Security
-- ---------------------------------------------------------------------------
alter table public.tier_config enable row level security;
alter table public.tier_settings enable row level security;
alter table public.profiles enable row level security;
alter table public.privacy_settings enable row level security;
alter table public.friendships enable row level security;
alter table public.blocks enable row level security;
alter table public.events enable row level security;
alter table public.reactions enable row level security;
alter table public.flagged_days enable row level security;
alter table public.detection_heartbeats enable row level security;
alter table public.device_tokens enable row level security;
alter table public.reports enable row level security;

create policy tier_config_read on public.tier_config for select to authenticated using (true);
create policy tier_settings_read on public.tier_settings for select to authenticated using (true);

-- profiles: own row only. Other users are visible only through the RPCs below.
create policy profiles_select_own on public.profiles for select to authenticated
    using (id = auth.uid());
create policy profiles_update_own on public.profiles for update to authenticated
    using (id = auth.uid()) with check (id = auth.uid());

create policy privacy_select_own on public.privacy_settings for select to authenticated
    using (user_id = auth.uid());
create policy privacy_update_own on public.privacy_settings for update to authenticated
    using (user_id = auth.uid()) with check (user_id = auth.uid());

create policy friendships_select_party on public.friendships for select to authenticated
    using (auth.uid() in (requester_id, addressee_id));
create policy friendships_insert_request on public.friendships for insert to authenticated
    with check (
        requester_id = auth.uid()
        and status = 'pending'
        and not public.is_blocked_with(addressee_id)
    );
-- Only the addressee can accept; nothing else is updatable (see column grants).
create policy friendships_accept on public.friendships for update to authenticated
    using (addressee_id = auth.uid() and status = 'pending')
    with check (addressee_id = auth.uid() and status = 'accepted');
-- Decline, cancel or remove.
create policy friendships_delete_party on public.friendships for delete to authenticated
    using (auth.uid() in (requester_id, addressee_id));

create policy blocks_select_own on public.blocks for select to authenticated
    using (blocker_id = auth.uid());
create policy blocks_insert_own on public.blocks for insert to authenticated
    with check (blocker_id = auth.uid());
create policy blocks_delete_own on public.blocks for delete to authenticated
    using (blocker_id = auth.uid());

-- events: read own directly (friends' posts come from feed()); insert own relapse only.
create policy events_select_own on public.events for select to authenticated
    using (user_id = auth.uid());
create policy events_insert_own_relapse on public.events for insert to authenticated
    with check (
        user_id = auth.uid()
        and type = 'relapse'
        and created_at <= now() + interval '1 minute'
        and created_at > now() - interval '7 days'   -- room for offline sync
    );

create policy reactions_select_related on public.reactions for select to authenticated
    using (
        user_id = auth.uid()
        or exists (select 1 from public.events e where e.id = event_id and e.user_id = auth.uid())
    );
create policy reactions_insert_own on public.reactions for insert to authenticated
    with check (user_id = auth.uid() and public.can_react(event_id, type));
create policy reactions_update_own on public.reactions for update to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid() and public.can_react(event_id, type));
create policy reactions_delete_own on public.reactions for delete to authenticated
    using (user_id = auth.uid());

-- flagged_days: insert-only. Self-reports need self_report_enabled; dates must be recent.
create policy flagged_days_select_own on public.flagged_days for select to authenticated
    using (user_id = auth.uid());
create policy flagged_days_insert_own on public.flagged_days for insert to authenticated
    with check (
        user_id = auth.uid()
        and (source = 'auto' or public.my_self_report_enabled())
        and local_date between public.my_local_today() - 6 and public.my_local_today()
    );

create policy heartbeats_select_own on public.detection_heartbeats for select to authenticated
    using (user_id = auth.uid());
create policy heartbeats_insert_own on public.detection_heartbeats for insert to authenticated
    with check (
        user_id = auth.uid()
        and local_date between public.my_local_today() - 1 and public.my_local_today()
    );

create policy device_tokens_all_own on public.device_tokens for all to authenticated
    using (user_id = auth.uid()) with check (user_id = auth.uid());

-- reports: write-only for clients; moderators read with the service role.
create policy reports_insert_own on public.reports for insert to authenticated
    with check (reporter_id = auth.uid());

-- ---------------------------------------------------------------------------
-- RPCs (the only way to read anything about other users)
-- ---------------------------------------------------------------------------
-- Finishes onboarding exactly once: username, timezone and an optional streak start
-- (up to 365 days back). Afterwards streak_start only changes through a relapse.
create function public.complete_onboarding(
    p_username text,
    p_timezone text,
    p_streak_start timestamptz default null
) returns void
language plpgsql security definer set search_path = '' as $$
declare
    v_user uuid := auth.uid();
    v_start timestamptz := coalesce(p_streak_start, now());
begin
    if v_user is null then
        raise exception 'Not signed in' using errcode = '28000';
    end if;
    if exists (select 1 from public.profiles p where p.id = v_user and p.onboarded_at is not null) then
        raise exception 'Onboarding already completed' using errcode = '22023';
    end if;
    if v_start > now() or v_start < now() - interval '365 days' then
        raise exception 'Streak start must be within the last 365 days' using errcode = '22023';
    end if;

    update public.profiles p
    set username = lower(p_username),
        timezone = p_timezone,
        streak_start = v_start,
        onboarded_at = now()
    where p.id = v_user;

    insert into public.events (user_id, type, created_at, local_date)
    values (v_user, 'streak_started', now(), current_date);

    -- Set the starting rank quietly: no rank-up posts for a backdated streak.
    perform public.compute_tiers(v_user, false);
end;
$$;

-- Friends' posts, filtered by each owner's privacy settings, keyset-paginated.
-- Reaction counts and the viewer's own reaction come back in the same query.
create function public.feed(
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
    select
        e.id, e.user_id, p.username, p.display_name, p.avatar_url,
        e.type, e.payload, e.local_date, e.created_at,
        case when ps.show_tier = 'friends' then p.tier end,
        case when ps.show_tier = 'friends' then p.tier_verified else false end,
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
    left join public.reactions mine on mine.event_id = e.id and mine.user_id = auth.uid()
    where e.user_id in (select public._friend_ids(auth.uid()))
      and public._can_view_event(auth.uid(), e.user_id, e.type)
      and (p_cursor_created_at is null or (e.created_at, e.id) < (p_cursor_created_at, p_cursor_id))
    order by e.created_at desc, e.id desc
    limit least(greatest(coalesce(p_limit, 20), 1), 50);
$$;

-- "Your bros" strip: friends with rank, streak and verified flag (per their privacy),
-- ordered by rank then streak.
create function public.friends_ranks() returns table (
    user_id uuid,
    username text,
    display_name text,
    avatar_url text,
    tier public.tier,
    tier_verified boolean,
    streak_days integer
)
language sql stable security definer set search_path = '' as $$
    select
        p.id, p.username, p.display_name, p.avatar_url,
        case when ps.show_tier = 'friends' then p.tier end,
        case when ps.show_tier = 'friends' then p.tier_verified else false end,
        case when ps.show_streak = 'friends' then
            greatest(0, (now() at time zone p.timezone)::date - (p.streak_start at time zone p.timezone)::date)
        end
    from public.profiles p
    join public.privacy_settings ps on ps.user_id = p.id
    join public.tier_config tc on tc.tier = p.tier
    where p.id in (select public._friend_ids(auth.uid()))
    order by
        case when ps.show_tier = 'friends' then tc.ordinal end desc nulls last,
        case when ps.show_streak = 'friends' then p.streak_start end asc nulls last;
$$;

-- A single profile: your own, or a friend's with private fields hidden.
create function public.get_profile(p_user uuid) returns table (
    user_id uuid,
    username text,
    display_name text,
    avatar_url text,
    tier public.tier,
    streak_tier public.tier,
    tier_verified boolean,
    streak_days integer,
    longest_streak_days integer,
    is_self boolean
)
language sql stable security definer set search_path = '' as $$
    select
        p.id, p.username, p.display_name, p.avatar_url,
        case when v.self or ps.show_tier = 'friends' then p.tier end,
        case when v.self or ps.show_tier = 'friends' then p.streak_tier end,
        case when v.self or ps.show_tier = 'friends' then p.tier_verified else false end,
        case when v.self or ps.show_streak = 'friends' then
            greatest(0, (now() at time zone p.timezone)::date - (p.streak_start at time zone p.timezone)::date)
        end,
        case when v.self or ps.show_streak = 'friends' then p.longest_streak_days end,
        v.self
    from public.profiles p
    join public.privacy_settings ps on ps.user_id = p.id
    cross join lateral (select p.id = auth.uid() as self) v
    where p.id = p_user
      and (v.self or p.id in (select public._friend_ids(auth.uid())));
$$;

-- Username prefix search for adding friends. Returns identity fields only.
create function public.search_users(p_query text) returns table (
    user_id uuid,
    username text,
    display_name text,
    avatar_url text
)
language sql stable security definer set search_path = '' as $$
    select p.id, p.username, p.display_name, p.avatar_url
    from public.profiles p
    where char_length(p_query) >= 2
      and p.onboarded_at is not null
      and p.username like
          replace(replace(replace(lower(p_query), '\', '\\'), '%', '\%'), '_', '\_') || '%'
      and p.id <> auth.uid()
      and not public._is_blocked_between(auth.uid(), p.id)
    order by p.username
    limit 20;
$$;

-- Who reacted to a post the caller is allowed to see.
create function public.event_reactors(p_event uuid) returns table (
    user_id uuid,
    username text,
    display_name text,
    avatar_url text,
    type public.reaction_type,
    reacted_at timestamptz
)
language sql stable security definer set search_path = '' as $$
    select p.id, p.username, p.display_name, p.avatar_url, r.type, r.updated_at
    from public.reactions r
    join public.events e on e.id = r.event_id
    join public.profiles p on p.id = r.user_id
    where r.event_id = p_event
      and public._can_view_event(auth.uid(), e.user_id, e.type)
      and not public._is_blocked_between(auth.uid(), r.user_id)
    order by r.updated_at desc;
$$;

-- ---------------------------------------------------------------------------
-- Privileges: start from nothing, then grant exactly what clients need.
-- (service_role keeps full access for Edge Functions.)
-- ---------------------------------------------------------------------------
revoke all on all tables in schema public from anon, authenticated;
revoke execute on all functions in schema public from public, anon, authenticated;

grant select on public.tier_config, public.tier_settings to authenticated;

grant select on public.profiles to authenticated;
-- Not updatable by clients: streak_start, tier, streak_tier, tier_verified, longest_streak_days, onboarded_at.
grant update (username, display_name, avatar_url, timezone, self_report_enabled) on public.profiles to authenticated;

grant select on public.privacy_settings to authenticated;
grant update (show_streak, show_relapses, show_tier, allow_haha) on public.privacy_settings to authenticated;

grant select, insert, delete on public.friendships to authenticated;
grant update (status) on public.friendships to authenticated;

grant select, insert, delete on public.blocks to authenticated;

grant select, insert on public.events to authenticated;

grant select, insert, delete on public.reactions to authenticated;
grant update (type) on public.reactions to authenticated;

grant select, insert on public.flagged_days to authenticated;
grant select, insert on public.detection_heartbeats to authenticated;
grant select, insert, update, delete on public.device_tokens to authenticated;
grant insert on public.reports to authenticated;

grant execute on function
    public.is_blocked_with(uuid),
    public.my_local_today(),
    public.my_self_report_enabled(),
    public.can_react(uuid, public.reaction_type),
    public.complete_onboarding(text, text, timestamptz),
    public.feed(timestamptz, uuid, integer),
    public.friends_ranks(),
    public.get_profile(uuid),
    public.search_users(text),
    public.event_reactors(uuid)
to authenticated;

-- ---------------------------------------------------------------------------
-- Storage: avatars. Signed-in users can read; each user writes only inside
-- a folder named after their own user id.
-- ---------------------------------------------------------------------------
insert into storage.buckets (id, name, public)
values ('avatars', 'avatars', false)
on conflict (id) do nothing;

create policy avatars_read on storage.objects for select to authenticated
    using (bucket_id = 'avatars');
create policy avatars_insert_own on storage.objects for insert to authenticated
    with check (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text);
create policy avatars_update_own on storage.objects for update to authenticated
    using (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text);
create policy avatars_delete_own on storage.objects for delete to authenticated
    using (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text);
