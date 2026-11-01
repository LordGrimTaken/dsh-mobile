import test from 'node:test';
import assert from 'node:assert/strict';

import { ApiError } from '../lib/errors.js';
import {
  MAX_AFTER_SECONDS,
  MAX_EVERY_SECONDS,
  MIN_EVERY_SECONDS,
  mapScheduleRecord,
  parseScheduleCreateBody,
  parseScheduleUpdateBody,
  scheduleRecordForUpdate,
  scheduleServiceError,
  scheduleSummary,
  sortSchedules,
} from '../lib/schedules.js';

const T = (offset = 0) => Date.UTC(2026, 9, 30, 9, 40, 0) + offset;

test('parseScheduleCreateBody translates each mobile timing selector to DSH keywords', () => {
  const after = parseScheduleCreateBody({
    title: '  Remind me ',
    instruction: ' Ping me ',
    afterSeconds: 60,
  });
  assert.deepEqual(after, {
    title: 'Remind me',
    instruction: 'Ping me',
    selector: 'afterSeconds',
    request: { title: 'Remind me', prompt: 'Ping me', after_seconds: 60 },
  });

  const every = parseScheduleCreateBody({ title: 'Poll', instruction: 'Check', everySeconds: MIN_EVERY_SECONDS });
  assert.deepEqual(every.request, { title: 'Poll', prompt: 'Check', every_seconds: MIN_EVERY_SECONDS });

  const daily = parseScheduleCreateBody({ title: 'Standup', instruction: 'Go', dailyTime: '09:30' });
  assert.deepEqual(daily.request, { title: 'Standup', prompt: 'Go', daily: { time: '09:30', time_zone: 'UTC' } });

  const dailyZoned = parseScheduleCreateBody({
    title: 'Standup',
    instruction: 'Go',
    dailyTime: '23:05',
    timeZone: 'Europe/Berlin',
  });
  assert.deepEqual(dailyZoned.request.daily, { time: '23:05', time_zone: 'Europe/Berlin' });

  const at = parseScheduleCreateBody({ title: 'Later', instruction: 'Do it', at: '2026-11-01T12:00:00Z' });
  assert.deepEqual(at.request, { title: 'Later', prompt: 'Do it', at: '2026-11-01T12:00:00Z' });

  assert.equal(parseScheduleCreateBody({ title: 'x', instruction: 'y', afterSeconds: MAX_AFTER_SECONDS }).request.after_seconds, MAX_AFTER_SECONDS);
  assert.equal(parseScheduleCreateBody({ title: 'x', instruction: 'y', everySeconds: MAX_EVERY_SECONDS }).request.every_seconds, MAX_EVERY_SECONDS);
});

test('parseScheduleCreateBody rejects missing, blank, over-long and invalid timing input', () => {
  const bad = (body) => {
    assert.throws(
      () => parseScheduleCreateBody(body),
      (error) => error instanceof ApiError && error.code === 'invalid_request' && error.status === 400,
    );
  };
  bad({});
  bad({ instruction: 'x', afterSeconds: 60 });
  bad({ title: '   ', instruction: 'x', afterSeconds: 60 });
  bad({ title: 'x'.repeat(201), instruction: 'x', afterSeconds: 60 });
  bad({ title: 'x', afterSeconds: 60 });
  bad({ title: 'x', instruction: '   ', afterSeconds: 60 });
  bad({ title: 'x', instruction: 'y'.repeat(4001), afterSeconds: 60 });
  bad({ title: 'x', instruction: 'y' });
  bad({ title: 'x', instruction: 'y', afterSeconds: 60, everySeconds: 300 });
  bad({ title: 'x', instruction: 'y', afterSeconds: 60, dailyTime: '09:00' });
  bad({ title: 'x', instruction: 'y', afterSeconds: 0 });
  bad({ title: 'x', instruction: 'y', afterSeconds: -1 });
  bad({ title: 'x', instruction: 'y', afterSeconds: 1.5 });
  bad({ title: 'x', instruction: 'y', afterSeconds: MAX_AFTER_SECONDS + 1 });
  bad({ title: 'x', instruction: 'y', everySeconds: MIN_EVERY_SECONDS - 1 });
  bad({ title: 'x', instruction: 'y', everySeconds: '300' });
  bad({ title: 'x', instruction: 'y', dailyTime: '9:00' });
  bad({ title: 'x', instruction: 'y', dailyTime: '24:00' });
  bad({ title: 'x', instruction: 'y', dailyTime: 930 });
  bad({ title: 'x', instruction: 'y', dailyTime: '09:30', timeZone: '' });
  bad({ title: 'x', instruction: 'y', at: 'not-a-date' });
  bad({ title: 'x', instruction: 'y', at: 42 });
});

