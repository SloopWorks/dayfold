# 08 — Mobile Client (Compose Multiplatform)

> Status: **reviewed (2 agents) → fixes applied**. Anchored by **ADR 0013**
> (KMP/CMP + redux-kotlin) + ADR 0009 (M3E) + 0006 (deep-link) + 0014
> (triggers) + 0015 (E2E). **Milestone:** [M0] render-only (operator device,
> household token, **no login, no geofencing**, time-notifications + foreground
> proximity only, **plaintext SQLDelight**) · [M1] auth/invite/device UI +
> multi-member + **background geofencing + SQLCipher** · [later] activity.

> **STATE LIB — verified 2026-06-18:** all modules exist at exact version
> **`1.0.0-alpha01`** (KMP; `org.reduxkotlin:redux-kotlin[-compose|-granular|
> -compose-saveable|-threadsafe|-concurrent|-multimodel]`). **Real API is
> `fieldState` — NOT `fieldStateOf`** (docs/skill drift). BUT alpha01 is
> ~1-day-old with 2 alpha01-only modules → **default to `0.6.2` stable** +
> hand-written root reducer + `selectorState`/`store.select{}`; treat alpha01
> as a feature-flag upgrade. Operator preference: **INB-11**.

## Architecture & modules (redux-kotlin, package-by-feature)

```
app/ core/ infra/ ui/ feature/{now,hubs,auth[M1],settings[M1]}/
  {model, actions, reducer, effects, screen, selectors, tests}
```
Single **`StableStore`** (pending the gate). **Root reducer is HAND-WRITTEN**
delegating to slice reducers — `combineReducers` only combines *same-state-type*
reducers and does **not** map a heterogeneous `AppState`:
```kotlin
fun appReducer(s: AppState, a: Any) = s.copy(
  session = sessionReducer(s.session, a), sync = syncReducer(s.sync, a),
  content = contentReducer(s.content, a), nav = navReducer(s.nav, a), ui = uiReducer(s.ui, a))
```
Effects in middleware, off-main (Rule E, `NotificationContext` → main).

## State shape

```
AppState {
  session { credential?, family_id?, status }            // M0: household token implicit
  sync    { cursor?, lastSyncedAt?, status }
  content { hubsById, sectionsById, blocksById, cardsById, placesById }  // store = reactive PROJECTION of the DB; DB = persistent source of truth (ADR 0020)
  nav     { backstack:[Route], listDetail{selectedHubId?}, focusBlockId? }  // full tree, state-keyed
  ui      { permissionStates, banners }
}
```
`triggers` is **NOT in state** — it's an **effect-maintained** cache (depends on
live device location, which never enters the store/server). Render isolation
(Rule C): composables bind the **narrowest memoized slice** (`selectorState`/
`distinctUntilChanged`) — never `AppState` wholesale; no composable reads
SQLDelight directly.

## Effects (middleware — the seams)

- **Sync effect:** `GET /families/{fid}/sync?since=cursor` → **apply one page
  (changes+tombstones) AND advance the cursor in ONE SQLDelight transaction**
  (crash-between = no loss; cursor only moves on commit) → emit
  `CacheUpdated(changedIds)` → re-hydrate only touched slices. Tombstone-apply
  idempotent; tombstone+change for same id → **tombstone wins if
  `deleted_at > updated_at`**. `has_more` paginates; cursor never advances past
  an uncommitted page. **Cadence (ADR 0020):** sync-on-open/foreground-resume +
  **foreground poll ~30–60 s** (paused when backgrounded) + pull-to-refresh +
  **background sync** (below). Push (FCM/APNs/SSE) deferred — same flow when added.
- **Cache effect:** **plaintext SQLDelight at M0**; **SQLCipher at M1** (under
  E2E). **WAL mode** so readers get a consistent snapshot during a write tx —
  no half-applied delta on screen.
