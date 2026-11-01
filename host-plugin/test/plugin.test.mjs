import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { readFile } from 'node:fs/promises';

import { apply } from '../lib/index.js';
import { freePort, tempStateFile, waitFor } from './helpers.mjs';

/** Minimal Cordis-like plugin context with effect disposal tracking. */
function createFakePluginCtx(services = {}) {
  /** @type {Map<string, Function[]>} */
  const listeners = new Map();
  /** @type {Array<() => any>} */
  const effects = [];
  return {
    effects,
    get(name) {
      return services[name];
    },
    on(event, listener) {
      const list = listeners.get(event) ?? [];
      list.push(listener);
      listeners.set(event, list);
      return () => listeners.set(event, (listeners.get(event) ?? []).filter((entry) => entry !== listener));
    },
    effect(callback) {
      const disposer = callback();
      effects.push(disposer);
      return disposer;
    },
    listenerCount(event) {
      return (listeners.get(event) ?? []).length;
    },
    async dispose() {
      while (effects.length > 0) {
        const disposer = effects.pop();
        await disposer?.();
      }
    },
  };
}

async function jsonRequest(url, options = {}) {
  const response = await fetch(url, options);
  const text = await response.text();
  return { response, body: text === '' ? null : JSON.parse(text) };
}

test('the plugin starts a loopback API, pairs a device, persists hashed state, and stops on dispose', async (t) => {
  const port = await freePort();
  const stateFile = await tempStateFile(t);
  const ctx = createFakePluginCtx();
  const base = `http://127.0.0.1:${port}`;

  apply(ctx, { port, stateFile, hostName: 'test-host', publicBaseUrl: 'https://pc.tailnet.ts.net' });

  await waitFor(async () => {
    try {
      return (await fetch(`${base}/health`)).ok;
    } catch {
      return false;
    }
  });

  const health = await jsonRequest(`${base}/health`);
  assert.equal(health.response.status, 200);
  assert.deepEqual(health.body, { ok: true, version: '0.3.13', apiVersion: 1, paired: false });

  // Without a paired device, every authenticated endpoint is 401.
  const unauthenticated = await jsonRequest(`${base}/mobile/v1/sessions`);
  assert.equal(unauthenticated.response.status, 401);
  assert.equal(unauthenticated.body.error.code, 'unauthorized');

  // Desktop pairing: code, then complete.
  const pairNew = await jsonRequest(`${base}/mobile/v1/pair/new`, { method: 'POST' });
  assert.equal(pairNew.response.status, 200);
  assert.match(pairNew.body.code, /^\d{6}$/);
  assert.match(pairNew.body.qrPayload, /^dshmobile:\/\/pair\?base=https%3A%2F%2Fpc\.tailnet\.ts\.net&code=\d{6}&host=test-host$/);

  const complete = await jsonRequest(`${base}/mobile/v1/pair/complete`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code: pairNew.body.code, deviceName: 'Pixel 9', platform: 'android' }),
  });
  assert.equal(complete.response.status, 200);
  assert.match(complete.body.token, /^[A-Za-z0-9_-]{43}$/);
  assert.match(complete.body.deviceId, /^dev_/);
  assert.deepEqual(complete.body.host, { name: 'test-host', apiVersion: 1, version: '0.3.13' });
  assert.deepEqual(complete.body.workspaces, [], 'no DSH services in the fake ctx');

  const pairedHealth = await jsonRequest(`${base}/health`);
  assert.equal(pairedHealth.body.paired, true);

  const host = await jsonRequest(`${base}/mobile/v1/host`, {
    headers: { authorization: `Bearer ${complete.body.token}` },
  });
  assert.deepEqual(host.body, { name: 'test-host', version: '0.3.13', workspaces: [] });

  // Push is wired by the real plugin entry point even when DSH services are
  // absent: GET returns the disabled default and PUT persists a config.
  const push = await jsonRequest(`${base}/mobile/v1/push`, {
    headers: { authorization: `Bearer ${complete.body.token}` },
  });
  assert.equal(push.response.status, 200);
  assert.deepEqual(push.body, {
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
  const pushUpdate = await jsonRequest(`${base}/mobile/v1/push`, {
    method: 'PUT',
    headers: { authorization: `Bearer ${complete.body.token}`, 'content-type': 'application/json' },
    body: JSON.stringify({ enabled: true, topic: 'plugin_test_topic' }),
  });
  assert.equal(pushUpdate.response.status, 200);
  assert.deepEqual(pushUpdate.body, {
    supported: true,
    enabled: true,
    server: 'https://ntfy.sh',
    topic: 'plugin_test_topic',
    hasToken: false,
  });

  // The HTTP layer stays thin: with no DSH services, data endpoints are 501.
  const sessions = await jsonRequest(`${base}/mobile/v1/sessions`, {
    headers: { authorization: `Bearer ${complete.body.token}` },
  });
  assert.equal(sessions.response.status, 501);
  assert.deepEqual(sessions.body, {
    error: { code: 'unsupported', message: 'DSH Session Controller is not available in this host process' },
  });

  // Attachment upload needs the DSH attachment capability; without it the
  // route answers 501 (validation happens before the adapter call).
  const attachmentUpload = await jsonRequest(`${base}/mobile/v1/sessions/s_1/attachments`, {
    method: 'POST',
    headers: { authorization: `Bearer ${complete.body.token}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      name: 'shot.png',
      mimeType: 'image/png',
      dataBase64: Buffer.from([0x89, 0x50, 0x4e, 0x47]).toString('base64'),
    }),
  });
  assert.equal(attachmentUpload.response.status, 501);
  assert.equal(attachmentUpload.body.error.code, 'unsupported');

  // Pairing state was persisted atomically and only as a sha256 hash.
  const saved = await waitFor(async () => {
    try {
      const state = JSON.parse(await readFile(stateFile, 'utf8'));
      return state.devices?.length === 1 ? state : false;
    } catch {
      return false;
    }
  });
  assert.match(saved.devices[0].tokenHash, /^[0-9a-f]{64}$/);
  assert.equal(saved.devices[0].deviceName, 'Pixel 9');
  assert.equal(JSON.stringify(saved).includes(complete.body.token), false, 'raw token is never persisted');

  // Plugin unload closes the carrier.
  await ctx.dispose();
  await waitFor(async () => {
    try {
      await fetch(`${base}/health`);
      return false;
    } catch {
      return true;
    }
  });

  // A fresh plugin run restores the paired device from disc.
  const restartedPort = await freePort();
  const restarted = createFakePluginCtx();
  apply(restarted, { port: restartedPort, stateFile, hostName: 'test-host' });
  await waitFor(async () => {
    try {
      return (await fetch(`http://127.0.0.1:${restartedPort}/health`)).ok;
    } catch {
      return false;
    }
  });
  const restoredHealth = await jsonRequest(`http://127.0.0.1:${restartedPort}/health`);
  assert.equal(restoredHealth.body.paired, true);
  const restoredHost = await jsonRequest(`http://127.0.0.1:${restartedPort}/mobile/v1/host`, {
    headers: { authorization: `Bearer ${complete.body.token}` },
  });
  assert.equal(restoredHost.response.status, 200, 'the previously issued token still authenticates');
  await restarted.dispose();
});

