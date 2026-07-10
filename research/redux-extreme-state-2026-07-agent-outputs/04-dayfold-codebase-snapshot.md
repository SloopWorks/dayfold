# Agent memo 4/4 — dayfold redux architecture snapshot (apps/client + apps/ui)

Raw fleet output for `research/2026-07-10-redux-extreme-state-architecture.md`.
Produced 2026-07-10 by a codebase-exploration agent at commit `d8dd25f`;
unedited. All paths rooted at repo root.

Grounding note: `redux-kotlin` (`org.reduxkotlin`, alpha01), hand-written
root reducer, SQLDelight-as-truth offline-first.

## 1. COUNTS

| Metric | Value | Source |
|---|---|---|
| **Action types** (subtypes of `sealed interface Action`) | **99** | all in `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Model.kt:526-666` |
| Base action interface(s) | **1** (`Action`); plus a separate UI-intent union `CardAction` (9 subtypes, NOT store-dispatched) | `Model.kt:526`; `cards/CardAction.kt:13-38` |
| **Reducer functions** | **1 root** (`rootReducer`, a single 167-line `when(action)`) + 3 pure derivation helpers (`routeFor`, `activeFamilyIdFor`, `ownerFamiliesFor`). **No slice reducers, no `combineReducers`.** | `Reducer.kt:33-200`, `:15-28` |
| **Middleware** | **1** (`actionLog`, dev-only text log) | `Reducer.kt:206-211` |
| **Store enhancers** | devtools enhancer (`devTools(...)`) composed with the middleware, debug-only | `Reducer.kt:218-223` |
| **Selectors** (pure render-time projections) | Core: `feedCards`, `currentDetailCard` (`Selectors.kt`), `nowFeed`+`visibleSubjectKeys` (`NowFeed.kt`), `backAction`/`appHandlesBack` (`BackNav.kt`), `deriveNow` (`NowDerive.kt`), `rank` (`NowRank.kt`), `selectNotifications` (`NowNotify.kt`), timeline presenters (`TimelinePresenter.kt`). ~8 distinct selector fns. | see §2 |
| **`selectorState` call sites (Compose subscription)** | **1** — `store.selectorState { it }` subscribing the **entire** AppState | `apps/ui/.../FeedApp.kt:131` |
| **Distinct effect "engines"** | **4** (`SyncEngine`, `AuthEngine`, `HubEngine`, `NowEngine`) + `OutboxSender` (pure state machine) + `BackgroundNotify` (headless, storeless) | `client/src/commonMain/...` |
| **`store.dispatch` call sites** | 96 in client, 47 in ui = **143 total** | grep |
| **AppState top-level fields** | **57** | `Model.kt:424-522` |
| AppState nesting depth | **Flat / shallow** — 57 sibling fields; ~17 are custom nested immutable types (`Session`, `HubTree`, `NowContent`, `NotifConfig`, `HubAudience`, lists of `Card`/`Hub`/`FamilyMember`…). Deepest chain ~3 (`currentHubTree.blocks[].payload.items[]`). No nested state slices. | `Model.kt:424-522` |

**Kotlin LOC** (excluding `build/`):

| Module | main (common+platform) | test | total |
|---|---|---|---|
| apps/client | commonMain 5556 + android 393 + ios 532 + desktop 94 = **6575** | 6293 | **12868** |
| apps/ui | commonMain 8602 + android 156 + ios 318 + desktop 164 = **9240** | 4854 | **14094** |
| **Combined** | **15,815 main** | **11,147 test** | **26,962** |

**Action count "per feature"** (grouped by `Reducer.kt` section comments):
- Feed/sync/nav: `SyncStarted/Succeeded/Failed`, `CardsLoaded`, `NavToDetail`, `NavBack`, `Back`, `RestoreDetailStack` (~8)
- Hubs (ADR 0006/0030/0045): `OpenHubs/OpenFeed/HubsLoaded/HubsFailed/OpenHub/HubTreeLoaded/HubNotFound/CloseHub/CloseHubToFeed/SetHubReturnToDetail/SetHubFocus/SetHubFilter/HiddenLoaded/SetShowHidden/OpenTimelineDetail/CloseTimelineDetail/OpenAudienceSheet/HubAudienceLoaded/CloseAudienceSheet` (~19)
- Now/Notif bridges (ADR 0043/0044): `NowContentLoaded/SurfacingLoaded/NotifConfigLoaded/LocationPermissionLoaded/NotificationPermissionLoaded` (5)
- Auth/session (S5): ~16 (`AuthRestoring…SignedOut`)
- Invitee-join (S5 slice-2): ~6
- Owner approvals/roster/devices/invite-mint (S6): ~24
- CLI/device approval + deep-link + scan (S6-D/Phase2): ~20

