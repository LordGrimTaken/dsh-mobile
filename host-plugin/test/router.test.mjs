import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, utimes, writeFile } from 'node:fs/promises';

import { EventBus } from '../lib/events.js';
import { ApiError } from '../lib/errors.js';
import { HiddenSessionStore } from '../lib/hidden-sessions.js';
import { createRouter } from '../lib/router.js';
import { createAttachmentStore, createFakeAdapter, createFakePush, createHiddenStore, issueToken, makePairing, routeCall, testConfig, tempHiddenFile } from './helpers.mjs';

const silent = { info() {}, warn() {}, error() {} };

function setup(
  adapterOverrides = {},
  configOverrides = {},
  { issueToken: withToken = true, attachmentNow, hiddenStore, push: pushOverride } = {},
) {
  const config = testConfig(configOverrides);
  const pairing = makePairing(config);
  const adapter = createFakeAdapter(adapterOverrides);
  const bus = new EventBus({ logger: silent });
  const attachments = createAttachmentStore(config, attachmentNow ? { now: attachmentNow } : {});
  const hidden = hiddenStore ?? createHiddenStore();
  const push = pushOverride ?? createFakePush();
  const router = createRouter({ config, pairing, adapter, attachments, hiddenStore: hidden, push, bus, logger: silent });
  const issued = withToken ? issueToken(pairing) : null;
  return {
    config,
    pairing,
    adapter,
    attachments,
    hiddenStore: hidden,
    push,
    bus,
    router,
    token: issued?.token,
    deviceId: issued?.device?.deviceId,
  };
}

function assertErrorBody(result, status, code) {
  assert.equal(result.status, status);
  assert.deepEqual(Object.keys(result.body), ['error']);
  assert.deepEqual(Object.keys(result.body.error).sort(), ['code', 'message']);
  assert.equal(result.body.error.code, code);
  assert.equal(typeof result.body.error.message, 'string');
  assert.equal(result.body.error.message.length > 0, true);
}

/** Small deterministic payloads for upload tests. */
const PNG_BYTES = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x01, 0x02, 0x03]);
const PNG_BASE64 = PNG_BYTES.toString('base64');
const PDF_BYTES = Buffer.from('%PDF-1.4\nhello\n');
const PDF_BASE64 = PDF_BYTES.toString('base64');

async function uploadImage(router, token, sessionId = 's_1', overrides = {}) {
  return routeCall(router, {
    method: 'POST',
    path: `/mobile/v1/sessions/${sessionId}/attachments`,
    token,
    body: { name: 'shot.png', mimeType: 'image/png', dataBase64: PNG_BASE64, ...overrides },
  });
}

async function uploadFile(router, token, sessionId = 's_1', overrides = {}) {
  return routeCall(router, {
    method: 'POST',
    path: `/mobile/v1/sessions/${sessionId}/attachments`,
    token,
    body: { name: 'report.pdf', mimeType: 'application/pdf', dataBase64: PDF_BASE64, ...overrides },
  });
}

test('health is public and leaks no secrets', async () => {
  const { router } = setup({}, {}, { issueToken: false });
  const result = await routeCall(router, { path: '/mobile/v1/health' });
  assert.equal(result.status, 200);
  assert.deepEqual(result.body, { ok: true, version: '0.3.13', apiVersion: 1, paired: false });
  assert.equal(Object.keys(result.body).includes('token'), false);
});

test('usage requires a device token and returns aggregate token usage', async () => {
  const { router, token } = setup();
  const unauthorized = await routeCall(router, { path: '/mobile/v1/usage' });
  assertErrorBody(unauthorized, 401, 'unauthorized');
  const result = await routeCall(router, { path: '/mobile/v1/usage', token });
  assert.equal(result.status, 200);
  assert.equal(result.body.totals.totalTokens, 420);
  assert.equal(result.body.totals.cacheReadTokens, 300);
  assert.deepEqual(result.body.sessions, []);
  assert.equal(result.body.balance, null);
});

test('pair/new works from loopback and returns a 6-digit code with QR payload', async () => {
  const { router } = setup({}, { publicBaseUrl: 'https://pc.tailnet.ts.net', hostName: 'pc' });
  const result = await routeCall(router, { method: 'POST', path: '/mobile/v1/pair/new' });
  assert.equal(result.status, 200);
  assert.match(result.body.code, /^\d{6}$/);
  assert.match(result.body.expiresAt, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
  assert.equal(
    result.body.qrPayload,
    `dshmobile://pair?base=https%3A%2F%2Fpc.tailnet.ts.net&code=${result.body.code}&host=pc`,
  );
});

test('pair/new accepts IPv4-mapped loopback and omits base without publicBaseUrl', async () => {
  const { router } = setup();
  const result = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/pair/new',
    remoteAddress: '::ffff:127.0.0.1',
  });
  assert.equal(result.status, 200);
  assert.match(result.body.qrPayload, /^dshmobile:\/\/pair\?code=\d{6}&host=/);
});

test('pair/new is forbidden for non-loopback peers and for proxied requests', async () => {
  const { router } = setup();
  assertErrorBody(
    await routeCall(router, { method: 'POST', path: '/mobile/v1/pair/new', remoteAddress: '10.1.2.3' }),
    403,
    'forbidden',
  );

  for (const headers of [
    { 'x-forwarded-for': '100.64.0.7' },
    { 'X-Forwarded-Host': 'pc.tailnet.ts.net' },
    { forwarded: 'for=100.64.0.7' },
    { 'tailscale-user-login': 'operator@example.com' },
    { 'Tailscale-Funnel-Host': 'pc.tailnet.ts.net' },
  ]) {
    const result = await routeCall(router, { method: 'POST', path: '/mobile/v1/pair/new', headers });
    assertErrorBody(result, 403, 'forbidden');
  }
});

test('full pairing round-trip issues a working bearer token', async () => {
  const { router, pairing } = setup({}, {}, { issueToken: false });
  assert.equal(pairing.hasDevices(), false);

  const newResult = await routeCall(router, { method: 'POST', path: '/mobile/v1/pair/new' });
  const complete = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/pair/complete',
    body: { code: newResult.body.code, deviceName: 'Pixel 9', platform: 'android' },
  });
  assert.equal(complete.status, 200);
  assert.match(complete.body.token, /^[A-Za-z0-9_-]{43}$/);
  assert.match(complete.body.deviceId, /^dev_/);
  assert.deepEqual(complete.body.host, { name: 'test-host', apiVersion: 1, version: '0.3.13' });
  assert.equal(complete.body.workspaces.length, 1);
  assert.equal(JSON.stringify(complete.body.workspaces[0]).includes('token'), false);

  const health = await routeCall(router, { path: '/health' });
  assert.equal(health.body.paired, true);

  const host = await routeCall(router, { path: '/mobile/v1/host', token: complete.body.token });
  assert.equal(host.status, 200);
  assert.deepEqual(host.body, {
    name: 'test-host',
    version: '0.3.13',
    workspaces: [{ id: 'ws_1', name: 'DeepSeek Harness', path: 'C:\\.dsh' }],
  });

  // The same one-time code fails the second time.
  const replay = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/pair/complete',
    body: { code: newResult.body.code, deviceName: 'Pixel 9' },
  });
  assertErrorBody(replay, 400, 'invalid_code');
});

test('pair/complete validates its body and rate limits failures', async () => {
  const { router } = setup();
  assertErrorBody(await routeCall(router, { method: 'POST', path: '/mobile/v1/pair/complete', body: '{bad json' }), 400, 'invalid_request');
  assertErrorBody(await routeCall(router, { method: 'POST', path: '/mobile/v1/pair/complete', body: {} }), 400, 'invalid_request');

  for (let attempt = 0; attempt < 5; attempt += 1) {
    assertErrorBody(
      await routeCall(router, { method: 'POST', path: '/mobile/v1/pair/complete', body: { code: '000000' }, remoteAddress: '10.0.0.5' }),
      400,
      'invalid_code',
    );
  }
  assertErrorBody(
    await routeCall(router, { method: 'POST', path: '/mobile/v1/pair/complete', body: { code: '000000' }, remoteAddress: '10.0.0.5' }),
    429,
    'rate_limited',
  );
});

test('every non-exempt endpoint requires a bearer token', async () => {
  const { router } = setup();
  const requests = [
    { path: '/mobile/v1/host' },
    { path: '/mobile/v1/devices' },
    { method: 'DELETE', path: '/mobile/v1/devices/dev_other' },
    { path: '/mobile/v1/recovery' },
    { method: 'POST', path: '/mobile/v1/recovery' },
    { path: '/mobile/v1/presets' },
    { path: '/mobile/v1/models' },
    { path: '/mobile/v1/push' },
    { method: 'PUT', path: '/mobile/v1/push', body: { enabled: false } },
    { method: 'POST', path: '/mobile/v1/push/test' },
    { path: '/mobile/v1/search', query: 'q=hi' },
    { path: '/mobile/v1/sessions' },
    { path: '/mobile/v1/sessions/s_1' },
    { path: '/mobile/v1/sessions/s_1/approval-policy' },
    { method: 'PUT', path: '/mobile/v1/sessions/s_1/approval-policy', body: { policy: 'never' } },
    { path: '/mobile/v1/sessions/s_1/model' },
    { method: 'PUT', path: '/mobile/v1/sessions/s_1/model', body: { provider: 'deepseek', model: 'deepseek-chat' } },
    { path: '/mobile/v1/sessions/s_1/queue' },
    { method: 'PATCH', path: '/mobile/v1/sessions/s_1/queue/m_q1', body: { action: 'remove' } },
    { method: 'POST', path: '/mobile/v1/sessions/s_1/fork', body: {} },
    { method: 'POST', path: '/mobile/v1/sessions', body: {} },
    { method: 'POST', path: '/mobile/v1/sessions/s_1/prompt', body: { text: 'hi' } },
    { method: 'POST', path: '/mobile/v1/sessions/s_1/attachments', body: {} },
    { path: '/mobile/v1/sessions/s_1/attachments/att_img_1' },
    { method: 'POST', path: '/mobile/v1/sessions/s_1/interrupt' },
    { method: 'POST', path: '/mobile/v1/sessions/s_1/goal/edit', body: { objective: 'x' } },
    { method: 'POST', path: '/mobile/v1/sessions/s_1/goal/complete' },
    { method: 'POST', path: '/mobile/v1/sessions/s_1/goal/clear' },
    { path: '/mobile/v1/approvals' },
    { path: '/mobile/v1/questions' },
    { path: '/mobile/v1/schedules' },
    {
      method: 'POST',
      path: '/mobile/v1/sessions/s_1/schedules',
      body: { title: 'Remind', instruction: 'Ping', afterSeconds: 60 },
    },
    { method: 'DELETE', path: '/mobile/v1/schedules/schedule-1' },
    { method: 'PATCH', path: '/mobile/v1/schedules/schedule-1', body: { title: 'x' } },
    { path: '/mobile/v1/sessions/archived' },
    { method: 'POST', path: '/mobile/v1/sessions/s_1/rename', body: { title: 'x' } },
    { method: 'POST', path: '/mobile/v1/sessions/s_1/archive' },
    { method: 'POST', path: '/mobile/v1/sessions/s_1/unarchive' },
    { path: '/mobile/v1/events' },
  ];
  for (const request of requests) {
    const result = await routeCall(router, request);
    assertErrorBody(result, 401, 'unauthorized');
  }

  for (const authorization of ['Basic abc', 'Bearer ', 'Bearer bad-token-that-is-long-enough']) {
    assertErrorBody(
      await routeCall(router, { path: '/mobile/v1/host', headers: { authorization } }),
      401,
      'unauthorized',
    );
  }
});

