// Tests for the Streamable HTTP MCP surface and the two spike tools.
//
// Every test drives a real server over a real socket on an ephemeral port, and
// the tool-behavior tests drive it with the SDK's own MCP client so the shape
// under test is the shape a connector client actually speaks. The OAuth dance
// itself is covered exhaustively by `oauth.test.mjs`; this suite mints its
// credential through the server's own store so each test is about `/mcp`.

import assert from 'node:assert/strict';
import { Agent, request as httpRequest } from 'node:http';
import { after, describe, test } from 'node:test';

import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { ErrorCode, LATEST_PROTOCOL_VERSION, SUPPORTED_PROTOCOL_VERSIONS } from '@modelcontextprotocol/sdk/types.js';

import { ALL_CODES, CODES } from '../src/codes.mjs';
import {
  ACCESS_TOKEN_TTL_MS,
  AUDIENCE,
  MCP_BODY_LIMIT_BYTES,
  MCP_DEADLINE_MS,
  MCP_MAX_CONCURRENT_PER_CREDENTIAL,
  MCP_REQUIRED_SCOPE,
  SCOPES,
  SCOPE_CONTEXT_READ,
  SCOPE_DRAFT_SUBMIT,
  STATIC_CLIENT_ID,
  SUBJECT,
} from '../src/constants.mjs';
import { hmacBase64Url, randomKey } from '../src/crypto.mjs';
import {
  ALL_LOG_OUTCOMES,
  ALL_PROTOCOL_VERSION_OUTCOMES,
  LOG_CLASS,
  LOG_OUTCOME,
  outcomeForProtocolVersion,
} from '../src/log.mjs';
import {
  IDENTITY_STATUS,
  MAX_CLIENT_REQUEST_ID_LENGTH,
  MAX_RECORDS_REPORTED,
  RECEIPT_STATUS,
  SPIKE_INSTALL_ID,
  TOOLS,
  TOOL_FINISH,
  TOOL_IDENTITY,
} from '../src/mcp-schema.mjs';
import { createSpikeServer } from '../src/server.mjs';

const REDIRECT_URI = 'https://example.invalid/spike-callback';
const START_MS = Date.UTC(2026, 7, 20, 12, 0, 0);
const MCP_ACCEPT = 'application/json, text/event-stream';

/**
 * Pinned whole, not by substring: a regression that dropped `default-src` or
 * `base-uri` while keeping `frame-ancestors` would pass a substring check.
 * Written out as a literal rather than read from `src/`, so the assertion is
 * about the header the server actually sends and not about itself.
 */
const EXPECTED_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

const openServers = [];
const openClients = [];

after(async () => {
  await Promise.all(openClients.map((client) => client.close()));
  await Promise.all(openServers.map((handle) => handle.close()));
});

async function startSpike(overrides = {}) {
  const lines = [];
  const clock = { ms: START_MS };
  const signingKey = overrides.signingKey ?? randomKey(32);
  const handle = await createSpikeServer({
    port: 0,
    env: {},
    redirectUri: REDIRECT_URI,
    dcr: false,
    now: () => clock.ms,
    sink: { write: (line) => lines.push(line) },
    ...overrides,
    signingKey,
  });
  openServers.push(handle);
  return { ...handle, lines, clock, signingKey };
}

/**
 * A credential plus a genuine access token for it. The token is minted by the
 * server's own issuer, so everything `/mcp` checks is checked for real.
 */
function grant(spike, { scopes = [...SCOPES] } = {}) {
  const credential = spike.context.store.createCredential({
    clientId: STATIC_CLIENT_ID,
    redirectUri: REDIRECT_URI,
    resource: spike.resourceOrigin,
    scopes,
  });
  return { credential, accessToken: issueFor(spike, credential) };
}

function issueFor(spike, credential) {
  return spike.context.tokens.issue({
    issuer: spike.resourceOrigin,
    resource: credential.resource,
    credentialId: credential.credentialId,
    clientId: credential.clientId,
    scopes: credential.scopes,
  });
}

/** A correctly signed token carrying deliberately wrong claims. */
function forgeToken(spike, credential, overrides) {
  const claims = {
    iss: spike.resourceOrigin,
    aud: AUDIENCE,
    sub: SUBJECT,
    cid: credential.clientId,
    cred: credential.credentialId,
    scope: credential.scopes.join(' '),
    res: spike.resourceOrigin,
    jti: 'jti_spike_forged',
    iat: Math.floor(START_MS / 1000),
    exp: Math.floor((START_MS + ACCESS_TOKEN_TTL_MS) / 1000),
    ...overrides,
  };
  const encoded = Buffer.from(JSON.stringify(claims), 'utf8').toString('base64url');
  return `${encoded}.${hmacBase64Url(spike.signingKey, encoded)}`;
}

function mcpFetch(spike, { method = 'POST', token, body, accept = MCP_ACCEPT, contentType = 'application/json' } = {}) {
  const headers = {};
  if (accept !== null) headers.accept = accept;
  if (contentType !== null) headers['content-type'] = contentType;
  if (token !== undefined) headers.authorization = `Bearer ${token}`;
  return fetch(`${spike.url}/mcp`, {
    method,
    redirect: 'manual',
    headers,
    ...(body === undefined ? {} : { body: typeof body === 'string' ? body : JSON.stringify(body) }),
  });
}

function mcpFetchWithVersion(spike, token, protocolVersion, body) {
  return fetch(`${spike.url}/mcp`, {
    method: 'POST',
    redirect: 'manual',
    headers: {
      accept: MCP_ACCEPT,
      'content-type': 'application/json',
      authorization: `Bearer ${token}`,
      'mcp-protocol-version': protocolVersion,
    },
    body: JSON.stringify(body),
  });
}

/** The single log line the most recent request wrote. */
function lastOutcome(spike) {
  return JSON.parse(spike.lines.at(-1)).outcome;
}

let rpcSequence = 0;
function rpc(method, params) {
  rpcSequence += 1;
  return { jsonrpc: '2.0', id: rpcSequence, method, params };
}

function toolCall(name, args) {
  return rpc('tools/call', { name, arguments: args });
}

function initializeMessage() {
  return rpc('initialize', {
    protocolVersion: '2025-06-18',
    capabilities: {},
    clientInfo: { name: 'spike-test-client', version: '0.0.0' },
  });
}

