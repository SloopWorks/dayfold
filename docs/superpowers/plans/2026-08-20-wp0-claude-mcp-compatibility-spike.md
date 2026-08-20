# WP0 — synthetic Claude MCP compatibility spike (task plan)

**Parent plan:** `docs/superpowers/plans/2026-08-20-smart-briefings-v0.1-claude-bridge.md` §4
**Spec:** `specs/smart-briefings-v0.1/system-design.md` (§4, §8, §9, §10, §15, §16)
**ADR:** Proposed `adr/0071-self-managed-claude-bridge-v0.1.md`
**Entrypoint:** `specs/smart-briefings-v0.1/CLAUDE-HANDOFF.md` (Phase A)

This plan covers ONLY the work the gate table marks "allowed now": read/reconcile
the packet, and build + test the **local** synthetic spike artifact and its
operator runbook. It stops before deployment, any Claude account action, connector
installation, Terms acceptance, spend, or private data.

## Global Constraints (binding on every task)

1. **Synthetic only.** No real family, mailbox, Hub, person name, address, email,
   or token anywhere in code, fixtures, docs, or tests. Placeholder identifiers
   must be self-evidently synthetic (`inst_spike_...`, `example.invalid`).
2. **Credential isolation.** The spike must not read, import, or depend on any
   Dayfold credential, database, migration, env var, or module. Nothing under
   `apps/`, `packages/`, or `specs/domain-model/` may be imported. All spike keys
   and secrets are generated per process and held in memory only.
3. **No external action.** No deployment, tunnel, account creation, publish, or
   outbound network call at runtime. The server only answers inbound requests.
4. **Isolation of files.** Every code change lands under `spikes/claude-mcp-v0.1/`.
   Docs land only at the exact paths each task names. No file under `apps/`,
   `packages/`, `adr/`, `context/`, or `planning/` may be modified.
5. **Diagnostics are content-blind.** A log line carries exactly three fields plus
   a timestamp: `testRunId` (random per process), `class` (closed enum),
   `outcome` (closed enum). No message text, no request/response body, no header,
   no token, no tool input, no client name, no URL query.
6. **Errors are closed codes.** Every error response body and every MCP tool error
   is a bounded Dayfold-owned code from a fixed set. Never echo input, never
   include a provider/library message, never reveal existence of another tenant.
7. **Strict schemas.** Every tool input schema and every OAuth request validator
   sets `additionalProperties: false` and rejects unknown fields.
8. **Tests first.** Write the failing test before the implementation for every
   behavior listed in a task. Tests run with `node --test` (zero test deps).
9. **Runtime.** Node >= 20, ESM (`.mjs`). Exactly one runtime dependency is
   permitted: `@modelcontextprotocol/sdk` pinned to the exact version `1.30.0`
   (no `^`, no `~`). No other dependency, dev or runtime.
10. **No claim inflation.** No doc, comment, or log may state or imply that the
    spike proves Claude behavior, that Gmail was read, or that any gate is passed.

## Task 1 — Packet reconciliation report

Produce `research/2026-08-20-smart-briefings-v0.1-packet-reconciliation.md`.

The gate table's first row ("read/reconcile packet") has completion evidence
"inconsistencies reported, no repeated planning review". This task reports
inconsistencies **only**. It does not re-run the recorded two-round adversarial
review, does not re-argue architecture, and does not edit any packet file.

Read and cross-check against the actual repository state:

- `specs/smart-briefings-v0.1/CLAUDE-HANDOFF.md`
- `specs/smart-briefings-v0.1/system-design.md`
- `adr/0071-self-managed-claude-bridge-v0.1.md`, `adr/decisions-index.md`
- `adr/0061-cloud-routine-private-content-boundary.md`,
  `adr/0062-routine-observability-and-recovery-telemetry.md`
- `docs/superpowers/plans/2026-08-20-smart-briefings-v0.1-claude-bridge.md`
- `docs/superpowers/plans/2026-08-07-routine-integration-safe-slice.md`
- `designs/PROMPT-smart-briefings-v0.1-claude-bridge.md`
- `packages/routine-schema/`, `specs/domain-model/schemas/routine-*.schema.json`
- `apps/cli/src/main/kotlin/RoutineContract.kt`
- `apps/api/migrations/`, `apps/api/package.json`
- `apps/client/.../features/routines/`, `apps/ui/.../features/routines/`
- `processes/agent-dev-loop.md`, `CLAUDE.md`, `backlog/now.md`

Report each finding as a row: `ID | severity (blocking//material/minor) | packet
claim (file:line) | repository reality (file:line) | recommended reconciliation |
who decides (agent/operator)`. At minimum, verify and state a verdict for:

- the migration numbers the parent plan reserves (`0022/0023/0024`) against the
  highest migration currently on disk;
- the `packages/routine-schema/schemas/` + `fixtures/v2/` layout the parent plan
  names against where that package actually loads schemas from today;
- the ADR index numbering (0066–0070) and whether ADR 0071's number is safe;
- whether ADR 0061 / ADR 0062 statements conflict with ADR 0071 and which exact
  clauses the plan's "narrow/replace" instruction has to touch;
- whether `docs/superpowers/plans/2026-08-07-routine-integration-safe-slice.md` is
  superseded, partially live, or already built;
- the JDK/toolchain the parent plan's §14 verification block names against the
  version `processes/agent-dev-loop.md` pins;
- whether `backlog/now.md`, `CLAUDE.md`'s directory map, and
  `adr/decisions-index.md` reflect this work at all;
- V1 routine artifacts already in the repo (`RoutineContract.kt`, routine schemas,
  client/UI routine features) and exactly which of them WP1's V2 schema freeze
  would shadow rather than replace.

Close with a short "no new review opened" statement and an explicit list of items
that are operator decisions, not agent decisions.

**Files:** create `research/2026-08-20-smart-briefings-v0.1-packet-reconciliation.md` only.

## Task 2 — Spike scaffold and synthetic OAuth authorization server

Create `spikes/claude-mcp-v0.1/` with `package.json` (name
`@dayfold/spike-claude-mcp-v0.1`, `private: true`, `type: module`,
`"test": "node --test test/"`, engines node >=20, the single pinned dependency),
a `.gitignore` if needed, and `src/` + `test/` trees.

Implement a self-contained synthetic OAuth 2.1 authorization server on `node:http`:

```
GET  /.well-known/oauth-protected-resource
GET  /.well-known/oauth-authorization-server
POST /oauth/register        (only when SPIKE_DCR=on; otherwise 404 closed code)
GET  /oauth/authorize
POST /oauth/approve
POST /oauth/token
POST /oauth/revoke
GET  /healthz
```

Behavior:

- Authorization Code + **required** S256 PKCE. `plain` and a missing
  `code_challenge` are rejected.
- Exact `redirect_uri` string match against the registered client; exact
  `resource` match against the configured resource origin; `state` echoed back.
- `/oauth/authorize` renders a minimal HTML page that is unmistakably labelled
  synthetic, shows the bounded escaped client name, and requires a POST to
  `/oauth/approve` to proceed. No auto-approval on GET.
- Authorization codes: 32-byte random, stored **hashed** (SHA-256), single use,
  10-minute expiry, bound to client + redirect + resource + PKCE challenge.
- Access tokens: signed with a per-process random key, 5-minute lifetime,
  audience `dayfold-mcp-spike`, issuer = the configured resource origin, bound to
  the granted scopes `mcp:context.read mcp:draft.submit`.
- Refresh tokens: opaque 32-byte random, stored hashed, rotating; presenting a
  rotated (already-used) refresh token revokes the whole lineage.
- `/oauth/revoke` revokes the credential; a revoked credential's unexpired access
  token must fail on the next MCP call.
- Every state transition is compare-and-set; concurrent exchange of one code
  succeeds exactly once.
- Response headers on every OAuth/browser response: `Cache-Control: no-store`,
  `Referrer-Policy: no-referrer`, `Content-Security-Policy` with
  `frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`.
- Startup guard: if the process environment contains any Dayfold variable
  (`DATABASE_URL`, `DAYFOLD_API`, `FAMILY_ID`, `HOUSEHOLD_SECRET`, or any name
  starting with `AUTH_`), the server refuses to start with a closed code. This is
  the structural proof the spike cannot borrow a real credential.

Discovery documents must advertise the resource, the authorization server, the
supported PKCE method (`S256` only), the grant types, and the two scopes; and must
never advertise `plain`, implicit, or password grants.

**Tests (write first), in `test/oauth.test.mjs`:**

1. both discovery documents return the expected fields and no others;
2. `/oauth/authorize` rejects: missing PKCE, `plain` PKCE, unknown client,
   redirect mismatch, resource mismatch — each with a closed code, no echo;
3. GET `/oauth/authorize` never issues a code; only POST `/oauth/approve` does;
4. full happy path: authorize → approve → code → token → access + refresh;
5. code single-use: second exchange fails; concurrent exchange succeeds once;
6. code expiry boundary;
7. PKCE verifier mismatch fails;
8. refresh rotation works; reusing a rotated refresh revokes the lineage so the
   newest refresh also stops working;
