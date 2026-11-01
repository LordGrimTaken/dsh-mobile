import test from 'node:test';
import assert from 'node:assert/strict';

import {
  QuestionMirror,
  createQuestionMirror,
  mapQuestionOptions,
  mapQuestionPrompt,
} from '../lib/question-mirror.js';
import { ApiError } from '../lib/errors.js';
import { EventBus } from '../lib/events.js';

const silent = { info() {}, warn() {}, error() {} };
const T = (offset = 0) => Date.UTC(2026, 10, 1, 10, 0, 0) + offset;

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
  return createQuestionMirror({
    bus,
    cap,
    logger,
    now: () => T(1000),
    idFactory: () => `req-${++counter}`,
    ...overrides,
  });
}

const ASK_REQUEST = {
  agent: { id: 'session-question-1' },
  questions: [
    {
      id: 'approach',
      header: 'Approach',
      question: 'Which approach should I take?',
      detail: 'Pick a lane before I continue.',
      options: [{ label: 'Ship now', description: 'Fast path' }, { label: 'Wait for the page' }],
    },
    {
      id: 'confirm',
      question: 'Is the summary correct?',
      options: [{ label: 'Yes' }, { label: 'No' }],
    },
  ],
};

const flush = () => new Promise((resolve) => setTimeout(resolve, 10));
const resolvedEvents = (bus) => bus.buffer.filter((event) => event.type === 'question.resolved');

test('question mirror reports readOnly false by default and true when answering is disabled', () => {
  assert.deepEqual(makeMirror().list(), { questions: [], supported: true, readOnly: false });
  assert.deepEqual(makeMirror({ overrides: { mobileAnswer: false } }).list(), {
    questions: [],
    supported: true,
    readOnly: true,
  });
});

test('question mirror records rows while the desktop answers and forwards desktop outcomes', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 20 });
  const mirror = makeMirror({ bus });
  const desktopGate = deferred();

  const outcomePromise = mirror.handle(ASK_REQUEST, () => desktopGate.promise);
  assert.equal(mirror.list().questions.length, 2);
  assert.equal(mirror.list().readOnly, false);
  const row = mirror.list().questions[0];
  assert.equal(row.id, 'q_req-1:0');
  assert.equal(row.sessionId, 'session-question-1');
  assert.equal(row.prompt, 'Approach: Which approach should I take?\nPick a lane before I continue.');
  assert.deepEqual(row.options, ['Ship now', 'Wait for the page']);
  assert.equal(bus.buffer.filter((event) => event.type === 'question.requested').length, 2);

  const desktopAnswer = { answers: [{ id: 'approach', selected: ['Ship now'] }] };
  desktopGate.resolve(desktopAnswer);
  assert.deepEqual(await outcomePromise, desktopAnswer);
  assert.equal(mirror.size, 0);
  assert.equal(resolvedEvents(bus).length, 2);
});

test('question mirror clears rows when desktop next() rejects (error rethrown unchanged)', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 20 });
  const mirror = makeMirror({ bus });
  const gate = deferred();
  const outcomePromise = mirror.handle(ASK_REQUEST, () => gate.promise);
  assert.equal(mirror.size, 2);

  const desktopError = new Error('desktop cancelled the question');
  gate.reject(desktopError);
  await assert.rejects(() => outcomePromise, (error) => error === desktopError);
  assert.equal(mirror.size, 0);
  assert.equal(resolvedEvents(bus).length, 2);
});

test('question mirror caps pending rows, silently dropping the oldest', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 50 });
  const mirror = makeMirror({ bus, cap: 2 });
  /** @type {Array<() => void>} */
  const releases = [];
  for (let index = 0; index < 2; index += 1) {
    const gate = deferred();
    releases.push(gate.resolve);
    void mirror.handle(
      {
        ...ASK_REQUEST,
        questions: [
          { id: `a${index}`, question: `Question A${index}` },
          { id: `b${index}`, question: `Question B${index}` },
        ],
      },
      () => gate.promise,
    );
  }
  assert.deepEqual(
    mirror.list().questions.map((question) => question.id),
    ['q_req-2:0', 'q_req-2:1'],
    'oldest call rows dropped at the cap',
  );
  assert.throws(() => mirror.answer('q_req-1:0', 'anything'), (error) => error instanceof ApiError && error.code === 'not_found');
  for (const release of releases) release({ answers: [] });
  await flush();
  assert.equal(mirror.size, 0);
});

