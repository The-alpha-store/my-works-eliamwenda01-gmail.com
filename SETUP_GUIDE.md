# CampusConnect: Complete Beginner Setup Guide

This guide takes you from **a zip file on your computer** to **a working app on your phone**, with a database, push notifications, automatic builds on GitHub, and safely stored signing keys.

You do not need to understand everything first. Follow the steps in order, copy the commands exactly, and read the "What this does" notes when you want to learn why.

> **Honest heads-up.** The SQL database files in this project were tested on a real PostgreSQL database (63 automatic checks pass). The Android code was written carefully but could **not** be compiled in the environment where it was created. On your first build you may see a few small compile errors. That is normal; Part 9 explains how to read and fix them, and you can paste any error to Claude to get a targeted fix.
>
> Websites such as Supabase, Firebase and GitHub change their menus now and then. If a button has a slightly different name, look for the closest match.

---

## Table of contents

| Part | What you do | Time |
|---|---|---|
| 0 | How this guide works, and what you are building | 5 min |
| 1 | Install tools and create accounts | 45 min |
| 2 | Unzip and open the project | 15 min |
| 3 | **Database setup (Supabase)** | 45 min |
| 4 | **Connect the app to the database** | 20 min |
| 5 | **Firebase push notifications** | 60 min |
| 6 | **GitHub repository and CI/CD** | 60 min |
| 7 | **App signing keys and security** | 45 min |
| 8 | First run: set up your campus and test everything | 45 min |
| 9 | Build errors, updates and what to do next | read when needed |

Extra documents in the `docs/` folder: `PROJECT_TOUR.md` (what every file does), `TROUBLESHOOTING.md`, `GLOSSARY.md`, `SECURITY_CHECKLIST.md`.

---

## Part 0: How this guide works

### What you are building

CampusConnect is an Android app for a university:

- **Students** see their timetable, announcements, study materials (PDF) and deadlines.
- **Lecturers** publish announcements, change the timetable, upload PDFs and set deadlines.
- **Administrators** approve new accounts and set up programmes, courses and classes.

The app has two halves:

```
 Your phone (the app)  <--internet-->  Supabase (the "backend")
 Kotlin + Jetpack Compose              PostgreSQL database, login system, file storage
                                                |
                                                +--> Edge Function --> Firebase --> push notification to phones
```

- **Supabase** stores the data and decides who may see what (the "security rules").
- **Firebase** only delivers instant notifications to phones.
- **GitHub** stores your code and builds the app automatically.

### How to read the commands

A grey block is something you type or paste:

```bash
echo "hello"
```

- Lines that start with `#` are **comments**: notes for you, the computer ignores them.
- **Windows users:** open **PowerShell** (search "PowerShell" in the Start menu) unless told otherwise. **Mac/Linux users:** open **Terminal**.
- When you see `YOUR-SOMETHING` in capital letters, replace it with your own value.

### Two kinds of secret (very important)

| Kind | Examples | Can be in your app? | Can be on GitHub? |
|---|---|---|---|
| **Public** | Supabase project URL, Supabase `anon`/publishable key | Yes (by design) | Only as a GitHub *secret*, still best kept out of the code |
| **Secret** | Supabase `service_role` key, database password, Firebase service-account file, **signing keystore + passwords** | **Never** | **Never** in the code. Only as encrypted GitHub *secrets* |

Why is the `anon` key safe? Because the database refuses anything the user is not allowed to do (Row Level Security). The `service_role` key bypasses those rules, so it must never leave your server.

---

## Part 1: Install tools and create accounts

### 1.1 Create three free accounts

1. **GitHub**: https://github.com/signup
2. **Supabase**: https://supabase.com (click *Start your project*, sign in with GitHub)
3. **Google account** for Firebase: https://console.firebase.google.com (any Gmail works; use a personal one, not a school-managed account, because school accounts often block key creation)

