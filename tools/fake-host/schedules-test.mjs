#!/usr/bin/env node
/**
 * tools/fake-host/schedules-test.mjs
 *
 * Self-contained test for the fake host's in-memory schedules API:
 *   - spawns a fake host, pairs via the real endpoints,
 *   - GET /schedules -> {schedules, supported:true} with the seeded active
 *     daily + paused one-off fixtures in the frozen Schedule shape
 *     (id/sessionId/sessionTitle/title/instruction/status/schedule/createdAt/
 *     updatedAt, schedule {kind,summary,nextRunAt?,lastRunAt?}),
 *   - POST /sessions/{id}/schedules creates an afterSeconds schedule and a
 *     dailyTime schedule, both listed afterwards,
 *   - DELETE /schedules/{id} removes them ({ok:true,deleted:true}),
 *   - unknown delete -> 404, invalid create -> 400, unknown session -> 404,
 *   - no bearer token -> 401.
 *
 *   node tools/fake-host/schedules-test.mjs
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
const SCHEDULE_SHAPE = 'createdAt,id,instruction,schedule,sessionId,sessionTitle,status,title,updatedAt';

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

async function getSchedules(baseUrl, token) {
  const result = await api(baseUrl, 'GET', '/mobile/v1/schedules', undefined, token);
  assertEqual(result.status, 200, 'list status');
  assert(Array.isArray(result.json?.schedules), 'schedules array');
  return result.json;
}

function assertIso(value, label) {
  assert(typeof value === 'string' && !Number.isNaN(Date.parse(value)), `${label} must be an ISO timestamp`);
}

async function main() {
  console.log('[schedules-test] starting fake host on a random loopback port...');
  const server = await startServer();
  console.log(`[schedules-test] fake host ready at ${server.baseUrl}`);

  let token = '';
  try {
    await check('pair/new + pair/complete return a device token', async () => {
      token = await pair(server.baseUrl);
      assertEqual(token, PAIR_TOKEN, 'token');
      return 'paired';
    });

    await check('GET /schedules reports supported:true with the seeded daily + one-off fixtures', async () => {
      const result = await api(server.baseUrl, 'GET', '/mobile/v1/schedules', undefined, token);
      assertEqual(result.status, 200, 'HTTP status');
      assertEqual(result.json?.supported, true, 'supported');
      assert(Array.isArray(result.json?.schedules), 'schedules array');
      assert(result.json.schedules.length >= 2, 'seeded schedules present');

      const daily = result.json.schedules.find((item) => item.id === 'sch_seed_daily');
      const once = result.json.schedules.find((item) => item.id === 'sch_seed_once');
      assert(daily, 'seeded daily schedule');
      assert(once, 'seeded one-off schedule');
      assertEqual(Object.keys(daily).sort().join(','), SCHEDULE_SHAPE, 'daily schedule shape');
      assertEqual(Object.keys(once).sort().join(','), SCHEDULE_SHAPE, 'one-off schedule shape');

      assertEqual(daily.sessionId, 's_release', 'daily sessionId');
      assertEqual(daily.sessionTitle, 'Cut a patch release', 'daily sessionTitle');
      assertEqual(daily.status, 'active', 'daily status');
      assertEqual(daily.schedule.kind, 'daily', 'daily kind');
      assertMatch(daily.schedule.summary, /every day at 09:00/i, 'daily summary');
      assertIso(daily.schedule.nextRunAt, 'daily nextRunAt');
      assertIso(daily.schedule.lastRunAt, 'daily lastRunAt');

      assertEqual(once.sessionId, 's_economy', 'one-off sessionId');
      assertEqual(once.sessionTitle, 'Economy page copy pass', 'one-off sessionTitle');
      assertEqual(once.status, 'paused', 'one-off inactive status');
      assertEqual(once.schedule.kind, 'at', 'one-off kind');
      assertIso(once.schedule.nextRunAt, 'one-off nextRunAt');
      return `${result.json.schedules.length} seeded schedules`;
    });

    let afterScheduleId = '';
    await check('POST /sessions/{id}/schedules creates an afterSeconds schedule that lists', async () => {
      const before = Date.now();
      const result = await api(
        server.baseUrl,
        'POST',
        '/mobile/v1/sessions/s_android/schedules',
        { title: '  Quick build check  ', instruction: ' Check the latest build once. ', afterSeconds: 30 },
        token,
      );
      assertEqual(result.status, 200, 'create status');
      const row = result.json;
      assert(row?.id, 'created id');
      afterScheduleId = row.id;
      assertEqual(row.sessionId, 's_android', 'created sessionId');
      assertEqual(row.sessionTitle, 'Android stream teardown', 'created sessionTitle');
      assertEqual(row.title, 'Quick build check', 'created title trimmed');
      assertEqual(row.instruction, 'Check the latest build once.', 'created instruction trimmed');
      assertEqual(row.status, 'active', 'created status');
      assertEqual(row.schedule.kind, 'after', 'created kind');
      assertMatch(row.schedule.summary, /30 seconds/i, 'created summary');
      assertIso(row.schedule.nextRunAt, 'created nextRunAt');
      const nextRun = Date.parse(row.schedule.nextRunAt);
      assert(nextRun >= before + 20_000 && nextRun <= Date.now() + 40_000, 'nextRunAt roughly 30s ahead');

      const list = await getSchedules(server.baseUrl, token);
      assert(list.schedules.some((item) => item.id === afterScheduleId), 'created schedule listed');
      return afterScheduleId;
    });

    let dailyScheduleId = '';
    await check('POST /sessions/{id}/schedules creates a dailyTime schedule that lists', async () => {
      const result = await api(
        server.baseUrl,
        'POST',
        '/mobile/v1/sessions/s_release/schedules',
        { title: 'Morning release check', instruction: 'Summarize the release status every morning.', dailyTime: '09:15' },
        token,
      );
      assertEqual(result.status, 200, 'create status');
      const row = result.json;
      assert(row?.id, 'created id');
      dailyScheduleId = row.id;
      assertEqual(row.schedule.kind, 'daily', 'created kind');
      assertEqual(row.schedule.summary, 'Every day at 09:15', 'created summary');
      assertIso(row.schedule.nextRunAt, 'created nextRunAt');

      const list = await getSchedules(server.baseUrl, token);
      assert(list.schedules.some((item) => item.id === dailyScheduleId), 'created daily schedule listed');
      return dailyScheduleId;
    });

    await check('PATCH updates title and instruction on a seeded schedule and the list reflects it', async () => {
      const before = await getSchedules(server.baseUrl, token);
      const seeded = before.schedules.find((item) => item.id === 'sch_seed_daily');
      assert(seeded, 'seeded daily schedule');

      const result = await api(
        server.baseUrl,
        'PATCH',
        '/mobile/v1/schedules/sch_seed_daily',
        {
          title: '  Updated daily release status  ',
          instruction: '  Summarize the release status and note any open questions.  ',
        },
        token,
      );
      assertEqual(result.status, 200, 'patch status');
      const row = result.json;
      assertEqual(row.id, 'sch_seed_daily', 'id preserved');
      assertEqual(row.sessionId, 's_release', 'sessionId preserved');
      assertEqual(row.sessionTitle, 'Cut a patch release', 'sessionTitle preserved');
      assertEqual(row.status, 'active', 'status preserved');
      assertEqual(row.createdAt, seeded.createdAt, 'createdAt preserved');
      assertEqual(row.title, 'Updated daily release status', 'title updated/trimmed');
      assertEqual(
        row.instruction,
        'Summarize the release status and note any open questions.',
        'instruction updated/trimmed',
      );
      assertEqual(row.schedule.kind, seeded.schedule.kind, 'timing kind unchanged');
      assertEqual(row.schedule.summary, seeded.schedule.summary, 'timing summary unchanged');
      assertEqual(row.schedule.nextRunAt, seeded.schedule.nextRunAt, 'nextRunAt unchanged');
      assert(Date.parse(row.updatedAt) >= Date.parse(seeded.updatedAt), 'updatedAt bumped');

      const list = await getSchedules(server.baseUrl, token);
      const listed = list.schedules.find((item) => item.id === 'sch_seed_daily');
      assertEqual(listed?.title, 'Updated daily release status', 'list shows updated title');
      assertEqual(
        listed?.instruction,
        'Summarize the release status and note any open questions.',
        'list shows updated instruction',
      );
      return 'title+instruction updated; timing preserved';
    });

    await check('PATCH updates timing (afterSeconds -> dailyTime) and recomputes kind/summary/nextRunAt', async () => {
      const afterPatch = await api(
        server.baseUrl,
        'PATCH',
        `/mobile/v1/schedules/${afterScheduleId}`,
        { afterSeconds: 45 },
        token,
      );
      assertEqual(afterPatch.status, 200, 'afterSeconds patch status');
      assertEqual(afterPatch.json.schedule.kind, 'after', 'afterSeconds kind');
      assertMatch(afterPatch.json.schedule.summary, /45 seconds/i, 'afterSeconds summary');
      assertIso(afterPatch.json.schedule.nextRunAt, 'afterSeconds nextRunAt');
      assertEqual(afterPatch.json.title, 'Quick build check', 'title preserved');
      assertEqual(afterPatch.json.status, 'active', 'status preserved');

      const dailyPatch = await api(
        server.baseUrl,
        'PATCH',
        `/mobile/v1/schedules/${afterScheduleId}`,
        { dailyTime: '07:30' },
        token,
      );
      assertEqual(dailyPatch.status, 200, 'dailyTime patch status');
      assertEqual(dailyPatch.json.schedule.kind, 'daily', 'dailyTime kind');
      assertEqual(dailyPatch.json.schedule.summary, 'Every day at 07:30', 'dailyTime summary');
      assertIso(dailyPatch.json.schedule.nextRunAt, 'dailyTime nextRunAt');

      const list = await getSchedules(server.baseUrl, token);
      const listed = list.schedules.find((item) => item.id === afterScheduleId);
      assertEqual(listed?.schedule.kind, 'daily', 'list reflects updated kind');
      assertEqual(listed?.schedule.summary, 'Every day at 07:30', 'list reflects updated summary');
      return 'after -> daily recomputed';
    });

    await check('PATCH applies a combined title/instruction/dailyTime update', async () => {
      const before = await getSchedules(server.baseUrl, token);
      const seeded = before.schedules.find((item) => item.id === dailyScheduleId);
      assert(seeded, 'created daily schedule');

      const result = await api(
        server.baseUrl,
        'PATCH',
        `/mobile/v1/schedules/${dailyScheduleId}`,
        {
          title: 'Updated morning check',
          instruction: 'New instruction: summarize the release and list open questions.',
          dailyTime: '11:45',
        },
        token,
      );
      assertEqual(result.status, 200, 'combined patch status');
      const row = result.json;
      assertEqual(row.title, 'Updated morning check', 'combined title');
      assertEqual(
        row.instruction,
        'New instruction: summarize the release and list open questions.',
        'combined instruction',
      );
      assertEqual(row.schedule.kind, 'daily', 'combined kind');
      assertEqual(row.schedule.summary, 'Every day at 11:45', 'combined summary');
      assertIso(row.schedule.nextRunAt, 'combined nextRunAt');
      assertEqual(row.createdAt, seeded.createdAt, 'combined createdAt preserved');
      assertEqual(row.status, 'active', 'combined status preserved');

      const list = await getSchedules(server.baseUrl, token);
      const listed = list.schedules.find((item) => item.id === dailyScheduleId);
      assertEqual(listed?.title, 'Updated morning check', 'list combined title');
      assertEqual(listed?.schedule.summary, 'Every day at 11:45', 'list combined summary');
      return 'title+instruction+timing updated';
    });

    await check('PATCH unknown id 404 and no-op/invalid bodies 400', async () => {
      const unknown = await api(
        server.baseUrl,
        'PATCH',
        '/mobile/v1/schedules/sch_does_not_exist',
        { title: 'x' },
        token,
      );
      assertEqual(unknown.status, 404, 'unknown patch status');
      assertEqual(unknown.json?.error?.code, 'not_found', 'unknown patch code');

      const cases = [
        { body: {}, label: 'empty no-op' },
        { body: { title: '   ' }, label: 'empty title' },
        { body: { instruction: '   ' }, label: 'empty instruction' },
        { body: { afterSeconds: 5, dailyTime: '09:00' }, label: 'two timings' },
        { body: { afterSeconds: 0 }, label: 'afterSeconds out of range' },
        { body: { dailyTime: '24:01' }, label: 'bad dailyTime' },
        { body: { at: new Date(Date.now() - 60_000).toISOString() }, label: 'past at' },
        { body: { timeZone: 'UTC' }, label: 'timeZone only' },
      ];
      for (const testCase of cases) {
        const result = await api(
          server.baseUrl,
          'PATCH',
          '/mobile/v1/schedules/sch_seed_daily',
          testCase.body,
          token,
        );
        assertEqual(result.status, 400, `${testCase.label} status`);
        assertEqual(result.json?.error?.code, 'invalid_request', `${testCase.label} code`);
      }
      return `unknown 404; ${cases.length} invalid bodies -> 400`;
    });

    await check('DELETE /schedules/{id} removes created rows with {ok:true,deleted:true}', async () => {
      for (const id of [afterScheduleId, dailyScheduleId]) {
        const deleted = await api(server.baseUrl, 'DELETE', `/mobile/v1/schedules/${id}`, undefined, token);
        assertEqual(deleted.status, 200, `delete ${id} status`);
        assertEqual(deleted.json?.ok, true, `delete ${id} ok`);
        assertEqual(deleted.json?.deleted, true, `delete ${id} deleted`);
      }
      const list = await getSchedules(server.baseUrl, token);
      for (const id of [afterScheduleId, dailyScheduleId]) {
        assert(!list.schedules.some((item) => item.id === id), `${id} removed from list`);
      }
      return 'both created schedules removed';
    });

    await check('DELETE an unknown schedule id answers 404', async () => {
      const result = await api(server.baseUrl, 'DELETE', '/mobile/v1/schedules/sch_does_not_exist', undefined, token);
      assertEqual(result.status, 404, 'unknown delete status');
      assertEqual(result.json?.error?.code, 'not_found', 'unknown delete code');
      return 'unknown -> 404';
    });

    await check('invalid schedule create bodies answer 400 and unknown sessions 404', async () => {
      const cases = [
        { body: { instruction: 'x', afterSeconds: 5 }, label: 'missing title' },
        { body: { title: 'x', afterSeconds: 5 }, label: 'missing instruction' },
        { body: { title: 'x', instruction: 'y' }, label: 'missing timing' },
        {
          body: { title: 'x', instruction: 'y', afterSeconds: 5, dailyTime: '09:00' },
          label: 'two timings',
        },
        { body: { title: 'x', instruction: 'y', afterSeconds: 0 }, label: 'afterSeconds out of range' },
        { body: { title: 'x', instruction: 'y', everySeconds: 30 }, label: 'everySeconds out of range' },
        { body: { title: 'x', instruction: 'y', dailyTime: '25:00' }, label: 'bad dailyTime' },
        { body: { title: 'x', instruction: 'y', at: 'not-a-date' }, label: 'bad at' },
      ];
      for (const testCase of cases) {
        const result = await api(
          server.baseUrl,
          'POST',
          '/mobile/v1/sessions/s_release/schedules',
          testCase.body,
          token,
        );
        assertEqual(result.status, 400, `${testCase.label} status`);
        assertEqual(result.json?.error?.code, 'invalid_request', `${testCase.label} code`);
      }
      const unknownSession = await api(
        server.baseUrl,
        'POST',
        '/mobile/v1/sessions/s_nope/schedules',
        { title: 'x', instruction: 'y', afterSeconds: 5 },
        token,
      );
      assertEqual(unknownSession.status, 404, 'unknown session status');
      assertEqual(unknownSession.json?.error?.code, 'not_found', 'unknown session code');
      return `${cases.length} invalid bodies + unknown session`;
    });

    await check('schedules without a bearer token answer 401 unauthorized', async () => {
      const result = await api(server.baseUrl, 'GET', '/mobile/v1/schedules');
      assertEqual(result.status, 401, 'unauthorized status');
      assertEqual(result.json?.error?.code, 'unauthorized', 'unauthorized code');
      return 'no token -> 401';
    });
  } finally {
    await server.stop();
  }

  console.log('');
  console.log(`[schedules-test] ${passCount} passed, ${failCount} failed`);
  if (failCount > 0) {
    console.log('[schedules-test] failures:');
    for (const failure of failures) console.log(`  - ${failure}`);
    process.exitCode = 1;
  } else {
    console.log('[schedules-test] OK');
  }
}

main().catch((error) => {
  console.error('[schedules-test] fatal:', error);
  process.exitCode = 1;
});
