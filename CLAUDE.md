# BroBehave — "Bro, Behave."

Social accountability Android app for quitting porn/masturbation. Two pages only: **Feed**
(friends' ranks and posts, reactions) and **Profile** (streak, rank, slip tracking,
friends, settings). 18+. Tone: supportive, calm, brotherly banter; progress over
perfection. No explicit content anywhere. Never use the trademark "NoFap".

Distribution: Google Play (`play` flavor) and signed APK on GitHub Releases (`github` flavor).

## Core concepts

- **Streak** — days since the last relapse. Only a relapse resets it.
- **Flagged day** — a day the user visited an adult site (self-reported, or auto-detected
  if opted in). Never resets the streak. Only the date is stored — never domains, counts, times.
- **Tier (rank)** — 9 steps, Clown → Giga Chad, earned by streak days:
  0 / 1 / 3 / 7 / 15 / 30 / 45 / 60 / 120.
  `effective rank = streak rank − flagged days in the rolling 7-day window`, floored at Clown.
- **Verified ✓** — automatic detection is on (a heartbeat exists for every day in the window).

## Architecture decisions (and why)

| Decision | Rationale |
|---|---|
| Native Kotlin, Jetpack Compose, Material 3, single activity | Best platform fit; VpnService and WorkManager are first-class. |
| MVVM + unidirectional data flow (immutable `UiState` + events), layers ui / domain / data, repository pattern | Testable, predictable state. |
| Offline-first: Room is the source of truth for the user's **own** data | Streak, flagged days and relapse must work offline; WorkManager syncs. |
| Multi-module + Gradle convention plugins (`build-logic`) | Build speed, enforced boundaries, `:detection` can be feature-flagged. |
| Hilt, Coroutines + Flow, kotlinx.serialization | Standard, well supported. |
| **Navigation 3** (stable 1.2.x), type-safe `NavKey`s | Stable at project start, so chosen over Navigation Compose. |
| **Supabase** (Postgres + RLS, Auth, Storage, Edge Functions, pg_cron), client `supabase-kt` | Managed, no servers. |
| **Not Firebase for everything** | Friends, feed and privacy are relational. Postgres + RLS handles them with plain SQL instead of Firestore fan-out writes and denormalization. |
| **Not Rust** | Adds a JNI/NDK toolchain for no real benefit: the DNS engine only parses small, low-volume packets and the backend is managed. |
| Firebase for **FCM + Crashlytics only** | Sensitive data: no analytics, no other trackers. |
| Other users are read **only through SECURITY DEFINER RPCs** (`feed`, `friends_ranks`, `get_profile`, `search_users`, `event_reactors`); direct table reads return only your own rows | RLS is row-level; RPCs are how private *columns* stay hidden. Privacy is enforced in SQL, never only in the app. |
| Tier columns, tier/milestone events and `tier_verified` are server-computed; column-level grants stop clients writing them | Ranks must be trustworthy. |
| A relapse is a client insert of an `events` row (`type = 'relapse'`); a trigger resets the streak | A plain insert is easy to queue offline and replay. |
| Private notes on relapse/slip stay on the device (Room) and are never uploaded | Minimise sensitive data on the server. |
| AGP 9 built-in Kotlin (no `kotlin-android` plugin) | Current AGP default. |
| JUnit 5/6 (Jupiter) for unit tests; Robolectric (JUnit 4 via the Vintage engine) for Room DAO, Compose UI and Roborazzi screenshot tests | No emulator needed locally or in CI. |
| `applicationId = com.brobehave.app`, same for both flavors | One identity for Google Sign-In and App Links. |
| No dynamic color; dark-first | Brand consistency. |

Schema additions beyond the original spec: `profiles.onboarded_at` (locks `streak_start`
after onboarding) and the `tier_settings` single-row table (penalty and window length).

## Module map

```
:app                    Single activity, navigation shell (Navigation 3), flavors, FeatureFlags impl, FCM service
:core:model             Pure Kotlin. Rank ladder, TierCalculator, TierConfig, FeatureFlags interface
:core:data              Repositories (offline-first)
:core:database          Room (source of truth for own data)
:core:network           Supabase client (anon key via BuildConfig)
:core:designsystem      Tokens, BroBehaveTheme, components (StreakRing, TierBadge, PrimaryButton, EmptyState, ConfirmDialog, …)
:core:ui                Shared composables built from the design system
:core:testing           Test helpers (MainDispatcherExtension) + JUnit/MockK/Turbine
:feature:onboarding     18+ gate, username, timezone, streak start, privacy defaults
:feature:auth           Email/password, Google Sign-In, reset
:feature:feed           "Your bros" strip + feed + reactions
:feature:profile        Profile page + sub-screens (friends, tracking settings, app settings)
:detection              Optional DNS-only VpnService engine (Phase 4), gated by FeatureFlags.autoDetection
build-logic/convention  Convention plugins: brobehave.android.{application,library,compose,feature,room,robolectric}, brobehave.hilt, brobehave.jvm.library, brobehave.quality
supabase/               config.toml, migrations/, seed.sql, tests/database/ (pgTAP)
```

