// Content-blind diagnostics and leak canaries.
//
// This suite is the standing guarantee behind global constraints 5 and 6: that
// a log line carries four content-free keys and nothing else, and that no
// caller-supplied value reaches a diagnostic surface - a log line, an error
// body, an SDK-originated error string, or a response header.
//
// Every test drives a real server on an ephemeral port in-process through the
// factory, over a real socket. No child process, no mock of the server, no
// outbound network: the redirect URI is a synthetic `example.invalid` host and
// every request that could redirect there is sent with `redirect: 'manual'`
// (or read raw, which never follows).
//
// The canary assertions are deliberately written as "this marker is absent",
// never as "this response matches a known-good string": an allow-list of known
// library prose would stop catching a future SDK that starts echoing input at
// the same layer, which is the exact failure mode this suite exists to catch.

import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import { Agent, request as httpRequest } from 'node:http';
import { dirname, join, relative, resolve } from 'node:path';
import { after, describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';

import { ErrorCode } from '@modelcontextprotocol/sdk/types.js';

import { ALL_CODES, CODES } from '../src/codes.mjs';
import {
  MAX_CLIENT_NAME_LENGTH,
  MCP_BODY_LIMIT_BYTES,
  MCP_MAX_CONCURRENT_PER_CREDENTIAL,
  OAUTH_BODY_LIMIT_BYTES,
  SCOPES,
  STATIC_CLIENT_ID,
} from '../src/constants.mjs';
import { hmacBase64Url, sha256Base64Url } from '../src/crypto.mjs';
import { isForbiddenEnvName } from '../src/guard.mjs';
import {
  ALL_LOG_CLASSES,
  ALL_LOG_OUTCOMES,
  LOG_CLASS,
  LOG_OUTCOME,
  createLogger,
} from '../src/log.mjs';
import { TOOL_FINISH, TOOL_IDENTITY } from '../src/mcp-schema.mjs';
import { escapeHtml } from '../src/pages.mjs';
import { createSpikeServer } from '../src/server.mjs';

const REDIRECT_URI = 'https://example.invalid/spike-callback';
const START_MS = Date.UTC(2026, 7, 20, 12, 0, 0);
const MCP_ACCEPT = 'application/json, text/event-stream';
const LOG_KEYS = ['ts', 'testRunId', 'class', 'outcome'];

/**
 * One canary per content category, so a failure names which kind of value
 * leaked. Every string is self-evidently synthetic: no real person, mailbox,
 * household, provider or token is represented anywhere here.
 */
const CANARY = Object.freeze({
  MEDICAL: 'canary-medical-mri-result-Qk7',
  FINANCIAL: 'canary-financial-overdraft-Rt4',
  CHILD_NAME: 'canary-child-Fakename-Nn2',
  EMAIL: 'canary-person@example.invalid',
  URL: 'https://canary.example.invalid/leak-probe',
  BEARER: 'canary-bearer-token-Vv9',
  OAUTH_CODE: 'canary-oauth-code-Ww5',
  PROVIDER_ERROR: 'canary-provider-error-ECONNREFUSED-Xy8',
});

const ALL_CANARIES = Object.freeze(Object.values(CANARY));

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

// --------------------------------------------------------------------------
// Raw HTTP, so a probe can set a `Host` header, repeat a header, or send a
// body that `fetch` would normalize away.
// --------------------------------------------------------------------------

/**
 * @param {{port: number}} spike
 * @param {{method?: string, path?: string, headers?: Record<string, string|string[]>, body?: string}} spec
 */
function rawRequest(spike, spec) {
  const { method = 'GET', path = '/', headers = {}, body } = spec;
  const payload = body === undefined ? undefined : Buffer.from(body, 'utf8');
  return new Promise((settle, reject) => {
    const req = httpRequest(
      {
        host: '127.0.0.1',
        port: spike.port,
        method,
        path,
        agent: new Agent({ keepAlive: false }),
        headers: {
          ...(payload === undefined ? {} : { 'content-length': payload.length }),
          ...headers,
        },
      },
      (res) => {
        const chunks = [];
        res.on('data', (chunk) => chunks.push(chunk));
        const resolve = () =>
          settle({
            status: res.statusCode,
            headers: res.headers,
            rawHeaders: res.rawHeaders.join('\n'),
            body: Buffer.concat(chunks).toString('utf8'),
          });
        res.once('end', resolve);
        res.once('aborted', resolve);
      },
    );
    req.once('error', reject);
    if (payload !== undefined) req.write(payload);
    req.end();
  });
}

/** Everything this probe actually put on the wire, for a transmission check. */
function sentText(spec) {
  return [
    `${spec.method ?? 'GET'} ${spec.path ?? '/'}`,
    JSON.stringify(spec.headers ?? {}),
    spec.body ?? '',
  ].join('\n');
}

function query(pairs) {
  const params = new URLSearchParams();
  for (const [key, value] of pairs) {
    if (value !== undefined) params.append(key, value);
  }
  return params.toString();
}

function form(fields) {
  return new URLSearchParams(fields).toString();
}

const FORM_HEADERS = { 'content-type': 'application/x-www-form-urlencoded' };
const JSON_HEADERS = { 'content-type': 'application/json' };

function mcpHeaders(token, extra = {}) {
  return {
    accept: MCP_ACCEPT,
    'content-type': 'application/json',
    authorization: `Bearer ${token}`,
    ...extra,
  };
}

// --------------------------------------------------------------------------
// OAuth flow helpers
// --------------------------------------------------------------------------

function pkce() {
  const verifier = randomBytes(32).toString('base64url');
  return { verifier, challenge: createHash('sha256').update(verifier).digest('base64url') };
}

function authorizeQuery(spike, overrides = {}, { challenge }) {
  const base = {
    response_type: 'code',
    client_id: STATIC_CLIENT_ID,
    redirect_uri: REDIRECT_URI,
    resource: spike.url,
    scope: SCOPES.join(' '),
    code_challenge: challenge,
    code_challenge_method: 'S256',
    ...overrides,
  };
  return query(Object.entries(base));
}

function approvalTicket(html) {
  const match = html.match(/name="approval" value="([^"]+)"/);
  assert.ok(match, 'the consent page must carry a single-use approval ticket');
  return match[1];
}

/** authorize -> consent page -> approve -> the redirect that carries the code. */
async function authorizeAndApprove(spike, overrides = {}) {
  const keys = pkce();
  const page = await rawRequest(spike, {
    path: `/oauth/authorize?${authorizeQuery(spike, overrides, keys)}`,
  });
  assert.equal(page.status, 200, 'the authorize request must render a consent page');
  const approved = await rawRequest(spike, {
    method: 'POST',
    path: '/oauth/approve',
    headers: FORM_HEADERS,
    body: form({ approval: approvalTicket(page.body) }),
  });
  assert.equal(approved.status, 302);
  const location = new URL(approved.headers.location);
  return {
    ...keys,
    page,
    approved,
    location,
    ticket: approvalTicket(page.body),
    code: location.searchParams.get('code'),
  };
}

async function grantTokens(spike) {
  const granted = await authorizeAndApprove(spike);
  const exchanged = await rawRequest(spike, {
    method: 'POST',
    path: '/oauth/token',
    headers: FORM_HEADERS,
    body: form({
      grant_type: 'authorization_code',
      code: granted.code,
      redirect_uri: REDIRECT_URI,
      client_id: STATIC_CLIENT_ID,
      code_verifier: granted.verifier,
      resource: spike.url,
    }),
  });
  assert.equal(exchanged.status, 200);
  return { ...granted, tokens: JSON.parse(exchanged.body) };
}

/**
 * A credential and a genuine access token for it, minted by the server's own
 * issuer, so every check `/mcp` performs is performed for real. The OAuth dance
 * itself is covered by the flow helpers above and by `oauth.test.mjs`.
 */
function grant(spike) {
  const credential = spike.context.store.createCredential({
    clientId: STATIC_CLIENT_ID,
    redirectUri: REDIRECT_URI,
    resource: spike.resourceOrigin,
    scopes: [...SCOPES],
  });
  return {
    credential,
    accessToken: spike.context.tokens.issue({
      issuer: spike.resourceOrigin,
      resource: credential.resource,
      credentialId: credential.credentialId,
      clientId: credential.clientId,
      scopes: credential.scopes,
    }),
  };
}

// --------------------------------------------------------------------------
// JSON-RPC helpers
// --------------------------------------------------------------------------

let rpcSequence = 0;
function rpc(method, params, overrides = {}) {
  rpcSequence += 1;
  return { jsonrpc: '2.0', id: rpcSequence, method, params, ...overrides };
}

function toolCall(name, args, overrides = {}) {
  return rpc('tools/call', { name, arguments: args }, overrides);
}

