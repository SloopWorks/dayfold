# CL-SNAP — Agent Snapshot Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the agent dev loop a headless `f(state) → Compose UI` snapshot pipeline — render any client screen from a named state to a PNG (+ text semantics) in ms, verify against goldens — so most iterations are verified in text and pixels are read only on drift.

**Architecture:** A scene registry lives in `:client` `desktopTest` (reusing the existing hand-built `AppState` fixtures + the state-based composables `FeedScreen`/`HubDetailScreen`/`DetailScreen`). `org.reduxkotlin:redux-kotlin-snapshot:1.0.0-alpha04` renders it headless via Compose `ImageComposeScene`. Two consumers of one registry: a JUnit `assertGolden` CI gate, and a `:client:snapshotUi` JavaExec the agent drives for on-demand render / semantics / batch.

**Tech Stack:** Kotlin 2.3.20 multiplatform (desktop/JVM target), Compose-MP 1.9.3, `redux-kotlin-*` 1.0.0-alpha03, `redux-kotlin-snapshot` 1.0.0-alpha04, JUnit-platform, Gradle 9.4.1 / AGP 9.2.1.

## Global Constraints

- **JDK 17** for all Gradle builds: `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home`.
- **Run every Gradle command from `apps/`** (single root; no per-module wrapper). Module tasks are `:client:<task>`.
- Pin the snapshot lib **exactly** `org.reduxkotlin:redux-kotlin-snapshot:1.0.0-alpha04`; scope it **`desktopTest` only** (never `desktopMain`/`androidMain`/`iosMain` — it must not ship).
- New code package: `com.sloopworks.dayfold.client.snapshot`, under `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/`.
- **`SnapshotApp.runCli(argv)` takes OPTIONS ONLY — no leading `"snapshot"` token.** (The lib's command *is* `snapshot`; the brew `rk snapshot …` prefix applies only to the unified `rk` binary. The examples in `processes/agent-dev-loop.md` that write `--args="snapshot …"` are wrong — see Task 8.)
- Any `main()` that calls `runCli` MUST follow it with `kotlin.system.exitProcess(0)` (Skiko leaves non-daemon threads alive).
- **Goldens live at `apps/client/src/desktopTest/resources/snapshots/`** (pass `goldenDir` explicitly — the lib default `src/test/resources/snapshots` is the wrong dir for a KMP `desktopTest` source set).
- Golden verify tolerance: `maxDiffPercent = 2.0` (brand fonts are bundled via `compose.components.resources`, so the only cross-arch variance dev-arm64↔CI-ubuntu is Skiko AA — small; 2% absorbs it while catching real layout breaks). Record on dev; CI (`ubuntu-latest`) verifies.
- CI runs `./gradlew --no-daemon :client:desktopTest` on `ubuntu-latest` (`.github/workflows/ci.yml`). New tests run there — keep them deterministic.

---

### Task 1: Add the snapshot dependency + prove headless render works

**Files:**
- Modify: `apps/client/build.gradle.kts` (the `desktopTest` sourceSet `dependencies { }`, currently lines ~102–111)
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/SnapshotLibSmokeTest.kt` (create)

**Interfaces:**
- Consumes: `org.reduxkotlin.snapshot.demoSnapshots: SnapshotApp`, `SnapshotApp.render(scene, input, …): RenderResult`, `SnapshotInput.Preset`, `RenderResult.png: ByteArray`.
- Produces: nothing downstream — this task only proves the dep resolves and the render engine runs.

- [ ] **Step 1: Write the failing test**

Create `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/SnapshotLibSmokeTest.kt`:

```kotlin
package com.sloopworks.dayfold.client.snapshot

import org.reduxkotlin.snapshot.SnapshotInput
import org.reduxkotlin.snapshot.demoSnapshots
import kotlin.test.Test
import kotlin.test.assertTrue

// Proves the redux-kotlin-snapshot dep resolves and its headless ImageComposeScene
// backend renders on this JVM (the lib ships a self-contained `demo` scene).
class SnapshotLibSmokeTest {
  @Test fun demoSceneRendersPngBytes() {
    val result = demoSnapshots.render("demo", SnapshotInput.Preset("default"))
    assertTrue(result.png.size > 100, "expected PNG bytes, got ${result.png.size}")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd apps && JAVA_HOME=<jdk17> ./gradlew :client:desktopTest --tests "*SnapshotLibSmokeTest"`
Expected: FAIL — compile error, unresolved reference `org.reduxkotlin.snapshot` (dep not yet added).

- [ ] **Step 3: Add the dependency**

In `apps/client/build.gradle.kts`, inside `val desktopTest by getting { dependencies { … } }`, add:

```kotlin
        // CL-SNAP: headless f(state)->UI render + golden diff + text semantics.
        // Test-scope ONLY (must not ship). JVM-only artifact (no target suffix).
        implementation("org.reduxkotlin:redux-kotlin-snapshot:1.0.0-alpha04")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd apps && JAVA_HOME=<jdk17> ./gradlew :client:desktopTest --tests "*SnapshotLibSmokeTest"`
Expected: PASS (1 test). If it fails to *resolve* the artifact, confirm `mavenCentral()` is in `apps/settings.gradle.kts` (it is, lines 7/15) and that the version `1.0.0-alpha04` exists on Central.

- [ ] **Step 5: Commit**

```bash
git add apps/client/build.gradle.kts apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/SnapshotLibSmokeTest.kt
git commit -m "CL-SNAP: add redux-kotlin-snapshot:1.0.0-alpha04 (desktopTest) + render smoke"
```

---

### Task 2: Gate the alpha04 semantics claim (decision point)

The whole "verify content in text, zero vision tokens" tier depends on `RenderResult.semantics` being **populated** in alpha04 (it was `SemanticsDump.EMPTY` in alpha02). This task proves or disproves that. **If Step 2 fails, STOP and read the fallback note before continuing.**

**Files:**
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/SemanticsDumpTest.kt` (create)

**Interfaces:**
- Consumes: `RenderResult.semantics: SemanticsDump`, `SemanticsDump.texts: List<String>`.
- Produces: the verified fact that Tier-0 semantics assertions are usable (Tasks 6+ rely on it) — or the fallback decision.

- [ ] **Step 1: Write the test**

```kotlin
package com.sloopworks.dayfold.client.snapshot

import org.reduxkotlin.snapshot.SnapshotInput
import org.reduxkotlin.snapshot.demoSnapshots
import kotlin.test.Test
import kotlin.test.assertTrue

// alpha04 gate: the `demo` scene renders the text "snapshot ok"; its semantics dump
// MUST contain that string. If empty -> semantics is not wired in this build -> fall
// back to compose-ui-test onNodeWithText for content asserts (see plan Task 2 note).
class SemanticsDumpTest {
  @Test fun demoSceneExposesRenderedText() {
    val dump = demoSnapshots.render("demo", SnapshotInput.Preset("default")).semantics
    assertTrue(dump.texts.any { it.contains("snapshot ok") },
      "semantics.texts was ${dump.texts} — expected to contain 'snapshot ok'")
  }
}
```

- [ ] **Step 2: Run the test**

Run: `cd apps && JAVA_HOME=<jdk17> ./gradlew :client:desktopTest --tests "*SemanticsDumpTest"`
Expected: **PASS** if alpha04 populates semantics.

**If FAIL (`semantics.texts was []`):** semantics is still not wired in this build. Do NOT delete the test — instead:
1. Delete this test file (it documents a not-yet-true capability), and
2. In Tasks 6–7, replace every `assertSemantics(...)` helper call with the existing compose-ui-test pattern (`runComposeUiTest { setContent { … }; onNodeWithText("…").assertIsDisplayed() }`), which is font-independent and already proven in `FeedSnapshotTest.kt:74-83`. Pixel goldens (Task 6/7) are unaffected.
3. Note the finding in the Task 8 doc update and stop treating Tier-0 as available.

- [ ] **Step 3: Commit (only if PASS)**

```bash
git add apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/SemanticsDumpTest.kt
git commit -m "CL-SNAP: verify alpha04 populates RenderResult.semantics (Tier-0 gate)"
```

---

### Task 3: Extract shared AppState fixtures → `SnapshotStates`

DRY: the scene registry and the migrated golden tests must render the *same* states. Lift the existing hand-built `AppState` literals out of the test files into one object so there is a single source.

**Files:**
- Create: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/SnapshotStates.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/SnapshotStatesTest.kt` (create)
- Reference (copy literals verbatim from): `FeedSnapshotTest.kt` (feed + typed + detail states), `HubSnapshotTest.kt` (`canonicalHub()`)

**Interfaces:**
- Produces (Tasks 4, 6, 7 consume these exact signatures):
  - `object SnapshotStates`
  - `fun feed(preset: String): AppState` — presets: `"busy"`, `"empty"`, `"caught-up"`, `"syncing"`, `"offline"`, `"typed"`, `"enriched"`
  - `fun hubTree(preset: String): HubTree` — presets: `"canonical"`, `"enriched"`
  - `fun detailCard(preset: String): Card` — presets: `"file"`, `"link"`, `"invite"`, `"contact"`, `"geo"`, `"email"`
  - `val TYPED_FEED: AppState` (the 6-typed-card state, reused by `detailCard`)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.sloopworks.dayfold.client.snapshot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SnapshotStatesTest {
  @Test fun feedPresetsBuild() {
    assertEquals(6, SnapshotStates.feed("typed").cards.size)
    assertEquals(0, SnapshotStates.feed("empty").cards.size)
    assertNotNull(SnapshotStates.feed("busy"))
  }
  @Test fun hubAndDetailPresetsBuild() {
    assertEquals("sample", SnapshotStates.hubTree("canonical").hub.id)
    assertEquals("invite", SnapshotStates.detailCard("invite").type)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd apps && JAVA_HOME=<jdk17> ./gradlew :client:desktopTest --tests "*SnapshotStatesTest"`
Expected: FAIL — unresolved reference `SnapshotStates`.

- [ ] **Step 3: Create `SnapshotStates.kt`**

Structure below. **Fill the marked `AppState(...)` / `HubTree(...)` bodies by lifting the literals verbatim** from the cited source lines (they already compile in this module):

```kotlin
package com.sloopworks.dayfold.client.snapshot

import com.sloopworks.dayfold.client.*

// Single source of the states rendered by both the scene registry (Task 4) and the
// golden tests (Task 6/7). Literals lifted verbatim from FeedSnapshotTest / HubSnapshotTest.
object SnapshotStates {

  // Lift verbatim from FeedSnapshotTest.kt:87-107 (the `typedFeed` val body).
  val TYPED_FEED: AppState = AppState(cards = listOf(/* …6 typed cards… */))

  fun feed(preset: String): AppState = when (preset) {
    // "busy": lift the 3-card populated state from FeedSnapshotTest.kt:44-55.
    "busy" -> AppState(cards = listOf(/* …3 cards… */))
    "empty" -> AppState()                                           // FeedSnapshotTest.kt:58
    "caught-up" -> AppState(hubs = listOf(                          // FeedSnapshotTest.kt:63
      Hub(id = "h1", title = "Starting College", status = "active", visibility = "family")))
    "syncing" -> AppState(syncing = true)                          // FeedSnapshotTest.kt:66
    "offline" -> AppState(error = "No internet connection")        // FeedSnapshotTest.kt:68
    "typed" -> TYPED_FEED
    // "enriched": lift from FeedSnapshotTest.kt:116-121 (`enrichedFeed`).
    "enriched" -> AppState(cards = listOf(/* …1 enriched card… */))
    else -> error("unknown feed preset '$preset'")
  }

  // Lift the whole HubTree body verbatim from HubSnapshotTest.kt:24-53 (`canonicalHub()`).
  fun hubTree(preset: String): HubTree = when (preset) {
    "canonical" -> HubTree(/* …hub + sections + 7 blocks… */)
    "enriched" -> hubTree("canonical").let { base ->               // HubSnapshotTest.kt:78-79
      base.copy(hub = base.hub.copy(media = HubMedia(icon = "school", accentColor = "#3B5BDB")))
    }
    else -> error("unknown hub preset '$preset'")
  }

  // The 6 detail cards are the same objects as TYPED_FEED's cards, addressed by id.
  fun detailCard(preset: String): Card =
    TYPED_FEED.cards.firstOrNull { it.id == preset }
      ?: error("unknown detail preset '$preset' (ids: ${TYPED_FEED.cards.map { it.id }})")
}
```

Note: in `TYPED_FEED`, the 6 cards have `id`s `"file"`, `"link"`, `"invite"`, `"contact"`, `"geo"`, `"email"` (see `FeedSnapshotTest.kt:87-107`) — that is why `detailCard(preset)` can look them up by `preset` name.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd apps && JAVA_HOME=<jdk17> ./gradlew :client:desktopTest --tests "*SnapshotStatesTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/SnapshotStates.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/SnapshotStatesTest.kt
git commit -m "CL-SNAP: extract shared AppState/HubTree snapshot fixtures (SnapshotStates)"
```

---

### Task 4: Scene registry + `main` (the CLI entry point)

**Files:**
- Create: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/SnapshotScenes.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/SnapshotScenesTest.kt` (create)

**Interfaces:**
- Consumes: `SnapshotStates` (Task 3); `snapshotApp { }`, `SceneArgs`, `SnapshotInput`, `SnapshotApp.render(...)`, `SnapshotApp.runCli(argv)`; composables `FeedScreen(AppState)`, `HubDetailScreen(AppState)`, `DetailScreen(Card, onBack, onAction)` (`com.sloopworks.dayfold.client.cards.DetailScreen`), `DayfoldTheme(darkTheme, content)` (`com.sloopworks.dayfold.client.theme.DayfoldTheme`), `currentDetailCard(AppState): Card?`.
- Produces: `val clientSnapshots: SnapshotApp` with scenes `feed` / `hub-detail` / `detail`; top-level `fun main(argv)` → generated main class `com.sloopworks.dayfold.client.snapshot.SnapshotScenesKt` (Task 5 references it).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.sloopworks.dayfold.client.snapshot

import org.reduxkotlin.snapshot.SnapshotInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotScenesTest {
  @Test fun registeredScenes() {
    assertEquals(setOf("feed", "hub-detail", "detail"), clientSnapshots.scenes.map { it.name }.toSet())
  }
  @Test fun everySceneRendersPixels() {
    assertTrue(clientSnapshots.render("feed", SnapshotInput.Preset("busy")).png.size > 100)
    assertTrue(clientSnapshots.render("hub-detail", SnapshotInput.Preset("canonical")).png.size > 100)
    assertTrue(clientSnapshots.render("detail", SnapshotInput.Preset("invite")).png.size > 100)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd apps && JAVA_HOME=<jdk17> ./gradlew :client:desktopTest --tests "*SnapshotScenesTest"`
Expected: FAIL — unresolved reference `clientSnapshots`.

- [ ] **Step 3: Create `SnapshotScenes.kt`**

```kotlin
package com.sloopworks.dayfold.client.snapshot

import androidx.compose.runtime.Composable
import com.sloopworks.dayfold.client.cards.DetailScreen
import com.sloopworks.dayfold.client.currentDetailCard
import com.sloopworks.dayfold.client.FeedScreen
import com.sloopworks.dayfold.client.HubDetailScreen
import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import org.reduxkotlin.snapshot.SnapshotApp
import org.reduxkotlin.snapshot.SnapshotInput
import org.reduxkotlin.snapshot.runCli
import org.reduxkotlin.snapshot.snapshotApp

// One registry, two consumers: assertGolden tests (Task 6/7) and the :client:snapshotUi
// CLI (Task 5). presets -> SnapshotStates -> the state-based composables under DayfoldTheme.
val clientSnapshots: SnapshotApp = snapshotApp {
  defaults { width = 411; height = 891; density = 2f; theme = "light" }

  scene("feed") {
    presets("busy", "empty", "caught-up", "syncing", "offline", "typed", "enriched")
    render { args ->
      val state = SnapshotStates.feed(presetName(args.input))
      themed(args.theme) { FeedScreen(state) }
    }
  }

  scene("hub-detail") {
    presets("canonical", "enriched")
    render { args ->
      val tree = SnapshotStates.hubTree(presetName(args.input))
      val state = AppState(currentHubId = tree.hub.id, currentHubTree = tree)
      themed(args.theme) { HubDetailScreen(state) }
    }
  }

  scene("detail") {
    presets("file", "link", "invite", "contact", "geo", "email")
    render { args ->
      val id = presetName(args.input)
      val state = SnapshotStates.TYPED_FEED.copy(detailStack = listOf(id))
      val card = currentDetailCard(state)!!
      themed(args.theme) { DetailScreen(card, onBack = {}, onAction = {}) }
    }
  }
}

// Presets only (this registry doesn't accept ad-hoc --state-json; keep the surface small).
private fun presetName(input: SnapshotInput): String = when (input) {
  is SnapshotInput.Preset -> input.name
  is SnapshotInput.Json -> error("this registry takes --preset, not --state-json")
}

private fun themed(theme: String?, content: @Composable () -> Unit): @Composable () -> Unit =
  { DayfoldTheme(darkTheme = theme == "dark") { content() } }

fun main(argv: Array<String>) {
  clientSnapshots.runCli(argv)   // argv = OPTIONS only, no leading "snapshot"
  kotlin.system.exitProcess(0)   // Skiko leaves non-daemon threads alive
}
```

If `HubTree`/`HubMedia` or the exact `HubDetailScreen` parameter differs, resolve against `HubSnapshotTest.kt:55-57` (it constructs `AppState(currentHubId=…, currentHubTree=…)` then calls `HubDetailScreen(state)` — mirror that).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd apps && JAVA_HOME=<jdk17> ./gradlew :client:desktopTest --tests "*SnapshotScenesTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/SnapshotScenes.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/SnapshotScenesTest.kt
git commit -m "CL-SNAP: scene registry (feed/hub-detail/detail) + runCli main"
```

---

### Task 5: `:client:snapshotUi` Gradle task + CLI verification

**Files:**
- Modify: `apps/client/build.gradle.kts` (append a `JavaExec` task registration after the `tasks.named<Test>("desktopTest")` line, ~148)

**Interfaces:**
- Consumes: the desktopTest runtime classpath + the `SnapshotScenesKt` main class (Task 4).
- Produces: `./gradlew :client:snapshotUi -PsnapshotArgs="…"` — the agent-loop entry (Tasks 6/8 and the agent use it).

- [ ] **Step 1: Register the task**

Append to `apps/client/build.gradle.kts`:

```kotlin
// CL-SNAP: run the snapshot scene registry's CLI against the desktopTest classpath.
// Usage: ./gradlew :client:snapshotUi -PsnapshotArgs="--scene feed --preset busy --out /tmp/x.png"
// NOTE: args are OPTIONS ONLY (no leading "snapshot"); split on spaces (no spaces in paths).
tasks.register<JavaExec>("snapshotUi") {
  group = "verification"
  description = "Render/verify client snapshot scenes headlessly (agent loop)."
  val testComp = kotlin.targets.getByName("desktop").compilations.getByName("test")
  dependsOn(testComp.compileTaskProvider)
  mainClass.set("com.sloopworks.dayfold.client.snapshot.SnapshotScenesKt")
  classpath = files(testComp.output.allOutputs, testComp.runtimeDependencyFiles)
  val raw = (project.findProperty("snapshotArgs") as String?).orEmpty()
  argumentProviders.add { raw.split(" ").filter { it.isNotBlank() } }
}
```

If `runtimeDependencyFiles` is not resolvable on this Kotlin version, use `testComp.runtimeDependencyFiles ?: files()` or the target's test run task classpath; verify by running Step 2.

- [ ] **Step 2: Verify `--list`**

Run: `cd apps && JAVA_HOME=<jdk17> ./gradlew :client:snapshotUi -PsnapshotArgs="--list" -q`
Expected: JSON on stdout listing scenes `feed`, `hub-detail`, `detail` with their presets.

- [ ] **Step 3: Verify a single render**

Run: `cd apps && JAVA_HOME=<jdk17> ./gradlew :client:snapshotUi -PsnapshotArgs="--scene feed --preset busy --out /tmp/feed-busy.png" -q`
Expected: stdout `wrote /tmp/feed-busy.png (<N> B)`; the PNG exists (`ls -la /tmp/feed-busy.png`).

- [ ] **Step 4: Probe the `--semantics` flag (discovery — record the result)**

Run: `cd apps && JAVA_HOME=<jdk17> ./gradlew :client:snapshotUi -PsnapshotArgs="--scene feed --preset busy --semantics --out /tmp/x.png" -q`
- If it prints a text dump → the CLI exposes Tier-0 semantics; note the exact flag for the Task 8 docs.
- If it errors `no such option: --semantics` → the alpha04 CLI does not surface semantics; Tier-0 text lives only on the in-process `render().semantics` path (Task 2). Record this in Task 8.

- [ ] **Step 5: Commit**

```bash
git add apps/client/build.gradle.kts
git commit -m "CL-SNAP: :client:snapshotUi JavaExec (render/list/verify via runCli)"
```

---

### Task 6: Golden harness — `assertGolden` for one surface (the pattern)

Establishes the committed-golden regression gate with the agreed tolerance, then bakes the first golden. Task 7 repeats the (mechanical) step for the rest.

**Files:**
- Create: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/GoldenSnapshotTest.kt`
- Create (recorded artifact): `apps/client/src/desktopTest/resources/snapshots/feed-busy.png`

**Interfaces:**
- Consumes: `clientSnapshots` (Task 4); `org.reduxkotlin.snapshot.assertGolden(scene, preset, theme, goldenDir, name, tolerance, maxDiffPercent, record)`.
- Produces: `GOLDEN_DIR` constant + the `assertGolden`-wrapping test class that Task 7 extends.

- [ ] **Step 1: Write the test (asserts against a not-yet-existing golden)**

```kotlin
package com.sloopworks.dayfold.client.snapshot

import org.reduxkotlin.snapshot.assertGolden
import java.io.File
import kotlin.test.Test

// Committed-golden regression gate. Goldens recorded on dev; CI (ubuntu) verifies with a
// 2% tolerance — brand fonts are bundled, so cross-arch variance is only Skiko AA.
// Re-record after an INTENTIONAL visual change:
//   cd apps && ./gradlew :client:desktopTest --tests "*GoldenSnapshotTest" -Dsnapshot.record=true
// then EYEBALL the changed PNG before committing.
class GoldenSnapshotTest {
  private fun golden(scene: String, preset: String, theme: String? = null) =
    clientSnapshots.assertGolden(
      scene = scene, preset = preset, theme = theme,
      goldenDir = GOLDEN_DIR, maxDiffPercent = 2.0,
    )

  @Test fun feedBusy() = golden("feed", "busy")

  companion object { val GOLDEN_DIR = File("src/desktopTest/resources/snapshots") }
}
```

- [ ] **Step 2: Run to verify it fails (missing golden)**

Run: `cd apps && JAVA_HOME=<jdk17> ./gradlew :client:desktopTest --tests "*GoldenSnapshotTest"`
Expected: FAIL — `missing golden 'feed-busy' — run with -Dsnapshot.record=true to create it`.

- [ ] **Step 3: Record the golden**

Run: `cd apps && JAVA_HOME=<jdk17> ./gradlew :client:desktopTest --tests "*GoldenSnapshotTest" -Dsnapshot.record=true`
Expected: PASS; `apps/client/src/desktopTest/resources/snapshots/feed-busy.png` now exists. **Open it and confirm it shows the populated feed** (record without review defeats the gate).

- [ ] **Step 4: Run again in verify mode to confirm it passes**

Run: `cd apps && JAVA_HOME=<jdk17> ./gradlew :client:desktopTest --tests "*GoldenSnapshotTest"`
Expected: PASS (verifies the just-recorded golden at 0% diff).

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/GoldenSnapshotTest.kt apps/client/src/desktopTest/resources/snapshots/feed-busy.png
git commit -m "CL-SNAP: golden harness + first golden (feed/busy, 2% tol)"
```

---

### Task 7: Goldens for the remaining canonical surfaces

Add one `@Test` per surface below to `GoldenSnapshotTest`, record, eyeball, commit. Keep the set **small and canonical** (light theme unless dark is the point) to bound repo weight.

**Files:**
- Modify: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/GoldenSnapshotTest.kt`
- Create (recorded): the PNGs under `apps/client/src/desktopTest/resources/snapshots/`

- [ ] **Step 1: Add the test methods**

Insert into `GoldenSnapshotTest` (each uses the `golden(...)` helper from Task 6):

```kotlin
  @Test fun feedTyped() = golden("feed", "typed")
  @Test fun feedEmpty() = golden("feed", "empty")
  @Test fun feedCaughtUp() = golden("feed", "caught-up")
  @Test fun feedEnriched() = golden("feed", "enriched")
  @Test fun hubCanonical() = golden("hub-detail", "canonical")
  @Test fun hubCanonicalDark() = golden("hub-detail", "canonical", theme = "dark")
  @Test fun hubEnriched() = golden("hub-detail", "enriched")
  @Test fun detailInvite() = golden("detail", "invite")
  @Test fun detailContact() = golden("detail", "contact")
  @Test fun detailFile() = golden("detail", "file")
  @Test fun detailEmail() = golden("detail", "email")
```

- [ ] **Step 2: Record all goldens**

Run: `cd apps && JAVA_HOME=<jdk17> ./gradlew :client:desktopTest --tests "*GoldenSnapshotTest" -Dsnapshot.record=true`
Expected: PASS; 11 new PNGs under `src/desktopTest/resources/snapshots/`.

- [ ] **Step 3: Eyeball each recorded PNG**

`Read` (or open) each new `apps/client/src/desktopTest/resources/snapshots/*.png` and confirm it shows the intended surface (e.g. `hub-detail-canonical.png` shows the full hub; `detail-invite.png` shows the invite detail). A wrong-looking golden here bakes a bug into the baseline.

- [ ] **Step 4: Verify the gate is green without record**

Run: `cd apps && JAVA_HOME=<jdk17> ./gradlew :client:desktopTest --tests "*GoldenSnapshotTest"`
Expected: PASS (all goldens verify at ~0% on the recording arch).

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/GoldenSnapshotTest.kt apps/client/src/desktopTest/resources/snapshots/
git commit -m "CL-SNAP: goldens for feed/hub-detail/detail canonical surfaces"
```

---

### Task 8: Document the loop + correct the drift

Make the new capability discoverable and fix the two stale docs the analysis found.

**Files:**
- Modify: `processes/agent-dev-loop.md` (the `⭐ rk snapshot` section, ~140-176, and the "Now available" note ~231-238)
- Modify: `adr/0019-client-observability-and-tooling.md` (the "Remaining (lower priority)" item #4)
- Modify: `CHANGELOG.md` (dated entry — this is a dev-surface/tooling change)

- [ ] **Step 1: Rewrite the `rk snapshot` section of `agent-dev-loop.md`**

Replace the aspirational API/command examples with the real, verified ones:
- Registry is `:client` desktopTest (`SnapshotScenes.kt`), scenes `feed` / `hub-detail` / `detail`.
- Drive it with `./gradlew :client:snapshotUi -PsnapshotArgs="--scene feed --preset busy --out /tmp/x.png"` — **options only, no leading `snapshot`** (call out the old wrong examples).
- Golden gate: `:client:desktopTest` runs `GoldenSnapshotTest`; re-record with `-Dsnapshot.record=true` then eyeball.
- Record the Task 2 semantics finding (Tier-0 available or not) and the Task 5 Step-4 `--semantics` flag result.
- State the tiered loop: (0) semantics/content text, (1) golden verdict, (2) read the PNG only on drift.

- [ ] **Step 2: Correct ADR 0019**

ADRs are immutable once Accepted — do **not** rewrite history. Add a dated **addendum** note under the Status/Consequences (or a new "Update 2026-07-02" line): golden-image diffing (Remaining #4) is delivered by `redux-kotlin-snapshot:1.0.0-alpha04` (CL-SNAP), not the Roborazzi DIY path; goldens live in `apps/client/src/desktopTest/resources/snapshots/`, verified in CI at 2% tolerance.

- [ ] **Step 3: Add a CHANGELOG entry**

Add under the current date: headless snapshot render + committed-golden regression gate for the client UI (feed/hub-detail/detail), plus the `:client:snapshotUi` agent-loop entry.

- [ ] **Step 4: Commit**

```bash
git add processes/agent-dev-loop.md adr/0019-client-observability-and-tooling.md CHANGELOG.md
git commit -m "CL-SNAP: document the snapshot agent loop; correct agent-dev-loop.md + ADR 0019 addendum"
```

---

## Self-Review

**Spec coverage** (design §→task):
- §2 tiered loop → Tasks 2 (Tier-0 gate), 5 (CLI), 6/7 (Tier-1 golden), 8 (documented). ✅
- §3.1 registry in desktopTest → Tasks 1/4. ✅
- §3.2 scenes reuse existing fixtures → Task 3 (`SnapshotStates`) + Task 4. ✅ (Refined from the design's "FakeScenarios ids": `FeedScreen` consumes `AppState`, not HTTP responses, so we reuse the tests' hand-built `AppState` literals — a truer single-source than routing through `FakeScenarios`.)
- §3.3 `:client:snapshotUi` → Task 5. ✅
- §3.4 one golden dir → Tasks 6/7 (`src/desktopTest/resources/snapshots`). ✅
- §4 CI golden gate + semantics smoke → Tasks 6/7 (gate) + Task 2/5.4 (smoke). ✅
- §4 migrate the 5 hand-rolled tests → **descoped, deliberately.** They already write PNGs + assert `onNodeWithText`; the new `GoldenSnapshotTest` supersedes their regression role without touching them. Deleting/porting them is optional cleanup, not required for the gate — left out to keep the plan focused (YAGNI). Flag to operator in handoff.
- §5 determinism/cross-arch → resolved in Global Constraints (bundled fonts → 2% tolerance, record on dev / verify on ubuntu). ✅
- §6 rollout P1–P4 → Tasks 1–5 (spine), 6–7 (coverage/gate), 8 (docs). ✅

**Placeholder scan:** the `/* … */` bodies in Task 3 are explicit "lift verbatim from `<file>:<lines>`" instructions with exact citations to compiling source in-repo — not vague TODOs. No "add error handling"/"write tests for the above" present.

**Type consistency:** `clientSnapshots`, `SnapshotStates.feed/hubTree/detailCard/TYPED_FEED`, `GOLDEN_DIR`, `golden(scene,preset,theme)`, scene names `feed`/`hub-detail`/`detail`, preset names, and the `assertGolden` signature are used identically across Tasks 3→4→6→7. Main class `SnapshotScenesKt` matches the JavaExec `mainClass` in Task 5.

**Known verify-at-runtime points** (called out inline, not hidden): JavaExec classpath accessor (Task 5.1), the `--semantics` CLI flag existence (Task 5.4), and the alpha04 semantics-populated claim (Task 2) — each has an explicit fallback.
