/**
 * Conditional-GET helpers for JSON responses.
 *
 * Snapshots and transcripts are the large payloads on slow links. A weak ETag over the serialized
 * JSON lets the phone revalidate with `If-None-Match` and get an empty 304 instead of the body;
 * oversized bodies are left untagged so hashing never costs more than it saves.
 */

import crypto from 'node:crypto';

export const MAX_ETAG_BODY_BYTES = 512 * 1024;

/** Weak ETag for a JSON payload string. */
export function computeWeakEtag(payload) {
  const digest = crypto.createHash('sha1').update(String(payload)).digest('hex').slice(0, 20);
  return `W/"${digest}"`;
}

/** True when the request's If-None-Match header names [etag]. */
export function etagMatches(ifNoneMatch, etag) {
  if (!ifNoneMatch || !etag) return false;
  return String(ifNoneMatch)
    .split(',')
    .map((value) => value.trim())
    .includes(etag);
}
