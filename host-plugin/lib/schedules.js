/**
 * Pure schedule contract helpers for the Mobile API.
 *
 * DSH owns the reminder lifecycle (`ctx.get('schedule')`); this module only
 * validates the phone-friendly create body, maps `ScheduleCatalogEntry` rows to
 * the documented mobile shape, and sorts them. Nothing here touches DSH
 * services, so every rule is unit-testable.
 */

import { ApiError } from './errors.js';

/** Reminder title limit (DSH rejects over-long titles as `invalid_prompt`). */
export const SCHEDULE_TITLE_MAX_CHARS = 200;
/** Reminder content ("instruction") limit. */
export const SCHEDULE_INSTRUCTION_MAX_CHARS = 4000;
/** Smallest accepted one-shot delay. */
export const MIN_AFTER_SECONDS = 1;
/** Largest accepted delay / interval (one year). */
export const MAX_AFTER_SECONDS = 31_536_000;
/** DSH's fixed-rate floor (`MIN_EVERY_INTERVAL_SECONDS`). */
export const MIN_EVERY_SECONDS = 300;
/** Largest accepted recurring interval (one year). */
export const MAX_EVERY_SECONDS = 31_536_000;

/** Timing selectors accepted by the mobile create body (exactly one). */
export const SCHEDULE_SELECTORS = Object.freeze([
  'afterSeconds',
  'everySeconds',
  'dailyTime',
  'at',
]);

const DAILY_TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/;

/** @param {unknown} value @param {string} field @param {number} min @param {number} max */
function integerInRange(value, field, min, max) {
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) {
    throw new ApiError('invalid_request', `Field ${field} must be a safe integer`);
  }
  if (value < min || value > max) {
    throw new ApiError('invalid_request', `Field ${field} must be between ${min} and ${max}`);
  }
  return value;
}

/**
 * Validate one `POST /sessions/{id}/schedules` body and translate it to the
 * DSH `ScheduleCreateRequest` keywords.
 *
 * Mobile body: `{title, instruction, afterSeconds? | everySeconds? |
 * dailyTime? | at?}` — exactly one timing selector. `dailyTime` uses the
 * optional `timeZone` (default UTC) because DSH's daily rule requires an IANA
 * zone.
 *
 * @param {Record<string, unknown>} body
 * @returns {{
 *   title: string,
 *   instruction: string,
 *   selector: string,
 *   request: { title: string, prompt: string, after_seconds?: number, every_seconds?: number, daily?: { time: string, time_zone: string }, at?: string },
 * }}
 */
export function parseScheduleCreateBody(body) {
  const record = body && typeof body === 'object' ? body : {};
  const title = typeof record.title === 'string' ? record.title.trim() : '';
  if (title === '') throw new ApiError('invalid_request', 'Field title must be a non-empty string');
  if (title.length > SCHEDULE_TITLE_MAX_CHARS) {
    throw new ApiError('invalid_request', `Field title exceeds ${SCHEDULE_TITLE_MAX_CHARS} characters`);
  }
  const instruction = typeof record.instruction === 'string' ? record.instruction.trim() : '';
  if (instruction === '') {
    throw new ApiError('invalid_request', 'Field instruction must be a non-empty string');
  }
  if (instruction.length > SCHEDULE_INSTRUCTION_MAX_CHARS) {
    throw new ApiError(
      'invalid_request',
      `Field instruction exceeds ${SCHEDULE_INSTRUCTION_MAX_CHARS} characters`,
    );
  }

  const present = SCHEDULE_SELECTORS.filter(
    (key) => record[key] !== undefined && record[key] !== null,
  );
  if (present.length !== 1) {
    throw new ApiError(
      'invalid_request',
      'Exactly one timing selector is required: afterSeconds, everySeconds, dailyTime, or at',
    );
  }
  const selector = present[0];
  /** @type {Record<string, any>} */
  const request = { title, prompt: instruction };
  switch (selector) {
    case 'afterSeconds':
      request.after_seconds = integerInRange(
        record.afterSeconds,
        'afterSeconds',
        MIN_AFTER_SECONDS,
        MAX_AFTER_SECONDS,
      );
      break;
    case 'everySeconds':
      request.every_seconds = integerInRange(
        record.everySeconds,
        'everySeconds',
        MIN_EVERY_SECONDS,
        MAX_EVERY_SECONDS,
      );
      break;
    case 'dailyTime': {
      const dailyTime = record.dailyTime;
      if (typeof dailyTime !== 'string' || !DAILY_TIME_PATTERN.test(dailyTime.trim())) {
        throw new ApiError('invalid_request', 'Field dailyTime must be HH:MM (24-hour)');
      }
      const timeZone = typeof record.timeZone === 'string' ? record.timeZone.trim() : 'UTC';
      if (timeZone === '' || timeZone.length > 100) {
        throw new ApiError('invalid_request', 'Field timeZone must be a short IANA zone name');
      }
      request.daily = { time: dailyTime.trim(), time_zone: timeZone };
      break;
    }
    case 'at': {
      const at = record.at;
      if (typeof at !== 'string' || at.trim() === '' || Number.isNaN(Date.parse(at))) {
        throw new ApiError('invalid_request', 'Field at must be an ISO-8601 timestamp');
      }
      request.at = at.trim();
      break;
    }
    default:
      throw new ApiError('invalid_request', 'Unsupported timing selector');
  }
  return { title, instruction, selector, request };
}

