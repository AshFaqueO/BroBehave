# Play Store checklist

Everything needed to get BroBehave through Google Play review, written from how the app
actually behaves. Text in blocks is meant to be pasted into the Play Console; adjust
wording, not facts. If Play rejects the app, the signed APK on GitHub Releases is the fallback.

## 0. Before you start

- [ ] Google Play developer account, with **developer verification** completed.
- [ ] Register the package name **`com.brobehave.app`** to your verified developer identity
      (this also keeps installs of the GitHub APK working smoothly on certified devices).
- [ ] Production Supabase project, with all migrations applied and the three Edge Functions deployed.
- [ ] Firebase set up ([firebase-setup.md](firebase-setup.md)).
- [ ] A domain or GitHub Pages site serving the pages in `/site`
      (privacy policy, terms, account deletion). Set `brobehave.webBaseUrl` in `gradle.properties`.
- [ ] A contact email on those pages (currently a placeholder).
- [ ] Final design: icon, screenshots, feature graphic (current visuals are placeholders).
- [ ] Add to Google Cloud (OAuth Android clients) the SHA-1 **and** SHA-256 of **both** the
      GitHub release key **and** the Play App Signing key, or Google Sign-In fails in release builds.

## 1. Store listing draft

**App name:** BroBehave

**Short description (80 characters max):**

```
Quit together. Track your streak, add your bros, climb the ranks.
```

**Full description:**

```
Bro, Behave.

BroBehave is a social accountability app for guys who want to quit porn, together with
their friends. One number matters: days since your last relapse. Add your bros, see each
other's ranks, cheer the wins and laugh off the slips. Progress over perfection.

TRACK YOUR STREAK
A simple streak counter, a history calendar, and nine ranks to climb, from Clown to Giga Chad.

DO IT WITH YOUR BROS
A feed of your friends' rank-ups and resets. React with HAHA, Impressive, Fire, Respect or
Strong. Friends keep you honest; nobody gets shamed out.

SLIPS DON'T END EVERYTHING
A slip lowers your rank for a week and then it climbs back. Only a relapse resets the streak.

YOUR PRIVACY, YOUR CALL
Choose what friends see. No ads, no trackers, nothing sold. A discreet mode shows a
neutral icon and name on your home screen.

OPTIONAL: AUTOMATIC DETECTION (USES VPN)
If you choose to turn it on, BroBehave uses Android's VpnService on your device to check
the names of websites your phone looks up against a list of adult sites. It is fully
on-device: your traffic is not sent to any server, nothing is blocked or changed, and the
site names are never stored or shared. The only thing recorded is that a day was flagged,
which affects your rank. You see a full explanation and must agree first, and you can
turn it off at any time. This earns the Verified badge.

For adults (18+) only. BroBehave is a peer support tool, not medical advice or treatment.
```

**Category:** Health & Fitness. **Tags:** habit tracker, self-improvement.
**Contact email / privacy policy URL:** from the pages above.

**Screenshots (6, with captions):** Streak ("Your streak, front and centre") · Feed with
reactions ("Your bros have your back") · Ranks sheet ("Nine ranks to climb") · Friends
("Add your bros") · Privacy settings ("You decide what friends see") · Detection disclosure
("Optional, on-device, your choice").

## 2. Content rating

Complete the IARC questionnaire truthfully. Expected answers:

- Violence, drugs, gambling, profanity: **No**.
- Sexual content or nudity: **No** — the app contains no sexual content. It *references* a
  mature theme (quitting pornography) in text only.
- Users can interact / share content: **Yes** (usernames, display names, reactions; no free-text posts, no images, no chat).
- Shares user location: **No**. Digital purchases: **No**.

**Target audience:** 18 and over only. The app has an age confirmation at first run.
Do **not** tick any under-18 age group. Mark the app as not directed at children.

## 3. Data safety form

"Does your app collect or share any of the required user data types?" **Yes**.
"Is all of the user data encrypted in transit?" **Yes**.
"Do you provide a way for users to request that their data is deleted?" **Yes** —
in-app (Settings → Delete my account) and the account-deletion web URL.

