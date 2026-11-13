-- Tier system tests (pgTAP): every rank threshold, timezone boundaries, the rolling
-- window's edges, the flagged-day penalty and its floor, relapse, tier events, and
-- Verified heartbeat gaps. Time is controlled through compute_tiers' p_now parameter.
begin;

create extension if not exists pgtap with schema extensions;
select * from no_plan();

create schema if not exists tests;

-- The insert triggers recompute at the real "now"; these tests drive the clock themselves.
alter table public.flagged_days disable trigger flagged_days_recompute;
alter table public.detection_heartbeats disable trigger detection_heartbeats_recompute;

\set u '''aaaaaaaa-0000-0000-0000-000000000001'''

insert into auth.users (id, instance_id, aud, role, email)
values (:u, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'tiers@example.com');
update public.profiles set username = 'tiers', onboarded_at = now() where id = :u;

-- Puts the user in a known state and returns (streak rank, effective rank) at p_now.
create function tests.ranks(
    p_timezone text,
    p_streak_start timestamptz,
    p_now timestamptz,
    p_flagged date[] default '{}'
) returns text language plpgsql as $$
declare
    v_user constant uuid := 'aaaaaaaa-0000-0000-0000-000000000001';
    v_result text;
begin
    delete from public.flagged_days where user_id = v_user;
    insert into public.flagged_days (user_id, local_date, source)
    select v_user, d, 'self' from unnest(p_flagged) d;
    update public.profiles
    set timezone = p_timezone, streak_start = p_streak_start, tier = 'clown', streak_tier = 'clown'
    where id = v_user;
    perform public.compute_tiers(v_user, false, p_now);
    select streak_tier || '/' || tier into v_result from public.profiles where id = v_user;
    return v_result;
end;
$$;

-- Rank after p_days whole days, in UTC, with no flagged days.
create function tests.rank_after(p_days integer) returns text language sql as $$
    select tests.ranks('UTC', '2026-06-15 12:00+00'::timestamptz - make_interval(days => p_days), '2026-06-15 12:00+00');
$$;

-- ---------------------------------------------------------------------------
-- Rank thresholds at every boundary: 0 / 1 / 3 / 7 / 15 / 30 / 45 / 60 / 120
-- ---------------------------------------------------------------------------
select is(tests.rank_after(0), 'clown/clown', 'day 0 is Clown');
select is(tests.rank_after(1), 'noob/noob', 'day 1 is Noob');
select is(tests.rank_after(2), 'noob/noob', 'day 2 is still Noob');
select is(tests.rank_after(3), 'novice/novice', 'day 3 is Novice');
select is(tests.rank_after(6), 'novice/novice', 'day 6 is still Novice');
select is(tests.rank_after(7), 'average/average', 'day 7 is Average');
select is(tests.rank_after(14), 'average/average', 'day 14 is still Average');
select is(tests.rank_after(15), 'advanced/advanced', 'day 15 is Advanced');
select is(tests.rank_after(29), 'advanced/advanced', 'day 29 is still Advanced');
select is(tests.rank_after(30), 'sigma/sigma', 'day 30 is Sigma');
select is(tests.rank_after(44), 'sigma/sigma', 'day 44 is still Sigma');
select is(tests.rank_after(45), 'chad/chad', 'day 45 is Chad');
select is(tests.rank_after(59), 'chad/chad', 'day 59 is still Chad');
select is(tests.rank_after(60), 'absolute_chad/absolute_chad', 'day 60 is Absolute Chad');
select is(tests.rank_after(119), 'absolute_chad/absolute_chad', 'day 119 is still Absolute Chad');
select is(tests.rank_after(120), 'giga_chad/giga_chad', 'day 120 is Giga Chad');
select is(tests.rank_after(1000), 'giga_chad/giga_chad', 'Giga Chad is the top');

