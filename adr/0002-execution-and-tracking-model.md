# ADR 0002: Execution & Tracking Model — Phase-Gated, Agent-Operated, Operator-Escalated

## Status

Proposed (template default) — verify and accept at bootstrap, stamping the project date.

## Context

The project will run for years across many agent sessions, spanning
research, business operations, administration, and (later) software
delivery. Human attention must concentrate where only the operator can
decide.

## Decision

1. **Lifecycle phases:** Research → Validation → Specification → Roadmap →
   Build → Operate, with explicit exit gates
   (`context/goals-and-constraints.md`). Build does not start before spec
   acceptance.
2. **Work routing** per `processes/agent-routing.md`: research fleets with
   citation + adversarial-verification requirements; strategist reviews;
   specs/plans via two-round adversarial review; build (later) via an
   8-phase milestone workflow with independent fresh-context review and
   mechanical CI gates.
3. **Tracking:** repo Markdown is authoritative (ADR 0001). Pre-build, the
   planning-loop board `planning/workstreams.md` is the live tracker, with
   `backlog/now.md` as the operator-facing summary. At build start, the
   issue tracker mirrors `roadmap/`.
4. **Guardrail escalations** (work stops, operator decides): ADR-class
   decisions; legal/compliance questions; spend beyond thresholds; any
   external-facing action; blocked unknowns past a time-box.
5. **Session protocol:** the CLAUDE.md start/end-of-session routines are
   mandatory; durable outcomes promoted to Markdown at close-out.

## Rationale

Maximizes autonomous throughput while concentrating human attention on
operator-only decisions. The validation-first ordering reflects the
predecessor lesson: validate the riskiest dimension before building.

## Consequences

Positive: any fresh agent can orient from the repo alone; long-term
continuity independent of session memory.
Negative: process overhead on small tasks (routing doc states when to
skip); reconciliation discipline required at every close-out.

## Revisit Trigger

Build-phase start; or process overhead measurably exceeding its
defect-catch value (meta-review evidence).
