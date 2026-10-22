/**
 * Integration tests: real web clients (this data layer on the Firebase JS SDK) against the Firebase
 * emulators running this repo's firestore.rules and storage.rules, on the Android app's named database.
 *
 * Run from pulsechat/ (where firebase.json lives):
 *   firebase emulators:exec --only auth,firestore,storage --project demo-pulsechat "npm --prefix ../web run test:emulators"
 */
import assert from "node:assert/strict";
import { after, describe, test } from "node:test";
import { onLog } from "firebase/app";
import {
  Bytes, collection, deleteDoc, disableNetwork, doc, enableNetwork, getDoc, getDocFromServer, getDocs, serverTimestamp, setDoc, Timestamp,
  updateDoc, waitForPendingWrites, writeBatch, type Unsubscribe,
} from "firebase/firestore";
import { getBytes, ref as storageRef, uploadBytes } from "firebase/storage";
import {
  ANDROID_FIRESTORE_DATABASE_ID, MEDIA_CHUNK_BYTES, authEmailForUsername, createGroup, deleteForEveryone, deliveryStatus, directChat,
  directConversationId, disposePulseChat, editMessage, initPulseChat, isStoreRef, markConversationDelivered, markRead, mediaBytes,
  observeConversations, observeMessages, observePresence, observeSession, react, searchUsers, sendMedia, sendText, setTyping, signIn,
  signOut, signUp, startPresence, storeIdOf,
  type ChatMessage, type Conversation, type PresenceView, type PulseChat, type PulseChatAuthError, type Session,
} from "../src/pulsechat";

const PROJECT = process.env.PULSECHAT_TEST_PROJECT ?? "demo-pulsechat";
const HOST = process.env.PULSECHAT_EMULATOR_HOST ?? "127.0.0.1";
const PASSWORD = "Passw0rd1";
const RUN = Date.now().toString(36).slice(-6);
let counter = 0;
const username = (base: string) => `${base}${++counter}${RUN}`;

const clients: PulseChat[] = [];
const listeners: Unsubscribe[] = [];

function client(): PulseChat {
  const pc = initPulseChat({
    appName: `client-${clients.length}-${RUN}`,
    firebase: { apiKey: "demo-api-key", authDomain: `${PROJECT}.firebaseapp.com`, projectId: PROJECT, storageBucket: `${PROJECT}.appspot.com`, appId: "1:1:web:1" },
    firestoreDatabaseId: ANDROID_FIRESTORE_DATABASE_ID,
    emulators: { host: HOST },
  });
  clients.push(pc);
  return pc;
}

async function newUser(base: string, displayName = base): Promise<{ pc: PulseChat; uid: string; username: string }> {
  const pc = client();
  const name = username(base);
  const uid = await signUp(pc, { displayName, username: name, password: PASSWORD, confirm: PASSWORD });
  return { pc, uid, username: name };
}

// Known Firestore emulator bug (firebase/firebase-tools#8654): a Listen stream sometimes receives a corrupted
// frame ("RESOURCE_EXHAUSTED: Received message larger than max (554173458 vs ...)") and the SDK then waits its
// maximum backoff, about a minute, before listening again. That pause is not what these tests check, so the
// next wait restarts the clients' connections (pending writes and listeners carry over) and keeps waiting.
let listenDesyncs = 0;
let handledDesyncs = 0;
onLog((entry) => {
  if (entry.type === "@firebase/firestore" && entry.message.includes("'Listen'") && entry.message.includes("RESOURCE_EXHAUSTED")) listenDesyncs++;
});

async function restartConnectionsAfterDesync(what: string): Promise<boolean> {
  if (listenDesyncs === handledDesyncs) return false;
  handledDesyncs = listenDesyncs;
  console.warn(`emulator Listen-stream desync (firebase-tools#8654) before: ${what}; restarting the client connections`);
  await Promise.all(clients.map((pc) => disableNetwork(pc.db).then(() => enableNetwork(pc.db)).catch(() => undefined)));
  return true;
}

