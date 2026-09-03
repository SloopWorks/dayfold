Complete Dayfold's instrumentation integration with SWIP (SloopWorks Instrumentation Platform). Work in the **Dayfold repo**; the SWIP SDK is already built and published. This session finishes the *analytics + lifecycle + redux* wiring. The bug reporter is ALREADY integrated (ADR 0054, PRs #320/#321/#323 merged — shake→report live in debug builds); do NOT touch it.

## STEP 0 — Read first, confirm the gate before writing code

1. Your Dayfold-repo memory / project state, and the SWIP repo memory if accessible (`swip-project-state.md`). The SWIP repo lives at `~/workspace/sloopworksinstrumentationplatform` (GitHub `SloopWorks/swip`, private).
2. In the SWIP repo: `sdk-kmp/swip-rk/README.md` (the canonical redux wiring guide — follow it), `sdk-kmp/README.md` (module index), `docs/10-redux-kotlin-integration.md` (design + as-built banner), `.claude/skills/instrument-with-swip/SKILL.md` (schema authoring), `adr/0013` (swip-rk optional module), `adr/0019` (product manifests + generated init), `adr/0015` (vendor accounts), `adr/0018` (CollectionMode/privacy profiles), `INVARIANTS.md`.
3. In Dayfold: find the existing swip seams from the bug-reporter work — a `:swip-wiring` module, a `createAppStore(extraEnhancer)` (or similarly named) store-construction seam that keeps `:client` swip-free, the GH Packages plumbing in `apps/settings.gradle.kts` (repo secret `SLOOPWORKS_PACKAGES_TOKEN`, read:packages), and `DayfoldStateSanitizer`. VERIFY these names against the actual repo — they are from memory and may differ.

**THE GATE (ADR-0015) — analytics UNBLOCKED as of 2026-07-12:** the PostHog **project** key (`POSTHOG_KEY` = `phc_*`) + host (`POSTHOG_HOST` = `https://eu.i.posthog.com`, EU) are **already in the `dayfold` Infisical project** (Infisical org is named `keepqr` interim, one project per product — do NOT put Dayfold secrets in a keepqr folder). Wire the **live `PostHogTransport(apiKey, host, http)`** (already built in swip-core; URL `$host/batch/`), reading the two values via Infisical → BuildConfig. **Crash vendor is still an OPEN decision** — Sentry EU (adapter `SwipSentryAdapter` already built, ADR-0003; DSN not yet created) vs PostHog error tracking (consolidate — same EU vendor, rides the `SloopErrors` facade, no new secret, weaker native detail today) vs self-host GlitchTip (Sentry-wire-compatible, drop-in DSN). Until that's decided, wire crash via a NoOp `SloopErrors` and leave a one-line TODO; do not block analytics on it.

## Prerequisite — Dayfold has NO event schemas yet

`@sloopworks/swip-schema-dayfold` currently ships only the **manifest + generated init** (`DayfoldSwip.androidProd()`), NOT event types — no `schemas/dayfold/*.yaml` events are authored, so the mapper table has nothing to map to yet. Before the redux mappers can produce events you must:

1. Decide the **first slice of events** to track (start small — e.g. the 5–10 highest-value user actions already flowing through the Redux store; don't boil the ocean).
2. Author them as schemas in the **SWIP repo** `schemas/dayfold/<event>.v1.yaml` per the `instrument-with-swip` skill (JSON Schema 2020-12, `additionalProperties:false`, **every field has `x-swip.privacy_class`**, health-adjacent = `sensitive`, ids opaque). Run `pnpm swip schema check` + `pnpm swip schema gen`, commit generated output, PR it, and **publish a bumped `works.sloop.swip:schema-dayfold`** via the `publish-kmp` workflow (trim `modules` to just `:schema-dayfold:publishAllPublicationsToGitHubPackagesRepository`; versions are immutable so bump the version first). This gives Dayfold `@Serializable` event types (`AccountCreatedEvent`, …) to map onto.
3. This schema work can be a SWIP-repo task done first (coordinate with the user) or interleaved. The Dayfold wiring below assumes those event types exist.

## The Dayfold wiring (composition root only — `:client` stays swip-free)

Consume the published KMP artifacts (all `works.sloop.swip:*`, GH Packages, need the `read:packages` token already configured):
```kotlin
implementation("works.sloop.swip:swip-core:0.1.1")
implementation("works.sloop.swip:schema-dayfold:<bumped version with events>")
implementation("works.sloop.swip:swip-lifecycle:0.1.0")
implementation("works.sloop.swip:swip-rk:0.1.0")
```

1. **`Swip.init`** in the app composition root:
   `val swip = Swip.init(DayfoldSwip.androidProd(), DayfoldSwip.platformDeps(transport=…, storage=…, nowMs=…, monotonicNowMs=…, random=…, ioDispatcher=…), appScope)`. `DayfoldSwip.platformDeps(...)` is codegen'd (ADR-0019, PR #37) and auto-fills `criticalSchemas`/`anonymousSafeSchemas`/`pseudonymousStrip` from co-generated objects — you supply only the runtime bits + `transport`. Pass `initialMode` from Dayfold's resolved **privacy profile** (ADR-0018; opt-in regions boot `ANONYMOUS` pre-consent). `transport` = PostHog if keys exist, else NoOp/File (see the gate).
2. **Lifecycle** (`swip-lifecycle`): `SwipLifecycle.install(...)` via `ProcessLifecycleOwner` in the Android app; wire `handle.screen(route)` off the store's **route selector** (screen_view is route-driven, NOT Activity/Fragment auto — Compose single-Activity). All lifecycle events are `anonymous_safe`.
3. **swip-rk** — follow `sdk-kmp/swip-rk/README.md` exactly:
   - Build the **mapper table** in the composition root: `swipMappers { map<SomeAction> { SomeEvent(...) }; mapExposure<DecisionAction> { it.key } }` — the table IS Dayfold's tracking spec; unmapped actions emit nothing.
   - Wire via the existing `createAppStore(extraEnhancer)` seam:
     `compose(swipTimingEnhancer(swip.telemetry, mappers, monotonicNowMs, random), applyMiddleware(thunkMiddleware, swipMiddleware(analytics = swip.analytics.asSloopAnalytics(), errors, mappers, config, replayGuard = ReplayGuard.detectDevtools(isDebug = BuildConfig.DEBUG), consentGate = { swip.analytics.collectionMode() != CollectionMode.ANONYMOUS })))`.
   - **Order matters** (from swip-rk review): `thunkMiddleware` outermost, `swipTimingEnhancer` OUTSIDE `applyMiddleware`.
   - `errors`/`config`/`telemetry` are facades with no live KMP runtime yet — pass NoOp impls (dead-until-wired, same as the Sentry adapter). Only `analytics` is live.
   - **Reducers never call SWIP** — side effects live only in middleware/UI (purity/time-travel/replay). A reducer-source `works.sloop.swip` import is a defect.
4. **Consent → CollectionMode:** wire Dayfold's consent state to `swip.analytics.setCollectionMode(...)` / the `consentGate`. Nothing behavioral emits pre-consent (breadcrumb + exposure are gated; `track()` rides the pipeline consent gate).
5. **Deferred — do NOT build:** config-as-state middleware (`swipConfigMiddleware`) and `swipThunk` are intentionally deferred in swip-rk (Phase 2 / a telemetry-facade ADR). Don't reach for them.
6. **release builds = zero swip bytes** where that's Dayfold's existing pattern (mirror how the bug reporter's release variant is stripped).

## Tests (hermetic — injected clock, seeded RNG, in-memory fakes)

- **Mapper-table golden** (the workhorse): parameterize over every registered mapper → construct the action → run through `swipMiddleware` with an in-memory `SloopAnalytics` → assert the emitted event against a golden. Coverage of the table = the spec of what Dayfold tracks.
- **Purity:** dispatch a scripted action log twice → identical event sequences (minus ids/timestamps).
- **Privacy leak test:** if Dayfold has a mandatory salted-PII leak test in `:swip-wiring` (it does for the bug reporter), extend it to cover analytics event props — mapper lambdas must only project classified fields.
- On-device smoke: hermetic tests + goldens CANNOT catch host-integration bugs (lesson from the bug reporter — three bugs were found only on real device). Plan a manual device smoke: fire a mapped action, confirm the event reaches the transport (File/NoOp log in dev, or PostHog EU if keys live).

## Conventions (follow exactly — every SWIP/Dayfold PR went this way)

- **New git worktree + branch off latest `origin/main`**; `TDD always` (failing test → watch fail → implement → watch pass).
- One coherent PR per logical unit. Gates before commit: the Dayfold build's test + lint + `cd sdk-kmp`-equivalent Kotlin gates (`./gradlew <dayfold modules>:test` + `compileKotlinIosArm64 compileReleaseKotlinAndroid` if iOS/Android touched).
- Product code binds **facades only** — no PostHog/Sentry types in `:client` (the eject test / `:swip-wiring` seam enforces this).
- Commit trailer `Co-Authored-By: Claude <model> <noreply@anthropic.com>`; push; open PR; poll CI green; report; merge on the user's say-so.
- Write a Dayfold ADR (the bug reporter used ADR 0054 — pick the next number) recording the analytics integration decision.
- Update Dayfold's project memory + the SWIP `swip-project-state.md` (Dayfold-integration line) when done.

## Scope check before you start

This is large. Propose a **sequenced plan** to the user before executing: (a) author the first event-schema slice in SWIP + publish schema-dayfold; (b) Swip.init + lifecycle wiring with NoOp/File transport; (c) swip-rk mapper table + store wiring + tests; (d) PostHog transport swap once ADR-0015 keys land; (e) consent/CollectionMode wiring; (f) device smoke. Get the user to confirm the slice of events and whether vendor keys are available before writing code. For the store-wiring design decision (mapper table shape, enhancer order in Dayfold's real store), brainstorm→plan→subagent-driven execution like prior SWIP work.
