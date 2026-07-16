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

This **mirrors the proven arrival-scroll pattern** already used for hub deep-links
(`HubDetailScreen` → `focusedBlockItemIndex` + `LaunchedEffect{…animateScrollToItem}`,
`HubScreens.kt:366-377`; helper unit-tested in `HubArrivalIndexTest`). `nowLineItemIndex`
is the timeline analogue of `focusedBlockItemIndex`, living in `:ui` commonMain beside it.
**Deliberate divergence:** we pre-seat frame 0 and use `scrollToItem` (instant), *not*
`animateScrollToItem` — the deep-link case is user-initiated and benefits from the
animated reveal, but open-at-NOW fires on *every* open, so an animated scroll would fling
through all history each time.

```kotlin
// nowLineItemIndex(groups, nowIndex): absolute lazy-item index of the NOW line. Walk groups:
//   +1 per sticky header, +1 per stop; capture the running count when flat == nowIndex.
//   MUST also handle the trailing all-past case (nowIndex == totalFlat) after the loop,
//   mirroring the render's `now_line_end` item. Returns null when nowIndex is null.
val nowItemIndex = remember(presented) { nowLineItemIndex(presented.groups, presented.nowIndex) }
val headerPx = with(LocalDensity.current) { 46.dp.roundToPx() }

// ONE unconditional keyed remember (a conditional remember whose branch flips would fault
// Compose's slot table). null-checks live INSIDE, not around the remember call site.
val listState = remember(active) {
    LazyListState(firstVisibleItemIndex = if (autoScrollToNow) (nowItemIndex ?: 0) else 0)
}
LaunchedEffect(active) {
    if (autoScrollToNow && nowItemIndex != null) listState.scrollToItem(nowItemIndex, headerPx)
}
```

Mechanics and rationale:

- **Frame 0 already at NOW** (`LazyListState(firstVisibleItemIndex = nowItemIndex)`) —
  no top→NOW motion exists to be seen. The enter morph shows NOW-positioned content
  fading/sliding in.
- **Unconditional keyed remember.** A single `remember(active) { LazyListState(...) }` —
  `remember(active)` re-seats to NOW for the swapped content on Day↔Hub toggle (item
  positions differ per scale). The `autoScrollToNow` / null branch is *inside* the ctor
  arg, never around the remember, so the slot-table structure is stable across
  recomposition (avoids a "changing number of slots" fault when `nowItemIndex` resolves).
- **Sticky-header seat (`-headerPx`).** With offset 0 the pinned month header overlays
  the NOW line's top ~H px permanently. `scrollToItem(index, scrollOffset)` treats a
  **positive** offset as a *forward* scroll (the item moves UP, partially off the top),
  so a **negative** offset ≈ header height is what seats NOW *below* the header — it
  leaves H px of space above the item, which the pinned header fills (useful month
  context). **(Verified on-device: a positive offset hid NOW behind the header/title —
  corrected to negative.)** Because frame 0 is already at NOW, this nudge is ≤~46 px — a
  sub-perceptible settle masked by the HeroMs enter, **not** a jump. `initialScrollOffset`
  can't do this (it's non-negative), so the seat is a one-shot post-layout `scrollToItem`.
  `headerPx` is sized to the **taller** later-group header (top-pad 16, ~46.dp) so mid-list
  NOW never occludes; the group-0/all-future header (top-pad 0, ~30.dp) yields a ≤16 px
  cosmetic gap — invisible.
- **`LaunchedEffect(active)` keyed on scale only** (not `tl`): fires on open and on
  Day↔Hub toggle, never on incidental recomposition or a background sync (which would
  otherwise yank the scroll back mid-read).
- **Ephemeral by design.** Reopen resets to NOW — matches how the scale toggle already
  resets. No hoist / saveable needed for this full-screen substate.
- **Both scales.** `autoScrollToNow` defaults true for Day *and* Hub — "Today's schedule"
  also opens at NOW (skipping past-morning stops), consistent with the roadmap.
- **Reduced-motion honesty preserved.** `scrollToItem` is instantaneous, not an
  animation — nothing to gate; consistent with the NOW line's static-halo treatment.
  (`animateScrollToItem` would fling and fight the enter morph — rejected.)
- **Transition untouched.** Only list state + presenter change — no
  `SeekableTransitionState`, no transition spec, no shared-element keys → zero morph
  disruption.
- **Rail connector at NOW.** The NOW line is a full-width row between two stop rows,
  interrupting the vertical rail — this already happens in Day scale (ADR 0045); Hub now
  inherits the identical look. Consistent, not a regression.
- **Gap/future-month NOW.** When today has no stop, NOW seats under the next future
  month's header ("SEPTEMBER / NOW / Sep 5"). Pre-existing behavior, preserved.

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

- **Presenter (`:client`, pure) — `TimelinePresenterWindowTest`** (the only test that
  asserts Hub `nowIndex`; `HubArrivalIndexTest` is HubDetailScreen deep-link and is
  *unrelated*):
  - `presentTimelineDetail hub groups by month` (line 238): now=Aug24, stops
    Aug1/Aug15/Sep1. Flat index = `indexOfLast{≤now}+1 = 2` → NOW after the two past
    August stops, before Sep. **Assertion `0 → 2`** — this change *is* the bug fix.
  - `hub NOW band lands on next future month` (line 225): flat index = 1 (numerically
    unchanged, but update the comment to flat-index semantics so it isn't a misleading
    coincidence).
  - Add an explicit mid-month case: a current-month group with a past-in-month +
    future-in-month stop → NOW index lands between them.
  - `detail-day nowIndex …` (line 184): Day scale, unchanged (already flat).
- **`nowLineItemIndex` helper (`:ui`)** — unit-cover in `HubArrivalIndexTest` style
  (mirroring `focusedBlockItemIndex` tests): NOW at group-start, mid-group,
  all-past→trailing end, all-future, null passthrough.
- **Scroll behavior (`:ui`, `runComposeUiTest`)** — available (`ProximitySettingsTest`)
  and idles effects, so it's deterministic where a single-frame snapshot would race:
  compose `TimelineDetail(Hub, autoScrollToNow=true)`, `waitForIdle`, assert
  `onNodeWithText("NOW · …").assertIsDisplayed()` and the earliest past stop is not
  composed (scrolled off above). Closes the gap the `autoScrollToNow=false` snapshots leave.
- **Snapshots:** `TimelineDetail` gains `autoScrollToNow: Boolean = true`; snapshot
  scenes (`DerivedTimelineSnapshotTest`, `HubTimelineIntegrationSnapshotTest`,
  `TimelineDetailSnapshotTest`) pass `false` → deterministic top render (avoids the
  one-shot scroll racing the capture). They verify the **placement** change. Regenerate
  roadmap-detail (Hub) goldens on **both** macos + linux sets (cross-OS wrap drift means
  one set won't cover the other). Day-scale goldens are unchanged (same flat-index path;
  keys renamed only → no pixel change).
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
