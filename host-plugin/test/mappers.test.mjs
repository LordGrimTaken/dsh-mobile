import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { ApiError } from '../lib/errors.js';
import {
  buildMessages,
  collapseWhitespace,
  collectSessionAttachments,
  contentAttachments,
  extractText,
  fileMimeType,
  firstUserText,
  isoTime,
  mapGoalProjection,
  mapGoalSummary,
  mapHeaderSummary,
  mapSessionSummary,
  mapSessionJobs,
  mapSessionTeam,
  mapSessionTodos,
  mapSessionTrajectory,
  mapWorkspace,
  markdownImageReferences,
  messageAttachments,
  paginateMessages,
  previewFromEvents,
  readWorkspaceImage,
  redactTrajectorySecrets,
  redactTrajectoryPaths,
  remoteImageAttachments,
  remoteImageEntries,
  REMOTE_IMAGE_MAX_PER_MESSAGE,
  resolveWorkspaceImage,
  SESSION_TODO_CONTENT_MAX_CHARS,
  SESSION_TODOS_MAX_ITEMS,
  SESSION_TEAM_MAX_ACTIVE_TASKS,
  SESSION_TEAM_MAX_MEMBERS,
  SESSION_JOBS_MAX,
  SESSION_JOB_TEXT_MAX_CHARS,
  SESSION_JOB_DETAIL_MAX_CHARS,
  toolDetail,
  toolOutput,
  TOOL_OUTPUT_MAX_CHARS,
  TOOL_OUTPUT_TRUNCATION_MARKER,
  TRAJECTORY_DEFAULT_LIMIT,
  TRAJECTORY_MAX_LIMIT,
  TRAJECTORY_SUMMARY_MAX_CHARS,
  WORKSPACE_IMAGE_MAX_BYTES,
  workspaceImageAttachments,
} from '../lib/mappers.js';

const T = (offset = 0) => Date.UTC(2026, 9, 30, 9, 40, 0) + offset;

/** Raw DSH session events in the shapes appended by the agent loop. */
const EVENTS = [
  { type: 'turn/start', seq: 1, time: T(0), data: { turn: 1 } },
  {
    type: 'user/message',
    seq: 2,
    time: T(1000),
    data: { id: 'm_u1', role: 'user', content: [{ type: 'text', text: 'Ship the patch' }], source: { kind: 'user' } },
  },
  {
    type: 'assistant/message',
    seq: 3,
    time: T(2000),
    data: {
      turn: 1,
      step: 1,
      message: {
        id: 'm_a1',
        role: 'assistant',
        content: [
          { type: 'text', text: 'Running tests…' },
          { type: 'reasoning', text: 'thinking' },
        ],
        source: { kind: 'model', provider: 'deepseek', model: 'deepseek-flash' },
      },
      stream: [],
    },
  },
  {
    type: 'tool/call',
    seq: 4,
    time: T(3000),
    data: { turn: 1, step: 1, callId: 'call_1', name: 'Bash', arguments: '{"command":"pwsh -File tools/test.ps1 -Jobs 2"}' },
  },
  {
    type: 'tool/result',
    seq: 5,
    time: T(4000),
    data: {
      turn: 1,
      step: 1,
      message: {
        id: 'm_t1',
        role: 'tool',
        content: [{ type: 'text', text: 'ok' }],
        source: { kind: 'tool', callId: 'call_1' },
        toolCallId: 'call_1',
      },
    },
  },
  {
    type: 'assistant/message',
    seq: 6,
    time: T(5000),
    data: {
      turn: 1,
      step: 1,
      message: {
        id: 'm_a2',
        role: 'assistant',
        content: [{ type: 'text', text: 'All 128 tests passed' }],
        source: { kind: 'model', provider: 'deepseek', model: 'deepseek-flash' },
      },
      stream: [],
    },
  },
  { type: 'turn/end', seq: 7, time: T(6000), data: { turn: 1, reason: { kind: 'completed' } } },
];

