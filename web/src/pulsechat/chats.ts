/** Conversations (ChatRepository.kt / SyncManager.kt). */
import {
  collection, doc, onSnapshot, query, serverTimestamp, where, writeBatch, type FirestoreError, type Unsubscribe,
} from "firebase/firestore";
import { PulseChatError, requireUid, type PulseChat } from "./firebase";
import { directConversationId, newGroupId, newMessageId } from "./ids";
import { DISAPPEARING_OPTIONS, Fs, messageCreateData, toConversation, type Conversation } from "./schema";

/**
 * My conversations in real time, newest first. Same query as Android (`participants`
 * array-contains me, sorted on the client), so no composite index is needed.
 * Direct chats without any message are hidden, like on Android.
 */
export function observeConversations(
  pc: PulseChat,
  callback: (conversations: Conversation[]) => void,
  onError?: (error: FirestoreError) => void,
): Unsubscribe {
  const me = requireUid(pc);
  const q = query(collection(pc.db, Fs.CONVERSATIONS), where("participants", "array-contains", me));
  return onSnapshot(
    q,
    (snap) => {
      const list = snap.docs
        .map((d) => toConversation(d, me))
        .filter((c): c is Conversation => c !== null && (c.type === "group" || c.lastMessage !== null))
        .sort((a, b) => (b.lastMessageAt || b.updatedAt) - (a.lastMessageAt || a.updatedAt));
      callback(list);
    },
    onError,
  );
}

/**
 * The direct chat with `peerUid`. Nothing is written yet: the first message creates the
 * conversation document (same batch as the message), like on Android.
 * Note: the rules deny reading a conversation (or its messages) before it exists. Use the
 * entry from observeConversations() once it appears, and this draft until then.
 */
export function directChat(pc: PulseChat, peerUid: string): Conversation {
  const me = requireUid(pc);
  const participants = [me, peerUid].sort();
  return {
    id: directConversationId(me, peerUid),
    exists: false,
    type: "direct",
    participants,
    peerId: peerUid,
    title: null,
    description: null,
    photoUrl: null,
    admins: [],
    createdBy: null,
    onlyAdminsCanSend: false,
    onlyAdminsCanEditInfo: true,
    disappearingSeconds: 0,
    lastMessage: null,
    lastMessageAt: 0,
    updatedAt: 0,
    receipts: {},
    typing: {},
  };
}

/** Creates a group exactly like ChatRepository.createGroup (group doc + "created" system message). */
export async function createGroup(
  pc: PulseChat,
  input: { title: string; description?: string; memberIds: string[]; disappearingSeconds?: number },
): Promise<string> {
  const me = requireUid(pc);
  const title = input.title.trim();
  const description = (input.description ?? "").trim();
  const participants = [...new Set([me, ...input.memberIds])];
  const seconds = input.disappearingSeconds ?? 0;
  if (title.length === 0 || title.length > 100) throw new PulseChatError("INVALID_INPUT", "title must be 1..100 characters");
  if (description.length > 512) throw new PulseChatError("INVALID_INPUT", "description must be at most 512 characters");
  if (participants.length < 2 || participants.length > 256) throw new PulseChatError("INVALID_INPUT", "a group needs 2..256 members");
  if (!(DISAPPEARING_OPTIONS as readonly number[]).includes(seconds)) throw new PulseChatError("INVALID_INPUT", "invalid disappearing timer");
  const cid = newGroupId();
  const systemId = newMessageId();
  const conversationRef = doc(pc.db, Fs.CONVERSATIONS, cid);
  const batch = writeBatch(pc.db);
  batch.set(conversationRef, {
    type: Fs.TYPE_GROUP,
    participants,
    admins: [me],
    createdBy: me,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    title,
    description,
    photoUrl: null,
    onlyAdminsCanSend: false,
    onlyAdminsCanEditInfo: true,
    disappearingSeconds: seconds,
    lastMessageAt: serverTimestamp(),
    lastMessage: { id: systemId, senderId: me, type: "SYSTEM", preview: "", createdAt: serverTimestamp() },
  });
  batch.set(
    doc(conversationRef, Fs.MESSAGES, systemId),
    messageCreateData({
      id: systemId, senderId: me, type: "SYSTEM", text: "", clientCreatedAt: Date.now(),
      system: { event: "created", actor: me, targets: [], value: title },
    }),
  );
  await batch.commit();
  return cid;
}
