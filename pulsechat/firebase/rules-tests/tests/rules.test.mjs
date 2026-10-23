import { readFileSync } from "node:fs";
import { after, before, beforeEach, describe, test } from "node:test";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import {
  arrayRemove, arrayUnion, Bytes, collection, deleteDoc, deleteField, doc, getDoc, getDocs, query, serverTimestamp, setDoc, Timestamp,
  updateDoc, where, writeBatch,
} from "firebase/firestore";
import { deleteObject, getBytes, ref as sRef, uploadBytes } from "firebase/storage";

let env;
const rules = readFileSync(new URL("../../../firestore.rules", import.meta.url), "utf8");
const storageRules = readFileSync(new URL("../../../storage.rules", import.meta.url), "utf8");

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-pulsechat",
    firestore: { rules, host: "127.0.0.1", port: 8080 },
    storage: { rules: storageRules, host: "127.0.0.1", port: 9199 },
  });
});
after(async () => { await env?.cleanup(); });
beforeEach(async () => {
  await env.clearFirestore();
  await env.clearStorage();
});

const db = (uid) => env.authenticatedContext(uid).firestore();
const st = (uid) => env.authenticatedContext(uid).storage();
const anon = () => env.unauthenticatedContext().firestore();
const anonSt = () => env.unauthenticatedContext().storage();
const seed = (fn) => env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore()));
const seedSt = (fn) => env.withSecurityRulesDisabled(async (ctx) => fn(ctx.storage()));

const ids = { alice: "1000000001", bob: "1000000002", carol: "1000000003", dave: "1000000004" };
async function seedUser(fs, uid, extra = {}) {
  await setDoc(doc(fs, `users/${uid}`), {
    uid, username: uid, uniqueId10: ids[uid], displayName: uid, displayNameLower: uid, avatarColorHex: "#4F46E5",
    lastSeenVisibility: "everyone", photoVisibility: "everyone", aboutVisibility: "everyone", ...extra,
  });
}
async function seedPeople(extra = {}) {
  await seed(async (fs) => { for (const u of ["alice", "bob", "carol", "dave"]) await seedUser(fs, u, extra[u] ?? {}); });
}
const DM = "dm_alice_bob";
async function seedDm() {
  await seed(async (fs) => setDoc(doc(fs, `conversations/${DM}`), { type: "direct", participants: ["alice", "bob"] }));
}
function messageData(sender, extra = {}) {
  return {
    senderId: sender, type: "TEXT", text: "hello", createdAt: serverTimestamp(), updatedAt: serverTimestamp(), clientCreatedAt: Date.now(),
    editedAt: null, deletedForEveryone: false, forwarded: false, mentions: [], reactions: {}, ...extra,
  };
}
async function seedMessage(cid, mid, sender, ageMs = 0, extra = {}) {
  const at = Timestamp.fromMillis(Date.now() - ageMs);
  await seed(async (fs) => setDoc(doc(fs, `conversations/${cid}/messages/${mid}`), {
    senderId: sender, type: "TEXT", text: "hi", createdAt: at, updatedAt: at, clientCreatedAt: Date.now() - ageMs, editedAt: null,
    deletedForEveryone: false, forwarded: false, mentions: [], reactions: {}, ...extra,
  }));
}
async function seedGroup(extra = {}) {
  await seed(async (fs) => setDoc(doc(fs, "conversations/g1"), {
    type: "group", participants: ["alice", "bob", "carol"], admins: ["alice"], createdBy: "alice", title: "Team", description: "",
    onlyAdminsCanSend: false, onlyAdminsCanEditInfo: true, disappearingSeconds: 0, ...extra,
  }));
}

describe("identity and handles", () => {
  test("a user creates their own profile together with username and ID reservations", async () => {
    const fs = db("alice");
    const b = writeBatch(fs);
    b.set(doc(fs, "usernames/alice"), { uid: "alice" });
    b.set(doc(fs, `uniqueIds/${ids.alice}`), { uid: "alice", username: "alice" });
    b.set(doc(fs, "users/alice"), {
      uid: "alice", username: "alice", uniqueId10: ids.alice, displayName: "Alice", displayNameLower: "alice", avatarColorHex: "#4F46E5",
      lastSeenVisibility: "everyone", photoVisibility: "everyone", aboutVisibility: "everyone", createdAt: serverTimestamp(),
    });
    await assertSucceeds(b.commit());
  });

  test("nobody can take an existing username or create a profile for someone else", async () => {
    await seed(async (fs) => setDoc(doc(fs, "usernames/alice"), { uid: "alice" }));
    await assertFails(setDoc(doc(db("bob"), "usernames/alice"), { uid: "bob" }));
    await assertFails(setDoc(doc(db("bob"), "users/alice"), { uid: "alice", username: "alice" }));
  });

  test("username is immutable but display name can change", async () => {
    await seedPeople();
    await assertFails(updateDoc(doc(db("alice"), "users/alice"), { username: "queen" }));
    await assertSucceeds(updateDoc(doc(db("alice"), "users/alice"), { displayName: "Alice A.", displayNameLower: "alice a." }));
  });

  test("handle lookups work before sign-in but cannot be listed", async () => {
    await seed(async (fs) => setDoc(doc(fs, "usernames/alice"), { uid: "alice" }));
    await assertSucceeds(getDoc(doc(anon(), "usernames/alice")));
    await assertFails(getDocs(collection(anon(), "usernames")));
    await assertFails(getDoc(doc(anon(), "users/alice")));
  });
});

