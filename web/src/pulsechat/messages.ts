/** Messages: real-time reading, sending, media, receipts, typing, reactions, edit and delete (ChatRepository.kt). */
import {
  collection, deleteField, doc, onSnapshot, orderBy, limitToLast, query, serverTimestamp, Timestamp, updateDoc, writeBatch,
  type FirestoreError, type Unsubscribe,
} from "firebase/firestore";
import { getBytes, getDownloadURL, ref as storageRef, uploadBytes, uploadBytesResumable } from "firebase/storage";
import { PulseChatError, requireUid, type PulseChat } from "./firebase";
import { newMessageId } from "./ids";
import {
  MEDIA_MAX_BYTES, downloadFromStore, isStorageUnavailable, isStoreRef, markStorageUnavailable, mediaIdFor, storageLikelyAvailable, toBytes,
  uploadToStore,
} from "./media";
import {
  EDIT_WINDOW_MS, DELETE_FOR_EVERYONE_WINDOW_MS, Fs, MAX_TEXT_LENGTH, expiresAtFor, lastMessageData, messageCreateData, toMessage,
  type ChatMessage, type Conversation, type OutgoingMessage, type ReplyToDoc, type MediaDoc,
} from "./schema";
import { extensionFor, maxBytesFor, preview, previewText, typeForMime } from "./text";
import { readReceiptsEnabled } from "./users";

const conversationRef = (pc: PulseChat, cid: string) => doc(pc.db, Fs.CONVERSATIONS, cid);
const messageRef = (pc: PulseChat, cid: string, mid: string) => doc(pc.db, Fs.CONVERSATIONS, cid, Fs.MESSAGES, mid);

/**
 * The newest `limit` messages of a conversation in real time, oldest first. Includes my pending
 * writes (`pending: true`) and their server acknowledgement. Expired disappearing messages are
 * left out. Only call it for conversations that exist (see directChat()).
 */
export function observeMessages(
  pc: PulseChat,
  conversationId: string,
  callback: (messages: ChatMessage[]) => void,
  options: { limit?: number; onError?: (error: FirestoreError) => void } = {},
): Unsubscribe {
  const q = query(collection(pc.db, Fs.CONVERSATIONS, conversationId, Fs.MESSAGES), orderBy("createdAt", "asc"), limitToLast(options.limit ?? 50));
  return onSnapshot(
    q,
    { includeMetadataChanges: true },
    (snap) => {
      const now = Date.now();
      const messages = snap.docs
        .map((d) => toMessage(d, conversationId))
        .filter((m): m is ChatMessage => m !== null && !(m.expiresAt > 0 && m.expiresAt <= now))
        .sort((a, b) => a.createdAt - b.createdAt || a.id.localeCompare(b.id));
      callback(messages);
    },
    options.onError,
  );
}

function assertCanSend(conv: Conversation, me: string): void {
  if (!conv.participants.includes(me)) throw new PulseChatError("NOT_ALLOWED", "not a participant");
  if (conv.type === "group" && conv.onlyAdminsCanSend && !conv.admins.includes(me)) throw new PulseChatError("NOT_ALLOWED", "only admins can send");
}

const replyOf = (m: ChatMessage | null | undefined): ReplyToDoc | null =>
  m ? { id: m.id, senderId: m.senderId, type: m.type, preview: preview(previewText(m) || (m.media?.name ?? ""), 120) } : null;

/**
 * Writes the message and the chat preview in one batch, exactly like ChatRepository.pushMessage.
 * For a direct chat the conversation document is created (or merged) in the same batch, which the
 * rules allow only with the deterministic dm_ ID and the two sorted participants.
 */
async function commitMessage(pc: PulseChat, conv: Conversation, m: OutgoingMessage): Promise<void> {
  const convRef = conversationRef(pc, conv.id);
  const batch = writeBatch(pc.db);
  if (conv.type === "direct") batch.set(convRef, { type: Fs.TYPE_DIRECT, participants: [...conv.participants].sort() }, { merge: true });
  batch.set(messageRef(pc, conv.id, m.id), messageCreateData(m));
  batch.update(convRef, {
    lastMessage: lastMessageData(m),
    lastMessageAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    [`typing.${m.senderId}`]: deleteField(),
  });
  pc.cache.typing.set(conv.id, { typing: false, at: 0 });
  await batch.commit();
}

