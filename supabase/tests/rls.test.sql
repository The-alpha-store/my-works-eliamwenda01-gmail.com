-- pgTAP tests: who may see and do what, enforced inside the database.
-- Run with:  supabase db reset && supabase test db
-- Needs the sample data from supabase/seed.sql.

begin;
select * from no_plan();

create schema if not exists tests;
create or replace function tests.as_user(p uuid, p_aal text default 'aal1') returns void
language plpgsql as $$
begin
  -- Always start from the owner role so switching users works even when called as 'authenticated'.
  execute 'reset role';
  perform set_config('request.jwt.claims',
    json_build_object('sub', p, 'role', 'authenticated', 'aal', p_aal)::text, true);
  execute 'set local role authenticated';
end $$;

grant usage on schema tests to anon, authenticated;
grant execute on function tests.as_user(uuid, text) to anon, authenticated;

-- Sample people (see seed.sql)
-- asha, kelvin = students (BSc Computer Science)   baraka = pending (BSc Information Systems)
-- mushi teaches CS210 + CS215, kweka teaches IS202  emanuel = administrator

-- ======================================================================================
-- Anonymous visitors
-- ======================================================================================
set local role anon;
select throws_ok($$select * from public.profiles$$, '42501', null, 'anonymous cannot read profiles');
select throws_ok($$select * from public.announcements$$, '42501', null, 'anonymous cannot read announcements');
select isnt_empty($$select * from public.programmes$$, 'anonymous can read the programme list (sign-up needs it)');

-- ======================================================================================
-- Sign-up door: only university emails, and never a role from the app
-- ======================================================================================
reset role;
select throws_ok(
  $$insert into auth.users (id, email, raw_user_meta_data)
    values (gen_random_uuid(), 'someone@gmail.com', '{"full_name":"Out Sider","id_number":"T25-03-00001"}')$$,
  'P0001', 'university_required', 'sign-up must name a university');
select throws_ok(
  $$insert into auth.users (id, email, raw_user_meta_data)
    values (gen_random_uuid(), 'someone@gmail.com', '{"full_name":"Out Sider","id_number":"T25-03-00001","university":"NOWHERE"}')$$,
  'P0001', 'university_required', 'an unknown university is refused');

-- Any email provider and any number format are fine.
insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values ('88888888-8888-4888-8888-888888888888', 'amina.said@yahoo.com', now(),
        '{"full_name":"Amina Said","id_number":"ifm/2024/bsc/0042","university":"IFM","programme":"Free text programme"}');
select is((select university_id from public.profiles where id = '88888888-8888-4888-8888-888888888888'),
          (select id from public.universities where code = 'IFM'), 'a gmail-style email and a free-form number are accepted');
select is((select student_number from public.profiles where id = '88888888-8888-4888-8888-888888888888'),
          'ifm/2024/bsc/0042', 'the registration number is kept exactly as typed');
select throws_ok(
  $$insert into auth.users (id, email, raw_user_meta_data)
    values (gen_random_uuid(), 'other@yahoo.com', '{"full_name":"Other Person","id_number":"IFM/2024/BSC/0042","university":"IFM"}')$$,
  '23505', null, 'the same registration number cannot be used twice in one university');
select lives_ok(
  $$insert into auth.users (id, email, raw_user_meta_data)
    values (gen_random_uuid(), 'same.number@yahoo.com', '{"full_name":"Same Number","id_number":"ifm/2024/bsc/0042","university":"UDSM"}')$$,
  'but another university may use the same number');

insert into auth.users (id, email, raw_user_meta_data)
values ('99999999-9999-4999-8999-999999999999', 'eve.hacker@campus.ac.tz',
        '{"full_name":"Eve Hacker","id_number":"T25-03-00002","university":"CUOM","role":"admin","status":"approved","is_admin":true}');
select is((select role::text from public.profiles where id = '99999999-9999-4999-8999-999999999999'),
          'student', 'sign-up metadata cannot choose the role');
select is((select status::text from public.profiles where id = '99999999-9999-4999-8999-999999999999'),
          'pending', 'sign-up metadata cannot choose the status');

-- ======================================================================================
-- A pending account (Baraka): sees only itself
-- ======================================================================================
select tests.as_user('a0000000-0000-4000-8000-000000000003');
select is((select count(*)::int from public.profiles), 1, 'pending person sees only their own profile');
select is_empty($$select * from public.announcements$$, 'pending person sees no announcements');
select is_empty($$select * from public.class_sessions$$, 'pending person sees no timetable');
select is_empty($$select * from public.study_materials$$, 'pending person sees no materials');
select is_empty($$select * from public.deadlines$$, 'pending person sees no deadlines');
select throws_ok($$update public.profiles set role = 'admin' where id = 'a0000000-0000-4000-8000-000000000003'$$,
                 '42501', null, 'a person cannot promote themselves');
select throws_ok($$update public.profiles set status = 'approved' where id = 'a0000000-0000-4000-8000-000000000003'$$,
                 '42501', null, 'a person cannot approve themselves');
reset role;
select is(public.limit_for('a0000000-0000-4000-8000-000000000003', 'submissions_per_day'), 0::bigint,
          'pending accounts have a zero submission limit');

-- ======================================================================================
-- A student (Asha): own courses only, own data only
-- ======================================================================================
reset role;
select tests.as_user('a0000000-0000-4000-8000-000000000001');
select is((select count(*)::int from public.class_sessions where course_code = 'CUOM:IS202'), 1,
          'student sees the timetable of a course they are enrolled in');
select is((select count(*)::int from public.profiles where id <> 'a0000000-0000-4000-8000-000000000001'), 0,
          'student sees no other profile');
select is((select count(*)::int from public.announcements where course_code = 'CUOM:CS210'), 1,
          'student sees an announcement for their course');
select is((select count(*)::int from public.announcements where audience = 'campus'), 1,
          'student sees the campus-wide announcement');
select is((select count(*)::int from public.notifications where user_id <> 'a0000000-0000-4000-8000-000000000001'), 0,
          'student sees only their own notifications');
select is_empty($$select * from public.audit_log$$, 'student cannot read the audit log');
select throws_ok($$insert into public.announcements (author_id, title, body, audience)
    values ('a0000000-0000-4000-8000-000000000001', 'Hello all', 'Hi', 'campus')$$,
    'P0001', 'limit_reached: announcements_per_day (allowed 0)', 'student cannot post: their announcement limit is zero');
select throws_ok($$select public.approve_account('a0000000-0000-4000-8000-000000000003', 'student')$$,
                 '42501', null, 'student cannot approve accounts');
select throws_ok($$select * from public.announcement_stats('e0000000-0000-4000-8000-000000000001')$$,
                 '42501', null, 'student cannot read delivery counts');
