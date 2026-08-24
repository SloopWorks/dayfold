# Timeline: jump to NOW affordance

**Date:** 2026-08-24

**Surface:** `TimelineDetail` (day schedule + whole-Hub roadmap)

**Scope:** `apps/ui` only; no presenter, schema, route, or persistence change

**Relates to:** ADR 0008/0009 (design-first + M3E), ADR 0045/0046 (timeline),
ADR 0051 (motion), and the existing open-at-NOW design in
`2026-07-16-roadmap-now-chronological-scroll-design.md`

## Outcome

When a member scrolls the timeline's NOW marker completely out of the visible
list viewport, show a compact extended floating button at the lower trailing
edge:

> ◎ **Now**

Tapping it scrolls the same list back to the NOW marker. The normal path uses
an animated scroll; reduced-motion uses an immediate scroll. The marker is
seated below the pinned group header using the same negative 46dp offset as the
existing open-at-NOW behavior.

The revised high-fidelity interaction lives in
`designs/hub-timeline/Timeline-Detail.dc.html`.

## Existing-screen audit

- The full-screen timeline already computes the NOW line's absolute lazy-list
  item index and opens at it without a visible top-to-NOW jump.
- The NOW line is correctly chronological and rendered as its own list item.
- After a member intentionally explores earlier or later stops, there is no way
  back except manually hunting for the coral marker.
- The timeline has no bottom navigation while open, so a lower-trailing floating
  control has a stable home. The list already owns the navigation-bar inset.
- Both day and Hub scale can have NOW; not-today, archived, all-done, or
  unparseable-clock states legitimately have no marker.

## Interaction and visual specification

### Visibility

Show the control only when all of these are true:

1. the presented timeline has a valid NOW-line item index;
2. the lazy list has completed its first layout; and
3. the NOW item has no overlap with the visible viewport.

Any visible pixel of the marker keeps the control hidden. This makes the rule
literal—"off screen"—and avoids a second arbitrary distance threshold. The
first-layout gate prevents a one-frame flash before the pre-seated list measures.

Re-evaluate from `LazyListState.layoutInfo`, so the control responds to touch,
mouse wheel, keyboard, accessibility scrolling, viewport resizing, and content
reflow without separate input listeners. Scale changes get a new list state and
re-seat at the new scale's NOW marker, matching existing behavior.

### Placement and styling

- M3 extended floating action treatment, horizontally compact: `MyLocation`
  crosshair + **Now**.
- Lower trailing edge, 18dp from the content edge and 18dp above the navigation
  inset.
- Minimum 48dp target; icon is decorative because the visible text labels the
  action.
- Primary-container/on-primary-container colors and standard floating elevation
  keep it legible over both plain rows and colored milestone cards in light and
  dark themes.
- Reserve 72dp of additional bottom list padding whenever the affordance is
  available, so the last stop/provenance can scroll fully clear of the overlay.

The button says **Now**, not **Today**: day scale returns to the current time,
while Hub scale returns to the current date/month band. A direction arrow is
deliberately omitted because NOW may be above or below and the control's outcome
does not change.

### Motion

- Tap: `animateScrollToItem(nowItemIndex, -headerPx)`.
- Reduced motion: `scrollToItem(nowItemIndex, -headerPx)`.
- Entrance/exit: short emphasized fade + vertical settle; no transition under
  reduced motion.
- The button remains present during the scroll and disappears as soon as the NOW
  item enters the viewport. The existing polite live-region semantics on the
  NOW marker provide arrival confirmation.

The existing automatic open-at-NOW scroll stays instant. That arrival path runs
inside a container transform and must be correct on frame zero; the new animated
path is explicitly initiated by the member after exploring the list.

### Accessibility

- Spoken label: **Jump to now**; role: button.
- Target meets ADR 0009's >=48dp rule.
- No color-only meaning: the control has icon + text, while the destination has
  its existing spoken current-time label.
- Screen-reader and switch users can invoke the same action; reduced-motion users
  are not forced through a long animated traversal.

## Review round 1 — correctness and mobile failure modes

**Verdict: proceed after the safeguards below.**

1. **Initial flash:** an empty `visibleItemsInfo` before first measure would read
   as "NOW absent" and flash the button over the enter morph. Gate on
   `layoutInfo.totalItemsCount > 0`.
2. **Sticky-header occlusion:** scrolling to offset zero can leave NOW under a
   pinned month label. Reuse the already device-verified **negative** 46dp seat.
3. **End-of-list occlusion:** a floating action can cover the final stop or the
   provenance card. Add bottom content padding while a NOW target exists.
4. **False availability:** never show the control when the presenter suppresses
   NOW (not-today, archived/all-done, invalid clock). The existing nullable item
   index is authoritative.
5. **Snapshot churn:** current snapshots intentionally disable automatic
   open-at-NOW. Couple the test-only disabled mode to affordance suppression so
   static top-of-list goldens remain stable; add a dedicated scrolled behavioral
   test and one intentional affordance snapshot instead.
6. **Rapid re-tap / user interruption:** Compose scroll mutation already
   serializes/cancels competing scrolls. No separate busy state or input lock is
   needed; a new user drag can take control.
7. **Cross-platform:** all primitives are Compose Multiplatform commonMain;
   avoid platform-specific scroll listeners or haptics.

## Review round 2 — optimization and simplification

**Verdict: ship the smallest state-free version.**

- Keep visibility derived from `LazyListState`; do not add Redux/UI model state,
  saved state, analytics, or a "last known side of NOW" field.
- Use the existing `nowLineItemIndex`; do not duplicate presenter time math or
  identify the marker by text/semantics.
- Prefer one extended pill over two directional variants. Arrows add state and
  visual noise without changing the action.
- Keep the control out of the top app header. A permanent header action consumes
  scarce chrome even while NOW is visible; conditional lower-trailing placement
  is more reachable one-handed and preserves the timeline's hierarchy.
- Do not auto-return after inactivity. Exploring history/future is intentional;
  only a direct tap moves the member.
- No new ADR: this is a reversible render-layer navigation affordance within the
  already accepted timeline and motion boundaries.

## Implementation plan

1. Add a visibility helper over `LazyListLayoutInfo` and unit-cover no-layout,
   partial overlap, fully above, and fully below cases.
2. Add the floating control and reduced-motion-aware scroll behavior to
   `TimelineDetail`.
3. Add Compose behavior coverage: hidden at NOW, appears after scrolling away,
   tap returns to NOW and hides it.
4. Capture and inspect dedicated light + dark affordance snapshots; keep the
   committed top-render golden scenes deterministic.
5. Run focused UI tests, the full `:ui:desktopTest`, semantics snapshot, macOS
   golden verification, Android compile/build, and iOS simulator framework link.
6. Record the user-visible change in `CHANGELOG.md` and `backlog/now.md`.
