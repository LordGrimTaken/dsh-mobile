#!/usr/bin/env node
/**
 * tools/fake-host/smoke.mjs
 *
 * Zero-dependency smoke test for tools/fake-host/server.mjs. It does NOT
 * start the server itself: start the fake host first, then run this script.
 *
 *   & "C:\.dsh\resources\runtime\primary-runtime\dependencies\node\bin\node.exe" `
 *       tools/fake-host/server.mjs            # terminal 1
 *   & "C:\.dsh\resources\runtime\primary-runtime\dependencies\node\bin\node.exe" `
 *       tools/fake-host/smoke.mjs             # terminal 2
 *
 * Environment:
 *   SMOKE_BASE_URL   base URL of a running fake host (default http://127.0.0.1:3543)
 *   SMOKE_TOKEN      bearer token expected from pairing (default dev-token)
 *
 * Exits 0 when every check passes, 1 otherwise. Every check prints PASS/FAIL.
 */

import assert from 'node:assert/strict';

// ---------------------------------------------------------------------------
// CLI / config
// ---------------------------------------------------------------------------

function argValue(name) {
  const inline = process.argv.find((arg) => arg.startsWith(`${name}=`));
  if (inline) return inline.slice(name.length + 1);
  const index = process.argv.indexOf(name);
  if (index >= 0) {
    const next = process.argv[index + 1];
    if (next !== undefined && !next.startsWith('--')) return next;
  }
  return undefined;
}

const BASE_URL = (process.env.SMOKE_BASE_URL || argValue('--base-url') || 'http://127.0.0.1:3543')
  .replace(/\/+$/, '');
const TOKEN = process.env.SMOKE_TOKEN || 'dev-token';
const STEP_TIMEOUT_MS = 15_000;

const ctx = {
  /** @type {string | undefined} */
  pairCode: undefined,
  /** @type {string | undefined} */
  token: undefined,
  /** @type {string | undefined} */
  sessionId: undefined,
  /** @type {string | undefined} */
  newSessionId: undefined,
  /** @type {number} */
  sessionsCount: 0,
};

let passCount = 0;
let failCount = 0;
/** @type {string[]} */
const failures = [];
const startedAt = Date.now();

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function pass(name, detail) {
  passCount += 1;
  console.log(`[PASS] ${name}${detail ? ` — ${detail}` : ''}`);
}

function failure(name, error) {
  failCount += 1;
  const message = error instanceof Error ? error.message : String(error);
  failures.push(`${name}: ${message}`);
  console.log(`[FAIL] ${name} — ${message}`);
}

async function step(name, body) {
  try {
    const detail = await body();
    pass(name, detail);
  } catch (error) {
    failure(name, error);
  }
}

async function waitUntil(name, predicate, timeoutMs = 5_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await sleep(50);
  }
  throw new Error(`Timed out after ${timeoutMs}ms waiting for ${name}`);
}

// ---------------------------------------------------------------------------
// HTTP helper
// ---------------------------------------------------------------------------

/**
 * @param {string} path
 * @param {{method?: string, token?: string, body?: unknown}} [options]
 */
async function request(path, options = {}) {
  /** @type {Record<string, string>} */
  const headers = {};
  if (options.token) headers.Authorization = `Bearer ${options.token}`;
  /** @type {RequestInit} */
  const init = { method: options.method || 'GET', headers };
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(options.body);
  }
  const response = await fetch(`${BASE_URL}${path}`, init);
  const text = await response.text();
  let json;
  try {
    json = text ? JSON.parse(text) : undefined;
  } catch {
    throw new Error(`Non-JSON response for ${init.method} ${path}: ${text.slice(0, 200)}`);
  }
  return { status: response.status, json, headers: response.headers };
}

// ---------------------------------------------------------------------------
// Minimal SSE client (frame parser + awaitable predicates)
// ---------------------------------------------------------------------------

class EventStream {
  /** @param {string} [since] */
  constructor(since) {
    this.since = since;
    /** @type {Array<any>} */
    this.events = [];
    /** @type {Set<any>} */
    this.waiters = new Set();
    /** @type {string | undefined} */
    this.lastId = undefined;
    this.closed = false;
    this.buffer = '';
    this.decoder = new TextDecoder();
  }

