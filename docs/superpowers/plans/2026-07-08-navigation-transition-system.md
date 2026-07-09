# Navigation Transition System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every navigation edge a meaningful, consistent transition, driven by one central taxonomy, so existing and future routes animate correctly by default.

**Architecture:** A pure resolver (`RouteMotion.kt`) maps `(fromRoute, toRoute)` → a `NavAnim` via an exhaustive `routeSpec` table (adding a `Route` without a spec is a compile error). `NavMotion.kt` holds motion tokens and the single Compose builder `NavAnim.toContentTransform`. One `AnimatedContent` in `FeedApp` wraps the top-level `when(route)` and applies the resolver; Feed+Hubs share a content key so a persistent `TabShell` (one Scaffold + bottom bar) stays put while a nested `AnimatedContent` slides the tab content. The existing container transforms (Feed↔Detail, Hub↔Timeline) are untouched and become the "hero" tier.

**Tech Stack:** Kotlin 2.3.20, Compose Multiplatform 1.11.1 (androidx.compose.animation 1.11.2), redux-kotlin (route-driven nav, no nav library), JDK 17 Gradle.

## Global Constraints

- JDK 17 for all Gradle: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
- Module is `apps/ui`; commands run from `apps/` (`./gradlew :ui:desktopTest`, `./gradlew :androidApp:assembleDebug`).
- **No new dependencies.** Navigation stays redux-`route`-driven; do NOT add a nav library.
- **No reducer / `Route` / route-model changes.** Account stays a `Route`, animated *as* a modal.
- Preserve the ADR 0050 container transforms (Feed↔Detail in `ContentHost`, Hub↔Timeline in `HubDetailScreen`) — do not convert them.
- All motion honors reduced motion via `rememberReduceMotion()` (`Snap` → instant).
- `routeSpec(route)` MUST be an exhaustive `when` over `Route` (the consistency guarantee — no `else` branch).
- Match existing code style: terse comments, no unrelated refactors.

---

### Task 1: `RouteMotion.kt` — pure taxonomy + resolver (TDD)

The heart of the system. Pure Kotlin, no Compose → fully unit-tested.

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/RouteMotion.kt`
- Test: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/RouteMotionTest.kt`

**Interfaces:**
- Consumes: `Route` enum (`com.sloopworks.dayfold.client.Route`, values: Loading, SignIn, AuthError, CreateFamily, Feed, Hubs, Account, JoinInvite, Members, Invite, Devices, EnterCode, AuthorizeDevice, ScanPrimer, ScanDevice, ScanDenied, Proximity).
- Produces: `enum class NavKind`, `data class RouteSpec(tier, kind, tabIndex)`, `fun routeSpec(Route): RouteSpec`, `enum class NavAnim`, `fun navAnimFor(from: Route, to: Route, reduceMotion: Boolean): NavAnim`.

- [ ] **Step 1: Write the failing test**

Create `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/RouteMotionTest.kt`:

```kotlin
package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteMotionTest {
  private fun anim(from: Route, to: Route, reduce: Boolean = false) = navAnimFor(from, to, reduce)

  @Test fun tab_switch_is_shared_axis_x_by_index() {
    assertEquals(NavAnim.SharedXForward, anim(Route.Feed, Route.Hubs))
    assertEquals(NavAnim.SharedXBackward, anim(Route.Hubs, Route.Feed))
  }

  @Test fun opening_account_is_modal_enter_closing_is_modal_exit() {
    assertEquals(NavAnim.ModalEnter, anim(Route.Feed, Route.Account))
    assertEquals(NavAnim.ModalExit, anim(Route.Account, Route.Feed))
  }

  @Test fun push_deeper_from_modal_is_not_a_modal_exit() {
    // Account (modal, tier 1) -> Members (push, tier 2) must be a forward push, not exit.
    assertEquals(NavAnim.SharedZForward, anim(Route.Account, Route.Members))
    assertEquals(NavAnim.SharedZBackward, anim(Route.Members, Route.Account))
  }

  @Test fun settings_push_and_pop() {
    assertEquals(NavAnim.SharedZForward, anim(Route.Members, Route.Invite))
    assertEquals(NavAnim.SharedZBackward, anim(Route.Invite, Route.Members))
    assertEquals(NavAnim.SharedZForward, anim(Route.Account, Route.Devices))
    assertEquals(NavAnim.SharedZForward, anim(Route.Account, Route.Proximity))
  }

  @Test fun device_wizard_is_shared_axis_x() {
    assertEquals(NavAnim.SharedXForward, anim(Route.EnterCode, Route.ScanPrimer))
    assertEquals(NavAnim.SharedXBackward, anim(Route.ScanPrimer, Route.EnterCode))
    // entering / leaving the wizard from a non-wizard base
    assertEquals(NavAnim.SharedXForward, anim(Route.Feed, Route.EnterCode))
    assertEquals(NavAnim.SharedXBackward, anim(Route.EnterCode, Route.Feed))
  }

  @Test fun gate_edges_fade_through() {
    assertEquals(NavAnim.FadeThrough, anim(Route.Loading, Route.Feed))
    assertEquals(NavAnim.FadeThrough, anim(Route.SignIn, Route.CreateFamily))
  }

  @Test fun reduced_motion_and_self_transition_snap() {
    assertEquals(NavAnim.Snap, anim(Route.Feed, Route.Account, reduce = true))
    assertEquals(NavAnim.Snap, anim(Route.Feed, Route.Feed))
  }

  @Test fun routeSpec_is_total() {
    // Every Route must have a spec (compile-time exhaustiveness + no throw at runtime).
    Route.entries.forEach { routeSpec(it) }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :ui:desktopTest --tests "com.sloopworks.dayfold.client.RouteMotionTest"`
Expected: FAIL — compile error, `navAnimFor` / `NavAnim` / `routeSpec` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/RouteMotion.kt`:

```kotlin
package com.sloopworks.dayfold.client

// Pure navigation-motion taxonomy — NO Compose. Maps each Route to a motion class +
// hierarchy tier, and resolves a (from,to) route pair to a NavAnim. See ADR 0051.
// The `when` in routeSpec is exhaustive over Route → adding a Route without a spec is a
// compile error. That is the consistency guarantee: new nav must pick a NavKind.

enum class NavKind { Tab, Push, Modal, Wizard, Gate }

/** tier = hierarchy depth (drives push/pop + wizard/modal direction). tabIndex only for Tab. */
data class RouteSpec(val tier: Int, val kind: NavKind, val tabIndex: Int = -1)

fun routeSpec(route: Route): RouteSpec = when (route) {
  Route.Loading -> RouteSpec(0, NavKind.Gate)
  Route.SignIn -> RouteSpec(0, NavKind.Gate)
  Route.AuthError -> RouteSpec(0, NavKind.Gate)
  Route.CreateFamily -> RouteSpec(1, NavKind.Wizard)
  Route.JoinInvite -> RouteSpec(2, NavKind.Modal)
  Route.Feed -> RouteSpec(0, NavKind.Tab, tabIndex = 0)
  Route.Hubs -> RouteSpec(0, NavKind.Tab, tabIndex = 1)
  Route.Account -> RouteSpec(1, NavKind.Modal)
  Route.Proximity -> RouteSpec(2, NavKind.Push)
  Route.Devices -> RouteSpec(2, NavKind.Push)
  Route.Members -> RouteSpec(2, NavKind.Push)
  Route.Invite -> RouteSpec(3, NavKind.Push)
  Route.EnterCode -> RouteSpec(1, NavKind.Wizard)
  Route.ScanPrimer -> RouteSpec(2, NavKind.Wizard)
  Route.ScanDevice -> RouteSpec(3, NavKind.Wizard)
  Route.ScanDenied -> RouteSpec(3, NavKind.Wizard)
  Route.AuthorizeDevice -> RouteSpec(4, NavKind.Wizard)
}

enum class NavAnim {
  SharedXForward, SharedXBackward,
  SharedZForward, SharedZBackward,
  ModalEnter, ModalExit,
  FadeThrough, Snap,
}