test('GET /presets returns the agent-preset roster and requires a token', async () => {
  const { router, adapter, token } = setup();
  const result = await routeCall(router, { path: '/mobile/v1/presets', token });
  assert.equal(result.status, 200);
  assert.deepEqual(result.body, {
    presets: [
      { id: 'default', name: 'Default', isDefault: true },
      { id: 'focused', name: 'Focused', isDefault: false },
    ],
    supported: true,
    defaultId: 'default',
  });
  assert.equal(adapter.calls.length, 0, 'the roster is a read-only adapter call');
  assertErrorBody(await routeCall(router, { path: '/mobile/v1/presets' }), 401, 'unauthorized');
});

test('GET /search validates q/limit, maps hits and requires a token', async () => {
  const { router, adapter, token } = setup();
  const result = await routeCall(router, {
    path: '/mobile/v1/search',
    token,
    query: 'q=  hello  &limit=10',
  });
  assert.equal(result.status, 200);
  assert.equal(result.body.supported, true);
  assert.equal(result.body.truncated, false);
  assert.deepEqual(result.body.hits, [
    {
      sessionId: 's_1',
      sessionTitle: 'Cut a patch release',
      messageId: 'm_user_1',
      role: 'user',
      snippet: 'Hello there',
      seq: 7,
      createdAt: '2026-10-30T09:40:01Z',
    },
  ]);
  assert.deepEqual(adapter.calls.at(-1), { method: 'searchSessions', query: 'hello', limit: 10 });

  const defaults = await routeCall(router, { path: '/mobile/v1/search', token, query: 'q=tests' });
  assert.equal(defaults.status, 200);
  assert.deepEqual(adapter.calls.at(-1), { method: 'searchSessions', query: 'tests', limit: 50 });

  for (const params of [
    '',
    'q=',
    'q=a',
    `q=${'x'.repeat(201)}`,
    'q=hello&limit=0',
    'q=hello&limit=101',
    'q=hello&limit=abc',
    'q=hello&limit=1.5',
  ]) {
    assertErrorBody(await routeCall(router, { path: '/mobile/v1/search', token, query: params }), 400, 'invalid_request');
  }
  assertErrorBody(await routeCall(router, { path: '/mobile/v1/search', query: 'q=hello' }), 401, 'unauthorized');

  const filtered = await routeCall(router, {
    path: '/mobile/v1/search',
    token,
    query: 'q=hello&role=tool,user&since=2026-10-30T09:40:00Z&until=2026-10-30T09:40:10Z',
  });
  assert.equal(filtered.status, 200);
  assert.deepEqual(filtered.body.filters, {
    role: ['user', 'tool'],
    since: '2026-10-30T09:40:00.000Z',
    until: '2026-10-30T09:40:10.000Z',
  });
  assert.deepEqual(adapter.calls.at(-1).filters, {
    role: 'tool,user',
    since: '2026-10-30T09:40:00Z',
    until: '2026-10-30T09:40:10Z',
  });

  for (const params of [
    'q=hello&role=system',
    'q=hello&role=user,root',
    'q=hello&since=not-a-date',
    'q=hello&until=nope',
    'q=hello&since=2026-10-30T09:40:10Z&until=2026-10-30T09:40:00Z',
    'q=hello&offset=-1',
    'q=hello&offset=501',
    'q=hello&offset=1.5',
    'q=hello&offset=abc',
  ]) {
    assertErrorBody(await routeCall(router, { path: '/mobile/v1/search', token, query: params }), 400, 'invalid_request');
  }

  const paged = await routeCall(router, {
    path: '/mobile/v1/search',
    token,
    query: 'q=hello&limit=1&offset=2&role=assistant',
  });
  assert.equal(paged.status, 200);
  assert.equal(paged.body.hits.length, 1);
  assert.equal(paged.body.offset, 2);
  assert.equal(paged.body.nextOffset, null);
  assert.equal(paged.body.hasMore, false);
  assert.deepEqual(paged.body.filters, { role: ['assistant'] });
  assert.deepEqual(adapter.calls.at(-1), {
    method: 'searchSessions',
    query: 'hello',
    limit: 1,
    filters: { role: 'assistant', since: null, until: null },
    offset: 2,
  });
});

test('push config routes expose redacted config, validate and send a test', async () => {
  const { router, token, push } = setup();
  const initial = await routeCall(router, { path: '/mobile/v1/push', token });
  assert.equal(initial.status, 200);
  assert.deepEqual(initial.body, {
    supported: true,
    enabled: false,
    server: 'https://ntfy.sh',
    topic: '',
    hasToken: false,
    suppression: {
      quietHoursEnabled: false,
      quietStartHour: 22,
      quietEndHour: 7,
      timeZone: '',
      timeZoneValid: false,
      mutedCount: 0,
    },
  });

  const updated = await routeCall(router, {
    method: 'PUT',
    path: '/mobile/v1/push',
    token,
    body: { enabled: true, server: 'https://ntfy.example.com/', topic: 'dsh_topic', token: 'tk_secret' },
  });
  assert.equal(updated.status, 200);
  assert.deepEqual(updated.body, {
    supported: true,
    enabled: true,
    server: 'https://ntfy.example.com',
    topic: 'dsh_topic',
    hasToken: true,
  });
  assert.equal(JSON.stringify(updated.body).includes('tk_secret'), false, 'the token is never returned');
  assert.deepEqual(push.calls.at(-1), {
    method: 'update',
    body: { enabled: true, server: 'https://ntfy.example.com/', topic: 'dsh_topic', token: 'tk_secret' },
  });

  for (const body of [
    {},
    { enabled: 'yes' },
    { enabled: false, server: 'ftp://ntfy.example.com' },
    { enabled: false, server: 'http://ntfy.example.com' },
    { enabled: false, server: 'https://10.0.0.5' },
    { enabled: false, server: 'https://user:pass@ntfy.example.com' },
    { enabled: false, topic: 'not a topic' },
    { enabled: true, topic: '' },
  ]) {
    assertErrorBody(
      await routeCall(router, { method: 'PUT', path: '/mobile/v1/push', token, body }),
      400,
      'invalid_request',
    );
  }

  const tested = await routeCall(router, { method: 'POST', path: '/mobile/v1/push/test', token });
  assert.equal(tested.status, 200);
  assert.deepEqual(tested.body, { ok: true });
  assert.deepEqual(push.calls.at(-1), { method: 'sendTest' });

  const disabledSuppressionBody = {
    quietHoursEnabled: false,
    quietStartHour: 23,
    quietEndHour: 6,
    timeZone: 'Europe/Berlin',
    mutedSessionIds: ['session-a', 'session-b', 'session-c'],
  };
  const disabledRoundTrip = await routeCall(router, {
    method: 'PUT',
    path: '/mobile/v1/push/suppress',
    token,
    body: disabledSuppressionBody,
  });
  assert.equal(disabledRoundTrip.status, 200);
  assert.deepEqual(disabledRoundTrip.body, {
    quietHoursEnabled: false,
    quietStartHour: 23,
    quietEndHour: 6,
    timeZone: 'Europe/Berlin',
    timeZoneValid: true,
    mutedCount: 3,
  });
  assert.deepEqual((await routeCall(router, { path: '/mobile/v1/push', token })).body.suppression, disabledRoundTrip.body);

  const suppressionBody = {
    quietHoursEnabled: true,
    quietStartHour: 22,
    quietEndHour: 7,
    timeZone: 'America/New_York',
    mutedSessionIds: ['session-1', 'session-2'],
  };
  const suppressed = await routeCall(router, {
    method: 'PUT',
    path: '/mobile/v1/push/suppress',
    token,
    body: suppressionBody,
  });
  assert.equal(suppressed.status, 200);
  assert.deepEqual(suppressed.body, {
    quietHoursEnabled: true,
    quietStartHour: 22,
    quietEndHour: 7,
    timeZone: 'America/New_York',
    timeZoneValid: true,
    mutedCount: 2,
  });
  assert.deepEqual(push.calls.at(-1), { method: 'updateSuppression', body: suppressionBody });

  const afterSuppress = await routeCall(router, { path: '/mobile/v1/push', token });
  assert.deepEqual(afterSuppress.body.suppression, suppressed.body);
  assert.equal(JSON.stringify(afterSuppress.body).includes('tk_secret'), false);

  for (const body of [
    { quietHoursEnabled: 'yes' },
    { quietStartHour: 24 },
    { quietEndHour: -1 },
    { timeZone: 'Not/AZone' },
    { quietHoursEnabled: true, timeZone: '' },
    { mutedSessionIds: 'session-1' },
    { mutedSessionIds: ['bad session id'] },
    { mutedSessionIds: Array.from({ length: 501 }, (_, index) => `session-${index}`) },
  ]) {
    assertErrorBody(
      await routeCall(router, { method: 'PUT', path: '/mobile/v1/push/suppress', token, body }),
      400,
      'invalid_request',
    );
  }
  assertErrorBody(
    await routeCall(router, { method: 'PUT', path: '/mobile/v1/push/suppress', body: suppressionBody }),
    401,
    'unauthorized',
  );

  const { router: failingRouter, token: failingToken } = setup(
    {},
    {},
    {
      push: createFakePush({
        sendTestError: new ApiError('internal', 'Push server rejected the test notification (HTTP 403)', { status: 502 }),
      }),
    },
  );
  const failed = await routeCall(failingRouter, { method: 'POST', path: '/mobile/v1/push/test', token: failingToken });
  assert.equal(failed.status, 502);
  assert.equal(failed.body.error.code, 'internal');
  assert.match(failed.body.error.message, /HTTP 403/);

  assertErrorBody(await routeCall(router, { path: '/mobile/v1/push' }), 401, 'unauthorized');
});

