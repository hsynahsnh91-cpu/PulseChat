/**
 * Firestore schema shared with the Android app.
 *
 * Source of truth (keep in sync when either side changes):
 *  - pulsechat/app/src/main/java/com/example/data/remote/FirestoreSchema.kt   (paths, `Fs`)
 *  - pulsechat/app/src/main/java/com/example/data/remote/RemoteMappers.kt     (field names and types)
 *  - pulsechat/firestore.rules                                                (what the server accepts)
 * Do NOT use pulsechat/firebase-blueprint.json: it describes an older layout (top-level `messages`,
 * numeric timestamps) that the current rules reject.
 */
import {
  serverTimestamp, Timestamp, type DocumentData, type DocumentSnapshot, type FieldValue,
} from "firebase/firestore";
import { preview, previewText } from "./text";

export const Fs = {
  USERS: "users",
  RESTRICTED: "restricted",
  CONTACTS: "contacts",
  BLOCKED: "blocked",
  PRIVATE: "private",
  SETTINGS_DOC: "settings",
  DEVICES: "devices",
  LINKED_DEVICES: "linkedDevices",
  DEVICE_PAIRINGS: "devicePairings",
  DEVICE_PAIRING_CODES: "devicePairingCodes",
  PAIRING_EXCHANGE: "exchange",
  PAIRING_ENVELOPE_DOC: "envelope",
  CHAT_PREFS: "chatPrefs",
  USERNAMES: "usernames",
  UNIQUE_IDS: "uniqueIds",
  PRESENCE: "presence",
  CONVERSATIONS: "conversations",
  MESSAGES: "messages",
  STATUSES: "statuses",
  CALLS: "calls",
  REPORTS: "reports",
  MEDIA: "media",
  MEDIA_CHUNKS: "chunks",
  PHOTO_DOC: "photo",
  ABOUT_DOC: "about",
  TYPE_DIRECT: "direct",
  TYPE_GROUP: "group",
} as const;

/** Message types stored in Firestore (CALL_EVENT and AI_RESPONSE exist only on the Android device). */
export const MESSAGE_TYPES = ["TEXT", "IMAGE", "VIDEO", "AUDIO", "DOCUMENT", "LOCATION", "POLL", "CONTACT", "SYSTEM"] as const;
export type MessageType = (typeof MESSAGE_TYPES)[number];
export const isMediaType = (t: MessageType): boolean => t === "IMAGE" || t === "VIDEO" || t === "AUDIO" || t === "DOCUMENT";

export type Visibility = "everyone" | "contacts" | "nobody";

/** Allowed disappearing-message timers in seconds: off, 24 h, 7 days, 90 days. */
export const DISAPPEARING_OPTIONS = [0, 86_400, 604_800, 7_776_000] as const;
export const MAX_TEXT_LENGTH = 4096;
export const EDIT_WINDOW_MS = 15 * 60 * 1000;
/** A message keeps at most this many earlier versions (firestore.rules editByAuthor). */
export const MAX_EDITS = 20;
export const DELETE_FOR_EVERYONE_WINDOW_MS = 48 * 60 * 60 * 1000;

// ================================================================================ stored documents

/** users/{uid}: public profile. Photo and "about" live in users/{uid}/restricted/{photo|about}. */
export interface UserDoc {
  uid: string;
  username: string;             // ^[a-z][a-z0-9._]{2,19}$, immutable
  uniqueId10: string;           // ^[1-9][0-9]{9}$, immutable
  displayName: string;          // 1..50 characters
  displayNameLower: string;     // search key
  avatarColorHex: string;
  lastSeenVisibility: Visibility;
  photoVisibility: Visibility;
  aboutVisibility: Visibility;
  createdAt: Timestamp;         // server timestamp
}

/** conversations/{cid}.lastMessage: chat-list preview, written together with each message. */
export interface LastMessageDoc {
  id: string;
  senderId: string;
  type: MessageType;
  preview: string;              // previewText(), max 100 characters
  createdAt: Timestamp;
}

/** Read-receipt watermarks: createdAt of the newest message the user has delivered (d) / read (r). */
export interface ReceiptDoc { d?: Timestamp; r?: Timestamp }

