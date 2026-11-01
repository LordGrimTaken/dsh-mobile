import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { createDshAdapter } from '../lib/dsh-adapter.js';
import { ApiError } from '../lib/errors.js';
import { EventBus } from '../lib/events.js';
import { createRouter } from '../lib/router.js';
import { parseScheduleCreateBody, parseScheduleUpdateBody } from '../lib/schedules.js';
import { createHiddenStore, issueToken, makePairing, routeCall, testConfig } from './helpers.mjs';

const silent = { info() {}, warn() {}, error() {} };
const T = (offset = 0) => Date.UTC(2026, 9, 30, 9, 40, 0) + offset;

const HEADER = { version: 4, id: 's_1', createdAt: T(0), cwd: 'C:\\work', isSeeded: false };

/** Raw DSH events for a short completed turn. */
const EVENTS = [
  {
    type: 'user/message',
    seq: 1,
    time: T(1000),
    data: { id: 'm_user_1', role: 'user', content: [{ type: 'text', text: 'Hello there' }], source: { kind: 'user' } },
  },
  {
    type: 'assistant/message',
    seq: 2,
    time: T(2000),
    data: {
      message: {
        id: 'm_a1',
        role: 'assistant',
        content: [{ type: 'text', text: 'Working on it' }],
        source: { kind: 'model', provider: 'deepseek', model: 'flash' },
      },
      stream: [],
    },
  },
  {
    type: 'assistant/message',
    seq: 3,
    time: T(3000),
    data: {
      message: {
        id: 'm_a2',
        role: 'assistant',
        content: [{ type: 'text', text: 'All 128 tests passed' }],
        source: { kind: 'model', provider: 'deepseek', model: 'flash' },
      },
      stream: [],
    },
  },
];

/** Fake Cordis context: `get`, `on`, and an emit helper for tests. */
function createFakeCtx(services = {}) {
  /** @type {Map<string, Function[]>} */
  const listeners = new Map();
  return {
    get(name) {
      return services[name];
    },
    on(event, listener) {
      const list = listeners.get(event) ?? [];
      list.push(listener);
      listeners.set(event, list);
      return () => {
        const current = listeners.get(event) ?? [];
        listeners.set(event, current.filter((entry) => entry !== listener));
      };
    },
    emit(event, ...args) {
      for (const listener of [...(listeners.get(event) ?? [])]) listener(...args);
    },
    listenersFor(event) {
      return [...(listeners.get(event) ?? [])];
    },
    listenerCount(event) {
      return (listeners.get(event) ?? []).length;
    },
  };
}

function fakeSessionQuery({ snapshots = {}, titles = {}, read, filterEvents } = {}) {
  const calls = [];
  /** @type {Record<string, any>} */
  const service = {
    calls,
    async readSession(sessionId, signal) {
      calls.push({ method: 'readSession', sessionId, signal });
      if (read) return read(sessionId, signal);
      const snapshot = snapshots[sessionId];
      if (!snapshot) {
        const error = new Error(`Session ${sessionId} not found`);
        error.code = 'session/not-found';
        throw error;
      }
      return snapshot;
    },
    async filterSessions(filters) {
      const ids = filters.find((filter) => filter.kind === 'id')?.values ?? [];
      return ids
        .filter((sessionId) => snapshots[sessionId] !== undefined)
        .map((sessionId) => ({ header: snapshots[sessionId].session, live: false, persisted: true }));
    },
    async readTitleSnapshots(sessionIds) {
      return sessionIds.map((sessionId) => ({
        sessionId,
        status: 'fulfilled',
        value: {
          session: HEADER,
          ...(titles[sessionId]
            ? {
                title: {
                  title: titles[sessionId],
                  eventSeq: 2,
                  updatedAt: T(0),
                  messageSeqs: [],
                  source: { kind: 'user' },
                },
              }
            : {}),
        },
      }));
    },
  };
  if (filterEvents) {
    service.filterEvents = async (sessionId, filters) => {
      calls.push({ method: 'filterEvents', sessionId, filters });
      return filterEvents(sessionId, filters);
    };
  }
  return service;
}

/** Fake `ctx.attachments` capability (image + file admission). */
function fakeAttachments({ saveImageError, admitError } = {}) {
  const calls = [];
  return {
    calls,
    imageLimits: {
      maxImageBytes: 10 * 1024 * 1024,
      maxImagesPerMessage: 8,
      maxMessageImageBytes: 30 * 1024 * 1024,
      maxImagePixels: 4_000_000,
      maxImageDimension: 4096,
      mediaTypes: ['image/png', 'image/jpeg', 'image/webp', 'image/gif'],
    },
    isAttachmentError(error) {
      return Boolean(error && /** @type {any} */ (error).isAttachmentError === true);
    },
    async saveImage(input) {
      calls.push({ method: 'saveImage', input });
      if (saveImageError) throw saveImageError;
      return {
        attachmentId: 'img_01',
        mediaType: input.mediaType,
        bytes: input.data.length,
        width: 1,
        height: 1,
        ...(input.name ? { name: input.name } : {}),
      };
    },
    async admitEncodedFile(input) {
      calls.push({ method: 'admitEncodedFile', input });
      if (admitError) throw admitError;
      return { attachmentId: 'file_01', name: input.name ?? 'file', bytes: Buffer.from(input.data, 'base64').length };
    },
  };
}

/** Fake `ctx.attachments` read-back capability (images + streamed files). */
function fakeAttachmentReader({ imageData, fileChunks, readImageError, readFileError } = {}) {
  const calls = [];
  return {
    calls,
    async readImage(ref) {
      calls.push({ method: 'readImage', ref });
      if (readImageError) throw readImageError;
      return { ref, data: imageData ?? new Uint8Array([1, 2, 3]) };
    },
    async *readFileStream(ref) {
      calls.push({ method: 'readFileStream', ref });
      if (readFileError) throw readFileError;
      for (const chunk of fileChunks ?? [new Uint8Array([9, 8, 7])]) yield chunk;
    },
  };
}

/** Fake `ctx.schedule` management capability (catalog/create/update/delete). */
function fakeScheduleService({ catalog = [], create, update, del, catalogError } = {}) {
  const calls = [];
  return {
    calls,
    async catalog() {
      calls.push({ method: 'catalog' });
      if (catalogError) throw catalogError;
      return catalog;
    },
    async create(sessionId, request) {
      calls.push({ method: 'create', sessionId, request });
      if (create) return create(sessionId, request);
      return {
        id: 'schedule-new',
        kind: 'after',
        title: request.title,
        prompt: request.prompt,
        afterSeconds: request.after_seconds,
        scheduledAt: '2026-10-30T10:01:00Z',
      };
    },
    async update(request) {
      calls.push({ method: 'update', request });
      if (update) return update(request);
      // Echo the expected record with the supplied content/timing edits.
      const record = {
        ...request.expected,
        ...(request.title === undefined ? {} : { title: request.title }),
        ...(request.prompt === undefined ? {} : { prompt: request.prompt }),
      };
      if (request.change?.kind === 'at') {
        return {
          id: request.id,
          updated: true,
          record: {
            id: record.id,
            kind: 'at',
            title: record.title,
            prompt: record.prompt,
            scheduledAt: request.change.at,
          },
        };
      }
      if (request.change?.kind === 'every') {
        return {
          id: request.id,
          updated: true,
          record: {
            ...record,
            kind: 'every',
            everySeconds: request.change.every_seconds,
            scheduledAt: record.scheduledAt,
          },
        };
      }
      if (request.change?.kind === 'daily') {
        return {
          id: request.id,
          updated: true,
          record: {
            ...record,
            kind: 'daily',
            time: request.change.daily.time,
            timeZone: request.change.daily.time_zone,
            scheduledAt: record.scheduledAt,
          },
        };
      }
      return { id: request.id, updated: true, record };
    },
    async delete(request) {
      calls.push({ method: 'delete', request });
      if (del) return del(request);
      return { id: request.id, deleted: true };
    },
  };
}

/** Fake `ctx.fileUploads` capability (staged receipts). */
function fakeFileUploads() {
  const calls = [];
  return {
    calls,
    async uploadStream(request) {
      let bytes = 0;
      for await (const chunk of request.data) bytes += chunk.length;
      calls.push({ method: 'uploadStream', request, bytes });
      return {
        receiptId: 'receipt_01',
        file: { attachmentId: 'file_01', name: request.name ?? 'file', bytes },
      };
    },
  };
}

function attachmentError(message, code) {
  const error = new Error(message);
  error.name = 'AttachmentError';
  /** @type {any} */ (error).isAttachmentError = true;
  /** @type {any} */ (error).code = code;
  return error;
}

function fakeSessionController({ items = [], create, prompt, cancel, rename, list, modelCatalog, selectModel, updateQueue, fork } = {}) {
  const calls = [];
  return {
    calls,
    async list(request) {
      calls.push({ method: 'list', request });
      if (list) return list(request);
      return { items };
    },
    async modelCatalog() {
      calls.push({ method: 'modelCatalog' });
      if (modelCatalog) return modelCatalog();
      return {
        default: { provider: 'deepseek', model: 'deepseek-chat' },
        routableProviders: ['deepseek'],
        groups: [
          {
            id: 'deepseek',
            name: 'DeepSeek',
            models: [{ id: 'deepseek-chat', name: 'DeepSeek Chat' }],
          },
        ],
        failures: [],
      };
    },
    async selectModel(request) {
      calls.push({ method: 'selectModel', request });
      if (selectModel) return selectModel(request);
      return {
        selected: {
          provider: request.provider,
          model: request.model,
          ...(request.reasoningEffort === undefined ? {} : { reasoningEffort: request.reasoningEffort }),
        },
      };
    },
    async updateQueue(request) {
      calls.push({ method: 'updateQueue', request });
      if (updateQueue) return updateQueue(request);
      return { accepted: true };
    },
    async fork(request) {
      calls.push({ method: 'fork', request });
      if (fork) return fork(request);
      return { sessionId: 'session-forked-default' };
    },
    async create(request) {
      calls.push({ method: 'create', request });
      return create ? create(request) : { sessionId: 's_new' };
    },
    async prompt(request) {
      calls.push({ method: 'prompt', request });
      return prompt ? prompt(request) : { accepted: true };
    },
    cancel(request) {
      calls.push({ method: 'cancel', request });
      return cancel ? cancel(request) : { accepted: true };
    },
    async rename(request) {
      calls.push({ method: 'rename', request });
      if (rename) return rename(request);
      return { title: request.title, seq: 7 };
    },
  };
}

/**
 * Fake `ctx.workspaceController` for archive/unarchive/archived reads.
 * The archive set is exposed through the `follow()` baseline only, so the
 * adapter's stream-based fallback is exercised.
 */
function fakeWorkspaceController({ archived = [], archive, unarchive } = {}) {
  const calls = [];
  const archivedIds = new Set(archived.map(String));
  const controller = {
    calls,
    async archiveSession(request) {
      calls.push({ method: 'archiveSession', request });
      if (archive) return archive(request, controller);
      archivedIds.add(String(request.sessionId));
      return { archivedSessionIds: [...archivedIds] };
    },
    async unarchiveSession(request) {
      calls.push({ method: 'unarchiveSession', request });
      if (unarchive) return unarchive(request, controller);
      archivedIds.delete(String(request.sessionId));
      return { archivedSessionIds: [...archivedIds] };
    },
    follow() {
      const snapshot = [...archivedIds];
      return (async function* baseline() {
        yield {
          type: 'baseline',
          value: { items: [], archivedSessionIds: snapshot, pinnedSessionIds: [] },
        };
      })();
    },
  };
  return controller;
}

function fakeWorkspaceRegistry(workspaces) {
  return { list: () => workspaces };
}

/** Fake `ctx.approval` policy service (effectivePolicy/setPolicy). */
function fakeApprovalPolicyService({ policies = {}, setPolicy } = {}) {
  const calls = [];
  const service = {
    calls,
    effectivePolicy(session) {
      calls.push({ method: 'effectivePolicy', sessionId: session?.id });
      return policies[session?.id] ?? 'ask';
    },
    setPolicy(agent, policy) {
      calls.push({ method: 'setPolicy', sessionId: agent?.id, policy });
      if (setPolicy) return setPolicy(agent, policy);
      policies[agent?.id] = policy;
    },
  };
  return service;
}

/** Fake `ctx.agentPresets` capability (list + remote roster + defaultId). */
function fakeAgentPresets({ list, roster, defaultId = 'default', listError, rosterError } = {}) {
  const calls = [];
  const service = {
    calls,
    defaultId,
    async list() {
      calls.push({ method: 'list' });
      if (listError) throw listError;
      return (
        list ?? [
          { id: 'focused', name: 'Focused', order: 2, broken: 'activation failed' },
          { id: 'default', name: 'Default', description: 'General work', order: 0 },
          { id: 'research', name: 'Research', order: 1 },
        ]
      );
    },
    async remoteExportList() {
      calls.push({ method: 'remoteExportList' });
      if (rosterError) throw rosterError;
      return (
        roster ?? {
          presets: [
            { id: 'default', name: 'Default', isDefault: true },
            { id: 'focused', name: 'Focused', broken: 'activation failed', isDefault: false },
            { id: 'research', name: 'Research', isDefault: false },
          ],
        }
      );
    },
  };
  return service;
}

const SESSION_ITEMS = [
  { sessionId: 's_1', updatedAt: T(5000), running: true, agentAvailable: true, cwd: 'C:\\work' },
  { sessionId: 's_2', updatedAt: T(100), running: false, agentAvailable: false, cwd: 'C:\\elsewhere' },
];

const SNAPSHOTS = {
  s_1: { session: HEADER, inheritedEventCount: 0, events: EVENTS },
  s_2: {
    session: { ...HEADER, id: 's_2', cwd: 'C:\\elsewhere' },
    inheritedEventCount: 0,
    events: [EVENTS[0]],
  },
  s_new: {
    session: { ...HEADER, id: 's_new', cwd: 'C:\\work' },
    inheritedEventCount: 0,
    events: [],
  },
};

const WORKSPACES = [
  { id: 'ws_1', title: 'DeepSeek Harness', path: 'C:\\work', sessionIds: ['s_1', 's_new'] },
];

/**
 * Adapter under test.
 * Filtering is on by default in production; most legacy tests use synthetic
 * `s_1`/`s_2` ids, so they opt out of the hide-internal heuristic (the
 * filtering behaviour itself is covered by the dedicated tests at the end).
 */
function makeAdapter(ctx, bus = null, options = {}) {
  const config = options.config ?? testConfig({ sessions: { hideInternal: false, cacheFile: false } });
  return createDshAdapter({
    ctx,
    config,
    bus,
    logger: options.logger ?? silent,
    hiddenStore: options.hiddenStore ?? null,
  });
}

test('listSessions maps DSH summaries, workspaces, status; warm cache adds titles/previews', async () => {
  const ctx = createFakeCtx({
    sessionController: fakeSessionController({ items: SESSION_ITEMS }),
    sessionQuery: fakeSessionQuery({ snapshots: SNAPSHOTS, titles: { s_1: 'Cut a patch release' } }),
    workspaceRegistry: fakeWorkspaceRegistry(WORKSPACES),
  });
  const adapter = makeAdapter(ctx);

  // The first (cold) response is intentionally immediate; it must still carry the
  // controller-derived fields while title/preview enrichment warms in the background.
  const cold = await adapter.listSessions();
  assert.equal(cold.length, 2);
  assert.equal(cold[0].workspaceId, 'ws_1');
  assert.equal(cold[0].status, 'working');
  assert.equal(cold[0].live, true);

  let sessions = cold;
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 20));
    sessions = await adapter.listSessions();
    if (sessions[0]?.title === 'Cut a patch release') break;
  }

  assert.deepEqual(sessions[0], {
    id: 's_1',
    title: 'Cut a patch release',
    workspaceId: 'ws_1',
    status: 'working',
    preview: 'All 128 tests passed',
    updatedAt: '2026-10-30T09:40:05Z',
    unread: false,
    live: true,
    goal: null,
  });
  assert.equal(sessions[1].title, 'Hello there', 'title falls back to the first user prompt');
  assert.equal(sessions[1].workspaceId, null);
  assert.equal(sessions[1].status, 'idle');
  assert.equal(sessions[1].live, false);
  assert.equal(sessions[1].goal, null);
});

test('listSessions carries a goal summary for live sessions and null otherwise', async () => {
  const liveAgent = { id: 'session-goal-list-live' };
  const noGoalAgent = { id: 'session-goal-list-nogoal' };
  let currentGoal = {
    id: 'goal-list',
    objective: 'Ship the list goal',
    phase: 'active',
    maxGoalRounds: 50,
    roundsStarted: 7,
    revision: 2,
    activation: 'armed',
    createdAt: T(1000),
    updatedAt: T(2000),
  };
  const items = [
    { sessionId: 'session-goal-list-live', updatedAt: T(5000), running: false, agentAvailable: true, cwd: 'C:\\work' },
    { sessionId: 'session-goal-list-nogoal', updatedAt: T(4000), running: false, agentAvailable: true, cwd: 'C:\\work' },
    { sessionId: 'session-goal-list-cold', updatedAt: T(3000), running: false, agentAvailable: false, cwd: 'C:\\work' },
  ];
  const goalCalls = [];
  const ctx = createFakeCtx({
    sessionController: fakeSessionController({ items }),
    agents: {
      get: (id) => (id === liveAgent.id ? liveAgent : id === noGoalAgent.id ? noGoalAgent : undefined),
    },
    goals: {
      get: (agent) => {
        goalCalls.push(agent);
        return agent.id === liveAgent.id ? currentGoal : undefined;
      },
    },
  });
  const adapter = createDshAdapter({ ctx, config: testConfig(), logger: silent });

  const sessions = await adapter.listSessions();
  assert.equal(sessions.length, 3);
  const byId = new Map(sessions.map((session) => [session.id, session]));
  assert.deepEqual(byId.get('session-goal-list-live').goal, {
    objective: 'Ship the list goal',
    phase: 'active',
    roundsStarted: 7,
    maxGoalRounds: 50,
  });
  assert.equal(byId.get('session-goal-list-nogoal').goal, null);
  assert.equal(byId.get('session-goal-list-cold').goal, null);
  assert.equal(
    goalCalls.some((agent) => agent.id === 'session-goal-list-cold'),
    false,
    'non-live rows are never probed for goals',
  );
  // Existing fields are unchanged by the enrichment.
  assert.equal(byId.get('session-goal-list-live').live, true);
  assert.equal(byId.get('session-goal-list-live').title, 'Untitled session');

  // Serve-time enrichment: a goal change shows up on the next list call even
  // though the list payload itself is cached.
  currentGoal = { ...currentGoal, phase: 'paused', roundsStarted: 9, revision: 3 };
  const refreshed = await adapter.listSessions();
  assert.deepEqual(
    refreshed.find((session) => session.id === 'session-goal-list-live').goal,
    { objective: 'Ship the list goal', phase: 'paused', roundsStarted: 9, maxGoalRounds: 50 },
  );
});

test('listSessions includes durable goal projections for non-live sessions', async () => {
  const goalProjection = {
    goal: {
      id: 'goal-projected',
      revision: 2,
      objective: '  Cold projected goal  ',
      phase: 'paused',
      maxGoalRounds: 40,
    },
    roundsStarted: 3,
    createdAt: T(1000),
    updatedAt: T(2000),
  };
  const items = [
    {
      sessionId: 'session-goal-list-projected',
      updatedAt: T(5000),
      running: false,
      agentAvailable: false,
      cwd: 'C:\\work',
      projections: { asOfSeq: 12, values: { goal: goalProjection } },
    },
    {
      sessionId: 'session-goal-list-projected-live',
      updatedAt: T(4000),
      running: false,
      agentAvailable: true,
      cwd: 'C:\\work',
      projections: { asOfSeq: 13, values: { goal: goalProjection } },
    },
    {
      sessionId: 'session-goal-list-no-goal-projection',
      updatedAt: T(3000),
      running: false,
      agentAvailable: false,
      cwd: 'C:\\work',
      projections: { asOfSeq: 4, values: { goal: null } },
    },
  ];
  const agentProbes = [];
  const ctx = createFakeCtx({
    sessionController: fakeSessionController({ items }),
    agents: {
      get: (id) => {
        agentProbes.push(id);
        return id === 'session-goal-list-projected-live' ? { id } : undefined;
      },
    },
    goals: {
      get: (agent) =>
        agent.id === 'session-goal-list-projected-live'
          ? {
              id: 'goal-live',
              revision: 1,
              objective: 'Live authoritative goal',
              phase: 'active',
              maxGoalRounds: 10,
              roundsStarted: 2,
              activation: 'armed',
              createdAt: T(1000),
              updatedAt: T(2000),
            }
          : undefined,
    },
  });
  const adapter = createDshAdapter({ ctx, config: testConfig(), logger: silent });

  const sessions = await adapter.listSessions();
  const byId = new Map(sessions.map((session) => [session.id, session]));
  assert.deepEqual(byId.get('session-goal-list-projected').goal, {
    objective: 'Cold projected goal',
    phase: 'paused',
    roundsStarted: 3,
    maxGoalRounds: 40,
  });
  assert.deepEqual(byId.get('session-goal-list-projected-live').goal, {
    objective: 'Live authoritative goal',
    phase: 'active',
    roundsStarted: 2,
    maxGoalRounds: 10,
  });
  assert.equal(byId.get('session-goal-list-no-goal-projection').goal, null);
  assert.equal(
    agentProbes.includes('session-goal-list-projected'),
    false,
    'cold sessions are summarized from the projection without touching the agent registry',
  );

  // The warm cached-list path keeps the projection-derived goal too.
  const warm = await adapter.listSessions();
  assert.deepEqual(warm.find((row) => row.id === 'session-goal-list-projected').goal, {
    objective: 'Cold projected goal',
    phase: 'paused',
    roundsStarted: 3,
    maxGoalRounds: 40,
  });
});

test('listSessions goal enrichment falls back to the projection when live goals fail', async () => {
  const projection = {
    goal: {
      id: 'goal-fallback',
      revision: 1,
      objective: 'Projection fallback goal',
      phase: 'blocked',
      maxGoalRounds: 12,
    },
    roundsStarted: 4,
    createdAt: T(1000),
    updatedAt: T(2000),
  };
  const items = [
    {
      sessionId: 'session-goal-list-enrich',
      updatedAt: T(1000),
      running: false,
      agentAvailable: true,
      cwd: 'C:\\work',
      projections: { asOfSeq: 8, values: { goal: projection } },
    },
    { sessionId: 'session-goal-list-noproj', updatedAt: T(500), running: false, agentAvailable: true, cwd: 'C:\\work' },
  ];
  const noService = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({ items }),
      agents: { get: () => ({ id: 'session-goal-list-enrich' }) },
    }),
    config: testConfig(),
    logger: silent,
  });
  const rows = await noService.listSessions();
  const byId = new Map(rows.map((row) => [row.id, row]));
  assert.deepEqual(byId.get('session-goal-list-enrich').goal, {
    objective: 'Projection fallback goal',
    phase: 'blocked',
    roundsStarted: 4,
    maxGoalRounds: 12,
  });
  assert.equal(byId.get('session-goal-list-noproj').goal, null);

  const failing = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({ items }),
      agents: { get: () => ({ id: 'session-goal-list-enrich' }) },
      goals: {
        get() {
          throw new Error('goal agent is not live');
        },
      },
    }),
    config: testConfig(),
    logger: silent,
  });
  const failedRows = await failing.listSessions();
  assert.deepEqual(
    failedRows.find((row) => row.id === 'session-goal-list-enrich').goal,
    {
      objective: 'Projection fallback goal',
      phase: 'blocked',
      roundsStarted: 4,
      maxGoalRounds: 12,
    },
    'goal read errors fall back to the durable projection',
  );
  assert.equal(failedRows.find((row) => row.id === 'session-goal-list-noproj').goal, null);
});

test('listSessions reports unsupported when the session controller is absent', async () => {
  const adapter = makeAdapter(createFakeCtx({}));
  await assert.rejects(
    () => adapter.listSessions(),
    (error) => error instanceof ApiError && error.code === 'unsupported' && error.status === 501,
  );
});

test('getSession maps messages and paginates by before/limit', async () => {
  const ctx = createFakeCtx({
    sessionController: fakeSessionController({ items: SESSION_ITEMS }),
    sessionQuery: fakeSessionQuery({ snapshots: SNAPSHOTS }),
    workspaceRegistry: fakeWorkspaceRegistry(WORKSPACES),
  });
  const adapter = makeAdapter(ctx);

  const detail = await adapter.getSession('s_1', { limit: '2' });
  assert.deepEqual(detail.messages.map((message) => message.id), ['m_a1', 'm_a2']);
  assert.equal(detail.nextCursor, 'm_a1');

  const older = await adapter.getSession('s_1', { limit: '5', before: 'm_a1' });
  assert.deepEqual(older.messages.map((message) => message.id), ['m_user_1']);
  assert.equal(older.nextCursor, null);

  const one = await adapter.getSession('s_1', { limit: '1' });
  assert.deepEqual(one.messages.map((message) => message.id), ['m_a2']);

  await assert.rejects(
    () => adapter.getSession('s_1', { before: 'nope' }),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );
});

test('getSession returns not_found for unknown sessions', async () => {
  const ctx = createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots: SNAPSHOTS }) });
  const adapter = makeAdapter(ctx);
  await assert.rejects(
    () => adapter.getSession('s_missing'),
    (error) => error instanceof ApiError && error.code === 'not_found' && error.status === 404,
  );
});

test('prompt sends the exact DSH SessionPromptRequest the controller expects', async () => {
  const controller = fakeSessionController({ items: SESSION_ITEMS });
  const ctx = createFakeCtx({ sessionController: controller });
  const adapter = makeAdapter(ctx);

  const result = await adapter.prompt('s_1', 'Run the release');
  assert.equal(result.accepted, true);
  assert.match(result.runId, /^run_[0-9a-f]{20}$/);

  const call = controller.calls.at(-1);
  assert.equal(call.method, 'prompt');
  assert.equal(call.request.sessionId, 's_1');
  assert.equal(call.request.mode, 'queue');
  assert.match(call.request.requestId, /^req_[0-9a-f]{32}$/);
  assert.deepEqual(call.request.content, [{ type: 'text', text: 'Run the release' }]);
});

test('prompt maps DSH RemoteError codes onto mobile API codes', async () => {
  const notFound = makeAdapter(
    createFakeCtx({
      sessionController: fakeSessionController({
        prompt() {
          const error = new Error('missing');
          error.code = 'session/not-found';
          throw error;
        },
      }),
    }),
  );
  await assert.rejects(
    () => notFound.prompt('s_missing', 'hi'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  const internal = makeAdapter(
    createFakeCtx({
      sessionController: fakeSessionController({
        prompt() {
          throw new Error('provider exploded with a secret key');
        },
      }),
    }),
  );
  await assert.rejects(
    () => internal.prompt('s_1', 'hi'),
    (error) => error instanceof ApiError && error.code === 'internal' && !error.message.includes('secret'),
  );
});

test('interrupt and createSession forward their requests', async () => {
  const controller = fakeSessionController({ items: SESSION_ITEMS });
  const ctx = createFakeCtx({
    sessionController: controller,
    sessionQuery: fakeSessionQuery({ snapshots: SNAPSHOTS }),
    workspaceRegistry: fakeWorkspaceRegistry(WORKSPACES),
  });
  const adapter = makeAdapter(ctx);

  assert.deepEqual(await adapter.interrupt('s_1'), { ok: true });
  assert.deepEqual(controller.calls.at(-1), { method: 'cancel', request: { sessionId: 's_1' } });

  const created = await adapter.createSession({ workspaceId: 'ws_1', preset: 'cordis' });
  assert.equal(created.id, 's_new');
  assert.deepEqual(controller.calls.find((call) => call.method === 'create'), {
    method: 'create',
    request: { workspaceId: 'ws_1', agentPreset: 'cordis' },
  });
});

test('hostInfo reports name, version, apiVersion and workspaces', async () => {
  const ctx = createFakeCtx({ workspaceRegistry: fakeWorkspaceRegistry(WORKSPACES) });
  const adapter = makeAdapter(ctx);
  const host = await adapter.hostInfo();
  assert.equal(host.name.length > 0, true);
  assert.equal(host.version, '0.3.13');
  assert.equal(host.apiVersion, 1);
  assert.deepEqual(host.workspaces, [{ id: 'ws_1', name: 'DeepSeek Harness', path: 'C:\\work' }]);
});

test('attach turns DSH events into contract SSE envelopes and stops on dispose', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 100 });
  const ctx = createFakeCtx({
    sessionController: fakeSessionController({ items: SESSION_ITEMS }),
    sessionQuery: fakeSessionQuery({ snapshots: SNAPSHOTS }),
    workspaceRegistry: fakeWorkspaceRegistry(WORKSPACES),
  });
  const adapter = makeAdapter(ctx, bus);
  const dispose = adapter.attach();

  // user message → message.added
  ctx.emit('session/event', { id: 's_1' }, EVENTS[0]);
  let last = bus.buffer.at(-1);
  assert.equal(last.type, 'message.added');
  assert.equal(last.sessionId, 's_1');
  assert.equal(last.data.sessionId, 's_1');
  assert.equal(last.data.message.id, 'm_user_1');
  assert.equal(last.data.message.role, 'user');

  // assistant stream → deltas keyed by the stream id, then completed
  ctx.emit('agent/assistant-stream', { agent: { id: 's_1' }, frame: { type: 'start', attemptId: 'att_1' } });
  ctx.emit('agent/assistant-stream', { agent: { id: 's_1' }, frame: { type: 'chunk', attemptId: 'att_1', chunk: { type: 'text-delta', text: 'Hel' } } });
  ctx.emit('agent/assistant-stream', { agent: { id: 's_1' }, frame: { type: 'chunk', attemptId: 'att_1', chunk: { type: 'text-delta', text: 'lo' } } });
  const deltas = bus.buffer.filter((event) => event.type === 'message.delta');
  assert.deepEqual(deltas.map((event) => event.data), [
    { sessionId: 's_1', messageId: 'm_att_1', text: 'Hel' },
    { sessionId: 's_1', messageId: 'm_att_1', text: 'lo' },
  ]);
  ctx.emit('agent/assistant-stream', { agent: { id: 's_1' }, frame: { type: 'end', attemptId: 'att_1' } });
  last = bus.buffer.at(-1);
  assert.equal(last.type, 'message.completed');
  assert.deepEqual(last.data, { sessionId: 's_1', messageId: 'm_att_1' });

  // tool call/result upsert the same message row
  ctx.emit('session/event', { id: 's_1' }, {
    type: 'tool/call',
    seq: 8,
    time: T(8000),
    data: { callId: 'call_9', name: 'Bash', arguments: '{"command":"ls"}' },
  });
  const running = bus.buffer.at(-1).data.message;
  assert.equal(running.id, 'm_call_9');
  assert.equal(running.streaming, true);
  assert.equal(running.tool.state, 'running');

  ctx.emit('session/event', { id: 's_1' }, {
    type: 'tool/result',
    seq: 9,
    time: T(9000),
    data: { message: { id: 'm_durable', toolCallId: 'call_9', content: [] } },
  });
  const finished = bus.buffer.at(-1).data.message;
  assert.equal(finished.id, 'm_call_9');
  assert.equal(finished.streaming, false);
  assert.deepEqual(finished.tool, { name: 'Bash', detail: 'ls', state: 'done' });

  // session removal
  ctx.emit('api-session/removed', 's_1');
  last = bus.buffer.at(-1);
  assert.equal(last.type, 'session.removed');
  assert.deepEqual(last.data, { sessionId: 's_1' });

  const before = bus.buffer.length;
  dispose();
  assert.equal(ctx.listenerCount('session/event'), 0);
  ctx.emit('session/event', { id: 's_1' }, EVENTS[0]);
  assert.equal(bus.buffer.length, before, 'listeners are removed on dispose');
});

test('live tool/result SSE mapping includes capped text output', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 100 });
  const ctx = createFakeCtx({
    sessionController: fakeSessionController({ items: SESSION_ITEMS }),
    sessionQuery: fakeSessionQuery({ snapshots: SNAPSHOTS }),
    workspaceRegistry: fakeWorkspaceRegistry(WORKSPACES),
  });
  const adapter = makeAdapter(ctx, bus);
  const dispose = adapter.attach();

  ctx.emit('session/event', { id: 's_1' }, {
    type: 'tool/call',
    seq: 30,
    time: T(30_000),
    data: { callId: 'call_out', name: 'Bash', arguments: '{"command":"ls"}' },
  });
  ctx.emit('session/event', { id: 's_1' }, {
    type: 'tool/result',
    seq: 31,
    time: T(31_000),
    data: {
      message: {
        id: 'm_out',
        toolCallId: 'call_out',
        content: [
          { type: 'text', text: 'line one\nline two' },
          { type: 'image', attachment: { attachmentId: 'att_live', mediaType: 'image/png', bytes: 9, name: 'shot.png' } },
        ],
      },
    },
  });
  let message = bus.buffer.at(-1).data.message;
  assert.equal(message.id, 'm_call_out');
  assert.equal(message.text, '', 'result text is not promoted to message.text');
  assert.deepEqual(message.tool, {
    name: 'Bash',
    detail: 'ls',
    state: 'done',
    output: 'line one\nline two',
  });
  assert.equal(message.attachments[0].id, 'att_live');

  // Same 8,000-char cap + marker on the live path.
  ctx.emit('session/event', { id: 's_1' }, {
    type: 'tool/call',
    seq: 32,
    time: T(32_000),
    data: { callId: 'call_long', name: 'Pwsh', arguments: '{}' },
  });
  ctx.emit('session/event', { id: 's_1' }, {
    type: 'tool/result',
    seq: 33,
    time: T(33_000),
    data: { message: { id: 'm_long', toolCallId: 'call_long', content: [{ type: 'text', text: 'z'.repeat(8005) }] } },
  });
  message = bus.buffer.at(-1).data.message;
  assert.equal(message.tool.output.length, 8000 + '… (output truncated)'.length);
  assert.equal(message.tool.output.slice(0, 8000), 'z'.repeat(8000));
  assert.equal(message.tool.output.endsWith('… (output truncated)'), true);

  // Image-only result: attachments only, no output.
  ctx.emit('session/event', { id: 's_1' }, {
    type: 'tool/call',
    seq: 34,
    time: T(34_000),
    data: { callId: 'call_image', name: 'Screenshot', arguments: '{}' },
  });
  ctx.emit('session/event', { id: 's_1' }, {
    type: 'tool/result',
    seq: 35,
    time: T(35_000),
    data: {
      message: {
        id: 'm_image',
        toolCallId: 'call_image',
        content: [{ type: 'image', attachment: { attachmentId: 'att_only', mediaType: 'image/png', bytes: 3, name: 'only.png' } }],
      },
    },
  });
  message = bus.buffer.at(-1).data.message;
  assert.equal('output' in message.tool, false);
  assert.equal(message.attachments[0].id, 'att_only');
  dispose();
});

