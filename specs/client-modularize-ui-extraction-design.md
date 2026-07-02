# TASK-CLIENT-MODULARIZE — Slice 1: extract `:ui` from `:client`

**Status:** Proposed (design) · 2026-07-02 · **ADR-class** (module architecture →
Proposed ADR + operator acceptance before implementation)
**Scope decision (operator):** incremental — extract `:ui` first; engines/data/
store stay in `:client`. **No dependency-inversion refactor** this slice.
**Composes with:** `TASK-KMP` (done — `:client` is a true KMP module),
`backlog/next.md` TASK-CLIENT-MODULARIZE, CL-SNAP (PR #277 — coordination note §8).

---

## 1. Goal

Shrink the compilation unit a UI edit touches. Measured (CL-SNAP session): the
inner loop is **~5s recompile + ~2s fork/render**, and the recompile is the
**same ~5s whether editing a UI file or an unrelated data file** — `:client` is
one monolithic KMP compilation unit (79 commonMain files + 24 generated SQLDelight
+ sqldelight/ktor/coil). Extracting the 25 Compose files into a `:ui` module that
**depends on `:client`** means a UI edit recompiles `:ui` only — the 54 core files
+ generated SQLDelight + ktor leave the UI compile unit.

**Target payoff:** UI-edit recompile ~5s → **~2–3s** (to be verified — the Compose
compiler plugin floor remains; the split removes cross-concern recompiles + shrinks
the analyzed set). Secondary: independent `:ui`/`:client` build-cache + parallelism;
`:client` (core) no longer pulls Compose.

**Non-goals (this slice):** splitting `:client` into `:model`/`:data`; dependency
inversion of the engine↔client coupling; the web (wasmJs)/async-DB migration; any
behavior change. Pure structural move — **all tests stay green, no UX delta.**

## 2. Why this is safe to do now (verified)

- **No Compose in the 54 core files** (grep: zero `androidx.compose`/`@Composable`).
- **Acyclic:** 0 reverse-dependencies — no core file references any symbol defined
  in the 25 UI files. So `:ui → :client` is a clean one-way edge.
- **The engine↔client coupling stays intact** inside `:client` (SyncEngine/Auth
  Engine/HubEngine reference SyncClient/AuthClient/HubClient/ContentStore; Reducer
  references SyncClient). Because we are NOT splitting core, no interface extraction
  is needed — `:ui` simply depends on the whole of `:client`.

## 3. Target module graph

```
:client   (KMP: android/desktop/ios + desktopTest)   — "core", NO Compose
   ▲ api
:ui       (KMP: android/desktop/ios + desktopTest)   — Compose only
   ▲
:androidApp   /   desktop entry   /   iOS framework consumer
```

`:ui` `api(project(":client"))` so app modules that depend on `:ui` transitively
see core types. `:androidApp` switches its `project(":client")` dep → `:ui`.

## 4. What moves into `:ui`

**The 25 composable files** (commonMain):
AccountScreen, AuthScreens, CardRender, DayfoldIcons, DeviceApprovalScreens,
DevicesScreen, FeedScreen, HubScreens, JoinInviteScreen, MembersScreen, NotifStates,
NowFeedScreen, OfflineBanner, PermissionLadder, PlacesList, PredictiveBackMotion,
PrivacyAffordance, ProximitySettings, ProximitySettingsHost, ScanScreens,
TimelineCard, TimelineDetail, FeedApp, MediaEnrichment, QrScanner (expect) — plus
the `theme/` package (4 files) and the `cards/` UI (TypedCards/DetailScreen/shared
chrome + `PlatformActions` expect).

**Platform actuals** (move with their expect):
- `QrScanner.{android,desktop,ios}.kt` (androidMain uses CameraX + ML Kit).
- `cards/PlatformActions.{android,desktop,ios}.kt` + `PlatformActionsTest` (desktopTest).

**iOS framework entry:** `MainViewController.kt` (`ComposeUIViewController {
FeedApp(store) }`) → `:ui` iosMain. **`:ui` produces the iOS framework** (baseName
`client` preserved for the Swift side); `:client` stops emitting a framework.

**Desktop entry:** `Main.kt` (references `FeedApp`) → `:ui` desktopMain; the
`compose.desktop { application { mainClass } }` block moves to `:ui`.

**Image loading:** `CoilSetup.kt` + `MediaEnrichment.kt` → `:ui` (image loading is a
UI cross-cut; keeps coil out of `:client`). *Open for panel: CoilSetup imports ktor
for the network fetcher — confirm all coil (`coil-compose`, `coil-network-ktor3`)
lands in `:ui` and `:client` needs none.*

**composeResources (bundled fonts):** the `composeResources/font/` (Outfit/Figtree)
+ the `compose.resources { }` block + generated `Res` accessor package
(`com.sloopworks.dayfold.client.generated`) → `:ui`.

## 5. Dependencies to relocate (`:client` build.gradle.kts → `:ui`)

Move to `:ui` commonMain: `compose.runtime/foundation/material3/ui`,
`compose.components.resources`, `compose.materialIconsExtended`,
`org.jetbrains.compose.ui:ui-backhandler`, `io.coil-kt.coil3:coil-compose` +
`coil-network-ktor3`. `:ui` androidMain: CameraX (core/camera2/lifecycle/view) +
`mlkit:barcode-scanning` + `activity-compose` + `lifecycle-runtime-compose`.
`:client` **keeps** redux-kotlin, serialization, coroutines, sqldelight, datetime,
ktor-client-* (the data/engine layer). *Panel: audit that `:client` still compiles
with Compose fully removed — any stray Compose usage in a "core" file is a blocker.*

## 6. Tests split

- **Stay in `:client` desktopTest:** Reducer/Selector/Engine/pure-logic tests
  (ReducerTest, {Sync,Auth,Hub,Now}EngineTest, OutboxEgressTest, ContentStoreTest,
  ChecklistMerge, NowDerive/Rank, DeriveTimeline, etc.).
- **Move to `:ui` desktopTest:** the render/UI tests — `*SnapshotTest`
  (Feed/Hub/Auth/Enrichment/LoadingKit/Timeline/Checklist/Notif), `ScanScreensUiTest`,
  `AuthFlowUiTest`, `FeedAppHostTest` (integration: builds a store from `:client`,
  renders `FeedApp` from `:ui`), `PlatformActionsTest`.
- `:ui` desktopTest gets `compose.uiTest` + `kotlin("test")`; `:client` keeps its
  JUnit-platform test setup for the non-UI tests.

## 7. iOS / Android / desktop wiring

- **iOS:** `:ui` declares `iosArm64()`/`iosSimulatorArm64()` with the
  `binaries.framework { baseName = "client"; isStatic = true }` (moved from
  `:client`); `MainViewController` in `:ui` iosMain. `:client` keeps its iOS targets
  (for the data/engine code the framework transitively needs) but **no longer emits
  a framework**. *Panel (iOS): confirm a static framework from `:ui` correctly
  includes the transitive `:client` iOS klibs; watch `isStatic` + export.*
- **Android:** `:androidApp` deps `project(":client")` → `project(":ui")`;
  debugdrawer debug/release deps unchanged (they live at the app level). `:ui` is a
  `com.android.library` (namespace `…client.ui`), `:client` keeps its library
  namespace. *Panel (Android): variant/namespace collisions, manifest merging for
  the CameraX/permissions that move to `:ui`.*
- **Desktop:** app entry + `compose.desktop.application` move to `:ui`.

## 8. Coordination with CL-SNAP (PR #277)

This branch is off latest `main` (`b3b0dee`), which does **not** include CL-SNAP.
CL-SNAP adds a snapshot registry (`SnapshotScenes`) + tests that render composables
→ those belong in `:ui` after the split. **Ordering:** land after #277 merges (then
this slice also relocates the CL-SNAP snapshot files to `:ui`), or rebase #277 onto
this. Operator decides merge order; the plan will assume #277 merges first and
include the CL-SNAP snapshot-file relocation, or note the rebase.

