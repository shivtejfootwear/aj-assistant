-- AJ assistant: cloud याददाश्त (Supabase SQL Editor में एक बार Run करें)
-- सुरक्षित: हर user सिर्फ अपना ही data देख और बदल सकता है (Row Level Security)

-- 1) AJ की ताज़ा याददाश्त (हर user की एक row)
create table if not exists public.aj_memory (
  user_id    uuid primary key default auth.uid() references auth.users(id) on delete cascade,
  data       jsonb not null,
  updated_at timestamptz not null default now()
);

-- 2) रोज़ की अलग backup copy (cold backup)
create table if not exists public.aj_backups (
  id         bigserial primary key,
  user_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  data       jsonb not null,
  created_at timestamptz not null default now()
);
create index if not exists aj_backups_user_time on public.aj_backups (user_id, created_at desc);

alter table public.aj_memory  enable row level security;
alter table public.aj_backups enable row level security;

drop policy if exists "aj memory own" on public.aj_memory;
create policy "aj memory own" on public.aj_memory
  for all to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

drop policy if exists "aj backups read own" on public.aj_backups;
create policy "aj backups read own" on public.aj_backups
  for select to authenticated
  using (auth.uid() = user_id);

drop policy if exists "aj backups add own" on public.aj_backups;
create policy "aj backups add own" on public.aj_backups
  for insert to authenticated
  with check (auth.uid() = user_id);

grant select, insert, update on public.aj_memory to authenticated;
grant select, insert on public.aj_backups to authenticated;
grant usage, select on sequence public.aj_backups_id_seq to authenticated;

-- 3) 180 दिन से पुरानी backup copies अपने-आप हटें (free space बचाने के लिए)
create or replace function public.aj_prune_backups() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  delete from public.aj_backups
   where user_id = new.user_id and created_at < now() - interval '180 days';
  return new;
end $$;

drop trigger if exists aj_prune_backups_trg on public.aj_backups;
create trigger aj_prune_backups_trg after insert on public.aj_backups
  for each row execute function public.aj_prune_backups();
