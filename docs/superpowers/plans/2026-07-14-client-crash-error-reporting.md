# Client Crash / Error Reporting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn on SWIP's error pillar in Dayfold's Android debug/dogfood build — fatal crashes captured by Sentry (KMP project) and mirrored into the owned PostHog stream, handled `record()`/`wtf()` in both, joined on `error.fingerprint`.

**Architecture:** Consume the published KMP SWIP artifacts; construct a `SentryCrashReporter` in the debug glue and feed it through `SwipPlatformDeps.crashReporter`; hoist init to a custom `Application` so the crash handler installs in the earliest app code. Debug-only — the release APK keeps its inert same-signature glue and zero SWIP bytes.

**Tech Stack:** Kotlin 2.3.20 / Compose-MP, KMP (`apps/swip-wiring`, `apps/androidApp`), Gradle (single `apps/` wrapper, JDK 17), `works.sloop.swip:*` from GitHub Packages, `io.sentry:sentry-kotlin-multiplatform` (transitive via swip-sentry), Infisical for build-time secrets.

## Global Constraints

- **Debug/dogfood variant ONLY.** Every SWIP symbol enters via `debugImplementation` or `src/debug`. The release APK must stay `javap`-clean of `works/sloop/swip` (ADR 0055). `src/release/.../SwipAnalyticsGlue.kt` is an inert same-signature mirror — never edit it.
- **No hand-written vendor wiring beyond the sanctioned seam.** Product code touches `initSentryAndroid` / `SentryInitConfig` / `CrashReporter` / the `SloopErrors` facade only. No `io.sentry.*` import in Dayfold source. No DSN in the repo — it is read from `System.getenv` into a debug `buildConfigField` at build time.
- **Wrong-project guard:** `projectId = "4511734711189584"` is a committed constant (the KMP Sentry project — public id, not a secret), declared **independently** of the DSN. Never parse it from the DSN. Org id: `"o4511720596570112"`.
- **JDK 17 for Gradle:** `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`. Run gradle from `apps/`.
- **Build with secrets:** the on-device path needs `cd ~/workspace && infisical run --path=/dayfold -- <gradle/env>` so `SENTRY_KOTLIN_EU_DSN` + `POSTHOG_*` reach `buildConfigField`. A plain `./gradlew :androidApp:assembleDebug` (no Infisical) must still build and run — SWIP errors just stay off (empty-DSN guard).
- **Versions (exact):** `swip-core 0.1.11`, `schema-dayfold 0.1.7`, `swip-logging 0.1.2`, `swip-sentry 0.1.0`.
- **SWIP is read-only.** If a defect surfaces in `swip-sentry`/`swip-core`, report it (Task 6) — do not patch the sibling repo. Its sources are at `~/workspace/sloopworksinstrumentationplatform/sdk-kmp` for reference.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `apps/swip-wiring/build.gradle.kts` | bump `swip-core`, `schema-dayfold` | 1 |
| `apps/swip-wiring/src/commonMain/.../DayfoldAnalytics.kt` | delete the hand-rolled `NoOpErrors` (facade now ships one) | 1 |
| `apps/swip-wiring/src/desktopTest/.../DayfoldMapperTableTest.kt` | repoint `NoOpErrors` → swip-core's | 1 |
| `apps/swip-wiring/src/desktopTest/.../DayfoldLeakTest.kt` | repoint `NoOpErrors` → swip-core's | 1 |
| `apps/androidApp/src/debug/.../SwipAnalyticsGlue.kt` | error runtime on (T1); Sentry reporter + guard (T2); main-process guard (T3); debug-trigger fns (T4) | 1–4 |
| `apps/androidApp/build.gradle.kts` | bump `swip-logging`; add `swip-sentry`; `SENTRY_KOTLIN_EU_DSN` debug field | 1–2 |
| `apps/androidApp/src/main/.../DayfoldApp.kt` | **new** custom `Application`; hoists `swipInit` | 3 |
| `apps/androidApp/src/main/AndroidManifest.xml` | register `android:name=".DayfoldApp"` | 3 |
| `apps/androidApp/src/main/.../MainActivity.kt` | drop its `swipInit(application)` call | 3 |
| `apps/androidApp/src/debug/.../SwipErrorsTriggerPlugin.kt` | **new** debug-drawer plugin: fire wtf / fire crash | 4 |
| `apps/androidApp/src/debug/.../DebugDrawerPlugins.kt` | register the trigger plugin | 4 |
| `adr/0060-client-crash-error-reporting.md`, `CHANGELOG.md`, `backlog/now.md` | docs | 6 |

