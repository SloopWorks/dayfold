# SloopLogging — leveled logging facade + Dayfold front-door — design

**Date:** 2026-07-12 (rev 2, after SWIP-parity / memory / concurrency reviews) · **Branch:** `feat/sloop-logging-design`

Adds real, leveled, filterable, **PII-scrubbed** logging to Dayfold. Today the only
logger is `ClientLog` (`:client`): `log(tag, msg)` → `println` + one host sink, **no
levels, no filtering, no scrubbing**, ~5 call sites. This is a **parity port of the
existing `@sloopworks/swip-logging` (TS)** to Kotlin — a SWIP-owned `SloopLogging`
facade bound behind a thin Dayfold front-door so the shared core stays SWIP-free.

## This is a parity port, not a greenfield design

SWIP already ships **`@sloopworks/swip-logging`** (`packages/logging/src/index.ts`) —
the sanctioned logging helper for TS code, with a **built-in PII scrubber** and a
`no-direct-console` CI lint. Its contract is the baseline the Kotlin side must match:

```ts
type LogLevel = "debug" | "info" | "warn" | "error";          // 4 levels
type LogContext = Record<string, unknown>;
function log(level, message, context?): void;                  // structured; JSON.stringify({level,message,context})
function scrubString(s): string;                               // emails, /m/ tokens, high-entropy creds
function scrubObject(o): Record;                               // redacts email|token|api_key|envelope_key keys
function sanitizeError(err, context?): SanitizedError;         // scrub name/message/stack
```

**Fleet split (this spec makes it explicit):**
- **TS code** (`apps/api` backend, Cloudflare workers, any web-SDK consumer) → the
  existing **`@sloopworks/swip-logging`** (unchanged; Dayfold's API should adopt it —
  a separate small follow, noted below).
- **KMP clients** (Android / iOS / desktop / future wasmJs) → the new Kotlin
  **`SloopLogging`**, which keeps the same **core `log(level, message, context)`
  contract + the same scrubber semantics**, and adds the mobile-necessary machinery
  (platform writers, level policy, drawer control) as a **superset**.

## Resolved decisions

1. **Ownership:** `SloopLogging` is **SWIP-owned** — a new hand-rolled KMP module
   `swip-logging`, reusable across products, parity with the TS helper.
2. **Backend:** **hand-rolled native writers** (expect/actual: Logcat / os_log /
   println), zero third-party dep. Not Kermit.
3. **Levels:** **`debug | info | warn | error`** (4, parity with TS — **no VERBOSE**).
4. **Shape:** structured — `log(level, tag, message, context?, error?)`. `tag` is
   kept as a first-class field (mobile logcat/os_log are tag-oriented) and also
   emitted into the structured line so mobile logs parse like the TS ones.
5. **PII scrubbing is built in** (ported from the TS helper) — runs in the facade
   **after the level gate, before any writer**. Same regexes/semantics.
6. **Level control:** **local-first** (build-type default + per-tag overrides +
   live drawer toggle) via a **copy-on-write `LogLevelPolicy`**; a future
   `SloopConfig`-backed policy drives per app/user/group/version (config runtime
   deferred in SWIP — seam only for now).
7. **Boundary:** **two layers** — a thin Dayfold-owned `Log` front-door in `:client`
   (zero SWIP dep) with `SloopLogging` bound behind its sink at the composition root.
   Keeps `:client` independently buildable/testable without the SWIP toolchain.
8. **Egress:** **on-device only.** No log leaves the device except via the existing
   consented, sanitized bug-report breadcrumb path.
9. **Fan-out is synchronous** (no async log pipeline — see Concurrency/Memory).

## Architecture — two layers, corrected emit + gate model

```
:client engines / composition root
        │  Log.d("sync") { "401 — refreshing" }        (front-door, no SWIP import)
        ▼
   Log  (Dayfold-owned, :client, evolves ClientLog)
        │  coarse global floor gate (cheap, lazy) → sink, ELSE println fallback
        ▼
   SloopLogging  (SWIP-owned, swip-logging) — bound behind Log.sink at the composition root
        │  1. per-tag min-severity (copy-on-write policy)   2. PII scrub   3. sync fan-out
        ├─ PlatformConsoleWriter   (android.util.Log / os_log(%{public}s) / println)
        ├─ DebugDrawerWriter       (→ DebugLog.record(level,tag,msg); LogBuffer already leveled+bounded)
        └─ BreadcrumbWriter        (→ the bug-report crumb ring; on-device, consented)
   AnalyticsMirror = a HOOK in the swip-rk composition (on track → Log.d), NOT a writer.
```

