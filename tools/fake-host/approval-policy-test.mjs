#!/usr/bin/env node
/**
 * tools/fake-host/approval-policy-test.mjs
 *
 * Self-contained test for the fake host's per-session approval policy:
 *   - spawns a fake host, pairs via the real endpoints,
 *   - seed check: s_release starts on "never", s_economy on "ask", other
 *     sessions default to "ask"; GET returns {supported:true, live, policy},
 *   - PUT {policy} validates ask|never, persists in memory and GET reflects it,
 *   - invalid bodies 400, unknown sessions 404,
 *   - archived ("cold") sessions report live:false and reject PUT with 400,
 *   - GET/PUT without a bearer token -> 401.
 *
 *   node tools/fake-host/approval-policy-test.mjs
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
const POLICY_SHAPE = 'live,policy,supported';

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

async function getPolicy(baseUrl, token, sessionId) {
  const result = await api(
    baseUrl,
    'GET',
    `/mobile/v1/sessions/${sessionId}/approval-policy`,
    undefined,
    token,
  );
  assertEqual(result.status, 200, 'GET policy status');
  assertEqual(Object.keys(result.json).sort().join(','), POLICY_SHAPE, 'policy shape');
  assertEqual(result.json.supported, true, 'policy supported');
  assert(typeof result.json.live === 'boolean', 'policy live is boolean');
  assert(result.json.policy === 'ask' || result.json.policy === 'never', 'policy value');
  return result.json;
}

async function main() {
  console.log('[approval-policy-test] starting fake host on a random loopback port...');
  const server = await startServer();
  console.log(`[approval-policy-test] fake host ready at ${server.baseUrl}`);

  let token = '';
  try {
    await check('pair/new + pair/complete return a device token', async () => {
      token = await pair(server.baseUrl);
      assertEqual(token, PAIR_TOKEN, 'token');
      return 'paired';
    });

    await check('GET exposes the seeded never/ask policies and the default ask', async () => {
      const release = await getPolicy(server.baseUrl, token, 's_release');
      assertEqual(release.live, true, 'release live');
      assertEqual(release.policy, 'never', 'seeded s_release policy');

      const economy = await getPolicy(server.baseUrl, token, 's_economy');
      assertEqual(economy.live, true, 'economy live');
      assertEqual(economy.policy, 'ask', 'seeded s_economy policy');

      const android = await getPolicy(server.baseUrl, token, 's_android');
      assertEqual(android.policy, 'ask', 'default ask for other sessions');
      return 's_release=never; s_economy=ask; s_android=ask';
    });

    await check('GET for an unknown session answers 404 not_found', async () => {
      const result = await api(
        server.baseUrl,
        'GET',
        '/mobile/v1/sessions/s_does_not_exist/approval-policy',
        undefined,
        token,
      );
      assertEqual(result.status, 404, 'unknown GET status');
      assertEqual(result.json?.error?.code, 'not_found', 'unknown GET code');
      return 'unknown -> 404';
    });

    await check('PUT updates the policy and GET reflects it', async () => {
      const put = await api(
        server.baseUrl,
        'PUT',
        '/mobile/v1/sessions/s_release/approval-policy',
        { policy: 'ask' },
        token,
      );
      assertEqual(put.status, 200, 'PUT status');
      assertEqual(Object.keys(put.json).sort().join(','), POLICY_SHAPE, 'PUT shape');
      assertEqual(put.json.policy, 'ask', 'PUT policy');
      assertEqual(put.json.live, true, 'PUT live');

      const view = await getPolicy(server.baseUrl, token, 's_release');
      assertEqual(view.policy, 'ask', 'GET reflects update');

      const putNever = await api(
        server.baseUrl,
        'PUT',
        '/mobile/v1/sessions/s_economy/approval-policy',
        { policy: 'never' },
        token,
      );
      assertEqual(putNever.status, 200, 'second PUT status');
      assertEqual(putNever.json.policy, 'never', 'second PUT policy');
      return 's_release never -> ask; s_economy ask -> never';
    });

    await check('PUT rejects invalid policies with 400 and leaves the policy unchanged', async () => {
      const cases = [
        { body: {}, label: 'missing policy' },
        { body: { policy: 'sometimes' }, label: 'unknown policy' },
        { body: { policy: 42 }, label: 'non-string policy' },
        { body: { policy: '' }, label: 'empty policy' },
        { body: { policy: 'ALWAYS' }, label: 'wrong casing' },
      ];
      for (const testCase of cases) {
        const result = await api(
          server.baseUrl,
          'PUT',
          '/mobile/v1/sessions/s_release/approval-policy',
          testCase.body,
          token,
        );
        assertEqual(result.status, 400, `${testCase.label} status`);
        assertEqual(result.json?.error?.code, 'invalid_request', `${testCase.label} code`);
      }
      const view = await getPolicy(server.baseUrl, token, 's_release');
      assertEqual(view.policy, 'ask', 'policy unchanged after invalid puts');
      return `${cases.length} invalid bodies -> 400`;
    });

    await check('PUT for an unknown session answers 404 not_found', async () => {
      const result = await api(
        server.baseUrl,
        'PUT',
        '/mobile/v1/sessions/s_does_not_exist/approval-policy',
        { policy: 'never' },
        token,
      );
      assertEqual(result.status, 404, 'unknown PUT status');
      assertEqual(result.json?.error?.code, 'not_found', 'unknown PUT code');
      return 'unknown -> 404';
    });

    await check('archived (cold) sessions report live:false and reject PUT with 400', async () => {
      const archived = await api(server.baseUrl, 'POST', '/mobile/v1/sessions/s_search/archive', undefined, token);
      assertEqual(archived.status, 200, 'archive status');

      const view = await getPolicy(server.baseUrl, token, 's_search');
      assertEqual(view.live, false, 'archived session not live');
      assertEqual(view.policy, 'ask', 'archived policy still readable');

      const put = await api(
        server.baseUrl,
        'PUT',
        '/mobile/v1/sessions/s_search/approval-policy',
        { policy: 'never' },
        token,
      );
      assertEqual(put.status, 400, 'cold PUT status');
      assertEqual(put.json?.error?.code, 'invalid_request', 'cold PUT code');
      return 'archived -> live:false; PUT -> 400';
    });

    await check('approval-policy routes without a bearer token answer 401', async () => {
      const get = await api(server.baseUrl, 'GET', '/mobile/v1/sessions/s_release/approval-policy');
      assertEqual(get.status, 401, 'unauthorized GET status');
      assertEqual(get.json?.error?.code, 'unauthorized', 'unauthorized GET code');

      const put = await api(
        server.baseUrl,
        'PUT',
        '/mobile/v1/sessions/s_release/approval-policy',
        { policy: 'ask' },
      );
      assertEqual(put.status, 401, 'unauthorized PUT status');
      return 'GET/PUT -> 401';
    });
  } finally {
    await server.stop();
  }

  console.log('');
  console.log(`[approval-policy-test] ${passCount} passed, ${failCount} failed`);
  if (failCount > 0) {
    console.log('[approval-policy-test] failures:');
    for (const failure of failures) console.log(`  - ${failure}`);
    process.exitCode = 1;
  } else {
    console.log('[approval-policy-test] OK');
  }
}

main().catch((error) => {
  console.error('[approval-policy-test] fatal:', error);
  process.exitCode = 1;
});
