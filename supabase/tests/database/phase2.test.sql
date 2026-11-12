-- Phase 2 database tests (pgTAP): onboarding, friendships, posts, notification outbox.
begin;

create extension if not exists pgtap with schema extensions;
select * from no_plan();

create schema if not exists tests;
grant usage on schema tests to authenticated;

create function tests.login(p_user uuid) returns void language plpgsql as $$
begin
    perform set_config('request.jwt.claims', json_build_object('sub', p_user, 'role', 'authenticated')::text, true);
    perform set_config('role', 'authenticated', true);
end;
$$;
create function tests.logout() returns void language plpgsql as $$
begin
    perform set_config('role', 'postgres', true);
    perform set_config('request.jwt.claims', '', true);
end;
$$;
grant execute on function tests.login(uuid), tests.logout() to authenticated;

create function tests.make_auth_user(p_id uuid, p_name text) returns void language sql as $$
    insert into auth.users (id, instance_id, aud, role, email)
    values (p_id, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', p_name || '@example.com');
$$;

\set alice '''a0000000-0000-0000-0000-000000000001'''
\set bob   '''b0000000-0000-0000-0000-000000000002'''
\set carol '''c0000000-0000-0000-0000-000000000003'''

select tests.make_auth_user(:alice, 'alice');
select tests.make_auth_user(:bob, 'bob');
select tests.make_auth_user(:carol, 'carol');

select is(
    (select count(*) from public.notification_settings where user_id in (:alice, :bob, :carol)),
    3::bigint, 'signup creates notification settings'
);

-- ---------------------------------------------------------------------------
-- Onboarding
-- ---------------------------------------------------------------------------
select tests.login(:alice);

select throws_ok(
    $$select public.complete_onboarding('alice', 'Asia/Dhaka', false)$$,
    '22023', null, 'onboarding requires the 18+ confirmation'
);
select throws_ok(
    $$select public.complete_onboarding('alice', 'Mars/Olympus', true)$$,
    '22023', null, 'onboarding rejects an unknown timezone'
);
select throws_ok(
    $$select public.complete_onboarding('alice', 'Asia/Dhaka', true, now() - interval '400 days')$$,
    '22023', null, 'streak start cannot be more than 365 days back'
);
select throws_ok(
    $$select public.complete_onboarding('Al', 'Asia/Dhaka', true)$$,
    '23514', null, 'usernames must be 3-20 characters'
);
select lives_ok(
    $$select public.complete_onboarding('Alice', 'Asia/Dhaka', true, now() - interval '31 days', 'Alice A', true, 'friends', 'private', 'friends')$$,
    'onboarding succeeds with valid input'
);
select is((select username from public.profiles where id = :alice), 'alice', 'username is stored lowercase');
select is((select tier from public.profiles where id = :alice), 'sigma'::public.tier, 'a 31-day backdated streak starts at Sigma');
select is((select self_report_enabled from public.profiles where id = :alice), true, 'slip-tracking choice is stored');
select is((select show_relapses from public.privacy_settings where user_id = :alice), 'private'::public.visibility, 'privacy defaults are stored');
select isnt((select age_confirmed_at from public.profiles where id = :alice), null, '18+ confirmation is recorded');
select is(
    (select count(*) from public.events where user_id = :alice and type in ('milestone', 'tier_up')), 0::bigint,
    'a backdated streak creates no rank-up posts'
);
select throws_ok(
    $$select public.complete_onboarding('alice2', 'Asia/Dhaka', true)$$,
    '22023', null, 'onboarding cannot be repeated'
);

select tests.login(:bob);
select is(public.username_available('ALICE'), false, 'taken usernames are reported (case-insensitive)');
select is(public.username_available('bobby'), true, 'free usernames are reported');
select is(public.username_available('no'), false, 'invalid usernames are not available');
select throws_ok(
    $$select public.complete_onboarding('alice', 'UTC', true)$$,
    '23505', null, 'a duplicate username is rejected'
);
select lives_ok($$select public.complete_onboarding('bob', 'UTC', true)$$, 'bob onboards');

select tests.login(:carol);
select lives_ok($$select public.complete_onboarding('carol', 'America/New_York', true)$$, 'carol onboards');

-- ---------------------------------------------------------------------------
-- Friend requests
-- ---------------------------------------------------------------------------
select tests.login(:bob);
select lives_ok(
    $$insert into public.friendships (requester_id, addressee_id) values ('b0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001')$$,
    'bob sends alice a friend request'
);
select throws_ok(
    $$insert into public.friendships (requester_id, addressee_id, status) values ('b0000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000003', 'accepted')$$,
    '42501', null, 'a request cannot be created already accepted'
);
select is((select relation from public.my_friendships()), 'outgoing', 'bob sees an outgoing request');
-- RLS hides the row from the requester's UPDATE, so this matches nothing.
update public.friendships set status = 'accepted' where requester_id = :bob;
select is((select relation from public.my_friendships()), 'outgoing', 'the requester cannot accept their own request');

select tests.login(:alice);
select is((select relation from public.my_friendships()), 'incoming', 'alice sees an incoming request');
select is((select username from public.my_friendships()), 'bob', 'the request carries the other person''s identity');
select throws_ok(
    $$insert into public.friendships (requester_id, addressee_id) values ('a0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002')$$,
    '23505', null, 'the same pair cannot have a second friendship in the other direction'
);
update public.friendships set status = 'accepted' where addressee_id = :alice;
select is((select relation from public.my_friendships()), 'friend', 'after accepting they are friends');

select tests.logout();
select is(
    (select count(*) from public.notification_outbox where recipient_id = :alice and kind = 'friend_request' and actor_id = :bob),
    1::bigint, 'a friend request queues one push for the addressee'
);

-- ---------------------------------------------------------------------------
-- Posts: user_posts for own and a friend's profile
-- ---------------------------------------------------------------------------
-- A server-side rank-up for alice (as the hourly job would produce).
insert into public.events (id, user_id, type, payload, local_date)
values ('e0000000-0000-0000-0000-00000000000a', :alice, 'milestone', '{"streak_days": 45, "rank": "chad"}', current_date);

select is(
    (select count(*) from public.notification_outbox where recipient_id = :bob and kind = 'milestone'),
    1::bigint, 'a friend''s milestone queues a push for each friend'
);
select is(
    (select count(*) from public.notification_outbox where recipient_id = :carol),
    0::bigint, 'non-friends get no pushes'
);

select tests.login(:alice);
select is((select count(*) from public.user_posts(:alice)), 2::bigint, 'own posts: streak started + milestone');
select is((select my_reaction from public.user_posts(:alice) limit 1), null::public.reaction_type, 'own posts carry no own reaction');

select tests.login(:bob);
select is((select count(*) from public.user_posts(:alice)), 2::bigint, 'a friend sees the same posts on alice''s profile');
select lives_ok(
    $$insert into public.reactions (event_id, user_id, type) values ('e0000000-0000-0000-0000-00000000000a', 'b0000000-0000-0000-0000-000000000002', 'haha')$$,
    'bob reacts HAHA'
);
select lives_ok(
    $$update public.reactions set type = 'respect' where event_id = 'e0000000-0000-0000-0000-00000000000a' and user_id = 'b0000000-0000-0000-0000-000000000002'$$,
    'bob changes his reaction'
);
select is(
    (select reaction_counts from public.user_posts(:alice) where id = 'e0000000-0000-0000-0000-00000000000a'),
    '{"respect": 1}'::jsonb, 'one reaction per user per post: changing it replaces the old one'
);
select is(
    (select count(*) from public.event_reactors('e0000000-0000-0000-0000-00000000000a')), 1::bigint,
    'reactors are listed for a visible post'
);

select tests.login(:carol);
select is((select count(*) from public.user_posts(:alice)), 0::bigint, 'a stranger sees none of alice''s posts');
select is((select count(*) from public.my_friendships()), 0::bigint, 'a stranger sees no friendships');

select tests.logout();
select is(
    (select count(*) from public.notification_outbox where recipient_id = :alice and kind = 'reaction'),
    1::bigint, 'reactions on one post queue a single push inside the 15-minute window'
);

-- Per-type toggles are honoured when queueing.
update public.notification_settings set milestones = false where user_id = :bob;
insert into public.events (user_id, type, payload, local_date)
values (:alice, 'milestone', '{"streak_days": 60, "rank": "absolute_chad"}', current_date);
select is(
    (select count(*) from public.notification_outbox where recipient_id = :bob and kind = 'milestone'),
    1::bigint, 'no push is queued when the recipient switched that type off'
);

-- Clients cannot touch the outbox or other people's settings.
select tests.login(:bob);
select throws_ok($$select count(*) from public.notification_outbox$$, '42501', null, 'the outbox is not readable by clients');
select is((select count(*) from public.notification_settings), 1::bigint, 'only own notification settings are readable');
select lives_ok(
    $$update public.notification_settings set reactions = false where user_id = 'b0000000-0000-0000-0000-000000000002'$$,
    'own notification settings are updatable'
);

select * from finish();
rollback;
