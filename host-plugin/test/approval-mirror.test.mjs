import test from 'node:test';
import assert from 'node:assert/strict';

import { ApprovalMirror, createApprovalMirror, mapApprovalRequest } from '../lib/approval-mirror.js';
import { ApiError } from '../lib/errors.js';
import { EventBus } from '../lib/events.js';

const silent = { info() {}, warn() {}, error() {} };
const T = (offset = 0) => Date.UTC(2026, 10, 1, 9, 0, 0) + offset;

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** Minimal fake Cordis ctx that records waterfall listeners. */
function fakeWaterfallCtx() {
  /** @type {Map<string, Function[]>} */
  const listeners = new Map();
  return {
    on(event, listener) {
      const list = listeners.get(event) ?? [];
      list.push(listener);
      listeners.set(event, list);
      return () => listeners.set(event, (listeners.get(event) ?? []).filter((entry) => entry !== listener));
    },
    listenersFor(event) {
      return [...(listeners.get(event) ?? [])];
    },
  };
}

/** Collect warning lines so background desktop failures can be asserted. */
function warningCollector() {
  const warnings = [];
  return {
    warnings,
    logger: {
      info() {},
      error() {},
      warn(message) {
        warnings.push(String(message));
      },
    },
  };
}

function makeMirror({ bus = null, cap = 32, logger = silent, overrides = {} } = {}) {
  let counter = 0;
  return createApprovalMirror({
    bus,
    cap,
    logger,
    now: () => T(1000),
    idFactory: () => `ap_${++counter}`,
    ...overrides,
  });
}

const REQUEST = {
  agent: { id: 'session-approval-1' },
  toolName: 'Bash',
  callId: 'call_9',
  displayReason: { en: 'Publish the release?' },
};

const flush = () => new Promise((resolve) => setTimeout(resolve, 10));

test('approval mirror reports readOnly false by default and true when answering is disabled', () => {
  assert.deepEqual(makeMirror().list(), { approvals: [], supported: true, readOnly: false });
  assert.deepEqual(makeMirror({ overrides: { mobileAnswer: false } }).list(), {
    approvals: [],
    supported: true,
    readOnly: true,
  });
});

test('approval mirror records a pending row while next() awaits and forwards desktop outcomes', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 20 });
  const mirror = makeMirror({ bus });
  const gate = deferred();

  const outcomePromise = mirror.handle(REQUEST, () => gate.promise);
  assert.deepEqual(mirror.list(), {
    approvals: [
      {
        id: 'ap_1',
        sessionId: 'session-approval-1',
        title: 'Permission needed: Bash',
        description: 'Publish the release?',
        command: '',
        createdAt: '2026-11-01T09:00:01Z',
      },
    ],
    supported: true,
    readOnly: false,
  });
  const requested = bus.buffer.find((event) => event.type === 'approval.requested');
  assert.deepEqual(requested.data, mirror.list().approvals[0]);
  assert.equal(requested.sessionId, 'session-approval-1');

  gate.resolve('allowed-once');
  assert.equal(await outcomePromise, 'allowed-once', 'the desktop outcome is returned unchanged');
  assert.deepEqual(mirror.list().approvals, []);
  const resolved = bus.buffer.at(-1);
  assert.equal(resolved.type, 'approval.resolved');
  assert.deepEqual(resolved.data, { approvalId: 'ap_1' });
  assert.equal(resolved.sessionId, 'session-approval-1');
});

test('approval mirror clears and emits resolved when desktop next() rejects (error rethrown unchanged)', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 20 });
  const mirror = makeMirror({ bus });
  const gate = deferred();
  const outcomePromise = mirror.handle(REQUEST, () => gate.promise);
  assert.equal(mirror.size, 1);

  const desktopError = new Error('desktop rejected the request');
  gate.reject(desktopError);
  await assert.rejects(() => outcomePromise, (error) => error === desktopError);
  assert.equal(mirror.size, 0);
  assert.equal(bus.buffer.filter((event) => event.type === 'approval.resolved').length, 1);
});

test('approval mirror caps pending rows, silently dropping the oldest', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 50 });
  const mirror = makeMirror({ bus, cap: 3 });
  /** @type {Array<() => void>} */
  const releases = [];
  for (let index = 0; index < 5; index += 1) {
    const gate = deferred();
    releases.push(gate.resolve);
    void mirror.handle({ ...REQUEST, callId: `call_${index}` }, () => gate.promise);
  }
  const ids = mirror.list().approvals.map((approval) => approval.id);
  assert.deepEqual(ids, ['ap_5', 'ap_4', 'ap_3'], 'newest first, cap 3 kept');
  assert.equal(bus.buffer.filter((event) => event.type === 'approval.resolved').length, 0, 'eviction is silent');
  assert.throws(() => mirror.decide('ap_1', 'allow'), (error) => error instanceof ApiError && error.code === 'not_found');
  for (const release of releases) release('cancelled');
  await flush();
  assert.equal(mirror.size, 0);
});

