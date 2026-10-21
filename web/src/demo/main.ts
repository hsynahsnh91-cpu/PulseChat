/**
 * Minimal PulseChat web client on top of src/pulsechat (no framework). It demonstrates how a web UI
 * uses the data layer against the Android app's backend; a production web app would bring its own
 * framework, routing, i18n and design system. All user content is inserted as text (never HTML).
 */
import type { Unsubscribe } from "firebase/firestore";
import {
  completeProfile, configFromEnv, deliveryStatus, directChat, getUser, initPulseChat, markConversationDelivered, markRead, mediaUrl,
  observeConversations, observeMessages, observePresence, observeSession, PulseChatAuthError, PulseChatError, searchUsers, sendMedia,
  sendText, setTyping, signIn, signInWithGoogle, signOut, signUp, startPresence, TYPING_TIMEOUT_MS,
  type ChatMessage, type ChatUser, type Conversation, type DeliveryStatus, type MediaDoc, type PulseChat, type Session,
} from "../pulsechat";

type Child = Node | string | null | undefined | false;

function h<K extends keyof HTMLElementTagNameMap>(tag: K, props: Record<string, unknown> = {}, ...children: Child[]): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === undefined || value === null || value === false) continue;
    if (key.startsWith("on") && typeof value === "function") el.addEventListener(key.slice(2).toLowerCase(), value as EventListener);
    else if (key === "className") el.className = String(value);
    else el.setAttribute(key, value === true ? "" : String(value));
  }
  for (const child of children) if (child !== null && child !== undefined && child !== false) el.append(child);
  return el;
}

const root = document.getElementById("app")!;

const AUTH_MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: "Wrong username/ID or password.",
  USERNAME_TAKEN: "This username is already taken.",
  WEAK_PASSWORD: "Choose a stronger password.",
  NETWORK: "No connection to Firebase.",
  TOO_MANY_REQUESTS: "Too many attempts. Try again later.",
  PROVIDER_DISABLED: "This sign-in method is disabled in the Firebase console.",
  CANCELLED: "Cancelled.",
  BACKEND_NOT_READY: "The backend rejected the request. Are firestore.rules deployed?",
  INVALID_INPUT: "Please check your input",
  UNKNOWN: "Something went wrong.",
};

function describeError(e: unknown): string {
  if (e instanceof PulseChatAuthError) return `${AUTH_MESSAGES[e.code] ?? e.code}${e.field ? ` (${e.field.toLowerCase().replace(/_/g, " ")})` : ""}`;
  if (e instanceof PulseChatError) return e.message;
  return e instanceof Error ? e.message : String(e);
}