---

## Task 1: Consume published SWIP + turn on the owned-stream error runtime

Bump to the published artifacts and switch the redux middleware from `NoOpErrors` to the real error facade. The new `SloopErrors` facade added `wtf`/`drainStorms`/`flush`/`health`, so Dayfold's hand-rolled `NoOpErrors` no longer compiles — swip-core now ships one, so delete Dayfold's and repoint the two tests. At the end of this task the error runtime is live but tees to `NoOpCrashReporter` (no Sentry yet) — that arrives in Task 2.

**Files:**
- Modify: `apps/swip-wiring/build.gradle.kts:24-25`
- Modify: `apps/swip-wiring/src/commonMain/kotlin/com/sloopworks/dayfold/swip/DayfoldAnalytics.kt:48-52` (delete `NoOpErrors`)
- Modify: `apps/swip-wiring/src/desktopTest/kotlin/com/sloopworks/dayfold/swip/DayfoldMapperTableTest.kt:35,65`
- Modify: `apps/swip-wiring/src/desktopTest/kotlin/com/sloopworks/dayfold/swip/DayfoldLeakTest.kt:101`
- Modify: `apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/SwipAnalyticsGlue.kt:12,95-99,107`

**Interfaces:**
- Consumes: `works.sloop.swip.NoOpErrors` (object, package `works.sloop.swip`, from swip-core `Facades.kt` — satisfies the new `SloopErrors`); `SwipInstance.errors: SloopErrors`; `ConsentScope.ERRORS`.
- Produces: `requireSwip().errors` is a live `SwipErrors` runtime; `swipMiddleware(errors = …)` now feeds real breadcrumbs.

- [ ] **Step 1: Bump the swip-wiring artifacts**

In `apps/swip-wiring/build.gradle.kts`, change lines 24–25:
```kotlin
        api("works.sloop.swip:swip-core:0.1.11")
        api("works.sloop.swip:schema-dayfold:0.1.7")
```

- [ ] **Step 2: Bump swip-logging in androidApp**

In `apps/androidApp/build.gradle.kts`, change the swip-logging line (currently `:0.1.1`):
```kotlin
  debugImplementation("works.sloop.swip:swip-logging:0.1.2")
```

- [ ] **Step 3: Delete Dayfold's hand-rolled `NoOpErrors`**

In `apps/swip-wiring/src/commonMain/kotlin/com/sloopworks/dayfold/swip/DayfoldAnalytics.kt`, delete the whole block (the facade grew methods this no longer implements; swip-core ships the canonical NoOp):
```kotlin
/** swipMiddleware requires a SloopErrors; the analytics-only build has no error runtime. */
object NoOpErrors : SloopErrors {
  override fun record(error: Throwable, attrs: Map<String, JsonElement?>, mechanism: String) {}
  override fun breadcrumb(category: String, message: String) {}
}
```
Then remove the now-unused imports at the top of that file: `import works.sloop.swip.SloopErrors` and `import kotlinx.serialization.json.JsonElement` (verify neither is used elsewhere in the file before removing — `JsonElement` is not; `SloopErrors` is not).

- [ ] **Step 4: Repoint the two tests to swip-core's `NoOpErrors`**

Both test files reference `NoOpErrors` unqualified (same package). Add an explicit import to each and they resolve to swip-core's:

In `DayfoldMapperTableTest.kt` and `DayfoldLeakTest.kt`, add near the other `works.sloop.swip.*` imports:
```kotlin
import works.sloop.swip.NoOpErrors
```
(No call-site change — `swipMiddleware<AppState>(rec, NoOpErrors, …)` is unchanged; the object just comes from swip-core now.)

- [ ] **Step 5: Switch the debug middleware to the real error facade + grant ERRORS consent**

In `apps/androidApp/src/debug/.../SwipAnalyticsGlue.kt`:

Delete the import at line 12: `import com.sloopworks.dayfold.swip.NoOpErrors`.

In `debugStoreEnhancer()`, change `errors = NoOpErrors,` (line ~107) to:
```kotlin
        errors = requireSwip().errors,
```