test('isoTime renders contract ISO-8601 UTC without milliseconds', () => {
  assert.equal(isoTime(Date.UTC(2026, 9, 30, 9, 41, 12, 123)), '2026-10-30T09:41:12Z');
  assert.equal(isoTime(T(0)), '2026-10-30T09:40:00Z');
  assert.match(isoTime(undefined), /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
});

test('extractText concatenates text blocks and skips non-text blocks', () => {
  assert.equal(extractText([{ type: 'text', text: 'a' }, { type: 'reasoning', text: 'x' }, { type: 'text', text: 'b' }]), 'ab');
  assert.equal(extractText([{ type: 'image', attachment: {} }]), '');
  assert.equal(extractText(undefined), '');
});

test('toolDetail prefers the command-like argument and never throws on bad JSON', () => {
  assert.equal(toolDetail('{"command":"pwsh -File tools/test.ps1"}'), 'pwsh -File tools/test.ps1');
  assert.equal(toolDetail('{"description":"Read a file","path":"C:\\\\x"}'), 'Read a file');
  assert.equal(toolDetail('not json'), 'not json');
  assert.equal(toolDetail('{"count":3}'), '{"count":3}');
  assert.equal(toolDetail(''), '');
  assert.equal(toolDetail('{"command":"' + 'x'.repeat(500) + '"}').length, 200);
});

test('buildMessages folds a complete session into contract messages', () => {
  const messages = buildMessages(EVENTS);
  assert.deepEqual(
    messages.map((message) => [message.id, message.role]),
    [
      ['m_u1', 'user'],
      ['m_a1', 'assistant'],
      ['m_call_1', 'tool'],
      ['m_a2', 'assistant'],
    ],
  );
  assert.equal(messages[0].text, 'Ship the patch');
  assert.equal(messages[1].text, 'Running tests…');
  assert.equal(messages[2].text, '', 'tool rows carry no text');
  assert.deepEqual(messages[2].tool, {
    name: 'Bash',
    detail: 'pwsh -File tools/test.ps1 -Jobs 2',
    state: 'done',
    output: 'ok',
  });
  assert.equal(messages[0].createdAt, '2026-10-30T09:40:01Z');
  assert.equal(messages.every((message) => message.streaming === false), true);
});

test('buildMessages attaches durable event seq to user/assistant/tool rows', () => {
  const messages = buildMessages([
    {
      type: 'user/message',
      seq: 4,
      time: T(1000),
      data: { id: 'm_u', content: [{ type: 'text', text: 'fork here' }], source: { kind: 'user' } },
    },
    {
      type: 'assistant/message',
      seq: 6,
      time: T(2000),
      data: { message: { id: 'm_a', content: [{ type: 'text', text: 'done' }] } },
    },
    { type: 'tool/call', seq: 8, time: T(3000), data: { callId: 'c1', name: 'Pwsh', arguments: '{"command":"ls"}' } },
    {
      type: 'tool/result',
      seq: 9,
      time: T(4000),
      data: { message: { toolCallId: 'c1', content: [] } },
    },
    { type: 'tool/call', seq: 11, time: T(5000), data: { callId: 'c2', name: 'Bash', arguments: '{}' } },
  ]);

  assert.deepEqual(
    messages.map((message) => [message.id, message.role, message.seq]),
    [
      ['m_u', 'user', 4],
      ['m_a', 'assistant', 6],
      ['m_c1', 'tool', 9],
      ['m_c2', 'tool', 11],
    ],
  );

  const withoutSeq = buildMessages([
    {
      type: 'user/message',
      time: T(0),
      data: { id: 'm_no_seq', content: [{ type: 'text', text: 'no seq' }], source: { kind: 'user' } },
    },
  ]);
  assert.equal('seq' in withoutSeq[0], false, 'seq is optional/backward compatible');
});

test('buildMessages marks failed tool results and running (result-less) calls', () => {
  const failed = buildMessages([
    { type: 'tool/call', seq: 1, time: T(0), data: { callId: 'c1', name: 'Pwsh', arguments: '{"command":"rm x"}' } },
    {
      type: 'tool/result',
      seq: 2,
      time: T(1000),
      data: {
        message: { id: 'm_t', toolCallId: 'c1', isError: true, content: [] },
        error: { name: 'SandboxError', code: 'denied' },
      },
    },
  ]);
  assert.equal(failed[0].tool.state, 'failed');

  const running = buildMessages([
    { type: 'tool/call', seq: 1, time: T(0), data: { callId: 'c2', name: 'Bash', arguments: '{"command":"sleep 1"}' } },
  ]);
  assert.equal(running.length, 1);
  assert.equal(running[0].id, 'm_c2');
  assert.equal(running[0].streaming, true);
  assert.equal(running[0].tool.state, 'running');
});

test('buildMessages exposes image/file attachment metadata from content blocks', () => {
  const messages = buildMessages([
    {
      type: 'user/message',
      seq: 1,
      time: T(0),
      data: {
        id: 'm_att',
        role: 'user',
        content: [
          { type: 'text', text: 'Look at these' },
          {
            type: 'image',
            attachment: { attachmentId: 'att_img_1', mediaType: 'image/png', bytes: 1234, width: 64, height: 32, name: 'shot.png' },
          },
          { type: 'file', attachment: { attachmentId: 'att_file_1', name: 'report.pdf', bytes: 555 } },
        ],
        source: { kind: 'user' },
      },
    },
    {
      type: 'assistant/message',
      seq: 2,
      time: T(1000),
      data: {
        message: {
          id: 'm_att_a',
          role: 'assistant',
          content: [{ type: 'file', attachment: { attachmentId: 'att_file_2', name: 'data.bin', bytes: 9 } }],
          source: { kind: 'model' },
        },
      },
    },
  ]);

  assert.equal(messages[0].text, 'Look at these', 'text extraction is unchanged');
  assert.deepEqual(messages[0].attachments, [
    { id: 'att_img_1', kind: 'image', name: 'shot.png', mimeType: 'image/png', bytes: 1234, width: 64, height: 32 },
    { id: 'att_file_1', kind: 'file', name: 'report.pdf', mimeType: 'application/pdf', bytes: 555 },
  ]);
  assert.deepEqual(messages[1].attachments, [
    { id: 'att_file_2', kind: 'file', name: 'data.bin', mimeType: 'application/octet-stream', bytes: 9 },
  ]);

  const plain = buildMessages([
    { type: 'user/message', seq: 1, time: T(0), data: { id: 'm_plain', role: 'user', content: [{ type: 'text', text: 'hi' }], source: { kind: 'user' } } },
  ]);
  assert.equal('attachments' in plain[0], false, 'empty attachment lists are omitted');

  const defaults = buildMessages([
    {
      type: 'user/message',
      seq: 1,
      time: T(0),
      data: {
        id: 'm_defaults',
        role: 'user',
        content: [
          { type: 'image', attachment: { attachmentId: 'att_x' } },
          { type: 'file', attachment: { attachmentId: 'att_y' } },
        ],
        source: { kind: 'user' },
      },
    },
  ]);
  assert.deepEqual(defaults[0].attachments, [
    { id: 'att_x', kind: 'image', name: 'Image', mimeType: 'application/octet-stream', bytes: 0 },
    { id: 'att_y', kind: 'file', name: 'File', mimeType: 'application/octet-stream', bytes: 0 },
  ]);
});

test('tool result text becomes capped tool.output without leaking into other rows', () => {
  const call = (callId, name = 'Bash', args = '{"command":"ls"}') => ({
    type: 'tool/call',
    seq: 1,
    time: T(0),
    data: { callId, name, arguments: args },
  });
  const result = (callId, content, extra = {}) => ({
    type: 'tool/result',
    seq: 2,
    time: T(1000),
    data: { message: { id: 'm_res', toolCallId: callId, content, ...extra } },
  });

  const plain = buildMessages([
    call('c1'),
    result('c1', [{ type: 'text', text: 'line one\nline two' }]),
  ]);
  assert.equal(plain.length, 1);
  assert.equal(plain[0].text, '', 'result text never becomes the message text');
  assert.equal(plain[0].tool.output, 'line one\nline two');
  assert.equal(plain[0].tool.name, 'Bash');
  assert.equal(plain[0].tool.state, 'done');
  assert.equal(plain[0].tool.detail, 'ls');

  const json = buildMessages([call('c2'), result('c2', [{ type: 'text', text: '{"ok":true}' }])]);
  assert.equal(json[0].tool.output, '{"ok":true}');

  // Multiple text blocks are concatenated exactly like extractText; newlines
  // inside a block are preserved.
  const multiple = buildMessages([
    call('c3'),
    result('c3', [
      { type: 'text', text: 'first\n' },
      { type: 'image', attachment: { attachmentId: 'att_1' } },
      { type: 'text', text: 'second' },
    ]),
  ]);
  assert.equal(multiple[0].tool.output, 'first\nsecond');
  assert.equal('output' in multiple[0].attachments?.[0] ?? {}, false, 'attachment metadata only');

  // Empty / image-only results omit output entirely (and keep attachments).
  const empty = buildMessages([call('c4'), result('c4', [])]);
  assert.equal('output' in empty[0].tool, false);
  const imageOnly = buildMessages([
    call('c5'),
    result('c5', [{ type: 'image', attachment: { attachmentId: 'att_shot', mediaType: 'image/png', bytes: 5, name: 'shot.png' } }]),
  ]);
  assert.equal('output' in imageOnly[0].tool, false);
  assert.deepEqual(imageOnly[0].attachments, [
    { id: 'att_shot', kind: 'image', name: 'shot.png', mimeType: 'image/png', bytes: 5 },
  ]);

  // 8,000-char cap plus a clear truncation marker.
  const long = 'x'.repeat(TOOL_OUTPUT_MAX_CHARS + 500);
  const capped = buildMessages([call('c6'), result('c6', [{ type: 'text', text: long }])]);
  assert.equal(capped[0].tool.output.length, TOOL_OUTPUT_MAX_CHARS + TOOL_OUTPUT_TRUNCATION_MARKER.length);
  assert.equal(capped[0].tool.output.slice(0, TOOL_OUTPUT_MAX_CHARS), 'x'.repeat(TOOL_OUTPUT_MAX_CHARS));
  assert.equal(capped[0].tool.output.endsWith(TOOL_OUTPUT_TRUNCATION_MARKER), true);

  // Exact boundary is not truncated; helper returns empty for no text.
  const exact = buildMessages([call('c7'), result('c7', [{ type: 'text', text: 'y'.repeat(TOOL_OUTPUT_MAX_CHARS) }])]);
  assert.equal(exact[0].tool.output.length, TOOL_OUTPUT_MAX_CHARS);
  assert.equal(toolOutput([{ type: 'image' }]), '');
  assert.equal(toolOutput([{ type: 'text', text: 'a' }, { type: 'text', text: 'b' }]), 'ab');
});

test('content attachment helpers derive MIME types and session refs safely', () => {
  assert.equal(fileMimeType('a/b.pdf'), 'application/pdf');
  assert.equal(fileMimeType('NOTES.MD'), 'text/markdown');
  assert.equal(fileMimeType('archive.unknown'), 'application/octet-stream');
  assert.equal(fileMimeType('noext'), 'application/octet-stream');
  assert.equal(fileMimeType(undefined), 'application/octet-stream');

  const entries = contentAttachments([
    null,
    { type: 'text', text: 'ignored' },
    { type: 'image', attachment: { attachmentId: 'att_1', mediaType: 'image/jpeg', bytes: 4, width: 2, height: 3 } },
    { type: 'file', attachment: { attachmentId: 'att_2', name: 'x.zip', bytes: 8 } },
    { type: 'image', attachment: { attachmentId: '' } },
  ]);
  assert.deepEqual(entries.map((entry) => entry.id), ['att_1', 'att_2']);
  assert.deepEqual(messageAttachments([{ type: 'image', attachment: { attachmentId: 'att_1', mediaType: 'image/jpeg', bytes: 4 } }]), [
    { id: 'att_1', kind: 'image', name: 'Image', mimeType: 'image/jpeg', bytes: 4 },
  ]);
  assert.equal('ref' in messageAttachments([{ type: 'file', attachment: { attachmentId: 'att_2', name: 'x.zip', bytes: 8 } }])[0], false);

  const refs = collectSessionAttachments([
    {
      type: 'user/message',
      seq: 1,
      time: T(0),
      data: { id: 'm1', content: [{ type: 'image', attachment: { attachmentId: 'att_1', mediaType: 'image/png', bytes: 1 } }], source: { kind: 'user' } },
    },
    {
      type: 'user/message',
      seq: 2,
      time: T(1000),
      data: {
        id: 'm2',
        content: [{ type: 'file', attachment: { attachmentId: 'att_injected', name: 'secret.txt', bytes: 2 } }],
        source: { kind: 'file-change' },
      },
    },
    {
      type: 'assistant/message',
      seq: 3,
      time: T(2000),
      data: { message: { id: 'm3', content: [{ type: 'file', attachment: { attachmentId: 'att_2', name: 'x.bin', bytes: 3 } }] } },
    },
  ]);
  assert.deepEqual([...refs.keys()], ['att_1', 'att_2'], 'only human user + assistant messages contribute refs');
  assert.equal(refs.get('att_1').kind, 'image');
  assert.equal(refs.get('att_2').ref.attachmentId, 'att_2');
  assert.equal(collectSessionAttachments([]).size, 0);
});


test('previewFromEvents and firstUserText use the latest human-visible text', () => {
  assert.equal(previewFromEvents(EVENTS), 'All 128 tests passed');
  assert.equal(previewFromEvents(EVENTS, 10), 'All 128 t…');
  assert.equal(previewFromEvents([]), '');
  assert.equal(firstUserText(EVENTS), 'Ship the patch');
  assert.equal(collapseWhitespace('  a\n\n b\tc '), 'a b c');
});

test('paginateMessages returns the newest page and a backward cursor', () => {
  const messages = ['a', 'b', 'c', 'd', 'e'].map((id, index) => ({
    id,
    role: 'user',
    text: id,
    createdAt: isoTime(T(index * 1000)),
    streaming: false,
  }));

  const newest = paginateMessages(messages, { limit: 2 });
  assert.deepEqual(newest.messages.map((message) => message.id), ['d', 'e']);
  assert.equal(newest.nextCursor, 'd');

  const older = paginateMessages(messages, { limit: 2, before: 'd' });
  assert.deepEqual(older.messages.map((message) => message.id), ['b', 'c']);
  assert.equal(older.nextCursor, 'b');

  const first = paginateMessages(messages, { limit: 2, before: 'b' });
  assert.deepEqual(first.messages.map((message) => message.id), ['a']);
  assert.equal(first.nextCursor, null);

  const all = paginateMessages(messages, { limit: 'not-a-number' });
  assert.equal(all.messages.length, 5);
  assert.equal(all.nextCursor, null);

  assert.throws(
    () => paginateMessages(messages, { limit: 2, before: 'nope' }),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );
});

test('synthetic agent-injected user-role context is not shown as a human message', () => {
  const injected = {
    type: 'user/message',
    seq: 3,
    time: T(3000),
    data: {
      id: 'm_injected',
      role: 'user',
      content: [{ type: 'text', text: '# AGENTS.md contents…' }],
      source: { kind: 'file-change' },
    },
  };
  const messages = buildMessages([EVENTS[1], injected, EVENTS[2]]);
  assert.deepEqual(messages.map((message) => message.id), ['m_u1', 'm_a1']);
  assert.equal(firstUserText([injected, ...EVENTS]), 'Ship the patch');
  assert.equal(previewFromEvents([injected]), '');
});

test('schedule user messages are visible with a source marker; all other sources stay hidden', () => {
  const imageRef = { attachmentId: 'att_sched', mediaType: 'image/png', bytes: 4, width: 2, height: 2, name: 'shot.png' };
  const schedule = {
    type: 'user/message',
    seq: 2,
    time: T(2000),
    data: {
      id: 'm_schedule',
      role: 'user',
      content: [
        { type: 'text', text: 'Run the scheduled report' },
        { type: 'image', attachment: imageRef },
      ],
      source: { kind: 'schedule' },
    },
  };
  const internalKinds = [
    'runtime-context',
    'skill-catalog',
    'time-context',
    'agent-message',
    'goal',
    'subagent-settled',
    'compact-checkpoint',
    'tool-reminder',
    'agent-instructions',
    'file-change',
  ];
  const hidden = internalKinds.map((kind, index) => ({
    type: 'user/message',
    seq: 100 + index,
    time: T(1000 + index),
    data: {
      id: `m_hidden_${index}`,
      role: 'user',
      content: [{ type: 'text', text: `hidden ${kind}` }],
      source: { kind },
    },
  }));
  const ordinary = {
    type: 'user/message',
    seq: 1,
    time: T(1000),
    data: {
      id: 'm_human',
      role: 'user',
      content: [{ type: 'text', text: 'Human prompt' }],
      source: { kind: 'user' },
    },
  };
  const legacy = {
    type: 'user/message',
    seq: 3,
    time: T(3000),
    data: { id: 'm_legacy', role: 'user', content: [{ type: 'text', text: 'Legacy prompt' }] },
  };

  const messages = buildMessages([...hidden, ordinary, schedule, legacy]);
  assert.deepEqual(messages.map((message) => [message.id, message.role]), [
    ['m_human', 'user'],
    ['m_schedule', 'user'],
    ['m_legacy', 'user'],
  ]);
  assert.equal(messages[0].text, 'Human prompt');
  assert.equal('source' in messages[0], false, 'ordinary user message keeps the old shape');
  assert.equal(messages[1].text, 'Run the scheduled report');
  assert.equal(messages[1].source, 'schedule');
  assert.deepEqual(messages[1].attachments, [
    { id: 'att_sched', kind: 'image', name: 'shot.png', mimeType: 'image/png', bytes: 4, width: 2, height: 2 },
  ]);
  assert.equal('source' in messages[2], false, 'legacy/source-less user message keeps the old shape');
  // Attachment collectors follow the visible transcript rule: scheduled-message
  // refs are discoverable for the delivered transcript, never surfaced as list
  // preview/search content.
  const collected = collectSessionAttachments([schedule]);
  assert.equal(collected.has('att_sched'), true);
  assert.equal(collected.get('att_sched').kind, 'image');

  // Previews and title fallbacks are strict human-only: scheduled operational
  // text must not leak into session-list surfaces.
  assert.equal(previewFromEvents([schedule]), '');
  assert.equal(firstUserText([schedule]), '');
  assert.equal(previewFromEvents([...hidden, schedule]), '');
  assert.equal(firstUserText([...hidden, schedule]), '');
  assert.equal(previewFromEvents([...hidden, ordinary]), 'Human prompt');
});

test('mapSessionSummary produces the exact contract row', () => {
  const row = mapSessionSummary({
    summary: {
      sessionId: 's_1',
      updatedAt: Date.UTC(2026, 9, 30, 9, 41, 12),
      running: true,
      agentAvailable: true,
      cwd: 'C:\\work',
    },
    title: 'Cut a patch release',
    workspaceId: 'ws_1',
    preview: 'All 128 tests passed…',
  });
  assert.deepEqual(row, {
    id: 's_1',
    title: 'Cut a patch release',
    workspaceId: 'ws_1',
    status: 'working',
    preview: 'All 128 tests passed…',
    updatedAt: '2026-10-30T09:41:12Z',
    unread: false,
    live: true,
  });

  const idle = mapSessionSummary({
    summary: { sessionId: 's_2', updatedAt: T(0), running: false, agentAvailable: false },
  });
  assert.equal(idle.status, 'idle');
  assert.equal(idle.live, false);
  assert.equal(idle.workspaceId, null, 'unresolvable workspace is null, not omitted');
  assert.equal(idle.title, 'Untitled session');
  assert.equal(idle.preview, '');

  const withGoal = mapSessionSummary({ summary: { sessionId: 's_3' }, goal: null });
  assert.equal(withGoal.goal, null);
  assert.equal('goal' in mapSessionSummary({ summary: { sessionId: 's_4' } }), false);
});

test('mapSessionSummary adds optional lineage/preset fields and omits unknowns', () => {
  const base = { sessionId: 's_child', updatedAt: T(0), running: false, agentAvailable: false };
  const withLineage = mapSessionSummary({
    summary: base,
    title: 'Child',
    parentSessionId: 'session-parent',
    agentPreset: 'reviewer',
  });
  assert.deepEqual(
    { ...withLineage, updatedAt: undefined },
    {
      id: 's_child',
      title: 'Child',
      workspaceId: null,
      status: 'idle',
      preview: '',
      updatedAt: undefined,
      unread: false,
      live: false,
      parentSessionId: 'session-parent',
      agentPreset: 'reviewer',
    },
  );

  for (const bad of [undefined, null, '', '   ', 42]) {
    const row = mapSessionSummary({ summary: base, parentSessionId: bad, agentPreset: bad });
    assert.equal('parentSessionId' in row, false, `parent ${String(bad)} omitted`);
    assert.equal('agentPreset' in row, false, `preset ${String(bad)} omitted`);
  }
  const trimmed = mapSessionSummary({ summary: base, parentSessionId: '  p  ', agentPreset: '  r  ' });
  assert.equal(trimmed.parentSessionId, 'p');
  assert.equal(trimmed.agentPreset, 'r');
  const selfParent = mapSessionSummary({ summary: base, parentSessionId: 's_child' });
  assert.equal('parentSessionId' in selfParent, false, 'self-parent links are dropped');

  const header = mapHeaderSummary(
    { id: 's_h', createdAt: T(0), cwd: 'C:\\work', parentSession: 'session-parent', agentPreset: 'coder' },
    { parentSessionId: 'session-parent', agentPreset: 'coder' },
  );
  assert.equal(header.parentSessionId, 'session-parent');
  assert.equal(header.agentPreset, 'coder');
  assert.equal('parentSessionId' in mapHeaderSummary({ id: 's_plain', createdAt: T(0) }), false);
});

test('mapSessionSummary exposes aggregate delegation counts and omits empty/invalid', () => {
  const base = { sessionId: 's_parent', updatedAt: T(0), running: false, agentAvailable: false };
  const withDelegation = mapSessionSummary({ summary: base, delegation: { total: 3, working: 2 } });
  assert.deepEqual(withDelegation.delegation, { total: 3, working: 2 });
  assert.equal('delegation' in mapSessionSummary({ summary: base, delegation: { total: 0, working: 0 } }), false);
  assert.equal('delegation' in mapSessionSummary({ summary: base, delegation: null }), false);
  assert.equal('delegation' in mapSessionSummary({ summary: base, delegation: { total: -1, working: 0 } }), false);
  assert.equal('delegation' in mapSessionSummary({ summary: base, delegation: 'nope' }), false);
  const clamped = mapSessionSummary({ summary: base, delegation: { total: 2, working: 5 } });
  assert.deepEqual(clamped.delegation, { total: 2, working: 2 }, 'working never exceeds total');
  const noWorking = mapSessionSummary({ summary: base, delegation: { total: 2, working: 0 } });
  assert.deepEqual(noWorking.delegation, { total: 2, working: 0 });
  assert.equal(JSON.stringify(withDelegation).includes('child'), false);
});

test('mapGoalSummary projects objective/phase/rounds/limits or null', () => {
  assert.equal(mapGoalSummary(undefined), null);
  assert.equal(mapGoalSummary(null), null);
  assert.equal(mapGoalSummary('nope'), null);
  assert.deepEqual(mapGoalSummary({}), {
    objective: '',
    phase: 'active',
    roundsStarted: 0,
    maxGoalRounds: 0,
  });
  assert.deepEqual(mapGoalSummary({ objective: '  Ship the goal  ', phase: 'paused', roundsStarted: 3, maxGoalRounds: 10 }), {
    objective: 'Ship the goal',
    phase: 'paused',
    roundsStarted: 3,
    maxGoalRounds: 10,
  });
  assert.equal(mapGoalSummary({ objective: 42 }).objective, '', 'non-string objectives are empty');
  assert.equal(mapGoalSummary({ objective: `  ${'x'.repeat(700)}  ` }).objective.length, 500, 'objective is capped at 500 chars');
  assert.deepEqual(mapGoalSummary({ phase: 'active' }), {
    objective: '',
    phase: 'active',
    roundsStarted: 0,
    maxGoalRounds: 0,
  });
  assert.deepEqual(mapGoalSummary({ phase: 7, roundsStarted: '2', maxGoalRounds: '9' }), {
    objective: '',
    phase: 'active',
    roundsStarted: 2,
    maxGoalRounds: 9,
  });
});

test('mapGoalProjection maps the durable goal session projection', () => {
  assert.equal(mapGoalProjection(undefined), null);
  assert.equal(mapGoalProjection(null), null, 'pre-create / cleared projection is null');
  assert.equal(mapGoalProjection('nope'), null);
  assert.equal(mapGoalProjection({}), null);
  assert.equal(mapGoalProjection({ goal: null }), null);

  const projection = {
    goal: { id: 'goal-1', revision: 2, objective: 'Cold projected goal', phase: 'paused', maxGoalRounds: 40 },
    roundsStarted: 3,
    createdAt: T(1000),
    updatedAt: T(2000),
  };
  assert.deepEqual(mapGoalProjection(projection), {
    objective: 'Cold projected goal',
    phase: 'paused',
    roundsStarted: 3,
    maxGoalRounds: 40,
  });
  assert.equal(mapGoalProjection({ goal: { objective: 'x' } }).roundsStarted, 0);
  assert.equal(mapGoalProjection({ goal: { objective: `  ${'y'.repeat(600)}  ` } }).objective.length, 500);
});

test('mapHeaderSummary and mapWorkspace match the contract shapes', () => {
  assert.deepEqual(mapHeaderSummary({ id: 's_9', createdAt: T(0), cwd: 'C:\\x' }), {
    id: 's_9',
    title: 'Untitled session',
    workspaceId: null,
    status: 'idle',
    preview: '',
    updatedAt: '2026-10-30T09:40:00Z',
    unread: false,
    live: false,
  });
  assert.deepEqual(mapWorkspace({ id: 'ws_1', title: 'DeepSeek Harness', path: 'C:\\.dsh' }), {
    id: 'ws_1',
    name: 'DeepSeek Harness',
    path: 'C:\\.dsh',
  });
  assert.deepEqual(mapWorkspace({ id: 'ws_2' }), { id: 'ws_2', name: 'ws_2', path: '' });
});

test('tool result image/file blocks become message attachments and are readable', () => {
  const imageRef = {
    attachmentId: 'sha256:abc123',
    mediaType: 'image/png',
    bytes: 60160,
    width: 1080,
    height: 1920,
    name: 'screenshot.png',
  };
  const events = [
    {
      type: 'tool/call',
      seq: 1,
      time: T(0),
      data: { turn: 1, step: 1, callId: 'call_shot', name: 'screenshot', arguments: '{}' },
    },
    {
      type: 'tool/result',
      seq: 2,
      time: T(1000),
      data: {
        turn: 1,
        step: 1,
        message: {
          id: 'm_t',
          role: 'tool',
          content: [
            { type: 'text', text: 'captured' },
            {
              type: 'image',
              attachment: imageRef,
            },
            {
              type: 'file',
              attachment: {
                attachmentId: 'sha256:def456',
                bytes: 12,
                name: 'report.txt',
              },
            },
          ],
          source: { kind: 'tool', callId: 'call_shot' },
          toolCallId: 'call_shot',
        },
      },
    },
  ];
  const toolMessage = buildMessages(events).find((message) => message.role === 'tool');
  assert.ok(toolMessage);
  assert.equal(toolMessage.attachments.length, 2);
  assert.equal(toolMessage.attachments[0].kind, 'image');
  assert.equal(toolMessage.attachments[0].name, 'screenshot.png');
  assert.equal(toolMessage.attachments[0].width, 1080);
  assert.equal(toolMessage.attachments[1].kind, 'file');
  assert.equal(toolMessage.attachments[1].mimeType, 'text/plain');

  const refs = collectSessionAttachments(events);
  assert.ok(refs.has('sha256:abc123'));
  assert.equal(refs.get('sha256:abc123').kind, 'image');
  assert.ok(refs.has('sha256:def456'));
});

/** @param {import('node:test').TestContext} t */
function makeWorkspace(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-ws-images-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const cwd = path.join(root, 'project');
  const artifacts = path.join(root, 'artifacts');
  const outside = path.join(root, 'outside.png');
  fs.mkdirSync(cwd, { recursive: true });
  fs.mkdirSync(artifacts, { recursive: true });
  const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0]);
  fs.writeFileSync(path.join(cwd, 'shot.png'), png);
  fs.writeFileSync(path.join(cwd, 'shot one.png'), Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2]));
  fs.writeFileSync(path.join(cwd, 'notes.txt'), 'not an image');
  fs.writeFileSync(path.join(artifacts, 'out.jpg'), Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0, 0]));
  fs.writeFileSync(outside, png);
  return { root, cwd, artifacts, outside };
}