test('question mirror failures never change the waterfall outcome', async () => {
  const throwingBus = {
    publish() {
      throw new Error('SSE transport down');
    },
  };
  const resilient = makeMirror({ bus: throwingBus });
  assert.deepEqual(await resilient.handle(ASK_REQUEST, async () => ({ answers: [] })), { answers: [] });
  assert.equal(resilient.size, 0);

  const badIdMirror = makeMirror({
    overrides: {
      idFactory: () => {
        throw new Error('id failure');
      },
    },
  });
  const desktopAnswer = { answers: [{ id: 'x', selected: [] }] };
  assert.deepEqual(await badIdMirror.handle(ASK_REQUEST, async () => desktopAnswer), desktopAnswer);
  assert.equal(badIdMirror.size, 0, 'a mirroring failure records nothing');
});

test('question mirror respects the injected session visibility filter and scope attribution', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 20 });
  const mirror = makeMirror({ bus, overrides: { isSessionVisible: (id) => id !== 'session-hidden' } });
  const hiddenOutcome = await mirror.handle(
    { ...ASK_REQUEST, agent: { id: 'session-hidden' } },
    async () => ({ answers: [] }),
  );
  assert.deepEqual(hiddenOutcome, { answers: [] });
  assert.equal(mirror.size, 0);
  assert.equal(bus.buffer.length, 0);

  const scopeGate = deferred();
  const scoped = mirror.handle({ questions: [{ id: 'x', question: 'Scoped?' }] }, () => scopeGate.promise, {
    id: 'session-from-scope',
  });
  assert.equal(mirror.list().questions[0].sessionId, 'session-from-scope');
  scopeGate.resolve({ answers: [] });
  await scoped;
});

test('question mirror attaches to a waterfall ctx and dispose unblocks the batch', async () => {
  const ctx = fakeWaterfallCtx();
  const mirror = makeMirror();
  const dispose = mirror.attach(ctx);
  const handlers = ctx.listenersFor('user-questions/request');
  assert.equal(handlers.length, 1);

  const desktopGate = deferred();
  const outcomePromise = handlers[0](ASK_REQUEST, () => desktopGate.promise);
  assert.equal(mirror.size, 2);
  dispose();
  assert.equal(ctx.listenersFor('user-questions/request').length, 0);
  assert.equal(mirror.size, 0, 'dispose clears pending rows');
  assert.deepEqual(await outcomePromise, { answers: [] }, 'dispose unblocks a waiting agent');
  desktopGate.resolve({ answers: [] });
  await flush();
});

test('mobile answering a single question claims the call and resolves once', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 20 });
  const mirror = makeMirror({ bus });
  const desktopGate = deferred();
  const outcomePromise = mirror.handle(
    { ...ASK_REQUEST, questions: [{ id: 'confirm', question: 'Proceed?', options: [{ label: 'Yes' }, { label: 'No' }] }] },
    () => desktopGate.promise,
  );

  const answered = mirror.answer('q_req-1:0', 'Yes', 0);
  assert.deepEqual(answered, { ok: true, questionId: 'q_req-1:0', answered: 1, total: 1, complete: true });
  assert.deepEqual(await outcomePromise, { answers: [{ id: 'confirm', selected: ['Yes'] }] });
  assert.equal(mirror.size, 0);
  assert.equal(resolvedEvents(bus).length, 1);

  assert.throws(
    () => mirror.answer('q_req-1:0', 'No', 1),
    (error) => error instanceof ApiError && error.code === 'not_found' && error.status === 404,
  );

  // The desktop answerer settles later; no duplicate resolution or event.
  desktopGate.resolve({ answers: [{ id: 'confirm', selected: ['No'] }] });
  await flush();
  assert.equal(resolvedEvents(bus).length, 1);
});