select throws_ok($$insert into public.study_materials (course_code, title, uploader_id, storage_path, file_name, mime_type, size_bytes)
    values ('CUOM:CS210', 'Notes', 'a0000000-0000-4000-8000-000000000001', 'CUOM:CS210/x/y.pdf', 'y.pdf', 'application/pdf', 10)$$,
    'P0001', 'limit_reached: materials_per_day (allowed 0)', 'student cannot share materials: their upload limit is zero');
select lives_ok($$insert into public.deadline_done (deadline_id) values ('f0000000-0000-4000-8000-000000000001')$$,
                'student can tick a deadline done');
select lives_ok($$insert into public.announcement_reads (announcement_id, user_id)
    values ('e0000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000001')$$,
    'student opening a notice is recorded');

-- A student cannot mark a notice for a course they are not in as read.
reset role;
select tests.as_user('a0000000-0000-4000-8000-000000000003');  -- pending: cannot even see notices
select throws_ok($$insert into public.announcement_reads (announcement_id, user_id)
    values ('e0000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000003')$$,
    '42501', null, 'a person who cannot see a notice cannot record opening it');

-- Student devices are limited to 3.
reset role;
select tests.as_user('a0000000-0000-4000-8000-000000000001');
select lives_ok($$select public.register_device('phone-a', 'Phone A')$$, 'student registers phone 1');
select lives_ok($$select public.register_device('phone-b', 'Phone B')$$, 'student registers phone 2');
select lives_ok($$select public.register_device('phone-c', 'Phone C')$$, 'student registers phone 3');
select throws_ok($$select public.register_device('phone-d', 'Phone D')$$, 'P0001', null,
                 'a fourth phone is refused');
select lives_ok($$select public.register_device('phone-a', 'Phone A')$$, 'a known phone may sign in again');
select ok((select public.export_my_data() -> 'profile' ->> 'email') = 'a.mwangi@campus.ac.tz',
          'a person can export their own data');

-- ======================================================================================
-- Kelvin cannot see Asha's private ticks, and no one else can either
-- ======================================================================================
reset role;
select tests.as_user('a0000000-0000-4000-8000-000000000002');
select is((select count(*)::int from public.deadline_done), 0, 'a student cannot see another student''s ticks');
reset role;
select tests.as_user('b0000000-0000-4000-8000-000000000001');
select is((select count(*)::int from public.deadline_done), 0, 'a lecturer cannot see anyone''s ticks');
reset role;
select tests.as_user('c0000000-0000-4000-8000-000000000001', 'aal2');
select is((select count(*)::int from public.deadline_done), 0, 'an administrator cannot see anyone''s ticks');
reset role;
select tests.as_user('b0000000-0000-4000-8000-000000000001');
select is(public.deadline_done_count('f0000000-0000-4000-8000-000000000001'), 1,
          'the lecturer may see only the count of ticks');

-- ======================================================================================
-- A lecturer (Dr. Mushi, teaches CS210 and CS215)
-- ======================================================================================
select is((select count(*)::int from public.profiles), 1, 'lecturer cannot read student profiles');
select is((select count(*)::int from public.class_list where course_code = 'CUOM:CS210'), 2,
          'lecturer sees the class list of a course they teach');
select is((select count(*)::int from public.class_list where course_code = 'CUOM:IS202'), 0,
          'lecturer sees no class list for a course they do not teach');
select is((select count(*)::int from public.class_sessions where course_code = 'CUOM:IS202'), 0,
          'lecturer does not see the timetable of courses they do not teach');
select is_empty($$select * from public.audit_log$$, 'lecturer cannot read the audit log');
select throws_ok($$insert into public.announcements (author_id, title, body, audience)
    values ('b0000000-0000-4000-8000-000000000001', 'To everyone', 'Hello', 'campus')$$,
    '42501', null, 'lecturer cannot speak to the whole campus');
select throws_ok($$insert into public.announcements (author_id, title, body, audience, course_code)
    values ('b0000000-0000-4000-8000-000000000001', 'Not mine', 'Hello', 'course', 'CUOM:IS202')$$,
    '42501', null, 'lecturer cannot post to a course they do not teach');
select lives_ok($$insert into public.announcements (id, author_id, title, body, audience, course_code)
    values ('e0000000-0000-4000-8000-0000000000a1', 'b0000000-0000-4000-8000-000000000001',
            'Lab report due', 'Bring it on Friday.', 'course', 'CUOM:CS210')$$,
    'lecturer posts to their own course');
select throws_ok($$insert into public.deadlines (course_code, lecturer_id, title, due_at)
    values ('CUOM:IS202', 'b0000000-0000-4000-8000-000000000001', 'Not mine', now() + interval '3 days')$$,
    '42501', null, 'lecturer cannot set a deadline for a course they do not teach');
select throws_ok($$update public.announcements set priority = 'urgent' where id = 'e0000000-0000-4000-8000-000000000001'$$,
                 'P0001', null, 'editing cannot be used to make a notice Urgent');

-- Urgent is rationed for lecturers (2 a day) and limited to their own course.
select lives_ok($$insert into public.announcements (author_id, title, body, audience, course_code, priority)
    values ('b0000000-0000-4000-8000-000000000001', 'Urgent one', 'x', 'course', 'CUOM:CS210', 'urgent')$$,
    'first Urgent notice is allowed');
select lives_ok($$insert into public.announcements (author_id, title, body, audience, course_code, priority)
    values ('b0000000-0000-4000-8000-000000000001', 'Urgent two', 'x', 'course', 'CUOM:CS210', 'urgent')$$,
    'second Urgent notice is allowed');
select throws_ok($$insert into public.announcements (author_id, title, body, audience, course_code, priority)
    values ('b0000000-0000-4000-8000-000000000001', 'Urgent three', 'x', 'course', 'CUOM:CS210', 'urgent')$$,
    'P0001', null, 'third Urgent notice today is refused');
select is((public.my_limits() ->> 'urgent_left')::int, 0, 'my_limits reports no Urgent notices left');

-- Delivery counts, reminders, retraction.
select is((select delivered from public.announcement_stats('e0000000-0000-4000-8000-0000000000a1')), 2,
          'sender sees how many students it was delivered to');
select is((select opened from public.announcement_stats('e0000000-0000-4000-8000-0000000000a1')), 0,
          'nobody has opened it yet');
reset role;
select tests.as_user('a0000000-0000-4000-8000-000000000001');
select lives_ok($$insert into public.announcement_reads (announcement_id, user_id)
    values ('e0000000-0000-4000-8000-0000000000a1', 'a0000000-0000-4000-8000-000000000001')$$,
    'Asha opens the notice');
