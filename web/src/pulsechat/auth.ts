/**
 * Authentication compatible with the Android app (AuthRepository.kt):
 *  - username (or 10-digit ID) + password → Firebase email/password with the synthetic address
 *    from authEmailForUsername(), so the same account signs in on Android and on the web;
 *  - Google → the same Firebase user as Google sign-in on Android (same Google account);
 *  - the profile (users/{uid}) and the unique handles (usernames/, uniqueIds/) are created in one
 *    transaction, exactly like Android, because firestore.rules check all three together.
 */
import {
  createUserWithEmailAndPassword, deleteUser, GoogleAuthProvider, onAuthStateChanged, signInWithEmailAndPassword, signInWithPopup,
  signOut as firebaseSignOut,
} from "firebase/auth";
import { doc, getDoc, onSnapshot, runTransaction, type Unsubscribe } from "firebase/firestore";
import type { PulseChat } from "./firebase";
import {
  authEmailForUsername, avatarColorFor, displayNameError, generate10DigitId, isTenDigitId, normalizeUsername, passwordError, usernameError,
  type FieldError,
} from "./ids";
import { Fs, toUser, userProfileData, type ChatUser } from "./schema";

export type AuthErrorCode =
  | "INVALID_CREDENTIALS" | "USERNAME_TAKEN" | "WEAK_PASSWORD" | "NETWORK" | "TOO_MANY_REQUESTS" | "PROVIDER_DISABLED"
  | "CANCELLED" | "BACKEND_NOT_READY" | "INVALID_INPUT" | "UNKNOWN";

export class PulseChatAuthError extends Error {
  readonly code: AuthErrorCode;
  readonly field: FieldError | undefined;
  constructor(code: AuthErrorCode, field?: FieldError, detail?: string) {
    super(detail ? `${code}: ${detail}` : code);
    this.name = "PulseChatAuthError";
    this.code = code;
    this.field = field;
  }
}

export type Session =
  | { state: "loading" }
  | { state: "signedOut" }
  | { state: "needsProfile"; uid: string; suggestedName: string }
  | { state: "signedIn"; uid: string; profile: ChatUser }
  | { state: "profileUnavailable"; uid: string; error: string };

/** Mirrors AuthRepository.map(). */
export function mapAuthError(e: unknown): PulseChatAuthError {
  if (e instanceof PulseChatAuthError) return e;
  const code = (e as { code?: string } | null)?.code ?? "";
  const detail = (e as { message?: string } | null)?.message;
  switch (code) {
    case "auth/invalid-credential": case "auth/wrong-password": case "auth/user-not-found": case "auth/invalid-email":
    case "auth/user-disabled": case "auth/invalid-login-credentials":
      return new PulseChatAuthError("INVALID_CREDENTIALS");
    case "auth/email-already-in-use": return new PulseChatAuthError("USERNAME_TAKEN");
    case "auth/weak-password": return new PulseChatAuthError("WEAK_PASSWORD", "WEAK");
    case "auth/network-request-failed": case "unavailable": case "deadline-exceeded": return new PulseChatAuthError("NETWORK");
    case "auth/too-many-requests": return new PulseChatAuthError("TOO_MANY_REQUESTS");
    case "auth/operation-not-allowed": return new PulseChatAuthError("PROVIDER_DISABLED");
    case "auth/popup-closed-by-user": case "auth/cancelled-popup-request": return new PulseChatAuthError("CANCELLED");
    case "permission-denied": case "not-found": case "failed-precondition": return new PulseChatAuthError("BACKEND_NOT_READY", undefined, detail);
    default: return new PulseChatAuthError("UNKNOWN", undefined, detail);
  }
}

export async function isUsernameTaken(pc: PulseChat, username: string): Promise<boolean> {
  try {
    return (await getDoc(doc(pc.db, Fs.USERNAMES, normalizeUsername(username)))).exists();
  } catch (e) {
    throw mapAuthError(e);
  }
}

/** Creates a username/password account and its profile. Resolves with the new UID. */
export async function signUp(
  pc: PulseChat,
  input: { displayName: string; username: string; password: string; confirm?: string },
): Promise<string> {
  const invalid = displayNameError(input.displayName) ?? usernameError(input.username) ?? passwordError(input.password)
    ?? (input.confirm !== undefined && input.confirm !== input.password ? "MISMATCH" : null);
  if (invalid) throw new PulseChatAuthError("INVALID_INPUT", invalid);
  const username = normalizeUsername(input.username);
  pc.registering++;
  try {
    if (await isUsernameTaken(pc, username)) throw new PulseChatAuthError("USERNAME_TAKEN");
    let credential;
    try {
      credential = await createUserWithEmailAndPassword(pc.auth, authEmailForUsername(username, pc.projectId), input.password);
    } catch (e) {
      throw mapAuthError(e);
    }
    try {
      await createProfile(pc, credential.user.uid, username, input.displayName.trim());
    } catch (e) {
      await deleteUser(credential.user).catch(() => undefined);
      throw mapAuthError(e);
    }
    return credential.user.uid;
  } finally {
    pc.registering--;
  }
}

