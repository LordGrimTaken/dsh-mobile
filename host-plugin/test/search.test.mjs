import test from 'node:test';
import assert from 'node:assert/strict';

import { ApiError } from '../lib/errors.js';
import {
  SEARCH_DEFAULT_LIMIT,
  SEARCH_MAX_OFFSET,
  SEARCH_MAX_QUERY_CHARS,
  SEARCH_ROLES,
  SEARCH_SNIPPET_CHARS,
  findFirstMatch,
  hasSearchFilters,
  makeSearchSnippet,
  normalizeSearchFilters,
  normalizeSearchLimit,
  normalizeSearchOffset,
  normalizeSearchQuery,
  normalizeSearchRoles,
  normalizeSearchTimestamp,
  matchesFromSemanticDocuments,
  paginateSearchHits,
  searchEvents,
} from '../lib/search.js';

const T = (offset = 0) => Date.UTC(2026, 9, 30, 9, 40, 0) + offset;

test('normalizeSearchQuery trims and bounds the query', () => {
  assert.equal(normalizeSearchQuery('  hello  '), 'hello');
  assert.equal(normalizeSearchQuery('x'.repeat(SEARCH_MAX_QUERY_CHARS)).length, SEARCH_MAX_QUERY_CHARS);
  for (const value of [undefined, null, 42, '', ' ', 'a', 'x'.repeat(SEARCH_MAX_QUERY_CHARS + 1)]) {
    assert.throws(
      () => normalizeSearchQuery(value),
      (error) => error instanceof ApiError && error.code === 'invalid_request' && error.status === 400,
    );
  }
});

test('normalizeSearchLimit defaults to 50 and accepts 1..100', () => {
  assert.equal(normalizeSearchLimit(undefined), SEARCH_DEFAULT_LIMIT);
  assert.equal(normalizeSearchLimit(null), SEARCH_DEFAULT_LIMIT);
  assert.equal(normalizeSearchLimit(''), SEARCH_DEFAULT_LIMIT);
  assert.equal(normalizeSearchLimit('10'), 10);
  assert.equal(normalizeSearchLimit(100), 100);
  for (const value of [0, 101, -1, 1.5, '1.5', 'abc', '0', '101']) {
    assert.throws(
      () => normalizeSearchLimit(value),
      (error) => error instanceof ApiError && error.code === 'invalid_request',
    );
  }
});

test('findFirstMatch is case-insensitive and makeSearchSnippet centers the match', () => {
  assert.equal(findFirstMatch('Hello World', 'WORLD'), 6);
  assert.equal(findFirstMatch('Hello', 'zzz'), -1);
  assert.equal(findFirstMatch('', 'a'), -1);

  const text = `${'x'.repeat(300)} needle ${'y'.repeat(300)}`;
  const snippet = makeSearchSnippet(text, 'needle');
  assert.equal(snippet.includes('needle'), true);
  assert.equal(snippet.startsWith('…'), true);
  assert.equal(snippet.endsWith('…'), true);
  assert.equal(snippet.length <= SEARCH_SNIPPET_CHARS + 2, true);

  const short = makeSearchSnippet('all 128 tests passed', 'tests');
  assert.equal(short, 'all 128 tests passed');
  assert.equal(makeSearchSnippet('nothing', 'zzz'), '');
});

