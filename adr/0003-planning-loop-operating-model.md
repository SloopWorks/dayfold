# ADR 0003: Planning-Loop Operating Model — Autonomous Waterfall Deepening with Confidence-Gated Decisions

## Status

Proposed (template default) — accept at bootstrap once the operator
interview ratifies the constituent values (north star, kill-switch bundle,
confidence bar, engagement model), stamping the project date.

## Context

Deep planning across all business and technical domains must be executed
largely by agents, in waterfall order, while the operator supplies values
and direction and answers clarifying questions. Feasibility/viability is P0
and must be re-attacked periodically by adversarial agents. The process
must improve itself as it runs.

## Decision

1. **Operator/agent split:** the operator owns
   `context/values-and-direction.md` (agents propose changes via inbox
   only), gate decisions, kill/pivot decisions, and everything on the
   never-agent-decided list. Agents own execution depth.
2. **Waterfall board:** `planning/workstreams.md` defines phases A–E with
   explicit gates (G1 informed-&-viable → G2 strategy → G3 spec → G4
   build) and per-item definitions of done. Gates are operator decisions.
3. **The loop:** each iteration runs ORIENT → SELECT (strict priority, P0
   viability first) → EXECUTE → INTEGRATE → REVIEW → IMPROVE → CLOSE, per
   `processes/planning-loop.md`.
4. **Confidence protocol:** HIGH-confidence (≥2 independent primary
   sources / verified arithmetic / precedent, reversible, non-guardrail)
   decisions are made by agents and recorded inline for implicit
   ratification; MEDIUM → recommend + `[pending-ratify]` + proceed on
   non-dependent work; LOW/values-shaped → inbox question. Legal, pricing
   constants, scope, kill/pivot, spend, external actions are never
   agent-decided.
5. **P0 viability cadence:** adversarial review (strategist + red team
   minimum, fresh contexts) every 10 iterations / 30 days / any gate —
   whichever first; overdue review blocks all other loop work; scored
   against the operator's confidence bar; output is a dated research
   report + kill-switch register refresh.
6. **Kill switches:** measurable register in `context/kill-switches.md`;
   trips halt the loop and interrupt the operator immediately.
7. **Self-improvement:** mandatory journal entry per iteration with one
   improvement candidate; evidenced small tweaks applied directly (on
   second occurrence); structural changes via ADR; meta-review every 15
   iterations with measured metrics.
8. **Engagement:** per the values file (inbox + periodic digest by
   default); immediate interrupts for kill-switch trips, gates, compliance
   flags, kill verdicts.

## Rationale

Extends proven build-phase autonomy patterns (independent review, guardrail
escalation) to planning-phase documents. Confidence gating uses model
recommendations where confidence is high without ceding decisions that
shape values, money, or legal exposure. Waterfall gating prevents deep work
on foundations the operator hasn't ratified — two predecessor research
rounds once built on an unverified legal assumption that a later pass
refuted.

## Consequences

Positive: planning depth compounds without operator babysitting; every
decision traceable to a confidence level and ratification path; viability
cannot silently go stale.
Negative: per-iteration overhead (steps 5–7); a neglected inbox stalls
MEDIUM/LOW decisions; waterfall gates add latency vs opportunistic
ordering.

## Revisit Trigger

Meta-review evidence that gates or cadences are mis-tuned; transition to
build phase; operator moves to gate-only engagement.
