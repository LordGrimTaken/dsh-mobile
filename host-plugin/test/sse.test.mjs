import test from 'node:test';
import assert from 'node:assert/strict';

import { EventBus } from '../lib/events.js';
import { createRouter } from '../lib/router.js';
import { createMobileServer } from '../lib/server.js';
import { createFakeAdapter, issueToken, makePairing, testConfig, SseReader } from './helpers.mjs';

const silent = { info() {}, warn() {}, error() {} };

/** Boot a real HTTP server on an ephemeral loopback port. */
async function startServer(t, { adapter: adapterOverrides = {}, heartbeatMs = 5000 } = {}) {
  const config = testConfig({ sse: { heartbeatMs } });
  const pairing = makePairing(config);
  const adapter = createFakeAdapter(adapterOverrides);
  const bus = new EventBus({ bufferSize: 100, logger: silent });
  const router = createRouter({ config, pairing, adapter, bus, logger: silent });
  const server = createMobileServer({ config, router, bus, adapter, pairing, logger: silent });
  const address = await server.start();
  t.after(() => server.close());
  const issued = issueToken(pairing);
  return {
    url: `http://127.0.0.1:${address.port}`,
    token: issued.token,
    deviceId: issued.device.deviceId,
    bus,
    pairing,
    adapter,
    server,
  };
}

/** Open the SSE stream and always tear it down. */
async function connect(t, url, token, { since } = {}) {
  const controller = new AbortController();
  const query = since === undefined ? '' : `?since=${encodeURIComponent(since)}`;
  const response = await fetch(`${url}/mobile/v1/events${query}`, {
    headers: { authorization: `Bearer ${token}`, accept: 'text/event-stream' },
    signal: controller.signal,
  });
  const reader = new SseReader(response);
  t.after(async () => {
    await reader.close();
    controller.abort();
  });
  return { response, reader, controller };
}

test('SSE stream opens with a per-connection host.status envelope and no id', async (t) => {
  const { url, token } = await startServer(t);
  const { response, reader } = await connect(t, url, token);

  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-type') ?? '', /^text\/event-stream/);
  assert.match(response.headers.get('cache-control') ?? '', /no-cache/);

  const frame = await reader.nextFrame();
  assert.equal(frame.event, 'host.status');
  assert.equal(frame.id, undefined, 'per-connection frames carry no id:');
  assert.equal(frame.data.type, 'host.status');
  assert.deepEqual(frame.data.data, { connected: true });
  assert.match(frame.data.at, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
});

test('published events arrive as envelope frames with id, event, and data lines', async (t) => {
  const { url, token, bus } = await startServer(t);
  const { reader } = await connect(t, url, token);
  await reader.nextFrame(); // host.status

  const published = bus.publish(
    'message.delta',
    { sessionId: 's_1', messageId: 'm_9', text: 'All 128 ' },
    { sessionId: 's_1' },
  );
  assert.equal(published.id, 'ev_000001');

  const frame = await reader.nextFrame();
  assert.equal(frame.id, 'ev_000001');
  assert.equal(frame.event, 'message.delta');
  assert.equal(frame.data.id, 'ev_000001');
  assert.equal(frame.data.type, 'message.delta');
  assert.equal(frame.data.sessionId, 's_1');
  assert.deepEqual(frame.data.data, { sessionId: 's_1', messageId: 'm_9', text: 'All 128 ' });
});

test('a known since cursor replays only later events', async (t) => {
  const { url, token, bus } = await startServer(t);
  const first = bus.publish('session.removed', { sessionId: 's_1' }, { sessionId: 's_1' });
  bus.publish('session.removed', { sessionId: 's_2' }, { sessionId: 's_2' });

  const { reader } = await connect(t, url, token, { since: first.id });
  const status = await reader.nextFrame();
  assert.equal(status.event, 'host.status');

  const replayed = await reader.nextFrame();
  assert.equal(replayed.id, 'ev_000002');
  assert.equal(replayed.event, 'session.removed');
  assert.deepEqual(replayed.data.data, { sessionId: 's_2' });
});

test('an unknown since cursor triggers a snapshot resync, then live events resume', async (t) => {
  const { url, token, bus } = await startServer(t);
  const { reader } = await connect(t, url, token, { since: 'ev_999999' });

  const status = await reader.nextFrame();
  assert.equal(status.event, 'host.status');

  const snapshot = await reader.nextFrame();
  assert.equal(snapshot.event, 'session.updated');
  assert.equal(snapshot.id, undefined, 'snapshot frames must not advance the cursor');
  assert.equal(snapshot.data.type, 'session.updated');
  assert.equal(snapshot.data.data.id, 's_1');

  const published = bus.publish('message.added', { sessionId: 's_1', message: { id: 'm_1' } }, { sessionId: 's_1' });
  const frame = await reader.nextFrame();
  assert.equal(frame.id, published.id);
  assert.equal(frame.event, 'message.added');
});

test('the heartbeat is an envelope-shaped ping with empty data and no id', async (t) => {
  const { url, token } = await startServer(t, { heartbeatMs: 100 });
  const { reader } = await connect(t, url, token);
  await reader.nextFrame(); // host.status

  const pings = await reader.until((frame) => frame.event === 'ping', 4000);
  const ping = pings.at(-1);
  assert.equal(ping.id, undefined);
  assert.equal(ping.data.type, 'ping');
  assert.deepEqual(ping.data.data, {});
});

test('SSE without a valid token is a JSON 401, not a stream', async (t) => {
  const { url } = await startServer(t);
  const response = await fetch(`${url}/mobile/v1/events`, {
    headers: { authorization: `Bearer ${'x'.repeat(43)}` },
  });
  assert.equal(response.status, 401);
  assert.match(response.headers.get('content-type') ?? '', /application\/json/);
  assert.deepEqual(await response.json(), {
    error: { code: 'unauthorized', message: 'Missing or invalid device token' },
  });
});

test('revoking a device closes its already-open SSE stream', async (t) => {
  const { url, token, pairing, deviceId } = await startServer(t);
  const { reader } = await connect(t, url, token);
  await reader.nextFrame(); // host.status

  assert.equal(await pairing.revokeDevice(deviceId), true);
  await assert.rejects(async () => {
    for (;;) await reader.nextFrame(3000);
  }, /SSE stream ended/);
});