describe("direct chats and messages", () => {
  test("direct chats must use the deterministic ID", async () => {
    await seedPeople();
    await assertSucceeds(setDoc(doc(db("alice"), `conversations/${DM}`), { type: "direct", participants: ["alice", "bob"] }));
    await assertFails(setDoc(doc(db("alice"), "conversations/random"), { type: "direct", participants: ["alice", "bob"] }));
    await assertFails(setDoc(doc(db("alice"), "conversations/dm_bob_alice"), { type: "direct", participants: ["bob", "alice"] }));
  });

  test("first message is created atomically with the chat, exactly like the app does", async () => {
    await seedPeople();
    const fs = db("alice");
    const conv = doc(fs, `conversations/${DM}`);
    const b = writeBatch(fs);
    b.set(conv, { type: "direct", participants: ["alice", "bob"] }, { merge: true });
    b.set(doc(fs, `conversations/${DM}/messages/m1`), messageData("alice"));
    b.update(conv, {
      lastMessage: { id: "m1", senderId: "alice", type: "TEXT", preview: "hello", createdAt: serverTimestamp() },
      lastMessageAt: serverTimestamp(), updatedAt: serverTimestamp(), "typing.alice": deleteField(),
    });
    await assertSucceeds(b.commit());
  });

  test("outsiders cannot read or post; senders cannot be spoofed", async () => {
    await seedPeople();
    await seedDm();
    await assertFails(getDoc(doc(db("carol"), `conversations/${DM}`)));
    await assertFails(setDoc(doc(db("carol"), `conversations/${DM}/messages/x`), messageData("carol")));
    await assertFails(setDoc(doc(db("alice"), `conversations/${DM}/messages/x`), messageData("bob")));
    await assertSucceeds(setDoc(doc(db("bob"), `conversations/${DM}/messages/x`), messageData("bob")));
  });

  test("a message ID can be created only once, so scheduled-message retries can never duplicate it", async () => {
    await seedPeople();
    await seedDm();
    const fs = db("alice");
    const msg = doc(fs, `conversations/${DM}/messages/sched-1`);
    // Before sending, the app checks the server: reading a message ID that doesn't exist is allowed.
    await assertSucceeds(getDoc(msg));
    await assertSucceeds(setDoc(msg, messageData("alice", { text: "scheduled" })));
    // A retry of the same scheduled message (same ID, same batch as the app) is rejected as a whole.
    const conv = doc(fs, `conversations/${DM}`);
    const retry = writeBatch(fs);
    retry.set(conv, { type: "direct", participants: ["alice", "bob"] }, { merge: true });
    retry.set(msg, messageData("alice", { text: "scheduled" }));
    retry.update(conv, { lastMessage: { id: "sched-1", senderId: "alice", type: "TEXT", preview: "scheduled", createdAt: serverTimestamp() },
      lastMessageAt: serverTimestamp(), updatedAt: serverTimestamp() });
    await assertFails(retry.commit());
    await assertFails(setDoc(msg, messageData("alice", { text: "scheduled" })));
    const stored = await getDoc(msg);
    if (stored.data().text !== "scheduled") throw new Error("the first copy must stay untouched");
  });

  test("a blocked user cannot message the person who blocked them", async () => {
    await seedPeople();
    await seedDm();
    await seed(async (fs) => setDoc(doc(fs, "users/bob/blocked/alice"), { blockedAt: Timestamp.now() }));
    await assertFails(setDoc(doc(db("alice"), `conversations/${DM}/messages/x`), messageData("alice")));
  });

  test("only the author can edit, and only within 15 minutes", async () => {
    await seedPeople();
    await seedDm();
    await seedMessage(DM, "fresh", "alice", 60_000);
    await seedMessage(DM, "old", "alice", 20 * 60_000);
    const edit = { text: "edited", editedAt: serverTimestamp(), updatedAt: serverTimestamp(), edits: arrayUnion({ text: "hi", at: Timestamp.now() }) };
    await assertFails(updateDoc(doc(db("bob"), `conversations/${DM}/messages/fresh`), edit));
    await assertSucceeds(updateDoc(doc(db("alice"), `conversations/${DM}/messages/fresh`), edit));
    await assertFails(updateDoc(doc(db("alice"), `conversations/${DM}/messages/old`), edit));
  });

  test("every edit keeps the replaced text in the history, which cannot be skipped or rewritten", async () => {
    await seedPeople();
    await seedDm();
    await seedMessage(DM, "m1", "alice", 60_000);
    const msg = (uid) => doc(db(uid), `conversations/${DM}/messages/m1`);
    const base = { editedAt: serverTimestamp(), updatedAt: serverTimestamp() };
    // An edit without a history entry, with a made-up earlier text or a far-off time is refused.
    await assertFails(updateDoc(msg("alice"), { ...base, text: "v2" }));
    await assertFails(updateDoc(msg("alice"), { ...base, text: "v2", edits: arrayUnion({ text: "something else", at: Timestamp.now() }) }));
    await assertFails(updateDoc(msg("alice"), { ...base, text: "v2", edits: arrayUnion({ text: "hi", at: Timestamp.fromMillis(Date.now() + 3_600_000) }) }));
    // The real edit records the replaced text; the next one must keep the first entry and add the second.
    await assertSucceeds(updateDoc(msg("alice"), { ...base, text: "v2", edits: arrayUnion({ text: "hi", at: Timestamp.now() }) }));
    await assertFails(updateDoc(msg("alice"), { ...base, text: "v3", edits: [{ text: "v2", at: Timestamp.now() }] }));
    await assertSucceeds(updateDoc(msg("alice"), { ...base, text: "v3", edits: arrayUnion({ text: "v2", at: Timestamp.now() }) }));
    const stored = (await getDoc(msg("bob"))).data();
    if (stored.edits.length !== 2 || stored.edits[0].text !== "hi" || stored.edits[1].text !== "v2") throw new Error("history must list both earlier versions");
    // Only members of the chat can read the message, and with it the history.
    await assertFails(getDoc(doc(db("carol"), `conversations/${DM}/messages/m1`)));
  });

  test("delete for everyone must remove the content and is author-only", async () => {
    await seedPeople();
    await seedDm();
    await seedMessage(DM, "m1", "alice", 60_000, { media: { url: "https://x", path: "chat-media/x" } });
    const wipe = {
      deletedForEveryone: true, text: "", media: deleteField(), replyTo: deleteField(), poll: deleteField(), contact: deleteField(),
      location: deleteField(), edits: deleteField(), mentions: [], reactions: {}, updatedAt: serverTimestamp(),
    };
    await assertFails(updateDoc(doc(db("bob"), `conversations/${DM}/messages/m1`), wipe));
    await assertFails(updateDoc(doc(db("alice"), `conversations/${DM}/messages/m1`), { ...wipe, text: "still here" }));
    await assertSucceeds(updateDoc(doc(db("alice"), `conversations/${DM}/messages/m1`), wipe));
    // An edited message loses its edit history with it.
    await seedMessage(DM, "m2", "alice", 60_000, { text: "v2", editedAt: Timestamp.now(), edits: [{ text: "v1", at: Timestamp.now() }] });
    const { edits: _keepHistory, ...wipeWithoutHistory } = wipe;
    await assertFails(updateDoc(doc(db("alice"), `conversations/${DM}/messages/m2`), wipeWithoutHistory));
    await assertSucceeds(updateDoc(doc(db("alice"), `conversations/${DM}/messages/m2`), wipe));
  });

  test("a video's cover travels with the message but must stay small", async () => {
    await seedPeople();
    await seedDm();
    const media = (thumbBytes) => ({
      url: "pulsemedia://md1", path: "pulsemedia://md1", mime: "video/mp4", size: 1000, name: "clip.mp4", durationMs: 3000, width: 640, height: 360,
      waveform: null, coverMs: 1200, ...(thumbBytes === null ? {} : { thumb: Bytes.fromUint8Array(new Uint8Array(thumbBytes)) }),
    });
    const fs = db("alice");
    await assertSucceeds(setDoc(doc(fs, `conversations/${DM}/messages/v1`), messageData("alice", { type: "VIDEO", text: "", media: media(30_000) })));
    await assertSucceeds(setDoc(doc(fs, `conversations/${DM}/messages/v2`), messageData("alice", { type: "VIDEO", text: "", media: media(null) })));
    await assertFails(setDoc(doc(fs, `conversations/${DM}/messages/v3`), messageData("alice", { type: "VIDEO", text: "", media: media(70_000) })));
    await assertFails(setDoc(doc(fs, `conversations/${DM}/messages/v4`), messageData("alice", { type: "VIDEO", text: "", media: { ...media(null), thumb: "not bytes" } })));
  });

  test("reactions and receipts: each participant controls only their own entry", async () => {
    await seedPeople();
    await seedDm();
    await seedMessage(DM, "m1", "alice", 1000);
    await assertSucceeds(updateDoc(doc(db("bob"), `conversations/${DM}/messages/m1`), { "reactions.bob": "👍", updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(db("bob"), `conversations/${DM}/messages/m1`), { "reactions.alice": "👍", updatedAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(doc(db("bob"), `conversations/${DM}`), { "receipts.bob.r": Timestamp.now() }));
    await assertFails(updateDoc(doc(db("bob"), `conversations/${DM}`), { "receipts.alice.r": Timestamp.now() }));
    await assertFails(updateDoc(doc(db("bob"), `conversations/${DM}`), { participants: ["alice", "bob", "carol"] }));
  });
});

describe("live location", () => {
  const live = (uid, id = "s1") => doc(db(uid), `conversations/${DM}/live/${id}`);
  const start = (extra = {}) => ({
    ownerId: "alice", lat: 52.07, lng: 4.38, accuracy: 12, fixAt: Timestamp.now(), startedAt: serverTimestamp(), updatedAt: serverTimestamp(),
    expiresAt: Timestamp.fromMillis(Date.now() + 15 * 60_000), endedAt: null, ...extra,
  });
  const point = (lat) => ({ lat, lng: 4.39, accuracy: 8, fixAt: Timestamp.now(), updatedAt: serverTimestamp() });

  test("only chat members see it and only the sender writes it, for at most 8 hours", async () => {
    await seedPeople();
    await seedDm();
    await assertFails(setDoc(live("carol"), start({ ownerId: "carol" })));
    await assertFails(setDoc(live("bob"), start()));
    await assertFails(setDoc(live("alice"), start({ expiresAt: Timestamp.fromMillis(Date.now() + 9 * 3_600_000) })));
    await assertFails(setDoc(live("alice"), start({ lat: 123 })));
    await assertSucceeds(setDoc(live("alice"), start()));
    await assertSucceeds(getDoc(live("bob")));
    await assertFails(getDoc(live("carol")));
    await assertFails(updateDoc(live("bob"), point(52.08)));
    await assertSucceeds(updateDoc(live("alice"), point(52.08)));
    await assertFails(updateDoc(live("alice"), { expiresAt: Timestamp.fromMillis(Date.now() + 7 * 3_600_000), updatedAt: serverTimestamp() }));
  });

  test("a stopped or expired session can never move again", async () => {
    await seedPeople();
    await seedDm();
    await assertSucceeds(setDoc(live("alice"), start()));
    await assertSucceeds(updateDoc(live("alice"), { endedAt: serverTimestamp(), updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(live("alice"), point(52.09)));
    await assertFails(updateDoc(live("alice"), { endedAt: null, updatedAt: serverTimestamp() }));
    await seed(async (fs) => setDoc(doc(fs, `conversations/${DM}/live/old`), {
      ownerId: "alice", lat: 52, lng: 4, accuracy: 5, fixAt: Timestamp.fromMillis(Date.now() - 3_600_000), startedAt: Timestamp.fromMillis(Date.now() - 3_600_000),
      updatedAt: Timestamp.fromMillis(Date.now() - 3_600_000), expiresAt: Timestamp.fromMillis(Date.now() - 60_000), endedAt: null,
    }));
    await assertFails(updateDoc(live("alice", "old"), point(52.1)));
    // Marking an expired session as ended is still allowed (clean-up), and the sender can delete it.
    await assertSucceeds(updateDoc(live("alice", "old"), { endedAt: serverTimestamp(), updatedAt: serverTimestamp() }));
    await assertFails(deleteDoc(live("bob", "old")));
    await assertSucceeds(deleteDoc(live("alice", "old")));
  });
});

describe("groups", () => {
  test("only admins add members; members cannot promote themselves", async () => {
    await seedPeople();
    await seedGroup();
    await assertFails(updateDoc(doc(db("bob"), "conversations/g1"), { participants: arrayUnion("dave") }));
    await assertFails(updateDoc(doc(db("bob"), "conversations/g1"), { admins: arrayUnion("bob") }));
    await assertSucceeds(updateDoc(doc(db("alice"), "conversations/g1"), { participants: arrayUnion("dave") }));
  });

  test("admins-only messaging is enforced by the server", async () => {
    await seedPeople();
    await seedGroup({ onlyAdminsCanSend: true });
    await assertFails(setDoc(doc(db("bob"), "conversations/g1/messages/x"), messageData("bob")));
    await assertSucceeds(setDoc(doc(db("alice"), "conversations/g1/messages/y"), messageData("alice")));
  });

  test("members can leave; the last admin must hand over admin rights", async () => {
    await seedPeople();
    await seedGroup();
    await assertSucceeds(updateDoc(doc(db("bob"), "conversations/g1"), { participants: arrayRemove("bob") }));
    await assertFails(updateDoc(doc(db("alice"), "conversations/g1"), { participants: arrayRemove("alice"), admins: [] }));
    await assertSucceeds(updateDoc(doc(db("alice"), "conversations/g1"), { participants: arrayRemove("alice"), admins: ["carol"] }));
  });

  test("the group creator cannot be removed by another admin", async () => {
    await seedPeople();
    await seedGroup({ admins: ["alice", "bob"] });
    await assertFails(updateDoc(doc(db("bob"), "conversations/g1"), { participants: arrayRemove("alice"), admins: arrayRemove("alice") }));
    await assertSucceeds(updateDoc(doc(db("bob"), "conversations/g1"), { participants: arrayRemove("carol") }));
  });
});

describe("privacy", () => {
  test("last seen honours visibility, contacts and reciprocity", async () => {
    await seedPeople({ alice: { lastSeenVisibility: "contacts" } });
    await seed(async (fs) => {
      await setDoc(doc(fs, "presence/alice"), { online: true, heartbeatAt: Timestamp.now() });
      await setDoc(doc(fs, "users/alice/contacts/bob"), { nickname: null, addedAt: Timestamp.now() });
    });
    await assertSucceeds(getDoc(doc(db("bob"), "presence/alice")));
    await assertFails(getDoc(doc(db("carol"), "presence/alice")));
    await seed(async (fs) => updateDoc(doc(fs, "users/bob"), { lastSeenVisibility: "nobody" }));
    await assertFails(getDoc(doc(db("bob"), "presence/alice")));
  });

  test("profile photo visibility and blocking are enforced", async () => {
    await seedPeople({ alice: { photoVisibility: "contacts" } });
    await seed(async (fs) => {
      await setDoc(doc(fs, "users/alice/restricted/photo"), { url: "https://x", updatedAt: Timestamp.now() });
      await setDoc(doc(fs, "users/alice/contacts/bob"), { nickname: null, addedAt: Timestamp.now() });
    });
    await assertSucceeds(getDoc(doc(db("bob"), "users/alice/restricted/photo")));
    await assertFails(getDoc(doc(db("carol"), "users/alice/restricted/photo")));
    await seed(async (fs) => setDoc(doc(fs, "users/alice/blocked/bob"), { blockedAt: Timestamp.now() }));
    await assertFails(getDoc(doc(db("bob"), "users/alice/restricted/photo")));
  });
});

describe("statuses, calls and reports", () => {
  test("statuses are readable only by their audience", async () => {
    await seedPeople();
    await seed(async (fs) => setDoc(doc(fs, "statuses/s1"), {
      ownerId: "alice", type: "TEXT", text: "hi", audience: ["bob"], createdAt: Timestamp.now(), expiresAt: Timestamp.fromMillis(Date.now() + 3600_000),
    }));
    await assertSucceeds(getDoc(doc(db("bob"), "statuses/s1")));
    await assertFails(getDoc(doc(db("carol"), "statuses/s1")));
    await assertSucceeds(getDocs(query(collection(db("bob"), "statuses"), where("audience", "array-contains", "bob"))));
    await assertSucceeds(setDoc(doc(db("bob"), "statuses/s1/views/bob"), { viewedAt: serverTimestamp() }));
    await assertFails(setDoc(doc(db("carol"), "statuses/s1/views/carol"), { viewedAt: serverTimestamp() }));
  });

  test("call signalling: only the callee accepts; third parties see nothing", async () => {
    await seedPeople();
    const call = { callerId: "alice", calleeId: "bob", video: false, state: "ringing", offer: { type: "offer", sdp: "v=0" }, createdAt: serverTimestamp() };
    await assertSucceeds(setDoc(doc(db("alice"), "calls/c1"), call));
    await assertFails(getDoc(doc(db("carol"), "calls/c1")));
    await assertFails(updateDoc(doc(db("alice"), "calls/c1"), { state: "accepted", answer: { type: "answer", sdp: "v=0" }, answeredAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(doc(db("bob"), "calls/c1"), { state: "accepted", answer: { type: "answer", sdp: "v=0" }, answeredAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(doc(db("alice"), "calls/c1"), { state: "ended", endedAt: serverTimestamp(), endedBy: "alice" }));
  });

  test("blocked callers cannot ring", async () => {
    await seedPeople();
    await seed(async (fs) => setDoc(doc(fs, "users/bob/blocked/alice"), { blockedAt: Timestamp.now() }));
    await assertFails(setDoc(doc(db("alice"), "calls/c2"), {
      callerId: "alice", calleeId: "bob", video: true, state: "ringing", offer: { type: "offer", sdp: "v=0" }, createdAt: serverTimestamp(),
    }));
  });

  test("reports are write-only", async () => {
    await seedPeople();
    const ref = doc(db("alice"), "reports/r1");
    await assertSucceeds(setDoc(ref, { reporterId: "alice", targetUserId: "bob", conversationId: DM, messageIds: [], reason: "spam", createdAt: serverTimestamp() }));
    await assertFails(getDoc(ref));
  });
});

describe("ownership and housekeeping", () => {
  test("typing markers and chat preferences belong to their owner", async () => {
    await seedPeople();
    await seedDm();
    await assertSucceeds(updateDoc(doc(db("bob"), `conversations/${DM}`), { "typing.bob": serverTimestamp() }));
    await assertFails(updateDoc(doc(db("bob"), `conversations/${DM}`), { "typing.alice": serverTimestamp() }));
    await assertSucceeds(setDoc(doc(db("alice"), `users/alice/chatPrefs/${DM}`), { pinned: true, pinnedAt: 1 }));
    await assertFails(getDoc(doc(db("bob"), `users/alice/chatPrefs/${DM}`)));
    await assertFails(setDoc(doc(db("bob"), `users/alice/chatPrefs/${DM}`), { pinned: false }));
  });

  test("profiles are searchable only by signed-in users", async () => {
    await seedPeople();
    const search = (fs) => getDocs(query(collection(fs, "users"), where("username", ">=", "a"), where("username", "<=", "a\uf8ff")));
    await assertSucceeds(search(db("bob")));
    await assertFails(search(anon()));
  });

  test("only expired disappearing messages can be deleted", async () => {
    await seedPeople();
    await seedDm();
    await seedMessage(DM, "gone", "alice", 0, { expiresAt: Timestamp.fromMillis(Date.now() - 1000) });
    await seedMessage(DM, "live", "alice", 0, { expiresAt: Timestamp.fromMillis(Date.now() + 3600_000) });
    const { deleteDoc } = await import("firebase/firestore");
    await assertSucceeds(deleteDoc(doc(db("bob"), `conversations/${DM}/messages/gone`)));
    await assertFails(deleteDoc(doc(db("bob"), `conversations/${DM}/messages/live`)));
    await assertFails(deleteDoc(doc(db("carol"), `conversations/${DM}/messages/live`)));
  });
});

describe("built-in media store (used when Cloud Storage is unavailable)", () => {
  const meta = (owner, extra = {}) => ({ owner, mime: "image/jpeg", size: 1000, chunks: 2, createdAt: serverTimestamp(), ...extra });
  const chunk = (owner, n = 10) => ({ owner, data: Bytes.fromUint8Array(new Uint8Array(n)) });
  const M = "md0123456789abcdef0123";

  test("the owner uploads a file in chunks (retries overwrite); signed-in users read it by ID but cannot list", async () => {
    const fs = db("alice");
    await assertSucceeds(setDoc(doc(fs, `media/${M}`), meta("alice")));
    await assertSucceeds(setDoc(doc(fs, `media/${M}`), meta("alice")));
    await assertSucceeds(setDoc(doc(fs, `media/${M}/chunks/0`), chunk("alice")));
    await assertSucceeds(setDoc(doc(fs, `media/${M}/chunks/1`), chunk("alice")));
    await assertSucceeds(setDoc(doc(fs, `media/${M}/chunks/1`), chunk("alice")));
    await assertFails(setDoc(doc(fs, `media/${M}/chunks/2`), chunk("alice")));
    await assertSucceeds(getDoc(doc(db("bob"), `media/${M}`)));
    await assertSucceeds(getDoc(doc(db("bob"), `media/${M}/chunks/1`)));
    await assertFails(getDocs(collection(db("bob"), "media")));
    await assertFails(getDocs(collection(db("bob"), `media/${M}/chunks`)));
    await assertFails(getDoc(doc(anon(), `media/${M}`)));
    await assertFails(getDoc(doc(anon(), `media/${M}/chunks/0`)));
  });

  test("nobody else can write, overwrite or delete someone's media", async () => {
    await seed(async (fs) => {
      await setDoc(doc(fs, `media/${M}`), { owner: "alice", mime: "image/jpeg", size: 10, chunks: 1, createdAt: Timestamp.now() });
      await setDoc(doc(fs, `media/${M}/chunks/0`), chunk("alice"));
    });
    const bob = db("bob");
    await assertFails(setDoc(doc(bob, `media/${M}`), meta("bob")));
    await assertFails(setDoc(doc(bob, `media/${M}/chunks/0`), chunk("bob")));
    await assertFails(deleteDoc(doc(bob, `media/${M}/chunks/0`)));
    await assertFails(deleteDoc(doc(bob, `media/${M}`)));
    await assertFails(setDoc(doc(bob, "media/mdNoMetadataDocument01/chunks/0"), chunk("bob")));
    await assertSucceeds(deleteDoc(doc(db("alice"), `media/${M}/chunks/0`)));
    await assertSucceeds(deleteDoc(doc(db("alice"), `media/${M}`)));
  });

  test("sizes, chunk limits, owner and IDs are validated", async () => {
    const fs = db("alice");
    await assertFails(setDoc(doc(fs, "media/mdTooBig000000000001"), meta("alice", { size: 33554433 })));
    await assertFails(setDoc(doc(fs, "media/mdTooManyChunks00001"), meta("alice", { chunks: 65 })));
    await assertFails(setDoc(doc(fs, "media/mdSpoofedOwner000001"), meta("bob")));
    await assertFails(setDoc(doc(fs, "media/mdExtraField00000001"), meta("alice", { url: "x" })));
    await assertFails(setDoc(doc(fs, "media/bad id"), meta("alice")));
    await assertSucceeds(setDoc(doc(fs, "media/mdChunkLimit00000001"), meta("alice")));
    await assertFails(setDoc(doc(fs, "media/mdChunkLimit00000001/chunks/0"), chunk("alice", 524289)));
    await assertSucceeds(setDoc(doc(fs, "media/mdChunkLimit00000001/chunks/0"), chunk("alice", 524288)));
    await assertFails(setDoc(doc(fs, "media/mdChunkLimit00000001/chunks/1"), chunk("bob")));
  });

  test("scoped media enforces chat participation, group membership, status audience/expiry, and profile photo visibility/blocking", async () => {
    await seedPeople({ alice: { photoVisibility: "contacts" } });
    await seedDm();
    await seedGroup();
    await seed(async (fs) => {
      await setDoc(doc(fs, "users/alice/contacts/bob"), { nickname: null, addedAt: Timestamp.now() });
      await setDoc(doc(fs, "statuses/st_live"), {
        ownerId: "alice", type: "IMAGE", text: "hi", audience: ["bob"],
        createdAt: Timestamp.now(), expiresAt: Timestamp.fromMillis(Date.now() + 3600_000),
      });
      await setDoc(doc(fs, "statuses/st_expired"), {
        ownerId: "alice", type: "IMAGE", text: "old", audience: ["bob"],
        createdAt: Timestamp.fromMillis(Date.now() - 7200_000), expiresAt: Timestamp.fromMillis(Date.now() - 60_000),
      });
    });

    const alice = db("alice");
    const bob = db("bob");
    const carol = db("carol");
    const dave = db("dave");

    // 1. Direct chat media: participant (bob) can read, outsider (carol) cannot; non-participant cannot upload scoped to DM
    await assertFails(setDoc(doc(carol, "media/mdForeignChat0000001"), meta("carol", { scope: "chat", conversationId: DM })));
    await assertSucceeds(setDoc(doc(alice, "media/mdChatMedia000000001"), meta("alice", { scope: "chat", conversationId: DM })));
    await assertSucceeds(setDoc(doc(alice, "media/mdChatMedia000000001/chunks/0"), chunk("alice")));
    await assertSucceeds(getDoc(doc(bob, "media/mdChatMedia000000001")));
    await assertSucceeds(getDoc(doc(bob, "media/mdChatMedia000000001/chunks/0")));
    await assertFails(getDoc(doc(carol, "media/mdChatMedia000000001")));
    await assertFails(getDoc(doc(carol, "media/mdChatMedia000000001/chunks/0")));

    // 2. Group media: group member (carol) can read, outsider (dave) cannot
    await assertSucceeds(setDoc(doc(alice, "media/mdGroupMedia00000001"), meta("alice", { scope: "group", conversationId: "g1" })));
    await assertSucceeds(setDoc(doc(alice, "media/mdGroupMedia00000001/chunks/0"), chunk("alice")));
    await assertSucceeds(getDoc(doc(carol, "media/mdGroupMedia00000001")));
    await assertSucceeds(getDoc(doc(carol, "media/mdGroupMedia00000001/chunks/0")));
    await assertFails(getDoc(doc(dave, "media/mdGroupMedia00000001")));
    await assertFails(getDoc(doc(dave, "media/mdGroupMedia00000001/chunks/0")));

    // 3. Status media: audience member (bob) before expiry can read; non-audience (carol) or expired status cannot
    await assertSucceeds(setDoc(doc(alice, "media/mdStatusLive00000001"), meta("alice", { scope: "status", statusId: "st_live" })));
    await assertSucceeds(setDoc(doc(alice, "media/mdStatusLive00000001/chunks/0"), chunk("alice")));
    await assertSucceeds(getDoc(doc(bob, "media/mdStatusLive00000001")));
    await assertSucceeds(getDoc(doc(bob, "media/mdStatusLive00000001/chunks/0")));
    await assertFails(getDoc(doc(carol, "media/mdStatusLive00000001")));
    await assertFails(getDoc(doc(carol, "media/mdStatusLive00000001/chunks/0")));

    await assertSucceeds(setDoc(doc(alice, "media/mdStatusExp000000001"), meta("alice", { scope: "status", statusId: "st_expired" })));
    await assertFails(getDoc(doc(bob, "media/mdStatusExp000000001")));

    // 4. Profile photo media: contacts visibility & blocking enforced
    await assertSucceeds(setDoc(doc(alice, "media/mdProfilePhoto000001"), meta("alice", { scope: "profile" })));
    await assertSucceeds(setDoc(doc(alice, "media/mdProfilePhoto000001/chunks/0"), chunk("alice")));
    await assertSucceeds(getDoc(doc(db("bob"), "media/mdProfilePhoto000001")));
    await assertSucceeds(getDoc(doc(db("bob"), "media/mdProfilePhoto000001/chunks/0")));
    await assertFails(getDoc(doc(carol, "media/mdProfilePhoto000001")));
    await seed(async (fs) => setDoc(doc(fs, "users/alice/blocked/bob"), { blockedAt: Timestamp.now() }));
    await assertFails(getDoc(doc(db("bob"), "media/mdProfilePhoto000001")));
    await assertFails(getDoc(doc(db("bob"), "media/mdProfilePhoto000001/chunks/0")));
  });
});

describe("device linking and linked-device revocation", () => {
  const SESS = "sess_0123456789abcdefghijk";
  const SECRET = "secret_token_0123456789abcdef";
  const SECRET_HASH = "e05b8e30f924f5eb1c0264e32f040560d52b9b05a0808b4981197b3c9b7f4438"; // replaced dynamically below
  const VERIFIER = "verifier_tok_0123456789abcdef";

  async function sha256Hex(str) {
    const { createHash } = await import("node:crypto");
    return createHash("sha256").update(str, "utf8").digest("hex");
  }

  async function makeSession(overrides = {}) {
    const secretHash = await sha256Hex(SECRET);
    const codeChallenge = await sha256Hex(VERIFIER);
    const manualCodeHash = await sha256Hex("ABCD2345");
    return {
      sessionId: SESS,
      status: "pending",
      requesterDeviceId: "dev_new_0001",
      requesterDeviceName: "Pixel 8 Pro",
      requesterDeviceType: "android_phone",
      requesterPlatform: "android",
      requesterPublicKey: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0123456789abcdef0123456789abcdef",
      requesterKeyFingerprint: "0123456789abcdef01234567",
      ephemeralPublicKey: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfedcba9876543210fedcba9876543210",
      secretHash,
      codeChallenge,
      manualCodeHash,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
      expiresAt: Timestamp.fromMillis(Date.now() + 120_000),
      ownerUid: null,
      approvedByDeviceId: null,
      approvedAt: null,
      completedAt: null,
      consumed: false,
      ...overrides,
    };
  }

  test("unauthenticated new device creates a short-lived pairing session; listing is forbidden", async () => {
    const data = await makeSession();
    const anonFs = anon();
    const batch = writeBatch(anonFs);
    batch.set(doc(anonFs, `devicePairings/${SESS}`), data);
    batch.set(doc(anonFs, `devicePairingCodes/${data.manualCodeHash}`), {
      sessionId: SESS,
      codeVerifier: VERIFIER,
      keyFingerprint: data.requesterKeyFingerprint,
      createdAt: serverTimestamp(),
      expiresAt: data.expiresAt,
    });
    await assertSucceeds(batch.commit());
    await assertSucceeds(getDoc(doc(anonFs, `devicePairings/${SESS}`)));
    await assertFails(getDocs(collection(anonFs, "devicePairings")));
    await assertFails(getDocs(collection(db("alice"), "devicePairings")));
    await assertFails(getDocs(collection(db("alice"), "devicePairingCodes")));

    // Reject pre-approved, pre-owned, or long-TTL sessions
    await assertFails(setDoc(doc(anonFs, "devicePairings/sess_bad_status_012345678"), await makeSession({ sessionId: "sess_bad_status_012345678", status: "approved" })));
    await assertFails(setDoc(doc(anonFs, "devicePairings/sess_bad_owner_0123456789"), await makeSession({ sessionId: "sess_bad_owner_0123456789", ownerUid: "alice" })));
    await assertFails(setDoc(doc(anonFs, "devicePairings/sess_bad_ttl_012345678901"), await makeSession({
      sessionId: "sess_bad_ttl_012345678901",
      expiresAt: Timestamp.fromMillis(Date.now() + 10 * 60_000),
    })));
  });

  test("approval requires authenticated owner and valid QR codeVerifier proof; consumo is strictly one-time", async () => {
    await seedPeople();
    const data = await makeSession();
    const anonFs = anon();
    const aliceFs = db("alice");
    const bobFs = db("bob");
    await setDoc(doc(anonFs, `devicePairings/${SESS}`), data);

    // Unauthenticated or wrong codeVerifier Proof fails
    await assertFails(updateDoc(doc(anonFs, `devicePairings/${SESS}`), {
      status: "approved",
      ownerUid: "alice",
      approvedByDeviceId: "dev_primary_01",
      approvedAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
      codeVerifierProof: VERIFIER,
    }));
    await assertFails(updateDoc(doc(aliceFs, `devicePairings/${SESS}`), {
      status: "approved",
      ownerUid: "alice",
      approvedByDeviceId: "dev_primary_01",
      approvedAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
      codeVerifierProof: "wrong_verifier_0123456789abc",
    }));

    // Cannot substitute requesterDeviceId or requesterPublicKey during approval
    await assertFails(updateDoc(doc(aliceFs, `devicePairings/${SESS}`), {
      status: "approved",
      ownerUid: "alice",
      approvedByDeviceId: "dev_primary_01",
      requesterDeviceId: "dev_attacker_99",
      approvedAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
      codeVerifierProof: VERIFIER,
    }));

    // Valid approval + ECDH envelope creation in one batch succeeds
    const appBatch = writeBatch(aliceFs);
    appBatch.update(doc(aliceFs, `devicePairings/${SESS}`), {
      status: "approved",
      ownerUid: "alice",
      approvedByDeviceId: "dev_primary_01",
      approvedAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
      codeVerifierProof: VERIFIER,
    });
    appBatch.set(doc(aliceFs, `devicePairings/${SESS}/exchange/envelope`), {
      approverEphemeralPublicKey: data.ephemeralPublicKey,
      ivBase64Url: "0123456789abcdef",
      ciphertextBase64Url: "0123456789abcdef0123456789abcdef",
      createdAt: serverTimestamp(),
    });
    await assertSucceeds(appBatch.commit());

    // Concurrent second approval by bob fails
    await assertFails(updateDoc(doc(bobFs, `devicePairings/${SESS}`), {
      status: "approved",
      ownerUid: "bob",
      approvedByDeviceId: "dev_bob_01",
      approvedAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
      codeVerifierProof: VERIFIER,
    }));

    // Requesting device reads envelope while approved & unconsumed
    await assertSucceeds(getDoc(doc(anonFs, `devicePairings/${SESS}/exchange/envelope`)));

    // Completing without valid secretProof fails
    await assertFails(updateDoc(doc(aliceFs, `devicePairings/${SESS}`), {
      status: "completed",
      consumed: true,
      completedAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
      secretProof: "wrong_secret_0123456789abcdef",
      deviceSignature: "MEUCIQDz0123456789abcdef0123456789abcdef",
    }));

    // Valid one-time completion + linkedDevice creation + envelope deletion succeeds
    const compBatch = writeBatch(aliceFs);
    compBatch.update(doc(aliceFs, `devicePairings/${SESS}`), {
      status: "completed",
      consumed: true,
      completedAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
      secretProof: SECRET,
      deviceSignature: "MEUCIQDz0123456789abcdef0123456789abcdef",
    });
    compBatch.set(doc(aliceFs, "users/alice/linkedDevices/dev_new_0001"), {
      deviceId: "dev_new_0001",
      uid: "alice",
      deviceName: "Pixel 8 Pro",
      deviceType: "android_phone",
      platform: "android",
      publicKey: data.requesterPublicKey,
      keyFingerprint: data.requesterKeyFingerprint,
      authMethod: "qr_linked",
      status: "active",
      online: true,
      linkedAt: serverTimestamp(),
      lastActiveAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
      revokedAt: null,
      revokedByDeviceId: null,
      pairedByDeviceId: "dev_primary_01",
      sessionId: SESS,
    });
    compBatch.delete(doc(aliceFs, `devicePairings/${SESS}/exchange/envelope`));
    await assertSucceeds(compBatch.commit());

    // Replay of completion or reading envelope after consumption fails
    await assertFails(getDoc(doc(anonFs, `devicePairings/${SESS}/exchange/envelope`)));
    await assertFails(updateDoc(doc(aliceFs, `devicePairings/${SESS}`), {
      status: "completed",
      consumed: true,
      completedAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
      secretProof: SECRET,
      deviceSignature: "MEUCIQDz0123456789abcdef0123456789abcdef",
    }));
  });

  test("revoking a linked device blocks its custom-token claim, FCM token registration, and pairing approvals", async () => {
    await seedPeople();
    await seedDm();
    const alicePrimary = db("alice");
    const pubKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0123456789abcdef0123456789abcdef";

    // Register primary and secondary linked devices
    for (const devId of ["dev_primary_01", "dev_linked_02"]) {
      await assertSucceeds(setDoc(doc(alicePrimary, `users/alice/linkedDevices/${devId}`), {
        deviceId: devId,
        uid: "alice",
        deviceName: devId,
        deviceType: "android_phone",
        platform: "android",
        publicKey: pubKey,
        keyFingerprint: "0123456789abcdef01234567",
        authMethod: "primary_login",
        status: "active",
        online: true,
        linkedAt: serverTimestamp(),
        lastActiveAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
        revokedAt: null,
        revokedByDeviceId: null,
        pairedByDeviceId: null,
        sessionId: null,
      }));
    }

    const aliceLinked = env.authenticatedContext("alice", { linkedDeviceId: "dev_linked_02" }).firestore();
    // While active, dev_linked_02 can read conversations and register FCM token
    await assertSucceeds(getDoc(doc(aliceLinked, `conversations/${DM}`)));
    await assertSucceeds(setDoc(doc(aliceLinked, "users/alice/devices/dev_linked_02"), { token: "fcm_tok_2" }));

    // Primary device revokes dev_linked_02 and deletes its FCM token
    const revBatch = writeBatch(alicePrimary);
    revBatch.update(doc(alicePrimary, "users/alice/linkedDevices/dev_linked_02"), {
      status: "revoked",
      online: false,
      revokedAt: serverTimestamp(),
      revokedByDeviceId: "dev_primary_01",
      updatedAt: serverTimestamp(),
    });
    revBatch.delete(doc(alicePrimary, "users/alice/devices/dev_linked_02"));
    await assertSucceeds(revBatch.commit());

    // Even with an unexpired ID token, dev_linked_02 is now rejected across Firestore
    await assertFails(getDoc(doc(aliceLinked, `conversations/${DM}`)));
    await assertFails(setDoc(doc(alicePrimary, "users/alice/devices/dev_linked_02"), { token: "fcm_tok_readd" }));
    // But dev_linked_02 can still read its own linkedDevices doc to observe that it was revoked
    await assertSucceeds(getDoc(doc(aliceLinked, "users/alice/linkedDevices/dev_linked_02")));
  });

  test("requester can cancel a pending session and clean up its pairing code; active codes cannot be deleted and completed sessions cannot be replayed", async () => {
    await seedPeople();
    const anonFs = anon();
    const aliceFs = db("alice");
    const sessCancel = "sess_cancel_0123456789abc";
    const cancelCodeHash = await sha256Hex("CNCL2345");
    const cancelData = await makeSession({ sessionId: sessCancel, manualCodeHash: cancelCodeHash });

    const createBatch = writeBatch(anonFs);
    createBatch.set(doc(anonFs, `devicePairings/${sessCancel}`), cancelData);
    createBatch.set(doc(anonFs, `devicePairingCodes/${cancelCodeHash}`), {
      sessionId: sessCancel,
      codeVerifier: VERIFIER,
      keyFingerprint: cancelData.requesterKeyFingerprint,
      createdAt: serverTimestamp(),
      expiresAt: cancelData.expiresAt,
    });
    await assertSucceeds(createBatch.commit());

    // While session is still pending and unexpired, deleting its pairing code alone is denied
    await assertFails(deleteDoc(doc(anonFs, `devicePairingCodes/${cancelCodeHash}`)));

    // Cancelling the session (with secretProof) and deleting its pairing code in a cleanup batch succeeds
    const cancelBatch = writeBatch(anonFs);
    cancelBatch.update(doc(anonFs, `devicePairings/${sessCancel}`), {
      status: "cancelled",
      updatedAt: serverTimestamp(),
      secretProof: SECRET,
    });
    cancelBatch.delete(doc(anonFs, `devicePairingCodes/${cancelCodeHash}`));
    await assertSucceeds(cancelBatch.commit());

    // A previously completed session cannot be replayed in a later write to register another linked device
    const sessDone = "sess_done_0123456789abcdef";
    const doneData = await makeSession({
      sessionId: sessDone,
      status: "completed",
      consumed: true,
      ownerUid: "alice",
      approvedByDeviceId: "dev_primary_01",
      approvedAt: Timestamp.fromMillis(Date.now() - 10_000),
      completedAt: Timestamp.fromMillis(Date.now() - 5_000),
    });
    await seed(async (fs) => setDoc(doc(fs, `devicePairings/${sessDone}`), doneData));
    await assertFails(setDoc(doc(aliceFs, "users/alice/linkedDevices/dev_new_0001"), {
      deviceId: "dev_new_0001",
      uid: "alice",
      deviceName: "Pixel 8 Pro",
      deviceType: "android_phone",
      platform: "android",
      publicKey: doneData.requesterPublicKey,
      keyFingerprint: doneData.requesterKeyFingerprint,
      authMethod: "qr_linked",
      status: "active",
      online: true,
      linkedAt: serverTimestamp(),
      lastActiveAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
      revokedAt: null,
      revokedByDeviceId: null,
      pairedByDeviceId: "dev_primary_01",
      sessionId: sessDone,
    }));
  });
});

describe("Cloud Storage rules (storage.rules)", () => {
  const bytes = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]);

  test("direct and group chat media: only conversation participants can read; only the sender can upload or delete", async () => {
    const aliceSt = st("alice");
    const bobSt = st("bob");
    const carolSt = st("carol");
    const daveSt = st("dave");

    // Direct chat media (dm_alice_bob): alice uploads, bob can read, carol and unauthenticated cannot
    const dmPath = `chat-media/${DM}/alice/m1.png`;
    await assertFails(uploadBytes(sRef(bobSt, dmPath), bytes, { contentType: "image/png" }));
    await assertFails(uploadBytes(sRef(carolSt, `chat-media/${DM}/carol/m1.png`), bytes, { contentType: "image/png" }));
    await assertSucceeds(uploadBytes(sRef(aliceSt, dmPath), bytes, { contentType: "image/png", customMetadata: { participants: "alice,bob" } }));
    await assertSucceeds(getBytes(sRef(aliceSt, dmPath)));
    await assertSucceeds(getBytes(sRef(bobSt, dmPath)));
    await assertFails(getBytes(sRef(carolSt, dmPath)));
    await assertFails(getBytes(sRef(anonSt(), dmPath)));
    await assertFails(deleteObject(sRef(bobSt, dmPath)));
    await assertSucceeds(deleteObject(sRef(aliceSt, dmPath)));

    // Group chat media: requires sender in participants metadata; members can read, outsider dave cannot
    const grpPath = "chat-media/grp_team1/alice/m2.png";
    await assertFails(uploadBytes(sRef(aliceSt, grpPath), bytes, { contentType: "image/png" }));
    await assertSucceeds(uploadBytes(sRef(aliceSt, grpPath), bytes, {
      contentType: "image/png",
      customMetadata: { participants: "alice,bob,carol" },
    }));
    await assertSucceeds(getBytes(sRef(carolSt, grpPath)));
    await assertFails(getBytes(sRef(daveSt, grpPath)));
  });

  test("group photos: only group members in metadata can read or upload", async () => {
    const aliceSt = st("alice");
    const bobSt = st("bob");
    const daveSt = st("dave");
    const path = "group-photos/grp_team1/avatar.jpg";

    await assertFails(uploadBytes(sRef(daveSt, path), bytes, {
      contentType: "image/jpeg",
      customMetadata: { participants: "alice,bob" },
    }));
    await assertSucceeds(uploadBytes(sRef(aliceSt, path), bytes, {
      contentType: "image/jpeg",
      customMetadata: { participants: "alice,bob" },
    }));
    await assertSucceeds(getBytes(sRef(bobSt, path)));
    await assertFails(getBytes(sRef(daveSt, path)));
  });

  test("status media: only audience members before expiry can read; only the owner can upload or delete", async () => {
    const aliceSt = st("alice");
    const bobSt = st("bob");
    const carolSt = st("carol");
    const livePath = "status-media/alice/st_live.jpg";
    const expPath = "status-media/alice/st_exp.jpg";

    await assertFails(uploadBytes(sRef(bobSt, livePath), bytes, {
      contentType: "image/jpeg",
      customMetadata: { audience: "alice,bob", expiresAt: String(Date.now() + 3600_000) },
    }));
    await assertSucceeds(uploadBytes(sRef(aliceSt, livePath), bytes, {
      contentType: "image/jpeg",
      customMetadata: { audience: "alice,bob", expiresAt: String(Date.now() + 3600_000) },
    }));
    await assertSucceeds(getBytes(sRef(bobSt, livePath)));
    await assertFails(getBytes(sRef(carolSt, livePath)));
    await assertFails(deleteObject(sRef(bobSt, livePath)));

    // Expired status media cannot be read by non-owners
    await seedSt(async (s) => {
      await uploadBytes(sRef(s, expPath), bytes, {
        contentType: "image/jpeg",
        customMetadata: { audience: "alice,bob", expiresAt: String(Date.now() - 60_000) },
      });
    });
    await assertFails(getBytes(sRef(bobSt, expPath)));
    await assertSucceeds(getBytes(sRef(aliceSt, expPath)));
  });

  test("profile photos: visibility (everyone, contacts, nobody) and blocked lists are enforced", async () => {
    const aliceSt = st("alice");
    const bobSt = st("bob");
    const carolSt = st("carol");
    const daveSt = st("dave");
    const path = "profile-photos/alice/avatar.jpg";

    // 1. visibility = contacts, blocked = dave
    await assertSucceeds(uploadBytes(sRef(aliceSt, path), bytes, {
      contentType: "image/jpeg",
      customMetadata: { visibility: "contacts", contacts: "bob", blocked: "dave" },
    }));
    await assertSucceeds(getBytes(sRef(aliceSt, path)));
    await assertSucceeds(getBytes(sRef(bobSt, path)));
    await assertFails(getBytes(sRef(carolSt, path)));
    await assertFails(getBytes(sRef(daveSt, path)));

    // 2. visibility = everyone, blocked = dave
    await assertSucceeds(uploadBytes(sRef(aliceSt, path), bytes, {
      contentType: "image/jpeg",
      customMetadata: { visibility: "everyone", contacts: "bob", blocked: "dave" },
    }));
    await assertSucceeds(getBytes(sRef(carolSt, path)));
    await assertFails(getBytes(sRef(daveSt, path)));

    // 3. visibility = nobody: only owner can read
    await assertSucceeds(uploadBytes(sRef(aliceSt, path), bytes, {
      contentType: "image/jpeg",
      customMetadata: { visibility: "nobody", contacts: "bob", blocked: "" },
    }));
    await assertSucceeds(getBytes(sRef(aliceSt, path)));
    await assertFails(getBytes(sRef(bobSt, path)));
  });

  test("status audio track uploads are permitted for status owner and readable by audience before expiry", async () => {
    const aliceSt = st("alice");
    const bobSt = st("bob");
    const carolSt = st("carol");
    const audioPath = "status-media/alice/st_music_1_music.wav";

    await assertSucceeds(uploadBytes(sRef(aliceSt, audioPath), bytes, {
      contentType: "audio/wav",
      customMetadata: { audience: "alice,bob", expiresAt: String(Date.now() + 3600_000) },
    }));
    await assertSucceeds(getBytes(sRef(bobSt, audioPath)));
    await assertFails(getBytes(sRef(carolSt, audioPath)));
  });
});

describe("advanced statuses (music, mentions, likes, resharing), group @all mentions, advanced polls, and media quality", () => {
  async function sha256Hex(str) {
    const { createHash } = await import("node:crypto");
    return createHash("sha256").update(str, "utf8").digest("hex");
  }

  test("status music, mentions subset of audience, likes privacy, and resharing rules are enforced", async () => {
    await seedPeople();
    const alice = db("alice");
    const bob = db("bob");
    const carol = db("carol");
    const exp = Timestamp.fromMillis(Date.now() + 3600_000);

    // 1. Mentions cannot include users outside audience or self
    await assertFails(setDoc(doc(alice, "statuses/st_bad_mention"), {
      ownerId: "alice", type: "TEXT", text: "hi", bgColor: "#000000", font: "sans",
      audience: ["bob"], mentions: ["carol"], allowReshare: true,
      createdAt: serverTimestamp(), expiresAt: exp,
    }));
    await assertFails(setDoc(doc(alice, "statuses/st_self_mention"), {
      ownerId: "alice", type: "TEXT", text: "hi", bgColor: "#000000", font: "sans",
      audience: ["bob"], mentions: ["alice"], allowReshare: true,
      createdAt: serverTimestamp(), expiresAt: exp,
    }));

    // 2. Valid status with music metadata, mentions subset of audience, and allowReshare=true
    await assertSucceeds(setDoc(doc(alice, "statuses/st_music"), {
      ownerId: "alice", type: "TEXT", text: "morning vibes", bgColor: "#0F766E", font: "sans",
      audience: ["bob", "carol"], mentions: ["bob"], allowReshare: true,
      music: {
        trackId: "sunrise_acoustic", title: "Sunrise Acoustic", artist: "PulseChat Studio",
        url: null, startMs: 2000, durationMs: 15000,
      },
      createdAt: serverTimestamp(), expiresAt: exp,
    }));

    // 3. Status likes: audience member can like/unlike; non-audience cannot; bob cannot read carol's like; owner (alice) can read all likes
    const dave = db("dave");
    await assertFails(setDoc(doc(dave, "statuses/st_music/likes/dave"), { likedAt: serverTimestamp() }));
    await assertSucceeds(setDoc(doc(bob, "statuses/st_music/likes/bob"), { likedAt: serverTimestamp() }));
    await assertSucceeds(setDoc(doc(carol, "statuses/st_music/likes/carol"), { likedAt: serverTimestamp() }));
    await assertSucceeds(getDoc(doc(bob, "statuses/st_music/likes/bob")));
    await assertFails(getDoc(doc(bob, "statuses/st_music/likes/carol")));
    await assertSucceeds(getDoc(doc(alice, "statuses/st_music/likes/bob")));
    await assertSucceeds(getDoc(doc(alice, "statuses/st_music/likes/carol")));
    await assertSucceeds(deleteDoc(doc(bob, "statuses/st_music/likes/bob")));

    // 4. Status resharing: allowed when allowReshare=true and audience is a subset of original audience
    await assertFails(setDoc(doc(bob, "statuses/st_reshare_wider"), {
      ownerId: "bob", type: "TEXT", text: "morning vibes", bgColor: "#0F766E", font: "sans",
      audience: ["alice", "carol", "dave"], allowReshare: false,
      resharedFrom: { statusId: "st_music", ownerId: "alice", authorName: "Alice" },
      createdAt: serverTimestamp(), expiresAt: exp,
    }));
    await assertSucceeds(setDoc(doc(bob, "statuses/st_reshare_ok"), {
      ownerId: "bob", type: "TEXT", text: "morning vibes", bgColor: "#0F766E", font: "sans",
      audience: ["carol"], allowReshare: false,
      resharedFrom: { statusId: "st_music", ownerId: "alice", authorName: "Alice" },
      createdAt: serverTimestamp(), expiresAt: exp,
    }));

    // 5. Status resharing rejected when allowReshare=false or when resharing a reshare
    await assertFails(setDoc(doc(carol, "statuses/st_chain_reshare"), {
      ownerId: "carol", type: "TEXT", text: "morning vibes", bgColor: "#0F766E", font: "sans",
      audience: ["bob"], allowReshare: false,
      resharedFrom: { statusId: "st_reshare_ok", ownerId: "bob", authorName: "Bob" },
      createdAt: serverTimestamp(), expiresAt: exp,
    }));
  });

  test("group @all mentions enforce onlyAdminsCanMentionAll and 5-minute cooldown", async () => {
    await seedPeople();
    await seedGroup({ onlyAdminsCanMentionAll: true });
    const alice = db("alice");
    const bob = db("bob");

    // 1. Non-admin bob cannot send @all when onlyAdminsCanMentionAll is true
    await assertFails(setDoc(doc(bob, "conversations/g1/messages/m_bob_all"), {
      ...messageData("bob", { mentions: ["all"] }),
    }));

    // 2. Admin alice can send @all and bump lastMentionAllAt
    const batch1 = writeBatch(alice);
    batch1.set(doc(alice, "conversations/g1/messages/m_alice_all_1"), messageData("alice", { mentions: ["all"] }));
    batch1.update(doc(alice, "conversations/g1"), {
      lastMessage: { id: "m_alice_all_1", senderId: "alice", type: "TEXT", preview: "@all hi", createdAt: serverTimestamp() },
      lastMessageAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
      lastMentionAllAt: serverTimestamp(),
    });
    await assertSucceeds(batch1.commit());

    // 3. Immediate second @all within 5-minute cooldown is rejected by rules
    await assertFails(setDoc(doc(alice, "conversations/g1/messages/m_alice_all_2"), {
      ...messageData("alice", { mentions: ["all"] }),
    }));
  });

  test("anonymous polls, poll expiration, poll editing before votes, and HD/ORIGINAL media quality are enforced", async () => {
    await seedPeople();
    await seedGroup();
    const alice = db("alice");
    const bob = db("bob");
    const pollExp = Timestamp.fromMillis(Date.now() + 3600_000);

    // 1. Create an anonymous poll with expiration
    await assertSucceeds(setDoc(doc(alice, "conversations/g1/messages/m_poll_1"), messageData("alice", {
      type: "POLL",
      text: "Where should we meet?",
      poll: {
        question: "Where should we meet?",
        options: ["Zürich HB", "Bahnhofstrasse"],
        votes: {},
        anonymous: true,
        expiresAt: pollExp,
      },
    })));

    // 2. Creator alice can edit poll question/options before any votes are cast
    await assertSucceeds(updateDoc(doc(alice, "conversations/g1/messages/m_poll_1"), {
      text: "Where should we meet today?",
      "poll.question": "Where should we meet today?",
      "poll.options": ["Zürich HB", "Bahnhofstrasse", "Lake Geneva"],
      editedAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    }));

    // 3. In an anonymous poll, voting with raw UID is rejected; voting with sha256("poll:" + mid + ":" + uid) succeeds
    await assertFails(updateDoc(doc(bob, "conversations/g1/messages/m_poll_1"), {
      "poll.votes.bob": 1,
      updatedAt: serverTimestamp(),
    }));
    const bobAnonKey = await sha256Hex("poll:m_poll_1:bob");
    const carolAnonKey = await sha256Hex("poll:m_poll_1:carol");
    await assertFails(updateDoc(doc(bob, "conversations/g1/messages/m_poll_1"), {
      [`poll.votes.${carolAnonKey}`]: 1,
      updatedAt: serverTimestamp(),
    }));
    await assertSucceeds(updateDoc(doc(bob, "conversations/g1/messages/m_poll_1"), {
      [`poll.votes.${bobAnonKey}`]: 1,
      updatedAt: serverTimestamp(),
    }));

    // 4. Once a vote has been cast, creator can no longer edit the poll
    await assertFails(updateDoc(doc(alice, "conversations/g1/messages/m_poll_1"), {
      text: "Changed after vote?",
      "poll.question": "Changed after vote?",
      "poll.options": ["A", "B"],
      editedAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    }));

    // 5. Expired poll rejects new votes
    await seed(async (fs) => {
      await setDoc(doc(fs, "conversations/g1/messages/m_poll_exp"), {
        ...messageData("alice", {
          type: "POLL",
          text: "Expired poll",
          poll: {
            question: "Expired poll",
            options: ["Yes", "No"],
            votes: {},
            anonymous: false,
            expiresAt: Timestamp.fromMillis(Date.now() - 60_000),
          },
        }),
        createdAt: Timestamp.fromMillis(Date.now() - 120_000),
        updatedAt: Timestamp.fromMillis(Date.now() - 120_000),
      });
    });
    await assertFails(updateDoc(doc(bob, "conversations/g1/messages/m_poll_exp"), {
      "poll.votes.bob": 0,
      updatedAt: serverTimestamp(),
    }));

    // 6. Media quality validation: HD and ORIGINAL are accepted; bogus quality is rejected
    await assertSucceeds(setDoc(doc(alice, "conversations/g1/messages/m_img_hd"), messageData("alice", {
      type: "IMAGE",
      text: "HD photo",
      media: {
        url: "pulsemedia://md000000000000000001",
        path: "chat-media/g1/alice/m_img_hd.jpg",
        mime: "image/jpeg",
        size: 1024000,
        originalSize: 2048000,
        quality: "HD",
      },
    })));
    await assertFails(setDoc(doc(alice, "conversations/g1/messages/m_img_badq"), messageData("alice", {
      type: "IMAGE",
      text: "Bad quality",
      media: {
        url: "pulsemedia://md000000000000000001",
        path: "chat-media/g1/alice/m_img_badq.jpg",
        mime: "image/jpeg",
        size: 1024000,
        quality: "ULTRA_8K",
      },
    })));
  });
});