In `swipInit()`, change the consent grant (currently ANALYTICS only) to grant ERRORS too:
```kotlin
  SwipAnalyticsHolder.swip?.analytics?.setConsent(
    mapOf(
      ConsentScope.ANALYTICS to ConsentDecision.GRANTED,
      ConsentScope.ERRORS to ConsentDecision.GRANTED,
    ),
  )
```

- [ ] **Step 6: Run the swip-wiring desktop suite — the regression gate**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew --no-daemon :swip-wiring:desktopTest`
Expected: PASS (`DayfoldLeakTest`, `DayfoldMapperTableTest` green against 0.1.11). If the compiler reports API drift on `PostHogTransport`, `platformDeps`, `ConsentScope`, or `CollectionMode`, reconcile against the 0.1.11 sources at `~/workspace/sloopworksinstrumentationplatform/sdk-kmp` — **do not downgrade** the artifacts.

- [ ] **Step 7: Compile the debug app — the breaking-change gate for the glue**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew --no-daemon :androidApp:compileDebugKotlin`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add apps/swip-wiring/build.gradle.kts apps/androidApp/build.gradle.kts \
  apps/swip-wiring/src/commonMain/kotlin/com/sloopworks/dayfold/swip/DayfoldAnalytics.kt \
  apps/swip-wiring/src/desktopTest/kotlin/com/sloopworks/dayfold/swip/DayfoldMapperTableTest.kt \
  apps/swip-wiring/src/desktopTest/kotlin/com/sloopworks/dayfold/swip/DayfoldLeakTest.kt \
  apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/SwipAnalyticsGlue.kt
git commit -m "feat(client): consume SWIP 0.1.11/0.1.7/0.1.2 + turn on the error runtime (owned stream)"
```

---

## Task 2: Wire the Sentry crash reporter (the vendor)

Add the `swip-sentry` dependency, inject the DSN via a debug `buildConfigField`, and construct the `CrashReporter` in `swipInit`, feeding it through `SwipPlatformDeps.crashReporter`. Guarded on a non-blank DSN so a no-Infisical build still runs.

**Files:**
- Modify: `apps/androidApp/build.gradle.kts` (add `swip-sentry` dep; add `SENTRY_KOTLIN_EU_DSN` field to the `getByName("debug")` block near lines 55–58)
- Modify: `apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/SwipAnalyticsGlue.kt` (imports; `swipInit` body)

**Interfaces:**
- Consumes: `works.sloop.swip.sentry.initSentryAndroid(context: Context, config: SentryInitConfig): CrashReporter` (**suspend**); `works.sloop.swip.sentry.SentryInitConfig(dsn, region, orgId, projectId, release, dist, environment, debug=false)`; `works.sloop.swip.sentry.SentryRegion.EU`; `works.sloop.swip.errors.CrashReporter`. `SwipPlatformDeps.copy(crashReporter = …)` (data class; default `NoOpCrashReporter`).
- Produces: `swipInit` builds a real `SentryCrashReporter` (or skips it when the DSN is empty).

- [ ] **Step 1: Add the swip-sentry dependency**

In `apps/androidApp/build.gradle.kts`, next to the other SWIP `debugImplementation` lines (near line 133), add:
```kotlin
  // SWIP crash/error reporter (debug ONLY, ADR 0060). Pulls io.sentry:sentry-kotlin-multiplatform
  // + sentry-android transitively — release never references them.
  debugImplementation("works.sloop.swip:swip-sentry:0.1.0")
```

- [ ] **Step 2: Add the DSN buildConfig field (debug block only)**

In `apps/androidApp/build.gradle.kts`, inside `getByName("debug") { … }` (after the `POSTHOG_HOST` line ~58), add:
```kotlin
      // The KMP Sentry project's DSN (ADR 0060). Injected from Infisical at build; empty ⇒
      // crash reporting stays OFF so a no-Infisical debug build still runs. NEVER a literal.
      buildConfigField("String", "SENTRY_KOTLIN_EU_DSN", "\"${System.getenv("SENTRY_KOTLIN_EU_DSN") ?: ""}\"")