/** Signs in with a username (with or without "@") or the 10-digit ID. Resolves with the UID. */
export async function signIn(pc: PulseChat, identifier: string, password: string): Promise<string> {
  const input = identifier.trim().replace(/^@/, "").replace(/^#/, "");
  if (!input || !password) throw new PulseChatAuthError("INVALID_INPUT", "EMPTY");
  let username = normalizeUsername(input);
  if (isTenDigitId(input)) {
    const snap = await getDoc(doc(pc.db, Fs.UNIQUE_IDS, input)).catch(() => null);
    const resolved = snap?.get("username");
    if (typeof resolved !== "string") throw new PulseChatAuthError("INVALID_CREDENTIALS");
    username = resolved;
  }
  try {
    const credential = await signInWithEmailAndPassword(pc.auth, authEmailForUsername(username, pc.projectId), password);
    return credential.user.uid;
  } catch (e) {
    throw mapAuthError(e);
  }
}

/**
 * Google sign-in (browser popup). Add your web domain under Authentication → Settings →
 * Authorized domains. New users get session state "needsProfile": call completeProfile().
 */
export async function signInWithGoogle(pc: PulseChat): Promise<string> {
  try {
    return (await signInWithPopup(pc.auth, new GoogleAuthProvider())).user.uid;
  } catch (e) {
    throw mapAuthError(e);
  }
}

/** Second step for new Google users: choose a unique username and a display name. */
export async function completeProfile(pc: PulseChat, username: string, displayName: string): Promise<void> {
  const user = pc.auth.currentUser;
  if (!user) throw new PulseChatAuthError("INVALID_CREDENTIALS");
  const invalid = displayNameError(displayName) ?? usernameError(username);
  if (invalid) throw new PulseChatAuthError("INVALID_INPUT", invalid);
  try {
    await createProfile(pc, user.uid, normalizeUsername(username), displayName.trim());
  } catch (e) {
    throw mapAuthError(e);
  }
}

class UsernameTakenError extends Error {}

/** Port of AuthRepository.createProfile: reserves the username and a 10-digit ID, creates users/{uid}. */
async function createProfile(pc: PulseChat, uid: string, username: string, displayName: string): Promise<string> {
  const candidates = [generate10DigitId(), generate10DigitId(), generate10DigitId()];
  try {
    return await runTransaction(pc.db, async (tx) => {
      const userRef = doc(pc.db, Fs.USERS, uid);
      const existing = await tx.get(userRef);
      if (existing.exists()) return String(existing.get("uniqueId10") ?? "");
      const nameRef = doc(pc.db, Fs.USERNAMES, username);
      const nameSnap = await tx.get(nameRef);
      if (nameSnap.exists() && nameSnap.get("uid") !== uid) throw new UsernameTakenError();
      let id: string | null = null;
      for (const candidate of candidates) {
        if (!(await tx.get(doc(pc.db, Fs.UNIQUE_IDS, candidate))).exists()) { id = candidate; break; }
      }
      if (!id) throw new Error("could not allocate a unique ID");
      if (!nameSnap.exists()) tx.set(nameRef, { uid });
      tx.set(doc(pc.db, Fs.UNIQUE_IDS, id), { uid, username });
      tx.set(userRef, userProfileData(uid, username, id, displayName, avatarColorFor(uid)));
      return id;
    });
  } catch (e) {
    if (e instanceof UsernameTakenError) throw new PulseChatAuthError("USERNAME_TAKEN");
    throw e;
  }
}

/**
 * Session state, updated in real time (sign-in/out, profile created on another device).
 * Mirrors AuthRepository.session.
 */
export function observeSession(pc: PulseChat, callback: (session: Session) => void): Unsubscribe {
  let profileUnsubscribe: Unsubscribe | null = null;
  callback({ state: "loading" });
  const authUnsubscribe = onAuthStateChanged(pc.auth, (user) => {
    profileUnsubscribe?.();
    profileUnsubscribe = null;
    if (!user) {
      callback({ state: "signedOut" });
      return;
    }
    profileUnsubscribe = onSnapshot(
      doc(pc.db, Fs.USERS, user.uid),
      (snap) => {
        const profile = snap.exists() ? toUser(snap) : null;
        if (profile) callback({ state: "signedIn", uid: user.uid, profile });
        else if (pc.registering > 0 || snap.metadata.fromCache) callback({ state: "loading" });
        else callback({ state: "needsProfile", uid: user.uid, suggestedName: user.displayName ?? "" });
      },
      (error) => {
        if (pc.auth.currentUser?.uid === user.uid) callback({ state: "profileUnavailable", uid: user.uid, error: error.message });
      },
    );
  });
  return () => {
    profileUnsubscribe?.();
    authUnsubscribe();
  };
}

/** Signs out. Call the function returned by startPresence() first so "last seen" is published. */
export async function signOut(pc: PulseChat): Promise<void> {
  pc.cache.readReceipts = undefined;
  pc.cache.typing.clear();
  await firebaseSignOut(pc.auth);
}
