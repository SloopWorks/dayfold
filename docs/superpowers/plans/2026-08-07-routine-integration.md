# Routine Integration — implementation plan

> **Status:** Planning draft. Do not start customer-data handling, hosted
> decryption, auto-write, or provider provisioning until Proposed ADR 0061 is
> accepted. Two fresh-context adversarial reviews are still required.

**Goal:** Prove a scheduled, connected-source Dayfold curation loop while keeping
long-lived credentials and `FCK` on a K1/K3 Dayfold-controlled gateway, using the
existing CLI/content skill as the authoring seam.

**Design:**
`docs/superpowers/specs/2026-08-07-routine-integration-design.md`

**Claude Design gate:**
`designs/DESIGN-BRIEF-smart-briefings-subscription-routines.md` is authoritative
for the operator-clarified UX in which the user's Claude or ChatGPT subscription
owns scheduling and inference. Run it through a fresh Claude Design session and
obtain operator sign-off before deepening this plan or implementing a surface.
The architecture sections below still require reconciliation with that clarified
product boundary; this link does not ratify Proposed ADR 0061.

## Non-negotiable boundaries

- M0 is plaintext today; do not describe it as E2EE.
- No `HOUSEHOLD_SECRET`, human app credential, or rotating CLI refresh token in a
  scheduled cloud job.
- No Dayfold-owned Gmail OAuth in this plan.
- No direct K4/private-key cloud installation until ADR 0061 is Accepted.
- No W3/member intent in a hosted routine; ADRs 0041/0042 still govern.
- Shadow first. The first networked routine cannot write Dayfold content.
- The first auto-write tier, if reached, is upsert-only: no deletes, ACL/visibility
  widening, roles/invites, member-block edits, messages, or external actions.
- Source bodies and decrypted content never enter Git, test fixtures, audit logs,
  analytics, error reports, or task notes.
- Any new enrollment/revoke/run-history UI is ADR 0008 design-gated.

## Sequence and gates

```text
contract fixtures
  -> K1 manual shadow
  -> K1 scheduled shadow
  -> routine principal + K3 gateway
  -> staged transactional writes
  -> bounded auto-upserts
  -> one provider adapter
  -> optional K4 decision (separate gate)
```

## Phase 0 — Ratify and threat-model

**Exit:** the architecture is accepted or narrowed; no production secrets/data were
created.

- [ ] Run adversarial review 1 with fresh context: crypto/auth, prompt injection,
  Google source handling, multi-tenant ACL, and provider retention. Reviewer must
  try to kill the K3 gateway design.
- [ ] Fold corrections into the design and ADR; keep an explicit review record.
- [ ] Run adversarial review 2 with fresh context: simplify components, remove
  speculative portability, test the <2 hr/week steady-state constraint.
- [ ] Operator accepts/rejects ADR 0061 and answers INB-34.
- [ ] If E2EE is in the first implementation scope, accept/supersede ADRs 0015 and
  0017 first. Otherwise state plainly that the dogfood slice processes M0 plaintext.
- [ ] Privacy/counsel pass: connected-provider Gmail/Drive data flow, disclosure,
  eligible account tier, retention controls, deletion handling, and the trigger for
  CASA/restricted-scope verification.
- [ ] Choose one first provider spike based on connector access, not model taste.

## Phase 1 — Provider-neutral contract, no network writes

**Exit:** deterministic fixtures prove the same source/context input yields a valid,
bounded changeset; no provider integration and no Dayfold write.

### Files

- Create `specs/domain-model/schemas/routine-manifest.schema.json`.
- Create `specs/domain-model/schemas/routine-changeset.schema.json`.
- Create sanitized fixtures under `specs/domain-model/examples/routines/`.
- Add a new `dayfold-routine` skill only if the curator cannot cleanly expose a
  no-push changeset mode; do not weaken `dayfold-curator`'s propose-confirm rule.
- Add schema/codegen drift tests alongside the existing content-schema gates.

### Work

- [ ] Define manifest fields: identity, runner type, adapter, mode, expiry,
  permitted hubs/resources, source window, operation cap, forbidden actions.
