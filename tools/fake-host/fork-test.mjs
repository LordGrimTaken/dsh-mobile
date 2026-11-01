#!/usr/bin/env node
/**
 * tools/fake-host/fork-test.mjs
 *
 * Self-contained test for the fake host's session fork endpoint and message seq:
 *   - spawns a fake host, pairs via the real endpoints,
 *   - seeded session messages carry 1-based `seq` values,
 *   - POST /sessions/{id}/fork without atSeq forks the latest transcript ->
 *     {sessionId, forkedFrom}; the new `session-fork-…` session appears in
 *     GET /sessions with a fork title,
 *   - POST with a valid atSeq echoes it and copies only messages up to that seq,
 *   - invalid/unknown atSeq 400, archived session 400, unknown session 404,
 *   - no bearer token -> 401.
 *
 *   node tools/fake-host/fork-test.mjs
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
const SOURCE_SESSION = 's_release';

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

async function getSessionMessages(baseUrl, token, sessionId) {
  const result = await api(
    baseUrl,
    'GET',
    `/mobile/v1/sessions/${sessionId}`,
    undefined,
    token,
  );
  assertEqual(result.status, 200, `session ${sessionId} detail status`);
  assert(Array.isArray(result.json?.messages), `session ${sessionId} messages array`);
  return result.json.messages;
}

async function main() {
  console.log('[fork-test] starting fake host on a random loopback port...');
  const server = await startServer();
  console.log(`[fork-test] fake host ready at ${server.baseUrl}`);

  let token = '';
  try {
    await check('pair/new + pair/complete return a device token', async () => {
      token = await pair(server.baseUrl);
      assertEqual(token, PAIR_TOKEN, 'token');
      return 'paired';
    });

    await check('seeded session messages carry ascending 1-based seq values', async () => {
      const messages = await getSessionMessages(server.baseUrl, token, SOURCE_SESSION);
      assert(messages.length >= 2, 'seeded messages');
      messages.forEach((message, index) => {
        assertEqual(message.seq, index + 1, `message ${index} seq`);
      });
      return `${messages.length} messages seq 1..${messages.length}`;
    });

    let latestForkId = '';
    await check('forking latest creates a session-fork-… row in the list with a fork title', async () => {
      const result = await api(
        server.baseUrl,
        'POST',
        `/mobile/v1/sessions/${SOURCE_SESSION}/fork`,
        undefined,
        token,
      );
      assertEqual(result.status, 200, 'fork status');
      assertEqual(
        Object.keys(result.json).sort().join(','),
        'forkedFrom,sessionId',
        'latest fork response shape',
      );
      latestForkId = result.json.sessionId;
      assertMatch(latestForkId, /^session-fork-/, 'fork session id prefix');
      assertEqual(result.json.forkedFrom, SOURCE_SESSION, 'forkedFrom');

      const list = await api(server.baseUrl, 'GET', '/mobile/v1/sessions', undefined, token);
      assertEqual(list.status, 200, 'sessions list status');
      const row = list.json.sessions.find((session) => session.id === latestForkId);
      assert(row, 'forked session listed');
      assertMatch(row.title, /^Fork of /, 'fork title');

      const sourceMessages = await getSessionMessages(server.baseUrl, token, SOURCE_SESSION);
      const forkMessages = await getSessionMessages(server.baseUrl, token, latestForkId);
      assertEqual(forkMessages.length, sourceMessages.length, 'latest fork copies all messages');
      assertEqual(
        forkMessages.map((message) => message.seq).join(','),
        sourceMessages.map((message) => message.seq).join(','),
        'latest fork preserves seqs',
      );
      return latestForkId;
    });

    await check('forking at a seeded seq echoes atSeq and copies only messages up to it', async () => {
      const result = await api(
        server.baseUrl,
        'POST',
        `/mobile/v1/sessions/${SOURCE_SESSION}/fork`,
        { atSeq: 2 },
        token,
      );
      assertEqual(result.status, 200, 'atSeq fork status');
      assertEqual(
        Object.keys(result.json).sort().join(','),
        'atSeq,forkedFrom,sessionId',
        'atSeq fork response shape',
      );
      assertEqual(result.json.atSeq, 2, 'echoed atSeq');
      assertEqual(result.json.forkedFrom, SOURCE_SESSION, 'forkedFrom');
      assert(result.json.sessionId !== latestForkId, 'fork ids differ');

      const forkMessages = await getSessionMessages(server.baseUrl, token, result.json.sessionId);
      assertEqual(forkMessages.length, 2, 'only two messages copied');
      assertEqual(forkMessages.map((message) => message.seq).join(','), '1,2', 'seq subset');
      return `${result.json.sessionId} @2`;
    });

    await check('invalid atSeq values 400 and an archived source answers 400', async () => {
      const invalid = [0, -3, 1.5, '2', 999];
      for (const atSeq of invalid) {
        const result = await api(
          server.baseUrl,
          'POST',
          `/mobile/v1/sessions/${SOURCE_SESSION}/fork`,
          { atSeq },
          token,
        );
        assertEqual(result.status, 400, `atSeq ${JSON.stringify(atSeq)} status`);
        assertEqual(result.json?.error?.code, 'invalid_request', `atSeq ${JSON.stringify(atSeq)} code`);
      }
      const missing = await api(
        server.baseUrl,
        'POST',
        `/mobile/v1/sessions/${SOURCE_SESSION}/fork`,
        { atSeq: 424242 },
        token,
      );
      assertEqual(missing.status, 400, 'unknown atSeq status');

      const archived = await api(
        server.baseUrl,
        'POST',
        '/mobile/v1/sessions/s_search/archive',
        undefined,
        token,
      );
      assertEqual(archived.status, 200, 'archive status');
      const archivedFork = await api(
        server.baseUrl,
        'POST',
        '/mobile/v1/sessions/s_search/fork',
        undefined,
        token,
      );
      assertEqual(archivedFork.status, 400, 'archived fork status');
      assertEqual(archivedFork.json?.error?.code, 'invalid_request', 'archived fork code');
      return `${invalid.length + 1} invalid atSeq + archived -> 400`;
    });

    await check('fork of an unknown session answers 404', async () => {
      const result = await api(
        server.baseUrl,
        'POST',
        '/mobile/v1/sessions/s_does_not_exist/fork',
        undefined,
        token,
      );
      assertEqual(result.status, 404, 'unknown fork status');
      assertEqual(result.json?.error?.code, 'not_found', 'unknown fork code');
      return 'unknown -> 404';
    });

    await check('fork without a bearer token answers 401', async () => {
      const result = await api(
        server.baseUrl,
        'POST',
        `/mobile/v1/sessions/${SOURCE_SESSION}/fork`,
        undefined,
      );
      assertEqual(result.status, 401, 'unauthorized fork status');
      assertEqual(result.json?.error?.code, 'unauthorized', 'unauthorized fork code');
      return 'no token -> 401';
    });
  } finally {
    await server.stop();
  }

  console.log('');
  console.log(`[fork-test] ${passCount} passed, ${failCount} failed`);
  if (failCount > 0) {
    console.log('[fork-test] failures:');
    for (const failure of failures) console.log(`  - ${failure}`);
    process.exitCode = 1;
  } else {
    console.log('[fork-test] OK');
  }
}

main().catch((error) => {
  console.error('[fork-test] fatal:', error);
  process.exitCode = 1;
});