test('markdownImageReferences accepts local image links only', () => {
  const text = [
    'Shot: ![a](shot.png) and ![b](<shot one.png>)',
    '![dup](shot.png) ![dup2](shot.png)',
    '![pct](shot%20one.png) ![title](shot.png "title")',
    '![remote](https://example.com/x.png) ![file](file:///C:/x.png)',
    '![data](data:image/png;base64,AAAA) ![txt](notes.txt)',
    '![up](../outside.png) ![nested](sub/dir/pic.webp)',
  ].join('\n');
  assert.deepEqual(markdownImageReferences(text), [
    'shot.png',
    'shot one.png',
    '../outside.png',
    'sub/dir/pic.webp',
  ]);
  // Duplicates collapse; only the first 8 distinct local matches are processed.
  assert.deepEqual(markdownImageReferences('![x](shot.png)![y](shot.png)'), ['shot.png']);
  assert.deepEqual(markdownImageReferences('![bad](%E0%A4%A) ![none]()'), []);
  assert.deepEqual(markdownImageReferences(undefined), []);
  assert.equal(
    markdownImageReferences(
      Array.from({ length: 12 }, (_, index) => `![i${index}](shot${index}.png)`).join(' '),
    ).length,
    8,
  );
});

test('workspace images resolve inside cwd or configured roots and are rejected everywhere else', (t) => {
  const { root, cwd, artifacts } = makeWorkspace(t);
  const options = { cwd, artifactRoots: [artifacts] };

  const relative = resolveWorkspaceImage('shot.png', options);
  assert.equal(relative.kind, 'image');
  assert.equal(relative.name, 'shot.png');
  assert.equal(relative.mimeType, 'image/png');
  assert.equal(relative.bytes, 12);
  assert.equal(relative.id.startsWith('ws_'), true);
  assert.equal(relative.ref.workspacePath, fs.realpathSync(path.join(cwd, 'shot.png')));
  assert.equal(relative.ref.allowedRoot, fs.realpathSync(cwd));
  assert.equal(relative.ref.configuredRoot, false);

  assert.equal(resolveWorkspaceImage('shot one.png', options).name, 'shot one.png');
  assert.equal(resolveWorkspaceImage(encodeURIComponent('shot one.png'), options).name, 'shot one.png');
  assert.equal(resolveWorkspaceImage('shot%20one.png', options).name, 'shot one.png');
  const absolute = resolveWorkspaceImage(path.join(artifacts, 'out.jpg'), options);
  assert.equal(absolute.mimeType, 'image/jpeg');
  assert.equal(absolute.ref.configuredRoot, true);
  assert.equal(resolveWorkspaceImage('sub/../shot.png', options), null, '.. is always rejected');
  assert.equal(resolveWorkspaceImage('../outside.png', options), null);
  assert.equal(resolveWorkspaceImage('%2e%2e/outside.png', options), null);
  assert.equal(resolveWorkspaceImage('notes.txt', options), null);
  assert.equal(resolveWorkspaceImage('missing.png', options), null);
  assert.equal(resolveWorkspaceImage('https://example.com/x.png', options), null);
  assert.equal(resolveWorkspaceImage('C:\\Windows\\x.png', options), null, 'absolute outside roots');
  assert.equal(resolveWorkspaceImage('C:foo.png', options), null, 'drive-relative');
  assert.equal(resolveWorkspaceImage('\\\\server\\share\\x.png', options), null, 'UNC');
  assert.equal(resolveWorkspaceImage('//host/share/x.png', options), null, 'protocol-relative UNC');
  assert.equal(resolveWorkspaceImage('\\\\?\\C:\\Windows\\x.png', options), null, 'device path');
  assert.equal(resolveWorkspaceImage('shot.png:stream', options), null, 'ADS stream');
  assert.equal(resolveWorkspaceImage('shot.png%20', options), null, 'encoded trailing space');
  assert.equal(resolveWorkspaceImage('shot.png.', options), null, 'trailing dot');
  assert.equal(resolveWorkspaceImage(path.join(root, 'outside.png'), options), null);

  // C:\foo must not match C:\foobar by string prefix.
  const artifactsExtra = `${artifacts}-extra`;
  fs.mkdirSync(artifactsExtra, { recursive: true });
  const extraImage = path.join(artifactsExtra, 'sibling.png');
  fs.writeFileSync(extraImage, Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]));
  assert.equal(resolveWorkspaceImage(extraImage, options), null, 'sibling root prefix is not inside');

  // Zero-byte and non-image magic are rejected even with an image extension.
  fs.writeFileSync(path.join(cwd, 'empty.png'), Buffer.alloc(0));
  assert.equal(resolveWorkspaceImage('empty.png', options), null);
  fs.writeFileSync(path.join(cwd, 'fake.png'), 'not really a png');
  assert.equal(resolveWorkspaceImage('fake.png', options), null, 'magic bytes must match extension');

  // A symlink inside cwd pointing outside must not escape containment.
  const link = path.join(cwd, 'link.png');
  try {
    fs.symlinkSync(path.join(root, 'outside.png'), link, 'file');
    assert.equal(resolveWorkspaceImage('link.png', options), null, 'symlink escape rejected');
  } catch (error) {
    if (error?.code !== 'EPERM' && error?.code !== 'EACCES' && error?.code !== 'ENOSYS') throw error;
    t.diagnostic('symlink creation unavailable on this host; symlink escape case skipped');
  }

  // Oversized file (sparse, cheap to create) is rejected by the 25 MB limit.
  const large = path.join(cwd, 'huge.png');
  const fd = fs.openSync(large, 'w');
  fs.ftruncateSync(fd, WORKSPACE_IMAGE_MAX_BYTES + 1);
  fs.closeSync(fd);
  assert.equal(resolveWorkspaceImage('huge.png', options), null);
});