The action count is dominated by **auth/membership/device/invite** flows (~66 of 99), not content.

## 2. PATTERNS

**Action organization.** ONE flat `sealed interface Action` with all 99 subtypes co-located in a single 140-line block at the bottom of `Model.kt` (not package-by-feature — the spec `specs/prototype/08-mobile-client.md:18-30` *envisioned* per-feature `{model,actions,reducer,effects,screen,selectors}` slices with nested `AppState` and slice reducers; **the implementation diverged to flat**). AppState (57 fields) and all DTOs also live in the one `Model.kt`.

**Root reducer composition.** Hand-written single `rootReducer(state, action): AppState` — a giant exhaustive `when (action)` doing `state.copy(...)` (`Reducer.kt:33-200`). Explicit locked decision "no `combineReducers`" (`Reducer.kt:30-31`). `Back` recurses through `rootReducer(state, backAction(state))` (`:52`). Route is *derived* inside the reducer via `routeFor(session, families)` on every auth transition rather than stored independently.

**Async / side effects.** No thunk, no saga, **no async middleware**. All I/O lives in 4 hand-rolled **engine classes** that hold a `Store` reference and `store.dispatch(...)` imperatively. Each engine owns a `CoroutineScope(SupervisorJob()+Dispatchers.Default)` and a `Mutex` serializing its ops (`SyncEngine.kt:32`, `AuthEngine.kt:56`, `HubEngine.kt:29`, `NowEngine.kt:38`). UI callbacks (the ~40 `on*` lambdas of `FeedApp`) are wired in the platform shell to `scope.launch { engine.method() }` (`Main.kt:73-103`, `MainActivity.kt:299-338`). Refresh-on-401 is duplicated in each engine (`SyncEngine.refreshSession`, `AuthEngine`/`HubEngine.callWithRefresh`).

**SQLDelight ↔ store wiring (offline-first).** The DB is the single source of truth (ADR 0020). Flow:
`network /sync → ContentStore.applyDelta (one atomic txn) → SQLDelight → reactive Flow → SyncEngine bridge job → store.dispatch(*Loaded) → selectorState → UI` (`specs/prototype/08-mobile-client.md:148`; `SyncEngine.kt:47-79`). `SyncEngine.start()` launches **6 one-way DB→store bridge jobs**, each the *sole writer* of its slice: `activeCardsFlow→CardsLoaded`, `activeHubsFlow→HubsLoaded`, `hiddenIdsFlow→HiddenLoaded`, `nowContentFlow→NowContentLoaded`, `surfacingFlow→SurfacingLoaded`, `notifConfigFlow→NotifConfigLoaded` (`SyncEngine.kt:54-78`). The API client (`SyncClient`) is only reachable *through* `SyncEngine.drain()` — the UI never calls network directly; it dispatches nothing that writes content (content reaches the store ONLY via the bridges, `Model.kt:524`). Writes go DB-first then bridge-back (no optimistic store path — see mutations below). Single SQLite connection; writes must stay serialized (`ContentStore.kt:47-55` INVARIANT comment).

**Compose subscription.** A **single connected composable** `FeedApp` does `val state by store.selectorState { it }` — the *whole* AppState (`FeedApp.kt:131`). Everything below receives `state: AppState` as a plain param (`ContentHost`, `HubsHost`, all screens). There is **no per-field `fieldState`/narrowed selector usage anywhere** — the code comment explicitly flags this as the scaling lever not yet pulled: *"the whole AppState here; swap to per-field `fieldState`/narrower selectors to scope recomposition"* (`FeedApp.kt:64-66`). UI is a pure `when(route)` gate, no nav library (ADR 0013).

**Threading model.** `createConcurrentStore` (redux-kotlin `concurrent` variant) — thread-safe dispatch because effects dispatch from `Dispatchers.Default`/IO while Compose reads on main (`Reducer.kt:213-223`). No hard main-thread enforcement/assertion; instead relies on: immutable AppState (`val List`), bridge/engine `dispatch` from background, and a documented convention (`MainActivity.kt:113-120` onSaveInstanceState reasons about it). Bridge/poll `start()`/`resume()` guards are non-atomic and documented "must be called from the main thread" (`SyncEngine.kt:45,84`). Android maps foreground↔background to `resume()`/`pause()` via `repeatOnLifecycle(STARTED)` (`MainActivity.kt:239-248`); desktop polls always-on (`Main.kt:65`).