test('paired-device list and revoke routes never expose secrets and invalidate tokens', async () => {
  const { router, pairing, token, deviceId } = setup();
  const second = issueToken(pairing, { deviceName: 'Tablet', platform: 'android' });

  const list = await routeCall(router, { path: '/mobile/v1/devices', token });
  assert.equal(list.status, 200);
  assert.equal(list.body.supported, true);
  assert.equal(list.body.currentDeviceId, deviceId);
  assert.equal(list.body.devices.length, 2);
  for (const device of list.body.devices) {
    assert.deepEqual(Object.keys(device).sort(), [
      'deviceId',
      'deviceName',
      'lastSeenAt',
      'pairedAt',
      'platform',
    ]);
  }
  const row = list.body.devices.find((device) => device.deviceId === second.device.deviceId);
  assert.equal(row.deviceName, 'Tablet');
  assert.equal(row.platform, 'android');
  assert.equal(JSON.stringify(list.body).includes('tokenHash'), false, 'token hashes are never listed');
  assert.equal(JSON.stringify(list.body).includes('publicKey'), false, 'only documented metadata is listed');

  const revoked = await routeCall(router, {
    method: 'DELETE',
    path: `/mobile/v1/devices/${second.device.deviceId}`,
    token,
  });
  assert.equal(revoked.status, 200);
  assert.deepEqual(revoked.body, { ok: true, revoked: true });

  // Revocation is immediate: the removed device's token is now unauthorized.
  assertErrorBody(
    await routeCall(router, { path: '/mobile/v1/host', token: second.token }),
    401,
    'unauthorized',
  );

  assertErrorBody(
    await routeCall(router, { method: 'DELETE', path: `/mobile/v1/devices/${deviceId}`, token }),
    400,
    'invalid_request',
  );
  assertErrorBody(
    await routeCall(router, { method: 'DELETE', path: '/mobile/v1/devices/dev_missing', token }),
    404,
    'not_found',
  );
  assertErrorBody(await routeCall(router, { path: '/mobile/v1/devices' }), 401, 'unauthorized');
});

test('device revoke answers 501 when the pairing store lacks management', async () => {
  const { config, adapter } = setup();
  const fakePairing = {
    hasDevices: () => true,
    authenticate: () => ({ deviceId: 'dev_current' }),
    listDevices: () => [],
  };
  const router = createRouter({ config, pairing: fakePairing, adapter, logger: silent });
  const result = await routeCall(router, {
    method: 'DELETE',
    path: '/mobile/v1/devices/dev_other',
    token: 'abcdefghijklmnopqrstuvwxyz',
  });
  assert.equal(result.status, 501);
  assert.equal(result.body.error.code, 'unsupported');
});

test('sessions list and detail map through the adapter', async () => {
  const { router, adapter, token } = setup();
  const list = await routeCall(router, { path: '/mobile/v1/sessions', token });
  assert.equal(list.status, 200);
  assert.equal(list.body.sessions.length, 1);
  assert.equal(list.body.sessions[0].id, 's_1');

  await routeCall(router, { path: '/mobile/v1/sessions/s_1?limit=5&before=m_2', token });
  const call = adapter.calls.at(-1);
  assert.deepEqual(call, { method: 'getSession', sessionId: 's_1', options: { limit: '5', before: 'm_2' } });

  const detail = await routeCall(router, { path: '/mobile/v1/sessions/s_1', token });
  assert.equal(detail.status, 200);
  assert.equal(detail.body.session.id, 's_1');
  assert.equal(detail.body.messages.length, 2);
  assert.equal(detail.body.nextCursor, null);
});

test('unknown session and unknown routes use the documented error shapes', async () => {
  const { router, token } = setup(
    {
      async getSession() {
        throw new ApiError('not_found', 'Session not found');
      },
    },
    {},
  );
  assertErrorBody(await routeCall(router, { path: '/mobile/v1/sessions/s_missing', token }), 404, 'not_found');
  assertErrorBody(await routeCall(router, { path: '/mobile/v1/nope', token }), 404, 'not_found');
  assertErrorBody(await routeCall(router, { path: '/mobile/v1/sessions/s_1/interrupt', token }), 404, 'not_found');
});

test('session creation validates fields and forwards them', async () => {
  const { router, adapter, token } = setup();
  const created = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/sessions',
    token,
    body: { workspaceId: 'ws_1', preset: 'cordis' },
  });
  assert.equal(created.status, 200);
  assert.equal(created.body.session.id, 's_1');
  assert.deepEqual(adapter.calls.at(-1), { method: 'createSession', request: { workspaceId: 'ws_1', preset: 'cordis' } });

  assertErrorBody(
    await routeCall(router, { method: 'POST', path: '/mobile/v1/sessions', token, body: '{oops' }),
    400,
    'invalid_request',
  );
  assertErrorBody(
    await routeCall(router, { method: 'POST', path: '/mobile/v1/sessions', token, body: { workspaceId: 7 } }),
    400,
    'invalid_request',
  );
});

test('prompt accepts text and validates the request', async () => {
  const { router, adapter, token } = setup();
  const accepted = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/sessions/s_1/prompt',
    token,
    body: { text: 'Run the release' },
  });
  assert.equal(accepted.status, 200);
  assert.deepEqual(accepted.body, { accepted: true, runId: 'run_test' });
  assert.deepEqual(adapter.calls.at(-1), {
    method: 'prompt',
    sessionId: 's_1',
    content: [{ type: 'text', text: 'Run the release' }],
  });

  for (const body of [{}, { text: '' }, { text: '   ' }, { text: 42 }, { text: 'x'.repeat(32_001) }]) {
    assertErrorBody(
      await routeCall(router, { method: 'POST', path: '/mobile/v1/sessions/s_1/prompt', token, body }),
      400,
      'invalid_request',
    );
  }

  const { router: failing, token: failingToken } = setup({
    async prompt() {
      throw new ApiError('not_found', 'Session not found');
    },
  });
  assertErrorBody(
    await routeCall(failing, { method: 'POST', path: '/mobile/v1/sessions/s_x/prompt', token: failingToken, body: { text: 'hi' } }),
    404,
    'not_found',
  );
});

test('attachment upload accepts an image and returns the contract attachment', async () => {
  const { router, adapter, token, attachments } = setup();
  const result = await uploadImage(router, token);
  assert.equal(result.status, 200);
  assert.deepEqual(Object.keys(result.body), ['attachment']);
  const { attachment } = result.body;
  assert.match(attachment.attachmentId, /^att_[A-Za-z0-9_-]{16}$/);
  assert.equal(attachment.name, 'shot.png');
  assert.equal(attachment.mimeType, 'image/png');
  assert.equal(attachment.bytes, PNG_BYTES.length);
  assert.equal(attachment.kind, 'image');

  const call = adapter.calls.at(-1);
  assert.equal(call.method, 'uploadAttachment');
  assert.equal(call.input.sessionId, 's_1');
  assert.equal(call.input.kind, 'image');
  assert.equal(call.input.dataBase64, PNG_BASE64);
  assert.deepEqual(Buffer.from(call.input.data), PNG_BYTES);

  const stored = attachments.get(attachment.attachmentId);
  assert.equal(stored.sessionId, 's_1');
  assert.equal(stored.dataBase64, PNG_BASE64, 'image bytes are kept for the later prompt part');
});

test('attachment upload accepts a file and stages a receipt', async () => {
  const { router, adapter, token, attachments } = setup();
  const result = await uploadFile(router, token);
  assert.equal(result.status, 200);
  assert.equal(result.body.attachment.kind, 'file');
  assert.equal(result.body.attachment.mimeType, 'application/pdf');
  assert.equal(result.body.attachment.bytes, PDF_BYTES.length);

  const stored = attachments.get(result.body.attachment.attachmentId);
  assert.equal(stored.dataBase64, null, 'file payloads are not retained in host memory');
  assert.match(stored.receiptId, /^receipt_/);
  assert.equal(adapter.calls.at(-1).input.kind, 'file');
});

test('attachment read-back route returns raw bytes from the adapter', async () => {
  const { router, adapter, token } = setup();
  const result = await routeCall(router, { path: '/mobile/v1/sessions/s_1/attachments/att_img_1', token });
  assert.equal(result.status, 200);
  assert.equal(result.headers['Content-Type'], 'image/png');
  assert.equal(result.headers['Content-Length'], '4');
  assert.equal(result.headers['Content-Disposition'], 'inline; filename="shot.png"');
  assert.equal(Buffer.from(result.raw).toString('hex'), '89504e47');
  assert.deepEqual(adapter.calls.at(-1), {
    method: 'readAttachment',
    sessionId: 's_1',
    attachmentId: 'att_img_1',
  });

  assertErrorBody(
    await routeCall(router, { path: '/mobile/v1/sessions/s_1/attachments/att_missing', token }),
    404,
    'not_found',
  );
  assertErrorBody(
    await routeCall(router, { path: '/mobile/v1/sessions/s_1/attachments/att_img_1' }),
    401,
    'unauthorized',
  );

  const { router: invalidRouter, token: invalidToken } = setup({
    async readAttachment() {
      throw new ApiError('invalid_request', 'Malformed attachment id');
    },
  });
  assertErrorBody(
    await routeCall(invalidRouter, { path: '/mobile/v1/sessions/s_1/attachments/bad..id', token: invalidToken }),
    400,
    'invalid_request',
  );
});

test('tailscale identity auto-pair: methods, success, errors and allowlist', async () => {
  const { router, pairing } = setup();
  const methods = await routeCall(router, { path: '/mobile/v1/pair/methods' });
  assert.equal(methods.status, 200);
  assert.deepEqual(methods.body, {
    desktopCode: true,
    recovery: true,
    tailscaleAutoPair: true,
    tailscaleLoginRequired: false,
  });

  // No bearer: Tailscale Serve identity header mints a device token.
  const paired = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/pair/tailscale',
    headers: { 'tailscale-user-login': ' Me@Example.com ' },
    body: { deviceName: 'Pixel', platform: 'android' },
  });
  assert.equal(paired.status, 200);
  assert.equal(typeof paired.body.token, 'string');
  assert.equal(paired.body.device.deviceName, 'Pixel');
  assert.equal(paired.body.device.platform, 'android');
  assert.equal(paired.body.deviceId, paired.body.device.deviceId);
  assert.equal(paired.body.pairingMethod, 'tailscale');
  assert.equal(paired.body.identity, 'Me@Example.com');
  assert.equal(typeof paired.body.host.name, 'string');
  assert.equal(Array.isArray(paired.body.workspaces), true);
  assert.equal((await routeCall(router, { path: '/mobile/v1/host', token: paired.body.token })).status, 200);

  // Tailscale-User-Name is the documented fallback.
  const byName = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/pair/tailscale',
    headers: { 'tailscale-user-name': 'Just A Name' },
    body: {},
  });
  assert.equal(byName.status, 200);
  assert.equal(byName.body.identity, 'Just A Name');

  // Missing identity: 400 invalid_request with a serve hint.
  const missing = await routeCall(router, { method: 'POST', path: '/mobile/v1/pair/tailscale', body: {} });
  assertErrorBody(missing, 400, 'invalid_request');
  assert.match(missing.body.error.message, /Tailscale identity is missing/);
  assert.match(missing.body.error.message, /Tailscale Serve/);

  // Disabled via config.
  const { router: disabledRouter } = setup({}, { pairing: { tailscaleAutoPair: false } });
  const disabledMethods = await routeCall(disabledRouter, { path: '/mobile/v1/pair/methods' });
  assert.equal(disabledMethods.body.tailscaleAutoPair, false);
  assertErrorBody(
    await routeCall(disabledRouter, {
      method: 'POST',
      path: '/mobile/v1/pair/tailscale',
      headers: { 'tailscale-user-login': 'me@example.com' },
      body: {},
    }),
    403,
    'forbidden',
  );

  // Optional allowlist (case-insensitive), both mismatch and match.
  const { router: allowedRouter } = setup({}, { pairing: { tailscaleLogin: 'me@example.com' } });
  const allowedMethods = await routeCall(allowedRouter, { path: '/mobile/v1/pair/methods' });
  assert.equal(allowedMethods.body.tailscaleLoginRequired, true);
  assertErrorBody(
    await routeCall(allowedRouter, {
      method: 'POST',
      path: '/mobile/v1/pair/tailscale',
      headers: { 'tailscale-user-login': 'other@example.com' },
      body: {},
    }),
    403,
    'forbidden',
  );
  const allowed = await routeCall(allowedRouter, {
    method: 'POST',
    path: '/mobile/v1/pair/tailscale',
    headers: { 'tailscale-user-login': 'ME@Example.com' },
    body: {},
  });
  assert.equal(allowed.status, 200);
  assert.equal(allowed.body.identity, 'ME@Example.com');
});

