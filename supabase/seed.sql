-- =====================================================================================
-- DEVELOPMENT SEED. Fake people and sample data for local work and CI.
-- Never run this against a real project: it creates accounts with a known password.
-- Password for every sample account: Campus#2026
-- =====================================================================================

create extension if not exists pgcrypto;

insert into public.universities (id, code, name) values
  ('90000000-0000-4000-8000-000000000001', 'CUOM', 'Cuom'),
  ('90000000-0000-4000-8000-000000000002', 'IFM',  'Institute of Finance Management'),
  ('90000000-0000-4000-8000-000000000003', 'UDSM', 'University of Dar es Salaam')
on conflict (code) do nothing;

-- Cuom has set up its programmes and courses. IFM and UDSM have not yet: their people sign up and
-- their administrators set things up as the campus joins.
insert into public.programmes (id, university_id, name) values
  ('11111111-1111-4111-8111-111111111111', '90000000-0000-4000-8000-000000000001', 'BSc Computer Science'),
  ('22222222-2222-4222-8222-222222222222', '90000000-0000-4000-8000-000000000001', 'BSc Information Systems')
on conflict do nothing;

-- The course code (CUOM:CS210) is built by a trigger from the university and the short code.
insert into public.courses (university_id, short_code, title) values
  ('90000000-0000-4000-8000-000000000001', 'CS210', 'Data Structures & Algorithms'),
  ('90000000-0000-4000-8000-000000000001', 'MT101', 'Discrete Mathematics'),
  ('90000000-0000-4000-8000-000000000001', 'CS212', 'Database Systems'),
  ('90000000-0000-4000-8000-000000000001', 'IS202', 'Systems Analysis'),
  ('90000000-0000-4000-8000-000000000001', 'CS215', 'Computer Networks'),
  ('90000000-0000-4000-8000-000000000001', 'GS101', 'Communication Skills')
on conflict (code) do nothing;

insert into public.programme_courses (programme_id, course_code) values
  ('11111111-1111-4111-8111-111111111111', 'CUOM:CS210'), ('11111111-1111-4111-8111-111111111111', 'CUOM:MT101'),
  ('11111111-1111-4111-8111-111111111111', 'CUOM:CS212'), ('11111111-1111-4111-8111-111111111111', 'CUOM:IS202'),
  ('11111111-1111-4111-8111-111111111111', 'CUOM:CS215'), ('11111111-1111-4111-8111-111111111111', 'CUOM:GS101'),
  ('22222222-2222-4222-8222-222222222222', 'CUOM:IS202'), ('22222222-2222-4222-8222-222222222222', 'CUOM:MT101'),
  ('22222222-2222-4222-8222-222222222222', 'CUOM:GS101')
on conflict do nothing;

-- Only Cuom has uploaded its lists so far.
insert into public.university_students (university_id, student_number)
select '90000000-0000-4000-8000-000000000001', n from (values
  ('T22-03-08492'), ('T22-03-09120'), ('T24-03-01140'), ('T23-03-09412')) v(n)
on conflict do nothing;
insert into public.university_staff (university_id, staff_number)
select '90000000-0000-4000-8000-000000000001', n from (values
  ('STF-CS-041'), ('STF-IS-033'), ('STF-ADM-001'), ('STF-EE-078')) v(n)
on conflict do nothing;

-- People. They go through the real sign-up trigger, so they start as PENDING students.
insert into auth.users
  (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
   raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
   confirmation_token, email_change, email_change_token_new, recovery_token)
select '00000000-0000-0000-0000-000000000000', u.id, 'authenticated', 'authenticated', u.email,
       crypt('Campus#2026', gen_salt('bf')), now(),
       '{"provider":"email","providers":["email"]}'::jsonb, u.meta::jsonb, now(), now(), '', '', '', ''
from (values
  ('a0000000-0000-4000-8000-000000000001'::uuid, 'a.mwangi@campus.ac.tz',
     '{"full_name":"Asha Mwangi","id_number":"T22-03-08492","university":"CUOM","programme":"BSc Computer Science"}'),
  ('a0000000-0000-4000-8000-000000000002'::uuid, 'k.tarimo@campus.ac.tz',
     '{"full_name":"Kelvin Tarimo","id_number":"T22-03-09120","university":"CUOM","programme":"BSc Computer Science"}'),
  ('a0000000-0000-4000-8000-000000000003'::uuid, 'b.juma@campus.ac.tz',
     '{"full_name":"Baraka Juma","id_number":"T24-03-01140","university":"CUOM","programme":"BSc Information Systems"}'),
  ('b0000000-0000-4000-8000-000000000001'::uuid, 'j.mushi@campus.ac.tz',
     '{"full_name":"Dr. Joseph Mushi","id_number":"STF-CS-041","university":"CUOM","is_staff":true,"department":"Computer Science"}'),
  ('b0000000-0000-4000-8000-000000000002'::uuid, 'd.kweka@campus.ac.tz',
     '{"full_name":"Eng. Daniel Kweka","id_number":"STF-IS-033","university":"CUOM","is_staff":true,"department":"Information Systems"}'),
  ('c0000000-0000-4000-8000-000000000001'::uuid, 'admin.registry@campus.ac.tz',
     '{"full_name":"Emanuel Mwenda","id_number":"STF-ADM-001","university":"CUOM","is_staff":true,"department":"Academic Registry"}'),
  ('c2000000-0000-4000-8000-000000000001'::uuid, 'ifm.registry@gmail.com',
     '{"full_name":"Rehema Salum","id_number":"IFM/REG/01","university":"IFM","is_staff":true,"department":"Registry"}'),
  ('a2000000-0000-4000-8000-0000000000f1'::uuid, 'joyce.mapunda@gmail.com',
     '{"full_name":"Joyce Mapunda","id_number":"2023/BAC/00123","university":"IFM","programme":"Bachelor of Accounting"}')
) as u(id, email, meta)
on conflict (id) do nothing;

