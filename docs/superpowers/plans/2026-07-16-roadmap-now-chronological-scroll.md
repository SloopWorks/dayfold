# Roadmap chronological NOW + open-at-NOW scroll — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On the full-screen Roadmap (`TimelineDetail`), place the NOW line in true chronological order (inter-stop, even mid-month) and open the screen scrolled to NOW with past content reachable by scrolling up.

**Architecture:** Two changes. (1) `:client` presenter: the Hub `nowIndex` becomes a flat inter-stop index (like Day already is), so the NOW line lands between the correct two stops. (2) `:ui` `TimelineDetail`: unify Hub+Day NOW-line rendering, pre-seat the `LazyListState` at the NOW line so frame 0 is already at NOW (no jump under the enter morph), and a one-shot `scrollToItem(+headerPx)` seats NOW below the pinned month sticky-header.

**Tech Stack:** Kotlin Multiplatform, Compose-MP (commonMain, no expect/actual), kotlinx-datetime. Tests: kotlin.test + `runComposeUiTest`. Golden snapshots via `org.reduxkotlin.snapshot`.

## Global Constraints

- JDK 17 for Gradle: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
- Build/test from `apps/` (single Gradle root). `:client` = Compose-free core; `:ui` = Compose layer.
- All new code is commonMain — no platform-specific code, no `expect/actual`.
- `nowIndex` semantics for BOTH scales = a flat stop index into the chronological, sorted stop stream (index of the stop the NOW line renders *before*; `== total stop count` means after all stops). Unparseable clock → null.
- NOW-line item keys are scale-agnostic: `now_line_$fi` / `now_line_end`.
- Golden snapshots have PER-OS sets (`snapshots/macos` + `snapshots/linux`); re-record on both after an intentional visual change and eyeball before committing.

---

### Task 1: Presenter — Hub `nowIndex` becomes a flat inter-stop index

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/TimelinePresenter.kt:368-388` (Hub branch of `presentTimelineDetail`)
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelinePresenterWindowTest.kt`

**Interfaces:**
- Consumes: `presentTimelineDetail(tl, scale, nowIso, tz): PresentedTimeline` (existing), `PresentedTimeline.nowIndex: Int?` (existing field; meaning changes for Hub).
- Produces: Hub `nowIndex` = `sorted.indexOfLast { it.instant != null && it.instant <= now } + 1`, matching the Day branch's flat-index meaning.

- [ ] **Step 1: Update the two existing Hub `nowIndex` assertions + add a mid-month case**

In `TimelinePresenterWindowTest.kt`, change the test at ~line 217 (`hub NOW band lands on the next future month …`) — the assertion value stays `1` but update the comment:

```kotlin
        val d = presentTimelineDetail(tl, TimelineScale.Hub, "2026-07-10T10:00:00-04:00", ny)
        assertEquals(listOf("JUNE", "AUGUST"), d.groups.map { it.label })
        assertEquals(1, d.nowIndex)   // flat index: after jun (past), before aug — July has no stop
```

Change the test at ~line 228 (`presentTimelineDetail hub groups by month`) — assertion `0 → 2`:

```kotlin
        val result = presentTimelineDetail(tl, TimelineScale.Hub, "2026-08-24T10:00:00-04:00", ny)
        assertEquals(TimelineScale.Hub, result.scale)
        assertEquals(listOf("AUGUST", "SEPTEMBER"), result.groups.map { it.label })
        assertEquals(2, result.nowIndex) // flat index: after the two past Aug stops (Aug1, Aug15), before Sep
```

Add this new test just below it (before the closing `}` of the class):

```kotlin
    @Test fun `hub NOW lands after past-in-month stops, before future-in-month`() {
        // The current month (JULY) holds a past stop (Jul 3) and a future stop (Jul 25); now = Jul 16.
        // The NOW line must land BETWEEN them, not above the whole JULY group.
        val tl = Timeline(tz = "America/New_York", stops = listOf(
            Stop("2026-07-03", "past jul", done = true),
            Stop("2026-07-25", "future jul"),
        ))
        val d = presentTimelineDetail(tl, TimelineScale.Hub, "2026-07-16T10:00:00-04:00", ny)
        assertEquals(listOf("JULY"), d.groups.map { it.label })
        assertEquals(1, d.nowIndex)   // flat index 1: after Jul 3 (past), before Jul 25 (future)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest --tests "*TimelinePresenterWindowTest"`
Expected: FAIL — `presentTimelineDetail hub groups by month` expected `2` but was `0`; the new mid-month test expected `1` but was `0` (current group-index logic returns the group index).

