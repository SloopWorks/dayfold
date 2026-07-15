# Dayfold Runtime, Concurrency, and Render-Isolation Implementation Plan

> **Status:** ADR 0058 was accepted by the operator in-session on 2026-07-14.
> PR 1 plus the runtime/session, engine-hardening, immutable-command,
> platform-lifecycle, stable Compose-boundary, and route-level render-isolation
> work through the bounded portion of Task 14 are
> implemented and locally verified, except for the explicitly unchecked PR 2
> extraction/race-test items. Task 14's per-entity row subscriptions/proof and
> Task 15's state-keyed route effects remain open; PRs 5–6 remain staged.
> Checkbox steps are the handoff contract for implementation.

**Goal:** Make Dayfold's shared client lifecycle-safe and race-safe, prevent
off-main Compose notifications, enforce the SQLite single-writer invariant,
eliminate stale-session and stale-callback commits, and narrow Compose
subscriptions without changing user-visible behavior.

**Architecture:** Keep one Redux store and one process-shared `ContentStore`.
`DayfoldRuntime` becomes the small composition/lifecycle root for the store,
session coordinator, content bridges, sync coordinator, and feature engines.
Family-content bridges are family/session-owned and are cancelled and joined at
tenant replacement; only truly device/process-local collectors persist across
sessions. The database enforces its own serialization because Android/iOS
headless paths can run without a foreground runtime. Platform hosts provide a
serial UI-thread `NotificationContext`; effects run in runtime-owned background
scopes and only publish immutable results through Redux actions. Compose receives
`StableStore<AppState>` plus a stable command wrapper and binds route/feature/
leaf projections instead of the whole `AppState`.

**Primary code:** `apps/client`, `apps/ui`, `apps/androidApp`, iOS and desktop
entry points, Android/iOS headless notification glue, `apps/swip-wiring` debug
state consumers, architecture/process docs.

**Decision sources:** ADR 0013, ADR 0020, ADR 0038–0044, ADR 0047, ReduxKotlin
Rules C/E/F/G/I, and the adversarial review that preceded this plan.

---

## Global constraints

- Preserve `network → DB → Redux store → UI`; network responses never write
  render state directly.
- Keep reducers pure and IDs/timestamps minted at the command edge.
- Keep one store. Do not introduce a registry, per-feature stores, or
  `ModelState` as part of this work.
- Do not move the existing engines into Redux middleware. Use accepted ADR 0058's
  explicit runtime-owned effect and lifecycle contract.
- Every coroutine has one clear owner. Code receiving an injected scope must
  not cancel that scope; the creator cancels it.
- Runtime-retained dependencies are application/process-safe. Activities,
  controllers, views, lifecycle owners, permission launchers, and native sign-in
  UI adapters remain host-scoped and pass immutable results into commands.
- `CancellationException` is always rethrown. Use `NonCancellable` only for the
  minimum local security cleanup required during sign-out.
- Store notification callbacks are serial and FIFO. Never use a multi-threaded
  notification executor.
- All SQLDelight writes are serialized inside `ContentStore`, including writes
  from headless notification receivers/delegates.
- Token/auth commits require an unchanged identity epoch; family DB/Redux
  commits additionally require the captured family ID.
- No JVM-only synchronization primitives in common code. Use a KMP-portable
  gate or a small `expect`/`actual` seam.
- Do not fold the deferred SQLDelight async/web migration into this refactor.
  Keep new seams compatible with that future migration and update its plan.
- Preserve all current route, deep-link, process-death, background-notification,
  fake-backend, debug drawer, and SWIP behavior.
- TDD each race with deterministic barriers; use coroutine virtual time for the
  targeted runtime/engine tests that own an injected test scheduler. Do not use
  timing sleeps or retries as proof.
- Run Gradle from `apps/` with JDK 17. Never use `--no-verify`.

## Non-goals

- No visual redesign, new feature, navigation-library migration, or new backend
  API.
- No multiple Redux stores.
- No normalized `Map<Id, Entity>` rewrite unless profiling in PR 6 shows list
  lookup cost is material at the current ≤200-row scale.
- No WorkManager/BGTask background-sync implementation; this plan only leaves a
  safe runtime/command entry point for that queued work.
- No web target enablement; only preserve and document the future contract.

## Target ownership model

```text
Android ViewModel / iOS controller / Desktop application
                         │
                  DayfoldRuntime
        ┌────────────────┼───────────────────┐
        │                │                   │
  process job       identity epoch      stable commands
  + device bridge   + auth/family jobs  + lifecycle API
        │                │                   │
        │       ┌────────┴────────┐          │
        │  SessionCoordinator  SyncCoordinator
        │       │                 │
        └──── feature engines / clients ─────┘
                         │
                   ContentStore
             (owns DB write serialization)

Headless Android/iOS callbacks ──────────────┘
```

Runtime, authentication-operation, and family/session lifetimes are distinct.
Device-local bridges survive login changes; family-content bridges and
sync/Hub/member work are cancelled and joined at tenant replacement. Restore,
provider exchange, and `whoami` run in a replaceable runtime-owned auth child
before a family session necessarily exists.

## Delivery sequence

| PR | Scope | Risk retired |
|---|---|---|
| 1 | Store notification thread + DB serialization + existing synchronous DB seams off-main | Off-main Compose mutation; concurrent SQLite transactions; UI stalls on the new gate |
| 2 | Session coordinator + runtime lifecycle | Cross-tenant late commits; leaked engine scopes; refresh races |
| 3 | Engine and callback correctness | Sync storms; swallowed cancellation; stale Hub results; callback TOCTOU/double dispatch |
| 4 | Stable Compose boundary + narrow subscriptions | Root recomposition and non-skippable raw `Store` propagation |
| 5 | AppState/reducer slicing | 100+ field model and root-reducer readability/agent-context cost |
| 6 | Notification atomicity, performance evidence, platform/docs sweep | Duplicate notifications; projection hot spots; path drift |

