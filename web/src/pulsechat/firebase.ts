import { deleteApp, initializeApp, type FirebaseApp } from "firebase/app";
import { connectAuthEmulator, getAuth, type Auth } from "firebase/auth";
import {
  connectFirestoreEmulator, initializeFirestore, memoryLocalCache, persistentLocalCache, persistentMultipleTabManager, type Firestore,
} from "firebase/firestore";
import { connectStorageEmulator, getStorage, type FirebaseStorage } from "firebase/storage";
import { ANDROID_FIREBASE_PROJECT, ANDROID_FIRESTORE_DATABASE_ID, type PulseChatWebConfig } from "./config";

/** Firebase services bound to PulseChat's project and named database. Pass it to every module function. */
export interface PulseChat {
  app: FirebaseApp;
  auth: Auth;
  db: Firestore;
  storage: FirebaseStorage;
  projectId: string;
  /** Internal: >0 while signUp() creates the profile, so observeSession() does not report "needsProfile". */
  registering: number;
  /** Internal per-instance caches. */
  cache: { readReceipts?: Promise<boolean>; typing: Map<string, { typing: boolean; at: number }> };
}

export function initPulseChat(config: PulseChatWebConfig): PulseChat {
  const projectId = config.firebase.projectId;
  if (!projectId) throw new Error("firebase.projectId is missing");
  if (!config.emulators) {
    if (projectId !== ANDROID_FIREBASE_PROJECT.projectId) {
      console.warn(`[PulseChat] projectId "${projectId}" is not the Android app's project (${ANDROID_FIREBASE_PROJECT.projectId}); data will not sync.`);
    }
    if (config.firestoreDatabaseId !== ANDROID_FIRESTORE_DATABASE_ID) {
      console.warn(`[PulseChat] Firestore database "${config.firestoreDatabaseId}" is not the Android app's database; data will not sync.`);
    }
  }
  const app = config.appName ? initializeApp(config.firebase, config.appName) : initializeApp(config.firebase);
  const auth = getAuth(app);
  const db = initializeFirestore(
    app,
    { localCache: config.persistentCache ? persistentLocalCache({ tabManager: persistentMultipleTabManager() }) : memoryLocalCache() },
    config.firestoreDatabaseId,
  );
  const storage = getStorage(app);
  if (config.emulators) {
    const { host, authPort = 9099, firestorePort = 8080, storagePort = 9199 } = config.emulators;
    connectAuthEmulator(auth, `http://${host}:${authPort}`, { disableWarnings: true });
    connectFirestoreEmulator(db, host, firestorePort);
    connectStorageEmulator(storage, host, storagePort);
  }
  return { app, auth, db, storage, projectId, registering: 0, cache: { typing: new Map() } };
}

/** Releases the Firebase app (listeners, connections). */
export async function disposePulseChat(pc: PulseChat): Promise<void> {
  await deleteApp(pc.app);
}

export class PulseChatError extends Error {
  readonly code: ChatErrorCode;
  constructor(code: ChatErrorCode, detail?: string) {
    super(detail ? `${code}: ${detail}` : code);
    this.name = "PulseChatError";
    this.code = code;
  }
}

export type ChatErrorCode =
  | "NOT_SIGNED_IN" | "EMPTY" | "TOO_LONG" | "TOO_LARGE" | "NOT_ALLOWED" | "NOT_FOUND" | "INVALID_INPUT" | "EDIT_WINDOW_PASSED";

export function requireUid(pc: PulseChat): string {
  const uid = pc.auth.currentUser?.uid;
  if (!uid) throw new PulseChatError("NOT_SIGNED_IN");
  return uid;
}