> **Student tip:** Because you are a university student, apply for the **GitHub Student Developer Pack** (https://education.github.com/pack). It gives you GitHub Pro for free, which unlocks private-repository protections used in Part 6.

### 1.2 Install the programs

| Program | Why | Where |
|---|---|---|
| **Android Studio** (latest stable) | Builds and runs the app | https://developer.android.com/studio |
| **Git** | Saves versions of your code and talks to GitHub | https://git-scm.com/downloads |
| **Node.js (LTS)** | Lets you run the Supabase command-line tool with `npx` | https://nodejs.org |
| **GitHub CLI** (optional but recommended) | Easiest way to log in to GitHub from the terminal | https://cli.github.com |

Install each with the default options. Android Studio will download extra components on first launch (the Android SDK). Let it finish; it needs a few GB.

### 1.3 Check that everything installed

Open a **new** terminal and run each line. Each should print a version number.

```bash
git --version
node --version
npx --version
```

**What this does:** `--version` asks a program to say who it is. If you get "command not found", close the terminal, open a new one, and try again. If it still fails, reinstall that program.

---

## Part 2: Unzip and open the project

1. Create a folder for your coding projects, for example `C:\Projects` (Windows) or `~/Projects` (Mac/Linux).
2. Unzip `campusconnect-android.zip` into it. You should get a folder named `campusconnect-android`.
3. **Avoid** unzipping into OneDrive, Desktop or a folder with spaces or very long paths. Those cause strange build errors.

Open the folder and check that you can see these items:

```
campusconnect-android/
├── SETUP_GUIDE.md          <- this guide
├── README.md
├── app/                    <- the Android app source code
├── supabase/               <- SQL files, tests and the push Edge Function
├── docs/                   <- extra guides
├── .github/                <- automatic build workflows
├── local.properties.example
├── build.gradle.kts, settings.gradle.kts, gradlew ...
```

### 2.1 Open in Android Studio

1. Start Android Studio.
2. Click **Open** and choose the `campusconnect-android` folder (the one containing `settings.gradle.kts`).
3. If asked "Trust project?", click **Trust Project**.
4. Wait for the bottom bar to finish "Gradle sync". The first sync downloads many libraries and can take 5 to 15 minutes.

> Do not run the app yet. It needs the database settings from Part 4 first.

---

## Part 3: Database setup (Supabase)

You will create the database, run three SQL files that build everything, and check it worked.

### 3.1 Create the Supabase project

1. Go to https://supabase.com/dashboard and click **New project**.
2. **Organization:** pick the default one.
3. **Name:** `campusconnect`.
4. **Database Password:** click **Generate a password**, then **copy it into a password manager or a private note**. You will not see it again, and you need it for the CLI in Part 5.
5. **Region:** choose the closest to Tanzania (for example *South Africa (Cape Town)* if offered, otherwise *Mumbai* or *Frankfurt*). A closer region means a faster app.
6. Click **Create new project** and wait about two minutes.

### 3.2 Understand what the SQL files do

Open the folder `supabase/migrations/`. You will run these **in this exact order**:

| Order | File | What it builds |
|---|---|---|
| 1 | `20261019000000_init_campusconnect.sql` | All tables (people, courses, timetable, announcements, notifications, materials, deadlines), security rules (RLS), automatic notifications, the audit log, and the private PDF storage bucket |
| 2 | `20261019000100_staff_visibility.sql` | Lets everyone see the names of lecturers/admins (so announcements show "posted by") |
| 3 | `20261020000000_deadlines_materials_push.sql` | Notifications for new deadlines and PDFs, a 24-hour deadline reminder job, and the functions that register phones for push |

Why "migrations"? Each file is one step in building the database. Applying them in order always gives the same result, and you can keep them in Git as a history.

#### A reading guide to file 1 (so the code is not scary)

You do not need to memorise it. Here is how to read it:

```sql
create type public.user_role as enum ('student', 'lecturer', 'admin');
```
**Line by line:** `create type` makes a new kind of value. `enum` means "one of this fixed list". So a person's role can only ever be `student`, `lecturer` or `admin`; the database refuses anything else.

```sql
create table public.profiles (
  id        uuid primary key references auth.users (id) on delete cascade,
  full_name text not null,
  role      public.user_role not null default 'student',
  status    public.account_status not null default 'pending',
  ...
);
```
**Line by line:**
- `create table public.profiles` makes a table named `profiles` (one row per person).
- `id uuid primary key` is a unique ID. `references auth.users (id)` ties each profile to a login account that Supabase manages. `on delete cascade` means: if the login is deleted, delete the profile too.
- `not null` means the value is required.
- `default 'student'` / `default 'pending'` means: unless told otherwise, every new person starts as a **pending student**. Nobody can make themselves an admin by sending a different value.

```sql
alter table public.announcements enable row level security;

create policy announcements_select on public.announcements for select to authenticated
  using ( ... who may read ... );
```
**Line by line:** `enable row level security` switches on the rule "nobody can touch this table unless a policy allows it". A `policy` is one allowed action. `for select` = reading, `to authenticated` = only logged-in users, and `using (...)` is the condition. This is where "a student may only read announcements for their own courses" is enforced, **inside the database**, so it cannot be bypassed by editing the app.

```sql
create trigger announcements_fanout
  after insert or update on public.announcements
  for each row execute function public.fanout_announcement();
```
**Line by line:** a `trigger` runs a function automatically. Here, whenever an announcement is published, the database creates one notification row for each student in that audience. The app never has to do it.

### 3.3 Run the three SQL files

For **each** file, in order:

1. In the Supabase dashboard, click **SQL Editor** in the left menu.
2. Click **New query**.
3. On your computer, open the SQL file with a text editor (Notepad, VS Code). Press **Ctrl+A** (select all), **Ctrl+C** (copy).
4. Click in the Supabase editor, **Ctrl+V** (paste).
5. Click **Run** (or press Ctrl+Enter).
6. You should see **"Success. No rows returned"**.

**Expected messages**

- In file 3 you may see a notice: `pg_cron not available`. That is fine; you will enable it in step 3.6.
- If you see a **red error**, stop. Read it and check `docs/TROUBLESHOOTING.md`. The most common cause is running the files out of order or running one twice.

> **Do not run a file twice.** They are not designed to repeat. If you must start over: *Project Settings > General > Delete project*, create a new one, and begin again (fine while you are learning).

### 3.4 Check that it worked

1. Click **Table Editor** in the left menu. You should see tables such as `profiles`, `courses`, `class_sessions`, `announcements`, `notifications`, `materials`, `assignments`, `device_tokens`, `audit_logs`.
2. Click **Storage**. You should see a bucket named **materials** marked **Private**.
3. In **SQL Editor** run this query to confirm security is on for every table:

```sql
select tablename, rowsecurity
from pg_tables
where schemaname = 'public'
order by tablename;
```

**What this does:** it lists every table and whether Row Level Security is on. Every row should say `true`. If any says `false`, tell Claude before going further.

### 3.5 Authentication settings

1. Click **Authentication** in the left menu, then **Sign In / Providers** (older menus: *Providers*).
2. Make sure **Email** is enabled.
3. Find **Confirm email**.
   - **For your pilot/testing:** turn it **off**. The app has no screen for the "click this link" step, so with it on, users would have to confirm by a web link first.
   - **For real production:** turn it on and set up a proper Site URL and a custom email sender first.
4. Click **Save**.

### 3.6 Turn on the deadline reminder job (optional but recommended)

1. Click **Database**, then **Extensions**.
2. Search for `pg_cron` and switch it **on**.
3. Go to **SQL Editor**, **New query**, paste and run:

```sql
select cron.schedule(
  'deadline-reminders',
  '*/15 * * * *',
  'select public.send_deadline_reminders()'
);
```

**Line by line:** `cron.schedule` creates a repeating job. `'deadline-reminders'` is its name. `'*/15 * * * *'` means "every 15 minutes". The last text is the SQL to run: it finds deadlines due within 24 hours and notifies the enrolled students once.

### 3.7 (Optional) Run the security tests

The file `supabase/tests/rls_tests.sql` runs 63 checks, for example *"a student cannot make themselves an admin"* and *"a student cannot read another course's PDFs"*. It creates fake users and **rolls everything back** at the end.

- **Best way:** let GitHub run it for you automatically (Part 6). It runs on a clean test database every time you change a SQL file.
- **On your own Supabase project:** only do this on a throw-away project, never on one with real users. Paste the file into the SQL Editor and click **Run**; the results table at the end should show `true` in every `passed` column. If creating the fake users fails on Supabase, skip it: the GitHub version is the official check.

### 3.8 Create your own admin account (after Part 4)

Nobody is an admin at the start. After you register in the app (Part 8), you promote **yourself** once:

```sql
update public.profiles
   set role = 'admin', status = 'active'
 where email = 'YOUR-EMAIL@example.com';
```

**Line by line:** `update public.profiles` = change rows in the profiles table; `set role = 'admin', status = 'active'` = make them an approved admin; `where email = '...'` = but **only** the row with your email. **Never forget the `where` line**, or you would change everyone.

This works in the SQL Editor because the editor runs as the database owner, which the security rules allow. A normal app user cannot do it.

---

## Part 4: Connect the app to the database

This is how the app source code finds your Supabase project.

### 4.1 Find your two connection values

1. In the Supabase dashboard open **Project Settings** (gear icon), then **API** (or **API Keys**).
2. Copy the **Project URL**. It looks like `https://abcdefghijkl.supabase.co`.
3. Copy the **anon public** key (newer projects may call it the **publishable** key). It is a long text.

> **Do not copy** the `service_role` / `secret` key. If you ever paste that into the app, anyone could download your whole database.

### 4.2 Create `local.properties`

In the project folder you will find `local.properties.example`.

1. **Copy** it and name the copy exactly `local.properties` (no `.example`).
   - Android Studio may have already created a `local.properties` containing a line `sdk.dir=...`. If so, **keep that line** and add the lines below to the same file.
2. Open it and fill in your values:

```properties
SUPABASE_URL=https://abcdefghijkl.supabase.co
SUPABASE_ANON_KEY=eyJhbGciOi...your-long-public-key...
```

**Line by line:**
- Each line is `NAME=value` with **no spaces** around `=` and **no quotes**.
- `SUPABASE_URL` is where your database lives.
- `SUPABASE_ANON_KEY` is the public key that lets the app *ask* the database; the security rules then decide what it may do.
- You can leave the `FIREBASE_*` lines empty for now. Without them the app still works (it checks for news every ~15 minutes instead of instantly).

`local.properties` is listed in `.gitignore`, so Git will **never** upload it. That is intentional.

### 4.3 How the code uses these values (so you understand the connection)

Follow the trail through the source code:

1. **`app/build.gradle.kts`** reads `local.properties` (or environment variables on GitHub) and turns each name into a constant called `BuildConfig.SUPABASE_URL` and `BuildConfig.SUPABASE_ANON_KEY`:
   ```kotlin
   buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL")}\"")
   ```
2. **`data/AppContainer.kt`** creates the one shared connection:
   ```kotlin
   val client = createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) {
       install(Auth)       // login
       install(Postgrest)  // reading/writing tables
       install(Storage)    // PDF files
   }
   ```
3. **`data/Repo.kt`** has one function per action, for example:
   ```kotlin
   suspend fun announcements(): List<Announcement> =
       client.from("announcements").select(...)
   ```
   `from("announcements")` is the table name from your SQL file. This is the link between the SQL you ran and the app.
4. **`ui/ViewModels.kt`** calls the repository and keeps the result for the screens, and **`ui/*.kt`** draws the screens.

If you rename a table or column in SQL, you must rename it in `data/Models.kt` and `data/Repo.kt` too.

### 4.4 Run the app for the first time

1. In Android Studio, click **Sync Project with Gradle Files** (the elephant icon) after saving `local.properties`.
2. Choose where to run:
   - **Real phone (best):** on the phone open *Settings > About phone* and tap **Build number** 7 times, then *Developer options > USB debugging* ON. Plug in the USB cable and accept the prompt on the phone.
   - **Emulator:** *Device Manager > Create device* (a Pixel with API 34 is fine).
3. Click the green **Run** triangle.
4. You should see the **CampusConnect sign-in screen**.

If you instead see **"Supabase is not configured"**, `local.properties` is missing, misspelled, or you did not sync. Fix it and run again.

### 4.5 Prove the connection works

1. Tap **New here? Create an account**, enter a name, an email and a password of at least 8 characters, and tap **Create account**.
2. You should see **"Awaiting approval"**.
3. In Supabase, open **Table Editor > profiles**. Your new row is there with `status = pending`. 

That row appearing proves: app, internet, login system and database are all connected.

---

## Part 5: Firebase push notifications

### 5.1 What you are setting up

Without Firebase the app still works: it checks for news about every 15 minutes in the background. With Firebase, notifications arrive in **seconds**. The chain is:

```
Lecturer publishes  ->  database adds a notification row  ->  Database Webhook
   ->  Edge Function "send-push"  ->  Firebase Cloud Messaging  ->  the student's phone
```

You need **four small pieces**:

1. A Firebase project with your Android app registered (gives 4 values for the app).
2. A service-account key (lets *your server* send messages; **secret**).
3. The Edge Function deployed to Supabase.
4. A Database Webhook that calls the function whenever a notification is created.

Firebase Cloud Messaging is free.

### 5.2 Create the Firebase project

1. Go to https://console.firebase.google.com and click **Create a project** (or **Add project**).
2. **Name:** `campusconnect` and click **Continue**.
3. **Google Analytics:** you can switch it **off** (not needed). Click **Create project**, then **Continue**.

### 5.3 Register the Android app

1. On the project home page click the **Android icon** (or *Add app > Android*).
2. **Android package name:** type exactly `com.campusconnect.app`. This must match `applicationId` in `app/build.gradle.kts`. One wrong letter breaks push.
3. **App nickname:** `CampusConnect` (optional). Leave SHA-1 empty for now.
4. Click **Register app**.
5. Click **Download google-services.json**. **Save it somewhere private.**
   - You do **not** copy this file into the project. This project reads the values from `local.properties` instead, so the app still builds if Firebase is not set up. (If you do put the file in the project folder by accident, `.gitignore` stops Git from uploading it.)
6. Click **Next** through the remaining screens until you can click **Continue to console**. (Ignore the instructions about adding Gradle plugins.)

### 5.4 Copy the four values into `local.properties`

Open the downloaded `google-services.json` in a text editor. Find these four things:

```json
{
  "project_info": {
    "project_number": "123456789012",        <-- FIREBASE_SENDER_ID
    "project_id": "campusconnect-abc12"      <-- FIREBASE_PROJECT_ID
  },
  "client": [{
    "client_info": {
      "mobilesdk_app_id": "1:123456789012:android:0a1b2c3d4e5f"   <-- FIREBASE_APP_ID
    },
    "api_key": [{ "current_key": "AIzaSy..." }]                   <-- FIREBASE_API_KEY
  }]
}
```

Put them in `local.properties`:

```properties
FIREBASE_APP_ID=1:123456789012:android:0a1b2c3d4e5f
FIREBASE_API_KEY=AIzaSy...
FIREBASE_PROJECT_ID=campusconnect-abc12
FIREBASE_SENDER_ID=123456789012
```

Then in Android Studio click **Sync Project**, and run the app again. When you sign in, the app registers your phone's push address (called a *token*) in the `device_tokens` table.

**Check:** in Supabase *Table Editor > device_tokens* you should see one row after you log in with an **approved** account on a phone with Google Play Services. (Pending accounts are refused by design.)

### 5.5 Create the service-account key (the secret one)

This key lets your **server** send messages. Treat it like a password.

1. In Firebase click the **gear icon > Project settings > Service accounts** tab.
2. Click **Generate new private key**, confirm, and a `.json` file downloads.
3. Move it to a private folder **outside** the project, for example `C:\Secrets\campusconnect-fcm.json`.
4. **Never** upload it to GitHub, send it on WhatsApp, or put it in the app.

Now squash the file onto a single line (needed for the next step). Run one of these, replacing the path:

```bash
# Mac/Linux/Windows with Python installed
python3 -c "import json;print(json.dumps(json.load(open('/path/to/campusconnect-fcm.json'))))"
```

Copy the single long line it prints.

### 5.6 Install and log in to the Supabase command-line tool

In the terminal, go to the project folder:

```bash
cd path/to/campusconnect-android
npx supabase@latest login
```

**What this does:** `npx` runs a tool without installing it permanently. `supabase login` opens your browser and asks you to approve. Say yes.

Find your **project ref**: it is the first part of your project URL (`https://abcdefghijkl.supabase.co` gives `abcdefghijkl`). Then link the folder to the project:

```bash
npx supabase@latest link --project-ref abcdefghijkl
```

It asks for the **database password** you saved in Part 3.1.

### 5.7 Store the secrets on Supabase

1. Make a copy of `supabase/.env.example` named `supabase/.env` (it is git-ignored).
2. Open it. Replace the two values:

```bash
WEBHOOK_SECRET=PUT-A-LONG-RANDOM-STRING-HERE
FCM_SERVICE_ACCOUNT='PASTE-THE-ONE-LINE-JSON-HERE'
```

- `WEBHOOK_SECRET` is a password only you and the webhook share. Generate one: `python3 -c "import secrets;print(secrets.token_urlsafe(32))"`. **Write it down**, you need it again in step 5.9.
- Keep the **single quotes** around the JSON.

3. Upload the secrets:

```bash
npx supabase@latest secrets set --env-file supabase/.env
```

**What this does:** it stores the two values encrypted on Supabase. The function reads them while running; they never go into your code.

4. Delete `supabase/.env` and the downloaded service-account `.json` from your normal folders once you have them safely in your password manager, or at minimum keep them out of the project.

### 5.8 Deploy the Edge Function

```bash
npx supabase@latest functions deploy send-push --no-verify-jwt
```

**Line by line:** `functions deploy send-push` uploads the code in `supabase/functions/send-push/index.ts`. `--no-verify-jwt` turns off Supabase's default login check because the database (not a person) calls it; the function protects itself with `WEBHOOK_SECRET` instead.

If it complains about **Docker**, add `--use-api` to the end of the command.

You should see "Deployed Function send-push".

### 5.9 Create the Database Webhook

1. Supabase dashboard > **Database** > **Webhooks** (it may be under *Integrations*). Click **Create a new hook**.
2. Fill in:
   - **Name:** `send-push`
   - **Table:** `notifications`
   - **Events:** tick **Insert** only
   - **Type of hook:** **Supabase Edge Functions**
   - **Edge Function:** choose `send-push`
   - **HTTP Headers:** click **Add header**, name `x-webhook-secret`, value = your `WEBHOOK_SECRET` from step 5.7
3. Click **Create webhook**.

### 5.10 Test push end to end

1. You need **two accounts**: an approved lecturer (assigned to a class) and an approved student enrolled in it. Part 8 shows how.
2. Put the app on a **real phone** and sign in as the student. Allow notifications when asked.
3. Close the app completely (swipe it away).
4. On another phone or emulator, sign in as the lecturer and publish an announcement to that course.
5. Within a few seconds the student's phone shows a notification. Tapping it opens the announcement.

**If nothing arrives**, check in this order:

| Check | Where |
|---|---|
| Is there a row for the student? | Table Editor > `device_tokens` |
| Was a notification row created? | Table Editor > `notifications` |
| Did the webhook fire? | Database > Webhooks > your hook > recent requests |
| Did the function run or fail? | Edge Functions > `send-push` > **Logs** |
| Are notifications allowed for the app? | Phone Settings > Apps > CampusConnect |
| Cheap phones (Tecno, Infinix, Xiaomi, Oppo) | Turn off battery optimisation for the app; these phones often block background messages |

### 5.11 Good to know

- **One message per student.** The database creates one notification row per student and the webhook fires for each. That is perfect for a class of hundreds. For thousands at once you would batch messages; do that later.
- **If a student's phone has no Google Play Services**, the 15-minute background check still delivers the news (just slower).
- **Messages are data-only**, so the app builds the notification and can open the right screen when tapped.
- **Privacy:** notification text includes the announcement title and the first 140 characters. Tell lecturers not to put private information in announcements.

---

## Part 6: GitHub repository and CI/CD

**CI/CD** means "robots check and build your code every time you save it to GitHub". You get a ready-to-install APK and automatic security-test results without doing anything by hand.

### 6.1 Tell Git who you are (once per computer)

```bash
git config --global user.name "Your Name"
git config --global user.email "you@example.com"
git config --global init.defaultBranch main
```

**Line by line:** the first two put your name and email on every save ("commit"); use the same email as your GitHub account. The third makes new projects start on a branch called `main`.

### 6.2 Check that no secrets are about to be saved

Before the first commit, open `.gitignore` in the project folder and confirm it contains these lines (it already does):

```
/local.properties
*.jks
*.keystore
google-services.json
*service-account*.json
.env
supabase/.env
```

**What this does:** Git ignores any file matching these patterns, so passwords and keys never get uploaded. Now run:

```bash
cd path/to/campusconnect-android
git init
git status
```

Read the list under "Untracked files". **You must NOT see** `local.properties`, any `.jks`, `.env`, or a service-account `.json`. If you do, stop and fix `.gitignore` first.

### 6.3 Make your first commit

```bash
git add .
git commit -m "Initial commit: CampusConnect app, database and CI"
```

**Line by line:** `git add .` marks every (non-ignored) file to be saved. `git commit -m "..."` saves a snapshot with a message describing it.

### 6.4 Create the GitHub repository and upload

**Easiest way, with the GitHub CLI:**

```bash
gh auth login
gh repo create campusconnect --private --source=. --remote=origin --push
```

**Line by line:** `gh auth login` connects the terminal to your GitHub (choose *GitHub.com > HTTPS > Login with a web browser*). The second line creates a repo named `campusconnect`, makes it **private**, uses the current folder, names the remote `origin`, and uploads (`--push`).

**Without the CLI:**

1. On github.com click **+ > New repository**. Name `campusconnect`, choose **Private**, do **not** tick "Add a README". Click **Create repository**.
2. Copy the two lines GitHub shows under "push an existing repository":

```bash
git remote add origin https://github.com/YOUR-USERNAME/campusconnect.git
git push -u origin main
```

When asked for a password, GitHub no longer accepts your account password. Create a **Personal access token** (GitHub > Settings > Developer settings > Personal access tokens > Fine-grained tokens, repository access to this repo only, permission *Contents: Read and write*) and paste that instead.

> **Private vs public:** keep it private while you are learning. If you later make it public (good for a portfolio), first search the whole history for secrets (Part 7.9).

### 6.5 Add the secrets the robots need

GitHub robots must not read your `local.properties`, so you give them encrypted copies.

1. Open your repository on github.com.
2. **Settings > Secrets and variables > Actions > New repository secret**.
3. Add each secret below (name exactly as written, then paste the value):

| Secret name | Where the value comes from | Needed for |
|---|---|---|
| `SUPABASE_URL` | Supabase Project URL (Part 4.1) | builds |
| `SUPABASE_ANON_KEY` | Supabase anon/publishable key | builds |
| `FIREBASE_APP_ID` | google-services.json (Part 5.4) | builds (optional) |
| `FIREBASE_API_KEY` | same | builds (optional) |
| `FIREBASE_PROJECT_ID` | same | builds (optional) |
| `FIREBASE_SENDER_ID` | same | builds (optional) |
| `KEYSTORE_BASE64` | Part 7.5 | signed releases |
| `KEYSTORE_PASSWORD` | Part 7.2 | signed releases |
| `KEY_ALIAS` | Part 7.2 | signed releases |
| `KEY_PASSWORD` | Part 7.2 | signed releases |
| `SUPABASE_ACCESS_TOKEN` | supabase.com/dashboard/account/tokens | optional function deploy |
| `SUPABASE_PROJECT_REF` | your project ref | optional function deploy |

You can add the signing ones later in Part 7.

### 6.6 What each workflow does

All files are in `.github/workflows/`. A workflow is a recipe in YAML. Here is how to read `android-ci.yml`:

```yaml
on:
  push:
    branches: [main]
  pull_request:
```
**Meaning:** run this recipe when code is pushed to `main`, and on every pull request (a proposed change).

```yaml
permissions:
  contents: read
```
**Meaning:** the robot may only *read* your code. Giving robots the smallest permissions is a core security habit.

```yaml
- uses: actions/checkout@v4
- uses: gradle/actions/wrapper-validation@v4
- uses: actions/setup-java@v4
  with:
    distribution: temurin
    java-version: 17
```
**Meaning:** download the code; check the Gradle wrapper file was not tampered with; install Java 17 (the app needs it).

```yaml
- name: Build debug APK
  env:
    SUPABASE_URL: ${{ secrets.SUPABASE_URL }}
  run: ./gradlew :app:assembleDebug --stacktrace
```
**Meaning:** hand the secret to the build as an environment variable, then run the build command. `${{ secrets.NAME }}` is how a workflow reads a GitHub secret; GitHub hides it in the logs.

```yaml
- uses: actions/upload-artifact@v4
  with:
    name: campusconnect-debug-apk
    path: app/build/outputs/apk/debug/*.apk
```
**Meaning:** keep the finished APK so you can download it.

| Workflow | Runs when | Result |
|---|---|---|
| `android-ci.yml` | every push and pull request | builds the app; attaches a debug APK |
| `database-tests.yml` | when anything in `supabase/` changes | applies all SQL to a clean PostgreSQL and runs the 63 security tests; a failure blocks the change |
| `release.yml` | when you push a tag like `v0.2.0` | builds a **signed** APK and AAB and publishes a GitHub Release |
| `deploy-push-function.yml` | when you click *Run workflow* | redeploys the push Edge Function |

### 6.7 See it run

1. On github.com open the **Actions** tab. After your first push, a run called **Android CI** appears.
2. Yellow circle = running, green tick = passed, red cross = failed. Click a run, then a step, to read the log.
3. When it is green, scroll to **Artifacts** and download `campusconnect-debug-apk`. Unzip it; the `.apk` installs on a phone.

> **If the build is red on the first run,** read the first red line in the log. Most often it is a code error from Part 9, or a missing secret. Paste the error to Claude.

### 6.8 Best practices checklist

Do these once. Some need GitHub Pro (free with the Student Pack) for private repositories.

1. **Protect `main`:** *Settings > Branches (or Rules > Rulesets) > Add rule* for `main`:
   - Require a pull request before merging.
   - Require status checks to pass: select **Android CI** and **Database tests**.
   - Block force pushes and deletion.
2. **Work in branches, not on `main`:**

```bash
git switch -c feature/add-swahili-fix      # make and switch to a new branch
# ...edit files...
git add .
git commit -m "Fix Swahili wording on home screen"
git push -u origin feature/add-swahili-fix
```
   Then click **Compare & pull request** on GitHub. The robots check it; if green, click **Merge**.
3. **Turn on security scanning:** *Settings > Code security*: enable **Dependabot alerts**, **Dependabot security updates**, and **Secret scanning / Push protection** where available. Push protection blocks you from pushing something that looks like a key.
4. **Dependabot updates** are already configured in `.github/dependabot.yml`; it opens weekly pull requests for library updates. Merge them when CI is green.
5. **Never commit directly after a secret slip.** If you ever commit a secret, deleting it in a new commit is **not enough**: it stays in history. Rotate the secret (Part 7.9).
6. **CODEOWNERS:** edit `.github/CODEOWNERS` and replace `@YOUR-GITHUB-USERNAME` with your username, so changes to workflows and SQL need your review.
7. **Release approvals:** *Settings > Environments > New environment* named `production`, tick **Required reviewers**, add yourself. Releases then wait for your click before they can use the signing key. (If your plan does not offer this for private repos, delete the line `environment: production` from `release.yml`.)
8. **Use clear commit messages** in the present tense: "Add deadline reminder", not "stuff".
9. **Version releases with tags** (Part 6.9).

### 6.9 Make a release

1. Open `app/build.gradle.kts` and raise the numbers:
```kotlin
versionCode = 3        // must go up by at least 1 every release
versionName = "0.3.0"  // what people see
```
2. Commit, push, merge as above.
3. Tag and push the tag:
```bash
git switch main
git pull
git tag v0.3.0
git push origin v0.3.0
```
4. The **Release** workflow builds the signed files and attaches them to a GitHub Release.

> **How do students get the APK?** A *private* repository's releases can only be downloaded by people you invite. For a campus pilot use one of: Google Play **internal testing** (best), Firebase **App Distribution** (free), or send the APK file directly. Make sure students enable "install unknown apps" only for the source they trust.

---

## Part 7: App signing keys and security

### 7.1 What is a signing key?

Android only installs an **update** if it is signed by the **same key** as the version already on the phone. The key is your app's identity.

- **Lose the key** and you can never update the app for the people who installed it (with Google Play App Signing you can recover; see 7.6).
- **Leak the key** and someone else could publish a fake "update" that your students' phones accept.

So: back it up, protect it, never share it, never commit it.

Terms you will see:

| Term | Meaning |
|---|---|
| **Keystore** (`.jks` file) | A locked box file that holds your key |
| **Alias** | The name of the key inside the box |
| **Store password / key password** | Passwords that unlock the box and the key |
| **Debug key** | An automatic throw-away key Android Studio uses for test builds. Never use for release |
| **Upload key / app signing key** | With Google Play App Signing, you sign with an *upload key* and Google holds the real *app signing key* |

### 7.2 Create your release keystore

Choose **one** way.

**Way A: Android Studio wizard (easiest)**

1. Menu **Build > Generate Signed App Bundle / APK**.
2. Choose **APK** > **Next** > **Create new...**
3. **Key store path:** choose a folder **outside** the project, e.g. `C:\Keys\campusconnect-release.jks`.
4. Create a strong **Password** (use the same value for key password). Write it in a password manager now.
5. **Alias:** `campusconnect`. **Validity:** 30 years. Fill your name (the rest can be blank). **OK**.
6. You can close the wizard without building.

**Way B: command line**

```bash
keytool -genkeypair -v \
  -keystore campusconnect-release.jks \
  -alias campusconnect \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -storetype PKCS12
```

**Line by line:**
- `keytool` is the key tool that comes with Java (found inside Android Studio: Windows `C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe`, Mac `/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool`).
- `-genkeypair -v` creates a new key pair and prints details.
- `-keystore campusconnect-release.jks` is the file to create.
- `-alias campusconnect` is the key's name.
- `-keyalg RSA -keysize 2048` is a strong, widely supported algorithm and size.
- `-validity 10000` is days (about 27 years). Google Play needs it to last beyond 2033.
- `-storetype PKCS12` is the modern format.

It asks for a password (twice) and some questions (name, organisation, country: `TZ`). Use the **same** password for the key.

Write these four facts in your password manager **right now**:

```
Keystore file location:  C:\Keys\campusconnect-release.jks
Store password:          ********
Key alias:               campusconnect
Key password:            ******** (same as store password)
```

### 7.3 Back it up (do not skip)

1. Copy `campusconnect-release.jks` to **two** other safe places: an encrypted USB drive kept at home, and a private cloud drive or password-manager attachment.
2. Test it by listing its contents:

```bash
keytool -list -v -keystore campusconnect-release.jks -alias campusconnect
```

Enter the password. You should see "Owner", "Valid from ... until ...", and the **SHA-256 fingerprint**. Save the fingerprint in your notes; it lets you prove later that an APK is really yours.

### 7.4 Build a signed release on your own computer (optional)

Add to `local.properties` (use forward slashes `/` even on Windows):

```properties
KEYSTORE_PATH=C:/Keys/campusconnect-release.jks
KEYSTORE_PASSWORD=your-password
KEY_ALIAS=campusconnect
KEY_PASSWORD=your-password
```

Then:

```bash
./gradlew :app:assembleRelease     # Mac/Linux
.\gradlew.bat :app:assembleRelease # Windows PowerShell
```

The signed file appears in `app/build/outputs/apk/release/`. **How the project uses these lines:** in `app/build.gradle.kts`, the `signingConfigs` block reads them with `secret("KEYSTORE_PATH")` etc. If they are missing, the release build is simply **unsigned** (it will not install), instead of failing or using the debug key.

### 7.5 Give the key to GitHub safely (for automatic releases)

GitHub secrets only hold text, so turn the file into text (Base64):

```bash
# Mac
base64 -i campusconnect-release.jks | pbcopy
# Linux
base64 -w0 campusconnect-release.jks
# Windows PowerShell (copies to clipboard)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\Keys\campusconnect-release.jks")) | Set-Clipboard
```

Then in your repository: **Settings > Secrets and variables > Actions > New repository secret**:

| Name | Value |
|---|---|
| `KEYSTORE_BASE64` | the long Base64 text |
| `KEYSTORE_PASSWORD` | your store password |
| `KEY_ALIAS` | `campusconnect` |
| `KEY_PASSWORD` | your key password |

How `release.yml` uses them: it decodes the secret into a temporary file (`base64 --decode`), points `KEYSTORE_PATH` at it, builds, then **deletes** the file (`rm -f`) even if the build failed. The secret text is masked in logs. Clear your clipboard afterwards.

### 7.6 Google Play App Signing (strongly recommended if you publish on Play)

1. Create a Play Console account (one-time registration fee, currently about US$25).
2. When you upload your first app bundle (`.aab`), keep **Play App Signing** enabled (it is the default).
3. Your keystore becomes the **upload key**. Google keeps the real signing key in its own secure systems.
4. If you lose or leak the upload key you can ask Google to reset it. Without Play App Signing, a lost key means you can never update that app.

### 7.7 Verify a file really is signed by you

```bash
apksigner verify --print-certs app-release.apk
```

(`apksigner` is in the Android SDK folder: `Android/Sdk/build-tools/<version>/`.) The printed SHA-256 must equal the fingerprint you saved in 7.3. The release workflow prints the same information at the end of every build.

### 7.8 Key handling rules

| Do | Do not |
|---|---|
| Keep the keystore **outside** the project folder | Put the `.jks` inside the repo, even "private" |
| Store passwords in a password manager | Write them in code, `README`, chat or email |
| Keep **2+ encrypted backups** | Keep the only copy on one laptop |
| Use the `production` environment with required reviewer | Let any branch use the signing secrets |
| Use a different Google account password with 2-step verification | Share your GitHub/Google logins |
| Turn on **two-factor authentication** on GitHub, Google, Supabase | Skip 2FA |

### 7.9 If something leaks (emergency plan)

Act fast; do the rotation **first**, cleanup after.

| What leaked | What to do immediately |
|---|---|
| Supabase **`service_role`** key or DB password | Supabase > Project Settings > API: roll/regenerate the key. Change the database password (Project Settings > Database). Re-run `supabase secrets set` if needed. Check **Table Editor > audit_logs** for odd activity |
| Supabase **anon** key | Not an emergency (it is public by design); but verify RLS is on (Part 3.4) |
| Firebase **service-account** JSON | Google Cloud Console > IAM > Service Accounts > Keys: **delete** that key, create a new one, redo Part 5.5 and 5.7 |
| **Keystore or password** | If using Play App Signing: contact Play support to reset the upload key. If not: you cannot revoke it. Treat the app identity as compromised, publish a new app ID |
| **GitHub token** | github.com > Settings > Developer settings: revoke it |

Removing the secret from Git history (after rotating!):

```bash
pip install git-filter-repo
git filter-repo --invert-paths --path path/to/leaked-file
git push --force origin main
```

Rotating is what makes the old secret useless; history cleanup only reduces exposure.

### 7.10 Other security habits for this project

1. **Row Level Security stays on for every table.** Never turn it off "to test". The SQL tests in CI exist to catch mistakes.
2. **Only the anon key goes in the app.** If you ever see `service_role` in `local.properties` or any file under `app/`, remove it and rotate it.
3. **Restrict the Firebase API key (optional hardening):** Google Cloud Console > APIs & Services > Credentials > your Android key > *Application restrictions: Android apps*, add `com.campusconnect.app` with the SHA-1 of **both** your debug and release keys. Get SHA-1 with `keytool -list -v -keystore ...` (look for "SHA1"). Test push after changing it.
4. **Student data and the law.** Timetables, names and emails are personal data. Tanzania has a Personal Data Protection Act (2022). Get written approval from your institution before using real student data, use fake data while developing, collect only what you need, and write a short privacy note.
5. **Backups.** The free Supabase plan does not include automatic backups. Before real use, either upgrade to a plan with backups or regularly export data (`Database > Backups`, or `pg_dump`).
6. **Admin accounts:** give the admin role to as few people as possible and review **audit_logs** now and then.
7. **Keep dependencies updated** by merging Dependabot pull requests when CI is green.

---

## Part 8: First run: set up your campus and test everything

### 8.1 Become the admin

1. Sign in to the app (or register if you did not in Part 4.5).
2. In Supabase **SQL Editor**, run the promote snippet from Part 3.8 with **your** email.
3. In the app tap **Check again** (on the Awaiting approval screen). You are now in the app as an administrator.

### 8.2 Set up the academic structure

In the app: **Profile > Administration > Setup tab**. Do these in order:

1. **New programme:** code `BSC-IT`, name `BSc Information Technology`.
2. **New academic term:** name `2026/27 Semester 1`, pick start and end dates, keep **Current term** ticked.
3. **New course:** code `IT101`, title `Intro to Computing`, choose the programme.
4. Create a **lecturer account** and a **student account** (use two other emails; you can use the app on an emulator or phone, or sign out and register).
5. **Administration > Approvals:** approve the lecturer (choose *Lecturer*) and the student (choose *Student* and a programme).
6. **New class offering:** pick the course, the term and the lecturer.
7. **Enrol a student:** pick the class offering and search for the student by name.

### 8.3 Test each feature (tick them off)

| # | Sign in as | Do this | Expected |
|---|---|---|---|
| 1 | Lecturer | Timetable > **Add class** (tomorrow, with a room) | Class appears on the day |
| 2 | Student | Timetable | Sees the class |
| 3 | Lecturer | Edit the class: change the room | Badge **Changed** appears; student gets a notification |
| 4 | Lecturer | Post an announcement to the course | Student gets a notification; it appears under Announcements |
| 5 | Lecturer | Materials > **Upload** a small PDF | Student gets "New study material" |
| 6 | Student | Materials > tap the PDF | Downloads, opens in the viewer; shows a "saved offline" tick |
| 7 | Student | Turn on flight mode, reopen the PDF and timetable | Still works offline |
| 8 | Lecturer | Calendar > **Add deadline** (due in a few hours) | Student notified; appears on the calendar |
| 9 | Student | Calendar | Deadline shows with "Due soon" |
| 10 | Student | Profile > Language > **Kiswahili** | Whole app switches to Swahili, dates too |
| 11 | Student | Try Administration or posting an announcement | Not offered (and the database would refuse it) |
| 12 | Anyone | Profile > **Sign out**, sign in as someone else | No data from the previous user is visible |

### 8.4 Swahili wording

The Swahili text is in `app/src/main/java/com/campusconnect/app/Strings.kt`. It was written carefully but should be **reviewed by a native Swahili speaker** (a lecturer from the language department is ideal). To change a word, edit the text on the right of `to`:

```kotlin
"Timetable" to "Ratiba",
```

The left side is the English text used in the code; **do not change it**.

---

## Part 9: Build errors, updates and what to do next

### 9.1 Reading a build error in Android Studio

1. Open the **Build** tool window (bottom bar), click the first **red** message.
2. It shows a **file name, a line number, and a message**, for example:
   `e: HomeScreen.kt:42:17 Unresolved reference: DeadlineCard`.
3. Click the blue file link; Android Studio jumps to the line.
4. Fix the first error, rebuild, repeat. Later errors are often caused by the first one.

Typical causes: a missing `import` (hover the red word and press **Alt+Enter** > *Import*), a library function whose name changed between versions, or a typo.

**Getting help:** copy the full error text and the 10 lines of code around it and paste both to Claude, saying which file it is. Mention the library version from `app/build.gradle.kts`.

### 9.2 Where to change things

| I want to... | Edit |
|---|---|
| Change colours | `ui/theme/Theme.kt` |
| Change a screen's layout | the matching file in `ui/` |
| Add a database column | a **new** file in `supabase/migrations/` (never edit an old one that is already run), then `data/Models.kt` and `data/Repo.kt` |
| Change app name | `AndroidManifest.xml` (`android:label`) |
| Add a language word | `Strings.kt` |

### 9.3 Updating the database later

Create a new file named like `20261105000000_add_something.sql` in `supabase/migrations/`, paste it in the Supabase SQL Editor, run it, commit it to Git. The `database-tests` workflow then re-checks everything on a clean database.

### 9.4 Suggested next steps

1. Pilot with one department (3 lecturers, 30 to 60 students) for a month.
2. Get a native Swahili speaker to review `Strings.kt`.
3. Add an app icon (Android Studio: *File > New > Image Asset*) and a Play Store listing.
4. Enable Supabase backups and email confirmation, and set a custom domain/email sender.
5. Ask users what they miss before building more.

You now have the whole chain: **code on GitHub, tested database, signed builds, and push notifications**. Well done.
