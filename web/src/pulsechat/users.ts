/** Profiles, search, privacy-controlled fields and presence (UserRepository.kt). */
import { doc, getDoc, onSnapshot, serverTimestamp, setDoc, type Unsubscribe } from "firebase/firestore";
import { requireUid, type PulseChat } from "./firebase";
import { isTenDigitId, normalizeUsername, usernameError } from "./ids";
import { Fs, millis, toUser, type ChatUser, type PresenceDoc } from "./schema";

export async function getUser(pc: PulseChat, uid: string): Promise<ChatUser | null> {
  return toUser(await getDoc(doc(pc.db, Fs.USERS, uid)));
}

export function observeUser(pc: PulseChat, uid: string, callback: (user: ChatUser | null) => void): Unsubscribe {
  return onSnapshot(doc(pc.db, Fs.USERS, uid), (snap) => callback(toUser(snap)));
}

/**
 * Finds a person by their exact username (with or without "@") or exact 10-digit ID, like Android.
 * Display names are not searched: they are not unique, so a match could be the wrong person.
 * Lookups go through the usernames/uniqueIds reservations; me and users I blocked are excluded.
 */
export async function searchUsers(pc: PulseChat, text: string): Promise<ChatUser[]> {
  const me = requireUid(pc);
  const q = normalizeUsername(text);
  let uid: unknown = null;
  if (isTenDigitId(q)) uid = (await getDoc(doc(pc.db, Fs.UNIQUE_IDS, q))).get("uid");
  else if (usernameError(q) === null) uid = (await getDoc(doc(pc.db, Fs.USERNAMES, q))).get("uid");
  if (typeof uid !== "string" || uid === me) return [];
  const user = await getUser(pc, uid);
  if (!user) return [];
  const blocked = await getDoc(doc(pc.db, Fs.USERS, me, Fs.BLOCKED, uid));
  return blocked.exists() ? [] : [user];
}

/**
 * Profile photo URL from users/{uid}/restricted/photo. Returns null when it is not set or the
 * owner's privacy settings hide it from me (the rules deny the read).
 */
export async function getProfilePhotoUrl(pc: PulseChat, uid: string): Promise<string | null> {
  try {
    const url = (await getDoc(doc(pc.db, Fs.USERS, uid, Fs.RESTRICTED, Fs.PHOTO_DOC))).get("url");
    return typeof url === "string" ? url : null;
  } catch {
    return null;
  }
}

/** "About" text from users/{uid}/restricted/about (null when hidden or not set). */
export async function getAbout(pc: PulseChat, uid: string): Promise<string | null> {
  try {
    const text = (await getDoc(doc(pc.db, Fs.USERS, uid, Fs.RESTRICTED, Fs.ABOUT_DOC))).get("text");
    return typeof text === "string" ? text : null;
  } catch {
    return null;
  }
}

/** My read-receipt setting (users/{me}/private/settings.readReceipts, default on), shared with Android. */
export function readReceiptsEnabled(pc: PulseChat): Promise<boolean> {
  const me = requireUid(pc);
  pc.cache.readReceipts ??= getDoc(doc(pc.db, Fs.USERS, me, Fs.PRIVATE, Fs.SETTINGS_DOC))
    .then((snap) => snap.get("readReceipts") !== false)
    .catch(() => true);
  return pc.cache.readReceipts;
}

// ---------------------------------------------------------------------------------- presence

export const HEARTBEAT_INTERVAL_MS = 45_000;
export const STALE_AFTER_MS = 100_000;

/**
 * Publishes presence like Android: a heartbeat every 45 s while the page is visible, and
 * online=false + lastSeen when it is hidden or closed. Returns an async stop function; await it
 * before signOut().
 */
export function startPresence(pc: PulseChat): () => Promise<void> {
  const uid = requireUid(pc);
  const ref = doc(pc.db, Fs.PRESENCE, uid);
  let timer: ReturnType<typeof setInterval> | null = null;
  const beat = () => setDoc(ref, { online: true, heartbeatAt: serverTimestamp() }).catch(() => undefined);
  const offline = () => setDoc(ref, { online: false, heartbeatAt: serverTimestamp(), lastSeen: serverTimestamp() }).catch(() => undefined);
  const resume = () => {
    if (timer) return;
    void beat();
    timer = setInterval(beat, HEARTBEAT_INTERVAL_MS);
  };
  const pause = () => {
    if (timer) clearInterval(timer);
    timer = null;
    return offline();
  };
  const browser = typeof document !== "undefined" && typeof window !== "undefined";
  const onVisibility = () => (document.visibilityState === "visible" ? resume() : void pause());
  const onPageHide = () => void pause();
  if (browser) {
    document.addEventListener("visibilitychange", onVisibility);
    window.addEventListener("pagehide", onPageHide);
    if (document.visibilityState === "visible") resume();
  } else {
    resume();
  }
  return async () => {
    if (browser) {
      document.removeEventListener("visibilitychange", onVisibility);
      window.removeEventListener("pagehide", onPageHide);
    }
    await pause();
  };
}

export interface PresenceView {
  /** False when the user's privacy settings (or mine: reciprocity) hide their presence. */
  visible: boolean;
  online: boolean;
  /** Milliseconds; 0 when online or unknown. */
  lastSeen: number;
  /** Firestore error code when the read failed ("permission-denied" = hidden by privacy settings). */
  error?: string;
}

/** Another user's presence, as far as their privacy settings allow (the rules enforce it). */
export function observePresence(pc: PulseChat, uid: string, callback: (presence: PresenceView) => void): Unsubscribe {
  return onSnapshot(
    doc(pc.db, Fs.PRESENCE, uid),
    (snap) => {
      const d = snap.data({ serverTimestamps: "estimate" }) as Partial<PresenceDoc> | undefined;
      const heartbeatAt = millis(d?.heartbeatAt);
      const online = d?.online === true && Date.now() - heartbeatAt < STALE_AFTER_MS;
      callback({ visible: true, online, lastSeen: online ? 0 : Math.max(millis(d?.lastSeen), heartbeatAt) });
    },
    (error) => callback({ visible: false, online: false, lastSeen: 0, error: error.code }),
  );
}
