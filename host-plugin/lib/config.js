/**
 * Configuration defaults, deep merge, and validation for the DSH Mobile host.
 *
 * Tunable values live here so a profile patch (`cordis.patch.yml`) can adjust
 * them without touching the code. Security-critical values (the bind address)
 * are validated, not merely defaulted.
 */

import os from 'node:os';
import path from 'node:path';

/** API version reported by /health and pair/complete. */
export const API_VERSION = 1;

/** Plugin package version (kept in sync with package.json). */
export const PLUGIN_VERSION = '0.3.13';

/** Only loopback addresses may be bound; a Tailscale Serve proxy terminates TLS. */
const LOOPBACK_HOSTS = new Set(['127.0.0.1', 'localhost', '::1']);

/** @typedef {ReturnType<typeof resolveConfig>} ResolvedConfig */

export const DEFAULT_CONFIG = Object.freeze({
  enabled: true,
  /** Bind address. Loopback only — enforced by resolveConfig. */
  host: '127.0.0.1',
  /** TCP port that Tailscale Serve proxies to. */
  port: 3543,
  apiVersion: API_VERSION,
  version: PLUGIN_VERSION,
  /** Display name in GET /host and the pairing QR payload. */
  hostName: os.hostname(),
  /**
   * Public base URL the phone should use (e.g. https://pc.tailnet.ts.net).
   * Only used to build `qrPayload`; empty string omits `base` from the payload.
   */
  publicBaseUrl: '',
  /** Directory for persisted host state (hashed device tokens only). */
  stateDir: path.join(os.homedir(), '.dsh-mobile'),
  /** Pairing code lifetime and anti-bruteforce policy. */
  pairing: Object.freeze({
    codeTtlMs: 5 * 60 * 1000,
    codeLength: 6,
    maxFailedAttemptsPerMinute: 5,
    rateWindowMs: 60 * 1000,
    maxDevices: 32,
    /** Trust Tailscale Serve identity headers for one-tap pairing. */
    tailscaleAutoPair: true,
    /** Optional Tailscale login allowlist (case-insensitive); '' = any tailnet user. */
    tailscaleLogin: '',
  }),
  /** Server-sent event stream behaviour. */
  sse: Object.freeze({
    heartbeatMs: 20 * 1000,
    bufferSize: 500,
  }),
  sessions: Object.freeze({
    defaultLimit: 100,
    maxLimit: 500,
    previewLimit: 20,
    previewChars: 160,
    /** Hide subagent/teammate ("internal") sessions from the phone by default. */
    hideInternal: true,
    /** Auto-hide blank/Untitled sessions older than this (0 disables). */
    hideBlankAfterMs: 60 * 60 * 1000,
    /** Persisted hidden-id file; '' = next to stateFile (hidden-sessions.json). */
    hiddenFile: '',
    /** Persisted session-list cache path; '' = default tmp file, false = disabled. */
    cacheFile: '',
  }),
  /** Approval answering policy (desktop remains an answerer; first answer wins). */
  approvals: Object.freeze({
    /** Allow the phone to answer pending approvals (Allow/Deny). */
    mobileAnswer: true,
  }),
  /** User-question answering policy (first whole answer wins; batches must complete). */
  questions: Object.freeze({
    /** Allow the phone to answer pending user questions. */
    mobileAnswer: true,
  }),
  /** Mobile API v1 "Attachments (v0.2.2)" policy. */
  attachments: Object.freeze({
    /** Decoded byte limit for one attachment (contract: ~10 MB). */
    maxBytes: 10 * 1024 * 1024,
    /** Requested base64 limit for one upload (contract: 14 MB body). */
    maxBase64Bytes: 14 * 1024 * 1024,
    /** Pending attachment lifetime (contract: 24 h or consumed by a prompt). */
    ttlMs: 24 * 60 * 60 * 1000,
    /** Attachments accepted in one prompt. */
    maxPerPrompt: 8,
    /** Pending (not yet prompted) attachments kept in host memory. */
    maxPendingCount: 32,
    /** Upper bound for pending image bytes (image prompts re-send their bytes). */
    maxPendingBytes: 64 * 1024 * 1024,
  }),
  /**
   * Local artifact roots. Absolute Markdown image paths are served only when
   * they resolve (after symlink resolution) inside one of these directories.
   * Relative paths are always confined to the session cwd.
   */
  artifacts: Object.freeze({
    roots: Object.freeze([]),
    /**
     * Opt-in remote image proxy for Markdown http(s) links. Disabled by default:
     * the host never fetches remote URLs unless the operator enables it.
     */
    remote: Object.freeze({
      enabled: false,
      maxBytes: 10 * 1024 * 1024,
      timeoutMs: 8000,
      ttlMs: 10 * 60 * 1000,
      cacheEntries: 32,
    }),
  }),
  http: Object.freeze({
    // Large enough for one 14 MB base64 attachment body plus JSON overhead.
    maxBodyBytes: 15 * 1024 * 1024,
    requestTimeoutMs: 30 * 1000,
  }),
  /** Read-only usage sources. Empty values auto-discover the DSH home. */
  usage: Object.freeze({
    projectionsDir: '',
  }),
});

