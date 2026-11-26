create table if not exists public.message_email_cooldowns (
  user_id uuid primary key references auth.users(id) on delete cascade,
  last_email_sent_at timestamptz,
  lease_token uuid,
  lease_until timestamptz
);

alter table public.message_email_cooldowns enable row level security;
revoke all on public.message_email_cooldowns from public, anon, authenticated;
grant all on public.message_email_cooldowns to service_role;

create or replace function public.claim_message_email_cooldown(p_user_id uuid)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  new_lease uuid := gen_random_uuid();
  claimed_lease uuid;
begin
  insert into public.message_email_cooldowns (user_id, lease_token, lease_until)
  values (p_user_id, new_lease, now() + interval '2 minutes')
  on conflict (user_id) do update
    set lease_token = excluded.lease_token,
        lease_until = excluded.lease_until
    where (
      public.message_email_cooldowns.last_email_sent_at is null
      or public.message_email_cooldowns.last_email_sent_at <= now() - interval '30 minutes'
    )
    and (
      public.message_email_cooldowns.lease_until is null
      or public.message_email_cooldowns.lease_until < now()
    )
  returning lease_token into claimed_lease;

  return claimed_lease;
end;
$$;

create or replace function public.finish_message_email_cooldown(
  p_user_id uuid,
  p_lease_token uuid,
  p_sent boolean
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  update public.message_email_cooldowns
  set last_email_sent_at = case when p_sent then now() else last_email_sent_at end,
      lease_token = null,
      lease_until = null
  where user_id = p_user_id and lease_token = p_lease_token;
end;
$$;

revoke all on function public.claim_message_email_cooldown(uuid) from public, anon, authenticated;
revoke all on function public.finish_message_email_cooldown(uuid, uuid, boolean) from public, anon, authenticated;
grant execute on function public.claim_message_email_cooldown(uuid) to service_role;
grant execute on function public.finish_message_email_cooldown(uuid, uuid, boolean) to service_role;
