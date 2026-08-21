// GET /oauth/authorize and POST /oauth/approve.
//
// GET only renders a consent page and mints a single-use approval ticket; it
// never issues an authorization code. Only the POST can move the state, and it
// can move it exactly once.
//
// A rejected authorization request answers directly with a closed code instead
// of redirecting: the spike must not bounce a caller-supplied value (including
// `state`) back off an unvalidated redirect target.

import { CODES } from './codes.mjs';
import { OAUTH_BODY_LIMIT_BYTES, SCOPES } from './constants.mjs';
import { constantTimeEquals, sha256Base64Url } from './crypto.mjs';
import { fail, hasContentType, readBody, sendHtml, sendRedirect } from './http.mjs';
import { outcomeForResource } from './log.mjs';
import { renderApprovalPage } from './pages.mjs';
import { parseScopes, validateParams } from './validate.mjs';

const AUTHORIZE_SPEC = {
  required: ['response_type', 'client_id', 'redirect_uri'],
  optional: ['code_challenge', 'code_challenge_method', 'state', 'scope', 'resource'],
  limits: { code_challenge: 128, code_challenge_method: 16, response_type: 32 },
};

const APPROVE_SPEC = {
  required: ['approval'],
  limits: { approval: 128 },
};

const S256_CHALLENGE = /^[A-Za-z0-9\-_]{43}$/;

export function authorize(ctx, req, res, url) {
  const validated = validateParams(url.searchParams, AUTHORIZE_SPEC);
  if (!validated.ok) return fail(res, 400, validated.code);
  const params = validated.value;

  if (params.response_type !== 'code') return fail(res, 400, CODES.UNSUPPORTED_RESPONSE_TYPE);

  const client = ctx.store.getClient(params.client_id);
  if (!client) return fail(res, 400, CODES.UNKNOWN_CLIENT);
  if (params.redirect_uri !== client.redirectUri) return fail(res, 400, CODES.REDIRECT_MISMATCH);
  // Matched exactly when present; see the note in token-endpoint.mjs on why an
  // absent `resource` is not itself a refusal. The approval record below
  // backfills the configured origin either way, so presence is recorded in the
  // log outcome - otherwise the spike could not answer whether the client
  // sent one.
  if (params.resource !== undefined && params.resource !== ctx.resourceOrigin) {
    return fail(res, 400, CODES.RESOURCE_MISMATCH);
  }

  if (params.code_challenge === undefined) return fail(res, 400, CODES.PKCE_REQUIRED);
  // A missing method means `plain` under RFC 7636; the spike supports S256 only.
  if (params.code_challenge_method !== 'S256') return fail(res, 400, CODES.UNSUPPORTED_PKCE_METHOD);
  if (!S256_CHALLENGE.test(params.code_challenge)) return fail(res, 400, CODES.SCHEMA_INVALID);

  const scopes = parseScopes(params.scope, SCOPES);
  if (!scopes.ok) return fail(res, 400, scopes.code);

  const ticket = ctx.store.putApproval({
    clientId: client.clientId,
    redirectUri: client.redirectUri,
    resource: ctx.resourceOrigin,
    scopes: scopes.scopes,
    codeChallenge: params.code_challenge,
    state: params.state,
  });

  sendHtml(
    res,
    200,
    renderApprovalPage({ clientName: client.clientName, scopes: scopes.scopes, ticket }),
    new URL(client.redirectUri).origin,
  );
  return outcomeForResource(params.resource);
}

export async function approve(ctx, req, res) {
  const body = await readBody(req, OAUTH_BODY_LIMIT_BYTES);
  if (!body.ok) return fail(res, 413, body.code);
  if (!hasContentType(req, 'application/x-www-form-urlencoded')) {
    return fail(res, 400, CODES.SCHEMA_INVALID);
  }

  const validated = validateParams(new URLSearchParams(body.raw.toString('utf8')), APPROVE_SPEC);
  if (!validated.ok) return fail(res, 400, validated.code);

  const consumed = ctx.store.consumeApproval(validated.value.approval);
  if (!consumed.ok) return fail(res, 400, consumed.code);

  const request = consumed.request;
  const code = ctx.store.putAuthorizationCode({
    clientId: request.clientId,
    redirectUri: request.redirectUri,
    resource: request.resource,
    scopes: request.scopes,
    codeChallenge: request.codeChallenge,
  });

  const location = new URL(request.redirectUri);
  location.searchParams.set('code', code);
  if (request.state !== undefined) location.searchParams.set('state', request.state);
  return sendRedirect(res, location.toString());
}

/** Exported for the token endpoint: S256 verification of a presented verifier. */
export function matchesChallenge(verifier, challenge) {
  return constantTimeEquals(sha256Base64Url(verifier), challenge);
}