  async open() {
    const url = new URL(`${BASE_URL}/mobile/v1/events`);
    if (this.since) url.searchParams.set('since', this.since);
    this.controller = new AbortController();
    this.response = await fetch(url, {
      headers: { Authorization: `Bearer ${TOKEN}`, Accept: 'text/event-stream' },
      signal: this.controller.signal,
    });
    if (this.response.status !== 200) {
      throw new Error(`GET /mobile/v1/events returned HTTP ${this.response.status}`);
    }
    if (!this.response.body) throw new Error('GET /mobile/v1/events has no response body');
    this.reader = this.response.body.getReader();
    this.pumpPromise = this.pump();
    return this;
  }

  async pump() {
    try {
      for (;;) {
        const { value, done } = await this.reader.read();
        if (done) break;
        this.buffer += this.decoder.decode(value, { stream: true });
        this.buffer = this.buffer.replace(/\r\n/g, '\n');
        let index;
        while ((index = this.buffer.indexOf('\n\n')) >= 0) {
          const raw = this.buffer.slice(0, index);
          this.buffer = this.buffer.slice(index + 2);
          this.handleFrame(raw);
        }
      }
    } catch (error) {
      if (!this.closed) throw error;
    } finally {
      this.closed = true;
    }
  }

  handleFrame(raw) {
    /** @type {any} */
    const event = {
      type: 'message',
      data: undefined,
      id: undefined,
      // Raw SSE frame metadata, used to assert the documented framing rules.
      sse: { hasIdLine: false, id: undefined, event: undefined },
    };
    /** @type {string[]} */
    const dataLines = [];
    for (const line of raw.split('\n')) {
      if (line.startsWith(':')) continue;
      if (line.startsWith('id:')) {
        event.sse.hasIdLine = true;
        event.sse.id = line.slice(3).trim();
        event.id = event.sse.id;
      } else if (line.startsWith('event:')) {
        event.sse.event = line.slice(6).trim();
        event.type = event.sse.event;
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).replace(/^ /, ''));
      }
    }
    const payload = dataLines.join('\n');
    if (payload) {
      try {
        // Frames carry the full contract envelope in `data`: {id, type, at,
        // sessionId, data}. Expose the envelope and its inner payload.
        const envelope = JSON.parse(payload);
        event.envelope = envelope;
        event.data = envelope && typeof envelope === 'object' ? envelope.data : envelope;
      } catch {
        event.data = payload;
      }
    }
    if (event.id) this.lastId = event.id;
    this.events.push(event);
    for (const waiter of [...this.waiters]) {
      if (waiter.predicate(event)) {
        clearTimeout(waiter.timer);
        this.waiters.delete(waiter);
        waiter.resolve(event);
      }
    }
  }

  /**
   * @param {string} name
   * @param {(event: any) => boolean} predicate
   * @param {number} [fromIndex]
   * @param {number} [timeoutMs]
   */
  waitFor(name, predicate, fromIndex = 0, timeoutMs = STEP_TIMEOUT_MS) {
    for (let index = fromIndex; index < this.events.length; index += 1) {
      if (predicate(this.events[index])) return Promise.resolve(this.events[index]);
    }
    return new Promise((resolve, reject) => {
      const waiter = {
        predicate,
        resolve,
        /** @type {NodeJS.Timeout | null} */
        timer: null,
      };
      waiter.timer = setTimeout(() => {
        this.waiters.delete(waiter);
        reject(new Error(`Timed out after ${timeoutMs}ms waiting for SSE event: ${name}`));
      }, timeoutMs);
      this.waiters.add(waiter);
    });
  }

  close() {
    this.closed = true;
    try {
      this.controller?.abort();
    } catch {
      // ignore
    }
  }
}

// ---------------------------------------------------------------------------
// SSE framing assertions (contract: docs/mobile-api-v1.md "SSE events")
// ---------------------------------------------------------------------------

const REPLAYABLE_EVENT_TYPES = [
  'session.updated',
  'session.removed',
  'message.added',
  'message.delta',
  'message.completed',
  'approval.requested',
  'approval.resolved',
  'question.requested',
  'question.resolved',
];

const PER_CONNECTION_EVENT_TYPES = ['host.status', 'ping'];

const KNOWN_EVENT_TYPES = new Set([...REPLAYABLE_EVENT_TYPES, ...PER_CONNECTION_EVENT_TYPES]);

/**
 * Validate the documented SSE framing rules on parsed frames:
 *  - `data:` always carries the full envelope {id?, type, at, sessionId?, data}
 *  - the `event:` line mirrors envelope.type
 *  - `id:` is present for replayable/persisted events and absent for
 *    per-connection frames (host.status, ping) and snapshot session.updated
 *    frames (the snapshot is synthesized per connection, not persisted)
 */
