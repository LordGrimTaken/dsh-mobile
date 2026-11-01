/**
 * Pure host-wide message-search helpers for the Mobile API.
 *
 * The adapter feeds raw session events here; this module owns query/limit
 * validation, case-insensitive matching, bounded scanning and snippet shaping.
 * No DSH service or host file is touched, so the rules are fully unit-testable.
 */

import { ApiError } from './errors.js';
import { extractText, isHumanUserMessage, toolDetail } from './mappers.js';

/** Minimum accepted query length. */
export const SEARCH_MIN_QUERY_CHARS = 2;
/** Maximum accepted query length. */
export const SEARCH_MAX_QUERY_CHARS = 200;
/** Default number of hits returned. */
export const SEARCH_DEFAULT_LIMIT = 50;
/** Maximum number of hits returned. */
export const SEARCH_MAX_LIMIT = 100;
/** Default offset for paginated search. */
export const SEARCH_DEFAULT_OFFSET = 0;
/** Maximum accepted search offset (bounded so pagination stays cheap). */
export const SEARCH_MAX_OFFSET = 500;
/** Maximum number of visible sessions scanned by one request. */
export const SEARCH_MAX_SESSIONS = 200;
/** Maximum events scanned per session. */
export const SEARCH_MAX_EVENTS_PER_SESSION = 2000;
/** Maximum matches collected before the search reports truncation. */
export const SEARCH_MAX_MATCHES = 500;
/**
 * Self-identifying framing of a scheduler-delivered prompt. Semantic
 * `filterEvents` documents do not carry `source.kind`, so the fast path uses
 * this framing to keep schedule instructions and their operational details out
 * of host-wide search results without reading snapshots.
 */
export const SEARCH_SCHEDULE_MARKERS = [
  '[SCHEDULE REMINDER]',
  'This is a scheduled message from the user',
];
/**
 * Matches kept from a single session. Without this, one very busy recent
 * session can fill the global cap (e.g. matches for "the") and older sessions
 * never appear in results or pages.
 */
export const SEARCH_MAX_MATCHES_PER_SESSION = 50;
/** Wall-clock budget for one search request (under the HTTP request timeout). */
export const SEARCH_WALL_CLOCK_BUDGET_MS = 8000;
/** Minimum remaining budget worth starting another session snapshot read. */
export const SEARCH_MIN_READ_MS = 25;
/** Session reads run in small bounded chunks so more recent sessions are scanned. */
export const SEARCH_READ_CONCURRENCY = 4;
/** Event types searched (semantic documents path); keeps image blocks out of scope. */
export const SEARCH_SEMANTIC_EVENT_TYPES = Object.freeze(['user/message', 'assistant/message', 'tool/call']);
/** Snippet target length (before any ellipsis). */
export const SEARCH_SNIPPET_CHARS = 200;
/** Roles accepted by the `role` filter (canonical order). */
export const SEARCH_ROLES = Object.freeze(['user', 'assistant', 'tool']);

/**
 * Normalize the `role` query parameter: absent/empty → undefined, otherwise a
 * comma-separated list of distinct `user|assistant|tool` values (max 3).
 *
 * @param {unknown} value
 * @returns {string[] | undefined}
 */
export function normalizeSearchRoles(value) {
  if (value === undefined || value === null) return undefined;
  const rawValues = Array.isArray(value) ? value : [value];
  /** @type {Set<string>} */
  const roles = new Set();
  for (const raw of rawValues) {
    if (typeof raw !== 'string') {
      throw new ApiError('invalid_request', 'Query parameter role must be a comma-separated list of user, assistant, tool');
    }
    for (const part of raw.split(',')) {
      const role = part.trim().toLowerCase();
      if (role === '') continue;
      if (!SEARCH_ROLES.includes(role)) {
        throw new ApiError('invalid_request', `Query parameter role contains an invalid role (${SEARCH_ROLES.join(', ')})`);
      }
      roles.add(role);
      if (roles.size > SEARCH_ROLES.length) {
        throw new ApiError('invalid_request', 'Query parameter role accepts at most 3 distinct roles');
      }
    }
  }
  if (roles.size === 0) return undefined;
  return SEARCH_ROLES.filter((role) => roles.has(role));
}

