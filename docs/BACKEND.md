# CampusConnect backend (Supabase)

Everything the database must enforce lives in **one merged, re-runnable migration**:
`supabase/migrations/20261021000000_campusconnect_v2.sql`.

The app screens are a convenience. The rules below are enforced by the database, so a modified
app or a direct API call gets the same answer: *"You are not allowed to see that."*

## Files

| File | What it is |
|---|---|
| `supabase/migrations/20261021000000_campusconnect_v2.sql` | The whole schema: tables, row-level security, limits, audit log, storage rules, server functions. Safe to run twice. |
| `supabase/seed.sql` | **Development only.** Fake people and sample data. Password for every sample account: `Campus#2026`. Never run it on a real project. |
| `supabase/tests/rls.test.sql` | 163 pgTAP checks of who may see and do what: campuses cannot see each other, the app manager sees no campus content, limits by role, and more. |
| `supabase/config.toml` | Local and CI settings (email code, password rules, second step). |
| `.github/workflows/ci.yml` | Android build + unit tests, and database migration + tests, on every push and pull request. |
| `.github/workflows/deploy-supabase.yml` | Pushes new migrations to your hosted project (needs approval). |
| `.github/workflows/release-android.yml` | Builds a signed release when you push a tag like `v1.0.0`. |

There were no SQL files or workflows in the uploaded source, so these are new. If you have SQL
elsewhere (for example in the Supabase dashboard), compare it with the migration before running it.

## Universities: one app, many campuses

Every person chooses their **university** when they sign up, and everything they do stays inside it.

- **Any email works** (Gmail, Yahoo, a university address). It is used for the sign-in code and password reset.
- **Registration and staff numbers have no required format.** Each campus writes them differently. A number is
  unique *inside* its university, so two campuses may use the same one. People sign in with their
  registration number or their email.
- Each university has its **own administrators**. An administrator sees, approves and suspends only people of
  their own university, and reads only that university's audit log. Another university's data is invisible to them.
- Course codes are made unique across campuses with a prefix built by the database (`CUOM:CS210`). Apps send
  the short code (`CS210`) and show the short code.
- A university that has not set up programmes yet still works: its students type their programme, and its
  administrator assigns courses later. A university that has not uploaded a student/staff list simply gets
  "no list to check against" in the approval queue.
- Add a campus with one row: `insert into universities (code, name) values ('UDOM', 'University of Dodoma');`
  then create its first administrator (below).

This is the first version of campus sign-up. Rules that need each campus's agreement (number formats, email
checks, shared lists) can be added later per university.

## Three kinds of administrator

| | Sees | Can do |
|---|---|---|
| **Campus administrator** | only their own campus | approve their people and choose their roles; **add programmes, courses and rooms** (one by one or `import_courses`); assign lecturers to courses (`set_course_lecturers`, one course at a time; `set_teaching_assignments`, one lecturer at a time); post campus-wide; read their campus's audit log |
| **App manager** | totals per campus, **never what a campus wrote** | create campuses, make a campus's first administrator, delete any kind of data from any campus, delete a campus; read the log of their own actions |
| Everyone else | their own courses and data | see the table below |

**Campus set-up.** Programmes, courses and rooms are written by the campus administrator and read only by that
campus. Sign-up shows each campus's programmes; students choose from them (or type theirs if the campus has none yet).
When a course is added to a programme, students already in that programme are enrolled automatically.
Lecturers add a class by choosing a subject, the lecturer responsible, the day and time, and a room that is free
at that time (the database also refuses a clash of room or lecturer).

**App manager.** The account sits in the reserved `PLATFORM` campus, which is never offered at sign-up and holds
no campus content. It has no row-level policy on any campus table, so it cannot read a campus's data; it works only through
`platform_campus_summary()`, `platform_create_university()`, `platform_make_campus_admin()`,
`platform_wipe_campus()` and `platform_delete_university()`. It also needs the second step (aal2) like any administrator.
Create it once from the SQL editor: sign up in the app, verify the email, then
`update profiles set university_id = (select id from universities where is_platform), role = 'admin', status = 'approved', is_platform_admin = true where lower(email) = 'you@example.com';`

To add a campus: the app manager creates it (`platform_create_university`); the future campus administrator signs up in
the app choosing that campus and verifies their email; the app manager runs `platform_make_campus_admin`.
(The prototype app creates the first administrator in one step; against Supabase Auth, creating a login for someone else
needs a small Edge Function that uses the service key, so the invite-then-promote path is the safe default.)

