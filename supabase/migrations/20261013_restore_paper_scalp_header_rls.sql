-- `request.headers` is supplied by PostgREST at request execution. The
-- init-plan form incorrectly observed it as empty on this project, so retain
-- the direct expression. This table holds one row per installation only.
drop policy "paper_scalp_sync_select_own_device" on public.paper_scalp_sync;
drop policy "paper_scalp_sync_insert_own_device" on public.paper_scalp_sync;
drop policy "paper_scalp_sync_update_own_device" on public.paper_scalp_sync;

create policy "paper_scalp_sync_select_own_device"
on public.paper_scalp_sync for select to anon
using (
  device_id::text = coalesce(current_setting('request.headers', true)::jsonb ->> 'x-paper-sync-device', '')
  and secret_hash = coalesce(current_setting('request.headers', true)::jsonb ->> 'x-paper-sync-secret-hash', '')
);

create policy "paper_scalp_sync_insert_own_device"
on public.paper_scalp_sync for insert to anon
with check (
  device_id::text = coalesce(current_setting('request.headers', true)::jsonb ->> 'x-paper-sync-device', '')
  and secret_hash = coalesce(current_setting('request.headers', true)::jsonb ->> 'x-paper-sync-secret-hash', '')
);

create policy "paper_scalp_sync_update_own_device"
on public.paper_scalp_sync for update to anon
using (
  device_id::text = coalesce(current_setting('request.headers', true)::jsonb ->> 'x-paper-sync-device', '')
  and secret_hash = coalesce(current_setting('request.headers', true)::jsonb ->> 'x-paper-sync-secret-hash', '')
)
with check (
  device_id::text = coalesce(current_setting('request.headers', true)::jsonb ->> 'x-paper-sync-device', '')
  and secret_hash = coalesce(current_setting('request.headers', true)::jsonb ->> 'x-paper-sync-secret-hash', '')
);
