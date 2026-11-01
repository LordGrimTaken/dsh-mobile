/**
 * Shared test helpers (not a test file: node --test only matches *.test.mjs).
 */

import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { mkdtemp, rm } from 'node:fs/promises';

import { AttachmentStore } from '../lib/attachments.js';
import { resolveConfig } from '../lib/config.js';
import { ApiError } from '../lib/errors.js';
import { HiddenSessionStore } from '../lib/hidden-sessions.js';
import { PairingStore } from '../lib/pairing.js';
import { emptyPushConfig, emptyPushSuppression, normalizePushConfig, normalizePushSuppression, publicPushConfig, publicPushSuppression } from '../lib/push.js';
import { normalizeSearchFilters } from '../lib/search.js';

/** Fake push manager for router tests (real config validation, no network). */
export function createFakePush({ config, sendTestError } = {}) {
  let current = normalizePushConfig(config ?? {}, emptyPushConfig());
  let suppression = { ...emptyPushSuppression(), mutedSessionIds: [] };
  const calls = [];
  return {
    calls,
    getPublicConfig() {
      return publicPushConfig(current);
    },
    getSuppressionSummary() {
      return publicPushSuppression(suppression);
    },
    async updateSuppression(body) {
      calls.push({ method: 'updateSuppression', body });
      suppression = normalizePushSuppression(body, suppression);
      return publicPushSuppression(suppression);
    },
    async update(body) {
      calls.push({ method: 'update', body });
      if (!body || typeof body.enabled !== 'boolean') {
        throw new ApiError('invalid_request', 'Field enabled must be a boolean');
      }
      current = normalizePushConfig(body, current);
      return publicPushConfig(current);
    },
    async sendTest() {
      calls.push({ method: 'sendTest' });
      if (sendTestError) throw sendTestError;
      return { ok: true };
    },
  };
}

/** Config for tests: ephemeral port, temp state file, fast heartbeat. */
export function testConfig(overrides = {}) {  const base = {
    port: 0,
    version: '0.3.13',
    stateFile: path.join(os.tmpdir(), `dsh-mobile-test-${process.pid}-${Math.random().toString(16).slice(2)}.json`),
    sse: { heartbeatMs: 2000 },
    // Unit tests must never share the host's persisted session-list cache or real DSH home.
    sessions: { cacheFile: false },
    usage: {
      projectionsDir: path.join(
        os.tmpdir(),
        `dsh-mobile-test-projections-${process.pid}-${Math.random().toString(16).slice(2)}`,
      ),
    },
    ...overrides,
  };
  return resolveConfig(base);
}

/** Pending-attachment store for router tests. */
export function createAttachmentStore(config, options = {}) {
  return new AttachmentStore({ config, ...(options.now ? { now: options.now } : {}) });
}

/**
 * Hidden-session store for router/adapter tests. Memory-only by default;
 * pass `filePath` for persistence/mtime tests.
 */
export function createHiddenStore(options = {}) {
  return new HiddenSessionStore({ filePath: null, checkIntervalMs: 0, ...options });
}

/** Temp hidden-sessions file path, cleaned up when the test finishes. */
export async function tempHiddenFile(t) {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'dsh-mobile-hidden-'));
  t.after?.(() => rm(dir, { recursive: true, force: true }));
  return path.join(dir, 'hidden-sessions.json');
}

/** Pairing store with no persistence unless a test supplies one. */
export function makePairing(config, options = {}) {
  return new PairingStore({
    config,
    persist: options.persist ?? (async () => {}),
    ...(options.now ? { now: options.now } : {}),
    ...(options.randomInt ? { randomInt: options.randomInt } : {}),
  });
}

/** Create a code and complete pairing; returns the issued token/device. */
export function issueToken(pairing, overrides = {}) {
  const { code } = pairing.createCode();
  return pairing.complete({
    code,
    deviceName: 'Pixel 9',
    platform: 'android',
    ip: '127.0.0.1',
    ...overrides,
  });
}

/** One fake session summary in the shape sessionController.list returns. */
export function sessionSummary(overrides = {}) {
  return {
    sessionId: 's_1',
    updatedAt: Date.UTC(2026, 9, 30, 9, 41, 12),
    running: false,
    agentAvailable: false,
    blank: false,
    cwd: 'C:\\work',
    ...overrides,
  };
}

