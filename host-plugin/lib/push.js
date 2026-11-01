/**
 * Optional ntfy push publishing.
 *
 * The phone can receive notifications while the app is closed without any
 * third-party account: the host publishes short messages to an ntfy topic the
 * user subscribes to. Configuration is stored next to the pairing state (the
 * token is persisted in plain text at rest; GET never returns it).
 *
 * Safety properties:
 * - disabled by default; no network work until enabled with a topic;
 * - event fan-out is never blocked (short bounded queue, fire-and-forget);
 * - notifications carry a short title/summary and a `dshmobile://` deep link,
 *   never transcripts, tokens, paths, or headers;
 * - fetch is bounded by a timeout; failures are logged and swallowed;
 * - per-notification dedupe plus a 5 s turn-completion rate limit.
 */

import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';

import { ApiError } from './errors.js';

/** Default ntfy server. */
export const DEFAULT_NTFY_SERVER = 'https://ntfy.sh';
/** Topic shape accepted by ntfy and this host. */
export const PUSH_TOPIC_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;
/** Per-request fetch timeout. */
export const PUSH_TIMEOUT_MS = 5000;
/** Queue bound: further notifications are dropped (logged). */
export const PUSH_MAX_QUEUE = 50;
/** Turn-completion dedupe window per session. */
export const PUSH_TURN_DEDUPE_MS = 5000;
/** Token length bound (ntfy access tokens are much shorter). */
export const PUSH_MAX_TOKEN_CHARS = 512;
/** Consecutive delivery failures before the publisher pauses. */
export const PUSH_FAILURE_THRESHOLD = 2;
/** How long publishing pauses after repeated failures. */
export const PUSH_BACKOFF_MS = 60_000;
/** Global cap: notifications enqueued per rolling minute. */
export const PUSH_RATE_LIMIT_PER_MINUTE = 30;
/** Bound on muted session ids persisted with the suppression state. */
export const PUSH_MAX_MUTED_SESSIONS = 500;

/** Empty/default push suppression state. */
export function emptyPushSuppression() {
  return Object.freeze({
    quietHoursEnabled: false,
    quietStartHour: 22,
    quietEndHour: 7,
    timeZone: '',
    mutedSessionIds: Object.freeze([]),
  });
}

/** @param {unknown} value */
export function isValidTimeZone(value) {
  if (typeof value !== 'string' || value.trim() === '') return false;
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: value.trim() });
    return true;
  } catch {
    return false;
  }
}

/** Conservative session-id shape used for muted ids (same style as API ids). */
export const PUSH_SESSION_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/;

/**
 * Normalize app-provided suppression state. Missing fields take `fallback`
 * (partial updates merge), malformed values answer `400 invalid_request`.
 *
 * @param {unknown} raw
 * @param {{ quietHoursEnabled: boolean, quietStartHour: number, quietEndHour: number, timeZone: string, mutedSessionIds: readonly string[] }} [fallback]
 */