-- ---------------------------------------------------------------------------
-- Local midnight and timezones: days are local calendar days, not 24-hour periods
-- ---------------------------------------------------------------------------
-- 23:30 -> 00:30 in Dhaka (UTC+6) is one hour but a new local day.
select is(
    tests.ranks('Asia/Dhaka', '2026-06-14 17:30+00', '2026-06-14 18:30+00'), 'noob/noob',
    'UTC+6: crossing local midnight counts as day 1 after one hour'
);
-- The same two instants are the same local day in New York (UTC-4) and in UTC.
select is(
    tests.ranks('America/New_York', '2026-06-14 17:30+00', '2026-06-14 18:30+00'), 'clown/clown',
    'UTC-4: the same instants are still day 0'
);
select is(
    tests.ranks('UTC', '2026-06-14 17:30+00', '2026-06-14 18:30+00'), 'clown/clown',
    'UTC: the same instants are still day 0'
);
-- 23:30 -> 00:30 in New York is 03:30 -> 04:30 UTC: a new day there, not in UTC.
select is(
    tests.ranks('America/New_York', '2026-06-15 03:30+00', '2026-06-15 04:30+00'), 'noob/noob',
    'UTC-4: crossing local midnight counts as day 1'
);
select is(
    tests.ranks('UTC', '2026-06-15 03:30+00', '2026-06-15 04:30+00'), 'clown/clown',
    'UTC: 03:30 -> 04:30 is the same day'
);
-- 23 hours 59 minutes inside one local day is still day 0; one minute later is day 1.
select is(
    tests.ranks('Asia/Dhaka', '2026-06-14 18:00+00', '2026-06-15 17:59+00'), 'clown/clown',
    'UTC+6: 00:00 -> 23:59 local is day 0'
);
select is(
    tests.ranks('Asia/Dhaka', '2026-06-14 18:00+00', '2026-06-15 18:00+00'), 'noob/noob',
    'UTC+6: 00:00 -> next 00:00 local is day 1'
);
-- A timezone with a 45-minute offset (Nepal, UTC+5:45).
select is(
    tests.ranks('Asia/Kathmandu', '2026-06-14 18:10+00', '2026-06-14 18:20+00'), 'noob/noob',
    'UTC+5:45: midnight falls at 18:15 UTC'
);
-- The threshold day itself flips at local midnight: 29 -> 30 days.
select is(
    tests.ranks('Asia/Dhaka', '2026-05-16 06:00+00', '2026-06-14 17:59+00'), 'advanced/advanced',
    'one minute before local midnight on day 29'
);
select is(
    tests.ranks('Asia/Dhaka', '2026-05-16 06:00+00', '2026-06-14 18:00+00'), 'sigma/sigma',
    'at local midnight day 30 begins: Sigma'
);

-- ---------------------------------------------------------------------------
-- Flagged days: penalty, the rolling 7-day window's edges, the floor at Clown
-- ---------------------------------------------------------------------------
-- 50-day streak (Chad), "today" is 2026-06-15 in UTC.
select is(
    tests.ranks('UTC', '2026-04-26 12:00+00', '2026-06-15 12:00+00', array['2026-06-15', '2026-06-14']::date[]),
    'chad/advanced', 'two flagged days this week: Chad by streak, shown as Advanced'
);
select is(
    tests.ranks('UTC', '2026-04-26 12:00+00', '2026-06-15 12:00+00', array['2026-06-09']::date[]),
    'chad/sigma', 'a flag 6 days ago is still inside the window'
);
select is(
    tests.ranks('UTC', '2026-04-26 12:00+00', '2026-06-15 12:00+00', array['2026-06-08']::date[]),
    'chad/chad', 'a flag 7 days ago has left the window'
);
select is(
    tests.ranks('UTC', '2026-04-26 12:00+00', '2026-06-15 23:59+00', array['2026-06-09']::date[]),
    'chad/sigma', 'the flag counts until the last minute of its 7th day'
);
select is(
    tests.ranks('UTC', '2026-04-26 12:00+00', '2026-06-16 00:00+00', array['2026-06-09']::date[]),
    'chad/chad', 'at the next local midnight the flag ages out and the rank climbs back'
);
-- The window follows the user's local date, not UTC's.
select is(
    tests.ranks('Asia/Dhaka', '2026-04-26 12:00+00', '2026-06-15 18:30+00', array['2026-06-09']::date[]),
    'chad/chad', 'UTC+6: it is already the 16th locally, so a flag on the 9th is out'
);
select is(
    tests.ranks('America/New_York', '2026-04-26 12:00+00', '2026-06-16 03:00+00', array['2026-06-09']::date[]),
    'chad/sigma', 'UTC-4: it is still the 15th locally, so a flag on the 9th is in'
);
select is(
    tests.ranks('UTC', '2026-04-26 12:00+00', '2026-06-15 12:00+00', array['2026-06-16']::date[]),
    'chad/chad', 'a flag dated after today does not count'
);
-- Floor: the rank never goes below Clown.
select is(
    tests.ranks(
        'UTC', '2026-06-12 12:00+00', '2026-06-15 12:00+00',
        array['2026-06-09', '2026-06-10', '2026-06-11', '2026-06-12', '2026-06-13', '2026-06-14', '2026-06-15']::date[]
    ),
    'novice/clown', 'seven flagged days on a 3-day streak floor at Clown'
);
select is(
    tests.ranks(
        'UTC', '2026-02-15 12:00+00', '2026-06-15 12:00+00',
        array['2026-06-09', '2026-06-10', '2026-06-11', '2026-06-12', '2026-06-13', '2026-06-14', '2026-06-15']::date[]
    ),
    'giga_chad/noob', 'the worst week takes Giga Chad down seven steps, to Noob'
);

-- The penalty per flagged day comes from tier_settings.
update public.tier_settings set penalty_per_flagged_day = 2;
select is(
    tests.ranks('UTC', '2026-04-26 12:00+00', '2026-06-15 12:00+00', array['2026-06-15']::date[]),
    'chad/advanced', 'penalty of 2 per flagged day is read from tier_settings'
);
update public.tier_settings set penalty_per_flagged_day = 1;

-- ---------------------------------------------------------------------------
-- Tier events
-- ---------------------------------------------------------------------------
select tests.ranks('UTC', '2026-05-17 12:00+00', '2026-06-15 12:00+00');   -- day 29, Advanced
delete from public.events where user_id = :u;

