/**
 * Error vocabulary of DSH Mobile API v1 (see docs/mobile-api-v1.md § Errors).
 *
 * Every JSON error the host sends has exactly the shape:
 *   { "error": { "code": "<code>", "message": "<human readable>" } }
 */

/** HTTP status for each documented error code. */
export const ERROR_STATUS = Object.freeze({
  unauthorized: 401,
  forbidden: 403,
  not_found: 404,
  invalid_code: 400,
  rate_limited: 429,
  invalid_request: 400,
  unsupported: 501,
  internal: 500,
});

/** One API-level failure carrying a documented code and its HTTP status. */
export class ApiError extends Error {
  /**
   * @param {keyof typeof ERROR_STATUS} code
   * @param {string} message
   * @param {{ status?: number, details?: unknown, cause?: unknown }} [options]
   */
  constructor(code, message, options = {}) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause });
    this.name = 'ApiError';
    this.code = Object.hasOwn(ERROR_STATUS, code) ? code : 'internal';
    this.status = options.status ?? ERROR_STATUS[this.code];
    if (options.details !== undefined) this.details = options.details;
  }

  /** The contract JSON body for this error. */
  toBody() {
    return { error: { code: this.code, message: this.message } };
  }
}

/** Build an ApiError. */
export function apiError(code, message, options) {
  return new ApiError(code, message, options);
}

/** DSH RemoteError codes that map onto documented mobile API codes. */
const REMOTE_CODE_MAP = Object.freeze({
  'session/not-found': 'not_found',
  'session/conflict': 'invalid_request',
  'session/invalid-time-zone': 'invalid_request',
  'session/attachment-invalid': 'invalid_request',
  'session/queue-item-not-found': 'not_found',
  'session/steer-unavailable': 'invalid_request',
  'session/provider-credentials-unavailable': 'unsupported',
  'session/provider-models-unavailable': 'unsupported',
  'session/model-unavailable': 'unsupported',
  'session/projections-unavailable': 'unsupported',
  'session/migration-required': 'unsupported',
  'agent-preset/conflict': 'invalid_request',
  'gateway/bad-request': 'invalid_request',
  'gateway/cancelled': 'internal',
  'subagent/not-found': 'not_found',
});

/**
 * Normalize any thrown value into an ApiError.
 *
 * Remote failures from DSH carry a `code`; unknown failures become a generic
 * `internal` error so no host detail leaks to the phone. The caller logs the
 * original error.
 *
 * @param {unknown} error
 * @returns {ApiError}
 */
export function toApiError(error) {
  if (error instanceof ApiError) return error;
  const remoteCode = typeof (/** @type {any} */ (error)?.code) === 'string'
    ? /** @type {any} */ (error).code
    : '';
  const mapped = REMOTE_CODE_MAP[remoteCode];
  if (mapped) {
    const message = mapped === 'unsupported'
      ? 'This host capability is not available'
      : (error instanceof Error && error.message) || 'Request failed';
    return new ApiError(mapped, message, { cause: error });
  }
  return new ApiError('internal', 'Internal host error', { cause: error });
}

/** Build the JSON response descriptor for an error. */
export function errorResponse(error) {
  const api = toApiError(error);
  return { status: api.status, body: api.toBody() };
}
