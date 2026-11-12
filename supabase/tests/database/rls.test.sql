-- RLS and privacy tests (pgTAP). Run with: npm run db:test
-- Proves: non-friends can't read anything, blocked users see nothing, private fields
-- stay hidden, and clients can't write what only the server may write.
begin;

create extension if not exists pgtap with schema extensions;
select * from no_plan();

-- ---------------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------------
create schema if not exists tests;
grant usage on schema tests to authenticated;

-- Act as a signed-in user (role + JWT claims, local to this transaction).
create function tests.login(p_user uuid) returns void language plpgsql as $$
begin
    perform set_config('request.jwt.claims', json_build_object('sub', p_user, 'role', 'authenticated')::text, true);
    perform set_config('role', 'authenticated', true);
end;
$$;

-- Back to the superuser for setup and assertions on raw tables.
create function tests.logout() returns void language plpgsql as $$
begin
    perform set_config('role', 'postgres', true);
    perform set_config('request.jwt.claims', '', true);
end;
$$;
grant execute on function tests.login(uuid), tests.logout() to authenticated;

create function tests.make_user(p_id uuid, p_username text) returns void language plpgsql as $$
begin
    insert into auth.users (id, instance_id, aud, role, email)
    values (p_id, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', p_username || '@example.com');
    update public.profiles
    set username = p_username, display_name = initcap(p_username), onboarded_at = now(),
        streak_start = now() - interval '50 days'
    where id = p_id;
    perform public.compute_tiers(p_id, false);
end;
$$;

-- alice and bob are friends; carol is a stranger; dave gets blocked by alice.
\set alice '''a0000000-0000-0000-0000-000000000001'''
\set bob   '''b0000000-0000-0000-0000-000000000002'''
\set carol '''c0000000-0000-0000-0000-000000000003'''
\set dave  '''d0000000-0000-0000-0000-000000000004'''

select tests.make_user(:alice, 'alice');
select tests.make_user(:bob, 'bob');
select tests.make_user(:carol, 'carol');
select tests.make_user(:dave, 'dave');

insert into public.friendships (requester_id, addressee_id, status) values
    (:alice, :bob, 'accepted'),
    (:alice, :dave, 'accepted');

-- Server-written posts for alice.
insert into public.events (id, user_id, type, payload, local_date) values
    ('e0000000-0000-0000-0000-000000000001', :alice, 'milestone', '{"streak_days": 45, "rank": "chad"}', current_date),
    ('e0000000-0000-0000-0000-000000000002', :alice, 'tier_down', '{"from": "chad", "to": "sigma"}', current_date);

-- ---------------------------------------------------------------------------
-- Setup sanity: the signup trigger and tier computation
-- ---------------------------------------------------------------------------
select is(
    (select count(*) from public.privacy_settings where user_id in (:alice, :bob, :carol, :dave)),
    4::bigint, 'signup trigger creates privacy settings for every user'
);
select is(
    (select tier from public.profiles where id = :alice), 'chad'::public.tier,
    'a 50-day streak is Chad'
);

-- ---------------------------------------------------------------------------
-- A stranger sees nothing
-- ---------------------------------------------------------------------------
select tests.login(:carol);

select is((select count(*) from public.profiles), 1::bigint, 'stranger: only own profile row is readable');
select is((select count(*) from public.events where user_id = :alice), 0::bigint, 'stranger: cannot read alice''s events');
select is((select count(*) from public.privacy_settings where user_id = :alice), 0::bigint, 'stranger: cannot read alice''s privacy settings');
select is((select count(*) from public.friendships), 0::bigint, 'stranger: cannot see other people''s friendships');
select is((select count(*) from public.feed()), 0::bigint, 'stranger: feed is empty');
select is((select count(*) from public.friends_ranks()), 0::bigint, 'stranger: no friends'' ranks');
select is((select count(*) from public.get_profile(:alice)), 0::bigint, 'stranger: get_profile returns nothing for a non-friend');
select is((select count(*) from public.event_reactors('e0000000-0000-0000-0000-000000000001')), 0::bigint, 'stranger: cannot list reactors');
select throws_ok(
    $$insert into public.reactions (event_id, user_id, type) values ('e0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000003', 'fire')$$,
    '42501', null, 'stranger: cannot react to a non-friend''s post'
);
select is(
    (select count(*) from public.search_users('ali')), 1::bigint,
    'stranger: can find a user by username prefix (needed to send a request)'
);

-- ---------------------------------------------------------------------------
-- A friend sees posts, but never raw private data
-- ---------------------------------------------------------------------------
select tests.login(:bob);

select is((select count(*) from public.feed()), 2::bigint, 'friend: sees alice''s two posts in the feed');
select is((select count(*) from public.profiles), 1::bigint, 'friend: still cannot read alice''s profile row directly');
select is((select count(*) from public.events where user_id = :alice), 0::bigint, 'friend: no direct access to the events table');
select is((select tier from public.friends_ranks() where user_id = :alice), 'chad'::public.tier, 'friend: sees alice''s rank');
select is((select streak_days from public.friends_ranks() where user_id = :alice), 50, 'friend: sees alice''s streak');

select lives_ok(
    $$insert into public.reactions (event_id, user_id, type) values ('e0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002', 'fire')$$,
    'friend: can react to a visible post'
);
select throws_ok(
    $$insert into public.reactions (event_id, user_id, type) values ('e0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 'fire')$$,
    '42501', null, 'friend: cannot react on someone else''s behalf'
);
select is(
    (select my_reaction from public.feed() where id = 'e0000000-0000-0000-0000-000000000001'),
    'fire'::public.reaction_type, 'feed returns the viewer''s own reaction'
);
select is(
    (select reaction_counts from public.feed() where id = 'e0000000-0000-0000-0000-000000000001'),
    '{"fire": 1}'::jsonb, 'feed returns reaction counts per type'
);

-- ---------------------------------------------------------------------------
-- The owner: own data, and the things clients must not be able to write
-- ---------------------------------------------------------------------------
select tests.login(:alice);

select throws_ok(
    $$insert into public.reactions (event_id, user_id, type) values ('e0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'strong')$$,
    '42501', null, 'owner: cannot react to their own post'
);
select throws_ok(
    $$update public.profiles set tier = 'giga_chad' where id = 'a0000000-0000-0000-0000-000000000001'$$,
    '42501', null, 'owner: cannot set their own tier'
);
select throws_ok(
    $$update public.profiles set tier_verified = true where id = 'a0000000-0000-0000-0000-000000000001'$$,
    '42501', null, 'owner: cannot mark themselves verified'
);
select throws_ok(
    $$update public.profiles set streak_start = now() - interval '300 days' where id = 'a0000000-0000-0000-0000-000000000001'$$,
    '42501', null, 'owner: cannot rewrite the streak start'
);
select throws_ok(
    $$insert into public.events (user_id, type, local_date) values ('a0000000-0000-0000-0000-000000000001', 'tier_up', current_date)$$,
    '42501', null, 'owner: cannot fabricate a tier_up post'
);
select throws_ok(
    $$insert into public.events (user_id, type, local_date) values ('b0000000-0000-0000-0000-000000000002', 'relapse', current_date)$$,
    '42501', null, 'owner: cannot post a relapse for someone else'
);
select throws_ok(
    $$insert into public.flagged_days (user_id, local_date, source) values ('a0000000-0000-0000-0000-000000000001', public.my_local_today(), 'self')$$,
    '42501', null, 'self-report is rejected while self_report_enabled is false'
);
select throws_ok(
    $$select public.compute_tiers('a0000000-0000-0000-0000-000000000001')$$,
    '42501', null, 'clients cannot call the tier computation directly'
);

update public.profiles set self_report_enabled = true where id = :alice;
select lives_ok(
    $$insert into public.flagged_days (user_id, local_date, source) values ('a0000000-0000-0000-0000-000000000001', public.my_local_today(), 'self')$$,
    'self-report works once self_report_enabled is true'
);
select lives_ok(
    $$insert into public.flagged_days (user_id, local_date, source) values ('a0000000-0000-0000-0000-000000000001', public.my_local_today() - 1, 'auto')$$,
    'an automatic flag for yesterday is accepted'
);
select throws_ok(
    $$delete from public.flagged_days where user_id = 'a0000000-0000-0000-0000-000000000001'$$,
    '42501', null, 'flagged days cannot be deleted by their owner'
);
select throws_ok(
    $$insert into public.flagged_days (user_id, local_date, source) values ('a0000000-0000-0000-0000-000000000001', public.my_local_today() - 30, 'auto')$$,
    '42501', null, 'flagged days cannot be backdated beyond the window'
);

-- Tier: Chad by streak, two flagged days this week -> Advanced.
select is((select streak_tier from public.profiles where id = :alice), 'chad'::public.tier, 'flagged days do not change the streak rank');
select is((select tier from public.profiles where id = :alice), 'advanced'::public.tier, 'two flagged days pull Chad down to Advanced');
select is(
    (select count(*) from public.events where user_id = :alice and type = 'tier_down' and payload ->> 'to' in ('sigma', 'advanced')),
    3::bigint, 'each flagged day produced a tier_down post (plus the seeded one)'
);

-- Privacy switches.
update public.privacy_settings set show_tier = 'private', allow_haha = false where user_id = :alice;

select tests.login(:bob);
select is((select tier from public.friends_ranks() where user_id = :alice), null::public.tier, 'private tier is hidden from friends');
select is((select tier_verified from public.friends_ranks() where user_id = :alice), false, 'private tier also hides the verified flag');
select is(
    (select count(*) from public.feed() where type in ('tier_up', 'tier_down')), 0::bigint,
    'tier posts disappear from the feed when the tier is private'
);
select is((select count(*) from public.feed() where type = 'milestone'), 1::bigint, 'milestone posts stay visible (streak is still shared)');
select is((select count(*) from public.flagged_days), 0::bigint, 'friend: never sees flagged days');
select throws_ok(
    $$update public.reactions set type = 'haha' where event_id = 'e0000000-0000-0000-0000-000000000001' and user_id = 'b0000000-0000-0000-0000-000000000002'$$,
    '42501', null, 'HAHA is rejected when the owner switched it off'
);

-- Relapse: resets the streak, visible per privacy.
select tests.login(:alice);
select lives_ok(
    $$insert into public.events (user_id, type, local_date) values ('a0000000-0000-0000-0000-000000000001', 'relapse', current_date)$$,
    'owner: can post their own relapse'
);
select is((select tier from public.profiles where id = :alice), 'clown'::public.tier, 'a relapse resets the rank to Clown');
select is((select longest_streak_days from public.profiles where id = :alice), 50, 'the longest streak is kept');
update public.privacy_settings set show_relapses = 'private' where user_id = :alice;

select tests.login(:bob);
select is((select count(*) from public.feed() where type = 'relapse'), 0::bigint, 'private relapses are hidden from friends');

-- ---------------------------------------------------------------------------
-- Blocked users see nothing
-- ---------------------------------------------------------------------------
select tests.login(:dave);
select cmp_ok((select count(*) from public.feed()), '>', 0::bigint, 'before the block: dave sees alice''s posts');

select tests.login(:alice);
insert into public.blocks (blocker_id, blocked_id) values (:alice, :dave);

select tests.login(:dave);
select is((select count(*) from public.feed()), 0::bigint, 'blocked: feed is empty');
select is((select count(*) from public.friends_ranks()), 0::bigint, 'blocked: no ranks');
select is((select count(*) from public.get_profile(:alice)), 0::bigint, 'blocked: profile is not readable');
select is((select count(*) from public.search_users('ali')), 0::bigint, 'blocked: cannot find the blocker in search');
select is((select count(*) from public.friendships), 0::bigint, 'blocked: the friendship is gone');
select throws_ok(
    $$insert into public.friendships (requester_id, addressee_id) values ('d0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000001')$$,
    '42501', null, 'blocked: cannot send a new friend request'
);
select is((select count(*) from public.blocks), 0::bigint, 'blocked: cannot see who blocked them');

-- ---------------------------------------------------------------------------
-- Signed-out clients get nothing at all
-- ---------------------------------------------------------------------------
select tests.logout();
set local role anon;
select throws_ok($$select count(*) from public.profiles$$, '42501', null, 'anon: no table access');
select throws_ok($$select * from public.feed()$$, '42501', null, 'anon: no RPC access');
reset role;

select * from finish();
rollback;
