#!/usr/bin/env node
/**
 * tools/fake-host/attachments-test.mjs
 *
 * Self-contained test for the fake host's seeded message attachments, the
 * authenticated raw-bytes route and optional tool output:
 *   - spawns a default fake host, pairs via the real endpoints,
 *   - GET /sessions/s_release shows the seeded user message with two image
 *     attachments and one file attachment in the frozen Message.attachments
 *     shape ({id,kind,name,mimeType,bytes,width?,height?}),
 *   - a seeded tool message exposes a capped, text-only `tool.output`; a tool
 *     message without output omits the optional field; paged detail preserves it,
 *   - GET /sessions/s_release/attachments/att_seed_image returns raw PNG bytes
 *     (200, image/png, Content-Length = metadata bytes, PNG signature),
 *   - GET .../att_seed_file returns the exact text/plain fixture contents,
 *   - unknown attachment id -> 404, attachment under the wrong session -> 404,
 *   - no bearer token -> 401 unauthorized.
 *
 *   node tools/fake-host/attachments-test.mjs
 *
 * No dependencies; exits 0 only when every check passes.
 */
import net from 'node:net';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = path.dirname(fileURLToPath(import.meta.url));
const SERVER = path.join(DIR, 'server.mjs');
const NODE = process.execPath;
const PAIR_TOKEN = 'dev-token';

const IMAGE_ID = 'att_seed_image';
const IMAGE2_ID = 'att_seed_image_2';
const FILE_ID = 'att_seed_file';
const IMAGE_WIDTH = 512;
const IMAGE_HEIGHT = 512;
const IMAGE_BYTES = 3904;
const IMAGE2_WIDTH = 256;
const IMAGE2_HEIGHT = 256;
const IMAGE2_BYTES = 1163;
const FILE_TEXT = 'Release checklist\n- tests passed\n- changelog drafted\n';
const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
const TOOL_OUTPUT_MAX = 8000;
const TOOL_OUTPUT_MARKER = '… (output truncated)';