/** Minimal SSE reader: the transport answers a POST with one terminating stream. */
function sseMessages(text) {
  return text
    .split('\n\n')
    .map((frame) =>
      frame
        .split('\n')
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice('data:'.length).trim())
        .join(''),
    )
    .filter((data) => data.length > 0)
    .map((data) => JSON.parse(data));
}

async function rpcResponse(response) {
  assert.equal(response.status, 200);
  const messages = sseMessages(await response.text());
  assert.equal(messages.length, 1, 'one JSON-RPC response per request');
  return messages[0];
}

async function errorCode(response) {
  const body = await response.json();
  assert.deepEqual(Object.keys(body), ['error'], 'error bodies carry exactly one closed code');
  assert.ok(ALL_CODES.has(body.error), `${body.error} is not in the closed code set`);
  return body.error;
}

function assertChallenge(spike, response) {
  assert.equal(
    response.headers.get('www-authenticate'),
    `Bearer realm="dayfold-spike", resource_metadata="${spike.url}/.well-known/oauth-protected-resource"`,
  );
}

async function connectClient(spike, token) {
  const client = new Client({ name: 'spike-test-client', version: '0.0.0' });
  await client.connect(
    new StreamableHTTPClientTransport(new URL(`${spike.url}/mcp`), {
      requestInit: { headers: { authorization: `Bearer ${token}` } },
    }),
  );
  openClients.push(client);
  return client;
}

function toolPayload(result) {
  assert.notEqual(result.isError, true, 'expected a successful tool result');
  assert.equal(result.content.length, 1);
  assert.equal(result.content[0].type, 'text');
  return JSON.parse(result.content[0].text);
}

function toolErrorCode(result) {
  assert.equal(result.isError, true, 'expected a tool error result');
  assert.equal(result.content.length, 1);
  assert.equal(result.content[0].type, 'text');
  const code = result.content[0].text;
  assert.ok(ALL_CODES.has(code), `${code} is not in the closed code set`);
  return code;
}

async function mintRun(client) {
  return toolPayload(await client.callTool({ name: TOOL_IDENTITY, arguments: { schemaVersion: 1 } })).spikeRunId;
}

function finishInput(runId, clientRequestId, overrides = {}) {
  return {
    schemaVersion: 1,
    runId,
    result: 'draft',
    sources: [{ source: 'gmail', outcome: 'reported_observed', recordsReported: 3 }],
    clientRequestId,
    ...overrides,
  };
}

/**
 * A POST whose body is one byte short of its declared content-length, on its own
 * connection: the server has accepted the request and is waiting for the rest.
 */
function openStalledPost(spike, token, payload) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8');
  const req = httpRequest({
    host: '127.0.0.1',
    port: spike.port,
    path: '/mcp',
    method: 'POST',
    agent: new Agent({ keepAlive: false }),
    headers: {
      authorization: `Bearer ${token}`,
      accept: MCP_ACCEPT,
      'content-type': 'application/json',
      'content-length': body.length,
    },
  });

  const response = new Promise((resolve, reject) => {
    req.once('response', (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      const settle = () =>
        resolve({ status: res.statusCode, headers: res.headers, text: Buffer.concat(chunks).toString('utf8') });
      res.once('end', settle);
      res.once('aborted', settle);
    });
    req.once('error', reject);
  });

  req.write(body.subarray(0, body.length - 1));
  return {
    response,
    complete: () => req.end(body.subarray(body.length - 1)),
    abort: () => req.destroy(),
  };
}

async function waitUntil(predicate, label) {
  for (let attempt = 0; attempt < 500; attempt += 1) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
  throw new Error(`condition never held: ${label}`);
}

describe('1. an unauthenticated /mcp request is refused', () => {
  for (const [label, message] of [
    ['initialize', initializeMessage()],
    ['tools/list', rpc('tools/list', {})],
    ['tools/call', toolCall(TOOL_IDENTITY, { schemaVersion: 1 })],
  ]) {
    test(`${label} without a bearer token is a closed 401`, async () => {
      const spike = await startSpike();
      const response = await mcpFetch(spike, { body: message });

      assert.equal(response.status, 401);
      assertChallenge(spike, response);
      const raw = await response.clone().text();
      assert.ok(!raw.includes(TOOL_IDENTITY), 'a 401 must not leak a tool name');
      assert.ok(!raw.includes(TOOL_FINISH), 'a 401 must not leak a tool name');
      assert.equal(await errorCode(response), CODES.UNAUTHORIZED);
    });
  }

  test('a malformed authorization header is the same closed 401', async () => {
    const spike = await startSpike();
    const response = await fetch(`${spike.url}/mcp`, {
      method: 'POST',
      headers: { accept: MCP_ACCEPT, 'content-type': 'application/json', authorization: 'Basic c3Bpa2U6' },
      body: JSON.stringify(rpc('tools/list', {})),
    });
    assert.equal(response.status, 401);
    assert.equal(await errorCode(response), CODES.UNAUTHORIZED);
  });
});

describe('2. a token that is not bound to this server is refused', () => {
  test('a token signed by a different per-process key is refused', async () => {
    const spike = await startSpike();
    const other = await startSpike();
    const foreign = grant(other).accessToken;

    const response = await mcpFetch(spike, { token: foreign, body: rpc('tools/list', {}) });
    assert.equal(response.status, 401);
    assertChallenge(spike, response);
    assert.equal(await errorCode(response), CODES.UNAUTHORIZED);
  });

  for (const [label, overrides] of [
    ['audience', { aud: 'dayfold-mcp' }],
    ['issuer', { iss: 'https://example.invalid/other-issuer' }],
    ['resource', { res: 'https://example.invalid/other-resource' }],
  ]) {
    test(`a correctly signed token with the wrong ${label} is refused`, async () => {
      const spike = await startSpike();
      const { credential } = grant(spike);
      const token = forgeToken(spike, credential, overrides);

      const response = await mcpFetch(spike, { token, body: rpc('tools/list', {}) });
      assert.equal(response.status, 401);
      assertChallenge(spike, response);
      assert.equal(await errorCode(response), CODES.UNAUTHORIZED);
    });
  }
});

