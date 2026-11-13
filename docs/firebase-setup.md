# Firebase setup (push notifications + crash reports)

BroBehave uses Firebase for exactly two things: **Cloud Messaging** (push) and
**Crashlytics** (crash reports). No Analytics. Until this is done the app still builds
and runs; push and crash reporting are simply inactive.

You need a Google account. Nothing here costs money.

## 1. Create the project (about 3 minutes)

1. Open <https://console.firebase.google.com> and click **Create a project**.
2. Name it `BroBehave`.
3. **Turn Google Analytics off** when asked (the app must not have analytics).
4. Click **Create project**.

## 2. Add the Android app

1. On the project page click the **Android** icon ("Add app").
2. **Android package name:** `com.brobehave.app` (exactly this).
3. App nickname: `BroBehave`. Leave the SHA-1 field empty for now (see step 5).
4. Click **Register app**, then **Download google-services.json**.
5. Put the file at **`app/google-services.json`** in the project. It is git-ignored —
   never commit it.
6. Skip the remaining wizard steps ("Add Firebase SDK"); the project already has them.

Rebuild. The message "google-services.json not found" disappears from the build output,
and Crashlytics starts working on the next launch.

## 3. Let the server send pushes

Pushes are sent by the `push` Edge Function, which needs a service-account key.

1. Firebase console → ⚙ **Project settings** → **Service accounts** →
   **Generate new private key**. A JSON file downloads. **Treat it like a password**: do
   not put it in the project folder and never commit it.
2. Pick a long random string to use as a shared secret (for example the output of
   `openssl rand -hex 32`).
3. Give both to the Edge Function (hosted Supabase project):

   ```bash
   npx supabase secrets set PUSH_WEBHOOK_SECRET=<the random string>
   npx supabase secrets set FCM_SERVICE_ACCOUNT="$(cat /path/to/service-account.json)"
   npx supabase functions deploy push
   ```

4. Tell the database where the function is. In the Supabase SQL editor:

   ```sql
   select vault.create_secret('https://<project-ref>.supabase.co/functions/v1/push', 'push_function_url');
   select vault.create_secret('<the same random string>', 'push_function_secret');
   ```

From then on, a friend request, a friend's milestone or rank change, and reactions to your
posts produce a push (subject to each user's notification settings).

## 4. Check it works

1. Install the app on a real phone (or an emulator image with Google Play services),
   sign in, and allow notifications.
2. In Supabase → Table editor → `device_tokens` there should be a row for your user.
3. From a second account, send the first one a friend request. A notification should arrive.
4. Supabase → Edge Functions → `push` → Logs shows each call and how many devices it reached.

To see Crashlytics working, the simplest check is the Firebase console → Crashlytics page:
it switches from "waiting for your app" to the dashboard after the first launch of a build
that includes `google-services.json`.

## 5. Google Sign-In (separate from Firebase, but set up in the same sitting)

1. <https://console.cloud.google.com> → select the same project → **APIs & Services** →
   **Credentials** → **Create credentials** → **OAuth client ID**.
2. Create a **Web application** client. Copy its client ID into `local.properties` as
   `GOOGLE_WEB_CLIENT_ID=...` (and into the CI secret of the same name).
3. Create an **Android** client for package `com.brobehave.app` for **each** signing key:
   - your debug key (`keytool -list -v -keystore ~/.android/debug.keystore -storepass android`),
   - the GitHub release key,
   - the **Play App Signing** key (Play Console → Setup → App signing), not only the upload key.
4. In Supabase → Authentication → Providers → **Google**: enable it and paste the **Web**
   client ID (and secret).

Without the Android clients for the release keys, Google Sign-In works in debug builds and
silently fails in released ones.

## Known follow-up

Firebase now marks device registration tokens as deprecated in favour of Firebase
Installation IDs. The app still uses tokens, which continue to work; migrating is tracked
in `CLAUDE.md`.
