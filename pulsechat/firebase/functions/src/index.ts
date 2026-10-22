/**
 * PulseChat Cloud Functions (2nd gen).
 *
 * - onMessageCreated: data-only FCM push to the other participants (skips blocked senders,
 *   skips revoked linked devices, respects per-chat mute except @mentions) and prunes stale tokens.
 * - onCallCreated / onCallUpdated: high-priority call invitations and cancellations.
 * - onDevicePairingApproved: mints a Firebase Admin SDK custom token bound to the approved device
 *   and seals it with ECDH P-256 + HKDF-SHA256 + AES-256-GCM to the requesting device's ephemeral key.
 * - exchangeDevicePairing: HTTPS endpoint that verifies secret proof + ECDSA P-256 signature,
 *   atomically consumes the pairing session once, registers the linked device, and returns a custom token.
 * - onLinkedDeviceUpdated: removes FCM push tokens when a linked device is revoked and notifies
 *   existing active devices when a new device is linked.
 * - purgeExpired: hourly cleanup of expired disappearing messages, statuses, and pairing sessions.
 *
 * Requires the Blaze plan. The Firestore database ID must match the app's named database.
 */
import { setGlobalOptions, logger } from "firebase-functions/v2";
import { onDocumentCreated, onDocumentUpdated, onDocumentWritten } from "firebase-functions/v2/firestore";
import { onRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { FieldValue, getFirestore, Timestamp } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import {
  callEndedPayload,
  callPayload,
  deviceLinkedPayload,
  ExchangeRequest,
  isStaleTokenError,
  messagePayload,
  messageRecipients,
  MessageDoc,
  PairingSessionDoc,
  sealCustomTokenEnvelope,
  shouldNotify,
  validatePairingExchange,
} from "./payloads";

const DATABASE = process.env.PULSECHAT_DATABASE_ID ?? "ai-studio-android-pulsecha-2c0cef4c-b560-4944-a812-3a390d3cee6a";

initializeApp();
const db = getFirestore(DATABASE);
setGlobalOptions({ maxInstances: 10 });

async function deviceTokens(
  uid: string,
  excludeDeviceId?: string,
): Promise<{ deviceId: string; token: string; ref: FirebaseFirestore.DocumentReference }[]> {
  const [devicesSnap, linkedSnap] = await Promise.all([
    db.collection(`users/${uid}/devices`).get(),
    db.collection(`users/${uid}/linkedDevices`).get(),
  ]);
  const revokedIds = new Set(
    linkedSnap.docs.filter((d) => d.get("status") === "revoked").map((d) => d.id),
  );
  return devicesSnap.docs
    .filter((d) => d.id !== excludeDeviceId && !revokedIds.has(d.id))
    .map((d) => ({ deviceId: d.id, token: d.get("token") as string | undefined, ref: d.ref }))
    .filter(
      (d): d is { deviceId: string; token: string; ref: FirebaseFirestore.DocumentReference } =>
        typeof d.token === "string" && d.token.length > 0,
    );
}

async function push(
  uid: string,
  data: Record<string, string>,
  ttlSeconds: number,
  excludeDeviceId?: string,
): Promise<void> {
  const devices = await deviceTokens(uid, excludeDeviceId);
  if (devices.length === 0) return;
  const result = await getMessaging().sendEachForMulticast({
    tokens: devices.map((d) => d.token),
    data,
    android: { priority: "high", ttl: ttlSeconds * 1000 },
  });
  await Promise.all(
    result.responses.map(async (r, i) => {
      if (!r.success && isStaleTokenError(r.error?.code)) await devices[i].ref.delete().catch(() => undefined);
    }),
  );
}

export const onMessageCreated = onDocumentCreated(
  { document: "conversations/{cid}/messages/{mid}", database: DATABASE },
  async (event) => {
    const msg = event.data?.data() as MessageDoc | undefined;
    if (!msg) return;
    const { cid, mid } = event.params;
    const conv = (await db.doc(`conversations/${cid}`).get()).data();
    if (!conv) return;
    const now = Date.now();
    await Promise.all(
      messageRecipients(conv.participants as string[], msg.senderId).map(async (uid) => {
        const [blocked, prefs, devices] = await Promise.all([
          db.doc(`users/${uid}/blocked/${msg.senderId}`).get(),
          db.doc(`users/${uid}/chatPrefs/${cid}`).get(),
          deviceTokens(uid),
        ]);
        const state = {
          uid,
          blockedSender: blocked.exists,
          mutedUntil: Number(prefs.get("mutedUntil") ?? 0),
          tokens: devices.map((d) => d.token),
        };
        if (!shouldNotify(msg, state, now)) return;
        await push(uid, messagePayload(cid, mid), 24 * 3600);
      }),
    );
    logger.debug("message fan-out done", { cid, mid });
  },
);

export const onCallCreated = onDocumentCreated({ document: "calls/{callId}", database: DATABASE }, async (event) => {
  const call = event.data?.data();
  if (!call || call.state !== "ringing") return;
  const blocked = await db.doc(`users/${call.calleeId}/blocked/${call.callerId}`).get();
  if (blocked.exists) return;
  await push(call.calleeId as string, callPayload(event.params.callId), 45);
});

export const onCallUpdated = onDocumentUpdated({ document: "calls/{callId}", database: DATABASE }, async (event) => {
  const before = event.data?.before.data();
  const after = event.data?.after.data();
  if (!before || !after) return;
  if (before.state === "ringing" && ["cancelled", "missed", "failed"].includes(after.state as string)) {
    await push(after.calleeId as string, callEndedPayload(event.params.callId), 60);
  }
});

/**
 * When an authenticated device approves a pairing session, mint a Firebase Admin SDK custom token
 * with the `linkedDeviceId` claim and seal it with ECDH P-256 + HKDF-SHA256 + AES-256-GCM to the
 * requesting device's ephemeral public key if an envelope is not already written.
 */
export const onDevicePairingApproved = onDocumentUpdated(
  { document: "devicePairings/{sessionId}", database: DATABASE },
  async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();
    if (!before || !after) return;
    if (before.status !== "pending" || after.status !== "approved" || after.consumed === true) return;
    const sessionId = event.params.sessionId;
    const ownerUid = after.ownerUid as string | undefined;
    const approvedByDeviceId = after.approvedByDeviceId as string | undefined;
    const requesterDeviceId = after.requesterDeviceId as string | undefined;
    const expiresAt = after.expiresAt as Timestamp | undefined;
    if (!ownerUid || !approvedByDeviceId || !requesterDeviceId || !expiresAt) return;
    if (expiresAt.toMillis() <= Date.now()) return;

    const approverSnap = await db.doc(`users/${ownerUid}/linkedDevices/${approvedByDeviceId}`).get();
    if (approverSnap.exists && approverSnap.get("status") === "revoked") return;

    const envRef = db.doc(`devicePairings/${sessionId}/exchange/envelope`);
    const existingEnv = await envRef.get();
    if (existingEnv.exists) return;

    const customToken = await getAuth().createCustomToken(ownerUid, { linkedDeviceId: requesterDeviceId });
    const sealed = sealCustomTokenEnvelope(
      after.ephemeralPublicKey as string,
      sessionId,
      ownerUid,
      requesterDeviceId,
      after.secretHash as string,
      after.codeChallenge as string,
      JSON.stringify({ type: "customToken", uid: ownerUid, customToken }),
    );
    await envRef.set({
      ...sealed,
      createdAt: FieldValue.serverTimestamp(),
    });
  },
);

