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
security/      App lock (PulseChat PIN, optional fingerprint/face)
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
* **Media** – photos, videos, voice notes, documents, statuses and profile/group photos go to Cloud
  Storage when the project has a usable bucket. Projects without one (Cloud Storage needs the Blaze
  plan) automatically use the built-in media store instead: Firestore documents `media/{id}` with
  the file in 512 KB chunks (up to 32 MB per file), readable like Storage objects by signed-in users
  who hold the random ID, writable only by the uploader (`domain/MediaRefs.kt`, `firestore.rules`).
* **Gemini** – Firebase AI Logic (no API key in the APK). Features run only after explicit consent,
  output is labelled as AI-generated, and requests are rate limited client-side.
* **Encryption** – data is encrypted in transit (TLS) and at rest (Google Cloud). PulseChat does
  **not** provide end-to-end encryption and never claims to.

## Phase 1 features (Room schema v4)

| Feature | Where | How it works |
|---|---|---|
| Scheduled messages | Chat composer: type, tap the clock icon. Chat menu › Scheduled messages (edit, send now, delete, retry) | Stored in Room (`scheduled_messages`). A unique WorkManager job per message runs after the delay once a network is available, retrying with back-off. **Exactly once:** the scheduled message's ID is the Firestore message ID; an atomic Room claim (PENDING→SENDING) lets one attempt run at a time; each attempt asks the server whether the message already exists; and the rules reject re-creating an existing message. Runs that start too early (clock set back) re-queue themselves; boot, app update and clock/time-zone changes re-queue everything (`TimeChangeReceiver`). |
| Message reminders | Long-press a message › Remind me. Chats menu › Reminders | Room (`reminders`) + AlarmManager (`RTC_WAKEUP`, allowed while idle). Exact when the user allows *Alarms & reminders*, otherwise Android may deliver a few minutes late (explained in the app, with a link to the setting). Notification actions: Done, Snooze 1 hour; tapping opens the message. Re-armed after reboot/clock change. |
| In-chat translation | Long-press › Translate; target language per message or in Settings › Chats | On-device (ML Kit): message text never leaves the phone. Language packs (~30 MB each) are downloaded once, only after the user agrees. The original stays in the bubble; the translation card can be hidden; cached per message/language and invalidated when the message is edited. |
| Photo editor | Photo preview before sending › Edit (pencil) | Crop, draw, text, arrows, undo. *Blur* pixelates the selected pixels in the exported JPEG (not an overlay), so the hidden area can't be recovered from the sent photo. |
| Chat folders | Chats tab chips; *New folder* / *Edit folders* chip; multi-select › Add to folder | Room (`chat_folders`, `chat_folder_members`), per account on this device. Rename, reorder, delete (chats stay). |
| Voice-message transcription | Long-press a voice message › Transcribe | Gemini (Firebase AI Logic) after explicit action + AI consent; transcript saved locally and shown under the message. |
| Summary since last visit | Banner "N new messages since your last visit › Summarize" | Messages after the read watermark at opening, sent with neutral aliases (`m1`…); every summary point links to the messages it is based on (unknown references are discarded). |
| Tasks & events | Chat menu › Find tasks & events | Gemini proposes items with times; nothing is created until the user selects, edits and confirms; confirmed items become reminders. |
| Media, links and docs | Chat menu › Media, links and docs | Photos/videos grid, documents, links (extracted from text) and voice messages; only already-downloaded media is shown as thumbnails (browsing never downloads behind the user's back). |
| Diagnostics | Settings › Diagnostics | Internet, server reachability (timed read from the server), live sync and last server update, outbox and scheduled messages, notification permission/channels/push registration, exact alarms, battery and background restrictions, call permissions and audio options, last call quality, recent problems in plain language, and a copyable report without personal data. |
| Call audio | Calls; Settings › Diagnostics › Calls | See [docs/CALL_AUDIO.md](docs/CALL_AUDIO.md): investigation, configuration, invariants and the two-device test plan. |

**Setup needed for phase 1:** nothing for scheduling, reminders, folders, the photo editor, the media
browser and diagnostics. Translation downloads language packs from Google on first use (no
configuration). Transcription, summaries and tasks need **Firebase AI Logic** enabled for the project
(Firebase console › AI Logic › Get started › Gemini Developer API, available on the no-cost plan);
until then the app explains that the AI service isn't available. App Check enforcement is recommended
for AI Logic in production.

## Firebase setup (required)

The app is configured for Firebase project `gen-lang-client-0426516505` and the named Firestore
database `ai-studio-android-pulsecha-2c0cef4c-b560-4944-a812-3a390d3cee6a`
(`app/src/main/res/values/firebase_applet_config.xml`).

1. **Authentication** → Sign-in method: enable **Email/Password** and **Google**. Add the SHA-1/SHA-256
   of your signing keys to the Android app in Project settings (see *Stable signing key* below; every
   CI run prints the fingerprints of its APK in the "CI log digest (Android)" check and job summary).
2. **Deploy rules, indexes/TTL and Storage rules** (from this `pulsechat/` directory):
   ```bash
   npm install -g firebase-tools
   firebase login
   firebase deploy --only firestore          # rules + TTL settings (works on the free plan)
   firebase deploy --only storage            # only once Cloud Storage is set up (Blaze plan)
   ```
   Without the CLI, paste `firestore.rules` into Firebase console → Firestore → Rules (database
   `ai-studio-android-pulsecha-…`) and press **Publish**. Re-publish whenever this file changes
   (for example the `media` rules of the built-in media store).
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

PulseChat is a native Android (Gradle) project. It is not a web app, so npm / Capacitor
commands (`npm run build`, `npx cap sync`) do not apply.

Prerequisites: the latest stable Android Studio (or JDK 17+, Gradle 9.3.1 and Android SDK 36).

1. Open **this `pulsechat/` directory** in Android Studio (not the repository root) and wait for the
   Gradle sync.
2. Run the `app` configuration on a device or emulator (API 24+), or use
   **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. The APK is written to
   `app/build/outputs/apk/debug/app-debug.apk`. From a terminal: `gradle assembleDebug`.
3. Optional: copy `.env.example` to `.env` for local overrides (no Gemini key is required or used).

The debug signing config expects `debug.keystore` in this directory. AI Studio provides it; for
other builds the `createDebugKeystore` task creates it on the first debug build, with the standard
Android debug credentials. The file is git-ignored. Each machine gets its own key, so uninstall a
build signed elsewhere before installing yours.

Ready-made debug APKs: every CI run uploads the artifact **pulsechat-debug-apk** (Actions → Android
CI → a run → Artifacts).

### Stable signing key (Google sign-in on CI-built APKs)

Google sign-in only works for APKs whose signing-certificate fingerprint is registered in Firebase.
By default every CI run creates a new debug key, so each new APK needs its fingerprint added. To sign
every CI build with one key instead, create it once and store it as a repository secret:

```bash
keytool -genkeypair -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
keytool -list -v -keystore debug.keystore -storepass android     # SHA-1 and SHA-256 to add in Firebase
base64 -w0 debug.keystore                                          # PowerShell: [Convert]::ToBase64String([IO.File]::ReadAllBytes("debug.keystore"))
```

Add the base64 text as the secret **DEBUG_KEYSTORE_BASE64** (GitHub → Settings → Secrets and
variables → Actions), register both fingerprints in Firebase, and keep the file in this directory
for local builds so they use the same key. Do not commit it.

Release builds read `KEYSTORE_PATH`, `STORE_PASSWORD` and `KEY_PASSWORD` from the environment.

## Tests

| Suite | Location | How to run |
|---|---|---|
| Domain rules (delivery, edit/delete windows, groups, mentions, presence, calls…) | `app/src/test/java/com/example/domain` | `gradle testDebugUnitTest` |
| Room v2→v3 and v3→v4 migrations + DAO queries, incl. the exclusive scheduled-message claim (Robolectric) | `app/src/test/java/com/example/data/db` | `gradle testDebugUnitTest` |
| Call audio routing/processing policy and WebRTC stats parsing | `app/src/test/java/com/example/calls` | `gradle testDebugUnitTest` |
| Compose UI components + Arabic/RTL (Robolectric) | `app/src/test/java/com/example/ui` | `gradle testDebugUnitTest` |
| Firestore security rules (emulator) | `firebase/rules-tests` | `firebase emulators:exec --only firestore --project demo-pulsechat "npm --prefix firebase/rules-tests test"` |
| Cloud Functions payload logic | `firebase/functions` | `npm ci && npm run build && npm test` |
| Instrumented smoke test: real Application start, sign-in screen, local form validation | `app/src/androidTest/java/com/example/AppLaunchTest.kt` | `gradle connectedDebugAndroidTest` (device/emulator) |
| End-to-end flows on a device against the Firebase emulators (Auth, Firestore with `firestore.rules`, Storage) | `app/src/androidTest/java/com/example/*E2ETest.kt` | see below |
| Web client on the same backend: unit + contract tests, integration tests against the emulators and rules | `../web/test` | see [web/README.md](../web/README.md#step-6-run-locally-against-the-emulators-and-the-tests) |
| Android ↔ Web in real time (web client on the CI host, Android app on the emulator) | `app/src/androidTest/java/com/example/WebInteropE2ETest.kt`, `../web/test/android-peer.ts` | CI `e2e` job (runner argument `webPeer`) |

GitHub Actions (`.github/workflows/android-ci.yml`) runs all of them on every push: `assembleDebug`,
JVM tests, `assembleRelease`, the backend tests, the web client tests, and an `e2e` job that boots an API 34 Android
emulator and runs the instrumented tests against the Firebase emulators. The `e2e` job is marked
`continue-on-error` because hosted Android emulators are occasionally flaky; check its own result.

### End-to-end tests (Firebase emulators)

`MessagingE2ETest` and `GroupsStatusPrivacyE2ETest` drive the real repositories, sync engine and
UI of a debug build. Accounts take turns on one device and local storage is wiped between them, so
everything a user sees must come from the server. They cover:

* registration and sign-in by username and by 10-digit ID; user search by exact username or ID only
  (display names and partial usernames find nobody);
* one direct chat per pair, text and photo messages (Storage upload, download on the other side);
* the built-in media store (`BuiltInMediaStoreE2ETest`): photo, multi-chunk voice note and profile
  photo without Cloud Storage, identical bytes on the other device, owner-only writes; the home
  screen's per-tab titles and menus;
* unread counters, read receipts and reactions reaching the sender, edit and delete for everyone;
* groups: creation, sync to members, disappearing timer, admin-only sending, leaving;
* per-user chat preferences (pin, favourite, mute, unread mark, archive) and saved contacts
  restored on a new device;
* text and photo statuses delivered to the audience, views and the owner's viewer list;
* phase 1 (`Phase1FeaturesE2ETest`): a scheduled message raced by WorkManager and three direct
  attempts, then a simulated crash after sending and an explicit retry, exists exactly once on the
  server and reaches the recipient once; a scheduled message to a contact blocked meanwhile fails with a
  reason and can be deleted; chat-folder filtering, the links tab of the media browser and the
  diagnostics server check in the UI; on-device language identification in the translation flow;
* security rules in the same run: blocked users' messages fail, and writing another user's profile,
  reading their private settings, changing their reaction, or posting as a non-admin in an admin-only
  group are rejected with `PERMISSION_DENIED`.

To run them locally (no Firebase login needed; the project id must match `google-services.json`):

```bash
firebase emulators:start --only auth,firestore,storage --project gen-lang-client-0426516505
# with an Android emulator running (Gradle 9.3.1, as in CI - the repo has no gradlew script):
gradle connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.firebaseEmulatorHost=10.0.2.2
```

Without the `firebaseEmulatorHost` argument the end-to-end tests are skipped. The switch to the
emulators (`util/EmulatorConfig.kt`) is set only by the test runner and only honoured in debug
builds; cleartext HTTP to `10.0.2.2`, `localhost` and `127.0.0.1` is allowed by a debug-only network
security config (`src/debug`). Release builds always use the real backend with cleartext blocked.

## Known limitations

* No end-to-end encryption; message content is protected by Firestore rules and TLS.
* Storage rules cannot read the named Firestore database, so chat media is protected by
  unguessable paths and Firebase download tokens rather than by membership checks. The built-in
  media store uses the same model (random IDs) and is limited to 32 MB per file.
* Videos are not re-encoded, so long recordings can exceed the size limit.
* Group calls are not supported (one-to-one calls only). Calls need TURN for some networks.
* New group members can read history sent before they joined.
* Presence is heartbeat based (offline is shown up to ~100 s after a device disappears).
* Push notifications require the Cloud Functions to be deployed (Blaze plan).
* Scheduled messages are sent by the phone that scheduled them: it must be on and online at (or after)
  the scheduled time, and Android may defer the job by minutes in Doze/battery saver (longer for apps
  in the "rare" standby bucket). Server-side scheduling would need Cloud Functions (Blaze). Signing out
  cancels the account's unsent scheduled messages. Only text can be scheduled.
* Reminders are exact only with the *Alarms & reminders* permission; folders, reminders, scheduled
  messages, translations and transcripts are stored on this device (not synced between devices).
* On-device translation quality is "casual" (ML Kit, pivoting through English between two non-English
  languages); the translation libraries add about 25 MB to a universal APK (Play delivers one ABI).
* Transcription, summaries and task extraction send the selected content to Gemini (with consent) and
  need Firebase AI Logic; voice messages over 15 MB are not transcribed.
* The call-audio changes are verified by builds, unit tests and emulator tests only. Echo behaviour
  depends on the phone's hardware and must be confirmed with the two-device plan in
  [docs/CALL_AUDIO.md](docs/CALL_AUDIO.md).
