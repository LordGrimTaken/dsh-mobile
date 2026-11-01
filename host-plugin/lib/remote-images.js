/**
 * Opt-in remote image proxy for Markdown http(s) image links.
 *
 * This module is deliberately independent from the adapter so the URL policy,
 * SSRF classifier, streaming limits, redirect handling and bounded cache can be
 * unit-tested without a real network. The adapter only calls it on attachment
 * read (never during session listing/detail mapping).
 */

import crypto from 'node:crypto';
import dns from 'node:dns';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import path from 'node:path';

/** Hard cap for one proxied image; config may lower (but not raise) it. */
export const REMOTE_IMAGE_MAX_BYTES_CAP = 25 * 1024 * 1024;
/** Bounded in-memory cache for fetched remote images. */
export const REMOTE_IMAGE_CACHE_MAX_BYTES = 64 * 1024 * 1024;
/** Redirect hops followed before giving up. */
export const REMOTE_IMAGE_MAX_REDIRECTS = 3;
/** Sent to every remote host; no cookies/auth are ever forwarded. */
export const REMOTE_IMAGE_USER_AGENT = 'dsh-mobile-host/0.3.0 image-proxy';

const REMOTE_IMAGE_MIME = Object.freeze({
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
});

/** @param {string} extension @param {Buffer} bytes */
export function detectImageMagic(extension, bytes) {
  if (!Buffer.isBuffer(bytes) || bytes.length < 2) return null;
  const startsWith = (signature) =>
    bytes.length >= signature.length && signature.every((value, index) => bytes[index] === value);
  if (extension === '.png' && startsWith([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])) {
    return 'image/png';
  }
  if ((extension === '.jpg' || extension === '.jpeg') && startsWith([0xff, 0xd8, 0xff])) {
    return 'image/jpeg';
  }
  if (
    extension === '.gif' &&
    (startsWith([0x47, 0x49, 0x46, 0x38, 0x37, 0x61]) || startsWith([0x47, 0x49, 0x46, 0x38, 0x39, 0x61]))
  ) {
    return 'image/gif';
  }
  if (
    extension === '.webp' &&
    startsWith([0x52, 0x49, 0x46, 0x46]) &&
    bytes.length >= 12 &&
    bytes.subarray(8, 12).toString('latin1') === 'WEBP'
  ) {
    return 'image/webp';
  }
  return null;
}

/**
 * Validate a Markdown remote image URL and derive stable public metadata.
 * Never performs DNS or network work. Returns null for anything outside the
 * narrow http(s) image policy (scheme, ports, userinfo, fragment, extension).
 *
 * @param {unknown} rawUrl
 * @returns {{ url: URL, id: string, name: string, mimeType: string, extension: string } | null}
 */