/** Resolve the motion for a route change. Pure — unit-tested. First match wins. */
fun navAnimFor(from: Route, to: Route, reduceMotion: Boolean): NavAnim {
  if (reduceMotion) return NavAnim.Snap
  if (from == to) return NavAnim.Snap
  val f = routeSpec(from)
  val t = routeSpec(to)
  // 1. Tab peers → horizontal by index.
  if (f.kind == NavKind.Tab && t.kind == NavKind.Tab)
    return if (t.tabIndex > f.tabIndex) NavAnim.SharedXForward else NavAnim.SharedXBackward
  // 2. Modal open/close — tier-gated so a push *deeper from* a modal is NOT an exit.
  if (t.kind == NavKind.Modal && t.tier > f.tier) return NavAnim.ModalEnter
  if (f.kind == NavKind.Modal && t.tier < f.tier) return NavAnim.ModalExit
  // 3. Gate (boot / auth error) — unrelated content.
  if (f.kind == NavKind.Gate || t.kind == NavKind.Gate) return NavAnim.FadeThrough
  // 4. Wizard (either endpoint) — linear horizontal.
  if (f.kind == NavKind.Wizard || t.kind == NavKind.Wizard)
    return if (t.tier >= f.tier) NavAnim.SharedXForward else NavAnim.SharedXBackward
  // 5. Push / Pop by depth.
  return if (t.tier > f.tier) NavAnim.SharedZForward else NavAnim.SharedZBackward
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :ui:desktopTest --tests "com.sloopworks.dayfold.client.RouteMotionTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/RouteMotion.kt \
        apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/RouteMotionTest.kt
git commit -m "feat(ui): navigation-motion taxonomy + resolver (RouteMotion)"
```

---

### Task 2: `NavMotion.kt` — tokens + Compose `ContentTransform` builder

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/NavMotion.kt`

**Interfaces:**
- Consumes: `NavAnim` (Task 1).
- Produces: `object NavMotion { StandardMs, HeroMs, FastMs, ReducedMs, Emphasized, EmphasizedDecelerate, EmphasizedAccelerate }`; `fun NavAnim.toContentTransform(slidePx: Int): ContentTransform`.

- [ ] **Step 1: Write the implementation** (no unit test — `ContentTransform` has no meaningful equality; verified by build + on-device in later tasks)

Create `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/NavMotion.kt`:

```kotlin
package com.sloopworks.dayfold.client

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith

// Motion tokens — the single home for durations + easings across the app's navigation
// AND the hero container transforms (Feed↔Detail, Hub↔Timeline read HeroMs in Task 6).
object NavMotion {
  const val StandardMs = 400
  const val HeroMs = 460
  const val FastMs = 250
  const val ReducedMs = 0

  // Material 3 Expressive easings.
  val Emphasized: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
  val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
  val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
}

// Build the enter/exit pair for a resolved NavAnim. slidePx = axis travel in px
// (host passes ~30dp worth). Incoming uses decelerate (settles in); outgoing accelerates out.
fun NavAnim.toContentTransform(slidePx: Int): ContentTransform {
  val enterMs = NavMotion.StandardMs
  val dec = NavMotion.EmphasizedDecelerate
  val acc = NavMotion.EmphasizedAccelerate
  return when (this) {
    NavAnim.SharedXForward ->
      (slideInHorizontally(tween(enterMs, easing = dec)) { slidePx } + fadeIn(tween(enterMs))) togetherWith
        (slideOutHorizontally(tween(enterMs, easing = acc)) { -slidePx } + fadeOut(tween(enterMs)))
    NavAnim.SharedXBackward ->
      (slideInHorizontally(tween(enterMs, easing = dec)) { -slidePx } + fadeIn(tween(enterMs))) togetherWith
        (slideOutHorizontally(tween(enterMs, easing = acc)) { slidePx } + fadeOut(tween(enterMs)))
    NavAnim.SharedZForward ->
      (scaleIn(tween(enterMs, easing = dec), initialScale = 0.85f) + fadeIn(tween(enterMs))) togetherWith
        (scaleOut(tween(enterMs, easing = acc), targetScale = 1.1f) + fadeOut(tween(enterMs)))
    NavAnim.SharedZBackward ->
      (scaleIn(tween(enterMs, easing = dec), initialScale = 1.1f) + fadeIn(tween(enterMs))) togetherWith
        (scaleOut(tween(enterMs, easing = acc), targetScale = 0.85f) + fadeOut(tween(enterMs)))
    NavAnim.ModalEnter ->
      (slideInVertically(tween(enterMs, easing = dec)) { it } + fadeIn(tween(enterMs))) togetherWith
        fadeOut(tween(enterMs))
    NavAnim.ModalExit ->
      fadeIn(tween(enterMs)) togetherWith
        (slideOutVertically(tween(enterMs, easing = acc)) { it } + fadeOut(tween(enterMs)))
    NavAnim.FadeThrough ->
      fadeIn(tween(enterMs, delayMillis = 90)) togetherWith fadeOut(tween(90))
    NavAnim.Snap ->
      EnterTransition.None togetherWith ExitTransition.None
  }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :ui:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/NavMotion.kt
git commit -m "feat(ui): NavMotion tokens + NavAnim->ContentTransform builder"
```

---

### Task 3: `TabShell` — persistent Scaffold + bottom bar + inner tab slide

Extract one persistent shell so Feed↔Hubs slides its content while the bottom bar stays put. Feed/Hubs stop owning their Scaffold/bottomBar.

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/TabShell.kt`
- Modify: `apps/ui/.../FeedScreen.kt` (remove its `Scaffold`/`bottomBar`; expose content-only body), `apps/ui/.../HubScreens.kt` (hub list + hub detail: remove their `Scaffold` bottom bars where they duplicate the tab bar; keep their top bars).
- Modify: `apps/ui/.../FeedApp.kt` — Feed/Hubs branches render `TabShell`.

**Interfaces:**
- Consumes: `NavMotion`, `navAnimFor`/`NavAnim` (X specs), `DayfoldBottomNav` (`HubScreens.kt:105`), the existing `FeedScreen`/`HubsHost` content.
- Produces: `@Composable fun TabShell(state: AppState, store: Store<AppState>, feedContent, hubsContent, onNow, onHubs)` — renders a single `Scaffold(bottomBar = DayfoldBottomNav(...))` whose body is a nested `AnimatedContent(targetState = state.route)` (Feed vs Hubs) using the Tab X spec.

- [ ] **Step 1: Write `TabShell.kt`**

Create `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/TabShell.kt`:

```kotlin
package com.sloopworks.dayfold.client

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

// Persistent Feed↔Hubs shell: ONE Scaffold + bottom bar that stays put while a nested
// AnimatedContent slides the tab content (shared-axis-X). Non-tab routes render OUTSIDE
// this shell (AppNavHost). reduceMotion → Snap (via navAnimFor). See ADR 0051.
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun TabShell(
  route: Route,                         // Route.Feed or Route.Hubs
  reduceMotion: Boolean,
  onNow: () -> Unit,
  onHubs: () -> Unit,
  feedContent: @Composable (Modifier) -> Unit,
  hubsContent: @Composable (Modifier) -> Unit,
) {
  val slidePx = with(LocalDensity.current) { 30.dp.roundToPx() }
  Scaffold(bottomBar = { DayfoldBottomNav(hubsActive = route == Route.Hubs, onNow = onNow, onHubs = onHubs) }) { pad ->
    AnimatedContent(
      targetState = route,
      transitionSpec = { navAnimFor(initialState, targetState, reduceMotion).toContentTransform(slidePx) },
      contentKey = { it },
    ) { r ->
      Box(Modifier.fillMaxSize().padding(pad)) {
        if (r == Route.Hubs) hubsContent(Modifier.fillMaxSize()) else feedContent(Modifier.fillMaxSize())
      }
    }
  }
}
```

- [ ] **Step 2: Make `FeedScreen` content-only**

In `apps/ui/.../FeedScreen.kt`, the current `FeedScreen` wraps content in `Scaffold(bottomBar = DayfoldBottomNav(...))` (around line 95). Remove that `Scaffold`+`bottomBar` and the `onNavHubs` bottom-nav wiring from `FeedScreen`; render the feed body directly (keep its top area / account icon). The bottom bar now lives in `TabShell`. Keep the `onShown`, `onRefresh`, `onOpenAccount` params. (Exact edit: delete the `Scaffold(... bottomBar = { DayfoldBottomNav(...) }) { inner -> ... }` wrapper; hoist its `{ inner -> ... }` body up, applying the incoming `Modifier` from `TabShell`.)

- [ ] **Step 3: Make `HubsHost` (hub list + hub detail) content-only for the tab bar**

In `apps/ui/.../HubScreens.kt`, `HubListScreen` (`Scaffold` at ~136) and `HubDetailScreen` (`Scaffold` bottomBar at ~336) both mount `DayfoldBottomNav`. Remove the `bottomBar = { DayfoldBottomNav(...) }` from BOTH Scaffolds (keep their `topBar`s and content). The tab bar is now `TabShell`'s. `HubsHost` renders as the `hubsContent` slot.

- [ ] **Step 4: Wire `TabShell` into `FeedApp`'s Feed/Hubs branches**

In `apps/ui/.../FeedApp.kt`, replace the two branches:

```kotlin
Route.Feed -> ContentHost(store, state, handle, ...)
Route.Hubs -> HubsHost(store, state, ...)
```

with a single `TabShell` call for both (rendered when `state.route == Feed || Hubs`):

```kotlin
Route.Feed, Route.Hubs -> TabShell(
  route = state.route,
  reduceMotion = rememberReduceMotion(),
  onNow = { store.dispatch(OpenFeed) },
  onHubs = { store.dispatch(OpenHubs); onLoadHubs() },
  feedContent = { m -> ContentHost(store, state, handle, /* existing callbacks */, modifier = m) },
  hubsContent = { m -> HubsHost(store, state, /* existing callbacks */, modifier = m) },
)
```

(Thread a `modifier: Modifier = Modifier` param into `ContentHost` and `HubsHost` and apply it to their root; they no longer own the bottom bar.)

- [ ] **Step 5: Build + snapshot tests**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :ui:desktopTest`
Expected: BUILD SUCCESSFUL; snapshot tests pass (bottom bar still renders on Feed/Hubs; if a snapshot's bottom-bar position shifted, regenerate the golden per the snapshot workflow and eyeball the diff).

- [ ] **Step 6: On-device verify the tab slide + persistent bar**

Run (physical device or emulator):
```bash
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/dayfold-android-debug.apk
```
Tap the "Hubs" then "Now" bottom-nav items; burst-capture with `adb exec-out screencap -p`. Expected: content slides horizontally (Now→Hubs = leftward), the bottom bar does NOT flicker/re-lay.

- [ ] **Step 7: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/TabShell.kt \
        apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedScreen.kt \
        apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/HubScreens.kt \
        apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedApp.kt
git commit -m "feat(ui): persistent TabShell + shared-axis-X Feed<->Hubs tab slide"
```

---

### Task 4: `AppNavHost` — one host wraps the top-level router

Wrap the whole `when(route)` in a single `AnimatedContent` using the resolver, so every top-level edge (modal/push/wizard/gate) animates. Feed+Hubs share the content key `"tabs"` so entering/leaving the tabs group animates but a Feed↔Hubs switch does NOT re-trigger the outer host (that's TabShell's inner slide).

**Files:**
- Modify: `apps/ui/.../FeedApp.kt` (wrap the `when(state.route)` block).

**Interfaces:**
- Consumes: `navAnimFor`, `NavAnim.toContentTransform` (Tasks 1–2), the existing route branches, `TabShell` (Task 3).
- Produces: `private fun navGroupKey(route: Route): String` (Feed/Hubs → `"tabs"`, else `route.name`).

- [ ] **Step 1: Add `navGroupKey` + wrap the router**

In `apps/ui/.../FeedApp.kt`, add near the top of the file:

```kotlin
// Feed & Hubs share ONE key so the persistent TabShell (bar) does not cross-fade on a tab
// switch; the tab slide is TabShell's inner AnimatedContent. Every other route is its own key.
private fun navGroupKey(route: Route): String =
  if (route == Route.Feed || route == Route.Hubs) "tabs" else route.name
```

Wrap the existing `when (state.route) { ... }` (the top-level router, currently ~line 160) in:

```kotlin
val reduceMotion = rememberReduceMotion()
val slidePx = with(LocalDensity.current) { 30.dp.roundToPx() }
AnimatedContent(
  targetState = state.route,
  transitionSpec = {
    // Group Feed/Hubs so intra-tab switches don't animate at THIS layer.
    if (navGroupKey(initialState) == navGroupKey(targetState))
      NavAnim.Snap.toContentTransform(slidePx)
    else navAnimFor(initialState, targetState, reduceMotion).toContentTransform(slidePx)
  },
  contentKey = { navGroupKey(it) },
  label = "app-nav",
) { route ->
  when (route) {
    // ... existing branches, using `route` instead of `state.route` for the outer switch ...
  }
}
```

Keep the inner `when(route)` branches exactly as they are (Feed/Hubs → `TabShell`, all other routes → their screens wrapped in `SafeArea` as today). Note: read per-branch data from `state` (unchanged); only the outer selector becomes `route`.

- [ ] **Step 2: Build**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: On-device verify modal + push + wizard + back**

Install (as Task 3 step 6). Verify with screencap bursts:
- Tap account icon (Feed→Account): Account **slides up** over the feed (ModalEnter). Back: slides down.
- Account→Members: content **scales/fades forward** (SharedZForward). Back: reverse.
- Feed→"Connect device" (EnterCode): **slides left** (wizard forward). Back: right.
Expected: each edge animates per taxonomy; no instant cuts remain.

- [ ] **Step 4: Verify reduced motion snaps**

```bash
adb shell settings put global animator_duration_scale 0   # simulate reduce-motion via OS off
```
(Note: `rememberReduceMotion` reads `ANIMATOR_DURATION_SCALE`.) Re-open Account etc. Expected: instant (Snap). Then restore: `adb shell settings put global animator_duration_scale 1`.

- [ ] **Step 5: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedApp.kt
git commit -m "feat(ui): AppNavHost — taxonomy-driven transition for every route edge"
```

---

### Task 5: Hub list → Hub detail push

The hub list→detail swap (`HubsHost`, bare `if (currentHubId != null)`) is still an instant cut. Give it a Push (shared-axis-Z).

**Files:**
- Modify: `apps/ui/.../HubScreens.kt` (the `HubsHost` `if (state.currentHubId != null) HubDetailScreen else HubListScreen` around line 342).

**Interfaces:**
- Consumes: `NavAnim.SharedZForward/Backward`, `toContentTransform`, `rememberReduceMotion`.

- [ ] **Step 1: Wrap the list/detail swap in `AnimatedContent`**

In `HubsHost` (`HubScreens.kt`), replace:

```kotlin
Box {
  if (state.currentHubId != null) HubDetailScreen(...) else HubListScreen(...)
  if (state.audienceSheetOpen) WhoCanSeeSheet(...)
}
```

with (keep the audience sheet as-is, outside the AnimatedContent):

```kotlin
val reduceMotion = rememberReduceMotion()
val slidePx = with(LocalDensity.current) { 30.dp.roundToPx() }
Box {
  AnimatedContent(
    targetState = state.currentHubId != null,   // false = list, true = detail
    transitionSpec = {
      val anim = if (reduceMotion) NavAnim.Snap
        else if (targetState) NavAnim.SharedZForward else NavAnim.SharedZBackward
      anim.toContentTransform(slidePx)
    },
    contentKey = { it },
    label = "hub-list-detail",
  ) { showDetail ->
    if (showDetail) HubDetailScreen(...) else HubListScreen(...)
  }
  if (state.audienceSheetOpen) WhoCanSeeSheet(...)
}
```

(Preserve the existing `HubDetailScreen(...)` / `HubListScreen(...)` argument lists verbatim.)

- [ ] **Step 2: Build + on-device verify**

Run: `cd apps && ... ./gradlew :androidApp:assembleDebug` then install. Tap a hub row → detail scales/fades forward; back → reverse. The timeline container transform inside the detail still works (it's one layer deeper).

- [ ] **Step 3: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/HubScreens.kt
git commit -m "feat(ui): hub list->detail push transition"
```

---

### Task 6: Tokenize hero durations; fold `PredictiveBackMotion` easings into `NavMotion`

Remove the duplicated inline 360/280 durations and the orphaned easing; point the hero container transforms at the tokens.

**Files:**
- Modify: `apps/ui/.../FeedApp.kt` (`ContentHost` transitionSpec + the detail `animateTo`/`transitionSpec` durations), `apps/ui/.../HubScreens.kt` (`HubDetailScreen` timeline transitionSpec durations).
- Modify/Remove: `apps/ui/.../PredictiveBackMotion.kt` — move `EmphasizedDecelerate`/`Decelerate`/`decelerateProgress` usage; `EmphasizedDecelerate` now lives in `NavMotion`. Keep `decelerateProgress`/`Decelerate` in `PredictiveBackMotion.kt` (used by its test / future live-drag); re-point the easing reference.

**Interfaces:**
- Consumes: `NavMotion.HeroMs`, `NavMotion.EmphasizedDecelerate`.

- [ ] **Step 1: Point hero durations at `NavMotion`**

In `ContentHost` (`FeedApp.kt`) and `HubDetailScreen` (`HubScreens.kt`), the container-transform `transitionSpec` uses `val dur = if (reduceMotion) 0 else if (opening) 360 else 280`. Change the open value to `NavMotion.HeroMs` and keep close at a token (add `const val HeroCloseMs = 380` to `NavMotion` if a distinct close is wanted, else reuse `StandardMs`). Concretely:

```kotlin
val dur = if (reduceMotion) NavMotion.ReducedMs else if (opening) NavMotion.HeroMs else NavMotion.StandardMs
```

- [ ] **Step 2: De-dupe the easing**

In `PredictiveBackMotion.kt`, delete the local `EmphasizedDecelerate` declaration and (if referenced) import `NavMotion.EmphasizedDecelerate`. Leave `Decelerate` + `decelerateProgress` (still used by `PredictiveBackMotionTest`).

- [ ] **Step 3: Build + tests**

Run: `cd apps && ... ./gradlew :ui:desktopTest` then `:androidApp:assembleDebug`.
Expected: green; the card→detail morph still renders on-device (re-verify one open, matching ADR 0050).

- [ ] **Step 4: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedApp.kt \
        apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/HubScreens.kt \
        apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/PredictiveBackMotion.kt
git commit -m "refactor(ui): tokenize hero transition durations + easing in NavMotion"
```

---

### Task 7: ADR 0051 + CHANGELOG + decisions-index

**Files:**
- Create: `adr/0051-navigation-transition-system.md`
- Modify: `adr/decisions-index.md` (append 0051 row), `CHANGELOG.md` (dated entry).

- [ ] **Step 1: Write ADR 0051**

Create `adr/0051-navigation-transition-system.md` capturing: Status (Accepted — 2026-07-08, operator-directed: chose expressive/spatial + full-system+persistent-shell in brainstorming); Context (2/20 edges animated); Decision (the taxonomy table, `NavKind`, tier model, exhaustive `routeSpec` enforcement rule, `NavMotion` tokens, `AppNavHost`, persistent `TabShell`); the standing rule **"every Route MUST have a `routeSpec` entry; new nav picks a `NavKind`, it does not hand-roll a transition"**; Consequences; Composition (refines 0022/0045/0050 — container transforms are the Hero tier; composes 0047). Reference the design doc `docs/superpowers/specs/2026-07-08-navigation-transition-system-design.md`.

- [ ] **Step 2: Append decisions-index row**

Add a `| 0051 | Navigation Transition System | **Accepted** 2026-07-08 (operator-directed)… |` row to `adr/decisions-index.md` mirroring the 0050 row style.

- [ ] **Step 3: CHANGELOG entry**

Add a `## 2026-07-08 — Navigation transitions` section to `CHANGELOG.md` under "Added (client)": every nav edge now animates by a consistent taxonomy (tab slide, push, modal slide-up, wizard, fade-through), persistent bottom bar, reduced-motion honored.

- [ ] **Step 4: Commit**

```bash
git add adr/0051-navigation-transition-system.md adr/decisions-index.md CHANGELOG.md
git commit -m "docs: ADR 0051 navigation transition system + changelog"
```

---

## Self-Review

**Spec coverage:** taxonomy (Task 1 resolver + Task 2 specs), tokens (Task 2), central host (Task 4), persistent shell (Task 3), hub list→detail (Task 5), hero tokenization (Task 6), reduced motion (Tasks 1/2 `Snap`, verified Task 4 step 4), testing (Task 1 unit + on-device Tasks 3–6), ADR (Task 7). All spec sections map to a task.

**Placeholders:** none — every code step shows real code; the FeedScreen/HubScreens Scaffold edits reference exact composables/line-anchors and describe the exact wrapper to remove.

**Type consistency:** `NavKind`, `RouteSpec(tier,kind,tabIndex)`, `NavAnim` (8 values), `navAnimFor(from,to,reduceMotion)`, `NavAnim.toContentTransform(slidePx)`, `navGroupKey`, `TabShell(route,reduceMotion,onNow,onHubs,feedContent,hubsContent)` — consistent across Tasks 1–6.

**Note for executor:** Tasks 3–4 touch the shell; keep the app building after each. If a snapshot golden shifts only because the bottom bar moved into `TabShell` (same pixels, same place), regenerate and eyeball; a real content diff means a mistake.
