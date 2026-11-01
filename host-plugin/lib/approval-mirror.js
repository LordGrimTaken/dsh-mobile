/**
 * Approval mirror.
 *
 * DSH dispatches `approval/request` as a **waterfall**: every listener either
 * returns an outcome (claiming the request) or delegates with `next()`. This
 * mirror observes every request; with `mobileAnswer` enabled it also offers the
 * phone a first-answer-wins decision:
 *
 *   - read-only mode (`mobileAnswer: false`): record, publish
 *     `approval.requested`, call `next()` unchanged and return its outcome
 *     untouched — the desktop stays the only answerer.
 *   - mobile answering (`mobileAnswer: true`, default): record a deferred mobile
 *     decision, start the desktop chain with `next()`, and return whichever
 *     settles first. A desktop-first answer makes later mobile decisions fail
 *     (`404`); a mobile-first answer claims the request immediately and the
 *     desktop promise is left to settle in the background (logged on failure).
 *     `request.signal` abort settles the request as `cancelled`.
 *
 * Exactly one `approval.resolved` is published per recorded request; mirroring
 * and race failures are logged and swallowed so the agent's approval path is
 * never affected.
 */

import crypto from 'node:crypto';

import { ApiError } from './errors.js';
import { isoTime } from './mappers.js';

/** Hard bound for pending mirrored approvals (oldest is dropped silently). */
export const DEFAULT_APPROVAL_CAP = 32;

/** Stable DSH approval outcomes accepted by the waterfall. */
export const APPROVAL_OUTCOMES = Object.freeze({
  allow: 'allowed-once',
  deny: 'rejected',
  abort: 'cancelled',
  unavailable: 'unavailable',
});

const MOBILE_ANSWER_DISABLED_MESSAGE =
  'Approvals are read-only on this host: set approvals.mobileAnswer=true to answer them from the phone';