-- The app manager belongs to the reserved PLATFORM university, not to any campus.
insert into auth.users
  (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
   raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
   confirmation_token, email_change, email_change_token_new, recovery_token)
values ('00000000-0000-0000-0000-000000000000', 'f1000000-0000-4000-8000-000000000001', 'authenticated', 'authenticated',
        'manager@campusconnect.app', crypt('Campus#2026', gen_salt('bf')), now(),
        '{"provider":"email","providers":["email"]}'::jsonb,
        '{"full_name":"CampusConnect Manager","id_number":"APP-001","university":"CUOM","is_staff":true}'::jsonb,
        now(), now(), '', '', '', '')
on conflict (id) do nothing;

-- Rooms of Cuom (its administrator maintains these in the app).
insert into public.rooms (university_id, name)
select '90000000-0000-4000-8000-000000000001', n from (values
  ('LTB Room 1'), ('LTB Room 2'), ('LTB Room 3'), ('LTB Room 4'), ('Main Hall South'),
  ('Science Complex Hall B'), ('Computer Lab 3'), ('Advanced Software Lab')) v(n)
on conflict do nothing;

-- Registry decisions (done here as the database owner, like a first-time setup).
update public.profiles set role = 'student', status = 'approved', cohort = 'Year 2 - Semester 1',
  programme_id = '11111111-1111-4111-8111-111111111111'
where id in ('a0000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000002');
update public.profiles set role = 'lecturer', status = 'approved', cohort = 'Lecturer'
where id in ('b0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000002');
update public.profiles set role = 'admin', status = 'approved', cohort = 'Chief Administrator'
where id = 'c0000000-0000-4000-8000-000000000001';
update public.profiles set role = 'admin', status = 'approved', cohort = 'Registrar'
where id = 'c2000000-0000-4000-8000-000000000001';
update public.profiles set university_id = (select id from public.universities where is_platform),
  role = 'admin', status = 'approved', is_platform_admin = true, cohort = 'App manager'
where id = 'f1000000-0000-4000-8000-000000000001';
update public.profiles set role = 'student', status = 'approved', cohort = 'Year 2'
where id = 'a2000000-0000-4000-8000-0000000000f1';
-- Baraka stays pending, so the waiting room can be tried.

insert into public.enrollments (student_id, course_code)
select s, c from (values ('a0000000-0000-4000-8000-000000000001'::uuid), ('a0000000-0000-4000-8000-000000000002'::uuid)) a(s),
                 (values ('CUOM:CS210'), ('CUOM:MT101'), ('CUOM:CS212'), ('CUOM:IS202'), ('CUOM:CS215'), ('CUOM:GS101')) b(c)
on conflict do nothing;

insert into public.teaching_assignments (lecturer_id, course_code) values
  ('b0000000-0000-4000-8000-000000000001', 'CUOM:CS210'), ('b0000000-0000-4000-8000-000000000001', 'CUOM:CS215'),
  ('b0000000-0000-4000-8000-000000000002', 'CUOM:IS202')
on conflict do nothing;

insert into public.class_sessions (id, course_code, kind, lecturer_id, day_of_week, start_time, end_time, venue) values
  ('d0000000-0000-4000-8000-000000000001', 'CUOM:CS210', 'lecture', 'b0000000-0000-4000-8000-000000000001', 1, '08:00', '10:00', 'Lecture Hall A1'),
  ('d0000000-0000-4000-8000-000000000002', 'CUOM:CS215', 'lecture', 'b0000000-0000-4000-8000-000000000001', 3, '10:00', '12:00', 'Lab 1'),
  ('d0000000-0000-4000-8000-000000000003', 'CUOM:IS202', 'lecture', 'b0000000-0000-4000-8000-000000000002', 2, '14:00', '16:00', 'Room B4')
on conflict do nothing;

-- Content is inserted with the owner's rights, so the limit triggers still count it.
insert into public.announcements (id, author_id, title, body, audience, course_code, priority) values
  ('e0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000001',
   'CS210 test moved', 'The midterm moves to Thursday 10:00 in A1.', 'course', 'CUOM:CS210', 'academic'),
  ('e0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000002',
   'IS202 case study', 'Bring the printed case study on Tuesday.', 'course', 'CUOM:IS202', 'normal')
on conflict do nothing;
insert into public.announcements (id, author_id, title, body, audience, priority) values
  ('e0000000-0000-4000-8000-000000000003', 'c0000000-0000-4000-8000-000000000001',
   'Library open until 22:00', 'The library stays open until 22:00 during exams.', 'campus', 'normal')
on conflict do nothing;

insert into public.deadlines (id, course_code, lecturer_id, title, instructions, due_at) values
  ('f0000000-0000-4000-8000-000000000001', 'CUOM:CS210', 'b0000000-0000-4000-8000-000000000001',
   'Programming Assignment 2', 'Implement BFS and DFS. Submit one PDF.', now() + interval '7 days'),
  ('f0000000-0000-4000-8000-000000000002', 'CUOM:IS202', 'b0000000-0000-4000-8000-000000000002',
   'Case study report', 'Two pages.', now() + interval '5 days')
on conflict do nothing;
insert into public.deadlines (id, course_code, lecturer_id, title, instructions, due_at) values
  ('f0000000-0000-4000-8000-000000000003', null, 'c0000000-0000-4000-8000-000000000001',
   'Exam registration closes', 'Register at the registry office.', now() + interval '10 days')
on conflict do nothing;
