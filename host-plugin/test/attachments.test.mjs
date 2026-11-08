import test from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';

import {
  AttachmentStore,
  decodeBase64Attachment,
  generateAttachmentId,
  IMAGE_MEDIA_TYPES,
  kindForMimeType,
} from '../lib/attachments.js';
import { resolveConfig } from '../lib/config.js';
import { ApiError } from '../lib/errors.js';
import { buildPromptContent } from '../lib/mappers.js';
import { createAttachmentStore, testConfig } from './helpers.mjs';

const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01, 0x02]);
const PNG_BASE64 = PNG.toString('base64');

/** @param {unknown} value */
function isApiError(code) {
  return (error) => error instanceof ApiError && error.code === code;
}

test('attachment config defaults match the v0.3.13 contract', () => {
  const config = resolveConfig({});
  assert.equal(config.version, '0.3.13');
  assert.equal(config.attachments.maxBytes, 10 * 1024 * 1024);
  assert.equal(config.attachments.maxBase64Bytes, 14 * 1024 * 1024);
  assert.equal(config.attachments.ttlMs, 24 * 60 * 60 * 1000);
  assert.equal(config.attachments.maxPerPrompt, 8);
  assert.equal(config.http.maxBodyBytes >= config.attachments.maxBase64Bytes, true);
  assert.deepEqual([...IMAGE_MEDIA_TYPES], ['image/png', 'image/jpeg', 'image/webp', 'image/gif']);
  assert.deepEqual(config.artifacts.roots, [], 'no artifact roots by default');
  assert.deepEqual(config.artifacts.remote, {
    enabled: false,
    maxBytes: 10 * 1024 * 1024,
    timeoutMs: 8000,
    ttlMs: 10 * 60 * 1000,
    cacheEntries: 32,
  });
  assert.equal(resolveConfig({ artifacts: { remote: { enabled: 'yes' } } }).artifacts.remote.enabled, false);
  const remote = resolveConfig({
    artifacts: {
      remote: { enabled: true, maxBytes: 100 * 1024 * 1024, timeoutMs: 1, ttlMs: 9_000_000, cacheEntries: 0 },
    },
  }).artifacts.remote;
  assert.equal(remote.enabled, true);
  assert.equal(remote.maxBytes, 10 * 1024 * 1024, 'oversized/invalid maxBytes falls back to the default');
  assert.equal(remote.timeoutMs, 8000);
  assert.equal(remote.ttlMs, 10 * 60 * 1000);
  assert.equal(remote.cacheEntries, 32);
});

test('artifacts.roots normalize to unique absolute directories and reject unsafe values', () => {
  const first = path.join(os.tmpdir(), 'dsh-artifacts-a');
  const second = path.join(os.tmpdir(), 'dsh-artifacts-b');
  // Windows filesystems are case-insensitive; POSIX ones are not, so only exercise the
  // case-collapse behaviour where the platform actually collapses it.
  const duplicate = process.platform === 'win32' ? first.toUpperCase() : first;
  const roots = resolveConfig({
    artifacts: { roots: [`  ${first}  `, duplicate, ` ${second} `, ''] },
  }).artifacts.roots;
  assert.equal(roots.length, 2);
  assert.equal(roots[0], path.normalize(first));
  assert.equal(roots[1], path.normalize(second));

  for (const bad of ['relative/dir', [42], 42, { roots: 'C:\\foo' }]) {
    assert.throws(() => resolveConfig({ artifacts: { roots: bad } }), /artifacts\.roots/u);
  }
  const uncValues = process.platform === 'win32'
    ? ['\\\\server\\share', '//host/share', '\\\\?\\C:\\device']
    : ['//host/share', '///server/share', '//?/device'];
  for (const bad of uncValues) {
    assert.throws(() => resolveConfig({ artifacts: { roots: [bad] } }), /UNC\/device/u);
  }
});