test('tailscale auto-pair shares the pairing rate limiter and maxDevices', async () => {
  const { router: limitedRouter } = setup({}, { pairing: { maxFailedAttemptsPerMinute: 1 } });
  assertErrorBody(
    await routeCall(limitedRouter, { method: 'POST', path: '/mobile/v1/pair/tailscale', body: {} }),
    400,
    'invalid_request',
  );
  assertErrorBody(
    await routeCall(limitedRouter, {
      method: 'POST',
      path: '/mobile/v1/pair/tailscale',
      headers: { 'tailscale-user-login': 'me@example.com' },
      body: {},
    }),
    429,
    'rate_limited',
  );

  const maxed = setup({}, { pairing: { maxDevices: 1 } });
  assert.equal(maxed.pairing.listDevices().length, 1);
  const result = await routeCall(maxed.router, {
    method: 'POST',
    path: '/mobile/v1/pair/tailscale',
    headers: { 'tailscale-user-login': 'me@example.com' },
    body: { deviceName: 'Only device' },
  });
  assert.equal(result.status, 200);
  assert.equal(maxed.pairing.listDevices().length, 1);
  assert.equal(maxed.pairing.listDevices()[0].deviceName, 'Only device');
});

test('recovery endpoints redact, rotate, bootstrap pairing and rate-limit', async () => {
  const { router, pairing, token } = setup();
  const info = await routeCall(router, { path: '/mobile/v1/recovery', token });
  assert.equal(info.status, 200);
  assert.deepEqual(info.body, { supported: true, configured: false });

  const created = await routeCall(router, { method: 'POST', path: '/mobile/v1/recovery', token });
  assert.equal(created.status, 200);
  assert.match(created.body.code, /^(?:[A-Z2-7]{4}-){12}[A-Z2-7]{4}$/);
  assert.match(created.body.createdAt, /^\d{4}-\d{2}-\d{2}T/);
  assert.equal(JSON.stringify(created.body).includes('hash'), false);

  const configured = await routeCall(router, { path: '/mobile/v1/recovery', token });
  assert.deepEqual(configured.body, { supported: true, configured: true, createdAt: created.body.createdAt });

  const rotated = await routeCall(router, { method: 'POST', path: '/mobile/v1/recovery', token });
  assert.notEqual(rotated.body.code, created.body.code);

  // The old code is dead; the rotated one pairs a new device without bearer auth.
  const oldAttempt = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/pair/recovery',
    body: { code: created.body.code, deviceName: 'Old code' },
  });
  assertErrorBody(oldAttempt, 401, 'invalid_code');

  const paired = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/pair/recovery',
    body: { code: rotated.body.code, deviceName: 'Recovered phone', platform: 'android' },
  });
  assert.equal(paired.status, 200);
  assert.equal(typeof paired.body.token, 'string');
  assert.equal(paired.body.device.deviceName, 'Recovered phone');
  assert.match(paired.body.recoveryCode, /^(?:[A-Z2-7]{4}-){12}[A-Z2-7]{4}$/);
  assert.equal(paired.body.device.tokenHash, undefined);
  assert.equal(typeof paired.body.host.name, 'string');
  assert.equal(Array.isArray(paired.body.workspaces), true);

  const authd = await routeCall(router, { path: '/mobile/v1/host', token: paired.body.token });
  assert.equal(authd.status, 200);
  // Single-use: replaying the consumed code fails; the rotated next code works.
  assertErrorBody(
    await routeCall(router, {
      method: 'POST',
      path: '/mobile/v1/pair/recovery',
      body: { code: rotated.body.code, deviceName: 'Replay' },
    }),
    401,
    'invalid_code',
  );
  const next = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/pair/recovery',
    body: { code: paired.body.recoveryCode, deviceName: 'Second recovery' },
  });
  assert.equal(next.status, 200);
  assert.equal(pairing.listDevices().length, 3);

  for (const body of [{}, { code: 42 }]) {
    assertErrorBody(
      await routeCall(router, { method: 'POST', path: '/mobile/v1/pair/recovery', body }),
      400,
      'invalid_request',
    );
  }
  assertErrorBody(
    await routeCall(router, { method: 'POST', path: '/mobile/v1/pair/recovery', body: { code: '' } }),
    401,
    'invalid_code',
  );
  assertErrorBody(await routeCall(router, { path: '/mobile/v1/recovery' }), 401, 'unauthorized');
  assertErrorBody(await routeCall(router, { method: 'POST', path: '/mobile/v1/recovery' }), 401, 'unauthorized');

  // No devices at all: a saved recovery code can bootstrap access.
  const bareConfig = testConfig();
  const barePairing = makePairing(bareConfig);
  const bareRecovery = await barePairing.createRecoveryCode();
  const bareRouter = createRouter({
    config: bareConfig,
    pairing: barePairing,
    adapter: createFakeAdapter(),
    logger: silent,
  });
  const bootstrapped = await routeCall(bareRouter, {
    method: 'POST',
    path: '/mobile/v1/pair/recovery',
    body: { code: bareRecovery.code, deviceName: 'First device' },
  });
  assert.equal(bootstrapped.status, 200);
  assert.equal(typeof bootstrapped.body.token, 'string');
  assert.equal((await routeCall(bareRouter, { path: '/mobile/v1/host', token: bootstrapped.body.token })).status, 200);

  // Rate limit: the second wrong code from the same IP answers 429.
  const limitedConfig = testConfig({ pairing: { maxFailedAttemptsPerMinute: 1 } });
  const limitedPairing = makePairing(limitedConfig);
  await limitedPairing.createRecoveryCode();
  const limitedRouter = createRouter({
    config: limitedConfig,
    pairing: limitedPairing,
    adapter: createFakeAdapter(),
    logger: silent,
  });
  assertErrorBody(
    await routeCall(limitedRouter, { method: 'POST', path: '/mobile/v1/pair/recovery', body: { code: 'A'.repeat(52) } }),
    401,
    'invalid_code',
  );
  assertErrorBody(
    await routeCall(limitedRouter, { method: 'POST', path: '/mobile/v1/pair/recovery', body: { code: 'B'.repeat(52) } }),
    429,
    'rate_limited',
  );
});

test('session fork route validates atSeq, maps the new session and requires auth', async () => {
  const { router, adapter, token } = setup();
  const latest = await routeCall(router, { method: 'POST', path: '/mobile/v1/sessions/s_1/fork', token, body: {} });
  assert.equal(latest.status, 200);
  assert.deepEqual(latest.body, { sessionId: 'session-forked-1', forkedFrom: 's_1' });
  assert.deepEqual(adapter.calls.at(-1), { method: 'forkSession', sessionId: 's_1', atSeq: undefined });

  const exact = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/sessions/s_1/fork',
    token,
    body: { atSeq: 12 },
  });
  assert.equal(exact.status, 200);
  assert.deepEqual(exact.body, { sessionId: 'session-forked-1', forkedFrom: 's_1', atSeq: 12 });

  for (const atSeq of [-1, 1.5, '12', Number.MAX_SAFE_INTEGER + 1, null]) {
    assertErrorBody(
      await routeCall(router, { method: 'POST', path: '/mobile/v1/sessions/s_1/fork', token, body: { atSeq } }),
      400,
      'invalid_request',
    );
  }
  assertErrorBody(
    await routeCall(router, { method: 'POST', path: '/mobile/v1/sessions/s_missing/fork', token, body: {} }),
    404,
    'not_found',
  );
  assertErrorBody(
    await routeCall(router, { method: 'POST', path: '/mobile/v1/sessions/s_1/fork', token, body: { atSeq: 999 } }),
    400,
    'invalid_request',
  );
  assertErrorBody(
    await routeCall(router, { method: 'POST', path: '/mobile/v1/sessions/s_1/fork', body: {} }),
    401,
    'unauthorized',
  );
});

test('queue routes map reads, validate PATCH actions and require auth', async () => {
  const { router, adapter, token } = setup();
  const read = await routeCall(router, { path: '/mobile/v1/sessions/s_1/queue', token });
  assert.equal(read.status, 200);
  assert.deepEqual(read.body, { supported: true, live: false, items: [] });
  assert.deepEqual(adapter.calls.at(-1), { method: 'listSessionQueue', sessionId: 's_1' });

  const edit = await routeCall(router, {
    method: 'PATCH',
    path: '/mobile/v1/sessions/s_1/queue/m_q1',
    token,
    body: { action: 'edit', text: '  Updated prompt  ' },
  });
  assert.equal(edit.status, 200);
  assert.deepEqual(edit.body, { accepted: true });
  assert.deepEqual(adapter.calls.at(-1), {
    method: 'updateSessionQueue',
    sessionId: 's_1',
    itemId: 'm_q1',
    body: { action: 'edit', text: '  Updated prompt  ' },
  });

  const remove = await routeCall(router, {
    method: 'PATCH',
    path: '/mobile/v1/sessions/s_1/queue/m_q1',
    token,
    body: { action: 'remove' },
  });
  assert.equal(remove.status, 200);
  assert.deepEqual(remove.body, { accepted: true });

  for (const body of [
    {},
    { action: 'nope' },
    { action: 'edit' },
    { action: 'edit', text: '' },
    { action: 'edit', text: '   ' },
    { action: 'edit', text: 'x'.repeat(20_001) },
    { action: 'remove', text: 'not allowed' },
    { action: 'steer', text: 'not allowed' },
  ]) {
    assertErrorBody(
      await routeCall(router, { method: 'PATCH', path: '/mobile/v1/sessions/s_1/queue/m_q1', token, body }),
      400,
      'invalid_request',
    );
  }
  assertErrorBody(
    await routeCall(router, {
      method: 'PATCH',
      path: '/mobile/v1/sessions/s_1/queue/m_unknown',
      token,
      body: { action: 'remove' },
    }),
    404,
    'not_found',
  );
  assertErrorBody(
    await routeCall(router, {
      method: 'PATCH',
      path: '/mobile/v1/sessions/s_cold/queue/m_q1',
      token,
      body: { action: 'remove' },
    }),
    400,
    'invalid_request',
  );
  assertErrorBody(await routeCall(router, { path: '/mobile/v1/sessions/s_1/queue' }), 401, 'unauthorized');
  assertErrorBody(
    await routeCall(router, {
      method: 'PATCH',
      path: '/mobile/v1/sessions/s_1/queue/m_q1',
      body: { action: 'remove' },
    }),
    401,
    'unauthorized',
  );
});

