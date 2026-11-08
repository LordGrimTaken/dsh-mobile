import test from 'node:test';
import assert from 'node:assert/strict';

import { PAIR_PAGE_CSP, qrLibrarySource } from '../lib/pair-page.js';
import { createRouter } from '../lib/router.js';
import { createFakeAdapter, makePairing, routeCall, testConfig } from './helpers.mjs';

const silent = { info() {}, warn() {}, error() {} };

function setup(configOverrides = {}) {
  const config = testConfig(configOverrides);
  const pairing = makePairing(config);
  const adapter = createFakeAdapter();
  const router = createRouter({ config, pairing, adapter, logger: silent });
  return { config, pairing, adapter, router };
}

/** @param {{ raw?: Uint8Array }} result */
function textOf(result) {
  return new TextDecoder().decode(result.raw ?? new Uint8Array());
}

test('pairing page: served as HTML on loopback with a strict CSP and no inline script', async () => {
  const { router } = setup({ publicBaseUrl: 'https://pc.tailnet.ts.net' });
  const result = await routeCall(router, { path: '/pair' });

  assert.equal(result.status, 200);
  assert.equal(result.headers['Content-Type'], 'text/html; charset=utf-8');
  assert.equal(result.headers['Content-Security-Policy'], PAIR_PAGE_CSP);
  assert.equal(result.headers['Cache-Control'], 'no-store');
  assert.equal(result.headers['X-Frame-Options'], 'DENY');
  assert.equal(result.headers['X-Content-Type-Options'], 'nosniff');
  assert.equal(result.body, undefined, 'HTML must be raw bytes, never JSON');

  const html = textOf(result);
  assert.match(html, /Connect a phone/);
  assert.match(html, /<!doctype html>/i);
  assert.match(html, /\/pair\/app\.js/);
  assert.match(html, /\/pair\/qrcode-generator\.js/);
  assert.equal(html.includes('<script>'), false, 'inline script would violate the CSP');
  // The page is static: it must not embed a pairing code, token or host URL.
  assert.equal(/[0-9]{6}/.test(html), false);
  assert.equal(html.includes('pc.tailnet.ts.net'), false);
});

test('pairing page: trailing slash serves the same page', async () => {
  const { router } = setup();
  const plain = await routeCall(router, { path: '/pair' });
  const slashed = await routeCall(router, { path: '/pair/' });
  assert.equal(slashed.status, 200);
  assert.equal(textOf(slashed), textOf(plain));
});

test('pairing page controller: asks the same host for a fresh code', async () => {
  const { router } = setup();
  const result = await routeCall(router, { path: '/pair/app.js' });

  assert.equal(result.status, 200);
  assert.equal(result.headers['Content-Type'], 'application/javascript; charset=utf-8');
  const source = textOf(result);
  assert.match(source, /fetch\('\/mobile\/v1\/pair\/new'/);
  assert.match(source, /createSvgTag/);
});

test('pairing page: QR encoder is vendored and served locally', async () => {
  const { router } = setup();
  const result = await routeCall(router, { path: '/pair/qrcode-generator.js' });

  assert.equal(result.status, 200);
  assert.equal(result.headers['Content-Type'], 'application/javascript; charset=utf-8');
  const source = textOf(result);
  assert.equal(source.length > 10_000, true, 'vendored QR encoder source is served');
  assert.match(source, /createSvgTag/);
  assert.match(source, /MIT license/i, 'licence header must stay with the vendored file');
  assert.equal(source, qrLibrarySource());
});

test('pairing page: mint call still yields a scannable payload', async () => {
  const { router, config } = setup({ publicBaseUrl: 'https://pc.tailnet.ts.net' });
  const minted = await routeCall(router, { method: 'POST', path: '/mobile/v1/pair/new' });

  assert.equal(minted.status, 200);
  assert.match(minted.body.code, /^[0-9]{6}$/);
  assert.equal(
    minted.body.qrPayload,
    `dshmobile://pair?base=https%3A%2F%2Fpc.tailnet.ts.net&code=${minted.body.code}&host=${encodeURIComponent(config.hostName)}`,
  );
  assert.equal(Number.isNaN(Date.parse(minted.body.expiresAt)), false);
});