Deleting data returns the **storage paths** of the files to remove. The `campus-wipe-files` Edge Function calls `platform_wipe_campus` with the caller JWT (so the database re-checks platform-admin/MFA authorization), then removes returned objects from the matching private Storage buckets using the service key server-side. Database deletion may already be committed if Storage removal fails; the function reports partial failures explicitly. The audit log is never deleted.

## Who may do what

The limits come from one table, `role_limits`, with a row for **every role and membership status**.
`NULL` means no limit. Change a number there and the rule changes for everyone, without touching the app.

| Membership | Posts per day | Urgent per day | Files shared per day | Largest shared file | Deadlines per day | Hand-ins per day | Largest hand-in | Phones |
|---|---|---|---|---|---|---|---|---|
| **Student, approved** | 0 | 0 | 0 | none | 0 | 20 | 15 MB | 3 |
| **Lecturer, approved** | 30 | 2 | 20 | 50 MB | 10 | 0 | none | 3 |
| **Administrator, approved** | no limit | no limit | no limit | 50 MB (bucket cap) | no limit | no limit | 15 MB (bucket cap) | 5 |
| **Pending** (any role) | 0 | 0 | 0 | none | 0 | 0 | none | 1 |
| **Rejected / Suspended** | 0 | 0 | 0 | none | 0 | 0 | none | 0 |

Days reset at midnight in Tanzania (`Africa/Dar_es_Salaam`).

What each person can see and do (all enforced by row-level security):

| | Student | Lecturer | Administrator |
|---|---|---|---|
| Own profile | yes | yes | yes |
| Other students' profiles | **never** | class list of own courses only (name + number) | yes |
| Timetable | enrolled courses | courses they teach | all |
| Announcements | published, for their audiences | own (drafts too) + audiences | all |
| Post to the whole campus | no | **no** | yes |
| Post to a course | no | only courses they teach | any |
| Post to a programme | no | programmes containing a course they teach | any |
| Urgent priority | no | own course only, 2 a day | yes |
| Study materials | approved, enrolled courses | own courses | all, can remove |
| Deadlines | enrolled courses + registry calendar | own courses | all |
| Deadline "done" ticks | **own only** | **never** (count only) | **never** (count only) |
| Hand-ins | own only | courses they teach | all |
| Notifications | own only | own only | own only |
| Delivered / opened counts | no | own announcements | all |
| Audit log | no | no | read only |
| Approve accounts, assign courses, suspend | no | no | yes (needs second step) |

Other protections built in:

- **Sign-up** must name a university. Whatever role or status the app sends is ignored: every new account is a
  **pending student** of that university. Only that university's administrator can change that, through
  `approve_account`, which can never create an administrator.
- **Administrators need the second step.** `is_admin()` is true only when the sign-in carries `aal2`
  (set `require_admin_mfa` to `false` in `app_settings` only while bootstrapping).
- **Suspending an account works immediately.** Every policy checks the account status on every request.
- **File sizes are read from storage**, not from what the app claims.
- **The audit log is append-only**: no update, delete or truncate, not even by the database owner.
- Room changes and cancellations **must give a reason**, and a room that is already busy is refused.
- Files are private. A file can be read only when the database row that points to it is visible to the reader.
  Folder rules: `materials/<COURSE>/<lecturer uid>/…`, `submissions/<student uid>/<deadline id>/…`,
  `announcements/<author uid>/…`.

## Set up a new Supabase project

1. Create the project at supabase.com.
2. Install the CLI, then: `supabase login` and `supabase link --project-ref <your-ref>`.
3. `supabase db push` (applies the migration; does **not** run the seed).
4. Add your campuses and their reference data: rows in `universities`, then (per university, optional)
   `programmes`, `courses` (give `university_id` and `short_code`), `programme_courses`, and the registry's
   `university_students` / `university_staff` lists.
5. **Dashboard > Authentication** settings, matching `supabase/config.toml`:
   - Email provider on, **Confirm email** on, OTP length 6, OTP expiry 600 s.
   - Minimum password length 8, no other password rules. On the Pro plan also switch on
     *leaked password protection*.
   - Multi-factor (TOTP) on.
   - Refresh token rotation on. Leave anonymous sign-ins off.
   - Site URL / redirect: `campusconnect://login-callback`.
6. Create the first administrator **of each university**:
   1. Sign up in the app, choosing that university, and verify the code.
   2. In the SQL editor: `select public.bootstrap_first_admin('registry@example.com', 'STAFF-NUMBER');`
      (works only while that university has no administrator).
   3. Sign in, enrol in two-step sign-in (TOTP), sign in again. Only then do admin powers switch on.
