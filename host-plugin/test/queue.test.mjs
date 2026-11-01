import test from 'node:test';
import assert from 'node:assert/strict';

import { ApiError } from '../lib/errors.js';
import {
  QUEUE_MAX_ITEMS,
  QUEUE_MAX_TEXT_CHARS,
  mapQueueItems,
  normalizeQueueAction,
} from '../lib/queue.js';

test('normalizeQueueAction builds exact DSH actions and validates text rules', () => {
  assert.deepEqual(normalizeQueueAction({ action: 'edit', text: '  hello  ' }), {
    kind: 'edit',
    content: [{ type: 'text', text: 'hello' }],
  });
  assert.deepEqual(normalizeQueueAction({ action: 'remove' }), { kind: 'remove' });
  assert.deepEqual(normalizeQueueAction({ action: 'steer' }), { kind: 'steer' });

  const bad = (body) => {
    assert.throws(
      () => normalizeQueueAction(body),
      (error) => error instanceof ApiError && error.code === 'invalid_request' && error.status === 400,
    );
  };
  bad(null);
  bad('remove');
  bad([]);
  bad({});
  bad({ action: 'nope' });
  bad({ action: 'edit' });
  bad({ action: 'edit', text: '' });
  bad({ action: 'edit', text: '   ' });
  bad({ action: 'edit', text: 42 });
  bad({ action: 'edit', text: 'x'.repeat(QUEUE_MAX_TEXT_CHARS + 1) });
  bad({ action: 'remove', text: 'nope' });
  bad({ action: 'steer', text: '' });
});

test('mapQueueItems maps nextTurn before nextStep with text, target and attachments', () => {
  const items = mapQueueItems({
    nextTurn: [
      {
        id: 'm_turn_1',
        content: [
          { type: 'text', text: 'first line' },
          { type: 'image', attachment: { attachmentId: 'att_1' } },
          { type: 'text', text: 'second line' },
        ],
        source: { kind: 'user' },
        createdAt: Date.UTC(2026, 9, 30, 9, 40, 0),
      },
      { id: 'm_turn_2', content: [{ type: 'text', text: 'next turn' }], source: { kind: 'user' } },
    ],
    nextStep: [
      {
        id: 'm_step_1',
        content: [
          { type: 'text', text: 'steer me' },
          { type: 'file', attachment: { attachmentId: 'att_2' } },
          { type: 'image', attachment: { attachmentId: 'att_3' } },
        ],
        source: { kind: 'user' },
        time: Date.UTC(2026, 9, 30, 9, 41, 12),
      },
    ],
  });

  assert.deepEqual(items, [
    {
      itemId: 'm_turn_1',
      text: 'first line\nsecond line',
      target: 'next-turn',
      attachmentCount: 1,
      createdAt: '2026-10-30T09:40:00.000Z',
    },
    { itemId: 'm_turn_2', text: 'next turn', target: 'next-turn', attachmentCount: 0 },
    {
      itemId: 'm_step_1',
      text: 'steer me',
      target: 'next-step',
      attachmentCount: 2,
      createdAt: '2026-10-30T09:41:12.000Z',
    },
  ]);

  assert.deepEqual(mapQueueItems(null), []);
  assert.deepEqual(mapQueueItems({ nextTurn: 'not an array' }), []);
  assert.deepEqual(
    mapQueueItems({ nextTurn: [null, { id: '' }, { id: 'm_ok', content: [] }] }),
    [{ itemId: 'm_ok', text: '', target: 'next-turn', attachmentCount: 0 }],
  );
});

test('mapQueueItems caps the returned list', () => {
  const nextTurn = Array.from({ length: QUEUE_MAX_ITEMS + 5 }, (_, index) => ({
    id: `m_${index}`,
    content: [{ type: 'text', text: `item ${index}` }],
    source: { kind: 'user' },
  }));
  const items = mapQueueItems({ nextTurn, nextStep: [{ id: 'm_after', content: [] }] });
  assert.equal(items.length, QUEUE_MAX_ITEMS);
  assert.equal(items.at(-1).itemId, `m_${QUEUE_MAX_ITEMS - 1}`);
});
