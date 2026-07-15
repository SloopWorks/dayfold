# Plan — SQLDelight sync → async DB migration (the web-client DB prerequisite)

**Status: ATTEMPTED 2026-06-28, reverted — BIGGER than "bounded". Not started in main.**
Prerequisite for a *functional* Compose-for-Web (`wasmJs`) client (see
`context/open-questions.md` → `OQ-web-target`). Do this **only when web is greenlit**, and
budget it as a **dedicated session** (NOT an autonomous-loop iteration) — see the attempt log
below: the main-code change is clean, but the **test suite needs a wide refactor** and there's
an **async schema-create ordering flake** to resolve first.

## Attempt log (2026-06-28) — what actually happened

A full attempt got the **main code compiling on every platform** (desktop, android
`assembleDebug`, iOS `compileKotlinIosSimulatorArm64` all green) but surfaced two things the
"bounded / behaviour-preserving" framing missed. Reverted rather than ship half-done.

**The concrete main-code changes that worked (re-use these next time):**
- `build.gradle.kts`: `generateAsync.set(true)` **+ add dep `app.cash.sqldelight:async-extensions:2.3.2`** (this is where `awaitAs*` and `synchronous()` live — not in coroutines-extensions).
- `ContentStore`: `applyDelta`/`wipe`/`activeCards`/`cursor` → `suspend`; `executeAsList`/`executeAsOneOrNull` → `awaitAsList`/`awaitAsOneOrNull`. Flows unchanged.
- `AuthEngine`: `clearCache: () -> Unit` → `suspend () -> Unit` (both call sites already suspend ✓).
- `SyncEngine`: `onSyncHttpError` → `suspend` (both callers in `suspend syncNow` ✓).
- **All 3 drivers** (`DriverFactory.{desktop,android,ios}`): `ContentDb.Schema` → `ContentDb.Schema.synchronous()` (async schema → sync driver).
- `MainActivity` (android shell): the debug seed `wipe()`/`applyDelta()` → wrap in `lifecycleScope.launch { }`.

**Blocker 1 — the test suite is a wide refactor, not "already runBlocking".** ~25 DB tests
across `ContentStoreTest`, `HubCacheTest`, `SyncEngineTest`, `HubEngineTest` call the
now-suspend methods outside a coroutine. Wrapping each `@Test` in `runBlocking` is mechanical
but extensive, and the shared helpers (`freshStore()`/`store()`/`freshContentStore()`) calling
`ContentStore.create()` force the cascade wider.

**Blocker 2 — async schema-create flake (the real gotcha).** `ContentStore.create()` does
`ContentDb.Schema.create(driver)`; under `generateAsync` that returns an async `QueryResult`.
`.synchronous().create(driver)` compiles and fixed *most* in-memory tests, but the suite stayed
**flaky**: a *different* in-memory test fails each run with `SQLITE_ERROR: no such table: hub` —
the write races the schema-create. The robust fix is to make `ContentStore.create()` **suspend**
and `.await()` the create — but that makes every `create()` caller (the test helpers above)
suspend, cascading to ~all DB tests. (Production is likely unaffected: the shells build the
driver via the *constructor* with `schema = …synchronous()`, so the schema is applied before any
query — only the test `create()` factory has the ordering race.)

**Recommendation:** when web is greenlit, do this as one focused sitting: apply the main-code
changes above, then make `ContentStore.create()` `suspend` + `.await()`, then convert the DB
tests to `runBlocking` in one sweep, and run `:client:desktopTest` **several times** to confirm
the flake is gone before the Pixel smoke. Verified end-to-end, it's a half-day, not a loop tick.

### Flake root-cause investigation (2026-06-28, follow-up, no code change)

Narrowed the "no such table" flake cheaply (without re-running the migration):
- **Ruled OUT parallel test execution.** There is no `maxParallelForks` / `forkEvery` /
  `junit.jupiter…parallel` / `org.gradle.parallel` config anywhere — Gradle's `Test` default is
  serial (one fork). So the flake is **not** a cross-test in-memory-DB collision; disabling
  parallelism is a non-fix.
