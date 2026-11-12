-- Local development seed (runs on `supabase db reset`). LOCAL ONLY — never run against production.
-- Three bros who are all friends. Sign in with <name>@example.com / password123.

do $$
declare
    v_users constant jsonb := '[
        {"id": "11111111-1111-1111-1111-111111111111", "name": "rafi",  "days": 50, "tz": "Asia/Dhaka"},
        {"id": "22222222-2222-2222-2222-222222222222", "name": "tanim", "days": 8,  "tz": "Asia/Dhaka"},
        {"id": "33333333-3333-3333-3333-333333333333", "name": "sam",   "days": 0,  "tz": "America/New_York"}
    ]';
    u jsonb;
    v_id uuid;
begin
    for u in select * from jsonb_array_elements(v_users) loop
        v_id := (u ->> 'id')::uuid;

        insert into auth.users (
            instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
            raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
            confirmation_token, email_change, email_change_token_new, recovery_token
        ) values (
            '00000000-0000-0000-0000-000000000000', v_id, 'authenticated', 'authenticated',
            (u ->> 'name') || '@example.com',
            extensions.crypt('password123', extensions.gen_salt('bf')), now(),
            '{"provider": "email", "providers": ["email"]}', '{}', now(), now(),
            '', '', '', ''
        );

        insert into auth.identities (
            id, user_id, provider_id, identity_data, provider, last_sign_in_at, created_at, updated_at
        ) values (
            gen_random_uuid(), v_id, v_id::text,
            jsonb_build_object('sub', v_id::text, 'email', (u ->> 'name') || '@example.com'),
            'email', now(), now(), now()
        );

        -- The signup trigger created the profile; finish onboarding by hand.
        update public.profiles
        set username = u ->> 'name',
            display_name = initcap(u ->> 'name'),
            timezone = u ->> 'tz',
            streak_start = now() - make_interval(days => (u ->> 'days')::integer),
            onboarded_at = now()
        where id = v_id;

        insert into public.events (user_id, type, local_date) values (v_id, 'streak_started', current_date);
        perform public.compute_tiers(v_id, false);
        update public.profiles set age_confirmed_at = now() where id = v_id;
    end loop;

    insert into public.friendships (requester_id, addressee_id, status) values
        ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 'accepted'),
        ('11111111-1111-1111-1111-111111111111', '33333333-3333-3333-3333-333333333333', 'accepted'),
        ('22222222-2222-2222-2222-222222222222', '33333333-3333-3333-3333-333333333333', 'accepted');
end;
$$;
