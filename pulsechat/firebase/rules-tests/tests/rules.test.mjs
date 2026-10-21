import { readFileSync } from "node:fs";
import { after, before, beforeEach, describe, test } from "node:test";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import {
  arrayRemove, arrayUnion, Bytes, collection, deleteDoc, deleteField, doc, getDoc, getDocs, query, serverTimestamp, setDoc, Timestamp,
  updateDoc, where, writeBatch,
} from "firebase/firestore";

let env;
const rules = readFileSync(new URL("../../../firestore.rules", import.meta.url), "utf8");

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-pulsechat",
    firestore: { rules, host: "127.0.0.1", port: 8080 },
  });
});
after(async () => { await env?.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

const db = (uid) => env.authenticatedContext(uid).firestore();
const anon = () => env.unauthenticatedContext().firestore();
const seed = (fn) => env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore()));

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
    const edit = { text: "edited", editedAt: serverTimestamp(), updatedAt: serverTimestamp() };
    await assertFails(updateDoc(doc(db("bob"), `conversations/${DM}/messages/fresh`), edit));
    await assertSucceeds(updateDoc(doc(db("alice"), `conversations/${DM}/messages/fresh`), edit));
    await assertFails(updateDoc(doc(db("alice"), `conversations/${DM}/messages/old`), edit));
  });

  test("delete for everyone must remove the content and is author-only", async () => {
    await seedPeople();
    await seedDm();
    await seedMessage(DM, "m1", "alice", 60_000, { media: { url: "https://x", path: "chat-media/x" } });
    const wipe = {
      deletedForEveryone: true, text: "", media: deleteField(), replyTo: deleteField(), poll: deleteField(), contact: deleteField(),
      location: deleteField(), mentions: [], reactions: {}, updatedAt: serverTimestamp(),
    };
    await assertFails(updateDoc(doc(db("bob"), `conversations/${DM}/messages/m1`), wipe));
    await assertFails(updateDoc(doc(db("alice"), `conversations/${DM}/messages/m1`), { ...wipe, text: "still here" }));
    await assertSucceeds(updateDoc(doc(db("alice"), `conversations/${DM}/messages/m1`), wipe));
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
});
