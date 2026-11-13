-- BroBehave — Phase 4: storage for the signed blocklist used by automatic detection.
--
-- The bucket is private and has no client policies: only the `blocklist` Edge Function
-- (service role) reads it, and only maintainers (service role / dashboard) write to it.
-- flagged_days (source 'auto') and detection_heartbeats already exist from the initial schema.

insert into storage.buckets (id, name, public)
values ('blocklist', 'blocklist', false)
on conflict (id) do nothing;
