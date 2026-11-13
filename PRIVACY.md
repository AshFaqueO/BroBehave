# BroBehave privacy policy (draft)

_Last updated: 13 November 2026. This is a draft written from how the app actually works;
have it reviewed before launch. The public copy is `site/privacy.html` — keep the two in sync._

BroBehave is a social accountability app for adults (18+). It deals with a sensitive
subject, so it is built to collect as little as possible. There are **no ads, no
advertising identifiers, no analytics trackers**, and your data is never sold.

## What we collect

| Data | Why | Who can see it |
|---|---|---|
| Email address (or Google account email) | Signing in, password reset | Only you |
| Username, display name | So friends can find and recognise you | Anyone searching by username; your friends |
| Timezone | To count days by your local midnight | Only you |
| Streak start, longest streak, rank | The core of the app | Your friends, unless you set them to private |
| Relapse dates | Resetting your streak; your history calendar | Your friends, unless you set relapses to private |
| Flagged days (the date, and whether it was self-reported or automatic) | Adjusting your rank for 7 days | Only you. Friends see only the resulting rank. |
| Friendships, blocks, reactions, reports | Social features and moderation | The people involved; reports are seen only by moderators |
| Notification token | Sending push notifications | Only our systems |
| Crash reports (Firebase Crashlytics) | Fixing bugs | Only the developers. They never contain site names or notes. |

## What we never collect

- The names of websites you visit, how many times, or at what time.
- Your private notes on a relapse or slip. They stay on your phone and are never uploaded.
- Your contacts, location, photos, or browsing history.

## Automatic detection (optional, off by default)

If you turn on automatic detection, BroBehave uses Android's VPN feature **on your phone only**.

- **What is checked:** only the names of websites your phone looks up, compared on the
  phone against a list of adult sites. Nothing is blocked or changed, and your traffic is
  not routed through any BroBehave server.
- **What is shared:** only that a day was flagged, which lowers your rank for 7 days.
- **What is never shared or stored:** which sites, how many times, or when. Site names are
  discarded the moment they are checked, even on your phone, and are never logged.

You see a full explanation and must agree before it starts. You can turn it off at any
time from the app or from its notification.

## Where data is stored

Account and app data is stored with **Supabase** (database, sign-in, file storage). Push
notifications are delivered through **Firebase Cloud Messaging** and crash reports through
**Firebase Crashlytics**, both by Google. Data is encrypted in transit. Access rules are
enforced in the database itself (Row Level Security), not only in the app.

## Your choices

- Choose what friends see (streak, relapses, rank) in Settings → Privacy.
- Turn each notification type on or off in Settings.
- Block or report anyone; remove friends at any time.
- Delete your account and all its data in Settings → Delete my account, or through the
  account deletion web page.

A flagged day cannot be removed once recorded; this is what keeps ranks honest. Deleting
your account removes them along with everything else.

## Retention

Data is kept while your account exists and deleted when you delete the account. Crash
reports expire after 90 days.

## Age

BroBehave is for adults only. You must confirm you are 18 or older to use it. We do not
knowingly keep data about anyone under 18.

## Changes and contact

If this policy changes in a way that matters, the app will tell you.
Contact: _[contact email to be added]_.
