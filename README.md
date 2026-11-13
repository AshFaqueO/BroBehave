# BroBehave — "Bro, Behave."

A social accountability Android app for quitting porn/masturbation, together with your
friends. Brotherly banter plus genuine support: progress over perfection. For adults (18+).

Two pages, that's all:

- **Feed** — your bros' ranks and posts (rank-ups, slips, resets), with reactions
  (😂 HAHA, 🤯 Impressive, 🔥 Fire, 👊 Respect, 💪 Strong).
- **Profile** — your streak and rank, "I relapsed" / "I slipped", history calendar,
  slip tracking, friends, settings.

## Features

- **Streak** — days since your last relapse. Only a relapse resets it.
- **Nine ranks** — Clown → Noob → Novice → Average → Advanced → Sigma → Chad →
  Absolute Chad → Giga Chad, at 0 / 1 / 3 / 7 / 15 / 30 / 45 / 60 / 120 days.
- **Slips** — a flagged day never breaks your streak; it lowers your rank by one step for
  7 days, then the rank climbs back by itself.
- **Friends** — search by username, requests, invite links, block and report.
- **Privacy controls** — choose whether friends see your streak, relapses and rank; switch
  HAHA reactions off for your posts.
- **Works offline** — your streak, slips and relapses are saved on the phone and synced later.
- **Discreet mode** — show a neutral "Habits" icon and name on the home screen.
- **Optional automatic detection** — earns the Verified ✓ badge (see below).
- No ads, no analytics, no trackers.

## Screenshots

_To be added once the final design is in._

## Install (GitHub release)

1. Download `BroBehave-<version>-<code>.apk` and its `.sha256` file from the
   [Releases page](https://github.com/AshfaqueO/BroBehave/releases).
2. Verify the download (the two values must match):

   ```bash
   sha256sum -c BroBehave-<version>-<code>.apk.sha256        # Linux / macOS
   ```

   ```powershell
   Get-FileHash .\BroBehave-<version>-<code>.apk -Algorithm SHA256   # Windows
   ```

3. Open the APK on your phone and allow installing from that source when Android asks.

The GitHub build checks for a newer release once a day and offers the download. It never
installs anything by itself. A GitHub build and a Play Store build are signed with
different keys, so you cannot update one with the other: uninstall first to switch.

## Automatic detection, in plain language

This is optional and **off unless you turn it on**. It exists so your rank isn't only
your own word for it.

- **What it does:** it uses Android's VPN feature *on your phone* to see the **names** of
  websites the phone looks up (for example `example.com`) and compares them with a list
  of adult sites.
- **What it does not do:** it does not read pages, messages or anything you type. It does
  not block or change anything. Your internet traffic is **not** sent through any
  BroBehave server; only the name lookups pass through the app, and they are forwarded
  unchanged.
- **What gets recorded:** only that *today was flagged*. Not which site, not how many
  times, not when. The site name is thrown away the moment it has been checked, even on
  your phone, and it is never written to any log.
- **What friends see:** only your rank (if you allow it), and a Verified ✓ badge while
  detection has been on for a full week.
- **You stay in control:** you see a full explanation and must tick a box before Android's
  own VPN dialog appears. A notification with a **Turn off** button is shown the whole
  time it runs.
- **Limits:** only one VPN can run at a time, so it stops if you start another VPN app.
  Encrypted DNS (Chrome's "Secure DNS", or a named Private DNS provider) hides lookups
  from it. A flagged day cannot be removed.

More detail: [PRIVACY.md](PRIVACY.md).

## For developers

Everything a contributor needs — architecture, module map, conventions, commands, and
what is still open — is in **[CLAUDE.md](CLAUDE.md)**. Short version:

**Requirements:** JDK 17+, Android SDK (API 37), Node 18+, Docker Desktop (for the local backend).

```bash
# 1. Local backend (Supabase: Postgres, auth, storage)
npm install
npm run db:start            # prints the API URL and anon key
npm run db:test             # 156 database assertions (RLS, onboarding, tiers)

# 2. local.properties (git-ignored) — create it in the project root
#    sdk.dir=<path to your Android SDK>
#    SUPABASE_URL=http://127.0.0.1:54321
#    SUPABASE_ANON_KEY=<anon key printed by db:start>

# 3. Build, test, check
./gradlew assemblePlayDebug assembleGithubDebug
./gradlew test ktlintCheck detekt :app:lintPlayDebug

# 4. Run on an emulator or a USB-connected phone
adb reverse tcp:54321 tcp:54321     # lets the device reach the local backend
adb install -r app/build/outputs/apk/play/debug/app-play-debug.apk
```

Seed accounts (local only): `rafi@example.com`, `tanim@example.com`, `sam@example.com`,
password `password123`.

**Tech:** Kotlin, Jetpack Compose, Material 3, Hilt, Room, WorkManager, Navigation 3;
Supabase (Postgres with Row Level Security, Auth, Storage, Edge Functions); Firebase for
push notifications and crash reports only.

**Before you push:** `./gradlew ktlintFormat` then the checks above. CI runs the same on
every pull request (`.github/workflows/ci.yml`).

**Never commit:** `local.properties`, `google-services.json`, keystores, `.env` files, or
any key. They are git-ignored; keep it that way.

Setup guides: [Firebase](docs/firebase-setup.md) ·
[Play Store checklist](docs/play-store-checklist.md).

## Licence

Private project, all rights reserved — see [LICENSE](LICENSE). The bundled adult-domain
list is derived from [StevenBlack/hosts](https://github.com/StevenBlack/hosts) (MIT).
