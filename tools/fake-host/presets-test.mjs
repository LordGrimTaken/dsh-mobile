#!/usr/bin/env node
/**
 * tools/fake-host/presets-test.mjs
 *
 * Self-contained test for the fake host's agent-presets endpoint:
 *   - spawns a fake host, pairs via the real endpoints,
 *   - GET /presets -> {presets, supported:true, defaultId} with the four
 *     seeded presets ({id,name?,description?,isDefault,broken?}) sorted by name,
 *   - exactly one isDefault preset and defaultId match it; the broken fixture is
 *     flagged with broken:true while healthy presets omit the flag,
 *   - GET /host still works and POST /sessions accepts a preset id,
 *   - no bearer token -> 401.
 *
 *   node tools/fake-host/presets-test.mjs
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

const PRESET_KEYS = 'description,id,isDefault,name';

async function getPresets(baseUrl, token) {
  const result = await api(baseUrl, 'GET', '/mobile/v1/presets', undefined, token);
  assertEqual(result.status, 200, 'presets status');
  assertEqual(result.json?.supported, true, 'presets supported');
  assert(Array.isArray(result.json?.presets), 'presets array');
  return result.json;
}

async function main() {
  console.log('[presets-test] starting fake host on a random loopback port...');
  const server = await startServer();
  console.log(`[presets-test] fake host ready at ${server.baseUrl}`);

  let token = '';
  try {
    await check('pair/new + pair/complete return a device token', async () => {
      token = await pair(server.baseUrl);
      assertEqual(token, PAIR_TOKEN, 'token');
      return 'paired';
    });

    await check('GET /presets returns the four seeded presets sorted by name in the frozen shape', async () => {
      const result = await getPresets(server.baseUrl, token);
      assertEqual(result.presets.length, 4, 'four presets');

      const expectedOrder = ['Broken preset', 'Minimal', 'PTC', 'Standard'];
      assertEqual(
        result.presets.map((preset) => preset.name).join('|'),
        expectedOrder.join('|'),
        'presets sorted by name',
      );
      for (const preset of result.presets) {
        assertMatch(preset.id, /^[a-z0-9-]+$/i, 'preset id');
        assert(typeof preset.name === 'string' && preset.name.length > 0, `${preset.id} name`);
        assert(typeof preset.isDefault === 'boolean', `${preset.id} isDefault`);
        assert(typeof preset.description === 'string' && preset.description.length > 0, `${preset.id} description`);
        const keys = Object.keys(preset).sort().join(',');
        assertEqual(
          keys,
          preset.id === 'broken' ? 'broken,description,id,isDefault,name' : PRESET_KEYS,
          `${preset.id} shape`,
        );
      }
      return expectedOrder.join(' < ');
    });

    await check('exactly one default preset + defaultId, and the broken fixture is flagged', async () => {
      const result = await getPresets(server.baseUrl, token);
      const defaults = result.presets.filter((preset) => preset.isDefault === true);
      assertEqual(defaults.length, 1, 'one default preset');
      assertEqual(defaults[0].id, 'standard', 'default preset id');
      assertEqual(result.defaultId, 'standard', 'defaultId');

      const broken = result.presets.find((preset) => preset.broken === true);
      assert(broken, 'broken preset present');
      assertEqual(broken.id, 'broken', 'broken preset id');
      assertEqual(broken.isDefault, false, 'broken preset is not default');
      for (const preset of result.presets) {
        if (preset.id === 'broken') continue;
        assertEqual(preset.broken, undefined, `${preset.id} has no broken flag`);
      }
      return `${defaults[0].id} default; broken flagged`;
    });

    await check('GET /host works and POST /sessions accepts a preset id', async () => {
      const host = await api(server.baseUrl, 'GET', '/mobile/v1/host', undefined, token);
      assertEqual(host.status, 200, 'host status');
      assertEqual(host.json?.name, 'fake-host', 'host name');

      const created = await api(
        server.baseUrl,
        'POST',
        '/mobile/v1/sessions',
        { workspaceId: 'ws_1', preset: 'minimal' },
        token,
      );
      assertEqual(created.status, 200, 'create session status');
      const session = created.json?.session;
      assert(session?.id, 'created session id');
      assertEqual(session.workspaceId, 'ws_1', 'created session workspaceId');
      return `session ${session.id} created with preset minimal`;
    });

    await check('GET /presets without a bearer token answers 401', async () => {
      const result = await api(server.baseUrl, 'GET', '/mobile/v1/presets');
      assertEqual(result.status, 401, 'unauthorized status');
      assertEqual(result.json?.error?.code, 'unauthorized', 'unauthorized code');
      return 'no token -> 401';
    });
  } finally {
    await server.stop();
  }

  console.log('');
  console.log(`[presets-test] ${passCount} passed, ${failCount} failed`);
  if (failCount > 0) {
    console.log('[presets-test] failures:');
    for (const failure of failures) console.log(`  - ${failure}`);
    process.exitCode = 1;
  } else {
    console.log('[presets-test] OK');
  }
}

main().catch((error) => {
  console.error('[presets-test] fatal:', error);
  process.exitCode = 1;
});
