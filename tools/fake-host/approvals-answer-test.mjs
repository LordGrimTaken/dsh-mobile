#!/usr/bin/env node
/**
 * tools/fake-host/approvals-answer-test.mjs
 *
 * Self-contained test for the fake host's MOBILE-ANSWERING approvals mode
 * (`FAKE_APPROVALS_READONLY=0` + `FAKE_APPROVALS_ANSWER=1`):
 *   - spawns an answer-mode host, pairs via the real endpoints,
 *   - GET /approvals -> {approvals, supported:true, readOnly:false} with two
 *     seeded pending approvals (allow target + deny target),
 *   - POST allow -> 200 {ok:true, approvalId, outcome:"allowed-once"}, the
 *     approval disappears, approval.resolved is emitted over SSE, and a second
 *     POST answers 404,
 *   - POST deny on the fresh deny fixture -> 200 outcome "rejected", disappears,
 *     second POST 404,
 *   - unknown ids answer 404,
 *   - a default read-only host still answers 501 for POST /approvals/{id},
 *   - legacy mode (`FAKE_APPROVALS_READONLY=0` without ANSWER) still passes the
 *     existing smoke.mjs end to end.
 *
 *   node tools/fake-host/approvals-answer-test.mjs
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
function connectSse(baseUrl, token) {
  return new Promise((resolve, reject) => {
    const request = http.get(
      `${baseUrl}/mobile/v1/events`,
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
  console.log('[approvals-answer-test] starting ANSWER-mode fake host on a random loopback port...');
  const answerServer = await startServer({
    FAKE_APPROVALS_READONLY: '0',
    FAKE_APPROVALS_ANSWER: '1',
  });
  console.log(`[approvals-answer-test] answer-mode host ready at ${answerServer.baseUrl}`);

  let token = '';
  let sse = null;
  try {
    await check('pair/new + pair/complete return a device token', async () => {
      token = await pair(answerServer.baseUrl);
      assertEqual(token, PAIR_TOKEN, 'token');
      return 'paired';
    });

    await check('answer-mode GET /approvals reports readOnly:false with the seeded pending approvals', async () => {
      const result = await api(answerServer.baseUrl, 'GET', '/mobile/v1/approvals', undefined, token);
      assertEqual(result.status, 200, 'HTTP status');
      assertEqual(result.json?.supported, true, 'supported');
      assertEqual(result.json?.readOnly, false, 'readOnly');
      assert(Array.isArray(result.json?.approvals), 'approvals array');
      const allowTarget = result.json.approvals.find((item) => item.id === 'ap_answer_seed');
      const denyTarget = result.json.approvals.find((item) => item.id === 'ap_answer_deny');
      assert(allowTarget, 'allow target seeded');
      assert(denyTarget, 'deny target seeded');
      assertEqual(
        Object.keys(allowTarget).sort().join(','),
        'command,createdAt,description,id,sessionId,title',
        'approval shape',
      );
      return `${result.json.approvals.length} pending approvals`;
    });

    await check('POST allow resolves once (outcome allowed-once), emits approval.resolved and then 404s', async () => {
      sse = await connectSse(answerServer.baseUrl, token);
      const result = await api(
        answerServer.baseUrl,
        'POST',
        '/mobile/v1/approvals/ap_answer_seed',
        { decision: 'allow' },
        token,
      );
      assertEqual(result.status, 200, 'allow status');
      assertEqual(result.json?.ok, true, 'ok');
      assertEqual(result.json?.approvalId, 'ap_answer_seed', 'approvalId');
      assertEqual(result.json?.outcome, 'allowed-once', 'outcome');

      const frame = await sse.waitFor(
        (item) => item.event === 'approval.resolved' && item.data?.data?.approvalId === 'ap_answer_seed',
      );
      assertEqual(frame.data.type, 'approval.resolved', 'SSE envelope type');
      assertEqual(frame.data.sessionId, 's_release', 'SSE sessionId');

      const list = await api(answerServer.baseUrl, 'GET', '/mobile/v1/approvals', undefined, token);
      assertEqual(list.status, 200, 'list status');
      assertEqual(list.json?.readOnly, false, 'still answering');
      assert(!list.json.approvals.some((item) => item.id === 'ap_answer_seed'), 'allowed approval disappeared');

      const again = await api(
        answerServer.baseUrl,
        'POST',
        '/mobile/v1/approvals/ap_answer_seed',
        { decision: 'allow' },
        token,
      );
      assertEqual(again.status, 404, 'second allow status');
      assertEqual(again.json?.error?.code, 'not_found', 'second allow code');
      return 'allowed-once -> resolved -> 404 on repeat';
    });

    await check('POST deny resolves the fresh deny fixture with outcome rejected, then 404s', async () => {
      const result = await api(
        answerServer.baseUrl,
        'POST',
        '/mobile/v1/approvals/ap_answer_deny',
        { decision: 'deny' },
        token,
      );
      assertEqual(result.status, 200, 'deny status');
      assertEqual(result.json?.ok, true, 'ok');
      assertEqual(result.json?.outcome, 'rejected', 'outcome');

      const frame = await sse.waitFor(
        (item) => item.event === 'approval.resolved' && item.data?.data?.approvalId === 'ap_answer_deny',
      );
      assertEqual(frame.data.sessionId, 's_android', 'SSE sessionId');

      const list = await api(answerServer.baseUrl, 'GET', '/mobile/v1/approvals', undefined, token);
      assert(!list.json.approvals.some((item) => item.id === 'ap_answer_deny'), 'denied approval disappeared');

      const again = await api(
        answerServer.baseUrl,
        'POST',
        '/mobile/v1/approvals/ap_answer_deny',
        { decision: 'deny' },
        token,
      );
      assertEqual(again.status, 404, 'second deny status');
      assertEqual(again.json?.error?.code, 'not_found', 'second deny code');
      return 'rejected -> resolved -> 404 on repeat';
    });

    await check('unknown approval id answers 404 in answer mode', async () => {
      const result = await api(
        answerServer.baseUrl,
        'POST',
        '/mobile/v1/approvals/does-not-exist',
        { decision: 'allow' },
        token,
      );
      assertEqual(result.status, 404, 'unknown status');
      assertEqual(result.json?.error?.code, 'not_found', 'unknown code');
      return 'unknown id -> 404';
    });
  } finally {
    sse?.close();
    await answerServer.stop();
  }

  console.log('[approvals-answer-test] starting read-only host to verify it still answers 501...');
  const readOnlyServer = await startServer();
  try {
    await check('read-only mode still answers 501 for POST /approvals/{id}', async () => {
      const readOnlyToken = await pair(readOnlyServer.baseUrl);
      const list = await api(readOnlyServer.baseUrl, 'GET', '/mobile/v1/approvals', undefined, readOnlyToken);
      assertEqual(list.status, 200, 'list status');
      assertEqual(list.json?.readOnly, true, 'readOnly');
      assert(list.json.approvals.some((item) => item.id === 'ap_readonly_seed'), 'read-only seed present');

      const result = await api(
        readOnlyServer.baseUrl,
        'POST',
        '/mobile/v1/approvals/ap_readonly_seed',
        { decision: 'allow' },
        readOnlyToken,
      );
      assertEqual(result.status, 501, 'read-only status');
      assertEqual(result.json?.error?.code, 'unsupported', 'read-only code');
      assertMatch(result.json?.error?.message, /read-only;.*resolve on desktop/i, 'read-only message');
      return result.json.error.message;
    });
  } finally {
    await readOnlyServer.stop();
  }

  console.log('[approvals-answer-test] starting LEGACY-mode host for smoke compatibility...');
  const legacyServer = await startServer({
    FAKE_APPROVALS_READONLY: '0',
    FAKE_QUESTIONS_READONLY: '0',
  });
  try {
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
  console.log(`[approvals-answer-test] ${passCount} passed, ${failCount} failed`);
  if (failCount > 0) {
    console.log('[approvals-answer-test] failures:');
    for (const failure of failures) console.log(`  - ${failure}`);
    process.exitCode = 1;
  } else {
    console.log('[approvals-answer-test] OK');
  }
}

main().catch((error) => {
  console.error('[approvals-answer-test] fatal:', error);
  process.exitCode = 1;
});
