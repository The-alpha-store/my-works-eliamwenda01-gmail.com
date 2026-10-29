# Security checklist: before real students use the app

Tick every box.

## Accounts
- [ ] Two-factor authentication is on for GitHub, Google/Firebase and Supabase
- [ ] Passwords are stored only in a password manager
- [ ] Only the people who truly need it have the admin role (check `profiles` where `role = 'admin'`)

## Database
- [ ] All three migrations applied, in order, once
- [ ] Every table shows `rowsecurity = true` (query in Setup Guide Part 3.4)
- [ ] Database tests pass in GitHub (`Database tests` is green)
- [ ] Email confirmation turned **on** for real use, with a proper sender
- [ ] Backups enabled (paid plan) or a regular export routine exists
- [ ] The `materials` storage bucket is **Private**

## Secrets
- [ ] `local.properties` is not in Git (`git status` never shows it)
- [ ] No `service_role` key anywhere under `app/`
- [ ] No `.jks`, `.env` or service-account `.json` in the repository (`git ls-files | grep -E "jks|\.env|service-account"` prints nothing)
- [ ] GitHub secret scanning and push protection are on
- [ ] Firebase API key restricted to the app (optional, Setup Guide 7.10)

## Signing
- [ ] Release keystore created outside the project
- [ ] Two encrypted backups exist; location and passwords recorded in the password manager
- [ ] SHA-256 fingerprint saved
- [ ] Release workflow uses the `production` environment with a required reviewer (or you run releases yourself)

## Privacy and process
- [ ] Written permission from the institution before using real student data
- [ ] Only necessary personal data is collected
- [ ] Lecturers know not to put private information in announcements (they appear in notifications)
- [ ] A named person can disable a compromised account (Admin: set status to suspended in `profiles`)
- [ ] You know the leak plan (Setup Guide 7.9)

## Current-repository additions

- [ ] `SUPABASE_URL` and `SUPABASE_ANON_KEY` are configured for the build; never set a service-role key in Android.
- [ ] Migrate the current `AuthRepository` and `CampusRepository` away from in-memory data before production use.
- [ ] Confirm a Computer Science student cannot read Human Resources course materials, submissions, or private profile details by direct REST calls.
- [ ] Confirm a CR's on-behalf-of lecturer is validated server-side for the course/audience.
- [ ] Deploy and test `campus-wipe-files`; verify database wipe plus object removal, and retry behavior on partial Storage failure.
- [ ] Test programme/course/room edit and delete paths with foreign-key dependencies before exposing them in the admin UI.