function initializeMessage(overrides = {}) {
  return rpc('initialize', {
    protocolVersion: '2025-06-18',
    capabilities: {},
    clientInfo: { name: 'spike-test-client', version: '0.0.0' },
    ...overrides,
  });
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

/** The transport answers a POST with one terminating event stream. */
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

/**
 * The transport answers a well-formed POST with an event stream, but rejects a
 * malformed envelope with a bare JSON body. Both framings are read here so an
 * assertion can name the JSON-RPC error code either way.
 */
function singleRpcMessage(response) {
  if (!response.body.includes('data:')) return JSON.parse(response.body);
  const messages = sseMessages(response.body);
  assert.equal(messages.length, 1, 'one JSON-RPC response per request');
  return messages[0];
}

async function mcpPost(spike, token, message, extraHeaders = {}) {
  return rawRequest(spike, {
    method: 'POST',
    path: '/mcp',
    headers: mcpHeaders(token, extraHeaders),
    body: JSON.stringify(message),
  });
}

/** Mints the one run this credential owns, and returns its server-chosen id. */
async function mintRun(spike, token) {
  const response = await mcpPost(spike, token, toolCall(TOOL_IDENTITY, { schemaVersion: 1 }));
  assert.equal(response.status, 200);
  const message = singleRpcMessage(response);
  assert.notEqual(message.result?.isError, true, 'identity must succeed');
  return JSON.parse(message.result.content[0].text).spikeRunId;
}

// --------------------------------------------------------------------------
// Log-line helpers
// --------------------------------------------------------------------------

function parsedLines(spike) {
  return spike.lines.map((line, index) => {
    let parsed;
    try {
      parsed = JSON.parse(line);
    } catch {
      assert.fail(`log line ${index} is not JSON: ${line}`);
    }
    return parsed;
  });
}

function assertContentBlindLine(entry, label) {
  assert.deepEqual(
    Object.keys(entry),
    LOG_KEYS,
    `${label} must carry exactly ${LOG_KEYS.join(', ')} in that order`,
  );
  assert.ok(ALL_LOG_CLASSES.has(entry.class), `${label} class ${entry.class} is not declared`);
  assert.ok(ALL_LOG_OUTCOMES.has(entry.outcome), `${label} outcome ${entry.outcome} is not declared`);
  assert.equal(typeof entry.testRunId, 'string');
  assert.ok(!Number.isNaN(Date.parse(entry.ts)), `${label} ts is not a timestamp`);
}

/** Everything the sink was ever handed, as one searchable string. */
function logText(spike) {
  return spike.lines.join('');
}

// --------------------------------------------------------------------------
// Canary matching
// --------------------------------------------------------------------------

/**
 * The encodings a canary could survive in. A leak that percent-encoded, HTML-
 * escaped or JSON-escaped the value on the way out is still a leak, so every
 * form is searched, not just the literal one.
 */
function canaryForms(value) {
  const json = JSON.stringify(value);
  return [
    ...new Set([
      value,
      encodeURIComponent(value),
      escapeHtml(value),
      json.slice(1, -1),
      Buffer.from(value, 'utf8').toString('base64'),
    ]),
  ];
}

function assertAbsent(haystack, value, label) {
  for (const form of canaryForms(value)) {
    assert.ok(!haystack.includes(form), `${label} carries the canary (as ${JSON.stringify(form)})`);
  }
}

function assertNoCanaryInLog(spike, label) {
  const text = logText(spike);
  for (const canary of ALL_CANARIES) assertAbsent(text, canary, `${label}: a log line`);
}

describe('1. every diagnostic line the full drive produces is content-blind', () => {
  /**
   * Discovery, registration, the whole OAuth dance, every MCP tool success and
   * every reachable failure class, driven end to end against one server. The
   * lines it produces are read off the server's own sink afterwards.
   */
  async function driveEverything(spike) {
    const { accessToken } = grant(spike);

    // Discovery, health, and the routing refusals.
    await rawRequest(spike, { path: '/.well-known/oauth-protected-resource' });
    await rawRequest(spike, { path: '/.well-known/oauth-authorization-server' });
    await rawRequest(spike, { path: '/healthz' });
    await rawRequest(spike, { path: '/not-a-real-route' });
    await rawRequest(spike, { method: 'POST', path: '/healthz' });
    await rawRequest(spike, { method: 'POST', path: '/oauth/register', headers: JSON_HEADERS, body: '{}' });

    // The full authorization-code dance, then refresh and revoke.
    const granted = await grantTokens(spike);
    const refreshed = await rawRequest(spike, {
      method: 'POST',
      path: '/oauth/token',
      headers: FORM_HEADERS,
      body: form({
        grant_type: 'refresh_token',
        refresh_token: granted.tokens.refresh_token,
        client_id: STATIC_CLIENT_ID,
      }),
    });
    assert.equal(refreshed.status, 200, 'the refresh grant must succeed');
    await rawRequest(spike, {
      method: 'POST',
      path: '/oauth/revoke',
      headers: FORM_HEADERS,
      body: form({ token: JSON.parse(refreshed.body).refresh_token }),
    });

    // OAuth failure classes.
    await rawRequest(spike, { path: `/oauth/authorize?${query([['response_type', 'code']])}` });
    await rawRequest(spike, {
      method: 'POST',
      path: '/oauth/token',
      headers: FORM_HEADERS,
      body: form({
        grant_type: 'authorization_code',
        code: 'code_spike_never_issued',
        redirect_uri: REDIRECT_URI,
        client_id: STATIC_CLIENT_ID,
        code_verifier: 'a'.repeat(43),
      }),
    });
    await rawRequest(spike, {
      method: 'POST',
      path: '/oauth/token',
      headers: FORM_HEADERS,
      body: `grant_type=authorization_code&code=${'a'.repeat(OAUTH_BODY_LIMIT_BYTES + 1)}`,
    });

    // Every MCP success, then every reachable MCP failure class.
    await mcpPost(spike, accessToken, initializeMessage());
    await mcpPost(spike, accessToken, rpc('tools/list', {}));
    const runId = await mintRun(spike, accessToken);
    await mcpPost(spike, accessToken, toolCall(TOOL_FINISH, finishInput(runId, 'req_spike_first')));
    await mcpPost(spike, accessToken, toolCall(TOOL_FINISH, finishInput(runId, 'req_spike_second')));
    await mcpPost(spike, accessToken, toolCall(TOOL_FINISH, finishInput(runId, 'req_spike_first', { result: 'failed' })));
    await mcpPost(spike, accessToken, toolCall(TOOL_FINISH, finishInput('run_spike_never_minted', 'req_spike_third')));
    await mcpPost(spike, accessToken, toolCall(TOOL_IDENTITY, { schemaVersion: 99 }));
    await mcpPost(spike, accessToken, toolCall('dayfold_spike_not_a_tool', {}));
    await mcpPost(spike, accessToken, rpc('prompts/list', {}));
    await mcpPost(spike, accessToken, { jsonrpc: '1.0', id: 1, method: 'tools/list' });
    await mcpPost(spike, accessToken, rpc('tools/list', {}), { 'mcp-protocol-version': '1999-01-01' });
    await rawRequest(spike, { method: 'POST', path: '/mcp', headers: mcpHeaders(accessToken), body: '{' });
    await rawRequest(spike, { method: 'GET', path: '/mcp', headers: mcpHeaders(accessToken) });
    await rawRequest(spike, { method: 'POST', path: '/mcp', headers: JSON_HEADERS, body: '{}' });
    await rawRequest(spike, {
      method: 'POST',
      path: '/mcp',
      headers: { ...mcpHeaders(accessToken), accept: 'text/plain' },
      body: JSON.stringify(rpc('tools/list', {})),
    });
    await rawRequest(spike, {
      method: 'POST',
      path: '/mcp',
      headers: { ...mcpHeaders(accessToken), 'content-type': 'text/plain' },
      body: JSON.stringify(rpc('tools/list', {})),
    });
    await rawRequest(spike, {
      method: 'POST',
      path: '/mcp',
      headers: mcpHeaders(accessToken),
      body: `{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"pad":"${'a'.repeat(MCP_BODY_LIMIT_BYTES)}"}}`,
    });
  }

  test('the full drive writes only four-key lines drawn from the declared enums', async () => {
    const spike = await startSpike({ dcr: true });
    await driveEverything(spike);

    const entries = parsedLines(spike);
    assert.ok(entries.length > 25, `the drive must exercise the server broadly, saw ${entries.length}`);
    entries.forEach((entry, index) => assertContentBlindLine(entry, `line ${index}`));

    // Every line shares the one per-process correlation handle and nothing else.
    const runIds = new Set(entries.map((entry) => entry.testRunId));
    assert.deepEqual([...runIds], [spike.testRunId]);
  });

  test('the drive reaches every declared outcome except the unreachable internal one', async () => {
    // A short deadline and a saturated credential are the only two outcomes
    // that need their own server shape. The deadline is generous relative to
    // the work between saturating the cap and probing it, so a loaded machine
    // cannot free a slot mid-probe and turn the 429 into a 200.
    const spike = await startSpike({ dcr: true, mcpDeadlineMs: 300 });
    await driveEverything(spike);

    const { credential, accessToken } = grant(spike);
    const stalled = [];
    for (let index = 0; index < MCP_MAX_CONCURRENT_PER_CREDENTIAL; index += 1) {
      stalled.push(openStalledPost(spike, accessToken, toolCall(TOOL_IDENTITY, { schemaVersion: 1 })));
    }
    await waitUntil(
      () => spike.context.mcp.limiter.inFlight(credential.credentialId) === MCP_MAX_CONCURRENT_PER_CREDENTIAL,
      'every concurrency slot taken',
    );
    const throttled = await mcpPost(spike, accessToken, rpc('tools/list', {}));
    assert.equal(throttled.status, 429, 'a saturated credential is throttled');
    for (const request of stalled) request.complete();
    for (const request of stalled) assert.equal((await request.response).status, 200);

    // One request that never finishes sending runs out the deadline instead.
    const abandoned = openStalledPost(spike, accessToken, toolCall(TOOL_IDENTITY, { schemaVersion: 1 }));
    assert.equal((await abandoned.response).status, 504, 'an unfinished request hits the deadline');

    const outcomes = new Set(parsedLines(spike).map((entry) => entry.outcome));
    const missing = [...ALL_LOG_OUTCOMES].filter((outcome) => !outcomes.has(outcome));
    // `error` maps only from `spike.internal`, which no reachable input
    // produces - a property worth pinning, not a gap.
    assert.deepEqual(missing, [LOG_OUTCOME.ERROR], `unreached outcomes: ${missing.join(', ')}`);

    for (const entry of parsedLines(spike)) assertContentBlindLine(entry, 'drive line');
  });

  test('nothing but the log sink writes to stdout or stderr during the drive', async () => {
    // The node:test reporter writes its own frames to `process.stdout` from
    // inside this process, so stdout cannot be captured across an await without
    // swallowing them. Instead: `console.*` (which the reporter never uses) and
    // `process.stderr` are captured across the whole drive, and the default
    // stdout sink is proven separately, below, in a synchronous window.
    const captured = [];
    const realStderr = process.stderr.write.bind(process.stderr);
    const consoleMethods = ['log', 'info', 'warn', 'error', 'debug', 'trace', 'dir'];
    const realConsole = Object.fromEntries(consoleMethods.map((name) => [name, console[name]]));

    let spike;
    try {
      process.stderr.write = (chunk) => {
        captured.push(`stderr: ${String(chunk)}`);
        return true;
      };
      for (const name of consoleMethods) {
        console[name] = (...args) => captured.push(`console.${name}: ${args.join(' ')}`);
      }
      spike = await startSpike({ dcr: true });
      await driveEverything(spike);
    } finally {
      process.stderr.write = realStderr;
      for (const name of consoleMethods) console[name] = realConsole[name];
    }

    assert.deepEqual(captured, [], 'the drive wrote outside the log sink');
    assert.ok(spike.lines.length > 25, 'the drive still produced its log lines');
  });

  test('the default sink is stdout, and it writes the same four-key line', async () => {
    // A synchronous window: no other writer can interleave, so this can patch
    // `process.stdout.write` without swallowing a reporter frame.
    const captured = [];
    const realStdout = process.stdout.write.bind(process.stdout);
    try {
      process.stdout.write = (chunk) => {
        captured.push(String(chunk));
        return true;
      };
      createLogger({ testRunId: 'run_spike_default_sink' })(LOG_CLASS.HEALTH, LOG_OUTCOME.OK);
    } finally {
      process.stdout.write = realStdout;
    }

    assert.equal(captured.length, 1, 'the default sink writes exactly one line');
    assert.ok(captured[0].endsWith('\n'), 'a log line is newline terminated');
    assertContentBlindLine(JSON.parse(captured[0]), 'the default-sink line');
  });

  test('the logger refuses a class or outcome outside the declared enums', () => {
    const log = createLogger({ testRunId: 'run_spike_enum_guard', sink: { write() {} } });
    assert.throws(() => log('not.a.declared.class', LOG_OUTCOME.OK), { message: CODES.INTERNAL });
    assert.throws(() => log(LOG_CLASS.HEALTH, 'not_a_declared_outcome'), { message: CODES.INTERNAL });
  });
});

/**
 * A POST one byte short of its declared content-length, on its own connection:
 * the server has accepted the request and is waiting for the rest.
 */
function openStalledPost(spike, token, payload) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8');
  const req = httpRequest({
    host: '127.0.0.1',
    port: spike.port,
    path: '/mcp',
    method: 'POST',
    agent: new Agent({ keepAlive: false }),
    headers: { ...mcpHeaders(token), 'content-length': body.length },
  });

  const response = new Promise((resolve, reject) => {
    req.once('response', (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      const settle = () =>
        resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString('utf8') });
      res.once('end', settle);
      res.once('aborted', settle);
    });
    req.once('error', reject);
  });

  req.write(body.subarray(0, body.length - 1));
  return { response, complete: () => req.end(body.subarray(body.length - 1)) };
}

