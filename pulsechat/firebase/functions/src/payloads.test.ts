import { test } from "node:test";
import assert from "node:assert/strict";
import * as crypto from "node:crypto";
import {
  callPayload,
  deviceLinkedPayload,
  deviceRevokedPayload,
  isStaleTokenError,
  messagePayload,
  messageRecipients,
  PairingSessionDoc,
  sealCustomTokenEnvelope,
  sha256Hex,
  shouldNotify,
  validatePairingExchange,
  verifyEcdsaChallenge,
  verifySecretProof,
} from "./payloads";

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
  assert.deepEqual(deviceLinkedPayload("d1", "Pixel 8"), { type: "device_linked", deviceId: "d1", deviceName: "Pixel 8" });
  assert.deepEqual(deviceRevokedPayload("d1"), { type: "device_revoked", deviceId: "d1" });
  assert.equal(isStaleTokenError("messaging/registration-token-not-registered"), true);
  assert.equal(isStaleTokenError("messaging/internal-error"), false);
});

test("device pairing secret proof, ECDSA P-256 signature, and exchange validation", () => {
  const kp = crypto.generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const eph = crypto.generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const pubSpki = (kp.publicKey.export({ format: "der", type: "spki" }) as Buffer).toString("base64url");
  const ephSpki = (eph.publicKey.export({ format: "der", type: "spki" }) as Buffer).toString("base64url");

  const sessionId = "sess_1234567890abcdefghij";
  const secret = "super_secret_random_token_123456";
  const secretHash = sha256Hex(secret);
  const codeChallenge = sha256Hex("verifier_token_1234567890");
  const deviceId = "device_new_001";
  const now = 1_700_000_000_000;

  assert.equal(verifySecretProof(secret, secretHash), true);
  assert.equal(verifySecretProof("wrong_secret", secretHash), false);

  const transcript = Buffer.from(`pulsechat-exchange:v1:${sessionId}:${secretHash}:${deviceId}:${now}`, "utf8");
  const signature = crypto.sign("sha256", transcript, kp.privateKey).toString("base64url");

  assert.equal(verifyEcdsaChallenge(pubSpki, sessionId, secretHash, deviceId, now, signature), true);
  assert.equal(verifyEcdsaChallenge(pubSpki, sessionId, secretHash, "other_device", now, signature), false);

  const session: PairingSessionDoc = {
    sessionId,
    status: "approved",
    consumed: false,
    requesterDeviceId: deviceId,
    requesterDeviceName: "Pixel 8",
    requesterDeviceType: "android_phone",
    requesterPlatform: "android",
    requesterPublicKey: pubSpki,
    requesterKeyFingerprint: sha256Hex(pubSpki).slice(0, 24),
    ephemeralPublicKey: ephSpki,
    secretHash,
    codeChallenge,
    ownerUid: "alice",
    approvedByDeviceId: "device_primary_001",
    expiresAtMs: now + 60_000,
  };

  assert.deepEqual(
    validatePairingExchange(
      session,
      { sessionId, sessionSecret: secret, deviceId, timestampMs: now, signature },
      false,
      now + 1_000,
    ),
    { ok: true },
  );

  // Reject already consumed, expired, revoked approver, or wrong secret
  assert.deepEqual(
    validatePairingExchange(
      { ...session, consumed: true },
      { sessionId, sessionSecret: secret, deviceId, timestampMs: now, signature },
      false,
      now,
    ),
    { ok: false, reason: "already_consumed" },
  );
  assert.deepEqual(
    validatePairingExchange(
      session,
      { sessionId, sessionSecret: secret, deviceId, timestampMs: now, signature },
      true,
      now,
    ),
    { ok: false, reason: "approver_revoked" },
  );
  assert.deepEqual(
    validatePairingExchange(
      session,
      { sessionId, sessionSecret: secret, deviceId, timestampMs: now, signature },
      false,
      now + 120_000,
    ),
    { ok: false, reason: "expired" },
  );
  assert.deepEqual(
    validatePairingExchange(
      session,
      { sessionId, sessionSecret: "wrong", deviceId, timestampMs: now, signature },
      false,
      now,
    ),
    { ok: false, reason: "invalid_secret" },
  );

  // Seal ECDH P-256 + HKDF-SHA256 + AES-256-GCM custom token envelope and decrypt with requester ephemeral private key
  const plainJson = JSON.stringify({ type: "customToken", uid: "alice", customToken: "tok_abc" });
  const sealed = sealCustomTokenEnvelope(ephSpki, sessionId, "alice", deviceId, secretHash, codeChallenge, plainJson);
  const approverPub = crypto.createPublicKey({
    key: Buffer.from(sealed.approverEphemeralPublicKey, "base64url"),
    format: "der",
    type: "spki",
  });
  const shared = crypto.diffieHellman({ privateKey: eph.privateKey, publicKey: approverPub });
  const info = Buffer.from(`pulsechat-pair-v1:${secretHash}:${codeChallenge}`, "utf8");
  const aesKey = Buffer.from(crypto.hkdfSync("sha256", shared, Buffer.from(sessionId, "utf8"), info, 32));
  const rawCipher = Buffer.from(sealed.ciphertextBase64Url, "base64url");
  const tag = rawCipher.subarray(rawCipher.length - 16);
  const body = rawCipher.subarray(0, rawCipher.length - 16);
  const decipher = crypto.createDecipheriv("aes-256-gcm", aesKey, Buffer.from(sealed.ivBase64Url, "base64url"));
  decipher.setAAD(Buffer.from(`${sessionId}|alice|${deviceId}|${secretHash}`, "utf8"));
  decipher.setAuthTag(tag);
  const decrypted = Buffer.concat([decipher.update(body), decipher.final()]).toString("utf8");
  assert.equal(decrypted, plainJson);
});
