# Loading States Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every user-triggered async action in the Dayfold client visible loading/error/empty feedback so the app never looks frozen.

**Architecture:** A small reusable loading component kit in `client/commonMain/.../ui/loading/`; additive Redux state (boolean busy flags + single nullable op-ids, no `Loadable<T>` refactor); per-screen wiring driven by `AppState`. Verified by Compose snapshot tests (desktopTest) + reducer unit tests + a fake-backend latency knob.

**Tech Stack:** Kotlin 2.3.20, Compose-Multiplatform 1.11.1 (Material 3 via `compose.material3`), redux-kotlin 1.0.0-alpha03, ktor 3.5.0 (MockEngine fakes), Compose UI Test (`runComposeUiTest` → PNG snapshots).

## Global Constraints

- Compose-MP **1.11.1**; Material 3 ships in the already-declared `compose.material3` — **no new Gradle dependency** for skeletons or `PullToRefreshBox` (the latter is `@OptIn(ExperimentalMaterial3Api::class)`).
- redux-kotlin **1.0.0-alpha03**; `store.selectorState { it }` (from `redux-kotlin-compose`) is the single state projection in `FeedApp.kt:84`.
- All UI in `commonMain`. Active compiled targets are **androidMain + desktopMain only** (iOS pending) — any `expect`/`actual` needs exactly those two actuals.
- The reducer is **pure**; all I/O lives in `AuthEngine`/`HubEngine` (mutex-serialized → at most one op in flight).
- Color tokens must be ones explicitly defined in `theme/Color.kt`: prefer `surfaceContainer`, `surfaceContainerHigh`, `surfaceContainerHighest`, `onSurfaceVariant`, `outline`, `outlineVariant`. **Do not use `surfaceVariant`** (not set in the schemes).
- Existing custom buttons are Box-based: `AuthButton` (`AuthScreens.kt:88`), `PillButton` (`DeviceApprovalScreens.kt:64`). Reuse them; do not introduce M3 `Button` for actions that already use these.
- Snapshot tests construct `AppState(...)` directly and render the screen under `DayfoldTheme` (no store) → PNG in `build/snapshots/`. Pattern: `AuthScreensSnapshotTest.kt`.
- Toolchain: `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home`; run Gradle from `apps/`: `./gradlew :client:desktopTest` (tests), `./gradlew :client:compileKotlinDesktop` (fast compile check).
- Commit after every task. Branch is `worktree-loading-states`.

## File map

| File | Change |
|---|---|
| `client/commonMain/.../ui/loading/Shimmer.kt` | Create — `Modifier.shimmer()` + `expect rememberReduceMotion()` |
| `client/androidMain/.../ui/loading/ReduceMotion.android.kt` | Create — actual |
| `client/desktopMain/.../ui/loading/ReduceMotion.desktop.kt` | Create — actual |
| `client/commonMain/.../ui/loading/Skeletons.kt` | Create — `SkeletonBox`, `ListSkeleton`, `FeedSkeleton` |
| `client/commonMain/.../ui/loading/StateViews.kt` | Create — `ErrorRetry`, `EmptyState` |
| `client/commonMain/.../ui/loading/StableLoading.kt` | Create — `rememberStableLoading` |
| `client/commonMain/.../ui/loading/BusyIndicators.kt` | Create — `RowBusy`, `FullScreenLoading` |
| `client/commonMain/.../client/AuthScreens.kt` | Modify — `AuthButton(busy=)`, `SignInScreen(pendingProvider=)`, `SplashScreen` → `FullScreenLoading` |
| `client/commonMain/.../client/DeviceApprovalScreens.kt` | Modify — `PillButton(busy=)`, drop "Checking…/Working…" morph |
| `client/commonMain/.../client/JoinInviteScreen.kt` | Modify — spinner in `JoinEntry` |
| `client/commonMain/.../client/AccountScreen.kt` | Modify — `signOutBusy` param + busy sign-out button |
| `client/commonMain/.../client/FeedScreen.kt` | Modify — skeleton + `PullToRefreshBox` |
| `client/commonMain/.../client/HubScreens.kt` | Modify — skeletons + error states + audience |
| `client/commonMain/.../client/MembersScreen.kt` | Modify — skeleton + per-row busy + error |
| `client/commonMain/.../client/DevicesScreen.kt` | Modify — skeleton + per-row busy + error |
| `client/commonMain/.../client/Model.kt` | Modify — new `AppState` fields + actions |
| `client/commonMain/.../client/Reducer.kt` | Modify — reduce new actions |
| `client/commonMain/.../client/AuthEngine.kt` | Modify — dispatch new Requested/Failed actions |
| `client/commonMain/.../client/FeedApp.kt` | Modify — thread `pendingProvider`/`signOutBusy` |
| `client/commonMain/.../client/fake/FakeBackend.kt` | Modify — `latencyMs` field |
| `client/desktopMain/.../fake/FakeHttpClient.kt` | Modify — `delay()` |
| `androidApp/src/debug/.../FakeBackend.kt` | Modify — `delay()` |
| `client/desktopTest/.../LoadingKitSnapshotTest.kt` | Create — component snapshots |
| `client/desktopTest/.../LoadingReducerTest.kt` | Create — reducer unit tests |
| `client/desktopTest/.../AuthScreensSnapshotTest.kt` | Modify — busy/error/empty screen snapshots |

The package prefix `client/commonMain/.../` = `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/`. New kit package is `com.sloopworks.dayfold.client.ui.loading`.

---

## Task 1: Reduced-motion seam + shimmer modifier

**Files:**
- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ui/loading/Shimmer.kt`
- Create: `apps/client/src/androidMain/kotlin/com/sloopworks/dayfold/client/ui/loading/ReduceMotion.android.kt`
- Create: `apps/client/src/desktopMain/kotlin/com/sloopworks/dayfold/client/ui/loading/ReduceMotion.desktop.kt`

**Interfaces:**
- Produces: `@Composable expect fun rememberReduceMotion(): Boolean`; `fun Modifier.shimmer(): Modifier` (animated sweep when motion allowed, static dim fill when reduced).

- [ ] **Step 1: Create the shimmer + expect declaration**

`Shimmer.kt`:
```kotlin
package com.sloopworks.dayfold.client.ui.loading

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

/**
 * True when the platform asks for reduced motion. Shimmer falls back to a static
 * dim fill so the animation never runs (WCAG 2.3.3 / vestibular safety).
 */
@Composable expect fun rememberReduceMotion(): Boolean

/**
 * Placeholder fill for skeletons: a horizontal highlight sweep over a base tint.
 * When reduce-motion is on, paints a single static tint (no animation).
 */
fun Modifier.shimmer(): Modifier = composed {
  val base = MaterialTheme.colorScheme.surfaceContainerHigh
  val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
  if (rememberReduceMotion()) {
    background(base)
  } else {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
      initialValue = -600f,
      targetValue = 600f,
      animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
      label = "shimmer-x",
    )
    background(
      Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(x, 0f),
        end = Offset(x + 300f, 0f),
      ),
    )
  }
}
```

- [ ] **Step 2: Create the desktop actual**

`ReduceMotion.desktop.kt`:
```kotlin
package com.sloopworks.dayfold.client.ui.loading

import androidx.compose.runtime.Composable

