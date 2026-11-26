create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text not null unique check (username ~ '^[a-z0-9_]{3,24}$'),
  display_name text not null check (char_length(display_name) between 1 and 48),
  created_at timestamptz not null default now()
);

create table if not exists public.friend_requests (
  id uuid primary key default gen_random_uuid(),
  from_user uuid not null references public.profiles(id) on delete cascade,
  to_user uuid not null references public.profiles(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending', 'accepted', 'declined')),
  created_at timestamptz not null default now(),
  unique (from_user, to_user),
  check (from_user <> to_user)
);

create table if not exists public.friendships (
  user_id uuid not null references public.profiles(id) on delete cascade,
  friend_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, friend_id),
  check (user_id <> friend_id)
);

create table if not exists public.messages (
  id uuid primary key default gen_random_uuid(),
  sender_id uuid not null references public.profiles(id) on delete cascade,
  recipient_id uuid not null references public.profiles(id) on delete cascade,
  text text not null default '',
  attachment_path text,
  attachment_name text,
  attachment_mime text,
  created_at timestamptz not null default now(),
  check (sender_id <> recipient_id),
  check (char_length(text) <= 4000),
  check (text <> '' or attachment_path is not null)
);

create index if not exists friend_requests_to_status_idx on public.friend_requests (to_user, status);
create index if not exists friendships_user_idx on public.friendships (user_id, friend_id);
create index if not exists messages_conversation_idx on public.messages (sender_id, recipient_id, created_at);
create index if not exists messages_recipient_idx on public.messages (recipient_id, sender_id, created_at);

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  requested_username text;
  safe_username text;
  requested_name text;
begin
  requested_username := lower(coalesce(new.raw_user_meta_data ->> 'username', split_part(coalesce(new.email, ''), '@', 1)));
  safe_username := regexp_replace(requested_username, '[^a-z0-9_]', '', 'g');
  if char_length(safe_username) < 3 then
    safe_username := 'user_' || substr(replace(new.id::text, '-', ''), 1, 12);
  end if;
  requested_name := coalesce(nullif(trim(new.raw_user_meta_data ->> 'display_name'), ''), safe_username);
  insert into public.profiles (id, username, display_name)
  values (new.id, safe_username, left(requested_name, 48));
  return new;
end;
$$;

drop trigger if exists on_auth_user_created_nook on auth.users;
create trigger on_auth_user_created_nook
  after insert on auth.users
  for each row execute procedure public.handle_new_user();

do $$
declare
  account record;
  requested_username text;
  safe_username text;
  requested_name text;
begin
  for account in
    select id, email, raw_user_meta_data
    from auth.users
    order by created_at
  loop
    if not exists (select 1 from public.profiles where id = account.id) then
      requested_username := lower(coalesce(
        account.raw_user_meta_data ->> 'username',
        split_part(coalesce(account.email, ''), '@', 1)
      ));
      safe_username := left(regexp_replace(requested_username, '[^a-z0-9_]', '', 'g'), 24);
      if char_length(safe_username) < 3 then
        safe_username := 'user_' || substr(replace(account.id::text, '-', ''), 1, 12);
      end if;
      if exists (select 1 from public.profiles p where p.username = safe_username) then
        safe_username := left(safe_username, 17) || '_' || substr(replace(account.id::text, '-', ''), 1, 6);
      end if;
      requested_name := coalesce(
        nullif(trim(account.raw_user_meta_data ->> 'display_name'), ''),
        safe_username
      );
      insert into public.profiles (id, username, display_name)
      values (account.id, safe_username, left(requested_name, 48))
      on conflict (id) do nothing;
    end if;
  end loop;
end;
$$;

create or replace function public.create_friendship_on_accept()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.status = 'accepted' and old.status = 'pending' then
    insert into public.friendships (user_id, friend_id)
    values (new.from_user, new.to_user), (new.to_user, new.from_user)
    on conflict do nothing;
  end if;
  return new;
end;
$$;

create or replace function public.keep_friend_request_participants_unchanged()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.from_user <> old.from_user or new.to_user <> old.to_user then
    raise exception 'friend request participants cannot be changed';
  end if;
  return new;
end;
$$;

