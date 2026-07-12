# SloopLogging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add leveled, PII-scrubbed, filterable logging to Dayfold — a SWIP-owned `SloopLogging` KMP facade (parity port of the TS `@sloopworks/swip-logging`) bound behind a thin Dayfold `Log` front-door so `:client` stays SWIP-free.

**Architecture:** **Phase A** (SWIP repo): a new hand-rolled `swip-logging` KMP module — facade + ported scrubber + copy-on-write level policy + platform console writers + `Swip.init` wiring; publish `0.1.0`. **Phase B** (Dayfold): evolve `ClientLog`→`Log` (levels, lazy, sink-fallback, context), bind `SloopLogging` at the composition root with the writers, add the analytics-mirror hook + detekt lint, seed engine logging.

**Tech Stack:** Kotlin 2.3.20 / KMP, `kotlinx.atomicfu.locks` + `kotlin.concurrent.Volatile` (KMP-portable sync), expect/actual platform writers (logcat / os_log / println), JDK17, GitHub Packages.

**Design source:** `docs/superpowers/specs/2026-07-12-sloop-logging-design.md`. **TS parity source:** `~/workspace/sloopworksinstrumentationplatform/packages/logging/src/index.ts`.

## Global Constraints

- **Levels = `debug | info | warn | error`** (4, parity with the TS helper — **no VERBOSE**). Kotlin enum `Severity { DEBUG, INFO, WARN, ERROR }` (SWIP) and `LogLevel { DEBUG, INFO, WARN, ERROR }` (Dayfold front-door, SWIP-free).
- **Core contract = `log(level, tag, message, context?, error?)`** with structured `context: Map<String, Any?>?`.
- **PII scrubber ported verbatim** from the TS helper, runs **after the level gate, before any writer**. Regexes are **top-level compiled `val`s**.
- **`:client` imports no `works.sloop.swip`** — logging goes through the Dayfold `Log` front-door; `SloopLogging` binds behind its sink at the composition root.
- **Synchronous fan-out** — no `Channel`/coroutine/queue log pipeline.
- **Copy-on-write policy** — runtime level changes swap an immutable `LogLevelPolicy` via a `@Volatile`/atomic reference; never mutate a shared map in place.
- **Writers are thread-safe; the writer list is immutable after construction.** No writer logs through the facade (analytics-mirror is a hook, not a writer).
- **KMP-portable sync only** — `kotlinx.atomicfu.locks` / `kotlin.concurrent.Volatile`; never `@Synchronized`.
- **On-device only** egress. `context` used at `INFO`+ only (eager alloc defeats the lazy gate). TDD, frequent commits.
- **Repos:** SWIP `~/workspace/sloopworksinstrumentationplatform` (branch off `origin/main`, pull first); Dayfold `~/workspace/dayfold` (branch `feat/sloop-logging-design`). Dayfold gradle needs `GITHUB_ACTOR=patjackson52 SLOOPWORKS_PACKAGES_TOKEN=$(gh auth token)` prefix, run from `apps/`.
- **A6 (SWIP ADR + PR merge + publish) and B5 (Dayfold ADR + PR merge) are OPERATOR-gated.** Phase B is blocked on the `swip-logging:0.1.0` publish.

---

# Phase A — SWIP repo: the `swip-logging` module

Pull first: `cd ~/workspace/sloopworksinstrumentationplatform && git checkout main && git pull origin main && git checkout -b swip-logging`.

### Task A1: Facade interfaces (in swip-core) + module scaffold

The facade **interfaces live in `swip-core` `Facades.kt`** (mirrors `SloopAnalytics`/`SloopErrors` — facades exposed on `SwipInstance` have their interface in core), so both core (`SwipInstance`/`SwipPlatformDeps`) and the `:swip-logging` module can reference them. The **runtime + scrubber + platform writers live in `:swip-logging`** (depends on `:swip-core`).

**Files:**
- Modify: `sdk-kmp/swip-core/src/commonMain/kotlin/works/sloop/swip/Facades.kt` (add the interfaces + `NoOpLogging`)
- Create: `sdk-kmp/swip-logging/build.gradle.kts`
- Modify: `sdk-kmp/settings.gradle.kts` (add `include(":swip-logging")`)
- Create: `sdk-kmp/swip-logging/src/commonMain/kotlin/works/sloop/swip/logging/.gitkeep` (placeholder; real files land A2–A4)

