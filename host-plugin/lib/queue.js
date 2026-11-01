/**
 * Pure helpers for the live-agent prompt queue (Inbox).
 *
 * DSH keeps pending prompts in `agent.inbox.nextTurn` / `agent.inbox.nextStep`
 * as user messages (`{id, content, source}`). This module maps them to the
 * phone contract and validates queue mutations; it never touches a service.
 */

import { ApiError } from './errors.js';

/** Maximum characters accepted by a queue edit (matches the prompt contract scale). */
export const QUEUE_MAX_TEXT_CHARS = 20_000;
/** Maximum items returned by GET /sessions/{id}/queue. */
export const QUEUE_MAX_ITEMS = 100;
/** Maximum characters of list text returned (full edit text stays smaller). */
export const QUEUE_LIST_TEXT_MAX_CHARS = 20_000;

/** @param {unknown} value */
function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/** Text of one message's content blocks, joined by newlines, capped. */
function messageText(message) {
  const content = Array.isArray(message?.content) ? message.content : [];
  const parts = [];
  for (const block of content) {
    if (isObject(block) && block.type === 'text' && typeof block.text === 'string' && block.text !== '') {
      parts.push(block.text);
    }
  }
  return parts.join('\n').slice(0, QUEUE_LIST_TEXT_MAX_CHARS);
}

/** Number of attachment content blocks (image/file) in one message. */
function attachmentCount(message) {
  const content = Array.isArray(message?.content) ? message.content : [];
  return content.filter((block) => isObject(block) && (block.type === 'image' || block.type === 'file')).length;
}

/** Optional ISO timestamp when the message carries one. */
function messageCreatedAt(message) {
  const value = message?.createdAt ?? message?.time;
  if (typeof value !== 'number' || !Number.isFinite(value)) return undefined;
  try {
    return new Date(value).toISOString();
  } catch {
    return undefined;
  }
}

/**
 * Validate one PATCH queue body into the exact DSH `QueueAction` shape.
 *
 * - `edit`: `text` required, trimmed 1..20000 chars → `{kind:"edit",content:[{type:"text",text}]}`
 * - `remove` / `steer`: `text` must be absent
 *
 * @param {unknown} body
 * @returns {{ kind: 'edit', content: Array<{ type: 'text', text: string }> } | { kind: 'remove' } | { kind: 'steer' }}
 */
export function normalizeQueueAction(body) {
  if (!isObject(body)) {
    throw new ApiError('invalid_request', 'Queue action body must be an object');
  }
  const action = body.action;
  if (action !== 'edit' && action !== 'remove' && action !== 'steer') {
    throw new ApiError('invalid_request', 'Field action must be "edit", "remove" or "steer"');
  }
  if (action !== 'edit') {
    if (body.text !== undefined) {
      throw new ApiError('invalid_request', 'Field text is only allowed for action "edit"');
    }
    return { kind: action };
  }
  if (typeof body.text !== 'string') {
    throw new ApiError('invalid_request', 'Field text is required for action "edit"');
  }
  const text = body.text.trim();
  if (text === '') {
    throw new ApiError('invalid_request', 'Field text must not be empty');
  }
  if (text.length > QUEUE_MAX_TEXT_CHARS) {
    throw new ApiError('invalid_request', `Field text exceeds ${QUEUE_MAX_TEXT_CHARS} characters`);
  }
  return { kind: 'edit', content: [{ type: 'text', text }] };
}

/**
 * Map one live agent's Inbox to the phone contract, next-turn items before
 * next-step items. Malformed messages are skipped; the list is capped.
 *
 * @param {{ nextTurn?: ReadonlyArray<any>, nextStep?: ReadonlyArray<any> } | null | undefined} inbox
 * @returns {Array<{ itemId: string, text: string, target: 'next-turn' | 'next-step', attachmentCount: number, createdAt?: string }>}
 */
export function mapQueueItems(inbox) {
  const items = [];
  const targets = [
    ['nextTurn', 'next-turn'],
    ['nextStep', 'next-step'],
  ];
  for (const [key, target] of targets) {
    const messages = Array.isArray(inbox?.[key]) ? inbox[key] : [];
    for (const message of messages) {
      if (items.length >= QUEUE_MAX_ITEMS) return items;
      if (!isObject(message) || typeof message.id !== 'string' || message.id === '') continue;
      const createdAt = messageCreatedAt(message);
      items.push({
        itemId: message.id,
        text: messageText(message),
        target,
        attachmentCount: attachmentCount(message),
        ...(createdAt === undefined ? {} : { createdAt }),
      });
    }
  }
  return items;
}