/**
 * Fake DSH adapter exposing the same surface as lib/dsh-adapter.js.
 * Individual methods can be overridden by tests.
 */
export function createFakeAdapter(overrides = {}) {
  /** @type {Array<Record<string, any>>} */
  const calls = [];
  const workspaces = [{ id: 'ws_1', name: 'DeepSeek Harness', path: 'C:\\.dsh' }];
  const sessions = [
    {
      id: 's_1',
      title: 'Cut a patch release',
      workspaceId: 'ws_1',
      status: 'working',
      preview: 'All 128 tests passed…',
      updatedAt: '2026-10-30T09:41:12Z',
      unread: false,
      live: true,
    },
  ];
  const detail = {
    session: sessions[0],
    messages: [
      { id: 'm_1', role: 'user', text: 'Ship the patch', createdAt: '2026-10-30T09:40:00Z', streaming: false },
      { id: 'm_2', role: 'assistant', text: 'All 128 tests passed', createdAt: '2026-10-30T09:41:12Z', streaming: false },
    ],
    nextCursor: null,
  };

  const adapter = {
    calls,
    async hostInfo() {
      return { name: 'test-host', version: '0.3.13', apiVersion: 1, workspaces };
    },
    async listWorkspaces() {
      return workspaces;
    },
    async listPresets() {
      return {
        presets: [
          { id: 'default', name: 'Default', isDefault: true },
          { id: 'focused', name: 'Focused', isDefault: false },
        ],
        supported: true,
        defaultId: 'default',
      };
    },
    async listSessions() {
      return sessions;
    },
    async searchSessions(query, limit, filters, offset) {
      const pageOffset = Number.isSafeInteger(offset) ? offset : 0;
      calls.push({
        method: 'searchSessions',
        query,
        limit,
        ...(filters === undefined ? {} : { filters }),
        ...(pageOffset === 0 ? {} : { offset: pageOffset }),
      });
      const result = {
        hits: [
          {
            sessionId: 's_1',
            sessionTitle: 'Cut a patch release',
            messageId: 'm_user_1',
            role: 'user',
            snippet: 'Hello there',
            seq: 7,
            createdAt: '2026-10-30T09:40:01Z',
          },
        ],
        supported: true,
        truncated: false,
        offset: pageOffset,
        nextOffset: null,
        hasMore: false,
        scanIncomplete: false,
        scannedSessions: 1,
        totalSessions: 1,
      };
      return filters === undefined
        ? result
        : { ...result, filters: normalizeSearchFilters(filters).echo };
    },
    async getSessionSkills(sessionId) {
      calls.push({ method: 'getSessionSkills', sessionId });
      if (sessionId === 's_missing') throw new ApiError('not_found', 'Session not found');
      return {
        supported: true,
        skills: [
          { name: 'alpha', description: 'Alpha skill' },
          { name: 'beta', description: 'Beta skill' },
        ],
      };
    },
    async getSessionTodos(sessionId) {
      calls.push({ method: 'getSessionTodos', sessionId });
      if (sessionId === 's_missing') throw new ApiError('not_found', 'Session not found');
      return {
        supported: true,
        updatedAt: '2026-10-30T09:40:01Z',
        counts: { pending: 1, inProgress: 1, completed: 1 },
        todos: [
          { content: 'first', status: 'pending' },
          { content: 'second', status: 'in_progress' },
          { content: 'third', status: 'completed' },
        ],
      };
    },
    async getSessionTeam(sessionId) {
      calls.push({ method: 'getSessionTeam', sessionId });
      if (sessionId === 's_missing') throw new ApiError('not_found', 'Session not found');
      return {
        supported: true,
        counts: { pending: 1, inProgress: 1, blocked: 1, completed: 1 },
        members: [{ name: 'Worker One', phase: 'working' }],
        tasks: [
          { id: 'task-1', subject: 'First board task', status: 'in_progress' },
          { id: 'task-2', subject: 'Blocked board task', status: 'blocked', blockedBy: ['task-1'] },
        ],
      };
    },
    async getSessionTrajectory(sessionId, options = {}) {
      calls.push({
        method: 'getSessionTrajectory',
        sessionId,
        ...(options.limit === undefined ? {} : { limit: options.limit }),
        ...(options.beforeSeq === undefined ? {} : { beforeSeq: options.beforeSeq }),
      });
      if (sessionId === 's_missing') throw new ApiError('not_found', 'Session not found');
      return {
        supported: true,
        events: [
          { seq: 7, time: '2026-10-30T09:40:07Z', type: 'user/message', summary: 'hello trajectory' },
          { seq: 6, time: '2026-10-30T09:40:06Z', type: 'assistant/message', summary: 'reply trajectory' },
        ],
        nextBeforeSeq: 6,
        hasMore: true,
      };
    },
    async getSessionJobs(sessionId) {
      calls.push({ method: 'getSessionJobs', sessionId });
      if (sessionId === 's_missing') throw new ApiError('not_found', 'Session not found');
      return {
        supported: true,
        counts: { running: 1, completed: 1, failed: 1 },
        jobs: [
          { id: 'pwsh-3', kind: 'pwsh', label: 'Still running', status: 'running' },
          { id: 'pwsh-2', kind: 'pwsh', label: 'Failed job', status: 'failed' },
          { id: 'pwsh-1', kind: 'pwsh', label: 'Done job', status: 'completed' },
        ],
        truncated: false,
        totalJobs: 3,
      };
    },
    async getSessionContext(sessionId) {
      calls.push({ method: 'getSessionContext', sessionId });
      if (sessionId === 's_missing') throw new ApiError('not_found', 'Session not found');
      return {
        supported: true,
        surfaceTokens: 1000,
        contextWindow: 2000,
        pressureTokens: 500,
        percent: 25,
        breakdown: { systemTokens: 100, toolsTokens: 200, messageTokens: 700 },
        sampledAt: '2026-10-30T09:40:05Z',
      };
    },
    async getApprovalPolicy(sessionId) {
      calls.push({ method: 'getApprovalPolicy', sessionId });
      if (sessionId === 's_missing') throw new ApiError('not_found', 'Session not found');
      return { supported: true, live: false, policy: 'ask' };
    },
    async setApprovalPolicy(sessionId, policy) {
      calls.push({ method: 'setApprovalPolicy', sessionId, policy });
      if (sessionId === 's_missing') throw new ApiError('not_found', 'Session not found');
      if (sessionId === 's_cold') throw new ApiError('invalid_request', 'open the session first');
      return { supported: true, live: true, policy };
    },
    async getModelCatalog() {
      return {
        supported: true,
        default: { provider: 'deepseek', model: 'deepseek-chat' },
        groups: [
          {
            id: 'deepseek',
            name: 'DeepSeek',
            models: [{ id: 'deepseek-chat', name: 'DeepSeek Chat' }],
          },
        ],
        failures: [],
        truncated: false,
      };
    },
    async getSessionModel(sessionId) {
      calls.push({ method: 'getSessionModel', sessionId });
      if (sessionId === 's_missing') throw new ApiError('not_found', 'Session not found');
      return {
        supported: true,
        live: false,
        selection: { provider: 'deepseek', model: 'deepseek-chat' },
        source: 'default',
      };
    },
    async selectSessionModel(sessionId, selection) {
      calls.push({ method: 'selectSessionModel', sessionId, selection });
      if (sessionId === 's_missing') throw new ApiError('not_found', 'Session not found');
      if (sessionId === 's_cold') throw new ApiError('invalid_request', 'open the session first');
      return { supported: true, live: true, selection };
    },
    async listSessionQueue(sessionId) {
      calls.push({ method: 'listSessionQueue', sessionId });
      if (sessionId === 's_missing') throw new ApiError('not_found', 'Session not found');
      return { supported: true, live: false, items: [] };
    },
    async updateSessionQueue(sessionId, itemId, body) {
      calls.push({ method: 'updateSessionQueue', sessionId, itemId, body });
      if (sessionId === 's_missing') throw new ApiError('not_found', 'Session not found');
      if (sessionId === 's_cold') throw new ApiError('invalid_request', 'open the session first');
      if (itemId === 'm_unknown') throw new ApiError('not_found', 'Queued item is no longer pending');
      return { accepted: true };
    },
    async getSession(sessionId, options) {
      calls.push({ method: 'getSession', sessionId, options });
      return detail;
    },
    async sessionExists(sessionId) {
      calls.push({ method: 'sessionExists', sessionId });
      return sessionId !== 's_missing';
    },
    async forkSession(sessionId, atSeq) {
      calls.push({ method: 'forkSession', sessionId, atSeq });
      if (sessionId === 's_missing') throw new ApiError('not_found', 'Session not found');
      if (atSeq === 999) throw new ApiError('invalid_request', 'The requested fork boundary is not available');
      return { sessionId: 'session-forked-1', forkedFrom: sessionId, ...(atSeq === undefined ? {} : { atSeq }) };
    },
    async sessionAccess(sessionId) {
      calls.push({ method: 'sessionAccess', sessionId });
      if (sessionId === 's_missing') return { exists: false, internal: false };
      const internal =
        sessionId === '351ca07c-6d26-46a8-a685-cb627d642806' || sessionId === 's_internal';
      return { exists: true, internal, ...(internal ? { reason: 'origin=subagent' } : {}) };
    },
    async renameSession(sessionId, title) {
      calls.push({ method: 'renameSession', sessionId, title });
      if (sessionId === 's_missing') throw new ApiError('not_found', 'Session not found');
      return { ok: true, title, seq: 42 };
    },
    async archiveSession(sessionId) {
      calls.push({ method: 'archiveSession', sessionId });
      if (sessionId === 's_missing') throw new ApiError('not_found', 'Session not found');
      return { ok: true, archived: true };
    },
    async unarchiveSession(sessionId) {
      calls.push({ method: 'unarchiveSession', sessionId });
      if (sessionId === 's_missing') throw new ApiError('not_found', 'Session not found');
      return { ok: true, archived: false };
    },
    async listArchivedSessions() {
      return { sessions: [], supported: true };
    },
    async uploadAttachment(input) {
      calls.push({ method: 'uploadAttachment', input });
      return {
        ref: { attachmentId: `dsh_att_${calls.length}`, name: input.name, bytes: input.data.length },
        receiptId: input.kind === 'file' ? `receipt_${calls.length}` : null,
      };
    },
    async readAttachment(sessionId, attachmentId) {
      calls.push({ method: 'readAttachment', sessionId, attachmentId });
      if (attachmentId === 'att_missing') {
        throw new ApiError('not_found', 'Attachment not found in this session');
      }
      return {
        status: 200,
        headers: {
          'Content-Type': 'image/png',
          'Content-Length': '4',
          'Content-Disposition': 'inline; filename="shot.png"',
        },
        raw: new Uint8Array([0x89, 0x50, 0x4e, 0x47]),
      };
    },
    async listSchedules() {
      return { schedules: [], supported: true };
    },
    async createSessionSchedule(sessionId, parsed) {
      calls.push({ method: 'createSessionSchedule', sessionId, parsed });
      return {
        supported: true,
        schedule: {
          id: 'schedule-1',
          sessionId,
          title: parsed?.title ?? '',
          instruction: parsed?.instruction ?? '',
          status: 'active',
          schedule: {
            kind: 'after',
            summary: 'in 60s',
            nextRunAt: '2026-10-30T10:01:00Z',
            lastRunAt: null,
          },
          createdAt: '2026-10-30T10:00:00Z',
          updatedAt: '2026-10-30T10:00:00Z',
        },
      };
    },
    async deleteSchedule(scheduleId) {
      calls.push({ method: 'deleteSchedule', scheduleId });
      if (scheduleId === 'schedule_missing') {
        throw new ApiError('not_found', 'Schedule not found');
      }
      return { ok: true, deleted: true };
    },
    async updateSchedule(scheduleId, parsed) {
      calls.push({ method: 'updateSchedule', scheduleId, parsed });
      if (scheduleId === 'schedule_missing') {
        throw new ApiError('not_found', 'Schedule not found');
      }
      return {
        supported: true,
        updated: true,
        changed: true,
        schedule: {
          id: scheduleId,
          sessionId: 's_1',
          title: parsed?.title ?? 'Original title',
          instruction: parsed?.instruction ?? 'Original instruction',
          status: 'active',
          schedule: {
            kind: parsed?.change?.kind ?? 'after',
            summary: 'updated timing',
            nextRunAt: '2026-10-31T10:00:00Z',
            lastRunAt: null,
          },
          createdAt: '2026-10-30T10:00:00Z',
          updatedAt: '2026-10-30T10:05:00Z',
        },
      };
    },
    async createSession(request) {
      calls.push({ method: 'createSession', request });
      return sessions[0];
    },
    async prompt(sessionId, content) {
      calls.push({ method: 'prompt', sessionId, content });
      return { accepted: true, runId: 'run_test' };
    },
    async interrupt(sessionId) {
      calls.push({ method: 'interrupt', sessionId });
      return { ok: true };
    },
    async getSessionGoal(sessionId) {
      calls.push({ method: 'getSessionGoal', sessionId });
      return {
        supported: true,
        live: true,
        goal: {
          id: 'goal_1',
          objective: 'Original objective',
          phase: 'active',
          maxGoalRounds: 100,
          roundsStarted: 1,
          revision: 3,
          activation: 'armed',
          createdAt: '2026-10-30T09:40:00Z',
          updatedAt: '2026-10-30T09:41:00Z',
        },
      };
    },
    async createSessionGoal(sessionId, request) {
      calls.push({ method: 'createSessionGoal', sessionId, request });
      return {
        supported: true,
        live: true,
        goal: {
          id: 'goal_new',
          objective: request.objective,
          phase: 'active',
          maxGoalRounds: request.maxGoalRounds ?? 100,
          roundsStarted: 0,
          revision: 1,
          activation: 'armed',
          createdAt: '2026-10-30T09:40:00Z',
          updatedAt: '2026-10-30T09:40:00Z',
        },
      };
    },
    async resumeSessionGoal(sessionId) {
      calls.push({ method: 'resumeSessionGoal', sessionId });
      return { supported: true, live: true, goal: { id: 'goal_1', phase: 'active', revision: 4 } };
    },
    async pauseSessionGoal(sessionId) {
      calls.push({ method: 'pauseSessionGoal', sessionId });
      return { supported: true, live: true, goal: { id: 'goal_1', phase: 'paused', revision: 4 } };
    },
    async completeSessionGoal(sessionId, ref = {}) {
      calls.push({ method: 'completeSessionGoal', sessionId, ref });
      return { supported: true, live: true, goal: { id: 'goal_1', phase: 'complete', revision: 4 } };
    },
    async editSessionGoal(sessionId, request) {
      calls.push({ method: 'editSessionGoal', sessionId, request });
      return {
        supported: true,
        live: true,
        goal: {
          id: 'goal_1',
          objective: request.objective ?? 'Original objective',
          phase: 'active',
          maxGoalRounds: request.maxGoalRounds ?? 100,
          roundsStarted: 1,
          revision: 4,
          activation: 'armed',
          createdAt: '2026-10-30T09:40:00Z',
          updatedAt: '2026-10-30T09:42:00Z',
        },
      };
    },
    async clearSessionGoal(sessionId) {
      calls.push({ method: 'clearSessionGoal', sessionId });
      return { supported: true, live: true, goal: null };
    },
    async listApprovals() {
      return { approvals: [], supported: true, readOnly: false };
    },
    async resolveApproval(approvalId, decision) {
      calls.push({ method: 'resolveApproval', approvalId, decision });
      return { ok: true, approvalId, outcome: decision === 'deny' ? 'rejected' : 'allowed-once' };
    },
    async listQuestions() {
      return { questions: [], supported: true, readOnly: false };
    },
    async answerQuestion(questionId, answer, optionIndex) {
      calls.push({ method: 'answerQuestion', questionId, answer, optionIndex });
      return { ok: true, questionId, answered: 1, total: 1, complete: true };
    },
    async getUsage() {
      return {
        generatedAt: '2026-10-30T09:41:12Z',
        totals: {
          uncachedInputTokens: 100,
          outputTokens: 20,
          cacheReadTokens: 300,
          cacheWriteTokens: 0,
          totalTokens: 420,
        },
        sessions: [],
        balance: null,
        balanceError: null,
      };
    },
    attach() {
      return () => {};
    },
    ...overrides,
  };
  return adapter;
}

