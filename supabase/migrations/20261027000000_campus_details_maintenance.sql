-- Campus details and offline (maintenance) mode.
--  * the app manager edits every detail of a campus and switches its offline mode;
--  * a campus administrator edits only the contact details of their own campus.
-- Safe to run twice.

alter table public.universities
  add column if not exists location          text    not null default '',
  add column if not exists contact_email     text    not null default '',
  add column if not exists contact_phone     text    not null default '',
  add column if not exists under_maintenance boolean not null default false,
  add column if not exists maintenance_note  text    not null default '';

-- The app manager puts a campus offline or brings it back. Everyone at that campus sees the
-- maintenance screen while it is on.
create or replace function public.platform_set_maintenance(p_university uuid, p_offline boolean, p_note text default '')
returns void language plpgsql security definer set search_path = public as $$
begin
  if not public.is_platform_admin() then raise exception 'not_allowed' using errcode = '42501'; end if;
  update public.universities
     set under_maintenance = coalesce(p_offline, false),
         maintenance_note  = left(coalesce(p_note, ''), 500)
   where id = p_university and not is_platform;
  if not found then raise exception 'unknown campus' using errcode = 'P0001'; end if;
  perform public.log_audit(
    case when p_offline then 'CAMPUS_OFFLINE' else 'CAMPUS_ONLINE' end,
    (select code from public.universities where id = p_university)
  );
end $$;

-- App manager: any field. Campus administrator: contact details of their own campus only
-- (name and code stay with the app manager).
create or replace function public.update_campus_details(
  p_university uuid, p_name text, p_location text, p_contact_email text, p_contact_phone text)
returns void language plpgsql security definer set search_path = public as $$
begin
  if public.is_platform_admin() then
    update public.universities
       set name = coalesce(nullif(trim(p_name), ''), name),
           location = left(coalesce(p_location, ''), 200),
           contact_email = left(coalesce(p_contact_email, ''), 200),
           contact_phone = left(coalesce(p_contact_phone, ''), 50)
     where id = p_university and not is_platform;
  elsif public.campus_admin(p_university) then
    update public.universities
       set location = left(coalesce(p_location, ''), 200),
           contact_email = left(coalesce(p_contact_email, ''), 200),
           contact_phone = left(coalesce(p_contact_phone, ''), 50)
     where id = p_university and not is_platform;
  else
    raise exception 'not_allowed' using errcode = '42501';
  end if;
  if not found then raise exception 'unknown campus' using errcode = 'P0001'; end if;
  perform public.log_audit('CAMPUS_DETAILS_EDITED', (select code from public.universities where id = p_university));
end $$;

grant execute on function
  public.platform_set_maintenance(uuid, boolean, text),
  public.update_campus_details(uuid, text, text, text, text)
to authenticated;