reset role;
select tests.as_user('b0000000-0000-4000-8000-000000000001');
select is((select opened from public.announcement_stats('e0000000-0000-4000-8000-0000000000a1')), 1,
          'sender sees it was opened once');
select is(public.remind_unread('e0000000-0000-4000-8000-0000000000a1'), 1, 'one student is reminded');
select is(public.remind_unread('e0000000-0000-4000-8000-0000000000a1'), 0, 'only one reminder is ever sent');
select is(public.retract_announcement('e0000000-0000-4000-8000-0000000000a1'), true, 'sender retracts the notice');
reset role;
select tests.as_user('a0000000-0000-4000-8000-000000000002');
select is((select count(*)::int from public.announcements where id = 'e0000000-0000-4000-8000-0000000000a1'), 0,
          'a retracted notice disappears for students');
select ok(exists (select 1 from public.notifications where title like 'Correction%'),
          'students get a short correction');

-- A room change needs a reason, is shown to enrolled students, and warns about clashes.
reset role;
select tests.as_user('b0000000-0000-4000-8000-000000000001');
select throws_ok($$update public.class_sessions set venue = 'Lab 2' where id = 'd0000000-0000-4000-8000-000000000001'$$,
                 'P0001', 'reason_required', 'a room change without a reason is refused');
select lives_ok($$update public.class_sessions set venue = 'Lab 2', change_reason = 'Projector fault in A1'
                  where id = 'd0000000-0000-4000-8000-000000000001'$$, 'a room change with a reason is saved');
select is((select status::text from public.class_sessions where id = 'd0000000-0000-4000-8000-000000000001'),
          'changed', 'the session is marked Changed');
reset role;
select tests.as_user('a0000000-0000-4000-8000-000000000002');
select ok(exists (select 1 from public.notifications where title = 'Room changed: CS210'),
          'enrolled students are told about the room change');
reset role;
select tests.as_user('b0000000-0000-4000-8000-000000000001');
select throws_ok($$update public.class_sessions set venue = 'Lab 2', change_reason = 'Clash test', day_of_week = 1,
    start_time = '09:00', end_time = '11:00' where id = 'd0000000-0000-4000-8000-000000000002'$$,
    'P0001', null, 'moving a class into a busy room and time is refused');

-- Files: the size is read from storage, never trusted, and limited by role.
reset role;
insert into storage.objects (bucket_id, name, metadata)
values ('materials', 'CUOM:CS210/b0000000-0000-4000-8000-000000000001/notes.pdf', '{"size": 2000000}'),
       ('materials', 'CUOM:CS210/b0000000-0000-4000-8000-000000000001/huge.pdf',  '{"size": 60000000}'),
       ('submissions', 'a0000000-0000-4000-8000-000000000001/f0000000-0000-4000-8000-000000000001/work.pdf', '{"size": 1000000}'),
       ('submissions', 'a0000000-0000-4000-8000-000000000001/f0000000-0000-4000-8000-000000000001/big.pdf',  '{"size": 20000000}');
select tests.as_user('b0000000-0000-4000-8000-000000000001');
select throws_ok($$insert into public.study_materials (course_code, title, uploader_id, storage_path, file_name, mime_type, size_bytes)
    values ('CUOM:CS210', 'Missing', 'b0000000-0000-4000-8000-000000000001', 'CUOM:CS210/b0000000-0000-4000-8000-000000000001/nope.pdf', 'nope.pdf', 'application/pdf', 5)$$,
    'P0001', 'file_not_uploaded', 'a material row needs a real uploaded file');
select throws_ok($$insert into public.study_materials (course_code, title, uploader_id, storage_path, file_name, mime_type, size_bytes)
    values ('CUOM:CS210', 'Huge', 'b0000000-0000-4000-8000-000000000001', 'CUOM:CS210/b0000000-0000-4000-8000-000000000001/huge.pdf', 'huge.pdf', 'application/pdf', 5)$$,
    'P0001', null, 'a lecturer file over 50 MB is refused, whatever size the app claims');
select lives_ok($$insert into public.study_materials (course_code, title, uploader_id, storage_path, file_name, mime_type, size_bytes)
    values ('CUOM:CS210', 'Week 1 notes', 'b0000000-0000-4000-8000-000000000001', 'CUOM:CS210/b0000000-0000-4000-8000-000000000001/notes.pdf', 'notes.pdf', 'application/pdf', 5)$$,
    'a lecturer shares a file for their course');
reset role;
select tests.as_user('a0000000-0000-4000-8000-000000000001');
select is((select count(*)::int from public.study_materials), 1, 'enrolled students see the material');
reset role;
select tests.as_user('a0000000-0000-4000-8000-000000000003');
select is((select count(*)::int from public.study_materials), 0, 'a pending account does not');

-- Submissions: own work only, size limited, per-course.
reset role;
select tests.as_user('a0000000-0000-4000-8000-000000000001');
select throws_ok($$insert into public.submissions (deadline_id, course_code, student_id, storage_path, file_name, mime_type, size_bytes)
    values ('f0000000-0000-4000-8000-000000000001', 'CUOM:CS210', 'a0000000-0000-4000-8000-000000000001',
            'a0000000-0000-4000-8000-000000000001/f0000000-0000-4000-8000-000000000001/big.pdf', 'big.pdf', 'application/pdf', 5)$$,
    'P0001', null, 'a student file over 15 MB is refused');
select lives_ok($$insert into public.submissions (id, deadline_id, course_code, student_id, storage_path, file_name, mime_type, size_bytes)
    values ('a1000000-0000-4000-8000-000000000001', 'f0000000-0000-4000-8000-000000000001', 'CUOM:CS210',
            'a0000000-0000-4000-8000-000000000001',
            'a0000000-0000-4000-8000-000000000001/f0000000-0000-4000-8000-000000000001/work.pdf', 'work.pdf', 'application/pdf', 5)$$,
    'a student submits work');
select throws_ok($$insert into public.submissions (deadline_id, course_code, student_id, storage_path, file_name, mime_type, size_bytes)
    values ('f0000000-0000-4000-8000-000000000001', 'CUOM:CS210', 'a0000000-0000-4000-8000-000000000002',
            'a0000000-0000-4000-8000-000000000001/f0000000-0000-4000-8000-000000000001/work.pdf', 'work.pdf', 'application/pdf', 5)$$,
    '42501', null, 'a student cannot submit as someone else');
reset role;
select tests.as_user('a0000000-0000-4000-8000-000000000002');
select is((select count(*)::int from public.submissions), 0, 'another student cannot see that submission');
reset role;
select tests.as_user('b0000000-0000-4000-8000-000000000002');
select is((select count(*)::int from public.submissions), 0, 'a lecturer of another course cannot see it either');
reset role;
select tests.as_user('b0000000-0000-4000-8000-000000000001');
select is((select count(*)::int from public.submissions), 1, 'the course lecturer sees it');
select lives_ok($$update public.submissions set grade = '92/100', feedback = 'Clean work' where id = 'a1000000-0000-4000-8000-000000000001'$$,
                'the lecturer grades it');
