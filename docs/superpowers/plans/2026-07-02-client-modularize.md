# TASK-CLIENT-MODULARIZE Implementation Plan (rev 2, post round-2 panel)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Speed up / de-risk the client iteration loop by first measuring, then enabling Gradle build/config caching, then extracting the 40 Compose files into a `:ui` module so a UI edit stops recompiling core + logic tests.

**Architecture:** Three phases with gates. P0 measures (is incremental compilation already effective?). P1 enables `build-cache`/`configuration-cache`/`parallel`. P2 extracts a `:ui` KMP module that `api`-depends on `:client` (engines/data/store stay; no dependency inversion). Operator gate + ADR gate before P2.

**Tech Stack:** Kotlin 2.3.20 MP, Compose-MP 1.9.3(desktop)/1.11.1(android), AGP 9.2.1, Gradle 9.4.1, SQLDelight 2.3.2, ktor 3.5.0, redux-kotlin 1.0.0-alpha03, JDK 17.

## Global Constraints

- **JDK 17**; **run from `apps/`**; `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- New `:ui` module `api(project(":client"))` (mandatory — apps use core types through it).
- **Move rule (MECHANICAL, commonMain):** move to `:ui` iff imports `androidx.compose`/`org.jetbrains.compose`/`@Composable`/`coil3`. Verified = **40 files**. Plus the named platform actuals + entries (below). Everything else stays.
- **Stay in `:client` (Compose-free):** `cards/CardAction.kt`, `cards/DetailMeta.kt`, `cards/TypedCardLogic.kt` + tests `DetailMetaTest`, `TypedCardLogicTest`.
- **Three expect/actual seams move to `:ui`:** `QrScanner`, `cards/PlatformActions`, `rememberReduceMotion` (`ui/loading/Shimmer.kt` + `ReduceMotion.{android,desktop,ios}.kt`) — each with all android+desktop+ios actuals. `DriverFactory` stays.
- **`linkrules` srcDir stays single-homed in `:client`** (never duplicate into `:ui`). Moved UI uses only its public `linkify`/`MediaValidation`.
- **CROSS-MODULE `internal` CONTRACT (round-2 critical):** `internal` is *module*-scoped. Any symbol that STAYS in `:client` but is referenced by a MOVED file/test must be `public`. Known cases to promote: `selectScale` (`TimelinePresenter.kt:166`, used by `HubScreens.kt:471`), `cardToNowItem` (`NowFeed.kt:52`, used by `NowFeedScreenTest`). More surface at compile — promote each as it appears. Also keep the iOS/desktop entry glue public (`IosTokenStore`, `IosContentStoreHolder`, `IosNotifGlue`, `IosDeepLinkBus`, `reRegisterGeofences()`, `reconcileExactSchedules()`, `FileTokenStore`, `fake.fakeClientForApi`).
- `:ui` android: `namespace="com.sloopworks.dayfold.client.ui"`, `compileSdk=37`, `minSdk=33`, `sourceCompatibility/targetCompatibility=VERSION_17`, `jvmToolchain(17)`.
- **iOS:** `iosArm64()`+`iosSimulatorArm64()` ONLY (no iosX64) on BOTH modules; `:ui` declares `binaries.framework { baseName="client"; isStatic=true }`; `:client` keeps the two targets but **drops** `binaries.framework`. No `export()`. Generated framework header: `apps/ui/build/bin/iosSimulatorArm64/debugFramework/client.framework/Headers/client.h`.
- `:client` must end **Compose-free** (grep-verified). `Reducer.kt`'s `compose(...)` is redux **core** (from `redux-kotlin-concurrent`, stays) — no change needed; `FeedApp`'s `selectorState` needs `redux-kotlin-compose` moved to `:ui`.
- **Operator gate (P1→P2):** if the split win is marginal, STOP + prompt operator. **ADR gate:** Proposed ADR Accepted before P2.
- **No behavior/UX change.** Every phase keeps the full suite green. **Refactor verification = existing suite green + all targets compile + grep invariants**, not new behavioral tests.
- **Measurement method:** warm daemon; for **cold-full** compile numbers use `clean` (wipe the module `build/`) — `--rerun-tasks` alone still hits Kotlin IC caches; for **incremental** numbers re-edit the file each iteration. Kotlin build reports land in **`apps/build/kotlin-reports/`** (root), metric label ≈ "Number of source files compiled". Record in `specs/client-modularize-measurements.md`, labeling each number cold-full vs incremental.

---

## PHASE 0 — Measure (unconditional; gates the split's speed claim)

### Task 0.1: Kotlin build reports + baselines (inner loop AND full build)

**Files:** Modify `apps/gradle.properties`; Create `specs/client-modularize-measurements.md`.

- [ ] **Step 1:** Append to `apps/gradle.properties`:
```
# CL-MODULARIZE P0 — attribute compile time + incremental-recompile size
kotlin.build.report.output=file
kotlin.build.report.file.output_dir=build/kotlin-reports
```
- [ ] **Step 2 — incremental inner loop (the C1 signal):** warm daemon; edit one UI file body (blank line in `FeedScreen.kt`) **before each** run:
```
cd apps; export JAVA_HOME=$(/usr/libexec/java_home -v 17)
for i in 1 2 3; do printf '\n//probe %s\n' $i >> client/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedScreen.kt; \
  ./gradlew :client:compileTestKotlinDesktop --profile; done
