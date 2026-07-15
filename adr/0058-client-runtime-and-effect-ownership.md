# ADR 0058: Client Runtime and Effect Ownership

## Status

**Accepted** 2026-07-14 (operator accepted in-session). This ADR narrowly
supersedes only ADR 0013 Decision 3's requirement that side effects
originate exclusively in Redux middleware. All other ADR 0013 decisions remain
in force. Composes with ADR 0020's `network → DB → Redux store → UI`
dataflow.

## Context

ADR 0013 correctly established one Redux store, pure reducers, render-isolated
Compose subscriptions, off-main effects, and state-keyed lifecycle. Its
middleware-only effect-origin rule is less well suited to the client that now
exists. Dayfold has process- and session-lifetime work, foreground polling,
headless Android/iOS notification callbacks, platform authentication UI, and
several engines whose ownership must follow host lifecycle rather than a Redux
action stream.

Forcing that work through middleware would hide coroutine ownership and OS
lifecycle boundaries without improving the unidirectional dataflow. Leaving the
current engines independently scoped is also unsafe: duplicated lifecycle and
token-refresh paths make cancellation, stale-session rejection, and shutdown
hard to prove. A durable ownership contract is needed before those engines are
refactored.

## Decision

1. **Keep one Redux store and pure reducers.** Reducers perform no I/O, launch no
   coroutine, and mint no ID or timestamp. IDs and timestamps are captured at
   the command edge. Family-content network results always follow
   `network → DB → Redux`; auth, control, and status results may publish
   explicit Redux actions. UI code does not consume network responses directly.
2. **Permit two explicit effect origins.** Store-centric effects may continue to
   originate in middleware. Lifecycle-, command-, and platform-driven effects
   may originate in runtime-owned commands or feature engines. Effects do not
   originate from reducers or composable rendering. This point alone supersedes
   ADR 0013's middleware-only wording.
3. **Keep effects off the UI thread.** Network, database reconciliation,
   decoding, and other blocking or material work runs on an injected background
   dispatcher. Immutable results are published through DB projections or Redux
   actions. Store subscriber delivery uses a platform UI-thread
   `NotificationContext` that is serial and FIFO.
4. **Make `DayfoldRuntime` the composition and lifecycle root.** It owns store
   construction, clients, the session coordinator, content bridges, sync
   coordination, engines, and the root structured-concurrency job. A replaceable
   authentication-operation child owns restore/provider exchange/`whoami` work
   that establishes a login before any active session child exists. It delegates
   domain logic; it does not absorb reducers, SQL, ranking/presentation logic,
   or platform UI behavior.
5. **Make ownership explicit and structured.** Every coroutine has one owner.
   The runtime owns process/device-local DB-to-store collectors independently of
   login, but family-content collectors belong to a replaceable family/session
   child. On tenant replacement they are cancelled and joined before cache wipe,
   then restarted for the new family; a queued old-family projection therefore
   cannot repopulate Redux after reset. A component given a scope does not cancel
   that scope. Cancellation is rethrown, and shutdown consists of a synchronous,
   idempotent `cancel()` boundary plus a suspending `awaitClosed()` join for
   ordered teardown and tests.
6. **Do not retain host UI objects.** Runtime-retained dependencies must be safe
   across Android Activity recreation and iOS controller replacement. An
   Activity, View, lifecycle owner, UIViewController, permission launcher,
   Credential Manager UI adapter, or object retaining one remains host-scoped.
   The host completes native UI work and passes its immutable result, such as a
   provider token, into a runtime command.
7. **Separate identity lifetime from the family tenant boundary.** The
   coordinator exposes an opaque, redacted `AuthSessionContext` containing the
   identity epoch and credentials, and derives a `FamilySessionContext` only
   when a family is selected. Neither is a data class, neither prints or enters
   Redux/DevTools/SWIP, and no log may contain access or refresh tokens. This
   permits restore, `whoami`, create-family, and join flows while family ID is
   absent. Installing, rotating, and invalidating identity are atomic; family
   selection/replacement is atomic within that identity epoch. Before a late
   token commit, an operation verifies the identity epoch. Before family DB or
   Redux publication it verifies identity epoch plus family. Token refresh
   rotates credentials within the same identity epoch; a different login
   allocates a new epoch, and invalidation advances it. Sign-out/family
   replacement closes publication, cancels and joins the affected children, and
   prevents old-family bridge emissions before wiping/publishing terminal state.
8. **Keep database safety independent of runtime lifetime.** `ContentStore`
   serializes its writes and composite notification snapshots because headless
   platform callbacks can run without a foreground runtime. The runtime is not
   the database lock.
9. **Retain render isolation and state-keyed lifecycle.** Compose receives a
   stable store wrapper and stable commands, and subscribes to the narrowest
   route/feature/leaf projection. Lifecycle loads key on state so process-death
   restoration, deep links, and DevTools hydration behave like interactive
   navigation. Commands and lifecycle entry points are idempotent.
10. **Keep platform hosts thin but authoritative for host lifecycle.** Android
    retains only the host-safe runtime in a ViewModel; iOS and desktop own one
    runtime per controller/application lifetime. Hosts call runtime lifecycle
    methods and native UI seams, not engine internals. Headless entry points use
    the process-shared database boundary directly.

## Rationale

This preserves the useful Redux constraints—deterministic reducers,
unidirectional publication, narrow subscriptions, and testable actions—while
placing cancellation and lifetime where mobile platforms actually expose them.
The runtime is deliberately a small owner/coordinator rather than a service
locator or a second state store. Identity/family contexts make cross-tenant late
commits rejectable by construction, and a host-safe boundary prevents retained
native UI objects from leaking across recreation.

Alternatives considered:

- **Middleware-only effects.** Rejected for lifecycle/headless work because the
  action stream does not own the Activity/controller, foreground state, or
  process callback lifetime. It remains valid for effects naturally caused by
  Redux actions.
- **Independent self-scoped engines.** Rejected because scope cancellation,
  token refresh, and pause/resume ownership remain duplicated and can race.
- **One god runtime containing all application logic.** Rejected because it
  would replace an oversized reducer with an oversized mutable service and
  worsen testing, readability, and AI context locality.
- **Platform-specific runtimes.** Rejected because shared KMP ownership is
  testable once; platform code is needed only for lifecycle and native UI seams.

## Consequences

Positive:

- Coroutine lifetime, session replacement, and shutdown become explicit and
  testable across Android, iOS, and desktop.
- Middleware remains available without being forced around OS-shaped work.
- Native sign-in/permission UI stays recreation-safe.
- Pure reducers, DB-as-source-of-truth, state-keyed lifecycle, render isolation,
  and main-thread subscriber delivery remain normative.

Negative:

- The runtime and session coordinator add concepts that require concurrency and
  platform-lifecycle tests.
- Call sites must distinguish host-scoped native UI seams from runtime-safe
  dependencies.
- Existing engines and callbacks require staged migration; mixing old and new
  ownership indefinitely would be worse than either model.

## Revisit Trigger

Revisit if runtime ownership grows beyond coordination into domain logic, if a
platform cannot provide serial UI-thread notifications, if background execution
requires a materially different process model, or if measured middleware-based
effects prove simpler for a specific feature. Such a revisit must continue to
preserve pure reducers, identity/family checks, off-main work, and narrow UI
subscriptions unless a separate superseding ADR explicitly changes them.