Dependency direction: `feature → core:ui → core:designsystem → core:model`;
`core:data → core:database, core:network`. Features never depend on each other.

## Conventions

- Latest stable versions only, all in `gradle/libs.versions.toml`. Check current docs before upgrading.
- All user-facing strings in `strings.xml` (English now, Bangla-ready; Bangla runs ~30% longer). Use plurals.
- Design tokens only from `:core:designsystem` — no raw colors/dp in feature code.
  **The current tokens are placeholders**; the Claude Design handoff is the source of truth
  for visuals once provided (implement its tokens exactly, keep its component names).
- Accessibility: content descriptions, 48dp touch targets, AA contrast, 200% font scale,
  never colour alone (ranks always show their name), respect reduced motion.
- **Logs and crash reports must never contain personal data, domains, or notes.**
- **No secrets in the repo.** Supabase URL + anon key come from `local.properties` or CI
  env vars (`SUPABASE_URL`, `SUPABASE_ANON_KEY`). The service role key lives only in Edge
  Function secrets.
- Every schema change is a new SQL migration in `supabase/migrations/`; add pgTAP tests
  for every new policy or RPC.
- The rank thresholds live in `tier_config` (SQL) and `Rank` (Kotlin); keep them in sync
  (Phase 3 adds the test that asserts it).
- detekt + ktlint must be clean; no suppressed warning without a comment explaining why.
- Ask the owner before any major architectural change.

## Commands

Requires JDK 17+ and the Android SDK (`sdk.dir` in `local.properties`).

```bash
./gradlew assemblePlayDebug assembleGithubDebug   # build both flavors
./gradlew test                                    # all unit tests (JVM + Robolectric)
./gradlew ktlintCheck detekt                      # static analysis
./gradlew ktlintFormat                            # auto-format
./gradlew :app:lintPlayDebug                      # Android lint
./gradlew :core:designsystem:recordRoborazziDebug # re-record screenshot goldens
./gradlew :core:designsystem:verifyRoborazziDebug # compare against goldens
```

## Supabase local workflow

Needs Docker Desktop running. The CLI is an npm dev dependency (`npm install` once).

```bash
npm run db:start    # start the local stack (prints the API URL and anon key)
npm run db:reset    # re-apply all migrations + seed.sql
npm run db:test     # run the pgTAP RLS tests in supabase/tests/database
npm run db:stop
```

Put the printed values in `local.properties` (from the emulator the host is `10.0.2.2`):

```
SUPABASE_URL=http://10.0.2.2:54321
SUPABASE_ANON_KEY=<anon key from db:start>
```

Seed users: `rafi@example.com`, `tanim@example.com`, `sam@example.com` / `password123` (local only).

### Integration test against the local stack

`core/data/.../SupabaseIntegrationTest` drives the real repositories against the seeded
local database (it skips itself when the variables are not set):

```bash
npm run db:reset
SUPABASE_TEST_URL=http://127.0.0.1:54321 SUPABASE_TEST_ANON_KEY=<anon key> ./gradlew :core:data:testDebugUnitTest
```

It changes seed data, so run `npm run db:reset` afterwards.

### Edge Functions

- `supabase/functions/delete-account` — called by the app with the user's JWT; deletes
  avatar files and the auth user (all tables cascade).
- `supabase/functions/push` — called by the database (trigger → pg_net) with a shared
  secret; turns a `notification_outbox` row into FCM data messages.

Serve locally with `npx supabase functions serve`. Production setup for push:

```sql
select vault.create_secret('https://<project>.supabase.co/functions/v1/push', 'push_function_url');
select vault.create_secret('<random secret>', 'push_function_secret');
```

and Edge Function secrets `PUSH_WEBHOOK_SECRET` (same random secret) and
`FCM_SERVICE_ACCOUNT` (Firebase service-account JSON). Until both Vault secrets exist the
dispatch trigger does nothing.

### Other local config (`local.properties`)

`GOOGLE_WEB_CLIENT_ID=<OAuth web client id>` enables the "Continue with Google" button
(the Google provider must also be enabled in Supabase Auth). Without it the button is hidden.

## Things that are placeholders (replace before release)