test('kindForMimeType classifies the four image media types and everything else as file', () => {
  for (const mediaType of IMAGE_MEDIA_TYPES) assert.equal(kindForMimeType(mediaType), 'image');
  assert.equal(kindForMimeType('IMAGE/PNG'), 'image');
  assert.equal(kindForMimeType('application/pdf'), 'file');
  assert.equal(kindForMimeType('image/svg+xml'), 'file');
  assert.equal(kindForMimeType(undefined), 'file');
});

test('decodeBase64Attachment accepts canonical base64, tolerating wrapped lines', () => {
  const decoded = decodeBase64Attachment(PNG_BASE64, { maxDecodedBytes: 1024 });
  assert.deepEqual(decoded.data, PNG);
  assert.equal(decoded.canonical, PNG_BASE64);

  const wrapped = `${PNG_BASE64.slice(0, 8)}\n${PNG_BASE64.slice(8)}`;
  assert.deepEqual(decodeBase64Attachment(wrapped, { maxDecodedBytes: 1024 }).data, PNG);
});

test('decodeBase64Attachment rejects malformed, non-canonical, empty, and oversized payloads', () => {
  for (const value of ['', '   ', 'not base64!!', 'AAA', 'AB==', 42, null, undefined]) {
    assert.throws(
      () => decodeBase64Attachment(value, { maxDecodedBytes: 1024 }),
      isApiError('invalid_request'),
      `payload ${String(value)} must be refused`,
    );
  }
  assert.throws(
    () => decodeBase64Attachment(Buffer.alloc(2048).toString('base64'), { maxDecodedBytes: 1024 }),
    (error) => error instanceof ApiError && error.code === 'invalid_request' && error.message.includes('limit'),
  );
});

test('generateAttachmentId mints opaque, unique att_ ids', () => {
  const ids = new Set();
  for (let index = 0; index < 50; index += 1) {
    const id = generateAttachmentId();
    assert.match(id, /^att_[A-Za-z0-9_-]{16}$/);
    ids.add(id);
  }
  assert.equal(ids.size, 50);
});

test('AttachmentStore records pending attachments and expires them after the TTL', () => {
  let now = Date.UTC(2026, 10, 1, 12, 0, 0);
  const config = testConfig();
  const store = createAttachmentStore(config, { now: () => now });

  const record = store.add({
    kind: 'image',
    name: 'shot.png',
    mimeType: 'image/png',
    bytes: PNG.length,
    sessionId: 's_1',
    deviceId: 'dev_1',
    dataBase64: PNG_BASE64,
  });
  assert.equal(store.size, 1);
  assert.equal(store.get(record.attachmentId).expiresAt, now + config.attachments.ttlMs);
  assert.deepEqual(store.publicView(record), {
    attachmentId: record.attachmentId,
    name: 'shot.png',
    mimeType: 'image/png',
    bytes: PNG.length,
    kind: 'image',
  });
  assert.equal('dataBase64' in store.publicView(record), false, 'bytes never leave the host through the API');

  now += config.attachments.ttlMs + 1;
  assert.equal(store.size, 0);
  assert.equal(store.get(record.attachmentId), undefined);
  assert.equal(store.pendingImageBytes, 0);
});

test('AttachmentStore enforces the pending count and image byte bounds', () => {
  const config = testConfig({ attachments: { maxPendingCount: 2, maxPendingBytes: 100 } });
  const store = new AttachmentStore({ config });
  const add = (name, dataBase64) =>
    store.add({
      kind: dataBase64 ? 'image' : 'file',
      name,
      mimeType: dataBase64 ? 'image/png' : 'application/pdf',
      bytes: 1,
      sessionId: 's_1',
      deviceId: 'dev_1',
      dataBase64: dataBase64 ?? null,
    });

  add('one', null);
  add('two', null);
  assert.throws(() => add('three', null), isApiError('invalid_request'));

  const byteBound = testConfig({ attachments: { maxPendingBytes: 10 } });
  const byteStore = new AttachmentStore({ config: byteBound });
  const bigImage = Buffer.alloc(8, 1).toString('base64'); // 12 chars > 10 bytes
  assert.throws(
    () =>
      byteStore.add({
        kind: 'image',
        name: 'big.png',
        mimeType: 'image/png',
        bytes: 8,
        sessionId: 's_1',
        deviceId: 'dev_1',
        dataBase64: bigImage,
      }),
    isApiError('invalid_request'),
  );
});