/**
 * Drive the router directly (no sockets).
 * @param {{ handle: (request: any) => Promise<any> }} router
 */
export function routeCall(router, { method = 'GET', path: requestPath, token, body, headers = {}, remoteAddress = '127.0.0.1', query } = {}) {
  const url = new URL(`http://127.0.0.1${requestPath}${query ? `?${query}` : ''}`);
  const mergedHeaders = { ...headers };
  if (token) mergedHeaders.authorization = `Bearer ${token}`;
  return router.handle({
    method,
    pathname: url.pathname,
    query: url.searchParams,
    headers: mergedHeaders,
    body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
    remoteAddress,
  });
}

/** Ask the OS for a currently free loopback port. */
export async function freePort() {
  const probe = net.createServer();
  await new Promise((resolve, reject) => {
    probe.once('error', reject);
    probe.listen(0, '127.0.0.1', resolve);
  });
  const address = probe.address();
  await new Promise((resolve) => probe.close(() => resolve(undefined)));
  return address.port;
}

/** Temp state file path, cleaned up when the test finishes. */
export async function tempStateFile(t) {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'dsh-mobile-test-'));
  t.after?.(() => rm(dir, { recursive: true, force: true }));
  return path.join(dir, 'host-state.json');
}

/** Poll an async predicate until truthy or the timeout elapses. */
export async function waitFor(predicate, { timeoutMs = 5000, intervalMs = 25 } = {}) {
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const value = await predicate();
      if (value) return value;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  throw lastError ?? new Error('waitFor timed out');
}