test('approval and question answering are on by default', async () => {
  const adapter = makeAdapter(createFakeCtx({}));
  assert.deepEqual(await adapter.listApprovals(), { approvals: [], supported: true, readOnly: false });
  await assert.rejects(
    () => adapter.resolveApproval('ap_1', 'allow'),
    (error) => error instanceof ApiError && error.code === 'not_found' && error.status === 404,
  );
  assert.deepEqual(await adapter.listQuestions(), { questions: [], supported: true, readOnly: false });
  await assert.rejects(
    () => adapter.answerQuestion('q_1', 'yes'),
    (error) => error instanceof ApiError && error.code === 'not_found' && error.status === 404,
  );

  const readOnly = createDshAdapter({
    ctx: createFakeCtx({}),
    config: testConfig({ approvals: { mobileAnswer: false }, questions: { mobileAnswer: false } }),
    bus: null,
    logger: silent,
  });
  assert.deepEqual(await readOnly.listApprovals(), { approvals: [], supported: true, readOnly: true });
  assert.deepEqual(await readOnly.listQuestions(), { questions: [], supported: true, readOnly: true });
  await assert.rejects(
    () => readOnly.resolveApproval('ap_1', 'allow'),
    (error) => error instanceof ApiError && error.code === 'unsupported' && /read-only/i.test(error.message),
  );
  await assert.rejects(
    () => readOnly.answerQuestion('q_1', 'yes'),
    (error) => error instanceof ApiError && error.code === 'unsupported' && /read-only/i.test(error.message),
  );
});

test('approval mirror lists pending requests, publishes lifecycle events and forwards outcomes', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 50 });
  const ctx = createFakeCtx({});
  const adapter = createDshAdapter({ ctx, config: testConfig(), bus, logger: silent });
  const dispose = adapter.attach();
  const handler = ctx.listenersFor('approval/request')[0];
  assert.equal(typeof handler, 'function', 'adapter registers one pass-through waterfall listener');

  // The command line is recovered from the tool/call event the adapter tracks.
  ctx.emit(
    'session/event',
    { id: 'session-approval-1' },
    {
      type: 'tool/call',
      seq: 4,
      time: T(1000),
      data: { callId: 'call_9', name: 'Bash', arguments: '{"command":"pwsh -File tools/publish.mjs --patch"}' },
    },
  );

  let resolveNext;
  const outcomePromise = handler(
    {
      agent: { id: 'session-approval-1' },
      toolName: 'Bash',
      callId: 'call_9',
      displayReason: { en: 'Publish the release?' },
    },
    () => new Promise((resolve) => {
      resolveNext = resolve;
    }),
  );

  const pending = await adapter.listApprovals();
  assert.equal(pending.supported, true);
  assert.equal(pending.readOnly, false);
  assert.equal(pending.approvals.length, 1);
  const [approval] = pending.approvals;
  assert.match(approval.id, /^ap_[0-9a-f-]{36}$/);
  assert.equal(approval.sessionId, 'session-approval-1');
  assert.equal(approval.title, 'Permission needed: Bash');
  assert.equal(approval.description, 'Publish the release?');
  assert.equal(approval.command, 'pwsh -File tools/publish.mjs --patch');
  assert.match(approval.createdAt, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);

  const requested = bus.buffer.find((event) => event.type === 'approval.requested');
  assert.deepEqual(requested.data, approval);
  assert.equal(requested.sessionId, 'session-approval-1');

  resolveNext('allowed-once');
  assert.equal(await outcomePromise, 'allowed-once', 'next() outcome is returned unchanged');
  assert.deepEqual((await adapter.listApprovals()).approvals, []);
  const resolved = bus.buffer.at(-1);
  assert.equal(resolved.type, 'approval.resolved');
  assert.deepEqual(resolved.data, { approvalId: approval.id });
  assert.equal(resolved.sessionId, 'session-approval-1');
  dispose();
});

test('approval mirror propagates rejection, skips hidden sessions and clears on dispose', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 50 });
  const ctx = createFakeCtx({});
  const adapter = createDshAdapter({ ctx, config: testConfig(), bus, logger: silent });
  const dispose = adapter.attach();
  const handler = ctx.listenersFor('approval/request')[0];

  const desktopError = new Error('desktop rejected the request');
  let rejectNext;
  const rejection = handler(
    { agent: { id: 'session-approval-2' }, toolName: 'Bash' },
    () => new Promise((_, reject) => {
      rejectNext = reject;
    }),
  );
  assert.equal((await adapter.listApprovals()).approvals.length, 1);
  rejectNext(desktopError);
  await assert.rejects(() => rejection, (error) => error === desktopError);
  assert.equal((await adapter.listApprovals()).approvals.length, 0, 'rejection also clears the pending row');
  assert.equal(bus.buffer.at(-1).type, 'approval.resolved');

  // Hidden/internal sessions are never mirrored (desktop-only request stays out
  // of the phone list/SSE), but the waterfall outcome still passes through.
  const eventsBefore = bus.buffer.length;
  const hiddenOutcome = await handler(
    { agent: { id: '351ca07c-6d26-46a8-a685-cb627d642806' }, toolName: 'Bash' },
    async () => 'rejected',
  );
  assert.equal(hiddenOutcome, 'rejected');
  assert.equal(bus.buffer.length, eventsBefore);
  assert.equal((await adapter.listApprovals()).approvals.length, 0);

  let releaseNext;
  void handler(
    { agent: { id: 'session-approval-3' }, toolName: 'Bash' },
    () => new Promise((resolve) => {
      releaseNext = resolve;
    }),
  );
  assert.equal((await adapter.listApprovals()).approvals.length, 1);
  dispose();
  assert.equal((await adapter.listApprovals()).approvals.length, 0, 'dispose clears the pending mirror');
  releaseNext?.('cancelled');
});

test('approval mirror answers from the phone (first answer wins) through the adapter', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 50 });
  const ctx = createFakeCtx({});
  const adapter = createDshAdapter({ ctx, config: testConfig(), bus, logger: silent });
  const dispose = adapter.attach();
  const handler = ctx.listenersFor('approval/request')[0];

  let resolveDesktop;
  const outcomePromise = handler(
    { agent: { id: 'session-approval-mobile' }, toolName: 'Bash' },
    () => new Promise((resolve) => {
      resolveDesktop = resolve;
    }),
  );
  const [approval] = (await adapter.listApprovals()).approvals;
  assert.match(approval.id, /^ap_/);

  const answered = await adapter.resolveApproval(approval.id, 'deny');
  assert.deepEqual(answered, { ok: true, approvalId: approval.id, outcome: 'rejected' });
  assert.equal(await outcomePromise, 'rejected', 'the mobile outcome claims the waterfall');
  assert.deepEqual((await adapter.listApprovals()).approvals, []);
  assert.equal(bus.buffer.filter((event) => event.type === 'approval.resolved').length, 1);

  await assert.rejects(
    () => adapter.resolveApproval(approval.id, 'allow'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  // The desktop answerer settles later; the resolution is not duplicated.
  resolveDesktop('allowed-once');
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(bus.buffer.filter((event) => event.type === 'approval.resolved').length, 1);
  dispose();
});

test('question mirror lists pending rows, publishes lifecycle events and forwards outcomes', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 50 });
  const ctx = createFakeCtx({});
  const adapter = createDshAdapter({ ctx, config: testConfig(), bus, logger: silent });
  const dispose = adapter.attach();
  const handler = ctx.listenersFor('user-questions/request')[0];
  assert.equal(typeof handler, 'function', 'adapter registers one pass-through question listener');

  let resolveNext;
  const outcomePromise = handler(
    {
      agent: { id: 'session-question-1' },
      questions: [
        {
          id: 'approach',
          header: 'Approach',
          question: 'Which approach should I take?',
          options: [{ label: 'Ship now' }, { label: 'Wait for the page' }],
        },
        { id: 'confirm', question: 'Is the summary correct?', detail: 'Check the diff first.' },
      ],
    },
    () => new Promise((resolve) => {
      resolveNext = resolve;
    }),
  );

  const pending = await adapter.listQuestions();
  assert.equal(pending.supported, true);
  assert.equal(pending.readOnly, false);
  assert.equal(pending.questions.length, 2);
  const [first, second] = pending.questions;
  assert.match(first.id, /^q_[0-9a-f-]{36}:0$/);
  assert.match(second.id, /^q_[0-9a-f-]{36}:1$/);
  assert.equal(first.sessionId, 'session-question-1');
  assert.equal(first.prompt, 'Approach: Which approach should I take?');
  assert.deepEqual(first.options, ['Ship now', 'Wait for the page']);
  assert.equal(second.prompt, 'Is the summary correct?\nCheck the diff first.');
  assert.deepEqual(second.options, []);

  const requested = bus.buffer.filter((event) => event.type === 'question.requested');
  assert.equal(requested.length, 2);
  assert.deepEqual(requested[0].data, first);
  assert.equal(requested[0].sessionId, 'session-question-1');

  const answer = { answers: [{ id: 'approach', selected: ['Ship now'] }] };
  resolveNext(answer);
  assert.equal(await outcomePromise, answer, 'next() outcome is returned unchanged');
  assert.deepEqual((await adapter.listQuestions()).questions, []);
  const resolved = bus.buffer.filter((event) => event.type === 'question.resolved');
  assert.deepEqual(
    resolved.map((event) => event.data),
    [{ questionId: first.id }, { questionId: second.id }],
  );
  dispose();
});

test('question mirror propagates rejection, skips hidden sessions and clears on dispose', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 50 });
  const ctx = createFakeCtx({});
  const adapter = createDshAdapter({ ctx, config: testConfig(), bus, logger: silent });
  const dispose = adapter.attach();
  const handler = ctx.listenersFor('user-questions/request')[0];

  const desktopError = new Error('desktop cancelled the question');
  let rejectNext;
  const rejection = handler(
    { agent: { id: 'session-question-2' }, questions: [{ id: 'x', question: 'Proceed?' }] },
    () => new Promise((_, reject) => {
      rejectNext = reject;
    }),
  );
  assert.equal((await adapter.listQuestions()).questions.length, 1);
  rejectNext(desktopError);
  await assert.rejects(() => rejection, (error) => error === desktopError);
  assert.equal((await adapter.listQuestions()).questions.length, 0, 'rejection also clears the pending row');
  assert.equal(bus.buffer.at(-1).type, 'question.resolved');

  // Hidden/internal sessions are pass-through only (no list/SSE mirroring).
  const eventsBefore = bus.buffer.length;
  const hiddenAnswer = { answers: [{ id: 'x', selected: [] }] };
  const hiddenOutcome = await handler(
    {
      agent: { id: '351ca07c-6d26-46a8-a685-cb627d642806' },
      questions: [{ id: 'x', question: 'Hidden question?' }],
    },
    async () => hiddenAnswer,
  );
  assert.deepEqual(hiddenOutcome, hiddenAnswer);
  assert.equal(bus.buffer.length, eventsBefore);
  assert.equal((await adapter.listQuestions()).questions.length, 0);

  let releaseNext;
  void handler(
    { agent: { id: 'session-question-3' }, questions: [{ id: 'y', question: 'Still pending?' }] },
    () => new Promise((resolve) => {
      releaseNext = resolve;
    }),
  );
  assert.equal((await adapter.listQuestions()).questions.length, 1);
  dispose();
  assert.equal((await adapter.listQuestions()).questions.length, 0, 'dispose clears the pending mirror');
  releaseNext?.({ answers: [] });
});

test('question mirror answers a whole batch from the phone through the adapter', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 50 });
  const ctx = createFakeCtx({});
  const adapter = createDshAdapter({ ctx, config: testConfig(), bus, logger: silent });
  const dispose = adapter.attach();
  const handler = ctx.listenersFor('user-questions/request')[0];

  let resolveDesktop;
  const outcomePromise = handler(
    {
      agent: { id: 'session-question-mobile' },
      questions: [
        { id: 'approach', question: 'Pick one', options: [{ label: 'Ship now' }, { label: 'Wait' }] },
        { id: 'name', question: 'What should I call it?' },
      ],
    },
    () => new Promise((resolve) => {
      resolveDesktop = resolve;
    }),
  );

  const [first, second] = (await adapter.listQuestions()).questions;
  assert.equal((await adapter.listQuestions()).readOnly, false);

  const partial = await adapter.answerQuestion(first.id, 'Ship now', 0);
  assert.deepEqual(partial, { ok: true, questionId: first.id, answered: 1, total: 2, complete: false });
  assert.equal((await adapter.listQuestions()).questions.length, 1);

  const completed = await adapter.answerQuestion(second.id, '  Release Candidate  ');
  assert.deepEqual(completed, { ok: true, questionId: second.id, answered: 2, total: 2, complete: true });
  assert.deepEqual(await outcomePromise, {
    answers: [
      { id: 'approach', selected: ['Ship now'] },
      { id: 'name', selected: [], custom: 'Release Candidate' },
    ],
  });
  assert.equal(bus.buffer.filter((event) => event.type === 'question.resolved').length, 2);
  await assert.rejects(
    () => adapter.answerQuestion(first.id, 'Wait', 1),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  // The desktop answerer settles later; no duplicate resolution/events.
  resolveDesktop({ answers: [] });
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(bus.buffer.filter((event) => event.type === 'question.resolved').length, 2);
  dispose();
});

test('sessionExists resolves live and persisted sessions without activating', async () => {
  const query = fakeSessionQuery({ snapshots: SNAPSHOTS });
  const adapter = makeAdapter(createFakeCtx({ sessionQuery: query }));
  assert.equal(await adapter.sessionExists('s_1'), true);
  assert.equal(await adapter.sessionExists('s_missing'), false);

  const listed = makeAdapter(createFakeCtx({ sessionController: fakeSessionController({ items: SESSION_ITEMS }) }));
  assert.equal(await listed.sessionExists('s_2'), true, 'falls back to the visible session list');
  assert.equal(await listed.sessionExists('nope'), false);

  const bare = makeAdapter(createFakeCtx({}));
  assert.equal(await bare.sessionExists('whatever'), true, 'no history service defers to prompt admission');
});

test('uploadAttachment saves images through attachments.saveImage', async () => {
  const attachments = fakeAttachments();
  const adapter = makeAdapter(createFakeCtx({ attachments }));
  const data = Buffer.from([0x89, 0x50, 0x4e, 0x47]);
  const result = await adapter.uploadAttachment({
    sessionId: 's_1',
    name: 'shot.png',
    mimeType: 'image/png',
    kind: 'image',
    data,
    dataBase64: data.toString('base64'),
  });
  assert.equal(result.receiptId, null);
  assert.equal(result.ref.attachmentId, 'img_01');
  assert.equal(attachments.calls.length, 1);
  assert.deepEqual(attachments.calls[0].input, { data, mediaType: 'image/png', name: 'shot.png' });
});

test('uploadAttachment maps image policy refusals and missing capabilities', async () => {
  const rejecting = fakeAttachments({ saveImageError: attachmentError('Image exceeds the 10 MB limit', 'IMAGE_TOO_LARGE') });
  const adapter = makeAdapter(createFakeCtx({ attachments: rejecting }));
  await assert.rejects(
    () =>
      adapter.uploadAttachment({
        sessionId: 's_1',
        name: 'big.png',
        mimeType: 'image/png',
        kind: 'image',
        data: Buffer.from([1]),
        dataBase64: 'AQ==',
      }),
    (error) => error instanceof ApiError && error.code === 'invalid_request' && error.message.includes('10 MB'),
  );

  const noAttachmentService = makeAdapter(createFakeCtx({}));
  await assert.rejects(
    () =>
      noAttachmentService.uploadAttachment({
        sessionId: 's_1',
        name: 'a.png',
        mimeType: 'image/png',
        kind: 'image',
        data: Buffer.from([1]),
        dataBase64: 'AQ==',
      }),
    (error) => error instanceof ApiError && error.code === 'unsupported',
  );

  const wrongImageType = makeAdapter(createFakeCtx({ attachments: fakeAttachments() }));
  await assert.rejects(
    () =>
      wrongImageType.uploadAttachment({
        sessionId: 's_1',
        name: 'a.svg',
        mimeType: 'image/svg+xml',
        kind: 'image',
        data: Buffer.from([1]),
        dataBase64: 'AQ==',
      }),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );
});

test('uploadAttachment stores files and stages a prompt receipt', async () => {
  const attachments = fakeAttachments();
  const fileUploads = fakeFileUploads();
  const adapter = makeAdapter(createFakeCtx({ attachments, fileUploads }));
  const data = Buffer.from('%PDF-1.4\n');
  const result = await adapter.uploadAttachment({
    sessionId: 's_1',
    name: 'report.pdf',
    mimeType: 'application/pdf',
    kind: 'file',
    data,
    dataBase64: data.toString('base64'),
  });
  assert.equal(result.receiptId, 'receipt_01');
  assert.equal(result.ref.attachmentId, 'file_01');
  assert.deepEqual(attachments.calls.map((call) => call.method), ['admitEncodedFile']);
  assert.equal(attachments.calls[0].input.data, data.toString('base64'));
  const upload = fileUploads.calls.at(-1);
  assert.equal(upload.request.sessionId, 's_1');
  assert.equal(upload.request.name, 'report.pdf');
  assert.equal(upload.bytes, data.length);
});

test('uploadAttachment refuses files when the file-upload capability is missing', async () => {
  const adapter = makeAdapter(createFakeCtx({ attachments: fakeAttachments() }));
  await assert.rejects(
    () =>
      adapter.uploadAttachment({
        sessionId: 's_1',
        name: 'a.txt',
        mimeType: 'text/plain',
        kind: 'file',
        data: Buffer.from('x'),
        dataBase64: 'eA==',
      }),
    (error) => error instanceof ApiError && error.code === 'unsupported',
  );
});

test('prompt forwards prompt content parts unchanged', async () => {
  const controller = fakeSessionController();
  const adapter = makeAdapter(createFakeCtx({ sessionController: controller }));
  const content = [
    { type: 'text', text: 'Summarise this' },
    { type: 'file', receiptId: 'receipt_01' },
    { type: 'image', mediaType: 'image/png', data: 'AQ==' },
  ];
  await adapter.prompt('s_1', content);
  assert.deepEqual(controller.calls.at(-1).request.content, content);
  await assert.rejects(
    () => adapter.prompt('s_1', []),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );
});

/* ------------------------------------------------------------------------- */
/* Internal (subagent/teammate) session hiding — task-13                       */
/* ------------------------------------------------------------------------- */

const USER_HEADER_1 = {
  version: 4,
  id: 'session-11111111-1111-4111-8111-111111111111',
  createdAt: T(0),
  cwd: 'C:\\work',
  isSeeded: false,
  delegationDepth: 0,
};

/** Live subagent header shape (decompressed from ~/.dsh/sessions). */
const SUBAGENT_HEADER = {
  version: 4,
  id: '351ca07c-6d26-46a8-a685-cb627d642806',
  createdAt: T(0),
  cwd: 'C:\\work',
  parentSession: 'session-11111111-1111-4111-8111-111111111111',
  isSeeded: false,
  origin: 'subagent',
  delegationDepth: 1,
};

/** Mixed list rows: user sessions vs every internal marker variant. */
const MIXED_LIST_ROWS = [
  {
    sessionId: 'session-11111111-1111-4111-8111-111111111111',
    updatedAt: T(5000),
    running: false,
    agentAvailable: false,
    cwd: 'C:\\work',
    projections: { values: { subagent: {} } },
  },
  {
    sessionId: '351ca07c-6d26-46a8-a685-cb627d642806',
    updatedAt: T(4000),
    running: false,
    agentAvailable: false,
    cwd: 'C:\\work',
    origin: 'subagent',
    parentSessionId: 'session-11111111-1111-4111-8111-111111111111',
    projections: { values: { subagent: { identity: { mode: 'continuable', label: 'child 5' } } } },
  },
  {
    sessionId: 'session-defensive-child',
    updatedAt: T(3000),
    running: false,
    agentAvailable: false,
    cwd: 'C:\\work',
    parentSessionId: 'session-11111111-1111-4111-8111-111111111111',
  },
  {
    sessionId: 'session-projection-child',
    updatedAt: T(2000),
    running: false,
    agentAvailable: false,
    cwd: 'C:\\work',
    projections: { values: { subagent: { identity: { mode: 'one-shot' } } } },
  },
  {
    sessionId: '09b2a3c4-1111-4222-8333-444455556666',
    updatedAt: T(1000),
    running: false,
    agentAvailable: false,
    cwd: 'C:\\work',
  },
  {
    sessionId: 'session-user-2',
    updatedAt: T(500),
    running: false,
    agentAvailable: false,
    cwd: 'C:\\work',
    projections: { values: { subagent: {} } },
  },
];

/** Session query fixtures for the mixed rows. */
function mixedSnapshots() {
  /** @type {Record<string, any>} */
  const snapshots = {};
  for (const row of MIXED_LIST_ROWS) {
    const header = row.origin === 'subagent'
      ? { ...SUBAGENT_HEADER, id: row.sessionId }
      : row.parentSessionId !== undefined
        ? { ...USER_HEADER_1, id: row.sessionId, parentSession: row.parentSessionId, delegationDepth: 1 }
        : { ...USER_HEADER_1, id: row.sessionId };
    snapshots[row.sessionId] = { session: header, inheritedEventCount: 0, events: EVENTS };
  }
  return snapshots;
}

function mixedCtx() {
  return createFakeCtx({
    sessionController: fakeSessionController({ items: MIXED_LIST_ROWS }),
    sessionQuery: fakeSessionQuery({ snapshots: mixedSnapshots() }),
    workspaceRegistry: fakeWorkspaceRegistry([]),
  });
}

test('listSessions hides every internal-session marker by default', async () => {
  const adapter = createDshAdapter({ ctx: mixedCtx(), config: testConfig(), bus: null, logger: silent });
  const sessions = await adapter.listSessions();
  assert.deepEqual(
    sessions.map((session) => session.id),
    ['session-11111111-1111-4111-8111-111111111111', 'session-user-2'],
    'subagent origin/parent/projection/bare-UUID rows are filtered; top-level sessions stay',
  );
});

test('sessions.hideInternal=false returns every session (operator escape hatch)', async () => {
  const adapter = createDshAdapter({
    ctx: mixedCtx(),
    config: testConfig({ sessions: { hideInternal: false, cacheFile: false } }),
    bus: null,
    logger: silent,
  });
  const sessions = await adapter.listSessions();
  assert.equal(sessions.length, MIXED_LIST_ROWS.length);
  assert.equal(sessions.some((session) => session.id === '351ca07c-6d26-46a8-a685-cb627d642806'), true);
});

test('listSessions filters from the persisted cache shape (cold start) too', async () => {
  // Simulate a restarted host whose persisted payload still holds all rows:
  // the adapter must filter by the persisted hidden markers and the id heuristic.
  // (The shared test config disables the cache file, so the payload is injected
  // through the same code path a warm cache uses.)
  const adapter = createDshAdapter({ ctx: mixedCtx(), config: testConfig(), bus: null, logger: silent });
  const cold = await adapter.listSessions();
  const warm = await adapter.listSessions();
  assert.deepEqual(
    warm.map((session) => session.id),
    cold.map((session) => session.id),
  );
  assert.equal(warm.every((session) => session.id.startsWith('session-')), true);
});

test('SSE fan-out never forwards hidden sessions', async () => {
  const ctx = mixedCtx();
  const bus2 = new EventBus({ logger: silent, bufferSize: 100 });
  const adapter2 = createDshAdapter({ ctx, config: testConfig(), bus: bus2, logger: silent });
  const dispose2 = adapter2.attach();

  // 1) top-level session (session-* id, empty subagent projection) → forwarded
  ctx.emit('session/event', { id: 'session-11111111-1111-4111-8111-111111111111', header: USER_HEADER_1 }, EVENTS[0]);
  assert.equal(bus2.buffer.at(-1)?.type, 'message.added');
  const afterVisible = bus2.buffer.length;

  // 2) subagent sessions by header marker (even when the id looks top-level)
  ctx.emit(
    'session/event',
    { id: 'session-defensive-child', header: { ...USER_HEADER_1, id: 'session-defensive-child', parentSession: 'session-11111111-1111-4111-8111-111111111111', delegationDepth: 1 } },
    EVENTS[0],
  );
  // 3) live subagent Session object with its full header
  ctx.emit('session/event', { id: '351ca07c-6d26-46a8-a685-cb627d642806', header: SUBAGENT_HEADER }, EVENTS[0]);
  // 4) unknown bare-UUID id with no header: documented heuristic
  ctx.emit('session/event', { id: '09b2a3c4-1111-4222-8333-444455556666' }, EVENTS[0]);
  assert.equal(bus2.buffer.length, afterVisible, 'no hidden session event is published');

  // 5) assistant streams: hidden agent suppressed, visible agent forwarded
  ctx.emit('agent/assistant-stream', {
    agent: { id: '351ca07c-6d26-46a8-a685-cb627d642806', session: { header: SUBAGENT_HEADER } },
    frame: { type: 'start', attemptId: 'att_hidden' },
  });
  ctx.emit('agent/assistant-stream', {
    agent: { id: '351ca07c-6d26-46a8-a685-cb627d642806', session: { header: SUBAGENT_HEADER } },
    frame: { type: 'chunk', attemptId: 'att_hidden', chunk: { type: 'text-delta', text: 'secret' } },
  });
  assert.equal(bus2.buffer.some((event) => event.type === 'message.delta'), false);

  ctx.emit('agent/assistant-stream', {
    agent: { id: 'session-11111111-1111-4111-8111-111111111111', session: { header: USER_HEADER_1 } },
    frame: { type: 'start', attemptId: 'att_visible' },
  });
  ctx.emit('agent/assistant-stream', {
    agent: { id: 'session-11111111-1111-4111-8111-111111111111', session: { header: USER_HEADER_1 } },
    frame: { type: 'chunk', attemptId: 'att_visible', chunk: { type: 'text-delta', text: 'hi' } },
  });
  assert.equal(bus2.buffer.at(-1)?.type, 'message.delta');

  // 6) removal events: hidden suppressed, visible forwarded
  const beforeRemovals = bus2.buffer.length;
  ctx.emit('api-session/removed', '351ca07c-6d26-46a8-a685-cb627d642806');
  assert.equal(bus2.buffer.length, beforeRemovals);
  ctx.emit('api-session/removed', 'session-11111111-1111-4111-8111-111111111111');
  assert.equal(bus2.buffer.at(-1)?.type, 'session.removed');

  // 7) api-session/added with a subagent summary must not emit session.updated
  const beforeAdded = bus2.buffer.length;
  ctx.emit('api-session/added', MIXED_LIST_ROWS[1]);
  await new Promise((resolve) => setTimeout(resolve, 40));
  assert.equal(bus2.buffer.length, beforeAdded);

  dispose2();
});

test('session.updated is suppressed when the header reveals a late-added subagent', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 50 });
  const snapshots = {
    'session-late-child': {
      session: { ...USER_HEADER_1, id: 'session-late-child', parentSession: USER_HEADER_1.id, delegationDepth: 1 },
      inheritedEventCount: 0,
      events: EVENTS,
    },
  };
  const ctx = createFakeCtx({
    sessionQuery: fakeSessionQuery({ snapshots }),
    sessionController: fakeSessionController({ items: [] }),
  });
  const adapter = createDshAdapter({ ctx, config: testConfig(), bus, logger: silent });
  const dispose = adapter.attach();

  // The session is not in any list yet, so the first guard passes; reading its
  // header for the summary reveals the parentSession marker and it stays hidden.
  ctx.emit('api-session/status', 'session-late-child', true);
  await new Promise((resolve) => setTimeout(resolve, 40));
  assert.equal(bus.buffer.some((event) => event.type === 'session.updated'), false);

  dispose();
});

test('sessionAccess classifies existence and internal sessions from headers', async () => {
  const snapshots = mixedSnapshots();
  const adapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: testConfig(),
    logger: silent,
  });
  assert.deepEqual(await adapter.sessionAccess('session-11111111-1111-4111-8111-111111111111'), {
    exists: true,
    internal: false,
  });
  assert.deepEqual(await adapter.sessionAccess('351ca07c-6d26-46a8-a685-cb627d642806'), {
    exists: true,
    internal: true,
    reason: 'origin=subagent',
  });
  assert.deepEqual(await adapter.sessionAccess('session-defensive-child'), {
    exists: true,
    internal: true,
    reason: 'parentSession',
  });
  assert.deepEqual(await adapter.sessionAccess('session-user-2'), { exists: true, internal: false });
  assert.deepEqual(await adapter.sessionAccess('session-missing'), { exists: false, internal: false });

  // A brand-new live session that is not persisted yet: in-process store fallback.
  const liveHeader = { ...USER_HEADER_1, id: 'session-fresh' };
  const liveAdapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionQuery: fakeSessionQuery({ snapshots: {} }),
      sessions: { get: (id) => (id === 'session-fresh' ? { header: liveHeader } : undefined) },
    }),
    config: testConfig(),
    logger: silent,
  });
  assert.deepEqual(await liveAdapter.sessionAccess('session-fresh'), { exists: true, internal: false });
  assert.deepEqual(await liveAdapter.sessionAccess('session-absent'), { exists: false, internal: false });
});