- All design tokens, icons, avatars (initials) and the launcher icons — pending the design handoff.
- Web pages: `brobehave.webBaseUrl` in `gradle.properties` (currently the GitHub Pages
  address) drives the invite link, privacy policy, terms and account-deletion URLs. The
  pages themselves are in `/site`, deployed by `.github/workflows/pages.yml`. GitHub Pages
  for a private repository needs a paid plan. The contact email on those pages is still a
  placeholder, and App Links need `/.well-known/assetlinks.json` on the serving domain
  (until then invite links open the web page, whose button opens the app via `brobehave://`).
- `brobehave.githubRepo` in `gradle.properties`: the repository whose Releases the `github`
  flavor checks. The Releases API only answers for a **public** repository.
- Push uses FCM registration tokens, which Firebase now marks deprecated in favour of
  Firebase Installation IDs; migrate once the server-side send API for IDs is confirmed.

## Firebase

FCM + Crashlytics only. Download `google-services.json` from the Firebase console
(Android app with package `com.brobehave.app`) and put it at **`app/google-services.json`**.
It is git-ignored — never commit it. Without the file the app still builds and runs; the
Firebase Gradle plugins are skipped and push/crash reporting are inactive.

## Phase plan

1. **Foundation** — scaffold, convention plugins, flavors, design-system skeleton, Supabase schema + RLS tests, Firebase wiring, navigation shell. ✔
2. **Core social app** — onboarding, auth, Profile page, Feed page, reactions, notifications, moderation, offline sync. ← built; see "Phase 2 open items"
3. **Tier system** — section 5 end to end, boundary/timezone tests, SQL↔Kotlin config test. ✔
4. **Automatic detection** — `:detection` DNS-only VpnService, blocklist, prominent disclosure, heartbeats. ← built (ahead of Phase 3); see "Automatic detection"
5. **Release & distribution** — signing, R8 rules, baseline profiles, GitHub Actions, README/PRIVACY/Play checklist. ✔ (licence: private, all rights reserved)

Finish and verify each phase (builds, tests pass, app runs) before starting the next.

### Tier system (Phase 3)

- `compute_tiers(user, emit_events, now)` in SQL is the authority; `TierCalculator` /
  `StreakSnapshot` in Kotlin compute the same thing for instant UI. The same boundary
  cases are tested on both sides (`supabase/tests/database/tiers.test.sql`,
  `LocalTierBoundaryTest`), and `TierConfigSyncTest` reads the migrations and fails if the
  rank ladder in SQL and Kotlin ever differ.