/** Incremental SSE frame reader used by the end-to-end streaming test. */
export class SseReader {
  /** @param {Response} response */
  constructor(response) {
    this.reader = response.body.getReader();
    this.decoder = new TextDecoder();
    this.buffer = '';
    /** @type {Array<{ id?: string, event: string, data: any }>} */
    this.frames = [];
    this.done = false;
  }

  /** Parse one complete `\n\n` block; returns null for retry/comment-only blocks. */
  #parse(block) {
    let id;
    let event;
    const dataLines = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('id:')) id = line.slice(3).trim();
      else if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
    }
    if (id === undefined && event === undefined && dataLines.length === 0) return null;
    let data = {};
    const raw = dataLines.join('\n');
    if (raw !== '') {
      try {
        data = JSON.parse(raw);
      } catch {
        data = raw;
      }
    }
    return { ...(id === undefined ? {} : { id }), event: event ?? 'message', data };
  }

  /** Wait for the next frame, with a timeout so a broken stream fails fast. */
  async nextFrame(timeoutMs = 5000) {
    if (this.frames.length > 0) return this.frames.shift();
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const separatorIndex = this.buffer.indexOf('\n\n');
      if (separatorIndex !== -1) {
        const block = this.buffer.slice(0, separatorIndex);
        this.buffer = this.buffer.slice(separatorIndex + 2);
        if (block.trim() === '') continue;
        const frame = this.#parse(block);
        if (frame !== null) return frame;
        continue;
      }
      const remaining = deadline - Date.now();
      const read = await Promise.race([
        this.reader.read(),
        new Promise((_, reject) => setTimeout(() => reject(new Error('SSE frame timeout')), Math.max(1, remaining))),
      ]);
      if (read.done) throw new Error('SSE stream ended');
      this.buffer += this.decoder.decode(read.value, { stream: true });
    }
    throw new Error('SSE frame timeout');
  }

  /** Collect frames until the predicate holds; returns every frame read. */
  async until(predicate, timeoutMs = 5000) {
    const collected = [];
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const frame = await this.nextFrame(Math.max(1, deadline - Date.now()));
      collected.push(frame);
      if (predicate(frame)) return collected;
    }
    throw new Error('SSE until() timed out');
  }

  async close() {
    try {
      await this.reader.cancel();
    } catch {
      /* already closed */
    }
  }
}
