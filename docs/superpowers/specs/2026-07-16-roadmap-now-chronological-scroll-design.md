# Roadmap: chronological NOW placement + open-at-NOW scroll

**Date:** 2026-07-16
**Surface:** `TimelineDetail` (full-screen Roadmap / timeline detail — Hub scale)
**Scope:** `apps/client` (`TimelinePresenter`), `apps/ui` (`TimelineDetail`)
**Relates to:** ADR 0045/0046 (hub timeline presenter + derived timeline)

## Problem

On the full-screen Roadmap (`TimelineDetail`, Hub scale), two defects:

1. **NOW placed at a month-group boundary, not inter-stop.** `presentTimelineDetail`
   (Hub branch) sets `nowIndex` to the *group index* of the first month on-or-after
   the current month, and `TimelineDetail` renders the NOW line right after that
   month's sticky header (`TimelineDetail.kt:116-121`). So inside the current month
   the NOW line floats *above* past-in-month stops. E.g. today = Jul 16: the "JULY"
   group renders `header → NOW → Jul 3 (done) → Jul 25` — NOW wrongly sits above the
   already-past Jul 3. The Day scale already does this correctly, using a *flat stop
   index* (`nowLineIndex` = last-past + 1) so the NOW line lands between the right two
   stops (`TimelineDetail.kt:126`).

2. **No initial scroll.** The `LazyColumn` uses a default state → opens at item 0
   (earliest past). The user must hunt downward for NOW.

## Goals

1. NOW placed in true chronological order, inline with content — even mid-month.
2. On navigate (no deep-link), the Roadmap opens with the NOW line at the top of the
   viewport; past content is above it, reachable by scrolling up.
3. Future content flows below NOW as today.

Non-goal: the "navigate directly to specific content/date" caveat — `TimelineDetail`
has no such entry point yet. When one is added it will pass an explicit target index
that overrides the open-at-NOW scroll.

## Design

### A. Chronological NOW placement (`:client` — `TimelinePresenter.presentTimelineDetail`, Hub branch)

Replace the group-index `nowIndex` with a **flat stop index**, mirroring the Day
branch, computed on the same `sorted` list that feeds the month groups:

```kotlin
val nowIndex = now?.let { n ->
    sorted.indexOfLast { it.instant != null && it.instant <= n } + 1
}   // null when the clock is unparseable
```

- `sorted = presented.sortedWith(compareBy(nullsLast()){ it.instant })` — the same
  ordering `buildMonthGroups` consumes, so the flat index matches render order
  exactly.
- Null-instant ("UPCOMING") stops sort last and are never counted as past → NOW lands
  before them.
- Gap-month case (stops in May + Sept, now July) → index of the first Sept stop → NOW
  renders just after the SEPT header, identical to today's behavior. No regression.
- `PresentedTimeline.nowIndex` keeps its `Int?` type; only its **meaning** changes for
  Hub (group index → flat stop index), unifying it with the Day branch's meaning.

### B. Unified NOW-line rendering (`:ui` — `TimelineDetail`)

Delete the Hub-specific group-header NOW branch (`TimelineDetail.kt:116-121`). The
existing Day flat-index insertion (NOW line before the stop at `flatIdx == nowIndex`)
plus the trailing end-check now serve **both** scales. Drop the `active == Day` guards;
rename keys to be scale-agnostic (`now_line_$fi` / `now_line_end`).

Result: the NOW line renders between the correct two stops for both scales — for Hub,
inside the current month between its past and future stops.

### C. Open-at-NOW scroll, jump-free (`:ui` — `TimelineDetail`)

`TimelineDetail` enters through a plain state-driven `AnimatedContent`
(`FeedApp.kt:434`, `fadeIn + slideInVertically`, inside the detail-stack container
transform; **`SeekableTransitionState` is deliberately avoided** — it drops the
sharedBounds morph). The list must therefore be correct on **frame 0** — a
post-composition scroll would play a visible top→NOW jump during the enter morph.

Pre-seat the list state so frame 0 is already at NOW:

```kotlin
// nowLineItemIndex: absolute lazy-item index of the NOW line (walk groups:
//   +1 per sticky header, +1 per stop; capture running count when flat == nowIndex).
val nowItemIndex = remember(presented) { nowLineItemIndex(presented.groups, presented.nowIndex) }
val headerPx = with(LocalDensity.current) { 44.dp.roundToPx() }

val listState =
    if (autoScrollToNow && nowItemIndex != null) remember(active) { LazyListState(nowItemIndex) }
    else rememberLazyListState()

LaunchedEffect(active) {
    if (autoScrollToNow && nowItemIndex != null) listState.scrollToItem(nowItemIndex, headerPx)
}
```

