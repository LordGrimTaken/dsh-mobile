/**
 * Thin DSH-facing adapter for the Mobile API.
 *
 * Everything DSH-specific lives here: resolving optional Cordis services,
 * calling `sessionController` / `sessionQuery` / `workspaceRegistry`, folding
 * live events into the SSE bus, and normalizing DSH failures into ApiErrors.
 *
 * The HTTP/pairing layer never imports DSH packages, and this module works with
 * a plain fake `ctx` in tests (see test/adapter.test.mjs), so the whole host can
 * be unit-tested with no DSH process and no network.
 *
 * Verified against the live inspect contracts of:
 *   - sessionController.list/create/prompt/cancel  (@deepseek-ai/dsh-api-session-controller)
 *   - sessionQuery.readSession/readTitleSnapshots  (@deepseek-ai/dsh-session-query)
 *   - workspaceRegistry.list                       (@deepseek-ai/dsh-workspace)
 * and the `session/event`, `agent/assistant-stream`, `api-session/*` events.
 */

import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { ApiError, toApiError } from './errors.js';
import { createApprovalMirror } from './approval-mirror.js';
import { createQuestionMirror } from './question-mirror.js';
import { isoTime, buildMessages, collectSessionAttachments, extractText, firstUserText, mapGoalProjection, mapGoalSummary, mapHeaderSummary, mapSessionJobs, mapSessionSummary, mapSessionTeam, mapSessionTodos, mapSessionTrajectory, mapWorkspace, messageAttachments, paginateMessages, previewFromEvents, readWorkspaceImage, toolDetail, toolOutput, workspaceImageAttachments } from './mappers.js';
import { RemoteImageFetcher } from './remote-images.js';
import {
  mapScheduleRecord,
  scheduleRecordForUpdate,
  scheduleServiceError,
  sortSchedules,
} from './schedules.js';
import { normalizeSessionTitle } from './sessions.js';
import { mapPresetRoster } from './presets.js';
import {
  foldModelSelection,
  mapModelCatalog,
  normalizeModelSelection,
  publicModelSelection,
} from './models.js';
import { mapQueueItems, normalizeQueueAction } from './queue.js';
import {
  SEARCH_MAX_EVENTS_PER_SESSION,
  SEARCH_MAX_MATCHES,
  SEARCH_MAX_MATCHES_PER_SESSION,
  SEARCH_MAX_SESSIONS,
  SEARCH_MIN_READ_MS,
  SEARCH_READ_CONCURRENCY,
  SEARCH_SEMANTIC_EVENT_TYPES,
  SEARCH_WALL_CLOCK_BUDGET_MS,
  hasSearchFilters,
  matchesFromSemanticDocuments,
  normalizeSearchFilters,
  normalizeSearchLimit,
  normalizeSearchOffset,
  normalizeSearchQuery,
  paginateSearchHits,
  searchEvents,
} from './search.js';

/** Read one optional Cordis service without throwing when it is absent. */
function optionalService(ctx, name) {
  try {
    if (ctx && typeof ctx.get === 'function') return ctx.get(name);
    return ctx ? /** @type {any} */ (ctx)[name] : undefined;
  } catch {
    return undefined;
  }
}

/** A fresh AbortSignal for one DSH call. */
function newSignal() {
  return new AbortController().signal;
}

/** True on Windows; used for platform-appropriate path matching. */
const IS_WINDOWS = process.platform === 'win32';

/**
 * Normalize a filesystem path for workspace matching.
 * Separators are unified to `/` and trailing separators dropped; Windows paths
 * are additionally case-insensitive. POSIX paths keep their case (host plugin
 * runs on headless Linux hosts too).
 */
export function normalizePath(value) {
  if (typeof value !== 'string' || value === '') return '';
  const unified = value.replace(/[\\/]+/g, '/').replace(/\/+$/, '');
  return IS_WINDOWS ? unified.toLowerCase() : unified;
}

/**
 * Resolve a Node builtin without importing it statically (host stays install-free).
 * Safe to call outside a plugin context; used by the cache-path helper too.
 */
function nodeModule(name) {
  try {
    return process.getBuiltinModule?.(name);
  } catch {
    return undefined;
  }
}

/**
 * Location of the persisted session-list enrichment cache.
 *
 * `sessions.cacheFile` wins (`false` disables persistence, a string is an
 * explicit path). The default lives next to the pairing state file so two DSH
 * profiles on one machine (desktop + a headless `web` CLI profile) never share
 * session-list content or hidden markers through a global temp file.
 *
 * @param {any} config resolved plugin config
 * @returns {string | null}
 */
export function sessionListCachePath(config) {
  const configured = config?.sessions?.cacheFile;
  if (configured === false) return null;
  if (typeof configured === 'string' && configured.trim() !== '') return configured;
  const path = nodeModule('node:path');
  if (path && typeof config?.stateFile === 'string' && config.stateFile !== '') {
    return path.join(path.dirname(config.stateFile), 'session-list-cache.json');
  }
  const os = nodeModule('node:os');
  return os && path ? path.join(os.tmpdir(), `dsh-mobile-sessions-cache-${process.pid}.json`) : null;
}

/** True when a DSH error means "this session does not exist". */
export function isSessionNotFound(error) {
  const code = /** @type {any} */ (error)?.code;
  if (typeof code === 'string' && (code === 'session/not-found' || code.endsWith('/not-found'))) return true;
  if (typeof code === 'string' && code === 'ENOENT') return true;
  return false;
}

/**
 * Why a DSH session-list row is an internal (subagent/teammate) session, or
 * undefined for a user session.
 *
 * Markers, in priority order (verified against live DSH 0.2.1-rc.5 data):
 * 1. `origin === 'subagent'` — persisted SessionHeader field, exposed by
 *    `sessionController.list` (`SessionSummary.origin`).
 * 2. `parentSessionId` — a forked/child session, also exposed by the list row.
 * 3. `projections.values.subagent.identity` — the `subagent` projection row;
 *    live projection caches carry `{ identity: { mode, label, seq } }` for
 *    subagent sessions and `{}` for top-level ones.
 * 4. Documented heuristic fallback: DSH mints ordinary sessions as
 *    `session-<uuid>` while subagent/teammate sessions use a bare UUID. Only
 *    applied when no positive top-level marker is present.
 *
 * @param {any} summary one `sessionController.list` row
 * @returns {string | undefined} marker name when internal
 */
export function internalReasonFromSessionSummary(summary) {
  if (!summary || typeof summary !== 'object') return undefined;
  if (summary.origin === 'subagent') return 'origin=subagent';
  if (summary.parentSessionId !== undefined && summary.parentSessionId !== null && String(summary.parentSessionId) !== '') {
    return 'parentSessionId';
  }
  const subagent = summary.projections?.values?.subagent;
  if (subagent && typeof subagent === 'object' && subagent.identity && typeof subagent.identity === 'object') {
    return 'subagent-projection';
  }
  const id = typeof summary.sessionId === 'string' ? summary.sessionId : '';
  if (id !== '' && !id.startsWith('session-')) return 'id-heuristic';
  return undefined;
}

/**
 * Why a persisted `SessionHeader` is an internal session, or undefined.
 * The live `session/event` feed carries the Session object, whose `header`
 * exposes these same fields.
 *
 * @param {any} header
 * @returns {string | undefined} marker name when internal
 */
export function internalReasonFromSessionHeader(header) {
  if (!header || typeof header !== 'object') return undefined;
  if (header.origin === 'subagent') return 'origin=subagent';
  if (header.parentSession !== undefined && header.parentSession !== null && String(header.parentSession) !== '') {
    return 'parentSession';
  }
  if (typeof header.delegationDepth === 'number' && header.delegationDepth > 0) return 'delegationDepth';
  const id = typeof header.id === 'string' ? header.id : '';
  if (id !== '' && !id.startsWith('session-')) return 'id-heuristic';
  return undefined;
}

/** Latest known event time for a summary. */
function latestEventTime(events, fallback) {
  for (let index = (events?.length ?? 0) - 1; index >= 0; index -= 1) {
    const time = /** @type {any} */ (events[index])?.time;
    if (typeof time === 'number') return time;
  }
  return typeof fallback === 'number' ? fallback : Date.now();
}

/** Opaque DSH attachment-id shape accepted by the read-back route (no paths). */
const ATTACHMENT_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/;

/**
 * Safe Content-Disposition for an attachment name. Header-injection characters
 * and non-ASCII are replaced so a host filename can never break the response
 * headers. Images may render inline; files are always a download so a hostile
 * HTML/SVG payload cannot execute in a browser that opens the URL directly.
 * @param {unknown} name
 * @param {'inline' | 'attachment'} [type]
 */
