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
| **P0 Viability & feasibility review** | Every 10 loop iterations or 30 days or at any gate — whichever first. Overdue review blocks all other loop work. Scores against the operator's confidence bar (values file) | `research/viability-review-YYYY-MM.md` (adversarial fleet; kill-switch register refresh; confidence-bar scorecard) | **Last: 2026-06-18 (validation round 1 = review #1). Next due: 2026-07-18 or +10 iterations.** Verdict: CONDITIONAL (learning-lab GO / business NO-GO) |
| Process self-improvement | Journal every iteration; meta-review every 15 iterations | `processes/loop-journal.md` + process-doc edits/ADRs | Seeded (Iteration 0 logged) |
| Board/reality reconciliation | Every close-out | This file accurate vs actual docs | — |

## Phase A — Critical info & direction → **Gate G1: "Informed & viable"**

**Two-track gate** (this venture's learning-lab primary goal splits it):

- **G1a — Dogfood build authorized (learning track).** Passes when: the
  content-API MVP architecture is specced and the wall-avoiding scope is
  confirmed buildable (adults-only, Calendar-only/no-Gmail-OAuth, plain
  deep-links). Validation round 1 found **no fatal flaw for the learning
  goal**, so G1a is close. **A8 (hi-fi mockups, ADR 0008) gates A3** — no
  build before the look-and-feel is mocked, committed to `designs/`, and
  operator-approved. The dogfood prototype is a learning artifact; it does
  NOT need WTP evidence.
- **G1b — Paid-business path authorized (income track).** Passes when: a
  flip-condition (ADR 0004) is evidenced OR a defensible niche is found
  (A1); the legal posture is operator-confirmed for any scope beyond
  adults-only/Calendar-only (A2); ≥~5 real family WTP conversations
  analyzed (A4); margin model accepted (A5); **AND the confidence case
  meets the operator's bar (A7).** Until then the business stays NO-GO and
  Phase B is gate-wait.

| Item | Deliverable + DoD | Depends on | Status |
|---|---|---|---|
| A1 Niche & differentiation brief (riskiest dimension = WTP/defensibility) | `research/niche-and-wedge-2026.md`: does a defensible niche exist (co-parenting/split-household, special-needs/IEP, eldercare) or does this stay a pure learning lab? Map each flip-condition (ADR 0004) to a cheap test. DoD: operator can decide pursue-niche / learning-lab-only from this doc | round 1 (done) | todo |
| A2 Legal posture confirmation (regulated edge) | COPPA + Google restricted-scope + LLM-data-handling memo for the **chosen scope**; agenda prepped from `research/.../compliance.md`. Desk-researchable: draft the memo + attorney agenda. **Operator-only:** retain a COPPA attorney *if/when* scope expands beyond adults-only/Calendar-only | compliance findings (done); operator | todo (desk part) / blocked(operator) |
| A3 Prototype build — operator-driven dumb renderer (gates G1a; scope = ADR 0007) | Exactly 5 parts, nothing more: (1) cloud DB + content API w/ idempotent upsert for cards + Hub/Section/Block; (2) Claude Code push skill/CLI; (3) two render surfaces (Now + Hubs); (4) in-app card→hub/block tap-through (internal nav, no Universal Links); (5) CMP native Android+iOS (no web) + single-household token auth. Deferred per ADR 0007: push, multi-member auth, permissions, integrations, Universal Links, widgets. DoD: operator using it daily on device | round 1 (done); ADR 0007; **A8 mockups (ADR 0008)** | blocked(A8) |
| A4 Field-validation kit + concierge pilot | Interview script + concierge-pilot design (hand-author briefings for ~5 friend families, no Gmail OAuth); two adversarial rounds; then analyzed conversations. DoD: WTP signal for G1b. **Operator-only:** running the conversations | A1 framing | todo |
| A5 Margin model | Per-family contribution incl. operator-hours, at $39-79/yr anchors; annual-first; store-commission sensitivity. Draft now from `pricing-structure.md` | round 1 (draft-ready) | todo |
| A6 Entity + product-name decision | Resolved. Working repo name = `family-ai-dashboard`; consumer product name TBD. Entity only needed at paid launch / commerce-API gate | A1 | todo |
| A7 Confidence case (gates G1b) | `research/confidence-case.md`: scorecard vs the MODERATE bar; claims tiered desk-proven/inference/only-field-provable; 2 adversarial rounds. DoD: operator can make go / field-test-first / no-go on the paid path | A1–A5 | todo |
| A8 Hi-fi UI/UX mockups (gates A3 + deep planning; ADR 0008) | Hi-fi mockups of **Now** (briefing cards) + **Hubs** (Event Hub dossier, incl. card→block deep-link highlight state) via Claude Code (`frontend-design` skill), committed to `designs/`; operator sign-off. **DoD: operator approves the look-and-feel.** This is the immediate next item — blocks A3 and any deep PRD/architecture work | round 1 (done); ADR 0008 | **todo (next)** |

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
| C1 PRD v0 | `specs/prd-v0.md` + 2 adversarial rounds. Covers **Now (briefing) + Event Hubs as co-equal surfaces** (feeds from `specs/event-hubs-design.md`, pending ADR 0006) | G2 | gate-wait |
| C1b Event Hubs spec + Hub JSON schema | Promote `specs/event-hubs-design.md` → binding spec; schemas in `specs/domain-model/schemas/`; 2 adversarial rounds | C1, ADR 0006 Accepted | gate-wait |
| C2 System design | `specs/architecture.md` (content API + Hub upsert + render layer) | C1 | gate-wait |
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
| E3 Niche channel/partnership scoping | Deferred until A1 names a niche (e.g. school / co-parenting / eldercare partner channels). Drop if the venture stays learning-lab-only | A1, A2 | gate-wait (deferred until niche) |