- **CORRECTED root cause (probe-backed): a schema-create ordering race, NOT concurrent access.**
  A throwaway probe stress-tested the *current* (sync) store with 60 concurrent `applyDelta`
  writes + a concurrent flow read on its single JDBC connection. It failed **12/12** — but with
  `SQLITE_ERROR: cannot start a transaction within a transaction`, i.e. a *transaction/locking*
  error, **never** `no such table`. So concurrency produces a *different* error than the flake.
  The migration flake's `no such table` means the table genuinely **isn't there yet** →
  `ContentStore.create()`'s `ContentDb.Schema.create(driver)` (wrapped `.synchronous()`) did not
  reliably finish creating the schema before the first write ran. That is a **create-ordering
  race**, and the fix is to make `create()` **`suspend`** and **`.await()`** the schema build
  (deterministic), NOT a dispatcher confinement.
- **Separate finding from the probe (fixed 2026-07-14):** the single JDBC connection cannot run
  **concurrent transactions**. `ContentStore` now enforces that invariant with a per-instance,
  reentrant AtomicFU gate around every public mutation and the multi-query notification snapshot.
  Correctness no longer depends on `SyncEngine` being the only writer or retaining serial page
  delivery. Ordinary single-query reads remain outside the gate. This was a distinct latent race,
  not the web-migration flake's `no such table` root cause.
- **Async migration changes the gate, not the invariant.** The current monitor cannot enclose a
  suspending generated query. When `generateAsync` lands, replace it in the same change with one
  KMP suspend-aware single-writer boundary (prefer `Mutex.withLock`, or a single-writer actor if
  measurement justifies it). Route **every** mutator and the composite notification/Now snapshots
  through that boundary; do not retain a sync lock around a suspend call. Because `Mutex` is not
  reentrant, locked public methods call private unlocked query helpers rather than one another.
  Headless Android/iOS entry points launch these suspend operations on the injected DB dispatcher.
- **First step for the dedicated session (REVISED):** make `ContentStore.create()` `suspend` +
  `ContentDb.Schema.create(driver).await()` (drop `.synchronous()` on the create path), thread
  the suspend through the test helpers (`freshStore`/`store`/`freshContentStore` → suspend → their
  tests `runBlocking`), then run `:client:desktopTest` ~10× to confirm `no such table` is gone.
  Only then proceed to the main-code + test-conversion sweep above. (Earlier draft suggested a
  single-dispatcher confinement first — the probe shows that would chase the wrong cause.)

This is why the migration is a deliberate session, not a loop tick: it touches the data layer's
concurrency model, and that must be understood + verified, not patched hopefully.

## Why

The only web SQLDelight driver is `app.cash.sqldelight:web-worker-driver` (2.3.2,
wasmJs-published ✓), and it is **async**. SQLDelight will only generate async query
APIs when `generateAsync = true`. The current DB layer is **synchronous**, so the web
driver can't be used until the generated queries (and their call sites) are async.

Good news from sizing it (2026-06-28): the surface is **small** and the sync drivers
keep working — SQLDelight runs async-generated code on a synchronous driver (it
completes immediately). So Android/desktop/iOS keep `AndroidSqliteDriver` /
`JdbcSqliteDriver` / `NativeSqliteDriver`; only the **code** becomes suspend.

## Exact surface (verified against `ContentStore.kt` + callers)

1. **`apps/client/build.gradle.kts` → `sqldelight { databases { create("ContentDb") { … } } }`**
   add `generateAsync.set(true)`. (Keep the `sqlite-3-38-dialect` + `verifyMigrations`.)

