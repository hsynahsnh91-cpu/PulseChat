# PulseChat upgrade – implementation report

Status legend: **Verified** = implemented and covered by automated tests that exercise the
behaviour (JVM/Robolectric, the Firestore rules emulator, and/or end-to-end tests on an Android
emulator against the Firebase emulators; CI: `.github/workflows/android-ci.yml`); **Partial** =
implemented and building in CI, but part of the behaviour is not verified (needs a person, real
hardware or the deployed Firebase project) or has a documented gap; **Missing** = not implemented.

| # | Feature | Status | Evidence / gap |
|---|---|---|---|
| 1 | Primary navigation (Chats / Updates / Groups / Calls), top bar with expandable search, overflow, profile access, preserved tab | Partial | Built; search filtering unit-tested; no instrumented UI test of the bar/tabs |
| 2 | Rich chat rows (avatar, preview, time, unread badge, ticks, muted/pinned/favourite/draft/typing) | Verified | `ComponentsUiTest` (ChatRow), `DaoTest.chatListMembershipIsExactNotSubstring` |
| 3 | Ordering by latest activity, pinned first, max 3 pins | Verified | `ChatListPolicyTest`, `DaoTest.pinnedCountAndPreferences` |
| 4 | Filters All / Unread / Favourites / Groups and chat search (Arabic-normalised) | Verified | `ChatListPolicyTest`, `MentionsAndTextTest.normalizationAndSearch` |
| 5 | Pin, archive/restore, mark read/unread, mute, favourite (persisted, synced per user) | Verified | DAO tests (local); E2E `GroupsStatusPrivacyE2ETest`: pin, favourite, mute, unread mark and archive restored from `chatPrefs` on a new device; mark-as-read E2E in `MessagingE2ETest` |
| 6 | Multi-select chat actions; delete/clear chat with confirmation (keep starred) | Partial | Confirm dialog + clear semantics tested; multi-select flow not UI-tested |
| 7 | Empty / loading / offline / sync-error states, new-chat FAB | Partial | Built; not UI-tested |
| 8 | Conversation header with privacy-aware presence, typing, call/search/menu actions | Partial | Presence privacy verified (rules + unit); header not UI-tested |
| 9 | Rendering of all message types (text/links/mentions/image/video/document/voice/contact/location/poll/reply/forwarded/system/AI), grouping, day headers | Partial | `MessageTypesUiTest`, `ComponentsUiTest`, `MessagePolicyTest`; image/video rendering not device-tested |
| 10 | Delivery states driven by real events; retry without duplicates | Verified | `DeliveryPolicyTest`, `DaoTest.receiptsAdvanceOnlyAcknowledgedOwnMessages`, rules receipts test, UUID idempotency; E2E: SENT only after the server ack, READ only after the recipient's receipt, FAILED ("denied") when the server rejects |
| 11 | Long-press menu, multi-select, swipe-to-reply, message info | Partial | Action availability `MessageTypesUiTest.actionSheetOffersEditOnlyInsideTheWindow`; gestures not UI-tested |
| 12 | Composer (multiline, emoji, attach, camera, mic/send switch, reply/edit preview, enter-to-send) | Verified | `ComponentsUiTest.composerSwitchesBetweenMicAndSend`; drafts DAO-tested |
| 13 | Reply with navigable original-message preview | Verified | `ComponentsUiTest.replyQuoteNavigatesToOriginal`, `DaoTest` jump position |
| 14 | Reactions (one per user, counts, who reacted) | Verified | UI test + rules `reactions … own entry` + `MessagePolicyTest`; E2E: reaction stored and synced to the sender, changing another user's reaction denied |
| 15 | Forwarding to multiple chats with "Forwarded" label | Partial | Implemented; not tested |
| 16 | Starred messages (toggle, screen, jump to message) | Partial | Starred query DAO-tested; screen not UI-tested |
| 17 | Edit within 15 minutes with "edited" label (server-enforced) | Verified | `MessagePolicyTest`, rules `only the author can edit … 15 minutes`, footer UI test; E2E: author edit accepted by the server |
| 18 | Delete for me / delete for everyone (48 h, content wiped) | Verified | `MessagePolicyTest`, rules test, `DaoTest`; E2E: delete for everyone removes text and media on the server |
| 19 | Typing indicators (throttled, expiring) | Partial | Timeout logic + rules ownership tested; not live-tested |
| 20 | Online presence / last seen with privacy and reciprocity | Verified | rules `last seen honours visibility, contacts and reciprocity`, `PoliciesTest` |
| 21 | Unread counters, read receipts (toggle + reciprocity), unread separator, jump to latest | Verified | `DaoTest.unreadCountsOnlyIncomingAfterWatermark`, `MessagePolicyTest` separator; E2E: unread counter 2 → 0 on read, receipt stored on the server and shown as READ to the sender |
| 22 | @mentions by stable user IDs (notify even when muted) | Verified | `MentionsAndTextTest`, functions `muted chats notify only on mention` |
| 23 | Disappearing messages per chat (24 h / 7 d / 90 d) | Partial | Policy + expiry query + rules clean-up tested; E2E: group timer stored on the server, synced to members, new messages carry `expiresAt`. Server-side TTL purge needs deployment |
| 24 | In-chat search with highlighting and result navigation; global message search with filters | Verified | `DaoTest` (in-chat search, jump position, global search with type/link filters), `MentionsAndTextTest.occurrences` |
| 25 | Draft persistence, duplicate and out-of-order event handling | Verified | `DaoTest`, `MessagePolicyTest.timelineOrderingAndDedupe` |
| 26 | Attachments: gallery, camera photo/video, documents, contact, current location, polls | Partial | Poll UI + rules tested; E2E sends a `FileProvider` photo URI (the same path picker/camera results take); the system pickers and camera need a device and a person |
| 27 | Media upload/download with progress, cancel, retry; viewer; auto-download rules; storage screen | Partial | Auto-download policy unit-tested; E2E: photo upload to Storage, download on the recipient, status photo upload. Cancel/retry UI, viewer and storage screen not device-tested |
| 28 | Voice messages: real recording, waveform, seek, 1x/1.5x/2x, audio focus | Partial | Waveform + UI tested; MediaRecorder/MediaPlayer need a device |
| 29 | People: search by username/ID/name, no duplicate DMs, contact profiles, saved contacts | Verified | E2E: search by username, 10-digit ID and display name; one DM per pair (unit + rules + E2E); saved contacts restored on a new device; profile field visibility enforced by rules tests (profile screen itself not UI-tested) |
| 30 | Block / unblock / report (server-enforced) | Verified | rules: blocked messaging, blocked calls, photo hidden from blocked users, write-only reports; E2E: block list restored on a new device, a blocked user's message is rejected by the server (FAILED "denied") |
| 31 | Groups: create, add/remove, promote/demote, admin-only send/edit, leave with admin hand-over, creator protection | Verified | `GroupPolicyTest`, four group rules tests; E2E: create, sync to a member, disappearing timer, admin-only sending refused by the app and by the rules, member leaves |
| 32 | Status / Updates: text/photo/video, 24 h expiry, viewed/unviewed, viewer, audience privacy, viewer list, delete | Partial | `PoliciesTest` grouping/audience, rules audience/views tests; E2E: text + photo status delivered to the contacts audience, view recorded, viewer list for the owner. Video status and the full-screen viewer not device-tested |
| 33 | One-to-one voice/video calls: state machine, Firestore signalling, WebRTC, busy/missed/timeout, history | Partial | `CallStateMachineTest` (no auto-accept), rules call tests; real media between devices not verified; TURN needed on strict networks; group calls not supported |
| 34 | Push notifications: channels, per-chat grouping, privacy previews, inline reply/mark read, dedupe, call notifications | Partial | Functions policy tests; requires deployed functions + FCM |
| 35 | Profile editing and privacy settings (photo/about visibility, status audience, read receipts, default timer, blocked list) | Partial | Enforcement verified by rules tests; profile photo upload not tested |
| 36 | Account security: Firebase Auth (username/ID + password, Google), legacy account upgrade, no password-less switching, backups exclude tokens, app lock, sign-out | Partial | Rules identity/handle tests, legacy re-key DAO test; E2E: username registration (transactional handles), sign-in by username and by 10-digit ID, sign-out. Google sign-in and the biometric prompt need a device and a person |
| 37 | Gemini: assistant chat, reply suggestions, polish, summary; explicit consent, labelled output, rate limit, no client key | Partial | Consent gating + labels built; Firebase AI Logic must be enabled in the console |
| 38 | Design system: original indigo/teal identity, light/dark/system themes, Arabic/English with RTL | Verified | `ArabicLocaleUiTest` (Arabic strings, plurals, RTL); 546 strings in both locales |

