#!/usr/bin/env node
/**
 * tools/fake-host/server.mjs
 *
 * Zero-dependency fake host for the DSH Mobile v1 API (Node 22+ / ESM).
 * Implements docs/mobile-api-v1.md with in-memory demo data so the Android
 * client can exercise the real HTTP + SSE paths before the desktop host
 * plugin exists.
 *
 *   node server.mjs                 # 0.0.0.0:3543
 *   PORT=3600 node server.mjs       # custom port
 *   node server.mjs --port 3600     # same via argument
 *
 * DEV ONLY. This server is deliberately insecure: fixed pairing code,
 * fixed bearer token and a wildcard bind. Never expose it outside your
 * development machine, and never use it as a production host.
 *
 * Fake-mode deviations from the contract (all intentional, see README.md):
 *   - POST /pair/new accepts requests from any peer (the real host is loopback only)
 *   - the pairing code and device token are fixed, and the code is reusable
 *     for the lifetime of the process instead of one-use
 *   - state lives in memory: restarting the server reseeds the demo data
 *   - the event id sequence restarts on every process start
 */

import { randomBytes } from 'node:crypto';
import http from 'node:http';

// ---------------------------------------------------------------------------
// Configuration
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

function asInteger(value, fallback) {
  const parsed = Number.parseInt(String(value ?? ''), 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

const API = '/mobile/v1';
const API_VERSION = 1;
const VERSION = '0.2.0';
const DEFAULT_PORT = 3543;
const DEFAULT_PAIR_CODE = '482913';
const PAIR_TTL_MS = 5 * 60_000; // contract: code valid 5 minutes
const MAX_EVENT_LOG = 1_000;
const MAX_MESSAGES_PER_PAGE = 200;

const PORT = asInteger(process.env.PORT ?? argValue('--port') ?? DEFAULT_PORT, DEFAULT_PORT);
if (!Number.isInteger(PORT) || PORT < 1 || PORT > 65535) {
  console.error(`[fake-host] invalid port: ${PORT}`);
  process.exit(1);
}

const BIND_ADDRESS = process.env.FAKE_BIND || '0.0.0.0';
const HOST_NAME = process.env.FAKE_HOST_NAME || 'fake-host';
const PAIR_CODE = process.env.FAKE_PAIR_CODE || DEFAULT_PAIR_CODE;
const ANY_PAIR_CODE = ['1', 'true', 'yes'].includes(
  String(process.env.FAKE_ANY_CODE || '').toLowerCase(),
);
const DEV_TOKEN = process.env.FAKE_TOKEN || 'dev-token';
// Token of the seeded legacy paired device (never used as the current device by
// first pairing; revocable like any other non-current device).
const LEGACY_DEVICE_TOKEN = 'legacy-dev-token';
// Recovery fixture: a configured code is seeded for tests; set
// FAKE_RECOVERY_CODE='' to start unconfigured.
const SEEDED_RECOVERY_CODE = 'dsh-recovery-seed-0001';
const RECOVERY_CODE_INITIAL =
  process.env.FAKE_RECOVERY_CODE === undefined
    ? SEEDED_RECOVERY_CODE
    : process.env.FAKE_RECOVERY_CODE;
const MAX_PAIRED_DEVICES = 10;
// Tailscale auto-pairing fixtures: enabled by default; FAKE_TAILSCALE_LOGIN is
// an optional comma-separated allowlist of Tailscale logins.
const TAILSCALE_AUTOPAIR = !['0', 'false', 'no', 'off'].includes(
  String(process.env.FAKE_TAILSCALE_AUTOPAIR ?? '1').toLowerCase(),
);
const TAILSCALE_LOGIN_ALLOWLIST = String(process.env.FAKE_TAILSCALE_LOGIN ?? '')
  .split(',')
  .map((login) => login.trim().toLowerCase())
  .filter((login) => login !== '');
const HEARTBEAT_MS = clamp(asInteger(process.env.FAKE_HEARTBEAT_MS, 20_000), 500, 60_000);
const STREAM_DELAY_MIN_MS = clamp(asInteger(process.env.FAKE_STREAM_DELAY_MIN, 25), 5, 1_000);
const STREAM_DELAY_MAX_MS = Math.max(
  STREAM_DELAY_MIN_MS,
  clamp(asInteger(process.env.FAKE_STREAM_DELAY_MAX, 40), 5, 1_000),
);
const QUIET = process.env.FAKE_QUIET === '1';
// Read-only approvals mode (default): mirrors a host where approval decisions can
// only be made on the desktop. Set FAKE_APPROVALS_READONLY=0 for the legacy demo
// flow in which POST /approvals/{id} is simulated (used by smoke.mjs).
const APPROVALS_READONLY = !['0', 'false', 'no', 'off'].includes(
  String(process.env.FAKE_APPROVALS_READONLY ?? '1').toLowerCase(),
);
// Mobile-answering approvals mode: answer-mode hosts report readOnly:false, seed
// pending approvals and let POST /approvals/{id} resolve them with
// outcome "allowed-once"/"rejected". Requires FAKE_APPROVALS_READONLY=0 and
// FAKE_APPROVALS_ANSWER=1. Legacy demo flow (smoke.mjs) is
// FAKE_APPROVALS_READONLY=0 with ANSWER unset: simulated allow/deny without seeds.
const APPROVALS_ANSWER = !APPROVALS_READONLY && !['0', 'false', 'no', 'off'].includes(
  String(process.env.FAKE_APPROVALS_ANSWER ?? '0').toLowerCase(),
);
// Read-only questions mode (default): matches the approvals mirror - the phone can
// view pending user-questions but answers stay on the desktop. Set
// FAKE_QUESTIONS_READONLY=0 for the legacy simulated answer flow (used by smoke.mjs).
const QUESTIONS_READONLY = !['0', 'false', 'no', 'off'].includes(
  String(process.env.FAKE_QUESTIONS_READONLY ?? '1').toLowerCase(),
);
// Mobile question-answering mode (requires FAKE_QUESTIONS_READONLY=0 and
// FAKE_QUESTIONS_ANSWER=1): the phone can answer pending questions, reporting
// per-question batch progress ({answered,total,complete}). Legacy simulated flow
// (smoke.mjs) is FAKE_QUESTIONS_READONLY=0 with ANSWER unset.
const QUESTIONS_ANSWER = !QUESTIONS_READONLY && !['0', 'false', 'no', 'off'].includes(
  String(process.env.FAKE_QUESTIONS_ANSWER ?? '0').toLowerCase(),
);

const validTokens = new Set([DEV_TOKEN, LEGACY_DEVICE_TOKEN]);

// ---------------------------------------------------------------------------
// In-memory state
// ---------------------------------------------------------------------------

/** @type {Array<{id: string, name: string, path: string}>} */
const workspaces = [
  { id: 'ws_1', name: 'DeepSeek Harness', path: 'C:\\.dsh' },
  {
    id: 'ws_2',
    name: 'Demo Project',
    path: 'C:\\demo\\project-alpha',
  },
  {
    id: 'ws_3',
    name: 'Research Notes',
    path: 'C:\\demo\\notes',
  },
];

/** @type {Array<{id: string, name: string, description?: string, isDefault: boolean, broken?: boolean}>} */
const presets = [
  {
    id: 'standard',
    name: 'Standard',
    description: 'Default agent preset for general work.',
    isDefault: true,
  },
  {
    id: 'minimal',
    name: 'Minimal',
    description: 'Small context and terse replies for quick jobs.',
    isDefault: false,
  },
  {
    id: 'ptc',
    name: 'PTC',
    description: 'Plan-then-code preset for larger changes.',
    isDefault: false,
  },
  {
    id: 'broken',
    name: 'Broken preset',
    description: 'Fixture preset used to test the disabled/broken preset UI.',
    isDefault: false,
    broken: true,
  },
];

// In-memory ntfy push configuration (default disabled; token only ever reported
// as hasToken).
const pushConfig = {
  enabled: false,
  server: 'https://ntfy.sh',
  topic: '',
  token: '',
};

/** @type {Map<string, 'ask'|'never'>} In-memory per-session approval policy. */
const approvalPolicies = new Map();

// In-memory model catalog (two providers; reasoning efforts on DeepSeek models)
// and per-session selections for the model picker.
const modelCatalog = {
  default: { provider: 'deepseek', model: 'deepseek-chat', reasoningEffort: 'medium' },
  groups: [
    {
      id: 'deepseek',
      name: 'DeepSeek',
      models: [
        {
          id: 'deepseek-chat',
          name: 'DeepSeek Chat',
          description: 'General-purpose chat model.',
          reasoning: {
            efforts: [
              { id: 'low', name: 'Low' },
              { id: 'medium', name: 'Medium' },
              { id: 'high', name: 'High' },
            ],
            defaultEffort: 'medium',
          },
        },
        {
          id: 'deepseek-reasoner',
          name: 'DeepSeek Reasoner',
          description: 'Reasoning model with longer thinking.',
          reasoning: {
            efforts: [
              { id: 'low', name: 'Low' },
              { id: 'medium', name: 'Medium' },
              { id: 'high', name: 'High' },
            ],
            defaultEffort: 'high',
          },
        },
      ],
    },
    {
      id: 'local',
      name: 'Local',
      models: [
        {
          id: 'local-small',
          name: 'Local Small',
          description: 'Fast local model with no reasoning controls.',
        },
      ],
    },
  ],
  failures: [],
};

/** @type {Map<string, {provider: string, model: string, reasoningEffort?: string}>} */
const sessionModelSelections = new Map();
sessionModelSelections.set('s_release', {
  provider: 'deepseek',
  model: 'deepseek-reasoner',
  reasoningEffort: 'high',
});

/**
 * In-memory prompt queues for live sessions (empty for sessions without a seed).
 * @type {Map<string, Array<{itemId: string, text: string, target: 'next-turn'|'next-step', attachmentCount: number, createdAt: string}>>}
 */
const sessionQueues = new Map();
sessionQueues.set('s_release', [
  {
    itemId: 'queue_seed_next_turn',
    text: 'Also update the changelog entry.',
    target: 'next-turn',
    attachmentCount: 0,
    createdAt: isoMinutesAgo(2),
  },
  {
    itemId: 'queue_seed_next_step',
    text: 'After that, run the full test suite.',
    target: 'next-step',
    attachmentCount: 1,
    createdAt: isoMinutesAgo(1),
  },
]);

/**
 * In-memory paired-device registry keyed by deviceId. Every live pairing gets a
 * unique deviceId but shares the fixed dev token (so legacy pair flows keep
 * their expected token); the seeded legacy device has its own token so revoke
 * invalidation is testable.
 * @type {Map<string, {deviceId: string, deviceName: string, platform: string, pairedAt: string, lastSeenAt: string, token: string}>}
 */
const deviceRegistry = new Map();
deviceRegistry.set('dev_seed_legacy', {
  deviceId: 'dev_seed_legacy',
  deviceName: 'Legacy tablet',
  platform: 'android',
  pairedAt: isoMinutesAgo(3 * 24 * 60),
  lastSeenAt: isoMinutesAgo(2 * 24 * 60),
  token: LEGACY_DEVICE_TOKEN,
});

/** @type {Map<string, any>} */
const sessions = new Map();
/** @type {Set<string>} */
const archivedSessions = new Set();
/** @type {Map<string, any>} */
const approvals = new Map();
/** @type {Map<string, any>} */
const questions = new Map();
/** @type {Map<string, any>} */
const goals = new Map();
/** @type {Map<string, {sessionId: string, kind: string, name: string, mimeType: string, bytes: Buffer, width?: number, height?: number}>} */
const attachmentFixtures = new Map();
/** @type {Map<string, any>} */
const schedules = new Map();
let scheduleSequence = 0;
/** @type {Set<{res: http.ServerResponse}>} */
const clients = new Set();
/** @type {Array<any>} */
const eventLog = [];
/** @type {Map<string, {count: number, resetAt: number}>} */
const pairFailures = new Map();

let eventSequence = 0;
let messageSequence = 0;
let goalSequence = 0;
let paired = false;
/** @type {string | null} */
let pairCode = null;
let pairExpiresAt = 0;
/** @type {string | null} */
let recoveryCode = RECOVERY_CODE_INITIAL === '' ? null : RECOVERY_CODE_INITIAL;
/** @type {string | null} */
let recoveryCreatedAt = recoveryCode === null ? null : isoMinutesAgo(30);

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

function nowIso(timestamp = Date.now()) {
  // Contract examples use second precision UTC timestamps ("...Z").
  return new Date(timestamp).toISOString().replace(/\.\d{3}Z$/, 'Z');
}

function isoMinutesAgo(minutes) {
  return nowIso(Date.now() - minutes * 60_000);
}

function nextMessageId() {
  messageSequence += 1;
  return `m_${String(messageSequence).padStart(4, '0')}`;
}

function randomId(prefix) {
  return `${prefix}${randomBytes(6).toString('hex')}`;
}

/** New single-use recovery code in the documented shape. */
function generateRecoveryCode() {
  return `dsh-${randomBytes(6).toString('hex')}`;
}

/** Normalize a user-entered recovery code (whitespace/case-insensitive). */
function normalizeRecoveryCode(value) {
  return String(value ?? '').replace(/\s+/g, '').toLowerCase();
}

/** Simple fake-host device cap (real hosts may track a configured maximum). */
function ensureDeviceCapacity() {
  if (deviceRegistry.size >= MAX_PAIRED_DEVICES) {
    fail(400, 'invalid_request', 'Too many paired devices; revoke one first');
  }
}

function firstLine(text) {
  return String(text).split('\n')[0].trim();
}

function chunkText(text) {
  const chunks = [];
  let index = 0;
  while (index < text.length) {
    const size = 3 + Math.floor(Math.random() * 5); // 3..7 characters
    chunks.push(text.slice(index, index + size));
    index += size;
  }
  return chunks;
}

const APPROVAL_TRIGGER = /deploy|publish|push|delete/i;

class ApiError extends Error {
  /**
   * @param {number} status
   * @param {string} code
   * @param {string} message
   */
  constructor(status, code, message) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

function fail(status, code, message) {
  throw new ApiError(status, code, message);
}

function sendJson(res, status, payload) {
  if (res.writableEnded) return;
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function sendError(res, status, code, message) {
  sendJson(res, status, { error: { code, message } });
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      chunks.push(chunk);
      if (size > 1_000_000) {
        reject(new ApiError(400, 'invalid_request', 'Request body is too large'));
        req.removeAllListeners('data');
        req.resume();
      }
    });
    req.on('end', () => {
      if (size === 0) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        reject(new ApiError(400, 'invalid_request', 'Request body is not valid JSON'));
      }
    });
    req.on('error', (error) => reject(error));
  });
}