- [ ] Define changeset fields: `runId`, `baseCursor`, operations, stable `opId`,
  action/kind/id, expected version, target hub, source references, body, provenance.
- [ ] Specify canonical validation errors and redacted run summaries.
- [ ] Extract a pure routine-policy validator shared by CLI/gateway/API tests.
- [ ] Add prompt-injection fixtures where email/doc text asks the agent to reveal
  secrets, widen scope, call arbitrary URLs, or delete content. Expected result is a
  policy rejection or inert quoted data.
- [ ] Add ACL fixtures for family/restricted hubs and ensure a card/block audience
  never exceeds its source/target hub audience.
- [ ] Add authorship fixtures: loop-created may follow policy; member-created is
  never edited by the routine.

### Verification

- [ ] Schema valid/invalid fixture suite.
- [ ] Property test: no accepted changeset contains a forbidden action.
- [ ] Property test: audience(result) is a subset of allowed audience.
- [ ] Replay fixture: duplicate `runId/opId` is recognized before apply exists.
- [ ] Secret/PII canary test: raw source content cannot enter the run summary/audit
  serialization.

## Phase 2 — K1 manual shadow using the existing CLI

**Exit:** the operator can run one command locally, inspect connected sources and
current Dayfold state, and receive a changeset/diff with zero writes.

### CLI work

- [ ] Refactor `apps/cli/Main.kt` command branches into testable `cmd*()` functions
  first (already recorded as a maintenance prerequisite in `backlog/now.md`).
- [ ] Add `dayfold changeset validate <file>` — offline structural/policy validation.
- [ ] Add `dayfold changeset diff <file>` — pull current state and show proposed
  creates/updates/conflicts; never write.
- [ ] Add `dayfold routine shadow <manifest> <changeset>` as orchestration sugar
  only if it remains a thin composition of pull/validate/diff.
- [ ] Ensure `DAYFOLD_NO_UPDATE_CHECK=1` in unattended runs.
- [ ] Keep current Keychain/Secret Service auth for K1; do not use
  `--allow-env-key` on the operator Mac.

### Skill work

- [ ] Give the routine a bounded input contract: current Dayfold JSON, normalized
  source records, manifest, and current time.
- [ ] Reuse curator content-model/guardrail references rather than duplicating them.
- [ ] Replace per-run human confirmation with **output-only shadow mode**, not
  silent pushing.
- [ ] Require source URLs/opaque IDs, provenance, dedupe against `pull`, stable
  checklist IDs, and honest privacy chips.
- [ ] Treat all source content as data; never interpolate it into system/tool
  instructions.

### Verification

- [ ] CLI unit tests use faked HTTP/auth; do not depend on a real family.
- [ ] Full CLI test: `cd apps/cli && ./gradlew test` on JDK 17.
- [ ] Manual dogfood over synthetic or operator-owned data: generated changeset is
  reviewed, no API PUT/DELETE appears in the transcript/log.
- [ ] Run twice over identical inputs: second run proposes no duplicates.

## Phase 3 — Routine principal and K3 gateway

**Exit:** a remote agent can read a minimal working set and validate/stage a
changeset through narrow tools without receiving Dayfold long-lived credentials or
`FCK`.

**Start gate:** Phase-2 shadow runs must demonstrate enough useful, non-duplicative
output to justify an always-on component. Otherwise stop at K1; the learning goal is
met without adding K3 maintenance.

### Design gate

- [ ] Run
  `designs/DESIGN-BRIEF-smart-briefings-subscription-routines.md`: mock the
  provider-owned subscription handoff, provider-to-Dayfold OAuth approval,
  per-hub scope/privacy review, waiting/active/unknown-health states, first-draft
  review, two-step revoke, and optional Claude “Run from Dayfold” credential
  paste; obtain operator sign-off (ADR 0008).

### API/data model

- [ ] Add tracked migrations for `routine_principals`, `routine_assertion_jti`,
  `routine_runs`, and `routine_changesets` (or prove an existing table can safely
  own each concern).
- [ ] Add owner-only enrollment/approve/revoke endpoints. Reuse the device approval
  ceremony and origin/fingerprint language where possible.