const sameDay = (a: Date, b: Date) => a.toDateString() === b.toDateString();
function formatTime(ms: number): string {
  const d = new Date(ms);
  return sameDay(d, new Date()) ? d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : d.toLocaleDateString();
}
const formatSize = (bytes: number) => (bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`);
const TICKS: Record<DeliveryStatus, string> = { sending: "🕓", sent: "✓", delivered: "✓✓", read: "✓✓" };
const TYPE_LABELS: Record<string, string> = {
  IMAGE: "📷 Photo", VIDEO: "🎥 Video", AUDIO: "🎤 Voice message", DOCUMENT: "📄", LOCATION: "📍 Location", POLL: "📊", CONTACT: "👤", SYSTEM: "Group update",
};

function start(): PulseChat | null {
  try {
    return initPulseChat(configFromEnv(import.meta.env));
  } catch (e) {
    root.replaceChildren(h("main", { className: "auth" }, h("h1", {}, "PulseChat Web"), h("p", { className: "error" }, describeError(e)), h("p", {}, "See web/README.md, step 1.")));
    return null;
  }
}

function run(pc: PulseChat): void {
  let me: ChatUser | null = null;
  let sessionUid: string | null = null;
  let conversations: Conversation[] = [];
  let current: Conversation | null = null;
  let messages: ChatMessage[] = [];
  let presenceText = "";
  let sessionStops: Unsubscribe[] = [];
  let chatStops: Unsubscribe[] = [];
  let stopMessages: Unsubscribe | null = null;
  let messagesFor: string | null = null;
  let stopPresence: (() => Promise<void>) | null = null;
  const profiles = new Map<string, ChatUser>();
  const loading = new Set<string>();
  const urls = new Map<string, Promise<string>>();
  const typingSeen = new Map<string, { value: number; seenAt: number }>();

  const list = h("ul", { className: "chats" });
  const header = h("header", { className: "chat-header" });
  const thread = h("ol", { className: "thread" });
  const composer = h("form", { className: "composer" });
  const status = h("p", { className: "status", role: "status" });

  const nameOf = (uid: string) => (uid === me?.uid ? "You" : profiles.get(uid)?.displayName ?? "…");
  const titleOf = (c: Conversation) => (c.type === "group" ? c.title ?? "" : c.peerId ? nameOf(c.peerId) : "");

  function ensureProfile(uid: string): void {
    if (profiles.has(uid) || loading.has(uid)) return;
    loading.add(uid);
    getUser(pc, uid).then((u) => {
      if (u) profiles.set(uid, u);
      renderList();
      renderHeader();
      renderThread();
    }).catch(() => undefined).finally(() => loading.delete(uid));
  }

  function resolveUrl(media: MediaDoc): Promise<string> {
    const key = media.path || media.url;
    let url = urls.get(key);
    if (!url) {
      url = mediaUrl(pc, media);
      urls.set(key, url);
    }
    return url;
  }

  function previewOf(c: Conversation): string {
    const last = c.lastMessage;
    if (!last) return "";
    const label = TYPE_LABELS[last.type];
    const text = label ? `${label} ${last.preview}`.trim() : last.preview;
    return c.type === "group" && last.type !== "SYSTEM" ? `${nameOf(last.senderId)}: ${text}` : text;
  }

  function renderList(): void {
    if (!me) return;
    const uid = me.uid;
    list.replaceChildren(...conversations.map((c) => {
      const unread = !!c.lastMessage && c.lastMessage.senderId !== uid && c.lastMessageAt > (c.receipts[uid]?.readUpTo ?? 0);
      return h("li", { className: c.id === current?.id ? "active" : "" },
        h("button", { type: "button", onclick: () => openChat(c) },
          h("span", { className: "title", dir: "auto" }, titleOf(c)),
          h("span", { className: "time" }, c.lastMessageAt ? formatTime(c.lastMessageAt) : ""),
          h("span", { className: "preview", dir: "auto" }, previewOf(c)),
          unread && h("span", { className: "dot", "aria-label": "unread" })));
    }));
  }

  function renderHeader(): void {
    const conv = current;
    if (!conv || !me) {
      header.replaceChildren(h("span", { className: "muted" }, "Select a chat, or find someone to start chatting."));
      return;
    }
    const now = Date.now();
    const typing = Object.entries(conv.typing).filter(([uid, value]) => {
      if (uid === me!.uid) return false;
      const key = `${conv.id}:${uid}`;
      const seen = typingSeen.get(key);
      if (!seen || seen.value !== value) typingSeen.set(key, { value, seenAt: now });
      return now - typingSeen.get(key)!.seenAt < TYPING_TIMEOUT_MS;
    }).map(([uid]) => nameOf(uid));
    const subtitle = typing.length > 0
      ? conv.type === "group" ? `${typing.join(", ")} typing…` : "typing…"
      : conv.type === "group" ? `${conv.participants.length} members` : presenceText;
    header.replaceChildren(h("strong", { dir: "auto" }, titleOf(conv)), h("span", { className: "muted" }, subtitle));
  }

  function systemText(m: ChatMessage): string {
    const s = m.system;
    if (!s) return "Group updated";
    const targets = s.targets.map(nameOf).join(", ");
    switch (s.event) {
      case "created": return `${nameOf(s.actor)} created the group "${s.value ?? ""}"`;
      case "added": return `${nameOf(s.actor)} added ${targets}`;
      case "removed": return `${nameOf(s.actor)} removed ${targets}`;
      case "left": return `${nameOf(s.actor)} left`;
      case "promoted": return `${nameOf(s.actor)} made ${targets} an admin`;
      case "demoted": return `${nameOf(s.actor)} removed ${targets} as admin`;
      default: return `${nameOf(s.actor)} updated the group`;
    }
  }

  function mediaNode(m: ChatMessage, media: MediaDoc): HTMLElement {
    const el = m.type === "IMAGE" ? h("img", { alt: media.name ?? "photo", loading: "lazy" })
      : m.type === "VIDEO" ? h("video", { controls: true, preload: "metadata" })
      : m.type === "AUDIO" ? h("audio", { controls: true, preload: "none" })
      : h("a", { target: "_blank", rel: "noopener" }, `📄 ${media.name ?? "file"} (${formatSize(media.size)})`);
    resolveUrl(media).then((url) => {
      if (el instanceof HTMLAnchorElement) el.href = url;
      else (el as HTMLImageElement | HTMLMediaElement).src = url;
    }).catch(() => undefined);
    return el;
  }

  function messageItem(m: ChatMessage, conv: Conversation, uid: string): HTMLElement {
    if (m.type === "SYSTEM") return h("li", { className: "system" }, systemText(m));
    const mine = m.senderId === uid;
    const body: Child[] = [];
    if (conv.type === "group" && !mine) body.push(h("span", { className: "sender", dir: "auto" }, nameOf(m.senderId)));
    if (m.replyTo && !m.deleted) body.push(h("blockquote", { dir: "auto" }, m.replyTo.preview || m.replyTo.type.toLowerCase()));
    if (m.deleted) {
      body.push(h("em", { className: "muted" }, "This message was deleted"));
    } else {
      if (m.media) body.push(mediaNode(m, m.media));
      if (m.location) body.push(h("a", { href: `https://www.openstreetmap.org/?mlat=${m.location.lat}&mlon=${m.location.lng}`, target: "_blank", rel: "noopener" }, "📍 Location"));
      if (m.contact) body.push(h("span", { dir: "auto" }, `👤 ${m.contact.name ?? ""} ${m.contact.phone ?? ""}`));
      if (m.poll) {
        const votes = Object.values(m.poll.votes);
        body.push(h("strong", { dir: "auto" }, `📊 ${m.poll.question ?? ""}`),
          h("ul", { className: "poll" }, ...m.poll.options.map((o, i) => h("li", { dir: "auto" }, `${o} · ${votes.filter((v) => v === i).length}`))));
      }
      if (m.text && m.type !== "POLL") body.push(h("p", { dir: "auto" }, m.text));
    }
    const reactions = Object.values(m.reactions);
    const state = deliveryStatus(m, conv, uid);
    const meta = h("span", { className: "meta" }, m.editedAt ? "edited · " : "", formatTime(m.createdAt),
      state && h("span", { className: `ticks ${state}`, title: state }, ` ${TICKS[state]}`));
    return h("li", { className: mine ? "mine" : "theirs" },
      h("div", { className: "bubble" }, ...body, reactions.length > 0 && h("span", { className: "reactions" }, reactions.join(" ")), meta));
  }

  function renderThread(): void {
    const conv = current;
    if (!conv || !me) { thread.replaceChildren(); return; }
    if (!conv.exists) {
      thread.replaceChildren(h("li", { className: "system" }, "Say hello 👋 The chat is created with the first message."));
      return;
    }
    const atBottom = thread.scrollHeight - thread.scrollTop - thread.clientHeight < 60;
    const uid = me.uid;
    thread.replaceChildren(...messages.map((m) => messageItem(m, conv, uid)));
    if (atBottom) thread.scrollTop = thread.scrollHeight;
  }

  function maybeMarkRead(): void {
    if (current?.exists && document.visibilityState === "visible" && document.hasFocus()) {
      void markRead(pc, current, messages).catch(() => undefined);
    }
  }

  function ensureMessageListener(): void {
    if (!current?.exists || messagesFor === current.id) return;
    stopMessages?.();
    const cid = current.id;
    messagesFor = cid;
    stopMessages = observeMessages(pc, cid, (list) => {
      if (current?.id !== cid) return;
      messages = list;
      list.forEach((m) => ensureProfile(m.senderId));
      renderThread();
      maybeMarkRead();
    }, { limit: 100, onError: (e) => { status.textContent = `Messages: ${e.message}`; } });
  }

  async function sendFile(input: HTMLInputElement): Promise<void> {
    const file = input.files?.[0];
    input.value = "";
    const conv = current;
    if (!file || !conv) return;
    const size = file.type.startsWith("image/")
      ? await createImageBitmap(file).then((b) => ({ width: b.width, height: b.height })).catch(() => ({ width: 0, height: 0 }))
      : { width: 0, height: 0 };
    try {
      await sendMedia(pc, conv, { data: file, mime: file.type || "application/octet-stream", fileName: file.name, ...size }, {
        onProgress: (p) => { status.textContent = `Uploading ${file.name}… ${p}%`; },
      });
      status.textContent = "";
    } catch (e) {
      status.textContent = describeError(e);
    }
  }

  function renderComposer(): void {
    const conv = current;
    if (!conv) { composer.replaceChildren(); return; }
    const input = h("input", { name: "text", placeholder: "Message", autocomplete: "off", dir: "auto", maxlength: 4096 });
    input.addEventListener("input", () => { if (current) void setTyping(pc, current, input.value.trim().length > 0).catch(() => undefined); });
    const file = h("input", { type: "file", hidden: true, accept: "image/*,video/*,audio/*,application/pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.zip" });
    file.addEventListener("change", () => void sendFile(file));
    composer.onsubmit = (ev) => {
      ev.preventDefault();
      const text = input.value;
      if (!text.trim() || !current) return;
      input.value = "";
      sendText(pc, current, text).catch((e) => { status.textContent = describeError(e); });
    };
    composer.replaceChildren(h("button", { type: "button", className: "icon", title: "Attach a file", onclick: () => file.click() }, "📎"), file, input,
      h("button", { type: "submit" }, "Send"));
    input.focus();
  }

  function openChat(conv: Conversation): void {
    chatStops.forEach((stop) => stop());
    chatStops = [];
    stopMessages?.();
    stopMessages = null;
    messagesFor = null;
    messages = [];
    presenceText = "";
    status.textContent = "";
    current = conv;
    if (conv.peerId) {
      ensureProfile(conv.peerId);
      chatStops.push(observePresence(pc, conv.peerId, (p) => {
        presenceText = !p.visible ? "" : p.online ? "online" : p.lastSeen ? `last seen ${formatTime(p.lastSeen)}` : "";
        renderHeader();
      }));
    }
    ensureMessageListener();
    renderList();
    renderHeader();
    renderThread();
    renderComposer();
  }

  function mainView(profile: ChatUser): HTMLElement {
    const results = h("ul", { className: "results" });
    const search = h("form", { className: "search" }, h("input", { name: "q", placeholder: "Find people: username or 10-digit ID", minlength: 2, required: true }));
    search.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const q = String(new FormData(search).get("q") ?? "");
      results.replaceChildren(h("li", { className: "muted" }, "Searching…"));
      try {
        const found = await searchUsers(pc, q);
        found.forEach((u) => profiles.set(u.uid, u));
        results.replaceChildren(...(found.length > 0
          ? found.map((u) => h("li", {}, h("button", { type: "button", onclick: () => {
            results.replaceChildren();
            openChat(conversations.find((c) => c.type === "direct" && c.peerId === u.uid) ?? directChat(pc, u.uid));
          } }, h("span", { dir: "auto" }, u.displayName), h("span", { className: "muted" }, ` @${u.username}`))))
          : [h("li", { className: "muted" }, "No users found.")]));
      } catch (e) {
        results.replaceChildren(h("li", { className: "error" }, describeError(e)));
      }
    });
    const logout = async () => {
      await stopPresence?.();
      stopPresence = null;
      await signOut(pc);
    };
    return h("div", { className: "layout" },
      h("aside", {},
        h("div", { className: "me" }, h("strong", { dir: "auto" }, profile.displayName),
          h("span", { className: "muted" }, `@${profile.username} · ID ${profile.uniqueId10}`),
          h("button", { type: "button", className: "link", onclick: () => void logout() }, "Sign out")),
        search, results, list),
      h("section", { className: "chat" }, header, thread, status, composer));
  }

  function authView(): HTMLElement {
    const error = h("p", { className: "error", role: "alert" });
    const fail = (e: unknown) => { error.textContent = describeError(e); };
    const signInForm = h("form", {},
      h("h2", {}, "Sign in"),
      h("input", { name: "id", placeholder: "Username or 10-digit ID", autocomplete: "username", required: true }),
      h("input", { name: "password", type: "password", placeholder: "Password", autocomplete: "current-password", required: true }),
      h("button", { type: "submit" }, "Sign in"));
    signInForm.addEventListener("submit", (ev) => {
      ev.preventDefault();
      const f = new FormData(signInForm);
      signIn(pc, String(f.get("id")), String(f.get("password"))).catch(fail);
    });
    const signUpForm = h("form", {},
      h("h2", {}, "Create account"),
      h("input", { name: "name", placeholder: "Display name", maxlength: 50, required: true, dir: "auto" }),
      h("input", { name: "username", placeholder: "Username (a-z, 0-9, . _)", autocomplete: "username", required: true }),
      h("input", { name: "password", type: "password", placeholder: "Password (8+ characters, letters and digits)", autocomplete: "new-password", required: true }),
      h("button", { type: "submit" }, "Create account"));
    signUpForm.addEventListener("submit", (ev) => {
      ev.preventDefault();
      const f = new FormData(signUpForm);
      signUp(pc, { displayName: String(f.get("name")), username: String(f.get("username")), password: String(f.get("password")) }).catch(fail);
    });
    return h("main", { className: "auth" },
      h("h1", {}, "PulseChat"),
      h("p", { className: "muted" }, "The same account, chats and messages as the Android app."),
      signInForm, signUpForm,
      h("button", { type: "button", className: "secondary", onclick: () => void signInWithGoogle(pc).catch(fail) }, "Continue with Google"),
      error);
  }

  function profileView(suggestedName: string): HTMLElement {
    const error = h("p", { className: "error", role: "alert" });
    const form = h("form", {},
      h("h2", {}, "Choose your username"),
      h("input", { name: "name", placeholder: "Display name", value: suggestedName, maxlength: 50, required: true, dir: "auto" }),
      h("input", { name: "username", placeholder: "Username", required: true }),
      h("button", { type: "submit" }, "Continue"));
    form.addEventListener("submit", (ev) => {
      ev.preventDefault();
      const f = new FormData(form);
      completeProfile(pc, String(f.get("username")), String(f.get("name"))).catch((e) => { error.textContent = describeError(e); });
    });
    return h("main", { className: "auth" }, form, h("button", { type: "button", className: "link", onclick: () => void signOut(pc) }, "Sign out"), error);
  }

  function teardown(): void {
    sessionStops.forEach((stop) => stop());
    sessionStops = [];
    chatStops.forEach((stop) => stop());
    chatStops = [];
    stopMessages?.();
    stopMessages = null;
    messagesFor = null;
    if (stopPresence) void stopPresence();
    stopPresence = null;
    conversations = [];
    current = null;
    messages = [];
  }

  function onSession(s: Session): void {
    if (s.state === "signedIn") {
      me = s.profile;
      profiles.set(s.uid, s.profile);
      if (sessionUid === s.uid) return;
      teardown();
      sessionUid = s.uid;
      root.replaceChildren(mainView(s.profile));
      renderHeader();
      sessionStops.push(observeConversations(pc, (all) => {
        conversations = all;
        for (const c of all) {
          if (c.peerId) ensureProfile(c.peerId);
          void markConversationDelivered(pc, c).catch(() => undefined);
        }
        if (current) current = all.find((c) => c.id === current!.id) ?? current;
        ensureMessageListener();
        renderList();
        renderHeader();
        renderThread();
      }, (e) => { status.textContent = `Chats: ${e.message}`; }));
      stopPresence = startPresence(pc);
      return;
    }
    teardown();
    sessionUid = null;
    me = null;
    if (s.state === "loading") root.replaceChildren(h("main", { className: "auth" }, h("p", { className: "muted" }, "Loading…")));
    else if (s.state === "signedOut") root.replaceChildren(authView());
    else if (s.state === "needsProfile") root.replaceChildren(profileView(s.suggestedName));
    else root.replaceChildren(h("main", { className: "auth" }, h("p", { className: "error" }, `Could not load your profile: ${s.error}`),
      h("button", { type: "button", onclick: () => location.reload() }, "Retry")));
  }

  window.addEventListener("focus", maybeMarkRead);
  document.addEventListener("visibilitychange", maybeMarkRead);
  setInterval(renderHeader, 1000);
  observeSession(pc, onSession);
}

const pulseChat = start();
if (pulseChat) run(pulseChat);