function requireObjectBody(body) {
  if (body === null || typeof body !== 'object' || Array.isArray(body)) {
    fail(400, 'invalid_request', 'Request body must be a JSON object');
  }
  return body;
}

function sessionSummary(session) {
  return {
    id: session.id,
    title: session.title,
    workspaceId: session.workspaceId ?? null,
    status: session.status,
    preview: session.preview,
    updatedAt: session.updatedAt,
    unread: session.unread,
    live: true,
    // Session-list goal summary (frozen contract): {phase, roundsStarted,
    // maxGoalRounds} when the session has a goal, otherwise null. Tiny on
    // purpose so banners render without a per-session goal fetch.
    goal: goalSummary(goals.get(session.id) ?? null),
  };
}

function sortedSessions() {
  return [...sessions.values()].sort(
    (left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt),
  );
}

// ---------------------------------------------------------------------------
// SSE plumbing
// ---------------------------------------------------------------------------

function frameFor(envelope) {
  // Match the documented frame exactly: event:, then id: (only for replayable
  // events), then data: carrying the FULL envelope JSON.
  const lines = [`event: ${envelope.type}`];
  if (envelope.id) lines.push(`id: ${envelope.id}`);
  lines.push(`data: ${JSON.stringify(envelope)}`);
  return `${lines.join('\n')}\n\n`;
}

function writeFrame(client, envelope) {
  if (client.res.writableEnded) {
    clients.delete(client);
    return;
  }
  try {
    client.res.write(frameFor(envelope));
  } catch {
    clients.delete(client);
  }
}

function eventSequenceOf(eventId) {
  const match = /^ev_(\d+)$/.exec(String(eventId ?? ''));
  return match ? Number(match[1]) : null;
}

/**
 * Record an event in the replay log and broadcast it to every connected SSE
 * client. `data` is the payload the contract puts inside the envelope.
 */
function emitEvent(type, sessionId, data) {
  eventSequence += 1;
  const envelope = {
    id: `ev_${String(eventSequence).padStart(6, '0')}`,
    type,
    at: nowIso(),
    // Snapshot the payload: callers may keep mutating the source object (e.g. a
    // tool row transitioning running -> done) and the replay log must keep the
    // state that was broadcast at this moment.
    data: data === undefined ? {} : structuredClone(data),
  };
  if (sessionId !== undefined && sessionId !== null) envelope.sessionId = sessionId;
  eventLog.push(envelope);
  if (eventLog.length > MAX_EVENT_LOG) {
    eventLog.splice(0, eventLog.length - MAX_EVENT_LOG);
  }
  for (const client of clients) writeFrame(client, envelope);
  return envelope;
}

function emitSessionUpdated(session) {
  emitEvent('session.updated', session.id, sessionSummary(session));
}

function appendMessage(session, message) {
  session.messages.push(message);
  emitEvent('message.added', session.id, { sessionId: session.id, message });
  return message;
}

function touchSession(session, patch) {
  Object.assign(session, patch, { updatedAt: nowIso() });
  emitSessionUpdated(session);
}

// ---------------------------------------------------------------------------
// Demo seeds (mirrors app/src/main/java/dev/dsh/mobile/data/Demo.kt)
// ---------------------------------------------------------------------------

function seedMessage(role, minutesAgo, text = '', extra = {}) {
  return {
    id: nextMessageId(),
    role,
    text,
    createdAt: isoMinutesAgo(minutesAgo),
    streaming: false,
    ...extra,
  };
}

const TOOL_OUTPUT_MAX = 8_000;
const TOOL_OUTPUT_MARKER = '… (output truncated)';

/**
 * Frozen host contract: `tool.output` keeps the first 8,000 characters of the
 * text-only tool result, then appends the exact marker `… (output truncated)`
 * (after the cap, so the total may exceed 8,000); absent when the result is
 * empty or only images/files.
 */
function seededToolOutput(text) {
  const value = typeof text === 'string' ? text : '';
  if (value === '') return undefined;
  if (value.length <= TOOL_OUTPUT_MAX) return value;
  return `${value.slice(0, TOOL_OUTPUT_MAX)}${TOOL_OUTPUT_MARKER}`;
}

function seedSession(spec) {
  sessions.set(spec.id, {
    id: spec.id,
    title: spec.title,
    workspaceId: spec.workspaceId,
    status: spec.status || 'idle',
    preview: spec.preview,
    updatedAt: isoMinutesAgo(spec.updatedMinutesAgo),
    unread: Boolean(spec.unread),
    // Seeded messages carry an optional 1-based seq (fork cursor).
    messages: spec.messages.map((message, index) => ({ ...message, seq: index + 1 })),
  });
}

/**
 * Register one seeded attachment's bytes and return its Message-attachment DTO
 * ({id,kind,name,mimeType,bytes[,width,height]}, frozen contract).
 */
function seedAttachment(spec) {
  attachmentFixtures.set(spec.id, {
    sessionId: spec.sessionId,
    kind: spec.kind,
    name: spec.name,
    mimeType: spec.mimeType,
    bytes: spec.bytes,
    ...(spec.width === undefined ? {} : { width: spec.width }),
    ...(spec.height === undefined ? {} : { height: spec.height }),
  });
  return {
    id: spec.id,
    kind: spec.kind,
    name: spec.name,
    mimeType: spec.mimeType,
    bytes: spec.bytes.length,
    ...(spec.width === undefined ? {} : { width: spec.width }),
    ...(spec.height === undefined ? {} : { height: spec.height }),
  };
}

/** Public schedule DTO (frozen contract); resolved from the in-memory record. */
function scheduleView(row) {
  const session = sessions.get(row.sessionId);
  return {
    id: row.id,
    sessionId: row.sessionId,
    ...(session?.title ? { sessionTitle: session.title } : {}),
    title: row.title,
    instruction: row.instruction,
    status: row.status,
    schedule: { ...row.schedule },
    createdAt: row.createdAt,
    updatedAt: row.updatedAt,
  };
}

/** Register one seeded schedule (in-memory only, like the demo sessions). */
function seedSchedule(spec) {
  schedules.set(spec.id, {
    id: spec.id,
    sessionId: spec.sessionId,
    title: spec.title,
    instruction: spec.instruction,
    status: spec.status,
    schedule: spec.schedule,
    createdAt: spec.createdAt,
    updatedAt: spec.updatedAt,
  });
}

/** Next local occurrence of an HH:MM daily time, as an ISO timestamp. */
function nextDailyRunAt(dailyTime) {
  const [hours, minutes] = dailyTime.split(':').map(Number);
  const next = new Date();
  next.setSeconds(0, 0);
  next.setHours(hours, minutes, 0, 0);
  if (next.getTime() <= Date.now()) next.setDate(next.getDate() + 1);
  return next.toISOString();
}

/**
 * Validate one POST /sessions/{id}/schedules body against the host contract:
 * title/instruction required, exactly one timing field, ranges enforced.
 * Returns {title, instruction, schedule:{kind,summary,nextRunAt}} or fails 400.
 */
function parseScheduleRequest(body) {
  const title = typeof body.title === 'string' ? body.title.trim() : '';
  if (!title) fail(400, 'invalid_request', 'Body must include a non-empty title');
  if (title.length > 200) fail(400, 'invalid_request', 'Field title must be at most 200 characters');
  const instruction = typeof body.instruction === 'string' ? body.instruction.trim() : '';
  if (!instruction) fail(400, 'invalid_request', 'Body must include a non-empty instruction');
  if (instruction.length > 4_000) {
    fail(400, 'invalid_request', 'Field instruction must be at most 4000 characters');
  }

  const timingFields = ['afterSeconds', 'everySeconds', 'dailyTime', 'at'].filter(
    (field) => body[field] !== undefined && body[field] !== null,
  );
  if (timingFields.length !== 1) {
    fail(
      400,
      'invalid_request',
      'Body must include exactly one of afterSeconds, everySeconds, dailyTime or at',
    );
  }

  return { title, instruction, schedule: parseScheduleTiming(timingFields[0], body) };
}

/**
 * Validate one timing field (`afterSeconds`|`everySeconds`|`dailyTime`|`at`)
 * and return the computed schedule object {kind, summary, nextRunAt}; fails 400
 * with the host error messages. Shared by create and PATCH.
 */
function parseScheduleTiming(field, body) {
  if (field === 'afterSeconds') {
    const value = body.afterSeconds;
    if (!Number.isInteger(value) || value < 1 || value > 86_400) {
      fail(400, 'invalid_request', 'afterSeconds must be an integer between 1 and 86400');
    }
    return {
      kind: 'after',
      summary: `Once in ${value} seconds`,
      nextRunAt: nowIso(Date.now() + value * 1_000),
    };
  }
  if (field === 'everySeconds') {
    const value = body.everySeconds;
    if (!Number.isInteger(value) || value < 60 || value > 604_800) {
      fail(400, 'invalid_request', 'everySeconds must be an integer between 60 and 604800');
    }
    return {
      kind: 'every',
      summary: `Every ${value} seconds`,
      nextRunAt: nowIso(Date.now() + value * 1_000),
    };
  }
  if (field === 'dailyTime') {
    const value = body.dailyTime;
    if (typeof value !== 'string' || !/^([01]\d|2[0-3]):[0-5]\d$/.test(value)) {
      fail(400, 'invalid_request', 'dailyTime must be a 24-hour HH:MM string');
    }
    return {
      kind: 'daily',
      summary: `Every day at ${value}`,
      nextRunAt: nextDailyRunAt(value),
    };
  }

  // field === 'at'
  const value = body.at;
  if (typeof value !== 'string' || Number.isNaN(Date.parse(value))) {
    fail(400, 'invalid_request', 'at must be an ISO date-time string');
  }
  if (Date.parse(value) <= Date.now()) {
    fail(400, 'invalid_request', 'at must be in the future');
  }
  return {
    kind: 'at',
    summary: `Once at ${value}`,
    nextRunAt: new Date(value).toISOString(),
  };
}