git checkout -- client/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedScreen.kt
```
Read `apps/build/kotlin-reports/*` for **"Number of source files compiled: N"** per run. **N≈1–2 ⇒ IC effective ⇒ split marginal; N≈all ⇒ broad recompile ⇒ split delivers.** Also note config-time from `apps/build/reports/profile/*.html`.
- [ ] **Step 3 — cold-full compile baselines (main vs test, separated):**
```
cd apps
./gradlew :client:clean; ./gradlew :client:compileKotlinDesktop --profile         # main cold
./gradlew :client:clean; ./gradlew :client:compileTestKotlinDesktop --profile     # test cold
```
- [ ] **Step 4 — whole-repo full-build baseline (for the +5–15% regression, DoD):**
```
cd apps && ./gradlew clean && ./gradlew assembleDebug :client:desktopTest --profile   # wall time
```
- [ ] **Step 5:** Record all numbers in `specs/client-modularize-measurements.md` (table: metric | cold-full | incremental-N | wall), + the C1 verdict + the full-build baseline.
- [ ] **Step 6:** `git add apps/gradle.properties specs/client-modularize-measurements.md && git commit -m "CL-MODULARIZE P0: build reports + inner-loop + full-build baselines"`

---

## PHASE 1 — Cheap build levers

### Task 1.1: build-cache + parallel; re-measure
**Files:** Modify `apps/gradle.properties`, `specs/client-modularize-measurements.md`.
- [ ] **Step 1:** add `org.gradle.caching=true` and `org.gradle.parallel=true`.
- [ ] **Step 2:** `cd apps && ./gradlew :client:desktopTest` → BUILD SUCCESSFUL.
- [ ] **Step 3:** re-measure the incremental inner loop (0.1 Step 2 method) + a cold-daemon proxy (`./gradlew --stop` then time `assembleDebug :client:desktopTest`). Append.
- [ ] **Step 4:** `git commit -am "CL-MODULARIZE P1a: build-cache + parallel; re-measure"`

### Task 1.2: configuration-cache spike (exercise the ANDROID + iOS graph)
**Files:** Modify `apps/gradle.properties` (+ build scripts only if a task needs a CC fix), `specs/client-modularize-measurements.md`.
- [ ] **Step 1:** set `org.gradle.configuration-cache=true`. Test the graphs that actually matter (not just desktopTest):
```
cd apps && ./gradlew :androidApp:assembleDebug        # google-services is the prime CC suspect
./gradlew :client:linkDebugFrameworkIosSimulatorArm64
./gradlew :client:desktopTest
```
- [ ] **Step 2 — branch:**
  - **All store "Configuration cache entry stored" + reuse on 2nd run:** keep it on; record the config-time delta.
  - **A task is CC-incompatible** (likely `com.google.gms.google-services`, or a CL-SNAP-style custom task if #277 merged): capture the exact incompatible-task report; attempt the standard fix (no `Project` at execution time / use `providers`/`layout`). If not cleanly fixable in budget, **revert to `false`** and record the blocker + offending task as a filed follow-up. Do NOT force it.
- [ ] **Step 3:** re-measure; append.
- [ ] **Step 4:** `git commit -am "CL-MODULARIZE P1b: configuration-cache spike (on, or blocker recorded)"`

---

## ⛔ GATE (operator + ADR) — after Phase 1

Implementer STOPS and reports measured numbers. Compute projected P2 win = (P1 test-compile) − (estimated `:ui`-only test-compile, informed by the C1 N-files signal).
- **Marginal** (< ~1s, or P1 captured most of it): **prompt the operator to discuss** before P2.
- **Worthwhile:** author the **Proposed ADR** (module architecture) → operator **Accepts** → proceed.

---

## PHASE 2 — Extract `:ui` (gated)

### Task 2.1: Scaffold empty `:ui` KMP module (NO compose.resources block yet)
**Files:** Create `apps/ui/build.gradle.kts`, `apps/ui/src/commonMain/kotlin/.gitkeep`; Modify `apps/settings.gradle.kts`.
- [ ] **Step 1:** add `include(":ui")` to `apps/settings.gradle.kts`.
- [ ] **Step 2:** write `apps/ui/build.gradle.kts` — plugins `kotlin("multiplatform")`, `plugin.compose`, `org.jetbrains.compose`, `com.android.library` (+ `plugin.serialization` only if a moved file needs it); `jvmToolchain(17)`; `androidTarget()`; `jvm("desktop")`; `listOf(iosArm64(), iosSimulatorArm64()).forEach { it.binaries.framework { baseName="client"; isStatic=true } }`. commonMain: `api(project(":client"))` + Compose (runtime/foundation/material3/ui), `compose.components.resources`, `compose.materialIconsExtended`, `org.jetbrains.compose.ui:ui-backhandler`, `io.coil-kt.coil3:coil-compose`+`coil-network-ktor3`, `org.reduxkotlin:redux-kotlin-compose:1.0.0-alpha03`. androidMain (`implementation`): CameraX core/camera2/lifecycle/view, `mlkit:barcode-scanning`, `activity-compose`, `lifecycle-runtime-compose`. desktopMain: `compose.desktop.currentOs`. desktopTest: `compose.uiTest`, `kotlin("test")`. `android { namespace="com.sloopworks.dayfold.client.ui"; compileSdk=37; defaultConfig{minSdk=33}; compileOptions{VERSION_17} }`. `compose.desktop { application { mainClass="com.sloopworks.dayfold.client.MainKt" } }`. **Do NOT add `compose.resources {}` yet** (deferred to 2.2a to avoid a duplicate-`Res` window while `:client` still owns it).
- [ ] **Step 3:** verify empty module compiles: `cd apps && ./gradlew :ui:compileKotlinDesktop :ui:compileDebugKotlinAndroid :ui:compileKotlinIosSimulatorArm64` → SUCCESS.
- [ ] **Step 4:** `git add apps/settings.gradle.kts apps/ui/ && git commit -m "CL-MODULARIZE P2.1: scaffold empty :ui KMP module (api-depends :client)"`

### Task 2.2a: Move ALL Compose (main+tests+resources+entries) to `:ui`, rewire app, promote internals — ONE GREEN COMMIT
**Files:** `git mv` the move-set + Compose/render tests + `composeResources/` + entries from `apps/client/src/**` → `apps/ui/src/**`; Modify `apps/client/build.gradle.kts` (move the `compose.resources{}` block OUT), `apps/ui/build.gradle.kts` (add the `compose.resources{}` block), `apps/androidApp/build.gradle.kts` (dep `:client`→`:ui`), and promote internals in `:client`.

- [ ] **Step 1 — move main files:** `cd apps/client/src/commonMain/kotlin && for f in $(grep -rlE "androidx\.compose|org\.jetbrains\.compose|coil3|@Composable" .); do mkdir -p "../../../../ui/src/commonMain/kotlin/$(dirname $f)"; git mv "$f" "apps/ui/src/commonMain/kotlin/$f"; done` (run from repo root with correct paths). Then `git mv` the platform actuals (`QrScanner.{android,desktop,ios}`, `cards/PlatformActions.{android,desktop,ios}`, `ui/loading/ReduceMotion.{android,desktop,ios}`), desktop `Main.kt`, iosMain `MainViewController.kt`, and the `composeResources/` dir → mirror paths under `apps/ui/src/**`.
- [ ] **Step 2 — move the Compose/render tests** (compile-driven rule: a test moves iff it references a moved symbol). Move the 29 tests matching `grep -rlE "runComposeUiTest|compose.ui.test" apps/client/src/desktopTest` PLUS the symbol-referencing ones the grep misses: `CardRenderTest`, `cards/PlatformActionsTest`, `cards/PlatformUriHandlerTest`, `BlockMarkdownTest`, `DayfoldThemeTest`, `EnrichmentValidationTest`, `NowFeedScreenTest`. **Keep** logic tests incl. `DetailMetaTest`, `TypedCardLogicTest` in `:client`.
- [ ] **Step 3 — resources block relocation (atomic):** remove the `compose.resources {}` block from `apps/client/build.gradle.kts`; add it to `apps/ui/build.gradle.kts` (`publicResClass=false; packageOfResClass="com.sloopworks.dayfold.client.generated"; generateResClass=auto`). (Fonts dir already moved in Step 1.)
- [ ] **Step 4 — rewire app:** in `apps/androidApp/build.gradle.kts` change `implementation(project(":client"))` → `implementation(project(":ui"))`.
- [ ] **Step 5 — compile-driven internal promotion:** `cd apps && ./gradlew :ui:compileKotlinDesktop`. For every `cannot access '<sym>': it is internal` error where `<sym>` lives in a staying `:client` file, change that declaration to `public` (add `// public: consumed cross-module by :ui`). Known: `selectScale` (`TimelinePresenter.kt`), `cardToNowItem` (`NowFeed.kt`). Repeat until `:ui` compiles. (If a symbol shouldn't be public API, extract a public wrapper — but for this slice, promote.)
- [ ] **Step 6 — verify GREEN at this commit (all of it):**
```
cd apps
./gradlew :client:compileKotlinDesktop :client:desktopTest          # :client still has (unused) compose deps → green; logic tests only
./gradlew :ui:compileKotlinDesktop :ui:compileDebugKotlinAndroid :ui:compileKotlinIosSimulatorArm64 :ui:desktopTest
./gradlew :androidApp:assembleDebug
./gradlew :ui:linkDebugFrameworkIosSimulatorArm64                    # iOS de-risk (highest risk)
find apps/ui/build -name client.h | xargs grep -l MainViewController # entry exported?
```
Expected: all SUCCESS; `client.h` contains `MainViewController`; sum(`:client`+`:ui` desktopTest counts) == pre-split total.
- [ ] **Step 7:** `git add -A && git commit -m "CL-MODULARIZE P2.2a: move Compose (main+tests+resources+entries) to :ui, rewire app, promote cross-module internals"`

### Task 2.2b: Strip Compose from `:client` → Compose-free
**Files:** Modify `apps/client/build.gradle.kts`.
- [ ] **Step 1:** remove from `:client`: the compose plugins (`plugin.compose`, `org.jetbrains.compose`), all `compose.*` deps, `compose.components.resources`, `materialIconsExtended`, `ui-backhandler`, coil, `redux-kotlin-compose`, `compose.uiTest` (desktopTest), the `compose.desktop {}` block, and `binaries.framework {}` (keep the two iOS targets).
- [ ] **Step 2 — verify Compose-free + green:**
```
cd apps && ./gradlew :client:compileKotlinDesktop :client:compileDebugKotlinAndroid :client:compileKotlinIosSimulatorArm64 :client:desktopTest
grep -rE "androidx\.compose|org\.jetbrains\.compose|@Composable|coil3" client/src && echo "LEFTOVER" || echo "client Compose-free ✓"
```
Expected: SUCCESS + "Compose-free". If `Reducer.kt`'s `compose(...)` fails, qualify to the redux-core symbol (per Global Constraints it should resolve unchanged).
- [ ] **Step 3:** `git commit -am "CL-MODULARIZE P2.2b: strip Compose deps/plugin/framework from :client (Compose-free)"`

### Task 2.3: Measure the split; verify full DoD (incl. release + iOS); update docs
**Files:** Modify `specs/client-modularize-measurements.md`, `processes/agent-dev-loop.md`, `backlog/next.md`, `CHANGELOG.md`, the Accepted ADR.
- [ ] **Step 1 — measure post-split:** edit one `:ui` composable body; `./gradlew :ui:compileTestKotlinDesktop`; confirm the kotlin-report shows `:client` **up-to-date** (not recompiled) and the logic tests skipped; record `:ui`-edit recompile time vs P0/P1 baselines.
- [ ] **Step 2 — full DoD:**
```
cd apps
./gradlew :client:desktopTest :ui:desktopTest                    # full suite green, counts preserved
./gradlew :androidApp:assembleDebug :androidApp:assembleRelease  # BOTH variants (release: devtools-core exclusion)
./gradlew :ui:linkDebugFrameworkIosSimulatorArm64
```
- [ ] **Step 3 — docs:** record before/after in the measurements doc; update `agent-dev-loop.md` (`:client`/`:ui` map + which module a UI edit recompiles); mark `TASK-CLIENT-MODULARIZE` done in `backlog/next.md`; CHANGELOG (internal — optional); reconcile the Accepted ADR to the final shape.
- [ ] **Step 4:** `git add -A && git commit -m "CL-MODULARIZE P2.3: measure split, verify DoD (debug+release+iOS), update docs+ADR"`

---

## Self-Review

**Spec coverage:** P0 §4 → T0.1 (now incremental + cold-full + full-build, corrected method) ✅; P1 §5 → T1.1/T1.2 (CC spike exercises android+iOS graphs) ✅; operator+ADR gate §2 → ⛔ GATE ✅; mechanical move §3/§6a → T2.2a Step 1 (grep) ✅; **cross-module internal contract (round-2 C1/C2)** → Global Constraints + T2.2a Step 5 ✅; deps incl redux-kotlin-compose §6b → T2.1/T2.2b ✅; compose.resources atomic relocation (Android C1) → T2.1 (no block) + T2.2a Step 3 ✅; SDK values §6d → Global Constraints + T2.1 ✅; iOS framework + link de-risk + **correct header path** §6e → T2.2a Step 6 ✅; test split incl the 6 grep-missed tests (round-2 I1/I2) → T2.2a Step 2 ✅; **assembleRelease** (round-2 I1) → T2.3 Step 2 ✅; full-build regression (round-2 I-4) → T0.1 Step 4 + T2.3 ✅; DoD §9 → T2.3 ✅.

**Green-at-boundary (round-2 C-1/C-2 fixed):** T2.1 empty `:ui` compiles; **T2.2a is one atomic green commit** (move + tests + resources + app-rewire + internal-promotion — `:client` keeps unused compose deps so it stays green); **T2.2b** strips `:client` (green). No red intermediate commit.

**Placeholder scan:** the 40-file move + internal-promotion are compile-driven procedures (grep + iterate-on-compile-errors), not hand-lists or TBDs — the correct shape for a mechanical refactor with unknown-count visibility fixes. Known cases named (`selectScale`, `cardToNowItem`).

**Type/naming consistency:** `:client`/`:ui`, namespace `…client.ui`, framework `client`/isStatic, `packageOfResClass=…client.generated`, SDK 37/33/17, iosArm64+iosSimulatorArm64, mainClass `MainKt`, header path `apps/ui/build/bin/iosSimulatorArm64/debugFramework/client.framework/Headers/client.h` — consistent across constraints + tasks.
