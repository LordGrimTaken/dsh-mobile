#!/usr/bin/env node
/**
 * tools/fake-host/recovery-test.mjs
 *
 * Self-contained test for the fake host's recovery pairing:
 *   - spawns a fake host (seeded configured recovery code
 *     `dsh-recovery-seed-0001`), pairs normally,
 *   - GET /recovery -> {supported,configured,createdAt?} with the code redacted,
 *   - POST /pair/recovery (NO auth) redeems the seeded code case/space-normalized
 *     -> {token, device, recoveryCode} and rotates it; reusing the old code -> 401,
 *   - POST /recovery (auth) generates/rotates -> {code,createdAt}; the new code
 *     redeems and rotates again, old one 401,
 *   - invalid/missing codes 400/401,
 *   - a host started with FAKE_RECOVERY_CODE='' is unconfigured: GET reports
 *     configured:false and /pair/recovery answers 400,
 *   - GET/POST /recovery require auth (401), while /pair/recovery is public.
 *
 *   node tools/fake-host/recovery-test.mjs
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
const SEEDED_CODE = 'dsh-recovery-seed-0001';
const CODE_PATTERN = /^dsh-[0-9a-f]{12}$/;
const DEVICE_SHAPE = 'deviceId,deviceName,pairedAt,platform';

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

/** Pair one device via the real endpoints; returns the device token. */
async function pair(baseUrl, deviceName = 'QA Pixel') {
  const started = await api(baseUrl, 'POST', '/mobile/v1/pair/new');
  assertEqual(started.status, 200, 'pair/new status');
  const code = started.json?.code;
  assertMatch(code, /^\d{6}$/, 'pair code');
  const completed = await api(baseUrl, 'POST', '/mobile/v1/pair/complete', {
    code,
    deviceName,
    platform: 'android',
  });
  assertEqual(completed.status, 200, 'pair/complete status');
  assert(completed.json?.token, 'pair token');
  return completed.json.token;
}

function assertIso(value, label) {
  assert(typeof value === 'string' && !Number.isNaN(Date.parse(value)), `${label} must be an ISO timestamp`);
}

function assertRecoveredDevice(device, expectedName) {
  assert(device, 'device object');
  assertEqual(Object.keys(device).sort().join(','), DEVICE_SHAPE, 'device shape');
  assertMatch(device.deviceId, /^dev_[0-9a-f]+$/, 'deviceId');
  assertEqual(device.deviceName, expectedName, 'deviceName');
  assertEqual(device.platform, 'android', 'platform');
  assertIso(device.pairedAt, 'pairedAt');
}