// Desktop has no OS reduced-motion signal we read at M0 → animate.
@Composable actual fun rememberReduceMotion(): Boolean = false
```

- [ ] **Step 3: Create the android actual**

`ReduceMotion.android.kt`:
```kotlin
package com.sloopworks.dayfold.client.ui.loading

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// Honor the system "remove animations" setting (ANIMATOR_DURATION_SCALE == 0).
@Composable actual fun rememberReduceMotion(): Boolean {
  val resolver = LocalContext.current.contentResolver
  return remember(resolver) {
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
  }
}
```

- [ ] **Step 4: Compile**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ui/loading/Shimmer.kt apps/client/src/androidMain/kotlin/com/sloopworks/dayfold/client/ui/loading/ReduceMotion.android.kt apps/client/src/desktopMain/kotlin/com/sloopworks/dayfold/client/ui/loading/ReduceMotion.desktop.kt
git commit -m "feat(client): shimmer modifier + reduced-motion seam"
```

---

## Task 2: Skeleton components

**Files:**
- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ui/loading/Skeletons.kt`

**Interfaces:**
- Consumes: `Modifier.shimmer()` (Task 1).
- Produces: `SkeletonBox(modifier, corner)`, `ListSkeleton(rows, modifier)`, `FeedSkeleton(modifier)`. All carry `semantics { invisibleToUser() }` at the container with one `liveRegion`-announced "Loading" node.

- [ ] **Step 1: Create the file**

`Skeletons.kt`:
```kotlin
package com.sloopworks.dayfold.client.ui.loading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A single shimmering placeholder block. */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier, corner: Int = 12) {
  Spacer(modifier.clip(RoundedCornerShape(corner.dp)).shimmer())
}

// One node announces the region; the rest are hidden so TalkBack doesn't read
// dozens of empty placeholders.
private fun Modifier.loadingRegion(label: String) =
  semantics { liveRegion = LiveRegionMode.Polite; contentDescription = label }

/** Generic list placeholder: N rows of avatar + two text lines. */
@Composable
fun ListSkeleton(rows: Int = 4, modifier: Modifier = Modifier) {
  Column(
    modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp).loadingRegion("Loading"),
    verticalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    repeat(rows) {
      Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).shimmer().padding(13.dp).semantics { invisibleToUser() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        SkeletonBox(Modifier.size(38.dp), corner = 50)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          SkeletonBox(Modifier.height(14.dp).width(140.dp))
          SkeletonBox(Modifier.height(12.dp).width(90.dp))
        }
      }
    }
  }
}