## 9. Risks

- **iOS framework relocation** (`:client`→`:ui`) is the highest-uncertainty piece
  (static-framework transitive klib inclusion; Swift host consumes `client.framework`
  — no Xcode project exists yet, so low blast radius today).
- **Compose-plugin floor:** the split shrinks the analyzed set + kills cross-concern
  recompiles; it does not remove Compose's per-file cost. The ~2–3s target is an
  estimate — **measure before/after** (DoD).
- **composeResources migration** (font generation moving modules) can be fiddly.
- **Two KMP modules** now configure android/desktop/ios source sets — more Gradle
  config; mitigated because `:client` sheds Compose.
- **Large mechanical test/file move** — risk is a missed reference or a test landing
  in the wrong module; caught by "all targets compile + full suite green".

## 10. Success criteria (DoD)

- Editing a `:ui` composable recompiles `:ui` (+ its tests) only — **not** `:client`
  or the generated SQLDelight; the **measured** UI-edit recompile improvement vs the
  ~5s baseline is recorded.
- `:client` compiles with **zero Compose** on its classpath.
- All targets build (android/desktop/iosArm64/iosSimulatorArm64); the **full test
  suite stays green** (counts unchanged, just redistributed across `:client`/`:ui`).
- No behavior/UX change.

## 11. Alternatives considered

- **Full `:model`/`:data`/`:ui` split** (needs dependency inversion of engine↔client)
  — higher payoff (independent model/data testability, web-target prep) but higher
  risk; deferred (operator chose incremental).
- **Model-first** (extract pure-leaf `:model`) — less UI-loop benefit (UI stays
  coupled to engines/data). Rejected for this slice's goal.
- **Config-cache only** (no split) — cheap, attacks the ~2s fork not the ~5s
  recompile. Complementary, not a substitute; bank it alongside.
