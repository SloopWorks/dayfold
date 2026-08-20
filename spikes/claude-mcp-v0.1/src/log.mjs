// The single logging front door.
//
// One line per request. JSON. Exactly the keys `ts`, `testRunId`, `class`,
// `outcome`, where `class` and `outcome` are closed enums declared here. No
// message text, no body, no header, no token, no client name, no URL query.
//
// This module is the only place in `src/` allowed to write to stdout/stderr,
// and it writes only through an injectable sink so tests can capture lines
// in-process. Tasks 3 and 4 extend the enums; they do not add a second writer.

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
});

export const LOG_OUTCOME = Object.freeze({
  OK: 'ok',
  // A success where the caller sent no RFC 8707 `resource` indicator. Whether
  // the parameter was present is a boolean about protocol shape, not content,
  // so recording it stays content-blind - and it is one of the questions the
  // spike exists to answer.
  OK_RESOURCE_ABSENT: 'ok_resource_absent',
  REJECTED: 'rejected',
  INVALID_REQUEST: 'invalid_request',
  INVALID_GRANT: 'invalid_grant',
  UNAUTHORIZED: 'unauthorized',
  NOT_FOUND: 'not_found',
  METHOD_NOT_ALLOWED: 'method_not_allowed',
  TOO_LARGE: 'too_large',
  ERROR: 'error',
});

export const ALL_LOG_CLASSES = Object.freeze(new Set(Object.values(LOG_CLASS)));
export const ALL_LOG_OUTCOMES = Object.freeze(new Set(Object.values(LOG_OUTCOME)));

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