let passCount = 0;
let failCount = 0;
const failures = [];

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function assertEqual(actual, expected, label) {
  if (!Object.is(actual, expected)) {
    throw new Error(`${label}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

function assertMatch(value, pattern, label) {
  if (!pattern.test(String(value ?? ''))) {
    throw new Error(`${label}: ${JSON.stringify(value)} does not match ${pattern}`);
  }
}

async function check(name, fn) {
  try {
    const detail = await fn();
    passCount += 1;
    console.log(`[PASS] ${name}${detail ? ` - ${detail}` : ''}`);
  } catch (error) {
    failCount += 1;
    const message = error instanceof Error ? error.message : String(error);
    failures.push(`${name}: ${message}`);
    console.log(`[FAIL] ${name} - ${message}`);
  }
}

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

/** Start one fake host; returns { baseUrl, output(), stop() }. */
async function startServer(extraEnv = {}) {
  const port = await freePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  let output = '';
  const child = spawn(NODE, [SERVER], {
    cwd: DIR,
    env: {
      ...process.env,
      PORT: String(port),
      FAKE_BIND: '127.0.0.1',
      FAKE_QUIET: '1',
      FAKE_HEARTBEAT_MS: '5000',
      ...extraEnv,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });
  child.stdout.on('data', (chunk) => {
    output += chunk.toString();
  });
  child.stderr.on('data', (chunk) => {
    output += chunk.toString();
  });
  for (let attempt = 0; attempt < 100; attempt += 1) {
    try {
      const response = await fetch(`${baseUrl}/mobile/v1/health`);
      if (response.ok) {
        return {
          baseUrl,
          output: () => output,
          async stop() {
            if (child.exitCode !== null) return;
            const exited = new Promise((resolve) => child.once('exit', resolve));
            child.kill('SIGTERM');
            await Promise.race([exited, sleep(2000)]);
            if (child.exitCode === null) child.kill('SIGKILL');
          },
        };
      }
    } catch {
      // not listening yet
    }
    await sleep(100);
  }
  child.kill('SIGKILL');
  throw new Error(`fake host did not start within 10s. Log:\n${output}`);
}

async function api(baseUrl, method, requestPath, body, token) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(`${baseUrl}${requestPath}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let json = null;
  if (text) {
    try {
      json = JSON.parse(text);
    } catch {
      json = null;
    }
  }
  return { status: response.status, json, text };
}

/** Fetch raw bytes (no JSON parsing) with optional bearer token. */
async function fetchBytes(baseUrl, requestPath, token) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(`${baseUrl}${requestPath}`, { headers });
  const buffer = Buffer.from(await response.arrayBuffer());
  return {
    status: response.status,
    contentType: response.headers.get('content-type') ?? '',
    contentLength: response.headers.get('content-length') ?? '',
    contentDisposition: response.headers.get('content-disposition') ?? '',
    buffer,
  };
}

/** Pair via the real endpoints and return the device token. */
async function pair(baseUrl) {
  const started = await api(baseUrl, 'POST', '/mobile/v1/pair/new');
  assertEqual(started.status, 200, 'pair/new status');
  const code = started.json?.code;
  assertMatch(code, /^\d{6}$/, 'pair code');
  const completed = await api(baseUrl, 'POST', '/mobile/v1/pair/complete', {
    code,
    deviceName: 'QA Pixel',
    platform: 'android',
  });
  assertEqual(completed.status, 200, 'pair/complete status');
  assert(completed.json?.token, 'pair token');
  return completed.json.token;
}

async function main() {
  console.log('[attachments-test] starting fake host on a random loopback port...');
  const server = await startServer();
  console.log(`[attachments-test] fake host ready at ${server.baseUrl}`);

  let token = '';
  try {
    await check('pair/new + pair/complete return a device token', async () => {
      token = await pair(server.baseUrl);
      assertEqual(token, PAIR_TOKEN, 'token');
      return 'paired';
    });

    await check('GET /sessions/s_release exposes the seeded image + file attachment metadata', async () => {
      const detail = await api(server.baseUrl, 'GET', '/mobile/v1/sessions/s_release', undefined, token);
      assertEqual(detail.status, 200, 'session detail status');
      const messages = detail.json?.messages;
      assert(Array.isArray(messages), 'messages array');
      const carrier = messages.find((message) => Array.isArray(message.attachments) && message.attachments.length > 0);
      assert(carrier, 'a seeded message carries attachments');
      assertEqual(carrier.role, 'user', 'attachment message role');
      assertEqual(carrier.attachments.length, 3, 'three attachments');

      const image = carrier.attachments.find((attachment) => attachment.id === IMAGE_ID);
      const image2 = carrier.attachments.find((attachment) => attachment.id === IMAGE2_ID);
      const file = carrier.attachments.find((attachment) => attachment.id === FILE_ID);
      assert(image, 'image attachment present');
      assert(image2, 'second image attachment present');
      assert(file, 'file attachment present');
      for (const [label, attachment] of [
        ['image', image],
        ['second image', image2],
      ]) {
        assertEqual(
          Object.keys(attachment).sort().join(','),
          'bytes,height,id,kind,mimeType,name,width',
          `${label} attachment shape`,
        );
      }
      assertEqual(
        Object.keys(file).sort().join(','),
        'bytes,id,kind,mimeType,name',
        'file attachment shape',
      );
      assertEqual(image.kind, 'image', 'image kind');
      assertEqual(image.name, 'screenshot.png', 'image name');
      assertEqual(image.mimeType, 'image/png', 'image mimeType');
      assertEqual(image.bytes, IMAGE_BYTES, 'image bytes');
      assertEqual(image.width, IMAGE_WIDTH, 'image width');
      assertEqual(image.height, IMAGE_HEIGHT, 'image height');
      assertEqual(image2.kind, 'image', 'second image kind');
      assertEqual(image2.name, 'diagram.png', 'second image name');
      assertEqual(image2.mimeType, 'image/png', 'second image mimeType');
      assertEqual(image2.bytes, IMAGE2_BYTES, 'second image bytes');
      assertEqual(image2.width, IMAGE2_WIDTH, 'second image width');
      assertEqual(image2.height, IMAGE2_HEIGHT, 'second image height');
      assertEqual(file.kind, 'file', 'file kind');
      assertEqual(file.name, 'release-checklist.txt', 'file name');
      assertEqual(file.mimeType, 'text/plain', 'file mimeType');
      assertEqual(file.bytes, Buffer.byteLength(FILE_TEXT), 'file bytes');
      return `message=${carrier.id} image=${image.bytes}B image2=${image2.bytes}B file=${file.bytes}B`;
    });

    await check('a seeded tool message exposes capped text-only tool.output', async () => {
      const detail = await api(server.baseUrl, 'GET', '/mobile/v1/sessions/s_economy', undefined, token);
      assertEqual(detail.status, 200, 'economy session detail status');
      const messages = detail.json?.messages;
      assert(Array.isArray(messages), 'economy messages array');
      const toolMessage = messages.find((message) => message.role === 'tool' && message.tool?.name === 'Edit');
      assert(toolMessage, 'economy Edit tool message');
      const tool = toolMessage.tool;

      assertEqual(Object.keys(tool).sort().join(','), 'detail,name,output,state', 'tool shape with output');
      assertEqual(typeof tool.output, 'string', 'tool.output is a string');
      assert(tool.output.length > 0, 'tool.output is non-empty');
      assertEqual(
        tool.output.slice(0, TOOL_OUTPUT_MAX).length,
        TOOL_OUTPUT_MAX,
        'first 8000 chars retained',
      );
      assertEqual(
        tool.output.slice(TOOL_OUTPUT_MAX),
        TOOL_OUTPUT_MARKER,
        'exact host marker appended after the cap',
      );
      assertMatch(tool.output, /\(output truncated\)/, 'marker uses host parentheses form');
      assert(!tool.output.includes('[output truncated]'), 'legacy bracketed marker is gone');
      assert(
        !tool.output.slice(0, TOOL_OUTPUT_MAX).includes('output truncated'),
        'marker only appears after the cap',
      );
      assert(tool.output.length > TOOL_OUTPUT_MAX, 'total may exceed 8000 after the marker');
      assertMatch(tool.output, /economy\.ts:1: updated currency row 1/, 'tool.output contains result text');
      assert(!/data:image|base64|attachmentId|mimeType/i.test(tool.output), 'tool.output has no media payload');
      assertEqual('attachments' in toolMessage, false, 'tool message output has no attachments array');
      return `${tool.output.length} chars`;
    });

    await check('a tool message without output omits the optional field and JSON is otherwise unchanged', async () => {
      const detail = await api(server.baseUrl, 'GET', '/mobile/v1/sessions/s_release', undefined, token);
      assertEqual(detail.status, 200, 'release session detail status');
      const toolMessage = detail.json?.messages?.find(
        (message) => message.role === 'tool' && message.tool?.name === 'Bash',
      );
      assert(toolMessage, 'release Bash tool message');
      const tool = toolMessage.tool;

      assertEqual('output' in tool, false, 'tool without output omits the field');
      assertEqual(Object.keys(tool).sort().join(','), 'detail,name,state', 'tool shape unchanged');
      assertEqual(
        Object.keys(toolMessage).sort().join(','),
        'createdAt,id,role,seq,streaming,text,tool',
        'message shape otherwise unchanged',
      );
      assertEqual(toolMessage.text, '', 'tool row text stays empty');
      assertEqual(toolMessage.streaming, false, 'streaming flag unchanged');
      return tool.name;
    });

    await check('paged session detail preserves tool.output', async () => {
      const full = await api(server.baseUrl, 'GET', '/mobile/v1/sessions/s_economy', undefined, token);
      const fullTool = full.json?.messages?.find(
        (message) => message.role === 'tool' && message.tool?.name === 'Edit',
      );
      assert(fullTool, 'full-detail Edit tool message');
      const fullOutput = fullTool.tool.output;

      const page = await api(
        server.baseUrl,
        'GET',
        '/mobile/v1/sessions/s_economy?limit=2',
        undefined,
        token,
      );
      assertEqual(page.status, 200, 'paged detail status');
      assertEqual(page.json?.messages?.length, 2, 'page holds two messages');
      const pagedTool = page.json.messages.find(
        (message) => message.role === 'tool' && message.tool?.name === 'Edit',
      );
      assert(pagedTool, 'paged tool message present');
      assertEqual(pagedTool.tool.output, fullOutput, 'paged tool.output matches full detail');
      assert(page.json?.nextCursor, 'nextCursor present');

      const older = await api(
        server.baseUrl,
        'GET',
        `/mobile/v1/sessions/s_economy?limit=2&before=${encodeURIComponent(page.json.nextCursor)}`,
        undefined,
        token,
      );
      assertEqual(older.status, 200, 'older paged detail status');
      assert(older.json?.messages?.length >= 1, 'older page has messages');
      assert(
        !older.json.messages.some((message) => message.role === 'tool' && message.tool?.name === 'Edit'),
        'tool message is not in the older page',
      );
      assertEqual(older.json?.nextCursor, null, 'oldest page has no nextCursor');
      return `${pagedTool.tool.output.length} chars preserved across paging`;
    });

    await check('GET the image attachment returns raw PNG bytes with correct headers', async () => {
      const result = await fetchBytes(
        server.baseUrl,
        `/mobile/v1/sessions/s_release/attachments/${IMAGE_ID}`,
        token,
      );
      assertEqual(result.status, 200, 'image status');
      assertEqual(result.contentType.split(';')[0].trim(), 'image/png', 'image content-type');
      assertMatch(result.contentDisposition, /^inline;\s*filename="screenshot\.png"$/, 'image disposition');
      assertMatch(result.buffer.subarray(0, 8).toString('hex'), /^89504e470d0a1a0a$/, 'PNG signature');
      assertEqual(result.buffer.length, IMAGE_BYTES, 'image byte length');
      assertEqual(result.buffer.length, Number(result.contentLength), 'content-length matches body');
      return `${result.buffer.length}B, signature ok`;
    });

    await check('seeded image is a valid 512x512 PNG under 20 KB (IHDR dimensions and bytes)', async () => {
      const result = await fetchBytes(
        server.baseUrl,
        `/mobile/v1/sessions/s_release/attachments/${IMAGE_ID}`,
        token,
      );
      assertEqual(result.status, 200, 'image status');
      assertEqual(result.buffer.length, IMAGE_BYTES, 'image bytes');
      assertEqual(
        result.buffer.length > 0 && result.buffer.length < 20 * 1024,
        true,
        'image fixture under 20 KB',
      );
      // PNG layout: 8-byte signature, then the IHDR chunk: length(4) type(4)
      // width(4) height(4). The chunk type must be "IHDR" and the dimensions
      // must match the metadata.
      assertEqual(result.buffer.subarray(12, 16).toString('ascii'), 'IHDR', 'IHDR chunk type');
      assertEqual(result.buffer.readUInt32BE(16), IMAGE_WIDTH, 'IHDR width');
      assertEqual(result.buffer.readUInt32BE(20), IMAGE_HEIGHT, 'IHDR height');
      return `IHDR ${result.buffer.readUInt32BE(16)}x${result.buffer.readUInt32BE(20)}, ${result.buffer.length}B`;
    });

    await check('the second seeded image downloads as a valid 256x256 PNG (multiple-image paging fixture)', async () => {
      const result = await fetchBytes(
        server.baseUrl,
        `/mobile/v1/sessions/s_release/attachments/${IMAGE2_ID}`,
        token,
      );
      assertEqual(result.status, 200, 'second image status');
      assertEqual(result.contentType.split(';')[0].trim(), 'image/png', 'second image content-type');
      assertMatch(result.contentDisposition, /^inline;\s*filename="diagram\.png"$/, 'second image disposition');
      assertMatch(result.buffer.subarray(0, 8).toString('hex'), /^89504e470d0a1a0a$/, 'second PNG signature');
      assertEqual(result.buffer.length, IMAGE2_BYTES, 'second image bytes');
      assertEqual(result.buffer.length, Number(result.contentLength), 'second content-length matches body');
      assertEqual(result.buffer.subarray(12, 16).toString('ascii'), 'IHDR', 'second IHDR chunk type');
      assertEqual(result.buffer.readUInt32BE(16), IMAGE2_WIDTH, 'second IHDR width');
      assertEqual(result.buffer.readUInt32BE(20), IMAGE2_HEIGHT, 'second IHDR height');
      assert(
        result.buffer.subarray(0, 64).compare(Buffer.alloc(64)) !== 0,
        'second image has non-empty pixel data',
      );
      return `IHDR ${IMAGE2_WIDTH}x${IMAGE2_HEIGHT}, ${result.buffer.length}B`;
    });

    await check('GET the file attachment returns the exact text/plain bytes', async () => {
      const result = await fetchBytes(
        server.baseUrl,
        `/mobile/v1/sessions/s_release/attachments/${FILE_ID}`,
        token,
      );
      assertEqual(result.status, 200, 'file status');
      assertEqual(result.contentType.split(';')[0].trim(), 'text/plain', 'file content-type');
      assertMatch(result.contentDisposition, /^attachment;\s*filename="release-checklist\.txt"$/, 'file disposition');
      assertEqual(result.buffer.toString('utf8'), FILE_TEXT, 'file contents');
      return `${result.buffer.length}B text/plain`;
    });

    await check('unknown attachment id answers 404 with a JSON error', async () => {
      const result = await api(
        server.baseUrl,
        'GET',
        '/mobile/v1/sessions/s_release/attachments/att_does_not_exist',
        undefined,
        token,
      );
      assertEqual(result.status, 404, 'unknown id status');
      assertEqual(result.json?.error?.code, 'not_found', 'unknown id code');
      return 'unknown id -> 404';
    });

    await check('attachment under the wrong session answers 404', async () => {
      const result = await api(
        server.baseUrl,
        'GET',
        `/mobile/v1/sessions/s_android/attachments/${IMAGE_ID}`,
        undefined,
        token,
      );
      assertEqual(result.status, 404, 'wrong session status');
      assertEqual(result.json?.error?.code, 'not_found', 'wrong session code');
      return 'wrong session -> 404';
    });

    await check('attachment bytes without a bearer token answer 401 unauthorized', async () => {
      const result = await fetchBytes(server.baseUrl, `/mobile/v1/sessions/s_release/attachments/${IMAGE_ID}`);
      assertEqual(result.status, 401, 'unauthorized status');
      const json = JSON.parse(result.buffer.toString('utf8'));
      assertEqual(json?.error?.code, 'unauthorized', 'unauthorized code');
      return 'no token -> 401';
    });
  } finally {
    await server.stop();
  }

  console.log('');
  console.log(`[attachments-test] ${passCount} passed, ${failCount} failed`);
  if (failCount > 0) {
    console.log('[attachments-test] failures:');
    for (const failure of failures) console.log(`  - ${failure}`);
    process.exitCode = 1;
  } else {
    console.log('[attachments-test] OK');
  }
}

main().catch((error) => {
  console.error('[attachments-test] fatal:', error);
  process.exitCode = 1;
});