/** A promise plus a resolver; one mobile decision per pending approval. */
function createDeferred() {
  let resolve;
  const promise = new Promise((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

/**
 * Human text for one approval request: localized display reason first, then the
 * raw `reason`, then a generic sentence. DSH's request event carries no command
 * line; callers may inject a `resolveCommand` lookup (e.g. from a prior
 * `tool/call` session event) and it defaults to ''.
 *
 * @param {any} request DSH `ApprovalRequestEvent`
 * @param {{ id: string, createdAt: string, command: string }} fields
 * @returns {{ id: string, sessionId: string, title: string, description: string, command: string, createdAt: string }}
 */
export function mapApprovalRequest(request, { id, createdAt, command }) {
  const sessionId = request?.agent?.id ? String(request.agent.id) : '';
  const toolName =
    typeof request?.toolName === 'string' && request.toolName.trim() !== ''
      ? request.toolName.trim()
      : 'tool';

  let displayReason;
  if (request?.displayReason && typeof request.displayReason === 'object') {
    const values = Object.values(request.displayReason);
    const english = /** @type {Record<string, unknown>} */ (request.displayReason).en;
    displayReason = typeof english === 'string' && english.trim() !== '' ? english : undefined;
    if (displayReason === undefined) {
      displayReason = values.find((value) => typeof value === 'string' && value.trim() !== '');
    }
  }
  const reason =
    typeof displayReason === 'string' && displayReason.trim() !== ''
      ? displayReason.trim()
      : typeof request?.reason === 'string' && request.reason.trim() !== ''
        ? request.reason.trim()
        : `The host is waiting for approval to run ${toolName}.`;

  return {
    id,
    sessionId,
    title: `Permission needed: ${toolName}`,
    description: reason,
    command: typeof command === 'string' ? command : '',
    createdAt,
  };
}

/** In-memory approval mirror: read-only observer or first-answer-wins answerer. */
export class ApprovalMirror {
  /**
   * @param {{
   *   bus?: import('./events.js').EventBus | null,
   *   logger?: Pick<Console, 'warn'>,
   *   cap?: number,
   *   mobileAnswer?: boolean,
   *   now?: () => number,
   *   idFactory?: () => string,
   *   resolveCommand?: (sessionId: string, callId: string) => string | undefined,
   *   isSessionVisible?: (sessionId: string) => boolean,
   * }} [options]
   */
  constructor({
    bus = null,
    logger = console,
    cap = DEFAULT_APPROVAL_CAP,
    mobileAnswer = true,
    now = () => Date.now(),
    idFactory = () => `ap_${crypto.randomUUID()}`,
    resolveCommand = () => '',
    isSessionVisible = () => true,
  } = {}) {
    this.bus = bus;
    this.logger = logger;
    this.cap = Number.isSafeInteger(cap) && cap > 0 ? cap : DEFAULT_APPROVAL_CAP;
    /** Only an explicit `false` disables mobile answering (default true). */
    this.mobileAnswer = mobileAnswer !== false;
    this.now = now;
    this.idFactory = idFactory;
    this.resolveCommand = resolveCommand;
    this.isSessionVisible = typeof isSessionVisible === 'function' ? isSessionVisible : () => true;
    /**
     * Pending requests: id -> { approval, decision, claimed, settled, evicted }.
     * Insertion order = request order.
     * @type {Map<string, any>}
     */
    this.pending = new Map();
  }

  /** Replace the session visibility predicate (internal/hidden filtering). */
  setSessionFilter(predicate) {
    this.isSessionVisible = typeof predicate === 'function' ? predicate : () => true;
  }

  /** Replace the best-effort tool-command lookup used for `approval.command`. */
  setCommandResolver(resolver) {
    this.resolveCommand = typeof resolver === 'function' ? resolver : () => '';
  }

  /** Number of currently pending mirrored approvals. */
  get size() {
    return this.pending.size;
  }

  /**
   * Contract list payload for `GET /mobile/v1/approvals`.
   *
   * `readOnly` is `false` only when the host allows phone answering; clients use
   * it to decide whether to render Allow/Deny controls.
   *
   * @returns {{ approvals: Array<Record<string, any>>, supported: true, readOnly: boolean }}
   */
  list() {
    return {
      approvals: [...this.pending.values()].reverse().map((entry) => ({ ...entry.approval })),
      supported: true,
      readOnly: !this.mobileAnswer,
    };
  }

  /**
   * Resolve one pending approval from the phone. First answer wins: an approval
   * already claimed by the desktop (or already answered) is `not_found`.
   *
   * @param {string} approvalId
   * @param {'allow' | 'deny'} decision
   * @returns {string} the DSH ApprovalOutcome ('allowed-once' | 'rejected')
   */
  decide(approvalId, decision) {
    if (!this.mobileAnswer) throw new ApiError('unsupported', MOBILE_ANSWER_DISABLED_MESSAGE);
    /** @type {string | undefined} */
    let outcome;
    if (decision === 'allow') outcome = APPROVAL_OUTCOMES.allow;
    else if (decision === 'deny') outcome = APPROVAL_OUTCOMES.deny;
    else throw new ApiError('invalid_request', 'Field decision must be "allow" or "deny"');

    const entry = this.pending.get(String(approvalId));
    if (!entry || entry.settled || entry.claimed) {
      throw new ApiError(
        'not_found',
        'This approval is no longer pending: it was answered elsewhere, expired, or is unknown',
      );
    }
    entry.claimed = true;
    entry.decision.resolve(outcome);
    return outcome;
  }

  /** Drop all pending records without emitting resolved events (dispose). */
  clear() {
    for (const entry of this.pending.values()) {
      if (!entry.settled) {
        entry.settled = true;
        // Unblock a waiting agent when the plugin unloads; the desktop chain may
        // still answer in the background and its outcome is ignored.
        try {
          entry.decision.resolve(APPROVAL_OUTCOMES.unavailable);
        } catch {
          /* already resolved */
        }
      }
    }
    this.pending.clear();
  }

  /**
   * Register the pass-through waterfall listener.
   * @param {any} ctx Cordis plugin context
   * @returns {() => void} disposer
   */
  attach(ctx) {
    if (!ctx || typeof ctx.on !== 'function') return () => {};
    const listener = (request, next) => this.handle(request, next);
    let dispose = () => {};
    try {
      const returned = ctx.on('approval/request', listener);
      if (typeof returned === 'function') dispose = returned;
    } catch (error) {
      this.logger.warn?.(
        `[dsh-mobile-host] could not register the approval mirror listener: ${String(
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
   * @param {any} request DSH `ApprovalRequestEvent`
   * @param {() => Promise<any>} next delegate to the remaining (desktop) answerers
   * @returns {Promise<any>} the first outcome (desktop or mobile) or `next()`'s rejection
   */
  async handle(request, next) {
    if (typeof next !== 'function') return undefined;
    /** @type {any} */
    let entry = null;
    try {
      entry = this.#record(request);
    } catch (error) {
      this.logger.warn?.(
        `[dsh-mobile-host] approval mirroring failed (agent path unaffected): ${String(
          /** @type {any} */ (error)?.message ?? error,
        )}`,
      );
      entry = null;
    }
    if (!entry) {
      // Hidden/not-mirrored request: pure delegation, exactly like before.
      try {
        return await next();
      } catch (error) {
        throw error;
      }
    }
    if (!this.mobileAnswer) return this.#handleReadOnly(entry, next);
    return this.#handleMobile(entry, request, next);
  }

  /**
   * Read-only mode: pass through unchanged and clean up when `next()` settles.
   * @param {any} entry
   * @param {() => Promise<any>} next
   */
  async #handleReadOnly(entry, next) {
    try {
      return await next();
    } finally {
      this.#finalize(entry);
    }
  }

  /**
   * Mobile-answering mode: race the deferred mobile decision against the
   * desktop chain (`next()`), first answer wins.
   *
   * @param {any} entry
   * @param {any} request
   * @param {() => Promise<any>} next
   */
  async #handleMobile(entry, request, next) {
    if (request?.signal?.aborted === true) {
      this.#finalize(entry);
      return APPROVAL_OUTCOMES.abort;
    }
    const onAbort = () => {
      if (entry.settled || entry.claimed) return;
      entry.claimed = true;
      entry.decision.resolve(APPROVAL_OUTCOMES.abort);
    };
    try {
      request?.signal?.addEventListener?.('abort', onAbort, { once: true });
    } catch {
      /* non-standard signal */
    }

    // The desktop chain is always started so the desktop remains an answerer.
    // Its rejection is normalized so a mobile-first win cannot leak it as an
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
            if (!entry.settled) {
              entry.claimed = true;
              this.#finalize(entry);
            }
            return result.outcome;
          }
          if (entry.settled) {
            // Mobile already answered: surface the desktop failure in logs only.
            this.logger.warn?.(
              `[dsh-mobile-host] desktop approval answerer failed after mobile resolution: ${String(
                /** @type {any} */ (result.error)?.message ?? result.error,
              )}`,
            );
            return undefined;
          }
          entry.claimed = true;
          this.#finalize(entry);
          throw result.error;
        }),
        entry.decision.promise.then((outcome) => {
          if (!entry.settled) this.#finalize(entry);
          return outcome;
        }),
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
   * Record one pending approval (with its deferred mobile decision) and publish
   * `approval.requested`.
   *
   * @param {any} request
   * @returns {any | null} the pending entry, or null when the session is filtered
   */
  #record(request) {
    const sessionId = request?.agent?.id ? String(request.agent.id) : '';
    let visible = true;
    try {
      visible = this.isSessionVisible(sessionId) !== false;
    } catch {
      visible = true;
    }
    if (!visible) return null;

    const callId = request?.callId ? String(request.callId) : '';
    let command = '';
    try {
      const resolved = this.resolveCommand(sessionId, callId);
      command = typeof resolved === 'string' ? resolved : '';
    } catch {
      command = '';
    }

    const approval = mapApprovalRequest(request, {
      id: String(this.idFactory()),
      createdAt: isoTime(this.now()),
      command,
    });
    const entry = {
      approval,
      decision: createDeferred(),
      claimed: false,
      settled: false,
      evicted: false,
    };
    this.pending.set(approval.id, entry);
    // Bound the mirror: the oldest pending record is dropped silently (it is no
    // longer phone-answerable; the desktop chain still answers it).
    while (this.pending.size > this.cap) {
      const oldestId = this.pending.keys().next().value;
      if (oldestId === undefined) break;
      const oldest = this.pending.get(oldestId);
      this.pending.delete(oldestId);
      if (oldest) oldest.evicted = true;
    }
    this.#publish('approval.requested', approval, approval.sessionId);
    return entry;
  }

  /**
   * Remove a settled approval and publish `approval.resolved` exactly once.
   * @param {any} entry
   * @returns {boolean} true when this call published the resolution
   */
  #finalize(entry) {
    if (!entry || typeof entry !== 'object' || entry.settled) return false;
    entry.settled = true;
    entry.claimed = true;
    if (this.pending.get(entry.approval.id) === entry) this.pending.delete(entry.approval.id);
    if (entry.evicted) return false;
    this.#publish('approval.resolved', { approvalId: entry.approval.id }, entry.approval.sessionId);
    return true;
  }

  /** Best-effort SSE publish; a transport problem must not affect the agent. */
  #publish(type, data, sessionId) {
    if (!this.bus || typeof this.bus.publish !== 'function') return;
    try {
      this.bus.publish(type, data, sessionId ? { sessionId } : {});
    } catch (error) {
      this.logger.warn?.(
        `[dsh-mobile-host] approval mirror SSE publish failed: ${String(
          /** @type {any} */ (error)?.message ?? error,
        )}`,
      );
    }
  }
}

/** Create one approval mirror. */
export function createApprovalMirror(options = {}) {
  return new ApprovalMirror(options);
}
