# Claude MCP compatibility spike — v0.1 (throwaway, synthetic)

A disposable local server built for one purpose: to give the operator something
safe to point a real Claude connector client at, so that **the operator** can
record how that client actually behaves. It is a synthetic OAuth 2.1
authorization server plus a stateless Streamable HTTP MCP surface with exactly
two tools.

**Nothing in this directory proves anything about Claude.** The spike has never
been deployed, connected, or exercised by any provider client. It answers
inbound requests; it makes none. Provider behavior is recorded only by the
operator running `RUNBOOK.md`, and it lands only in
`research/2026-08-20-smart-briefings-v0.1-compatibility-spike.md` — which is
currently an empty template.

Work Package 0 of
`docs/superpowers/plans/2026-08-20-smart-briefings-v0.1-claude-bridge.md` §4.
Gate context: `specs/smart-briefings-v0.1/CLAUDE-HANDOFF.md`.

## What it is

- A single Node process, `node:http` only, all state in memory, nothing on disk.
- An OAuth surface shaped like RFC 9728 + RFC 8414 discovery, Authorization
  Code + S256 PKCE, exact-match redirect, rotating refresh, revocation, and an
  optional RFC 7591 dynamic-registration endpoint that is **off by default**.
- A `POST /mcp` Streamable HTTP endpoint in stateless mode behind a bearer
  check, exposing `dayfold_spike_identity` and `dayfold_spike_finish`.
- Content-blind diagnostics: one JSON line per request carrying exactly
  `ts`, `testRunId`, `class`, `outcome` — closed enums, no bodies, no headers,
  no tokens, no URLs, no tool input.
- 149 tests, `node --test`, zero test dependencies.

## What it is not

- **Not production, and not a prototype of production.** WP4 builds the real
  bridge; this shares no code, no credential, and no database with it.
- **Not connected to Dayfold.** It imports nothing from `apps/`, `packages/`,
  or `specs/domain-model/`, and it refuses to start if the environment carries
  any Dayfold variable (see *Startup guard*).
- **Not a Gmail client.** It never contacts Google, Anthropic, or any other
  host. It has no outbound network call of any kind.
- **Not a security-hardened public service.** Approval-ticket and run state
  grow unbounded in memory, and there is no rate limit beyond a per-credential
  in-flight cap. That is correct for a throwaway bound to `127.0.0.1` and it is
  a real consideration the moment the operator fronts it with a tunnel — see
  `RUNBOOK.md` § "Expected behaviors that can look like defects".
- **Not evidence.** No file here may be cited as proof of Claude behavior, of a
  Gmail read, or of any gate being passed.

## Requirements

- Node >= 20 (verified on v24.13.0).
- Exactly one dependency: `@modelcontextprotocol/sdk`, pinned to `1.30.0`.

## Install, test, run

From `spikes/claude-mcp-v0.1/`:

```sh
npm install     # installs the single pinned dependency
npm test        # node --test  -> 149 pass / 0 fail
npm start       # node src/main.mjs -> listens on http://127.0.0.1:8787
```

After npm's own two banner lines, `npm start` writes one log line and then
stays quiet until it is called:

```text
> @dayfold/spike-claude-mcp-v0.1@0.0.0 start
> node src/main.mjs

{"ts":"...","testRunId":"I51x9yJrzAE","class":"server.start","outcome":"ok"}
```

Stop it with Ctrl-C. The process holds every key and every credential in
memory, so stopping it destroys all of them.

A liveness check, on the default port:

```sh
curl -s http://127.0.0.1:8787/healthz
# {"status":"ok"}
```

## Environment knobs

Four, all optional, all read in `src/main.mjs` and nowhere else. An unset or
empty value falls back to the default.