/** Feed placeholder: a few tall cards mirroring TypedCardItem metrics. */
@Composable
fun FeedSkeleton(modifier: Modifier = Modifier) {
  Column(
    modifier.fillMaxSize().padding(PaddingValues(16.dp)).loadingRegion("Loading your day"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    repeat(3) {
      SkeletonBox(Modifier.fillMaxWidth().height(120.dp).semantics { invisibleToUser() }, corner = 18)
    }
  }
}
```

- [ ] **Step 2: Compile**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL. (If `invisibleToUser` is unresolved, it's `androidx.compose.ui.semantics.invisibleToUser` — experimental; add `@file:OptIn(ExperimentalComposeUiApi::class)` at the top of the file.)

- [ ] **Step 3: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ui/loading/Skeletons.kt
git commit -m "feat(client): skeleton placeholder components"
```

---

## Task 3: Error + empty state views

**Files:**
- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ui/loading/StateViews.kt`

**Interfaces:**
- Produces: `ErrorRetry(message: String?, onRetry: () -> Unit, retrying: Boolean = false, modifier)`, `EmptyState(title: String, body: String, modifier)`.

- [ ] **Step 1: Create the file**

`StateViews.kt`:
```kotlin
package com.sloopworks.dayfold.client.ui.loading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Inline failure with a retry. `retrying` shows a spinner inside the button. */
@Composable
fun ErrorRetry(message: String?, onRetry: () -> Unit, retrying: Boolean = false, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  Column(
    modifier.fillMaxWidth().padding(40.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Something went wrong", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
    Text(
      message ?: "Please try again.",
      style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
    )
    Button(onClick = onRetry, enabled = !retrying) {
      if (retrying) CircularProgressIndicator(strokeWidth = 2.dp, color = cs.onPrimary, modifier = Modifier.size(18.dp))
      else Text("Try again")
    }
  }
}

/** Calm empty-list state. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  Column(
    modifier.fillMaxWidth().padding(40.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, textAlign = TextAlign.Center)
    Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
  }
}
```

- [ ] **Step 2: Compile**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ui/loading/StateViews.kt
git commit -m "feat(client): error-retry + empty-state views"
```

---

## Task 4: Anti-flash helper

**Files:**
- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ui/loading/StableLoading.kt`

**Interfaces:**
- Produces: `@Composable fun rememberStableLoading(loading: Boolean, delayMs: Long = 200): Boolean` — stays false until `loading` has been true continuously for `delayMs` (no min-floor: flips false immediately when loading ends).

- [ ] **Step 1: Create the file**

`StableLoading.kt`:
```kotlin
package com.sloopworks.dayfold.client.ui.loading

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Debounce a loading flag so fast responses never flash a skeleton: returns true
 * only after [loading] has held for [delayMs]; returns false the instant loading ends.
 */
@Composable
fun rememberStableLoading(loading: Boolean, delayMs: Long = 200): Boolean {
  var shown by remember { mutableStateOf(false) }
  LaunchedEffect(loading) {
    if (loading) { delay(delayMs); shown = true } else shown = false
  }
  return shown && loading
}
```

- [ ] **Step 2: Compile**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ui/loading/StableLoading.kt
git commit -m "feat(client): rememberStableLoading anti-flash helper"
```

---

## Task 5: Busy indicators (RowBusy + FullScreenLoading) and SplashScreen consolidation

**Files:**
- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ui/loading/BusyIndicators.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AuthScreens.kt:143-153` (SplashScreen)

**Interfaces:**
- Produces: `RowBusy(modifier)` — a 48dp touch-footprint box holding an 18dp spinner (drop-in replacement for a row's trailing action while in flight). `FullScreenLoading(content: @Composable () -> Unit)` — centered mark+spinner column.

- [ ] **Step 1: Create BusyIndicators.kt**

```kotlin
package com.sloopworks.dayfold.client.ui.loading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** In-row busy spinner sized to a 48dp touch target (no layout jump vs an icon button). */
@Composable
fun RowBusy(modifier: Modifier = Modifier) {
  Box(
    modifier.size(48.dp).semantics { contentDescription = "Working" },
    contentAlignment = Alignment.Center,
  ) {
    CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
  }
}

/** Full-screen blocking spinner over the app surface. */
@Composable
fun FullScreenLoading(content: @Composable () -> Unit) {
  val cs = MaterialTheme.colorScheme
  Box(Modifier.fillMaxSize().background(cs.surface), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
      content()
      CircularProgressIndicator(color = cs.primary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
    }
  }
}
```

- [ ] **Step 2: Refactor SplashScreen to use FullScreenLoading**

In `AuthScreens.kt`, replace the body of `SplashScreen` (lines 144-153) with:
```kotlin
@Composable
fun SplashScreen() {
  com.sloopworks.dayfold.client.ui.loading.FullScreenLoading { DayfoldMark(size = 64) }
}
```
(Keep the `// ── Splash …` comment above it.)

- [ ] **Step 3: Compile + run the existing splash snapshot to confirm no regression**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest --tests "com.sloopworks.dayfold.client.AuthScreensSnapshotTest.splash"`
Expected: PASS (writes `build/snapshots/auth-splash.png`).

- [ ] **Step 4: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ui/loading/BusyIndicators.kt apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AuthScreens.kt
git commit -m "feat(client): RowBusy + FullScreenLoading; fold SplashScreen into it"
```

---

## Task 6: Busy-aware buttons (AuthButton, PillButton)

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AuthScreens.kt:88-118` (AuthButton)
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/DeviceApprovalScreens.kt:64-86` (PillButton)

**Interfaces:**
- Produces: `AuthButton(..., busy: Boolean = false)` and `PillButton(..., busy: Boolean = false)` — when busy: label stays, leading slot becomes a spinner, button disabled, `stateDescription="Busy"`.

- [ ] **Step 1: Add `busy` to AuthButton**

Replace `AuthButton` (lines 88-118) with:
```kotlin
internal fun AuthButton(
  text: String,
  container: Color,
  content: Color,
  modifier: Modifier = Modifier,
  border: Color? = null,
  enabled: Boolean = true,
  busy: Boolean = false,
  leading: @Composable (() -> Unit)? = null,
  onClick: () -> Unit = {},
) {
  val cs = MaterialTheme.colorScheme
  val effectiveEnabled = enabled && !busy
  Box(
    modifier
      .fillMaxWidth()
      .height(54.dp)
      .clip(RoundedCornerShape(16.dp))
      .background(if (effectiveEnabled) container else cs.surfaceContainerHigh)
      .then(if (border != null) Modifier.border(1.dp, border, RoundedCornerShape(16.dp)) else Modifier)
      .clickable(enabled = effectiveEnabled, onClick = onClick)
      .semantics { if (busy) stateDescription = "Busy" },
    contentAlignment = Alignment.Center,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
      if (busy) {
        CircularProgressIndicator(strokeWidth = 2.dp, color = content, modifier = Modifier.size(20.dp))
      } else if (leading != null) leading()
      Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (effectiveEnabled || busy) content else cs.onSurfaceVariant,
      )
    }
  }
}
```
Add these imports to `AuthScreens.kt` if absent: `androidx.compose.material3.CircularProgressIndicator`, `androidx.compose.foundation.layout.size`, `androidx.compose.ui.semantics.semantics`, `androidx.compose.ui.semantics.stateDescription`.

- [ ] **Step 2: Add `busy` to PillButton**

Replace `PillButton` (lines 64-86) with:
```kotlin
@Composable
internal fun PillButton(
  text: String,
  container: Color,
  content: Color,
  modifier: Modifier = Modifier,
  border: Color? = null,
  enabled: Boolean = true,
  busy: Boolean = false,
  onClick: () -> Unit = {},
) {
  val cs = MaterialTheme.colorScheme
  val effectiveEnabled = enabled && !busy
  Box(
    modifier
      .height(52.dp)
      .clip(RoundedCornerShape(16.dp))
      .background(if (effectiveEnabled) container else cs.surfaceContainerHigh)
      .then(if (border != null) Modifier.border(1.5.dp, border, RoundedCornerShape(16.dp)) else Modifier)
      .clickable(enabled = effectiveEnabled, onClick = onClick)
      .semantics { if (busy) stateDescription = "Busy" },
    contentAlignment = Alignment.Center,
  ) {
    androidx.compose.foundation.layout.Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(9.dp),
    ) {
      if (busy) androidx.compose.material3.CircularProgressIndicator(strokeWidth = 2.dp, color = content, modifier = Modifier.size(18.dp))
      Text(text, style = MaterialTheme.typography.titleSmall, color = if (effectiveEnabled || busy) content else cs.onSurfaceVariant)
    }
  }
}
```
Add imports to `DeviceApprovalScreens.kt` if absent: `androidx.compose.foundation.layout.size`, `androidx.compose.ui.semantics.semantics`, `androidx.compose.ui.semantics.stateDescription`. (`CircularProgressIndicator` is already imported at line 23.)

- [ ] **Step 3: Compile**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AuthScreens.kt apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/DeviceApprovalScreens.kt
git commit -m "feat(client): busy spinner support in AuthButton + PillButton"
```

---

## Task 7: Redux state — new fields, actions, reducer, engine

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Model.kt:283-339` (AppState), `:399-409` (actions)
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Reducer.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AuthEngine.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/LoadingReducerTest.kt`

**Interfaces:**
- Produces (AppState fields): `pendingProvider: String?`, `signOutBusy: Boolean`, `rosterBusy: Boolean`, `rosterError: String?`, `memberOpId: String?`, `deviceListBusy: Boolean`, `deviceListError: String?`, `deviceOpId: String?`, `audienceError: String?`.
- Produces (actions): `MemberOpRequested(uid)`, `RosterRequested`, `RosterFailed(message)`, `DeviceOpRequested(id)`, `DevicesRequested`, `DevicesFailed(message)`, `AudienceFailed(message)`.

- [ ] **Step 1: Write the failing reducer test**

Create `LoadingReducerTest.kt`:
```kotlin
package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class LoadingReducerTest {
  @Test fun signInRequestedSetsPendingProvider() {
    val s = rootReducer(AppState(), SignInRequested("google"))
    assertEquals("google", s.pendingProvider)
    assertTrue(s.authBusy)
  }

  @Test fun signInFailedClearsPendingProvider() {
    val s = rootReducer(AppState(pendingProvider = "google", authBusy = true), SignInFailed("nope"))
    assertNull(s.pendingProvider)
    assertFalse(s.authBusy)
  }

  @Test fun signOutRequestedSetsBusy() {
    assertTrue(rootReducer(AppState(), SignOutRequested).signOutBusy)
  }

  @Test fun memberOpRequestedThenResolvedClearsId() {
    val a = rootReducer(AppState(), MemberOpRequested("u1"))
    assertEquals("u1", a.memberOpId)
    val b = rootReducer(a, MemberResolved("u1"))
    assertNull(b.memberOpId)
  }

  @Test fun memberOpClearedOnApprovalsFailed() {
    assertNull(rootReducer(AppState(memberOpId = "u1"), ApprovalsFailed).memberOpId)
  }

  @Test fun rosterRequestedFailedFlow() {
    val a = rootReducer(AppState(), RosterRequested)
    assertTrue(a.rosterBusy)
    val b = rootReducer(a, RosterFailed("x"))
    assertFalse(b.rosterBusy); assertEquals("x", b.rosterError); assertNull(b.memberOpId)
  }

  @Test fun deviceOpRequestedThenRevokedClearsId() {
    val a = rootReducer(AppState(), DeviceOpRequested("c1"))
    assertEquals("c1", a.deviceOpId)
    assertNull(rootReducer(a, DeviceRevoked("c1")).deviceOpId)
  }

  @Test fun devicesRequestedFailedFlow() {
    val a = rootReducer(AppState(), DevicesRequested)
    assertTrue(a.deviceListBusy)
    val b = rootReducer(a, DevicesFailed("x"))
    assertFalse(b.deviceListBusy); assertEquals("x", b.deviceListError)
  }

  @Test fun audienceFailedSetsError() {
    assertEquals("x", rootReducer(AppState(), AudienceFailed("x")).audienceError)
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest --tests "com.sloopworks.dayfold.client.LoadingReducerTest"`
Expected: FAIL (unresolved references `pendingProvider`, `MemberOpRequested`, etc.).

- [ ] **Step 3: Add the AppState fields**

In `Model.kt`, inside `data class AppState(` add these fields (place near the related slices, before the closing `)` at line 339):
```kotlin
  // loading-state additions (2026-06-28)
  val pendingProvider: String? = null,   // which sign-in provider button spins
  val signOutBusy: Boolean = false,
  val rosterBusy: Boolean = false,
  val rosterError: String? = null,
  val memberOpId: String? = null,        // member row currently approving/declining/removing
  val deviceListBusy: Boolean = false,
  val deviceListError: String? = null,
  val deviceOpId: String? = null,        // device row currently revoking
  val audienceError: String? = null,
```

- [ ] **Step 4: Add the actions**

In `Model.kt`, in the owner-side approvals / devices action block (after line 409, near `ApprovalsFailed`), add:
```kotlin
data class MemberOpRequested(val uid: String) : Action   // approve/decline/remove start
data object RosterRequested : Action
data class RosterFailed(val message: String) : Action
data class DeviceOpRequested(val id: String) : Action    // revoke start
data object DevicesRequested : Action
data class DevicesFailed(val message: String) : Action
data class AudienceFailed(val message: String) : Action
```

- [ ] **Step 5: Reduce the new + amended actions**

In `Reducer.kt`:

Replace `is SignInRequested -> ...` (line 77) with:
```kotlin
  is SignInRequested -> state.copy(authBusy = true, authError = null, pendingProvider = action.provider)
```
Replace `is SignInSucceeded -> ...` (lines 78-81) and `is SignInFailed -> ...` (line 82):
```kotlin
  is SignInSucceeded -> state.copy(
    session = action.session, authBusy = false, authError = null, pendingProvider = null,
    route = Route.Loading,
  )
  is SignInFailed -> state.copy(authBusy = false, authError = action.message, pendingProvider = null)
```
Replace the `// SignOutRequested is an effect trigger …` comment (line 104) with a real branch:
```kotlin
  is SignOutRequested -> state.copy(signOutBusy = true)
```
Replace `is RosterLoaded -> ...` (line 118):
```kotlin
  is RosterLoaded -> state.copy(members = action.members, rosterBusy = false, rosterError = null, memberOpId = null)
```
Replace `is MemberRemoved -> ...` (line 119):
```kotlin
  is MemberRemoved -> state.copy(members = state.members.filterNot { it.uid == action.uid }, memberOpId = null)
```
Replace `is DevicesLoaded -> ...` (line 121):
```kotlin
  is DevicesLoaded -> state.copy(devices = action.devices, deviceListBusy = false, deviceListError = null, deviceOpId = null)
```
Replace `is DeviceRevoked -> ...` (line 122):
```kotlin
  is DeviceRevoked -> state.copy(devices = state.devices.filterNot { it.id == action.id }, deviceOpId = null)
```
Replace `is MemberResolved -> ...` (line 125):
```kotlin
  is MemberResolved -> state.copy(pendingApprovals = state.pendingApprovals.filterNot { it.uid == action.uid }, memberOpId = null)
```
Replace `is ApprovalsFailed -> ...` (line 126):
```kotlin
  is ApprovalsFailed -> state.copy(approvalsBusy = false, memberOpId = null)
```
Replace `is OpenAudienceSheet -> ...` (line 67):
```kotlin
  is OpenAudienceSheet -> state.copy(audienceSheetOpen = true, currentHubAudience = null, audienceError = null)
```
Replace `is HubAudienceLoaded -> ...` (line 68):
```kotlin
  is HubAudienceLoaded -> state.copy(currentHubAudience = action.audience, audienceError = null)
```
Replace `is CloseAudienceSheet -> ...` (line 69):
```kotlin
  is CloseAudienceSheet -> state.copy(audienceSheetOpen = false, currentHubAudience = null, audienceError = null)
```
Add these new branches anywhere in the owner-side approvals section (e.g. after line 126):
```kotlin
  is MemberOpRequested -> state.copy(memberOpId = action.uid)
  is RosterRequested -> state.copy(rosterBusy = true, rosterError = null)
  is RosterFailed -> state.copy(rosterBusy = false, rosterError = action.message, memberOpId = null)
  is DeviceOpRequested -> state.copy(deviceOpId = action.id)
  is DevicesRequested -> state.copy(deviceListBusy = true, deviceListError = null)
  is DevicesFailed -> state.copy(deviceListBusy = false, deviceListError = action.message, deviceOpId = null)
  is AudienceFailed -> state.copy(audienceError = action.message)
```

- [ ] **Step 6: Run reducer test to verify it passes**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest --tests "com.sloopworks.dayfold.client.LoadingReducerTest"`
Expected: PASS (10 tests).

- [ ] **Step 7: Wire the engine to dispatch the new actions**

In `AuthEngine.kt`:

`resolveMember` (lines 151-159) — dispatch the op-start; the catch already dispatches `ApprovalsFailed` (clears the id):
```kotlin
  private suspend fun resolveMember(fid: String, uid: String, approve: Boolean) = mutex.withLock {
    val session = store.state.session ?: return@withLock
    store.dispatch(MemberOpRequested(uid))
    try {
      callWithRefresh(session) { if (approve) authClient.approveMember(it.access, fid, uid) else authClient.declineMember(it.access, fid, uid) }
      store.dispatch(MemberResolved(uid))
    } catch (e: Exception) {
      store.dispatch(ApprovalsFailed)
    }
  }
```

`loadMembers` (lines 162-167) — dispatch Requested + Failed:
```kotlin
  suspend fun loadMembers(fid: String) = mutex.withLock {
    val session = store.state.session ?: return@withLock
    store.dispatch(RosterRequested)
    try {
      store.dispatch(RosterLoaded(callWithRefresh(session) { authClient.familyMembers(it.access, fid) }))
    } catch (e: Exception) { store.dispatch(RosterFailed("Couldn't load members. Try again.")) }
  }
```

`removeMember` (lines 170-178) — dispatch the op-start; the catch reloads roster (`RosterLoaded` clears the id):
```kotlin
  suspend fun removeMember(fid: String, uid: String) = mutex.withLock {
    val session = store.state.session ?: return@withLock
    store.dispatch(MemberOpRequested(uid))
    try {
      callWithRefresh(session) { authClient.removeMember(it.access, fid, uid) }
      store.dispatch(MemberRemoved(uid))
    } catch (e: Exception) {
      store.dispatch(RosterLoaded(runCatching { callWithRefresh(session) { authClient.familyMembers(it.access, fid) } }.getOrDefault(store.state.members)))
    }
  }
```

`loadDevices` (lines 181-185) — Requested + Failed:
```kotlin
  suspend fun loadDevices() = mutex.withLock {
    val session = store.state.session ?: return@withLock
    store.dispatch(DevicesRequested)
    try { store.dispatch(DevicesLoaded(callWithRefresh(session) { authClient.credentials(it.access) })) }
    catch (e: Exception) { store.dispatch(DevicesFailed("Couldn't load devices. Try again.")) }
  }
```

`revokeDevice` (lines 188-196) — op-start; catch reloads (`DevicesLoaded` clears the id):
```kotlin
  suspend fun revokeDevice(id: String) = mutex.withLock {
    val session = store.state.session ?: return@withLock
    store.dispatch(DeviceOpRequested(id))
    try {
      callWithRefresh(session) { authClient.revokeCredential(it.access, id) }
      store.dispatch(DeviceRevoked(id))
    } catch (e: Exception) {
      store.dispatch(DevicesLoaded(runCatching { callWithRefresh(session) { authClient.credentials(it.access) } }.getOrDefault(store.state.devices)))
    }
  }
```

- [ ] **Step 8: Wire audience failure**

Find the suspend function wired to `onLoadAudience` (grep): `cd apps && grep -rn "onLoadAudience" client/src/commonMain` and follow it to the engine method that dispatches `HubAudienceLoaded` (likely in `HubEngine.kt`). Wrap its body in `try { … } catch (e: Exception) { store.dispatch(AudienceFailed("Couldn't load who can see this. Try again.")) }`, matching the existing `loadApprovals` try/catch shape. Show the edited function in the commit.

- [ ] **Step 9: Compile + run full reducer test + existing tests**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest`
Expected: PASS (all existing + new). If an existing reducer test asserted on the old `SignOutRequested` no-op, update it to expect `signOutBusy = true`.

- [ ] **Step 10: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Model.kt apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Reducer.kt apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AuthEngine.kt apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/HubEngine.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/LoadingReducerTest.kt
git commit -m "feat(client): loading-state fields + actions + engine dispatch"
```

---

## Task 8: Sign-in per-provider spinner

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AuthScreens.kt:185-242` (SignInScreen)
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedApp.kt` (SignInScreen call, ~line 113)

**Interfaces:**
- Consumes: `AuthButton(busy=)` (Task 6), `AppState.pendingProvider` (Task 7).
- Changes `SignInScreen` signature: replace `busy: Boolean` with `pendingProvider: String? = null`.

- [ ] **Step 1: Update SignInScreen signature + buttons**

In `SignInScreen` (lines 185-242): change the signature param `busy: Boolean = false,` to `pendingProvider: String? = null,`. Add `val busy = pendingProvider != null` as the first line of the function body. Update the three `AuthButton` calls:
- Google (line 219-223): `enabled = !busy, busy = pendingProvider == "google", leading = { GoogleGlyph() },`
- Apple (line 224-227): `enabled = !busy, busy = pendingProvider == "apple", leading = { AppleGlyph(cs.surface) },`
- Dev (line 229-232): `enabled = !busy, busy = pendingProvider == "dev",`

(The `error?.let { … }` block and the rest stay unchanged — `busy` is still referenced by them via the new local val.)

- [ ] **Step 2: Update the FeedApp call site**

In `FeedApp.kt`, the `Route.SignIn` branch (~line 113), change:
```kotlin
        else SignInScreen(busy = state.authBusy, error = state.authError, onProvider = onSignIn, onDevSignIn = onDevSignIn)
```
to:
```kotlin
        else SignInScreen(pendingProvider = state.pendingProvider, error = state.authError, onProvider = onSignIn, onDevSignIn = onDevSignIn)
```

- [ ] **Step 3: Update the existing busy snapshot test**

In `AuthScreensSnapshotTest.kt`, change `signInBusy` (line 30):
```kotlin
  @Test fun signInBusy() = snap("auth-signin-busy") { SignInScreen(pendingProvider = "google") }
```

- [ ] **Step 4: Compile + run sign-in snapshots**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest --tests "com.sloopworks.dayfold.client.AuthScreensSnapshotTest.signIn*"`
Expected: PASS. Inspect `build/snapshots/auth-signin-busy.png` — the Google button shows a spinner, others disabled.

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AuthScreens.kt apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedApp.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AuthScreensSnapshotTest.kt
git commit -m "feat(client): per-provider sign-in spinner"
```

---

## Task 9: Sign-out busy

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AccountScreen.kt:44-51` (signature), `:151-155` (sign-out box)
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedApp.kt` (AccountScreen call, ~line 156)

**Interfaces:**
- Consumes: `AppState.signOutBusy` (Task 7).
- Changes `AccountScreen` signature: add `signOutBusy: Boolean = false`.

- [ ] **Step 1: Add the param + busy sign-out button**

In `AccountScreen.kt`, add `signOutBusy: Boolean = false,` to the signature (after `state: AppState,`). Replace the sign-out Box (lines 151-155) with:
```kotlin
      Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
          .border(1.5.dp, cs.outline, RoundedCornerShape(16.dp))
          .clickable(enabled = !signOutBusy) { confirmSignOut = true }
          .semantics { if (signOutBusy) stateDescription = "Busy" },
        contentAlignment = Alignment.Center,
      ) {
        if (signOutBusy) {
          androidx.compose.material3.CircularProgressIndicator(strokeWidth = 2.dp, color = cs.onSurface, modifier = Modifier.size(20.dp))
        } else {
          Text("Sign out", style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
        }
      }
```
Add imports if absent: `androidx.compose.foundation.layout.size`, `androidx.compose.ui.semantics.semantics`, `androidx.compose.ui.semantics.stateDescription`.

- [ ] **Step 2: Pass the flag from FeedApp**

In `FeedApp.kt`, the `Route.Account` branch (~line 156), add `signOutBusy = state.signOutBusy,`:
```kotlin
      Route.Account -> AccountScreen(
        state, signOutBusy = state.signOutBusy, onSignOut = onSignOut, onClose = { store.dispatch(CloseAccount) },
        onOpenMembers = { store.dispatch(OpenMembers) },
        onOpenDevices = { store.dispatch(OpenDevices) },
      )
```

- [ ] **Step 3: Add a snapshot for the busy state**

In `AuthScreensSnapshotTest.kt`, after `accountDark` add:
```kotlin
  @Test fun accountSignOutBusy() = snap("auth-account-signout-busy") { AccountScreen(acctState, signOutBusy = true) }
```

- [ ] **Step 4: Compile + run**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest --tests "com.sloopworks.dayfold.client.AuthScreensSnapshotTest.account*"`
Expected: PASS. Inspect `build/snapshots/auth-account-signout-busy.png`.

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AccountScreen.kt apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedApp.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AuthScreensSnapshotTest.kt
git commit -m "feat(client): sign-out busy spinner"
```

---

## Task 10: Button-busy for join / enter-code / approve-device

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/JoinInviteScreen.kt:108-123`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/DeviceApprovalScreens.kt:199-204, 322-335`

**Interfaces:**
- Consumes: `PillButton(busy=)` (Task 6).

- [ ] **Step 1: JoinEntry — spinner instead of "Joining…"**

In `JoinInviteScreen.kt`, replace the button `Box` (lines 108-123) with one that keeps the "Join" label and adds a spinner:
```kotlin
  Box(
    Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp))
      .background(if (!busy && token.isNotBlank()) cs.primary else cs.surfaceContainerHigh)
      .clickable(enabled = !busy && token.isNotBlank()) { onJoin(inviteTokenOf(token)) }
      .semantics { if (busy) stateDescription = "Busy" },
    contentAlignment = Alignment.Center,
  ) {
    androidx.compose.foundation.layout.Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(9.dp),
    ) {
      if (busy) androidx.compose.material3.CircularProgressIndicator(strokeWidth = 2.dp, color = cs.onPrimary, modifier = Modifier.size(18.dp))
      Text(
        "Join",
        style = MaterialTheme.typography.labelLarge,
        color = if (!busy && token.isNotBlank()) cs.onPrimary else cs.onSurfaceVariant,
      )
    }
  }
```
Add imports if absent: `androidx.compose.foundation.layout.size`, `androidx.compose.ui.semantics.semantics`, `androidx.compose.ui.semantics.stateDescription`, `androidx.compose.ui.Alignment`.

- [ ] **Step 2: EnterCode Continue — busy spinner, drop "Checking…"**

In `DeviceApprovalScreens.kt`, replace the Continue `PillButton` (lines 199-204):
```kotlin
    PillButton(
      "Continue",
      container = cs.primary, content = cs.onPrimary, enabled = ready, busy = state.deviceBusy,
      modifier = Modifier.fillMaxWidth().testTag("device-continue"), onClick = { submit() },
    )
```
Note: `ready` (line 160) is `code.length == CODE_LEN && !state.deviceBusy` — keep as-is; `busy` now drives the spinner.

- [ ] **Step 3: AuthorizeDevice Approve — busy spinner, drop "Working…"**

Replace the Approve `PillButton` (lines 330-334):
```kotlin
        PillButton(
          "Approve", container = cs.primary, content = cs.onPrimary, enabled = canApprove, busy = state.deviceBusy,
          modifier = Modifier.weight(1.2f).testTag("device-approve"),
          onClick = { selectedFid?.let { onApprove(it) } },
        )
```

- [ ] **Step 4: Compile + run device + join snapshots**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest --tests "com.sloopworks.dayfold.client.AuthScreensSnapshotTest.enterCode*" --tests "com.sloopworks.dayfold.client.AuthScreensSnapshotTest.authorize*" --tests "com.sloopworks.dayfold.client.AuthScreensSnapshotTest.join*"`
Expected: PASS (existing snapshots still render; label no longer morphs).

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/JoinInviteScreen.kt apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/DeviceApprovalScreens.kt
git commit -m "feat(client): button-busy spinners for join/enter-code/approve-device"
```

---

## Task 11: Feed — skeleton + pull-to-refresh

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedScreen.kt:42-100`

**Interfaces:**
- Consumes: `FeedSkeleton` (Task 2), `rememberStableLoading` (Task 4), `PullToRefreshBox`.

- [ ] **Step 1: Empty branch → skeleton**

In `FeedScreen.kt`, replace the empty-cards `Box` (the `if (state.cards.isEmpty()) { ... }` block, lines ~78-87) with:
```kotlin
    if (state.cards.isEmpty()) {
      when {
        rememberStableLoading(state.syncing) ->
          FeedSkeleton(Modifier.padding(pad))
        state.error != null ->
          Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { EmptyFeedError(state.error, onRefresh) }
        else ->
          Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { FamilyNullState(onConnectDevice = onConnectDevice) }
      }
    } else {
```
Add imports: `com.sloopworks.dayfold.client.ui.loading.FeedSkeleton`, `com.sloopworks.dayfold.client.ui.loading.rememberStableLoading`.

- [ ] **Step 2: Populated branch → wrap LazyColumn in PullToRefreshBox**

Replace the populated `LazyColumn(...)` block (lines ~88-100) with:
```kotlin
    } else {
      androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = state.syncing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize().padding(pad),
      ) {
        LazyColumn(
          Modifier.fillMaxSize(),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          if (state.error != null) item(key = "sync-error") { RefreshErrorBanner(onRefresh) }
          items(feedCards(state, kotlin.time.Clock.System.now().toString()), key = { it.id }) { card ->
            if (card.type != null) TypedCardItem(card, onAction) else CardItem(card)
          }
        }
      }
    }
```
The `@OptIn(ExperimentalMaterial3Api::class)` annotation already present on `FeedScreen` (line 42) covers `PullToRefreshBox`.

- [ ] **Step 3: Compile + run feed snapshots**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:compileKotlinDesktop && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest --tests "com.sloopworks.dayfold.client.FeedSnapshotTest"`
Expected: BUILD SUCCESSFUL; existing feed snapshots PASS. (Skeleton only shows after the 200ms debounce, so a default-render snapshot of an empty syncing feed may show nothing — that's expected; the skeleton is verified directly in Task 17.)

- [ ] **Step 4: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedScreen.kt
git commit -m "feat(client): feed skeleton + pull-to-refresh"
```

---

## Task 12: Hubs — skeletons + error states + audience

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/HubScreens.kt:90-114` (list), `:221-225` (detail), `:359-363` (audience)

**Interfaces:**
- Consumes: `ListSkeleton`, `ErrorRetry` (Tasks 2-3), `AppState.hubError`/`audienceError`.
- Note: identify the list/detail reload callbacks already passed to these composables (grep their signatures for the hub-load lambdas — they receive `onOpenHub`/the hubs-host wiring). Use the available reload lambda for `onRetry`; if the list screen has none, dispatch is wired in `HubsHost` (`FeedApp.kt`) via `onLoadHubs` — thread it in as a new `onRetry: () -> Unit = {}` param the same way `onOpenHub` is passed.

- [ ] **Step 1: HubListScreen loading/error**

Replace the `state.hubs.isEmpty() && state.hubsBusy ->` arm and add an error arm (lines 90-101):
```kotlin
      when {
        state.hubs.isEmpty() && state.hubsBusy -> ListSkeleton(rows = 4, modifier = Modifier.fillMaxSize())
        state.hubs.isEmpty() && state.hubError != null ->
          Box(Modifier.fillMaxSize(), Alignment.Center) { ErrorRetry(state.hubError, onRetry = onRetry) }
        state.hubs.isEmpty() ->
          Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(
              "When a big family event shows up — a trip, a move, a birthday — a hub appears here.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(40.dp),
            )
          }
```
Add the `onRetry` param to `HubListScreen` if it lacks a reload lambda (default `{}`), and wire it from `HubsHost` in `FeedApp.kt` to `onLoadHubs`. Imports: `com.sloopworks.dayfold.client.ui.loading.ListSkeleton`, `com.sloopworks.dayfold.client.ui.loading.ErrorRetry`.

- [ ] **Step 2: HubDetailScreen loading/error**

Replace lines 221-225:
```kotlin
    when {
      tree == null && state.hubsBusy -> ListSkeleton(rows = 5, modifier = Modifier.fillMaxSize().padding(pad))
      tree == null -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
        ErrorRetry(state.hubError ?: "Hub unavailable", onRetry = { onOpenHub(state.currentHubId ?: "") })
      }
      else -> {
```
(`onOpenHub` is already a param of `HubDetailScreen`; re-opening reloads the tree.)

- [ ] **Step 3: WhoCanSeeSheet audience**

Replace lines 359-363:
```kotlin
        when {
          state.audienceError != null ->
            ErrorRetry(state.audienceError, onRetry = onRetryAudience)
          aud == null ->
            androidx.compose.material3.CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
          else -> aud.members.forEach { m -> AudienceRow(m, isYou = m.uid == state.session?.userId) }
        }
```
Add an `onRetryAudience: () -> Unit = {}` param to `WhoCanSeeSheet` and wire it (in `HubsHost`/`FeedApp.kt`) to the same lambda as `onLoadAudience`. Imports: `androidx.compose.foundation.layout.size`.

- [ ] **Step 4: Compile + run hub snapshots**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:compileKotlinDesktop && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest --tests "com.sloopworks.dayfold.client.HubSnapshotTest"`
Expected: BUILD SUCCESSFUL; existing hub snapshots PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/HubScreens.kt apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedApp.kt
git commit -m "feat(client): hub list/detail skeletons + error + audience states"
```

---

## Task 13: Members — skeleton + per-row busy + error

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/MembersScreen.kt:37-143`

**Interfaces:**
- Consumes: `ListSkeleton`, `ErrorRetry`, `RowBusy`; `AppState.rosterBusy`/`rosterError`/`memberOpId`.

- [ ] **Step 1: List loading/error in the body**

In `MembersScreen` body, replace the MEMBERS section (lines 71-86, the `Label("MEMBERS"...)` block) with:
```kotlin
      Label("MEMBERS", cs.onSurfaceVariant)
      when {
        state.members.isEmpty() && state.rosterBusy -> ListSkeleton(rows = 3)
        state.members.isEmpty() && state.rosterError != null -> ErrorRetry(state.rosterError, onRetry = onLoadMembers)
        state.members.isNotEmpty() ->
          Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            state.members.forEach { m ->
              MemberRow(m, isOwner = m.role == "owner", busy = m.uid == state.memberOpId, anyBusy = state.memberOpId != null, onRemove = onRemoveMember)
            }
          }
        else -> MemberRow(
          FamilyMember(uid = "me", displayName = "You", role = me?.role ?: "adult"),
          isOwner = me?.role == "owner", busy = false, anyBusy = false, onRemove = {},
        )
      }
```
Imports: `com.sloopworks.dayfold.client.ui.loading.ListSkeleton`, `com.sloopworks.dayfold.client.ui.loading.ErrorRetry`, `com.sloopworks.dayfold.client.ui.loading.RowBusy`.

- [ ] **Step 2: PendingRow per-row busy**

Update `PendingRow` (lines 117-143): change signature to `private fun PendingRow(p: PendingMember, busy: Boolean, anyBusy: Boolean, onApprove: (String) -> Unit, onDecline: (String) -> Unit)`. While `busy`, replace BOTH action boxes with a single `RowBusy()`; otherwise disable taps when `anyBusy`. Replace the two action `Box`es (decline + approve) with:
```kotlin
    if (busy) {
      RowBusy()
    } else {
      Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(cs.surfaceContainerHigh)
          .testTag("decline-${p.uid}").clickable(enabled = !anyBusy) { onDecline(p.uid) }
          .semantics { contentDescription = "Decline ${p.displayName ?: "request"}" },
        contentAlignment = Alignment.Center,
      ) { Text("✕", color = cs.error, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {}) }
      Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(cs.primary)
          .testTag("approve-${p.uid}").clickable(enabled = !anyBusy) { onApprove(p.uid) }
          .semantics { contentDescription = "Approve ${p.displayName ?: "request"}" },
        contentAlignment = Alignment.Center,
      ) { Text("✓", color = cs.onPrimary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {}) }
    }
```
Update the `PendingRow` call site (line 67): `state.pendingApprovals.forEach { p -> PendingRow(p, busy = p.uid == state.memberOpId, anyBusy = state.memberOpId != null, onApprove, onDecline) }`.

- [ ] **Step 3: MemberRow per-row busy**

Update `MemberRow` (lines 90-115): change signature to `private fun MemberRow(m: FamilyMember, isOwner: Boolean, busy: Boolean, anyBusy: Boolean, onRemove: (String) -> Unit)`. In the `else` (non-owner) branch, replace the remove `Box` with:
```kotlin
      if (busy) RowBusy() else Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(cs.surfaceContainerHigh)
          .testTag("remove-${m.uid}").clickable(enabled = !anyBusy) { onRemove(m.uid) }
          .semantics { contentDescription = "Remove ${m.displayName ?: "member"}" },
        contentAlignment = Alignment.Center,
      ) { Text("✕", color = cs.error, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {}) }
```

- [ ] **Step 4: Compile + run members snapshot**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:compileKotlinDesktop && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest --tests "com.sloopworks.dayfold.client.AuthScreensSnapshotTest.members"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/MembersScreen.kt
git commit -m "feat(client): members skeleton + per-row busy + error state"
```

---

## Task 14: Devices — skeleton + per-row busy + error

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/DevicesScreen.kt:34-115`

**Interfaces:**
- Consumes: `ListSkeleton`, `ErrorRetry`, `RowBusy`; `AppState.deviceListBusy`/`deviceListError`/`deviceOpId`.

- [ ] **Step 1: List loading/error + per-row busy in the body**

Replace the device list `Column` (lines 80-82, the `Column(verticalArrangement = ...) { state.devices.forEach ... }`) with:
```kotlin
      Spacer(Modifier.height(9.dp))
      when {
        state.devices.isEmpty() && state.deviceListBusy -> ListSkeleton(rows = 3, modifier = Modifier.padding(top = 4.dp))
        state.devices.isEmpty() && state.deviceListError != null -> ErrorRetry(state.deviceListError, onRetry = onLoad)
        else -> Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
          state.devices.forEach { d -> DeviceRow(d, busy = d.id == state.deviceOpId, anyBusy = state.deviceOpId != null, onRevoke) }
        }
      }
```
Imports: `com.sloopworks.dayfold.client.ui.loading.ListSkeleton`, `com.sloopworks.dayfold.client.ui.loading.ErrorRetry`, `com.sloopworks.dayfold.client.ui.loading.RowBusy`.

- [ ] **Step 2: DeviceRow per-row busy**

Update `DeviceRow` (lines 89-115): signature → `private fun DeviceRow(d: DeviceCredential, busy: Boolean, anyBusy: Boolean, onRevoke: (String) -> Unit)`. Replace the revoke `Box` (the `if (!d.current) { Box(...) }` block) with:
```kotlin
    if (!d.current) {
      if (busy) RowBusy() else Box(
        Modifier.clip(RoundedCornerShape(50)).background(cs.surfaceContainerHigh)
          .testTag("revoke-${d.id}").clickable(enabled = !anyBusy) { onRevoke(d.id) }.padding(horizontal = 13.dp, vertical = 7.dp),
      ) { Text("Revoke", style = MaterialTheme.typography.labelLarge, color = cs.error) }
    }
```

- [ ] **Step 3: Compile + run devices snapshot**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:compileKotlinDesktop && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest --tests "com.sloopworks.dayfold.client.AuthScreensSnapshotTest.devices"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/DevicesScreen.kt
git commit -m "feat(client): devices skeleton + per-row revoke busy + error state"
```

---

## Task 15: Fake-backend latency knob

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/fake/FakeBackend.kt:49-61`
- Modify: `apps/client/src/desktopMain/kotlin/com/sloopworks/dayfold/client/fake/FakeHttpClient.kt:24-27`
- Modify: `apps/androidApp/src/debug/.../FakeBackend.kt:23-31`

**Interfaces:**
- Produces: `FakeBackendData.latencyMs: Long`; both MockEngine adapters `delay()` before responding so loading states are observable.

- [ ] **Step 1: Add the field**

In `FakeBackend.kt`, add to `FakeBackendData` (after `syncStatus`):
```kotlin
  /** Artificial per-request delay (debug-only) so loading states are observable. */
  val latencyMs: Long = 0,
```
Confirm `FakeBackend` exposes its data (it is constructed `FakeBackend(scenario.data)`); if the constructor param is private, change it to `class FakeBackend(val data: FakeBackendData)` so adapters can read `backend.data.latencyMs`.

- [ ] **Step 2: Desktop adapter — delay + env override**

Replace `FakeHttpClient.kt` lines 24-27:
```kotlin
fun fakeHttpClient(backend: FakeBackend): HttpClient = HttpClient(MockEngine { request ->
  val latency = System.getenv("DAYFOLD_FAKE_LATENCY")?.toLongOrNull() ?: backend.data.latencyMs
  if (latency > 0) kotlinx.coroutines.delay(latency)
  val res = backend.handle(request.method.value, request.url.encodedPath, request.url.parameters["user_code"])
  respond(res.json, HttpStatusCode.fromValue(res.status), headersOf(HttpHeaders.ContentType, "application/json"))
})
```

- [ ] **Step 3: Android debug adapter — delay**

Replace `androidApp/src/debug/.../FakeBackend.kt` lines 23-31:
```kotlin
fun fakeBackendClient(scenarioId: String): HttpClient? =
  FakeScenarios.byId(scenarioId)?.let { scenario ->
    val backend = FakeBackend(scenario.data.copy(latencyMs = 1200))
    HttpClient(MockEngine { request ->
      if (backend.data.latencyMs > 0) kotlinx.coroutines.delay(backend.data.latencyMs)
      val res = backend.handle(request.method.value, request.url.encodedPath, request.url.parameters["user_code"])
      respond(res.json, HttpStatusCode.fromValue(res.status), headersOf(HttpHeaders.ContentType, "application/json"))
    })
  }
```

- [ ] **Step 4: Compile both targets**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:compileKotlinDesktop :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/fake/FakeBackend.kt apps/client/src/desktopMain/kotlin/com/sloopworks/dayfold/client/fake/FakeHttpClient.kt "apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/FakeBackend.kt"
git commit -m "test(client): fake-backend latency knob for loading-state demos"
```
(Adjust the android path to its real location if different — `git status` will show it.)

---

## Task 16: Component snapshot tests

**Files:**
- Create: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/LoadingKitSnapshotTest.kt`

**Interfaces:**
- Consumes: every kit component (Tasks 1-5) + busy buttons (Task 6).

- [ ] **Step 1: Write the snapshot tests**

```kotlin
package com.sloopworks.dayfold.client

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import com.sloopworks.dayfold.client.ui.loading.EmptyState
import com.sloopworks.dayfold.client.ui.loading.ErrorRetry
import com.sloopworks.dayfold.client.ui.loading.FeedSkeleton
import com.sloopworks.dayfold.client.ui.loading.ListSkeleton
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LoadingKitSnapshotTest {
  private fun snap(name: String, dark: Boolean = false, content: @Composable () -> Unit) = runComposeUiTest {
    setContent { DayfoldTheme(darkTheme = dark) { content() } }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0, "snapshot has no pixels")
    val dir = File("build/snapshots").apply { mkdirs() }
    ImageIO.write(img.toAwtImage(), "png", File(dir, "$name.png"))
  }

  @Test fun listSkeleton() = snap("kit-list-skeleton") { ListSkeleton(rows = 4, modifier = Modifier.fillMaxWidth()) }
  @Test fun feedSkeleton() = snap("kit-feed-skeleton") { FeedSkeleton() }
  @Test fun errorRetry() = snap("kit-error-retry") { ErrorRetry("Couldn't load devices. Try again.", onRetry = {}) }
  @Test fun errorRetryBusy() = snap("kit-error-retry-busy") { ErrorRetry("Retrying", onRetry = {}, retrying = true) }
  @Test fun emptyState() = snap("kit-empty-state") { EmptyState("No devices yet", "Phones and CLIs you authorize show up here.") }
}
```
Note: `ListSkeleton`/`FeedSkeleton` shimmer animates; `captureToImage` grabs one frame — pixels are non-zero regardless. Reduced-motion is the desktop default (`false`), so the animated path is exercised.

- [ ] **Step 2: Run**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest --tests "com.sloopworks.dayfold.client.LoadingKitSnapshotTest"`
Expected: PASS (5 tests). Inspect PNGs in `apps/client/build/snapshots/kit-*.png`.

- [ ] **Step 3: Commit**

```bash
git add apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/LoadingKitSnapshotTest.kt
git commit -m "test(client): snapshot the loading component kit"
```

---

## Task 17: Screen loading-state snapshots + full suite

**Files:**
- Modify: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AuthScreensSnapshotTest.kt`

**Interfaces:**
- Consumes: the wired screens (Tasks 11-14).

- [ ] **Step 1: Add loading/error/empty screen snapshots**

In `AuthScreensSnapshotTest.kt`, add:
```kotlin
  @Test fun membersLoading() = snap("members-loading") {
    MembersScreen(AppState(
      families = listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")),
      activeFamilyId = "fam1", rosterBusy = true,
    ))
  }
  @Test fun membersError() = snap("members-error") {
    MembersScreen(AppState(
      families = listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")),
      activeFamilyId = "fam1", rosterError = "Couldn't load members. Try again.",
    ))
  }
  @Test fun membersRowBusy() = snap("members-row-busy") {
    MembersScreen(AppState(
      families = listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")),
      activeFamilyId = "fam1",
      pendingApprovals = listOf(PendingMember("u9", "Sam Rivera")),
      members = listOf(FamilyMember("u1", "Pat Jackson", role = "owner", status = "active")),
      memberOpId = "u9",
    ))
  }
  @Test fun devicesLoading() = snap("devices-loading") { DevicesScreen(AppState(deviceListBusy = true)) }
  @Test fun devicesError() = snap("devices-error") { DevicesScreen(AppState(deviceListError = "Couldn't load devices. Try again.")) }
  @Test fun devicesRowBusy() = snap("devices-row-busy") {
    DevicesScreen(AppState(
      devices = listOf(
        DeviceCredential("c1", kind = "app", label = "iPhone 15 Pro", current = true),
        DeviceCredential("c2", kind = "cli", label = "claude-code · CI"),
      ),
      deviceOpId = "c2",
    ))
  }
```

- [ ] **Step 2: Run the full desktopTest suite**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest`
Expected: PASS (all suites). Inspect the new `build/snapshots/members-*.png`, `devices-*.png`.

- [ ] **Step 3: Commit**

```bash
git add apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AuthScreensSnapshotTest.kt
git commit -m "test(client): snapshot member/device loading/error/busy states"
```

---

## Task 18: Full build + on-device smoke (verification)

**Files:** none (verification only).

- [ ] **Step 1: Full client + android build**

Run: `cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 2: Desktop fake-backend visual smoke (slow latency)**

Run: `cd apps && DAYFOLD_FAKE_LATENCY=1500 JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home DAYFOLD_API=fake://busy-family ./gradlew :client:run` (or the desktop run task; confirm with `./gradlew :client:tasks`). Observe: feed shows the skeleton on entry, hub list shows skeletons, members/devices show skeletons then content, pull-to-refresh spins. Sign-out shows the button spinner. Use `fake://sync-error` to see the feed error + retry, `fake://owner-approvals` for per-row approve spinners.

- [ ] **Step 3 (optional): On-device**

Run: `apps/scripts/ondevice-demo.sh` then exercise sign-in (Apple→dev path) → sign-out → members/devices. Tear down with `apps/scripts/ondevice-demo.sh --down`.

- [ ] **Step 4: Final commit (if any snapshot baselines need adding)**

```bash
git add -A && git commit -m "chore(client): loading-states verification pass" || echo "nothing to commit"
```

---

## Self-review (completed during authoring)

- **Spec coverage:** sign-in (T8), sign-out (T9), create-family/join/enter-code/approve-device button-busy (T6,T10), feed skeleton+pull-to-refresh+error/empty (T11), hubs skeletons+error+audience (T12), members skeleton+per-row+error (T13), devices skeleton+per-row+error (T14), shimmer+reduced-motion (T1), error/empty components (T3), anti-flash (T4), op-id state + missing actions (T7), fake latency knob (T15), snapshots+reducer tests (T7,T16,T17). All spec sections map to a task.
- **Type consistency:** `memberOpId`/`deviceOpId` (single nullable, not Set) used identically across Model/Reducer/Engine/screens; `RowBusy`/`ListSkeleton`/`ErrorRetry`/`EmptyState`/`FeedSkeleton`/`FullScreenLoading`/`shimmer`/`rememberStableLoading`/`rememberReduceMotion` names consistent across producer and consumer tasks.
- **Known read-first spots** (flagged inline, not placeholders): T7 step 8 (audience-load engine fn — grep `onLoadAudience`), T12 (hub reload lambdas — wire `onRetry` like existing `onOpenHub`), T15 step 3 (android debug FakeBackend exact path). These are localized, pattern-matching edits with the surrounding code quoted.
