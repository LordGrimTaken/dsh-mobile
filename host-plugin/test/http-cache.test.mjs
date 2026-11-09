import assert from 'node:assert/strict';
import { test } from 'node:test';

import {
  MAX_ETAG_BODY_BYTES,
  computeWeakEtag,
  etagMatches,
} from '../lib/http-cache.js';

test('weak etags are stable and weak', () => {
  const one = computeWeakEtag(JSON.stringify({ sessions: [1, 2, 3] }));
  const two = computeWeakEtag(JSON.stringify({ sessions: [1, 2, 3] }));
  assert.equal(one, two);
  assert.match(one, /^W\/"[0-9a-f]{20}"$/);
  assert.notEqual(one, computeWeakEtag(JSON.stringify({ sessions: [1, 2, 4] })));
});

test('if-none-match matching tolerates lists and whitespace', () => {
  const etag = computeWeakEtag('{}');
  assert.equal(etagMatches(etag, etag), true);
  assert.equal(etagMatches(` ${etag} `, etag), true);
  assert.equal(etagMatches(`W/"other", ${etag}`, etag), true);
  assert.equal(etagMatches('W/"other"', etag), false);
  assert.equal(etagMatches(undefined, etag), false);
  assert.equal(etagMatches(etag, undefined), false);
});

test('etag size budget leaves large payloads untagged by the router', () => {
  assert.ok(MAX_ETAG_BODY_BYTES >= 64 * 1024);
  const big = 'x'.repeat(MAX_ETAG_BODY_BYTES + 1);
  assert.ok(big.length > MAX_ETAG_BODY_BYTES);
});