select throws_ok($$update public.submissions set student_id = 'a0000000-0000-4000-8000-000000000002' where id = 'a1000000-0000-4000-8000-000000000001'$$,
                 '42501', null, 'a marker may change only grade, feedback and status');
reset role;
select tests.as_user('a0000000-0000-4000-8000-000000000001');
select ok(exists (select 1 from public.notifications where title like 'Assignment graded%'), 'the student is told about the grade');

-- ======================================================================================
-- The registry (administrator): two-step sign-in, approvals, audit log
-- ======================================================================================
reset role;
select tests.as_user('c0000000-0000-4000-8000-000000000001', 'aal1');
select is(public.is_admin(), false, 'an administrator without the second step has no admin powers');
reset role;
select tests.as_user('c0000000-0000-4000-8000-000000000001', 'aal2');
select is(public.is_admin(), true, 'with the second step the administrator has admin powers');
select is(public.registry_match('a0000000-0000-4000-8000-000000000003'), true, 'registry sees the number is on the student list');
select throws_ok($$select public.registry_match('a2000000-0000-4000-8000-0000000000f1')$$, '42501', null, 'a Cuom administrator cannot check an IFM person against any list');
select throws_ok($$select public.approve_account('a0000000-0000-4000-8000-000000000003', 'admin')$$,
                 'P0001', 'cannot_grant_admin_here', 'accounts cannot be made administrators from the queue');
select lives_ok($$select public.approve_account('a0000000-0000-4000-8000-000000000003', 'student', '22222222-2222-4222-8222-222222222222')$$,
                'the registry approves Baraka as a student');
select isnt_empty($$select * from public.audit_log where action = 'ACCOUNT_APPROVED'$$, 'the approval is in the audit log');
select throws_ok($$update public.audit_log set details = 'edited'$$, '42501', null, 'an administrator cannot edit an audit entry');
select throws_ok($$delete from public.audit_log$$, '42501', null, 'an administrator cannot erase an audit entry');
select lives_ok($$insert into public.announcements (author_id, title, body, audience)
    values ('c0000000-0000-4000-8000-000000000001', 'Campus notice', 'Open late', 'campus')$$,
    'the registry speaks to the whole campus');

reset role;
select throws_ok($$update public.audit_log set details = 'edited'$$, 'P0001', 'audit_log_is_append_only',
                 'even the database owner cannot edit an audit entry');
select throws_ok($$delete from public.audit_log$$, 'P0001', 'audit_log_is_append_only',
                 'even the database owner cannot erase an audit entry');
select throws_ok($$truncate public.audit_log$$, 'P0001', 'audit_log_is_append_only',
                 'even the database owner cannot truncate the audit log');
select tests.as_user('a0000000-0000-4000-8000-000000000003');
select is((select count(*)::int from public.class_sessions where course_code = 'CUOM:IS202'), 1,
          'Baraka now sees the courses of his programme');
select is((select count(*)::int from public.class_sessions where course_code = 'CUOM:CS210'), 0,
          'and not the courses of another programme');
select ok(exists (select 1 from public.notifications where title like 'Karibu%'), 'Baraka is welcomed');
reset role;
select is(public.limit_for('a0000000-0000-4000-8000-000000000003', 'submissions_per_day'), 20::bigint,
          'an approved student gets the student limits');

-- Switching someone off takes effect immediately.
reset role;
select tests.as_user('c0000000-0000-4000-8000-000000000001', 'aal2');
select lives_ok($$select public.suspend_account('a0000000-0000-4000-8000-000000000002', 'Test')$$, 'the registry suspends Kelvin');
select throws_ok($$select public.suspend_account('c0000000-0000-4000-8000-000000000001', 'oops')$$, 'P0001', 'cannot_suspend_self',
                 'an administrator cannot suspend themselves');
reset role;
select tests.as_user('a0000000-0000-4000-8000-000000000002');
select is_empty($$select * from public.announcements$$, 'a suspended person sees nothing, right away');
reset role;
select is(public.limit_for('a0000000-0000-4000-8000-000000000002', 'submissions_per_day'), 0::bigint, 'and has zero limits');

-- Kelvin was suspended above; approve him again so the later tests (quizzes, etc.) see an active student.
reset role;
-- reset role does not clear the pretend sign-in; clear it so the update runs as the database owner,
-- not as Kelvin (the profile guard only lets the registry or the owner change a status).
select set_config('request.jwt.claims', '', true), set_config('request.jwt.claim.sub', '', true);
update public.profiles
set status = 'approved'
where id = 'a0000000-0000-4000-8000-000000000002'::uuid;

-- ======================================================================================
-- Universities are kept apart
-- ======================================================================================
reset role;
select tests.as_user('c2000000-0000-4000-8000-000000000001', 'aal2');   -- IFM registry
select is((select count(*)::int from public.profiles where university_id <> (select id from public.universities where code = 'IFM')), 0,
          'an IFM administrator sees no Cuom profile');
select is_empty($$select * from public.class_sessions$$, 'an IFM administrator sees no Cuom timetable');
select is_empty($$select * from public.announcements$$, 'an IFM administrator sees no Cuom announcements');
select is_empty($$select * from public.audit_log where university_id <> (select id from public.universities where code = 'IFM')$$,
                'an IFM administrator sees no Cuom audit entry');
select throws_ok($$select public.approve_account('a0000000-0000-4000-8000-000000000003', 'student')$$, '42501', null,
                 'an IFM administrator cannot approve a Cuom account');
select throws_ok($$select public.suspend_account('a0000000-0000-4000-8000-000000000001', 'x')$$, '42501', null,
                 'an IFM administrator cannot suspend a Cuom student');
select isnt_empty($$select * from public.profiles where id = '88888888-8888-4888-8888-888888888888'$$,
                  'an IFM administrator sees the people waiting to join IFM');
select is(public.registry_match('88888888-8888-4888-8888-888888888888'), null,
          'IFM has not uploaded a list, so there is nothing to check against');
select lives_ok($$select public.approve_account('88888888-8888-4888-8888-888888888888', 'student')$$,
                'and approves them');

reset role;
select tests.as_user('c0000000-0000-4000-8000-000000000001', 'aal2');   -- Cuom registry
select is((select count(*)::int from public.profiles where university_id <> (select id from public.universities where code = 'CUOM')), 0,
          'a Cuom administrator sees no IFM profile');
select throws_ok($$select public.approve_account('a2000000-0000-4000-8000-0000000000f1', 'student')$$, '42501', null,
                 'a Cuom administrator cannot touch an IFM account');
