import test from 'node:test';
import assert from 'node:assert/strict';

import { EventBus } from '../lib/events.js';
import { ApiError } from '../lib/errors.js';
import {
  DEFAULT_NTFY_SERVER,
  PUSH_BACKOFF_MS,
  PUSH_FAILURE_THRESHOLD,
  PUSH_MAX_QUEUE,
  PUSH_MAX_MUTED_SESSIONS,
  PushConfigFileStore,
  PushManager,
  createPushManager,
  emptyPushSuppression,
  isQuietHoursActive,
  normalizePushConfig,
  normalizePushSuppression,
  notificationForEvent,
  publicPushConfig,
  publicPushSuppression,
  validatePushServer,
} from '../lib/push.js';

const silent = { info() {}, warn() {}, error() {} };
const T = (offset = 0) => Date.UTC(2026, 9, 30, 9, 40, 0) + offset;

function fakeResponse({ ok = true, status = 200 } = {}) {
  return { ok, status };
}

/** Fake fetch recording each call; `handler` may override the response. */
function fakeFetch({ handler } = {}) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    if (handler) return handler(url, init);
    return fakeResponse();
  };
  fetchImpl.calls = calls;
  return fetchImpl;
}

function fakeStore(initial = null) {
  const state = { value: initial, saves: 0 };
  return {
    state,
    async load() {
      return state.value;
    },
    async save(value) {
      state.value = value;
      state.saves += 1;
    },
  };
}

test('normalizePushConfig validates and normalizes the push configuration', () => {
  assert.deepEqual(normalizePushConfig({}), {
    enabled: false,
    server: DEFAULT_NTFY_SERVER,
    topic: '',
    token: null,
  });
  assert.deepEqual(
    normalizePushConfig({
      enabled: true,
      server: 'https://ntfy.example.com///',
      topic: ' dsh_test-1 ',
      token: ' tk_secret ',
    }),
    {
      enabled: true,
      server: 'https://ntfy.example.com',
      topic: 'dsh_test-1',
      token: 'tk_secret',
    },
  );
  assert.equal(normalizePushConfig({ token: '' }).token, null);

  const fallback = { enabled: true, server: 'https://ntfy.example.com', topic: 'abc', token: null };
  assert.deepEqual(normalizePushConfig({ enabled: false }, fallback), {
    enabled: false,
    server: 'https://ntfy.example.com',
    topic: 'abc',
    token: null,
  });

  const bad = (raw, fallbackConfig) => {
    assert.throws(
      () => normalizePushConfig(raw, fallbackConfig),
      (error) => error instanceof ApiError && error.code === 'invalid_request' && error.status === 400,
    );
  };
  bad({ enabled: 'yes' });
  bad({ enabled: false, server: 'ftp://ntfy.example.com' });
  bad({ enabled: false, server: 'not-a-url' });
  bad({ enabled: false, server: 42 });
  bad({ enabled: false, topic: 'space topic' });
  bad({ enabled: false, topic: 'x'.repeat(65) });
  bad({ enabled: false, topic: 42 });
  bad({ enabled: false, token: 42 });
  bad({ enabled: false, token: 'x'.repeat(513) });
  bad({ enabled: true, topic: '' });
});

test('validatePushServer enforces the https/SSRF policy', () => {
  assert.equal(validatePushServer('https://ntfy.sh/'), 'https://ntfy.sh');
  assert.equal(validatePushServer('https://ntfy.example.com///'), 'https://ntfy.example.com');
  assert.equal(validatePushServer('http://localhost:8080'), 'http://localhost:8080');
  assert.equal(validatePushServer('http://127.0.0.1:2586'), 'http://127.0.0.1:2586');
  assert.equal(validatePushServer('https://[::1]:8080'), 'https://[::1]:8080');

  const bad = (value) => {
    assert.throws(
      () => validatePushServer(value),
      (error) => error instanceof ApiError && error.code === 'invalid_request' && error.status === 400,
    );
  };
  bad('http://ntfy.sh');
  bad('http://example.com:8080');
  bad('https://127.0.0.2');
  bad('https://10.0.0.5');
  bad('https://172.16.0.9');
  bad('https://192.168.1.20');
  bad('https://169.254.169.254/latest/meta-data');
  bad('https://[fd00::1]');
  bad('https://user:pass@ntfy.sh');
  bad('https://ntfy.sh/path');
  bad('https://ntfy.sh?x=1');
  bad('ftp://ntfy.sh');
  assert.equal(validatePushServer(DEFAULT_NTFY_SERVER), DEFAULT_NTFY_SERVER);
});

