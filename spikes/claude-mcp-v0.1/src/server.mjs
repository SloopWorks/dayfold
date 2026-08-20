// The server factory.
//
// Pure factory, no child process, no ambient configuration: every knob is an
// explicit option so a test can drive a real server on an ephemeral port and
// control its clock. The startup guard is the one thing that reads an
// environment, and it reads the one it is handed.

import { createServer } from 'node:http';

import {
  DEFAULT_HOST,
  DEFAULT_PORT,
  DEFAULT_REDIRECT_URI,
  STATIC_CLIENT_ID,
  STATIC_CLIENT_NAME,
} from './constants.mjs';
import { randomKey, randomSecret } from './crypto.mjs';
import { assertCleanEnvironment } from './guard.mjs';
import { LOG_CLASS, LOG_OUTCOME, createLogger } from './log.mjs';
import { createRouter } from './router.mjs';
import { createStore } from './store.mjs';
import { createTokenIssuer, verifyBearer } from './access-token.mjs';

/**
 * @param {object} [options]
 * @param {number} [options.port] 0 for an ephemeral port.
 * @param {string} [options.host]
 * @param {string} [options.resourceOrigin] advertised resource + issuer; defaults to the bound origin.
 * @param {string} [options.redirectUri] the single proven redirect URI.
 * @param {boolean} [options.dcr] enable dynamic client registration.
 * @param {Record<string, unknown>} [options.env] environment inspected by the startup guard.
 * @param {{write: (line: string) => void}} [options.sink] log sink; defaults to process.stdout.
 * @param {() => number} [options.now] injectable clock.
 * @param {string} [options.testRunId]
 */
export async function createSpikeServer(options = {}) {
  assertCleanEnvironment(options.env ?? process.env);

  const now = options.now ?? Date.now;
  const host = options.host ?? DEFAULT_HOST;
  const testRunId = options.testRunId ?? randomSecret(8);
  const log = createLogger({ testRunId, sink: options.sink, now });

  const store = createStore({ now });
  const redirectUri = options.redirectUri ?? DEFAULT_REDIRECT_URI;
  store.putClient({
    clientId: STATIC_CLIENT_ID,
    clientName: STATIC_CLIENT_NAME,
    redirectUri,
  });

  const ctx = {
    config: { host, redirectUri, dcr: Boolean(options.dcr) },
    resourceOrigin: options.resourceOrigin ?? null,
    now,
    log,
    store,
    tokens: createTokenIssuer({ signingKey: randomKey(32), now }),
  };

  const route = createRouter(ctx);
  const server = createServer((req, res) => {
    route(req, res).catch(() => {
      log(LOG_CLASS.HTTP_UNKNOWN, LOG_OUTCOME.ERROR);
      if (!res.writableEnded) res.destroy();
    });
  });

  await new Promise((resolve, reject) => {
    const onError = (error) => reject(error);
    server.once('error', onError);
    server.listen(options.port ?? DEFAULT_PORT, host, () => {
      server.removeListener('error', onError);
      resolve();
    });
  });

  const url = `http://${host}:${server.address().port}`;
  ctx.resourceOrigin ??= url;
  log(LOG_CLASS.SERVER_START, LOG_OUTCOME.OK);

  return {
    port: server.address().port,
    url,
    resourceOrigin: ctx.resourceOrigin,
    testRunId,
    context: ctx,
    /** The same bearer check the MCP surface performs on every call. */
    verifyBearer: (headerValue) => verifyBearer(ctx, headerValue),
    async close() {
      if (!server.listening) return;
      server.closeAllConnections();
      await new Promise((resolve) => server.close(() => resolve()));
    },
  };
}