select lives_ok($$insert into public.announcements (author_id, title, body, audience)
    values ('c0000000-0000-4000-8000-000000000001', 'Cuom campus only', 'Hello Cuom', 'campus')$$, 'Cuom posts campus-wide');

reset role;
select tests.as_user('a2000000-0000-4000-8000-0000000000f1');           -- IFM student
select is_empty($$select * from public.announcements$$, 'a Cuom campus notice does not reach an IFM student');
select is((select count(*)::int from public.notifications where title = 'Cuom campus only'), 0,
          'and sends them no notification');

reset role;
select tests.as_user('a0000000-0000-4000-8000-000000000001');           -- Cuom student
select is((select count(*)::int from public.announcements where title = 'Cuom campus only'), 1, 'but it does reach Cuom students');

-- ======================================================================================
-- Campus set-up: the campus administrator builds the catalogue
-- ======================================================================================
reset role;
select tests.as_user('c0000000-0000-4000-8000-000000000001', 'aal2');   -- Cuom registry
select lives_ok($$insert into public.programmes (university_id, name)
    select id, 'BSc Cyber Security' from public.universities where code = 'CUOM'$$, 'a campus administrator adds a programme');
select lives_ok($$insert into public.courses (university_id, short_code, title)
    select id, 'CS301', 'Network Security' from public.universities where code = 'CUOM'$$, 'and a course');
select is((select code from public.courses where short_code = 'CS301'), 'CUOM:CS301', 'the course code gets its campus prefix');
select lives_ok($$insert into public.programme_courses (programme_id, course_code)
    values ('11111111-1111-4111-8111-111111111111', 'CUOM:CS301')$$, 'the course is put into a programme');
select is((public.import_courses('[{"code":"CS302","title":"Cryptography","programme":"BSc Computer Science"},{"code":"x","title":""}]'::jsonb) ->> 'added')::int,
          1, 'importing a list adds the readable rows');
select lives_ok($$insert into public.rooms (university_id, name)
    select id, 'Lab 9' from public.universities where code = 'CUOM'$$, 'and a room');
select throws_ok($$insert into public.courses (university_id, short_code, title)
    select id, 'ACC101', 'Accounting' from public.universities where code = 'IFM'$$, '42501', null,
    'a campus administrator cannot add a course to another campus');
select throws_ok($$insert into public.rooms (university_id, name)
    select id, 'Stolen room' from public.universities where code = 'IFM'$$, '42501', null,
    'nor a room');

reset role;
select tests.as_user('a0000000-0000-4000-8000-000000000001');           -- Cuom student
select ok(exists (select 1 from public.enrollments where course_code = 'CUOM:CS301'),
          'students already in the programme are enrolled in the new course');
select ok(exists (select 1 from public.enrollments where course_code = 'CUOM:CS302'), 'and in the imported one');
select throws_ok($$insert into public.programmes (university_id, name)
    select id, 'Sneaky programme' from public.universities where code = 'CUOM'$$, '42501', null, 'a student cannot add a programme');
select isnt_empty($$select * from public.rooms$$, 'a student can see their campus rooms');

reset role;
select tests.as_user('c2000000-0000-4000-8000-000000000001', 'aal2');   -- IFM registry
select is_empty($$select * from public.rooms$$, 'another campus cannot see Cuom rooms');
select is_empty($$select * from public.courses$$, 'nor its courses');

-- A notice can be on behalf of a lecturer who teaches the course.
reset role;
select tests.as_user('c0000000-0000-4000-8000-000000000001', 'aal2');
select lives_ok($$insert into public.announcements (author_id, title, body, audience, course_code, responsible_lecturer_id)
    values ('c0000000-0000-4000-8000-000000000001', 'Test moved', 'Thursday', 'course', 'CUOM:CS210', 'b0000000-0000-4000-8000-000000000001')$$,
    'the registry posts on behalf of the lecturer who teaches the course');
select throws_ok($$insert into public.announcements (author_id, title, body, audience, course_code, responsible_lecturer_id)
    values ('c0000000-0000-4000-8000-000000000001', 'Wrong lecturer', 'x', 'course', 'CUOM:CS210', 'b0000000-0000-4000-8000-000000000002')$$,
    'P0001', 'responsible_not_for_course', 'but not on behalf of a lecturer who does not teach it');

-- ======================================================================================
-- The app manager
-- ======================================================================================
reset role;
select throws_ok(
  $$insert into auth.users (id, email, raw_user_meta_data)
    values ('77777777-7777-4777-8777-777777777777', 'plat.try@gmail.com', '{"full_name":"Plat Try","id_number":"P-1","university":"PLATFORM"}')$$,
  'P0001', 'university_required', 'nobody can sign up into the app manager''s reserved campus');

select tests.as_user('f1000000-0000-4000-8000-000000000001', 'aal2');   -- app manager
select is((select count(*)::int from public.profiles), 1, 'the app manager sees only their own profile');
select is_empty($$select * from public.announcements$$, 'and no campus announcements');
select is_empty($$select * from public.class_sessions$$, 'and no timetable');
select is_empty($$select * from public.study_materials$$, 'and no materials');
select is_empty($$select * from public.rooms$$, 'and no campus rooms');
select throws_ok($$select public.approve_account('99999999-9999-4999-8999-999999999999', 'student')$$, '42501', null,
                 'and cannot approve a campus''s people (that is the campus administrator''s job)');
select isnt_empty($$select * from public.platform_campus_summary()$$, 'but sees totals per campus');
select is_empty($$select * from public.platform_campus_summary() where code = 'PLATFORM'$$, 'without the reserved campus in the list');
select lives_ok($$select public.platform_create_university('UDOM', 'University of Dodoma')$$, 'the app manager creates a campus');
select lives_ok($$select public.platform_wipe_campus((select id from public.universities where code = 'CUOM'), 'announcements')$$,
                'and deletes one kind of data from a campus');
select throws_ok($$select public.platform_wipe_campus((select id from public.universities where code = 'CUOM'), 'nonsense')$$,
                 'P0001', 'unknown scope', 'an unknown scope is refused');
select lives_ok($$select public.platform_wipe_campus((select id from public.universities where code = 'UDSM'), 'people')$$,
                'and removes the people of a campus');
select lives_ok($$select public.platform_delete_university((select id from public.universities where code = 'UDOM'))$$,
                'and deletes a campus completely');
select isnt_empty($$select * from public.audit_log where action = 'CAMPUS_DELETED'$$, 'the manager''s actions are in the audit log');

reset role;
select is((select count(*)::int from public.announcements where title = 'Test moved'), 0, 'the deleted announcements are gone');
select is((select count(*)::int from public.profiles p join public.universities u on u.id = p.university_id where u.code = 'UDSM'), 0,
          'the removed people are gone');