export function normalizePushSuppression(raw, fallback = emptyPushSuppression()) {
  const current = isObject(raw) ? raw : {};
  const quietHoursEnabled = current.quietHoursEnabled === undefined
    ? fallback.quietHoursEnabled
    : current.quietHoursEnabled;
  if (typeof quietHoursEnabled !== 'boolean') {
    throw new ApiError('invalid_request', 'Field quietHoursEnabled must be a boolean');
  }
  const hour = (name, fallbackValue) => {
    const value = current[name] === undefined ? fallbackValue : current[name];
    if (!Number.isSafeInteger(value) || value < 0 || value > 23) {
      throw new ApiError('invalid_request', `Field ${name} must be an integer 0..23`);
    }
    return value;
  };
  const quietStartHour = hour('quietStartHour', fallback.quietStartHour);
  const quietEndHour = hour('quietEndHour', fallback.quietEndHour);

  let timeZone = current.timeZone === undefined ? fallback.timeZone : current.timeZone;
  if (typeof timeZone !== 'string') {
    throw new ApiError('invalid_request', 'Field timeZone must be an IANA time-zone string');
  }
  timeZone = timeZone.trim();
  if (timeZone !== '' && !isValidTimeZone(timeZone)) {
    throw new ApiError('invalid_request', 'Field timeZone must be a valid IANA time-zone string');
  }
  if (quietHoursEnabled && timeZone === '') {
    throw new ApiError('invalid_request', 'Field timeZone is required when quietHoursEnabled is true');
  }

  /** @type {string[]} */
  let mutedSessionIds;
  if (current.mutedSessionIds === undefined) {
    mutedSessionIds = [...(fallback.mutedSessionIds ?? [])];
  } else {
    if (!Array.isArray(current.mutedSessionIds)) {
      throw new ApiError('invalid_request', 'Field mutedSessionIds must be an array of session ids');
    }
    if (current.mutedSessionIds.length > PUSH_MAX_MUTED_SESSIONS) {
      throw new ApiError('invalid_request', `Field mutedSessionIds must contain at most ${PUSH_MAX_MUTED_SESSIONS} ids`);
    }
    const seen = new Set();
    mutedSessionIds = [];
    for (const entry of current.mutedSessionIds) {
      if (typeof entry !== 'string') {
        throw new ApiError('invalid_request', 'Field mutedSessionIds must contain strings');
      }
      const id = entry.trim();
      if (!PUSH_SESSION_ID_PATTERN.test(id)) {
        throw new ApiError('invalid_request', 'Field mutedSessionIds contains an invalid session id');
      }
      if (seen.has(id)) continue;
      seen.add(id);
      mutedSessionIds.push(id);
    }
  }
  return { quietHoursEnabled, quietStartHour, quietEndHour, timeZone, mutedSessionIds };
}

/** Public suppression summary (no secrets; token lives only in push config). */
export function publicPushSuppression(suppression) {
  const state = isObject(suppression) ? suppression : {};
  const start = Number.isSafeInteger(state.quietStartHour) ? state.quietStartHour : emptyPushSuppression().quietStartHour;
  const end = Number.isSafeInteger(state.quietEndHour) ? state.quietEndHour : emptyPushSuppression().quietEndHour;
  const timeZone = typeof state.timeZone === 'string' ? state.timeZone.trim() : '';
  return {
    quietHoursEnabled: state.quietHoursEnabled === true,
    quietStartHour: start,
    quietEndHour: end,
    timeZone,
    timeZoneValid: isValidTimeZone(timeZone),
    mutedCount: Array.isArray(state.mutedSessionIds) ? state.mutedSessionIds.length : 0,
  };
}

/**
 * Whether quiet hours are active at instant `now` in the configured IANA zone.
 * The local hour is derived by `Intl` (so DST transitions are handled by the
 * runtime, not by UTC offsets); `start === end` means "no quiet window";
 * `start > end` crosses midnight. Start inclusive, end exclusive.
 *
 * @param {any} suppression normalized suppression state
 * @param {number} [now] epoch ms
 */
export function isQuietHoursActive(suppression, now = Date.now()) {
  if (!isObject(suppression) || suppression.quietHoursEnabled !== true) return false;
  const start = suppression.quietStartHour;
  const end = suppression.quietEndHour;
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || start > 23 || end < 0 || end > 23) {
    return false;
  }
  if (start === end) return false;
  if (!isValidTimeZone(suppression.timeZone)) return false;
  let hour;
  try {
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone: suppression.timeZone,
      hour: '2-digit',
      hourCycle: 'h23',
    }).formatToParts(new Date(now));
    const value = parts.find((part) => part.type === 'hour')?.value ?? '';
    hour = Number.parseInt(value, 10);
  } catch {
    return false;
  }
  if (!Number.isSafeInteger(hour) || hour < 0 || hour > 23) return false;
  return start < end ? hour >= start && hour < end : hour >= start || hour < end;
}

/** Empty/default push configuration. */
export function emptyPushConfig() {
  return Object.freeze({ enabled: false, server: DEFAULT_NTFY_SERVER, topic: '', token: null });
}

/** @param {unknown} value */
function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/** Expand/identify a literal private, loopback or link-local IP host. */
function isPrivateOrLinkLocalHost(hostname) {
  const host = hostname.startsWith('[') && hostname.endsWith(']') ? hostname.slice(1, -1) : hostname;
  if (host === '::1') return true;
  if (/^127\./.test(host) || host === '0.0.0.0') return true;
  if (/^10\./.test(host)) return true;
  if (/^192\.168\./.test(host)) return true;
  if (/^172\.(1[6-9]|2\d|3[01])\./.test(host)) return true;
  if (/^169\.254\./.test(host)) return true; // link-local (incl. cloud metadata)
  if (/^(fc|fd)[0-9a-f]{2}:/i.test(host)) return true; // IPv6 unique-local
  if (/^fe[89ab][0-9a-f]:/i.test(host)) return true; // IPv6 link-local
  return false;
}

