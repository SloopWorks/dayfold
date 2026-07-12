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
   `POSTHOG_PROJECT_KEY=phc_…` (client-side ingest key). **No Sentry keys** →
   crash/errors/config/telemetry facades stay **NoOp** (analytics is the only
   live runtime, exactly as SWIP is built).
2. **First event slice:** ~8 product events (below), **count-only** — no
   identifiers, no free-text, no PII leaves the device in slice 1.
3. **Sequencing:** SWIP slice (schemas + geoip scrub) published **first**, then
   the Dayfold wiring consumes the bumped `schema-dayfold`.
4. **Variant scope: DEBUG / internal only** (like the bug reporter). Analytics
   + PostHog keys ship in **debug/internal builds only**; the public release
   APK carries **zero swip analytics bytes** (inert release glue). No
   privacy-policy / consent surface is required for slice 1 (operator's own
   dogfooded household is the only subject). Flipping analytics into release
   for real users is a **later, ADR-gated** step requiring a disclosure +
   consent surface (guardrail #4).
5. **Geoip (privacy):** the PostHog transport must **not** leak location. A
   small SWIP-side change adds `$geoip_disable:true` (+ `$ip` scrub) to
   `PostHogTransport` properties — PostHog would otherwise server-side-geoip
   the device IP onto every event. Bundled into the Part-A SWIP PR.
6. **Identity: never `identify()` with PII.** `distinct_id` stays the opaque
   SWIP installation id (`event.distinctId ?: "anonymous"`). Dayfold must
   **never** pass `userId`/email to `analytics.identify()` — that would link
   all family behavior to the account. No identify call in slice 1.

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

**Also in the Part-A SWIP PR (P1 geoip fix):** in
`sdk-kmp/swip-core/.../pipeline/PostHogTransport.kt`, add
`put("\$geoip_disable", true)` and `put("\$ip", "0.0.0.0")` to each event's
`properties` object so PostHog does not geoip the device IP. Add a
`PostHogTransportTest` assertion. This bumps **swip-core 0.1.1 → 0.1.2** as
well (publish both `:swip-core` and `:schema-dayfold`, modules input trimmed to
exactly those two).

Then: `pnpm swip schema check` + `pnpm swip schema gen`, commit generated
Kotlin (`schemas/dayfold` → `@sloopworks/swip-schema-dayfold` types +
regenerated `DayfoldAnonymousSafe`/`Critical`/`PseudonymousStrip`), PR to SWIP
main, bump + publish `swip-core` + `schema-dayfold` **0.1.1 → 0.1.2** via
`publish-kmp` (`modules` input **trimmed to only**
`:swip-core:… :schema-dayfold:publishAllPublicationsToGitHubPackagesRepository`
— GH Packages versions are immutable; the full default 409s).

## Part B — Dayfold repo: composition-root wiring (`:client` stays SWIP-free)

**Key structural facts (verified against the real repo — the generic STEP-0
wiring example does NOT apply verbatim):**
- Dayfold has **no `thunkMiddleware`** — effects are engine/subscriber-driven
  (`SyncEngine`/`AuthEngine` subscribe to the store). Do **not** introduce one.
- `createAppStore(initial, debug, extraEnhancer)` (Reducer.kt:249) exposes a
  **single, innermost** enhancer slot, and it is **already used** by the bug
  reporter: `MainActivity.kt:174` → `extraEnhancer = bugReporterEnhancer()`.
  Analytics must **share** that slot, not claim it.
- Analytics is **debug/internal-only** (decision 4), same lifetime as the
  recorder → both live in `apps/androidApp/src/debug/.../BugReporterGlue.kt`;
  the `src/release` mirror stays inert (zero swip bytes) — which is now
  correct, not a mistake.

Consume in `apps/swip-wiring/build.gradle.kts`:

```
implementation("works.sloop.swip:swip-core:0.1.2")        // bumped: geoip scrub
implementation("works.sloop.swip:schema-dayfold:0.1.2")   // bumped: has events
implementation("works.sloop.swip:swip-lifecycle:0.1.0")
implementation("works.sloop.swip:swip-rk:0.1.0")
```

1. **`Swip.init`** (once, at debug-glue install):
   `Swip.init(DayfoldSwip.androidProd(), DayfoldSwip.platformDeps(transport=…, storage=…, nowMs=…, monotonicNowMs=…, random=…, ioDispatcher=…), appScope)`.
   `platformDeps(...)` (ADR-0019 codegen) auto-fills
   `criticalSchemas`/`anonymousSafeSchemas`/`pseudonymousStrip`. `initialMode`
   from Dayfold's resolved privacy profile (dogfood/US → FULL is fine; no
   consent UI needed for slice 1). **Never call `analytics.identify()` with
   PII** (decision 6).
