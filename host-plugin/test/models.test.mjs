import test from 'node:test';
import assert from 'node:assert/strict';

import { ApiError } from '../lib/errors.js';
import {
  MODEL_FAILURE_MESSAGE,
  MODEL_NAME_MAX_CHARS,
  MODELS_MAX_EFFORTS,
  MODELS_MAX_FAILURES,
  MODELS_MAX_GROUPS,
  MODELS_MAX_MODELS_PER_GROUP,
  foldModelSelection,
  mapModelCatalog,
  normalizeModelSelection,
  publicModelSelection,
} from '../lib/models.js';

test('normalizeModelSelection validates provider/model and optional reasoningEffort', () => {
  assert.deepEqual(normalizeModelSelection({ provider: ' deepseek ', model: ' chat ' }), {
    provider: 'deepseek',
    model: 'chat',
  });
  assert.deepEqual(
    normalizeModelSelection({ provider: 'p', model: 'm', reasoningEffort: ' high ' }),
    { provider: 'p', model: 'm', reasoningEffort: 'high' },
  );

  const bad = (value) => {
    assert.throws(
      () => normalizeModelSelection(value),
      (error) => error instanceof ApiError && error.code === 'invalid_request' && error.status === 400,
    );
  };
  bad(null);
  bad('model');
  bad([]);
  bad({});
  bad({ provider: 'p' });
  bad({ model: 'm' });
  bad({ provider: '', model: 'm' });
  bad({ provider: 'p', model: '' });
  bad({ provider: 'p', model: 'm', reasoningEffort: 42 });
  bad({ provider: 'p', model: 'm', reasoningEffort: '' });
});

test('publicModelSelection drops junk and returns null when empty', () => {
  assert.deepEqual(publicModelSelection({ provider: 'p', model: 'm' }), { provider: 'p', model: 'm' });
  assert.equal(publicModelSelection({}), null);
  assert.equal(publicModelSelection(null), null);
  assert.equal(publicModelSelection('x'), null);
});

test('mapModelCatalog sanitizes, caps groups/models/efforts/failures and reports truncation', () => {
  const groups = [];
  for (let index = 0; index < MODELS_MAX_GROUPS + 5; index += 1) {
    const models = [];
    for (let modelIndex = 0; modelIndex < MODELS_MAX_MODELS_PER_GROUP + 2; modelIndex += 1) {
      models.push({
        id: `model-${index}-${modelIndex}`,
        name: `Model ${index}/${modelIndex}`,
        description: 'd'.repeat(2000),
        apiKey: 'sk-secret-should-not-leak',
        reasoning: {
          defaultEffort: 'high',
          efforts: Array.from({ length: MODELS_MAX_EFFORTS + 3 }, (_, effortIndex) => ({
            id: `effort-${effortIndex}`,
            name: `Effort ${effortIndex}`,
            secret: 'sk-secret-should-not-leak',
          })),
        },
      });
    }
    groups.push({ id: `provider-${index}`, name: `Provider ${index}`, models });
  }
  const failures = Array.from({ length: MODELS_MAX_FAILURES + 2 }, (_, index) => ({
    id: `failure-${index}`,
    name: `Failure ${index}`,
    message: 'provider unavailable',
    apiKey: 'sk-secret-should-not-leak',
  }));

  const mapped = mapModelCatalog({
    default: { provider: 'deepseek', model: 'deepseek-chat' },
    routableProviders: ['deepseek'],
    groups,
    failures,
    secret: 'sk-secret-should-not-leak',
  });

  assert.equal(mapped.supported, true);
  assert.equal(mapped.truncated, true);
  assert.deepEqual(mapped.default, { provider: 'deepseek', model: 'deepseek-chat' });
  assert.equal(mapped.groups.length, MODELS_MAX_GROUPS);
  assert.equal(mapped.groups[0].models.length, MODELS_MAX_MODELS_PER_GROUP);
  assert.equal(mapped.groups[0].models[0].reasoning.efforts.length, MODELS_MAX_EFFORTS);
  assert.equal(mapped.groups[0].models[0].description.length, 1000);
  assert.equal(mapped.groups[0].name.length <= MODEL_NAME_MAX_CHARS, true);
  assert.equal(mapped.failures.length, MODELS_MAX_FAILURES);
  assert.equal(mapped.failures[0].message, MODEL_FAILURE_MESSAGE);
  const serialized = JSON.stringify(mapped);
  assert.equal(serialized.includes('sk-secret'), false, 'unknown provider fields are not exposed');
  assert.equal(serialized.includes('routableProviders'), false);
  assert.equal('secret' in mapped, false);
});

