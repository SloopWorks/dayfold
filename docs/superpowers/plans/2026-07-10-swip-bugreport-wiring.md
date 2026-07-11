# Dayfold swip-bugreport Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the swip bug reporter into dayfold's Android app for DEBUG builds — shake/FAB → capture → annotate → review → report lane — with the redux timeline recorder on dayfold's store, a product-owned sanitizer + leak test, and zero footprint in release.

**Architecture:** Follows dayfold's proven per-variant idiom (`debugDrawerPlugins()` pattern): `src/main` calls variant-provided functions; `src/debug` wires the real SDK (`debugImplementation` artifacts), `src/release` is an inert mirror. `:client` stays swip-free — `createAppStore` gains an optional `extraEnhancer` composed RIGHTMOST (the recorder's required innermost slot). A new tiny KMP module `:swip-wiring` holds the slice registry + `DayfoldStateSanitizer` + the docs/12-mandated product-owned leak test (androidApp has no JVM test source set; this module gives the hermetic home).

**Tech Stack:** dayfold apps build (Kotlin 2.3.20 / CMP 1.11.1 / AGP 9.2.1); `works.sloop.swip:{swip-bugreport,swip-rk-recorder,swip-bugreport-ui}:0.1.0` + `com.sloopworks.ui:foundation:0.1.0` from GitHub Packages.

## Global Constraints