test('publicPushConfig never includes the token', () => {
  const published = publicPushConfig({
    enabled: true,
    server: 'https://ntfy.example.com',
    topic: 'abc',
    token: 'tk_secret',
  });
  assert.deepEqual(published, {
    supported: true,
    enabled: true,
    server: 'https://ntfy.example.com',
    topic: 'abc',
    hasToken: true,
  });
  assert.equal(JSON.stringify(published).includes('tk_secret'), false);
  assert.equal(publicPushConfig({ enabled: false }).hasToken, false);
});

test('notificationForEvent maps approval, question and goal transitions', () => {
  assert.deepEqual(notificationForEvent({ type: 'approval.requested', data: { id: 'ap_1' } }), {
    title: 'Approval needed',
    text: 'A tool is waiting for approval on the host.',
    priority: 4,
    tags: 'warning',
    click: 'dshmobile://inbox',
    dedupeKey: 'approval:ap_1',
    dedupeMs: 0,
  });
  assert.deepEqual(notificationForEvent({ type: 'question.requested', data: { id: 'q_1' } }), {
    title: 'Input needed',
    text: 'The host is waiting for an answer.',
    priority: 4,
    tags: 'question',
    click: 'dshmobile://inbox',
    dedupeKey: 'question:q_1',
    dedupeMs: 0,
  });

  const paused = notificationForEvent({
    type: 'goal.changed',
    sessionId: 'session-1',
    data: { sessionId: 'session-1', operation: 'pause', goal: { phase: 'paused', revision: 4 } },
  });
  assert.equal(paused.title, 'Goal paused');
  assert.equal(paused.click, 'dshmobile://session/session-1');
  assert.equal(paused.dedupeKey, 'goal:session-1:4');
  assert.equal(paused.dedupeMs, 0);
  assert.equal(paused.priority, 3);

  const blocked = notificationForEvent({
    type: 'goal.changed',
    sessionId: 'session-1',
    data: { sessionId: 'session-1', operation: 'block', goal: { phase: 'blocked', revision: 5 } },
  });
  assert.equal(blocked.title, 'Goal blocked');
  assert.equal(blocked.priority, 4);
  assert.equal(blocked.tags, 'warning');

  const completed = notificationForEvent({
    type: 'goal.changed',
    data: { sessionId: 'session-2', operation: 'complete', goal: { phase: 'complete', revision: 1 } },
  });
  assert.equal(completed.title, 'Goal complete');
  assert.equal(completed.click, 'dshmobile://session/session-2');

  assert.equal(
    notificationForEvent({ type: 'goal.changed', data: { operation: 'edit' } }),
    null,
    'goal edits are not pushed',
  );
  assert.equal(notificationForEvent({ type: 'approval.resolved', data: {} }), null);
  assert.equal(notificationForEvent(null), null);
});

test('PushManager persists config, redacts the token on GET and validates enabled/topic', async () => {
  const store = fakeStore();
  const push = createPushManager({ store, fetchImpl: fakeFetch(), logger: silent });

  assert.deepEqual(await push.load(), {
    supported: true,
    enabled: false,
    server: DEFAULT_NTFY_SERVER,
    topic: '',
    hasToken: false,
  });

  const updated = await push.update({ enabled: true, topic: 'dsh_test', token: 'tk_1' });
  assert.deepEqual(updated, {
    supported: true,
    enabled: true,
    server: DEFAULT_NTFY_SERVER,
    topic: 'dsh_test',
    hasToken: true,
  });
  assert.equal(store.state.saves, 1);
  assert.equal(store.state.value.token, 'tk_1');
  assert.equal(JSON.stringify(push.getPublicConfig()).includes('tk_1'), false);

  await assert.rejects(
    () => push.update({ enabled: true, topic: '' }),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );
  await assert.rejects(
    () => push.update({ enabled: false, server: 'ftp://x' }),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );

  // Null token clears the stored credential; GET only reports hasToken.
  const cleared = await push.update({ enabled: false, token: null });
  assert.equal(cleared.hasToken, false);
  assert.equal(store.state.value.token, null);
});

