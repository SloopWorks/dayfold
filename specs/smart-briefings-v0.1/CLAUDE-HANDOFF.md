# Claude handoff — V0.1 Claude Bridge operator pilot

This is the single entrypoint for a fresh Claude Code session. The objective is a
gated operator pilot, not the paid hosted release. Begin with a synthetic provider
compatibility spike; do not process private Gmail/Dayfold data until the explicit
authority gates are satisfied.

## Read in this order

Read every selected file completely before acting:

1. `CLAUDE.md`
2. `context/values-and-direction.md`
3. `context/business-constitution.md`
4. `context/goals-and-constraints.md`
5. `context/kill-switches.md`
6. `backlog/now.md`
7. `backlog/operator-inbox.md`
8. `adr/decisions-index.md`
9. `adr/0071-self-managed-claude-bridge-v0.1.md`
10. `specs/smart-briefings-v0.1/system-design.md`
11. `research/2026-08-20-smart-briefings-v0.1-adversarial-review.md`
12. `docs/superpowers/plans/2026-08-20-smart-briefings-v0.1-claude-bridge.md`
13. `designs/PROMPT-smart-briefings-v0.1-claude-bridge.md`
14. Proposed ADRs 0061 and 0062 for superseded/conflicting context
15. `docs/superpowers/plans/2026-08-07-routine-integration-safe-slice.md`
16. `processes/agent-dev-loop.md` before API/KMP/client work

Existing implementation references:

- `packages/routine-schema/`
- `specs/domain-model/schemas/routine-*.schema.json`
- `apps/cli/src/main/kotlin/RoutineContract.kt`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/routines/`
- `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/routines/`
- `designs/routine-integration/`

## Non-negotiable product boundary

- Operator pilot, Claude-only, manual only; not commercial V0.1.
- Claude owns inference and Google OAuth. Dayfold receives neither Google nor
  Claude subscription credentials.
- One installation/family, one source owner/Owner, one Hub.
- One run creates zero or one private `create_card` proposal.
- Connector tools: context, proposal validate, proposal stage, finish only.
- Separate connector token issuer/audience/verifier/refresh store and isolated MCP
  service. Never generic tokens, scopes, routes, or repositories.
- Proposal accepts/rejects only; no Edit. Card ID, Hub, provenance, visibility,
  audience, and apply authority are server/human-owned.
- Human acceptance always inserts a restricted card with source owner mandatory
  and optional exact active-adult recipients. Never generic upsert.
- No schedule, auto-publish, update/delete, source write, attachment/link access,
  Calendar/Drive, ChatGPT/BYOK, K3/K4, or E2EE claim.
- Gmail outcomes are reported by Claude, not independently verified by Dayfold.
- All testing stays synthetic until an eligible no-training authority and Gmail
  mutation gate exist.

## Current gate table

| Work | Allowed now | Operator prerequisite | Completion evidence |
|---|---|---|---|
| Read/reconcile packet | yes | none | inconsistencies reported, no repeated planning review |
| Build local synthetic spike | yes | none; no external account/data | local tests and runbook |
| Deploy/run spike in Claude | no | explicit account, preview deployment, Terms/spend approval | dated compatibility report |
| Generate final hi-fi | after spike report | provider facts reconciled | ADR 0008 sign-off |
| Implement schemas/server/client with synthetic data | no | ADR 0071 + constants/hosting accepted | WP tests and security review |
| Use private operator data | no | eligible no-training authority + Gmail write gate | recorded authority and passing canaries |
| Non-operator release | no | legal/privacy/deletion/commercial release decision | separate accepted release packet |

## Required sequence and stop points

### Phase A — prepare the synthetic spike

1. Confirm branch/worktree and read the authoritative packet.
2. Do not repeat the recorded two-round reviews unless architecture changes.
3. Implement only Work Package 0's local synthetic server/tests and operator
   runbook.
4. Stop before deployment, Claude account action, connector installation, Terms,
   or spend.

### Phase B — operator compatibility test

After explicit authorization, the operator performs external Claude/deployment
actions. Record actual OAuth/MCP/client/Gmail-write behavior in
`research/2026-08-20-smart-briefings-v0.1-compatibility-spike.md`. Stop on any
plan no-go; do not code around it.

### Phase C — final design/ADR gate

1. Reconcile only recorded provider facts into ADR/spec/plan.
2. Run the full Claude Design prompt.
3. Complete visual, copy, privacy, audience, browser, and accessibility reviews.
4. Stop for ADR 0008 sign-off and ADR 0071/constants decisions.

### Phase D — synthetic implementation

Execute Work Packages 1–9 in order with tests first and authority-changing commits
isolated. Stop before private-data use for a fresh security review and the explicit
no-training/Gmail-write authority.

### Phase E — eligible dogfood

Run canaries, export/delete/kill-switch/rollback proof, then ten authorized manual
runs. Apply the pre-ratified pass/kill thresholds. Do not infer permission for a
second family or commercial release.

## Immediate stop conditions

Stop if:

- provider evidence/ADR/hi-fi/operator gate is missing;
- Gmail and Dayfold connectors cannot coexist;
- Gmail mutation can occur without unavoidable human confirmation;
- OAuth requires weak redirect/PKCE/resource binding or a Claude credential;
- connector/app access or refresh tokens cross protocols;
- generic Hub/content routes, grants, middleware, diagnostics, or upsert are reused;
- a non-recipient can receive an accepted card;
- model input affects identity, Hub, audience, visibility, provenance, or apply;
- source/proposal/OAuth content reaches diagnostics;
- a consumer toggle is treated as sufficient no-training authority;
- another family's data, production deployment, account creation, public
  publication, Terms acceptance, or spend is required without approval.

## Prompt to start Claude

```text
Prepare and execute the gated V0.1 Claude Bridge operator-pilot plan from
specs/smart-briefings-v0.1/CLAUDE-HANDOFF.md.

Follow CLAUDE.md. Read the listed packet completely, confirm the current gate
table, then begin only Phase A: build and test the local synthetic compatibility
spike and its operator runbook. Do not repeat already-recorded adversarial reviews,
deploy anything, use private data, configure an external account/connector, accept
terms, or spend money. Stop at the first explicit gate and report the exact
operator action/evidence required.
```

## Completion standard

Claude must report exact evidence, files, test counts, operator actions, private-
data authority (or synthetic-only status), dogfood pass/kill result if authorized,
and remaining blockers. “Build passes,” mockup screenshots, OAuth approval, or a
finish receipt alone do not prove the pilot works or that Gmail was read.