describe('2b. scope authority: the live credential record wins', () => {
  test('a token whose signed scope claim lacks the required scope is refused', async () => {
    const spike = await startSpike();
    const { credential } = grant(spike);
    const token = forgeToken(spike, credential, { scope: 'mcp:draft.submit' });

    assert.ok(credential.scopes.includes(MCP_REQUIRED_SCOPE), 'the credential still holds the scope');
    const response = await mcpFetch(spike, { token, body: rpc('tools/list', {}) });
    assert.equal(response.status, 401);
    assert.equal(await errorCode(response), CODES.UNAUTHORIZED);
  });

  test('a narrowing refresh retires an older, wider token that is still unexpired', async () => {
    const spike = await startSpike();
    const { credential, accessToken } = grant(spike);
    assert.equal((await mcpFetch(spike, { token: accessToken, body: rpc('tools/list', {}) })).status, 200);

    const refreshToken = spike.context.store.issueRefreshToken(credential);
    const refreshed = await fetch(`${spike.url}/oauth/token`, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'refresh_token',
        refresh_token: refreshToken,
        client_id: STATIC_CLIENT_ID,
        resource: spike.url,
        scope: 'mcp:draft.submit',
      }).toString(),
    });
    assert.equal(refreshed.status, 200);
    const narrowed = await refreshed.json();
    assert.deepEqual(credential.scopes, ['mcp:draft.submit']);

    const wide = await mcpFetch(spike, { token: accessToken, body: rpc('tools/list', {}) });
    assert.equal(wide.status, 401, 'the wider token no longer outlives the narrowing');
    assert.equal(await errorCode(wide), CODES.UNAUTHORIZED);

    // Re-widening the record cannot resurrect the narrowed token: the signed
    // claim is checked as well, so the two together are downgrade-only.
    credential.scopes = [...SCOPES];
    const narrow = await mcpFetch(spike, { token: narrowed.access_token, body: rpc('tools/list', {}) });
    assert.equal(narrow.status, 401);
    assert.equal(await errorCode(narrow), CODES.UNAUTHORIZED);
  });
});

describe('3. a revoked credential is refused even with an unexpired token', () => {
  test('revoking through the token endpoint closes /mcp immediately', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);
    assert.equal((await mcpFetch(spike, { token: accessToken, body: rpc('tools/list', {}) })).status, 200);

    const revoked = await fetch(`${spike.url}/oauth/revoke`, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ token: accessToken }).toString(),
    });
    assert.equal(revoked.status, 200);

    const response = await mcpFetch(spike, { token: accessToken, body: rpc('tools/list', {}) });
    assert.equal(response.status, 401);
    assertChallenge(spike, response);
    assert.equal(await errorCode(response), CODES.UNAUTHORIZED);
  });
});

describe('4. an expired access token is refused', () => {
  test('the expiry boundary closes /mcp', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);
    assert.equal((await mcpFetch(spike, { token: accessToken, body: rpc('tools/list', {}) })).status, 200);

    spike.clock.ms = START_MS + ACCESS_TOKEN_TTL_MS;
    const response = await mcpFetch(spike, { token: accessToken, body: rpc('tools/list', {}) });
    assert.equal(response.status, 401);
    assert.equal(await errorCode(response), CODES.UNAUTHORIZED);
  });
});

describe('5. initialize and tools/list expose exactly two strict tools', () => {
  test('the server declares only the tools capability', async () => {
    const spike = await startSpike();
    const client = await connectClient(spike, grant(spike).accessToken);

    assert.deepEqual(client.getServerCapabilities(), { tools: {} });
  });

  test('tools/list returns the two spike tools and their strict schemas', async () => {
    const spike = await startSpike();
    const client = await connectClient(spike, grant(spike).accessToken);

    const { tools } = await client.listTools();
    assert.deepEqual(
      tools.map((tool) => tool.name).sort(),
      [TOOL_FINISH, TOOL_IDENTITY].sort(),
    );
    assert.equal(tools.length, TOOLS.length);

    for (const tool of tools) {
      assert.equal(tool.inputSchema.type, 'object');
      assert.equal(tool.inputSchema.additionalProperties, false, `${tool.name} accepts unknown fields`);
      assert.ok(tool.inputSchema.required.includes('schemaVersion'));
      assert.ok(typeof tool.description === 'string' && tool.description.length > 0);
    }

    const finish = tools.find((tool) => tool.name === TOOL_FINISH);
    assert.deepEqual(finish.inputSchema.required.sort(), [
      'clientRequestId',
      'result',
      'runId',
      'schemaVersion',
      'sources',
    ]);
    assert.deepEqual(finish.inputSchema.properties.result.enum, ['draft', 'no_changes', 'failed']);
    const row = finish.inputSchema.properties.sources.items;
    assert.equal(row.additionalProperties, false, 'each source row rejects unknown fields');
    assert.deepEqual(row.properties.source.enum, ['gmail']);
    assert.deepEqual(row.properties.outcome.enum, [
      'reported_observed',
      'reported_zero_results',
      'reported_unavailable',
    ]);
    assert.equal(row.properties.recordsReported.minimum, 0);
    assert.equal(row.properties.recordsReported.maximum, MAX_RECORDS_REPORTED);
    assert.equal(finish.inputSchema.properties.clientRequestId.maxLength, MAX_CLIENT_REQUEST_ID_LENGTH);
  });
});

describe('6. the identity tool returns the constant synthetic values', () => {
  test('identity is constant and mints one run per credential', async () => {
    const spike = await startSpike();
    const client = await connectClient(spike, grant(spike).accessToken);

    const payload = toolPayload(await client.callTool({ name: TOOL_IDENTITY, arguments: { schemaVersion: 1 } }));
    assert.deepEqual(Object.keys(payload).sort(), ['installId', 'schemaVersion', 'scopes', 'spikeRunId', 'status']);
    assert.equal(payload.schemaVersion, 1);
    assert.equal(payload.installId, SPIKE_INSTALL_ID);
    assert.ok(SPIKE_INSTALL_ID.startsWith('inst_spike_'), 'the install id is self-evidently synthetic');
    assert.equal(payload.status, IDENTITY_STATUS);
    assert.deepEqual(payload.scopes, [...SCOPES]);
    assert.match(payload.spikeRunId, /^run_spike_/);

    const again = toolPayload(await client.callTool({ name: TOOL_IDENTITY, arguments: { schemaVersion: 1 } }));
    assert.deepEqual(again, payload, 'a repeat call is identical, run included');

    const otherClient = await connectClient(spike, grant(spike).accessToken);
    const other = toolPayload(await otherClient.callTool({ name: TOOL_IDENTITY, arguments: { schemaVersion: 1 } }));
    assert.notEqual(other.spikeRunId, payload.spikeRunId, 'each credential gets its own run');
    assert.equal(other.installId, payload.installId);
    assert.equal(other.status, payload.status);
  });
});

