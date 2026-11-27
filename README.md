# GhostRelay

GhostRelay is a dark-mode Android one-to-one messaging prototype. It connects to a Supabase project configured by the person building the APK. Users create separate accounts, add friends by username, then exchange messages and attachments. Optional live-location sharing is available after the matching Supabase migration is installed.

### Available in the Android app

- Email and password account registration and sign-in through Supabase Auth.
- Saved login session on the device; users do not normally need to sign in again after each launch.
- Profiles with a unique username and display name.
- Username search, friend requests, accept/decline actions, and a friend list.
- One-to-one text conversations stored in Supabase. The open conversation refreshes periodically for new messages.
- Image, audio-file, and general file attachments through private Supabase Storage. The app limits uploads to 12 MB per file.
- Dark-mode chat interface with sent/received message styling and timestamps.
- Local activation-code gate on every app launch. A three-finger tap returns to the locked screen.
- After an incorrect activation code, an optional selfie prompt can open the camera. No photo is taken automatically: the person must choose **Take selfie** and take the picture. The photo is saved locally to `Pictures/Store`.

### Optional feature: live location

Live location is implemented in the Android app but requires the `202611080003_live_location.sql` migration in the Supabase project. The person sharing must tap **Share live location** and grant Android location permission. A persistent notification shows that sharing is active; sharing can be stopped from the chat or notification. A friend can open the latest location in Maps. Locations older than three minutes are treated as stale. Android battery settings and background restrictions may reduce or pause updates.

### Optional feature: email message alerts

An email alert Edge Function is included, but it does not work until the email migration, Resend account/sender, function secrets, function deployment, and Supabase Database Webhook are configured. After setup, the first incoming message can send an email; further alerts to that recipient are suppressed for 30 minutes. Email alerts do not include the message text. The old local “Get your fitness ready” notification has been removed. Native push notifications are not implemented.

## Feature status

| Feature | Status | What to know |
|---|---|---|
| Accounts and saved login | Available after Supabase setup | Supabase Auth; one account per user |
| Friend list and requests | Available after migration 001 | Search by username, send request, accept/decline |
| One-to-one messages | Available after migration 001 | No group or invite-only rooms |
| Image/audio/file attachments | Available after migration 001 | Up to 12 MB; audio is selected from files, not recorded live |
| Live location | Requires migration 003 | Explicitly started; visible notification; user can stop it |
| Email alerts | Optional server setup required | Migration 002, Resend, Edge Function, webhook |
| Device pairing | Not implemented | Pairing screen is a placeholder |
| Custom activation code | Available at build time | Configure `app.lockCode`; same code is in the APK for all recipients |

## Requirements

- Android Studio or the Android command-line build tools.
- JDK 17.
- Android SDK Platform 35 and build tools available through Android Studio.
- Android 8.0 (API 26) or later to install the app.
- A Supabase project for accounts, friends, messages, attachments, and optional location sharing.

Project identifiers: Android application ID `dev.nook.messenger`; version `0.1.0` (version code `1`); minimum API 26; target API 35. The UI uses Jetpack Compose and Material 3. Supabase integration uses the Kotlin Supabase client and Ktor Android engine.

## Configure your own Supabase project and lock code

Each APK is built for one Supabase project and one activation code. Configure these before building; end users cannot enter a Supabase URL/key or change the activation code inside the APK.

1. Create a Supabase project. In **Project Settings → API**, copy the project URL and the **publishable** key. A legacy `anon` public key is also accepted.
2. At the repository root, copy the template:

   ```powershell
   Copy-Item store.properties.example store.properties
   ```

3. Edit `store.properties`:

   ```properties
   supabase.url=https://YOUR_PROJECT_ID.supabase.co
   supabase.publishableKey=sb_publishable_YOUR_PUBLIC_KEY
   app.lockCode=YOUR_4_TO_12_DIGIT_CODE
   ```

   Choose 4–12 numeric digits. Everyone installing the APK uses the same activation code. The code is compiled into the APK, so it is a convenience gate, not a secure account password.

   Use only a Supabase publishable key (or legacy anon key). **Never put a `service_role` or `sb_secret_` key in this file or in the Android app.** Gradle rejects secret key formats and validates that an app lock code was selected.

4. In the Supabase dashboard, open **SQL Editor** and run the required migration:

   - [`supabase/migrations/202611080001_messenger.sql`](supabase/migrations/202611080001_messenger.sql) — profiles, friend requests, friendships, messages, RLS policies, and the private `chat-attachments` storage bucket.

5. Optional migrations, depending on the feature you want:

   - [`supabase/migrations/202611080003_live_location.sql`](supabase/migrations/202611080003_live_location.sql) — live locations, readable only by the sharer and the selected friend under RLS.
   - [`supabase/migrations/202611080002_email_notifications.sql`](supabase/migrations/202611080002_email_notifications.sql) — server-side 30-minute email cooldown. This alone does not send email; configure the Edge Function and webhook below as well.