test('mapScheduleRecord projects all DSH kinds into the mobile shape', () => {
  const after = mapScheduleRecord(
    { id: 'schedule-1', kind: 'after', title: 'Remind', prompt: 'Ping me', afterSeconds: 60, scheduledAt: '2026-10-30T10:01:00Z' },
    { sessionId: 'session-a', sessionTitle: 'A', status: 'active', createdAt: '2026-10-30T10:00:00Z', updatedAt: '2026-10-30T10:00:00Z' },
  );
  assert.deepEqual(after, {
    id: 'schedule-1',
    sessionId: 'session-a',
    sessionTitle: 'A',
    title: 'Remind',
    instruction: 'Ping me',
    status: 'active',
    schedule: { kind: 'after', summary: 'in 60s', nextRunAt: '2026-10-30T10:01:00Z', lastRunAt: null },
    createdAt: '2026-10-30T10:00:00Z',
    updatedAt: '2026-10-30T10:00:00Z',
  });

  const inactive = mapScheduleRecord(
    { id: 'schedule-2', kind: 'at', title: 'Done', prompt: 'x', scheduledAt: '2026-10-29T10:00:00Z' },
    { sessionId: 'session-b', status: 'inactive', lastDelivery: { deliveredAt: '2026-10-29T10:00:01Z' } },
  );
  assert.equal(inactive.status, 'inactive');
  assert.equal(inactive.schedule.nextRunAt, null);
  assert.equal(inactive.schedule.lastRunAt, '2026-10-29T10:00:01Z');
  assert.equal('sessionTitle' in inactive, false);
  assert.equal(inactive.createdAt, null);

  const kinds = [
    { kind: 'every', everySeconds: 300, expected: 'every 300s' },
    { kind: 'daily', time: '09:30', timeZone: 'UTC', expected: 'daily at 09:30 UTC' },
    { kind: 'weekly', weekdays: [1, 3, 5], time: '08:00', timeZone: 'UTC', expected: 'weekly on 1,3,5 at 08:00 UTC' },
    { kind: 'cron', expression: '0 9 * * *', timeZone: 'UTC', expected: 'cron 0 9 * * * (UTC)' },
  ];
  for (const { expected, ...record } of kinds) {
    assert.equal(scheduleSummary(record), expected);
  }
  assert.equal(scheduleSummary({ kind: 'mystery' }), 'mystery');
  assert.equal(scheduleSummary(null), 'scheduled');

  const sanitized = mapScheduleRecord(
    { id: 'x'.repeat(250), kind: 'after', title: 't'.repeat(250), prompt: 'p', afterSeconds: 1, scheduledAt: '' },
    { sessionId: 'session-c', createdAt: null, updatedAt: null },
  );
  assert.equal(sanitized.id.length, 200);
  assert.equal(sanitized.title.length, 200);
  assert.equal(sanitized.schedule.nextRunAt, null);
  assert.equal(sanitized.createdAt, null);
});

test('sortSchedules orders by next run then id, with inactive rows last', () => {
  const rows = [
    { id: 'b', schedule: { nextRunAt: '2026-10-30T10:00:00Z' } },
    { id: 'a', schedule: { nextRunAt: '2026-10-30T10:00:00Z' } },
    { id: 'later', schedule: { nextRunAt: '2026-10-31T10:00:00Z' } },
    { id: 'inactive', schedule: { nextRunAt: null } },
  ];
  assert.deepEqual(
    sortSchedules(rows).map((row) => row.id),
    ['a', 'b', 'later', 'inactive'],
  );
});

test('scheduleServiceError maps DSH input failures to documented errors', () => {
  const input = new Error('target is not strictly future');
  input.code = 'not_future';
  const mapped = scheduleServiceError(input);
  assert.equal(mapped instanceof ApiError, true);
  assert.equal(mapped.code, 'invalid_request');
  assert.equal(mapped.status, 400);
  assert.equal(mapped.details.code, 'not_future');
  assert.equal(mapped.message, 'target is not strictly future');

  const subagent = new Error('target session is owned by a delegated child');
  subagent.code = 'subagent_session';
  assert.equal(scheduleServiceError(subagent).code, 'invalid_request');

  const internal = new Error('boom');
  internal.code = 'internal_error';
  assert.equal(scheduleServiceError(internal).code, 'internal');
  assert.equal(scheduleServiceError(internal).status, 500);

  assert.equal(scheduleServiceError(new Error('plain')), null);
  assert.equal(scheduleServiceError({}), null);
});

