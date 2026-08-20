// Tests for the synthetic OAuth 2.1 authorization server.
//
// Every test drives a real server over a real socket on an ephemeral port.
// No child processes, no mocks of the server, no outbound network: the
// configured redirect URI is a synthetic `example.invalid` host and every
// request that could redirect there uses `redirect: 'manual'`.

import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { after, describe, test } from 'node:test';

import { ALL_CODES, CODES } from '../src/codes.mjs';
import {
  ACCESS_TOKEN_TTL_MS,
  AUDIENCE,
  AUTH_CODE_TTL_MS,
  DCR_CLIENT_TTL_MS,
  OAUTH_BODY_LIMIT_BYTES,
  SCOPES,
  STATIC_CLIENT_ID,
  STATIC_CLIENT_NAME,
} from '../src/constants.mjs';
import { randomSecret } from '../src/crypto.mjs';
import {
  ALL_LOG_OUTCOMES,
  ALL_MAPPED_CODES,
  LOG_CLASS,
  LOG_OUTCOME,
  outcomeForCode,
} from '../src/log.mjs';
import { createSpikeServer } from '../src/server.mjs';

const REDIRECT_URI = 'https://example.invalid/spike-callback';
const OTHER_REDIRECT_URI = 'https://example.invalid/other-callback';
const START_MS = Date.UTC(2026, 7, 20, 12, 0, 0);

/** The whole header, written out as a literal - see `assertSecurityHeaders`. */
const EXPECTED_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

const openHandles = [];

after(async () => {
  await Promise.all(openHandles.map((handle) => handle.close()));
});

async function startSpike(overrides = {}) {
  const lines = [];
  const clock = { ms: START_MS };
  const handle = await createSpikeServer({
    port: 0,
    env: {},
    redirectUri: REDIRECT_URI,
    dcr: false,
    now: () => clock.ms,
    sink: { write: (line) => lines.push(line) },
    ...overrides,
  });
  openHandles.push(handle);
  return { ...handle, lines, clock };
}

/**
 * Starts a server the startup guard is expected to refuse. The handle is
 * registered for cleanup **before** it is returned, so that if a regression
 * ever lets one start, the caller's assertion fails and is reported - rather
 * than leaving a live listener that makes `node --test` hang forever.
 */
async function startExpectingRefusal(env) {
  const handle = await createSpikeServer({
    port: 0,
    env,
    redirectUri: REDIRECT_URI,
    sink: { write() {} },
  });
  openHandles.push(handle);
  return handle;
}

function base64url(buffer) {
  return buffer.toString('base64url');
}

function pkce() {
  const verifier = base64url(randomBytes(32));
  const challenge = base64url(createHash('sha256').update(verifier).digest());
  return { verifier, challenge };
}

function authorizeUrl(origin, params) {
  const url = new URL('/oauth/authorize', origin);
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) url.searchParams.set(key, value);
  }
  return url;
}

function postForm(url, fields) {
  return fetch(url, {
    method: 'POST',
    redirect: 'manual',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(fields).toString(),
  });
}

function authorizeParams(spike, overrides = {}) {
  const { verifier, challenge } = pkce();
  return {
    verifier,
    params: {
      response_type: 'code',
      client_id: STATIC_CLIENT_ID,
      redirect_uri: REDIRECT_URI,
      resource: spike.url,
      scope: SCOPES.join(' '),
      code_challenge: challenge,
      code_challenge_method: 'S256',
      state: `state_spike_${randomBytes(4).toString('hex')}`,
      ...overrides,
    },
  };
}

function approvalTicket(html) {
  const match = html.match(/name="approval" value="([^"]+)"/);
  assert.ok(match, 'approval page must carry a single-use approval ticket');
  return match[1];
}

/** authorize (GET) -> consent page -> approve (POST) -> redirect carrying the code. */
async function authorizeAndApprove(spike, overrides = {}) {
  const { verifier, params } = authorizeParams(spike, overrides);
  const pageResponse = await fetch(authorizeUrl(spike.url, params), { redirect: 'manual' });
  assert.equal(pageResponse.status, 200);
  const html = await pageResponse.text();
  const approveResponse = await postForm(`${spike.url}/oauth/approve`, {
    approval: approvalTicket(html),
  });
  assert.equal(approveResponse.status, 302);
  const location = new URL(approveResponse.headers.get('location'));
  return {
    verifier,
    params,
    html,
    pageResponse,
    approveResponse,
    location,
    code: location.searchParams.get('code'),
  };
}

