import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';

import { resolveConfig } from '../lib/config.js';
import { ApiError } from '../lib/errors.js';
import {
  PairingStore,
  RECOVERY_CODE_BYTES,
  canonicalRecoveryCode,
  formatRecoveryCode,
  generatePairingCode,
  generateRecoveryCode,
  generateToken,
  hashToken,
  sanitizeText,
  RateLimiter,
  TOKEN_BYTES,
} from '../lib/pairing.js';
import { JsonStateStore, normalizeState } from '../lib/store.js';
import { tempStateFile } from './helpers.mjs';
import { issueToken, makePairing, testConfig } from './helpers.mjs';

test('config: defaults match the v1 contract', () => {
  const config = resolveConfig({});
  assert.equal(config.host, '127.0.0.1');
  assert.equal(config.port, 3543);
  assert.equal(config.apiVersion, 1);
  assert.equal(config.pairing.codeTtlMs, 5 * 60 * 1000);
  assert.equal(config.pairing.codeLength, 6);
  assert.equal(config.pairing.maxFailedAttemptsPerMinute, 5);
  assert.equal(config.pairing.tailscaleAutoPair, true);
  assert.equal(config.pairing.tailscaleLogin, '');
  assert.equal(config.sse.heartbeatMs, 20 * 1000);
  assert.equal(config.approvals.mobileAnswer, true);
  assert.equal(config.questions.mobileAnswer, true);
  assert.equal(config.hostName.length > 0, true);
});

test('config: approvals/questions mobileAnswer default on and only explicit false disables', () => {
  assert.equal(resolveConfig({}).approvals.mobileAnswer, true);
  assert.equal(resolveConfig({}).questions.mobileAnswer, true);
  assert.equal(resolveConfig({ approvals: { mobileAnswer: false } }).approvals.mobileAnswer, false);
  assert.equal(resolveConfig({ questions: { mobileAnswer: false } }).questions.mobileAnswer, false);
  assert.equal(resolveConfig({ approvals: { mobileAnswer: 'nope' } }).approvals.mobileAnswer, true);
  assert.equal(resolveConfig({ questions: { mobileAnswer: 'nope' } }).questions.mobileAnswer, true);
  assert.equal(resolveConfig({ approvals: { mobileAnswer: 0 } }).approvals.mobileAnswer, true);
  assert.equal(resolveConfig({ questions: { mobileAnswer: 0 } }).questions.mobileAnswer, true);
  assert.equal(Object.isFrozen(resolveConfig({}).approvals), true);
  assert.equal(Object.isFrozen(resolveConfig({}).questions), true);
});

test('config: tailscale auto-pair defaults on and normalizes auto-pair/login', () => {
  assert.equal(resolveConfig({}).pairing.tailscaleAutoPair, true);
  assert.equal(resolveConfig({ pairing: { tailscaleAutoPair: false } }).pairing.tailscaleAutoPair, false);
  assert.equal(resolveConfig({ pairing: { tailscaleAutoPair: 'false' } }).pairing.tailscaleAutoPair, true);
  assert.equal(resolveConfig({}).pairing.tailscaleLogin, '');
  assert.equal(resolveConfig({ pairing: { tailscaleLogin: '' } }).pairing.tailscaleLogin, '');
  assert.equal(
    resolveConfig({ pairing: { tailscaleLogin: '  Me@Example.com  ' } }).pairing.tailscaleLogin,
    'Me@Example.com',
  );
  assert.equal(resolveConfig({ pairing: { tailscaleLogin: 42 } }).pairing.tailscaleLogin, '');
});

test('config: refuses to bind anything but loopback', () => {
  for (const host of ['0.0.0.0', '192.168.1.20', '::', 'example.com']) {
    assert.throws(() => resolveConfig({ host }), /refusing to bind/, `host ${host} must be refused`);
  }
  assert.doesNotThrow(() => resolveConfig({ host: '127.0.0.1' }));
  assert.doesNotThrow(() => resolveConfig({ host: 'localhost' }));
  assert.doesNotThrow(() => resolveConfig({ host: '::1' }));
});