/**
 * Validate one `PATCH /schedules/{id}` body: optional `title`, optional
 * `instruction`, and at most one timing selector with the same limits as
 * create. At least one change is required.
 *
 * DSH's `ScheduleTimingChange` has no `after` variant, so a mobile
 * `afterSeconds` edit is translated to an absolute `{kind:'at'}` target
 * (`now + afterSeconds`); all other selectors keep their DSH shape.
 *
 * @param {Record<string, unknown>} body
 * @returns {{
 *   title?: string,
 *   instruction?: string,
 *   selector?: string,
 *   change?: { kind: string, at?: string, every_seconds?: number, daily?: { time: string, time_zone: string } },
 * }}
 */
export function parseScheduleUpdateBody(body) {
  const record = body && typeof body === 'object' ? body : {};
  /** @type {{ title?: string, instruction?: string, selector?: string, change?: Record<string, any> }} */
  const parsed = {};

  if (record.title !== undefined && record.title !== null) {
    if (typeof record.title !== 'string') {
      throw new ApiError('invalid_request', 'Field title must be a string');
    }
    const title = record.title.trim();
    if (title === '') throw new ApiError('invalid_request', 'Field title must not be empty');
    if (title.length > SCHEDULE_TITLE_MAX_CHARS) {
      throw new ApiError('invalid_request', `Field title exceeds ${SCHEDULE_TITLE_MAX_CHARS} characters`);
    }
    parsed.title = title;
  }
  if (record.instruction !== undefined && record.instruction !== null) {
    if (typeof record.instruction !== 'string') {
      throw new ApiError('invalid_request', 'Field instruction must be a string');
    }
    const instruction = record.instruction.trim();
    if (instruction === '') {
      throw new ApiError('invalid_request', 'Field instruction must not be empty');
    }
    if (instruction.length > SCHEDULE_INSTRUCTION_MAX_CHARS) {
      throw new ApiError(
        'invalid_request',
        `Field instruction exceeds ${SCHEDULE_INSTRUCTION_MAX_CHARS} characters`,
      );
    }
    parsed.instruction = instruction;
  }

  const present = SCHEDULE_SELECTORS.filter(
    (key) => record[key] !== undefined && record[key] !== null,
  );
  if (present.length > 1) {
    throw new ApiError(
      'invalid_request',
      'At most one timing selector may be supplied: afterSeconds, everySeconds, dailyTime, or at',
    );
  }
  if (present.length === 1) {
    const selector = present[0];
    parsed.selector = selector;
    switch (selector) {
      case 'afterSeconds': {
        const afterSeconds = integerInRange(
          record.afterSeconds,
          'afterSeconds',
          MIN_AFTER_SECONDS,
          MAX_AFTER_SECONDS,
        );
        // DSH timing changes have no `after` variant; commit an absolute target.
        parsed.change = {
          kind: 'at',
          at: new Date(Date.now() + afterSeconds * 1000).toISOString(),
        };
        break;
      }
      case 'everySeconds':
        parsed.change = {
          kind: 'every',
          every_seconds: integerInRange(
            record.everySeconds,
            'everySeconds',
            MIN_EVERY_SECONDS,
            MAX_EVERY_SECONDS,
          ),
        };
        break;
      case 'dailyTime': {
        const dailyTime = record.dailyTime;
        if (typeof dailyTime !== 'string' || !DAILY_TIME_PATTERN.test(dailyTime.trim())) {
          throw new ApiError('invalid_request', 'Field dailyTime must be HH:MM (24-hour)');
        }
        const timeZone = typeof record.timeZone === 'string' ? record.timeZone.trim() : 'UTC';
        if (timeZone === '' || timeZone.length > 100) {
          throw new ApiError('invalid_request', 'Field timeZone must be a short IANA zone name');
        }
        parsed.change = { kind: 'daily', daily: { time: dailyTime.trim(), time_zone: timeZone } };
        break;
      }
      case 'at': {
        const at = record.at;
        if (typeof at !== 'string' || at.trim() === '' || Number.isNaN(Date.parse(at))) {
          throw new ApiError('invalid_request', 'Field at must be an ISO-8601 timestamp');
        }
        parsed.change = { kind: 'at', at: at.trim() };
        break;
      }
      default:
        throw new ApiError('invalid_request', 'Unsupported timing selector');
    }
  } else if (record.timeZone !== undefined && record.timeZone !== null) {
    // A time zone only applies to a dailyTime change.
    throw new ApiError('invalid_request', 'Field timeZone requires dailyTime');
  }

  if (parsed.title === undefined && parsed.instruction === undefined && parsed.change === undefined) {
    throw new ApiError('invalid_request', 'At least one of title, instruction, or a timing selector is required');
  }
  return parsed;
}

