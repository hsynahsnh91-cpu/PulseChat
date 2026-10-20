/**
 * PulseChat Cloud Functions (2nd gen).
 *
 * - onMessageCreated: data-only FCM push to the other participants (skips blocked senders,
 *   respects per-chat mute except @mentions) and prunes stale device tokens.
 * - onCallCreated / onCallUpdated: high-priority call invitations and cancellations.
 * - purgeExpired: hourly cleanup of expired disappearing messages and statuses (TTL policies in
 *   firestore.indexes.json do the same; this is a fallback).
 *
 * Requires the Blaze plan. The Firestore database ID must match the app's named database.
 */
import { setGlobalOptions, logger } from "firebase-functions/v2";
import { onDocumentCreated, onDocumentUpdated } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { callEndedPayload, callPayload, isStaleTokenError, messagePayload, messageRecipients, MessageDoc, shouldNotify } from "./payloads";

const DATABASE = process.env.PULSECHAT_DATABASE_ID ?? "ai-studio-android-pulsecha-2c0cef4c-b560-4944-a812-3a390d3cee6a";

initializeApp();
const db = getFirestore(DATABASE);
setGlobalOptions({ maxInstances: 10 });

async function deviceTokens(uid: string): Promise<{ token: string; ref: FirebaseFirestore.DocumentReference }[]> {
  const snap = await db.collection(`users/${uid}/devices`).get();
  return snap.docs
    .map((d) => ({ token: d.get("token") as string | undefined, ref: d.ref }))
    .filter((d): d is { token: string; ref: FirebaseFirestore.DocumentReference } => typeof d.token === "string" && d.token.length > 0);
}

async function push(uid: string, data: Record<string, string>, ttlSeconds: number): Promise<void> {
  const devices = await deviceTokens(uid);
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

export const purgeExpired = onSchedule("every 60 minutes", async () => {
  const now = Timestamp.now();
  let deleted = 0;
  for (const query of [
    db.collectionGroup("messages").where("expiresAt", "<", now).limit(400),
    db.collection("statuses").where("expiresAt", "<", now).limit(400),
  ]) {
    const snap = await query.get();
    const batch = db.batch();
    snap.docs.forEach((d) => batch.delete(d.ref));
    if (!snap.empty) await batch.commit();
    deleted += snap.size;
  }
  logger.info("purged expired documents", { deleted });
});