test('multi-question calls resolve per row and only return the batch when complete', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 20 });
  const mirror = makeMirror({ bus });
  const desktopGate = deferred();
  const outcomePromise = mirror.handle(ASK_REQUEST, () => desktopGate.promise);

  const first = mirror.answer('q_req-1:0', 'Ship now', 0);
  assert.deepEqual(first, { ok: true, questionId: 'q_req-1:0', answered: 1, total: 2, complete: false });
  assert.equal(mirror.size, 1);
  assert.deepEqual(mirror.list().questions.map((question) => question.id), ['q_req-1:1']);
  assert.equal(resolvedEvents(bus).length, 1);

  let settled = false;
  void outcomePromise.then(() => {
    settled = true;
  });
  await flush();
  assert.equal(settled, false, 'the call is not returned before every question is answered');

  const second = mirror.answer('q_req-1:1', 'Yes', 0);
  assert.deepEqual(second, { ok: true, questionId: 'q_req-1:1', answered: 2, total: 2, complete: true });
  assert.deepEqual(await outcomePromise, {
    answers: [
      { id: 'approach', selected: ['Ship now'] },
      { id: 'confirm', selected: ['Yes'] },
    ],
  });
  assert.equal(resolvedEvents(bus).length, 2);
  assert.equal(mirror.size, 0);
  desktopGate.resolve({ answers: [] });
  await flush();
  assert.equal(resolvedEvents(bus).length, 2);
});

test('option labels match without optionIndex, case-insensitively; unmatched text is custom', async () => {
  const mirror = makeMirror();
  const desktopGate = deferred();
  const outcomePromise = mirror.handle(
    {
      agent: { id: 'session-question-1' },
      questions: [
        { id: 'label', question: 'Pick one', options: [{ label: 'Ship now' }, { label: 'Wait' }] },
        { id: 'free', question: 'What should I name it?' },
      ],
    },
    () => desktopGate.promise,
  );

  assert.equal(mirror.answer('q_req-1:0', 'wait').complete, false);
  assert.equal(mirror.answer('q_req-1:1', '  Release Candidate  ').complete, true);
  assert.deepEqual(await outcomePromise, {
    answers: [
      { id: 'label', selected: ['Wait'] },
      { id: 'free', selected: [], custom: 'Release Candidate' },
    ],
  });
});

test('desktop answering first wins; later mobile answers are not_found', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 20 });
  const mirror = makeMirror({ bus });
  const desktopAnswer = { answers: [{ id: 'approach', selected: ['Wait for the page'] }] };
  assert.deepEqual(await mirror.handle(ASK_REQUEST, async () => desktopAnswer), desktopAnswer);
  assert.equal(mirror.size, 0);
  assert.equal(resolvedEvents(bus).length, 2);
  assert.throws(
    () => mirror.answer('q_req-1:0', 'Ship now', 0),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
});

test('invalid option indexes and empty free-text answers are invalid_request', async () => {
  const mirror = makeMirror();
  const desktopGate = deferred();
  void mirror.handle(ASK_REQUEST, () => desktopGate.promise);

  assert.throws(
    () => mirror.answer('q_req-1:0', 'Ship now', 5),
    (error) => error instanceof ApiError && error.code === 'invalid_request' && error.status === 400,
  );
  assert.throws(
    () => mirror.answer('q_req-1:0', 'Ship now', -1),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );
  assert.throws(
    () => mirror.answer('q_req-1:0', '   '),
    (error) => error instanceof ApiError && error.code === 'invalid_request',
  );
  assert.throws(
    () => mirror.answer('q_missing:0', 'Yes'),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );
  desktopGate.resolve({ answers: [] });
  await flush();
});