test('buildMessages/collectSessionAttachments add synthetic workspace images without host paths', (t) => {
  const { root, cwd, artifacts } = makeWorkspace(t);
  const options = { cwd, artifactRoots: [artifacts] };
  const events = [
    {
      type: 'user/message',
      seq: 1,
      time: T(1000),
      data: {
        id: 'm_user',
        role: 'user',
        content: [
          {
            type: 'text',
            text: 'Look ![shot](shot.png) ![again](shot.png) ![outside](../outside.png) ![remote](https://x/y.png)',
          },
        ],
        source: { kind: 'user' },
      },
    },
    {
      type: 'assistant/message',
      seq: 2,
      time: T(2000),
      data: {
        message: {
          id: 'm_assistant',
          content: [{ type: 'text', text: `Absolute ![out](${path.join(artifacts, 'out.jpg')})` }],
        },
      },
    },
    {
      type: 'tool/call',
      seq: 3,
      time: T(3000),
      data: { callId: 'c1', name: 'Tool', arguments: '{"path":"shot.png"}' },
    },
    {
      type: 'tool/result',
      seq: 4,
      time: T(4000),
      data: {
        message: {
          id: 'm_result',
          toolCallId: 'c1',
          content: [{ type: 'text', text: 'Generated ![result](shot%20one.png)' }],
        },
      },
    },
  ];

  const messages = buildMessages(events, options);
  assert.equal(messages.length, 3);
  assert.equal(messages[0].attachments.length, 1, 'dedupe per message, outside/remote ignored');
  assert.deepEqual(messages[0].attachments[0], {
    id: resolveWorkspaceImage('shot.png', options).id,
    kind: 'image',
    name: 'shot.png',
    mimeType: 'image/png',
    bytes: 12,
  });
  assert.equal(messages[1].attachments[0].name, 'out.jpg');
  assert.equal(messages[2].attachments[0].name, 'shot one.png');
  // The original Markdown text is preserved; attachment metadata never carries a
  // host path (the message text itself is user/assistant content).
  assert.match(messages[0].text, /!\[shot\]\(shot\.png\)/);
  assert.match(messages[1].text, /!\[out\]/);
  const attachmentJson = JSON.stringify(messages.flatMap((message) => message.attachments ?? []));
  assert.equal(attachmentJson.includes(root), false);
  assert.equal(attachmentJson.includes('workspacePath'), false);
  assert.equal(attachmentJson.includes('allowedRoot'), false);
  assert.equal('attachments' in buildMessages(events)[0], false, 'options are required');

  const refs = collectSessionAttachments(events, options);
  const userRef = refs.get(messages[0].attachments[0].id);
  assert.equal(userRef.kind, 'image');
  assert.equal(userRef.ref.workspacePath, fs.realpathSync(path.join(cwd, 'shot.png')));
  assert.equal(userRef.ref.allowedRoot, fs.realpathSync(cwd));
  assert.equal(refs.has(messages[2].attachments[0].id), true, 'tool-result markdown is collected');
  const absoluteRef = refs.get(messages[1].attachments[0].id);

  const loaded = readWorkspaceImage(userRef.ref, options);
  assert.equal(Buffer.from(loaded.bytes).toString('hex'), '89504e470d0a1a0a00000000');
  assert.equal(loaded.mimeType, 'image/png');
  assert.equal(readWorkspaceImage({ ...userRef.ref, allowedRoot: path.join(root, 'outside') }, options), null);
  assert.equal(readWorkspaceImage({ workspacePath: path.join(cwd, 'missing.png'), allowedRoot: cwd }, options), null);
  assert.equal(readWorkspaceImage({ workspacePath: userRef.ref.workspacePath }, options), null);
  assert.equal(workspaceImageAttachments('![x](shot.png)', options)[0].id.startsWith('ws_'), true);

  // Removing the configured root stops serving absolute refs immediately.
  assert.equal(readWorkspaceImage(absoluteRef.ref, { cwd, artifactRoots: [artifacts] }).bytes.length > 0, true);
  assert.equal(readWorkspaceImage(absoluteRef.ref, { cwd, artifactRoots: [] }), null);
  assert.equal(readWorkspaceImage(absoluteRef.ref, { cwd, artifactRoots: [path.join(root, 'other')] }), null);
  // Relative refs stay confined to cwd and are not affected by root config.
  assert.equal(readWorkspaceImage(userRef.ref, { cwd, artifactRoots: [] }).bytes.length > 0, true);
});