test('searchEvents matches human user text, assistant text and tool detail', () => {
  const events = [
    { type: 'user/message', seq: 1, time: T(1000), data: { id: 'm_u', content: [{ type: 'text', text: 'Ship the patch' }], source: { kind: 'user' } } },
    {
      type: 'user/message',
      seq: 2,
      time: T(2000),
      data: { id: 'm_injected', content: [{ type: 'text', text: 'Ship injected context' }], source: { kind: 'file-change' } },
    },
    {
      type: 'assistant/message',
      seq: 3,
      time: T(3000),
      data: { message: { id: 'm_a', content: [{ type: 'text', text: 'I will ship the patch now' }] } },
    },
    { type: 'tool/call', seq: 4, time: T(4000), data: { callId: 'call_1', name: 'Pwsh', arguments: '{"command":"pwsh -File tools/ship.ps1"}' } },
  ];

  const found = searchEvents(events, 'SHIP');
  assert.equal(found.truncated, false);
  assert.deepEqual(
    found.matches.map((match) => [match.messageId, match.role]),
    [
      ['m_u', 'user'],
      ['m_a', 'assistant'],
      ['m_call_1', 'tool'],
    ],
  );
  assert.deepEqual(found.matches.map((match) => match.seq), [1, 3, 4], 'raw-event seq is carried on hits');
  assert.equal(found.matches[2].snippet.includes('ship.ps1'), true);
  assert.match(found.matches[0].snippet, /Ship the patch/);
  assert.equal(found.matches[0].time, T(1000));

  const toolOnly = searchEvents(events, 'ship.ps1');
  assert.deepEqual(toolOnly.matches.map((match) => match.messageId), ['m_call_1']);

  const injectedOnly = searchEvents(events, 'injected context');
  assert.equal(injectedOnly.matches.length, 0, 'injected user context is not searched');

  const noSeq = searchEvents(
    [{ type: 'user/message', time: T(0), data: { id: 'm_noseq', content: [{ type: 'text', text: 'needle no seq' }], source: { kind: 'user' } } }],
    'needle',
  );
  assert.equal('seq' in noSeq.matches[0], false, 'unknown event seq is omitted');
  assert.equal(noSeq.matches[0].messageId, 'm_noseq');
});

test('searchEvents excludes scheduled prompts and other internal sources but keeps human prompts', () => {
  const events = [
    {
      type: 'user/message',
      seq: 1,
      time: T(1000),
      data: {
        id: 'm_sched',
        content: [{ type: 'text', text: 'scheduled needle prompt' }],
        source: { kind: 'schedule' },
      },
    },
    {
      type: 'user/message',
      seq: 2,
      time: T(2000),
      data: {
        id: 'm_ctx',
        content: [{ type: 'text', text: 'runtime needle context' }],
        source: { kind: 'runtime-context' },
      },
    },
    {
      type: 'user/message',
      seq: 3,
      time: T(3000),
      data: {
        id: 'm_human',
        content: [{ type: 'text', text: 'human needle prompt' }],
        source: { kind: 'user' },
      },
    },
  ];
  const result = searchEvents(events, 'needle');
  assert.deepEqual(result.matches.map((match) => [match.messageId, match.role]), [['m_human', 'user']]);
  assert.equal(result.matches.some((match) => match.messageId === 'm_sched'), false);
  assert.equal(result.matches.some((match) => match.messageId === 'm_ctx'), false);
});

test('searchEvents dedupes message ids and reports bounds/deadline truncation', () => {
  const events = [
    { type: 'user/message', seq: 1, time: T(1000), data: { id: 'm_u', content: [{ type: 'text', text: 'needle one' }], source: { kind: 'user' } } },
    { type: 'user/message', seq: 2, time: T(2000), data: { id: 'm_u', content: [{ type: 'text', text: 'needle two' }], source: { kind: 'user' } } },
    { type: 'assistant/message', seq: 3, time: T(3000), data: { message: { id: 'm_a', content: [{ type: 'text', text: 'needle three' }] } } },
  ];
  const deduped = searchEvents(events, 'needle');
  assert.deepEqual(deduped.matches.map((match) => match.messageId), ['m_u', 'm_a']);
  assert.equal(deduped.truncated, false);

  const capped = searchEvents(events, 'needle', { maxEvents: 2 });
  assert.equal(capped.truncated, true);

  const expired = searchEvents(events, 'needle', { deadline: T(0), now: () => T(1000) });
  assert.equal(expired.matches.length, 0);
  assert.equal(expired.truncated, true);
});

