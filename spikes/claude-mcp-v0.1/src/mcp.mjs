// The `/mcp` route: Streamable HTTP in stateless mode, behind the bearer check.
//
// Order matters. Authorization runs on headers alone, before a byte of body is
// read, so an unauthorized caller can never occupy a slot or a buffer. The
// concurrency slot is taken next, then the body is read under a hard byte cap
// and only then parsed - the cap is on raw bytes, never on a parsed object.
// The whole exchange runs under a handling deadline.

import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { SUPPORTED_PROTOCOL_VERSIONS } from '@modelcontextprotocol/sdk/types.js';

import { verifyBearer } from './access-token.mjs';
import { CODES } from './codes.mjs';
import { MCP_BODY_LIMIT_BYTES, MCP_REQUIRED_SCOPE } from './constants.mjs';
import { SECURITY_HEADERS, fail, hasContentType, readBody, sendJson } from './http.mjs';
import { LOG_OUTCOME, outcomeForCode, outcomeForProtocolVersion } from './log.mjs';
import { createToolServer } from './mcp-tools.mjs';

/** Streamable HTTP requires a client to accept both. */
const REQUIRED_ACCEPT = Object.freeze(['application/json', 'text/event-stream']);

/**
 * Per-credential in-flight cap. A rejected acquire is a closed code, never a
 * queue: the spike answers immediately rather than holding a connection open.
 */
export function createConcurrencyLimiter({ maxConcurrent }) {
  const counts = new Map();

  return {
    /** Read-only depth for one credential; the cap's only observable. */
    inFlight: (credentialId) => counts.get(credentialId) ?? 0,

    acquire(credentialId) {
      const current = counts.get(credentialId) ?? 0;
      if (current >= maxConcurrent) return { ok: false };
      counts.set(credentialId, current + 1);

      let released = false;
      return {
        ok: true,
        release() {
          if (released) return;
          released = true;
          const remaining = (counts.get(credentialId) ?? 1) - 1;
          if (remaining <= 0) counts.delete(credentialId);
          else counts.set(credentialId, remaining);
        },
      };
    },
  };
}

function unauthorized(ctx, res) {
  sendJson(
    res,
    401,
    { error: CODES.UNAUTHORIZED },
    {
      'www-authenticate': `Bearer realm="dayfold-spike", resource_metadata="${ctx.resourceOrigin}/.well-known/oauth-protected-resource"`,
    },
  );
  return outcomeForCode(CODES.UNAUTHORIZED);
}

/**
 * Bearer plus scope. The required scope must be carried by the signed token
 * *and* still held by the live credential record. The record is the authority:
 * a narrowing refresh overwrites it, so the pair can only ever downgrade - an
 * older, wider token cannot outlive a narrowing (ADR 0071 section 3).
 */
function authorizeCall(ctx, req) {
  const verified = verifyBearer(ctx, req.headers.authorization);
  if (!verified.ok) return null;

  const claimed = typeof verified.claims.scope === 'string' ? verified.claims.scope.split(' ') : [];
  if (!claimed.includes(MCP_REQUIRED_SCOPE)) return null;
  if (!verified.credential.scopes.includes(MCP_REQUIRED_SCOPE)) return null;
  return verified.credential;
}

/**
 * `MCP-Protocol-Version` is screened here rather than left to the transport.
 * The SDK reflects an unsupported value back into its own error message
 * verbatim, which would put caller-controlled text in a response body. Screened
 * at the same seam as Accept and Content-Type, the header stays fully
 * observable - as a closed outcome, never as an echo.
 */
function protocolVersionAccepted(req) {
  const header = req.headers['mcp-protocol-version'];
  if (header === undefined) return true;
  return typeof header === 'string' && SUPPORTED_PROTOCOL_VERSIONS.includes(header);
}

function acceptsStreamableHttp(req) {
  const header = req.headers.accept;
  if (typeof header !== 'string') return false;
  return REQUIRED_ACCEPT.every((mediaType) => header.includes(mediaType));
}

