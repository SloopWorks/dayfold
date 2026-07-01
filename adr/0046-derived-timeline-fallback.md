# ADR 0046: Client-Derived Timeline Fallback — a Second On-Device Projection over Hub Content

## Status

**Proposed** 2026-07-01 (agent-drafted at the ADR 0045 Phase-2 gate the parent ADR
reserved: *"a client-side derived fallback (`deriveTimeline` over a hub's existing
dated blocks) is deferred to Phase 2 and ADR-gated separately — it is the
ADR-0043-class second on-device projection over hub content and is not opened
here."* **Operator-gated** — it softens the dumb-client posture and re-touches the
Hub→Now boundary (product-scope-shaped, ADR-0043/0045-class). **Not accepted; do
not build until ratified.**

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

## Decision (proposed — options flagged for the operator)

Add a pure `deriveTimeline(hub, clock, tz): Timeline?` in `commonMain` (mirrors
`deriveNow` / `TimelinePresenter` — ephemeral, never synced, snapshot-testable). It
runs **only when a hub has no authored `Hub.timeline`**, projecting the hub's
already-synced dated blocks into synthetic stops, then renders through the **same**
`TimelinePresenter` + card + detail (zero new render code). **Authored always wins**
(a hub with an authored timeline never derives).

Open decisions the operator must settle before build:

1. **Source set — which dated content maps to a stop.** Proposed: checklist items
   with a `due` (title = item text, `done` = item done); `milestone` blocks
   (`date` + `label`); `location`/place pickups with a time; and the hub's
   `countdown_to` / `start_at` / `end_at` as anchor stops. Attachments derive from
   the block (a `location` → `nav`; a `link`/`document` → `link`/`open`). **Question:
   include all of these, or a narrower set for the first slice?**

2. **Now-feeding — stays render-only, or feeds the derived Now engine.** Proposed:
   **render-only, like ADR 0045 Phase 1** — a derived timeline stop does **not**
   feed `deriveNow` (ADR 0043) and fires **no** notification (ADR 0044). Feeding Now
   would re-open the notification-posture surface and risk double-surfacing the same
   dated block (once as a Now item, once as a timeline stop). **Recommend deferring
   any Now-feeding to a further ADR.**

3. **Provenance copy.** Proposed: a derived timeline shows **"Built from this hub"**
   (honest — it *is* derived), distinct from the authored **"Added to this hub"**.
   Non-negotiable per the ADR 0045 / ADR 0014-0015 honesty guardrail.

4. **Timezone.** A derived timeline has no author-stamped `tz`. Proposed: family-tz →
   device-tz fallback (same chain as ADR 0045, minus the authored layer). Accepts the
   cross-device disagreement risk the authored `tz` was designed to avoid — acceptable
   because a derived timeline is a convenience, not the authored source of truth.

5. **Dumb-client posture.** The load-bearing question: **is a second on-device
   projection acceptable?** ADR 0043 already established on-device derivation for Now;
   this extends the same precedent to timelines. It does **not** add a server
   projection or a write path. The operator decides whether the convenience (free
   timelines for dated hubs) is worth the further softening.

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

- **INB → operator decision** on the five points above (source set, Now-feeding,
  provenance copy, tz, dumb-client posture) **before** any build.
- A hi-fi design mock of the derived states (the "Built from this hub" provenance +
  the empty/sparse cases) is required (ADR 0008 Gate A) before deep build.