| Variable | Default | Effect |
|---|---|---|
| `SPIKE_PORT` | `8787` | Listen port. A non-integer or out-of-range value silently falls back to the default. `0` picks an ephemeral port. |
| `SPIKE_RESOURCE_ORIGIN` | the bound origin (`http://127.0.0.1:<port>`) | The advertised OAuth `issuer` and `resource`, and the origin every issued token is bound to. Set this to the **public HTTPS origin** whenever the server is reached through a tunnel or a proxy — otherwise discovery advertises a loopback URL the caller cannot reach. |
| `SPIKE_REDIRECT_URI` | `https://example.invalid/spike-callback` | The single redirect URI the static client is registered with, matched **exactly**. A real connector client has its own callback URL; until this is set to that exact string, its authorize request is refused with `spike.redirect_mismatch`. |
| `SPIKE_DCR` | unset (off) | `on` enables `POST /oauth/register` and advertises `registration_endpoint` in discovery. Any other value, or unset, leaves the route answering `spike.dcr_disabled` (404). |

There is no host knob: the server binds `127.0.0.1` only
(`DEFAULT_HOST` in `src/constants.mjs`). Anything that fronts it must run on
the same machine.

## Startup guard

Before it opens a socket, the process inspects its own environment and refuses
to start if it carries any Dayfold-shaped variable (`src/guard.mjs`):

- the literal names `DATABASE_URL`, `FAMILY_ID`, `HOUSEHOLD_SECRET`;
- **any** name beginning with `AUTH_` or `DAYFOLD_` — prefixes, not an
  enumeration, so `DAYFOLD_API`, `DAYFOLD_SESSION_SECRET`, and any future
  sibling are all refused.

The refusal is one log line and exit code 1, naming no variable:

```json
{"ts":"...","testRunId":"fwWbwzln95E","class":"server.start","outcome":"rejected"}
```

This is the structural reason the spike cannot borrow a real Dayfold
credential. If the server will not start, check the shell for those names
first.

## HTTP surface

Every route below is the complete surface; anything else is a closed 404.

| Method | Path | Purpose | Log class |
|---|---|---|---|
| GET | `/.well-known/oauth-protected-resource` | RFC 9728 resource metadata | `discovery.protected_resource` |
| GET | `/.well-known/oauth-authorization-server` | RFC 8414 AS metadata | `discovery.authorization_server` |
| POST | `/oauth/register` | Dynamic client registration — 404 unless `SPIKE_DCR=on` | `oauth.register` |
| GET | `/oauth/authorize` | Renders the synthetic consent page and mints a single-use approval ticket. **Never** issues a code. | `oauth.authorize` |
| POST | `/oauth/approve` | Consumes the ticket, issues the code, 302s to the exact redirect | `oauth.approve` |
| POST | `/oauth/token` | `authorization_code` and `refresh_token` grants | `oauth.token` |
| POST | `/oauth/revoke` | Revokes a credential; always answers 200 | `oauth.revoke` |
| POST | `/mcp` | Streamable HTTP, stateless, bearer-gated | `mcp` |
| GET, DELETE | `/mcp` | Authenticates, then refuses 405 (stateless mode has no session and no standalone stream) | `mcp` |
| GET | `/healthz` | Liveness | `health` |

Advertised OAuth metadata is deliberately narrow: `response_types_supported`
is `["code"]`, `grant_types_supported` is
`["authorization_code","refresh_token"]`, `code_challenge_methods_supported`
is `["S256"]`, `token_endpoint_auth_methods_supported` is `["none"]`. Implicit,
password, and `plain` PKCE are never offered.

Every response carries `cache-control: no-store`, `referrer-policy:
no-referrer`, `x-content-type-options: nosniff`, and
`content-security-policy: default-src 'none'; frame-ancestors 'none';
base-uri 'none'; form-action 'self'`.

## The two tools

Both input schemas are hand-authored JSON Schema with
`additionalProperties: false`, published in `tools/list` and enforced at call
time by the same module (`src/mcp-schema.mjs`), so the advertised contract and
the enforced contract cannot drift.

### `dayfold_spike_identity`

Input: `{ "schemaVersion": 1 }` — nothing else is accepted.

Returns constants plus the run id this credential should finish. A repeat call
on the same credential returns the same run:

```json
{"schemaVersion":1,"installId":"inst_spike_constant","status":"ready_first_run",
 "scopes":["mcp:context.read","mcp:draft.submit"],"spikeRunId":"run_spike_<random>"}
```

### `dayfold_spike_finish`

Input:

```json
{"schemaVersion":1,"runId":"run_spike_...","result":"no_changes",
 "sources":[{"source":"gmail","outcome":"reported_zero_results","recordsReported":0}],
 "clientRequestId":"req_spike_1"}
```

Closed value sets:

| Field | Allowed values |
|---|---|
| `schemaVersion` | `1` (const) |
| `result` | `draft`, `no_changes`, `failed` |
| `sources[].source` | `gmail` (exactly one row, no duplicates) |
| `sources[].outcome` | `reported_observed`, `reported_zero_results`, `reported_unavailable` |
| `sources[].recordsReported` | integer 0–100; must be `0` unless the outcome is `reported_observed` |
| `runId` | 1–128 chars |
| `clientRequestId` | 1–64 chars matching `^[A-Za-z0-9_-]+$` |

Returns a receipt of closed enums and bounded counts:

```json
{"schemaVersion":1,"runId":"run_spike_...","status":"recorded","result":"no_changes",
 "sourcesRecorded":1,"recordsRecorded":0}
```

Finish is idempotent per `clientRequestId`: the same id over the same payload
replays the first receipt; the same id over a changed payload is
`spike.replay_mismatch`; a second distinct finish on a closed run is
`spike.run_closed`. A `runId` held by another credential is reported exactly
like one that never existed (`spike.run_unknown`), so it can never become an
existence oracle.

`sources[].outcome` values are named `reported_*` on purpose: they are what a
caller **says** happened. The spike verifies none of it, and neither can
Dayfold (ADR 0071 §9).

### Response shape

With `Accept: application/json, text/event-stream` the SDK answers in
Server-Sent Events frames, not bare JSON:

```text
event: message
data: {"result":{...},"jsonrpc":"2.0","id":3}
```

A tool-level rejection is a normal JSON-RPC **result** with `isError: true`
whose entire content is one closed code — no field name, no echoed value, no
library text:

```text
data: {"result":{"content":[{"type":"text","text":"spike.schema_invalid"}],"isError":true},"jsonrpc":"2.0","id":3}
```

An unregistered JSON-RPC method (a client probing `prompts/list` or
`resources/list` against a tools-only server) is a JSON-RPC error with the
standard `-32601` code and the closed message `spike.unknown_method`.

## Caps, TTLs, and limits

| Thing | Value | Source |
|---|---|---|
| Access-token TTL | 5 minutes | `ACCESS_TOKEN_TTL_MS` |
| Authorization-code TTL | 10 minutes, single use, stored hashed | `AUTH_CODE_TTL_MS` |
| Approval-ticket TTL | 10 minutes, single use, stored hashed | `APPROVAL_TTL_MS` |
| Dynamically registered client TTL | 10 minutes | `DCR_CLIENT_TTL_MS` |
| OAuth request body cap | 16 KiB | `OAUTH_BODY_LIMIT_BYTES` |
| `/mcp` request body cap | 64 KiB, enforced on raw bytes before any parse | `MCP_BODY_LIMIT_BYTES` |
| `/mcp` handling deadline | 10 s, then `504 spike.deadline_exceeded` | `MCP_DEADLINE_MS` |
| In-flight `/mcp` calls per credential | 4, then `429 spike.too_many_requests` (refused immediately, never queued) | `MCP_MAX_CONCURRENT_PER_CREDENTIAL` |
| OAuth parameter length | 2048 default, tighter per parameter | `MAX_PARAM_LENGTH` |
| Refresh tokens | opaque, rotating; presenting a rotated token revokes the whole lineage | `src/store.mjs` |

Constants live in `src/constants.mjs`.

## Identity constants

