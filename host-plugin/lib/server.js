/**
 * node:http carrier for the Mobile API.
 *
 * The carrier owns sockets, body limits, JSON encoding, and the SSE stream; all
 * routing decisions stay in lib/router.js. Binds loopback only (validated by
 * resolveConfig) and is closed through the owning plugin effect.
 */

import http from 'node:http';
import zlib from 'node:zlib';

import { ApiError, errorResponse } from './errors.js';
import { formatLiveFrame, formatPingFrame } from './events.js';
import { isoTime } from './mappers.js';

/**
 * @param {{
 *   config: any,
 *   router: ReturnType<import('./router.js').createRouter>,
 *   bus: import('./events.js').EventBus,
 *   adapter: ReturnType<import('./dsh-adapter.js').createDshAdapter>,
 *   pairing?: import('./pairing.js').PairingStore | null,
 *   logger?: Pick<Console, 'warn' | 'error' | 'info'>,
 * }} options
 */
export function createMobileServer({ config, router, bus, adapter, pairing = null, logger = console }) {
  /** @type {http.Server | null} */
  let server = null;
  /** @type {Map<string, Set<() => void>>} SSE closers keyed by authenticated device id. */
  const streamsByDevice = new Map();
  /** @type {(() => void) | null} */
  let unsubscribeRevoke = null;
  if (typeof pairing?.onRevoke === 'function') {
    unsubscribeRevoke = pairing.onRevoke((revokedDeviceId) => {
      const streams = streamsByDevice.get(String(revokedDeviceId));
      if (!streams) return;
      for (const close of [...streams]) close();
    });
  }

  /**
   * Read a request body with a hard byte cap.
   * @param {http.IncomingMessage} req
   */
  function readBody(req) {
    return new Promise((resolve, reject) => {
      /** @type {Buffer[]} */
      const chunks = [];
      let size = 0;
      let settled = false;
      const fail = (error) => {
        if (settled) return;
        settled = true;
        reject(error);
      };
      req.on('data', (chunk) => {
        if (settled) return;
        size += chunk.length;
        if (size > config.http.maxBodyBytes) {
          fail(new ApiError('invalid_request', 'Request body is too large'));
          req.destroy();
          return;
        }
        chunks.push(chunk);
      });
      req.on('end', () => {
        if (settled) return;
        settled = true;
        resolve(Buffer.concat(chunks).toString('utf8'));
      });
      req.on('error', fail);
      req.on('aborted', () => fail(new ApiError('invalid_request', 'Request aborted')));
    });
  }

  /** @param {http.ServerResponse} res @param {{ status: number, body?: unknown, headers?: Record<string, string> }} result */
  function sendJson(res, result) {
    if (res.writableEnded || res.destroyed) return;
    if (result.status === 304) {
      res.writeHead(304, result.headers ?? {});
      res.end();
      return;
    }
    let body = Buffer.from(JSON.stringify(result.body ?? {}), 'utf8');
    const headers = {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
      ...(result.headers ?? {}),
    };
    // Snapshot/transcript payloads dominate slow-link time; gzip them when the client advertises
    // support. Small responses stay uncompressed so headers don't outweigh the body.
    const acceptEncoding = String(res.req?.headers?.['accept-encoding'] ?? '');
    const alreadyEncoded = headers['Content-Encoding'] || headers['content-encoding'];
    if (!alreadyEncoded && body.length >= 1024 && /\bgzip\b/.test(acceptEncoding)) {
      const compressed = zlib.gzipSync(body);
      body = compressed;
      headers['Content-Encoding'] = 'gzip';
      headers.Vary = 'Accept-Encoding';
    }
    headers['Content-Length'] = body.length;
    res.writeHead(result.status, headers);
    res.end(body);
  }

  /**
   * Send one raw/binary route result (attachment bytes). Owns no paths: the
   * router/adapter descriptor already carries headers and a body source.
   *
   * @param {http.ServerResponse} res
   * @param {{ status: number, headers?: Record<string, string>, raw: Uint8Array | ArrayBuffer | AsyncIterable<Uint8Array | string> }} result
   */
  async function sendRaw(res, result) {
    if (res.writableEnded || res.destroyed) return;
    const headers = {
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
      ...(result.headers ?? {}),
    };
    const raw = result.raw;
    if (raw instanceof Uint8Array || raw instanceof ArrayBuffer) {
      const data = raw instanceof ArrayBuffer ? new Uint8Array(raw) : raw;
      res.writeHead(result.status, { ...headers, 'Content-Length': String(data.byteLength) });
      res.end(Buffer.from(data));
      return;
    }
    res.writeHead(result.status, headers);
    try {
      for await (const chunk of raw) {
        if (res.writableEnded || res.destroyed) return;
        res.write(typeof chunk === 'string' ? chunk : Buffer.from(chunk));
      }
      res.end();
    } catch (error) {
      // Headers already reached the client: abort the socket so a truncated
      // download can never be mistaken for a complete file.
      logger.warn?.(
        `[dsh-mobile-host] raw response stream failed: ${String(/** @type {any} */ (error)?.message ?? error)}`,
      );
      res.destroy();
    }
  }

  /**
   * Open one Server-Sent-Events stream.
   * @param {http.IncomingMessage} req
   * @param {http.ServerResponse} res
   * @param {string | undefined} since
   * @param {string | undefined} deviceId authenticated device id (revocation closes the stream)
   */
  function openEventStream(req, res, since, deviceId) {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
      'X-Content-Type-Options': 'nosniff',
    });
    // Tell the client how fast to retry after a dropped stream.
    res.write('retry: 3000\n\n');

    /** @param {string} text */
    const write = (text) => {
      if (res.writableEnded || res.destroyed) return;
      try {
        res.write(text);
      } catch {
        /* stream already gone */
      }
    };

    /** @param {Record<string, any>} envelope */
    const sendEnvelope = (envelope) => {
      write(`id: ${String(envelope.id ?? '')}\nevent: ${String(envelope.type ?? '')}\ndata: ${JSON.stringify(envelope)}\n\n`);
    };

    /** @param {string} type @param {unknown} data */
    const sendDirect = (type, data) => {
      write(formatLiveFrame(type, data));
    };

    // Every connection starts with the liveness marker.
    sendDirect('host.status', { connected: true });

    let closed = false;
    /** @type {() => void} */
    let unsubscribe = () => {};
    /** @type {NodeJS.Timeout | null} */
    let heartbeat = null;

    const repair = () => {
      // Cursor fell out of the replay buffer: send a full snapshot and let the
      // client refetch details. Snapshot frames carry no `id:` so they never
      // advance the resume cursor into ids the buffer does not know.
      void adapter
        .listSessions()
        .then((sessions) => {
          if (closed) return;
          for (const session of sessions) {
            write(`event: session.updated\ndata: ${JSON.stringify({ type: 'session.updated', at: isoTime(Date.now()), sessionId: session.id, data: session })}\n\n`);
          }
        })
        .catch((error) => {
          logger.warn?.(`[dsh-mobile-host] SSE resync snapshot failed: ${String(/** @type {any} */ (error)?.message ?? error)}`);
        });
    };

    if (typeof since === 'string' && since !== '') {
      const replay = bus.replaySince(since);
      if (replay.found) {
        for (const event of replay.events) sendEnvelope(event);
      } else {
        repair();
      }
    }

    unsubscribe = bus.subscribe(sendEnvelope);
    heartbeat = setInterval(() => write(formatPingFrame()), config.sse.heartbeatMs);
    heartbeat.unref?.();

    /** @type {() => void} */
    let unregisterStream = () => {};
    const cleanup = () => {
      if (closed) return;
      closed = true;
      unsubscribe();
      if (heartbeat) clearInterval(heartbeat);
      heartbeat = null;
      unregisterStream();
    };
    req.on('close', cleanup);
    res.on('close', cleanup);
    res.on('error', cleanup);

    if (deviceId) {
      // Track this stream by device so a revocation can end it immediately
      // instead of leaving the already-authenticated connection alive.
      const closeForRevoke = () => {
        if (closed) return;
        try {
          res.end();
        } catch {
          /* stream already gone */
        }
        cleanup();
      };
      const streams = streamsByDevice.get(deviceId) ?? new Set();
      streams.add(closeForRevoke);
      streamsByDevice.set(deviceId, streams);
      unregisterStream = () => {
        const current = streamsByDevice.get(deviceId);
        if (!current) return;
        current.delete(closeForRevoke);
        if (current.size === 0) streamsByDevice.delete(deviceId);
      };
    }
  }

  /** @param {http.IncomingMessage} req @param {http.ServerResponse} res */
  async function handleRequest(req, res) {
    // Parse only path/query; the request's own host header is irrelevant.
    const url = new URL(req.url ?? '/', 'http://localhost');
    const headers = {};
    for (const [key, value] of Object.entries(req.headers)) {
      if (value !== undefined) headers[key] = value;
    }

    let body;
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      try {
        body = await readBody(req);
      } catch (error) {
        sendJson(res, errorResponse(error instanceof ApiError ? error : new ApiError('invalid_request', 'Could not read request body')));
        return;
      }
    }

    const result = await router.handle({
      method: req.method ?? 'GET',
      pathname: url.pathname,
      query: url.searchParams,
      headers,
      body,
      remoteAddress: req.socket.remoteAddress ?? '',
    });

    if (result.sse) {
      openEventStream(req, res, result.since, result.deviceId);
      return;
    }
    if (result.raw !== undefined) {
      await sendRaw(res, result);
      return;
    }
    sendJson(res, result);
  }

  /** Start listening. Resolves with the bound address. */
  async function start() {
    if (server) return server.address();
    const instance = http.createServer((req, res) => {
      handleRequest(req, res).catch((error) => {
        logger.error?.(`[dsh-mobile-host] request carrier failure: ${String(/** @type {any} */ (error)?.stack ?? error)}`);
        try {
          sendJson(res, errorResponse(new ApiError('internal', 'Internal host error')));
        } catch {
          res.destroy();
        }
      });
    });
    instance.requestTimeout = config.http.requestTimeoutMs;
    // Node requires headersTimeout <= requestTimeout.
    instance.headersTimeout = Math.min(config.http.requestTimeoutMs, 10_000);
    instance.keepAliveTimeout = 5_000;
    instance.on('clientError', (_error, socket) => {
      socket.destroy();
    });

    await new Promise((resolve, reject) => {
      const onError = (error) => {
        instance.off('listening', onListening);
        reject(error);
      };
      const onListening = () => {
        instance.off('error', onError);
        resolve(undefined);
      };
      instance.once('error', onError);
      instance.once('listening', onListening);
      instance.listen(config.port, config.host);
    });
    server = instance;
    return instance.address();
  }

  /** Stop listening and terminate open SSE streams. */
  async function close() {
    const instance = server;
    if (!instance) return;
    server = null;
    unsubscribeRevoke?.();
    unsubscribeRevoke = null;
    instance.closeAllConnections?.();
    await new Promise((resolve) => instance.close(() => resolve(undefined)));
  }

  /** Bound `{ address, port }` or null. */
  function address() {
    return server?.address() ?? null;
  }

  return { start, close, address, handleRequest };
}