test('model catalog and session model routes map, validate and require auth', async () => {
  const { router, adapter, token } = setup();
  const catalog = await routeCall(router, { path: '/mobile/v1/models', token });
  assert.equal(catalog.status, 200);
  assert.equal(catalog.body.supported, true);
  assert.deepEqual(catalog.body.default, { provider: 'deepseek', model: 'deepseek-chat' });
  assert.equal(catalog.body.groups[0].models[0].id, 'deepseek-chat');
  assert.deepEqual(catalog.body.failures, []);
  assert.equal(catalog.body.truncated, false);
  assert.equal(JSON.stringify(catalog.body).includes('apiKey'), false);

  const read = await routeCall(router, { path: '/mobile/v1/sessions/s_1/model', token });
  assert.equal(read.status, 200);
  assert.deepEqual(read.body, {
    supported: true,
    live: false,
    selection: { provider: 'deepseek', model: 'deepseek-chat' },
    source: 'default',
  });
  assert.deepEqual(adapter.calls.at(-1), { method: 'getSessionModel', sessionId: 's_1' });

  const write = await routeCall(router, {
    method: 'PUT',
    path: '/mobile/v1/sessions/s_1/model',
    token,
    body: { provider: 'deepseek', model: 'deepseek-reasoner', reasoningEffort: 'high' },
  });
  assert.equal(write.status, 200);
  assert.deepEqual(write.body, {
    supported: true,
    live: true,
    selection: { provider: 'deepseek', model: 'deepseek-reasoner', reasoningEffort: 'high' },
  });
  assert.deepEqual(adapter.calls.at(-1), {
    method: 'selectSessionModel',
    sessionId: 's_1',
    selection: { provider: 'deepseek', model: 'deepseek-reasoner', reasoningEffort: 'high' },
  });

  for (const body of [
    {},
    { provider: 'deepseek' },
    { model: 'deepseek-chat' },
    { provider: '', model: 'deepseek-chat' },
    { provider: 'deepseek', model: '' },
    { provider: 'deepseek', model: 'deepseek-chat', reasoningEffort: 42 },
  ]) {
    assertErrorBody(
      await routeCall(router, { method: 'PUT', path: '/mobile/v1/sessions/s_1/model', token, body }),
      400,
      'invalid_request',
    );
  }
  assertErrorBody(
    await routeCall(router, {
      method: 'PUT',
      path: '/mobile/v1/sessions/s_cold/model',
      token,
      body: { provider: 'deepseek', model: 'deepseek-chat' },
    }),
    400,
    'invalid_request',
  );
  assertErrorBody(await routeCall(router, { path: '/mobile/v1/sessions/s_missing/model', token }), 404, 'not_found');
  assertErrorBody(await routeCall(router, { path: '/mobile/v1/models' }), 401, 'unauthorized');
  assertErrorBody(await routeCall(router, { path: '/mobile/v1/sessions/s_1/model' }), 401, 'unauthorized');
});

test('GET /sessions/{id}/skills maps the catalog and requires a token', async () => {
  const { router, adapter, token } = setup();
  const result = await routeCall(router, { path: '/mobile/v1/sessions/s_1/skills', token });
  assert.equal(result.status, 200);
  assert.deepEqual(result.body, {
    supported: true,
    skills: [
      { name: 'alpha', description: 'Alpha skill' },
      { name: 'beta', description: 'Beta skill' },
    ],
  });
  assert.deepEqual(adapter.calls.at(-1), { method: 'getSessionSkills', sessionId: 's_1' });

  assertErrorBody(
    await routeCall(router, { path: '/mobile/v1/sessions/s_1/skills' }),
    401,
    'unauthorized',
  );
  assertErrorBody(
    await routeCall(router, { path: '/mobile/v1/sessions/s_missing/skills', token }),
    404,
    'not_found',
  );
});

test('GET /sessions/{id}/todos maps the plan and requires a token', async () => {
  const { router, adapter, token } = setup();
  const result = await routeCall(router, { path: '/mobile/v1/sessions/s_1/todos', token });
  assert.equal(result.status, 200);
  assert.deepEqual(result.body, {
    supported: true,
    updatedAt: '2026-10-30T09:40:01Z',
    counts: { pending: 1, inProgress: 1, completed: 1 },
    todos: [
      { content: 'first', status: 'pending' },
      { content: 'second', status: 'in_progress' },
      { content: 'third', status: 'completed' },
    ],
  });
  assert.deepEqual(adapter.calls.at(-1), { method: 'getSessionTodos', sessionId: 's_1' });

  assertErrorBody(
    await routeCall(router, { path: '/mobile/v1/sessions/s_1/todos' }),
    401,
    'unauthorized',
  );
  assertErrorBody(
    await routeCall(router, { path: '/mobile/v1/sessions/s_missing/todos', token }),
    404,
    'not_found',
  );
});

test('GET /sessions/{id}/team maps the board and requires a token', async () => {
  const { router, adapter, token } = setup();
  const result = await routeCall(router, { path: '/mobile/v1/sessions/s_1/team', token });
  assert.equal(result.status, 200);
  assert.deepEqual(result.body, {
    supported: true,
    counts: { pending: 1, inProgress: 1, blocked: 1, completed: 1 },
    members: [{ name: 'Worker One', phase: 'working' }],
    tasks: [
      { id: 'task-1', subject: 'First board task', status: 'in_progress' },
      { id: 'task-2', subject: 'Blocked board task', status: 'blocked', blockedBy: ['task-1'] },
    ],
  });
  assert.deepEqual(adapter.calls.at(-1), { method: 'getSessionTeam', sessionId: 's_1' });

  assertErrorBody(
    await routeCall(router, { path: '/mobile/v1/sessions/s_1/team' }),
    401,
    'unauthorized',
  );
  assertErrorBody(
    await routeCall(router, { path: '/mobile/v1/sessions/s_missing/team', token }),
    404,
    'not_found',
  );
});

test('GET /sessions/{id}/trajectory forwards paging params and requires a token', async () => {
  const { router, adapter, token } = setup();
  const result = await routeCall(router, {
    path: '/mobile/v1/sessions/s_1/trajectory',
    token,
    query: 'limit=2&beforeSeq=7',
  });
  assert.equal(result.status, 200);
  assert.deepEqual(result.body, {
    supported: true,
    events: [
      { seq: 7, time: '2026-10-30T09:40:07Z', type: 'user/message', summary: 'hello trajectory' },
      { seq: 6, time: '2026-10-30T09:40:06Z', type: 'assistant/message', summary: 'reply trajectory' },
    ],
    nextBeforeSeq: 6,
    hasMore: true,
  });
  assert.deepEqual(adapter.calls.at(-1), {
    method: 'getSessionTrajectory',
    sessionId: 's_1',
    limit: '2',
    beforeSeq: '7',
  });

  await routeCall(router, { path: '/mobile/v1/sessions/s_1/trajectory', token });
  assert.deepEqual(adapter.calls.at(-1), { method: 'getSessionTrajectory', sessionId: 's_1' });

  assertErrorBody(
    await routeCall(router, { path: '/mobile/v1/sessions/s_1/trajectory' }),
    401,
    'unauthorized',
  );
  assertErrorBody(
    await routeCall(router, { path: '/mobile/v1/sessions/s_missing/trajectory', token }),
    404,
    'not_found',
  );
});

test('GET /sessions/{id}/jobs maps the folded jobs and requires a token', async () => {
  const { router, adapter, token } = setup();
  const result = await routeCall(router, { path: '/mobile/v1/sessions/s_1/jobs', token });
  assert.equal(result.status, 200);
  assert.deepEqual(result.body, {
    supported: true,
    counts: { running: 1, completed: 1, failed: 1 },
    jobs: [
      { id: 'pwsh-3', kind: 'pwsh', label: 'Still running', status: 'running' },
      { id: 'pwsh-2', kind: 'pwsh', label: 'Failed job', status: 'failed' },
      { id: 'pwsh-1', kind: 'pwsh', label: 'Done job', status: 'completed' },
    ],
    truncated: false,
    totalJobs: 3,
  });
  assert.deepEqual(adapter.calls.at(-1), { method: 'getSessionJobs', sessionId: 's_1' });

  assertErrorBody(
    await routeCall(router, { path: '/mobile/v1/sessions/s_1/jobs' }),
    401,
    'unauthorized',
  );
  assertErrorBody(
    await routeCall(router, { path: '/mobile/v1/sessions/s_missing/jobs', token }),
    404,
    'not_found',
  );
});

test('GET /sessions/{id}/context maps pressure and requires a token', async () => {
  const { router, adapter, token } = setup();
  const result = await routeCall(router, { path: '/mobile/v1/sessions/s_1/context', token });
  assert.equal(result.status, 200);
  assert.deepEqual(result.body, {
    supported: true,
    surfaceTokens: 1000,
    contextWindow: 2000,
    pressureTokens: 500,
    percent: 25,
    breakdown: { systemTokens: 100, toolsTokens: 200, messageTokens: 700 },
    sampledAt: '2026-10-30T09:40:05Z',
  });
  assert.deepEqual(adapter.calls.at(-1), { method: 'getSessionContext', sessionId: 's_1' });

  assertErrorBody(
    await routeCall(router, { path: '/mobile/v1/sessions/s_1/context' }),
    401,
    'unauthorized',
  );
  assertErrorBody(
    await routeCall(router, { path: '/mobile/v1/sessions/s_missing/context', token }),
    404,
    'not_found',
  );
  assertErrorBody(
    await routeCall(router, { method: 'POST', path: '/mobile/v1/sessions/s_1/context', token }),
    404,
    'not_found',
  );
});