/**
 * Validate the ntfy server origin (SSRF guard).
 *
 * - https is required, except plain http for an explicit local host
 *   (`localhost`, `127.0.0.1`, `[::1]`) for self-hosted local servers;
 * - userinfo (credentials) is rejected;
 * - literal loopback/private/link-local IP targets are rejected (only the
 *   explicit localhost forms above are accepted);
 * - only an origin is accepted (no path/query/hash), because the topic is
 *   appended to it.
 *
 * DNS names that resolve to private addresses cannot be detected synchronously;
 * that residual risk is documented in the README.
 *
 * @param {unknown} value
 * @returns {string} normalized origin without trailing slash
 */
export function validatePushServer(value) {
  if (typeof value !== 'string') {
    throw new ApiError('invalid_request', 'Field server must be a URL string');
  }
  const text = value.trim().replace(/\/+$/, '');
  /** @type {URL} */
  let url;
  try {
    url = new URL(text);
  } catch {
    throw new ApiError('invalid_request', 'Field server must be a valid http(s) URL');
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new ApiError('invalid_request', 'Field server must start with http:// or https://');
  }
  if (url.username !== '' || url.password !== '') {
    throw new ApiError('invalid_request', 'Field server must not contain credentials');
  }
  if (url.pathname !== '/' || url.search !== '' || url.hash !== '') {
    throw new ApiError('invalid_request', 'Field server must be an origin URL without a path, query or fragment');
  }
  const hostname = url.hostname.toLowerCase();
  const localhost = hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '[::1]';
  if (url.protocol === 'http:' && !localhost) {
    throw new ApiError(
      'invalid_request',
      'Field server must use https://; plain http is only allowed for localhost/127.0.0.1/[::1]',
    );
  }
  if (!localhost && isPrivateOrLinkLocalHost(hostname)) {
    throw new ApiError('invalid_request', 'Field server must not target a private, loopback or link-local address');
  }
  return `${url.protocol}//${url.host}`;
}

/**
 * Validate/normalize one push configuration.
 *
 * @param {unknown} raw candidate (missing fields take `fallback`)
 * @param {{ enabled: boolean, server: string, topic: string, token: string | null }} [fallback]
 * @returns {{ enabled: boolean, server: string, topic: string, token: string | null }}
 */
export function normalizePushConfig(raw, fallback = emptyPushConfig()) {
  const current = isObject(raw) ? raw : {};
  const enabled = current.enabled === undefined ? fallback.enabled : current.enabled;
  if (typeof enabled !== 'boolean') {
    throw new ApiError('invalid_request', 'Field enabled must be a boolean');
  }

  let server = current.server === undefined ? fallback.server : current.server;
  server = validatePushServer(server);

  let topic = current.topic === undefined ? fallback.topic : current.topic;
  if (typeof topic !== 'string') {
    throw new ApiError('invalid_request', 'Field topic must be a string');
  }
  topic = topic.trim();
  if (topic !== '' && !PUSH_TOPIC_PATTERN.test(topic)) {
    throw new ApiError(
      'invalid_request',
      'Field topic must be 1..64 characters of letters, digits, underscore or dash',
    );
  }

  let token = current.token === undefined ? fallback.token : current.token;
  if (token === null || token === undefined) {
    token = null;
  } else {
    if (typeof token !== 'string') {
      throw new ApiError('invalid_request', 'Field token must be a string or null');
    }
    token = token.trim();
    if (token === '') token = null;
    else if (token.length > PUSH_MAX_TOKEN_CHARS) {
      throw new ApiError('invalid_request', `Field token exceeds ${PUSH_MAX_TOKEN_CHARS} characters`);
    }
  }

  if (enabled && topic === '') {
    throw new ApiError('invalid_request', 'Field topic is required when push is enabled');
  }
  return { enabled, server, topic, token };
}

