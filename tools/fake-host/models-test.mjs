#!/usr/bin/env node
/**
 * tools/fake-host/models-test.mjs
 *
 * Self-contained test for the fake host's model catalog + per-session model
 * selection:
 *   - spawns a fake host, pairs via the real endpoints,
 *   - GET /models -> {supported, default:{provider,model,reasoningEffort?},
 *     groups:[{id,name,models:[{id,name,description?,reasoning?:{efforts,
 *     defaultEffort?}}]}], failures:[], truncated:false} with two providers
 *     (DeepSeek reasoning models + Local) and a deepseek-chat default,
 *   - GET /sessions/{id}/model -> {supported,live,selection,source}: seeded
 *     custom selection on s_release, default elsewhere,
 *   - PUT validates provider/model/reasoningEffort and the GET reflects it,
 *   - invalid fields 400, unknown session 404, archived/cold PUT 400,
 *   - no bearer token -> 401.
 *
 *   node tools/fake-host/models-test.mjs
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
const DEFAULT_SELECTION = {
  provider: 'deepseek',
  model: 'deepseek-chat',
  reasoningEffort: 'medium',
};

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

async function getSessionModel(baseUrl, token, sessionId) {
  const result = await api(
    baseUrl,
    'GET',
    `/mobile/v1/sessions/${sessionId}/model`,
    undefined,
    token,
  );
  assertEqual(result.status, 200, 'GET session model status');
  return result.json;
}

function assertSelection(actual, expected, label) {
  assert(actual, `${label}: selection`);
  assertEqual(actual.provider, expected.provider, `${label} provider`);
  assertEqual(actual.model, expected.model, `${label} model`);
  if (expected.reasoningEffort === undefined) {
    assertEqual(actual.reasoningEffort, undefined, `${label} reasoningEffort omitted`);
  } else {
    assertEqual(actual.reasoningEffort, expected.reasoningEffort, `${label} reasoningEffort`);
  }
}

async function main() {
  console.log('[models-test] starting fake host on a random loopback port...');
  const server = await startServer();
  console.log(`[models-test] fake host ready at ${server.baseUrl}`);

  let token = '';
  try {
    await check('pair/new + pair/complete return a device token', async () => {
      token = await pair(server.baseUrl);
      assertEqual(token, PAIR_TOKEN, 'token');
      return 'paired';
    });

    await check('GET /models returns the two-provider catalog, default and efforts', async () => {
      const result = await api(server.baseUrl, 'GET', '/mobile/v1/models', undefined, token);
      assertEqual(result.status, 200, 'catalog status');
      assertEqual(result.json?.supported, true, 'catalog supported');
      assertEqual(result.json?.truncated, false, 'catalog truncated');
      assert(Array.isArray(result.json?.failures), 'failures array');
      assertEqual(result.json.failures.length, 0, 'no failures');
      assertSelection(result.json.default, DEFAULT_SELECTION, 'default');
      assertEqual(result.json.groups.length, 2, 'two provider groups');
      assertEqual(
        result.json.groups.map((group) => group.id).join(','),
        'deepseek,local',
        'group ids',
      );

      const deepseek = result.json.groups.find((group) => group.id === 'deepseek');
      assertEqual(deepseek.name, 'DeepSeek', 'deepseek group name');
      assertEqual(deepseek.models.length, 2, 'two deepseek models');
      const chat = deepseek.models.find((model) => model.id === 'deepseek-chat');
      const reasoner = deepseek.models.find((model) => model.id === 'deepseek-reasoner');
      assert(chat, 'chat model present');
      assert(reasoner, 'reasoner model present');
      assertEqual(chat.reasoning.efforts.map((effort) => effort.id).join(','), 'low,medium,high', 'efforts');
      assertEqual(chat.reasoning.defaultEffort, 'medium', 'chat default effort');
      assertEqual(reasoner.reasoning.defaultEffort, 'high', 'reasoner default effort');

      const local = result.json.groups.find((group) => group.id === 'local');
      assertEqual(local.models.length, 1, 'one local model');
      assertEqual(local.models[0].id, 'local-small', 'local model id');
      assertEqual(local.models[0].reasoning, undefined, 'local model has no reasoning');
      return 'deepseek(2 reasoning models) + local(1)';
    });

    await check('GET session model returns the default selection for an unseeded session', async () => {
      const result = await getSessionModel(server.baseUrl, token, 's_economy');
      assertEqual(result.supported, true, 'supported');
      assertEqual(result.live, true, 'live');
      assertEqual(result.source, 'default', 'source');
      assertSelection(result.selection, DEFAULT_SELECTION, 'default selection');
      return 's_economy -> default';
    });

    await check('GET session model returns the seeded custom selection for s_release', async () => {
      const result = await getSessionModel(server.baseUrl, token, 's_release');
      assertEqual(result.source, 'session', 'source');
      assertSelection(
        result.selection,
        { provider: 'deepseek', model: 'deepseek-reasoner', reasoningEffort: 'high' },
        'custom selection',
      );
      return 's_release -> deepseek-reasoner/high';
    });

    await check('PUT a local model persists and GET reflects it; PUT a reasoning effort too', async () => {
      const localPut = await api(
        server.baseUrl,
        'PUT',
        '/mobile/v1/sessions/s_economy/model',
        { provider: 'local', model: 'local-small' },
        token,
      );
      assertEqual(localPut.status, 200, 'local PUT status');
      assertEqual(localPut.json?.supported, true, 'local PUT supported');
      assertEqual(localPut.json?.live, true, 'local PUT live');
      assertSelection(localPut.json?.selection, { provider: 'local', model: 'local-small' }, 'local PUT');

      let view = await getSessionModel(server.baseUrl, token, 's_economy');
      assertEqual(view.source, 'session', 'source after local PUT');
      assertSelection(view.selection, { provider: 'local', model: 'local-small' }, 'GET after local PUT');

      const reasoningPut = await api(
        server.baseUrl,
        'PUT',
        '/mobile/v1/sessions/s_economy/model',
        { provider: 'deepseek', model: 'deepseek-chat', reasoningEffort: 'low' },
        token,
      );
      assertEqual(reasoningPut.status, 200, 'reasoning PUT status');
      assertSelection(
        reasoningPut.json?.selection,
        { provider: 'deepseek', model: 'deepseek-chat', reasoningEffort: 'low' },
        'reasoning PUT',
      );
      view = await getSessionModel(server.baseUrl, token, 's_economy');
      assertSelection(
        view.selection,
        { provider: 'deepseek', model: 'deepseek-chat', reasoningEffort: 'low' },
        'GET after reasoning PUT',
      );
      return 'local-small -> deepseek-chat/low';
    });

    await check('invalid provider/model/effort bodies answer 400 invalid_request', async () => {
      const cases = [
        { body: {}, label: 'empty body' },
        { body: { provider: 'deepseek' }, label: 'missing model' },
        { body: { model: 'deepseek-chat' }, label: 'missing provider' },
        { body: { provider: '', model: 'deepseek-chat' }, label: 'empty provider' },
        { body: { provider: 42, model: 'deepseek-chat' }, label: 'non-string provider' },
        { body: { provider: 'nope', model: 'deepseek-chat' }, label: 'unknown provider' },
        { body: { provider: 'deepseek', model: 'nope' }, label: 'unknown model' },
        {
          body: { provider: 'deepseek', model: 'deepseek-chat', reasoningEffort: 'extreme' },
          label: 'unknown effort',
        },
        {
          body: { provider: 'local', model: 'local-small', reasoningEffort: 'low' },
          label: 'effort on non-reasoning model',
        },
        {
          body: { provider: 'deepseek', model: 'deepseek-chat', reasoningEffort: 3 },
          label: 'non-string effort',
        },
      ];
      for (const testCase of cases) {
        const result = await api(
          server.baseUrl,
          'PUT',
          '/mobile/v1/sessions/s_android/model',
          testCase.body,
          token,
        );
        assertEqual(result.status, 400, `${testCase.label} status`);
        assertEqual(result.json?.error?.code, 'invalid_request', `${testCase.label} code`);
      }
      return `${cases.length} invalid bodies -> 400`;
    });

    await check('unknown session GET/PUT model answers 404 not_found', async () => {
      for (const method of ['GET', 'PUT']) {
        const result = await api(
          server.baseUrl,
          method,
          '/mobile/v1/sessions/s_does_not_exist/model',
          method === 'PUT' ? { provider: 'deepseek', model: 'deepseek-chat' } : undefined,
          token,
        );
        assertEqual(result.status, 404, `${method} unknown session status`);
        assertEqual(result.json?.error?.code, 'not_found', `${method} unknown session code`);
      }
      return 'GET/PUT -> 404';
    });

    await check('archived (cold) session reports live:false and rejects PUT with 400', async () => {
      const archived = await api(
        server.baseUrl,
        'POST',
        '/mobile/v1/sessions/s_search/archive',
        undefined,
        token,
      );
      assertEqual(archived.status, 200, 'archive status');

      const view = await getSessionModel(server.baseUrl, token, 's_search');
      assertEqual(view.live, false, 'archived live flag');
      assertEqual(view.source, 'default', 'archived default source');

      const put = await api(
        server.baseUrl,
        'PUT',
        '/mobile/v1/sessions/s_search/model',
        { provider: 'deepseek', model: 'deepseek-chat' },
        token,
      );
      assertEqual(put.status, 400, 'cold PUT status');
      assertEqual(put.json?.error?.code, 'invalid_request', 'cold PUT code');
      return 'archived -> live:false; PUT 400';
    });

    await check('model routes without a bearer token answer 401', async () => {
      const catalog = await api(server.baseUrl, 'GET', '/mobile/v1/models');
      assertEqual(catalog.status, 401, 'unauthorized catalog status');
      assertEqual(catalog.json?.error?.code, 'unauthorized', 'unauthorized catalog code');

      const get = await api(server.baseUrl, 'GET', '/mobile/v1/sessions/s_release/model');
      assertEqual(get.status, 401, 'unauthorized GET status');
      const put = await api(server.baseUrl, 'PUT', '/mobile/v1/sessions/s_release/model', {
        provider: 'deepseek',
        model: 'deepseek-chat',
      });
      assertEqual(put.status, 401, 'unauthorized PUT status');
      return 'catalog/GET/PUT -> 401';
    });
  } finally {
    await server.stop();
  }

  console.log('');
  console.log(`[models-test] ${passCount} passed, ${failCount} failed`);
  if (failCount > 0) {
    console.log('[models-test] failures:');
    for (const failure of failures) console.log(`  - ${failure}`);
    process.exitCode = 1;
  } else {
    console.log('[models-test] OK');
  }
}

main().catch((error) => {
  console.error('[models-test] fatal:', error);
  process.exitCode = 1;
});
