/**
 * DSH Host plugin entry point for the DSH Mobile API v1.
 *
 * The plugin starts a loopback-only HTTP server (`127.0.0.1:3543` by default)
 * that the phone reaches through Tailscale Serve. It never exposes DSH's own
 * web server or launch token: devices authenticate with their own bearer token
 * issued through the pairing flow.
 *
 * Everything DSH-specific is isolated in lib/dsh-adapter.js. This entry only:
 *   1. resolves config + persisted pairing state,
 *   2. creates the event bus / pairing store / attachment store / DSH adapter,
 *   3. starts the HTTP carrier inside a Cordis effect (so plugin unload stops it),
 *   4. wires DSH events → SSE through the adapter disposer.
 *
 * No DSH package imports: the module loads with Node built-ins alone, which
 * keeps it unit-testable offline (test/plugin.test.mjs uses a fake ctx).
 *
 * Load safety: `apply` never throws. A bad row config, a failed bind, or a
 * misbehaving attachment store is logged and leaves the Host untouched; the
 * plugin is simply inactive (or runs without the failing capability).
 */

import { AttachmentStore } from './attachments.js';
import { createApprovalMirror } from './approval-mirror.js';
import { createQuestionMirror } from './question-mirror.js';
import { resolveConfig } from './config.js';
import { createDshAdapter } from './dsh-adapter.js';
import { EventBus } from './events.js';
import { HiddenSessionStore } from './hidden-sessions.js';
import { PairingStore } from './pairing.js';
import { PushConfigFileStore, createPushManager } from './push.js';
import { createRouter } from './router.js';
import { createMobileServer } from './server.js';
import { startMdnsAdvertiser } from './mdns.js';
import { JsonStateStore } from './store.js';

import path from 'node:path';

export const name = 'dsh-mobile-host';

/**
 * Start the Mobile API for one Host plugin row.
 *
 * @param {any} ctx Cordis plugin context
 * @param {Record<string, unknown>} [config] row config from cordis.patch.yml
 */
export function apply(ctx, config = {}) {
  const logger = console;
  try {
    activate(ctx, config ?? {}, logger);
  } catch (error) {
    // A plugin row must never take the Host down: log and stay inactive.
    logger.error?.(
      `[dsh-mobile-host] failed to activate; plugin stays inactive: ${String(
        /** @type {any} */ (error)?.message ?? error,
      )}`,
    );
  }
}

/**
 * @param {any} ctx
 * @param {Record<string, unknown>} config
 * @param {Console} logger
 */
function activate(ctx, config, logger) {
  let resolved;
  try {
    resolved = resolveConfig(config);
  } catch (error) {
    logger.error?.(
      `[dsh-mobile-host] invalid configuration; plugin stays inactive: ${String(
        /** @type {any} */ (error)?.message ?? error,
      )}`,
    );
    return;
  }
  if (!resolved.enabled) {
    logger.info?.('[dsh-mobile-host] disabled by configuration');
    return;
  }

  const bus = new EventBus({ bufferSize: resolved.sse.bufferSize, logger });
  const hiddenStore = new HiddenSessionStore({ filePath: resolved.sessions.hiddenFile, logger });
  // Approval/question mirrors: pass-through observers of DSH's waterfalls.
  // Approvals optionally accept a first mobile answer (config), while the
  // desktop UI remains an answerer; the adapter owns visibility filtering.
  const approvalMirror = createApprovalMirror({
    bus,
    logger,
    mobileAnswer: resolved.approvals.mobileAnswer,
  });
  const questionMirror = createQuestionMirror({
    bus,
    logger,
    mobileAnswer: resolved.questions.mobileAnswer,
  });
  // Optional ntfy push notifications: subscribes to the event bus, persists its
  // config next to the pairing state (token plaintext at rest, never returned by
  // GET). Disabled by default; loaded in the startup effect below.
  const pushStore = new PushConfigFileStore(
    path.join(path.dirname(resolved.stateFile), 'push-config.json'),
    { logger },
  );
  const push = createPushManager({ bus, store: pushStore, logger });
  const adapter = createDshAdapter({
    ctx,
    config: resolved,
    bus,
    logger,
    hiddenStore,
    approvalMirror,
    questionMirror,
    push,
  });
  const stateStore = new JsonStateStore(resolved.stateFile, { logger });
  const attachmentStore = new AttachmentStore({ config: resolved, logger });

  /** @type {Promise<{ pairing: PairingStore, server: ReturnType<typeof createMobileServer> }> | null} */
  let ready = null;

  ctx.effect(() => {
    const disposeEvents = adapter.attach();
    let closed = false;
    let mdnsStop = null;

    ready = (async () => {
      // Load the curated hidden list before serving the first request (missing
      // file = empty set; corrupt file = empty set with a warning).
      await hiddenStore.load();
      await push.load();
      const state = await stateStore.load();
      const pairing = new PairingStore({
        config: resolved,
        state,
        persist: (nextState) => stateStore.save(nextState),
        logger,
      });
      const router = createRouter({
        config: resolved,
        pairing,
        adapter,
        attachments: attachmentStore,
        hiddenStore,
        push,
        bus,
        logger,
      });
      const server = createMobileServer({ config: resolved, router, bus, adapter, pairing, logger });
      const address = await server.start();
      const host = typeof address === 'object' && address !== null ? address : {};
      logger.info?.(
        `[dsh-mobile-host] Mobile API v1 listening on ${resolved.host}:${host.port ?? resolved.port} ` +
          `(state: ${resolved.stateFile}, hidden: ${hiddenStore.size}, paired: ${pairing.hasDevices() ? 'yes' : 'no'})`,
      );
      try {
        mdnsStop = startMdnsAdvertiser({
          port: Number(host.port ?? resolved.port),
          hostName: resolved.hostName,
          version: resolved.version,
          logger,
        });
      } catch {
        mdnsStop = null;
      }
      return { pairing, server };
    })().catch((error) => {
      logger.error?.(
        `[dsh-mobile-host] could not start the Mobile API on ${resolved.host}:${resolved.port}: ${String(
          /** @type {any} */ (error)?.message ?? error,
        )}`,
      );
      return null;
    });

    return async () => {
      if (closed) return;
      closed = true;
      try {
        disposeEvents();
      } catch {
        /* already torn down */
      }
      try {
        mdnsStop?.();
      } catch {
        /* ignore */
      }
      push.close();
      attachmentStore.clear();
      const started = await ready?.catch(() => null);
      if (started?.server) {
        try {
          await started.server.close();
        } catch (error) {
          logger.warn?.(
            `[dsh-mobile-host] error while stopping the Mobile API: ${String(
              /** @type {any} */ (error)?.message ?? error,
            )}`,
          );
        }
      }
    };
  });
}