/** Safe public projection (never includes the token). */
export function publicPushConfig(config) {
  const normalized = normalizePushConfig(config);
  return {
    supported: true,
    enabled: normalized.enabled,
    server: normalized.server,
    topic: normalized.topic,
    hasToken: typeof normalized.token === 'string' && normalized.token !== '',
  };
}

/**
 * Map one bus envelope to a push notification, or null when it is not a push
 * event (resolved/message events etc.).
 *
 * @param {any} envelope `{ type, data, sessionId? }`
 * @returns {{ title: string, text: string, priority: number, tags: string, click: string, dedupeKey?: string, dedupeMs?: number } | null}
 */
export function notificationForEvent(envelope) {
  if (!isObject(envelope)) return null;
  const type = envelope.type;
  const data = isObject(envelope.data) ? envelope.data : {};
  const envelopeSessionId = typeof envelope.sessionId === 'string' ? envelope.sessionId : '';
  const dataSessionId = typeof data.sessionId === 'string' ? data.sessionId : '';
  const sessionId = envelopeSessionId || dataSessionId;

  if (type === 'approval.requested') {
    const approvalId = typeof data.id === 'string' ? data.id : '';
    return {
      title: 'Approval needed',
      text: 'A tool is waiting for approval on the host.',
      priority: 4,
      tags: 'warning',
      click: 'dshmobile://inbox',
      ...(sessionId === '' ? {} : { muteSessionId: sessionId }),
      ...(approvalId === '' ? {} : { dedupeKey: `approval:${approvalId}`, dedupeMs: 0 }),
    };
  }
  if (type === 'question.requested') {
    const questionId = typeof data.id === 'string' ? data.id : '';
    return {
      title: 'Input needed',
      text: 'The host is waiting for an answer.',
      priority: 4,
      tags: 'question',
      click: 'dshmobile://inbox',
      ...(sessionId === '' ? {} : { muteSessionId: sessionId }),
      ...(questionId === '' ? {} : { dedupeKey: `question:${questionId}`, dedupeMs: 0 }),
    };
  }
  if (type === 'goal.changed') {
    const operation = typeof data.operation === 'string' ? data.operation : '';
    const phase = isObject(data.goal) && typeof data.goal.phase === 'string'
      ? data.goal.phase
      : operation === 'pause'
        ? 'paused'
        : operation === 'block'
          ? 'blocked'
          : operation === 'complete'
            ? 'complete'
            : '';
    if (operation !== 'pause' && operation !== 'block' && operation !== 'complete') return null;
    const revision = isObject(data.goal) && Number.isSafeInteger(data.goal.revision)
      ? data.goal.revision
      : '';
    const sessionLink = sessionId === '' ? 'dshmobile://inbox' : `dshmobile://session/${sessionId}`;
    return {
      title: `Goal ${phase || operation}`,
      text: phase === 'blocked' ? 'The goal needs attention.' : `The goal is now ${phase || operation}.`,
      priority: phase === 'blocked' ? 4 : 3,
      tags: phase === 'blocked' ? 'warning' : 'white_check_mark',
      click: sessionLink,
      ...(sessionId === '' || revision === ''
        ? {}
        : { dedupeKey: `goal:${sessionId}:${revision}` }),
      dedupeMs: 0,
    };
  }
  return null;
}

/**
 * Tiny atomic JSON store for the push configuration
 * (`<state dir>/push-config.json`). Missing/corrupt content degrades to the
 * disabled default; token is persisted in plain text at rest (documented).
 */
export class PushConfigFileStore {
  /** @param {string} filePath @param {{ logger?: Pick<Console, 'warn'> }} [options] */
  constructor(filePath, { logger = console } = {}) {
    this.filePath = filePath;
    this.logger = logger;
    /** @type {Promise<void>} */
    this.queue = Promise.resolve();
  }

  async load() {
    try {
      const text = await readFile(this.filePath, 'utf8');
      const parsed = JSON.parse(text);
      return isObject(parsed) ? parsed : null;
    } catch (error) {
      if (/** @type {any} */ (error)?.code !== 'ENOENT') {
        this.logger.warn?.(
          `[dsh-mobile-host] ignoring unreadable push config ${this.filePath}: ${String(
            /** @type {any} */ (error)?.message ?? error,
          )}`,
        );
      }
      return null;
    }
  }

