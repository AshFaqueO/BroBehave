-- BroBehave — Phase 3: final tier rules.
--
-- Change from the initial schema: Verified no longer drops at local midnight. It is true
-- when a detection heartbeat exists for every day of the 7-day window ending today OR of
-- the window ending yesterday (today's heartbeat only arrives once the phone has been on
-- for a moment). Everything else in compute_tiers is unchanged.

create or replace function public.compute_tiers(
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

    -- "Days" are local calendar days in the user's timezone.
    v_today := (p_now at time zone v_profile.timezone)::date;
    v_window_start := v_today - (v_settings.window_days - 1);
    v_streak_days := greatest(0, v_today - (v_profile.streak_start at time zone v_profile.timezone)::date);

    -- Rank earned by the streak alone.
    select tc.tier, tc.ordinal into v_streak_tier, v_streak_ord
    from public.tier_config tc
    where tc.min_streak_days <= v_streak_days
    order by tc.ordinal desc
    limit 1;

    -- Effective rank = streak rank - (flagged days in the window * penalty), floored at Clown.
    select count(*) into v_flagged
    from public.flagged_days fd
    where fd.user_id = p_user and fd.local_date between v_window_start and v_today;

    v_new_ord := greatest(0, v_streak_ord - v_flagged * v_settings.penalty_per_flagged_day);
    select tc.tier into v_new_tier from public.tier_config tc where tc.ordinal = v_new_ord;

    -- Verified: a full window of heartbeats ending today, or ending yesterday.
    select
        (select count(*) from public.detection_heartbeats h
         where h.user_id = p_user and h.local_date between v_window_start and v_today) = v_settings.window_days
        or
        (select count(*) from public.detection_heartbeats h
         where h.user_id = p_user and h.local_date between v_window_start - 1 and v_today - 1) = v_settings.window_days
    into v_verified;

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
            insert into public.events (user_id, type, payload, created_at, local_date)
            values (
                p_user, 'milestone',
                jsonb_build_object('streak_days', v_streak_days, 'rank', v_streak_tier, 'tier', v_new_tier),
                p_now, v_today
            );
        elsif v_new_ord > v_old_ord then
            -- A flagged day left the window: the rank climbs back.
            insert into public.events (user_id, type, payload, created_at, local_date)
            values (p_user, 'tier_up', jsonb_build_object('from', v_profile.tier, 'to', v_new_tier), p_now, v_today);
        elsif v_new_ord < v_old_ord then
            insert into public.events (user_id, type, payload, created_at, local_date)
            values (p_user, 'tier_down', jsonb_build_object('from', v_profile.tier, 'to', v_new_tier), p_now, v_today);
        end if;
    end if;
end;
$$;

revoke execute on function public.compute_tiers(uuid, boolean, timestamptz) from public, anon, authenticated;
