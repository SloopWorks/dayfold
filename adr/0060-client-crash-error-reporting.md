# ADR 0060: Client Crash / Error Reporting — Debug-Only Android, SWIP Error Pillar → Sentry (KMP project) + PostHog

## Status

**Proposed** 2026-07-15 (agent-drafted; accept on merge). Realizes
`docs/superpowers/specs/2026-07-14-client-crash-error-reporting-design.md`.
Companion to the SWIP error pillar (SWIP PR #67/#68/#73, published KMP
artifacts). Sibling of **ADR 0059** — the API half of the same pillar (merged
as PR #336, commit `c65c0d4`, since this ADR was drafted). Composes with ADR 0055 (analytics — shares `:swip-wiring`
and the debug/release glue split), ADR 0054 (bug reporter — same
debug-only idiom), ADR 0056/0057 (logging, inspector — same pattern).

## Context

The client currently wires `errors = NoOpErrors` (ADR 0055 shipped analytics
only, with errors/config/telemetry left NoOp). SloopWorks published the SWIP
error pillar's KMP/Android artifacts (`swip-sentry`), so Dayfold's dogfood
build can now turn on real crash and error capture: fatal crashes via
Sentry's global handler, mirrored into SWIP's owned PostHog stream on next
launch; handled `record()`/`wtf()` calls teed to both PostHog (owned) and
Sentry (triage).

A source review of the published SDK corrected two assumptions carried into
the design: `swipMiddleware(errors=…)` only emits breadcrumbs (no
`record()`, no try/catch), and a fatal crash is captured under Sentry's own
grouping with no shared id back to the mirrored PostHog event. Both
corrections are recorded below since they shape what slice 1 actually is.

## Decision

1. **Vendor + project — Sentry KMP project `4511734711189584`, EU org
   `o4511720596570112`, declared independently of any DSN.** Dayfold has
   three Sentry DSNs live in the same EU org, all `*.ingest.de.sentry.io`:
   this app's KMP project (`4511734711189584`), the API's Node project
   (`4511734782820432`, ADR 0059), and a legacy `SENTRY_DSN`. Because all
   three are EU hosts on the same org, a wrong-DSN paste (Node's or the
   legacy one) would still pass any check that derives `orgId`/`projectId`
   from the DSN itself — the check becomes tautological. `swip-sentry`'s
   `verifyDsn` asserts the passed `orgId`/`projectId` against the DSN's host
   label and path at init and fails the boot on a mismatch, but that guard
   only catches a wrong DSN if the asserted values are **independent** of
   it. So `projectId = "4511734711189584"` (a public id, not a secret) is a
   committed constant in the init call, never parsed from
   `BuildConfig.SENTRY_KOTLIN_EU_DSN`. This is the wrong-project guard in
   full — see also point 3.
