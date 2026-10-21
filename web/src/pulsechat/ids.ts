/**
 * Identifiers and input validation. Ports of pulsechat/app/src/main/java/com/example/domain/Ids.kt and
 * Validators.kt: both clients must derive exactly the same IDs and login addresses.
 */

/** Deterministic direct-chat ID `dm_<lowerUid>_<higherUid>` (firestore.rules rejects any other ID). */
export function directConversationId(a: string, b: string): string {
  if (!a || !b || a === b) throw new Error("invalid direct participants");
  const [first, second] = a < b ? [a, b] : [b, a];
  return `dm_${first}_${second}`;
}

export const isDirectConversationId = (id: string): boolean => id.startsWith("dm_");

/** Client-generated message IDs are random UUIDs; they double as idempotency keys. */
export const newMessageId = (): string => crypto.randomUUID();

/** Group IDs: `grp_` + UUID without dashes (Ids.newId("grp")). */
export const newGroupId = (): string => `grp_${crypto.randomUUID().replace(/-/g, "")}`;

/** Public 10-digit account ID; never starts with 0. */
export function generate10DigitId(): string {
  const digits = crypto.getRandomValues(new Uint8Array(10));
  let id = String(1 + (digits[0] % 9));
  for (let i = 1; i < 10; i++) id += String(digits[i] % 10);
  return id;
}

/**
 * Username/password accounts use Firebase email/password auth with a synthetic, non-deliverable
 * address under the project's firebaseapp.com domain. Same formula as Android, so one account
 * signs in on both platforms. Never shown to users and never stored in Firestore.
 */
export function authEmailForUsername(username: string, projectId: string): string {
  return `${username.trim().toLowerCase()}@${projectId.trim().toLowerCase()}.firebaseapp.com`;
}

export const AVATAR_COLORS = ["#4F46E5", "#0D9488", "#DB2777", "#EA580C", "#7C3AED", "#2563EB", "#059669", "#B45309"] as const;

/** java.lang.String#hashCode, so a UID gets the same avatar colour as on Android. */
export function javaHashCode(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
  return h;
}

export const avatarColorFor = (uid: string): string => AVATAR_COLORS[(javaHashCode(uid) & 0x7fffffff) % AVATAR_COLORS.length];

// ---------------------------------------------------------------------------------- validation

export type FieldError = "EMPTY" | "TOO_SHORT" | "TOO_LONG" | "INVALID_CHARS" | "MUST_START_WITH_LETTER" | "WEAK" | "MISMATCH";

export const USERNAME_MIN = 3;
export const USERNAME_MAX = 20;
export const PASSWORD_MIN = 8;
export const DISPLAY_NAME_MAX = 50;

export const normalizeUsername = (input: string): string => input.trim().replace(/^@/, "").toLowerCase();

export function usernameError(raw: string): FieldError | null {
  const u = normalizeUsername(raw);
  if (u.length === 0) return "EMPTY";
  if (u.length < USERNAME_MIN) return "TOO_SHORT";
  if (u.length > USERNAME_MAX) return "TOO_LONG";
  if (!/^[a-z0-9._]+$/.test(u) || u.includes("..") || u.endsWith(".")) return "INVALID_CHARS";
  if (!/^[a-z]/.test(u)) return "MUST_START_WITH_LETTER";
  return null;
}

export function passwordError(password: string): FieldError | null {
  if (password.length === 0) return "EMPTY";
  if (password.length < PASSWORD_MIN) return "TOO_SHORT";
  if (password.length > 128) return "TOO_LONG";
  if (!/\p{L}/u.test(password) || !/\p{Nd}/u.test(password)) return "WEAK";
  return null;
}

export function displayNameError(name: string): FieldError | null {
  if (name.trim().length === 0) return "EMPTY";
  if (name.trim().length > DISPLAY_NAME_MAX) return "TOO_LONG";
  return null;
}

export const isTenDigitId = (value: string): boolean => /^[1-9][0-9]{9}$/.test(value.trim());
