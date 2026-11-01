#!/usr/bin/env node
/**
 * tools/fake-host/search-test.mjs
 *
 * Self-contained test for the fake host's message search endpoint:
 *   - spawns a fake host, pairs via the real endpoints,
 *   - seeded deterministic phrases: `Quasar-Gate` (s_release assistant message)
 *     and `quasar-ledger` (s_economy user message),
 *   - GET /search?q=... returns newest-first hits
 *     ({sessionId,sessionTitle?,messageId,role,snippet,createdAt?}) with
 *     supported:true, offset, nextOffset, hasMore and truncated:false (the fake
 *     host has no bounded-scan cap),
 *   - searches user/assistant text and tool detail, case-insensitively,
 *   - honors limit/offset paging, returns empty hits for no match, 400 for
 *     invalid q/limit/offset, and 401 without a bearer token.
 *
 *   node tools/fake-host/search-test.mjs
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
const HIT_SHAPE = 'createdAt,messageId,role,sessionId,sessionTitle,snippet';

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

async function search(baseUrl, token, params) {
  const searchParams = new URLSearchParams(params);
  return api(baseUrl, 'GET', `/mobile/v1/search?${searchParams.toString()}`, undefined, token);
}

async function searchHits(baseUrl, token, params) {
  const result = await search(baseUrl, token, params);
  assertEqual(result.status, 200, 'search status');
  assertEqual(result.json?.supported, true, 'search supported');
  assert(Array.isArray(result.json?.hits), 'hits array');
  return result.json;
}

function assertIso(value, label) {
  assert(typeof value === 'string' && !Number.isNaN(Date.parse(value)), `${label} must be an ISO timestamp`);
}

async function main() {
  console.log('[search-test] starting fake host on a random loopback port...');
  const server = await startServer();
  console.log(`[search-test] fake host ready at ${server.baseUrl}`);

  let token = '';
  try {
    await check('pair/new + pair/complete return a device token', async () => {
      token = await pair(server.baseUrl);
      assertEqual(token, PAIR_TOKEN, 'token');
      return 'paired';
    });

    await check('a known phrase returns one shaped assistant hit with a snippet', async () => {
      const result = await searchHits(server.baseUrl, token, { q: 'quasar-gate' });
      assertEqual(result.hits.length, 1, 'one hit');
      const hit = result.hits[0];
      assertEqual(Object.keys(hit).sort().join(','), HIT_SHAPE, 'hit shape');
      assertEqual(hit.sessionId, 's_release', 'sessionId');
      assertEqual(hit.sessionTitle, 'Cut a patch release', 'sessionTitle');
      assertEqual(hit.role, 'assistant', 'role');
      assert(hit.messageId, 'messageId');
      assertMatch(hit.snippet.toLowerCase(), /quasar-gate/, 'snippet contains query');
      assert(hit.snippet.length <= 160, 'snippet is short');
      assertIso(hit.createdAt, 'createdAt');
      assertEqual(result.truncated, false, 'not truncated');
      return `${hit.sessionId}/${hit.role}`;
    });

    await check('search is case-insensitive for the second seeded phrase', async () => {
      const result = await searchHits(server.baseUrl, token, { q: 'QUASAR-LEDGER' });
      assertEqual(result.hits.length, 1, 'one hit');
      const hit = result.hits[0];
      assertEqual(hit.sessionId, 's_economy', 'sessionId');
      assertEqual(hit.sessionTitle, 'Economy page copy pass', 'sessionTitle');
      assertEqual(hit.role, 'user', 'role');
      assertMatch(hit.snippet.toLowerCase(), /quasar-ledger/, 'snippet contains query');
      return `${hit.sessionId}/${hit.role}`;
    });

    await check('a shared phrase returns hits across two sessions newest-first', async () => {
      const result = await searchHits(server.baseUrl, token, { q: 'quasar' });
      assert(result.hits.length >= 2, 'at least two hits');
      const sessions = result.hits.map((hit) => hit.sessionId);
      assert(sessions.includes('s_release'), 's_release hit');
      assert(sessions.includes('s_economy'), 's_economy hit');
      assert(
        sessions.indexOf('s_release') < sessions.indexOf('s_economy'),
        'newest hit first (s_release assistant message is newer)',
      );
      for (const hit of result.hits) assertMatch(hit.snippet.toLowerCase(), /quasar/, 'snippet contains query');
      return sessions.join(' -> ');
    });

    await check('tool-detail text is searchable and reports role tool', async () => {
      const result = await searchHits(server.baseUrl, token, { q: 'test.ps1' });
      const hit = result.hits.find((item) => item.role === 'tool');
      assert(hit, 'tool hit');
      assertEqual(hit.sessionId, 's_release', 'tool sessionId');
      assertMatch(hit.snippet, /test\.ps1/, 'tool snippet contains detail');
      return hit.messageId;
    });

    await check('limit is honored; truncated stays false and hasMore tracks the dropped hits', async () => {
      const limited = await searchHits(server.baseUrl, token, { q: 'quasar', limit: '1' });
      assertEqual(limited.hits.length, 1, 'limit=1 returns one hit');
      assertEqual(limited.truncated, false, 'fake host never truncates its scan');
      assertEqual(limited.hasMore, true, 'limit=1 has more matches');
      assertEqual(limited.nextOffset, 1, 'limit=1 nextOffset');

      const full = await searchHits(server.baseUrl, token, { q: 'quasar', limit: '10' });
      assertEqual(full.hits.length >= 2, true, 'limit=10 returns all hits');
      assertEqual(full.truncated, false, 'limit=10 not truncated');
      assertEqual(full.hasMore, false, 'limit=10 hasMore false');
      return `${limited.hits.length}/${full.hits.length} hits`;
    });

    await check('offset pages through newest-first hits without duplicates', async () => {
      const all = await searchHits(server.baseUrl, token, { q: 'quasar', limit: '10' });
      assertEqual(all.hits.length, 2, 'two seeded quasar hits');
      const ids = all.hits.map((hit) => hit.messageId);

      const first = await searchHits(server.baseUrl, token, { q: 'quasar', limit: '1', offset: '0' });
      assertEqual(first.hits.length, 1, 'page 0 has one hit');
      assertEqual(first.offset, 0, 'page 0 offset');
      assertEqual(first.hasMore, true, 'page 0 hasMore');
      assertEqual(first.nextOffset, 1, 'page 0 nextOffset');
      assertEqual(first.truncated, false, 'fake host never truncates its scan');
      assertEqual(first.hits[0].messageId, ids[0], 'page 0 first id');

      const second = await searchHits(server.baseUrl, token, { q: 'quasar', limit: '1', offset: '1' });
      assertEqual(second.hits.length, 1, 'page 1 has one hit');
      assertEqual(second.offset, 1, 'page 1 offset');
      assertEqual(second.hasMore, false, 'page 1 hasMore false (last page)');
      assertEqual(second.nextOffset, null, 'page 1 nextOffset null');
      assertEqual(second.truncated, false, 'page 1 truncated false');
      assertEqual(second.hits[0].messageId, ids[1], 'page 1 second id');
      assert(ids[0] !== ids[1], 'pages contain distinct hits');
      return `${ids.length} hits in 2 pages`;
    });

    await check('offset beyond the matches returns an empty final page', async () => {
      const beyond = await searchHits(server.baseUrl, token, { q: 'quasar', offset: '2', limit: '10' });
      assertEqual(beyond.hits.length, 0, 'offset=2 empty');
      assertEqual(beyond.offset, 2, 'offset echoed');
      assertEqual(beyond.hasMore, false, 'beyond hasMore false');
      assertEqual(beyond.nextOffset, null, 'beyond nextOffset null');
      assertEqual(beyond.truncated, false, 'beyond truncated false');

      const maxOffset = await searchHits(server.baseUrl, token, { q: 'quasar', offset: '500' });
      assertEqual(maxOffset.hits.length, 0, 'offset=500 empty');
      assertEqual(maxOffset.hasMore, false, 'offset=500 hasMore false');
      return 'beyond -> empty; offset 500 allowed';
    });

    await check('invalid offset values answer 400 invalid_request', async () => {
      const cases = [
        { q: 'quasar', offset: '-1' },
        { q: 'quasar', offset: '1.5' },
        { q: 'quasar', offset: 'abc' },
        { q: 'quasar', offset: '501' },
        { q: 'quasar', offset: '' },
      ];
      for (const testCase of cases) {
        const result = await search(server.baseUrl, token, testCase);
        assertEqual(result.status, 400, `invalid offset ${JSON.stringify(testCase)} status`);
        assertEqual(
          result.json?.error?.code,
          'invalid_request',
          `invalid offset ${JSON.stringify(testCase)} code`,
        );
      }
      return `${cases.length} invalid offsets -> 400`;
    });

    await check('role filter restricts hits and accepts comma-separated roles', async () => {
      const unfiltered = await searchHits(server.baseUrl, token, { q: 'quasar' });
      assert(unfiltered.hits.length >= 2, 'unfiltered seeded hits');

      const userOnly = await searchHits(server.baseUrl, token, { q: 'quasar', role: 'user' });
      assert(userOnly.hits.length >= 1, 'user hits');
      for (const hit of userOnly.hits) assertEqual(hit.role, 'user', 'user-only role');
      assert(userOnly.hits.some((hit) => hit.sessionId === 's_economy'), 'user hit in s_economy');

      const assistantOnly = await searchHits(server.baseUrl, token, { q: 'quasar', role: 'assistant' });
      assert(assistantOnly.hits.length >= 1, 'assistant hits');
      for (const hit of assistantOnly.hits) assertEqual(hit.role, 'assistant', 'assistant-only role');
      assert(assistantOnly.hits.some((hit) => hit.sessionId === 's_release'), 'assistant hit in s_release');

      const comma = await searchHits(server.baseUrl, token, { q: 'quasar', role: 'user, assistant' });
      assertEqual(comma.hits.length, unfiltered.hits.length, 'comma list returns both roles');
      for (const hit of comma.hits) assert(['user', 'assistant'].includes(hit.role), 'comma role allowed');
      assert(comma.hits.some((hit) => hit.sessionId === 's_release'), 'comma includes s_release');
      assert(comma.hits.some((hit) => hit.sessionId === 's_economy'), 'comma includes s_economy');

      const toolHit = await searchHits(server.baseUrl, token, { q: 'test.ps1', role: 'tool' });
      assertEqual(toolHit.hits.length >= 1, true, 'tool role keeps tool hit');
      for (const hit of toolHit.hits) assertEqual(hit.role, 'tool', 'tool-only role');

      const noTool = await searchHits(server.baseUrl, token, { q: 'test.ps1', role: 'user,assistant' });
      assertEqual(noTool.hits.length, 0, 'tool hit excluded by non-tool roles');
      return 'user / assistant / comma / tool';
    });

    await check('since/until filter on hit createdAt with inclusive boundaries', async () => {
      const all = await searchHits(server.baseUrl, token, { q: 'quasar' });
      assert(all.hits.length >= 2, 'two seeded hits');
      const newer = all.hits[0];
      const older = all.hits[all.hits.length - 1];
      assert(Date.parse(newer.createdAt) >= Date.parse(older.createdAt), 'hits newest-first');

      const sinceNewer = await searchHits(server.baseUrl, token, {
        q: 'quasar',
        since: newer.createdAt,
      });
      assert(sinceNewer.hits.some((hit) => hit.messageId === newer.messageId), 'since includes boundary hit');
      for (const hit of sinceNewer.hits) {
        assert(Date.parse(hit.createdAt) >= Date.parse(newer.createdAt), 'since lower bound');
      }

      const untilNewer = await searchHits(server.baseUrl, token, {
        q: 'quasar',
        until: newer.createdAt,
      });
      assertEqual(untilNewer.hits.length, all.hits.length, 'until boundary is inclusive');

      const untilOlder = await searchHits(server.baseUrl, token, {
        q: 'quasar',
        until: older.createdAt,
      });
      assertEqual(untilOlder.hits.length, 1, 'until older keeps only the boundary hit');
      assertEqual(untilOlder.hits[0].messageId, older.messageId, 'until boundary hit id');

      const window = await searchHits(server.baseUrl, token, {
        q: 'quasar',
        since: older.createdAt,
        until: newer.createdAt,
      });
      assertEqual(window.hits.length, all.hits.length, 'closed since/until window keeps both');
      return 'inclusive since/until boundaries';
    });

    await check('invalid role/date/range filters answer 400 invalid_request', async () => {
      const all = await searchHits(server.baseUrl, token, { q: 'quasar' });
      const newer = all.hits[0];
      const older = all.hits[all.hits.length - 1];
      const cases = [
        { q: 'quasar', role: 'admin' },
        { q: 'quasar', role: '' },
        { q: 'quasar', role: 'user,' },
        { q: 'quasar', since: 'not-a-date' },
        { q: 'quasar', until: 'nope' },
        { q: 'quasar', since: newer.createdAt, until: older.createdAt },
      ];
      for (const testCase of cases) {
        const result = await search(server.baseUrl, token, testCase);
        assertEqual(result.status, 400, `invalid filter ${JSON.stringify(testCase)} status`);
        assertEqual(
          result.json?.error?.code,
          'invalid_request',
          `invalid filter ${JSON.stringify(testCase)} code`,
        );
      }
      return `${cases.length} invalid filters -> 400`;
    });

    await check('unfiltered search behaviour is unchanged', async () => {
      const result = await searchHits(server.baseUrl, token, { q: 'quasar' });
      assertEqual(
        Object.keys(result).sort().join(','),
        'hasMore,hits,nextOffset,offset,supported,truncated',
        'envelope shape',
      );
      assertEqual(result.supported, true, 'supported');
      assertEqual(result.truncated, false, 'not truncated');
      assertEqual(result.offset, 0, 'offset defaults to 0');
      assertEqual(result.nextOffset, null, 'nextOffset null');
      assertEqual(result.hasMore, false, 'hasMore false');
      assert(result.hits.length >= 2, 'both seeded hits');
      assert(result.hits.some((hit) => hit.sessionId === 's_release'), 's_release hit');
      assert(result.hits.some((hit) => hit.sessionId === 's_economy'), 's_economy hit');
      assert(
        result.hits.every((hit) => Object.keys(hit).sort().join(',') === 'createdAt,messageId,role,sessionId,sessionTitle,snippet'),
        'hit shape unchanged',
      );
      return 'unchanged';
    });

    await check('a no-match query returns empty hits with truncated:false', async () => {
      const result = await searchHits(server.baseUrl, token, { q: 'zzzznotfound' });
      assertEqual(result.hits.length, 0, 'no hits');
      assertEqual(result.truncated, false, 'not truncated');
      return 'empty';
    });

    await check('invalid q and limit values answer 400 invalid_request', async () => {
      const cases = [
        { q: 'a' },
        { q: 'x'.repeat(201) },
        { q: '' },
        { q: 'quasar', limit: '0' },
        { q: 'quasar', limit: 'abc' },
        { q: 'quasar', limit: '101' },
      ];
      for (const testCase of cases) {
        const result = await search(server.baseUrl, token, testCase);
        assertEqual(result.status, 400, `invalid case ${JSON.stringify(testCase)} status`);
        assertEqual(result.json?.error?.code, 'invalid_request', `invalid case ${JSON.stringify(testCase)} code`);
      }
      const missingQuery = await api(server.baseUrl, 'GET', '/mobile/v1/search', undefined, token);
      assertEqual(missingQuery.status, 400, 'missing q status');
      return `${cases.length + 1} invalid queries -> 400`;
    });

    await check('search without a bearer token answers 401 unauthorized', async () => {
      const result = await api(server.baseUrl, 'GET', '/mobile/v1/search?q=quasar');
      assertEqual(result.status, 401, 'unauthorized status');
      assertEqual(result.json?.error?.code, 'unauthorized', 'unauthorized code');
      return 'no token -> 401';
    });
  } finally {
    await server.stop();
  }

  console.log('');
  console.log(`[search-test] ${passCount} passed, ${failCount} failed`);
  if (failCount > 0) {
    console.log('[search-test] failures:');
    for (const failure of failures) console.log(`  - ${failure}`);
    process.exitCode = 1;
  } else {
    console.log('[search-test] OK');
  }
}

main().catch((error) => {
  console.error('[search-test] fatal:', error);
  process.exitCode = 1;
});
