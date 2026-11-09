/**
 * HTTP router for DSH Mobile API v1 (docs/mobile-api-v1.md).
 *
 * `handle()` is a pure request → response-dscriptor function: no sockets, no
 * timers, no DSH imports. The HTTP carrier (lib/server.js) performs the I/O and
 * the SSE stream. Tests drive `handle()` directly with fake requests.
 */

import { decodeBase64Attachment, kindForMimeType } from './attachments.js';
import { ApiError, errorResponse, toApiError } from './errors.js';
import { buildPromptContent, isoTime } from './mappers.js';
import { normalizeModelSelection } from './models.js';
import { PAIR_APP_JS, PAIR_PAGE_HTML, pairPageHeaders, qrLibrarySource } from './pair-page.js';
import { emptyPushSuppression, publicPushSuppression } from './push.js';
import { normalizeQueueAction } from './queue.js';
import { parseScheduleCreateBody, parseScheduleUpdateBody } from './schedules.js';
import { SEARCH_MAX_LIMIT, hasSearchFilters, normalizeSearchFilters, normalizeSearchLimit, normalizeSearchOffset, normalizeSearchQuery } from './search.js';
import { normalizeSessionTitle } from './sessions.js';
import { readProfilePlugins } from './plugins.js';
import { MAX_ETAG_BODY_BYTES, computeWeakEtag, etagMatches } from './http-cache.js';
/** All v1 routes live under this prefix; the prefix itself is optional (`/health` works too). */
export const API_PREFIX = '/mobile/v1';

/** Conservative path-segment allowlist for ids. */
const SEGMENT = /^[A-Za-z0-9_.:-]{1,200}$/;

/** Max prompt characters accepted by POST /sessions/{id}/prompt. */
export const MAX_PROMPT_CHARS = 32_000;

/** Max attachment display-name characters. */
export const MAX_ATTACHMENT_NAME_CHARS = 255;

/** Max declared-mime-type characters. */
export const MAX_MIME_TYPE_CHARS = 128;

/** Hard cap on ids accepted in one prompt body (real cap is config.attachments.maxPerPrompt). */
const MAX_ATTACHMENT_IDS = 64;

/** Reasonable media-type shape check. */
const MIME_TYPE_PATTERN = /^[a-z0-9][a-z0-9!#$&^_.+-]*\/[a-z0-9][a-z0-9!#$&^_.+-]*$/i;

/**
 * Whether an address is loopback (IPv4, IPv6, or IPv4-mapped IPv6).
 * @param {unknown} address
 */
export function isLoopbackAddress(address) {
  const raw = String(address ?? '').trim().toLowerCase();
  const host = raw.startsWith('::ffff:') ? raw.slice(7) : raw;
  return host === '127.0.0.1' || host === '::1' || host === 'localhost';
}

/**
 * Whether a request carries reverse-proxy identity headers.
 *
 * Tailscale Serve (and any HTTP reverse proxy) forwards remote clients to
 * 127.0.0.1, so a naive loopback check would expose /pair/new to the tailnet.
 * These headers exist only on proxied requests; the desktop calls directly, so
 * rejecting them keeps pairing desktop-only even behind Tailscale Serve.
 * (docs/mobile-api-v1.md § Host plugin rules; a local admin secret replaces
 * this heuristic in v0.3.)
 *
 * @param {Record<string, string | string[] | undefined>} headers
 */
export function isProxied(headers) {
  const exact = new Set([
    'x-forwarded-for',
    'x-forwarded-host',
    'x-forwarded-proto',
    'x-real-ip',
    'forwarded',
  ]);
  for (const key of Object.keys(headers ?? {})) {
    const lower = key.toLowerCase();
    if (exact.has(lower)) return true;
    if (lower.startsWith('tailscale-user-') || lower.startsWith('tailscale-funnel-')) return true;
  }
  return false;
}

/**
 * Strip the optional `/mobile/v1` prefix.
 * @param {string} pathname
 */
export function normalizePath(pathname) {
  const path = String(pathname ?? '/');
  if (path === API_PREFIX) return '/';
  if (path.startsWith(`${API_PREFIX}/`)) return path.slice(API_PREFIX.length);
  return path;
}

/**
 * Build the `dshmobile://pair?base=&code=&host=` QR payload.
 * @param {{ publicBaseUrl: string, hostName: string }} config
 * @param {string} code
 */
export function buildQrPayload(config, code) {
  const params = new URLSearchParams();
  if (config.publicBaseUrl) params.set('base', config.publicBaseUrl);
  params.set('code', code);
  params.set('host', config.hostName);
  return `dshmobile://pair?${params.toString()}`;
}

/** @param {number} status @param {unknown} body */
function json(status, body) {
  return { status, body };
}

/**
 * JSON result with a weak ETag, or an empty 304 when the caller's If-None-Match matches.
 * Used for the session snapshot and transcript endpoints.
 */
function etagJson(request, body) {
  const payload = JSON.stringify(body);
  if (payload.length > MAX_ETAG_BODY_BYTES) return json(200, body);
  const etag = computeWeakEtag(payload);
  if (etagMatches(request.headers?.['if-none-match'], etag)) {
    return { status: 304, headers: { ETag: etag } };
  }
  return { status: 200, body, headers: { ETag: etag } };
}

/**
 * Static page/script response. The carrier writes `raw` bytes verbatim, so HTML is never
 * JSON-encoded; headers override the JSON defaults.
 * @param {Record<string, string>} headers
 * @param {string} body
 */
function page(headers, body) {
  return {
    status: 200,
    headers: { ...pairPageHeaders(), ...headers },
    raw: new TextEncoder().encode(body),
  };
}

/** @param {unknown} body @param {boolean} required */
function parseJsonObject(body, required = false) {
  const text = body === undefined || body === null ? '' : String(body).trim();
  if (text === '') {
    if (required) throw new ApiError('invalid_request', 'Request body must be a JSON object');
    return {};
  }
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new ApiError('invalid_request', 'Request body must be valid JSON');
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new ApiError('invalid_request', 'Request body must be a JSON object');
  }
  return parsed;
}

