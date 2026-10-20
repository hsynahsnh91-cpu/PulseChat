# PulseChat

PulseChat is an Android messaging app (Kotlin, Jetpack Compose, Material 3) backed by Firebase.
It supports one-to-one and group chats, media and voice messages, 24-hour status updates,
WebRTC voice/video calls, push notifications, privacy controls, an optional Gemini assistant
and full Arabic/English localization with right-to-left layouts.

AI Studio project: https://ai.studio/apps/2c0cef4c-b560-4944-a812-3a390d3cee6a

---

## Architecture

```
ui/            Compose screens + per-screen ViewModels (home, chat, info, contacts, settings, status, calls, auth)
domain/        Pure Kotlin business rules (delivery states, edit/delete windows, group roles, mentions,
               presence, disappearing messages, call state machine, chat-list ordering) - unit tested
data/db        Room database (single source of truth for the UI), DAOs, schema migrations
data/remote    Firestore schema + mappers
data/repository Auth, sync, chats/messages, users/contacts/blocks, media, statuses, calls, AI
calls/         WebRTC session + Firestore signalling + foreground service
notifications/ FCM service, notification channels, inline reply / mark-as-read actions
security/      App lock (biometric / device credential)
firebase/      Cloud Functions (TypeScript) and Firestore rules tests
```

* **Identity** – Firebase Authentication. Username (or 10-digit ID) + password accounts use the
  Email/Password provider with a synthetic, non-deliverable login address derived from the
  username; Google sign-in uses Credential Manager. Accounts that existed only on a device in
  earlier versions are verified once, upgraded to Firebase accounts, and their local history is
  re-keyed to the new UID.
* **Data flow** – every mutation is written to Room first (optimistic UI) and then committed to
  Firestore; Firestore listeners are merged back into Room by `SyncManager` with idempotent,
  ID-keyed upserts and `updatedAt`-based out-of-order protection. Message IDs are client UUIDs,
  so retries never duplicate messages.
* **Delivery states** – `PENDING → SENDING → SENT → DELIVERED → READ` (or `FAILED`) advance only on
  real events: server acknowledgement and recipients' receipt watermarks.
* **Security** – `firestore.rules` and `storage.rules` enforce participants, admin roles, blocking,
  edit (15 min) / delete-for-everyone (48 h) windows, per-user reactions/votes/receipts, privacy of
  last seen / photo / about, status audiences and call signalling transitions.
* **Gemini** – Firebase AI Logic (no API key in the APK). Features run only after explicit consent,
  output is labelled as AI-generated, and requests are rate limited client-side.
* **Encryption** – data is encrypted in transit (TLS) and at rest (Google Cloud). PulseChat does
  **not** provide end-to-end encryption and the app says so.

## Firebase setup (required)

The app is configured for Firebase project `gen-lang-client-0426516505` and the named Firestore
database `ai-studio-android-pulsecha-2c0cef4c-b560-4944-a812-3a390d3cee6a`
(`app/src/main/res/values/firebase_applet_config.xml`).

1. **Authentication** → Sign-in method: enable **Email/Password** and **Google**. Add the SHA-1/SHA-256
   of your signing keys to the Android app in Project settings.
2. **Deploy rules, indexes/TTL and Storage rules** (from this `pulsechat/` directory):
   ```bash
   npm install -g firebase-tools
   firebase login
   firebase deploy --only firestore,storage
   ```
   `firestore.indexes.json` also defines TTL policies on `messages.expiresAt` and
   `statuses.expiresAt` (disappearing messages and 24-hour statuses).
3. **Cloud Functions** (push notifications; requires the Blaze plan):
   ```bash
   firebase deploy --only functions
   ```
4. **Firebase AI Logic** → enable it and choose the Gemini Developer API (the Gemini key stays on
   Google's servers). Recommended: enable **App Check** (Play Integrity for release builds; the debug
   provider is installed in debug builds – register its debug token printed in logcat).
5. **Calls behind strict NATs** need a TURN relay. Create the Firestore document `config/webrtc` with
   `iceServers: [{ urls: ["turn:your.server:3478"], username: "...", credential: "..." }]`
   (readable by signed-in users, writable only from the console/Admin SDK).

Without steps 2–3 the app shows a "backend not configured" banner and messages cannot sync; without
step 3 notifications only arrive while the app is open.

## Build and run

Prerequisites: Android Studio (or JDK 17+ and Gradle 9.3.1), Android SDK 36.

1. Open the `pulsechat/` directory in Android Studio.
2. Copy `.env.example` to `.env` if you need local overrides (no Gemini key is required or used).
3. The debug signing config expects `debug.keystore` in this directory (AI Studio provides it).
   For local builds either copy your `~/.android/debug.keystore` here or temporarily remove the
   `signingConfig = signingConfigs.getByName("debugConfig")` line.
4. Run the `app` configuration on a device or emulator (API 24+).

Release builds read `KEYSTORE_PATH`, `STORE_PASSWORD` and `KEY_PASSWORD` from the environment.

## Tests

| Suite | Location | How to run |
|---|---|---|
| Domain rules (delivery, edit/delete windows, groups, mentions, presence, calls…) | `app/src/test/java/com/example/domain` | `gradle testDebugUnitTest` |
| Room v2→v3 migration + DAO queries (Robolectric) | `app/src/test/java/com/example/data/db` | `gradle testDebugUnitTest` |
| Compose UI components + Arabic/RTL (Robolectric) | `app/src/test/java/com/example/ui` | `gradle testDebugUnitTest` |
| Firestore security rules (emulator) | `firebase/rules-tests` | `firebase emulators:exec --only firestore --project demo-pulsechat "npm --prefix firebase/rules-tests test"` |
| Cloud Functions payload logic | `firebase/functions` | `npm ci && npm run build && npm test` |

GitHub Actions (`.github/workflows/android-ci.yml`) runs all of them plus `assembleDebug` and
`assembleRelease` on every push.

## Known limitations

* No end-to-end encryption; message content is protected by Firestore rules and TLS.
* Storage rules cannot read the named Firestore database, so chat media is protected by
  unguessable paths and Firebase download tokens rather than by membership checks.
* Group calls are not supported (one-to-one calls only). Calls need TURN for some networks.
* New group members can read history sent before they joined.
* Presence is heartbeat based (offline is shown up to ~100 s after a device disappears).
* Push notifications require the Cloud Functions to be deployed (Blaze plan).