describe('7. both tools reject everything outside their schema', () => {
  const identityRejections = [
    ['an unknown field', { schemaVersion: 1, spikeUnknown: 'x' }],
    ['a wrong type', { schemaVersion: '1' }],
    ['a missing required field', {}],
    ['a wrong schema version', { schemaVersion: 2 }],
    ['an omitted arguments object', undefined],
  ];

  test('identity rejects every malformed input with one closed code', async () => {
    const spike = await startSpike();
    const client = await connectClient(spike, grant(spike).accessToken);

    for (const [label, args] of identityRejections) {
      const result = await client.callTool({ name: TOOL_IDENTITY, arguments: args });
      assert.equal(toolErrorCode(result), CODES.SCHEMA_INVALID, `identity accepted ${label}`);
    }
  });

  test('finish rejects every malformed input with one closed code', async () => {
    const spike = await startSpike();
    const client = await connectClient(spike, grant(spike).accessToken);
    const runId = await mintRun(client);
    const row = { source: 'gmail', outcome: 'reported_observed', recordsReported: 3 };

    const rejections = [
      ['an unknown top-level field', finishInput(runId, 'req-unknown', { spikeUnknown: 1 })],
      ['an unknown field in a source row', finishInput(runId, 'req-row', { sources: [{ ...row, spikeUnknown: 1 }] })],
      ['a wrong type', finishInput(runId, 'req-type', { result: 7 })],
      ['a non-integer count', finishInput(runId, 'req-frac', { sources: [{ ...row, recordsReported: 1.5 }] })],
      ['a count above the range', finishInput(runId, 'req-high', { sources: [{ ...row, recordsReported: MAX_RECORDS_REPORTED + 1 }] })],
      ['a count below the range', finishInput(runId, 'req-low', { sources: [{ ...row, recordsReported: -1 }] })],
      ['a bad result enum', finishInput(runId, 'req-result', { result: 'ok' })],
      ['a bad outcome enum', finishInput(runId, 'req-outcome', { sources: [{ ...row, outcome: 'reported_maybe' }] })],
      ['a bad source enum', finishInput(runId, 'req-source', { sources: [{ ...row, source: 'imap' }] })],
      ['a missing required field', { schemaVersion: 1, runId, result: 'draft', clientRequestId: 'req-missing' }],
      ['a duplicate source row', finishInput(runId, 'req-dup', { sources: [row, { ...row }] })],
      ['an empty source list', finishInput(runId, 'req-empty', { sources: [] })],
      [
        'a non-zero count on a non-observed outcome',
        finishInput(runId, 'req-nonzero', {
          sources: [{ source: 'gmail', outcome: 'reported_zero_results', recordsReported: 2 }],
        }),
      ],
      ['an oversized clientRequestId', finishInput(runId, 'r'.repeat(MAX_CLIENT_REQUEST_ID_LENGTH + 1))],
      ['an empty clientRequestId', finishInput(runId, '')],
      ['a clientRequestId outside the charset', finishInput(runId, 'req id!')],
      ['a wrong schema version', finishInput(runId, 'req-version', { schemaVersion: 2 })],
    ];

    for (const [label, args] of rejections) {
      const result = await client.callTool({ name: TOOL_FINISH, arguments: args });
      assert.equal(toolErrorCode(result), CODES.SCHEMA_INVALID, `finish accepted ${label}`);
    }

    // A rejected call must not have consumed the run.
    const accepted = toolPayload(await client.callTool({ name: TOOL_FINISH, arguments: finishInput(runId, 'req-ok') }));
    assert.equal(accepted.status, RECEIPT_STATUS);
  });

  test('a zero count on a non-observed outcome is accepted', async () => {
    const spike = await startSpike();
    const client = await connectClient(spike, grant(spike).accessToken);
    const runId = await mintRun(client);

    const receipt = toolPayload(
      await client.callTool({
        name: TOOL_FINISH,
        arguments: finishInput(runId, 'req-zero', {
          result: 'no_changes',
          sources: [{ source: 'gmail', outcome: 'reported_unavailable', recordsReported: 0 }],
        }),
      }),
    );
    assert.equal(receipt.recordsRecorded, 0);
    assert.equal(receipt.result, 'no_changes');
  });
});

describe('8. finish replays and conflicts are closed', () => {
  test('the same request id replays, a changed payload conflicts, a second finish conflicts', async () => {
    const spike = await startSpike();
    const client = await connectClient(spike, grant(spike).accessToken);
    const runId = await mintRun(client);

    const first = toolPayload(await client.callTool({ name: TOOL_FINISH, arguments: finishInput(runId, 'req-1') }));
    assert.deepEqual(Object.keys(first).sort(), [
      'recordsRecorded',
      'result',
      'runId',
      'schemaVersion',
      'sourcesRecorded',
      'status',
    ]);
    assert.equal(first.status, RECEIPT_STATUS);
    assert.equal(first.runId, runId);
    assert.equal(first.sourcesRecorded, 1);
    assert.equal(first.recordsRecorded, 3);

    const replay = toolPayload(await client.callTool({ name: TOOL_FINISH, arguments: finishInput(runId, 'req-1') }));
    assert.deepEqual(replay, first, 'a repeated clientRequestId returns the original receipt verbatim');

    const mismatch = await client.callTool({
      name: TOOL_FINISH,
      arguments: finishInput(runId, 'req-1', { result: 'failed' }),
    });
    assert.equal(toolErrorCode(mismatch), CODES.REPLAY_MISMATCH);

    const second = await client.callTool({ name: TOOL_FINISH, arguments: finishInput(runId, 'req-2') });
    assert.equal(toolErrorCode(second), CODES.RUN_CLOSED);

    const stillReplays = toolPayload(
      await client.callTool({ name: TOOL_FINISH, arguments: finishInput(runId, 'req-1') }),
    );
    assert.deepEqual(stillReplays, first, 'a closed run still replays its own receipt');
  });
});

