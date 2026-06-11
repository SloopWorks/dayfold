# Planning Workstreams — Waterfall Board

The planning loop's work queue. Phases gate in waterfall order: a phase's
**gate** must pass (operator decision, recorded here + in an ADR where
durable) before dependent downstream work starts. Standing tracks run every
phase. The loop updates Status/Next columns at every close-out; definitions
of done (DoD) are the completeness bar for loop step 5.

Status values: `todo` / `active` / `blocked(reason)` / `gate-wait` / `done`.
Board hygiene: items tagged "operator" must state which sub-parts are
agent-desk-researchable vs truly operator-only.

## Standing tracks (never close)

| Track | Cadence | Deliverable | Status |
|---|---|---|---|
| **P0 Viability & feasibility review** | Every 10 loop iterations or 30 days or at any gate — whichever first. Overdue review blocks all other loop work. Scores against the operator's confidence bar (values file) | `research/viability-review-YYYY-MM.md` (adversarial fleet; kill-switch register refresh; confidence-bar scorecard) | Last: {{date}}. Next due: {{date}} |
| Process self-improvement | Journal every iteration; meta-review every 15 iterations | `processes/loop-journal.md` + process-doc edits/ADRs | Seeded |
| Board/reality reconciliation | Every close-out | This file accurate vs actual docs | — |

## Phase A — Critical info & direction → **Gate G1: "Informed & viable"**

G1 passes when: {{DOMAIN_BLOCKERS_RESOLVED — the legal/technical/market
questions the bootstrap validation surfaced}}; ≥{{N}} real customer
conversations analyzed; margin model accepted; kill-switch register armed
with real data; viability review post-data is ship/conditional; **AND the
confidence case meets the operator's bar (values file) — affirmative
evidence per pillar.**

| Item | Deliverable + DoD | Depends on | Status |
|---|---|---|---|
| A1 {{Riskiest-dimension brief — aim the first deep-dive at whatever kills the venture}} | `research/{{slug}}.md`; DoD: operator can decide {{fork}} from this doc | — | todo |
| A2 {{Expert/professional consult, if the domain has a legal/regulated edge}} | Counsel/expert opinion; agenda prepped by A1 | A1, operator | blocked(A1) |
| A3 {{Capability verification — the technical facts the product depends on}} | Capability matrix; desk pass first, residual operator checks listed | — | todo |
| A4 {{Field-validation kit + N real customer conversations}} | Script/template kit (two adversarial rounds) + analyzed conversations | A1 framing | todo |
| A5 Margin model | Per-customer contribution incl. operator-hours at actual target sizes | A3/A4 data (draft from estimates) | todo |
| A6 Entity + name decision | Resolved | A1 | todo |
| A7 Business-success confidence case | `research/confidence-case.md`: scorecard vs the values-file bar; every claim tiered desk-proven / inference / only-field-provable; 2 adversarial rounds. DoD: operator can make go / cheap-field-test-first / no-go from the doc | A1–A5 | todo |

## Phase B — Business strategy → **Gate G2: "Strategy accepted"**

G2 passes when: B1–B5 done with adversarial reviews, pricing ADR Accepted
(B6), and the operator accepts the strategy + GTM direction.

| Item | Deliverable + DoD | Depends on | Status |
|---|---|---|---|
| B1 Business strategy doc | `specs/business-strategy.md`: segment priorities, competitive posture, opportunism-clause signal definitions | G1 | gate-wait |
| B2 GTM plan | `specs/gtm-plan.md`: motion, sequencing, channels, partnerships | G1, B1 | gate-wait |
| B3 Customer-acquisition machine | `specs/customer-acquisition.md`: prospecting beyond the initial list, funnel math with measured rates | B2 | gate-wait |
| B4 Marketing plan | `specs/marketing-plan.md`: positioning, content, what-not-to-do | B1 | gate-wait |
| B5 Risk register consolidation | `specs/risk-register.md`: all rounds merged, owners, mitigations; links every kill switch | B1 | gate-wait |
| B6 Pricing acceptance | Pricing ADR → Accepted | A5 | gate-wait |

## Phase C — Product & systems → **Gate G3: "Spec accepted"** (incl. delivery review)

G3 passes when: C1–C4 accepted after two-round reviews AND the C5 delivery
review finds no unaddressed gaps (design-complete ≠ delivery-ready).

| Item | Deliverable + DoD | Depends on | Status |
|---|---|---|---|
| C1 PRD v0 | `specs/prd-v0.md` + 2 adversarial rounds | G2 | gate-wait |
| C2 System design | `specs/architecture.md` | C1 | gate-wait |
| C3 Infrastructure plan | `specs/infrastructure.md`: stack, environments, cost model, secrets, backup/DR | C2 | gate-wait |
| C4 Security & compliance model | `specs/security-model.md` + compliance operationalization + threat register | C1, A2 | gate-wait |
| C5 Delivery review | Design-complete ≠ delivery-ready audit: CI, test substrate, gates | C1–C4 | gate-wait |

## Phase D — Implementation planning → **Gate G4: "Build authorized"**

G4 passes when: D1–D3 done, kill-switch register re-checked, and the
operator explicitly authorizes build spend/hours.

| Item | Deliverable + DoD | Depends on | Status |
|---|---|---|---|
| D1 Roadmap milestones | `roadmap/milestones/` + dependency graph + unknowns register | G3 | gate-wait |
| D2 Tracking bootstrap | Issues/milestones/board mirroring roadmap | D1 | gate-wait |
| D3 Build-phase workflow | `processes/milestone-workflow.md` (8-phase, independent review, CI gates) | D1 | gate-wait |

## Phase E — Operations & growth design (drafts may start pre-G3; finalize post-G4)

| Item | Deliverable + DoD | Depends on | Status |
|---|---|---|---|
| E1 Operations runbooks | onboarding, support triage, breakage response, periodic business review | C2 | gate-wait |
| E2 Automations design | `specs/automations.md`: scheduled agents, monitoring/alerting, instrumentation | C2 | gate-wait |
| E3 {{Domain-specific ops partnership/edge items}} | — | A2 | gate-wait |
