-- DipHunter 0.3.8 cloud sync table.
-- Run in the Supabase SQL editor. Enable RLS. The Android app uses the
-- publishable/anon key only and never uploads the Kalshi private key.
--
-- Keys are namespaced per app (`grokbot:`, `kashi:`). Each app reads and
-- upserts only its own prefix. Do not update or delete another prefix.

create table if not exists public.diphunter_sync (
  key text primary key,
  kind text not null,
  updated_at bigint not null,
  payload jsonb not null
);

create index if not exists diphunter_sync_kind_idx on public.diphunter_sync (kind);
create index if not exists diphunter_sync_updated_idx on public.diphunter_sync (updated_at desc);

alter table public.diphunter_sync enable row level security;

-- Single-user / trusted-device pattern: allow the anon key to read/write
-- this table. Tighten if you share a project with other people.
drop policy if exists diphunter_sync_all on public.diphunter_sync;
create policy diphunter_sync_all on public.diphunter_sync
  for all
  using (true)
  with check (true);
