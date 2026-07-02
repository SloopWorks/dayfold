# ADR 0019: Client Observability & Tooling (redux-kotlin devtools, snapshots, debug, CLI)

## Status

**Accepted** 2026-06-19 (operator-directed; operator maintains reduxkotlin).
Extends ADR 0013; composes with `research/reduxkotlin-1.0-feedback.md`.
**DevTools is now LIVE** — the modules publish at `1.0.0-alpha01` on Maven
Central (also `1.0.0-SNAPSHOT`). Verified on-device: in-app drawer recording
real actions (`specs/prototype/devtools-{bubble,drawer}.png`).

## Context

ADR 0013 chose redux-kotlin partly *because* redux affords devtools,
time-travel, and a debuggable `f(store.state) → UI` model — but nothing codified
**how/when** we adopt that tooling, and there were no tests or decisions for it.
The client is now on redux-kotlin `1.0.0-alpha01` with `store.selectorState{}`.
Some tooling is adoptable today; some depends on reduxkotlin modules not yet on
Maven Central.

## Decision

Adopt client observability in two tiers — **now** (what's published) and
**when-published** (gated on reduxkotlin releases) — and back each with tests.

**Now (implemented):**
1. **DevTools enhancer** — `createAppStore(debug = true)` attaches
   `devTools(DevToolsConfig(instanceId="family-ai", name="Family AI"))` from
   `redux-kotlin-devtools-core`, recording actions + state diffs to the global
   `DevToolsHub`. Release passes `debug = false` (no recording).
2. **In-app debug drawer (Android)** — `ReduxDevToolsHost(InAppConfig(triggers =
   {BUBBLE}))` wraps the UI: a floating bubble (with action count) opens the
   Redux DevTools drawer (ACTIONS / STATE / DIFF / PIPELINE / OUTPUTS — i.e.
   the action log + time-travel + state inspection). `debugImplementation`
   `redux-kotlin-devtools-inapp`; `releaseImplementation` `-inapp-noop` (the
   no-op facade strips the UI from release).
3. **Compose UI snapshot tests** — `runComposeUiTest { … captureToImage() }`
   renders `FeedScreen(state)` off-screen (headless) → pixels (`FeedSnapshotTest`).

**Remaining (lower priority):**
4. **Golden-image snapshot diffing** — add Roborazzi / a desktop golden harness
   so snapshots *assert against goldens* in CI (today's test captures only).
   Pure Compose infra — no reduxkotlin dependency.
5. **Remote DevTools** — optionally add `redux-kotlin-devtools-remote` /
   `-bridge` to stream to the standalone monitor / browser extension.
6. **redux-kotlin CLI** — adopt for scaffolding / driving devtools if it fits
   the agent-build flow (ADR 0012) once a CLI artifact is available.

**Test policy:** every store-driven surface gets (a) reducer unit tests, (b) a
Compose snapshot, and — once #4 lands — a golden assertion in CI.

## Rationale

The debug middleware + snapshot capture are cheap, real today, and give
immediate debuggability + regression coverage of the `f(store.state)→UI` path.
Deferring devtools/golden/CLI to when-published avoids depending on
unpublished artifacts while keeping a clear adoption path — and the integration
gaps feed back into reduxkotlin 1.0.0 (INB-15).

## Consequences

Positive: actions/state are observable in dev; UI snapshots guard rendering; a
documented path to full devtools/time-travel. Negative: the debug middleware is
a temporary stand-in; golden diffing + devtools are pending external releases
(can't be CI-enforced until then); snapshot golden files add repo weight when #4
lands.

## Update 2026-07-02 — CL-SNAP delivered Remaining #4 and #6

**"Remaining" item #4 (golden-image diffing)** is now delivered via
`org.reduxkotlin:redux-kotlin-snapshot:1.0.0-alpha04` (Maven Central), **not**
the Roborazzi DIY path noted at acceptance. Implementation (epic CL-SNAP):
- `desktopTest` dep in `apps/client/build.gradle.kts`.
- Scene registry: `SnapshotScenes.kt` (scenes `feed`, `hub-detail`, `detail`;
  state fixtures from `SnapshotStates.kt` — hand-built `AppState` literals).
- 12 committed goldens in `apps/client/src/desktopTest/resources/snapshots/`.
- `GoldenSnapshotTest` verifies goldens at `maxDiffPercent = 2.0` in
  `:client:desktopTest` (CI = ubuntu-latest). Re-record: `-Dsnapshot.record=true`.
- `--semantics` flag confirmed working in alpha04 (Tier-0 text smoke, zero
  vision tokens).

**"Remaining" item #6 (redux-kotlin CLI)** is also delivered: the
`:client:snapshotUi` Gradle task (`-PsnapshotArgs="…"`) is the agent-loop
entry for headless render + semantic inspection. See `processes/agent-dev-loop.md`
`⭐ rk snapshot` section for the full workflow.

**"Remaining" item #5 (remote devtools)** is still pending.

## Revisit Trigger

`redux-kotlin-devtools` remote/bridge module publishes to Maven Central (adopt
#5); or time-travel becomes load-bearing for the deep-link surface.