async function waitUntil(predicate, label) {
  for (let attempt = 0; attempt < 500; attempt += 1) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
  throw new Error(`condition never held: ${label}`);
}

// --------------------------------------------------------------------------
// 2. Leak canaries
// --------------------------------------------------------------------------

/**
 * Each probe plants one canary in one caller-controlled channel, states the
 * closed refusal (or success) it expects, and declares the one place - if any -
 * the canary is permitted to appear.
 *
 * `allow` values:
 *  - `undefined`: the canary may appear nowhere in the response at all.
 *  - `'dcr-client-name'`: the canary may appear only as the `client_name` of
 *    the registration success body, which RFC 7591 has the response echo.
 *  - `'rpc-id'`: the canary may appear only as the JSON-RPC `id`, which
 *    JSON-RPC 2.0 requires the response to carry back verbatim.
 *
 * Both exemptions are one named field, asserted present and then stripped -
 * never a blanket pass over a whole body or a whole probe.
 *
 * The two remaining permitted appearances - `state` in a redirect Location and
 * an HTML-escaped `client_name` on the consent page - each get their own test
 * below, because the property worth pinning there is more than mere absence.
 */
const PROBES = [
  // ---- OAuth query parameters -------------------------------------------
  {
    label: 'authorize: state',
    canary: CANARY.CHILD_NAME,
    setup: 'oauth',
    expect: { status: 200 },
    build: (env) => ({
      path: `/oauth/authorize?${authorizeQuery(env.spike, { state: CANARY.CHILD_NAME }, env.keys)}`,
    }),
  },
  {
    label: 'authorize: scope',
    canary: CANARY.MEDICAL,
    setup: 'oauth',
    expect: { status: 400, errorCode: CODES.SCOPE_INVALID },
    build: (env) => ({
      path: `/oauth/authorize?${authorizeQuery(env.spike, { scope: CANARY.MEDICAL }, env.keys)}`,
    }),
  },
  {
    label: 'authorize: resource',
    canary: CANARY.URL,
    setup: 'oauth',
    expect: { status: 400, errorCode: CODES.RESOURCE_MISMATCH },
    build: (env) => ({
      path: `/oauth/authorize?${authorizeQuery(env.spike, { resource: CANARY.URL }, env.keys)}`,
    }),
  },
  {
    label: 'authorize: redirect_uri',
    canary: CANARY.URL,
    setup: 'oauth',
    expect: { status: 400, errorCode: CODES.REDIRECT_MISMATCH },
    build: (env) => ({
      path: `/oauth/authorize?${authorizeQuery(env.spike, { redirect_uri: CANARY.URL }, env.keys)}`,
    }),
  },
  {
    label: 'authorize: client_id',
    canary: CANARY.FINANCIAL,
    setup: 'oauth',
    expect: { status: 400, errorCode: CODES.UNKNOWN_CLIENT },
    build: (env) => ({
      path: `/oauth/authorize?${authorizeQuery(env.spike, { client_id: CANARY.FINANCIAL }, env.keys)}`,
    }),
  },
  {
    label: 'authorize: response_type',
    canary: CANARY.MEDICAL,
    setup: 'oauth',
    expect: { status: 400, errorCode: CODES.UNSUPPORTED_RESPONSE_TYPE },
    build: (env) => ({
      path: `/oauth/authorize?${authorizeQuery(env.spike, { response_type: CANARY.MEDICAL }, env.keys)}`,
    }),
  },
  {
    label: 'authorize: code_challenge',
    canary: CANARY.MEDICAL,
    setup: 'oauth',
    expect: { status: 400, errorCode: CODES.SCHEMA_INVALID },
    build: (env) => ({
      path: `/oauth/authorize?${authorizeQuery(env.spike, { code_challenge: CANARY.MEDICAL }, env.keys)}`,
    }),
  },
  {
    // Longer than the 16-byte cap on this parameter, so it is refused on length
    // before the S256 check - which is itself the point: a caller-supplied value
    // that never reaches the branch it names must still not come back.
    label: 'authorize: code_challenge_method',
    canary: CANARY.BEARER,
    setup: 'oauth',
    expect: { status: 400, errorCode: CODES.SCHEMA_INVALID },
    build: (env) => ({
      path: `/oauth/authorize?${authorizeQuery(env.spike, { code_challenge_method: CANARY.BEARER }, env.keys)}`,
    }),
  },
  {
    label: 'authorize: an unknown query parameter',
    canary: CANARY.EMAIL,
    setup: 'oauth',
    expect: { status: 400, errorCode: CODES.SCHEMA_INVALID },
    build: (env) => ({
      path: `/oauth/authorize?${authorizeQuery(env.spike, { canary_probe: CANARY.EMAIL }, env.keys)}`,
    }),
  },
  {
    label: 'authorize: a repeated query parameter',
    canary: CANARY.PROVIDER_ERROR,
    setup: 'oauth',
    expect: { status: 400, errorCode: CODES.SCHEMA_INVALID },
    build: (env) => ({
      path: `/oauth/authorize?${authorizeQuery(env.spike, {}, env.keys)}&${query([
        ['state', CANARY.PROVIDER_ERROR],
        ['state', `${CANARY.PROVIDER_ERROR}-second`],
      ])}`,
    }),
  },

  // ---- OAuth form fields -------------------------------------------------
  {
    label: 'approve: approval ticket',
    canary: CANARY.OAUTH_CODE,
    setup: 'oauth',
    expect: { status: 400, errorCode: CODES.APPROVAL_INVALID },
    build: () => ({
      method: 'POST',
      path: '/oauth/approve',
      headers: FORM_HEADERS,
      body: form({ approval: CANARY.OAUTH_CODE }),
    }),
  },
  {
    label: 'token: grant_type',
    canary: CANARY.MEDICAL,
    setup: 'oauth',
    expect: { status: 400, errorCode: CODES.UNSUPPORTED_GRANT_TYPE },
    build: () => ({
      method: 'POST',
      path: '/oauth/token',
      headers: FORM_HEADERS,
      body: form({ grant_type: CANARY.MEDICAL }),
    }),
  },
  {
    label: 'token: authorization code',
    canary: CANARY.OAUTH_CODE,
    setup: 'oauth',
    expect: { status: 400, errorCode: CODES.INVALID_GRANT },
    build: () => ({
      method: 'POST',
      path: '/oauth/token',
      headers: FORM_HEADERS,
      body: form({
        grant_type: 'authorization_code',
        code: CANARY.OAUTH_CODE,
        redirect_uri: REDIRECT_URI,
        client_id: STATIC_CLIENT_ID,
        code_verifier: 'a'.repeat(43),
      }),
    }),
  },
  {
    // A well-formed verifier carrying a canary, against a real code, so the
    // value reaches the constant-time PKCE comparison itself.
    label: 'token: PKCE verifier',
    canary: CANARY.MEDICAL,
    setup: 'oauth',
    prepare: async (env) => ({ granted: await authorizeAndApprove(env.spike) }),
    expect: { status: 400, errorCode: CODES.PKCE_VERIFIER_MISMATCH },
    build: (env) => ({
      method: 'POST',
      path: '/oauth/token',
      headers: FORM_HEADERS,
      body: form({
        grant_type: 'authorization_code',
        code: env.granted.code,
        redirect_uri: REDIRECT_URI,
        client_id: STATIC_CLIENT_ID,
        code_verifier: `${CANARY.MEDICAL}.${'a'.repeat(43 - CANARY.MEDICAL.length - 1)}`,
        resource: env.spike.url,
      }),
    }),
  },
  {
    label: 'token: resource indicator on a real code',
    canary: CANARY.URL,
    setup: 'oauth',
    prepare: async (env) => ({ granted: await authorizeAndApprove(env.spike) }),
    expect: { status: 400, errorCode: CODES.RESOURCE_MISMATCH },
    build: (env) => ({
      method: 'POST',
      path: '/oauth/token',
      headers: FORM_HEADERS,
      body: form({
        grant_type: 'authorization_code',
        code: env.granted.code,
        redirect_uri: REDIRECT_URI,
        client_id: STATIC_CLIENT_ID,
        code_verifier: env.granted.verifier,
        resource: CANARY.URL,
      }),
    }),
  },
  {
    label: 'token: refresh_token',
    canary: CANARY.OAUTH_CODE,
    setup: 'oauth',
    expect: { status: 400, errorCode: CODES.INVALID_GRANT },
    build: () => ({
      method: 'POST',
      path: '/oauth/token',
      headers: FORM_HEADERS,
      body: form({
        grant_type: 'refresh_token',
        refresh_token: CANARY.OAUTH_CODE,
        client_id: STATIC_CLIENT_ID,
      }),
    }),
  },
  {
    label: 'token: an unknown form field',
    canary: CANARY.EMAIL,
    setup: 'oauth',
    expect: { status: 400, errorCode: CODES.SCHEMA_INVALID },
    build: () => ({
      method: 'POST',
      path: '/oauth/token',
      headers: FORM_HEADERS,
      body: form({
        grant_type: 'authorization_code',
        code: 'code_spike_never_issued',
        redirect_uri: REDIRECT_URI,
        client_id: STATIC_CLIENT_ID,
        code_verifier: 'a'.repeat(43),
        canary_field: CANARY.EMAIL,
      }),
    }),
  },
  {
    label: 'revoke: token and token_type_hint',
    canary: CANARY.BEARER,
    setup: 'oauth',
    expect: { status: 200 },
    build: () => ({
      method: 'POST',
      path: '/oauth/revoke',
      headers: FORM_HEADERS,
      body: form({ token: CANARY.BEARER, token_type_hint: CANARY.FINANCIAL }),
    }),
  },

  // ---- Headers -----------------------------------------------------------
  {
    label: 'headers: User-Agent and Referer',
    canary: CANARY.PROVIDER_ERROR,
    setup: 'oauth',
    expect: { status: 200 },
    build: () => ({
      path: '/healthz',
      headers: {
        'user-agent': CANARY.PROVIDER_ERROR,
        referer: `${CANARY.URL}?from=${encodeURIComponent(CANARY.EMAIL)}`,
      },
    }),
  },
  {
    label: 'headers: a repeated header',
    canary: CANARY.MEDICAL,
    setup: 'oauth',
    expect: { status: 200 },
    build: () => ({
      path: '/healthz',
      headers: { 'accept-language': [CANARY.MEDICAL, CANARY.FINANCIAL] },
    }),
  },
  {
    // A classic reflection channel: the advertised issuer must come from the
    // bound origin, never from whatever Host the caller claimed.
    label: 'headers: Host on a discovery document',
    canary: CANARY.CHILD_NAME,
    setup: 'oauth',
    expect: { status: 200 },
    build: () => ({
      path: '/.well-known/oauth-authorization-server',
      headers: { host: `${CANARY.CHILD_NAME}.example.invalid`, 'x-forwarded-host': CANARY.URL },
    }),
    extraCanaries: [CANARY.URL],
  },
  {
    label: 'headers: Cookie on the consent page',
    canary: CANARY.EMAIL,
    setup: 'oauth',
    expect: { status: 200 },
    build: (env) => ({
      path: `/oauth/authorize?${authorizeQuery(env.spike, {}, env.keys)}`,
      headers: { cookie: `spike_probe=${encodeURIComponent(CANARY.EMAIL)}` },
    }),
  },
  {
    label: 'headers: bearer token contents',
    canary: CANARY.BEARER,
    setup: 'mcp',
    expect: { status: 401, errorCode: CODES.UNAUTHORIZED },
    build: () => ({
      method: 'POST',
      path: '/mcp',
      headers: { accept: MCP_ACCEPT, ...JSON_HEADERS, authorization: `Bearer ${CANARY.BEARER}` },
      body: JSON.stringify(rpc('tools/list', {})),
    }),
  },
  {
    label: 'headers: a non-bearer authorization scheme',
    canary: CANARY.FINANCIAL,
    setup: 'mcp',
    expect: { status: 401, errorCode: CODES.UNAUTHORIZED },
    build: () => ({
      method: 'POST',
      path: '/mcp',
      headers: {
        accept: MCP_ACCEPT,
        ...JSON_HEADERS,
        authorization: `Basic ${Buffer.from(CANARY.FINANCIAL, 'utf8').toString('base64')}`,
      },
      body: JSON.stringify(rpc('tools/list', {})),
    }),
  },
  {
    label: 'headers: MCP-Protocol-Version',
    canary: CANARY.MEDICAL,
    setup: 'mcp',
    expect: { status: 400, errorCode: CODES.UNSUPPORTED_PROTOCOL_VERSION },
    build: (env) => ({
      method: 'POST',
      path: '/mcp',
      headers: mcpHeaders(env.accessToken, { 'mcp-protocol-version': CANARY.MEDICAL }),
      body: JSON.stringify(rpc('tools/list', {})),
    }),
  },
  {
    label: 'headers: a repeated MCP-Protocol-Version',
    canary: CANARY.MEDICAL,
    setup: 'mcp',
    expect: { status: 400, errorCode: CODES.UNSUPPORTED_PROTOCOL_VERSION },
    build: (env) => ({
      method: 'POST',
      path: '/mcp',
      headers: mcpHeaders(env.accessToken, {
        'mcp-protocol-version': [CANARY.MEDICAL, CANARY.FINANCIAL],
      }),
      body: JSON.stringify(rpc('tools/list', {})),
    }),
  },
  {
    label: 'headers: a canary in the Accept header',
    canary: CANARY.PROVIDER_ERROR,
    setup: 'mcp',
    expect: { status: 406, errorCode: CODES.NOT_ACCEPTABLE },
    build: (env) => ({
      method: 'POST',
      path: '/mcp',
      headers: mcpHeaders(env.accessToken, { accept: `application/${CANARY.PROVIDER_ERROR}` }),
      body: JSON.stringify(rpc('tools/list', {})),
    }),
  },
  {
    label: 'headers: a canary in the Content-Type',
    canary: CANARY.PROVIDER_ERROR,
    setup: 'mcp',
    expect: { status: 415, errorCode: CODES.UNSUPPORTED_MEDIA_TYPE },
    build: (env) => ({
      method: 'POST',
      path: '/mcp',
      headers: mcpHeaders(env.accessToken, { 'content-type': `text/${CANARY.PROVIDER_ERROR}` }),
      body: JSON.stringify(rpc('tools/list', {})),
    }),
  },
  {
    // A charset parameter is discarded by the media-type check, so the request
    // succeeds - the canary rides all the way through a 200.
    label: 'headers: a canary in a Content-Type parameter',
    canary: CANARY.CHILD_NAME,
    setup: 'mcp',
    expect: { status: 200 },
    build: (env) => ({
      method: 'POST',
      path: '/mcp',
      headers: mcpHeaders(env.accessToken, {
        'content-type': `application/json; charset=utf-8; spike-probe=${CANARY.CHILD_NAME}`,
      }),
      body: JSON.stringify(rpc('tools/list', {})),
    }),
  },
  {
    label: 'headers: an arbitrary custom header on a successful call',
    canary: CANARY.FINANCIAL,
    setup: 'mcp',
    expect: { status: 200 },
    build: (env) => ({
      method: 'POST',
      path: '/mcp',
      headers: mcpHeaders(env.accessToken, {
        'x-spike-canary': CANARY.FINANCIAL,
        'x-forwarded-for': CANARY.URL,
      }),
      body: JSON.stringify(rpc('tools/list', {})),
    }),
    extraCanaries: [CANARY.URL],
  },

  // ---- Dynamic client registration --------------------------------------
  {
    // RFC 7591 has the registration response echo the registered metadata, so
    // this one occurrence is expected; every other surface must stay clean.
    label: 'register: client_name (echoed by RFC 7591)',
    canary: CANARY.CHILD_NAME,
    setup: 'dcr',
    expect: { status: 201, allow: 'dcr-client-name' },
    build: () => ({
      method: 'POST',
      path: '/oauth/register',
      headers: JSON_HEADERS,
      body: JSON.stringify({ redirect_uris: [REDIRECT_URI], client_name: CANARY.CHILD_NAME }),
    }),
  },
  {
    label: 'register: redirect_uris',
    canary: CANARY.URL,
    setup: 'dcr',
    expect: { status: 400, errorCode: CODES.REDIRECT_MISMATCH },
    build: () => ({
      method: 'POST',
      path: '/oauth/register',
      headers: JSON_HEADERS,
      body: JSON.stringify({ redirect_uris: [CANARY.URL], client_name: CANARY.CHILD_NAME }),
    }),
    extraCanaries: [CANARY.CHILD_NAME],
  },
  {
    label: 'register: scope',
    canary: CANARY.MEDICAL,
    setup: 'dcr',
    expect: { status: 201 },
    build: () => ({
      method: 'POST',
      path: '/oauth/register',
      headers: JSON_HEADERS,
      body: JSON.stringify({ redirect_uris: [REDIRECT_URI], scope: CANARY.MEDICAL }),
    }),
  },
  {
    label: 'register: an unknown body field',
    canary: CANARY.EMAIL,
    setup: 'dcr',
    expect: { status: 400, errorCode: CODES.SCHEMA_INVALID },
    build: () => ({
      method: 'POST',
      path: '/oauth/register',
      headers: JSON_HEADERS,
      body: JSON.stringify({ redirect_uris: [REDIRECT_URI], canary_field: CANARY.EMAIL }),
    }),
  },
  {
    label: 'register: an over-long client_name',
    canary: CANARY.PROVIDER_ERROR,
    setup: 'dcr',
    expect: { status: 400, errorCode: CODES.SCHEMA_INVALID },
    build: () => ({
      method: 'POST',
      path: '/oauth/register',
      headers: JSON_HEADERS,
      body: JSON.stringify({
        redirect_uris: [REDIRECT_URI],
        client_name: `${CANARY.PROVIDER_ERROR}${'x'.repeat(MAX_CLIENT_NAME_LENGTH)}`,
      }),
    }),
  },
  {
    label: 'register: malformed JSON',
    canary: CANARY.FINANCIAL,
    setup: 'dcr',
    expect: { status: 400, errorCode: CODES.SCHEMA_INVALID },
    build: () => ({
      method: 'POST',
      path: '/oauth/register',
      headers: JSON_HEADERS,
      body: `{"redirect_uris":["${REDIRECT_URI}"],"client_name":"${CANARY.FINANCIAL}"`,
    }),
  },
  {
    label: 'register: a client_name while registration is disabled',
    canary: CANARY.CHILD_NAME,
    setup: 'oauth',
    expect: { status: 404, errorCode: CODES.DCR_DISABLED },
    build: () => ({
      method: 'POST',
      path: '/oauth/register',
      headers: JSON_HEADERS,
      body: JSON.stringify({ redirect_uris: [REDIRECT_URI], client_name: CANARY.CHILD_NAME }),
    }),
  },

  // ---- MCP tool arguments and the JSON-RPC envelope ----------------------
  {
    label: 'mcp: an unknown tool name',
    canary: CANARY.MEDICAL,
    setup: 'mcp',
    expect: { status: 200, toolErrorCode: CODES.UNKNOWN_TOOL },
    build: (env) => mcpBody(env, toolCall(`tool_${CANARY.MEDICAL}`, { schemaVersion: 1 })),
  },
  {
    label: 'mcp: finish runId',
    canary: CANARY.MEDICAL,
    setup: 'mcp',
    expect: { status: 200, toolErrorCode: CODES.RUN_UNKNOWN },
    build: (env) => mcpBody(env, toolCall(TOOL_FINISH, finishInput(`run_${CANARY.MEDICAL}`, 'req_spike_probe'))),
  },
  {
    // The one canary that reaches a *successful* tool call: `clientRequestId`
    // is the idempotency key, and the receipt must not carry it back.
    label: 'mcp: clientRequestId on a successful finish',
    canary: CANARY.CHILD_NAME,
    setup: 'mcp',
    prepare: async (env) => ({ runId: await mintRun(env.spike, env.accessToken) }),
    expect: { status: 200, toolSuccess: true },
    build: (env) => mcpBody(env, toolCall(TOOL_FINISH, finishInput(env.runId, CANARY.CHILD_NAME))),
  },
  {
    label: 'mcp: finish result',
    canary: CANARY.FINANCIAL,
    setup: 'mcp',
    prepare: async (env) => ({ runId: await mintRun(env.spike, env.accessToken) }),
    expect: { status: 200, toolErrorCode: CODES.SCHEMA_INVALID },
    build: (env) =>
      mcpBody(env, toolCall(TOOL_FINISH, finishInput(env.runId, 'req_spike_probe', { result: CANARY.FINANCIAL }))),
  },
  {
    label: 'mcp: finish source row',
    canary: CANARY.EMAIL,
    setup: 'mcp',
    prepare: async (env) => ({ runId: await mintRun(env.spike, env.accessToken) }),
    expect: { status: 200, toolErrorCode: CODES.SCHEMA_INVALID },
    build: (env) =>
      mcpBody(
        env,
        toolCall(
          TOOL_FINISH,
          finishInput(env.runId, 'req_spike_probe', {
            sources: [{ source: CANARY.EMAIL, outcome: CANARY.PROVIDER_ERROR, recordsReported: 1 }],
          }),
        ),
      ),
    extraCanaries: [CANARY.PROVIDER_ERROR],
  },
  {
    label: 'mcp: an unknown tool argument',
    canary: CANARY.MEDICAL,
    setup: 'mcp',
    expect: { status: 200, toolErrorCode: CODES.SCHEMA_INVALID },
    build: (env) =>
      mcpBody(env, toolCall(TOOL_IDENTITY, { schemaVersion: 1, canaryProbe: CANARY.MEDICAL })),
  },
  {
    label: 'mcp: an unimplemented JSON-RPC method',
    canary: CANARY.MEDICAL,
    setup: 'mcp',
    expect: { status: 200, rpcErrorMessage: CODES.UNKNOWN_METHOD },
    build: (env) => mcpBody(env, rpc(`prompts/${CANARY.MEDICAL}`, {})),
  },
  {
    // The SDK answers a malformed envelope with its own static parse error.
    // The assertion is on the canary's absence, never on that string, so a
    // future SDK that starts echoing input here still fails this test.
    label: 'mcp: a malformed JSON-RPC envelope',
    canary: CANARY.PROVIDER_ERROR,
    setup: 'mcp',
    // The numeric code is pinned so the row cannot quietly start probing a
    // different layer if a future SDK moves the rejection. The message text is
    // still never asserted - only the canary's absence from it.
    expect: { status: 400, rpcErrorCode: ErrorCode.ParseError },
    build: (env) => mcpBody(env, { jsonrpc: CANARY.PROVIDER_ERROR, id: 1, method: 'tools/list' }),
  },
  {
    label: 'mcp: non-object tool arguments',
    canary: CANARY.MEDICAL,
    setup: 'mcp',
    expect: { status: 200, rpcErrorCode: ErrorCode.InternalError },
    build: (env) => mcpBody(env, toolCall(TOOL_IDENTITY, CANARY.MEDICAL)),
  },
  {
    // JSON-RPC 2.0 requires the response to carry the request id back verbatim.
    // Bounded, protocol-mandated, and confined to that one field.
    label: 'mcp: the JSON-RPC id (echoed by JSON-RPC 2.0)',
    canary: CANARY.MEDICAL,
    setup: 'mcp',
    expect: { status: 200, allow: 'rpc-id', rpcErrorMessage: CODES.UNKNOWN_METHOD },
    build: (env) => mcpBody(env, rpc('prompts/list', {}, { id: `id_${CANARY.MEDICAL}` })),
  },
  {
    label: 'mcp: initialize protocolVersion and clientInfo',
    canary: CANARY.CHILD_NAME,
    setup: 'mcp',
    expect: { status: 200 },
    build: (env) =>
      mcpBody(
        env,
        initializeMessage({
          protocolVersion: `version-${CANARY.CHILD_NAME}`,
          clientInfo: { name: `client-${CANARY.EMAIL}`, version: `v-${CANARY.FINANCIAL}` },
        }),
      ),
    extraCanaries: [CANARY.EMAIL, CANARY.FINANCIAL],
  },
  {
    label: 'mcp: a _meta progress token',
    canary: CANARY.MEDICAL,
    setup: 'mcp',
    expect: { status: 200 },
    build: (env) => mcpBody(env, rpc('tools/list', { _meta: { progressToken: `pt_${CANARY.MEDICAL}` } })),
  },
  {
    label: 'mcp: a JSON-RPC batch',
    canary: CANARY.FINANCIAL,
    setup: 'mcp',
    expect: { status: 200 },
    build: (env) => mcpBody(env, [rpc(`resources/${CANARY.FINANCIAL}`, {})]),
  },
  {
    label: 'mcp: malformed JSON',
    canary: CANARY.EMAIL,
    setup: 'mcp',
    expect: { status: 400, errorCode: CODES.SCHEMA_INVALID },
    build: (env) => ({
      method: 'POST',
      path: '/mcp',
      headers: mcpHeaders(env.accessToken),
      body: `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"${CANARY.EMAIL}"`,
    }),
  },
  {
    label: 'mcp: an oversized body',
    canary: CANARY.MEDICAL,
    setup: 'mcp',
    expect: { status: 413, errorCode: CODES.TOO_LARGE },
    build: (env) => ({
      method: 'POST',
      path: '/mcp',
      headers: mcpHeaders(env.accessToken),
      body: `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"${CANARY.MEDICAL}","pad":"${'a'.repeat(MCP_BODY_LIMIT_BYTES)}"}}`,
    }),
  },
  {
    label: 'mcp: a canary on a method the server does not route',
    canary: CANARY.OAUTH_CODE,
    setup: 'mcp',
    expect: { status: 405, errorCode: CODES.METHOD_NOT_ALLOWED },
    build: (env) => ({
      method: 'DELETE',
      path: `/mcp?probe=${encodeURIComponent(CANARY.OAUTH_CODE)}`,
      headers: mcpHeaders(env.accessToken),
    }),
  },
  {
    label: 'routing: a canary in an unknown path',
    canary: CANARY.CHILD_NAME,
    setup: 'oauth',
    expect: { status: 404, errorCode: CODES.NOT_FOUND },
    build: () => ({ path: `/${encodeURIComponent(CANARY.CHILD_NAME)}?q=${encodeURIComponent(CANARY.EMAIL)}` }),
    extraCanaries: [CANARY.EMAIL],
  },
];