/**
 * Parse one `since`/`until` value: ISO-8601 (preferred) or epoch milliseconds.
 * Returns the epoch ms plus the canonical ISO echo; absent/empty → undefined.
 *
 * @param {unknown} value
 * @param {string} field
 * @returns {{ ms: number, iso: string } | undefined}
 */
export function normalizeSearchTimestamp(value, field) {
  if (value === undefined || value === null) return undefined;
  if (typeof value !== 'string' && typeof value !== 'number') {
    throw new ApiError('invalid_request', `Query parameter ${field} must be an ISO-8601 timestamp or epoch milliseconds`);
  }
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value) || value < 0) {
      throw new ApiError('invalid_request', `Query parameter ${field} must be a valid timestamp`);
    }
    return { ms: value, iso: new Date(value).toISOString() };
  }
  const text = value.trim();
  if (text === '') return undefined;
  let ms;
  if (/^\d+$/.test(text)) {
    ms = Number(text);
    if (!Number.isSafeInteger(ms)) {
      throw new ApiError('invalid_request', `Query parameter ${field} must be a valid timestamp`);
    }
  } else {
    if (!/^\d{4}-\d{2}-\d{2}(?:[T ].*)?$/.test(text)) {
      throw new ApiError('invalid_request', `Query parameter ${field} must be an ISO-8601 timestamp or epoch milliseconds`);
    }
    ms = Date.parse(text);
    if (!Number.isFinite(ms)) {
      throw new ApiError('invalid_request', `Query parameter ${field} must be an ISO-8601 timestamp or epoch milliseconds`);
    }
  }
  return { ms, iso: new Date(ms).toISOString() };
}

/**
 * Normalize all search filters. Absent filters produce an empty object so the
 * unfiltered response stays byte-for-byte compatible.
 *
 * @param {{ role?: unknown, since?: unknown, until?: unknown }} [raw]
 * @returns {{ roles?: string[], since?: number, until?: number, echo: { role?: string[], since?: string, until?: string } }}
 */
export function normalizeSearchFilters(raw = {}) {
  // Accept both the raw query shape (`role`) and an already-normalized object
  // (`roles`) so a normalized filters object can be passed through idempotently.
  const roles = normalizeSearchRoles(raw?.role ?? raw?.roles);
  const since = normalizeSearchTimestamp(raw?.since, 'since');
  const until = normalizeSearchTimestamp(raw?.until, 'until');
  if (since !== undefined && until !== undefined && since.ms > until.ms) {
    throw new ApiError('invalid_request', 'Query parameter since must not be after until');
  }
  /** @type {{ role?: string[], since?: string, until?: string }} */
  const echo = {};
  if (roles !== undefined) echo.role = roles;
  if (since !== undefined) echo.since = since.iso;
  if (until !== undefined) echo.until = until.iso;
  return {
    ...(roles === undefined ? {} : { roles }),
    ...(since === undefined ? {} : { since: since.ms }),
    ...(until === undefined ? {} : { until: until.ms }),
    echo,
  };
}

/** @param {{ echo?: Record<string, unknown> }} filters */
export function hasSearchFilters(filters) {
  return filters?.echo !== undefined && Object.keys(filters.echo).length > 0;
}

/**
 * Normalize the `q` query parameter: trimmed, 2..200 characters.
 * @param {unknown} value
 * @returns {string}
 */
export function normalizeSearchQuery(value) {
  if (typeof value !== 'string') {
    throw new ApiError('invalid_request', 'Query parameter q is required');
  }
  const query = value.trim();
  if (query.length < SEARCH_MIN_QUERY_CHARS) {
    throw new ApiError('invalid_request', `Query parameter q must be at least ${SEARCH_MIN_QUERY_CHARS} characters`);
  }
  if (query.length > SEARCH_MAX_QUERY_CHARS) {
    throw new ApiError('invalid_request', `Query parameter q exceeds ${SEARCH_MAX_QUERY_CHARS} characters`);
  }
  return query;
}

/**
 * Normalize the `limit` query parameter: integer 1..100 (default 50).
 * @param {unknown} value
 * @returns {number}
 */