function safeDisposition(name, type = 'inline') {
  const cleaned = String(name ?? '')
    .replace(/[^\x20-\x7E]/g, '_')
    .replace(/["\\]/g, '_')
    .slice(0, 180)
    .trim();
  const kind = type === 'attachment' ? 'attachment' : 'inline';
  return `${kind}; filename="${cleaned === '' ? 'attachment' : cleaned}"`;
}

/**
 * Create the DSH adapter.
 *
 * @param {{
 *   ctx: any,
 *   config: any,
 *   bus?: import('./events.js').EventBus | null,
 *   logger?: Pick<Console, 'warn' | 'error'>,
 *   hiddenStore?: import('./hidden-sessions.js').HiddenSessionStore | null,
 *   approvalMirror?: import('./approval-mirror.js').ApprovalMirror | null,
 *   questionMirror?: import('./question-mirror.js').QuestionMirror | null,
 *   push?: import('./push.js').PushManager | null,
 *   searchBudgetMs?: number,
 * }} options
 */
export function createDshAdapter({
  ctx,
  config,
  bus = null,
  logger = console,
  hiddenStore = null,
  approvalMirror = null,
  questionMirror = null,
  push = null,
  searchBudgetMs = null,
  remoteImageFetcher = null,
}) {
  /** Whole-request search budget (tests may shrink it; production default 3 s). */
  const searchBudget =
    Number.isSafeInteger(searchBudgetMs) && searchBudgetMs > 0
      ? searchBudgetMs
      : SEARCH_WALL_CLOCK_BUDGET_MS;
  /** @type {Map<string, boolean>} */
  const runningSessions = new Map();
  /** @type {Map<string, string>} active assistant stream message id per session */
  const activeAttempts = new Map();
  /** @type {Map<string, Map<string, { name: string, detail: string }>>} */
  const toolInfo = new Map();

  // Approval mirror: observes the `approval/request` waterfall and, when
  // `approvals.mobileAnswer` is enabled, races a deferred mobile decision against
  // the desktop answerer (first answer wins). The adapter owns visibility +
  // command lookup so hidden/internal sessions never leak.
  const mirror =
    approvalMirror ??
    createApprovalMirror({
      bus,
      logger,
      mobileAnswer: config?.approvals?.mobileAnswer !== false,
    });
  try {
    mirror.setSessionFilter?.((sessionId) => !isHiddenSession(sessionId));
    mirror.setCommandResolver?.((sessionId, callId) => {
      if (!sessionId || !callId) return '';
      return toolInfo.get(String(sessionId))?.get(String(callId))?.detail ?? '';
    });
  } catch {
    /* the mirror keeps its defaults; approval reads stay best-effort */
  }

  // Question mirror: same waterfall observer. With `questions.mobileAnswer`
  // enabled the phone can answer a whole batch (first whole answer wins); the
  // adapter owns visibility filtering.
  const questionMirrorInstance =
    questionMirror ??
    createQuestionMirror({
      bus,
      logger,
      mobileAnswer: config?.questions?.mobileAnswer !== false,
    });
  try {
    questionMirrorInstance.setSessionFilter?.((sessionId) => !isHiddenSession(sessionId));
  } catch {
    /* the mirror keeps its defaults; question reads stay best-effort */
  }

  /* ------------------------------------------------------------------ */
  /* Session-list fast path: caches + background refresh                 */
  /* ------------------------------------------------------------------ */

  /** Cached session-list enrichment, persisted across host restarts. */
  const sessionListCache = {
    /** @type {Map<string, string>} */
    titles: new Map(),
    /** @type {Map<string, { preview: string, firstUser: string }>} */
    previews: new Map(),
    /** @type {Map<string, string>} last known agent preset per session */
    agentPresets: new Map(),
    /** @type {Array<Record<string, any>> | null} */
    payload: null,
    /** @type {number} */
    payloadAt: 0,
    /** @type {number} */
    lastRequestAt: 0,
    /** @type {Promise<void> | null} */
    refreshing: null,
    /** @type {number} */
    lastRefresh: 0,
    /** @type {ReturnType<typeof setTimeout> | null} */
    persistTimer: null,
    /** @type {ReturnType<typeof setTimeout> | null} */
    refreshTimer: null,
    /** @type {boolean} */
    loaded: false,
  };

  /**
   * Session ids proven internal (subagent/teammate) from list rows or headers.
   * Kept across the list cache so SSE can filter before a refresh lands.
   * @type {Set<string>}
   */
  const internalSessionIds = new Set();

  /**
   * Last known workspace archive set (ids are hidden from the normal session
   * list). Refreshed lazily so archiving on the desktop also hides rows here.
   * @type {{ ids: Set<string> | null, at: number }}
   */
  const archivedSessionCache = { ids: null, at: 0 };

  /**
   * Aggregate internal direct-child counts keyed by visible parent id. Derived
   * only from raw `sessionController.list` rows (no Agent activation); the map
   * is refreshed with the list payload or lazily for a detail read.
   * @type {{ map: Map<string, { total: number, working: number }> | null, at: number }}
   */
  const delegationCache = { map: null, at: 0 };

  /**
   * Session ids that are blank and stale (auto-hidden for the list, not
   * persisted). Recomputed from every list payload.
   * @type {Set<string>}
   */
  const autoHiddenIds = new Set();

  /** Cached DeepSeek API-key balance read (30s TTL); never contains the key. */
  const balanceCache = { at: 0, value: null, error: null };
  /** Cached projection-cache usage scan (10s TTL). */
  const projectionUsageCache = { at: 0, value: null };
  /** Sessions whose broken/unreadable context projection file was already logged. */
  const contextCacheWarned = new Set();
  /** Bound the per-session warning set so malformed caches cannot grow it forever. */
  const CONTEXT_CACHE_WARN_LIMIT = 500;

  /**
   * Record one warned session, evicting the oldest entry when the bound is hit.
   * Returns true only when this call should emit the warning.
   *
   * @param {string} sessionId
   */
  function markContextCacheWarned(sessionId) {
    if (contextCacheWarned.has(sessionId)) return false;
    if (contextCacheWarned.size >= CONTEXT_CACHE_WARN_LIMIT) {
      const oldest = contextCacheWarned.values().next().value;
      if (oldest !== undefined) contextCacheWarned.delete(oldest);
    }
    contextCacheWarned.add(sessionId);
    return true;
  }

  /** Current config switch (defaults to hiding internal sessions). */
  function hideInternalSessions() {
    return config?.sessions?.hideInternal !== false;
  }

  /** Remember/forget one session's internal marker from its header. */
  function noteSessionHeader(sessionId, header) {
    const id = String(sessionId ?? '');
    if (id === '') return;
    if (internalReasonFromSessionHeader(header) !== undefined) internalSessionIds.add(id);
    else if (header && typeof header === 'object') internalSessionIds.delete(id);
  }

  /** Remember/forget markers for a batch of `sessionController.list` rows. */
  function absorbInternalMarkers(items) {
    for (const item of items ?? []) {
      const id = String(item?.sessionId ?? '');
      if (id === '') continue;
      if (internalReasonFromSessionSummary(item) !== undefined) internalSessionIds.add(id);
      else internalSessionIds.delete(id);
    }
  }

  /** Whether the operator persisted this id as hidden (`sessions/hidden`). */
  function isExplicitlyHidden(sessionId) {
    return hiddenStore?.has?.(sessionId) === true;
  }

  /**
   * Whether one session must stay out of the mobile list and SSE stream.
   * Combines the three independent filters: internal (subagent) sessions, the
   * operator's persisted hidden list, and stale blank auto-hiding.
   * @param {unknown} sessionId
   */
  function isHiddenSession(sessionId) {
    const id = String(sessionId ?? '');
    if (id === '') return false;
    if (isExplicitlyHidden(id) || autoHiddenIds.has(id)) return true;
    if (!hideInternalSessions()) return false;
    if (internalSessionIds.has(id)) return true;
    return !id.startsWith('session-');
  }

  /** Internal-only check (used where the operator hide list must not matter). */
  function isInternalSession(sessionId) {
    const id = String(sessionId ?? '');
    if (id === '' || !hideInternalSessions()) return false;
    if (internalSessionIds.has(id)) return true;
    return !id.startsWith('session-');
  }

  /** Lazy mtime refresh of the persisted hidden list (cheap, throttled in the store). */
  function refreshHiddenStore() {
    try {
      void hiddenStore?.refresh?.();
    } catch {
      /* hidden list refresh is best-effort */
    }
  }

  /**
   * Stale-blank rule for one raw `sessionController.list` row: no title, no
   * preview, not working, and older than `sessions.hideBlankAfterMs`.
   *
   * Two knowledge levels:
   * - DSH's own `blank` flag is authoritative (the session never started a turn);
   * - otherwise only hide when we have already resolved title/preview knowledge
   *   for the id, so a cold-start list can never hide a real session whose title
   *   has not been loaded yet (it would flicker back after enrichment).
   *
   * @param {Record<string, any>} item
   * @param {number} now
   */
  function isStaleBlankListItem(item, now) {
    const blankAfterMs = config?.sessions?.hideBlankAfterMs;
    if (typeof blankAfterMs !== 'number' || blankAfterMs <= 0) return false;
    if (item?.running === true) return false;
    const updatedAt = typeof item?.updatedAt === 'number' ? item.updatedAt : Number.NaN;
    if (!Number.isFinite(updatedAt) || now - updatedAt < blankAfterMs) return false;
    const id = String(item?.sessionId ?? '');
    const projectionTitle = item?.projections?.values?.title;
    const cachedTitle = sessionListCache.titles.get(id);
    const cachedPreview = sessionListCache.previews.get(id);
    const title = String(
      typeof projectionTitle === 'string' && projectionTitle.trim() !== ''
        ? projectionTitle.trim()
        : (cachedTitle ?? ''),
    ).trim();
    const untitled = title === '' || title === 'Untitled session';
    const preview = String(cachedPreview?.preview ?? '').trim();
    if (!untitled || preview !== '') return false;
    const knowledgeResolved =
      (typeof projectionTitle === 'string' && projectionTitle.trim() !== '') ||
      cachedTitle !== undefined ||
      cachedPreview !== undefined;
    return item?.blank === true || knowledgeResolved;
  }

  /** Recompute the stale-blank auto-hidden set from raw list rows. */
  function refreshAutoHidden(items) {
    autoHiddenIds.clear();
    const now = Date.now();
    for (const item of items ?? []) {
      const id = String(item?.sessionId ?? '');
      if (id !== '' && isStaleBlankListItem(item, now)) autoHiddenIds.add(id);
    }
    return autoHiddenIds;
  }

  /** @param {Array<Record<string, any>> | null} payload */
  function visibleSessionPayload(payload) {
    if (!Array.isArray(payload)) return payload;
    return payload.filter((summary) => {
      const id = summary?.id;
      if (isExplicitlyHidden(id) || autoHiddenIds.has(id)) return false;
      if (isInternalSession(id)) return false;
      return true;
    });
  }

  /** Cache file location; null when the host cannot write one. */
  function sessionListCacheFile() {
    // Default is scoped next to the pairing state file, so a desktop profile and
    // a headless `web` CLI profile on one machine never share list content.
    return sessionListCachePath(config);
  }

  /** Load the persisted title/preview cache once. */
  function loadSessionListCache() {
    if (sessionListCache.loaded) return;
    sessionListCache.loaded = true;
    const fs = nodeModule('node:fs');
    const file = sessionListCacheFile();
    if (!fs || !file) return;
    try {
      const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
      if (Array.isArray(parsed?.titles)) {
        for (const entry of parsed.titles) {
          if (Array.isArray(entry) && typeof entry[0] === 'string' && typeof entry[1] === 'string' && entry[1].trim() !== '') {
            sessionListCache.titles.set(entry[0], entry[1]);
          }
        }
      }
      const previews = parsed?.previews;
      if (previews && typeof previews === 'object') {
        for (const [id, value] of Object.entries(previews)) {
          if (value && typeof value.preview === 'string') {
            sessionListCache.previews.set(id, {
              preview: value.preview,
              firstUser: typeof value.firstUser === 'string' ? value.firstUser : '',
            });
          }
        }
      }
      if (Array.isArray(parsed?.payload) && parsed.payload.length > 0) {
        sessionListCache.payload = parsed.payload;
        sessionListCache.payloadAt = typeof parsed.at === 'number' ? parsed.at : 0;
      }
      // Precise internal-session markers survive a host restart, so the first
      // request after boot already filters subagent sessions (the bare-UUID
      // heuristic is the second line of defence in isHiddenSession).
      if (Array.isArray(parsed?.hidden)) {
        for (const id of parsed.hidden) {
          if (typeof id === 'string' && id !== '') internalSessionIds.add(id);
        }
      }
    } catch {
      /* first run or unreadable cache: the background refresh rebuilds it */
    }
  }

  /** Persist the cache soon (debounced, atomic, best-effort). */
  function persistSessionListCacheSoon() {
    if (sessionListCache.persistTimer !== null) return;
    sessionListCache.persistTimer = setTimeout(() => {
      sessionListCache.persistTimer = null;
      const fs = nodeModule('node:fs');
      const file = sessionListCacheFile();
      if (!fs || !file) return;
      try {
        const pathModule = nodeModule('node:path');
        if (pathModule) fs.mkdirSync(pathModule.dirname(file), { recursive: true });
        const document = {
          at: Date.now(),
          payload: sessionListCache.payload,
          hidden: [...internalSessionIds].slice(-500),
          titles: [...sessionListCache.titles.entries()].slice(-400),
          previews: Object.fromEntries([...sessionListCache.previews.entries()].slice(-120)),
        };
        const temporary = `${file}.tmp`;
        fs.writeFileSync(temporary, JSON.stringify(document));
        fs.renameSync(temporary, file);
      } catch {
        /* cache is best-effort */
      }
    }, 750);
    sessionListCache.persistTimer.unref?.();
  }

  /**
   * Fold cheap projection titles from `controller.list` rows into the cache.
   * @param {any[]} items
   * @returns {number} how many titles were taken
   */
  function absorbProjectionTitles(items) {
    let found = 0;
    for (const item of items) {
      const id = String(item?.sessionId ?? '');
      const values = item?.projections?.values;
      const title = values && typeof values.title === 'string' ? values.title.trim() : '';
      if (id !== '' && title !== '') {
        sessionListCache.titles.set(id, title);
        found += 1;
      }
    }
    return found;
  }

  /** Run `worker` over `items` with bounded concurrency. */
  async function mapWithConcurrency(items, limit, worker) {
    let index = 0;
    const runners = Array.from({ length: Math.min(limit, items.length) }, async () => {
      while (index < items.length) {
        const current = items[index];
        index += 1;
        await worker(current);
      }
    });
    await Promise.allSettled(runners);
  }

  /**
   * Aggregate internal direct children of every visible parent from raw list
   * rows. Direct children only (a grandchild's parent is the child id, not the
   * top-level parent); `working` is the best-effort DSH `running` flag.
   *
   * @param {any[]} items raw `sessionController.list` rows
   * @param {any[]} items raw `sessionController.list` rows
   * @param {Set<string> | null} archivedIds archived child ids to exclude;
   *   `null` means archive state is unknown and every count is withheld.
   * @returns {Map<string, { total: number, working: number }>}
   */
  function computeDelegationCounts(items, archivedIds) {
    /** @type {Map<string, { total: number, working: number }>} */
    const counts = new Map();
    if (archivedIds === null) return counts;
    for (const item of items ?? []) {
      if (internalReasonFromSessionSummary(item) === undefined) continue;
      if (archivedIds.has(String(item?.sessionId ?? ''))) continue;
      const parentId = optionalId(item?.parentSessionId);
      if (parentId === undefined) continue;
      const current = counts.get(parentId) ?? { total: 0, working: 0 };
      current.total += 1;
      if (item?.running === true) current.working += 1;
      counts.set(parentId, current);
    }
    return counts;
  }

  /**
   * Read (and briefly cache) delegation counts from the raw session list. Any
   * list failure degrades to "unknown" (the field is omitted), never a 500.
   *
   * @returns {Promise<Map<string, { total: number, working: number }> | null>}
   */
  async function readDelegationCounts() {
    if (delegationCache.map !== null && Date.now() - delegationCache.at < 5000) {
      return delegationCache.map;
    }
    const { sessionController } = services();
    if (!sessionController || typeof sessionController.list !== 'function') return null;
    try {
      const value = await sessionController.list({}, newSignal());
      const items = Array.isArray(value?.items) ? value.items : [];
      let archived = null;
      try {
        archived = await currentArchivedSessionIds();
      } catch {
        archived = null;
      }
      // Unknown archive state could inflate a live parent's count with archived
      // children; withhold the aggregate instead of guessing.
      if (archived === null) return null;
      delegationCache.map = computeDelegationCounts(items, archived);
      delegationCache.at = Date.now();
      return delegationCache.map;
    } catch {
      return null;
    }
  }

  /**
   * Delegation counts for one visible parent; undefined for internal/hidden
   * parents and when the children cannot be read.
   *
   * @param {string} sessionId
   * @param {any} [header]
   */
  async function delegationForSession(sessionId, header) {
    if (isHiddenSession(sessionId)) return undefined;
    if (internalReasonFromSessionHeader(header) !== undefined) return undefined;
    const counts = await readDelegationCounts();
    return counts?.get(String(sessionId));
  }

  /**
   * Build the wire session list from raw controller rows plus cached enrichment.
   * @param {any[]} items
   * @param {Map<string, string>} bySession
   * @param {Map<string, string>} byPath
   * @param {Set<string> | null} archivedIds archived ids from the current archive state
   * @returns {Array<Record<string, any>>}
   */
  function buildSessionListPayload(items, bySession, byPath, archivedIds) {
    const delegationCounts = computeDelegationCounts(items, archivedIds);
    if (archivedIds !== null) {
      delegationCache.map = delegationCounts;
      delegationCache.at = Date.now();
    }
    return items.map((item) => {
      const id = String(item.sessionId);
      runningSessions.set(id, item.running === true);
      const cachedPreview = sessionListCache.previews.get(id);
      const agentPreset = listItemAgentPreset(item, id);
      const parentSessionId = optionalId(item.parentSessionId);
      const delegation =
        internalReasonFromSessionSummary(item) === undefined && !isHiddenSession(id)
          ? delegationCounts.get(id)
          : undefined;
      return mapSessionSummary({
        summary: item,
        title: sessionListCache.titles.get(id) ?? cachedPreview?.firstUser ?? 'Untitled session',
        workspaceId: bySession.get(id) ?? workspaceForCwd(byPath, item.cwd) ?? null,
        preview: cachedPreview?.preview ?? '',
        // Durable goal projection: this is what lets cold (non-live) sessions
        // carry a goal summary with no agent creation and no log read.
        goal: mapGoalProjection(item.projections?.values?.goal),
        ...(parentSessionId === undefined ? {} : { parentSessionId }),
        ...(agentPreset === undefined ? {} : { agentPreset }),
        ...(delegation === undefined ? {} : { delegation }),
      });
    });
  }

  /**
   * Warm titles and the most recent previews without blocking a request.
   * Only one refresh runs at a time, and a 4s floor keeps rapid polling cheap.
   * @returns {Promise<void>}
   */
  function refreshSessionListCache() {
    if (sessionListCache.refreshing !== null) return sessionListCache.refreshing;
    const now = Date.now();
    if (now - sessionListCache.lastRefresh < 4000) return Promise.resolve();
    sessionListCache.lastRefresh = now;
    sessionListCache.refreshing = (async () => {
      try {
        const { sessionController } = services();
        if (!sessionController || typeof sessionController.list !== 'function') return;
        const value = await sessionController.list({}, newSignal());
        const items = Array.isArray(value?.items) ? value.items : [];
        absorbProjectionTitles(items);
        absorbInternalMarkers(items);
        refreshAutoHidden(items);
        const { bySession, byPath } = await workspaceIndex();
        // Only enrich sessions the phone can see: internal sessions, the
        // operator's hidden ids, and stale-blank sessions about to be auto-hidden
        // must not cost title/preview log reads.
        const ids = items.map((item) => String(item.sessionId)).filter((id) => !isHiddenSession(id));
        const missingTitles = ids.filter((id) => !sessionListCache.titles.has(id));
        if (missingTitles.length > 0) {
          const titles = await loadTitles(missingTitles);
          for (const [id, title] of titles) sessionListCache.titles.set(id, title);
        }
        const previewTargets = ids
          .filter((id) => {
            const known = sessionListCache.previews.get(id);
            return known === undefined || known.preview === '';
          })
          .slice(0, Math.min(6, Math.max(0, config.sessions.previewLimit)));
        await mapWithConcurrency(previewTargets, 2, async (id) => {
          try {
            const snapshot = await Promise.race([
              readSessionSnapshot(id),
              new Promise((_, reject) => {
                // unref the fallback timer: a fast snapshot must not keep a test
                // process (or a shutting-down host) alive for 15 s.
                const timer = setTimeout(() => reject(new Error('preview timeout')), 15000);
                timer.unref?.();
              }),
            ]);
            sessionListCache.previews.set(id, {
              preview: previewFromEvents(snapshot.events, config.sessions.previewChars),
              firstUser: firstUserText(snapshot.events, 80),
            });
          } catch {
            /* leave this session for a later refresh */
          }
        });
        let archived = null;
        try {
          archived = await currentArchivedSessionIds();
        } catch {
          archived = null;
        }
        sessionListCache.payload = buildSessionListPayload(items, bySession, byPath, archived);
        sessionListCache.payloadAt = Date.now();
        persistSessionListCacheSoon();
      } catch (error) {
        logger.warn?.(`[dsh-mobile-host] session list cache refresh failed: ${String(/** @type {any} */ (error)?.message ?? error)}`);
      } finally {
        sessionListCache.refreshing = null;
        const recentlyUsed = Date.now() - sessionListCache.lastRequestAt < 60000;
        if (recentlyUsed && sessionListCache.refreshTimer === null) {
          sessionListCache.refreshTimer = setTimeout(() => {
            sessionListCache.refreshTimer = null;
            if (Date.now() - sessionListCache.lastRequestAt < 60000) void refreshSessionListCache();
          }, 15000);
          sessionListCache.refreshTimer.unref?.();
        }
      }
    })();
    return sessionListCache.refreshing;
  }

  loadSessionListCache();


  function services() {
    return {
      sessionController: optionalService(ctx, 'sessionController'),
      sessionQuery: optionalService(ctx, 'sessionQuery'),
      workspaceRegistry: optionalService(ctx, 'workspaceRegistry'),
      workspaceController: optionalService(ctx, 'workspaceController'),
      agentPresets: optionalService(ctx, 'agentPresets'),
      sessionSkillCatalog: optionalService(ctx, 'sessionSkillCatalog'),
      agents: optionalService(ctx, 'agents'),
      sessions: optionalService(ctx, 'sessions'),
      goals: optionalService(ctx, 'goals'),
      approval: optionalService(ctx, 'approval'),
      agentDefaultModel: optionalService(ctx, 'agentDefaultModel'),
      schedule: optionalService(ctx, 'schedule'),
      attachments: optionalService(ctx, 'attachments'),
      fileUploads: optionalService(ctx, 'fileUploads'),
      deepseekAccount: optionalService(ctx, 'deepseekAccount'),
    };
  }

  /* ------------------------------------------------------------------ */
  /* Host + workspaces                                                   */
  /* ------------------------------------------------------------------ */

  /** @returns {Promise<Array<{ id: string, name: string, path: string }>>} */
  async function listWorkspaces() {
    const { workspaceRegistry } = services();
    if (!workspaceRegistry || typeof workspaceRegistry.list !== 'function') return [];
    try {
      const workspaces = workspaceRegistry.list();
      return (Array.isArray(workspaces) ? workspaces : []).map(mapWorkspace);
    } catch (error) {
      logger.warn?.(`[dsh-mobile-host] workspaceRegistry.list failed: ${String(/** @type {any} */ (error)?.message ?? error)}`);
      return [];
    }
  }

  /**
   * `GET /mobile/v1/presets` — the agent-preset roster for the create-session
   * sheet. Reads DSH's declared presets (`list`) and the selection roster
   * (`remoteExportList`), merges them, and never activates a Session.
   */
  async function listPresets() {
    const { agentPresets } = services();
    if (
      !agentPresets ||
      (typeof agentPresets.list !== 'function' &&
        typeof agentPresets.remoteExportList !== 'function')
    ) {
      return { presets: [], supported: false };
    }

    /** @type {Array<Record<string, any>> | null} */
    let listRows = null;
    if (typeof agentPresets.list === 'function') {
      try {
        const rows = await agentPresets.list();
        listRows = Array.isArray(rows) ? rows : [];
      } catch (error) {
        logger.warn?.(
          `[dsh-mobile-host] agentPresets.list failed: ${String(/** @type {any} */ (error)?.message ?? error)}`,
        );
        listRows = null;
      }
    }

    /** @type {{ presets?: Array<Record<string, any>> } | null} */
    let roster = null;
    if (typeof agentPresets.remoteExportList === 'function') {
      try {
        const exported = await agentPresets.remoteExportList();
        roster = exported && typeof exported === 'object' ? exported : null;
      } catch (error) {
        logger.warn?.(
          `[dsh-mobile-host] agentPresets.remoteExportList failed: ${String(/** @type {any} */ (error)?.message ?? error)}`,
        );
        roster = null;
      }
    }

    /** @type {string | null} */
    let defaultId = null;
    try {
      const value =
        typeof /** @type {any} */ (agentPresets).defaultId === 'function'
          ? /** @type {any} */ (agentPresets).defaultId()
          : /** @type {any} */ (agentPresets).defaultId;
      if (typeof value === 'string') defaultId = value;
    } catch {
      /* getter unavailable: roster rows still carry isDefault */
    }

    return mapPresetRoster({ listRows, roster, defaultId });
  }

  /** Workspaces plus the sessionId → workspaceId index DSH maintains. */
  async function workspaceIndex() {    const { workspaceRegistry } = services();
    /** @type {Array<any>} */
    const raw = [];
    if (workspaceRegistry && typeof workspaceRegistry.list === 'function') {
      try {
        const listed = workspaceRegistry.list();
        if (Array.isArray(listed)) raw.push(...listed);
      } catch {
        /* degraded: no workspace mapping */
      }
    }
    /** @type {Map<string, string>} */
    const bySession = new Map();
    /** @type {Array<{ id: string, name: string, path: string }>} */
    const mapped = [];
    const byPath = new Map();
    for (const workspace of raw) {
      const entry = mapWorkspace(workspace);
      mapped.push(entry);
      byPath.set(normalizePath(entry.path), entry.id);
      let sessionIds = [];
      try {
        sessionIds = Array.isArray(workspace?.sessionIds) ? workspace.sessionIds : [];
      } catch {
        sessionIds = [];
      }
      for (const sessionId of sessionIds) bySession.set(String(sessionId), entry.id);
    }
    return { list: mapped, bySession, byPath };
  }

  /** @param {Map<string, string>} byPath @param {string | undefined} cwd */
  function workspaceForCwd(byPath, cwd) {
    const normalized = normalizePath(cwd);
    return normalized === '' ? undefined : byPath.get(normalized);
  }

  async function hostInfo() {
    return {
      name: config.hostName,
      version: config.version,
      apiVersion: config.apiVersion,
      workspaces: await listWorkspaces(),
    };
  }

  /* ------------------------------------------------------------------ */
  /* Session summaries                                                   */
  /* ------------------------------------------------------------------ */

  /**
   * Fold titles for a batch of sessions through the session-title projection.
   * @param {string[]} sessionIds
   * @returns {Promise<Map<string, string>>}
   */
  async function loadTitles(sessionIds) {
    /** @type {Map<string, string>} */
    const titles = new Map();
    const { sessionQuery } = services();
    if (!sessionQuery || typeof sessionQuery.readTitleSnapshots !== 'function' || sessionIds.length === 0) {
      return titles;
    }
    try {
      const results = await sessionQuery.readTitleSnapshots(sessionIds, newSignal());
      for (const result of Array.isArray(results) ? results : []) {
        if (result?.status !== 'fulfilled') continue;
        const title = result.value?.title?.title;
        if (typeof title === 'string' && title.trim() !== '') titles.set(String(result.sessionId), title.trim());
      }
    } catch (error) {
      logger.warn?.(`[dsh-mobile-host] reading session titles failed: ${String(/** @type {any} */ (error)?.message ?? error)}`);
    }
    return titles;
  }

  /**
   * Read one full logical session log (live-preferred) without activating it.
   * @param {string} sessionId
   * @param {AbortSignal} [signal] optional caller cancellation (search budget)
   * @returns {Promise<{ header: any, events: any[] }>}
   */
  async function readSessionSnapshot(sessionId, signal) {
    const { sessionQuery } = services();
    if (!sessionQuery || typeof sessionQuery.readSession !== 'function') {
      throw new ApiError('unsupported', 'Session history is not available in this host process');
    }
    const snapshot = signal
      ? await sessionQuery.readSession(sessionId, signal)
      : await sessionQuery.readSession(sessionId);
    return {
      header: snapshot?.session ?? null,
      events: Array.isArray(snapshot?.events) ? snapshot.events : [],
    };
  }

  /**
   * Latest non-empty agent preset from a session snapshot: the most recent
   * `agent-preset/selected` event wins (DSH initializes the projection from the
   * header), then the header's creation preset.
   *
   * @param {ReadonlyArray<any>} events
   * @param {unknown} headerPreset
   */
  function agentPresetFromSnapshot(events, headerPreset) {
    for (let index = (events?.length ?? 0) - 1; index >= 0; index -= 1) {
      const event = events[index];
      if (/** @type {any} */ (event)?.type !== 'agent-preset/selected') continue;
      const selected = /** @type {any} */ (event).data?.agentPreset;
      // The latest selection can explicitly clear the preset; do not fall back
      // to the older creation preset in that case.
      return typeof selected === 'string' && selected.trim() !== '' ? selected.trim() : undefined;
    }
    return typeof headerPreset === 'string' && headerPreset.trim() !== ''
      ? headerPreset.trim()
      : undefined;
  }

  /** @param {unknown} value */
  function optionalPreset(value) {
    return typeof value === 'string' && value.trim() !== '' ? value.trim() : undefined;
  }

  /**
   * Agent preset for one raw `sessionController.list` row. The projection is
   * authoritative when present (including an explicit null/clear); otherwise
   * fall back to a preset learned from an earlier snapshot/detail read.
   *
   * @param {any} item
   * @param {string} sessionId
   */
  function listItemAgentPreset(item, sessionId) {
    const values = item?.projections?.values;
    if (values && Object.prototype.hasOwnProperty.call(values, 'agentPreset')) {
      const preset = optionalPreset(values.agentPreset);
      if (preset !== undefined) sessionListCache.agentPresets.set(sessionId, preset);
      else sessionListCache.agentPresets.delete(sessionId);
      return preset;
    }
    return sessionListCache.agentPresets.get(sessionId);
  }

  /** @param {unknown} value */
  function optionalId(value) {
    return typeof value === 'string' && value.trim() !== '' ? value.trim() : undefined;
  }

  /**
   * Resolve the parent session id to expose. The SAME access-based rule is used
   * by list, detail and archived summaries: unknown, hidden, internal and
   * archived parents are omitted (the phone may not be able to read them), and a
   * parent id equal to the session's own id is dropped as invalid self-lineage.
   * Only the id is returned (never a title); the client resolves the parent.
   *
   * @param {{ id?: unknown, parentSession?: unknown, parentSessionId?: unknown, header?: any } | null} source
   */
  async function visibleParentSessionId(source) {
    const ownId = optionalId(source?.id) ?? optionalId(source?.header?.id);
    const raw =
      optionalId(source?.parentSessionId) ??
      optionalId(source?.parentSession) ??
      optionalId(source?.header?.parentSession);
    if (raw === undefined || raw === ownId) return undefined;
    let access;
    try {
      access = await sessionAccess(raw);
    } catch {
      return undefined;
    }
    if (!access?.exists || access.internal === true) return undefined;
    if (isHiddenSession(raw)) return undefined;
    let archived = null;
    try {
      archived = await currentArchivedSessionIds();
    } catch {
      // Unknown archive state cannot prove the parent visible: omit it rather
      // than risk leaking a hidden parent or failing the whole summary.
      return undefined;
    }
    if (archived?.has(raw)) return undefined;
    return raw;
  }

  /**
   * Apply the access-based parent rule to a session-list payload. This uses the
   * exact same helper as detail/archived summaries (no payload-membership
   * shortcut), so all endpoints agree on whether a parent is exposed. Failures
   * are per-row and degrade to omitting the field, never a 500.
   *
   * @param {Array<Record<string, any>> | null} sessions
   */
  async function filterVisibleParents(sessions) {
    if (!Array.isArray(sessions)) return sessions;
    /** @type {Map<string, string | undefined>} */
    const visibility = new Map();
    /** @type {Array<Record<string, any>>} */
    const output = [];
    for (const session of sessions) {
      const parent = optionalId(session?.parentSessionId);
      if (parent === undefined) {
        output.push(session);
        continue;
      }
      if (!visibility.has(parent)) {
        let visible;
        try {
          visible = await visibleParentSessionId({ id: session?.id, parentSessionId: parent });
        } catch {
          visible = undefined;
        }
        visibility.set(parent, visible);
      }
      const visibleParent = visibility.get(parent);
      if (visibleParent === parent) {
        output.push(session);
      } else {
        const { parentSessionId, ...rest } = session;
        output.push(rest);
      }
    }
    return output;
  }


  /**
   * Build one contract SessionSummary from a raw log snapshot.
   * @param {string} sessionId
   * @param {{ header: any, events: any[] }} snapshot
   */
  async function summaryFromSnapshot(sessionId, snapshot) {
    const { bySession, byPath } = await workspaceIndex();
    const titles = await loadTitles([sessionId]);
    const header = snapshot.header ?? {};
    // Any path that sees the header can settle this session's visibility.
    noteSessionHeader(sessionId, header);
    const running = runningSessions.get(sessionId) === true;
    const live = running || runningSessions.has(sessionId) || optionalService(ctx, 'agents')?.get?.(sessionId) !== undefined;
    const agentPreset = agentPresetFromSnapshot(snapshot.events, header.agentPreset);
    if (agentPreset !== undefined) sessionListCache.agentPresets.set(sessionId, agentPreset);
    else sessionListCache.agentPresets.delete(sessionId);
    const parentSessionId = await visibleParentSessionId(header);
    const delegation = await delegationForSession(sessionId, header);
    return mapSessionSummary({
      summary: {
        sessionId,
        updatedAt: latestEventTime(snapshot.events, header.createdAt),
        running,
        agentAvailable: live,
        cwd: header.cwd,
      },
      title: titles.get(sessionId) || firstUserText(snapshot.events) || 'Untitled session',
      workspaceId: bySession.get(sessionId) ?? workspaceForCwd(byPath, header.cwd) ?? null,
      preview: previewFromEvents(snapshot.events, config.sessions.previewChars),
      ...(parentSessionId === undefined ? {} : { parentSessionId }),
      ...(agentPreset === undefined ? {} : { agentPreset }),
      ...(delegation === undefined ? {} : { delegation }),
    });
  }

  /**
   * Contract summary for one session; null when it does not exist.
   * @param {string} sessionId
   */
  async function getSessionSummary(sessionId) {
    try {
      const snapshot = await readSessionSnapshot(sessionId);
      if (!snapshot.header) return null;
      return await summaryFromSnapshot(sessionId, snapshot);
    } catch (error) {
      if (isSessionNotFound(error)) return null;
      if (!(error instanceof ApiError)) {
        // Session query is unavailable: fall back to the list endpoint below.
      } else if (error.code !== 'unsupported') {
        throw toApiError(error);
      }
    }

    // Fallback: derive from the visible list (sessionQuery unavailable).
    const { sessionController } = services();
    if (!sessionController || typeof sessionController.list !== 'function') {
      throw new ApiError('unsupported', 'Session history is not available in this host process');
    }
    const { items } = await sessionController.list({}, newSignal());
    const found = (Array.isArray(items) ? items : []).find((item) => String(item.sessionId) === sessionId);
    if (!found) return null;
    if (internalReasonFromSessionSummary(found) === undefined) internalSessionIds.delete(String(sessionId));
    else internalSessionIds.add(String(sessionId));
    const { bySession, byPath } = await workspaceIndex();
    const titles = await loadTitles([sessionId]);
    runningSessions.set(sessionId, found.running === true);
    const agentPreset = listItemAgentPreset(found, sessionId) ?? optionalPreset(found.header?.agentPreset);
    const parentSessionId = await visibleParentSessionId(found);
    return mapSessionSummary({
      summary: found,
      title: titles.get(sessionId) ?? 'Untitled session',
      workspaceId: bySession.get(sessionId) ?? workspaceForCwd(byPath, found.cwd) ?? null,
      ...(parentSessionId === undefined ? {} : { parentSessionId }),
      ...(agentPreset === undefined ? {} : { agentPreset }),
    });
  }

  /**
   * `GET /mobile/v1/sessions` — visible sessions ordered by activity.
   * @returns {Promise<Array<Record<string, any>>>}
   */
  async function listSessions() {
    // Capability check comes first: even with a warm cache, a host without the
    // session controller must report `unsupported` rather than serving stale data.
    const { sessionController } = services();
    if (!sessionController || typeof sessionController.list !== 'function') {
      throw new ApiError('unsupported', 'DSH Session Controller is not available in this host process');
    }

    // Lazy mtime refresh of the operator's persisted hidden list (throttled by
    // the store, so polling the list cannot become an fs storm).
    await hiddenStore?.refresh?.();

    sessionListCache.lastRequestAt = Date.now();
    const cachedPayload = sessionListCache.payload;
    if (Array.isArray(cachedPayload) && cachedPayload.length > 0) {
      // Stale-while-revalidate: answer immediately from the last known list.
      void refreshSessionListCache();
      return filterVisibleParents(
        await withoutArchivedSessions(attachGoalSummaries(visibleSessionPayload(cachedPayload))),
      );
    }

    // Cold start with no cached list: build once synchronously, then warm.
    let items;
    try {
      const value = await sessionController.list({}, newSignal());
      items = Array.isArray(value?.items) ? value.items : [];
    } catch (error) {
      throw toApiError(error);
    }
    const { bySession, byPath } = await workspaceIndex();
    absorbProjectionTitles(items);
    absorbInternalMarkers(items);
    refreshAutoHidden(items);
    let archived = null;
    try {
      archived = await currentArchivedSessionIds();
    } catch {
      archived = null;
    }
    const built = buildSessionListPayload(items, bySession, byPath, archived);
    sessionListCache.payload = built;
    sessionListCache.payloadAt = Date.now();
    persistSessionListCacheSoon();
    void refreshSessionListCache();
    return filterVisibleParents(
      await withoutArchivedSessions(attachGoalSummaries(visibleSessionPayload(built))),
    );
  }

  /**
   * Aggregate token usage from every session projection plus, when the host
   * process has a DeepSeek API key, the remaining API balance.
   *
   * `GET /mobile/v1/usage`
   * @returns {Promise<Record<string, any>>}
   */
  async function getUsage() {
    const projected = readUsageFromProjections();
    if (projected !== null) {
      const balance = await readDeepSeekBalance();
      return {
        generatedAt: isoTime(Date.now()),
        totals: projected.totals,
        sessions: projected.sessions,
        ...balance,
      };
    }

    const { sessionController } = services();
    if (!sessionController || typeof sessionController.list !== 'function') {
      throw new ApiError('unsupported', 'DSH Session Controller is not available in this host process');
    }
    let items;
    try {
      const value = await sessionController.list({}, newSignal());
      items = Array.isArray(value?.items) ? value.items : [];
    } catch (error) {
      throw toApiError(error);
    }

    const totals = emptyTokenTotals();
    const sessions = [];
    for (const item of items) {
      const id = String(item?.sessionId ?? '');
      if (id === '') continue;
      const tokenTotals = tokenTotalsFrom(item?.projections?.values?.tokenUsage?.totals);
      if (tokenTotals === null) continue;
      addTokenTotals(totals, tokenTotals);
      const title = String(item?.projections?.values?.title ?? '').trim();
      sessions.push({
        sessionId: id,
        title: title === '' ? 'Untitled session' : title,
        updatedAt: isoTime(typeof item?.updatedAt === 'number' ? item.updatedAt : Date.now()),
        ...tokenTotals,
      });
    }
    sessions.sort((left, right) => String(right.updatedAt).localeCompare(String(left.updatedAt)));
    totals.totalTokens =
      totals.uncachedInputTokens + totals.outputTokens + totals.cacheReadTokens + totals.cacheWriteTokens;

    const balance = await readDeepSeekBalance();
    return {
      generatedAt: isoTime(Date.now()),
      totals,
      sessions,
      ...balance,
    };
  }

  /** Candidate directories for DSH's persisted tokenUsage projections. */
  function projectionUsageDirs() {
    const configured = String(config?.usage?.projectionsDir ?? '').trim();
    // An explicit path is authoritative: tests and unusual DSH_HOME layouts must not silently
    // fall back to the user's real cache.
    if (configured !== '') return [configured];
    const candidates = [];
    const dshHome = String(process.env.DSH_HOME ?? '').trim();
    if (dshHome !== '') candidates.push(path.join(dshHome, 'storages', 'session_projcache', 'sessions'));
    candidates.push(path.join(os.homedir(), '.dsh', 'storages', 'session_projcache', 'sessions'));
    if (typeof config?.stateFile === 'string' && config.stateFile !== '') {
      candidates.push(
        path.join(
          path.dirname(path.dirname(config.stateFile)),
          '.dsh',
          'storages',
          'session_projcache',
          'sessions',
        ),
      );
    }
    candidates.push('/data/dsh/storages/session_projcache/sessions');
    return [...new Set(candidates.filter((candidate) => candidate !== ''))];
  }

  /**
   * Read tokenUsage from DSH's persisted projection cache.
   *
   * `sessionController.list()` does not always carry heavy projection rows (tokenUsage is absent
   * on the desktop host after a restart), but the projection cache is the same durable source the
   * desktop UI renders from. This scans it read-only; subagent rows contribute to totals while only
   * `session-*` rows are listed per session.
   */
  function readUsageFromProjections() {
    if (Date.now() - projectionUsageCache.at < 10_000 && projectionUsageCache.value !== null) {
      return projectionUsageCache.value;
    }
    let dir = null;
    for (const candidate of projectionUsageDirs()) {
      try {
        if (fs.existsSync(candidate) && fs.statSync(candidate).isDirectory()) {
          dir = candidate;
          break;
        }
      } catch {
        /* keep looking */
      }
    }
    if (dir === null) return null;

    let files;
    try {
      files = fs.readdirSync(dir).filter((name) => name.endsWith('.json'));
    } catch {
      return null;
    }
    if (files.length === 0) return null;

    const totals = emptyTokenTotals();
    const sessions = [];
    for (const file of files) {
      const full = path.join(dir, file);
      let data;
      try {
        data = JSON.parse(fs.readFileSync(full, 'utf8'));
      } catch {
        continue;
      }
      const rows = data?.record?.rows;
      const tokenTotals = tokenTotalsFrom(rows?.tokenUsage?.val?.totals);
      if (tokenTotals === null) continue;
      addTokenTotals(totals, tokenTotals);
      const id = file.slice(0, -'.json'.length);
      if (!id.startsWith('session-')) continue;
      let updatedAt = Date.now();
      try {
        updatedAt = fs.statSync(full).mtimeMs;
      } catch {
        /* keep now */
      }
      const title = String(rows?.title?.val ?? '').trim();
      sessions.push({
        sessionId: id,
        title: title === '' ? 'Untitled session' : title,
        updatedAt: isoTime(updatedAt),
        ...tokenTotals,
      });
    }
    totals.totalTokens =
      totals.uncachedInputTokens + totals.outputTokens + totals.cacheReadTokens + totals.cacheWriteTokens;
    if (totals.totalTokens === 0 && sessions.length === 0) return null;
    sessions.sort((left, right) => String(right.updatedAt).localeCompare(String(left.updatedAt)));
    const value = { totals, sessions };
    projectionUsageCache.at = Date.now();
    projectionUsageCache.value = value;
    return value;
  }

  /**
   * One non-negative safe-integer projection number, or undefined.
   *
   * @param {unknown} value
   * @param {{ positive?: boolean }} [options]
   */
  function contextInteger(value, options = {}) {
    if (typeof value !== 'number' || !Number.isSafeInteger(value)) return undefined;
    if (value < (options.positive === true ? 1 : 0)) return undefined;
    return value;
  }

  /**
   * Read one session's projection-cache record (`record` in
   * `session-<id>.json`). Read-only and path-safe: the session id must be a
   * plain token, and only the configured/discovered projection directories are
   * consulted. Malformed/unreadable files degrade to null; the first failure per
   * session is logged without any path or payload.
   *
   * @param {string} sessionId
   * @returns {any | null}
   */
  function readContextProjectionRecord(sessionId) {
    if (!/^[A-Za-z0-9_-]+$/u.test(sessionId)) return null;
    for (const dir of projectionUsageDirs()) {
      let file;
      try {
        if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) continue;
        file = path.join(dir, `${sessionId}.json`);
      } catch {
        continue;
      }
      let raw;
      let fileStat;
      try {
        // Defensive: only a regular file is readable here. lstat (not stat)
        // rejects symlinks/junctions and other non-regular cache entries.
        fileStat = fs.lstatSync(file);
      } catch (error) {
        if (/** @type {any} */ (error)?.code !== 'ENOENT' && markContextCacheWarned(sessionId)) {
          logger.warn?.('[dsh-mobile-host] context projection cache unreadable for one session');
        }
        continue;
      }
      if (!fileStat.isFile()) continue;
      try {
        raw = fs.readFileSync(file, 'utf8');
      } catch (error) {
        if (/** @type {any} */ (error)?.code !== 'ENOENT' && markContextCacheWarned(sessionId)) {
          logger.warn?.('[dsh-mobile-host] context projection cache unreadable for one session');
        }
        continue;
      }
      try {
        const data = JSON.parse(raw);
        return data?.record ?? null;
      } catch {
        if (markContextCacheWarned(sessionId)) {
          logger.warn?.('[dsh-mobile-host] context projection cache malformed for one session');
        }
        continue;
      }
    }
    return null;
  }

  /**
   * Map a projection record to the context-pressure contract. Only finite
   * non-negative integers are exposed; unknown fields are omitted. `percent` is
   * `min(100, pressureTokens / contextWindow * 100)` rounded to one decimal.
   *
   * @param {any} record
   */
  function mapContextProjection(record) {
    const pressure = record?.rows?.contextPressure?.val;
    const breakdownRow = record?.rows?.contextBreakdown?.val;
    const rawBreakdown = breakdownRow?.breakdown;
    const surfaceTokens = contextInteger(pressure?.surfaceTokens) ?? contextInteger(breakdownRow?.surfaceTokens);
    const contextWindow = contextInteger(pressure?.contextWindow, { positive: true });
    const pressureTokens = contextInteger(pressure?.pressureTokens);
    /** @type {Record<string, number>} */
    const breakdown = {};
    for (const [key, value] of [
      ['systemTokens', rawBreakdown?.systemTokens],
      ['toolsTokens', rawBreakdown?.toolsTokens],
      ['messageTokens', rawBreakdown?.messageTokens],
    ]) {
      const parsed = contextInteger(value);
      if (parsed !== undefined) breakdown[/** @type {string} */ (key)] = parsed;
    }
    /** @type {any} */
    const result = { supported: false };
    if (surfaceTokens !== undefined) result.surfaceTokens = surfaceTokens;
    if (contextWindow !== undefined) result.contextWindow = contextWindow;
    if (pressureTokens !== undefined) result.pressureTokens = pressureTokens;
    if (contextWindow !== undefined && pressureTokens !== undefined) {
      const rawPercent = (pressureTokens / contextWindow) * 100;
      result.percent = Math.min(100, Math.max(0, Math.round(rawPercent * 10) / 10));
    }
    if (Object.keys(breakdown).length > 0) result.breakdown = breakdown;
    const sampledAt = pressure?.sampledAt ?? record?.updatedAt;
    if (typeof sampledAt === 'number' && Number.isFinite(sampledAt)) {
      result.sampledAt = isoTime(sampledAt);
    }
    result.supported = [
      result.surfaceTokens,
      result.contextWindow,
      result.pressureTokens,
      result.percent,
      result.breakdown,
    ].some((value) => value !== undefined);
    return result;
  }

  /**
   * `GET /sessions/{id}/context` — context-window pressure from DSH's persisted
   * projection cache. Visible sessions only; read-only disk access with no Agent
   * activation. Missing file/rows or malformed JSON → `{supported:false}` (200);
   * no paths or model/prompt text are exposed.
   *
   * @param {string} sessionId
   */
  async function getSessionContext(sessionId) {
    await requireVisibleSession(sessionId);
    const record = readContextProjectionRecord(sessionId);
    if (record === null) return { supported: false };
    return mapContextProjection(record);
  }


  /** Bucket values used by the `tokenUsage` projection. */
  function emptyTokenTotals() {
    return { uncachedInputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0 };
  }

  /** @returns {{ uncachedInputTokens: number, outputTokens: number, cacheReadTokens: number, cacheWriteTokens: number } | null} */
  function tokenTotalsFrom(raw) {
    if (raw === null || typeof raw !== 'object') return null;
    const totals = emptyTokenTotals();
    let found = false;
    for (const key of Object.keys(totals)) {
      const value = Number(raw[key]);
      if (Number.isFinite(value) && value >= 0) {
        totals[key] = value;
        found = true;
      }
    }
    return found ? totals : null;
  }

  function addTokenTotals(target, source) {
    for (const key of Object.keys(target)) target[key] += source[key] ?? 0;
  }

  /**
   * Remaining DeepSeek credit. Prefer the signed-in Platform account wallet,
   * fall back to the API-key balance endpoint when DEEPSEEK_API_KEY is present.
   * Missing credentials is a normal condition, not an error.
   */
  async function readDeepSeekBalance() {
    if (Date.now() - balanceCache.at < 30_000) {
      return { balance: balanceCache.value, balanceError: balanceCache.error };
    }
    const account = await readAccountBalance();
    if (account !== null) {
      balanceCache.at = Date.now();
      balanceCache.value = account.balance;
      balanceCache.error = account.balanceError;
      return account;
    }
    const apiKey = String(process.env.DEEPSEEK_API_KEY ?? '').trim();
    if (apiKey === '') return { balance: null, balanceError: null };
    try {
      const signal = typeof AbortSignal?.timeout === 'function' ? AbortSignal.timeout(8000) : undefined;
      const response = await fetch('https://api.deepseek.com/user/balance', {
        headers: { authorization: `Bearer ${apiKey}`, accept: 'application/json' },
        ...(signal ? { signal } : {}),
      });
      if (!response.ok) {
        throw new Error(`DeepSeek API returned HTTP ${response.status}`);
      }
      const payload = await response.json();
      const infos = Array.isArray(payload?.balance_infos) ? payload.balance_infos : [];
      const balance = {
        available: payload?.is_available === true,
        balances: infos.map((info) => ({
          currency: String(info?.currency ?? ''),
          totalBalance: String(info?.total_balance ?? '0'),
          grantedBalance: String(info?.granted_balance ?? '0'),
          toppedUpBalance: String(info?.topped_up_balance ?? '0'),
        })),
      };
      balanceCache.at = Date.now();
      balanceCache.value = balance;
      balanceCache.error = null;
      return { balance, balanceError: null };
    } catch (error) {
      const message = String(/** @type {any} */ (error)?.message ?? error);
      balanceCache.at = Date.now();
      balanceCache.value = null;
      balanceCache.error = message;
      return { balance: null, balanceError: message };
    }
  }

  /**
   * Query the signed-in DSH Platform account wallet through the host service.
   * Returns null when the service or account is absent (so the API-key path can run).
   */
  async function readAccountBalance() {
    const { deepseekAccount } = services();
    if (!deepseekAccount || typeof deepseekAccount.getBalance !== 'function') return null;
    const client = {
      version: String(config?.version ?? '0'),
      locale: 'en',
      timezoneOffsetSeconds: -new Date().getTimezoneOffset() * 60,
    };
    let result;
    try {
      result = await deepseekAccount.getBalance(client);
    } catch (error) {
      return {
        balance: null,
        balanceError: `DeepSeek account balance query failed: ${String(
          /** @type {any} */ (error)?.message ?? error,
        )}`,
      };
    }
    if (result === null || result === undefined) return null;
    if (result.status !== 'ready') {
      return { balance: null, balanceError: 'DeepSeek account balance is unavailable' };
    }
    const wallets = new Map();
    for (const wallet of Array.isArray(result.value) ? result.value : []) {
      const key = String(wallet?.currency ?? '');
      if (key === '') continue;
      const entry = wallets.get(key) ?? { currency: key, toppedUp: 0, granted: 0 };
      entry.toppedUp += walletAmount(wallet?.balance);
      wallets.set(key, entry);
    }
    for (const wallet of Array.isArray(result.bonusWallets) ? result.bonusWallets : []) {
      const key = String(wallet?.currency ?? '');
      if (key === '') continue;
      const entry = wallets.get(key) ?? { currency: key, toppedUp: 0, granted: 0 };
      entry.granted += walletAmount(wallet?.balance);
      wallets.set(key, entry);
    }
    if (wallets.size === 0) {
      return { balance: null, balanceError: 'DeepSeek account returned no wallet balance' };
    }
    return {
      balance: {
        available: [...wallets.values()].some((wallet) => wallet.toppedUp + wallet.granted > 0),
        balances: [...wallets.values()].map((wallet) => ({
          currency: wallet.currency,
          totalBalance: formatWallet(wallet.toppedUp + wallet.granted),
          grantedBalance: formatWallet(wallet.granted),
          toppedUpBalance: formatWallet(wallet.toppedUp),
        })),
      },
      balanceError: null,
    };
  }

  function walletAmount(value) {
    const number = Number(String(value ?? '0').replaceAll(',', ''));
    return Number.isFinite(number) ? number : 0;
  }

  function formatWallet(value) {
    return Number.isInteger(value) ? String(value) : value.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
  }

  /**
   * `GET /mobile/v1/sessions/{id}` — summary + mapMessages page.
   * @param {string} sessionId
   * @param {{ limit?: string | number, before?: string | null }} options
   */
  async function getSession(sessionId, options = {}) {
    let snapshot;
    try {
      snapshot = await readSessionSnapshot(sessionId);
    } catch (error) {
      if (isSessionNotFound(error)) throw new ApiError('not_found', 'Session not found');
      throw toApiError(error);
    }
    if (!snapshot.header) throw new ApiError('not_found', 'Session not found');

    const session = await summaryFromSnapshot(sessionId, snapshot);
    const messages = buildMessages(snapshot.events, workspaceImageOptions(snapshot.header?.cwd));
    let page;
    try {
      page = paginateMessages(messages, {
        limit: options.limit,
        before: options.before,
        maxLimit: config.sessions.maxLimit,
      });
    } catch (error) {
      throw toApiError(error);
    }
    return { session, messages: page.messages, nextCursor: page.nextCursor };
  }

  /**
   * `POST /mobile/v1/sessions` — create (or reuse a blank) session.
   * @param {{ workspaceId?: string, preset?: string }} request
   */
  async function createSession({ workspaceId, preset }) {
    const { sessionController } = services();
    if (!sessionController || typeof sessionController.create !== 'function') {
      throw new ApiError('unsupported', 'Session creation is not available in this host process');
    }
    let value;
    try {
      value = await sessionController.create({
        ...(workspaceId ? { workspaceId } : {}),
        ...(preset ? { agentPreset: preset } : {}),
      });
    } catch (error) {
      throw toApiError(error);
    }
    const sessionId = String(value?.sessionId ?? '');
    if (sessionId === '') throw new ApiError('internal', 'Host did not return a session id');
    const summary = await getSessionSummary(sessionId).catch(() => null);
    return summary ?? mapHeaderSummary({ id: sessionId, createdAt: Date.now() });
  }

  /* ------------------------------------------------------------------ */
  /* Attachments (v0.2.2)                                                */
  /* ------------------------------------------------------------------ */

  /** Image media types accepted by the DSH attachment policy. */
  const IMAGE_MEDIA_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif']);

  /**
   * Map an attachment-capability failure onto the mobile error vocabulary.
   * Policy refusals (AttachmentError) become `invalid_request` with the policy
   * message; everything else keeps the normal DSH error mapping.
   *
   * @param {unknown} error
   * @param {string} fallbackMessage
   */
  function attachmentFailure(error, fallbackMessage) {
    const { attachments } = services();
    const code = /** @type {any} */ (error)?.code;
    const isPolicyFailure =
      (attachments && typeof attachments.isAttachmentError === 'function' && attachments.isAttachmentError(error)) ||
      (typeof code === 'string' && (code.startsWith('ATTACHMENT_') || code.startsWith('IMAGE_')));
    if (isPolicyFailure) {
      return new ApiError('invalid_request', error instanceof Error && error.message ? error.message : fallbackMessage, {
        cause: error,
      });
    }
    return toApiError(error);
  }

  /**
   * Whether a Session exists (live or persisted) without activating it.
   * @param {string} sessionId
   * @returns {Promise<boolean>}
   */
  async function sessionExists(sessionId) {
    const { sessionQuery, sessionController } = services();
    if (sessionQuery && typeof sessionQuery.filterSessions === 'function') {
      try {
        const records = await sessionQuery.filterSessions([{ kind: 'id', values: [sessionId] }], newSignal());
        return Array.isArray(records) && records.length > 0;
      } catch (error) {
        if (isSessionNotFound(error)) return false;
        logger.warn?.(
          `[dsh-mobile-host] session existence check failed: ${String(/** @type {any} */ (error)?.message ?? error)}`,
        );
      }
    }
    if (sessionController && typeof sessionController.list === 'function') {
      try {
        const { items } = await sessionController.list({}, newSignal());
        return (Array.isArray(items) ? items : []).some((item) => String(item.sessionId) === sessionId);
      } catch (error) {
        throw toApiError(error);
      }
    }
    // No history service at all: let the prompt admission be the authority.
    return true;
  }

  /**
   * Existence plus internal-session classification for one session id.
   *
   * Used before uploads so a subagent/teammate session is rejected with a
   * documented 4xx instead of leaking a DSH staging failure as a 500 (QA
   * defect O3). The header read also feeds the hidden-session set used by SSE.
   *
   * @param {string} sessionId
   * @returns {Promise<{ exists: boolean, internal: boolean, reason?: string }>}
   */
  async function sessionAccess(sessionId) {
    const { sessionQuery, sessionController } = services();
    if (sessionQuery && typeof sessionQuery.filterSessions === 'function') {
      try {
        const records = await sessionQuery.filterSessions([{ kind: 'id', values: [sessionId] }], newSignal());
        if (Array.isArray(records) && records.length > 0) {
          const header = records[0]?.header;
          noteSessionHeader(sessionId, header);
          const reason = internalReasonFromSessionHeader(header);
          return { exists: true, internal: reason !== undefined, ...(reason === undefined ? {} : { reason }) };
        }
        // A brand-new live Session may not be persisted yet; check the
        // in-process store before declaring it missing (mobile create → attach
        // is a real flow). This is cheap, unlike a full `controller.list`.
        const liveSession = services().sessions?.get?.(sessionId) ?? services().agents?.get?.(sessionId)?.session;
        if (liveSession) {
          noteSessionHeader(sessionId, liveSession.header);
          const reason = internalReasonFromSessionHeader(liveSession.header);
          return { exists: true, internal: reason !== undefined, ...(reason === undefined ? {} : { reason }) };
        }
        return { exists: false, internal: false };
      } catch (error) {
        if (isSessionNotFound(error)) return { exists: false, internal: false };
        logger.warn?.(
          `[dsh-mobile-host] session access check failed: ${String(/** @type {any} */ (error)?.message ?? error)}`,
        );
      }
    }
    if (sessionController && typeof sessionController.list === 'function') {
      try {
        const { items } = await sessionController.list({}, newSignal());
        const found = (Array.isArray(items) ? items : []).find((item) => String(item.sessionId) === sessionId);
        if (!found) return { exists: false, internal: false };
        const reason = internalReasonFromSessionSummary(found);
        if (reason === undefined) internalSessionIds.delete(String(sessionId));
        else internalSessionIds.add(String(sessionId));
        return { exists: true, internal: reason !== undefined, ...(reason === undefined ? {} : { reason }) };
      } catch (error) {
        throw toApiError(error);
      }
    }
    // No history service at all: let the capability (attachment/prompt) decide.
    return { exists: true, internal: false };
  }

  /**
   * Map a Session-scoped file-staging failure onto a documented 4xx.
   *
   * `ctx.fileUploads.uploadStream` is a Session-scoped host operation: subagent
   * and some persisted sessions surface DSH errors here (e.g.
   * `session/agent-busy` "…is a subagent session; use subagent delivery"). The
   * underlying error is logged, and the phone gets a clear documented error
   * instead of the old generic 500 (QA defect O3).
   *
   * @param {string} sessionId
   * @param {unknown} error
   * @returns {ApiError}
   */
  function fileStagingFailure(sessionId, error) {
    const code = typeof (/** @type {any} */ (error)?.code) === 'string' ? /** @type {any} */ (error).code : '';
    const rawMessage = error instanceof Error ? error.message : String(error ?? '');
    logger.warn?.(
      `[dsh-mobile-host] file attachment staging failed for session "${sessionId}"` +
        (code ? ` (${code})` : '') +
        `: ${rawMessage || 'unknown error'}`,
    );
    if (isSessionNotFound(error) || code === 'session/not-found') {
      return new ApiError('not_found', 'Session not found', { cause: error });
    }
    const reason = rawMessage.length > 200 ? `${rawMessage.slice(0, 199)}…` : rawMessage;
    const subagentHint = /subagent|agent-busy|writer-held|internal/i.test(`${code} ${rawMessage}`)
      ? ' Subagent/internal sessions cannot receive attachments; open the parent session instead.'
      : '';
    return new ApiError(
      'invalid_request',
      `The host could not stage this file attachment for the session${reason ? `: ${reason}` : ''}.${subagentHint}`,
      { cause: error, details: { sessionId } },
    );
  }

  /**
   * `POST /mobile/v1/sessions/{id}/attachments` — persist one upload through
   * the DSH attachment capability.
   *
   * Images go through `attachments.saveImage` (authoritative policy validation
   * plus durable normalized reference). Files go through
   * `attachments.admitEncodedFile` (durable byte-for-byte reference) and are
   * additionally staged through `fileUploads.uploadStream` so a prompt can
   * reference them by receipt id, which is the documented
   * `PromptContentPart { type: 'file', receiptId }` route.
   *
   * @param {{
   *   sessionId: string,
   *   name: string,
   *   mimeType: string,
   *   kind: 'image' | 'file',
   *   data: Uint8Array,
   *   dataBase64: string,
   * }} input
   * @returns {Promise<{ ref: unknown, receiptId: string | null }>}
   */
  async function uploadAttachment({ sessionId, name, mimeType, kind, data, dataBase64 }) {
    const { attachments, fileUploads } = services();
    if (!attachments) {
      throw new ApiError('unsupported', 'Attachment storage is not available in this host process');
    }

    if (kind === 'image') {
      if (!IMAGE_MEDIA_TYPES.has(String(mimeType ?? '').toLowerCase())) {
        throw new ApiError('invalid_request', 'Image attachments must be png, jpeg, webp, or gif');
      }
      if (typeof attachments.saveImage !== 'function') {
        throw new ApiError('unsupported', 'Image attachments are not available in this host process');
      }
      try {
        const ref = await attachments.saveImage({ data, mediaType: mimeType, name });
        return { ref, receiptId: null };
      } catch (error) {
        throw attachmentFailure(error, 'The image was rejected by the host attachment policy');
      }
    }

    if (
      typeof attachments.admitEncodedFile !== 'function' ||
      !fileUploads ||
      typeof fileUploads.uploadStream !== 'function'
    ) {
      throw new ApiError(
        'unsupported',
        'File attachments are not available in this host process (file-upload capability missing)',
      );
    }
    let ref;
    try {
      ref = await attachments.admitEncodedFile({ data: dataBase64, name });
    } catch (error) {
      throw attachmentFailure(error, 'The file was rejected by the host attachment service');
    }
    try {
      const receipt = await fileUploads.uploadStream({
        sessionId,
        name,
        data: (async function* oneChunk() {
          yield data;
        })(),
      });
      return {
        ref: receipt?.file ?? ref,
        receiptId: receipt?.receiptId ? String(receipt.receiptId) : null,
      };
    } catch (error) {
      throw fileStagingFailure(sessionId, error);
    }
  }

  /**
   * Options shared by every message builder/collector that can resolve local
   * Markdown image links: the session cwd plus operator-configured roots.
   *
   * @param {unknown} cwd
   */
  function workspaceImageOptions(cwd) {
    return {
      cwd: typeof cwd === 'string' && cwd.trim() !== '' ? cwd : undefined,
      artifactRoots: config.artifacts?.roots ?? [],
      remoteImages: config.artifacts?.remote?.enabled === true,
    };
  }

  /** Lazily-created remote proxy; null while `artifacts.remote.enabled` is false. */
  let remoteImagesInstance = remoteImageFetcher;
  function remoteImages() {
    if (config.artifacts?.remote?.enabled !== true) return null;
    if (remoteImagesInstance === null) {
      remoteImagesInstance = new RemoteImageFetcher(config.artifacts.remote);
    }
    return remoteImagesInstance;
  }

  /**
   * `GET /mobile/v1/sessions/{id}/attachments/{attachmentId}` — read back one
   * attachment referenced by this session's visible messages.
   *
   * Refs are derived from the same message mapping path as session detail, so
   * the route can only serve objects that session history actually references;
   * no filesystem paths are ever exposed. DSH images are read with the
   * attachment service's verifying `readImage` (bounded by the image policy),
   * files stream through `readFileStream`, and synthetic `ws_*` workspace-image
   * refs are re-validated (realpath containment + extension/size) and read from
   * disk directly.
   *
   * @param {string} sessionId
   * @param {string} attachmentId
   * @returns {Promise<{ status: number, headers: Record<string, string>, raw: Uint8Array | AsyncIterable<Uint8Array> }>}
   */
  async function readAttachment(sessionId, attachmentId) {
    const id = String(attachmentId ?? '');
    if (!ATTACHMENT_ID_PATTERN.test(id)) {
      throw new ApiError('invalid_request', 'Malformed attachment id');
    }

    let snapshot;
    try {
      snapshot = await readSessionSnapshot(sessionId);
    } catch (error) {
      if (isSessionNotFound(error)) throw new ApiError('not_found', 'Session not found');
      throw toApiError(error);
    }
    if (!snapshot.header) throw new ApiError('not_found', 'Session not found');

    const workspaceOptions = workspaceImageOptions(snapshot.header?.cwd);
    const refs = collectSessionAttachments(snapshot.events, workspaceOptions);
    const entry = refs.get(id);
    if (!entry) throw new ApiError('not_found', 'Attachment not found in this session');

    // Synthetic workspace images are served straight from the re-validated file
    // (no attachment store round-trip and no session activation); they are only
    // exposed for sessions the phone can see.
    if (entry.ref && typeof entry.ref.workspacePath === 'string') {
      const access = await visibleSessionAccess(sessionId);
      if (!access.exists || !access.visible) throw new ApiError('not_found', 'Session not found');
      const image = readWorkspaceImage(entry.ref, workspaceOptions);
      if (image === null) throw new ApiError('not_found', 'Workspace image is missing or no longer allowed');
      return {
        status: 200,
        headers: {
          'Content-Type': image.mimeType,
          'Content-Length': String(image.bytes.byteLength),
          'Content-Disposition': safeDisposition(image.name, 'inline'),
          'Cache-Control': 'private, max-age=300',
          'X-Content-Type-Options': 'nosniff',
        },
        raw: image.bytes,
      };
    }

    // Remote http(s) images are fetched only here (never during mapping). The
    // proxy is opt-in; disabled config returns a generic 404.
    if (entry.ref && typeof entry.ref.remoteUrl === 'string') {
      const fetcher = remoteImages();
      if (fetcher === null) throw new ApiError('not_found', 'Remote image is not available');
      const access = await visibleSessionAccess(sessionId);
      if (!access.exists || !access.visible) throw new ApiError('not_found', 'Session not found');
      let image;
      try {
        image = await fetcher.get(entry.ref.remoteUrl);
      } catch {
        // Deliberately generic: no remote URL/host is logged or returned.
        throw new ApiError('not_found', 'Remote image is not available');
      }
      const ttlSeconds = Math.max(1, Math.floor((config.artifacts?.remote?.ttlMs ?? 600_000) / 1000));
      return {
        status: 200,
        headers: {
          'Content-Type': image.mimeType,
          'Content-Length': String(image.bytes.byteLength),
          'Content-Disposition': safeDisposition(image.name, 'inline'),
          'Cache-Control': `private, max-age=${ttlSeconds}`,
          'X-Content-Type-Options': 'nosniff',
        },
        raw: image.bytes,
      };
    }

    const { attachments } = services();
    if (!attachments) {
      throw new ApiError('unsupported', 'Attachment storage is not available in this host process');
    }
    const disposition = safeDisposition(entry.name);

    if (entry.kind === 'image') {
      if (typeof attachments.readImage !== 'function') {
        throw new ApiError('unsupported', 'Image attachment reads are not available in this host process');
      }
      let stored;
      try {
        stored = await attachments.readImage(entry.ref, newSignal());
      } catch (error) {
        throw new ApiError(
          'not_found',
          `Attachment bytes are not available: ${String(/** @type {any} */ (error)?.message ?? error)}`,
          { cause: error },
        );
      }
      const data = stored?.data;
      const bytes =
        data instanceof Uint8Array ? data : data instanceof ArrayBuffer ? new Uint8Array(data) : null;
      if (bytes === null) {
        throw new ApiError('internal', 'Attachment bytes are unavailable');
      }
      return {
        status: 200,
        headers: {
          'Content-Type': entry.mimeType,
          'Content-Length': String(bytes.byteLength),
          'Content-Disposition': disposition,
        },
        raw: bytes,
      };
    }

    if (typeof attachments.readFileStream !== 'function') {
      throw new ApiError('unsupported', 'File attachment reads are not available in this host process');
    }
    /** @type {Record<string, string>} */
    const headers = {
      'Content-Type': entry.mimeType,
      'Content-Disposition': safeDisposition(entry.name, 'attachment'),
    };
    if (Number.isSafeInteger(entry.bytes) && entry.bytes >= 0) {
      headers['Content-Length'] = String(entry.bytes);
    }
    let stream;
    try {
      stream = attachments.readFileStream(entry.ref, newSignal());
    } catch (error) {
      throw new ApiError(
        'not_found',
        `Attachment bytes are not available: ${String(/** @type {any} */ (error)?.message ?? error)}`,
        { cause: error },
      );
    }
    return { status: 200, headers, raw: stream };
  }

  /* ------------------------------------------------------------------ */
  /* Read-only workspace files                                           */
  /* ------------------------------------------------------------------ */

  const FILE_LIST_MAX_ENTRIES = 500;
  const FILE_PREVIEW_MAX_BYTES = 1024 * 1024;
  const FILE_PREVIEW_MAX_CHARS = 200_000;
  const FILE_RAW_MAX_BYTES = 25 * 1024 * 1024;

  /** @type {Readonly<Record<string, string>>} */
  const FILE_MIME_BY_EXTENSION = Object.freeze({
    txt: 'text/plain',
    md: 'text/markdown',
    markdown: 'text/markdown',
    json: 'application/json',
    yaml: 'application/yaml',
    yml: 'application/yaml',
    toml: 'application/toml',
    xml: 'application/xml',
    js: 'text/javascript',
    mjs: 'text/javascript',
    cjs: 'text/javascript',
    jsx: 'text/javascript',
    ts: 'application/typescript',
    tsx: 'application/typescript',
    py: 'text/x-python',
    rb: 'text/x-ruby',
    go: 'text/x-go',
    rs: 'text/x-rust',
    java: 'text/x-java',
    kt: 'text/x-kotlin',
    c: 'text/x-c',
    h: 'text/x-c',
    cc: 'text/x-c',
    cpp: 'text/x-c',
    hpp: 'text/x-c',
    cs: 'text/plain',
    php: 'application/x-php',
    sh: 'text/x-sh',
    bash: 'text/x-sh',
    zsh: 'text/x-sh',
    fish: 'text/x-sh',
    ps1: 'text/plain',
    bat: 'text/plain',
    cmd: 'text/plain',
    sql: 'application/sql',
    html: 'text/html',
    htm: 'text/html',
    css: 'text/css',
    scss: 'text/x-scss',
    sass: 'text/x-sass',
    less: 'text/x-less',
    csv: 'text/csv',
    tsv: 'text/tab-separated-values',
    log: 'text/plain',
    ini: 'text/plain',
    cfg: 'text/plain',
    conf: 'text/plain',
    env: 'text/plain',
    properties: 'text/plain',
    gradle: 'text/plain',
    png: 'image/png',
    jpg: 'image/jpeg',
    jpeg: 'image/jpeg',
    gif: 'image/gif',
    webp: 'image/webp',
    ico: 'image/x-icon',
    svg: 'image/svg+xml',
    pdf: 'application/pdf',
    zip: 'application/zip',
    gz: 'application/gzip',
    mp4: 'video/mp4',
    mp3: 'audio/mpeg',
    wav: 'audio/wav',
    woff: 'font/woff',
    woff2: 'font/woff2',
  });
  const FILE_TEXT_EXTENSIONS = new Set(
    Object.keys(FILE_MIME_BY_EXTENSION).filter((extension) =>
      FILE_MIME_BY_EXTENSION[extension].startsWith('text/') ||
      ['application/json', 'application/yaml', 'application/toml', 'application/xml',
        'application/typescript', 'application/sql', 'application/x-php'].includes(
        FILE_MIME_BY_EXTENSION[extension],
      ),
    ),
  );
  const FILE_TEXT_NAMES = new Set(['dockerfile', 'makefile', 'license', 'readme', 'notice', '.gitignore', '.npmrc', '.env']);
  const FILE_ACTIVE_EXTENSIONS = new Set(['html', 'htm', 'svg', 'xml', 'js', 'mjs', 'cjs', 'jsx', 'css']);
  /** `O_NOFOLLOW` is POSIX-only; on Windows the validated realpath + fd read is
   * the available protection (the constant is absent there). */
  const FILE_OPEN_NOFOLLOW = Number.isInteger(fs.constants.O_NOFOLLOW) ? fs.constants.O_NOFOLLOW : 0;
  const FILE_DENIED_DIRECTORY_SEGMENTS = new Set(['.git', '.ssh', '.aws', '.gnupg']);
  const FILE_DENIED_BASENAMES = new Set([
    '.env',
    '.npmrc',
    '.pypirc',
    '.netrc',
    'credentials',
    'secrets',
    'id_rsa',
    'id_dsa',
    'id_ecdsa',
    'id_ed25519',
  ]);
  const FILE_DENIED_EXTENSIONS = new Set(['pem', 'key', 'pfx', 'p12', 'keystore', 'kdbx', 'token']);

  /**
   * Default defense-in-depth denylist for one path segment. Denied entries are
   * omitted from listings and answering them directly is a generic 404, so this
   * is not a content scanner: a secret in an ordinary source file can still be
   * visible if the operator chose to keep it there.
   *
   * @param {unknown} segment
   */
  function isDeniedWorkspaceSegment(segment) {
    const lower = String(segment ?? '').toLowerCase();
    if (lower === '') return false;
    if (FILE_DENIED_DIRECTORY_SEGMENTS.has(lower)) return true;
    if (FILE_DENIED_BASENAMES.has(lower)) return true;
    if (lower.startsWith('.env.') || lower.startsWith('.credentials')) return true;
    if (lower.startsWith('credentials.') || lower.startsWith('secrets.')) return true;
    // Defense-in-depth: any name containing "secret" (may hide e.g.
    // `secret-santa.ts`; documented false-positive trade-off).
    if (lower.includes('secret')) return true;
    const index = lower.lastIndexOf('.');
    const extension = index === -1 ? '' : lower.slice(index + 1);
    return FILE_DENIED_EXTENSIONS.has(extension);
  }

  /** @param {unknown} name */
  function fileExtension(name) {
    const base = path.basename(String(name ?? '')).toLowerCase();
    if (FILE_TEXT_NAMES.has(base)) return base;
    const index = base.lastIndexOf('.');
    return index === -1 ? '' : base.slice(index + 1);
  }

  /** @param {unknown} name */
  function fileMimeType(name) {
    const base = path.basename(String(name ?? '')).toLowerCase();
    if (FILE_TEXT_NAMES.has(base)) return 'text/plain';
    return FILE_MIME_BY_EXTENSION[fileExtension(name)] ?? 'application/octet-stream';
  }

  /** @param {unknown} name */
  function isTextFilePath(name) {
    const base = path.basename(String(name ?? '')).toLowerCase();
    if (FILE_TEXT_NAMES.has(base)) return true;
    return FILE_TEXT_EXTENSIONS.has(fileExtension(name));
  }

  /** @param {unknown} name */
  function fileDisposition(name) {
    return FILE_ACTIVE_EXTENSIONS.has(fileExtension(name)) ? 'attachment' : 'inline';
  }

  /**
   * Windows reserved device basenames (CON, PRN, AUX, NUL, COM1-9, LPT1-9) are
   * extension-tolerant aliases for devices, never legitimate workspace entries.
   *
   * @param {string} segment
   */
  function isReservedWindowsDeviceName(segment) {
    const base = segment.split('.')[0].toLowerCase();
    return /^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])$/u.test(base);
  }

  /**
   * Validate and normalize one client-supplied workspace-relative path.
   *
   * Rejects absolute paths, Windows drive paths, UNC roots, alternate data
   * streams (any colon), `..`/`.` segments, empty segments, control characters,
   * Windows-forbidden characters and trailing dot/space aliases. The returned
   * `rel` always uses `/` separators; root is ''.
   *
   * @param {unknown} rawPath
   * @returns {{ rel: string, segments: string[] }}
   */
  function normalizeWorkspaceRelativePath(rawPath) {
    const value = typeof rawPath === 'string' ? rawPath : '';
    if (value === '' || value === '.') return { rel: '', segments: [] };
    if (value.length > 4096) {
      throw new ApiError('invalid_request', 'File path is too long');
    }
    if (/[\u0000-\u001f\u007f]/u.test(value)) {
      throw new ApiError('invalid_request', 'File path contains invalid characters');
    }
    const slashed = value.replace(/\\/gu, '/');
    if (
      slashed.startsWith('/') ||
      slashed.startsWith('//') ||
      /^[A-Za-z]:/u.test(slashed)
    ) {
      throw new ApiError('invalid_request', 'File path must be relative');
    }
    const segments = slashed.split('/');
    for (const segment of segments) {
      if (
        segment === '' ||
        segment === '.' ||
        segment === '..' ||
        /[<>:"|?*]/u.test(segment) ||
        segment.endsWith('.') ||
        segment.endsWith(' ') ||
        isReservedWindowsDeviceName(segment)
      ) {
        throw new ApiError('invalid_request', 'File path is not allowed');
      }
    }
    const rel = segments.join('/');
    if (rel === '') throw new ApiError('invalid_request', 'File path is not allowed');
    return { rel, segments };
  }

  /** @param {string} root @param {string} candidate */
  function isContainedPath(root, candidate) {
    const relative = path.relative(root, candidate);
    return relative === '' || (relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative));
  }

  /** @param {string} rel */
  function parentRelativePath(rel) {
    if (rel === '') return '';
    const index = rel.lastIndexOf('/');
    return index === -1 ? '' : rel.slice(0, index);
  }

  /**
   * Resolve the session cwd to a real directory. Missing history, missing cwd or
   * an unusable root is a generic 404 (no host path is exposed).
   *
   * @param {string} sessionId
   */
  async function sessionFileRoot(sessionId) {
    await requireVisibleSession(sessionId);
    let snapshot;
    try {
      snapshot = await readSessionSnapshot(sessionId);
    } catch (error) {
      if (isSessionNotFound(error)) throw new ApiError('not_found', 'Session not found');
      throw toApiError(error);
    }
    const cwd = snapshot.header?.cwd;
    if (typeof cwd !== 'string' || cwd.trim() === '') {
      throw new ApiError('not_found', 'File root not found');
    }
    try {
      const root = await fs.promises.realpath(path.resolve(cwd));
      const stat = await fs.promises.stat(root);
      if (!stat.isDirectory()) throw new Error('not a directory');
      return root;
    } catch {
      throw new ApiError('not_found', 'File root not found');
    }
  }

  /**
   * Resolve one relative path to a real path inside the session cwd. Symlink
   * escapes, missing targets and non-regular targets become a generic 404.
   *
   * @param {string} rootReal
   * @param {unknown} rawPath
   * @returns {Promise<{ real: string, rel: string, name: string, stat: import('node:fs').Stats }>}
   */
  async function resolveWorkspaceFile(rootReal, rawPath) {
    const { rel, segments } = normalizeWorkspaceRelativePath(rawPath);
    if (segments.some((segment) => isDeniedWorkspaceSegment(segment))) {
      // Denied paths are indistinguishable from missing paths, including for
      // guessed nested paths.
      throw new ApiError('not_found', 'File not found');
    }
    const candidate = segments.length === 0 ? rootReal : path.join(rootReal, ...segments);
    let real;
    try {
      real = await fs.promises.realpath(candidate);
    } catch {
      throw new ApiError('not_found', 'File not found');
    }
    if (!isContainedPath(rootReal, real)) throw new ApiError('not_found', 'File not found');
    // Check the canonical target too: a symlink with an innocent name must not
    // alias a denied file/directory (`.credentials.yaml`, `.git`, ...).
    const realRelative = path.relative(rootReal, real);
    const realSegments = realRelative === '' ? [] : realRelative.split(path.sep);
    if (realSegments.some((segment) => isDeniedWorkspaceSegment(segment))) {
      throw new ApiError('not_found', 'File not found');
    }
    let stat;
    try {
      stat = await fs.promises.stat(real);
    } catch {
      throw new ApiError('not_found', 'File not found');
    }
    if (!stat.isFile() && !stat.isDirectory()) throw new ApiError('not_found', 'File not found');
    return {
      real,
      rel,
      name: segments.length === 0 ? path.basename(rootReal) : segments[segments.length - 1],
      stat,
    };
  }

  /**
   * Re-validate containment immediately before opening and read from the open
   * descriptor. This closes the TOCTOU window where a path component is swapped
   * to a symlink after the first realpath: the second realpath must still be
   * inside the root, `O_NOFOLLOW` is used where supported (POSIX), and the
   * `fstat` on the handle must report a regular file. Reads always come from the
   * validated fd, never from the original path string.
   *
   * @param {string} rootReal
   * @param {string} realPath
   * @returns {Promise<{ handle: import('node:fs').promises.FileHandle, stat: import('node:fs').Stats }>}
   */
  async function openVerifiedWorkspaceFile(rootReal, realPath) {
    let revalidated;
    try {
      revalidated = await fs.promises.realpath(realPath);
    } catch {
      throw new ApiError('not_found', 'File not found');
    }
    if (!isContainedPath(rootReal, revalidated)) {
      throw new ApiError('not_found', 'File not found');
    }
    let handle;
    try {
      handle = await fs.promises.open(revalidated, fs.constants.O_RDONLY | FILE_OPEN_NOFOLLOW);
    } catch {
      throw new ApiError('not_found', 'File not found');
    }
    try {
      const stat = await handle.stat();
      if (!stat.isFile()) throw new Error('not a regular file');
      return { handle, stat };
    } catch {
      await handle.close().catch(() => {});
      throw new ApiError('not_found', 'File not found');
    }
  }

  /**
   * `GET /sessions/{id}/files?path=` — list one directory under the session cwd.
   * Dirs first, then case-insensitive name; 500 entries; symlinks skipped; file
   * metadata only exposes bytes/mime/modifiedAt. No host path is returned.
   *
   * @param {string} sessionId
   * @param {unknown} [rawPath]
   */
  async function listSessionFiles(sessionId, rawPath = '') {
    const root = await sessionFileRoot(sessionId);
    const target = await resolveWorkspaceFile(root, rawPath);
    if (!target.stat.isDirectory()) {
      throw new ApiError('invalid_request', 'Path is not a directory');
    }
    /** @type {import('node:fs').Dirent[]} */
    let dirents;
    try {
      dirents = await fs.promises.readdir(target.real, { withFileTypes: true });
    } catch {
      throw new ApiError('not_found', 'File not found');
    }
    /** @type {Array<Record<string, any>>} */
    const entries = [];
    for (const dirent of dirents) {
      // Symlinks are never listed: showing a link target as dir/file would make
      // the listing lie, and skipping them avoids following anything.
      if (dirent.isSymbolicLink()) continue;
      // Default sensitive-path denylist: hidden from listings (and direct access
      // is a 404 in resolveWorkspaceFile), so guessing a name cannot expose it.
      if (isDeniedWorkspaceSegment(dirent.name)) continue;
      const kind = dirent.isDirectory() ? 'dir' : dirent.isFile() ? 'file' : null;
      if (kind === null) continue;
      const childRel = target.rel === '' ? dirent.name : `${target.rel}/${dirent.name}`;
      /** @type {Record<string, any>} */
      const entry = { name: dirent.name, kind, path: childRel };
      if (kind === 'file') {
        try {
          const childStat = await fs.promises.lstat(path.join(target.real, dirent.name));
          if (!childStat.isFile()) continue;
          entry.bytes = childStat.size;
          entry.mimeType = fileMimeType(dirent.name);
          entry.modifiedAt = isoTime(childStat.mtimeMs);
        } catch {
          continue;
        }
      }
      entries.push(entry);
    }
    entries.sort((left, right) => {
      if (left.kind !== right.kind) return left.kind === 'dir' ? -1 : 1;
      const byName = String(left.name).toLowerCase().localeCompare(String(right.name).toLowerCase());
      return byName === 0 ? String(left.name).localeCompare(String(right.name)) : byName;
    });
    return {
      supported: true,
      path: target.rel,
      parent: parentRelativePath(target.rel),
      entries: entries.slice(0, FILE_LIST_MAX_ENTRIES),
      truncated: entries.length > FILE_LIST_MAX_ENTRIES,
      entryCount: entries.length,
    };
  }

  /**
   * `GET /sessions/{id}/files/content?path=` — sanitized UTF-8 text preview.
   * At most 1 MB is read and 200k characters are returned (`truncated`); binary
   * or oversized files are a 415 (`unsupported`, status override) and never a
   * giant body. No host path is returned.
   *
   * @param {string} sessionId
   * @param {unknown} rawPath
   */
  async function readSessionFileContent(sessionId, rawPath) {
    const root = await sessionFileRoot(sessionId);
    const target = await resolveWorkspaceFile(root, rawPath);
    if (!target.stat.isFile()) {
      throw new ApiError('invalid_request', 'Path is not a file');
    }
    if (!isTextFilePath(target.name)) {
      throw new ApiError('unsupported', 'File is not a text file', { status: 415 });
    }
    const { handle, stat } = await openVerifiedWorkspaceFile(root, target.real);
    let bytes;
    try {
      if (stat.size > FILE_PREVIEW_MAX_BYTES) {
        throw new ApiError('unsupported', 'File is too large to preview', { status: 415 });
      }
      bytes = await handle.readFile();
    } finally {
      await handle.close().catch(() => {});
    }
    if (bytes.includes(0)) {
      throw new ApiError('unsupported', 'File is not a text file', { status: 415 });
    }
    let text = bytes.toString('utf8');
    let truncated = false;
    if (text.length > FILE_PREVIEW_MAX_CHARS) {
      text = text.slice(0, FILE_PREVIEW_MAX_CHARS);
      truncated = true;
    }
    return {
      name: target.name,
      path: target.rel,
      bytes: bytes.byteLength,
      mimeType: fileMimeType(target.name),
      text,
      truncated,
    };
  }

  /**
   * `GET /sessions/{id}/files/raw?path=` — raw bytes for a regular file ≤25 MB.
   * Content-Type is extension-based, Content-Length exact, disposition inline,
   * nosniff + private cache. Too-large files are a JSON 413; missing/outside and
   * directories are 404/400. No mutation.
   *
   * @param {string} sessionId
   * @param {unknown} rawPath
   * @returns {Promise<{ status: number, headers: Record<string, string>, raw: Uint8Array }>}
   */
  async function readSessionFileRaw(sessionId, rawPath) {
    const root = await sessionFileRoot(sessionId);
    const target = await resolveWorkspaceFile(root, rawPath);
    if (!target.stat.isFile()) {
      throw new ApiError('invalid_request', 'Path is not a file');
    }
    const { handle, stat } = await openVerifiedWorkspaceFile(root, target.real);
    let bytes;
    try {
      if (stat.size > FILE_RAW_MAX_BYTES) {
        throw new ApiError('invalid_request', 'File is too large to download', { status: 413 });
      }
      bytes = await handle.readFile();
    } finally {
      await handle.close().catch(() => {});
    }
    return {
      status: 200,
      headers: {
        'Content-Type': fileMimeType(target.name),
        'Content-Length': String(bytes.byteLength),
        'Content-Disposition': safeDisposition(target.name, fileDisposition(target.name)),
        'X-Content-Type-Options': 'nosniff',
        'Cache-Control': 'private, max-age=60',
      },
      raw: new Uint8Array(bytes),
    };
  }


  /* ------------------------------------------------------------------ */
  /* Schedules (reminders)                                               */
  /* ------------------------------------------------------------------ */

  /**
   * Session visibility for schedule rows: the schedule catalog lists every
   * session binding, but the phone may only see sessions the list would show
   * (hidden/auto-hidden/internal sessions are excluded).
   *
   * @param {string} sessionId
   */
  async function visibleSessionAccess(sessionId) {
    let access;
    try {
      access = await sessionAccess(sessionId);
    } catch {
      access = { exists: false, internal: false };
    }
    if (!access?.exists) return { exists: false, visible: false, internal: false };
    return {
      exists: true,
      visible: !isHiddenSession(sessionId),
      internal: access.internal === true,
    };
  }

  /**
   * Derive creation/update timestamps for schedule ids from the owning
   * session's `schedule/change` events. DSH's public catalog entries carry no
   * timestamps, so this is best-effort and yields null for unreadable sessions.
   *
   * @param {string[]} sessionIds
   */
  async function loadScheduleTimestamps(sessionIds) {
    /** @type {Map<string, number>} */
    const created = new Map();
    /** @type {Map<string, number>} */
    const updated = new Map();
    for (const sessionId of sessionIds) {
      let snapshot;
      try {
        snapshot = await readSessionSnapshot(sessionId);
      } catch {
        continue;
      }
      for (const event of snapshot.events ?? []) {
        if (/** @type {any} */ (event)?.type !== 'schedule/change') continue;
        const data = /** @type {any} */ (event)?.data ?? {};
        if (data?.version !== 1) continue;
        const time = /** @type {any} */ (event).time;
        if (!Number.isFinite(time)) continue;
        if (data.operation === 'create' && data.schedule?.id) {
          const id = String(data.schedule.id);
          if (!created.has(id)) created.set(id, time);
          updated.set(id, Math.max(time, updated.get(id) ?? Number.NEGATIVE_INFINITY));
        } else if ((data.operation === 'delete' || data.operation === 'dispatch') && data.id) {
          const id = String(data.id);
          updated.set(id, Math.max(time, updated.get(id) ?? Number.NEGATIVE_INFINITY));
        }
      }
    }
    return { created, updated };
  }

  /** Best-effort title for one schedule row (list cache, then title snapshots). */
  async function scheduleSessionTitle(sessionId, titles) {
    const cached = sessionListCache.titles.get(sessionId);
    if (typeof cached === 'string' && cached !== '') return cached;
    if (titles instanceof Map) {
      const snapshotTitle = titles.get(sessionId);
      if (typeof snapshotTitle === 'string' && snapshotTitle !== '') return snapshotTitle;
    }
    const loaded = await loadTitles([sessionId]);
    return loaded.get(sessionId) ?? '';
  }

  /**
   * `GET /mobile/v1/schedules` — every visible reminder across sessions.
   */
  async function listSchedules() {
    const { schedule } = services();
    if (!schedule || typeof schedule.catalog !== 'function') {
      return { schedules: [], supported: false };
    }
    let entries;
    try {
      entries = await schedule.catalog();
    } catch (error) {
      throw scheduleServiceError(error) ?? toApiError(error);
    }
    const catalog = Array.isArray(entries) ? entries : [];
    const sessionIds = [...new Set(catalog.map((entry) => String(entry?.sessionId ?? '')).filter(Boolean))];

    /** @type {Map<string, { exists: boolean, visible: boolean, internal: boolean }>} */
    const access = new Map();
    await Promise.all(
      sessionIds.map(async (sessionId) => {
        access.set(sessionId, await visibleSessionAccess(sessionId));
      }),
    );
    const visibleIds = sessionIds.filter((sessionId) => {
      const state = access.get(sessionId);
      return state?.exists === true && state.visible === true && state.internal === false;
    });
    const titles = await loadTitles(visibleIds);
    const timestamps = await loadScheduleTimestamps(visibleIds);
    const visible = new Set(visibleIds);

    const rows = [];
    for (const entry of catalog) {
      const sessionId = String(entry?.sessionId ?? '');
      if (!visible.has(sessionId)) continue;
      const id = String(entry?.id ?? '');
      const createdAt = timestamps.created.get(id);
      const updatedAt = timestamps.updated.get(id);
      rows.push(
        mapScheduleRecord(entry, {
          sessionId,
          status: entry?.status === 'inactive' ? 'inactive' : 'active',
          sessionTitle: await scheduleSessionTitle(sessionId, titles),
          lastDelivery: entry?.lastDelivery,
          createdAt: Number.isFinite(createdAt) ? isoTime(createdAt) : null,
          updatedAt: Number.isFinite(updatedAt) ? isoTime(updatedAt) : null,
        }),
      );
    }
    return { schedules: sortSchedules(rows), supported: true };
  }

  /**
   * `POST /mobile/v1/sessions/{id}/schedules` — create one reminder. The input
   * is already validated/translated by `parseScheduleCreateBody`.
   *
   * @param {string} sessionId
   * @param {{ request: Record<string, any> }} parsed
   */
  async function createSessionSchedule(sessionId, parsed) {
    const { schedule } = services();
    if (!schedule || typeof schedule.create !== 'function') {
      throw new ApiError('unsupported', 'Schedules are not available in this host process');
    }
    const access = await visibleSessionAccess(sessionId);
    if (!access.exists) throw new ApiError('not_found', 'Session not found');
    if (access.internal) {
      throw new ApiError(
        'invalid_request',
        'Schedules cannot be bound to subagent/internal sessions; open the parent session instead',
      );
    }
    if (!access.visible) throw new ApiError('not_found', 'Session not found');

    let record;
    try {
      record = await schedule.create(sessionId, parsed?.request ?? {}, newSignal());
    } catch (error) {
      throw scheduleServiceError(error) ?? toApiError(error);
    }
    const now = isoTime(Date.now());
    return {
      supported: true,
      schedule: mapScheduleRecord(record, {
        sessionId,
        status: 'active',
        sessionTitle: await scheduleSessionTitle(sessionId, null),
        createdAt: now,
        updatedAt: now,
      }),
    };
  }

  /**
   * `DELETE /mobile/v1/schedules/{id}` — resolve the owning session from the
   * catalog (visible rows only) and delete there.
   *
   * @param {string} scheduleId
   */
  async function deleteSchedule(scheduleId) {
    const id = String(scheduleId ?? '').trim();
    const { schedule } = services();
    if (!schedule || typeof schedule.delete !== 'function' || typeof schedule.catalog !== 'function') {
      throw new ApiError('unsupported', 'Schedules are not available in this host process');
    }
    if (id === '' || id.length > 200) throw new ApiError('not_found', 'Schedule not found');

    let catalog;
    try {
      catalog = await schedule.catalog();
    } catch (error) {
      throw scheduleServiceError(error) ?? toApiError(error);
    }
    const entry = (Array.isArray(catalog) ? catalog : []).find(
      (candidate) => String(candidate?.id ?? '') === id,
    );
    if (!entry) throw new ApiError('not_found', 'Schedule not found');
    const sessionId = String(entry.sessionId ?? '');
    const access = await visibleSessionAccess(sessionId);
    if (!access.exists || !access.visible || access.internal) {
      throw new ApiError('not_found', 'Schedule not found');
    }

    let result;
    try {
      result = await schedule.delete({ id, sessionId }, newSignal());
    } catch (error) {
      throw scheduleServiceError(error) ?? toApiError(error);
    }
    if (result?.deleted === true) return { ok: true, deleted: true };
    throw new ApiError('not_found', 'Schedule not found');
  }

  /**
   * `PATCH /mobile/v1/schedules/{id}` — edit an active reminder.
   *
   * Resolves the owning session from the visible catalog, builds the exact DSH
   * `ScheduleUpdateRequest` (binding + complete observed record as the CAS
   * `expected`, optional `change`/title/prompt) and maps the committed record.
   * DSH's no-op path returns the unchanged record; the response exposes both
   * `updated` and `changed`.
   *
   * @param {string} scheduleId
   * @param {{ title?: string, instruction?: string, change?: Record<string, any> }} parsed
   */
  async function updateSchedule(scheduleId, parsed) {
    const id = String(scheduleId ?? '').trim();
    const { schedule } = services();
    if (
      !schedule ||
      typeof schedule.update !== 'function' ||
      typeof schedule.catalog !== 'function'
    ) {
      throw new ApiError('unsupported', 'Schedules are not available in this host process');
    }
    if (id === '' || id.length > 200) throw new ApiError('not_found', 'Schedule not found');

    let catalog;
    try {
      catalog = await schedule.catalog();
    } catch (error) {
      throw scheduleServiceError(error) ?? toApiError(error);
    }
    const entry = (Array.isArray(catalog) ? catalog : []).find(
      (candidate) => String(candidate?.id ?? '') === id,
    );
    if (!entry) throw new ApiError('not_found', 'Schedule not found');
    const sessionId = String(entry.sessionId ?? '');
    const access = await visibleSessionAccess(sessionId);
    if (!access.exists || !access.visible || access.internal) {
      throw new ApiError('not_found', 'Schedule not found');
    }

    const expected = scheduleRecordForUpdate(entry);
    /** @type {Record<string, any>} */
    const request = { id, sessionId, expected };
    if (parsed?.title !== undefined) request.title = parsed.title;
    if (parsed?.instruction !== undefined) request.prompt = parsed.instruction;
    if (parsed?.change !== undefined) request.change = parsed.change;

    let result;
    try {
      result = await schedule.update(request, newSignal());
    } catch (error) {
      throw scheduleServiceError(error) ?? toApiError(error);
    }

    if (result?.updated === true && result.record) {
      const canonical = scheduleRecordForUpdate(result.record);
      const unchanged = JSON.stringify(canonical) === JSON.stringify(expected);
      const timestamps = await loadScheduleTimestamps([sessionId]);
      const created = timestamps.created.get(id);
      return {
        supported: true,
        updated: true,
        changed: !unchanged,
        schedule: mapScheduleRecord(result.record, {
          sessionId,
          status: entry.status === 'inactive' ? 'inactive' : 'active',
          sessionTitle: await scheduleSessionTitle(sessionId, null),
          lastDelivery: entry.lastDelivery,
          createdAt: Number.isFinite(created) ? isoTime(created) : null,
          updatedAt: isoTime(Date.now()),
        }),
      };
    }

    const code =
      typeof (/** @type {any} */ (result)?.code) === 'string' ? /** @type {any} */ (result).code : '';
    if (code === 'schedule_not_found' || code === 'schedule_ended') {
      throw new ApiError('not_found', 'Schedule not found');
    }
    if (code === 'schedule_conflict') {
      throw new ApiError('invalid_request', 'Schedule changed on the host; refresh the list and retry', {
        details: { code },
      });
    }
    const mapped = scheduleServiceError(result);
    if (mapped) throw mapped;
    throw new ApiError('internal', 'Schedule update failed');
  }

  /* ------------------------------------------------------------------ */
  /* Session management: rename, archive/unarchive, archived list        */
  /* ------------------------------------------------------------------ */

  /**
   * Archived ids used to hide rows from the normal session list. Cached for a
   * few seconds so desktop-side archive changes are picked up without a stream
   * read on every `/sessions` request.
   *
   * @returns {Promise<Set<string> | null>}
   */
  async function currentArchivedSessionIds() {
    const now = Date.now();
    if (archivedSessionCache.ids !== null && now - archivedSessionCache.at < 5000) {
      return archivedSessionCache.ids;
    }
    const ids = await readArchivedSessionIds();
    if (ids !== null) {
      archivedSessionCache.ids = ids;
      archivedSessionCache.at = now;
    }
    return archivedSessionCache.ids;
  }

  /** Keep the list cache coherent with an archive mutation we just performed. */
  function noteArchivedState(sessionId, archived) {
    if (archivedSessionCache.ids === null) return;
    if (archived) archivedSessionCache.ids.add(String(sessionId));
    else archivedSessionCache.ids.delete(String(sessionId));
    archivedSessionCache.at = Date.now();
    // Archived children must not inflate delegation counts; force a recount on
    // the next list/detail read.
    delegationCache.map = null;
    delegationCache.at = 0;
  }

  /**
   * Remove archived rows from the normal `/sessions` payload (best-effort: when
   * the archive set is unavailable the list is served unchanged).
   *
   * @param {Array<Record<string, any>> | null} payload
   */
  async function withoutArchivedSessions(payload) {
    if (!Array.isArray(payload)) return payload;
    const archived = await currentArchivedSessionIds();
    if (!archived || archived.size === 0) return payload;
    return payload.filter((session) => !archived.has(String(session?.id ?? '')));
  }

  /** Map a DSH session-controller rename failure onto the documented errors. */
  function renameFailure(error) {
    const code =
      typeof (/** @type {any} */ (error)?.code) === 'string' ? /** @type {any} */ (error).code : '';
    const message =
      typeof (/** @type {any} */ (error)?.message) === 'string' ? /** @type {any} */ (error).message : '';
    if (code === 'session/not-found') return new ApiError('not_found', 'Session not found');
    if (code === 'session/title-invalid') {
      return new ApiError('invalid_request', message || 'The title was rejected by the host', {
        cause: error,
        details: { code },
      });
    }
    if (code === 'session/agent-busy' || code === 'session/writer-held' || code === 'session/conflict') {
      return new ApiError('invalid_request', message || 'The session is busy; retry when it is idle', {
        cause: error,
        details: { code },
      });
    }
    return null;
  }

  /**
   * `POST /sessions/{id}/rename` — rename one already-open session.
   *
   * DSH's rename path resumes the Session. The mobile API deliberately does not
   * activate a cold conversation for a cosmetic edit, so it requires a live
   * agent and answers a clear `400` instead when none is loaded.
   *
   * @param {string} sessionId
   * @param {string} title
   */
  async function renameSession(sessionId, title) {
    const normalized = normalizeSessionTitle(title);
    const { sessionController } = services();
    if (!sessionController || typeof sessionController.rename !== 'function') {
      throw new ApiError('unsupported', 'Session renaming is not available in this host process');
    }
    const access = await visibleSessionAccess(sessionId);
    if (!access.exists) throw new ApiError('not_found', 'Session not found');
    if (access.internal) {
      throw new ApiError(
        'invalid_request',
        'Rename is not available for subagent/internal sessions; open the parent session instead',
      );
    }
    if (!access.visible) throw new ApiError('not_found', 'Session not found');
    if (!liveAgentFor(sessionId)) {
      throw new ApiError(
        'invalid_request',
        'Rename requires the conversation to be open on the host; open it on the desktop first (cold sessions are not activated by the mobile API)',
      );
    }

    let result;
    try {
      result = await sessionController.rename({ sessionId, title: normalized });
    } catch (error) {
      throw renameFailure(error) ?? toApiError(error);
    }
    const accepted =
      typeof result?.title === 'string' && result.title.trim() !== ''
        ? result.title.trim()
        : normalized;
    sessionListCache.titles.set(String(sessionId), accepted);
    /** @type {Record<string, any>} */
    const response = { ok: true, title: accepted };
    if (Number.isSafeInteger(result?.seq)) response.seq = result.seq;
    return response;
  }

  /** Map archive/unarchive failures (active sessions, unknown ids, …). */
  function archiveFailure(error, action) {
    const code =
      typeof (/** @type {any} */ (error)?.code) === 'string' ? /** @type {any} */ (error).code : '';
    const message =
      typeof (/** @type {any} */ (error)?.message) === 'string' ? /** @type {any} */ (error).message : '';
    if (code === 'session/not-found' || code.endsWith('/not-found')) {
      return new ApiError('not_found', 'Session not found');
    }
    if (/active|activity/i.test(code) || /active|activity/i.test(message)) {
      return new ApiError(
        'invalid_request',
        message || `Cannot ${action} a session that is active; stop it on the desktop and retry`,
        code === '' ? { cause: error } : { cause: error, details: { code } },
      );
    }
    return null;
  }

  /**
   * Archive or unarchive one ordinary visible session through the Workspace
   * controller. `stopActivity` is deliberately never sent: archiving a session
   * with running work is refused by DSH instead of silently stopping host work.
   *
   * @param {string} sessionId
   * @param {boolean} archived
   */
  async function setSessionArchived(sessionId, archived) {
    const { workspaceController } = services();
    const method = archived ? 'archiveSession' : 'unarchiveSession';
    if (!workspaceController || typeof workspaceController[method] !== 'function') {
      throw new ApiError('unsupported', 'Session archiving is not available in this host process');
    }
    const access = await visibleSessionAccess(sessionId);
    if (!access.exists || !access.visible || access.internal) {
      throw new ApiError('not_found', 'Session not found');
    }
    try {
      if (archived) {
        await workspaceController.archiveSession({ sessionId });
      } else {
        await workspaceController.unarchiveSession({ sessionId });
      }
    } catch (error) {
      throw archiveFailure(error, archived ? 'archive' : 'unarchive') ?? toApiError(error);
    }
    noteArchivedState(String(sessionId), archived);
    return { ok: true, archived: archived === true };
  }

  /** @param {string} sessionId */
  async function archiveSession(sessionId) {
    return setSessionArchived(sessionId, true);
  }

  /** @param {string} sessionId */
  async function unarchiveSession(sessionId) {
    return setSessionArchived(sessionId, false);
  }

  /**
   * Current archived-session id set.
   *
   * Prefers a synchronous registry projection when the installed build exposes
   * one (`archivedSessionIds` array/Set/getter); otherwise reads the first
   * `workspaceController.follow()` baseline, which is reconnect-safe and does
   * not activate sessions.
   *
   * @returns {Promise<Set<string> | null>}
   */
  async function readArchivedSessionIds() {
    const { workspaceRegistry, workspaceController } = services();
    for (const source of [workspaceRegistry, workspaceController]) {
      if (!source) continue;
      try {
        const value = /** @type {any} */ (source).archivedSessionIds;
        if (typeof value === 'function') {
          const current = value.call(source);
          if (Array.isArray(current) || current instanceof Set) {
            return new Set([...current].map((id) => String(id)));
          }
        } else if (Array.isArray(value) || value instanceof Set) {
          return new Set([...value].map((id) => String(id)));
        }
      } catch {
        /* try the next source */
      }
    }
    if (!workspaceController || typeof workspaceController.follow !== 'function') return null;

    const controller = new AbortController();
    /** @type {AsyncIterator<any> | null} */
    let iterator = null;
    try {
      iterator = workspaceController.follow(controller.signal)[Symbol.asyncIterator]?.() ?? null;
      if (!iterator || typeof iterator.next !== 'function') return null;
      const first = await iterator.next();
      if (first.done || first.value?.type !== 'baseline') return null;
      const ids = first.value?.value?.archivedSessionIds;
      return new Set((Array.isArray(ids) ? ids : []).map((id) => String(id)));
    } catch {
      return null;
    } finally {
      controller.abort();
      try {
        await iterator?.return?.();
      } catch {
        /* stream already closed */
      }
    }
  }

  /**
   * `GET /sessions/archived` — archived session summaries, filtered exactly like
   * the normal list (hidden/internal sessions never leak). Rows use the same
   * summary mapper as the list (no goal enrichment).
   */
  async function listArchivedSessions() {
    const ids = await readArchivedSessionIds();
    if (ids === null) return { sessions: [], supported: false };
    /** @type {Array<Record<string, any>>} */
    const sessions = [];
    for (const sessionId of ids) {
      const access = await visibleSessionAccess(sessionId);
      if (!access.exists || !access.visible || access.internal) continue;
      try {
        const summary = await getSessionSummary(sessionId);
        if (summary) sessions.push(summary);
      } catch {
        /* an unreadable archived session must not break the list */
      }
    }
    sessions.sort((left, right) =>
      String(right?.updatedAt ?? '').localeCompare(String(left?.updatedAt ?? '')),
    );
    return { sessions, supported: true };
  }

  /* ------------------------------------------------------------------ */
  /* Host-wide message search                                            */
  /* ------------------------------------------------------------------ */

  /**
   * `GET /search` — bounded, case-insensitive search over the visible session
   * list (hidden/internal/archived rows are already excluded by listSessions).
   *
   * Cost bounds: at most {@link SEARCH_MAX_SESSIONS} recent sessions are read,
   * at most {@link SEARCH_MAX_EVENTS_PER_SESSION} events per session on the
   * snapshot fallback, at most {@link SEARCH_MAX_MATCHES} matches are collected,
   * and the whole scan stops after {@link SEARCH_WALL_CLOCK_BUDGET_MS}. Reads
   * prefer `sessionQuery.filterEvents` (semantic, text-only documents) so
   * tool-result image payloads and replay validation are not paid; sessions are
   * read in chunks of {@link SEARCH_READ_CONCURRENCY}. The response reports
   * `scanIncomplete`/`scannedSessions`/`totalSessions` whenever the bounded scan
   * did not cover every visible session.
   *
   * @param {unknown} rawQuery
   * @param {unknown} rawLimit
   * @param {{ role?: unknown, since?: unknown, until?: unknown }} [rawFilters]
   * @param {unknown} [rawOffset] page offset (0..500); applied after newest-first ordering
   */
  async function searchSessions(rawQuery, rawLimit, rawFilters, rawOffset) {
    // The wall-clock budget covers the WHOLE request: list work, every snapshot
    // read, and event scanning. QA measured the old implementation at ~4.9 s
    // because the deadline started after listSessions() and snapshot reads were
    // unbounded; both are raced against the deadline now.
    const startedAt = Date.now();
    const deadline = startedAt + searchBudget;
    const query = normalizeSearchQuery(rawQuery);
    const limit = normalizeSearchLimit(rawLimit);
    const offset = normalizeSearchOffset(rawOffset);
    const filters = normalizeSearchFilters(rawFilters ?? {});
    const filterEcho = hasSearchFilters(filters) ? filters.echo : undefined;
    const withEcho = (result) => (filterEcho === undefined ? result : { ...result, filters: filterEcho });
    const { sessionController, sessionQuery } = services();
    if (
      !sessionController ||
      typeof sessionController.list !== 'function' ||
      !sessionQuery ||
      typeof sessionQuery.readSession !== 'function'
    ) {
      return withEcho({
        ...paginateSearchHits([], { offset, limit, truncated: false }),
        supported: false,
        scanIncomplete: false,
        scannedSessions: 0,
        totalSessions: 0,
      });
    }
    const budgetController = new AbortController();
    const budgetTimer = setTimeout(() => budgetController.abort(), searchBudget);
    budgetTimer.unref?.();

    /** @param {Promise<any>} promise @param {number} atDeadline @param {any} onTimeout */
    const beforeDeadline = (promise, atDeadline, onTimeout) => {
      const remaining = atDeadline - Date.now();
      if (remaining <= 0) return Promise.resolve(onTimeout);
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => resolve(onTimeout), remaining);
        timer.unref?.();
        Promise.resolve(promise).then(
          (value) => {
            clearTimeout(timer);
            resolve(value);
          },
          (error) => {
            clearTimeout(timer);
            reject(error);
          },
        );
      });
    };

    try {
      let visible;
      try {
        visible = await beforeDeadline(listSessions(), deadline, null);
      } catch (error) {
        throw toApiError(error);
      }
      if (visible === null) {
        // The list itself consumed the budget: report a bounded empty result.
        return withEcho({
          ...paginateSearchHits([], { offset, limit, truncated: true }),
          supported: true,
          scanIncomplete: true,
          scannedSessions: 0,
          totalSessions: 0,
        });
      }
      const ordered = (Array.isArray(visible) ? visible : [])
        .filter((session) => session && typeof session.id === 'string' && session.id !== '')
        .sort((left, right) => String(right.updatedAt ?? '').localeCompare(String(left.updatedAt ?? '')));

      const totalSessions = ordered.length;
      const selected = ordered.slice(0, SEARCH_MAX_SESSIONS);
      let scanIncomplete = totalSessions > SEARCH_MAX_SESSIONS;
      let scannedSessions = 0;
      // Collect up to the global match cap so a page offset can reach matches in
      // older sessions; a low limit must not stop the scan after one busy session.
      const hitCap = SEARCH_MAX_MATCHES;
      /** @type {Array<Record<string, any>>} */
      const hits = [];

      /**
       * Read one session's matches via the cheapest available path.
       * - `sessionQuery.filterEvents` returns semantic text documents without
       *   replay-validation or image payload materialisation. Its documents do
       *   not carry `source.kind`; `matchesFromSemanticDocuments` excludes
       *   scheduler-delivered user prompts by their self-identifying framing, so
       *   no snapshot verification is needed on the fast path.
       * - the full snapshot + raw-event scan remains the fallback when the
       *   semantic reader is unavailable/fails (strict human predicate).
       */
      const readSnapshotMatches = async (session) => {
        let snapshot;
        try {
          snapshot = await beforeDeadline(
            readSessionSnapshot(session.id, budgetController.signal),
            deadline,
            null,
          );
        } catch {
          if (budgetController.signal.aborted || Date.now() >= deadline) return { timeout: true };
          return { unreadable: true }; // unreadable session: skip, never fail the search
        }
        if (snapshot === null) return { timeout: true };
        const found = searchEvents(snapshot.events, query, {
          maxEvents: SEARCH_MAX_EVENTS_PER_SESSION,
          deadline,
          ...(filters.roles === undefined ? {} : { roles: filters.roles }),
          ...(filters.since === undefined ? {} : { since: filters.since }),
          ...(filters.until === undefined ? {} : { until: filters.until }),
        });
        return { matches: found.matches, truncated: found.truncated };
      };

      const readSessionMatches = async (session) => {
        if (sessionQuery && typeof sessionQuery.filterEvents === 'function') {
          try {
            const documents = await beforeDeadline(
              sessionQuery.filterEvents(session.id, [
                { kind: 'type', values: [...SEARCH_SEMANTIC_EVENT_TYPES] },
                { kind: 'surface', values: ['current', 'log-only'] },
                { kind: 'text', text: query },
              ]),
              deadline,
              null,
            );
            if (documents === null) return { timeout: true };
            return {
              matches: matchesFromSemanticDocuments(documents, query, {
                ...(filters.roles === undefined ? {} : { roles: filters.roles }),
                ...(filters.since === undefined ? {} : { since: filters.since }),
                ...(filters.until === undefined ? {} : { until: filters.until }),
              }),
            };
          } catch (error) {
            if (budgetController.signal.aborted || Date.now() >= deadline) return { timeout: true };
            logger.warn?.(
              `[dsh-mobile-host] semantic search read failed; falling back to snapshot: ${String(
                /** @type {any} */ (error)?.message ?? error,
              )}`,
            );
          }
        }
        return readSnapshotMatches(session);
      };

      for (let index = 0; index < selected.length; index += SEARCH_READ_CONCURRENCY) {
        if (deadline - Date.now() < SEARCH_MIN_READ_MS) {
          scanIncomplete = true;
          break;
        }
        const chunk = selected.slice(index, index + SEARCH_READ_CONCURRENCY);
        const results = await Promise.all(
          chunk.map((session) => readSessionMatches(session).catch(() => ({ unreadable: true }))),
        );
        let stop = false;
        for (let chunkIndex = 0; chunkIndex < chunk.length; chunkIndex += 1) {
          const session = chunk[chunkIndex];
          const result = results[chunkIndex];
          if (result.timeout) {
            scanIncomplete = true;
            stop = true;
            break;
          }
          if (result.unreadable) {
            // Covered but unreadable: counts toward scannedSessions (the scan
            // reached it) and is skipped without failing the whole search.
            scannedSessions += 1;
            continue;
          }
          scannedSessions += 1;
          if (result.truncated) scanIncomplete = true;
          const title =
            typeof session.title === 'string' &&
            session.title.trim() !== '' &&
            session.title.trim() !== 'Untitled session'
              ? session.title.trim()
              : undefined;
          for (const match of (result.matches ?? []).slice(0, SEARCH_MAX_MATCHES_PER_SESSION)) {
            hits.push({
              sessionId: session.id,
              ...(title === undefined ? {} : { sessionTitle: title }),
              messageId: match.messageId,
              role: match.role,
              snippet: match.snippet,
              ...(Number.isSafeInteger(match.seq) && match.seq >= 0 ? { seq: match.seq } : {}),
              ...(Number.isFinite(match.time) ? { createdAt: isoTime(match.time) } : {}),
            });
            if (hits.length >= hitCap) {
              scanIncomplete = true;
              stop = true;
              break;
            }
          }
          if (stop) break;
        }
        if (stop) break;
      }
      if (scannedSessions < totalSessions) scanIncomplete = true;

      hits.sort(
        (left, right) =>
          String(right.createdAt ?? '').localeCompare(String(left.createdAt ?? '')) ||
          String(left.messageId).localeCompare(String(right.messageId)),
      );
      const page = paginateSearchHits(hits, { offset, limit, truncated: scanIncomplete });
      return withEcho({
        ...page,
        supported: true,
        scanIncomplete,
        scannedSessions,
        totalSessions,
      });
    } finally {
      clearTimeout(budgetTimer);
      budgetController.abort();
    }
  }

  /* ------------------------------------------------------------------ */
  /* Prompt / interrupt                                                  */
  /* ------------------------------------------------------------------ */

  /** Stable, client-visible run identity for one accepted prompt. */
  function mintRunId() {
    return `run_${crypto.randomUUID().replaceAll('-', '').slice(0, 20)}`;
  }

  /**
   * Normalize a prompt payload into DSH `PromptContentPart[]`.
   * A plain string is accepted for internal/back-compat callers.
   * @param {string | ReadonlyArray<Record<string, any>>} content
   */
  function promptContentParts(content) {
    if (typeof content === 'string') return [{ type: 'text', text: content }];
    if (!Array.isArray(content) || content.length === 0) {
      throw new ApiError('invalid_request', 'Prompt content must not be empty');
    }
    return [...content];
  }

  /**
   * `POST /mobile/v1/sessions/{id}/prompt` — admit one prompt (queue mode).
   * Content may be text and/or image/file parts built by the router.
   * @param {string} sessionId
   * @param {string | ReadonlyArray<Record<string, any>>} content
   */
  async function prompt(sessionId, content) {
    const { sessionController } = services();
    if (!sessionController || typeof sessionController.prompt !== 'function') {
      throw new ApiError('unsupported', 'Prompting is not available in this host process');
    }
    const parts = promptContentParts(content);
    const requestId = `req_${crypto.randomUUID().replaceAll('-', '')}`;
    try {
      await sessionController.prompt(
        {
          requestId,
          sessionId,
          mode: 'queue',
          content: parts,
        },
        newSignal(),
      );
    } catch (error) {
      throw toApiError(error);
    }
    return { accepted: true, runId: mintRunId() };
  }

  /**
   * `POST /mobile/v1/sessions/{id}/interrupt` — cancel the active turn.
   * @param {string} sessionId
   */
  async function interrupt(sessionId) {
    const { sessionController } = services();
    if (!sessionController || typeof sessionController.cancel !== 'function') {
      throw new ApiError('unsupported', 'Interrupting a turn is not available in this host process');
    }
    try {
      sessionController.cancel({ sessionId });
    } catch (error) {
      throw toApiError(error);
    }
    return { ok: true };
  }

  /* ------------------------------------------------------------------ */
  /* Goal bridge                                                         */
  /* ------------------------------------------------------------------ */

  /**
   * Active goal surface for one session. DSH exposes goals through `ctx.goals`
   * (GoalService), which requires the session's live agent; the phone can see
   * and resume the current goal without owning a desktop UI.
   */
  function mapGoalView(view) {
    if (!view || typeof view.id !== 'string') return null;
    return {
      id: view.id,
      objective: typeof view.objective === 'string' ? view.objective : '',
      phase: typeof view.phase === 'string' ? view.phase : 'active',
      maxGoalRounds: Number(view.maxGoalRounds) || 0,
      roundsStarted: Number(view.roundsStarted) || 0,
      revision: Number(view.revision) || 1,
      activation: typeof view.activation === 'string' ? view.activation : 'disarmed',
      createdAt: isoTime(view.createdAt),
      updatedAt: isoTime(view.updatedAt),
      ...(view.blockedReason && typeof view.blockedReason === 'object'
        ? {
            blockedReason: {
              code: String(view.blockedReason.code ?? ''),
              message: String(view.blockedReason.message ?? ''),
            },
          }
        : {}),
    };
  }

  /** The live agent for a session, or null when no agent is loaded. */
  function liveAgentFor(sessionId) {
    const { agents } = services();
    try {
      return agents?.get?.(sessionId) ?? null;
    } catch {
      return null;
    }
  }

  /**
   * Goal summary for one session-list row, resolved from the live agent.
   *
   * Returns null when there is no live agent, no current goal, `ctx.goals` is
   * unavailable, or the read raises (goal reads are best-effort enrichment for
   * the list, never an error surface). The caller falls back to the durable
   * projection-derived summary when this returns null.
   *
   * @param {string} sessionId
   * @returns {{ objective: string, phase: string, roundsStarted: number, maxGoalRounds: number } | null}
   */
  function goalSummaryForSession(sessionId) {
    const { goals } = services();
    if (!goals || typeof goals.get !== 'function') return null;
    const agent = liveAgentFor(sessionId);
    if (!agent) return null;
    try {
      return mapGoalSummary(mapGoalView(goals.get(agent)));
    } catch {
      return null;
    }
  }

  /**
   * Attach `goal` summaries to a session-list payload at serve time.
   *
   * The payload already carries a durable goal summary derived from the DSH
   * `goal` session projection (available for cold sessions too). For live
   * sessions the live `ctx.agents` + `ctx.goals` view is authoritative and
   * overrides it; if that read is unavailable the projection-derived summary is
   * the fallback. A projection can lag until the next background list refresh
   * (same staleness posture as titles/previews).
   *
   * @param {Array<Record<string, any>> | null} payload
   * @returns {Array<Record<string, any>> | null}
   */
  function attachGoalSummaries(payload) {
    if (!Array.isArray(payload)) return payload;
    return payload.map((summary) => {
      const projected = summary?.goal ?? null;
      const goal =
        summary?.live === true
          ? goalSummaryForSession(String(summary?.id ?? '')) ?? projected
          : projected;
      return { ...summary, goal };
    });
  }

  /**
   * Map the durable `goal/change` session-event payload (DSH `GoalChangeMeta`)
   * to the contract goal shape, or null for a clear tombstone.
   *
   * The durable event carries a full `GoalSnapshot` plus replay counters
   * (`roundsStarted`, `createdAt`, `updatedAt`) but never the process-local
   * `activation`; `mapGoalView` defaults that to `disarmed`.
   *
   * @param {any} data SessionEvent<'goal/change'>.data
   * @returns {Record<string, any> | null}
   */
  function mapGoalChange(data) {
    if (!data || typeof data !== 'object') return null;
    if (data.operation === 'clear') return null;
    const snapshot = data.goal;
    if (!snapshot || typeof snapshot !== 'object') return null;
    return mapGoalView({
      ...snapshot,
      roundsStarted: data.roundsStarted,
      createdAt: data.createdAt,
      updatedAt: data.updatedAt,
    });
  }

  /**
   * Prefer the live goal view when it describes the same revision, because it
   * also carries the process-local `activation` a durable event cannot know.
   * Falls back to the event-derived mapping when no live agent/goal is readable.
   *
   * @param {string} sessionId
   * @param {Record<string, any> | null} mappedGoal
   * @returns {Record<string, any> | null}
   */
  function goalWithLiveActivation(sessionId, mappedGoal) {
    if (!mappedGoal) return null;
    const { goals } = services();
    if (!goals || typeof goals.get !== 'function') return mappedGoal;
    const agent = liveAgentFor(sessionId);
    if (!agent) return mappedGoal;
    try {
      const live = mapGoalView(goals.get(agent));
      if (live && live.id === mappedGoal.id && live.revision === mappedGoal.revision) return live;
    } catch {
      /* the durable mapping remains usable */
    }
    return mappedGoal;
  }

  /**
   * @param {string} sessionId
   * @returns {Promise<{ supported: boolean, live: boolean, goal: any }>}
   */
  async function getSessionGoal(sessionId) {
    const { goals } = services();
    if (!goals || typeof goals.get !== 'function') {
      return { supported: false, live: false, goal: null };
    }
    const agent = liveAgentFor(sessionId);
    if (!agent) return { supported: true, live: false, goal: null };
    try {
      return { supported: true, live: true, goal: mapGoalView(goals.get(agent)) };
    } catch (error) {
      throw new ApiError(
        'internal',
        `Could not read the session goal: ${String(/** @type {any} */ (error)?.message ?? error)}`,
      );
    }
  }

  /**
   * Create a goal for one live session. A non-complete current goal must be cleared
   * first (DSH `GOAL_ALREADY_EXISTS`); completed goals may be replaced.
   * @param {string} sessionId
   * @param {{ objective: string, maxGoalRounds?: number }} request
   */
  async function createSessionGoal(sessionId, request) {
    const { goals } = services();
    if (!goals || typeof goals.create !== 'function') {
      throw new ApiError('unsupported', 'Goal controls are not available in this host process');
    }
    const agent = liveAgentFor(sessionId);
    if (!agent) {
      throw new ApiError(
        'invalid_request',
        'The session has no live agent; open the conversation on the host, then create its goal',
      );
    }
    const current = goals.get(agent);
    if (current && current.phase !== 'complete') {
      throw new ApiError(
        'invalid_request',
        `A goal already exists with phase "${current.phase}"; resume, complete, or clear it first`,
      );
    }
    try {
      const view = goals.create(agent, {
        objective: request.objective,
        ...(request.maxGoalRounds === undefined ? {} : { maxGoalRounds: request.maxGoalRounds }),
      });
      return { supported: true, live: true, goal: mapGoalView(view) };
    } catch (error) {
      throw new ApiError(
        'invalid_request',
        `Could not create the goal: ${String(/** @type {any} */ (error)?.message ?? error)}`,
      );
    }
  }

  /**
   * Resume (re-arm) the current goal for one live session.
   * @param {string} sessionId
   */
  async function resumeSessionGoal(sessionId) {
    const { goals } = services();
    if (!goals || typeof goals.resume !== 'function') {
      throw new ApiError('unsupported', 'Goal controls are not available in this host process');
    }
    const agent = liveAgentFor(sessionId);
    if (!agent) {
      throw new ApiError(
        'invalid_request',
        'The session has no live agent; open the conversation on the host, then resume its goal',
      );
    }
    const current = goals.get(agent);
    if (!current) throw new ApiError('not_found', 'This session has no current goal');
    try {
      const view = goals.resume(agent, { id: current.id, revision: current.revision });
      return { supported: true, live: true, goal: mapGoalView(view) };
    } catch (error) {
      throw new ApiError(
        'invalid_request',
        `Could not resume the goal: ${String(/** @type {any} */ (error)?.message ?? error)}`,
      );
    }
  }

  /**
   * Pause the current active goal for one live session.
   * @param {string} sessionId
   */
  async function pauseSessionGoal(sessionId) {
    const { goals } = services();
    if (!goals || typeof goals.pause !== 'function') {
      throw new ApiError('unsupported', 'Goal controls are not available in this host process');
    }
    const agent = liveAgentFor(sessionId);
    if (!agent) {
      throw new ApiError(
        'invalid_request',
        'The session has no live agent; open the conversation on the host, then pause its goal',
      );
    }
    const current = goals.get(agent);
    if (!current) throw new ApiError('not_found', 'This session has no current goal');
    try {
      const view = goals.pause(agent, { id: current.id, revision: current.revision });
      return { supported: true, live: true, goal: mapGoalView(view) };
    } catch (error) {
      throw new ApiError(
        'invalid_request',
        `Could not pause the goal: ${String(/** @type {any} */ (error)?.message ?? error)}`,
      );
    }
  }

  /**
   * Mark the current goal complete (active|paused|blocked -> complete) for one
   * live session. DSH disarms the goal and appends a `goal/change` event with
   * operation "complete", which the live event fan-out forwards as
   * `goal.changed`.
   *
   * @param {string} sessionId
   */
  async function completeSessionGoal(sessionId, ref = {}) {
    const { goals } = services();
    if (!goals || typeof goals.complete !== 'function') {
      throw new ApiError('unsupported', 'Goal controls are not available in this host process');
    }
    const agent = liveAgentFor(sessionId);
    if (!agent) {
      throw new ApiError(
        'invalid_request',
        'The session has no live agent; open the conversation on the host, then complete its goal',
      );
    }
    const current = goals.get(agent);
    if (!current) throw new ApiError('not_found', 'This session has no current goal');
    if (
      (ref.id !== undefined && ref.id !== current.id) ||
      (ref.revision !== undefined && ref.revision !== current.revision)
    ) {
      throw new ApiError('invalid_request', 'Goal revision is stale; refresh the goal and retry');
    }
    try {
      const view = goals.complete(agent, { id: current.id, revision: current.revision });
      return { supported: true, live: true, goal: mapGoalView(view) };
    } catch (error) {
      throw goalFailure(error, 'complete');
    }
  }

  /**
   * Map a DSH `GoalError` (stable `GOAL_*` code) to a documented 400 with its
   * clear message; unexpected failures keep the normal error mapping.
   *
   * @param {unknown} error
   * @param {string} action human-readable verb for context
   * @returns {ApiError}
   */
  function goalFailure(error, action) {
    const code = typeof (/** @type {any} */ (error)?.code) === 'string' ? /** @type {any} */ (error).code : '';
    if (code.startsWith('GOAL_')) {
      const message = error instanceof Error && error.message ? error.message : `Could not ${action} the goal`;
      return new ApiError('invalid_request', message, { cause: error, details: { code } });
    }
    return toApiError(error);
  }

  /**
   * Edit the current goal (objective and/or round cap) of one live session.
   * DSH compares the current revision for the mutation, so a concurrent change
   * surfaces as a `GOAL_STALE_REVISION` 400 instead of silently applying.
   *
   * @param {string} sessionId
   * @param {{ objective?: string, maxGoalRounds?: number }} request
   */
  async function editSessionGoal(sessionId, request) {
    const patch = request && typeof request === 'object' ? request : {};
    const hasObjective = patch.objective !== undefined;
    const hasRounds = patch.maxGoalRounds !== undefined;
    if (!hasObjective && !hasRounds) {
      throw new ApiError('invalid_request', 'Edit requires objective and/or maxGoalRounds');
    }
    const { goals } = services();
    if (!goals || typeof goals.edit !== 'function') {
      throw new ApiError('unsupported', 'Goal controls are not available in this host process');
    }
    const agent = liveAgentFor(sessionId);
    if (!agent) {
      throw new ApiError(
        'invalid_request',
        'The session has no live agent; open the conversation on the host, then edit its goal',
      );
    }
    const current = goals.get(agent);
    if (!current) throw new ApiError('not_found', 'This session has no current goal');
    try {
      const view = goals.edit(
        agent,
        { id: current.id, revision: current.revision },
        {
          ...(hasObjective ? { objective: patch.objective } : {}),
          ...(hasRounds ? { maxGoalRounds: patch.maxGoalRounds } : {}),
        },
      );
      return { supported: true, live: true, goal: mapGoalView(view) };
    } catch (error) {
      throw goalFailure(error, 'edit');
    }
  }

  /**
   * Clear the current goal of one live session. DSH keeps a durable tombstone;
   * the session then has no current goal and a new one can be created.
   *
   * @param {string} sessionId
   * @returns {Promise<{ supported: boolean, live: boolean, goal: null }>}
   */
  async function clearSessionGoal(sessionId) {
    const { goals } = services();
    if (!goals || typeof goals.clear !== 'function') {
      throw new ApiError('unsupported', 'Goal controls are not available in this host process');
    }
    const agent = liveAgentFor(sessionId);
    if (!agent) {
      throw new ApiError(
        'invalid_request',
        'The session has no live agent; open the conversation on the host, then clear its goal',
      );
    }
    const current = goals.get(agent);
    if (!current) throw new ApiError('not_found', 'This session has no current goal');
    try {
      goals.clear(agent, { id: current.id, revision: current.revision });
      return { supported: true, live: true, goal: null };
    } catch (error) {
      throw goalFailure(error, 'clear');
    }
  }

  /* ------------------------------------------------------------------ */
  /* Session fork                                                        */
  /* ------------------------------------------------------------------ */

  /** Throw a 404 unless the session is an ordinary visible session. */
  async function requireVisibleSession(sessionId) {
    const access = await visibleSessionAccess(sessionId);
    if (!access.exists || !access.visible || access.internal) {
      throw new ApiError('not_found', 'Session not found');
    }
  }

  /**
   * `GET /sessions/{id}/todos` — the latest agent plan/todo list from the
   * session log. Visible sessions only; reads the snapshot without activating an
   * Agent. No `todo/write` event → `{supported:false, counts:{…0}, todos:[]}`
   * (200). Hidden/internal/unknown → 404.
   *
   * @param {string} sessionId
   */
  async function getSessionTodos(sessionId) {
    await requireVisibleSession(sessionId);
    let snapshot;
    try {
      snapshot = await readSessionSnapshot(sessionId);
    } catch (error) {
      if (isSessionNotFound(error)) throw new ApiError('not_found', 'Session not found');
      throw toApiError(error);
    }
    return mapSessionTodos(snapshot.events);
  }

  /**
   * `GET /sessions/{id}/team` — the Agent Teams board folded from the session
   * log. Visible sessions only; reads the snapshot without activating an Agent.
   * No `team/task`/`team/member` event → `{supported:false, counts:{…0},
   * members:[], tasks:[]}` (200). Hidden/internal/unknown → 404.
   *
   * @param {string} sessionId
   */
  async function getSessionTeam(sessionId) {
    await requireVisibleSession(sessionId);
    let snapshot;
    try {
      snapshot = await readSessionSnapshot(sessionId);
    } catch (error) {
      if (isSessionNotFound(error)) throw new ApiError('not_found', 'Session not found');
      throw toApiError(error);
    }
    return mapSessionTeam(snapshot.events);
  }

  /**
   * `GET /sessions/{id}/trajectory?limit=&beforeSeq=` — a compact newest-first
   * journal of the raw session events (allowlisted summaries only, no payloads).
   * Visible sessions only; reads the snapshot without activating an Agent.
   * A readable session with no eligible events returns `{supported:false,
   * events:[], nextBeforeSeq:null, hasMore:false}`; only an unavailable/failing
   * history service maps to `501`. Unknown/hidden/internal → `404`.
   *
   * @param {string} sessionId
   * @param {{ limit?: unknown, beforeSeq?: unknown }} [options]
   */
  async function getSessionTrajectory(sessionId, options = {}) {
    await requireVisibleSession(sessionId);
    let snapshot;
    try {
      snapshot = await readSessionSnapshot(sessionId);
    } catch (error) {
      if (isSessionNotFound(error)) throw new ApiError('not_found', 'Session not found');
      throw toApiError(error);
    }
    return mapSessionTrajectory(snapshot.events, options);
  }

  /**
   * `GET /sessions/{id}/jobs` — folded background tool jobs from the session log.
   * Visible sessions only; snapshot read without activating an Agent. No job
   * events → `{supported:false, counts:{…0}, jobs:[]}` (200). Missing history
   * service → 501; unknown/hidden/internal → 404.
   *
   * @param {string} sessionId
   */
  async function getSessionJobs(sessionId) {
    await requireVisibleSession(sessionId);
    let snapshot;
    try {
      snapshot = await readSessionSnapshot(sessionId);
    } catch (error) {
      if (isSessionNotFound(error)) throw new ApiError('not_found', 'Session not found');
      throw toApiError(error);
    }
    return mapSessionJobs(snapshot.events);
  }

  /**
   * `POST /sessions/{id}/fork`.
   *
   * Forks an exact durable prefix into a new Session (the source is untouched).
   * `atSeq` is optional: omitted → DSH picks the latest completed-turn prefix;
   * supplied → must be a non-negative safe integer. Reads/fork never activate
   * the source Agent. Hidden/internal/unknown → 404, invalid `atSeq` → 400,
   * missing service → 501.
   *
   * A `session/workspace-attach-failed` response still means the fork Session
   * exists: the route returns 200 with `warning:"workspace-attach-failed"` and
   * the new id so the phone can open it.
   *
   * @param {string} sessionId
   * @param {unknown} rawAtSeq
   */
  async function forkSession(sessionId, rawAtSeq) {
    await requireVisibleSession(sessionId);
    if (rawAtSeq !== undefined && (!Number.isSafeInteger(rawAtSeq) || rawAtSeq < 0)) {
      throw new ApiError('invalid_request', 'Field atSeq must be a non-negative safe integer');
    }
    const atSeq = rawAtSeq === undefined ? undefined : Number(rawAtSeq);
    const { sessionController } = services();
    if (!sessionController || typeof sessionController.fork !== 'function') {
      throw new ApiError('unsupported', 'Session forking is not available in this host process');
    }

    let result;
    try {
      result = await sessionController.fork({
        sessionId,
        ...(atSeq === undefined ? {} : { atSeq }),
      });
    } catch (error) {
      const code = String(/** @type {any} */ (error)?.code ?? '');
      if (code === 'session/fork-unavailable') {
        throw new ApiError(
          'invalid_request',
          atSeq === undefined
            ? 'This session has no completed turn to fork from'
            : 'The requested fork boundary is not available in this session',
          { cause: error },
        );
      }
      if (code === 'session/not-found') throw new ApiError('not_found', 'Session not found', { cause: error });
      if (code === 'gateway/bad-request') {
        throw new ApiError('invalid_request', 'The fork boundary is invalid', { cause: error });
      }
      if (code === 'session/workspace-attach-failed') {
        const childId = String(/** @type {any} */ (error)?.details?.sessionId ?? '');
        if (childId !== '') {
          return {
            sessionId: childId,
            forkedFrom: sessionId,
            ...(atSeq === undefined ? {} : { atSeq }),
            warning: 'workspace-attach-failed',
          };
        }
      }
      throw toApiError(error);
    }

    const childId = String(/** @type {any} */ (result)?.sessionId ?? '');
    if (childId === '') throw new ApiError('internal', 'Fork did not return a session id');
    return {
      sessionId: childId,
      forkedFrom: sessionId,
      ...(atSeq === undefined ? {} : { atSeq }),
    };
  }

  /* ------------------------------------------------------------------ */
  /* Models: catalog + per-session selection                             */
  /* ------------------------------------------------------------------ */

  /** Throw a 404 unless the session is an ordinary visible session. */
  async function requireModelSession(sessionId) {
    const access = await visibleSessionAccess(sessionId);
    if (!access.exists || !access.visible || access.internal) {
      throw new ApiError('not_found', 'Session not found');
    }
  }

  /** Host default model selection (`agentDefaultModel.currentSelection()`). */
  function defaultModelSelection() {
    const { agentDefaultModel } = services();
    if (!agentDefaultModel || typeof agentDefaultModel.currentSelection !== 'function') return null;
    try {
      return publicModelSelection(agentDefaultModel.currentSelection());
    } catch (error) {
      logger.warn?.(
        `[dsh-mobile-host] agentDefaultModel.currentSelection failed: ${String(
          /** @type {any} */ (error)?.message ?? error,
        )}`,
      );
      return null;
    }
  }

  /**
   * `GET /models` — sanitized, capped model catalog for the phone selectors.
   * Missing service → 501; provider failures are isolated data, never errors.
   */
  async function getModelCatalog() {
    const { sessionController } = services();
    if (!sessionController || typeof sessionController.modelCatalog !== 'function') {
      throw new ApiError('unsupported', 'Model catalog is not available in this host process');
    }
    let catalog;
    try {
      catalog = await sessionController.modelCatalog();
    } catch (error) {
      throw toApiError(error);
    }
    return mapModelCatalog(catalog);
  }

  /**
   * `GET /sessions/{id}/model`.
   *
   * Cold sessions are never activated: the durable selection is folded from
   * `model/selection`/`request/header` events; with no log selection the host
   * default is returned as `source:"default"`. Unknown/hidden/internal → 404.
   *
   * @param {string} sessionId
   */
  async function getSessionModel(sessionId) {
    await requireModelSession(sessionId);
    const live = Boolean(liveAgentFor(sessionId));
    const { sessionQuery, sessionController, agentDefaultModel } = services();

    /** @type {{ provider: string, model: string, reasoningEffort?: string } | null} */
    let folded = null;
    if (sessionQuery && typeof sessionQuery.readSession === 'function') {
      try {
        const snapshot = await readSessionSnapshot(sessionId);
        folded = foldModelSelection(snapshot.events);
      } catch {
        /* unreadable log: fall through to the documented default */
      }
    }
    if (folded !== null) {
      return { supported: true, live, selection: folded, source: 'session' };
    }
    const fallback = defaultModelSelection();
    const supported = Boolean(
      (sessionQuery && typeof sessionQuery.readSession === 'function') ||
        (agentDefaultModel && typeof agentDefaultModel.currentSelection === 'function') ||
        (sessionController && typeof sessionController.modelCatalog === 'function'),
    );
    return { supported, live, selection: fallback, source: 'default' };
  }

  /**
   * `PUT /sessions/{id}/model`.
   *
   * Live-only: `sessionController.selectModel()` resumes a session, so a cold
   * session answers 400 instead of being activated. Validation is DSH's own
   * (`session/model-unavailable` → 400 short message); missing service → 501.
   *
   * @param {string} sessionId
   * @param {unknown} rawSelection
   */
  async function selectSessionModel(sessionId, rawSelection) {
    const selection = normalizeModelSelection(rawSelection);
    await requireModelSession(sessionId);
    const { sessionController } = services();
    if (!sessionController || typeof sessionController.selectModel !== 'function') {
      throw new ApiError('unsupported', 'Model selection is not available in this host process');
    }
    if (!liveAgentFor(sessionId)) {
      throw new ApiError(
        'invalid_request',
        'Model selection can only be changed while the session is open on the host; open it on the desktop first',
      );
    }
    // Re-check immediately before the call: `selectModel` resolves the agent
    // itself (and would resume a detached session), so a session that detached
    // between the check above and this call must not be silently activated.
    if (!liveAgentFor(sessionId)) {
      throw new ApiError(
        'invalid_request',
        'Model selection can only be changed while the session is open on the host; open it on the desktop first',
      );
    }

    let result;
    try {
      result = await sessionController.selectModel({
        sessionId,
        provider: selection.provider,
        model: selection.model,
        ...(selection.reasoningEffort === undefined ? {} : { reasoningEffort: selection.reasoningEffort }),
      });
    } catch (error) {
      const code = String(/** @type {any} */ (error)?.code ?? '');
      if (code === 'session/model-unavailable') {
        const message = typeof /** @type {any} */ (error)?.message === 'string' && /** @type {any} */ (error).message !== ''
          ? /** @type {any} */ (error).message.slice(0, 300)
          : 'The selected model is not available';
        throw new ApiError('invalid_request', message, { cause: error });
      }
      if (code === 'session/not-found') throw new ApiError('not_found', 'Session not found', { cause: error });
      if (code === 'session/agent-busy' || code === 'session/writer-held') {
        throw new ApiError('invalid_request', 'Session is busy; try again shortly', { cause: error });
      }
      throw toApiError(error);
    }

    const selected = publicModelSelection(result?.selected) ?? selection;
    return { supported: true, live: true, selection: selected };
  }

  /* ------------------------------------------------------------------ */
  /* Live queue (prompt inbox)                                           */
  /* ------------------------------------------------------------------ */

  /** Throw a 404 unless the session is an ordinary visible session. */
  async function requireVisibleQueueSession(sessionId) {
    await requireVisibleSession(sessionId);
  }

  /**
   * `GET /sessions/{id}/queue`.
   *
   * Live agents expose their pending prompts in `agent.inbox.nextTurn` /
   * `nextStep`; a cold session never gets an agent and returns an empty queue.
   * Unknown/hidden/internal → 404; no `agents` service → `supported:false`.
   *
   * @param {string} sessionId
   */
  async function listSessionQueue(sessionId) {
    await requireVisibleQueueSession(sessionId);
    const { agents } = services();
    if (!agents || typeof agents.get !== 'function') {
      return { supported: false, live: false, items: [] };
    }
    const agent = liveAgentFor(sessionId);
    if (!agent) return { supported: true, live: false, items: [] };
    return { supported: true, live: true, items: mapQueueItems(agent.inbox) };
  }

  /* ------------------------------------------------------------------ */
  /* Session skills                                                      */
  /* ------------------------------------------------------------------ */

  const SKILLS_MAX_ENTRIES = 100;
  const SKILL_NAME_MAX_CHARS = 120;
  const SKILL_DESCRIPTION_MAX_CHARS = 500;

  /** A safe kebab/id-like skill name; paths and control characters are refused. */
  function safeSkillName(value) {
    if (typeof value !== 'string') return undefined;
    const name = value.trim();
    if (name === '' || name.length > SKILL_NAME_MAX_CHARS) return undefined;
    if (name === '.' || name === '..') return undefined;
    if (/[\u0000-\u001f\u007f]/u.test(name)) return undefined;
    if (name.includes('/') || name.includes('\\')) return undefined;
    return name;
  }

  /**
   * One-line trimmed description capped for the phone; no metadata fields.
   * Missing/empty descriptions stay valid as an empty string (a valid skill
   * name must not become invisible just because it has no description).
   */
  function safeSkillDescription(value) {
    if (typeof value !== 'string') return '';
    const text = value
      .replace(/[\u0000-\u001f\u007f]+/gu, ' ')
      .replace(/\s+/gu, ' ')
      .trim();
    return text.length > SKILL_DESCRIPTION_MAX_CHARS
      ? text.slice(0, SKILL_DESCRIPTION_MAX_CHARS)
      : text;
  }

  /**
   * Sanitize one DSH SkillListValue into the mobile contract. Only name and
   * description are exposed: `path`, `whenToUse` and `modelInvocable` stay
   * host-side; entries with a valid name are kept even when the description is
   * empty/missing (`description: ""`), while blank/unsafe names are dropped.
   * Names dedupe, results sort by name and cap at 100.
   *
   * @param {any} value
   */
  function sanitizeSkillEntries(value) {
    const rows = Array.isArray(value?.skills) ? value.skills : [];
    const seen = new Set();
    /** @type {Array<{ name: string, description: string }>} */
    const skills = [];
    for (const entry of rows) {
      if (!entry || typeof entry !== 'object') continue;
      const name = safeSkillName(entry.name);
      const description = safeSkillDescription(entry.description);
      if (name === undefined) continue;
      const key = name.toLowerCase();
      if (seen.has(key)) continue;
      seen.add(key);
      skills.push({ name, description });
    }
    skills.sort((left, right) => left.name.localeCompare(right.name));
    return skills.slice(0, SKILLS_MAX_ENTRIES);
  }

  /**
   * `GET /sessions/{id}/skills` — the user-invocable skills visible to one
   * session composition, read through DSH's non-activating
   * `sessionSkillCatalog.list` (it observes the Session; it never creates an
   * Agent). Hidden/internal/unknown sessions are 404. A missing catalog service
   * or an unsupported/failing read degrades to `{supported:false, skills:[]}`
   * with a 200; no session/agent mutation is performed.
   *
   * @param {string} sessionId
   */
  async function getSessionSkills(sessionId) {
    const access = await visibleSessionAccess(sessionId);
    if (!access.exists || !access.visible || access.internal) {
      throw new ApiError('not_found', 'Session not found');
    }
    const { sessionSkillCatalog } = services();
    if (!sessionSkillCatalog || typeof sessionSkillCatalog.list !== 'function') {
      return { supported: false, skills: [] };
    }
    let value;
    try {
      value = await sessionSkillCatalog.list({ sessionId }, newSignal());
    } catch (error) {
      const code = typeof /** @type {any} */ (error)?.code === 'string' ? /** @type {any} */ (error).code : '';
      if (code === 'session/not-found' || code.endsWith('/not-found')) {
        throw new ApiError('not_found', 'Session not found');
      }
      // Unsupported catalogue/provider failures degrade; never echo paths or
      // internals in the error text.
      logger.warn?.('[dsh-mobile-host] skill catalog listing is unavailable');
      return { supported: false, skills: [] };
    }
    return { supported: true, skills: sanitizeSkillEntries(value) };
  }

  /**
   * `PATCH /sessions/{id}/queue/{itemId}`.
   *
   * Live-only: `sessionController.updateQueue` resolves a cold agent itself, so
   * the adapter re-checks `agents.get` immediately before the call. Unknown,
   * hidden and internal sessions are 404; no service → 501.
   *
   * @param {string} sessionId
   * @param {string} itemId
   * @param {unknown} body
   */
  async function updateSessionQueue(sessionId, itemId, body) {
    const action = normalizeQueueAction(body);
    await requireVisibleQueueSession(sessionId);
    const { sessionController, agents } = services();
    if (
      !sessionController ||
      typeof sessionController.updateQueue !== 'function' ||
      !agents ||
      typeof agents.get !== 'function'
    ) {
      throw new ApiError('unsupported', 'Queue management is not available in this host process');
    }
    const id = String(itemId ?? '');
    if (id === '') throw new ApiError('not_found', 'Queued item is no longer pending');

    const requireLive = () => {
      if (!liveAgentFor(sessionId)) {
        throw new ApiError(
          'invalid_request',
          'Queue changes can only be made while the session is open on the host; open the session first',
        );
      }
    };
    requireLive();
    // Re-check immediately before the call: updateQueue would otherwise resolve
    // (activate) a session that detached between the first check and the call.
    requireLive();

    try {
      await sessionController.updateQueue({ sessionId, itemId: id, action });
    } catch (error) {
      const code = String(/** @type {any} */ (error)?.code ?? '');
      if (code === 'session/queue-item-not-found') {
        throw new ApiError('not_found', 'Queued item is no longer pending', { cause: error });
      }
      if (code === 'session/steer-unavailable') {
        throw new ApiError(
          'invalid_request',
          'Steering is only available for the next-turn item while the agent is running',
          { cause: error },
        );
      }
      if (code === 'session/attachment-invalid' || code === 'gateway/bad-request') {
        const message = typeof /** @type {any} */ (error)?.message === 'string' && /** @type {any} */ (error).message !== ''
          ? /** @type {any} */ (error).message.slice(0, 300)
          : 'The queue change was rejected';
        throw new ApiError('invalid_request', message, { cause: error });
      }
      if (code === 'session/not-found') throw new ApiError('not_found', 'Session not found', { cause: error });
      throw toApiError(error);
    }
    return { accepted: true };
  }

  /* ------------------------------------------------------------------ */
  /* Approval policy (ask / never)                                       */
  /* ------------------------------------------------------------------ */

  /** Validated approval policies exposed by DSH's user-approval service. */
  const APPROVAL_POLICIES = Object.freeze(['ask', 'never']);

  /** @param {unknown} value @returns {'ask' | 'never'} */
  function normalizeApprovalPolicy(value) {
    if (value === 'ask' || value === 'never') return value;
    throw new ApiError('invalid_request', 'Field policy must be "ask" or "never"');
  }

  /**
   * Fold the durable `approval/policy` events from a session log, newest last.
   * Falls back to DSH's default `ask` when no policy event is readable.
   *
   * @param {ReadonlyArray<any>} events
   * @returns {'ask' | 'never'}
   */
  function foldApprovalPolicy(events) {
    let policy = 'ask';
    for (const event of events ?? []) {
      if (!event || event.type !== 'approval/policy') continue;
      const value = event.data?.policy;
      if (value === 'ask' || value === 'never') policy = value;
    }
    return policy;
  }

  /** Throw a 404 unless the session is an ordinary visible session. */
  async function requireApprovalPolicySession(sessionId) {
    await requireVisibleSession(sessionId);
  }

  /**
   * `GET /sessions/{id}/approval-policy`.
   *
   * Live sessions use `approval.effectivePolicy(agent.session)` (override or the
   * configured default). Cold sessions are never activated: their durable
   * `approval/policy` fold is read from the snapshot, with `ask` as the
   * documented fallback when the log/config is unavailable. Unknown, hidden and
   * internal sessions are 404.
   *
   * @param {string} sessionId
   * @returns {Promise<{ supported: boolean, live: boolean, policy: 'ask' | 'never' }>}
   */
  async function getApprovalPolicy(sessionId) {
    await requireApprovalPolicySession(sessionId);
    const { approval } = services();
    const agent = liveAgentFor(sessionId);

    if (agent?.session && approval && typeof approval.effectivePolicy === 'function') {
      try {
        const policy = approval.effectivePolicy(agent.session);
        if (policy === 'ask' || policy === 'never') {
          return { supported: true, live: true, policy };
        }
      } catch (error) {
        logger.warn?.(
          `[dsh-mobile-host] approval.effectivePolicy failed: ${String(
            /** @type {any} */ (error)?.message ?? error,
          )}`,
        );
      }
    }

    // Cold (or unreadable live policy): fold the durable log without activating.
    let policy = 'ask';
    try {
      const snapshot = await readSessionSnapshot(sessionId);
      policy = foldApprovalPolicy(snapshot.events);
    } catch {
      /* fallback documented: no readable log → default ask */
    }
    return { supported: approval !== null && approval !== undefined, live: Boolean(agent), policy };
  }

  /**
   * `PUT /sessions/{id}/approval-policy`.
   *
   * Writes require a live agent (`approval.setPolicy(agent, policy)`); a cold
   * session answers 400 instead of being resumed. Unknown/hidden/internal → 404,
   * missing approval service → 501, invalid policy → 400.
   *
   * @param {string} sessionId
   * @param {unknown} rawPolicy
   * @returns {Promise<{ supported: true, live: true, policy: 'ask' | 'never' }>}
   */
  async function setApprovalPolicy(sessionId, rawPolicy) {
    const policy = normalizeApprovalPolicy(rawPolicy);
    await requireApprovalPolicySession(sessionId);
    const { approval } = services();
    if (!approval || typeof approval.setPolicy !== 'function') {
      throw new ApiError('unsupported', 'Approval policy is not available in this host process');
    }
    const agent = liveAgentFor(sessionId);
    if (!agent) {
      throw new ApiError(
        'invalid_request',
        'Approval policy can only be changed while the session is open on the host; open it on the desktop first',
      );
    }
    try {
      approval.setPolicy(agent, policy);
    } catch (error) {
      throw toApiError(error);
    }

    let effective = policy;
    if (typeof approval.effectivePolicy === 'function' && agent.session) {
      try {
        const value = approval.effectivePolicy(agent.session);
        if (value === 'ask' || value === 'never') effective = value;
      } catch {
        /* fall back to the accepted request value */
      }
    }
    return { supported: true, live: true, policy: effective };
  }

  /* ------------------------------------------------------------------ */
  /* Approval + question mirrors (read-only or first-answer-wins)        */
  /* ------------------------------------------------------------------ */

  async function listApprovals() {
    // Pending `approval/request` dispatches; readOnly reflects mobile answering.
    return mirror.list();
  }

  /**
   * Answer one pending approval from the phone (first answer wins).
   *
   * The mirror throws `unsupported` (501) when `approvals.mobileAnswer` is off,
   * `not_found` (404) when the approval is unknown or already answered, and
   * `invalid_request` (400) for an unrecognized decision.
   *
   * @param {string} approvalId
   * @param {'allow' | 'deny'} decision
   * @returns {Promise<{ ok: true, approvalId: string, outcome: string }>}
   */
  async function resolveApproval(approvalId, decision) {
    const outcome = mirror.decide(approvalId, decision);
    return { ok: true, approvalId: String(approvalId), outcome };
  }

  async function listQuestions() {
    // Pending `user-questions/request` rows; readOnly reflects mobile answering.
    return questionMirrorInstance.list();
  }

  /**
   * Answer one row of a pending question call from the phone.
   *
   * The mirror throws `unsupported` (501) when `questions.mobileAnswer` is off,
   * `not_found` (404) when the row is unknown/already answered/the call settled,
   * and `invalid_request` (400) for an out-of-range option index or empty
   * free-text answer. The call's `{answers}` batch is completed only once every
   * question in it has been answered from the phone.
   *
   * @param {string} questionId row id (`q_<uuid>:<index>`)
   * @param {string} answer
   * @param {number} [optionIndex]
   * @param {string} [note] reserved for future use
   * @returns {Promise<{ ok: true, questionId: string, answered: number, total: number, complete: boolean }>}
   */
  async function answerQuestion(questionId, answer, optionIndex, note) {
    return questionMirrorInstance.answer(questionId, answer, optionIndex, note);
  }

  /* ------------------------------------------------------------------ */
  /* Live event fan-out                                                  */
  /* ------------------------------------------------------------------ */

  /** @param {string} sessionId */
  async function publishSummary(sessionId) {
    if (!bus) return;
    // Lazy mtime refresh of the operator's hidden list; hidden sessions never publish.
    refreshHiddenStore();
    // Never forward live rows for sessions the phone must not see.
    if (isHiddenSession(sessionId)) return;
    try {
      const summary = await getSessionSummary(sessionId);
      if (!summary) return;
      // Reading the snapshot may have revealed a subagent header (a session
      // added after the last list refresh), so re-check before publishing.
      if (isHiddenSession(sessionId)) return;
      bus.publish('session.updated', summary, { sessionId });
    } catch (error) {
      logger.warn?.(`[dsh-mobile-host] refreshing session summary failed: ${String(/** @type {any} */ (error)?.message ?? error)}`);
    }
  }

  /** @param {string} sessionId @param {any} event @param {unknown} cwd */
  function handleSessionEvent(sessionId, event, cwd) {
    if (!bus || !event) return;
    refreshHiddenStore();
    if (isHiddenSession(sessionId)) return;
    const workspaceOptions = workspaceImageOptions(cwd);
    const type = /** @type {any} */ (event).type;
    switch (type) {
      case 'user/message': {
        const [message] = buildMessages([event], workspaceOptions);
        if (message) bus.publish('message.added', { sessionId, message }, { sessionId });
        break;
      }
      case 'assistant/message': {
        const streamId = activeAttempts.get(sessionId);
        if (streamId) {
          activeAttempts.delete(sessionId);
          bus.publish('message.completed', { sessionId, messageId: streamId }, { sessionId });
        }
        // Durable assistant rows normally arrive via the next detail fetch; when
        // the text references local workspace images we publish the mapped row
        // immediately so those attachments render live too.
        const [assistantMessage] = buildMessages([event], workspaceOptions);
        if (
          assistantMessage &&
          Array.isArray(assistantMessage.attachments) &&
          assistantMessage.attachments.some(
            (entry) =>
              typeof entry?.id === 'string' &&
              (entry.id.startsWith('ws_') || entry.id.startsWith('ext_')),
          )
        ) {
          bus.publish('message.added', { sessionId, message: assistantMessage }, { sessionId });
        }
        break;
      }
      case 'tool/call': {
        const data = /** @type {any} */ (event).data ?? {};
        const callId = String(data.callId ?? '');
        if (callId === '') break;
        if (!toolInfo.has(sessionId)) toolInfo.set(sessionId, new Map());
        toolInfo.get(sessionId)?.set(callId, {
          name: typeof data.name === 'string' ? data.name : 'tool',
          detail: toolDetail(data.arguments),
        });
        bus.publish(
          'message.added',
          {
            sessionId,
            message: {
              id: `m_${callId}`,
              role: 'tool',
              text: '',
              createdAt: isoTime(/** @type {any} */ (event).time),
              streaming: true,
              tool: { name: typeof data.name === 'string' ? data.name : 'tool', detail: toolDetail(data.arguments), state: 'running' },
            },
          },
          { sessionId },
        );
        break;
      }
      case 'tool/result': {
        const data = /** @type {any} */ (event).data ?? {};
        const message = data.message;
        const callId = String(message?.toolCallId ?? data.callId ?? '');
        if (callId === '') break;
        const info = toolInfo.get(sessionId)?.get(callId);
        toolInfo.get(sessionId)?.delete(callId);
        const failed = Boolean(data.error) || Boolean(message?.isError);
        const toolAttachments = messageAttachments(message?.content);
        const workspaceAttachments = workspaceImageAttachments(
          extractText(message?.content),
          workspaceOptions,
        );
        const mergedAttachments = [...toolAttachments];
        const seenAttachmentIds = new Set(mergedAttachments.map((entry) => entry.id));
        for (const entry of workspaceAttachments) {
          if (seenAttachmentIds.has(entry.id)) continue;
          seenAttachmentIds.add(entry.id);
          mergedAttachments.push(entry);
        }
        const output = toolOutput(message?.content);
        bus.publish(
          'message.added',
          {
            sessionId,
            message: {
              id: `m_${callId}`,
              role: 'tool',
              text: '',
              createdAt: isoTime(/** @type {any} */ (event).time),
              streaming: false,
              tool: {
                name: info?.name ?? 'tool',
                detail: info?.detail ?? '',
                state: failed ? 'failed' : 'done',
                ...(output === '' ? {} : { output }),
              },
              ...(mergedAttachments.length > 0 ? { attachments: mergedAttachments } : {}),
            },
          },
          { sessionId },
        );
        break;
      }
      case 'turn/end': {
        runningSessions.set(sessionId, false);
        void publishSummary(sessionId);
        // No bus event exists for turn completion; notify the push publisher.
        try {
          push?.handleTurnFinished?.(sessionId, sessionListCache.titles.get(sessionId));
        } catch (error) {
          logger.warn?.(
            `[dsh-mobile-host] push turn hook failed: ${String(
              /** @type {any} */ (error)?.message ?? error,
            )}`,
          );
        }
        break;
      }
      case 'goal/change': {
        // DSH commits the full goal snapshot (or clear tombstone) to the session
        // log; forward it so the phone updates its GOAL banner without polling.
        const data = /** @type {any} */ (event).data ?? {};
        const operation = typeof data.operation === 'string' ? data.operation : 'edit';
        const goal = goalWithLiveActivation(sessionId, mapGoalChange(data));
        bus.publish('goal.changed', { sessionId, operation, goal }, { sessionId });
        break;
      }
      default:
        break;
    }
  }

  /** @param {any} payload `{ agent, frame }` from `agent/assistant-stream` */
  function handleAssistantStream(payload) {
    if (!bus) return;
    const sessionId = payload?.agent?.id ? String(payload.agent.id) : '';
    const frame = payload?.frame;
    if (sessionId === '' || !frame) return;
    refreshHiddenStore();
    // The live Agent carries its Session/header; use it to classify visibility.
    noteSessionHeader(sessionId, payload?.agent?.session?.header);
    if (isHiddenSession(sessionId)) return;
    const attemptId = frame.attemptId ? String(frame.attemptId) : '';
    const streamMessageId = () => attemptId !== '' ? `m_${attemptId}` : `m_stream_${sessionId}`;

    if (frame.type === 'start') {
      activeAttempts.set(sessionId, streamMessageId());
      return;
    }
    if (frame.type === 'chunk') {
      const chunk = frame.chunk;
      if (chunk?.type === 'text-delta' && typeof chunk.text === 'string' && chunk.text !== '') {
        runningSessions.set(sessionId, true);
        bus.publish(
          'message.delta',
          { sessionId, messageId: activeAttempts.get(sessionId) ?? streamMessageId(), text: chunk.text },
          { sessionId },
        );
      }
      return;
    }
    if (frame.type === 'end') {
      const messageId = activeAttempts.get(sessionId) ?? (attemptId !== '' ? `m_${attemptId}` : null);
      if (messageId) bus.publish('message.completed', { sessionId, messageId }, { sessionId });
      activeAttempts.delete(sessionId);
    }
  }

  /**
   * Subscribe to the DSH event feed. Returns a disposer; the caller owns it
   * through its plugin effect.
   * @returns {() => void}
   */
  function attach() {
    if (!ctx || typeof ctx.on !== 'function') return () => {};
    /** @type {Array<() => void>} */
    const disposers = [];
    const on = (/** @type {string} */ name, /** @type {(...args: any[]) => void} */ listener) => {
      const dispose = ctx.on(name, listener);
      if (typeof dispose === 'function') disposers.push(dispose);
    };

    on('session/event', (session, event) => {
      const sessionId = session?.id ? String(session.id) : '';
      noteSessionHeader(sessionId, session?.header);
      handleSessionEvent(sessionId, event, session?.header?.cwd);
    });
    on('agent/assistant-stream', (payload) => {
      handleAssistantStream(payload);
    });
    on('api-session/added', (summary) => {
      const sessionId = summary?.sessionId ? String(summary.sessionId) : '';
      if (sessionId === '') return;
      if (internalReasonFromSessionSummary(summary) === undefined) internalSessionIds.delete(sessionId);
      else internalSessionIds.add(sessionId);
      if (isHiddenSession(sessionId)) return;
      runningSessions.set(sessionId, summary.running === true);
      void publishSummary(sessionId);
    });
    on('api-session/status', (sessionId, running) => {
      const id = String(sessionId);
      if (isHiddenSession(id)) return;
      runningSessions.set(id, running === true);
      void publishSummary(id);
    });
    on('api-session/activity', (sessionId) => {
      const id = String(sessionId);
      if (isHiddenSession(id)) return;
      void publishSummary(id);
    });
    on('api-session/removed', (sessionId) => {
      const id = String(sessionId);
      refreshHiddenStore();
      const hidden = isHiddenSession(id);
      runningSessions.delete(id);
      activeAttempts.delete(id);
      toolInfo.delete(id);
      internalSessionIds.delete(id);
      if (hidden) return;
      bus?.publish('session.removed', { sessionId: id }, { sessionId: id });
    });

    // Read-only approval mirror: pass-through waterfall listener. Its disposer
    // also clears the pending snapshot; the adapter clears again on teardown.
    const disposeApprovalMirror = mirror.attach(ctx);
    if (typeof disposeApprovalMirror === 'function') disposers.push(disposeApprovalMirror);

    // Read-only user-question mirror: same pass-through contract.
    const disposeQuestionMirror = questionMirrorInstance.attach(ctx);
    if (typeof disposeQuestionMirror === 'function') disposers.push(disposeQuestionMirror);

    // Periodic lazy refresh of the persisted hidden list so SSE filtering picks
    // up external edits even while no list request is made (the store throttles
    // the actual fs stat; this timer is cheap and unref'd).
    let hiddenRefreshTimer = null;
    if (hiddenStore?.filePath && bus) {
      const interval = Math.max(250, Number(hiddenStore.checkIntervalMs) || 1000);
      hiddenRefreshTimer = setInterval(() => refreshHiddenStore(), interval);
      hiddenRefreshTimer.unref?.();
    }

    return () => {
      if (hiddenRefreshTimer !== null) clearInterval(hiddenRefreshTimer);
      hiddenRefreshTimer = null;
      while (disposers.length > 0) {
        const dispose = disposers.pop();
        try {
          dispose?.();
        } catch {
          /* ignore teardown race */
        }
      }
      mirror.clear();
      questionMirrorInstance.clear();
    };
  }

  return {
    hostInfo,
    listWorkspaces,
    listPresets,
    listSessions,
    getUsage,
    getSession,
    getSessionSummary,
    sessionExists,
    sessionAccess,
    renameSession,
    archiveSession,
    unarchiveSession,
    listArchivedSessions,
    searchSessions,
    createSession,
    uploadAttachment,
    readAttachment,
    listSessionFiles,
    readSessionFileContent,
    readSessionFileRaw,
    listSchedules,
    createSessionSchedule,
    updateSchedule,
    deleteSchedule,
    prompt,
    interrupt,
    getSessionGoal,
    createSessionGoal,
    resumeSessionGoal,
    pauseSessionGoal,
    completeSessionGoal,
    editSessionGoal,
    clearSessionGoal,
    listApprovals,
    getApprovalPolicy,
    setApprovalPolicy,
    getModelCatalog,
    getSessionModel,
    selectSessionModel,
    listSessionQueue,
    updateSessionQueue,
    getSessionSkills,
    getSessionTodos,
    getSessionTeam,
    getSessionTrajectory,
    getSessionJobs,
    getSessionContext,
    forkSession,
    resolveApproval,
    listQuestions,
    answerQuestion,
    attach,
    /** exposed for tests/diagnostics */
    _internal: {
      runningSessions,
      activeAttempts,
      toolInfo,
      internalSessionIds,
      autoHiddenIds,
      hiddenStore,
      approvalMirror: mirror,
      questionMirror: questionMirrorInstance,
    },
  };
}