/** @param {Record<string, unknown>} base @param {Record<string, unknown>} override */
function mergeObjects(base, override) {
  const out = { ...base };
  for (const [key, value] of Object.entries(override)) {
    if (value === undefined) continue;
    const current = out[key];
    if (
      value !== null &&
      typeof value === 'object' &&
      !Array.isArray(value) &&
      current !== null &&
      typeof current === 'object' &&
      !Array.isArray(current)
    ) {
      out[key] = mergeObjects(current, value);
    } else {
      out[key] = value;
    }
  }
  return out;
}

/** @param {unknown} value @param {number} fallback */
function positiveInt(value, fallback) {
  const parsed = typeof value === 'number' ? value : Number.parseInt(String(value ?? ''), 10);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback;
}

/** @param {unknown} value @param {number} min @param {number} max @param {number} fallback */
function intInRange(value, min, max, fallback) {
  const parsed = typeof value === 'number' ? value : Number.parseInt(String(value ?? ''), 10);
  return Number.isSafeInteger(parsed) && parsed >= min && parsed <= max ? parsed : fallback;
}

/** @param {unknown} value @param {number} fallback */
function nonNegativeInt(value, fallback) {
  const parsed = typeof value === 'number' ? value : Number.parseInt(String(value ?? ''), 10);
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : fallback;
}

/** @param {unknown} value */
function nonEmptyString(value) {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : undefined;
}

/**
 * Normalize `artifacts.roots` to unique absolute, normalized directories.
 * Invalid entries fail fast: serving local files without an absolute root policy
 * is a security decision, not something to silently guess.
 *
 * @param {unknown} value
 * @returns {string[]}
 */
function normalizeArtifactRoots(value) {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value)) {
    throw new Error('[dsh-mobile-host] artifacts.roots must be an array of absolute directory paths');
  }
  /** @type {string[]} */
  const roots = [];
  const seen = new Set();
  for (const entry of value) {
    if (typeof entry !== 'string') {
      throw new Error('[dsh-mobile-host] artifacts.roots entries must be strings');
    }
    const trimmed = entry.trim();
    if (trimmed === '') continue;
    if (!path.isAbsolute(trimmed)) {
      throw new Error(`[dsh-mobile-host] artifacts.roots entry must be an absolute path: ${JSON.stringify(trimmed)}`);
    }
    if (trimmed.startsWith('\\\\') || trimmed.startsWith('//') || /^\\\\[?.]\\/u.test(trimmed)) {
      throw new Error(`[dsh-mobile-host] artifacts.roots must not be UNC/device paths: ${JSON.stringify(trimmed)}`);
    }
    const normalized = path.normalize(trimmed);
    const key = process.platform === 'win32' ? normalized.toLowerCase() : normalized;
    if (seen.has(key)) continue;
    seen.add(key);
    roots.push(normalized);
  }
  return roots;
}