test('approval-policy routes map reads, writes, validation and auth', async () => {
  const { router, adapter, token } = setup();
  const read = await routeCall(router, { path: '/mobile/v1/sessions/s_1/approval-policy', token });
  assert.equal(read.status, 200);
  assert.deepEqual(read.body, { supported: true, live: false, policy: 'ask' });
  assert.deepEqual(adapter.calls.at(-1), { method: 'getApprovalPolicy', sessionId: 's_1' });

  const write = await routeCall(router, {
    method: 'PUT',
    path: '/mobile/v1/sessions/s_1/approval-policy',
    token,
    body: { policy: 'never' },
  });
  assert.equal(write.status, 200);
  assert.deepEqual(write.body, { supported: true, live: true, policy: 'never' });
  assert.deepEqual(adapter.calls.at(-1), { method: 'setApprovalPolicy', sessionId: 's_1', policy: 'never' });

  for (const body of [{}, { policy: 'always' }, { policy: null }, { policy: 42 }]) {
    assertErrorBody(
      await routeCall(router, { method: 'PUT', path: '/mobile/v1/sessions/s_1/approval-policy', token, body }),
      400,
      'invalid_request',
    );
  }
  assertErrorBody(
    await routeCall(router, { method: 'PUT', path: '/mobile/v1/sessions/s_cold/approval-policy', token, body: { policy: 'ask' } }),
    400,
    'invalid_request',
  );
  assertErrorBody(
    await routeCall(router, { path: '/mobile/v1/sessions/s_missing/approval-policy', token }),
    404,
    'not_found',
  );
  assertErrorBody(await routeCall(router, { path: '/mobile/v1/sessions/s_1/approval-policy' }), 401, 'unauthorized');
});

test('session rename, archive/unarchive and archived-list routes', async () => {
  const { router, adapter, token } = setup();

  const archived = await routeCall(router, { path: '/mobile/v1/sessions/archived', token });
  assert.equal(archived.status, 200);
  assert.deepEqual(archived.body, { sessions: [], supported: true });

  const renamed = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/sessions/s_1/rename',
    token,
    body: { title: '  New title  ' },
  });
  assert.equal(renamed.status, 200);
  assert.deepEqual(renamed.body, { ok: true, title: 'New title', seq: 42 });
  assert.deepEqual(adapter.calls.at(-1), {
    method: 'renameSession',
    sessionId: 's_1',
    title: 'New title',
  });

  for (const body of [
    {},
    { title: '' },
    { title: '   ' },
    { title: 123 },
    { title: 'x'.repeat(201) },
    { title: null },
  ]) {
    assertErrorBody(
      await routeCall(router, { method: 'POST', path: '/mobile/v1/sessions/s_1/rename', token, body }),
      400,
      'invalid_request',
    );
  }
  assertErrorBody(
    await routeCall(router, {
      method: 'POST',
      path: '/mobile/v1/sessions/s_missing/rename',
      token,
      body: { title: 'x' },
    }),
    404,
    'not_found',
  );

  const archiveResult = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/sessions/s_1/archive',
    token,
  });
  assert.equal(archiveResult.status, 200);
  assert.deepEqual(archiveResult.body, { ok: true, archived: true });
  assert.deepEqual(adapter.calls.at(-1), { method: 'archiveSession', sessionId: 's_1' });

  const unarchiveResult = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/sessions/s_1/unarchive',
    token,
  });
  assert.equal(unarchiveResult.status, 200);
  assert.deepEqual(unarchiveResult.body, { ok: true, archived: false });
  assert.deepEqual(adapter.calls.at(-1), { method: 'unarchiveSession', sessionId: 's_1' });

  assertErrorBody(
    await routeCall(router, { method: 'POST', path: '/mobile/v1/sessions/s_missing/archive', token }),
    404,
    'not_found',
  );
});

test('schedule routes list, create with validation, and delete', async () => {
  const { router, adapter, token } = setup();
  const listed = await routeCall(router, { path: '/mobile/v1/schedules', token });
  assert.deepEqual(listed.body, { schedules: [], supported: true });

  const created = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/sessions/s_1/schedules',
    token,
    body: { title: '  Remind me ', instruction: ' Ping me ', afterSeconds: 60 },
  });
  assert.equal(created.status, 200);
  assert.equal(created.body.supported, true);
  assert.equal(created.body.schedule.id, 'schedule-1');
  assert.deepEqual(adapter.calls.at(-1), {
    method: 'createSessionSchedule',
    sessionId: 's_1',
    parsed: {
      title: 'Remind me',
      instruction: 'Ping me',
      selector: 'afterSeconds',
      request: { title: 'Remind me', prompt: 'Ping me', after_seconds: 60 },
    },
  });

  const daily = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/sessions/s_1/schedules',
    token,
    body: { title: 'Daily', instruction: 'Standup', dailyTime: '09:30', timeZone: 'Europe/Berlin' },
  });
  assert.equal(daily.status, 200);
  assert.deepEqual(adapter.calls.at(-1).parsed.request, {
    title: 'Daily',
    prompt: 'Standup',
    daily: { time: '09:30', time_zone: 'Europe/Berlin' },
  });

  for (const body of [
    {},
    { title: '', instruction: 'x', afterSeconds: 60 },
    { title: 'x', instruction: '', afterSeconds: 60 },
    { title: 'x', instruction: 'y' },
    { title: 'x', instruction: 'y', afterSeconds: 60, everySeconds: 300 },
    { title: 'x', instruction: 'y', afterSeconds: 0 },
    { title: 'x', instruction: 'y', afterSeconds: 1.5 },
    { title: 'x', instruction: 'y', everySeconds: 299 },
    { title: 'x', instruction: 'y', dailyTime: '25:00' },
    { title: 'x', instruction: 'y', at: 'not-a-date' },
    { title: 'x'.repeat(201), instruction: 'y', afterSeconds: 60 },
    { title: 'x', instruction: 'y'.repeat(4001), afterSeconds: 60 },
  ]) {
    assertErrorBody(
      await routeCall(router, { method: 'POST', path: '/mobile/v1/sessions/s_1/schedules', token, body }),
      400,
      'invalid_request',
    );
  }
  assertErrorBody(
    await routeCall(router, { method: 'POST', path: '/mobile/v1/sessions/s_1/schedules', token, body: '{bad' }),
    400,
    'invalid_request',
  );
  assert.equal(
    adapter.calls.filter((call) => call.method === 'createSessionSchedule').length,
    2,
    'validation refusals never reach the adapter',
  );

  const deleted = await routeCall(router, { method: 'DELETE', path: '/mobile/v1/schedules/schedule-1', token });
  assert.equal(deleted.status, 200);
  assert.deepEqual(deleted.body, { ok: true, deleted: true });
  assert.deepEqual(adapter.calls.at(-1), { method: 'deleteSchedule', scheduleId: 'schedule-1' });

  const patched = await routeCall(router, {
    method: 'PATCH',
    path: '/mobile/v1/schedules/schedule-1',
    token,
    body: { title: '  Edited title  ', instruction: ' Edited instruction ', everySeconds: 600 },
  });
  assert.equal(patched.status, 200);
  assert.equal(patched.body.supported, true);
  assert.equal(patched.body.updated, true);
  assert.equal(patched.body.changed, true);
  assert.deepEqual(adapter.calls.at(-1), {
    method: 'updateSchedule',
    scheduleId: 'schedule-1',
    parsed: {
      title: 'Edited title',
      instruction: 'Edited instruction',
      selector: 'everySeconds',
      change: { kind: 'every', every_seconds: 600 },
    },
  });

  const patchedDaily = await routeCall(router, {
    method: 'PATCH',
    path: '/mobile/v1/schedules/schedule-1',
    token,
    body: { dailyTime: '08:15', timeZone: 'UTC' },
  });
  assert.equal(patchedDaily.status, 200);
  assert.deepEqual(adapter.calls.at(-1).parsed.change, {
    kind: 'daily',
    daily: { time: '08:15', time_zone: 'UTC' },
  });

  for (const body of [
    {},
    { timeZone: 'UTC' },
    { title: '' },
    { title: 'x'.repeat(201) },
    { instruction: '' },
    { everySeconds: 60 },
    { everySeconds: 600, at: '2026-11-01T12:00:00Z' },
    { dailyTime: '9:00' },
    { at: 'not-a-date' },
  ]) {
    assertErrorBody(
      await routeCall(router, { method: 'PATCH', path: '/mobile/v1/schedules/schedule-1', token, body }),
      400,
      'invalid_request',
    );
  }
  assertErrorBody(
    await routeCall(router, { method: 'PATCH', path: '/mobile/v1/schedules/schedule-1', token, body: '{bad' }),
    400,
    'invalid_request',
  );
  assertErrorBody(
    await routeCall(router, { method: 'PATCH', path: '/mobile/v1/schedules/schedule_missing', token, body: { title: 'x' } }),
    404,
    'not_found',
  );

  assertErrorBody(
    await routeCall(router, { method: 'DELETE', path: '/mobile/v1/schedules/schedule_missing', token }),
    404,
    'not_found',
  );
  assertErrorBody(await routeCall(router, { path: '/mobile/v1/schedules' }), 401, 'unauthorized');
});

test('attachment upload validates body fields, base64, limits, session and capability', async () => {
  const { router, token } = setup();
  const path = '/mobile/v1/sessions/s_1/attachments';
  assertErrorBody(await routeCall(router, { method: 'POST', path, token, body: {} }), 400, 'invalid_request');
  assertErrorBody(await routeCall(router, { method: 'POST', path, token, body: '{bad' }), 400, 'invalid_request');
  assertErrorBody(
    await routeCall(router, { method: 'POST', path, token, body: { name: '', mimeType: 'image/png', dataBase64: PNG_BASE64 } }),
    400,
    'invalid_request',
  );
  assertErrorBody(
    await routeCall(router, { method: 'POST', path, token, body: { name: 'x', mimeType: 'not-a-mime', dataBase64: PNG_BASE64 } }),
    400,
    'invalid_request',
  );
  for (const dataBase64 of ['not base64!!', 'AAA', 'AB==', '']) {
    assertErrorBody(
      await routeCall(router, { method: 'POST', path, token, body: { name: 'x', mimeType: 'image/png', dataBase64 } }),
      400,
      'invalid_request',
    );
  }
  // Unknown session → 404.
  assertErrorBody(await uploadImage(router, token, 's_missing'), 404, 'not_found');
  // Capability missing → 501.
  const { router: unsupportedRouter, token: unsupportedToken } = setup({
    async uploadAttachment() {
      throw new ApiError('unsupported', 'Attachment storage is not available in this host process');
    },
  });
  assertErrorBody(await uploadImage(unsupportedRouter, unsupportedToken), 501, 'unsupported');
});

test('attachment upload enforces the configured encoded and decoded limits', async () => {
  const { router, token } = setup({}, { attachments: { maxBase64Bytes: 16, maxBytes: 8 } });
  // 32 bytes decoded → 44 base64 chars, above both configured limits.
  const payload = Buffer.alloc(32, 7).toString('base64');
  assertErrorBody(
    await routeCall(router, {
      method: 'POST',
      path: '/mobile/v1/sessions/s_1/attachments',
      token,
      body: { name: 'x.png', mimeType: 'image/png', dataBase64: payload },
    }),
    400,
    'invalid_request',
  );
  // 12 bytes decoded → 16 base64 chars: passes the encoded cap, fails the decoded cap.
  const smallEncoded = Buffer.alloc(12, 7).toString('base64');
  assertErrorBody(
    await routeCall(router, {
      method: 'POST',
      path: '/mobile/v1/sessions/s_1/attachments',
      token,
      body: { name: 'x.png', mimeType: 'image/png', dataBase64: smallEncoded },
    }),
    400,
    'invalid_request',
  );
});

