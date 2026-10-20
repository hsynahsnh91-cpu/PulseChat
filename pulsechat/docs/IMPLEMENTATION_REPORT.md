# PulseChat upgrade – implementation report

Status legend: **Verified** = implemented and covered by automated tests that exercise the
behaviour (CI: `.github/workflows/android-ci.yml`); **Partial** = implemented and building in CI
but not verified end-to-end (needs a device and/or the deployed Firebase backend), or with a
documented gap; **Missing** = not implemented.

| # | Feature | Status | Evidence / gap |
|---|---|---|---|
| 1 | Primary navigation (Chats / Updates / Groups / Calls), top bar with expandable search, overflow, profile access, preserved tab | Partial | Built; search filtering unit-tested; no instrumented UI test of the bar/tabs |
| 2 | Rich chat rows (avatar, preview, time, unread badge, ticks, muted/pinned/favourite/draft/typing) | Verified | `ComponentsUiTest` (ChatRow), `DaoTest.chatListMembershipIsExactNotSubstring` |
| 3 | Ordering by latest activity, pinned first, max 3 pins | Verified | `ChatListPolicyTest`, `DaoTest.pinnedCountAndPreferences` |
| 4 | Filters All / Unread / Favourites / Groups and chat search (Arabic-normalised) | Verified | `ChatListPolicyTest`, `MentionsAndTextTest.normalizationAndSearch` |
| 5 | Pin, archive/restore, mark read/unread, mute, favourite (persisted, synced per user) | Partial | Local persistence DAO-tested; `chatPrefs` sync not verified against a live backend |
| 6 | Multi-select chat actions; delete/clear chat with confirmation (keep starred) | Partial | Confirm dialog + clear semantics tested; multi-select flow not UI-tested |
| 7 | Empty / loading / offline / sync-error states, new-chat FAB | Partial | Built; not UI-tested |
| 8 | Conversation header with privacy-aware presence, typing, call/search/menu actions | Partial | Presence privacy verified (rules + unit); header not UI-tested |
| 9 | Rendering of all message types (text/links/mentions/image/video/document/voice/contact/location/poll/reply/forwarded/system/AI), grouping, day headers | Partial | `MessageTypesUiTest`, `ComponentsUiTest`, `MessagePolicyTest`; image/video rendering not device-tested |
| 10 | Delivery states driven by real events; retry without duplicates | Verified | `DeliveryPolicyTest`, `DaoTest.receiptsAdvanceOnlyAcknowledgedOwnMessages`, rules receipts test; UUID idempotency |
| 11 | Long-press menu, multi-select, swipe-to-reply, message info | Partial | Action availability `MessageTypesUiTest.actionSheetOffersEditOnlyInsideTheWindow`; gestures not UI-tested |
| 12 | Composer (multiline, emoji, attach, camera, mic/send switch, reply/edit preview, enter-to-send) | Verified | `ComponentsUiTest.composerSwitchesBetweenMicAndSend`; drafts DAO-tested |
| 13 | Reply with navigable original-message preview | Verified | `ComponentsUiTest.replyQuoteNavigatesToOriginal`, `DaoTest` jump position |
| 14 | Reactions (one per user, counts, who reacted) | Verified | UI test + rules `reactions … own entry` + `MessagePolicyTest` |
| 15 | Forwarding to multiple chats with "Forwarded" label | Partial | Implemented; not tested |
| 16 | Starred messages (toggle, screen, jump to message) | Partial | Starred query DAO-tested; screen not UI-tested |
| 17 | Edit within 15 minutes with "edited" label (server-enforced) | Verified | `MessagePolicyTest`, rules `only the author can edit … 15 minutes`, footer UI test |
| 18 | Delete for me / delete for everyone (48 h, content wiped) | Verified | `MessagePolicyTest`, rules test, `DaoTest` |
| 19 | Typing indicators (throttled, expiring) | Partial | Timeout logic + rules ownership tested; not live-tested |
| 20 | Online presence / last seen with privacy and reciprocity | Verified | rules `last seen honours visibility, contacts and reciprocity`, `PoliciesTest` |
| 21 | Unread counters, read receipts (toggle + reciprocity), unread separator, jump to latest | Verified | `DaoTest.unreadCountsOnlyIncomingAfterWatermark`, `MessagePolicyTest` separator |
| 22 | @mentions by stable user IDs (notify even when muted) | Verified | `MentionsAndTextTest`, functions `muted chats notify only on mention` |
| 23 | Disappearing messages per chat (24 h / 7 d / 90 d) | Partial | Policy + expiry query + rules clean-up tested; TTL/scheduled purge need deployment |
| 24 | In-chat search with highlighting and result navigation; global message search with filters | Verified | `DaoTest` (in-chat search, jump position, global search with type/link filters), `MentionsAndTextTest.occurrences` |
| 25 | Draft persistence, duplicate and out-of-order event handling | Verified | `DaoTest`, `MessagePolicyTest.timelineOrderingAndDedupe` |
| 26 | Attachments: gallery, camera photo/video, documents, contact, current location, polls | Partial | Poll UI + rules tested; pickers need a device |
| 27 | Media upload/download with progress, cancel, retry; viewer; auto-download rules; storage screen | Partial | Auto-download policy unit-tested; Firebase Storage transfers not tested |
| 28 | Voice messages: real recording, waveform, seek, 1x/1.5x/2x, audio focus | Partial | Waveform + UI tested; MediaRecorder/MediaPlayer need a device |
| 29 | People: search by username/ID/name, no duplicate DMs, contact profiles, saved contacts | Partial | Deterministic DM ID verified (unit + rules); Firestore search not tested |
| 30 | Block / unblock / report (server-enforced) | Verified | rules: blocked messaging, blocked calls, photo hidden from blocked users, write-only reports |
| 31 | Groups: create, add/remove, promote/demote, admin-only send/edit, leave with admin hand-over, creator protection | Verified | `GroupPolicyTest`, four group rules tests |
| 32 | Status / Updates: text/photo/video, 24 h expiry, viewed/unviewed, viewer, audience privacy, viewer list, delete | Partial | `PoliciesTest` grouping/audience, rules audience/views tests; uploads + viewer not device-tested |
| 33 | One-to-one voice/video calls: state machine, Firestore signalling, WebRTC, busy/missed/timeout, history | Partial | `CallStateMachineTest` (no auto-accept), rules call tests; real media between devices not verified; TURN needed on strict networks; group calls not supported |
| 34 | Push notifications: channels, per-chat grouping, privacy previews, inline reply/mark read, dedupe, call notifications | Partial | Functions policy tests; requires deployed functions + FCM |
| 35 | Profile editing and privacy settings (photo/about visibility, status audience, read receipts, default timer, blocked list) | Partial | Enforcement verified by rules tests; profile photo upload not tested |
| 36 | Account security: Firebase Auth (username/ID + password, Google), legacy account upgrade, no password-less switching, backups exclude tokens, app lock, sign-out | Partial | Rules identity/handle tests, legacy re-key DAO test; Google sign-in and biometric prompt need a device |
| 37 | Gemini: assistant chat, reply suggestions, polish, summary; explicit consent, labelled output, rate limit, no client key | Partial | Consent gating + labels built; Firebase AI Logic must be enabled in the console |
| 38 | Design system: original indigo/teal identity, light/dark/system themes, Arabic/English with RTL | Verified | `ArabicLocaleUiTest` (Arabic strings, plurals, RTL); 546 strings in both locales |

Not implemented by design / out of scope: end-to-end encryption (not claimed anywhere),
group calls, live location sharing, phone-contact import.

## Test results (CI)

* `assembleDebug`, `assembleRelease` (with lint-vital): passing.
* JVM/Robolectric unit tests (64): domain policies, Room migration v2→v3, DAO queries, Compose UI
  components and message types, Arabic/RTL.
* Firestore rules (official emulator): 24 tests.
* Cloud Functions: TypeScript build + 4 unit tests.
* Not run: instrumented tests on a device/emulator (no device available in CI), end-to-end tests
  against the real Firebase project.

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