/**
 * Merge user config over defaults and validate security-critical fields.
 *
 * @param {Record<string, unknown>} [raw]
 * @returns {Readonly<ResolvedConfig>}
 */
export function resolveConfig(raw = {}) {
  const merged = mergeObjects(DEFAULT_CONFIG, raw);

  if (!LOOPBACK_HOSTS.has(merged.host)) {
    throw new Error(
      `[dsh-mobile-host] refusing to bind to ${JSON.stringify(merged.host)}: ` +
        'the mobile API must stay on a loopback address so Tailscale Serve can terminate TLS',
    );
  }

  const port = intInRange(merged.port, 0, 65_535, DEFAULT_CONFIG.port);
  const publicBaseUrl = nonEmptyString(merged.publicBaseUrl) ?? '';
  if (publicBaseUrl !== '' && !/^https?:\/\//i.test(publicBaseUrl)) {
    throw new Error('[dsh-mobile-host] publicBaseUrl must start with http:// or https://');
  }
  if (publicBaseUrl.endsWith('/')) {
    throw new Error('[dsh-mobile-host] publicBaseUrl must not end with a slash');
  }

  const stateDir = nonEmptyString(merged.stateDir) ?? DEFAULT_CONFIG.stateDir;
  const stateFile = nonEmptyString(merged.stateFile) ?? path.join(stateDir, 'host-state.json');
  const codeLength = positiveInt(merged.pairing?.codeLength, DEFAULT_CONFIG.pairing.codeLength);
  if (codeLength !== 6) {
    throw new Error('[dsh-mobile-host] pairing.codeLength must be 6 (Mobile API v1 contract)');
  }

  /** @type {ResolvedConfig} */
  const resolved = {
    enabled: merged.enabled !== false,
    host: merged.host,
    port,
    apiVersion: positiveInt(merged.apiVersion, API_VERSION),
    version: nonEmptyString(merged.version) ?? PLUGIN_VERSION,
    hostName: nonEmptyString(merged.hostName) ?? os.hostname(),
    publicBaseUrl,
    stateDir,
    stateFile,
    pairing: {
      codeTtlMs: positiveInt(merged.pairing?.codeTtlMs, DEFAULT_CONFIG.pairing.codeTtlMs),
      codeLength,
      maxFailedAttemptsPerMinute: positiveInt(
        merged.pairing?.maxFailedAttemptsPerMinute,
        DEFAULT_CONFIG.pairing.maxFailedAttemptsPerMinute,
      ),
      rateWindowMs: positiveInt(merged.pairing?.rateWindowMs, DEFAULT_CONFIG.pairing.rateWindowMs),
      maxDevices: positiveInt(merged.pairing?.maxDevices, DEFAULT_CONFIG.pairing.maxDevices),
      // Explicit false disables the Tailscale one-tap path; anything else keeps
      // the default (like the internal-session privacy switch).
      tailscaleAutoPair: merged.pairing?.tailscaleAutoPair !== false,
      tailscaleLogin: (nonEmptyString(merged.pairing?.tailscaleLogin) ?? '').trim().slice(0, 320),
    },
    sse: {
      heartbeatMs: positiveInt(merged.sse?.heartbeatMs, DEFAULT_CONFIG.sse.heartbeatMs),
      bufferSize: positiveInt(merged.sse?.bufferSize, DEFAULT_CONFIG.sse.bufferSize),
    },
    sessions: {
      defaultLimit: positiveInt(merged.sessions?.defaultLimit, DEFAULT_CONFIG.sessions.defaultLimit),
      maxLimit: positiveInt(merged.sessions?.maxLimit, DEFAULT_CONFIG.sessions.maxLimit),
      previewLimit: intInRange(merged.sessions?.previewLimit, 0, 500, DEFAULT_CONFIG.sessions.previewLimit),
      previewChars: positiveInt(merged.sessions?.previewChars, DEFAULT_CONFIG.sessions.previewChars),
      // Only an explicit `false` disables the filter; any other value keeps the
      // privacy-preserving default so a typo cannot expose subagent sessions.
      hideInternal: merged.sessions?.hideInternal !== false,
      // 0 disables auto-hiding; any invalid value falls back to the 1 h default.
      hideBlankAfterMs: nonNegativeInt(merged.sessions?.hideBlankAfterMs, DEFAULT_CONFIG.sessions.hideBlankAfterMs),
      // The curated hidden list lives next to the pairing state unless a path is given.
      hiddenFile:
        nonEmptyString(merged.sessions?.hiddenFile) ??
        path.join(path.dirname(stateFile), 'hidden-sessions.json'),
      cacheFile:
        merged.sessions?.cacheFile === false
          ? false
          : typeof merged.sessions?.cacheFile === 'string'
            ? merged.sessions.cacheFile
            : DEFAULT_CONFIG.sessions.cacheFile,
    },
    approvals: {
      // Only an explicit `false` disables phone answering; default is ON.
      mobileAnswer: merged.approvals?.mobileAnswer !== false,
    },
    questions: {
      // Only an explicit `false` disables phone answering; default is ON.
      mobileAnswer: merged.questions?.mobileAnswer !== false,
    },
    attachments: {
      maxBytes: positiveInt(merged.attachments?.maxBytes, DEFAULT_CONFIG.attachments.maxBytes),
      maxBase64Bytes: positiveInt(merged.attachments?.maxBase64Bytes, DEFAULT_CONFIG.attachments.maxBase64Bytes),
      ttlMs: positiveInt(merged.attachments?.ttlMs, DEFAULT_CONFIG.attachments.ttlMs),
      maxPerPrompt: positiveInt(merged.attachments?.maxPerPrompt, DEFAULT_CONFIG.attachments.maxPerPrompt),
      maxPendingCount: positiveInt(merged.attachments?.maxPendingCount, DEFAULT_CONFIG.attachments.maxPendingCount),
      maxPendingBytes: positiveInt(merged.attachments?.maxPendingBytes, DEFAULT_CONFIG.attachments.maxPendingBytes),
    },
    artifacts: {
      roots: normalizeArtifactRoots(merged.artifacts?.roots),
      remote: {
        // Only an explicit `true` opens the remote proxy; a typo cannot enable it.
        enabled: merged.artifacts?.remote?.enabled === true,
        maxBytes: intInRange(
          merged.artifacts?.remote?.maxBytes,
          1024,
          25 * 1024 * 1024,
          DEFAULT_CONFIG.artifacts.remote.maxBytes,
        ),
        timeoutMs: intInRange(
          merged.artifacts?.remote?.timeoutMs,
          1000,
          30_000,
          DEFAULT_CONFIG.artifacts.remote.timeoutMs,
        ),
        ttlMs: intInRange(
          merged.artifacts?.remote?.ttlMs,
          60_000,
          3_600_000,
          DEFAULT_CONFIG.artifacts.remote.ttlMs,
        ),
        cacheEntries: intInRange(
          merged.artifacts?.remote?.cacheEntries,
          1,
          64,
          DEFAULT_CONFIG.artifacts.remote.cacheEntries,
        ),
      },
    },
    http: {
      maxBodyBytes: positiveInt(merged.http?.maxBodyBytes, DEFAULT_CONFIG.http.maxBodyBytes),
      requestTimeoutMs: positiveInt(merged.http?.requestTimeoutMs, DEFAULT_CONFIG.http.requestTimeoutMs),
    },
    usage: {
      projectionsDir: nonEmptyString(merged.usage?.projectionsDir) ?? '',
    },
  };

  return deepFreeze(resolved);
}

/** @param {unknown} value */
export function deepFreeze(value) {
  if (value !== null && typeof value === 'object' && !Object.isFrozen(value)) {
    Object.freeze(value);
    for (const child of Object.values(value)) deepFreeze(child);
  }
  return value;
}
