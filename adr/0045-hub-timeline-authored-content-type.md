# ADR 0045: Hub Timeline — Authored Hub Property with On-Device Presentation

## Status

**Accepted** 2026-06-30 (operator ratified in-session — "0045 accepted"; Gate B
closed). Was **Proposed** 2026-06-30 (agent-drafted from the imported hi-fi mock
`designs/hub-timeline/` + a 6-agent review panel — 3 correctness/gaps/xplat,
3 completeness/UX-M3/data-systems; **operator-gated** — adds a new authored
content representation over hub content + a new on-device presentation
projection, which is product-scope-shaped and touches the content schema, so it
is ADR-0035/0006-class). Source-model, division-of-labor, placement, Phase-1
posture, and timezone authority were **operator-decided in-session 2026-06-30**.

**Gate A — ADR 0008 hi-fi sign-off — CLEARED** 2026-06-30: the imported mock
(`designs/hub-timeline/`) was revised (honest authored copy, one-card IA,
empty/all-done/not-today state mocks, citation fixes, token fidelity), pushed to
the claude.ai/design project, and **signed off by the operator**. **Both gates
closed → cleared for build.**

Statuses: Proposed | Accepted | Superseded | Deprecated.

## Context

A Hub holds a flat, unordered list of blocks (ADR 0006). Dated content — a
checklist due date, a milestone, a move-in day — has no **axis of time**: a
months-long project and a single frantic day read identically. The imported mock
(`designs/hub-timeline/`) proposes a **timeline** rendered at two scales (a day
rail with a live NOW line; a hub roadmap of month milestones), living as a card
in the dossier that taps open to a full view.

The mock asserted "visuals + motion only — no app code/schema/ADR changes." A
6-agent read-only review found that false and load-bearing:

1. Rendering a timeline requires **new on-device logic** the existing `deriveNow`
   engine (ADR 0043) does not implement — full past→future ordering, per-stop
   status, part-of-day/month grouping, NOW-line placement, windowing, and a
   roadmap-collapse rule. `deriveNow` is an imminence filter (drops past/done,
   window-bounds, collapses a checklist to one item, day-granular).
2. A timeline that needs information a hub's blocks don't already contain (the
   operator's "some hubs do not have content for everything") **cannot** be a
   pure client-side derivation — it needs authored content.
3. The mock mis-cited governance (claimed ADR 0008 for the write model; the write
   model is ADR 0038/0039 and the author/visibility posture ADR 0030) and claimed
   "never a notification," which ADR 0044 (local notifications, accepted
   2026-06-30) contradicts.
4. The data path the mock assumed does not exist: the block-type enum is
   **frozen-closed** (ADR 0035) at both validators; "one timeline per hub" is
   **unenforceable as a block** (blocks are keyed by ULID under a `section_id`,
   not a hub); a "hub timezone" field **does not exist**; and the `df://` deep-link
   scheme the mock invented has **no resolver and is not in the URI allowlist**.

This makes a hub timeline a new surface and ADR-class, not a render tweak.

## Decision

**Render a hub timeline from an authored, content-blind hub property, computing
all time-relative presentation on-device as a pure function.** Seam principle
(inherited from ADR 0043): *author the irreducible; derive the structural.*

1. **Authored-first source model (C, A-first).** A new authored hub property
   `Hub.timeline` is the source of truth, shipped first. A client-side *derived
   fallback* (`deriveTimeline` over a hub's existing dated blocks, for hubs
   without an authored timeline) is **deferred to Phase 2** and ADR-gated
   separately — it is the ADR-0043-class "second on-device projection over hub
   content" and is not opened here.

2. **Author writes stops; client computes presentation.** The author (Claude
   skill / CLI / content API) writes the irreducible **stops**. The **client**
   computes status / NOW line / day-vs-roadmap scale / grouping / windowing /
   collapse locally from stops + clock + injected tz. The **server stores +
   structurally validates only** (`tz` present, `stops` non-empty, each stop has a
   date, attachment `kind` ∈ enum) and never reads stop prose — the dumb-server /
   content-blind invariant (ADR 0039 §7, ADR 0015/0017) is preserved.

3. **A hub property, not a block.** `Hub.timeline` is a hub-level property
   (sibling to `places`), synced with the hub row. One-per-hub is enforced for
   free by the hub primary key; it renders as a **hoisted card** in the dossier;
   old clients ignore an unknown hub property cleanly (no unknown-block-type
   placeholder, which a block path would have required as an ADR-0039
   prerequisite, and no `focusedBlockItemIndex` disturbance).

4. **Phase 1 is render-only and Now-invisible.** A Phase-1 timeline stop does
   **not** feed the Now derived engine and fires **no** notification. Provenance
   copy reflects **authored** origin — no "derived on-device / built from this
   hub" claim until the Phase-2 derive path actually ships (honesty guardrail,
   ADR 0014/0015; CLAUDE.md). A stop notifies only if it *independently* surfaces
   as a Now item under ADR 0044 — which, in Phase 1, it cannot.

