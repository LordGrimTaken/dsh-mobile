/**
 * User-question mirror.
 *
 * DSH dispatches `user-questions/request` as a **waterfall**: every listener
 * either returns a complete `{answers}` batch (claiming the call) or delegates
 * with `next()`. This mirror observes every request; with `mobileAnswer`
 * enabled it also offers the phone a **first-whole-answer-wins** batch:
 *
 *   - read-only mode (`mobileAnswer: false`): record one row per question,
 *     publish `question.requested`, call `next()` unchanged and return its
 *     outcome untouched — the desktop stays the only answerer.
 *   - mobile answering (`mobileAnswer: true`, default): record the batch context
 *     (original question ids + option labels), start the desktop chain with
 *     `next()`, and race it against completion of the mobile batch. Desktop
 *     first wins: all rows are removed and later mobile answers fail (`404`).
 *     Mobile completing every question returns `{answers}` immediately and lets
 *     the desktop promise settle in the background (logged on failure).
 *     `request.signal` abort settles the call as `{answers: []}` (DSH has no
 *     cancelled question outcome) and resolves the rows.
 *
 * Every answered row is removed from the pending list and publishes exactly one
 * `question.resolved`; a whole call publishes at most one resolution outcome.
 * Mirroring/racing failures are logged and swallowed so the agent's question
 * path is never affected.
 */

import crypto from 'node:crypto';

import { ApiError } from './errors.js';
import { isoTime } from './mappers.js';

/** Hard bound for pending mirrored question rows (oldest is dropped silently). */
export const DEFAULT_QUESTION_CAP = 32;

const MOBILE_ANSWER_DISABLED_MESSAGE =
  'Questions are read-only on this host: set questions.mobileAnswer=true to answer them from the phone';