### Layer 1 — `Log` front-door (`:client`, Dayfold-owned, no SWIP dep)

```kotlin
enum class LogLevel { DEBUG, INFO, WARN, ERROR }   // ordered; parity with TS (no VERBOSE)

object Log {
  @Volatile var sink: ((level: LogLevel, tag: String, message: String, context: Map<String, Any?>?, error: Throwable?) -> Unit)? = null
  @Volatile var minLevel: LogLevel = LogLevel.DEBUG   // COARSE global floor (= min across per-tag floors)

  inline fun log(level: LogLevel, tag: String, error: Throwable? = null, context: Map<String, Any?>? = null, message: () -> String) {
    if (level < minLevel) return                      // lazy gate BEFORE building the message/using context
    val m = message()
    val s = sink
    if (s != null) s(level, tag, m, context, error)   // bound app → SloopLogging owns ALL output + fine filtering
    else println("[$level/$tag] $m")                  // fallback ONLY when unbound (bare :client / desktopTest / pre-init)
  }
  inline fun d(tag: String, message: () -> String) = log(LogLevel.DEBUG, tag, message = message)
  inline fun i(tag: String, message: () -> String) = log(LogLevel.INFO, tag, message = message)
  inline fun w(tag: String, error: Throwable? = null, message: () -> String) = log(LogLevel.WARN, tag, error, message = message)
  inline fun e(tag: String, error: Throwable? = null, message: () -> String) = log(LogLevel.ERROR, tag, error, message = message)
}
```

- **Emit model (fixes the double-emit):** the front-door prints **only when no sink
  is bound**. In the running app a sink is bound → `SloopLogging` is the *sole*
  output + filter; no duplicate logcat line. Bare `:client`/desktopTest/pre-init →
  stdout fallback (keeps the cheap dev loop, no crash).
- **Two-tier gate:** the front-door holds only a **coarse global floor** (= the
  minimum across all per-tag floors) for a cheap `inline` lazy short-circuit;
  **fine per-tag filtering lives in `SloopLogging`** so it can never be bypassed by
  the front-door. The composition root recomputes `Log.minLevel` whenever the policy
  changes.
- **`context: Map` and the lazy gate (memory decision):** `context` is evaluated
  **eagerly** at the call site, so it defeats the lazy gate. Rule: **`context` is for
  `INFO`+ only** (diagnostics, not hot paths); hot `DEBUG`/redux/verbose logging uses
  the message lambda alone (which is fully lazy). Documented in the convention.
- Migrate the ~5 `ClientLog.log` sites to `Log.<level>`; remove `ClientLog`.
- **Lints (parity with TS `no-direct-console`):** a detekt rule forbidding raw
  `println` / `android.util.Log` outside the logging facade, and no `Log.` from a
  reducer (purity).

### Layer 2 — `SloopLogging` (SWIP `swip-logging`, hand-rolled)

```kotlin
enum class Severity { DEBUG, INFO, WARN, ERROR }               // parity with TS
interface LogWriter { fun write(severity: Severity, tag: String, message: String, context: Map<String, Any?>?, error: Throwable?) }  // MUST be thread-safe
interface LogLevelPolicy { fun minSeverityFor(tag: String): Severity }                  // immutable snapshot

interface SloopLogging {
  fun log(severity: Severity, tag: String, message: String, context: Map<String, Any?>? = null, error: Throwable? = null)
  fun minSeverity(): Severity                                  // coarse floor (for the front-door)
  fun setPolicy(policy: LogLevelPolicy)                        // copy-on-write swap
}
```

- **Runtime** (hand-rolled like swip-lifecycle): holds an **immutable `List<LogWriter>`**
  (built once, never mutated → safe concurrent iteration) and a **`@Volatile`/atomic
  reference to an immutable `LogLevelPolicy`**. `log()` = per-tag gate (policy load) →
  **scrub** (message + context + error) → **synchronous** fan-out to writers.
- **Scrubber (ported from the TS helper, parity + privacy):** `scrubString`
  (emails → `[redacted-email]`, `/m/{22,}` → `[redacted-token]`, high-entropy `{40,}`
  → `[redacted-token]`), `scrubObject` (redact `email|token|api_key|envelope_key`
  keys), `sanitizeError` (name/message/stack). **Regexes are top-level compiled `val`s**
  (compile once). Runs **after** the level gate (below-threshold lines pay nothing).