2. **Transport = PostHog:** `POSTHOG_PROJECT_KEY` + `POSTHOG_HOST` reach the app
   as `buildConfigField`s from env, **debug variant only** (mirror the existing
   `DAYFOLD_API`/`HOUSEHOLD_SECRET`/`DEV_AUTH_SECRET` pattern in
   `apps/androidApp/build.gradle.kts`); CI/local injects them from Infisical
   `/dayfold`. Supply the Android **`HttpPoster` actual** (swip-core ships
   `HttpUrlConnectionPoster`) to the PostHog transport.
3. **Lifecycle (swip-lifecycle):** `SwipLifecycle.install(...)` via
   `ProcessLifecycleOwner`, fed the **same `monotonicNowMs`** source (so
   `foreground_ms` is consistent). `screen_view` is a **store subscription** in
   the composition root: subscribe to the store, and on route change (deduped)
   call `handle.screen(state.route.name)`. `Route` is an **id-free enum**, so
   `screen_name` carries no identifiers.
4. **swip-rk mapper table** — the table **is** the tracking spec (unmapped
   actions emit nothing). Compose the analytics middleware **with** the
   recorder into the single `extraEnhancer` slot, in the debug glue. **No
   `swipTimingEnhancer`** (it targets the telemetry facade, which is NoOp —
   pure per-dispatch overhead for zero output; add when telemetry lands):
   ```
   // in BugReporterGlue.kt (debug): the ONE enhancer MainActivity passes
   fun debugStoreEnhancer(): StoreEnhancer<AppState>? = compose(listOfNotNull(
     bugReporterEnhancer(),                          // existing recorder (innermost debug tool)
     applyMiddleware(swipMiddleware(
       analytics   = swip.analytics.asSloopAnalytics(),
       errors      = NoOpErrors, config = NoOpConfig, // SDK-provided NoOp facades
       mappers     = mappers,
       replayGuard = ReplayGuard.detectDevtools(isDebug = BuildConfig.DEBUG),
       consentGate = { swip.analytics.collectionMode() in setOf(FULL, PSEUDONYMOUS) },
     )),
   ))
   // MainActivity.kt:174 changes: extraEnhancer = debugStoreEnhancer()  (release mirror → null)
   ```
   Reducers never import `works.sloop.swip` (purity / time-travel / replay).
5. **Release variant = zero SWIP-analytics bytes** — the `src/release`
   `debugStoreEnhancer()` mirror returns `null` (same inert pattern the bug
   reporter already uses). Correct under decision 4.

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

- **Flip analytics into the release APK for real users** — ADR-gated; requires
  a privacy-policy disclosure + consent surface wired to `setCollectionMode`
  (guardrail #4) before any non-operator user is measured.
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
PostHog EU transport under ADR-0015, **debug/internal-only** variant scope,
**count-only** slice-1 privacy floor, **no-geoip** (SWIP-side `$geoip_disable`)
+ **never-identify-with-PII** rules, analytics-only (Sentry deferred), and the
`:client`-stays-SWIP-free boundary. Customer-data-handling posture (guardrail
#3/#4: behavioral analytics to a third-party processor) is operator-ratified
via this ADR **scoped to the operator's dogfooded household**; widening to
real users is a separate future ADR gated on a disclosure + consent surface.
