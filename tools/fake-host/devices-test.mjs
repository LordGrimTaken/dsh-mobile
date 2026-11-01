#!/usr/bin/env node
/**
 * tools/fake-host/devices-test.mjs
 *
 * Self-contained test for the fake host's paired-device management:
 *   - spawns a fake host and pairs a device through the real pair endpoints
 *     (live pairings share the fixed dev token; each gets a unique deviceId),
 *   - GET /devices -> {supported:true, devices:[{deviceId,deviceName,platform,
 *     pairedAt,lastSeenAt}], currentDeviceId} with no tokens/secrets and the
 *     current marker matching the caller's newest device,
 *   - DELETE /devices/{id} revokes the seeded legacy device (its own token) ->
 *     {ok:true,revoked:true}, the device disappears from the list and its token
 *     answers 401 afterwards,
 *   - DELETE of the current device -> 400, unknown device -> 404,
 *   - GET/DELETE without a bearer token -> 401.
 *
 *   node tools/fake-host/devices-test.mjs
 *
 * No dependencies; exits 0 only when every check passes.
 */
import net from 'node:net';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = path.dirname(fileURLToPath(import.meta.url));
const SERVER = path.join(DIR, 'server.mjs');
const NODE = process.execPath;
const DEVICE_SHAPE = 'deviceId,deviceName,lastSeenAt,pairedAt,platform';
const LEGACY_DEVICE_ID = 'dev_seed_legacy';
const LEGACY_DEVICE_TOKEN = 'legacy-dev-token';