/**
 * One-time pairing exchange endpoint: verifies the requesting device's secret proof and ECDSA P-256
 * signature, atomically marks the session completed, registers the linked device, and returns a
 * Firebase Admin SDK custom token bound to `linkedDeviceId`.
 */
export const exchangeDevicePairing = onRequest({ cors: true }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }
  const body = (req.body ?? {}) as Partial<ExchangeRequest>;
  const request: ExchangeRequest = {
    sessionId: String(body.sessionId ?? ""),
    sessionSecret: String(body.sessionSecret ?? ""),
    deviceId: String(body.deviceId ?? ""),
    timestampMs: Number(body.timestampMs ?? 0),
    signature: String(body.signature ?? ""),
  };
  if (!/^[A-Za-z0-9_-]{20,40}$/.test(request.sessionId)) {
    res.status(400).json({ error: "invalid_session_id" });
    return;
  }
  try {
    const sessionRef = db.doc(`devicePairings/${request.sessionId}`);
    const outcome = await db.runTransaction(async (tx) => {
      const snap = await tx.get(sessionRef);
      const data = snap.data();
      const expiresAt = data?.expiresAt as Timestamp | undefined;
      const sessionDoc: PairingSessionDoc | undefined = data
        ? {
            sessionId: String(data.sessionId ?? snap.id),
            status: String(data.status ?? ""),
            consumed: Boolean(data.consumed),
            requesterDeviceId: String(data.requesterDeviceId ?? ""),
            requesterDeviceName: String(data.requesterDeviceName ?? "Android Device"),
            requesterDeviceType: String(data.requesterDeviceType ?? "android_phone"),
            requesterPlatform: String(data.requesterPlatform ?? "android"),
            requesterPublicKey: String(data.requesterPublicKey ?? ""),
            requesterKeyFingerprint: String(data.requesterKeyFingerprint ?? ""),
            ephemeralPublicKey: String(data.ephemeralPublicKey ?? ""),
            secretHash: String(data.secretHash ?? ""),
            codeChallenge: String(data.codeChallenge ?? ""),
            ownerUid: (data.ownerUid as string | null) ?? null,
            approvedByDeviceId: (data.approvedByDeviceId as string | null) ?? null,
            expiresAtMs: expiresAt ? expiresAt.toMillis() : 0,
          }
        : undefined;

      let approverRevoked = false;
      if (sessionDoc?.ownerUid && sessionDoc?.approvedByDeviceId) {
        const appSnap = await tx.get(
          db.doc(`users/${sessionDoc.ownerUid}/linkedDevices/${sessionDoc.approvedByDeviceId}`),
        );
        approverRevoked = appSnap.exists && appSnap.get("status") === "revoked";
      }

      const check = validatePairingExchange(sessionDoc, request, approverRevoked, Date.now());
      if (!check.ok || !sessionDoc || !sessionDoc.ownerUid) {
        return { ok: false as const, reason: check.ok ? "missing_owner" : check.reason };
      }

      const ownerUid = sessionDoc.ownerUid;
      const linkedRef = db.doc(`users/${ownerUid}/linkedDevices/${sessionDoc.requesterDeviceId}`);
      const envRef = db.doc(`devicePairings/${request.sessionId}/exchange/envelope`);

      tx.update(sessionRef, {
        status: "completed",
        consumed: true,
        completedAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      });
      tx.delete(envRef);
      tx.set(linkedRef, {
        deviceId: sessionDoc.requesterDeviceId,
        uid: ownerUid,
        deviceName: sessionDoc.requesterDeviceName,
        deviceType: sessionDoc.requesterDeviceType,
        platform: sessionDoc.requesterPlatform,
        publicKey: sessionDoc.requesterPublicKey,
        keyFingerprint: sessionDoc.requesterKeyFingerprint,
        authMethod: "qr_linked",
        status: "active",
        online: true,
        linkedAt: FieldValue.serverTimestamp(),
        lastActiveAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
        revokedAt: null,
        revokedByDeviceId: null,
        pairedByDeviceId: sessionDoc.approvedByDeviceId,
        sessionId: sessionDoc.sessionId,
      });
      return {
        ok: true as const,
        ownerUid,
        deviceId: sessionDoc.requesterDeviceId,
        deviceName: sessionDoc.requesterDeviceName,
      };
    });

    if (!outcome.ok) {
      res.status(403).json({ error: outcome.reason });
      return;
    }

    const customToken = await getAuth().createCustomToken(outcome.ownerUid, {
      linkedDeviceId: outcome.deviceId,
    });

    res.status(200).json({
      uid: outcome.ownerUid,
      deviceId: outcome.deviceId,
      customToken,
    });
  } catch (e) {
    logger.error("exchangeDevicePairing failed", e);
    res.status(500).json({ error: "exchange_failed" });
  }
});

