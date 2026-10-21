/**
 * Fast tests without Firebase: pure helpers, plus contract tests that read the Android sources,
 * firestore.rules and google-services.json so the web schema cannot silently drift from them.
 * Run: npm test
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, test } from "node:test";
import {
  ANDROID_FIREBASE_PROJECT, ANDROID_FIRESTORE_DATABASE_ID, avatarColorFor, authEmailForUsername, configFromEnv, deliveryStatus,
  directConversationId, extensionFor, Fs, generate10DigitId, javaHashCode, lastMessageData, messageCreateData, passwordError, preview,
  typeForMime, userProfileData, usernameError, type ChatMessage, type Conversation,
} from "../src/pulsechat";

const repo = (path: string) => readFileSync(new URL(`../../${path}`, import.meta.url), "utf8");
const kotlin = (file: string) => repo(`pulsechat/app/src/main/java/com/example/${file}`);
const rules = repo("pulsechat/firestore.rules");

/** Keys of the `hasOnly([...])` list that follows `anchor` in firestore.rules. */
function rulesKeys(anchor: string): string[] {
  const start = rules.indexOf(anchor);
  assert.ok(start >= 0, `anchor not found in firestore.rules: ${anchor}`);
  const list = rules.slice(start).match(/hasOnly\(\[([^\]]*)\]\)/);
  assert.ok(list, `no hasOnly list after ${anchor}`);
  return [...list[1].matchAll(/'([^']+)'/g)].map((m) => m[1]);
}

/** String keys of the `mapOf("key" to ...)` entries inside a Kotlin function body. */
function kotlinMapKeys(source: string, functionName: string, untilMarker: string): string[] {
  const start = source.indexOf(`fun ${functionName}(`);
  assert.ok(start >= 0, `fun ${functionName} not found`);
  const body = source.slice(start, source.indexOf(untilMarker, start));
  return [...body.matchAll(/"(\w+)" to /g)].map((m) => m[1]);
}

describe("contract with the Android app and firestore.rules", () => {
  test("named Firestore database and project match the Android config", () => {
    const xml = repo("pulsechat/app/src/main/res/values/firebase_applet_config.xml");
    assert.match(xml, new RegExp(`>${ANDROID_FIRESTORE_DATABASE_ID}<`));
    const services = JSON.parse(repo("pulsechat/app/google-services.json"));
    assert.equal(services.project_info.project_id, ANDROID_FIREBASE_PROJECT.projectId);
    assert.equal(services.project_info.project_number, ANDROID_FIREBASE_PROJECT.projectNumber);
    assert.equal(services.project_info.storage_bucket, ANDROID_FIREBASE_PROJECT.storageBucket);
    assert.equal(JSON.parse(repo("pulsechat/firebase.json")).firestore[0].database, ANDROID_FIRESTORE_DATABASE_ID);
  });

  test("collection names equal FirestoreSchema.kt (object Fs)", () => {
    const schema = kotlin("data/remote/FirestoreSchema.kt");
    for (const [key, value] of Object.entries(Fs)) {
      assert.match(schema, new RegExp(`const val ${key} = "${value.replace(".", "\\.")}"`), `Fs.${key}`);
    }
  });

  test("message documents use exactly the fields RemoteMappers.messageCreateMap writes and the rules allow", () => {
    const android = kotlinMapKeys(kotlin("data/remote/RemoteMappers.kt"), "messageCreateMap", "fun status(");
    const allowed = rulesKeys("function validNewMessage()");
    const full = messageCreateData({
      id: "m1", senderId: "u1", type: "IMAGE", text: "caption", clientCreatedAt: 1, mentions: ["u2"],
      replyTo: { id: "m0", senderId: "u2", type: "TEXT", preview: "hi" }, expiresAtMillis: Date.now() + 60_000,
      media: { url: "https://x", path: "chat-media/c/u1/m1.jpg", mime: "image/jpeg", size: 3, name: "a.jpg", durationMs: 0, width: 1, height: 1, waveform: null },
    });
    const text = messageCreateData({ id: "m2", senderId: "u1", type: "TEXT", text: "hi", clientCreatedAt: 1 });
    // Base document: identical key set to Android's non-optional keys.
    const androidBase = ["senderId", "type", "text", "createdAt", "updatedAt", "clientCreatedAt", "editedAt", "deletedForEveryone", "forwarded", "mentions", "reactions"];
    assert.deepEqual(Object.keys(text).sort(), [...androidBase].sort());
    for (const key of androidBase) assert.ok(android.includes(key), `Android writes ${key}`);
    for (const key of Object.keys(full)) assert.ok(allowed.includes(key), `rules allow message field ${key}`);
    // Nested media/replyTo maps: same keys as Android.
    for (const key of Object.keys(full.media as object)) assert.ok(android.includes(key), `Android media field ${key}`);
    for (const key of Object.keys(full.replyTo as object)) assert.ok(android.includes(key), `Android replyTo field ${key}`);
    assert.equal(text.editedAt, null);
    assert.equal(text.deletedForEveryone, false);
    assert.deepEqual(text.reactions, {});
  });

  test("profile documents match RemoteMappers.userProfileMap and the users create rule", () => {
    const data = userProfileData("u1", "alice", "1234567890", " Alice ", "#4F46E5");
    const android = kotlinMapKeys(kotlin("data/remote/RemoteMappers.kt"), "userProfileMap", "fun conversation(");
    assert.deepEqual(Object.keys(data).sort(), [...android].sort());
    assert.deepEqual(Object.keys(data).sort(), rulesKeys("match /users/{userId}").sort());
    assert.equal(data.displayNameLower, "alice");
  });

  test("chat preview fields match ChatRepository.pushMessage", () => {
    const keys = Object.keys(lastMessageData({ id: "m", senderId: "u", type: "TEXT", text: "x", clientCreatedAt: 1 }));
    const push = kotlin("data/repository/ChatRepository.kt");
    const block = push.slice(push.indexOf("\"lastMessage\" to mapOf("), push.indexOf("\"lastMessageAt\" to", push.indexOf("\"lastMessage\" to mapOf(")));
    assert.deepEqual(keys.sort(), [...block.matchAll(/"(\w+)" to /g)].map((m) => m[1]).filter((k) => k !== "lastMessage").sort());
  });
});