function assertEnvelopeFraming(frames, label) {
  assert.ok(frames.length > 0, `${label}: no SSE frames received`);
  let replayable = 0;
  let perConnection = 0;
  for (const frame of frames) {
    const envelope = frame.envelope;
    assert.ok(
      envelope && typeof envelope === 'object',
      `${label}: frame data: is not the envelope JSON`,
    );
    assert.equal(
      frame.sse.event,
      envelope.type,
      `${label}: the event: line must mirror envelope.type`,
    );
    assert.equal(typeof envelope.type, 'string');
    assert.ok(KNOWN_EVENT_TYPES.has(envelope.type), `${label}: unexpected event type "${envelope.type}"`);
    assert.ok(
      !Number.isNaN(Date.parse(envelope.at)),
      `${label}: envelope.at must be an ISO-8601 timestamp`,
    );
    assert.ok(
      Object.prototype.hasOwnProperty.call(envelope, 'data'),
      `${label}: envelope must carry a data field`,
    );
    assert.deepEqual(frame.data, envelope.data, `${label}: parsed payload must be envelope.data`);

    if (frame.sse.hasIdLine) {
      assert.match(frame.sse.id, /^ev_\d{6}$/, `${label}: id: line must look like ev_000123`);
      assert.equal(envelope.id, frame.sse.id, `${label}: envelope.id must match the id: line`);
      assert.ok(
        !PER_CONNECTION_EVENT_TYPES.includes(envelope.type),
        `${label}: per-connection event ${envelope.type} must not carry an id: line`,
      );
      assert.equal(
        typeof envelope.sessionId,
        'string',
        `${label}: replayable ${envelope.type} envelope must carry sessionId`,
      );
      replayable += 1;
    } else {
      assert.equal(
        envelope.id,
        undefined,
        `${label}: a frame without an id: line must not carry an envelope id`,
      );
      const allowedWithoutId =
        PER_CONNECTION_EVENT_TYPES.includes(envelope.type) || envelope.type === 'session.updated';
      assert.ok(
        allowedWithoutId,
        `${label}: replayable event ${envelope.type} is missing its id: line`,
      );
      if (envelope.type === 'session.updated') {
        assert.equal(
          typeof envelope.sessionId,
          'string',
          `${label}: snapshot session.updated envelope must carry sessionId`,
        );
      }
      perConnection += 1;
    }
  }
  return { replayable, perConnection };
}

// ---------------------------------------------------------------------------
// Checks
// ---------------------------------------------------------------------------

async function probeServer() {
  try {
    const response = await fetch(`${BASE_URL}/mobile/v1/health`);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    await response.text();
  } catch (error) {
    console.error(`[FAIL] fake host is not reachable at ${BASE_URL}: ${error.message}`);
    console.error('       Start it first, e.g. (from the repo root):');
    console.error(
      '       & "C:\\.dsh\\resources\\runtime\\primary-runtime\\dependencies\\node\\bin\\node.exe" tools\\fake-host\\server.mjs',
    );
    process.exit(1);
  }
}