function exchangeCode(
  spike,
  { code, verifier, redirectUri = REDIRECT_URI, clientId = STATIC_CLIENT_ID, resource = spike.url },
) {
  return postForm(`${spike.url}/oauth/token`, {
    grant_type: 'authorization_code',
    code,
    redirect_uri: redirectUri,
    client_id: clientId,
    code_verifier: verifier,
    ...(resource === null ? {} : { resource }),
  });
}

/** `resource: null` omits the indicator entirely (`undefined` would take the default). */
function refresh(spike, refreshToken, { clientId = STATIC_CLIENT_ID, resource = spike.url } = {}) {
  return postForm(`${spike.url}/oauth/token`, {
    grant_type: 'refresh_token',
    refresh_token: refreshToken,
    client_id: clientId,
    ...(resource === null ? {} : { resource }),
  });
}

/** The outcome of the most recent log line for a route class. */
function lastOutcome(spike, logClass) {
  const outcomes = spike.lines
    .map((line) => JSON.parse(line))
    .filter((entry) => entry.class === logClass)
    .map((entry) => entry.outcome);
  assert.ok(outcomes.length > 0, `no log line recorded for ${logClass}`);
  const outcome = outcomes.at(-1);
  assert.ok(ALL_LOG_OUTCOMES.has(outcome), `${outcome} is not a declared outcome`);
  return outcome;
}

/** Full happy path, returned as a token response body. */
async function grantTokens(spike) {
  const granted = await authorizeAndApprove(spike);
  const response = await exchangeCode(spike, granted);
  assert.equal(response.status, 200);
  return { ...granted, tokens: await response.json() };
}

async function errorCode(response) {
  const body = await response.json();
  assert.deepEqual(Object.keys(body), ['error'], 'error bodies carry exactly one closed code');
  assert.ok(ALL_CODES.has(body.error), `${body.error} is not in the closed code set`);
  return body.error;
}

describe('1. discovery documents', () => {
  test('protected-resource and authorization-server documents expose the expected fields and no others', async () => {
    const spike = await startSpike();

    const resourceResponse = await fetch(`${spike.url}/.well-known/oauth-protected-resource`);
    assert.equal(resourceResponse.status, 200);
    assert.equal(resourceResponse.headers.get('content-type'), 'application/json');
    const resourceDoc = await resourceResponse.json();
    assert.deepEqual(Object.keys(resourceDoc).sort(), [
      'authorization_servers',
      'bearer_methods_supported',
      'resource',
      'scopes_supported',
    ]);
    assert.equal(resourceDoc.resource, spike.url);
    assert.deepEqual(resourceDoc.authorization_servers, [spike.url]);
    assert.deepEqual(resourceDoc.scopes_supported, [...SCOPES]);
    assert.deepEqual(resourceDoc.bearer_methods_supported, ['header']);

    const serverResponse = await fetch(`${spike.url}/.well-known/oauth-authorization-server`);
    assert.equal(serverResponse.status, 200);
    const serverDoc = await serverResponse.json();
    assert.deepEqual(Object.keys(serverDoc).sort(), [
      'authorization_endpoint',
      'code_challenge_methods_supported',
      'grant_types_supported',
      'issuer',
      'response_types_supported',
      'revocation_endpoint',
      'scopes_supported',
      'token_endpoint',
      'token_endpoint_auth_methods_supported',
    ]);
    assert.equal(serverDoc.issuer, spike.url);
    assert.equal(serverDoc.authorization_endpoint, `${spike.url}/oauth/authorize`);
    assert.equal(serverDoc.token_endpoint, `${spike.url}/oauth/token`);
    assert.equal(serverDoc.revocation_endpoint, `${spike.url}/oauth/revoke`);
    assert.deepEqual(serverDoc.scopes_supported, [...SCOPES]);
    assert.deepEqual(serverDoc.response_types_supported, ['code']);
    assert.deepEqual(serverDoc.grant_types_supported, ['authorization_code', 'refresh_token']);
    assert.deepEqual(serverDoc.code_challenge_methods_supported, ['S256']);
    assert.deepEqual(serverDoc.token_endpoint_auth_methods_supported, ['none']);

    // Never advertise a downgraded PKCE method or a legacy grant.
    const rendered = JSON.stringify(serverDoc);
    for (const forbidden of ['plain', 'implicit', 'password', 'client_credentials']) {
      assert.ok(!rendered.includes(forbidden), `discovery must not advertise ${forbidden}`);
    }
  });
});