PR 1 is an independent correctness fix and may land before Task 0 is ratified.
Its Auth/Hub dispatcher changes only preserve ADR 0013's already-accepted
off-main-effects rule while introducing the blocking DB gate; they do not adopt
the proposed runtime/engine ownership model.
The ADR 0058 runtime/effect-ownership gate for PRs 2–6 is satisfied.

---

## Task 0 — Architecture gate: formalize effect and runtime ownership

**Files**

- Create: `adr/0058-client-runtime-and-effect-ownership.md` (verify the next free
  ADR number before creating it)
- Modify: `adr/decisions-index.md`
- Modify after acceptance: `docs/architecture.md`

**Decision to record**

- Preserve ADR 0013's pure reducers, off-main effects, UI-thread notification,
  render isolation, mint-at-edge, and state-keyed lifecycle requirements.
- Supersede only “effects originate only in middleware.” Dayfold effects may
  originate in runtime-owned commands/engines because OS lifecycle and headless
  callbacks are clearer there, provided they remain cancellable, off-main, and
  publish results through actions/DB bridges.
- `DayfoldRuntime` is a composition/lifecycle root, not a domain service.
- `ContentStore` owns database serialization independently of runtime lifetime.

- [x] Draft the ADR with alternatives: middleware-only, current
      independent engines, one god runtime, and the recommended runtime-owned
      command model.
- [x] Include the identity/family epoch tenant boundary and scope-ownership contract as
      normative requirements.
- [x] Obtain operator acceptance before PR 2. Do not silently mark the ADR
      Accepted.
- [x] Track the decision explicitly as `backlog/operator-inbox.md` INB-33.
- [x] Add ADR 0058 to the decision index.
- [x] Once accepted, update the ADR status and architecture description.

**Gate:** Satisfied 2026-07-14; PR 2 may proceed.

---

# PR 1 — Threading foundation: UI notifications and database serialization

## Task 1 — Add the main-thread `NotificationContext` platform seam

**Files**

- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/MainNotificationContext.kt`
- Create: `apps/client/src/androidMain/kotlin/com/sloopworks/dayfold/client/MainNotificationContext.android.kt`
- Create: `apps/client/src/iosMain/kotlin/com/sloopworks/dayfold/client/MainNotificationContext.ios.kt`
- Create: `apps/client/src/desktopMain/kotlin/com/sloopworks/dayfold/client/MainNotificationContext.desktop.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Reducer.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/MainNotificationContextTest.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/CreateAppStoreEnhancerTest.kt`

**Interface**

```kotlin
expect fun mainNotificationContext(): NotificationContext

fun createAppStore(
  notificationContext: NotificationContext,
  initial: AppState = AppState(),
  debug: Boolean = true,
  extraEnhancer: StoreEnhancer<AppState>? = null,
): Store<AppState>
```

Make the production parameter required so a new call site cannot silently
deliver Compose notifications on the dispatching worker. Tests use a separate
`createTestAppStore(..., notificationContext = NotificationContext.Inline)`
helper or pass `Inline` explicitly.

- [x] Write a desktop test dispatching from a worker thread and assert the
      subscriber runs on the desktop UI event thread.
- [x] Add a queue-backed test proving callbacks execute one at a time and in
      dispatch order.
- [x] Implement one serial main-queue context per platform; inline delivery is
      allowed only when no earlier queued delivery can be overtaken. Add a mixed
      worker/main dispatch test proving FIFO order.
- [x] Implement Android with main `Looper` and `Handler.post`; do not use a
      coalescing helper whose inline fast path can overtake queued notifications.
- [x] Implement iOS with a main-thread check plus `dispatch_async` to the main
      queue.
- [x] Implement Desktop with the Compose/Swing event-dispatch thread.
- [x] Pass the supplied context through both debug and release store factories.
- [x] Verify on-target dispatch is inline only when the serial queue is empty and
      off-target dispatch is posted; do not add one frame of latency to an
      uncontended main-thread navigation action or violate FIFO under contention.

## Task 2 — Enforce database serialization inside `ContentStore`

**Files**

- Modify: `apps/client/build.gradle.kts` only if an explicit KMP lock dependency
  is required
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ContentStore.kt`
- Modify: `apps/client/src/iosMain/kotlin/com/sloopworks/dayfold/client/IosContentStoreHolder.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/ContentStoreConcurrencyTest.kt`
- Test: notification snapshot barriers in `ContentStoreConcurrencyTest.kt`
- Test: `apps/client/src/iosTest/kotlin/com/sloopworks/dayfold/client/ContentStoreNativeConcurrencyTest.kt`
- Test: `apps/androidApp/src/androidTest/kotlin/com/sloopworks/dayfold/android/ContentStoreAndroidConcurrencyTest.kt`

**Design**

- Use one reentrant KMP write gate per `ContentStore` instance.
- Wrap every mutating public method, not merely SQLDelight `transaction` calls:
  sync delta/wipes, schema reconciliation, membership replacement, outbox
  transitions, hide/unhide, surfacing, notification config/log/reservation, and
  any future write.
- Gather `notifSnapshot()` under the same gate so it cannot combine rows from
  different write versions.
- Keep reactive SQLDelight flows asynchronous; do not place Compose or network
  work under the DB gate.
- Synchronize iOS singleton initialization. Volatile publication alone does not
  make the Elvis construction atomic.

- [x] First add a deterministic failing stress test that launches concurrent
      `applyDelta`, outbox, membership, surfacing, notification-config, and wipe
      writes against one JDBC-backed store.
- [x] Assert no `cannot start a transaction within a transaction`, cursor/outbox
      invariants remain valid, and the final database is readable.
- [x] Add a snapshot/write barrier test proving `notifSnapshot()` is internally
      consistent.
- [x] Repeat the write/snapshot contract with `NativeSqliteDriver` on the iOS
      simulator; JDBC-only success is not platform-parity evidence.
- [x] Repeat it against an isolated `AndroidSqliteDriver` database in Android
      instrumentation; delete the test database after the run.
- [x] Implement the gate and route every write through it.
- [x] Add a comment that a single instance/connection is necessary but not
      sufficient for single-writer correctness.
- [x] Make `IosContentStoreHolder.get()` atomic.
- [x] Test the iOS holder's factory logic through an injectable/common singleton
      helper where feasible.
