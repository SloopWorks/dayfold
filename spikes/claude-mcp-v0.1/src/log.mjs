// The single logging front door.
//
// One line per request. JSON. Exactly the keys `ts`, `testRunId`, `class`,
// `outcome`, where `class` and `outcome` are closed enums declared here. No
// message text, no body, no header, no token, no client name, no URL query.
//
// This module is the only place in `src/` allowed to write to stdout/stderr,
// and it writes only through an injectable sink so tests can capture lines
// in-process. Task 4 extends the enums; it does not add a second writer.

import { SUPPORTED_PROTOCOL_VERSIONS } from '@modelcontextprotocol/sdk/types.js';

import { CODES } from './codes.mjs';

export const LOG_CLASS = Object.freeze({
  SERVER_START: 'server.start',
  HEALTH: 'health',
  HTTP_UNKNOWN: 'http.unknown',
  DISCOVERY_PROTECTED_RESOURCE: 'discovery.protected_resource',
  DISCOVERY_AUTHORIZATION_SERVER: 'discovery.authorization_server',
  OAUTH_REGISTER: 'oauth.register',
  OAUTH_AUTHORIZE: 'oauth.authorize',
  OAUTH_APPROVE: 'oauth.approve',
  OAUTH_TOKEN: 'oauth.token',
  OAUTH_REVOKE: 'oauth.revoke',
  MCP: 'mcp',
});

export const LOG_OUTCOME = Object.freeze({
  OK: 'ok',
  // A success where the caller sent no RFC 8707 `resource` indicator. Whether
  // the parameter was present is a boolean about protocol shape, not content,
  // so recording it stays content-blind - and it is one of the questions the
  // spike exists to answer.
  OK_RESOURCE_ABSENT: 'ok_resource_absent',
  // A success where the caller sent no `MCP-Protocol-Version` header. Which
  // version a client negotiates - and whether it sends one at all - is protocol
  // shape, not content, and it is one of the questions the spike exists to
  // answer. Recorded as an outcome so the value itself never has to be.
  OK_PROTOCOL_VERSION_ABSENT: 'ok_protocol_version_absent',
  REJECTED: 'rejected',
  INVALID_REQUEST: 'invalid_request',
  INVALID_GRANT: 'invalid_grant',
  UNAUTHORIZED: 'unauthorized',
  NOT_FOUND: 'not_found',
  METHOD_NOT_ALLOWED: 'method_not_allowed',
  TOO_LARGE: 'too_large',
  THROTTLED: 'throttled',
  // The MCP protocol layer refused the request itself: the spike's own checks
  // all passed and the SDK still answered 4xx/5xx.
  PROTOCOL_REJECTED: 'protocol_rejected',
  // A JSON-RPC method this server does not implement - a client probing for a
  // capability the spike never declared.
  METHOD_UNSUPPORTED: 'method_unsupported',
  DEADLINE_EXCEEDED: 'deadline_exceeded',
  CONFLICT: 'conflict',
  ERROR: 'error',
});

/**
 * One closed outcome per protocol version the pinned SDK supports.
 *
 * *Which* version a client negotiates is the more useful fact than merely
 * whether it sent one: it is the floor the real bridge has to support. It is
 * also still content-blind - the value set is `SUPPORTED_PROTOCOL_VERSIONS`,
 * an enum owned by a pinned dependency and fully knowable to Dayfold before
 * any client connects. The same reasoning already justifies
 * `ok_resource_absent` and `ok_protocol_version_absent`: protocol shape, not
 * content. A value outside the set gets no outcome of its own and is never
 * written anywhere - see `outcomeForProtocolVersion`.
 *
 * A `Map`, not an object: the lookup key is a caller-supplied header value, and
 * an object would answer `constructor` or `toString` with something off
 * `Object.prototype`.
 */
const PROTOCOL_VERSION_OUTCOMES = new Map(
  SUPPORTED_PROTOCOL_VERSIONS.map((version) => [
    version,
    `ok_protocol_version_${version.replaceAll('-', '_')}`,
  ]),
);

export const ALL_PROTOCOL_VERSION_OUTCOMES = Object.freeze(new Set(PROTOCOL_VERSION_OUTCOMES.values()));

export const ALL_LOG_CLASSES = Object.freeze(new Set(Object.values(LOG_CLASS)));
export const ALL_LOG_OUTCOMES = Object.freeze(
  new Set([...Object.values(LOG_OUTCOME), ...ALL_PROTOCOL_VERSION_OUTCOMES]),
);

/**
 * Closed code -> closed outcome. Every code in `CODES` maps to exactly one
 * outcome so a handler never has to invent a log value from an error.
 */