test('normalizeSearchRoles accepts a comma-separated ordered list and rejects invalid roles', () => {
  assert.equal(normalizeSearchRoles(undefined), undefined);
  assert.equal(normalizeSearchRoles(''), undefined);
  assert.equal(normalizeSearchRoles(' , '), undefined);
  assert.deepEqual(normalizeSearchRoles('user'), ['user']);
  assert.deepEqual(normalizeSearchRoles('tool,user'), ['user', 'tool']);
  assert.deepEqual(normalizeSearchRoles('user,assistant,tool'), [...SEARCH_ROLES]);
  assert.deepEqual(normalizeSearchRoles('user, user'), ['user']);
  for (const value of ['system', 'user,root', 42, ['user', 7]]) {
    assert.throws(
      () => normalizeSearchRoles(value),
      (error) => error instanceof ApiError && error.code === 'invalid_request' && error.status === 400,
    );
  }
});

test('normalizeSearchTimestamp accepts ISO-8601 and epoch ms and canonicalizes to ISO', () => {
  assert.equal(normalizeSearchTimestamp(undefined, 'since'), undefined);
  assert.equal(normalizeSearchTimestamp('', 'since'), undefined);
  assert.deepEqual(normalizeSearchTimestamp('2026-10-30T09:40:00Z', 'since'), {
    ms: T(0),
    iso: '2026-10-30T09:40:00.000Z',
  });
  assert.deepEqual(normalizeSearchTimestamp('2026-10-30T11:40:00+02:00', 'since'), {
    ms: T(0),
    iso: '2026-10-30T09:40:00.000Z',
  });
  assert.deepEqual(normalizeSearchTimestamp(String(T(1000)), 'until'), {
    ms: T(1000),
    iso: '2026-10-30T09:40:01.000Z',
  });
  for (const value of ['not-a-date', '2026-13-40T99:99:99Z', 1.5, {}]) {
    assert.throws(
      () => normalizeSearchTimestamp(value, 'since'),
      (error) => error instanceof ApiError && error.code === 'invalid_request',
    );
  }
});

test('normalizeSearchFilters validates ranges and echoes only set filters', () => {
  const empty = normalizeSearchFilters();
  assert.deepEqual(empty, { echo: {} });
  assert.equal(hasSearchFilters(empty), false);

  const filtered = normalizeSearchFilters({
    role: 'assistant,user',
    since: '2026-10-30T09:40:00Z',
    until: String(T(10_000)),
  });
  assert.deepEqual(filtered, {
    roles: ['user', 'assistant'],
    since: T(0),
    until: T(10_000),
    echo: {
      role: ['user', 'assistant'],
      since: '2026-10-30T09:40:00.000Z',
      until: '2026-10-30T09:40:10.000Z',
    },
  });
  assert.equal(hasSearchFilters(filtered), true);

  // Normalized filters are idempotent (accept `roles` as well as raw `role`) so
  // the role echo cannot be dropped when an already-normalized object is passed
  // through a caller/adapter boundary.
  const normalizedAgain = normalizeSearchFilters(filtered);
  assert.deepEqual(normalizedAgain.roles, ['user', 'assistant']);
  assert.deepEqual(normalizedAgain.echo, filtered.echo);
  assert.deepEqual(
    normalizeSearchFilters(normalizeSearchFilters({ role: 'assistant' })).echo,
    { role: ['assistant'] },
  );

  assert.throws(
    () => normalizeSearchFilters({ since: T(10_000), until: T(0) }),
    (error) => error instanceof ApiError && error.code === 'invalid_request' && /since/.test(error.message),
  );
  assert.throws(
    () => normalizeSearchFilters({ until: 'not-a-date' }),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );
});

