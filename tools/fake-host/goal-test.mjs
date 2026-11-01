#!/usr/bin/env node
/**
 * tools/fake-host/goal-test.mjs
 *
 * Self-contained test for the fake host's goal endpoints + SSE goal.changed
 * frames. It starts tools/fake-host/server.mjs on a random loopback port,
 * exercises the endpoints, and stops the server again. No dependencies.
 *
 *   node tools/fake-host/goal-test.mjs
 *
 * Env overrides (optional): FAKE_TOKEN (default dev-token).
 * Exits 0 when every check passes, 1 otherwise.
 */
import http from 'node:http';
import net from 'node:net';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SERVER = path.join(path.dirname(fileURLToPath(import.meta.url)), 'server.mjs');
const NODE = process.execPath;
const TOKEN = process.env.FAKE_TOKEN || 'dev-token';

let baseUrl = '';
let child = null;
let serverLog = '';

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

async function startServer() {
  const port = await freePort();
  baseUrl = `http://127.0.0.1:${port}`;
  child = spawn(NODE, [SERVER], {
    cwd: path.dirname(SERVER),
    env: {
      ...process.env,
      PORT: String(port),
      FAKE_BIND: '127.0.0.1',
      FAKE_QUIET: '1',
      FAKE_HEARTBEAT_MS: '5000',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });
  child.stdout.on('data', (chunk) => {
    serverLog += chunk.toString();
  });
  child.stderr.on('data', (chunk) => {
    serverLog += chunk.toString();
  });
  for (let attempt = 0; attempt < 100; attempt += 1) {
    try {
      const response = await fetch(`${baseUrl}/mobile/v1/health`);
      if (response.ok) return;
    } catch {
      // server not listening yet
    }
    await sleep(100);
  }
  throw new Error(`fake host did not start within 10s. Log:\n${serverLog}`);
}

async function stopServer() {
  if (!child) return;
  const exited = new Promise((resolve) => child.once('exit', resolve));
  child.kill('SIGTERM');
  await Promise.race([exited, sleep(2000)]);
  if (child.exitCode === null) child.kill('SIGKILL');
}

async function api(method, requestPath, body, token = TOKEN) {
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

function connectSse(token = TOKEN) {
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
              const timer = setTimeout(() => {
                rejectFrame(new Error(`timed out after ${timeoutMs}ms waiting for an SSE frame`));
              }, timeoutMs);
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

/** Fetch GET /sessions and return the SessionSummary for one session id. */
async function getSessionSummary(sessionId) {
  const result = await api('GET', '/mobile/v1/sessions');
  assertEqual(result.status, 200, 'GET /sessions status');
  const list = result.json?.sessions;
  assert(Array.isArray(list), 'GET /sessions returns a sessions array');
  const summary = list.find((item) => item.id === sessionId);
  assert(summary, `session ${sessionId} missing from GET /sessions`);
  return summary;
}

/** Envelope helper: fake-host frames carry the full envelope on `data:`. */
function envelope(x) {
  return x?.data ?? null;
}

async function main() {
  console.log('[goal-test] starting fake host on a random loopback port...');
  await startServer();
  console.log(`[goal-test] fake host ready at ${baseUrl}`);

  let sse = null;
  try {
    sse = await connectSse();
    console.log('[goal-test] SSE connected');

    await check('GET /sessions exposes the seeded paused goal summary and null for goalless sessions', async () => {
      const pausedSummary = await getSessionSummary('s_android');
      assert(pausedSummary.goal, 's_android goal summary must be present');
      assertEqual(pausedSummary.goal.phase, 'paused', 'seeded phase');
      assertEqual(pausedSummary.goal.roundsStarted, 3, 'seeded roundsStarted');
      assertEqual(pausedSummary.goal.maxGoalRounds, 8, 'seeded maxGoalRounds');
      assertEqual(
        pausedSummary.goal.objective,
        'Make the device stream recover cleanly after screen rotation',
        'seeded objective',
      );
      assertEqual(
        Object.keys(pausedSummary.goal).sort().join(','),
        'maxGoalRounds,objective,phase,roundsStarted',
        'goal summary carries exactly phase/roundsStarted/maxGoalRounds/objective',
      );

      const noGoalSummary = await getSessionSummary('s_economy');
      assertEqual(noGoalSummary.goal, null, 'goalless session goal summary');
      return 's_android paused {8,3}; s_economy null';
    });

    await check('GET /sessions exposes the multi-session goal fixtures (active/paused/complete/blocked)', async () => {
      const expected = [
        ['s_android', 'paused', 3, 8, 'Make the device stream recover cleanly after screen rotation'],
        ['s_search', 'active', 7, 20, 'Make session search respect renames without a restart'],
        ['s_worktree', 'complete', 15, 15, 'Clean up stale worktrees and keep the dirty branch'],
        ['s_notes', 'blocked', 4, 10, 'Draft the v0.4.7 release notes'],
      ];
      for (const [id, phase, roundsStarted, maxGoalRounds, objective] of expected) {
        const summary = await getSessionSummary(id);
        assert(summary.goal, `${id} goal summary must be present`);
        assertEqual(summary.goal.phase, phase, `${id} phase`);
        assertEqual(summary.goal.roundsStarted, roundsStarted, `${id} roundsStarted`);
        assertEqual(summary.goal.maxGoalRounds, maxGoalRounds, `${id} maxGoalRounds`);
        assertEqual(summary.goal.objective, objective, `${id} objective`);
        assertEqual(
          Object.keys(summary.goal).sort().join(','),
          'maxGoalRounds,objective,phase,roundsStarted',
          `${id} goal summary shape`,
        );
      }
      for (const id of ['s_release', 's_economy']) {
        const summary = await getSessionSummary(id);
        assertEqual(summary.goal, null, `${id} must stay goalless`);
      }
      return '4 seeded phases (active/paused/complete/blocked) + 2 goalless';
    });

    await check('GET goal on a session without a goal returns {supported,live,goal:null}', async () => {
      const result = await api('GET', '/mobile/v1/sessions/s_release/goal');
      assertEqual(result.status, 200, 'HTTP status');
      assertEqual(result.json?.supported, true, 'supported');
      assertEqual(result.json?.live, true, 'live');
      assertEqual(result.json?.goal, null, 'goal');
      return 's_release has no goal';
    });

    await check('GET goal on the seeded paused session exposes the mapped view', async () => {
      const result = await api('GET', '/mobile/v1/sessions/s_android/goal');
      assertEqual(result.status, 200, 'HTTP status');
      assertEqual(result.json?.supported, true, 'supported');
      assertEqual(result.json?.live, true, 'live');
      const goal = result.json?.goal;
      assert(goal && typeof goal === 'object', 'goal must be an object');
      assertMatch(goal.id, /^goal_/, 'goal.id');
      assertEqual(goal.phase, 'paused', 'goal.phase');
      assertEqual(goal.activation, 'disarmed', 'goal.activation');
      assertEqual(goal.maxGoalRounds, 8, 'goal.maxGoalRounds');
      assertEqual(goal.roundsStarted, 3, 'goal.roundsStarted');
      assertEqual(goal.revision, 2, 'goal.revision');
      assert(Number.isFinite(Date.parse(goal.createdAt)), 'createdAt must be ISO');
      assert(Number.isFinite(Date.parse(goal.updatedAt)), 'updatedAt must be ISO');
      assert(!('blockedReason' in goal), 'paused goal has no blockedReason');
      return `phase=${goal.phase} revision=${goal.revision}`;
    });

    const createdGoal = await (async () => {
      let goal = null;
      await check('POST goal creates an active/armed goal and emits SSE goal.changed(created)', async () => {
        const result = await api('POST', '/mobile/v1/sessions/s_release/goal', {
          objective: '  Ship fake host goal coverage  ',
          maxGoalRounds: 5,
        });
        assertEqual(result.status, 200, 'HTTP status');
        const created = result.json?.goal;
        assert(created && typeof created === 'object', 'goal must be an object');
        assertMatch(created.id, /^goal_/, 'goal.id');
        assertEqual(created.objective, 'Ship fake host goal coverage', 'objective is trimmed');
        assertEqual(created.phase, 'active', 'phase');
        assertEqual(created.activation, 'armed', 'activation');
        assertEqual(created.maxGoalRounds, 5, 'maxGoalRounds');
        assertEqual(created.roundsStarted, 0, 'roundsStarted');
        assertEqual(created.revision, 1, 'revision');
        assertEqual(result.json?.supported, true, 'supported');
        assertEqual(result.json?.live, true, 'live');
        goal = created;

        const frame = await sse.waitFor(
          (item) => item.event === 'goal.changed' && item.data?.data?.operation === 'created',
        );
        const payload = frame.data.data;
        assertEqual(payload.sessionId, 's_release', 'SSE sessionId');
        assertEqual(payload.goal.id, created.id, 'SSE goal id');
        assertEqual(payload.goal.phase, 'active', 'SSE goal phase');
        return `goal=${created.id} revision=${created.revision}`;
      });
      return goal;
    })();

    await check('POST goal is rejected while a non-complete goal exists', async () => {
      const result = await api('POST', '/mobile/v1/sessions/s_release/goal', { objective: 'another goal' });
      assertEqual(result.status, 400, 'HTTP status');
      assertEqual(result.json?.error?.code, 'invalid_request', 'error.code');
      assertMatch(result.json?.error?.message, /already exists/i, 'error.message');
      return result.json.error.message;
    });

    await check('POST goal/pause pauses the active goal (revision+1) and emits goal.changed(paused)', async () => {
      const result = await api('POST', '/mobile/v1/sessions/s_release/goal/pause');
      assertEqual(result.status, 200, 'HTTP status');
      const goal = result.json?.goal;
      assertEqual(goal.phase, 'paused', 'phase');
      assertEqual(goal.activation, 'disarmed', 'activation');
      assertEqual(goal.revision, 2, 'revision');
      const frame = await sse.waitFor(
        (item) => item.event === 'goal.changed' && item.data?.data?.operation === 'paused',
      );
      const payload = frame.data.data;
      assertEqual(payload.goal.id, createdGoal.id, 'SSE goal id');
      assertEqual(payload.goal.revision, 2, 'SSE revision');
      return `revision=${goal.revision}`;
    });

    await check('POST goal/resume re-arms a paused goal (revision+1) and emits goal.changed(resumed)', async () => {
      const result = await api('POST', '/mobile/v1/sessions/s_release/goal/resume');
      assertEqual(result.status, 200, 'HTTP status');
      const goal = result.json?.goal;
      assertEqual(goal.phase, 'active', 'phase');
      assertEqual(goal.activation, 'armed', 'activation');
      assertEqual(goal.revision, 3, 'revision');
      const frame = await sse.waitFor(
        (item) => item.event === 'goal.changed' && item.data?.data?.operation === 'resumed',
      );
      const payload = frame.data.data;
      assertEqual(payload.goal.id, createdGoal.id, 'SSE goal id');
      assertEqual(payload.goal.revision, 3, 'SSE revision');
      return `revision=${goal.revision}`;
    });

    await check('POST goal/pause on the seeded paused session is rejected', async () => {
      const result = await api('POST', '/mobile/v1/sessions/s_android/goal/pause');
      assertEqual(result.status, 400, 'HTTP status');
      assertEqual(result.json?.error?.code, 'invalid_request', 'error.code');
      assertMatch(result.json?.error?.message, /not active/i, 'error.message');
      return result.json.error.message;
    });

    await check('POST goal/resume on the seeded paused session re-arms it', async () => {
      const result = await api('POST', '/mobile/v1/sessions/s_android/goal/resume');
      assertEqual(result.status, 200, 'HTTP status');
      assertEqual(result.json?.goal?.phase, 'active', 'phase');
      assertEqual(result.json?.goal?.activation, 'armed', 'activation');
      assertEqual(result.json?.goal?.revision, 3, 'revision');
      return `revision=${result.json.goal.revision}`;
    });

    await check('POST goal/resume without a goal returns 404 not_found', async () => {
      const result = await api('POST', '/mobile/v1/sessions/s_economy/goal/resume');
      assertEqual(result.status, 404, 'HTTP status');
      assertEqual(result.json?.error?.code, 'not_found', 'error.code');
      return result.json.error.message;
    });

    await check('goal endpoints validate input and unknown sessions', async () => {
      const emptyObjective = await api('POST', '/mobile/v1/sessions/s_economy/goal', { objective: '   ' });
      assertEqual(emptyObjective.status, 400, 'empty objective status');
      assertEqual(emptyObjective.json?.error?.code, 'invalid_request', 'empty objective code');

      const badRounds = await api('POST', '/mobile/v1/sessions/s_economy/goal', { objective: 'x', maxGoalRounds: 0 });
      assertEqual(badRounds.status, 400, 'maxGoalRounds=0 status');
      assertEqual(badRounds.json?.error?.code, 'invalid_request', 'maxGoalRounds=0 code');

      const unknownSession = await api('GET', '/mobile/v1/sessions/nope/goal');
      assertEqual(unknownSession.status, 404, 'unknown session status');
      assertEqual(unknownSession.json?.error?.code, 'not_found', 'unknown session code');
      return 'rejected empty objective, zero rounds, unknown session';
    });

    await check('a newly created session starts goalless and can get a goal', async () => {
      const createdSession = await api('POST', '/mobile/v1/sessions', { workspaceId: 'ws_1' });
      assertEqual(createdSession.status, 200, 'session create status');
      const sessionId = createdSession.json?.session?.id;
      assert(sessionId, 'new session id');

      const before = await api('GET', `/mobile/v1/sessions/${sessionId}/goal`);
      assertEqual(before.status, 200, 'GET new session goal status');
      assertEqual(before.json?.goal, null, 'new session goal');

      const createdGoalResult = await api('POST', `/mobile/v1/sessions/${sessionId}/goal`, {
        objective: 'Verify goals on fresh sessions',
      });
      assertEqual(createdGoalResult.status, 200, 'create goal status');
      assertEqual(createdGoalResult.json?.goal?.phase, 'active', 'created phase');
      assertEqual(createdGoalResult.json?.goal?.maxGoalRounds, 0, 'default maxGoalRounds');
      return `session=${sessionId}`;
    });

    await check('GET goal reflects the last mutation', async () => {
      const result = await api('GET', '/mobile/v1/sessions/s_release/goal');
      assertEqual(result.status, 200, 'HTTP status');
      assertEqual(result.json?.goal?.id, createdGoal.id, 'goal id');
      assertEqual(result.json?.goal?.phase, 'active', 'phase');
      assertEqual(result.json?.goal?.revision, 3, 'revision');
      return `revision=${result.json.goal.revision}`;
    });

    await check('POST goal/edit updates fields (revision+1, phase/activation unchanged) and emits goal.changed(edit)', async () => {
      const result = await api('POST', '/mobile/v1/sessions/s_release/goal/edit', {
        objective: '  Ship fake host goal edit + clear  ',
        maxGoalRounds: 7,
      });
      assertEqual(result.status, 200, 'HTTP status');
      const goal = result.json?.goal;
      assertEqual(goal.id, createdGoal.id, 'goal id unchanged');
      assertEqual(goal.objective, 'Ship fake host goal edit + clear', 'objective trimmed/updated');
      assertEqual(goal.maxGoalRounds, 7, 'maxGoalRounds');
      assertEqual(goal.revision, 4, 'revision');
      assertEqual(goal.phase, 'active', 'phase unchanged');
      assertEqual(goal.activation, 'armed', 'activation unchanged');
      const frame = await sse.waitFor(
        (item) => item.event === 'goal.changed' && item.data?.data?.operation === 'edit',
      );
      const payload = frame.data.data;
      assertEqual(payload.sessionId, 's_release', 'SSE sessionId');
      assertEqual(payload.goal.id, createdGoal.id, 'SSE goal id');
      assertEqual(payload.goal.revision, 4, 'SSE revision');
      assertEqual(payload.goal.objective, 'Ship fake host goal edit + clear', 'SSE objective');
      return `revision=${goal.revision}`;
    });

    await check('POST goal/edit with only maxGoalRounds keeps the objective', async () => {
      const result = await api('POST', '/mobile/v1/sessions/s_release/goal/edit', { maxGoalRounds: 9 });
      assertEqual(result.status, 200, 'HTTP status');
      const goal = result.json?.goal;
      assertEqual(goal.objective, 'Ship fake host goal edit + clear', 'objective unchanged');
      assertEqual(goal.maxGoalRounds, 9, 'maxGoalRounds');
      assertEqual(goal.revision, 5, 'revision');
      return `revision=${goal.revision}`;
    });

    await check('POST goal/edit validates fields and requires a current goal', async () => {
      const noFields = await api('POST', '/mobile/v1/sessions/s_release/goal/edit', {});
      assertEqual(noFields.status, 400, 'no fields status');
      assertEqual(noFields.json?.error?.code, 'invalid_request', 'no fields code');
      assertMatch(noFields.json?.error?.message, /objective|maxGoalRounds/i, 'no fields message');

      const blankObjective = await api('POST', '/mobile/v1/sessions/s_release/goal/edit', { objective: '   ' });
      assertEqual(blankObjective.status, 400, 'blank objective status');
      assertEqual(blankObjective.json?.error?.code, 'invalid_request', 'blank objective code');

      const badRounds = await api('POST', '/mobile/v1/sessions/s_release/goal/edit', { maxGoalRounds: 0 });
      assertEqual(badRounds.status, 400, 'bad rounds status');
      assertEqual(badRounds.json?.error?.code, 'invalid_request', 'bad rounds code');

      const noGoal = await api('POST', '/mobile/v1/sessions/s_economy/goal/edit', { objective: 'x' });
      assertEqual(noGoal.status, 404, 'no goal status');
      assertEqual(noGoal.json?.error?.code, 'not_found', 'no goal code');

      const unknownSession = await api('POST', '/mobile/v1/sessions/nope/goal/edit', { objective: 'x' });
      assertEqual(unknownSession.status, 404, 'unknown session status');
      assertEqual(unknownSession.json?.error?.code, 'not_found', 'unknown session code');
      return 'rejected empty body, blank objective, zero rounds, no-goal and unknown sessions';
    });

    await check('POST goal/edit preserves a paused phase/activation', async () => {
      const paused = await api('POST', '/mobile/v1/sessions/s_release/goal/pause');
      assertEqual(paused.status, 200, 'pause status');
      assertEqual(paused.json?.goal?.phase, 'paused', 'paused phase');
      assertEqual(paused.json?.goal?.revision, 6, 'paused revision');

      const edited = await api('POST', '/mobile/v1/sessions/s_release/goal/edit', { objective: 'Edit while paused' });
      assertEqual(edited.status, 200, 'edit status');
      const goal = edited.json?.goal;
      assertEqual(goal.objective, 'Edit while paused', 'objective');
      assertEqual(goal.phase, 'paused', 'phase preserved');
      assertEqual(goal.activation, 'disarmed', 'activation preserved');
      assertEqual(goal.revision, 7, 'revision');
      return `phase=${goal.phase} revision=${goal.revision}`;
    });

    await check('POST goal/clear removes the goal and emits goal.changed(clear) with goal null', async () => {
      const result = await api('POST', '/mobile/v1/sessions/s_release/goal/clear');
      assertEqual(result.status, 200, 'HTTP status');
      assertEqual(result.json?.supported, true, 'supported');
      assertEqual(result.json?.live, true, 'live');
      assertEqual(result.json?.goal, null, 'goal');

      const frame = await sse.waitFor(
        (item) => item.event === 'goal.changed' && item.data?.data?.operation === 'clear',
      );
      const payload = frame.data.data;
      assertEqual(payload.sessionId, 's_release', 'SSE sessionId');
      assertEqual(payload.goal, null, 'SSE goal is null');

      const after = await api('GET', '/mobile/v1/sessions/s_release/goal');
      assertEqual(after.status, 200, 'GET after clear status');
      assertEqual(after.json?.goal, null, 'goal after clear');

      const again = await api('POST', '/mobile/v1/sessions/s_release/goal/clear');
      assertEqual(again.status, 404, 'second clear status');
      assertEqual(again.json?.error?.code, 'not_found', 'second clear code');
      return 'cleared; second clear returned 404';
    });

    await check('a goal can be created again after a clear (revision resets)', async () => {
      const result = await api('POST', '/mobile/v1/sessions/s_release/goal', { objective: 'Goal after clear' });
      assertEqual(result.status, 200, 'HTTP status');
      const goal = result.json?.goal;
      assert(goal, 'new goal');
      assert(goal.id !== createdGoal.id, 'new goal id');
      assertEqual(goal.objective, 'Goal after clear', 'objective');
      assertEqual(goal.phase, 'active', 'phase');
      assertEqual(goal.activation, 'armed', 'activation');
      assertEqual(goal.revision, 1, 'revision reset');
      return `goal=${goal.id} revision=${goal.revision}`;
    });
    let listSessionId = '';
    await check('GET /sessions shows goal:null for a fresh session', async () => {
      const created = await api('POST', '/mobile/v1/sessions', { workspaceId: 'ws_1' });
      assertEqual(created.status, 200, 'create session status');
      listSessionId = created.json?.session?.id;
      assert(listSessionId, 'fresh session id');
      const summary = await getSessionSummary(listSessionId);
      assertEqual(summary.goal, null, 'fresh session goal summary');
      return `session=${listSessionId}`;
    });

    await check('GET /sessions reflects goal create and edit (phase/rounds/max)', async () => {
      const created = await api('POST', `/mobile/v1/sessions/${listSessionId}/goal`, {
        objective: 'List summary goal',
        maxGoalRounds: 5,
      });
      assertEqual(created.status, 200, 'create goal status');
      let summary = await getSessionSummary(listSessionId);
      assertEqual(summary.goal?.phase, 'active', 'after create phase');
      assertEqual(summary.goal?.roundsStarted, 0, 'after create roundsStarted');
      assertEqual(summary.goal?.maxGoalRounds, 5, 'after create maxGoalRounds');

      const edited = await api('POST', `/mobile/v1/sessions/${listSessionId}/goal/edit`, { maxGoalRounds: 7 });
      assertEqual(edited.status, 200, 'edit goal status');
      summary = await getSessionSummary(listSessionId);
      assertEqual(summary.goal?.phase, 'active', 'after edit phase');
      assertEqual(summary.goal?.maxGoalRounds, 7, 'after edit maxGoalRounds');
      assertEqual(summary.goal?.roundsStarted, 0, 'after edit roundsStarted');
      return 'create -> {active,0,5}; edit -> maxGoalRounds 7';
    });

    await check('GET /sessions reflects goal objective on create and edit', async () => {
      let summary = await getSessionSummary(listSessionId);
      assertEqual(summary.goal?.objective, 'List summary goal', 'objective after create');

      const edited = await api('POST', `/mobile/v1/sessions/${listSessionId}/goal/edit`, {
        objective: '  Updated list summary objective  ',
      });
      assertEqual(edited.status, 200, 'edit objective status');
      assertEqual(
        edited.json?.goal?.objective,
        'Updated list summary objective',
        'edit response objective is trimmed',
      );

      summary = await getSessionSummary(listSessionId);
      assertEqual(summary.goal?.objective, 'Updated list summary objective', 'objective after edit');
      assertEqual(summary.goal?.phase, 'active', 'objective edit keeps phase');
      assertEqual(summary.goal?.maxGoalRounds, 7, 'objective edit keeps maxGoalRounds');
      return 'create/edit objective reflected in GET /sessions';
    });

    await check('GET /sessions reflects goal pause and resume phases', async () => {
      const paused = await api('POST', `/mobile/v1/sessions/${listSessionId}/goal/pause`);
      assertEqual(paused.status, 200, 'pause status');
      let summary = await getSessionSummary(listSessionId);
      assertEqual(summary.goal?.phase, 'paused', 'after pause phase');

      const resumed = await api('POST', `/mobile/v1/sessions/${listSessionId}/goal/resume`);
      assertEqual(resumed.status, 200, 'resume status');
      summary = await getSessionSummary(listSessionId);
      assertEqual(summary.goal?.phase, 'active', 'after resume phase');
      return 'pause -> paused; resume -> active';
    });

    await check('GET /sessions reflects goal clear as goal:null', async () => {
      const cleared = await api('POST', `/mobile/v1/sessions/${listSessionId}/goal/clear`);
      assertEqual(cleared.status, 200, 'clear status');
      const summary = await getSessionSummary(listSessionId);
      assertEqual(summary.goal, null, 'after clear goal summary');
      const detail = await api('GET', `/mobile/v1/sessions/${listSessionId}/goal`);
      assertEqual(detail.status, 200, 'goal endpoint after clear status');
      assertEqual(detail.json?.goal, null, 'goal endpoint after clear');
      return 'clear -> goal:null';
    });

    await check('seeded active goal mutations reflect in the session-list summary', async () => {
      const paused = await api('POST', '/mobile/v1/sessions/s_search/goal/pause');
      assertEqual(paused.status, 200, 'pause status');
      assertEqual(paused.json?.goal?.revision, 5, 'pause revision');
      let summary = await getSessionSummary('s_search');
      assertEqual(summary.goal?.phase, 'paused', 'after pause phase');
      assertEqual(summary.goal?.roundsStarted, 7, 'after pause roundsStarted');

      const edited = await api('POST', '/mobile/v1/sessions/s_search/goal/edit', { maxGoalRounds: 25 });
      assertEqual(edited.status, 200, 'edit status');
      assertEqual(edited.json?.goal?.revision, 6, 'edit revision');
      summary = await getSessionSummary('s_search');
      assertEqual(summary.goal?.phase, 'paused', 'after edit phase preserved');
      assertEqual(summary.goal?.maxGoalRounds, 25, 'after edit maxGoalRounds');

      const resumed = await api('POST', '/mobile/v1/sessions/s_search/goal/resume');
      assertEqual(resumed.status, 200, 'resume status');
      assertEqual(resumed.json?.goal?.revision, 7, 'resume revision');
      summary = await getSessionSummary('s_search');
      assertEqual(summary.goal?.phase, 'active', 'after resume phase');
      assertEqual(summary.goal?.maxGoalRounds, 25, 'after resume maxGoalRounds');
      return 'pause/edit/resume reflected in GET /sessions';
    });

    await check('seeded complete goal cannot be resumed (terminal)', async () => {
      const completeSummary = await getSessionSummary('s_worktree');
      assertEqual(completeSummary.goal?.phase, 'complete', 'seeded complete phase');

      const resume = await api('POST', '/mobile/v1/sessions/s_worktree/goal/resume');
      assertEqual(resume.status, 400, 'complete resume status');
      assertEqual(resume.json?.error?.code, 'invalid_request', 'complete resume code');
      assertMatch(resume.json?.error?.message, /completed/i, 'complete resume message');
      return 'complete resume -> 400';
    });

    await check('POST goal/complete completes the seeded active goal, emits SSE and updates the summary', async () => {
      const before = await api('GET', '/mobile/v1/sessions/s_search/goal');
      assertEqual(before.status, 200, 'goal status');
      const goalBefore = before.json?.goal;
      assert(goalBefore, 'seeded active goal');
      assertEqual(goalBefore.phase, 'active', 'before phase');

      const completed = await api('POST', '/mobile/v1/sessions/s_search/goal/complete', {
        id: goalBefore.id,
        revision: goalBefore.revision,
      });
      assertEqual(completed.status, 200, 'complete status');
      const goalAfter = completed.json?.goal;
      assertEqual(goalAfter.id, goalBefore.id, 'goal id kept');
      assertEqual(goalAfter.phase, 'complete', 'completed phase');
      assertEqual(goalAfter.activation, 'disarmed', 'completed activation');
      assertEqual(goalAfter.revision, goalBefore.revision + 1, 'completed revision');

      const frame = await sse.waitFor(
        (item) => item.event === 'goal.changed' && item.data?.data?.operation === 'complete',
      );
      const payload = frame.data.data;
      assertEqual(payload.sessionId, 's_search', 'SSE sessionId');
      assertEqual(payload.goal.id, goalBefore.id, 'SSE goal id');
      assertEqual(payload.goal.phase, 'complete', 'SSE phase');

      const summary = await getSessionSummary('s_search');
      assertEqual(summary.goal?.phase, 'complete', 'summary phase');
      assertEqual(summary.goal?.roundsStarted, 7, 'summary roundsStarted');
      assertEqual(summary.goal?.maxGoalRounds, 25, 'summary maxGoalRounds');
      assertEqual(
        summary.goal?.objective,
        'Make session search respect renames without a restart',
        'summary objective',
      );
      return `revision ${goalBefore.revision} -> ${goalAfter.revision}`;
    });

    await check('POST goal/complete from paused guards stale revision, completes, then rejects a second complete', async () => {
      const createdSession = await api('POST', '/mobile/v1/sessions', { workspaceId: 'ws_1' });
      assertEqual(createdSession.status, 200, 'session create status');
      const sessionId = createdSession.json?.session?.id;
      assert(sessionId, 'paused-complete session id');

      const created = await api('POST', `/mobile/v1/sessions/${sessionId}/goal`, {
        objective: 'Complete from paused',
        maxGoalRounds: 4,
      });
      assertEqual(created.status, 200, 'create goal status');
      const paused = await api('POST', `/mobile/v1/sessions/${sessionId}/goal/pause`);
      assertEqual(paused.status, 200, 'pause status');
      const goal = paused.json?.goal;
      assertEqual(goal.phase, 'paused', 'paused phase');
      assertEqual(goal.revision, 2, 'paused revision');

      const stale = await api('POST', `/mobile/v1/sessions/${sessionId}/goal/complete`, {
        id: goal.id,
        revision: goal.revision - 1,
      });
      assertEqual(stale.status, 400, 'stale revision status');
      assertEqual(stale.json?.error?.code, 'invalid_request', 'stale revision code');
      assertMatch(stale.json?.error?.message, /revision|stale/i, 'stale revision message');

      const completed = await api('POST', `/mobile/v1/sessions/${sessionId}/goal/complete`, {
        id: goal.id,
        revision: goal.revision,
      });
      assertEqual(completed.status, 200, 'complete status');
      assertEqual(completed.json?.goal?.phase, 'complete', 'completed phase');
      assertEqual(completed.json?.goal?.activation, 'disarmed', 'completed activation');
      assertEqual(completed.json?.goal?.revision, 3, 'completed revision');
      const summary = await getSessionSummary(sessionId);
      assertEqual(summary.goal?.phase, 'complete', 'paused-complete summary phase');

      const again = await api('POST', `/mobile/v1/sessions/${sessionId}/goal/complete`, {});
      assertEqual(again.status, 400, 'second complete status');
      assertEqual(again.json?.error?.code, 'invalid_request', 'second complete code');
      assertMatch(again.json?.error?.message, /already complete/i, 'second complete message');
      return 'paused -> complete with stale-revision guard';
    });

    await check('seeded complete goal rejects complete; clearing it after completion yields goal:null', async () => {
      const detail = await api('GET', '/mobile/v1/sessions/s_worktree/goal');
      assertEqual(detail.status, 200, 'goal detail status');
      const goal = detail.json?.goal;
      assertEqual(goal?.phase, 'complete', 'seeded complete phase');

      const rejected = await api('POST', '/mobile/v1/sessions/s_worktree/goal/complete', {
        id: goal.id,
        revision: goal.revision,
      });
      assertEqual(rejected.status, 400, 'complete again status');
      assertEqual(rejected.json?.error?.code, 'invalid_request', 'complete again code');
      assertMatch(rejected.json?.error?.message, /already complete/i, 'complete again message');

      const cleared = await api('POST', '/mobile/v1/sessions/s_worktree/goal/clear');
      assertEqual(cleared.status, 200, 'clear status');
      assertEqual(cleared.json?.goal, null, 'clear goal');
      const after = await getSessionSummary('s_worktree');
      assertEqual(after.goal, null, 'after clear list summary');
      return 'complete rejected; clear -> null';
    });
  } finally {
    sse?.close();
    await stopServer();
  }

  console.log('');
  console.log(`[goal-test] ${passCount} passed, ${failCount} failed`);
  if (failCount > 0) {
    console.log('[goal-test] failures:');
    for (const failure of failures) console.log(`  - ${failure}`);
    process.exitCode = 1;
  } else {
    console.log('[goal-test] OK');
  }
}

main().catch((error) => {
  console.error('[goal-test] fatal:', error);
  process.exitCode = 1;
});