/**
 * Build the exact `expected: ScheduleRecord` value for a DSH `update` call from
 * a catalog entry. The catalog entry is a record plus `sessionId`/`status`/
 * `lastDelivery`; DSH validates the record union strictly, so only the
 * kind-specific fields are copied.
 *
 * @param {any} entry
 */
export function scheduleRecordForUpdate(entry) {
  /** @type {Record<string, any>} */
  const record = {
    id: String(entry?.id ?? ''),
    kind: String(entry?.kind ?? ''),
    title: String(entry?.title ?? ''),
    prompt: String(entry?.prompt ?? ''),
  };
  switch (record.kind) {
    case 'after':
      record.afterSeconds = Number(entry?.afterSeconds) || 0;
      record.scheduledAt = String(entry?.scheduledAt ?? '');
      break;
    case 'at':
      record.scheduledAt = String(entry?.scheduledAt ?? '');
      break;
    case 'every':
      record.everySeconds = Number(entry?.everySeconds) || 0;
      record.scheduledAt = String(entry?.scheduledAt ?? '');
      break;
    case 'daily':
      record.time = String(entry?.time ?? '');
      record.timeZone = String(entry?.timeZone ?? '');
      record.scheduledAt = String(entry?.scheduledAt ?? '');
      break;
    case 'weekly':
      record.time = String(entry?.time ?? '');
      record.timeZone = String(entry?.timeZone ?? '');
      record.weekdays = Array.isArray(entry?.weekdays) ? [...entry.weekdays] : [];
      record.scheduledAt = String(entry?.scheduledAt ?? '');
      break;
    case 'cron':
      record.expression = String(entry?.expression ?? '');
      record.timeZone = String(entry?.timeZone ?? '');
      record.scheduledAt = String(entry?.scheduledAt ?? '');
      break;
    default:
      record.scheduledAt = String(entry?.scheduledAt ?? '');
      break;
  }
  return record;
}

/** Trim + cap one human string for the contract row. */
function safeText(value, max = 500) {
  return typeof value === 'string' ? value.trim().slice(0, max) : '';
}

/**
 * Human-readable timing summary of one DSH ScheduleRecord.
 * @param {any} record
 */
export function scheduleSummary(record) {
  const kind = safeText(record?.kind, 20);
  let summary;
  switch (kind) {
    case 'after':
      summary = `in ${Number(record?.afterSeconds) || 0}s`;
      break;
    case 'at':
      summary = `at ${safeText(record?.scheduledAt, 40)}`;
      break;
    case 'every':
      summary = `every ${Number(record?.everySeconds) || 0}s`;
      break;
    case 'daily':
      summary = `daily at ${safeText(record?.time, 10)} ${safeText(record?.timeZone, 40)}`;
      break;
    case 'weekly': {
      const weekdays = Array.isArray(record?.weekdays) ? record.weekdays.join(',') : '';
      summary = `weekly on ${weekdays} at ${safeText(record?.time, 10)} ${safeText(record?.timeZone, 40)}`;
      break;
    }
    case 'cron':
      summary = `cron ${safeText(record?.expression, 80)} (${safeText(record?.timeZone, 40)})`;
      break;
    default:
      summary = kind === '' ? 'scheduled' : kind;
  }
  return summary.trim().slice(0, 200);
}

