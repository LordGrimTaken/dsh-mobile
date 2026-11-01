import test from 'node:test';
import assert from 'node:assert/strict';

import {
  PRESET_DESCRIPTION_MAX_CHARS,
  PRESET_NAME_MAX_CHARS,
  mapPresetRoster,
  safePresetText,
} from '../lib/presets.js';

test('safePresetText trims and caps optional display strings', () => {
  assert.equal(safePresetText('  Default  ', 200), 'Default');
  assert.equal(safePresetText('', 200), undefined);
  assert.equal(safePresetText('   ', 200), undefined);
  assert.equal(safePresetText(42, 200), undefined);
  assert.equal(safePresetText(undefined, 200), undefined);
  assert.equal(safePresetText('x'.repeat(PRESET_NAME_MAX_CHARS + 50), PRESET_NAME_MAX_CHARS).length, PRESET_NAME_MAX_CHARS);
  assert.equal(
    safePresetText('d'.repeat(PRESET_DESCRIPTION_MAX_CHARS + 50), PRESET_DESCRIPTION_MAX_CHARS).length,
    PRESET_DESCRIPTION_MAX_CHARS,
  );
});

test('mapPresetRoster merges list metadata with the roster default marker', () => {
  const result = mapPresetRoster({
    listRows: [
      { id: 'focused', name: ' Focused ', order: 2, broken: 'activation failed' },
      { id: 'default', name: 'Default', description: 'General work', order: 0 },
      { id: 'research', name: 'Research', order: 1 },
      { id: 'zzz' },
      { id: 'aaa' },
      { id: '' },
      null,
    ],
    roster: {
      presets: [
        { id: 'default', name: 'Default', isDefault: true },
        { id: 'focused', name: 'Focused', broken: 'activation failed', isDefault: false },
        { id: 'research', name: 'Research', isDefault: false },
      ],
    },
    defaultId: 'default',
  });

  assert.deepEqual(result, {
    presets: [
      { id: 'default', name: 'Default', description: 'General work', isDefault: true },
      { id: 'research', name: 'Research', isDefault: false },
      { id: 'focused', name: 'Focused', isDefault: false, broken: true },
      { id: 'aaa', isDefault: false },
      { id: 'zzz', isDefault: false },
    ],
    supported: true,
    defaultId: 'default',
  });
});

test('mapPresetRoster falls back to one source and the defaultId getter', () => {
  const listOnly = mapPresetRoster({
    listRows: [
      { id: 'second', name: 'Second', order: 1 },
      { id: 'first', name: 'First', order: 0 },
    ],
    roster: null,
    defaultId: 'first',
  });
  assert.deepEqual(listOnly, {
    presets: [
      { id: 'first', name: 'First', isDefault: true },
      { id: 'second', name: 'Second', isDefault: false },
    ],
    supported: true,
    defaultId: 'first',
  });

  const rosterOnly = mapPresetRoster({
    listRows: null,
    roster: { presets: [{ id: 'b', name: 'B', isDefault: false }, { id: 'a', name: 'A', isDefault: true }] },
    defaultId: null,
  });
  assert.deepEqual(rosterOnly.presets.map((preset) => preset.id), ['a', 'b']);
  assert.equal(rosterOnly.presets[0].isDefault, true);
  assert.equal('defaultId' in rosterOnly, false);

  const empty = mapPresetRoster({});
  assert.deepEqual(empty, { presets: [], supported: true });
});

test('mapPresetRoster caps display strings and never exposes broken diagnostics', () => {
  const result = mapPresetRoster({
    listRows: [
      {
        id: 'broken',
        name: 'n'.repeat(PRESET_NAME_MAX_CHARS + 20),
        description: 'd'.repeat(PRESET_DESCRIPTION_MAX_CHARS + 20),
        broken: 'C:\\internal\\path\\preset.yml failed to load',
      },
    ],
    roster: null,
    defaultId: null,
  });
  const row = result.presets[0];
  assert.equal(row.name.length, PRESET_NAME_MAX_CHARS);
  assert.equal(row.description.length, PRESET_DESCRIPTION_MAX_CHARS);
  assert.equal(row.broken, true);
  assert.equal(JSON.stringify(row).includes('C:\\\\internal'), false, 'diagnostics are not echoed');
});
