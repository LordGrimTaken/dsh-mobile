/**
 * Tiny JSON state stores for persisted device-token hashes.
 *
 * Only SHA-256 token hashes and device metadata are persisted; raw bearer
 * tokens exist only in the phone's response and are never written to disk.
 */

import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';

/** Empty persisted state. */
export function emptyState() {
  return { version: 1, devices: [] };
}

/** @param {unknown} value */
function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/**
 * Validate a decoded state document. Unknown/corrupt data is discarded rather
 * than trusted: a bad state file must never authenticate a device.
 *
 * @param {unknown} value
 */
export function normalizeState(value) {
  if (!isPlainObject(value)) return emptyState();
  const devices = Array.isArray(value.devices)
    ? value.devices.filter(
        (device) =>
          isPlainObject(device) &&
          typeof device.deviceId === 'string' &&
          device.deviceId.length > 0 &&
          typeof device.tokenHash === 'string' &&
          /^[0-9a-f]{64}$/.test(device.tokenHash),
      )
    : [];
  // Optional recovery-code hash (same file, one atomic write). Old
  // devices-only state files simply omit it.
  const recovery = isPlainObject(value.recovery) &&
    typeof value.recovery.hash === 'string' &&
    /^[0-9a-f]{64}$/.test(value.recovery.hash) &&
    typeof value.recovery.createdAt === 'number'
    ? { hash: value.recovery.hash, createdAt: value.recovery.createdAt }
    : undefined;
  return {
    version: 1,
    devices: devices.map((device) => ({ ...device })),
    ...(recovery === undefined ? {} : { recovery }),
  };
}

/** In-memory store used by tests and as a fallback. */
export class MemoryStateStore {
  /** @param {unknown} [initial] */
  constructor(initial = emptyState()) {
    this.state = normalizeState(initial);
    this.saves = 0;
    this.failures = 0;
  }

  async load() {
    return structuredClone(this.state);
  }

  /** @param {unknown} state */
  async save(state) {
    this.state = structuredClone(normalizeState(state));
    this.saves += 1;
  }
}

/**
 * Atomic JSON file store (`write tmp → rename`), serialized through one queue.
 * Load failures degrade to empty state; save failures are logged and swallowed
 * so a read-only profile directory cannot break pairing for the current run.
 */
export class JsonStateStore {
  /**
   * @param {string} filePath
   * @param {{ logger?: Pick<Console, 'warn' | 'error'> }} [options]
   */
  constructor(filePath, options = {}) {
    this.filePath = filePath;
    this.logger = options.logger ?? console;
    /** @type {Promise<void>} */
    this.queue = Promise.resolve();
  }

  async load() {
    try {
      const text = await readFile(this.filePath, 'utf8');
      return normalizeState(JSON.parse(text));
    } catch (error) {
      if (/** @type {any} */ (error)?.code !== 'ENOENT') {
        this.logger.warn?.(
          `[dsh-mobile-host] ignoring unreadable state file ${this.filePath}: ${String(
            /** @type {any} */ (error)?.message ?? error,
          )}`,
        );
      }
      return emptyState();
    }
  }

  /** @param {unknown} state */
  async save(state) {
    const snapshot = normalizeState(state);
    const run = async () => {
      try {
        await mkdir(path.dirname(this.filePath), { recursive: true });
        const temporary = `${this.filePath}.${process.pid}.tmp`;
        await writeFile(temporary, `${JSON.stringify(snapshot, null, 2)}\n`, 'utf8');
        await rename(temporary, this.filePath);
      } catch (error) {
        this.logger.warn?.(
          `[dsh-mobile-host] could not persist host state to ${this.filePath}: ${String(
            /** @type {any} */ (error)?.message ?? error,
          )}`,
        );
      }
    };
    this.queue = this.queue.then(run, run);
    return this.queue;
  }
}