test('parseScheduleUpdateBody accepts partial title/instruction/timing edits', () => {
  assert.deepEqual(parseScheduleUpdateBody({ title: '  New title ' }), { title: 'New title' });
  assert.deepEqual(parseScheduleUpdateBody({ instruction: ' New instruction ' }), {
    instruction: 'New instruction',
  });
  assert.deepEqual(parseScheduleUpdateBody({ everySeconds: 600 }), {
    selector: 'everySeconds',
    change: { kind: 'every', every_seconds: 600 },
  });
  assert.deepEqual(parseScheduleUpdateBody({ dailyTime: '08:15', timeZone: 'Europe/Berlin' }), {
    selector: 'dailyTime',
    change: { kind: 'daily', daily: { time: '08:15', time_zone: 'Europe/Berlin' } },
  });
  assert.deepEqual(parseScheduleUpdateBody({ at: '2026-11-01T12:00:00Z' }), {
    selector: 'at',
    change: { kind: 'at', at: '2026-11-01T12:00:00Z' },
  });

  const before = Date.now();
  const after = parseScheduleUpdateBody({ afterSeconds: 120 });
  assert.equal(after.selector, 'afterSeconds');
  assert.equal(after.change.kind, 'at');
  const atMs = Date.parse(after.change.at);
  assert.equal(Number.isFinite(atMs), true);
  assert.equal(atMs >= before + 120 * 1000 - 1000, true, 'afterSeconds becomes a future absolute target');

  assert.deepEqual(parseScheduleUpdateBody({ title: 'T', instruction: 'I', everySeconds: 300 }), {
    title: 'T',
    instruction: 'I',
    selector: 'everySeconds',
    change: { kind: 'every', every_seconds: 300 },
  });
  assert.deepEqual(parseScheduleUpdateBody({ dailyTime: '09:30' }).change.daily.time_zone, 'UTC');
});

test('parseScheduleUpdateBody rejects no-op, conflicting and invalid edits', () => {
  const bad = (body) => {
    assert.throws(
      () => parseScheduleUpdateBody(body),
      (error) => error instanceof ApiError && error.code === 'invalid_request' && error.status === 400,
    );
  };
  bad({});
  bad({ timeZone: 'UTC' });
  bad({ title: '' });
  bad({ title: '   ' });
  bad({ title: 42 });
  bad({ title: 'x'.repeat(201) });
  bad({ instruction: '' });
  bad({ instruction: 'x'.repeat(4001) });
  bad({ everySeconds: 60 });
  bad({ everySeconds: 600, at: '2026-11-01T12:00:00Z' });
  bad({ dailyTime: '9:00' });
  bad({ dailyTime: '24:00' });
  bad({ dailyTime: '09:00', timeZone: '' });
  bad({ at: 'not-a-date' });
  bad({ at: 42 });
});

test('scheduleRecordForUpdate copies only the kind-specific expected record fields', () => {
  assert.deepEqual(
    scheduleRecordForUpdate({
      id: 'schedule-1',
      kind: 'after',
      title: 'T',
      prompt: 'P',
      afterSeconds: 60,
      scheduledAt: '2026-10-30T10:01:00Z',
      sessionId: 'session-a',
      status: 'active',
      lastDelivery: { deliveredAt: '2026-10-30T10:02:00Z' },
    }),
    {
      id: 'schedule-1',
      kind: 'after',
      title: 'T',
      prompt: 'P',
      afterSeconds: 60,
      scheduledAt: '2026-10-30T10:01:00Z',
    },
  );

  const weeklyEntry = {
    id: 'schedule-2',
    kind: 'weekly',
    title: 'W',
    prompt: 'P',
    time: '08:00',
    timeZone: 'UTC',
    weekdays: [1, 3, 5],
    scheduledAt: '2026-11-02T08:00:00Z',
    sessionId: 'session-a',
    status: 'active',
  };
  const weekly = scheduleRecordForUpdate(weeklyEntry);
  assert.deepEqual(weekly, {
    id: 'schedule-2',
    kind: 'weekly',
    title: 'W',
    prompt: 'P',
    time: '08:00',
    timeZone: 'UTC',
    weekdays: [1, 3, 5],
    scheduledAt: '2026-11-02T08:00:00Z',
  });
  assert.notEqual(weekly.weekdays, weeklyEntry.weekdays, 'weekdays array is copied');

  assert.deepEqual(
    scheduleRecordForUpdate({ id: 's', kind: 'daily', title: 'D', prompt: 'P', time: '09:00', timeZone: 'UTC', scheduledAt: 'x' }),
    { id: 's', kind: 'daily', title: 'D', prompt: 'P', time: '09:00', timeZone: 'UTC', scheduledAt: 'x' },
  );
  assert.deepEqual(
    scheduleRecordForUpdate({ id: 's', kind: 'cron', title: 'C', prompt: 'P', expression: '0 9 * * *', timeZone: 'UTC', scheduledAt: 'x' }),
    { id: 's', kind: 'cron', title: 'C', prompt: 'P', expression: '0 9 * * *', timeZone: 'UTC', scheduledAt: 'x' },
  );
  assert.deepEqual(
    scheduleRecordForUpdate({ id: 's', kind: 'every', title: 'E', prompt: 'P', everySeconds: 300, scheduledAt: 'x' }),
    { id: 's', kind: 'every', title: 'E', prompt: 'P', everySeconds: 300, scheduledAt: 'x' },
  );
});
