# ADR 0056: SloopLogging Integration — Leveled, Scrubbed, On-Device Logging

## Status

**Proposed** 2026-07-12 (agent-drafted with the `sloop-logging` integration
work; accept on merge). Composes ADR 0047 (`:client` Compose-free core, the
module this front-door lives in), ADR 0054 (shares the debug glue + the
breadcrumb ring), ADR 0055 (analytics, when it lands on `main`).

## Context

Dogfooding and debug builds had two disconnected signal paths: `println`
scattered ad hoc, and the SWIP bug-reporter's breadcrumb ring (ADR 0054)
capturing whatever text happened to be logged nearby. Neither was leveled,
neither was consistently scrubbed, and `:client` (SWIP-free by design, ADR
0047) had no vocabulary for "log this milestone" that a host could bind to
a real sink without pulling SWIP into the shared KMP core.

SloopWorks now publishes `swip-logging` (`works.sloop.swip:swip-logging:0.1.1`,
depends on `swip-core:0.1.3`, ADR-0025 in the SWIP repo) — a leveled logger
with a PII scrubber that runs ahead of every writer. Wiring it raises the
same two forces ADR 0054 already resolved for the bug reporter: `:client`
must stay swip-free, and the release APK must carry zero swip bytes.

## Decision

- **`Log` front-door in `:client`** (`com.sloopworks.dayfold.client.Log`,
  landed with B1): `Log.d/i/w/e(tag) { message }`, 4 levels (debug/info/warn/
  error — parity with `@sloopworks/swip-logging`'s `Severity`), lazy inline
  message (below-threshold calls build no string), a `Volatile var sink`
  a host can bind, and a `println` fallback when unbound (bare `:client` /
  `desktopTest` / pre-init still print). `:client` stays SWIP-free — this
  file has no swip import.
- **SloopLogging bound behind `Log.sink` in the androidApp *debug* glue**
  (`LoggingGlue.kt`, mirrors the `debugDrawerPlugins()` / bug-reporter idiom
  from ADR 0054): a console writer + the in-app devtools drawer writer.
  `src/release` carries no such wiring — release keeps the `println`
  fallback, zero swip bytes, same enforcement shape as ADR 0054's
  `assembleRelease` classpath gate. Release's `installLogging` floors
  `Log.minLevel` at `WARN` so the unbound front-door doesn't ship at its
  `DEBUG` default; the tradeoff (below) is that this `println` fallback is
  **unscrubbed** — the PII scrubber lives inside SloopLogging, which release
  never binds — so release-build call sites must not pass raw PII, relying
  on call-site discipline rather than the scrubber as a backstop.
- **The PII scrubber runs inside SloopLogging, ahead of every writer** — the
  scrub boundary is the log call itself, not each writer. Callers pass ids/
  counts/enums/outcomes, never raw session tokens, emails, or names; the
  scrubber is a backstop, not a license (engine call sites at B5 follow this
  convention — see `AuthEngine`/`SyncEngine`/`HubEngine`/`NowEngine`).
- **Breadcrumb ring scrubbed too**: the bug-reporter's `Log.sink` wrapper
  (`BugReporterGlue.kt`) now runs `works.sloop.swip.logging.scrubString(m)`
  before appending to the 32-deep ring, closing a leak where the ring
  captured pre-scrub raw text even though downstream SloopLogging writers
  scrubbed their own copy.
- **On-device-only egress** — no upload path for logs (mirrors ADR 0054's
  bug-report lane posture); everything stays in the console + the local
  devtools drawer.
- **Level control is local-first** today (a device-local `Log.minLevel`, the
  coarse global floor already in the front-door) with a future `SloopConfig`
  seam reserved for remote/per-tag control — not built at B5.

## Rationale

- Reusing the `debugDrawerPlugins()` / bug-reporter shape (`src/main` calls a
  variant-agnostic seam; `src/debug` wires the real SDK; `src/release` is
  inert) keeps the zero-release-footprint bar enforceable by the same
  compile-gate idiom ADR 0054 already established, instead of a second bespoke
  wiring pattern.
- Scrubbing at the log boundary (inside SloopLogging, before any writer) means
  every consumer — console, drawer, breadcrumb ring — sees the same sanitized
  text; the alternative (each writer scrubs its own copy) is exactly the shape
  that produced the breadcrumb leak this ADR closes.
- A lazy, leveled `Log` facade with a `println` fallback lets `:client` unit
  tests and bare desktop runs print without any host binding — no test-only
  no-op sink needed.

## Consequences

Positive:
- One vocabulary for engine milestones (auth/sync/hub/now), consistent with
  the redux action log already in the reducer for state-transition detail.
- The breadcrumb ring and the console/drawer writers are now provably
  consistent — both pass through the same `scrubString`.
- `:client` gains zero swip surface; the release APK is unaffected.

Negative:
- Two scrub call sites still exist in source (SloopLogging's internal one +
  the explicit `scrubString` call in the breadcrumb wrapper) — necessary
  because the breadcrumb ring reads `Log.sink`'s raw arguments directly
  rather than going through a SloopLogging writer; a future refactor could
  fold the ring into SloopLogging as just another writer.
- Level control has no remote/operator lever yet — a misbehaving device stays
  at whatever `Log.minLevel` ships with the build until the `SloopConfig`
  seam lands.
- **Release logging is WARN+ via the unscrubbed `println` fallback, not a
  scrubbed writer.** SloopLogging (and its scrubber) is debug-only wiring;
  release has zero swip bytes by design, so there is no scrub boundary in a
  release build — only the `WARN` floor plus call-site no-PII discipline.
  Release logs are on-device-only (logcat), but they are not scrubbed.

## Follow-ups (deferred, not built at B5)

- **Analytics-mirror hook**: `Log.d` on each tracked event, once the
  analytics wiring (PR #327) lands on `main` — this branch predates it.
- **Per-tag drawer level toggle** (today's `minLevel` is a single global
  floor).
- **Wiring `apps/api`** to the TS `@sloopworks/swip-logging` package (this
  ADR covers the Kotlin/`:client`+`:androidApp` side only).

## Revisit Trigger

The analytics-mirror hook lands (ADR 0055 merges to `main`), a `SloopConfig`
remote-level seam is built, or `apps/api` logging is wired — each revisits
this ADR's scope.