test('searchEvents applies role and inclusive date filters during the scan', () => {
  const events = [
    { type: 'user/message', seq: 1, time: T(1000), data: { id: 'm_u', content: [{ type: 'text', text: 'needle user' }], source: { kind: 'user' } } },
    { type: 'assistant/message', seq: 2, time: T(2000), data: { message: { id: 'm_a', content: [{ type: 'text', text: 'needle assistant' }] } } },
    { type: 'tool/call', seq: 3, time: T(3000), data: { callId: 'c1', name: 'Pwsh', arguments: '{"command":"needle tool"}' } },
    {
      type: 'user/message',
      seq: 4,
      data: { id: 'm_undated', content: [{ type: 'text', text: 'needle no timestamp' }], source: { kind: 'user' } },
    },
  ];

  assert.deepEqual(
    searchEvents(events, 'needle', { roles: ['user', 'assistant'] }).matches.map((match) => [match.messageId, match.role]),
    [['m_u', 'user'], ['m_a', 'assistant'], ['m_undated', 'user']],
  );
  assert.deepEqual(
    searchEvents(events, 'needle', { since: T(2000), until: T(3000) }).matches.map((match) => match.messageId),
    ['m_a', 'm_c1'],
    'date bounds are inclusive and exclude undated events',
  );
  assert.deepEqual(
    searchEvents(events, 'needle', { roles: ['tool'], since: T(3001) }).matches,
    [],
  );
  assert.deepEqual(
    searchEvents(events, 'needle', { roles: ['user'], since: T(0), until: T(500) }).matches,
    [],
    'undated events are excluded whenever any date bound is set',
  );
});

test('normalizeSearchOffset accepts 0..500 and rejects invalid values', () => {
  assert.equal(normalizeSearchOffset(undefined), 0);
  assert.equal(normalizeSearchOffset(null), 0);
  assert.equal(normalizeSearchOffset(''), 0);
  assert.equal(normalizeSearchOffset(0), 0);
  assert.equal(normalizeSearchOffset('2'), 2);
  assert.equal(normalizeSearchOffset(SEARCH_MAX_OFFSET), SEARCH_MAX_OFFSET);
  for (const value of [-1, SEARCH_MAX_OFFSET + 1, 1.5, '1.5', 'abc', '-2']) {
    assert.throws(
      () => normalizeSearchOffset(value),
      (error) => error instanceof ApiError && error.code === 'invalid_request' && error.status === 400,
    );
  }
});

test('paginateSearchHits slices after newest-first ordering and computes pagination', () => {
  const hits = Array.from({ length: 5 }, (_, index) => ({ messageId: `m_${index}` }));
  assert.deepEqual(paginateSearchHits(hits, { offset: 0, limit: 2 }), {
    hits: [hits[0], hits[1]],
    offset: 0,
    nextOffset: 2,
    hasMore: true,
    truncated: false,
  });
  assert.deepEqual(paginateSearchHits(hits, { offset: 2, limit: 2 }), {
    hits: [hits[2], hits[3]],
    offset: 2,
    nextOffset: 4,
    hasMore: true,
    truncated: false,
  });
  // Last page: no more collected matches → nextOffset is null.
  assert.deepEqual(paginateSearchHits(hits, { offset: 4, limit: 2 }), {
    hits: [hits[4]],
    offset: 4,
    nextOffset: null,
    hasMore: false,
    truncated: false,
  });
  assert.deepEqual(paginateSearchHits(hits, { offset: 3, limit: 2 }), {
    hits: [hits[3], hits[4]],
    offset: 3,
    nextOffset: null,
    hasMore: false,
    truncated: false,
  });
  // Offset beyond the matches: empty page, no more, no nextOffset (never loops).
  assert.deepEqual(paginateSearchHits(hits, { offset: 10, limit: 2 }), {
    hits: [],
    offset: 10,
    nextOffset: null,
    hasMore: false,
    truncated: false,
  });
  // Truncated scan + a non-empty page → there may be more beyond it.
  assert.deepEqual(paginateSearchHits(hits, { offset: 0, limit: 2, truncated: true }), {
    hits: [hits[0], hits[1]],
    offset: 0,
    nextOffset: 2,
    hasMore: true,
    truncated: true,
  });
  // H2: truncated scan + empty page never advertises hasMore/nextOffset.
  assert.deepEqual(paginateSearchHits(hits, { offset: 10, limit: 2, truncated: true }), {
    hits: [],
    offset: 10,
    nextOffset: null,
    hasMore: false,
    truncated: true,
  });
  const capped = paginateSearchHits(Array.from({ length: SEARCH_MAX_OFFSET }, (_, index) => index), {
    offset: SEARCH_MAX_OFFSET,
    limit: 10,
    truncated: true,
  });
  assert.deepEqual(capped, {
    hits: [],
    offset: SEARCH_MAX_OFFSET,
    nextOffset: null,
    hasMore: false,
    truncated: true,
  });
});

