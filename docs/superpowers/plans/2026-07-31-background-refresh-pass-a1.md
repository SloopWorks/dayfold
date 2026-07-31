# Background Refresh Pass (ADR 0020 R3) — Slice A1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a backgrounded device sync content on a schedule, so the next app open is already fresh and background notifications stop firing off hours-stale content.

**Architecture:** Extract the `/sync` page-drain loop out of `SyncEngine` into a `SyncDrainer` that takes its session concerns as injected lambdas. Foreground `syncNow` passes the existing epoch-fenced ones; a new headless `backgroundRefreshPass` passes pass-through ones, resolving family from the `membership` cache and credentials from `TokenStore`. Both call the same drain code — the codebase's "NO ENGINE FORK" invariant holds. Android gains a WorkManager `PeriodicWorkRequest`; iOS folds the pass into its existing `BGAppRefreshTask`.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines, SQLDelight, ktor client, `androidx.work` (new), iOS `BGTaskScheduler` (existing).

**Spec:** `docs/superpowers/specs/2026-07-31-background-refresh-and-weather-design.md` Part A.

## Global Constraints

- **JDK 17** for all Gradle builds: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`. Read `processes/agent-dev-loop.md` before touching `apps/client` or `apps/androidApp`.
- Tests run from `apps/`: `JAVA_HOME=<jdk17> ./gradlew :client:desktopTest` (~440 tests), `:ui:desktopTest` (~329).
- **No engine fork.** Foreground and background must execute the same drain code. A second copy of the paging loop is a plan failure.
- **No new ADR needed for this slice** — ADR 0020 R3 was Accepted 2026-07-31. Do not widen scope into weather (slices B1–B3) or push (FCM/APNs).
- **Nothing in this slice may promise freshness.** No UI copy, log line, or comment may state a guaranteed cadence. See spec A.6.
- `SyncReason.BACKGROUND` already exists in the enum and is currently never called. Use it; do not add a new reason.
- **ONE syncer per process — this is a correctness rule, not an optimization.**
  `AuthClient.kt:133` documents that `POST /auth/refresh` rotates the session and
  **reuse-detection revokes the lineage server-side**. WorkManager runs in the app's
  main process, so if a live runtime is holding the session in memory and the
  background pass independently refreshes from the persisted token, the second use of
  the rotated token is treated as reuse and **the user is signed out**. The background
  pass must therefore delegate to the live runtime when one exists and take the
  headless path only when the process has none. The same rule removes a cursor
  read-fetch-apply race between the 45s foreground poll and the worker.
  **Amended 2026-07-31 (operator ruling):** delegating via `requestSync` alone is
  insufficient — `SyncCoordinator.pause()` (called when the app backgrounds) makes
  `requestSync` a no-op, so a warm-backgrounded process would never sync. The
  coordinator gains a one-shot entry that runs a single pass even while paused,
  serialized through the same mutex and without restarting the poll loop. `pause()`
  governs the POLL LOOP, not the coordinator's willingness to do one-shot work.
- **DB work goes through `databaseDispatcher`.** `ContentStore.applyDelta` is
  `withWriteGate`-serialized so concurrency can't corrupt, but the convention (and
  ADR 0058's serialization posture) is that store work is dispatched, not run on
  whatever thread the caller happened to be on.
- **Do not add any "notify the store" step.** `ContentBridge` already collects
  `contentStore.activeCardsFlow(databaseDispatcher)` (and hubs/hidden), so a
  background DB write propagates into a live Redux store on its own.
- Branch from latest `main`. Commits and PR text written normally (not caveman).

---

## File Structure

| File | Responsibility |
|---|---|
| `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/SyncDrainer.kt` | **New.** The page-drain loop, session concerns injected. The only place `/sync` paging exists. |
| `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/SyncEngine.kt` | **Modify.** `drain()` becomes a call into `SyncDrainer` with the epoch-fenced lambdas. |
| `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/BackgroundRefresh.kt` | **New.** `backgroundRefreshPass` — the bounded, headless orchestration both platforms call. |
| `apps/client/src/androidMain/kotlin/com/sloopworks/dayfold/client/AndroidBackgroundRefresh.kt` | **New.** `RefreshWorker` + unique-periodic enqueue. |
| `apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/NotifReceivers.kt` | **Modify.** `BootReceiver` also ensures the periodic work is enqueued. |
| `apps/androidApp/build.gradle.kts` | **Modify.** Add `androidx.work:work-runtime-ktx`. |
| `apps/client/src/iosMain/kotlin/com/sloopworks/dayfold/client/IosBackgroundNotify.kt` | **Modify.** `bgReconcile()` calls the shared pass. |
| `apps/iosApp/Sources/App.swift` | **Modify.** Add the missing `expirationHandler`. |
| `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/SyncDrainerTest.kt` | **New.** Paging, cursor advance, commit-rejection, cancellation. |
| `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/BackgroundRefreshTest.kt` | **New.** Budget exhaustion, step ordering, no-session no-op. |

---

> **Reviewed 2026-07-31** (correctness / boundaries / concurrency / redux / mobile). Six
> corrections are folded in below: the one-syncer-per-process delegation rule (token-rotation
> reuse detection would sign the user out), the iOS async-completion fix, the drainer seam
> moved so page→DB isn't duplicated, `activeFamilyIdFor` reused instead of restated,
> dispatched DB work, and the note that `ContentBridge` already propagates background writes
> into a live Redux store.

## Task 1: Extract `SyncDrainer` from `SyncEngine`

Pure refactor. Behavior must not change; the existing sync tests are the proof.

**Files:**
- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/SyncDrainer.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/SyncEngine.kt:171-205`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/SyncDrainerTest.kt`

**Interfaces:**
- Consumes: `SyncClient.fetchPage(familyId: String, accessToken: String, since: String?): SyncResponse`; `ContentStore.cursor()`, `.applyDelta(...)`, `.wipeForResync()`.
- Produces: `class SyncDrainer(contentStore, databaseDispatcher, nowIso, fetch, commit, onActivity)` with `suspend fun drain()`. It owns page→DB concretely; only the session concerns (`fetch`, `commit`) are injected. Task 5 constructs it for the headless path.

- [ ] **Step 1: Write the failing test**

Create `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/SyncDrainerTest.kt`:

```kotlin
package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SyncDrainerTest {

  // Two pages, then done. Proves the loop follows has_more and feeds each page's
  // cursor back into the next fetch.
  @Test fun `drains every page in order`() = runTest {
    val cursors = mutableListOf<String?>()
    var page = 0
    val drainer = drainerFor(inMemoryContentStore(), fetch = { since ->
      cursors += since
      page++
      if (page == 1) syncResponse(nextCursor = "c1", hasMore = true)
      else syncResponse(nextCursor = "c2", hasMore = false)
    })

    drainer.drain()

    assertEquals(listOf(null, "c1"), cursors)
    assertEquals(2, page)
  }

  // A rejected commit means the family session was replaced mid-pass. The drain must
  // abort rather than apply a page into the wrong tenant's cache.
  @Test fun `aborts when a commit is rejected`() = runTest {
    var fetches = 0
    val drainer = drainerFor(
      inMemoryContentStore(),
      fetch = { fetches++; syncResponse(nextCursor = "c1", hasMore = true) },
      commit = { false },
    )

    val error = runCatching { drainer.drain() }.exceptionOrNull()

    assertTrue(error is kotlinx.coroutines.CancellationException)
    assertEquals(1, fetches)
  }

  // A page carrying full_resync wipes the synced cache before applying, so the rebuild
  // starts clean (ADR 0040 stale-cursor directive). Uses a real in-memory ContentStore
  // because the drainer now owns page->DB directly.
  @Test fun `full resync page wipes before applying`() = runTest {
    val store = inMemoryContentStore()
    store.applyDelta(
      changedCards = listOf(card(id = "stale")), changedHubs = emptyList(),
      changedSections = emptyList(), changedBlocks = emptyList(), tombstones = emptyList(),
      nextCursor = "c0", nowIso = "2026-07-31T00:00:00Z", changedPlaces = emptyList(),
    )

    drainerFor(store, fetch = { syncResponse(nextCursor = "c1", hasMore = false, fullResync = true) }).drain()

    // The pre-existing card is gone: the wipe ran before the (empty) page was applied.
    assertTrue(store.activeCards().none { it.id == "stale" })
    assertEquals("c1", store.cursor())
  }

  private fun drainerFor(
    store: ContentStore,
    fetch: suspend (String?) -> SyncResponse,
    commit: suspend (block: () -> Unit) -> Boolean = { block -> block(); true },
  ) = SyncDrainer(
    contentStore = store,
    databaseDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    nowIso = { "2026-07-31T12:00:00Z" },
    fetch = fetch,
    commit = commit,
    onActivity = {},
  )

  private fun syncResponse(
    nextCursor: String,
    hasMore: Boolean,
    fullResync: Boolean = false,
  ) = SyncResponse(
    changes = Changes(),
    tombstones = emptyList(),
    nextCursor = nextCursor,
    hasMore = hasMore,
    fullResync = fullResync,
  )
}
```

Before running: confirm the constructor parameter names of `SyncResponse` and `Changes` in
`SyncClient.kt` and adjust the `syncResponse` helper to match — do not change those classes. For
`inMemoryContentStore()` and `card(...)`, reuse the existing test helpers: grep the desktopTest
sources (`grep -rn "ContentStore(" apps/client/src/desktopTest | head`) for how `ContentStoreTest`
and `BackgroundNotifyTest` build a store over an in-memory JDBC driver, and use that same helper
rather than writing a second one.

- [ ] **Step 2: Run the test to verify it fails**

```
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest --tests '*SyncDrainerTest*'
```

Expected: compilation failure — `Unresolved reference: SyncDrainer`.

- [ ] **Step 3: Write `SyncDrainer`**

Create `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/SyncDrainer.kt`:

```kotlin
package com.sloopworks.dayfold.client

