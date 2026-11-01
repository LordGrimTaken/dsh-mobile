#!/usr/bin/env node
/**
 * tools/fake-host/questions-readonly-test.mjs
 *
 * Self-contained test for the fake host's READ-ONLY questions surface:
 *   - spawns server.mjs on a random loopback port (default read-only mode),
 *   - pairs like a real client (pair/new -> pair/complete -> token),
 *   - GET /questions returns {questions, supported:true, readOnly:true} with the
 *     seeded pending question in the documented Question shape (2 options),
 *   - the seeded question.requested SSE fixture is replayable,
 *   - POST /questions/{id} (seeded and unknown) returns 501 unsupported with the
 *     "read-only; resolve on desktop" message,
 *   - legacy compatibility: a second fake host started with
 *     FAKE_QUESTIONS_READONLY=0 (and approvals legacy too) passes the existing
 *     smoke.mjs end to end.
 *
 *   node tools/fake-host/questions-readonly-test.mjs
 *
 * No dependencies; exits 0 only when every check passes.
 */
import http from 'node:http';
import net from 'node:net';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = path.dirname(fileURLToPath(import.meta.url));
const SERVER = path.join(DIR, 'server.mjs');
const SMOKE = path.join(DIR, 'smoke.mjs');
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

/** Start one fake host; returns { baseUrl, child, output(), stop() }. */
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
          child,
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

function parseFrame(raw) {
  const lines = raw.split('\n');
  let event = null;
  let data = null;
  for (const line of lines) {
    if (line.startsWith('event: ')) event = line.slice(7).trim();
    else if (line.startsWith('data: ')) {
      try {
        data = JSON.parse(line.slice(6));
      } catch {
        data = null;
      }
    }
  }
  return event ? { event, data } : null;
}

/** Connect to the SSE stream; frames carry the full envelope on `data:`. */
function connectSse(baseUrl, token, since) {
  const query = typeof since === 'string' && since !== '' ? `?since=${encodeURIComponent(since)}` : '';
  return new Promise((resolve, reject) => {
    const request = http.get(
      `${baseUrl}/mobile/v1/events${query}`,
      { headers: { Authorization: `Bearer ${token}` } },
      (response) => {
        if (response.statusCode !== 200) {
          reject(new Error(`SSE connect failed with HTTP ${response.statusCode}`));
          return;
        }
        response.setEncoding('utf8');
        const frames = [];
        const waiters = [];
        let buffer = '';
        response.on('data', (chunk) => {
          buffer += chunk;
          let index;
          while ((index = buffer.indexOf('\n\n')) >= 0) {
            const raw = buffer.slice(0, index);
            buffer = buffer.slice(index + 2);
            const frame = parseFrame(raw);
            if (!frame) continue;
            frames.push(frame);
            for (const waiter of [...waiters]) {
              if (waiter.match(frame)) {
                waiters.splice(waiters.indexOf(waiter), 1);
                clearTimeout(waiter.timer);
                waiter.resolve(frame);
              }
            }
          }
        });
        resolve({
          frames,
          waitFor(match, timeoutMs = 5000) {
            const existing = frames.find(match);
            if (existing) return Promise.resolve(existing);
            return new Promise((resolveFrame, rejectFrame) => {
              const timer = setTimeout(
                () => rejectFrame(new Error(`timed out after ${timeoutMs}ms waiting for an SSE frame`)),
                timeoutMs,
              );
              waiters.push({ match, resolve: resolveFrame, timer });
            });
          },
          close() {
            request.destroy();
          },
        });
      },
    );
    request.on('error', reject);
    request.setTimeout(10_000, () => request.destroy(new Error('SSE connect timeout')));
  });
}

async function runExistingSmoke(baseUrl) {
  return new Promise((resolve) => {
    let output = '';
    const child = spawn(NODE, [SMOKE], {
      cwd: DIR,
      env: { ...process.env, SMOKE_BASE_URL: baseUrl, SMOKE_TOKEN: PAIR_TOKEN },
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    });
    child.stdout.on('data', (chunk) => {
      output += chunk.toString();
    });
    child.stderr.on('data', (chunk) => {
      output += chunk.toString();
    });
    child.on('exit', (code) => resolve({ code, output }));
    child.on('error', (error) => resolve({ code: -1, output: `${output}\n${error.message}` }));
  });
}

