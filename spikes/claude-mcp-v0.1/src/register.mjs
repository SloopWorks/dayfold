// POST /oauth/register — dynamic client registration, off unless SPIKE_DCR=on.
//
// When it is on, a registration may claim exactly one redirect URI and it must
// be the redirect the operator already proved (SPIKE_REDIRECT_URI); the issued
// client is short lived. The strict field allowlist is intentional: a real
// connector client sending a field this rejects is exactly the compatibility
// evidence the spike exists to collect.

import { CODES } from './codes.mjs';
import { MAX_CLIENT_NAME_LENGTH, OAUTH_BODY_LIMIT_BYTES, SCOPES } from './constants.mjs';
import { fail, hasContentType, readBody, sendJson } from './http.mjs';
import { LOG_OUTCOME } from './log.mjs';
import { isBoundedString, validateObject } from './validate.mjs';

const REGISTER_SPEC = {
  required: ['redirect_uris'],
  optional: [
    'client_name',
    'grant_types',
    'response_types',
    'token_endpoint_auth_method',
    'scope',
  ],
};

export async function register(ctx, req, res) {
  if (!ctx.config.dcr) return fail(res, 404, CODES.DCR_DISABLED);

  const body = await readBody(req, OAUTH_BODY_LIMIT_BYTES);
  if (!body.ok) return fail(res, 413, body.code);
  if (!hasContentType(req, 'application/json')) return fail(res, 400, CODES.SCHEMA_INVALID);

  let parsed;
  try {
    parsed = JSON.parse(body.raw.toString('utf8'));
  } catch {
    return fail(res, 400, CODES.SCHEMA_INVALID);
  }

  const validated = validateObject(parsed, REGISTER_SPEC);
  if (!validated.ok) return fail(res, 400, validated.code);
  const request = validated.value;

  if (!Array.isArray(request.redirect_uris)) return fail(res, 400, CODES.SCHEMA_INVALID);
  if (request.redirect_uris.length !== 1) return fail(res, 400, CODES.REDIRECT_MISMATCH);
  if (request.redirect_uris[0] !== ctx.config.redirectUri) {
    return fail(res, 400, CODES.REDIRECT_MISMATCH);
  }

  if (request.client_name !== undefined && !isBoundedString(request.client_name, MAX_CLIENT_NAME_LENGTH)) {
    return fail(res, 400, CODES.SCHEMA_INVALID);
  }

  const client = ctx.store.registerDynamicClient({
    clientName: request.client_name ?? 'Unnamed synthetic client',
    redirectUri: ctx.config.redirectUri,
  });

  sendJson(res, 201, {
    client_id: client.clientId,
    client_id_issued_at: Math.floor(ctx.now() / 1000),
    client_name: client.clientName,
    redirect_uris: [client.redirectUri],
    grant_types: ['authorization_code', 'refresh_token'],
    response_types: ['code'],
    token_endpoint_auth_method: 'none',
    scope: SCOPES.join(' '),
  });
  return LOG_OUTCOME.OK;
}