**Devtools.** redux-kotlin-devtools `devTools(DevToolsConfig(instanceId="family-ai"))` enhancer, composed **debug-only** (`createAppStore(debug=BuildConfig.DEBUG)`, `Reducer.kt:218-223`, `MainActivity.kt:168`). Records to a DevToolsHub surfaced in an in-app **debug drawer** (`apps/debugdrawer-redux/.../ReduxDevToolsDebugPlugin.kt`; installed `MainActivity.kt:134-152`). Plus the `actionLog` text middleware → `ClientLog` → drawer Logs panel. Release passes `debug=false` (neither) because each serializes the full AppState per dispatch (`MainActivity.kt:166-167`).

## 3. HOT PATHS

**High-frequency dispatches are deliberately kept OUT of the store.** No scroll/text/animation/location action exists in the 99-action set.

- **Scroll position** — hoisted Compose state, NOT store: `feedListState`/`hubListState = rememberLazyListState()` hoisted up to `FeedApp` so they survive nav/tab swaps (`FeedApp.kt:136-139`, comments `:132-139` explain the hoist is because `AnimatedContent` has no `SaveableStateHolder`). No `snapshotFlow`/`derivedStateOf` on scroll into the store (grep: 0 hits).
- **Text input** — local Compose state only. Only 3 files have text fields (`AuthScreens.kt`, `JoinInviteScreen.kt`, `DeviceApprovalScreens.kt`); each holds the buffer in `remember { mutableStateOf }` and only dispatches a terminal action (e.g. `RedeemRequested(token)`, `CreateFamilyRequested`) on submit. Keystrokes never hit the store.
- **Animation** — predictive-back/shared-transition run on Compose `SeekableTransitionState`/`AnimatedContent`; only the terminal `NavBack`/`NavToDetail` commit dispatches (`FeedApp.kt:342-349`, `:356-382`).
- **Location** — live position is **never** dispatched or persisted (ADR 0014). It is *injected* into the pure `nowFeed(state, nowIso, location, …)` selector at render/notify time (`NowFeed.kt:11-22`, `BackgroundNotify.kt:42-58`). Only coarse OS *permission state* is a store slice (`LocationPermissionLoaded`).

**Ephemeral vs store state.** Rule in practice: ephemeral view state (scroll, text buffers, expand/collapse, transition progress) = local `remember`/hoisted Compose state (~51 `mutableStateOf`/`rememberLazyListState` sites across 15 UI files); durable/shared state (content, session, route, nav stack, filters, toggles like `showHidden`/`hubFilter`) = store. The store even holds some UI-ish state (route substates, busy flags, `inviteMode`, `audienceSheetOpen`) — contributing to the 57-field width.

## 4. PAIN POINTS (visible in code/docs)

- **Whole-state subscription is the acknowledged perf lever not yet pulled** — `FeedApp.kt:64-66` ("swap to per-field `fieldState`/narrower selectors to scope recomposition"). Every dispatch notifies the one root subscriber; recomposition scoping relies entirely on Compose skippability + stable params, not on selector memoization.
- **Spec↔impl divergence on state shape** — `specs/prototype/08-mobile-client.md:18-47` specifies package-by-feature slices, a nested `AppState(session,sync,content,nav,ui)`, slice reducers, and "composables bind the **narrowest memoized slice** … (+ a **recomposition-count CI guard**)". Actual: flat 57-field AppState, one monolithic reducer, one whole-state binding, **and the recomposition-count CI guard does not exist** (grep: 0). This is the primary gap for a scaling discussion.
- **Full-AppState serialization cost** flagged as the reason devtools+log middleware are stripped from release (`MainActivity.kt:166-167`).
- **JSON (de)serialization must stay off the recomposition path** — "the perf finding": payload/privacy decoded at the DB↔store projection boundary on a background dispatcher, never during recomposition (`ContentStore.kt:40-44`). Related: derivation kept off the hot path in `cards/DetailMeta.kt:7`, `cards/TypedCardLogic.kt:7`.
- **Single-writer SQLite invariant** — concurrent `applyDelta`/`wipe` throw "cannot start a transaction within a transaction"; write path must not be parallelized without a single-writer dispatcher (`ContentStore.kt:47-55`).
- **`O(n²)` ranker** tolerated only because "n is small (a calm feed)" (`NowRank.kt:155`) — an explicit scale assumption baked in.
- Duplicated refresh-on-401 logic across all 4 engines (no shared interceptor) — `SyncEngine.kt:187`, `HubEngine.kt:108`, AuthEngine.
- 32 TODO/FIXME/HACK/perf comments across client+ui main source.