/**
 * Sends a text message. Resolves with the message ID once the server has accepted it (offline:
 * when the connection is back). The message appears in observeMessages() immediately.
 */
export async function sendText(
  pc: PulseChat,
  conv: Conversation,
  text: string,
  options: { replyTo?: ChatMessage | null; mentions?: string[] } = {},
): Promise<string> {
  const me = requireUid(pc);
  if (text.trim().length === 0) throw new PulseChatError("EMPTY");
  if (text.length > MAX_TEXT_LENGTH) throw new PulseChatError("TOO_LONG");
  assertCanSend(conv, me);
  const now = Date.now();
  const message: OutgoingMessage = {
    id: newMessageId(),
    senderId: me,
    type: "TEXT",
    text: text.trim(),
    clientCreatedAt: now,
    mentions: [...new Set(options.mentions ?? [])],
    replyTo: replyOf(options.replyTo),
    expiresAtMillis: expiresAtFor(now, conv.disappearingSeconds),
  };
  await commitMessage(pc, conv, message);
  return message.id;
}

export interface MediaInput {
  data: Blob | Uint8Array | ArrayBuffer;
  /** e.g. "image/jpeg", "audio/webm;codecs=opus". */
  mime: string;
  fileName?: string;
  caption?: string;
  /** Send as a file (DOCUMENT) even if it is an image/video/audio. */
  asDocument?: boolean;
  width?: number;
  height?: number;
  durationMs?: number;
  /** Voice notes: comma-separated amplitudes (0..100), shown as the waveform on Android. */
  waveform?: string | null;
}

const byteLength = (data: Blob | Uint8Array | ArrayBuffer): number =>
  data instanceof Uint8Array ? data.byteLength : data instanceof ArrayBuffer ? data.byteLength : data.size;

/**
 * Uploads a file and then sends the message with the same `media` fields Android writes. Like
 * Android, uploads go to chat-media/{cid}/{myUid}/{messageId}.{ext} in Cloud Storage (the path
 * storage.rules allow) when the project has a usable bucket, otherwise to the built-in media store
 * (media.ts). `store: "builtin"` skips Cloud Storage.
 */
export async function sendMedia(
  pc: PulseChat,
  conv: Conversation,
  input: MediaInput,
  options: { replyTo?: ChatMessage | null; onProgress?: (percent: number) => void; store?: "auto" | "builtin" } = {},
): Promise<string> {
  const me = requireUid(pc);
  assertCanSend(conv, me);
  const type = input.asDocument ? "DOCUMENT" : typeForMime(input.mime);
  const size = byteLength(input.data);
  if (size === 0) throw new PulseChatError("EMPTY");
  if (size > maxBytesFor(type)) throw new PulseChatError("TOO_LARGE");
  const id = newMessageId();
  let path = `chat-media/${conv.id}/${me}/${id}.${extensionFor(input.mime, input.fileName ?? "")}`;
  let url: string | null = null;
  if (options.store !== "builtin" && storageLikelyAvailable(pc)) {
    try {
      url = await uploadToStorage(pc, path, input, options.onProgress);
    } catch (e) {
      if (!isStorageUnavailable(e)) throw e;
      markStorageUnavailable(pc);
    }
  }
  if (url === null) {
    if (size > MEDIA_MAX_BYTES) throw new PulseChatError("TOO_LARGE");
    url = await uploadToStore(pc, await toBytes(input.data), input.mime, mediaIdFor(id), options.onProgress);
    path = url;
  }
  const now = Date.now();
  const media: MediaDoc = {
    url,
    path,
    mime: input.mime,
    size,
    name: input.fileName ?? null,
    durationMs: Math.round(input.durationMs ?? 0),
    width: Math.round(input.width ?? 0),
    height: Math.round(input.height ?? 0),
    waveform: input.waveform ?? null,
  };
  await commitMessage(pc, conv, {
    id, senderId: me, type, text: (input.caption ?? "").trim(), clientCreatedAt: now, replyTo: replyOf(options.replyTo), media,
    expiresAtMillis: expiresAtFor(now, conv.disappearingSeconds),
  });
  return id;
}