| Constant | Value | Why |
|---|---|---|
| Token audience | `dayfold-mcp-spike` | Deliberately **not** the production `dayfold-mcp` (system-design §10), so a spike token can never be mistaken for a real one. |
| Subject | `user_spike_local` | Synthetic; there is no account. |
| Static client id | `client_spike_static` | The one pre-registered client. |
| Static client name | `Spike Synthetic Connector` | Rendered, escaped, on the consent page. |
| Scopes | `mcp:context.read`, `mcp:draft.submit` | Same names as production; different audience and issuer. |
| MCP server name / version | `dayfold-spike-claude-mcp` / `0.1.0` | Reported in `initialize`. |
| Install id | `inst_spike_constant` | A constant string, not read from anywhere. |

Signing keys are generated per process and held only in memory: **restarting
the server invalidates every token it ever issued.**

## Closed error codes

Every error the spike can produce is one of these 31 strings. Nothing else
ever reaches a response body: no library message, no provider message, no echo
of caller input, no indication that another tenant exists.

| Code | HTTP | Raised when |
|---|---|---|
| `spike.not_found` | 404 | Unknown route. |
| `spike.method_not_allowed` | 405 | Known path, wrong method (includes `GET`/`DELETE /mcp`). |
| `spike.too_large` | 413 | Body over the route's byte cap; the body is drained, never parsed. |
| `spike.schema_invalid` | 400 | Unknown, repeated, empty-valued, or oversized parameter; malformed JSON; a tool argument outside its schema (as a tool error, HTTP 200). |
| `spike.internal` | 500 | Any thrown error, mapped to one code. |
| `spike.unknown_client` | 400 at authorize, 401 at token | `client_id` not registered, or expired. |
| `spike.redirect_mismatch` | 400 | `redirect_uri` is not the exact configured redirect (also DCR claiming any other, or more than one, URI). |
| `spike.resource_mismatch` | 400 | A `resource` indicator was sent and does not equal the advertised resource. |
| `spike.unsupported_response_type` | 400 | `response_type` is not `code`. |
| `spike.pkce_required` | 400 | No `code_challenge`. |
| `spike.unsupported_pkce_method` | 400 | `code_challenge_method` is absent or not `S256` (absent means `plain` under RFC 7636). |
| `spike.scope_invalid` | 400 | A requested scope is unsupported, duplicated, or the list is empty; on refresh, any widening. |
| `spike.approval_invalid` | 400 | Approval ticket unknown, already used, or expired. |
| `spike.unsupported_grant_type` | 400 | `grant_type` is neither `authorization_code` nor `refresh_token`. |
| `spike.invalid_grant` | 400 | Unknown code or refresh token; grant bound to another client; a rotated refresh token replayed (which revokes the lineage). |
| `spike.code_expired` | 400 | Authorization code past its 10 minutes. |
| `spike.code_already_used` | 400 | Authorization code replayed. |
| `spike.pkce_verifier_mismatch` | 400 | `code_verifier` does not hash to the stored challenge. |
| `spike.not_acceptable` | 406 | `/mcp` request whose `Accept` lacks `application/json` or `text/event-stream`. |
| `spike.unsupported_media_type` | 415 | `/mcp` request that is not `application/json`. |
| `spike.too_many_requests` | 429 | More than 4 in-flight `/mcp` calls for one credential. |
| `spike.deadline_exceeded` | 504 | `/mcp` exchange still running after 10 s. |
| `spike.unknown_tool` | 200 (tool error) | `tools/call` naming a tool that is not one of the two. |
| `spike.unknown_method` | JSON-RPC `-32601` | A JSON-RPC method with no registered handler. |
| `spike.unsupported_protocol_version` | 400 | `MCP-Protocol-Version` header present, unsupported, on a non-`initialize` message. |
| `spike.run_unknown` | 200 (tool error) | `runId` unknown **or** owned by another credential. |
| `spike.run_closed` | 200 (tool error) | A second distinct finish on a closed run. |
| `spike.replay_mismatch` | 200 (tool error) | Same `clientRequestId`, different payload. |
| `spike.unauthorized` | 401 | Any bearer failure — bad signature, wrong issuer/audience/resource, expired, or revoked credential. One code for all of them, so nothing is learned about which check failed. |
| `spike.dcr_disabled` | 404 | `POST /oauth/register` while `SPIKE_DCR` is not `on`. |
| `spike.env_contaminated` | — | Startup refusal; never reaches HTTP. |