function mcpBody(env, message) {
  return {
    method: 'POST',
    path: '/mcp',
    headers: mcpHeaders(env.accessToken),
    body: JSON.stringify(message),
  };
}

async function setupFor(kind) {
  if (kind === 'dcr') return { spike: await startSpike({ dcr: true }) };
  if (kind === 'mcp') {
    const spike = await startSpike();
    return { spike, ...grant(spike) };
  }
  const spike = await startSpike();
  return { spike, keys: pkce() };
}

describe('2. leak canaries', () => {
  for (const probe of PROBES) {
    test(`${probe.label} reaches no diagnostic surface`, async () => {
      const env = await setupFor(probe.setup);
      Object.assign(env, probe.prepare ? await probe.prepare(env) : {});
      const spec = probe.build(env);
      const canaries = [probe.canary, ...(probe.extraCanaries ?? [])];

      // The canary must actually be on the wire: a probe that never sent its
      // marker would pass every assertion below while proving nothing.
      const sent = sentText(spec);
      for (const canary of canaries) {
        assert.ok(
          canaryForms(canary).some((form) => sent.includes(form)),
          `${probe.label} never transmitted the canary ${canary}`,
        );
      }

      const before = env.spike.lines.length;
      const response = await rawRequest(env.spike, spec);

      // The request reached the intended code path, not some generic refusal.
      assert.equal(response.status, probe.expect.status, `${probe.label}: unexpected status`);
      if (probe.expect.errorCode !== undefined) {
        const body = JSON.parse(response.body);
        assert.deepEqual(Object.keys(body), ['error'], `${probe.label}: an error body is one closed code`);
        assert.equal(body.error, probe.expect.errorCode, `${probe.label}: unexpected code`);
        assert.ok(ALL_CODES.has(body.error), `${probe.label}: ${body.error} is not a closed code`);
      }
      if (probe.expect.toolErrorCode !== undefined) {
        const result = singleRpcMessage(response).result;
        assert.equal(result.isError, true, `${probe.label}: expected a tool error`);
        assert.equal(result.content[0].text, probe.expect.toolErrorCode);
      }
      if (probe.expect.toolSuccess) {
        const result = singleRpcMessage(response).result;
        assert.notEqual(result.isError, true, `${probe.label}: expected a successful tool call`);
      }
      if (probe.expect.rpcErrorMessage !== undefined) {
        assert.equal(singleRpcMessage(response).error.message, probe.expect.rpcErrorMessage);
      }
      if (probe.expect.rpcErrorCode !== undefined) {
        assert.equal(
          singleRpcMessage(response).error.code,
          probe.expect.rpcErrorCode,
          `${probe.label}: the rejection moved to a different JSON-RPC layer`,
        );
      }

      // Exactly one line, and it is content blind.
      const written = env.spike.lines.slice(before);
      assert.equal(written.length, 1, `${probe.label}: wrote ${written.length} log lines`);
      assertContentBlindLine(JSON.parse(written[0]), `${probe.label}: the log line`);

      // The canary reaches no log line, ever - not even one written earlier by
      // this probe's own setup.
      assertNoCanaryInLog(env.spike, probe.label);

      // ...and no response surface, except the one field the probe declares.
      // A permitted echo is asserted positively and then removed, so the
      // exemption covers exactly that field - not the whole body, and not the
      // probe's other canaries - and so a future change that silently stopped
      // echoing would fail here rather than pass unnoticed.
      let body = response.body;
      if (probe.expect.allow === 'rpc-id') {
        const message = singleRpcMessage(response);
        assert.equal(message.id, `id_${probe.canary}`, `${probe.label}: the id must come back verbatim`);
        delete message.id;
        body = JSON.stringify(message);
      }
      if (probe.expect.allow === 'dcr-client-name') {
        const registered = JSON.parse(response.body);
        assert.equal(
          registered.client_name,
          probe.canary,
          `${probe.label}: RFC 7591 has the registration response echo the registered name`,
        );
        delete registered.client_name;
        body = JSON.stringify(registered);
      }
      for (const canary of canaries) {
        assertAbsent(response.rawHeaders, canary, `${probe.label}: a response header`);
        assertAbsent(body, canary, `${probe.label}: the response body`);
      }
    });
  }

  test('a canary in `state` comes back only in the Location of the proven redirect', async () => {
    const spike = await startSpike();
    const granted = await authorizeAndApprove(spike, { state: `state_${CANARY.CHILD_NAME}` });

    // RFC 6749 requires `state` to come back to the client. It is reflected
    // exactly once, into the Location header of the 302, and only after the
    // redirect target has been matched against the registered URI - so it can
    // never be bounced to an attacker-chosen host.
    assert.equal(granted.location.origin + granted.location.pathname, REDIRECT_URI);
    assert.equal(granted.location.searchParams.get('state'), `state_${CANARY.CHILD_NAME}`);

    assertAbsent(granted.approved.body, CANARY.CHILD_NAME, 'the redirect body');
    assertAbsent(granted.page.body, CANARY.CHILD_NAME, 'the consent page');
    assertNoCanaryInLog(spike, 'state reflection');
  });

  test('a canary in `client_name` reaches the consent page only HTML-escaped', async () => {
    const spike = await startSpike({ dcr: true });
    // The canary is wrapped in markup, so a raw occurrence would be a live
    // injection and an escaped one is inert. Both are checked.
    const injected = `<script>alert("${CANARY.CHILD_NAME}")</script>`;

    const registered = await rawRequest(spike, {
      method: 'POST',
      path: '/oauth/register',
      headers: JSON_HEADERS,
      body: JSON.stringify({ redirect_uris: [REDIRECT_URI], client_name: injected }),
    });
    assert.equal(registered.status, 201);
    const clientId = JSON.parse(registered.body).client_id;

    const keys = pkce();
    const page = await rawRequest(spike, {
      path: `/oauth/authorize?${authorizeQuery(spike, { client_id: clientId }, keys)}`,
    });
    assert.equal(page.status, 200);

    // The escaping is the security property, so it gets its own assertion.
    assert.ok(page.body.includes(escapeHtml(injected)), 'the consent page must render the escaped name');
    assert.ok(!page.body.includes('<script>'), 'the consent page must not render raw markup');
    assert.ok(!page.body.includes(injected), 'the consent page must not render the raw client name');
    assert.ok(!page.body.includes('alert("'), 'no unescaped quote may survive into the page');

    // Escaped on the page is the one permitted appearance: nowhere else.
    assertAbsent(page.rawHeaders, CANARY.CHILD_NAME, 'the consent-page headers');
    assertNoCanaryInLog(spike, 'client_name escaping');
  });

  test('every canary category is exercised by at least one probe', () => {
    const used = new Set(PROBES.flatMap((probe) => [probe.canary, ...(probe.extraCanaries ?? [])]));
    for (const canary of ALL_CANARIES) {
      assert.ok(used.has(canary), `no probe plants ${canary}`);
    }
  });
});

