# SWIP Analytics Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire live PostHog analytics into Dayfold's redux store via the published SWIP KMP SDK, debug/internal-only, count-only, `:client` stays SWIP-free.

**Architecture:** Two repos. **Phase A** (SWIP repo): add a geoip-scrub to `PostHogTransport`, author the first Dayfold event-schema slice, publish `swip-core`+`schema-dayfold` 0.1.2. **Phase B** (Dayfold repo): a `swipMappers` action→event table + `NoOpErrors` facade in `:swip-wiring`; `Swip.init` + PostHog transport + `SwipLifecycle` + the analytics middleware composed into the existing `extraEnhancer` slot, all in the androidApp **debug** glue (release mirror stays inert).

**Tech Stack:** Kotlin 2.3.20 / KMP, redux-kotlin 1.0.0-alpha03 (transitive via swip-rk), SWIP artifacts on GitHub Packages, JSON Schema 2020-12 (SWIP schema authoring), PostHog EU wire transport, JDK17.

**Design source:** `docs/superpowers/specs/2026-07-11-swip-analytics-integration-design.md`

## Global Constraints

- **`:client` stays SWIP-free** — no `works.sloop.swip` import in `apps/client` (build-enforced boundary). Mappers/slices live in `:swip-wiring`; init/transport in the androidApp debug glue.
- **Debug/internal-only.** Analytics + PostHog keys ship in **debug builds only**. The `src/release` glue mirror returns `null` (zero swip-analytics bytes).
- **Count-only slice 1.** No identifiers, no free-text, no PII in any event. **Never call `analytics.identify()`** with `userId`/email.
- **Reducers never import `works.sloop.swip`** (purity / time-travel / replay).
- **SWIP artifacts** resolve from GitHub Packages (`SLOOPWORKS_PACKAGES_TOKEN` / `gpr.token`, already configured in `apps/settings.gradle.kts`).
- **GH Packages versions are immutable** — republish = bump patch; trim the `publish-kmp` `modules` input to only the bumped module(s).
- TDD, frequent commits. SWIP repo: `~/workspace/sloopworksinstrumentationplatform` (branch off `origin/main` — pull first). Dayfold repo: current branch `feat/swip-analytics-integration`.
- **Verified SDK signatures (from swip-core/swip-rk/swip-lifecycle/schema-dayfold `origin/main`):**
  - `Swip.init(config: SwipInitConfig, deps: SwipPlatformDeps, scope: CoroutineScope): SwipInstance`; `SwipInstance.analytics: AnalyticsHandle` is the **only** public member (no `.telemetry`/`.errors`/`.config`).
  - `DayfoldSwip.androidProd(): SwipInitConfig`; `DayfoldSwip.platformDeps(transport, storage, appVersion, os, nowMs, monotonicNowMs, random, persistence?=null, ulid?=null, flushIntervalMs=30_000, initialMode=FULL, channelSignals=ChannelSignals()): SwipPlatformDeps`.
  - `PostHogTransport(apiKey: String, host: String, http: HttpPoster) : AnalyticsTransport`; `HttpUrlConnectionPoster() : HttpPoster` (androidMain, no-arg).
  - `AnalyticsHandle.track(event, tier=NORMAL)`, `flush()`, `setCollectionMode(mode)`, `collectionMode(): CollectionMode`.
  - `enum class CollectionMode { FULL, PSEUDONYMOUS, ANONYMOUS, ESSENTIAL, OFF }`.
  - `AnalyticsHandle.asSloopAnalytics(): SloopAnalytics` (swip-rk `Adapter.kt`).
  - `swipMiddleware<S>(analytics: SloopAnalytics, errors: SloopErrors, mappers: SwipActionMappers, config: SloopConfig? = null, replayGuard: ReplayGuard, consentGate: () -> Boolean = { true }): Middleware<S>`.
  - `ReplayGuard.detectDevtools(isDebug: Boolean): ReplayGuard`.
  - `swipMappers { map<A> { (A) -> SwipEvent } }` — lambda gets **the action only**, keyed on exact `KClass`.
  - `interface SloopErrors { fun record(error, attrs=emptyMap(), mechanism="generic"); fun breadcrumb(category: String, message: String) }` — **no NoOp provided; write one.**
  - `SwipLifecycle.install(app, analytics: AnalyticsHandle, monotonicNowMs = { SystemClock.elapsedRealtime() }): SwipLifecycleHandle` (androidMain); `SwipLifecycleHandle.screen(name: String)`.

