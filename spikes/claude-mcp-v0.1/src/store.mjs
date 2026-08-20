// In-memory state for one server instance. Nothing is persisted: no database,
// no file, no process-external state of any kind.
//
// Authorization codes, refresh tokens and approval tickets are stored hashed,
// and every state transition is a single synchronous compare-and-set, so two
// concurrent requests against one credential can only ever produce one winner.

import { CODES } from './codes.mjs';
import { APPROVAL_TTL_MS, AUTH_CODE_TTL_MS, DCR_CLIENT_TTL_MS } from './constants.mjs';
import { randomSecret, sha256Base64Url } from './crypto.mjs';

/** @param {{now: () => number}} options */
export function createStore({ now }) {
  // Random only, for the same reason `mcp-runs.mjs` mints run ids that way: a
  // counter would tell each caller how many identifiers the process minted
  // before theirs. A credential id needs it at least as much, because it rides
  // inside the access token's own decodable `cred` claim - so a counter there
  // is readable by any token holder.
  const nextId = (prefix) => `${prefix}_${randomSecret(8)}`;

  const clients = new Map();
  const approvals = new Map();
  const authorizationCodes = new Map();
  const refreshTokens = new Map();
  const credentials = new Map();

  function putClient({ clientId, clientName, redirectUri, expiresAt = null }) {
    const client = { clientId, clientName, redirectUri, expiresAt };
    clients.set(clientId, client);
    return client;
  }

  function getClient(clientId) {
    const client = clients.get(clientId);
    if (!client) return null;
    if (client.expiresAt !== null && now() >= client.expiresAt) {
      clients.delete(clientId);
      return null;
    }
    return client;
  }

  function registerDynamicClient({ clientName, redirectUri }) {
    return putClient({
      clientId: nextId('client_spike_dcr'),
      clientName,
      redirectUri,
      expiresAt: now() + DCR_CLIENT_TTL_MS,
    });
  }

  /** Mints an approval ticket for a pending authorization request. */
  function putApproval(request) {
    const ticket = randomSecret();
    approvals.set(sha256Base64Url(ticket), {
      request,
      used: false,
      expiresAt: now() + APPROVAL_TTL_MS,
    });
    return ticket;
  }

  function consumeApproval(ticket) {
    const record = approvals.get(sha256Base64Url(String(ticket)));
    if (!record || record.used) return { ok: false, code: CODES.APPROVAL_INVALID };
    record.used = true;
    if (now() >= record.expiresAt) return { ok: false, code: CODES.APPROVAL_INVALID };
    return { ok: true, request: record.request };
  }

  function putAuthorizationCode(grant) {
    const code = randomSecret();
    authorizationCodes.set(sha256Base64Url(code), {
      grant,
      used: false,
      expiresAt: now() + AUTH_CODE_TTL_MS,
    });
    return code;
  }

  /**
   * Consumes a code before any binding is checked, so a failed exchange burns
   * the code exactly like a successful one.
   */
  function consumeAuthorizationCode(code) {
    const record = authorizationCodes.get(sha256Base64Url(String(code)));
    if (!record) return { ok: false, code: CODES.INVALID_GRANT };
    if (record.used) return { ok: false, code: CODES.CODE_ALREADY_USED };
    record.used = true;
    if (now() >= record.expiresAt) return { ok: false, code: CODES.CODE_EXPIRED };
    return { ok: true, grant: record.grant };
  }

  function createCredential({ clientId, redirectUri, resource, scopes }) {
    const credential = {
      credentialId: nextId('cred_spike'),
      clientId,
      redirectUri,
      resource,
      scopes,
      revoked: false,
    };
    credentials.set(credential.credentialId, credential);
    return credential;
  }

  function getCredential(credentialId) {
    return credentials.get(credentialId) ?? null;
  }

  function revokeCredential(credential) {
    credential.revoked = true;
  }

  function issueRefreshToken(credential) {
    const token = randomSecret();
    refreshTokens.set(sha256Base64Url(token), { credentialId: credential.credentialId, used: false });
    return token;
  }

  function findCredentialByRefreshToken(token) {
    const record = refreshTokens.get(sha256Base64Url(String(token)));
    if (!record) return null;
    return getCredential(record.credentialId);
  }

  /**
   * Rotating refresh: presenting an already-rotated token revokes the whole
   * lineage, so the newest refresh token stops working too.
   */
  function consumeRefreshToken(token) {
    const record = refreshTokens.get(sha256Base64Url(String(token)));
    if (!record) return { ok: false, code: CODES.INVALID_GRANT };
    const credential = getCredential(record.credentialId);
    if (!credential || credential.revoked) return { ok: false, code: CODES.INVALID_GRANT };
    if (record.used) {
      revokeCredential(credential);
      return { ok: false, code: CODES.INVALID_GRANT };
    }
    record.used = true;
    return { ok: true, credential };
  }

  return {
    putClient,
    getClient,
    registerDynamicClient,
    putApproval,
    consumeApproval,
    putAuthorizationCode,
    consumeAuthorizationCode,
    createCredential,
    getCredential,
    revokeCredential,
    issueRefreshToken,
    findCredentialByRefreshToken,
    consumeRefreshToken,
  };
}