test('matchesFromSemanticDocuments maps lightweight docs with roles, filters and exact substring', () => {
  const documents = [
    { seq: 1, type: 'user/message', time: T(1000), text: 'Needle in the user prompt' },
    { seq: 2, type: 'assistant/message', time: T(2000), text: 'assistant needle answer' },
    { seq: 3, type: 'tool/call', time: T(3000), text: 'Pwsh\ngrep -r needle .' },
    { seq: 4, type: 'tool/result', time: T(4000), text: 'needle result is not searched' },
    { seq: 5, type: 'user/message', time: T(5000), text: 'injected needle context' },
  ];
  assert.deepEqual(
    matchesFromSemanticDocuments(documents, 'NEEDLE').map((match) => [match.messageId, match.role, match.seq]),
    [
      ['m_ev_1', 'user', 1],
      ['m_ev_2', 'assistant', 2],
      ['m_ev_3', 'tool', 3],
      ['m_ev_5', 'user', 5],
    ],
  );
  assert.deepEqual(
    matchesFromSemanticDocuments([{ type: 'user/message', time: T(0), text: 'needle no seq' }], 'needle').map(
      (match) => [match.messageId, 'seq' in match],
    ),
    [['m_ev_0', false]],
    'unknown semantic-document seq keeps the old id but omits seq',
  );
  assert.deepEqual(
    matchesFromSemanticDocuments(documents, 'needle', { roles: ['tool'] }).map((match) => match.role),
    ['tool'],
  );
  assert.deepEqual(
    matchesFromSemanticDocuments(documents, 'needle', { since: T(2000), until: T(3000) }).map((match) => match.messageId),
    ['m_ev_2', 'm_ev_3'],
  );
  // The exact substring semantics still apply after the whitespace-flexible
  // semantic prefilter (e.g. "need le" is not a match for "needle").
  assert.deepEqual(matchesFromSemanticDocuments([{ seq: 6, type: 'user/message', time: T(0), text: 'need le' }], 'needle'), []);
  assert.deepEqual(
    matchesFromSemanticDocuments([{ seq: 7, type: 'assistant/message', time: T(0), text: 'needle one two three' }], 'needle one')
      .map((match) => match.snippet),
    ['needle one two three'],
  );
});

test('matchesFromSemanticDocuments excludes scheduled-prompt framing without snapshots', () => {
  const documents = [
    {
      seq: 1,
      type: 'user/message',
      time: T(1000),
      text: '[SCHEDULE REMINDER]\nThis is a scheduled message from the user\nschedule_id_json: {"id":"sched_1"}\nneedle reminder text',
    },
    {
      seq: 2,
      type: 'user/message',
      time: T(2000),
      text: 'This is a scheduled message from the user\nneedle with alternate framing',
    },
    { seq: 3, type: 'user/message', time: T(3000), text: 'human needle prompt' },
    { seq: 4, type: 'assistant/message', time: T(4000), text: 'assistant needle reply' },
  ];
  assert.deepEqual(
    matchesFromSemanticDocuments(documents, 'needle').map((match) => [match.messageId, match.role]),
    [
      ['m_ev_3', 'user'],
      ['m_ev_4', 'assistant'],
    ],
    'scheduled framing is excluded; human user and assistant docs stay',
  );

  // Heuristic is case-insensitive and substring-based (documented trade-off: a
  // human prompt that literally includes the framing marker is also excluded).
  assert.deepEqual(
    matchesFromSemanticDocuments(
      [{ seq: 5, type: 'user/message', time: T(0), text: 'text [schedule reminder] needle' }],
      'needle',
    ),
    [],
  );
});
