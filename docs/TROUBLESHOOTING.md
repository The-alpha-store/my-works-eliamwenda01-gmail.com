# Troubleshooting

Find your symptom, try the fix, and if it still fails paste the **exact error text** to Claude.

## Android Studio and building

| Symptom | Likely cause | Fix |
|---|---|---|
| "Supabase is not configured" screen | `local.properties` missing or misspelt | Copy `local.properties.example`, fill both Supabase lines, **Sync Project**, run again |
| `Unresolved reference: BuildConfig` | Project not synced, or first build not done | *File > Sync Project with Gradle Files*, then *Build > Rebuild Project* |
| `Unresolved reference ...` on a library function | Library version differs from what the code expects | Paste the error and the line to Claude; mention the version in `app/build.gradle.kts` |
| `SDK location not found` | `sdk.dir` missing | Open the project with *File > Open* in Android Studio; it creates it. Or add `sdk.dir=C:\\Users\\You\\AppData\\Local\\Android\\Sdk` to `local.properties` |
| `Could not resolve io.github.jan-tennert...` | No internet or blocked network | Check your connection; try again; try mobile data |
| `Unsupported class file major version` | Wrong Java | *Settings > Build > Gradle > Gradle JDK*: choose the bundled JDK 17 (jbr-17) |
| Very slow first build | Normal | Wait; later builds are fast |
| `Out of memory` | Low RAM | In `gradle.properties` change `-Xmx2048m` to `-Xmx3072m`; close other programs |
| Phone not listed to run on | USB debugging off or cable is charge-only | Re-enable USB debugging; try another cable; accept the prompt on the phone |

## Database (Supabase)

| Symptom | Likely cause | Fix |
|---|---|---|
| `type "user_role" already exists` | You ran a migration twice | Start fresh: new Supabase project, run the three files once, in order |
| `relation "public.profiles" does not exist` | Files run out of order | Run `...000000_init...` first |
| Register works but no row in `profiles` | File 1 did not finish | Re-check it ran with "Success"; look for the trigger `on_auth_user_created` |
| App shows empty lists for everyone | Normal until the account is approved and enrolled | Part 8: approve, assign, enrol |
| "You are not allowed to do that" | A security rule did its job | You are using an account without the right role or course |
| `permission denied for table ...` | RLS/grants issue | Check the table has policies; re-run the migration on a fresh project |
| Cannot sign in after registering | Email confirmation is on | Authentication > Providers > Email: turn **Confirm email** off for the pilot |
| "Wrong email or password" | Typo, or not registered | Use *New here? Create an account* |

## Uploads, PDFs and offline

| Symptom | Fix |
|---|---|
| "That file is not a PDF" | Choose a real `.pdf` file (the app checks the first bytes) |
| "PDF is larger than 10 MB" | Compress it (many free online PDF compressors) |
| Upload fails with "not allowed" | You are not the lecturer of that course; ask an admin |
| PDF will not open | Re-download while online; ask the lecturer to upload again if the file is damaged |

## Push notifications

See the checklist table in `SETUP_GUIDE.md` Part 5.10. The most common causes are: wrong package name in Firebase
(must be `com.campusconnect.app`), a `FIREBASE_*` value missing in `local.properties`, the webhook header
`x-webhook-secret` not matching `WEBHOOK_SECRET`, the account still pending, or battery optimisation on the phone.

## GitHub and CI

| Symptom | Fix |
|---|---|
| `git push` asks for a password and rejects it | Use `gh auth login`, or a Personal Access Token instead of your password |
| `remote: Permission denied` | Log in to the right GitHub account; check the repo name in `git remote -v` |
| Android CI red at "Build debug APK" | Open the log, read the first line starting with `e:` or `error:`; fix like a local build error |
| Android CI red at "wrapper-validation" | The `gradle/wrapper/gradle-wrapper.jar` was changed. Restore it from the original zip |
| Database tests red | Open the log; lines starting `f|` name the failing check. Do not merge until fixed |
| Release workflow: "Keystore was tampered with, or password was incorrect" | `KEYSTORE_PASSWORD`/`KEY_PASSWORD` secret is wrong, or the Base64 secret is cut off. Re-create both |
| Release workflow waits forever | The `production` environment needs your approval: open the run and click **Review deployments** |
| `environment` not available | Your plan does not offer it for private repos: remove the `environment: production` line in `release.yml` |

## Signing

| Symptom | Fix |
|---|---|
| "App not installed" over an older version | The older version was signed with a different key. Uninstall it first (data is lost) or use the original key |
| Release APK will not install | It is **unsigned**: the `KEYSTORE_*` lines are missing or the path is wrong |
| Forgot keystore password | It cannot be recovered. With Play App Signing, ask Google to reset the upload key. Otherwise you must publish under a new app id |