Mechanics and rationale:

- **Frame 0 already at NOW** (`LazyListState(firstVisibleItemIndex = nowItemIndex)`) —
  no top→NOW motion exists to be seen. The enter morph shows NOW-positioned content
  fading/sliding in.
- **Sticky-header seat (`+headerPx`).** With offset 0 the pinned month header overlays
  the NOW line's top ~H px permanently. `scrollToItem`'s post-condition is
  `item.offset == scrollOffset` (px from viewport start), so a **positive** offset ≈
  header height seats NOW below the header; the header pins in the freed gap (useful
  month context). Because frame 0 is already at NOW, this nudge is ≤~44 px — a
  sub-perceptible settle masked by the HeroMs enter, **not** a jump. `initialScrollOffset`
  can't do this (it only scrolls the first item *up*), so the seat is a one-shot
  post-layout `scrollToItem`.
- **`LaunchedEffect(active)` keyed on scale only** (not `tl`): fires on open and on
  Day↔Hub toggle, never on incidental recomposition or a background sync (which would
  otherwise yank the scroll back mid-read). `remember(active) { LazyListState(...) }`
  re-seats to NOW for the swapped content on toggle (item positions differ per scale).
- **Ephemeral by design.** Reopen resets to NOW — matches how the scale toggle already
  resets. No hoist / saveable needed for this full-screen substate.
- **Reduced-motion honesty preserved.** `scrollToItem` is instantaneous, not an
  animation — nothing to gate; consistent with the NOW line's static-halo treatment.
  (`animateScrollToItem` would fling and fight the enter morph — rejected.)
- **Transition untouched.** Only list state + presenter change — no
  `SeekableTransitionState`, no transition spec, no shared-element keys → zero morph
  disruption.

`headerPx` uses a ~44.dp constant (first-group header has top-pad 0, later headers
top-pad 16 → a few px variance, cosmetically invisible). An `onGloballyPositioned`
measurement is rejected as unnecessary async complexity.

### Behavior after

- Past above NOW (scroll up), NOW at viewport top on open, future below.
- NOW in its true chronological slot between last-past and first-future stop, even
  mid-month.
- All-past roadmap → NOW at end (opens at bottom). All-future → NOW at top, nothing
  above. `nowIndex == null` → opens at top, no NOW line. All correct.

## KMP platforms

Zero platform-specific code. `rememberLazyListState`, `LazyListState`, `scrollToItem`,
`LaunchedEffect`, `LocalDensity` are all Compose-MP **commonMain** → Android / iOS /
Web / Desktop identical, no `expect/actual`. Presenter change is `:client` commonMain
(pure Kotlin + kotlinx-datetime).

## Testing

- **Presenter (`:client`, pure):** update the Hub `nowIndex` expectations from group
  index to flat stop index — `TimelinePresenterWindowTest`, `HubArrivalIndexTest`,
  `DeriveTimelineTest`, `TimelineModelTest` (whichever assert Hub `nowIndex`). Add a
  case: a current-month group with a past-in-month + future-in-month stop → NOW index
  lands between them.
- **`nowLineItemIndex` helper (`:ui`):** unit-cover the group-walk (NOW at group start,
  mid-group, all-past→end, all-future→0, null passthrough).
- **Snapshots:** `TimelineDetail` gains `autoScrollToNow: Boolean = true`; snapshot
  scenes (`DerivedTimelineSnapshotTest`, `HubTimelineIntegrationSnapshotTest`,
  `TimelineDetailSnapshotTest`) pass `false` → deterministic top render (avoids a
  one-shot scroll racing the single-frame capture). They then verify the **placement**
  change. Regenerate roadmap-detail goldens on **both** macos + linux sets (cross-OS
  wrap drift means one set won't cover the other).
- **On-device:** operator-driven smoke on the Pixel — open a roadmap with past +
  present + future milestones; confirm it opens at NOW with past scrollable above and
  the NOW line seated below the pinned month header.

## Files

- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/TimelinePresenter.kt`
  — Hub `nowIndex` → flat stop index.
- `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/TimelineDetail.kt`
  — unified NOW-line render, `nowLineItemIndex`, pre-seated `LazyListState`,
  `autoScrollToNow` param.
- Tests + goldens per above.