function seedDemoData() {
  // Attachment fixtures: a 512x512 PNG (valid IHDR, simple pattern), a second
  // 256x256 PNG for multiple-image paging, and one text/plain file on a seeded
  // user message; their bytes are served by the authenticated attachment route.
  const seededImage = seedAttachment({
    id: 'att_seed_image',
    sessionId: 's_release',
    kind: 'image',
    name: 'screenshot.png',
    mimeType: 'image/png',
    bytes: Buffer.from(
      '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',
      'base64',
    ),
    width: 512,
    height: 512,
  });
  const seededImage2 = seedAttachment({
    id: 'att_seed_image_2',
    sessionId: 's_release',
    kind: 'image',
    name: 'diagram.png',
    mimeType: 'image/png',
    bytes: Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAIAAADTED8xAAAEUklEQVR42u3TOUqEQRSFUZfR2xARRBptEUHEsCN34ohzO+O8SROXYPDSP7aK+x94acFXF87Sz++Pc6O9JRM4AJwDwLmRA5h+HzgXfwA4AABwAADgAADAAQCAA8A0DgDnAHAOAOcAcA4A5wBwDgDnAHAOAOcAcA4A5wBwDgDnAHAOAOcAcA4A5wBwDgDnAHAOAOcAcA4A5wBwDgDnAHAOAOcAcA4A5/4TwMbdbfObzGeT+ayHEj2pPQDoAQAAPQAAoAcAAPQAAIAeAAyqBwCD6hkrgHrsXPYB4AAYArBz8dz8KrGHEj2pPQDoAQAAPQAAoAcAAPQAAIAeAAyqBwCD6gHAoHoAMKgeAAyqBwCD6gHAoHoAMKieXAD7R5/Nrz7QQ4me1B4A9AAAgB4AANADAAB6AABADwAG1QOAQfUAYFA9ABhUDwAG1QOAQfUAYFA9ABhUDwAG1QOAQfWkAVh9O21+9YEeSvSk9gCgBwAA9AAAgB4AANADAAB6ADCoHgAMqgcAg+oBwKB6ADCoHgAMqgcAg+oBwKB6ADCoHgAMqicNQD12LvsAcAAMAZg+XDe/SuyhRE9qDwB6AABADwAA6AEAAD0AAKAHAIPqAcCgegAwqB4ADKoHAIPqAcCgegAwqB4ADKonF8D21WPzqw/0UKIntQcAPQAAoAcAAPQAAIAeAADQA4BB9QBgUD0AGFQPAAbVA4BB9QBgUD0AGFQPAAbVA4BB9QBgUD1pAPZO3ptffaCHEj2pPQDoAQAAPQAAoAcAAPQAAIAeAAyqBwCD6gHAoHoAMKgeAAyqBwCD6gHAoHoAMKgeAAyqBwCD6kkDUI+dyz4AHABDAFY+jptfJfZQoie1BwA9AACgBwAA9AAAgB4AANADgEH1AGBQPQAYVA8ABtUDgEH1AGBQPQAYVA8ABtWTC2D96bL51Qd6KNGT2gOAHgAA0AMAAHoAAEAPAADoAcCgegAwqB4ADKoHAIPqAcCgegAwqB4ADKoHAIPqAcCgegAwqJ40AFs3982vPtBDiZ7UHgD0AACAHgAA0AMAAHoAAEAPAAbVA4BB9QBgUD0AGFQPAAbVA4BB9QBgUD0AGFQPAAbVA4BB9aQBqMfOZR8ADoAhALtnr82vEnso0ZPaA4AeAADQAwAAegAAQA8AAOgBwKB6ADCoHgAMqgcAg+oBwKB6ADCoHgAMqgcAg+rJBbD8ddj86gM9lOhJ7QFADwAA6AEAAD0AAKAHAAD0AGBQPQAYVA8ABtUDgEH1AGBQPQAYVA8ABtUDgEH1AGBQPQAYVE8agLWX8+ZXH+ihRE9qDwB6AABADwAA6AEAAD0AAKAHAIPqAcCgegAwqB4ADKoHAIPqAcCgegAwqB4ADKoHAIPqAcCgetIA1GPnsg8AB8AQgM3FovlVYg8lelJ7ANADAAB6AABADwAA6AEAAD0AGFQPAAbVA4BB9QBgUD3jAuDc2A4AB4BzADg3wvsDJqtYCXLrZ1cAAAAASUVORK5CYII=',
      'base64',
    ),
    width: 256,
    height: 256,
  });
  const seededFile = seedAttachment({
    id: 'att_seed_file',
    sessionId: 's_release',
    kind: 'file',
    name: 'release-checklist.txt',
    mimeType: 'text/plain',
    bytes: Buffer.from('Release checklist\n- tests passed\n- changelog drafted\n', 'utf8'),
  });

  seedSession({
    id: 's_release',
    title: 'Cut a patch release',
    workspaceId: 'ws_1',
    status: 'idle',
    preview: 'All 128 tests passed. The changelog is drafted - publish now?',
    updatedMinutesAgo: 2,
    messages: [
      seedMessage(
        'user',
        30,
        'Cut a patch release and update the changelog. Push when green.',
        { attachments: [seededImage, seededImage2, seededFile] },
      ),
      seedMessage(
        'assistant',
        29,
        "On it - bumping the version, running the full suite, then I'll draft the changelog entry before anything gets published.",
      ),
      seedMessage('tool', 27, '', {
        tool: { name: 'Bash', detail: 'pwsh -File tools/test.ps1 -Jobs 2', state: 'done' },
      }),
      seedMessage(
        'assistant',
        24,
        "All **128 tests passed**. The changelog entry is drafted and the release is staged - say the word and I'll publish. The Quasar-Gate phrase is seeded for search tests.",
      ),
    ],
  });

  seedSession({
    id: 's_economy',
    title: 'Economy page copy pass',
    workspaceId: 'ws_2',
    status: 'working',
    preview: 'Every currency row now names one source and one sink.',
    updatedMinutesAgo: 18,
    unread: true,
    messages: [
      seedMessage(
        'user',
        35,
        'Tighten the economy page copy. One sentence per direction, no placeholders. The quasar-ledger phrase is seeded for search tests.',
      ),
      seedMessage(
        'assistant',
        22,
        'Done. Six currencies, each row names where it comes from and what takes it. I also removed the last two placeholder lines and added a test that proves every currency is covered exactly once.',
      ),
      seedMessage('tool', 21, '', {
        tool: {
          name: 'Edit',
          detail: 'src/ui/economy.ts - +18 -11',
          state: 'done',
          output: seededToolOutput(
            Array.from(
              { length: 260 },
              (_, index) => `economy.ts:${index + 1}: updated currency row ${index + 1}`,
            ).join('\n'),
          ),
        },
      }),
    ],
  });

  seedSession({
    id: 's_android',
    title: 'Android stream teardown',
    workspaceId: 'ws_3',
    status: 'needs_input',
    preview: 'The emulator stream needs a bounded reconnect loop.',
    updatedMinutesAgo: 60,
    messages: [
      seedMessage(
        'user',
        120,
        "The device stream keeps dropping after the screen rotates. Find the root cause but don't change anything yet.",
      ),
      seedMessage(
        'assistant',
        68,
        'Found it: the stream reconnect loop has no backoff and races the activity recreation. I can fix it with a bounded, cancellable retry, but I need direction on whether to keep the stream alive across rotation or restart it cleanly.',
      ),
    ],
  });

  seedSession({
    id: 's_search',
    title: 'Session search bug',
    workspaceId: 'ws_1',
    status: 'idle',
    preview: 'Search misses titles after the workspace rename.',
    updatedMinutesAgo: 180,
    messages: [
      seedMessage(
        'user',
        240,
        'Search for "release" doesn\'t find the session I renamed. Fix it.',
      ),
      seedMessage(
        'assistant',
        195,
        'Fixed. The index cached the old title and never invalidated on rename. Added a regression test that renames a session and searches for the new title.',
      ),
    ],
  });

  seedSession({
    id: 's_worktree',
    title: 'Worktree cleanup',
    workspaceId: 'ws_2',
    status: 'idle',
    preview: 'Removed 3 stale worktrees, kept the codex branch.',
    updatedMinutesAgo: 1_560,
    messages: [
      seedMessage(
        'user',
        1_620,
        'Clean up the stale worktrees but keep the one with uncommitted changes.',
      ),
      seedMessage(
        'assistant',
        1_565,
        'Removed 3 stale worktrees. Kept `codex/help-search` because it has one uncommitted file; I left a note in the session.',
      ),
    ],
  });

  seedSession({
    id: 's_notes',
    title: 'Release notes draft',
    workspaceId: 'ws_1',
    status: 'idle',
    preview: 'Drafted notes for v0.4.7 - two paragraphs and a changelog block.',
    updatedMinutesAgo: 1_800,
    messages: [
      seedMessage('user', 1_860, 'Draft the release notes for v0.4.7. Keep it short.'),
      seedMessage(
        'assistant',
        1_805,
        "Drafted. Two paragraphs and a changelog block, no marketing fluff. It's sitting in `build/release-notes.md`.",
      ),
    ],
  });

  // needs_input session also has a pending question, so GET /questions is not
  // empty out of the box. The seeded question is the read-only-mode fixture and
  // its question.requested event is recorded for SSE replay.
  const seededQuestion = {
    id: 'q_1',
    sessionId: 's_android',
    prompt: 'Which approach should I take?',
    options: ['Keep the stream alive across rotation', 'Restart the stream cleanly'],
    createdAt: isoMinutesAgo(68),
    // Internal batch size (not exposed by questionView): the single-question case.
    batchTotal: 1,
  };
  questions.set(seededQuestion.id, seededQuestion);
  emitEvent('question.requested', seededQuestion.sessionId, seededQuestion);

  // Mobile question-answering mode: a two-question batch on s_release, the single
  // q_1 fixture and a free-text q_free (options: []) so single answers, batch
  // progress ({answered,total,complete}) and custom non-option answers can be
  // exercised.
  if (QUESTIONS_ANSWER) {
    const seededBatch = [
      {
        id: 'q_batch_1',
        sessionId: 's_release',
        prompt: 'Which release channel should v0.4.7 use?',
        options: ['stable', 'beta'],
        createdAt: isoMinutesAgo(7),
        batchTotal: 2,
      },
      {
        id: 'q_batch_2',
        sessionId: 's_release',
        prompt: 'Should the changelog be updated in the same commit?',
        options: ['yes, same commit', 'no, separate commit'],
        createdAt: isoMinutesAgo(6),
        batchTotal: 2,
      },
      {
        id: 'q_free',
        sessionId: 's_economy',
        prompt: 'Anything else I should know about this release?',
        options: [],
        createdAt: isoMinutesAgo(5),
        batchTotal: 1,
      },
    ];
    for (const question of seededBatch) {
      questions.set(question.id, question);
      emitEvent('question.requested', question.sessionId, question);
    }
  }

  // Multi-session goal fixtures for the Goals dashboard / goal-filter UI: four
  // sessions carry distinct phases with realistic round counters. s_release and
  // s_economy stay goalless (goal-test uses them as create/no-goal fixtures).
  // Create/edit/pause/resume/clear via the /goal endpoints (see README.md).
  const goalNow = Date.now();
  goals.set('s_android', {
    id: 'goal_0001',
    objective: 'Make the device stream recover cleanly after screen rotation',
    phase: 'paused',
    maxGoalRounds: 8,
    roundsStarted: 3,
    revision: 2,
    activation: 'disarmed',
    createdAt: goalNow - 12 * 60_000,
    updatedAt: goalNow - 68 * 60_000,
  });
  goals.set('s_search', {
    id: 'goal_0002',
    objective: 'Make session search respect renames without a restart',
    phase: 'active',
    maxGoalRounds: 20,
    roundsStarted: 7,
    revision: 4,
    activation: 'armed',
    createdAt: goalNow - 45 * 60_000,
    updatedAt: goalNow - 3 * 60_000,
  });
  goals.set('s_worktree', {
    id: 'goal_0003',
    objective: 'Clean up stale worktrees and keep the dirty branch',
    phase: 'complete',
    maxGoalRounds: 15,
    roundsStarted: 15,
    revision: 9,
    activation: 'disarmed',
    createdAt: goalNow - 26 * 60 * 60_000,
    updatedAt: goalNow - 25 * 60 * 60_000,
  });
  goals.set('s_notes', {
    id: 'goal_0004',
    objective: 'Draft the v0.4.7 release notes',
    phase: 'blocked',
    maxGoalRounds: 10,
    roundsStarted: 4,
    revision: 2,
    activation: 'disarmed',
    blockedReason: {
      code: 'blocked',
      message: 'Waiting for the release checklist from the desktop',
    },
    createdAt: goalNow - 3 * 60 * 60_000,
    updatedAt: goalNow - 2 * 60 * 60_000,
  });

  // Read-only approvals: one pending deploy approval is seeded so the app/QA can
  // exercise the read-only banner, the {supported, readOnly} list envelope and
  // the SSE approval.requested fixture (replay with /events?since=ev_000000).
  if (APPROVALS_READONLY) {
    const seededApproval = {
      id: 'ap_readonly_seed',
      sessionId: 's_release',
      title: 'Permission needed: deploy release',
      description: 'The agent wants to run a release command in your workspace (read-only demo).',
      command: 'pwsh -File tools/publish.mjs --patch',
      createdAt: isoMinutesAgo(9),
    };
    approvals.set(seededApproval.id, seededApproval);
    emitEvent('approval.requested', seededApproval.sessionId, seededApproval);
  } else if (APPROVALS_ANSWER) {
    // Mobile-answering mode: two pending approvals so both allow and deny paths
    // can be exercised against fresh fixtures (approval.resolved is emitted when
    // resolved; a second POST finds nothing and answers 404).
    const seededApprovals = [
      {
        id: 'ap_answer_seed',
        sessionId: 's_release',
        title: 'Permission needed: deploy release',
        description: 'Mobile-answering demo: allow this approval from the phone.',
        command: 'pwsh -File tools/publish.mjs --patch',
        createdAt: isoMinutesAgo(9),
      },
      {
        id: 'ap_answer_deny',
        sessionId: 's_android',
        title: 'Permission needed: push branch',
        description: 'Mobile-answering demo: deny this approval from the phone.',
        command: 'git push origin feature/mobile-approvals',
        createdAt: isoMinutesAgo(8),
      },
    ];
    for (const seededApproval of seededApprovals) {
      approvals.set(seededApproval.id, seededApproval);
      emitEvent('approval.requested', seededApproval.sessionId, seededApproval);
    }
  }

  // In-memory schedule fixtures: one active daily schedule on s_release and one
  // paused one-off on s_economy (covers sessionTitle, both timing kinds and an
  // inactive status for the schedules list).
  seedSchedule({
    id: 'sch_seed_daily',
    sessionId: 's_release',
    title: 'Daily release status',
    instruction: 'Summarize the release status and list any failing checks.',
    status: 'active',
    schedule: {
      kind: 'daily',
      summary: 'Every day at 09:00',
      nextRunAt: nextDailyRunAt('09:00'),
      lastRunAt: isoMinutesAgo(24 * 60),
    },
    createdAt: isoMinutesAgo(5 * 24 * 60),
    updatedAt: isoMinutesAgo(24 * 60),
  });
  seedSchedule({
    id: 'sch_seed_once',
    sessionId: 's_economy',
    title: 'One-off economy audit',
    instruction: 'Audit the economy page copy once and report a short diff summary.',
    status: 'paused',
    schedule: {
      kind: 'at',
      summary: `Once at ${nowIso(Date.now() + 3 * 24 * 60 * 60 * 1_000)}`,
      nextRunAt: nowIso(Date.now() + 3 * 24 * 60 * 60 * 1_000),
    },
    createdAt: isoMinutesAgo(2 * 24 * 60),
    updatedAt: isoMinutesAgo(2 * 24 * 60),
  });

  // Per-session approval policy fixtures: s_release starts on "never" and
  // s_economy on "ask"; every other session defaults to "ask".
  approvalPolicies.set('s_release', 'never');
  approvalPolicies.set('s_economy', 'ask');
}

// ---------------------------------------------------------------------------
// Mock reply engine (mirrors Demo.kt replyFor / chunked streaming)
// ---------------------------------------------------------------------------

function replyFor(prompt) {
  const lower = prompt.toLowerCase();
  if (lower.includes('test') || lower.includes('suite')) {
    return (
      "Ran the suite - 128 passed, 0 failed. The flaky one from yesterday didn't reappear.\n\n" +
      '```\nTest Files  14 passed (14)\nTests       128 passed (128)\nDuration    41.2s\n```\n\n' +
      'Want me to commit this and open a PR, or keep going on the economy page?'
    );
  }
  if (lower.includes('status') || lower.includes('where')) {
    return (
      "Here's where things stand: the working tree is clean, the last build passed, and the only open question " +
      'is whether to cut the release now or after the economy page lands.\n\n' +
      "I'd lean toward shipping the release today and folding the page into the next patch."
    );
  }
  return (
    "On it. I'll work in your workspace on the desktop and keep this session updated as I go.\n\n" +
    'Plan:\n1. Inspect the current state\n2. Make the change and run the checks\n3. Report back with a short diff summary\n\n' +
    "You can close the app - I'll keep going, and I'll ask before doing anything destructive."
  );
}

