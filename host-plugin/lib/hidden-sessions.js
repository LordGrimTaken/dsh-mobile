/**
 * Persisted hidden-session ids for the Mobile API list filter.
 *
 * The file is user/operator-owned and may be edited by hand (or written by the
 * app through `PUT /mobile/v1/sessions/hidden`):
 *
 *   { "updatedAt": "2026-10-30T13:00:00Z", "ids": ["session-…", …] }
 *
 * Reads are lazy and mtime-cached: expensive parsing happens only when the file
 * changes, with a small throttle so frequent list/SSE reads cannot turn into an
 * fs storm. A missing or corrupt file is an empty set (with a warning for
 * corrupt content), never a start-up failure.
 *
 * Hidden sessions are hidden from the LIST only: `GET /sessions/{id}`, prompts,
 * and attachment uploads keep working for them.
 */

import { promises as fs } from 'node:fs';
import path from 'node:path';

import { ApiError } from './errors.js';

/** Hard cap for the persisted set (also enforced by PUT). */
export const MAX_HIDDEN_SESSIONS = 500;

/** Default minimum interval between mtime checks per store instance. */
export const DEFAULT_HIDDEN_CHECK_INTERVAL_MS = 1000;

/** DSH session ids look like `session-<uuid>`; hidden entries must be such ids. */
export const SESSION_ID_PATTERN = /^session-[A-Za-z0-9][A-Za-z0-9_.:-]{0,190}$/;

/** @param {unknown} value */
export function isSessionIdString(value) {
  return typeof value === 'string' && SESSION_ID_PATTERN.test(value);
}

/**
 * Validate a `hiddenIds` array from the management API.
 * Duplicate ids are de-duplicated; anything else invalid is a 400.
 *
 * @param {unknown} value
 * @param {{ cap?: number }} [options]
 * @returns {string[]}
 */
export function validateHiddenIds(value, { cap = MAX_HIDDEN_SESSIONS } = {}) {
  if (!Array.isArray(value)) {
    throw new ApiError('invalid_request', 'Field hiddenIds must be an array of session ids');
  }
  if (value.length > cap) {
    throw new ApiError('invalid_request', `Field hiddenIds must contain at most ${cap} ids`);
  }
  /** @type {string[]} */
  const ids = [];
  const seen = new Set();
  for (const entry of value) {
    if (!isSessionIdString(entry)) {
      throw new ApiError(
        'invalid_request',
        'Field hiddenIds must contain session ids of the form "session-<id>"',
      );
    }
    if (seen.has(entry)) continue;
    seen.add(entry);
    ids.push(entry);
  }
  return ids;
}

/** @param {string} filePath */
async function statStamp(filePath) {
  try {
    const stat = await fs.stat(filePath);
    return `${stat.mtimeMs}:${stat.size}`;
  } catch {
    return null;
  }
}

/**
 * Parse one hidden-sessions document. Unknown/corrupt shapes become an empty
 * set; individual invalid ids are dropped defensively.
 *
 * @param {unknown} value
 * @param {{ cap: number, logger: Pick<Console, 'warn'> }} options
 * @returns {{ ids: string[], updatedAt: string | null }}
 */
export function parseHiddenSessions(value, { cap, logger }) {
  /** @type {unknown} */
  let rawIds;
  let updatedAt = null;
  if (Array.isArray(value)) {
    rawIds = value;
  } else if (value && typeof value === 'object') {
    const record = /** @type {Record<string, unknown>} */ (value);
    rawIds = Array.isArray(record.ids) ? record.ids : Array.isArray(record.hiddenIds) ? record.hiddenIds : [];
    if (typeof record.updatedAt === 'string' && record.updatedAt.trim() !== '') updatedAt = record.updatedAt;
  } else {
    rawIds = [];
  }
  /** @type {string[]} */
  const ids = [];
  const seen = new Set();
  let dropped = 0;
  for (const entry of rawIds) {
    if (!isSessionIdString(entry) || seen.has(entry)) {
      dropped += 1;
      continue;
    }
    seen.add(entry);
    ids.push(entry);
  }
  if (dropped > 0) {
    logger.warn?.(`[dsh-mobile-host] ignored ${dropped} invalid/duplicate hidden session id(s) from the hidden file`);
  }
  if (ids.length > cap) {
    logger.warn?.(`[dsh-mobile-host] hidden sessions file exceeds the ${cap}-id cap; only the first ${cap} are used`);
    ids.length = cap;
  }
  return { ids, updatedAt };
}

/**
 * In-memory view of the persisted hidden ids with a lazy mtime cache.
 * `filePath: null` keeps it memory-only (tests/embedding).
 */
