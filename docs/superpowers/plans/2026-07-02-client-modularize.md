# TASK-CLIENT-MODULARIZE Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Speed up (and de-risk) the client build/iteration loop by first measuring, then enabling Gradle build/config caching, then extracting the 40 Compose files into a `:ui` module so a UI edit stops recompiling the core + logic tests.

**Architecture:** Three phases with gates. Phase 0 measures the real cost breakdown (is incremental compilation already effective?). Phase 1 enables `build-cache`/`configuration-cache`/`parallel`. Phase 2 extracts a new `:ui` KMP module that `api`-depends on `:client` (engines/data/store stay in `:client`; no dependency inversion). An **operator gate** sits between Phase 1 and Phase 2.

**Tech Stack:** Kotlin 2.3.20 multiplatform, Compose-MP 1.9.3 (desktop) / 1.11.1 (android), AGP 9.2.1, Gradle 9.4.1, SQLDelight 2.3.2, ktor 3.5.0, redux-kotlin 1.0.0-alpha03, JDK 17.

## Global Constraints

- **JDK 17** for all Gradle; **run every command from `apps/`** (single root). Resolve JDK17 via `/usr/libexec/java_home -v 17`.
- Modules: existing `:client` (becomes "core"), new `:ui`, apps `:androidApp` + desktop entry + iOS framework consumer. `:ui` **`api(project(":client"))`** (mandatory — apps use core types through it).
- **Move rule is MECHANICAL:** a `commonMain` file moves to `:ui` iff it imports `androidx.compose` / `org.jetbrains.compose` / `@Composable` / `coil3`. Verified move-set = **40 files**. Everything else stays in `:client`.
- **Stay in `:client` (Compose-free, core depends on them):** `cards/CardAction.kt`, `cards/DetailMeta.kt`, `cards/TypedCardLogic.kt` + their tests `DetailMetaTest`, `TypedCardLogicTest`.
- **Three expect/actual seams move to `:ui`:** `QrScanner`, `cards/PlatformActions`, `rememberReduceMotion` (`ui/loading/Shimmer.kt` + `ReduceMotion.{android,desktop,ios}.kt`). `DriverFactory` (SQLDelight) **stays** in `:client`.
- **`linkrules` srcDir stays single-homed in `:client`** — never duplicate `kotlin.srcDir("../../packages/linkrules")` into `:ui` (FQN clash).
- `:ui` android: `namespace = "com.sloopworks.dayfold.client.ui"`, `compileSdk = 37`, `minSdk = 33`, `sourceCompatibility/targetCompatibility = VERSION_17`, `jvmToolchain(17)` — mirror `:client` exactly.
- **iOS:** targets `iosArm64()` + `iosSimulatorArm64()` ONLY (no iosX64) on BOTH modules; `:ui` declares `binaries.framework { baseName = "client"; isStatic = true }`; `:client` **drops** `binaries.framework` but keeps the two iOS targets. No `export()` needed. `:client` iosMain glue consumed by `:ui` entry points (`IosTokenStore`, `IosContentStoreHolder`, `IosNotifGlue`, `IosDeepLinkBus`, `reRegisterGeofences()`, `reconcileExactSchedules()`, desktop `FileTokenStore`, `fake.fakeClientForApi`) **must stay `public`**.
- **`:client` must end with ZERO Compose** on its classpath (grep-verified). Includes checking `Reducer.kt`'s `import org.reduxkotlin.compose` + `compose(...)` resolves from redux **core** after `redux-kotlin-compose` leaves.
- **Operator gate (Phase 1 → Phase 2):** if the measured split win is marginal, STOP and prompt the operator; do not auto-proceed.
- **ADR gate:** Phase 2 is ADR-class — a Proposed ADR must be operator-Accepted before Phase 2 implementation.
- **No behavior/UX change** anywhere. Every phase keeps the full test suite green.
- Measurements method: warm daemon, N=5 discard-first; record numbers in `specs/client-modularize-measurements.md`.

---

## PHASE 0 — Measure (unconditional; gates the split's speed claim)

### Task 0.1: Enable Kotlin build reports + capture the baseline