/**
 * A "run" owns the timers for one in-flight assistant turn.
 * @typedef {{id: string, cancelled: boolean, timers: Set<NodeJS.Timeout>, messageId: string | null}} Run
 */

function schedule(run, callback, delay) {
  if (run.cancelled) return;
  const timer = setTimeout(() => {
    run.timers.delete(timer);
    if (run.cancelled) return;
    try {
      callback();
    } catch (error) {
      console.error('[fake-host] stream callback failed:', error);
    }
  }, delay);
  run.timers.add(timer);
}

function streamDelay() {
  return (
    STREAM_DELAY_MIN_MS +
    Math.floor(Math.random() * (STREAM_DELAY_MAX_MS - STREAM_DELAY_MIN_MS + 1))
  );
}

function findMessage(session, messageId) {
  return session.messages.find((message) => message.id === messageId);
}

/**
 * Stop the in-flight turn: cancel timers and (when a streaming message was
 * already emitted) close it with a message.completed event. No session state
 * is touched here, callers decide how the turn ended.
 */
function finishRun(session) {
  const run = session.run;
  if (!run) return false;
  run.cancelled = true;
  for (const timer of run.timers) clearTimeout(timer);
  run.timers.clear();
  if (run.messageId) {
    const message = findMessage(session, run.messageId);
    if (message && message.streaming) {
      message.streaming = false;
      emitEvent('message.completed', session.id, {
        sessionId: session.id,
        messageId: message.id,
      });
    }
  }
  session.run = null;
  return true;
}

function startStreamingReply(session, promptText) {
  const run = session.run;
  const reply = replyFor(promptText);
  const chunks = chunkText(reply);

  schedule(
    run,
    () => {
      const message = {
        id: nextMessageId(),
        role: 'assistant',
        text: '',
        createdAt: nowIso(),
        streaming: true,
      };
      session.messages.push(message);
      run.messageId = message.id;
      emitEvent('message.added', session.id, { sessionId: session.id, message });

      let index = 0;
      const step = () => {
        if (run.cancelled) return;
        if (index >= chunks.length) {
          message.streaming = false;
          emitEvent('message.completed', session.id, {
            sessionId: session.id,
            messageId: message.id,
          });
          session.run = null;
          touchSession(session, { status: 'idle', preview: firstLine(reply).slice(0, 90) });
          return;
        }
        const chunk = chunks[index];
        index += 1;
        message.text += chunk;
        emitEvent('message.delta', session.id, {
          sessionId: session.id,
          messageId: message.id,
          text: chunk,
        });
        schedule(run, step, streamDelay());
      };
      schedule(run, step, streamDelay());
    },
    250,
  );
}

function startApprovalRequest(session, promptText) {
  const run = session.run;
  schedule(
    run,
    () => {
      appendMessage(session, {
        id: nextMessageId(),
        role: 'assistant',
        text: 'That command changes something outside the workspace, so I need your approval before I run it.',
        createdAt: nowIso(),
        streaming: false,
      });

      const workspace = workspaces.find((item) => item.id === session.workspaceId);
      const command = /deploy|publish/i.test(promptText)
        ? 'pwsh -File tools/publish.mjs --patch'
        : 'git push origin main';
      const approval = {
        id: randomId('ap_'),
        sessionId: session.id,
        title: 'Permission needed: run command',
        description: `The agent wants to run a command in ${workspace ? workspace.path : 'the workspace'}.`,
        command,
        createdAt: nowIso(),
      };
      approvals.set(approval.id, approval);
      session.run = null;
      emitEvent('approval.requested', session.id, approval);
      touchSession(session, {
        status: 'needs_input',
        preview: `Waiting for approval: ${approval.command}`,
      });
    },
    400,
  );
}

// ---------------------------------------------------------------------------
// Pairing
// ---------------------------------------------------------------------------

function clientIp(req) {
  return req.socket.remoteAddress || 'unknown';
}

function pairingRateLimited(ip) {
  const record = pairFailures.get(ip);
  return Boolean(record && record.count >= 5 && Date.now() < record.resetAt);
}

function recordPairingFailure(ip) {
  const now = Date.now();
  const record = pairFailures.get(ip);
  if (!record || now >= record.resetAt) {
    pairFailures.set(ip, { count: 1, resetAt: now + 60_000 });
    return;
  }
  record.count += 1;
}

function handlePairNew(req, res) {
  paired = false; // a fresh pairing helper call starts a new pairing window
  pairCode = PAIR_CODE;
  pairExpiresAt = Date.now() + PAIR_TTL_MS;
  const forwardedProto = String(req.headers['x-forwarded-proto'] || '').toLowerCase();
  const protocol = forwardedProto === 'https' ? 'https' : 'http';
  const hostHeader = req.headers.host || `127.0.0.1:${PORT}`;
  const base = `${protocol}://${hostHeader}`;
  const qrPayload =
    `dshmobile://pair?base=${encodeURIComponent(base)}` +
    `&code=${encodeURIComponent(pairCode)}` +
    `&host=${encodeURIComponent(HOST_NAME)}`;
  sendJson(res, 200, {
    code: pairCode,
    expiresAt: nowIso(pairExpiresAt),
    qrPayload,
  });
}

async function handlePairComplete(req, res) {
  const ip = clientIp(req);
  if (pairingRateLimited(ip)) {
    fail(429, 'rate_limited', 'Too many failed pairing attempts, try again in a minute');
  }
  const body = requireObjectBody(await readJsonBody(req));
  const code = String(body.code ?? '');
  const expired = pairExpiresAt !== null && Date.now() > pairExpiresAt;
  const valid = ANY_PAIR_CODE
    ? /^\d{6}$/.test(code)
    : pairCode !== null && code === pairCode && !expired;
  if (!valid) {
    recordPairingFailure(ip);
    fail(400, 'invalid_code', 'Pairing code is invalid or has expired');
  }

  paired = true;
  pairFailures.delete(ip);
  const deviceName =
    typeof body.deviceName === 'string' && body.deviceName.trim()
      ? body.deviceName.trim()
      : 'Android device';
  const platform =
    typeof body.platform === 'string' && body.platform.trim() ? body.platform.trim() : 'android';
  // Every live pairing shares the fixed dev token (legacy pair/complete tests
  // expect it) but registers a distinct device record.
  ensureDeviceCapacity();
  const device = {
    deviceId: randomId('dev_'),
    deviceName,
    platform,
    pairedAt: nowIso(),
    lastSeenAt: nowIso(),
    token: DEV_TOKEN,
  };
  deviceRegistry.set(device.deviceId, device);
  validTokens.add(DEV_TOKEN);
  sendJson(res, 200, {
    token: DEV_TOKEN,
    deviceId: device.deviceId,
    host: { name: HOST_NAME, apiVersion: API_VERSION, version: VERSION },
    workspaces: workspaces.map((workspace) => ({
      id: workspace.id,
      name: workspace.name,
      path: workspace.path,
    })),
  });
}

/** GET /mobile/v1/recovery: {supported,configured,createdAt?} (code redacted). */
function handleRecoveryGet(res) {
  sendJson(res, 200, {
    supported: true,
    configured: recoveryCode !== null,
    ...(recoveryCreatedAt === null ? {} : { createdAt: recoveryCreatedAt }),
  });
}

/** POST /mobile/v1/recovery: generate/rotate the code -> {code,createdAt}. */
function handleRecoveryPost(res) {
  recoveryCode = generateRecoveryCode();
  recoveryCreatedAt = nowIso();
  sendJson(res, 200, { code: recoveryCode, createdAt: recoveryCreatedAt });
}

/**
 * POST /mobile/v1/pair/recovery (NO auth): redeem the single-use recovery code,
 * rotating it and registering a recovered device.
 */
async function handlePairRecovery(req, res) {
  const body = requireObjectBody(await readJsonBody(req));
  if (typeof body.code !== 'string' || !body.code.trim()) {
    fail(400, 'invalid_request', 'Body must include a recovery code');
  }
  if (recoveryCode === null) {
    fail(400, 'invalid_request', 'Recovery is not configured on this host');
  }
  if (normalizeRecoveryCode(body.code) !== normalizeRecoveryCode(recoveryCode)) {
    fail(401, 'unauthorized', 'Recovery code is invalid or already used');
  }
  ensureDeviceCapacity();
  const deviceName =
    typeof body.deviceName === 'string' && body.deviceName.trim()
      ? body.deviceName.trim()
      : 'Recovered device';
  const platform =
    typeof body.platform === 'string' && body.platform.trim() ? body.platform.trim() : 'android';
  const device = {
    deviceId: randomId('dev_'),
    deviceName,
    platform,
    pairedAt: nowIso(),
    lastSeenAt: nowIso(),
    token: DEV_TOKEN,
  };
  deviceRegistry.set(device.deviceId, device);
  validTokens.add(DEV_TOKEN);
  paired = true;
  const nextRecoveryCode = generateRecoveryCode();
  recoveryCode = nextRecoveryCode;
  recoveryCreatedAt = nowIso();
  sendJson(res, 200, {
    token: DEV_TOKEN,
    device: {
      deviceId: device.deviceId,
      deviceName: device.deviceName,
      platform: device.platform,
      pairedAt: device.pairedAt,
    },
    recoveryCode: nextRecoveryCode,
  });
}

/** GET /mobile/v1/pair/methods: which pairing helpers this host offers. */
function handlePairMethods(res) {
  sendJson(res, 200, {
    desktopCode: true,
    recovery: recoveryCode !== null,
    tailscaleAutoPair: TAILSCALE_AUTOPAIR,
    tailscaleLoginRequired: TAILSCALE_LOGIN_ALLOWLIST.length > 0,
  });
}

/** Read the Tailscale identity header (string or first array entry). */
function tailscaleLoginHeader(req) {
  const raw = req.headers['tailscale-user-login'];
  if (Array.isArray(raw)) return String(raw[0] ?? '').trim();
  return String(raw ?? '').trim();
}

/**
 * POST /mobile/v1/pair/tailscale (NO auth): pair using the Tailscale identity
 * header; returns a pair-complete-like payload plus pairingMethod/identity.
 */
async function handlePairTailscale(req, res) {
  if (!TAILSCALE_AUTOPAIR) {
    fail(403, 'forbidden', 'Tailscale auto-pairing is disabled on this host');
  }
  const login = tailscaleLoginHeader(req);
  if (!login) {
    fail(400, 'tailscale_identity_missing', 'Tailscale-User-Login header is required');
  }
  if (
    TAILSCALE_LOGIN_ALLOWLIST.length > 0 &&
    !TAILSCALE_LOGIN_ALLOWLIST.includes(login.toLowerCase())
  ) {
    fail(403, 'forbidden', 'Tailscale identity is not allowed to pair with this host');
  }
  const body = requireObjectBody(await readJsonBody(req));
  ensureDeviceCapacity();
  const deviceName =
    typeof body.deviceName === 'string' && body.deviceName.trim()
      ? body.deviceName.trim()
      : 'Tailscale device';
  const platform =
    typeof body.platform === 'string' && body.platform.trim() ? body.platform.trim() : 'android';
  const device = {
    deviceId: randomId('dev_'),
    deviceName,
    platform,
    pairedAt: nowIso(),
    lastSeenAt: nowIso(),
    token: DEV_TOKEN,
  };
  deviceRegistry.set(device.deviceId, device);
  validTokens.add(DEV_TOKEN);
  paired = true;
  sendJson(res, 200, {
    token: DEV_TOKEN,
    deviceId: device.deviceId,
    host: { name: HOST_NAME, apiVersion: API_VERSION, version: VERSION },
    workspaces: workspaces.map((workspace) => ({
      id: workspace.id,
      name: workspace.name,
      path: workspace.path,
    })),
    pairingMethod: 'tailscale',
    identity: { login },
  });
}

// ---------------------------------------------------------------------------
// Route handlers
// ---------------------------------------------------------------------------

function handleHealth(res) {
  sendJson(res, 200, { ok: true, version: VERSION, apiVersion: API_VERSION, paired });
}

/** GET /mobile/v1/presets: sorted agent presets + supported/defaultId. */
function handlePresetsList(res) {
  const list = [...presets].sort((left, right) => left.name.localeCompare(right.name));
  const defaultPreset = presets.find((preset) => preset.isDefault);
  sendJson(res, 200, {
    presets: list,
    supported: true,
    ...(defaultPreset ? { defaultId: defaultPreset.id } : {}),
  });
}

/** GET /mobile/v1/plugins: installed profile-plugin inventory (contract mirror of the real host). */
function handlePluginsList(res) {
  sendJson(res, 200, {
    supported: true,
    profile: 'web',
    packages: [
      { name: '@deepseek-ai/dsh-base', version: '0.2.2-rc.1', specifier: '^0.2.2-rc.1', local: false },
      { name: '@deepseek-ai/dsh-web-app', version: '0.2.2-rc.1', specifier: '^0.2.2-rc.1', local: false },
      { name: '@goodandready/dsh-context-lens', version: '0.1.35', specifier: '^0.1.35', local: false },
      { name: '@goodandready/dsh-plugin-notify', version: '0.4.7', specifier: '^0.4.7', local: false },
      { name: '@local/agent-team-plus', version: '', specifier: 'link:/srv/dsh/workspaces/dsh-root/Xians-DeepSeek-Plugins/plugins/agent-team-plus', local: true },
      { name: '@local/game-lab', version: '', specifier: 'link:/srv/dsh/workspaces/dsh-root/dsh-inapp-plugin', local: true },
      { name: '@local/harness-deck', version: '', specifier: 'link:/srv/dsh/workspaces/dsh-root/Xians-DeepSeek-Plugins/plugins/harness-deck', local: true },
      { name: 'dsh-mobile-host', version: '', specifier: 'link:/srv/dsh/workspaces/dsh-root/dsh-mobile/host-plugin', local: true },
    ],
    bundles: [
      '@deepseek-ai/dsh-base',
      '@deepseek-ai/dsh-web-app',
      '@deepseek-ai/dsh-experimental-plugin-market-bundle',
    ],
  });
}