export function parseRemoteImageUrl(rawUrl) {
  if (typeof rawUrl !== 'string') return null;
  const trimmed = rawUrl.trim();
  if (trimmed === '') return null;
  let parsed;
  try {
    parsed = new URL(trimmed);
  } catch {
    return null;
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null;
  if (parsed.username !== '' || parsed.password !== '') return null;
  if (parsed.hash !== '') return null;
  if (parsed.port !== '' && parsed.port !== '80' && parsed.port !== '443') return null;
  if (parsed.hostname === '') return null;
  // Trailing-dot hosts alias another name in some resolvers; keep the policy tight.
  if (parsed.hostname.endsWith('.')) return null;

  let decodedPath;
  try {
    decodedPath = decodeURIComponent(parsed.pathname);
  } catch {
    return null;
  }
  const extension = path.extname(decodedPath).toLowerCase();
  const mimeType = REMOTE_IMAGE_MIME[extension];
  if (mimeType === undefined) return null;

  let name = path.posix.basename(decodedPath);
  name = name.replace(/[\u0000-\u001f\u007f\\/:*?"<>|]/gu, '_').trim().slice(0, 120);
  if (name === '' || name === '.' || name === '..') name = `remote-image${extension}`;

  return {
    url: parsed,
    id: `ext_${crypto.createHash('sha256').update(parsed.toString()).digest('hex').slice(0, 16)}`,
    name,
    mimeType,
    extension,
  };
}

/** @param {number[]} octets */
function ipv4Allowed(octets) {
  const [a, b, c] = octets;
  if (a === 0) return false; // "this" network / unspecified
  if (a === 10) return false; // private
  if (a === 100 && b >= 64 && b <= 127) return false; // CGNAT
  if (a === 127) return false; // loopback
  if (a === 169 && b === 254) return false; // link-local
  if (a === 172 && b >= 16 && b <= 31) return false; // private
  if (a === 192 && b === 0 && (c === 0 || c === 2)) return false; // IETF / TEST-NET-1
  if (a === 192 && b === 88 && c === 99) return false; // deprecated 6to4 relay
  if (a === 192 && b === 168) return false; // private
  if (a === 198 && (b === 18 || b === 19)) return false; // benchmarking
  if (a === 198 && b === 51 && c === 100) return false; // TEST-NET-2
  if (a === 203 && b === 0 && c === 113) return false; // TEST-NET-3
  if (a >= 224) return false; // multicast, reserved, broadcast
  return true;
}

/** @param {string} address */
function parseIpv4(address) {
  const parts = address.split('.');
  if (parts.length !== 4) return null;
  const octets = parts.map((part) => (/^\d{1,3}$/u.test(part) ? Number(part) : Number.NaN));
  if (octets.some((value) => !Number.isInteger(value) || value < 0 || value > 255)) return null;
  return octets;
}

/** @param {string} address */
function parseIpv6Groups(address) {
  let normalized = address.toLowerCase();
  if (normalized.includes('%') || normalized.includes('/')) return null;
  // Convert an embedded dotted-quad tail to two hex groups first.
  if (normalized.includes('.')) {
    const dot = normalized.lastIndexOf('.');
    const colon = normalized.lastIndexOf(':', dot);
    if (colon < 0) return null;
    const v4 = parseIpv4(normalized.slice(colon + 1));
    if (v4 === null) return null;
    const high = ((v4[0] << 8) | v4[1]).toString(16);
    const low = ((v4[2] << 8) | v4[3]).toString(16);
    normalized = `${normalized.slice(0, colon + 1)}${high}:${low}`;
  }
  if (normalized.includes(':::')) return null;
  const split = normalized.split('::');
  if (split.length > 2) return null;
  const head = split[0] === '' ? [] : split[0].split(':');
  const tail = split.length === 2 ? (split[1] === '' ? [] : split[1].split(':')) : [];
  let groups;
  if (split.length === 1) {
    if (head.length !== 8) return null;
    groups = head;
  } else {
    const missing = 8 - head.length - tail.length;
    if (missing < 1) return null;
    groups = [...head, ...Array.from({ length: missing }, () => '0'), ...tail];
  }
  if (groups.length !== 8) return null;
  const numbers = groups.map((group) => (/^[0-9a-f]{1,4}$/u.test(group) ? Number.parseInt(group, 16) : Number.NaN));
  return numbers.some((value) => !Number.isInteger(value)) ? null : numbers;
}

/** @param {string} address */
function ipv6Allowed(address) {
  const groups = parseIpv6Groups(address);
  if (groups === null) return false;
  const [first] = groups;
  const v4From = (high, low) => ipv4Allowed([(high >> 8) & 0xff, high & 0xff, (low >> 8) & 0xff, low & 0xff]);

  // Unspecified :: and loopback ::1.
  if (groups.every((group) => group === 0)) return false;
  if (groups.slice(0, 7).every((group) => group === 0) && groups[7] === 1) return false;

  // IPv4-mapped ::ffff:0:0/96.
  if (groups.slice(0, 5).every((group) => group === 0) && groups[5] === 0xffff) {
    return v4From(groups[6], groups[7]);
  }
  // Deprecated IPv4-compatible ::/96 (non-zero tail) is not public unicast.
  if (groups.slice(0, 6).every((group) => group === 0)) return false;

  // NAT64 well-known 64:ff9b::/96 — classify the embedded IPv4.
  if (first === 0x64 && groups[1] === 0xff9b && groups.slice(2, 6).every((group) => group === 0)) {
    return v4From(groups[6], groups[7]);
  }
  // NAT64 local-use 64:ff9b:1::/48 is not globally reachable.
  if (first === 0x64 && groups[1] === 0xff9b && groups[2] === 1) return false;

  // 6to4 2002::/16 — embedded IPv4 is groups[1],groups[2].
  if (first === 0x2002) return v4From(groups[1], groups[2]);

  // Teredo 2001:0000::/32 (compressed or uncompressed).
  if (first === 0x2001 && groups[1] === 0x0000) return false;
  // ORCHIDv1 2001:10::/28 and ORCHIDv2 2001:20::/28.
  if (first === 0x2001 && (groups[1] & 0xfff0) === 0x0010) return false;
  if (first === 0x2001 && (groups[1] & 0xfff0) === 0x0020) return false;
  // Documentation 2001:db8::/32 and 3fff::/20.
  if (first === 0x2001 && groups[1] === 0x0db8) return false;
  if (first === 0x3fff && (groups[1] & 0xf000) === 0) return false;
  // Benchmarking 2001:2::/48.
  if (first === 0x2001 && groups[1] === 0x0002 && groups[2] === 0) return false;
  // Discard-only 100::/64.
  if (first === 0x0100 && groups.slice(1, 4).every((group) => group === 0)) return false;

  if ((first & 0xfe00) === 0xfc00) return false; // ULA fc00::/7
  if ((first & 0xffc0) === 0xfe80) return false; // link-local fe80::/10
  if ((first & 0xffc0) === 0xfec0) return false; // deprecated site-local fec0::/10
  if ((first & 0xff00) === 0xff00) return false; // multicast ff00::/8
  return true;
}

/**
 * Classify one resolved address for the SSRF policy. Public unicast addresses
 * are allowed; loopback/private/link-local/ULA/multicast/reserved are rejected.
 *
 * @param {unknown} address
 * @returns {{ allowed: boolean, family: 0 | 4 | 6, reason: string }}
 */
export function classifyRemoteAddress(address) {
  if (typeof address !== 'string') return { allowed: false, family: 0, reason: 'invalid' };
  const family = net.isIP(address);
  if (family === 4) {
    const octets = parseIpv4(address);
    return {
      allowed: octets !== null && ipv4Allowed(octets),
      family: 4,
      reason: octets !== null && ipv4Allowed(octets) ? 'public' : 'non-public-ipv4',
    };
  }
  if (family === 6) {
    const allowed = ipv6Allowed(address);
    return { allowed, family: 6, reason: allowed ? 'public' : 'non-public-ipv6' };
  }
  return { allowed: false, family: 0, reason: 'invalid' };
}

/** @param {unknown} value */
function headerValue(headers, name) {
  if (!headers || typeof headers !== 'object') return '';
  const value = headers[name];
  return Array.isArray(value) ? String(value[0] ?? '') : String(value ?? '');
}

/** @param {string} value */
function parseContentType(value) {
  return value.split(';')[0].trim().toLowerCase();
}

/**
 * Fetch one remote image with SSRF pinning, redirect revalidation, strict
 * streaming bounds and magic-byte validation. The transport/lookup are
 * injectable so tests never touch the network.
 */
export class RemoteImageFetcher {
  /**
   * @param {{
   *   maxBytes?: number, timeoutMs?: number, ttlMs?: number, cacheEntries?: number,
   *   maxCacheBytes?: number, lookup?: (hostname: string) => Promise<string[]>,
   *   request?: (url: URL, context: { address: string, family: number, maxBytes: number, timeoutMs: number }) => Promise<{ statusCode: number, headers: Record<string, any>, body: Buffer }>,
   *   now?: () => number,
   * }} [options]
   */
  constructor(options = {}) {
    this.maxBytes = Math.min(
      REMOTE_IMAGE_MAX_BYTES_CAP,
      Number.isSafeInteger(options.maxBytes) && options.maxBytes > 0 ? options.maxBytes : 10 * 1024 * 1024,
    );
    this.timeoutMs = Number.isSafeInteger(options.timeoutMs) && options.timeoutMs > 0 ? options.timeoutMs : 8000;
    this.ttlMs = Number.isSafeInteger(options.ttlMs) && options.ttlMs > 0 ? options.ttlMs : 10 * 60 * 1000;
    this.cacheEntries = Number.isSafeInteger(options.cacheEntries) && options.cacheEntries > 0 ? options.cacheEntries : 32;
    this.maxCacheBytes = Number.isSafeInteger(options.maxCacheBytes) && options.maxCacheBytes > 0
      ? options.maxCacheBytes
      : REMOTE_IMAGE_CACHE_MAX_BYTES;
    this.now = typeof options.now === 'function' ? options.now : Date.now;
    this.lookup = typeof options.lookup === 'function' ? options.lookup : defaultLookup;
    this.request = typeof options.request === 'function' ? options.request : requestRemoteImage;
    // A global cap prevents many distinct refs from launching parallel 10 MB
    // downloads; failures are negatively cached briefly to stop retry storms.
    this.maxConcurrent = Number.isSafeInteger(options.maxConcurrent) && options.maxConcurrent > 0
      ? Math.min(options.maxConcurrent, 16)
      : 4;
    this.negativeTtlMs = Number.isSafeInteger(options.negativeTtlMs) && options.negativeTtlMs > 0
      ? options.negativeTtlMs
      : 30_000;
    this.active = 0;
    this.waiters = [];
    /** @type {Map<string, number>} */
    this.negativeCache = new Map();
    /** @type {Map<string, { bytes: Buffer, mimeType: string, name: string, expiresAt: number, lastUsed: number }>} */
    this.cache = new Map();
    /** @type {Map<string, Promise<{ bytes: Buffer, mimeType: string, name: string }>>} */
    this.inflight = new Map();
    this.cachedBytes = 0;
  }

  /**
   * Return a validated image for a remote URL (cached; concurrent callers share
   * one fetch; at most `maxConcurrent` fetches run at once). Rejects with a
   * generic error that never includes the URL.
   *
   * @param {string} rawUrl
   * @returns {Promise<{ bytes: Buffer, mimeType: string, name: string }>}
   */
  async get(rawUrl) {
    const parsed = parseRemoteImageUrl(rawUrl);
    if (parsed === null) throw remoteError('Invalid remote image URL');
    const key = parsed.url.toString();
    this.pruneExpired();
    if ((this.negativeCache.get(key) ?? 0) > this.now()) return Promise.reject(remoteError('Remote image is not available'));
    const cached = this.cache.get(key);
    if (cached) {
      cached.lastUsed = this.now();
      return { bytes: cached.bytes, mimeType: cached.mimeType, name: cached.name };
    }
    const pending = this.inflight.get(key);
    if (pending) return pending;
    const task = this.withSlot(() => this.fetch(parsed))
      .then((image) => {
        this.store(key, image);
        return image;
      })
      .catch((error) => {
        this.rememberFailure(key);
        throw error;
      })
      .finally(() => {
        this.inflight.delete(key);
      });
    this.inflight.set(key, task);
    return task;
  }

  /** @param {() => Promise<any>} task */
  async withSlot(task) {
    if (this.active >= this.maxConcurrent) {
      await new Promise((resolve) => this.waiters.push(resolve));
    }
    this.active += 1;
    try {
      return await task();
    } finally {
      this.active -= 1;
      const next = this.waiters.shift();
      if (next) next();
    }
  }

  /** @param {string} key */
  rememberFailure(key) {
    this.negativeCache.set(key, this.now() + this.negativeTtlMs);
    while (this.negativeCache.size > this.cacheEntries) {
      const oldest = this.negativeCache.keys().next().value;
      if (oldest === undefined) break;
      this.negativeCache.delete(oldest);
    }
  }

  /** @param {ReturnType<typeof parseRemoteImageUrl>} parsed */
  async fetch(parsed) {
    let current = parsed.url;
    let redirects = 0;
    while (true) {
      if (redirects > REMOTE_IMAGE_MAX_REDIRECTS) throw remoteError('Too many redirects');
      const { address, family } = await this.resolvePinnedAddress(current);
      const response = await this.request(current, {
        address,
        family,
        maxBytes: this.maxBytes,
        timeoutMs: this.timeoutMs,
      });
      if (response.statusCode >= 300 && response.statusCode < 400) {
        const location = headerValue(response.headers, 'location');
        if (location === '' || redirects === REMOTE_IMAGE_MAX_REDIRECTS) throw remoteError('Invalid redirect');
        let next;
        try {
          next = new URL(location, current);
        } catch {
          throw remoteError('Invalid redirect');
        }
        // Every hop must pass the same URL policy (http(s), port, image extension).
        const nextParsed = parseRemoteImageUrl(next.toString());
        if (nextParsed === null) throw remoteError('Redirect outside image policy');
        current = nextParsed.url;
        redirects += 1;
        continue;
      }
      if (response.statusCode !== 200) throw remoteError('Remote image fetch failed');
      const body = Buffer.isBuffer(response.body) ? response.body : Buffer.from(response.body ?? []);
      if (body.length === 0) throw remoteError('Remote image is empty');
      if (body.length > this.maxBytes) throw remoteError('Remote image is too large');
      const declared = parseContentType(headerValue(response.headers, 'content-type'));
      if (declared !== '' && !declared.startsWith('image/')) throw remoteError('Remote image type is not an image');
      const magicType = detectImageMagic(parsed.extension, body);
      if (magicType === null || magicType !== parsed.mimeType) throw remoteError('Remote image content is invalid');
      // A declared Content-Type must exactly match the magic-byte type; an
      // absent header is fine (many CDNs omit it), a misleading one is not.
      if (declared !== '' && declared !== magicType) {
        throw remoteError('Remote image content type mismatch');
      }
      return { bytes: body, mimeType: magicType, name: parsed.name };
    }
  }

  /** @param {URL} url */
  async resolvePinnedAddress(url) {
    const hostname = url.hostname.replace(/^\[|\]$/gu, '');
    if (net.isIP(hostname) !== 0) {
      const classified = classifyRemoteAddress(hostname);
      if (!classified.allowed) throw remoteError('Remote host is not public');
      return { address: hostname, family: classified.family };
    }
    let addresses;
    try {
      addresses = await this.lookup(hostname);
    } catch {
      throw remoteError('Remote host could not be resolved');
    }
    if (!Array.isArray(addresses) || addresses.length === 0) throw remoteError('Remote host is not public');
    // Pin only after EVERY address passes the policy: a mixed answer is hostile.
    for (const address of addresses) {
      if (!classifyRemoteAddress(address).allowed) throw remoteError('Remote host is not public');
    }
    for (const address of addresses) {
      const family = net.isIP(address);
      if (family === 4 || family === 6) return { address, family };
    }
    throw remoteError('Remote host is not public');
  }

  /** @param {string} key @param {{ bytes: Buffer, mimeType: string, name: string }} image */
  store(key, image) {
    this.pruneExpired();
    const existing = this.cache.get(key);
    if (existing) this.cachedBytes -= existing.bytes.length;
    this.cache.set(key, {
      ...image,
      expiresAt: this.now() + this.ttlMs,
      lastUsed: this.now(),
    });
    this.cachedBytes += image.bytes.length;
    while (this.cache.size > this.cacheEntries || this.cachedBytes > this.maxCacheBytes) {
      let oldestKey = null;
      let oldestUsed = Number.POSITIVE_INFINITY;
      for (const [candidateKey, entry] of this.cache) {
        if (entry.lastUsed < oldestUsed && candidateKey !== key) {
          oldestUsed = entry.lastUsed;
          oldestKey = candidateKey;
        }
      }
      if (oldestKey === null) {
        // Only the just-inserted entry remains; drop it rather than exceed bounds.
        this.cachedBytes -= image.bytes.length;
        this.cache.delete(key);
        break;
      }
      const removed = this.cache.get(oldestKey);
      this.cachedBytes -= removed.bytes.length;
      this.cache.delete(oldestKey);
    }
  }

  pruneExpired() {
    const now = this.now();
    for (const [key, entry] of this.cache) {
      if (entry.expiresAt <= now) {
        this.cachedBytes -= entry.bytes.length;
        this.cache.delete(key);
      }
    }
    for (const [key, expiresAt] of this.negativeCache) {
      if (expiresAt <= now) this.negativeCache.delete(key);
    }
  }
}

/** Generic error: never includes the remote URL or host. */
function remoteError(message) {
  const error = new Error(message);
  error.code = 'remote_image_failed';
  return error;
}

/** @param {string} hostname */
async function defaultLookup(hostname) {
  const records = await dns.promises.lookup(hostname, { all: true, verbatim: true });
  return records.map((record) => record.address);
}

/**
 * Default network transport. Resolves to `{statusCode, headers, body}` bytes;
 * enforces timeout and byte cap while streaming, aborts redirect bodies right
 * after headers, and never sends cookies/auth. Exported for transport-level
 * tests; production callers should use {@link RemoteImageFetcher}.
 *
 * @param {URL} url
 * @param {{ address: string, family: number, maxBytes: number, timeoutMs: number }} context
 */
export function requestRemoteImage(url, context) {
  return new Promise((resolve, reject) => {
    const isHttps = url.protocol === 'https:';
    const client = isHttps ? https : http;
    const headers = {
      Host: url.host,
      'User-Agent': REMOTE_IMAGE_USER_AGENT,
      Accept: 'image/png,image/jpeg,image/webp,image/gif;q=0.9,*/*;q=0.1',
      Connection: 'close',
    };
    let settled = false;
    const finish = (error, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (error) reject(remoteError('Remote image fetch failed'));
      else resolve(value);
    };
    const timer = setTimeout(() => {
      request.destroy(new Error('timeout'));
      finish(remoteError('Remote image fetch timed out'));
    }, context.timeoutMs);
    timer.unref?.();
    let request;
    try {
      request = client.request(
        {
          protocol: url.protocol,
          hostname: context.address,
          port: url.port === '' ? (isHttps ? 443 : 80) : Number(url.port),
          path: `${url.pathname}${url.search}`,
          method: 'GET',
          headers,
          ...(isHttps ? { servername: url.hostname.replace(/^\[|\]$/gu, '') } : {}),
          lookup: (hostname, options, callback) => {
            // DNS is pinned to the already-validated address; the OS resolver is
            // never consulted again between validation and connect.
            if (typeof options === 'function') {
              callback = options;
              options = {};
            }
            if (options && options.all) {
              callback(null, [{ address: context.address, family: context.family }]);
            } else {
              callback(null, context.address, context.family);
            }
          },
        },
        (response) => {
          const statusCode = Number(response.statusCode ?? 0);
          // Redirect responses are never body-buffered: abort immediately after
          // the headers, then revalidate the Location hop.
          if (statusCode >= 300 && statusCode < 400) {
            response.destroy();
            finish(null, { statusCode, headers: response.headers ?? {}, body: Buffer.alloc(0) });
            return;
          }
          const chunks = [];
          let total = 0;
          response.on('data', (chunk) => {
            total += chunk.length;
            if (total > context.maxBytes) {
              response.destroy(new Error('too large'));
              finish(remoteError('Remote image is too large'));
              return;
            }
            chunks.push(Buffer.from(chunk));
          });
          response.on('end', () => {
            finish(null, {
              statusCode,
              headers: response.headers ?? {},
              body: Buffer.concat(chunks),
            });
          });
          response.on('error', () => finish(remoteError('Remote image fetch failed')));
        },
      );
    } catch {
      finish(remoteError('Remote image fetch failed'));
      return;
    }
    request.on('error', () => finish(remoteError('Remote image fetch failed')));
    request.end();
  });
}