test('approval mirror failures never change the waterfall outcome', async () => {
  const throwingBus = {
    publish() {
      throw new Error('SSE transport down');
    },
  };
  const resilient = makeMirror({ bus: throwingBus, overrides: { logger: silent } });
  assert.equal(await resilient.handle(REQUEST, async () => 'rejected'), 'rejected');
  assert.equal(resilient.size, 0);

  const badIdMirror = makeMirror({
    overrides: {
      idFactory: () => {
        throw new Error('id failure');
      },
    },
  });
  assert.equal(await badIdMirror.handle(REQUEST, async () => 'allowed-once'), 'allowed-once');
  assert.equal(badIdMirror.size, 0, 'a mirroring failure records nothing');

  const badCommandMirror = makeMirror({
    overrides: {
      resolveCommand: () => {
        throw new Error('tool info unavailable');
      },
    },
  });
  const commandGate = deferred();
  const pending = badCommandMirror.handle(REQUEST, () => commandGate.promise);
  assert.equal(badCommandMirror.list().approvals[0].command, '');
  commandGate.resolve('cancelled');
  assert.equal(await pending, 'cancelled');
});

test('approval mirror respects the injected session visibility filter', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 20 });
  const mirror = makeMirror({ bus, overrides: { isSessionVisible: (id) => id !== 'session-hidden' } });
  const outcome = await mirror.handle({ ...REQUEST, agent: { id: 'session-hidden' } }, async () => 'rejected');
  assert.equal(outcome, 'rejected', 'hidden sessions still delegate unchanged');
  assert.equal(mirror.size, 0);
  assert.equal(bus.buffer.length, 0);
  assert.throws(
    () => mirror.decide('ap_hidden', 'allow'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  const visibleGate = deferred();
  const visible = mirror.handle(REQUEST, () => visibleGate.promise);
  assert.equal(mirror.size, 1);
  visibleGate.resolve('allowed-once');
  assert.equal(await visible, 'allowed-once');
});

test('approval mirror attaches to a waterfall ctx, registers one listener and clears on dispose', async () => {
  const ctx = fakeWaterfallCtx();
  const mirror = makeMirror();
  const dispose = mirror.attach(ctx);
  const handlers = ctx.listenersFor('approval/request');
  assert.equal(handlers.length, 1);

  const gate = deferred();
  const outcomePromise = handlers[0](REQUEST, () => gate.promise);
  assert.equal(mirror.size, 1);
  dispose();
  assert.equal(ctx.listenersFor('approval/request').length, 0);
  assert.equal(mirror.size, 0, 'dispose clears pending rows');
  // Dispose unblocks a waiting agent as `unavailable`; the desktop chain may
  // still settle in the background and its outcome is ignored.
  assert.equal(await outcomePromise, 'unavailable');
  gate.resolve('cancelled');
  await flush();
});

test('mobile allow wins the race, resolves once, and makes later decisions stale', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 20 });
  const mirror = makeMirror({ bus });
  const desktopGate = deferred();
  const outcomePromise = mirror.handle(REQUEST, () => desktopGate.promise);
  assert.equal(mirror.size, 1);

  assert.equal(mirror.decide('ap_1', 'allow'), 'allowed-once');
  assert.equal(await outcomePromise, 'allowed-once', 'mobile answer is returned immediately');
  assert.deepEqual(mirror.list().approvals, []);
  assert.equal(bus.buffer.filter((event) => event.type === 'approval.resolved').length, 1);
  assert.throws(
    () => mirror.decide('ap_1', 'deny'),
    (error) => error instanceof ApiError && error.code === 'not_found' && error.status === 404,
  );

  // The desktop answerer settles later; it must not publish a second resolution.
  desktopGate.resolve('rejected');
  await flush();
  assert.equal(bus.buffer.filter((event) => event.type === 'approval.resolved').length, 1);
});

test('mobile deny maps to rejected', async () => {
  const mirror = makeMirror();
  const gate = deferred();
  const outcomePromise = mirror.handle(REQUEST, () => gate.promise);
  assert.equal(mirror.decide('ap_1', 'deny'), 'rejected');
  assert.equal(await outcomePromise, 'rejected');
  assert.equal(mirror.size, 0);
});