The Android app uses the publishable key directly. Database RLS policies are essential because a publishable key is public and can be extracted from an APK. `store.properties.example` contains placeholders only. The real `store.properties` and `local.properties` are ignored by Git. Do not commit them.


## Build the APK

After creating `store.properties`, run from the repository root in PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it over USB with Android Debug Bridge (ADB):

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio, let Gradle sync, then choose **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

### Share with friends

1. Run migration 001 on the Supabase project used for this build.
2. Build the APK and send that same APK to each friend, or publish it as a GitHub Release asset.
3. Each person installs it, enters the configured activation code, creates their own Store account, and confirms their email if Supabase email confirmation is enabled.
4. Users search for each other by username, send friend requests, accept the requests, and then open a one-to-one chat.

All users of a shared APK connect to the same configured Supabase project and use the same activation code, but each creates their own account.it is compiled into the APK. For repeatable APK updates, use a consistent signing key. The default debug APK is suitable for testing, not a production release.


## Configure email alerts (optional)

Email alerts require all of these steps:

1. Run migration 002 in the Supabase SQL Editor.
2. Create a Resend account, verify a sender domain, and create an API key.
3. Deploy [`supabase/functions/send-message-email`](supabase/functions/send-message-email) using the Supabase CLI.
4. Set these Edge Function secrets in Supabase: `RESEND_API_KEY`, `STORE_FROM_EMAIL`, `STORE_SUPABASE_SECRET_KEY`, and `STORE_MESSAGE_WEBHOOK_SECRET`. These are server secrets; never put them in `store.properties` or the APK.
5. In Supabase **Integrations → Database Webhooks**, create an `INSERT` webhook on `public.messages` targeting the Edge Function. Add the `x-store-webhook-secret` header with the same value as `STORE_MESSAGE_WEBHOOK_SECRET`.

The email tells the recipient that a friend sent a message, does not include message content, and includes the footer “Connect your Supabase with email today. Cheap pricing. Fast storage.” Resend requires a verified sender. Without the migration, secrets, deployed function, and webhook, email alerts are inactive.

## How to use the app

1. Launch Store and tap **OK** on the error-style launch screen.
2. Enter the `app.lockCode` value configured for the APK.
3. Create an account or sign in.
4. Search for a friend using their username and send a request.
5. Have them accept the request, then select them from the friend list.
6. Type a message and tap Send. Use the attachment, image, or audio controls to choose files from the phone.
7. For location, install optional migration 003, open a friend chat, and tap **Share live location**. Grant location permission. Stop sharing from the chat or the persistent Android notification.

## Repository layout

| Path | Purpose |
|---|---|
| `app/` | Native Android app (Kotlin, Jetpack Compose) |
| `app/src/main/java/dev/nook/messenger/MainActivity.kt` | Screens, activation gate, chat UI, attachment flow, and location UI |
| `app/src/main/java/dev/nook/messenger/SupabaseRepository.kt` | Supabase Auth, profiles, friend requests, messages, storage, and location records |
| `app/src/main/java/dev/nook/messenger/LiveLocationService.kt` | Visible Android foreground service for user-started live location |
| `store.properties.example` | Safe configuration template; copy to ignored `store.properties` |
| `supabase/migrations/` | SQL setup for messenger, optional email alerts, and optional live location |
| `supabase/functions/send-message-email/` | Optional Supabase Edge Function for email alerts |
| `server/` | Separate legacy laptop-hosted web prototype; not used by the Android Supabase app |

## Separate laptop web prototype

The `server/` folder contains a Node.js web prototype, separate from the Android Supabase chat. It needs Node.js 20 or later. From the `server` folder, run:

```powershell
npm start
```

This prototype keeps up to 500 messages in memory and writes attachments under `server/uploads`. It has no authentication or end-to-end encryption. Use only on a trusted private network for non-sensitive testing. Do not expose it to the public internet. The Android app does not require this server.

## Security and privacy notes

- Supabase Auth and RLS protect access according to the configured policies.
- The Supabase publishable/anon key and local activation code are embedded in the APK. Treat neither as a secret; never embed a service-role/secret key.
- The activation code is shared by everyone using the same APK and can be extracted from it. Use Supabase Auth for actual account access.
- Location is only requested after the user taps the sharing control. Android shows an ongoing notification while the foreground location service runs. The user can stop sharing at any time.
- Email delivery requires server-side secrets. Keep all Resend, Supabase service-role, and webhook secrets in Supabase Edge Function secrets only.

## Known limitations
- Email alerts are optional and inactive until fully configured.
- Android background location frequency depends on device power management, permission state, and location availability.

## License

No open-source license has been selected for this repository. Unless a license is added, GitHub's default copyright rules apply; publishing the source does not automatically grant permission to reuse or redistribute it. Choose and add a license before presenting this as an open-source project.