import kotlinx.coroutines.CancellationException

// ADR 0020 R3 — the ONE /sync paging loop. Extracted from SyncEngine so the foreground pass
// and the headless background pass execute the same code (the "NO ENGINE FORK" invariant that
// BackgroundNotify.kt states for the notify path). Everything session-shaped is injected:
// the foreground supplies epoch-fenced authorize/commit (ADR 0058), the background supplies
// pass-throughs, and neither knows about the other.
class SyncDrainer(
  private val contentStore: ContentStore,
  private val databaseDispatcher: CoroutineDispatcher,
  private val nowIso: () -> String,
  /** Fetch one page. Foreground wraps this in the coordinator's authorizedCall. */
  private val fetch: suspend (since: String?) -> SyncResponse,
  /** Applies [block] iff the session is still current; false = replaced mid-pass. */
  private val commit: suspend (block: () -> Unit) -> Boolean,
  private val onActivity: () -> Unit,
) {
  /**
   * Drain pages until the server reports no more. Each page is its own atomic apply, and the
   * cursor only advances on commit — so cancelling mid-drain (a background wake running out of
   * budget) leaves a consistent cache that the next pass resumes from with no gap and no
   * double-pull. That property is what makes bounding the background pass safe.
   */
  suspend fun drain() {
    var hasMore = true
    while (hasMore) {
      val since = withContext(databaseDispatcher) { contentStore.cursor() }
      val resp = fetch(since)
      if (resp.hasMaterialChanges()) onActivity()
      // page -> DB is IDENTICAL in both paths, so it lives here concretely. Only the
      // session concerns (fetch/commit) are injected; duplicating the applyDelta
      // mapping at each call site is what this extraction exists to prevent.
      val committed = withContext(databaseDispatcher) {
        commit {
          // ADR 0040 stale-cursor directive: rebuild clean when the server reset the scan.
          if (resp.fullResync) contentStore.wipeForResync()
          contentStore.applyDelta(
            changedCards = resp.changes.cards,
            changedHubs = resp.changes.hubs,
            changedSections = resp.changes.sections,
            changedBlocks = resp.changes.blocks,
            tombstones = resp.tombstones,
            nextCursor = resp.nextCursor,
            nowIso = nowIso(),
            changedPlaces = resp.changes.places,
          )
        }
      }
      if (!committed) throw CancellationException("Family session replaced")
      hasMore = resp.hasMore
    }
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest --tests '*SyncDrainerTest*'
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Rewire `SyncEngine.drain` to use it**

In `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/SyncEngine.kt`, replace the body of the private `drain(context, onActivity)` (currently lines 171-205) with:

```kotlin
  /** Drain all /sync pages into the DB in order (each page is its own atomic applyDelta).
   *  The loop itself lives in [SyncDrainer] so the background pass reuses it (ADR 0020 R3). */
  private suspend fun drain(
    context: FamilySessionContext,
    onActivity: () -> Unit,
  ) {
    SyncDrainer(
      contentStore = contentStore,
      databaseDispatcher = databaseDispatcher,
      nowIso = nowProvider,
      fetch = { since ->
        sessionCoordinator.authorizedCall(context) { current ->
          current.withFamilyAndAccessToken { familyId, accessToken ->
            syncClient.fetchPage(familyId, accessToken, since)
          }
        }
      },
      commit = { block -> sessionCoordinator.commitIfCurrent(context) { block() } },
      onActivity = onActivity,
    ).drain()
  }
```

- [ ] **Step 6: Run the full client suite — the refactor must be invisible**

```
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest
```

Expected: PASS, all ~443 tests (440 existing + 3 new). Any pre-existing sync test that now fails means the extraction changed behavior — fix the extraction, not the test.

- [ ] **Step 7: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/SyncDrainer.kt \
        apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/SyncEngine.kt \
        apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/SyncDrainerTest.kt
git commit -m "refactor: extract SyncDrainer so background sync reuses the foreground loop"
```

---

## Task 2: The bounded background pass

**Files:**
- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/BackgroundRefresh.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/BackgroundRefreshTest.kt`

**Interfaces:**
- Consumes: `SyncDrainer` (Task 1); `ContentStore.cachedMemberships(): List<FamilyMembership>`; `TokenStore.load(): Session?` where `Session(access: String, refresh: String, userId: String?)`.
- Produces: `suspend fun backgroundRefreshPass(deps: RefreshDeps, budget: Duration): RefreshOutcome` and `class RefreshDeps(memberships, session, syncOnce, reconcile)`, consumed by Tasks 3 and 4. `RefreshDeps.syncOnce` has type `suspend (familyId: String, session: Session) -> Unit`; Task 5 fills it with `headlessSync(contentStore, syncClient, familyId, session, refreshAccess, nowIso)` behind a per-platform wrapper.

- [ ] **Step 1: Write the failing test**

Create `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/BackgroundRefreshTest.kt`:

```kotlin
package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class BackgroundRefreshTest {

  // No cached family (fresh install, or signed out) → the pass does nothing at all.
  // It must NOT attempt a network call it cannot authorize.
  @Test fun `no cached family is a clean no-op`() = runTest {
    var synced = false
    val outcome = backgroundRefreshPass(
      deps = deps(memberships = emptyList(), sync = { synced = true }),
      budget = 30.seconds,
    )

    assertFalse(synced)
    assertFalse(outcome.synced)
    assertEquals("no-family", outcome.skippedReason)
  }

  // Reconcile MUST still run when sync overruns the budget: schedules going stale is a
  // worse failure than content going stale, and reconcile is cheap and local.
  @Test fun `reconcile still runs when sync exhausts the budget`() = runTest {
    var reconciled = false
    val outcome = backgroundRefreshPass(
      deps = deps(sync = { delay(60.seconds) }, reconcile = { reconciled = true }),
      budget = 1.seconds,
    )

    assertTrue(outcome.budgetExhausted)
    assertFalse(outcome.synced)
    assertTrue(reconciled)
  }

  // The happy path reports what it did, for the Log line.
  @Test fun `reports a completed pass`() = runTest {
    var reconciled = false
    val outcome = backgroundRefreshPass(
      deps = deps(reconcile = { reconciled = true }),
      budget = 30.seconds,
    )

    assertTrue(outcome.synced)
    assertTrue(reconciled)
    assertFalse(outcome.budgetExhausted)
    assertEquals(null, outcome.skippedReason)
  }

  // A live runtime must be delegated to — never bypassed. Two independent refreshers
  // race token rotation and the server's reuse detection signs the user out.
  @Test fun `delegates to the live runtime instead of syncing headlessly`() = runTest {
    var delegated = false
    var headless = false
    val outcome = backgroundRefreshPass(
      deps = deps(delegate = { delegated = true }, sync = { headless = true }),
      budget = 30.seconds,
    )

    assertTrue(delegated)
    assertFalse(headless)
    assertTrue(outcome.delegated)
    assertTrue(outcome.synced)
  }

  private fun deps(
    memberships: List<FamilyMembership> = listOf(FamilyMembership(familyId = "f1")),
    session: Session? = Session(access = "a", refresh = "r"),
    delegate: (suspend () -> Unit)? = null,
    sync: suspend () -> Unit = {},
    reconcile: () -> Unit = {},
  ) = RefreshDeps(
    memberships = { memberships },
    session = { session },
    delegateToRuntime = delegate,
    syncOnce = { _, _ -> sync() },
    reconcile = reconcile,
  )
}
```

- [ ] **Step 2: Run the test to verify it fails**

```
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest --tests '*BackgroundRefreshTest*'
```

Expected: compilation failure — `Unresolved reference: backgroundRefreshPass`.

- [ ] **Step 3: Write the pass**

Create `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/BackgroundRefresh.kt`:

```kotlin
package com.sloopworks.dayfold.client

import kotlin.time.Duration
import kotlinx.coroutines.withTimeoutOrNull

// ADR 0020 R3 — the headless refresh pass both platforms call (Android WorkManager,
// iOS BGAppRefreshTask). Holds NO Redux store: a freshly-spawned background process has an
// empty SessionCoordinator, so family comes from the `membership` cache (ADR 0052) and
// credentials from TokenStore. Bounded because iOS grants ~30s per wake; safe to bound
// because SyncDrainer commits per page and the cursor only advances on commit.

/** Everything the pass touches, injected so it is testable with no platform and no network. */
class RefreshDeps(
  val memberships: () -> List<FamilyMembership>,
  val session: () -> Session?,
  /**
   * The live runtime's sync entry point when this process HAS one, else null.
   * Delegating is mandatory, not an optimization: two independent refreshers race
   * refresh-token rotation, and the server's reuse detection revokes the lineage —
   * signing the user out. See Global Constraints.
   */
  val delegateToRuntime: (suspend () -> Unit)?,
  val syncOnce: suspend (familyId: String, session: Session) -> Unit,
  val reconcile: () -> Unit,
)

/** What actually happened — for the Log line. Never rendered as a freshness promise. */
data class RefreshOutcome(
  val synced: Boolean = false,
  val budgetExhausted: Boolean = false,
  val reconciled: Boolean = false,
  val delegated: Boolean = false,
  val skippedReason: String? = null,
)

suspend fun backgroundRefreshPass(deps: RefreshDeps, budget: Duration): RefreshOutcome {
  // A live runtime owns the session and the cursor. Hand it the work and stop.
  deps.delegateToRuntime?.let { delegate ->
    val done = withTimeoutOrNull(budget) { runCatching { delegate() }.isSuccess }
    deps.reconcile()
    return RefreshOutcome(
      synced = done == true, budgetExhausted = done == null,
      reconciled = true, delegated = true,
    )
  }

  // Reuse the reducer's selection rule rather than restating it (Reducer.kt:23).
  // Known limitation: activeFamilyId is in-memory only, so a multi-family user's
  // explicit selection is not visible here — this picks the first active membership,
  // which is what the reducer does today.
  val familyId = activeFamilyIdFor(deps.memberships())
  val session = deps.session()
  if (familyId == null || session == null) {
    // Nothing to sync, but reconcile is local and still worth doing.
    deps.reconcile()
    return RefreshOutcome(reconciled = true, skippedReason = if (familyId == null) "no-family" else "no-session")
  }

  // Step 1 — sync. A timeout is NOT an error: the cursor makes the partial pass resumable,
  // so the next wake continues. Retrying here would spend the budget reconcile still needs.
  val completed = withTimeoutOrNull(budget) {
    runCatching { deps.syncOnce(familyId, session) }.isSuccess
  }

  // Step 2 (slice B1) — refreshForecasts() lands here.

  // Step 3 — reconcile ALWAYS runs, even when sync overran. Local, cheap, and stale
  // schedules are a worse failure than stale content.
  deps.reconcile()

  return RefreshOutcome(
    synced = completed == true,
    budgetExhausted = completed == null,
    reconciled = true,
  )
}
```

- [ ] **Step 4: Run the test to verify it passes**

```
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest --tests '*BackgroundRefreshTest*'
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/BackgroundRefresh.kt \
        apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/BackgroundRefreshTest.kt
git commit -m "feat: add the bounded headless background refresh pass"
```

---

## Task 3: Android — WorkManager periodic work

**Files:**
- Modify: `apps/androidApp/build.gradle.kts` (dependencies block, ~line 96)
- Create: `apps/client/src/androidMain/kotlin/com/sloopworks/dayfold/client/AndroidBackgroundRefresh.kt`
- Modify: `apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/NotifReceivers.kt` (`BootReceiver`)

**Interfaces:**
- Consumes: `backgroundRefreshPass(deps, budget)` and `RefreshDeps` (Task 2); `AndroidContentStoreHolder.get(context)`; `reReggisterGeofences(context)` — note the existing spelling is `reRegisterGeofences(context)` in `AndroidBackgroundNotify.kt`, use that.
- Produces: `fun ensurePeriodicRefresh(context: Context)`, called from `BootReceiver` and app start.

- [ ] **Step 1: Add the dependency**

In `apps/androidApp/build.gradle.kts`, inside `dependencies {`, next to the other AndroidX entries:

```kotlin
  // ADR 0020 R3 — periodic background refresh. Default androidx.startup init; no custom
  // WorkerFactory, so no Configuration.Provider on the Application class.
  implementation("androidx.work:work-runtime-ktx:2.9.1")
```

- [ ] **Step 2: Write the worker and the enqueue**

Create `apps/client/src/androidMain/kotlin/com/sloopworks/dayfold/client/AndroidBackgroundRefresh.kt`:

```kotlin
package com.sloopworks.dayfold.client

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

// ADR 0020 R3 — Android glue. All decision logic is in commonMain (backgroundRefreshPass);
// this is a thin OS bridge, mirroring AndroidBackgroundNotify.

const val REFRESH_WORK_NAME = "dayfold.refresh"

// 30 minutes, not the 15-minute floor: the OS runs periodic work inside a flex window subject
// to Doze and standby buckets, so a shorter request buys jitter, not freshness. NEVER treat
// this as a guaranteed cadence (spec A.6).
private const val REFRESH_INTERVAL_MINUTES = 30L
private const val REFRESH_FLEX_MINUTES = 10L

class RefreshWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val outcome = runBackgroundRefresh(applicationContext)
    Log.i("refresh") { "background pass: $outcome" }
    // A budget overrun is not a failure — the cursor resumes next wake. Returning retry()
    // here would burn standby-bucket quota on a problem waiting solves.
    return Result.success()
  }
}

/**
 * Enqueue the periodic refresh. KEEP so repeated calls (app start, BOOT_COMPLETED) are free
 * rather than duplicative — WorkManager already persists its own work across reboots, so the
 * boot re-enqueue is belt-and-braces. Switch to UPDATE only if the interval/constraints change.
 */
fun ensurePeriodicRefresh(context: Context) {
  val request = PeriodicWorkRequestBuilder<RefreshWorker>(
    REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES,
    REFRESH_FLEX_MINUTES, TimeUnit.MINUTES,
  )
    // CONNECTED + BatteryNotLow ONLY. RequiresCharging/DeviceIdle would starve it: an unmet
    // constraint can skip a run entirely, not merely delay it.
    .setConstraints(
      Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build(),
    )
    .build()

  WorkManager.getInstance(context.applicationContext)
    .enqueueUniquePeriodicWork(REFRESH_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
}

/** Wire the commonMain pass to Android's store + notify glue. Budget is generous relative to
 *  iOS because WorkManager allows ~10 minutes; the cap exists to bound a hung network call. */
internal suspend fun runBackgroundRefresh(context: Context): RefreshOutcome {
  val cs = AndroidContentStoreHolder.get(context)
  return backgroundRefreshPass(
    deps = RefreshDeps(
      memberships = { cs.cachedMemberships() },
      session = { AndroidTokenStore(context).load() },
      syncOnce = { familyId, session -> androidHeadlessSync(context, familyId, session) },
      reconcile = { reRegisterGeofences(context) },
    ),
    budget = 60.seconds,
  )
}

/** Supplies the platform pieces to the shared headless drain (Task 5's signature). */
private suspend fun androidHeadlessSync(context: Context, familyId: String, session: Session) {
  val cs = AndroidContentStoreHolder.get(context)
  headlessSync(
    contentStore = cs,
    syncClient = androidSyncClient(context),
    familyId = familyId,
    session = session,
    refreshAccess = { refresh -> androidRefreshAccess(context, refresh) },
    nowIso = { kotlin.time.Clock.System.now().toString() },
  )
}
```

`androidSyncClient` / `androidRefreshAccess` are the two remaining platform seams — construct
them the same way the foreground runtime does. Grep `DayfoldRuntimeFactory.create()` for how
`SyncClient` and the auth refresh are built today and reuse that construction rather than
inventing a second one.

`AndroidTokenStore` and `headlessSync` are named here as the seams Task 5 fills in. Before
writing this file, grep for the existing Android `TokenStore` implementation
(`grep -rn "TokenStore" apps/client/src/androidMain`) and use its real class name; if the
constructor differs, adapt the call rather than changing that class.

- [ ] **Step 3: Enqueue on boot**

In `apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/NotifReceivers.kt`, in `BootReceiver.onReceive`, after the existing geofence re-registration call, add:

```kotlin
    // ADR 0020 R3 — WorkManager persists work across reboots, but re-enqueueing with KEEP is
    // free and covers an install whose work was cleared (force-stop, app data clear).
    com.sloopworks.dayfold.client.ensurePeriodicRefresh(context)
```

- [ ] **Step 4: Enqueue on app start**

Find the `Application` subclass introduced for ADR 0060 (`grep -rn "class .*Application" apps/androidApp/src/main`) and call `ensurePeriodicRefresh(this)` at the end of its `onCreate`.

- [ ] **Step 5: Build**

```
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :androidApp:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add apps/androidApp/build.gradle.kts \
        apps/client/src/androidMain/kotlin/com/sloopworks/dayfold/client/AndroidBackgroundRefresh.kt \
        apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/NotifReceivers.kt
git commit -m "feat(android): schedule periodic background refresh via WorkManager"
```

---

## Task 4: iOS — sync in the BGTask, and the missing expiration handler

**Files:**
- Modify: `apps/client/src/iosMain/kotlin/com/sloopworks/dayfold/client/IosBackgroundNotify.kt` (`bgReconcile`)
- Modify: `apps/iosApp/Sources/App.swift:30-34`

**Interfaces:**
- Consumes: `backgroundRefreshPass` (Task 2), `IosContentStoreHolder.get()`, the existing `reconcileExactSchedules()`.
- Produces: `bgRefresh()` exported to Swift as `IosBackgroundNotifyKt.bgRefresh()`, plus `bgCancelRefresh()` for the expiration handler.

- [ ] **Step 1: Fix the expiration handler in Swift**

In `apps/iosApp/Sources/App.swift`, replace the `register` block (currently lines 30-34):

```swift
    BGTaskScheduler.shared.register(forTaskWithIdentifier: bgTaskId, using: nil) { [weak self] task in
      // iOS grants ~30s. Without an expirationHandler an overrun kills the app AND reduces how
      // often the system schedules this task afterwards — a self-inflicted freshness penalty.
      task.expirationHandler = {
        IosBackgroundNotifyKt.bgCancelRefresh()
        task.setTaskCompleted(success: false)
      }
      // bgRefresh is ASYNC. Completing the task here would let iOS suspend the app before
      // the work finished — silently, with no error and no log. Complete in the callback.
      IosBackgroundNotifyKt.bgRefresh {
        self?.submitReconcile()             // re-arm the next opportunistic run
        task.setTaskCompleted(success: true)
      }
    }
```

- [ ] **Step 2: Write the Kotlin side**

In `apps/client/src/iosMain/kotlin/com/sloopworks/dayfold/client/IosBackgroundNotify.kt`, keep `bgReconcile()` as-is (other callers may rely on it) and add:

```kotlin
// ADR 0020 R3 — the iOS BGAppRefreshTask entry point. Bounded at 25s, comfortably inside the
// ~30s the system grants, so the expiration handler is a backstop rather than the normal exit.
private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private var refreshJob: Job? = null

/** [onComplete] is invoked on the last line of the pass — the Swift side must call
 *  setTaskCompleted only from there, never immediately after this returns. */
fun bgRefresh(onComplete: () -> Unit) {
  val cs = IosContentStoreHolder.get()
  refreshJob = refreshScope.launch {
    val outcome = backgroundRefreshPass(
      deps = RefreshDeps(
        memberships = { cs.cachedMemberships() },
        session = { IosTokenStore().load() },
        syncOnce = { familyId, session ->
          headlessSync(
            contentStore = cs,
            syncClient = iosSyncClient(),
            familyId = familyId,
            session = session,
            refreshAccess = { refresh -> iosRefreshAccess(refresh) },
            nowIso = { kotlin.time.Clock.System.now().toString() },
          )
        },
        reconcile = { reconcileExactSchedules() },
      ),
      budget = 25.seconds,
    )
    Log.i("refresh") { "background pass: $outcome" }
    onComplete()
  }
}

/** Called from the BGTask expirationHandler — stop immediately so iOS is not forced to kill us. */
fun bgCancelRefresh() {
  refreshJob?.cancel()
}
```

Confirm the iOS `TokenStore` implementation's real class name first
(`grep -n "TokenStore" apps/client/src/iosMain/kotlin/com/sloopworks/dayfold/client/IosTokenStore.kt`)
and use it.

- [ ] **Step 3: Build the iOS framework**

```
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :ui:linkDebugFrameworkIosSimulatorArm64
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add apps/client/src/iosMain/kotlin/com/sloopworks/dayfold/client/IosBackgroundNotify.kt \
        apps/iosApp/Sources/App.swift
git commit -m "feat(ios): sync in the background refresh task; add the missing expirationHandler"
```

---

## Task 5: Headless sync — family + credentials without a Redux store

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/BackgroundRefresh.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/BackgroundRefreshTest.kt`

**Interfaces:**
- Consumes: `SyncDrainer` (Task 1), `SyncClient.fetchPage`, `ContentStore.cursor()/applyDelta()/wipeForResync()`, `AuthClient` refresh.
- Produces: `suspend fun headlessSync(...)` referenced by Tasks 3 and 4.

- [ ] **Step 1: Write the regression guard**

This one is deliberately NOT a TDD red step — it exercises Task 1's `SyncDrainer` directly and
must pass immediately. Its job is to fail later if anyone gives the background path its own
paging loop. Append to `BackgroundRefreshTest.kt`:

```kotlin
  // The headless path must use the SAME drainer as the foreground: pages applied in order,
  // cursor advanced per page, no Redux involvement.
  @Test fun `headless sync drains pages through the shared drainer`() = runTest {
    val store = inMemoryContentStore()
    var page = 0
    SyncDrainer(
      contentStore = store,
      databaseDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
      nowIso = { "2026-07-31T12:00:00Z" },
      fetch = {
        page++
        SyncResponse(
          changes = Changes(), tombstones = emptyList(),
          nextCursor = "c$page", hasMore = page < 2, fullResync = false,
        )
      },
      commit = { block -> block(); true },
      onActivity = {},
    ).drain()

    // Both pages applied through the SHARED drainer, cursor left at the last one.
    assertEquals(2, page)
    assertEquals("c2", store.cursor())
  }
```

- [ ] **Step 2: Run it**

```
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest --tests '*BackgroundRefreshTest*'
```

Expected: PASS immediately — see Step 1. If it FAILS, Task 1's drainer is wrong; fix that, not this test.

- [ ] **Step 3: Implement `headlessSync`**

Append to `BackgroundRefresh.kt`:

```kotlin
/**
 * Drain /sync with no Redux store. Uses the SAME [SyncDrainer] the foreground uses; the session
 * lambdas are pass-throughs because a background process has no epochs to fence against — it
 * holds one family and one credential for the life of the wake.
 *
 * A 401 refreshes once via [refreshAccess] and retries; a second failure gives up and lets the
 * next foreground open handle re-auth (a background wake must never drive a sign-out).
 */
suspend fun headlessSync(
  contentStore: ContentStore,
  syncClient: SyncClient,
  databaseDispatcher: CoroutineDispatcher,
  familyId: String,
  session: Session,
  refreshAccess: suspend (refresh: String) -> Session?,
  nowIso: () -> String,
) {
  var current = session
  var refreshed = false
  SyncDrainer(
    contentStore = contentStore,
    databaseDispatcher = databaseDispatcher,
    nowIso = nowIso,
    fetch = { since ->
      try {
        syncClient.fetchPage(familyId, current.access, since)
      } catch (e: AuthHttpException) {
        // ONE refresh attempt. Only reachable when no live runtime exists (see the
        // delegation rule) so there is exactly one refresher and no rotation race.
        if (e.status != 401 || refreshed) throw e
        refreshed = true
        current = refreshAccess(current.refresh) ?: throw e
        syncClient.fetchPage(familyId, current.access, since)
      }
    },
    // No epochs to fence in a headless process: one family, one credential, one wake.
    commit = { block -> block(); true },
    onActivity = {},
  ).drain()
}
```

Then update the platform call sites from Tasks 3 and 4 to pass the real arguments. Grep for the
existing refresh call (`grep -n "refresh" apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AuthClient.kt`)
and use its actual signature for `refreshAccess`.

- [ ] **Step 4: Run the full suite**

```
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest && \
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :ui:desktopTest
```

Expected: PASS both.

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/BackgroundRefresh.kt \
        apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/BackgroundRefreshTest.kt
git commit -m "feat: drain /sync headlessly in the background pass"
```

---

## Task 6: Docs and backlog

**Files:**
- Modify: `docs/architecture.md`
- Modify: `backlog/next.md` (TASK-SYNC REMAINING, ~line 172)
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Update the architecture doc**

In `docs/architecture.md`, in the component table, add a row after the CLI row:

```markdown
| Background refresh | `:client` `BackgroundRefresh.kt` + `AndroidBackgroundRefresh.kt` / `IosBackgroundNotify.kt` | Kotlin commonMain + WorkManager (Android) / BGAppRefreshTask (iOS) | ADR 0020 R3 — periodic bounded pass: drain `/sync` headlessly, then reconcile geofences + exact schedules, so the next open is already fresh. Best-effort, OS-throttled; never a guaranteed cadence. |
```

- [ ] **Step 2: Update the backlog**

In `backlog/next.md`, edit the **TASK-SYNC REMAINING** bullet: strike the R3 clause, keep **push** and **iOS sync-config** as still-open, and note that R3 landed with the date and PR number.

- [ ] **Step 3: Add the changelog entry**

At the top of `CHANGELOG.md`, above the most recent dated section:

```markdown
## 2026-07-31 — The app refreshes in the background

### Added
- **Content now refreshes while the app is closed**, so opening dayfold shows
  the current day rather than the last one you looked at. The device pulls
  changes on a schedule and re-arms its reminders in the same pass. This is
  best-effort by design — both platforms throttle background work, and the app
  never claims to be fresher than it is.

### Fixed
- **Background notifications no longer fire from stale content.** They
  previously ran against whatever was last synced while the app was open, so a
  reminder could reflect content hours out of date.
- **An iOS background task could be killed mid-run** and quietly reduce how
  often the system scheduled it afterwards; it now yields cleanly.
```

- [ ] **Step 4: Commit and open the PR**

```bash
git add docs/architecture.md backlog/next.md CHANGELOG.md
git commit -m "docs: record the background refresh pass (ADR 0020 R3)"
git push -u origin <branch>
gh pr create --title "Background refresh pass (ADR 0020 R3)" --body "<summary>"
```

---

## Verification before calling this done

- [ ] `:client:desktopTest` and `:ui:desktopTest` both green.
- [ ] `:androidApp:assembleDebug` and `:ui:linkDebugFrameworkIosSimulatorArm64` both build.
- [ ] CI green on the pushed SHA — verify by SHA and workflow, not "latest run".
- [ ] **On-device smoke (operator-driven).** Agent builds, installs, and reads logcat; the operator drives the device. Force a run with:
      `adb shell cmd jobscheduler run -f com.sloopworks.dayfold <jobId>`
      and confirm a `background pass:` log line with `synced=true`. Check the standby bucket with
      `adb shell am get-standby-bucket com.sloopworks.dayfold`.
- [ ] Confirm no UI string, log line, or doc added by this slice promises a refresh cadence.