test('mapModelCatalog handles missing/invalid values without throwing', () => {
  assert.deepEqual(mapModelCatalog(null), {
    supported: true,
    default: null,
    groups: [],
    failures: [],
    truncated: false,
  });
  const mapped = mapModelCatalog({
    default: { provider: '', model: '' },
    groups: [{ id: '', models: [] }, { id: 'p', name: '', models: [{ id: 'm', name: '' }] }],
    failures: [{ id: 'bad' }],
  });
  assert.equal(mapped.default, null);
  assert.deepEqual(mapped.groups, [{ id: 'p', name: 'p', models: [{ id: 'm', name: 'm' }] }]);
  assert.deepEqual(mapped.failures, [{ id: 'bad', name: 'bad', message: MODEL_FAILURE_MESSAGE }]);
});

test('mapModelCatalog redacts credential-shaped provider failure data', () => {
  const mapped = mapModelCatalog({
    groups: [],
    failures: [
      {
        id: 'provider-sk-SECRET12345678',
        name: 'Bearer tok_abcdefghijkl',
        message: 'https://user:pass@internal.example/v1 failed: Authorization: Bearer sk-live-abcdefgh',
      },
      { id: 'other', name: 'Other', message: 'sk-1234567890abcdef' },
    ],
  });
  const serialized = JSON.stringify(mapped.failures);
  assert.equal(serialized.includes('sk-'), false, 'key-shaped strings are redacted');
  assert.equal(serialized.includes('Bearer'), false, 'bearer credentials are redacted');
  assert.equal(serialized.includes('tok_'), false, 'tokens are redacted');
  assert.equal(serialized.includes('user:pass'), false, 'URL userinfo is redacted');
  assert.equal(mapped.failures.length, 2);
  for (const failure of mapped.failures) {
    assert.equal(failure.message, MODEL_FAILURE_MESSAGE);
  }
});

test('foldModelSelection uses the latest model/selection or request header config', () => {
  assert.equal(foldModelSelection([]), null);
  assert.equal(foldModelSelection(null), null);

  const fromSelection = foldModelSelection([
    { type: 'model/selection', data: { provider: 'p1', model: 'm1' } },
    { type: 'request/header', data: { header: { config: { provider: 'p2', model: 'm2' } } } },
    { type: 'model/selection', data: { provider: 'p3', model: 'm3', reasoningEffort: 'high' } },
  ]);
  assert.deepEqual(fromSelection, { provider: 'p3', model: 'm3', reasoningEffort: 'high' });

  const fromHeader = foldModelSelection([
    { type: 'request/header', data: { header: { config: { provider: 'deepseek', model: 'deepseek-chat' } } } },
    { type: 'tool/result', data: {} },
  ]);
  assert.deepEqual(fromHeader, { provider: 'deepseek', model: 'deepseek-chat' });

  const invalidIgnored = foldModelSelection([
    { type: 'model/selection', data: { provider: '', model: '' } },
    { type: 'request/header', data: { header: {} } },
  ]);
  assert.equal(invalidIgnored, null);
});

test('foldModelSelection matches the installed DSH event shapes', () => {
  // `model/selection` data is ModelSelection {provider, model, reasoningEffort?}.
  assert.deepEqual(
    foldModelSelection([
      { type: 'model/selection', seq: 4, time: 1, data: { provider: 'deepseek', model: 'deepseek-reasoner', reasoningEffort: 'high' } },
    ]),
    { provider: 'deepseek', model: 'deepseek-reasoner', reasoningEffort: 'high' },
  );
  // `request/header` data.header.config is LlmCallConfig ({provider, model, reasoningEffort?}
  // plus control fields); the adapter must fold the selection fields only.
  assert.deepEqual(
    foldModelSelection([
      {
        type: 'request/header',
        seq: 5,
        time: 2,
        data: {
          header: {
            config: { provider: 'deepseek', model: 'deepseek-chat', reasoningEffort: 'medium', temperature: 0.2 },
            adapterDefaults: { reasoningEffort: true },
            tools: [],
          },
          reason: 'change',
          startsSeries: true,
        },
      },
    ]),
    { provider: 'deepseek', model: 'deepseek-chat', reasoningEffort: 'medium' },
  );
});