- Worktree `~/workspace/dayfold-bugreport-wt`, branch `swip-bugreport-wiring` (dayfold main @ ccd38d6). Commits end with the Claude Code trailer. Gradle runs from `apps/`.
- PREREQUISITE (verify in Task 1): the swip `publish-kmp` workflow has published 0.1.0 — resolution against GH Packages must succeed with a `read:packages` PAT.
- Maven consumption: dayfold has NO credentials plumbing today. Add TWO repo blocks (swip + sloopworks-ui packages) to `apps/settings.gradle.kts` `dependencyResolutionManagement`, credentials `System.getenv("SLOOPWORKS_PACKAGES_TOKEN") ?: providers.gradleProperty("gpr.token").orNull ?: ""` / user `GITHUB_ACTOR ?: gpr.user ?: ""` (matches swip's convention). Local dev uses `~/.gradle/gradle.properties` `gpr.user`/`gpr.token`; CI needs the `SLOOPWORKS_PACKAGES_TOKEN` repo secret in DAYFOLD (user action — the PR body must call it out; ci.yml env additions ship in this plan so CI goes green the moment the secret exists).
- Variant split rules: `src/main` NEVER imports `works.sloop.swip.*`; per-variant function pairs mirror `DebugDrawerPlugins.kt` exactly (same package `com.sloopworks.dayfold.android`, same signatures both variants). Release wiring = pass-through/no-op; release APK gains zero swip bytes (`debugImplementation` only).
- Recorder wiring rules (swip docs/10+12, binding): enhancer passed at store CONSTRUCTION, rightmost/innermost in `compose(...)`; dormant until `activate()`; NO sanitizer → recorder refuses to start; product-owned salted-fixture leak test is MANDATORY before enabling (docs/12 §6) — it lives in `:swip-wiring` desktopTest and joins dayfold CI's test line.
- Slice registry policy (privacy floor): ONLY low-risk slices are registered — `route` (enum name), `syncing` (Boolean), `detailStack` (card ids, pseudonymous), `cardsCount` (derived Int, NOT card content), `hubFilter` (String — REALITY CHECK: it's filter CHIPS `all|active|planning` (Model.kt:491), not user-typed text; recorded as a low-risk enum-ish string; the 32-char truncation stays as declared defense-in-depth only). NEVER registered: `session`, `mintedInvite`, `members`, `pendingApprovals`, `families`, `devices`, `pendingDevice`, `myDisplayName`, `cards` content, `error`/`authError` strings (may embed server messages). The registry is the first fence; `DayfoldStateSanitizer` is the second (drops any value containing a JWT-shaped `eyJ` prefix or `@` — defense in depth); the leak test is the gate.
- Consent posture: debug builds only, gate = `BuildConfig.DEBUG`, `internalChannel = { true }` (dayfold debug builds are internal by definition — ADR-0021 layer 2); identity = `(null, null)` ANONYMOUS (no swip identity stack in dayfold yet); no upload (no gateway) — reports land in the lane on device, visible via `pending()`.
- Acceptance gates: `:client:desktopTest` + `:ui:desktopTest` + `:swip-wiring:desktopTest` green; `:androidApp:assembleDebug` builds; `:androidApp:assembleRelease` as a LOCAL gate (dayfold CI has no release job; release-android.yml is tag-gated) — verifies the release classpath carries zero swip bytes. Emulator run = manual user step (PR body includes a 5-line smoke script).

---

### Task 1: Maven plumbing + resolution proof

**Files:**
- Modify: `apps/settings.gradle.kts` (two maven blocks in `dependencyResolutionManagement`)
- Modify: `.github/workflows/ci.yml` (env `SLOOPWORKS_PACKAGES_TOKEN: ${{ secrets.SLOOPWORKS_PACKAGES_TOKEN }}` on the client/ui test job AND the androidApp assembleDebug job)

**Steps:**
- [ ] **Step 1:** Add to `dependencyResolutionManagement.repositories` in `apps/settings.gradle.kts`:

```kotlin
    // SWIP bug reporter + SloopWorks design tokens (private GitHub Packages).
    // Local dev: gpr.user/gpr.token in ~/.gradle/gradle.properties (read:packages PAT).
    // CI: SLOOPWORKS_PACKAGES_TOKEN secret.
    listOf(
      "https://maven.pkg.github.com/SloopWorks/swip",
      "https://maven.pkg.github.com/SloopWorks/sloopworks-ui",
    ).forEach { pkgUrl ->
      maven {
        url = uri(pkgUrl)
        credentials {
          username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").orNull ?: ""
          password = System.getenv("SLOOPWORKS_PACKAGES_TOKEN") ?: providers.gradleProperty("gpr.token").orNull ?: ""
        }
      }
    }
```

- [ ] **Step 2: Resolution proof** — temporarily add `debugImplementation("works.sloop.swip:swip-bugreport-ui:0.1.0")` to `apps/androidApp/build.gradle.kts` deps, run `cd apps && ./gradlew :androidApp:dependencies --configuration debugRuntimeClasspath --console=plain | grep -E "works.sloop.swip|com.sloopworks.ui" | head -5` → all four artifacts resolve (needs local `gpr.token`; if 401, STOP — the publish-kmp dispatch hasn't landed; report and wait). Keep the dep (Task 3 needs it anyway; add `swip-bugreport` + `swip-rk-recorder` too, all `debugImplementation`).
- [ ] **Step 3:** ci.yml: add the env line to both jobs' gradle steps. **Step 4: Commit** — `build: GitHub Packages repos + swip bugreport debug deps + CI token env`.

---

### Task 2: `createAppStore` extraEnhancer seam (TDD, :client stays swip-free)

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Reducer.kt` (the `createAppStore` function, ~line 235)
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/CreateAppStoreEnhancerTest.kt`

**Interfaces:**
- `fun createAppStore(initial: AppState = AppState(), debug: Boolean = true, extraEnhancer: StoreEnhancer<AppState>? = null): Store<AppState>` — when non-null, `extraEnhancer` composes RIGHTMOST: debug → `compose(devTools(...), applyMiddleware(actionLog), extraEnhancer)`; non-debug → `compose(extraEnhancer)` equivalent (pass it directly as the enhancer). Null → exactly today's behavior.

- [ ] **Step 1: Failing test**

```kotlin
package com.sloopworks.dayfold.client

import org.reduxkotlin.StoreEnhancer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreateAppStoreEnhancerTest {
  /** Counting enhancer: wraps dispatch, increments per action, innermost-position observable. */
  private var seen = 0
  private val counting: StoreEnhancer<AppState> = { creator ->
    { r, s, e ->
      val store = creator(r, s, e)
      val inner = store.dispatch
      store.dispatch = { a -> seen++; inner(a) }
      store
    }
  }

  @Test fun extra_enhancer_sees_dispatches_in_debug_and_release_modes() {
    for (debug in listOf(true, false)) {
      seen = 0
      val store = createAppStore(debug = debug, extraEnhancer = counting)
      store.dispatch(OpenFeed) // data object, Reducer.kt:56 → route = Route.Feed (initial is Route.Loading)
      assertTrue(seen >= 1, "debug=$debug")  // >=1: devtools may re-dispatch internals
      assertEquals(Route.Feed, store.state.route)
    }
  }

  @Test fun null_extra_enhancer_is_todays_behavior() {
    val store = createAppStore(debug = false)
    store.dispatch(OpenFeed)
    assertEquals(Route.Feed, store.state.route)
  }
}
```

- [ ] **Step 2: fail** (`cd apps && ./gradlew :client:desktopTest --console=plain`). **Step 3: implement** (verified shapes — `compose` has a `List` overload; `enhancer` must be NAMED in the non-debug branch, `createConcurrentStore` has `notificationContext`/`onError` params before it):

```kotlin
fun createAppStore(initial: AppState = AppState(), debug: Boolean = true, extraEnhancer: StoreEnhancer<AppState>? = null): Store<AppState> =
  if (debug) createConcurrentStore(
    ::rootReducer, initial,
    enhancer = compose(listOfNotNull(
      devTools(DevToolsConfig(instanceId = "family-ai", name = "Family AI")),
      applyMiddleware(actionLog),
      extraEnhancer, // rightmost = innermost — the recorder's required slot
    )),
  )
  else createConcurrentStore(::rootReducer, initial, enhancer = extraEnhancer)
``` **Step 4: pass + full `:client:desktopTest` green. Step 5: Commit** — `client: createAppStore optional extraEnhancer (innermost slot) for debug tooling`.

---

### Task 3: `:swip-wiring` module — slice registry + sanitizer + LEAK TEST (TDD)

**Files:**
- Modify: `apps/settings.gradle.kts` (`include(":swip-wiring")`)
- Create: `apps/swip-wiring/build.gradle.kts` — KMP: androidTarget + jvm("desktop"); plugins `org.jetbrains.kotlin.multiplatform` + `com.android.library` (AGP 9 requires it with androidTarget) with `android { namespace = "com.sloopworks.dayfold.swip"; compileSdk = 37; defaultConfig { minSdk = 33 } }`; commonMain deps: `api(project(":client"))`, `api("works.sloop.swip:swip-rk-recorder:0.1.0")`; desktopTest: kotlin("test"), coroutines-test
- Create: `apps/swip-wiring/src/commonMain/kotlin/com/sloopworks/dayfold/swip/DayfoldRecording.kt`
- Test: `apps/swip-wiring/src/desktopTest/kotlin/com/sloopworks/dayfold/swip/DayfoldLeakTest.kt`

**Interfaces:**
- `fun dayfoldSlices(): List<SliceSpec<AppState>>` — exactly the Global-Constraints registry: `route` (String via `state.route.name`), `syncing` (Boolean), `detailStack` (List<String>), `cardsCount` (Int via `state.cards.size`), `hubFilter` (String). Apply lambdas: route/syncing/hubFilter restore into `copy(...)` (route via `Route.valueOf`), detailStack restores, cardsCount has NO apply (derived).
- `val dayfoldSanitizer: StateSanitizer` — drops (returns null for) any JsonElement whose serialized text contains `"eyJ"` (JWT prefix) or `"@"`; truncates `hubFilter` strings to 32 chars (re-encode); passes everything else.
- `fun dayfoldRecorder(scope: CoroutineScope, appVersion: String): ReduxTimelineRecorder<AppState>` — specs + sanitizer + `RecorderConfig(appVersion = appVersion)` + `Clock` from `System.currentTimeMillis` (androidMain/desktopMain expect? NO — swip's `Clock` is a fun interface; pass `Clock { System.currentTimeMillis() }` — commonMain can't... use `kotlin.time`? Simplest: the function takes `clock: works.sloop.swip.bugreport.lane.Clock` as a parameter; Android caller passes the real one).

- [ ] **Step 1: Failing leak test** (the docs/12 §6 mandatory product-owned test — salted REAL state):

```kotlin
package com.sloopworks.dayfold.swip

import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.Session
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.reduxkotlin.createStore
import works.sloop.swip.bugreport.lane.Clock
import works.sloop.swip.rk.recorder.RecorderConfig
import works.sloop.swip.rk.recorder.ReduxTimelineRecorder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/12 §6: product-owned sanitizer leak test over SALTED real state. */
class DayfoldLeakTest {
  private val salted = AppState(
    session = Session(access = "eyJSALTEDJWTACCESS", refresh = "eyJSALTEDREFRESH", userId = "u_salted"),
    myDisplayName = "Salted Q. User",
    hubFilter = "salted-search someone@example.com padding-padding-padding", // synthetic: real values are chip literals; salt proves the fence anyway
    detailStack = listOf("card_salt_1"),
  )

  @Test fun journal_never_contains_salted_pii() = runTest {
    val rec = ReduxTimelineRecorder(
      specs = dayfoldSlices(),
      sanitizer = dayfoldSanitizer,
      config = RecorderConfig(appVersion = "test"),
      clock = Clock { 0L },
      scope = this,
    )
    val store = createStore({ s: AppState, _: Any -> s.copy(syncing = !s.syncing) }, salted, rec.enhancer())
    rec.activate()
    repeat(3) { store.dispatch("tick"); advanceUntilIdle() }
    val text = rec.freeze()!!.journalJson.decodeToString() + rec.freeze()!!.finalStateJson.decodeToString()
    rec.deactivate()
    // secrets/PII salts must be absent
    assertFalse("eyJSALTED" in text)
    assertFalse("Salted Q. User" in text)
    assertFalse("someone@example.com" in text)  // hubFilter carried an email → sanitizer drops it
    assertFalse("u_salted" in text)
    // pseudonymous + derived slices ARE present (registry works)
    assertTrue("card_salt_1" in text)     // detailStack ids allowed (internal debug)
    assertTrue("cardsCount" in text)
  }

  @Test fun hub_filter_without_pii_is_truncated_not_dropped() = runTest {
    val rec = ReduxTimelineRecorder(
      specs = dayfoldSlices(), sanitizer = dayfoldSanitizer,
      config = RecorderConfig(appVersion = "test"), clock = Clock { 0L }, scope = this,
    )
    val longFilter = "x".repeat(100)
    val store = createStore({ s: AppState, _: Any -> s }, AppState(hubFilter = longFilter), rec.enhancer())
    rec.activate()
    store.dispatch("tick"); advanceUntilIdle()
    val text = rec.freeze()!!.journalJson.decodeToString()
    rec.deactivate()
    assertFalse(longFilter in text)
    assertTrue("x".repeat(32) in text)
  }
}
```
(Adjust `AppState`/`Session` constructor args to the real Model.kt signatures — `AppState` has ~50 defaulted fields, the four above are settable by name; `Session(access, refresh, userId)` per Model.kt:404.)

- [ ] **Step 2: fail. Step 3: implement `DayfoldRecording.kt`.** Sanitizer sketch:

```kotlin
val dayfoldSanitizer = StateSanitizer { slice, value ->
  val text = value.toString()
  when {
    "eyJ" in text || "@" in text -> null            // JWT-shaped or email-shaped → drop slice value
    slice == "hubFilter" -> JsonPrimitive(text.trim('"').take(32))
    else -> value
  }
}
```

- [ ] **Step 4: pass** (`./gradlew :swip-wiring:desktopTest`). **Step 5:** add `:swip-wiring:desktopTest` to ci.yml's client/ui test gradle line. **Step 6: Commit** — `swip-wiring: dayfold slice registry + sanitizer + mandatory leak test`.

---

### Task 4: androidApp variant wiring

**Files:**
- Create: `apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/BugReporterGlue.kt`
- Create: `apps/androidApp/src/release/kotlin/com/sloopworks/dayfold/android/BugReporterGlue.kt` (inert mirror)
- Modify: `apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/MainActivity.kt` (store creation line 168 + setContent block line 291 + a shake lifecycle hook)
- Modify: `apps/androidApp/build.gradle.kts` — ADD `debugImplementation(project(":swip-wiring"))` AND `debugImplementation("com.squareup.okio:okio:3.9.1")` (swip-bugreport declares okio as `implementation`, but ReportLane's public ctor takes okio types — consumer must supply it. SWIP follow-up noted in the PR body: okio should be `api` in swip-bugreport, ship with 0.1.1)

**Interfaces (BOTH variants define, signatures identical — the `debugDrawerPlugins` idiom):**
- `fun bugReporterEnhancer(): StoreEnhancer<AppState>?` — debug: builds the singleton recorder (`dayfoldRecorder(...)` from `:swip-wiring`, `Clock { System.currentTimeMillis() }`, `CoroutineScope(SupervisorJob() + Dispatchers.Default)`, appVersion = BuildConfig.VERSION_NAME) and returns its enhancer; release: `null`.
- `fun bugReporterInstall(activity: ComponentActivity)` — debug (exact signatures, reviewer-verified):
```kotlin
BugReportsConfig(
  gate = ReportGate { BuildConfig.DEBUG },
  ids = ReportIdGenerator { "rpt_" + java.util.UUID.randomUUID().toString().replace("-", "").take(20) },
  context = { ContextBlock(
    appVersion = BuildConfig.VERSION_NAME, osName = "android",
    osVersion = android.os.Build.VERSION.RELEASE, device = android.os.Build.MODEL,
    locale = java.util.Locale.getDefault().toLanguageTag(), channel = "debug",
  ) },
  identity = { null to null },
  configState = { null to emptyMap() },
  sources = CaptureSources(
    // WeakReference — the holder is a singleton; capturing the Activity directly
    // would leak it and stale after rotation:
    screenshot = AndroidScreenshotProvider { BugReporterHolder.currentActivity?.get() },
    breadcrumbs = <32-deep ArrayDeque ring fed by WRAPPING ClientLog.sink — keep the
      existing DebugLog forwarding AND append "tag: msg"; install AFTER MainActivity's
      sink assignment (line ~152)>,
    timeline = recorder,
  ),
  lane = ReportLane(
    fs = FileSystem.SYSTEM,
    dir = androidReportDir(activity.applicationContext),
    clock = Clock { System.currentTimeMillis() },
    health = SdkHealthCounter { }, // no-op — no swip telemetry stack in dayfold yet
  ),
)
```
plus `BugReporterHolder.currentActivity = WeakReference(activity)` on every install; `BugReporterController(facade, scope, internalChannel = { true })`; `AndroidShakeSource(sensorManager, ShakeDetector { controller.open(ReportType.BUG, trigger = "shake") })` (ShakeDetector ctor = `(config = ShakeConfig(), onShake)`); `recorder.activate()`; lifecycle observer: shake `start()` on RESUME / `stop()` on PAUSE (stop() already resets the detector); release: no-op.
- `@Composable fun BugReporterWrapped(content: @Composable () -> Unit)` — debug: `BugReporterOverlay(controller, dark = isSystemInDarkTheme(), showEntryPoint = true, entry = EntryStyle.EdgeTab) { content() }`; release: `content()`.

**Steps:**
- [ ] **Step 1:** Implement both variant files (debug holds the singletons in a `private object BugReporterHolder`); modify MainActivity: line ~168 → `store = createAppStore(debug = BuildConfig.DEBUG, extraEnhancer = bugReporterEnhancer())`; after drawer install → `bugReporterInstall(this)`; `setContent { DebugDrawerHost { BugReporterWrapped { FeedApp(store, ...) } } }` (BugReporterWrapped INSIDE the drawer host, wrapping the app content).
- [ ] **Step 2: Compile gates** — `./gradlew :androidApp:assembleDebug --console=plain` AND `:androidApp:assembleRelease` (release must compile with the inert mirror + zero swip deps on its classpath; if the release lint/classpath complains, the `configurations.configureEach` exclusion idiom at build.gradle.kts:82 is the precedent for surgery).
- [ ] **Step 3:** `:client:desktopTest :ui:desktopTest :swip-wiring:desktopTest` all green. **Step 4: Commit** — `androidApp: bug reporter debug wiring — recorder, shake, overlay (release inert)`.

---

### Task 5: Docs, PR, user handoff

- [ ] **Step 1:** dayfold `CLAUDE.md`/AGENTS.md commands section (if it lists gradle test lines): add `:swip-wiring:desktopTest`. Add `docs/` note if dayfold has an integration docs home (check `docs/` / `adr/` — if ADRs are numbered, this wiring deserves a short ADR: "swip-bugreport in debug builds"; write it following the local ADR template).
- [ ] **Step 2:** Push branch, open PR titled `swip-bugreport: debug-build wiring (shake → capture → annotate → review)` with body: what's wired, the slice-registry privacy floor + leak test, release = zero footprint, **required repo secret `SLOOPWORKS_PACKAGES_TOKEN` (read:packages PAT) before CI can pass** (call this out first), and a manual smoke script:
  1. `SLOOPWORKS_PACKAGES_TOKEN=<pat> ./gradlew :androidApp:installDebug`
  2. shake the device (or drag the edge tab) → review sheet rises
  3. tap the screenshot row → Annotate → draw + blur → Done
  4. toggle Logs off → Send report → send-state card shows QUEUED
  5. re-open → edge tab badge shows pending (no upload — gateway is Phase 1)
- [ ] **Step 3:** Watch CI: expected RED on the packages steps until the user sets the secret — report that state honestly in the final message rather than waiting indefinitely.

## Self-review notes

- `:client` swip-freedom preserved (Task 2 touches only redux types it already depends on); `src/main` swip-freedom preserved (variant fns only).
- The leak test is the docs/12 §6 gate and runs in dayfold CI (Task 3 Step 5) — the recorder cannot be enabled without it existing, satisfying the lint-gate spirit until swip ships a real lint.
- Release safety: debugImplementation-only + inert mirrors + assembleRelease gate.
- Known open items deliberately NOT here: upload (gateway, Phase 1), quick-fire surface for dayfold dogfood builds (needs channel wiring — follow-up), iOS wiring (dayfold iosApp consumes the :ui framework — swip iOS wiring lands with dayfold's iOS bugreporter pass).