test('prompt with an image attachment and empty text builds an image content part', async () => {
  const { router, adapter, token, attachments } = setup();
  const uploaded = await uploadImage(router, token);
  const attachmentId = uploaded.body.attachment.attachmentId;

  const accepted = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/sessions/s_1/prompt',
    token,
    body: { attachmentIds: [attachmentId] },
  });
  assert.equal(accepted.status, 200);
  assert.deepEqual(adapter.calls.at(-1), {
    method: 'prompt',
    sessionId: 's_1',
    content: [{ type: 'image', mediaType: 'image/png', data: PNG_BASE64, name: 'shot.png' }],
  });
  assert.equal(attachments.get(attachmentId), undefined, 'a successfully prompted attachment is consumed');

  // Consumed ids cannot be reused.
  assertErrorBody(
    await routeCall(router, {
      method: 'POST',
      path: '/mobile/v1/sessions/s_1/prompt',
      token,
      body: { attachmentIds: [attachmentId] },
    }),
    404,
    'not_found',
  );
});

test('prompt with text + file attachment builds text and receipt parts', async () => {
  const { router, adapter, token } = setup();
  const uploaded = await uploadFile(router, token);
  const attachmentId = uploaded.body.attachment.attachmentId;

  const accepted = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/sessions/s_1/prompt',
    token,
    body: { text: 'Summarise this', attachmentIds: [attachmentId] },
  });
  assert.equal(accepted.status, 200);
  const content = adapter.calls.at(-1).content;
  assert.equal(content.length, 2);
  assert.deepEqual(content[0], { type: 'text', text: 'Summarise this' });
  assert.equal(content[1].type, 'file');
  assert.match(content[1].receiptId, /^receipt_/);
});

test('attachment capacity is refused before anything is persisted through DSH', async () => {
  const { router, adapter, token } = setup({}, { attachments: { maxPendingCount: 1 } });
  assert.equal((await uploadImage(router, token)).status, 200);
  const persistedBefore = adapter.calls.filter((call) => call.method === 'uploadAttachment').length;
  assertErrorBody(await uploadImage(router, token), 400, 'invalid_request');
  assert.equal(
    adapter.calls.filter((call) => call.method === 'uploadAttachment').length,
    persistedBefore,
    'the refused upload never reached the DSH attachment service',
  );
});

test('attachment upload to a subagent/internal session is a documented 4xx (QA defect O3)', async () => {
  const { router, adapter, token } = setup();

  // Bare-UUID style subagent session id observed in live DSH data.
  const bareSubagentId = '351ca07c-6d26-46a8-a685-cb627d642806';
  const bare = await uploadImage(router, token, bareSubagentId);
  assertErrorBody(bare, 400, 'invalid_request');
  assert.match(bare.body.error.message, /subagent\/internal sessions/i);
  assert.match(bare.body.error.message, /parent session/i);

  const named = await uploadFile(router, token, 's_internal');
  assertErrorBody(named, 400, 'invalid_request');
  assert.match(named.body.error.message, /subagent\/internal sessions/i);

  assert.equal(
    adapter.calls.some((call) => call.method === 'uploadAttachment'),
    false,
    'no DSH attachment write is attempted for internal sessions',
  );

  // A regular user session still uploads.
  assert.equal((await uploadImage(router, token)).status, 200);
  assert.equal(adapter.calls.some((call) => call.method === 'uploadAttachment'), true);
});

test('a failed prompt leaves its attachments pending for a retry', async () => {
  const { router, adapter, token, attachments } = setup({
    async prompt() {
      throw new ApiError('internal', 'temporary admission failure');
    },
  });
  const uploaded = await uploadImage(router, token);
  const attachmentId = uploaded.body.attachment.attachmentId;

  const body = { attachmentIds: [attachmentId] };
  assertErrorBody(
    await routeCall(router, { method: 'POST', path: '/mobile/v1/sessions/s_1/prompt', token, body }),
    500,
    'internal',
  );
  assert.notEqual(attachments.get(attachmentId), undefined, 'not consumed by a failed prompt');

  adapter.prompt = async () => ({ accepted: true, runId: 'run_retry' });
  const accepted = await routeCall(router, { method: 'POST', path: '/mobile/v1/sessions/s_1/prompt', token, body });
  assert.equal(accepted.status, 200);
  assert.deepEqual(accepted.body, { accepted: true, runId: 'run_retry' });
  assert.equal(attachments.get(attachmentId), undefined, 'consumed by the successful retry');
});

test('prompt rejects unknown, expired, foreign-device and foreign-session attachments', async () => {
  let now = Date.now();
  const { router, token, pairing } = setup({}, {}, { attachmentNow: () => now });
  const uploaded = await uploadImage(router, token);
  const attachmentId = uploaded.body.attachment.attachmentId;

  // Unknown id.
  assertErrorBody(
    await routeCall(router, {
      method: 'POST',
      path: '/mobile/v1/sessions/s_1/prompt',
      token,
      body: { attachmentIds: ['att_does_not_exist'] },
    }),
    404,
    'not_found',
  );

  // Wrong session.
  assertErrorBody(
    await routeCall(router, {
      method: 'POST',
      path: '/mobile/v1/sessions/s_2/prompt',
      token,
      body: { attachmentIds: [attachmentId] },
    }),
    404,
    'not_found',
  );

  // Foreign device (a second paired phone).
  const otherDeviceToken = issueToken(pairing, { deviceName: 'Other phone' }).token;
  assertErrorBody(
    await routeCall(router, {
      method: 'POST',
      path: '/mobile/v1/sessions/s_1/prompt',
      token: otherDeviceToken,
      body: { attachmentIds: [attachmentId] },
    }),
    404,
    'not_found',
  );

  // Still valid for the owning device, then it expires after 24 h.
  assert.equal(
    (
      await routeCall(router, {
        method: 'POST',
        path: '/mobile/v1/sessions/s_1/prompt',
        token,
        body: { attachmentIds: [attachmentId] },
      })
    ).status,
    200,
  );
  const second = await uploadImage(router, token);
  now += 24 * 60 * 60 * 1000 + 1;
  assertErrorBody(
    await routeCall(router, {
      method: 'POST',
      path: '/mobile/v1/sessions/s_1/prompt',
      token,
      body: { attachmentIds: [second.body.attachment.attachmentId] },
    }),
    404,
    'not_found',
  );
});

test('prompt validates attachmentIds and mixed empty-text rules', async () => {
  const { router, token } = setup();
  const bad = async (body) =>
    assertErrorBody(
      await routeCall(router, { method: 'POST', path: '/mobile/v1/sessions/s_1/prompt', token, body }),
      400,
      'invalid_request',
    );

  await bad({ text: 'hi', attachmentIds: 'att_1' });
  await bad({ text: 'hi', attachmentIds: [''] });
  await bad({ text: 'hi', attachmentIds: [7] });
  await bad({ text: 'hi', attachmentIds: Array.from({ length: 9 }, (_, index) => `att_${index}`) });
  // Empty text without attachments stays invalid; a text-only empty prompt is invalid too.
  await bad({});
  await bad({ text: ' ' });
});

test('interrupt returns ok and forwards the session id', async () => {
  const { router, adapter, token } = setup();
  const result = await routeCall(router, { method: 'POST', path: '/mobile/v1/sessions/s_1/interrupt', token });
  assert.equal(result.status, 200);
  assert.deepEqual(result.body, { ok: true });
  assert.deepEqual(adapter.calls.at(-1), { method: 'interrupt', sessionId: 's_1' });
});

test('approval and question answering routes resolve via the adapter', async () => {
  const { router, adapter, token } = setup();
  const approvals = await routeCall(router, { path: '/mobile/v1/approvals', token });
  assert.deepEqual(approvals.body, { approvals: [], supported: true, readOnly: false });
  const questions = await routeCall(router, { path: '/mobile/v1/questions', token });
  assert.deepEqual(questions.body, { questions: [], supported: true, readOnly: false });

  const allowed = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/approvals/ap_1',
    token,
    body: { decision: 'allow' },
  });
  assert.equal(allowed.status, 200);
  assert.deepEqual(allowed.body, { ok: true, approvalId: 'ap_1', outcome: 'allowed-once' });
  assert.deepEqual(adapter.calls.at(-1), { method: 'resolveApproval', approvalId: 'ap_1', decision: 'allow' });

  const denied = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/approvals/ap_2',
    token,
    body: { decision: 'deny', note: 'not on this host' },
  });
  assert.equal(denied.status, 200);
  assert.deepEqual(denied.body, { ok: true, approvalId: 'ap_2', outcome: 'rejected' });
  assert.deepEqual(adapter.calls.at(-1), { method: 'resolveApproval', approvalId: 'ap_2', decision: 'deny' });

  assertErrorBody(
    await routeCall(router, { method: 'POST', path: '/mobile/v1/approvals/ap_1', token, body: { decision: 'maybe' } }),
    400,
    'invalid_request',
  );
  assertErrorBody(
    await routeCall(router, { method: 'POST', path: '/mobile/v1/approvals/ap_1', token, body: {} }),
    400,
    'invalid_request',
  );
  const answered = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/questions/q_1',
    token,
    body: { answer: 'Ship now', optionIndex: 0 },
  });
  assert.equal(answered.status, 200);
  assert.deepEqual(answered.body, { ok: true, questionId: 'q_1', answered: 1, total: 1, complete: true });
  assert.deepEqual(adapter.calls.at(-1), {
    method: 'answerQuestion',
    questionId: 'q_1',
    answer: 'Ship now',
    optionIndex: 0,
  });

  const freeText = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/questions/q_2',
    token,
    body: { answer: '  custom text  ' },
  });
  assert.equal(freeText.status, 200);
  assert.deepEqual(adapter.calls.at(-1), {
    method: 'answerQuestion',
    questionId: 'q_2',
    answer: '  custom text  ',
    optionIndex: undefined,
  });

  assertErrorBody(
    await routeCall(router, { method: 'POST', path: '/mobile/v1/questions/q_1', token, body: {} }),
    400,
    'invalid_request',
  );
  assertErrorBody(
    await routeCall(router, { method: 'POST', path: '/mobile/v1/questions/q_1', token, body: { answer: 'x', optionIndex: -1 } }),
    400,
    'invalid_request',
  );
});

test('events route returns an SSE descriptor with the replay cursor', async () => {
  const { router, token, deviceId } = setup();
  const result = await routeCall(router, { path: '/mobile/v1/events', token, query: 'since=ev_000123' });
  assert.deepEqual(result, { status: 200, sse: true, since: 'ev_000123', deviceId });

  const noCursor = await routeCall(router, { path: '/mobile/v1/events', token });
  assert.equal(noCursor.sse, true);
  assert.equal(noCursor.since, undefined);
});