async function uploadToStorage(pc: PulseChat, path: string, input: MediaInput, onProgress?: (percent: number) => void): Promise<string> {
  const fileRef = storageRef(pc.storage, path);
  const metadata = { contentType: input.mime };
  if (onProgress) {
    await new Promise<void>((resolve, reject) => {
      uploadBytesResumable(fileRef, input.data, metadata).on(
        "state_changed",
        (s) => onProgress(Math.round((100 * s.bytesTransferred) / Math.max(1, s.totalBytes))),
        reject,
        () => resolve(),
      );
    });
  } else {
    await uploadBytes(fileRef, input.data, metadata);
  }
  return getDownloadURL(fileRef);
}

/**
 * URL to display/download media. Prefers the Storage path (always current), like Android. Media in
 * the built-in store is downloaded and returned as an object URL (revoke it when done).
 */
export async function mediaUrl(pc: PulseChat, media: MediaDoc): Promise<string> {
  if (isStoreRef(media.url) || isStoreRef(media.path)) {
    const bytes = await mediaBytes(pc, media);
    return URL.createObjectURL(new Blob([bytes as Uint8Array<ArrayBuffer>], { type: media.mime || "application/octet-stream" }));
  }
  if (media.path) {
    try {
      return await getDownloadURL(storageRef(pc.storage, media.path));
    } catch {
      // fall back to the stored URL
    }
  }
  return media.url;
}

/** The file's bytes, from Cloud Storage or the built-in media store. */
export async function mediaBytes(pc: PulseChat, media: MediaDoc, onProgress?: (percent: number) => void): Promise<Uint8Array> {
  const store = isStoreRef(media.url) ? media.url : isStoreRef(media.path) ? media.path : null;
  if (store) return downloadFromStore(pc, store, onProgress);
  return new Uint8Array(await getBytes(storageRef(pc.storage, media.path)));
}

// ---------------------------------------------------------------------------------- receipts

const latestIncoming = (messages: ChatMessage[], me: string): number =>
  messages.reduce((max, m) => (m.senderId !== me && !m.pending && m.createdAt > max ? m.createdAt : max), 0);

/**
 * Marks the conversation read up to the newest incoming message (SyncManager.markRead):
 * writes receipts.{me}.r and .d, or only .d when I turned read receipts off (setting shared with Android).
 */
export async function markRead(pc: PulseChat, conv: Conversation, messages: ChatMessage[]): Promise<void> {
  const me = requireUid(pc);
  const upTo = latestIncoming(messages, me);
  if (!conv.exists || upTo <= 0) return;
  const mine = conv.receipts[me] ?? { deliveredUpTo: 0, readUpTo: 0 };
  const at = Timestamp.fromMillis(upTo);
  if (await readReceiptsEnabled(pc)) {
    if (upTo > mine.readUpTo) await updateDoc(conversationRef(pc, conv.id), { [`receipts.${me}.r`]: at, [`receipts.${me}.d`]: at });
  } else if (upTo > mine.deliveredUpTo) {
    await updateDoc(conversationRef(pc, conv.id), { [`receipts.${me}.d`]: at });
  }
}

/** Delivery receipt (two grey ticks for the sender) for messages that reached this device. */
export async function markDelivered(pc: PulseChat, conv: Conversation, messages: ChatMessage[]): Promise<void> {
  const me = requireUid(pc);
  const upTo = latestIncoming(messages, me);
  const mine = conv.receipts[me] ?? { deliveredUpTo: 0, readUpTo: 0 };
  if (!conv.exists || upTo <= Math.max(mine.deliveredUpTo, mine.readUpTo)) return;
  await updateDoc(conversationRef(pc, conv.id), { [`receipts.${me}.d`]: Timestamp.fromMillis(upTo) });
}

/**
 * Delivery receipt straight from the chat list (no message listener needed): the newest message
 * reached this device. lastMessageAt and that message's createdAt are the same server time,
 * because both are written by one batch.
 */