- [x] Update `specs/web-async-db-migration-plan.md`: its “production has one
      writer” statement is stale; document the new gate and how the later suspend
      migration will replace/adapt it.

## Task 2b — Keep existing UI-triggered database seams off-main

The new synchronous gate must not turn foreground sync contention into an
Activity/controller event-loop stall. This is a prerequisite safety correction
under accepted ADR 0013 and is also consistent with ADR 0058; it does not yet
implement ADR 0058's runtime ownership.

- [x] Inject a KMP `databaseDispatcher` into `AuthEngine` and `HubEngine` with a
      background default; existing call sites remain source-compatible.
- [x] Run membership-cache load/save/clear and Hub toggle/delete/retry/hide/unhide
      writes on that dispatcher.
- [x] Capture Hub session identity, clock, and operation ID at the command edge
      before changing dispatcher.
- [x] Make sign-out and dead-session token/cache/terminal-state cleanup minimally
      `NonCancellable`, then propagate cancellation.
- [x] Add deterministic held-dispatcher tests proving the caller/UI dispatcher
      stays responsive and cancellation cannot strand tenant data.

## Task 3 — PR 1 verification

- [x] `cd apps && JAVA_HOME=<jdk17> ./gradlew :client:desktopTest`
- [x] Repeat `ContentStoreConcurrencyTest` at least 20 times without sleeps or
      retries masking failures.
- [x] `./gradlew :client:compileDebugKotlinAndroid :client:compileKotlinIosArm64`
- [x] `./gradlew :client:iosSimulatorArm64Test`
- [x] `./gradlew :ui:linkDebugFrameworkIosSimulatorArm64`
- [x] Confirm existing `createAppStore` enhancer ordering tests still pass.
- [x] Fresh-context review specifically checks serial notification delivery and
      that no `ContentStore` mutation escaped the gate.

---

# PR 2 — Session boundary and `DayfoldRuntime`

## Task 4 — Introduce immutable session context and single-flight refresh

**Files**

- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/SessionCoordinator.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AuthEngine.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/SyncClient.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/HubClient.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/SessionCoordinatorTest.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/SessionBoundaryTest.kt`

**Interfaces (shape, not token-printable data DTOs)**

```kotlin
// Opaque classes with redacted toString; never actions, state, DevTools, or SWIP data.
class AuthSessionContext internal constructor(/* epoch + credentials */)
class FamilySessionContext internal constructor(/* auth context + familyId */)

class SessionCoordinator {
  fun install(session: Session): AuthSessionContext
  fun authSnapshot(): AuthSessionContext?
  fun familySnapshot(familyId: String): FamilySessionContext?
  fun rotate(
    expected: AuthSessionContext,
    session: Session,
  ): AuthSessionContext?
  fun invalidate(expectedEpoch: Long? = null): AuthSessionContext?
  suspend fun <T> authorizedCall(
    context: AuthSessionContext,
    call: suspend (AuthSessionContext) -> T,
  ): T
  fun isCurrent(context: AuthSessionContext): Boolean
  fun isCurrent(context: FamilySessionContext): Boolean
}
```

The runtime owns and cancels authentication/family jobs; the coordinator owns
only identity epoch and refresh serialization. `install`, `rotate`, `invalidate`, and
`authSnapshot` operate on the complete identity epoch/session atomically.
`familySnapshot` succeeds only for a selected family and captures identity epoch
plus family for tenant-bound work. This lets restore, `whoami`, create-family,
and join flows run with a valid token while no family exists. `rotate` fails if
the expected auth context is stale and replaces credentials without changing
the identity epoch. `install` allocates a new epoch; `invalidate` advances it,
clears the active context, and returns the old opaque context for bounded remote
sign-out. Auth commits validate identity epoch; family DB/Redux commits validate
identity epoch plus family. Contexts expose only vetted request helpers, redact
`toString`, and never enter logs, actions, Redux state, DevTools, or SWIP.

- [x] Write a test where two simultaneous 401 responses yield exactly one refresh,
      one token-store write, and one `SessionRotated` dispatch.
- [x] Cancel the caller that first observed the 401 while another waiter remains;
      the coordinator-owned refresh continues once, whereas epoch invalidation
      cancels it for everyone and clears the single-flight slot.
- [x] Write a test where sign-out invalidates the epoch while refresh is blocked;
      releasing the refresh must not save or dispatch the rotated session.
- [x] Write a stale-401 test where another caller has already rotated the token:
      retry once with the current same-epoch session before starting a refresh.
      Only a still-current token may enter the single-flight refresh path.
- [x] Capture one `FamilySessionContext` for an entire sync drain. Stop reading family
      and token independently on every page/outbox request.
- [x] Change `SyncClient` transport calls to accept the captured family/token (or
      `FamilySessionContext`) explicitly; remove the independent provider lambdas that
      can mix families/tokens across pages.
- [x] Route Auth, Sync, and Hub authorized calls through the coordinator; delete
      their duplicate refresh implementations.
- [x] Before every token, DB, or Redux commit originating from network work, check
      that the captured context is current.
- [x] Test restore/sign-in and create/join-family with a valid auth session but no
      family; no dummy family ID is permitted.

## Task 5 — Build `DayfoldRuntime` with explicit job ownership

**Files**

- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/DayfoldRuntime.kt`
- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/DayfoldRuntimeFactory.kt`
- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ContentBridge.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/DayfoldRuntimeTest.kt`

**Runtime responsibilities**

- Own store creation, clients, `SessionCoordinator`, feature engines, clocks/ID
  generators, and the root `SupervisorJob`.
- Own three explicit children: a runtime/device child for truly device-local
  bridges; a replaceable authentication-operation child for restore/provider
  exchange/`whoami` before a session exists; and a replaceable family/session
  child for family-content bridges plus sync/Hub/member work.
- Expose idempotent `start()`, `resume()`, `pause()`, synchronous `cancel()`, and
  suspend `awaitClosed()`.
- Serialize lifecycle transitions through one narrow runtime mutex/state machine;
  concurrent platform callbacks must not create duplicate workers.