// --------------------------------------------------------------------------
// 3. Credential material never reaches a log line
// --------------------------------------------------------------------------

describe('3. no credential material reaches a log line', () => {
  test('no token, code, verifier, ticket or secret appears in any log line', async () => {
    // A known signing key, so the one long-lived secret in the process can be
    // searched for too; production would generate it per process.
    const signingKey = randomBytes(32);
    const spike = await startSpike({ dcr: true, signingKey });
    const granted = await grantTokens(spike);

    const refreshed = await rawRequest(spike, {
      method: 'POST',
      path: '/oauth/token',
      headers: FORM_HEADERS,
      body: form({
        grant_type: 'refresh_token',
        refresh_token: granted.tokens.refresh_token,
        client_id: STATIC_CLIENT_ID,
      }),
    });
    assert.equal(refreshed.status, 200);
    const rotated = JSON.parse(refreshed.body);

    // Use the tokens on /mcp so they pass through the bearer check too.
    const used = await rawRequest(spike, {
      method: 'POST',
      path: '/mcp',
      headers: mcpHeaders(rotated.access_token),
      body: JSON.stringify(rpc('tools/list', {})),
    });
    assert.equal(used.status, 200);

    await rawRequest(spike, {
      method: 'POST',
      path: '/oauth/revoke',
      headers: FORM_HEADERS,
      body: form({ token: rotated.refresh_token }),
    });

    const secrets = [
      ['approval ticket', granted.ticket],
      ['authorization code', granted.code],
      ['PKCE verifier', granted.verifier],
      ['PKCE challenge', granted.challenge],
      ['first access token', granted.tokens.access_token],
      ['first refresh token', granted.tokens.refresh_token],
      ['rotated access token', rotated.access_token],
      ['rotated refresh token', rotated.refresh_token],
    ];

    const text = logText(spike);
    for (const [label, secret] of secrets) {
      assert.ok(typeof secret === 'string' && secret.length >= 16, `${label} is not a real secret`);
      // Plaintext, every transport encoding, and the two hashed forms the
      // store itself keeps - a digest in a log line is still a correlatable
      // handle on a credential.
      assertAbsent(text, secret, `a log line: ${label}`);
      assertAbsent(text, sha256Base64Url(secret), `a log line: ${label} (sha256)`);
      assertAbsent(text, createHash('sha256').update(secret, 'utf8').digest('hex'), `a log line: ${label} (hex sha256)`);
      // A prefix long enough to be a usable correlation handle, in case some
      // future truncation writes only the head of a token.
      assertAbsent(text, secret.slice(0, 12), `a log line: ${label} (prefix)`);
      // The signed access tokens split on `.`; the payload half alone would
      // disclose every claim.
      for (const part of secret.split('.')) {
        if (part.length >= 16) assertAbsent(text, part, `a log line: ${label} (segment)`);
      }
    }

    // The signing key never appears in any encoding either, nor does an HMAC
    // taken under it - a valid signature in a log line is credential material.
    for (const encoding of ['hex', 'base64', 'base64url']) {
      assertAbsent(text, signingKey.toString(encoding), `a log line: the signing key (${encoding})`);
    }
    assertAbsent(text, hmacBase64Url(signingKey, granted.code), 'a log line: an HMAC under the signing key');

    for (const entry of parsedLines(spike)) assertContentBlindLine(entry, 'a flow line');
  });

  test('the spike issues no client secret at all, in any body or log line', async () => {
    const spike = await startSpike({ dcr: true });
    const registered = await rawRequest(spike, {
      method: 'POST',
      path: '/oauth/register',
      headers: JSON_HEADERS,
      body: JSON.stringify({ redirect_uris: [REDIRECT_URI], client_name: 'Spike Probe Client' }),
    });
    assert.equal(registered.status, 201);

    const body = JSON.parse(registered.body);
    assert.equal(body.client_secret, undefined, 'a public client is issued no secret');
    assert.equal(body.client_secret_expires_at, undefined);
    assert.equal(body.token_endpoint_auth_method, 'none');

    const discovery = await rawRequest(spike, { path: '/.well-known/oauth-authorization-server' });
    assert.deepEqual(JSON.parse(discovery.body).token_endpoint_auth_methods_supported, ['none']);
    assert.ok(!logText(spike).includes('client_secret'), 'no log line names a client secret');
  });
});

