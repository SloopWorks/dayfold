# Client Crash / Error Reporting — Debug-Only Android, SWIP Error Pillar → Sentry (KMP project) + PostHog

**Date:** 2026-07-14
**Status:** Design, pending operator review → ADR 0059
**Scope:** Dayfold Android client (`apps/androidApp`, `apps/swip-wiring`), debug/dogfood variant only
**Companion:** SWIP error pillar (SWIP PR #67/#68/#73, published KMP artifacts). Realizes the
KMP/Android half of `sloopworksinstrumentationplatform/docs/superpowers/specs/2026-07-13-swip-errors-sentry-design.md`.
Sibling of ADR 0058 (the API half, PR #336).

---

## 1. Goal

Dayfold's Android **dogfood build** reports crashes and errors through SWIP. Fatal crashes are
captured by Sentry's global handler and **mirrored back into SWIP's owned PostHog stream** on the
next launch, so the crash the operator hit shows up alongside their analytics. Handled `record()` /
`wtf()` reports land in **both** PostHog (owned) and Sentry (triage), joinable on
`error.fingerprint` == Sentry's `swip.fingerprint` tag.

The client currently wires `errors = NoOpErrors` (ADR 0055 shipped analytics only). This turns the
real pillar on.

## 2. Scope decision — debug/dogfood only (why)

Every prior SWIP surface — bug reporter (ADR 0054), analytics (0055), logging (0056), inspector
(0057) — ships in **debug/dogfood builds only**; the release APK carries zero SWIP bytes, verified
by a `javap` check. Crash reporting follows the identical pattern, for the identical reasons:

- **No consent surface, no privacy-policy change.** In a debug build on the operator's own device,
  consent is the operator consenting to capture their *own* crashes. A release build reporting real
  families' crashes needs a consent surface wired to `CollectionMode` and a privacy-policy
  disclosure (hard guardrails #1/#3/#4) — that is a **future ADR**, not this one.
- **The dogfood target is exactly where the operator's crashes happen.** Dayfold is dogfooded on the
  operator's own household on debug/dogfood builds (Pixel, on-device). Debug-only still catches the
  crashes that matter today.
- **Android first.** Android is the real dogfood target and `swip-sentry`'s Android binding is the
  proven one (the synchronous-fatal marker-file mirror). iOS (sentry-cocoa + the two-dSYM upload
  trap) is a clean follow-up PR.

**This is the load-bearing distinction from the API (ADR 0058):** an API error is our
infrastructure reporting on itself, with no user in the event, so consent there is `() => true`
unconditionally. A *client* error can carry the device's `distinct_id`. Debug-only-on-the-operator's-
device is what makes granting consent honest here; it is not a precedent for release.

## 3. What "slice 1" actually captures (corrected from an earlier draft)

A source review of the published KMP SDK corrected two assumptions:

1. **`swipMiddleware(errors=…)` records nothing.** Its `errors` parameter is used **only for
   breadcrumbs** (`errors.breadcrumb("action", …)` on each *registered* action) — there is no
   try/catch and no `record()`. Passing the real facade buys crash **context**, not error capture.
   (The "swip-rk error hook, `mechanism = redux`" is an *unbuilt* SWIP fast-follow.)
2. **A fatal crash is not fingerprint-joined to Sentry.** Sentry's global handler captures the crash
   under its *own* grouping with no `swip.fingerprint` tag; SWIP mirrors it into PostHog as
   `swip:event:error:1` `handled:false` with a SWIP-computed `error.fingerprint`. The mirror carries
   Sentry's `eventId` for **dedupe only** — it is not written to the owned event's props. So a fatal
   crash correlates to its Sentry copy fuzzily (type/message/time), **not** by a shared id. Only
   handled `record()` / `wtf()` are cleanly id-joined (SWIP tees to Sentry and tags `swip.fingerprint`).

So **slice 1 captures, automatically:**

- **Fatal crashes** — Sentry's `UncaughtExceptionHandler` (installed by `SentryAndroid.init`) owns
  them; the marker-file mirror (baked into `swip-sentry`, handling the synchronous-Android-fatal
  case) converts each into a mirrored owned PostHog event on the **next launch**.
- **Breadcrumbs** — the redux middleware feeds action-name breadcrumbs into the ring buffer, so a
  subsequent crash carries context.

Slice 1 does **not** instrument any production handled-error site. That is deliberate (§7). Instead,
a **debug-gated manual trigger** exercises the handled `record()` / `wtf()` path so it is proven
end-to-end — the client analog of the API's `/debug/boom` + `/debug/wtf`.

## 4. Artifacts (Dayfold lags SWIP; jump to the published set)

`apps/swip-wiring/build.gradle.kts`:
- `works.sloop.swip:swip-core` `0.1.8 → 0.1.11`
- `works.sloop.swip:schema-dayfold` `0.1.4 → 0.1.7`

`apps/androidApp/build.gradle.kts` (all `debugImplementation`):
- `works.sloop.swip:swip-logging` `0.1.1 → 0.1.2`
- **add** `works.sloop.swip:swip-sentry:0.1.0` (pulls `io.sentry:sentry-kotlin-multiplatform` +
  `io.sentry:sentry-android` transitively — debug classpath only, so the release APK is untouched)

**Breaking-change risk (build-gated, not resolvable on paper):** the `SloopErrors` facade gained
`wtf` / `drainStorms` / `flush` / `health`, so Dayfold's hand-rolled `object NoOpErrors` in
`DayfoldAnalytics.kt` no longer satisfies it → **delete it, use swip-core's
`works.sloop.swip.errors.NoOpErrors`**. The 0.1.8 → 0.1.11 hop also pulls the durability fixes
(#71/#73); the existing analytics wiring (`PostHogTransport` ctor, `platformDeps` params,
`ConsentScope`, `CollectionMode`) must be confirmed still-compiling by an actual
`:androidApp:assembleDebug`, not assumed.

## 5. Wiring

The release variant (`src/release/.../SwipAnalyticsGlue.kt`) is an inert same-signature mirror and
stays untouched. Steps 1–6 below are in the **debug** glue (`src/debug/.../SwipAnalyticsGlue.kt`);
step 0 is shared `src/main`.

0. **Hoist init to a custom `Application` — so crashes during startup are caught.** Sentry must
   install its `UncaughtExceptionHandler` in the earliest app code; `swipInit` runs in
   `MainActivity.onCreate` today (`MainActivity:165`), which misses any crash before the first
   activity. Add `DayfoldApp : Application` in `src/main` and register it
   (`android:name=".DayfoldApp"` — the `<application>` tag has none today):
   ```kotlin
   // src/main — variant-agnostic. swipInit resolves to the debug glue (real) or the
   // release glue (inert `= Unit`), so this stays SWIP-free and release keeps zero bytes.
   class DayfoldApp : Application() {
     override fun onCreate() { super.onCreate(); swipInit(this) }
   }
   ```
   `MainActivity` **drops** its `swipInit(application)` call; `Application.onCreate` always precedes
   `MainActivity.onCreate`, so the "before the store is created" ordering (`debugStoreEnhancer()`
   reads the swip instance) is preserved and strengthened. `swipInit` is already idempotent
   (`SwipAnalyticsHolder.swip != null` early-return), so a stray double-call is harmless.

   **Main-process guard (new, required by the hoist).** `Application.onCreate` runs in *every*
   process; a future `:background`/work process would otherwise double-init Sentry and contend on the
   crash-marker file. Add an early guard at the top of the **debug** `swipInit` (this keeps `src/main`
   SWIP-free — `isMainProcess` is a SWIP symbol, already imported in the glue and already used for the
   persistence guard at line 84):
   ```kotlin
   fun swipInit(app: Application) {
     if (!isMainProcess(app)) return          // Application.onCreate fires per-process
     if (SwipAnalyticsHolder.swip != null) return
     …
   }
   ```
   Dayfold declares no `android:process` today, so this is a no-op now; it makes the hoist correct
   against a future second process rather than a latent double-init.

1. **Build the reporter once in `swipInit`, before `Swip.init`:**
   ```kotlin
   val reporter: CrashReporter = runBlocking(Dispatchers.IO) {
     initSentryAndroid(app, SentryInitConfig(
       dsn         = BuildConfig.SENTRY_KOTLIN_EU_DSN,
       region      = SentryRegion.EU,
       orgId       = "o4511720596570112",
       projectId   = "4511734711189584",   // the KMP project — declared INDEPENDENTLY (§6)
       release     = BuildConfig.VERSION_NAME,
       dist        = BuildConfig.VERSION_CODE.toString(),
       environment = "development",
       debug       = BuildConfig.DEBUG,
     ))
   }
   ```
   `initSentryAndroid` is `suspend` (it prepares the crash-marker file off-main and recovers a prior
   crash's marker). It must complete before `Swip.init`, so it is awaited with a one-time
   `runBlocking(Dispatchers.IO)`. **Cost:** briefly blocks the main thread in `Application.onCreate`
   (before any UI) on a small file read — one-time, debug-only, acceptable, and the correct trade for
   installing the crash handler as early as possible.

2. **Feed it into the deps:** the existing call already does
   `DayfoldSwip.platformDeps(...).copy(debugSink = …)`; extend it to
   `.copy(debugSink = …, crashReporter = reporter)`. `platformDeps` takes no `crashReporter` param,
   so `.copy` is the seam (it defaults to `NoOpCrashReporter`). With a real reporter present and
   `"errors" ∈ modules` (it is, in `androidProd()`), `Swip.init` builds a real `SwipErrors` runtime
   (`Swip.kt:290`) that tees handled errors to Sentry and mirrors foreign crashes back in.

3. **Grant ERRORS consent** alongside ANALYTICS — the pipeline parks events at `UNKNOWN` otherwise,
   so the mirrored crash event would enqueue and never ship:
   ```kotlin
   swip.analytics.setConsent(mapOf(
     ConsentScope.ANALYTICS to ConsentDecision.GRANTED,
     ConsentScope.ERRORS    to ConsentDecision.GRANTED,
   ))
   ```

4. **Breadcrumbs:** `swipMiddleware(errors = requireSwip().errors, …)` instead of `NoOpErrors`.
   Behaviour is unchanged (the middleware adds no try/catch and rethrows nothing new); it only
   emits action breadcrumbs. Registered-action names are already the analytics surface, so no new
   data category is exposed.

5. **BuildConfig field** (debug block only, `System.getenv`, like `POSTHOG_*`):
   ```kotlin
   buildConfigField("String", "SENTRY_KOTLIN_EU_DSN", "\"${System.getenv("SENTRY_KOTLIN_EU_DSN") ?: ""}\"")
   ```
   Empty ⇒ **skip** `initSentryAndroid` (guard) so a plain `./gradlew :androidApp:assembleDebug`
   without Infisical still builds and runs (errors just off), exactly as analytics behaves.

6. **Debug smoke trigger** (gated, exercises the handled path for §8): a debug-drawer action, or a
   `BuildConfig.DEBUG`-guarded hidden entry point, that calls
   `requireSwip().errors.wtf("dayfold.client.smoke", "deliberate client non-crash report", …)` and
   (separately) throws to exercise the fatal path. Not shipped in any user-facing surface.

## 6. The wrong-project guard — declare org/project independently

Dayfold has three Sentry DSNs in one EU org, all `*.ingest.de.sentry.io`:

| var | project id | owner |
|---|---|---|
| `SENTRY_KOTLIN_EU_DSN` | `4511734711189584` | **this — the KMP app** |
| `SENTRY_NODE_EU_DSN` | `4511734782820432` | the API (ADR 0058) |
| `SENTRY_DSN` | — | legacy |

`swip-sentry`'s `verifyDsn` asserts the passed `orgId` + `projectId` against the DSN's host label
and path at init, and **fails the boot** on a mismatch. That guard only works if `projectId` is
declared **independently** of the DSN. Deriving it from the DSN (as the SWIP live-smoke test does)
makes the check tautological and lets the API's or the legacy DSN sail through into the mobile
project. So `projectId = "4511734711189584"` is a committed constant (a public id, not a secret),
**not** parsed from `BuildConfig.SENTRY_KOTLIN_EU_DSN`.

## 7. Why not instrument sync-failure (rejected slice-1 handled site)

An earlier draft proposed wiring `errors.record()` at the sync-failure path to demonstrate handled
capture. Source review rejected it:

- `SyncFailed(val message: String)` (`Model.kt:568`) carries a **free-text** message — potentially
  PII-bearing. The analytics mapper already drops it (`map<SyncFailed> { SyncFailedEvent } // drop
  free-text message`); recording it as an error would reintroduce exactly what analytics discards.
- Sync failures are **transient/expected** (offline, retry). Recording each at `severity=ERROR`
  inflates the owned stream and tees network noise to Sentry as if it were a defect.

Real handled-error sites belong on genuine defensive/"should-never-happen" branches (`wtf()`'s
purpose), added deliberately as they are identified — not on a routine network condition. Slice 1
proves the handled path with the debug trigger (§5.6) instead. (If a sync-failure signal is wanted
later, the correct shape is `wtf("sync.failed", <stable non-PII message>, severity = WARN)` — WARN
lands in the owned stream only and is **not** teed to Sentry, per the pillar's §12 routing — never
the raw `message`.)

## 8. Verification

**Build / hermetic:**
- `infisical run --path=/dayfold -- ./gradlew :androidApp:assembleDebug` — green (this is also the
  0.1.8→0.1.11 breaking-change gate, §4).
- `apps/swip-wiring` `DayfoldLeakTest` — green (no PII leak through the mapper table).
- Release variant still `javap`-clean of `works/sloop/swip` (ADR 0055's zero-bytes guarantee holds
  because `swip-sentry` is `debugImplementation`).

**On the real device (Pixel dogfood) — the part no unit test substitutes for:**
1. **Handled path:** trigger the debug `wtf()` + a debug `record()` → confirm each in the **Sentry
   KMP project** (tagged `swip.fingerprint`) **and** PostHog (`error.fingerprint`), joined on the
   fingerprint. Same evidence shape as the API PR (event ids + matching fingerprints, not a claim).
2. **Fatal path:** force a real crash → app dies → **relaunch** → confirm (a) the crash in Sentry
   (its own grouping) and (b) a mirrored `swip:event:error:1 handled:false` in PostHog. These
   correlate by type/message/time, not by id (§3) — state that honestly.
3. **Local fast loop:** mirrored error events flow through `analytics.track(scope = ERRORS)`, so the
   **SWIP inspector drawer (ADR 0057)** shows them without a vendor round-trip.

## 9. Known gaps / follow-ups (named, not hidden)

- **Startup crash coverage — closed in this PR (§5.0).** Init is hoisted to a custom
  `Application.onCreate`, so Sentry's handler is installed in the earliest app code and catches
  crashes throughout startup, not just after the first activity. (Analytics moves with it — a
  strict improvement, and it now inits behind a main-process guard.)
- **SWIP gap (report, do not patch):** `initSentryAndroid` has no `consented: () -> Boolean`
  parameter (the TS `initSentryNode` requires one). Sentry initializes globally and captures
  immediately, so there is no way to gate the *SDK* on `ConsentScope.ERRORS` from the product side.
  Fine for debug-only-granted; the **release ADR is blocked** until `swip-sentry` KMP adds a consent
  gate (`beforeSend → drop` when ERRORS denied). File against SWIP.
- **iOS** (sentry-cocoa binding + the shared-framework-plus-app two-dSYM upload lane) — separate ADR.
- **Real handled-error call sites** (`record()` / `wtf()` on genuine defect branches) — added as
  identified; slice 1 ships only the debug trigger.
- **R8 mapping upload:** *not needed for this scope.* Debug builds are un-minified, so stacks are
  already symbolic. Mapping upload is a release-only concern for a future ADR.

## 10. ADR 0059 will record

Vendor + project (Sentry KMP `4511734711189584`, EU, and why not the Node/legacy DSN); debug-only
scope + the honest consent argument and the release boundary; the independent-id wrong-project guard;
the marker-file fatal mirror + the fatal-vs-handled join distinction; slice-1 contents (crashes +
breadcrumbs + debug trigger; no production handled site); the custom-`Application` hoist + its
main-process guard; and the SWIP `consented`-gate gap as the blocker for any release-scope follow-up.