/** GET /mobile/v1/models: catalog, default selection, failures. */
function handleModelsList(res) {
  sendJson(res, 200, {
    supported: true,
    default: { ...modelCatalog.default },
    groups: modelCatalog.groups,
    failures: modelCatalog.failures,
    truncated: false,
  });
}

/** Effective model selection for a session plus where it came from. */
function effectiveModelSelection(sessionId) {
  const stored = sessionModelSelections.get(sessionId);
  return stored
    ? { selection: { ...stored }, source: 'session' }
    : { selection: { ...modelCatalog.default }, source: 'default' };
}

/** GET /mobile/v1/sessions/{id}/model: {supported,live,selection,source}. */
function handleSessionModelGet(res, sessionId) {
  if (!sessions.has(sessionId)) fail(404, 'not_found', `No such session: ${sessionId}`);
  const { selection, source } = effectiveModelSelection(sessionId);
  sendJson(res, 200, {
    supported: true,
    live: !archivedSessions.has(sessionId),
    selection,
    source,
  });
}

/** PUT /mobile/v1/sessions/{id}/model {provider,model,reasoningEffort?}. */
async function handleSessionModelPut(req, res, sessionId) {
  if (!sessions.has(sessionId)) fail(404, 'not_found', `No such session: ${sessionId}`);
  if (archivedSessions.has(sessionId)) {
    fail(400, 'invalid_request', 'Session is not live; model selection is read-only');
  }
  const body = requireObjectBody(await readJsonBody(req));
  if (typeof body.provider !== 'string' || !body.provider.trim()) {
    fail(400, 'invalid_request', 'Field provider must be a non-empty string');
  }
  if (typeof body.model !== 'string' || !body.model.trim()) {
    fail(400, 'invalid_request', 'Field model must be a non-empty string');
  }
  const provider = body.provider.trim();
  const modelId = body.model.trim();
  const group = modelCatalog.groups.find((entry) => entry.id === provider);
  const model = group?.models.find((entry) => entry.id === modelId);
  if (!model) {
    fail(400, 'invalid_request', `Model is not available in the catalog: ${provider}/${modelId}`);
  }

  let reasoningEffort;
  if (body.reasoningEffort !== undefined && body.reasoningEffort !== null) {
    if (!model.reasoning) {
      fail(400, 'invalid_request', 'This model does not support reasoningEffort');
    }
    if (typeof body.reasoningEffort !== 'string') {
      fail(400, 'invalid_request', 'Field reasoningEffort must be a string');
    }
    reasoningEffort = body.reasoningEffort.trim();
    if (!model.reasoning.efforts.some((effort) => effort.id === reasoningEffort)) {
      fail(400, 'invalid_request', 'reasoningEffort is not supported by this model');
    }
  } else if (model.reasoning) {
    reasoningEffort = model.reasoning.defaultEffort;
  }

  const selection = {
    provider,
    model: modelId,
    ...(reasoningEffort ? { reasoningEffort } : {}),
  };
  sessionModelSelections.set(sessionId, selection);
  sendJson(res, 200, { supported: true, live: true, selection: { ...selection } });
}

/** GET /mobile/v1/sessions/{id}/queue: {supported,live,items}. */
function handleSessionQueueGet(res, sessionId) {
  if (!sessions.has(sessionId)) fail(404, 'not_found', `No such session: ${sessionId}`);
  const live = !archivedSessions.has(sessionId);
  const items = live
    ? (sessionQueues.get(sessionId) ?? []).map((item) => ({ ...item }))
    : [];
  sendJson(res, 200, { supported: true, live, items });
}

/** PATCH /mobile/v1/sessions/{id}/queue/{itemId} {action,text?}. */
async function handleSessionQueueUpdate(req, res, sessionId, itemId) {
  if (!sessions.has(sessionId)) fail(404, 'not_found', `No such session: ${sessionId}`);
  if (archivedSessions.has(sessionId)) {
    fail(400, 'invalid_request', 'Session is not live; queue is read-only');
  }
  const body = requireObjectBody(await readJsonBody(req));
  if (body.action !== 'edit' && body.action !== 'remove' && body.action !== 'steer') {
    fail(400, 'invalid_request', 'Field action must be "edit", "remove" or "steer"');
  }
  const items = sessionQueues.get(sessionId) ?? [];
  const index = items.findIndex((item) => item.itemId === itemId);
  if (index < 0) fail(404, 'not_found', `No such queue item: ${itemId}`);
  const item = items[index];

  if (body.action === 'edit') {
    if (typeof body.text !== 'string') {
      fail(400, 'invalid_request', 'Field text must be a string for edit');
    }
    const text = body.text.trim();
    if (!text) fail(400, 'invalid_request', 'Field text must be a non-empty string');
    if (text.length > 4_000) {
      fail(400, 'invalid_request', 'Field text must be at most 4000 characters');
    }
    item.text = text;
  } else {
    if (body.action === 'steer' && item.target !== 'next-turn') {
      fail(400, 'invalid_request', 'Only a next-turn queue item can be steered');
    }
    items.splice(index, 1);
  }
  sessionQueues.set(sessionId, items);
  sendJson(res, 200, { accepted: true });
}

/** POST /mobile/v1/sessions/{id}/fork {atSeq?} -> {sessionId,forkedFrom,atSeq?}. */
async function handleSessionFork(req, res, sessionId) {
  const source = sessions.get(sessionId);
  if (!source) fail(404, 'not_found', `No such session: ${sessionId}`);
  if (archivedSessions.has(sessionId)) {
    fail(400, 'invalid_request', 'Fork is not available for an archived session');
  }
  const body = requireObjectBody(await readJsonBody(req));
  let atSeq;
  if (body.atSeq !== undefined && body.atSeq !== null) {
    if (!Number.isSafeInteger(body.atSeq) || body.atSeq < 1) {
      fail(400, 'invalid_request', 'Field atSeq must be a positive integer');
    }
    if (!source.messages.some((message) => message.seq === body.atSeq)) {
      fail(400, 'invalid_request', 'atSeq does not match a message in this session');
    }
    atSeq = body.atSeq;
  }

  const cloned = atSeq === undefined
    ? source.messages
    : source.messages.filter((message) => message.seq <= atSeq);
  const newId = randomId('session-fork-');
  const forked = {
    id: newId,
    title: `Fork of ${source.title}`,
    workspaceId: source.workspaceId,
    status: 'idle',
    preview:
      atSeq === undefined
        ? `Fork of ${source.title}`
        : `Fork of ${source.title} at message #${atSeq}`,
    updatedAt: nowIso(),
    unread: false,
    messages: cloned.map((message) => ({ ...message, id: nextMessageId() })),
  };
  sessions.set(newId, forked);
  emitSessionUpdated(forked);
  sendJson(res, 200, {
    sessionId: newId,
    forkedFrom: sessionId,
    ...(atSeq === undefined ? {} : { atSeq }),
  });
}

function handleHost(res) {
  sendJson(res, 200, {
    name: HOST_NAME,
    version: VERSION,
    workspaces: workspaces.map((workspace) => ({
      id: workspace.id,
      name: workspace.name,
      path: workspace.path,
    })),
  });
}

function handleSessionsList(res) {
  const list = sortedSessions()
    .filter((session) => !archivedSessions.has(session.id))
    .map(sessionSummary);
  sendJson(res, 200, { sessions: list });
}

/** GET /mobile/v1/sessions/archived: archived SessionSummary rows. */
function handleArchivedSessionsList(res) {
  const list = sortedSessions()
    .filter((session) => archivedSessions.has(session.id))
    .map(sessionSummary);
  sendJson(res, 200, { sessions: list, supported: true });
}

/** POST /mobile/v1/sessions/{id}/rename: {ok,title} with trimmed 1..200 title. */
async function handleSessionRename(req, res, sessionId) {
  const session = sessions.get(sessionId);
  if (!session || archivedSessions.has(sessionId)) {
    fail(404, 'not_found', `No such session: ${sessionId}`);
  }
  const body = requireObjectBody(await readJsonBody(req));
  if (typeof body.title !== 'string') fail(400, 'invalid_request', 'Field title must be a string');
  const title = body.title.trim();
  if (!title) fail(400, 'invalid_request', 'Field title must be a non-empty string');
  if (title.length > 200) fail(400, 'invalid_request', 'Field title must be at most 200 characters');
  touchSession(session, { title });
  sendJson(res, 200, { ok: true, title: session.title });
}

/** POST /mobile/v1/sessions/{id}/archive: hide from GET /sessions. */
function handleSessionArchive(res, sessionId) {
  const session = sessions.get(sessionId);
  if (!session || archivedSessions.has(sessionId)) {
    fail(404, 'not_found', `No such session: ${sessionId}`);
  }
  archivedSessions.add(sessionId);
  emitEvent('session.removed', sessionId, { sessionId });
  sendJson(res, 200, { ok: true, archived: true });
}

/** POST /mobile/v1/sessions/{id}/unarchive: restore to GET /sessions. */
function handleSessionUnarchive(res, sessionId) {
  const session = sessions.get(sessionId);
  if (!session || !archivedSessions.has(sessionId)) {
    fail(404, 'not_found', `No such session: ${sessionId}`);
  }
  archivedSessions.delete(sessionId);
  touchSession(session, {});
  sendJson(res, 200, { ok: true, archived: false });
}

/** GET/PUT /mobile/v1/sessions/{id}/approval-policy shared view. */
function approvalPolicyView(sessionId) {
  return {
    supported: true,
    live: !archivedSessions.has(sessionId),
    policy: approvalPolicies.get(sessionId) ?? 'ask',
  };
}

/** GET /mobile/v1/sessions/{id}/approval-policy: {supported,live,policy}. */
function handleApprovalPolicyGet(res, sessionId) {
  if (!sessions.has(sessionId)) fail(404, 'not_found', `No such session: ${sessionId}`);
  sendJson(res, 200, approvalPolicyView(sessionId));
}

/** PUT /mobile/v1/sessions/{id}/approval-policy {policy:"ask"|"never"}. */
async function handleApprovalPolicyPut(req, res, sessionId) {
  if (!sessions.has(sessionId)) fail(404, 'not_found', `No such session: ${sessionId}`);
  if (archivedSessions.has(sessionId)) {
    fail(400, 'invalid_request', 'Session is not live; approval policy is read-only');
  }
  const body = requireObjectBody(await readJsonBody(req));
  if (body.policy !== 'ask' && body.policy !== 'never') {
    fail(400, 'invalid_request', 'Field policy must be "ask" or "never"');
  }
  approvalPolicies.set(sessionId, body.policy);
  sendJson(res, 200, approvalPolicyView(sessionId));
}

