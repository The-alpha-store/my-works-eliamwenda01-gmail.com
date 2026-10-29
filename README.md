# CampusConnect

CampusConnect is an Android (Kotlin + Jetpack Compose) campus app for universities in Tanzania. The project is being moved from a local prototype to a Supabase-backed application.

## What's in this repository

- `app/` — Android application source.
- `app/src/main/java/com/willykez/campconnect/data/supabase/SupabaseClient.kt` — authenticated Supabase Auth, PostgREST, Storage and Edge Function HTTP transport.
- `supabase/migrations/` — database schema, row-level security (RLS), role limits and audit rules.
- `supabase/functions/campus-wipe-files/` — server-side campus wipe + Storage cleanup function.
- `supabase/tests/` — database authorization tests.
- `.github/workflows/` — Android CI, database tests, database deployment, release and Edge Function deployment workflows.
- `docs/` — setup, security and operational notes.

## Important status note

The Supabase database schema and a reusable network client are included, but the existing screens and `CampusRepository` / `AuthRepository` still contain local in-memory prototype behavior. The app is **not yet end-to-end live-backend connected**: do not use it with real student records until the ViewModel/repositories are migrated to the Supabase client and the integration tests pass. The default build disables the demo entry point; this alone does not convert the local repositories into a live backend.

## Privacy model

A student's programme/course selection is not authorization by itself. The server must enforce university membership, approved account status, course enrollment, programme audience and role using RLS on every query. A Computer Science student must not be able to read Human Resources materials, submissions, class lists or private profile data by changing client filters. UI filtering is only for usability; RLS is the security boundary.

Class representatives may publish only when posting on behalf of a selected responsible lecturer, and only for that lecturer's authorized course/audience. The server must validate the relationship rather than trusting the selected lecturer in the form.

## Quick setup

1. Create a Supabase project.
2. Apply migrations with `supabase link --project-ref YOUR_PROJECT_REF` and `supabase db push`.
3. Configure `SUPABASE_URL` and `SUPABASE_ANON_KEY` as Gradle properties or environment variables for the Android build. The publishable/anon key is allowed in a client; **never use the service-role key in the APK**.
4. Deploy `campus-wipe-files` after setting the function's Supabase environment. The function uses the caller's JWT for the database RPC, then uses the service-role key only server-side to delete Storage objects.
5. Run `supabase test db` and the Android CI before using a hosted project.

See [`docs/BACKEND.md`](docs/BACKEND.md) for the full configuration and security checklist.

## Processed documents and offline summaries

- `ProcessedDocumentEntity`, `ProcessedDocumentDao`, `AppDatabase`, and `ProcessedDocumentRepository` provide Room-backed metadata and summary storage, reactive Flow queries, targeted course queries, keyword search, and local-first writes.
- On first database creation, the local library is seeded with three clearly labelled academic sample summaries (Algorithms, Database Normalization, TCP Congestion Control). They are local demonstration records, not university records.
- The new **AI Summaries (Room DB)** shortcut is available from Materials, and the storage icon in the top bar opens the processed-summary library. The library supports search, course/category filters, full-analysis bottom sheet, copy/share, and local deletion.
- Appearance preference is stored locally and can be set to System, Light, or Dark from Profile. The top-bar sun/moon action toggles light/dark.
- `supabase/migrations/20261024000000_processed_documents.sql` adds cloud metadata storage with RLS. Students can read only processed documents for courses in which they are enrolled; lecturers can read courses they teach; authors/admins have the scoped management policies. Apply migrations using `supabase db reset` locally or the project's established migration deployment process.
- Cloud save requires the signed-in Supabase access token and the authenticated uploader UUID. The repository saves to Room first and reports `LOCAL_ONLY` if cloud configuration/session details are unavailable.

### Current limitations to resolve before a production test

- The existing CampusConnect login and campus repositories are still not fully migrated from in-memory prototype state to Supabase. Do not interpret the new document repository as proof that the whole app is live-backend connected.
- The current app does not yet contain the requested `DocuReadAnalysisScreen` or a production AI extraction pipeline. The new processed-summary screen is ready to display and manage records, but the document picker/AI-analysis flow must call `ProcessedDocumentRepository.save(...)` with the real document metadata, Supabase session token and uploader UUID.
- Local sample summaries are global demo records. Before a production rollout, scope the Room query to the current user's permitted course codes and clear or migrate cached data on sign-out/account switch. Server-side RLS remains the actual cloud security boundary.
- The source URI is intentionally not synced to Supabase; device content URIs are local-only and are not portable between devices. Cloud document viewing should use private Storage paths and signed URLs instead.
- Android compilation and Robolectric tests were not executed in this environment because the archive does not include a Gradle wrapper JAR and no system Gradle executable is available. GitHub Actions CI is the next verification step.

## ChuoPulse upgrade notes

- App name: **ChuoPulse** (change it in `util/AppBrand.kt` and `res/values/strings.xml`). Package / applicationId: `com.willykez.campconnect`.
- New: animated splash, campus **offline (maintenance) mode** (app manager switches it per campus; every user of that campus sees a locked maintenance screen), editable campus details (manager: all fields; campus admin: contact details), admin/manager **add a person** with email + starting password, top-bar profile dropdown menu, cloud PDFs download to cache with timeouts instead of loading forever.
- Not compiled here (no Android SDK in the authoring environment): run `./gradlew :app:assembleDebug :app:testDebugUnitTest` before use.
