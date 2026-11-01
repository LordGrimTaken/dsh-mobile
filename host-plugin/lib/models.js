/**
 * Pure model-catalog and model-selection helpers for the Mobile API.
 *
 * DSH's `sessionController` owns the real catalog and selection writes; this
 * module only validates/sanitizes/limits what the phone sees and folds a
 * session's durable selection without activating it.
 */

import { ApiError } from './errors.js';

/** Maximum provider groups returned by GET /models. */
export const MODELS_MAX_GROUPS = 50;
/** Maximum models returned per provider group. */
export const MODELS_MAX_MODELS_PER_GROUP = 100;
/** Maximum reasoning efforts returned per model. */
export const MODELS_MAX_EFFORTS = 20;
/** Maximum isolated provider failures returned. */
export const MODELS_MAX_FAILURES = 50;
/** Maximum characters of a provider/model/effort id. */
export const MODEL_ID_MAX_CHARS = 200;
/** Maximum characters of a display name. */
export const MODEL_NAME_MAX_CHARS = 200;
/** Maximum characters of a description. */
export const MODEL_DESCRIPTION_MAX_CHARS = 1000;
/** Safe, generic provider-failure text (raw provider errors are never forwarded). */
export const MODEL_FAILURE_MESSAGE = 'Provider unavailable';

/** Redact credential-looking fragments before they can reach the phone. */
function redactCredentialText(text) {
  return text
    .replace(/\bBearer\s+[A-Za-z0-9._~+/=-]+/gi, '[redacted]')
    .replace(/\bsk-[A-Za-z0-9_-]{8,}/g, '[redacted]')
    .replace(/https?:\/\/[^\s/@:]+:[^\s/@]+@/gi, 'https://[redacted]@');
}

/** @param {unknown} value */
function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/**
 * Trim/collapse one display string and cap it. Returns undefined when empty.
 * @param {unknown} value
 * @param {number} max
 */
function cleanText(value, max) {
  if (typeof value !== 'string') return undefined;
  const text = value.replace(/\s+/g, ' ').trim();
  if (text === '') return undefined;
  return text.slice(0, max);
}

/** @param {unknown} value */
function cleanId(value) {
  const id = cleanText(value, MODEL_ID_MAX_CHARS);
  return id === undefined ? '' : id;
}

/**
 * Normalize one `{provider, model, reasoningEffort?}` selection.
 *
 * @param {unknown} value
 * @param {{ requireFields?: boolean }} [options]
 * @returns {{ provider: string, model: string, reasoningEffort?: string }}
 */
export function normalizeModelSelection(value, { requireFields = true } = {}) {
  if (!isObject(value)) {
    throw new ApiError('invalid_request', 'Model selection must be an object');
  }
  const provider = cleanId(value.provider);
  const model = cleanId(value.model);
  if (requireFields && provider === '') {
    throw new ApiError('invalid_request', 'Field provider is required');
  }
  if (requireFields && model === '') {
    throw new ApiError('invalid_request', 'Field model is required');
  }
  const reasoningEffort = value.reasoningEffort === undefined
    ? undefined
    : cleanText(value.reasoningEffort, MODEL_ID_MAX_CHARS);
  if (value.reasoningEffort !== undefined && reasoningEffort === undefined) {
    throw new ApiError('invalid_request', 'Field reasoningEffort must be a non-empty string');
  }
  const selection = { provider, model };
  if (reasoningEffort !== undefined) selection.reasoningEffort = reasoningEffort;
  return selection;
}

/**
 * Sanitize one selection for output (drops unknown fields, caps strings).
 * @param {unknown} value
 * @returns {{ provider: string, model: string, reasoningEffort?: string } | null}
 */
export function publicModelSelection(value) {
  try {
    const selection = normalizeModelSelection(value, { requireFields: false });
    if (selection.provider === '' && selection.model === '') return null;
    return selection;
  } catch {
    return null;
  }
}

/**
 * Map DSH's `modelCatalog()` value onto the phone contract, capping every list.
 * Only documented fields are copied (never provider credentials or extra
 * internals), strings are trimmed/capped, and `truncated` reports any cap hit.
 *
 * @param {unknown} catalog
 * @returns {{
 *   supported: true,
 *   default: { provider: string, model: string, reasoningEffort?: string } | null,
 *   groups: Array<{ id: string, name: string, models: Array<Record<string, any>> }>,
 *   failures: Array<{ id: string, name: string, message: string }>,
 *   truncated: boolean,
 * }}
 */