// --------------------------------------------------------------------------
// 4. Source scan
// --------------------------------------------------------------------------

const SRC_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'src');

function sourceFiles() {
  // Recursive: the property these scans assert is over `src/**`, not over its
  // top level. `src/` happens to be flat today, so a non-recursive walk would
  // pass while silently ceasing to cover the first module anyone nests.
  // `name` is the path relative to `src/`, so a nested file cannot borrow a
  // top-level file's allow-list entry.
  const files = readdirSync(SRC_DIR, { recursive: true })
    .filter((name) => name.endsWith('.mjs'))
    .map((name) => ({ name, path: join(SRC_DIR, name), text: readFileSync(join(SRC_DIR, name), 'utf8') }));
  assert.ok(files.length >= 20, `expected the whole spike source tree, found ${files.length} files`);
  return files;
}

/**
 * The Dayfold-shaped names the startup guard refuses. Held here as a literal so
 * the scan is independent of the guard's internals, then cross-checked against
 * the guard so the two can never drift apart.
 */
const DAYFOLD_ENV_NAMES = Object.freeze([
  'DATABASE_URL',
  'DAYFOLD_API',
  'FAMILY_ID',
  'HOUSEHOLD_SECRET',
  'AUTH_SECRET',
  'AUTH_GOOGLE_CLIENT_ID',
  // Prefix members that exist in no list anywhere: the guard must refuse a
  // name because of its shape, not because someone remembered to enumerate it.
  'DAYFOLD_SESSION_SECRET',
  'AUTH_TRUST_HOST',
]);