- **Platform writers** (`expect/actual`): Android `android.util.Log.println(priority,
  tag, msg)` (tag ≤ 23 chars, truncate; **not** `System.out`); iOS `os_log` with
  **`%{public}s`** (safe — already scrubbed; without `{public}` Console.app shows
  `<private>`); JVM `println`. K/N synchronization uses `kotlinx.atomicfu.locks` /
  `@Volatile` — **never** `@Synchronized` (JVM-only).
- **Wiring:** exposed on `SwipInstance` (2nd facade with a real runtime, after
  analytics) and built in `Swip.init`/`platformDeps` with the writer list + initial
  policy. **Safe publication:** fully constructed on the init thread, then published
  via the `@Volatile Log.sink` write (happens-before for all reader threads).

### Level control + the SloopConfig seam

- Build-type defaults: debug policy floor = `DEBUG`; release = `WARN` (near-silent).
- Per-tag overrides via `LogLevelPolicy` (e.g. `sync`=DEBUG, `redux`=INFO).
- **Live control = copy-on-write:** the drawer toggle builds a **new immutable
  policy** and calls `setPolicy` (`@Volatile` reference store) — it **never mutates a
  shared map in place** (that would race/CME against concurrent readers). Readers do a
  volatile load of the current immutable policy. The composition root also recomputes
  `Log.minLevel` (the coarse front-door floor) on each swap.
- **Config seam (future, unwired):** a `ConfigLogLevelPolicy` backed by `SloopConfig`
  keys resolves per app/user/group/version the day SWIP's config runtime ships — a
  drop-in `LogLevelPolicy`, no call-site change.

### The analytics-in-logs case — a hook, not a writer

On every swip-rk-tracked event, a **hook in the swip-rk composition** calls
`Log.d("analytics") { event.schema + non-PII props }`. It is a **producer**, not a
`LogWriter`, so it never re-enters the writer fan-out (no re-entrancy/loop). DEBUG →
off in release. No PII (schema + classified props, same discipline as the mappers).

## Concurrency (logs arrive from IO / main / background-receiver threads)

- **Front-door:** `@Volatile var sink/minLevel` (KMP `kotlin.concurrent.Volatile`,
  as `ClientLog` already uses). Write-once at composition root, read-many; the two
  fields aren't invariant-coupled → volatile visibility suffices.
- **Policy:** copy-on-write, immutable-snapshot swap via `@Volatile`/atomic ref
  (above). No in-place mutation of a shared map.
- **Writers:** `LogWriter.write` is a **thread-safety contract** — may be called
  concurrently. Verified-safe existing sinks: `LogBuffer` (`atomicfu.locks`,
  eviction inside the lock), the bug-report crumb ring (`synchronized`), logcat /
  os_log / println (platform-safe). The writer **list is immutable after
  construction** (safe concurrent iteration).
- **Safe publication** at init via the volatile sink store; nothing mutates writers
  post-publication; pre-init logs (`sink == null`) fall to `println`.
- **No writer logs through the facade** (avoids re-entrancy; analytics-mirror is a
  hook, not a writer). Add a depth-guard only if a future writer must log.
- **Log-lambda discipline:** lambdas capture an **immutable snapshot** (the action, a
  `val`), never re-read live shared mutable state off-thread. (The Reducer log already
  captures `s = store.state`.) Framework can't enforce — documented.
- **Ordering** is nondeterministic across threads but **per-line atomic** (LogBuffer
  `seq` preserves arrival order; logcat lines atomic). Don't rely on global order.

## Memory & performance (mobile)

- **Lazy `inline` gate is the core win** — below-threshold calls allocate nothing
  (no lambda object, no string). Release (WARN+) → `DEBUG`/`DEBUG`-tagged sites
  short-circuit → zero heap. Preserve.
- **`context` map allocates eagerly** → restrict to `INFO`+ (rule above); hot paths
  use the lazy lambda only.
- **Scrubber:** static top-level regexes (compile once) + scrub **after** the gate →
  cost confined to emitted lines.
- **Synchronous fan-out — no `Channel`/coroutine/queue log pipeline** (that would add
  per-log element allocation + a retaining buffer + backpressure for no benefit;
  local console writes are fast). Zero steady-state heap beyond the message string.
- **Retained buffers are bounded:** drawer `LogBuffer` (capacity + evict-oldest,
  verified) and the 32-cap breadcrumb ring — fixed ceilings, debug-only.
- **Release footprint trade:** `minLevel` is a runtime `var` (for live control) →
  R8/ProGuard can't strip below-floor calls; the inlined message-building code ships
  dead-but-present (trivial code-size, **zero heap** — gate short-circuits). Keeping
  runtime control is worth it; a stripped variant would kill live level control.
