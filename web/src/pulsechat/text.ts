import type { MessageType } from "./schema";

/** Port of TextTools.preview: single line, whitespace collapsed, truncated with an ellipsis. */
export function preview(text: string, max = 80): string {
  const collapsed = text.replace(/[ \t\n\x0B\f\r]+/g, " ").trim();
  return collapsed.length <= max ? collapsed : `${collapsed.slice(0, max - 1).trimEnd()}…`;
}

/** Raw preview content stored in `lastMessage.preview` (SyncManager.previewText); UIs add the type label. */
export function previewText(m: {
  type: MessageType;
  text: string;
  deleted?: boolean;
  media?: { name: string | null } | null;
  contact?: { name: string | null } | null;
  poll?: { question: string | null } | null;
}): string {
  if (m.deleted) return "";
  switch (m.type) {
    case "DOCUMENT": return m.media?.name ?? m.text;
    case "CONTACT": return m.contact?.name ?? m.text;
    case "POLL": return m.poll?.question ?? m.text;
    default: return m.text;
  }
}

// ---------------------------------------------------------------------------------- media

/** MediaLimits (Policies.kt); Storage rules cap chat media below 100 MB. */
export const MAX_IMAGE_BYTES = 16 * 1024 * 1024;
export const MAX_VIDEO_BYTES = 64 * 1024 * 1024;
export const MAX_DOCUMENT_BYTES = 100 * 1024 * 1024 - 1;

export function maxBytesFor(type: MessageType): number {
  if (type === "IMAGE") return MAX_IMAGE_BYTES;
  if (type === "VIDEO") return MAX_VIDEO_BYTES;
  return MAX_DOCUMENT_BYTES;
}

const baseMime = (mime: string): string => mime.split(";")[0].trim().toLowerCase();

/** MediaRepository.typeForMime. */
export function typeForMime(mime: string): MessageType {
  const m = baseMime(mime);
  if (m.startsWith("image/")) return "IMAGE";
  if (m.startsWith("video/")) return "VIDEO";
  if (m.startsWith("audio/")) return "AUDIO";
  return "DOCUMENT";
}

const EXTENSIONS: Record<string, string> = {
  "image/jpeg": "jpg", "image/png": "png", "image/gif": "gif", "image/webp": "webp", "image/heic": "heic", "image/heif": "heif",
  "image/bmp": "bmp", "image/svg+xml": "svg",
  "video/mp4": "mp4", "video/webm": "webm", "video/3gpp": "3gp", "video/quicktime": "mov", "video/x-matroska": "mkv",
  "audio/mp4": "m4a", "audio/mpeg": "mp3", "audio/ogg": "ogg", "audio/webm": "webm", "audio/aac": "aac", "audio/wav": "wav",
  "audio/x-wav": "wav", "audio/amr": "amr", "audio/opus": "opus",
  "application/pdf": "pdf", "text/plain": "txt", "text/csv": "csv", "application/zip": "zip", "application/json": "json",
  "application/msword": "doc", "application/vnd.ms-excel": "xls", "application/vnd.ms-powerpoint": "ppt",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "docx",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": "xlsx",
  "application/vnd.openxmlformats-officedocument.presentationml.presentation": "pptx",
};

/** MediaRepository.extensionFor: extension from the MIME type, else from the file name, else "bin". */
export function extensionFor(mime: string, fileName: string): string {
  const fromMime = EXTENSIONS[baseMime(mime)];
  if (fromMime) return fromMime;
  const dot = fileName.lastIndexOf(".");
  const fromName = dot >= 0 ? fileName.slice(dot + 1).toLowerCase() : "";
  if (fromName.length > 0 && fromName.length <= 5 && /^[\p{L}\p{Nd}]+$/u.test(fromName)) return fromName;
  return "bin";
}