test('file staging failures map to a documented 4xx and log the DSH error (defect O3)', async () => {
  const warnings = [];
  const capturingLogger = {
    info() {},
    warn: (...args) => warnings.push(args.map(String).join(' ')),
    error: (...args) => warnings.push(args.map(String).join(' ')),
  };
  const subagentError = new Error(
    'session "351ca07c-6d26-46a8-a685-cb627d642806" is a subagent session; use subagent delivery',
  );
  subagentError.code = 'session/agent-busy';
  const data = Buffer.from('%PDF-1.4\n');

  /** @param {any} uploadError */
  const adapterWithStagingError = (uploadError, options = {}) =>
    makeAdapter(
      createFakeCtx({
        attachments: fakeAttachments(),
        fileUploads: {
          async uploadStream() {
            throw uploadError;
          },
        },
      }),
      null,
      { logger: options.logger ?? silent },
    );

  const upload = (adapter, sessionId) =>
    adapter.uploadAttachment({
      sessionId,
      name: 'report.pdf',
      mimeType: 'application/pdf',
      kind: 'file',
      data,
      dataBase64: data.toString('base64'),
    });

  await assert.rejects(
    () => upload(adapterWithStagingError(subagentError, { logger: capturingLogger }), '351ca07c-6d26-46a8-a685-cb627d642806'),
    (error) =>
      error instanceof ApiError &&
      error.code === 'invalid_request' &&
      error.status === 400 &&
      /subagent/i.test(error.message) &&
      /parent session/i.test(error.message),
    'subagent staging failures must be a documented 400, not a 500',
  );
  assert.equal(warnings.some((line) => /file attachment staging failed/i.test(line)), true);
  assert.equal(warnings.some((line) => /session\/agent-busy/.test(line)), true);

  // Generic DSH failure: still 4xx (logged), never an opaque internal 500.
  await assert.rejects(
    () => upload(adapterWithStagingError(new Error('disk quota exceeded')), 'session-11111111-1111-4111-8111-111111111111'),
    (error) => error instanceof ApiError && error.code === 'invalid_request' && /could not stage/i.test(error.message),
  );

  // A not-found staging error keeps the documented not_found mapping.
  const notFoundError = new Error('session not found');
  notFoundError.code = 'session/not-found';
  await assert.rejects(
    () => upload(adapterWithStagingError(notFoundError), 'session-11111111-1111-4111-8111-111111111111'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
});

/* ------------------------------------------------------------------------- */
/* Persisted hidden list + stale-blank auto-hide — task-16                     */
/* ------------------------------------------------------------------------- */

const HIDDEN_A = 'session-hidden-a';
const HIDDEN_B = 'session-hidden-b';
const VISIBLE_C = 'session-visible-c';
const STALE_BLANK = 'session-stale-blank';
const FRESH_BLANK = 'session-fresh-blank';
const TITLED_OLD = 'session-titled-old';
const WORKING_BLANK = 'session-working-blank';

function userHeaderFor(id) {
  return { version: 4, id, createdAt: T(0), cwd: 'C:\\work', isSeeded: false, delegationDepth: 0 };
}

/** Build controller rows + query snapshots for one set of ids. */
function rowsFor(rowsSpec, { events = EVENTS } = {}) {
  const rows = [];
  const snapshots = {};
  for (const spec of rowsSpec) {
    rows.push({
      sessionId: spec.id,
      updatedAt: spec.updatedAt ?? T(1000),
      running: spec.running === true,
      agentAvailable: false,
      blank: spec.blank === true,
      cwd: 'C:\\work',
      ...(spec.title ? { projections: { values: { title: spec.title } } } : {}),
    });
    snapshots[spec.id] = {
      session: userHeaderFor(spec.id),
      inheritedEventCount: 0,
      events: spec.events ?? events,
    };
  }
  return { rows, snapshots };
}

test('listSessions excludes persisted hidden ids and skips their enrichment reads', async () => {
  const { rows, snapshots } = rowsFor([
    { id: HIDDEN_A, title: 'Hidden A', updatedAt: T(5000) },
    { id: VISIBLE_C, title: 'Visible C', updatedAt: T(4000) },
    { id: HIDDEN_B, title: 'Hidden B', updatedAt: T(3000) },
  ]);
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace([HIDDEN_A, HIDDEN_B]);
  const query = fakeSessionQuery({ snapshots });
  const ctx = createFakeCtx({
    sessionController: fakeSessionController({ items: rows }),
    sessionQuery: query,
    workspaceRegistry: fakeWorkspaceRegistry([]),
  });
  const adapter = createDshAdapter({ ctx, config: testConfig(), hiddenStore, logger: silent });

  const sessions = await adapter.listSessions();
  assert.deepEqual(sessions.map((session) => session.id), [VISIBLE_C]);

  // The background refresh enriches only visible sessions.
  const deadline = Date.now() + 2000;
  while (!query.calls.some((call) => call.method === 'readSession') && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  const enrichedIds = query.calls.filter((call) => call.method === 'readSession').map((call) => call.sessionId);
  assert.equal(enrichedIds.includes(HIDDEN_A), false);
  assert.equal(enrichedIds.includes(HIDDEN_B), false);
  assert.equal(enrichedIds.includes(VISIBLE_C), true);
});

test('stale blank sessions are auto-hidden; fresh, working and titled ones stay', async () => {
  const now = Date.now();
  const { rows, snapshots } = rowsFor(
    [
      { id: STALE_BLANK, updatedAt: now - 2 * 60 * 60 * 1000, blank: true, events: [] },
      { id: FRESH_BLANK, updatedAt: now - 60 * 1000, blank: true, events: [] },
      { id: TITLED_OLD, title: 'A real title', updatedAt: now - 5 * 60 * 60 * 1000, events: [] },
      { id: WORKING_BLANK, updatedAt: now - 3 * 60 * 60 * 1000, blank: true, running: true, events: [] },
      { id: VISIBLE_C, title: 'Visible C', updatedAt: now - 9 * 60 * 60 * 1000 },
    ],
  );
  const ctxServices = () => ({
    sessionController: fakeSessionController({ items: rows }),
    sessionQuery: fakeSessionQuery({ snapshots }),
    workspaceRegistry: fakeWorkspaceRegistry([]),
  });

  const adapter = createDshAdapter({ ctx: createFakeCtx(ctxServices()), config: testConfig(), logger: silent });
  const sessions = await adapter.listSessions();
  const ids = sessions.map((session) => session.id);
  assert.equal(ids.includes(STALE_BLANK), false, 'stale blank session auto-hidden');
  assert.equal(ids.includes(FRESH_BLANK), true, 'fresh blank session kept');
  assert.equal(ids.includes(TITLED_OLD), true, 'old but titled session kept');
  assert.equal(ids.includes(WORKING_BLANK), true, 'working session kept');

  // Auto-hiding is not persisted: the hidden store stays empty.
  assert.deepEqual(adapter._internal.hiddenStore?.getState().ids ?? [], []);
  assert.equal(adapter._internal.autoHiddenIds.has(STALE_BLANK), true);

  // hideBlankAfterMs: 0 disables the auto-hide rule.
  const disabled = createDshAdapter({
    ctx: createFakeCtx(ctxServices()),
    config: testConfig({ sessions: { cacheFile: false, hideBlankAfterMs: 0 } }),
    logger: silent,
  });
  assert.equal((await disabled.listSessions()).some((session) => session.id === STALE_BLANK), true);
});

test('SSE fan-out suppresses persisted hidden ids (but forwards visible ones)', async () => {
  const { rows, snapshots } = rowsFor([
    { id: HIDDEN_A, title: 'Hidden A', updatedAt: T(5000) },
    { id: VISIBLE_C, title: 'Visible C', updatedAt: T(4000) },
  ]);
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace([HIDDEN_A]);
  const bus = new EventBus({ logger: silent, bufferSize: 50 });
  const ctx = createFakeCtx({
    sessionController: fakeSessionController({ items: rows }),
    sessionQuery: fakeSessionQuery({ snapshots }),
    workspaceRegistry: fakeWorkspaceRegistry([]),
  });
  const adapter = createDshAdapter({ ctx, config: testConfig(), bus, hiddenStore, logger: silent });
  const dispose = adapter.attach();

  ctx.emit('session/event', { id: HIDDEN_A, header: userHeaderFor(HIDDEN_A) }, EVENTS[0]);
  assert.equal(bus.buffer.length, 0, 'hidden session event suppressed');

  ctx.emit('session/event', { id: VISIBLE_C, header: userHeaderFor(VISIBLE_C) }, EVENTS[0]);
  assert.equal(bus.buffer.at(-1)?.type, 'message.added');

  // Assistant streams from a hidden session are suppressed too.
  const before = bus.buffer.length;
  ctx.emit('agent/assistant-stream', {
    agent: { id: HIDDEN_A, session: { header: userHeaderFor(HIDDEN_A) } },
    frame: { type: 'start', attemptId: 'att_hidden_2' },
  });
  ctx.emit('agent/assistant-stream', {
    agent: { id: HIDDEN_A, session: { header: userHeaderFor(HIDDEN_A) } },
    frame: { type: 'chunk', attemptId: 'att_hidden_2', chunk: { type: 'text-delta', text: 'nope' } },
  });
  assert.equal(bus.buffer.length, before);

  dispose();
});

test('hidden sessions stay addressable: detail and prompt work', async () => {
  const { rows, snapshots } = rowsFor([{ id: HIDDEN_A, title: 'Hidden A', updatedAt: T(5000) }]);
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace([HIDDEN_A]);
  const controller = fakeSessionController({ items: rows });
  const ctx = createFakeCtx({
    sessionController: controller,
    sessionQuery: fakeSessionQuery({ snapshots }),
    workspaceRegistry: fakeWorkspaceRegistry([]),
  });
  const adapter = createDshAdapter({ ctx, config: testConfig(), hiddenStore, logger: silent });

  const detail = await adapter.getSession(HIDDEN_A);
  assert.equal(detail.session.id, HIDDEN_A);
  assert.equal(detail.messages.length > 0, true);

  const accepted = await adapter.prompt(HIDDEN_A, 'Hello hidden session');
  assert.deepEqual(accepted.accepted, true);
  assert.equal(controller.calls.at(-1).method, 'prompt');

  // Hidden is not "internal": attachment access must not be rejected.
  assert.deepEqual(await adapter.sessionAccess(HIDDEN_A), { exists: true, internal: false });
});

test('hidden list mtime changes reach the adapter list without a restart', async (t) => {
  const { tempHiddenFile } = await import('./helpers.mjs');
  const { utimes, writeFile } = await import('node:fs/promises');
  const hiddenFile = await tempHiddenFile(t);
  const hiddenStore = createHiddenStore({ filePath: hiddenFile, checkIntervalMs: 0 });
  await writeFile(hiddenFile, JSON.stringify({ updatedAt: new Date().toISOString(), ids: [HIDDEN_A] }), 'utf8');

  const { rows, snapshots } = rowsFor([
    { id: HIDDEN_A, title: 'Hidden A', updatedAt: T(5000) },
    { id: HIDDEN_B, title: 'Hidden B', updatedAt: T(4000) },
  ]);
  const ctx = createFakeCtx({
    sessionController: fakeSessionController({ items: rows }),
    sessionQuery: fakeSessionQuery({ snapshots }),
    workspaceRegistry: fakeWorkspaceRegistry([]),
  });
  const adapter = createDshAdapter({ ctx, config: testConfig(), hiddenStore, logger: silent });
  assert.deepEqual((await adapter.listSessions()).map((session) => session.id), [HIDDEN_B]);

  // Operator edits the file; the next list call re-reads it (mtime changed).
  await writeFile(hiddenFile, JSON.stringify({ updatedAt: new Date().toISOString(), ids: [HIDDEN_B] }), 'utf8');
  const later = new Date(Date.now() + 5000);
  await utimes(hiddenFile, later, later);
  assert.deepEqual((await adapter.listSessions()).map((session) => session.id), [HIDDEN_A]);
});

test('getUsage aggregates tokenUsage projections and ignores sessions without usage', async () => {
  const items = [
    {
      sessionId: 's_use_1',
      updatedAt: T(2000),
      projections: {
        values: {
          title: 'Ship the usage screen',
          tokenUsage: {
            totals: { uncachedInputTokens: 10, outputTokens: 4, cacheReadTokens: 100, cacheWriteTokens: 2 },
          },
        },
      },
    },
    {
      sessionId: 's_use_2',
      updatedAt: T(1000),
      projections: {
        values: {
          title: 'Second session',
          tokenUsage: {
            totals: { uncachedInputTokens: 5, outputTokens: 6, cacheReadTokens: 50, cacheWriteTokens: 0 },
          },
        },
      },
    },
    { sessionId: 's_no_usage', updatedAt: T(500), projections: { values: { title: 'No tokens yet' } } },
  ];
  const ctx = createFakeCtx({ sessionController: fakeSessionController({ items }) });
  const adapter = makeAdapter(ctx);
  const previousKey = process.env.DEEPSEEK_API_KEY;
  process.env.DEEPSEEK_API_KEY = '';
  try {
    const usage = await adapter.getUsage();
    assert.deepEqual(usage.totals, {
      uncachedInputTokens: 15,
      outputTokens: 10,
      cacheReadTokens: 150,
      cacheWriteTokens: 2,
      totalTokens: 177,
    });
    assert.equal(usage.sessions.length, 2);
    assert.equal(usage.sessions[0].sessionId, 's_use_1');
    assert.equal(usage.sessions[0].title, 'Ship the usage screen');
    assert.equal(usage.sessions[0].cacheReadTokens, 100);
    assert.equal(usage.balance, null);
    assert.equal(usage.balanceError, null);
    assert.match(usage.generatedAt, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
  } finally {
    if (previousKey === undefined) delete process.env.DEEPSEEK_API_KEY;
    else process.env.DEEPSEEK_API_KEY = previousKey;
  }
});

test('getUsage reads tokenUsage directly from persisted projection files', async (t) => {
  const { mkdtemp, rm, writeFile } = await import('node:fs/promises');
  const os = await import('node:os');
  const path = await import('node:path');
  const dir = await mkdtemp(path.join(os.tmpdir(), 'dsh-usage-test-'));
  t.after?.(() => rm(dir, { recursive: true, force: true }));

  const write = (name, title, totals) =>
    writeFile(
      path.join(dir, name),
      JSON.stringify({ version: 1, record: { rows: { title: { val: title }, tokenUsage: { val: { totals } } } } }),
      'utf8',
    );
  await write('session-usage-a.json', 'Alpha', {
    uncachedInputTokens: 10,
    outputTokens: 2,
    cacheReadTokens: 100,
    cacheWriteTokens: 0,
  });
  // Subagent rows count toward totals but are not listed as user sessions.
  await write('subagent.json', 'Child', {
    uncachedInputTokens: 1,
    outputTokens: 1,
    cacheReadTokens: 2,
    cacheWriteTokens: 0,
  });

  const config = testConfig({ usage: { projectionsDir: dir } });
  const adapter = makeAdapter(createFakeCtx({}), null, { config });
  const previousKey = process.env.DEEPSEEK_API_KEY;
  process.env.DEEPSEEK_API_KEY = '';
  try {
    const usage = await adapter.getUsage();
    assert.deepEqual(usage.totals, {
      uncachedInputTokens: 11,
      outputTokens: 3,
      cacheReadTokens: 102,
      cacheWriteTokens: 0,
      totalTokens: 116,
    });
    assert.equal(usage.sessions.length, 1);
    assert.equal(usage.sessions[0].sessionId, 'session-usage-a');
    assert.equal(usage.sessions[0].title, 'Alpha');
    assert.equal(usage.sessions[0].cacheReadTokens, 100);
    assert.equal(usage.balance, null);
    assert.equal(usage.balanceError, null);
  } finally {
    if (previousKey === undefined) delete process.env.DEEPSEEK_API_KEY;
    else process.env.DEEPSEEK_API_KEY = previousKey;
  }
});

test('getUsage returns the signed-in account wallet balance', async () => {
  let seenClient = null;
  const ctx = createFakeCtx({
    sessionController: fakeSessionController({ items: [] }),
    deepseekAccount: {
      async getBalance(client) {
        seenClient = client;
        return {
          status: 'ready',
          value: [{ currency: 'USD', balance: '12.34' }],
          bonusWallets: [{ currency: 'USD', balance: '1.66' }],
        };
      },
    },
  });
  const adapter = makeAdapter(ctx);
  const usage = await adapter.getUsage();
  assert.equal(seenClient.locale, 'en');
  assert.equal(typeof seenClient.timezoneOffsetSeconds, 'number');
  assert.deepEqual(usage.balance, {
    available: true,
    balances: [
      {
        currency: 'USD',
        totalBalance: '14',
        grantedBalance: '1.66',
        toppedUpBalance: '12.34',
      },
    ],
  });
  assert.equal(usage.balanceError, null);
});

test('session goal reads and resumes through the live agent', async () => {
  const agent = { id: 's_goal' };
  const goalView = {
    id: 'goal-test',
    objective: 'Finish the mobile goal controls',
    phase: 'paused',
    maxGoalRounds: 120,
    roundsStarted: 61,
    revision: 6,
    activation: 'disarmed',
    createdAt: T(1000),
    updatedAt: T(2000),
  };
  let resumed = null;
  const ctx = createFakeCtx({
    agents: {
      get(sessionId) {
        return sessionId === 's_goal' ? agent : undefined;
      },
    },
    goals: {
      get(seenAgent) {
        assert.equal(seenAgent, agent);
        return goalView;
      },
      resume(seenAgent, ref) {
        resumed = { agent: seenAgent, ref };
        return { ...goalView, phase: 'active', activation: 'armed', revision: ref.revision + 1 };
      },
    },
  });
  const adapter = createDshAdapter({ ctx, config: testConfig(), bus: null, logger: silent });

  const current = await adapter.getSessionGoal('s_goal');
  assert.equal(current.supported, true);
  assert.equal(current.live, true);
  assert.equal(current.goal.id, 'goal-test');
  assert.equal(current.goal.phase, 'paused');
  assert.equal(current.goal.roundsStarted, 61);
  assert.equal(current.goal.maxGoalRounds, 120);
  assert.equal(current.goal.activation, 'disarmed');
  assert.equal(current.goal.objective, 'Finish the mobile goal controls');

  const active = await adapter.resumeSessionGoal('s_goal');
  assert.deepEqual(resumed, { agent, ref: { id: 'goal-test', revision: 6 } });
  assert.equal(active.goal.phase, 'active');
  assert.equal(active.goal.activation, 'armed');
  assert.equal(active.goal.revision, 7);
});

test('session goal can be created when there is no current goal', async () => {
  const agent = { id: 's_goal_create' };
  let created = null;
  const createdView = {
    id: 'goal-created',
    objective: 'Ship the phone goal creation flow',
    phase: 'active',
    maxGoalRounds: 40,
    roundsStarted: 0,
    revision: 1,
    activation: 'armed',
    createdAt: T(1000),
    updatedAt: T(1000),
  };
  const ctx = createFakeCtx({
    agents: { get: (id) => (id === 's_goal_create' ? agent : undefined) },
    goals: {
      get: () => undefined,
      create(seenAgent, request) {
        created = { agent: seenAgent, request };
        return createdView;
      },
    },
  });
  const adapter = createDshAdapter({ ctx, config: testConfig(), bus: null, logger: silent });
  const result = await adapter.createSessionGoal('s_goal_create', {
    objective: 'Ship the phone goal creation flow',
    maxGoalRounds: 40,
  });
  assert.deepEqual(created, {
    agent,
    request: { objective: 'Ship the phone goal creation flow', maxGoalRounds: 40 },
  });
  assert.equal(result.goal.id, 'goal-created');
  assert.equal(result.goal.phase, 'active');
  assert.equal(result.goal.maxGoalRounds, 40);
  assert.equal(result.goal.activation, 'armed');
});

test('session goal can pause an active goal', async () => {
  const agent = { id: 's_goal_pause' };
  const activeView = {
    id: 'goal-pause',
    objective: 'Pause from the phone',
    phase: 'active',
    maxGoalRounds: 120,
    roundsStarted: 62,
    revision: 7,
    activation: 'armed',
    createdAt: T(1000),
    updatedAt: T(2000),
  };
  let paused = null;
  const ctx = createFakeCtx({
    agents: { get: (id) => (id === 's_goal_pause' ? agent : undefined) },
    goals: {
      get: () => activeView,
      pause(seenAgent, ref) {
        paused = { agent: seenAgent, ref };
        return { ...activeView, phase: 'paused', activation: 'disarmed', revision: ref.revision + 1 };
      },
    },
  });
  const adapter = createDshAdapter({ ctx, config: testConfig(), bus: null, logger: silent });
  const result = await adapter.pauseSessionGoal('s_goal_pause');
  assert.deepEqual(paused, { agent, ref: { id: 'goal-pause', revision: 7 } });
  assert.equal(result.goal.phase, 'paused');
  assert.equal(result.goal.activation, 'disarmed');
  assert.equal(result.goal.revision, 8);
});

test('session goal can be completed through the live agent', async () => {
  const agent = { id: 's_goal_complete' };
  const activeView = {
    id: 'goal-complete',
    objective: 'Complete from the phone',
    phase: 'active',
    maxGoalRounds: 64,
    roundsStarted: 4,
    revision: 7,
    activation: 'armed',
    createdAt: T(1000),
    updatedAt: T(2000),
  };
  let completed = null;
  const ctx = createFakeCtx({
    agents: { get: (id) => (id === 's_goal_complete' ? agent : undefined) },
    goals: {
      get: () => activeView,
      complete(seenAgent, ref) {
        completed = { agent: seenAgent, ref };
        return { ...activeView, phase: 'complete', activation: 'disarmed', revision: ref.revision + 1 };
      },
    },
  });
  const adapter = createDshAdapter({ ctx, config: testConfig(), bus: null, logger: silent });
  await assert.rejects(
    () => adapter.completeSessionGoal('s_goal_complete', { id: 'goal-complete', revision: 6 }),
    (error) =>
      error instanceof ApiError && error.code === 'invalid_request' && /stale/i.test(error.message),
  );
  assert.equal(completed, null);
  const result = await adapter.completeSessionGoal('s_goal_complete', {
    id: 'goal-complete',
    revision: 7,
  });
  assert.deepEqual(completed, { agent, ref: { id: 'goal-complete', revision: 7 } });
  assert.equal(result.supported, true);
  assert.equal(result.live, true);
  assert.equal(result.goal.phase, 'complete');
  assert.equal(result.goal.activation, 'disarmed');
  assert.equal(result.goal.revision, 8);
  assert.equal(result.goal.objective, 'Complete from the phone');
});

test('goal complete maps unsupported, no-live, no-goal, stale and invalid-transition errors', async () => {
  const unsupported = createDshAdapter({
    ctx: createFakeCtx({}),
    config: testConfig(),
    bus: null,
    logger: silent,
  });
  await assert.rejects(
    () => unsupported.completeSessionGoal('s_1'),
    (error) => error instanceof ApiError && error.code === 'unsupported' && error.status === 501,
  );

  const nonLive = createDshAdapter({
    ctx: createFakeCtx({
      agents: { get: () => undefined },
      goals: { get: () => undefined, complete: () => undefined },
    }),
    config: testConfig(),
    bus: null,
    logger: silent,
  });
  await assert.rejects(
    () => nonLive.completeSessionGoal('s_1'),
    (error) =>
      error instanceof ApiError &&
      error.code === 'invalid_request' &&
      error.status === 400 &&
      /no live agent/i.test(error.message),
  );

  const noGoal = createDshAdapter({
    ctx: createFakeCtx({
      agents: { get: () => ({ id: 's_1' }) },
      goals: { get: () => undefined, complete: () => undefined },
    }),
    config: testConfig(),
    bus: null,
    logger: silent,
  });
  await assert.rejects(
    () => noGoal.completeSessionGoal('s_1'),
    (error) => error instanceof ApiError && error.code === 'not_found' && error.status === 404,
  );

  const stale = new Error('stale goal ref "goal-1" revision 3; current is "goal-1" revision 4');
  stale.code = 'GOAL_STALE_REVISION';
  const invalidTransition = new Error('cannot complete goal "goal-1" from phase "complete"');
  invalidTransition.code = 'GOAL_INVALID_TRANSITION';
  const goals = {
    get: () => ({ id: 'goal-1', revision: 3, phase: 'active' }),
    complete() {
      throw stale;
    },
  };
  const failing = createDshAdapter({
    ctx: createFakeCtx({ agents: { get: () => ({ id: 's_1' }) }, goals }),
    config: testConfig(),
    bus: null,
    logger: silent,
  });
  await assert.rejects(
    () => failing.completeSessionGoal('s_1'),
    (error) =>
      error instanceof ApiError &&
      error.code === 'invalid_request' &&
      error.status === 400 &&
      /stale/i.test(error.message) &&
      error.details?.code === 'GOAL_STALE_REVISION',
  );

  goals.complete = () => {
    throw invalidTransition;
  };
  await assert.rejects(
    () => failing.completeSessionGoal('s_1'),
    (error) =>
      error instanceof ApiError &&
      error.code === 'invalid_request' &&
      error.details?.code === 'GOAL_INVALID_TRANSITION',
  );
});

test('session goal reports unsupported when ctx.goals is absent and refuses non-live sessions', async () => {
  const adapter = createDshAdapter({
    ctx: createFakeCtx({}),
    config: testConfig(),
    bus: null,
    logger: silent,
  });
  assert.deepEqual(await adapter.getSessionGoal('s_1'), {
    supported: false,
    live: false,
    goal: null,
  });
  await assert.rejects(
    () => adapter.resumeSessionGoal('s_1'),
    (error) => error instanceof ApiError && error.code === 'unsupported',
  );

  const liveAdapter = createDshAdapter({
    ctx: createFakeCtx({
      agents: { get: () => undefined },
      goals: { get: () => undefined, resume: () => undefined },
    }),
    config: testConfig(),
    bus: null,
    logger: silent,
  });
  const notLive = await liveAdapter.getSessionGoal('s_1');
  assert.equal(notLive.supported, true);
  assert.equal(notLive.live, false);
  await assert.rejects(
    () => liveAdapter.resumeSessionGoal('s_1'),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );
});

test('session goal can be edited (objective and round cap) through the live agent', async () => {
  const agent = { id: 's_goal_edit' };
  let current = {
    id: 'goal-edit',
    objective: 'Original objective',
    phase: 'active',
    maxGoalRounds: 120,
    roundsStarted: 5,
    revision: 3,
    activation: 'armed',
    createdAt: T(1000),
    updatedAt: T(2000),
  };
  const calls = [];
  const ctx = createFakeCtx({
    agents: { get: (id) => (id === 's_goal_edit' ? agent : undefined) },
    goals: {
      get: () => current,
      edit(seenAgent, ref, request) {
        calls.push({ agent: seenAgent, ref, request });
        current = { ...current, ...request, revision: ref.revision + 1, updatedAt: T(3000) };
        return current;
      },
    },
  });
  const adapter = createDshAdapter({ ctx, config: testConfig(), bus: null, logger: silent });

  // Both fields in one edit: DSH compares the revision read before the call.
  const combined = await adapter.editSessionGoal('s_goal_edit', {
    objective: 'Updated objective',
    maxGoalRounds: 60,
  });
  assert.deepEqual(calls.at(-1), {
    agent,
    ref: { id: 'goal-edit', revision: 3 },
    request: { objective: 'Updated objective', maxGoalRounds: 60 },
  });
  assert.equal(combined.supported, true);
  assert.equal(combined.live, true);
  assert.equal(combined.goal.objective, 'Updated objective');
  assert.equal(combined.goal.maxGoalRounds, 60);
  assert.equal(combined.goal.phase, 'active');
  assert.equal(combined.goal.roundsStarted, 5);
  assert.equal(combined.goal.revision, 4);

  // Round cap only.
  const roundsOnly = await adapter.editSessionGoal('s_goal_edit', { maxGoalRounds: 200 });
  assert.deepEqual(calls.at(-1).request, { maxGoalRounds: 200 });
  assert.equal(roundsOnly.goal.maxGoalRounds, 200);
  assert.equal(roundsOnly.goal.objective, 'Updated objective');
  assert.equal(roundsOnly.goal.revision, 5);

  // Objective only.
  const objectiveOnly = await adapter.editSessionGoal('s_goal_edit', { objective: 'Objective only' });
  assert.deepEqual(calls.at(-1).request, { objective: 'Objective only' });
  assert.equal(objectiveOnly.goal.objective, 'Objective only');
  assert.equal(objectiveOnly.goal.revision, 6);
});

test('session goal can be cleared through the live agent', async () => {
  const agent = { id: 's_goal_clear' };
  const current = {
    id: 'goal-clear',
    objective: 'Clear this goal',
    phase: 'active',
    maxGoalRounds: 40,
    roundsStarted: 2,
    revision: 7,
    activation: 'armed',
    createdAt: T(1000),
    updatedAt: T(2000),
  };
  let cleared = null;
  const ctx = createFakeCtx({
    agents: { get: (id) => (id === 's_goal_clear' ? agent : undefined) },
    goals: {
      get: () => current,
      clear(seenAgent, ref) {
        cleared = { agent: seenAgent, ref };
        return { id: current.id, revision: ref.revision + 1 };
      },
    },
  });
  const adapter = createDshAdapter({ ctx, config: testConfig(), bus: null, logger: silent });

  const result = await adapter.clearSessionGoal('s_goal_clear');
  assert.deepEqual(cleared, { agent, ref: { id: 'goal-clear', revision: 7 } });
  assert.deepEqual(result, { supported: true, live: true, goal: null });
});

test('goal edit/clear map malformed requests, missing goals, non-live sessions and GoalErrors', async () => {
  // Malformed request: at least one field is required (checked before services).
  const bare = createDshAdapter({ ctx: createFakeCtx({}), config: testConfig(), bus: null, logger: silent });
  for (const request of [{}, undefined, null]) {
    await assert.rejects(
      () => bare.editSessionGoal('s_1', request),
      (error) => error instanceof ApiError && error.code === 'invalid_request',
    );
  }
  await assert.rejects(
    () => bare.clearSessionGoal('s_1'),
    (error) => error instanceof ApiError && error.code === 'unsupported',
  );

  const noGoal = createDshAdapter({
    ctx: createFakeCtx({
      agents: { get: () => ({ id: 's_1' }) },
      goals: { get: () => undefined, edit: () => undefined, clear: () => undefined },
    }),
    config: testConfig(),
    bus: null,
    logger: silent,
  });
  await assert.rejects(
    () => noGoal.editSessionGoal('s_1', { objective: 'x' }),
    (error) => error instanceof ApiError && error.code === 'not_found' && error.status === 404,
  );
  await assert.rejects(
    () => noGoal.clearSessionGoal('s_1'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  const nonLive = createDshAdapter({
    ctx: createFakeCtx({
      agents: { get: () => undefined },
      goals: { get: () => undefined, edit: () => undefined, clear: () => undefined },
    }),
    config: testConfig(),
    bus: null,
    logger: silent,
  });
  await assert.rejects(
    () => nonLive.editSessionGoal('s_1', { objective: 'x' }),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );
  await assert.rejects(
    () => nonLive.clearSessionGoal('s_1'),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );

  // DSH GoalError keeps its stable code in details and maps to a clear 400.
  const stale = new Error('goal revision 7 is stale; reload and retry');
  stale.code = 'GOAL_STALE_REVISION';
  const goalError = new Error('goal is already complete');
  goalError.code = 'GOAL_INVALID_TRANSITION';
  const dshGoals = {
    get: () => ({ id: 'goal-1', revision: 7 }),
    edit() {
      throw stale;
    },
    clear() {
      throw goalError;
    },
  };
  const failing = createDshAdapter({
    ctx: createFakeCtx({ agents: { get: () => ({ id: 's_1' }) }, goals: dshGoals }),
    config: testConfig(),
    bus: null,
    logger: silent,
  });
  await assert.rejects(
    () => failing.editSessionGoal('s_1', { objective: 'x' }),
    (error) =>
      error instanceof ApiError &&
      error.code === 'invalid_request' &&
      error.status === 400 &&
      /stale/i.test(error.message) &&
      error.details?.code === 'GOAL_STALE_REVISION',
  );
  await assert.rejects(
    () => failing.clearSessionGoal('s_1'),
    (error) =>
      error instanceof ApiError &&
      error.code === 'invalid_request' &&
      error.details?.code === 'GOAL_INVALID_TRANSITION',
  );
});

/* ------------------------------------------------------------------------- */
/* goal.changed SSE fan-out (task-34)                                          */
/* ------------------------------------------------------------------------- */

const GOAL_SSE_SESSION = 'session-goal-live';

/** One durable DSH `goal/change` session-event fixture (GoalChangeMeta). */
function goalChangeEventData(overrides = {}) {
  return {
    kind: 'goal/change',
    version: 1,
    operation: 'create',
    goal: {
      id: 'goal_01',
      revision: 3,
      objective: 'Ship goal SSE events',
      phase: 'active',
      maxGoalRounds: 12,
    },
    roundsStarted: 2,
    createdAt: T(1000),
    updatedAt: T(5000),
    ...overrides,
  };
}

function goalChangeSessionEvent(data, { seq = 7, time = T(5000) } = {}) {
  return { type: 'goal/change', seq, time, data };
}

test('goal.changed forwards mapped DSH goal snapshots and clear tombstones', () => {
  const bus = new EventBus({ logger: silent, bufferSize: 50 });
  const ctx = createFakeCtx({});
  const adapter = createDshAdapter({ ctx, config: testConfig(), bus, logger: silent });
  const dispose = adapter.attach();
  const emit = (data, options) =>
    ctx.emit('session/event', { id: GOAL_SSE_SESSION }, goalChangeSessionEvent(data, options));

  emit(goalChangeEventData());
  let published = bus.buffer.at(-1);
  assert.equal(published.type, 'goal.changed');
  assert.match(published.id, /^ev_\d{6}$/);
  assert.match(published.at, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
  assert.equal(published.sessionId, GOAL_SSE_SESSION);
  assert.deepEqual(published.data, {
    sessionId: GOAL_SSE_SESSION,
    operation: 'create',
    goal: {
      id: 'goal_01',
      objective: 'Ship goal SSE events',
      phase: 'active',
      maxGoalRounds: 12,
      roundsStarted: 2,
      revision: 3,
      // Durable events carry no process-local activation; mapGoalView defaults it.
      activation: 'disarmed',
      createdAt: '2026-10-30T09:40:01Z',
      updatedAt: '2026-10-30T09:40:05Z',
    },
  });

  // pause / complete / edit keep the mapped snapshot and operation.
  emit(goalChangeEventData({ operation: 'pause', goal: { ...goalChangeEventData().goal, phase: 'paused', revision: 4 } }));
  published = bus.buffer.at(-1);
  assert.equal(published.data.operation, 'pause');
  assert.equal(published.data.goal.phase, 'paused');
  assert.equal(published.data.goal.revision, 4);
  assert.equal(published.data.goal.roundsStarted, 2);

  emit(goalChangeEventData({ operation: 'complete', goal: { ...goalChangeEventData().goal, phase: 'complete', revision: 5 } }));
  published = bus.buffer.at(-1);
  assert.equal(published.data.operation, 'complete');
  assert.equal(published.data.goal.phase, 'complete');
  assert.equal(published.data.goal.revision, 5);

  // A clear tombstone maps to operation "clear" with a null goal.
  emit(
    {
      kind: 'goal/change',
      version: 1,
      operation: 'clear',
      cleared: { id: 'goal_01', revision: 6 },
      clearedAt: T(6000),
    },
    { seq: 8, time: T(6000) },
  );
  published = bus.buffer.at(-1);
  assert.equal(published.type, 'goal.changed');
  assert.equal(published.data.operation, 'clear');
  assert.equal(published.data.goal, null);
  assert.equal(published.data.sessionId, GOAL_SSE_SESSION);

  // Defensive: a malformed payload still yields a well-formed event.
  emit({}, { seq: 9 });
  published = bus.buffer.at(-1);
  assert.equal(published.type, 'goal.changed');
  assert.equal(published.data.operation, 'edit');
  assert.equal(published.data.goal, null);

  dispose();
});

test('goal.changed prefers the live goal view for process-local activation', () => {
  const bus = new EventBus({ logger: silent, bufferSize: 20 });
  const agent = { id: GOAL_SSE_SESSION };
  const liveView = {
    id: 'goal_01',
    revision: 3,
    objective: 'Ship goal SSE events',
    phase: 'active',
    maxGoalRounds: 12,
    roundsStarted: 2,
    activation: 'armed',
    createdAt: T(1000),
    updatedAt: T(5000),
  };
  const ctx = createFakeCtx({
    agents: { get: (sessionId) => (sessionId === GOAL_SSE_SESSION ? agent : undefined) },
    goals: { get: () => liveView },
  });
  const adapter = createDshAdapter({ ctx, config: testConfig(), bus, logger: silent });
  const dispose = adapter.attach();

  ctx.emit('session/event', { id: GOAL_SSE_SESSION }, goalChangeSessionEvent(goalChangeEventData()));
  let published = bus.buffer.at(-1);
  assert.equal(published.data.goal.activation, 'armed', 'matching live revision overrides the durable default');

  // A stale live view (different revision) must not override the event snapshot.
  const staleCtx = createFakeCtx({
    agents: { get: () => agent },
    goals: { get: () => ({ ...liveView, revision: 2 }) },
  });
  const staleBus = new EventBus({ logger: silent, bufferSize: 20 });
  const staleAdapter = createDshAdapter({ ctx: staleCtx, config: testConfig(), bus: staleBus, logger: silent });
  const disposeStale = staleAdapter.attach();
  staleCtx.emit('session/event', { id: GOAL_SSE_SESSION }, goalChangeSessionEvent(goalChangeEventData()));
  published = staleBus.buffer.at(-1);
  assert.equal(published.data.goal.revision, 3);
  assert.equal(published.data.goal.activation, 'disarmed');
  disposeStale();
  dispose();
});

test('goal.changed is suppressed for hidden and internal sessions', async () => {
  const event = goalChangeSessionEvent(goalChangeEventData(), { seq: 12, time: T(9000) });

  // Internal (subagent) sessions: positive parentSession marker and bare-UUID heuristic.
  const internalBus = new EventBus({ logger: silent, bufferSize: 20 });
  const internalCtx = createFakeCtx({});
  const internalAdapter = createDshAdapter({ ctx: internalCtx, config: testConfig(), bus: internalBus, logger: silent });
  const disposeInternal = internalAdapter.attach();
  internalCtx.emit(
    'session/event',
    {
      id: 'session-defensive-goal-child',
      header: { version: 4, id: 'session-defensive-goal-child', createdAt: T(0), isSeeded: false, parentSession: 'session-parent', delegationDepth: 1 },
    },
    event,
  );
  assert.equal(internalBus.buffer.length, 0, 'subagent-parent marker suppresses goal.changed');
  internalCtx.emit('session/event', { id: '351ca07c-6d26-46a8-a685-cb627d642806' }, event);
  assert.equal(internalBus.buffer.length, 0, 'bare-UUID heuristic suppresses goal.changed');
  disposeInternal();

  // Operator-curated hidden user session: suppressed, visible one still forwarded.
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace(['session-hidden-goal']);
  const hiddenBus = new EventBus({ logger: silent, bufferSize: 20 });
  const hiddenCtx = createFakeCtx({});
  const hiddenAdapter = createDshAdapter({ ctx: hiddenCtx, config: testConfig(), bus: hiddenBus, hiddenStore, logger: silent });
  const disposeHidden = hiddenAdapter.attach();
  hiddenCtx.emit('session/event', { id: 'session-hidden-goal' }, event);
  assert.equal(hiddenBus.buffer.length, 0, 'curated hidden session suppresses goal.changed');
  hiddenCtx.emit('session/event', { id: 'session-visible-goal' }, event);
  assert.equal(hiddenBus.buffer.at(-1)?.type, 'goal.changed');
  disposeHidden();
});

test('session detail exposes attachment metadata and readAttachment serves only this session refs', async () => {
  const imageRef = { attachmentId: 'att_img_1', mediaType: 'image/png', bytes: 4, width: 2, height: 2, name: 'shot.png' };
  const fileRef = { attachmentId: 'att_file_1', name: 'report.pdf', bytes: 6 };
  const events = [
    {
      type: 'user/message',
      seq: 1,
      time: T(1000),
      data: {
        id: 'm_att',
        role: 'user',
        content: [{ type: 'text', text: 'Here' }, { type: 'image', attachment: imageRef }, { type: 'file', attachment: fileRef }],
        source: { kind: 'user' },
      },
    },
    {
      type: 'assistant/message',
      seq: 2,
      time: T(2000),
      data: { message: { id: 'm_att_a', role: 'assistant', content: [{ type: 'text', text: 'Looks good' }], source: { kind: 'model' } } },
    },
  ];
  const snapshots = {
    s_1: { session: HEADER, inheritedEventCount: 0, events },
    s_2: { session: { ...HEADER, id: 's_2' }, inheritedEventCount: 0, events: EVENTS },
  };
  const reader = fakeAttachmentReader({
    imageData: new Uint8Array([0x89, 0x50, 0x4e, 0x47]),
    fileChunks: [new Uint8Array([1, 2]), new Uint8Array([3, 4, 5, 6])],
  });
  const adapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }), attachments: reader }),
    config: testConfig(),
    logger: silent,
  });

  const detail = await adapter.getSession('s_1');
  assert.deepEqual(detail.messages[0].attachments, [
    { id: 'att_img_1', kind: 'image', name: 'shot.png', mimeType: 'image/png', bytes: 4, width: 2, height: 2 },
    { id: 'att_file_1', kind: 'file', name: 'report.pdf', mimeType: 'application/pdf', bytes: 6 },
  ]);
  assert.equal('attachments' in detail.messages[1], false, 'text-only messages omit the field');

  const image = await adapter.readAttachment('s_1', 'att_img_1');
  assert.equal(image.status, 200);
  assert.equal(image.headers['Content-Type'], 'image/png');
  assert.equal(image.headers['Content-Length'], '4');
  assert.equal(image.headers['Content-Disposition'], 'inline; filename="shot.png"');
  assert.equal(Buffer.from(image.raw).toString('hex'), '89504e47');
  assert.equal(reader.calls.at(-1).method, 'readImage');

  const file = await adapter.readAttachment('s_1', 'att_file_1');
  assert.equal(file.headers['Content-Type'], 'application/pdf');
  assert.equal(file.headers['Content-Length'], '6');
  assert.equal(file.headers['Content-Disposition'], 'attachment; filename="report.pdf"');
  const chunks = [];
  for await (const chunk of file.raw) chunks.push(Buffer.from(chunk));
  assert.equal(Buffer.concat(chunks).toString('hex'), '010203040506');
  assert.equal(reader.calls.at(-1).method, 'readFileStream');

  await assert.rejects(
    () => adapter.readAttachment('s_1', 'att_missing'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.readAttachment('s_2', 'att_img_1'),
    (error) => error instanceof ApiError && error.code === 'not_found',
    'an attachment referenced by another session is not readable here',
  );
  await assert.rejects(
    () => adapter.readAttachment('s_1', '../etc/passwd'),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );
  await assert.rejects(
    () => adapter.readAttachment('s_unknown', 'att_img_1'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  const noStorage = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(
    () => noStorage.readAttachment('s_1', 'att_img_1'),
    (error) => error instanceof ApiError && error.code === 'unsupported',
  );

  const failing = createDshAdapter({
    ctx: createFakeCtx({
      sessionQuery: fakeSessionQuery({ snapshots }),
      attachments: fakeAttachmentReader({ readImageError: new Error('object gone') }),
    }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(
    () => failing.readAttachment('s_1', 'att_img_1'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
});

const BARE_SESSION = '351ca07c-6d26-46a8-a685-cb627d642806';

function scheduleSnapshots() {
  return {
    'session-sched-a': {
      session: { ...HEADER, id: 'session-sched-a' },
      inheritedEventCount: 0,
      events: [
        { type: 'schedule/change', seq: 1, time: T(1000), data: { version: 1, operation: 'create', schedule: { id: 'schedule-2' } } },
        { type: 'schedule/change', seq: 2, time: T(2000), data: { version: 1, operation: 'dispatch', id: 'schedule-1' } },
        { type: 'schedule/change', seq: 3, time: T(3000), data: { version: 1, operation: 'create', schedule: { id: 'schedule-2' } } },
      ],
    },
    'session-sched-b': {
      session: { ...HEADER, id: 'session-sched-b' },
      inheritedEventCount: 0,
      events: [
        { type: 'schedule/change', seq: 1, time: T(1500), data: { version: 1, operation: 'create', schedule: { id: 'schedule-1' } } },
      ],
    },
    'session-hidden-sched': {
      session: { ...HEADER, id: 'session-hidden-sched' },
      inheritedEventCount: 0,
      events: [],
    },
    [BARE_SESSION]: {
      session: { ...HEADER, id: BARE_SESSION, origin: 'subagent' },
      inheritedEventCount: 0,
      events: [],
    },
  };
}

function scheduleCatalog() {
  return [
    {
      id: 'schedule-2',
      kind: 'after',
      title: 'Second',
      prompt: 'Two',
      afterSeconds: 60,
      scheduledAt: '2026-10-30T10:10:00Z',
      sessionId: 'session-sched-a',
      status: 'active',
      lastDelivery: { scheduledAt: T(1000), deliveredAt: '2026-10-30T09:00:01Z', messageId: 'm_1' },
    },
    {
      id: 'schedule-1',
      kind: 'at',
      title: 'First',
      prompt: 'One',
      scheduledAt: '2026-10-30T09:50:00Z',
      sessionId: 'session-sched-b',
      status: 'active',
    },
    {
      id: 'schedule-3',
      kind: 'after',
      title: 'Inactive',
      prompt: 'Three',
      afterSeconds: 1,
      scheduledAt: '2026-10-29T09:00:00Z',
      sessionId: 'session-sched-a',
      status: 'inactive',
    },
    {
      id: 'schedule-hidden',
      kind: 'every',
      title: 'Hidden',
      prompt: 'Four',
      everySeconds: 300,
      scheduledAt: '2026-10-30T09:30:00Z',
      sessionId: 'session-hidden-sched',
      status: 'active',
    },
    {
      id: 'schedule-internal',
      kind: 'every',
      title: 'Internal',
      prompt: 'Five',
      everySeconds: 300,
      scheduledAt: '2026-10-30T09:30:00Z',
      sessionId: BARE_SESSION,
      status: 'active',
    },
  ];
}

test('schedules list maps the catalog, filters invisible sessions and sorts by next run', async () => {
  const schedule = fakeScheduleService({ catalog: scheduleCatalog() });
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace(['session-hidden-sched']);
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      schedule,
      sessionQuery: fakeSessionQuery({
        snapshots: scheduleSnapshots(),
        titles: { 'session-sched-a': 'Alpha', 'session-sched-b': 'Beta' },
      }),
    }),
    config: testConfig(),
    hiddenStore,
    logger: silent,
  });

  const result = await adapter.listSchedules();
  assert.equal(result.supported, true);
  assert.deepEqual(result.schedules.map((row) => row.id), ['schedule-1', 'schedule-2', 'schedule-3']);
  const byId = new Map(result.schedules.map((row) => [row.id, row]));
  assert.equal(byId.get('schedule-2').sessionTitle, 'Alpha');
  assert.equal(byId.get('schedule-1').sessionTitle, 'Beta');
  assert.equal(byId.get('schedule-2').createdAt, '2026-10-30T09:40:01Z');
  assert.equal(byId.get('schedule-2').updatedAt, '2026-10-30T09:40:03Z');
  assert.equal(byId.get('schedule-1').createdAt, '2026-10-30T09:40:01Z');
  assert.equal(byId.get('schedule-1').updatedAt, '2026-10-30T09:40:02Z');
  assert.equal(byId.get('schedule-2').schedule.lastRunAt, '2026-10-30T09:00:01Z');
  assert.equal(byId.get('schedule-3').status, 'inactive');
  assert.equal(byId.get('schedule-3').schedule.nextRunAt, null);

  const absent = createDshAdapter({ ctx: createFakeCtx({}), config: testConfig(), logger: silent });
  assert.deepEqual(await absent.listSchedules(), { schedules: [], supported: false });
});

test('schedules create requires a visible non-internal session and forwards the DSH request', async () => {
  const parsed = parseScheduleCreateBody({ title: 'Remind me', instruction: 'Ping me', afterSeconds: 60 });
  const schedule = fakeScheduleService({});
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace(['session-hidden-sched']);
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      schedule,
      sessionQuery: fakeSessionQuery({
        snapshots: scheduleSnapshots(),
        titles: { 'session-sched-a': 'Alpha' },
      }),
    }),
    config: testConfig(),
    hiddenStore,
    logger: silent,
  });

  const created = await adapter.createSessionSchedule('session-sched-a', parsed);
  assert.deepEqual(schedule.calls.at(-1), {
    method: 'create',
    sessionId: 'session-sched-a',
    request: { title: 'Remind me', prompt: 'Ping me', after_seconds: 60 },
  });
  assert.equal(created.supported, true);
  assert.equal(created.schedule.id, 'schedule-new');
  assert.equal(created.schedule.sessionId, 'session-sched-a');
  assert.equal(created.schedule.sessionTitle, 'Alpha');
  assert.equal(created.schedule.status, 'active');
  assert.equal(created.schedule.schedule.kind, 'after');
  assert.equal(created.schedule.schedule.nextRunAt, '2026-10-30T10:01:00Z');
  assert.match(created.schedule.createdAt, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);

  await assert.rejects(
    () => adapter.createSessionSchedule('session-missing', parsed),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.createSessionSchedule(BARE_SESSION, parsed),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );
  await assert.rejects(
    () => adapter.createSessionSchedule('session-hidden-sched', parsed),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  const absent = createDshAdapter({ ctx: createFakeCtx({}), config: testConfig(), logger: silent });
  await assert.rejects(
    () => absent.createSessionSchedule('session-sched-a', parsed),
    (error) => error instanceof ApiError && error.code === 'unsupported',
  );

  const failingSchedule = fakeScheduleService({
    create() {
      const error = new Error('target is not strictly future');
      error.code = 'not_future';
      throw error;
    },
  });
  const failing = createDshAdapter({
    ctx: createFakeCtx({ schedule: failingSchedule, sessionQuery: fakeSessionQuery({ snapshots: scheduleSnapshots() }) }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(
    () => failing.createSessionSchedule('session-sched-a', parsed),
    (error) =>
      error instanceof ApiError &&
      error.code === 'invalid_request' &&
      error.status === 400 &&
      /future/i.test(error.message) &&
      error.details?.code === 'not_future',
  );
});

test('schedules delete resolves the session binding from the visible catalog', async () => {
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace(['session-hidden-sched']);
  const schedule = fakeScheduleService({ catalog: scheduleCatalog() });
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      schedule,
      sessionQuery: fakeSessionQuery({ snapshots: scheduleSnapshots() }),
    }),
    config: testConfig(),
    hiddenStore,
    logger: silent,
  });

  assert.deepEqual(await adapter.deleteSchedule('schedule-2'), { ok: true, deleted: true });
  assert.deepEqual(schedule.calls.at(-1), {
    method: 'delete',
    request: { id: 'schedule-2', sessionId: 'session-sched-a' },
  });

  await assert.rejects(
    () => adapter.deleteSchedule('schedule-missing'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.deleteSchedule('schedule-hidden'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.deleteSchedule('schedule-internal'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  const missingResult = fakeScheduleService({
    catalog: scheduleCatalog(),
    del: (request) => ({ id: request.id, deleted: false, code: 'schedule_not_found' }),
  });
  const missingAdapter = createDshAdapter({
    ctx: createFakeCtx({
      schedule: missingResult,
      sessionQuery: fakeSessionQuery({ snapshots: scheduleSnapshots() }),
    }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(
    () => missingAdapter.deleteSchedule('schedule-2'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  const absent = createDshAdapter({ ctx: createFakeCtx({}), config: testConfig(), logger: silent });
  await assert.rejects(
    () => absent.deleteSchedule('schedule-2'),
    (error) => error instanceof ApiError && error.code === 'unsupported',
  );
});

test('schedules update builds the exact DSH update request and maps the record', async () => {
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace(['session-hidden-sched']);
  const schedule = fakeScheduleService({ catalog: scheduleCatalog() });
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      schedule,
      sessionQuery: fakeSessionQuery({
        snapshots: scheduleSnapshots(),
        titles: { 'session-sched-a': 'Alpha' },
      }),
    }),
    config: testConfig(),
    hiddenStore,
    logger: silent,
  });

  const titleOnly = await adapter.updateSchedule('schedule-2', parseScheduleUpdateBody({ title: 'New title' }));
  assert.deepEqual(schedule.calls.at(-1), {
    method: 'update',
    request: {
      id: 'schedule-2',
      sessionId: 'session-sched-a',
      expected: {
        id: 'schedule-2',
        kind: 'after',
        title: 'Second',
        prompt: 'Two',
        afterSeconds: 60,
        scheduledAt: '2026-10-30T10:10:00Z',
      },
      title: 'New title',
    },
  });
  assert.equal(titleOnly.supported, true);
  assert.equal(titleOnly.updated, true);
  assert.equal(titleOnly.changed, true);
  assert.equal(titleOnly.schedule.title, 'New title');
  assert.equal(titleOnly.schedule.sessionTitle, 'Alpha');
  assert.equal(titleOnly.schedule.createdAt, '2026-10-30T09:40:01Z');

  const instructionOnly = await adapter.updateSchedule(
    'schedule-2',
    parseScheduleUpdateBody({ instruction: 'New instruction' }),
  );
  assert.equal(schedule.calls.at(-1).request.prompt, 'New instruction');
  assert.equal('title' in schedule.calls.at(-1).request, false);
  assert.equal(instructionOnly.schedule.instruction, 'New instruction');

  const combined = await adapter.updateSchedule(
    'schedule-2',
    parseScheduleUpdateBody({ title: 'Edited', instruction: 'Edited instruction', everySeconds: 600 }),
  );
  assert.deepEqual(schedule.calls.at(-1).request.change, { kind: 'every', every_seconds: 600 });
  assert.equal(schedule.calls.at(-1).request.title, 'Edited');
  assert.equal(schedule.calls.at(-1).request.prompt, 'Edited instruction');
  assert.equal(combined.schedule.schedule.kind, 'every');
  assert.equal(combined.schedule.schedule.summary, 'every 600s');

  const daily = await adapter.updateSchedule(
    'schedule-2',
    parseScheduleUpdateBody({ dailyTime: '08:15', timeZone: 'UTC' }),
  );
  assert.deepEqual(schedule.calls.at(-1).request.change, {
    kind: 'daily',
    daily: { time: '08:15', time_zone: 'UTC' },
  });
  assert.equal(daily.schedule.schedule.kind, 'daily');

  await assert.rejects(
    () => adapter.updateSchedule('schedule-missing', parseScheduleUpdateBody({ title: 'x' })),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.updateSchedule('schedule-hidden', parseScheduleUpdateBody({ title: 'x' })),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.updateSchedule('schedule-internal', parseScheduleUpdateBody({ title: 'x' })),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  const absent = createDshAdapter({ ctx: createFakeCtx({}), config: testConfig(), logger: silent });
  await assert.rejects(
    () => absent.updateSchedule('schedule-2', parseScheduleUpdateBody({ title: 'x' })),
    (error) => error instanceof ApiError && error.code === 'unsupported',
  );
});

test('schedules update exposes unchanged semantics and maps DSH misses/failures', async () => {
  const snapshots = scheduleSnapshots();

  const noopSchedule = fakeScheduleService({
    catalog: scheduleCatalog(),
    update: (request) => ({ id: request.id, updated: true, record: { ...request.expected } }),
  });
  const noopAdapter = createDshAdapter({
    ctx: createFakeCtx({ schedule: noopSchedule, sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: testConfig(),
    logger: silent,
  });
  const unchanged = await noopAdapter.updateSchedule(
    'schedule-2',
    parseScheduleUpdateBody({ title: 'ignored by the fake' }),
  );
  assert.equal(unchanged.updated, true);
  assert.equal(unchanged.changed, false);

  for (const [result, expectedCode, expectedStatus] of [
    [{ id: 'schedule-2', updated: false, code: 'schedule_not_found' }, 'not_found', 404],
    [{ id: 'schedule-2', updated: false, code: 'schedule_ended' }, 'not_found', 404],
    [{ id: 'schedule-2', updated: false, code: 'schedule_conflict' }, 'invalid_request', 400],
  ]) {
    const service = fakeScheduleService({ catalog: scheduleCatalog(), update: () => result });
    const failing = createDshAdapter({
      ctx: createFakeCtx({ schedule: service, sessionQuery: fakeSessionQuery({ snapshots }) }),
      config: testConfig(),
      logger: silent,
    });
    await assert.rejects(
      () => failing.updateSchedule('schedule-2', parseScheduleUpdateBody({ title: 'x' })),
      (error) => error instanceof ApiError && error.code === expectedCode && error.status === expectedStatus,
    );
  }

  const toolSchedule = fakeScheduleService({
    catalog: scheduleCatalog(),
    update: () => ({ code: 'not_future', message: 'target is not strictly future' }),
  });
  const toolAdapter = createDshAdapter({
    ctx: createFakeCtx({ schedule: toolSchedule, sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(
    () => toolAdapter.updateSchedule('schedule-2', parseScheduleUpdateBody({ at: '2026-11-01T12:00:00Z' })),
    (error) =>
      error instanceof ApiError &&
      error.code === 'invalid_request' &&
      error.status === 400 &&
      /future/i.test(error.message),
  );
});

function sessionManagementSnapshots() {
  return {
    'session-rename': {
      session: { ...HEADER, id: 'session-rename' },
      inheritedEventCount: 0,
      events: [EVENTS[0]],
    },
    'session-arch-visible': {
      session: { ...HEADER, id: 'session-arch-visible' },
      inheritedEventCount: 0,
      events: [EVENTS[0]],
    },
    'session-arch-hidden': {
      session: { ...HEADER, id: 'session-arch-hidden' },
      inheritedEventCount: 0,
      events: [],
    },
    [BARE_SESSION]: {
      session: { ...HEADER, id: BARE_SESSION, origin: 'subagent' },
      inheritedEventCount: 0,
      events: [],
    },
  };
}

test('session rename requires a live visible session and maps DSH errors', async () => {
  const snapshots = sessionManagementSnapshots();
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace(['session-arch-hidden']);
  const visibleAgent = { id: 'session-rename' };
  const controller = fakeSessionController({ rename: (request) => ({ title: request.title, seq: 11 }) });
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: controller,
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: { get: (id) => (id === 'session-rename' ? visibleAgent : undefined) },
    }),
    config: testConfig(),
    hiddenStore,
    logger: silent,
  });

  assert.deepEqual(await adapter.renameSession('session-rename', '  New title  '), {
    ok: true,
    title: 'New title',
    seq: 11,
  });
  assert.deepEqual(controller.calls.at(-1), {
    method: 'rename',
    request: { sessionId: 'session-rename', title: 'New title' },
  });
  await assert.rejects(
    () => adapter.renameSession('session-rename', '   '),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );

  const noLive = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: controller,
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: { get: () => undefined },
    }),
    config: testConfig(),
    hiddenStore,
    logger: silent,
  });
  await assert.rejects(
    () => noLive.renameSession('session-rename', 'Title'),
    (error) =>
      error instanceof ApiError &&
      error.code === 'invalid_request' &&
      /open on the host/i.test(error.message),
  );

  await assert.rejects(
    () => adapter.renameSession('session-missing', 'x'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.renameSession('session-arch-hidden', 'x'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.renameSession(BARE_SESSION, 'x'),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );

  const titleInvalid = new Error('Invalid session title');
  titleInvalid.code = 'session/title-invalid';
  const titleFailing = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({
        rename: () => {
          throw titleInvalid;
        },
      }),
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: { get: () => visibleAgent },
    }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(
    () => titleFailing.renameSession('session-rename', 'x'),
    (error) =>
      error instanceof ApiError &&
      error.code === 'invalid_request' &&
      error.details?.code === 'session/title-invalid',
  );

  const notFound = new Error('Session vanish');
  notFound.code = 'session/not-found';
  const notFoundFailing = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({
        rename: () => {
          throw notFound;
        },
      }),
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: { get: () => visibleAgent },
    }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(
    () => notFoundFailing.renameSession('session-rename', 'x'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
});

test('session archive/unarchive use the workspace controller and visibility policy', async () => {
  const snapshots = sessionManagementSnapshots();
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace(['session-arch-hidden']);
  const workspaceController = fakeWorkspaceController();
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      workspaceController,
      sessionQuery: fakeSessionQuery({ snapshots }),
    }),
    config: testConfig(),
    hiddenStore,
    logger: silent,
  });

  assert.deepEqual(await adapter.archiveSession('session-rename'), { ok: true, archived: true });
  assert.deepEqual(workspaceController.calls.at(-1), {
    method: 'archiveSession',
    request: { sessionId: 'session-rename' },
  });
  assert.equal(
    'stopActivity' in workspaceController.calls.at(-1).request,
    false,
    'the phone never asks the host to stop activity',
  );

  assert.deepEqual(await adapter.unarchiveSession('session-rename'), { ok: true, archived: false });
  assert.deepEqual(workspaceController.calls.at(-1), {
    method: 'unarchiveSession',
    request: { sessionId: 'session-rename' },
  });

  const callsBefore = workspaceController.calls.length;
  await assert.rejects(
    () => adapter.archiveSession('session-missing'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.archiveSession('session-arch-hidden'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.archiveSession(BARE_SESSION),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  assert.equal(workspaceController.calls.length, callsBefore, 'invisible ids never reach the service');

  const activeError = new Error('The session still has activity');
  activeError.code = 'workspace/session-active';
  const busyController = fakeWorkspaceController({
    archive: () => {
      throw activeError;
    },
  });
  const busy = createDshAdapter({
    ctx: createFakeCtx({ workspaceController: busyController, sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(
    () => busy.archiveSession('session-rename'),
    (error) =>
      error instanceof ApiError &&
      error.code === 'invalid_request' &&
      /activity/i.test(error.message) &&
      error.details?.code === 'workspace/session-active',
  );

  const absent = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(
    () => absent.archiveSession('session-rename'),
    (error) => error instanceof ApiError && error.code === 'unsupported',
  );
  await assert.rejects(
    () => absent.unarchiveSession('session-rename'),
    (error) => error instanceof ApiError && error.code === 'unsupported',
  );
});

test('archived sessions list maps visible summaries and never leaks hidden/internal rows', async () => {
  const snapshots = sessionManagementSnapshots();
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace(['session-arch-hidden']);
  const workspaceController = fakeWorkspaceController({
    archived: ['session-arch-visible', 'session-arch-hidden', BARE_SESSION, 'session-arch-missing'],
  });
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      workspaceController,
      sessionQuery: fakeSessionQuery({ snapshots }),
      sessionController: fakeSessionController({
        items: [
          { sessionId: 'session-arch-visible', updatedAt: T(5000), running: false, agentAvailable: false, cwd: 'C:\\work' },
          { sessionId: 'session-arch-other', updatedAt: T(4000), running: false, agentAvailable: false, cwd: 'C:\\work' },
        ],
      }),
    }),
    config: testConfig(),
    hiddenStore,
    logger: silent,
  });

  const result = await adapter.listArchivedSessions();
  assert.equal(result.supported, true);
  assert.deepEqual(result.sessions.map((session) => session.id), ['session-arch-visible']);
  const row = result.sessions[0];
  assert.equal(row.id, 'session-arch-visible');
  assert.equal(typeof row.updatedAt, 'string');
  assert.equal(row.unread, false);
  assert.equal('goal' in row, false, 'archived rows are not goal-enriched');

  // The normal list hides archived sessions and restores them on unarchive.
  const mainList = await adapter.listSessions();
  assert.equal(
    mainList.some((session) => session.id === 'session-arch-visible'),
    false,
    'archived sessions are absent from the normal list',
  );
  assert.equal(mainList.some((session) => session.id === 'session-arch-other'), true);
  assert.deepEqual(await adapter.unarchiveSession('session-arch-visible'), {
    ok: true,
    archived: false,
  });
  const restored = await adapter.listSessions();
  assert.equal(restored.some((session) => session.id === 'session-arch-visible'), true);

  // Registry projection fallback (no controller present).
  const registry = { archivedSessionIds: ['session-arch-visible'] };
  const viaRegistry = createDshAdapter({
    ctx: createFakeCtx({ workspaceRegistry: registry, sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: testConfig(),
    hiddenStore,
    logger: silent,
  });
  const registryResult = await viaRegistry.listArchivedSessions();
  assert.equal(registryResult.supported, true);
  assert.deepEqual(registryResult.sessions.map((session) => session.id), ['session-arch-visible']);

  const absent = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: testConfig(),
    logger: silent,
  });
  assert.deepEqual(await absent.listArchivedSessions(), { sessions: [], supported: false });
});

test('presets roster merges list/roster, sorts, marks defaults and keeps broken rows', async () => {
  const service = fakeAgentPresets();
  const adapter = createDshAdapter({
    ctx: createFakeCtx({ agentPresets: service }),
    config: testConfig(),
    logger: silent,
  });

  const result = await adapter.listPresets();
  assert.deepEqual(result, {
    presets: [
      { id: 'default', name: 'Default', description: 'General work', isDefault: true },
      { id: 'research', name: 'Research', isDefault: false },
      { id: 'focused', name: 'Focused', isDefault: false, broken: true },
    ],
    supported: true,
    defaultId: 'default',
  });
  assert.deepEqual(service.calls.map((call) => call.method), ['list', 'remoteExportList']);
  assert.equal(JSON.stringify(result).includes('activation failed'), false, 'diagnostics are not leaked');

  const listOnly = createDshAdapter({
    ctx: createFakeCtx({
      agentPresets: {
        defaultId: 'first',
        async list() {
          return [
            { id: 'second', name: 'Second', order: 1 },
            { id: 'first', name: 'First', order: 0 },
          ];
        },
      },
    }),
    config: testConfig(),
    logger: silent,
  });
  assert.deepEqual(
    (await listOnly.listPresets()).presets.map((preset) => [preset.id, preset.isDefault]),
    [
      ['first', true],
      ['second', false],
    ],
  );

  const rosterOnly = createDshAdapter({
    ctx: createFakeCtx({
      agentPresets: {
        defaultId: 'a',
        async remoteExportList() {
          return {
            presets: [
              { id: 'b', name: 'B', isDefault: false },
              { id: 'a', name: 'A', isDefault: true },
            ],
          };
        },
      },
    }),
    config: testConfig(),
    logger: silent,
  });
  const rosterResult = await rosterOnly.listPresets();
  assert.deepEqual(
    rosterResult.presets.map((preset) => [preset.id, preset.isDefault]),
    [
      ['a', true],
      ['b', false],
    ],
  );

  const absent = createDshAdapter({ ctx: createFakeCtx({}), config: testConfig(), logger: silent });
  assert.deepEqual(await absent.listPresets(), { presets: [], supported: false });

  const failing = createDshAdapter({
    ctx: createFakeCtx({
      agentPresets: fakeAgentPresets({
        listError: new Error('boom'),
        rosterError: new Error('boom'),
        defaultId: '',
      }),
    }),
    config: testConfig(),
    logger: silent,
  });
  assert.deepEqual(await failing.listPresets(), { presets: [], supported: true });
});

test('searchSessions scans visible sessions, sorts hits and excludes hidden/internal/archived', async () => {
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace(['session-search-hidden']);
  const workspaceController = fakeWorkspaceController({ archived: ['session-search-archived'] });
  const snapshots = {
    'session-search-a': {
      session: { ...HEADER, id: 'session-search-a' },
      inheritedEventCount: 0,
      events: [
        { type: 'user/message', seq: 1, time: T(1000), data: { id: 'm_u_a', content: [{ type: 'text', text: 'needle in chat' }], source: { kind: 'user' } } },
        { type: 'tool/call', seq: 2, time: T(2000), data: { callId: 'call_1', name: 'Pwsh', arguments: '{"command":"grep -r needle ."}' } },
        { type: 'assistant/message', seq: 3, time: T(4000), data: { message: { id: 'm_a_a', content: [{ type: 'text', text: 'found needle here' }] } } },
      ],
    },
    'session-search-b': {
      session: { ...HEADER, id: 'session-search-b' },
      inheritedEventCount: 0,
      events: [
        { type: 'assistant/message', seq: 1, time: T(2500), data: { message: { id: 'm_a_b', content: [{ type: 'text', text: 'needle again' }] } } },
      ],
    },
    'session-search-hidden': {
      session: { ...HEADER, id: 'session-search-hidden' },
      inheritedEventCount: 0,
      events: [{ type: 'user/message', seq: 1, time: T(5000), data: { id: 'm_h', content: [{ type: 'text', text: 'needle hidden' }], source: { kind: 'user' } } }],
    },
    'session-search-archived': {
      session: { ...HEADER, id: 'session-search-archived' },
      inheritedEventCount: 0,
      events: [{ type: 'user/message', seq: 1, time: T(6000), data: { id: 'm_arch', content: [{ type: 'text', text: 'needle archived' }], source: { kind: 'user' } } }],
    },
    [BARE_SESSION]: {
      session: { ...HEADER, id: BARE_SESSION, origin: 'subagent' },
      inheritedEventCount: 0,
      events: [{ type: 'user/message', seq: 1, time: T(7000), data: { id: 'm_i', content: [{ type: 'text', text: 'needle internal' }], source: { kind: 'user' } } }],
    },
  };
  const items = [
    { sessionId: 'session-search-a', updatedAt: T(5000), running: false, agentAvailable: false, cwd: 'C:\\work' },
    { sessionId: 'session-search-b', updatedAt: T(3000), running: false, agentAvailable: false, cwd: 'C:\\work' },
    { sessionId: 'session-search-hidden', updatedAt: T(6000), running: false, agentAvailable: false, cwd: 'C:\\work' },
    { sessionId: 'session-search-archived', updatedAt: T(7000), running: false, agentAvailable: false, cwd: 'C:\\work' },
    { sessionId: BARE_SESSION, updatedAt: T(8000), running: false, agentAvailable: false, cwd: 'C:\\work' },
    { sessionId: 'session-search-broken', updatedAt: T(2000), running: false, agentAvailable: false, cwd: 'C:\\work' },
  ];
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({ items }),
      sessionQuery: fakeSessionQuery({ snapshots }),
      workspaceController,
    }),
    config: testConfig(),
    hiddenStore,
    logger: silent,
  });

  const result = await adapter.searchSessions('NEEDLE', 10);
  assert.equal(result.supported, true);
  assert.equal(result.truncated, false);
  assert.deepEqual(
    result.hits.map((hit) => [hit.sessionId, hit.messageId, hit.role]),
    [
      ['session-search-a', 'm_a_a', 'assistant'],
      ['session-search-b', 'm_a_b', 'assistant'],
      ['session-search-a', 'm_call_1', 'tool'],
      ['session-search-a', 'm_u_a', 'user'],
    ],
  );
  assert.deepEqual(result.hits.map((hit) => hit.seq), [3, 1, 2, 1], 'raw-event seq on each hit');
  assert.match(result.hits[0].snippet, /found needle here/);
  assert.equal(result.hits[0].createdAt, '2026-10-30T09:40:04Z');
  assert.equal(
    result.hits.some((hit) => /hidden|archived|internal/.test(hit.snippet)),
    false,
    'hidden/internal/archived sessions never contribute hits',
  );

  const toolOnly = await adapter.searchSessions('grep -r needle', 5);
  assert.deepEqual(toolOnly.hits.map((hit) => [hit.role, hit.messageId]), [['tool', 'm_call_1']]);

  const limited = await adapter.searchSessions('needle', 2);
  assert.equal(limited.hits.length, 2);
  assert.equal(limited.truncated, false, 'page overflow is pagination, not a scan cap');
  assert.equal(limited.hasMore, true);
  assert.equal(limited.nextOffset, 2);

  const absent = createDshAdapter({
    ctx: createFakeCtx({ sessionController: fakeSessionController({ items }) }),
    config: testConfig(),
    logger: silent,
  });
  assert.deepEqual(await absent.searchSessions('needle', 5), {
    hits: [],
    supported: false,
    offset: 0,
    nextOffset: null,
    hasMore: false,
    truncated: false,
    scanIncomplete: false,
    scannedSessions: 0,
    totalSessions: 0,
  });
});

test('search budget starts before the session list and bounds slow list work', async () => {
  const sessionController = fakeSessionController({
    list: async () => {
      await new Promise((resolve) => setTimeout(resolve, 250));
      return {
        items: [
          { sessionId: 'session-list-slow', updatedAt: T(1000), running: false, agentAvailable: false, cwd: 'C:\\work' },
        ],
      };
    },
  });
  const sessionQuery = fakeSessionQuery({
    snapshots: {
      'session-list-slow': { session: { ...HEADER, id: 'session-list-slow' }, inheritedEventCount: 0, events: [] },
    },
  });
  const adapter = createDshAdapter({
    ctx: createFakeCtx({ sessionController, sessionQuery }),
    config: testConfig(),
    logger: silent,
    searchBudgetMs: 80,
  });

  const started = Date.now();
  const result = await adapter.searchSessions('needle', 5);
  const elapsed = Date.now() - started;
  assert.equal(result.supported, true);
  assert.equal(result.truncated, true);
  assert.deepEqual(result.hits, []);
  assert.equal(result.hasMore, false, 'truncated scan + empty page must not advertise more');
  assert.equal(result.nextOffset, null);
  assert.equal(elapsed < 220, true, `slow list must be cut off by the budget (${elapsed}ms)`);
});

test('search budget bounds slow snapshot reads and keeps partial hits', async () => {
  const items = [
    { sessionId: 'session-search-fast', updatedAt: T(2000), running: false, agentAvailable: false, cwd: 'C:\\work' },
    { sessionId: 'session-search-slower', updatedAt: T(1000), running: false, agentAvailable: false, cwd: 'C:\\work' },
  ];
  const fastSnapshot = {
    session: { ...HEADER, id: 'session-search-fast' },
    inheritedEventCount: 0,
    events: [
      { type: 'assistant/message', seq: 1, time: T(2000), data: { message: { id: 'm_fast', content: [{ type: 'text', text: 'needle fast' }] } } },
    ],
  };
  const slowSnapshot = {
    session: { ...HEADER, id: 'session-search-slower' },
    inheritedEventCount: 0,
    events: [
      { type: 'assistant/message', seq: 1, time: T(1000), data: { message: { id: 'm_slow', content: [{ type: 'text', text: 'needle slow' }] } } },
    ],
  };
  const sessionQuery = fakeSessionQuery({
    snapshots: {
      'session-search-fast': fastSnapshot,
      'session-search-slower': slowSnapshot,
    },
    read: async (sessionId) => {
      if (sessionId === 'session-search-slower') {
        await new Promise((resolve) => setTimeout(resolve, 250));
        return slowSnapshot;
      }
      return fastSnapshot;
    },
  });
  const adapter = createDshAdapter({
    ctx: createFakeCtx({ sessionController: fakeSessionController({ items }), sessionQuery }),
    config: testConfig(),
    logger: silent,
    searchBudgetMs: 100,
  });

  const started = Date.now();
  const result = await adapter.searchSessions('needle', 5);
  const elapsed = Date.now() - started;
  assert.equal(result.supported, true);
  assert.equal(result.truncated, true, 'the unscanned session marks the result truncated');
  assert.deepEqual(
    result.hits.map((hit) => hit.sessionId),
    ['session-search-fast'],
    'already-collected hits are preserved when the budget is hit',
  );
  assert.equal(elapsed < 260, true, `slow snapshot read must be cut off (${elapsed}ms)`);
  assert.equal(
    sessionQuery.calls.some((call) => call.signal instanceof AbortSignal),
    true,
    'snapshot reads receive the request abort signal',
  );
});

test('turn/end notifies the push manager with the cached session title', async () => {
  const bus = new EventBus({ logger: silent });
  const calls = [];
  const push = {
    handleTurnFinished(sessionId, sessionTitle) {
      calls.push({ sessionId, sessionTitle });
    },
  };
  const ctx = createFakeCtx({
    sessionController: fakeSessionController({ items: SESSION_ITEMS }),
    sessionQuery: fakeSessionQuery({ snapshots: SNAPSHOTS, titles: { s_1: 'Cut a patch release' } }),
    workspaceRegistry: fakeWorkspaceRegistry(WORKSPACES),
  });
  const adapter = createDshAdapter({
    ctx,
    config: testConfig({ sessions: { hideInternal: false, cacheFile: false } }),
    bus,
    logger: silent,
    push,
  });
  const dispose = adapter.attach();
  await adapter.listSessions();
  ctx.emit('session/event', { id: 's_1' }, {
    type: 'turn/end',
    seq: 20,
    time: T(20_000),
    data: { reason: { kind: 'completed' } },
  });
  assert.deepEqual(calls, [{ sessionId: 's_1', sessionTitle: 'Cut a patch release' }]);
  dispose();
});

test('approval policy GET reads live effective policy and folds cold logs safely', async () => {
  const approval = fakeApprovalPolicyService({ policies: { 'session-live': 'never' } });
  const liveAgent = { id: 'session-live', session: { id: 'session-live' } };
  const snapshots = {
    'session-live': { session: { ...HEADER, id: 'session-live' }, inheritedEventCount: 0, events: [] },
    'session-cold': {
      session: { ...HEADER, id: 'session-cold' },
      inheritedEventCount: 0,
      events: [
        { type: 'approval/policy', seq: 1, time: T(1000), data: { policy: 'never' } },
        { type: 'approval/policy', seq: 2, time: T(2000), data: { policy: 'ask' } },
        { type: 'approval/policy', seq: 3, time: T(3000), data: { policy: 'never' } },
        { type: 'turn/end', seq: 4, time: T(4000), data: { turn: 1, reason: { kind: 'completed' } } },
      ],
    },
    'session-hidden': { session: { ...HEADER, id: 'session-hidden' }, inheritedEventCount: 0, events: [] },
    [BARE_SESSION]: { session: { ...HEADER, id: BARE_SESSION, origin: 'subagent' }, inheritedEventCount: 0, events: [] },
    'session-unreadable': { session: { ...HEADER, id: 'session-unreadable' }, inheritedEventCount: 0, events: [] },
  };
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace(['session-hidden']);
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionQuery: fakeSessionQuery({
        snapshots,
        read: async (sessionId) => {
          if (sessionId === 'session-unreadable') throw new Error('unreadable');
          return snapshots[sessionId];
        },
      }),
      agents: { get: (id) => (id === 'session-live' ? liveAgent : undefined) },
      approval,
    }),
    config: testConfig({ sessions: { hideInternal: false, cacheFile: false } }),
    hiddenStore,
    logger: silent,
  });

  assert.deepEqual(await adapter.getApprovalPolicy('session-live'), { supported: true, live: true, policy: 'never' });
  assert.deepEqual(await adapter.getApprovalPolicy('session-cold'), { supported: true, live: false, policy: 'never' });
  assert.deepEqual(await adapter.getApprovalPolicy('session-unreadable'), { supported: true, live: false, policy: 'ask' });

  for (const id of ['session-hidden', BARE_SESSION, 's_missing']) {
    await assert.rejects(
      () => adapter.getApprovalPolicy(id),
      (error) => error instanceof ApiError && error.code === 'not_found' && error.status === 404,
    );
  }

  const noApproval = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: testConfig(),
    logger: silent,
  });
  assert.deepEqual(await noApproval.getApprovalPolicy('session-cold'), { supported: false, live: false, policy: 'never' });
});