async function until(what: string, condition: () => boolean, timeoutMs = 20_000, details?: () => string): Promise<void> {
  let deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (condition()) return;
    if (await restartConnectionsAfterDesync(what)) deadline = Math.max(deadline, Date.now() + timeoutMs);
    await new Promise((r) => setTimeout(r, 25));
  }
  assert.fail(`timed out waiting for: ${what}${details ? ` (state: ${details()})` : ""}`);
}

async function expectDenied(what: string, write: Promise<unknown>): Promise<void> {
  await assert.rejects(write, (e: { code?: string }) => {
    assert.ok(e.code === "permission-denied" || e.code === "storage/unauthorized", `${what}: expected a rules denial, got ${e.code}`);
    return true;
  });
}

/** Keeps the latest value delivered by a listener. */
function live<T>(subscribe: (set: (value: T) => void) => Unsubscribe, initial: T): { readonly value: T } {
  const box = { value: initial };
  listeners.push(subscribe((v) => { box.value = v; }));
  return box;
}

after(async () => {
  listeners.forEach((stop) => stop());
  await Promise.all(clients.map((pc) => disposePulseChat(pc).catch(() => undefined)));
});

const ANDROID_MESSAGE_KEYS = ["senderId", "type", "text", "createdAt", "updatedAt", "clientCreatedAt", "editedAt", "deletedForEveryone", "forwarded", "mentions", "reactions"];
const ANDROID_PROFILE_KEYS = ["uid", "username", "uniqueId10", "displayName", "displayNameLower", "avatarColorHex", "lastSeenVisibility", "photoVisibility", "aboutVisibility", "createdAt"];

describe("accounts (same scheme as the Android app)", () => {
  test("sign-up creates the profile and handles; sign-in works by username and by 10-digit ID", { timeout: 60_000 }, async () => {
    const alice = await newUser("alice", "Alice Web");
    const profile = await getDoc(doc(alice.pc.db, "users", alice.uid));
    assert.deepEqual(Object.keys(profile.data()!).sort(), [...ANDROID_PROFILE_KEYS].sort());
    assert.ok(profile.get("createdAt") instanceof Timestamp);
    const id10 = profile.get("uniqueId10") as string;
    assert.match(id10, /^[1-9][0-9]{9}$/);
    assert.deepEqual((await getDoc(doc(alice.pc.db, "usernames", alice.username))).data(), { uid: alice.uid });
    assert.deepEqual((await getDoc(doc(alice.pc.db, "uniqueIds", id10))).data(), { uid: alice.uid, username: alice.username });
    assert.equal(alice.pc.auth.currentUser?.email, authEmailForUsername(alice.username, PROJECT), "Android login address");

    const sessions: Session[] = [];
    listeners.push(observeSession(alice.pc, (s) => sessions.push(s)));
    await until("signed-in session", () => sessions.at(-1)?.state === "signedIn");

    await signOut(alice.pc);
    await until("signed-out session", () => sessions.at(-1)?.state === "signedOut");
    assert.equal(await signIn(alice.pc, `@${alice.username.toUpperCase()}`, PASSWORD), alice.uid);
    await signOut(alice.pc);
    assert.equal(await signIn(alice.pc, id10, PASSWORD), alice.uid);
    await signOut(alice.pc);
    await assert.rejects(signIn(alice.pc, alice.username, "Wrong0pass"), (e: PulseChatAuthError) => e.code === "INVALID_CREDENTIALS");
    await assert.rejects(
      signUp(alice.pc, { displayName: "Copy", username: alice.username, password: PASSWORD }),
      (e: PulseChatAuthError) => e.code === "USERNAME_TAKEN",
    );
  });
});