**Files:**
- Modify: `apps/gradle.properties`
- Create: `specs/client-modularize-measurements.md`

**Interfaces:**
- Produces: the baseline numbers every later phase compares against, and the answer to "is IC already effective?" (recompiled-N-files).

- [ ] **Step 1: Add build-report props**

Append to `apps/gradle.properties`:
```
# CL-MODULARIZE Phase 0 — attribute compile time + incremental-recompile size
kotlin.build.report.output=file
kotlin.build.report.file.output_dir=build/kotlin-reports
```

- [ ] **Step 2: Capture the baseline (warm daemon, N=5, discard first run)**

Run each, discard run 1, average runs 2-5. Record verbatim outputs:
```
cd apps; export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :client:compileKotlinDesktop --profile                 # main compile alone
./gradlew :client:compileTestKotlinDesktop --profile             # the ~5.1s task
# then edit ONE UI file body (add a blank line to FeedScreen.kt), re-run compileTestKotlinDesktop
./gradlew help --profile                                         # pure configuration time
./gradlew :client:desktopTest --rerun-tasks --profile            # full test-compile+run
```
Read after each: `apps/client/build/kotlin-reports/*` (look for **"recompiled N files"**) and `apps/build/reports/profile/*.html` (configuration vs task-execution vs test).

- [ ] **Step 3: Record findings in `specs/client-modularize-measurements.md`**

Write a table: task | wall (avg) | recompiled-N-files | config-time. Then a one-line verdict on **C1**: is `:client` recompiling broadly (many files → split will help) or 1–2 files (fixed-cost-bound → split marginal)? And separate main-compile (edit→render path) from test-compile (edit→test path).

- [ ] **Step 4: Commit**
```bash
git add apps/gradle.properties specs/client-modularize-measurements.md
git commit -m "CL-MODULARIZE P0: kotlin build reports + baseline measurements"
```

---

## PHASE 1 — Cheap build levers (bank regardless of the split)

### Task 1.1: Enable build-cache + parallel; re-measure

**Files:** Modify `apps/gradle.properties`, `specs/client-modularize-measurements.md`

- [ ] **Step 1: Add the safe levers**
```
org.gradle.caching=true
org.gradle.parallel=true
```
- [ ] **Step 2: Verify the build still passes clean**
Run: `cd apps && ./gradlew :client:desktopTest` → BUILD SUCCESSFUL.
- [ ] **Step 3: Re-measure** the inner loop (same method as 0.1 Step 2) + a **worktree-cold** proxy: `./gradlew :client:desktopTest --offline` after `./gradlew --stop` (cold daemon) — record the build-cache benefit on a cold start. Append to the measurements doc.
- [ ] **Step 4: Commit**
```bash
git add apps/gradle.properties specs/client-modularize-measurements.md
git commit -m "CL-MODULARIZE P1a: enable build-cache + parallel; re-measure"
```

### Task 1.2: Spike configuration-cache (may need compat fixes)

**Files:** Modify `apps/gradle.properties` (+ possibly build scripts if a task is CC-incompatible), `specs/client-modularize-measurements.md`

- [ ] **Step 1: Try turning it on**
Set `org.gradle.configuration-cache=true`. Run: `cd apps && ./gradlew :client:desktopTest`.
- [ ] **Step 2: Branch on the result**
  - **If BUILD SUCCESSFUL + "Configuration cache entry stored":** run twice more; confirm "Reusing configuration cache" and record the config-time delta. Keep it on.
  - **If it fails** (SQLDelight / Compose-resources / AGP 9.2.1 task not CC-compatible — the `android.*` experimental flags in gradle.properties hint at AGP-9 quirks): capture the exact incompatible-task error. Attempt the standard fix (avoid `Project` at execution time / `providers.*`). If not cleanly fixable in ≤ the task's budget, **revert to `false`**, and record the blocker + the offending task in the measurements doc as a filed follow-up. Do NOT force it.
- [ ] **Step 3: Re-measure** the inner loop; append.
- [ ] **Step 4: Commit**
```bash
git add apps/gradle.properties specs/client-modularize-measurements.md
git commit -m "CL-MODULARIZE P1b: configuration-cache spike (on, or blocker recorded)"
```

