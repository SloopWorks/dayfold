# TASK-CLIENT-MODULARIZE — measure → cheap levers → extract `:ui`

**Status:** Proposed (design, rev 2 post expert-panel) · 2026-07-02 · **ADR-class**
**Scope decision (operator):** incremental — measure first, bank the cheap build
levers, then extract `:ui` (no dependency inversion; engines/data/store stay in
`:client`).
**Reviewed by:** 4-agent expert panel (KMP-arch, iOS/Native, Android, Gradle
build-speed) — all findings folded in below.
**Composes with:** `TASK-KMP` (done), CL-SNAP (PR #277 — §Coordination).

---

## 1. Goal & the honest value proposition (revised after panel)

The trigger was the measured inner loop: **~5s recompile + ~2s fork/render**,
recompile the **same ~5s whether editing a UI or a data file**. The naive read
("monolith → split → 2–3s") **did not survive review.** The Gradle expert's verdict:
- The ~5s could be **fixed per-task overhead** (daemon, classpath fingerprint,
  Compose plugin init, IC bookkeeping, test-compile), not module size — in which
  case the split lands **~4–4.5s, not 2–3s**. This is **unproven** and must be
  measured before the split is justified on speed.
- **`configuration-cache` and `build-cache` are OFF.** Enabling them is ~2 lines,
  zero architectural risk, and likely a **bigger** inner-loop win than the split —
  and a large win on this **worktree-heavy** repo (every worktree switch is cold).

**So the split's durable value is architectural, not the headline speed number:**
test isolation (a UI edit stops recompiling the logic tests), `:client` sheds
Compose from its classpath, independent build-cache/parallelism, and the structural
setup for the eventual `:model`/`:data` split. Any compile-time win is a measured
bonus, not the premise.

**Non-goals:** `:model`/`:data` split; dependency inversion; web/async-DB
migration; any behavior/UX change.

## 2. Phased approach

- **Phase 0 — Measure** (gates everything). Turn on Kotlin build reports; attribute
  the 5s to compile-vs-config-vs-fork and main-vs-test; get "recompiled N files"
  per edit. Resolves whether IC is already effective (→ split marginal) or `:client`
  recompiles broadly (→ split delivers).
- **Phase 1 — Cheap levers.** Enable `build-cache` (safe) + `configuration-cache`
  (spike compat with SQLDelight/Compose/AGP 9.2.1). Re-measure. Bank whatever they
  give regardless of the split.
- **Phase 2 — Extract `:ui`.** The structural move (below), justified by the
  architectural benefits + the Phase-0/1 measured numbers.

Each phase is independently valuable and independently shippable.

**Operator gate between Phase 1 and Phase 2 (operator-directed 2026-07-02):** after
Phase 0/1 measurement, if the `:ui` split's projected compile win is **marginal**
(e.g. Phase 1 already captured most of it, or IC is already tight so the split
saves < ~1s), **STOP and prompt the operator to discuss** before starting Phase 2
— do not auto-proceed on "architectural value" alone. If the numbers show a
worthwhile win, continue into Phase 2.

## 3. Verified feasibility (corrected by panel)

- **Acyclic `:ui → :client` — at the Compose boundary, per file.** Corrected counts:
  `commonMain` ≈ **40 Compose/coil files → `:ui`, ~37 Compose-free → `:client`**
  (my first scan under-counted at 25/54; it missed `ui/loading/`, `cards/` Compose
  files, and `org.jetbrains.compose`/`coil3` imports).
- **The move boundary is MECHANICAL:** a commonMain file moves to `:ui` iff it
  imports `androidx.compose` / `org.jetbrains.compose` / `@Composable` / `coil3`.
  Everything else stays. This is the definition — not a hand-list.
- **Explicit stay-in-`:client` exceptions** (Compose-free, and core depends on them
  → moving would create a cycle): `cards/CardAction.kt` (used by `NowNotify`,
  `TimelineActions`), `cards/DetailMeta.kt`, `cards/TypedCardLogic.kt`. Their tests
  (`DetailMetaTest`, `TypedCardLogicTest`) stay too.
- **Three expect/actual seams move to `:ui`** (not two): `QrScanner`,
  `cards/PlatformActions`, and **`rememberReduceMotion`** (`ui/loading/Shimmer.kt`
  + `ReduceMotion.{android,desktop,ios}.kt`). Each is self-contained in the moved
  set; `:ui` declares android+desktop+ios source sets. `DriverFactory` (SQLDelight)
  stays in `:client`.
- **`linkrules` srcDir stays single-homed in `:client`** (`kotlin.srcDir(
  "../../packages/linkrules")`) — consumed by both core (`Model`/`MediaValidation`)
  and UI (`MediaEnrichment`/`CoilSetup` via `linkify`); `:ui` gets it through the
  `api` edge. **Do NOT duplicate the srcDir into `:ui`** (FQN redeclaration clash).

## 4. Phase 0 — Measurement (do first; it gates the split's speed claim)

Add to `apps/gradle.properties` (one-time):
```
kotlin.build.report.output=file
kotlin.build.report.file.output_dir=build/kotlin-reports
```
Method (warm daemon, N=5, discard first), captured **before** any change:
```
./gradlew :client:compileKotlinDesktop --profile          # main compile alone
./gradlew :client:compileTestKotlinDesktop --profile      # the 5.1s task
# edit one UI file body (non-ABI), re-run; read:
#   build/kotlin-reports/*  → "recompiled N files"  (1–2 = fixed-cost-bound → split marginal;
#                                                     ~all = broad recompile → split delivers)
#   build/reports/profile/*.html → configuration vs task-execution vs fork split
./gradlew help --profile                                  # pure configuration cost
```
**Record the numbers in the plan's Phase-0 task.** They set the honest target for
Phase 2 and separate the human's edit→render path (main compile) from the
edit→test path (test compile) — the split's biggest real win is **test isolation**,
so measure them separately.

## 5. Phase 1 — Cheap build levers (bank regardless of the split)

`apps/gradle.properties`:
```
org.gradle.caching=true                 # build-cache — safe; big on worktree/branch switch + CI
org.gradle.configuration-cache=true     # kills re-configuration every build; grows with modules
org.gradle.parallel=true                # minor inner loop; helps full/CI
# optional: kotlin.daemon.jvmargs=-Xmx2g   # less daemon GC thrash under Compose
```
`configuration-cache` may need a compatibility spike (SQLDelight / Compose / AGP
9.2.1 tasks that break CC). If it can't be turned on cleanly, keep `build-cache` +
`parallel` and file the CC blocker. **Re-measure** the inner loop after this phase —
this is the number the split must then beat to justify itself on speed.

## 6. Phase 2 — Extract `:ui` (target module graph)

```
:client   (KMP: android/desktop/ios + desktopTest)   — "core", NO Compose on classpath
   ▲ api(project(":client"))
:ui       (KMP: android/desktop/ios + desktopTest)   — Compose only; emits the iOS framework
   ▲
:androidApp   /   desktop app entry (:ui)   /   iOS framework consumer (:ui)
```
`api` (not `implementation`) is **mandatory**: `androidApp/MainActivity` uses
`createAppStore`/`AuthEngine`/`SyncEngine`/`ContentStore`/`DriverFactory`/
`AndroidContentStoreHolder` — all in `:client`; `implementation` would hide them.
Zero encapsulation of core from apps is accepted this slice (that's what the
deferred `:model`/`:data` split fixes).

### 6a. What moves (by the mechanical rule) — the ~40 Compose/coil files
All commonMain composables + `theme/` (4) + `ui/loading/` (5: BusyIndicators,
Shimmer, Skeletons, StableLoading, StateViews) + the Compose `cards/` files
(TypedCards, DetailScreen, PlatformActions, PlatformUriHandler, SharedScopes) +
FeedApp + MediaEnrichment + CoilSetup. Plus platform actuals: `QrScanner.{android,
desktop,ios}`, `PlatformActions.{android,desktop,ios}`, `ReduceMotion.{android,
desktop,ios}`. Plus entry points: `Main.kt` (desktop) + `MainViewController.kt`
(iOS framework entry). **The implementation plan derives the exact list by grep,
not by copying this prose.**

### 6b. Dependencies to relocate (`:client` → `:ui`)
commonMain: `compose.runtime/foundation/material3/ui`, `compose.components.resources`,
`compose.materialIconsExtended`, `org.jetbrains.compose.ui:ui-backhandler`,
`io.coil-kt.coil3:coil-compose` + `coil-network-ktor3`, **and
`org.reduxkotlin:redux-kotlin-compose`** (FeedApp's `selectorState` — was
`implementation` on `:client`, not transitive; panel-caught). `:ui` androidMain:
CameraX (core/camera2/lifecycle/view) + `mlkit:barcode-scanning` +
`activity-compose` + `lifecycle-runtime-compose` (all `implementation` — used only
inside `QrScanner.android`). `:client` **keeps** redux-kotlin core/granular/
devtools, serialization, coroutines, sqldelight, datetime, ktor-client-*.
**Verify `:client` compiles with Compose fully gone** — incl. `Reducer.kt`'s
`import org.reduxkotlin.compose` + `compose(...)` enhancer call (redux **core**
composition, not the Jetpack binding; qualify the import if it fails to resolve
after `redux-kotlin-compose` leaves).

### 6c. Compose Resources (fonts) — Android dex-merge trap
Move the `compose.resources { }` block, `composeResources/font/` (Outfit/Figtree),
and keep the generated package `com.sloopworks.dayfold.client.generated` **to `:ui`**.
**Fully remove** the block + assets from `:client` — a partial leftover makes both
modules generate the same `Res` class → duplicate-class dex-merge failure in
release. Only `theme/Theme.kt`/`Type.kt` reference `Res` (they move with it); no
core file does.

### 6d. Android module config (must mirror `:client` exactly)
`:ui` = `com.android.library`, `namespace = "com.sloopworks.dayfold.client.ui"`
(distinct → no R/BuildConfig clash), `compileSdk = 37`, `minSdk = 33`,
`sourceCompatibility/targetCompatibility = 17`, `jvmToolchain(17)`. Failure modes:
compileSdk < 37 → immediate Compose build fail; minSdk drift → runtime crash on
API-33 symbols; JVM mismatch → Kotlin metadata errors. **CAMERA / uses-feature
stay in `:androidApp`'s manifest** (already there; `:client` has no manifest) —
nothing to move. debug/release variant matching is already proven by `:client`;
debugdrawer deps stay at `:androidApp`. R8 is off today; when TASK-mobile-r8 runs,
Compose/CameraX/ML-Kit/redux keep-rules belong in `:ui`'s **`consumerProguardFiles`**
(file that note in the R8 ticket now).

### 6e. iOS framework relocation (highest-uncertainty piece)
`:ui` declares `iosArm64()`/`iosSimulatorArm64()` (no iosX64 — granular alpha has
none; **both** modules must keep exactly this pair) with
`binaries.framework { baseName = "client"; isStatic = true }`. `:client` **keeps**
its iOS targets (its iosMain klibs are what the framework needs) but **drops
`binaries.framework`** (two frameworks named `client` = duplicate). No
`export(project(":client"))` needed — Swift calls only `MainViewController()`; the
store is built inside Kotlin. **New public contract:** `MainViewController` (in
`:ui`) consumes public `:client` iosMain glue (`IosTokenStore`,
`IosContentStoreHolder`, `IosNotifGlue`, `IosDeepLinkBus`, `reRegisterGeofences()`,
`reconcileExactSchedules()`) — these are public today and **must stay public**
(add as a constraint). Desktop `Main.kt` likewise consumes public `:client` glue
(`FileTokenStore`, `fake.fakeClientForApi`).

### 6f. Test split
- **Stay in `:client` desktopTest** (logic): Reducer/Selector/{Sync,Auth,Hub,Now}
  Engine/OutboxEgress/ContentStore/ChecklistMerge/NowDerive/NowRank/DeriveTimeline,
  **plus the Compose-free `cards/` logic tests `DetailMetaTest`, `TypedCardLogicTest`**.
- **Move to `:ui` desktopTest** (render/integration): `*SnapshotTest`
  (Feed/Hub/Auth/Enrichment/LoadingKit/Timeline/Checklist/Notif), `ScanScreensUiTest`,
  `AuthFlowUiTest`, `FeedAppHostTest` (builds a `:client` store, renders `:ui`
  FeedApp), `PlatformActionsTest`, `CardRenderTest`, `PlatformUriHandlerTest`.
- `:ui` desktopTest gets `compose.uiTest` + `kotlin("test")`; `:client` keeps its
  JUnit-platform setup. Total test count unchanged, just redistributed.

## 7. Coordination with CL-SNAP (PR #277)

This branch is off `main` (`b3b0dee`) without CL-SNAP. CL-SNAP adds a snapshot
registry + golden tests that render composables → they belong in `:ui`. Assume
**#277 merges first**; Phase 2 then also relocates the CL-SNAP snapshot files
(`SnapshotScenes`, `SnapshotStates`, `GoldenSnapshotTest`, the goldens, the
`snapshotUi` task) to `:ui`. If #277 lands after, rebase. Operator sets merge order.

## 8. Risks

- **iOS framework relocation** — highest uncertainty (static-framework transitive
  klib inclusion). De-risk FIRST, before the bulk move:
  `./gradlew :ui:linkDebugFrameworkIosSimulatorArm64`, then grep the generated
  `client.framework/Headers/client.h` for `MainViewController`.
- **Speed under-delivery** — the split may yield only ~0.5–1.5s; Phase 0/1 make
  this honest, not a surprise.
- **Full/CI build ~5–15% slower** (2 compile tasks + 2× config + extra klib link);
  build-cache mitigates on reruns. Quantify in Phase 0.
- **compose.resources dex-merge trap** (§6c) and **`Res` package** migration.
- **Large mechanical move** — a missed Compose file leaves Compose on `:client`
  (DoD catches it); a misplaced test lands in the wrong module (compile catches it).

## 9. Success criteria (DoD)

- **Measured** before/after inner-loop numbers recorded (Phase 0 baseline, Phase 1
  post-cheap-levers, Phase 2 post-split), separating main vs test compile.
- Editing a `:ui` composable recompiles `:ui` (+ its tests) only — not `:client`
  or generated SQLDelight, and not the `:client` logic tests.
- `:client` compiles with **zero Compose** on its classpath (grep-verified).
- All targets build (android/desktop/iosArm64/iosSimulatorArm64); **full test suite
  green** (counts unchanged, redistributed). `:ui:linkDebugFrameworkIosSimulatorArm64`
  succeeds with `MainViewController` exported.
- No behavior/UX change.

## 10. Alternatives considered

- **Full `:model`/`:data`/`:ui`** (dependency inversion) — deferred (operator chose
  incremental).
- **Config-cache/build-cache only, no split** — now Phase 1; if measurement shows
  the split adds little compile win, the split still proceeds for architectural
  value, but the operator may stop after Phase 1 (the phasing makes that a clean
  exit).
- **Rename `:client`→`:core`** — deferred as tech-debt to the `:model`/`:data`
  slice (settings/dep churn now for no functional gain; package + namespaces stay).
