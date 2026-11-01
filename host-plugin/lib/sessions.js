/**
 * Pure session-management contract helpers for the Mobile API.
 *
 * DSH owns session rename/archive; this module only normalizes phone input so
 * the router and adapter share one validation rule.
 */

import { ApiError } from './errors.js';

/** DSH/mobile title limit for session rename. */
export const SESSION_TITLE_MAX_CHARS = 200;

/**
 * Normalize one requested session title: a trimmed non-empty string of at most
 * {@link SESSION_TITLE_MAX_CHARS} characters.
 *
 * @param {unknown} value
 * @returns {string}
 */
export function normalizeSessionTitle(value) {
  if (typeof value !== 'string') throw new ApiError('invalid_request', 'Field title must be a string');
  const title = value.trim();
  if (title === '') throw new ApiError('invalid_request', 'Field title must not be empty');
  if (title.length > SESSION_TITLE_MAX_CHARS) {
    throw new ApiError('invalid_request', `Field title exceeds ${SESSION_TITLE_MAX_CHARS} characters`);
  }
  return title;
}