- **Release has no SloopLogging runtime bound** (zero swip bytes, above) → `Log.sink`
  is null and every WARN+/ERROR line falls through to the front-door's own `println`
  fallback, which runs **before** the scrub boundary and is therefore **unscrubbed**.
  Release's `installLogging` floors `Log.minLevel = WARN` so at least DEBUG/INFO
  volume doesn't ship; the scrubber itself is debug-only wiring. The control in
  release is call-site discipline (ids/counts/enums, never raw tokens/emails/names),
  not the scrubber — see Privacy below.
- `sanitizeError` calls `stackTraceToString()` (large alloc) — WARN/ERROR only
  (rare); never capture stacks on `DEBUG` lines.

## Privacy

- **On-device only** (console/logcat/drawer/breadcrumb). Single egress = the existing
  consented, sanitized bug-report breadcrumb path (ADR 0054 `user_submitted`).
- **Scrubbing at the log boundary** (the ported TS scrubber) is the primary control —
  strictly stronger than the old `dayfoldSanitizer` (`eyJ`/`@`) and applied to *every*
  emitted line, not just breadcrumbs. Call-site discipline (log ids/counts/enums, not
  names/tokens verbatim) is defense-in-depth.
- **This scrubber is debug-only.** It lives inside SloopLogging, and release never
  binds `Log.sink` (zero swip bytes) — so a release build has no scrub boundary at
  all. Release logs are `WARN`+ via the front-door's own `println` fallback,
  **unscrubbed**, on-device (logcat) only. Do not claim release logs are scrubbed;
  the only control there is call-site no-PII discipline plus the `WARN` floor.
- Extend the salted-PII leak test to the scrubber + breadcrumb-writer path.

## "Right level, not noisy" convention

| Level | Use | Release? | `context`? |
|---|---|---|---|
| ERROR | failures that lose data / block the user | yes | ok |
| WARN | recoverable failures, retries, degraded paths | yes | ok |
| INFO | lifecycle / nav / sync milestones (one-liners) | no | ok |
| DEBUG | redux actions, analytics events, engine detail | no | **avoid** (eager alloc) |

Release ships `WARN`+ → near-silent. Debug ships `DEBUG` with per-tag dialing.

## Scope / sequencing (trimmed slice 1)

- **Phase A — SWIP repo (needs a SWIP ADR — new facade/module is ADR-class there):**
  `swip-logging` module — `SloopLogging` + `LogWriter`/`LogLevelPolicy` + the ported
  **scrubber** + platform console writers + build-type policy + `Swip.init` wiring +
  hermetic tests. **Slice 1 defers** the `SloopConfig` seam (already deferred) and
  keeps the machinery minimal. Publish `swip-logging`.
- **Phase B — Dayfold:** evolve `ClientLog`→`Log` (levels + lazy + sink-fallback +
  `context`), migrate the ~5 sites, remove `ClientLog`; at the composition root bind
  `SloopLogging` behind `Log.sink` with the writers + build-type policy; the
  **DebugDrawerWriter** maps `Severity`→the existing `DebugLog.LogLevel{D,I,W,E}` (the
  drawer is **already leveled + bounded** — just pass the level through; the per-tag
  drawer *toggle UI* is a **follow**, not slice 1); add the analytics-mirror hook; add
  the detekt lint; add a Dayfold ADR; seed logging into the engines at the convention.
- **Follow (separate, small):** point `apps/api` (TS) at the existing
  `@sloopworks/swip-logging` instead of raw `console.*`.

## Testing

- **SWIP:** writer fan-out (fake writer records calls); per-tag min-severity
  filtering (below-floor drops); **lazy-gate** (message lambda not invoked below
  threshold); **scrubber** parity vectors (emails / `/m/` tokens / high-entropy /
  PII-keys / `sanitizeError`) matching the TS golden cases; **copy-on-write policy**
  under concurrent read + swap (no CME); platform-writer smoke.
- **Dayfold:** `Log` lazy-gate + **sink-vs-println fallback** + `context` forwarding
  (evolve `ClientLogTest`); policy→`minLevel` recompute; analytics-mirror emits at
  DEBUG only; extend the salted-PII leak test to the scrubber/breadcrumb path.

## Known unknowns (resolve in the plan)

1. `SloopLogging` in `swip-core` `Facades.kt` vs its own `swip-logging` module — a
   SWIP-ADR call (swip-lifecycle precedent = own module).
2. Exact `Swip.init`/`platformDeps` surface for injecting writers + policy (read the
   current `SwipPlatformDeps` when building Phase A).
3. Whether the Kotlin scrubber should share golden fixtures with the TS scrubber
   (recommended — byte-parity like SWIP's other dual-SDK components).
