/**
 * "Web peer" for the Android ↔ Web end-to-end test (CI e2e job, WebInteropE2ETest.kt).
 *
 * A PulseChat web client (this data layer on the Firebase JS SDK) that runs on the CI host against
 * the same Firebase emulators as the Android emulator (project gen-lang-client-0426516505, the
 * app's named database and storage bucket) and answers Android users in real time:
 *  - creates the account "webonly" on the web and signs out (Android then signs in with it);
 *  - signs up "webpeer" and, for every incoming message: writes a read receipt, replies to TEXT
 *    with "echo: <text>" (quoting it) and sends IMAGE uploads back after downloading them from Storage.
 * Prints WEB_PEER_READY when it is listening. Stop it with SIGTERM.
 */
import { getBytes, ref as storageRef } from "firebase/storage";
import type { Unsubscribe } from "firebase/firestore";
import {
  ANDROID_FIREBASE_PROJECT, ANDROID_FIRESTORE_DATABASE_ID, disposePulseChat, initPulseChat, markRead, observeConversations, observeMessages,
  sendMedia, sendText, signOut, signUp, startPresence, type ChatMessage, type Conversation, type PulseChatWebConfig,
} from "../src/pulsechat";

const PASSWORD = process.env.WEB_PEER_PASSWORD ?? "Passw0rd1";
const PEER = process.env.WEB_PEER_USERNAME ?? "webpeer";
const WEB_ONLY = process.env.WEB_ONLY_USERNAME ?? "webonly";
const HOST = process.env.PULSECHAT_EMULATOR_HOST ?? "127.0.0.1";

const config = (appName: string): PulseChatWebConfig => ({
  appName,
  firebase: {
    apiKey: "demo-api-key",
    authDomain: ANDROID_FIREBASE_PROJECT.authDomain,
    projectId: ANDROID_FIREBASE_PROJECT.projectId,
    storageBucket: ANDROID_FIREBASE_PROJECT.storageBucket,
    appId: "1:1:web:1",
  },
  firestoreDatabaseId: ANDROID_FIRESTORE_DATABASE_ID,
  emulators: { host: HOST },
});

const log = (message: string) => console.log(`[web-peer ${new Date().toISOString()}] ${message}`);

async function main(): Promise<void> {
  // 1. An account that only ever existed on the web.
  const webOnly = initPulseChat(config("web-only"));
  await signUp(webOnly, { displayName: "Web Only", username: WEB_ONLY, password: PASSWORD });
  await signOut(webOnly);
  await disposePulseChat(webOnly);
  log(`created web-only account @${WEB_ONLY}`);

  // 2. The peer that answers Android users.
  const pc = initPulseChat(config("web-peer"));
  const me = await signUp(pc, { displayName: "Web Peer", username: PEER, password: PASSWORD });
  const stopPresence = startPresence(pc);
  const conversations = new Map<string, Conversation>();
  const watching = new Map<string, Unsubscribe>();
  const handled = new Set<string>();

  const answer = async (cid: string, messages: ChatMessage[]): Promise<void> => {
    for (const m of messages) {
      if (m.senderId === me || m.pending || handled.has(m.id) || m.type === "SYSTEM") continue;
      handled.add(m.id);
      log(`received ${m.type} ${m.id} in ${cid} from ${m.senderId}: "${m.text}"`);
      try {
        const conv = conversations.get(cid)!;
        await markRead(pc, conv, messages);
        if (m.type === "TEXT") {
          await sendText(pc, conv, `echo: ${m.text}`, { replyTo: m });
        } else if (m.type === "IMAGE" && m.media) {
          const bytes = new Uint8Array(await getBytes(storageRef(pc.storage, m.media.path)));
          log(`downloaded ${bytes.byteLength} bytes from ${m.media.path}`);
          await sendMedia(pc, conv, {
            data: bytes, mime: m.media.mime, fileName: `echo-${m.media.name ?? "photo"}`, caption: `echo: ${m.text}`,
            width: m.media.width, height: m.media.height,
          });
        }
        log(`answered ${m.id}`);
      } catch (e) {
        log(`failed to answer ${m.id}: ${e instanceof Error ? e.stack ?? e.message : String(e)}`);
      }
    }
  };

  const stopConversations = observeConversations(
    pc,
    (list) => {
      for (const conv of list) {
        conversations.set(conv.id, conv);
        if (!watching.has(conv.id)) {
          log(`watching ${conv.type} chat ${conv.id}`);
          watching.set(conv.id, observeMessages(pc, conv.id, (messages) => void answer(conv.id, messages), {
            onError: (e) => log(`messages(${conv.id}) listener: ${e.code}`),
          }));
        }
      }
    },
    (e) => log(`conversations listener: ${e.code} ${e.message}`),
  );

  const shutdown = async () => {
    log("stopping");
    stopConversations();
    watching.forEach((stop) => stop());
    await stopPresence();
    await disposePulseChat(pc);
    process.exit(0);
  };
  process.on("SIGTERM", () => void shutdown());
  process.on("SIGINT", () => void shutdown());
  console.log(`WEB_PEER_READY uid=${me} username=${PEER}`);
}

main().catch((e) => {
  console.error("web peer failed:", e);
  process.exit(1);
});