/** Raw bytes of one seeded attachment (authenticated; session-scoped). */
function handleAttachmentBytes(res, sessionId, attachmentId) {
  const record = attachmentFixtures.get(attachmentId);
  if (!record || record.sessionId !== sessionId) {
    fail(404, 'not_found', `No such attachment: ${attachmentId}`);
  }
  const safeName = String(record.name).replace(/["\\\r\n]/g, '_');
  const disposition = record.kind === 'image' ? 'inline' : 'attachment';
  res.writeHead(200, {
    'Content-Type': record.mimeType,
    'Content-Length': String(record.bytes.length),
    'Content-Disposition': `${disposition}; filename="${safeName}"`,
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
  });
  res.end(record.bytes);
}

function handleSessionDetail(res, session, url) {
  const limitParam = url.searchParams.get('limit');
  let limit = limitParam === null ? 100 : Number.parseInt(limitParam, 10);
  if (!Number.isFinite(limit) || limit <= 0) {
    fail(400, 'invalid_request', 'limit must be a positive integer');
  }
  limit = Math.min(limit, MAX_MESSAGES_PER_PAGE);

  const before = url.searchParams.get('before');
  let end = session.messages.length;
  if (before) {
    const index = session.messages.findIndex((message) => message.id === before);
    if (index < 0) {
      fail(400, 'invalid_request', `Unknown message cursor: ${before}`);
    }
    end = index;
  }
  const start = Math.max(0, end - limit);
  const messages = session.messages.slice(start, end);
  const nextCursor = start > 0 ? session.messages[start].id : null;
  sendJson(res, 200, { session: sessionSummary(session), messages, nextCursor });
}

async function handleSessionCreate(req, res) {
  const body = requireObjectBody(await readJsonBody(req));
  const workspaceId = typeof body.workspaceId === 'string' ? body.workspaceId : '';
  const workspace = workspaces.find((item) => item.id === workspaceId);
  if (!workspace) {
    fail(400, 'invalid_request', 'workspaceId must reference a known workspace');
  }
  if (body.preset !== undefined && body.preset !== null && typeof body.preset !== 'string') {
    fail(400, 'invalid_request', 'preset must be a string when provided');
  }
  const preset = (body.preset || 'default').trim() || 'default';
  const session = {
    id: randomId('s_'),
    title: 'New session',
    workspaceId: workspace.id,
    status: 'idle',
    preview: 'Ready when you are.',
    updatedAt: nowIso(),
    unread: false,
    messages: [],
    run: null,
  };
  session.messages.push({
    id: nextMessageId(),
    role: 'assistant',
    text:
      `New **${preset}** session in ${workspace.name}.\n\n` +
      `I'm running on your desktop with full access to \`${workspace.path}\`. ` +
      'What should I work on?',
    createdAt: nowIso(),
    streaming: false,
  });
  sessions.set(session.id, session);
  emitSessionUpdated(session);
  sendJson(res, 200, { session: sessionSummary(session) });
}

async function handlePrompt(req, res, sessionId) {
  const session = sessions.get(sessionId);
  if (!session) fail(404, 'not_found', `No such session: ${sessionId}`);
  const body = requireObjectBody(await readJsonBody(req));
  const text = typeof body.text === 'string' ? body.text.trim() : '';
  if (!text) fail(400, 'invalid_request', 'Body must include a non-empty "text" string');

  // A new prompt supersedes any previous in-flight turn.
  finishRun(session);

  const runId = randomId('run_');
  appendMessage(session, {
    id: nextMessageId(),
    role: 'user',
    text,
    createdAt: nowIso(),
    streaming: false,
  });
  touchSession(session, { status: 'working', preview: text, unread: false });

  /** @type {Run} */
  const run = { id: runId, cancelled: false, timers: new Set(), messageId: null };
  session.run = run;
  sendJson(res, 200, { accepted: true, runId });

  if (APPROVAL_TRIGGER.test(text)) {
    startApprovalRequest(session, text);
  } else {
    startStreamingReply(session, text);
  }
}

function handleInterrupt(res, sessionId) {
  const session = sessions.get(sessionId);
  if (!session) fail(404, 'not_found', `No such session: ${sessionId}`);
  const hadRun = finishRun(session);
  if (hadRun) {
    appendMessage(session, {
      id: nextMessageId(),
      role: 'system',
      text: 'You stopped this turn.',
      createdAt: nowIso(),
      streaming: false,
    });
    touchSession(session, { status: 'idle', preview: 'Stopped.' });
  }
  sendJson(res, 200, { ok: true });
}

/** Public Approval shape (contract): exactly the documented fields. */
function approvalView(approval) {
  return {
    id: approval.id,
    sessionId: approval.sessionId,
    title: approval.title,
    description: approval.description,
    command: approval.command,
    createdAt: approval.createdAt,
  };
}

function handleApprovalsList(res) {
  const list = [...approvals.values()]
    .sort((left, right) => Date.parse(left.createdAt) - Date.parse(right.createdAt))
    .map(approvalView);
  sendJson(res, 200, { approvals: list, supported: true, readOnly: APPROVALS_READONLY });
}

async function handleApprovalDecision(req, res, approvalId) {
  if (APPROVALS_READONLY) {
    // Read-only approvals: the phone can view but never resolve them.
    fail(501, 'unsupported', 'read-only; resolve on desktop');
  }
  const approval = approvals.get(approvalId);
  if (!approval) fail(404, 'not_found', `No such approval: ${approvalId}`);
  const body = requireObjectBody(await readJsonBody(req));
  const decision = body.decision;
  if (decision !== 'allow' && decision !== 'deny') {
    fail(400, 'invalid_request', 'decision must be "allow" or "deny"');
  }
  if (body.note !== undefined && body.note !== null && typeof body.note !== 'string') {
    fail(400, 'invalid_request', 'note must be a string when provided');
  }

  approvals.delete(approvalId);
  emitEvent('approval.resolved', approval.sessionId, { approvalId });

  if (APPROVALS_ANSWER) {
    // Mobile-answering mode: resolve only (no legacy session simulation) and
    // report the one-shot outcome. A repeat POST now answers 404 above.
    sendJson(res, 200, {
      ok: true,
      approvalId,
      outcome: decision === 'allow' ? 'allowed-once' : 'rejected',
    });
    return;
  }

  const session = sessions.get(approval.sessionId);
  if (session) {
    appendMessage(session, {
      id: nextMessageId(),
      role: 'system',
      text: decision === 'allow' ? `You approved: ${approval.command}` : `You denied: ${approval.command}`,
      createdAt: nowIso(),
      streaming: false,
    });

    if (decision === 'allow') {
      // Contract note: there is no message.updated event, so tool rows are
      // upserted through message.added with the same message id. Mimic a real
      // running -> done transition so clients can exercise the upsert path.
      const toolMessage = {
        id: nextMessageId(),
        role: 'tool',
        text: '',
        createdAt: nowIso(),
        streaming: false,
        tool: { name: 'Bash', detail: approval.command, state: 'running' },
      };
      appendMessage(session, toolMessage);
      touchSession(session, { status: 'working', preview: `Running: ${approval.command}` });

      const published = /publish|deploy/i.test(approval.command);
      const doneText = published
        ? 'Done. Published **v0.4.7** and updated the changelog.\n\n' +
          '```\npublished v0.4.7   ->   3 assets\nchangelog           ->   2 entries\n```'
        : 'Done. The command finished successfully and the working tree is clean.';
      const donePreview = published
        ? 'Done. Published v0.4.7 and updated the changelog.'
        : 'Done. The command finished successfully.';

      const finishTimer = setTimeout(() => {
        toolMessage.tool = { name: 'Bash', detail: approval.command, state: 'done' };
        emitEvent('message.added', session.id, {
          sessionId: session.id,
          message: toolMessage,
        });
        appendMessage(session, {
          id: nextMessageId(),
          role: 'assistant',
          text: doneText,
          createdAt: nowIso(),
          streaming: false,
        });
        touchSession(session, { status: 'idle', preview: donePreview });
      }, 300);
      finishTimer.unref();
    } else {
      appendMessage(session, {
        id: nextMessageId(),
        role: 'assistant',
        text: "Understood - I won't run it. I can prepare a dry run instead if you want.",
        createdAt: nowIso(),
        streaming: false,
      });
      touchSession(session, { status: 'idle', preview: 'Stopped - command denied.' });
    }
  }

  sendJson(res, 200, { ok: true });
}

// ---------------------------------------------------------------------------
// Goals (fake GoalService surface, mirrors host-plugin mapGoalView)
// ---------------------------------------------------------------------------

/** Map one stored goal to the host-plugin `mapGoalView` JSON shape. */
function goalView(goal) {
  if (!goal || typeof goal.id !== 'string') return null;
  return {
    id: goal.id,
    objective: goal.objective,
    phase: goal.phase,
    maxGoalRounds: goal.maxGoalRounds,
    roundsStarted: goal.roundsStarted,
    revision: goal.revision,
    activation: goal.activation,
    createdAt: nowIso(goal.createdAt),
    updatedAt: nowIso(goal.updatedAt),
    ...(goal.blockedReason
      ? {
          blockedReason: {
            code: String(goal.blockedReason.code ?? ''),
            message: String(goal.blockedReason.message ?? ''),
          },
        }
      : {}),
  };
}

/** Compact goal summary embedded in SessionSummary (list/detail/snapshots). */
function goalSummary(goal) {
  if (!goal) return null;
  return {
    phase: goal.phase,
    roundsStarted: goal.roundsStarted,
    maxGoalRounds: goal.maxGoalRounds,
    objective: typeof goal.objective === 'string' ? goal.objective : '',
  };
}

function nextGoalId() {
  goalSequence += 1;
  return `goal_${String(goalSequence).padStart(4, '0')}`;
}

function requireSession(sessionId) {
  const session = sessions.get(sessionId);
  if (!session) fail(404, 'not_found', `No such session: ${sessionId}`);
  return session;
}

function requireGoal(sessionId) {
  requireSession(sessionId);
  const goal = goals.get(sessionId);
  if (!goal) fail(404, 'not_found', 'This session has no current goal');
  return goal;
}

/** {supported, live, goal|null} as returned by GET /sessions/{id}/goal. */
function sessionGoalView(sessionId) {
  requireSession(sessionId);
  return { supported: true, live: true, goal: goalView(goals.get(sessionId) ?? null) };
}

function emitGoalChanged(sessionId, operation, goal) {
  emitEvent('goal.changed', sessionId, { sessionId, operation, goal: goalView(goal) });
}

function handleGoalGet(res, sessionId) {
  sendJson(res, 200, sessionGoalView(sessionId));
}

async function handleGoalCreate(req, res, sessionId) {
  requireSession(sessionId);
  const body = requireObjectBody(await readJsonBody(req));
  const objective = typeof body.objective === 'string' ? body.objective.trim() : '';
  if (!objective) fail(400, 'invalid_request', 'Field objective must be a non-empty string');
  if (objective.length > 4_000) {
    fail(400, 'invalid_request', 'Field objective must be at most 4000 characters');
  }
  let maxGoalRounds = 0;
  if (body.maxGoalRounds !== undefined && body.maxGoalRounds !== null) {
    if (!Number.isInteger(body.maxGoalRounds) || body.maxGoalRounds < 1 || body.maxGoalRounds > 10_000) {
      fail(400, 'invalid_request', 'Field maxGoalRounds must be a positive integer (max 10000)');
    }
    maxGoalRounds = body.maxGoalRounds;
  }
  const current = goals.get(sessionId);
  if (current && current.phase !== 'complete') {
    fail(
      400,
      'invalid_request',
      `A goal already exists with phase "${current.phase}"; resume, complete, or clear it first`,
    );
  }
  const now = Date.now();
  const goal = {
    id: nextGoalId(),
    objective,
    phase: 'active',
    maxGoalRounds,
    roundsStarted: 0,
    revision: 1,
    activation: 'armed',
    createdAt: now,
    updatedAt: now,
  };
  goals.set(sessionId, goal);
  emitGoalChanged(sessionId, 'created', goal);
  sendJson(res, 200, sessionGoalView(sessionId));
}

function handleGoalResume(res, sessionId) {
  const goal = requireGoal(sessionId);
  if (goal.phase === 'complete') {
    fail(400, 'invalid_request', 'A completed goal cannot be resumed; create a new goal instead');
  }
  if (goal.phase === 'active' && goal.activation === 'armed') {
    fail(400, 'invalid_request', 'Goal is already active');
  }
  goal.phase = 'active';
  goal.activation = 'armed';
  delete goal.blockedReason;
  goal.revision += 1;
  goal.updatedAt = Date.now();
  emitGoalChanged(sessionId, 'resumed', goal);
  sendJson(res, 200, sessionGoalView(sessionId));
}

function handleGoalPause(res, sessionId) {
  const goal = requireGoal(sessionId);
  if (goal.phase !== 'active') {
    fail(400, 'invalid_request', `Goal is ${goal.phase}, not active`);
  }
  goal.phase = 'paused';
  goal.activation = 'disarmed';
  goal.revision += 1;
  goal.updatedAt = Date.now();
  emitGoalChanged(sessionId, 'paused', goal);
  sendJson(res, 200, sessionGoalView(sessionId));
}

/**
 * Complete the current goal (`active|paused|blocked` -> `complete`), mirroring
 * pause/resume: optional `{id?, revision?}` concurrency guards must match the
 * current goal, activation becomes disarmed, revision+1. Completing an already
 * complete goal is `400 invalid_request`.
 */
async function handleGoalComplete(req, res, sessionId) {
  const goal = requireGoal(sessionId);
  const body = requireObjectBody(await readJsonBody(req));
  if (goal.phase === 'complete') {
    fail(400, 'invalid_request', 'Goal is already complete');
  }
  if (body.id !== undefined && body.id !== null) {
    if (typeof body.id !== 'string') fail(400, 'invalid_request', 'Field id must be a string');
    if (body.id !== goal.id) fail(400, 'invalid_request', 'Goal id does not match the current goal');
  }
  if (body.revision !== undefined && body.revision !== null) {
    if (!Number.isInteger(body.revision)) {
      fail(400, 'invalid_request', 'Field revision must be an integer');
    }
    if (body.revision !== goal.revision) {
      fail(400, 'invalid_request', 'Goal revision is stale; refresh the goal and retry');
    }
  }
  goal.phase = 'complete';
  goal.activation = 'disarmed';
  delete goal.blockedReason;
  goal.revision += 1;
  goal.updatedAt = Date.now();
  emitGoalChanged(sessionId, 'complete', goal);
  sendJson(res, 200, sessionGoalView(sessionId));
}

/**
 * Edit the objective and/or maxGoalRounds of the current goal. Phase and
 * activation are left unchanged; revision increments (host contract).
 */
async function handleGoalEdit(req, res, sessionId) {
  const goal = requireGoal(sessionId);
  const body = requireObjectBody(await readJsonBody(req));
  const hasObjective = Object.hasOwn(body, 'objective');
  const hasMaxGoalRounds = Object.hasOwn(body, 'maxGoalRounds');
  if (!hasObjective && !hasMaxGoalRounds) {
    fail(400, 'invalid_request', 'Body must include objective or maxGoalRounds');
  }
  let objective = goal.objective;
  if (hasObjective) {
    if (typeof body.objective !== 'string') {
      fail(400, 'invalid_request', 'Field objective must be a string');
    }
    objective = body.objective.trim();
    if (!objective) fail(400, 'invalid_request', 'Field objective must be a non-empty string');
    if (objective.length > 4_000) {
      fail(400, 'invalid_request', 'Field objective must be at most 4000 characters');
    }
  }
  let maxGoalRounds = goal.maxGoalRounds;
  if (hasMaxGoalRounds) {
    if (!Number.isInteger(body.maxGoalRounds) || body.maxGoalRounds < 1 || body.maxGoalRounds > 10_000) {
      fail(400, 'invalid_request', 'Field maxGoalRounds must be a positive integer (max 10000)');
    }
    maxGoalRounds = body.maxGoalRounds;
  }
  goal.objective = objective;
  goal.maxGoalRounds = maxGoalRounds;
  goal.revision += 1;
  goal.updatedAt = Date.now();
  emitGoalChanged(sessionId, 'edit', goal);
  sendJson(res, 200, sessionGoalView(sessionId));
}

/** Clear the current goal entirely; the session becomes goalless again. */
function handleGoalClear(res, sessionId) {
  requireGoal(sessionId);
  goals.delete(sessionId);
  emitGoalChanged(sessionId, 'clear', null);
  sendJson(res, 200, { supported: true, live: true, goal: null });
}

/** Public Question shape (contract): exactly the documented fields. */
function questionView(question) {
  return {
    id: question.id,
    sessionId: question.sessionId,
    prompt: question.prompt,
    options: Array.isArray(question.options) ? [...question.options] : [],
    createdAt: question.createdAt,
  };
}

function handleQuestionsList(res) {
  const list = [...questions.values()]
    .sort((left, right) => Date.parse(left.createdAt) - Date.parse(right.createdAt))
    .map(questionView);
  sendJson(res, 200, { questions: list, supported: true, readOnly: QUESTIONS_READONLY });
}

async function handleQuestionAnswer(req, res, questionId) {
  if (QUESTIONS_READONLY) {
    // Read-only questions: answers must happen on the desktop.
    fail(501, 'unsupported', 'read-only; resolve on desktop');
  }
  const question = questions.get(questionId);
  if (!question) fail(404, 'not_found', `No such question: ${questionId}`);
  const body = requireObjectBody(await readJsonBody(req));
  let answer = typeof body.answer === 'string' ? body.answer.trim() : '';
  if (body.optionIndex !== undefined && body.optionIndex !== null) {
    const index = body.optionIndex;
    if (!Number.isInteger(index) || index < 0 || index >= question.options.length) {
      fail(400, 'invalid_request', 'optionIndex is out of range');
    }
    if (!answer) answer = question.options[index];
  }
  if (!answer) fail(400, 'invalid_request', 'Body must include a non-empty answer or optionIndex');

  if (QUESTIONS_ANSWER) {
    // Mobile-answering mode: report per-session batch progress and resolve only.
    const pendingForSession = [...questions.values()].filter((item) => item.sessionId === question.sessionId).length;
    const total = Number.isInteger(question.batchTotal) && question.batchTotal > 0 ? question.batchTotal : pendingForSession;
    questions.delete(questionId);
    emitEvent('question.resolved', question.sessionId, { questionId });
    const remaining = [...questions.values()].filter((item) => item.sessionId === question.sessionId).length;
    sendJson(res, 200, {
      ok: true,
      questionId,
      answered: total - remaining,
      total,
      complete: remaining === 0,
    });
    return;
  }

  questions.delete(questionId);
  emitEvent('question.resolved', question.sessionId, { questionId });

  const session = sessions.get(question.sessionId);
  if (session) {
    appendMessage(session, {
      id: nextMessageId(),
      role: 'user',
      text: answer,
      createdAt: nowIso(),
      streaming: false,
    });
    touchSession(session, { status: 'idle', preview: firstLine(answer).slice(0, 90) });
  }
  sendJson(res, 200, { ok: true });
}

/** GET /mobile/v1/schedules: {schedules:[Schedule], supported:true}. */
function handleSchedulesList(res) {
  const list = [...schedules.values()]
    .sort((left, right) => {
      const leftAt = Date.parse(left.schedule.nextRunAt ?? left.createdAt);
      const rightAt = Date.parse(right.schedule.nextRunAt ?? right.createdAt);
      return leftAt - rightAt;
    })
    .map(scheduleView);
  sendJson(res, 200, { schedules: list, supported: true });
}

/** POST /mobile/v1/sessions/{id}/schedules -> the created Schedule row. */
async function handleScheduleCreate(req, res, sessionId) {
  const session = sessions.get(sessionId);
  if (!session) fail(404, 'not_found', `No such session: ${sessionId}`);
  const body = requireObjectBody(await readJsonBody(req));
  const parsed = parseScheduleRequest(body);
  scheduleSequence += 1;
  const now = Date.now();
  const row = {
    id: `sch_${String(scheduleSequence).padStart(4, '0')}`,
    sessionId,
    title: parsed.title,
    instruction: parsed.instruction,
    status: 'active',
    schedule: parsed.schedule,
    createdAt: nowIso(now),
    updatedAt: nowIso(now),
  };
  schedules.set(row.id, row);
  sendJson(res, 200, scheduleView(row));
}

/** PATCH /mobile/v1/schedules/{id} -> the updated Schedule row. */
async function handleSchedulePatch(req, res, scheduleId) {
  const row = schedules.get(scheduleId);
  if (!row) fail(404, 'not_found', `No such schedule: ${scheduleId}`);
  const body = requireObjectBody(await readJsonBody(req));
  const hasTitle = Object.hasOwn(body, 'title');
  const hasInstruction = Object.hasOwn(body, 'instruction');
  const timingFields = ['afterSeconds', 'everySeconds', 'dailyTime', 'at'].filter(
    (field) => body[field] !== undefined && body[field] !== null,
  );
  if (!hasTitle && !hasInstruction && timingFields.length === 0) {
    fail(
      400,
      'invalid_request',
      'Body must include at least one of title, instruction, afterSeconds, everySeconds, dailyTime or at',
    );
  }
  if (timingFields.length > 1) {
    fail(
      400,
      'invalid_request',
      'Body must include at most one of afterSeconds, everySeconds, dailyTime or at',
    );
  }
  if (hasTitle) {
    if (typeof body.title !== 'string') fail(400, 'invalid_request', 'Field title must be a string');
    const title = body.title.trim();
    if (!title) fail(400, 'invalid_request', 'Field title must be a non-empty string');
    if (title.length > 200) fail(400, 'invalid_request', 'Field title must be at most 200 characters');
    row.title = title;
  }
  if (hasInstruction) {
    if (typeof body.instruction !== 'string') {
      fail(400, 'invalid_request', 'Field instruction must be a string');
    }
    const instruction = body.instruction.trim();
    if (!instruction) fail(400, 'invalid_request', 'Field instruction must be a non-empty string');
    if (instruction.length > 4_000) {
      fail(400, 'invalid_request', 'Field instruction must be at most 4000 characters');
    }
    row.instruction = instruction;
  }
  if (body.timeZone !== undefined && body.timeZone !== null) {
    if (typeof body.timeZone !== 'string' || !body.timeZone.trim()) {
      fail(400, 'invalid_request', 'Field timeZone must be a non-empty string');
    }
    row.timeZone = body.timeZone.trim();
  }
  if (timingFields.length === 1) {
    row.schedule = parseScheduleTiming(timingFields[0], body);
  }
  row.updatedAt = nowIso();
  sendJson(res, 200, scheduleView(row));
}

/** DELETE /mobile/v1/schedules/{id} -> {ok:true, deleted:true}; unknown 404. */
function handleScheduleDelete(res, scheduleId) {
  if (!schedules.has(scheduleId)) fail(404, 'not_found', `No such schedule: ${scheduleId}`);
  schedules.delete(scheduleId);
  sendJson(res, 200, { ok: true, deleted: true });
}

/** Searchable text of one message (text + tool detail for tool rows). */
function searchableText(message) {
  const parts = [];
  if (typeof message.text === 'string' && message.text) parts.push(message.text);
  if (message.tool && typeof message.tool.detail === 'string' && message.tool.detail) {
    parts.push(message.tool.detail);
  }
  return parts.join('\n');
}

/** Short snippet around the first match, on one line, with ellipses at cuts. */
function searchSnippet(text, matchIndex, queryLength) {
  const collapsed = text.replace(/\s+/g, ' ').trim();
  const index = collapsed.toLowerCase().indexOf(text.slice(matchIndex, matchIndex + queryLength).toLowerCase());
  const start = Math.max(0, (index >= 0 ? index : 0) - 36);
  const end = Math.min(collapsed.length, (index >= 0 ? index : 0) + queryLength + 56);
  const prefix = start > 0 ? '…' : '';
  const suffix = end < collapsed.length ? '…' : '';
  return `${prefix}${collapsed.slice(start, end).trim()}${suffix}`;
}

/** GET/PUT /mobile/v1/push shared view: the token is never returned. */
function pushView() {
  return {
    supported: true,
    enabled: pushConfig.enabled,
    server: pushConfig.server,
    topic: pushConfig.topic,
    hasToken: pushConfig.token.length > 0,
  };
}

function handlePushGet(res) {
  sendJson(res, 200, pushView());
}

/** PUT /mobile/v1/push {enabled, server?, topic?, token?}: validated + persisted. */
async function handlePushPut(req, res) {
  const body = requireObjectBody(await readJsonBody(req));
  if (typeof body.enabled !== 'boolean') {
    fail(400, 'invalid_request', 'Field enabled must be a boolean');
  }
  const server = body.server === undefined
    ? pushConfig.server
    : typeof body.server === 'string'
      ? body.server.trim()
      : fail(400, 'invalid_request', 'Field server must be a string');
  const topic = body.topic === undefined
    ? pushConfig.topic
    : typeof body.topic === 'string'
      ? body.topic.trim()
      : fail(400, 'invalid_request', 'Field topic must be a string');
  let token = pushConfig.token;
  if (body.token !== undefined) {
    if (typeof body.token !== 'string') fail(400, 'invalid_request', 'Field token must be a string');
    token = body.token.trim();
  }

  if (body.enabled) {
    if (!server) fail(400, 'invalid_request', 'server is required when push is enabled');
    if (!topic) fail(400, 'invalid_request', 'topic is required when push is enabled');
  }
  if (server !== '') {
    let parsed;
    try {
      parsed = new URL(server);
    } catch {
      fail(400, 'invalid_request', 'server must be a valid http(s) URL');
    }
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      fail(400, 'invalid_request', 'server must use http or https');
    }
    if (parsed.username !== '' || parsed.password !== '') {
      fail(400, 'invalid_request', 'server must not contain credentials');
    }
  }
  if (topic !== '' && !/^[A-Za-z0-9_-]{1,64}$/.test(topic)) {
    fail(400, 'invalid_request', 'topic must be 1-64 characters of A-Z, a-z, 0-9, _ or -');
  }
  if (token.length > 256) fail(400, 'invalid_request', 'token must be at most 256 characters');

  pushConfig.enabled = body.enabled;
  pushConfig.server = server;
  pushConfig.topic = topic;
  pushConfig.token = token;
  sendJson(res, 200, pushView());
}

/** POST /mobile/v1/push/test: synthetic delivery only (never hits the network). */
function handlePushTest(res) {
  sendJson(res, 200, { ok: true });
}

/**
 * GET /mobile/v1/search?q=<text>&limit=50[&offset=0][&role=&since=&until=]:
 * case-insensitive substring search over seeded message text + tool detail,
 * newest-first, optionally filtered by role list and inclusive date bounds;
 * offset pages through the ordered hits (0..500).
 */
function handleSearch(res, url) {
  const query = (url.searchParams.get('q') ?? '').trim();
  if (query.length < 2 || query.length > 200) {
    fail(400, 'invalid_request', 'Query "q" must be between 2 and 200 characters');
  }
  const limitParam = url.searchParams.get('limit');
  let limit = 50;
  if (limitParam !== null && limitParam !== '') {
    limit = Number(limitParam);
    if (!Number.isInteger(limit) || limit < 1 || limit > 100) {
      fail(400, 'invalid_request', 'limit must be an integer between 1 and 100');
    }
  }
  const offsetParam = url.searchParams.get('offset');
  let offset = 0;
  if (offsetParam !== null) {
    offset = offsetParam === '' ? Number.NaN : Number(offsetParam);
    if (!Number.isInteger(offset) || offset < 0 || offset > 500) {
      fail(400, 'invalid_request', 'offset must be an integer between 0 and 500');
    }
  }

  const roleParam = url.searchParams.get('role');
  let roleFilter = null;
  if (roleParam !== null) {
    const roles = roleParam.split(',').map((role) => role.trim());
    if (
      roles.length === 0 ||
      roles.some((role) => role === '' || !['user', 'assistant', 'tool'].includes(role))
    ) {
      fail(400, 'invalid_request', 'role must be a comma-separated list of user, assistant, tool');
    }
    roleFilter = new Set(roles);
  }

  function parseBound(param, name) {
    if (param === null) return null;
    const parsed = Date.parse(param);
    if (Number.isNaN(parsed)) {
      fail(400, 'invalid_request', `${name} must be an ISO date-time`);
    }
    return parsed;
  }
  const sinceMs = parseBound(url.searchParams.get('since'), 'since');
  const untilMs = parseBound(url.searchParams.get('until'), 'until');
  if (sinceMs !== null && untilMs !== null && sinceMs > untilMs) {
    fail(400, 'invalid_request', 'since must not be after until');
  }

  const lowered = query.toLowerCase();
  const hits = [];
  for (const session of sessions.values()) {
    for (const message of session.messages) {
      if (roleFilter !== null && !roleFilter.has(message.role)) continue;
      if (sinceMs !== null || untilMs !== null) {
        const createdAt = message.createdAt ? Date.parse(message.createdAt) : NaN;
        if (Number.isNaN(createdAt)) continue;
        if (sinceMs !== null && createdAt < sinceMs) continue;
        if (untilMs !== null && createdAt > untilMs) continue;
      }
      const text = searchableText(message);
      const index = text.toLowerCase().indexOf(lowered);
      if (index < 0) continue;
      hits.push({
        sessionId: session.id,
        ...(session.title ? { sessionTitle: session.title } : {}),
        messageId: message.id,
        role: message.role,
        snippet: searchSnippet(text, index, query.length),
        ...(message.createdAt ? { createdAt: message.createdAt } : {}),
      });
    }
  }
  hits.sort(
    (left, right) =>
      Date.parse(right.createdAt ?? 0) - Date.parse(left.createdAt ?? 0) ||
      left.messageId.localeCompare(right.messageId),
  );
  const page = hits.slice(offset, offset + limit);
  const hasMore = offset + page.length < hits.length;
  sendJson(res, 200, {
    hits: page,
    supported: true,
    // The fake host has no bounded-scan cap, so `truncated` is always false;
    // `hasMore` reports whether another page of matches exists.
    truncated: false,
    offset,
    nextOffset: hasMore ? offset + page.length : null,
    hasMore,
  });
}

function handleEvents(req, res, url) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache, no-transform',
    'X-Accel-Buffering': 'no',
  });
  if (typeof res.flushHeaders === 'function') res.flushHeaders();
  if (res.socket) res.socket.setNoDelay(true);

  const client = { res };
  clients.add(client);
  if (!QUIET) console.log('[fake-host] SSE client connected');

  // Contract: host.status is sent on connect.
  writeFrame(client, {
    type: 'host.status',
    at: nowIso(),
    data: { connected: true },
  });

  const sinceRaw = url.searchParams.get('since');
  const sinceSequence = eventSequenceOf(sinceRaw);
  const oldestRetained = eventLog.length > 0 ? eventSequenceOf(eventLog[0].id) : null;
  const cursorUsable =
    sinceSequence !== null &&
    sinceSequence <= eventSequence &&
    (oldestRetained === null || sinceSequence >= oldestRetained - 1);

  if (cursorUsable) {
    for (const event of eventLog) {
      if (Number(event.id.slice(3)) > sinceSequence) writeFrame(client, event);
    }
  } else {
    // Cursor missing/evicted/unrecognised: send a full session snapshot per
    // contract and let the client refetch details.
    for (const session of sessions.values()) {
      writeFrame(client, {
        type: 'session.updated',
        at: nowIso(),
        sessionId: session.id,
        data: sessionSummary(session),
      });
    }
  }

  req.on('close', () => {
    clients.delete(client);
    if (!QUIET) console.log('[fake-host] SSE client disconnected');
  });
}