test('PushManager sends approval/question/goal events with ntfy headers and deep links', async () => {
  const bus = new EventBus({ logger: silent });
  const fetchImpl = fakeFetch();
  const push = createPushManager({ bus, fetchImpl, logger: silent });
  await push.update({ enabled: true, server: 'https://ntfy.example.com', topic: 'dsh_topic', token: 'tk_secret' });

  bus.publish('approval.requested', { id: 'ap_1', title: 'Permission needed: Bash' });
  bus.publish('question.requested', { id: 'q_1', prompt: 'Proceed?' });
  bus.publish('goal.changed', { sessionId: 'session-1', operation: 'pause', goal: { phase: 'paused', revision: 2 } }, { sessionId: 'session-1' });
  await waitFor(() => fetchImpl.calls.length >= 3);

  assert.equal(fetchImpl.calls.length, 3);
  const approval = fetchImpl.calls[0];
  assert.equal(approval.url, 'https://ntfy.example.com/dsh_topic');
  assert.equal(approval.init.method, 'POST');
  assert.equal(approval.init.headers.Title, 'Approval needed');
  assert.equal(approval.init.headers.Priority, '4');
  assert.equal(approval.init.headers.Tags, 'warning');
  assert.equal(approval.init.headers.Click, 'dshmobile://inbox');
  assert.equal(approval.init.headers.Authorization, 'Bearer tk_secret');
  assert.equal(approval.init.body, 'A tool is waiting for approval on the host.');

  const question = fetchImpl.calls[1];
  assert.equal(question.init.headers.Title, 'Input needed');
  assert.equal(question.init.headers.Tags, 'question');

  const goal = fetchImpl.calls[2];
  assert.equal(goal.init.headers.Title, 'Goal paused');
  assert.equal(goal.init.headers.Click, 'dshmobile://session/session-1');
  assert.equal(goal.init.body, 'The goal is now paused.');

  push.close();
});

test('PushManager is a no-op while disabled and swallows fetch failures', async () => {
  const bus = new EventBus({ logger: silent });
  const fetchImpl = fakeFetch();
  const push = createPushManager({ bus, fetchImpl, logger: silent });

  bus.publish('approval.requested', { id: 'ap_disabled' });
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.equal(fetchImpl.calls.length, 0);

  await push.update({ enabled: true, topic: 'dsh_topic' });
  bus.publish('approval.requested', { id: 'ap_fails' });
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.equal(fetchImpl.calls.length, 1, 'the failing notification was attempted');

  // A later failure must not throw out of the event path.
  const failing = createPushManager({
    bus: new EventBus({ logger: silent }),
    fetchImpl: async () => {
      throw new Error('network down');
    },
    logger: silent,
  });
  await failing.update({ enabled: true, topic: 'dsh_topic' });
  failing.handleTurnFinished('session-1');
  await new Promise((resolve) => setTimeout(resolve, 20));
  failing.close();
  push.close();
});

test('PushManager dedupes repeated goal revisions and rate-limits turn completion', async () => {
  const bus = new EventBus({ logger: silent });
  const fetchImpl = fakeFetch();
  let now = T(0);
  const push = createPushManager({ bus, fetchImpl, logger: silent, now: () => now, turnDedupeMs: 5000 });
  await push.update({ enabled: true, topic: 'dsh_topic' });
  const goal = { sessionId: 'session-1', operation: 'pause', goal: { phase: 'paused', revision: 2 } };
  bus.publish('goal.changed', goal, { sessionId: 'session-1' });
  bus.publish('goal.changed', goal, { sessionId: 'session-1' });
  const repeatedQuestion = { id: 'q_same' };
  bus.publish('question.requested', repeatedQuestion);
  bus.publish('question.requested', repeatedQuestion);
  push.handleTurnFinished('session-1');
  push.handleTurnFinished('session-1');
  await waitFor(() => fetchImpl.calls.length >= 3);
  assert.equal(fetchImpl.calls.length, 3, 'duplicates are dropped (goal revision + question id + turn window)');

  now = T(6000);
  push.handleTurnFinished('session-1');
  await waitFor(() => fetchImpl.calls.length >= 4);
  assert.equal(fetchImpl.calls.length, 4, 'turn notifications resume after the 5 s window');
  push.close();
});