2. **Scope = debug/dogfood-only Android, with an honest consent argument
   specific to the client (not the API's).** Every prior SWIP surface (bug
   reporter 0054, analytics 0055, logging 0056, inspector 0057) ships debug-
   only; crash reporting follows the identical pattern. The reason is
   stronger here than "consistency": the API's error pillar (ADR 0059)
   grants `ERRORS` consent unconditionally because an API error is
   infrastructure reporting on itself — no user is in the event. A **client**
   error can carry the device's `distinct_id`, so an unconditional grant on
   the client would be reporting on a real person by default. Consent is
   granted here **only because** this is a debug build running on the
   operator's own device — the operator consenting to capture their own
   crashes. That is not transferable: it is **not a release precedent**. A
   release build reporting real families' crashes needs a consent surface
   wired to `CollectionMode` and a privacy-policy disclosure (hard
   guardrails #1/#3/#4) — a future ADR, gated further on the SWIP gap in
   point 6.
3. **Custom `Application` hoist for earliest crash coverage, with a
   main-process guard.** `swipInit` previously ran in
   `MainActivity.onCreate`, missing any crash before the first activity.
   `DayfoldApp : Application` (new, `src/main`, variant-agnostic) now calls
   `swipInit(this)` from `Application.onCreate`, which always precedes
   `MainActivity.onCreate` — so Sentry's `UncaughtExceptionHandler` installs
   in the earliest app code and the `debugStoreEnhancer()` "before the store
   is created" ordering is preserved and strengthened. `swipInit` stays
   idempotent (`SwipAnalyticsHolder.swip != null` early-return), so
   `MainActivity` dropping its own call is safe either way.
   `Application.onCreate` runs in *every* process, though, and a future
   `:background`/work process would otherwise double-init Sentry and
   contend on the crash-marker file (point 4) — so debug `swipInit` gains an
   `isMainProcess(app)` guard at its top, ahead of the existing persistence
   guard. Dayfold declares no `android:process` today, so this is a no-op
   now; it makes the hoist correct against a future second process rather
   than a latent double-init landmine.
4. **Marker-file fatal mirror, and the fatal-vs-handled join distinction —
   record this precisely, it is the subtle part.** `initSentryAndroid` is
   `suspend`: it prepares an on-disk crash marker file off-main and, on
   launch, recovers any marker left by a prior fatal crash, mirroring it
   into SWIP's owned PostHog stream as `swip:event:error:1`,
   `handled:false`. This mirrored event carries Sentry's `eventId` for
   **dedupe only** — it is not written into the owned event's properties.
   **Handled and fatal errors are joined differently, and only one of them
   is a clean join:**
   - Handled `record()`/`wtf()` calls are teed by SWIP to both stores and
     **id-joined** — Sentry's `swip.fingerprint` tag equals the owned
     event's `error.fingerprint`. A dashboard can pivot cleanly between the
     two.
   - A **fatal** crash is caught by Sentry's own global handler, under
     Sentry's own grouping, with **no `swip.fingerprint` tag** — SWIP never
     sees the exception, only the marker file on next launch. So the
     mirrored PostHog event and its Sentry counterpart correlate only
     **fuzzily** (type, message, time), **not** by a shared id. Verification
     (point 5) states this correlation honestly rather than implying an id
     join that doesn't exist.
5. **Slice-1 contents: fatal crashes + breadcrumbs + a debug trigger — no
   production handled-error site.** Automatically captured: fatal crashes
   (via the marker-file mirror) and action-name breadcrumbs (the redux
   middleware's `errors.breadcrumb(...)`, context only — no try/catch, no
   `record()`). A debug-gated manual trigger (`errors.wtf(...)` plus a
   deliberate throw) proves the handled path end-to-end without shipping a
   production call site. **Sync-failure was considered and rejected** as
   slice-1's handled-error demo: `SyncFailed(val message: String)` carries a
   free-text, potentially-PII-bearing message that the analytics mapper
   already drops (`// drop free-text message`) — recording it as an error
   would reintroduce exactly what analytics discards. Sync failures are also
   transient/expected (offline, retry); recording each at `severity=ERROR`
   would inflate the owned stream and tee routine network noise to Sentry as
   a defect. Real handled-error sites belong on genuine
   defensive/"should-never-happen" branches, added deliberately as
   identified — not as a default over a routine condition. (If a
   sync-failure signal is wanted later, the correct shape is
   `wtf("sync.failed", <stable non-PII message>, severity = WARN)` — WARN
   stays owned-stream-only and is not teed to Sentry — never the raw
   `message`.)
6. **Release-scope blockers, named for a future ADR — not resolved here.**
   - **SWIP consent gap:** `initSentryAndroid` has no `consented: () ->
     Boolean` parameter (the TS `initSentryNode` requires one). Sentry
     initializes globally and captures immediately, so there is no
     product-side way to gate the *SDK* on `ConsentScope.ERRORS` — only
     whether Dayfold *grants* that scope, which is fine when the grant
     itself is honest (debug-only, operator's own device, point 2) but
     insufficient once a release build must be able to withhold capture
     per-family. Filed for SWIP; drafted at
     `.superpowers/sdd/swip-consent-gap-issue.md` (not yet opened — the
     controller files external issues). Any release-scope ADR is blocked on
     this landing (`beforeSend → drop` when `ERRORS` is denied).
   - **A consent surface** wired to `CollectionMode`/`ConsentScope.ERRORS`
     (none exists yet; analytics has the same open item from ADR 0055).
   - **A privacy-policy disclosure** naming crash/error reporting to a
     third-party processor, before it ever runs on a non-operator family's
     device.

## Rationale

- Reuses the ADR 0054/0055/0056/0057 debug-only, inert-release-mirror idiom
  rather than inventing a new wiring pattern for the fourth SWIP pillar.
- Declaring `orgId`/`projectId` as a committed constant rather than deriving
  it from the DSN is the only guard that actually catches a wrong-DSN paste
  across three same-org, same-host-pattern EU projects — a DSN-derived
  assertion is a tautology, not a check.
- The client/API consent asymmetry (unconditional at the API, device-scoped
  and debug-only at the client) is a real distinction, not an inconsistency:
  the two loops differ in whether a person's identifier is in the event.
- Hoisting to `Application.onCreate` is a strict improvement over
  `MainActivity.onCreate` init (earlier coverage, same ordering guarantees)
  and costs only a one-time, small, debug-only main-thread block before any
  UI is drawn.
- Alternatives rejected: shipping to release now (opens guardrails #1/#3/#4
  with no consent surface or disclosure); instrumenting sync-failure as the
  slice-1 handled-error proof (PII + noise, §5); deriving `projectId` from
  the DSN (defeats the wrong-project guard); leaving init in `MainActivity`
  (misses startup crashes).

## Consequences

Positive:
- Fatal crashes and handled errors from the operator's own dogfood device
  now reach Sentry (triage) and PostHog (owned stream), closing the last
  NoOp SWIP pillar (`errors`) on the client.
- Startup-crash coverage gap is closed in this same change (the
  `Application` hoist), not left as a known gap.
- The wrong-project guard is structural (a compile-time constant assertion
  at boot), not review-dependent.

Negative:
- The fatal-crash correlation between Sentry and the owned PostHog mirror is
  fuzzy (type/message/time), not an id join — a real limitation to state
  honestly in any on-call/triage runbook, not paper over.
- No production handled-error call site ships in this slice; defect
  visibility beyond crashes and the debug trigger is a follow-up.
- Release-scope reporting remains blocked on a SWIP SDK change (the
  `consented` gate) outside Dayfold's control, plus consent-surface and
  disclosure work inside it.

## Revisit Trigger

Revisit when: `swip-sentry` KMP gains a `consented`/`beforeSend`-drop gate
(unblocks the release-scope ADR); a real handled-error call site is
identified on a genuine defensive branch; iOS crash reporting
(sentry-cocoa, the two-dSYM upload lane) is scoped as its own ADR; or the
on-device smoke (Task 5 — Pixel, both fatal and handled paths, evidence not
assumption) surfaces a join or delivery behavior different from what's
recorded here.