// ---------------------------------------------------------------------------
// Auth + routing
// ---------------------------------------------------------------------------

/** Latest paired device for one bearer token (the "current" device). */
function currentDeviceForToken(token) {
  return [...deviceRegistry.values()]
    .filter((device) => device.token === token)
    .sort(
      (left, right) =>
        Date.parse(right.pairedAt) - Date.parse(left.pairedAt) ||
        left.deviceId.localeCompare(right.deviceId),
    )[0];
}

/** GET /mobile/v1/devices: paired devices plus the bearer's current device id. */
function handleDevicesList(res, token) {
  const current = currentDeviceForToken(token);
  const devices = [...deviceRegistry.values()]
    .map((device) => ({
      deviceId: device.deviceId,
      deviceName: device.deviceName,
      platform: device.platform,
      pairedAt: device.pairedAt,
      lastSeenAt: device.lastSeenAt,
    }))
    .sort(
      (left, right) =>
        Date.parse(right.pairedAt) - Date.parse(left.pairedAt) ||
        left.deviceId.localeCompare(right.deviceId),
    );
  sendJson(res, 200, {
    supported: true,
    devices,
    currentDeviceId: current ? current.deviceId : null,
  });
}

/** DELETE /mobile/v1/devices/{deviceId}: revoke a non-current paired device. */
function handleDeviceRevoke(res, token, deviceId) {
  const device = deviceRegistry.get(deviceId);
  if (!device) fail(404, 'not_found', `No such device: ${deviceId}`);
  const current = currentDeviceForToken(token);
  if (device.token === token && current && device.deviceId === current.deviceId) {
    fail(400, 'invalid_request', 'Cannot revoke the current device');
  }
  deviceRegistry.delete(deviceId);
  const tokenStillUsed = [...deviceRegistry.values()].some((entry) => entry.token === device.token);
  if (!tokenStillUsed) validTokens.delete(device.token);
  sendJson(res, 200, { ok: true, revoked: true });
}

