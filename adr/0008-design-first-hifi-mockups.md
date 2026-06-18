# ADR 0008: Design-First — Hi-Fi UI/UX Mockups Precede Deep Planning and Build

## Status

**Accepted** 2026-06-18 (operator instruction, in-session). Immutable —
supersede, do not edit. Process rule; applies to all surfaces.

## Context

The product's entire premise is a *sleek, calm* UI (constitution identity).
The operator mandated that before any in-depth planning or build work, the
UI/UX must be mocked up in **high fidelity** using Claude Code, with the
designs committed to this repo. Building or deep-speccing before the look-
and-feel is resolved risks expensive rework and a product that fails the
"calm/sleek" bar it's premised on.

## Decision

1. **Hi-fi mockups are a hard precondition.** Before deep planning (PRD/
   architecture detail) or any build of a surface, that surface must have a
   committed **high-fidelity** UI/UX mockup. Low-fi wireframes do not satisfy
   this gate.
2. **Authored with Claude Code**, using the `frontend-design` skill for
   production-grade, non-generic visuals.
3. **Committed to `designs/`** in this repo (per-surface subfolders;
   rendered artifacts — HTML/CSS prototypes and/or exported images — that the
   operator can actually view).
4. **Scope for the prototype (ADR 0007):** mock the two surfaces first —
   **Now** (briefing cards) and **Hubs** (Event Hub dossier incl. the
   card→block deep-link target/highlight state). These gate board item A3.
5. **Operator sign-off on the designs** is required before the gated work
   proceeds (operator-gated, like other gates).

## Rationale

Cheap to iterate pixels, expensive to iterate built code. For a calm/sleek-
premised product the visual bar IS the product; resolving it first de-risks
both the build and the PRD. Claude Code + `frontend-design` makes hi-fi
mockups fast and in-repo, so the gate costs little.

**Rejected:** "design as we build" — rejected by operator mandate and by the
rework risk. "Low-fi wireframes suffice" — rejected; the differentiator is
fidelity/calm, not just layout.

## Consequences

Positive: the look-and-feel is settled and reviewable before code; PRD/specs
can reference real screens; reduces build rework.
Negative: adds a design pass before A3 build; mockups must be kept roughly in
sync as the design evolves (treat as living, not contract — code is truth
once built).

## Revisit Trigger

A new surface is added (mock it first); or the mockup-maintenance burden
outweighs its value (loosen to "mock new/changed surfaces only").