  /** @param {unknown} value */
  async save(value) {
    // The same atomic file carries the push config and the app-provided
    // suppression state; unknown/older files keep the disabled default.
    const snapshot = {
      ...normalizePushConfig(value),
      suppression: normalizePushSuppression(
        isObject(value) && value.suppression !== undefined ? value.suppression : {},
        emptyPushSuppression(),
      ),
    };
    const run = async () => {
      try {
        await mkdir(path.dirname(this.filePath), { recursive: true });
        const temporary = `${this.filePath}.${process.pid}.tmp`;
        await writeFile(temporary, `${JSON.stringify(snapshot, null, 2)}\n`, 'utf8');
        await rename(temporary, this.filePath);
      } catch (error) {
        this.logger.warn?.(
          `[dsh-mobile-host] could not persist push config to ${this.filePath}: ${String(
            /** @type {any} */ (error)?.message ?? error,
          )}`,
        );
      }
    };
    this.queue = this.queue.then(run, run);
    return this.queue;
  }
}

/** Push manager: config lifecycle + fire-and-forget ntfy publishing. */
export class PushManager {
  /**
   * @param {{
   *   bus?: import('./events.js').EventBus | null,
   *   store?: { load?: () => Promise<any>, save?: (value: any) => Promise<void> } | null,
   *   fetchImpl?: typeof fetch,
   *   logger?: Pick<Console, 'warn' | 'error'>,
   *   now?: () => number,
   *   requestTimeoutMs?: number,
   *   turnDedupeMs?: number,
   *   maxQueue?: number,
   * }} [options]
   */
  constructor({
    bus = null,
    store = null,
    fetchImpl = globalThis.fetch,
    logger = console,
    now = Date.now,
    requestTimeoutMs = PUSH_TIMEOUT_MS,
    turnDedupeMs = PUSH_TURN_DEDUPE_MS,
    maxQueue = PUSH_MAX_QUEUE,
  } = {}) {
    this.bus = bus;
    this.store = store;
    this.fetchImpl = typeof fetchImpl === 'function' ? fetchImpl : globalThis.fetch;
    this.logger = logger;
    this.now = now;
    this.requestTimeoutMs = requestTimeoutMs;
    this.turnDedupeMs = turnDedupeMs;
    this.maxQueue = Number.isSafeInteger(maxQueue) && maxQueue > 0 ? maxQueue : PUSH_MAX_QUEUE;
    /** @type {{ enabled: boolean, server: string, topic: string, token: string | null }} */
    this.config = { ...emptyPushConfig() };
    /** @type {{ quietHoursEnabled: boolean, quietStartHour: number, quietEndHour: number, timeZone: string, mutedSessionIds: string[] }} */
    this.suppression = {
      ...emptyPushSuppression(),
      mutedSessionIds: [],
    };
    /** @type {Array<{ title: string, text: string, priority: number, tags: string, click: string }>} */
    this.queue = [];
    /** @type {Map<string, number>} */
    this.dedupe = new Map();
    this.draining = false;
    this.closed = false;
    this.consecutiveFailures = 0;
    this.pausedUntil = 0;
    /** @type {number[]} */
    this.sentAt = [];
    this.unsubscribe = null;
    if (bus && typeof bus.subscribe === 'function') {
      try {
        this.unsubscribe = bus.subscribe((envelope) => {
          try {
            const notification = notificationForEvent(envelope);
            if (notification) this.#enqueue(notification);
          } catch (error) {
            this.logger.warn?.(
              `[dsh-mobile-host] push event mapping failed: ${String(
                /** @type {any} */ (error)?.message ?? error,
              )}`,
            );
          }
        });
      } catch {
        this.unsubscribe = null;
      }
    }
  }

  /** Load the persisted configuration (missing/corrupt → disabled default). */
  async load() {
    try {
      const raw = await this.store?.load?.();
      this.config = normalizePushConfig(raw ?? {}, emptyPushConfig());
      this.suppression = normalizePushSuppression(
        isObject(raw) && raw.suppression !== undefined ? raw.suppression : {},
        emptyPushSuppression(),
      );
    } catch (error) {
      this.config = { ...emptyPushConfig() };
      this.suppression = { ...emptyPushSuppression(), mutedSessionIds: [] };
      this.logger.warn?.(
        `[dsh-mobile-host] ignoring invalid push config: ${String(
          /** @type {any} */ (error)?.message ?? error,
        )}`,
      );
    }
    return this.getPublicConfig();
  }

