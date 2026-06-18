# family-ai-dashboard

A calm, AI-powered household dashboard. One account per family, members log
in (adults at MVP). It reads the family's existing signals — calendar,
email, lists/tasks, weather, location — and renders a single sleek daily
**briefing** plus a short list of **smart recommended actions** with deep
links ("party Saturday — ordered groceries? [list]"; "school email needs an
RSVP Thursday [reply]"; "rain at soccer 4pm — pack jackets"). Built mobile-
first on Compose Multiplatform (Android/iOS/Web). The MVP wedge is a
**content API + CLI + Claude skill** so external AI loops and scheduled
tasks author/update the cards — the dashboard renders intelligence produced
elsewhere; it is not an open-ended chatbot. Primary purpose is a
**learning lab**; durable side income is a co-goal. Dogfooded on the
operator's own household first.

**It is not:** a family chat/social app; a chore/allowance/gamified kids
app; a calendar/email/list *replacement*; an open-ended AI chatbot; a
venture-scale startup. See `context/business-constitution.md` — scope
changes require an ADR.

## Current stage

**Bootstrapped 2026-06-18; entering planning loop, Phase A (validation
follow-through).** Validation round 1 verdict: **CONDITIONAL — learning-lab
GO, standalone-business NO-GO** (the AI-briefing concept is commoditized by
Gemini Daily Brief / Alexa+ / funded verticals; build to learn, not to bet
on revenue). The one defensible wedge found: a **multi-member family-tenant
briefing**, which no native OS ships. Deep planning
runs as an autonomous loop over the waterfall board
(`planning/workstreams.md`) per `processes/planning-loop.md` (ADR 0003):
the operator sets values/direction and answers the inbox; agents deepen
strategy, GTM, risk, specs, system design, ops, automations, marketing,
acquisition, and infrastructure — gated, adversarially reviewed, P0
viability re-attacked on cadence. Build starts only after the spec gates.
Check `backlog/now.md` and `planning/workstreams.md` for the live front.

**If asked to "run a loop iteration":** follow `processes/planning-loop.md`
end to end, including the journal entry and inbox/digest close-out.
**If asked to "bootstrap this project":** follow `BOOTSTRAP.md`.

## Directory map

| Path | Holds | Authority |
|---|---|---|
| `CLAUDE.md` | This file — session protocol, governance | Source of truth |
| `adr/` | Decision records + `decisions-index.md` | Source of truth (immutable once Accepted) |
| `context/` | Values & direction (operator-owned), constitution, goals/constraints, kill switches, open questions, operating lessons | Source of truth |
| `planning/` | Waterfall workstream board the loop executes | Live working state |
| `research/` | Research reports with citations; validation reviews | Evidence (dated snapshots, never silently edited) |
| `roadmap/` | Execution plan, milestone definitions (post-spec) | Source of truth for execution |
| `specs/` | PRD, architecture, pricing model (post-validation) | Source of truth |
| `processes/` | Planning loop, agent routing, research workflow, fleet patterns, loop journal | Source of truth for process |
| `backlog/` | `now.md` / `next.md` / `later.md` / `operator-inbox.md` | Working state |

## Required start-of-session routine

1. Load context in this order:
   1. `CLAUDE.md`
   2. `context/values-and-direction.md` (operator-owned — agents never edit)
   3. `context/business-constitution.md`
   4. `context/goals-and-constraints.md` + `context/kill-switches.md`
   5. `backlog/now.md` + `backlog/operator-inbox.md` (apply operator answers first)
   6. `planning/workstreams.md` (if doing loop/planning work)
   7. Relevant ADRs (`adr/decisions-index.md` first)
   8. Relevant research / specs for the task at hand
   9. Persistent memory system (if available)
2. Do not begin substantive work until constraints are loaded.

## Required end-of-session routine

1. Summarize work completed.
2. Store working memory (if a memory system is available).
3. Promote durable learnings into repo files; unresolved items into
   `context/open-questions.md`.
4. Create or update ADRs when a durable decision was made.
5. Update `backlog/now.md` / `next.md` / `later.md`.

## Process rules

- **Adversarial review by default.** Plans, specs, and research syntheses
  get two rounds of adversarial review (round 1 correctness, round 2
  optimization/simplification) before acceptance. Research claims require
  citations labeled `[fact:source]` / `[estimate]` / `[assumption]`.
- **Confidence protocol** (ADR 0003; canonical table:
  `processes/planning-loop.md` §3): HIGH → agent decides + records;
  MEDIUM → `[pending-ratify]` + inbox; LOW/values-shaped → ask. **Never
  agent-decided: legal, pricing constants, scope, kill/pivot, spend,
  external actions.**
- **Routing.** Match the task class to the right agent/process via
  `processes/agent-routing.md` before starting multi-step work.
- **ADR-class decisions** (anything touching product scope, pricing, legal
  or compliance posture, platform/vendor choices, customer-data handling,
  automation-autonomy boundaries, or maintenance burden) must be written as
  a Proposed ADR and accepted by the operator before they take effect.
  Accepted ADRs are immutable — supersede, don't edit.
- **Design-first (ADR 0008).** No deep planning or build of any surface
  before a hi-fi UI/UX mockup of it exists in `designs/` (authored with
  Claude Code + `frontend-design`) and the operator has signed off.
- **External actions** (emails, calls, sign-ups, payments, anything a
  customer, prospect, or vendor can see) are operator-gated. Agents draft;
  the operator sends.
- **Git.** Branch from latest `main` for any non-trivial change; never work
  on `main` once build starts. Commits/PRs written normally.

## Memory governance

Repo Markdown is the reviewed source of truth. Any connected memory system
is working memory: session continuity, retrieval, agent-workflow
experimentation.

Priority when sources conflict:

1. Current operator instruction
2. `CLAUDE.md`
3. ADRs in `adr/`
4. Source-of-truth docs in `context/`, `specs/`, `roadmap/`, `processes/`
5. Research reports in `research/` (dated evidence, may be stale)
6. Retrieved memory
7. Agent/session notes

If memory conflicts with repo Markdown, trust the repo Markdown. Do not let
memory drift silently change business direction — promote durable changes
through an ADR.

## Hard guardrails (escalate, never decide alone)

1. **Children's data (COPPA + state child-privacy / App-Store-Accountability
   laws).** No collection of personal info from under-13s without verifiable
   parental consent. MVP is **adults-only accounts** — children appear only
   as subjects in the parents' own data, never as account holders — to stay
   clear of this line. Adding child accounts is an ADR-gated decision that
   re-opens the full COPPA burden.
2. Pricing constants and billing mechanics.
3. **Restricted-scope data + LLM data handling.** Reading Gmail uses Google
   *restricted* scopes → mandatory recurring CASA security assessment
   (~$540–1,500/yr) the moment another family's Gmail is read server-side.
   MVP avoids it: Calendar read is only *sensitive* (no CASA), and the
   content-API/forward path means no direct Gmail OAuth at MVP. Routing any
   family email/calendar content through third-party LLMs requires explicit
   disclosure. Changing this posture is ADR-gated.
4. **Customer-relationship line.** Never become the family's system of
   record; never spam; honor data export + delete on request. No dark-
   pattern retention or cancellation friction.
5. Sending messages outside the documented consent posture.
6. Spend above agreed thresholds; new legal entities; signing anything.
