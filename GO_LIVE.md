# Going live: ChuoPulse + Supabase + GitHub (exact steps)

## 0. The honest state of this project

| Part | State |
|---|---|
| Database (tables, security rules, quizzes, storage, app-manager functions) | Written. **Not live until you deploy it (steps 1 to 6).** Includes the new `20261027000000_campus_details_maintenance.sql`. |
| Build settings | `SUPABASE_URL` and `SUPABASE_ANON_KEY` are read from a Gradle property, then an environment variable (GitHub secret), then `.env`. A **release build refuses to build** without real values or with `DEMO_MODE_ENABLED=true`. |
| Sign-in screen badge | Green "Backend connected" = this build has real settings. Amber "Local test mode" = it does not. |
| **Sign-in** | **Live (new).** With real keys, email + password sign in through Supabase Auth and the profile is read from `profiles`. Registration, codes, password reset and registration-number sign-in are still local. |
| **Rest of the app data** | **Still on the phone.** `AuthRepository` (sign-in, accounts) and `CampusRepository` (timetable, notices, files, quizzes) keep everything in memory. Only the AI-summary save talks to Supabase. This, not demo mode, is why the app still behaves like a demo. Step 9 is the work that remains. |

Setting the keys makes the build "connected". It does **not** by itself move students, timetables or files into Supabase. Do not enter real student records until step 9 items 1 to 4 are done and tested.

## 1. Create the Supabase project
1. supabase.com, New project, name `chuopulse`. Pick the region closest to your users. Set a strong database password and **save it**.
2. Wait until the project is ready.

## 2. The values you need and where each goes

| Value | Where to find it | Goes into |
|---|---|---|
| Project URL `https://abcd1234.supabase.co` | Project Settings, API | `.env` and GitHub secret `SUPABASE_URL` |
| anon / publishable key | Project Settings, API | `.env` and GitHub secret `SUPABASE_ANON_KEY` |
| Project ref (`abcd1234`) | Project Settings, General | GitHub secret `SUPABASE_PROJECT_ID` |
| Database password | You chose it in step 1 | GitHub secret `SUPABASE_DB_PASSWORD` |
| Access token | supabase.com, Account, Access Tokens | GitHub secret `SUPABASE_ACCESS_TOKEN` |
| service_role / secret key | Project Settings, API | **Nowhere.** Never in the app, `.env` or Git. |

The anon key is meant to be inside the app; the database rules protect the data. The password, access token and service_role key are real secrets.

## 3. Local `.env` (building on your computer)
Copy `.env.example` to `.env` next to `settings.gradle.kts`:
```
SUPABASE_URL=https://abcd1234.supabase.co
SUPABASE_ANON_KEY=eyJ...your anon key...
DEMO_MODE_ENABLED=false
```
`.env` is in `.gitignore`. Rebuild (`./gradlew :app:assembleDebug`). The sign-in badge must turn green. Android Studio: after editing `.env`, run Build > Clean Project, then rebuild.

## 4. GitHub repository secrets
Repository, Settings, Secrets and variables, Actions, New repository secret:

| Secret | Used by |
|---|---|
| `SUPABASE_URL`, `SUPABASE_ANON_KEY` | every Android build |
| `SUPABASE_ACCESS_TOKEN`, `SUPABASE_DB_PASSWORD`, `SUPABASE_PROJECT_ID` | database and function deploy |
| `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD` | signed release (key alias must be `upload`) |

Also: Settings, Environments, New environment, named exactly `production` (the deploy jobs use it). Optionally require yourself as reviewer.

## 5. Deploy the database
GitHub, Actions, "CampusConnect CI, Database and Deployment", Run workflow, tick **deploy_database**. It runs the tests, then `supabase db push`, which applies everything in `supabase/migrations/`. `seed.sql` (fake people) is never sent to production. Later, any push to `main` that changes `supabase/migrations/**` deploys automatically after tests pass.

Check: Supabase, Table Editor shows `universities`, `profiles`, `class_sessions`, `quizzes`...; Storage lists `materials`, `submissions`, `announcements`.

## 6. Supabase dashboard settings (hosted projects do not read `config.toml`)
- Authentication, Providers, Email: enable, **Confirm email on**, minimum password length 8.
- URL Configuration: Site URL `campusconnect://login-callback`; add the same under Redirect URLs.
- Email templates: "Confirm signup" and "Reset password" must include `{{ .Token }}` (the six-digit code the app asks for).
- **SMTP Settings: use your own provider** (Resend, Brevo, SendGrid). Supabase's built-in sender allows only a few emails an hour.