export function normalizeSearchLimit(value) {
  if (value === undefined || value === null || value === '') return SEARCH_DEFAULT_LIMIT;
  /** @type {number} */
  let parsed;
  if (typeof value === 'number') {
    parsed = value;
  } else if (typeof value === 'string' && /^\d+$/.test(value.trim())) {
    parsed = Number(value.trim());
  } else {
    throw new ApiError('invalid_request', `Query parameter limit must be an integer 1..${SEARCH_MAX_LIMIT}`);
  }
  if (!Number.isSafeInteger(parsed) || parsed < 1 || parsed > SEARCH_MAX_LIMIT) {
    throw new ApiError('invalid_request', `Query parameter limit must be an integer 1..${SEARCH_MAX_LIMIT}`);
  }
  return parsed;
}

/**
 * Normalize the `offset` query parameter: integer 0..500 (default 0).
 * @param {unknown} value
 * @returns {number}
 */
export function normalizeSearchOffset(value) {
  if (value === undefined || value === null || value === '') return SEARCH_DEFAULT_OFFSET;
  /** @type {number} */
  let parsed;
  if (typeof value === 'number') {
    parsed = value;
  } else if (typeof value === 'string' && /^\d+$/.test(value.trim())) {
    parsed = Number(value.trim());
  } else {
    throw new ApiError('invalid_request', `Query parameter offset must be an integer 0..${SEARCH_MAX_OFFSET}`);
  }
  if (!Number.isSafeInteger(parsed) || parsed < 0 || parsed > SEARCH_MAX_OFFSET) {
    throw new ApiError('invalid_request', `Query parameter offset must be an integer 0..${SEARCH_MAX_OFFSET}`);
  }
  return parsed;
}

/**
 * Slice the already newest-first collected hits into one stable page.
 *
 * Frozen pagination contract:
 * - `offset` echoes the requested offset (clamped to the accepted range);
 * - `hasMore` is true when another collected match exists beyond this page, or
 *   when the bounded scan was truncated (cap/budget) **and this page returned at
 *   least one hit**. A truncated scan with an empty page reports `hasMore:false`
 *   so the client can never loop on the same offset;
 * - `nextOffset` is `offset + hits.length` when `hasMore` is true, else `null`;
 * - `truncated` keeps its scan-level meaning (cap/budget stopped the scan) and is
 *   independent of pagination.
 *
 * @param {ReadonlyArray<any>} hits
 * @param {{ offset?: number, limit?: number, truncated?: boolean }} [options]
 * @returns {{ hits: Array<any>, offset: number, nextOffset: number | null, hasMore: boolean, truncated: boolean }}
 */
export function paginateSearchHits(hits, { offset = SEARCH_DEFAULT_OFFSET, limit = SEARCH_DEFAULT_LIMIT, truncated = false } = {}) {
  const list = Array.isArray(hits) ? hits : [];
  const start = Number.isSafeInteger(offset) && offset >= 0
    ? Math.min(offset, SEARCH_MAX_OFFSET)
    : SEARCH_DEFAULT_OFFSET;
  const pageSize = Number.isSafeInteger(limit) && limit > 0
    ? Math.min(limit, SEARCH_MAX_LIMIT)
    : SEARCH_DEFAULT_LIMIT;
  const page = list.slice(start, start + pageSize);
  const scanTruncated = truncated === true;
  const moreBeyondPage = list.length > start + page.length;
  const hasMore = moreBeyondPage || (scanTruncated && page.length > 0);
  return {
    hits: page,
    offset: start,
    nextOffset: hasMore ? start + page.length : null,
    hasMore,
    truncated: scanTruncated,
  };
}

/** @param {unknown} text @param {string} query */
export function findFirstMatch(text, query) {
  if (typeof text !== 'string' || text === '') return -1;
  return text.toLowerCase().indexOf(query.toLowerCase());
}

/**
 * Snippet around the first match, collapsed to one line, with ellipses when
 * the match is not near the start/end. Never longer than ~200 chars.
 *
 * @param {string} text
 * @param {string} query
 * @param {number} [max]
 */
export function makeSearchSnippet(text, query, max = SEARCH_SNIPPET_CHARS) {
  const source = String(text ?? '');
  const index = findFirstMatch(source, query);
  if (index < 0) return '';
  const context = Math.max(0, Math.floor((max - Math.min(query.length, max)) / 2));
  let start = Math.max(0, index - context);
  let end = Math.min(source.length, start + max);
  if (end - start < max) start = Math.max(0, end - max);
  let snippet = source
    .slice(start, end)
    .replace(/\s+/g, ' ')
    .trim();
  if (start > 0) snippet = `…${snippet}`;
  if (end < source.length) snippet = `${snippet}…`;
  return snippet.length > max + 2 ? snippet.slice(0, max + 2) : snippet;
}