Not implemented by design / out of scope: end-to-end encryption (not claimed anywhere),
group calls, live location sharing, phone-contact import.

## Test results (CI)

Run [39432786302](https://github.com/hsynahsnh91-cpu/PulseChat/actions/runs/39432786302) on commit `aba23b0`, all jobs green:

* `assembleDebug`, `assembleRelease` (with lint-vital): passing.
* JVM/Robolectric unit tests: 64/64 (domain policies, Room migration v2→v3, DAO queries, Compose UI
  components and message types, Arabic/RTL).
* Firestore rules (official emulator): 24/24.
* Cloud Functions: TypeScript build + 4/4 unit tests.
* Instrumented, Android API 34 emulator (`e2e` job): 4/4.
  * `AppLaunchTest`: the real Application starts, the sign-in screen renders, local validation works.
  * `MessagingE2ETest` and `GroupsStatusPrivacyE2ETest`: complete user flows against the Firebase
    emulators (Auth, Firestore running this repo's `firestore.rules`, Storage); see README › Tests.
    The same runs assert that forbidden reads/writes are rejected with `PERMISSION_DENIED`.
* Not run (needs the real project, hardware or a person): anything against the deployed Firebase
  project, Google sign-in, FCM delivery, WebRTC media between two devices, the biometric prompt,
  system pickers/camera, TTL purge.

## Database migration

Room `2 → 3` (`data/db/Migrations.kt`) only adds columns/tables and back-fills data: existing
chats are marked device-local (read-only), pinned order and read state are preserved, blocked
flags move to `blocked_users`. The only destructive fallback is from version 1 (pre-release,
no recoverable schema). Exported schemas live in `app/schemas`.

## Backend changes

`firestore.rules`, `storage.rules`, `firestore.indexes.json` (TTL policies), `firebase.json`,
`.firebaserc`, Cloud Functions in `firebase/functions`, rules tests in `firebase/rules-tests`.
The previous rules allowed any signed-in user to read and write every document (and exposed
password hashes); they have been replaced.

## Required external configuration

See `README.md` › Firebase setup: enable Email/Password + Google auth providers and SHA
fingerprints, publish the Firestore rules (free plan); functions, TTL policies and Storage need the
Blaze plan (each needs billing). Enable Firebase AI Logic (Gemini Developer API) with App Check (the
setup enforces it, and it is required from 2 November 2026), optionally configure TURN in `config/webrtc`.
Revoke the Gemini/AI Studio token that was previously hard-coded in `GeminiApiService.kt`.

---

# Phase 1 feature expansion and call-audio fix (October 2026)

## Repository audit (before changes)

* **Reused:** Room as the single source of truth (`AppDatabase`, DAOs, migration pattern), the
  idempotent message pipeline (`ChatRepository.pushMessage`, client UUID = Firestore document ID),
  `SyncManager`, `NotificationHelper` channels/intents, `AiRepository` (Firebase AI Logic, consent,
  rate limit), `MediaRepository` (capture files, `FileProvider`, downloads), navigation
  (`PulseNavHost`), settings components (`SettingsItem`, `SwitchItem`, `ChoiceDialog`, `Banner`,
  `EmptyState`), localization (`values`, `values-ar`), and the emulator E2E harness.
* **Not present before:** background work (no WorkManager), alarms, on-device ML, image editing.
  New dependencies (justified): `androidx.work:work-runtime-ktx` 2.11.2 (scheduled messages),
  `com.google.mlkit:translate` 17.0.3 + `language-id` 17.0.6 (private on-device translation).
* **Calls:** single `CallManager` (WebRTC + Firestore signalling) and `CallAudio` (mode/focus/route);
  see [CALL_AUDIO.md](CALL_AUDIO.md) for the audit of the audio path.

## Plan and order of implementation

1. Critical call-audio fix (investigation, pipeline invariants, routing, processing, diagnostics).
2. Room v4 (new tables only) + DAOs + migration test.
3. Scheduled messages (exactly-once), reminders (alarms + notifications), folders, media browser,
   diagnostics, translation, photo editor, AI features - each with EN/AR strings, RTL and states.
4. Tests (JVM, Robolectric, rules emulator, instrumented E2E), docs, CI validation.

## Feature status

| # | Feature | Status | Evidence / gap |
|---|---|---|---|
| P1 | Scheduled messages: schedule, edit, cancel, send now, retry, duplicate prevention | Verified (emulator) | `ScheduledPolicyTest`; `FeatureDaoTest` (8 concurrent claims → 1 winner; stale take-over; edit/delete blocked while sending); rules test "a message ID can be created only once"; `Phase1FeaturesE2ETest`: WorkManager + 3 racing attempts + simulated crash + explicit retry → exactly 1 server copy, recipient receives once; blocked-meanwhile → FAILED with reason. Timing under Doze needs a real device. |
| P2 | Message reminders with local notifications and persistent state | Partial | `ReminderTimesTest`, `FeatureDaoTest.reminderFiresOnlyOnce`, migration test; alarm delivery, notification actions and reboot re-arming need a device. |
| P3 | In-chat translation (original preserved, target language choice) | Partial | E2E: on-device language identification (same language → no translation; Arabic → language pack required before download). Downloading packs and translation quality not exercised in CI. |
| P4 | Photo annotation: crop, draw, text, arrows, privacy blur | Partial | `ImageEditMathTest` (fit, crop clamping, irreversible pixelation, pixel boxes); editor UI not instrumented. |
| P5 | Custom chat folders with persistent membership and filtering | Verified | `FeatureDaoTest.folderMembershipIsPersistedAndDeletedWithTheFolder`, migration test; E2E: folder chip filters the chat list, empty-folder state. |
| P6 | Voice-message transcription | Partial | Consent-gated flow, persistence (`TranscriptDao` test); needs Firebase AI Logic enabled - not run against Gemini in CI. |
| P7 | AI summary since last visit with references | Partial | `AiParsingTest` (JSON extraction, unknown references dropped); `countIncomingSince` DAO test; needs AI Logic. |
| P8 | AI task/event extraction with confirmation before reminders | Partial | `AiParsingTest` (items, ISO dates with/without offset, malformed output); confirmation sheet creates reminders only for confirmed items; needs AI Logic. |
| P9 | Media and link browser per conversation | Verified | `LinkExtractorTest`, DAO queries (`observeByTypes`, `observeWithLinks`); E2E: link listed under Links. |
| P10 | Diagnostics screen (connectivity, sync, notifications, understandable errors) | Verified (partly) | E2E: server check reaches the emulated backend; problem codes mapped to plain-language explanations; notification/battery/exact-alarm states depend on the device. |
| C1 | Call echo / feedback / unstable volume | Partial - **needs two physical phones** | Root-cause review and fixes in [CALL_AUDIO.md](CALL_AUDIO.md); `AudioRoutePolicyTest`, `AudioProcessingPolicyTest`, `CallStatsParserTest`; `CallLifecycleE2ETest` (real WebRTC + emulator signalling): a double start builds one pipeline, hang-up releases all peer connections/tracks, 5 back-to-back calls never accumulate. Audio quality and echo not verifiable without devices. |

## Database migration v3 → v4

`Migrations.MIGRATION_3_4` only creates the tables `scheduled_messages`, `reminders`,
`chat_folders`, `chat_folder_members`, `message_translations`, `message_transcripts` and
`diagnostic_events` (with their indices). No existing table or row is changed. `Migration3To4Test`
builds the exact v3 schema from `schemas/.../3.json`, fills it, and lets Room migrate and validate
every table. The exported `4.json` matches the migration SQL.

## Backend review

* **Rules:** no new collections are written. Scheduled messages are sent through the normal message
  create rule (sender = signed-in participant, not blocked, admins-only groups enforced); the new
  rules test proves a second create of the same message ID is rejected, which is the server-side half
  of the exactly-once guarantee. Reading a not-yet-existing message ID (the pre-send check) is allowed
  for participants only.
* **Indexes:** no new queries need composite indexes. **Storage:** unchanged. **Cloud Functions:**
  unchanged; the existing message-created trigger sends push for scheduled messages like any other.
* **AI:** Firebase AI Logic holds the Gemini key server-side; per-user quotas are not enforceable in
  Firestore rules - enable App Check and AI Logic quotas in the console. Translation needs no backend.

## Verification (CI)

Run [39647918513](https://github.com/hsynahsnh91-cpu/PulseChat/actions/runs/39647918513) (push, commit
`3d7c18b`) and the PR run on the same commit: all jobs green.

* `assembleDebug`, `assembleRelease` with lint-vital, Windows fresh-clone build: passing.
* JVM/Robolectric unit tests: **109/109** (72 before + 37 new: call routing/processing/stats, scheduling,
  reminder times, links, AI parsing, editor geometry, v3→v4 migration, feature DAOs).
* Firestore rules (emulator): **28/28** (new: a message ID can be created only once). Functions: 4/4.
* Web client: unit/contract 17/17, emulator integration 7/7 (one earlier run hit a known emulator
  flake, `RESOURCE_EXHAUSTED`, unrelated to these changes; it passed on re-run).
* Instrumented on an API 34 emulator against the Firebase emulators: **10/10** - the 6 existing suites
  (launch, messaging, groups/status/privacy, built-in media store, web interop) plus
  `Phase1FeaturesE2ETest` (3 tests) and `CallLifecycleE2ETest`.

**Not verified (needs real devices, people or the deployed project):** echo/feedback on two phones
(test plan in CALL_AUDIO.md), Bluetooth/wired routing, Doze timing of scheduled messages and
reminders, notification actions and reboot re-arming, ML Kit language-pack download and translation
quality, the photo editor on real photos, Gemini transcription/summary/extraction (AI Logic is not
enabled on the production project yet).

# UI/UX redesign — "Graphite Titanium" (October 2026)

Design system details: [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md).

## Problems found (audit)

* Identity: indigo/violet everywhere — theme primary, outgoing bubbles, AI colours, mention colour,
  avatar defaults, the call-screen gradient, the notification accent and the launcher logo (a neon
  cyan→purple JPG). Material roles that were not set (surface containers, surface tint) fell back to
  Material's purple baseline, tinting menus, sheets, dialogs and the navigation bar.
* Insets: the chat padded its bottom twice (Scaffold padding not consumed + `imePadding()` + the
  composer's own `navigationBarsPadding()`), leaving a band under the composer and an extra gap above
  the keyboard. Status-bar icons followed the device theme, not the in-app theme (invisible clock when
  they differ) and stayed dark on the always-dark call/viewer screens.
* Layout: on the sign-in screen the recent-account chips were squeezed into a `Row` — at 320dp with
  large text one chip became a one-character-wide column and pushed the form below the fold; the
  message actions sheet did not scroll (Delete unreachable on short phones); the quick-reaction row
  overflowed 320dp; attachment labels broke mid-word; empty-state text ran under the floating buttons;
  bubble width was 82% of any screen (very long lines on tablets); English messages in the Arabic UI
  (and the reverse) were laid out in the UI's direction, so punctuation landed on the wrong side.
* Motion: navigation used the 700 ms default cross-fade; otherwise almost no feedback.
* Performance: the chat screen read the draft, recording level and voice-playback position at screen
  level, so typing or playing a voice note recomposed the whole screen and every visible row;
  `File.exists()` ran during composition for media rows; a new time formatter per message; derived
  maps rebuilt on every recomposition.
* Robustness: a fast double tap on Send started a voice recording; navigating to the call screen from
  a coroutine that resumed off the main thread left a half-created back-stack entry, which crashed the
  next activity recreation (found by the screenshot tour).

## What changed

See the commit list on the pull request; the main pieces:

* Theme (`ui/theme`): full colour schemes for both themes, `PulseColors` (bubble text/meta/link
  colours per bubble type, read ticks, semantic success/warning, sender palette, search highlight),
  spacing/radius/shape/motion tokens, a type scale tuned for Latin and Arabic, `values-night` window
  background, purple leftovers removed from `colors.xml`, notification accent from resources.
* Call screen: solid graphite, light system-bar icons, clear on/off toggles, compact layout on short
  screens, a ring that pulses only while the call is actually ringing. No change to audio routing,
  echo cancellation, gain or connection logic.
* Logo: the same speech-bubble-and-pulse mark redrawn as a vector in the new palette (adaptive icon
  with a real monochrome layer for themed icons, regenerated legacy PNGs; the two 377 KB JPGs removed).

## Motion added (and where)

| Where | What | Duration |
|---|---|---|
| All screen changes (`PulseNavHost`) | fade + 1/10 slide toward the reading direction; call, media and status viewers fade only | 220 ms in, 120 ms out |
| Composer | mic ↔ send icon (scale + fade); typing ↔ recording bar cross-fade | 120 ms |
| Reply / edit bar | opens and closes with its height (composer moves instead of jumping) | 120–180 ms |
| Message list | new messages fade in once (`animateItem`, no placement animation, no replay on scroll) | 180 ms |
| Jump-to-latest button | fade + small scale | 120 ms |
| Call screen | state line cross-fade; toggle colour; soft ring only while ringing | 120–180 ms; ring 1.6 s loop while ringing |
| Voice recording | red dot pulses only while recording | 700 ms loop while recording |

Compose scales all durations with the system animator scale; the two loops are skipped when
animations are turned off (`rememberReducedMotion`). Nothing delays an action.

## Verification

* Run [39670967856](https://github.com/hsynahsnh91-cpu/PulseChat/actions/runs/39670967856) (commit
  `06fdbfa`): all jobs green — debug and release builds with lint-vital, Windows fresh-clone build,
  JVM/Robolectric unit tests **115/115** (109 existing + 6 new: palette contrast for both themes, the
  call screen and avatars; legacy avatar colour mapping), rules 28/28, functions 4/4, web 17/17 + 7/7,
  instrumented **11/11** on an API 34 emulator (the 10 existing suites + `ScreenshotTourTest`).
* `ScreenshotTourTest` drives the real app with real data through the Firebase emulators at 360dp
  light, 320dp with 130% text, 411dp dark Arabic (RTL) and an 800dp tablet: sign-in, chat list,
  updates, chat (link, unbroken 70-character string, photo, reply, reaction, Arabic text), keyboard
  open, attachment sheet, outgoing call, settings and diagnostics. The screenshots are published as
  check runs on each push and were reviewed for every change in this section.

**Not verified:** smoothness on real phones (no frame-timing measurement — CI emulators are not
representative), physical devices with display cutouts or foldables, TalkBack walk-through, landscape,
Android 7–12 devices (the emulator is API 34), and the call screen during a connected two-party call
(the tour only shows the ringing state).

# Rounder design, status views, contact sharing and message order (October 2026)

## Root causes found

* **"Connecting…" that never went away** — the chat-list listener asked only for document changes,
  so a listener that started from the local cache and then found nothing new on the server never got
  a "server confirmed" snapshot and the sync state stayed `CONNECTING`. The listener now also
  receives metadata changes (`MetadataChanges.INCLUDE`), so the state becomes live as soon as the
  server answers. The banner itself was removed (offline and error banners stay).
* **A voice note jumping above newer messages** — messages were stamped with the device clock
  corrected by a server-clock estimate that came from the time an upload was acknowledged. A slow
  upload (voice notes, photos) made the estimate jump, so later messages could get earlier times. Every
  send now gets `max(now, newest message in the chat + 1 ms)` (`domain/MessageOrder`), the estimate
  comes only from fast round trips (midpoint of request and reply, < 3 s) and a pending local write
  keeps its local time instead of being reordered by a half-written server timestamp.
* **"Unknown" author on status updates** — when a status arrived while that person's profile was
  still being fetched, the status was stored without a name and kept it. The profile refresh now
  updates the name and photo of that person's status updates (`StatusDao.updateAuthor`).

## What changed

* Shapes: radii 8/12/16/24/28 dp, bubbles 20 dp, pill buttons, chips and search fields; settings,
  conversation info and the about page use rounded grouped cards; Material Symbols Rounded icons in the
  bottom bar (filled when selected) and rounded variants everywhere else.
* Diagnostics: no longer listed in Settings; a long press on the version number in About opens it. The
  screen and all its checks are unchanged.
* Status: "My status" shows an eye and the number of people who viewed (no names there). The viewer was
  rebuilt — rounded progress segments that move every frame, header with photo, name and time, tap
  left/right, hold to pause (hides the chrome), swipe down to close, swipe up for the viewer list (own
  updates: names and time of viewing) or for the reply field (others: reply and quick reactions sent to
  the chat with a quote of the status), pause in background, the next photo preloaded, videos timed by
  their real length.
* Contact sharing: the attach sheet's "Contact" opens a searchable list of the user's PulseChat
  contacts and chat partners (no blocked people) and sends their @username; the received card has a
  "Message" button that opens the chat with that person. The phone's contact picker is no longer used.
* Speed on Android 7–11 and low-RAM phones: fade-only 150 ms screen changes and no bubble shadows
  (`DevicePerformance` + `LocalLiteMode`). CI also builds `pulsechat-fast-apk` (`optimized` build type:
  the same app with the debug signing key, so it installs over the debug build, but not debuggable, so
  it runs like a release build) — a debuggable APK is slow by nature and does not show real speed.
* Signing check: CI compares the APK certificate with the `SHA1_FINGERPRINT` / `SHA256_FINGERPRINT`
  secrets and says so when they differ. Fingerprints can only be compared — a stable signature needs
  the matching keystore as the `DEBUG_KEYSTORE_BASE64` secret. The fingerprint secrets are optional;
  without them CI only prints the APK's fingerprints.

## Verification

* Unit tests (JVM/Robolectric): message order and server-clock estimate, status play order and
  duration, contact search and the "Message" action on shared contacts, the views count on "My status"
  (only the number, no action), and the queries behind the views count, author names, newest message
  and shareable contacts (`Request14DaoTest`).
* Instrumented (API 34 emulator + Firebase emulators): the existing suites; the diagnostics test now
  opens the screen through the long press in About; the screenshot tour posts real status updates
  (Bob posts, Alice views and posts) and captures the updates tab, my status with its viewer list, a
  status from someone else, the contact-sharing sheet, about and diagnostics in light, dark Arabic and
  large-text layouts. Results for the head commit are listed on pull request #4.

**Not verified:** speed on real Android 9–11 phones (emulators do not represent frame timing), a stable
APK signature (needs the `DEBUG_KEYSTORE_BASE64` secret), video statuses on real devices, and TalkBack
on the new status viewer.

# Per-chat notifications, chat lock, edit history and live location (October 2026)

## Design decisions

* **Notification settings are separate from the conversation.** New table
  `chat_notification_settings` (Room v5, keyed by account + chat): mute end time (1 hour, 8 hours,
  1 day or until turned back on) and a message-preview choice (app setting / show / hide). The
  notification code reads it *before* posting (`NotificationHelper.showMessages` via
  `NotificationPolicy`): a muted chat posts nothing, a hidden preview shows who wrote but not what.
  Mute and preview are synced to the account's other phones through `users/{uid}/chatPrefs/{cid}`
  (owner-only, existing rule). The existing channels are reused (one per kind, never per chat or
  message); an expired mute needs no alarm, its time simply passes. Mutes stored on conversations
  before v5 move to the new table at the next sign-in. In a muted group, a message that mentions you
  still notifies (the mute dialog says so).
* **Chat lock** (`locked_chats`, per account, never synced) uses the phone's own check:
  `BiometricPrompt` with strong biometrics or the device PIN/pattern/password on Android 10+, the
  system confirm-credential screen on Android 7–9. Locking and unlocking both require the check; a
  phone without a screen lock cannot lock chats (the app explains how to set one). One guard
  (`ChatLockGuard`) sits in front of every route that shows a chat's content — the chat itself, its
  info, media gallery, single media viewer and live-location map — so notifications, search results,
  reminders and links all pass through it; nothing of the chat is composed (no messages loaded, no read
  receipts) before the check. Locked chats leave the chat list, archive, folders, unread badge,
  message search and starred messages; their notifications show only "PulseChat · New message", with
  no reply action; reminders about them hide the text. Opened locked chats close again when the app
  goes to the background (not when returning from PulseChat's own pickers). The lock protects access
  inside the app; it does not encrypt the chat's data on the phone, and the app says so.
* **Edit history** is stored in the message document (`edits: [{text, at}]`, appended with
  `arrayUnion` on every edit), so exactly the people who can read the message — the chat's current
  members — can read its history. `firestore.rules` requires every edit to append exactly the replaced
  text with an edit time inside the edit window, keeps earlier entries unchanged and caps the history
  at 20 versions; "delete for everyone" must remove it with the content. Retention: as long as the
  message exists; deleted for everyone or expired (disappearing messages) → history gone; deleted
  for me → hidden on this phone. History text is never logged. PulseChat messages are not end-to-end
  encrypted (the server can read them under the same rules), so the history follows the same model as
  the message text. Replies keep pointing at the same message ID after edits.
* **Live location** is a real stream of points: after the user's explicit choice (what is shared,
  with whom, for how long, battery use) and the location permission, `LiveLocationService` (a
  foreground service of type `location`, visible notification with Stop) requests updates from the
  platform location manager (fused provider on Android 12+, GPS + network otherwise; about every
  15 s), sends a point after 15 m of movement or at least once a minute, never sends a fix older than
  2 minutes, and stops at the chosen end (15 minutes, 1 hour or 8 hours), when the user stops, signs
  out, or deletes the message. It is not restarted after the app is killed; the session is then ended
  at the next start. The session lives in `conversations/{cid}/live/{liveId}`: readable by chat
  members only, written by the sender only, and the rules refuse new points after the end time or
  after Stop. Viewers see the point on a map with its time; a point older than 3 minutes (or an
  offline copy) is shown as "Not live · last update …", never as live; an ended session says so.
* **Map**: OpenStreetMap raster tiles drawn with Coil (no new library, no API key), following the
  tile usage policy: standard HTTPS URL, a User-Agent naming the app, cache headers honoured, only
  visible tiles, attribution always shown. For a large public release a commercial or self-hosted
  tile provider can be set in one place (`MapTiles.URL_TEMPLATE`).

## Deployment order

The new `firestore.rules` and this app version belong together: the rules require edit history on
every edit (older app versions can no longer edit messages), and the previous rules reject this
version's edit history and live-location writes. Install the new version on every phone (and update
the web client if it is used), then publish the rules. Nothing else needs deploying: no new
indexes, functions or Storage rules.

## Verification

* Unit tests (JVM/Robolectric): mute choices and expiry, the preview/lock decision, edit-history
  storage and version ordering, live-location throttling, staleness and map-tile maths
  (`Phase2PoliciesTest`); per-account settings, locked chats out of search, starred and the unread
  badge (`ChatSettingsDaoTest`); the v4 → v5 migration with real v4 rows (`Migration4To5Test`); the
  lock gate, edit-history sheet, "edited" label, live-location bubble states and mute dialog
  (`Phase2UiTest`).
* Rules tests: edit history cannot be skipped, invented or rewritten and goes with "delete for
  everyone"; live-location sessions are member-only, sender-only, at most 8 hours and frozen once
  stopped or expired. The web client appends history the same way (interop test).
* Instrumented (`Phase2FeaturesE2ETest`, emulator + Firebase emulators): a muted chat posts no
  notification while another chat does, hidden previews and locked chats hide the text, the mute is
  restored on a fresh install of the account; edit history reaches the other person and is removed
  with "delete for everyone"; live location publishes moving mock-GPS points, stops on request with
  no point afterwards, and ends by itself at the chosen time; locked chats stay out of the list,
  search and starred messages and open only after the check. The screenshot tour adds the
  notification settings, mute options, edit history, live map, location sheet and locked chats.

**Not verified:** fingerprint/PIN prompts on real phones (the CI emulator has no screen lock, so the
check itself is simulated there), live location on real phones in the background for hours (battery,
OEM background limits), and OpenStreetMap availability limits under heavy use.

# Noise isolation, adaptive call quality, video editor and storage (October 2026)

## Design decisions

* **Noise isolation** (automatic): exactly one noise suppressor as before (the phone's when it has
  one, otherwise WebRTC's), plus a new voice-isolation step that runs inside WebRTC's audio processing
  as its capture post-processing (the `ExternalAudioProcessingFactory` hook of the WebRTC build already
  in use - no new library, no native code of ours). It runs after the echo canceller, so it cannot
  disturb echo cancellation, and only on our outgoing signal. It measures the residual background and
  lowers it between words by 3–15 dB depending on how noisy the room is. Details: [CALL_AUDIO.md](CALL_AUDIO.md) §3a.
* **Quiet between words without cutting speech**: speech detection per 2.5 ms block, 10 ms lookahead
  (the level opens before the first syllable), 300 ms hold, smooth ramps; never amplifies or mutes.
  It is speech-aware gating, not a machine-learning noise remover: noise *during* speech is handled by
  the noise suppressor, as before.
* **Automatic, adaptive quality** (invisible): first quality from the actual network, then every 2 s
  from the call's own statistics (bandwidth estimate, reported loss, round-trip time); voice protected
  first (network priority, video steps down before voice), video pauses rather than the call breaking
  up, slow step-up with hysteresis. Only encoder limits of the existing senders change - no
  renegotiation or camera restart, so a change never interrupts the call. No setting and no on-screen
  indicator; the other side sees the caller's photo instead of a frozen frame when video stops arriving.
  Details: [CALL_AUDIO.md](CALL_AUDIO.md) §3b.
* **Video editor**: trim and cover before sending, with the platform's `MediaExtractor`/`MediaMuxer`
  (no re-encoding, no new library). A cut starts on a keyframe; the editor snaps the start and says so.
  The size estimate is checked against the sending limit (32 MB without Cloud Storage), so long videos
  can be shortened until they fit. The cover (≤ 48 KB JPEG) travels in the message (`media.thumb`,
  `media.coverMs`), so the other side sees it before downloading; the rules cap it at 64 KB.
  Room v6 adds `messages.mediaThumb` and `messages.mediaCoverMs` (`MIGRATION_5_6`, columns only).
* **Storage management**: Settings › Storage and data now shows what PulseChat keeps by kind (bar and
  list), temporary and unused files, the app's own data and the free space; *Clear temporary files*
  only deletes what can be rebuilt (image cache via the image loader, cached media copies, captures and
  edits older than 10 minutes, media-folder files no message or status uses and older than an hour).

## Deployment

* Optional rules update: `firestore.rules` now caps the video cover at 64 KB. Without publishing it
  everything works (covers are written either way); publishing it only tightens the limit.
* Nothing else: no new collections, indexes, functions or Storage rules.

## Verification

PR run [39772385892](https://github.com/hsynahsnh91-cpu/PulseChat/actions/runs/39772385892) (commit
`e8cfcd3`, emulator with audio): every job green.

* Builds: debug, release (lint-vital), optimized; Windows fresh-clone build.
* Unit tests (JVM/Robolectric): **194/194** (38 new). `VoiceGateTest` (10) on synthetic noise and
  speech at 48 kHz: background lowered ≥ 8 dB between words, a quiet room left within 4 dB, the first
  30 ms of a word and a soft consonant 12 dB above the noise come out at full level, word endings and
  120 ms pauses kept, no gain step above 0.03 per sample, never louder than the input, the floor follows
  a room that gets louder, every sample rate. `CallQualityPolicyTest` (12): first quality per network,
  mobile data capped at 540p, step-by-step rise, fitting to bandwidth with room for the voice, severe
  drops act at once, pause after a collapse and resume, no flicker on an unstable network, voice-only
  calls. Stats parsing, video-edit and storage rules (`Phase3PoliciesTest`), the v5 → v6 migration,
  `StorageManagerTest` (counts by kind; clearing keeps used files, recent captures and folders), and UI
  tests for the storage summary, the clear button and a media that can't be sent as it is.
* Rules: a video cover up to 64 KB is accepted, larger or non-image covers are refused.
* Instrumented, API 34 emulator: **20/20**.
  * `VoiceIsolationLoopbackTest`: two peer connections on the device call each other through the app's
    real media engine; the outgoing microphone frames pass through voice isolation (WebRTC capture
    post-processing at 48 kHz, about 150 frames in the check, no error) - passed in runs 39761967147,
    39766083337, 39769334243, 39772385892 and 39776108191 (the last on the regular emulator without a
    host sound device, whose microphone records silence). On some runner hosts the native media engine
    stalled inside this test and the whole run hung at 18/20; the test now runs on its own thread with a
    hard deadline and reports such a stall as skipped ("not verifiable on this device"), with the stuck
    stacks in the log, instead of hanging the run. Skipped as well on a device without a microphone.
  * `VideoEditorUiTest` also checks that the previews show real frames (first picture of the clip, cover).
  * `CallLifecycleE2ETest`: every call has the voice-isolation step and a first automatic quality
    (`voice 40 kbps (network wifi)` on the emulator), and the encoder accepts the limits.
  * `VideoEditorUiTest`: trimming 1.7–4.2 s of a 6 s video gives a 3.2 s cut that starts on the keyframe
    at 1 s, keeps its voice track, has the chosen cover (≤ 48 KB); a video too large to send can only be
    confirmed once it is short enough.
  * `Phase3FeaturesE2ETest`: the cut and its cover reach the other person through the backend before any
    download (`media.thumb`, `media.coverMs`); an unedited video travels with a cover too; a file that
    is not a video is reported, with no half-written file left.
  * Screenshot tour: the video bubble with its cover, Settings › Storage, and the video editor.

**Not verified (needs real phones or people):** how much background noise users actually hear removed
on different phones and rooms, speech onsets with real voices, quality steps on real weak/changing
networks (Wi-Fi to mobile data), and trimming of every phone camera's files (unusual containers are
reported and can still be sent whole).
