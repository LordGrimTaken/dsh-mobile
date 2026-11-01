#!/usr/bin/env node
/**
 * tools/fake-host/push-test.mjs
 *
 * Self-contained test for the fake host's in-memory ntfy push configuration:
 *   - spawns a fake host, pairs via the real endpoints,
 *   - GET /push -> {supported:true, enabled:false, server:"https://ntfy.sh",
 *     topic:"", hasToken:false} by default (token always redacted),
 *   - PUT /push validates enabled/server/topic/token (400 for invalid values),
 *   - a valid PUT persists and GET reflects it with hasToken:true,
 *   - token can be cleared again (hasToken:false),
 *   - POST /push/test -> {ok:true} (synthetic, no network),
 *   - all push routes require a bearer token (401).
 *
 *   node tools/fake-host/push-test.mjs
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
const PAIR_TOKEN = 'dev-token';
const PUSH_SHAPE = 'enabled,hasToken,server,supported,topic';

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

/** Pair via the real endpoints and return the device token. */
async function pair(baseUrl) {
  const started = await api(baseUrl, 'POST', '/mobile/v1/pair/new');
  assertEqual(started.status, 200, 'pair/new status');
  const code = started.json?.code;
  assertMatch(code, /^\d{6}$/, 'pair code');
  const completed = await api(baseUrl, 'POST', '/mobile/v1/pair/complete', {
    code,
    deviceName: 'QA Pixel',
    platform: 'android',
  });
  assertEqual(completed.status, 200, 'pair/complete status');
  assert(completed.json?.token, 'pair token');
  return completed.json.token;
}

function assertPushView(json, expected, label) {
  assert(json, `${label}: body`);
  assertEqual(Object.keys(json).sort().join(','), PUSH_SHAPE, `${label} shape`);
  assertEqual(json.supported, true, `${label} supported`);
  assertEqual(json.enabled, expected.enabled, `${label} enabled`);
  assertEqual(json.server, expected.server, `${label} server`);
  assertEqual(json.topic, expected.topic, `${label} topic`);
  assertEqual(json.hasToken, expected.hasToken, `${label} hasToken`);
  assertEqual(json.token, undefined, `${label} token redacted`);
}

async function getPush(baseUrl, token) {
  const result = await api(baseUrl, 'GET', '/mobile/v1/push', undefined, token);
  assertEqual(result.status, 200, 'GET /push status');
  return result.json;
}