test('config: deep-merges nested overrides and freezes the result', () => {
  const config = resolveConfig({ pairing: { codeTtlMs: 60_000 }, port: 0 });
  assert.equal(config.pairing.codeTtlMs, 60_000);
  assert.equal(config.pairing.codeLength, 6, 'unspecified nested defaults are kept');
  assert.equal(config.port, 0);
  assert.equal(Object.isFrozen(config), true);
  assert.equal(Object.isFrozen(config.pairing), true);
});

test('config: validates publicBaseUrl and pairing code length', () => {
  assert.throws(() => resolveConfig({ publicBaseUrl: 'pc.tailnet.ts.net' }), /must start with http/);
  assert.throws(() => resolveConfig({ publicBaseUrl: 'https://pc.ts.net/' }), /must not end with a slash/);
  assert.equal(resolveConfig({ publicBaseUrl: 'https://pc.tailnet.ts.net' }).publicBaseUrl, 'https://pc.tailnet.ts.net');
  assert.throws(() => resolveConfig({ pairing: { codeLength: 8 } }), /codeLength must be 6/);
});

test('pairing: tokens are 32 random base64url bytes and only their sha256 is storable', () => {
  const token = generateToken();
  assert.equal(Buffer.from(token, 'base64url').length, TOKEN_BYTES);
  assert.match(token, /^[A-Za-z0-9_-]{43}$/);
  assert.notEqual(generateToken(), token);
  assert.match(hashToken(token), /^[0-9a-f]{64}$/);
  assert.notEqual(hashToken(token), token);
});

test('pairing: codes are 6 digits, one-time, and expire after five minutes', () => {
  let now = Date.UTC(2026, 9, 30, 10, 0, 0);
  const config = testConfig();
  const pairing = makePairing(config, { now: () => now });

  const { code, expiresAt } = pairing.createCode();
  assert.match(code, /^\d{6}$/);
  assert.equal(expiresAt - now, 5 * 60 * 1000);

  const issued = pairing.complete({ code, deviceName: 'Pixel 9', platform: 'android', ip: '127.0.0.1' });
  assert.equal(pairing.pendingCodeCount(), 0, 'used code is consumed');
  assert.equal(issued.token.length > 20, true);
  assert.equal(issued.device.deviceName, 'Pixel 9');

  assert.throws(
    () => pairing.complete({ code, deviceName: 'Pixel 9', platform: 'android', ip: '127.0.0.1' }),
    (error) => error instanceof ApiError && error.code === 'invalid_code' && error.status === 400,
  );

  const second = pairing.createCode();
  now += 5 * 60 * 1000 + 1;
  assert.throws(
    () => pairing.complete({ code: second.code, ip: '127.0.0.1' }),
    (error) => error instanceof ApiError && error.code === 'invalid_code',
  );
});

test('pairing: same code cannot be used concurrently (one use)', () => {
  const pairing = makePairing(testConfig());
  const { code } = pairing.createCode();
  assert.throws(() => pairing.complete({ code: '000000', ip: '127.0.0.1' }), /invalid, expired, or already used/);
});

test('pairing: failed attempts are rate limited per IP at 5/minute', () => {
  const config = testConfig();
  const pairing = makePairing(config);
  for (let attempt = 0; attempt < 5; attempt += 1) {
    assert.throws(
      () => pairing.complete({ code: '111111', ip: '10.0.0.9' }),
      (error) => error instanceof ApiError && error.code === 'invalid_code',
    );
  }
  assert.throws(
    () => pairing.complete({ code: '111111', ip: '10.0.0.9' }),
    (error) => error instanceof ApiError && error.code === 'rate_limited' && error.status === 429,
  );
  // A different peer keeps its own budget.
  assert.throws(
    () => pairing.complete({ code: '111111', ip: '10.0.0.10' }),
    (error) => error instanceof ApiError && error.code === 'invalid_code',
  );
});

test('pairing: successful pairing is not charged against the failure budget', () => {
  const pairing = makePairing(testConfig());
  for (let attempt = 0; attempt < 4; attempt += 1) {
    assert.throws(() => pairing.complete({ code: '222222', ip: '127.0.0.1' }));
  }
  const { code } = pairing.createCode();
  assert.doesNotThrow(() => pairing.complete({ code, ip: '127.0.0.1' }));
});