test('a bad bind configuration is logged and does not throw out of apply (host stays up)', () => {
  const ctx = createFakePluginCtx();
  const originalError = console.error;
  console.error = () => {};
  try {
    assert.doesNotThrow(() => apply(ctx, { host: '0.0.0.0', port: 0 }));
  } finally {
    console.error = originalError;
  }
  assert.equal(ctx.effects.length, 0, 'invalid configuration activates nothing');
  assert.equal(ctx.listenerCount('session/event'), 0);
});

test('a disabled plugin starts no server and registers no listeners', async (t) => {
  const port = await freePort();
  const stateFile = await tempStateFile(t);
  const ctx = createFakePluginCtx();
  apply(ctx, { enabled: false, port, stateFile });
  assert.equal(ctx.effects.length, 0);
  assert.equal(ctx.listenerCount('session/event'), 0);
  await assert.rejects(() => fetch(`http://127.0.0.1:${port}/health`));
});

test('the plugin serves the hidden-session management API and persists next to the state file', async (t) => {
  const port = await freePort();
  const stateFile = await tempStateFile(t);
  const ctx = createFakePluginCtx();
  const base = `http://127.0.0.1:${port}`;
  apply(ctx, { port, stateFile, hostName: 'hidden-test-host' });

  await waitFor(async () => {
    try {
      return (await fetch(`${base}/health`)).ok;
    } catch {
      return false;
    }
  });

  const pairNew = await jsonRequest(`${base}/mobile/v1/pair/new`, { method: 'POST' });
  const complete = await jsonRequest(`${base}/mobile/v1/pair/complete`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code: pairNew.body.code, deviceName: 'Pixel 9', platform: 'android' }),
  });
  const auth = { authorization: `Bearer ${complete.body.token}` };

  const empty = await jsonRequest(`${base}/mobile/v1/sessions/hidden`, { headers: auth });
  assert.equal(empty.response.status, 200);
  assert.deepEqual(empty.body, { ids: [], updatedAt: null });

  const hiddenId = 'session-0988fac4-e222-4309-8a11-1b42cf50b524';
  const put = await jsonRequest(`${base}/mobile/v1/sessions/hidden`, {
    method: 'PUT',
    headers: { ...auth, 'content-type': 'application/json' },
    body: JSON.stringify({ hiddenIds: [hiddenId] }),
  });
  assert.equal(put.response.status, 200);
  assert.deepEqual(put.body.ids, [hiddenId]);

  // Default location: same directory as the pairing state file.
  const hiddenFile = path.join(path.dirname(stateFile), 'hidden-sessions.json');
  const saved = JSON.parse(await readFile(hiddenFile, 'utf8'));
  assert.deepEqual(saved.ids, [hiddenId]);

  const get = await jsonRequest(`${base}/mobile/v1/sessions/hidden`, { headers: auth });
  assert.deepEqual(get.body.ids, [hiddenId]);

  await ctx.dispose();
});