test('abort settles the call as an empty answers batch and blocks later answers', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 20 });
  const mirror = makeMirror({ bus });
  const controller = new AbortController();
  const desktopGate = deferred();
  const outcomePromise = mirror.handle({ ...ASK_REQUEST, signal: controller.signal }, () => desktopGate.promise);
  assert.equal(mirror.size, 2);

  controller.abort();
  assert.deepEqual(await outcomePromise, { answers: [] });
  assert.equal(mirror.size, 0);
  assert.equal(resolvedEvents(bus).length, 2);
  assert.throws(
    () => mirror.answer('q_req-1:0', 'Ship now', 0),
    (error) => error instanceof ApiError && error.code === 'not_found',
  );

  desktopGate.resolve({ answers: [{ id: 'approach', selected: ['Ship now'] }] });
  await flush();
  assert.equal(resolvedEvents(bus).length, 2);
});

test('a desktop failure after mobile completion is logged, not rethrown or double-resolved', async () => {
  const collector = warningCollector();
  const bus = new EventBus({ logger: silent, bufferSize: 20 });
  const mirror = makeMirror({ bus, logger: collector.logger });
  const desktopGate = deferred();
  const outcomePromise = mirror.handle(ASK_REQUEST, () => desktopGate.promise);
  mirror.answer('q_req-1:0', 'Ship now', 0);
  mirror.answer('q_req-1:1', 'Yes', 0);
  assert.deepEqual(await outcomePromise, {
    answers: [
      { id: 'approach', selected: ['Ship now'] },
      { id: 'confirm', selected: ['Yes'] },
    ],
  });

  desktopGate.reject(new Error('desktop answerer failed late'));
  await flush();
  assert.equal(resolvedEvents(bus).length, 2);
  assert.equal(collector.warnings.some((line) => /failed after mobile resolution/.test(line)), true);
});

test('mobile answering disabled keeps read-only pass-through and rejects answers', async () => {
  const bus = new EventBus({ logger: silent, bufferSize: 20 });
  const mirror = makeMirror({ bus, overrides: { mobileAnswer: false } });
  assert.equal(mirror.list().readOnly, true);

  const desktopGate = deferred();
  const outcomePromise = mirror.handle(ASK_REQUEST, () => desktopGate.promise);
  assert.equal(mirror.size, 2);
  assert.throws(
    () => mirror.answer('q_req-1:0', 'Ship now', 0),
    (error) => error instanceof ApiError && error.code === 'unsupported' && error.status === 501,
  );

  const desktopAnswer = { answers: [{ id: 'approach', selected: ['Ship now'] }] };
  desktopGate.resolve(desktopAnswer);
  assert.deepEqual(await outcomePromise, desktopAnswer, 'next() outcome is unchanged in read-only mode');
  assert.equal(resolvedEvents(bus).length, 2);
});

test('an empty questions array is not mirrored (pass-through next unchanged)', async () => {
  const mirror = makeMirror();
  const desktopAnswer = { answers: [] };
  assert.deepEqual(await mirror.handle({ questions: [] }, async () => desktopAnswer), desktopAnswer);
  assert.equal(mirror.size, 0);
});

test('question prompt/options mappers are defensive', async () => {
  assert.equal(mapQuestionPrompt({ question: 'Plain?' }), 'Plain?');
  assert.equal(mapQuestionPrompt({ header: 'H', detail: 'D' }), 'H\nD');
  assert.equal(mapQuestionPrompt({}), 'Question from the host');
  assert.equal(mapQuestionPrompt(null), 'Question from the host');
  assert.deepEqual(mapQuestionOptions({ options: [{ label: ' A ' }, { label: '' }, {}, { label: 'B' }] }), ['A', 'B']);
  assert.deepEqual(mapQuestionOptions({ options: 'nope' }), []);
  assert.deepEqual(mapQuestionOptions(null), []);

  const noNext = new QuestionMirror({ logger: silent });
  assert.equal(await noNext.handle(ASK_REQUEST, undefined), undefined);
});