test('pairing: authenticate accepts only the exact issued token', () => {
  const pairing = makePairing(testConfig());
  const { token, device } = pairing.complete({ ...pairing.createCode(), ip: '127.0.0.1' });
  assert.equal(pairing.hasDevices(), true);
  const resolved = pairing.authenticate(token);
  assert.equal(resolved?.deviceId, device.deviceId);
  assert.equal(resolved?.deviceName, 'Unknown device');
  assert.equal(pairing.authenticate(`${token}x`), undefined);
  assert.equal(pairing.authenticate('short'), undefined);
  assert.equal(pairing.authenticate(undefined), undefined);
  assert.equal(pairing.listDevices().length, 1);
  assert.equal('tokenHash' in pairing.listDevices()[0], false, 'hashes never leave the store');
});

test('pairing: device hashes survive a store restart through persisted state', () => {
  /** @type {any} */
  let saved;
  const config = testConfig();
  const first = new PairingStore({
    config,
    persist: async (state) => {
      saved = state;
    },
  });
  const { token, device } = first.complete({ ...first.createCode(), ip: '127.0.0.1' });
  assert.ok(saved);
  assert.match(saved.devices[0].tokenHash, /^[0-9a-f]{64}$/);
  assert.equal(JSON.stringify(saved).includes(token), false, 'raw token is never persisted');

  const restored = new PairingStore({ config, state: saved });
  assert.equal(restored.authenticate(token)?.deviceId, device.deviceId);
});

test('pairing: device metadata is sanitized and capped', () => {
  assert.equal(sanitizeText('  Pixel\u0000 9 ', 100), 'Pixel 9');
  assert.equal(sanitizeText('   ', 100), undefined);
  assert.equal(sanitizeText('x'.repeat(150), 100).length, 100);

  const pairing = makePairing(testConfig());
  const { device } = pairing.complete({
    ...pairing.createCode(),
    deviceName: 'x'.repeat(200),
    platform: 'android',
    publicKey: 'a'.repeat(9000),
    ip: '127.0.0.1',
  });
  assert.equal(device.deviceName.length, 100);
  assert.equal(device.publicKey.length, 4096);
});

test('pairing: rate limiter window recovers and retryAfterMs is reported', () => {
  let now = 0;
  const limiter = new RateLimiter({ limit: 2, windowMs: 60_000, now: () => now });
  assert.equal(limiter.allows('ip'), true);
  limiter.recordFailure('ip');
  limiter.recordFailure('ip');
  assert.equal(limiter.allows('ip'), false);
  assert.equal(limiter.retryAfterMs('ip'), 60_000);
  now = 60_001;
  assert.equal(limiter.allows('ip'), true);
});

test('pairing: codes generated by the standalone helper are always 6 digits', () => {
  for (let index = 0; index < 200; index += 1) {
    assert.match(generatePairingCode(), /^\d{6}$/);
  }
});

test('pairing: revokeDevice removes one device, persists awaited, notifies and invalidates its token', async () => {
  const config = testConfig();
  let saves = 0;
  const pairing = makePairing(config, {
    persist: async () => {
      saves += 1;
    },
  });
  const revoked = [];
  const unsubscribe = pairing.onRevoke((deviceId) => revoked.push(deviceId));
  const first = issueToken(pairing, { deviceName: 'Phone' });
  const second = issueToken(pairing, { deviceName: 'Tablet' });
  assert.equal(pairing.listDevices().length, 2);

  saves = 0;
  assert.equal(await pairing.revokeDevice(second.device.deviceId), true);
  assert.equal(pairing.listDevices().length, 1);
  assert.equal(pairing.authenticate(second.token), undefined, 'revoked token stops authenticating');
  assert.equal(pairing.authenticate(first.token).deviceId, first.device.deviceId);
  assert.equal(saves, 1, 'persistence is awaited before revokeDevice resolves');
  assert.deepEqual(revoked, [second.device.deviceId]);

  unsubscribe();
  assert.equal(await pairing.revokeDevice(second.device.deviceId), false);
  assert.equal(await pairing.revokeDevice(''), false);
});