```

- [ ] **Step 3: Add imports to the glue**

In `apps/androidApp/src/debug/.../SwipAnalyticsGlue.kt`, add:
```kotlin
import kotlinx.coroutines.runBlocking
import works.sloop.swip.errors.CrashReporter
import works.sloop.swip.sentry.SentryInitConfig
import works.sloop.swip.sentry.SentryRegion
import works.sloop.swip.sentry.initSentryAndroid
```
(`kotlinx.coroutines.Dispatchers` is already imported.)

- [ ] **Step 4: Build the reporter and feed it through the deps**

In `swipInit()`, immediately after `val storage = AndroidSwipStorage(app)…` and before the `Swip.init(` call, add:
```kotlin
  // Crash/error vendor (ADR 0060). initSentryAndroid is suspend (prepares the crash-marker
  // file off-main + recovers a prior crash's marker), and MUST complete before Swip.init, so
  // it is awaited once here. Empty DSN (no Infisical) ⇒ no Sentry, NoOpCrashReporter default.
  // projectId is the KMP project, declared INDEPENDENTLY of the DSN so verifyDsn can catch a
  // wrong-DSN paste (the API's or the legacy project) by failing the boot.
  val crashReporter: CrashReporter = if (BuildConfig.SENTRY_KOTLIN_EU_DSN.isNotBlank()) {
    runBlocking(Dispatchers.IO) {
      initSentryAndroid(
        app,
        SentryInitConfig(
          dsn = BuildConfig.SENTRY_KOTLIN_EU_DSN,
          region = SentryRegion.EU,
          orgId = "o4511720596570112",
          projectId = "4511734711189584",
          release = BuildConfig.VERSION_NAME,
          dist = BuildConfig.VERSION_CODE.toString(),
          environment = "development",
          debug = BuildConfig.DEBUG,
        ),
      )
    }
  } else {
    works.sloop.swip.errors.NoOpCrashReporter
  }
```

Then change the deps `.copy(...)` (currently `.copy(debugSink = SwipInspectorGlue.debugSink())`) to also pass the reporter:
```kotlin
    ).copy(debugSink = SwipInspectorGlue.debugSink(), crashReporter = crashReporter),
```

- [ ] **Step 5: Compile the debug app**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew --no-daemon :androidApp:compileDebugKotlin`
Expected: PASS. (If `NoOpCrashReporter` is not at `works.sloop.swip.errors`, grep `~/workspace/sloopworksinstrumentationplatform/sdk-kmp/swip-core` for `object NoOpCrashReporter` and use its actual package.)

- [ ] **Step 6: Commit**

```bash
git add apps/androidApp/build.gradle.kts apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/SwipAnalyticsGlue.kt
git commit -m "feat(client): construct the Sentry CrashReporter and feed it through SwipPlatformDeps"
```

---

## Task 3: Hoist init to a custom `Application` + main-process guard

Move `swipInit` out of `MainActivity.onCreate` into a new `Application.onCreate`, so Sentry's `UncaughtExceptionHandler` installs in the earliest app code and catches startup crashes. `Application.onCreate` runs per-process, so add a main-process guard to the debug `swipInit`.

**Files:**
- Create: `apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/DayfoldApp.kt`
- Modify: `apps/androidApp/src/main/AndroidManifest.xml:22-26` (add `android:name`)
- Modify: `apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/MainActivity.kt:153-157` (drop the `swipInit(application)` call)
- Modify: `apps/androidApp/src/debug/.../SwipAnalyticsGlue.kt` (`swipInit` main-process guard)

**Interfaces:**
- Consumes: `swipInit(app: Application)` (variant glue: real in debug, `= Unit` in release); `works.sloop.swip.platform.isMainProcess(app)` (already imported in the debug glue).
- Produces: `DayfoldApp : Application` — the registered application; `swipInit` guaranteed to run before any activity.

- [ ] **Step 1: Add the main-process guard to the debug `swipInit`**

In `apps/androidApp/src/debug/.../SwipAnalyticsGlue.kt`, make the first lines of `swipInit`:
```kotlin
fun swipInit(app: Application) {
  // Application.onCreate fires in EVERY process; only the main (UI) process runs the pipeline
  // + Sentry. A second process here would double-init Sentry and contend on the crash marker.
  if (!isMainProcess(app)) return
  if (SwipAnalyticsHolder.swip != null) return
  …
```

- [ ] **Step 2: Create the custom Application**

