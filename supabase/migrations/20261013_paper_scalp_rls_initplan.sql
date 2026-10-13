-- Align the applied initial migration with Supabase's RLS init-plan guidance.
-- The initial migration in this branch already contains these expressions;
-- this file exists so an existing project receives the performance correction.
drop policy "paper_scalp_sync_select_own_device" on public.paper_scalp_sync;
drop policy "paper_scalp_sync_insert_own_device" on public.paper_scalp_sync;
drop policy "paper_scalp_sync_update_own_device" on public.paper_scalp_sync;

create policy "paper_scalp_sync_select_own_device"
on public.paper_scalp_sync for select to anon
using (
  device_id::text = coalesce((select current_setting('request.headers', true))::jsonb ->> 'x-paper-sync-device', '')
  and secret_hash = coalesce((select current_setting('request.headers', true))::jsonb ->> 'x-paper-sync-secret-hash', '')
);

create policy "paper_scalp_sync_insert_own_device"
on public.paper_scalp_sync for insert to anon
with check (
  device_id::text = coalesce((select current_setting('request.headers', true))::jsonb ->> 'x-paper-sync-device', '')
  and secret_hash = coalesce((select current_setting('request.headers', true))::jsonb ->> 'x-paper-sync-secret-hash', '')
);

create policy "paper_scalp_sync_update_own_device"
on public.paper_scalp_sync for update to anon
using (
  device_id::text = coalesce((select current_setting('request.headers', true))::jsonb ->> 'x-paper-sync-device', '')
  and secret_hash = coalesce((select current_setting('request.headers', true))::jsonb ->> 'x-paper-sync-secret-hash', '')
)
with check (
  device_id::text = coalesce((select current_setting('request.headers', true))::jsonb ->> 'x-paper-sync-device', '')
  and secret_hash = coalesce((select current_setting('request.headers', true))::jsonb ->> 'x-paper-sync-secret-hash', '')
);
