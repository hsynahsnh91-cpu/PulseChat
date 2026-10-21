/**
 * Built-in media store (MediaRefs.kt / MediaRepository.kt): used for media whenever the Firebase
 * project has no usable Cloud Storage bucket. A file is `media/{mediaId}` (owner, mime, size,
 * chunks) plus `media/{mediaId}/chunks/{index}` documents holding at most 512 KB each. Messages,
 * statuses and profile/group photos then carry `pulsemedia://{mediaId}` where a download URL would
 * otherwise go. firestore.rules enforce the same limits.
 */
import { Bytes, doc, getDoc, serverTimestamp, setDoc } from "firebase/firestore";
import { PulseChatError, requireUid, type PulseChat } from "./firebase";
import { Fs } from "./schema";

export const MEDIA_SCHEME = "pulsemedia";
export const MEDIA_CHUNK_BYTES = 512 * 1024;
export const MEDIA_MAX_CHUNKS = 64;
export const MEDIA_MAX_BYTES = MEDIA_CHUNK_BYTES * MEDIA_MAX_CHUNKS;

const PREFIX = `${MEDIA_SCHEME}://`;
const MEDIA_ID = /^[A-Za-z0-9_-]{8,64}$/;

/** True for references into the built-in media store. */
export const isStoreRef = (ref?: string | null): ref is string => !!ref && ref.startsWith(PREFIX) && MEDIA_ID.test(ref.slice(PREFIX.length));
export const storeIdOf = (ref?: string | null): string | null => (isStoreRef(ref) ? ref.slice(PREFIX.length) : null);
export const storeRef = (mediaId: string): string => {
  if (!MEDIA_ID.test(mediaId)) throw new Error("invalid media id");
  return PREFIX + mediaId;
};
export const chunkCount = (size: number): number => (size <= 0 ? 0 : Math.ceil(size / MEDIA_CHUNK_BYTES));
export const newMediaId = (): string => `md${crypto.randomUUID().replace(/-/g, "")}`;

/** Media ID derived from a message ID, so retries rewrite the same documents (MediaRefs.mediaIdFor). */
export function mediaIdFor(key: string): string {
  const clean = key.replace(/[^A-Za-z0-9]/g, "").slice(0, 60);
  return clean.length >= 16 ? `md${clean}` : newMediaId();
}

/** Errors meaning "no usable Cloud Storage bucket" (as opposed to network trouble). */
export function isStorageUnavailable(e: unknown): boolean {
  const code = String((e as { code?: unknown })?.code ?? "");
  const status = Number((e as { status?: unknown })?.status ?? 0);
  return [
    "storage/object-not-found", "storage/bucket-not-found", "storage/project-not-found", "storage/unauthorized",
    "storage/quota-exceeded", "storage/no-default-bucket", "storage/invalid-default-bucket",
  ].includes(code) || (code === "storage/unknown" && [400, 402, 403, 404, 412].includes(status));
}

const STORAGE_RECHECK_MS = 6 * 60 * 60 * 1000;
export const storageLikelyAvailable = (pc: PulseChat): boolean => Date.now() >= (pc.cache.storageUnavailableUntil ?? 0);
export const markStorageUnavailable = (pc: PulseChat): void => { pc.cache.storageUnavailableUntil = Date.now() + STORAGE_RECHECK_MS; };

export async function toBytes(data: Blob | Uint8Array | ArrayBuffer): Promise<Uint8Array> {
  if (data instanceof Uint8Array) return data;
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  return new Uint8Array(await data.arrayBuffer());
}

/** Writes the metadata document, then the chunks in order. Returns the `pulsemedia://` reference. */
export async function uploadToStore(
  pc: PulseChat, data: Uint8Array, mime: string, mediaId: string, onProgress?: (percent: number) => void,
): Promise<string> {
  const owner = requireUid(pc);
  if (data.byteLength === 0) throw new PulseChatError("EMPTY");
  if (data.byteLength > MEDIA_MAX_BYTES) throw new PulseChatError("TOO_LARGE");
  const chunks = chunkCount(data.byteLength);
  const meta = doc(pc.db, Fs.MEDIA, mediaId);
  await setDoc(meta, { owner, mime: mime.slice(0, 128), size: data.byteLength, chunks, createdAt: serverTimestamp() });
  for (let i = 0; i < chunks; i++) {
    const part = data.subarray(i * MEDIA_CHUNK_BYTES, Math.min(data.byteLength, (i + 1) * MEDIA_CHUNK_BYTES));
    await setDoc(doc(pc.db, Fs.MEDIA, mediaId, Fs.MEDIA_CHUNKS, String(i)), { owner, data: Bytes.fromUint8Array(part) });
    onProgress?.(Math.round(((i + 1) * 100) / chunks));
  }
  return storeRef(mediaId);
}

/** Reads a file from the built-in store. */
export async function downloadFromStore(pc: PulseChat, ref: string, onProgress?: (percent: number) => void): Promise<Uint8Array> {
  const mediaId = storeIdOf(ref);
  if (!mediaId) throw new Error("not a built-in media reference");
  const meta = await getDoc(doc(pc.db, Fs.MEDIA, mediaId));
  if (!meta.exists()) throw new PulseChatError("NOT_FOUND");
  const chunks = Number(meta.get("chunks") ?? 0);
  if (!Number.isInteger(chunks) || chunks < 1 || chunks > MEDIA_MAX_CHUNKS) throw new PulseChatError("NOT_FOUND");
  const parts: Uint8Array[] = [];
  let total = 0;
  for (let i = 0; i < chunks; i++) {
    const snap = await getDoc(doc(pc.db, Fs.MEDIA, mediaId, Fs.MEDIA_CHUNKS, String(i)));
    const bytes = snap.get("data");
    if (!(bytes instanceof Bytes)) throw new PulseChatError("NOT_FOUND");
    const part = bytes.toUint8Array();
    parts.push(part);
    total += part.byteLength;
    onProgress?.(Math.round(((i + 1) * 100) / chunks));
  }
  const out = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) { out.set(part, offset); offset += part.byteLength; }
  return out;
}