- Define the backgrounding policy explicitly: bridges remain active; polling
  stops; the active drain may finish under epoch checks, but a pending rerun is
  held until resume. This is the recommended initial policy: it preserves
  committed page/outbox progress, avoids cancellation-as-error churn, and starts
  no new polling work in background. OS-scheduled background sync remains a
  separate explicit entry point.
- Guarantee that `cancel()` immediately closes the publication boundary, rejects
  new work, and starts cancellation without blocking a non-suspending platform
  callback. `awaitClosed()` joins owned children and closes owned resources. No
  action may dispatch after `cancel()`; tests call `awaitClosed()` before
  asserting resource teardown.

- [ ] Extract the six DB projection collectors from `SyncEngine.start()` into
      `ContentBridge`; keep one writer per Redux slice. Classify each collector:
      cards/hubs/tree/Now/surfacing are family-owned, while only genuinely
      device-local state may remain runtime-owned.
- [x] Write lifecycle tests for repeated start/resume/pause/cancel/await calls.
- [x] Write a “cancel while collector/network is blocked” test; after cancel,
      release the block, await closure, and assert no dispatch.
- [x] Do not inject one shared scope into engines that still call `scope.cancel()`.
      Remove engine scope ownership first or give each engine an explicitly owned
      child that only the runtime cancels.
- [x] Move schema reconciliation and initial bridge setup off the UI thread while
      preserving “reconcile before first projection” ordering.
- [x] Inject a background DB dispatcher and run every synchronous `ContentStore`
      query/mutation there, including Auth cache seams and Hub/Now UI commands.
      A deterministic test must hold the DB gate, issue a command from the UI
      dispatcher, and prove the UI dispatcher remains free while work waits.

## Task 6 — Make sign-out and session expiry atomic at the application boundary

**Files**

- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AuthEngine.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/DayfoldRuntime.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Reducer.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/SessionBoundaryTest.kt`

- [x] On sign-out/session-expiry, invalidate the epoch before waiting on any
      potentially slow server sign-out.
- [x] Capture the old session first, then invalidate/cancel/clear locally. A
      bounded best-effort server sign-out may use the captured access token, but
      it cannot delay or mutate the local terminal state.
- [ ] Cancel and join cached-membership reconciliation, sync, Hub audience/tree,
      and other session-bound jobs.
- [x] Perform local token/cache cleanup even if server sign-out fails.
- [x] Clear the DB before publishing the terminal signed-out state, with bridge
      emissions unable to restore stale tenant data afterward.
- [x] Close family publication, cancel and join old family-content collectors,
      then wipe/reset and start collectors for the new family. Device-local
      collectors remain independent and must not publish tenant content.
- [ ] Test blocked sync → sign-out → release sync: DB stays empty and route remains
      SignIn.
- [x] Test cached `whoami` reconcile → sign-out → release response: memberships
      remain empty.
- [x] Test family/epoch replacement with the same races, even before a family
      switcher UI exists.
- [x] Deterministically block an old-family projection after it reads but before
      dispatch, replace/wipe the family, then release it; no old content action
      lands and the new family's collectors start exactly once.

## Task 7 — PR 2 verification

- [ ] Run targeted `SessionCoordinatorTest`, `SessionBoundaryTest`, and
      `DayfoldRuntimeTest` cases under coroutine virtual time with injected test
      dispatchers. Leave unrelated existing `:client:desktopTest` tests on their
      appropriate real/test dispatchers, then run the full suite normally.
- [x] Verify every broad exception handler rethrows cancellation.
- [x] Verify a runtime can be constructed with fake clients, fixed clock, fixed ID
      source, inline notification context, and deterministic dispatcher.
- [x] Confirm no runtime class imports Android/UIKit/Swing types.

---

# PR 3 — Engine simplification and callback correctness

## Task 8 — Replace queued `syncNow()` calls with a conflated coordinator

**Files**

- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/SyncCoordinator.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/SyncEngine.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/HubEngine.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/SyncCoordinatorTest.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/SyncEngineTest.kt`

**Design**

- `SyncEngine` performs one captured-context drain and owns no poll loop or scope.
- `SyncCoordinator` has one worker and a conflated request signal: while a drain is
  active, any number of requests schedule at most one rerun.
- Poll, resume, manual refresh, outbox mutation, and push/background hooks all call
  `requestSync(reason)`.
- Emit sync status only when it changes; avoid repeated `SyncStarted`/`Succeeded`
  churn for a no-op/conflated request.

- [x] Test 100 concurrent requests produce one active drain plus at most one rerun.
- [x] Test requests arriving during the rerun schedule at most one further pass.
- [x] Test pause/close cancellation propagates without `SyncFailed`.
- [x] Preserve page ordering, full-resync, outbox rebase, and revocation semantics.
- [x] Replace every production `scope.launch { syncEngine.syncNow() }` call with a
      reasoned sync request.

## Task 9 — Correlate Hub work and split lifecycle from network serialization

**Files**

- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Model.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Reducer.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/HubEngine.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/HubEngineTest.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AuthReducerTest.kt`

- [x] Change result actions to carry correlation data, e.g.
      `HubTreeLoaded(hubId, tree)` and
      `HubAudienceLoaded(hubId, requestId, audience)`.
- [x] Hub tree collectors are family/session-owned and capture a
      `FamilySessionContext`; actions carry tenant generation plus request data.
      Reducers ignore results not matching the current generation, hub, or
      request.
- [x] Give the tree collector, audience load, and audience mutation separate jobs/
      narrow mutexes. `closeHub` must not wait behind a slow audience request.
- [x] Preserve PR 1's injected DB-dispatcher boundary for Hub local commands;
      runtime ownership may replace the dispatcher provider but must not move the
      writes back to the Compose/Activity caller dispatcher.
- [x] Test cancelled hub A collector emitting after hub B opens; hub B state must
      remain unchanged.
- [x] Test close while audience load is blocked; close completes and no late
      audience action lands.
- [x] Remove dispatch from the low-level subscription cleanup method. Navigation
      and cleanup are owned once by the command layer in Task 11.

## Task 10 — Simplify Auth and Now engine concurrency

**Files**

- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AuthEngine.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/NowEngine.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ContentStore.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AuthEngineTest.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/NowEngineTest.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/NowContentConsistencyTest.kt`