Nothing is **shared** with third parties (service providers processing on our behalf —
Supabase, Firebase — are not "sharing" under Play's definition). Nothing is used for
advertising or sold.

| Data type (Play's name) | Collected | Purpose | Optional? |
|---|---|---|---|
| Personal info → Email address | Yes | Account management | Required |
| Personal info → User IDs (username) | Yes | App functionality, account management | Required |
| Personal info → Name (display name) | Yes | App functionality | Optional |
| Health and fitness → Health info (streak, relapse dates, flagged days, rank) | Yes | App functionality | Required for the core feature |
| App activity → App interactions (reactions, friend requests) | Yes | App functionality | Optional |
| App activity → Other user-generated content (username, display name) | Yes | App functionality | Required |
| App info and performance → Crash logs, Diagnostics | Yes | Analytics (crash reporting only) | Required |
| Device or other IDs (push notification token) | Yes | App functionality | Optional (only with notifications on) |
| Web browsing → Web browsing history | **No** | — | — |

About **web browsing**: with automatic detection on, website names are examined on the
device only and discarded immediately. They are never transmitted off the device and never
stored, so they are not "collected" under Play's definition (data processed only on-device
is out of scope). State this explicitly in the VpnService declaration below so the two
answers are consistent.

Private notes on a relapse or slip are stored only on the device: not collected.

## 4. VpnService declaration

Play requires apps using `VpnService` to declare it (Policy: Permissions and APIs that
Access Sensitive Information → VPN Service). The service may not collect personal data
without prominent disclosure and consent, may not redirect or manipulate traffic for
monetisation, and the use must be disclosed in the store listing — all satisfied.

**Declaration text:**

```
BroBehave is an accountability app for adults quitting pornography. Its optional
"Automatic detection" feature uses VpnService as a local, on-device DNS observer so that a
user's rank can be verified rather than only self-reported. This is a core, user-facing
feature that the user explicitly opts into.

How it works:
- The VPN interface is configured with a single virtual DNS server address and a route
  for that one address only. All other traffic bypasses the VPN entirely.
- Each DNS query is forwarded, unmodified, to the network's own DNS server and the
  response is relayed back unmodified. No traffic is blocked, filtered, redirected,
  proxied, or sent to any server operated by us or by a third party.
- The queried hostname is compared on-device against a bundled list of adult domains
  (stored as hashes). The hostname is discarded immediately after the comparison. It is
  never written to storage, never logged, and never leaves the device.
- If a hostname matches, the app records only that the current calendar day was flagged.
  That single fact (a date) is synced to the user's account and lowers their in-app rank
  for seven days. No domain, count, or time is stored anywhere.

Disclosure and consent:
- The feature is off by default. Before the system VPN consent dialog is shown, the app
  shows a dedicated full-screen disclosure describing what is checked, what is shared,
  and what is never shared. It requires the user to tick an unticked checkbox and tap
  "Turn on"; "Not now" leaves the app fully usable.
- While active, a persistent notification is shown with a "Turn off" action, and the
  feature can be switched off in the app at any time.

The VPN is not used for advertising, monetisation, or analytics, and does not manipulate
ads or traffic of other apps. Its use is disclosed in the store listing.
```

**Demo video script (about 90 seconds, screen recording with captions):**

| Time | On screen | Caption / voice-over |
|---|---|---|
| 0:00–0:08 | App opens on the Profile page | "BroBehave helps adults quit porn with their friends. Automatic detection is an optional feature." |
| 0:08–0:18 | Scroll to **Slip tracking**; the Automatic detection switch is off | "It is off by default. Turning it on is the user's choice." |
| 0:18–0:40 | Tap the switch; the full-screen disclosure appears; scroll through all four sections slowly | "Before anything starts, the app explains what is checked — website names only, on this phone — what is shared — only that a day was flagged — and what is never shared." |
| 0:40–0:48 | Show that **Turn on** is disabled; tick the checkbox; tap **Turn on** | "The user must tick this box. Only then does Android's own VPN dialog appear." |
| 0:48–0:55 | Android VPN dialog; tap **OK**; key icon and persistent notification appear | "Android asks for consent. A persistent notification shows the feature is running." |
| 0:55–1:10 | Open a browser, load an ordinary website normally | "All traffic bypasses the VPN except name lookups, which are forwarded unchanged. Nothing is blocked or slowed." |
| 1:10–1:20 | Back in the app: status shows "On"; rank badge shows Verified once eligible | "The only effect is on the user's own rank inside the app." |
| 1:20–1:30 | Pull down the notification shade; tap **Turn off**; key icon disappears | "The user can turn it off at any time from the notification or the app." |

Record on a real device. Do not show any adult site: to demonstrate a flag, use a debug
build and the debug-only test name described in `CLAUDE.md`, or simply omit that step.

## 5. User-generated content (UGC) — moderation summary

Play requires UGC apps to define objectionable content, provide in-app reporting and
blocking, and act on reports.

```
User-generated content in BroBehave is limited to usernames, display names, and emoji
reactions on system-generated posts. There is no free-text posting, messaging, image or
video sharing. (Avatar images are not yet user-uploadable.)

- Terms: users must accept terms prohibiting harassment, hate, sexual content and
  impersonation, including in usernames and display names.
- Reporting: any user can report another user from that user's profile (reasons:
  harassment, inappropriate content, spam, impersonation, other).
- Blocking: any user can block another user; blocked users cannot find them, see their
  content, or send requests, and are not told.
- Controls: users can turn off "HAHA" reactions on their own posts and choose what
  friends can see. Content is visible only to accepted friends.
- Enforcement: reports are reviewed by the developers, and offending names or accounts
  are removed.
```

- [ ] **Decide who reviews reports and how often** (they are in the `reports` table,
      readable only with the service role). There is no admin screen yet; use the Supabase
      table editor. State a response time you can keep (for example 48 hours).
- [ ] If avatar uploads are added later, re-do this section (image moderation is required).

## 6. Closed testing plan (required for new personal developer accounts)

Google requires **at least 12 testers opted in for 14 consecutive days** before a new
personal account can apply for production access.

1. **Internal testing first (days −7 to 0):** upload the first signed `play` App Bundle
   (the `play-app-bundle` artifact of the release workflow). 3–5 people. Fix crashes.
2. **Recruit 15–20 testers** (aim above 12, since some drop out): friends who fit the
   audience, with Google accounts on Android 8+ devices. Collect their Google emails.
3. **Create the closed track**, add the tester list, and send the opt-in link. Each tester
   must tap "Become a tester" and install from Play — only then do they count.
4. **Day 0–14:** keep at least 12 opted in continuously. Ask testers to use the app a few
   times a week: sign up, add each other, react, try a slip and a relapse, try detection.
   Ship at least one or two updates during the window (it shows active testing).
5. **Collect feedback** in one place (a form or group chat) and keep notes: Google's
   production-access form asks what you tested and what you changed.
6. **Day 14+:** apply for production access, answering from those notes. Then promote the
   tested build.

## 7. Release mechanics

- [ ] Tag a release (`git tag v1.0.0 && git push origin v1.0.0`). The workflow builds the
      signed GitHub APK (published as a Release) and the signed Play App Bundle (artifact).
- [ ] Increase `versionCode` in `app/build.gradle.kts` for every upload.
- [ ] Use **Play App Signing**; the `PLAY_*` secrets hold only the upload key.
- [ ] Upload the R8 mapping file if Play asks (it is in `app/build/outputs/mapping/playRelease/`).
- [ ] App Links: host `/.well-known/assetlinks.json` (with the SHA-256 of both signing
      keys) on the domain that serves the invite pages, or invite links open in the browser
      instead of the app. On `<user>.github.io` this file must live in the user-site
      repository, not this one.

## 8. If Play rejects it

The most likely reason is the VpnService use. Options, in order: answer the rejection with
the declaration above and the video; ship the `play` flavor with
`AUTO_DETECTION = false` (one line in `app/build.gradle.kts` — the feature disappears from
that flavor and self-report remains); keep distributing the full `github` flavor.
