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
fingerprints, deploy rules/indexes/storage/functions (Blaze plan for functions), enable Firebase
AI Logic (Gemini Developer API) and App Check, optionally configure TURN in `config/webrtc`.
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