/**
 * When a linked device is revoked, delete its FCM token immediately so it receives no further
 * notifications; when a new QR-linked device becomes active (created or re-linked), notify the
 * account's other active devices once.
 */
export const onLinkedDeviceUpdated = onDocumentWritten(
  { document: "users/{uid}/linkedDevices/{deviceId}", database: DATABASE },
  async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();
    if (!after) return;
    const { uid, deviceId } = event.params;
    if (after.status === "revoked") {
      await db.doc(`users/${uid}/devices/${deviceId}`).delete().catch(() => undefined);
    } else if (before?.status !== "active" && after.status === "active" && after.authMethod === "qr_linked") {
      await push(uid, deviceLinkedPayload(deviceId, String(after.deviceName ?? "Android Device")), 24 * 3600, deviceId);
    }
  },
);

export const purgeExpired = onSchedule("every 60 minutes", async () => {
  const now = Timestamp.now();
  let deleted = 0;
  for (const query of [
    db.collectionGroup("messages").where("expiresAt", "<", now).limit(400),
    db.collection("statuses").where("expiresAt", "<", now).limit(400),
    db.collection("devicePairings").where("expiresAt", "<", now).limit(200),
    db.collection("devicePairingCodes").where("expiresAt", "<", now).limit(400),
  ]) {
    const snap = await query.get();
    const batch = db.batch();
    snap.docs.forEach((d) => {
      if (d.ref.parent.id === "devicePairings") {
        batch.delete(d.ref.collection("exchange").doc("envelope"));
      }
      batch.delete(d.ref);
    });
    if (!snap.empty) await batch.commit();
    deleted += snap.size;
  }
  logger.info("purged expired documents", { deleted });
});
