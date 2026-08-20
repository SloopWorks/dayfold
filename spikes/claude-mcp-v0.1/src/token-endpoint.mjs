// POST /oauth/token and POST /oauth/revoke. Access tokens themselves are
// minted and verified in access-token.mjs.
//
// The authorization code is consumed by a compare-and-set *before* any binding
// is checked, so a failed exchange burns the code exactly like a successful one
// and two concurrent exchanges of one code can only produce one winner.

import { matchesChallenge } from './authorize.mjs';
import { CODES } from './codes.mjs';
import { OAUTH_BODY_LIMIT_BYTES } from './constants.mjs';
import { fail, hasContentType, readBody, sendJson } from './http.mjs';
import { LOG_OUTCOME } from './log.mjs';
import { parseScopes, validateParams } from './validate.mjs';

const CODE_GRANT_SPEC = {
  required: ['grant_type', 'code', 'redirect_uri', 'client_id', 'code_verifier'],
  optional: ['resource'],
  limits: { grant_type: 32, code: 128, code_verifier: 128 },
};

const REFRESH_GRANT_SPEC = {
  required: ['grant_type', 'refresh_token', 'client_id'],
  optional: ['resource', 'scope'],
  limits: { grant_type: 32, refresh_token: 128 },
};

const REVOKE_SPEC = {
  required: ['token'],
  optional: ['token_type_hint', 'client_id'],
  limits: { token: 4096, token_type_hint: 32 },
};

const CODE_VERIFIER = /^[A-Za-z0-9\-._~]{43,128}$/;

async function readForm(req) {
  const body = await readBody(req, OAUTH_BODY_LIMIT_BYTES);
  if (!body.ok) return { ok: false, status: 413, code: body.code };
  if (!hasContentType(req, 'application/x-www-form-urlencoded')) {
    return { ok: false, status: 400, code: CODES.SCHEMA_INVALID };
  }
  return { ok: true, params: new URLSearchParams(body.raw.toString('utf8')) };
}

function issueTokens(ctx, credential) {
  const accessToken = ctx.tokens.issue({
    issuer: ctx.resourceOrigin,
    resource: credential.resource,
    credentialId: credential.credentialId,
    clientId: credential.clientId,
    scopes: credential.scopes,
  });
  return {
    access_token: accessToken,
    token_type: 'Bearer',
    expires_in: ctx.tokens.ttlMs / 1000,
    refresh_token: ctx.store.issueRefreshToken(credential),
    scope: credential.scopes.join(' '),
  };
}

export async function token(ctx, req, res) {
  const form = await readForm(req);
  if (!form.ok) return fail(res, form.status, form.code);

  const grantType = form.params.get('grant_type');
  if (grantType === 'authorization_code') return exchangeCode(ctx, res, form.params);
  if (grantType === 'refresh_token') return exchangeRefresh(ctx, res, form.params);
  return fail(res, 400, CODES.UNSUPPORTED_GRANT_TYPE);
}

function exchangeCode(ctx, res, rawParams) {
  const validated = validateParams(rawParams, CODE_GRANT_SPEC);
  if (!validated.ok) return fail(res, 400, validated.code);
  const params = validated.value;
  if (!CODE_VERIFIER.test(params.code_verifier)) return fail(res, 400, CODES.SCHEMA_INVALID);

  const client = ctx.store.getClient(params.client_id);
  if (!client) return fail(res, 401, CODES.UNKNOWN_CLIENT);

  const consumed = ctx.store.consumeAuthorizationCode(params.code);
  if (!consumed.ok) return fail(res, 400, consumed.code);
  const grant = consumed.grant;

  if (grant.clientId !== params.client_id) return fail(res, 400, CODES.INVALID_GRANT);
  if (grant.redirectUri !== params.redirect_uri) return fail(res, 400, CODES.REDIRECT_MISMATCH);
  // `resource` is matched exactly when present. A client that omits it is not
  // refused: the spike is probing what a connector client sends, and a hard
  // requirement here would end the probe before anything else is observed.
  if (params.resource !== undefined && params.resource !== grant.resource) {
    return fail(res, 400, CODES.RESOURCE_MISMATCH);
  }
  if (!matchesChallenge(params.code_verifier, grant.codeChallenge)) {
    return fail(res, 400, CODES.PKCE_VERIFIER_MISMATCH);
  }

  const credential = ctx.store.createCredential({
    clientId: grant.clientId,
    redirectUri: grant.redirectUri,
    resource: grant.resource,
    scopes: grant.scopes,
  });
  sendJson(res, 200, issueTokens(ctx, credential));
  return LOG_OUTCOME.OK;
}

function exchangeRefresh(ctx, res, rawParams) {
  const validated = validateParams(rawParams, REFRESH_GRANT_SPEC);
  if (!validated.ok) return fail(res, 400, validated.code);
  const params = validated.value;

  const client = ctx.store.getClient(params.client_id);
  if (!client) return fail(res, 401, CODES.UNKNOWN_CLIENT);

  const consumed = ctx.store.consumeRefreshToken(params.refresh_token);
  if (!consumed.ok) return fail(res, 400, consumed.code);
  const credential = consumed.credential;

  if (credential.clientId !== params.client_id) return fail(res, 400, CODES.INVALID_GRANT);
  if (params.resource !== undefined && params.resource !== credential.resource) {
    return fail(res, 400, CODES.RESOURCE_MISMATCH);
  }

  // Scope may narrow on refresh, never widen.
  const scopes = parseScopes(params.scope, credential.scopes);
  if (!scopes.ok) return fail(res, 400, scopes.code);
  credential.scopes = scopes.scopes;

  sendJson(res, 200, issueTokens(ctx, credential));
  return LOG_OUTCOME.OK;
}

/**
 * Revocation answers 200 whatever it is handed: an unknown token must not
 * become an existence oracle.
 */
export async function revoke(ctx, req, res) {
  const form = await readForm(req);
  if (!form.ok) return fail(res, form.status, form.code);

  const validated = validateParams(form.params, REVOKE_SPEC);
  if (!validated.ok) return fail(res, 400, validated.code);
  const presented = validated.value.token;

  const byRefresh = ctx.store.findCredentialByRefreshToken(presented);
  if (byRefresh) {
    ctx.store.revokeCredential(byRefresh);
  } else {
    const verified = ctx.tokens.verify(presented, {
      issuer: ctx.resourceOrigin,
      resource: ctx.resourceOrigin,
    });
    const credential = verified.ok ? ctx.store.getCredential(verified.claims.cred) : null;
    if (credential) ctx.store.revokeCredential(credential);
  }

  sendJson(res, 200, {});
  return LOG_OUTCOME.OK;
}
