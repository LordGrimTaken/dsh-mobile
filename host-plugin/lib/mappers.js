/**
 * Mappers from DSH Session values to the DSH Mobile API v1 JSON contract.
 *
 * The adapter hands raw `SessionEvent`s here; nothing in this module touches
 * DSH services, so every mapping rule is unit-testable against fixture events.
 */

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

import { ApiError } from './errors.js';
import { parseRemoteImageUrl } from './remote-images.js';

const DEFAULT_LIMIT = 100;

/** ISO-8601 UTC without milliseconds, matching the contract examples. */
export function isoTime(ms) {
  const date = new Date(typeof ms === 'number' && Number.isFinite(ms) ? ms : Date.now());
  return date.toISOString().replace(/\.\d{3}Z$/, 'Z');
}

/** Collapse whitespace runs into single spaces (mobile previews are one line). */
export function collapseWhitespace(text) {
  return String(text ?? '').replace(/\s+/g, ' ').trim();
}

/**
 * Concatenate the text blocks of a DSH content array.
 * Unknown block types (images, files, tool-call) contribute nothing.
 *
 * @param {unknown} content
 */
export function extractText(content) {
  if (!Array.isArray(content)) return '';
  const parts = [];
  for (const block of content) {
    if (block && typeof block === 'object' && /** @type {any} */ (block).type === 'text') {
      const text = /** @type {any} */ (block).text;
      if (typeof text === 'string' && text !== '') parts.push(text);
    }
  }
  return parts.join('');
}

/** Maximum characters of tool-result output included in a tool message. */
export const TOOL_OUTPUT_MAX_CHARS = 8000;
/** Marker appended when the tool output is longer than the cap. */
export const TOOL_OUTPUT_TRUNCATION_MARKER = '… (output truncated)';

/**
 * Text-only output of a tool result, capped so transcripts stay bounded.
 * Image/file blocks contribute nothing; newlines inside text blocks are kept.
 *
 * @param {unknown} content tool-result message content blocks
 * @returns {string} empty string when the result has no text output
 */
export function toolOutput(content) {
  const text = extractText(content);
  if (text === '') return '';
  if (text.length <= TOOL_OUTPUT_MAX_CHARS) return text;
  return `${text.slice(0, TOOL_OUTPUT_MAX_CHARS)}${TOOL_OUTPUT_TRUNCATION_MARKER}`;
}

/* ------------------------------------------------------------------ */
/* Local workspace images referenced from Markdown (![alt](path))       */
/* ------------------------------------------------------------------ */

/** Images larger than this are never referenced or served. */
export const WORKSPACE_IMAGE_MAX_BYTES = 25 * 1024 * 1024;
/** Maximum workspace-image Markdown matches processed per message text. */
export const WORKSPACE_IMAGE_MAX_PER_MESSAGE = 8;
/** Maximum remote http(s) image links processed per message text. */
export const REMOTE_IMAGE_MAX_PER_MESSAGE = 4;

const WORKSPACE_IMAGE_MIME = Object.freeze({
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
});