describe('9. an unknown or foreign runId is not an existence oracle', () => {
  test('a foreign run is refused exactly like a run that never existed', async () => {
    const spike = await startSpike();
    const client = await connectClient(spike, grant(spike).accessToken);
    const otherClient = await connectClient(spike, grant(spike).accessToken);
    const foreignRunId = await mintRun(otherClient);
    await mintRun(client);

    const unknown = await client.callTool({
      name: TOOL_FINISH,
      arguments: finishInput('run_spike_absent_0000', 'req-unknown'),
    });
    const foreign = await client.callTool({
      name: TOOL_FINISH,
      arguments: finishInput(foreignRunId, 'req-foreign'),
    });

    assert.equal(toolErrorCode(unknown), CODES.RUN_UNKNOWN);
    assert.deepEqual(foreign, unknown, 'the two rejections are byte-identical');
  });
});

describe('10. the body cap is enforced before the body is parsed', () => {
  test('one byte over the cap is refused without parsing', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);

    const response = await mcpFetch(spike, { token: accessToken, body: 'x'.repeat(MCP_BODY_LIMIT_BYTES + 1) });
    assert.equal(response.status, 413);
    assert.equal(await errorCode(response), CODES.TOO_LARGE);
  });

  test('the same unparseable body at the cap reaches the parser instead', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);

    const response = await mcpFetch(spike, { token: accessToken, body: 'x'.repeat(MCP_BODY_LIMIT_BYTES) });
    assert.equal(response.status, 400);
    assert.equal(await errorCode(response), CODES.SCHEMA_INVALID);
  });
});

describe('11. the deadline and concurrency caps return closed codes', () => {
  test('the shipped caps are the ones the brief names', () => {
    assert.equal(MCP_BODY_LIMIT_BYTES, 64 * 1024);
    assert.equal(MCP_DEADLINE_MS, 10_000);
    assert.equal(MCP_MAX_CONCURRENT_PER_CREDENTIAL, 4);
    assert.ok(SCOPES.includes(MCP_REQUIRED_SCOPE), 'the required scope is one the server grants');
  });

  test('a request that outlives the handling deadline is refused with a closed code', async () => {
    const spike = await startSpike({ mcpDeadlineMs: 60 });
    const { accessToken } = grant(spike);
    const stalled = openStalledPost(spike, accessToken, toolCall(TOOL_IDENTITY, { schemaVersion: 1 }));

    const response = await stalled.response;
    stalled.abort();
    assert.equal(response.status, 504);
    assert.deepEqual(JSON.parse(response.text), { error: CODES.DEADLINE_EXCEEDED });
  });

  test('a fifth concurrent request on one credential is refused, and another credential is not', async () => {
    const spike = await startSpike();
    const { credential, accessToken } = grant(spike);
    const limiter = spike.context.mcp.limiter;

    const stalled = [];
    for (let index = 0; index < MCP_MAX_CONCURRENT_PER_CREDENTIAL; index += 1) {
      stalled.push(openStalledPost(spike, accessToken, toolCall(TOOL_IDENTITY, { schemaVersion: 1 })));
    }
    await waitUntil(
      () => limiter.inFlight(credential.credentialId) === MCP_MAX_CONCURRENT_PER_CREDENTIAL,
      'four requests in flight',
    );

    const refused = await mcpFetch(spike, { token: accessToken, body: rpc('tools/list', {}) });
    assert.equal(refused.status, 429);
    assert.equal(await errorCode(refused), CODES.TOO_MANY_REQUESTS);

    const otherCredential = await mcpFetch(spike, { token: grant(spike).accessToken, body: rpc('tools/list', {}) });
    assert.equal(otherCredential.status, 200, 'the cap is per credential');

    for (const request of stalled) request.complete();
    for (const request of stalled) {
      const response = await request.response;
      assert.equal(response.status, 200);
    }
    assert.equal(limiter.inFlight(credential.credentialId), 0, 'every slot is released');
  });
});

describe('12. the surface is stateless', () => {
  test('two sequential tool calls on separate connections succeed with no session id', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);

    const first = openStalledPost(spike, accessToken, toolCall(TOOL_IDENTITY, { schemaVersion: 1 }));
    first.complete();
    const firstResponse = await first.response;
    assert.equal(firstResponse.status, 200);
    assert.equal(firstResponse.headers['mcp-session-id'], undefined, 'stateless mode issues no session id');

    const second = openStalledPost(spike, accessToken, toolCall(TOOL_IDENTITY, { schemaVersion: 1 }));
    second.complete();
    const secondResponse = await second.response;
    assert.equal(secondResponse.status, 200);
    assert.equal(secondResponse.headers['mcp-session-id'], undefined);

    const payloads = [firstResponse, secondResponse].map((response) => {
      const [message] = sseMessages(response.text);
      return JSON.parse(message.result.content[0].text);
    });
    assert.equal(payloads[0].installId, SPIKE_INSTALL_ID);
    assert.deepEqual(payloads[1], payloads[0], 'the second call sees the same run without a session');
  });
});

