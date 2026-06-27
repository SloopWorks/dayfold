# Predictive Back — Design (Core: P0 + P1)

**Date:** 2026-06-27 · **Target:** Android 17 / SDK 37 (minSdk 34), Compose Multiplatform 1.11.1 · **Status:** approved, pre-implementation

## Goal

Bring dayfold's back navigation onto the predictive-back model recommended by Google for `targetSdk` ≥ 36, **keeping the existing Material container-transform** for card→detail but making it gesture-driven. Scope = **Core (P0 + P1)**; P2 (predictive back on hub/auth/scan route changes) is explicitly deferred.

## Background / current state (audited)

- Nav is **custom redux** (a `when(route)` gate, no nav library — ADR 0013). Two dimensions in `AppState`: `route: Route` (top gate) and substacks (`detailStack: List<String>` for feed↔detail inside `Route.Feed`; `currentHubId` for hubs).
- **Exactly one** `BackHandler` exists — `cards/DetailScreen.kt:63` (`androidx.compose.ui.backhandler.BackHandler`) → `NavBack`. It is **commit-only**: the reverse morph is `AnimatedContent` + `tween` fired *after* dispatch, not driven by gesture progress.
- The card→detail morph uses `SharedTransitionLayout` + `cardSharedBounds(id)` in `ContentHost` (`FeedApp.kt:189-213`), keyed on `detail?.id`.
- ⚠️ **Bug:** every other screen (hub detail, Account, Members, Devices, AuthorizeDevice, EnterCode, Scan*, JoinInvite) has **no `BackHandler`**, so on Android 16/17 the system back gesture/button **exits the app** instead of going up one level (`onBackPressed`/`KEYCODE_BACK` are dead at targetSdk ≥ 36).
- `AndroidManifest.xml` lacks `android:enableOnBackInvokedCallback`.

## Decisions