test('approval policy PUT requires a live agent and calls setPolicy exactly once', async () => {
  const approval = fakeApprovalPolicyService({ policies: { 'session-live': 'ask' } });
  const liveAgent = { id: 'session-live', session: { id: 'session-live' } };
  const snapshots = {
    'session-live': { session: { ...HEADER, id: 'session-live' }, inheritedEventCount: 0, events: [] },
    'session-cold': { session: { ...HEADER, id: 'session-cold' }, inheritedEventCount: 0, events: [] },
    'session-hidden': { session: { ...HEADER, id: 'session-hidden' }, inheritedEventCount: 0, events: [] },
  };
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace(['session-hidden']);
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: { get: (id) => (id === 'session-live' ? liveAgent : undefined) },
      approval,
    }),
    config: testConfig({ sessions: { hideInternal: false, cacheFile: false } }),
    hiddenStore,
    logger: silent,
  });

  assert.deepEqual(await adapter.setApprovalPolicy('session-live', 'never'), {
    supported: true,
    live: true,
    policy: 'never',
  });
  assert.deepEqual(approval.calls, [
    { method: 'setPolicy', sessionId: 'session-live', policy: 'never' },
    { method: 'effectivePolicy', sessionId: 'session-live' },
  ]);

  await assert.rejects(
    () => adapter.setApprovalPolicy('session-live', 'sometimes'),
    (error) => error instanceof ApiError && error.code === 'invalid_request' && error.status === 400,
  );
  await assert.rejects(
    () => adapter.setApprovalPolicy('session-cold', 'never'),
    (error) =>
      error instanceof ApiError &&
      error.code === 'invalid_request' &&
      error.status === 400 &&
      /open/.test(error.message),
  );
  await assert.rejects(
    () => adapter.setApprovalPolicy('session-hidden', 'never'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  const noApproval = createDshAdapter({
    ctx: createFakeCtx({
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: { get: (id) => (id === 'session-live' ? liveAgent : undefined) },
    }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(
    () => noApproval.setApprovalPolicy('session-live', 'never'),
    (error) => error instanceof ApiError && error.code === 'unsupported' && error.status === 501,
  );

  const failing = fakeApprovalPolicyService({
    setPolicy() {
      const error = new Error('agent is busy');
      error.code = 'session/agent-busy';
      throw error;
    },
  });
  const failingAdapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: { get: (id) => (id === 'session-live' ? liveAgent : undefined) },
      approval: failing,
    }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(() => failingAdapter.setApprovalPolicy('session-live', 'never'));
});

test('getModelCatalog maps/caps the catalog, isolates failures and never leaks secrets', async () => {
  const controller = fakeSessionController({
    modelCatalog: async () => ({
      default: { provider: 'deepseek', model: 'deepseek-chat' },
      routableProviders: ['deepseek'],
      groups: [
        {
          id: 'deepseek',
          name: 'DeepSeek',
          models: [
            {
              id: 'deepseek-chat',
              name: 'DeepSeek Chat',
              apiKey: 'sk-secret',
              reasoning: { efforts: [{ id: 'high', name: 'High' }], defaultEffort: 'high' },
            },
          ],
        },
      ],
      failures: [
        {
          id: 'other',
          name: 'Other',
          // Raw provider error text can contain credentials/headers/userinfo.
          message: 'request failed: Authorization: Bearer abcdefgh https://user:pass@internal sk-live-secret123',
          token: 'sk-secret',
        },
      ],
    }),
  });
  const adapter = makeAdapter(createFakeCtx({ sessionController: controller }));
  const catalog = await adapter.getModelCatalog();
  assert.equal(catalog.supported, true);
  assert.deepEqual(catalog.default, { provider: 'deepseek', model: 'deepseek-chat' });
  assert.deepEqual(catalog.groups[0].models[0].reasoning.efforts, [{ id: 'high', name: 'High' }]);
  assert.deepEqual(catalog.failures, [{ id: 'other', name: 'Other', message: 'Provider unavailable' }]);
  assert.equal(catalog.truncated, false);
  const serialized = JSON.stringify(catalog);
  assert.equal(serialized.includes('sk-secret'), false);
  assert.equal(serialized.includes('sk-live'), false);
  assert.equal(serialized.includes('Bearer'), false);
  assert.equal(serialized.includes('user:pass'), false);

  const absent = makeAdapter(createFakeCtx({}));
  await assert.rejects(
    () => absent.getModelCatalog(),
    (error) => error instanceof ApiError && error.code === 'unsupported' && error.status === 501,
  );

  const failing = makeAdapter(
    createFakeCtx({
      sessionController: fakeSessionController({
        modelCatalog: async () => {
          throw new Error('catalog exploded');
        },
      }),
    }),
  );
  await assert.rejects(
    () => failing.getModelCatalog(),
    (error) => error instanceof ApiError && error.code === 'internal' && error.status === 500,
  );
});

test('getSessionModel folds durable selection without activating and falls back to the default', async () => {
  const snapshots = {
    'session-live': {
      session: { ...HEADER, id: 'session-live' },
      inheritedEventCount: 0,
      events: [
        { type: 'model/selection', seq: 1, data: { provider: 'deepseek', model: 'deepseek-reasoner', reasoningEffort: 'high' } },
        { type: 'request/header', seq: 2, data: { header: { config: { provider: 'deepseek', model: 'deepseek-chat' } } } },
      ],
    },
    'session-cold': {
      session: { ...HEADER, id: 'session-cold' },
      inheritedEventCount: 0,
      events: [{ type: 'model/selection', seq: 1, data: { provider: 'other', model: 'other-model' } }],
    },
    'session-empty': { session: { ...HEADER, id: 'session-empty' }, inheritedEventCount: 0, events: [] },
    'session-unreadable': { session: { ...HEADER, id: 'session-unreadable' }, inheritedEventCount: 0, events: [] },
    'session-hidden': { session: { ...HEADER, id: 'session-hidden' }, inheritedEventCount: 0, events: [] },
    [BARE_SESSION]: { session: { ...HEADER, id: BARE_SESSION, origin: 'subagent' }, inheritedEventCount: 0, events: [] },
  };
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace(['session-hidden']);
  const liveAgent = { id: 'session-live', session: { id: 'session-live' } };
  const controller = fakeSessionController({ items: [] });
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: controller,
      sessionQuery: fakeSessionQuery({
        snapshots,
        read: async (sessionId) => {
          if (sessionId === 'session-unreadable') throw new Error('unreadable');
          return snapshots[sessionId];
        },
      }),
      agents: { get: (id) => (id === 'session-live' ? liveAgent : undefined) },
      agentDefaultModel: { currentSelection: () => ({ provider: 'deepseek', model: 'deepseek-chat' }) },
    }),
    config: testConfig({ sessions: { hideInternal: false, cacheFile: false } }),
    hiddenStore,
    logger: silent,
  });

  assert.deepEqual(await adapter.getSessionModel('session-live'), {
    supported: true,
    live: true,
    selection: { provider: 'deepseek', model: 'deepseek-chat' },
    source: 'session',
  });
  assert.deepEqual(await adapter.getSessionModel('session-cold'), {
    supported: true,
    live: false,
    selection: { provider: 'other', model: 'other-model' },
    source: 'session',
  });
  assert.deepEqual(await adapter.getSessionModel('session-empty'), {
    supported: true,
    live: false,
    selection: { provider: 'deepseek', model: 'deepseek-chat' },
    source: 'default',
  });
  assert.deepEqual(await adapter.getSessionModel('session-unreadable'), {
    supported: true,
    live: false,
    selection: { provider: 'deepseek', model: 'deepseek-chat' },
    source: 'default',
  });
  assert.equal(
    controller.calls.some((call) => call.method === 'selectModel'),
    false,
    'cold reads never activate or select',
  );

  for (const id of ['session-hidden', BARE_SESSION, 'session-missing']) {
    await assert.rejects(
      () => adapter.getSessionModel(id),
      (error) => error instanceof ApiError && error.code === 'not_found' && error.status === 404,
    );
  }
});

