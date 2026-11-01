import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';

import {
  RemoteImageFetcher,
  classifyRemoteAddress,
  detectImageMagic,
  parseRemoteImageUrl,
  requestRemoteImage,
  REMOTE_IMAGE_MAX_REDIRECTS,
} from '../lib/remote-images.js';

const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0]);
const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0, 0]);
const GIF = Buffer.from([0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0, 0]);
const WEBP = Buffer.concat([
  Buffer.from('RIFF', 'latin1'),
  Buffer.from([0, 0, 0, 0]),
  Buffer.from('WEBP', 'latin1'),
  Buffer.from([0, 0]),
]);

function response(statusCode, headers, body) {
  return { statusCode, headers, body };
}

/**
 * @param {{ status?: number, headers?: Record<string, string>, body?: Buffer, redirects?: Array<{status:number, location?:string}> }} [plan]
 */
function makeFetchHarness(plan = {}) {
  const calls = [];
  const redirects = [...(plan.redirects ?? [])];
  const fetcher = new RemoteImageFetcher({
    maxBytes: plan.maxBytes ?? 1024,
    timeoutMs: 1000,
    ttlMs: 60_000,
    cacheEntries: plan.cacheEntries ?? 4,
    lookup: async (hostname) => {
      calls.push({ type: 'lookup', hostname });
      return plan.addresses ?? ['93.184.216.34'];
    },
    request: async (url, context) => {
      calls.push({ type: 'request', url: url.toString(), address: context.address, family: context.family });
      const redirect = redirects.shift();
      if (redirect) {
        return response(redirect.status, { location: redirect.location ?? '' }, Buffer.alloc(0));
      }
      return response(plan.status ?? 200, plan.headers ?? { 'content-type': 'image/png' }, plan.body ?? PNG);
    },
  });
  return { fetcher, calls };
}

test('parseRemoteImageUrl enforces the narrow http(s) image policy', () => {
  const parsed = parseRemoteImageUrl('https://cdn.example.com/pics/Cat.PNG?x=1');
  assert.equal(parsed.mimeType, 'image/png');
  assert.equal(parsed.name, 'Cat.PNG');
  assert.equal(parsed.id, parseRemoteImageUrl('https://cdn.example.com/pics/Cat.PNG?x=1').id);
  assert.notEqual(parsed.id, parseRemoteImageUrl('https://cdn.example.com/pics/dog.png').id);
  assert.equal(parsed.url.toString(), 'https://cdn.example.com/pics/Cat.PNG?x=1');
  assert.equal(parsed.url.search, '?x=1');

  for (const bad of [
    'http://cdn.example.com/a.svg',
    'http://cdn.example.com/a.txt',
    'http://cdn.example.com/a.png#fragment',
    'http://user:pass@cdn.example.com/a.png',
    'http://cdn.example.com:8080/a.png',
    'file:///C:/a.png',
    'data:image/png;base64,AAAA',
    'ftp://cdn.example.com/a.png',
    'http://cdn.example.com/a.png.',
    'http://cdn.example.com.',
    'not a url',
    '',
    null,
  ]) {
    assert.equal(parseRemoteImageUrl(bad), null, `must reject ${String(bad)}`);
  }
  assert.equal(parseRemoteImageUrl('http://cdn.example.com/a.jpg:stream'), null, 'ADS rejected');
  assert.equal(parseRemoteImageUrl('http://cdn.example.com/a.png').mimeType, 'image/png');
  assert.equal(parseRemoteImageUrl('http://cdn.example.com/a.JPEG').mimeType, 'image/jpeg');
  assert.equal(parseRemoteImageUrl('http://cdn.example.com/a.webp').mimeType, 'image/webp');
  assert.equal(parseRemoteImageUrl('http://cdn.example.com/a.gif').mimeType, 'image/gif');
});