A `401` from `/mcp` carries a `WWW-Authenticate: Bearer` header pointing at the
protected-resource metadata document.

## Diagnostics contract

One line per request, JSON, exactly four keys:

```json
{"ts":"2026-08-20T20:51:56.553Z","testRunId":"I51x9yJrzAE","class":"oauth.authorize","outcome":"ok_resource_absent"}
```

`testRunId` is random per process and is the only correlation handle. A log
line with any fifth key, or any value outside the two enums below, is a defect
worth reporting.

**`class`** (11): `server.start`, `health`, `http.unknown`,
`discovery.protected_resource`, `discovery.authorization_server`,
`oauth.register`, `oauth.authorize`, `oauth.approve`, `oauth.token`,
`oauth.revoke`, `mcp`.

**`outcome`** (16): `ok`, `ok_resource_absent`,
`ok_protocol_version_absent`, `rejected`, `invalid_request`, `invalid_grant`,
`unauthorized`, `not_found`, `method_not_allowed`, `too_large`, `throttled`,
`protocol_rejected`, `method_unsupported`, `deadline_exceeded`, `conflict`,
`error`.

Three outcomes carry the spike's own observations rather than a plain success:

- `ok_resource_absent` — the request succeeded and carried **no** RFC 8707
  `resource` indicator. Recorded independently at `/oauth/authorize` and
  `/oauth/token`, because a client may send it at one and not the other.
- `ok_protocol_version_absent` — the request succeeded and carried **no**
  `MCP-Protocol-Version` header.
- `protocol_rejected` — the spike's own checks all passed and the MCP transport
  still answered 4xx/5xx.

Presence, never value: whether a client sends `resource` or a protocol version
is protocol shape, which is exactly what the spike exists to observe; the value
itself is never written anywhere.

## Deliberate behaviors that can look like defects

Nine of them, each designed, each capable of reading as a broken spike if it is
hit unannounced: strict empty-value rejection, the strict six-field DCR
allowlist, lenient handling of an absent `resource`, two static SDK strings on
the wire, three protocol-mandated reflections, the protocol-version screen's
`initialize` exemption, unbounded in-memory ticket state, the startup guard,
and the per-process signing key. **Read
`RUNBOOK.md` § "Expected behaviors that can look like defects" before running
the matrix** — every one of them is written up there with what to record.

## Tests

```sh
npm test
```

| File | Tests | Covers |
|---|---|---|
| `test/oauth.test.mjs` | 39 | Discovery, authorize rejections, resource-indicator recording, no auto-approval on GET, happy path, code single-use and expiry, PKCE, refresh rotation and lineage revocation, revocation, DCR, security headers, startup guard, transport limits. |
| `test/mcp.test.mjs` | 39 | Unauthenticated and cross-bound tokens, scope authority, revoked credential, expired token, `initialize`/`tools/list` shape, identity constants, schema rejection, replay and conflict, run-ownership opacity, pre-parse body cap, deadline and concurrency caps, statelessness. |
| `test/diagnostics.test.mjs` | 71 | Content-blindness of every line a full drive produces, leak canaries, credential material, the single-writer and no-Dayfold-env source scans, and the import allowlist. |
| **Total** | **149** | |

## Provenance

Built across commits `7d445960` → `1fb54d44` on branch
`codex/v0-1-claude-handoff`. Verified locally on Node v24.13.0 / npm 11.6.2:
`npm install`, `npm test` (149 pass / 0 fail), `npm start`, and a full
loopback drive of authorize → approve → token → `initialize` → `tools/list` →
`tools/call` → revoke.

## Next

`RUNBOOK.md` — the operator-run procedure. **Every external step in it is
operator-only.** Results, when there are any, go in
`research/2026-08-20-smart-briefings-v0.1-compatibility-spike.md`.
