import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { readFile, rm, utimes, writeFile } from 'node:fs/promises';

import { resolveConfig } from '../lib/config.js';
import { ApiError } from '../lib/errors.js';
import {
  HiddenSessionStore,
  MAX_HIDDEN_SESSIONS,
  parseHiddenSessions,
  validateHiddenIds,
} from '../lib/hidden-sessions.js';
import { createHiddenStore, tempHiddenFile } from './helpers.mjs';

const silent = { info() {}, warn() {}, error() {} };
const A = 'session-0988fac4-e222-4309-8a11-1b42cf50b524';
const B = 'session-16016a8a-453f-44a3-ac2a-055b5868c629';

function isApiError(code) {
  return (error) => error instanceof ApiError && error.code === code;
}

test('config: hidden-session defaults and overrides', () => {
  const config = resolveConfig({ stateFile: 'C:\\tmp\\state\\host-state.json' });
  assert.equal(config.sessions.hideBlankAfterMs, 3_600_000);
  assert.equal(config.sessions.hiddenFile, path.join('C:\\tmp\\state', 'hidden-sessions.json'));

  const custom = resolveConfig({
    stateFile: 'C:\\tmp\\state\\host-state.json',
    sessions: { hiddenFile: 'C:\\curated\\hidden.json', hideBlankAfterMs: 0 },
  });
  assert.equal(custom.sessions.hiddenFile, 'C:\\curated\\hidden.json');
  assert.equal(custom.sessions.hideBlankAfterMs, 0, '0 disables auto-hiding');

  // Invalid values fall back to the safe defaults.
  assert.equal(resolveConfig({ sessions: { hideBlankAfterMs: -5 } }).sessions.hideBlankAfterMs, 3_600_000);
  assert.equal(resolveConfig({ sessions: { hideBlankAfterMs: 'nope' } }).sessions.hideBlankAfterMs, 3_600_000);
});

test('validateHiddenIds accepts session ids, de-duplicates, and enforces the cap', () => {
  assert.deepEqual(validateHiddenIds([]), []);
  assert.deepEqual(validateHiddenIds([A, B, A]), [A, B]);
  for (const bad of [
    'session-abc',
    42,
    null,
    [7],
    [''],
    ['not-a-session'],
    ['session-'],
    ['session-' + 'x'.repeat(200)],
    Array.from({ length: MAX_HIDDEN_SESSIONS + 1 }, (_, index) => `session-${index}`),
  ]) {
    assert.throws(() => validateHiddenIds(bad), isApiError('invalid_request'));
  }
  assert.equal(validateHiddenIds(Array.from({ length: MAX_HIDDEN_SESSIONS }, (_, index) => `session-${index}`)).length, 500);
});

test('parseHiddenSessions tolerates legacy shapes and drops invalid entries', () => {
  assert.deepEqual(parseHiddenSessions({ updatedAt: '2026-10-30T13:00:00Z', ids: [A, 'bad', A] }, { cap: 500, logger: silent }), {
    ids: [A],
    updatedAt: '2026-10-30T13:00:00Z',
  });
  assert.deepEqual(parseHiddenSessions([A, B], { cap: 500, logger: silent }), { ids: [A, B], updatedAt: null });
  assert.deepEqual(parseHiddenSessions({ hiddenIds: [B] }, { cap: 500, logger: silent }), { ids: [B], updatedAt: null });
  assert.deepEqual(parseHiddenSessions('garbage', { cap: 500, logger: silent }), { ids: [], updatedAt: null });
  assert.deepEqual(parseHiddenSessions({ ids: 'nope' }, { cap: 500, logger: silent }), { ids: [], updatedAt: null });
  assert.equal(parseHiddenSessions({ ids: [A, B] }, { cap: 1, logger: silent }).ids.length, 1);
});

test('store: missing file is an empty set, existing files load ids and updatedAt', async (t) => {
  const missing = createHiddenStore({ filePath: await tempHiddenFile(t) });
  await missing.load();
  assert.deepEqual(missing.getState(), { ids: [], updatedAt: null });
  assert.equal(missing.has(A), false);

  const hiddenFile = await tempHiddenFile(t);
  await writeFile(hiddenFile, JSON.stringify({ updatedAt: '2026-10-30T13:00:00Z', ids: [A] }), 'utf8');
  const loaded = createHiddenStore({ filePath: hiddenFile });
  await loaded.load();
  assert.deepEqual(loaded.getState(), { ids: [A], updatedAt: '2026-10-30T13:00:00Z' });
  assert.equal(loaded.has(A), true);
  assert.equal(loaded.has(B), false);
  assert.equal(loaded.size, 1);
});