select ok(exists (select 1 from public.class_sessions), 'other kinds of data were left alone');

select tests.as_user('c0000000-0000-4000-8000-000000000001', 'aal2');   -- Cuom registry
select throws_ok($$select public.platform_create_university('NOPE', 'No campus')$$, '42501', null, 'a campus administrator cannot create campuses');
select throws_ok($$select public.platform_wipe_campus((select id from public.universities where code = 'CUOM'), 'everything')$$, '42501', null,
                 'or delete campus data through the manager''s door');
select throws_ok($$select * from public.platform_campus_summary()$$, '42501', null, 'or list every campus');

-- ======================================================================================
-- Assigning the lecturers of one course (campus administrator only, own campus only)
-- ======================================================================================
reset role;
-- mushi teaches CS210 and CS215 and has a live CS210 class (see seed.sql); kweka teaches IS202.
select tests.as_user('a0000000-0000-4000-8000-000000000001');
select throws_ok($$select public.set_course_lecturers('CS210', array['b0000000-0000-4000-8000-000000000001']::uuid[])$$,
                 '42501', null, 'a student cannot assign lecturers');
reset role;
select tests.as_user('b0000000-0000-4000-8000-000000000001');
select throws_ok($$select public.set_course_lecturers('CS210', array['b0000000-0000-4000-8000-000000000001']::uuid[])$$,
                 '42501', null, 'a lecturer cannot assign lecturers, not even to their own course');
select tests.as_user('c0000000-0000-4000-8000-000000000001', 'aal1');
select throws_ok($$select public.set_course_lecturers('CS210', array['b0000000-0000-4000-8000-000000000001']::uuid[])$$,
                 '42501', null, 'an administrator without the second step cannot assign lecturers');

select tests.as_user('c0000000-0000-4000-8000-000000000001', 'aal2');   -- Cuom registry
select throws_ok($$select public.set_course_lecturers('NOPE999', array[]::uuid[])$$,
                 'P0001', 'course_not_found', 'an unknown course is refused');
select throws_ok($$select public.set_course_lecturers('CS210', array['a0000000-0000-4000-8000-000000000001']::uuid[])$$,
                 'P0001', 'not_a_lecturer', 'a student cannot be made a lecturer of a course');
select throws_ok($$select public.set_course_lecturers('CS210', array['c2000000-0000-4000-8000-000000000001']::uuid[])$$,
                 'P0001', 'not_a_lecturer', 'a person from another campus cannot be assigned');
select throws_ok($$select public.set_course_lecturers('CS210', array['b0000000-0000-4000-8000-000000000002']::uuid[])$$,
                 'P0001', 'lecturer_has_classes', 'a lecturer with a live class cannot be removed from the course');
select is((select count(*)::int from public.teaching_assignments
           where course_code = 'CUOM:CS210' and lecturer_id = 'b0000000-0000-4000-8000-000000000001'), 1,
          'a refused change leaves the assignments exactly as they were');

-- Adding a second lecturer while keeping the first works, by short code.
select lives_ok($$select public.set_course_lecturers('CS210',
  array['b0000000-0000-4000-8000-000000000001','b0000000-0000-4000-8000-000000000002']::uuid[])$$,
  'the registry can assign two lecturers to one course');
select is((select count(*)::int from public.teaching_assignments where course_code = 'CUOM:CS210'), 2,
          'both lecturers now teach the course');
select is((select count(*)::int from public.teaching_assignments
           where lecturer_id = 'b0000000-0000-4000-8000-000000000001' and course_code = 'CUOM:CS215'), 1,
          'assigning one course leaves the lecturer''s other courses alone');
select is((select count(*)::int from public.audit_log where action = 'COURSE_LECTURERS_SET'), 1,
          'the change is recorded in the audit log');
select lives_ok($$select public.set_course_lecturers('CS210',
  array['b0000000-0000-4000-8000-000000000001','b0000000-0000-4000-8000-000000000002']::uuid[])$$,
  'saving the same list again is harmless');
select is((select count(*)::int from public.audit_log where action = 'COURSE_LECTURERS_SET'), 1,
          'and is not logged twice');

-- The new lecturer is told, and now really may act on the course.
select tests.as_user('b0000000-0000-4000-8000-000000000002');
select is((select count(*)::int from public.notifications where title = 'You now teach CS210'), 1,
          'the newly assigned lecturer is notified');
select ok(public.teaches('CUOM:CS210'), 'the newly assigned lecturer now teaches the course in the database''s eyes');

-- Removing a lecturer who has no live class there is allowed.
select tests.as_user('c0000000-0000-4000-8000-000000000001', 'aal2');
select lives_ok($$select public.set_course_lecturers('CS210', array['b0000000-0000-4000-8000-000000000001']::uuid[])$$,
                'a lecturer without a live class can be removed from the course');
select is((select count(*)::int from public.teaching_assignments where course_code = 'CUOM:CS210'), 1,
          'only the remaining lecturer teaches it');

-- Another campus's registry cannot reach a Cuom course, even by its short code.
select tests.as_user('c2000000-0000-4000-8000-000000000001', 'aal2');   -- IFM registry
select throws_ok($$select public.set_course_lecturers('CS210', array[]::uuid[])$$,
                 'P0001', 'course_not_found', 'another campus''s registry cannot change a Cuom course');
reset role;
select is((select count(*)::int from public.teaching_assignments where course_code = 'CUOM:CS210'), 1,
          'and nothing changed');

