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


## TASK-WEATHER — weather-conditional content (PARKED 2026-07-31)

**Parked at the ADR 0008 design gate.** The design pass ran and the operator did
not sign off, so nothing may be built. Resuming means a fresh design round first,
then the weather ADR, then the B1–B3 slices.

Everything needed to pick it up is written down:

- **Spec** — `docs/superpowers/specs/2026-07-31-background-refresh-and-weather-design.md`
  Part B. Content-places-only forecasts (ADR 0014's live-position promise stays
  intact), a closed condition vocabulary, `show_when` as an AND-gate distinct from
  `triggers[]`'s OR-boosters, fail-open on a missing forecast, and a flagged
  aggregate card.
- **Design + rationale** — `designs/weather/` and its `RATIONALE.md`. Not approved,
  but its four resolved questions (icon precedence, aggregate collapse shape, mixed
  provenance, hidden-card silence) are reasoned and worth reading before redesigning.
- **Design brief** — `designs/DESIGN-BRIEF-weather-conditional.md`, reusable for the
  next round.

**Three things that must not be lost when this resumes:**

1. **Condition-neutral copy is a blocking gate on B2.** The "never render
   unverified weather" rule governs the app's rendering, not the curator's prose —
   a card titled "Rain at soccer 4pm" still asserts weather on a fail-open dry day,
   and no client-side rule can retract authored text. Copy must be true on a dry
   day; the chip only adds evidence.
2. **`CLIENT_SCHEMA_VERSION` must go 3 → 4 when `show_when` lands.** It is a
   behavior-affecting decoded field on synced content, so `ignoreUnknownKeys`
   dropped it on already-cached rows and the cursor cannot backfill. Without the
   bump every cached card gates on `NULL` and — because the gate fails open — they
   all silently keep showing. Same class as the heal bug the A1 whole-branch review
   caught.
3. **Weather glyph names must stay disjoint from the ADR 0036 `media.icon` enum**
   and be server-rejected there, or a curator can author a weather glyph as a
   card's identity icon and eventually contradict live conditions.

Open questions carried forward: vendor (Open-Meteo's free tier bars commercial use,
so it has a licensing cliff at monetization; NWS is unrestricted but US-only), and
that WeatherKit attribution would appear on *every* card showing a weather chip,
not just the aggregate — a recurring calm cost to price into that decision.