/** @param {unknown} value @param {string} field @param {{ max?: number, required?: boolean }} [options] */
function optionalString(value, field, options = {}) {
  if (value === undefined || value === null) {
    if (options.required) throw new ApiError('invalid_request', `Missing required field: ${field}`);
    return undefined;
  }
  if (typeof value !== 'string') throw new ApiError('invalid_request', `Field ${field} must be a string`);
  const max = options.max ?? 1024;
  if (value.length > max) throw new ApiError('invalid_request', `Field ${field} exceeds ${max} characters`);
  return value;
}

/**
 * Validate an optional positive safe integer.
 * @param {unknown} value @param {string} field @param {{ max?: number, required?: boolean }} [options]
 */
function optionalPositiveInt(value, field, options = {}) {
  if (value === undefined || value === null) {
    if (options.required) throw new ApiError('invalid_request', `Missing required field: ${field}`);
    return undefined;
  }
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 1) {
    throw new ApiError('invalid_request', `Field ${field} must be a positive integer`);
  }
  const max = options.max ?? Number.MAX_SAFE_INTEGER;
  if (value > max) throw new ApiError('invalid_request', `Field ${field} must be at most ${max}`);
  return value;
}

/**
 * Validate the optional `attachmentIds` prompt field.
 * @param {unknown} value
 * @returns {string[]}
 */
function parseAttachmentIds(value) {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value)) throw new ApiError('invalid_request', 'Field attachmentIds must be an array of strings');
  if (value.length > MAX_ATTACHMENT_IDS) {
    throw new ApiError('invalid_request', `Field attachmentIds must contain at most ${MAX_ATTACHMENT_IDS} ids`);
  }
  return value.map((id) => {
    if (typeof id !== 'string' || id.trim() === '' || id.length > 200) {
      throw new ApiError('invalid_request', 'Field attachmentIds must contain non-empty attachment ids');
    }
    return id;
  });
}

/**
 * Normalize and validate an upload's declared mime type.
 * @param {unknown} value
 */
function parseMimeType(value) {
  const raw = optionalString(value, 'mimeType', { required: true, max: MAX_MIME_TYPE_CHARS });
  const mimeType = raw.trim().toLowerCase();
  if (!MIME_TYPE_PATTERN.test(mimeType)) {
    throw new ApiError('invalid_request', 'Field mimeType must be a valid media type, e.g. image/png');
  }
  return mimeType;
}

/** Extract the bearer token from headers. */
function bearerToken(headers) {
  const raw = headers?.authorization ?? headers?.Authorization;
  if (typeof raw !== 'string') throw new ApiError('unauthorized', 'Missing or invalid device token');
  const match = /^Bearer\s+(\S+)$/i.exec(raw.trim());
  if (!match) throw new ApiError('unauthorized', 'Missing or invalid device token');
  return match[1];
}

/** Case-insensitive single header lookup (Node lowercases, tests may not). */
function headerValue(headers, name) {
  for (const [key, value] of Object.entries(headers ?? {})) {
    if (key.toLowerCase() !== name) continue;
    if (Array.isArray(value)) return typeof value[0] === 'string' ? value[0] : undefined;
    return typeof value === 'string' ? value : undefined;
  }
  return undefined;
}

/**
 * Create the router.
 *
 * @param {{
 *   config: any,
 *   pairing: import('./pairing.js').PairingStore,
 *   adapter: ReturnType<import('./dsh-adapter.js').createDshAdapter>,
 *   attachments?: import('./attachments.js').AttachmentStore | null,
 *   hiddenStore?: import('./hidden-sessions.js').HiddenSessionStore | null,
 *   bus?: import('./events.js').EventBus | null,
 *   logger?: Pick<Console, 'warn' | 'error' | 'info'>,
 *   now?: () => number,
 * }} deps
 */