---

## ⛔ GATE (operator) — after Phase 1

**The implementer STOPS here and reports the measured numbers to the controller/operator.** Compute the **projected** Phase-2 win: (Phase-1 test-compile time) − (estimated `:ui`-only test-compile). Per the design's operator gate:
- If the projected split win is **marginal** (< ~1s, or Phase 1 already captured most of it): **prompt the operator to discuss** before Phase 2. Do not proceed.
- If **worthwhile**: author the **Proposed ADR** (module architecture) → operator Accepts → proceed to Phase 2.

Phase 2 tasks below execute only after this gate clears.

---

## PHASE 2 — Extract `:ui` (gated)

> Phase-2 verification is refactor-shaped: the "tests" are **(a)** the existing suite staying green, **(b)** all targets compiling, **(c)** grep invariants (zero Compose on `:client`). No behavior changes, so no new behavioral tests — the guarantee is "same tests, redistributed, still green."

### Task 2.1: Scaffold the empty `:ui` KMP module + wire the build

**Files:**
- Create: `apps/ui/build.gradle.kts`, `apps/ui/src/commonMain/kotlin/.gitkeep`
- Modify: `apps/settings.gradle.kts` (add `include(":ui")`)

**Interfaces:**
- Produces: an empty `:ui` module that `api`-depends on `:client` and compiles all targets — the container for Task 2.2's move.

- [ ] **Step 1: Add `include(":ui")` to `apps/settings.gradle.kts`.**
- [ ] **Step 2: Write `apps/ui/build.gradle.kts`** — mirror `:client`'s plugins (`kotlin("multiplatform")`, `plugin.serialization` if needed, `plugin.compose`, `org.jetbrains.compose`, `com.android.library`), `jvmToolchain(17)`, `androidTarget()`, `jvm("desktop")`, `listOf(iosArm64(), iosSimulatorArm64()).forEach { it.binaries.framework { baseName = "client"; isStatic = true } }`. commonMain deps: `api(project(":client"))` + all Compose/coil/redux-kotlin-compose/ui-backhandler deps (per design §6b). androidMain: CameraX + mlkit + activity-compose + lifecycle-runtime-compose (`implementation`). desktopMain: `compose.desktop.currentOs`. desktopTest: `compose.uiTest` + `kotlin("test")`. `android { namespace="com.sloopworks.dayfold.client.ui"; compileSdk=37; defaultConfig{minSdk=33}; compileOptions{17/17} }`. Add the `compose.resources { }` block (packageOfResClass = `com.sloopworks.dayfold.client.generated`). `compose.desktop { application { mainClass = "com.sloopworks.dayfold.client.MainKt" } }`.
- [ ] **Step 3: Verify the empty module compiles all targets**
```
cd apps && ./gradlew :ui:compileKotlinDesktop :ui:compileDebugKotlinAndroid :ui:compileKotlinIosSimulatorArm64
```
Expected: BUILD SUCCESSFUL (empty commonMain).
- [ ] **Step 4: Commit**
```bash
git add apps/settings.gradle.kts apps/ui/
git commit -m "CL-MODULARIZE P2.1: scaffold empty :ui KMP module (api-depends :client)"
```

### Task 2.2: Move the 40 Compose files + actuals + resources + entries; strip Compose from `:client`

**Files:** `git mv` the move-set from `apps/client/src/**` → `apps/ui/src/**` (same package paths); Modify `apps/client/build.gradle.kts` (remove Compose deps + `compose.resources` block + `compose.desktop`), `apps/ui/build.gradle.kts` (already has them).

**Interfaces:**
- Produces: `:client` Compose-free + `:ui` holding all UI; the iOS framework now emitted by `:ui`.

- [ ] **Step 1: Generate the move list mechanically and `git mv` (preserve history), keeping package dirs**