async function main() {
  console.log('[push-test] starting fake host on a random loopback port...');
  const server = await startServer();
  console.log(`[push-test] fake host ready at ${server.baseUrl}`);

  let token = '';
  try {
    await check('pair/new + pair/complete return a device token', async () => {
      token = await pair(server.baseUrl);
      assertEqual(token, PAIR_TOKEN, 'token');
      return 'paired';
    });

    await check('GET /push starts disabled with ntfy.sh defaults and the token redacted', async () => {
      const view = await getPush(server.baseUrl, token);
      assertPushView(
        view,
        { enabled: false, server: 'https://ntfy.sh', topic: '', hasToken: false },
        'defaults',
      );
      return 'disabled; https://ntfy.sh; empty topic; no token';
    });

    await check('PUT /push rejects invalid enabled/server/topic/token values with 400', async () => {
      const cases = [
        { body: {}, label: 'missing enabled' },
        { body: { enabled: 'yes' }, label: 'non-boolean enabled' },
        { body: { enabled: true }, label: 'enabled without topic' },
        { body: { enabled: true, topic: 'bad topic!' }, label: 'bad topic characters' },
        { body: { enabled: true, topic: 'x'.repeat(65) }, label: 'topic too long' },
        { body: { enabled: true, topic: 'ok', server: 'not a url' }, label: 'invalid server URL' },
        { body: { enabled: true, topic: 'ok', server: 'ftp://ntfy.example.com' }, label: 'non-http server' },
        { body: { enabled: false, topic: 'bad topic!' }, label: 'bad topic while disabled' },
        { body: { enabled: false, token: 42 }, label: 'non-string token' },
      ];
      for (const testCase of cases) {
        const result = await api(server.baseUrl, 'PUT', '/mobile/v1/push', testCase.body, token);
        assertEqual(result.status, 400, `${testCase.label} status`);
        assertEqual(result.json?.error?.code, 'invalid_request', `${testCase.label} code`);
      }
      return `${cases.length} invalid bodies -> 400`;
    });

    await check('a valid PUT persists and GET returns hasToken:true without the token', async () => {
      const put = await api(
        server.baseUrl,
        'PUT',
        '/mobile/v1/push',
        {
          enabled: true,
          server: 'https://ntfy.example.com',
          topic: 'dsh-alerts',
          token: 'supersecret-token',
        },
        token,
      );
      assertEqual(put.status, 200, 'PUT status');
      assertPushView(
        put.json,
        {
          enabled: true,
          server: 'https://ntfy.example.com',
          topic: 'dsh-alerts',
          hasToken: true,
        },
        'PUT response',
      );
      assert(!JSON.stringify(put.json).includes('supersecret-token'), 'token not echoed in PUT response');

      const view = await getPush(server.baseUrl, token);
      assertPushView(
        view,
        {
          enabled: true,
          server: 'https://ntfy.example.com',
          topic: 'dsh-alerts',
          hasToken: true,
        },
        'GET after PUT',
      );
      assert(!JSON.stringify(view).includes('supersecret-token'), 'token not echoed in GET response');
      return 'enabled dsh-alerts on ntfy.example.com; hasToken true';
    });

    await check('disabling push and clearing the token persists (hasToken:false)', async () => {
      const put = await api(
        server.baseUrl,
        'PUT',
        '/mobile/v1/push',
        { enabled: false, token: '' },
        token,
      );
      assertEqual(put.status, 200, 'PUT status');
      assertPushView(
        put.json,
        {
          enabled: false,
          server: 'https://ntfy.example.com',
          topic: 'dsh-alerts',
          hasToken: false,
        },
        'disabled PUT',
      );

      const view = await getPush(server.baseUrl, token);
      assertEqual(view.enabled, false, 'persisted disabled');
      assertEqual(view.hasToken, false, 'persisted cleared token');
      return 'disabled; token cleared';
    });

    await check('POST /push/test answers {ok:true} (synthetic, no network)', async () => {
      const result = await api(server.baseUrl, 'POST', '/mobile/v1/push/test', undefined, token);
      assertEqual(result.status, 200, 'test status');
      assertEqual(result.json?.ok, true, 'test ok');
      assertEqual(Object.keys(result.json).sort().join(','), 'ok', 'test shape');
      return 'ok';
    });

    await check('push routes without a bearer token answer 401 unauthorized', async () => {
      const get = await api(server.baseUrl, 'GET', '/mobile/v1/push');
      assertEqual(get.status, 401, 'GET unauthorized status');
      assertEqual(get.json?.error?.code, 'unauthorized', 'GET unauthorized code');

      const put = await api(server.baseUrl, 'PUT', '/mobile/v1/push', { enabled: false });
      assertEqual(put.status, 401, 'PUT unauthorized status');

      const test = await api(server.baseUrl, 'POST', '/mobile/v1/push/test');
      assertEqual(test.status, 401, 'test unauthorized status');
      return 'GET/PUT/test -> 401';
    });
  } finally {
    await server.stop();
  }

  console.log('');
  console.log(`[push-test] ${passCount} passed, ${failCount} failed`);
  if (failCount > 0) {
    console.log('[push-test] failures:');
    for (const failure of failures) console.log(`  - ${failure}`);
    process.exitCode = 1;
  } else {
    console.log('[push-test] OK');
  }
}

main().catch((error) => {
  console.error('[push-test] fatal:', error);
  process.exitCode = 1;
});