export function createRouter({ config, pairing, adapter, attachments = null, hiddenStore = null, push = null, logger = console }) {
  /**
   * @param {{
   *   method: string,
   *   pathname: string,
   *   query?: URLSearchParams | string | null,
   *   headers?: Record<string, any>,
   *   body?: string,
   *   remoteAddress?: string,
   * }} request
   * @returns {Promise<{ status: number, body?: unknown, headers?: Record<string, string>, sse?: boolean, since?: string, deviceId?: string }>}
   */
  async function handle(request) {
    try {
      const method = String(request.method ?? 'GET').toUpperCase();
      const path = normalizePath(request.pathname ?? '/');
      const query = request.query instanceof URLSearchParams
        ? request.query
        : new URLSearchParams(typeof request.query === 'string' ? request.query : '');
      const headers = request.headers ?? {};
      const remoteAddress = String(request.remoteAddress ?? '');

      // ---- Unauthenticated routes -------------------------------------
      if (method === 'GET' && path === '/health') {
        return json(200, {
          ok: true,
          version: config.version,
          apiVersion: config.apiVersion,
          paired: pairing.hasDevices(),
        });
      }

      // ---- "Connect a phone" pairing page (loopback-served) ------------
      // The page itself is static. It mints codes by calling POST /pair/new from the browser on
      // the host, which keeps the endpoint's loopback + anti-proxy guard meaningful.
      if (method === 'GET' && (path === '/pair' || path === '/pair/')) {
        return page({}, PAIR_PAGE_HTML);
      }

      if (method === 'GET' && path === '/pair/app.js') {
        return page({ 'Content-Type': 'application/javascript; charset=utf-8' }, PAIR_APP_JS);
      }

      if (method === 'GET' && path === '/pair/qrcode-generator.js') {
        return page({ 'Content-Type': 'application/javascript; charset=utf-8' }, qrLibrarySource());
      }

      if (method === 'POST' && path === '/pair/new') {
        if (!isLoopbackAddress(remoteAddress)) {
          throw new ApiError('forbidden', 'pair/new is only available from the desktop (loopback)');
        }
        if (isProxied(headers)) {
          throw new ApiError('forbidden', 'pair/new must not be reached through a reverse proxy');
        }
        const { code, expiresAt } = pairing.createCode();
        return json(200, { code, expiresAt: isoTime(expiresAt), qrPayload: buildQrPayload(config, code) });
      }

      if (method === 'POST' && path === '/pair/complete') {
        const body = parseJsonObject(request.body, true);
        const code = optionalString(body.code, 'code', { required: true, max: 16 });
        const deviceName = optionalString(body.deviceName, 'deviceName', { max: 100 });
        const platform = optionalString(body.platform, 'platform', { max: 40 });
        const publicKey = optionalString(body.publicKey, 'publicKey', { max: 4096 });
        const { token, device } = pairing.complete({
          code: code.trim(),
          deviceName,
          platform,
          publicKey,
          ip: remoteAddress || 'unknown',
        });
        const hostInfo = await adapter.hostInfo();
        return json(200, {
          token,
          deviceId: device.deviceId,
          host: { name: hostInfo.name, apiVersion: config.apiVersion, version: config.version },
          workspaces: hostInfo.workspaces,
        });
      }

      if (method === 'POST' && path === '/pair/recovery') {
        if (typeof pairing.consumeRecoveryCode !== 'function') {
          throw new ApiError('unsupported', 'Recovery-code pairing is not available in this host process');
        }
        const body = parseJsonObject(request.body, true);
        const code = optionalString(body.code, 'code', { required: true, max: 256 });
        const deviceName = optionalString(body.deviceName, 'deviceName', { max: 100 });
        const platform = optionalString(body.platform, 'platform', { max: 40 });
        const publicKey = optionalString(body.publicKey, 'publicKey', { max: 4096 });
        const result = await pairing.consumeRecoveryCode(code, {
          deviceName,
          platform,
          publicKey,
          ip: remoteAddress || 'unknown',
        });
        if (!result) {
          throw new ApiError('invalid_code', 'Recovery code is invalid or already used', { status: 401 });
        }
        const hostInfo = await adapter.hostInfo();
        return json(200, {
          token: result.token,
          device: result.device,
          recoveryCode: result.nextCode,
          createdAt: result.createdAt,
          host: { name: hostInfo.name, apiVersion: config.apiVersion, version: config.version },
          workspaces: hostInfo.workspaces,
        });
      }

      if (method === 'GET' && path === '/pair/methods') {
        return json(200, {
          desktopCode: true,
          recovery: true,
          tailscaleAutoPair: config.pairing?.tailscaleAutoPair !== false,
          tailscaleLoginRequired: (config.pairing?.tailscaleLogin ?? '') !== '',
        });
      }

      if (method === 'POST' && path === '/pair/tailscale') {
        if (config.pairing?.tailscaleAutoPair === false) {
          throw new ApiError('forbidden', 'Tailscale auto-pairing is disabled on this host');
        }
        const body = parseJsonObject(request.body, false);
        const deviceName = optionalString(body.deviceName, 'deviceName', { max: 100 });
        const platform = optionalString(body.platform, 'platform', { max: 40 });
        const ip = remoteAddress || 'unknown';
        // Tailscale Serve adds these headers; the host only ever binds loopback.
        const identityHeader = headerValue(headers, 'tailscale-user-login') ??
          headerValue(headers, 'tailscale-user-name');
        const identity = typeof identityHeader === 'string' ? identityHeader.trim().slice(0, 320) : '';
        pairing.checkPairingRateLimit(ip);
        if (identity === '') {
          pairing.recordPairingFailure(ip);
          throw new ApiError(
            'invalid_request',
            'Tailscale identity is missing; open this host through Tailscale Serve',
            { details: { code: 'tailscale_identity_missing' } },
          );
        }
        const requiredLogin = config.pairing?.tailscaleLogin ?? '';
        if (requiredLogin !== '' && identity.toLowerCase() !== requiredLogin.toLowerCase()) {
          pairing.recordPairingFailure(ip);
          throw new ApiError('forbidden', 'This Tailscale identity is not allowed to pair with this host');
        }
        const { token, device } = pairing.pairDevice({ deviceName, platform, ip });
        const hostInfo = await adapter.hostInfo();
        return json(200, {
          token,
          device,
          deviceId: device.deviceId,
          host: { name: hostInfo.name, apiVersion: config.apiVersion, version: config.version },
          workspaces: hostInfo.workspaces,
          pairingMethod: 'tailscale',
          identity,
        });
      }

      // ---- Authenticated routes ---------------------------------------
      const token = bearerToken(headers);
      const device = pairing.authenticate(token);
      if (!device) throw new ApiError('unauthorized', 'Missing or invalid device token');
      const deviceId = String(device.deviceId);

      if (method === 'GET' && path === '/recovery') {
        if (typeof pairing.getRecoveryInfo !== 'function') {
          throw new ApiError('unsupported', 'Recovery-code pairing is not available in this host process');
        }
        return json(200, { supported: true, ...pairing.getRecoveryInfo() });
      }

      if (method === 'POST' && path === '/recovery') {
        if (typeof pairing.createRecoveryCode !== 'function') {
          throw new ApiError('unsupported', 'Recovery-code pairing is not available in this host process');
        }
        const created = await pairing.createRecoveryCode();
        return json(200, { code: created.code, createdAt: created.createdAt });
      }

      if (method === 'GET' && path === '/host') {
        const hostInfo = await adapter.hostInfo();
        return json(200, {
          name: hostInfo.name,
          version: hostInfo.version,
          workspaces: hostInfo.workspaces,
        });
      }

      if (method === 'GET' && path === '/devices') {
        const devices = pairing
          .listDevices()
          .slice(0, 64)
          .map((device) => ({
            deviceId: device.deviceId,
            deviceName: device.deviceName,
            platform: device.platform,
            pairedAt: device.pairedAt,
            lastSeenAt: device.lastSeenAt,
          }));
        return json(200, { supported: true, devices, currentDeviceId: deviceId });
      }

      const deviceRevokeMatch = /^\/devices\/([^/]+)$/.exec(path);
      if (method === 'DELETE' && deviceRevokeMatch) {
        if (typeof pairing.revokeDevice !== 'function') {
          throw new ApiError('unsupported', 'Paired-device management is not available in this host process');
        }
        const targetDeviceId = decodeSegment(deviceRevokeMatch[1]);
        if (targetDeviceId === deviceId) {
          throw new ApiError('invalid_request', 'cannot revoke the device you are using');
        }
        if (!(await pairing.revokeDevice(targetDeviceId))) {
          throw new ApiError('not_found', 'Device not found');
        }
        return json(200, { ok: true, revoked: true });
      }

      if (method === 'GET' && path === '/plugins') {
        return json(200, readProfilePlugins());
      }

      if (method === 'GET' && path === '/presets') {
        return json(200, await adapter.listPresets());
      }

      if (method === 'GET' && path === '/models') {
        return json(200, await adapter.getModelCatalog());
      }

      if (method === 'GET' && path === '/search') {
        const q = normalizeSearchQuery(query.get('q'));
        const limit = normalizeSearchLimit(query.get('limit'));
        const offset = normalizeSearchOffset(query.get('offset'));
        const rawFilters = {
          role: query.get('role'),
          since: query.get('since'),
          until: query.get('until'),
        };
        // Validate here, but pass the RAW values to the adapter so it normalizes
        // exactly once (a normalized object loses `.role`, which dropped the
        // role echo before QA caught it).
        const filters = normalizeSearchFilters(rawFilters);
        return json(
          200,
          await adapter.searchSessions(
            q,
            limit,
            hasSearchFilters(filters) ? rawFilters : undefined,
            offset,
          ),
        );
      }

      if (method === 'GET' && path === '/push') {
        if (!push) {
          return json(200, {
            supported: false,
            enabled: false,
            server: '',
            topic: '',
            hasToken: false,
            suppression: publicPushSuppression(emptyPushSuppression()),
          });
        }
        return json(200, {
          ...push.getPublicConfig(),
          suppression: push.getSuppressionSummary(),
        });
      }

      if (method === 'PUT' && path === '/push') {
        if (!push) throw new ApiError('unsupported', 'Push notifications are not available in this host process');
        const body = parseJsonObject(request.body, true);
        return json(200, await push.update(body));
      }

      if (method === 'PUT' && path === '/push/suppress') {
        if (!push) throw new ApiError('unsupported', 'Push notifications are not available in this host process');
        const body = parseJsonObject(request.body, true);
        return json(200, await push.updateSuppression(body));
      }

      if (method === 'POST' && path === '/push/test') {
        if (!push) throw new ApiError('unsupported', 'Push notifications are not available in this host process');
        return json(200, await push.sendTest());
      }

      if (method === 'GET' && path === '/schedules') {
        return json(200, await adapter.listSchedules());
      }

      const scheduleDeleteMatch = /^\/schedules\/([^/]+)$/.exec(path);
      if (method === 'DELETE' && scheduleDeleteMatch) {
        const scheduleId = decodeSegment(scheduleDeleteMatch[1]);
        return json(200, await adapter.deleteSchedule(scheduleId));
      }
      if (method === 'PATCH' && scheduleDeleteMatch) {
        const scheduleId = decodeSegment(scheduleDeleteMatch[1]);
        const body = parseJsonObject(request.body, true);
        const parsed = parseScheduleUpdateBody(body);
        return json(200, await adapter.updateSchedule(scheduleId, parsed));
      }

      const sessionSchedulesMatch = /^\/sessions\/([^/]+)\/schedules$/.exec(path);
      if (method === 'POST' && sessionSchedulesMatch) {
        const sessionId = decodeSegment(sessionSchedulesMatch[1]);
        const body = parseJsonObject(request.body, true);
        const parsed = parseScheduleCreateBody(body);
        return json(200, await adapter.createSessionSchedule(sessionId, parsed));
      }

      if (method === 'GET' && path === '/sessions') {
        return etagJson(request, { sessions: await adapter.listSessions() });
      }

      if (method === 'GET' && path === '/usage') {
        return json(200, await adapter.getUsage());
      }

      if (method === 'POST' && path === '/sessions') {
        const body = parseJsonObject(request.body, false);
        const workspaceId = optionalString(body.workspaceId, 'workspaceId', { max: 200 });
        const preset = optionalString(body.preset, 'preset', { max: 200 });
        return json(200, { session: await adapter.createSession({ workspaceId, preset }) });
      }

      // Curated hidden-session list (management endpoints for the app). These
      // must be matched BEFORE GET /sessions/{id} ("hidden" is not a session id).
      if (method === 'GET' && path === '/sessions/hidden') {
        if (!hiddenStore) throw new ApiError('unsupported', 'Hidden sessions are not available in this host process');
        await hiddenStore.refresh?.();
        const state = hiddenStore.getState();
        return json(200, { ids: state.ids, updatedAt: state.updatedAt });
      }

      if (method === 'PUT' && path === '/sessions/hidden') {
        if (!hiddenStore) throw new ApiError('unsupported', 'Hidden sessions are not available in this host process');
        const body = parseJsonObject(request.body, true);
        // Validation (array shape, session-id strings, ≤500 ids) lives in the
        // store/helper so the file cache and API share one rule set.
        const state = await hiddenStore.replace(body.hiddenIds);
        return json(200, { ids: state.ids, updatedAt: state.updatedAt });
      }

      // Archived sessions must be matched BEFORE GET /sessions/{id}.
      if (method === 'GET' && path === '/sessions/archived') {
        return json(200, await adapter.listArchivedSessions());
      }

      const sessionRenameMatch = /^\/sessions\/([^/]+)\/rename$/.exec(path);
      if (method === 'POST' && sessionRenameMatch) {
        const sessionId = decodeSegment(sessionRenameMatch[1]);
        const body = parseJsonObject(request.body, true);
        const title = normalizeSessionTitle(body.title);
        return json(200, await adapter.renameSession(sessionId, title));
      }

      const sessionArchiveMatch = /^\/sessions\/([^/]+)\/(archive|unarchive)$/.exec(path);
      if (method === 'POST' && sessionArchiveMatch) {
        const sessionId = decodeSegment(sessionArchiveMatch[1]);
        if (sessionArchiveMatch[2] === 'archive') {
          return json(200, await adapter.archiveSession(sessionId));
        }
        return json(200, await adapter.unarchiveSession(sessionId));
      }

      const sessionForkMatch = /^\/sessions\/([^/]+)\/fork$/.exec(path);
      if (method === 'POST' && sessionForkMatch) {
        const sessionId = decodeSegment(sessionForkMatch[1]);
        const body = parseJsonObject(request.body, false);
        if (body.atSeq !== undefined && (!Number.isSafeInteger(body.atSeq) || body.atSeq < 0)) {
          throw new ApiError('invalid_request', 'Field atSeq must be a non-negative safe integer');
        }
        return json(200, await adapter.forkSession(sessionId, body.atSeq));
      }

      const approvalPolicyMatch = /^\/sessions\/([^/]+)\/approval-policy$/.exec(path);
      if ((method === 'GET' || method === 'PUT') && approvalPolicyMatch) {
        const sessionId = decodeSegment(approvalPolicyMatch[1]);
        if (method === 'GET') {
          return json(200, await adapter.getApprovalPolicy(sessionId));
        }
        const body = parseJsonObject(request.body, true);
        if (body.policy !== 'ask' && body.policy !== 'never') {
          throw new ApiError('invalid_request', 'Field policy must be "ask" or "never"');
        }
        return json(200, await adapter.setApprovalPolicy(sessionId, body.policy));
      }

      const sessionModelMatch = /^\/sessions\/([^/]+)\/model$/.exec(path);
      if ((method === 'GET' || method === 'PUT') && sessionModelMatch) {
        const sessionId = decodeSegment(sessionModelMatch[1]);
        if (method === 'GET') {
          return json(200, await adapter.getSessionModel(sessionId));
        }
        const body = parseJsonObject(request.body, true);
        const selection = normalizeModelSelection(body);
        return json(200, await adapter.selectSessionModel(sessionId, selection));
      }

      const sessionQueueMatch = /^\/sessions\/([^/]+)\/queue$/.exec(path);
      if (method === 'GET' && sessionQueueMatch) {
        const sessionId = decodeSegment(sessionQueueMatch[1]);
        return json(200, await adapter.listSessionQueue(sessionId));
      }

      const sessionQueueItemMatch = /^\/sessions\/([^/]+)\/queue\/([^/]+)$/.exec(path);
      if (method === 'PATCH' && sessionQueueItemMatch) {
        const sessionId = decodeSegment(sessionQueueItemMatch[1]);
        const itemId = decodeSegment(sessionQueueItemMatch[2]);
        const body = parseJsonObject(request.body, true);
        normalizeQueueAction(body);
        return json(200, await adapter.updateSessionQueue(sessionId, itemId, body));
      }

      const sessionSkillsMatch = /^\/sessions\/([^/]+)\/skills$/.exec(path);
      if (method === 'GET' && sessionSkillsMatch) {
        const sessionId = decodeSegment(sessionSkillsMatch[1]);
        return json(200, await adapter.getSessionSkills(sessionId));
      }

      const sessionTodosMatch = /^\/sessions\/([^/]+)\/todos$/.exec(path);
      if (method === 'GET' && sessionTodosMatch) {
        const sessionId = decodeSegment(sessionTodosMatch[1]);
        return json(200, await adapter.getSessionTodos(sessionId));
      }

      const sessionTeamMatch = /^\/sessions\/([^/]+)\/team$/.exec(path);
      if (method === 'GET' && sessionTeamMatch) {
        const sessionId = decodeSegment(sessionTeamMatch[1]);
        return json(200, await adapter.getSessionTeam(sessionId));
      }

      const sessionTrajectoryMatch = /^\/sessions\/([^/]+)\/trajectory$/.exec(path);
      if (method === 'GET' && sessionTrajectoryMatch) {
        const sessionId = decodeSegment(sessionTrajectoryMatch[1]);
        return json(
          200,
          await adapter.getSessionTrajectory(sessionId, {
            limit: query.get('limit') ?? undefined,
            beforeSeq: query.get('beforeSeq') ?? undefined,
          }),
        );
      }

      const sessionJobsMatch = /^\/sessions\/([^/]+)\/jobs$/.exec(path);
      if (method === 'GET' && sessionJobsMatch) {
        const sessionId = decodeSegment(sessionJobsMatch[1]);
        return json(200, await adapter.getSessionJobs(sessionId));
      }

      const sessionContextMatch = /^\/sessions\/([^/]+)\/context$/.exec(path);
      if (method === 'GET' && sessionContextMatch) {
        const sessionId = decodeSegment(sessionContextMatch[1]);
        return json(200, await adapter.getSessionContext(sessionId));
      }

      const promptMatch = /^\/sessions\/([^/]+)\/prompt$/.exec(path);
      if (method === 'POST' && promptMatch) {
        const sessionId = decodeSegment(promptMatch[1]);
        const body = parseJsonObject(request.body, true);
        // Text may be empty when at least one attachment is referenced.
        const text = optionalString(body.text, 'text', { max: MAX_PROMPT_CHARS }) ?? '';
        const attachmentIds = parseAttachmentIds(body.attachmentIds);
        if (attachmentIds.length > config.attachments.maxPerPrompt) {
          throw new ApiError(
            'invalid_request',
            `At most ${config.attachments.maxPerPrompt} attachments are allowed per prompt`,
          );
        }
        if (text.trim() === '' && attachmentIds.length === 0) {
          throw new ApiError('invalid_request', 'Field text must not be empty unless attachmentIds are present');
        }

        /** @type {Array<Record<string, any>>} */
        let pending = [];
        if (attachmentIds.length > 0) {
          if (!attachments) {
            throw new ApiError('unsupported', 'Attachments are not available in this host process');
          }
          pending = attachments.resolveForPrompt({ attachmentIds, sessionId, deviceId });
        }
        const content = buildPromptContent(text, pending);
        const result = await adapter.prompt(sessionId, content);
        attachments?.consume(pending);
        return json(200, result);
      }

      const attachmentUploadMatch = /^\/sessions\/([^/]+)\/attachments$/.exec(path);
      if (method === 'POST' && attachmentUploadMatch) {
        const sessionId = decodeSegment(attachmentUploadMatch[1]);
        const body = parseJsonObject(request.body, true);
        const name = optionalString(body.name, 'name', { required: true, max: MAX_ATTACHMENT_NAME_CHARS });
        if (name.trim() === '') throw new ApiError('invalid_request', 'Field name must not be empty');
        const mimeType = parseMimeType(body.mimeType);
        const dataBase64 = optionalString(body.dataBase64, 'dataBase64', {
          required: true,
          max: config.http.maxBodyBytes,
        });
        if (dataBase64.length > config.attachments.maxBase64Bytes) {
          throw new ApiError(
            'invalid_request',
            `Field dataBase64 exceeds the ${Math.floor(config.attachments.maxBase64Bytes / (1024 * 1024))} MB encoded limit`,
          );
        }
        const { data, canonical } = decodeBase64Attachment(dataBase64, {
          maxDecodedBytes: config.attachments.maxBytes,
        });
        if (!attachments) {
          throw new ApiError('unsupported', 'Attachments are not available in this host process');
        }
        const kind = kindForMimeType(mimeType);
        // Refuse over-capacity uploads *before* persisting anything through DSH.
        attachments.assertCapacity({ kind, dataBase64: kind === 'image' ? canonical : null });

        if (typeof adapter.sessionAccess !== 'function') {
          if (!(await adapter.sessionExists(sessionId))) {
            throw new ApiError('not_found', 'Session not found');
          }
        } else {
          const access = await adapter.sessionAccess(sessionId);
          if (!access?.exists) throw new ApiError('not_found', 'Session not found');
          if (access?.internal) {
            // QA defect O3: subagent/teammate sessions cannot receive uploads;
            // answer a documented 4xx instead of leaking a DSH staging 500.
            throw new ApiError(
              'invalid_request',
              'Attachments cannot be uploaded for subagent/internal sessions; open the parent session instead',
            );
          }
        }
        const uploaded = await adapter.uploadAttachment({
          sessionId,
          deviceId,
          name: name.trim(),
          mimeType,
          kind,
          data,
          dataBase64: canonical,
        });
        const record = attachments.add({
          kind,
          name: name.trim(),
          mimeType,
          bytes: data.length,
          sessionId,
          deviceId,
          ref: uploaded?.ref ?? null,
          receiptId: uploaded?.receiptId ?? null,
          dataBase64: kind === 'image' ? canonical : null,
        });
        return json(200, { attachment: attachments.publicView(record) });
      }

      const attachmentReadMatch = /^\/sessions\/([^/]+)\/attachments\/([^/]+)$/.exec(path);
      if (method === 'GET' && attachmentReadMatch) {
        const sessionId = decodeSegment(attachmentReadMatch[1]);
        const attachmentId = decodeSegment(attachmentReadMatch[2]);
        // The adapter derives the ref from this session's messages and returns a
        // raw-response descriptor: {status, headers, raw: Uint8Array|AsyncIterable}.
        return await adapter.readAttachment(sessionId, attachmentId);
      }

      // Read-only workspace file browser under the session cwd. `path` is always
      // workspace-relative and validated/safely resolved in the adapter.
      const sessionFilesMatch = /^\/sessions\/([^/]+)\/files$/.exec(path);
      if (method === 'GET' && sessionFilesMatch) {
        const sessionId = decodeSegment(sessionFilesMatch[1]);
        return json(200, await adapter.listSessionFiles(sessionId, query.get('path') ?? ''));
      }

      const sessionFileContentMatch = /^\/sessions\/([^/]+)\/files\/content$/.exec(path);
      if (method === 'GET' && sessionFileContentMatch) {
        const sessionId = decodeSegment(sessionFileContentMatch[1]);
        return json(200, await adapter.readSessionFileContent(sessionId, query.get('path') ?? ''));
      }

      const sessionFileRawMatch = /^\/sessions\/([^/]+)\/files\/raw$/.exec(path);
      if (method === 'GET' && sessionFileRawMatch) {
        const sessionId = decodeSegment(sessionFileRawMatch[1]);
        return await adapter.readSessionFileRaw(sessionId, query.get('path') ?? '');
      }

      const interruptMatch = /^\/sessions\/([^/]+)\/interrupt$/.exec(path);
      if (method === 'POST' && interruptMatch) {
        const sessionId = decodeSegment(interruptMatch[1]);
        return json(200, await adapter.interrupt(sessionId));
      }

      const goalMatch = /^\/sessions\/([^/]+)\/goal$/.exec(path);
      if (method === 'GET' && goalMatch) {
        const sessionId = decodeSegment(goalMatch[1]);
        return json(200, await adapter.getSessionGoal(sessionId));
      }
      if (method === 'POST' && goalMatch) {
        const sessionId = decodeSegment(goalMatch[1]);
        const body = parseJsonObject(request.body, true);
        const objective = optionalString(body.objective, 'objective', {
          required: true,
          max: 4000,
        });
        if (objective.trim() === '') {
          throw new ApiError('invalid_request', 'Field objective must not be empty');
        }
        const maxGoalRounds = optionalPositiveInt(body.maxGoalRounds, 'maxGoalRounds', {
          max: 10_000,
        });
        return json(
          200,
          await adapter.createSessionGoal(sessionId, {
            objective: objective.trim(),
            ...(maxGoalRounds === undefined ? {} : { maxGoalRounds }),
          }),
        );
      }

      const goalResumeMatch = /^\/sessions\/([^/]+)\/goal\/resume$/.exec(path);
      if (method === 'POST' && goalResumeMatch) {
        const sessionId = decodeSegment(goalResumeMatch[1]);
        return json(200, await adapter.resumeSessionGoal(sessionId));
      }

      const goalPauseMatch = /^\/sessions\/([^/]+)\/goal\/pause$/.exec(path);
      if (method === 'POST' && goalPauseMatch) {
        const sessionId = decodeSegment(goalPauseMatch[1]);
        return json(200, await adapter.pauseSessionGoal(sessionId));
      }

      const goalCompleteMatch = /^\/sessions\/([^/]+)\/goal\/complete$/.exec(path);
      if (method === 'POST' && goalCompleteMatch) {
        const sessionId = decodeSegment(goalCompleteMatch[1]);
        // Body is optional for compatibility; when present, id/revision form an
        // optimistic-concurrency guard mirroring the goal edit/clear routes.
        const body = parseJsonObject(request.body, false);
        const id = optionalString(body.id, 'id', { max: 200 });
        const revision = optionalPositiveInt(body.revision, 'revision', { max: 1_000_000 });
        return json(
          200,
          await adapter.completeSessionGoal(sessionId, {
            ...(id === undefined ? {} : { id }),
            ...(revision === undefined ? {} : { revision }),
          }),
        );
      }

      const goalEditMatch = /^\/sessions\/([^/]+)\/goal\/edit$/.exec(path);
      if (method === 'POST' && goalEditMatch) {
        const sessionId = decodeSegment(goalEditMatch[1]);
        const body = parseJsonObject(request.body, true);
        const objective = optionalString(body.objective, 'objective', { max: 4000 });
        if (objective !== undefined && objective.trim() === '') {
          throw new ApiError('invalid_request', 'Field objective must not be empty');
        }
        const maxGoalRounds = optionalPositiveInt(body.maxGoalRounds, 'maxGoalRounds', { max: 10_000 });
        if (objective === undefined && maxGoalRounds === undefined) {
          throw new ApiError('invalid_request', 'Field objective and/or maxGoalRounds is required');
        }
        return json(
          200,
          await adapter.editSessionGoal(sessionId, {
            ...(objective === undefined ? {} : { objective: objective.trim() }),
            ...(maxGoalRounds === undefined ? {} : { maxGoalRounds }),
          }),
        );
      }

      const goalClearMatch = /^\/sessions\/([^/]+)\/goal\/clear$/.exec(path);
      if (method === 'POST' && goalClearMatch) {
        const sessionId = decodeSegment(goalClearMatch[1]);
        return json(200, await adapter.clearSessionGoal(sessionId));
      }

      const sessionMatch = /^\/sessions\/([^/]+)$/.exec(path);
      if (method === 'GET' && sessionMatch) {
        const sessionId = decodeSegment(sessionMatch[1]);
        const detail = await adapter.getSession(sessionId, {
          limit: query.get('limit'),
          before: query.get('before'),
        });
        return etagJson(request, detail);
      }

      if (method === 'GET' && path === '/approvals') {
        // Read-only mirror: {approvals, supported:true, readOnly:true}.
        return json(200, await adapter.listApprovals());
      }

      const approvalMatch = /^\/approvals\/([^/]+)$/.exec(path);
      if (method === 'POST' && approvalMatch) {
        const approvalId = decodeSegment(approvalMatch[1]);
        const body = parseJsonObject(request.body, true);
        const decision = optionalString(body.decision, 'decision', { required: true, max: 8 });
        if (decision !== 'allow' && decision !== 'deny') {
          throw new ApiError('invalid_request', 'Field decision must be "allow" or "deny"');
        }
        const note = optionalString(body.note, 'note', { max: 2000 });
        return json(200, await adapter.resolveApproval(approvalId, decision, note));
      }

      if (method === 'GET' && path === '/questions') {
        // Mirror envelope: {questions, supported:true, readOnly} (readOnly is
        // false when questions.mobileAnswer is enabled).
        return json(200, await adapter.listQuestions());
      }

      const questionMatch = /^\/questions\/([^/]+)$/.exec(path);
      if (method === 'POST' && questionMatch) {
        const questionId = decodeSegment(questionMatch[1]);
        const body = parseJsonObject(request.body, true);
        const answer = optionalString(body.answer, 'answer', { required: true, max: 4000 });
        const optionIndexValue = body.optionIndex;
        let optionIndex;
        if (optionIndexValue !== undefined && optionIndexValue !== null) {
          if (!Number.isSafeInteger(optionIndexValue) || optionIndexValue < 0) {
            throw new ApiError('invalid_request', 'Field optionIndex must be a non-negative integer');
          }
          optionIndex = optionIndexValue;
        }
        return json(200, await adapter.answerQuestion(questionId, answer, optionIndex));
      }

      if (method === 'GET' && path === '/events') {
        return { status: 200, sse: true, since: query.get('since') ?? undefined, deviceId };
      }

      throw new ApiError('not_found', `Unknown endpoint: ${method} ${path}`);
    } catch (error) {
      if (!(error instanceof ApiError)) {
        const code = /** @type {any} */ (error)?.code;
        if (typeof code === 'string' && code.startsWith('session/')) {
          return errorResponse(toApiError(error));
        }
        logger.error?.(
          `[dsh-mobile-host] unhandled route error: ${String(/** @type {any} */ (error)?.stack ?? error)}`,
        );
      }
      return errorResponse(error);
    }
  }

  /** @param {string} raw */
  function decodeSegment(raw) {
    let decoded;
    try {
      decoded = decodeURIComponent(raw);
    } catch {
      throw new ApiError('not_found', 'Unknown endpoint');
    }
    if (!SEGMENT.test(decoded)) throw new ApiError('not_found', 'Unknown endpoint');
    return decoded;
  }

  return { handle };
}