describe("identifiers and validation (Ids.kt / Validators.kt)", () => {
  test("direct chat IDs are deterministic and ordered", () => {
    assert.equal(directConversationId("bUid", "aUid"), "dm_aUid_bUid");
    assert.equal(directConversationId("aUid", "bUid"), "dm_aUid_bUid");
    assert.throws(() => directConversationId("a", "a"));
  });

  test("login address matches the Android formula", () => {
    assert.equal(authEmailForUsername(" Alice ", "Gen-Lang-Client-0426516505"), "alice@gen-lang-client-0426516505.firebaseapp.com");
  });

  test("username and password rules", () => {
    assert.equal(usernameError("ab"), "TOO_SHORT");
    assert.equal(usernameError("a".repeat(21)), "TOO_LONG");
    assert.equal(usernameError("al..ice"), "INVALID_CHARS");
    assert.equal(usernameError("alice."), "INVALID_CHARS");
    assert.equal(usernameError("1alice"), "MUST_START_WITH_LETTER");
    assert.equal(usernameError("@Alice_1"), null);
    const rulesUsername = /^[a-z][a-z0-9._]{2,19}$/;
    for (const ok of ["abc", "alice_1", "a.b.c", "z".repeat(20)]) assert.ok(usernameError(ok) === null && rulesUsername.test(ok), ok);
    assert.equal(passwordError("short1"), "TOO_SHORT");
    assert.equal(passwordError("abcdefgh"), "WEAK");
    assert.equal(passwordError("12345678"), "WEAK");
    assert.equal(passwordError("Passw0rd1"), null);
  });

  test("10-digit IDs never start with 0", () => {
    for (let i = 0; i < 2000; i++) assert.match(generate10DigitId(), /^[1-9][0-9]{9}$/);
  });

  test("avatar colour uses java.lang.String#hashCode", () => {
    assert.equal(javaHashCode("hello"), 99162322);
    assert.equal(javaHashCode("polygenelubricants"), -2147483648);
    assert.equal(avatarColorFor("polygenelubricants"), "#4F46E5");
  });
});

describe("text, media and delivery helpers", () => {
  test("preview collapses whitespace and truncates like TextTools.preview", () => {
    assert.equal(preview("  hello \n\t world  "), "hello world");
    const long = preview("x".repeat(200), 100);
    assert.equal(long.length, 100);
    assert.ok(long.endsWith("…"));
  });

  test("MIME type mapping", () => {
    assert.equal(typeForMime("image/jpeg"), "IMAGE");
    assert.equal(typeForMime("audio/webm;codecs=opus"), "AUDIO");
    assert.equal(typeForMime("application/pdf"), "DOCUMENT");
    assert.equal(extensionFor("audio/webm;codecs=opus", ""), "webm");
    assert.equal(extensionFor("application/x-unknown", "Report.PDF"), "pdf");
    assert.equal(extensionFor("application/x-unknown", "noext"), "bin");
  });

  test("delivery status follows the receipt watermarks (DeliveryPolicy.fromReceipts)", () => {
    const conv = { participants: ["me", "a", "b"], receipts: {} } as unknown as Conversation;
    const msg = { senderId: "me", pending: false, createdAt: 1000 } as ChatMessage;
    assert.equal(deliveryStatus({ ...msg, pending: true }, conv, "me"), "sending");
    assert.equal(deliveryStatus(msg, conv, "me"), "sent");
    conv.receipts = { a: { deliveredUpTo: 1000, readUpTo: 0 }, b: { deliveredUpTo: 0, readUpTo: 1500 } };
    assert.equal(deliveryStatus(msg, conv, "me"), "delivered");
    conv.receipts.a.readUpTo = 1000;
    assert.equal(deliveryStatus(msg, conv, "me"), "read");
    assert.equal(deliveryStatus({ ...msg, senderId: "a" }, conv, "me"), null);
  });

  test("config from environment variables", () => {
    assert.throws(() => configFromEnv({}), /VITE_FIREBASE_API_KEY/);
    const config = configFromEnv({ VITE_FIREBASE_API_KEY: "k", VITE_FIREBASE_PROJECT_ID: ANDROID_FIREBASE_PROJECT.projectId, VITE_FIREBASE_APP_ID: "1:2:web:3" });
    assert.equal(config.firestoreDatabaseId, ANDROID_FIRESTORE_DATABASE_ID);
    assert.equal(config.firebase.storageBucket, ANDROID_FIREBASE_PROJECT.storageBucket);
    assert.equal(config.firebase.authDomain, ANDROID_FIREBASE_PROJECT.authDomain);
    assert.equal(config.emulators, undefined);
  });
});