drop trigger if exists friend_request_participants_immutable_nook on public.friend_requests;
create trigger friend_request_participants_immutable_nook
  before update on public.friend_requests
  for each row execute procedure public.keep_friend_request_participants_unchanged();

drop trigger if exists friend_request_accepted_nook on public.friend_requests;
create trigger friend_request_accepted_nook
  after update of status on public.friend_requests
  for each row execute procedure public.create_friendship_on_accept();

alter table public.profiles enable row level security;
alter table public.friend_requests enable row level security;
alter table public.friendships enable row level security;
alter table public.messages enable row level security;

revoke all on public.profiles, public.friend_requests, public.friendships, public.messages from anon;
grant select, update on public.profiles to authenticated;
grant select, insert, update on public.friend_requests to authenticated;
grant select on public.friendships to authenticated;
grant select, insert on public.messages to authenticated;

drop policy if exists "signed in users can find profiles" on public.profiles;
create policy "signed in users can find profiles"
  on public.profiles for select to authenticated using (true);
drop policy if exists "users can update own profile" on public.profiles;
create policy "users can update own profile"
  on public.profiles for update to authenticated using (id = (select auth.uid())) with check (id = (select auth.uid()));

drop policy if exists "participants can read friend requests" on public.friend_requests;
create policy "participants can read friend requests"
  on public.friend_requests for select to authenticated
  using (from_user = (select auth.uid()) or to_user = (select auth.uid()));
drop policy if exists "users can send friend requests" on public.friend_requests;
create policy "users can send friend requests"
  on public.friend_requests for insert to authenticated
  with check (from_user = (select auth.uid()) and to_user <> (select auth.uid()) and status = 'pending');
drop policy if exists "recipient can respond to pending request" on public.friend_requests;
create policy "recipient can respond to pending request"
  on public.friend_requests for update to authenticated
  using (to_user = (select auth.uid()) and status = 'pending')
  with check (to_user = (select auth.uid()) and status in ('accepted', 'declined'));

drop policy if exists "users can read own friendships" on public.friendships;
create policy "users can read own friendships"
  on public.friendships for select to authenticated using (user_id = (select auth.uid()));

drop policy if exists "friends can read their messages" on public.messages;
create policy "friends can read their messages"
  on public.messages for select to authenticated
  using (
    (sender_id = (select auth.uid()) or recipient_id = (select auth.uid()))
    and exists (
      select 1 from public.friendships f
      where f.user_id = (select auth.uid())
        and f.friend_id = case when sender_id = (select auth.uid()) then recipient_id else sender_id end
    )
  );
drop policy if exists "users can send messages to friends" on public.messages;
create policy "users can send messages to friends"
  on public.messages for insert to authenticated
  with check (
    sender_id = (select auth.uid())
    and exists (
      select 1 from public.friendships f
      where f.user_id = (select auth.uid()) and f.friend_id = recipient_id
    )
  );

insert into storage.buckets (id, name, public)
values ('chat-attachments', 'chat-attachments', false)
on conflict (id) do nothing;

drop policy if exists "friends can upload chat attachments" on storage.objects;
create policy "friends can upload chat attachments"
  on storage.objects for insert to authenticated
  with check (
    bucket_id = 'chat-attachments'
    and (storage.foldername(name))[1] = (select auth.uid())::text
    and exists (
      select 1 from public.friendships f
      where f.user_id = (select auth.uid())
        and f.friend_id::text = (storage.foldername(name))[2]
    )
  );
drop policy if exists "friends can read chat attachments" on storage.objects;
create policy "friends can read chat attachments"
  on storage.objects for select to authenticated
  using (
    bucket_id = 'chat-attachments'
    and (
      (storage.foldername(name))[1] = (select auth.uid())::text
      or exists (
        select 1 from public.friendships f
        where f.user_id = (select auth.uid())
          and f.friend_id::text = (storage.foldername(name))[1]
      )
    )
  );
drop policy if exists "owners can delete chat attachments" on storage.objects;
create policy "owners can delete chat attachments"
  on storage.objects for delete to authenticated
  using (bucket_id = 'chat-attachments' and (storage.foldername(name))[1] = (select auth.uid())::text);
