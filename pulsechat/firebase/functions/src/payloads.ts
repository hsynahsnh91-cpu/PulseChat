/**
 * Pure helpers (unit-tested) deciding who gets a push and what it contains.
 * Payloads are data-only and carry IDs, never message content: the app fetches the
 * content with the recipient's own credentials, so Firestore rules still apply.
 */
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

const STALE_TOKEN_ERRORS = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
  "messaging/invalid-argument",
]);

export function isStaleTokenError(code: string | undefined): boolean {
  return code !== undefined && STALE_TOKEN_ERRORS.has(code);
}
