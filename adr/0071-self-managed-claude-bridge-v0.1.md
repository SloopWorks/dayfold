# ADR 0071: Claude Bridge V0.1 operator pilot

## Status

**Proposed** 2026-08-20. This is an operator-gated product, provider, security,
retention, and hosting decision. Merging the document does not accept it and does
not authorize deployment, external setup, private Gmail data, Terms acceptance,
customer access, or spend.

- Design: `specs/smart-briefings-v0.1/system-design.md`
- Hi-fi gate: `designs/PROMPT-smart-briefings-v0.1-claude-bridge.md`
- Plan: `docs/superpowers/plans/2026-08-20-smart-briefings-v0.1-claude-bridge.md`
- Reviews: `research/2026-08-20-smart-briefings-v0.1-adversarial-review.md`
- Provider evidence:
  `research/2026-08-20-smart-briefings-v0.1-compatibility-spike.md` — one
  operator-run session, 2026-08-21, Claude **Max** / claude.ai web / personal
  account. **3 of 10 matrix questions answered, 7 `UNKNOWN`.** Every "recorded
  2026-08-21" note below cites that file; nothing else in this ADR is measured.

If accepted, this narrows/replaces the V0.1 portions of Proposed ADR 0061.
Proposed ADR 0062 applies only where this ADR and the dedicated connector
diagnostic boundary do not supersede it.

## Context

Dayfold currently has a content API, CLI/curator path, a broad Smart Briefings
hi-fi, and a synthetic client preview. It has no live provider connector, OAuth
authorization server, connector credential, durable installation/run, or private
draft publication path.

The cheapest useful proof is bring-your-own Claude: the user's Claude account
performs inference and owns Google OAuth. Dayfold does not buy model inference or
request Gmail scopes. Claude remote connectors, however, call public MCP servers
from Anthropic's cloud and normally hold an OAuth credential for that resource.

M0 Dayfold content is already plaintext at the Dayfold API. A K3 relay would add
operations without shrinking the current server trust boundary. This is not true
after a server-blind E2EE cutover.

The proof also exposes two hard conflicts:

- Claude's native Gmail capability may include writes; a prompt asking Claude to
  read is not a permission boundary.
- Dayfold's constitution says family data is never used to train shared models. A
  consumer model-improvement toggle is not a verifiable absolute.

Therefore the first executable milestone is an operator-controlled, synthetic
compatibility pilot—not the commercial hosted product.

## Proposed decision

### 1. Name and scope it as an operator pilot

V0.1 is Claude-only, manually invoked, and supports one source owner, one family,
one selected Hub, and one live installation. One run yields zero or one private
`create_card` proposal. The Dayfold owner may accept or reject it; editing,
scheduling, attachments, links, Calendar, Drive, multi-card output, and automatic
publication are deferred.

Pricing, billing, customer signup, and customer Terms do not belong to this pilot.
They remain required for the later paid hosted release.

### 2. Put inference and Google OAuth in the user's Claude account

Dayfold does not call the Claude API, capture a Claude subscription credential,
hold a Google credential, poll Gmail, or invoke Google. The user manually starts
each run in Claude. Google authorization and provider-side data remain under
Anthropic/Google controls and outside Dayfold's revocation authority.

### 3. Host an isolated Dayfold MCP Bridge

Use a separate stateless Vercel service/entrypoint at a dedicated OAuth resource
origin. Do not mount MCP inside the current API middleware. The bridge has:

- Streamable HTTP MCP;
- its own discovery/authorize/token/revoke surface;
- its own issuer, audience, signing key, access verifier, opaque refresh store,
  and connector-only repositories;
- its own generated diagnostic source with request/message stripping;
- the same-region Postgres used for enrollment, run, and proposal state.

The main Dayfold API retains app approval, review, accept/reject, and revocation
status. A connector token is never accepted by current app/CLI routes and an
app/CLI token is never accepted by the bridge. Connector refresh tokens can never
enter `/auth/refresh`.

The bridge tools are only:

- return the minimum context from one bearer-bound Hub and start/resume a run;
- validate one bounded proposal;
- stage one source-owner-private proposal;
- finish the run with content-free provider-reported outcomes.

There is no generic Hub/content, apply, edit, delete, send, share, role, URL-fetch,
filesystem, shell, or OAuth tool.

