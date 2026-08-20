# Local Search — Adversarial Design Review

**Date:** 2026-08-19
**Scope:** Fresh-context Claude Code reviews of
`designs/local-search/` against `designs/DESIGN-BRIEF-local-search.md` and
`research/2026-08-19-local-search-exploration.md`. Both passes were read-only;
the corrections were applied separately and validated afterward.

## Pass 1 — correctness and completeness

**Verdict:** `NEEDS CHANGES`

The review found five substantive and four minor issues:

1. A semantic-only board claimed no shared words while one row contained the
   exact query token `game`.
2. The one-shot arrival ring remained visible indefinitely and looked identical
   to the keyboard cursor ring.
3. The offline state showed a different result corpus despite claiming parity.
4. The one-character, below-threshold state was not reviewable.
5. Long-query tail behavior, excerpt clamping, title wrapping, and a deeper
   breadcrumb needed a concrete stress case.
6. Filter-chip targets, adaptive keymap copy, caret visibility, and the
   prototype's CDN disclosure needed small corrections.

All were corrected. The reviewer also verified that the gallery contains no
product API, persistence, telemetry, database, embedding, or app-code work; no
permission leak; no score/model/assistant language; honest fuzzy marks; adequate
mark contrast; and the required light/dark, compact/adaptive, offline,
empty-cache, archived, and no-result states.

## Pass 2 — simplification

**Verdict:** `SIMPLIFY`

The review found that the interaction model was good but the gallery repeated
itself. The applied reductions were:

- replaced the duplicated 26-frame state wall with a four-frame, side-by-side
  density comparison; every required state remains in the light/dark switcher;
- made the plain mixed list the clear default and limited filter chips to the
  explicit comparison;
- removed the redundant filtered state, numbered state pointers, decorative
  badges, dead `Offline copy` fixture, repeated engineering tokens, and a
  duplicate arrival explanation;
- shortened state, decision, and semantic-board copy;
- kept the section result/arrival, fuzzy heading plus per-row label, archived
  label, non-color match underline, trust/offline slot, long-content stress
  fixture, reduced-motion arrival cue, and all edge states.

## Validation

All five `.dc.html` components evaluate through the shared design runtime after
the fixes, and every file has one balanced `<x-dc>` root. No implementation or
architecture decision was made; ADR 0008 operator sign-off still gates product
planning.
