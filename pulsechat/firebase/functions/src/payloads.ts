/**
 * Pure helpers (unit-tested) deciding who gets a push, what it contains, and verifying
 * cryptographic proofs and state transitions for the Device Linking System.
 * Payloads are data-only and carry IDs, never message content: the app fetches the
 * content with the recipient's own credentials, so Firestore rules still apply.
 */
import * as crypto from "node:crypto";

export interface MessageDoc {
  senderId: string;
  type: string;
  mentions?: string[];
}

export interface RecipientState {
  uid: string;
  blockedSender: boolean;
  mutedUntil: number;
  tokens: string[];
}

export interface PairingSessionDoc {
  sessionId: string;
  status: string;
  consumed: boolean;
  requesterDeviceId: string;
  requesterDeviceName: string;
  requesterDeviceType: string;
  requesterPlatform: string;
  requesterPublicKey: string;
  requesterKeyFingerprint: string;
  ephemeralPublicKey: string;
  secretHash: string;
  codeChallenge: string;
  ownerUid: string | null;
  approvedByDeviceId: string | null;
  expiresAtMs: number;
}

export interface ExchangeRequest {
  sessionId: string;
  sessionSecret: string;
  deviceId: string;
  timestampMs: number;
  signature: string;
}

export function messageRecipients(participants: string[], senderId: string): string[] {
  return [...new Set(participants)].filter((uid) => uid !== senderId);
}

/** Muted chats stay silent unless the recipient is @mentioned; blocked senders never notify. */
export function shouldNotify(msg: MessageDoc, recipient: RecipientState, now: number): boolean {
  if (msg.type === "SYSTEM") return false;
  if (recipient.blockedSender) return false;
  if (recipient.tokens.length === 0) return false;
  const mentioned = (msg.mentions ?? []).includes(recipient.uid);
  return recipient.mutedUntil <= now || mentioned;
}

export function messagePayload(conversationId: string, messageId: string): Record<string, string> {
  return { type: "message", conversationId, messageId };
}

export function callPayload(callId: string): Record<string, string> {
  return { type: "call", callId };
}

export function callEndedPayload(callId: string): Record<string, string> {
  return { type: "call_ended", callId };
}

export function deviceLinkedPayload(deviceId: string, deviceName: string): Record<string, string> {
  return { type: "device_linked", deviceId, deviceName: deviceName.slice(0, 64) };
}

export function deviceRevokedPayload(deviceId: string): Record<string, string> {
  return { type: "device_revoked", deviceId };
}

const STALE_TOKEN_ERRORS = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
  "messaging/invalid-argument",
]);

export function isStaleTokenError(code: string | undefined): boolean {
  return code !== undefined && STALE_TOKEN_ERRORS.has(code);
}

// ------------------------------------------------------------------ Device Linking Crypto & Validation

export function sha256Hex(input: string): string {
  return crypto.createHash("sha256").update(input, "utf8").digest("hex");
}

export function verifySecretProof(secret: string, expectedHashHex: string): boolean {
  if (!secret || typeof secret !== "string" || !/^[0-9a-f]{64}$/i.test(expectedHashHex)) return false;
  const actual = Buffer.from(sha256Hex(secret), "hex");
  const expected = Buffer.from(expectedHashHex.toLowerCase(), "hex");
  return actual.length === expected.length && crypto.timingSafeEqual(actual, expected);
}

export function verifyEcdsaChallenge(
  publicKeySpkiBase64Url: string,
  sessionId: string,
  secretHash: string,
  deviceId: string,
  timestampMs: number,
  signatureBase64Url: string,
): boolean {
  try {
    const spkiDer = Buffer.from(publicKeySpkiBase64Url, "base64url");
    const pubKey = crypto.createPublicKey({ key: spkiDer, format: "der", type: "spki" });
    const transcript = Buffer.from(
      `pulsechat-exchange:v1:${sessionId}:${secretHash.toLowerCase()}:${deviceId}:${timestampMs}`,
      "utf8",
    );
    const sigDer = Buffer.from(signatureBase64Url, "base64url");
    return crypto.verify("sha256", transcript, pubKey, sigDer);
  } catch {
    return false;
  }
}

export function sealCustomTokenEnvelope(
  requesterEphPubSpkiBase64Url: string,
  sessionId: string,
  ownerUid: string,
  requesterDeviceId: string,
  secretHash: string,
  codeChallenge: string,
  plaintextJson: string,
): { approverEphemeralPublicKey: string; ivBase64Url: string; ciphertextBase64Url: string } {
  const peerSpki = Buffer.from(requesterEphPubSpkiBase64Url, "base64url");
  const peerKey = crypto.createPublicKey({ key: peerSpki, format: "der", type: "spki" });
  const eph = crypto.generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const shared = crypto.diffieHellman({ privateKey: eph.privateKey, publicKey: peerKey });
  const info = Buffer.from(`pulsechat-pair-v1:${secretHash.toLowerCase()}:${codeChallenge.toLowerCase()}`, "utf8");
  const aesKey = Buffer.from(crypto.hkdfSync("sha256", shared, Buffer.from(sessionId, "utf8"), info, 32));
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", aesKey, iv);
  const aad = Buffer.from(`${sessionId}|${ownerUid}|${requesterDeviceId}|${secretHash.toLowerCase()}`, "utf8");
  cipher.setAAD(aad);
  const encrypted = Buffer.concat([cipher.update(plaintextJson, "utf8"), cipher.final(), cipher.getAuthTag()]);
  const approverSpki = eph.publicKey.export({ format: "der", type: "spki" }) as Buffer;
  return {
    approverEphemeralPublicKey: approverSpki.toString("base64url"),
    ivBase64Url: iv.toString("base64url"),
    ciphertextBase64Url: encrypted.toString("base64url"),
  };
}

export function validatePairingExchange(
  session: PairingSessionDoc | undefined,
  req: ExchangeRequest,
  approverRevoked: boolean,
  nowMs: number,
): { ok: true } | { ok: false; reason: string } {
  if (!session) return { ok: false, reason: "not_found" };
  if (session.consumed || session.status === "completed") return { ok: false, reason: "already_consumed" };
  if (session.status !== "approved") return { ok: false, reason: `invalid_status_${session.status}` };
  if (!session.ownerUid || !session.approvedByDeviceId) return { ok: false, reason: "missing_owner" };
  if (approverRevoked) return { ok: false, reason: "approver_revoked" };
  if (nowMs >= session.expiresAtMs) return { ok: false, reason: "expired" };
  if (session.requesterDeviceId !== req.deviceId) return { ok: false, reason: "device_mismatch" };
  if (Math.abs(nowMs - req.timestampMs) > 5 * 60_000) return { ok: false, reason: "timestamp_skew" };
  if (!verifySecretProof(req.sessionSecret, session.secretHash)) return { ok: false, reason: "invalid_secret" };
  if (
    !verifyEcdsaChallenge(
      session.requesterPublicKey,
      session.sessionId,
      session.secretHash,
      req.deviceId,
      req.timestampMs,
      req.signature,
    )
  ) {
    return { ok: false, reason: "invalid_signature" };
  }
  return { ok: true };
}
