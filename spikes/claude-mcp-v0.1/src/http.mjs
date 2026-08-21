// HTTP plumbing: the security headers every response carries, the response
// helpers, and a bounded body reader that never parses an oversized request.

import { CODES } from './codes.mjs';
import { LOG_OUTCOME, outcomeForCode } from './log.mjs';

export const SECURITY_HEADERS = Object.freeze({
  'cache-control': 'no-store',
  'referrer-policy': 'no-referrer',
  'x-content-type-options': 'nosniff',
  'content-security-policy':
    "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
});

function send(res, status, headers, body) {
  res.writeHead(status, {
    ...SECURITY_HEADERS,
    'content-length': Buffer.byteLength(body),
    ...headers,
  });
  res.end(body);
}

export function sendJson(res, status, payload, headers = {}) {
  send(res, status, { 'content-type': 'application/json', ...headers }, JSON.stringify(payload));
}

// The consent page is the one document that submits a form whose *redirect*
// leaves this origin: POST /oauth/approve answers 302 to the client's callback.
// Chrome and Safari enforce `form-action` against the redirect target, not just
// the initial POST, so a bare `form-action 'self'` silently blocks the browser
// from delivering the authorization code and the ceremony dies with no request
// reaching the server at all. curl does not enforce CSP, so only a real browser
// shows this. Widen `form-action` to the one registered redirect origin — still
// an exact allow-list, not a relaxation to '*'.
export function sendHtml(res, status, html, formActionOrigin) {
  const headers = { 'content-type': 'text/html; charset=utf-8' };
  if (formActionOrigin) {
    headers['content-security-policy'] =
      `default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self' ${formActionOrigin}`;
  }
  send(res, status, headers, html);
}

export function sendRedirect(res, location) {
  send(res, 302, { location }, '');
  return LOG_OUTCOME.OK;
}

/** The only way an error leaves this server: one closed code, nothing else. */
export function fail(res, status, code) {
  sendJson(res, status, { error: code });
  return outcomeForCode(code);
}

/**
 * Reads a request body up to `limitBytes`. Over the limit the body is drained
 * and discarded rather than buffered, so an oversized request is refused
 * before anything parses it.
 */
export async function readBody(req, limitBytes) {
  const chunks = [];
  let size = 0;
  let tooLarge = false;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limitBytes) {
      tooLarge = true;
      chunks.length = 0;
      continue;
    }
    chunks.push(chunk);
  }
  if (tooLarge) return { ok: false, code: CODES.TOO_LARGE };
  return { ok: true, raw: Buffer.concat(chunks) };
}

export function hasContentType(req, expected) {
  const value = req.headers['content-type'];
  if (typeof value !== 'string') return false;
  return value.split(';')[0].trim().toLowerCase() === expected;
}
