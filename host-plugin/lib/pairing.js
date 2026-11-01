/**
 * Pairing lifecycle for DSH Mobile API v1.
 *
 * - 6-digit one-time codes with a 5 minute lifetime (created on the desktop,
 *   via the loopback-only `POST /mobile/v1/pair/new`).
 * - Failed `pair/complete` attempts are rate limited per client IP.
 * - Successful completion mints a 32-byte base64url device token; only its
 *   SHA-256 hash plus device metadata is retained.
 *
 * This module is pure Node built-ins and has no DSH dependency, so the whole
 * lifecycle is unit-testable offline.
 */

import crypto from 'node:crypto';
import { readFileSync } from 'node:fs';
import { unlink } from 'node:fs/promises';
import { ApiError } from './errors.js';

/** Bytes of entropy in a device token (32 bytes → 43 base64url chars). */
export const TOKEN_BYTES = 32;
/** Bytes of entropy in a recovery code (32 bytes → 52 base32 chars / 256 bits). */
export const RECOVERY_CODE_BYTES = 32;
/** Recovery codes have no expiry; single-use and rotated on consumption. */
const RECOVERY_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
const RECOVERY_CODE_CHARS = Math.ceil((RECOVERY_CODE_BYTES * 8) / 5);

/** Length of the numeric pairing code. */
export const PAIRING_CODE_LENGTH = 6;

/** Generate a fresh device token: random 32-byte base64url value. */
export function generateToken() {
  return crypto.randomBytes(TOKEN_BYTES).toString('base64url');
}

/** SHA-256 hex hash of a device token (all the host ever stores). */
export function hashToken(token) {
  return crypto.createHash('sha256').update(String(token), 'utf8').digest('hex');
}

/** Validate a persisted recovery-state object `{hash, createdAt}`. */
function normalizeRecoveryState(value) {
  if (
    value &&
    typeof value === 'object' &&
    typeof value.hash === 'string' &&
    /^[0-9a-f]{64}$/.test(value.hash) &&
    typeof value.createdAt === 'number'
  ) {
    return { hash: value.hash, createdAt: value.createdAt };
  }
  return null;
}

/** Base32 (RFC 4648 alphabet, no padding) encoding of 32 random bytes. */
export function generateRecoveryCode() {
  const bytes = crypto.randomBytes(RECOVERY_CODE_BYTES);
  let bits = '';
  for (const byte of bytes) bits += byte.toString(2).padStart(8, '0');
  let compact = '';
  for (let index = 0; index < bits.length; index += 5) {
    compact += RECOVERY_ALPHABET[Number.parseInt(bits.slice(index, index + 5).padEnd(5, '0'), 2)];
  }
  return formatRecoveryCode(compact);
}

/** Group a compact recovery code into 4-char display groups (`ABCD-EFGH-…`). */
export function formatRecoveryCode(compact) {
  return String(compact ?? '').replace(/(.{4})(?=.)/g, '$1-');
}

/**
 * Normalize user input for a recovery code: trim, strip spaces/dashes, uppercase,
 * then require the exact base32 charset/length. Returns undefined when invalid.
 * @param {unknown} value
 */
export function canonicalRecoveryCode(value) {
  if (typeof value !== 'string') return undefined;
  const compact = value.replace(/[\s-]/g, '').toUpperCase();
  if (compact.length !== RECOVERY_CODE_CHARS) return undefined;
  for (const char of compact) {
    if (!RECOVERY_ALPHABET.includes(char)) return undefined;
  }
  return compact;
}

/** Generate a zero-padded numeric pairing code. */
export function generatePairingCode(length = PAIRING_CODE_LENGTH) {
  const code = String(crypto.randomInt(0, 10 ** length)).padStart(length, '0');
  return code;
}

/** Generate a stable-looking opaque device id (`dev_...`). */
export function generateDeviceId() {
  return `dev_${crypto.randomBytes(10).toString('base64url')}`;
}

/** Trim, strip control characters, collapse whitespace, and cap a string. */
export function sanitizeText(value, maxLength) {
  if (typeof value !== 'string') return undefined;
  const cleaned = value.replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim();
  if (cleaned === '') return undefined;
  return cleaned.length > maxLength ? cleaned.slice(0, maxLength) : cleaned;
}

/**
 * Fixed-window-per-key failure counter used by pair/complete.
 * Only failed attempts are recorded, so a legitimate pairing never consumes
 * the caller's budget.
 */
