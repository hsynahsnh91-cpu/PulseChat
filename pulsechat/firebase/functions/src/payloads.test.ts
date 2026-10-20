import { test } from "node:test";
import assert from "node:assert/strict";
import { callPayload, isStaleTokenError, messagePayload, messageRecipients, shouldNotify } from "./payloads";

const base = { uid: "bob", blockedSender: false, mutedUntil: 0, tokens: ["t1"] };

test("sender never receives its own push", () => {
  assert.deepEqual(messageRecipients(["alice", "bob", "bob", "carol"], "alice"), ["bob", "carol"]);
});

test("blocked senders and system messages do not notify", () => {
  assert.equal(shouldNotify({ senderId: "alice", type: "TEXT" }, { ...base, blockedSender: true }, 10), false);
  assert.equal(shouldNotify({ senderId: "alice", type: "SYSTEM" }, base, 10), false);
  assert.equal(shouldNotify({ senderId: "alice", type: "TEXT" }, { ...base, tokens: [] }, 10), false);
});

test("muted chats notify only on mention", () => {
  const muted = { ...base, mutedUntil: 1_000 };
  assert.equal(shouldNotify({ senderId: "alice", type: "TEXT" }, muted, 10), false);
  assert.equal(shouldNotify({ senderId: "alice", type: "TEXT", mentions: ["bob"] }, muted, 10), true);
  assert.equal(shouldNotify({ senderId: "alice", type: "TEXT" }, muted, 2_000), true);
});

test("payloads carry IDs only", () => {
  assert.deepEqual(messagePayload("c1", "m1"), { type: "message", conversationId: "c1", messageId: "m1" });
  assert.deepEqual(callPayload("k1"), { type: "call", callId: "k1" });
  assert.equal(isStaleTokenError("messaging/registration-token-not-registered"), true);
  assert.equal(isStaleTokenError("messaging/internal-error"), false);
});
