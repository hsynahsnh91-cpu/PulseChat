# PulseChat Web: same Firebase backend as the Android app

This folder lets a **web app** use the **exact same Firebase project, Firestore database, Auth
accounts, Storage bucket and security rules** as the PulseChat Android app (Kotlin, Compose, Room).
A message sent on the web appears on Android in real time, and the other way round.

| Path | What it is |
| --- | --- |
| `src/pulsechat/` | Framework-agnostic TypeScript data layer on the Firebase JS SDK (modular). Copy it into any web app (React, Next.js, Vue, Svelte, Angular, plain TS). |
| `index.html`, `src/demo/` | Minimal demo web client built on the data layer (Vite, no framework). |
| `test/unit.test.ts` | Unit tests + **contract tests** that read the Kotlin sources, `firestore.rules` and `google-services.json`, so the web schema cannot drift from Android. |
| `test/interop.test.ts` | Integration tests: real web clients against the Firebase emulators running this repo's `firestore.rules` and `storage.rules`. |
| `test/android-peer.ts` | Web client used by the Android instrumented test `WebInteropE2ETest` (Android ↔ Web in real time, in CI). |

> This is not a Capacitor/WebView wrapper of the Android app. The Android app stays native; this
> is a separate web client that talks to the same backend.

## What "the same backend" means

| Shared piece | Android (source of truth) | Web |
| --- | --- | --- |
| Firebase project | `pulsechat/app/google-services.json` → `gen-lang-client-0426516505` | same `projectId` |
| Firestore database | **named** database `ai-studio-android-pulsecha-2c0cef4c-b560-4944-a812-3a390d3cee6a` (`res/values/firebase_applet_config.xml`) | `initializeFirestore(app, settings, databaseId)`. `getFirestore(app)` opens the empty `(default)` database instead. |
| Accounts | username → email/password account `<username>@<projectId>.firebaseapp.com` (`Ids.authEmailForUsername`); Google sign-in | same formula (`authEmailForUsername`) and Google popup → **same Firebase UID** on both platforms |
| Data model | `FirestoreSchema.kt`, `RemoteMappers.kt`, `ChatRepository.kt` | `src/pulsechat/schema.ts`, `messages.ts` (1:1 ports) |
| Security | `pulsechat/firestore.rules`, `pulsechat/storage.rules` (server-side) | the same rules apply to web requests automatically |
| Files | bucket `gen-lang-client-0426516505.firebasestorage.app`, `chat-media/{cid}/{senderUid}/{messageId}.{ext}` | same bucket and paths |
| Server logic | Cloud Functions (Firestore triggers: push fan-out, call invites, cleanup) | triggered by web writes too, so Android devices get push notifications for web messages |

Real-time sync uses Firestore snapshot listeners (`onSnapshot`) on both sides. No extra server is involved.

---

## Step 1: get the web config from the same Firebase project

`google-services.json` configures **Android apps only**. A web app needs its own *Web app* entry in
the **same project**: it gets its own `appId` (and usually its own browser API key). The project,
database, bucket and users stay the same.

