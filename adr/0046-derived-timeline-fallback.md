# ADR 0046: Client-Derived Timeline Fallback — a Second On-Device Projection over Hub Content

## Status

**Accepted** 2026-07-01 (operator ratified in-session — "Accept + build"; **both gates
closed**: Gate A = `designs/derived-timeline/` hi-fi mock imported + signed off, Gate B =
this ADR accepted). The five open decisions are resolved to the mock's recommended
answers (see Decision §1–§5). Was **Proposed** 2026-07-01 (agent-drafted at the ADR 0045
Phase-2 gate the parent reserved; **operator-gated** — it softens the dumb-client posture
and re-touches the Hub→Now boundary, ADR-0043/0045-class).

Statuses: Proposed | Accepted | Superseded | Deprecated.

## Context

ADR 0045 shipped `Hub.timeline` as an **authored** hub property: an author writes
the stops, the client lays them out. A hub with **no** authored timeline shows **no**
timeline card — even when its blocks already carry dates (a checklist item `due`, a
`milestone` block `date`, a `location` pickup, the hub's own `countdown_to` /
`start_at` / `end_at`). Those hubs could get a timeline **for free** if the client
projected their existing dated content into stops.

This is exactly the **second on-device projection over hub content** ADR 0045
deferred. It is ADR-0043-class: `deriveNow` (ADR 0043) is the *first* such
projection (hub content → ephemeral Now items); `deriveTimeline` would be the
*second* (hub content → an ephemeral timeline). Each one the client synthesizes is
another place the "dumb client renders intelligence authored elsewhere" posture
(ADR 0015/0043) is softened — the client is now *deriving* structure, not just
laying out authored structure. That is the crux and the reason this is its own
operator-gated ADR rather than a Phase-2 detail.

Constraints inherited: content-blind server (no schema change — this is pure client,
like `deriveNow`); the ADR 0045 **honesty guardrail** (provenance copy must reflect
*actual* origin — the moment a timeline is derived, "Added to this hub" is a lie and
must become "Built from this hub"); no `Hub.timeline.tz` exists for a derived
timeline (the authored one carries its own `tz`; a derived one must fall back
family→device tz).

## Decision (ratified 2026-07-01)

Add a pure `deriveTimeline(hub, clock, tz): Timeline?` in `commonMain` (mirrors
`deriveNow` / `TimelinePresenter` — ephemeral, never synced, snapshot-testable). It
runs **only when a hub has no authored `Hub.timeline`**, projecting the hub's
already-synced dated blocks into synthetic stops, then renders through the **same**
`TimelinePresenter` + card + detail (zero new render code). **Authored always wins**
(a hub with an authored timeline never derives). The **second on-device projection**
over hub content (after `deriveNow`) is accepted — it adds no server projection and
no write path (the dumb-server invariant holds).

1. **Source set — all four dated sources.** Checklist items with a `due`
   (title = item text, `done` = item done); `milestone` blocks (`payload.date` +
   label); **location pickups** (a location/timed block via its `triggers[].when.at`);
   and the hub's own `countdown_to` / `start_at` / `end_at` as anchor stops.

2. **Now-feeding — render-only.** A derived stop does **not** feed `deriveNow`
   (ADR 0043) and fires **no** notification (ADR 0044) — no bell, reminder chip, or
   "notify me" anywhere. Any future Now-feeding is a separate ADR (avoids re-opening
   the notification surface + double-surfacing the same dated block).

3. **Provenance copy — "From this hub's dates".** A neutral outline chip (no fill,
   no accent hue, plain `event` glyph — never a sparkle, never "AI"), visually
   unmistakable from the authored purple "Added to this hub". The detail footnote
   states plainly it's laid out from existing dates and doesn't notify. (Honesty
   guardrail, ADR 0014/0015; four alternates on the signed-off `Provenance` board.)

4. **Timezone — family → device fallback** (a derived timeline has no author-stamped
   `tz`). Accepts the cross-device-disagreement risk the authored `tz` avoids —
   acceptable because a derived timeline is a convenience, not the source of truth.

5. **Per-stop source tag — "label" depth.** Each derived stop shows a quiet ghost
   tag (icon + one word: `checklist` / `milestone` / `pickup` / `hub date`), distinct
   from the filled attachment chips; minimal (icon-only) / verbose (phrase) are
   available. When a block carries **>1 date, the most-specific stated time wins** (an
   instant beats a bare date; matches the ADR 0045 authored rule) — never inferred
   from location or traffic. **Thin content:** ≥2 dated stops render; **1 or 0** → a
   gentle "No timeline yet" nudge (fall back to showing nothing if it reads as clutter).

## Consequences

**Positive.** Every dated hub gains a timeline with zero authoring effort. No schema
change, no server change, no new write path (pure client, like `deriveNow`). Reuses
the entire ADR 0045 render pipeline. Re-enables a "surfaces through Now" story if
decision (2) is later revisited.

**Costs / risks.** A second on-device projection further softens the dumb-client
posture (the crux — operator's call). Mapping heterogeneous blocks to stops is a
larger test matrix than the authored path. Provenance honesty must flip correctly or
it violates the guardrail. Potential double-surfacing if a derived stop later feeds
Now (mitigated by decision 2 = render-only). tz fallback re-introduces the
cross-device disagreement the authored `tz` avoided.

**Rejected alternatives.** (A) Never derive — forgoes free timelines for dated hubs
(the status quo after ADR 0045). (B) Server-side derivation — violates the
content-blind / dumb-server invariant. (C) Author-required-always — is ADR 0045 as
shipped; this ADR exists only to decide whether to add the fallback.

## Composition

Composes ADR 0045 (authored timeline + presenter it reuses), 0043 (first on-device
projection / dumb-client precedent it extends), 0006/0035 (block content it reads),
0014/0015 (honesty + content-blind), 0044 (notification posture — untouched if
decision 2 = render-only), 0030 (visibility), 0022 (typed renderers).

## Open

- **Gate A (ADR 0008 hi-fi mock) — CLEARED** 2026-07-01: `designs/derived-timeline/`
  authored in Claude Design + imported (Index, Provenance, Sparse, Tap-To-Detail,
  the derived card + detail). It reuses the signed-off hub-timeline card/detail
  verbatim; the new pieces are the honest provenance chip, per-stop source tags, the
  sparse/"not enough" states, and the render-only posture. The mock **resolves the
  open decisions** with these recommended answers (operator design-stage guidance,
  to be **ratified together with Gate B**):
  1. **Source set** — all four: checklist due-dates, milestones, location pickups,
     the hub's own countdown/start/end.
  2. **Now-feeding** — **render-only** (no `deriveNow`, no notification; no bell /
     reminder / "notify me" anywhere).
  3. **Provenance copy** — **"From this hub's dates"** (neutral outline chip, `event`
     glyph — no fill, no accent, no "AI"); detail footnote states it plainly.
  4. **tz** — family → device fallback (a derived timeline has no author `tz`).
  5. **Per-stop source depth** — **"label"** (icon + one word); minimal/verbose are
     live-toggleable. Each stop names its source block, distinct from attachment chips.
  - **New rule surfaced by the mock:** when a block carries >1 date, the **most-
    specific stated time wins** (an instant beats a bare date), matching the ADR 0045
    authored rule — never inferred from location/traffic.
- **Gate B — operator ratification (accept the second on-device projection) —
  CLEARED** 2026-07-01 ("Accept + build"). Both gates closed → **cleared for build**.