- **Crypto effect [M1, E2E]:** **decrypt-once-into-cache** off-main; cancel on
  nav-away. **Decrypt failure** (AAD/version mismatch) → **quarantine the row
  (`needs-redecrypt`) + re-pull + soft error**; **never advance the cursor past
  it; never log plaintext**. Keychain `kSecAttrAccessibleAfterFirstUnlock` for
  the DB key + FCK (background relaunch works post-first-unlock; pre-first-
  unlock cold start can't render — acceptable).
- **Auth effect [M1]:** Firebase (GitLive + native glue) → token mint/refresh.
- **Trigger effect** (see below) · **Deep-link effect** → `Navigate(...)`.

## Rendering (M3E + markdown + deep-link)

- **Now** feed + **Hubs** list/detail. **Hub detail = ONE outer `LazyColumn`,
  items keyed by stable `blockId`** (stable keys → no recompose/scroll jank on
  reorder). **In-hub markdown blocks render NON-lazily** (fixed/measured) —
  `LazyMarkdownSuccess` is itself a LazyColumn and **cannot be nested** in the
  hub list; reserve it for a **full-screen single-doc view**. Long parse stays
  off-main (`parseMarkdownFlow`, cancel on nav-away). Link-scheme allowlist +
  images-off (event-hubs §Markdown).
- **Deep-link arrival (state-keyed, Rule I):** card `target{hubId,sectionId?,
  blockId?}` → `nav` route + `focusBlockId` → detail
  `lazyListState.scrollToItem(indexOf(blockId))` + `BringIntoViewRequester` +
  transient highlight + expand section. **Resolves against the LOCAL cache**
  (nearest-ancestor fallback). **On fallback: suppress the highlight, show the
  "that item moved" banner** instead.

## Trigger matcher (ADR 0014, on-device) — permission-gated

- **Active set is gated on permission** (it's inert otherwise): under
  **when-in-use**, register **ZERO OS geofences** — do **foreground-only
  proximity highlighting** on last-known location, honest UI ("background place
  reminders need Always"). Populate `activeGeofences[]` **only when Always /
  Allow-all-the-time** is granted. (M0 has **no geofencing at all** — time
  triggers only.)
- **Geo re-rank (M1):** nearest-N within limits (iOS ~20 / Android ~100);
  **reserve 1 iOS region as a large "leave-the-cluster" boundary** around the
  current centroid → crossing it re-ranks+re-registers (the canonical pattern,
  not SLC alone); pair with **significant-location-change** as a coarse wake; a
  **foreground reconcile** on app open; **force-quit kills monitoring** —
  documented UX limitation ("reopen after long trips").
- **Time / local notifications:** iOS caps **64 pending** (soonest kept, rest
  discarded) → schedule **soonest-~32** with headroom + **foreground top-up**;
  **recurring rule = ONE `UNCalendarNotificationTrigger`** (not N requests);
  **quiet-hours computed AT SCHEDULE TIME** (shift/suppress fire-dates — iOS
  has no fire-time filter); **dedupe by trigger-id** (re-schedule replaces);
  **daily-cap via an on-device per-day counter**. Live position never leaves.

## Privacy — observability ban (the load-bearing promise)

Device **live location NEVER** enters logs, crash reports, analytics, or
breadcrumbs (enforce via a detekt/lint rule banning location vars in log
calls). **Decrypted content** (titles, `body_md`, place labels/coords) is never
logged. Prefer no third-party crash SDK on screens holding location/plaintext
(or scrub custom keys). A test asserts **no coordinate-shaped payload egresses**
the network. (Extends ADR 0014's server "never logged" to the client.)

## E2E (ADR 0015) — why both layers

**AEAD(FCK)** protects content in transit + server-breach (server never holds
FCK); **SQLCipher** protects the *decrypted plaintext cache at rest on the
device* (cold device / backup extraction — incl. place coords). They close
**different** threats — don't cut either. iOS path: SQLDelight
`NativeSqliteDriver` + SQLCipher CocoaPods **static framework**, **`linkSqlite =
false`** (else the build silently links system SQLite and the DB is
**UNENCRYPTED** — known footgun); pin the pre-1.0 dep.

## Navigation, persistence, perf, testing

- Navigation **is state** (nav tree); deep-links/triggers dispatch nav actions.
  Adaptive: phone bottom bar; rail/drawer + list-detail on wide (the `nav`
  backstack+listDetail carries it).
- `compose-saveable` + `SaveableStateRegistry` snapshot nav tree + scroll across
  process death; cache survives restarts (renders on cold start *if keychain
  unlocked* at M1).
- Perf: stable LazyColumn keys; **memoized referentially-stable selectors**
  (`distinctUntilChanged`); cancel off-main decrypt/parse on nav-away; stream/
  window large (M1, ≤25 MB) spilled bodies.
- Testing: pure reducers (fast unit), effect fakes, selector + screenshot tests;
  **`./gradlew build`** verify gate; ship **`AGENTS.md`** + `.claude/skills/
  redux-kotlin/`.

## Data freshness & offline-first sync (ADR 0020)

**Unidirectional, DB-as-source-of-truth:**
`network /sync ──writes──▶ SQLDelight (truth) ──reactive query (Flow)──▶ store ──selectorState──▶ UI`.
The UI never reads the network; the store is a **reactive projection of the DB**.
One writer per layer.

- **Instant cold start (R1):** on launch, hydrate the store from the DB and
  render **before any network** — the app shows last-synced content offline,
  instantly. Then kick a foreground sync; the DB write flows back to the UI.
- **Foreground freshness (R2):** immediate sync on open/resume + a **poll loop
  (~30–60 s, configurable)** while foreground (cancelled on background) +
  pull-to-refresh. A push lands within one interval. **Push deferred** (FCM/APNs
  or SSE/WebSocket) — when added it just *triggers the same sync*, no dataflow
  change.
- **Background freshness (R3):** one **shared sync engine** in `commonMain`,
  invoked by:
  - **Android `WorkManager`** — `PeriodicWorkRequest` (~15–30 min, the platform
    floor), constraints `NetworkType.CONNECTED`; runs the engine → writes the DB.
  - **iOS `BGTaskScheduler`** — `BGAppRefreshTask` registered at launch,
    rescheduled on completion; same engine.
  No UI in background — just the DB write, so the next open is already fresh.
- **Cursor + `lastSyncedAt` persist in the DB** (`sync_meta`): foreground,
  background, and cold-start all resume from the same keyset cursor → no gaps,
  no double-pull, crash-safe (cursor commits with the page).
- **Single-writer (M0):** content is server-authored, so the client DB is a pure
  read-replica — no client-write conflicts. 2-way (ADR 0016) later adds an
  `outbox` table feeding the same one-way flow.

### Build slice — "Persistence & Sync" (closes the in-memory → offline gap)
The shipped M0 client is **in-memory** (store fed straight from the network).
To reach this design:
1. **SQLDelight (KMP)** — drivers `AndroidSqliteDriver` / `NativeSqliteDriver`
   (iOS) / `JdbcSqliteDriver` (desktop); tables = content (cards at M0) +
   `sync_meta(cursor, last_synced_at)`; WAL.
2. **Sync engine** (`commonMain`) — `SyncClient` writes the DB in one tx
   (upsert + tombstone + cursor) instead of dispatching to the store; drains
   `has_more`.
3. **DB→store bridge** — SQLDelight reactive `Flow` → dispatch hydrate actions →
   store → `selectorState` (FeedApp unchanged).
4. **Cold-start** — hydrate store from DB first, then sync.
5. **Foreground poll loop** (coroutine) + **WorkManager** (Android) +
   **BGTaskScheduler** (iOS) glue, all calling the shared engine.
6. **Tests** — offline-open (DB only, no net), sync-updates-DB-updates-UI,
   background-sync-writes-DB, cursor survives restart.

## Open questions
- Confirm redux-kotlin alpha1 coordinates/modules (the pre-build gate).
- Android background-location policy review (Android 11+ "Allow all the time"
  flow + Play Store justification) at M1.
- SQLCipher-KMP version pin (with the ADR 0015 crypto lib).
