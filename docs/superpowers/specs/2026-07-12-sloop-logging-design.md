# SloopLogging — leveled logging facade + Dayfold front-door — design

**Date:** 2026-07-12 · **Branch:** `feat/sloop-logging-design`

Adds real, leveled, filterable logging to Dayfold. Today the only logger is
`ClientLog` (`:client`): `log(tag, msg)` → `println` + one host sink, **no
levels, no filtering**, ~5 call sites. This design makes logging a first-class,
low-noise, config-controllable capability — a **SWIP-owned `SloopLogging`
facade** (a 5th facade beside Analytics/Config/Errors/Telemetry) bound behind a
thin Dayfold-owned front-door so the shared core stays SWIP-free.

## Resolved decisions (operator, 2026-07-12)

1. **Ownership:** `SloopLogging` is **SWIP-owned** — a new hand-rolled KMP module
   `swip-logging`, reusable across SloopWorks products.
2. **Backend:** **hand-rolled native writers** (expect/actual: Logcat / os_log /
   println), zero third-party dep — matches swip-core's posture (no vendor
   logging lib). Not Kermit.
3. **Level control:** **local-first now** (build-type default minSeverity +
   per-tag overrides + a live debug-drawer toggle) with a **`LogLevelPolicy`
   seam** a future SWIP `SloopConfig` binding drives per app/user/group/version
   (the SloopConfig KMP runtime doesn't exist yet — deferred to SWIP Phase 2).
4. **Boundary:** **two layers** — a thin Dayfold-owned `Log` front-door in
   `:client` (zero SWIP dep) with `SloopLogging` bound behind its sink at the
   composition root. Keeps `:client` independently buildable/testable without the
   SWIP toolchain (ADR 0047 core-portability; the reason the boundary matters for
   logging — reducer-purity does not, since logging is an engine-side effect).
5. **Egress:** **on-device only.** No log leaves the device except via the
   existing **consented, sanitized bug-report breadcrumb** path. No new network
   surface.

## Architecture — two layers

```
:client engines / composition root
        │  Log.d("sync") { "401 — refreshing" }        (front-door, no SWIP import)
        ▼
   Log  (Dayfold-owned, :client, ~40 lines: evolves ClientLog)
        │  sink: (Severity, tag, msg, throwable?) -> Unit     (pluggable)
        ▼
   SloopLogging  (SWIP-owned, swip-logging module) — bound behind the sink at the composition root
        │  minSeverity policy (global + per-tag) → fan-out to writers
        ├─ PlatformConsoleWriter   (Logcat / os_log / println, expect/actual)
        ├─ DebugDrawerWriter       (existing Logs panel)
        ├─ BreadcrumbWriter        (→ SloopErrors.breadcrumb ring → bug reports)
        └─ AnalyticsMirrorWriter   (dev: logs each swip-rk-tracked event, DEBUG)
```

### Layer 1 — `Log` front-door (`:client`, Dayfold-owned, no SWIP dep)

Evolve `ClientLog` into a leveled facade:

```kotlin
enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }  // ordered

object Log {
  @Volatile var sink: ((level: LogLevel, tag: String, message: String, error: Throwable?) -> Unit)? = null
  @Volatile var minLevel: LogLevel = LogLevel.VERBOSE   // gate BEFORE building the message

  inline fun log(level: LogLevel, tag: String, error: Throwable? = null, message: () -> String) {
    if (level < minLevel) return                        // lazy: no string built below threshold
    val m = message()
    println("[$level/$tag] $m")                         // always-on stdout (keeps the cheap dev loop)
    sink?.invoke(level, tag, m, error)
  }
  inline fun v(tag: String, message: () -> String) = log(LogLevel.VERBOSE, tag, null, message)
  inline fun d(tag: String, message: () -> String) = log(LogLevel.DEBUG, tag, null, message)
  inline fun i(tag: String, message: () -> String) = log(LogLevel.INFO, tag, null, message)
  inline fun w(tag: String, error: Throwable? = null, message: () -> String) = log(LogLevel.WARN, tag, error, message)
  inline fun e(tag: String, error: Throwable? = null, message: () -> String) = log(LogLevel.ERROR, tag, error, message)
}
```

- **Lazy message** (`() -> String`) — below-threshold calls build no string.
- `minLevel` is a plain field the composition root sets from `SloopLogging`'s
  resolved policy; a bare `:client` (desktopTest) leaves it at VERBOSE → println.
- Migrate the ~5 `ClientLog.log(tag, msg)` sites to `Log.<level>(tag){msg}`;
  `ClientLog` is removed (its only sink consumer — the Android entrypoint — moves
  to `Log.sink`).
- **Lint:** no `Log.` call from a reducer (purity). One narrow rule; engines/UI
  are free to log.

### Layer 2 — `SloopLogging` (SWIP `swip-logging` module, hand-rolled)

Facade (in swip-core `Facades.kt`, or the swip-logging module — TBD-with-SWIP-ADR),
following the existing facade style:

```kotlin
enum class Severity { VERBOSE, DEBUG, INFO, WARN, ERROR }   // SWIP's own; Dayfold maps LogLevel→Severity
interface SloopLogging {
  fun log(severity: Severity, tag: String, message: String, error: Throwable? = null)
  fun minSeverity(): Severity                                // resolved (policy)
}
interface LogWriter { fun write(severity: Severity, tag: String, message: String, error: Throwable?) }
interface LogLevelPolicy { fun minSeverityFor(tag: String): Severity }   // global + per-tag; config seam
```

- **Runtime** (`swip-logging`, hand-rolled like swip-lifecycle): a `SloopLogging`
  impl holding a `List<LogWriter>` + a `LogLevelPolicy`; on `log()` it checks the
  policy's per-tag minSeverity, then fans out to writers whose own floor passes.
- **Platform writers** via `expect/actual`: Android `Log.println` (logcat, tag
  truncated to 23 chars), iOS `os_log`/`NSLog`, JVM `println`.
- **Wiring:** exposed on `SwipInstance` and constructed in `Swip.init`/
  `platformDeps` alongside the other facades (writers + initial policy injected).
- The **BreadcrumbWriter** forwards to the existing `SloopErrors.breadcrumb`
  (so logs enrich bug reports through the already-consented, sanitized path).

### Level control + the SloopConfig seam

- **Build-type defaults:** debug policy = VERBOSE; release policy = WARN (quiet).
- **Per-tag overrides:** `LogLevelPolicy` returns a per-tag floor (e.g. `sync`=DEBUG,
  `redux`=INFO) so noisy tags can be dialed independently.
- **Live control:** the debug drawer gets a level control (global + per-tag) that
  sets the policy at runtime → immediate effect.
- **Config seam (future, unwired now):** a `ConfigLogLevelPolicy` backed by
  `SloopConfig` keys (e.g. `log.min_severity`, `log.tag.<tag>`) resolves per
  app/user/group/version the day SWIP's config runtime ships — a drop-in
  `LogLevelPolicy` swap, no call-site change.

### The analytics-in-logs case (your motivating example)

An `AnalyticsMirrorWriter` (or a one-line hook in the swip-rk composition):
on every tracked event, `Log.d("analytics"){ event.schema + non-PII props }`.
Console visibility of analytics as they fire, at DEBUG (off in release). **No PII**
— same discipline as the mappers (schema + classified props only).

## "Right level, not noisy" — the logging convention

| Level | Use | Release? |
|---|---|---|
| ERROR | failures that lose data / block the user | yes |
| WARN | recoverable failures, retries, degraded paths | yes |
| INFO | lifecycle / nav / sync milestones, one-liners | no (debug default) |
| DEBUG | redux actions, analytics events, engine detail | no |
| VERBOSE | tight loops, payload dumps | no |

Release ships **WARN+** → near-silent. Debug ships VERBOSE with per-tag dialing.

## Privacy

Logs are **on-device only** (console/logcat/drawer/breadcrumb). The single egress
is the existing bug-report breadcrumb path — already consent-gated (ADR 0054
`user_submitted`) and run through the `dayfoldSanitizer` (drops `eyJ`/`@`). Call
sites follow the analytics no-raw-PII discipline (log ids/counts/enums, not
names/tokens/messages verbatim). A follow to the existing salted-PII leak test
covers the breadcrumb-writer path.

## Scope / sequencing (mirrors the analytics effort)

- **Phase A — SWIP repo:** the `swip-logging` module — `SloopLogging` facade +
  `LogWriter`/`LogLevelPolicy` + platform console writers + the runtime +
  `Swip.init` wiring + hermetic tests (writer fan-out, per-tag minSeverity
  filtering, lazy-gate). Requires a **SWIP ADR** (new facade/module is ADR-class
  there) + a SWIP docs entry. Publish `swip-logging`.
- **Phase B — Dayfold:** evolve `ClientLog`→`Log` (levels + lazy + sink),
  migrate call sites, remove `ClientLog`; at the composition root bind
  `SloopLogging` behind `Log.sink` with the four writers + build-type policy;
  add the debug-drawer level control; add the analytics-mirror; add a Dayfold
  ADR; seed logging into the engines (sync/auth/hub/now) at the convention above.

## Testing

- **SWIP:** writer fan-out (a fake writer records calls), per-tag minSeverity
  filtering (below-floor drops), lazy-gate (message lambda not invoked below
  threshold), platform-writer smoke.
- **Dayfold:** `Log` front-door lazy-gate + sink forwarding (evolve
  `ClientLogTest`); policy→minLevel binding; analytics-mirror emits at DEBUG only;
  extend the salted-PII leak test to the breadcrumb-writer path.

## Known unknowns (resolve in the plan)

1. Whether `SloopLogging` lives in `swip-core` `Facades.kt` or its own
   `swip-logging` module — a SWIP-ADR call (facade cohesion vs module
   granularity; swip-lifecycle precedent = its own module).
2. Exact `Swip.init`/`platformDeps` surface for injecting writers + policy
   (read the current `SwipPlatformDeps` when building Phase A).
3. iOS `os_log` vs `NSLog` choice (os_log = structured/filterable, preferred).