describe('2. authorize rejections', () => {
  const rejections = [
    ['missing PKCE', { code_challenge: undefined, code_challenge_method: undefined }, CODES.PKCE_REQUIRED],
    ['plain PKCE', { code_challenge_method: 'plain' }, CODES.UNSUPPORTED_PKCE_METHOD],
    ['unknown client', { client_id: 'client_spike_unknown' }, CODES.UNKNOWN_CLIENT],
    ['redirect mismatch', { redirect_uri: OTHER_REDIRECT_URI }, CODES.REDIRECT_MISMATCH],
    ['resource mismatch', { resource: 'https://mcp.other.example.invalid' }, CODES.RESOURCE_MISMATCH],
  ];

  for (const [label, overrides, expectedCode] of rejections) {
    test(`${label} is rejected with a closed code and no echo`, async () => {
      const spike = await startSpike();
      const { params } = authorizeParams(spike, overrides);
      const response = await fetch(authorizeUrl(spike.url, params), { redirect: 'manual' });

      assert.equal(response.status, 400);
      assert.equal(response.headers.get('location'), null, 'a rejected authorize never redirects');
      const raw = await response.text();
      assert.deepEqual(Object.keys(JSON.parse(raw)), ['error']);
      assert.equal(JSON.parse(raw).error, expectedCode);

      // No echo: nothing the caller supplied comes back in the body.
      for (const value of Object.values(params)) {
        if (value === undefined) continue;
        assert.ok(!raw.includes(value), `response echoed input ${value}`);
      }
    });
  }
});

describe('2b. the resource indicator is recorded, per endpoint', () => {
  // The spike accepts a request that omits RFC 8707 `resource`, so the only
  // way it can answer "did the client send one?" is the log outcome. Presence
  // is recorded separately at each endpoint, because a client may send it at
  // one and not the other, and that difference is itself the evidence.
  test('a present indicator records `ok` at both endpoints', async () => {
    const spike = await startSpike();

    const granted = await authorizeAndApprove(spike);
    assert.equal(lastOutcome(spike, LOG_CLASS.OAUTH_AUTHORIZE), LOG_OUTCOME.OK);

    assert.equal((await exchangeCode(spike, granted)).status, 200);
    assert.equal(lastOutcome(spike, LOG_CLASS.OAUTH_TOKEN), LOG_OUTCOME.OK);
  });

  test('an indicator absent at authorize is recorded at authorize only', async () => {
    const spike = await startSpike();

    const granted = await authorizeAndApprove(spike, { resource: undefined });
    assert.equal(lastOutcome(spike, LOG_CLASS.OAUTH_AUTHORIZE), LOG_OUTCOME.OK_RESOURCE_ABSENT);

    assert.equal((await exchangeCode(spike, granted)).status, 200);
    assert.equal(lastOutcome(spike, LOG_CLASS.OAUTH_TOKEN), LOG_OUTCOME.OK);
  });

  test('an indicator absent at the token endpoint is recorded there, on both grants', async () => {
    const spike = await startSpike();

    const granted = await authorizeAndApprove(spike);
    assert.equal(lastOutcome(spike, LOG_CLASS.OAUTH_AUTHORIZE), LOG_OUTCOME.OK);

    // authorization_code grant, indicator omitted
    const exchanged = await exchangeCode(spike, { ...granted, resource: null });
    assert.equal(exchanged.status, 200);
    assert.equal(lastOutcome(spike, LOG_CLASS.OAUTH_TOKEN), LOG_OUTCOME.OK_RESOURCE_ABSENT);

    // refresh_token grant, indicator present
    const tokens = await exchanged.json();
    const rotated = await refresh(spike, tokens.refresh_token);
    assert.equal(rotated.status, 200);
    assert.equal(lastOutcome(spike, LOG_CLASS.OAUTH_TOKEN), LOG_OUTCOME.OK);

    // refresh_token grant, indicator omitted
    const next = await rotated.json();
    assert.equal((await refresh(spike, next.refresh_token, { resource: null })).status, 200);
    assert.equal(lastOutcome(spike, LOG_CLASS.OAUTH_TOKEN), LOG_OUTCOME.OK_RESOURCE_ABSENT);
  });

  test('a mismatched indicator is still a rejection, not a recorded success', async () => {
    const spike = await startSpike();
    const { params } = authorizeParams(spike, { resource: 'https://mcp.other.example.invalid' });

    const response = await fetch(authorizeUrl(spike.url, params), { redirect: 'manual' });
    assert.equal(response.status, 400);
    assert.equal(await errorCode(response), CODES.RESOURCE_MISMATCH);
    assert.equal(lastOutcome(spike, LOG_CLASS.OAUTH_AUTHORIZE), LOG_OUTCOME.INVALID_REQUEST);
  });
});