**Console:** [Firebase console](https://console.firebase.google.com/project/gen-lang-client-0426516505/settings/general)
→ Project settings → General → *Your apps* → **Add app** → Web (`</>`) → nickname "PulseChat Web" →
copy the `firebaseConfig` object.

**CLI** (alternative):

```bash
npm install -g firebase-tools
firebase login
firebase apps:create web "PulseChat Web" --project gen-lang-client-0426516505
firebase apps:sdkconfig web <WEB_APP_ID> --project gen-lang-client-0426516505   # prints firebaseConfig
```

How the Android values map to the web config:

| Android (`google-services.json` / resources) | Web `firebaseConfig` | Value |
| --- | --- | --- |
| `project_info.project_id` | `projectId` | `gen-lang-client-0426516505` |
| `project_info.project_number` | `messagingSenderId` | `677612835092` |
| `project_info.storage_bucket` | `storageBucket` | `gen-lang-client-0426516505.firebasestorage.app` |
| derived from the project ID | `authDomain` | `gen-lang-client-0426516505.firebaseapp.com` |
| `client[].client_info.mobilesdk_app_id` (`1:677612835092:android:…`) | `appId` | **Do not reuse.** Use the Web app's ID (`1:677612835092:web:…`). |
| `client[].api_key[].current_key` | `apiKey` | Use the key shown for the Web app. The Android key may be restricted to Android apps. |
| `firebase_applet_config.xml` → `firestore_database_id` | not part of `firebaseConfig`: the database ID | `ai-studio-android-pulsecha-2c0cef4c-b560-4944-a812-3a390d3cee6a` |

Then:

```bash
cd web
cp .env.example .env      # .env is git-ignored; fill VITE_FIREBASE_API_KEY and VITE_FIREBASE_APP_ID
```

These values identify the project; they are not secrets. Protection comes from Firebase Auth and
the security rules (and App Check). Never put service-account keys or other admin credentials in a
web app.

## Step 2: Firebase console settings for the web

1. **Authentication → Sign-in method:** *Email/Password* must be enabled (username accounts use it on
   both platforms). Enable *Google* if you offer Google sign-in.
2. **Authentication → Settings → Authorized domains:** add every domain that serves the web app
   (`localhost` is there by default). Required for Google sign-in.
3. **API key restrictions** (Google Cloud console → APIs & Services → Credentials): restrict the
   browser key with *HTTP referrers* to your domains.
4. **App Check:** if enforcement is on for Firestore/Storage (Android uses Play Integrity), register
   the Web app with **reCAPTCHA Enterprise** and initialize App Check right after `initPulseChat()`.
   Otherwise every web request is rejected:

   ```ts
   import { initializeAppCheck, ReCaptchaEnterpriseProvider } from "firebase/app-check";
   initializeAppCheck(pc.app, { provider: new ReCaptchaEnterpriseProvider("<site key>"), isTokenAutoRefreshEnabled: true });
   ```

5. **Rules:** nothing changes. Deploy them once from `pulsechat/` if you have not:
   `firebase deploy --only firestore,storage`.
6. **Storage CORS** is only needed if the browser reads file *bytes* (`getBlob`, `getBytes`, `fetch`).
   `<img src>`, `<video src>` and download links work without it. If you need it:

   ```bash
   echo '[{"origin":["https://your.domain"],"method":["GET"],"maxAgeSeconds":3600}]' > cors.json
   gcloud storage buckets update gs://gen-lang-client-0426516505.firebasestorage.app --cors-file=cors.json
   ```

## Step 3: initialize Firebase in the web app

**Use this folder** (Vite demo):

```bash
cd web
npm install
npm run dev          # http://localhost:5173
```

**Or add it to an existing web app:** `npm install firebase`, copy `src/pulsechat/` into the project, then:

```ts
// firebase.ts
import { configFromEnv, initPulseChat } from "./pulsechat";
export const pc = initPulseChat(configFromEnv(import.meta.env)); // Vite; other bundlers: pass an object with the same keys
```

What `initPulseChat()` does (`src/pulsechat/firebase.ts`):

```ts
const app = initializeApp(firebaseConfig);                       // Web app config from step 1
const auth = getAuth(app);                                       // same users as Android
const db = initializeFirestore(app, { localCache: memoryLocalCache() },
  "ai-studio-android-pulsecha-2c0cef4c-b560-4944-a812-3a390d3cee6a");   // the Android app's named database
const storage = getStorage(app);                                 // bucket from firebaseConfig.storageBucket
```

Set `VITE_FIRESTORE_PERSISTENT_CACHE=true` for an IndexedDB offline cache (the web counterpart of Room).

Notes:
- **Next.js:** use the `NEXT_PUBLIC_` prefix and build the config object yourself. Use the client SDK
  only in client components (`"use client"`).
- **Node.js version:** `firebase@13` declares Node ≥ 24.12 (for tooling, SSR and tests). Browsers are
  unaffected. If you are on Node 22, `firebase@12.19` works with the same modular API.

## Step 4: the data model the web must follow

```
users/{uid}                    public profile (UserDoc)
  restricted/{photo|about}     privacy-controlled photo URL / about text
  private/settings             readReceipts, … (owner only)
  contacts/{uid}  blocked/{uid}  devices/{id}  chatPrefs/{cid}
usernames/{username}           { uid }                unique handle
uniqueIds/{10 digits}          { uid, username }      public numeric ID, login by ID
presence/{uid}                 { online, heartbeatAt, lastSeen }
conversations/{cid}            cid = dm_<lowerUid>_<higherUid> | grp_<uuid>
  messages/{messageId}         messageId = random UUID
statuses/…  calls/…  reports/…  config/webrtc   (not covered by the web module yet)
```

**Users** (`users/{uid}`): `uid`, `username` (`^[a-z][a-z0-9._]{2,19}$`), `uniqueId10`, `displayName`
(1–50), `displayNameLower`, `avatarColorHex`, `lastSeenVisibility` / `photoVisibility` /
`aboutVisibility` (`everyone|contacts|nobody`), `createdAt` (server timestamp). Created in **one
transaction** together with `usernames/{username}` and `uniqueIds/{id}`; the rules check all three.

**Conversations** (`conversations/{cid}`):

| Field | Type | Notes |
| --- | --- | --- |
| `type` | `"direct"` \| `"group"` | |
| `participants` | `string[]` | direct: 2 UIDs, **sorted**; group: 2–256 |
| `lastMessage` | `{ id, senderId, type, preview, createdAt }` | written with every message (chat list) |
| `lastMessageAt`, `updatedAt` | Timestamp | server timestamps |
| `receipts.{uid}` | `{ d?: Timestamp, r?: Timestamp }` | watermarks: `createdAt` of the newest message delivered/read |
| `typing.{uid}` | Timestamp | removed when the user sends |
| `disappearingSeconds` | number | `0`, `86400`, `604800`, `7776000` |
| group only | `admins`, `createdBy`, `createdAt`, `title`, `description`, `photoUrl`, `onlyAdminsCanSend`, `onlyAdminsCanEditInfo` | |

**Messages** (`conversations/{cid}/messages/{messageId}`):

| Field | Type | Notes |
| --- | --- | --- |
| `senderId` | string | must be the signed-in UID |
| `type` | `TEXT` `IMAGE` `VIDEO` `AUDIO` `DOCUMENT` `LOCATION` `POLL` `CONTACT` `SYSTEM` | |
| `text` | string ≤ 4096 | text or media caption; `""` after delete-for-everyone |
| `createdAt`, `updatedAt` | Timestamp | **`serverTimestamp()` only** |
| `clientCreatedAt` | number (ms) | sender clock |
| `editedAt` | Timestamp \| null | `null` on create |
| `deletedForEveryone`, `forwarded` | boolean | `false` on create (`forwarded` may be true) |
| `mentions` | `string[]` | UIDs, ≤ 50 |
| `reactions` | `{ [uid]: emoji }` | `{}` on create |
| `replyTo` | `{ id, senderId, type, preview }` | optional |
| `media` | `{ url, path, mime, size, name, durationMs, width, height, waveform }` | media types |
| `contact`, `location`, `poll`, `system` | maps | per type |
| `expiresAt` | Timestamp | disappearing messages |

**Rules the web must respect.** These are the common mistakes, and the tests check them:

1. Open the **named database**, not `(default)`.
2. `createdAt` / `updatedAt` / `editedAt` must be `serverTimestamp()`. `Date.now()` or `new Date()` is rejected.
3. Message documents are create-only with a fixed field list. `editedAt: null`, `deletedForEveryone: false`
   and `reactions: {}` are required.
4. Direct chat ID = `dm_<lowerUid>_<higherUid>` with sorted `participants`. The **first message creates the
   conversation in the same batch**.
5. Every send also updates `lastMessage`, `lastMessageAt` and `updatedAt` in that batch; the Android chat
   list depends on it.
6. `receipts`, `typing`, `reactions` and poll `votes`: each user writes **only their own key**.
7. Do not read a direct chat (or its messages) **before it exists**: the rules deny it. Use the chat list.
8. **Do not use `pulsechat/firebase-blueprint.json`.** It is an old layout (top-level `messages`, numeric
   timestamps) that the rules reject (covered by a test).
9. Media goes to `chat-media/{cid}/{yourUid}/{messageId}.{ext}`; store both `url` and `path`.
10. No composite indexes are deployed. Use the same single-field queries (the module does).

## Step 5: reading and writing (examples)

```ts
import {
  observeSession, signIn, signUp, searchUsers, directChat, observeConversations, observeMessages,
  sendText, sendMedia, markRead, deliveryStatus, startPresence, observePresence,
} from "./pulsechat";
import { pc } from "./firebase";

// Accounts: an account created here signs in on Android, and vice versa.
await signUp(pc, { displayName: "Sara", username: "sara", password: "Passw0rd1" });
await signIn(pc, "sara", "Passw0rd1");          // or the 10-digit ID shown in the Android profile
observeSession(pc, (s) => { if (s.state === "signedIn") console.log(s.profile.displayName, s.profile.uniqueId10); });
const stopPresence = startPresence(pc);        // online / last seen, like Android (await stopPresence() before signOut)

// Users: search by username prefix or 10-digit ID.
const [friend] = await searchUsers(pc, "ahmad");

// Conversations: real-time chat list (direct + groups), newest first.
const stopChats = observeConversations(pc, (chats) => render(chats));

// Messages: open/create a direct chat and send. The first message creates the conversation.
const chat = directChat(pc, friend.uid);       // use the observeConversations() entry once it exists
await sendText(pc, chat, "مرحبا 👋 Hello from the web");
await sendMedia(pc, chat, { data: file, mime: file.type, fileName: file.name, caption: "photo" });

// Real-time messages + read receipts + ticks (same semantics as Android).
// `conv` is the chat's entry from observeConversations(): it exists and carries the receipts used for ticks.
const stopMessages = observeMessages(pc, conv.id, async (messages) => {
  await markRead(pc, conv, messages);          // writes receipts.{me}.r/.d (respects the shared read-receipt setting)
  for (const m of messages) console.log(m.text, deliveryStatus(m, conv, pc.auth.currentUser!.uid)); // sending|sent|delivered|read
});
```

What `sendText()` writes, so you can do it without the module (this is `ChatRepository.pushMessage`):

```ts
import { deleteField, doc, serverTimestamp, writeBatch } from "firebase/firestore";

const convRef = doc(db, "conversations", cid);
const id = crypto.randomUUID();
const batch = writeBatch(db);
if (isDirect) batch.set(convRef, { type: "direct", participants: [me, peer].sort() }, { merge: true });
batch.set(doc(convRef, "messages", id), {
  senderId: me, type: "TEXT", text, createdAt: serverTimestamp(), updatedAt: serverTimestamp(),
  clientCreatedAt: Date.now(), editedAt: null, deletedForEveryone: false, forwarded: false, mentions: [], reactions: {},
});
batch.update(convRef, {
  lastMessage: { id, senderId: me, type: "TEXT", preview: text.slice(0, 100) /* module: preview() */, createdAt: serverTimestamp() },
  lastMessageAt: serverTimestamp(), updatedAt: serverTimestamp(), [`typing.${me}`]: deleteField(),
});
await batch.commit();
```

React example (any framework works the same way: subscribe in an effect, unsubscribe on cleanup):

```tsx
function useMessages(chat: Conversation | null) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  useEffect(() => {
    if (!chat?.exists) { setMessages([]); return; }
    return observeMessages(pc, chat.id, setMessages);   // returns the unsubscribe function
  }, [chat?.id, chat?.exists]);
  return messages;
}
```

Also available: `createGroup`, `react`, `editMessage` (15 min), `deleteForEveryone` (48 h), `setTyping`,
`markDelivered`, `markConversationDelivered`, `observePresence`, `getProfilePhotoUrl`, `getAbout`,
`signInWithGoogle` + `completeProfile`, `mediaUrl`.

## Step 6: run locally against the emulators, and the tests

```bash
# Terminal 1 (from pulsechat/, where firebase.json is): uses the repo's firestore.rules and storage.rules
firebase emulators:start --only auth,firestore,storage --project demo-pulsechat
# Terminal 2: in web/.env set VITE_FIREBASE_PROJECT_ID=demo-pulsechat, VITE_FIREBASE_EMULATOR_HOST=127.0.0.1,
#             and any non-empty VITE_FIREBASE_API_KEY / VITE_FIREBASE_APP_ID
cd web && npm run dev
```

| Command | What it checks |
| --- | --- |
| `npm test` | unit + contract tests (no Firebase needed) |
| `npm run typecheck`, `npm run build` | TypeScript (strict) and the production build |
| `firebase emulators:exec --only auth,firestore,storage --project demo-pulsechat "npm --prefix ../web run test:emulators"` (from `pulsechat/`) | accounts, search, direct chat, real-time delivery, receipts, typing, presence, media upload/download, reactions, edit, delete, groups, and rejection of wrong shapes, all against the real rules |

CI (`.github/workflows/android-ci.yml`) runs all of the above in the **web** job. The **e2e** job also
starts `test/android-peer.ts` next to the Android emulator, and `WebInteropE2ETest` checks Android ↔ Web
in real time: a web-created account signs in on Android, text and photos go both ways, and web read
receipts reach Android.

## Step 7: deploy the web app

`npm run build` writes static files to `web/dist/`. Host them anywhere (Firebase Hosting, Netlify,
Vercel, …), then add the domain to **Authorized domains** and to the API key's referrer list (step 2).

## Coverage and limits

Implemented and tested: username/ID/password accounts and profile creation, search, presence,
direct and group chats, text, photos/files (upload + download), replies, reactions, edit,
delete-for-everyone, read/delivery receipts, typing, disappearing-message expiry, group creation.

Implemented but not covered by automated tests: Google sign-in (a popup can't run in CI) and
App Check (configured in your project).

Not in the web module yet (the backend supports them; the Android app has them):
- **Web push notifications.** FCM for web needs a service worker, a VAPID key and a token document in
  `users/{uid}/devices/{id}` (field `token`). The existing Cloud Function already sends to every token.
- **Voice/video calls.** WebRTC signalling lives in `calls/{id}`; there is no web implementation.
- Statuses, contacts and blocking UI, chat preferences (`users/{uid}/chatPrefs`: pin, archive, mute),
  group admin operations (add/remove/promote), sending polls, locations and contacts (reading them works).
- The Gemini assistant (device-local conversation on Android).

---

## ملخص بالعربية

الهدف: تطبيق ويب يستخدم **نفس** مشروع Firebase وقاعدة Firestore (المسمّاة) والحسابات وقواعد الأمان مثل تطبيق Android، مع مزامنة فورية.

1. **إعدادات الويب:** `google-services.json` خاص بـ Android. سجّل *Web app* في نفس المشروع (Console ← Project settings ← Add app ← Web، أو `firebase apps:create web`). خذ `apiKey` و `appId` الخاصين بالويب، وباقي القيم كما في الجدول أعلاه. ثم انسخ `web/.env.example` إلى `web/.env` واملأه.
2. **إعدادات Console:** فعّل Email/Password (وGoogle إن أردت)، وأضف نطاق موقعك إلى Authorized domains. وإن كان App Check مفعّلاً فسجّل الويب بـ reCAPTCHA Enterprise.
3. **التهيئة:** `initPulseChat(configFromEnv(import.meta.env))`، وهي تفتح قاعدة البيانات المسمّاة `ai-studio-android-…` وليس `(default)`.
4. **نموذج البيانات:** نفس المسارات والحقول: `users`، و`conversations/{cid}/messages`، و`dm_<uid الأصغر>_<uid الأكبر>`، والطوابع الزمنية بـ `serverTimestamp()`. لا تستخدم `firebase-blueprint.json` لأنه قديم وترفضه القواعد.
5. **القراءة والكتابة:** `observeConversations`، `observeMessages`، `sendText`، `sendMedia`، `markRead` (أمثلة الخطوة 5).
6. **الاختبارات:** `npm test` محلياً، واختبارات المحاكيات مع القواعد الحقيقية، واختبار Android ↔ Web في CI.