- **D1 — API tier:** custom `PredictiveBackHandler` + shared-element/seekable transition (Google's documented path for a custom container transform when there is no nav library). dayfold's redux nav rules out the Navigation-Compose/Nav3 built-in tier.
- **D2 — Handler variant: Option A** — the **Compose-Multiplatform common** `androidx.compose.ui.backhandler.PredictiveBackHandler` (in `commonMain`), one codebase across Android/iOS/desktop. It carries an experimental `@OptIn`; the seekable + shared-element transition APIs are experimental regardless, so the Android-only stable handler (Option B) would not avoid `@OptIn` and would fork the code. Accepted risk: a possible small future migration to the NavigationEvent API.
- **D3 — Single animation driver:** a `SeekableTransitionState` is the *only* thing that animates the feed↔detail morph; redux remains the single source of truth for *which* screen is shown. This avoids double-animation.

## Architecture

Two cooperating back handlers, priority by Compose nesting (innermost-enabled wins):
- **Shell handler** (FeedApp) — "up one level" for every non-detail screen (P0). Hard-cut (animating these is deferred P2).
- **Container-transform handler** (feed↔detail, in `ContentHost`) — gesture-driven seekable morph (P1).

### Unit 1 — Manifest opt-in
`apps/androidApp/src/main/AndroidManifest.xml`: add `android:enableOnBackInvokedCallback="true"` on `<application>`. Explicit + future-proof.

### Unit 2 — Redux back-model (pure, the testable core)
Add one `Back` action + a pure selector `appHandlesBack(state): Boolean`, and reducer logic mapping current state → "up one level":

| Current state | `Back` resolves to |
|---|---|
| `Feed` + `detailStack` non-empty | pop detail (`NavBack`) — *owned by Unit 4's gesture handler; shell handler disabled here* |
| `Hubs` + `currentHubId != null` | `CloseHub` |
| `Account` | `CloseAccount` → Feed |
| `Members` / `Devices` | `OpenAccount` |
| `AuthorizeDevice` / `EnterCode` / `ScanPrimer` / `ScanDevice` / `ScanDenied` | `CloseDeviceFlow` |
| `JoinInvite` | `JoinDismissed` |
| `SignIn` / `Loading` / `CreateFamily` / `AuthError` | *not handled* → system exits |

`Back` is the single source of truth for back semantics; existing per-screen tap handlers (the arrow icons) keep dispatching their specific actions unchanged. `appHandlesBack` is true for every row above except the last group, AND false when a detail is open (Unit 4 owns that).

### Unit 3 — Shell back handler (P0)
In `FeedApp`: `BackHandler(enabled = appHandlesBack(state)) { dispatch(Back) }`. Hard-cut, matching today's route transitions. Fixes the exits-app bug on hub-detail, Account, Members, Devices, scan/device, JoinInvite.

### Unit 4 — Gesture-driven container transform (P1, the headline)
Refactor `ContentHost` from `AnimatedContent(targetState = detail?.id)` to a **`SeekableTransitionState<String?>`** (`null` = feed, id = detail) inside the existing `SharedTransitionLayout`, keeping `cardSharedBounds`:
- **Redux → UI sync:** `LaunchedEffect(reduxDetailKey)` → `seekable.animateTo(key)` for every *non-gesture* transition (open detail, in-hero arrow back, deep-stack pops).
- **Gesture path:** `PredictiveBackHandler(enabled = detailOpen)` collects `progress` →
  - on each event: `seekable.seekTo(fraction = decelerate(event.progress), targetState = feedKey)` — drag tracks the finger; progress run through a decelerate interpolator (Google: never feed raw linear); `event.swipeEdge` honored for direction / RTL.
  - on normal completion (**commit**): `seekable.animateTo(feedKey, spec = emphasizedDecelerate)` then `dispatch(NavBack)`.
  - on `CancellationException` (**cancel**): `seekable.animateTo(detailKey, spec = emphasizedDecelerate)`; **rethrow**.
- **Emphasized-decelerate preserved** (ADR 0022): `CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)` governs only the settle tail on commit/cancel.
- DetailScreen's plain `BackHandler` (line 63) is **removed**; its in-hero back arrow keeps dispatching `NavBack` (flows through the sync→animate path).
- The render structure becomes `SharedTransitionLayout { rememberTransition(seekable).AnimatedContent(contentKey = { it }) { key -> if (key == null) Feed else Detail } }`.

### Unit 5 — Motion helpers (small, shared) — `PredictiveBackMotion.kt`
- `EmphasizedDecelerate` easing constant.
- `decelerateProgress(raw: Float): Float` — decelerate interpolation of gesture progress.
- `reducedMotion()` guard — read `Settings.Global.ANIMATOR_DURATION_SCALE`; when `0f`, skip the preview scrub and jump to commit (honor user setting). Android-specific read via `expect/actual` (Android reads Settings; other platforms return false).

## Data flow

Gesture → `PredictiveBackHandler.progress` → `seekable.seekTo` (morph tracks finger) → release → **commit** dispatches redux `NavBack` **or** **cancel** animates back. Redux owns *which* screen; the seekable state owns the *animation*. Forward nav and tap-back drive the same seekable via the redux→UI `LaunchedEffect`, so there is exactly one animator.

## Components & boundaries

| Unit | File(s) | Responsibility | Depends on |
|---|---|---|---|
| 1 | `AndroidManifest.xml` | OS opt-in | — |
| 2 | `Model.kt` (the `Action` hierarchy), `Reducer.kt`, new `BackNav.kt` (the `appHandlesBack`/`Back`-resolution selector) | back semantics (pure) | AppState |
| 3 | `FeedApp.kt` | shell back → `Back` | Unit 2, `compose.ui.backhandler` |
| 4 | `FeedApp.kt` (`ContentHost`), `cards/DetailScreen.kt` | gesture-driven morph | Unit 5, seekable + shared-element APIs |
| 5 | `PredictiveBackMotion.kt` (+ `actual` on Android) | easing, progress curve, reduced-motion | platform Settings (Android) |

## Testing

- **Unit 2 (pure, load-bearing):** `Back` from each route/substate → correct next state; `appHandlesBack` truth table. Full coverage in `*ReducerTest` / a new `BackNavTest`.
- **Unit 5 (pure):** `decelerateProgress` monotonic 0→1 with decelerate shape; reduced-motion guard logic.
- **Unit 4 (UI):** a desktop Compose test that drives `PredictiveBackHandler` progress and asserts **commit dispatches `NavBack`** and **cancel does not** (if drivable in the `desktopTest` harness); otherwise this path is covered by the emulator **verify** phase (adb back-gesture + screenshots of the morph at progress and after commit/cancel).
- **Regression:** existing snapshot/render tests (`HubScreens`, card detail, `BlockMarkdown`, fake-backend) stay green.

## Cross-platform note

All changes live in `commonMain` except the Android manifest line and the `actual` reduced-motion read. Android gets the gesture; iOS interactive-pop drives the same handler; desktop is unaffected (no back gesture, `BackHandler` still fires on its platform trigger).

## Risks / mitigations

- **Seekable ↔ redux reconciliation / double-animation** — mitigated by D3 (seekable is the only animator; redux only sets the target; the `LaunchedEffect` is gated so it does not fight an in-flight gesture).
- **Experimental `@OptIn`** (D2) — verified compiling in a spike; contained to Units 4/5; possible minor future migration to NavigationEvent.
- **Deep detail-stack pops** keep today's cross-fade between two unrelated detail cards (no special container morph) — consistent with current behavior; only the top-level card↔detail boundary gets the gesture-driven morph.
- **3-button nav** — back action is identical; only the drag *preview* is gesture-only. Logic never gates on the gesture.

## Out of scope (deferred)

- P2: gesture-driven predictive back (full-screen scale + 35% fade-through) for hub list↔detail and auth/scan/settings route changes.
- Adopting a navigation library (Navigation 3 / NavDisplay) — Android-only UI on KMP as of mid-2026; conflicts with ADR 0013.
- Migrating to the NavigationEvent API.