/** conversations/{cid}. Direct chats: cid = dm_<lowerUid>_<higherUid>. Groups: cid = grp_<uuid>. */
export interface ConversationDoc {
  type: "direct" | "group";
  participants: string[];                 // direct: exactly 2 UIDs, sorted ascending; group: 2..256
  lastMessage?: LastMessageDoc;
  lastMessageAt?: Timestamp;
  updatedAt?: Timestamp;
  typing?: Record<string, Timestamp>;     // uid -> last keystroke; each user writes only their own key
  receipts?: Record<string, ReceiptDoc>;  // uid -> watermarks; each user writes only their own key
  disappearingSeconds?: number;           // one of DISAPPEARING_OPTIONS
  // groups only
  admins?: string[];
  createdBy?: string;
  createdAt?: Timestamp;
  title?: string;                         // 1..100
  description?: string;                   // <= 512
  photoUrl?: string | null;
  onlyAdminsCanSend?: boolean;
  onlyAdminsCanEditInfo?: boolean;
}

export interface MediaDoc {
  url: string;                  // download URL, or pulsemedia://{mediaId} for the built-in media store (media.ts)
  path: string;                 // chat-media/{cid}/{senderUid}/{messageId}.{ext} or the same pulsemedia:// reference
  mime: string;
  size: number;                 // bytes
  name: string | null;          // original file name
  durationMs: number;           // audio/video, else 0
  width: number;                // images/videos, else 0
  height: number;
  waveform: string | null;      // voice notes: comma-separated amplitudes
}

export interface ReplyToDoc { id: string; senderId: string | null; type: MessageType; preview: string }
export interface SystemDoc { event: string; actor: string; targets: string[]; value: string | null }
export interface PollDoc { question: string | null; options: string[]; votes: Record<string, number> }

/** conversations/{cid}/messages/{messageId}. Create-only shape enforced by firestore.rules (validNewMessage). */
export interface MessageDoc {
  senderId: string;
  type: MessageType;
  text: string;                 // message text or media caption; "" once deleted for everyone
  createdAt: Timestamp;         // server timestamp: ordering and receipts
  updatedAt: Timestamp;         // server timestamp, bumped by every edit/reaction/vote/delete
  clientCreatedAt: number;      // sender clock, milliseconds
  editedAt: Timestamp | null;
  deletedForEveryone: boolean;
  forwarded: boolean;
  mentions: string[];           // UIDs, max 50
  reactions: Record<string, string>; // uid -> emoji; each user writes only their own key
  replyTo?: ReplyToDoc;
  media?: MediaDoc;
  contact?: { name: string | null; phone: string | null };
  location?: { lat: number; lng: number; liveId?: string; liveUntil?: number };
  poll?: PollDoc;
  expiresAt?: Timestamp;        // disappearing messages (TTL)
  system?: SystemDoc;           // type SYSTEM (group events)
  edits?: EditDoc[];            // earlier versions of an edited text, oldest first (members only)
}

/** One earlier version of an edited message: the replaced text and when the edit happened. */
export interface EditDoc { text: string; at: Timestamp }

/** presence/{uid}: heartbeat every 45 s while active; stale after 100 s. */
export interface PresenceDoc { online: boolean; heartbeatAt?: Timestamp; lastSeen?: Timestamp }

// ================================================================================ view models

export interface ChatUser {
  uid: string;
  username: string;
  uniqueId10: string;
  displayName: string;
  avatarColorHex: string;
  createdAt: number;
}

export interface Receipt { deliveredUpTo: number; readUpTo: number }

export interface Conversation {
  id: string;
  /** False for a direct chat that has no document yet: the first message creates it. */
  exists: boolean;
  type: "direct" | "group";
  participants: string[];
  /** Direct chats: the other participant. */
  peerId: string | null;
  title: string | null;
  description: string | null;
  photoUrl: string | null;
  admins: string[];
  createdBy: string | null;
  onlyAdminsCanSend: boolean;
  onlyAdminsCanEditInfo: boolean;
  disappearingSeconds: number;
  lastMessage: { id: string; senderId: string; type: MessageType; preview: string; at: number } | null;
  lastMessageAt: number;
  updatedAt: number;
  receipts: Record<string, Receipt>;
  typing: Record<string, number>;
}

export interface ChatMessage {
  id: string;
  conversationId: string;
  senderId: string;
  type: MessageType;
  text: string;
  /** Server time in ms (estimated locally while the write is pending). */
  createdAt: number;
  clientCreatedAt: number;
  /** True until the server has acknowledged the write ("sending" clock icon). */
  pending: boolean;
  editedAt: number;
  updatedAt: number;
  deleted: boolean;
  forwarded: boolean;
  mentions: string[];
  reactions: Record<string, string>;
  replyTo: ReplyToDoc | null;
  media: MediaDoc | null;
  contact: { name: string | null; phone: string | null } | null;
  location: { lat: number; lng: number } | null;
  poll: PollDoc | null;
  expiresAt: number;
  system: SystemDoc | null;
  /** Earlier versions (text and edit time in ms), oldest first; empty if never edited or deleted. */
  edits: { text: string; at: number }[];
}