test('classifyRemoteAddress rejects private, loopback, link-local, ULA and mapped ranges', () => {
  for (const address of [
    '127.0.0.1',
    '127.255.255.255',
    '10.0.0.1',
    '172.16.0.1',
    '172.31.255.255',
    '192.168.1.1',
    '169.254.169.254',
    '100.64.0.1',
    '0.0.0.0',
    '198.18.0.1',
    '198.51.100.1',
    '203.0.113.1',
    '224.0.0.1',
    '255.255.255.255',
    '::1',
    '::',
    'fc00::1',
    'fd12:3456::1',
    'fe80::1%eth0',
    'ff02::1',
    '::ffff:127.0.0.1',
    '64:ff9b::7f00:1',
    '64:ff9b:1::1',
    '2002:7f00:1::',
    '2002:0a00:1::',
    'fec0::1',
    '2001:10::1',
    '2001:20::1',
    '3fff::1',
    '2001:0000:4136:e378::1',
    '2001:0:4136:e378:8000:63bf:3fff:fdd2',
    '2001:db8::1',
    'not-an-ip',
    '',
  ]) {
    assert.equal(classifyRemoteAddress(address).allowed, false, `${address} must be rejected`);
  }
  for (const address of [
    '1.1.1.1',
    '8.8.8.8',
    '93.184.216.34',
    '2606:4700:4700::1111',
    '2001:4860:4860::8888',
    '2002:0808:0808::',
  ]) {
    assert.equal(classifyRemoteAddress(address).allowed, true, `${address} must be accepted`);
  }
});

test('fetcher pins the validated address, follows bounded redirects and rejects bad content', async () => {
  const success = makeFetchHarness();
  const image = await success.fetcher.get('https://cdn.example.com/cat.png');
  assert.deepEqual(image.bytes, PNG);
  assert.equal(image.mimeType, 'image/png');
  assert.equal(image.name, 'cat.png');
  assert.equal(success.calls.find((call) => call.type === 'request').address, '93.184.216.34');

  // Cache: the second read does not fetch again.
  const before = success.calls.filter((call) => call.type === 'request').length;
  await success.fetcher.get('https://cdn.example.com/cat.png');
  assert.equal(success.calls.filter((call) => call.type === 'request').length, before);

  const redirect = makeFetchHarness({
    redirects: [{ status: 302, location: 'https://cdn.example.com/moved.png' }],
  });
  const redirected = await redirect.fetcher.get('https://cdn.example.com/cat.png');
  assert.deepEqual(redirected.bytes, PNG);
  assert.equal(redirect.calls.filter((call) => call.type === 'request').length, 2);

  for (const badRedirect of [
    { status: 302, location: 'https://cdn.example.com/moved.txt' },
    { status: 302, location: 'ftp://cdn.example.com/moved.png' },
    { status: 302, location: '' },
  ]) {
    const harness = makeFetchHarness({ redirects: [badRedirect] });
    await assert.rejects(() => harness.fetcher.get('https://cdn.example.com/cat.png'));
    assert.equal(harness.calls.filter((call) => call.type === 'request').length, 1);
  }

  const tooMany = makeFetchHarness({
    redirects: Array.from({ length: REMOTE_IMAGE_MAX_REDIRECTS + 1 }, (_, index) => ({
      status: 302,
      location: `https://cdn.example.com/${index}.png`,
    })),
  });
  await assert.rejects(() => tooMany.fetcher.get('https://cdn.example.com/start.png'));

  for (const plan of [
    { body: Buffer.alloc(0) },
    { body: Buffer.from('not an image'), headers: { 'content-type': 'image/png' } },
    { body: JPEG, headers: { 'content-type': 'image/png' } },
    { body: PNG, headers: { 'content-type': 'text/html' } },
    // C6: declared type must exactly match the magic bytes, not just be image/*.
    { body: PNG, headers: { 'content-type': 'image/svg+xml' } },
    { body: PNG, headers: { 'content-type': 'image/jpeg' } },
    { body: Buffer.concat([PNG, Buffer.alloc(2048)]) },
  ]) {
    const harness = makeFetchHarness(plan);
    await assert.rejects(() => harness.fetcher.get('https://cdn.example.com/cat.png'));
  }

  // Absent Content-Type is allowed when the magic matches the extension.
  const noType = makeFetchHarness({ headers: {}, body: PNG });
  assert.equal((await noType.fetcher.get('https://cdn.example.com/cat.png')).mimeType, 'image/png');

  const jpeg = makeFetchHarness({ body: JPEG, headers: { 'content-type': 'image/jpeg' } });
  assert.equal((await jpeg.fetcher.get('https://cdn.example.com/cat.jpg')).mimeType, 'image/jpeg');

  const webp = makeFetchHarness({ body: WEBP, headers: { 'content-type': 'image/webp' } });
  assert.equal((await webp.fetcher.get('https://cdn.example.com/cat.webp')).mimeType, 'image/webp');
  const gif = makeFetchHarness({ body: GIF, headers: { 'content-type': 'image/gif' } });
  assert.equal((await gif.fetcher.get('https://cdn.example.com/cat.gif')).mimeType, 'image/gif');
});