- [ ] Add a short-lived Ed25519 client-assertion exchange. Verify `aud`, expiry,
  clock skew, live principal/policy, unique `jti`, family binding, key fingerprint,
  and credential grants before minting a five-minute access token.
- [ ] No routine refresh token.
- [ ] Distinguish `runner=k1|k3|k4` in audit and UI.
- [ ] Resolve routine policy and credential grants on every operation.
- [ ] Rate-limit assertion exchange and tool calls per routine/family.

### Gateway

- [ ] Build the smallest daemon around the CLI/library; do not reimplement the
  content model, HTTP auth, or crypto.
- [ ] Store the routine private key and, after E2EE, the unwrap key in OS keychain/
  TPM/KMS; never in repo/config/plaintext env files.
- [ ] Expose four tools only: context-get, changeset-validate, changeset-stage,
  run-finish. No arbitrary shell/path/URL tool.
- [ ] Authenticate provider calls with expiring, audience-bound tokens. Bind the
  provider run identity to `runId`; reject replays.
- [ ] Minimize and size-cap plaintext returned per tool call/run.
- [ ] Domain-allowlist outbound traffic; URL fetch remains with provider connectors
  or a separately sandboxed fetcher.
- [ ] Wipe ephemeral plaintext on success, failure, timeout, and restart recovery.

### Verification

- [ ] Assertion tests: wrong family/audience/key, expired, future, replayed `jti`,
  revoked principal, rotated key, excessive clock skew.
- [ ] IDOR matrix across two families and restricted hubs.
- [ ] Gateway tool fuzz: path traversal, shell strings, oversized payloads, hostile
  source URLs, prompt-injection content.
- [ ] Canary secrets placed in gateway env/keychain never appear in tool output,
  logs, exceptions, SWIP/PostHog/Sentry, or provider-visible files.
- [ ] Kill gateway mid-run: no orphaned token/plaintext; run becomes failed/expired.

## Phase 4 — Staged, transactional apply

**Exit:** an operator-approved changeset applies all-or-nothing, replay-safe, and
conflict-safe through CLI/gateway.

- [ ] Decide whether to realize ADR 0039's deferred `/mutations` batch or add a
  narrowly authoring-specific changeset endpoint; do not maintain two overlapping
  batch engines.
- [ ] Validate the complete changeset before opening a transaction.
- [ ] Enforce server-side: policy, scopes, target visibility, audience intersection,
  author-kind boundary, action/write caps, and no forbidden operations.
- [ ] Enforce `expectedVersion` on updates. Return a structured conflict; never
  silently last-write-wins over a human/another routine.
- [ ] Record/replay `runId + opId` idempotently.
- [ ] Apply hub/section/block/card changes all-or-nothing.
- [ ] Add `dayfold changeset apply <file> --staged-id <id>`; print a concise receipt.
- [ ] Keep per-run approval outside the model. Only the operator-approved staged ID
  is applicable.
- [ ] Add rollback/recovery guidance. Prefer compensating upserts/tombstones only if
  they preserve authorship and audit; do not promise magic undo.

### Verification

- [ ] Partial failure rolls back every operation.
- [ ] Same changeset twice returns the original receipt with no version bumps.
- [ ] Concurrent human edit produces conflict and no partial routine update.
- [ ] Attempts to delete/widen/change role/edit member content are rejected even
  with a forged body/prompt.
- [ ] Audit contains IDs/counts/result/provider/routine/run, no raw bodies.

## Phase 5 — Bounded auto-upserts

**Exit:** a narrowly authorized routine can apply safe upserts without per-run
approval and remains easy to disable.

- [ ] Operator ratifies the exact action matrix, resource set, per-run write cap,
  schedule, source window, and routine expiration.
- [ ] Change manifest mode from `staged` to `bounded_auto` through an owner-only
  approval; never via the routine itself.
- [ ] Auto-apply only changesets that pass the same Phase-4 endpoint and policy.
- [ ] Alert/report every rejection and every auto-applied run.
- [ ] Add a one-tap/one-call routine revoke and a global family automation kill
  switch.
- [ ] Auto-pause after repeated failures, conflicts, unusual source/write volume,
  or an expired policy review date.