export type DeliveryStatus = "sending" | "sent" | "delivered" | "read";

// ================================================================================ mapping helpers

/** Milliseconds, floored like Android's Timestamp.toDate().time, so receipt comparisons agree across clients. */
export function millis(value: unknown): number {
  if (value && typeof (value as Timestamp).toMillis === "function") return Math.floor((value as Timestamp).toMillis());
  if (typeof value === "number" && Number.isFinite(value)) return Math.floor(value);
  return 0;
}

const strings = (value: unknown): string[] => (Array.isArray(value) ? value.filter((v): v is string => typeof v === "string") : []);

function stringMap(value: unknown): Record<string, string> {
  const out: Record<string, string> = {};
  if (value && typeof value === "object") for (const [k, v] of Object.entries(value)) if (typeof v === "string") out[k] = v;
  return out;
}

const asType = (value: unknown): MessageType =>
  (MESSAGE_TYPES as readonly string[]).includes(value as string) ? (value as MessageType) : "TEXT";

const obj = <T>(value: unknown): T | null => (value && typeof value === "object" ? (value as T) : null);

export function toUser(snap: DocumentSnapshot): ChatUser | null {
  const d = snap.data({ serverTimestamps: "estimate" }) as Partial<UserDoc> | undefined;
  if (!d) return null;
  return {
    uid: snap.id,
    username: d.username ?? "",
    uniqueId10: d.uniqueId10 ?? "",
    displayName: d.displayName ?? "",
    avatarColorHex: d.avatarColorHex ?? "#4F46E5",
    createdAt: millis(d.createdAt),
  };
}

export function toConversation(snap: DocumentSnapshot, me: string): Conversation | null {
  const d = snap.data({ serverTimestamps: "estimate" }) as Partial<ConversationDoc> | undefined;
  if (!d) return null;
  const participants = strings(d.participants);
  const isGroup = d.type === Fs.TYPE_GROUP;
  const receipts: Record<string, Receipt> = {};
  for (const [uid, v] of Object.entries(obj<Record<string, ReceiptDoc>>(d.receipts) ?? {})) {
    receipts[uid] = { deliveredUpTo: millis(v?.d), readUpTo: millis(v?.r) };
  }
  const typing: Record<string, number> = {};
  for (const [uid, v] of Object.entries(obj<Record<string, unknown>>(d.typing) ?? {})) {
    const at = millis(v);
    if (at > 0) typing[uid] = at;
  }
  const last = obj<Partial<LastMessageDoc>>(d.lastMessage);
  return {
    id: snap.id,
    exists: true,
    type: isGroup ? "group" : "direct",
    participants,
    peerId: isGroup ? null : participants.find((p) => p !== me) ?? null,
    title: isGroup ? d.title ?? "" : null,
    description: d.description ?? null,
    photoUrl: d.photoUrl ?? null,
    admins: strings(d.admins),
    createdBy: d.createdBy ?? null,
    onlyAdminsCanSend: d.onlyAdminsCanSend ?? false,
    onlyAdminsCanEditInfo: d.onlyAdminsCanEditInfo ?? true,
    disappearingSeconds: typeof d.disappearingSeconds === "number" ? d.disappearingSeconds : 0,
    lastMessage: last
      ? { id: last.id ?? "", senderId: last.senderId ?? "", type: asType(last.type), preview: last.preview ?? "", at: millis(d.lastMessageAt) || millis(last.createdAt) }
      : null,
    lastMessageAt: millis(d.lastMessageAt),
    updatedAt: millis(d.updatedAt),
    receipts,
    typing,
  };
}

export function toMessage(snap: DocumentSnapshot, conversationId: string): ChatMessage | null {
  const d = snap.data({ serverTimestamps: "estimate" }) as Partial<MessageDoc> | undefined;
  if (!d) return null;
  const deleted = d.deletedForEveryone === true;
  const clientCreatedAt = typeof d.clientCreatedAt === "number" ? d.clientCreatedAt : 0;
  return {
    id: snap.id,
    conversationId,
    senderId: d.senderId ?? "",
    type: asType(d.type),
    text: deleted ? "" : d.text ?? "",
    createdAt: millis(d.createdAt) || clientCreatedAt,
    clientCreatedAt,
    pending: snap.metadata.hasPendingWrites,
    editedAt: millis(d.editedAt),
    updatedAt: millis(d.updatedAt),
    deleted,
    forwarded: d.forwarded === true,
    mentions: strings(d.mentions),
    reactions: deleted ? {} : stringMap(d.reactions),
    replyTo: obj<ReplyToDoc>(d.replyTo),
    media: deleted ? null : obj<MediaDoc>(d.media),
    contact: obj(d.contact),
    location: obj(d.location),
    poll: obj<PollDoc>(d.poll),
    expiresAt: millis(d.expiresAt),
    system: obj<SystemDoc>(d.system),
    edits: deleted || !Array.isArray(d.edits) ? [] : d.edits
      .filter((e): e is EditDoc => !!e && typeof (e as EditDoc).text === "string")
      .map((e) => ({ text: e.text, at: millis(e.at) })),
  };
}