test('desktop answering first wins; a later mobile decision is not_found', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 20 });
  const mirror = makeMirror({ bus });
  const outcome = await mirror.handle(REQUEST, async () => 'rejected');
  assert.equal(outcome, 'rejected');
  assert.equal(mirror.size, 0);
  assert.equal(bus.buffer.filter((event) => event.type === 'approval.resolved').length, 1);
  assert.throws(
    () => mirror.decide('ap_1', 'allow'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
});

test('abort settles as cancelled and blocks later decisions', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 20 });
  const mirror = makeMirror({ bus });
  const controller = new AbortController();
  const desktopGate = deferred();
  const outcomePromise = mirror.handle({ ...REQUEST, signal: controller.signal }, () => desktopGate.promise);
  assert.equal(mirror.size, 1);

  controller.abort();
  assert.equal(await outcomePromise, 'cancelled');
  assert.equal(mirror.size, 0);
  assert.equal(bus.buffer.filter((event) => event.type === 'approval.resolved').length, 1);
  assert.throws(
    () => mirror.decide('ap_1', 'allow'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  desktopGate.resolve('allowed-once');
  await flush();
  assert.equal(bus.buffer.filter((event) => event.type === 'approval.resolved').length, 1);
});

test('a desktop failure after a mobile answer is logged, not rethrown or double-resolved', async () => {
  const collector = warningCollector();
  const bus = new EventBus({ logger: silent, bufferSize: 20 });
  const mirror = makeMirror({ bus, logger: collector.logger });
  const desktopGate = deferred();
  const outcomePromise = mirror.handle(REQUEST, () => desktopGate.promise);
  assert.equal(mirror.decide('ap_1', 'allow'), 'allowed-once');
  assert.equal(await outcomePromise, 'allowed-once');

  desktopGate.reject(new Error('desktop answerer failed late'));
  await flush();
  assert.equal(bus.buffer.filter((event) => event.type === 'approval.resolved').length, 1);
  assert.equal(collector.warnings.some((line) => /failed after mobile resolution/.test(line)), true);
});

test('mobile answering disabled keeps read-only pass-through and rejects decisions', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 20 });
  const mirror = makeMirror({ bus, overrides: { mobileAnswer: false } });
  assert.equal(mirror.list().readOnly, true);

  const gate = deferred();
  const outcomePromise = mirror.handle(REQUEST, () => gate.promise);
  assert.equal(mirror.size, 1);
  assert.throws(
    () => mirror.decide('ap_1', 'allow'),
    (error) => error instanceof ApiError && error.code === 'unsupported' && error.status === 501,
  );

  gate.resolve('rejected');
  assert.equal(await outcomePromise, 'rejected', 'next() outcome is unchanged in read-only mode');
  assert.equal(bus.buffer.filter((event) => event.type === 'approval.resolved').length, 1);
});

test('unknown approvals are not_found and duplicate mobile decisions fail', async () => {
  const mirror = makeMirror();
  assert.throws(
    () => mirror.decide('ap_missing', 'allow'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  const gate = deferred();
  const outcomePromise = mirror.handle(REQUEST, () => gate.promise);
  assert.equal(mirror.decide('ap_1', 'allow'), 'allowed-once');
  assert.throws(
    () => mirror.decide('ap_1', 'allow'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  assert.equal(await outcomePromise, 'allowed-once');
  gate.resolve('rejected');
});

test('invalid decisions are invalid_request in mobile mode', () => {
  const mirror = makeMirror();
  const gate = deferred();
  void mirror.handle(REQUEST, () => gate.promise);
  assert.throws(
    () => mirror.decide('ap_1', 'maybe'),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );
  gate.resolve('cancelled');
});

test('mapApprovalRequest falls back safely and derives title/description/command', async () => {
  const mapped = mapApprovalRequest(
    { agent: { id: 'session-1' }, toolName: 'Pwsh', reason: 'Run the publish script' },
    { id: 'ap_x', createdAt: '2026-11-01T09:00:00Z', command: '' },
  );
  assert.deepEqual(mapped, {
    id: 'ap_x',
    sessionId: 'session-1',
    title: 'Permission needed: Pwsh',
    description: 'Run the publish script',
    command: '',
    createdAt: '2026-11-01T09:00:00Z',
  });

  const generic = mapApprovalRequest({}, { id: 'ap_y', createdAt: '2026-11-01T09:00:00Z', command: 'x' });
  assert.equal(generic.sessionId, '');
  assert.equal(generic.title, 'Permission needed: tool');
  assert.equal(generic.description, 'The host is waiting for approval to run tool.');
  assert.equal(generic.command, 'x');

  // No next() delegate: never claim/throw, just decline to mirror.
  const noNext = new ApprovalMirror({ logger: silent });
  assert.equal(await noNext.handle(REQUEST, undefined), undefined);
});
