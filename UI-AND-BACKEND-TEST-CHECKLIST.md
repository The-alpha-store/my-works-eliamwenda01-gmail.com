# CampusConnect: pre-test checklist

## UI pass included in this revision

- Main notice details, publish notice, edit notice, upload material, and create deadline workflows use Material 3 modal bottom sheets instead of centered dialogs.
- The sheets use the full available height and keep their form content scrollable; keyboard insets are applied to reduce the chance that fields or action buttons are hidden while typing.
- Small destructive confirmations (for example, canceling a class) remain dialogs because a compact confirmation is more appropriate than a full sheet.
- Existing test tags are retained so UI tests can continue to target the workflows.

## Must be completed before treating this as a live-backend test build

1. Replace the in-memory `AuthRepository` and `CampusRepository` data paths with Supabase-backed implementations. The `SupabaseClient` transport alone does not make app data live.
2. Persist and restore the auth session securely, handle token refresh and sign-out, and route unconfirmed/pending/rejected accounts to the correct screen.
3. Map the authenticated profile to a university, programme, course enrolments and role. Do not use a client-side role check as the security boundary.
4. Verify Row Level Security using at least two distinct test users from different programmes and, ideally, different universities. A Computer Science student must not be able to read Human Resources-only notices, documents, assignments or submissions by changing client filters.
5. Wire upload/download/preview to private Supabase Storage and verify object policies. Keep only the anon/publishable key in the Android app; never put the service-role key in the APK or GitHub secrets exposed to client builds.
6. Test the campus wipe Edge Function against a disposable campus. Confirm it checks the caller's authorization, removes database rows and Storage objects, handles partial failures, and leaves an audit trail. Do not test destructive wipe against real campus data.
7. Finish persistent edit/delete operations for programme, course and room setup. The current setup catalogue is still in-memory and must not be considered durable.
8. Run `gradle :app:testDebugUnitTest`, `gradle :app:lintDebug`, and `gradle :app:assembleDebug` in GitHub Actions or a machine with the Android SDK. Install the resulting debug APK on a physical phone and exercise the flows below.

## Manual UI smoke test

- Sign in, sign out, rotate/recreate the activity, and use Android Back from each main tab.
- Open and dismiss each bottom sheet by the close icon, tapping outside, and swiping down.
- Focus every text field with the keyboard open; verify the last field and primary action can still be reached by scrolling.
- Publish a notice with and without an attachment; verify the responsible-lecturer picker only appears for an eligible course and correctly identifies the lecturer.
- Open a notice with a long body and attachment; verify the sheet scrolls and preview navigation can be exited cleanly.
- Upload a material and create a deadline with validation errors and valid data.
- Check small-screen layout, large text/display scaling, empty states, offline state and snackbar visibility.
- Verify that student screens contain only that student's assigned campus/programme/course data.

## Status

The UI changes in this revision have been applied to source, but an Android build and device-level interaction test have not been run in this environment. Do not describe this archive as production-ready until the automated and manual checks above pass.