/** @param {string} extension @param {Buffer} bytes */
function hasImageMagic(extension, bytes) {
  const startsWith = (signature) =>
    bytes.length >= signature.length && signature.every((value, index) => bytes[index] === value);
  if (extension === '.png') {
    return startsWith([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  }
  if (extension === '.jpg' || extension === '.jpeg') return startsWith([0xff, 0xd8, 0xff]);
  if (extension === '.gif') {
    return startsWith([0x47, 0x49, 0x46, 0x38, 0x37, 0x61]) || startsWith([0x47, 0x49, 0x46, 0x38, 0x39, 0x61]);
  }
  if (extension === '.webp') {
    return (
      startsWith([0x52, 0x49, 0x46, 0x46]) &&
      bytes.length >= 12 &&
      bytes.subarray(8, 12).toString('latin1') === 'WEBP'
    );
  }
  return false;
}

/** @param {string} filePath */
function readImageMagic(filePath) {
  let fd;
  try {
    fd = fs.openSync(filePath, 'r');
    const header = Buffer.alloc(12);
    const read = fs.readSync(fd, header, 0, 12, 0);
    return header.subarray(0, read);
  } catch {
    return null;
  } finally {
    if (fd !== undefined) {
      try {
        fs.closeSync(fd);
      } catch {
        /* best-effort close */
      }
    }
  }
}

/**
 * Small, linear Markdown image matcher. Only a single image per `![]()` is
 * accepted; paths may be `<angle bracket>`-wrapped and titles are ignored.
 * URLs are filtered after decoding.
 */
const MARKDOWN_IMAGE_PATTERN = /!\[[^\]\n]*\]\(\s*(?:<([^<>\n]+)>|([^\s()<>]+))(?:\s+(?:"[^"\n]*"|'[^'\n]*'|\([^)\n]*\)))?\s*\)/g;
const REMOTE_URL_PATTERN = /^(?:https?|file|data|ftp|ftps|mailto|tel|javascript):/i;

/** @param {string} rawMarkdownPath */
function decodeImageReference(rawMarkdownPath) {
  const trimmed = rawMarkdownPath.trim();
  if (trimmed === '') return null;
  let decoded;
  try {
    decoded = decodeURIComponent(trimmed);
  } catch {
    return null;
  }
  if (decoded === '' || decoded.includes('\0')) return null;
  return decoded;
}

/** @param {string} value */
function hasImageExtension(value) {
  return WORKSPACE_IMAGE_MIME[path.extname(value).toLowerCase()] !== undefined;
}

/** @param {string} value */
function isUnsafeReferencePath(value) {
  if (value.includes('\0')) return true;
  if (value.startsWith('\\\\') || value.startsWith('//')) return true; // UNC / protocol-relative
  if (/^\\\\[?.]\\/u.test(value) || /^\/\/[?.]\//u.test(value)) return true; // device namespace
  if (process.platform === 'win32' && /^[A-Za-z]:/u.test(value) && !path.isAbsolute(value)) return true;
  // Windows strips trailing dots/spaces during resolution, which could alias a
  // different file; reject them and ADS (`name.png:stream`) segments outright.
  const segments = value.replaceAll('\\', '/').split('/');
  for (let index = 0; index < segments.length; index += 1) {
    const segment = segments[index];
    if (segment === '..') return true;
    if (segment !== '' && (segment.endsWith('.') || segment.endsWith(' '))) return true;
    if (segment.includes(':')) {
      const driveSegment = index === 0 && /^[A-Za-z]:$/u.test(segment) && path.isAbsolute(value);
      if (!driveSegment) return true;
    }
  }
  return false;
}

/** @param {string} child @param {string} parent */
function isInsidePath(child, parent) {
  const relative = path.relative(parent, child);
  if (relative === '') return true;
  if (relative.startsWith('..') || path.isAbsolute(relative)) return false;
  if (process.platform === 'win32' && relative.toLowerCase().startsWith('..')) return false;
  return true;
}

/** @param {string} candidate */
function realPathOrNull(candidate) {
  try {
    return fs.realpathSync(candidate);
  } catch {
    return null;
  }
}

/**
 * Parse local image references from one text string. Remote/data/file URLs are
 * ignored, as are non-image extensions. Percent escapes are decoded; order is
 * preserved and duplicates collapse.
 *
 * @param {unknown} text
 * @param {number} [maxReferences] cap on matches processed (default 8 per message)
 * @returns {string[]}
 */
export function markdownImageReferences(text, maxReferences = WORKSPACE_IMAGE_MAX_PER_MESSAGE) {
  if (typeof text !== 'string' || text === '') return [];
  const cap = Number.isSafeInteger(maxReferences) && maxReferences > 0
    ? maxReferences
    : WORKSPACE_IMAGE_MAX_PER_MESSAGE;
  /** @type {string[]} */
  const references = [];
  const seen = new Set();
  MARKDOWN_IMAGE_PATTERN.lastIndex = 0;
  let match;
  while ((match = MARKDOWN_IMAGE_PATTERN.exec(text)) !== null) {
    const raw = match[1] ?? match[2] ?? '';
    const decoded = decodeImageReference(raw);
    if (decoded === null) continue;
    if (REMOTE_URL_PATTERN.test(decoded) || decoded.startsWith('//')) continue;
    if (!hasImageExtension(decoded)) continue;
    if (seen.has(decoded)) continue;
    seen.add(decoded);
    references.push(decoded);
    if (references.length >= cap) break;
  }
  return references;
}

/**
 * Resolve one Markdown image reference to a local image inside the session cwd
 * (relative refs) or inside one operator-configured artifact root (absolute
 * refs). Symlinks are resolved with realpath before containment, so a link can
 * never escape its allowed base. The extension, zero-byte and magic-byte checks
 * are applied here so only real images become attachments. Returns null for
 * every rejected reference.
 *
 * @param {string} reference decoded reference from {@link markdownImageReferences}
 * @param {{ cwd?: unknown, artifactRoots?: unknown }} [options]
 * @returns {{ id: string, kind: 'image', name: string, mimeType: string, bytes: number, ref: { workspacePath: string, allowedRoot: string, configuredRoot: boolean } } | null}
 */
export function resolveWorkspaceImage(reference, options = {}) {
  if (typeof reference !== 'string') return null;
  const decoded = decodeImageReference(reference);
  if (decoded === null || isUnsafeReferencePath(decoded) || !hasImageExtension(decoded)) return null;

  const roots = Array.isArray(options.artifactRoots)
    ? options.artifactRoots.filter((root) => typeof root === 'string' && root.trim() !== '')
    : [];
  /** @type {Array<{ candidate: string, base: string, configuredRoot: boolean }>} */
  const candidates = [];
  if (path.isAbsolute(decoded)) {
    for (const root of roots) {
      const realRoot = realPathOrNull(root);
      if (realRoot === null) continue;
      const candidate = path.resolve(decoded);
      if (!isInsidePath(path.resolve(candidate), realRoot)) continue;
      candidates.push({ candidate, base: realRoot, configuredRoot: true });
    }
  } else {
    const segments = decoded.split(/[\\/]+/u);
    if (segments.includes('..')) return null;
    if (decoded.includes(':')) return null;
    const cwd = typeof options.cwd === 'string' ? options.cwd.trim() : '';
    if (cwd === '' || !path.isAbsolute(cwd)) return null;
    const realCwd = realPathOrNull(cwd);
    if (realCwd === null) return null;
    candidates.push({ candidate: path.resolve(realCwd, decoded), base: realCwd, configuredRoot: false });
  }

  for (const { candidate, base, configuredRoot } of candidates) {
    const real = realPathOrNull(candidate);
    if (real === null || !isInsidePath(real, base)) continue;
    const extension = path.extname(real).toLowerCase();
    const mimeType = WORKSPACE_IMAGE_MIME[extension];
    if (mimeType === undefined) continue;
    let stat;
    try {
      stat = fs.statSync(real);
    } catch {
      continue;
    }
    if (!stat.isFile() || stat.size <= 0 || stat.size > WORKSPACE_IMAGE_MAX_BYTES) continue;
    const magic = readImageMagic(real);
    if (magic === null || !hasImageMagic(extension, magic)) continue;
    const id = `ws_${crypto.createHash('sha256').update(real).digest('hex').slice(0, 16)}`;
    return {
      id,
      kind: 'image',
      name: path.basename(real),
      mimeType,
      bytes: stat.size,
      ref: { workspacePath: real, allowedRoot: base, configuredRoot },
    };
  }
  return null;
}

/**
 * Internal attachment entries referenced by Markdown image links in a text
 * block (for {@link collectSessionAttachments}); duplicates collapse per text.
 *
 * @param {unknown} text
 * @param {{ cwd?: unknown, artifactRoots?: unknown }} [options]
 */
export function workspaceImageEntries(text, options = {}) {
  /** @type {Array<any>} */
  const entries = [];
  const seen = new Set();
  for (const reference of markdownImageReferences(text)) {
    const entry = resolveWorkspaceImage(reference, options);
    if (entry === null || seen.has(entry.id)) continue;
    seen.add(entry.id);
    entries.push(entry);
  }
  return entries;
}

/**
 * Public message-attachment metadata for workspace image links (internal `ref`
 * stripped). Host paths are never exposed: `id` is a hash and `name` is the
 * basename only.
 *
 * @param {unknown} text
 * @param {{ cwd?: unknown, artifactRoots?: unknown }} [options]
 */
export function workspaceImageAttachments(text, options = {}) {
  return workspaceImageEntries(text, options).map(({ ref, ...metadata }) => metadata);
}

/**
 * Internal synthetic entries for remote http(s) Markdown image links. Metadata
 * only: nothing is fetched here. Disabled unless `options.remoteImages === true`
 * (the adapter passes `artifacts.remote.enabled`).
 *
 * @param {unknown} text
 * @param {{ remoteImages?: unknown }} [options]
 */
export function remoteImageEntries(text, options = {}) {
  if (options.remoteImages !== true || typeof text !== 'string' || text === '') return [];
  /** @type {Array<any>} */
  const entries = [];
  const seen = new Set();
  MARKDOWN_IMAGE_PATTERN.lastIndex = 0;
  let match;
  while ((match = MARKDOWN_IMAGE_PATTERN.exec(text)) !== null) {
    const parsed = parseRemoteImageUrl(match[1] ?? match[2] ?? '');
    if (parsed === null || seen.has(parsed.id)) continue;
    seen.add(parsed.id);
    entries.push({
      id: parsed.id,
      kind: 'image',
      name: parsed.name,
      mimeType: parsed.mimeType,
      bytes: 0,
      ref: { remoteUrl: parsed.url.toString() },
    });
    if (entries.length >= REMOTE_IMAGE_MAX_PER_MESSAGE) break;
  }
  return entries;
}

/** Public metadata for remote image links (internal `ref` stripped, no URL). */
export function remoteImageAttachments(text, options = {}) {
  return remoteImageEntries(text, options).map(({ ref, ...metadata }) => metadata);
}

/**
 * Append remote image attachments to a message, without overriding existing
 * attachment ids (local workspace images win on collisions).
 *
 * @param {Record<string, any>} message
 * @param {unknown} text
 * @param {{ remoteImages?: unknown }} [options]
 */
function attachRemoteImages(message, text, options = {}) {
  const synthetic = remoteImageAttachments(text, options);
  if (synthetic.length === 0) return;
  const existing = Array.isArray(message.attachments) ? message.attachments : [];
  const seen = new Set(existing.map((entry) => entry?.id));
  const merged = [...existing, ...synthetic.filter((entry) => !seen.has(entry.id))];
  if (merged.length > 0) message.attachments = merged;
}

/** @param {string} left @param {string} right */
function sameCanonicalPath(left, right) {
  return process.platform === 'win32'
    ? left.toLowerCase() === right.toLowerCase()
    : left === right;
}

/**
 * Serve re-validation for a synthetic workspace image ref. Resolves the path
 * again (realpath + containment + current configured-root policy + extension +
 * size + magic), re-checks the realpath immediately before opening, then opens a
 * handle and fstats/reads that same handle so a swapped file/symlink cannot
 * escape the allowed base between validation and read. Returns null when any
 * guard fails. Errors intentionally never include host paths.
 *
 * @param {any} ref internal ref saved by {@link resolveWorkspaceImage}
 * @param {{ cwd?: unknown, artifactRoots?: unknown }} [options]
 * @returns {{ bytes: Buffer, mimeType: string, name: string } | null}
 */
export function readWorkspaceImage(ref, options = {}) {
  if (!ref || typeof ref.workspacePath !== 'string' || typeof ref.allowedRoot !== 'string') return null;

  // Absolute refs were admitted by a configured root; that root must still be
  // configured NOW (removing it from config must stop serving immediately).
  if (ref.configuredRoot === true) {
    const roots = Array.isArray(options.artifactRoots)
      ? options.artifactRoots.filter((root) => typeof root === 'string' && root.trim() !== '')
      : [];
    const allowedRoot = realPathOrNull(ref.allowedRoot);
    if (allowedRoot === null) return null;
    const stillConfigured = roots
      .map((root) => realPathOrNull(root))
      .some((root) => root !== null && sameCanonicalPath(root, allowedRoot));
    if (!stillConfigured) return null;
  }

  const real = realPathOrNull(ref.workspacePath);
  if (real === null) return null;
  const base = realPathOrNull(ref.allowedRoot);
  if (base === null || !isInsidePath(real, base)) return null;
  const extension = path.extname(real).toLowerCase();
  const mimeType = WORKSPACE_IMAGE_MIME[extension];
  if (mimeType === undefined) return null;

  // Re-realpath immediately before opening (reduces the validation→read TOCTOU
  // window); the opened handle is then fstat'ed and read as the single source of
  // truth for size/content.
  const immediatelyBeforeRead = realPathOrNull(ref.workspacePath);
  if (immediatelyBeforeRead === null || immediatelyBeforeRead !== real) return null;

  let fd;
  try {
    fd = fs.openSync(real, 'r');
  } catch {
    return null;
  }
  try {
    const stat = fs.fstatSync(fd);
    if (!stat.isFile() || stat.size <= 0 || stat.size > WORKSPACE_IMAGE_MAX_BYTES) return null;
    const bytes = fs.readFileSync(fd);
    if (bytes.byteLength !== stat.size) return null;
    if (!hasImageMagic(extension, bytes.subarray(0, 12))) return null;
    return { bytes, mimeType, name: path.basename(real) };
  } catch {
    return null;
  } finally {
    try {
      fs.closeSync(fd);
    } catch {
      /* best-effort close */
    }
  }
}

/**
 * Best-effort MIME type for a stored file attachment name.
 *
 * DSH file refs carry only `{attachmentId, name, bytes}` (no media type), so the
 * message metadata derives it from the filename extension and falls back to
 * `application/octet-stream`.
 *
 * @param {unknown} name
 */
export function fileMimeType(name) {
  const filename = typeof name === 'string' ? name.trim() : '';
  const dot = filename.lastIndexOf('.');
  if (dot < 0 || dot === filename.length - 1) return 'application/octet-stream';
  const extension = filename.slice(dot + 1).toLowerCase();
  return FILE_MIME_TYPES[extension] ?? 'application/octet-stream';
}

/** @type {Readonly<Record<string, string>>} */
const FILE_MIME_TYPES = Object.freeze({
  pdf: 'application/pdf',
  txt: 'text/plain',
  log: 'text/plain',
  md: 'text/markdown',
  json: 'application/json',
  csv: 'text/csv',
  html: 'text/html',
  htm: 'text/html',
  xml: 'application/xml',
  yaml: 'application/yaml',
  yml: 'application/yaml',
  zip: 'application/zip',
  gz: 'application/gzip',
  tar: 'application/x-tar',
  mp4: 'video/mp4',
  webm: 'video/webm',
  mp3: 'audio/mpeg',
  wav: 'audio/wav',
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  webp: 'image/webp',
  gif: 'image/gif',
  svg: 'image/svg+xml',
  doc: 'application/msword',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  xls: 'application/vnd.ms-excel',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  ppt: 'application/vnd.ms-powerpoint',
  pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
});

/**
 * Contract metadata (plus the raw DSH ref) for image/file content blocks.
 *
 * Installed DSH shapes:
 *   image = `{type:'image', attachment:{attachmentId, mediaType, bytes, width, height, name?}}`
 *   file  = `{type:'file',  attachment:{attachmentId, name, bytes}}`
 *
 * The returned `ref` is internal and must never be sent to the phone; the
 * public projection is produced by {@link messageAttachments}.
 *
 * @param {unknown} content DSH content block array
 * @returns {Array<{ id: string, kind: 'image' | 'file', name: string, mimeType: string, bytes: number, width?: number, height?: number, ref: Record<string, any> }>}
 */
export function contentAttachments(content) {
  if (!Array.isArray(content)) return [];
  /** @type {Array<any>} */
  const attachments = [];
  for (const block of content) {
    if (!block || typeof block !== 'object') continue;
    const type = /** @type {any} */ (block).type;
    if (type !== 'image' && type !== 'file') continue;
    const ref = /** @type {any} */ (block).attachment;
    if (!ref || typeof ref !== 'object') continue;
    const id = typeof ref.attachmentId === 'string' ? ref.attachmentId.trim() : '';
    if (id === '') continue;
    const kind = type === 'image' ? 'image' : 'file';
    const fallbackName = kind === 'image' ? 'Image' : 'File';
    const name =
      typeof ref.name === 'string' && ref.name.trim() !== '' ? ref.name.trim() : fallbackName;
    const mimeType =
      kind === 'image'
        ? typeof ref.mediaType === 'string' && ref.mediaType.trim() !== ''
          ? ref.mediaType.trim()
          : 'application/octet-stream'
        : fileMimeType(ref.name);
    const bytes = Number.isSafeInteger(ref.bytes) && ref.bytes >= 0 ? ref.bytes : 0;
    /** @type {any} */
    const entry = { id, kind, name, mimeType, bytes };
    if (kind === 'image') {
      if (Number.isSafeInteger(ref.width) && ref.width > 0) entry.width = ref.width;
      if (Number.isSafeInteger(ref.height) && ref.height > 0) entry.height = ref.height;
    }
    entry.ref = ref;
    attachments.push(entry);
  }
  return attachments;
}

/**
 * Public message-attachment metadata (internal `ref` stripped).
 * @param {unknown} content
 */
export function messageAttachments(content) {
  return contentAttachments(content).map(({ ref, ...metadata }) => metadata);
}

/**
 * Every attachment ref referenced by a session's visible messages, keyed by
 * attachment id. This is the same visibility path as {@link buildMessages}:
 * human user messages, assistant messages and tool results.
 *
 * With `cwd`/`artifactRoots`, Markdown image links in those text blocks are
 * resolved to synthetic internal refs (containing the canonical host path for
 * the read route; never serialized as public metadata).
 *
 * @param {ReadonlyArray<any>} events
 * @param {{ cwd?: unknown, artifactRoots?: unknown, remoteImages?: unknown }} [options]
 * @returns {Map<string, any>}
 */
export function collectSessionAttachments(events, options = {}) {
  /** @type {Map<string, any>} */
  const refs = new Map();
  for (const event of events ?? []) {
    if (!event || typeof event !== 'object') continue;
    const type = /** @type {any} */ (event).type;
    const data = /** @type {any} */ (event).data ?? {};
    let content;
    if (type === 'user/message') {
      if (!isVisibleUserMessage(data)) continue;
      content = data?.content;
    } else if (type === 'assistant/message') {
      content = data?.message?.content;
    } else if (type === 'tool/result') {
      // Tool result images/files (screenshots, generated artifacts) are attachments too.
      content = data?.message?.content ?? data?.content;
    } else {
      continue;
    }
    for (const entry of contentAttachments(content)) {
      if (!refs.has(entry.id)) refs.set(entry.id, entry);
    }
    // Collect every synthetic ref a message can carry (per-message caps still
    // bound work). A session-wide cap here would make attachments that the
    // detail builder already showed unreadable, because the builder and this
    // collection pass must agree on the same visible set.
    for (const entry of workspaceImageEntries(extractText(content), options)) {
      if (!refs.has(entry.id)) refs.set(entry.id, entry);
    }
    if (type === 'user/message' || type === 'assistant/message') {
      for (const entry of remoteImageEntries(extractText(content), options)) {
        if (!refs.has(entry.id)) refs.set(entry.id, entry);
      }
    }
  }
  return refs;
}

/**
 * Best-effort one-line tool description from the model's raw JSON arguments.
 * The contract's `tool.detail` shows the command-like part of the call.
 *
 * @param {unknown} rawArguments
 */
export function toolDetail(rawArguments) {
  const trimmed = typeof rawArguments === 'string' ? rawArguments.trim() : '';
  if (trimmed === '') return '';
  let parsed;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return truncate(trimmed, 200);
  }
  if (parsed === null || typeof parsed !== 'object') return truncate(trimmed, 200);
  const record = /** @type {Record<string, unknown>} */ (parsed);
  for (const key of ['command', 'script', 'description', 'prompt', 'path', 'file_path', 'query', 'pattern', 'url', 'name']) {
    const value = record[key];
    if (typeof value === 'string' && value.trim() !== '') return truncate(value.trim(), 200);
  }
  const firstString = Object.values(record).find((value) => typeof value === 'string' && value.trim() !== '');
  return typeof firstString === 'string' ? truncate(firstString.trim(), 200) : truncate(trimmed, 200);
}

/** @param {string} text @param {number} max */
function truncate(text, max) {
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}

/** Choose the mobile message id for a DSH message (stable, opaque). */
function messageId(message, event) {
  const id = message && typeof message === 'object' ? /** @type {any} */ (message).id : undefined;
  return typeof id === 'string' && id !== '' ? id : `m_ev_${String(/** @type {any} */ (event)?.seq ?? '0')}`;
}

/**
 * Strict human prompt: `source.kind` absent or `'user'`.
 *
 * This is deliberately used by session-list previews, the first-user title
 * fallback and host-wide search: scheduled deliveries are transcript-only and
 * must not leak operational scheduler text into those surfaces.
 *
 * @param {any} data user/message event payload
 */
export function isHumanUserMessage(data) {
  const kind = data?.source?.kind;
  return kind === undefined || kind === 'user';
}

/**
 * Visible transcript user message: a human prompt or a delivered schedule prompt.
 *
 * DSH records injected agent context (file-change notices, AGENTS.md, skill
 * content, goal continuations, time/runtime context…) as user-role events; their
 * `source.kind` differs from `'user'`. Those stay hidden. Scheduled prompts are
 * real user-authored prompts delivered by the scheduler (`source.kind ===
 * 'schedule'`) and are part of the visible transcript (and its attachments).
 *
 * @param {any} data user/message event payload
 */
export function isVisibleUserMessage(data) {
  const kind = data?.source?.kind;
  return kind === undefined || kind === 'user' || kind === 'schedule';
}

/** Whether this user-message event was delivered by the scheduler. */
export function isScheduledUserMessage(data) {
  return data?.source?.kind === 'schedule';
}

/** Non-negative event sequence for fork boundaries, when present. */
function eventSeq(event) {
  const seq = /** @type {any} */ (event)?.seq;
  return Number.isSafeInteger(seq) && seq >= 0 ? seq : undefined;
}

/**
 * Append Markdown-referenced workspace images to a message's public attachment
 * metadata, deduplicated against DSH image/file attachments by id.
 *
 * @param {Record<string, any>} message
 * @param {unknown} text
 * @param {{ cwd?: unknown, artifactRoots?: unknown }} [options]
 */
function attachWorkspaceImages(message, text, options = {}) {
  if (typeof text !== 'string' || text === '') return;
  const synthetic = workspaceImageAttachments(text, options);
  if (synthetic.length === 0) return;
  const existing = Array.isArray(message.attachments) ? message.attachments : [];
  const seen = new Set(existing.map((entry) => entry?.id));
  const merged = [...existing, ...synthetic.filter((entry) => !seen.has(entry.id))];
  if (merged.length > 0) message.attachments = merged;
}

/**
 * Fold raw session events into contract Messages (chronological).
 *
 * - user / assistant messages carry their concatenated text and the durable
 *   event `seq` (when available) so the phone can request a precise fork cut.
 * - A completed tool call becomes one `tool` row with state done|failed,
 *   identified by `m_<callId>` so live SSE updates can upsert the same row.
 * - A tool call without a result (interrupted turn) is a running tool row.
 * - System-prompt and developer events are not shown to the phone.
 * - With `cwd`/`artifactRoots`, Markdown image links in text blocks become
 *   synthetic workspace-image attachments (public metadata only).
 *
 * @param {ReadonlyArray<any>} events
 * @param {{ cwd?: unknown, artifactRoots?: unknown }} [options]
 * @returns {Array<Record<string, any>>}
 */
export function buildMessages(events, options = {}) {
  /** @type {Array<Record<string, any>>} */
  const messages = [];
  /** @type {Map<string, { name: string, detail: string, time: number, seq?: number }>} */
  const pendingCalls = new Map();

  for (const event of events ?? []) {
    if (!event || typeof event !== 'object') continue;
    const data = /** @type {any} */ (event).data ?? {};
    const seq = eventSeq(event);
    switch (/** @type {any} */ (event).type) {
      case 'user/message': {
        if (!isVisibleUserMessage(data)) break;
        /** @type {Record<string, any>} */
        const userMessage = {
          id: messageId(data, event),
          ...(seq === undefined ? {} : { seq }),
          role: 'user',
          text: extractText(data?.content),
          createdAt: isoTime(/** @type {any} */ (event).time),
          streaming: false,
          ...(isScheduledUserMessage(data) ? { source: 'schedule' } : {}),
        };
        const userAttachments = messageAttachments(data?.content);
        if (userAttachments.length > 0) userMessage.attachments = userAttachments;
        attachWorkspaceImages(userMessage, userMessage.text, options);
        attachRemoteImages(userMessage, userMessage.text, options);
        messages.push(userMessage);
        break;
      }
      case 'assistant/message': {
        const message = data?.message;
        /** @type {Record<string, any>} */
        const assistantMessage = {
          id: messageId(message, event),
          ...(seq === undefined ? {} : { seq }),
          role: 'assistant',
          text: extractText(message?.content),
          createdAt: isoTime(/** @type {any} */ (event).time),
          streaming: false,
        };
        const assistantAttachments = messageAttachments(message?.content);
        if (assistantAttachments.length > 0) assistantMessage.attachments = assistantAttachments;
        attachWorkspaceImages(assistantMessage, assistantMessage.text, options);
        attachRemoteImages(assistantMessage, assistantMessage.text, options);
        messages.push(assistantMessage);
        break;
      }
      case 'tool/call': {
        const callId = String(data?.callId ?? '');
        if (callId !== '') {
          pendingCalls.set(callId, {
            name: typeof data?.name === 'string' ? data.name : 'tool',
            detail: toolDetail(data?.arguments),
            time: /** @type {any} */ (event).time,
            ...(seq === undefined ? {} : { seq }),
          });
        }
        break;
      }
      case 'tool/result': {
        const message = data?.message;
        const callId = String(message?.toolCallId ?? data?.callId ?? '');
        const call = pendingCalls.get(callId);
        const failed = Boolean(data?.error) || Boolean(message?.isError);
        /** Tool results can carry image/file blocks (e.g. screenshots); expose them like message attachments. */
        const toolAttachments = messageAttachments(message?.content);
        const output = toolOutput(message?.content);
        /** @type {Record<string, any>} */
        const toolMessage = {
          id: callId === '' ? messageId(message, event) : `m_${callId}`,
          ...(seq === undefined ? {} : { seq }),
          role: 'tool',
          text: '',
          createdAt: isoTime(/** @type {any} */ (event).time),
          streaming: false,
          tool: {
            name: call?.name ?? 'tool',
            detail: call?.detail ?? '',
            state: failed ? 'failed' : 'done',
            ...(output === '' ? {} : { output }),
          },
        };
        if (toolAttachments.length > 0) toolMessage.attachments = toolAttachments;
        attachWorkspaceImages(toolMessage, extractText(message?.content), options);
        // Remote refs are intentionally user/assistant-only: tool-result text is
        // attacker-influenced and must never trigger host egress.
        messages.push(toolMessage);
        pendingCalls.delete(callId);
        break;
      }
      default:
        break;
    }
  }

  for (const [callId, call] of pendingCalls) {
    messages.push({
      id: `m_${callId}`,
      ...(call.seq === undefined ? {} : { seq: call.seq }),
      role: 'tool',
      text: '',
      createdAt: isoTime(call.time),
      streaming: true,
      tool: { name: call.name, detail: call.detail, state: 'running' },
    });
  }

  return messages;
}

/**
 * One-line preview of the latest human-visible message in a session log.
 *
 * @param {ReadonlyArray<any>} events
 * @param {number} [maxChars]
 */
export function previewFromEvents(events, maxChars = 160) {
  for (let index = (events?.length ?? 0) - 1; index >= 0; index -= 1) {
    const event = events[index];
    const type = /** @type {any} */ (event)?.type;
    if (type !== 'assistant/message' && type !== 'user/message') continue;
    const data = /** @type {any} */ (event).data;
    if (type === 'user/message' && !isHumanUserMessage(data)) continue;
    const content = type === 'assistant/message' ? data?.message?.content : data?.content;
    const text = collapseWhitespace(extractText(content));
    if (text !== '') return truncate(text, maxChars);
  }
  return '';
}

/**
 * Map a DSH SessionSummary (from `sessionController.list`) to the contract.
 *
 * `goal` is the optional session-list goal summary derived from the durable
 * `goal` session projection (see `mapGoalProjection`); callers that cannot
 * resolve it yet omit the parameter, and list enrichment fills it at serve time.
 *
 * @param {{
 *   summary: Record<string, any>,
 *   title?: string | null,
 *   workspaceId?: string | null,
 *   preview?: string,
 *   goal?: { objective: string, phase: string, roundsStarted: number, maxGoalRounds: number } | null,
 *   parentSessionId?: string,
 *   agentPreset?: string,
 *   delegation?: { total: number, working: number },
 * }} input
 */
export function mapSessionSummary({
  summary,
  title,
  workspaceId,
  preview = '',
  goal,
  parentSessionId,
  agentPreset,
  delegation,
}) {
  const id = summary?.sessionId ?? summary?.id;
  /** @type {Record<string, any>} */
  const row = {
    id: String(id ?? ''),
    title: title ?? 'Untitled session',
    workspaceId: workspaceId ?? null,
    // DSH knows running/idle; "needs_input" needs approval/question bridging (P2, not wired yet).
    status: summary?.running === true ? 'working' : 'idle',
    preview: collapseWhitespace(preview),
    updatedAt: isoTime(typeof summary?.updatedAt === 'number' ? summary.updatedAt : Date.now()),
    unread: false,
    live: summary?.agentAvailable === true,
  };
  if (goal !== undefined) row.goal = goal;
  // Additive lineage fields: omit entirely when unknown/filtered (never null).
  // Self-parent links are invalid and defensively dropped.
  const parent = optionalField(parentSessionId);
  if (parent !== undefined && parent !== row.id) row.parentSessionId = parent;
  const preset = optionalField(agentPreset);
  if (preset !== undefined) row.agentPreset = preset;
  // Aggregate internal direct-child counts only (never ids/titles). Omitted when
  // there are no children; `working` is a best-effort DSH `running` snapshot.
  if (delegation && typeof delegation === 'object') {
    const total = Number.isSafeInteger(delegation.total) && delegation.total > 0 ? delegation.total : 0;
    const working = Number.isSafeInteger(delegation.working) && delegation.working > 0
      ? Math.min(delegation.working, total)
      : 0;
    if (total > 0) row.delegation = { total, working };
  }
  return row;
}

/** @param {unknown} value */
function optionalField(value) {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : undefined;
}

/**
 * Map a DSH SessionHeader (persisted session without a list summary).
 * @param {any} header
 * @param {{ parentSessionId?: string, agentPreset?: string }} [lineage]
 */
export function mapHeaderSummary(header, lineage = {}) {
  return mapSessionSummary({
    summary: {
      sessionId: header?.id,
      updatedAt: header?.createdAt,
      running: false,
      agentAvailable: false,
      cwd: header?.cwd,
    },
    ...lineage,
  });
}

/** Hard cap for the goal objective carried by a session-list row. */
export const GOAL_OBJECTIVE_MAX_CHARS = 500;

/**
 * Map a DSH GoalView (or goal-ish projection) to the session-list goal summary.
 *
 * The list carries only what a dashboard row needs (`objective`, `phase`,
 * `roundsStarted`, `maxGoalRounds`); `null`/absent means no current goal or an
 * unavailable goal source.
 *
 * @param {any} view DSH GoalView (or null/undefined)
 * @returns {{ objective: string, phase: string, roundsStarted: number, maxGoalRounds: number } | null}
 */
export function mapGoalSummary(view) {
  if (!view || typeof view !== 'object') return null;
  return {
    objective:
      typeof view.objective === 'string' ? view.objective.trim().slice(0, GOAL_OBJECTIVE_MAX_CHARS) : '',
    phase: typeof view.phase === 'string' ? view.phase : 'active',
    roundsStarted: Number(view.roundsStarted) || 0,
    maxGoalRounds: Number(view.maxGoalRounds) || 0,
  };
}

/**
 * Map the durable `goal` session projection (wire value of the DSH goal unit)
 * to the session-list goal summary.
 *
 * The wire value is `{ goal: GoalSnapshot, roundsStarted, createdAt, updatedAt }`
 * or `null` (before a first create and after a clear tombstone). It comes from
 * every `sessionController.list` row's cached projection hints, so cold
 * (non-live) sessions get a goal summary without loading an agent or reading a
 * log. Process-local activation ("armed") is deliberately absent from the
 * projection and is not part of this summary.
 *
 * @param {any} projection `SessionSummary.projections.values.goal`
 * @returns {{ objective: string, phase: string, roundsStarted: number, maxGoalRounds: number } | null}
 */
export function mapGoalProjection(projection) {
  if (!projection || typeof projection !== 'object') return null;
  const snapshot = projection.goal;
  if (!snapshot || typeof snapshot !== 'object') return null;
  return mapGoalSummary({
    objective: snapshot.objective,
    phase: snapshot.phase,
    roundsStarted: projection.roundsStarted,
    maxGoalRounds: snapshot.maxGoalRounds,
  });
}

/**
 * Map one DSH Workspace to the contract workspace entry.
 * @param {Record<string, any>} workspace
 */
export function mapWorkspace(workspace) {
  return {
    id: String(workspace?.id ?? ''),
    name: typeof workspace?.title === 'string' && workspace.title !== '' ? workspace.title : String(workspace?.id ?? ''),
    path: String(workspace?.path ?? ''),
  };
}

/** Maximum todo items exposed by `GET /sessions/{id}/todos`. */
export const SESSION_TODOS_MAX_ITEMS = 50;
/** Maximum characters of one todo content line. */
export const SESSION_TODO_CONTENT_MAX_CHARS = 500;

/** @param {unknown} value */
function sessionTodoStatus(value) {
  if (value === 'pending' || value === 'in_progress' || value === 'completed') return value;
  // Unknown/legacy statuses are treated as not-started rather than dropped.
  return 'pending';
}

/** @param {unknown} value */
function sessionTodoContent(value) {
  if (typeof value !== 'string') return '';
  return value
    .replace(/[\u0000-\u001f\u007f]+/gu, ' ')
    .replace(/\s+/gu, ' ')
    .trim()
    .slice(0, SESSION_TODO_CONTENT_MAX_CHARS);
}

/**
 * Project the latest `todo/write` event in a session log into the mobile plan
 * contract. The latest event by log order wins (even when it is empty), items
 * keep their order and are not deduped, unknown statuses map to `pending`,
 * blank/unsafe contents are omitted, and output is capped at 50 items / 500
 * chars. No event → `supported:false` with zero counts.
 *
 * @param {ReadonlyArray<any>} events
 * @returns {{ supported: boolean, updatedAt?: string, counts: { pending: number, inProgress: number, completed: number }, todos: Array<{ content: string, status: 'pending' | 'in_progress' | 'completed' }> }}
 */
export function mapSessionTodos(events) {
  let latest = null;
  for (let index = (events?.length ?? 0) - 1; index >= 0; index -= 1) {
    const event = events[index];
    if (/** @type {any} */ (event)?.type === 'todo/write') {
      latest = event;
      break;
    }
  }
  const empty = {
    supported: false,
    counts: { pending: 0, inProgress: 0, completed: 0 },
    todos: [],
  };
  if (latest === null) return empty;

  const rawItems = Array.isArray(/** @type {any} */ (latest).data?.todos)
    ? /** @type {any} */ (latest).data.todos
    : [];
  /** @type {Array<{ content: string, status: 'pending' | 'in_progress' | 'completed' }>} */
  const todos = [];
  for (const item of rawItems) {
    if (todos.length >= SESSION_TODOS_MAX_ITEMS) break;
    const content = sessionTodoContent(/** @type {any} */ (item)?.content);
    if (content === '') continue;
    todos.push({ content, status: sessionTodoStatus(/** @type {any} */ (item)?.status) });
  }
  const counts = {
    pending: todos.filter((todo) => todo.status === 'pending').length,
    inProgress: todos.filter((todo) => todo.status === 'in_progress').length,
    completed: todos.filter((todo) => todo.status === 'completed').length,
  };
  /** @type {any} */
  const result = { supported: true, counts, todos };
  const time = /** @type {any} */ (latest).time;
  if (typeof time === 'number' && Number.isFinite(time)) result.updatedAt = isoTime(time);
  return result;
}

/** Maximum members exposed by `GET /sessions/{id}/team`. */
export const SESSION_TEAM_MAX_MEMBERS = 50;
/** Maximum active tasks exposed by `GET /sessions/{id}/team`. */
export const SESSION_TEAM_MAX_ACTIVE_TASKS = 50;
/** Maximum chars of one member name. */
export const SESSION_TEAM_MEMBER_NAME_MAX_CHARS = 120;
/** Maximum chars of one task subject. */
export const SESSION_TEAM_TASK_SUBJECT_MAX_CHARS = 200;
/** Maximum chars of one member phase value. */
export const SESSION_TEAM_PHASE_MAX_CHARS = 60;
/** Maximum chars of a board task id. */
export const SESSION_TEAM_TASK_ID_MAX_CHARS = 120;

/**
 * Sanitize a team-board text field: controls→spaces, whitespace collapsed,
 * trimmed and length-capped.
 *
 * @param {unknown} value
 * @param {number} maxChars
 */
function teamText(value, maxChars) {
  if (typeof value !== 'string') return '';
  return value
    .replace(/[\u0000-\u001f\u007f]+/gu, ' ')
    .replace(/\s+/gu, ' ')
    .trim()
    .slice(0, maxChars);
}

/** @param {unknown} value */
function teamTaskStatus(value) {
  if (value === 'pending' || value === 'in_progress' || value === 'blocked' || value === 'completed') {
    return value;
  }
  // Unknown/legacy board statuses are treated as not-started, like todos.
  return 'pending';
}

/**
 * Project the Agent Teams board from a session log.
 *
 * Folds the latest `team/task` event per task id and the latest `team/member`
 * event per member id (log order wins). Only sanitized `name`/`phase` and
 * `id`/`subject`/`status`/`blockedBy` leave the host: descriptions, owner ids,
 * write scopes, member ids, provider/context and every other internal field are
 * omitted. Active tasks (pending/in_progress/blocked) are listed newest-revision
 * first, capped at 50; completed tasks only contribute to `counts.completed`.
 * Blank/unsafe names/subjects are dropped, unknown statuses map to `pending`, no
 * team events → `supported:false` with zero counts.
 *
 * @param {ReadonlyArray<any>} events
 * @returns {{ supported: boolean, counts: { pending: number, inProgress: number, blocked: number, completed: number }, members: Array<{ name: string, phase: string }>, tasks: Array<{ id: string, subject: string, status: 'pending' | 'in_progress' | 'blocked' | 'completed', blockedBy?: string[] }> }}
 */
export function mapSessionTeam(events) {
  /** @type {Map<string, { id: string, subject: string, status: 'pending' | 'in_progress' | 'blocked' | 'completed', blockedBy?: string[], revision: number, lastSeq: number }>} */
  const tasks = new Map();
  /** @type {Map<string, { name: string, phase: string, lastSeq: number }>} */
  const members = new Map();
  let supported = false;

  for (let index = 0; index < (events?.length ?? 0); index += 1) {
    const event = events[index];
    const type = /** @type {any} */ (event)?.type;
    if (type !== 'team/task' && type !== 'team/member') continue;
    supported = true;
    const data = /** @type {any} */ (event).data ?? {};
    if (type === 'team/task') {
      const task = data.task;
      if (!task || typeof task !== 'object') continue;
      const id = teamText(task.id, SESSION_TEAM_TASK_ID_MAX_CHARS);
      if (id === '') continue;
      const subject = teamText(task.subject, SESSION_TEAM_TASK_SUBJECT_MAX_CHARS);
      if (subject === '') {
        // The latest revision for this id is blank/unsafe: drop it instead of
        // resurrecting an older, now-stale subject.
        tasks.delete(id);
        continue;
      }
      /** @type {string[] | undefined} */
      let blockedBy;
      if (Array.isArray(task.blockedBy) && task.blockedBy.length > 0) {
        blockedBy = [];
        for (const raw of task.blockedBy) {
          const taskId = teamText(raw, SESSION_TEAM_TASK_ID_MAX_CHARS);
          if (taskId !== '' && !blockedBy.includes(taskId)) blockedBy.push(taskId);
          if (blockedBy.length >= SESSION_TEAM_MAX_ACTIVE_TASKS) break;
        }
      }
      const revision = Number.isSafeInteger(task.revision) && task.revision >= 0 ? task.revision : 0;
      tasks.set(id, {
        id,
        subject,
        status: teamTaskStatus(task.status),
        ...(blockedBy === undefined ? {} : { blockedBy }),
        revision,
        lastSeq: index,
      });
    } else {
      const member = data.member;
      if (!member || typeof member !== 'object') continue;
      const rawId = typeof member.id === 'string' && member.id !== '' ? member.id : '';
      const name = teamText(member.name, SESSION_TEAM_MEMBER_NAME_MAX_CHARS);
      if (rawId === '') continue;
      if (name === '') {
        // The latest revision for this member is blank/unsafe: remove it.
        members.delete(rawId);
        continue;
      }
      members.set(rawId, {
        name,
        phase: teamText(member.phase, SESSION_TEAM_PHASE_MAX_CHARS),
        lastSeq: index,
      });
    }
  }

  const counts = { pending: 0, inProgress: 0, blocked: 0, completed: 0 };
  /** @type {Array<any>} */
  const active = [];
  for (const task of tasks.values()) {
    if (task.status === 'pending') counts.pending += 1;
    else if (task.status === 'in_progress') counts.inProgress += 1;
    else if (task.status === 'blocked') counts.blocked += 1;
    else counts.completed += 1;
    if (task.status !== 'completed') active.push(task);
  }
  active.sort((left, right) => right.revision - left.revision || right.lastSeq - left.lastSeq);
  const listedTasks = active.slice(0, SESSION_TEAM_MAX_ACTIVE_TASKS).map((task) => ({
    id: task.id,
    subject: task.subject,
    status: task.status,
    ...(task.blockedBy === undefined ? {} : { blockedBy: task.blockedBy }),
  }));
  const listedMembers = [...members.values()]
    .sort((left, right) => right.lastSeq - left.lastSeq)
    .slice(0, SESSION_TEAM_MAX_MEMBERS)
    .map((member) => ({ name: member.name, phase: member.phase }));

  return {
    supported,
    counts,
    members: listedMembers,
    tasks: listedTasks,
  };
}

/** Maximum jobs exposed by `GET /sessions/{id}/jobs`. */
export const SESSION_JOBS_MAX = 50;
/** Maximum chars of a job kind/label. */
export const SESSION_JOB_TEXT_MAX_CHARS = 240;
/** Maximum chars of a job detail line. */
export const SESSION_JOB_DETAIL_MAX_CHARS = 120;

/**
 * Sanitize one job text field: controls→spaces, whitespace collapsed, trimmed,
 * credential values redacted and length-capped.
 *
 * @param {unknown} value
 * @param {number} maxChars
 */
function jobText(value, maxChars) {
  if (typeof value !== 'string') return '';
  const collapsed = value
    .replace(/[\u0000-\u001f\u007f]+/gu, ' ')
    .replace(/\s+/gu, ' ')
    .trim();
  return redactTrajectoryPaths(redactTrajectorySecrets(collapsed)).slice(0, maxChars);
}

/** @param {unknown} value */
function jobTimeMs(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value !== '') {
    const parsed = Date.parse(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return undefined;
}

/**
 * Normalize a settled job status. Registered events are always running; known
 * terminal aliases collapse to completed/failed/stopped; any other compact
 * status is preserved (lower-cased/capped) and counted as completed because the
 * job is settled.
 *
 * @param {unknown} value
 * @param {boolean} registered
 */
function jobStatus(value, registered) {
  if (registered) return 'running';
  // Sanitize (controls/collapse/redact) before classifying so an unknown status
  // cannot carry control characters, credentials or absolute paths.
  const compact = jobText(value, 120);
  const lower = compact.toLowerCase();
  if (['completed', 'complete', 'success', 'succeeded', 'done', 'ok'].includes(lower)) return 'completed';
  if (['failed', 'failure', 'error', 'errored'].includes(lower)) return 'failed';
  if (['stopped', 'stop', 'cancelled', 'canceled', 'aborted', 'killed', 'interrupted'].includes(lower)) return 'stopped';
  if (lower === 'running') return 'running';
  return lower === '' ? 'unknown' : lower.slice(0, 40);
}

/**
 * Project background tool jobs from a session log.
 *
 * Folds the latest `job/registered` / `job/settled` event per job id by log
 * order: registered → running, a settled event carries its normalized status.
 * Running jobs are listed first (most recently active first), then finished
 * jobs newest-finished first, capped at 50; counts cover **all** folded jobs.
 * Only sanitized `id`/`kind`/`label`/`status`/timestamps/duration/`detail` leave
 * the host — owner ids, tool-call ids and raw payloads are omitted. Text fields
 * are controls→spaces/collapsed, credential-redacted and capped (240/240/120).
 *
 * @param {ReadonlyArray<any>} events
 * @returns {{ supported: boolean, counts: { running: number, completed: number, failed: number }, jobs: Array<{ id: string, kind: string, label: string, status: string, startedAt?: string, finishedAt?: string, durationMs?: number, detail?: string }>, truncated: boolean, totalJobs: number }}
 */
export function mapSessionJobs(events) {
  /** @type {Map<string, { id: string, kind: string, label: string, status: string, startedAtMs?: number, finishedAtMs?: number, detail?: string, lastSeq: number }>} */
  const folded = new Map();
  let supported = false;

  for (let index = 0; index < (events?.length ?? 0); index += 1) {
    const event = events[index];
    const type = /** @type {any} */ (event)?.type;
    if (type !== 'job/registered' && type !== 'job/settled') continue;
    supported = true;
    const data = /** @type {any} */ (event).data ?? {};
    const id = jobText(data.jobId, 120);
    if (id === '') continue;
    const previous = folded.get(id);
    const registered = type === 'job/registered';
    const kind = jobText(data.kind, SESSION_JOB_TEXT_MAX_CHARS) || previous?.kind || 'job';
    const label = jobText(data.label, SESSION_JOB_TEXT_MAX_CHARS) || previous?.label || kind;
    const detail = jobText(data.detail, SESSION_JOB_DETAIL_MAX_CHARS) || previous?.detail;
    const startedAtMs = jobTimeMs(data.startedAt) ?? previous?.startedAtMs;
    const finishedAtMs = registered ? undefined : (jobTimeMs(data.finishedAt) ?? previous?.finishedAtMs);
    folded.set(id, {
      id,
      kind,
      label,
      status: jobStatus(data.status, registered),
      ...(startedAtMs === undefined ? {} : { startedAtMs }),
      ...(finishedAtMs === undefined ? {} : { finishedAtMs }),
      ...(detail === undefined ? {} : { detail }),
      lastSeq: index,
    });
  }

  const counts = { running: 0, completed: 0, failed: 0 };
  const running = [];
  const finished = [];
  for (const job of folded.values()) {
    if (job.status === 'running') {
      counts.running += 1;
      running.push(job);
    } else if (job.status === 'failed') {
      counts.failed += 1;
      finished.push(job);
    } else {
      counts.completed += 1;
      finished.push(job);
    }
  }
  running.sort((left, right) => right.lastSeq - left.lastSeq);
  finished.sort((left, right) => (right.finishedAtMs ?? -1) - (left.finishedAtMs ?? -1) || right.lastSeq - left.lastSeq);
  const listed = [...running, ...finished].slice(0, SESSION_JOBS_MAX).map((job) => {
    const result = {
      id: job.id,
      kind: job.kind,
      label: job.label,
      status: job.status,
    };
    if (job.startedAtMs !== undefined) result.startedAt = isoTime(job.startedAtMs);
    if (job.finishedAtMs !== undefined) result.finishedAt = isoTime(job.finishedAtMs);
    if (
      job.startedAtMs !== undefined &&
      job.finishedAtMs !== undefined &&
      Number.isSafeInteger(job.finishedAtMs - job.startedAtMs) &&
      job.finishedAtMs >= job.startedAtMs
    ) {
      result.durationMs = job.finishedAtMs - job.startedAtMs;
    }
    if (job.detail !== undefined) result.detail = job.detail;
    return result;
  });

  return {
    supported,
    counts,
    jobs: listed,
    truncated: folded.size > SESSION_JOBS_MAX,
    totalJobs: folded.size,
  };
}

/**
 * Slice messages for `GET /sessions/{id}?limit=&before=`.
 *
 * `before` is a message id from the previous page; the returned page is the
 * newest `limit` messages strictly older than it, in chronological order.
 * `nextCursor` is the oldest returned id when an older page still exists.
 *
 * @param {Array<Record<string, any>>} messages
 * @param {{ limit?: number | string, before?: string | null, maxLimit?: number }} [options]
 * @returns {{ messages: Array<Record<string, any>>, nextCursor: string | null }}
 */
export function paginateMessages(messages, options = {}) {
  const parsedLimit = Number.parseInt(String(options.limit ?? ''), 10);
  const maxLimit = options.maxLimit ?? 500;
  const limit = Number.isSafeInteger(parsedLimit) && parsedLimit > 0
    ? Math.min(parsedLimit, maxLimit)
    : DEFAULT_LIMIT;

  let end = messages.length;
  const before = options.before;
  if (typeof before === 'string' && before !== '') {
    const index = messages.findIndex((message) => message.id === before);
    if (index === -1) {
      throw new ApiError('invalid_request', 'Unknown `before` message cursor for this session');
    }
    end = index;
  }

  const start = Math.max(0, end - limit);
  const page = messages.slice(start, end);
  const nextCursor = start > 0 ? (page[0]?.id ?? null) : null;
  return { messages: page, nextCursor };
}

/** Default trajectory page size. */
export const TRAJECTORY_DEFAULT_LIMIT = 100;
/** Maximum trajectory page size. */
export const TRAJECTORY_MAX_LIMIT = 200;
/** Maximum chars of one trajectory summary. */
export const TRAJECTORY_SUMMARY_MAX_CHARS = 300;

/**
 * Redact credential-looking values from one trajectory fragment.
 *
 * Covers `sk-…` API keys, `Bearer …` tokens, `Authorization:` values,
 * `token=…`/`api_key=…`/`password=…`/`secret=…`-style key-values and long
 * base64-looking blobs. Normal tool names, commands and paths are left usable.
 * This runs before the 300-char cap so a secret cannot survive by straddling the
 * truncation boundary.
 *
 * @param {unknown} value
 */
export function redactTrajectorySecrets(value) {
  if (typeof value !== 'string' || value === '') return '';
  let text = value;
  // Authorization header values (with or without a Bearer scheme).
  text = text.replace(
    /\b(Authorization\s*:\s*)(?:Bearer\s+)?(?:"[^"]*"|'[^']*'|[^\s,;]+)/giu,
    '$1<redacted>',
  );
  // Standalone bearer tokens.
  text = text.replace(/\bBearer\s+[A-Za-z0-9._~+/=-]{4,}/giu, 'Bearer <redacted>');
  // Any assignment whose name contains a credential word anywhere, e.g.
  // CLOUDFLARE_API_TOKEN=..., _TOKEN: ..., DB_PASSWORD="...", AWS_ACCESS_KEY_ID=...
  text = text.replace(
    /\b([A-Za-z0-9_.-]*(?:TOKEN|SECRET|PASSWORD|PASSWD|API_KEY|ACCESS_KEY|PRIVATE_KEY)[A-Za-z0-9_.-]*)(["']?)(\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s,;]+)/giu,
    '$1$2$3<redacted>',
  );
  // Legacy key=value / key: value credential pairs (kept for short names).
  text = text.replace(
    /\b(access[_-]?token|api[_-]?key|client[_-]?secret|password|passwd|secret|token)(\s*[=:]\s*)(?:Bearer\s+)?(?:"[^"]*"|'[^']*'|[^\s,;]+)/giu,
    '$1$2<redacted>',
  );
  // Provider-prefixed tokens (Cloudflare, GitHub, GitLab, Slack, OpenAI,
  // npm, Google, AWS).
  text = text.replace(
    /\b(?:cfat_|cf_|gh[pousr]_|github_pat_|glpat-|xox[baprs]-|sk-|pk_|rk_|npm_|ya29\.|AIza|AKIA|ASIA)[A-Za-z0-9_-]{8,}\b/gu,
    '<redacted>',
  );
  // JWT-shaped tokens (three dot-separated base64url segments).
  text = text.replace(
    /\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/gu,
    '<redacted>',
  );
  // Long base64-looking blobs (80+ chars), e.g. encoded payloads/secrets.
  text = text.replace(/[A-Za-z0-9+/]{80,}={0,2}/gu, '<redacted>');
  return text;
}

/**
 * Redact absolute host paths from one trajectory fragment while keeping normal
 * commands usable. Covers Windows drive paths, UNC roots and POSIX paths under
 * common host roots (`/home`, `/Users`, `/root`, `/etc`, `/var`, `/tmp`,
 * `/opt`, `/mnt`). Everything is replaced with `<path>`.
 *
 * @param {unknown} value
 */
export function redactTrajectoryPaths(value) {
  if (typeof value !== 'string' || value === '') return '';
  let text = value;
  // UNC roots (\\server\share\...) must be handled before drive paths.
  text = text.replace(/\\\\[^\s\\/]+[\\/][^\s"'<>|,;]*/gu, '<path>');
  // Windows drive paths (C:\..., C:/...); the lookbehind avoids matching URL
  // scheme fragments like `https://`.
  text = text.replace(/(?<![\w])[A-Za-z]:[\\/][^\s"'<>|,;]*/gu, '<path>');
  // POSIX host paths under common roots; the lookbehind avoids URL path tails
  // such as `example.com/home`.
  text = text.replace(/(?<![\w])\/(?:home|Users|root|etc|var|tmp|opt|mnt)(?:\/[^\s"'<>|,;]*)?/gu, '<path>');
  return text;
}

/**
 * Sanitize one trajectory text fragment: controls→spaces, whitespace collapsed,
 * trimmed, credential/path-redacted and length-capped.
 *
 * @param {unknown} value
 * @param {number} [maxChars]
 */
function trajectoryText(value, maxChars = TRAJECTORY_SUMMARY_MAX_CHARS) {
  if (typeof value !== 'string') return '';
  const collapsed = value
    .replace(/[\u0000-\u001f\u007f]+/gu, ' ')
    .replace(/\s+/gu, ' ')
    .trim();
  return redactTrajectoryPaths(redactTrajectorySecrets(collapsed)).slice(0, maxChars);
}

/** @param {any} event */
function trajectorySummary(event) {
  const type = String(/** @type {any} */ (event)?.type ?? '');
  const data = /** @type {any} */ (event).data ?? {};
  const turn = data.turn?.turn ?? data.turn ?? data.turnNumber;
  const step = data.step?.step ?? data.step ?? data.stepNumber;
  const reason = trajectoryText(data.reason ?? data.message ?? '', 120);
  if (type === 'turn/start') return `turn ${Number.isSafeInteger(turn) ? turn : '?'} started`;
  if (type === 'turn/end') {
    const label = `turn ${Number.isSafeInteger(turn) ? turn : '?'} ended`;
    return reason === '' ? label : `${label} (${reason})`;
  }
  if (type === 'step/start') return `step ${Number.isSafeInteger(step) ? step : '?'} started`;
  if (type === 'step/end') {
    const label = `step ${Number.isSafeInteger(step) ? step : '?'} ended`;
    return reason === '' ? label : `${label} (${reason})`;
  }
  if (type === 'user/message') {
    if (!isVisibleUserMessage(data)) {
      const kind = trajectoryText(data?.source?.kind, 60) || 'context';
      return `[context] ${kind}`;
    }
    return trajectoryText(extractText(data?.content)) || '[empty message]';
  }
  if (type === 'assistant/message') {
    return trajectoryText(extractText(data?.message?.content)) || '[empty message]';
  }
  if (type === 'tool/call') {
    const name = trajectoryText(data.name, 80) || 'tool';
    const detail = trajectoryText(toolDetail(data.arguments));
    return detail === '' ? name : `${name}: ${detail}`;
  }
  if (type === 'tool/result') {
    const message = data.message;
    const failed = Boolean(data.error) || Boolean(message?.isError);
    if (failed) return 'failed';
    const name = trajectoryText(data.toolName ?? data.name, 80);
    const output = trajectoryText(extractText(message?.content) || toolOutput(message?.content));
    if (name !== '' && output !== '') return `${name}: ${output}`;
    if (output !== '') return output;
    if (name !== '') return `${name} completed`;
    return 'done';
  }
  if (type === 'goal/change') {
    const change = trajectoryText(data.phase ?? data.status ?? data.action ?? data.kind, 120);
    return change === '' ? 'goal changed' : `goal ${change}`;
  }
  if (type === 'todo/write') {
    const todos = Array.isArray(data.todos) ? data.todos : [];
    let pending = 0;
    let inProgress = 0;
    let completed = 0;
    for (const todo of todos) {
      if (todo?.status === 'in_progress') inProgress += 1;
      else if (todo?.status === 'completed') completed += 1;
      else pending += 1;
    }
    return `todos: ${pending} pending, ${inProgress} in progress, ${completed} completed`;
  }
  if (type === 'team/task') {
    const task = data.task;
    const id = trajectoryText(task?.id, 120);
    const subject = trajectoryText(task?.subject);
    const status = trajectoryText(task?.status, 40);
    if (id !== '' && subject !== '') {
      return `${id}: ${subject}${status === '' ? '' : ` [${status}]`}`;
    }
    return 'team task updated';
  }
  if (type === 'team/member') {
    const member = data.member;
    const name = trajectoryText(member?.name, 120);
    const phase = trajectoryText(member?.phase, 60);
    if (name === '') return 'team member updated';
    return phase === '' ? name : `${name} (${phase})`;
  }
  if (/^schedule(?:\/|$)/u.test(type)) {
    const suffix = trajectoryText(type.replace(/^schedule\/?/u, ''), 120);
    return suffix === '' ? 'schedule event' : `schedule ${suffix}`;
  }
  // Unknown event types are kept type-only so no payload can leak.
  return trajectoryText(type, 120) || 'event';
}

/**
 * Map raw session events to one page of the compact trajectory contract.
 *
 * Every snapshot event with a durable non-negative seq is included (known types
 * get a short sanitized one-line summary, unknown types are type-only; no raw
 * payloads are copied). Events are ordered newest-first by strictly decreasing
 * seq; `limit` defaults to 100 (1..200) and `beforeSeq` is exclusive. `hasMore`
 * is true when older events remain and `nextBeforeSeq` is the oldest seq of the
 * page (null otherwise). This pure mapper only sees events: a readable session
 * with no eligible events → `{supported:false, events:[]}`; callers must map an
 * unavailable history service to `501` before calling it.
 *
 * @param {ReadonlyArray<any>} events
 * @param {{ limit?: unknown, beforeSeq?: unknown }} [options]
 * @returns {{ supported: boolean, events: Array<{ seq: number, time?: string, type: string, summary: string, status?: string }>, nextBeforeSeq: number | null, hasMore: boolean }}
 */
export function mapSessionTrajectory(events, options = {}) {
  const rawLimit = options.limit;
  let limit = TRAJECTORY_DEFAULT_LIMIT;
  if (rawLimit !== undefined && rawLimit !== null && rawLimit !== '') {
    const parsed = typeof rawLimit === 'number' ? rawLimit : Number(String(rawLimit).trim());
    if (!Number.isSafeInteger(parsed) || parsed < 1 || parsed > TRAJECTORY_MAX_LIMIT) {
      throw new ApiError('invalid_request', `\`limit\` must be an integer 1..${TRAJECTORY_MAX_LIMIT}`);
    }
    limit = parsed;
  }
  const rawBefore = options.beforeSeq;
  let beforeSeq = null;
  if (rawBefore !== undefined && rawBefore !== null && rawBefore !== '') {
    const parsed = typeof rawBefore === 'number' ? rawBefore : Number(String(rawBefore).trim());
    if (!Number.isSafeInteger(parsed) || parsed < 0) {
      throw new ApiError('invalid_request', '`beforeSeq` must be a non-negative integer');
    }
    beforeSeq = parsed;
  }

  /** @type {Map<number, { seq: number, time?: string, type: string, summary: string, status?: string }>} */
  const bySeq = new Map();
  for (const event of events ?? []) {
    const seq = /** @type {any} */ (event)?.seq;
    if (!Number.isSafeInteger(seq) || seq < 0) continue;
    const type = trajectoryText(/** @type {any} */ (event).type, 120) || 'event';
    const summary = trajectorySummary(event);
    const rawStatus = type === 'tool/result'
      ? (Boolean(/** @type {any} */ (event).data?.error) || Boolean(/** @type {any} */ (event).data?.message?.isError) ? 'failed' : 'done')
      : undefined;
    /** @type {any} */
    const entry = { seq, type, summary, ...(rawStatus === undefined ? {} : { status: rawStatus }) };
    const time = /** @type {any} */ (event).time;
    if (typeof time === 'number' && Number.isFinite(time)) entry.time = isoTime(time);
    // Keep one entry per seq; log replay can repeat an event.
    bySeq.set(seq, entry);
  }
  const ordered = [...bySeq.values()].sort((left, right) => right.seq - left.seq);
  if (ordered.length === 0) {
    return { supported: false, events: [], nextBeforeSeq: null, hasMore: false };
  }
  const eligible = beforeSeq === null ? ordered : ordered.filter((event) => event.seq < beforeSeq);
  const page = eligible.slice(0, limit);
  const hasMore = eligible.length > limit;
  return {
    supported: true,
    events: page,
    nextBeforeSeq: hasMore && page.length > 0 ? page[page.length - 1].seq : null,
    hasMore,
  };
}

/** First human user prompt text, used as a title fallback. */
export function firstUserText(events, maxChars = 80) {
  for (const event of events ?? []) {
    if (/** @type {any} */ (event)?.type !== 'user/message') continue;
    const data = /** @type {any} */ (event).data;
    if (!isHumanUserMessage(data)) continue;
    const text = collapseWhitespace(extractText(data?.content));
    if (text !== '') return truncate(text, maxChars);
  }
  return '';
}

/**
 * Build the DSH `PromptContentPart[]` for one mobile prompt.
 *
 * - text part first, when the prompt has non-whitespace text
 * - image attachments become `{type:'image', mediaType, data}` parts; the DSH
 *   session controller admits (validates + stores) those bytes itself
 * - file attachments become `{type:'file', receiptId}` parts, resolved by the
 *   session controller through `ctx.fileUploads` (a staged receipt must exist)
 *
 * @param {string} text
 * @param {ReadonlyArray<Record<string, any>>} [attachments] pending attachment records
 * @returns {Array<Record<string, any>>}
 */
export function buildPromptContent(text, attachments = []) {
  /** @type {Array<Record<string, any>>} */
  const content = [];
  const promptText = typeof text === 'string' ? text : '';
  if (promptText.trim() !== '') content.push({ type: 'text', text: promptText });

  for (const attachment of attachments ?? []) {
    if (attachment?.kind === 'image') {
      if (typeof attachment.dataBase64 !== 'string' || attachment.dataBase64 === '') {
        throw new ApiError('internal', 'Image attachment bytes are no longer available; re-upload the image');
      }
      content.push({
        type: 'image',
        mediaType: attachment.mimeType,
        data: attachment.dataBase64,
        ...(typeof attachment.name === 'string' && attachment.name !== '' ? { name: attachment.name } : {}),
      });
      continue;
    }
    if (attachment?.kind === 'file') {
      if (typeof attachment.receiptId !== 'string' || attachment.receiptId === '') {
        throw new ApiError(
          'unsupported',
          'File attachments cannot be admitted into prompts in this host process (file-upload receipts are unavailable)',
        );
      }
      content.push({ type: 'file', receiptId: attachment.receiptId });
      continue;
    }
    throw new ApiError('invalid_request', 'Unknown attachment kind');
  }
  return content;
}

