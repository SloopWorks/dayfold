// The routing table. One entry per (method, path); each entry names the log
// class for that route and the handler, which returns the closed log outcome.
//
// Task 3 adds its `/mcp` entries to ROUTES; nothing else has to change.

import { approve, authorize } from './authorize.mjs';
import { CODES } from './codes.mjs';
import { authorizationServer, health, protectedResource } from './discovery.mjs';
import { fail } from './http.mjs';
import { LOG_CLASS, LOG_OUTCOME } from './log.mjs';
import { register } from './register.mjs';
import { revoke, token } from './token-endpoint.mjs';

export const ROUTES = Object.freeze([
  {
    method: 'GET',
    path: '/.well-known/oauth-protected-resource',
    logClass: LOG_CLASS.DISCOVERY_PROTECTED_RESOURCE,
    handler: protectedResource,
  },
  {
    method: 'GET',
    path: '/.well-known/oauth-authorization-server',
    logClass: LOG_CLASS.DISCOVERY_AUTHORIZATION_SERVER,
    handler: authorizationServer,
  },
  { method: 'POST', path: '/oauth/register', logClass: LOG_CLASS.OAUTH_REGISTER, handler: register },
  { method: 'GET', path: '/oauth/authorize', logClass: LOG_CLASS.OAUTH_AUTHORIZE, handler: authorize },
  { method: 'POST', path: '/oauth/approve', logClass: LOG_CLASS.OAUTH_APPROVE, handler: approve },
  { method: 'POST', path: '/oauth/token', logClass: LOG_CLASS.OAUTH_TOKEN, handler: token },
  { method: 'POST', path: '/oauth/revoke', logClass: LOG_CLASS.OAUTH_REVOKE, handler: revoke },
  { method: 'GET', path: '/healthz', logClass: LOG_CLASS.HEALTH, handler: health },
]);

/** Only the pathname and query are used; the base is a parsing placeholder. */
const PARSE_BASE = 'http://spike.invalid';

export function createRouter(ctx) {
  const byMethodAndPath = new Map(ROUTES.map((route) => [`${route.method} ${route.path}`, route]));
  const logClassByPath = new Map(ROUTES.map((route) => [route.path, route.logClass]));

  return async function route(req, res) {
    let url;
    try {
      url = new URL(req.url, PARSE_BASE);
    } catch {
      ctx.log(LOG_CLASS.HTTP_UNKNOWN, fail(res, 400, CODES.SCHEMA_INVALID));
      return;
    }

    const entry = byMethodAndPath.get(`${req.method} ${url.pathname}`);
    if (entry) {
      let outcome;
      try {
        outcome = await entry.handler(ctx, req, res, url);
      } catch {
        // No thrown error ever reaches a body: it becomes one closed code.
        // Task 4 hardens this mapper.
        outcome = res.headersSent ? LOG_OUTCOME.ERROR : fail(res, 500, CODES.INTERNAL);
        if (!res.writableEnded) res.end();
      }
      ctx.log(entry.logClass, outcome);
      return;
    }

    if (logClassByPath.has(url.pathname)) {
      ctx.log(logClassByPath.get(url.pathname), fail(res, 405, CODES.METHOD_NOT_ALLOWED));
      return;
    }

    ctx.log(LOG_CLASS.HTTP_UNKNOWN, fail(res, 404, CODES.NOT_FOUND));
  };
}
