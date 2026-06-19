# ADR 0022: Typed Content Library, Detail View & the Fold Gesture

## Status

**Accepted — 2026-06-19 (operator).** Authored from the Claude Design import
"Family AI dashboard design brief" (`designs/content/*`, `designs/Brand.dc.html`).
ADR-class (product scope + M0 schema/data-model + brand). **Operator decisions
(INB-15/16/17/18):** accepted; **D2 = Option B (extend `briefing_cards` in
place); unify `content_item` deferred to M1**; **D5 name = "Dayfold" confirmed**;
the phone mockups are **signed off** (ADR 0008 gate cleared for phone surfaces;
adaptive two-pane queued for a Claude-Design pass); **M0 ships all 6 content
types** (operator widened the recommended 2-type slice). Immutable from here —
supersede, don't edit.

> **Citation correction.** The imported designs label the content library
> "ADR 0015". Repo ADR 0015 is **End-to-End Encryption**. No content-library
> ADR existed; this record (0022) fills that gap. The design's "ADR 0015"
> references should be read as "ADR 0022".

## Context

M0 ships a **feed-only** renderer: `briefing_cards` carry `kind`
(`action|info|weather|countdown`), `body_md`, and a deep-link `target`. Hub
content lives in a **separate** `blocks` table with a typed `payload`
(`link|checklist|document|milestone|contact|location|budget`). There is **no
detail view, no navigation, no transition** in the client (feed is tap-inert);
the M3 theme is library default (no brand theme).

The design import introduces three things M0 does not have:

1. **A typed content library.** A content item is **data + a type**; the type
   selects three render layouts — a **Now card** (glanceable), a **Hub block**
   (row in a dossier), and a **Detail** (full screen). Six types are designed:
   **file/document, link/form, invitation/RSVP, contact/vendor, geo/map,
   email**. "New use cases — tickets, receipts, boarding passes — slot in by
   adding a type, not a screen." Detail anatomy is constant across types:
   **hero → metadata → actions → provenance+privacy → related**.
2. **The fold gesture.** Tap a card → it **expands into its detail** via a
   Material 3 Expressive **container transform** (the card morphs into the
   screen; ~460ms open / ~420ms back, emphasized-decelerate). The brand
   metaphor *is* this motion: content is folded away until it matters, then
   unfolds.
3. **The "Dayfold" brand.** Name + mark ("turned corner"), voice ("One calm
   view of family life"), and a full M3 token set (warm coral primary `#C0381E`,
   teal secondary, violet tertiary; Outfit/Figtree; 26dp card radius; warm-
   tinted elevation) — light **and** dark.

## Decision (proposed)

### D1 — Adopt the typed content library as the M0+ content model

A content item carries a **`type`** and a **type-specific `payload`**, and is
renderable as Now-card / Hub-block / Detail. Six launch types
(file, link, invite, contact, geo, email); the set is open (add a type, not a
screen). Each type declares: icon + accent role, kicker, primary `actions[]`,
the field list it shows, its **trigger** (time and/or geo) and **when-shown**
rule, and its **provenance source**.

### D2 — Storage model (the load-bearing fork — operator picks)

- **Option A (recommended): unify.** One `content_item` table
  (`type`, `payload jsonb`, `body_md`, `provenance`, `triggers`, `version`,
  routing/timestamps cleartext). Now-feed membership and Hub membership become
  **edges/views** over it. Rationale: matches the design's "one item, three
  renderings"; M0 data is throwaway dogfood (ADR 0015 note) so the migration is
  cheap now and **avoids a painful re-model later**; keeps the E2EE cleartext/
  ciphertext column split (ADR 0015/0017) defined in **one** place.
- **Option B: extend in place.** Keep `briefing_cards` + `blocks` separate; add
  `type` + `payload` + `detail` to `briefing_cards`. Smaller diff; perpetuates
  two type systems and two payload schemas to keep in lockstep.