-- ======================================================================================
-- Timed quizzes: who may set, see, take and mark them, and what the clock enforces.
-- (now() is frozen inside this test transaction, so "time passing" is simulated by moving the
--  attempt's own timestamps as the database owner.)
-- ======================================================================================
reset role;
set local role anon;
select throws_ok($$select public.start_quiz_attempt(gen_random_uuid())$$, '42501', null, 'anonymous cannot start a quiz');
reset role;

select tests.as_user('a0000000-0000-4000-8000-000000000001');
select throws_ok($$select public.create_quiz('CS210','Sneaky','',10, now(), now() + interval '2 hours',
  '[{"kind":"short_answer","body":"x","points":1}]'::jsonb)$$, '42501', null, 'a student cannot set a quiz');
select tests.as_user('b0000000-0000-4000-8000-000000000002');
select throws_ok($$select public.create_quiz('CS210','Not mine','',10, now(), now() + interval '2 hours',
  '[{"kind":"short_answer","body":"x","points":1}]'::jsonb)$$, '42501', null, 'a lecturer cannot set a quiz for a course they do not teach');

select tests.as_user('b0000000-0000-4000-8000-000000000001');
select throws_ok($$select public.create_quiz('CS210','x','',0, now(), now() + interval '2 hours',
  '[{"kind":"short_answer","body":"x","points":1}]'::jsonb)$$, 'P0001', 'bad_duration', 'time allowed must be 1 to 180 minutes');
select throws_ok($$select public.create_quiz('CS210','x','',30, now(), now() + interval '10 minutes',
  '[{"kind":"short_answer","body":"x","points":1}]'::jsonb)$$, 'P0001', 'bad_window', 'the quiz must stay open at least as long as the time allowed');
select throws_ok($$select public.create_quiz('CS210','x','',10, now(), now() + interval '1 hour', '[]'::jsonb)$$,
                 'P0001', 'no_questions', 'a quiz needs questions');
select throws_ok($$select public.create_quiz('CS210','x','',10, now(), now() + interval '1 hour',
  '[{"kind":"multiple_choice","body":"Pick","options":["a","b"],"points":1}]'::jsonb)$$,
  'P0001', 'bad_question', 'a multiple choice question needs a right answer');

select lives_ok($$select public.create_quiz('CS210','Week 5 quiz','Answer everything', 20,
  now() - interval '1 minute', now() + interval '2 hours',
  '[{"kind":"multiple_choice","body":"Which structure is LIFO?","options":["Queue","Stack","Tree"],"correct":1,"points":2},
    {"kind":"multiple_choice","body":"Binary search needs a","options":["Sorted list","Hash table"],"correct":0,"points":1},
    {"kind":"short_answer","body":"Define Big-O notation.","model_answer":"An upper bound on growth","points":4}]'::jsonb)$$,
  'the lecturer sets a quiz for a course they teach');
select is((select count(*)::int from public.quiz_answer_keys), 3, 'the lecturer can read the answer key');

-- Students: questions only after Start, never the key.
select tests.as_user('a0000000-0000-4000-8000-000000000001');
select is((select count(*)::int from public.quizzes), 1, 'an enrolled student sees the quiz');
select is((select count(*)::int from public.quiz_questions), 0, 'but not its questions before pressing Start');
select is((select count(*)::int from public.notifications where title = 'New quiz: CS210'), 1, 'and is told about it');
select lives_ok($$select * from public.start_quiz_attempt((select id from public.quizzes where title = 'Week 5 quiz'))$$, 'the student starts the quiz');
select is((select count(*)::int from public.quiz_questions), 3, 'after starting, the questions are readable');
select is((select count(*)::int from public.quiz_answer_keys), 0, 'but the answer key never is');
select is((select deadline_at - started_at from public.quiz_attempts), interval '20 minutes', 'the timer is the time allowed');
select is((select attempt_id from public.start_quiz_attempt((select id from public.quizzes where title = 'Week 5 quiz'))), (select id from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000001'::uuid), 'starting again resumes the same attempt');
select throws_ok($$insert into public.quiz_answers (attempt_id, question_id, answer) values (gen_random_uuid(), gen_random_uuid(), '1')$$,
                 '42501', null, 'a student cannot write answers directly');
select throws_ok($$update public.quiz_attempts set status = 'submitted'$$, '42501', null, 'a student cannot change an attempt directly');

select lives_ok($$select public.save_quiz_answer((select id from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000001'::uuid), (select id from public.quiz_questions where quiz_id = (select id from public.quizzes where title = 'Week 5 quiz') and position = 1), '1')$$, 'an answer is saved while the timer runs');
select lives_ok($$select public.save_quiz_answer((select id from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000001'::uuid), (select id from public.quiz_questions where quiz_id = (select id from public.quizzes where title = 'Week 5 quiz') and position = 2), '9')$$, 'an option that does not exist is simply not stored');
select is((select count(*)::int from public.quiz_answers where attempt_id = (select id from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000001'::uuid)), 1, 'only the valid answer was kept');
select is((select public.quiz_attempt_score((select id from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000001'::uuid))), null, 'x') where false;
select throws_ok($$select public.quiz_attempt_score((select id from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000001'::uuid))$$, '42501', null, 'the score cannot be read while the quiz is running');

-- Other people see nothing of it.
select tests.as_user('a0000000-0000-4000-8000-000000000002');
select is((select count(*)::int from public.quiz_attempts), 0, 'another student cannot see this attempt');
select tests.as_user('a2000000-0000-4000-8000-0000000000f1');
select is((select count(*)::int from public.quizzes), 0, 'a student of another campus sees no quizzes');
select throws_ok($$select * from public.start_quiz_attempt((select id from public.quizzes where title = 'Week 5 quiz'))$$, 'P0001', 'quiz_not_found', 'and cannot start one');
select tests.as_user('b0000000-0000-4000-8000-000000000002');
select is((select count(*)::int from public.quizzes), 0, 'a lecturer of another course sees no quiz');
select is((select count(*)::int from public.quiz_attempts), 0, 'and no attempts');
select throws_ok($$select public.delete_quiz((select id from public.quizzes where title = 'Week 5 quiz'))$$, '42501', null, 'and cannot delete it');

-- The clock: ten seconds after the deadline the answers on the screen are still accepted...
reset role;
update public.quiz_attempts set started_at = now() - interval '30 minutes', deadline_at = now() - interval '5 seconds'
 where student_id = 'a0000000-0000-4000-8000-000000000001'::uuid;
select tests.as_user('a0000000-0000-4000-8000-000000000001');
select throws_ok($$select public.save_quiz_answer((select id from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000001'::uuid), (select id from public.quiz_questions where quiz_id = (select id from public.quizzes where title = 'Week 5 quiz') and position = 2), '0')$$, 'P0001', 'time_up', 'an ordinary save after zero is refused');
select lives_ok($$select public.submit_quiz_attempt((select id from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000001'::uuid), jsonb_build_object((select id from public.quiz_questions where quiz_id = (select id from public.quizzes where title = 'Week 5 quiz') and position = 3)::text, 'Written at the last second'))$$,
                'the final hand-in is accepted just after zero');
select is((select status from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000001'::uuid), 'auto_submitted', 'and the attempt counts as submitted when time ran out');
select is((select answer from public.quiz_answers where attempt_id = (select id from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000001'::uuid) and question_id = (select id from public.quiz_questions where quiz_id = (select id from public.quizzes where title = 'Week 5 quiz') and position = 3)), 'Written at the last second',
          'the last-second answer was kept');
select lives_ok($$select public.submit_quiz_attempt((select id from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000001'::uuid), '{}'::jsonb)$$, 'handing in twice is harmless');
select throws_ok($$select * from public.start_quiz_attempt((select id from public.quizzes where title = 'Week 5 quiz'))$$, 'P0001', 'already_submitted', 'there is no second go');

-- ...but a late flush well after zero is ignored, and the attempt closes with what it already had.
select tests.as_user('a0000000-0000-4000-8000-000000000002');
select lives_ok($$select * from public.start_quiz_attempt((select id from public.quizzes where title = 'Week 5 quiz'))$$, 'a second student starts');
reset role;
update public.quiz_attempts set started_at = now() - interval '40 minutes', deadline_at = now() - interval '30 seconds'
 where student_id = 'a0000000-0000-4000-8000-000000000002'::uuid;
select tests.as_user('a0000000-0000-4000-8000-000000000002');
select lives_ok($$select public.submit_quiz_attempt((select id from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000002'::uuid), jsonb_build_object((select id from public.quiz_questions where quiz_id = (select id from public.quizzes where title = 'Week 5 quiz') and position = 1)::text, '1'))$$, 'a late hand-in still closes the attempt');
select is((select count(*)::int from public.quiz_answers where attempt_id = (select id from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000002'::uuid)), 0, 'but answers sent well after zero are ignored');
select is((select status from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000002'::uuid), 'auto_submitted', 'and it is marked as submitted when time ran out');

-- Scores and marking.
select tests.as_user('a0000000-0000-4000-8000-000000000001');
select is((public.quiz_attempt_score((select id from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000001'::uuid)) ->> 'choice_points')::int, 2, 'multiple choice is marked at once');
select is((public.quiz_attempt_score((select id from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000001'::uuid)) ->> 'complete')::boolean, false, 'written answers are not marked yet');
select throws_ok($$select public.grade_quiz_attempt((select id from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000001'::uuid), jsonb_build_object((select id from public.quiz_questions where quiz_id = (select id from public.quizzes where title = 'Week 5 quiz') and position = 3)::text, 4))$$, '42501', null, 'a student cannot mark their own work');
select tests.as_user('b0000000-0000-4000-8000-000000000001');
select lives_ok($$select public.grade_quiz_attempt((select id from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000001'::uuid), jsonb_build_object((select id from public.quiz_questions where quiz_id = (select id from public.quizzes where title = 'Week 5 quiz') and position = 3)::text, 99, (select id from public.quiz_questions where quiz_id = (select id from public.quizzes where title = 'Week 5 quiz') and position = 1)::text, 5))$$,
                'the lecturer marks the written answer');
select tests.as_user('a0000000-0000-4000-8000-000000000001');
select is((public.quiz_attempt_score((select id from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000001'::uuid)) ->> 'written_points')::int, 4, 'points are capped at what the question is worth');
select is((public.quiz_attempt_score((select id from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000001'::uuid)) ->> 'choice_points')::int, 2, 'and marks given to a multiple choice question are ignored');
select is((public.quiz_attempt_score((select id from public.quiz_attempts where student_id = 'a0000000-0000-4000-8000-000000000001'::uuid)) ->> 'complete')::boolean, true, 'the score is complete once marked');
select is((select count(*)::int from public.notifications where title = 'Quiz marked: CS210'), 1, 'the student is told it was marked');

-- Windows: not open yet, already closed.
select tests.as_user('b0000000-0000-4000-8000-000000000001');
select lives_ok($$select public.create_quiz('CS210','Future quiz','',10, now() + interval '1 day', now() + interval '2 days',
  '[{"kind":"short_answer","body":"x","points":1}]'::jsonb)$$, 'a quiz can be set for the future');
select lives_ok($$select public.create_quiz('CS210','Past quiz','',10, now() - interval '1 hour', now() + interval '1 hour',
  '[{"kind":"short_answer","body":"x","points":1}]'::jsonb)$$, 'another quiz to close');
reset role;
update public.quizzes set opens_at = now() - interval '3 hours', closes_at = now() - interval '1 minute' where title = 'Past quiz';
select tests.as_user('a0000000-0000-4000-8000-000000000001');
select throws_ok($$select * from public.start_quiz_attempt((select id from public.quizzes where title = 'Future quiz'))$$, 'P0001', 'not_open_yet', 'a quiz cannot be started before it opens');
select throws_ok($$select * from public.start_quiz_attempt((select id from public.quizzes where title = 'Past quiz'))$$, 'P0001', 'closed', 'or after it has closed');

-- Rescheduling and deleting.
select tests.as_user('b0000000-0000-4000-8000-000000000002');
select throws_ok($$select public.reschedule_quiz((select id from public.quizzes where title = 'Future quiz'), 15, now(), now() + interval '1 day')$$,
                 '42501', null, 'another lecturer cannot reschedule it');
select tests.as_user('b0000000-0000-4000-8000-000000000001');
select throws_ok($$select public.reschedule_quiz((select id from public.quizzes where title = 'Future quiz'), 60, now(), now() + interval '10 minutes')$$,
                 'P0001', 'bad_window', 'a window shorter than the time allowed is refused');
select lives_ok($$select public.reschedule_quiz((select id from public.quizzes where title = 'Future quiz'), 15, now() + interval '3 days', now() + interval '4 days')$$,
                'the lecturer reschedules');
select lives_ok($$select public.delete_quiz((select id from public.quizzes where title = 'Week 5 quiz'))$$, 'the lecturer deletes a quiz');
reset role;
select is((select count(*)::int from public.quiz_attempts), 0, 'its attempts are removed with it');
select is((select count(*)::int from public.quiz_questions where quiz_id not in (select id from public.quizzes)), 0, 'and its questions');

-- ======================================================================================
-- Guards that keep future changes honest
-- ======================================================================================
reset role;
select is_empty(
  $$select tablename from pg_tables where schemaname = 'public' and not rowsecurity$$,
  'every table in the public schema has row-level security switched on');
select is((select count(*)::int from public.role_limits), 12,
          'there is a limits row for every role and membership status');
select is_empty(
  $$select role, status from public.role_limits
    where status <> 'approved' and (coalesce(announcements_per_day, 1) <> 0 or coalesce(materials_per_day, 1) <> 0
          or coalesce(submissions_per_day, 1) <> 0 or coalesce(deadlines_per_day, 1) <> 0)$$,
  'accounts that are not approved have zero limits for everything');
select is((select urgent_per_day from public.role_limits where role = 'lecturer' and status = 'approved'), 2,
          'lecturers may send two Urgent notices a day');
select ok((select urgent_per_day is null from public.role_limits where role = 'admin' and status = 'approved'),
          'the registry has no Urgent limit');
select is_empty(
  $$select p.proname from pg_proc p join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public' and p.proname in ('bootstrap_first_admin', 'log_audit', 'notify_users', 'limit_for', 'check_limit')
      and (has_function_privilege('anon', p.oid, 'execute') or has_function_privilege('authenticated', p.oid, 'execute'))$$,
  'internal functions cannot be called by app users');

select * from finish();
rollback;