describe('13. a JSON-RPC error inside a 200 is never logged as a success', () => {
  /**
   * The SDK validates a request against its own zod schema before any handler
   * runs, and answers a failure with a JSON-RPC error envelope inside an HTTP
   * **200**. Nothing the spike owns sees that failure: no closed code is
   * recorded and the status is a success, so the only evidence it happened is
   * the envelope itself. The log is the operator's sole observation channel,
   * so a line that says `ok` for a call that failed is a false record.
   */
  test('the SDK\'s own request-schema failure is recorded as protocol_rejected', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);
    const before = spike.lines.length;

    const response = await mcpFetch(spike, { token: accessToken, body: toolCall(TOOL_IDENTITY, 'not-an-object') });
    const message = await rpcResponse(response);
    assert.equal(response.status, 200, 'the failure really does arrive inside a 200');
    assert.equal(message.result, undefined);
    assert.equal(message.error.code, ErrorCode.InternalError);

    assert.equal(spike.lines.length - before, 1, 'still exactly one line');
    assert.equal(lastOutcome(spike), LOG_OUTCOME.PROTOCOL_REJECTED);
  });

  test('a batch in which every entry fails is recorded, not just its last entry', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);
    const before = spike.lines.length;

    const response = await mcpFetch(spike, {
      token: accessToken,
      body: [toolCall(TOOL_IDENTITY, 'not-an-object'), toolCall(TOOL_FINISH, 'also-not-an-object')],
    });
    assert.equal(response.status, 200);
    const messages = sseMessages(await response.text());
    assert.equal(messages.length, 2, 'one response per batch entry');
    for (const message of messages) assert.equal(message.error.code, ErrorCode.InternalError);

    assert.equal(spike.lines.length - before, 1, 'a batch is still one request and one line');
    assert.equal(lastOutcome(spike), LOG_OUTCOME.PROTOCOL_REJECTED);
  });

  test('a healthy call in the same shape is still recorded as a success', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);

    await (await mcpFetch(spike, { token: accessToken, body: toolCall(TOOL_IDENTITY, { schemaVersion: 1 }) })).text();
    assert.equal(lastOutcome(spike), LOG_OUTCOME.OK_PROTOCOL_VERSION_ABSENT, 'the check must not swallow a success');
  });

  test('a closed code the spike recorded beats a nameless envelope', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);

    // Entry one is rejected by the spike's own validator (a closed code, worth
    // naming); entry two fails inside the SDK (an envelope, nameless). The
    // spike's own code is the more specific fact, so it is the one recorded.
    await (
      await mcpFetch(spike, {
        token: accessToken,
        body: [toolCall(TOOL_IDENTITY, { schemaVersion: 99 }), toolCall(TOOL_FINISH, 'not-an-object')],
      })
    ).text();
    assert.equal(lastOutcome(spike), LOG_OUTCOME.INVALID_REQUEST);
  });

  test('a batch with two differently-coded failures records the first, not the last', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);

    // Two failures with *different* closed outcomes. Recording whichever
    // handler settled last would make the line depend on scheduling; the first
    // failure in the batch is a property of the request itself.
    await (
      await mcpFetch(spike, {
        token: accessToken,
        body: [
          toolCall(TOOL_FINISH, finishInput('run_spike_never_minted', 'req_spike_batch')),
          rpc('prompts/list', {}),
        ],
      })
    ).text();
    assert.equal(lastOutcome(spike), LOG_OUTCOME.REJECTED, 'the run_unknown from entry one');

    // Reversed, the other one is first - so the order really is the request's.
    await (
      await mcpFetch(spike, {
        token: accessToken,
        body: [
          rpc('prompts/list', {}),
          toolCall(TOOL_FINISH, finishInput('run_spike_never_minted', 'req_spike_batch_2')),
        ],
      })
    ).text();
    assert.equal(lastOutcome(spike), LOG_OUTCOME.METHOD_UNSUPPORTED, 'the unknown_method from entry one');
  });

  test('an unimplemented method still reports its own closed outcome', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);

    // This path also produces an error envelope, but the spike's fallback
    // handler recorded a closed code first - which is strictly more specific.
    await (await mcpFetch(spike, { token: accessToken, body: rpc('prompts/list', {}) })).text();
    assert.equal(lastOutcome(spike), LOG_OUTCOME.METHOD_UNSUPPORTED);
  });
});

describe('14. the negotiated protocol version is recorded as its own closed outcome', () => {
  test('each version the pinned SDK supports has a distinct declared outcome', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);

    const seen = new Set();
    for (const version of SUPPORTED_PROTOCOL_VERSIONS) {
      await (await mcpFetchWithVersion(spike, accessToken, version, rpc('tools/list', {}))).text();
      const outcome = lastOutcome(spike);
      assert.ok(ALL_LOG_OUTCOMES.has(outcome), `${version} produced an undeclared outcome ${outcome}`);
      assert.ok(ALL_PROTOCOL_VERSION_OUTCOMES.has(outcome), `${version} was not recorded by value`);
      assert.ok(outcome.includes(version.replaceAll('-', '_')), `${outcome} does not name ${version}`);
      assert.ok(!seen.has(outcome), `${version} shares an outcome with another version`);
      seen.add(outcome);
    }
    assert.equal(seen.size, SUPPORTED_PROTOCOL_VERSIONS.length);
  });

  test('the line keeps its four keys and its closed enums', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);

    await (await mcpFetchWithVersion(spike, accessToken, LATEST_PROTOCOL_VERSION, rpc('tools/list', {}))).text();
    const line = JSON.parse(spike.lines.at(-1));
    assert.deepEqual(Object.keys(line), ['ts', 'testRunId', 'class', 'outcome']);
    assert.equal(line.class, LOG_CLASS.MCP);
    assert.ok(ALL_LOG_OUTCOMES.has(line.outcome));
  });

  test('an absent header is still the absent outcome', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);

    await (await mcpFetch(spike, { token: accessToken, body: rpc('tools/list', {}) })).text();
    assert.equal(lastOutcome(spike), LOG_OUTCOME.OK_PROTOCOL_VERSION_ABSENT);
  });

  test('a version outside the SDK\'s list is a plain ok, never a manufactured outcome', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);
    const outOfList = '2099-01-01';
    assert.ok(!SUPPORTED_PROTOCOL_VERSIONS.includes(outOfList));

    // `initialize` is the one message the version screen skips, so it is the
    // only way an unlisted value reaches the recorder at all. It has no closed
    // outcome of its own by construction: the enum is the SDK's list, and the
    // value itself is never written anywhere.
    await (await mcpFetchWithVersion(spike, accessToken, outOfList, initializeMessage())).text();
    assert.equal(lastOutcome(spike), LOG_OUTCOME.OK);
    assert.ok(!spike.lines.at(-1).includes(outOfList), 'the value itself must never be written');
  });

  test('every outcome the recorder can return is declared', () => {
    for (const version of [...SUPPORTED_PROTOCOL_VERSIONS, '2099-01-01', 'constructor', '__proto__']) {
      const outcome = outcomeForProtocolVersion(version);
      assert.equal(typeof outcome, 'string', `${version} produced a non-string outcome`);
      assert.ok(ALL_LOG_OUTCOMES.has(outcome), `${version} produced an undeclared outcome`);
    }
    assert.equal(outcomeForProtocolVersion(undefined), LOG_OUTCOME.OK_PROTOCOL_VERSION_ABSENT);
  });
});

