/**
 * Attachment lifecycle for DSH Mobile API v1 "Attachments (v0.2.2)".
 *
 * Uploads create one pending attachment record bound to a pairing device and a
 * target session. A later prompt references the ids, and the router builds the
 * DSH prompt content parts from the records (see `buildPromptContent` in
 * mappers.js). Records live in host memory only: image bytes are needed again
 * when the prompt is admitted (the DSH prompt image part carries raw base64),
 * while file prompts reference a staged `fileUploads` receipt. Nothing here
 * touches DSH, so the whole lifecycle is unit-testable offline.
 */

import crypto from 'node:crypto';

import { ApiError } from './errors.js';

/** Image media types the DSH attachment policy accepts (`ImageMediaType`). */
export const IMAGE_MEDIA_TYPES = Object.freeze([
  'image/png',
  'image/jpeg',
  'image/webp',
  'image/gif',
]);

/** Bytes of entropy in a host-minted attachment id. */
const ATTACHMENT_ID_BYTES = 12;

/** Mint one opaque attachment id (`att_...`). */
export function generateAttachmentId() {
  return `att_${crypto.randomBytes(ATTACHMENT_ID_BYTES).toString('base64url')}`;
}

/**
 * Decide whether an upload is an image or a file from its declared media type.
 * The contract's image kinds are exactly the DSH `ImageMediaType` values.
 *
 * @param {unknown} mimeType
 * @returns {'image' | 'file'}
 */
export function kindForMimeType(mimeType) {
  return IMAGE_MEDIA_TYPES.includes(String(mimeType ?? '').toLowerCase()) ? 'image' : 'file';
}

/**
 * Decode and validate `dataBase64` from an upload body.
 *
 * Accepts canonical standard base64 (whitespace between lines is tolerated and
 * stripped), rejects malformed/non-canonical encodings, empty payloads, and
 * anything above the decoded byte limit.
 *
 * @param {unknown} dataBase64
 * @param {{ maxDecodedBytes: number }} options
 * @returns {{ data: Buffer, canonical: string }}
 */
export function decodeBase64Attachment(dataBase64, { maxDecodedBytes }) {
  if (typeof dataBase64 !== 'string' || dataBase64.trim() === '') {
    throw new ApiError('invalid_request', 'Field dataBase64 must be a non-empty base64 string');
  }
  const compact = dataBase64.replace(/\s+/g, '');
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(compact) || compact.length % 4 !== 0) {
    throw new ApiError('invalid_request', 'Field dataBase64 must be valid base64');
  }
  const data = Buffer.from(compact, 'base64');
  const canonical = data.toString('base64');
  if (canonical !== compact) {
    throw new ApiError('invalid_request', 'Field dataBase64 must be canonical base64');
  }
  if (data.length === 0) {
    throw new ApiError('invalid_request', 'Attachment payload must not be empty');
  }
  if (data.length > maxDecodedBytes) {
    const limitMb = Math.floor(maxDecodedBytes / (1024 * 1024));
    throw new ApiError('invalid_request', `Attachment exceeds the ${limitMb} MB limit`);
  }
  return { data, canonical };
}

/**
 * In-memory pending-attachment table with TTL and device/session binding.
 *
 * The store enforces the host-side bounds (pending count and pending image
 * bytes) so a phone cannot grow host memory without sending prompts.
 */
export class AttachmentStore {
  /**
   * @param {{ config: any, now?: () => number, logger?: Pick<Console, 'warn'> }} options
   */
  constructor({ config, now = () => Date.now(), logger = console }) {
    this.config = config;
    this.now = now;
    this.logger = logger;
    /** @type {Map<string, Record<string, any>>} */
    this.records = new Map();
    this.pendingImageBytes = 0;
  }

  /** Drop expired records (called lazily on every operation). */
  prune() {
    const now = this.now();
    for (const record of [...this.records.values()]) {
      if (record.expiresAt <= now) this.#delete(record);
    }
  }

