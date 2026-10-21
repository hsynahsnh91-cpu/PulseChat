import type { FirebaseOptions } from "firebase/app";

/**
 * Named Firestore database used by the Android app
 * (pulsechat/app/src/main/res/values/firebase_applet_config.xml, `firestore_database_id`).
 * The web app must open the same database: `getFirestore(app)` without an ID opens "(default)",
 * which is a different, empty database and would not sync with Android.
 */
export const ANDROID_FIRESTORE_DATABASE_ID = "ai-studio-android-pulsecha-2c0cef4c-b560-4944-a812-3a390d3cee6a";

/** Project values from pulsechat/app/google-services.json that the web config must match. */
export const ANDROID_FIREBASE_PROJECT = {
  projectId: "gen-lang-client-0426516505",
  /** `project_info.project_number`: the web config calls it `messagingSenderId`. */
  projectNumber: "677612835092",
  storageBucket: "gen-lang-client-0426516505.firebasestorage.app",
  authDomain: "gen-lang-client-0426516505.firebaseapp.com",
} as const;

export interface EmulatorSettings {
  host: string;
  /** Defaults match pulsechat/firebase.json. */
  authPort?: number;
  firestorePort?: number;
  storagePort?: number;
}

export interface PulseChatWebConfig {
  /** Firebase console → Project settings → Your apps → (your Web app) → SDK setup and configuration. */
  firebase: FirebaseOptions;
  /** Must equal {@link ANDROID_FIRESTORE_DATABASE_ID}. */
  firestoreDatabaseId: string;
  /** Talk to the local Firebase emulators (`firebase emulators:start` in pulsechat/) instead of production. */
  emulators?: EmulatorSettings;
  /** Persistent IndexedDB cache for offline use (browsers only). Default: in-memory cache. */
  persistentCache?: boolean;
  /** Firebase app name. Only needed to run several signed-in users in one process (tests). */
  appName?: string;
}

/**
 * Builds the config from Vite-style environment variables (see web/.env.example).
 * Pass `import.meta.env` (Vite) or an object with the same keys (other bundlers).
 */
export function configFromEnv(env: Record<string, string | boolean | undefined>): PulseChatWebConfig {
  const value = (key: string): string | undefined => {
    const v = env[key];
    return typeof v === "string" && v.trim() !== "" ? v.trim() : undefined;
  };
  const required = ["VITE_FIREBASE_API_KEY", "VITE_FIREBASE_PROJECT_ID", "VITE_FIREBASE_APP_ID"];
  const missing = required.filter((k) => value(k) === undefined);
  if (missing.length > 0) {
    throw new Error(`Missing ${missing.join(", ")}. Copy web/.env.example to web/.env and fill in your Web app's config.`);
  }
  const projectId = value("VITE_FIREBASE_PROJECT_ID")!;
  const emulatorHost = value("VITE_FIREBASE_EMULATOR_HOST");
  return {
    firebase: {
      apiKey: value("VITE_FIREBASE_API_KEY"),
      authDomain: value("VITE_FIREBASE_AUTH_DOMAIN") ?? `${projectId}.firebaseapp.com`,
      projectId,
      storageBucket: value("VITE_FIREBASE_STORAGE_BUCKET") ?? ANDROID_FIREBASE_PROJECT.storageBucket,
      messagingSenderId: value("VITE_FIREBASE_MESSAGING_SENDER_ID"),
      appId: value("VITE_FIREBASE_APP_ID"),
    },
    firestoreDatabaseId: value("VITE_FIRESTORE_DATABASE_ID") ?? ANDROID_FIRESTORE_DATABASE_ID,
    emulators: emulatorHost ? { host: emulatorHost } : undefined,
    persistentCache: value("VITE_FIRESTORE_PERSISTENT_CACHE") === "true",
  };
}
