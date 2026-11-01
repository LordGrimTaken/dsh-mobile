/**
 * Pure agent-preset roster helpers for the Mobile API.
 *
 * DSH's `agentPresets` service owns the real roster; this module only merges the
 * list/roster shapes, sanitizes display strings, and sorts for the phone. No DSH
 * service is touched here, so the mapping is fully unit-testable.
 */

/** Maximum characters of a preset display name in the response. */
export const PRESET_NAME_MAX_CHARS = 200;
/** Maximum characters of a preset description in the response. */
export const PRESET_DESCRIPTION_MAX_CHARS = 1000;

/**
 * Trim and cap one optional display string.
 * @param {unknown} value
 * @param {number} max
 * @returns {string | undefined}
 */
export function safePresetText(value, max) {
  if (typeof value !== 'string') return undefined;
  const text = value.trim();
  if (text === '') return undefined;
  return text.slice(0, max);
}

/**
 * Map the `agentPresets` list() rows + remoteExportList() roster onto the mobile
 * preset rows.
 *
 * - `list()` contributes order/broken/name/description metadata.
 * - `remoteExportList()` contributes the authoritative default marker.
 * - When only one source exists it is used on its own.
 * - Broken presets are still included (`broken: true`) so the app can disable
 *   them; the diagnostic reason itself is not exposed.
 *
 * Sorted by contiguous `order` (rows without one last), then by id.
 *
 * @param {{
 *   listRows?: ReadonlyArray<Record<string, any>> | null,
 *   roster?: { presets?: ReadonlyArray<Record<string, any>> } | null,
 *   defaultId?: string | null,
 * }} input
 * @returns {{ presets: Array<Record<string, any>>, supported: true, defaultId?: string }}
 */
export function mapPresetRoster({ listRows = null, roster = null, defaultId = null } = {}) {
  const list = Array.isArray(listRows) ? listRows : [];
  const rosterRows = Array.isArray(roster?.presets) ? roster.presets : [];
  const byId = new Map();

  for (const row of list) {
    if (!row || typeof row !== 'object') continue;
    const id = typeof row.id === 'string' ? row.id.trim() : '';
    if (id === '') continue;
    byId.set(id, {
      id,
      name: safePresetText(row.name, PRESET_NAME_MAX_CHARS),
      description: safePresetText(row.description, PRESET_DESCRIPTION_MAX_CHARS),
      order: Number.isSafeInteger(row.order) ? row.order : undefined,
      broken: Boolean(row.broken),
    });
  }
  for (const row of rosterRows) {
    if (!row || typeof row !== 'object') continue;
    const id = typeof row.id === 'string' ? row.id.trim() : '';
    if (id === '') continue;
    const existing = byId.get(id) ?? { id };
    byId.set(id, {
      ...existing,
      id,
      ...(existing.name === undefined ? { name: safePresetText(row.name, PRESET_NAME_MAX_CHARS) } : {}),
      ...(existing.description === undefined
        ? { description: safePresetText(row.description, PRESET_DESCRIPTION_MAX_CHARS) }
        : {}),
      broken: Boolean(existing.broken) || Boolean(row.broken),
      isDefault: row.isDefault === true,
    });
  }

  const defaultText = typeof defaultId === 'string' ? defaultId.trim() : '';
  const rows = [...byId.values()].map((row) => {
    const output = { id: row.id, isDefault: row.isDefault === true || (defaultText !== '' && row.id === defaultText) };
    if (row.name !== undefined) output.name = row.name;
    if (row.description !== undefined) output.description = row.description;
    if (row.broken) output.broken = true;
    return output;
  });
  rows.sort((left, right) => {
    const leftOrder = byId.get(left.id)?.order;
    const rightOrder = byId.get(right.id)?.order;
    const leftValid = Number.isSafeInteger(leftOrder);
    const rightValid = Number.isSafeInteger(rightOrder);
    if (leftValid && rightValid && leftOrder !== rightOrder) return leftOrder - rightOrder;
    if (leftValid !== rightValid) return leftValid ? -1 : 1;
    return left.id.localeCompare(right.id);
  });

  /** @type {{ presets: Array<Record<string, any>>, supported: true, defaultId?: string }} */
  const result = { presets: rows, supported: true };
  if (defaultText !== '') result.defaultId = defaultText;
  return result;
}