- [x] Cancel the previous restore reconciliation before starting another.
- [x] Do not hold one Auth mutex across every unrelated network operation. Keep
      serialization only around shared session mutation; allow independent reads
      with epoch/request correlation.
- [x] Preserve PR 1's injected DB-dispatcher boundary for Auth cache load/save/wipe,
      and move the remaining Now writes onto the runtime-provided DB dispatcher.
      Retain the deterministic held-dispatcher tests.
- [x] Ensure sign-out preempts rather than waits behind a slow auth request.
- [x] Replace broad `catch (Exception)` blocks with cancellation-first handling and
      domain-specific error mapping.
- [x] Convert `NowEngine.noteShown` to one conflated actor/batch loop instead of one
      coroutine per render report; keep the fixed-window, non-starving debounce.
- [x] Route dismiss and flush writes through the same ordered Now command stream
      (plus the ContentStore gate as final protection).
- [x] Replace `nowContentFlow`'s independent `combine(sections, blocks, places)`
      reads with one revision/invalidation-driven, gate-protected snapshot so one
      emission cannot mix table versions. Keep query/decoding work on the injected
      DB dispatcher and hold no gate across Compose or network work.
- [x] Add a deterministic multi-table transaction test that blocks between query
      stages and proves observers receive only complete old or complete new
      `NowContent`, never a mixed projection.
- [x] Preserve write-if-new anti-nag behavior and deterministic clock injection.

## Task 11 — Replace platform callback walls with immutable commands

**Files**

- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/DayfoldCommands.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/DayfoldRuntime.kt`
- Modify: `apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/MainActivity.kt`
- Modify: `apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/AndroidFirebaseSignIn.kt`
- Modify: `apps/ui/src/iosMain/kotlin/com/sloopworks/dayfold/client/MainViewController.kt`
- Modify: `apps/ui/src/desktopMain/kotlin/com/sloopworks/dayfold/client/Main.kt`
- Modify: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedApp.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/DayfoldCommandsTest.kt`
- Test: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/CardHubNavTest.kt`

**Command rules**

- A command receives all IDs shown at tap time. It does not read family/device/hub
  identity later inside a launched coroutine.
- If the command must snapshot an auth/family context itself, it does so synchronously
  before launching and carries that immutable context.
- Each intent causes exactly one navigation action.
- Multi-field navigation changes are represented by one atomic Redux action.

- [x] Add complete command arguments for member approval, invite, pending-device,
      Hub, outbox, notification config, and navigation operations.
- [x] Replace `store.state.activeFamilyId?.let { ... }` inside delayed host
      coroutines with the family ID rendered/passed by the screen.
- [x] Pass the pending device code from the screen/command intent; never re-read
      `store.state.pendingDevice` after launch.
- [x] Replace `OpenHubs` + `SetHubReturnToDetail` with one atomic action carrying
      the return destination.
- [x] Replace Compose dispatch + `HubEngine.closeHub()` dispatch with one
      `commands.closeHub(expectedHubId, destination)` operation.
- [x] Test rapid close hub A → open hub B: delayed cleanup for A cannot cancel or
      clear B.
- [x] Remove duplicated Android/iOS/Desktop callback business logic; hosts should
      construct a host-safe runtime, retain native UI launchers locally, and pass
      stable commands/platform seams only.

## Task 12 — Migrate host lifecycle ownership

**Files**

- Create: `apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/DayfoldRuntimeViewModel.kt`
- Modify: `apps/androidApp/build.gradle.kts` if the ViewModel/runtime dependency
  is not already direct
- Modify: `apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/MainActivity.kt`
- Modify: `apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/AndroidFirebaseSignIn.kt`
- Modify: `apps/ui/src/iosMain/kotlin/com/sloopworks/dayfold/client/MainViewController.kt`
- Modify: `apps/ui/src/desktopMain/kotlin/com/sloopworks/dayfold/client/Main.kt`
- Test: `apps/androidApp/src/androidTest/kotlin/com/sloopworks/dayfold/android/RuntimeRecreationTest.kt`
- Test: `apps/androidApp/src/androidTest/kotlin/com/sloopworks/dayfold/android/MainNotificationContextAndroidTest.kt`
- Test: `apps/client/src/iosTest/kotlin/com/sloopworks/dayfold/client/RuntimeLifecycleIosTest.kt`

- [x] Android retains only application-safe runtime/store dependencies across
      configuration changes in a ViewModel. `onCleared` calls `cancel()`; the
      ViewModel's closeable/owned teardown path awaits or closes resources without
      blocking the main thread. Process death reconstructs from token/DB as today.
- [x] Do not retain `AndroidFirebaseSignIn`, Activity context, Credential Manager
      UI, permission launchers, views, or lifecycle owners in the ViewModel or
      runtime. The current Activity-scoped provider flow obtains an immutable ID
      token and passes it to a runtime command; future Apple/native UI follows the
      same boundary.
- [x] Android `repeatOnLifecycle(STARTED)` calls runtime resume/pause, not engine
      internals.
- [x] iOS remembers one runtime and calls non-suspending `cancel()` from
      `DisposableEffect.onDispose`; foreground notifications call runtime
      resume/pause. Do not launch `awaitClosed()` in a composition scope that is
      cancelled by the same disposal.
- [x] Desktop calls `cancel()`, awaits closure from an application-owned scope,
      then invokes `exitApplication`; owned HTTP/client resources are closed.
- [x] Add an Android recreation test proving one bridge subscription and one poll
      worker remain after repeated Activity recreation, and that the destroyed
      Activity/provider-sign-in adapter is not retained.
- [x] Add an Android instrumentation assertion that a worker-thread dispatch
      reaches subscribers on the main Looper in FIFO order.
- [x] Add an iOS simulator lifecycle test for cancel/dispose while work is blocked
      and a main-thread/FIFO notification assertion.
- [x] Verify headless notification receivers/delegates still use the process-shared
      `ContentStore` safely when no runtime exists.

---

# PR 4 — Stable Compose boundary and render isolation

## Task 13 — Introduce stable store/command wrappers at the UI boundary

**Files**

- Create: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/StableDayfoldCommands.kt`
- Create: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/StablePlatformActions.kt`
- Modify: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedApp.kt`
- Modify: Android/iOS/Desktop entry points and UI tests/snapshot harness call sites
- Test: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/FeedAppHostTest.kt`

**Target signature**

```kotlin
@Composable
fun FeedApp(
  store: StableStore<AppState>,
  commands: StableDayfoldCommands,
  platformActions: StablePlatformActions,
)
```

- [x] Wrap raw store once with `rememberStableStore`; do not pass raw
      `Store<AppState>` through composable parameters.
- [x] Wrap Compose-free `DayfoldCommands` in an `@Stable` UI value/class.
- [x] Wrap `PlatformActions` in an `@Stable` UI value/class as the only native
      handoff seam; do not assume a raw expect/actual object is Compose-stable.
- [x] Leaf composables receive immutable presentation data and remembered callbacks,
      never store/runtime/engine objects.
- [x] Update all tests, snapshot scenes, fake hosts, and previews to the new boundary.

## Task 14 — Replace root whole-state subscription with route projections

**Files**

- Create: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/AppShellSelectors.kt`
- Modify: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedApp.kt`
- Modify: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/TabShell.kt`
- Test: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/RenderIsolationTest.kt`

**Projection**

```kotlin
data class AppShellState(
  val route: Route,
  val detailCardId: String?,
  val currentHubId: String?,
  val deviceResuming: Boolean,
  val backTarget: BackTarget?,
)
```

The exact fields may be refined, but the shell may contain only values needed to
choose the active route/overlay/back owner. It must not include cards, hubs,
members, sync status, notification configuration, or profile data.

- [x] Replace `store.selectorState { it }` with
      `store.value.selectorState(::appShellState)`; `StableStore.value` is the
      raw-store receiver for the ReduxKotlin extension.
- [x] Move route-specific bindings into route hosts so inactive routes are not
      subscribed to their feature state.
- [x] Bind Now, Hubs, Auth, Account, Devices, Members, Invite, and Proximity to
      dedicated immutable view-state selectors.
- [x] Keep list derivation in pure, cheap selectors or memoized/reducer-maintained
      projections, not composable bodies. A granular subscription avoids
      recomposition but still evaluates its selector on every notification on
      the notification thread; do not put full-feed ranking/JSON work there.
- [ ] Key list/row leaves by stable entity ID. Parent lists select stable ordered
      IDs; each row selects only its entity/presentation state.
- [x] Pass top-level/non-capturing selector functions to `selectorState`. A lambda
      closing over a row ID or other changing composable input is remembered by
      store and can freeze the first value; if an input is unavoidable, key the
      composable/subscription by that input and test argument replacement.
- [x] Add recomposition counters proving a surfacing/config/profile change does
      not recompose the root or unrelated route.
- [ ] Add row-level proof: changing one checklist block or member row leaves an
      unrelated row's count unchanged.
- [x] Navigate repeatedly between routes and assert inactive subscriptions are
      disposed and subscriber counts return to baseline; recomposition counters
      alone do not detect leaked subscriptions.

## Task 15 — Route-scoped lifecycle effects

**Files**

- Create or modify route host composables in `apps/ui/src/commonMain/...`
- Modify: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedApp.kt`
- Test: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/FeedAppHostTest.kt`

- [ ] Key Hub tree/audience loads on selected route state (`currentHubId`), not on
      click callbacks alone.
- [ ] Ensure process-death restore, deep links, and DevTools state hydration trigger
      the same runtime command when the state slice appears.
- [ ] Make lifecycle commands idempotent so recomposition/state replay cannot launch
      duplicate work.
- [ ] Test direct preloaded state at Hub detail triggers the same subscription as a
      navigation action.

**Adjacent correctness completed:** Android/iOS local-notification targets are
retained outside Redux until a current family runtime admits `OpenHub`, acknowledged
only after that commit, and cleared at identity/family boundaries. This closes the
pre-restore tap-loss and tenant-crossing races but does not replace the unchecked
state-keyed route-effect work above.

## Task 16 — PR 4 visual and render verification

- [x] `./gradlew :ui:desktopTest`
- [x] `./gradlew :ui:iosSimulatorArm64Test :ui:linkDebugFrameworkIosSimulatorArm64`
- [x] Run `RenderIsolationTest` separately and inspect per-node counts.
- [ ] Run snapshot semantics for feed, hub detail, auth, members, devices, and
      proximity.
- [ ] Run the full macOS golden suite; no golden should change for this behavior-
      preserving refactor.
- [ ] If any golden changes, treat it as a regression until explained; do not
      record new goldens by default.

---

# PR 5 — AppState and reducer slicing

## Task 17 — Define named state slices while preserving one root store

**Files**

- Create under `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/feature/`:
  - `auth/AuthState.kt`
  - `navigation/NavState.kt`
  - `content/ContentState.kt`
  - `hubs/HubState.kt`
  - `now/NowState.kt`
  - `notifications/NotificationState.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Model.kt`
- Modify: selectors and state fixtures across `:client`, `:ui`, fake backend,
  snapshots, and SWIP wiring

**Target root shape**

```kotlin
data class AppState(
  val auth: AuthState = AuthState(),
  val nav: NavState = NavState(),
  val content: ContentState = ContentState(),
  val hubs: HubState = HubState(),
  val now: NowState = NowState(),
  val notifications: NotificationState = NotificationState(),
)
```

- [ ] Inventory every existing field and assign it to exactly one slice; include a
      migration table in the PR description.
- [ ] Keep feature model/actions/reducer/selectors/tests co-located so an app or AI
      change can load one feature's context instead of the current monolithic
      `Model.kt` + `Reducer.kt` pair.
- [ ] Keep transient Compose-only UI state local unless process/navigation restore
      requires Redux ownership.
- [ ] Preserve structural sharing: an unhandled action returns the exact same slice
      instance.
- [ ] Do not normalize content collections in this PR unless PR 6 evidence demands
      it.
- [ ] Add fixture builders so tests no longer repeat 100-field `AppState(...)`
      literals; builders must remain explicit about the slice under test.

## Task 18 — Split the root reducer into pure feature reducers

**Files**

- Create reducer files beside each Task 17 slice
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Reducer.kt`
- Split existing reducer tests by feature; retain one root fan-out test suite