describe('3. no auto-approval on GET', () => {
  test('GET /oauth/authorize renders a synthetic consent page and issues nothing', async () => {
    const spike = await startSpike();
    const { params } = authorizeParams(spike);
    const response = await fetch(authorizeUrl(spike.url, params), { redirect: 'manual' });

    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-type'), /^text\/html/);
    assert.equal(response.headers.get('location'), null);

    const html = await response.text();
    assert.ok(html.includes('SYNTHETIC'), 'the page is unmistakably labelled synthetic');
    assert.ok(html.includes(STATIC_CLIENT_NAME), 'the page shows the registered client name');
    assert.ok(html.includes('<form method="post" action="/oauth/approve">'));
    assert.ok(!html.includes('code='), 'the consent page never carries an authorization code');

    // A ticket alone is not a grant: only POST /oauth/approve moves the state.
    const wrongMethod = await fetch(`${spike.url}/oauth/approve`, { redirect: 'manual' });
    assert.equal(wrongMethod.status, 405);
    assert.equal(await errorCode(wrongMethod), CODES.METHOD_NOT_ALLOWED);

    const approved = await postForm(`${spike.url}/oauth/approve`, { approval: approvalTicket(html) });
    assert.equal(approved.status, 302);
    assert.ok(new URL(approved.headers.get('location')).searchParams.get('code'));
  });

  test('an approval ticket is single use', async () => {
    const spike = await startSpike();
    const { params } = authorizeParams(spike);
    const page = await fetch(authorizeUrl(spike.url, params), { redirect: 'manual' });
    const ticket = approvalTicket(await page.text());

    assert.equal((await postForm(`${spike.url}/oauth/approve`, { approval: ticket })).status, 302);
    const replay = await postForm(`${spike.url}/oauth/approve`, { approval: ticket });
    assert.equal(replay.status, 400);
    assert.equal(await errorCode(replay), CODES.APPROVAL_INVALID);
  });
});

describe('4. happy path', () => {
  test('authorize -> approve -> code -> token yields an access token and a refresh token', async () => {
    const spike = await startSpike();
    const granted = await authorizeAndApprove(spike);

    assert.equal(granted.location.origin + granted.location.pathname, REDIRECT_URI);
    assert.equal(granted.location.searchParams.get('state'), granted.params.state, 'state is echoed back');
    assert.ok(granted.code);

    const response = await exchangeCode(spike, granted);
    assert.equal(response.status, 200);
    const tokens = await response.json();
    assert.deepEqual(Object.keys(tokens).sort(), [
      'access_token',
      'expires_in',
      'refresh_token',
      'scope',
      'token_type',
    ]);
    assert.equal(tokens.token_type, 'Bearer');
    assert.equal(tokens.expires_in, ACCESS_TOKEN_TTL_MS / 1000);
    assert.equal(tokens.scope, SCOPES.join(' '));
    assert.notEqual(tokens.access_token, tokens.refresh_token);

    const verified = spike.verifyBearer(`Bearer ${tokens.access_token}`);
    assert.equal(verified.ok, true);
    assert.equal(verified.claims.aud, AUDIENCE);
    assert.equal(verified.claims.iss, spike.url);
    assert.equal(verified.claims.res, spike.url);
    assert.deepEqual(verified.claims.scope.split(' '), [...SCOPES]);

    // A token minted by a different process key is not accepted here.
    const other = await startSpike();
    assert.equal(spike.verifyBearer(`Bearer ${(await grantTokens(other)).tokens.access_token}`).ok, false);
  });
});

