import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';

import { resolveConfig } from '../lib/config.js';
import {
  internalReasonFromSessionHeader,
  internalReasonFromSessionSummary,
  normalizePath,
  sessionListCachePath,
} from '../lib/dsh-adapter.js';

/** A live top-level session row (shape observed from sessionController.list). */
const USER_ROW = {
  sessionId: 'session-11111111-1111-4111-8111-111111111111',
  updatedAt: 1793356136001,
  running: false,
  agentAvailable: false,
  cwd: 'C:\\.dsh',
  projections: { values: { subagent: {} } },
};

/** A live subagent session row: origin + parentSession are the positive markers. */
const SUBAGENT_ROW = {
  sessionId: '351ca07c-6d26-46a8-a685-cb627d642806',
  updatedAt: 1793356136001,
  running: false,
  agentAvailable: false,
  cwd: 'C:\\.dsh',
  origin: 'subagent',
  parentSessionId: 'session-11111111-1111-4111-8111-111111111111',
  projections: {
    values: { subagent: { identity: { mode: 'continuable', label: 'sword-and-seal-roadmap-batch2 child 5', seq: 0 } } },
  },
};

test('config: sessions.hideInternal defaults to true and only explicit false disables it', () => {
  assert.equal(resolveConfig({}).sessions.hideInternal, true);
  assert.equal(resolveConfig({ sessions: { hideInternal: false } }).sessions.hideInternal, false);
  assert.equal(resolveConfig({ sessions: { hideInternal: true } }).sessions.hideInternal, true);
  // A typo cannot weaken the privacy default.
  assert.equal(resolveConfig({ sessions: { hideInternal: 'nope' } }).sessions.hideInternal, true);
  assert.equal(resolveConfig({ sessions: { hideInternal: 0 } }).sessions.hideInternal, true);
});

test('summary marker detection keeps ordinary user sessions visible', () => {
  assert.equal(internalReasonFromSessionSummary(USER_ROW), undefined);
  // Empty subagent projection value is the live top-level shape: not internal.
  assert.equal(
    internalReasonFromSessionSummary({
      sessionId: 'session-1',
      projections: { values: { subagent: { identity: null } } },
    }),
    undefined,
  );
});

test('summary marker detection recognises origin, parentSessionId, projection identity and bare ids', () => {
  assert.equal(internalReasonFromSessionSummary(SUBAGENT_ROW), 'origin=subagent');
  assert.equal(
    internalReasonFromSessionSummary({ sessionId: 'session-child', parentSessionId: 'session-parent' }),
    'parentSessionId',
  );
  assert.equal(
    internalReasonFromSessionSummary({
      sessionId: 'session-child',
      projections: { values: { subagent: { identity: { mode: 'continuable', label: 'child' } } } },
    }),
    'subagent-projection',
  );
  assert.equal(
    internalReasonFromSessionSummary({ sessionId: '351ca07c-6d26-46a8-a685-cb627d642806' }),
    'id-heuristic',
  );
  assert.equal(
    internalReasonFromSessionSummary({ sessionId: 'session-1', origin: 'subagent', parentSessionId: 'p' }),
    'origin=subagent',
    'the strongest marker wins',
  );
  assert.equal(internalReasonFromSessionSummary(null), undefined);
  assert.equal(internalReasonFromSessionSummary({}), undefined);
});

test('header marker detection mirrors the persisted SessionHeader fields', () => {
  assert.equal(
    internalReasonFromSessionHeader({ id: 'session-1', version: 4, createdAt: 1, isSeeded: false, delegationDepth: 0 }),
    undefined,
  );
  assert.equal(internalReasonFromSessionHeader({ id: 'session-1', origin: 'subagent' }), 'origin=subagent');
  assert.equal(internalReasonFromSessionHeader({ id: 'session-child', parentSession: 'session-parent' }), 'parentSession');
  assert.equal(internalReasonFromSessionHeader({ id: 'session-child', delegationDepth: 2 }), 'delegationDepth');
  assert.equal(
    internalReasonFromSessionHeader({ id: '351ca07c-6d26-46a8-a685-cb627d642806' }),
    'id-heuristic',
  );
  assert.equal(internalReasonFromSessionHeader(null), undefined);
});

test('normalizePath is platform-aware (headless Linux keeps case-sensitive paths)', () => {
  // Separators unified and trailing separators dropped on every platform.
  assert.equal(normalizePath('C:\\\\work\\\\proj\\\\'), normalizePath('C:/work/proj'));
  assert.equal(normalizePath('/home/user/proj/'), '/home/user/proj');
  assert.equal(normalizePath('/home/user/proj//'), '/home/user/proj');
  assert.equal(normalizePath(''), '');
  assert.equal(normalizePath(null), '');

  if (process.platform === 'win32') {
    assert.equal(normalizePath('C:\\\\Work\\\\Proj'), 'c:/work/proj', 'Windows matching stays case-insensitive');
  } else {
    assert.equal(normalizePath('/Home/User/Proj'), '/Home/User/Proj', 'POSIX matching keeps case');
    assert.notEqual(normalizePath('/Home/User/Proj'), normalizePath('/home/user/proj'));
  }
});

test('sessionListCachePath is scoped to the host state file (multi-profile isolation)', () => {
  // Default: next to stateFile, so desktop and a headless web profile never share it.
  const config = resolveConfig({ stateFile: path.join('C:\\\\tmp', 'poc-home', 'state', 'host-state.json') });
  assert.equal(sessionListCachePath(config), path.join('C:\\\\tmp', 'poc-home', 'state', 'session-list-cache.json'));

  // Explicit path wins; false disables persistence.
  assert.equal(
    sessionListCachePath({ sessions: { cacheFile: path.join('C:\\\\tmp', 'custom.json') }, stateFile: 'x' }),
    path.join('C:\\\\tmp', 'custom.json'),
  );
  assert.equal(sessionListCachePath({ sessions: { cacheFile: false }, stateFile: 'x' }), null);

  // No stateFile: falls back to a process-unique temp path rather than a shared one.
  const fallback = sessionListCachePath({ sessions: {}, stateFile: '' });
  assert.equal(typeof fallback, 'string');
  assert.equal(fallback.includes(String(process.pid)), true);
});
