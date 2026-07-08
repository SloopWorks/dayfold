# Card detail — de-duplicate facts (DETAILS canonical)

**Date:** 2026-07-08
**Status:** Design (approved)
**Related:** CL-6 detail (ADR 0022)

## Problem

The card detail screen states the same facts twice: the **hero `InfoPanel`**
(`DetailScreen.HeroMedia`) is a label-less restatement of the **DETAILS** list
(`detailMeta`). Clearest on an invite — date + place appear in the hero *and* as
When/Where below. This is visual noise. (Reported on the Morton-Finney invite.)

## Goal

DETAILS is the single, labeled source of facts. Remove the hero fact-restatement.
Keep the hero for genuinely non-factual affordances; lose no information.

## Design

### HeroMedia (`DetailScreen.kt`)
Drop the `InfoPanel` from every branch. The hero renders **only affordances**:
- `geo` → `MapStrip()`
- `invite` → `RsvpAffordance(...)`
- `contact` → `ContactReachRow(...)`
- `file` / `link` / `email` → nothing (hero collapses; `HeroMedia` emits no item)

The affordances that were nested under the InfoPanel (`RsvpAffordance`,
`ContactReachRow`) become the hero content directly. `InfoPanel` becomes unused →
delete it.

### detailMeta (`DetailMeta.kt`) — fold hero-only fields into labeled rows
Add rows so nothing the hero showed is lost (only-when-present, like the others):
- **invite** → `+ MetaRow("Host", host)` (after Where)
- **geo** → `+ MetaRow("Place", label)` (first, before Address — the venue name vs the street)
- **email** → `+ MetaRow("Preview", bodyExcerpt)` (after Subject)
- **link** → `+ MetaRow("Title", title)` + `+ MetaRow("About", ogDesc)` (after Site)
- **file** → `+ MetaRow("Type", mime)` (after Size)
- **contact** → `+ MetaRow("Company", company)` + `+ MetaRow("Role", role)` (before Phone)

Row order chosen so the most identifying fact leads each type's block.

### Result
`title → [affordance: map / RSVP / Call·Text] → actions → body_md → DETAILS (one
labeled block, incl. Note) → hub link → related → provenance`. Every fact appears
once, labeled.

## Testing
- **Unit (`DetailMetaTest`):** assert the folded rows appear for each type (Host,
  Place, Preview, Title/About, Type, Company/Role) and are omitted when the field is
  null.
- **Snapshot:** `detail-*` goldens (file/link/invite/contact/geo/email ± dark) shift —
  re-record macOS + linux, keep only `detail-*` shots (revert docker AA noise on
  unrelated shots).
- **On-device (Pixel):** the Morton-Finney invite states When/Where once (in DETAILS);
  no top restatement card.

## Rollout
No ADR (render-layer simplification). CHANGELOG (client). No data/model change.