test('PushManager backoff stops delivery after consecutive failures', async () => {
  const bus = new EventBus({ logger: silent });
  const fetchImpl = fakeFetch({
    handler: async () => {
      throw new Error('ntfy down');
    },
  });
  let now = T(0);
  const push = createPushManager({ bus, fetchImpl, logger: silent, now: () => now });
  await push.update({ enabled: true, topic: 'dsh_topic' });

  bus.publish('approval.requested', { id: 'ap_fail_1' });
  bus.publish('approval.requested', { id: 'ap_fail_2' });
  await waitFor(() => fetchImpl.calls.length >= PUSH_FAILURE_THRESHOLD);
  assert.equal(fetchImpl.calls.length, PUSH_FAILURE_THRESHOLD);

  // The circuit is open: further events are skipped without a fetch.
  bus.publish('approval.requested', { id: 'ap_paused' });
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.equal(fetchImpl.calls.length, PUSH_FAILURE_THRESHOLD);

  // After the backoff window a new notification is attempted again.
  now = T(PUSH_BACKOFF_MS + 1000);
  bus.publish('approval.requested', { id: 'ap_resumed' });
  await waitFor(() => fetchImpl.calls.length >= PUSH_FAILURE_THRESHOLD + 1);
  assert.equal(fetchImpl.calls.length, PUSH_FAILURE_THRESHOLD + 1);
  push.close();
});

test('push suppression state normalizes partial updates and validates bounds', () => {
  const defaults = emptyPushSuppression();
  assert.deepEqual(normalizePushSuppression({}), {
    quietHoursEnabled: false,
    quietStartHour: 22,
    quietEndHour: 7,
    timeZone: '',
    mutedSessionIds: [],
  });
  assert.deepEqual(
    normalizePushSuppression({ quietHoursEnabled: true, timeZone: 'UTC' }, defaults),
    { quietHoursEnabled: true, quietStartHour: 22, quietEndHour: 7, timeZone: 'UTC', mutedSessionIds: [] },
  );
  const partial = normalizePushSuppression(
    { quietEndHour: 6, mutedSessionIds: ['session-a', 'session-a', 'session-b'] },
    { ...defaults, quietHoursEnabled: true, timeZone: 'America/New_York' },
  );
  assert.equal(partial.quietStartHour, 22);
  assert.equal(partial.quietEndHour, 6);
  assert.equal(partial.timeZone, 'America/New_York');
  assert.deepEqual(partial.mutedSessionIds, ['session-a', 'session-b']);

  const bad = (raw, fallback) => {
    assert.throws(
      () => normalizePushSuppression(raw, fallback),
      (error) => error instanceof ApiError && error.code === 'invalid_request' && error.status === 400,
    );
  };
  bad({ quietHoursEnabled: 'yes' });
  bad({ quietStartHour: 24 });
  bad({ quietStartHour: -1 });
  bad({ quietEndHour: 1.5 });
  bad({ timeZone: 'Not/AZone' });
  bad({ quietHoursEnabled: true, timeZone: '' });
  bad({ mutedSessionIds: 'session-a' });
  bad({ mutedSessionIds: [42] });
  bad({ mutedSessionIds: ['bad session id'] });
  bad({ mutedSessionIds: Array.from({ length: PUSH_MAX_MUTED_SESSIONS + 1 }, (_, index) => `session-${index}`) });

  assert.equal(publicPushSuppression(partial).mutedCount, 2);
  assert.equal(publicPushSuppression(partial).timeZoneValid, true);
  assert.equal(publicPushSuppression({ ...partial, timeZone: '' }).timeZoneValid, false);
});