  /** @param {Record<string, any>} record */
  #delete(record) {
    if (!this.records.delete(record.attachmentId)) return;
    this.pendingImageBytes = Math.max(0, this.pendingImageBytes - (record.imageBytes ?? 0));
  }

  /** Number of pending (non-expired) attachments. */
  get size() {
    this.prune();
    return this.records.size;
  }

  /**
   * Throw when one more pending attachment of this shape would exceed a bound.
   * Callers run this *before* persisting bytes through DSH, so a limit refusal
   * never leaves an orphaned durable attachment behind.
   *
   * @param {{ kind: 'image' | 'file', dataBase64?: string | null }} input
   */
  assertCapacity(input) {
    this.prune();
    const { config } = this;
    if (this.records.size >= config.attachments.maxPendingCount) {
      throw new ApiError(
        'invalid_request',
        'Too many pending attachments; send or discard the current ones before uploading more',
      );
    }
    const imageBytes = input.kind === 'image' && typeof input.dataBase64 === 'string'
      ? Buffer.byteLength(input.dataBase64, 'utf8')
      : 0;
    if (this.pendingImageBytes + imageBytes > config.attachments.maxPendingBytes) {
      throw new ApiError(
        'invalid_request',
        'Pending attachment data exceeds the host memory limit; send a prompt before uploading more',
      );
    }
  }

  /**
   * Register one uploaded attachment.
   *
   * @param {{
   *   kind: 'image' | 'file',
   *   name: string,
   *   mimeType: string,
   *   bytes: number,
   *   sessionId: string,
   *   deviceId: string,
   *   ref?: unknown,
   *   receiptId?: string | null,
   *   dataBase64?: string | null,
   * }} input
   * @returns {Record<string, any>}
   */
  add(input) {
    this.assertCapacity(input);
    const imageBytes = input.kind === 'image' && typeof input.dataBase64 === 'string'
      ? Buffer.byteLength(input.dataBase64, 'utf8')
      : 0;
    const now = this.now();
    /** @type {Record<string, any>} */
    const record = {
      attachmentId: generateAttachmentId(),
      kind: input.kind,
      name: input.name,
      mimeType: input.mimeType,
      bytes: input.bytes,
      sessionId: String(input.sessionId),
      deviceId: String(input.deviceId),
      createdAt: now,
      expiresAt: now + this.config.attachments.ttlMs,
      ref: input.ref ?? null,
      receiptId: input.receiptId ?? null,
      dataBase64: input.dataBase64 ?? null,
      imageBytes,
    };
    this.records.set(record.attachmentId, record);
    this.pendingImageBytes += imageBytes;
    return record;
  }

  /**
   * Resolve ids for a prompt on one session/device.
   *
   * Binding failures answer `not_found` on purpose: a phone that presents a
   * foreign or stale id learns nothing about whether it exists.
   *
   * @param {{ attachmentIds: string[], sessionId: string, deviceId: string }} request
   * @returns {Array<Record<string, any>>}
   */
  resolveForPrompt({ attachmentIds, sessionId, deviceId }) {
    this.prune();
    const seen = new Set();
    /** @type {Array<Record<string, any>>} */
    const records = [];
    for (const id of attachmentIds) {
      if (typeof id !== 'string' || id === '') {
        throw new ApiError('invalid_request', 'Field attachmentIds must contain non-empty strings');
      }
      if (seen.has(id)) {
        throw new ApiError('invalid_request', 'Field attachmentIds must not repeat an id');
      }
      seen.add(id);
      const record = this.records.get(id);
      if (
        record === undefined ||
        record.expiresAt <= this.now() ||
        record.sessionId !== String(sessionId) ||
        record.deviceId !== String(deviceId)
      ) {
        throw new ApiError('not_found', 'Attachment not found or expired');
      }
      records.push(record);
    }
    return records;
  }

  /**
   * Remove records consumed by a successfully admitted prompt.
   * @param {Array<Record<string, any>>} records
   */
  consume(records) {
    for (const record of records ?? []) this.#delete(record);
  }

  /** Remove every pending record (plugin unload). */
  clear() {
    this.records.clear();
    this.pendingImageBytes = 0;
  }

  /** Pending record by id, for tests/diagnostics. */
  get(attachmentId) {
    this.prune();
    return this.records.get(attachmentId);
  }

  /**
   * Contract JSON for one uploaded attachment.
   * @param {Record<string, any>} record
   */
  publicView(record) {
    return {
      attachmentId: record.attachmentId,
      name: record.name,
      mimeType: record.mimeType,
      bytes: record.bytes,
      kind: record.kind,
    };
  }
}