**Recorded 2026-08-21 — the shape above is the shape the real client drives.**
Measured against Claude Max on claude.ai web, personal account. These facts
change no decision in this section; they close questions it was written around.

- **Dynamic client registration is not required** (spike question 3, F-RUNBOOK).
  Claude never called `/oauth/register`. It read the authorization-server
  metadata, found no `registration_endpoint`, and asked the operator to enter an
  OAuth client ID by hand; the entire ceremony then completed against a **public
  client with no secret** (`token_endpoint_auth_methods_supported: ["none"]`).
  The client is **metadata-driven, not probe-driven** — what the metadata
  advertises controls the behavior, and the registration route need not exist.
  *Not measured:* how the client behaves when `registration_endpoint` **is**
  advertised; that pass was not run.
- **Streamable HTTP is the right transport** (question 5). `initialize`,
  `tools/list`, and `tools/call` all completed against a stateless server that
  issues no session id; Claude negotiated MCP protocol version **`2025-11-25`**,
  opened no standalone `GET` stream, and issued no `DELETE` on `/mcp`.
- **The bridge is an architectural peer of a first-party connector** (question
  8). Gmail's own Claude connector is itself a remote MCP server **authored by
  Google** at `https://gmailmcp.googleapis.com/mcp/v1`, per Anthropic's
  provider-authored connector-directory page. The isolated-remote-MCP shape this
  section chose is the same shape Google ships on that surface.

### 4. Separate ephemeral enrollment from durable installation/run identity

A 60-minute enrollment attempt owns the browser/poll/approval ceremony. Approval
creates a durable installation with one-live-per-family constraint. The first
authenticated context call creates/resumes a server-minted run. One open run and
one proposal per run are enforced in the database. Finish is idempotent and may
close with no changes. App/Claude return alone never promotes state.

The manual lifecycle uses `ready_first_run`, `run_in_progress`, `ready_manual`,
`revoking`, and `revoked`; it does not claim the connector is Active.

### 5. Make publication a restricted, human-only insert

Every staged proposal is readable only by its source owner in Dayfold. The model
cannot supply identity, Hub, audience, visibility, provenance, source locator,
card ID, or apply authority.

Acceptance requires the source owner's human app token. The source owner is a
mandatory recipient; additional exact active adults are validated against the
Hub. The dedicated insert path always writes
`briefing_cards.visibility='restricted'` with the exact audience—even in a
family-visible Hub—and never calls the overwrite-capable generic upsert. Accepted
proposal content is purged in the same transaction after the canonical card is
inserted.

### 6. Bound and disclose Gmail-derived proposal content

The pilot uses one requested source preset: the run instruction asks Claude to
search no more than 100 results from the prior 14 days, excluding spam/trash.
Dayfold cannot enforce or verify the Gmail search boundary. It stores no Gmail
query, label, ID, link, header,
signature, attachment name, or raw message artifact.

Proposal title/body limits, safe Markdown, and forbidden fields minimize copied
content but cannot prove that Claude paraphrased rather than reproduced source
text. The owner must inspect the proposal. UI copy explicitly covers third-party,
child, health, financial, and other sensitive information and states that the
draft is server-readable by Dayfold M0.

### 7. Treat Gmail writes as a provider compatibility no-go gate

Before private-data use, the exact Claude surface must expose read-only Gmail
tools or unavoidable per-mutation human confirmation with no remembered approval,
silent retry, or unattended execution. A synthetic prompt-injection test attempts
send/reply/label/delete/archive. Failure to prove one of those provider-level
boundaries stops the pilot. Dayfold instructions alone never qualify.

### 8. Preserve the no-training constitution

All compatibility work uses synthetic data. Private operator Gmail/Dayfold data
requires either:

- an eligible commercial provider contract/posture under which customer content
  is not used to train shared models; or
- an explicit, separately accepted constitutional amendment.

Turning off a consumer model-improvement setting is useful risk reduction, but is
not sufficient authorization. Non-operator use additionally requires counsel and
privacy approval, versioned Terms/Privacy/AI disclosure acceptance, complete
export/deletion policy, and relevant store disclosures.

### 9. Distinguish Dayfold facts from Claude reports