test('selectSessionModel is live-only, calls selectModel with exact args and maps errors', async () => {
  const snapshots = {
    'session-live': { session: { ...HEADER, id: 'session-live' }, inheritedEventCount: 0, events: [] },
    'session-cold': { session: { ...HEADER, id: 'session-cold' }, inheritedEventCount: 0, events: [] },
    'session-hidden': { session: { ...HEADER, id: 'session-hidden' }, inheritedEventCount: 0, events: [] },
  };
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace(['session-hidden']);
  const liveAgent = { id: 'session-live', session: { id: 'session-live' } };
  const controller = fakeSessionController({
    selectModel: async (request) => ({
      selected: {
        provider: request.provider,
        model: request.model,
        ...(request.reasoningEffort === undefined ? {} : { reasoningEffort: request.reasoningEffort }),
      },
    }),
  });
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: controller,
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: { get: (id) => (id === 'session-live' ? liveAgent : undefined) },
    }),
    config: testConfig({ sessions: { hideInternal: false, cacheFile: false } }),
    hiddenStore,
    logger: silent,
  });

  assert.deepEqual(
    await adapter.selectSessionModel('session-live', {
      provider: ' deepseek ',
      model: ' deepseek-reasoner ',
      reasoningEffort: ' high ',
    }),
    {
      supported: true,
      live: true,
      selection: { provider: 'deepseek', model: 'deepseek-reasoner', reasoningEffort: 'high' },
    },
  );
  assert.deepEqual(controller.calls, [
    {
      method: 'selectModel',
      request: {
        sessionId: 'session-live',
        provider: 'deepseek',
        model: 'deepseek-reasoner',
        reasoningEffort: 'high',
      },
    },
  ]);

  await assert.rejects(
    () => adapter.selectSessionModel('session-live', { provider: 'p' }),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );
  await assert.rejects(
    () => adapter.selectSessionModel('session-cold', { provider: 'p', model: 'm' }),
    (error) => error instanceof ApiError && error.code === 'invalid_request' && /open/.test(error.message),
  );
  await assert.rejects(
    () => adapter.selectSessionModel('session-hidden', { provider: 'p', model: 'm' }),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.selectSessionModel('session-missing', { provider: 'p', model: 'm' }),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  assert.equal(controller.calls.filter((call) => call.method === 'selectModel').length, 1, 'no write for cold/hidden');

  const noService = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: {
        async list() {
          return { items: [] };
        },
      },
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: { get: (id) => (id === 'session-live' ? liveAgent : undefined) },
    }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(
    () => noService.selectSessionModel('session-live', { provider: 'p', model: 'm' }),
    (error) => error instanceof ApiError && error.code === 'unsupported' && error.status === 501,
  );

  const unavailable = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({
        selectModel: async () => {
          const error = new Error('Model is not available to this account');
          error.code = 'session/model-unavailable';
          throw error;
        },
      }),
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: { get: (id) => (id === 'session-live' ? liveAgent : undefined) },
    }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(
    () => unavailable.selectSessionModel('session-live', { provider: 'p', model: 'm' }),
    (error) =>
      error instanceof ApiError &&
      error.code === 'invalid_request' &&
      error.status === 400 &&
      /not available/.test(error.message),
  );
});

test('selectSessionModel re-checks live immediately before calling selectModel', async () => {
  const snapshots = {
    'session-live': { session: { ...HEADER, id: 'session-live' }, inheritedEventCount: 0, events: [] },
  };
  const controller = fakeSessionController();
  let agentChecks = 0;
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: controller,
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: {
        get: () => {
          agentChecks += 1;
          return agentChecks === 1 ? { id: 'session-live', session: { id: 'session-live' } } : undefined;
        },
      },
    }),
    config: testConfig({ sessions: { hideInternal: false, cacheFile: false } }),
    logger: silent,
  });

  await assert.rejects(
    () => adapter.selectSessionModel('session-live', { provider: 'p', model: 'm' }),
    (error) => error instanceof ApiError && error.code === 'invalid_request' && /open/.test(error.message),
  );
  assert.equal(agentChecks, 2, 'the adapter checks live twice: before validation and immediately before the call');
  assert.equal(
    controller.calls.filter((call) => call.method === 'selectModel').length,
    0,
    'selectModel is never called after the session detached',
  );
});

test('listSessionQueue maps live inbox items in target order without activating cold sessions', async () => {
  const snapshots = {
    'session-queue-live': { session: { ...HEADER, id: 'session-queue-live' }, inheritedEventCount: 0, events: [] },
    'session-queue-cold': { session: { ...HEADER, id: 'session-queue-cold' }, inheritedEventCount: 0, events: [] },
    'session-hidden': { session: { ...HEADER, id: 'session-hidden' }, inheritedEventCount: 0, events: [] },
    [BARE_SESSION]: { session: { ...HEADER, id: BARE_SESSION, origin: 'subagent' }, inheritedEventCount: 0, events: [] },
  };
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace(['session-hidden']);
  const liveAgent = {
    id: 'session-queue-live',
    session: { id: 'session-queue-live' },
    status: 'running',
    inbox: {
      nextTurn: [
        {
          id: 'm_turn',
          content: [
            { type: 'text', text: 'queued turn' },
            { type: 'image', attachment: { attachmentId: 'att_q1' } },
          ],
          source: { kind: 'user' },
          createdAt: T(1000),
        },
      ],
      nextStep: [{ id: 'm_step', content: [{ type: 'text', text: 'queued step' }], source: { kind: 'user' } }],
    },
  };
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: { get: (id) => (id === 'session-queue-live' ? liveAgent : undefined) },
    }),
    config: testConfig({ sessions: { hideInternal: false, cacheFile: false } }),
    hiddenStore,
    logger: silent,
  });

  assert.deepEqual(await adapter.listSessionQueue('session-queue-live'), {
    supported: true,
    live: true,
    items: [
      {
        itemId: 'm_turn',
        text: 'queued turn',
        target: 'next-turn',
        attachmentCount: 1,
        createdAt: '2026-10-30T09:40:01.000Z',
      },
      { itemId: 'm_step', text: 'queued step', target: 'next-step', attachmentCount: 0 },
    ],
  });
  assert.deepEqual(await adapter.listSessionQueue('session-queue-cold'), {
    supported: true,
    live: false,
    items: [],
  });

  for (const id of ['session-hidden', BARE_SESSION, 'session-queue-missing']) {
    await assert.rejects(
      () => adapter.listSessionQueue(id),
      (error) => error instanceof ApiError && error.code === 'not_found' && error.status === 404,
    );
  }

  const noAgents = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: testConfig({ sessions: { hideInternal: false, cacheFile: false } }),
    hiddenStore,
    logger: silent,
  });
  assert.deepEqual(await noAgents.listSessionQueue('session-queue-cold'), {
    supported: false,
    live: false,
    items: [],
  });
});

test('updateSessionQueue calls updateQueue with exact actions and maps errors', async () => {
  const snapshots = {
    'session-queue-live': { session: { ...HEADER, id: 'session-queue-live' }, inheritedEventCount: 0, events: [] },
    'session-queue-cold': { session: { ...HEADER, id: 'session-queue-cold' }, inheritedEventCount: 0, events: [] },
    'session-hidden': { session: { ...HEADER, id: 'session-hidden' }, inheritedEventCount: 0, events: [] },
  };
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace(['session-hidden']);
  const liveAgent = { id: 'session-queue-live', session: { id: 'session-queue-live' }, status: 'running', inbox: { nextTurn: [], nextStep: [] } };
  const controller = fakeSessionController({
    updateQueue: async (request) => {
      if (request.itemId === 'm_gone') {
        const error = new Error('queued item is no longer pending');
        error.code = 'session/queue-item-not-found';
        throw error;
      }
      if (request.itemId === 'm_steer') {
        const error = new Error('current turn no longer accepts steering');
        error.code = 'session/steer-unavailable';
        throw error;
      }
      return { accepted: true };
    },
  });
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: controller,
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: { get: (id) => (id === 'session-queue-live' ? liveAgent : undefined) },
    }),
    config: testConfig({ sessions: { hideInternal: false, cacheFile: false } }),
    hiddenStore,
    logger: silent,
  });

  assert.deepEqual(
    await adapter.updateSessionQueue('session-queue-live', 'm_q1', { action: 'edit', text: '  updated  ' }),
    { accepted: true },
  );
  assert.deepEqual(controller.calls.at(-1).request, {
    sessionId: 'session-queue-live',
    itemId: 'm_q1',
    action: { kind: 'edit', content: [{ type: 'text', text: 'updated' }] },
  });
  await adapter.updateSessionQueue('session-queue-live', 'm_q2', { action: 'remove' });
  assert.deepEqual(controller.calls.at(-1).request.action, { kind: 'remove' });
  await adapter.updateSessionQueue('session-queue-live', 'm_q3', { action: 'steer' });
  assert.deepEqual(controller.calls.at(-1).request.action, { kind: 'steer' });

  await assert.rejects(
    () => adapter.updateSessionQueue('session-queue-live', 'm_q1', { action: 'edit', text: '' }),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );
  await assert.rejects(
    () => adapter.updateSessionQueue('session-queue-cold', 'm_q1', { action: 'remove' }),
    (error) => error instanceof ApiError && error.code === 'invalid_request' && /open/.test(error.message),
  );
  await assert.rejects(
    () => adapter.updateSessionQueue('session-hidden', 'm_q1', { action: 'remove' }),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.updateSessionQueue('session-queue-missing', 'm_q1', { action: 'remove' }),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.updateSessionQueue('session-queue-live', 'm_gone', { action: 'remove' }),
    (error) => error instanceof ApiError && error.code === 'not_found' && error.status === 404,
  );
  await assert.rejects(
    () => adapter.updateSessionQueue('session-queue-live', 'm_steer', { action: 'steer' }),
    (error) =>
      error instanceof ApiError &&
      error.code === 'invalid_request' &&
      error.status === 400 &&
      /Steering/.test(error.message),
  );

  const noService = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: {
        async list() {
          return { items: [] };
        },
      },
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: { get: (id) => (id === 'session-queue-live' ? liveAgent : undefined) },
    }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(
    () => noService.updateSessionQueue('session-queue-live', 'm_q1', { action: 'remove' }),
    (error) => error instanceof ApiError && error.code === 'unsupported' && error.status === 501,
  );

  let agentChecks = 0;
  const racingController = fakeSessionController();
  const racing = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: racingController,
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: {
        get: () => {
          agentChecks += 1;
          return agentChecks === 1 ? liveAgent : undefined;
        },
      },
    }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(
    () => racing.updateSessionQueue('session-queue-live', 'm_q1', { action: 'remove' }),
    (error) => error instanceof ApiError && error.code === 'invalid_request' && /open/.test(error.message),
  );
  assert.equal(agentChecks, 2, 'live is checked again immediately before updateQueue');
  assert.equal(
    racingController.calls.filter((call) => call.method === 'updateQueue').length,
    0,
    'updateQueue is never called after the session detached',
  );
});

test('forkSession calls the controller, validates atSeq and maps errors', async () => {
  const snapshots = {
    'session-fork-source': { session: { ...HEADER, id: 'session-fork-source' }, inheritedEventCount: 0, events: [] },
    'session-hidden': { session: { ...HEADER, id: 'session-hidden' }, inheritedEventCount: 0, events: [] },
    [BARE_SESSION]: { session: { ...HEADER, id: BARE_SESSION, origin: 'subagent' }, inheritedEventCount: 0, events: [] },
  };
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace(['session-hidden']);
  let forkError = null;
  const controller = fakeSessionController({
    fork: async (request) => {
      if (forkError) throw forkError;
      return { sessionId: 'session-forked-1' };
    },
  });
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: controller,
      sessionQuery: fakeSessionQuery({ snapshots }),
    }),
    config: testConfig({ sessions: { hideInternal: false, cacheFile: false } }),
    hiddenStore,
    logger: silent,
  });

  assert.deepEqual(await adapter.forkSession('session-fork-source'), {
    sessionId: 'session-forked-1',
    forkedFrom: 'session-fork-source',
  });
  assert.deepEqual(controller.calls.at(-1), { method: 'fork', request: { sessionId: 'session-fork-source' } });

  assert.deepEqual(await adapter.forkSession('session-fork-source', 12), {
    sessionId: 'session-forked-1',
    forkedFrom: 'session-fork-source',
    atSeq: 12,
  });
  assert.deepEqual(controller.calls.at(-1), {
    method: 'fork',
    request: { sessionId: 'session-fork-source', atSeq: 12 },
  });

  for (const atSeq of [-1, 1.5, '3', Number.MAX_SAFE_INTEGER + 1]) {
    await assert.rejects(
      () => adapter.forkSession('session-fork-source', atSeq),
      (error) => error instanceof ApiError && error.code === 'invalid_request' && error.status === 400,
    );
  }
  for (const id of ['session-hidden', BARE_SESSION, 'session-fork-missing']) {
    await assert.rejects(
      () => adapter.forkSession(id),
      (error) => error instanceof ApiError && error.code === 'not_found' && error.status === 404,
    );
  }

  const errorOf = (code, details) => {
    const error = new Error('fork failed with C:\\internal\\path');
    error.code = code;
    if (details !== undefined) error.details = details;
    return error;
  };
  forkError = errorOf('session/fork-unavailable');
  await assert.rejects(
    () => adapter.forkSession('session-fork-source'),
    (error) =>
      error instanceof ApiError &&
      error.code === 'invalid_request' &&
      error.status === 400 &&
      /completed turn/.test(error.message),
  );
  forkError = errorOf('session/fork-unavailable');
  await assert.rejects(
    () => adapter.forkSession('session-fork-source', 7),
    (error) => error instanceof ApiError && error.code === 'invalid_request' && /boundary/.test(error.message),
  );
  forkError = errorOf('session/not-found');
  await assert.rejects(
    () => adapter.forkSession('session-fork-source'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  // Workspace attach failure: the child exists, so report it with a warning.
  forkError = errorOf('session/workspace-attach-failed', { sessionId: 'session-child-1' });
  assert.deepEqual(await adapter.forkSession('session-fork-source', 2), {
    sessionId: 'session-child-1',
    forkedFrom: 'session-fork-source',
    atSeq: 2,
    warning: 'workspace-attach-failed',
  });

  forkError = errorOf('gateway/internal');
  await assert.rejects(
    () => adapter.forkSession('session-fork-source'),
    (error) => error instanceof ApiError && error.code === 'internal' && error.status === 500,
  );

  const noService = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(
    () => noService.forkSession('session-fork-source'),
    (error) => error instanceof ApiError && error.code === 'unsupported' && error.status === 501,
  );
});

test('searchSessions applies role/date filters during the scan and echoes them', async () => {
  const snapshots = {
    'session-filter-a': {
      session: { ...HEADER, id: 'session-filter-a' },
      inheritedEventCount: 0,
      events: [
        { type: 'user/message', seq: 1, time: T(1000), data: { id: 'm_u_a', content: [{ type: 'text', text: 'needle user' }], source: { kind: 'user' } } },
        { type: 'assistant/message', seq: 2, time: T(2000), data: { message: { id: 'm_a_a', content: [{ type: 'text', text: 'needle assistant' }] } } },
        { type: 'tool/call', seq: 3, time: T(4000), data: { callId: 'c1', name: 'Pwsh', arguments: '{"command":"needle tool"}' } },
        { type: 'user/message', seq: 4, data: { id: 'm_undated', content: [{ type: 'text', text: 'needle undated' }], source: { kind: 'user' } } },
      ],
    },
    'session-filter-b': {
      session: { ...HEADER, id: 'session-filter-b' },
      inheritedEventCount: 0,
      events: [
        { type: 'assistant/message', seq: 1, time: T(6000), data: { message: { id: 'm_a_b', content: [{ type: 'text', text: 'needle b' }] } } },
      ],
    },
  };
  const items = [
    { sessionId: 'session-filter-a', updatedAt: T(5000), running: false, agentAvailable: false, cwd: 'C:\\work' },
    { sessionId: 'session-filter-b', updatedAt: T(4000), running: false, agentAvailable: false, cwd: 'C:\\work' },
  ];
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({ items }),
      sessionQuery: fakeSessionQuery({ snapshots }),
    }),
    config: testConfig(),
    logger: silent,
  });

  const unfiltered = await adapter.searchSessions('needle', 10);
  assert.equal(unfiltered.hits.length, 5);
  assert.equal('filters' in unfiltered, false, 'unfiltered responses stay unchanged');

  const assistants = await adapter.searchSessions('needle', 10, { role: 'assistant' });
  assert.deepEqual(
    assistants.hits.map((hit) => [hit.sessionId, hit.messageId, hit.role]),
    [
      ['session-filter-b', 'm_a_b', 'assistant'],
      ['session-filter-a', 'm_a_a', 'assistant'],
    ],
  );
  assert.deepEqual(assistants.filters, { role: ['assistant'] });

  const dated = await adapter.searchSessions('needle', 10, {
    since: '2026-10-30T09:40:02Z',
    until: '2026-10-30T09:40:06Z',
  });
  assert.deepEqual(
    dated.hits.map((hit) => [hit.messageId, hit.role, hit.createdAt]),
    [
      ['m_a_b', 'assistant', '2026-10-30T09:40:06Z'],
      ['m_c1', 'tool', '2026-10-30T09:40:04Z'],
      ['m_a_a', 'assistant', '2026-10-30T09:40:02Z'],
    ],
    'date bounds are inclusive and exclude undated events',
  );
  assert.deepEqual(dated.filters, {
    since: '2026-10-30T09:40:02.000Z',
    until: '2026-10-30T09:40:06.000Z',
  });

  const toolAfter = await adapter.searchSessions('needle', 10, { role: 'tool', since: T(5000) });
  assert.deepEqual(toolAfter.hits, []);
  assert.equal(toolAfter.truncated, false);
  assert.deepEqual(toolAfter.filters, { role: ['tool'], since: new Date(T(5000)).toISOString() });

  await assert.rejects(
    () => adapter.searchSessions('needle', 10, { role: 'system' }),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );
});