describe('15. each tool requires its own scope, not one blanket scope', () => {
  test('a read-narrowed credential may read identity but may not submit a finish', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike, { scopes: [SCOPE_CONTEXT_READ] });
    const client = await connectClient(spike, accessToken);

    const runId = await mintRun(client);
    const refused = await client.callTool({ name: TOOL_FINISH, arguments: finishInput(runId, 'req_spike_narrow') });
    assert.equal(toolErrorCode(refused), CODES.SCOPE_INSUFFICIENT);
  });

  test('the same finish succeeds on a credential that holds the submit scope', async () => {
    const spike = await startSpike();
    const client = await connectClient(spike, grant(spike).accessToken);

    const runId = await mintRun(client);
    const receipt = toolPayload(
      await client.callTool({ name: TOOL_FINISH, arguments: finishInput(runId, 'req_spike_wide') }),
    );
    assert.equal(receipt.runId, runId);
  });

  test('a refusal on scope is recorded as its own closed outcome, never as ok', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike, { scopes: [SCOPE_CONTEXT_READ] });
    const client = await connectClient(spike, accessToken);
    const runId = await mintRun(client);

    const before = spike.lines.length;
    await client.callTool({ name: TOOL_FINISH, arguments: finishInput(runId, 'req_spike_logged') });
    const outcomes = spike.lines.slice(before).map((line) => JSON.parse(line).outcome);
    assert.ok(outcomes.includes(LOG_OUTCOME.REJECTED), `a scope refusal was logged as ${outcomes.join(',')}`);
  });

  test('the scope check runs before the tool sees its arguments', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike, { scopes: [SCOPE_CONTEXT_READ] });
    const client = await connectClient(spike, accessToken);

    // Arguments that would fail schema validation. Reporting the schema code
    // here would tell a caller its narrowed credential *would* have been
    // allowed to submit, which the scope refusal exists to withhold.
    const refused = await client.callTool({ name: TOOL_FINISH, arguments: { schemaVersion: 99 } });
    assert.equal(toolErrorCode(refused), CODES.SCOPE_INSUFFICIENT);
  });

  test('a narrowing that drops the connection scope is refused before any tool runs', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike, { scopes: [SCOPE_DRAFT_SUBMIT] });

    const response = await mcpFetch(spike, { token: accessToken, body: rpc('tools/list', {}) });
    assert.equal(response.status, 401);
    assert.equal(await errorCode(response), CODES.UNAUTHORIZED);
  });

  test('the two tools do not share one scope', () => {
    assert.notEqual(SCOPE_CONTEXT_READ, SCOPE_DRAFT_SUBMIT);
    assert.deepEqual([...SCOPES].sort(), [SCOPE_CONTEXT_READ, SCOPE_DRAFT_SUBMIT].sort());
    assert.equal(MCP_REQUIRED_SCOPE, SCOPE_CONTEXT_READ);
  });
});