const OUTCOME_BY_CODE = Object.freeze({
  [CODES.NOT_FOUND]: LOG_OUTCOME.NOT_FOUND,
  [CODES.DCR_DISABLED]: LOG_OUTCOME.NOT_FOUND,
  [CODES.METHOD_NOT_ALLOWED]: LOG_OUTCOME.METHOD_NOT_ALLOWED,
  [CODES.TOO_LARGE]: LOG_OUTCOME.TOO_LARGE,
  [CODES.SCHEMA_INVALID]: LOG_OUTCOME.INVALID_REQUEST,
  [CODES.INTERNAL]: LOG_OUTCOME.ERROR,
  [CODES.UNKNOWN_CLIENT]: LOG_OUTCOME.UNAUTHORIZED,
  [CODES.REDIRECT_MISMATCH]: LOG_OUTCOME.INVALID_REQUEST,
  [CODES.RESOURCE_MISMATCH]: LOG_OUTCOME.INVALID_REQUEST,
  [CODES.UNSUPPORTED_RESPONSE_TYPE]: LOG_OUTCOME.INVALID_REQUEST,
  [CODES.PKCE_REQUIRED]: LOG_OUTCOME.INVALID_REQUEST,
  [CODES.UNSUPPORTED_PKCE_METHOD]: LOG_OUTCOME.INVALID_REQUEST,
  [CODES.SCOPE_INVALID]: LOG_OUTCOME.INVALID_REQUEST,
  [CODES.APPROVAL_INVALID]: LOG_OUTCOME.INVALID_GRANT,
  [CODES.UNSUPPORTED_GRANT_TYPE]: LOG_OUTCOME.INVALID_REQUEST,
  [CODES.INVALID_GRANT]: LOG_OUTCOME.INVALID_GRANT,
  [CODES.CODE_EXPIRED]: LOG_OUTCOME.INVALID_GRANT,
  [CODES.CODE_ALREADY_USED]: LOG_OUTCOME.INVALID_GRANT,
  [CODES.PKCE_VERIFIER_MISMATCH]: LOG_OUTCOME.INVALID_GRANT,
  [CODES.NOT_ACCEPTABLE]: LOG_OUTCOME.INVALID_REQUEST,
  [CODES.UNSUPPORTED_MEDIA_TYPE]: LOG_OUTCOME.INVALID_REQUEST,
  [CODES.TOO_MANY_REQUESTS]: LOG_OUTCOME.THROTTLED,
  [CODES.DEADLINE_EXCEEDED]: LOG_OUTCOME.DEADLINE_EXCEEDED,
  [CODES.UNKNOWN_TOOL]: LOG_OUTCOME.INVALID_REQUEST,
  // Deliberately not `unauthorized`: that outcome is how the bearer check
  // refuses a whole request at the HTTP layer, and the runbook reads it as the
  // revocation signal. A scope refusal on one tool is a live credential the
  // spike answered on its own terms, which is what `rejected` records.
  [CODES.SCOPE_INSUFFICIENT]: LOG_OUTCOME.REJECTED,
  [CODES.UNKNOWN_METHOD]: LOG_OUTCOME.METHOD_UNSUPPORTED,
  [CODES.UNSUPPORTED_PROTOCOL_VERSION]: LOG_OUTCOME.INVALID_REQUEST,
  [CODES.RUN_UNKNOWN]: LOG_OUTCOME.REJECTED,
  [CODES.RUN_CLOSED]: LOG_OUTCOME.CONFLICT,
  [CODES.REPLAY_MISMATCH]: LOG_OUTCOME.CONFLICT,
  [CODES.UNAUTHORIZED]: LOG_OUTCOME.UNAUTHORIZED,
  [CODES.ENV_CONTAMINATED]: LOG_OUTCOME.REJECTED,
});

/**
 * Every code carried by this table has a declared outcome; the fallback exists
 * only so an unmapped code degrades instead of crashing a response. A test
 * asserts the table is total over `CODES`, so the fallback stays unreachable.
 */
export const ALL_MAPPED_CODES = Object.freeze(new Set(Object.keys(OUTCOME_BY_CODE)));

export function outcomeForCode(code) {
  return OUTCOME_BY_CODE[code] ?? LOG_OUTCOME.ERROR;
}

/**
 * Success outcome for a request that may carry a `resource` indicator.
 * Records presence only - never the value.
 */
export function outcomeForResource(resourceParam) {
  return resourceParam === undefined ? LOG_OUTCOME.OK_RESOURCE_ABSENT : LOG_OUTCOME.OK;
}

/**
 * Success outcome for a request that may carry an `MCP-Protocol-Version`
 * header. Absent is its own outcome; a version the SDK lists is recorded as
 * the closed outcome that names it; anything else is a plain `ok`.
 *
 * The fallback is only reachable on an `initialize`, which negotiates its
 * version in the body and is therefore exempt from the header screen in
 * `src/mcp.mjs`. Recording it as `ok` rather than inventing an outcome from
 * the value is what keeps the enum closed - a plain `ok` on an `mcp` line is
 * itself the observation that the client named something the SDK does not.
 */
export function outcomeForProtocolVersion(headerValue) {
  if (headerValue === undefined) return LOG_OUTCOME.OK_PROTOCOL_VERSION_ABSENT;
  return PROTOCOL_VERSION_OUTCOMES.get(headerValue) ?? LOG_OUTCOME.OK;
}

/**
 * @param {object} options
 * @param {string} options.testRunId random per process; the only correlation handle.
 * @param {{write: (line: string) => void}} [options.sink] defaults to process.stdout.
 * @param {() => number} [options.now]
 */
export function createLogger({ testRunId, sink = process.stdout, now = Date.now }) {
  return function log(logClass, outcome) {
    if (!ALL_LOG_CLASSES.has(logClass)) throw new Error(CODES.INTERNAL);
    if (!ALL_LOG_OUTCOMES.has(outcome)) throw new Error(CODES.INTERNAL);
    sink.write(
      `${JSON.stringify({
        ts: new Date(now()).toISOString(),
        testRunId,
        class: logClass,
        outcome,
      })}\n`,
    );
  };
}