test('GET /search exercises the full router+adapter path and keeps the role echo', async () => {
  const snapshots = {
    'session-integration': {
      session: { ...HEADER, id: 'session-integration' },
      inheritedEventCount: 0,
      events: [
        { type: 'user/message', seq: 1, time: T(1000), data: { id: 'm_u_int', content: [{ type: 'text', text: 'needle user' }], source: { kind: 'user' } } },
        { type: 'assistant/message', seq: 2, time: T(2000), data: { message: { id: 'm_a_int', content: [{ type: 'text', text: 'needle assistant' }] } } },
        { type: 'tool/call', seq: 3, time: T(4000), data: { callId: 'c_int', name: 'Pwsh', arguments: '{"command":"needle tool"}' } },
      ],
    },
  };
  const config = testConfig();
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({
        items: [{ sessionId: 'session-integration', updatedAt: T(5000), running: false, agentAvailable: false, cwd: 'C:\\work' }],
      }),
      sessionQuery: fakeSessionQuery({ snapshots }),
    }),
    config,
    logger: silent,
  });
  const pairing = makePairing(config);
  const router = createRouter({ config, pairing, adapter, logger: silent });
  const token = issueToken(pairing).token;

  // Role-only: the role filter and its echo survive the full path.
  const roleOnly = await routeCall(router, {
    path: '/mobile/v1/search',
    token,
    query: 'q=needle&limit=10&role=assistant',
  });
  assert.equal(roleOnly.status, 200);
  assert.deepEqual(roleOnly.body.filters, { role: ['assistant'] });
  assert.deepEqual(roleOnly.body.hits.map((hit) => hit.role), ['assistant']);
  assert.deepEqual(roleOnly.body.hits.map((hit) => hit.seq), [2]);

  // Date-only: only the date echo.
  const dateOnly = await routeCall(router, {
    path: '/mobile/v1/search',
    token,
    query: 'q=needle&limit=10&since=2026-10-30T09:40:02Z&until=2026-10-30T09:40:06Z',
  });
  assert.equal(dateOnly.status, 200);
  assert.deepEqual(dateOnly.body.filters, {
    since: '2026-10-30T09:40:02.000Z',
    until: '2026-10-30T09:40:06.000Z',
  });
  assert.deepEqual(dateOnly.body.hits.map((hit) => hit.messageId), ['m_c_int', 'm_a_int']);
  assert.deepEqual(dateOnly.body.hits.map((hit) => hit.seq), [3, 2]);

  // Combined role+date: both applied and echoed, hits filtered.
  const combined = await routeCall(router, {
    path: '/mobile/v1/search',
    token,
    query: 'q=needle&limit=10&role=assistant&since=2026-10-30T09:40:02Z',
  });
  assert.equal(combined.status, 200);
  assert.deepEqual(combined.body.filters, {
    role: ['assistant'],
    since: '2026-10-30T09:40:02.000Z',
  });
  assert.deepEqual(combined.body.hits.map((hit) => hit.role), ['assistant']);
  assert.deepEqual(combined.body.hits.map((hit) => hit.seq), [2]);
});

test('searchSessions pages newest-first results with stable offset/nextOffset/hasMore', async () => {
  const events = [
    { type: 'user/message', seq: 1, time: T(500), data: { id: 'm_user', content: [{ type: 'text', text: 'needle user' }], source: { kind: 'user' } } },
  ];
  for (let index = 1; index <= 5; index += 1) {
    events.push({
      type: 'assistant/message',
      seq: index + 1,
      time: T(index * 1000),
      data: { message: { id: `m_${index}`, content: [{ type: 'text', text: `needle ${index}` }] } },
    });
  }
  const snapshots = {
    'session-pagination': { session: { ...HEADER, id: 'session-pagination' }, inheritedEventCount: 0, events },
  };
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({
        items: [{ sessionId: 'session-pagination', updatedAt: T(9000), running: false, agentAvailable: false, cwd: 'C:\\work' }],
      }),
      sessionQuery: fakeSessionQuery({ snapshots }),
    }),
    config: testConfig(),
    logger: silent,
  });

  const first = await adapter.searchSessions('needle', 2);
  assert.deepEqual(first.hits.map((hit) => hit.messageId), ['m_5', 'm_4']);
  assert.deepEqual(first.hits.map((hit) => hit.seq), [6, 5]);
  assert.equal(first.offset, 0);
  assert.equal(first.nextOffset, 2);
  assert.equal(first.hasMore, true);
  assert.equal(first.truncated, false, 'a partial page is pagination, not a scan cap');

  const second = await adapter.searchSessions('needle', 2, undefined, 2);
  assert.deepEqual(second.hits.map((hit) => hit.messageId), ['m_3', 'm_2']);
  assert.deepEqual(second.hits.map((hit) => hit.seq), [4, 3]);
  assert.equal(second.offset, 2);
  assert.equal(second.nextOffset, 4);
  assert.equal(second.hasMore, true);

  const last = await adapter.searchSessions('needle', 2, undefined, 4);
  assert.deepEqual(last.hits.map((hit) => hit.messageId), ['m_1', 'm_user']);
  assert.deepEqual(last.hits.map((hit) => hit.seq), [2, 1]);
  assert.equal(last.nextOffset, null, 'last page has no nextOffset');
  assert.equal(last.hasMore, false);
  assert.equal(last.truncated, false);

  const beyond = await adapter.searchSessions('needle', 2, undefined, 10);
  assert.deepEqual(beyond.hits, []);
  assert.equal(beyond.offset, 10);
  assert.equal(beyond.nextOffset, null);
  assert.equal(beyond.hasMore, false);

  const atCap = await adapter.searchSessions('needle', 2, undefined, 500);
  assert.deepEqual(atCap.hits, []);
  assert.equal(atCap.nextOffset, null);
  assert.equal(atCap.hasMore, false);

  // Filters are applied during the scan, then pagination slices the filtered set.
  const filtered = await adapter.searchSessions('needle', 2, { role: 'assistant' }, 2);
  assert.deepEqual(filtered.hits.map((hit) => hit.messageId), ['m_3', 'm_2']);
  assert.deepEqual(filtered.filters, { role: ['assistant'] });
  assert.equal(filtered.nextOffset, 4);
  assert.equal(filtered.hasMore, true);

  const userOnly = await adapter.searchSessions('needle', 2, { role: 'user' });
  assert.deepEqual(userOnly.hits.map((hit) => hit.messageId), ['m_user']);
  assert.deepEqual(userOnly.hits.map((hit) => hit.seq), [1]);
  assert.equal(userOnly.hasMore, false);
  assert.equal(userOnly.nextOffset, null);

  await assert.rejects(
    () => adapter.searchSessions('needle', 2, undefined, -1),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );
});

test('search uses lightweight semantic documents, not slow image-heavy snapshots', async () => {
  const snapshots = {};
  const items = [];
  for (let index = 0; index < 3; index += 1) {
    const sessionId = `session-perf-${index}`;
    snapshots[sessionId] = {
      session: { ...HEADER, id: sessionId },
      inheritedEventCount: 0,
      events: [],
    };
    items.push({ sessionId, updatedAt: T(5000 - index), running: false, agentAvailable: false, cwd: 'C:\\work' });
  }
  let readCalls = 0;
  const sessionQuery = fakeSessionQuery({
    snapshots,
    // QA reproduction: full snapshot reads on the image-heavy corpus cost ~1.5 s.
    read: async (sessionId) => {
      readCalls += 1;
      await new Promise((resolve) => setTimeout(resolve, 1500));
      return snapshots[sessionId];
    },
    filterEvents: async (sessionId) => [
      {
        sessionId,
        seq: 7,
        type: sessionId.endsWith('2') ? 'tool/call' : 'assistant/message',
        time: T(3000),
        surface: 'current',
        text: 'needle release note',
      },
    ],
  });
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({ items }),
      sessionQuery,
    }),
    config: testConfig(),
    logger: silent,
  });

  const started = Date.now();
  const result = await adapter.searchSessions('needle', 10);
  const elapsed = Date.now() - started;
  assert.equal(result.supported, true);
  assert.equal(result.hits.length, 3);
  assert.deepEqual(result.hits.map((hit) => hit.role).sort(), ['assistant', 'assistant', 'tool']);
  assert.equal(result.scannedSessions, 3);
  assert.equal(result.totalSessions, 3);
  assert.equal(result.scanIncomplete, false);
  assert.equal(result.truncated, false);
  assert.equal(
    sessionQuery.calls.filter((call) => call.method === 'filterEvents').length,
    3,
    'the search itself reads only semantic documents',
  );
  assert.equal(elapsed < 1000, true, `semantic search must not await 1.5 s snapshots (${elapsed}ms)`);
  assert.equal(result.hits.some((hit) => hit.snippet.includes('needle')), true);
  assert.deepEqual(result.hits.map((hit) => hit.seq), [7, 7, 7], 'semantic-document seq on each hit');
});

test('search parallelises slow snapshot reads and returns hits within the 8 s budget', async () => {
  const snapshots = {};
  const items = [];
  for (let index = 0; index < 10; index += 1) {
    const sessionId = `session-slow-${index}`;
    snapshots[sessionId] = {
      session: { ...HEADER, id: sessionId },
      inheritedEventCount: 0,
      events: [
        {
          type: 'assistant/message',
          seq: 1,
          time: T(index * 1000),
          data: { message: { id: `m_slow_${index}`, content: [{ type: 'text', text: `needle ${index}` }] } },
        },
      ],
    };
    items.push({ sessionId, updatedAt: T(10_000 - index), running: false, agentAvailable: false, cwd: 'C:\\work' });
  }
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({ items }),
      sessionQuery: fakeSessionQuery({
        snapshots,
        read: async (sessionId) => {
          await new Promise((resolve) => setTimeout(resolve, 120));
          return snapshots[sessionId];
        },
      }),
    }),
    config: testConfig(),
    logger: silent,
  });

  const started = Date.now();
  const result = await adapter.searchSessions('needle', 10);
  const elapsed = Date.now() - started;
  assert.equal(result.hits.length, 10);
  assert.equal(result.scannedSessions, 10);
  assert.equal(result.totalSessions, 10);
  assert.equal(result.scanIncomplete, false);
  assert.equal(elapsed < 900, true, `bounded concurrency must overlap snapshot reads (${elapsed}ms)`);
});

test('search reports scanIncomplete with partial hits when the budget cuts reads', async () => {
  const snapshots = {};
  const items = [];
  for (let index = 0; index < 10; index += 1) {
    const sessionId = `session-budget-${index}`;
    snapshots[sessionId] = {
      session: { ...HEADER, id: sessionId },
      inheritedEventCount: 0,
      events: [
        {
          type: 'assistant/message',
          seq: 1,
          time: T(index * 1000),
          data: { message: { id: `m_budget_${index}`, content: [{ type: 'text', text: `needle ${index}` }] } },
        },
      ],
    };
    items.push({ sessionId, updatedAt: T(10_000 - index), running: false, agentAvailable: false, cwd: 'C:\\work' });
  }
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({ items }),
      sessionQuery: fakeSessionQuery({
        snapshots,
        read: async (sessionId) => {
          await new Promise((resolve) => setTimeout(resolve, 150));
          return snapshots[sessionId];
        },
      }),
    }),
    config: testConfig(),
    logger: silent,
    searchBudgetMs: 250,
  });

  const result = await adapter.searchSessions('needle', 10);
  assert.equal(result.supported, true);
  assert.equal(result.scanIncomplete, true, 'a partial scan must say so explicitly');
  assert.equal(result.truncated, true);
  assert.equal(result.scannedSessions < result.totalSessions, true);
  assert.equal(result.hits.length > 0, true, 'partial hits from scanned sessions are returned');
  assert.equal(result.hasMore, true);
});

/** @param {import('node:test').TestContext} t */
function makeWorkspaceImagesFixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-adapter-ws-images-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const cwd = path.join(root, 'project');
  const artifacts = path.join(root, 'artifacts');
  const outside = path.join(root, 'outside.png');
  fs.mkdirSync(cwd, { recursive: true });
  fs.mkdirSync(artifacts, { recursive: true });
  const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0]);
  fs.writeFileSync(path.join(cwd, 'shot.png'), png);
  fs.writeFileSync(path.join(artifacts, 'out.jpg'), Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0, 0]));
  fs.writeFileSync(outside, png);
  return { root, cwd, artifacts, outside };
}

test('session detail and readAttachment serve workspace images with no host paths and visibility guards', async (t) => {
  const { cwd, artifacts, outside } = makeWorkspaceImagesFixture(t);
  const sessionId = 'session-ws-images';
  const header = { ...HEADER, id: sessionId, cwd };
  const events = [
    {
      type: 'user/message',
      seq: 1,
      time: T(1000),
      data: {
        id: 'm_ws_user',
        role: 'user',
        content: [
          {
            type: 'text',
            text: `Look ![shot](shot.png) ![repeat](shot.png) ![outside](../outside.png) ![remote](https://x/y.png)`,
          },
        ],
        source: { kind: 'user' },
      },
    },
    {
      type: 'assistant/message',
      seq: 2,
      time: T(2000),
      data: {
        message: {
          id: 'm_ws_assistant',
          content: [{ type: 'text', text: `Artifact ![out](${path.join(artifacts, 'out.jpg')})` }],
        },
      },
    },
  ];
  const snapshots = { [sessionId]: { session: header, inheritedEventCount: 0, events } };
  const config = testConfig({ artifacts: { roots: [artifacts] } });
  const adapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config,
    logger: silent,
  });

  const detail = await adapter.getSession(sessionId);
  assert.equal(detail.messages.length, 2);
  assert.equal(detail.messages[0].attachments.length, 1, 'dedupe + outside/remote rejected');
  const userAttachment = detail.messages[0].attachments[0];
  assert.equal(userAttachment.kind, 'image');
  assert.equal(userAttachment.name, 'shot.png');
  assert.equal(userAttachment.mimeType, 'image/png');
  assert.equal(userAttachment.bytes, 12);
  assert.equal(userAttachment.id.startsWith('ws_'), true);
  assert.equal(detail.messages[1].attachments[0].name, 'out.jpg');
  const attachmentJson = JSON.stringify(detail.messages.flatMap((message) => message.attachments ?? []));
  assert.equal(attachmentJson.includes(cwd), false);
  assert.equal(attachmentJson.includes(artifacts), false);
  assert.equal(attachmentJson.includes('workspacePath'), false);
  assert.equal(attachmentJson.includes('allowedRoot'), false);
  assert.equal(JSON.stringify(detail.messages[0].text).includes('shot.png'), true, 'markdown text stays intact');

  const served = await adapter.readAttachment(sessionId, userAttachment.id);
  assert.equal(served.status, 200);
  assert.equal(served.headers['Content-Type'], 'image/png');
  assert.equal(served.headers['Content-Length'], '12');
  assert.equal(served.headers['Content-Disposition'], 'inline; filename="shot.png"');
  assert.equal(served.headers['Cache-Control'], 'private, max-age=300');
  assert.equal(served.headers['X-Content-Type-Options'], 'nosniff');
  assert.equal(Buffer.from(served.raw).toString('hex'), '89504e470d0a1a0a00000000');

  // The same synthetic attachment is readable through the existing bearer route.
  const pairing = makePairing(config);
  const token = issueToken(pairing).token;
  const router = createRouter({ config, pairing, adapter, logger: silent });
  const routed = await routeCall(router, {
    path: `/mobile/v1/sessions/${sessionId}/attachments/${userAttachment.id}`,
    token,
  });
  assert.equal(routed.status, 200);
  assert.equal(routed.headers['Content-Type'], 'image/png');
  assert.equal(Buffer.from(routed.raw).toString('hex'), '89504e470d0a1a0a00000000');
  const unauthorized = await routeCall(router, {
    path: `/mobile/v1/sessions/${sessionId}/attachments/${userAttachment.id}`,
  });
  assert.equal(unauthorized.status, 401);

  // A hidden session must not serve synthetic workspace images.
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace([sessionId]);
  const hiddenAdapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config,
    hiddenStore,
    logger: silent,
  });
  await assert.rejects(
    () => hiddenAdapter.readAttachment(sessionId, userAttachment.id),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  // Root removed from current config: the absolute artifact is no longer collected/served.
  const noRootsConfig = testConfig({ artifacts: { roots: [] } });
  const noRootsAdapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: noRootsConfig,
    logger: silent,
  });
  const noRootsDetail = await noRootsAdapter.getSession(sessionId);
  assert.equal('attachments' in noRootsDetail.messages[1], false);
  await assert.rejects(
    () => noRootsAdapter.readAttachment(sessionId, detail.messages[1].attachments[0].id),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  // Relative cwd refs still work after removing artifact roots.
  assert.equal((await noRootsAdapter.readAttachment(sessionId, userAttachment.id)).status, 200);

  // A file that disappears after the detail response is a 404 on read.
  fs.rmSync(path.join(cwd, 'shot.png'));
  await assert.rejects(
    () => adapter.readAttachment(sessionId, userAttachment.id),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  assert.equal(fs.existsSync(outside), true);
});

test('live SSE rows expose the same synthetic workspace images', async (t) => {
  const { cwd } = makeWorkspaceImagesFixture(t);
  const sessionId = 'session-ws-live';
  const session = { id: sessionId, header: { ...HEADER, id: sessionId, cwd } };
  const bus = new EventBus({ logger: silent, bufferSize: 100 });
  const ctx = createFakeCtx({
    sessionController: fakeSessionController({ items: [] }),
    sessionQuery: fakeSessionQuery({ snapshots: {} }),
  });
  const adapter = makeAdapter(ctx, bus, { config: testConfig({ artifacts: { roots: [] } }) });
  const stop = adapter.attach();

  ctx.emit('session/event', session, {
    type: 'user/message',
    seq: 1,
    time: T(1000),
    data: { id: 'm_live_user', role: 'user', content: [{ type: 'text', text: 'Live ![shot](shot.png)' }], source: { kind: 'user' } },
  });
  let message = bus.buffer.at(-1).data.message;
  assert.equal(message.attachments[0].name, 'shot.png');
  assert.equal(JSON.stringify(message.attachments).includes(cwd), false);

  ctx.emit('session/event', session, {
    type: 'tool/call',
    seq: 2,
    time: T(2000),
    data: { callId: 'call_ws', name: 'Bash', arguments: '{}' },
  });
  ctx.emit('session/event', session, {
    type: 'tool/result',
    seq: 3,
    time: T(3000),
    data: { message: { id: 'm_ws_result', toolCallId: 'call_ws', content: [{ type: 'text', text: 'made ![shot](shot.png)' }] } },
  });
  message = bus.buffer.at(-1).data.message;
  assert.equal(message.attachments[0].name, 'shot.png');
  assert.equal(JSON.stringify(message.attachments).includes(cwd), false);

  ctx.emit('session/event', session, {
    type: 'assistant/message',
    seq: 4,
    time: T(4000),
    data: { message: { id: 'm_ws_assistant_live', content: [{ type: 'text', text: 'Here ![shot](shot.png)' }] } },
  });
  const assistant = bus.buffer.filter((event) => event.type === 'message.added').at(-1).data.message;
  assert.equal(assistant.role, 'assistant');
  assert.equal(assistant.attachments[0].name, 'shot.png');
  stop();
});

test('remote image attachments are metadata-only on detail, fetch on read, and disabled by default', async () => {
  const sessionId = 'session-remote-images';
  const header = { ...HEADER, id: sessionId };
  const events = [
    {
      type: 'user/message',
      seq: 1,
      time: T(1000),
      data: {
        id: 'm_remote_user',
        role: 'user',
        content: [{ type: 'text', text: 'Hello ![cat](https://cdn.example.com/cat.png?size=1)' }],
        source: { kind: 'user' },
      },
    },
  ];
  const snapshots = { [sessionId]: { session: header, inheritedEventCount: 0, events } };
  const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0]);
  const fetcherCalls = [];
  const fetcher = {
    async get(url) {
      fetcherCalls.push(url);
      return { bytes: png, mimeType: 'image/png', name: 'cat.png' };
    },
  };
  const config = testConfig({ artifacts: { remote: { enabled: true, ttlMs: 60_000 } } });
  const adapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config,
    logger: silent,
    remoteImageFetcher: fetcher,
  });

  const detail = await adapter.getSession(sessionId);
  assert.equal(detail.messages[0].attachments.length, 1);
  const attachment = detail.messages[0].attachments[0];
  assert.equal(attachment.id.startsWith('ext_'), true);
  assert.equal(attachment.kind, 'image');
  assert.equal(attachment.name, 'cat.png');
  assert.equal(attachment.mimeType, 'image/png');
  assert.equal(attachment.bytes, 0);
  assert.equal(fetcherCalls.length, 0, 'mapping must never fetch remotely');
  assert.equal(JSON.stringify(attachment).includes('cdn.example.com'), false);
  assert.equal(JSON.stringify(attachment).includes('http'), false);

  const served = await adapter.readAttachment(sessionId, attachment.id);
  assert.equal(served.status, 200);
  assert.equal(served.headers['Content-Type'], 'image/png');
  assert.equal(served.headers['Content-Length'], String(png.length));
  assert.equal(served.headers['Content-Disposition'], 'inline; filename="cat.png"');
  assert.equal(served.headers['Cache-Control'], 'private, max-age=60');
  assert.equal(served.headers['X-Content-Type-Options'], 'nosniff');
  assert.deepEqual(Buffer.from(served.raw), png);
  assert.deepEqual(fetcherCalls, ['https://cdn.example.com/cat.png?size=1']);

  // Hidden sessions never serve remote refs.
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace([sessionId]);
  const hiddenAdapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config,
    logger: silent,
    hiddenStore,
    remoteImageFetcher: { get: async () => ({ bytes: png, mimeType: 'image/png', name: 'cat.png' }) },
  });
  await assert.rejects(
    () => hiddenAdapter.readAttachment(sessionId, attachment.id),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  // Fetcher failures are a generic 404 and never leak the URL/host.
  const failingAdapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config,
    logger: silent,
    remoteImageFetcher: {
      async get() {
        throw new Error('boom https://cdn.example.com/cat.png');
      },
    },
  });
  await assert.rejects(
    () => failingAdapter.readAttachment(sessionId, attachment.id),
    (error) =>
      error instanceof ApiError &&
      error.code === 'not_found' &&
      !String(error.message).includes('cdn.example.com') &&
      !String(error.message).includes('http'),
  );

  // Disabled config: no ext metadata and reads 404 (default posture).
  const disabled = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: testConfig(),
    logger: silent,
  });
  const disabledDetail = await disabled.getSession(sessionId);
  assert.equal('attachments' in disabledDetail.messages[0], false);
  await assert.rejects(
    () => disabled.readAttachment(sessionId, attachment.id),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
});

test('session summaries expose visible lineage and agent-preset fields', async () => {
  const parentHeader = { ...HEADER, id: 'session-parent', agentPreset: 'parent-preset' };
  const childHeader = {
    ...HEADER,
    id: 'session-child',
    parentSession: 'session-parent',
    agentPreset: 'coder',
  };
  const snapshots = {
    'session-parent': { session: parentHeader, inheritedEventCount: 0, events: [] },
    'session-child': {
      session: childHeader,
      inheritedEventCount: 0,
      events: [
        { type: 'agent-preset/selected', seq: 1, time: T(1000), data: { agentPreset: 'reviewer' } },
        // Real DSH shape: SessionEventMap['agent-preset/selected'] = { agentPreset: string }.
        { type: 'agent-preset/selected', seq: 2, time: T(2000), data: { agentPreset: 'reviewer2' } },
      ],
    },
  };
  const items = [
    { sessionId: 'session-parent', updatedAt: T(5000), running: false, agentAvailable: false, cwd: 'C:\\work' },
    {
      sessionId: 'session-child',
      parentSessionId: 'session-parent',
      updatedAt: T(4000),
      running: false,
      agentAvailable: false,
      cwd: 'C:\\work',
    },
  ];
  const makeLineageAdapter = (options = {}) =>
    createDshAdapter({
      ctx: createFakeCtx({
        sessionController: fakeSessionController({ items: options.items ?? items }),
        sessionQuery: fakeSessionQuery({ snapshots: options.snapshots ?? snapshots }),
        workspaceController: options.workspaceController,
      }),
      config: testConfig({ sessions: { hideInternal: false, cacheFile: false } }),
      logger: silent,
      hiddenStore: options.hiddenStore,
    });

  const adapter = makeLineageAdapter();
  const detail = await adapter.getSession('session-child');
  assert.equal(detail.session.parentSessionId, 'session-parent');
  assert.equal(detail.session.agentPreset, 'reviewer2', 'latest selected event wins over header');
  assert.equal('title' in detail.session, true);

  const list = await adapter.listSessions();
  const childRow = list.find((row) => row.id === 'session-child');
  assert.equal(childRow.parentSessionId, 'session-parent');
  assert.equal(childRow.agentPreset, 'reviewer2', 'preset learned from detail falls back when projection absent');
  assert.equal(list.find((row) => row.id === 'session-parent').parentSessionId, undefined);

  // Projection is authoritative for list rows and can explicitly clear a preset.
  const projectionItems = items.map((item) =>
    item.sessionId === 'session-child'
      ? { ...item, projections: { kind: 'cached', asOfSeq: 9, values: { agentPreset: 'list-preset' } } }
      : item,
  );
  const projected = await makeLineageAdapter({ items: projectionItems }).listSessions();
  assert.equal(projected.find((row) => row.id === 'session-child').agentPreset, 'list-preset');

  const clearedItems = items.map((item) =>
    item.sessionId === 'session-child'
      ? { ...item, projections: { kind: 'cached', asOfSeq: 9, values: { agentPreset: null } } }
      : item,
  );
  const cleared = await makeLineageAdapter({ items: clearedItems }).listSessions();
  assert.equal('agentPreset' in cleared.find((row) => row.id === 'session-child'), false);

  // A latest null selection clears the header preset for detail too.
  const clearedSnapshots = {
    ...snapshots,
    'session-child': {
      ...snapshots['session-child'],
      events: [{ type: 'agent-preset/selected', seq: 1, time: T(1000), data: { agentPreset: null } }],
    },
  };
  const clearedDetail = await makeLineageAdapter({ snapshots: clearedSnapshots }).getSession('session-child');
  assert.equal('agentPreset' in clearedDetail.session, false);
});

test('lineage omits hidden, internal, archived and unknown parents', async () => {
  const parentHeader = { ...HEADER, id: 'session-parent' };
  const childHeader = { ...HEADER, id: 'session-child', parentSession: 'session-parent' };
  const snapshots = {
    'session-parent': { session: parentHeader, inheritedEventCount: 0, events: [] },
    'session-child': { session: childHeader, inheritedEventCount: 0, events: [] },
  };
  const items = [
    { sessionId: 'session-parent', updatedAt: T(5000), running: false, agentAvailable: false, cwd: 'C:\\work' },
    {
      sessionId: 'session-child',
      parentSessionId: 'session-parent',
      updatedAt: T(4000),
      running: false,
      agentAvailable: false,
      cwd: 'C:\\work',
    },
  ];
  const makeAdapter = (options = {}) =>
    createDshAdapter({
      ctx: createFakeCtx({
        sessionController: fakeSessionController({ items: options.items ?? items }),
        sessionQuery: fakeSessionQuery({ snapshots: options.snapshots ?? snapshots }),
        workspaceController: options.workspaceController,
      }),
      config: testConfig({ sessions: { hideInternal: false, cacheFile: false } }),
      logger: silent,
      hiddenStore: options.hiddenStore,
    });

  const hiddenStore = createHiddenStore();
  await hiddenStore.replace(['session-parent']);
  const hiddenAdapter = makeAdapter({ hiddenStore });
  assert.equal('parentSessionId' in (await hiddenAdapter.getSession('session-child')).session, false);
  assert.equal(
    'parentSessionId' in (await hiddenAdapter.listSessions()).find((row) => row.id === 'session-child'),
    false,
  );

  const internalChild = { ...childHeader, id: 'session-internal-child', parentSession: 'bare-parent' };
  const internalSnapshots = {
    'bare-parent': { session: { ...HEADER, id: 'bare-parent' }, inheritedEventCount: 0, events: [] },
    'session-internal-child': { session: internalChild, inheritedEventCount: 0, events: [] },
  };
  const internalItems = [
    { sessionId: 'bare-parent', updatedAt: T(5000), running: false, agentAvailable: false, cwd: 'C:\\work' },
    {
      sessionId: 'session-internal-child',
      parentSessionId: 'bare-parent',
      updatedAt: T(4000),
      running: false,
      agentAvailable: false,
      cwd: 'C:\\work',
    },
  ];
  const internalAdapter = makeAdapter({ snapshots: internalSnapshots, items: internalItems });
  assert.equal('parentSessionId' in (await internalAdapter.getSession('session-internal-child')).session, false);
  assert.equal(
    'parentSessionId' in (await internalAdapter.listSessions()).find((row) => row.id === 'session-internal-child'),
    false,
  );

  const archivedAdapter = makeAdapter({ workspaceController: fakeWorkspaceController({ archived: ['session-parent'] }) });
  assert.equal('parentSessionId' in (await archivedAdapter.getSession('session-child')).session, false);
  assert.equal(
    'parentSessionId' in (await archivedAdapter.listSessions()).find((row) => row.id === 'session-child'),
    false,
  );

  const missingSnapshots = {
    'session-child': { session: { ...childHeader, parentSession: 'session-missing' }, inheritedEventCount: 0, events: [] },
  };
  const missingAdapter = makeAdapter({ snapshots: missingSnapshots });
  assert.equal('parentSessionId' in (await missingAdapter.getSession('session-child')).session, false);

  // Archived list: a parented child is internal by the existing privacy rule and
  // is absent from the archived list entirely, so its parent cannot leak there.
  const archivedHiddenChild = makeAdapter({
    workspaceController: fakeWorkspaceController({ archived: ['session-child'] }),
  });
  const archivedRows = await archivedHiddenChild.listArchivedSessions();
  assert.equal(archivedRows.sessions.some((row) => row.id === 'session-child'), false);
});

test('lineage drops self-parent links, survives access errors, and agrees on archived lists', async () => {
  const childHeader = { ...HEADER, id: 'session-child', parentSession: 'session-parent' };
  const snapshots = {
    'session-parent': { session: { ...HEADER, id: 'session-parent' }, inheritedEventCount: 0, events: [] },
    'session-child': { session: childHeader, inheritedEventCount: 0, events: [] },
  };
  const items = [
    { sessionId: 'session-parent', updatedAt: T(5000), running: false, agentAvailable: false, cwd: 'C:\\work' },
    {
      sessionId: 'session-child',
      parentSessionId: 'session-parent',
      updatedAt: T(4000),
      running: false,
      agentAvailable: false,
      cwd: 'C:\\work',
    },
  ];
  const makeAdapter = (options = {}) =>
    createDshAdapter({
      ctx: createFakeCtx({
        sessionController:
          options.sessionController ?? fakeSessionController({ items: options.items ?? items }),
        sessionQuery: options.sessionQuery ?? fakeSessionQuery({ snapshots: options.snapshots ?? snapshots }),
        workspaceController: options.workspaceController,
      }),
      config: testConfig({ sessions: { hideInternal: false, cacheFile: false } }),
      logger: silent,
      hiddenStore: options.hiddenStore,
    });

  // Self-parent is invalid lineage, defensively dropped in detail and list.
  const selfSnapshots = {
    'session-self': {
      session: { ...HEADER, id: 'session-self', parentSession: 'session-self' },
      inheritedEventCount: 0,
      events: [],
    },
  };
  const selfItems = [
    {
      sessionId: 'session-self',
      parentSessionId: 'session-self',
      updatedAt: T(4000),
      running: false,
      agentAvailable: false,
      cwd: 'C:\\work',
    },
  ];
  const selfAdapter = makeAdapter({ sessionQuery: fakeSessionQuery({ snapshots: selfSnapshots }), items: selfItems });
  assert.equal('parentSessionId' in (await selfAdapter.getSession('session-self')).session, false);
  assert.equal('parentSessionId' in (await selfAdapter.listSessions())[0], false);

  // Access errors must omit the parent, never 500 the summary.
  const failingQuery = {
    ...fakeSessionQuery({ snapshots }),
    async filterSessions() {
      throw new Error('access check denied');
    },
  };
  const failingAdapter = makeAdapter({
    sessionQuery: failingQuery,
    sessionController: fakeSessionController({
      items: [],
      list: async () => {
        throw new Error('session list denied');
      },
    }),
  });
  const failingDetail = await failingAdapter.getSession('session-child');
  assert.equal('parentSessionId' in failingDetail.session, false);

  // Unknown parent from the list's perspective (not present in the controller
  // list either) is omitted rather than fabricated.
  const unknownParentItems = items.filter((item) => item.sessionId !== 'session-parent');
  const unknownParentSnapshots = { 'session-child': snapshots['session-child'] };
  const unknownParentList = await makeAdapter({
    items: unknownParentItems,
    snapshots: unknownParentSnapshots,
  }).listSessions();
  assert.equal('parentSessionId' in unknownParentList.find((row) => row.id === 'session-child'), false);

  // Archived list coverage: a normal archived row keeps its preset; archived
  // parented rows are excluded by the internal-session privacy rule (documented
  // in README), so lineage fields are not fabricated there.
  const archivedSessionId = 'session-archived-preset';
  const archivedSnapshots = {
    [archivedSessionId]: {
      session: { ...HEADER, id: archivedSessionId, agentPreset: 'archived-preset' },
      inheritedEventCount: 0,
      events: [],
    },
  };
  const archivedItems = [
    {
      sessionId: archivedSessionId,
      updatedAt: T(4000),
      running: false,
      agentAvailable: false,
      cwd: 'C:\\work',
    },
  ];
  const archivedListAdapter = makeAdapter({
    snapshots: archivedSnapshots,
    items: archivedItems,
    workspaceController: fakeWorkspaceController({ archived: [archivedSessionId] }),
  });
  const archived = await archivedListAdapter.listArchivedSessions();
  const archivedRow = archived.sessions.find((row) => row.id === archivedSessionId);
  assert.equal(archivedRow.agentPreset, 'archived-preset');
  assert.equal('parentSessionId' in archivedRow, false);
});