test('store: corrupt file degrades to an empty set instead of throwing', async (t) => {
  const warnings = [];
  const hiddenFile = await tempHiddenFile(t);
  await writeFile(hiddenFile, '{ this is not json', 'utf8');
  const store = createHiddenStore({ filePath: hiddenFile, logger: { warn: (line) => warnings.push(String(line)) } });
  await store.load();
  assert.deepEqual(store.getState().ids, []);
  assert.equal(warnings.some((line) => /not valid JSON/i.test(line)), true);
});

test('store: refresh only reloads when the file mtime/size changes', async (t) => {
  const hiddenFile = await tempHiddenFile(t);
  const store = createHiddenStore({ filePath: hiddenFile, logger: silent });
  await writeFile(hiddenFile, JSON.stringify({ updatedAt: '2026-10-30T13:00:00Z', ids: [A] }), 'utf8');
  await store.load();
  assert.deepEqual(store.getState().ids, [A]);

  // Unchanged file: no reload.
  assert.equal(await store.refresh(), false);

  await writeFile(hiddenFile, JSON.stringify({ updatedAt: '2026-10-30T13:05:00Z', ids: [B] }), 'utf8');
  const later = new Date(Date.now() + 5000);
  await utimes(hiddenFile, later, later);
  assert.equal(await store.refresh(), true);
  assert.deepEqual(store.getState().ids, [B]);
  assert.equal(await store.refresh(), false, 'second check sees the same stamp');

  // Deleted file clears the set.
  await rm(hiddenFile);
  assert.equal(await store.refresh(), true);
  assert.deepEqual(store.getState(), { ids: [], updatedAt: null });
});

test('store: refresh throttles stat calls within the configured interval', async (t) => {
  const hiddenFile = await tempHiddenFile(t);
  await writeFile(hiddenFile, JSON.stringify({ ids: [A] }), 'utf8');
  let now = 1000;
  const store = new HiddenSessionStore({ filePath: hiddenFile, checkIntervalMs: 1000, now: () => now });
  // First call performs the stat (lastCheck starts at -Infinity).
  assert.equal(await store.refresh(), true);
  assert.deepEqual(store.getState().ids, [A]);

  // Rewrite, but the interval has not elapsed: refresh short-circuits.
  await writeFile(hiddenFile, JSON.stringify({ ids: [B] }), 'utf8');
  const later = new Date(Date.now() + 5000);
  await utimes(hiddenFile, later, later);
  assert.equal(await store.refresh(), false);
  assert.deepEqual(store.getState().ids, [A], 'cached set kept inside the throttle window');

  // Advance the injected clock past the interval: the change is picked up.
  now += 1001;
  assert.equal(await store.refresh(), true);
  assert.deepEqual(store.getState().ids, [B]);
});

test('store: replace validates, persists the documented shape, and updates the cache', async (t) => {
  const hiddenFile = await tempHiddenFile(t);
  const store = createHiddenStore({ filePath: hiddenFile, logger: silent, now: () => Date.UTC(2026, 9, 30, 13, 0, 0) });
  const state = await store.replace([A, B, A]);
  assert.deepEqual(state.ids, [A, B]);
  assert.equal(state.updatedAt, '2026-10-30T13:00:00.000Z');
  assert.deepEqual(store.getState(), state);

  const document = JSON.parse(await readFile(hiddenFile, 'utf8'));
  assert.deepEqual(document, { version: 1, updatedAt: state.updatedAt, ids: [A, B] });

  await assert.rejects(() => store.replace('nope'), isApiError('invalid_request'));
  await assert.rejects(() => store.replace(['bad-id']), isApiError('invalid_request'));
  assert.deepEqual(store.getState().ids, [A, B], 'a refused replace keeps the old set');
});

test('store: replace works in memory when no file path is configured', async () => {
  const store = createHiddenStore();
  const state = await store.replace([A]);
  assert.deepEqual(state.ids, [A]);
  assert.equal(store.has(A), true);
  assert.equal(store.getState().updatedAt !== null, true);
  await store.replace([]);
  assert.equal(store.size, 0);
  assert.equal(store.filePath, null);
});

test('store: concurrent replaces serialize and the last one wins', async (t) => {
  const hiddenFile = await tempHiddenFile(t);
  const store = createHiddenStore({ filePath: hiddenFile, logger: silent });
  const results = await Promise.all([store.replace([A]), store.replace([B]), store.replace([])]);
  assert.deepEqual(results.at(-1).ids, []);
  assert.deepEqual(store.getState().ids, []);
  const document = JSON.parse(await readFile(hiddenFile, 'utf8'));
  assert.deepEqual(document.ids, []);
});
