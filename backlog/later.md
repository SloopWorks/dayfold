# Backlog — Later

Parked. Pull forward only through `now.md`.

## CL-SNAP follow-up: pixel ↔ composable inspector (Layout-Inspector-style overlay)

Make a rendered snapshot a **queryable, addressable surface** — map an image
pixel (x,y) to the composable(s) that drew it, and the reverse (name → bbox),
with a browser highlight overlay. Depends on `redux-kotlin-snapshot` (operator-
owned reduxkotlin) growing the geometry the current dump lacks.

**Context / gap (probed 2026-07-02):** alpha04's `--semantics` dump emits the
semantic *tree* + `role`/`text`/`desc` but **no bounds/coordinates**, so
pixel↔node mapping isn't possible from what's emitted today. The geometry
exists on Compose `SemanticsNode.boundsInRoot: Rect` — it's just not serialized.

**Level 1 — semantic inspector (small lift; the clean increment on the dump):**
- reduxkotlin: add `bounds:[x,y,w,h]` per node to the semantics dump (JSON);
  add an `--unmerged` flag (else a `Button` merges its children → you hit the
  button, not the inner Text/Icon).
- dayfold: an HTML **canvas overlay viewer** — the golden PNG on a `<canvas>`,
  hover/click draws the containing node's rect + a role/text tooltip
  (DOM-inspector UX). Plus agent helpers: `whatIsAt(x,y)` → smallest node whose
  rect contains the point; `bboxOf(text|testTag)` → rect (for cropping).
- Blind spot: purely-decorative composables (background Box, spacer) emit no
  semantics node → invisible at this level.

**Level 2 — full composable + source location (bigger; = Android Studio Layout
Inspector):** read the **LayoutNode** tree (every node, incl. decorative) off
the `ImageComposeScene` root + the Compose compiler source-info markers
(file:line / function) → pixel → deepest composable → its source. Uses
experimental/internal Compose APIs; proven pattern (how Layout Inspector works
headlessly). A real reduxkotlin feature, not a patch.

**Why it matters for the agent loop (the real payoff):** with bounds as *text*,
both directions cost **zero vision tokens** — forward "what's at (x,y)?" →
`button "Share"`; inverse "where does the RSVP toggle render?" → bbox → **crop
that ~200×80 region** and read only it instead of the whole ~822×1782 PNG. The
snapshot stops being an image you read and becomes something you query.

**Caveats to design around:** `mergeDescendants` (dump the unmerged tree);
Level-2 source-info needs the Compose compiler flag left on (debug keeps it).

Design/plan lineage: `specs/cl-snap-agent-snapshot-loop-design.md`,
`docs/superpowers/plans/2026-07-02-cl-snap-agent-snapshot-loop.md`, PR #277.