export function mapModelCatalog(catalog) {
  const raw = isObject(catalog) ? catalog : {};
  const rawGroups = Array.isArray(raw.groups) ? raw.groups : [];
  const rawFailures = Array.isArray(raw.failures) ? raw.failures : [];
  let truncated = rawGroups.length > MODELS_MAX_GROUPS || rawFailures.length > MODELS_MAX_FAILURES;

  const groups = [];
  for (const group of rawGroups.slice(0, MODELS_MAX_GROUPS)) {
    if (!isObject(group)) continue;
    const id = cleanId(group.id);
    if (id === '') continue;
    const rawModels = Array.isArray(group.models) ? group.models : [];
    if (rawModels.length > MODELS_MAX_MODELS_PER_GROUP) truncated = true;
    const models = [];
    for (const model of rawModels.slice(0, MODELS_MAX_MODELS_PER_GROUP)) {
      if (!isObject(model)) continue;
      const modelId = cleanId(model.id);
      if (modelId === '') continue;
      const name = cleanText(model.name, MODEL_NAME_MAX_CHARS) ?? modelId;
      const description = cleanText(model.description, MODEL_DESCRIPTION_MAX_CHARS);
      /** @type {Record<string, any>} */
      const row = { id: modelId, name };
      if (description !== undefined) row.description = description;
      if (isObject(model.reasoning)) {
        const rawEfforts = Array.isArray(model.reasoning.efforts) ? model.reasoning.efforts : [];
        if (rawEfforts.length > MODELS_MAX_EFFORTS) truncated = true;
        const efforts = [];
        for (const effort of rawEfforts.slice(0, MODELS_MAX_EFFORTS)) {
          if (!isObject(effort)) continue;
          const effortId = cleanId(effort.id);
          if (effortId === '') continue;
          const effortName = cleanText(effort.name, MODEL_NAME_MAX_CHARS) ?? effortId;
          const effortDescription = cleanText(effort.description, MODEL_DESCRIPTION_MAX_CHARS);
          efforts.push({
            id: effortId,
            name: effortName,
            ...(effortDescription === undefined ? {} : { description: effortDescription }),
          });
        }
        const defaultEffort = cleanText(model.reasoning.defaultEffort, MODEL_ID_MAX_CHARS);
        row.reasoning = {
          efforts,
          ...(defaultEffort === undefined ? {} : { defaultEffort }),
        };
      }
      models.push(row);
    }
    groups.push({ id, name: cleanText(group.name, MODEL_NAME_MAX_CHARS) ?? id, models });
  }

  const failures = [];
  for (const failure of rawFailures.slice(0, MODELS_MAX_FAILURES)) {
    if (!isObject(failure)) continue;
    const rawId = cleanText(failure.id, MODEL_ID_MAX_CHARS);
    if (rawId === undefined) continue;
    const id = redactCredentialText(rawId).trim().slice(0, MODEL_ID_MAX_CHARS);
    if (id === '') continue;
    const name = redactCredentialText(cleanText(failure.name, MODEL_NAME_MAX_CHARS) ?? rawId)
      .trim()
      .slice(0, MODEL_NAME_MAX_CHARS);
    failures.push({
      id,
      name: name === '' ? id : name,
      // Provider error text can contain credential/header/URL fragments: never
      // forward it. The provider identity is enough for the phone to show which
      // provider is unavailable.
      message: MODEL_FAILURE_MESSAGE,
    });
  }

  return {
    supported: true,
    default: publicModelSelection(raw.default),
    groups,
    failures,
    truncated,
  };
}

/**
 * Fold the durable selection from a session log without activating it.
 *
 * The latest `model/selection` event and the latest `request/header` config both
 * carry a selection; the later event wins. Returns null when the log has none
 * (caller then falls back to the host default with `source:"default"`).
 *
 * @param {ReadonlyArray<any>} events
 * @returns {{ provider: string, model: string, reasoningEffort?: string } | null}
 */
export function foldModelSelection(events) {
  /** @type {{ provider: string, model: string, reasoningEffort?: string } | null} */
  let selection = null;
  for (const event of events ?? []) {
    if (!event || typeof event !== 'object') continue;
    if (event.type === 'model/selection') {
      const candidate = publicModelSelection(event.data);
      if (candidate !== null) selection = candidate;
    } else if (event.type === 'request/header') {
      const config = event.data?.header?.config;
      const candidate = publicModelSelection(config);
      if (candidate !== null) selection = candidate;
    }
  }
  return selection;
}