test('pairing: a failed revocation persistence rolls back and surfaces the error', async () => {
  const config = testConfig();
  let fail = true;
  const pairing = new PairingStore({
    config,
    persist: async () => {
      if (fail) throw new Error('disk full');
    },
    logger: { warn() {} },
  });
  const first = issueToken(pairing, { deviceName: 'Phone' });
  const second = issueToken(pairing, { deviceName: 'Tablet' });

  await assert.rejects(() => pairing.revokeDevice(second.device.deviceId), /disk full/);
  assert.equal(pairing.listDevices().length, 2, 'the removal is rolled back on a failed write');
  assert.equal(pairing.authenticate(second.token).deviceId, second.device.deviceId);
  assert.equal(pairing.authenticate(first.token).deviceId, first.device.deviceId);

  fail = false;
  assert.equal(await pairing.revokeDevice(second.device.deviceId), true);
  assert.equal(pairing.listDevices().length, 1);
});

test('recovery: codes use 52 base32 chars (256-bit entropy), grouped and case-insensitive', () => {
  assert.equal(RECOVERY_CODE_BYTES, 32);
  const code = generateRecoveryCode();
  assert.match(code, /^(?:[A-Z2-7]{4}-){12}[A-Z2-7]{4}$/);
  const compact = canonicalRecoveryCode(code);
  assert.equal(compact.length, 52);
  assert.equal(formatRecoveryCode(compact), code);
  assert.equal(canonicalRecoveryCode(code.toLowerCase()), compact);
  assert.equal(canonicalRecoveryCode('not a code'), undefined);
  assert.equal(canonicalRecoveryCode('A'.repeat(51)), undefined);
  assert.notEqual(generateRecoveryCode(), generateRecoveryCode());
});

test('recovery: main state stores only the hash and rotates atomically', async (t) => {
  const stateFile = await tempStateFile(t);
  const config = testConfig({ stateFile });
  const stateStore = new JsonStateStore(stateFile, { logger: { warn() {} } });
  const pairing = new PairingStore({
    config,
    persist: (state) => stateStore.save(state),
    logger: { warn() {} },
  });
  assert.deepEqual(pairing.getRecoveryInfo(), { configured: false });

  const created = await pairing.createRecoveryCode();
  assert.match(created.code, /^(?:[A-Z2-7]{4}-){12}[A-Z2-7]{4}$/);
  assert.match(created.createdAt, /^\d{4}-\d{2}-\d{2}T/);
  const info = pairing.getRecoveryInfo();
  assert.equal(info.configured, true);
  assert.equal(info.createdAt, created.createdAt);
  assert.equal(JSON.stringify(info).includes(created.code.replace(/-/g, '')), false);
  assert.equal(JSON.stringify(info).includes('hash'), false);

  const rotated = await pairing.createRecoveryCode();
  assert.notEqual(rotated.code, created.code);

  // The MAIN state file contains only the recovery hash, never the code.
  const persisted = await stateStore.load();
  assert.match(persisted.recovery.hash, /^[0-9a-f]{64}$/);
  assert.equal(JSON.stringify(persisted).includes(rotated.code.replace(/-/g, '')), false);
  assert.deepEqual(persisted.devices, []);

  // A fresh store loads the main state and redeems the code (single atomic write
  // includes the minted device + rotated hash).
  const reloaded = new PairingStore({
    config,
    state: persisted,
    persist: (state) => stateStore.save(state),
    logger: { warn() {} },
  });
  assert.deepEqual(reloaded.getRecoveryInfo(), { configured: true, createdAt: rotated.createdAt });
  const paired = await reloaded.consumeRecoveryCode(rotated.code, { deviceName: 'Phone', platform: 'android' });
  assert.equal(typeof paired?.token, 'string');
  assert.equal(paired.device.deviceName, 'Phone');
  assert.equal(reloaded.authenticate(paired.token).deviceId, paired.device.deviceId);
  assert.match(paired.nextCode, /^(?:[A-Z2-7]{4}-){12}[A-Z2-7]{4}$/);
  assert.notEqual(paired.nextCode, rotated.code);

  const afterRedeem = await stateStore.load();
  assert.equal(afterRedeem.devices.length, 1);
  assert.equal(afterRedeem.devices[0].deviceId, paired.device.deviceId);
  assert.notEqual(afterRedeem.recovery.hash, persisted.recovery.hash, 'hash rotated in the same state write');

  // Single-use: the consumed code no longer works, the new one does.
  assert.equal(await reloaded.consumeRecoveryCode(rotated.code), undefined);
  const second = await reloaded.consumeRecoveryCode(paired.nextCode, { deviceName: 'Tablet' });
  assert.equal(typeof second?.token, 'string');
});