describe("direct chat between two web clients", () => {
  test("search, first message creates the chat, real-time delivery, receipts, typing, media, reactions, edit, delete", { timeout: 120_000 }, async () => {
    const alice = await newUser("alice", "Alice");
    const bob = await newUser("bob", "Bob");
    const aliceId10 = (await getDoc(doc(bob.pc.db, "users", alice.uid))).get("uniqueId10") as string;

    // Search by exact username (any case, with or without "@") or 10-digit ID, like Android.
    // Display names and partial usernames find nobody: names are not unique.
    assert.equal((await searchUsers(bob.pc, `@${alice.username.toUpperCase()}`))[0]?.uid, alice.uid);
    assert.equal((await searchUsers(bob.pc, aliceId10))[0]?.uid, alice.uid);
    assert.deepEqual(await searchUsers(bob.pc, "Alice"), []);
    assert.deepEqual(await searchUsers(bob.pc, alice.username.slice(0, -2)), []);

    // Listener errors are recorded so a failed wait says why (a denied or broken listener vs. a late update).
    const listenErrors: string[] = [];
    const aliceChats = live<Conversation[]>((set) => observeConversations(alice.pc, set, (e) => { listenErrors.push(`alice: ${e.code}`); }), []);
    const bobChats = live<Conversation[]>((set) => observeConversations(bob.pc, set, (e) => { listenErrors.push(`bob: ${e.code}`); }), []);

    // Bob's first message creates dm_<low>_<high> in the same batch.
    const draft = directChat(bob.pc, alice.uid);
    assert.equal(draft.id, directConversationId(alice.uid, bob.uid));
    const firstId = await sendText(bob.pc, draft, "  Hello from the web 👋  ");
    const aliceConv = () => aliceChats.value.find((c) => c.id === draft.id)!;
    const bobConv = () => bobChats.value.find((c) => c.id === draft.id)!;
    await until("chat appears for Alice", () => aliceConv()?.lastMessage?.id === firstId, 20_000, () =>
      `Alice sees [${aliceChats.value.map((c) => `${c.id}:${c.lastMessage?.id ?? "-"}`).join(", ")}], listener errors [${listenErrors.join(", ")}]`);
    assert.equal(aliceConv().peerId, bob.uid);
    assert.equal(aliceConv().lastMessage?.preview, "Hello from the web 👋");

    const stored = (await getDoc(doc(alice.pc.db, "conversations", draft.id, "messages", firstId))).data()!;
    assert.deepEqual(Object.keys(stored).sort(), [...ANDROID_MESSAGE_KEYS].sort(), "same fields as Android's messageCreateMap");
    assert.ok(stored.createdAt instanceof Timestamp && stored.updatedAt instanceof Timestamp);
    assert.equal(stored.text, "Hello from the web 👋");
    const convDoc = (await getDoc(doc(alice.pc.db, "conversations", draft.id))).data()!;
    assert.equal(convDoc.type, "direct");
    assert.deepEqual(convDoc.participants, [alice.uid, bob.uid].sort());

    // Real-time messages on both sides; Bob's ticks follow Alice's receipts.
    const aliceMessages = live<ChatMessage[]>((set) => observeMessages(alice.pc, draft.id, set), []);
    const bobMessages = live<ChatMessage[]>((set) => observeMessages(bob.pc, draft.id, set), []);
    await until("Alice receives the message", () => aliceMessages.value.some((m) => m.id === firstId && !m.pending));
    await until("Bob's chat list", () => bobConv() !== undefined);
    await until("server ack for Bob", () => bobMessages.value.some((m) => m.id === firstId && !m.pending));
    const bobStatus = () => deliveryStatus(bobMessages.value.find((m) => m.id === firstId)!, bobConv(), bob.uid);
    assert.equal(bobStatus(), "sent");
    await markConversationDelivered(alice.pc, aliceConv());
    await until("delivered tick", () => bobStatus() === "delivered");
    await markRead(alice.pc, aliceConv(), aliceMessages.value);
    await until("read tick", () => bobStatus() === "read");

    // Typing indicator, then a reply that clears it and quotes Bob's message.
    await setTyping(alice.pc, aliceConv(), true);
    await until("Bob sees Alice typing", () => (bobConv().typing[alice.uid] ?? 0) > 0);
    const original = aliceMessages.value.find((m) => m.id === firstId)!;
    const replyId = await sendText(alice.pc, aliceConv(), "Hi Bob", { replyTo: original });
    await until("Bob receives the reply", () => bobMessages.value.some((m) => m.id === replyId));
    await until("typing marker cleared by sending", () => bobConv().typing[alice.uid] === undefined);
    assert.equal(bobMessages.value.find((m) => m.id === replyId)!.replyTo?.id, firstId);

    // Media: Storage path in the sender's folder; the peer downloads identical bytes.
    const png = Uint8Array.from(Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64"));
    const photoId = await sendMedia(bob.pc, bobConv(), { data: png, mime: "image/png", fileName: "pixel.png", caption: "pixel", width: 1, height: 1 });
    await until("Alice receives the photo", () => aliceMessages.value.some((m) => m.id === photoId));
    const photo = aliceMessages.value.find((m) => m.id === photoId)!;
    assert.equal(photo.type, "IMAGE");
    assert.equal(photo.text, "pixel");
    assert.equal(photo.media?.path, `chat-media/${draft.id}/${bob.uid}/${photoId}.png`);
    assert.equal(photo.media?.size, png.byteLength);
    assert.deepEqual(new Uint8Array(await getBytes(storageRef(alice.pc.storage, photo.media!.path))), png);
    await expectDenied("upload into another user's folder", uploadBytes(storageRef(alice.pc.storage, `chat-media/${draft.id}/${bob.uid}/x.png`), png, { contentType: "image/png" }));

    // Reactions, edit and delete-for-everyone as the rules allow them.
    await react(alice.pc, aliceConv(), original, "👍");
    await until("reaction reaches Bob", () => bobMessages.value.find((m) => m.id === firstId)?.reactions[alice.uid] === "👍");
    await editMessage(bob.pc, bobConv(), bobMessages.value.find((m) => m.id === firstId)!, "Hello again");
    await until("edit reaches Alice", () => {
      const m = aliceMessages.value.find((x) => x.id === firstId);
      return m?.text === "Hello again" && m.editedAt > 0;
    });
    // The replaced text is kept in the message's edit history, visible to the chat's members.
    const edited = aliceMessages.value.find((x) => x.id === firstId)!;
    assert.deepEqual(edited.edits.map((e) => e.text), ["Hello from the web 👋"]);
    assert.ok(edited.edits[0].at > 0);
    await until("Bob sees his photo as the last message", () => bobConv().lastMessage?.id === photoId);
    await deleteForEveryone(bob.pc, bobConv(), bobMessages.value.find((m) => m.id === photoId)!);
    await until("deletion reaches Alice", () => aliceMessages.value.find((m) => m.id === photoId)?.deleted === true);
    const deleted = (await getDoc(doc(alice.pc.db, "conversations", draft.id, "messages", photoId))).data()!;
    assert.equal(deleted.text, "");
    assert.equal("media" in deleted, false);

    // The rules stop impersonation and edits of other people's messages.
    await expectDenied(
      "editing someone else's message",
      updateDoc(doc(alice.pc.db, "conversations", draft.id, "messages", firstId), { text: "hacked", editedAt: serverTimestamp(), updatedAt: serverTimestamp() }),
    );
    await expectDenied("changing someone else's receipt", updateDoc(doc(alice.pc.db, "conversations", draft.id), { [`receipts.${bob.uid}.r`]: Timestamp.now() }));
  });
});

describe("built-in media store (projects without Cloud Storage)", () => {
  test("chunked upload, download by the peer, references in the message, owner-only writes", { timeout: 90_000 }, async () => {
    const alice = await newUser("alice", "Alice");
    const bob = await newUser("bob", "Bob");
    const draft = directChat(bob.pc, alice.uid);
    await sendText(bob.pc, draft, "hi");
    const bobChats = live<Conversation[]>((set) => observeConversations(bob.pc, set), []);
    const aliceMessages = live<ChatMessage[]>((set) => observeMessages(alice.pc, draft.id, set), []);
    await until("Bob's chat list", () => bobChats.value.some((c) => c.id === draft.id));
    const conv = bobChats.value.find((c) => c.id === draft.id)!;

    // 700 KB of voice data: two chunks.
    const voice = Uint8Array.from({ length: MEDIA_CHUNK_BYTES + 180_000 }, (_, i) => (i * 31) % 251);
    const id = await sendMedia(bob.pc, conv, { data: voice, mime: "audio/mp4", fileName: "voice.m4a", durationMs: 3000 }, { store: "builtin" });
    await until("Alice receives the voice note", () => aliceMessages.value.some((m) => m.id === id));
    const msg = aliceMessages.value.find((m) => m.id === id)!;
    assert.equal(msg.type, "AUDIO");
    assert.ok(isStoreRef(msg.media?.url) && msg.media?.path === msg.media?.url, `built-in reference: ${msg.media?.url}`);
    const meta = (await getDoc(doc(alice.pc.db, "media", storeIdOf(msg.media!.url)!))).data()!;
    assert.equal(meta.chunks, 2);
    assert.equal(meta.owner, bob.uid);
    assert.deepEqual(await mediaBytes(alice.pc, msg.media!), voice, "Alice downloads identical bytes");

    const mediaId = storeIdOf(msg.media!.url)!;
    await expectDenied("overwriting someone else's chunk", setDoc(doc(alice.pc.db, "media", mediaId, "chunks", "0"), { owner: alice.uid, data: Bytes.fromUint8Array(new Uint8Array(4)) }));
    await expectDenied("deleting someone else's media", deleteDoc(doc(alice.pc.db, "media", mediaId)));
  });
});

describe("presence", () => {
  test("heartbeat and last seen reach other users (privacy setting: everyone)", { timeout: 90_000 }, async () => {
    const alice = await newUser("palice");
    const bob = await newUser("pbob");
    const stopPresence = startPresence(alice.pc);
    await waitForPendingWrites(alice.pc.db);
    const own = await getDocFromServer(doc(alice.pc.db, "presence", alice.uid));
    assert.equal(own.get("online"), true, "heartbeat stored on the server");
    assert.ok(own.get("heartbeatAt") instanceof Timestamp, "heartbeatAt is a server timestamp");
    const peerRead = await getDocFromServer(doc(bob.pc.db, "presence", alice.uid)).then((d) => d.data(), (e: { code?: string }) => `error ${e.code}`);
    assert.equal(typeof peerRead === "object" && peerRead?.online, true, `Bob reads Alice's presence: ${JSON.stringify(peerRead)}`);

    const presence = live<PresenceView | null>((set) => observePresence(bob.pc, alice.uid, set), null);
    await until("listener reports Alice online", () => presence.value?.online === true, 20_000, () => JSON.stringify(presence.value));
    await stopPresence();
    await until("listener reports last seen", () => presence.value?.online === false && presence.value.lastSeen > 0, 20_000, () => JSON.stringify(presence.value));
  });
});

describe("listeners attached before the document exists", () => {
  test("session observer during sign-up; presence listener before the first heartbeat", { timeout: 90_000 }, async () => {
    const pc = client();
    const states: string[] = [];
    listeners.push(observeSession(pc, (s) => states.push(s.state)));
    await until("signed out", () => states.at(-1) === "signedOut", 20_000, () => states.join(","));
    const uid = await signUp(pc, { displayName: "Fresh", username: username("fresh"), password: PASSWORD });
    await until("signed in right after sign-up", () => states.at(-1) === "signedIn", 20_000, () => states.join(","));
    assert.ok(!states.includes("needsProfile"), `no "needsProfile" flash during sign-up: ${states.join(",")}`);

    const bob = await newUser("ebob");
    const early = live<PresenceView | null>((set) => observePresence(bob.pc, uid, set), null);
    await until("first presence snapshot", () => early.value !== null, 20_000, () => JSON.stringify(early.value));
    assert.equal(early.value?.error, undefined, `listening to a missing presence document: ${JSON.stringify(early.value)}`);
    const stop = startPresence(pc);
    await until("the early listener sees the first heartbeat", () => early.value?.online === true, 20_000, () => JSON.stringify(early.value));
    await stop();
  });
});

describe("groups", () => {
  test("create a group like Android; members chat in real time; outsiders are denied", { timeout: 90_000 }, async () => {
    const alice = await newUser("galice", "Alice");
    const bob = await newUser("gbob", "Bob");
    const carol = await newUser("gcarol", "Carol");
    const mallory = await newUser("gmal", "Mallory");
    const gid = await createGroup(alice.pc, { title: "Web team", description: "from the browser", memberIds: [bob.uid, carol.uid] });
    assert.match(gid, /^grp_[0-9a-f]{32}$/);

    const bobChats = live<Conversation[]>((set) => observeConversations(bob.pc, set), []);
    await until("group appears for Bob", () => bobChats.value.some((c) => c.id === gid));
    const group = bobChats.value.find((c) => c.id === gid)!;
    assert.equal(group.type, "group");
    assert.equal(group.title, "Web team");
    assert.deepEqual(group.admins, [alice.uid]);

    const carolMessages = live<ChatMessage[]>((set) => observeMessages(carol.pc, gid, set), []);
    await until("system message", () => carolMessages.value.some((m) => m.type === "SYSTEM" && m.system?.event === "created" && m.system.actor === alice.uid));
    await sendText(bob.pc, group, "hello group");
    await until("Carol receives Bob's message", () => carolMessages.value.some((m) => m.text === "hello group" && m.senderId === bob.uid));

    await expectDenied("non-member reads messages", getDocs(collection(mallory.pc.db, "conversations", gid, "messages")));
    await expectDenied("non-member posts", sendText(mallory.pc, { ...group, participants: [...group.participants, mallory.uid] }, "let me in"));
  });
});

describe("why the web app must use this schema", () => {
  test("layouts from the old AI Studio blueprint and client-side timestamps are rejected", { timeout: 60_000 }, async () => {
    const alice = await newUser("lalice");
    const bob = await newUser("lbob");
    await expectDenied(
      "top-level messages collection (firebase-blueprint.json)",
      setDoc(doc(alice.pc.db, "messages", "legacy1"), {
        messageId: "legacy1", conversationId: "c1", senderId: alice.uid, receiverId: bob.uid, content: "hi", type: "TEXT", status: "SENT", timestamp: Date.now(),
      }),
    );
    await expectDenied("direct chat with a non-deterministic ID", setDoc(doc(alice.pc.db, "conversations", "chat_1"), { type: "direct", participants: [alice.uid, bob.uid].sort() }));
    const cid = directConversationId(alice.uid, bob.uid);
    const batch = writeBatch(alice.pc.db);
    batch.set(doc(alice.pc.db, "conversations", cid), { type: "direct", participants: [alice.uid, bob.uid].sort() }, { merge: true });
    batch.set(doc(alice.pc.db, "conversations", cid, "messages", "m1"), {
      senderId: alice.uid, type: "TEXT", text: "hi", createdAt: Timestamp.now(), updatedAt: Timestamp.now(), clientCreatedAt: Date.now(),
      editedAt: null, deletedForEveryone: false, forwarded: false, mentions: [], reactions: {},
    });
    await expectDenied("client clock instead of serverTimestamp()", batch.commit());
  });
});