async function run() {
  let mainStream;

  await step('health: GET /mobile/v1/health is public and reports apiVersion 1', async () => {
    const result = await request('/mobile/v1/health');
    assert.equal(result.status, 200);
    assert.equal(result.json.ok, true);
    assert.equal(result.json.apiVersion, 1);
    assert.equal(typeof result.json.version, 'string');
    assert.equal(typeof result.json.paired, 'boolean');
    return `version=${result.json.version} paired=${result.json.paired}`;
  });

  await step('auth: GET /sessions without a token is 401 unauthorized', async () => {
    const result = await request('/mobile/v1/sessions');
    assert.equal(result.status, 401);
    assert.equal(result.json.error.code, 'unauthorized');
    assert.equal(typeof result.json.error.message, 'string');
  });

  await step('auth: GET /sessions with a bad token is 401 unauthorized', async () => {
    const result = await request('/mobile/v1/sessions', { token: 'not-the-dev-token' });
    assert.equal(result.status, 401);
    assert.equal(result.json.error.code, 'unauthorized');
  });

  await step('pairing: POST /pair/new returns a 6-digit code and QR payload', async () => {
    const result = await request('/mobile/v1/pair/new', { method: 'POST' });
    assert.equal(result.status, 200);
    assert.match(result.json.code, /^\d{6}$/);
    assert.equal(Number.isNaN(Date.parse(result.json.expiresAt)), false);
    assert.ok(Date.parse(result.json.expiresAt) > Date.now());
    assert.ok(result.json.qrPayload.startsWith('dshmobile://pair?'));
    assert.ok(result.json.qrPayload.includes(`code=${result.json.code}`));
    ctx.pairCode = result.json.code;
    return `code=${result.json.code} expiresAt=${result.json.expiresAt}`;
  });

  await step('pairing: POST /pair/complete with a bad code is 400 invalid_code', async () => {
    const result = await request('/mobile/v1/pair/complete', {
      method: 'POST',
      body: { code: 'not-a-code', deviceName: 'Smoke Pixel', platform: 'android' },
    });
    assert.equal(result.status, 400);
    assert.equal(result.json.error.code, 'invalid_code');
  });

  await step('pairing: POST /pair/complete returns dev token, host and workspaces', async () => {
    const result = await request('/mobile/v1/pair/complete', {
      method: 'POST',
      body: {
        code: ctx.pairCode || '482913',
        deviceName: 'Smoke Pixel',
        platform: 'android',
      },
    });
    assert.equal(result.status, 200);
    assert.equal(result.json.token, TOKEN);
    assert.match(result.json.deviceId, /^dev_/);
    assert.equal(result.json.host.apiVersion, 1);
    assert.equal(typeof result.json.host.name, 'string');
    assert.equal(typeof result.json.host.version, 'string');
    assert.ok(Array.isArray(result.json.workspaces));
    assert.equal(result.json.workspaces.length, 3);
    for (const workspace of result.json.workspaces) {
      assert.equal(typeof workspace.id, 'string');
      assert.equal(typeof workspace.name, 'string');
      assert.equal(typeof workspace.path, 'string');
    }
    ctx.token = result.json.token;
    return `token=${result.json.token} deviceId=${result.json.deviceId} workspaces=${result.json.workspaces.length}`;
  });

  await step('host: GET /host returns name, version and the 3 workspaces', async () => {
    assert.ok(ctx.token, 'no token: pairing failed earlier');
    const result = await request('/mobile/v1/host', { token: ctx.token });
    assert.equal(result.status, 200);
    assert.equal(typeof result.json.name, 'string');
    assert.equal(typeof result.json.version, 'string');
    assert.equal(result.json.workspaces.length, 3);
  });

  await step('sessions: GET /sessions returns seeded demos with all 3 statuses', async () => {
    assert.ok(ctx.token, 'no token: pairing failed earlier');
    const result = await request('/mobile/v1/sessions', { token: ctx.token });
    assert.equal(result.status, 200);
    assert.ok(Array.isArray(result.json.sessions));
    assert.ok(result.json.sessions.length >= 5, `expected >= 5 sessions, got ${result.json.sessions.length}`);
    const statuses = new Set(result.json.sessions.map((session) => session.status));
    for (const status of ['working', 'needs_input', 'idle']) {
      assert.ok(statuses.has(status), `seeded sessions are missing status "${status}"`);
    }
    for (const session of result.json.sessions) {
      for (const key of ['id', 'title', 'workspaceId', 'status', 'preview', 'updatedAt', 'unread', 'live']) {
        assert.ok(key in session, `session summary is missing "${key}"`);
      }
      assert.equal(typeof session.live, 'boolean');
      assert.equal(typeof session.unread, 'boolean');
      // Contract: SessionSummary.workspaceId may be null when unresolved.
      assert.ok(
        session.workspaceId === null || typeof session.workspaceId === 'string',
        'workspaceId must be a string or null',
      );
      assert.ok(!Number.isNaN(Date.parse(session.updatedAt)));
    }
    const sortedDesc = result.json.sessions.every((session, index) => {
      if (index === 0) return true;
      const previous = result.json.sessions[index - 1];
      return Date.parse(previous.updatedAt) >= Date.parse(session.updatedAt);
    });
    assert.ok(sortedDesc, 'sessions must be sorted by updatedAt descending');
    ctx.sessionId = result.json.sessions[0].id;
    ctx.sessionsCount = result.json.sessions.length;
    return `${result.json.sessions.length} sessions, statuses=${[...statuses].join('/')}`;
  });

  await step('sessions: GET /sessions/{id} returns session + messages', async () => {
    assert.ok(ctx.token && ctx.sessionId, 'no token/session from earlier steps');
    const result = await request(`/mobile/v1/sessions/${ctx.sessionId}`, { token: ctx.token });
    assert.equal(result.status, 200);
    assert.equal(result.json.session.id, ctx.sessionId);
    assert.ok(Array.isArray(result.json.messages));
    assert.ok(result.json.messages.length > 0);
    assert.ok('nextCursor' in result.json);
    for (const message of result.json.messages) {
      assert.equal(typeof message.id, 'string');
      assert.ok(['user', 'assistant', 'system', 'tool'].includes(message.role));
      assert.equal(typeof message.text, 'string');
      assert.ok(!Number.isNaN(Date.parse(message.createdAt)));
      assert.equal(typeof message.streaming, 'boolean');
    }
    return `${result.json.messages.length} messages, nextCursor=${String(result.json.nextCursor)}`;
  });

  await step('sessions: GET /sessions/{id}?limit=2 paginates via nextCursor', async () => {
    const result = await request(`/mobile/v1/sessions/${ctx.sessionId}?limit=2`, {
      token: ctx.token,
    });
    assert.equal(result.status, 200);
    assert.ok(result.json.messages.length <= 2);
    assert.ok(result.json.messages.length > 0);
    assert.equal(typeof result.json.nextCursor, 'string');
    const older = await request(
      `/mobile/v1/sessions/${ctx.sessionId}?limit=2&before=${result.json.nextCursor}`,
      { token: ctx.token },
    );
    assert.equal(older.status, 200);
    assert.ok(older.json.messages.length > 0);
  });

  await step('sessions: POST /sessions creates a session with a greeting message', async () => {
    const created = await request('/mobile/v1/sessions', {
      method: 'POST',
      token: ctx.token,
      body: { workspaceId: 'ws_1', preset: 'deepseek' },
    });
    assert.equal(created.status, 200);
    assert.match(created.json.session.id, /^s_/);
    assert.equal(created.json.session.workspaceId, 'ws_1');
    ctx.newSessionId = created.json.session.id;
    const detail = await request(`/mobile/v1/sessions/${ctx.newSessionId}`, { token: ctx.token });
    assert.equal(detail.status, 200);
    assert.equal(detail.json.messages.length, 1);
    assert.equal(detail.json.messages[0].role, 'assistant');
    assert.match(detail.json.messages[0].text, /New \*\*deepseek\*\* session/);
    return `sessionId=${ctx.newSessionId}`;
  });

  await step('SSE: GET /events connects, sends host.status + session snapshot', async () => {
    mainStream = new EventStream();
    await mainStream.open();
    await mainStream.waitFor(
      'host.status',
      (event) => event.type === 'host.status' && event.data?.connected === true,
      0,
      5_000,
    );
    await waitUntil(
      'session snapshot frames',
      () =>
        mainStream.events.filter(
          (event) => event.type === 'session.updated' && event.id === undefined,
        ).length >= ctx.sessionsCount,
      5_000,
    );
    const snapshots = mainStream.events.filter(
      (event) => event.type === 'session.updated' && event.id === undefined,
    ).length;
    return `host.status + ${snapshots} snapshot session.updated frames`;
  });

  await step('prompt: POST /prompt streams message.delta chunks then message.completed', async () => {
    assert.ok(mainStream, 'SSE stream was not opened');
    const from = mainStream.events.length;
    const accepted = await request(`/mobile/v1/sessions/${ctx.newSessionId}/prompt`, {
      method: 'POST',
      token: ctx.token,
      body: { text: 'Run the test suite and report status.' },
    });
    assert.equal(accepted.status, 200);
    assert.equal(accepted.json.accepted, true);
    assert.match(accepted.json.runId, /^run_/);

    await mainStream.waitFor(
      'user message.added',
      (event) =>
        event.type === 'message.added' &&
        event.data?.sessionId === ctx.newSessionId &&
        event.data.message.role === 'user',
      from,
    );
    await mainStream.waitFor(
      'session.updated working',
      (event) =>
        event.type === 'session.updated' &&
        event.data?.id === ctx.newSessionId &&
        event.data.status === 'working',
      from,
    );
    const assistantAdded = await mainStream.waitFor(
      'assistant message.added (streaming)',
      (event) =>
        event.type === 'message.added' &&
        event.data?.sessionId === ctx.newSessionId &&
        event.data.message.role === 'assistant' &&
        event.data.message.streaming === true,
      from,
    );
    const messageId = assistantAdded.data.message.id;

    const firstDelta = await mainStream.waitFor(
      'message.delta',
      (event) => event.type === 'message.delta' && event.data?.messageId === messageId,
      from,
    );
    assert.ok(firstDelta.data.text.length > 0);
    assert.equal(typeof firstDelta.id, 'string');
    assert.match(firstDelta.id, /^ev_\d{6}$/);

    await mainStream.waitFor(
      'message.completed',
      (event) => event.type === 'message.completed' && event.data?.messageId === messageId,
      from,
    );
    await mainStream.waitFor(
      'session.updated idle',
      (event) =>
        event.type === 'session.updated' &&
        event.data?.id === ctx.newSessionId &&
        event.data.status === 'idle',
      from,
    );

    const deltas = mainStream.events.filter(
      (event) => event.type === 'message.delta' && event.data?.messageId === messageId,
    );
    assert.ok(deltas.length >= 3, `expected several deltas, got ${deltas.length}`);
    const streamedText = deltas.map((event) => event.data.text).join('');
    const detail = await request(`/mobile/v1/sessions/${ctx.newSessionId}`, { token: ctx.token });
    const finalMessage = detail.json.messages.find((message) => message.id === messageId);
    assert.ok(finalMessage, 'final assistant message is missing from session detail');
    assert.equal(finalMessage.streaming, false);
    assert.equal(finalMessage.text, streamedText);
    assert.match(finalMessage.text, /128 passed/);
    return `${deltas.length} deltas, ${streamedText.length} chars, messageId=${messageId}`;
  });

  await step('approval: a deploy prompt raises approval.requested', async () => {
    assert.ok(mainStream, 'SSE stream was not opened');
    const from = mainStream.events.length;
    const accepted = await request(`/mobile/v1/sessions/${ctx.newSessionId}/prompt`, {
      method: 'POST',
      token: ctx.token,
      body: { text: 'Please deploy v1.2.3 to production.' },
    });
    assert.equal(accepted.status, 200);
    assert.equal(accepted.json.accepted, true);
    const approval = await mainStream.waitFor(
      'approval.requested',
      (event) =>
        event.type === 'approval.requested' && event.data?.sessionId === ctx.newSessionId,
      from,
    );
    assert.match(approval.data.id, /^ap_/);
    assert.equal(typeof approval.data.title, 'string');
    assert.equal(typeof approval.data.description, 'string');
    assert.match(approval.data.command, /publish|push/);
    ctx.approvalId = approval.data.id;
    const list = await request('/mobile/v1/approvals', { token: ctx.token });
    assert.equal(list.status, 200);
    assert.ok(list.json.approvals.some((item) => item.id === ctx.approvalId));
    return `approvalId=${ctx.approvalId} command="${approval.data.command}"`;
  });

  await step('approval: deny resolves it and returns the session to idle', async () => {
    const from = mainStream.events.length;
    const result = await request(`/mobile/v1/approvals/${ctx.approvalId}`, {
      method: 'POST',
      token: ctx.token,
      body: { decision: 'deny' },
    });
    assert.equal(result.status, 200);
    assert.equal(result.json.ok, true);
    await mainStream.waitFor(
      'approval.resolved',
      (event) => event.type === 'approval.resolved' && event.data?.approvalId === ctx.approvalId,
      from,
    );
    await mainStream.waitFor(
      'session.updated idle after deny',
      (event) =>
        event.type === 'session.updated' &&
        event.data?.id === ctx.newSessionId &&
        event.data.status === 'idle',
      from,
    );
    const list = await request('/mobile/v1/approvals', { token: ctx.token });
    assert.ok(!list.json.approvals.some((item) => item.id === ctx.approvalId));
  });

  await step('approval: allow runs the fake command to completion', async () => {
    const promptFrom = mainStream.events.length;
    const accepted = await request(`/mobile/v1/sessions/${ctx.newSessionId}/prompt`, {
      method: 'POST',
      token: ctx.token,
      body: { text: 'publish the patch release now' },
    });
    assert.equal(accepted.status, 200);
    const approvalEvent = await mainStream.waitFor(
      'approval.requested (allow case)',
      (event) =>
        event.type === 'approval.requested' && event.data?.sessionId === ctx.newSessionId,
      promptFrom,
    );
    const resolveFrom = mainStream.events.length;
    const decision = await fetch(`${BASE_URL}/mobile/v1/approvals/${approvalEvent.data.id}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${ctx.token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ decision: 'allow', note: 'smoke test' }),
    });
    assert.equal(decision.status, 200);
    await mainStream.waitFor(
      'approval.resolved (allow)',
      (event) =>
        event.type === 'approval.resolved' && event.data?.approvalId === approvalEvent.data.id,
      resolveFrom,
    );

    // Contract note: tool rows are upserted through message.added with the same
    // message id (no message.updated event type exists).
    const runningTool = await mainStream.waitFor(
      'tool message.added (running)',
      (event) =>
        event.type === 'message.added' &&
        event.data?.sessionId === ctx.newSessionId &&
        event.data.message.role === 'tool' &&
        event.data.message.tool?.state === 'running',
      resolveFrom,
    );
    const toolId = runningTool.data.message.id;
    const finishedTool = await mainStream.waitFor(
      'tool message.added upsert (done, same id)',
      (event) =>
        event.type === 'message.added' &&
        event.data?.sessionId === ctx.newSessionId &&
        event.data.message.id === toolId &&
        event.data.message.tool?.state === 'done',
      resolveFrom,
    );
    assert.equal(finishedTool.data.message.id, toolId, 'tool upsert must reuse the message id');

    await mainStream.waitFor(
      'session.updated idle after allow',
      (event) =>
        event.type === 'session.updated' &&
        event.data?.id === ctx.newSessionId &&
        event.data.status === 'idle',
      resolveFrom,
    );
    const detail = await request(`/mobile/v1/sessions/${ctx.newSessionId}`, { token: ctx.token });
    const toolRow = detail.json.messages.find((message) => message.id === toolId);
    assert.ok(toolRow, 'tool row is missing from session detail');
    assert.equal(toolRow.tool?.state, 'done');
    const lastAssistant = [...detail.json.messages].reverse().find((message) => message.role === 'assistant');
    assert.ok(lastAssistant);
    assert.match(lastAssistant.text, /Done\./);
    return `toolId=${toolId} running->done upserted`;
  });

  await step('interrupt: POST /interrupt stops streaming and returns the session to idle', async () => {
    const from = mainStream.events.length;
    const accepted = await request(`/mobile/v1/sessions/${ctx.newSessionId}/prompt`, {
      method: 'POST',
      token: ctx.token,
      body: { text: 'Write a long report about the project, in detail.' },
    });
    assert.equal(accepted.status, 200);
    const delta = await mainStream.waitFor(
      'first message.delta',
      (event) => event.type === 'message.delta' && event.data?.sessionId === ctx.newSessionId,
      from,
    );
    assert.match(delta.id, /^ev_\d{6}$/);
    const interrupt = await request(`/mobile/v1/sessions/${ctx.newSessionId}/interrupt`, {
      method: 'POST',
      token: ctx.token,
    });
    assert.equal(interrupt.status, 200);
    assert.equal(interrupt.json.ok, true);
    await mainStream.waitFor(
      'message.completed after interrupt',
      (event) =>
        event.type === 'message.completed' && event.data?.messageId === delta.data.messageId,
      from,
    );
    await mainStream.waitFor(
      'session.updated idle after interrupt',
      (event) =>
        event.type === 'session.updated' &&
        event.data?.id === ctx.newSessionId &&
        event.data.status === 'idle',
      from,
    );
    const detail = await request(`/mobile/v1/sessions/${ctx.newSessionId}`, { token: ctx.token });
    assert.equal(detail.json.session.status, 'idle');
    const message = detail.json.messages.find((item) => item.id === delta.data.messageId);
    assert.ok(message, 'interrupted assistant message is missing');
    assert.equal(message.streaming, false);
  });

  await step('questions: GET /questions lists the seeded question and answering resolves it', async () => {
    const list = await request('/mobile/v1/questions', { token: ctx.token });
    assert.equal(list.status, 200);
    assert.ok(list.json.questions.length >= 1, 'expected at least one seeded question');
    const question = list.json.questions[0];
    for (const key of ['id', 'sessionId', 'prompt', 'options', 'createdAt']) {
      assert.ok(key in question, `question is missing "${key}"`);
    }
    assert.ok(Array.isArray(question.options) && question.options.length >= 2);
    const from = mainStream.events.length;
    const result = await request(`/mobile/v1/questions/${question.id}`, {
      method: 'POST',
      token: ctx.token,
      body: { answer: question.options[0], optionIndex: 0 },
    });
    assert.equal(result.status, 200);
    assert.equal(result.json.ok, true);
    await mainStream.waitFor(
      'question.resolved',
      (event) => event.type === 'question.resolved' && event.data?.questionId === question.id,
      from,
    );
    const after = await request('/mobile/v1/questions', { token: ctx.token });
    assert.ok(!after.json.questions.some((item) => item.id === question.id));
    return `questionId=${question.id}`;
  });

  await step('SSE: reconnecting with since=<lastEventId> replays only newer events', async () => {
    assert.ok(mainStream, 'SSE stream was not opened');
    const cursor = mainStream.lastId;
    assert.ok(cursor, 'main stream never received an event id');
    assert.match(cursor, /^ev_\d{6}$/);
    const created = await request('/mobile/v1/sessions', {
      method: 'POST',
      token: ctx.token,
      body: { workspaceId: 'ws_2' },
    });
    assert.equal(created.status, 200);

    const replay = new EventStream(cursor);
    await replay.open();
    try {
      await replay.waitFor('replay host.status', (event) => event.type === 'host.status', 0, 5_000);
      assert.equal(replay.events[0]?.type, 'host.status');
      await replay.waitFor(
        'replayed session.updated',
        (event) =>
          event.type === 'session.updated' && event.data?.id === created.json.session.id,
        0,
        8_000,
      );
      await sleep(100); // let the remaining replayed frames parse
      const replayStats = assertEnvelopeFraming(replay.events, 'SSE replay');
      assert.ok(replayStats.replayable >= 1, 'replay must include replayable frames with id: lines');
      assert.equal(
        replay.events[0]?.sse.hasIdLine,
        false,
        'host.status is a per-connection frame and must not carry an id: line',
      );
      const cursorSequence = Number(cursor.slice(3));
      const withIds = replay.events.filter((event) => typeof event.id === 'string');
      assert.ok(withIds.length >= 1, 'no replayed events with ids');
      for (const event of withIds) {
        assert.ok(
          Number(event.id.slice(3)) > cursorSequence,
          `replayed event ${event.id} is not newer than cursor ${cursor}`,
        );
      }
      return `cursor=${cursor}, replayed ${withIds.length} events`;
    } finally {
      replay.close();
    }
  });

  await step('SSE framing: full envelope in data:, event: mirrors type, id: only on replayable frames', async () => {
    assert.ok(mainStream, 'SSE stream was not opened');
    const stats = assertEnvelopeFraming(mainStream.events, 'main SSE stream');
    assert.ok(stats.replayable >= 5, `expected several replayable frames, got ${stats.replayable}`);
    assert.ok(
      stats.perConnection >= 1,
      'expected per-connection frames (host.status / session snapshot)',
    );

    const hostStatusFrames = mainStream.events.filter(
      (frame) => frame.envelope?.type === 'host.status',
    );
    assert.ok(hostStatusFrames.length >= 1, 'no host.status frame captured');
    for (const frame of hostStatusFrames) {
      assert.equal(frame.sse.hasIdLine, false, 'host.status must not carry an id: line');
      assert.deepEqual(frame.data, { connected: true });
    }

    // Snapshot session.updated frames are per-connection (not persisted), so
    // they carry an envelope but no id: line.
    const snapshots = mainStream.events.filter(
      (frame) => frame.envelope?.type === 'session.updated' && !frame.sse.hasIdLine,
    );
    assert.ok(snapshots.length >= 1, 'no per-connection snapshot session.updated frames captured');

    const replayableTypes = new Set(
      mainStream.events
        .filter((frame) => frame.sse.hasIdLine)
        .map((frame) => frame.envelope.type),
    );
    for (const required of [
      'session.updated',
      'message.added',
      'message.delta',
      'message.completed',
      'approval.requested',
      'approval.resolved',
    ]) {
      assert.ok(
        replayableTypes.has(required),
        `no replayable ${required} frame with an id: line captured`,
      );
    }
    return `${stats.replayable} replayable + ${stats.perConnection} per-connection frames validated`;
  });

  await step('health: reports paired=true after a successful pairing', async () => {
    const result = await request('/mobile/v1/health');
    assert.equal(result.status, 200);
    assert.equal(result.json.paired, true);
  });

  await step('errors: unknown session is 404 not_found', async () => {
    const result = await request('/mobile/v1/sessions/does-not-exist', { token: ctx.token });
    assert.equal(result.status, 404);
    assert.equal(result.json.error.code, 'not_found');
  });

  await step('SSE: GET /events without a token is 401 unauthorized', async () => {
    const response = await fetch(`${BASE_URL}/mobile/v1/events`);
    assert.equal(response.status, 401);
    const body = await response.json();
    assert.equal(body.error.code, 'unauthorized');
  });

  mainStream?.close();
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

const watchdog = setTimeout(() => {
  console.error('[FAIL] smoke test timed out after 90s');
  process.exit(1);
}, 90_000);

try {
  await probeServer();
  await run();
} catch (error) {
  failure('unexpected error', error);
} finally {
  clearTimeout(watchdog);
  const duration = Date.now() - startedAt;
  console.log('');
  console.log(`${passCount} passed, ${failCount} failed in ${(duration / 1000).toFixed(1)}s`);
  if (failCount > 0) {
    console.log('Failures:');
    for (const item of failures) console.log(`  - ${item}`);
    process.exitCode = 1;
  }
}