Create `apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/DayfoldApp.kt`:
```kotlin
package com.sloopworks.dayfold.android

import android.app.Application

/**
 * Hosts SWIP init in the EARLIEST app code (ADR 0060): the crash handler must be installed
 * before anything can crash during startup. `swipInit` resolves to the debug glue (real) or
 * the release glue (inert `= Unit`), so this class stays SWIP-free and release keeps zero bytes.
 */
class DayfoldApp : Application() {
  override fun onCreate() {
    super.onCreate()
    swipInit(this)
  }
}
```

- [ ] **Step 3: Register it in the manifest**

In `apps/androidApp/src/main/AndroidManifest.xml`, add `android:name` to the `<application>` tag:
```xml
    <application
        android:name=".DayfoldApp"
        android:enableOnBackInvokedCallback="true"
        android:label="Dayfold"
        android:usesCleartextTraffic="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
```

- [ ] **Step 4: Drop the redundant call in MainActivity**

In `apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/MainActivity.kt`, delete the `swipInit(application)` line (~165) and update the preceding comment. The store is still created below with `debugStoreEnhancer()`; `Application.onCreate` has already run `swipInit`, so `requireSwip()` resolves. Replace:
```kotlin
    // SWIP analytics runtime (debug builds only; inert mirror in release, ADR 0055). Must
    // run BEFORE store creation — debugStoreEnhancer() below reads the swip instance.
    swipInit(application)
```
with:
```kotlin
    // SWIP init runs in DayfoldApp.onCreate (ADR 0060) — before any activity — so the crash
    // handler is installed early and requireSwip() below is ready. Nothing to do here.
```

- [ ] **Step 5: Build the debug APK**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew --no-daemon :androidApp:assembleDebug`
Expected: PASS.

- [ ] **Step 6: Verify release stays zero-SWIP-bytes**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew --no-daemon :androidApp:assembleRelease`
Then confirm no SWIP classes in the release build (ADR 0055 guarantee):
```bash
cd apps && find androidApp/build -path '*release*' -name '*.dex' | head -1
# unzip the release APK's classes and grep, OR (cheaper) confirm DayfoldApp is the only new main/ class and swip-sentry is debugImplementation:
grep -rn "works.sloop.swip" androidApp/src/main androidApp/src/release && echo "LEAK — main/release references SWIP" || echo "clean: no SWIP in main/ or release/"
```
Expected: `clean: no SWIP in main/ or release/` (the `DayfoldApp` class references only `swipInit`, whose release impl is inert).

- [ ] **Step 7: Commit**

```bash
git add apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/DayfoldApp.kt \
  apps/androidApp/src/main/AndroidManifest.xml \
  apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/MainActivity.kt \
  apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/SwipAnalyticsGlue.kt
git commit -m "feat(client): hoist SWIP init to a custom Application (earliest crash coverage) + main-process guard"
```

---

## Task 4: Debug-drawer trigger for the handled path

Add two debug-only glue functions and a `DebugPlugin` with two buttons, so the on-device smoke (Task 5) can exercise `wtf()` and a forced crash without any user-facing surface.

**Files:**
- Modify: `apps/androidApp/src/debug/.../SwipAnalyticsGlue.kt` (add `swipDebugFireWtf`, `swipDebugFireCrash`)
- Create: `apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/SwipErrorsTriggerPlugin.kt`
- Modify: `apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/DebugDrawerPlugins.kt`

**Interfaces:**
- Consumes: `SwipInstance.errors.wtf(key, message, attrs, severity)`; `com.sloopworks.debugdrawer.DebugPlugin` (`val id`, `val title`, `@Composable fun Content(scope: DebugScope)`).
- Produces: `swipDebugFireWtf()`, `swipDebugFireCrash()`; `SwipErrorsTriggerPlugin`.

- [ ] **Step 1: Add the trigger functions to the glue**

In `apps/androidApp/src/debug/.../SwipAnalyticsGlue.kt`, add near the other public glue functions:
```kotlin
/** Debug-only smoke (ADR 0060 §8). Fires a deliberate non-crash report through the pillar. */
fun swipDebugFireWtf() {
  SwipAnalyticsHolder.swip?.errors?.wtf(
    key = "dayfold.client.smoke",
    message = "deliberate client non-crash report",
    attrs = mapOf("surface" to "android-debug"),
    severity = works.sloop.swip.ErrorSeverity.ERROR,
  )
}

/** Debug-only smoke: an unhandled throw → Sentry's global handler → marker → mirrored next launch. */
fun swipDebugFireCrash(): Nothing =
  throw IllegalStateException("dayfold client smoke: deliberate unhandled crash")
```