// ================================================================================ documents to write

/** Port of RemoteMappers.userProfileMap (users/{uid} create). */
export function userProfileData(uid: string, username: string, uniqueId10: string, displayName: string, colorHex: string): DocumentData {
  return {
    uid,
    username,
    uniqueId10,
    displayName,
    displayNameLower: displayName.trim().toLowerCase(),
    avatarColorHex: colorHex,
    lastSeenVisibility: "everyone",
    photoVisibility: "everyone",
    aboutVisibility: "everyone",
    createdAt: serverTimestamp(),
  };
}

/** A message about to be sent (input of {@link messageCreateData}). */
export interface OutgoingMessage {
  id: string;
  senderId: string;
  type: MessageType;
  text: string;
  clientCreatedAt: number;
  forwarded?: boolean;
  mentions?: string[];
  replyTo?: ReplyToDoc | null;
  media?: MediaDoc | null;
  contact?: { name: string | null; phone: string | null } | null;
  location?: { lat: number; lng: number } | null;
  poll?: { question: string; options: string[] } | null;
  /** 0 = does not disappear. */
  expiresAtMillis?: number;
  system?: SystemDoc | null;
}

/** Port of RemoteMappers.messageCreateMap: the complete document written when a message is sent. */
export function messageCreateData(m: OutgoingMessage): DocumentData {
  const data: Record<string, unknown> = {
    senderId: m.senderId,
    type: m.type,
    text: m.text,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    clientCreatedAt: m.clientCreatedAt,
    editedAt: null,
    deletedForEveryone: false,
    forwarded: m.forwarded ?? false,
    mentions: m.mentions ?? [],
    reactions: {},
  };
  if (m.replyTo) {
    data.replyTo = { id: m.replyTo.id, senderId: m.replyTo.senderId, type: m.replyTo.type, preview: preview(m.replyTo.preview, 120) };
  }
  if (isMediaType(m.type) && m.media) data.media = { ...m.media };
  if (m.type === "CONTACT" && m.contact) data.contact = { name: m.contact.name, phone: m.contact.phone };
  if (m.type === "LOCATION" && m.location) data.location = { lat: m.location.lat, lng: m.location.lng };
  if (m.type === "POLL" && m.poll) data.poll = { question: m.poll.question, options: m.poll.options, votes: {} };
  if (m.expiresAtMillis && m.expiresAtMillis > 0) data.expiresAt = Timestamp.fromMillis(m.expiresAtMillis);
  if (m.type === "SYSTEM" && m.system) data.system = m.system;
  return data;
}

/** conversations/{cid}.lastMessage for a message being sent (ChatRepository.pushMessage). */
export function lastMessageData(m: OutgoingMessage): Record<string, string | FieldValue> {
  return {
    id: m.id,
    senderId: m.senderId,
    type: m.type,
    preview: preview(previewText({ type: m.type, text: m.text, media: m.media, contact: m.contact, poll: m.poll }), 100),
    createdAt: serverTimestamp(),
  };
}

/** DisappearingPolicy.expiresAt. */
export const expiresAtFor = (createdAt: number, seconds: number): number => (seconds > 0 ? createdAt + seconds * 1000 : 0);

/**
 * Port of DeliveryPolicy.fromReceipts: state of my message from the recipients' watermarks.
 * Returns null for incoming messages. Groups: every recipient must have delivered/read it.
 */
export function deliveryStatus(m: ChatMessage, conv: Conversation, me: string): DeliveryStatus | null {
  if (m.senderId !== me) return null;
  if (m.pending) return "sending";
  const recipients = conv.participants.filter((p) => p !== m.senderId);
  if (recipients.length === 0) return "sent";
  const r = (uid: string): Receipt => conv.receipts[uid] ?? { deliveredUpTo: 0, readUpTo: 0 };
  if (recipients.every((uid) => r(uid).readUpTo >= m.createdAt)) return "read";
  if (recipients.every((uid) => Math.max(r(uid).deliveredUpTo, r(uid).readUpTo) >= m.createdAt)) return "delivered";
  return "sent";
}