export class RateLimiter {
  /**
   * @param {{ limit: number, windowMs: number, now?: () => number }} options
   */
  constructor({ limit, windowMs, now = () => Date.now() }) {
    this.limit = limit;
    this.windowMs = windowMs;
    this.now = now;
    /** @type {Map<string, number[]>} */
    this.attempts = new Map();
  }

  /** @param {string} key */
  #recent(key) {
    const now = this.now();
    const cutoff = now - this.windowMs;
    const timestamps = (this.attempts.get(key) ?? []).filter((time) => time > cutoff);
    if (timestamps.length === 0) this.attempts.delete(key);
    else this.attempts.set(key, timestamps);
    return timestamps;
  }

  /**
   * Whether another failure from `key` is currently allowed.
   * @param {string} key
   */
  allows(key) {
    return this.#recent(key).length < this.limit;
  }

  /**
   * How long until this key may attempt again, in ms (0 when allowed).
   * @param {string} key
   */
  retryAfterMs(key) {
    const timestamps = this.#recent(key);
    if (timestamps.length < this.limit) return 0;
    const oldest = timestamps[0];
    return Math.max(0, oldest + this.windowMs - this.now());
  }

  /** @param {string} key */
  recordFailure(key) {
    const timestamps = this.#recent(key);
    timestamps.push(this.now());
    this.attempts.set(key, timestamps);
    // Bound memory: drop the oldest keys once the table grows too large.
    if (this.attempts.size > 4096) {
      const oldestKey = this.attempts.keys().next().value;
      if (oldestKey !== undefined) this.attempts.delete(oldestKey);
    }
  }

  /** @param {string} key */
  reset(key) {
    this.attempts.delete(key);
  }
}

/** Public (non-secret) view of one paired device. */
export function publicDevice(device) {
  return {
    deviceId: device.deviceId,
    deviceName: device.deviceName,
    platform: device.platform,
    pairedAt: new Date(device.createdAt).toISOString(),
    lastSeenAt: device.lastSeenAt ? new Date(device.lastSeenAt).toISOString() : null,
    ...(device.publicKey ? { publicKey: device.publicKey } : {}),
  };
}

/**
 * In-memory pairing state machine with optional persistence of device hashes.
 */
export class PairingStore {
  /**
   * @param {{
   *   config: { pairing: { codeTtlMs: number, codeLength: number, maxFailedAttemptsPerMinute: number, rateWindowMs: number, maxDevices: number } },
   *   now?: () => number,
   *   randomInt?: (min: number, max: number) => number,
   *   state?: unknown,
   *   persist?: (state: { version: number, devices: any[] }) => Promise<void> | void,
   *   logger?: Pick<Console, 'warn' | 'error'>,
   * }} options
   */
  constructor({ config, now = () => Date.now(), randomInt = crypto.randomInt, state, persist, logger = console }) {
    this.config = config;
    this.now = now;
    this.randomInt = randomInt;
    this.codeTtlMs = config.pairing.codeTtlMs;
    this.codeLength = config.pairing.codeLength ?? PAIRING_CODE_LENGTH;
    this.maxDevices = config.pairing.maxDevices ?? 32;
    this.persist = persist ?? (async () => {});
    this.logger = logger;
    /** @type {Set<(deviceId: string) => void>} */
    this.revokeListeners = new Set();

    this.limiter = new RateLimiter({
      limit: config.pairing.maxFailedAttemptsPerMinute,
      windowMs: config.pairing.rateWindowMs,
      now,
    });

    /** @type {Map<string, { code: string, createdAt: number, expiresAt: number, used: boolean }>} */
    this.codes = new Map();
    /** @type {Array<Record<string, any>>} */
    this.devices = [];

    const devices = Array.isArray(/** @type {any} */ (state)?.devices) ? /** @type {any} */ (state).devices : [];
    for (const device of devices) {
      if (
        device &&
        typeof device.deviceId === 'string' &&
        typeof device.tokenHash === 'string' &&
        /^[0-9a-f]{64}$/.test(device.tokenHash) &&
        typeof device.createdAt === 'number'
      ) {
        this.devices.push({ ...device });
      }
    }

    // Long-lived single-use recovery code: only its SHA-256 hash is persisted,
    // in the MAIN state file so devices + recovery rotate in one atomic write.
    // A legacy `<stateFile>.recovery.json` sidecar is read for migration.
    this.recoveryFile = typeof config?.recoveryFile === 'string'
      ? config.recoveryFile
      : typeof config?.stateFile === 'string' && config.stateFile !== ''
        ? `${config.stateFile}.recovery.json`
        : null;
    /** @type {{ hash: string, createdAt: number } | null} */
    this.recovery = normalizeRecoveryState(/** @type {any} */ (state)?.recovery) ?? this.#readRecoveryFile();
  }

