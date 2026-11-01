#!/usr/bin/env node
/**
 * tools/fake-host/tailscale-test.mjs
 *
 * Self-contained test for the fake host's Tailscale auto-pairing:
 *   - pairs once via the existing pair endpoints,
 *   - GET /pair/methods -> {desktopCode, recovery, tailscaleAutoPair,
 *     tailscaleLoginRequired} (no auth),
 *   - POST /pair/tailscale (no auth) with a Tailscale-User-Login header returns a
 *     pair-complete-like payload + {pairingMethod:"tailscale", identity:{login}}
 *     and its token works on GET /sessions,
 *   - missing header -> 400 code tailscale_identity_missing,
 *   - allowlist host: mismatched login -> 403, matching login (case-insensitive)
 *     -> 200,
 *   - disabled host (FAKE_TAILSCALE_AUTOPAIR=0): methods flag false + POST 403,
 *   - both pairing endpoints stay auth-exempt.
 *
 *   node tools/fake-host/tailscale-test.mjs
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
const LOGIN = 'qa@example.com';

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

async function api(baseUrl, method, requestPath, body, token, extraHeaders = {}) {
  const headers = { ...extraHeaders };
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

async function getMethods(baseUrl) {
  const result = await api(baseUrl, 'GET', '/mobile/v1/pair/methods');
  assertEqual(result.status, 200, 'methods status');
  return result.json;
}

async function main() {
  console.log('[tailscale-test] starting fake host on a random loopback port...');
  const server = await startServer();
  console.log(`[tailscale-test] fake host ready at ${server.baseUrl}`);

  let token = '';
  try {
    await check('pair/new + pair/complete return a device token', async () => {
      token = await pair(server.baseUrl);
      assertEqual(token, PAIR_TOKEN, 'token');
      return 'paired';
    });

    await check('GET /pair/methods returns the four boolean capabilities without auth', async () => {
      const methods = await getMethods(server.baseUrl);
      assertEqual(
        Object.keys(methods).sort().join(','),
        'desktopCode,recovery,tailscaleAutoPair,tailscaleLoginRequired',
        'methods shape',
      );
      assertEqual(methods.desktopCode, true, 'desktopCode');
      assertEqual(methods.recovery, true, 'recovery configured seed');
      assertEqual(methods.tailscaleAutoPair, true, 'tailscaleAutoPair default');
      assertEqual(methods.tailscaleLoginRequired, false, 'tailscaleLoginRequired default');
      return '4 methods';
    });

    await check('POST /pair/tailscale with the identity header pairs and its token works', async () => {
      const result = await api(
        server.baseUrl,
        'POST',
        '/mobile/v1/pair/tailscale',
        { deviceName: 'Tailnet Pixel', platform: 'android' },
        undefined,
        { 'Tailscale-User-Login': LOGIN },
      );
      assertEqual(result.status, 200, 'tailscale pair status');
      assertEqual(result.json?.token, PAIR_TOKEN, 'token');
      assertMatch(result.json?.deviceId, /^dev_[0-9a-f]+$/, 'deviceId');
      assertEqual(result.json?.pairingMethod, 'tailscale', 'pairingMethod');
      assertEqual(result.json?.identity?.login, LOGIN, 'identity login');
      assertEqual(result.json?.host?.name, 'fake-host', 'host name');
      assertEqual(result.json?.workspaces?.length, 3, 'workspaces');
      assertEqual(result.json?.deviceName, undefined, 'no extra device object required');

      const sessions = await api(
        server.baseUrl,
        'GET',
        '/mobile/v1/sessions',
        undefined,
        result.json.token,
      );
      assertEqual(sessions.status, 200, 'paired token works on /sessions');
      assert(Array.isArray(sessions.json?.sessions), 'sessions array');
      return `identity ${LOGIN}`;
    });

    await check('POST /pair/tailscale without the header answers 400 tailscale_identity_missing', async () => {
      const result = await api(server.baseUrl, 'POST', '/mobile/v1/pair/tailscale', {});
      assertEqual(result.status, 400, 'missing header status');
      assertEqual(result.json?.error?.code, 'tailscale_identity_missing', 'missing header code');
      return result.json.error.code;
    });

    await check('allowlist host rejects other logins 403 and accepts a matching login (case-insensitive)', async () => {
      const allowHost = await startServer({ FAKE_TAILSCALE_LOGIN: 'allowed@example.com' });
      try {
        const methods = await getMethods(allowHost.baseUrl);
        assertEqual(methods.tailscaleAutoPair, true, 'allowlist auto-pair enabled');
        assertEqual(methods.tailscaleLoginRequired, true, 'allowlist required flag');

        const mismatch = await api(
          allowHost.baseUrl,
          'POST',
          '/mobile/v1/pair/tailscale',
          {},
          undefined,
          { 'Tailscale-User-Login': 'other@example.com' },
        );
        assertEqual(mismatch.status, 403, 'mismatch status');
        assertEqual(mismatch.json?.error?.code, 'forbidden', 'mismatch code');

        const match = await api(
          allowHost.baseUrl,
          'POST',
          '/mobile/v1/pair/tailscale',
          { deviceName: 'Allowed Phone' },
          undefined,
          { 'Tailscale-User-Login': 'Allowed@Example.COM' },
        );
        assertEqual(match.status, 200, 'allowlist match status');
        assertEqual(match.json?.identity?.login, 'Allowed@Example.COM', 'identity echoes header');
      } finally {
        await allowHost.stop();
      }
      return 'mismatch 403; match 200';
    });

    await check('disabled host reports tailscaleAutoPair:false and POST answers 403', async () => {
      const disabledHost = await startServer({ FAKE_TAILSCALE_AUTOPAIR: '0' });
      try {
        const methods = await getMethods(disabledHost.baseUrl);
        assertEqual(methods.tailscaleAutoPair, false, 'disabled autoPair flag');
        assertEqual(methods.desktopCode, true, 'desktopCode still true');

        const result = await api(
          disabledHost.baseUrl,
          'POST',
          '/mobile/v1/pair/tailscale',
          {},
          undefined,
          { 'Tailscale-User-Login': LOGIN },
        );
        assertEqual(result.status, 403, 'disabled status');
        assertEqual(result.json?.error?.code, 'forbidden', 'disabled code');
      } finally {
        await disabledHost.stop();
      }
      return 'disabled -> 403';
    });

    await check('both Tailscale pairing endpoints are auth-exempt', async () => {
      const methods = await getMethods(server.baseUrl);
      assertEqual(methods.desktopCode, true, 'methods without token');
      const result = await api(
        server.baseUrl,
        'POST',
        '/mobile/v1/pair/tailscale',
        {},
        undefined,
        { 'Tailscale-User-Login': 'second@example.com' },
      );
      assertEqual(result.status, 200, 'tailscale pair without token');
      return 'no token -> 200';
    });
  } finally {
    await server.stop();
  }

  console.log('');
  console.log(`[tailscale-test] ${passCount} passed, ${failCount} failed`);
  if (failCount > 0) {
    console.log('[tailscale-test] failures:');
    for (const failure of failures) console.log(`  - ${failure}`);
    process.exitCode = 1;
  } else {
    console.log('[tailscale-test] OK');
  }
}

main().catch((error) => {
  console.error('[tailscale-test] fatal:', error);
  process.exitCode = 1;
});