commonMain (40): `cd apps/client/src/commonMain/kotlin && grep -rlE "androidx\.compose|org\.jetbrains\.compose|coil3|@Composable" . ` → for each, `git mv apps/client/src/commonMain/kotlin/<p> apps/ui/src/commonMain/kotlin/<p>`. Then move platform source sets for the seams + entries: `QrScanner.{android,desktop,ios}.kt`, `cards/PlatformActions.{android,desktop,ios}.kt`, `ui/loading/ReduceMotion.{android,desktop,ios}.kt`, desktop `Main.kt`, iosMain `MainViewController.kt`. Move `apps/client/src/commonMain/composeResources/` → `apps/ui/src/commonMain/composeResources/`.

- [ ] **Step 2: Strip Compose from `apps/client/build.gradle.kts`** — remove `compose.*` deps, `compose.components.resources`, `materialIconsExtended`, `ui-backhandler`, coil, `redux-kotlin-compose`, the `compose.resources { }` block, the `compose.desktop { }` block, and the compose plugins (`plugin.compose`, `org.jetbrains.compose`). Remove `binaries.framework { }` from `:client` (keep the two iOS targets). Remove the moved androidMain deps (CameraX/mlkit/activity-compose/lifecycle).

- [ ] **Step 3: Verify `:client` is Compose-free and compiles**
```
cd apps && ./gradlew :client:compileKotlinDesktop :client:compileDebugKotlinAndroid :client:compileKotlinIosSimulatorArm64
grep -rE "androidx\.compose|org\.jetbrains\.compose|@Composable|coil3" client/src && echo "LEFTOVER COMPOSE" || echo "client is Compose-free ✓"
```
Expected: BUILD SUCCESSFUL + "Compose-free". If `Reducer.kt`'s `compose(...)` fails to resolve, qualify the import to the redux-core symbol (not `redux-kotlin-compose`).

- [ ] **Step 4: iOS de-risk — link the `:ui` framework and confirm the entry is exported**
```
cd apps && ./gradlew :ui:linkDebugFrameworkIosSimulatorArm64
grep -q MainViewController client/build/bin/*/debugFramework/../.. 2>/dev/null; \
  find . -path "*client.framework/Headers/client.h" -exec grep -l MainViewController {} \;
```
Expected: link SUCCEEDS; `MainViewController` present in the generated `client.h`. (This is the highest-risk check — do it here, not at the end.)

- [ ] **Step 5: Verify `:ui` compiles all targets**
```
cd apps && ./gradlew :ui:compileKotlinDesktop :ui:compileDebugKotlinAndroid :ui:compileKotlinIosSimulatorArm64
```

- [ ] **Step 6: Commit**
```bash
git add -A
git commit -m "CL-MODULARIZE P2.2: move 40 Compose files + actuals + resources + entries to :ui; strip Compose from :client"
```

### Task 2.3: Wire the apps to `:ui`; smoke all app targets

**Files:** Modify `apps/androidApp/build.gradle.kts` (dep `:client` → `:ui`).

- [ ] **Step 1:** In `apps/androidApp/build.gradle.kts`, change `implementation(project(":client"))` → `implementation(project(":ui"))` (debugdrawer deps unchanged).
- [ ] **Step 2: Build the Android app + desktop entry**
```
cd apps && ./gradlew :androidApp:assembleDebug :ui:compileKotlinDesktop
```
Expected: BUILD SUCCESSFUL. (Desktop `run`/`Main.kt` now lives in `:ui`.)
- [ ] **Step 3: Commit**
```bash
git add apps/androidApp/build.gradle.kts
git commit -m "CL-MODULARIZE P2.3: androidApp depends on :ui"
```

### Task 2.4: Split the test source sets

**Files:** `git mv` render/UI tests `apps/client/src/desktopTest/**` → `apps/ui/src/desktopTest/**`; logic tests (+ `DetailMetaTest`, `TypedCardLogicTest`) STAY in `:client`.