**Design**

- Root reducer handles only true cross-slice/reset fan-out, then delegates.
- A shared action may update multiple slices intentionally, e.g. membership load
  updates auth and route; sign-out replaces all sensitive slices.
- Keep one hand-readable root function. Do not adopt multiple stores or a generic
  reducer framework solely to remove lines.

**Required sign-out/session-replacement reset matrix**

| State category | Sign-out / expiry | Family replacement |
|---|---|---|
| Session, active family, members, devices, invites | Clear | Replace atomically from the new session/family |
| Navigation route, detail stack, pending device/invite route data | Reset to the appropriate auth gate; remove tenant IDs | Reset before exposing the new family's content |
| Cards, content projections, Hub tree/audience, Now-derived results | Clear after DB wipe and prevent old bridge emissions from restoring | Clear old tenant before hydrating new tenant |
| Sync/Hub/Auth in-flight and error/status state | Cancel old epoch and reset | Cancel old epoch and reset |
| Family-scoped notification config/reservations | Clear with tenant DB data | Replace from the new family's local projection |
| Device/OS-local permission and capability facts | Preserve, then re-evaluate if needed | Preserve; never sync across devices |
| Theme, accessibility, devtools, and other non-tenant device preferences | Preserve | Preserve |

Before moving fields, classify every existing field into this matrix. If a field
mixes tenant and device-local concerns, split it rather than applying an
ambiguous whole-slice reset.

- [ ] Move clauses feature by feature with characterization tests green after each
      move.
- [ ] Test that unrelated actions preserve slice identity.
- [ ] Test signed-out/session-expired fan-out clears every sensitive slice.
- [ ] Test the reset matrix for sign-out, expiry, and family replacement,
      including preservation of device-local permission/capability/preferences
      and rejection of a late old-epoch bridge emission.
- [ ] Test profile/identity changes remain consistent across any duplicated
      presentation caches.
- [ ] Update action logging to print bounded summaries, never serialize/log full
      state in release.

## Task 19 — Update debug, analytics, snapshot, and persistence consumers

**Files**

- Modify: `apps/swip-wiring` slice registry, sanitizer, mapper tests
- Modify: Android debug enhancer/lifecycle glue
- Modify: `apps/ui/src/desktopTest/.../snapshot/SnapshotStates.kt`
- Modify: reducer/UI tests constructing `AppState`
- Modify any saved-state/detail-stack restoration code in `MainActivity`

- [ ] Keep SWIP's privacy allowlist no broader than before; nesting state must not
      accidentally register card/hub content.
- [ ] Run the mandatory salted-PII leak test.
- [ ] Preserve detail-stack process-death restore semantics and preloaded route
      behavior.
- [ ] Verify fake backend scenarios still traverse the real DB→store route.
- [ ] Confirm DevTools action/state inspection remains usable with nested slices.

## Task 20 — PR 5 verification

- [ ] `./gradlew :client:desktopTest :ui:desktopTest :swip-wiring:desktopTest`
- [ ] Verify test counts, including `runBlocking<Unit>` tests.
- [ ] `./gradlew :androidApp:assembleDebug`
- [ ] `./gradlew :androidApp:assembleRelease`
- [ ] `./gradlew :client:iosSimulatorArm64Test :ui:iosSimulatorArm64Test`
- [ ] `./gradlew :ui:compileKotlinIosArm64 :ui:linkDebugFrameworkIosSimulatorArm64`
- [ ] Full snapshot semantics and golden suite; expected pixel diff is zero.

---

# PR 6 — Atomic notifications, measured performance, and final path sweep

## Task 21 — Make notification cap/dedup reservation atomic

**Files**

- Modify: `apps/client/src/commonMain/sqldelight/com/sloopworks/dayfold/client/db/Content.sq`
- Create: next SQLDelight migration under
  `apps/client/src/commonMain/sqldelight/com/sloopworks/dayfold/client/db/migrations/`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ContentStore.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/BackgroundNotify.kt`
- Modify: Android/iOS background notification actuals
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/BackgroundNotifyConcurrencyTest.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/NotifStateTest.kt`

**Design**

- Add a local-day bucket and uniqueness for `(subject_key, local_day)`.
- Replace read-plan-post-log with transactional candidate reservation under the
  daily cap, then post only successfully reserved subjects.
- Prefer a possible missed notification after an ambiguous crash over duplicate
  interruption. Document that calm-product tradeoff.
- Do not release a reservation when delivery outcome is unknown. If platform APIs
  expose a definite pre-post failure, a bounded release/retry may be added and
  tested.

- [ ] Write a barrier test running exact and geofence callbacks concurrently;
      assert one subject/day reservation and cap compliance.
- [ ] Write migration tests for existing rows and local-date rollover.
- [ ] Keep notification state local-only and out of Redux persistence/sync.
- [ ] Verify Android and iOS scheduling/tap deep links are unchanged.

## Task 22 — Measure before adding further performance machinery

**Files**

- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/ProjectionPerformanceTest.kt`
- Test: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/RenderIsolationTest.kt`
- Modify production code only where measurements justify it

**Measurements**

- Store notifications and root/route/row recompositions per representative
  action.
- SQLDelight projection query/emission count and JSON decode count for one sync
  page.
- Immediate-resume sync request count after login/create-family/hub mutation.
- Runtime startup work executed before first frame.
- Debug versus release dispatch overhead; full-state serialization must remain
  debug-only.

- [ ] Establish a baseline from the pre-refactor commit or a retained fixture.
- [ ] Set behavioral budgets rather than device-specific millisecond assertions:
      unrelated root recompositions = 0; redundant drain queue depth ≤1; one DB
      bridge collector per projection; unchanged rows do not recompose.
