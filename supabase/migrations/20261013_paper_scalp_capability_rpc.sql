-- PostgREST did not reliably expose custom request headers after policy
-- changes. Keep the table RLS-enabled and inaccessible to anon; this narrow
-- RPC checks the per-installation capability atomically instead.
revoke select, insert, update on table public.paper_scalp_sync from anon;

drop policy "paper_scalp_sync_select_own_device" on public.paper_scalp_sync;
drop policy "paper_scalp_sync_insert_own_device" on public.paper_scalp_sync;
drop policy "paper_scalp_sync_update_own_device" on public.paper_scalp_sync;

create or replace function public.upsert_paper_scalp_sync(
  p_device_id uuid,
  p_secret_hash text,
  p_updated_at bigint,
  p_payload jsonb
)
returns bigint
language plpgsql
security definer
set search_path = ''
as $$
declare
  synced_at bigint;
begin
  if p_secret_hash !~ '^[0-9a-f]{64}$' then
    raise exception 'paper sync capability is malformed';
  end if;
  if p_updated_at <= 0 or jsonb_typeof(p_payload) <> 'object' then
    raise exception 'paper sync payload is malformed';
  end if;

  insert into public.paper_scalp_sync as current_row (device_id, secret_hash, updated_at, payload)
  values (p_device_id, p_secret_hash, p_updated_at, p_payload)
  on conflict (device_id) do update
  set updated_at = excluded.updated_at, payload = excluded.payload
  where current_row.secret_hash = excluded.secret_hash
  returning updated_at into synced_at;

  if synced_at is null then
    raise exception 'paper sync capability was not accepted';
  end if;
  return synced_at;
end;
$$;

revoke all on function public.upsert_paper_scalp_sync(uuid, text, bigint, jsonb) from public;
grant execute on function public.upsert_paper_scalp_sync(uuid, text, bigint, jsonb) to anon;