- [ ] **Step 1: Move the render/integration tests to `:ui`** — `*SnapshotTest` (Feed/Hub/Auth/Enrichment/LoadingKit/Timeline/Checklist/Notif), `ScanScreensUiTest`, `AuthFlowUiTest`, `FeedAppHostTest`, `PlatformActionsTest`, `CardRenderTest`, `PlatformUriHandlerTest`. Rule: a test moves iff it imports `androidx.compose.ui.test`/`runComposeUiTest`/renders a composable. Verify with: `grep -lE "runComposeUiTest|compose.ui.test" apps/client/src/desktopTest -r`.
- [ ] **Step 2: Confirm the split is clean**
```
cd apps
./gradlew :client:desktopTest    # logic tests only — green, count = (old total − moved)
./gradlew :ui:desktopTest        # render tests — green, count = moved
```
Expected: both BUILD SUCCESSFUL; **sum of the two test counts == the pre-split total** (record both).
- [ ] **Step 3: Commit**
```bash
git add -A
git commit -m "CL-MODULARIZE P2.4: split tests — render→:ui, logic(+cards logic)→:client"
```

### Task 2.5: Measure the split; verify DoD; update docs

**Files:** Modify `specs/client-modularize-measurements.md`, `processes/agent-dev-loop.md`, `backlog/next.md`, `CHANGELOG.md`; the Accepted ADR (from the gate).

- [ ] **Step 1: Measure post-split** — edit one `:ui` composable body; run `./gradlew :ui:compileTestKotlinDesktop`; confirm `:client` is NOT recompiled (kotlin-report shows `:client` up-to-date) and record the `:ui`-edit recompile time vs the Phase-0/1 baselines. Confirm the logic tests are skipped on a UI edit.
- [ ] **Step 2: DoD verification** — full suite green across `:client` + `:ui` (counts preserved); all targets build (`./gradlew :androidApp:assembleDebug :ui:linkDebugFrameworkIosSimulatorArm64 :ui:compileKotlinDesktop`); `:client` grep Compose-free.
- [ ] **Step 3: Update docs** — record the measured before/after in the measurements doc; update `agent-dev-loop.md` (the `:client`/`:ui` module map + which module a UI edit recompiles); mark `TASK-CLIENT-MODULARIZE` done in `backlog/next.md`; CHANGELOG entry (internal tooling — optional per repo rules); ensure the Accepted ADR reflects the final shape.
- [ ] **Step 4: Commit**
```bash
git add -A
git commit -m "CL-MODULARIZE P2.5: measure split, verify DoD, update docs + ADR"
```

---

## Self-Review

**Spec coverage** (design §→task): Phase 0 measure §4 → T0.1 ✅; Phase 1 §5 → T1.1/T1.2 ✅; operator gate §2 → the ⛔ GATE ✅; move-set/mechanical rule §3/§6a → T2.2 Step 1 (grep-derived, not hand-listed) ✅; deps incl redux-kotlin-compose §6b → T2.1/T2.2 ✅; compose.resources full removal §6c → T2.2 Step 2 ✅; android SDK values §6d → T2.1 Step 2 (Global Constraints) ✅; iOS framework relocation + link de-risk §6e → T2.2 Step 4 ✅; public-glue contract → Global Constraints ✅; test split incl cards-logic-stays §6f → T2.4 ✅; CL-SNAP coordination §7 → assumed #277-first (noted; if not, T2.2/T2.4 also relocate CL-SNAP snapshot files) ✅; DoD §9 → T2.5 ✅; ADR gate → the ⛔ GATE ✅.

**Placeholder scan:** the 40-file move is defined by an exact grep (DRY, robust) rather than a hand-enumerated list — intentional, not a placeholder. No "TBD"/"handle edge cases". Refactor tasks verify via compile + existing-suite-green + grep invariants (correct for a no-behavior-change extraction).

**Type/naming consistency:** module names `:client`/`:ui`, namespace `…client.ui`, framework baseName `client`, package `com.sloopworks.dayfold.client(.generated)`, SDK 37/33/17, iosArm64+iosSimulatorArm64 — used identically across Global Constraints + all tasks. `MainKt` mainClass matches the desktop entry moved in T2.2.

**Known contingencies flagged inline:** CC compat (T1.2 branch), `Reducer.kt` `compose()` resolution (T2.2 Step 3), CL-SNAP merge order (§7), and the operator/ADR gate — each with an explicit branch, not a silent assumption.