function requireAuth(req) {
  const header = req.headers.authorization || '';
  const match = /^Bearer\s+(.+)$/i.exec(header);
  if (!match || !validTokens.has(match[1])) {
    fail(401, 'unauthorized', 'Missing or invalid device token');
  }
  const token = match[1];
  for (const device of deviceRegistry.values()) {
    if (device.token === token) device.lastSeenAt = nowIso();
  }
  return token;
}

function normalizePath(pathname) {
  if (pathname.length > 1) return pathname.replace(/\/+$/, '');
  return pathname;
}

async function dispatch(req, res, url, pathname) {
  const method = req.method || 'GET';

  // Public endpoints (contract: only /health and /pair/complete skip auth;
  // /pair/new is loopback-only on the real host and open in fake mode).
  if (method === 'GET' && pathname === `${API}/health`) return handleHealth(res);
  if (method === 'GET' && pathname === `${API}/pair/methods`) return handlePairMethods(res);
  if (method === 'POST' && pathname === `${API}/pair/new`) return handlePairNew(req, res);
  if (method === 'POST' && pathname === `${API}/pair/complete`) return handlePairComplete(req, res);
  if (method === 'POST' && pathname === `${API}/pair/recovery`) return handlePairRecovery(req, res);
  if (method === 'POST' && pathname === `${API}/pair/tailscale`) return handlePairTailscale(req, res);

  const authToken = requireAuth(req);
  if (method === 'GET' && pathname === `${API}/recovery`) return handleRecoveryGet(res);
  if (method === 'POST' && pathname === `${API}/recovery`) return handleRecoveryPost(res);
  if (method === 'GET' && pathname === `${API}/devices`) return handleDevicesList(res, authToken);
  let deviceMatch = /^\/mobile\/v1\/devices\/([^/]+)$/.exec(pathname);
  if (deviceMatch && method === 'DELETE') {
    return handleDeviceRevoke(res, authToken, decodeURIComponent(deviceMatch[1]));
  }

  if (method === 'GET' && pathname === `${API}/host`) return handleHost(res);
  if (method === 'GET' && pathname === `${API}/presets`) return handlePresetsList(res);
  if (method === 'GET' && pathname === `${API}/models`) return handleModelsList(res);
  if (method === 'GET' && pathname === `${API}/plugins`) return handlePluginsList(res);
  if (method === 'GET' && pathname === `${API}/sessions`) return handleSessionsList(res);
  if (method === 'GET' && pathname === `${API}/sessions/archived`) return handleArchivedSessionsList(res);
  if (method === 'POST' && pathname === `${API}/sessions`) return handleSessionCreate(req, res);

  let match = /^\/mobile\/v1\/sessions\/([^/]+)\/rename$/.exec(pathname);
  if (match && method === 'POST') return handleSessionRename(req, res, match[1]);

  match = /^\/mobile\/v1\/sessions\/([^/]+)\/archive$/.exec(pathname);
  if (match && method === 'POST') return handleSessionArchive(res, match[1]);

  match = /^\/mobile\/v1\/sessions\/([^/]+)\/unarchive$/.exec(pathname);
  if (match && method === 'POST') return handleSessionUnarchive(res, match[1]);

  match = /^\/mobile\/v1\/sessions\/([^/]+)\/fork$/.exec(pathname);
  if (match && method === 'POST') return handleSessionFork(req, res, match[1]);

  match = /^\/mobile\/v1\/sessions\/([^/]+)\/approval-policy$/.exec(pathname);
  if (match && method === 'GET') return handleApprovalPolicyGet(res, match[1]);
  if (match && method === 'PUT') return handleApprovalPolicyPut(req, res, match[1]);

  match = /^\/mobile\/v1\/sessions\/([^/]+)\/model$/.exec(pathname);
  if (match && method === 'GET') return handleSessionModelGet(res, match[1]);
  if (match && method === 'PUT') return handleSessionModelPut(req, res, match[1]);

  match = /^\/mobile\/v1\/sessions\/([^/]+)\/queue$/.exec(pathname);
  if (match && method === 'GET') return handleSessionQueueGet(res, match[1]);

  match = /^\/mobile\/v1\/sessions\/([^/]+)\/queue\/([^/]+)$/.exec(pathname);
  if (match && method === 'PATCH') {
    return handleSessionQueueUpdate(req, res, match[1], decodeURIComponent(match[2]));
  }

  if (method === 'GET' && pathname === `${API}/approvals`) return handleApprovalsList(res);
  if (method === 'GET' && pathname === `${API}/questions`) return handleQuestionsList(res);
  if (method === 'GET' && pathname === `${API}/events`) return handleEvents(req, res, url);
  if (method === 'GET' && pathname === `${API}/search`) return handleSearch(res, url);
  if (method === 'GET' && pathname === `${API}/push`) return handlePushGet(res);
  if (method === 'PUT' && pathname === `${API}/push`) return handlePushPut(req, res);
  if (method === 'POST' && pathname === `${API}/push/test`) return handlePushTest(res);

  if (method === 'GET' && pathname === `${API}/schedules`) return handleSchedulesList(res);

  match = /^\/mobile\/v1\/sessions\/([^/]+)\/schedules$/.exec(pathname);
  if (match && method === 'POST') return handleScheduleCreate(req, res, match[1]);

  match = /^\/mobile\/v1\/schedules\/([^/]+)$/.exec(pathname);
  if (match && method === 'DELETE') return handleScheduleDelete(res, decodeURIComponent(match[1]));
  if (match && method === 'PATCH') return handleSchedulePatch(req, res, decodeURIComponent(match[1]));

  match = /^\/mobile\/v1\/sessions\/([^/]+)\/attachments\/([^/]+)$/.exec(pathname);
  if (match && method === 'GET') return handleAttachmentBytes(res, match[1], match[2]);

  match = /^\/mobile\/v1\/sessions\/([^/]+)\/prompt$/.exec(pathname);
  if (match && method === 'POST') return handlePrompt(req, res, match[1]);

  match = /^\/mobile\/v1\/sessions\/([^/]+)\/interrupt$/.exec(pathname);
  if (match && method === 'POST') return handleInterrupt(res, match[1]);

  match = /^\/mobile\/v1\/sessions\/([^/]+)\/goal$/.exec(pathname);
  if (match && method === 'GET') return handleGoalGet(res, match[1]);
  if (match && method === 'POST') return handleGoalCreate(req, res, match[1]);

  match = /^\/mobile\/v1\/sessions\/([^/]+)\/goal\/resume$/.exec(pathname);
  if (match && method === 'POST') return handleGoalResume(res, match[1]);

  match = /^\/mobile\/v1\/sessions\/([^/]+)\/goal\/pause$/.exec(pathname);
  if (match && method === 'POST') return handleGoalPause(res, match[1]);

  match = /^\/mobile\/v1\/sessions\/([^/]+)\/goal\/complete$/.exec(pathname);
  if (match && method === 'POST') return handleGoalComplete(req, res, match[1]);

  match = /^\/mobile\/v1\/sessions\/([^/]+)\/goal\/edit$/.exec(pathname);
  if (match && method === 'POST') return handleGoalEdit(req, res, match[1]);

  match = /^\/mobile\/v1\/sessions\/([^/]+)\/goal\/clear$/.exec(pathname);
  if (match && method === 'POST') return handleGoalClear(res, match[1]);

  match = /^\/mobile\/v1\/sessions\/([^/]+)$/.exec(pathname);
  if (match && method === 'GET') {
    const session = sessions.get(match[1]);
    if (!session) fail(404, 'not_found', `No such session: ${match[1]}`);
    return handleSessionDetail(res, session, url);
  }

  match = /^\/mobile\/v1\/approvals\/([^/]+)$/.exec(pathname);
  if (match && method === 'POST') return handleApprovalDecision(req, res, match[1]);

  match = /^\/mobile\/v1\/questions\/([^/]+)$/.exec(pathname);
  if (match && method === 'POST') return handleQuestionAnswer(req, res, match[1]);

  fail(404, 'not_found', `Unknown endpoint: ${method} ${pathname}`);
}

// ---------------------------------------------------------------------------
// HTTP server lifecycle
// ---------------------------------------------------------------------------

const server = http.createServer(async (req, res) => {
  const startedAt = Date.now();
  res.on('finish', () => {
    if (QUIET) return;
    const duration = Date.now() - startedAt;
    console.log(`[fake-host] ${req.method} ${req.url} -> ${res.statusCode} (${duration}ms)`);
  });

  try {
    const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
    await dispatch(req, res, url, normalizePath(url.pathname));
  } catch (error) {
    if (error instanceof ApiError) {
      sendError(res, error.status, error.code, error.message);
      return;
    }
    console.error('[fake-host] unhandled error:', error);
    if (!res.headersSent) sendError(res, 500, 'internal', 'Unexpected server error');
    else if (!res.writableEnded) res.end();
  }
});

const heartbeatTimer = setInterval(() => {
  for (const client of clients) {
    writeFrame(client, { type: 'ping', at: nowIso(), data: {} });
  }
}, HEARTBEAT_MS);

server.on('error', (error) => {
  if (error && error.code === 'EADDRINUSE') {
    console.error(
      `[fake-host] port ${PORT} is already in use. Stop the other process or use PORT=<other> .`,
    );
  } else {
    console.error('[fake-host] server error:', error);
  }
  process.exit(1);
});

seedDemoData();

server.listen(PORT, BIND_ADDRESS, () => {
  console.log(`[fake-host] DSH Mobile API v1 fake host listening on http://${BIND_ADDRESS}:${PORT}`);
  console.log(
    `[fake-host] pairing code "${ANY_PAIR_CODE ? '<any 6-digit>' : PAIR_CODE}" ` +
      `token "${DEV_TOKEN}" host name "${HOST_NAME}"`,
  );
  console.log(`[fake-host] emulator base URL: http://10.0.2.2:${PORT}`);
  console.log('[fake-host] phone on same Wi-Fi: http://<pc-lan-ip>:' + PORT);
  console.log('[fake-host] WARNING: fake mode - dev machine only, do not expose publicly.');
});

let shuttingDown = false;
function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log(`\n[fake-host] ${signal} received, shutting down.`);
  clearInterval(heartbeatTimer);
  for (const client of clients) {
    try {
      client.res.end();
    } catch {
      // ignore
    }
  }
  clients.clear();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 1_000).unref();
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