## 5. DATA-LOADING PARADIGMS (distinct async patterns)

Six distinct patterns are present:

1. **Cold-start hydration (DB→store bridge, zero-network)** — 6 reactive `Flow` bridges started once; first emission = cached rows. `SyncEngine.start()` `SyncEngine.kt:47-79`; wired `Main.kt:63`, `MainActivity.kt:231`. Plus **DB-first route gate** (ADR 0052): `AuthEngine.restore()` routes off `cachedMemberships()` then reconciles over network in background (`AuthEngine.kt:63-90`).
2. **Initial sync + foreground poll (pull-all-pages)** — `SyncEngine.resume()` → `syncNow()` under mutex → `drain()` loops `/sync` pages, each an atomic `applyDelta`, advancing the cursor; 45s poll loop (`SyncEngine.kt:85-149`). Handles ADR 0040 `full_resync` (stale cursor → `wipeForResync`).
3. **Pull-to-refresh / on-demand fetch** — same `syncNow()` triggered by UI `onRefresh`/`onLoadHubs`/hub open (`FeedApp.kt:102`; `Main.kt:91-94`, `MainActivity.kt:320-323`). Hub tree load is a *reactive DB subscription* per open hub: `HubEngine.openHub` → `contentStore.hubTreeFlow(hubId).collect → HubTreeLoaded` (`HubEngine.kt:73-89`, `ContentStore.kt:349-364`).
4. **Mutation + outbox (egress) with optimistic-local write** — member checklist toggle / block delete. Optimistic apply written **to the DB** (not the store) + enqueue one coalesced outbox op in one txn, then kick `syncNow()`; the DB→store bridge re-projects the optimistic state. Drain is FIFO under the sync mutex, classified by the pure `OutboxSender.classify` state machine (Acked/ReMerge(412)/Drop/Backoff/Failed). `HubEngine.toggleItem/deleteBlock` `HubEngine.kt:39-58`; `ContentStore.enqueueBlockToggle/enqueueBlockDelete` `ContentStore.kt:247-278`; `SyncEngine.drainOutbox` `:162-182`; `OutboxSender.kt:30-57`. Note: block deletes are optimistic-*pending* ("Removing…"), removed only on the inbound tombstone — not optimistic-remove.
5. **Background/headless notification pass (storeless)** — geofence/exact-alarm wake reads a **synchronous** snapshot of the same process-shared SQLDelight connection, builds a minimal `AppState`, and reuses the SAME pure `nowFeed()` + `selectNotifications` — no engine, no store, no 2nd connection. `BackgroundNotify.planBackgroundNotifications` `:42-60`; sync getters `ContentStore.kt:460-493`; Android geofence/alarm wiring `MainActivity.kt:259-289`.
6. **Auth / session flows (imperative effect-trigger)** — `AuthEngine` (432 LOC) sequences `AuthClient` I/O + `TokenStore` persistence, dispatching `*Requested/*Loaded/*Failed` triplets; covers sign-in (Firebase→`/auth/firebase`, dev-token fallback), whoami/memberships, create-family, invitee redeem, owner approvals/roster, invite mint/revoke, device/CLI approval, deep-link stash-and-resume. `AuthEngine.kt`; wired via ~30 `on*` lambdas in `Main.kt:73-103` / `MainActivity.kt:299-338`. Token rotation on 401 is a cross-cutting sub-pattern in patterns 2–4 and 6.

**Key architectural through-line:** a *single flat 57-field AppState*, a *single 167-line hand-written reducer*, *one middleware*, and *one whole-state Compose subscription*, with all async pushed into *4 imperative store-holding engine classes* and *6 sole-writer DB→store Flow bridges* — high-frequency/ephemeral state deliberately excluded from the store. The documented-but-unbuilt scaling levers are per-field `fieldState`/memoized slice selectors, package-by-feature slice reducers, and a recomposition-count CI guard (all specified in `specs/prototype/08-mobile-client.md`, none implemented).