- [ ] Keep delete, ACL/visibility changes, roles/invites, messages, and external
  actions permanently outside this tier.

### Dogfood gate

- [ ] Review an initial run set in shadow mode and an initial run set in staged
  mode; operator decides whether the observed precision justifies auto-upsert.
- [ ] Exercise source deletion/correction, duplicated emails, moved Drive docs,
  stale routine state, and two simultaneous provider runs.
- [ ] Measure operator review/maintenance time against the <2 hr/week constraint.

## Phase 6 — First scheduled provider adapter

**Exit:** one provider runs the K3 shadow/staged flow remotely on schedule with
connected sources. The other provider remains unbuilt.

### Spike selection

- [ ] Test Claude Cowork Scheduled task and Claude Code `/schedule` access to the
  needed Gmail/Drive/Calendar connector + remote gateway tool.
- [ ] Test ChatGPT Scheduled task and Codex Automation access to the same, noting
  that connected-source tasks may fit ChatGPT better than a repo-centric Codex
  cloud environment.
- [ ] Compare: connector result quality, scopes/approval persistence, remote tool
  auth, run history/retention, egress allowlists, admin controls, and cost.
- [ ] Choose one; record the vendor choice in ADR 0061 or a follow-up ADR.

### Adapter build

- [ ] Package durable instructions as the smallest supported skill/plugin.
- [ ] Prompt contains manifest reference and workflow only; no secrets or family
  data.
- [ ] Configure minimum network egress and tool permissions.
- [ ] Require a commercial/business workspace and record training/retention/admin
  settings used by the pilot.
- [ ] Test manually before scheduling; run remote shadow first.
- [ ] Document pause/revoke/provider-offboarding and task-history deletion.

## Phase 7 — E2EE integration

**Blocked on Accepted ADRs 0015 and 0017.** The earlier phases can dogfood against
M0 plaintext, honestly labeled.

- [ ] Implement content encryption/decrypt-once according to the accepted E2EE ADR,
  not this plan.
- [ ] Treat the routine gateway as an approved member/device-like key recipient.
- [ ] Bind routine public-key fingerprint to owner approval; detect key changes.
- [ ] Wrap `FCK` to the gateway key; never send raw `FCK` to the Dayfold server.
- [ ] Keep routing/version/ACL metadata clear only where the accepted ADR allows.
- [ ] Encrypt local gateway working storage or keep plaintext memory-only where
  practical; define crash-swap/core-dump handling.
- [ ] Verify revocation blocks future unwrap/sync but document that it cannot erase
  prior model-provider/session copies.
- [ ] Update privacy chips/copy to distinguish "Dayfold cannot read stored content"
  from "selected content was processed by Claude/OpenAI."

## Phase 8 — Optional direct K4 experiment

**Not authorized by this plan.** Start only after an explicit operator decision and
any required successor ADR/constitution update.

- [ ] Limit to operator household + separate flag/routine principal.
- [ ] Provider secret holds a dedicated routine key, never `HOUSEHOLD_SECRET`, human
  auth, or K1/K3 key.
- [ ] Expire and rotate frequently; resource grants are narrower than K3.
- [ ] No W3/member intents.
- [ ] Disclosure states that the provider is a durable key-holder and processes
  decrypted content; do not use the ordinary K1/K3 honesty copy.
- [ ] Red-team cloud VM/key exfiltration, prompt injection, logs/artifacts, cache
  resume, provider support access, and task-history retention.
- [ ] Compare reliability/maintenance gain against the privacy loss. Kill K4 if the
  benefit is marginal.

## Completion definition

The integration is complete for the first production-worthy tier only when:

- one scheduled provider adapter runs through K3;
- current Dayfold state and connected-source deltas are processed;
- changes are schema/ACL/policy/concurrency validated;
- writes are replay-safe and transactional;
- secrets/`FCK` stay out of the provider job;
- selected provider plaintext processing is disclosed;
- raw source/content is absent from logs/audits/task artifacts;
- routine revoke/expiry/kill switch work;
- adversarial security + simplification reviews pass;
- operator maintenance stays within the project constraint.