/** Stable message identity for one matched event (matches buildMessages ids). */
function messageIdFor(type, data, event) {
  if (type === 'tool/call') {
    const callId = String(data?.callId ?? '');
    return callId === '' ? '' : `m_${callId}`;
  }
  const message = type === 'assistant/message' ? data?.message : data;
  if (typeof message?.id === 'string' && message.id !== '') return message.id;
  return `m_ev_${String(event?.seq ?? '0')}`;
}

/**
 * Scan one session's events for the query.
 *
 * Matches:
 * - human `user/message` text,
 * - `assistant/message` text,
 * - `tool/call` command-like `toolDetail(arguments)` (role `tool`).
 *
 * Injected user context, tool results and other event types are skipped. The
 * first match per message id wins; scanning stops at `maxEvents`, the deadline,
 * or `SEARCH_MAX_MATCHES`. Optional `roles` and `since`/`until` (epoch ms,
 * inclusive) filter candidates during the scan; when a date bound is set,
 * matches whose event has no timestamp are excluded.
 *
 * @param {ReadonlyArray<any>} events
 * @param {string} query
 * @param {{ maxEvents?: number, deadline?: number, now?: () => number, roles?: ReadonlyArray<string>, since?: number, until?: number }} [options]
 * @returns {{ matches: Array<{ messageId: string, role: string, snippet: string, text: string, time?: number }>, truncated: boolean }}
 */
export function searchEvents(events, query, options = {}) {
  const maxEvents = Number.isSafeInteger(options.maxEvents) && options.maxEvents > 0
    ? options.maxEvents
    : SEARCH_MAX_EVENTS_PER_SESSION;
  const deadline = Number.isFinite(options.deadline) ? options.deadline : Number.POSITIVE_INFINITY;
  const now = typeof options.now === 'function' ? options.now : Date.now;
  const lowerQuery = query.toLowerCase();
  const roleFilter = Array.isArray(options.roles) && options.roles.length > 0
    ? new Set(options.roles)
    : null;
  const since = Number.isFinite(options.since) ? Number(options.since) : undefined;
  const until = Number.isFinite(options.until) ? Number(options.until) : undefined;
  const dateFiltered = since !== undefined || until !== undefined;

  /** @type {Array<{ messageId: string, role: string, snippet: string, text: string, seq?: number, time?: number }>} */
  const matches = [];
  const seen = new Set();
  let scanned = 0;
  let truncated = false;

  for (const event of events ?? []) {
    if (scanned >= maxEvents) {
      truncated = true;
      break;
    }
    if (now() > deadline) {
      truncated = true;
      break;
    }
    scanned += 1;
    if (!event || typeof event !== 'object') continue;
    const type = /** @type {any} */ (event).type;
    const data = /** @type {any} */ (event).data ?? {};
    /** @type {{ role: string, text: string } | null} */
    let candidate = null;
    if (type === 'user/message') {
      if (!isHumanUserMessage(data)) continue;
      const text = extractText(data?.content);
      if (text !== '') candidate = { role: 'user', text };
    } else if (type === 'assistant/message') {
      const text = extractText(data?.message?.content);
      if (text !== '') candidate = { role: 'assistant', text };
    } else if (type === 'tool/call') {
      const detail = toolDetail(data?.arguments);
      if (detail !== '') {
        const name = typeof data?.name === 'string' && data.name !== '' ? data.name : 'tool';
        candidate = { role: 'tool', text: `${name}: ${detail}` };
      }
    }
    if (!candidate || !candidate.text.toLowerCase().includes(lowerQuery)) continue;
    if (roleFilter !== null && !roleFilter.has(candidate.role)) continue;
    if (dateFiltered) {
      const time = typeof /** @type {any} */ (event).time === 'number' ? /** @type {any} */ (event).time : undefined;
      if (time === undefined) continue; // documented: date-bounded searches skip undated events
      if (since !== undefined && time < since) continue;
      if (until !== undefined && time > until) continue;
    }
    const messageId = messageIdFor(type, data, event);
    if (messageId === '' || seen.has(messageId)) continue;
    seen.add(messageId);
    const rawEventSeq = /** @type {any} */ (event).seq;
    const eventSeq = Number.isSafeInteger(rawEventSeq) && rawEventSeq >= 0 ? rawEventSeq : undefined;
    matches.push({
      messageId,
      role: candidate.role,
      snippet: makeSearchSnippet(candidate.text, query),
      text: candidate.text,
      ...(eventSeq === undefined ? {} : { seq: eventSeq }),
      ...(typeof /** @type {any} */ (event).time === 'number'
        ? { time: /** @type {any} */ (event).time }
        : {}),
    });
    if (matches.length >= SEARCH_MAX_MATCHES) {
      truncated = true;
      break;
    }
  }
  return { matches, truncated };
}