test('router JSON carries lineage/preset fields through list and detail', async () => {
  const header = {
    ...HEADER,
    id: 'session-lineage',
    parentSession: 'session-lineage-parent',
    agentPreset: 'coder',
  };
  const snapshots = {
    'session-lineage-parent': { session: { ...HEADER, id: 'session-lineage-parent' }, inheritedEventCount: 0, events: [] },
    'session-lineage': { session: header, inheritedEventCount: 0, events: [] },
  };
  const items = [
    { sessionId: 'session-lineage-parent', updatedAt: T(5000), running: false, agentAvailable: false, cwd: 'C:\\work' },
    {
      sessionId: 'session-lineage',
      parentSessionId: 'session-lineage-parent',
      updatedAt: T(4000),
      running: false,
      agentAvailable: false,
      cwd: 'C:\\work',
    },
  ];
  const config = testConfig({ sessions: { hideInternal: false, cacheFile: false } });
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({ items }),
      sessionQuery: fakeSessionQuery({ snapshots }),
    }),
    config,
    logger: silent,
  });
  const pairing = makePairing(config);
  const token = issueToken(pairing).token;
  const router = createRouter({ config, pairing, adapter, logger: silent });

  const detail = await routeCall(router, { path: '/mobile/v1/sessions/session-lineage', token });
  assert.equal(detail.status, 200);
  assert.equal(detail.body.session.parentSessionId, 'session-lineage-parent');
  assert.equal(detail.body.session.agentPreset, 'coder');

  const list = await routeCall(router, { path: '/mobile/v1/sessions', token });
  assert.equal(list.status, 200);
  const row = list.body.sessions.find((session) => session.id === 'session-lineage');
  assert.equal(row.parentSessionId, 'session-lineage-parent');
  assert.equal(row.agentPreset, 'coder');
  const parentRow = list.body.sessions.find((session) => session.id === 'session-lineage-parent');
  assert.equal('parentSessionId' in parentRow, false);
});

test('getSessionSkills sanitizes, sorts, dedupes and never exposes paths', async () => {
  const sessionId = 'session-skills';
  const snapshots = {
    [sessionId]: { session: { ...HEADER, id: sessionId }, inheritedEventCount: 0, events: [] },
  };
  const calls = [];
  const catalog = {
    async list(request, signal) {
      calls.push({ request, signalType: typeof signal?.aborted });
      return {
        skills: [
          {
            name: ' zeta ',
            description: 'Zeta skill',
            path: 'C:\\secret\\SKILL.md',
            whenToUse: 'never',
            modelInvocable: true,
          },
          { name: 'alpha', description: '  Alpha   one  ' },
          { name: 'ALPHA', description: 'duplicate alpha' },
          { name: '../evil', description: 'path-ish name' },
          { name: 'slash/evil', description: 'slash name' },
          { name: 'control\u0000name', description: 'control name' },
          { name: '', description: 'blank name' },
          { name: 'blank-description', description: '   ' },
          { name: 'missing-description' },
          { name: 'long', description: 'x'.repeat(600) },
        ],
      };
    },
  };
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionQuery: fakeSessionQuery({ snapshots }),
      sessionSkillCatalog: catalog,
      agents: {
        get() {
          throw new Error('skills must never activate an agent');
        },
      },
    }),
    config: testConfig(),
    logger: silent,
  });

  const result = await adapter.getSessionSkills(sessionId);
  assert.deepEqual(result, {
    supported: true,
    skills: [
      { name: 'alpha', description: 'Alpha one' },
      { name: 'blank-description', description: '' },
      { name: 'long', description: 'x'.repeat(500) },
      { name: 'missing-description', description: '' },
      { name: 'zeta', description: 'Zeta skill' },
    ],
  });
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0].request, { sessionId });
  assert.equal(calls[0].signalType, 'boolean');
  const serialized = JSON.stringify(result);
  assert.equal(serialized.includes('SKILL.md'), false);
  assert.equal(serialized.includes('C:\\'), false);
  assert.equal(serialized.includes('whenToUse'), false);
  assert.equal(serialized.includes('modelInvocable'), false);
  assert.equal(serialized.includes('path'), false);
});

test('getSessionSkills caps at 100 sorted names and degrades safely when unsupported', async () => {
  const sessionId = 'session-skills-cap';
  const snapshots = {
    [sessionId]: { session: { ...HEADER, id: sessionId }, inheritedEventCount: 0, events: [] },
  };
  let calls = 0;
  const many = Array.from({ length: 105 }, (_, index) => ({
    name: `skill-${String(index).padStart(3, '0')}`,
    description: `skill ${index}`,
  }));
  const catalog = {
    async list() {
      calls += 1;
      return { skills: many };
    },
  };
  const makeAdapter = (options = {}) =>
    createDshAdapter({
      ctx: createFakeCtx({
        sessionQuery: fakeSessionQuery({ snapshots }),
        sessionSkillCatalog: options.catalog,
      }),
      config: testConfig(),
      logger: silent,
      hiddenStore: options.hiddenStore,
    });

  const capped = await makeAdapter({ catalog }).getSessionSkills(sessionId);
  assert.equal(capped.supported, true);
  assert.equal(capped.skills.length, 100);
  assert.equal(capped.skills[0].name, 'skill-000');
  assert.equal(capped.skills.at(-1).name, 'skill-099');
  assert.equal(calls, 1);

  assert.deepEqual(await makeAdapter().getSessionSkills(sessionId), { supported: false, skills: [] });

  const failing = await makeAdapter({
    catalog: {
      async list() {
        throw Object.assign(new Error('gateway/internal'), { code: 'gateway/internal' });
      },
    },
  }).getSessionSkills(sessionId);
  assert.deepEqual(failing, { supported: false, skills: [] });

  await assert.rejects(
    () =>
      makeAdapter({
        catalog: {
          async list() {
            throw Object.assign(new Error('session not found'), { code: 'session/not-found' });
          },
        },
      }).getSessionSkills(sessionId),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  // Hidden and internal sessions are 404 before any catalog read.
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace([sessionId]);
  await assert.rejects(
    () => makeAdapter({ catalog, hiddenStore }).getSessionSkills(sessionId),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  const internalSnapshots = {
    'bare-skills': { session: { ...HEADER, id: 'bare-skills' }, inheritedEventCount: 0, events: [] },
  };
  const internalAdapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionQuery: fakeSessionQuery({ snapshots: internalSnapshots }),
      sessionSkillCatalog: catalog,
    }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(
    () => internalAdapter.getSessionSkills('bare-skills'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
});

test('session summaries expose aggregate internal direct-child delegation counts', async () => {
  const header = (id) => ({ ...HEADER, id, cwd: 'C:\\work' });
  const parents = [
    { sessionId: 'session-parent-a', updatedAt: T(6000), running: false, agentAvailable: false, cwd: 'C:\\work' },
    { sessionId: 'session-parent-b', updatedAt: T(5000), running: false, agentAvailable: false, cwd: 'C:\\work' },
    { sessionId: 'session-parent-c', updatedAt: T(4000), running: false, agentAvailable: false, cwd: 'C:\\work' },
  ];
  const internalChildren = [
    {
      sessionId: 'child-bare-a',
      parentSessionId: 'session-parent-a',
      origin: 'subagent',
      running: true,
      updatedAt: T(3000),
      agentAvailable: true,
      cwd: 'C:\\work',
    },
    {
      sessionId: 'session-child-b',
      parentSessionId: 'session-parent-a',
      origin: 'subagent',
      running: false,
      updatedAt: T(2000),
      agentAvailable: false,
      cwd: 'C:\\work',
    },
    {
      sessionId: 'child-bare-b',
      parentSessionId: 'session-parent-b',
      origin: 'subagent',
      running: false,
      updatedAt: T(1500),
      agentAvailable: false,
      cwd: 'C:\\work',
    },
    {
      sessionId: 'child-grand',
      parentSessionId: 'child-bare-a',
      origin: 'subagent',
      running: true,
      updatedAt: T(1000),
      agentAvailable: true,
      cwd: 'C:\\work',
    },
  ];
  const items = [...parents, ...internalChildren];
  const snapshots = Object.fromEntries(
    [...parents, ...internalChildren].map((item) => [
      item.sessionId,
      {
        session:
          item.parentSessionId === undefined
            ? header(item.sessionId)
            : { ...header(item.sessionId), parentSession: item.parentSessionId, origin: 'subagent' },
        inheritedEventCount: 0,
        events: [],
      },
    ]),
  );
  const makeDelegationAdapter = (options = {}) =>
    createDshAdapter({
      ctx: createFakeCtx({
        sessionController: fakeSessionController({ items: options.items ?? items }),
        sessionQuery: fakeSessionQuery({ snapshots }),
        workspaceController: options.workspaceController ?? fakeWorkspaceController(),
      }),
      config: testConfig(),
      logger: silent,
      hiddenStore: options.hiddenStore,
    });

  const adapter = makeDelegationAdapter();
  const list = await adapter.listSessions();
  assert.deepEqual(list.map((row) => row.id), ['session-parent-a', 'session-parent-b', 'session-parent-c']);
  assert.deepEqual(list.find((row) => row.id === 'session-parent-a').delegation, { total: 2, working: 1 });
  assert.deepEqual(list.find((row) => row.id === 'session-parent-b').delegation, { total: 1, working: 0 });
  assert.equal('delegation' in list.find((row) => row.id === 'session-parent-c'), false, 'no children → omitted');
  // Direct children only: child-grand belongs to child-bare-a, so parent-a stays at 2.
  assert.equal(list.find((row) => row.id === 'session-parent-a').delegation.total, 2);
  const listJson = JSON.stringify(list);
  for (const child of internalChildren) {
    assert.equal(listJson.includes(child.sessionId), false, 'internal ids never leak');
  }

  const detailA = await adapter.getSession('session-parent-a');
  assert.deepEqual(detailA.session.delegation, { total: 2, working: 1 });
  assert.equal(detailA.session.delegation.working <= detailA.session.delegation.total, true);
  const detailC = await adapter.getSession('session-parent-c');
  assert.equal('delegation' in detailC.session, false);

  // Archived children are excluded from the aggregate: they must not inflate a
  // live parent's agent count.
  const archivedChildAdapter = makeDelegationAdapter({
    workspaceController: fakeWorkspaceController({ archived: ['child-bare-a'] }),
  });
  const archivedChildList = await archivedChildAdapter.listSessions();
  assert.deepEqual(
    archivedChildList.find((row) => row.id === 'session-parent-a').delegation,
    { total: 1, working: 0 },
  );
  assert.deepEqual(
    (await archivedChildAdapter.getSession('session-parent-a')).session.delegation,
    { total: 1, working: 0 },
  );

  // Hidden parent: counts omitted in detail and row excluded from the list.
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace(['session-parent-a']);
  const hiddenAdapter = makeDelegationAdapter({ hiddenStore });
  assert.equal('delegation' in (await hiddenAdapter.getSession('session-parent-a')).session, false);
  assert.equal((await hiddenAdapter.listSessions()).some((row) => row.id === 'session-parent-a'), false);

  // Internal parent: its own detail row never exposes delegation.
  assert.equal('delegation' in (await adapter.getSession('child-bare-a')).session, false);

  // Archived parent keeps the same aggregate counts when the raw rows still see
  // its children; archived semantics are documented as best-effort.
  const archivedAdapter = makeDelegationAdapter({
    workspaceController: fakeWorkspaceController({ archived: ['session-parent-a'] }),
  });
  const archived = await archivedAdapter.listArchivedSessions();
  assert.deepEqual(archived.sessions.find((row) => row.id === 'session-parent-a').delegation, { total: 2, working: 1 });

  // Router end-to-end JSON.
  const config = testConfig();
  const routerAdapter = makeDelegationAdapter();
  const pairing = makePairing(config);
  const token = issueToken(pairing).token;
  const router = createRouter({ config, pairing, adapter: routerAdapter, logger: silent });
  const listResponse = await routeCall(router, { path: '/mobile/v1/sessions', token });
  assert.equal(listResponse.status, 200);
  const listBody = JSON.stringify(listResponse.body);
  assert.equal(listBody.includes('child-bare'), false);
  assert.deepEqual(
    listResponse.body.sessions.find((row) => row.id === 'session-parent-a').delegation,
    { total: 2, working: 1 },
  );
  const detailResponse = await routeCall(router, { path: '/mobile/v1/sessions/session-parent-a', token });
  assert.deepEqual(detailResponse.body.session.delegation, { total: 2, working: 1 });
  const detailJson = JSON.stringify(detailResponse.body);
  for (const child of internalChildren) {
    assert.equal(detailJson.includes(child.sessionId), false, 'detail never leaks internal child ids');
  }
});

test('session detail messages carry additive event seq and preserve pagination order', async () => {
  const sessionId = 'session-detail-seq';
  const snapshots = {
    [sessionId]: {
      session: { ...HEADER, id: sessionId },
      inheritedEventCount: 0,
      events: [
        { type: 'user/message', seq: 1, time: T(1000), data: { id: 'm_seq_u', content: [{ type: 'text', text: 'one' }], source: { kind: 'user' } } },
        { type: 'assistant/message', seq: 2, time: T(2000), data: { message: { id: 'm_seq_a', content: [{ type: 'text', text: 'two' }] } } },
        { type: 'tool/call', seq: 3, time: T(3000), data: { callId: 'c_seq', name: 'Bash', arguments: '{}' } },
        {
          type: 'tool/result',
          seq: 4,
          time: T(4000),
          data: { message: { id: 'm_seq_tool', toolCallId: 'c_seq', content: [{ type: 'text', text: 'four' }] } },
        },
        {
          type: 'user/message',
          time: T(5000),
          data: { id: 'm_seq_noseq', content: [{ type: 'text', text: 'five' }], source: { kind: 'user' } },
        },
      ],
    },
  };
  const adapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: testConfig(),
    logger: silent,
  });

  const detail = await adapter.getSession(sessionId);
  assert.deepEqual(detail.messages.map((message) => message.id), [
    'm_seq_u',
    'm_seq_a',
    'm_c_seq',
    'm_seq_noseq',
  ]);
  assert.deepEqual(detail.messages.map((message) => message.seq), [1, 2, 4, undefined]);
  assert.equal('seq' in detail.messages[3], false, 'unknown event seq is omitted');

  const page = await adapter.getSession(sessionId, { limit: 2 });
  assert.deepEqual(
    page.messages.map((message) => message.seq),
    detail.messages.slice(-2).map((message) => message.seq),
    'pagination preserves seq and message order',
  );
});

test('getSessionTodos reads the latest todo/write without activating a session', async () => {
  const sessionId = 'session-todos';
  const snapshots = {
    [sessionId]: {
      session: { ...HEADER, id: sessionId },
      inheritedEventCount: 0,
      events: [
        { type: 'todo/write', seq: 1, time: T(1000), data: { todos: [{ content: 'stale', status: 'pending' }] } },
        { type: 'assistant/message', seq: 2, time: T(2000), data: { message: { id: 'm_a', content: [{ type: 'text', text: 'working' }] } } },
        {
          type: 'todo/write',
          seq: 3,
          time: T(3000),
          data: {
            todos: [
              { content: 'pending item', status: 'pending' },
              { content: 'active item', status: 'in_progress' },
              { content: 'done item', status: 'completed' },
            ],
          },
        },
      ],
    },
    'bare-todos': { session: { ...HEADER, id: 'bare-todos' }, inheritedEventCount: 0, events: [] },
    'session-todos-empty': { session: { ...HEADER, id: 'session-todos-empty' }, inheritedEventCount: 0, events: [] },
  };
  let agentLookups = 0;
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: {
        get() {
          agentLookups += 1;
          throw new Error('todos must not activate an agent');
        },
      },
    }),
    config: testConfig(),
    logger: silent,
  });

  const result = await adapter.getSessionTodos(sessionId);
  assert.deepEqual(result, {
    supported: true,
    updatedAt: '2026-10-30T09:40:03Z',
    counts: { pending: 1, inProgress: 1, completed: 1 },
    todos: [
      { content: 'pending item', status: 'pending' },
      { content: 'active item', status: 'in_progress' },
      { content: 'done item', status: 'completed' },
    ],
  });
  assert.equal(agentLookups, 0, 'snapshot-only read');

  assert.deepEqual(await adapter.getSessionTodos('session-todos-empty'), {
    supported: false,
    counts: { pending: 0, inProgress: 0, completed: 0 },
    todos: [],
  });

  const hiddenStore = createHiddenStore();
  await hiddenStore.replace([sessionId]);
  const hiddenAdapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: testConfig(),
    logger: silent,
    hiddenStore,
  });
  await assert.rejects(
    () => hiddenAdapter.getSessionTodos(sessionId),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.getSessionTodos('bare-todos'),
    (error) => error instanceof ApiError && error.code === 'not_found',
    'internal sessions are 404',
  );
  await assert.rejects(
    () => adapter.getSessionTodos('session-todos-missing'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  // Router JSON end-to-end + auth.
  const config = testConfig();
  const routerAdapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config,
    logger: silent,
  });
  const pairing = makePairing(config);
  const token = issueToken(pairing).token;
  const router = createRouter({ config, pairing, adapter: routerAdapter, logger: silent });
  const routed = await routeCall(router, { path: '/mobile/v1/sessions/session-todos/todos', token });
  assert.equal(routed.status, 200);
  assert.deepEqual(routed.body.todos, result.todos);
  assert.deepEqual(routed.body.counts, result.counts);
  assert.equal(
    (await routeCall(router, { path: '/mobile/v1/sessions/session-todos/todos' })).status,
    401,
  );
});

test('scheduled prompts appear in session detail and live SSE with a source marker', async () => {
  const sessionId = 'session-scheduled';
  const scheduleEvent = {
    type: 'user/message',
    seq: 1,
    time: T(1000),
    data: {
      id: 'm_scheduled',
      role: 'user',
      content: [
        { type: 'text', text: 'Scheduled prompt text' },
        { type: 'image', attachment: { attachmentId: 'att_sched', mediaType: 'image/png', bytes: 4, width: 2, height: 2, name: 'shot.png' } },
      ],
      source: { kind: 'schedule' },
    },
  };
  const snapshots = {
    [sessionId]: {
      session: { ...HEADER, id: sessionId },
      inheritedEventCount: 0,
      events: [scheduleEvent],
    },
  };
  const detailAdapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: testConfig(),
    logger: silent,
  });
  const detail = await detailAdapter.getSession(sessionId);
  assert.equal(detail.messages.length, 1);
  assert.equal(detail.messages[0].role, 'user');
  assert.equal(detail.messages[0].text, 'Scheduled prompt text');
  assert.equal(detail.messages[0].source, 'schedule');
  assert.equal(detail.messages[0].attachments[0].id, 'att_sched');

  const bus = new EventBus({ logger: silent, bufferSize: 20 });
  const ctx = createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) });
  const liveAdapter = makeAdapter(ctx, bus, { config: testConfig() });
  const dispose = liveAdapter.attach();
  ctx.emit('session/event', { id: sessionId, header: { ...HEADER, id: sessionId } }, scheduleEvent);
  const live = bus.buffer.at(-1);
  assert.equal(live.type, 'message.added');
  assert.equal(live.data.message.source, 'schedule');
  assert.equal(live.data.message.attachments[0].id, 'att_sched');
  dispose();
});

test('semantic search excludes scheduled framing and never forces a snapshot read', async () => {
  const sessionId = 'session-sched-search';
  const sessionQuery = fakeSessionQuery({
    snapshots: {},
    filterEvents: async (id) => [
      {
        sessionId: id,
        seq: 1,
        type: 'user/message',
        time: T(1000),
        surface: 'current',
        text: '[SCHEDULE REMINDER]\nThis is a scheduled message from the user\nschedule_id_json: {"id":"sched_1"}\nneedle reminder text',
      },
      {
        sessionId: id,
        seq: 2,
        type: 'user/message',
        time: T(2000),
        surface: 'current',
        text: 'human needle prompt',
      },
      {
        sessionId: id,
        seq: 3,
        type: 'assistant/message',
        time: T(3000),
        surface: 'current',
        text: 'assistant needle reply',
      },
    ],
  });
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({
        items: [{ sessionId, updatedAt: T(5000), running: false, agentAvailable: false, cwd: 'C:\\work' }],
      }),
      sessionQuery,
    }),
    config: testConfig(),
    logger: silent,
  });

  const started = Date.now();
  const result = await adapter.searchSessions('needle', 10);
  const elapsed = Date.now() - started;
  assert.deepEqual(result.hits.map((hit) => hit.messageId), ['m_ev_3', 'm_ev_2']);
  assert.equal(
    result.hits.some((hit) => String(hit.snippet).includes('scheduled')),
    false,
    'scheduled prompt text never becomes a search hit',
  );
  assert.equal(
    sessionQuery.calls.some((call) => call.method === 'readSession' && call.signal !== undefined),
    false,
    'semantic user-text matching stays on the fast path (no snapshot verification)',
  );
  assert.equal(elapsed < 1000, true, `scheduled-framing filter must not slow the semantic path (${elapsed}ms)`);
});

test('getSessionTeam folds the latest team board without activating a session', async () => {
  const sessionId = 'session-team';
  const taskEvent = (id, revision, subject, status, extra = {}) => ({
    type: 'team/task',
    seq: revision,
    time: T(revision * 1000),
    data: {
      task: {
        id,
        revision,
        subject,
        description: 'SECRET task description',
        status,
        ownerId: 'agent-secret-owner',
        writeScopes: ['host-plugin/lib/secret.js'],
        blockedBy: extra.blockedBy ?? [],
      },
    },
  });
  const memberEvent = (id, name, phase, revision = 1) => ({
    type: 'team/member',
    seq: revision,
    time: T(revision * 1000),
    data: {
      member: {
        id,
        name,
        description: 'SECRET member description',
        provider: 'secret-provider',
        context: 'secret-context',
        phase,
      },
    },
  });
  const snapshots = {
    [sessionId]: {
      session: { ...HEADER, id: sessionId },
      inheritedEventCount: 0,
      events: [
        taskEvent('task-1', 1, 'old', 'pending'),
        taskEvent('task-1', 2, 'Write the endpoint', 'in_progress'),
        taskEvent('task-2', 1, 'Blocked task', 'blocked', { blockedBy: ['task-1'] }),
        taskEvent('task-3', 1, 'Finished task', 'completed'),
        memberEvent('member-1', 'Worker One', 'working'),
        memberEvent('member-1', 'Worker One renamed', 'idle', 2),
      ],
    },
    'bare-team': { session: { ...HEADER, id: 'bare-team' }, inheritedEventCount: 0, events: [] },
    'session-team-empty': { session: { ...HEADER, id: 'session-team-empty' }, inheritedEventCount: 0, events: [] },
  };
  let agentLookups = 0;
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: {
        get() {
          agentLookups += 1;
          throw new Error('team board must not activate an agent');
        },
      },
    }),
    config: testConfig(),
    logger: silent,
  });

  const board = await adapter.getSessionTeam(sessionId);
  assert.deepEqual(board, {
    supported: true,
    counts: { pending: 0, inProgress: 1, blocked: 1, completed: 1 },
    members: [{ name: 'Worker One renamed', phase: 'idle' }],
    tasks: [
      { id: 'task-1', subject: 'Write the endpoint', status: 'in_progress' },
      { id: 'task-2', subject: 'Blocked task', status: 'blocked', blockedBy: ['task-1'] },
    ],
  });
  assert.equal(agentLookups, 0, 'snapshot-only read');
  const json = JSON.stringify(board);
  for (const secret of ['SECRET', 'agent-secret-owner', 'host-plugin/lib/secret.js', 'secret-provider', 'secret-context', 'member-1']) {
    assert.equal(json.includes(secret), false, `${secret} must not leak`);
  }

  assert.deepEqual(await adapter.getSessionTeam('session-team-empty'), {
    supported: false,
    counts: { pending: 0, inProgress: 0, blocked: 0, completed: 0 },
    members: [],
    tasks: [],
  });

  const hiddenStore = createHiddenStore();
  await hiddenStore.replace([sessionId]);
  const hiddenAdapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: testConfig(),
    logger: silent,
    hiddenStore,
  });
  await assert.rejects(
    () => hiddenAdapter.getSessionTeam(sessionId),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.getSessionTeam('bare-team'),
    (error) => error instanceof ApiError && error.code === 'not_found',
    'internal sessions are 404',
  );
  await assert.rejects(
    () => adapter.getSessionTeam('session-team-missing'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  const config = testConfig();
  const routerAdapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config,
    logger: silent,
  });
  const pairing = makePairing(config);
  const token = issueToken(pairing).token;
  const router = createRouter({ config, pairing, adapter: routerAdapter, logger: silent });
  const routed = await routeCall(router, { path: '/mobile/v1/sessions/session-team/team', token });
  assert.equal(routed.status, 200);
  assert.deepEqual(routed.body.tasks, board.tasks);
  assert.deepEqual(routed.body.members, board.members);
  assert.equal((await routeCall(router, { path: '/mobile/v1/sessions/session-team/team' })).status, 401);
});

test('getSessionTrajectory pages compact events without activating a session', async () => {
  const sessionId = 'session-traj';
  const snapshots = {
    [sessionId]: {
      session: { ...HEADER, id: sessionId },
      inheritedEventCount: 0,
      events: [
        { type: 'turn/start', seq: 1, time: T(1000), data: { turn: 1 } },
        { type: 'user/message', seq: 2, time: T(2000), data: { id: 'm_u', content: [{ type: 'text', text: 'visible needle' }], source: { kind: 'user' } } },
        {
          type: 'user/message',
          seq: 3,
          time: T(3000),
          data: { id: 'm_ctx', content: [{ type: 'text', text: 'SECRET_INTERNAL body' }], source: { kind: 'runtime-context' } },
        },
        { type: 'assistant/message', seq: 4, time: T(4000), data: { message: { id: 'm_a', content: [{ type: 'text', text: 'assistant needle reply' }] } } },
        { type: 'tool/call', seq: 5, time: T(5000), data: { callId: 'c1', name: 'Pwsh', arguments: '{"command":"echo needle"}' } },
        { type: 'tool/result', seq: 6, time: T(6000), data: { message: { toolCallId: 'c1', content: [{ type: 'text', text: 'needle output' }] } } },
      ],
    },
    'bare-traj': { session: { ...HEADER, id: 'bare-traj' }, inheritedEventCount: 0, events: [] },
    'session-traj-empty': { session: { ...HEADER, id: 'session-traj-empty' }, inheritedEventCount: 0, events: [] },
    'session-traj-secret': {
      session: { ...HEADER, id: 'session-traj-secret' },
      inheritedEventCount: 0,
      events: [
        {
          type: 'tool/call',
          seq: 1,
          time: T(1000),
          data: {
            name: 'Pwsh',
            arguments: JSON.stringify({
              command:
                'echo CLOUDFLARE_API_TOKEN=cfat_FAKE1234567890abcdef && git status C:\\dev\\repo\\file.ts',
            }),
          },
        },
      ],
    },
  };
  let agentLookups = 0;
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: {
        get() {
          agentLookups += 1;
          throw new Error('trajectory must not activate an agent');
        },
      },
    }),
    config: testConfig(),
    logger: silent,
  });

  const first = await adapter.getSessionTrajectory(sessionId, { limit: 3 });
  assert.equal(first.supported, true);
  assert.deepEqual(first.events.map((event) => event.seq), [6, 5, 4]);
  assert.equal(first.hasMore, true);
  assert.equal(first.nextBeforeSeq, 4);
  assert.equal(first.events[2].summary, 'assistant needle reply');
  assert.equal(agentLookups, 0, 'snapshot-only read');
  assert.equal(JSON.stringify(first).includes('SECRET_INTERNAL'), false);

  const second = await adapter.getSessionTrajectory(sessionId, { limit: 3, beforeSeq: 4 });
  assert.deepEqual(second.events.map((event) => event.seq), [3, 2, 1]);
  assert.equal(second.hasMore, false);
  assert.equal(second.nextBeforeSeq, null);
  assert.equal(second.events[0].summary, '[context] runtime-context');
  assert.equal(second.events[1].summary, 'visible needle');
  assert.equal(second.events[2].summary, 'turn 1 started');

  assert.deepEqual(await adapter.getSessionTrajectory('session-traj-empty'), {
    supported: false,
    events: [],
    nextBeforeSeq: null,
    hasMore: false,
  });
  // Trajectory endpoint integration: shared generalized credential redactor and
  // absolute-path pass run on tool-call summaries.
  const secretTrajectory = await adapter.getSessionTrajectory('session-traj-secret');
  const secretJson = JSON.stringify(secretTrajectory);
  assert.equal(secretJson.includes('cfat_FAKE1234567890abcdef'), false);
  assert.equal(secretJson.includes('cfat_'), false);
  assert.equal(secretJson.includes('C:\\dev'), false);
  assert.equal(secretTrajectory.events[0].summary.includes('git status'), true);
  assert.match(secretTrajectory.events[0].summary, /<redacted>/);
  assert.match(secretTrajectory.events[0].summary, /<path>/);
  // Q3 distinction: a readable session with no events returns supported:false;
  // only an unavailable history service maps to 501.
  const noHistoryAdapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({
        items: [{ sessionId, updatedAt: T(9000), running: false, agentAvailable: false, cwd: 'C:\\work' }],
      }),
    }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(
    () => noHistoryAdapter.getSessionTrajectory(sessionId),
    (error) => error instanceof ApiError && error.code === 'unsupported' && error.status === 501,
  );
  await assert.rejects(
    () => adapter.getSessionTrajectory(sessionId, { limit: 201 }),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );
  await assert.rejects(
    () => adapter.getSessionTrajectory(sessionId, { beforeSeq: -1 }),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );

  const hiddenStore = createHiddenStore();
  await hiddenStore.replace([sessionId]);
  const hiddenAdapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: testConfig(),
    logger: silent,
    hiddenStore,
  });
  await assert.rejects(
    () => hiddenAdapter.getSessionTrajectory(sessionId),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.getSessionTrajectory('bare-traj'),
    (error) => error instanceof ApiError && error.code === 'not_found',
    'internal sessions are 404',
  );
  await assert.rejects(
    () => adapter.getSessionTrajectory('session-traj-missing'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  const config = testConfig();
  const routerAdapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config,
    logger: silent,
  });
  const pairing = makePairing(config);
  const token = issueToken(pairing).token;
  const router = createRouter({ config, pairing, adapter: routerAdapter, logger: silent });
  const routed = await routeCall(router, {
    path: '/mobile/v1/sessions/session-traj/trajectory',
    token,
    query: 'limit=2',
  });
  assert.equal(routed.status, 200);
  assert.deepEqual(routed.body.events.map((event) => event.seq), [6, 5]);
  assert.deepEqual(routed.body.nextBeforeSeq, 5);
  assert.equal(routed.body.hasMore, true);
  assert.equal(
    (await routeCall(router, { path: '/mobile/v1/sessions/session-traj/trajectory' })).status,
    401,
  );
  const invalid = await routeCall(router, {
    path: '/mobile/v1/sessions/session-traj/trajectory',
    token,
    query: 'limit=999',
  });
  assert.equal(invalid.status, 400);
  assert.equal(invalid.body.error.code, 'invalid_request');
  const missing = await routeCall(router, { path: '/mobile/v1/sessions/session-traj-missing/trajectory', token });
  assert.equal(missing.status, 404);
});