test('workspace image caps bound per-message matches and per-session refs', (t) => {
  const { cwd, artifacts } = makeWorkspace(t);
  const options = { cwd, artifactRoots: [artifacts] };
  const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0]);
  for (let index = 0; index < 40; index += 1) {
    fs.writeFileSync(path.join(cwd, `cap${index}.png`), png);
  }

  // One message processes at most 8 matches even when the text has more.
  const single = buildMessages(
    [
      {
        type: 'user/message',
        seq: 1,
        time: T(0),
        data: {
          id: 'm_cap',
          content: [{ type: 'text', text: Array.from({ length: 12 }, (_, i) => `![i${i}](cap${i}.png)`).join(' ') }],
          source: { kind: 'user' },
        },
      },
    ],
    options,
  );
  assert.equal(single[0].attachments.length, 8);

  // Collection includes every synthetic ref across messages (per-message cap only).
  const events = Array.from({ length: 5 }, (_, messageIndex) => ({
    type: 'user/message',
    seq: messageIndex + 1,
    time: T(messageIndex * 1000),
    data: {
      id: `m_cap_${messageIndex}`,
      content: [
        {
          type: 'text',
          text: Array.from(
            { length: 8 },
            (_, refIndex) => `![i${messageIndex}-${refIndex}](cap${messageIndex * 8 + refIndex}.png)`,
          ).join(' '),
        },
      ],
      source: { kind: 'user' },
    },
  }));
  const refs = collectSessionAttachments(events, options);
  const synthetic = [...refs.values()].filter((entry) => entry.ref?.configuredRoot === false);
  assert.equal(synthetic.length, 40);
});