describe('5. authorization code single use', () => {
  test('a second exchange of the same code fails', async () => {
    const spike = await startSpike();
    const granted = await authorizeAndApprove(spike);

    assert.equal((await exchangeCode(spike, granted)).status, 200);
    const replay = await exchangeCode(spike, granted);
    assert.equal(replay.status, 400);
    assert.equal(await errorCode(replay), CODES.CODE_ALREADY_USED);
  });

  test('concurrent exchange of one code succeeds exactly once', async () => {
    const spike = await startSpike();
    const granted = await authorizeAndApprove(spike);

    const responses = await Promise.all([exchangeCode(spike, granted), exchangeCode(spike, granted)]);
    const statuses = responses.map((response) => response.status).sort();
    assert.deepEqual(statuses, [200, 400]);

    const failed = responses.find((response) => response.status === 400);
    assert.equal(await errorCode(failed), CODES.CODE_ALREADY_USED);
    const succeeded = responses.find((response) => response.status === 200);
    assert.ok((await succeeded.json()).access_token);
  });
});

describe('6. authorization code expiry boundary', () => {
  test('a code is exchangeable up to, and not at, its expiry', async () => {
    const spike = await startSpike();
    const justInTime = await authorizeAndApprove(spike);
    const tooLate = await authorizeAndApprove(spike);

    spike.clock.ms = START_MS + AUTH_CODE_TTL_MS - 1;
    assert.equal((await exchangeCode(spike, justInTime)).status, 200);

    spike.clock.ms = START_MS + AUTH_CODE_TTL_MS;
    const expired = await exchangeCode(spike, tooLate);
    assert.equal(expired.status, 400);
    assert.equal(await errorCode(expired), CODES.CODE_EXPIRED);
  });
});

describe('7. PKCE verifier', () => {
  test('a mismatched verifier fails the exchange', async () => {
    const spike = await startSpike();
    const granted = await authorizeAndApprove(spike);

    const response = await exchangeCode(spike, { code: granted.code, verifier: pkce().verifier });
    assert.equal(response.status, 400);
    assert.equal(await errorCode(response), CODES.PKCE_VERIFIER_MISMATCH);
  });

  test('a mismatched redirect_uri fails the exchange', async () => {
    const spike = await startSpike();
    const granted = await authorizeAndApprove(spike);

    const response = await exchangeCode(spike, { ...granted, redirectUri: OTHER_REDIRECT_URI });
    assert.equal(response.status, 400);
    assert.equal(await errorCode(response), CODES.REDIRECT_MISMATCH);
  });
});

describe('8. refresh rotation and lineage revocation', () => {
  test('refresh rotates, and reusing a rotated refresh kills the whole lineage', async () => {
    const spike = await startSpike();
    const { tokens: first } = await grantTokens(spike);

    const rotatedResponse = await refresh(spike, first.refresh_token);
    assert.equal(rotatedResponse.status, 200);
    const second = await rotatedResponse.json();
    assert.notEqual(second.refresh_token, first.refresh_token, 'refresh tokens rotate');
    assert.notEqual(second.access_token, first.access_token);
    assert.equal(spike.verifyBearer(`Bearer ${second.access_token}`).ok, true);

    const reuse = await refresh(spike, first.refresh_token);
    assert.equal(reuse.status, 400);
    assert.equal(await errorCode(reuse), CODES.INVALID_GRANT);

    // Lineage revoked: the newest refresh token and its access token both stop working.
    const afterReuse = await refresh(spike, second.refresh_token);
    assert.equal(afterReuse.status, 400);
    assert.equal(await errorCode(afterReuse), CODES.INVALID_GRANT);
    assert.equal(spike.verifyBearer(`Bearer ${second.access_token}`).ok, false);
  });
});

describe('9. revocation', () => {
  test('revocation kills an unexpired access token and the refresh lineage', async () => {
    const spike = await startSpike();
    const { tokens } = await grantTokens(spike);
    assert.equal(spike.verifyBearer(`Bearer ${tokens.access_token}`).ok, true);

    const revoked = await postForm(`${spike.url}/oauth/revoke`, {
      token: tokens.refresh_token,
      token_type_hint: 'refresh_token',
      client_id: STATIC_CLIENT_ID,
    });
    assert.equal(revoked.status, 200);
    assert.deepEqual(await revoked.json(), {});

    // The access token has not expired yet, and must still fail the bearer check
    // that the MCP surface performs on every call.
    spike.clock.ms = START_MS + ACCESS_TOKEN_TTL_MS - 1;
    const check = spike.verifyBearer(`Bearer ${tokens.access_token}`);
    assert.equal(check.ok, false);
    assert.equal(check.code, CODES.UNAUTHORIZED);

    const afterRevoke = await refresh(spike, tokens.refresh_token);
    assert.equal(afterRevoke.status, 400);
    assert.equal(await errorCode(afterRevoke), CODES.INVALID_GRANT);
  });

  test('revoking an unknown token is not an existence oracle', async () => {
    const spike = await startSpike();
    const response = await postForm(`${spike.url}/oauth/revoke`, {
      token: base64url(randomBytes(32)),
      client_id: STATIC_CLIENT_ID,
    });
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {});
  });

  test('an expired access token fails the bearer check', async () => {
    const spike = await startSpike();
    const { tokens } = await grantTokens(spike);
    spike.clock.ms = START_MS + ACCESS_TOKEN_TTL_MS;
    assert.equal(spike.verifyBearer(`Bearer ${tokens.access_token}`).ok, false);
  });
});