**Interfaces:**
- Produces (in `works.sloop.swip`): `enum Severity { DEBUG, INFO, WARN, ERROR }`; `interface SloopLogging { fun log(severity, tag, message, context?, error?); fun minSeverity(): Severity; fun setPolicy(policy) }`; `interface LogWriter { fun write(severity, tag, message, context, error) }`; `interface LogLevelPolicy { fun minSeverityFor(tag: String): Severity }`; `object NoOpLogging : SloopLogging`.

- [ ] **Step 1: Add the interfaces to `swip-core` `Facades.kt`** (package `works.sloop.swip`, beside the other `Sloop*` facades):

```kotlin
/** Parity with @sloopworks/swip-logging LogLevel ("debug"|"info"|"warn"|"error"). Ordered. */
enum class Severity { DEBUG, INFO, WARN, ERROR }

/** A destination for emitted, already-scrubbed log lines. MUST be thread-safe (called concurrently). */
interface LogWriter {
  fun write(severity: Severity, tag: String, message: String, context: Map<String, Any?>?, error: Throwable?)
}

/** Immutable per-tag minimum severity. Swapped copy-on-write; never mutated in place. */
interface LogLevelPolicy { fun minSeverityFor(tag: String): Severity }

interface SloopLogging {
  fun log(severity: Severity, tag: String, message: String, context: Map<String, Any?>? = null, error: Throwable? = null)
  /** Coarse floor = min across all per-tag floors; drives the Dayfold front-door's cheap gate. */
  fun minSeverity(): Severity
  /** Copy-on-write: replaces the whole policy (build a new immutable one; @Volatile store). */
  fun setPolicy(policy: LogLevelPolicy)
}

/** Default when a product wires no logging (parity with the other NoOp facades). */
object NoOpLogging : SloopLogging {
  override fun log(severity: Severity, tag: String, message: String, context: Map<String, Any?>?, error: Throwable?) {}
  override fun minSeverity(): Severity = Severity.ERROR
  override fun setPolicy(policy: LogLevelPolicy) {}
}
```

- [ ] **Step 2: Create the module `build.gradle.kts`** — copy `sdk-kmp/swip-lifecycle/build.gradle.kts` verbatim, then change only: the top comment; `android { namespace = "works.sloop.swip.logging" }`; drop the `androidMain.dependencies { lifecycle-process }` block. Keep `api(project(":swip-core"))`, the serialization plugin/dep, all 4 targets, `maven-publish`, and the `publishing` block.

- [ ] **Step 3: Add the module to the build** — in `sdk-kmp/settings.gradle.kts`, add `include(":swip-logging")` after `:swip-rk`. Create the `src/commonMain/kotlin/works/sloop/swip/logging/` dir with a `.gitkeep`.

- [ ] **Step 4: Verify it compiles.** `cd sdk-kmp && ./gradlew :swip-core:desktopTest :swip-logging:compileKotlinDesktop` → BUILD SUCCESSFUL (existing swip-core tests unaffected — additive interfaces).

- [ ] **Step 5: Commit.**

```bash
git add sdk-kmp/swip-core/src/commonMain/kotlin/works/sloop/swip/Facades.kt sdk-kmp/swip-logging/build.gradle.kts sdk-kmp/settings.gradle.kts sdk-kmp/swip-logging/src/
git commit -m "feat(swip-core): SloopLogging facade interfaces + NoOpLogging; scaffold :swip-logging module"
```

### Task A2: PII scrubber (parity port of the TS helper)

**Files:**
- Create: `sdk-kmp/swip-logging/src/commonMain/kotlin/works/sloop/swip/logging/Scrub.kt`
- Test: `sdk-kmp/swip-logging/src/desktopTest/kotlin/works/sloop/swip/logging/ScrubTest.kt`

**Interfaces:**
- Produces: `fun scrubString(value: String): String`; `fun scrubObject(obj: Map<String, Any?>): Map<String, Any?>`; `data class SanitizedError(name, message, stack?, context?)`; `fun sanitizeError(err: Throwable, context: Map<String, Any?>? = null): SanitizedError`.

