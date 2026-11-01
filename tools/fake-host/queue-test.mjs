#!/usr/bin/env node
/**
 * tools/fake-host/queue-test.mjs
 *
 * Self-contained test for the fake host's per-session prompt queue:
 *   - spawns a fake host, pairs via the real endpoints,
 *   - GET /sessions/{id}/queue -> {supported,live,items:[{itemId,text,target,
 *     attachmentCount,createdAt}]} with two seeded items on s_release
 *     (next-turn then next-step) and an empty queue elsewhere,
 *   - PATCH .../queue/{itemId} {action:"edit"|"remove"|"steer", text?} ->
 *     {accepted:true}: edit replaces text, remove drops the item, steer only
 *     works for next-turn items (otherwise 400),
 *   - invalid action/text 400, unknown item 404, unknown session 404,
 *   - archived/cold sessions report live:false with empty items and reject
 *     PATCH with 400,
 *   - no bearer token -> 401.
 *
 *   node tools/fake-host/queue-test.mjs
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
const ITEM_SHAPE = 'attachmentCount,createdAt,itemId,target,text';
const NEXT_TURN_ID = 'queue_seed_next_turn';
const NEXT_STEP_ID = 'queue_seed_next_step';

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

async function getQueue(baseUrl, token, sessionId) {
  const result = await api(
    baseUrl,
    'GET',
    `/mobile/v1/sessions/${sessionId}/queue`,
    undefined,
    token,
  );
  assertEqual(result.status, 200, 'GET queue status');
  assertEqual(result.json?.supported, true, 'queue supported');
  assert(Array.isArray(result.json?.items), 'queue items array');
  return result.json;
}

function assertIso(value, label) {
  assert(typeof value === 'string' && !Number.isNaN(Date.parse(value)), `${label} must be an ISO timestamp`);
}

async function main() {
  console.log('[queue-test] starting fake host on a random loopback port...');
  const server = await startServer();
  console.log(`[queue-test] fake host ready at ${server.baseUrl}`);

  let token = '';
  try {
    await check('pair/new + pair/complete return a device token', async () => {
      token = await pair(server.baseUrl);
      assertEqual(token, PAIR_TOKEN, 'token');
      return 'paired';
    });

    await check('GET the seeded s_release queue returns two shaped items in order', async () => {
      const queue = await getQueue(server.baseUrl, token, 's_release');
      assertEqual(queue.live, true, 'live');
      assertEqual(queue.items.length, 2, 'two seeded items');
      assertEqual(
        queue.items.map((item) => item.target).join(','),
        'next-turn,next-step',
        'queue order',
      );
      for (const item of queue.items) {
        assertEqual(Object.keys(item).sort().join(','), ITEM_SHAPE, 'item shape');
        assert(typeof item.itemId === 'string' && item.itemId.length > 0, 'itemId');
        assert(typeof item.text === 'string' && item.text.length > 0, 'text');
        assert(typeof item.attachmentCount === 'number', 'attachmentCount');
        assertIso(item.createdAt, 'createdAt');
      }
      assertEqual(queue.items[0].itemId, NEXT_TURN_ID, 'first item id');
      assertEqual(queue.items[1].itemId, NEXT_STEP_ID, 'second item id');
      assertEqual(queue.items[0].attachmentCount, 0, 'next-turn attachmentCount');
      assertEqual(queue.items[1].attachmentCount, 1, 'next-step attachmentCount');
      return `${queue.items.length} items`;
    });

    await check('GET a session without queued prompts returns empty items', async () => {
      const queue = await getQueue(server.baseUrl, token, 's_economy');
      assertEqual(queue.live, true, 'live');
      assertEqual(queue.items.length, 0, 'empty queue');
      return 'empty';
    });

    await check('PATCH edit replaces the text and preserves the other item fields', async () => {
      const edited = await api(
        server.baseUrl,
        'PATCH',
        `/mobile/v1/sessions/s_release/queue/${NEXT_TURN_ID}`,
        { action: 'edit', text: '  Updated: also update the changelog.  ' },
        token,
      );
      assertEqual(edited.status, 200, 'edit status');
      assertEqual(edited.json?.accepted, true, 'edit accepted');
      assertEqual(Object.keys(edited.json).sort().join(','), 'accepted', 'edit response shape');

      const queue = await getQueue(server.baseUrl, token, 's_release');
      assertEqual(queue.items.length, 2, 'queue length unchanged');
      const item = queue.items.find((entry) => entry.itemId === NEXT_TURN_ID);
      assertEqual(item.text, 'Updated: also update the changelog.', 'edited text trimmed');
      assertEqual(item.target, 'next-turn', 'target preserved');
      assertEqual(item.attachmentCount, 0, 'attachmentCount preserved');
      assertIso(item.createdAt, 'createdAt preserved shape');
      return 'edit reflected';
    });

    await check('PATCH steer on a next-step item answers 400', async () => {
      const result = await api(
        server.baseUrl,
        'PATCH',
        `/mobile/v1/sessions/s_release/queue/${NEXT_STEP_ID}`,
        { action: 'steer' },
        token,
      );
      assertEqual(result.status, 400, 'steer next-step status');
      assertEqual(result.json?.error?.code, 'invalid_request', 'steer next-step code');
      const queue = await getQueue(server.baseUrl, token, 's_release');
      assertEqual(queue.items.length, 2, 'queue unchanged after rejected steer');
      return 'next-step steer -> 400';
    });

    await check('invalid action/text bodies 400 and unknown item/session 404', async () => {
      const invalidCases = [
        { body: {}, label: 'missing action' },
        { body: { action: 'move' }, label: 'unknown action' },
        { body: { action: 'edit' }, label: 'edit without text' },
        { body: { action: 'edit', text: '   ' }, label: 'edit empty text' },
        { body: { action: 'edit', text: 42 }, label: 'edit non-string text' },
      ];
      for (const testCase of invalidCases) {
        const result = await api(
          server.baseUrl,
          'PATCH',
          `/mobile/v1/sessions/s_release/queue/${NEXT_TURN_ID}`,
          testCase.body,
          token,
        );
        assertEqual(result.status, 400, `${testCase.label} status`);
        assertEqual(result.json?.error?.code, 'invalid_request', `${testCase.label} code`);
      }

      const unknownItem = await api(
        server.baseUrl,
        'PATCH',
        '/mobile/v1/sessions/s_release/queue/queue_does_not_exist',
        { action: 'remove' },
        token,
      );
      assertEqual(unknownItem.status, 404, 'unknown item status');
      assertEqual(unknownItem.json?.error?.code, 'not_found', 'unknown item code');
      return `${invalidCases.length} invalid bodies + unknown item`;
    });

    await check('PATCH remove and steer drop their items and reflect in GET', async () => {
      const removed = await api(
        server.baseUrl,
        'PATCH',
        `/mobile/v1/sessions/s_release/queue/${NEXT_STEP_ID}`,
        { action: 'remove' },
        token,
      );
      assertEqual(removed.status, 200, 'remove status');
      assertEqual(removed.json?.accepted, true, 'remove accepted');

      let queue = await getQueue(server.baseUrl, token, 's_release');
      assertEqual(queue.items.length, 1, 'one item after remove');
      assertEqual(queue.items[0].itemId, NEXT_TURN_ID, 'next-step removed');

      const steered = await api(
        server.baseUrl,
        'PATCH',
        `/mobile/v1/sessions/s_release/queue/${NEXT_TURN_ID}`,
        { action: 'steer' },
        token,
      );
      assertEqual(steered.status, 200, 'steer status');
      assertEqual(steered.json?.accepted, true, 'steer accepted');

      queue = await getQueue(server.baseUrl, token, 's_release');
      assertEqual(queue.items.length, 0, 'queue empty after remove + steer');
      return 'remove + steer reflected';
    });

    await check('unknown session 404; archived session returns live:false empty items and PATCH 400', async () => {
      const unknownGet = await api(
        server.baseUrl,
        'GET',
        '/mobile/v1/sessions/s_does_not_exist/queue',
        undefined,
        token,
      );
      assertEqual(unknownGet.status, 404, 'unknown session GET status');
      assertEqual(unknownGet.json?.error?.code, 'not_found', 'unknown session GET code');

      const archived = await api(
        server.baseUrl,
        'POST',
        '/mobile/v1/sessions/s_search/archive',
        undefined,
        token,
      );
      assertEqual(archived.status, 200, 'archive status');

      const queue = await getQueue(server.baseUrl, token, 's_search');
      assertEqual(queue.live, false, 'archived live flag');
      assertEqual(queue.items.length, 0, 'archived queue empty');

      const patch = await api(
        server.baseUrl,
        'PATCH',
        `/mobile/v1/sessions/s_search/queue/${NEXT_TURN_ID}`,
        { action: 'remove' },
        token,
      );
      assertEqual(patch.status, 400, 'archived PATCH status');
      assertEqual(patch.json?.error?.code, 'invalid_request', 'archived PATCH code');
      return 'unknown 404; archived -> live:false/empty/400';
    });

    await check('queue routes without a bearer token answer 401', async () => {
      const get = await api(server.baseUrl, 'GET', '/mobile/v1/sessions/s_release/queue');
      assertEqual(get.status, 401, 'unauthorized GET status');
      assertEqual(get.json?.error?.code, 'unauthorized', 'unauthorized GET code');

      const patch = await api(
        server.baseUrl,
        'PATCH',
        `/mobile/v1/sessions/s_release/queue/${NEXT_TURN_ID}`,
        { action: 'remove' },
      );
      assertEqual(patch.status, 401, 'unauthorized PATCH status');
      return 'GET/PATCH -> 401';
    });
  } finally {
    await server.stop();
  }

  console.log('');
  console.log(`[queue-test] ${passCount} passed, ${failCount} failed`);
  if (failCount > 0) {
    console.log('[queue-test] failures:');
    for (const failure of failures) console.log(`  - ${failure}`);
    process.exitCode = 1;
  } else {
    console.log('[queue-test] OK');
  }
}

main().catch((error) => {
  console.error('[queue-test] fatal:', error);
  process.exitCode = 1;
});