describe('10. dynamic client registration', () => {
  test('registration is a closed 404 when SPIKE_DCR is unset', async () => {
    const spike = await startSpike({ dcr: false });

    const response = await postForm(`${spike.url}/oauth/register`, {});
    assert.equal(response.status, 404);
    assert.equal(await errorCode(response), CODES.DCR_DISABLED);

    const discovery = await (await fetch(`${spike.url}/.well-known/oauth-authorization-server`)).json();
    assert.ok(!('registration_endpoint' in discovery));
  });

  test('registration issues a short-lived client with only the proven redirect', async () => {
    const spike = await startSpike({ dcr: true });

    const discovery = await (await fetch(`${spike.url}/.well-known/oauth-authorization-server`)).json();
    assert.equal(discovery.registration_endpoint, `${spike.url}/oauth/register`);

    const response = await fetch(`${spike.url}/oauth/register`, {
      method: 'POST',
      redirect: 'manual',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ redirect_uris: [REDIRECT_URI], client_name: 'Spike Synthetic Client' }),
    });
    assert.equal(response.status, 201);
    const registered = await response.json();
    assert.deepEqual(Object.keys(registered).sort(), [
      'client_id',
      'client_id_issued_at',
      'client_name',
      'grant_types',
      'redirect_uris',
      'response_types',
      'scope',
      'token_endpoint_auth_method',
    ]);
    assert.match(registered.client_id, /^client_spike_/);
    assert.deepEqual(registered.redirect_uris, [REDIRECT_URI]);
    assert.equal(registered.token_endpoint_auth_method, 'none');

    // The issued client is usable while it lives...
    const live = await fetch(
      authorizeUrl(spike.url, authorizeParams(spike, { client_id: registered.client_id }).params),
      { redirect: 'manual' },
    );
    assert.equal(live.status, 200);
    assert.ok((await live.text()).includes('Spike Synthetic Client'));

    // ...and is short lived.
    spike.clock.ms = START_MS + DCR_CLIENT_TTL_MS;
    const dead = await fetch(
      authorizeUrl(spike.url, authorizeParams(spike, { client_id: registered.client_id }).params),
      { redirect: 'manual' },
    );
    assert.equal(dead.status, 400);
    assert.equal(await errorCode(dead), CODES.UNKNOWN_CLIENT);
  });

  test('registration rejects an unproven redirect and unknown fields', async () => {
    const spike = await startSpike({ dcr: true });

    const register = (body) =>
      fetch(`${spike.url}/oauth/register`, {
        method: 'POST',
        redirect: 'manual',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(body),
      });

    const unproven = await register({ redirect_uris: [OTHER_REDIRECT_URI] });
    assert.equal(unproven.status, 400);
    assert.equal(await errorCode(unproven), CODES.REDIRECT_MISMATCH);

    const twoRedirects = await register({ redirect_uris: [REDIRECT_URI, OTHER_REDIRECT_URI] });
    assert.equal(twoRedirects.status, 400);
    assert.equal(await errorCode(twoRedirects), CODES.REDIRECT_MISMATCH);

    const unknownField = await register({ redirect_uris: [REDIRECT_URI], spike_unknown_field: 'x' });
    assert.equal(unknownField.status, 400);
    assert.equal(await errorCode(unknownField), CODES.SCHEMA_INVALID);

    const missing = await register({ client_name: 'Spike Synthetic Client' });
    assert.equal(missing.status, 400);
    assert.equal(await errorCode(missing), CODES.SCHEMA_INVALID);

    const malformed = await fetch(`${spike.url}/oauth/register`, {
      method: 'POST',
      redirect: 'manual',
      headers: { 'content-type': 'application/json' },
      body: '{not json',
    });
    assert.equal(malformed.status, 400);
    assert.equal(await errorCode(malformed), CODES.SCHEMA_INVALID);
  });
});