test('recovery: legacy sidecar is read and migrated into the main state file', async (t) => {
  const stateFile = await tempStateFile(t);
  const config = testConfig({ stateFile });
  const legacyCode = generateRecoveryCode();
  const createdAt = Date.now();
  await writeFile(
    `${stateFile}.recovery.json`,
    `${JSON.stringify({ version: 1, recovery: { hash: hashToken(canonicalRecoveryCode(legacyCode)), createdAt } })}\n`,
    'utf8',
  );
  const stateStore = new JsonStateStore(stateFile, { logger: { warn() {} } });
  const pairing = new PairingStore({
    config,
    state: await stateStore.load(),
    persist: (state) => stateStore.save(state),
    logger: { warn() {} },
  });
  assert.equal(pairing.getRecoveryInfo().configured, true, 'legacy sidecar is honored');

  // Any state write moves the hash into the main file and removes the sidecar.
  const rotated = await pairing.createRecoveryCode();
  assert.notEqual(rotated.code, legacyCode);
  assert.match((await stateStore.load()).recovery.hash, /^[0-9a-f]{64}$/);
  await assert.rejects(readFile(`${stateFile}.recovery.json`, 'utf8'), /ENOENT/);
});

test('store: normalizeState preserves the optional recovery hash and drops invalid data', () => {
  const hash = 'a'.repeat(64);
  assert.deepEqual(
    normalizeState({ version: 1, devices: [], recovery: { hash, createdAt: 123 } }),
    { version: 1, devices: [], recovery: { hash, createdAt: 123 } },
  );
  assert.deepEqual(normalizeState({ version: 1, devices: [] }), { version: 1, devices: [] });
  for (const recovery of [
    { hash: 'short', createdAt: 1 },
    { hash, createdAt: 'nope' },
    { hash: 'A'.repeat(64), createdAt: 1 },
    'nope',
    null,
  ]) {
    assert.deepEqual(normalizeState({ version: 1, devices: [], recovery }), { version: 1, devices: [] });
  }
});

test('recovery: missing/wrong codes fail, failures are rate-limited, no devices needed', async (t) => {
  const stateFile = await tempStateFile(t);
  const config = testConfig({ stateFile, pairing: { maxFailedAttemptsPerMinute: 2 } });
  const pairing = new PairingStore({ config, persist: async () => {}, logger: { warn() {} } });
  assert.deepEqual(pairing.getRecoveryInfo(), { configured: false });

  const created = await pairing.createRecoveryCode();
  assert.equal(await pairing.consumeRecoveryCode('B'.repeat(52)), undefined); // failure 1
  const paired = await pairing.consumeRecoveryCode(created.code, { deviceName: 'Recovered' });
  assert.equal(typeof paired?.token, 'string');
  assert.equal(pairing.listDevices().length, 1);
  assert.equal(await pairing.consumeRecoveryCode('C'.repeat(52)), undefined); // failure 2

  await assert.rejects(
    () => pairing.consumeRecoveryCode('D'.repeat(52)),
    (error) => error instanceof ApiError && error.code === 'rate_limited' && error.status === 429,
  );

  const maxedConfig = testConfig({ stateFile: await tempStateFile(t), pairing: { maxDevices: 1 } });
  const maxed = new PairingStore({ config: maxedConfig, persist: async () => {}, logger: { warn() {} } });
  const issued = await maxed.createRecoveryCode();
  issueToken(maxed, { deviceName: 'Old device' });
  const result = await maxed.consumeRecoveryCode(issued.code, { deviceName: 'New device' });
  assert.equal(result.device.deviceName, 'New device');
  assert.equal(maxed.listDevices().length, 1);
  assert.equal(maxed.listDevices()[0].deviceName, 'New device');
});