9. `/oauth/revoke` makes a still-unexpired access token fail;
10. `/oauth/register` returns a closed 404 code when `SPIKE_DCR` is unset, and
    issues a short-lived client with only the proven redirect when it is `on`;
11. security headers present on authorize/approve/token responses;
12. startup guard rejects a Dayfold-shaped env var.

**Files:** `spikes/claude-mcp-v0.1/**` only.

## Task 3 — Streamable HTTP MCP surface and the two spike tools

Add `POST /mcp` (plus the `GET`/`DELETE` behavior the SDK's stateless mode
requires) using `@modelcontextprotocol/sdk` 1.30.0 `StreamableHTTPServerTransport`
in **stateless** mode (no session id, no resumability, no notifications, no
sampling, no elicitation, no resource subscriptions).

Authorization: every `/mcp` request requires a `Bearer` access token issued by
this spike's own token endpoint, with matching issuer, audience, resource, scope,
and a non-revoked credential. An unauthenticated or invalid request returns `401`
with a `WWW-Authenticate` header pointing at
`/.well-known/oauth-protected-resource`, and a body containing a closed code only.

Tools — exactly two, no others registered:

- `dayfold_spike_identity`: input `{ "schemaVersion": 1 }`, `additionalProperties:
  false`, no other property accepted. Returns a constant synthetic install ID, a
  closed `status` enum value (`ready_first_run`), the closed scope list, and the
  process `spikeRunId`.
- `dayfold_spike_finish`: input `{ schemaVersion: 1, runId, result, sources[],
  clientRequestId }`, `additionalProperties: false` at every object. `result` is
  the closed enum `draft | no_changes | failed`; each `sources[]` row is
  `{ source: "gmail", outcome: reported_observed | reported_zero_results |
  reported_unavailable, recordsReported: integer 0..100 }`; exactly one row per
  requested source, duplicates rejected; `recordsReported` must be 0 when the
  outcome is not `reported_observed`. `clientRequestId` is opaque, 1..64 chars of
  `[A-Za-z0-9_-]`. Returns closed enums and bounded counts only.

Run/replay semantics: `runId` must be a run this spike minted (the identity tool
mints one per credential); an unknown or foreign `runId` fails with a closed code
and no existence oracle. A repeated `clientRequestId` on the same run returns the
original receipt verbatim; a *different* payload under the same `clientRequestId`
is a closed conflict; a second distinct finish for a closed run is a closed
conflict.

Caps: request body limited to 64 KiB **checked before JSON parse**; per-request
handling deadline of 10 seconds; at most 4 concurrent in-flight `/mcp` requests
per credential, excess rejected with a closed code.

**Tests (write first), in `test/mcp.test.mjs`:**

1. unauthenticated `initialize`/`tools/list`/`tools/call` → 401 + correct
   `WWW-Authenticate`, closed body, no tool names leaked;
2. token from a *different* per-process key / wrong audience / wrong issuer /
   wrong resource → 401, each distinctly asserted;
3. revoked credential with an unexpired access token → 401;
4. expired access token → 401;
5. `initialize` + `tools/list` return exactly the two tools and their strict
   schemas;
6. `dayfold_spike_identity` returns the constant synthetic values;
7. schema rejection matrix for both tools: unknown field, wrong type, out-of-range
   count, bad enum, missing required, duplicate source row, non-zero count on a
   non-observed outcome, oversized `clientRequestId`, bad `clientRequestId` charset;
8. replay: same `clientRequestId` + same payload → identical receipt; same
   `clientRequestId` + different payload → conflict; second distinct finish →
   conflict;
9. unknown/foreign `runId` → closed code, identical shape to the unknown-run case
   (no existence oracle);
10. body cap: a 64 KiB + 1 request is rejected before parse;
11. deadline and concurrency caps return closed codes;
12. stateless proof: two sequential `tools/call` requests on separate connections
    with no session header both succeed.

**Files:** `spikes/claude-mcp-v0.1/**` only.

## Task 4 — Content-blind diagnostics and leak canaries

Implement the single logging front door used by every route: one line per request,
JSON, exactly the keys `ts`, `testRunId`, `class`, `outcome`. `class` and
`outcome` are closed enums declared in one module. Nothing else may call
`console.*` anywhere in `src/`.

Add an error mapper so that every thrown error — including one thrown by the MCP
SDK or by `JSON.parse` — is converted to a closed code before it reaches a
response body or a log line. Message text never survives.

**Tests (write first), in `test/diagnostics.test.mjs`:**

1. drive the full flow (discovery, authorize, approve, token, refresh, revoke,
   every MCP tool success and every failure class) while capturing stdout/stderr,
   then assert every captured line parses as JSON and has exactly the four
   permitted keys with `class`/`outcome` drawn from the declared enums;