/** The two prefixes the guard refuses wholesale. */
const DAYFOLD_ENV_PREFIXES = Object.freeze(['AUTH_', 'DAYFOLD_']);

/** The four knobs `main.mjs` is allowed to read. */
const SPIKE_ENV_NAMES = Object.freeze([
  'SPIKE_PORT',
  'SPIKE_RESOURCE_ORIGIN',
  'SPIKE_REDIRECT_URI',
  'SPIKE_DCR',
]);

describe('4. src/ writes only through the logging front door and reads no Dayfold variable', () => {
  test('no file calls console.*, and only the logging module writes to a std stream', () => {
    const consoleCall = /\bconsole\s*\.\s*(log|warn|error|info|debug|trace|dir|table)\s*\(/;
    const streamWrite = /\bprocess\s*\.\s*(stdout|stderr)\s*\.\s*write\s*\(/;

    for (const file of sourceFiles()) {
      assert.ok(!consoleCall.test(file.text), `src/${file.name} calls console.*`);
      // `log.mjs` is the single logging front door and the only module allowed
      // to reach a standard stream at all.
      if (file.name === 'log.mjs') continue;
      assert.ok(!streamWrite.test(file.text), `src/${file.name} writes to a standard stream directly`);
    }
  });

  test('no file outside the startup guard mentions a Dayfold environment name', () => {
    // `guard.mjs` is the sole allow-listed reader: it exists precisely to
    // inspect these names and refuse to start, so a scan that forbade every
    // occurrence would fail on the control that enforces the constraint.
    for (const name of DAYFOLD_ENV_NAMES) {
      assert.ok(isForbiddenEnvName(name), `the guard no longer refuses ${name}; this scan is stale`);
    }

    for (const file of sourceFiles()) {
      if (file.name === 'guard.mjs') continue;
      for (const name of DAYFOLD_ENV_NAMES) {
        assert.ok(!file.text.includes(name), `src/${file.name} mentions the Dayfold variable ${name}`);
      }
      // The prefix rules, not just the sample names above. Scoped to string
      // literals, because an env variable can only be read by name: the spike's
      // own `AUTH_CODE_TTL_MS` constant is an identifier, not a lookup key.
      for (const prefix of DAYFOLD_ENV_PREFIXES) {
        assert.ok(
          !new RegExp(`['"\`]${prefix}[A-Z0-9_]*['"\`]`).test(file.text),
          `src/${file.name} names a ${prefix}* variable`,
        );
        assert.ok(
          !new RegExp(`\\bprocess\\s*\\.\\s*env\\s*\\.\\s*${prefix}`).test(file.text),
          `src/${file.name} reads a ${prefix}* variable`,
        );
      }
    }
  });

  test('the startup guard refuses a whole Dayfold prefix, not an enumerated list', async () => {
    // A prefix hole here would undercut global constraint 2 rather than merely
    // a test: the guard is the structural proof that the spike cannot borrow a
    // real credential, so a `DAYFOLD_*` name nobody thought to enumerate must
    // still stop the server from starting.
    for (const prefix of DAYFOLD_ENV_PREFIXES) {
      const invented = `${prefix}NAME_THAT_IS_IN_NO_LIST`;
      assert.ok(isForbiddenEnvName(invented), `the guard admits ${invented}`);
    }
    // ...while everything the spike legitimately reads still starts.
    for (const allowed of [...SPIKE_ENV_NAMES, 'PATH', 'HOME', 'NODE_ENV']) {
      assert.ok(!isForbiddenEnvName(allowed), `the guard refuses ${allowed}`);
    }

    // The refusal is real, not just a predicate: a novel prefix member stops
    // the factory, with one closed code and no hint which variable was seen.
    await assert.rejects(
      () =>
        createSpikeServer({
          port: 0,
          env: { PATH: '/usr/bin', DAYFOLD_SESSION_SECRET: 'synthetic-value' },
          redirectUri: REDIRECT_URI,
          sink: { write() {} },
        }),
      (error) => {
        assert.equal(error.code, CODES.ENV_CONTAMINATED);
        assert.equal(error.message, CODES.ENV_CONTAMINATED, 'the guard leaks no variable name');
        return true;
      },
    );
  });

  test('process.env is read in exactly two places, and only for SPIKE_* knobs', () => {
    const readers = sourceFiles()
      .filter((file) => /\bprocess\s*\.\s*env\b/.test(file.text))
      .map((file) => file.name)
      .sort();
    // `main.mjs` turns the four SPIKE_* knobs into configuration; `server.mjs`
    // hands the ambient environment to the startup guard when a caller supplies
    // none. Nothing else may look at it.
    assert.deepEqual(readers, ['main.mjs', 'server.mjs']);

    const main = sourceFiles().find((file) => file.name === 'main.mjs');
    const shouted = [...main.text.matchAll(/'([A-Z][A-Z0-9_]{2,})'/g)].map((match) => match[1]);
    assert.ok(shouted.length > 0, 'main.mjs must name the knobs it reads');
    for (const name of shouted) {
      assert.ok(SPIKE_ENV_NAMES.includes(name), `main.mjs reads ${name}, which is not a SPIKE_* knob`);
      assert.ok(!isForbiddenEnvName(name), `${name} is a Dayfold-shaped name`);
    }
  });
});

// --------------------------------------------------------------------------
// 5. Import isolation
// --------------------------------------------------------------------------

const SPECIFIER = /(?:\bfrom\s*|\bimport\s*\(\s*|\brequire\s*\(\s*)['"]([^'"]+)['"]/g;
const PINNED_DEPENDENCY = '@modelcontextprotocol/sdk';

describe('5. src/ imports nothing outside itself but node: and the one pinned dependency', () => {
  test('every module specifier is node:, the pinned SDK, or a sibling inside src/', () => {
    const seen = [];
    for (const file of sourceFiles()) {
      // A leaf module (`codes.mjs`, `constants.mjs`) legitimately imports
      // nothing, so this counts specifiers across the tree rather than per file.
      const specifiers = [...file.text.matchAll(SPECIFIER)].map((match) => match[1]);
      seen.push(...specifiers);

      for (const specifier of specifiers) {
        if (specifier.startsWith('node:')) continue;
        if (specifier === PINNED_DEPENDENCY || specifier.startsWith(`${PINNED_DEPENDENCY}/`)) continue;
        assert.ok(specifier.startsWith('./'), `src/${file.name} imports the bare specifier ${specifier}`);

        const resolved = resolve(dirname(file.path), specifier);
        const inside = relative(SRC_DIR, resolved);
        assert.ok(
          inside.length > 0 && !inside.startsWith('..'),
          `src/${file.name} imports ${specifier}, which escapes src/`,
        );
      }
    }

    // The scan is only meaningful if it actually saw the tree's imports.
    assert.ok(seen.length > 20, `expected the source tree's imports, saw ${seen.length}`);
    assert.ok(
      seen.some((specifier) => specifier.startsWith(PINNED_DEPENDENCY)),
      'the MCP surface must import the pinned SDK',
    );
    assert.ok(seen.some((specifier) => specifier.startsWith('node:')), 'node: builtins must be used by name');
  });

  test('no file uses require(), or names a path outside the spike', () => {
    for (const file of sourceFiles()) {
      assert.ok(!/\brequire\s*\(/.test(file.text), `src/${file.name} uses require()`);
      for (const forbidden of ['apps/', 'packages/', 'specs/domain-model', 'node_modules']) {
        assert.ok(!file.text.includes(forbidden), `src/${file.name} names ${forbidden}`);
      }
    }
  });

  test('the package declares exactly one pinned runtime dependency and no dev dependency', () => {
    const manifest = JSON.parse(
      readFileSync(join(SRC_DIR, '..', 'package.json'), 'utf8'),
    );
    assert.deepEqual(manifest.dependencies, { [PINNED_DEPENDENCY]: '1.30.0' });
    assert.equal(manifest.devDependencies, undefined);
    assert.equal(manifest.type, 'module');
    assert.equal(manifest.private, true);
  });
});