5. **Author-stamped timezone.** The timeline carries its own IANA `tz` string
   (content-blind; travels with the stops); the client falls back family-tz →
   device-tz. This protects the multi-member family-tenant wedge — members in
   different zones see the same NOW line / done-set for the same authored timeline
   (a device-tz-only model silently disagrees cross-device).

6. **Stops carry typed attachments, no new URI scheme.** OS-handoff attachments
   (`call`→tel, `nav`→geo, `link`→https) reuse `cardActionUri`/`vettedOpenUri`;
   in-app jumps are a typed `open` kind dispatched through the store
   (`CardAction.OpenHub`), never a `df://` URI. Stop times reuse the schema's
   RFC-3339 convention (date-only = all-day) — no `date`+`HH:mm` split.

7. **Member model.** The timeline is author-authored and is a **projection, not a
   worklist** — stops are not member-checkable (unlike checklist `done`,
   ADR 0038). The card supports per-member **"Hide for me"** (local-only, ADR 0039
   §W5) for consistency with every other dossier element. Whole-timeline
   visibility inherits the hub's ADR-0030 visibility.

8. **Platforms: Android + iOS + Desktop.** Web is out of scope (no `wasmJs`
   target exists in the repo). All load-bearing constructs (the shared-element
   container transform, the segmented scope toggle, the reduce-motion
   expect/actual, the brand fonts) already ship in `commonMain` for these three.

Full design, schema, presentation pipeline, states, change-set, and review audit
trail: `specs/hub-timeline-design.md`.

## Consequences

**Positive.** Most Dayfold-native answer — the timeline is intelligence authored
elsewhere and rendered by a dumb, content-blind client (the MVP wedge). Handles
sparse hubs (the author can supply info the blocks lack). One-per-hub for free.
No client derivation in Phase 1 → does not soften the dumb-client posture now.
Reuses existing infra (container transform, segmented toggle, card-action handoff,
fonts) — the build is additive. Author-stamped tz keeps the multi-member wedge
coherent.

**Costs / risks.** Adds a hub-level property → touches `content.schema.json`, the
codegen, both validators, the hub sync DTO, and the hand-written client `Model.kt`
+ a new non-block render path. A new `TimelinePresenter` with a larger test matrix
than `deriveNow` (scale selection, windowing, grouping, NOW-line). The imported
mock requires a revision pass before ADR-0008 sign-off (honest copy, one-card IA,
state mocks). Phase-2 derived fallback + any Now-feeding is explicitly **not**
decided here and reopens the ADR-0043 dumb-client question on its own ADR.

**Rejected alternatives.** (B) Pure client-derived timeline — cannot supply info
absent from blocks, and is the ADR-0043-class second projection. (A-only) authored
with no future derived fallback — forgoes free timelines for dated hubs. Timeline
as a **block** — one-per-hub unenforceable, no section home, unknown-block
forward-compat hazard. `date`+`HH:mm` split + a `df://` scheme + a "hub timezone"
field — three representations the schema/infra do not support.

## Composition

Composes ADR 0043 (Now content model / on-device pure-function precedent),
0035/0006 (content schema + block-type set), 0030 (per-member visibility +
author-trusted posture), 0038/0039 (two-way write model + "Hide for me"),
0014/0015/0017 (location privacy + content-blind/E2EE), 0044 (notification
posture — untouched in Phase 1), 0004 §4 (subjects-only / COPPA), 0022 (typed
renderers + container-transform fold), 0008 (design-first gate).

## Open

- **INB →** ratify this ADR (Gate B) + sign off the revised `designs/hub-timeline/`
  mock (Gate A) before build.
- Phase-2 derived fallback (`deriveTimeline` + any Now-feeding) = a separate
  Proposed ADR.
