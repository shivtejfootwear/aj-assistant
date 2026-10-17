-- AJ: अपने कई फ़ोन एक admin से देखना (Supabase SQL Editor में एक बार Run करें)
-- सुरक्षित: हर user सिर्फ अपने ही फ़ोन देख/चला सकता है (Row Level Security)

-- 1) हर फ़ोन अपनी जानकारी यहाँ रखता है (battery, storage, आज के count, location)
create table if not exists public.aj_devices (
  device_id  text primary key,
  user_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name       text,
  status     jsonb,
  last_seen  timestamptz not null default now()
);
create index if not exists aj_devices_user on public.aj_devices (user_id, last_seen desc);

-- 2) admin फ़ोन से दूसरे फ़ोन को भेजे गए काम (status, locate, ring)
create table if not exists public.aj_commands (
  id         bigserial primary key,
  user_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  target     text not null,            -- किस device_id के लिए
  type       text not null,            -- status | locate | ring
  args       jsonb,
  state      text not null default 'new',  -- new | done
  result     jsonb,
  created_at timestamptz not null default now()
);
create index if not exists aj_commands_target on public.aj_commands (user_id, target, state);

alter table public.aj_devices  enable row level security;
alter table public.aj_commands enable row level security;

drop policy if exists "aj devices own" on public.aj_devices;
create policy "aj devices own" on public.aj_devices
  for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "aj commands own" on public.aj_commands;
create policy "aj commands own" on public.aj_commands
  for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

grant select, insert, update, delete on public.aj_devices to authenticated;
grant select, insert, update, delete on public.aj_commands to authenticated;
grant usage, select on sequence public.aj_commands_id_seq to authenticated;

-- 3) 2 दिन से पुराने पूरे हो चुके काम अपने-आप हटें (जगह बचाने के लिए)
create or replace function public.aj_prune_commands() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  delete from public.aj_commands
   where user_id = new.user_id and state = 'done' and created_at < now() - interval '2 days';
  return new;
end $$;

drop trigger if exists aj_prune_commands_trg on public.aj_commands;
create trigger aj_prune_commands_trg after insert on public.aj_commands
  for each row execute function public.aj_prune_commands();

-- 4) फ़ोटो/रिकॉर्डिंग के लिए private storage (bucket: aj-media)
insert into storage.buckets (id, name, public)
values ('aj-media', 'aj-media', false)
on conflict (id) do nothing;

drop policy if exists "aj media own" on storage.objects;
create policy "aj media own" on storage.objects
  for all to authenticated
  using (bucket_id = 'aj-media' and (storage.foldername(name))[1] = auth.uid()::text)
  with check (bucket_id = 'aj-media' and (storage.foldername(name))[1] = auth.uid()::text);
