# Project tour: what every file is for

```
campusconnect-android/
├── SETUP_GUIDE.md                 The step-by-step guide
├── README.md                      Overview
├── local.properties.example       Template for YOUR secret settings (copy to local.properties)
├── build.gradle.kts               Project-level build setup (plugin versions)
├── settings.gradle.kts            Which modules and repositories to use
├── gradle.properties              Build memory and options
├── gradlew, gradlew.bat, gradle/  The Gradle wrapper (builds the app the same way everywhere)
├── .gitignore                     Files Git must never upload (secrets, build output)
│
├── app/
│   ├── build.gradle.kts           App build: libraries, version, signing, reads local.properties
│   └── src/main/
│       ├── AndroidManifest.xml    Permissions, app name, the push service (the only XML file)
│       └── java/com/campusconnect/app/
│           ├── MainActivity.kt    The app's front door; picks the language; handles notification taps
│           ├── CampusApp.kt       Starts when the app starts; creates the notification channel; starts Firebase
│           ├── Util.kt            Date formatting (East Africa Time) and friendly error messages
│           ├── Strings.kt         English -> Kiswahili dictionary and the tr() function
│           ├── data/              TALKS TO THE DATABASE
│           │   ├── Models.kt          Shapes of the data (Profile, ClassSession, Announcement...)
│           │   ├── Repo.kt            One function per database action
│           │   ├── AppContainer.kt    Creates the single Supabase connection
│           │   ├── Cache.kt, CachedList.kt   Offline copy: show saved data first, refresh after
│           │   └── Settings.kt        Remembers the chosen language
│           ├── push/              INSTANT NOTIFICATIONS
│           │   ├── Push.kt            Firebase set-up and phone registration
│           │   ├── CampusMessagingService.kt   Receives messages from Firebase
│           │   └── Notifier.kt        Shows the notification and opens the right screen when tapped
│           ├── sync/SyncWorker.kt Background check every ~15 minutes (fallback without Firebase)
│           └── ui/                WHAT YOU SEE
│               ├── theme/Theme.kt     Colours and text styles, light and dark
│               ├── Components.kt      Reusable pieces: cards, dialogs, pickers, loading and error views
│               ├── ViewModels.kt      The "brains" behind each screen
│               ├── AppRoot.kt         Login state, bottom navigation, screen routes
│               ├── auth/AuthScreens.kt   Sign in, register, awaiting approval
│               ├── HomeScreen.kt, TimetableScreen.kt, AnnouncementScreens.kt,
│               │   MaterialsScreen.kt (also the PDF viewer), CalendarScreen.kt,
│               │   NotificationsScreen.kt, ProfileScreen.kt, AdminScreen.kt
│
├── supabase/
│   ├── migrations/                SQL that builds the database (run in order)
│   ├── tests/                     63 security tests, a test runner and stand-ins for Supabase pieces
│   ├── functions/send-push/       The Edge Function that sends push notifications
│   └── .env.example               Template for the function's secrets
│
├── .github/
│   ├── workflows/                 Robots: build, database tests, signed release, function deploy
│   ├── dependabot.yml             Weekly library update pull requests
│   ├── pull_request_template.md   Checklist shown on every pull request
│   └── CODEOWNERS                 Who must review sensitive files
│
└── docs/                          Extra guides (this one, troubleshooting, glossary, security)
```

## How one feature flows through the code

Example: a lecturer publishes an announcement.

1. `AnnouncementScreens.kt` (ComposeAnnouncementScreen) collects title and message, calls `AnnouncementsVm.publish`.
2. `ViewModels.kt` calls `Repo.createAnnouncement`.
3. `Repo.kt` inserts a row into the `announcements` table.
4. The database checks the **policy**: is this person a lecturer of that course? If not, it refuses.
5. The database **trigger** `fanout_announcement` creates a `notifications` row for each enrolled student.
6. The **webhook** calls the `send-push` function, which asks Firebase to alert each student's phone.
7. The student's phone runs `CampusMessagingService`, `Notifier` shows the alert, and a tap opens the announcement.