  /** Persist config+suppression together in the one atomic push state file. */
  async #persist() {
    try {
      await this.store?.save?.({ ...this.config, suppression: this.suppression });
    } catch (error) {
      this.logger.warn?.(
        `[dsh-mobile-host] could not persist push config: ${String(
          /** @type {any} */ (error)?.message ?? error,
        )}`,
      );
    }
  }

  /** Public, token-free projection. */
  getPublicConfig() {
    return publicPushConfig(this.config);
  }

  /**
   * Replace/merge the configuration and persist it.
   * @param {unknown} body
   */
  async update(body) {
    const candidate = isObject(body) ? body : {};
    if (typeof candidate.enabled !== 'boolean') {
      throw new ApiError('invalid_request', 'Field enabled must be a boolean');
    }
    const next = normalizePushConfig(candidate, {
      enabled: this.config.enabled,
      server: this.config.server,
      topic: this.config.topic,
      token: this.config.token,
    });
    if (next.enabled === false && next.topic === '' && this.config.topic === '') {
      // Valid (subscribing is optional while disabled); nothing to do.
    }
    this.config = next;
    await this.#persist();
    return this.getPublicConfig();
  }

  /** Public, token-free suppression summary for GET /push. */
  getSuppressionSummary() {
    return publicPushSuppression(this.suppression);
  }

  /**
   * Replace/merge the app-provided suppression state and persist it with the
   * push config atomically. Missing fields keep their current values.
   *
   * @param {unknown} body
   */
  async updateSuppression(body) {
    const candidate = isObject(body) ? body : {};
    this.suppression = normalizePushSuppression(candidate, this.suppression);
    await this.#persist();
    return this.getSuppressionSummary();
  }

  /** Send one synchronous test notification (returns success/error to the API). */
  async sendTest() {
    const config = normalizePushConfig(this.config);
    if (!config.enabled || config.topic === '') {
      throw new ApiError('invalid_request', 'Push notifications are not enabled for this host');
    }
    try {
      await this.#deliverBase({
        title: 'DSH Mobile test',
        text: 'Push notifications are working.',
        priority: 3,
        tags: 'white_check_mark',
        click: 'dshmobile://inbox',
      });
      this.consecutiveFailures = 0;
      this.pausedUntil = 0;
      return { ok: true };
    } catch (error) {
      const status = Number(/** @type {any} */ (error)?.httpStatus) || 0;
      throw new ApiError(
        'internal',
        status > 0
          ? `Push server rejected the test notification (HTTP ${status})`
          : 'Could not reach the push server',
        { status: 502, cause: error },
      );
    }
  }

  /**
   * Adapter hook for `turn/end` (no bus event exists for it).
   * @param {string} sessionId
   * @param {string} [sessionTitle]
   */
  handleTurnFinished(sessionId, sessionTitle) {
    const id = String(sessionId ?? '');
    if (id === '') return;
    const title = typeof sessionTitle === 'string' && sessionTitle.trim() !== '' ? sessionTitle.trim() : '';
    this.#enqueue({
      title: 'Turn finished',
      text: title === '' ? 'The agent finished a turn.' : `Finished in ${title.slice(0, 100)}.`,
      priority: 3,
      tags: 'white_check_mark',
      click: `dshmobile://session/${id}`,
      muteSessionId: id,
      dedupeKey: `turn:${id}`,
      dedupeMs: this.turnDedupeMs,
    });
  }

  /** Stop listening and drop queued work. */
  close() {
    this.closed = true;
    this.queue.length = 0;
    this.dedupe.clear();
    try {
      this.unsubscribe?.();
    } catch {
      /* already closed */
    }
    this.unsubscribe = null;
  }

  /** Queue one notification (fire-and-forget). */
  #enqueue(notification) {
    if (this.closed) return;
    if (!this.config.enabled || this.config.topic === '') return;
    const now = this.now();
    // App-provided suppression is evaluated before dedupe/rate capacity so a
    // muted/quiet notification never consumes those budgets.
    if (isQuietHoursActive(this.suppression, now)) return;
    const muteSessionId = typeof notification?.muteSessionId === 'string' ? notification.muteSessionId : '';
    if (muteSessionId !== '' && this.suppression.mutedSessionIds.includes(muteSessionId)) return;
    // Circuit breaker: after repeated delivery failures stop trying for a while.
    if (now < this.pausedUntil) return;
    // Global rate cap (rolling minute), independent of per-event dedupe.
    this.sentAt = this.sentAt.filter((sentAt) => now - sentAt < 60_000);
    if (this.sentAt.length >= PUSH_RATE_LIMIT_PER_MINUTE) {
      this.logger.warn?.('[dsh-mobile-host] push rate cap reached; dropping a notification');
      return;
    }
    this.sentAt.push(now);
    const key = typeof notification?.dedupeKey === 'string' ? notification.dedupeKey : '';
    if (key !== '') {
      const windowMs = Number.isFinite(notification?.dedupeMs) ? notification.dedupeMs : 0;
      const last = this.dedupe.get(key);
      if (windowMs > 0 && typeof last === 'number' && now - last < windowMs) return;
      if (windowMs === 0 && typeof last === 'number') {
        // Zero-window dedupe: only a repeated identical event id is dropped.
        if (now - last < 60_000) return;
      }
      this.dedupe.set(key, now);
      if (this.dedupe.size > 500) {
        const oldest = this.dedupe.keys().next().value;
        if (oldest !== undefined) this.dedupe.delete(oldest);
      }
    }
    if (this.queue.length >= this.maxQueue) {
      this.logger.warn?.('[dsh-mobile-host] push queue full; dropping a notification');
      return;
    }
    this.queue.push({
      title: String(notification?.title ?? 'DSH Mobile'),
      text: String(notification?.text ?? ''),
      priority: Number.isSafeInteger(notification?.priority) ? notification.priority : 3,
      tags: String(notification?.tags ?? ''),
      click: String(notification?.click ?? 'dshmobile://inbox'),
    });
    void this.#drain();
  }

  /** Drain queued notifications serially; failures are logged only. */
  async #drain() {
    if (this.draining || this.closed) return;
    this.draining = true;
    try {
      while (this.queue.length > 0 && !this.closed) {
        if (this.now() < this.pausedUntil) {
          this.queue.length = 0;
          break;
        }
        const notification = this.queue.shift();
        if (!notification) continue;
        try {
          await this.#deliverBase(notification);
          this.consecutiveFailures = 0;
        } catch (error) {
          this.consecutiveFailures += 1;
          if (this.consecutiveFailures >= PUSH_FAILURE_THRESHOLD) {
            this.pausedUntil = this.now() + PUSH_BACKOFF_MS;
            this.logger.warn?.(
              `[dsh-mobile-host] push delivery failed ${this.consecutiveFailures} times; pausing for ${Math.round(
                PUSH_BACKOFF_MS / 1000,
              )}s`,
            );
          }
          this.logger.warn?.(
            `[dsh-mobile-host] push notification failed: ${String(
              /** @type {any} */ (error)?.message ?? error,
            )}`,
          );
        }
      }
    } finally {
      this.draining = false;
    }
  }

  /** One bounded POST; surfaces HTTP status for the test endpoint. */
  async #deliverBase(notification) {
    const config = normalizePushConfig(this.config);
    if (!config.enabled || config.topic === '') {
      const error = new Error('Push is not enabled');
      /** @type {any} */ (error).httpStatus = 0;
      throw error;
    }
    const url = `${config.server.replace(/\/+$/, '')}/${encodeURIComponent(config.topic)}`;
    /** @type {Record<string, string>} */
    const headers = {
      Title: notification.title,
      Priority: String(notification.priority),
      ...(notification.tags ? { Tags: notification.tags } : {}),
      Click: notification.click,
      'Content-Type': 'text/plain; charset=utf-8',
    };
    if (config.token) headers.Authorization = `Bearer ${config.token}`;

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.requestTimeoutMs);
    timer.unref?.();
    try {
      const response = await this.fetchImpl(url, {
        method: 'POST',
        headers,
        body: notification.text,
        signal: controller.signal,
      });
      if (!response || response.ok !== true) {
        const status = Number(response?.status) || 0;
        const error = new Error(`Push server rejected the notification (HTTP ${status})`);
        /** @type {any} */ (error).httpStatus = status;
        throw error;
      }
    } finally {
      clearTimeout(timer);
    }
  }
}

/** Create one push manager (index wiring convenience). */
export function createPushManager(options = {}) {
  return new PushManager(options);
}