test('quiet hours handle same-day, midnight-crossing, start==end, time zones and DST', () => {
  const quiet = (start, end, timeZone, now) =>
    isQuietHoursActive(
      {
        quietHoursEnabled: true,
        quietStartHour: start,
        quietEndHour: end,
        timeZone,
        mutedSessionIds: [],
      },
      now,
    );
  const atUtc = (hour) => Date.UTC(2026, 0, 15, hour, 30, 0);
  assert.equal(quiet(9, 17, 'UTC', atUtc(10)), true);
  assert.equal(quiet(9, 17, 'UTC', atUtc(9)), true, 'start is inclusive');
  assert.equal(quiet(9, 17, 'UTC', atUtc(17)), false, 'end is exclusive');
  assert.equal(quiet(9, 17, 'UTC', atUtc(3)), false);

  assert.equal(quiet(22, 7, 'UTC', atUtc(23)), true, 'cross-midnight evening');
  assert.equal(quiet(22, 7, 'UTC', atUtc(0)), true, 'cross-midnight midnight');
  assert.equal(quiet(22, 7, 'UTC', atUtc(6)), true);
  assert.equal(quiet(22, 7, 'UTC', atUtc(7)), false);
  assert.equal(quiet(22, 7, 'UTC', atUtc(12)), false);
  assert.equal(quiet(5, 5, 'UTC', atUtc(5)), false, 'start==end disables the window');
  assert.equal(quiet(9, 17, '', atUtc(10)), false, 'invalid/empty zone is never quiet');

  // 2026-03-08 America/New_York: 02:00 EST jumps to 03:00 EDT.
  const springForward = (utc) => Date.parse(utc);
  assert.equal(quiet(1, 2, 'America/New_York', springForward('2026-03-08T06:30:00Z')), true, '01:30 EST');
  assert.equal(quiet(1, 2, 'America/New_York', springForward('2026-03-08T07:30:00Z')), false, '03:30 EDT');
  assert.equal(quiet(3, 4, 'America/New_York', springForward('2026-03-08T07:30:00Z')), true, '03:30 EDT in 3..4');
  // Tokyo keeps a fixed local hour.
  assert.equal(quiet(22, 7, 'Asia/Tokyo', Date.parse('2026-01-01T14:30:00Z')), true, '23:30 JST');
  assert.equal(quiet(22, 7, 'Asia/Tokyo', Date.parse('2026-01-01T03:30:00Z')), false, '12:30 JST');
});

test('PushManager suppresses quiet-hour and muted-session notifications only', async () => {
  const bus = new EventBus({ logger: silent });
  const fetchImpl = fakeFetch();
  let now = Date.UTC(2026, 0, 15, 23, 30, 0); // 23:30 UTC
  const push = createPushManager({ bus, fetchImpl, logger: silent, now: () => now });
  await push.update({ enabled: true, topic: 'dsh_topic' });

  // Quiet 22..7 UTC: all pushes are suppressed, including goal pushes.
  await push.updateSuppression({
    quietHoursEnabled: true,
    quietStartHour: 22,
    quietEndHour: 7,
    timeZone: 'UTC',
    mutedSessionIds: ['session-muted'],
  });
  bus.publish('goal.changed', { sessionId: 'session-muted', operation: 'pause', goal: { phase: 'paused', revision: 2 } });
  push.handleTurnFinished('session-muted');
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.equal(fetchImpl.calls.length, 0, 'quiet hours suppress every push');

  // Daytime: a muted session is suppressed, goal (no mute id) is delivered.
  now = Date.UTC(2026, 0, 16, 12, 0, 0);
  bus.publish('goal.changed', { sessionId: 'session-muted', operation: 'pause', goal: { phase: 'paused', revision: 3 } });
  await waitFor(() => fetchImpl.calls.length >= 1);
  assert.equal(fetchImpl.calls.length, 1, 'goal pushes ignore mute');

  bus.publish('approval.requested', { id: 'ap_muted' }, { sessionId: 'session-muted' });
  bus.publish('question.requested', { id: 'q_muted' }, { sessionId: 'session-muted' });
  push.handleTurnFinished('session-muted');
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.equal(fetchImpl.calls.length, 1, 'muted session-scoped pushes are suppressed');

  bus.publish('approval.requested', { id: 'ap_other' }, { sessionId: 'session-other' });
  push.handleTurnFinished('session-other');
  await waitFor(() => fetchImpl.calls.length >= 3);
  assert.equal(fetchImpl.calls.length, 3, 'non-muted session pushes still deliver');

  // Disabling quiet hours restores delivery; clearing mutes restores the muted session.
  await push.updateSuppression({ quietHoursEnabled: false, mutedSessionIds: [] });
  bus.publish('approval.requested', { id: 'ap_after' }, { sessionId: 'session-muted' });
  await waitFor(() => fetchImpl.calls.length >= 4);
  assert.equal(fetchImpl.calls.length, 4);

  const summary = push.getSuppressionSummary();
  assert.equal(summary.quietHoursEnabled, false);
  assert.equal(summary.mutedCount, 0);
  assert.equal(summary.timeZoneValid, true);
  push.close();
});

