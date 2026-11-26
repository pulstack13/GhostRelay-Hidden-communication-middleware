create table if not exists public.live_locations (
  user_id uuid not null references public.profiles(id) on delete cascade,
  friend_id uuid not null references public.profiles(id) on delete cascade,
  latitude double precision not null check (latitude between -90 and 90),
  longitude double precision not null check (longitude between -180 and 180),
  accuracy_meters real not null default 0 check (accuracy_meters >= 0),
  updated_at timestamptz not null default now(),
  primary key (user_id, friend_id),
  check (user_id <> friend_id)
);

create index if not exists live_locations_friend_idx on public.live_locations (friend_id, user_id);
alter table public.live_locations enable row level security;
revoke all on public.live_locations from anon;
grant select, insert, update, delete on public.live_locations to authenticated;

drop policy if exists "friends can view shared live locations" on public.live_locations;
create policy "friends can view shared live locations"
  on public.live_locations for select to authenticated
  using (
    user_id = (select auth.uid())
    or exists (
      select 1 from public.friendships f
      where f.user_id = (select auth.uid()) and f.friend_id = live_locations.user_id
        and live_locations.friend_id = (select auth.uid())
    )
  );

drop policy if exists "users can share location with friends" on public.live_locations;
create policy "users can share location with friends"
  on public.live_locations for insert to authenticated
  with check (
    user_id = (select auth.uid())
    and exists (select 1 from public.friendships f where f.user_id = (select auth.uid()) and f.friend_id = live_locations.friend_id)
  );

drop policy if exists "users can update own shared location" on public.live_locations;
create policy "users can update own shared location"
  on public.live_locations for update to authenticated
  using (user_id = (select auth.uid()))
  with check (
    user_id = (select auth.uid())
    and exists (select 1 from public.friendships f where f.user_id = (select auth.uid()) and f.friend_id = live_locations.friend_id)
  );

drop policy if exists "users can stop own location sharing" on public.live_locations;
create policy "users can stop own location sharing"
  on public.live_locations for delete to authenticated using (user_id = (select auth.uid()));