7. After that, approve every other account from the app's Admin tab (`approve_account`).

## Run and test locally

```bash
supabase start        # needs Docker
supabase db reset     # migration + seed.sql
supabase test db      # runs supabase/tests/*.test.sql
```

## GitHub Actions

Add these under **Settings > Secrets and variables > Actions**:

| Secret | Used by | Where to get it |
|---|---|---|
| `SUPABASE_ACCESS_TOKEN` | deploy | supabase.com > Account > Access Tokens |
| `SUPABASE_DB_PASSWORD` | deploy | the database password you chose for the project |
| `SUPABASE_PROJECT_ID` | deploy | the project reference (Project settings > General) |
| `KEYSTORE_BASE64` | release | `base64 -w0 my-upload-key.jks` |
| `STORE_PASSWORD`, `KEY_PASSWORD` | release | your upload keystore passwords |

Also create an **Environment** called `production` (Settings > Environments) and add yourself as a
required reviewer, so a person approves every production database change.

`ci.yml` runs everywhere. The deploy workflow runs `ci.yml` first, shows a dry run, and only then pushes.

## Integration status — read before production use

The project now contains a reusable `SupabaseClient` transport for Auth, PostgREST, private Storage uploads/removal, and Edge Function calls. Build-time configuration is available through `SUPABASE_URL` and `SUPABASE_ANON_KEY` Gradle properties/environment variables. However, the existing screens still call the local in-memory `AuthRepository` and `CampusRepository`; those classes have **not yet been migrated to the new client**. Treat this as an integration-ready backend foundation, not a finished live app. Do not enter real student information until this remaining work is completed and tested.

Required next integration steps:

1. Replace `AuthRepository` local account/code maps with Supabase Auth. Never display verification codes in the app. Supabase Auth sign-in is email-based; registration-number sign-in needs a protected lookup/RPC that returns no account details to unauthorized callers.
2. Replace `CampusRepository` in-memory lists and mutation methods with Supabase queries/RPCs and Flow state updates. Keep RLS as the authority; do not fetch all campus data and filter it on the phone.
3. Add secure session persistence/refresh, email OTP verification, account approval waiting state, logout/revocation, and network error handling.
4. Wire course/programme selection to server enrollment and ensure every query is constrained by approved profile, university, and audience policies. A student from Computer Science must never read Human Resources documents or submissions.
5. Add integration tests for student A vs student B, different programmes, different universities, pending users, lecturers, CR posting on behalf of lecturers, and Storage access.
6. Add setup list edit/delete operations through validated database functions and reflect them in `CampusSetupScreen`; handle foreign-key dependencies with a clear confirmation dialog.
7. For campus wipes, invoke `campus-wipe-files` rather than deleting only database rows. The function can report partial Storage failure, so provide a retry/admin audit flow.

The class representative policy is: a CR may post only on behalf of a responsible lecturer. The server must validate the selected lecturer teaches the target course and the CR is authorized by the campus registry; the client picker is not sufficient authorization.


## Timed quizzes (migration `20261026000000_quizzes.sql`)

- Tables: `quizzes`, `quiz_questions`, `quiz_answer_keys`, `quiz_attempts`, `quiz_answers`. Nobody writes them
  directly; every change goes through `create_quiz`, `reschedule_quiz`, `delete_quiz`, `start_quiz_attempt`,
  `save_quiz_answer`, `submit_quiz_attempt`, `grade_quiz_attempt` and `quiz_attempt_score`.
- Students read a quiz's questions only after pressing Start, and never read `quiz_answer_keys`.
- The database clock decides: an attempt ends at start + duration (never after the quiz closes). Ordinary saves
  after that are refused; the final hand-in is accepted for 10 seconds of grace, then the attempt is closed with the
  answers it already had.
- Optional tidy-up: schedule `select public.finalize_expired_quiz_attempts();` every minute (pg_cron or a scheduled
  Edge Function). Nothing depends on it.
- The app's in-memory `CampusRepository` follows the same rules until it is moved to Supabase.

## Document analysis (AI Summaries)

Runs on the phone: text is read from PDFs and photos with on-device text recognition (ML Kit) and from Word,
PowerPoint, Excel, OpenDocument and text files directly; a summary, key topics, objectives and study tips are made
from the document's own sentences. Files are copied into the app's private `files/library` folder and results are
stored in Room. Nothing is uploaded. A language-model analysis could be added later as an Edge Function once the
app signs in with Supabase.