- Verified = a heartbeat for every day of the 7-day window ending today **or** ending
  yesterday (so the badge does not drop at midnight before the day's first heartbeat).
- Events: the streak crossing a threshold posts `milestone`; a flagged day posts
  `tier_down`; a flag ageing out posts `tier_up`; a relapse posts only `relapse` +
  `streak_started`.

### Release & distribution (Phase 5)

- **Flavors:** `play` uses Play In-App Updates and In-App Review; `github` checks the
  GitHub Releases API at most once a day and offers the APK as a browser download
  (`app/src/play`, `app/src/github`, common interface `DistributionServices`).
- **Signing:** from `BROBEHAVE_KEYSTORE_FILE`, `BROBEHAVE_KEYSTORE_PASSWORD`,
  `BROBEHAVE_KEY_ALIAS`, `BROBEHAVE_KEY_PASSWORD`. Without them release builds are unsigned.
- **`staging` build type:** the R8-shrunk release code, debug-signed and allowed plain http,
  for testing what actually ships against the local stack:
  `./gradlew assembleGithubStaging` then install as usual. Never distributed.
- **R8:** no custom keep rules were needed; the libraries' own consumer rules suffice
  (verified by running the staging build on an emulator: session restore, feed decoding,
  Room, detection service).
- **Baseline profiles:** `:baselineprofile` module;
  `./gradlew :app:generateGithubReleaseBaselineProfile :app:generatePlayReleaseBaselineProfile`
  on an emulator or rooted device (it clears the app's data on that device). Generated
  files in `app/src/<flavor>Release/generated/baselineProfiles/` are committed.
- **CI** (`.github/workflows`): `ci.yml` on pull requests (ktlint, detekt, unit tests,
  lint, screenshots, both flavors, database tests, integration tests); `release.yml` on a
  `v*` tag (signed GitHub APK + SHA-256 + changelog as a Release, signed Play App Bundle
  as an artifact); `pages.yml` publishes `/site`. The workflows have not run yet — they
  need the repository and its secrets (listed at the top of `release.yml`).
- **Release APK naming:** `BroBehave-<versionName>-<versionCode>.apk`; the in-app update
  check reads the version code from that name.
- Docs: `README.md`, `PRIVACY.md`, `docs/play-store-checklist.md`, `docs/firebase-setup.md`.

### Automatic detection (Phase 4, built before Phase 3 at the owner's request)

How it works (`:detection`):

- `DetectionVpnService` is an on-device `VpnService` that receives **only DNS**: it sets a
  virtual DNS server (`10.111.222.2`) and adds a route for that single address, so all other
  traffic bypasses the VPN. Each query is forwarded unchanged to the network's own DNS
  server (`UpstreamResolver`) and the answer relayed back. Nothing is blocked or modified.
- `engine/` is pure Kotlin and fully unit-tested: `DnsMessage` (reads the question name,
  total on malformed input, fuzz-tested), `IpPackets` (IPv4/UDP parse + build, TCP reset),
  `DomainMatcher` (sorted 64-bit FNV-1a hashes, subdomain matching), `DailyFlagger`
  (one flag per local day), `UpstreamResolver` (UDP, TCP retry when truncated).
- TCP: the tunnel has no TCP stack. Truncated UDP answers are completed over TCP upstream
  and relayed over UDP; TCP connection attempts to the virtual server (DNS-over-TCP/TLS)
  get an immediate reset so the system resolver falls back to UDP.
- On a match the service calls `StreakRepository.reportSlip(source = AUTO)`, which writes
  today's local date to `flagged_days`. **The site name is never stored, logged, or put in
  an exception; it exists only as a local variable during the comparison.**
- While running it records one heartbeat per local day (`detection_heartbeats`); the
  server grants Verified only when every day in the window has one.
- Turning it on always goes through `DetectionDisclosureScreen` (full screen, unticked
  checkbox, "Turn on" / "Not now") **before** Android's VPN dialog. There is deliberately
  no `start()` on `DetectionGateway`.
- Foreground service type `systemExempted` (the type Android lists for VPN apps), with a
  persistent notification that has a "Turn off" action.
- Restarts after reboot and after an app update if the user had it on and VPN consent
  still stands; `onRevoke` (another VPN took over) sets status REVOKED and stops heartbeats.
- Gated by `FeatureFlags.autoDetection` (per-flavor `AUTO_DETECTION` BuildConfig field).

Blocklist:

```bash
python tools/build_blocklist.py                              # refresh the bundled asset
python tools/build_blocklist.py --out dist/ --key <key.pem>  # signed update package
```

- Source: StevenBlack/hosts "porn-only" (MIT). Bundled as `detection/src/main/assets/blocklist.bin`
  — hashes only, never names. OISD NSFW is not included yet (check its licence first).
- Updates: `BlocklistUpdater` (weekly WorkManager job) fetches a manifest and the file from
  the `blocklist` Edge Function and installs it only if `BlocklistVerifier` accepts it:
  newer version, matching size and SHA-256, valid ECDSA P-256 signature, parses as a list.
- To enable updates: create a P-256 key pair, keep the private key **outside the repo**,
  put the base64 X.509 public key in `local.properties` as `BLOCKLIST_PUBLIC_KEY`, and
  upload `manifest.json` + `blocklist.bin` to the private `blocklist` Storage bucket.
  While the key is blank, only the bundled list is used.
- Debug builds only: `detection-test.brobehave.invalid` counts as listed, so detection can
  be tested without looking up a real adult site
  (`adb shell ping -c 1 www.detection-test.brobehave.invalid`).

Known limits: Chrome "Secure DNS" / DNS-over-HTTPS, a named Private DNS provider, or
another VPN app all hide lookups from this method (Private DNS and VPN takeover are
detected and explained; DoH inside an app is not detectable). IPv4 tunnel only.

Phase 4 open items: the signed-update path has unit tests but has not been run end to end
against a published list; always-on VPN and reboot restart are implemented but untested;
"another VPN took over" was not exercised on a device; the Play VpnService declaration
and video are Phase 5.

### Phase 2 open items

- Never run on a device or emulator yet; the new screens have view-model tests but no Compose UI tests.
- Google Sign-In and FCM delivery are untested (need a Google web client ID and a Firebase project).
- Avatar image upload (Coil + Storage) is not built; avatars show initials.
- Rank-up celebration is a dialog, not yet an animation.
- Reporting covers users (from a friend's profile), not individual posts.
- Password reset sends the email; there is no in-app screen for choosing the new password.
- Bangla strings are not written; push notification text is English only (worded on the server).
- Automatic detection toggles are shown disabled until Phase 4.

### How navigation works (Phase 2)

`AppViewModel` derives a `RootState` (LOADING / INTRO / AUTH / SETUP / MAIN / UNREACHABLE)
from the session, the cached profile and the intro flag. `BroBehaveNavShell` keeps that
root key at the bottom of the Navigation 3 back stack and pushes sub-screens (friends,
settings, edit profile, a friend's profile) on top of `MainKey`. Screens never navigate
on "signed in" callbacks — the state changes and the root follows.