- [ ] **Step 3: Replace the Hub `nowIndex` computation with a flat stop index**

In `TimelinePresenter.kt`, in the `TimelineScale.Hub ->` branch, replace this block:

```kotlin
            // NOW band sits above the first month group that is on-or-after the current month —
            // the current-month group when it exists, else the next future month (so a roadmap that
            // skips the current month still shows a NOW line). Null when every month is already past.
            val nowKey = now?.toLocalDateTime(tz)?.let { it.year * 12 + it.month.ordinal }
            val nowIndex = nowKey?.let {
                groups.indexOfFirst { g ->
                    val k = g.stops.firstOrNull()?.instant?.toLocalDateTime(tz)?.let { d -> d.year * 12 + d.month.ordinal }
                    k != null && k >= nowKey
                }.takeIf { idx -> idx >= 0 }
            }
```

with:

```kotlin
            // NOW line is placed inter-stop, chronologically: a flat index into the sorted stop
            // stream = the first stop strictly after now. Within the current month it therefore
            // lands AFTER past-in-month stops (not above them). null when the clock is unparseable.
            // This unifies the field's meaning with the Day branch (both = flat stop index).
            val nowIndex = now?.let { n ->
                sorted.indexOfLast { it.instant != null && it.instant <= n } + 1
            }
```