export async function markConversationDelivered(pc: PulseChat, conv: Conversation): Promise<void> {
  const me = requireUid(pc);
  const last = conv.lastMessage;
  if (!conv.exists || !last || last.senderId === me || last.at <= 0) return;
  const mine = conv.receipts[me] ?? { deliveredUpTo: 0, readUpTo: 0 };
  if (last.at <= Math.max(mine.deliveredUpTo, mine.readUpTo)) return;
  await updateDoc(conversationRef(pc, conv.id), { [`receipts.${me}.d`]: Timestamp.fromMillis(last.at) });
}

// ---------------------------------------------------------------------------------- typing

export const TYPING_THROTTLE_MS = 3_000;
export const TYPING_TIMEOUT_MS = 6_000;

/** Publishes my typing state (throttled to one write per 3 s), like ChatRepository.setTyping. */
export async function setTyping(pc: PulseChat, conv: Conversation, typing: boolean): Promise<void> {
  const me = requireUid(pc);
  if (!conv.exists) return;
  const now = Date.now();
  const last = pc.cache.typing.get(conv.id) ?? { typing: false, at: 0 };
  if (typing && last.typing && now - last.at < TYPING_THROTTLE_MS) return;
  if (!typing && !last.typing) return;
  pc.cache.typing.set(conv.id, { typing, at: now });
  await updateDoc(conversationRef(pc, conv.id), { [`typing.${me}`]: typing ? serverTimestamp() : deleteField() });
}

// ---------------------------------------------------------------------------------- reactions, edit, delete

/** Sets (or with null removes) my reaction. Each user can only change their own. */
export async function react(pc: PulseChat, conv: Conversation, message: ChatMessage, emoji: string | null): Promise<void> {
  const me = requireUid(pc);
  if (emoji !== null && (emoji.length === 0 || emoji.length > 16)) throw new PulseChatError("INVALID_INPUT", "emoji");
  const batch = writeBatch(pc.db);
  batch.update(messageRef(pc, conv.id, message.id), { [`reactions.${me}`]: emoji ?? deleteField(), updatedAt: serverTimestamp() });
  batch.update(conversationRef(pc, conv.id), { updatedAt: serverTimestamp() });
  await batch.commit();
}

/** Authors may edit their own text messages for 15 minutes (firestore.rules editByAuthor). */
export async function editMessage(pc: PulseChat, conv: Conversation, message: ChatMessage, newText: string): Promise<void> {
  const me = requireUid(pc);
  const text = newText.trim();
  if (message.senderId !== me || message.type !== "TEXT" || message.deleted) throw new PulseChatError("NOT_ALLOWED");
  if (Date.now() - message.createdAt > EDIT_WINDOW_MS) throw new PulseChatError("EDIT_WINDOW_PASSED");
  if (text.length === 0) throw new PulseChatError("EMPTY");
  if (text.length > MAX_TEXT_LENGTH) throw new PulseChatError("TOO_LONG");
  const batch = writeBatch(pc.db);
  batch.update(messageRef(pc, conv.id, message.id), { text, editedAt: serverTimestamp(), updatedAt: serverTimestamp() });
  batch.update(conversationRef(pc, conv.id), {
    updatedAt: serverTimestamp(),
    ...(conv.lastMessage?.id === message.id ? { "lastMessage.preview": preview(text, 100) } : {}),
  });
  await batch.commit();
}

/** Authors may delete their messages for everyone within 48 hours; the content is removed. */
export async function deleteForEveryone(pc: PulseChat, conv: Conversation, message: ChatMessage): Promise<void> {
  const me = requireUid(pc);
  if (message.senderId !== me || message.deleted) throw new PulseChatError("NOT_ALLOWED");
  if (Date.now() - message.createdAt > DELETE_FOR_EVERYONE_WINDOW_MS) throw new PulseChatError("EDIT_WINDOW_PASSED");
  const batch = writeBatch(pc.db);
  batch.update(messageRef(pc, conv.id, message.id), {
    deletedForEveryone: true, text: "", media: deleteField(), replyTo: deleteField(), poll: deleteField(), contact: deleteField(),
    location: deleteField(), mentions: [], reactions: {}, updatedAt: serverTimestamp(),
  });
  batch.update(conversationRef(pc, conv.id), {
    updatedAt: serverTimestamp(),
    ...(conv.lastMessage?.id === message.id ? { "lastMessage.preview": "" } : {}),
  });
  await batch.commit();
}
