// The `/mcp` route: Streamable HTTP in stateless mode, behind the bearer check.
//
// Order matters. Authorization runs on headers alone, before a byte of body is
// read, so an unauthorized caller can never occupy a slot or a buffer. The
// concurrency slot is taken next, then the body is read under a hard byte cap
// and only then parsed - the cap is on raw bytes, never on a parsed object.
// The whole exchange runs under a handling deadline.

import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { SUPPORTED_PROTOCOL_VERSIONS, isInitializeRequest } from '@modelcontextprotocol/sdk/types.js';

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
 * Bearer plus scope. A scope counts as held only if it is carried by the signed
 * token *and* still held by the live credential record - so the effective grant
 * is the intersection of the two. The record is the authority: a narrowing
 * refresh overwrites it, so the pair can only ever downgrade - an older, wider
 * token cannot outlive a narrowing (ADR 0071 section 3).
 *
 * Returns the credential plus that intersection; the per-tool checks in
 * `mcp-tools.mjs` read the same set, so no tool can be reached with a scope
 * the connection did not actually prove.
 */
function authorizeCall(ctx, req) {
  const verified = verifyBearer(ctx, req.headers.authorization);
  if (!verified.ok) return null;

  const claimed = typeof verified.claims.scope === 'string' ? verified.claims.scope.split(' ') : [];
  const granted = verified.credential.scopes.filter((scope) => claimed.includes(scope));
  if (!granted.includes(MCP_REQUIRED_SCOPE)) return null;
  return { credential: verified.credential, granted };
}

/**
 * `MCP-Protocol-Version` is screened here rather than left to the transport.
 * The SDK reflects an unsupported value back into its own error message
 * verbatim, which would put caller-controlled text in a response body. Screened,
 * the header stays fully observable - as a closed outcome, never as an echo.
 *
 * The screen mirrors the SDK exactly: an `initialize` negotiates its version in
 * the body, so the SDK never validates the header for one, and neither may the
 * spike. Refusing there would manufacture a failed connection for a surface the
 * real bridge accepts - the one divergence class this spike must not have.
 * `messages.some(isInitializeRequest)` is the SDK's own test, batch included.
 */
function protocolVersionAccepted(req, message) {
  const header = req.headers['mcp-protocol-version'];
  if (header === undefined) return true;

  const messages = Array.isArray(message) ? message : [message];
  if (messages.some((entry) => isInitializeRequest(entry))) return true;
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
async function exchange(ctx, req, res, authorized) {
  try {
    const body = await readBody(req, MCP_BODY_LIMIT_BYTES);
    if (!body.ok) return fail(res, 413, body.code);
    if (!acceptsStreamableHttp(req)) return fail(res, 406, CODES.NOT_ACCEPTABLE);
    if (!hasContentType(req, 'application/json')) return fail(res, 415, CODES.UNSUPPORTED_MEDIA_TYPE);

    let message;
    try {
      message = JSON.parse(body.raw.toString('utf8'));
    } catch {
      return fail(res, 400, CODES.SCHEMA_INVALID);
    }

    // After the parse, because whether the header may be screened at all
    // depends on which message carries it.
    if (!protocolVersionAccepted(req, message)) {
      return fail(res, 400, CODES.UNSUPPORTED_PROTOCOL_VERSION);
    }

    return await dispatch(ctx, req, res, authorized, message);
  } catch {
    if (res.headersSent || res.writableEnded) return LOG_OUTCOME.ERROR;
    return fail(res, 500, CODES.INTERNAL);
  }
}

/**
 * Wraps the transport's own writer so the spike sees every JSON-RPC message it
 * puts on the wire.
 *
 * This is the only place the SDK's own error envelope is observable. The SDK
 * validates a request against its own schema before any handler runs, and
 * answers a failure by *resolving* the request with an error response
 * (`shared/protocol.js`, the rejection branch of `_onrequest`) inside an HTTP
 * **200**. Nothing the spike registered ever sees it, and `Protocol._onerror`
 * does not fire for it - so without this wrap a failed call is recorded as a
 * healthy one, in the log that is the operator's only observation channel.
 *
 * Sticky and per-entry: a batch is answered with one `send` per response, so a
 * failure anywhere in a batch is caught, not just its last entry.
 */
function watchForErrorEnvelope(transport) {
  const state = { seen: false };
  const rawSend = transport.send.bind(transport);
  transport.send = async (message, options) => {
    const entries = Array.isArray(message) ? message : [message];
    if (entries.some((entry) => entry?.error !== undefined)) state.seen = true;
    return rawSend(message, options);
  };
  return state;
}

async function dispatch(ctx, req, res, authorized, message) {
  let rejectionCode;
  const server = createToolServer({
    ctx,
    credential: authorized.credential,
    granted: authorized.granted,
    // First code wins. A batch can produce several; taking the last would make
    // the recorded outcome depend on which handler happened to settle last.
    record: (code) => { if (rejectionCode === undefined) rejectionCode = code; },
  });
  const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
  const envelope = watchForErrorEnvelope(transport);

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

  // A code the spike recorded is the most specific fact available, so it wins.
  if (rejectionCode !== undefined) return outcomeForCode(rejectionCode);
  // Then the two ways a failure can happen with no code to name it: a status
  // the transport chose, or an error envelope it wrote inside a 200. Neither
  // is ever an `ok`.
  if (res.statusCode >= 400 || envelope.seen) return LOG_OUTCOME.PROTOCOL_REJECTED;
  return outcomeForProtocolVersion(req.headers['mcp-protocol-version']);
}

export async function mcpPost(ctx, req, res) {
  const authorized = authorizeCall(ctx, req);
  if (!authorized) return unauthorized(ctx, res);

  const slot = ctx.mcp.limiter.acquire(authorized.credential.credentialId);
  if (!slot.ok) return fail(res, 429, CODES.TOO_MANY_REQUESTS);

  const deadline = startDeadline(ctx, res);
  try {
    return await Promise.race([deadline.expiry, exchange(ctx, req, res, authorized)]);
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