(The `todayYear`/`nowKey` locals: `todayYear` is still used by `buildMonthGroups` — keep it. `nowKey` is now unused — delete its line.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest --tests "*TimelinePresenterWindowTest"`
Expected: PASS (all Hub + Day nowIndex cases green).

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/TimelinePresenter.kt \
        apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelinePresenterWindowTest.kt
git commit -m "fix(client): roadmap NOW line placed inter-stop (flat index), not at month boundary"
```

---

### Task 2: `nowLineItemIndex` helper + unit tests (`:ui`)

**Files:**
- Modify: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/TimelineDetail.kt` (add helper near the bottom, beside the other private helpers)
- Test: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelineNowLineIndexTest.kt` (create)

**Interfaces:**
- Consumes: `TimelineGroup(label, stops)` and `PresentedStop` (from `:client`, same package, already in scope), `StopStatus`, `Stop`.
- Produces: `internal fun nowLineItemIndex(groups: List<TimelineGroup>, nowIndex: Int?): Int?` — the absolute LazyColumn item index of the NOW line.

- [ ] **Step 1: Write the failing test**

Create `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelineNowLineIndexTest.kt`:

```kotlin
package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelineNowLineIndexTest {
    // The helper reads only group/stop counts (not instants), so dummy stops suffice.
    private fun ps() = PresentedStop(Stop(at = "2026-01-01", title = "x"), StopStatus.Upcoming, null)
    private fun grp(vararg n: Int) = n.map { count -> TimelineGroup("g", List(count) { ps() }) }

    // groups [A:2 stops, B:1 stop] → items: A-hdr(0) a0(1) a1(2) B-hdr(3) b0(4) [end]
    private val groups = grp(2, 1)

    @Test fun nowAtFirstStop() {
        // nowIndex 0 → NOW before a0, i.e. right after A's header at item 1
        assertEquals(1, nowLineItemIndex(groups, 0))
    }

    @Test fun nowMidFirstGroup() {
        // nowIndex 1 → NOW before a1 at item 2
        assertEquals(2, nowLineItemIndex(groups, 1))
    }

    @Test fun nowAtSecondGroupStart() {
        // nowIndex 2 → NOW before b0, right after B's header at item 4
        assertEquals(4, nowLineItemIndex(groups, 2))
    }

    @Test fun nowAfterAllStops() {
        // nowIndex 3 (== total stop count) → trailing NOW line at item 5 (before provenance)
        assertEquals(5, nowLineItemIndex(groups, 3))
    }

    @Test fun nullAndOutOfRangeYieldNull() {
        assertNull(nowLineItemIndex(groups, null))
        assertNull(nowLineItemIndex(groups, 4)) // beyond total → null (no scroll)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :ui:desktopTest --tests "*TimelineNowLineIndexTest"`
Expected: FAIL to compile — `nowLineItemIndex` is unresolved.

- [ ] **Step 3: Add the helper**

In `TimelineDetail.kt`, add near the other private helpers at the bottom of the file (e.g. after `tlSourceTag`):

```kotlin
/**
 * Absolute LazyColumn item index of the NOW line, matching [TimelineDetail]'s item emission:
 * one sticky-header item per group, then one item per stop, with the NOW line inserted before the
 * stop at flat index [nowIndex] — or after all stops when [nowIndex] equals the total stop count
 * (the trailing `now_line_end` item). Returns null when [nowIndex] is null or out of range.
 */
internal fun nowLineItemIndex(groups: List<TimelineGroup>, nowIndex: Int?): Int? {
    if (nowIndex == null) return null
    var abs = 0
    var flat = 0
    for (group in groups) {
        abs++ // sticky header
        for (stop in group.stops) {
            if (flat == nowIndex) return abs
            abs++ // stop row
            flat++
        }
    }
    return if (flat == nowIndex) abs else null // trailing all-past NOW line (before provenance)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :ui:desktopTest --tests "*TimelineNowLineIndexTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/TimelineDetail.kt \
        apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelineNowLineIndexTest.kt
git commit -m "feat(ui): nowLineItemIndex — absolute item index of the roadmap NOW line"
```

---

### Task 3: `TimelineDetail` — unified NOW render + open-at-NOW scroll

**Files:**
- Modify: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/TimelineDetail.kt` (imports, signature, scroll wiring, render loop)
- Modify (pass `autoScrollToNow = false` at snapshot render sites): `apps/ui/src/desktopTest/.../TimelineDetailSnapshotTest.kt`, `apps/ui/src/desktopTest/.../DerivedTimelineSnapshotTest.kt`, `apps/ui/src/desktopTest/.../snapshot/SnapshotScenes.kt`

**Interfaces:**
- Consumes: `nowLineItemIndex(groups, nowIndex)` (Task 2), `presentTimelineDetail(...)` (Task 1).
- Produces: `fun TimelineDetail(tl, scale, nowIso, tz, onBack, onAction, autoScrollToNow: Boolean = true)`.

- [ ] **Step 1: Add imports**

In `TimelineDetail.kt`, add to the import block:

```kotlin
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
```

- [ ] **Step 2: Add the `autoScrollToNow` parameter**

Change the function signature (currently ends `onAction: (CardAction) -> Unit,`):

```kotlin
fun TimelineDetail(
    tl: Timeline,
    scale: TimelineScale,
    nowIso: String,
    tz: TimeZone,
    onBack: () -> Unit,
    onAction: (CardAction) -> Unit,
    // Opens scrolled to the NOW line (past above, future below). false in snapshot scenes so the
    // one-shot scroll doesn't race the single-frame capture.
    autoScrollToNow: Boolean = true,
) {
```

- [ ] **Step 3: Add the scroll wiring after `presented`**

Immediately after the existing `val presented = remember(tl, active, nowIso, tz) { presentTimelineDetail(tl, active, nowIso, tz) }` line, insert:

```kotlin
    // Open-at-NOW: pre-seat the list so frame 0 is already at the NOW line (no top→NOW jump under
    // the enter morph); a one-shot seat nudge then drops NOW below the pinned month sticky-header.
    // ONE unconditional keyed remember — a conditional remember whose branch flips faults the slot
    // table. remember(active) re-seats to NOW for the swapped content on the Day↔Hub toggle.
    val nowItemIndex = remember(presented) { nowLineItemIndex(presented.groups, presented.nowIndex) }
    val headerPx = with(LocalDensity.current) { 46.dp.roundToPx() }
    val listState = remember(active) {
        LazyListState(firstVisibleItemIndex = if (autoScrollToNow) (nowItemIndex ?: 0) else 0)
    }
    LaunchedEffect(active) {
        if (autoScrollToNow && nowItemIndex != null) listState.scrollToItem(nowItemIndex, headerPx)
    }
```

- [ ] **Step 4: Wire the state into the LazyColumn**

Change the `LazyColumn(` in the feed section to pass the state (add `state = listState,` as the first argument after the modifier):

```kotlin
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 18.dp + navBottom),
        ) {
```

- [ ] **Step 5: Unify the NOW-line rendering (delete the Hub branch, drop scale guards, rename keys)**

Replace the render block (the Hub group-header NOW branch + the Day per-stop insertion + the trailing Day block) with this scale-agnostic version:

```kotlin
            var flatIdx = 0
            presented.groups.forEachIndexed { groupIdx, group ->
                stickyHeader(key = "grp_$groupIdx") {
                    TlGroupHeader(group.label, isFirst = groupIdx == 0)
                }

                group.stops.forEachIndexed { stopIdx, ps ->
                    val fi = flatIdx
                    // NOW line before the stop at this flat (chronological) index — both scales.
                    if (presented.nowIndex == fi) {
                        item(key = "now_line_$fi") {
                            TlNowLine(presented.nowTimeLabel ?: "Today")
                        }
                    }
                    val isLast = groupIdx == presented.groups.lastIndex &&
                        stopIdx == group.stops.lastIndex
                    item(key = "stop_${groupIdx}_$stopIdx") {
                        TlEntryRow(ps, isLast, active, presented.derived, onAction)
                    }
                    flatIdx++
                }
            }

            // NOW after all stops (an all-past timeline) — both scales.
            val totalFlat = flatIdx
            if (presented.nowIndex == totalFlat) {
                item(key = "now_line_end") {
                    TlNowLine(presented.nowTimeLabel ?: "Today")
                }
            }
```

- [ ] **Step 6: Pass `autoScrollToNow = false` at every snapshot render site**

In `SnapshotScenes.kt` (~line 420) change:

```kotlin
        TimelineDetail(tl = tl, scale = scale, nowIso = SnapshotStates.TIMELINE_NOW, tz = NY, onBack = {}, onAction = {}, autoScrollToNow = false)
```

In `DerivedTimelineSnapshotTest.kt:70` change:

```kotlin
                    TimelineDetail(derived(), TimelineScale.Day, nowIso, ny, {}, {}, autoScrollToNow = false)
```

In `TimelineDetailSnapshotTest.kt`, add `autoScrollToNow = false` to **every** `TimelineDetail(...)` call — the two helper calls (`shot`, `shotScrolled`, using named args, add `autoScrollToNow = false,` after `onAction = {},`) and the 14 inline test calls of the form `TimelineDetail(dayTimeline(), TimelineScale.Day, nowIso, ny, {}, {})` → `TimelineDetail(dayTimeline(), TimelineScale.Day, nowIso, ny, {}, {}, autoScrollToNow = false)` (also the `hubTimeline()` and `bothScalesTimeline()` variants). All 16 sites in this file.

- [ ] **Step 7: Run the affected UI tests to verify they compile + pass (top render preserved)**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :ui:desktopTest --tests "*TimelineDetailSnapshotTest" --tests "*DerivedTimelineSnapshotTest"`
Expected: PASS — with `autoScrollToNow = false` these render from the top exactly as before, so existing behavioral assertions still hold.

- [ ] **Step 8: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/TimelineDetail.kt \
        apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelineDetailSnapshotTest.kt \
        apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/DerivedTimelineSnapshotTest.kt \
        apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/SnapshotScenes.kt
git commit -m "feat(ui): roadmap opens scrolled to NOW; unified inter-stop NOW-line render"
```

---

### Task 4: `runComposeUiTest` — open-at-NOW behavior

**Files:**
- Test: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelineDetailSnapshotTest.kt` (add one test + two imports)

**Interfaces:**
- Consumes: `TimelineDetail(..., autoScrollToNow = true)` (Task 3, default).

- [ ] **Step 1: Add the imports**

At the top of `TimelineDetailSnapshotTest.kt` add:

```kotlin
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
```

- [ ] **Step 2: Write the test**

Add inside `TimelineDetailSnapshotTest` (this one keeps `autoScrollToNow` at its default `true`):

```kotlin
    @Test fun `hub roadmap opens scrolled to NOW with earliest past scrolled off`() = runComposeUiTest {
        // Five past monthly milestones + one future stop. now = nowIso (Aug 24). Opening at NOW must
        // scroll the earliest month (JAN "kickoff") off the top, and the NOW line must be visible.
        val tl = Timeline(title = "Season", tz = "America/New_York", stops = listOf(
            Stop("2026-01-10", "kickoff", done = true),
            Stop("2026-02-10", "phase one", done = true),
            Stop("2026-03-10", "phase two", done = true),
            Stop("2026-04-10", "phase three", done = true),
            Stop("2026-05-10", "phase four", done = true),
            Stop("2026-08-25", "launch"),
        ))
        setContent {
            DayfoldTheme(darkTheme = false) {
                Box(Modifier.width(390.dp).height(760.dp)) {
                    TimelineDetail(tl, TimelineScale.Hub, nowIso, ny, {}, {})
                }
            }
        }
        waitForIdle()
        onNodeWithText("NOW · Today").assertIsDisplayed()
        onNodeWithText("kickoff").assertDoesNotExist()   // earliest past scrolled above the fold
    }
```

- [ ] **Step 3: Run to verify it passes**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :ui:desktopTest --tests "*TimelineDetailSnapshotTest"`
Expected: PASS. (If `kickoff` unexpectedly still exists, the viewport is too tall / not enough past — this data set puts 5 months above NOW, comfortably beyond a 760.dp fold.)

- [ ] **Step 4: Commit**

```bash
git add apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelineDetailSnapshotTest.kt
git commit -m "test(ui): roadmap opens scrolled to NOW, earliest past off-screen"
```

---

### Task 5: Regenerate + verify the Hub roadmap golden

**Files:**
- Modify (regenerated PNGs): `apps/ui/src/desktopTest/resources/snapshots/macos/timeline-detail-hub.png` and `.../linux/timeline-detail-hub.png` (only if the captured viewport shifts)

**Interfaces:** none (visual regression gate `GoldenSnapshotTest.timelineDetailHub`, scene `timeline-detail`/`hub`).

- [ ] **Step 1: Run the golden gate to see what changed**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :ui:desktopTest --tests "*GoldenSnapshotTest"`
Expected: `timelineDetailHub` may FAIL (diff > 4%) if the NOW line's new inter-stop position is within the captured top-of-list viewport; Day/derived/both-toggle presets are unchanged (same flat-index path). If nothing fails, skip to Step 4.
(Note: `GoldenSnapshotTest.kt`'s header comment shows the record command as `:client:desktopTest`; the file lives under `:ui` — use `:ui:desktopTest`. If the module is ambiguous, the plain run above reveals which task compiles/owns it.)

- [ ] **Step 2: Re-record the macOS golden and eyeball it**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :ui:desktopTest --tests "*GoldenSnapshotTest" -Dsnapshot.record=true`
Then open `apps/ui/src/desktopTest/resources/snapshots/macos/timeline-detail-hub.png` and confirm the NOW line now sits between the correct stops (after the current month's past stops, not above the whole month).

- [ ] **Step 3: Re-record the linux golden in the ubuntu container**

Follow the linux golden recipe in `processes/agent-dev-loop.md` (ubuntu container, same `-Dsnapshot.record=true` command). This produces `apps/ui/src/desktopTest/resources/snapshots/linux/timeline-detail-hub.png`. Cross-OS glyph drift means the macOS PNG cannot stand in for linux.

- [ ] **Step 4: Run the gate green (macOS)**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :ui:desktopTest --tests "*GoldenSnapshotTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/ui/src/desktopTest/resources/snapshots/macos/timeline-detail-hub.png \
        apps/ui/src/desktopTest/resources/snapshots/linux/timeline-detail-hub.png
git commit -m "test(ui): re-record roadmap-detail hub golden (inter-stop NOW line)"
```

---

### Task 6: Full green + changelog + on-device smoke

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Run the full client + ui desktop test suites**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest :ui:desktopTest`
Expected: PASS (all suites green).

- [ ] **Step 2: Add a CHANGELOG entry**

Add under the current dated section of `CHANGELOG.md`:

```markdown
- **Roadmap NOW placement + open-at-NOW:** the full-screen Roadmap (timeline detail)
  now places the NOW line in true chronological order — inline between the last past and
  first future stop, even mid-month — and opens scrolled to NOW, with past content
  reachable by scrolling up. (`TimelineDetail`, ADR 0045/0046 surface.)
```

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs(changelog): roadmap NOW chronological placement + open-at-NOW"
```

- [ ] **Step 4: Build + install to the Pixel for operator smoke (operator drives the UI)**

Run:
```bash
cd apps
SDK=~/Library/Android/sdk; DEV=$($SDK/platform-tools/adb devices | awk 'NR>1&&$2=="device"{print $1;exit}')
ANDROID_HOME=$SDK JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :androidApp:assembleDebug
$SDK/platform-tools/adb -s $DEV install -r androidApp/build/outputs/apk/debug/dayfold-android-debug.apk
$SDK/platform-tools/adb -s $DEV shell am start -n com.sloopworks.dayfold/com.sloopworks.dayfold.android.MainActivity
```
Operator confirms on a roadmap with past + present + future milestones: opens at NOW, past scrolls up, NOW line seated below the pinned month header, no jump/flash during the open animation.

---

## Notes / out of scope

- The "navigate directly to specific content/date" caveat is out of scope — `TimelineDetail` has no such entry point yet; when added it passes an explicit target index that overrides open-at-NOW.
- Rail connector interruption at the NOW line and the gap-month "NOW under a future month header" case are pre-existing behaviors (Day scale since ADR 0045), inherited by Hub — not regressions.