test('unexpected adapter failures become internal errors without leaking details', async () => {
  const { router, token } = setup({
    async listSessions() {
      throw new Error('secret stack trace with C:\\demo\\paths');
    },
  });
  const result = await routeCall(router, { path: '/mobile/v1/sessions', token });
  assertErrorBody(result, 500, 'internal');
  assert.equal(result.body.error.message.includes('secret'), false);
});

/* ------------------------------------------------------------------------- */
/* Curated hidden-session management endpoints — task-16                       */
/* ------------------------------------------------------------------------- */

test('GET /sessions/hidden returns the current hidden state and requires a token', async () => {
  const { router, token } = setup();
  const result = await routeCall(router, { path: '/mobile/v1/sessions/hidden', token });
  assert.equal(result.status, 200);
  assert.deepEqual(result.body, { ids: [], updatedAt: null });

  assertErrorBody(await routeCall(router, { path: '/mobile/v1/sessions/hidden' }), 401, 'unauthorized');
});

test('PUT /sessions/hidden persists the list, is readable again, and writes the file shape', async (t) => {
  const hiddenFile = await tempHiddenFile(t);
  const hiddenStore = createHiddenStore({ filePath: hiddenFile });
  const { router, token } = setup({}, {}, { hiddenStore });
  const ids = ['session-0988fac4-e222-4309-8a11-1b42cf50b524', 'session-16016a8a-453f-44a3-ac2a-055b5868c629'];

  const put = await routeCall(router, {
    method: 'PUT',
    path: '/mobile/v1/sessions/hidden',
    token,
    body: { hiddenIds: ids },
  });
  assert.equal(put.status, 200);
  assert.deepEqual(put.body.ids, ids);
  assert.match(put.body.updatedAt, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);

  const get = await routeCall(router, { path: '/mobile/v1/sessions/hidden', token });
  assert.deepEqual(get.body, put.body);

  // The file itself is the durable contract: { updatedAt, ids }.
  const document = JSON.parse(await readFile(hiddenFile, 'utf8'));
  assert.deepEqual(document.ids, ids);
  assert.equal(document.updatedAt, put.body.updatedAt);

  // A fresh store (host restart) loads the same list from disc.
  const reloaded = new HiddenSessionStore({ filePath: hiddenFile, checkIntervalMs: 0 });
  await reloaded.load();
  assert.deepEqual(reloaded.getState().ids, ids);
});

test('PUT /sessions/hidden validates ids, shape, and the 500-id cap', async () => {
  const { router, token } = setup();
  const put = (body) => routeCall(router, { method: 'PUT', path: '/mobile/v1/sessions/hidden', token, body });

  assertErrorBody(await put('{bad json'), 400, 'invalid_request');
  assertErrorBody(await put({}), 400, 'invalid_request');
  assertErrorBody(await put({ hiddenIds: 'session-abc' }), 400, 'invalid_request');
  assertErrorBody(await put({ hiddenIds: [7] }), 400, 'invalid_request');
  assertErrorBody(await put({ hiddenIds: [''] }), 400, 'invalid_request');
  assertErrorBody(await put({ hiddenIds: ['not-a-session'] }), 400, 'invalid_request');
  assertErrorBody(await put({ hiddenIds: ['session-'.padEnd(300, 'x')] }), 400, 'invalid_request');
  assertErrorBody(
    await put({ hiddenIds: Array.from({ length: 501 }, (_, index) => `session-${index}`) }),
    400,
    'invalid_request',
  );

  // Duplicates are de-duplicated, not rejected, and the empty list clears.
  const deduped = await put({ hiddenIds: ['session-abc', 'session-abc'] });
  assert.equal(deduped.status, 200);
  assert.deepEqual(deduped.body.ids, ['session-abc']);
  const cleared = await put({ hiddenIds: [] });
  assert.deepEqual(cleared.body.ids, []);
});

test('hidden list refreshes when the file changes on disc (mtime cache)', async (t) => {
  const hiddenFile = await tempHiddenFile(t);
  const hiddenStore = createHiddenStore({ filePath: hiddenFile, checkIntervalMs: 0 });
  const { router, token } = setup({}, {}, { hiddenStore });
  const first = 'session-0988fac4-e222-4309-8a11-1b42cf50b524';
  const second = 'session-16016a8a-453f-44a3-ac2a-055b5868c629';

  await writeFile(hiddenFile, JSON.stringify({ updatedAt: new Date().toISOString(), ids: [first] }), 'utf8');
  const one = await routeCall(router, { path: '/mobile/v1/sessions/hidden', token });
  assert.deepEqual(one.body.ids, [first]);

  // Externally edit the file (not through PUT); bump mtime explicitly so the
  // cache invalidation is deterministic on coarse filesystems.
  await writeFile(hiddenFile, JSON.stringify({ updatedAt: new Date().toISOString(), ids: [second] }), 'utf8');
  const later = new Date(Date.now() + 5000);
  await utimes(hiddenFile, later, later);

  const two = await routeCall(router, { path: '/mobile/v1/sessions/hidden', token });
  assert.deepEqual(two.body.ids, [second]);
});

test('hidden sessions remain addressable: detail and uploads still work', async (t) => {
  const hiddenFile = await tempHiddenFile(t);
  const hiddenStore = createHiddenStore({ filePath: hiddenFile });
  const { router, token } = setup({}, {}, { hiddenStore });
  const hiddenId = 'session-hidden-user-1';

  const put = await routeCall(router, {
    method: 'PUT',
    path: '/mobile/v1/sessions/hidden',
    token,
    body: { hiddenIds: [hiddenId] },
  });
  assert.equal(put.status, 200);

  // GET /sessions/{id} keeps working for a hidden session.
  const detail = await routeCall(router, { path: `/mobile/v1/sessions/${hiddenId}`, token });
  assert.equal(detail.status, 200);
  assert.equal(detail.body.session.id, 's_1');

  // Attachment uploads are NOT rejected merely because the session is hidden.
  const upload = await routeCall(router, {
    method: 'POST',
    path: `/mobile/v1/sessions/${hiddenId}/attachments`,
    token,
    body: { name: 'shot.png', mimeType: 'image/png', dataBase64: PNG_BASE64 },
  });
  assert.equal(upload.status, 200);
  assert.equal(upload.body.attachment.kind, 'image');

  // A prompt is likewise unaffected.
  const prompt = await routeCall(router, {
    method: 'POST',
    path: `/mobile/v1/sessions/${hiddenId}/prompt`,
    token,
    body: { text: 'Hello hidden session' },
  });
  assert.equal(prompt.status, 200);
});

test('goal edit and clear routes forward validated requests to the adapter', async () => {
  const { router, adapter, token } = setup();

  const edited = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/sessions/s_1/goal/edit',
    token,
    body: { objective: '  Updated objective  ', maxGoalRounds: 60 },
  });
  assert.equal(edited.status, 200);
  assert.deepEqual(adapter.calls.at(-1), {
    method: 'editSessionGoal',
    sessionId: 's_1',
    request: { objective: 'Updated objective', maxGoalRounds: 60 },
  });
  assert.equal(edited.body.supported, true);
  assert.equal(edited.body.live, true);
  assert.equal(edited.body.goal.objective, 'Updated objective');
  assert.equal(edited.body.goal.maxGoalRounds, 60);

  const roundsOnly = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/sessions/s_1/goal/edit',
    token,
    body: { maxGoalRounds: 200 },
  });
  assert.equal(roundsOnly.status, 200);
  assert.deepEqual(adapter.calls.at(-1).request, { maxGoalRounds: 200 });

  const cleared = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/sessions/s_1/goal/clear',
    token,
  });
  assert.equal(cleared.status, 200);
  assert.deepEqual(cleared.body, { supported: true, live: true, goal: null });
  assert.deepEqual(adapter.calls.at(-1), { method: 'clearSessionGoal', sessionId: 's_1' });
});

test('goal complete route forwards optional ref to the adapter', async () => {
  const { router, adapter, token } = setup();
  const completed = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/sessions/s_1/goal/complete',
    token,
  });
  assert.equal(completed.status, 200);
  assert.deepEqual(adapter.calls.at(-1), {
    method: 'completeSessionGoal',
    sessionId: 's_1',
    ref: {},
  });
  assert.deepEqual(completed.body, {
    supported: true,
    live: true,
    goal: { id: 'goal_1', phase: 'complete', revision: 4 },
  });

  // A client-supplied ref is forwarded so the adapter can reject a stale dialog.
  const withBody = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/sessions/s_1/goal/complete',
    token,
    body: { id: 'goal_1', revision: 4 },
  });
  assert.equal(withBody.status, 200);
  assert.deepEqual(adapter.calls.at(-1), {
    method: 'completeSessionGoal',
    sessionId: 's_1',
    ref: { id: 'goal_1', revision: 4 },
  });

  const badRevision = await routeCall(router, {
    method: 'POST',
    path: '/mobile/v1/sessions/s_1/goal/complete',
    token,
    body: { revision: 0 },
  });
  assertErrorBody(badRevision, 400, 'invalid_request');
});

test('goal edit validates objective, round cap and JSON body', async () => {
  const { router, adapter, token } = setup();
  const path = '/mobile/v1/sessions/s_1/goal/edit';
  for (const body of [
    {},
    { objective: '' },
    { objective: '   ' },
    { objective: 42 },
    { maxGoalRounds: 0 },
    { maxGoalRounds: -1 },
    { maxGoalRounds: 1.5 },
    { maxGoalRounds: '5' },
    { maxGoalRounds: 10_001 },
  ]) {
    assertErrorBody(await routeCall(router, { method: 'POST', path, token, body }), 400, 'invalid_request');
  }
  assertErrorBody(
    await routeCall(router, { method: 'POST', path, token, body: '{bad json' }),
    400,
    'invalid_request',
  );
  assert.equal(
    adapter.calls.some((call) => call.method === 'editSessionGoal'),
    false,
    'validation refusals never reach the adapter',
  );
});


test('session snapshot revalidates with a weak ETag and returns an empty 304', async () => {
  const { router, token } = setup();
  const first = await routeCall(router, { path: '/mobile/v1/sessions', token });
  assert.equal(first.status, 200);
  const etag = first.headers?.ETag;
  assert.equal(typeof etag, 'string');
  assert.match(etag, /^W\/"[0-9a-f]{20}"$/);
  const second = await routeCall(router, {
    path: '/mobile/v1/sessions',
    token,
    headers: { 'if-none-match': etag },
  });
  assert.equal(second.status, 304);
  assert.equal(second.headers?.ETag, etag);
  assert.equal(second.body, undefined);
  const stale = await routeCall(router, {
    path: '/mobile/v1/sessions',
    token,
    headers: { 'if-none-match': 'W/"stale-0000000000000"' },
  });
  assert.equal(stale.status, 200);
});