**DECIDED (operator, INB-15): Option B — extend `briefing_cards` in place for
M0; unify into `content_item` deferred to M1** (you migrate for E2EE anyway).
Rationale: M0 data is throwaway, `briefing_cards` works, and unify's payoff (one
cleartext/ciphertext boundary) only lands with E2EE at M1. Either
way, `payload` per-type `$defs` must be **fully generated** via a JSON-schema
`discriminator` → zod `discriminatedUnion` + Kotlin `@JsonClassDiscriminator`
sealed interface (TS + Kotlin) — today payload is a codegen catch-all (`z.any()`),
the deferred "payload/$defs" gap noted across the backlog. **Ciphertext-candidate
fields carry a machine-readable `x-e2e` annotation** so M1 E2EE drops in cleanly.

### D3 — Detail view + the fold gesture are M0+ surfaces

Add a **DetailScreen** (constant skeleton, per-type hero) and the **container-
transform** card→detail transition (`SharedTransitionLayout` +
`AnimatedContent`, corner morph 26→0dp, content fade-in after grow, scrim,
asymmetric faster back). **Predictive-back** is desired but needs a Compose-MP
upgrade (1.9.3 → ≥1.10) — treated as a scoped sub-task/risk, not a blocker for
the base transition.

### D4 — Provenance + **privacy** become first-class fields

Detail shows a **privacy chip** ("Stored on your device", "Location never
leaves", "Opened in your browser", "Matched on your device"). This is the
honesty posture of the constitution + ADR 0014 (private trigger engine) made
visible. Store **source channel** and **storage/processing location** per item.
**Honesty constraint (review):** a chip claim is only allowed when an actual
schema/API/client boundary enforces it — "Location never leaves" holds for
*live position* only (place coords are family-visible content); M0-plaintext
"Stored on your device" means *a cached copy* (the server also holds it).
Actions are a **closed, typed union** (no freeform URLs); OG metadata is
author-stamped, never server- or client-fetched (no SSRF / timing oracle).

### D5 — Adopt the Dayfold M3 theme

Replace library-default theming with the brand token set (light+dark color
scheme, Outfit/Figtree type, 26dp/999 shape, warm elevation, expressive motion
scheme). **The brand name "Dayfold" is confirmed** (operator, INB-17; "your day,
folded into one place"); the repo slug stays `family-ai-dashboard`.

### D6 — Design-first gate (ADR 0008)

These are **new surfaces**. The imported `designs/content/*` mockups are
**signed off** (operator, INB-16) — the ADR 0008 gate is **cleared for the
phone surfaces**, so CL-0…CL-7 may build. The **adaptive (tablet/foldable/
desktop) two-pane detail remains a design GAP** — phone-only is designed — so
**CL-10 stays blocked behind a queued Claude-Design pass** for the expanded
detail + fold-at-width behavior.

## Consequences

**Positive:** an extensible content model (types, not screens); the signature
fold interaction; privacy made visible; a coherent brand; a clean place to land
the E2EE column split. **Negative / cost:** a real schema migration (Option A),
full payload codegen, new navigation + detail + transition client work, a
Compose-MP upgrade for predictive-back, and a map-render strategy for the geo
type (currently a CSS placeholder; real cross-platform maps are non-trivial —
see epic). **Open / operator-gated:** D2 fork; the Dayfold **name**. **Guardrail 3 (resolved
as a binding constraint, not an open question):** the `email` type stores body
excerpts, but only from content the **authoring loop (CLI / Claude) composes over
the operator's OWN data** — **never a server-side Gmail *restricted*-scope OAuth
read.** That is what keeps M0 clear of the CASA assessment. Any change to that
posture (e.g. reading another family's Gmail server-side) is a **new ADR**, not a
build tweak. G-SCOPE in the epic operationalizes this in CL-1/CL-3.

## Revisit Trigger

Adding a 7th+ content type that needs a new *surface* (not just a layout);
any type that ingests Gmail *restricted*-scope content server-side (re-opens
CASA, Guardrail 3); operator rejection of the unify fork.