describe('11. security headers', () => {
  const expected = {
    'cache-control': 'no-store',
    'referrer-policy': 'no-referrer',
    'x-content-type-options': 'nosniff',
  };

  function assertSecurityHeaders(response, label) {
    for (const [header, value] of Object.entries(expected)) {
      assert.equal(response.headers.get(header), value, `${label} is missing ${header}`);
    }
    // Pinned whole: a regression that dropped `default-src` or `base-uri`
    // while keeping `frame-ancestors` would pass a substring check.
    assert.equal(response.headers.get('content-security-policy'), EXPECTED_CSP, `${label} CSP changed`);
  }

  test('authorize, approve and token responses all carry the security headers', async () => {
    const spike = await startSpike();
    const { params, verifier } = authorizeParams(spike);

    const page = await fetch(authorizeUrl(spike.url, params), { redirect: 'manual' });
    assertSecurityHeaders(page, 'authorize');

    const approved = await postForm(`${spike.url}/oauth/approve`, {
      approval: approvalTicket(await page.text()),
    });
    assertSecurityHeaders(approved, 'approve');

    const code = new URL(approved.headers.get('location')).searchParams.get('code');
    const token = await postForm(`${spike.url}/oauth/token`, {
      grant_type: 'authorization_code',
      code,
      redirect_uri: REDIRECT_URI,
      client_id: STATIC_CLIENT_ID,
      code_verifier: verifier,
      resource: spike.url,
    });
    assert.equal(token.status, 200);
    assertSecurityHeaders(token, 'token');
  });
});

describe('12. startup guard', () => {
  const contaminated = [
    'DATABASE_URL',
    'DAYFOLD_API',
    'FAMILY_ID',
    'HOUSEHOLD_SECRET',
    'AUTH_SECRET',
    'AUTH_GOOGLE_CLIENT_ID',
  ];

  for (const name of contaminated) {
    test(`refuses to start when ${name} is present`, async () => {
      await assert.rejects(
        () => startExpectingRefusal({ PATH: '/usr/bin', [name]: 'synthetic-value' }),
        (error) => {
          assert.equal(error.code, CODES.ENV_CONTAMINATED);
          assert.equal(error.message, CODES.ENV_CONTAMINATED, 'the guard leaks no variable name');
          return true;
        },
      );
    });
  }

  test('a guard regression fails the suite instead of hanging it', async () => {
    // The exact path a regression takes: the factory returns a live server
    // rather than refusing. `startExpectingRefusal` must register that handle
    // for cleanup, or the failed assertion is never reported at all - the
    // listener stays open and `node --test` waits on it indefinitely.
    const before = openHandles.length;
    const handle = await startExpectingRefusal({ PATH: '/usr/bin' });

    assert.equal(openHandles.length, before + 1, 'a started server was not registered for cleanup');
    assert.equal(openHandles.at(-1), handle);
    assert.equal((await fetch(`${handle.url}/healthz`)).status, 200, 'it really is a live listener');
  });

  test('starts on an environment that carries only spike variables', async () => {
    const spike = await startSpike({
      env: { PATH: '/usr/bin', SPIKE_PORT: '0', SPIKE_DCR: 'off' },
    });
    assert.equal((await fetch(`${spike.url}/healthz`)).status, 200);
  });
});