- [ ] **Step 2: Create the trigger plugin**

Create `apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/SwipErrorsTriggerPlugin.kt`. Match the Compose imports the other drawer plugins use (check `SwipInspectorPlugin` / the drawer module if `material3` differs):
```kotlin
package com.sloopworks.dayfold.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sloopworks.debugdrawer.DebugPlugin
import com.sloopworks.debugdrawer.DebugScope

/**
 * Debug-only (ADR 0060 §8): fires the SWIP error pillar on demand so the handled + crash paths
 * can be proven on a real device. Never shown in any user-facing surface.
 */
class SwipErrorsTriggerPlugin : DebugPlugin {
  override val id = "swip-errors-trigger"
  override val title = "SWIP Errors (smoke)"

  @Composable
  override fun Content(scope: DebugScope) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { swipDebugFireWtf() }) { Text("Fire wtf() → PostHog + Sentry") }
      Button(onClick = { swipDebugFireCrash() }) { Text("Fire crash → Sentry (mirrors next launch)") }
    }
  }
}
```

- [ ] **Step 3: Register the plugin**

In `apps/androidApp/src/debug/.../DebugDrawerPlugins.kt`, add to the `buildList`:
```kotlin
  add(SwipErrorsTriggerPlugin())
```

- [ ] **Step 4: Build the debug APK**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew --no-daemon :androidApp:assembleDebug`
Expected: PASS. (If `material3` symbols don't resolve, match the Compose material import the existing plugins/screens use — grep the debug source for `androidx.compose.material`.)

- [ ] **Step 5: Commit**

```bash
git add apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/SwipAnalyticsGlue.kt \
  apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/SwipErrorsTriggerPlugin.kt \
  apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/DebugDrawerPlugins.kt
git commit -m "feat(client): debug-drawer trigger to smoke the SWIP error pillar (wtf + crash)"
```

---

## Task 5: On-device smoke — the real evidence

A green build is not proof; a lost event passes every test. Install a debug build with real credentials, exercise both paths on the Pixel, and record the vendor evidence (event ids + matching fingerprints), exactly as the API PR (#336) did. No code — this task produces the evidence block for the PR/ADR.

**Files:** none (verification).

- [ ] **Step 1: Build + install with real credentials**

```bash
cd ~/workspace && infisical run --path=/dayfold -- env \
  DAYFOLD_API=https://family-ai-dashboard.vercel.app FAMILY_ID=<dogfood> HOUSEHOLD_SECRET=<dogfood> \
  bash -c 'cd dayfold/apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew --no-daemon :androidApp:installDebug'