describe('supplementary: transport shape, method surface and logging', () => {
  test('GET and DELETE are authenticated first, then refused as methods', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);

    for (const method of ['GET', 'DELETE']) {
      const anonymous = await mcpFetch(spike, { method, contentType: null, body: undefined });
      assert.equal(anonymous.status, 401, `${method} must authenticate first`);
      assert.equal(await errorCode(anonymous), CODES.UNAUTHORIZED);

      const authenticated = await mcpFetch(spike, { method, token: accessToken, contentType: null, body: undefined });
      assert.equal(authenticated.status, 405, `${method} must be refused`);
      assert.equal(await errorCode(authenticated), CODES.METHOD_NOT_ALLOWED);
    }
  });

  test('an Accept header without text/event-stream is refused with a closed code', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);

    const response = await mcpFetch(spike, {
      token: accessToken,
      accept: 'application/json',
      body: rpc('tools/list', {}),
    });
    assert.equal(response.status, 406);
    assert.equal(await errorCode(response), CODES.NOT_ACCEPTABLE);
  });

  test('a non-JSON content type is refused with a closed code', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);

    const response = await mcpFetch(spike, {
      token: accessToken,
      contentType: 'text/plain',
      body: rpc('tools/list', {}),
    });
    assert.equal(response.status, 415);
    assert.equal(await errorCode(response), CODES.UNSUPPORTED_MEDIA_TYPE);
  });

  test('a body that is not JSON is refused with a closed code', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);

    const response = await mcpFetch(spike, { token: accessToken, body: '{ not json' });
    assert.equal(response.status, 400);
    assert.equal(await errorCode(response), CODES.SCHEMA_INVALID);
  });

  test('an unknown tool name is a closed code, not a library message', async () => {
    const spike = await startSpike();
    const client = await connectClient(spike, grant(spike).accessToken);

    const result = await client.callTool({ name: 'dayfold_spike_not_a_tool', arguments: {} });
    assert.equal(toolErrorCode(result), CODES.UNKNOWN_TOOL);
  });

  test('an /mcp response carries the security headers', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);
    const response = await mcpFetch(spike, { token: accessToken, body: rpc('tools/list', {}) });

    assert.equal(response.status, 200);
    assert.equal(response.headers.get('referrer-policy'), 'no-referrer');
    assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
    assert.equal(response.headers.get('content-security-policy'), EXPECTED_CSP);
    // Recorded deviation: the SDK's event-stream response replaces the staged
    // `no-store` with the streaming `no-cache, no-transform`. Both forbid a
    // cached copy; the value is the transport's, not the spike's.
    assert.match(response.headers.get('cache-control'), /no-store|no-cache/);
    assert.equal((await mcpFetch(spike, { body: rpc('tools/list', {}) })).headers.get('cache-control'), 'no-store');
  });

  test('no envelope-layer refusal echoes the caller\'s own text', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);
    const marker = 'spike-marker-Zq7xK3';

    // Every path where caller-controlled text reaches the SDK's own JSON-RPC
    // layer, each carrying a marker in the one field that path reads - including
    // the initialize header, which the spike deliberately passes through.
    const probes = [
      ['an unsupported protocol version', () => mcpFetchWithVersion(spike, accessToken, marker, rpc('tools/list', {}))],
      ['an unsupported protocol version on initialize', () => mcpFetchWithVersion(spike, accessToken, marker, initializeMessage())],
      ['a malformed envelope', () => mcpFetch(spike, { token: accessToken, body: { jsonrpc: marker, id: 1, method: 'tools/list' } })],
      ['an unimplemented method', () => mcpFetch(spike, { token: accessToken, body: rpc(`prompts/${marker}`, {}) })],
      ['non-object tool arguments', () => mcpFetch(spike, { token: accessToken, body: toolCall(TOOL_IDENTITY, marker) })],
    ];

    for (const [label, send] of probes) {
      const raw = await (await send()).text();
      assert.ok(raw.length > 0, `${label} answered with nothing`);
      assert.ok(!raw.includes(marker), `${label} echoed caller-controlled text: ${raw}`);
    }
  });

  test('an unsupported protocol version is refused with a closed code', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);

    const response = await mcpFetchWithVersion(spike, accessToken, '1999-01-01', rpc('tools/list', {}));
    assert.equal(response.status, 400);
    assert.equal(await errorCode(response), CODES.UNSUPPORTED_PROTOCOL_VERSION);

    for (const supported of SUPPORTED_PROTOCOL_VERSIONS) {
      const accepted = await mcpFetchWithVersion(spike, accessToken, supported, rpc('tools/list', {}));
      assert.equal(accepted.status, 200, `${supported} must be accepted`);
      await accepted.text();
    }
  });

  test('the screen runs only where the SDK screens, so initialize still negotiates', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);
    const outOfList = '2099-01-01';
    assert.ok(!SUPPORTED_PROTOCOL_VERSIONS.includes(outOfList));

    // An initialize carries its version in the body, so the SDK never screens
    // the header for one - and refusing here would manufacture a failed
    // connection for a surface the real bridge accepts.
    const initialized = await mcpFetchWithVersion(spike, accessToken, outOfList, initializeMessage());
    assert.equal(initialized.status, 200);
    const negotiated = await rpcResponse(initialized);
    assert.equal(negotiated.error, undefined);
    assert.ok(
      SUPPORTED_PROTOCOL_VERSIONS.includes(negotiated.result.protocolVersion),
      'initialize must negotiate a supported version',
    );

    // Every other message is still screened, with no echo.
    const refused = await mcpFetchWithVersion(spike, accessToken, outOfList, rpc('tools/list', {}));
    assert.equal(refused.status, 400);
    const raw = await refused.clone().text();
    assert.ok(!raw.includes(outOfList), 'the refusal must not echo the version');
    assert.equal(await errorCode(refused), CODES.UNSUPPORTED_PROTOCOL_VERSION);
  });

  test('an unimplemented method keeps the standard code with a closed message', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);

    const message = await rpcResponse(await mcpFetch(spike, { token: accessToken, body: rpc('prompts/list', {}) }));
    assert.equal(message.result, undefined);
    assert.equal(message.error.code, ErrorCode.MethodNotFound);
    assert.equal(message.error.message, CODES.UNKNOWN_METHOD);
  });

  test('transport-layer refusals and protocol probes each get their own outcome', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);

    const cases = [
      ['an unsupported protocol version', () => mcpFetchWithVersion(spike, accessToken, '1999-01-01', rpc('tools/list', {})), LOG_OUTCOME.INVALID_REQUEST],
      ['a malformed envelope', () => mcpFetch(spike, { token: accessToken, body: { jsonrpc: '1.0', id: 1, method: 'tools/list' } }), LOG_OUTCOME.PROTOCOL_REJECTED],
      ['an unimplemented method', () => mcpFetch(spike, { token: accessToken, body: rpc('resources/list', {}) }), LOG_OUTCOME.METHOD_UNSUPPORTED],
      ['no protocol version header', () => mcpFetch(spike, { token: accessToken, body: rpc('tools/list', {}) }), LOG_OUTCOME.OK_PROTOCOL_VERSION_ABSENT],
      ['a supported protocol version', () => mcpFetchWithVersion(spike, accessToken, LATEST_PROTOCOL_VERSION, rpc('tools/list', {})), outcomeForProtocolVersion(LATEST_PROTOCOL_VERSION)],
      // A 200 whose body carries the SDK's own JSON-RPC error envelope. The
      // failure never reaches the spike's handlers, so nothing records a code
      // and the status is a success: without the envelope check this reads as
      // a healthy call.
      ['an error envelope inside a 200', () => mcpFetch(spike, { token: accessToken, body: toolCall(TOOL_IDENTITY, 'not-an-object') }), LOG_OUTCOME.PROTOCOL_REJECTED],
    ];

    for (const [label, send, expected] of cases) {
      const before = spike.lines.length;
      await (await send()).text();
      assert.equal(spike.lines.length - before, 1, `${label} wrote more than one line`);
      assert.equal(lastOutcome(spike), expected, label);
    }
  });

  test('every /mcp request writes exactly one content-blind log line', async () => {
    const spike = await startSpike();
    const { accessToken } = grant(spike);
    const before = spike.lines.length;

    await mcpFetch(spike, { token: accessToken, body: rpc('tools/list', {}) });
    assert.equal(spike.lines.length - before, 1);

    const line = JSON.parse(spike.lines.at(-1));
    assert.deepEqual(Object.keys(line), ['ts', 'testRunId', 'class', 'outcome']);
    assert.equal(line.class, LOG_CLASS.MCP);
    assert.ok(ALL_LOG_OUTCOMES.has(line.outcome));
  });

  test('a tool-level rejection is recorded as its own closed outcome', async () => {
    const spike = await startSpike();
    const client = await connectClient(spike, grant(spike).accessToken);
    const before = spike.lines.length;

    await client.callTool({ name: TOOL_IDENTITY, arguments: { schemaVersion: 99 } });
    const outcomes = spike.lines.slice(before).map((line) => JSON.parse(line).outcome);
    assert.ok(outcomes.includes('invalid_request'), 'a rejected tool call is not logged as a success');
  });
});