test('fetcher rejects mixed/private DNS answers, IP literals and rebinding before connecting', async () => {
  const mixed = makeFetchHarness({ addresses: ['93.184.216.34', '127.0.0.1'] });
  await assert.rejects(
    () => mixed.fetcher.get('https://cdn.example.com/cat.png'),
    (error) => !String(error.message).includes('cdn.example.com'),
  );
  assert.equal(mixed.calls.filter((call) => call.type === 'request').length, 0);

  const privateOnly = makeFetchHarness({ addresses: ['10.0.0.5'] });
  await assert.rejects(() => privateOnly.fetcher.get('https://cdn.example.com/cat.png'));
  assert.equal(privateOnly.calls.filter((call) => call.type === 'request').length, 0);

  const literal = makeFetchHarness();
  const direct = await literal.fetcher.get('https://8.8.8.8/cat.png');
  assert.deepEqual(direct.bytes, PNG);
  assert.equal(literal.calls.filter((call) => call.type === 'lookup').length, 0);
  const localLiteral = makeFetchHarness();
  await assert.rejects(() => localLiteral.fetcher.get('https://127.0.0.1/cat.png'));
  assert.equal(localLiteral.calls.filter((call) => call.type === 'request').length, 0);
});

test('fetcher timeout/error is generic and cache is bounded', async () => {
  const failing = new RemoteImageFetcher({
    lookup: async () => ['93.184.216.34'],
    request: async () => {
      throw new Error('timeout');
    },
  });
  await assert.rejects(
    () => failing.get('https://cdn.example.com/cat.png'),
    (error) => !String(error.message).includes('cdn.example.com') && !String(error.message).includes('93.184'),
  );

  let now = 1000;
  const stamps = [];
  const fetcher = new RemoteImageFetcher({
    cacheEntries: 1,
    ttlMs: 10_000,
    maxBytes: 1024,
    now: () => now,
    lookup: async () => ['93.184.216.34'],
    request: async (url) => {
      stamps.push(url.pathname);
      return response(200, { 'content-type': 'image/png' }, PNG);
    },
  });
  await fetcher.get('https://cdn.example.com/one.png');
  await fetcher.get('https://cdn.example.com/two.png');
  assert.equal(fetcher.cache.size, 1);
  await fetcher.get('https://cdn.example.com/one.png'); // evicted → refetch
  assert.deepEqual(stamps, ['/one.png', '/two.png', '/one.png']);
  now += 20_000;
  await fetcher.get('https://cdn.example.com/one.png'); // TTL expired → refetch
  assert.equal(stamps.at(-1), '/one.png');
});