async function main() {
  console.log('[recovery-test] starting fake host on a random loopback port...');
  const server = await startServer();
  console.log(`[recovery-test] fake host ready at ${server.baseUrl}`);

  let token = '';
  let latestRecoveryCode = '';
  try {
    await check('pair/new + pair/complete return a device token', async () => {
      token = await pair(server.baseUrl);
      assertEqual(token, PAIR_TOKEN, 'token');
      return 'paired';
    });

    await check('GET /recovery reports the seeded configured state with the code redacted', async () => {
      const result = await api(server.baseUrl, 'GET', '/mobile/v1/recovery', undefined, token);
      assertEqual(result.status, 200, 'recovery status');
      assertEqual(result.json?.supported, true, 'supported');
      assertEqual(result.json?.configured, true, 'configured');
      assertIso(result.json?.createdAt, 'createdAt');
      assertEqual(result.json?.code, undefined, 'code redacted');
      assert(!result.text.includes(SEEDED_CODE), 'seeded code not returned');
      return 'configured';
    });

    await check('POST /pair/recovery redeems the seeded code (normalized) and rotates it', async () => {
      const redeemed = await api(server.baseUrl, 'POST', '/mobile/v1/pair/recovery', {
        code: `  ${SEEDED_CODE.toUpperCase()}  `,
        deviceName: 'Recovered Pixel',
        platform: 'android',
      });
      assertEqual(redeemed.status, 200, 'recovery redeem status');
      assertEqual(redeemed.json?.token, PAIR_TOKEN, 'recovered token');
      assertRecoveredDevice(redeemed.json?.device, 'Recovered Pixel');
      assertMatch(redeemed.json?.recoveryCode, CODE_PATTERN, 'next recovery code shape');
      assert(redeemed.json.recoveryCode !== SEEDED_CODE, 'code rotated');
      latestRecoveryCode = redeemed.json.recoveryCode;

      const reused = await api(server.baseUrl, 'POST', '/mobile/v1/pair/recovery', {
        code: SEEDED_CODE,
      });
      assertEqual(reused.status, 401, 'reused seeded code status');
      assertEqual(reused.json?.error?.code, 'unauthorized', 'reused seeded code code');
      return `rotated to ${redeemed.json.recoveryCode}`;
    });

    await check('POST /recovery generates a new code, redacted by GET', async () => {
      const generated = await api(server.baseUrl, 'POST', '/mobile/v1/recovery', undefined, token);
      assertEqual(generated.status, 200, 'generate status');
      assertMatch(generated.json?.code, CODE_PATTERN, 'generated code shape');
      assertIso(generated.json?.createdAt, 'generated createdAt');
      assert(generated.json.code !== latestRecoveryCode, 'generated code differs from recovery code');
      latestRecoveryCode = generated.json.code;

      const view = await api(server.baseUrl, 'GET', '/mobile/v1/recovery', undefined, token);
      assertEqual(view.status, 200, 'GET after generate status');
      assertEqual(view.json?.configured, true, 'configured after generate');
      assert(!view.text.includes(latestRecoveryCode), 'generated code not returned by GET');
      return 'generated';
    });

    await check('the generated code redeems once and the old code is then rejected', async () => {
      const usedCode = latestRecoveryCode;
      const redeemed = await api(server.baseUrl, 'POST', '/mobile/v1/pair/recovery', {
        code: usedCode,
        deviceName: 'Second Recovered',
      });
      assertEqual(redeemed.status, 200, 'generated redeem status');
      assertEqual(redeemed.json?.token, PAIR_TOKEN, 'generated redeem token');
      assertRecoveredDevice(redeemed.json?.device, 'Second Recovered');
      assertMatch(redeemed.json?.recoveryCode, CODE_PATTERN, 'next code shape');
      latestRecoveryCode = redeemed.json.recoveryCode;

      const reused = await api(server.baseUrl, 'POST', '/mobile/v1/pair/recovery', {
        code: usedCode,
      });
      assertEqual(reused.status, 401, 'reused generated code status');
      return 'single-use enforced';
    });

    await check('invalid recovery codes 401 and missing code 400', async () => {
      const wrong = await api(server.baseUrl, 'POST', '/mobile/v1/pair/recovery', {
        code: 'dsh-000000000000',
      });
      assertEqual(wrong.status, 401, 'wrong code status');
      assertEqual(wrong.json?.error?.code, 'unauthorized', 'wrong code code');

      for (const body of [{}, { code: '' }, { code: '   ' }, { code: 42 }]) {
        const result = await api(server.baseUrl, 'POST', '/mobile/v1/pair/recovery', body);
        assertEqual(result.status, 400, `missing code ${JSON.stringify(body)} status`);
        assertEqual(result.json?.error?.code, 'invalid_request', `missing code ${JSON.stringify(body)} code`);
      }
      return 'invalid -> 401; missing -> 400';
    });

    await check('an unconfigured host (FAKE_RECOVERY_CODE="") reports configured:false and rejects recovery', async () => {
      const unconfigured = await startServer({ FAKE_RECOVERY_CODE: '' });
      try {
        const unconfiguredToken = await pair(unconfigured.baseUrl, 'Unconfigured Pixel');
        const view = await api(unconfigured.baseUrl, 'GET', '/mobile/v1/recovery', undefined, unconfiguredToken);
        assertEqual(view.status, 200, 'unconfigured GET status');
        assertEqual(view.json?.supported, true, 'unconfigured supported');
        assertEqual(view.json?.configured, false, 'unconfigured flag');
        assertEqual(view.json?.createdAt, undefined, 'no createdAt when unconfigured');

        const attempt = await api(unconfigured.baseUrl, 'POST', '/mobile/v1/pair/recovery', {
          code: 'whatever-code',
        });
        assertEqual(attempt.status, 400, 'unconfigured recovery status');
        assertEqual(attempt.json?.error?.code, 'invalid_request', 'unconfigured recovery code');
      } finally {
        await unconfigured.stop();
      }
      return 'unconfigured -> 400';
    });

    await check('GET/POST /recovery require auth while /pair/recovery stays public', async () => {
      const get = await api(server.baseUrl, 'GET', '/mobile/v1/recovery');
      assertEqual(get.status, 401, 'unauthorized GET status');
      assertEqual(get.json?.error?.code, 'unauthorized', 'unauthorized GET code');

      const post = await api(server.baseUrl, 'POST', '/mobile/v1/recovery');
      assertEqual(post.status, 401, 'unauthorized POST status');
      assertEqual(post.json?.error?.code, 'unauthorized', 'unauthorized POST code');

      const unauthRecovery = await api(server.baseUrl, 'POST', '/mobile/v1/pair/recovery', {
        code: latestRecoveryCode,
        deviceName: 'No-Token Recovered',
      });
      assertEqual(unauthRecovery.status, 200, 'public recovery status');
      return 'GET/POST auth 401; recovery public 200';
    });
  } finally {
    await server.stop();
  }

  console.log('');
  console.log(`[recovery-test] ${passCount} passed, ${failCount} failed`);
  if (failCount > 0) {
    console.log('[recovery-test] failures:');
    for (const failure of failures) console.log(`  - ${failure}`);
    process.exitCode = 1;
  } else {
    console.log('[recovery-test] OK');
  }
}

main().catch((error) => {
  console.error('[recovery-test] fatal:', error);
  process.exitCode = 1;
});