2. **`ContentStore.kt` — replace the monitor with the suspend-aware writer boundary, then make
   one-shot queries + writes `suspend`** (reactive invalidation remains flow-based):
   - `activeCards()` → `suspend fun activeCards()` using `q.activeCards().awaitAsList()`.
   - `cursor()` → `suspend fun cursor()` using `q.getCursor().awaitAsOneOrNull()`.
   - Every public mutator (`applyDelta`, wipes, membership/schema, outbox, hide, surfacing,
     notification config/log/reservation) → `suspend` and one `Mutex.withLock` boundary; generated
     insert/update/delete/transaction calls become suspend under `generateAsync`.
   - Composite `notifSnapshot()` and revision-driven `NowContent` snapshot → `suspend` under the
     same boundary, using private unlocked await helpers so they cannot deadlock on a non-reentrant
     mutex.
   - `activeCardsFlow()` / `activeHubsFlow()` and invalidation flows keep
     `asFlow().mapToList()`; any multi-query projection still gathers one version-consistent
     snapshot through the writer boundary rather than `combine`-mixing table versions.

3. **Callers — thread `suspend`** (most are already in coroutine/suspend scope):
   - `SyncEngine.kt`: `contentStore.cursor()` (line ~101) and `contentStore.applyDelta(…)`
     (line ~102) are inside the suspend `loadPage` (it `await`s `syncClient.fetchPage`) —
     just add `await`/keep them in scope. `activeCardsFlow().collect{…}` / `activeHubsFlow`
     unchanged.
   - `wipe()` is called via a `clearCache: () -> Unit` lambda passed to `AuthEngine` (the
     iOS/desktop/android shells pass `clearCache = { cs.wipe() }`). **Change `AuthEngine`'s
     param to `suspend () -> Unit`** — both invocations are *already* suspend
     (`AuthEngine.signOut()` line ~113, `loadMemberships()` line ~294), so the lambdas just
     become suspend lambdas (Kotlin infers); no shell restructuring. **Confirmed clean.**
   - `activeCards()` — **confirmed used only in tests** (real code reads `activeCardsFlow`).
     `SyncEngineTest` already wraps every case in `runBlocking`, so the now-suspend
     `applyDelta`/`activeCards`/`cursor` calls work as-is — **no test rewiring.**
   - **The only compile-time unknown is the SQLDelight-async API itself** (the `transaction {}`
     block under `generateAsync`, and the exact `awaitAsList`/`awaitAsOneOrNull`/`.await()`
     names) — resolve by following the compiler.
   - UI, Auth, Hub, Now, and headless callers must enter through the injected background DB
     dispatcher; a Compose/main callback must never block waiting for the writer boundary.

4. **Startup is unchanged.** The shells construct `ContentStore(DriverFactory().createDriver())`
   eagerly (desktop `Main.kt`, android `MainActivity`); `createDriver()` stays sync — only the
   *queries* become suspend, run later in coroutines. No startup refactor needed for the
   existing platforms.

## Verification (all required before PR)

- `:client:desktopTest` green (the engine + ContentStore + feed tests exercise this path).
- `:androidApp:assembleDebug` compiles (Android target).
- iOS targets compile (`:client:compileKotlinIosArm64` / simulator) — no device run available;
  also execute the NativeSqliteDriver writer/snapshot concurrency test on the simulator.
- Run the deterministic all-writer + composite-snapshot concurrency contract on JDBC and Native;
  verify no nested transaction, no mixed-version snapshot, and no main-thread blocking.
- Smoke-install on the Pixel + verify sync→render still works (the DB behavior is identical;
  this confirms async-on-`AndroidSqliteDriver` at runtime).

## Then (web, separate work)

Once async lands: add the `wasmJs { browser() }` target + `wasmJsMain` deps
(`web-worker-driver`, ktor wasmJs/js engine, coil), write the 6 actuals
(`DriverFactory.wasmjs` = `WebWorkerDriver` + a bundled sqlite wasm worker; `PlatformActions`
= `window.open`/`tel:`/`mailto:`; QR trio = unsupported stubs), the `index.html` + `main()`
(`ComposeViewport`), and a deploy path. Adaptive layout per
`designs/DESIGN-BRIEF-full-adaptive.md` (operator-gated, ADR 0008).

## Risk / rollback

Bounded + behaviour-preserving (async-on-sync-driver completes synchronously), but it does
touch the shipping DB API. If any caller can't be made suspend cleanly, or an Android runtime
regression appears, revert is a single `generateAsync` flip + the suspend signatures. Don't
ship without the Pixel smoke check.