---

# Phase A — SWIP repo (`~/workspace/sloopworksinstrumentationplatform`)

Pull first (local checkout is behind): `git checkout main && git pull origin main && git checkout -b dayfold-analytics-slice`.

### Task A1: PostHog geoip scrub (privacy P1)

**Files:**
- Modify: `sdk-kmp/swip-core/src/commonMain/kotlin/works/sloop/swip/pipeline/PostHogTransport.kt`
- Test: `sdk-kmp/swip-core/src/desktopTest/kotlin/works/sloop/swip/pipeline/PostHogTransportTest.kt`

**Interfaces:**
- Produces: the wire payload every event's `properties` object now contains `$geoip_disable: true` and `$ip: "0.0.0.0"`.

- [ ] **Step 1: Write the failing test** — add to `PostHogTransportTest.kt`, sending one event through the transport with a fake `HttpPoster` that captures the body:

```kotlin
@Test fun properties_disable_geoip_and_scrub_ip() = runTest {
    var captured = ""
    val http = object : HttpPoster {
        override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            captured = body; return HttpResponse(200)
        }
    }
    PostHogTransport("phc_test", "https://eu.i.posthog.com", http).send(oneEventBatch())
    assertTrue("\"\$geoip_disable\":true" in captured, captured)
    assertTrue("\"\$ip\":\"0.0.0.0\"" in captured, captured)
}
```
(Reuse the existing test's batch helper for `oneEventBatch()`; if none, build a minimal `EventBatch` exactly as the neighbouring tests do.)

- [ ] **Step 2: Run — verify it fails.** `./gradlew :swip-core:desktopTest --tests '*PostHogTransportTest'` → FAIL (keys absent from body).

- [ ] **Step 3: Implement.** In `PostHogTransport.send(...)`, inside the per-event `buildJsonObject { "properties" ... }` block, add alongside the existing `$app_version`/`$os`/`$lib` puts:

```kotlin
put("\$geoip_disable", true)
put("\$ip", "0.0.0.0")
```

- [ ] **Step 4: Run — verify pass.** Same command → PASS. Also run full `:swip-core:desktopTest` (should stay green).

- [ ] **Step 5: Commit.**

```bash
git add sdk-kmp/swip-core/src/commonMain/kotlin/works/sloop/swip/pipeline/PostHogTransport.kt \
        sdk-kmp/swip-core/src/desktopTest/kotlin/works/sloop/swip/pipeline/PostHogTransportTest.kt
git commit -m "fix(swip-core): disable PostHog geoip + scrub \$ip in transport properties"
```

### Task A2: Author the Dayfold event-schema slice

**Files:**
- Create: `schemas/dayfold/account_signed_in.v1.yaml`, `signed_out.v1.yaml`, `family_created.v1.yaml`, `invite_redeemed.v1.yaml`, `invite_rejected.v1.yaml`, `hub_opened.v1.yaml`, `card_opened.v1.yaml`, `sync_failed.v1.yaml` (+ `checklist_item_toggled.v1.yaml` conditional — Step 2).
- Generated (committed, never edited): `sdk-kmp/schema-dayfold/src/commonMain/kotlin/works/sloop/swip/schema/dayfold/*` (types + regenerated AnonymousSafe/Critical/PseudonymousStrip).

**Interfaces:**
- Produces: `@Serializable` Kotlin event types in `works.sloop.swip.schema.dayfold` (naming: `account_signed_in` → `AccountSignedInEvent`; zero-prop → `data object`, with-prop → `data class`). **Confirm exact generated names after gen (Step 4) — Phase B mappers consume them.**

- [ ] **Step 1: Read** `INVARIANTS.md` + `.claude/skills/instrument-with-swip/SKILL.md` (§1). Schema shape (from `schemas/swip/screen_view.v1.yaml`): `$id: swip:event:<name>:1`, `additionalProperties: false`, `x-swip: { owner: dayfold, introduced: 2026-07-11, lifecycle: active, anonymous_safe: true, destinations: [analytics] }`.

- [ ] **Step 2: Resolve the checklist unknown (G4).** In Dayfold, grep the client for the dispatched action that toggles a checklist item: `grep -rniE 'ChecklistFold|toggl|done' apps/client/src/commonMain/.../*.kt | grep ': Action'`. **If** a discrete `Action` carrying the new checked state exists → author `checklist_item_toggled.v1.yaml` with a `checked` bool. **If not** (it's an effect/burst via `ChecklistFold`) → **omit** `checklist_item_toggled` from slice 1, note it in the ADR as a follow-up (add a thin toggle action later), and author the other 8 events only. Record the decision in the commit message.

- [ ] **Step 3: Author the 8 zero-prop / single-field schemas.** All identical shape except props. Example zero-prop (`account_signed_in.v1.yaml`, also the template for `signed_out`, `family_created`, `invite_redeemed`, `hub_opened`, `card_opened`, `sync_failed`):

```yaml
$id: swip:event:account_signed_in:1
type: object
additionalProperties: false
x-swip:
  owner: dayfold
  introduced: 2026-07-11
  lifecycle: active
  anonymous_safe: true
  destinations: [analytics]
properties: {}
```

`invite_rejected.v1.yaml` (single enum field):

```yaml
$id: swip:event:invite_rejected:1
type: object
additionalProperties: false
x-swip:
  owner: dayfold
  introduced: 2026-07-11
  lifecycle: active
  anonymous_safe: true
  destinations: [analytics]
properties:
  reason:
    type: string
    enum: [expired, locked, already, removed, error]
    x-swip: { privacy_class: none }
```

`checklist_item_toggled.v1.yaml` (only if Step 2 found a mappable action):

```yaml
$id: swip:event:checklist_item_toggled:1
type: object
additionalProperties: false
x-swip:
  owner: dayfold
  introduced: 2026-07-11
  lifecycle: active
  anonymous_safe: true
  destinations: [analytics]
properties:
  checked:
    type: boolean
    x-swip: { privacy_class: none }
```

- [ ] **Step 4: Check + generate.** `pnpm swip schema check` (must pass — every field classified, `anonymous_safe` lints ok). Then `pnpm swip schema gen`. Inspect the generated `schema-dayfold` types and **record their exact names** for Phase B.

- [ ] **Step 5: Verify compile.** `./gradlew :schema-dayfold:compileKotlinMetadata` (or the repo's schema-dayfold desktopTest) → green.

- [ ] **Step 6: Commit.**

```bash
git add schemas/dayfold/ sdk-kmp/schema-dayfold/src/commonMain/kotlin/works/sloop/swip/schema/dayfold/
git commit -m "feat(dayfold): first analytics event-schema slice (count-only, anonymous_safe)"
```

### Task A3: Version bump + PR + publish (operator-gated)

- [ ] **Step 1: Bump** `swip-core` + `schema-dayfold` 0.1.1 → 0.1.2 (repo's version mechanism — `gradle.properties`/version catalog; mirror PR #39's bump commit).
- [ ] **Step 2: PR** the branch to SWIP `main`; poll both CI lanes green; merge on user say-so.
- [ ] **Step 3: Publish (OPERATOR-run `workflow_dispatch`, post-merge).** `gh workflow run publish-kmp.yml -f modules=":swip-core:publishAllPublicationsToGitHubPackagesRepository :schema-dayfold:publishAllPublicationsToGitHubPackagesRepository" --ref main`. Verify `works.sloop.swip:{swip-core,schema-dayfold}:0.1.2` live on GH Packages. **Phase B is blocked until this is green.**
- [ ] **Step 4: Update** SWIP memory `swip-project-state.md` (Dayfold analytics slice published).

---

# Phase B — Dayfold repo (branch `feat/swip-analytics-integration`)

### Task B1: Consume artifacts + debug BuildConfig keys

**Files:**
- Modify: `apps/swip-wiring/build.gradle.kts` (add deps)
- Modify: `apps/androidApp/build.gradle.kts` (debug-only `buildConfigField`s)

**Interfaces:**
- Produces: `BuildConfig.POSTHOG_PROJECT_KEY`, `BuildConfig.POSTHOG_HOST` (debug variant); the four `works.sloop.swip:*` deps on the `:swip-wiring` classpath.

- [ ] **Step 1: Add deps** to `apps/swip-wiring/build.gradle.kts` `commonMain` (beside the existing `swip-rk-recorder`):

```kotlin
api("works.sloop.swip:swip-core:0.1.2")
api("works.sloop.swip:schema-dayfold:0.1.2")
api("works.sloop.swip:swip-lifecycle:0.1.0")
api("works.sloop.swip:swip-rk:0.1.0")
```

- [ ] **Step 2: Add debug-only BuildConfig keys** in `apps/androidApp/build.gradle.kts` — in the `debug` buildType block (NOT `defaultConfig`, so release never carries them):

```kotlin
buildTypes {
  getByName("debug") {
    buildConfigField("String", "POSTHOG_PROJECT_KEY", "\"${System.getenv("POSTHOG_PROJECT_KEY") ?: ""}\"")
    buildConfigField("String", "POSTHOG_HOST", "\"${System.getenv("POSTHOG_HOST") ?: "https://eu.i.posthog.com"}\"")
  }
}
```

- [ ] **Step 3: Verify** `./gradlew :swip-wiring:compileKotlinMetadata` resolves the new artifacts (needs `SLOOPWORKS_PACKAGES_TOKEN`/`gpr.token`). Expected: green (artifacts download from GH Packages).

- [ ] **Step 4: Commit.**

```bash
git add apps/swip-wiring/build.gradle.kts apps/androidApp/build.gradle.kts
git commit -m "build: consume swip analytics artifacts + debug-only PostHog BuildConfig keys"
```

### Task B2: Mapper table + NoOpErrors facade (in `:swip-wiring`, hermetically tested)

**Files:**
- Create: `apps/swip-wiring/src/commonMain/kotlin/com/sloopworks/dayfold/swip/DayfoldAnalytics.kt`
- Test: `apps/swip-wiring/src/desktopTest/kotlin/com/sloopworks/dayfold/swip/DayfoldMapperTableTest.kt`

**Interfaces:**
- Consumes: Dayfold action types from `:client` (`SignInSucceeded`, `SignedOut`, `FamilyCreated`, `InviteRedeemed`, `InviteRejected(reason)`, `OpenHub`, `NavToDetail`, `SyncFailed`); generated event types from `schema-dayfold` (exact names per Task A2 Step 4).
- Produces: `fun dayfoldMappers(): SwipActionMappers`; `object NoOpErrors : SloopErrors`.

- [ ] **Step 1: Write the failing test** (the workhorse golden). For every registered action, construct it, run it through `swipMiddleware` with an in-memory `SloopAnalytics`, assert the emitted event **type**:

```kotlin
class DayfoldMapperTableTest {
  private class Rec : SloopAnalytics {
    val events = mutableListOf<SwipEvent>()
    override fun track(event: SwipEvent) { events.add(event) }
    override fun identify(distinctId: String, traits: Map<String, JsonElement?>) = error("must not identify")
    override fun alias(previousId: String) {}
    override fun reset() {}
    override suspend fun flush() = FlushResult(0, 0)
    override fun setConsent(consent: Map<ConsentScope, ConsentDecision>) {}
    override fun optIn(scope: ConsentScope) {}
    override fun optOut(scope: ConsentScope) {}
  }
  private fun emit(action: Any): List<SwipEvent> {
    val rec = Rec()
    val mw = swipMiddleware<AppState>(rec, NoOpErrors, dayfoldMappers(), null, ReplayGuard.fixed(false))
    // redux-kotlin: Store.dispatch is a var; build the chain manually
    val store = createAppStore(debug = false)
    mw(store).invoke { it }.invoke(action)
    return rec.events
  }
  @Test fun invite_rejected_maps_reason() {
    val e = emit(InviteRejected("expired")).single()
    assertEquals(InviteRejectedEvent("expired"), e)   // adjust to generated name/shape
  }
  @Test fun sign_in_maps_and_carries_no_pii() {
    val e = emit(SignInSucceeded(Session("a1", "r1"))).single()
    assertEquals(AccountSignedInEvent, e)             // data object — no session data
  }
  // ... one case per registered action (family_created, invite_redeemed, hub_opened, card_opened, sync_failed, signed_out)
}
```

- [ ] **Step 2: Run — verify it fails.** `./gradlew :swip-wiring:desktopTest --tests '*DayfoldMapperTableTest'` → FAIL (unresolved `dayfoldMappers`/`NoOpErrors`).

- [ ] **Step 3: Implement** `DayfoldAnalytics.kt` (map each action to its generated event; **only** action-carried, classified fields — never session/name/message):

```kotlin
package com.sloopworks.dayfold.swip

import com.sloopworks.dayfold.client.*
import kotlinx.serialization.json.JsonElement
import works.sloop.swip.SloopErrors
import works.sloop.swip.rk.SwipActionMappers
import works.sloop.swip.rk.swipMappers
import works.sloop.swip.schema.dayfold.*   // generated event types

/** The tracking spec: unmapped actions emit nothing. Fields come from the ACTION only. */
fun dayfoldMappers(): SwipActionMappers = swipMappers {
  map<SignInSucceeded> { AccountSignedInEvent }              // drop session (PII)
  map<SignedOut> { SignedOutEvent }
  map<FamilyCreated> { FamilyCreatedEvent }                  // drop name + id
  map<InviteRedeemed> { InviteRedeemedEvent }                // drop familyName
  map<InviteRejected> { InviteRejectedEvent(it.reason) }     // reason is a `none` enum
  map<OpenHub> { HubOpenedEvent }                            // drop hubId (count-only)
  map<NavToDetail> { CardOpenedEvent }                       // drop cardId (count-only)
  map<SyncFailed> { SyncFailedEvent }                        // drop message (free-text)
  // checklist_item_toggled: add ONLY if Task A2 Step 2 found a mappable action
}

/** swipMiddleware requires a SloopErrors; analytics-only build has no error runtime. */
object NoOpErrors : SloopErrors {
  override fun record(error: Throwable, attrs: Map<String, JsonElement?>, mechanism: String) {}
  override fun breadcrumb(category: String, message: String) {}
}
```
Adjust event-type names/shapes to the exact generated ones (Task A2 Step 4).

- [ ] **Step 4: Run — verify pass.** Same command → PASS.

- [ ] **Step 5: Purity test.** Add `@Test fun purity_double_dispatch_identical()` — run a scripted list of actions through two fresh middleware instances; assert identical event sequences. Run → PASS.

- [ ] **Step 6: Commit.**

```bash
git add apps/swip-wiring/src/commonMain/kotlin/com/sloopworks/dayfold/swip/DayfoldAnalytics.kt \
        apps/swip-wiring/src/desktopTest/kotlin/com/sloopworks/dayfold/swip/DayfoldMapperTableTest.kt
git commit -m "feat(swip-wiring): dayfold analytics mapper table + NoOpErrors (count-only, hermetic golden)"
```

### Task B3: Composition-root wiring (androidApp debug glue)

**Files:**
- Modify: `apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/BugReporterGlue.kt` (add `Swip.init` + analytics enhancer accessor + lifecycle install)
- Modify: `apps/androidApp/src/release/kotlin/com/sloopworks/dayfold/android/BugReporterGlue.kt` (inert mirror for the new accessor)
- Modify: `apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/MainActivity.kt:174` (call the combined enhancer) + wire the route→screen subscription

**Interfaces:**
- Consumes: `dayfoldMappers()`, `NoOpErrors`, `bugReporterEnhancer()`.
- Produces: `fun debugStoreEnhancer(): StoreEnhancer<AppState>?` (debug: recorder ∘ analytics middleware; release: `null`); `fun swipAnalyticsInstall(app, store)` (Swip.init + lifecycle + screen subscription).

- [ ] **Step 1: Read** the exact `AndroidSwipStorage` ctor (`git show origin/main:$(git -C ~/workspace/sloopworksinstrumentationplatform ls-tree -r origin/main --name-only | grep -i AndroidSwipStorage)` in the SWIP repo — likely `AndroidSwipStorage(context)`).

- [ ] **Step 2: Add the SWIP singleton + accessors** to the debug `BugReporterGlue.kt`. Init once; compose analytics middleware with the recorder into the single enhancer:

```kotlin
private object SwipAnalyticsHolder {
  var swip: SwipInstance? = null
  val mappers = dayfoldMappers()
  var lifecycle: SwipLifecycleHandle? = null
}

/** The ONE enhancer MainActivity passes: recorder (innermost) ∘ analytics middleware. */
fun debugStoreEnhancer(): StoreEnhancer<AppState>? = compose(listOfNotNull(
  bugReporterEnhancer(),
  applyMiddleware(swipMiddleware<AppState>(
    analytics   = requireSwip().analytics.asSloopAnalytics(),
    errors      = NoOpErrors,
    mappers     = SwipAnalyticsHolder.mappers,
    config      = null,                                     // no exposures in slice 1
    replayGuard = ReplayGuard.detectDevtools(isDebug = BuildConfig.DEBUG),
    consentGate = { requireSwip().analytics.collectionMode() in setOf(CollectionMode.FULL, CollectionMode.PSEUDONYMOUS) },
  )),
))

private fun requireSwip(): SwipInstance = SwipAnalyticsHolder.swip ?: error("Swip.init not called")

fun swipInit(app: android.app.Application, scope: CoroutineScope) {
  if (SwipAnalyticsHolder.swip != null) return
  SwipAnalyticsHolder.swip = Swip.init(
    DayfoldSwip.androidProd(),
    DayfoldSwip.platformDeps(
      transport = PostHogTransport(BuildConfig.POSTHOG_PROJECT_KEY, BuildConfig.POSTHOG_HOST, HttpUrlConnectionPoster()),
      storage   = AndroidSwipStorage(app),                 // confirm ctor in Step 1
      appVersion = BuildConfig.VERSION_NAME,
      os = "android",
      nowMs = { System.currentTimeMillis() },
      monotonicNowMs = { android.os.SystemClock.elapsedRealtime() },
      random = { kotlin.random.Random.nextDouble() },
      initialMode = CollectionMode.FULL,                   // dogfold internal build
    ),
    scope,
  )
}
```
**Ordering note:** `debugStoreEnhancer()` runs at store construction (MainActivity), so `swipInit(...)` must run first. Call `swipInit` in `MainActivity.onCreate` BEFORE `createAppStore(...)`.

- [ ] **Step 3: Lifecycle + screen subscription.** Add:

```kotlin
fun swipLifecycleInstall(app: android.app.Application, store: Store<AppState>) {
  val handle = SwipLifecycle.install(app, requireSwip().analytics)
  SwipAnalyticsHolder.lifecycle = handle
  var last: String? = null
  store.subscribe {
    val name = store.state.route.name          // Route is an id-free enum
    if (name != last) { last = name; handle.screen(name) }
  }
  handle.screen(store.state.route.name)          // initial
}
```

- [ ] **Step 4: Inert release mirror.** In `src/release/.../BugReporterGlue.kt`, add same-signature no-ops so the release APK carries zero analytics: `fun debugStoreEnhancer(): StoreEnhancer<AppState>? = null`, `fun swipInit(app, scope) {}`, `fun swipLifecycleInstall(app, store) {}`. (The existing inert `bugReporterEnhancer()` pattern is the template.)

- [ ] **Step 5: Wire MainActivity.** At `MainActivity.kt`, before store creation call `swipInit(application, appScope)`; change line 174 to `extraEnhancer = debugStoreEnhancer()`; after the store + engines are built call `swipLifecycleInstall(application, store)`.

- [ ] **Step 6: Verify compile (both variants).** `./gradlew :androidApp:compileDebugKotlin :androidApp:compileReleaseKotlin` → green. Confirm release has no PostHog symbols: `./gradlew :androidApp:assembleRelease` then grep the mapping/APK is optional — the inert mirror guarantees it.

- [ ] **Step 7: Commit.**

```bash
git add apps/androidApp/src/debug/... apps/androidApp/src/release/... apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/MainActivity.kt
git commit -m "feat(android): Swip.init + PostHog transport + lifecycle, analytics middleware in debug store enhancer"
```

### Task B4: Extend the salted-PII leak test to analytics props

**Files:**
- Modify: `apps/swip-wiring/src/desktopTest/kotlin/com/sloopworks/dayfold/swip/DayfoldLeakTest.kt`

**Interfaces:**
- Consumes: `dayfoldMappers()`.

- [ ] **Step 1: Write the failing test.** For each salted PII probe (JWT-shaped `eyJ…`, email `x@y.z`, a family name, a sync error string), construct the action carrying it, emit through the mapper, serialize every emitted event to JSON, and assert the probe string is **absent**:

```kotlin
@Test fun analytics_events_never_carry_salted_pii() {
  val salt = "LEAK_${'$'}{saltId}"
  val actions = listOf(
    SignInSucceeded(Session("eyJ$salt", "eyJ$salt")),
    FamilyCreated("fam_$salt", "The $salt Family"),
    InviteRedeemed("The $salt Family"),
    SyncFailed("boom $salt @host"),
  )
  for (a in actions) for (e in emitThrough(dayfoldMappers(), a)) {
    val json = Json.encodeToString(SwipEvent.serializer(), e)   // or the concrete serializer
    assertFalse(salt in json, "leak via ${'$'}{e::class.simpleName}: $json")
  }
}
```
(Use the module's existing salt helper; reuse `emit(...)` from Task B2 or a shared helper.)

- [ ] **Step 2: Run — verify it passes** (the mappers already drop these fields, so this is a *regression guard*). If it FAILS, a mapper is projecting an unclassified field — fix the mapper. `./gradlew :swip-wiring:desktopTest --tests '*DayfoldLeakTest'` → PASS.

- [ ] **Step 3: Commit.**

```bash
git add apps/swip-wiring/src/desktopTest/kotlin/com/sloopworks/dayfold/swip/DayfoldLeakTest.kt
git commit -m "test(swip-wiring): salted-PII leak guard covers analytics event props"
```

### Task B5: ADR 0055 + docs + memory + on-device smoke prep

**Files:**
- Create: `adr/0055-swip-analytics-integration.md`
- Modify: `adr/decisions-index.md`, `CHANGELOG.md`, `backlog/now.md`, `docs/architecture.md`
- Memory: Dayfold memory + `MEMORY.md` pointer

- [ ] **Step 1: Write ADR 0055** (Proposed → operator accepts). Record: live PostHog EU transport under ADR-0015; **debug/internal-only** scope; **count-only** slice-1 floor; **no-geoip** (SWIP-side `$geoip_disable`) + **never-identify-with-PII**; analytics-only (Sentry deferred); `:client`-stays-SWIP-free; scoped to the operator's dogfooded household; widening to real users = future ADR gated on a disclosure + consent surface. List the tracked event slice.
- [ ] **Step 2: Add** the `decisions-index.md` row; a dated `CHANGELOG.md` entry (user-visible: analytics in dogfood builds); update `backlog/now.md` state; add the analytics data-flow to `docs/architecture.md`.
- [ ] **Step 3: On-device smoke checklist** (operator step, in the PR body): run a debug build, sign in / open a hub / open a card / fail a sync (airplane mode), confirm the events land in the PostHog EU project. Hermetic tests can't catch host-integration bugs (bug-reporter lesson).
- [ ] **Step 4: Update memory** — new Dayfold memory file (analytics integration: debug-only, count-only, PostHog EU, ADR 0055) + `MEMORY.md` pointer; link `[[server-content-blind-e2ee]]`, `[[client-session-userid-from-jwt]]`.
- [ ] **Step 5: Commit + open PR**, poll CI green, merge on user say-so.

---

## Self-Review

**Spec coverage:** Part A geoip → A1 ✓; Part A schemas+publish → A2/A3 ✓; deps+keys → B1 ✓; mapper table+NoOp facades → B2 ✓; Swip.init+transport+lifecycle+screen-subscription+enhancer-compose → B3 ✓; leak test → B4 ✓; ADR/docs/deferred-smoke → B5 ✓. Decisions 1-6 all land in a task. `swipTimingEnhancer` correctly absent (C3). `thunkMiddleware` correctly absent (C1). Release-inert (C2/decision 4) → B3 Step 4. Consent allowlist (C4) → B3 Step 2. HttpPoster actual (G2) → B3. screen subscription (G3) → B3 Step 3. identify-forbidden (P2) → B2 Rec asserts, B4 guards.

**Placeholder scan:** the two labeled reads (generated event-type names A2§4→B2; `AndroidSwipStorage` ctor B3§1) are genuine external-artifact lookups (types produced by Task A2; SDK ctor), each with the exact file to read — not hand-waving. G4 (checklist) is an explicit branch in A2§2, not a TODO.

**Type consistency:** `dayfoldMappers()` / `NoOpErrors` / `debugStoreEnhancer()` / `swipInit` / `swipLifecycleInstall` names match across B2/B3/B4. `swipMiddleware(analytics, errors, mappers, config, replayGuard, consentGate)` arg order matches the verified signature. `CollectionMode.{FULL,PSEUDONYMOUS}` verified. Event-type names are placeholders pending A2§4 and flagged as such at every use.