test('remote http(s) images become ext_ metadata only when enabled', (t) => {
  const { cwd, artifacts } = makeWorkspace(t);
  const disabledOptions = { cwd, artifactRoots: [artifacts] };
  const enabledOptions = { ...disabledOptions, remoteImages: true };
  const urls = [
    'https://cdn.example.com/a.png?x=1',
    'https://cdn.example.com/b.JPG',
    'http://cdn.example.com/c.webp',
    'https://cdn.example.com/d.gif',
    'https://cdn.example.com/e.png',
    'https://cdn.example.com/a.png?x=1',
  ];
  const text = [
    'Local ![shot](shot.png)',
    ...urls.map((url, index) => `remote${index} ![r${index}](${url})`),
    'ignored ![f](file:///C:/x.png) ![d](data:image/png;base64,AA) ![svg](https://cdn.example.com/x.svg)',
  ].join(' ');
  const event = {
    type: 'user/message',
    seq: 1,
    time: T(0),
    data: { id: 'm_remote', role: 'user', content: [{ type: 'text', text }], source: { kind: 'user' } },
  };

  const disabled = buildMessages([event], disabledOptions);
  assert.equal(disabled[0].attachments.length, 1);
  assert.equal(disabled[0].attachments[0].kind, 'image');
  assert.equal(disabled[0].attachments[0].id.startsWith('ws_'), true);

  const enabled = buildMessages([event], enabledOptions);
  const ext = enabled[0].attachments.filter((entry) => entry.id.startsWith('ext_'));
  assert.equal(ext.length, REMOTE_IMAGE_MAX_PER_MESSAGE, 'per-message cap');
  for (const entry of ext) {
    assert.deepEqual(Object.keys(entry).sort(), ['bytes', 'id', 'kind', 'mimeType', 'name']);
    assert.equal(entry.kind, 'image');
    assert.equal(entry.bytes, 0, 'metadata only; bytes are fetched on read');
    assert.equal(/^ext_[0-9a-f]{16}$/u.test(entry.id), true);
    assert.equal(entry.name.includes('/'), false);
    assert.equal(entry.name.includes('cdn.example.com'), false);
  }
  assert.equal(ext.some((entry) => entry.name === 'a.png'), true);
  assert.equal(ext.some((entry) => entry.name === 'b.JPG' && entry.mimeType === 'image/jpeg'), true);
  const attachmentJson = JSON.stringify(enabled[0].attachments);
  assert.equal(attachmentJson.includes('http'), false);
  assert.equal(attachmentJson.includes('cdn.example.com'), false);
  assert.equal(attachmentJson.includes(cwd), false);

  // The same URL produces the same id (dedupe before caps).
  assert.equal(
    remoteImageEntries('![x](https://cdn.example.com/a.png?x=1)', enabledOptions)[0].id,
    remoteImageEntries('![y](https://cdn.example.com/a.png?x=1)', enabledOptions)[0].id,
  );
  assert.deepEqual(remoteImageEntries('![x](https://cdn.example.com/a.png)', disabledOptions), []);
  assert.deepEqual(remoteImageAttachments('![x](https://cdn.example.com/a.png)', enabledOptions)[0].ref, undefined);

  // Collection includes every remote ref across messages (per-message cap only).
  const events = Array.from({ length: 5 }, (_, messageIndex) => ({
    type: 'user/message',
    seq: messageIndex + 1,
    time: T(messageIndex * 1000),
    data: {
      id: `m_remote_${messageIndex}`,
      content: [
        {
          type: 'text',
          text: Array.from(
            { length: 4 },
            (_, refIndex) => `![r${messageIndex}-${refIndex}](https://cdn.example.com/${messageIndex}-${refIndex}.png)`,
          ).join(' '),
        },
      ],
      source: { kind: 'user' },
    },
  }));
  const collected = collectSessionAttachments(events, enabledOptions);
  assert.equal(
    [...collected.values()].filter((entry) => entry.id.startsWith('ext_')).length,
    20,
  );
  const ref = [...collected.values()].find((entry) => entry.id.startsWith('ext_'));
  assert.match(ref.ref.remoteUrl, /^https:\/\/cdn\.example\.com\//u);
  // The local workspace feature still works alongside remote refs.
  assert.equal([...collectSessionAttachments([event], enabledOptions).values()].some((entry) => entry.id.startsWith('ws_')), true);

  // C4 privacy: remote refs are user/assistant-only; tool-result text never
  // becomes a fetchable ext ref (local ws_ in tool results is unchanged).
  const assistantRemote = buildMessages(
    [
      {
        type: 'assistant/message',
        seq: 2,
        time: T(2000),
        data: {
          message: {
            id: 'm_assistant_remote',
            content: [{ type: 'text', text: 'Render ![chart](https://cdn.example.com/chart.png)' }],
          },
        },
      },
    ],
    enabledOptions,
  );
  assert.equal(assistantRemote[0].attachments[0].id.startsWith('ext_'), true);

  const toolEvents = [
    { type: 'tool/call', seq: 3, time: T(3000), data: { callId: 'c_remote', name: 'Fetch', arguments: '{}' } },
    {
      type: 'tool/result',
      seq: 4,
      time: T(4000),
      data: {
        message: {
          id: 'm_tool_remote',
          toolCallId: 'c_remote',
          content: [{ type: 'text', text: 'fetched ![evil](https://cdn.example.com/evil.png)' }],
        },
      },
    },
  ];
  const toolMessages = buildMessages(toolEvents, enabledOptions);
  assert.equal(toolMessages.length, 1);
  assert.equal('attachments' in toolMessages[0], false, 'tool-result text never yields ext refs');
  assert.equal(
    [...collectSessionAttachments(toolEvents, enabledOptions).values()].some((entry) => entry.id.startsWith('ext_')),
    false,
  );
});

test('mapSessionTodos projects the latest todo/write with sanitization, statuses and counts', () => {
  const older = {
    type: 'todo/write',
    seq: 1,
    time: T(1000),
    data: { todos: [{ content: 'stale', status: 'pending' }] },
  };
  const latest = {
    type: 'todo/write',
    seq: 2,
    time: T(2000),
    data: {
      todos: [
        { content: '  first\n\t task  ', status: 'pending' },
        { content: 'second task', status: 'in_progress' },
        { content: 'done task', status: 'completed' },
        { content: 'odd status', status: 'blocked' },
        { content: '   ', status: 'pending' },
        { content: 'control\u0000content', status: 'completed' },
      ],
    },
  };
  const result = mapSessionTodos([older, latest]);
  assert.equal(result.supported, true);
  assert.equal(result.updatedAt, '2026-10-30T09:40:02Z');
  assert.deepEqual(result.todos, [
    { content: 'first task', status: 'pending' },
    { content: 'second task', status: 'in_progress' },
    { content: 'done task', status: 'completed' },
    { content: 'odd status', status: 'pending' },
    { content: 'control content', status: 'completed' },
  ]);
  assert.deepEqual(result.counts, { pending: 2, inProgress: 1, completed: 2 });

  // The latest event wins even when it is empty; no event → supported:false.
  const cleared = mapSessionTodos([older, { type: 'todo/write', seq: 3, time: T(3000), data: { todos: [] } }]);
  assert.equal(cleared.supported, true);
  assert.deepEqual(cleared.todos, []);
  assert.deepEqual(cleared.counts, { pending: 0, inProgress: 0, completed: 0 });
  assert.equal(cleared.updatedAt, '2026-10-30T09:40:03Z');

  assert.deepEqual(mapSessionTodos([]), {
    supported: false,
    counts: { pending: 0, inProgress: 0, completed: 0 },
    todos: [],
  });
  assert.deepEqual(mapSessionTodos([{ type: 'user/message', data: { todos: [{ content: 'x' }] } }]), {
    supported: false,
    counts: { pending: 0, inProgress: 0, completed: 0 },
    todos: [],
  });

  // Content cap, no dedupe, 50-item cap (counts reflect returned items).
  const long = mapSessionTodos([{ type: 'todo/write', data: { todos: [{ content: 'x'.repeat(600), status: 'pending' }] } }]);
  assert.equal(long.todos[0].content.length, SESSION_TODO_CONTENT_MAX_CHARS);
  const duplicates = mapSessionTodos([
    { type: 'todo/write', data: { todos: [{ content: 'same', status: 'pending' }, { content: 'same', status: 'completed' }] } },
  ]);
  assert.equal(duplicates.todos.length, 2, 'items are not deduped');
  const many = mapSessionTodos([
    {
      type: 'todo/write',
      data: { todos: Array.from({ length: 70 }, (_, index) => ({ content: `todo ${index}`, status: 'pending' })) },
    },
  ]);
  assert.equal(many.todos.length, SESSION_TODOS_MAX_ITEMS);
  assert.equal(many.counts.pending, SESSION_TODOS_MAX_ITEMS);
  // No todo/write event at all, or a malformed todos field, never throws.
  assert.deepEqual(mapSessionTodos([{ type: 'todo/write', data: { todos: 'nope' } }]), {
    supported: true,
    counts: { pending: 0, inProgress: 0, completed: 0 },
    todos: [],
  });
});

test('mapSessionTeam folds latest team/task and team/member events with sanitized safe fields', () => {
  const teamTask = (id, revision, subject, status, extra = {}) => ({
    type: 'team/task',
    seq: revision,
    time: T(revision * 1000),
    data: {
      task: {
        id,
        revision,
        subject,
        description: 'SECRET description must never leak',
        status,
        ownerId: 'agent-internal-owner',
        writeScopes: ['host-plugin/lib/secret.js'],
        blockedBy: extra.blockedBy ?? [],
      },
    },
  });
  const teamMember = (id, name, phase, revision = 1) => ({
    type: 'team/member',
    seq: revision,
    time: T(revision * 1000),
    data: {
      member: {
        id,
        name,
        description: 'SECRET member description',
        provider: 'openai',
        context: 'secret context',
        phase,
      },
    },
  });

  const events = [
    teamTask('task-1', 1, 'old subject', 'pending'),
    teamMember('member-a', 'old name', 'provisioning', 1),
    teamTask('task-2', 1, 'second task', 'in_progress'),
    teamMember('member-a', 'Worker A', 'working', 2),
    teamMember('member-b', '  Worker\n  B  ', 'idle', 2),
    // Latest revision wins even when it is blocked and has safe blockedBy ids.
    teamTask('task-1', 3, '  updated\n subject  ', 'blocked', { blockedBy: ['task-2', 'task-2', ''] }),
    teamTask('task-3', 2, 'done task', 'completed'),
    teamTask('task-4', 1, 'odd status task', 'mystery'),
  ];

  const board = mapSessionTeam(events);
  assert.equal(board.supported, true);
  assert.deepEqual(board.members, [
    { name: 'Worker B', phase: 'idle' },
    { name: 'Worker A', phase: 'working' },
  ]);
  assert.deepEqual(board.tasks, [
    { id: 'task-1', subject: 'updated subject', status: 'blocked', blockedBy: ['task-2'] },
    { id: 'task-4', subject: 'odd status task', status: 'pending' },
    { id: 'task-2', subject: 'second task', status: 'in_progress' },
  ]);
  assert.deepEqual(board.counts, { pending: 1, inProgress: 1, blocked: 1, completed: 1 });
  const json = JSON.stringify(board);
  for (const secret of [
    'SECRET',
    'agent-internal-owner',
    'host-plugin/lib/secret.js',
    'openai',
    'secret context',
    'member-a',
    'member-b',
  ]) {
    assert.equal(json.includes(secret), false, `${secret} must not leak`);
  }

  // A later blank/unsafe revision removes a previously valid listing; blank
  // subjects/names are never listed.
  const cleared = mapSessionTeam([
    teamTask('task-1', 1, 'visible', 'pending'),
    teamMember('member-a', 'Visible Member', 'working'),
    { type: 'team/task', seq: 9, data: { task: { id: 'task-1', revision: 9, subject: '   ', status: 'pending' } } },
    { type: 'team/member', seq: 9, data: { member: { id: 'member-a', name: '   ', phase: 'idle' } } },
    teamTask('task-2', 2, '', 'pending'),
    teamMember('member-b', '', 'idle', 2),
  ]);
  assert.equal(cleared.supported, true);
  assert.deepEqual(cleared.tasks, []);
  assert.deepEqual(cleared.members, []);
  assert.deepEqual(cleared.counts, { pending: 0, inProgress: 0, blocked: 0, completed: 0 });

  // Caps and text limits.
  const manyEvents = [];
  for (let index = 0; index < 70; index += 1) {
    manyEvents.push(teamTask(`task-${index}`, index + 1, `task ${index} ${'x'.repeat(400)}`, 'pending'));
  }
  for (let index = 0; index < 60; index += 1) {
    manyEvents.push(teamMember(`member-${index}`, `member ${index} ${'y'.repeat(200)}`, 'working', index + 1));
  }
  const capped = mapSessionTeam(manyEvents);
  assert.equal(capped.tasks.length, SESSION_TEAM_MAX_ACTIVE_TASKS);
  assert.equal(capped.members.length, SESSION_TEAM_MAX_MEMBERS);
  assert.equal(capped.counts.pending, 70, 'counts cover all folded tasks, not just the listed page');
  assert.equal(capped.tasks[0].subject.length, 200);
  assert.equal(capped.members[0].name.length, 120);
  // Highest revision first.
  assert.equal(capped.tasks[0].id, 'task-69');
  assert.equal(capped.members[0].name.startsWith('member 59'), true);

  // No team events at all, or unrelated events, → supported:false + zero shape.
  const empty = {
    supported: false,
    counts: { pending: 0, inProgress: 0, blocked: 0, completed: 0 },
    members: [],
    tasks: [],
  };
  assert.deepEqual(mapSessionTeam([]), empty);
  assert.deepEqual(mapSessionTeam([{ type: 'user/message', data: {} }]), empty);
  assert.deepEqual(
    mapSessionTeam([{ type: 'team/task', data: { task: { id: 'task-1', revision: 1, subject: 'ok', status: 'pending' } } }]).supported,
    true,
  );
});

test('mapSessionTrajectory maps allowlisted summaries, sanitizes and paginates newest-first', () => {
  const longText = `human prompt needle ${'word '.repeat(100)}`;
  const events = [
    { type: 'user/message', seq: 1, time: T(1000), data: { id: 'm_u', content: [{ type: 'text', text: '\u0000human\n  prompt needle' }], source: { kind: 'user' } } },
    {
      type: 'user/message',
      seq: 2,
      time: T(2000),
      data: {
        id: 'm_ctx',
        content: [{ type: 'text', text: 'SECRET_INTERNAL runtime context body' }],
        source: { kind: 'runtime-context' },
      },
    },
    { type: 'user/message', seq: 3, time: T(3000), data: { id: 'm_sched', content: [{ type: 'text', text: 'scheduled prompt text' }], source: { kind: 'schedule' } } },
    { type: 'assistant/message', seq: 4, time: T(4000), data: { message: { id: 'm_a', content: [{ type: 'text', text: 'assistant reply' }] } } },
    { type: 'tool/call', seq: 5, time: T(5000), data: { callId: 'c1', name: 'Pwsh', arguments: '{"command":"echo hello"}' } },
    {
      type: 'tool/result',
      seq: 6,
      time: T(6000),
      data: { message: { toolCallId: 'c1', isError: true, content: [] }, error: { code: 'denied' } },
    },
    {
      type: 'tool/result',
      seq: 7,
      time: T(7000),
      data: { message: { toolCallId: 'c2', content: [{ type: 'text', text: 'all good' }] } },
    },
    { type: 'turn/start', seq: 8, time: T(8000), data: { turn: 2 } },
    { type: 'turn/end', seq: 9, time: T(9000), data: { turn: 2, reason: 'done' } },
    { type: 'step/start', seq: 10, time: T(10000), data: { step: 1 } },
    { type: 'step/end', seq: 11, time: T(11000), data: { step: 1, reason: 'completed' } },
    { type: 'goal/change', seq: 12, time: T(12000), data: { phase: 'working', objective: 'SECRET_GOAL' } },
    {
      type: 'todo/write',
      seq: 13,
      time: T(13000),
      data: {
        todos: [
          { content: 'one', status: 'pending' },
          { content: 'two', status: 'in_progress' },
          { content: 'three', status: 'completed' },
        ],
      },
    },
    { type: 'team/task', seq: 14, time: T(14000), data: { task: { id: 'task-191', subject: 'Write tests', status: 'in_progress', description: 'SECRET_TASK' } } },
    { type: 'team/member', seq: 15, time: T(15000), data: { member: { id: 'member-secret-id', name: 'Worker One', phase: 'working', provider: 'SECRET_PROVIDER' } } },
    { type: 'schedule/deliver', seq: 16, time: T(16000), data: { reason: 'SECRET_SCHEDULE' } },
    { type: 'custom/weird', seq: 17, time: T(17000), data: { prompt: 'SECRET_RAW', attachment: { bytes: 'SECRET_BYTES' } } },
    { type: 'ignored/no-seq', data: { text: 'SECRET_NO_SEQ' } },
    { type: 'user/message', seq: 1, time: T(18000), data: { id: 'm_long', content: [{ type: 'text', text: longText }], source: { kind: 'user' } } },
  ];

  const page = mapSessionTrajectory(events);
  assert.equal(page.supported, true);
  assert.equal(page.hasMore, false);
  assert.equal(page.nextBeforeSeq, null);
  assert.deepEqual(page.events.map((event) => event.seq), [17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1]);
  // Duplicate seq keeps the last event; both are newest-first and strictly
  // decreasing.
  for (let index = 1; index < page.events.length; index += 1) {
    assert.equal(page.events[index].seq < page.events[index - 1].seq, true);
  }
  const bySeq = new Map(page.events.map((event) => [event.seq, event]));
  assert.equal(bySeq.get(1).summary.startsWith('human prompt needle word word'), true);
  assert.equal(bySeq.get(1).summary.length, TRAJECTORY_SUMMARY_MAX_CHARS);
  assert.equal(bySeq.get(2).summary, '[context] runtime-context');
  assert.equal(bySeq.get(3).summary, 'scheduled prompt text');
  assert.equal(bySeq.get(4).summary, 'assistant reply');
  assert.match(bySeq.get(5).summary, /^Pwsh: .*echo hello/);
  assert.equal(bySeq.get(6).summary, 'failed');
  assert.equal(bySeq.get(6).status, 'failed');
  assert.equal(bySeq.get(7).summary, 'all good');
  assert.equal(bySeq.get(7).status, 'done');
  assert.equal(bySeq.get(8).summary, 'turn 2 started');
  assert.equal(bySeq.get(9).summary, 'turn 2 ended (done)');
  assert.equal(bySeq.get(10).summary, 'step 1 started');
  assert.equal(bySeq.get(11).summary, 'step 1 ended (completed)');
  assert.equal(bySeq.get(12).summary, 'goal working');
  assert.equal(bySeq.get(13).summary, 'todos: 1 pending, 1 in progress, 1 completed');
  assert.equal(bySeq.get(14).summary, 'task-191: Write tests [in_progress]');
  assert.equal(bySeq.get(15).summary, 'Worker One (working)');
  assert.equal(bySeq.get(16).summary, 'schedule deliver');
  assert.equal(bySeq.get(17).summary, 'custom/weird');
  assert.equal(bySeq.get(1).type, 'user/message');
  assert.equal(bySeq.get(1).time, '2026-10-30T09:40:18Z');
  for (const event of page.events) {
    assert.equal('data' in event, false, 'raw payloads are never copied');
    assert.equal('content' in event, false);
    assert.equal('arguments' in event, false);
  }
  const json = JSON.stringify(page);
  for (const secret of [
    'SECRET_INTERNAL',
    'SECRET_GOAL',
    'SECRET_TASK',
    'SECRET_PROVIDER',
    'SECRET_SCHEDULE',
    'SECRET_RAW',
    'SECRET_BYTES',
    'SECRET_NO_SEQ',
    'member-secret-id',
  ]) {
    assert.equal(json.includes(secret), false, `${secret} must not leak into the trajectory`);
  }

  // Pagination is newest-first, beforeSeq exclusive and stable across pages.
  const first = mapSessionTrajectory(events, { limit: 5 });
  assert.deepEqual(first.events.map((event) => event.seq), [17, 16, 15, 14, 13]);
  assert.equal(first.hasMore, true);
  assert.equal(first.nextBeforeSeq, 13);
  const second = mapSessionTrajectory(events, { limit: 5, beforeSeq: 13 });
  assert.deepEqual(second.events.map((event) => event.seq), [12, 11, 10, 9, 8]);
  assert.equal(second.hasMore, true);
  assert.equal(second.nextBeforeSeq, 8);
  const third = mapSessionTrajectory(events, { limit: 5, beforeSeq: 8 });
  assert.deepEqual(third.events.map((event) => event.seq), [7, 6, 5, 4, 3]);
  assert.equal(third.nextBeforeSeq, 3);
  const fourth = mapSessionTrajectory(events, { limit: 5, beforeSeq: 3 });
  assert.deepEqual(fourth.events.map((event) => event.seq), [2, 1]);
  assert.equal(fourth.hasMore, false);
  assert.equal(fourth.nextBeforeSeq, null);
  assert.deepEqual(
    [...first.events, ...second.events, ...third.events, ...fourth.events].map((event) => event.seq),
    page.events.map((event) => event.seq),
    'pages tile the complete newest-first stream',
  );
  assert.deepEqual(mapSessionTrajectory(events, { beforeSeq: 1 }).events, []);
  assert.equal(mapSessionTrajectory(events, { beforeSeq: 1 }).hasMore, false);

  // Limits/params.
  assert.equal(mapSessionTrajectory(events, { limit: TRAJECTORY_DEFAULT_LIMIT }).events.length, page.events.length);
  assert.equal(mapSessionTrajectory(events, { limit: TRAJECTORY_MAX_LIMIT }).events.length, page.events.length);
  for (const bad of [0, -1, 1.5, 'abc', TRAJECTORY_MAX_LIMIT + 1]) {
    assert.throws(
      () => mapSessionTrajectory(events, { limit: bad }),
      (error) => error instanceof ApiError && error.code === 'invalid_request' && error.status === 400,
    );
  }
  for (const bad of [-1, 1.5, 'abc']) {
    assert.throws(
      () => mapSessionTrajectory(events, { beforeSeq: bad }),
      (error) => error instanceof ApiError && error.code === 'invalid_request' && error.status === 400,
    );
  }

  assert.deepEqual(mapSessionTrajectory([]), {
    supported: false,
    events: [],
    nextBeforeSeq: null,
    hasMore: false,
  });
  assert.deepEqual(
    mapSessionTrajectory([{ type: 'user/message', data: { content: [{ type: 'text', text: 'no seq' }] } }]),
    { supported: false, events: [], nextBeforeSeq: null, hasMore: false },
  );
});

test('trajectory summaries redact credentials and absolute paths but keep commands usable', () => {
  const base64 = 'A'.repeat(120);
  const raw =
    'C:\\dev\\repo\\src\\index.ts npm test -- --watch ' +
    'sk-abcdefgh12345678 Bearer abcdef123456 Authorization: Bearer authsecret ' +
    `token=supersecret api_key=abc123 password=hunter2 secret=s3cr3t ${base64}`;
  const redacted = redactTrajectorySecrets(raw);
  assert.equal(redacted.includes('npm test -- --watch'), true, 'normal commands stay usable');
  // Absolute paths are replaced after the credential pass; command words remain.
  assert.match(redactTrajectoryPaths('C:\\dev\\repo\\src\\index.ts'), /^<path>$/);
  assert.match(redactTrajectoryPaths('\\\\server\\share\\secret.txt'), /^<path>$/);
  for (const hostPath of ['/home/user/app.py', '/Users/user/app.py', '/root/.ssh/id_rsa', '/etc/passwd', '/var/log/x', '/tmp/x', '/opt/tool', '/mnt/drive/x']) {
    const redactedPath = redactTrajectoryPaths(`open ${hostPath} now`);
    assert.equal(redactedPath.includes(hostPath), false, hostPath);
    assert.equal(redactedPath.includes('<path>'), true, hostPath);
  }
  assert.equal(redactTrajectoryPaths('git status --short'), 'git status --short');
  assert.equal(redactTrajectoryPaths('see https://example.com/home/page'), 'see https://example.com/home/page');
  for (const secret of [
    'sk-abcdefgh12345678',
    'abcdef123456',
    'authsecret',
    'supersecret',
    'abc123',
    'hunter2',
    's3cr3t',
    base64,
  ]) {
    assert.equal(redacted.includes(secret), false, `${secret} must be redacted`);
  }
  assert.match(redacted, /<redacted>/);
  assert.match(redacted, /Authorization: <redacted>/);
  assert.match(redacted, /Bearer <redacted>/);
  assert.match(redacted, /token=<redacted>/);
  assert.match(redacted, /password=<redacted>/);

  // Provider-prefixed tokens and credential env names (crafted fake values).
  const fakeCloudflare = 'cfat_FAKE1234567890abcdef';
  const providerRaw = [
    `CLOUDFLARE_API_TOKEN=${fakeCloudflare}`,
    '_API_TOKEN: "fake api token value"',
    'MY_SECRET=supersecret DB_PASSWORD="hunter 2" AWS_ACCESS_KEY_ID=AKIAAAAAAAAAAAAAAAAA',
    'ghp_FAKE1234567890 github_pat_FAKE1234567890 glpat-FAKE1234567890 xoxb-FAKE1234567890',
    'AIzaFAKE1234567890 AKIAFAKE1234567890 ya29.FAKE1234567890 npm_FAKE1234567890 cf_FAKE1234567890 pk_FAKE1234567890 rk_FAKE1234567890',
    'JWT eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmYWtlIn0.fakesignature123',
    'git status --path C:\\dev\\repo\\file.ts',
  ].join(' | ');
  const providerRedacted = redactTrajectorySecrets(providerRaw);
  for (const secret of [
    fakeCloudflare,
    'cfat_',
    'ghp_FAKE1234567890',
    'github_pat_FAKE1234567890',
    'glpat-FAKE1234567890',
    'xoxb-FAKE1234567890',
    'AIzaFAKE1234567890',
    'AKIAFAKE1234567890',
    'ya29.FAKE1234567890',
    'npm_FAKE1234567890',
    'cf_FAKE1234567890',
    'pk_FAKE1234567890',
    'rk_FAKE1234567890',
    'eyJhbGciOiJIUzI1NiJ9',
    'supersecret',
    'hunter 2',
    'fake api token value',
    'AKIAAAAAAAAAAAAAAAAA',
  ]) {
    assert.equal(providerRedacted.includes(secret), false, `${secret} must be redacted`);
  }
  assert.match(providerRedacted, /CLOUDFLARE_API_TOKEN=<redacted>/);
  assert.match(providerRedacted, /_API_TOKEN: <redacted>/);
  assert.equal(providerRedacted.includes('git status --path C:\\dev\\repo\\file.ts'), true, 'credentials pass leaves commands/paths for the path pass');

  // Trajectory integration: the endpoint mapper runs this shared redactor and
  // then the absolute-path pass before the 300-char cap.
  const secretTrajectory = mapSessionTrajectory([
    {
      type: 'tool/call',
      seq: 30,
      time: T(0),
      data: {
        name: 'Pwsh',
        arguments: JSON.stringify({
          command: `echo CLOUDFLARE_API_TOKEN=${fakeCloudflare} && git status C:\\dev\\repo\\file.ts`,
        }),
      },
    },
  ]);
  const secretSummary = secretTrajectory.events[0].summary;
  assert.equal(secretSummary.includes(fakeCloudflare), false);
  assert.equal(secretSummary.includes('cfat_'), false);
  assert.equal(secretSummary.includes('C:\\dev'), false);
  assert.equal(secretSummary.includes('git status'), true);
  assert.match(secretSummary, /<redacted>/);
  assert.match(secretSummary, /<path>/);

  // Tool call/result summaries (and arbitrary summary sources) pass through the
  // same redaction before the 300-char cap.
  const longPrefix = 'x'.repeat(280);
  const page = mapSessionTrajectory([
    {
      type: 'tool/call',
      seq: 1,
      time: T(0),
      data: {
        name: 'Pwsh',
        arguments: JSON.stringify({
          command:
            'git status --path C:\\dev\\repo\\file.ts token=sk-abcdefgh12345678 password=hunter2',
        }),
      },
    },
    {
      type: 'tool/result',
      seq: 2,
      time: T(1000),
      data: {
        message: {
          toolCallId: 'c1',
          content: [{ type: 'text', text: `${longPrefix} Bearer abcdef123456 ${base64} all 128 tests passed` }],
        },
      },
    },
    {
      type: 'assistant/message',
      seq: 3,
      time: T(2000),
      data: { message: { content: [{ type: 'text', text: 'Authorization: Bearer authsecret done' }] } },
    },
  ]);
  const call = page.events.find((event) => event.seq === 1);
  assert.match(call.summary, /^Pwsh:/);
  assert.equal(call.summary.includes('git status'), true);
  assert.equal(call.summary.includes('--path <path>'), true);
  assert.equal(call.summary.includes('C:\\dev'), false);
  assert.equal(call.summary.includes('sk-abcdefgh12345678'), false);
  assert.equal(call.summary.includes('hunter2'), false);
  assert.match(call.summary, /<redacted>/);
  const result = page.events.find((event) => event.seq === 2);
  assert.equal(result.summary.includes('abcdef123456'), false);
  assert.equal(result.summary.includes(base64), false);
  assert.equal(result.summary.includes('all 128 tests passed'), true);
  assert.equal(result.summary.length <= 300, true);
  const assistant = page.events.find((event) => event.seq === 3);
  assert.equal(assistant.summary.includes('authsecret'), false);
  assert.match(assistant.summary, /Authorization: <redacted>/);
});

test('mapSessionJobs folds registered/settled jobs with statuses, ordering and safe text', () => {
  const jobEvent = (type, seq, data) => ({ type, seq, time: T(seq * 1000), data });
  const events = [
    jobEvent('job/registered', 1, {
      jobId: 'pwsh-1',
      owner: 'owner-secret',
      kind: 'pwsh',
      label: 'Long job',
      startedAt: T(1000),
      detail: 'starting',
      toolCall: { id: 'call-secret' },
    }),
    jobEvent('job/registered', 2, {
      jobId: 'pwsh-2',
      owner: 'owner-secret',
      kind: 'shell',
      label: 'Bearer abcdef123456 token=supersecret',
      startedAt: T(1500),
      toolCall: { id: 'call-secret' },
    }),
    jobEvent('job/settled', 3, {
      jobId: 'pwsh-1',
      owner: 'owner-secret',
      status: 'completed',
      finishedAt: T(3000),
      detail: `done ${'word '.repeat(200)}`,
    }),
    jobEvent('job/settled', 4, {
      jobId: 'pwsh-2',
      status: 'failed',
      finishedAt: T(4000),
      detail: 'boom',
    }),
    jobEvent('job/registered', 5, { jobId: 'pwsh-3', kind: 'pwsh', label: 'running one', startedAt: T(5000) }),
    jobEvent('job/registered', 6, { jobId: 'pwsh-4', kind: 'pwsh', label: 'running two', startedAt: T(6000) }),
    jobEvent('job/settled', 7, { jobId: 'pwsh-5', status: 'stopped', finishedAt: T(7000) }),
    jobEvent('job/settled', 8, { jobId: 'pwsh-6', status: 'weird-status', finishedAt: T(8000) }),
    jobEvent('job/registered', 9, { jobId: 'pwsh-7', kind: 'pwsh', label: 'retried', startedAt: T(1000) }),
    jobEvent('job/settled', 10, { jobId: 'pwsh-7', status: 'failed', finishedAt: T(2000) }),
    jobEvent('job/settled', 11, { jobId: 'pwsh-7', status: 'completed', finishedAt: T(9000) }),
    { type: 'user/message', seq: 12, data: { jobId: 'not-a-job' } },
    jobEvent('job/registered', 13, { jobId: '', kind: 'pwsh', label: 'no id' }),
  ];

  const board = mapSessionJobs(events);
  assert.equal(board.supported, true);
  assert.equal(board.truncated, false);
  assert.equal(board.totalJobs, 7);
  assert.deepEqual(board.counts, { running: 2, completed: 4, failed: 1 });
  assert.deepEqual(board.jobs.map((job) => job.id), [
    'pwsh-4',
    'pwsh-3',
    'pwsh-7',
    'pwsh-6',
    'pwsh-5',
    'pwsh-2',
    'pwsh-1',
  ]);
  const byId = new Map(board.jobs.map((job) => [job.id, job]));
  // Latest event per id wins (pwsh-7 ends completed at 09:40:09).
  assert.equal(byId.get('pwsh-7').status, 'completed');
  assert.equal(byId.get('pwsh-7').finishedAt, '2026-10-30T09:40:09Z');
  assert.equal(byId.get('pwsh-7').startedAt, '2026-10-30T09:40:01Z');
  assert.equal(byId.get('pwsh-7').durationMs, 8000);
  assert.equal(byId.get('pwsh-1').status, 'completed');
  assert.equal(byId.get('pwsh-1').durationMs, 2000);
  assert.equal(byId.get('pwsh-2').status, 'failed');
  assert.equal(byId.get('pwsh-5').status, 'stopped');
  assert.equal(byId.get('pwsh-6').status, 'weird-status');
  assert.equal(byId.get('pwsh-3').status, 'running');
  assert.equal('finishedAt' in byId.get('pwsh-3'), false);
  assert.equal(byId.get('pwsh-2').label.includes('abcdef123456'), false);
  assert.equal(byId.get('pwsh-2').label.includes('supersecret'), false);
  assert.match(byId.get('pwsh-2').label, /Bearer <redacted>/);
  assert.match(byId.get('pwsh-2').label, /token=<redacted>/);
  assert.equal(byId.get('pwsh-1').detail.length, SESSION_JOB_DETAIL_MAX_CHARS);

  // J3: absolute job paths are redacted in labels/details, normal commands kept.
  const pathBoard = mapSessionJobs([
    jobEvent('job/registered', 20, {
      jobId: 'pwsh-path',
      kind: 'pwsh',
      label: 'git status --path C:\\dev\\repo\\file.ts',
      detail: 'open /home/user/secret.txt and \\\\server\\share\\x',
    }),
    jobEvent('job/settled', 21, {
      jobId: 'pwsh-path',
      status: '\u0000weird\u0001status',
      finishedAt: T(1000),
    }),
    jobEvent('job/registered', 22, {
      jobId: 'pwsh-env',
      kind: 'pwsh',
      label: 'CLOUDFLARE_API_TOKEN=cfat_FAKE1234567890abcdef _API_TOKEN: "fake api token"',
      startedAt: T(0),
    }),
    jobEvent('job/settled', 23, {
      jobId: 'pwsh-env',
      status: 'failed',
      finishedAt: T(500),
      detail: 'ghp_FAKE1234567890 eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmYWtlIn0.fakesignature123',
    }),
  ]);
  assert.match(pathBoard.jobs[0].label, /^git status --path <path>$/);
  assert.equal(pathBoard.jobs[0].label.includes('C:\\dev'), false);
  assert.equal(pathBoard.jobs[0].detail.includes('/home/user'), false);
  assert.equal(pathBoard.jobs[0].detail.includes('\\\\server'), false);
  assert.match(pathBoard.jobs[0].detail, /<path>/);
  // J2: control-character unknown status is sanitized before classification.
  assert.equal(pathBoard.jobs[0].status, 'weird status');
  assert.equal(/[\u0000-\u001f]/u.test(pathBoard.jobs[0].status), false);
  // Provider/env credentials in jobs are redacted by the shared generalized pass.
  const envJob = pathBoard.jobs.find((job) => job.id === 'pwsh-env');
  assert.match(envJob.label, /CLOUDFLARE_API_TOKEN=<redacted>/);
  assert.match(envJob.label, /_API_TOKEN: <redacted>/);
  assert.equal(envJob.label.includes('cfat_'), false);
  assert.equal(envJob.detail.includes('ghp_'), false);
  assert.equal(envJob.detail.includes('eyJhbGciOiJIUzI1NiJ9'), false);
  assert.match(envJob.detail, /<redacted>/);

  const json = JSON.stringify(board);
  for (const secret of ['owner-secret', 'call-secret', 'toolCall', 'producerReported']) {
    assert.equal(json.includes(secret), false, `${secret} must not leak`);
  }

  // Text caps and control/whitespace sanitization.
  const capped = mapSessionJobs([
    jobEvent('job/registered', 14, {
      jobId: 'pwsh-long',
      kind: `k-${'word-'.repeat(80)}`,
      label: `  control\u0000label\n ${'word '.repeat(100)}  `,
      detail: 'word '.repeat(100),
      startedAt: T(0),
    }),
  ]);
  assert.equal(capped.jobs[0].kind.length, SESSION_JOB_TEXT_MAX_CHARS);
  assert.equal(capped.jobs[0].label.length, SESSION_JOB_TEXT_MAX_CHARS);
  assert.equal(capped.jobs[0].detail.length, SESSION_JOB_DETAIL_MAX_CHARS);
  assert.equal(capped.jobs[0].label.startsWith('control label'), true);
  assert.equal(capped.jobs[0].label.includes('\n'), false);

  // Cap is 50 but counts cover all folded jobs; running jobs are newest-first.
  const many = [];
  for (let index = 0; index < 60; index += 1) {
    many.push(jobEvent('job/registered', 100 + index, {
      jobId: `pwsh-many-${index}`,
      kind: 'pwsh',
      label: `job ${index}`,
      startedAt: T(index),
    }));
  }
  const cappedBoard = mapSessionJobs(many);
  assert.equal(cappedBoard.jobs.length, SESSION_JOBS_MAX);
  assert.equal(cappedBoard.counts.running, 60);
  assert.equal(cappedBoard.truncated, true);
  assert.equal(cappedBoard.totalJobs, 60);
  assert.equal(cappedBoard.jobs[0].id, 'pwsh-many-59');
  assert.equal(cappedBoard.jobs.at(-1).id, 'pwsh-many-10');

  assert.deepEqual(mapSessionJobs([]), {
    supported: false,
    counts: { running: 0, completed: 0, failed: 0 },
    jobs: [],
    truncated: false,
    totalJobs: 0,
  });
  assert.deepEqual(mapSessionJobs([{ type: 'user/message', data: {} }]), {
    supported: false,
    counts: { running: 0, completed: 0, failed: 0 },
    jobs: [],
    truncated: false,
    totalJobs: 0,
  });
  assert.deepEqual(
    mapSessionJobs([{ type: 'job/registered', data: { jobId: 'j', kind: 'k', label: 'l' } }]).counts,
    { running: 1, completed: 0, failed: 0 },
  );
});