test('fetcher caps global concurrency and negatively caches failures', async () => {
  let active = 0;
  let maxActive = 0;
  let requests = 0;
  const fetcher = new RemoteImageFetcher({
    maxConcurrent: 4,
    maxBytes: 1024,
    lookup: async () => ['93.184.216.34'],
    request: async () => {
      requests += 1;
      active += 1;
      maxActive = Math.max(maxActive, active);
      await new Promise((resolve) => setTimeout(resolve, 5));
      active -= 1;
      return response(200, { 'content-type': 'image/png' }, PNG);
    },
  });
  await Promise.all(Array.from({ length: 8 }, (_, index) => fetcher.get(`https://cdn.example.com/${index}.png`)));
  assert.equal(requests, 8);
  assert.equal(maxActive <= 4, true, `at most 4 fetches in flight (saw ${maxActive})`);
  assert.equal(maxActive >= 2, true, 'fetches should actually overlap');

  let now = 1000;
  let failures = 0;
  const failing = new RemoteImageFetcher({
    negativeTtlMs: 1000,
    now: () => now,
    lookup: async () => ['93.184.216.34'],
    request: async () => {
      failures += 1;
      throw new Error('network failed');
    },
  });
  await assert.rejects(() => failing.get('https://cdn.example.com/broken.png'));
  await assert.rejects(() => failing.get('https://cdn.example.com/broken.png'));
  assert.equal(failures, 1, 'negative cache suppresses retry storms inside the TTL');
  now += 2000;
  await assert.rejects(() => failing.get('https://cdn.example.com/broken.png'));
  assert.equal(failures, 2, 'after the negative TTL the URL is retried');
});

test('transport abandons redirect bodies immediately and enforces streaming size', async (t) => {
  let streamTimer = null;
  const redirectServer = http.createServer((request, response) => {
    response.on('error', () => {});
    response.writeHead(302, { Location: '/moved.png', 'Content-Type': 'text/html' });
    response.write(Buffer.alloc(1024));
    streamTimer = setInterval(() => {
      try {
        response.write(Buffer.alloc(1024));
      } catch {
        /* client already aborted */
      }
    }, 10);
    streamTimer.unref?.();
  });
  await new Promise((resolve) => redirectServer.listen(0, '127.0.0.1', resolve));
  t.after(() => {
    if (streamTimer) clearInterval(streamTimer);
    redirectServer.close();
  });
  const redirectPort = redirectServer.address().port;
  const started = Date.now();
  const redirect = await requestRemoteImage(new URL(`http://127.0.0.1:${redirectPort}/a.png`), {
    address: '127.0.0.1',
    family: 4,
    maxBytes: 1024 * 1024,
    timeoutMs: 1000,
  });
  assert.equal(redirect.statusCode, 302);
  assert.equal(redirect.body.length, 0, 'redirect body is not buffered');
  assert.equal(Date.now() - started < 500, true);

  const largeServer = http.createServer((request, response) => {
    response.writeHead(200, { 'Content-Type': 'image/png' });
    response.end(Buffer.concat([PNG, Buffer.alloc(4096)]));
  });
  await new Promise((resolve) => largeServer.listen(0, '127.0.0.1', resolve));
  t.after(() => largeServer.close());
  const largePort = largeServer.address().port;
  await assert.rejects(
    () =>
      requestRemoteImage(new URL(`http://127.0.0.1:${largePort}/big.png`), {
        address: '127.0.0.1',
        family: 4,
        maxBytes: 128,
        timeoutMs: 1000,
      }),
    (error) => !String(error.message).includes('127.0.0.1'),
  );
});

test('detectImageMagic matches only the allowed formats', () => {
  assert.equal(detectImageMagic('.png', PNG), 'image/png');
  assert.equal(detectImageMagic('.jpg', JPEG), 'image/jpeg');
  assert.equal(detectImageMagic('.jpeg', JPEG), 'image/jpeg');
  assert.equal(detectImageMagic('.gif', GIF), 'image/gif');
  assert.equal(detectImageMagic('.webp', WEBP), 'image/webp');
  assert.equal(detectImageMagic('.png', JPEG), null);
  assert.equal(detectImageMagic('.png', Buffer.alloc(0)), null);
});
