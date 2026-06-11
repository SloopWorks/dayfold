# {{PROJECT_NAME}}

{{ONE_PARAGRAPH_DESCRIPTION — what this venture is, for whom, in plain
language.}}

**It is not:** {{TOP_DRIFT_RISKS — the adjacent things this must not become}}.
See `context/business-constitution.md` — scope changes require an ADR.

## Current stage

**{{STAGE — e.g. "Planning loop, Phase A (validation)"}}.** Deep planning
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

1. {{DOMAIN_GUARDRAIL_1 — the venture's nearest legal/regulatory line}}
2. Pricing constants and billing mechanics.
3. {{DOMAIN_GUARDRAIL_2 — regulated/customer data handling posture}}
4. {{DOMAIN_GUARDRAIL_3 — customer-relationship line, e.g. cancellations}}
5. Sending messages outside the documented consent posture.
6. Spend above agreed thresholds; new legal entities; signing anything.