## 7. Create the first app manager (owner) — corrected
The database refuses an account that does not pick a real campus at sign-up (`university_required`), so **do not use "Add user" in the dashboard**. Do this instead:

1. SQL Editor, create the first campus once:
   ```sql
   insert into public.universities (code, name) values ('CUOM', 'Catholic University of Mbeya') on conflict (code) do nothing;
   ```
2. In the app (a build with step 9 item 1 done) sign up with your email, choosing that campus, and enter the emailed code. Until step 9 item 1 exists, create the account in Authentication, Users, "Add user" **with user metadata** `{"university":"CUOM","full_name":"Your Name","id_number":"OWNER-001","is_staff":true}` and Auto Confirm.
3. SQL Editor, run once (replace the email):
   ```sql
   update public.profiles
   set university_id = (select id from public.universities where is_platform),
       role = 'admin', status = 'approved', is_platform_admin = true,
       student_number = null, staff_number = 'OWNER-001', cohort = 'App manager',
       email_verified = true
   where lower(email) = lower('you@example.com');
   ```
4. **Admin second step.** By default the database requires an `aal2` (two-step) session for every admin and app-manager action, and this app has no two-step screen yet, so those actions would be refused. Until you build it, choose one:
   - Safer: build the TOTP enrol/verify screen first (step 9 item 6).
   - Temporary: `update public.app_settings set value = 'false' where key = 'require_admin_mfa';` This lowers security for admins. Switch it back to `'true'` when the screen exists.
5. Create each remaining campus from the app manager screen. A campus's first administrator signs up choosing that campus; the manager then promotes them (`platform_make_campus_admin`).

## 8. Edge function and uploads
Actions, Run workflow, `function_name = campus-wipe-files`. (The dropdown also lists `send-push`; that function is not in this repository, so do not pick it.)

## 9. What still has to be built in the app
The database is ready; the app must talk to it. Both repositories are synchronous and in-memory today, so each step is a refactor to suspend functions plus tests. In this order:
1. **Sign in is done (email + password).** Still to do: sign up, email code, reset password, number lookup, refresh-token storage. Use `SupabaseClient` (`signUp`, `signIn`, `refresh`, `getProfile`), replacing local accounts in `AuthRepository`. Store the refresh token with EncryptedSharedPreferences/Keystore.
2. **Campuses, programmes, courses** from Supabase; owner console via `platform_campus_summary`, `platform_set_maintenance`, `update_campus_details`.
3. **Timetable, announcements, deadlines, notifications**: read from tables, write through RPC/upsert, cache for offline.
4. **Files**: upload to the private buckets, open with signed links (this also fixes PDFs that load forever on other phones: today's file addresses only work on the phone that picked the file).
5. **Quizzes** (`create_quiz`, start, save answer, submit, grade functions in `20261026000000_quizzes.sql`).
6. TOTP enrol/verify screen for administrators, then instrumented tests against a staging project.
7. Maintenance mode is currently enforced by the app screen only. Add a database check if you need it enforced against direct API calls.

## 10. Quick connection test (no app needed)
```
curl "https://abcd1234.supabase.co/rest/v1/universities?select=code,name" \
  -H "apikey: YOUR_ANON_KEY" -H "Authorization: Bearer YOUR_ANON_KEY"
```
A JSON list (`[]` before you add a campus) means project, key and database are reachable. `relation does not exist` means step 5 has not run.

## 11. Signed release
1. `keytool -genkeypair -v -keystore upload-key.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000` (back it up somewhere safe).
2. `base64 -w0 upload-key.jks` (Mac: `base64 -i upload-key.jks`) into `KEYSTORE_BASE64`; passwords into `STORE_PASSWORD`, `KEY_PASSWORD`.
3. `git tag v1.0.0 && git push origin v1.0.0` builds the signed APK and bundle and attaches them to a GitHub release.
4. Missing `SUPABASE_URL`/key, or `DEMO_MODE_ENABLED=true`, stops the release with a clear message.

## 12. Never do
- Commit `.env`, a keystore, the service_role key, the database password or the access token.
- Put the service_role key in the Android app, even "temporarily".
- Turn on `DEMO_MODE_ENABLED` in a release.
- Run `supabase/seed.sql` on a real project.