/**
 * Resolves with the closed deadline outcome if the exchange is still running
 * when the timer fires. The stalled socket is dropped once the refusal is on
 * the wire, so a slow sender cannot hold the connection past its deadline.
 */
function startDeadline(ctx, res) {
  let timer;
  const expiry = new Promise((resolve) => {
    timer = setTimeout(() => {
      if (res.headersSent || res.writableEnded) {
        resolve(LOG_OUTCOME.ERROR);
        return;
      }
      const outcome = fail(res, 504, CODES.DEADLINE_EXCEEDED);
      res.once('finish', () => res.destroy());
      resolve(outcome);
    }, ctx.mcp.deadlineMs);
  });
  return { expiry, clear: () => clearTimeout(timer) };
}

/** Never rejects: every failure becomes one closed code, so the race is safe. */
async function exchange(ctx, req, res, credential) {
  try {
    const body = await readBody(req, MCP_BODY_LIMIT_BYTES);
    if (!body.ok) return fail(res, 413, body.code);
    if (!acceptsStreamableHttp(req)) return fail(res, 406, CODES.NOT_ACCEPTABLE);
    if (!hasContentType(req, 'application/json')) return fail(res, 415, CODES.UNSUPPORTED_MEDIA_TYPE);
    if (!protocolVersionAccepted(req)) return fail(res, 400, CODES.UNSUPPORTED_PROTOCOL_VERSION);

    let message;
    try {
      message = JSON.parse(body.raw.toString('utf8'));
    } catch {
      return fail(res, 400, CODES.SCHEMA_INVALID);
    }

    return await dispatch(ctx, req, res, credential, message);
  } catch {
    if (res.headersSent || res.writableEnded) return LOG_OUTCOME.ERROR;
    return fail(res, 500, CODES.INTERNAL);
  }
}

async function dispatch(ctx, req, res, credential, message) {
  let rejectionCode;
  const server = createToolServer({ ctx, credential, record: (code) => { rejectionCode = code; } });
  const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });

  try {
    // The transport writes its own response, so the headers every other route
    // gets from `send` have to be staged on the response before it does.
    for (const [header, value] of Object.entries(SECURITY_HEADERS)) res.setHeader(header, value);
    await server.connect(transport);
    await transport.handleRequest(req, res, message);
  } finally {
    // Closing the server closes the transport with it; a failed close must not
    // replace the outcome of a response that has already been written.
    await server.close().catch(() => {});
  }

  if (rejectionCode !== undefined) return outcomeForCode(rejectionCode);
  // The transport writes its own refusals straight to the response, so a status
  // it chose is the only evidence they happened: a 4xx is never an `ok`.
  if (res.statusCode >= 400) return LOG_OUTCOME.PROTOCOL_REJECTED;
  return outcomeForProtocolVersion(req.headers['mcp-protocol-version']);
}

export async function mcpPost(ctx, req, res) {
  const credential = authorizeCall(ctx, req);
  if (!credential) return unauthorized(ctx, res);

  const slot = ctx.mcp.limiter.acquire(credential.credentialId);
  if (!slot.ok) return fail(res, 429, CODES.TOO_MANY_REQUESTS);

  const deadline = startDeadline(ctx, res);
  try {
    return await Promise.race([deadline.expiry, exchange(ctx, req, res, credential)]);
  } finally {
    deadline.clear();
    slot.release();
  }
}

/**
 * Stateless mode has no session to terminate and offers no standalone event
 * stream, so `GET` and `DELETE` are refused - which the MCP spec allows and
 * the SDK's own client handles. They authenticate first all the same: `/mcp`
 * answers nothing at all without a bearer token.
 */
export function mcpMethodNotAllowed(ctx, req, res) {
  if (!authorizeCall(ctx, req)) return unauthorized(ctx, res);
  return fail(res, 405, CODES.METHOD_NOT_ALLOWED);
}
