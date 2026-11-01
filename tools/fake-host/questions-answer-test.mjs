#!/usr/bin/env node
/**
 * tools/fake-host/questions-answer-test.mjs
 *
 * Self-contained test for the fake host's MOBILE QUESTION-ANSWERING mode
 * (`FAKE_QUESTIONS_READONLY=0` + `FAKE_QUESTIONS_ANSWER=1`):
 *   - spawns an answer-mode host, pairs via the real endpoints,
 *   - GET /questions -> {questions, supported:true, readOnly:false} with the
 *     single q_1 fixture and the two-question q_batch_1/q_batch_2 batch,
 *   - answers the single question (200 {ok,questionId,answered:1,total:1,
 *     complete:true}), the row disappears and question.resolved fires over SSE,
 *   - answers the batch partially (answered:1,total:2,complete:false, one row
 *     remains), then fully (answered:2,total:2,complete:true),
 *   - repeat/unknown ids answer 404,
 *   - a default read-only host still answers 501,
 *   - legacy mode still passes the existing smoke.mjs end to end.
 *
 *   node tools/fake-host/questions-answer-test.mjs
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

const QUESTION_SHAPE = 'createdAt,id,options,prompt,sessionId';

async function main() {
  console.log('[questions-answer-test] starting ANSWER-mode fake host on a random loopback port...');
  const answerServer = await startServer({
    FAKE_QUESTIONS_READONLY: '0',
    FAKE_QUESTIONS_ANSWER: '1',
  });
  console.log(`[questions-answer-test] answer-mode host ready at ${answerServer.baseUrl}`);

  let token = '';
  let sse = null;
  try {
    await check('pair/new + pair/complete return a device token', async () => {
      token = await pair(answerServer.baseUrl);
      assertEqual(token, PAIR_TOKEN, 'token');
      return 'paired';
    });

    await check('answer-mode GET /questions reports readOnly:false with the single, batch and free-text fixtures', async () => {
      const result = await api(answerServer.baseUrl, 'GET', '/mobile/v1/questions', undefined, token);
      assertEqual(result.status, 200, 'HTTP status');
      assertEqual(result.json?.supported, true, 'supported');
      assertEqual(result.json?.readOnly, false, 'readOnly');
      assert(Array.isArray(result.json?.questions), 'questions array');
      const ids = result.json.questions.map((item) => item.id);
      for (const id of ['q_1', 'q_batch_1', 'q_batch_2', 'q_free']) assert(ids.includes(id), `${id} present`);
      const single = result.json.questions.find((item) => item.id === 'q_1');
      const batch = result.json.questions.filter((item) => item.id.startsWith('q_batch_'));
      const freeText = result.json.questions.find((item) => item.id === 'q_free');
      assertEqual(Object.keys(single).sort().join(','), QUESTION_SHAPE, 'single question shape');
      assertEqual(Object.keys(freeText).sort().join(','), QUESTION_SHAPE, 'free-text question shape');
      assertEqual(freeText.options.length, 0, 'free-text options are empty');
      assertEqual(batch.length, 2, 'two-question batch');
      for (const item of batch) assertEqual(Object.keys(item).sort().join(','), QUESTION_SHAPE, `${item.id} shape`);
      return `${result.json.questions.length} pending questions`;
    });

    await check('POST answer for the single question returns complete:true, removes it and emits question.resolved', async () => {
      sse = await connectSse(answerServer.baseUrl, token);
      const result = await api(
        answerServer.baseUrl,
        'POST',
        '/mobile/v1/questions/q_1',
        { answer: 'Keep the stream alive across rotation', optionIndex: 0 },
        token,
      );
      assertEqual(result.status, 200, 'HTTP status');
      assertEqual(result.json?.ok, true, 'ok');
      assertEqual(result.json?.questionId, 'q_1', 'questionId');
      assertEqual(result.json?.answered, 1, 'answered');
      assertEqual(result.json?.total, 1, 'total');
      assertEqual(result.json?.complete, true, 'complete');

      const frame = await sse.waitFor(
        (item) => item.event === 'question.resolved' && item.data?.data?.questionId === 'q_1',
      );
      assertEqual(frame.data.sessionId, 's_android', 'SSE sessionId');

      const list = await api(answerServer.baseUrl, 'GET', '/mobile/v1/questions', undefined, token);
      assert(!list.json.questions.some((item) => item.id === 'q_1'), 'answered row disappeared');
      return 'single answered; complete:true';
    });

    await check('free-text question accepts a custom answer, rejects empty, resolves and 404s on repeat', async () => {
      const empty = await api(
        answerServer.baseUrl,
        'POST',
        '/mobile/v1/questions/q_free',
        { answer: '   ' },
        token,
      );
      assertEqual(empty.status, 400, 'empty answer status');
      assertEqual(empty.json?.error?.code, 'invalid_request', 'empty answer code');

      const result = await api(
        answerServer.baseUrl,
        'POST',
        '/mobile/v1/questions/q_free',
        { answer: '  Ship it with a short changelog note.  ' },
        token,
      );
      assertEqual(result.status, 200, 'HTTP status');
      assertEqual(result.json?.ok, true, 'ok');
      assertEqual(result.json?.questionId, 'q_free', 'questionId');
      assertEqual(result.json?.answered, 1, 'answered');
      assertEqual(result.json?.total, 1, 'total');
      assertEqual(result.json?.complete, true, 'complete');

      const frame = await sse.waitFor(
        (item) => item.event === 'question.resolved' && item.data?.data?.questionId === 'q_free',
      );
      assertEqual(frame.data.sessionId, 's_economy', 'SSE sessionId');

      const list = await api(answerServer.baseUrl, 'GET', '/mobile/v1/questions', undefined, token);
      assert(!list.json.questions.some((item) => item.id === 'q_free'), 'free-text row disappeared');

      const repeat = await api(
        answerServer.baseUrl,
        'POST',
        '/mobile/v1/questions/q_free',
        { answer: 'again' },
        token,
      );
      assertEqual(repeat.status, 404, 'repeat status');
      assertEqual(repeat.json?.error?.code, 'not_found', 'repeat code');
      return 'custom free-text answer accepted; empty 400; repeat 404';
    });

    await check('batch partial answer reports complete:false and keeps the remaining row', async () => {
      const result = await api(
        answerServer.baseUrl,
        'POST',
        '/mobile/v1/questions/q_batch_1',
        { answer: 'stable', optionIndex: 0 },
        token,
      );
      assertEqual(result.status, 200, 'HTTP status');
      assertEqual(result.json?.ok, true, 'ok');
      assertEqual(result.json?.questionId, 'q_batch_1', 'questionId');
      assertEqual(result.json?.answered, 1, 'answered');
      assertEqual(result.json?.total, 2, 'total');
      assertEqual(result.json?.complete, false, 'complete');

      const frame = await sse.waitFor(
        (item) => item.event === 'question.resolved' && item.data?.data?.questionId === 'q_batch_1',
      );
      assertEqual(frame.data.sessionId, 's_release', 'SSE sessionId');

      const list = await api(answerServer.baseUrl, 'GET', '/mobile/v1/questions', undefined, token);
      assert(!list.json.questions.some((item) => item.id === 'q_batch_1'), 'answered batch row disappeared');
      assert(list.json.questions.some((item) => item.id === 'q_batch_2'), 'remaining batch row kept');
      return 'partial answered:1/total:2 complete:false';
    });

    await check('batch final custom answer reports complete:true and removes the batch', async () => {
      // No optionIndex and text that matches no option: the host contract treats
      // this as a custom answer (selected []), so it must still be accepted.
      const result = await api(
        answerServer.baseUrl,
        'POST',
        '/mobile/v1/questions/q_batch_2',
        { answer: 'ship it when ready' },
        token,
      );
      assertEqual(result.status, 200, 'HTTP status');
      assertEqual(result.json?.ok, true, 'ok');
      assertEqual(result.json?.questionId, 'q_batch_2', 'questionId');
      assertEqual(result.json?.answered, 2, 'answered');
      assertEqual(result.json?.total, 2, 'total');
      assertEqual(result.json?.complete, true, 'complete');

      const frame = await sse.waitFor(
        (item) => item.event === 'question.resolved' && item.data?.data?.questionId === 'q_batch_2',
      );
      assertEqual(frame.data.sessionId, 's_release', 'SSE sessionId');

      const list = await api(answerServer.baseUrl, 'GET', '/mobile/v1/questions', undefined, token);
      assert(!list.json.questions.some((item) => item.id.startsWith('q_batch_')), 'batch fully resolved');
      return 'custom answer accepted; answered:2/total:2 complete:true';
    });

    await check('repeat and unknown question answers return 404', async () => {
      const repeat = await api(
        answerServer.baseUrl,
        'POST',
        '/mobile/v1/questions/q_batch_2',
        { answer: 'stable' },
        token,
      );
      assertEqual(repeat.status, 404, 'repeat status');
      assertEqual(repeat.json?.error?.code, 'not_found', 'repeat code');

      const unknown = await api(
        answerServer.baseUrl,
        'POST',
        '/mobile/v1/questions/does-not-exist',
        { answer: 'whatever' },
        token,
      );
      assertEqual(unknown.status, 404, 'unknown status');
      assertEqual(unknown.json?.error?.code, 'not_found', 'unknown code');
      return 'repeat/unknown -> 404';
    });
  } finally {
    sse?.close();
    await answerServer.stop();
  }

  console.log('[questions-answer-test] starting read-only host to verify it still answers 501...');
  const readOnlyServer = await startServer();
  try {
    await check('read-only mode still answers 501 for POST /questions/{id}', async () => {
      const readOnlyToken = await pair(readOnlyServer.baseUrl);
      const list = await api(readOnlyServer.baseUrl, 'GET', '/mobile/v1/questions', undefined, readOnlyToken);
      assertEqual(list.status, 200, 'list status');
      assertEqual(list.json?.readOnly, true, 'readOnly');
      assert(list.json.questions.some((item) => item.id === 'q_1'), 'read-only seed present');

      const result = await api(
        readOnlyServer.baseUrl,
        'POST',
        '/mobile/v1/questions/q_1',
        { answer: 'whatever' },
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

  console.log('[questions-answer-test] starting LEGACY-mode host for smoke compatibility...');
  const legacyServer = await startServer({
    FAKE_QUESTIONS_READONLY: '0',
    FAKE_APPROVALS_READONLY: '0',
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
  console.log(`[questions-answer-test] ${passCount} passed, ${failCount} failed`);
  if (failCount > 0) {
    console.log('[questions-answer-test] failures:');
    for (const failure of failures) console.log(`  - ${failure}`);
    process.exitCode = 1;
  } else {
    console.log('[questions-answer-test] OK');
  }
}

main().catch((error) => {
  console.error('[questions-answer-test] fatal:', error);
  process.exitCode = 1;
});