let passCount = 0;
let failCount = 0;
const failures = [];

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function assertEqual(actual, expected, label) {
  if (!Object.is(actual, expected)) {
    throw new Error(`${label}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

function assertMatch(value, pattern, label) {
  if (!pattern.test(String(value ?? ''))) {
    throw new Error(`${label}: ${JSON.stringify(value)} does not match ${pattern}`);
  }
}

async function check(name, fn) {
  try {
    const detail = await fn();
    passCount += 1;
    console.log(`[PASS] ${name}${detail ? ` - ${detail}` : ''}`);
  } catch (error) {
    failCount += 1;
    const message = error instanceof Error ? error.message : String(error);
    failures.push(`${name}: ${message}`);
    console.log(`[FAIL] ${name} - ${message}`);
  }
}

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

/** Start one fake host; returns { baseUrl, output(), stop() }. */
async function startServer(extraEnv = {}) {
  const port = await freePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  let output = '';
  const child = spawn(NODE, [SERVER], {
    cwd: DIR,
    env: {
      ...process.env,
      PORT: String(port),
      FAKE_BIND: '127.0.0.1',
      FAKE_QUIET: '1',
      FAKE_HEARTBEAT_MS: '5000',
      ...extraEnv,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });
  child.stdout.on('data', (chunk) => {
    output += chunk.toString();
  });
  child.stderr.on('data', (chunk) => {
    output += chunk.toString();
  });
  for (let attempt = 0; attempt < 100; attempt += 1) {
    try {
      const response = await fetch(`${baseUrl}/mobile/v1/health`);
      if (response.ok) {
        return {
          baseUrl,
          output: () => output,
          async stop() {
            if (child.exitCode !== null) return;
            const exited = new Promise((resolve) => child.once('exit', resolve));
            child.kill('SIGTERM');
            await Promise.race([exited, sleep(2000)]);
            if (child.exitCode === null) child.kill('SIGKILL');
          },
        };
      }
    } catch {
      // not listening yet
    }
    await sleep(100);
  }
  child.kill('SIGKILL');
  throw new Error(`fake host did not start within 10s. Log:\n${output}`);
}

async function api(baseUrl, method, requestPath, body, token) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(`${baseUrl}${requestPath}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let json = null;
  if (text) {
    try {
      json = JSON.parse(text);
    } catch {
      json = null;
    }
  }
  return { status: response.status, json, text };
}

/** Pair one device via the real endpoints; returns {token, deviceId}. */
async function pairDevice(baseUrl, deviceName, platform) {
  const started = await api(baseUrl, 'POST', '/mobile/v1/pair/new');
  assertEqual(started.status, 200, 'pair/new status');
  const code = started.json?.code;
  assertMatch(code, /^\d{6}$/, 'pair code');
  const completed = await api(baseUrl, 'POST', '/mobile/v1/pair/complete', {
    code,
    deviceName,
    platform,
  });
  assertEqual(completed.status, 200, 'pair/complete status');
  assert(completed.json?.token, 'pair token');
  assert(completed.json?.deviceId, 'pair deviceId');
  return { token: completed.json.token, deviceId: completed.json.deviceId };
}

async function listDevices(baseUrl, token) {
  const result = await api(baseUrl, 'GET', '/mobile/v1/devices', undefined, token);
  assertEqual(result.status, 200, 'GET /devices status');
  assertEqual(result.json?.supported, true, 'devices supported');
  assert(Array.isArray(result.json?.devices), 'devices array');
  return result.json;
}

function assertIso(value, label) {
  assert(typeof value === 'string' && !Number.isNaN(Date.parse(value)), `${label} must be an ISO timestamp`);
}

async function main() {
  console.log('[devices-test] starting fake host on a random loopback port...');
  const server = await startServer();
  console.log(`[devices-test] fake host ready at ${server.baseUrl}`);

  let tokenA = '';
  let deviceIdA = '';
  try {
    await check('pairing returns the fixed dev token and a unique device id', async () => {
      const device = await pairDevice(server.baseUrl, 'QA Pixel', 'android');
      tokenA = device.token;
      deviceIdA = device.deviceId;
      assertEqual(tokenA, 'dev-token', 'live pairing token is the fixed dev token');
      assertMatch(deviceIdA, /^dev_[0-9a-f]+$/, 'deviceId format');
      return deviceIdA;
    });

    await check('GET /devices returns metadata + currentDeviceId with no tokens', async () => {
      const result = await listDevices(server.baseUrl, tokenA);
      assertEqual(result.currentDeviceId, deviceIdA, 'currentDeviceId matches the paired device');
      assert(result.devices.length >= 2, 'seeded legacy device plus the paired one');

      for (const device of result.devices) {
        assertEqual(Object.keys(device).sort().join(','), DEVICE_SHAPE, 'device shape');
        assertMatch(device.deviceId, /^dev_/, 'deviceId prefix');
        assert(typeof device.deviceName === 'string' && device.deviceName.length > 0, 'deviceName');
        assert(typeof device.platform === 'string' && device.platform.length > 0, 'platform');
        assertIso(device.pairedAt, 'pairedAt');
        assertIso(device.lastSeenAt, 'lastSeenAt');
        assertEqual(device.token, undefined, 'token redacted');
      }
      assert(
        result.devices.some((device) => device.deviceId === LEGACY_DEVICE_ID),
        'seeded legacy device present',
      );
      const current = result.devices.find((device) => device.deviceId === deviceIdA);
      assertEqual(current.deviceName, 'QA Pixel', 'current device name');
      assertEqual(current.platform, 'android', 'current device platform');
      assert(!JSON.stringify(result).includes(tokenA), 'dev-token value not present in response');
      return `${result.devices.length} devices; current=${deviceIdA}`;
    });

    await check('DELETE revokes the legacy device and its token answers 401 afterwards', async () => {
      const revoked = await api(
        server.baseUrl,
        'DELETE',
        `/mobile/v1/devices/${LEGACY_DEVICE_ID}`,
        undefined,
        tokenA,
      );
      assertEqual(revoked.status, 200, 'revoke status');
      assertEqual(revoked.json?.ok, true, 'revoke ok');
      assertEqual(revoked.json?.revoked, true, 'revoke flag');

      const revokedTokenRequest = await api(
        server.baseUrl,
        'GET',
        '/mobile/v1/devices',
        undefined,
        LEGACY_DEVICE_TOKEN,
      );
      assertEqual(revokedTokenRequest.status, 401, 'revoked token status');
      assertEqual(revokedTokenRequest.json?.error?.code, 'unauthorized', 'revoked token code');

      const result = await listDevices(server.baseUrl, tokenA);
      assert(
        !result.devices.some((device) => device.deviceId === LEGACY_DEVICE_ID),
        'revoked device removed from list',
      );
      assert(result.devices.some((device) => device.deviceId === deviceIdA), 'current device still listed');
      return 'revoked; legacy token 401';
    });

    await check('DELETE of the current device answers 400', async () => {
      const result = await api(
        server.baseUrl,
        'DELETE',
        `/mobile/v1/devices/${deviceIdA}`,
        undefined,
        tokenA,
      );
      assertEqual(result.status, 400, 'current DELETE status');
      assertEqual(result.json?.error?.code, 'invalid_request', 'current DELETE code');
      assertMatch(result.json?.error?.message, /current/i, 'current DELETE message');
      return 'current -> 400';
    });

    await check('DELETE of an unknown device answers 404', async () => {
      const result = await api(
        server.baseUrl,
        'DELETE',
        '/mobile/v1/devices/dev_does_not_exist',
        undefined,
        tokenA,
      );
      assertEqual(result.status, 404, 'unknown DELETE status');
      assertEqual(result.json?.error?.code, 'not_found', 'unknown DELETE code');
      return 'unknown -> 404';
    });

    await check('device routes without a bearer token answer 401', async () => {
      const list = await api(server.baseUrl, 'GET', '/mobile/v1/devices');
      assertEqual(list.status, 401, 'unauthorized GET status');
      assertEqual(list.json?.error?.code, 'unauthorized', 'unauthorized GET code');

      const remove = await api(server.baseUrl, 'DELETE', `/mobile/v1/devices/${LEGACY_DEVICE_ID}`);
      assertEqual(remove.status, 401, 'unauthorized DELETE status');
      return 'GET/DELETE -> 401';
    });
  } finally {
    await server.stop();
  }

  console.log('');
  console.log(`[devices-test] ${passCount} passed, ${failCount} failed`);
  if (failCount > 0) {
    console.log('[devices-test] failures:');
    for (const failure of failures) console.log(`  - ${failure}`);
    process.exitCode = 1;
  } else {
    console.log('[devices-test] OK');
  }
}

main().catch((error) => {
  console.error('[devices-test] fatal:', error);
  process.exitCode = 1;
});
