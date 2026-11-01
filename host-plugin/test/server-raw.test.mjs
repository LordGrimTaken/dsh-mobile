import test from 'node:test';
import assert from 'node:assert/strict';

import { createMobileServer } from '../lib/server.js';
import { testConfig } from './helpers.mjs';

const silent = { info() {}, warn() {}, error() {} };

test('the server sends raw attachment responses (buffer and streamed chunks)', async (t) => {
  const config = testConfig({ port: 0 });
  const router = {
    async handle(request) {
      if (request.pathname === '/raw') {
        return {
          status: 200,
          headers: {
            'Content-Type': 'application/pdf',
            'Content-Disposition': 'attachment; filename="report.pdf"',
          },
          raw: new Uint8Array([1, 2, 3, 4]),
        };
      }
      if (request.pathname === '/stream') {
        return {
          status: 200,
          headers: { 'Content-Type': 'application/octet-stream', 'Content-Length': '4' },
          raw: (async function* chunks() {
            yield Buffer.from([9, 8]);
            yield Buffer.from([7, 6]);
          })(),
        };
      }
      if (request.pathname === '/json') return { status: 200, body: { ok: true } };
      return { status: 404, body: { error: { code: 'not_found', message: 'Unknown endpoint' } } };
    },
  };
  const server = createMobileServer({ config, router, bus: null, adapter: {}, logger: silent });
  const address = await server.start();
  t.after(() => server.close());
  const base = `http://127.0.0.1:${address.port}`;

  const raw = await fetch(`${base}/raw`);
  assert.equal(raw.status, 200);
  assert.equal(raw.headers.get('content-type'), 'application/pdf');
  assert.equal(raw.headers.get('content-disposition'), 'attachment; filename="report.pdf"');
  assert.equal(raw.headers.get('content-length'), '4');
  assert.equal(raw.headers.get('cache-control'), 'no-store');
  assert.equal(raw.headers.get('x-content-type-options'), 'nosniff');
  assert.deepEqual([...new Uint8Array(await raw.arrayBuffer())], [1, 2, 3, 4]);

  const streamed = await fetch(`${base}/stream`);
  assert.equal(streamed.status, 200);
  assert.equal(streamed.headers.get('content-length'), '4');
  assert.deepEqual([...new Uint8Array(await streamed.arrayBuffer())], [9, 8, 7, 6]);

  const json = await fetch(`${base}/json`);
  assert.deepEqual(await json.json(), { ok: true });
  assert.match(json.headers.get('content-type'), /application\/json/);
});