2. canary test: inject synthetic canary strings (a medical phrase, a financial
   phrase, a child's synthetic name, a synthetic email address, a synthetic URL,
   a synthetic bearer token, a synthetic OAuth code, a synthetic raw provider
   error) into tool inputs, headers, query strings, and client name, then assert
   none appears in any log line or any response body;
3. assert no access token, refresh token, authorization code, PKCE verifier, or
   client secret (plaintext or hash) appears in any log line;
4. a source-scan test asserting `src/**` contains no `console.log|warn|error|info`
   outside the logging module, and no `process.env` read of a Dayfold variable;
5. an isolation test asserting no file under `spikes/claude-mcp-v0.1/src` imports
   anything outside that directory except `node:*` and the one pinned dependency.

**Files:** `spikes/claude-mcp-v0.1/**` only.

## Task 5 — Operator runbook and compatibility report template

Create `spikes/claude-mcp-v0.1/README.md` (what it is, what it is not, how to
install/run/test locally, the closed code and enum reference) and
`spikes/claude-mcp-v0.1/RUNBOOK.md` — the operator-run procedure.

The runbook must:

- open with the authority banner: every step in the "operator-only" section is an
  external action the agent is forbidden to perform (deployment/tunnel, Claude
  account or plan selection, connector installation, Terms acceptance, spend), and
  the whole matrix stays synthetic;
- give the exact local commands (`npm install`, `npm test`, `npm start`) and the
  environment knobs (`SPIKE_PORT`, `SPIKE_RESOURCE_ORIGIN`, `SPIKE_DCR`,
  `SPIKE_REDIRECT_URI`);
- state plainly that Claude's cloud cannot reach `localhost`, and list the
  operator's reachability options **as options to choose, not as instructions the
  agent executed**, with the trade-off of each and the note that any of them is a
  gated external action;
- carry all ten questions from the parent plan §4 "operator-run matrix" as a
  numbered checklist, each with: what to do, what to capture (screenshot /
  redacted transcript / log line `testRunId`), and what a PASS, FAIL, and UNKNOWN
  look like for that specific question;
- for question 9 (the injected-mutation test), give the exact synthetic email text
  to plant, state that it must be planted in a synthetic mailbox only, and define
  PASS as a provider-level block or an unavoidable per-mutation human confirmation
  — with an explicit note that a Dayfold instruction or a polite refusal is NOT a
  pass;
- restate the six stop conditions from parent plan §4 and the eleven immediate
  stop conditions from the handoff, and instruct the operator to stop and report
  rather than work around any of them;
- include a redaction checklist to run over every screenshot/transcript before it
  is pasted into the report.

Create `research/2026-08-20-smart-briefings-v0.1-compatibility-spike.md` as the
pre-seeded report: front matter (status: NOT YET RUN; all evidence pending), a
row per matrix question with `PASS | FAIL | UNKNOWN` defaulted to **UNKNOWN**,
evidence columns (date, Claude plan, client/surface, artifact reference), an
"architecture/UI consequence" column, and a closing section listing which packet
statements each answer would reconcile. It must state at the top that no external
test has been run and no result may be inferred from this file.

**Files:** create `spikes/claude-mcp-v0.1/README.md`,
`spikes/claude-mcp-v0.1/RUNBOOK.md`,
`research/2026-08-20-smart-briefings-v0.1-compatibility-spike.md`.

## Task 6 — Repository bookkeeping

Small, exact edits only:

- `backlog/now.md`: add a dated "Active — Smart Briefings V0.1 Claude Bridge
  (Phase A)" entry stating what was built, that it is synthetic-only and
  un-deployed, and pointing at the runbook + report template. Add the operator
  actions this work is blocked on to "Operator actions pending" (they are the
  Phase B gate items and INB-39).
- `CLAUDE.md`: add one `spikes/` row to the directory map table
  (`Throwaway compatibility spikes; never production, never private data`,
  authority `Working state`). Change nothing else.
- `context/open-questions.md`: add the open questions this phase surfaces that are
  not already tracked (provider reachability path for the spike; whether the V1
  routine artifacts get shadowed or removed at WP1).

Do **not** touch `CHANGELOG.md` (no user-visible product/API change), any ADR, any
`context/values-and-direction.md`, or `planning/`.

**Files:** `backlog/now.md`, `CLAUDE.md`, `context/open-questions.md` only.

## Stop point

After Task 6 the plan stops. WP1+ (final hi-fi, V2 schema freeze, migrations,
bridge implementation) requires: the recorded provider spike result, ADR 0008
sign-off, and ADR 0071 acceptance with ratified constants — all operator gates.