- [ ] If overlapping projections repeatedly decode the same full tables, first
      consolidate only the measured shared projection. Do not create a caching
      layer speculatively.
- [ ] If per-row list lookup is material at realistic maximum data, propose a
      separate normalization change with its own reducer/selector tests.

## Task 23 — Cross-platform and all-path audit

**Production paths**

- Android Activity/ViewModel, lifecycle, deep links, permission controllers,
  config writes, geofence receiver, exact-alarm receiver.
- iOS controller disposal, active/resign observers, notification delegate,
  region callback, exact scheduler, process-shared ContentStore holder.
- Desktop application close and persistent token/DB resources.
- Common fake backend, debug store enhancer, action log, SWIP lifecycle and leak
  registry.
- Future WorkManager/BGTask/push callers through `requestSync(reason)`.

**Verification**

- [ ] Android: repeated rotation/recreation, background/foreground, sign-out during
      sync, notification config toggle, geofence/exact simultaneous wake.
- [ ] Android: distinguish configuration recreation from real process-death
      reconstruction; run both and verify no retained Activity/native UI adapter.
- [ ] iOS: compile/link both targets; simulator smoke for dispose/recreate,
      foreground/background, local notification tap.
- [ ] Run `:client:iosSimulatorArm64Test :ui:iosSimulatorArm64Test` so Native
      concurrency/lifecycle behavior executes rather than merely compiles.
- [ ] Run `:androidApp:connectedDebugAndroidTest` on an emulator/device for main
      Looper, recreation, and lifecycle assertions.
- [ ] Desktop: close with active sync/Hub collector; process exits without leaked
      jobs.
- [ ] Fake backend: every scenario enters and navigates without live network.
- [ ] Release build: no SWIP/debug dependency regression.
- [ ] Web readiness: common runtime uses no JVM/native-only primitive;
      `mainNotificationContext` documents a future wasm inline actual; update
      `specs/web-async-db-migration-plan.md` without enabling the target.

## Task 24 — Documentation and close-out

**Files**

- Modify: `docs/architecture.md`
- Modify: `processes/agent-dev-loop.md`
- Modify: `specs/web-async-db-migration-plan.md`
- Modify: `backlog/now.md` / `backlog/next.md` as work moves
- Add `CHANGELOG.md` entry only if observable behavior changes; a pure refactor
  does not require one

- [ ] Document runtime ownership, identity/family epochs, sync conflation, ContentStore
      serialization, UI notification threading, and host lifecycles.
- [ ] Remove stale comments saying `SyncEngine` alone guarantees one writer or
      `pause()` stops an already-running fetch when it does not.
- [ ] Record final test counts and platform compile/smoke evidence.
- [ ] Run the repo's two review rounds: correctness first, then simplification/
      optimization. Resolve findings before merge.
- [ ] Update ADR/revisit triggers if implementation meaningfully diverges from the
      accepted design.

---

## Final acceptance matrix

| Concern | Acceptance criterion |
|---|---|
| Store threading | Every production Compose subscriber callback runs on its platform UI thread, serially and FIFO |
| Database | Concurrent writes from every foreground/headless source complete without nested-transaction errors |
| Tenant isolation | No old-epoch response can save a token, write family data, or dispatch state after sign-out/family replacement |
| Refresh | Concurrent 401s perform one refresh and one session rotation |
| Lifecycle | Runtime methods are idempotent; host disposal leaves no collectors/polls; no dispatch occurs after `cancel()`; `awaitClosed()` completes teardown |
| Sync | Any request burst queues at most one rerun; page/outbox ordering and full-resync behavior remain correct |
| Callbacks | Commands carry rendered IDs; hub close/navigation dispatch once; stale results are reducer-rejected |
| Render isolation | Unrelated state changes cause zero root and unrelated-row recompositions |
| State readability | `AppState` is composed of named slices; root reducer contains only delegation/cross-slice fan-out |
| Notifications | Concurrent exact/geofence wakes reserve/post each subject at most once per local day and obey the cap |
| Android | Rotation, background/foreground, process death, deep links, and headless notification paths pass |
| iOS | Native compile/link, controller disposal, lifecycle observers, singleton initialization, and notification tap pass |
| Desktop | UI-thread notifications and runtime cancel/await pass; no leaked process jobs |
| Debug/release | SWIP privacy leak test passes; debug tooling works; release remains free of debug-only runtime bytes |
| Web future | Common code remains KMP-portable and the async DB migration plan reflects the new serialization boundary |

## Final command gate

Run from `apps/`:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest :ui:desktopTest :swip-wiring:desktopTest \
    :client:iosSimulatorArm64Test :ui:iosSimulatorArm64Test \
    :androidApp:assembleDebug :androidApp:assembleRelease \
    :ui:compileKotlinIosArm64 :ui:linkDebugFrameworkIosSimulatorArm64
```

Then run the full repository CI-equivalent gates documented in
`processes/agent-dev-loop.md`, the macOS golden suite, and the platform smoke
matrix above. With a configured emulator/device, run
`./gradlew :androidApp:connectedDebugAndroidTest`. Do not merge on desktop tests
or compile-only Native evidence alone.

## Simplification review — decisions already made

- **One store stays.** State slices and narrow selectors solve readability/render
  isolation without cross-store coordination.
- **Runtime stays small.** It owns lifetime and coordinators; it does not absorb
  reducers, ranking, SQL, or platform notification logic.
- **Database safety lives in the database wrapper.** Runtime-only serialization
  would miss headless callbacks.
- **One token-refresh path.** Auth/Hub/Sync do not retain mirrored refresh code.
- **One sync request path.** Poll, resume, manual, mutation, and future push all
  signal the same conflated coordinator.
- **One command boundary.** Android/iOS/Desktop stop duplicating business
  callbacks and delayed `store.state` reads.
- **No speculative entity normalization or extra caching.** Add only if PR 6
  measurements justify it.
- **No forced middleware rewrite.** The ADR records the clearer runtime-owned
  effect model while preserving Redux's purity and dataflow guarantees.