/**
 * Whether semantic document text carries the scheduler's self-identifying
 * framing. Heuristic: a human prompt that literally includes a marker is also
 * excluded from search (documented trade-off); the transcript still shows it.
 *
 * @param {string} text
 */
function isScheduledSemanticText(text) {
  const lower = text.toLowerCase();
  return SEARCH_SCHEDULE_MARKERS.some((marker) => lower.includes(marker.toLowerCase()));
}

/**
 * Map lightweight `sessionQuery.filterEvents` semantic documents to search
 * matches. This path never materialises full session events (so tool-result
 * images and replay validation are not paid during search). The caller applies
 * typing/date filters server-side too, but this helper re-applies the exact
 * substring semantics so the result matches the raw-event search.
 *
 * Scheduled prompts are transcript-only and must not become search hits. Their
 * semantic documents have no `source.kind`, but the delivery is self-identifying
 * by its framing (see {@link SEARCH_SCHEDULE_MARKERS}); user documents carrying
 * that framing are skipped on this fast path. Raw-event search excludes them via
 * the strict human predicate.
 *
 * @param {ReadonlyArray<any>} documents
 * @param {string} query
 * @param {{ roles?: ReadonlyArray<string>, since?: number, until?: number }} [options]
 * @returns {Array<{ messageId: string, role: string, snippet: string, text: string, seq?: number, time?: number }>}
 */
export function matchesFromSemanticDocuments(documents, query, options = {}) {
  const roleFilter = Array.isArray(options.roles) && options.roles.length > 0
    ? new Set(options.roles)
    : null;
  const since = Number.isFinite(options.since) ? Number(options.since) : undefined;
  const until = Number.isFinite(options.until) ? Number(options.until) : undefined;
  const dateFiltered = since !== undefined || until !== undefined;
  const matches = [];
  const seen = new Set();
  for (const document of documents ?? []) {
    if (!document || typeof document.text !== 'string' || document.text === '') continue;
    /** @type {string | null} */
    let role = null;
    if (document.type === 'user/message') role = 'user';
    else if (document.type === 'assistant/message') role = 'assistant';
    else if (document.type === 'tool/call') role = 'tool';
    if (role === null) continue;
    if (role === 'user' && isScheduledSemanticText(document.text)) continue;
    if (roleFilter !== null && !roleFilter.has(role)) continue;
    const time = Number.isFinite(document.time) ? document.time : undefined;
    if (dateFiltered) {
      if (time === undefined) continue;
      if (since !== undefined && time < since) continue;
      if (until !== undefined && time > until) continue;
    }
    if (findFirstMatch(document.text, query) < 0) continue;
    const rawDocumentSeq = /** @type {any} */ (document).seq;
    const documentSeq = Number.isSafeInteger(rawDocumentSeq) && rawDocumentSeq >= 0 ? rawDocumentSeq : undefined;
    // Keep the existing fallback id (`m_ev_0`) when no usable seq exists, but
    // never fabricate a `seq` field on the hit.
    const messageId = `m_ev_${documentSeq ?? 0}`;
    if (seen.has(messageId)) continue;
    seen.add(messageId);
    matches.push({
      messageId,
      role,
      snippet: makeSearchSnippet(document.text, query),
      text: document.text,
      ...(documentSeq === undefined ? {} : { seq: documentSeq }),
      ...(time === undefined ? {} : { time }),
    });
    if (matches.length >= SEARCH_MAX_MATCHES) break;
  }
  return matches;
}