test('workspace files endpoints list/preview/raw safely inside the session cwd', async (t) => {
  const root = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'dsh-files-'));
  const outside = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'dsh-files-outside-'));
  t.after(async () => {
    await fs.promises.rm(root, { recursive: true, force: true });
    await fs.promises.rm(outside, { recursive: true, force: true });
  });
  const sessionId = 'session-files';
  const write = async (relative, content) => {
    const full = path.join(root, ...relative.split('/'));
    await fs.promises.mkdir(path.dirname(full), { recursive: true });
    await fs.promises.writeFile(full, content);
  };
  await write('alpha.txt', 'hello files');
  await write('beta.json', '{"b":1}');
  await write('binary.bin', Buffer.from([0, 1, 2]));
  await write('truncated.txt', 'x'.repeat(250_000));
  await write('huge.txt', Buffer.alloc(1024 * 1024 + 1, 0x41));
  await write('script.js', 'export const x = 1;');
  await write('style.css', 'body { color: red; }');
  await write('icon.svg', '<svg xmlns="http://www.w3.org/2000/svg"></svg>');
  await write('page.html', '<html></html>');
  await write('doc.xml', '<root />');
  await write('image.png', Buffer.from([0x89, 0x50, 0x4e, 0x47]));
  await write('swap.txt', 'safe until swapped');
  await write('src/App.ts', 'export const app = 1;');
  await write('src/lib/util.js', 'export const util = 1;');
  await write('many/f000.txt', '0');
  for (let index = 1; index < 505; index += 1) {
    await write(`many/f${String(index).padStart(3, '0')}.txt`, String(index));
  }
  await fs.promises.mkdir(path.join(root, 'empty-dir'));
  // A sparse >25 MB file avoids allocating a giant buffer in the test.
  const hugeRaw = path.join(root, 'huge.bin');
  const handle = await fs.promises.open(hugeRaw, 'w');
  await handle.truncate(25 * 1024 * 1024 + 1);
  await handle.close();
  await fs.promises.writeFile(path.join(outside, 'secret.txt'), 'outside secret');
  let symlinkReady = true;
  try {
    await fs.promises.symlink(path.join(outside, 'secret.txt'), path.join(root, 'escape.txt'), 'file');
    await fs.promises.symlink(path.join(root, 'alpha.txt'), path.join(root, 'inside-link.txt'), 'file');
  } catch {
    symlinkReady = false;
  }

  const snapshots = {
    [sessionId]: { session: { ...HEADER, id: sessionId, cwd: root }, inheritedEventCount: 0, events: [] },
  };
  let agentLookups = 0;
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({
        items: [{ sessionId, updatedAt: T(1000), running: false, agentAvailable: false, cwd: root }],
      }),
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: {
        get() {
          agentLookups += 1;
          throw new Error('files must not activate an agent');
        },
      },
    }),
    config: testConfig(),
    logger: silent,
  });

  // Root listing: dirs first, then case-insensitive names; no host path leaks;
  // symlinks are never listed.
  const listing = await adapter.listSessionFiles(sessionId, '');
  assert.equal(listing.supported, true);
  assert.equal(listing.path, '');
  assert.equal(listing.parent, '');
  assert.deepEqual(listing.entries.slice(0, 3).map((entry) => [entry.name, entry.kind]), [
    ['empty-dir', 'dir'],
    ['many', 'dir'],
    ['src', 'dir'],
  ]);
  const alpha = listing.entries.find((entry) => entry.name === 'alpha.txt');
  assert.deepEqual(
    { kind: alpha.kind, path: alpha.path, bytes: alpha.bytes, mimeType: alpha.mimeType, hasModifiedAt: typeof alpha.modifiedAt === 'string' },
    { kind: 'file', path: 'alpha.txt', bytes: 11, mimeType: 'text/plain', hasModifiedAt: true },
  );
  assert.equal(listing.entries.some((entry) => entry.name === 'escape.txt'), false);
  assert.equal(listing.entries.some((entry) => entry.name === 'inside-link.txt'), false);
  assert.equal(listing.truncated, false);
  assert.equal(listing.entryCount, listing.entries.length);
  assert.equal(JSON.stringify(listing).includes(root), false, 'no host paths in listing JSON');

  const nested = await adapter.listSessionFiles(sessionId, 'src');
  assert.equal(nested.path, 'src');
  assert.equal(nested.parent, '');
  assert.equal(nested.truncated, false);
  assert.equal(nested.entryCount, 2);
  assert.deepEqual(nested.entries.map((entry) => [entry.name, entry.kind, entry.path]), [
    ['lib', 'dir', 'src/lib'],
    ['App.ts', 'file', 'src/App.ts'],
  ]);
  const nestedLib = await adapter.listSessionFiles(sessionId, 'src/lib');
  assert.equal(nestedLib.parent, 'src');

  const capped = await adapter.listSessionFiles(sessionId, 'many');
  assert.equal(capped.entries.length, 500);
  assert.equal(capped.entries[0].name, 'f000.txt');
  assert.equal(capped.truncated, true);
  assert.equal(capped.entryCount, 505);

  // Text preview: normal files, truncation, binary/oversize 415s, no giant body.
  const preview = await adapter.readSessionFileContent(sessionId, 'alpha.txt');
  assert.deepEqual(preview, {
    name: 'alpha.txt',
    path: 'alpha.txt',
    bytes: 11,
    mimeType: 'text/plain',
    text: 'hello files',
    truncated: false,
  });
  const jsonPreview = await adapter.readSessionFileContent(sessionId, 'beta.json');
  assert.equal(jsonPreview.mimeType, 'application/json');
  assert.equal(jsonPreview.text, '{"b":1}');
  const truncated = await adapter.readSessionFileContent(sessionId, 'truncated.txt');
  assert.equal(truncated.bytes, 250_000);
  assert.equal(truncated.text.length, 200_000);
  assert.equal(truncated.truncated, true);
  for (const path of ['binary.bin', 'huge.txt']) {
    await assert.rejects(
      () => adapter.readSessionFileContent(sessionId, path),
      (error) => error instanceof ApiError && error.code === 'unsupported' && error.status === 415,
    );
  }
  await assert.rejects(
    () => adapter.readSessionFileContent(sessionId, 'missing.txt'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.readSessionFileContent(sessionId, 'src'),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );

  // Raw bytes + headers.
  const raw = await adapter.readSessionFileRaw(sessionId, 'alpha.txt');
  assert.equal(raw.status, 200);
  assert.equal(raw.headers['Content-Type'], 'text/plain');
  assert.equal(raw.headers['Content-Length'], '11');
  assert.equal(raw.headers['Content-Disposition'], 'inline; filename="alpha.txt"');
  assert.equal(raw.headers['X-Content-Type-Options'], 'nosniff');
  assert.match(raw.headers['Cache-Control'], /^private/);
  assert.equal(Buffer.from(raw.raw).toString('utf8'), 'hello files');
  const rawBinary = await adapter.readSessionFileRaw(sessionId, 'binary.bin');
  assert.equal(rawBinary.headers['Content-Type'], 'application/octet-stream');
  assert.equal(rawBinary.headers['Content-Disposition'], 'inline; filename="binary.bin"');
  assert.equal(Buffer.from(rawBinary.raw).byteLength, 3);
  // Active content types are downloads, not inline documents.
  for (const [name, disposition] of [
    ['script.js', 'attachment; filename="script.js"'],
    ['style.css', 'attachment; filename="style.css"'],
    ['icon.svg', 'attachment; filename="icon.svg"'],
    ['page.html', 'attachment; filename="page.html"'],
    ['doc.xml', 'attachment; filename="doc.xml"'],
    ['image.png', 'inline; filename="image.png"'],
  ]) {
    const served = await adapter.readSessionFileRaw(sessionId, name);
    assert.equal(served.headers['Content-Disposition'], disposition, name);
    assert.equal(served.headers['X-Content-Type-Options'], 'nosniff');
  }
  await assert.rejects(
    () => adapter.readSessionFileRaw(sessionId, 'huge.bin'),
    (error) => error instanceof ApiError && error.status === 413,
  );
  await assert.rejects(
    () => adapter.readSessionFileRaw(sessionId, 'src'),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );

  // Path traversal / absolute / drive / UNC / ADS / trailing aliases.
  for (const bad of [
    '../outside/secret.txt',
    'src/../../outside/secret.txt',
    '/etc/passwd',
    'C:\\Windows\\win.ini',
    '\\\\server\\share\\file.txt',
    'alpha.txt:stream',
    'alpha.txt.',
    'alpha.txt ',
    'src//App.ts',
    './alpha.txt',
    // Windows reserved device basenames are extension-tolerant aliases.
    'CON',
    'con.txt',
    'nul.log',
    'AUX.tar.gz',
    'COM1.txt',
    'LPT9.log',
    'src/PRN.txt',
  ]) {
    await assert.rejects(
      () => adapter.listSessionFiles(sessionId, bad),
      (error) => error instanceof ApiError && error.code === 'invalid_request' && error.status === 400,
      `rejects ${bad}`,
    );
  }

  // Symlink escapes are rejected; symlinks are never listed (both checked only
  // when the test process could create them, e.g. Windows may require admin).
  if (symlinkReady) {
    await assert.rejects(
      () => adapter.readSessionFileContent(sessionId, 'escape.txt'),
      (error) => error instanceof ApiError && error.code === 'not_found',
    );
    await assert.rejects(
      () => adapter.readSessionFileRaw(sessionId, 'escape.txt'),
      (error) => error instanceof ApiError && error.code === 'not_found',
    );
    assert.equal((await adapter.readSessionFileContent(sessionId, 'inside-link.txt')).text, 'hello files');

    // F1: swap the validated file for an escaping symlink after the first
    // realpath; the re-realpath before the descriptor open must catch it.
    const swapPath = path.join(root, 'swap.txt');
    const runSwap = async (call) => {
      const originalRealpath = fs.promises.realpath;
      let targetCalls = 0;
      fs.promises.realpath = async function swapRealpath(candidate, ...args) {
        if (path.resolve(String(candidate)) === path.resolve(swapPath)) {
          targetCalls += 1;
          if (targetCalls === 2) {
            await fs.promises.rm(swapPath, { force: true });
            await fs.promises.symlink(path.join(outside, 'secret.txt'), swapPath, 'file');
          }
        }
        return originalRealpath.call(this, candidate, ...args);
      };
      try {
        await assert.rejects(call, (error) => error instanceof ApiError && error.code === 'not_found');
      } finally {
        fs.promises.realpath = originalRealpath;
        await fs.promises.rm(swapPath, { force: true });
        await fs.promises.writeFile(swapPath, 'safe until swapped');
      }
      assert.equal(targetCalls >= 2, true, 'a second realpath must re-validate before open');
    };
    await runSwap(() => adapter.readSessionFileContent(sessionId, 'swap.txt'));
    await runSwap(() => adapter.readSessionFileRaw(sessionId, 'swap.txt'));
  }

  // Hidden/internal/unknown sessions are 404 before any file work.
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace([sessionId]);
  const hiddenAdapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: testConfig(),
    logger: silent,
    hiddenStore,
  });
  await assert.rejects(
    () => hiddenAdapter.listSessionFiles(sessionId, ''),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  const internalSnapshots = {
    'bare-files': { session: { ...HEADER, id: 'bare-files', cwd: root }, inheritedEventCount: 0, events: [] },
  };
  const internalAdapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots: internalSnapshots }) }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(
    () => internalAdapter.listSessionFiles('bare-files', ''),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.listSessionFiles('session-files-missing', ''),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  for (const [id, cwd] of [
    ['session-no-cwd', ''],
    ['session-missing-root', path.join(root, 'does-not-exist')],
  ]) {
    const noRootAdapter = createDshAdapter({
      ctx: createFakeCtx({
        sessionController: fakeSessionController({
          items: [{ sessionId: id, updatedAt: T(1000), running: false, agentAvailable: false, cwd }],
        }),
        sessionQuery: fakeSessionQuery({
          snapshots: { [id]: { session: { ...HEADER, id, ...(cwd === '' ? { cwd: '' } : { cwd }) }, inheritedEventCount: 0, events: [] } },
        }),
      }),
      config: testConfig(),
      logger: silent,
    });
    await assert.rejects(
      () => noRootAdapter.listSessionFiles(id, ''),
      (error) => error instanceof ApiError && error.code === 'not_found',
      `missing cwd/root is 404 (${id})`,
    );
  }
  assert.equal(agentLookups <= 1, true, 'file reads are snapshot/disk only (no activation)');

  // Router end-to-end: auth, JSON, 415 and the raw-response descriptor.
  const config = testConfig();
  const routerAdapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({
        items: [{ sessionId, updatedAt: T(1000), running: false, agentAvailable: false, cwd: root }],
      }),
      sessionQuery: fakeSessionQuery({ snapshots }),
    }),
    config,
    logger: silent,
  });
  const pairing = makePairing(config);
  const token = issueToken(pairing).token;
  const router = createRouter({ config, pairing, adapter: routerAdapter, logger: silent });
  const routedList = await routeCall(router, {
    path: `/mobile/v1/sessions/${sessionId}/files`,
    token,
    query: 'path=src',
  });
  assert.equal(routedList.status, 200);
  assert.equal(routedList.body.path, 'src');
  const routedContent = await routeCall(router, {
    path: `/mobile/v1/sessions/${sessionId}/files/content`,
    token,
    query: 'path=alpha.txt',
  });
  assert.equal(routedContent.status, 200);
  assert.equal(routedContent.body.text, 'hello files');
  const routedRaw = await routeCall(router, {
    path: `/mobile/v1/sessions/${sessionId}/files/raw`,
    token,
    query: 'path=alpha.txt',
  });
  assert.equal(routedRaw.status, 200);
  assert.equal(routedRaw.headers['Content-Length'], '11');
  assert.equal(Buffer.from(routedRaw.raw).toString('utf8'), 'hello files');
  const routedBinary = await routeCall(router, {
    path: `/mobile/v1/sessions/${sessionId}/files/content`,
    token,
    query: 'path=binary.bin',
  });
  assert.equal(routedBinary.status, 415);
  assert.equal(routedBinary.body.error.code, 'unsupported');
  assert.equal(
    (await routeCall(router, { path: `/mobile/v1/sessions/${sessionId}/files`, query: 'path=src' })).status,
    401,
  );
  assert.equal(
    (await routeCall(router, {
      path: `/mobile/v1/sessions/${sessionId}/files`,
      token,
      query: 'path=..%2Foutside',
    })).status,
    400,
  );
});

test('workspace files denylist hides credentials from listing and direct reads', async (t) => {
  const root = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'dsh-files-deny-'));
  t.after(async () => {
    await fs.promises.rm(root, { recursive: true, force: true });
  });
  const sessionId = 'session-files-deny';
  const write = async (relative, content = 'denied') => {
    const full = path.join(root, ...relative.split('/'));
    await fs.promises.mkdir(path.dirname(full), { recursive: true });
    await fs.promises.writeFile(full, content);
  };
  const deniedFiles = [
    '.credentials.yaml',
    '.env',
    '.env.local',
    '.Env.Production',
    'credentials.json',
    'Credentials.yml',
    'secrets.yaml',
    'Secrets.md',
    'id_rsa',
    'server.pem',
    'Server.PEM',
    'server.key',
    'cert.p12',
    'vault.kdbx',
    'api.token',
    'API.TOKEN',
    '.npmrc',
    '.pypirc',
    '.netrc',
    'MySecret.txt',
    'secret-santa.ts',
    '.git/config',
    '.ssh/authorized_keys',
    '.aws/credentials',
    '.gnupg/key',
    'src/.npmrc',
    'src/secrets/plan.md',
  ];
  for (const denied of deniedFiles) await write(denied);
  const allowed = [
    ['package.json', '{"name":"visible"}'],
    ['config.yaml', 'key: visible'],
    ['data.json', '{"visible":true}'],
    ['README.md', 'visible readme'],
    ['.gitignore', 'node_modules'],
    ['src/index.json', '{"nested":true}'],
  ];
  for (const [name, content] of allowed) await write(name, content);

  const snapshots = {
    [sessionId]: { session: { ...HEADER, id: sessionId, cwd: root }, inheritedEventCount: 0, events: [] },
  };
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({
        items: [{ sessionId, updatedAt: T(1000), running: false, agentAvailable: false, cwd: root }],
      }),
      sessionQuery: fakeSessionQuery({ snapshots }),
    }),
    config: testConfig(),
    logger: silent,
  });

  const listing = await adapter.listSessionFiles(sessionId, '');
  const listedNames = listing.entries.map((entry) => entry.name.toLowerCase());
  for (const denied of deniedFiles.filter((name) => !name.includes('/'))) {
    assert.equal(listedNames.includes(denied.toLowerCase()), false, `${denied} must be hidden`);
  }
  for (const [name] of allowed.filter(([name]) => !name.includes('/'))) {
    assert.equal(listing.entries.some((entry) => entry.name.toLowerCase() === name.toLowerCase()), true, `${name} must stay visible`);
  }
  assert.equal(listing.entries.some((entry) => entry.name === '.gitignore'), true, '.gitignore is not .git');
  assert.equal(listing.truncated, false);
  assert.equal(listing.entryCount, listing.entries.length);

  const nested = await adapter.listSessionFiles(sessionId, 'src');
  assert.deepEqual(nested.entries.map((entry) => entry.name), ['index.json']);
  assert.equal(nested.entryCount, 1);

  // Guessing a denied path is a generic 404 for every endpoint, including case
  // variants and nested paths.
  const deniedPaths = [
    ...deniedFiles,
    '.GIT/config',
    '.SSH/authorized_keys',
    'CREDENTIALS.JSON',
    'SECRETS.YAML',
    'SERVER.PEM',
    'API.TOKEN',
    'src/.NPMRC',
    'src/SECRETS/plan.md',
  ];
  for (const denied of deniedPaths) {
    await assert.rejects(
      () => adapter.listSessionFiles(sessionId, denied),
      (error) => error instanceof ApiError && error.code === 'not_found',
      `listing ${denied}`,
    );
    await assert.rejects(
      () => adapter.readSessionFileContent(sessionId, denied),
      (error) => error instanceof ApiError && error.code === 'not_found',
      `content ${denied}`,
    );
    await assert.rejects(
      () => adapter.readSessionFileRaw(sessionId, denied),
      (error) => error instanceof ApiError && error.code === 'not_found',
      `raw ${denied}`,
    );
  }

  // Ordinary JSON/YAML/config/source files remain readable.
  assert.equal((await adapter.readSessionFileContent(sessionId, 'package.json')).text, '{"name":"visible"}');
  assert.equal((await adapter.readSessionFileContent(sessionId, 'config.yaml')).text, 'key: visible');
  assert.equal((await adapter.readSessionFileContent(sessionId, 'data.json')).text, '{"visible":true}');
  assert.equal((await adapter.readSessionFileContent(sessionId, 'README.md')).text, 'visible readme');
  assert.equal((await adapter.readSessionFileContent(sessionId, 'src/index.json')).text, '{"nested":true}');

  // An innocently-named symlink to a denied canonical target is not a bypass
  // (conditional because Windows may require privileges to create symlinks).
  let symlinkReady = true;
  try {
    await fs.promises.symlink(path.join(root, '.credentials.yaml'), path.join(root, 'notes.txt'), 'file');
  } catch {
    symlinkReady = false;
  }
  if (symlinkReady) {
    const aliasListing = await adapter.listSessionFiles(sessionId, '');
    assert.equal(aliasListing.entries.some((entry) => entry.name === 'notes.txt'), false);
    await assert.rejects(
      () => adapter.readSessionFileContent(sessionId, 'notes.txt'),
      (error) => error instanceof ApiError && error.code === 'not_found',
    );
    await assert.rejects(
      () => adapter.readSessionFileRaw(sessionId, 'notes.txt'),
      (error) => error instanceof ApiError && error.code === 'not_found',
    );
  }

  // The denylist works through the bearer route too.
  const config = testConfig();
  const routerAdapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({
        items: [{ sessionId, updatedAt: T(1000), running: false, agentAvailable: false, cwd: root }],
      }),
      sessionQuery: fakeSessionQuery({ snapshots }),
    }),
    config,
    logger: silent,
  });
  const pairing = makePairing(config);
  const token = issueToken(pairing).token;
  const router = createRouter({ config, pairing, adapter: routerAdapter, logger: silent });
  const deniedRoute = await routeCall(router, {
    path: `/mobile/v1/sessions/${sessionId}/files/content`,
    token,
    query: 'path=.credentials.yaml',
  });
  assert.equal(deniedRoute.status, 404);
  assert.equal(deniedRoute.body.error.code, 'not_found');
  const allowedRoute = await routeCall(router, {
    path: `/mobile/v1/sessions/${sessionId}/files/content`,
    token,
    query: 'path=package.json',
  });
  assert.equal(allowedRoute.status, 200);
  assert.equal(allowedRoute.body.text, '{"name":"visible"}');
});

test('getSessionJobs folds background jobs without activating a session', async () => {
  const sessionId = 'session-jobs';
  const jobEvent = (type, seq, data) => ({ type, seq, time: T(seq * 1000), data });
  const snapshots = {
    [sessionId]: {
      session: { ...HEADER, id: sessionId },
      inheritedEventCount: 0,
      events: [
        jobEvent('job/registered', 1, {
          jobId: 'pwsh-1',
          owner: 'owner-secret',
          kind: 'pwsh',
          label: 'Slow build',
          startedAt: T(1000),
          toolCall: { id: 'call-secret' },
        }),
        jobEvent('job/settled', 2, {
          jobId: 'pwsh-1',
          owner: 'owner-secret',
          status: 'completed',
          finishedAt: T(4000),
          detail: 'all good',
        }),
        jobEvent('job/registered', 3, {
          jobId: 'pwsh-2',
          kind: 'shell',
          label: 'CLOUDFLARE_API_TOKEN=cfat_FAKE1234567890abcdef Bearer abcdef123456 password=hunter2',
          startedAt: T(2000),
        }),
        jobEvent('job/settled', 4, {
          jobId: 'pwsh-2',
          status: 'failed',
          finishedAt: T(5000),
          detail: 'failed token=supersecret',
        }),
        jobEvent('job/registered', 5, {
          jobId: 'pwsh-3',
          kind: 'pwsh',
          label: 'Still running',
          startedAt: T(6000),
        }),
      ],
    },
    'bare-jobs': { session: { ...HEADER, id: 'bare-jobs' }, inheritedEventCount: 0, events: [] },
    'session-jobs-empty': { session: { ...HEADER, id: 'session-jobs-empty' }, inheritedEventCount: 0, events: [] },
  };
  let agentLookups = 0;
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionQuery: fakeSessionQuery({ snapshots }),
      agents: {
        get() {
          agentLookups += 1;
          throw new Error('jobs must not activate an agent');
        },
      },
    }),
    config: testConfig(),
    logger: silent,
  });

  const board = await adapter.getSessionJobs(sessionId);
  assert.equal(board.supported, true);
  assert.equal(board.truncated, false);
  assert.equal(board.totalJobs, 3);
  assert.deepEqual(board.counts, { running: 1, completed: 1, failed: 1 });
  assert.deepEqual(board.jobs.map((job) => job.id), ['pwsh-3', 'pwsh-2', 'pwsh-1']);
  assert.equal(board.jobs[0].status, 'running');
  assert.equal(board.jobs[1].status, 'failed');
  assert.equal(board.jobs[1].label.includes('abcdef123456'), false);
  assert.equal(board.jobs[1].detail.includes('supersecret'), false);
  assert.equal(board.jobs[2].durationMs, 3000);
  assert.equal(board.jobs[2].startedAt, '2026-10-30T09:40:01Z');
  assert.equal(board.jobs[2].finishedAt, '2026-10-30T09:40:04Z');
  assert.equal(agentLookups, 0, 'snapshot-only read');
  const json = JSON.stringify(board);
  for (const secret of [
    'owner-secret',
    'call-secret',
    'toolCall',
    'abcdef123456',
    'supersecret',
    'cfat_FAKE1234567890abcdef',
    'cfat_',
  ]) {
    assert.equal(json.includes(secret), false, `${secret} must not leak`);
  }

  assert.deepEqual(await adapter.getSessionJobs('session-jobs-empty'), {
    supported: false,
    counts: { running: 0, completed: 0, failed: 0 },
    jobs: [],
    truncated: false,
    totalJobs: 0,
  });

  const hiddenStore = createHiddenStore();
  await hiddenStore.replace([sessionId]);
  const hiddenAdapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config: testConfig(),
    logger: silent,
    hiddenStore,
  });
  await assert.rejects(
    () => hiddenAdapter.getSessionJobs(sessionId),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.getSessionJobs('bare-jobs'),
    (error) => error instanceof ApiError && error.code === 'not_found',
    'internal sessions are 404',
  );
  await assert.rejects(
    () => adapter.getSessionJobs('session-jobs-missing'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  const noHistoryAdapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({
        items: [{ sessionId, updatedAt: T(9000), running: false, agentAvailable: false, cwd: 'C:\\work' }],
      }),
    }),
    config: testConfig(),
    logger: silent,
  });
  await assert.rejects(
    () => noHistoryAdapter.getSessionJobs(sessionId),
    (error) => error instanceof ApiError && error.code === 'unsupported' && error.status === 501,
  );

  const config = testConfig();
  const routerAdapter = createDshAdapter({
    ctx: createFakeCtx({ sessionQuery: fakeSessionQuery({ snapshots }) }),
    config,
    logger: silent,
  });
  const pairing = makePairing(config);
  const token = issueToken(pairing).token;
  const router = createRouter({ config, pairing, adapter: routerAdapter, logger: silent });
  const routed = await routeCall(router, { path: '/mobile/v1/sessions/session-jobs/jobs', token });
  assert.equal(routed.status, 200);
  assert.deepEqual(routed.body.counts, board.counts);
  assert.deepEqual(routed.body.jobs.map((job) => job.id), ['pwsh-3', 'pwsh-2', 'pwsh-1']);
  assert.equal((await routeCall(router, { path: '/mobile/v1/sessions/session-jobs/jobs' })).status, 401);
});

test('getSessionContext reads the projection cache safely and degrades without leaking', async (t) => {
  const cacheDir = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'dsh-context-cache-'));
  t.after(async () => {
    await fs.promises.rm(cacheDir, { recursive: true, force: true });
  });
  const writeCache = async (sessionId, record) => {
    await fs.promises.writeFile(path.join(cacheDir, `${sessionId}.json`), JSON.stringify({ record }));
  };
  const sessions = [
    'session-context-healthy',
    'session-context-round',
    'session-context-clamp',
    'session-context-breakdown',
    'session-context-invalid',
    'session-context-bad',
    'session-context-missing',
    'session-context-symlink',
  ];
  await writeCache('session-context-healthy', {
    updatedAt: T(5000),
    rows: {
      contextPressure: {
        val: { surfaceTokens: 1000, contextWindow: 2000, pressureTokens: 500, sampledSurfaceTokens: 900 },
      },
      contextBreakdown: {
        val: { surfaceTokens: 1000, breakdown: { systemTokens: 100, toolsTokens: 200, messageTokens: 700 } },
      },
      title: { val: 'SECRET TITLE' },
    },
  });
  await writeCache('session-context-round', {
    rows: { contextPressure: { val: { surfaceTokens: 1, contextWindow: 3, pressureTokens: 1 } } },
  });
  await writeCache('session-context-clamp', {
    rows: { contextPressure: { val: { surfaceTokens: 9999, contextWindow: 1000, pressureTokens: 5000 } } },
  });
  await writeCache('session-context-breakdown', {
    rows: {
      contextBreakdown: {
        val: { surfaceTokens: 123, breakdown: { systemTokens: 1, toolsTokens: 2, messageTokens: 120 } },
      },
    },
  });
  await writeCache('session-context-invalid', {
    rows: {
      contextPressure: { val: { surfaceTokens: -1, contextWindow: 0, pressureTokens: 1.5 } },
      contextBreakdown: { val: { surfaceTokens: 'nope', breakdown: { systemTokens: -2 } } },
    },
  });
  await fs.promises.writeFile(path.join(cacheDir, 'session-context-bad.json'), '{not json');

  const warnings = [];
  const logger = { info() {}, warn(message) { warnings.push(message); }, error() {} };
  const contextConfig = testConfig({ usage: { projectionsDir: cacheDir } });
  let agentLookups = 0;
  const ctx = createFakeCtx({
    sessionController: fakeSessionController({
      items: [
        ...sessions.map((sessionId) => ({
          sessionId,
          updatedAt: T(1000),
          running: false,
          agentAvailable: false,
          cwd: 'C:\\work',
        })),
        { sessionId: 'bare-context', updatedAt: T(1000), running: false, agentAvailable: false, cwd: 'C:\\work' },
      ],
    }),
    agents: {
      get() {
        agentLookups += 1;
        throw new Error('context endpoint must not activate');
      },
    },
  });
  const adapter = createDshAdapter({ ctx, config: contextConfig, logger });

  const healthy = await adapter.getSessionContext('session-context-healthy');
  assert.deepEqual(healthy, {
    supported: true,
    surfaceTokens: 1000,
    contextWindow: 2000,
    pressureTokens: 500,
    percent: 25,
    breakdown: { systemTokens: 100, toolsTokens: 200, messageTokens: 700 },
    sampledAt: '2026-10-30T09:40:05Z',
  });
  const healthyJson = JSON.stringify(healthy);
  for (const forbidden of [cacheDir, 'rows', 'val', 'sampledSurfaceTokens', 'SECRET TITLE', 'title']) {
    assert.equal(healthyJson.includes(forbidden), false, `${forbidden} must not leak`);
  }
  assert.equal(agentLookups, 0, 'no agent lookup for cache-backed context reads');

  assert.deepEqual(await adapter.getSessionContext('session-context-round'), {
    supported: true,
    surfaceTokens: 1,
    contextWindow: 3,
    pressureTokens: 1,
    percent: 33.3,
  });
  assert.equal((await adapter.getSessionContext('session-context-clamp')).percent, 100);
  assert.deepEqual(await adapter.getSessionContext('session-context-breakdown'), {
    supported: true,
    surfaceTokens: 123,
    breakdown: { systemTokens: 1, toolsTokens: 2, messageTokens: 120 },
  });
  assert.deepEqual(await adapter.getSessionContext('session-context-invalid'), { supported: false });
  assert.deepEqual(await adapter.getSessionContext('session-context-missing'), { supported: false });

  // Malformed JSON degrades to supported:false and logs at most once per session.
  assert.deepEqual(await adapter.getSessionContext('session-context-bad'), { supported: false });
  assert.deepEqual(await adapter.getSessionContext('session-context-bad'), { supported: false });
  assert.equal(
    warnings.filter((message) => message.includes('context projection cache malformed')).length,
    1,
    'malformed cache logs once',
  );
  assert.equal(warnings.some((message) => message.includes(cacheDir)), false, 'warnings carry no paths');

  // C8: a symlinked cache file is rejected (lstat-is-regular check); the target
  // would otherwise be a valid record. Conditional because Windows may require
  // privileges to create symlinks in the test process.
  await writeCache('real-target', {
    rows: { contextPressure: { val: { surfaceTokens: 10, contextWindow: 20, pressureTokens: 5 } } },
  });
  let symlinkReady = true;
  try {
    await fs.promises.symlink(
      path.join(cacheDir, 'real-target.json'),
      path.join(cacheDir, 'session-context-symlink.json'),
      'file',
    );
  } catch {
    symlinkReady = false;
  }
  if (symlinkReady) {
    assert.deepEqual(
      await adapter.getSessionContext('session-context-symlink'),
      { supported: false },
      'symlinked cache files are not followed',
    );
  }

  // Hidden/internal/unknown sessions are 404 before any cache work.
  const hiddenStore = createHiddenStore();
  await hiddenStore.replace(['session-context-healthy']);
  const hiddenAdapter = createDshAdapter({ ctx, config: contextConfig, logger, hiddenStore });
  await assert.rejects(
    () => hiddenAdapter.getSessionContext('session-context-healthy'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  await assert.rejects(
    () => adapter.getSessionContext('bare-context'),
    (error) => error instanceof ApiError && error.code === 'not_found',
    'internal sessions are 404',
  );
  await assert.rejects(
    () => adapter.getSessionContext('session-context-unknown'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  const config = testConfig({ usage: { projectionsDir: cacheDir } });
  const routerAdapter = createDshAdapter({ ctx, config, logger });
  const pairing = makePairing(config);
  const token = issueToken(pairing).token;
  const router = createRouter({ config, pairing, adapter: routerAdapter, logger });
  const routed = await routeCall(router, {
    path: '/mobile/v1/sessions/session-context-healthy/context',
    token,
  });
  assert.equal(routed.status, 200);
  assert.deepEqual(routed.body.percent, 25);
  assert.equal(
    (await routeCall(router, { path: '/mobile/v1/sessions/session-context-healthy/context' })).status,
    401,
  );
  assert.equal(
    (await routeCall(router, { method: 'POST', path: '/mobile/v1/sessions/session-context-healthy/context', token })).status,
    404,
  );
});

test('getSessionContext bounds the malformed-cache warning bookkeeping', async (t) => {
  const cacheDir = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'dsh-context-warn-'));
  t.after(async () => {
    await fs.promises.rm(cacheDir, { recursive: true, force: true });
  });
  const count = 501;
  const sessionIds = Array.from({ length: count }, (_, index) => `session-context-cap-${index}`);
  await Promise.all(
    sessionIds.map((sessionId) => fs.promises.writeFile(path.join(cacheDir, `${sessionId}.json`), '{broken')),
  );
  const warnings = [];
  const logger = { info() {}, warn(message) { warnings.push(message); }, error() {} };
  const adapter = createDshAdapter({
    ctx: createFakeCtx({
      sessionController: fakeSessionController({
        items: sessionIds.map((sessionId) => ({
          sessionId,
          updatedAt: T(1000),
          running: false,
          agentAvailable: false,
          cwd: 'C:\\work',
        })),
      }),
    }),
    config: testConfig({ usage: { projectionsDir: cacheDir } }),
    logger,
  });

  // One warning each for the first 501 distinct broken sessions; the cap then
  // evicts the oldest (session-context-cap-0).
  await Promise.all(sessionIds.map((sessionId) => adapter.getSessionContext(sessionId)));
  assert.equal(
    warnings.filter((message) => message.includes('context projection cache malformed')).length,
    count,
  );

  // Warning once again for the originally-first session proves the set is
  // bounded (oldest was evicted) rather than growing forever.
  assert.deepEqual(await adapter.getSessionContext('session-context-cap-0'), { supported: false });
  assert.equal(
    warnings.filter((message) => message.includes('context projection cache malformed')).length,
    count + 1,
    'warning bookkeeping is capped and evicts oldest entries',
  );
});




