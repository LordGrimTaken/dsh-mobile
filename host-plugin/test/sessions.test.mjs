import test from 'node:test';
import assert from 'node:assert/strict';

import { ApiError } from '../lib/errors.js';
import { SESSION_TITLE_MAX_CHARS, normalizeSessionTitle } from '../lib/sessions.js';

test('normalizeSessionTitle trims a valid title', () => {
  assert.equal(normalizeSessionTitle('  New title  '), 'New title');
  assert.equal(normalizeSessionTitle('x'.repeat(SESSION_TITLE_MAX_CHARS)).length, SESSION_TITLE_MAX_CHARS);
});

test('normalizeSessionTitle rejects non-strings, blank and over-long titles', () => {
  for (const value of [undefined, null, 42, {}, '', '   ', 'x'.repeat(SESSION_TITLE_MAX_CHARS + 1)]) {
    assert.throws(
      () => normalizeSessionTitle(value),
      (error) => error instanceof ApiError && error.code === 'invalid_request' && error.status === 400,
    );
  }
});
