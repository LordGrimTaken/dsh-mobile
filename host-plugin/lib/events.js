/**
 * Server-sent-event bus with a replay ring buffer.
 *
 * Every published event is frozen into the contract envelope:
 *   { id: "ev_000123", type: "message.delta", at: "<ISO-8601>", sessionId?, data }
 *
 * `since` replay: a client reconnects with the last id it saw. If that id is
 * still buffered the bus replays everything after it; otherwise the server
 * tells the client to resynchronize (host.status + session.updated snapshots).
 */

import { isoTime } from './mappers.js';

/** Event types defined by the contract (documentation-only list). */
export const EVENT_TYPES = Object.freeze([
  'session.updated',
  'session.removed',
  'message.added',
  'message.delta',
  'message.completed',
  'approval.requested',
  'approval.resolved',
  'question.requested',
  'question.resolved',
  'goal.changed',
  'host.status',
  'ping',
]);

/** Monotonic event sequence. */
export class EventBus {
  /** @param {{ bufferSize?: number, now?: () => number, logger?: Pick<Console, 'warn'> }} [options] */
  constructor({ bufferSize = 500, now = () => Date.now(), logger = console } = {}) {
    this.bufferSize = Math.max(1, bufferSize);
    this.now = now;
    this.logger = logger;
    this.sequence = 0;
    /** @type {Array<Record<string, any>>} */
    this.buffer = [];
    /** @type {Set<(event: Record<string, any>) => void>} */
    this.listeners = new Set();
  }

  /**
   * Publish one event.
   *
   * @param {string} type contract event type
   * @param {unknown} data event payload (the contract's `data` object)
   * @param {{ sessionId?: string | null }} [options]
   * @returns {Record<string, any>} the enveloped event
   */
  publish(type, data, options = {}) {
    this.sequence += 1;
    const event = {
      id: `ev_${String(this.sequence).padStart(6, '0')}`,
      type,
      at: isoTime(this.now()),
      ...(options.sessionId ? { sessionId: String(options.sessionId) } : {}),
      data: data === undefined ? {} : data,
    };
    this.buffer.push(event);
    if (this.buffer.length > this.bufferSize) this.buffer.splice(0, this.buffer.length - this.bufferSize);
    for (const listener of [...this.listeners]) {
      try {
        listener(event);
      } catch (error) {
        this.logger.warn?.(`[dsh-mobile-host] SSE listener failed: ${String(error)}`);
      }
    }
    return event;
  }

  /** @param {(event: Record<string, any>) => void} listener @returns {() => void} */
  subscribe(listener) {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  /**
   * Replay events strictly after `since`.
   *
   * @param {string | undefined | null} since
   * @returns {{ found: boolean, events: Array<Record<string, any>> }}
   *   `found: false` when no cursor was supplied or it fell out of the buffer.
   */
  replaySince(since) {
    if (typeof since !== 'string' || since === '') return { found: false, events: [] };
    const index = this.buffer.findIndex((event) => event.id === since);
    if (index === -1) return { found: false, events: [] };
    return { found: true, events: this.buffer.slice(index + 1) };
  }

  /** Last buffered event id, or undefined when empty. */
  lastEventId() {
    return this.buffer.at(-1)?.id;
  }
}

/**
 * Render one envelope as an SSE frame:
 *   id: ev_000001
 *   event: message.delta
 *   data: {...}
 *
 * @param {Record<string, any>} event
 */
export function formatSseFrame(event) {
  const id = String(event?.id ?? '').replace(/[\r\n]/g, '');
  const type = String(event?.type ?? '').replace(/[\r\n]/g, '');
  const data = JSON.stringify(event?.data ?? {});
  return `id: ${id}\nevent: ${type}\ndata: ${data}\n\n`;
}

/**
 * Render a per-connection (non-replayable) envelope frame. It carries the full
 * envelope like every other frame but deliberately has **no** `id:` line, so it
 * can never advance a client's `since` cursor.
 *
 * @param {string} type
 * @param {unknown} data
 * @param {number} [time]
 */
export function formatLiveFrame(type, data, time = Date.now()) {
  const envelope = { type, at: isoTime(time), data: data === undefined ? {} : data };
  return `event: ${String(type).replace(/[\r\n]/g, '')}\ndata: ${JSON.stringify(envelope)}\n\n`;
}

/** Heartbeat: envelope-shaped `ping` with empty data and no id. */
export function formatPingFrame(time = Date.now()) {
  return formatLiveFrame('ping', {}, time);
}

