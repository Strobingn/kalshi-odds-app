-- One encrypted-capability-protected paper ledger snapshot per APK installation.
-- This intentionally does not modify the legacy public.diphunter_sync table.
create table public.paper_scalp_sync (
  device_id uuid primary key,
  secret_hash text not null check (secret_hash ~ '^[0-9a-f]{64}$'),
  updated_at bigint not null check (updated_at > 0),
  payload jsonb not null check (jsonb_typeof(payload) = 'object')
);

alter table public.paper_scalp_sync enable row level security;

revoke all on table public.paper_scalp_sync from public;
grant select, insert, update on table public.paper_scalp_sync to anon;

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