export class HiddenSessionStore {
  /**
   * @param {{
   *   filePath?: string | null,
   *   logger?: Pick<Console, 'warn' | 'error'>,
   *   now?: () => number,
   *   checkIntervalMs?: number,
   *   cap?: number,
   * }} [options]
   */
  constructor({ filePath = null, logger = console, now = () => Date.now(), checkIntervalMs = DEFAULT_HIDDEN_CHECK_INTERVAL_MS, cap = MAX_HIDDEN_SESSIONS } = {}) {
    this.filePath = filePath;
    this.logger = logger;
    this.now = now;
    this.checkIntervalMs = Math.max(0, checkIntervalMs);
    this.cap = cap;
    /** @type {Set<string>} */
    this.ids = new Set();
    /** @type {string | null} */
    this.updatedAt = null;
    /** @type {string | null} */
    this.stamp = null;
    this.lastCheck = Number.NEGATIVE_INFINITY;
    /** @type {Promise<void>} */
    this.writeQueue = Promise.resolve();
  }

  /** Read the file once (startup). Missing/corrupt is an empty set. */
  async load() {
    this.lastCheck = this.now();
    if (!this.filePath) return this.getState();
    this.stamp = await statStamp(this.filePath);
    if (this.stamp !== null) await this.#read();
    return this.getState();
  }

  /**
   * Re-read the file when its mtime/size stamp changed (throttled).
   * @returns {Promise<boolean>} whether the cached set changed
   */
  async refresh() {
    if (!this.filePath) return false;
    const now = this.now();
    if (now - this.lastCheck < this.checkIntervalMs) return false;
    this.lastCheck = now;
    const stamp = await statStamp(this.filePath);
    if (stamp === null) {
      if (this.stamp === null && this.ids.size === 0) return false;
      this.stamp = null;
      this.ids = new Set();
      this.updatedAt = null;
      return true;
    }
    if (stamp === this.stamp) return false;
    this.stamp = stamp;
    await this.#read();
    return true;
  }

  /** Load + parse the current file. */
  async #read() {
    try {
      const text = await fs.readFile(/** @type {string} */ (this.filePath), 'utf8');
      let parsed;
      try {
        parsed = JSON.parse(text);
      } catch (error) {
        this.logger.warn?.(
          `[dsh-mobile-host] hidden sessions file is not valid JSON; treating it as empty: ${String(/** @type {any} */ (error)?.message ?? error)}`,
        );
        this.ids = new Set();
        this.updatedAt = null;
        return;
      }
      const state = parseHiddenSessions(parsed, { cap: this.cap, logger: this.logger });
      this.ids = new Set(state.ids);
      this.updatedAt = state.updatedAt ?? (state.ids.length > 0 ? new Date(this.now()).toISOString() : null);
    } catch (error) {
      if (/** @type {any} */ (error)?.code === 'ENOENT') {
        this.ids = new Set();
        this.updatedAt = null;
        return;
      }
      this.logger.warn?.(
        `[dsh-mobile-host] could not read hidden sessions file; treating it as empty: ${String(/** @type {any} */ (error)?.message ?? error)}`,
      );
      this.ids = new Set();
      this.updatedAt = null;
    }
  }

  /** @param {unknown} sessionId */
  has(sessionId) {
    return typeof sessionId === 'string' && this.ids.has(sessionId);
  }

  /** Current non-secret state for the management API. */
  getState() {
    return { ids: [...this.ids], updatedAt: this.updatedAt };
  }

  /** Number of hidden ids currently cached. */
  get size() {
    return this.ids.size;
  }

  /**
   * Replace the persisted hidden set (management PUT / clear).
   * In-memory-only stores update immediately; file stores write atomically
   * first and only then swap the cache, so a failed write leaves the old set.
   *
   * @param {unknown} rawIds
   * @returns {Promise<{ ids: string[], updatedAt: string | null }>}
   */
  async replace(rawIds) {
    const ids = validateHiddenIds(rawIds, { cap: this.cap });
    const updatedAt = new Date(this.now()).toISOString();
    const run = async () => {
      if (this.filePath) {
        const document = { version: 1, updatedAt, ids };
        const body = `${JSON.stringify(document, null, 2)}\n`;
        const directory = path.dirname(this.filePath);
        try {
          await fs.mkdir(directory, { recursive: true });
          const temporary = `${this.filePath}.${process.pid}.${this.now()}.tmp`;
          await fs.writeFile(temporary, body, 'utf8');
          await fs.rename(temporary, this.filePath);
        } catch (error) {
          throw new ApiError('internal', 'Could not persist the hidden session list', { cause: error });
        }
        this.stamp = await statStamp(this.filePath);
      }
      this.ids = new Set(ids);
      this.updatedAt = updatedAt;
      this.lastCheck = this.now();
    };
    this.writeQueue = this.writeQueue.then(run, run);
    await this.writeQueue;
    return this.getState();
  }
}