Dayfold can verify its approval, bridge calls, schema-valid stage, finish receipt,
human decision, and revocation. It cannot prove what Claude read in Gmail or that
Claude removed a connector/task. Gmail outcomes and counts are always labeled
**reported by Claude**.

### 10. Adopt explicit retention and kill-switch contracts

The proposed record-by-record periods in the system design require operator
ratification. Accepted proposal content is purged in the acceptance transaction;
rejected/expired content disappears from review immediately and is purged within
24 hours. Existing unresolved card tombstone, hard-purge, backup, and propagation
policy blocks non-operator privacy claims.

A database-backed security pause advances a control epoch and gates enrollment
create/approve, browser authorize/redirect, DCR, code/token/refresh, MCP, and human
acceptance while leaving status/reject/revoke/purge available. The pause
transaction denies/revokes pending authorizations, attempts, codes, installations,
credentials, and refresh lineages. Every issuance transaction locks/checks the
same epoch; pre-pause artifacts remain invalid after resume and a fresh enrollment
is required. Existing access tokens fail epoch + credential checks.

### 11. Keep connector diagnostics content-blind

The isolated MCP Bridge uses a generated error/SWIP source with
`stripMessage=true` and request stripping. Raw provider/source/model/proposal
content, OAuth values, stable tenant/resource IDs, and support codes never enter
logs, analytics, Sentry, SWIP, fixtures, headers, or provider-visible errors.
Durable closed-code run state owns expected recovery.

## Consequences

### Positive

- Tests the BYO-Claude value proposition without model COGS or Dayfold Gmail OAuth.
- Separates connector credentials from human credentials and publication power.
- Keeps the first mutation one-card, private, restricted, insert-only, and human.
- Makes provider claims and constitutional/legal gates explicit.
- Defers attachments/evidence, K3, scheduling, and commercial systems until value
  and provider feasibility are known.

### Negative

- OAuth authorization-server behavior and a public MCP bridge are material new
  security/operational surfaces.
- Anthropic holds a revocable Dayfold credential and sees plaintext Dayfold/Gmail
  information selected for the run.
- Consumer Claude subscriptions cannot satisfy the current constitutional gate.
- Dayfold cannot guarantee Google-side read-only behavior or verify Gmail use.
- The direct plaintext bridge must be replaced before server-blind E2EE.
- Removing attachment/source-link evidence weakens the value proposition being
  tested; that is a deliberate pilot limitation.

## Rejected for this pilot

- Calling the pilot the paid hosted release.
- Reusing app/CLI credentials, refresh tables, generic Hub grants, or generic
  content routes.
- Mounting MCP under message-preserving API diagnostics.
- Prompt-only Gmail write prevention.
- Model-supplied identity/audience/visibility/provenance/card ID.
- Generic upsert or family-visible publication.
- Multi-card output, partial acceptance, or draft editing.
- Consumer-toggle-only private-data authorization.
- Attachments, links, source locators, scheduled/unattended runs, source writes,
  auto-publish, K3/K4, ChatGPT, or BYOK.
- Claiming a finish receipt proves a Gmail read.

## Acceptance gates

Before Accepted:

1. The synthetic Claude compatibility spike records OAuth/MCP/client/Gmail-write
   behavior with no private data.
2. The final spike-informed hi-fi receives ADR 0008 operator sign-off.
3. The operator ratifies hosting, source preset, all caps/retention/value
   thresholds, diagnostic isolation, and private-data authority.
4. Security tests cover cross-protocol access/refresh presentation, OAuth
   ceremony, exact restricted audience in both Hub visibility modes, IDOR,
   personal-response filtering, run/replay, kill switch, revocation, content caps,
   prompt injection, and leak canaries.
5. Proposed ADR 0062 is narrowed/replaced where incompatible.
6. The operator performs every external account, connector, preview deployment,
   Terms, and spend action.
7. Counsel/privacy and versioned legal acceptance precede any non-operator data.

## Revisit triggers

- Accepted E2EE makes server content opaque.
- Claude offers verified read-only tools, attachment bytes, or durable locators.
- OAuth/MCP behavior changes.
- Pilot value misses the ratified thresholds.
- Attachment/evidence value dominates body-derived cards, accelerating the later
  evidence-capture milestone.