- [ ] **Step 1: Read** the TS source `~/workspace/sloopworksinstrumentationplatform/packages/logging/src/index.ts` (already in this repo you're in) and `packages/logging/test/scrubber.test.ts` — port the regexes + behavior **exactly**.

- [ ] **Step 2: Write failing tests** `ScrubTest.kt` mirroring the TS vectors:

```kotlin
package works.sloop.swip.logging
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrubTest {
  @Test fun scrubs_email() = assertEquals("hi [redacted-email]", scrubString("hi bob@example.com"))
  @Test fun scrubs_mgmt_link() = assertEquals("see /m/[redacted-token]", scrubString("see /m/${"a".repeat(22)}"))
  @Test fun scrubs_high_entropy() = assertEquals("[redacted-token]", scrubString("A".repeat(43)))
  @Test fun keeps_uuid_and_short_ids() {
    val uuid = "123e4567-e89b-12d3-a456-426614174000"       // 36 chars — under the 40 floor
    assertTrue(uuid in scrubString("id=$uuid"))
  }
  @Test fun redacts_pii_keyed_fields() {
    val out = scrubObject(mapOf("user_email" to "x@y.z", "reset_token" to "abc", "count" to 3))
    assertEquals("[redacted]", out["user_email"]); assertEquals("[redacted]", out["reset_token"]); assertEquals(3, out["count"])
  }
  @Test fun scrubs_nested_and_arrays() {
    val out = scrubObject(mapOf("note" to "mail me at a@b.co", "list" to listOf("c@d.co")))
    assertEquals("mail me at [redacted-email]", out["note"])
    assertEquals(listOf("[redacted-email]"), out["list"])
  }
  @Test fun sanitize_error_scrubs_message() {
    val e = sanitizeError(IllegalStateException("boom a@b.co"))
    assertEquals("boom [redacted-email]", e.message); assertEquals("IllegalStateException", e.name)
  }
}
```

- [ ] **Step 3: Run — verify fail.** `./gradlew :swip-logging:desktopTest --tests '*ScrubTest'` → FAIL (unresolved).

- [ ] **Step 4: Implement** `Scrub.kt` (top-level compiled regexes; order MGMT→EMAIL→HIGH_ENTROPY as in TS):

```kotlin
package works.sloop.swip.logging

private val PII_KEY_RE = Regex("(email|token|api_key|envelope_key)", RegexOption.IGNORE_CASE)
private val EMAIL_RE = Regex("""\S+@\S+\.\S+""")
private val MGMT_LINK_RE = Regex("""/m/[A-Za-z0-9_-]{22,}""")
private val HIGH_ENTROPY_RE = Regex("""[A-Za-z0-9_-]{40,}""")
private const val REDACTED = "[redacted]"

fun scrubString(value: String): String = value
  .replace(MGMT_LINK_RE, "/m/[redacted-token]")
  .replace(EMAIL_RE, "[redacted-email]")
  .replace(HIGH_ENTROPY_RE, "[redacted-token]")

private fun scrubValue(value: Any?): Any? = when (value) {
  is String -> scrubString(value)
  is List<*> -> value.map { scrubValue(it) }
  is Map<*, *> -> @Suppress("UNCHECKED_CAST") scrubObject(value as Map<String, Any?>)
  else -> value
}

fun scrubObject(obj: Map<String, Any?>): Map<String, Any?> =
  obj.mapValues { (k, v) -> if (PII_KEY_RE.containsMatchIn(k)) REDACTED else scrubValue(v) }

data class SanitizedError(val name: String, val message: String, val stack: String? = null, val context: Map<String, Any?>? = null)

fun sanitizeError(err: Throwable, context: Map<String, Any?>? = null): SanitizedError = SanitizedError(
  name = err::class.simpleName ?: "Throwable",
  message = scrubString(err.message ?: ""),
  stack = err.stackTraceToString().let { scrubString(it) },   // WARN/ERROR path only
  context = context?.let { scrubObject(it) },
)
```

- [ ] **Step 5: Run — verify pass.** Same command → PASS.

- [ ] **Step 6: Commit.**

```bash
git add sdk-kmp/swip-logging/src/commonMain/kotlin/works/sloop/swip/logging/Scrub.kt sdk-kmp/swip-logging/src/desktopTest/kotlin/works/sloop/swip/logging/ScrubTest.kt
git commit -m "feat(swip-logging): PII scrubber (scrubString/scrubObject/sanitizeError) — parity with TS"
```

### Task A3: The `SloopLogging` runtime (gate → scrub → sync fan-out, copy-on-write policy)

**Files:**
- Create: `sdk-kmp/swip-logging/src/commonMain/kotlin/works/sloop/swip/logging/Runtime.kt`
- Test: `sdk-kmp/swip-logging/src/desktopTest/kotlin/works/sloop/swip/logging/RuntimeTest.kt`

**Interfaces:**
- Consumes: `SloopLogging`/`LogWriter`/`LogLevelPolicy`/`Severity` (A1), `scrubString`/`scrubObject`/`sanitizeError` (A2).
- Produces: `class DefaultSloopLogging(writers: List<LogWriter>, initialPolicy: LogLevelPolicy) : SloopLogging`; `class FixedPolicy(private val floors: Map<String, Severity>, private val default: Severity) : LogLevelPolicy`.

- [ ] **Step 1: Write failing tests** `RuntimeTest.kt`:

```kotlin
package works.sloop.swip.logging
import kotlin.test.*

class RuntimeTest {
  private class Rec : LogWriter {
    val lines = mutableListOf<String>()
    override fun write(severity: Severity, tag: String, message: String, context: Map<String, Any?>?, error: Throwable?) {
      lines.add("$severity/$tag/$message")
    }
  }
  @Test fun below_per_tag_floor_is_dropped() {
    val w = Rec()
    val log = DefaultSloopLogging(listOf(w), FixedPolicy(mapOf("noisy" to Severity.WARN), Severity.DEBUG))
    log.log(Severity.DEBUG, "noisy", "x"); log.log(Severity.WARN, "noisy", "y"); log.log(Severity.DEBUG, "other", "z")
    assertEquals(listOf("WARN/noisy/y", "DEBUG/other/z"), w.lines)
  }
  @Test fun scrubs_before_writer() {
    val w = Rec()
    val log = DefaultSloopLogging(listOf(w), FixedPolicy(emptyMap(), Severity.DEBUG))
    log.log(Severity.INFO, "t", "mail a@b.co")
    assertEquals(listOf("INFO/t/mail [redacted-email]"), w.lines)
  }
  @Test fun min_severity_is_coarse_floor() {
    val log = DefaultSloopLogging(emptyList(), FixedPolicy(mapOf("a" to Severity.WARN, "b" to Severity.INFO), Severity.ERROR))
    assertEquals(Severity.INFO, log.minSeverity())   // min across floors incl. default
  }
  @Test fun setPolicy_swaps_atomically() {
    val w = Rec()
    val log = DefaultSloopLogging(listOf(w), FixedPolicy(emptyMap(), Severity.ERROR))
    log.log(Severity.INFO, "t", "before")            // dropped (floor ERROR)
    log.setPolicy(FixedPolicy(emptyMap(), Severity.DEBUG))
    log.log(Severity.INFO, "t", "after")             // passes
    assertEquals(listOf("INFO/t/after"), w.lines)
  }
  @Test fun fan_out_to_all_writers() {
    val a = Rec(); val b = Rec()
    DefaultSloopLogging(listOf(a, b), FixedPolicy(emptyMap(), Severity.DEBUG)).log(Severity.INFO, "t", "m")
    assertEquals(1, a.lines.size); assertEquals(1, b.lines.size)
  }
}
```

- [ ] **Step 2: Run — verify fail.** `./gradlew :swip-logging:desktopTest --tests '*RuntimeTest'` → FAIL.

- [ ] **Step 3: Implement** `Runtime.kt`:

```kotlin
package works.sloop.swip.logging
import kotlin.concurrent.Volatile

/** Immutable per-tag floors + a default. */
class FixedPolicy(private val floors: Map<String, Severity>, private val default: Severity) : LogLevelPolicy {
  override fun minSeverityFor(tag: String): Severity = floors[tag] ?: default
  internal fun coarseFloor(): Severity = (floors.values + default).minBy { it.ordinal }
}

/**
 * Writers are captured as an immutable list (safe concurrent iteration). The policy is a
 * @Volatile immutable reference, swapped copy-on-write (no in-place mutation). log() runs on
 * the CALLING thread: per-tag gate → scrub → synchronous fan-out. No queue/coroutine.
 */
class DefaultSloopLogging(writers: List<LogWriter>, initialPolicy: LogLevelPolicy) : SloopLogging {
  private val writers: List<LogWriter> = writers.toList()   // immutable snapshot
  @Volatile private var policy: LogLevelPolicy = initialPolicy

  override fun log(severity: Severity, tag: String, message: String, context: Map<String, Any?>?, error: Throwable?) {
    if (severity.ordinal < policy.minSeverityFor(tag).ordinal) return
    val m = scrubString(message)
    val c = context?.let { scrubObject(it) }
    for (w in writers) w.write(severity, tag, m, c, error)   // each writer is thread-safe by contract
  }
  override fun minSeverity(): Severity {
    val p = policy
    return if (p is FixedPolicy) p.coarseFloor() else Severity.DEBUG   // unknown policy → most permissive
  }
  override fun setPolicy(policy: LogLevelPolicy) { this.policy = policy }
}
```

- [ ] **Step 4: Run — verify pass.** Same command → PASS.

- [ ] **Step 5: Concurrency test** — add `@Test fun concurrent_log_and_policy_swap_no_crash()` that spawns threads logging while another calls `setPolicy` in a loop (use `kotlin.concurrent.thread` on desktop), asserting no exception + all above-floor lines land. Run → PASS.

- [ ] **Step 6: Commit.**

```bash
git add sdk-kmp/swip-logging/src/commonMain/kotlin/works/sloop/swip/logging/Runtime.kt sdk-kmp/swip-logging/src/desktopTest/kotlin/works/sloop/swip/logging/RuntimeTest.kt
git commit -m "feat(swip-logging): DefaultSloopLogging runtime — gate→scrub→sync fan-out, copy-on-write policy"
```

### Task A4: Platform console writers (expect/actual)

**Files:**
- Create: `.../commonMain/.../logging/PlatformConsole.kt` (expect), `androidMain`, `desktopMain`, `iosMain` actuals.
- Test: `.../desktopTest/.../logging/PlatformConsoleTest.kt`

**Interfaces:**
- Produces: `expect fun platformConsoleWriter(): LogWriter` in `works.sloop.swip.logging`.

- [ ] **Step 1: Write the desktop test** (the only hermetically testable target — captures stdout):

```kotlin
@Test fun desktop_writer_prints_line() {
  val out = java.io.ByteArrayOutputStream(); val prev = System.out
  System.setOut(java.io.PrintStream(out))
  try { platformConsoleWriter().write(Severity.INFO, "tag", "hello", null, null) } finally { System.setOut(prev) }
  assertTrue("hello" in out.toString() && "tag" in out.toString())
}
```

- [ ] **Step 2: Run — verify fail.** `./gradlew :swip-logging:desktopTest --tests '*PlatformConsoleTest'` → FAIL.

- [ ] **Step 3: Implement.**
  - `commonMain/PlatformConsole.kt`: `expect fun platformConsoleWriter(): LogWriter`
  - `desktopMain`: `actual fun platformConsoleWriter(): LogWriter = object : LogWriter { override fun write(severity, tag, message, context, error) { println("[$severity/$tag] $message"); error?.let { println(it.stackTraceToString()) } } }`
  - `androidMain`: map `Severity`→`android.util.Log` priority (DEBUG→Log.DEBUG, INFO→INFO, WARN→WARN, ERROR→ERROR); `android.util.Log.println(priority, tag.take(23), message)`; on error, `android.util.Log.println(priority, tag.take(23), error.stackTraceToString())`. Tag truncated to 23.
  - `iosMain`: use `platform.darwin.os_log` / `_os_log_impl` is awkward from K/N — use `platform.Foundation.NSLog("%{public}@", "[$severity/$tag] $message")` (simplest, thread-safe; message already scrubbed). Include the `%{public}@` format so it's not redacted.

- [ ] **Step 4: Run — verify pass** (desktop) + compile all targets: `./gradlew :swip-logging:compileKotlinDesktop :swip-logging:compileKotlinIosArm64 :swip-logging:compileDebugKotlinAndroid` → green.

- [ ] **Step 5: Commit.**

```bash
git add sdk-kmp/swip-logging/src/
git commit -m "feat(swip-logging): platform console writers (logcat / NSLog %{public} / println)"
```

### Task A5: Expose `logging` on `SwipInstance` (injected)

The `DefaultSloopLogging` runtime lives in `:swip-logging` (which depends on core), so core cannot construct it — expose `logging` on `SwipInstance` as an **injected** dependency: add `logging: SloopLogging = NoOpLogging` to `SwipPlatformDeps`, and `SwipInstance.logging = deps.logging`. The composition root (Dayfold) constructs `DefaultSloopLogging(...)` and passes it in. Interfaces already in core (A1) → no import problem.

**Files:**
- Modify: `sdk-kmp/swip-core/src/commonMain/kotlin/works/sloop/swip/Swip.kt` — **read it first; mirror how `analytics` is threaded through `SwipPlatformDeps` → `Swip.init` → `SwipInstance`.**

- [ ] **Step 1: Read** `Swip.kt` — locate `data class SwipPlatformDeps`, `class SwipInstance`, `object Swip { fun init(...) }`, and how `analytics` flows.
- [ ] **Step 2: Add** `val logging: SloopLogging = NoOpLogging` to `SwipPlatformDeps` (default keeps every existing caller compiling).
- [ ] **Step 3: Add** `val logging: SloopLogging` to `SwipInstance` (constructor param + public val), sourced from `deps.logging` in `Swip.init`, mirroring `analytics`.
- [ ] **Step 4: Compile** `./gradlew :swip-core:desktopTest` → green (existing tests unaffected — additive with a NoOp default).
- [ ] **Step 5: Commit.**

```bash
git add sdk-kmp/swip-core/src/commonMain/kotlin/works/sloop/swip/Swip.kt
git commit -m "feat(swip-core): SwipInstance.logging (injected SloopLogging, NoOp default)"
```

### Task A6: SWIP ADR + version + PR + publish (operator-gated)

- [ ] **Step 1: Write a SWIP ADR** (next number in `~/workspace/sloopworksinstrumentationplatform/adr/`) for the `SloopLogging` facade + `swip-logging` module: parity with `@sloopworks/swip-logging` (4 levels, structured context, ported scrubber), interfaces-in-core/runtime-in-module, hand-rolled writers, copy-on-write policy, synchronous fan-out, on-device-only. Add a `docs/` note if the repo convention needs one (mirror how swip-lifecycle was documented).
- [ ] **Step 2: Add `:swip-logging` to `publish-kmp.yml`** default modules template (mirror the `:swip-rk` entry).
- [ ] **Step 3: PR** to SWIP `main`; poll both CI lanes green; **merge is the operator's call.**
- [ ] **Step 4: Publish (OPERATOR `workflow_dispatch`, post-merge):** `gh workflow run publish-kmp.yml -f modules=":swip-logging:publishAllPublicationsToGitHubPackagesRepository" --ref main`. Verify `works.sloop.swip:swip-logging:0.1.0` live. **Phase B blocked until green.**
- [ ] **Step 5: Update** SWIP memory `swip-project-state.md`.

---

# Phase B — Dayfold repo (branch `feat/sloop-logging-design`)

### Task B1: Evolve `ClientLog` → `Log` front-door + migrate call sites

**Files:**
- Rewrite: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ClientLog.kt` → `Log.kt` (`object Log`)
- Modify: `apps/client/src/commonMain/.../SyncEngine.kt` (3 sites), `Reducer.kt` (1 site: the `actionLog`)
- Modify: `apps/androidApp/src/main/.../MainActivity.kt` (the `ClientLog.sink = …` line)
- Test: rewrite `apps/client/src/desktopTest/.../ClientLogTest.kt` → `LogTest.kt`

**Interfaces:**
- Produces: `object Log` with `enum LogLevel { DEBUG, INFO, WARN, ERROR }`, `@Volatile var sink`, `@Volatile var minLevel`, and `inline` `d/i/w/e(tag){msg}` per the spec's front-door code block.

- [ ] **Step 1: Write failing tests** `LogTest.kt`:

```kotlin
class LogTest {
  @AfterTest fun reset() { Log.sink = null; Log.minLevel = Log.LogLevel.DEBUG }
  @Test fun `below minLevel builds no message and does not emit`() {
    Log.minLevel = Log.LogLevel.WARN
    var built = false
    val seen = mutableListOf<String>(); Log.sink = { _, t, m, _, _ -> seen += "$t/$m" }
    Log.d("x") { built = true; "hi" }
    assertFalse(built); assertTrue(seen.isEmpty())
  }
  @Test fun `bound sink receives level and tag, no println path asserted here`() {
    val seen = mutableListOf<Triple<Log.LogLevel, String, String>>()
    Log.sink = { l, t, m, _, _ -> seen += Triple(l, t, m) }
    Log.w("sync") { "boom" }
    assertEquals(listOf(Triple(Log.LogLevel.WARN, "sync", "boom")), seen)
  }
  @Test fun `no sink installed does not throw`() { Log.sink = null; Log.i("t") { "no sink" } }
}
```

- [ ] **Step 2: Run — verify fail.** `cd apps && GITHUB_ACTOR=patjackson52 SLOOPWORKS_PACKAGES_TOKEN=$(gh auth token) ./gradlew :client:desktopTest --tests '*LogTest'` → FAIL.

- [ ] **Step 3: Implement** `Log.kt` exactly as the spec's Layer-1 code block (LogLevel DEBUG/INFO/WARN/ERROR; `@Volatile` sink/minLevel; `inline fun log(...)` with the sink-else-println fallback; `d/i/w/e`). Delete `ClientLog`.

- [ ] **Step 4: Migrate call sites** — `SyncEngine.kt`: `ClientLog.log("sync", "401 …")` → `Log.i("sync"){ "401 — refreshing access token" }`; `"token refresh failed…"` → `Log.w("sync"){ … }`; `"failed: HTTP …"` → `Log.w("sync"){ … }`. `Reducer.kt` actionLog → `Log.d("redux"){ "${action::class.simpleName} → cards=${s.cards.size} syncing=${s.syncing} error=${s.error}" }`. `MainActivity.kt`: `ClientLog.sink = { tag, msg -> DebugLog.i(tag, msg) }` becomes bound in B2 (leave a temporary `Log.sink = { l, t, m, _, _ -> DebugLog.record(map(l), t, m) }` — B2 replaces it).

- [ ] **Step 5: Run — verify pass** + full `:client:desktopTest` green.

- [ ] **Step 6: Commit.**

```bash
git add apps/client/src/ apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/MainActivity.kt
git commit -m "feat(client): ClientLog → leveled Log front-door (lazy inline, sink-fallback, context); migrate sites"
```

### Task B2: Composition-root binding + the three writers

**Files:**
- Modify: `apps/swip-wiring/build.gradle.kts` (add `api("works.sloop.swip:swip-logging:0.1.0")`)
- Create: `apps/swip-wiring/src/commonMain/.../swip/DayfoldLogging.kt` (writer builders + policy)
- Create: `apps/androidApp/src/debug/.../android/LoggingGlue.kt` + inert `src/release/.../LoggingGlue.kt`
- Modify: `MainActivity.kt` (call `installLogging(...)`)

**Interfaces:**
- Consumes: `works.sloop.swip.{SloopLogging, Severity, LogWriter, LogLevelPolicy}`, `works.sloop.swip.logging.{DefaultSloopLogging, FixedPolicy, platformConsoleWriter}`, `Log` (B1), `DebugLog.record` + `DebugLog.LogLevel` (existing), the bug-report crumb ring.
- Produces: `fun buildDayfoldLogging(debug: Boolean): SloopLogging` (:swip-wiring); `fun installLogging(debug: Boolean)` (androidApp glue) that binds `Log.sink` + `Log.minLevel`.

- [ ] **Step 1:** add the dep (Step verifies resolution via `:swip-wiring:compileKotlinMetadata`).
- [ ] **Step 2: `DayfoldLogging.kt`** — a `DebugDrawerWriter` mapping `Severity`→`DebugLog.LogLevel` (`DEBUG→D, INFO→I, WARN→W, ERROR→E`) calling `DebugLog.record(...)`; a `BreadcrumbWriter` appending `"$severity/$tag: $message"` to the bug-report crumb ring (reuse the existing synchronized ring the recorder glue owns — expose a thread-safe `addCrumb(String)` on it if needed); `buildDayfoldLogging(debug)` = `DefaultSloopLogging(listOf(platformConsoleWriter(), DebugDrawerWriter(), BreadcrumbWriter()), FixedPolicy(perTag, if (debug) Severity.DEBUG else Severity.WARN))`. Write a test: build with a fake DebugLog seam, log, assert the drawer got the mapped level.
- [ ] **Step 3: `LoggingGlue.kt` (debug)** — `fun installLogging(debug: Boolean) { val logging = buildDayfoldLogging(debug); Log.sink = { l, t, m, c, e -> logging.log(sev(l), t, m, c, e) }; Log.minLevel = level(logging.minSeverity()) }` with `Severity↔LogLevel` maps. Release mirror: `fun installLogging(debug: Boolean) {}` (front-door stays on its println fallback — or bind a console-only sink; choose println fallback = zero swip in release). **Decision:** release = inert (println fallback), consistent with debug/internal-only analytics; the release APK keeps the front-door's own stdout for WARN+ only.
- [ ] **Step 4:** `MainActivity.onCreate` — call `installLogging(BuildConfig.DEBUG)` right after `swipInit(...)` / before heavy logging.
- [ ] **Step 5: Compile both variants** (`:androidApp:compileDebugKotlin :compileReleaseKotlin`) + `:swip-wiring:desktopTest` green.
- [ ] **Step 6: Commit.**

### Task B3: Analytics-mirror hook

**Files:** Modify the swip-rk composition (the analytics debug glue `SwipAnalyticsGlue.kt` or the mapper wiring) to, on each tracked event, `Log.d("analytics") { event.schema }`.

- [ ] **Step 1:** In the debug analytics glue, wrap/observe the `track` path so each emitted `SwipEvent` also `Log.d("analytics"){ it.schema }`. (If the swip-rk middleware doesn't expose a post-track hook, add a tiny decorator around the `SloopAnalytics` passed to `swipMiddleware` that logs then delegates.) A decorator is cleanest: `class LoggingAnalytics(val inner: SloopAnalytics): SloopAnalytics { override fun track(e){ Log.d("analytics"){ e.schema }; inner.track(e) } ... }`.
- [ ] **Step 2:** Test (in :swip-wiring): the decorator logs schema then delegates; assert both.
- [ ] **Step 3:** Compile debug; commit.

### Task B4: detekt lints (no-direct-console + no-log-from-reducer)

- [ ] **Step 1:** Add/extend the repo's detekt config with a rule forbidding `println(`/`android.util.Log` outside `Log.kt` + the swip glue, and forbidding `Log.` inside `Reducer.kt`'s reducer functions. (If detekt isn't configured, add a minimal custom rule or a CI grep gate — check `apps/` for existing detekt; mirror its setup.)
- [ ] **Step 2:** Run the lint gate; commit.

### Task B5: Seed engine logging + Dayfold ADR + docs

- [ ] **Step 1:** Add `Log` calls at the convention (INFO for auth/sync/nav milestones in AuthEngine/SyncEngine/HubEngine/NowEngine; WARN/ERROR on failures; DEBUG kept minimal) — a handful per engine, not exhaustive.
- [ ] **Step 2:** Dayfold ADR (next after 0055) for the logging integration (front-door + SloopLogging binding, on-device-only, debug-internal binding/release-inert, scrubber). Add decisions-index row + CHANGELOG (internal) + architecture.md logging note.
- [ ] **Step 3:** Extend the salted-PII leak test to cover the logging path (feed PII through `Log` → sink → assert scrubbed).
- [ ] **Step 4:** Commit + open PR; poll CI; merge on operator say-so; on-device smoke (logcat shows leveled, scrubbed lines + analytics events at DEBUG). Update Dayfold memory.

---

## Self-Review

**Spec coverage:** facade+levels+contract (interfaces in core) → A1; scrubber → A2; runtime/gate/fan-out/copy-on-write → A3; platform writers → A4; `SwipInstance.logging` injection → A5; SWIP ADR/publish → A6; front-door+migration → B1; binding+writers+drawer-map+release-inert → B2; analytics-mirror hook → B3; no-direct-console lint → B4; engine logging + Dayfold ADR + leak test → B5. Memory (lazy gate, sync fan-out, static regexes, bounded buffers) enforced in A2/A3/B1 code + Global Constraints. Concurrency (volatile, copy-on-write, thread-safe writers, safe publication) in A3/A5/B1/B2 + Global Constraints. Fleet split (TS backend follow) noted in the design; the `apps/api` adoption is a separate follow, intentionally out of this plan.

**Placeholder scan:** A4 iOS uses `NSLog %{public}@` (concrete) rather than the os_log cinterop (noted simpler); B4 has a fallback if detekt isn't wired (a CI grep gate) — both concrete branches, not hand-waving. A1's `.gitkeep` is a real scaffold step (empty module dir before A2–A4 land files). No TODO/TBD.

**Type consistency:** facade interfaces (`SloopLogging`/`Severity`/`LogWriter`/`LogLevelPolicy`/`NoOpLogging`) live in **`works.sloop.swip`** (swip-core) from A1 — the runtime (`DefaultSloopLogging`/`FixedPolicy`/`scrub*`/`platformConsoleWriter`) lives in **`works.sloop.swip.logging`** (:swip-logging) and imports the interfaces from core; no mid-plan relocation. `Severity{DEBUG,INFO,WARN,ERROR}` (SWIP) ↔ `LogLevel{DEBUG,INFO,WARN,ERROR}` (Dayfold front-door) with explicit maps in B2/B3; `DebugLog.LogLevel{V,D,I,W,E}` mapped `DEBUG→D…ERROR→E` in B2; `SloopLogging.log(severity,tag,message,context?,error?)` used identically A3/A5/B2; `platformConsoleWriter()`, `DefaultSloopLogging`, `FixedPolicy`, `scrubString/scrubObject/sanitizeError` names consistent across tasks.