/** A promise plus a resolver; one batch completion per desktop call. */
function createDeferred() {
  let resolve;
  const promise = new Promise((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

/** @param {unknown} value */
function text(value) {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : '';
}

/**
 * Human-readable prompt for one DSH question item: header + question + detail,
 * as available.
 *
 * @param {any} item DSH `AskUserQuestionItem`
 */
export function mapQuestionPrompt(item) {
  const header = text(item?.header);
  const question = text(item?.question);
  const detail = text(item?.detail);
  /** @type {string[]} */
  const lines = [];
  if (header !== '' && question !== '') lines.push(`${header}: ${question}`);
  else if (question !== '') lines.push(question);
  else if (header !== '') lines.push(header);
  if (detail !== '') lines.push(detail);
  return lines.length > 0 ? lines.join('\n') : 'Question from the host';
}

/**
 * Option labels of one DSH question item.
 * @param {any} item
 * @returns {string[]}
 */
export function mapQuestionOptions(item) {
  if (!Array.isArray(item?.options)) return [];
  /** @type {string[]} */
  const options = [];
  for (const option of item.options) {
    const label = text(option?.label);
    if (label !== '') options.push(label);
  }
  return options;
}

/** In-memory question mirror: read-only observer or first-whole-answer answerer. */
export class QuestionMirror {
  /**
   * @param {{
   *   bus?: import('./events.js').EventBus | null,
   *   logger?: Pick<Console, 'warn'>,
   *   cap?: number,
   *   mobileAnswer?: boolean,
   *   now?: () => number,
   *   idFactory?: () => string,
   *   isSessionVisible?: (sessionId: string) => boolean,
   * }} [options]
   */
  constructor({
    bus = null,
    logger = console,
    cap = DEFAULT_QUESTION_CAP,
    mobileAnswer = true,
    now = () => Date.now(),
    idFactory = () => crypto.randomUUID(),
    isSessionVisible = () => true,
  } = {}) {
    this.bus = bus;
    this.logger = logger;
    this.cap = Number.isSafeInteger(cap) && cap > 0 ? cap : DEFAULT_QUESTION_CAP;
    /** Only an explicit `false` disables mobile answering (default true). */
    this.mobileAnswer = mobileAnswer !== false;
    this.now = now;
    this.idFactory = idFactory;
    this.isSessionVisible = typeof isSessionVisible === 'function' ? isSessionVisible : () => true;
    /** Pending contract rows: id -> internal row. Insertion order = request order. */
    this.pending = new Map();
    /** Whole-call batches awaiting their mobile answers: batchId -> batch. */
    this.batches = new Map();
  }

  /** Replace the session visibility predicate (internal/hidden filtering). */
  setSessionFilter(predicate) {
    this.isSessionVisible = typeof predicate === 'function' ? predicate : () => true;
  }

  /** Number of currently pending mirrored question rows. */
  get size() {
    return this.pending.size;
  }

  /**
   * Contract list payload for `GET /mobile/v1/questions`.
   *
   * `readOnly` is `false` only when the host allows phone answering.
   *
   * @returns {{ questions: Array<Record<string, any>>, supported: true, readOnly: boolean }}
   */
  list() {
    return {
      questions: [...this.pending.values()].map((row) => ({
        id: row.id,
        sessionId: row.sessionId,
        prompt: row.prompt,
        options: [...row.options],
        createdAt: row.createdAt,
      })),
      supported: true,
      readOnly: !this.mobileAnswer,
    };
  }

  /**
   * Answer one row of a pending question call from the phone.
   *
   * Options resolve from `optionIndex` when given, else from an exact
   * (case-insensitive) option-label match; anything else becomes DSH's
   * free-text `custom`. The whole batch resolves only once every question in
   * the original call has been answered.
   *
   * @param {string} rowId
   * @param {string} answer
   * @param {number} [optionIndex]
   * @param {string} [note] reserved for future use; ignored
   * @returns {{ ok: true, questionId: string, answered: number, total: number, complete: boolean }}
   */
  answer(rowId, answer, optionIndex, note) {
    if (!this.mobileAnswer) throw new ApiError('unsupported', MOBILE_ANSWER_DISABLED_MESSAGE);
    const row = this.pending.get(String(rowId));
    if (!row) {
      throw new ApiError(
        'not_found',
        'This question is no longer pending: it was answered elsewhere, expired, or is unknown',
      );
    }
    const batch = this.batches.get(row.batchId);
    if (!batch || batch.settled) {
      throw new ApiError('not_found', 'This question call is no longer pending');
    }

    /** @type {string[]} */
    let selected = [];
    /** @type {string | undefined} */
    let custom;
    const answerText = typeof answer === 'string' ? answer.trim() : '';
    if (optionIndex !== undefined && optionIndex !== null) {
      if (!Number.isSafeInteger(optionIndex) || optionIndex < 0 || optionIndex >= row.labels.length) {
        throw new ApiError('invalid_request', 'Field optionIndex is out of range for this question');
      }
      selected = [row.labels[optionIndex]];
    } else {
      if (answerText === '') throw new ApiError('invalid_request', 'Field answer must not be empty');
      const exact = row.labels.find((label) => label === answerText);
      const label = exact ?? row.labels.find((candidate) => candidate.toLowerCase() === answerText.toLowerCase());
      if (label !== undefined) selected = [label];
      else custom = answerText;
    }

    // Reserved field from the wire contract; DSH's answer item only carries
    // selected labels and an optional custom text.
    void note;

    const item = {
      id: row.questionId,
      selected,
      ...(custom === undefined ? {} : { custom }),
    };
    this.pending.delete(row.id);
    this.#publish('question.resolved', { questionId: row.id }, row.sessionId);
    batch.answers.set(row.id, item);

    const answered = batch.answers.size;
    const total = batch.total;
    const complete = answered >= total;
    if (complete && !batch.settled) {
      batch.settled = true;
      this.batches.delete(batch.id);
      batch.decision.resolve({
        answers: batch.rows.map((questionRow) => batch.answers.get(questionRow.id)).filter(Boolean),
      });
    }
    return { ok: true, questionId: row.id, answered, total, complete };
  }

  /** Drop all pending rows and unblock waiting agents without events (dispose). */
  clear() {
    for (const batch of this.batches.values()) {
      if (!batch.settled) {
        batch.settled = true;
        try {
          batch.decision.resolve({ answers: [] });
        } catch {
          /* already resolved */
        }
      }
    }
    this.batches.clear();
    this.pending.clear();
  }

  /**
   * Register the pass-through waterfall listener.
   * @param {any} ctx Cordis plugin context
   * @returns {() => void} disposer
   */
  attach(ctx) {
    if (!ctx || typeof ctx.on !== 'function') return () => {};
    const self = this;
    /**
     * Keep the Cordis waterfall `this` (the scoped agent) so requests without a
     * payload `agent` can still be attributed to their session.
     * @param {any} request
     * @param {() => Promise<any>} next
     */
    function listener(request, next) {
      return self.handle(request, next, this);
    }
    let dispose = () => {};
    try {
      const returned = ctx.on('user-questions/request', listener);
      if (typeof returned === 'function') dispose = returned;
    } catch (error) {
      this.logger.warn?.(
        `[dsh-mobile-host] could not register the question mirror listener: ${String(
          /** @type {any} */ (error)?.message ?? error,
        )}`,
      );
      return () => this.clear();
    }
    return () => {
      try {
        dispose();
      } catch {
        /* teardown race */
      }
      this.clear();
    };
  }

  /**
   * Waterfall handler.
   *
   * @param {any} request DSH `AskUserQuestionRequestEvent`
   * @param {() => Promise<any>} next delegate to the remaining (desktop) answerers
   * @param {any} [scope] Cordis `this` (scoped agent) for attribution fallbacks
   * @returns {Promise<any>} the first whole answer (desktop or mobile) or `next()`'s rejection
   */
  async handle(request, next, scope) {
    if (typeof next !== 'function') return undefined;
    /** @type {any} */
    let batch = null;
    try {
      batch = this.#record(request, scope);
    } catch (error) {
      this.logger.warn?.(
        `[dsh-mobile-host] question mirroring failed (agent path unaffected): ${String(
          /** @type {any} */ (error)?.message ?? error,
        )}`,
      );
      batch = null;
    }
    if (!batch) {
      // Hidden/empty request: pure delegation, exactly like read-only mode.
      try {
        return await next();
      } catch (error) {
        throw error;
      }
    }
    if (!this.mobileAnswer) return this.#handleReadOnly(batch, next);
    return this.#handleMobile(batch, request, next);
  }

  /**
   * Read-only mode: pass through unchanged and remove rows when `next()` settles.
   * @param {any} batch
   * @param {() => Promise<any>} next
   */
  async #handleReadOnly(batch, next) {
    try {
      return await next();
    } finally {
      this.#settleBatch(batch);
    }
  }

  /**
   * Mobile-answering mode: race the whole mobile batch against the desktop
   * chain (`next()`), first whole answer wins.
   *
   * @param {any} batch
   * @param {any} request
   * @param {() => Promise<any>} next
   */
  async #handleMobile(batch, request, next) {
    const abortBatch = () => {
      if (batch.settled) return;
      batch.settled = true;
      this.batches.delete(batch.id);
      this.#removeRows(batch);
      batch.decision.resolve({ answers: [] });
    };
    if (request?.signal?.aborted === true) {
      abortBatch();
      return { answers: [] };
    }
    const onAbort = () => abortBatch();
    try {
      request?.signal?.addEventListener?.('abort', onAbort, { once: true });
    } catch {
      /* non-standard signal */
    }

    // The desktop chain always starts so the desktop remains an answerer. Its
    // rejection is normalized so a mobile-first win cannot leak it as an
    // unhandled rejection; the race rethrows it when desktop answers first.
    const desktop = Promise.resolve()
      .then(() => next())
      .then(
        (outcome) => ({ ok: true, outcome }),
        (error) => ({ ok: false, error }),
      );

    try {
      return await Promise.race([
        desktop.then((result) => {
          if (result.ok) {
            if (!batch.settled) {
              batch.settled = true;
              this.batches.delete(batch.id);
              this.#removeRows(batch);
            }
            return result.outcome;
          }
          if (batch.settled) {
            // Mobile already completed (or the call aborted): log only.
            this.logger.warn?.(
              `[dsh-mobile-host] desktop question answerer failed after mobile resolution: ${String(
                /** @type {any} */ (result.error)?.message ?? result.error,
              )}`,
            );
            return undefined;
          }
          batch.settled = true;
          this.batches.delete(batch.id);
          this.#removeRows(batch);
          throw result.error;
        }),
        batch.decision.promise,
      ]);
    } finally {
      try {
        request?.signal?.removeEventListener?.('abort', onAbort);
      } catch {
        /* non-standard signal */
      }
    }
  }

  /**
   * Record one row per DSH question item with its whole-call context and publish
   * `question.requested` for each.
   *
   * @param {any} request
   * @param {any} [scope]
   * @returns {any | null} the call batch, or null when filtered/empty
   */
  #record(request, scope) {
    const sessionId = request?.agent?.id
      ? String(request.agent.id)
      : scope?.id
        ? String(scope.id)
        : scope?.agent?.id
          ? String(scope.agent.id)
          : '';
    let visible = true;
    try {
      visible = this.isSessionVisible(sessionId) !== false;
    } catch {
      visible = true;
    }
    if (!visible) return null;

    const items = Array.isArray(request?.questions) ? request.questions : [];
    if (items.length === 0) return null;

    const requestUuid = String(this.idFactory());
    const createdAt = isoTime(this.now());
    const batch = {
      id: `batch_${requestUuid}`,
      sessionId,
      total: items.length,
      rows: [],
      answers: new Map(),
      decision: createDeferred(),
      settled: false,
    };
    this.batches.set(batch.id, batch);

    items.forEach((item, index) => {
      const labels = mapQuestionOptions(item);
      const row = {
        id: `q_${requestUuid}:${index}`,
        batchId: batch.id,
        questionId: text(item?.id) !== '' ? text(item.id) : String(index),
        index,
        labels,
        // Contract fields (the phone never sees the batch context).
        sessionId,
        prompt: mapQuestionPrompt(item),
        options: [...labels],
        createdAt,
      };
      batch.rows.push(row);
      this.pending.set(row.id, row);
      this.#publish('question.requested', {
        id: row.id,
        sessionId: row.sessionId,
        prompt: row.prompt,
        options: [...row.options],
        createdAt: row.createdAt,
      }, sessionId);
    });

    // Bound the mirror: the oldest pending rows are dropped silently. A dropped
    // row can no longer be answered from the phone (the whole call still falls
    // back to the desktop answerer).
    while (this.pending.size > this.cap) {
      const oldestId = this.pending.keys().next().value;
      if (oldestId === undefined) break;
      this.pending.delete(oldestId);
    }
    return batch;
  }

  /** Remove every still-pending row of a settled batch and publish resolved. */
  #settleBatch(batch) {
    if (!batch || batch.settled) return;
    batch.settled = true;
    this.batches.delete(batch.id);
    this.#removeRows(batch);
  }

  /** Remove the rows that are still pending (each publishes resolved once). */
  #removeRows(batch) {
    for (const row of batch.rows) {
      if (!this.pending.delete(row.id)) continue; // already answered or evicted
      this.#publish('question.resolved', { questionId: row.id }, row.sessionId);
    }
  }

  /** Best-effort SSE publish; a transport problem must not affect the agent. */
  #publish(type, data, sessionId) {
    if (!this.bus || typeof this.bus.publish !== 'function') return;
    try {
      this.bus.publish(type, data, sessionId ? { sessionId } : {});
    } catch (error) {
      this.logger.warn?.(
        `[dsh-mobile-host] question mirror SSE publish failed: ${String(
          /** @type {any} */ (error)?.message ?? error,
        )}`,
      );
    }
  }
}

/** Create one question mirror. */
export function createQuestionMirror(options = {}) {
  return new QuestionMirror(options);
}