/**
 * Map one DSH ScheduleCatalogEntry (or a create result record) to the mobile
 * schedule row.
 *
 * DSH's public catalog types do not carry creation/update timestamps; the
 * adapter derives them from the owning session's `schedule/change` events when
 * readable and passes `null` otherwise.
 *
 * @param {any} record
 * @param {{
 *   sessionId: string,
 *   status?: 'active' | 'inactive',
 *   sessionTitle?: string,
 *   lastDelivery?: { deliveredAt?: string } | undefined,
 *   createdAt?: string | null,
 *   updatedAt?: string | null,
 * }} context
 */
export function mapScheduleRecord(record, context) {
  const status = context?.status === 'inactive' ? 'inactive' : 'active';
  const scheduledAt = safeText(record?.scheduledAt, 40);
  const row = {
    id: safeText(record?.id, 200),
    sessionId: String(context?.sessionId ?? ''),
    title: safeText(record?.title, SCHEDULE_TITLE_MAX_CHARS),
    instruction: safeText(record?.prompt, SCHEDULE_INSTRUCTION_MAX_CHARS),
    status,
    schedule: {
      kind: safeText(record?.kind, 20),
      summary: scheduleSummary(record),
      nextRunAt: status === 'active' && scheduledAt !== '' ? scheduledAt : null,
      lastRunAt: safeText(context?.lastDelivery?.deliveredAt, 40) || null,
    },
    createdAt: context?.createdAt ?? null,
    updatedAt: context?.updatedAt ?? null,
  };
  const sessionTitle = safeText(context?.sessionTitle, 200);
  if (sessionTitle !== '') row.sessionTitle = sessionTitle;
  return row;
}

/** Stable DSH schedule mutation error codes that mean "the request was bad". */
const SCHEDULE_INPUT_ERROR_CODES = new Set([
  'invalid_prompt',
  'invalid_selector',
  'invalid_rule',
  'invalid_time_zone',
  'not_future',
  'time_out_of_range',
  'frequency_too_high',
  'subagent_session',
]);

/**
 * Translate a DSH schedule failure into a documented mobile error. Unknown
 * failures return `null` so the caller can use the normal error mapping.
 *
 * @param {unknown} error
 * @returns {ApiError | null}
 */
export function scheduleServiceError(error) {
  const code = typeof (/** @type {any} */ (error)?.code) === 'string' ? /** @type {any} */ (error).code : '';
  if (code === 'internal_error' || code === 'corrupt_schedule_log' || code === 'persistence_uncertain') {
    return new ApiError('internal', 'The host schedule service failed', { cause: error });
  }
  if (SCHEDULE_INPUT_ERROR_CODES.has(code)) {
    const rawMessage = /** @type {any} */ (error)?.message;
    const message =
      typeof rawMessage === 'string' && rawMessage.trim() !== ''
        ? rawMessage
        : 'The schedule request was rejected';
    return new ApiError('invalid_request', message, { cause: error, details: { code } });
  }
  return null;
}

/**
 * Sort schedule rows by the next scheduled time (null last), then id.
 * @param {Array<Record<string, any>>} rows
 */
export function sortSchedules(rows) {
  return [...rows].sort((left, right) => {
    const leftAt = left?.schedule?.nextRunAt ? Date.parse(left.schedule.nextRunAt) : Number.NaN;
    const rightAt = right?.schedule?.nextRunAt ? Date.parse(right.schedule.nextRunAt) : Number.NaN;
    const leftValid = Number.isFinite(leftAt);
    const rightValid = Number.isFinite(rightAt);
    if (leftValid && rightValid && leftAt !== rightAt) return leftAt - rightAt;
    if (leftValid !== rightValid) return leftValid ? -1 : 1;
    return String(left?.id ?? '').localeCompare(String(right?.id ?? ''));
  });
}
