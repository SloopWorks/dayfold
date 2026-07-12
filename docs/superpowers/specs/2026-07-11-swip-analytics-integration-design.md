# Dayfold ⇄ SWIP analytics integration — design

**Date:** 2026-07-11 · **ADR:** 0055 (to be written) · **Branch:** `feat/swip-analytics-integration`

Wires live product analytics into Dayfold via the already-shipped SWIP KMP
SDK (swip-core / schema-dayfold / swip-lifecycle / swip-rk, all published to
GitHub Packages). Analytics flows from the redux store through a mapper table
in Dayfold's composition root; `:client` stays SWIP-free. Follows the
bug-reporter integration pattern (ADR 0054) and the swip-rk canonical wiring
(`sdk-kmp/swip-rk` on SWIP `origin/main`).

## Resolved decisions (operator-gated, answered 2026-07-11)

1. **Vendor gate (ADR-0015): MET.** Live **PostHog** transport. Keys in
   Infisical workspace `f780d948-…` folder `/dayfold` (dev/staging/prod):
   `POSTHOG_HOST=https://eu.i.posthog.com` (EU, correct for Dayfold),
   `POSTHOG_PROJECT_KEY=phc_…` (client-side ingest key, meant to ship in-app).
   **No Sentry keys** → crash/errors/config/telemetry facades stay **NoOp**
   (analytics is the only live runtime, exactly as SWIP is built).
2. **First event slice:** ~8 product events (below), **count-only** — no
   identifiers, no free-text, no PII leaves the device in slice 1.
3. **Sequencing:** SWIP schema slice authored + published **first**, then the
   Dayfold wiring consumes the bumped `schema-dayfold`.

## Hard constraint that shaped the schema

`swipMappers { map<A> { … } }` (swip-rk `Mappers.kt`) keys on the exact action
`KClass` and the lambda receives **only the action** — never store state. So
every event field must be a property of the dispatched action. State-derived
values (e.g. a resolved `card_type` from a `cardId`) are **not available** and
are out of slice 1.

## Part A — SWIP repo: author + publish the event slice

Work in `~/workspace/sloopworksinstrumentationplatform` on a branch off
`origin/main` (local checkout is behind — pull first; swip-rk #42 is on
`origin/main`).

`app_foregrounded` / `app_backgrounded` / `screen_view` **already exist** in
`schemas/swip/` (shipped by swip-lifecycle) and are `anonymous_safe` — they are
consumed for free, not re-authored. New `schemas/dayfold/*.v1.yaml`
(JSON Schema 2020-12, `additionalProperties:false`, every field carries
`x-swip.privacy_class`; ids opaque; health-adjacent = `sensitive`):

| Event `$id` | Source action | Props (privacy_class) | Notes |
|---|---|---|---|
| `account_signed_in.v1` | `SignInSucceeded(session)` | *(none)* | session carries PII → dropped; success is the signal |
| `signed_out.v1` | `SignedOut` | *(none)* | |
| `family_created.v1` | `FamilyCreated(familyId,name)` | *(none)* | drops name (PII) + id |
| `invite_redeemed.v1` | `InviteRedeemed(familyName?)` | *(none)* | drops familyName (PII) |
| `invite_rejected.v1` | `InviteRejected(reason)` | `reason` (none) | enum: expired\|locked\|already\|removed\|error |
| `hub_opened.v1` | `OpenHub(hubId)` | *(none)* | **count-only** — hubId dropped |
| `card_opened.v1` | `NavToDetail(cardId)` | *(none)* | **count-only** — cardId dropped |
| `sync_failed.v1` | `SyncFailed(message)` | *(none)* | **drops** the free-text message |
| `checklist_item_toggled.v1` | *(TBC — see Known unknowns)* | `checked` (none, bool) | engagement signal; no item id/content |

All events are `anonymous_safe` (no props, or a single low-risk enum/bool) so
they survive ANONYMOUS mode. None are `sensitive` because none carry content.

Then: `pnpm swip schema check` + `pnpm swip schema gen`, commit generated
Kotlin (`schemas/dayfold` → `@sloopworks/swip-schema-dayfold` types +
regenerated `DayfoldAnonymousSafe`/`Critical`/`PseudonymousStrip`), PR to SWIP
main, bump + publish `schema-dayfold` **0.1.1 → 0.1.2** via `publish-kmp`
(`modules` input **trimmed to only** `:schema-dayfold:publishAllPublicationsToGitHubPackagesRepository`
— GH Packages versions are immutable; the full default 409s).

## Part B — Dayfold repo: composition-root wiring (`:client` stays SWIP-free)

Consume in `apps/swip-wiring/build.gradle.kts`:

```
implementation("works.sloop.swip:swip-core:0.1.1")
implementation("works.sloop.swip:schema-dayfold:0.1.2")   // bumped, has events
implementation("works.sloop.swip:swip-lifecycle:0.1.0")
implementation("works.sloop.swip:swip-rk:0.1.0")
```

1. **`Swip.init`** in the androidApp composition root:
   `Swip.init(DayfoldSwip.androidProd(), DayfoldSwip.platformDeps(transport=…, storage=…, nowMs=…, monotonicNowMs=…, random=…, ioDispatcher=…), appScope)`.
   `platformDeps(...)` (ADR-0019 codegen) auto-fills
   `criticalSchemas`/`anonymousSafeSchemas`/`pseudonymousStrip`. Supply runtime
   bits + transport. `initialMode` from Dayfold's resolved privacy profile.
2. **Transport = PostHog:** `POSTHOG_PROJECT_KEY` + `POSTHOG_HOST` reach the app
   as `buildConfigField`s from env (mirror the existing
   `DAYFOLD_API`/`HOUSEHOLD_SECRET`/`DEV_AUTH_SECRET` pattern in
   `apps/androidApp/build.gradle.kts`); CI injects them from Infisical. The
   PostHog transport constructor is the one place the keys are read.
3. **Lifecycle (swip-lifecycle):** `SwipLifecycle.install(...)` via
   `ProcessLifecycleOwner`; `handle.screen(route)` wired off the store's route
   selector (screen_view is route-driven, not Activity/Fragment auto).
4. **swip-rk mapper table** in the composition root — the table **is** the
   tracking spec (unmapped actions emit nothing). Wire through the existing
   `createAppStore(extraEnhancer=…)` seam (Reducer.kt:249):
   ```
   compose(
     swipTimingEnhancer(swip.telemetry, mappers, monotonicNowMs, random),   // OUTSIDE applyMiddleware
     applyMiddleware(
       thunkMiddleware,                                                       // outermost
       swipMiddleware(
         analytics   = swip.analytics.asSloopAnalytics(),
         errors      = NoOpErrors, config = NoOpConfig,                       // dead-until-wired
         mappers     = mappers,
         replayGuard = ReplayGuard.detectDevtools(isDebug = BuildConfig.DEBUG),
         consentGate = { swip.analytics.collectionMode() != CollectionMode.ANONYMOUS },
       ),
     ),
   )
   ```
   Reducers never import `works.sloop.swip` (purity / time-travel / replay).
5. **Release variant = zero SWIP bytes** — mirror the bug-reporter's release
   strip (`apps/androidApp/src/release/…/BugReporterGlue.kt` no-op pattern).

## Tests (hermetic — injected clock, seeded RNG, in-memory analytics)

- **Mapper-table golden** (the workhorse): parameterize over every registered
  mapper → construct the action → run through `swipMiddleware` with an
  in-memory `SloopAnalytics` → assert the emitted event vs a golden. Coverage
  of the table = the spec of what Dayfold tracks.
- **Purity:** dispatch a scripted action log twice → identical event sequences
  (minus ids/timestamps).
- **Privacy leak test:** extend `apps/swip-wiring/.../DayfoldLeakTest.kt` (the
  mandatory salted-PII test) to cover analytics event props — mapper lambdas
  must project only classified fields, never session/name/message.

## Deferred (operator / follow-up)

- Consent state → `swip.analytics.setCollectionMode(...)` / the `consentGate`.
  Nothing behavioral emits pre-consent.
- **On-device smoke.** Hermetic tests + goldens cannot catch host-integration
  bugs — the bug-reporter found 3 that way (insets, unreachable scrubber, dep
  visibility). A manual device smoke is a distinct, required gate.
- Opaque-id funnels (hub_id/card_id) as an additive schema follow-up if a real
  analytic need appears.
- iOS wiring; config-as-state middleware + `swipThunk` (intentionally deferred
  in swip-rk — do not build).

## Known unknowns (resolve during the plan, not blocking design)

1. **`checklist_item_toggled` mappability.** The toggle appears to run through
   an optimistic `ChecklistFold` burst set / effect, not obviously a discrete
   dispatched `Action`. If there is no mappable action carrying the new
   `checked` state, either (a) substitute another high-value mapped action, or
   (b) add a thin toggle action. Confirm before authoring that schema.
2. Exact `platformDeps(...)` runtime-arg names + `PostHogTransport` constructor
   signature — read from the published `swip-core:0.1.1` / `schema-dayfold`
   sources at wiring time.

## ADR 0055

New Dayfold ADR (next after 0054) records: analytics integration posture, live
PostHog EU transport under ADR-0015, count-only slice-1 privacy floor,
analytics-only (Sentry deferred), and the `:client`-stays-SWIP-free boundary.
Customer-data-handling posture (guardrail #3: behavioral analytics to a
third-party vendor + disclosure) is operator-ratified via this ADR.
