// Discovery documents (RFC 9728 + RFC 8414 shaped) and the liveness probe.
//
// The advertised surface is deliberately narrow: S256 only, two grants, two
// scopes. `plain`, implicit and password grants are never advertised, and the
// registration endpoint appears only while dynamic registration is enabled.

import { sendJson } from './http.mjs';
import { LOG_OUTCOME } from './log.mjs';
import { SCOPES } from './constants.mjs';

export function protectedResource(ctx, req, res) {
  sendJson(res, 200, {
    resource: ctx.resourceOrigin,
    authorization_servers: [ctx.resourceOrigin],
    scopes_supported: [...SCOPES],
    bearer_methods_supported: ['header'],
  });
  return LOG_OUTCOME.OK;
}

export function authorizationServer(ctx, req, res) {
  const document = {
    issuer: ctx.resourceOrigin,
    authorization_endpoint: `${ctx.resourceOrigin}/oauth/authorize`,
    token_endpoint: `${ctx.resourceOrigin}/oauth/token`,
    revocation_endpoint: `${ctx.resourceOrigin}/oauth/revoke`,
    scopes_supported: [...SCOPES],
    response_types_supported: ['code'],
    grant_types_supported: ['authorization_code', 'refresh_token'],
    code_challenge_methods_supported: ['S256'],
    token_endpoint_auth_methods_supported: ['none'],
  };
  if (ctx.config.dcr) {
    document.registration_endpoint = `${ctx.resourceOrigin}/oauth/register`;
  }
  sendJson(res, 200, document);
  return LOG_OUTCOME.OK;
}

export function health(ctx, req, res) {
  sendJson(res, 200, { status: 'ok' });
  return LOG_OUTCOME.OK;
}