describe('13. minted identifiers carry no mint counter', () => {
  // `src/mcp-runs.mjs` mints run ids from randomness alone, on the stated
  // ground that a counter would tell each caller how many runs were minted
  // before theirs. A credential id needs that at least as much: it rides
  // inside the access token's own decodable `cred` claim, so a counter there
  // tells any token holder how many credentials the process has ever issued.
  const RANDOM_SUFFIX_LENGTH = randomSecret(8).length;

  function mintCredential(spike) {
    return spike.context.store.createCredential({
      clientId: STATIC_CLIENT_ID,
      redirectUri: REDIRECT_URI,
      resource: spike.url,
      scopes: [...SCOPES],
    });
  }

  test('a credential id is one fixed prefix plus one random block, and nothing else', async () => {
    const spike = await startSpike();
    const ids = Array.from({ length: 8 }, () => mintCredential(spike).credentialId);

    assert.equal(new Set(ids).size, ids.length, 'ids must still be unique');
    for (const id of ids) {
      assert.ok(id.startsWith('cred_spike_'), `${id} is not self-evidently synthetic`);
      assert.equal(
        id.length,
        'cred_spike_'.length + RANDOM_SUFFIX_LENGTH,
        `${id} carries a segment beyond the random block`,
      );
    }
  });

  test('the id inside a token\'s decodable cred claim carries no counter', async () => {
    const spike = await startSpike();
    const credential = mintCredential(spike);
    const token = spike.context.tokens.issue({
      issuer: spike.resourceOrigin,
      resource: credential.resource,
      credentialId: credential.credentialId,
      clientId: credential.clientId,
      scopes: credential.scopes,
    });

    // The payload is base64url, not encrypted: any token holder can read it.
    const claims = JSON.parse(Buffer.from(token.split('.')[0], 'base64url').toString('utf8'));
    assert.equal(claims.cred, credential.credentialId);
    assert.equal(claims.cred.length, 'cred_spike_'.length + RANDOM_SUFFIX_LENGTH);
  });

  test('a dynamically registered client id carries no counter either', async () => {
    const spike = await startSpike({ dcr: true });
    const ids = [];
    for (let attempt = 0; attempt < 3; attempt += 1) {
      const response = await fetch(`${spike.url}/oauth/register`, {
        method: 'POST',
        redirect: 'manual',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ redirect_uris: [REDIRECT_URI] }),
      });
      assert.equal(response.status, 201);
      ids.push((await response.json()).client_id);
    }

    assert.equal(new Set(ids).size, ids.length);
    for (const id of ids) {
      assert.ok(id.startsWith('client_spike_dcr_'), `${id} is not self-evidently synthetic`);
      assert.equal(
        id.length,
        'client_spike_dcr_'.length + RANDOM_SUFFIX_LENGTH,
        `${id} carries a segment beyond the random block`,
      );
    }
  });
});

describe('supplementary: transport limits and unknown routes', () => {
  test('an oversized body is rejected before it is parsed', async () => {
    const spike = await startSpike();
    const response = await fetch(`${spike.url}/oauth/token`, {
      method: 'POST',
      redirect: 'manual',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: `grant_type=authorization_code&code=${'a'.repeat(OAUTH_BODY_LIMIT_BYTES + 1)}`,
    });
    assert.equal(response.status, 413);
    assert.equal(await errorCode(response), CODES.TOO_LARGE);
  });

  test('a repeated parameter is rejected as a schema violation', async () => {
    const spike = await startSpike();
    const { params } = authorizeParams(spike);
    const url = authorizeUrl(spike.url, params);
    url.searchParams.append('state', 'state_spike_duplicate');

    const response = await fetch(url, { redirect: 'manual' });
    assert.equal(response.status, 400);
    assert.equal(await errorCode(response), CODES.SCHEMA_INVALID);
  });

  test('an unknown route is a closed 404', async () => {
    const spike = await startSpike();
    const response = await fetch(`${spike.url}/oauth/not-a-real-endpoint`, { redirect: 'manual' });
    assert.equal(response.status, 404);
    assert.equal(await errorCode(response), CODES.NOT_FOUND);
  });

  test('unknown token parameters are rejected as a schema violation', async () => {
    const spike = await startSpike();
    const granted = await authorizeAndApprove(spike);
    const response = await postForm(`${spike.url}/oauth/token`, {
      grant_type: 'authorization_code',
      code: granted.code,
      redirect_uri: REDIRECT_URI,
      client_id: STATIC_CLIENT_ID,
      code_verifier: granted.verifier,
      spike_unknown_field: 'x',
    });
    assert.equal(response.status, 400);
    assert.equal(await errorCode(response), CODES.SCHEMA_INVALID);
  });

  test('every closed code maps to a declared outcome', () => {
    for (const code of ALL_CODES) {
      assert.ok(ALL_MAPPED_CODES.has(code), `${code} has no declared outcome`);
      assert.ok(ALL_LOG_OUTCOMES.has(outcomeForCode(code)), `${code} maps outside the enum`);
    }
  });

  test('every request writes exactly one log line to the injected sink', async () => {
    const spike = await startSpike();
    const before = spike.lines.length;
    await fetch(`${spike.url}/healthz`);
    assert.equal(spike.lines.length - before, 1);
    const line = JSON.parse(spike.lines.at(-1));
    assert.deepEqual(Object.keys(line), ['ts', 'testRunId', 'class', 'outcome']);
  });
});
