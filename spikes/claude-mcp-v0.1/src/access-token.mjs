// Access-token issuance and verification.
//
// A token is `base64url(compact JSON payload) . base64url(HMAC-SHA256)` signed
// with a key generated per server instance. That is deliberate: it is enough to
// prove audience/issuer/resource binding to a connector client, and it leaves
// nothing on disk to leak. It is not a production JWT and there is no JWKS.

import { CODES } from './codes.mjs';
import { constantTimeEquals, hmacBase64Url, randomSecret } from './crypto.mjs';
import { ACCESS_TOKEN_TTL_MS, AUDIENCE, SUBJECT } from './constants.mjs';

/**
 * @param {object} options
 * @param {Buffer} options.signingKey per-process signing key.
 * @param {() => number} options.now
 */
export function createTokenIssuer({ signingKey, now }) {
  function sign(payload) {
    const encoded = Buffer.from(JSON.stringify(payload), 'utf8').toString('base64url');
    return `${encoded}.${hmacBase64Url(signingKey, encoded)}`;
  }

  return {
    ttlMs: ACCESS_TOKEN_TTL_MS,

    issue({ issuer, resource, credentialId, clientId, scopes }) {
      const issuedAt = now();
      return sign({
        iss: issuer,
        aud: AUDIENCE,
        sub: SUBJECT,
        cid: clientId,
        cred: credentialId,
        scope: scopes.join(' '),
        res: resource,
        jti: randomSecret(8),
        iat: Math.floor(issuedAt / 1000),
        exp: Math.floor((issuedAt + ACCESS_TOKEN_TTL_MS) / 1000),
      });
    },

    /**
     * Every failure returns the same closed code: an attacker learns nothing
     * about which check failed.
     */
    verify(token, { issuer, resource }) {
      const unauthorized = { ok: false, code: CODES.UNAUTHORIZED };
      if (typeof token !== 'string') return unauthorized;

      const parts = token.split('.');
      if (parts.length !== 2) return unauthorized;
      const [encoded, signature] = parts;
      if (!constantTimeEquals(signature, hmacBase64Url(signingKey, encoded))) return unauthorized;

      let claims;
      try {
        claims = JSON.parse(Buffer.from(encoded, 'base64url').toString('utf8'));
      } catch {
        return unauthorized;
      }

      if (claims.iss !== issuer) return unauthorized;
      if (claims.aud !== AUDIENCE) return unauthorized;
      if (claims.res !== resource) return unauthorized;
      if (typeof claims.exp !== 'number' || now() >= claims.exp * 1000) return unauthorized;

      return { ok: true, claims };
    },
  };
}

/** Parses `Authorization: Bearer <token>`; returns null when the header is unusable. */
export function bearerFromHeader(headerValue) {
  if (typeof headerValue !== 'string') return null;
  const match = headerValue.match(/^Bearer[ ]([\w.~+/=-]+)$/i);
  return match ? match[1] : null;
}

/**
 * The full bearer check: signature, issuer, audience, resource, expiry, and a
 * credential that has not been revoked. Every failure is the same closed code.
 * The MCP surface performs exactly this check on every call.
 */
export function verifyBearer(ctx, headerValue) {
  const unauthorized = { ok: false, code: CODES.UNAUTHORIZED };
  const token = bearerFromHeader(headerValue);
  if (!token) return unauthorized;

  const verified = ctx.tokens.verify(token, {
    issuer: ctx.resourceOrigin,
    resource: ctx.resourceOrigin,
  });
  if (!verified.ok) return unauthorized;

  const credential = ctx.store.getCredential(verified.claims.cred);
  if (!credential || credential.revoked) return unauthorized;

  return { ok: true, claims: verified.claims, credential };
}
