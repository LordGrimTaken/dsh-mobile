#!/usr/bin/env node
/**
 * tools/fake-host/sessions-management-test.mjs
 *
 * Self-contained test for the fake host's session rename/archive/unarchive and
 * archived-list management routes:
 *   - spawns a fake host, pairs via the real endpoints,
 *   - rename a seeded session (trimmed 1..200 title) and verify it in the
 *     session list + session detail,
 *   - archive it: it disappears from GET /sessions and appears in
 *     GET /sessions/archived ({sessions, supported:true}),
 *   - archived sessions reject rename/archive with 404, and unarchive restores
 *     it to the main list,
 *   - invalid rename bodies 400, unknown ids 404,
 *   - no bearer token -> 401.
 *
 *   node tools/fake-host/sessions-management-test.mjs
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

async function listSessions(baseUrl, token) {
  const result = await api(baseUrl, 'GET', '/mobile/v1/sessions', undefined, token);
  assertEqual(result.status, 200, 'sessions list status');
  assert(Array.isArray(result.json?.sessions), 'sessions array');
  return result.json.sessions;
}

async function listArchived(baseUrl, token) {
  const result = await api(baseUrl, 'GET', '/mobile/v1/sessions/archived', undefined, token);
  assertEqual(result.status, 200, 'archived list status');
  assertEqual(result.json?.supported, true, 'archived supported');
  assert(Array.isArray(result.json?.sessions), 'archived sessions array');
  return result.json.sessions;
}

async function main() {
  console.log('[sessions-management-test] starting fake host on a random loopback port...');
  const server = await startServer();
  console.log(`[sessions-management-test] fake host ready at ${server.baseUrl}`);

  let token = '';
  let renamedTitle = 'Economy copy pass (renamed)';
  try {
    await check('pair/new + pair/complete return a device token', async () => {
      token = await pair(server.baseUrl);
      assertEqual(token, PAIR_TOKEN, 'token');
      return 'paired';
    });

    await check('GET /sessions/archived starts empty with {sessions,supported:true}', async () => {
      const archived = await listArchived(server.baseUrl, token);
      assertEqual(archived.length, 0, 'no sessions archived at startup');
      const listed = await listSessions(server.baseUrl, token);
      assert(listed.some((session) => session.id === 's_economy'), 'seeded s_economy listed');
      return 'empty archived list';
    });

    await check('POST /sessions/{id}/rename trims the title and updates list/detail', async () => {
      const result = await api(
        server.baseUrl,
        'POST',
        '/mobile/v1/sessions/s_economy/rename',
        { title: '  Economy copy pass (renamed)  ' },
        token,
      );
      assertEqual(result.status, 200, 'rename status');
      assertEqual(result.json?.ok, true, 'rename ok');
      assertEqual(result.json?.title, renamedTitle, 'rename response title');

      const listed = await listSessions(server.baseUrl, token);
      const listedSession = listed.find((session) => session.id === 's_economy');
      assert(listedSession, 'renamed session still listed');
      assertEqual(listedSession.title, renamedTitle, 'list title updated');

      const detail = await api(server.baseUrl, 'GET', '/mobile/v1/sessions/s_economy', undefined, token);
      assertEqual(detail.status, 200, 'detail status');
      assertEqual(detail.json?.session?.title, renamedTitle, 'detail title updated');
      return renamedTitle;
    });

    await check('POST /sessions/{id}/archive hides it from the list and shows it in /sessions/archived', async () => {
      const result = await api(server.baseUrl, 'POST', '/mobile/v1/sessions/s_economy/archive', undefined, token);
      assertEqual(result.status, 200, 'archive status');
      assertEqual(result.json?.ok, true, 'archive ok');
      assertEqual(result.json?.archived, true, 'archive flag');

      const listed = await listSessions(server.baseUrl, token);
      assert(!listed.some((session) => session.id === 's_economy'), 'archived session hidden from main list');

      const archived = await listArchived(server.baseUrl, token);
      const archivedSession = archived.find((session) => session.id === 's_economy');
      assert(archivedSession, 'archived session appears in archived list');
      assertEqual(archivedSession.title, renamedTitle, 'archived list keeps renamed title');
      assertEqual(archivedSession.sessionId, undefined, 'archived row is a SessionSummary');
      return 'hidden from /sessions; listed in /sessions/archived';
    });

    await check('archived sessions reject rename/archive with 404; unarchive of a live session 404', async () => {
      const renameArchived = await api(
        server.baseUrl,
        'POST',
        '/mobile/v1/sessions/s_economy/rename',
        { title: 'nope' },
        token,
      );
      assertEqual(renameArchived.status, 404, 'rename archived status');
      assertEqual(renameArchived.json?.error?.code, 'not_found', 'rename archived code');

      const archiveAgain = await api(server.baseUrl, 'POST', '/mobile/v1/sessions/s_economy/archive', undefined, token);
      assertEqual(archiveAgain.status, 404, 'archive-again status');

      const unarchiveLive = await api(
        server.baseUrl,
        'POST',
        '/mobile/v1/sessions/s_release/unarchive',
        undefined,
        token,
      );
      assertEqual(unarchiveLive.status, 404, 'unarchive-live status');
      return 'archived visibility -> 404';
    });

    await check('POST /sessions/{id}/unarchive restores it to the main list', async () => {
      const result = await api(server.baseUrl, 'POST', '/mobile/v1/sessions/s_economy/unarchive', undefined, token);
      assertEqual(result.status, 200, 'unarchive status');
      assertEqual(result.json?.ok, true, 'unarchive ok');
      assertEqual(result.json?.archived, false, 'unarchive flag');

      const listed = await listSessions(server.baseUrl, token);
      assert(listed.some((session) => session.id === 's_economy'), 'unarchived session restored to main list');
      const archived = await listArchived(server.baseUrl, token);
      assert(!archived.some((session) => session.id === 's_economy'), 'session removed from archived list');
      return 'restored';
    });

    await check('invalid rename bodies 400 and unknown sessions 404', async () => {
      const cases = [
        { body: {}, label: 'missing title' },
        { body: { title: '' }, label: 'empty title' },
        { body: { title: '   ' }, label: 'whitespace title' },
        { body: { title: 42 }, label: 'non-string title' },
        { body: { title: 'x'.repeat(201) }, label: 'title too long' },
      ];
      for (const testCase of cases) {
        const result = await api(
          server.baseUrl,
          'POST',
          '/mobile/v1/sessions/s_release/rename',
          testCase.body,
          token,
        );
        assertEqual(result.status, 400, `${testCase.label} status`);
        assertEqual(result.json?.error?.code, 'invalid_request', `${testCase.label} code`);
      }
      for (const pathName of [
        '/mobile/v1/sessions/s_nope/rename',
        '/mobile/v1/sessions/s_nope/archive',
        '/mobile/v1/sessions/s_nope/unarchive',
      ]) {
        const isRename = pathName.endsWith('/rename');
        const result = await api(
          server.baseUrl,
          'POST',
          pathName,
          isRename ? { title: 'x' } : undefined,
          token,
        );
        assertEqual(result.status, 404, `${pathName} status`);
        assertEqual(result.json?.error?.code, 'not_found', `${pathName} code`);
      }
      return `${cases.length} invalid bodies; unknown ids 404`;
    });

    await check('session-management routes without a bearer token answer 401', async () => {
      const archived = await api(server.baseUrl, 'GET', '/mobile/v1/sessions/archived');
      assertEqual(archived.status, 401, 'archived unauthorized status');
      assertEqual(archived.json?.error?.code, 'unauthorized', 'archived unauthorized code');
      const rename = await api(server.baseUrl, 'POST', '/mobile/v1/sessions/s_release/rename', { title: 'x' });
      assertEqual(rename.status, 401, 'rename unauthorized status');
      return 'no token -> 401';
    });
  } finally {
    await server.stop();
  }

  console.log('');
  console.log(`[sessions-management-test] ${passCount} passed, ${failCount} failed`);
  if (failCount > 0) {
    console.log('[sessions-management-test] failures:');
    for (const failure of failures) console.log(`  - ${failure}`);
    process.exitCode = 1;
  } else {
    console.log('[sessions-management-test] OK');
  }
}

main().catch((error) => {
  console.error('[sessions-management-test] fatal:', error);
  process.exitCode = 1;
});