test('PushConfigFileStore persists suppression atomically and reloads it', async () => {
  const { mkdtemp, rm } = await import('node:fs/promises');
  const { tmpdir } = await import('node:os');
  const pathModule = await import('node:path');
  const dir = await mkdtemp(pathModule.join(tmpdir(), 'dsh-push-suppress-'));
  try {
    const file = pathModule.join(dir, 'push-config.json');
    const store = new PushConfigFileStore(file, { logger: silent });
    await store.save({
      enabled: true,
      topic: 'abc',
      token: 'tk_local',
      suppression: {
        quietHoursEnabled: true,
        quietStartHour: 23,
        quietEndHour: 6,
        timeZone: 'Europe/Berlin',
        mutedSessionIds: ['session-a'],
      },
    });
    const loaded = await store.load();
    assert.equal(loaded.enabled, true);
    assert.equal(loaded.token, 'tk_local');
    assert.equal(loaded.suppression.timeZone, 'Europe/Berlin');

    const manager = createPushManager({ store, fetchImpl: fakeFetch(), logger: silent });
    const publicConfig = await manager.load();
    assert.equal(publicConfig.enabled, true);
    assert.deepEqual(manager.getSuppressionSummary(), {
      quietHoursEnabled: true,
      quietStartHour: 23,
      quietEndHour: 6,
      timeZone: 'Europe/Berlin',
      timeZoneValid: true,
      mutedCount: 1,
    });
    await manager.updateSuppression({ mutedSessionIds: ['session-a', 'session-b'] });
    const reloaded = createPushManager({ store: new PushConfigFileStore(file, { logger: silent }), fetchImpl: fakeFetch(), logger: silent });
    await reloaded.load();
    assert.equal(reloaded.getSuppressionSummary().mutedCount, 2);
    assert.equal(reloaded.getPublicConfig().enabled, true, 'config survives suppression writes');
    reloaded.close();
    manager.close();
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('sendTest reports success or a short 502 and requires an enabled topic', async () => {
  const store = fakeStore();
  const success = createPushManager({ store, fetchImpl: fakeFetch(), logger: silent });
  await assert.rejects(
    () => success.sendTest(),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );
  await success.update({ enabled: true, topic: 'dsh_topic', token: 'tk' });
  assert.deepEqual(await success.sendTest(), { ok: true });

  const rejecting = createPushManager({
    store: fakeStore(),
    fetchImpl: async () => fakeResponse({ ok: false, status: 403 }),
    logger: silent,
  });
  await rejecting.update({ enabled: true, topic: 'dsh_topic' });
  await assert.rejects(
    () => rejecting.sendTest(),
    (error) =>
      error instanceof ApiError &&
      error.code === 'internal' &&
      error.status === 502 &&
      /HTTP 403/.test(error.message),
  );

  const offline = createPushManager({
    store: fakeStore(),
    fetchImpl: async () => {
      throw new Error('ECONNREFUSED');
    },
    logger: silent,
  });
  await offline.update({ enabled: true, topic: 'dsh_topic' });
  await assert.rejects(
    () => offline.sendTest(),
    (error) => error instanceof ApiError && error.code === 'internal' && error.status === 502 && /reach/.test(error.message),
  );
});

test('PushConfigFileStore tolerates missing files and persists atomically', async () => {
  const { mkdtemp, rm } = await import('node:fs/promises');
  const { tmpdir } = await import('node:os');
  const pathModule = await import('node:path');
  const dir = await mkdtemp(pathModule.join(tmpdir(), 'dsh-push-'));
  try {
    const file = pathModule.join(dir, 'push-config.json');
    const store = new PushConfigFileStore(file, { logger: silent });
    assert.equal(await store.load(), null);
    await store.save({ enabled: true, topic: 'abc', token: 'tk_local' });
    const loaded = await store.load();
    assert.equal(loaded.enabled, true);
    assert.equal(loaded.topic, 'abc');
    assert.equal(loaded.token, 'tk_local');
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

/** Wait until `predicate` is true (push delivery is fire-and-forget). */
async function waitFor(predicate, timeoutMs = 2000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  throw new Error('timed out waiting for push delivery');
}

// Keep the queue-bound constant covered by the public module surface.
void PUSH_MAX_QUEUE;