test('AttachmentStore.resolveForPrompt enforces device, session, expiry, and uniqueness', () => {
  let now = Date.now();
  const config = testConfig();
  const store = createAttachmentStore(config, { now: () => now });
  const image = store.add({
    kind: 'image',
    name: 'shot.png',
    mimeType: 'image/png',
    bytes: PNG.length,
    sessionId: 's_1',
    deviceId: 'dev_a',
    dataBase64: PNG_BASE64,
  });
  const file = store.add({
    kind: 'file',
    name: 'report.pdf',
    mimeType: 'application/pdf',
    bytes: 10,
    sessionId: 's_1',
    deviceId: 'dev_a',
    receiptId: 'receipt_1',
  });

  const resolved = store.resolveForPrompt({
    attachmentIds: [image.attachmentId, file.attachmentId],
    sessionId: 's_1',
    deviceId: 'dev_a',
  });
  assert.deepEqual(resolved.map((record) => record.kind), ['image', 'file']);

  const expectRefusal = (attachmentIds, sessionId = 's_1', deviceId = 'dev_a') =>
    assert.throws(
      () => store.resolveForPrompt({ attachmentIds, sessionId, deviceId }),
      isApiError('not_found'),
    );

  expectRefusal(['att_unknown']);
  expectRefusal([image.attachmentId], 's_2');
  expectRefusal([image.attachmentId], 's_1', 'dev_other');
  assert.throws(
    () => store.resolveForPrompt({ attachmentIds: [image.attachmentId, image.attachmentId], sessionId: 's_1', deviceId: 'dev_a' }),
    isApiError('invalid_request'),
  );
  assert.throws(
    () => store.resolveForPrompt({ attachmentIds: [''], sessionId: 's_1', deviceId: 'dev_a' }),
    isApiError('invalid_request'),
  );

  store.consume([image]);
  assert.equal(store.size, 1);
  assert.equal(store.pendingImageBytes, 0);
  expectRefusal([image.attachmentId]);

  now += config.attachments.ttlMs + 1;
  expectRefusal([file.attachmentId]);
});

test('buildPromptContent emits text/image/file parts in order and skips empty text', () => {
  const image = { kind: 'image', name: 'shot.png', mimeType: 'image/png', dataBase64: PNG_BASE64 };
  const file = { kind: 'file', name: 'report.pdf', receiptId: 'receipt_123' };

  assert.deepEqual(buildPromptContent('Hello', []), [{ type: 'text', text: 'Hello' }]);
  assert.deepEqual(buildPromptContent('', [file]), [{ type: 'file', receiptId: 'receipt_123' }]);
  assert.deepEqual(buildPromptContent('   ', [image]), [
    { type: 'image', mediaType: 'image/png', data: PNG_BASE64, name: 'shot.png' },
  ]);
  assert.deepEqual(buildPromptContent('Summarise', [image, file]), [
    { type: 'text', text: 'Summarise' },
    { type: 'image', mediaType: 'image/png', data: PNG_BASE64, name: 'shot.png' },
    { type: 'file', receiptId: 'receipt_123' },
  ]);
  assert.deepEqual(buildPromptContent('', []), []);
});

test('buildPromptContent refuses file records without a receipt and unknown kinds', () => {
  assert.throws(
    () => buildPromptContent('', [{ kind: 'file', name: 'x.pdf', receiptId: null }]),
    isApiError('unsupported'),
  );
  assert.throws(() => buildPromptContent('', [{ kind: 'video' }]), isApiError('invalid_request'), 'unknown kind');
  assert.throws(
    () => buildPromptContent('', [{ kind: 'image', name: 'x.png', mimeType: 'image/png', dataBase64: null }]),
    (error) => error instanceof ApiError && error.code === 'internal',
  );
});