  /** Load the hashed recovery state (missing/corrupt → not configured). */
  #readRecoveryFile() {
    if (this.recoveryFile === null) return null;
    try {
      const parsed = JSON.parse(readFileSync(this.recoveryFile, 'utf8'));
      const recovery = parsed?.recovery;
      if (
        recovery &&
        typeof recovery.hash === 'string' &&
        /^[0-9a-f]{64}$/.test(recovery.hash) &&
        typeof recovery.createdAt === 'number'
      ) {
        return { hash: recovery.hash, createdAt: recovery.createdAt };
      }
    } catch {
      /* missing/corrupt sidecar = no recovery code configured */
    }
    return null;
  }

  /** Drop used or expired codes. */
  pruneCodes() {
    const now = this.now();
    for (const [code, entry] of this.codes) {
      if (entry.used || entry.expiresAt <= now) this.codes.delete(code);
    }
  }

  /**
   * Mint one one-time pairing code.
   * @returns {{ code: string, createdAt: number, expiresAt: number }}
   */
  createCode() {
    this.pruneCodes();
    // Avoid returning a code that collides with a still-live one.
    let code;
    for (let attempt = 0; attempt < 8; attempt += 1) {
      const candidate = String(this.randomInt(0, 10 ** this.codeLength)).padStart(this.codeLength, '0');
      if (!this.codes.has(candidate)) {
        code = candidate;
        break;
      }
    }
    code ??= String(this.randomInt(0, 10 ** this.codeLength)).padStart(this.codeLength, '0');
    const createdAt = this.now();
    const expiresAt = createdAt + this.codeTtlMs;
    this.codes.set(code, { code, createdAt, expiresAt, used: false });
    return { code, createdAt, expiresAt };
  }

  /** Number of currently pending codes (test/diagnostics helper). */
  pendingCodeCount() {
    this.pruneCodes();
    return this.codes.size;
  }

  /**
   * Complete pairing with a code and issue a device token.
   *
   * @param {{
   *   code: unknown,
   *   deviceName?: unknown,
   *   platform?: unknown,
   *   publicKey?: unknown,
   *   ip?: string,
   * }} request
   * @returns {{ token: string, device: Record<string, any> }}
   * @throws {ApiError} rate_limited / invalid_code
   */
  complete({ code, deviceName, platform, publicKey, ip }) {
    const ipKey = ip || 'unknown';
    if (!this.limiter.allows(ipKey)) {
      const retryAfterMs = this.limiter.retryAfterMs(ipKey);
      throw new ApiError('rate_limited', 'Too many failed pairing attempts; try again later', {
        details: { retryAfterMs },
      });
    }

    this.pruneCodes();
    const entry = typeof code === 'string' ? this.codes.get(code) : undefined;
    if (!entry || entry.used || entry.expiresAt <= this.now()) {
      this.limiter.recordFailure(ipKey);
      throw new ApiError('invalid_code', 'Pairing code is invalid, expired, or already used');
    }

    // One-time: remove immediately so a concurrent replay cannot pass.
    entry.used = true;
    this.codes.delete(entry.code);

    const { token, device } = this.#createDevice({ deviceName, platform, publicKey, ip });
    void this.#persistDevices();

    return { token, device: publicDevice(device) };
  }

  /** Mint and retain one device record (shared by pair/complete + recovery). */
  #createDevice({ deviceName, platform, publicKey, ip }) {
    const token = generateToken();
    /** @type {Record<string, any>} */
    const device = {
      deviceId: generateDeviceId(),
      tokenHash: hashToken(token),
      deviceName: sanitizeText(deviceName, 100) ?? 'Unknown device',
      platform: sanitizeText(platform, 40) ?? 'unknown',
      createdAt: this.now(),
      lastSeenAt: null,
      ...(sanitizeText(publicKey, 4096) ? { publicKey: sanitizeText(publicKey, 4096) } : {}),
      ...(ip ? { ip } : {}),
    };
    this.devices.push(device);
    if (this.devices.length > this.maxDevices) {
      this.devices.splice(0, this.devices.length - this.maxDevices);
    }
    return { token, device };
  }

  /**
   * Persist devices **and** the recovery hash in one atomic state write.
   * Normal pairing persistence is best-effort (a read-only profile must not
   * break pairing for this run); revocation/recovery call this with
   * `throwOnError` so the caller can report and roll back.
   *
   * @param {{ throwOnError?: boolean }} [options]
   */
  async #persistDevices({ throwOnError = false } = {}) {
    try {
      await this.persist({
        version: 1,
        devices: this.devices.map((device) => ({ ...device })),
        ...(this.recovery === null ? {} : { recovery: { ...this.recovery } }),
      });
      void this.#removeRecoverySidecar();
    } catch (error) {
      this.logger.warn?.(
        `[dsh-mobile-host] could not persist paired devices: ${String(/** @type {any} */ (error)?.message ?? error)}`,
      );
      if (throwOnError) throw error;
    }
  }

  /** Remove a legacy recovery sidecar once the main state file holds the hash. */
  async #removeRecoverySidecar() {
    if (this.recoveryFile === null) return;
    try {
      await unlink(this.recoveryFile);
    } catch (error) {
      if (/** @type {any} */ (error)?.code !== 'ENOENT') {
        this.logger.warn?.(
          `[dsh-mobile-host] could not remove legacy recovery sidecar: ${String(
            /** @type {any} */ (error)?.message ?? error,
          )}`,
        );
      }
    }
  }

  /** Generate one recovery code and its stored hash/creation time. */
  #makeRecovery() {
    const code = generateRecoveryCode();
    return {
      code,
      hash: hashToken(canonicalRecoveryCode(code)),
      createdAt: this.now(),
    };
  }

  /**
   * Mint a device token without a pairing code or recovery code (Tailscale
   * identity pairing). Rate limiting/identity checks happen in the router.
   * @param {{ deviceName?: unknown, platform?: unknown, publicKey?: unknown, ip?: string }} [options]
   * @returns {{ token: string, device: Record<string, any> }}
   */
  pairDevice({ deviceName, platform, publicKey, ip } = {}) {
    const { token, device } = this.#createDevice({ deviceName, platform, publicKey, ip });
    void this.#persistDevices();
    return { token, device: publicDevice(device) };
  }

  /**
   * Throw `rate_limited` when this client IP has too many recent pairing
   * failures (shared limiter with pair/complete + recovery).
   * @param {string} ip
   */
  checkPairingRateLimit(ip) {
    const ipKey = ip || 'unknown';
    if (this.limiter.allows(ipKey)) return;
    throw new ApiError('rate_limited', 'Too many failed pairing attempts; try again later', {
      details: { retryAfterMs: this.limiter.retryAfterMs(ipKey) },
    });
  }

  /** Count one failed pairing attempt from this client IP. @param {string} ip */
  recordPairingFailure(ip) {
    this.limiter.recordFailure(ip || 'unknown');
  }

  /** Redacted recovery status: configured flag and creation time, never the code. */
  getRecoveryInfo() {
    return {
      configured: this.recovery !== null,
      ...(this.recovery === null ? {} : { createdAt: new Date(this.recovery.createdAt).toISOString() }),
    };
  }

  /**
   * Create/replace the single long-lived recovery code; devices + hash are
   * persisted together in one atomic state write.
   * @returns {Promise<{ code: string, createdAt: string }>}
   */
  async createRecoveryCode() {
    const next = this.#makeRecovery();
    const previous = this.recovery;
    this.recovery = { hash: next.hash, createdAt: next.createdAt };
    try {
      await this.#persistDevices({ throwOnError: true });
    } catch (error) {
      this.recovery = previous;
      throw new ApiError('internal', 'Could not persist the recovery code', { cause: error });
    }
    return { code: next.code, createdAt: new Date(next.createdAt).toISOString() };
  }

  /**
   * Redeem a recovery code: mint a device token, consume the code, rotate it and
   * return the next code. Invalid/absent/used codes return undefined after
   * counting a rate-limit failure; success works even with no paired devices.
   *
   * @param {unknown} code
   * @param {{ deviceName?: unknown, platform?: unknown, publicKey?: unknown, ip?: string }} [options]
   * @returns {Promise<{ token: string, device: Record<string, any>, nextCode: string, createdAt: string } | undefined>}
   */
  async consumeRecoveryCode(code, { deviceName, platform, publicKey, ip } = {}) {
    const ipKey = ip || 'unknown';
    if (!this.limiter.allows(ipKey)) {
      const retryAfterMs = this.limiter.retryAfterMs(ipKey);
      throw new ApiError('rate_limited', 'Too many failed recovery attempts; try again later', {
        details: { retryAfterMs },
      });
    }
    const canonical = canonicalRecoveryCode(code);
    if (canonical === undefined || this.recovery === null) {
      this.limiter.recordFailure(ipKey);
      return undefined;
    }
    const presented = Buffer.from(hashToken(canonical), 'utf8');
    const stored = Buffer.from(this.recovery.hash, 'utf8');
    if (stored.length !== presented.length || !crypto.timingSafeEqual(stored, presented)) {
      this.limiter.recordFailure(ipKey);
      return undefined;
    }

    const next = this.#makeRecovery();
    const previousRecovery = this.recovery;
    this.recovery = { hash: next.hash, createdAt: next.createdAt };
    const { token, device } = this.#createDevice({ deviceName, platform, publicKey, ip });
    try {
      // ONE atomic write: the new device and the rotated hash cannot diverge.
      await this.#persistDevices({ throwOnError: true });
    } catch (error) {
      const index = this.devices.indexOf(device);
      if (index >= 0) this.devices.splice(index, 1);
      this.recovery = previousRecovery;
      try {
        await this.#persistDevices({ throwOnError: true });
      } catch {
        /* best-effort rollback; the in-memory state was restored above */
      }
      throw new ApiError('internal', 'Could not persist the recovery pairing', { cause: error });
    }
    return {
      token,
      device: publicDevice(device),
      nextCode: next.code,
      createdAt: new Date(next.createdAt).toISOString(),
    };
  }

  /**
   * Resolve a bearer token to its paired device, or undefined.
   * Uses a timing-safe comparison of SHA-256 hashes.
   *
   * @param {unknown} token
   * @returns {Record<string, any> | undefined}
   */
  authenticate(token) {
    if (typeof token !== 'string' || token.length < 16 || token.length > 256) return undefined;
    const presented = Buffer.from(hashToken(token), 'utf8');
    for (const device of this.devices) {
      const stored = Buffer.from(device.tokenHash, 'utf8');
      if (stored.length === presented.length && crypto.timingSafeEqual(stored, presented)) {
        device.lastSeenAt = this.now();
        return publicDevice(device);
      }
    }
    return undefined;
  }

  /** Whether at least one device has ever been paired. */
  hasDevices() {
    return this.devices.length > 0;
  }

  /** Non-secret metadata of every paired device. */
  listDevices() {
    return this.devices.map((device) => publicDevice(device));
  }

  /**
   * Subscribe to device revocations (used to close that device's open SSE
   * streams immediately). Returns an unsubscribe function.
   *
   * @param {(deviceId: string) => void} listener
   * @returns {() => void}
   */
  onRevoke(listener) {
    if (typeof listener !== 'function') return () => {};
    this.revokeListeners.add(listener);
    return () => {
      this.revokeListeners.delete(listener);
    };
  }

  /**
   * Revoke one paired device by id. The device's token stops authenticating
   * immediately; revoke listeners run (closing open streams) and the reduced
   * list is persisted **before** the promise resolves. A persistence failure
   * rejects and restores the device, so a crash/partial write can never leave
   * the in-memory and on-disk states disagreeing about whether it was revoked.
   *
   * @param {string} deviceId
   * @returns {Promise<boolean>} true when a paired device was removed
   */
  async revokeDevice(deviceId) {
    const id = String(deviceId ?? '');
    if (id === '') return false;
    const index = this.devices.findIndex((device) => String(device.deviceId) === id);
    if (index < 0) return false;
    const [removed] = this.devices.splice(index, 1);

    // Invalidate/notify immediately, before waiting for disk I/O.
    for (const listener of this.revokeListeners) {
      try {
        listener(id);
      } catch (error) {
        this.logger.warn?.(
          `[dsh-mobile-host] device-revoke listener failed: ${String(/** @type {any} */ (error)?.message ?? error)}`,
        );
      }
    }

    try {
      await this.#persistDevices({ throwOnError: true });
    } catch (error) {
      // Roll back so a failed write cannot resurrect the device at next start.
      this.devices.splice(index, 0, removed);
      throw error;
    }
    return true;
  }
}