async function main() {
  console.log('[questions-test] starting read-only fake host on a random loopback port...');
  const readOnlyServer = await startServer({ FAKE_QUESTIONS_READONLY: '1' });
  console.log(`[questions-test] read-only host ready at ${readOnlyServer.baseUrl}`);

  let token = '';
  let replaySse = null;
  try {
    await check('pair/new + pair/complete return a device token', async () => {
      token = await pair(readOnlyServer.baseUrl);
      assertEqual(token, PAIR_TOKEN, 'token');
      return 'paired';
    });

    let seededQuestion = null;
    await check('GET /questions returns {questions, supported:true, readOnly:true} with the seeded question', async () => {
      const result = await api(readOnlyServer.baseUrl, 'GET', '/mobile/v1/questions', undefined, token);
      assertEqual(result.status, 200, 'HTTP status');
      assertEqual(result.json?.supported, true, 'supported');
      assertEqual(result.json?.readOnly, true, 'readOnly');
      assert(Array.isArray(result.json?.questions), 'questions array');
      seededQuestion = result.json.questions.find((item) => item.id === 'q_1');
      assert(seededQuestion, 'seeded question present');
      assertEqual(
        Object.keys(seededQuestion).sort().join(','),
        'createdAt,id,options,prompt,sessionId',
        'question shape',
      );
      assert(Array.isArray(seededQuestion.options) && seededQuestion.options.length >= 2, 'question options');
      assert(seededQuestion.options.every((option) => typeof option === 'string'), 'option strings');
      assert(Number.isFinite(Date.parse(seededQuestion.createdAt)), 'createdAt ISO');
      return `id=${seededQuestion.id} options=${seededQuestion.options.length}`;
    });

    await check('seeded question.requested is replayable over SSE (fixture)', async () => {
      replaySse = await connectSse(readOnlyServer.baseUrl, token, 'ev_000000');
      const frame = await replaySse.waitFor(
        (item) => item.event === 'question.requested' && item.data?.data?.id === 'q_1',
      );
      const payload = frame.data.data;
      assertEqual(frame.data.type, 'question.requested', 'envelope type');
      assertEqual(frame.data.sessionId, 's_android', 'envelope sessionId');
      assertEqual(payload.id, 'q_1', 'question id');
      assert(Array.isArray(payload.options) && payload.options.length >= 2, 'fixture options');
      return 'replayed q_1';
    });

    await check('POST /questions/{seeded} returns 501 unsupported "read-only; resolve on desktop"', async () => {
      const result = await api(
        readOnlyServer.baseUrl,
        'POST',
        '/mobile/v1/questions/q_1',
        { answer: 'Keep the stream alive across rotation', optionIndex: 0 },
        token,
      );
      assertEqual(result.status, 501, 'HTTP status');
      assertEqual(result.json?.error?.code, 'unsupported', 'error.code');
      assertMatch(result.json?.error?.message, /read-only;.*resolve on desktop/i, 'error.message');
      return result.json.error.message;
    });

    await check('POST /questions/{unknown} also returns 501 (read-only host)', async () => {
      const result = await api(
        readOnlyServer.baseUrl,
        'POST',
        '/mobile/v1/questions/does-not-exist',
        { answer: 'whatever' },
        token,
      );
      assertEqual(result.status, 501, 'HTTP status');
      assertEqual(result.json?.error?.code, 'unsupported', 'error.code');
      assertMatch(result.json?.error?.message, /read-only;.*resolve on desktop/i, 'error.message');
      return 'unknown id rejected as read-only';
    });
  } finally {
    replaySse?.close();
    await readOnlyServer.stop();
  }

  console.log('[questions-test] starting LEGACY-mode host (FAKE_QUESTIONS_READONLY=0, FAKE_APPROVALS_READONLY=0) for smoke compatibility...');
  const legacyServer = await startServer({ FAKE_QUESTIONS_READONLY: '0', FAKE_APPROVALS_READONLY: '0' });
  try {
    await check('legacy mode reports readOnly:false and keeps the seeded question answerable shape', async () => {
      const legacyToken = await pair(legacyServer.baseUrl);
      const list = await api(legacyServer.baseUrl, 'GET', '/mobile/v1/questions', undefined, legacyToken);
      assertEqual(list.status, 200, 'HTTP status');
      assertEqual(list.json?.supported, true, 'supported');
      assertEqual(list.json?.readOnly, false, 'readOnly');
      assert(Array.isArray(list.json?.questions), 'questions array');
      const seeded = list.json.questions.find((item) => item.id === 'q_1');
      assert(seeded, 'seeded question present in legacy mode');
      assert(Array.isArray(seeded.options) && seeded.options.length >= 2, 'legacy question options');
      return 'legacy readOnly:false, q_1 present';
    });

    await check('existing smoke.mjs passes end-to-end in legacy mode', async () => {
      const result = await runExistingSmoke(legacyServer.baseUrl);
      assertEqual(result.code, 0, `smoke exit code (output tail: ${result.output.slice(-400)})`);
      assertMatch(result.output, /\d+ passed, 0 failed/, 'smoke summary');
      return result.output
        .split('\n')
        .map((line) => line.trim())
        .find((line) => /passed, 0 failed/.test(line)) ?? 'smoke passed';
    });
  } finally {
    await legacyServer.stop();
  }

  console.log('');
  console.log(`[questions-test] ${passCount} passed, ${failCount} failed`);
  if (failCount > 0) {
    console.log('[questions-test] failures:');
    for (const failure of failures) console.log(`  - ${failure}`);
    process.exitCode = 1;
  } else {
    console.log('[questions-test] OK');
  }
}

main().catch((error) => {
  console.error('[questions-test] fatal:', error);
  process.exitCode = 1;
});