```
Expected: installed on the connected Pixel. Confirm in logcat that Sentry initialized: `adb logcat -d | grep -i "Sentry\|swip"` shows init, no `MALFORMED`/`MISMATCH` (a wrong DSN would boot-fail loudly — that is the guard working).

- [ ] **Step 2: Handled path — fire wtf()**

Open the debug drawer → "SWIP Errors (smoke)" → "Fire wtf()". Then confirm:
- **PostHog** (EU project): a `swip:event:error:1` with `key=dayfold.client.smoke`, `handled=true`, and an `error.fingerprint`.
- **Sentry** (KMP project `4511734711189584`): a message event tagged `swip.fingerprint == <that error.fingerprint>`, `swip.wtf_key=dayfold.client.smoke`, `environment=development`, no `request`/`extra`.
- **Locally:** the same event appears in the SWIP inspector drawer (ADR 0057).

Record the PostHog `$insert_id` + Sentry event id + the shared fingerprint.

- [ ] **Step 3: Fatal path — force a crash, then relaunch**

Debug drawer → "Fire crash". The app dies. **Relaunch it.** Then confirm:
- **Sentry:** the crash under its own grouping (its own stack), `environment=development`.
- **PostHog:** a mirrored `swip:event:error:1` with `handled=false` (arrives after relaunch, via the crash-marker file).

Note honestly: these correlate by type/message/time, **not** by a shared id (a Sentry-caught crash carries no `swip.fingerprint`; only the handled path in Step 2 is id-joined).

- [ ] **Step 4: Record the evidence**

Write the two-row evidence table (handled: id-joined on fingerprint; fatal: correlated) into a scratch note for the PR body + ADR. Real ids, not a claim.

---

## Task 6: ADR 0060 + CHANGELOG + backlog + SWIP gap report

**Files:**
- Create: `adr/0060-client-crash-error-reporting.md`
- Modify: `adr/decisions-index.md` (append the 0060 row)
- Modify: `CHANGELOG.md` (dated entry)
- Modify: `backlog/now.md` (operator-actions + follow-ups)

- [ ] **Step 1: Write ADR 0060**

Create `adr/0060-client-crash-error-reporting.md` following the format of `adr/0055` and `adr/0058`. It MUST record: the vendor + project (Sentry KMP `4511734711189584`, EU) and why not the Node (`…82820432`)/legacy DSN; debug-only scope + the honest consent argument (a client error can carry the device's `distinct_id`, so debug-only-on-the-operator's-device is what makes granting `ERRORS` honest — not a release precedent); the independent-id wrong-project guard; the custom-`Application` hoist + main-process guard; the marker-file fatal mirror + the fatal-vs-handled join distinction; slice-1 contents (crashes + breadcrumbs + debug trigger; no production handled site, and why sync-failure was rejected); and the release-scope blockers — the SWIP `consented`-gate gap and a consent surface / privacy disclosure. Status: `Proposed 2026-07-14 (agent-drafted; accept on merge)`.

- [ ] **Step 2: Index + CHANGELOG + backlog**

Append the ADR 0060 row to `adr/decisions-index.md`. Add a dated `CHANGELOG.md` entry (internal: "Android debug/dogfood builds now report crashes + errors through SWIP → Sentry (KMP project) + PostHog"). Add to `backlog/now.md`: operator follow-ups (accept ADR 0060; the release-scope future ADR is blocked on the SWIP consent gate + a consent surface).

- [ ] **Step 3: File the SWIP gap (report, do not patch)**

Open an issue against `~/workspace/sloopworksinstrumentationplatform` (via `gh issue create` in that repo, or note it for the operator to file): *`initSentryAndroid` has no `consented: () -> Boolean` parameter — the TS `initSentryNode` requires one. Sentry inits globally and captures immediately, so a product cannot gate the SDK on `ConsentScope.ERRORS`. Fine for debug-only-granted; blocks any Dayfold release-scope error reporting until the KMP Sentry init gains a consent gate (`beforeSend → drop` when ERRORS denied).* Record the issue URL in the ADR's follow-ups.

- [ ] **Step 4: Commit**

```bash
git add adr/0060-client-crash-error-reporting.md adr/decisions-index.md CHANGELOG.md backlog/now.md
git commit -m "docs(adr): ADR 0060 — client crash/error reporting (debug-only Android)"
```

---

## Self-Review

**Spec coverage:** §2 scope → Global Constraints + T1–T2. §3 slice-1 (crashes+breadcrumbs+trigger, no handled site) → T1 (breadcrumbs), T2 (crash reporter), T4 (trigger). §4 artifacts → T1–T2. §5.0 Application hoist + process guard → T3. §5.1–5.5 reporter/consent/DSN → T2 + T1 (consent). §5.6 debug trigger → T4. §6 wrong-project guard → T2 (constant `projectId`, Global Constraints). §7 sync-failure rejection → recorded in ADR (T6). §8 verification → T5 + build steps throughout. §9 gaps (startup closed; SWIP consent gap; iOS; handled sites; no R8) → T3 (closed), T6 (reported/recorded). §10 ADR → T6. All covered.

**Placeholders:** none — every code step shows exact code; the two `<dogfood>` tokens in T5 Step 1 are operator-supplied secrets, correctly not committed.

**Type consistency:** `swipInit(app: Application)`, `requireSwip().errors`, `initSentryAndroid(ctx, SentryInitConfig)→CrashReporter`, `SwipPlatformDeps.copy(crashReporter=…)`, `errors.wtf(key,message,attrs,severity)`, `ErrorSeverity.ERROR`, `DebugPlugin(id/title/Content)` — consistent across tasks. One deliberately-flagged uncertainty: `NoOpCrashReporter`'s exact package (`works.sloop.swip.errors`) — T2 Step 5 tells the implementer to confirm it against source, since a wrong package is a compile error caught immediately.