select public.compute_tiers(:u, true, '2026-06-16 00:05+00');              -- day 30
select is(
    (select payload ->> 'rank' from public.events where user_id = :u and type = 'milestone'),
    'sigma', 'reaching a threshold posts a milestone with the new rank'
);
select is(
    (select (payload ->> 'streak_days')::int from public.events where user_id = :u and type = 'milestone'),
    30, 'the milestone carries the streak length'
);
select public.compute_tiers(:u, true, '2026-06-16 09:00+00');
select is((select count(*) from public.events where user_id = :u), 1::bigint, 'recomputing with no change posts nothing');

insert into public.flagged_days (user_id, local_date, source) values (:u, '2026-06-16', 'auto');
select public.compute_tiers(:u, true, '2026-06-16 10:00+00');
select is(
    (select payload from public.events where user_id = :u and type = 'tier_down'),
    '{"from": "sigma", "to": "advanced"}'::jsonb, 'a flagged day posts a tier_down'
);
select is((select streak_tier from public.profiles where id = :u), 'sigma'::public.tier, 'the streak rank is untouched by the flag');

select public.compute_tiers(:u, true, '2026-06-23 00:05+00');              -- the flag ages out
select is(
    (select payload from public.events where user_id = :u and type = 'tier_up'),
    '{"from": "advanced", "to": "sigma"}'::jsonb, 'when the flag ages out the rank climbs back with a tier_up'
);

-- Relapse: back to Clown, the longest streak is kept, no extra tier_down post.
delete from public.events where user_id = :u;
insert into public.events (user_id, type, local_date) values (:u, 'relapse', current_date);
select is((select tier from public.profiles where id = :u), 'clown'::public.tier, 'a relapse resets the rank to Clown');
select is((select streak_tier from public.profiles where id = :u), 'clown'::public.tier, 'and the streak rank too');
select cmp_ok((select longest_streak_days from public.profiles where id = :u), '>=', 37, 'the longest streak is kept');
select is(
    (select count(*) from public.events where user_id = :u and type = 'tier_down'), 0::bigint,
    'the relapse post stands alone: no extra tier_down'
);
select is(
    (select count(*) from public.events where user_id = :u and type = 'streak_started'), 1::bigint,
    'a relapse starts a fresh streak'
);

-- ---------------------------------------------------------------------------
-- Verified: a heartbeat for every day of the window (ending today or yesterday)
-- ---------------------------------------------------------------------------
create function tests.verified(p_days date[], p_now timestamptz) returns boolean language plpgsql as $$
declare
    v_user constant uuid := 'aaaaaaaa-0000-0000-0000-000000000001';
begin
    delete from public.detection_heartbeats where user_id = v_user;
    insert into public.detection_heartbeats (user_id, local_date) select v_user, d from unnest(p_days) d;
    update public.profiles set timezone = 'UTC' where id = v_user;
    perform public.compute_tiers(v_user, false, p_now);
    return (select tier_verified from public.profiles where id = v_user);
end;
$$;

select is(
    tests.verified(
        array['2026-06-09', '2026-06-10', '2026-06-11', '2026-06-12', '2026-06-13', '2026-06-14', '2026-06-15']::date[],
        '2026-06-15 12:00+00'
    ), true, 'seven days in a row ending today: Verified'
);
select is(
    tests.verified(
        array['2026-06-08', '2026-06-09', '2026-06-10', '2026-06-11', '2026-06-12', '2026-06-13', '2026-06-14']::date[],
        '2026-06-15 00:05+00'
    ), true, 'just after midnight, before today''s heartbeat: still Verified'
);
select is(
    tests.verified(
        array['2026-06-09', '2026-06-10', '2026-06-11', '2026-06-13', '2026-06-14', '2026-06-15']::date[],
        '2026-06-15 12:00+00'
    ), false, 'a one-day gap in the middle: not Verified'
);
select is(
    tests.verified(
        array['2026-06-10', '2026-06-11', '2026-06-12', '2026-06-13', '2026-06-14', '2026-06-15']::date[],
        '2026-06-15 12:00+00'
    ), false, 'only six days so far: not Verified yet'
);
select is(
    tests.verified(
        array['2026-06-07', '2026-06-08', '2026-06-09', '2026-06-10', '2026-06-11', '2026-06-12', '2026-06-13']::date[],
        '2026-06-15 12:00+00'
    ), false, 'detection off for two days: Verified is lost'
);
select is(tests.verified('{}'::date[], '2026-06-15 12:00+00'), false, 'no heartbeats: not Verified');

-- The config table matches the documented ladder (the Kotlin side has its own test).
select results_eq(
    $$select tier::text, min_streak_days from public.tier_config order by ordinal$$,
    $$values ('clown', 0), ('noob', 1), ('novice', 3), ('average', 7), ('advanced', 15),
             ('sigma', 30), ('chad', 45), ('absolute_chad', 60), ('giga_chad', 120)$$,
    'tier_config holds the nine ranks and their thresholds'
);

select * from finish();
rollback;
