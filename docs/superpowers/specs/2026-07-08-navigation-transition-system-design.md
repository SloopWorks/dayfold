# Navigation Transition System — design

**Date:** 2026-07-08
**Status:** design (approved to spec) — implementation plan follows
**ADR:** 0051 (to be written from this design)
**Scope choice (operator):** full system + persistent shell; expressive / spatial motion.

## Problem

Navigation is a single redux `route: Route` gated by one `when (state.route)` in
`FeedApp.kt`. Only **2 of ~20** nav edges animate today — Feed↔Detail and
Hub↔Timeline (container transforms, ADR 0022/0045/0050). Every other route change
is an **instant cut**: the Feed↔Hubs tab switch, Feed→Account, all Account→settings
pushes (Members/Devices/Proximity/Invite), Hub list→Hub detail, the device-link
wizard, the sign-in/create-family flow, and every back. There is no shared motion
vocabulary, no tokens (durations 360/280 are inlined in two files; `EmphasizedDecelerate`
exists but is unused by the live specs), and no mechanism that makes a *future* route
animate correctly by default.

## Goals

1. A **meaningful, consistent** motion vocabulary: every nav edge animates in a way
   that reflects what the navigation *means* (lateral vs deeper vs back vs modal).
2. A **systematic mechanism** so existing *and future* routes get the right transition
   with near-zero per-site code — and it is a **compile error** to add a route without
   classifying its motion.
3. Motion personality: **expressive / spatial** (Material 3 Expressive), tuned to feel
   deliberate, honoring reduced-motion.
4. Preserve the existing container transforms (they become the "hero" tier).

## Non-goals

- No navigation library adoption; navigation stays redux-`route`-driven.
- No reducer/route-model changes (Account stays a `Route`, animated *as* a modal — no
  new overlay substate).
- No predictive-back finger-scrub work (ADR 0050 stands; commit-animated).
- Live-drag / gesture-scrubbed transitions beyond the existing detail predictive-back.

## Motion taxonomy (nav semantics → Material pattern)

| Kind | Pattern | Used by |
|---|---|---|
| **Tab** | Shared-axis **X** (±30dp slide + fade); direction by tab index | Feed ↔ Hubs |
| **Push** | Shared-axis **Z** (in: scale 0.8→1 + fade; out: 1→1.1 + fade) | Account→Members/Devices/Proximity, Members→Invite, Hub list→Hub detail |
| **Pop** | Shared-axis **Z reversed** | every back edge |
| **Modal** | Slide-up + scrim (enter) / slide-down (exit) | Feed→Account, JoinInvite, WhoCanSee sheet |
| **Wizard** | Shared-axis **X** forward/back | device-link (EnterCode→ScanPrimer→Scan*/AuthorizeDevice); SignIn→CreateFamily |
| **Gate** | Fade-through (unrelated content) | Loading→SignIn→Feed, AuthError |
| **Hero** | Container transform (unchanged) | Feed↔Detail, Hub↔Timeline (intra-surface, not a top-level route edge) |

Sub-decisions (operator-confirmed): Account = **modal**; its inner Members/Devices/
Proximity = **pushes**; device-link + sign-in = **wizard** axis-X.

## Route classification (source of truth)

`RouteSpec(tier: Int, kind: Kind)` per route. `tier` gives push/pop direction
(deeper = forward); `kind` selects the pattern.

| Route | tier | kind |
|---|---|---|
| Loading | 0 | Gate |
| SignIn | 0 | Gate |
| AuthError | 0 | Gate |
| CreateFamily | 1 | Wizard |
| JoinInvite | 2 | Modal |
| Feed | 0 | Tab (index 0) |
| Hubs | 0 | Tab (index 1) |
| Account | 1 | Modal |
| Proximity | 2 | Push |
| Devices | 2 | Push |
| Members | 2 | Push |
| Invite | 3 | Push |
| EnterCode | 1 | Wizard |
| ScanPrimer | 2 | Wizard |
| ScanDevice | 3 | Wizard |
| ScanDenied | 3 | Wizard |
| AuthorizeDevice | 4 | Wizard |

## Mechanism (three isolated units)

### 1. `RouteMotion.kt` — pure taxonomy + resolver (no Compose)

- `enum class NavKind { Tab, Push, Modal, Wizard, Gate }`
- `data class RouteSpec(val tier: Int, val kind: NavKind, val tabIndex: Int = -1)`
- `fun routeSpec(route: Route): RouteSpec` — **exhaustive `when`** over `Route`
  (adding a Route without a spec = compile error → the consistency guarantee).
- `enum class NavAnim { SharedXForward, SharedXBackward, SharedZForward, SharedZBackward, ModalEnter, ModalExit, FadeThrough, Snap }`
- `fun navAnimFor(from: Route, to: Route, reduceMotion: Boolean): NavAnim` — **pure,
  fully unit-testable.** Precedence (first match wins → total, no fallthrough gaps):
  1. `reduceMotion` → `Snap`
  2. `from == to` → `Snap` (no self-transition)
  3. both `Tab` → `SharedXForward` if `tabIndex(to) > tabIndex(from)` else `SharedXBackward`
  4. `to`=Modal & `from`≠Modal → `ModalEnter`; `from`=Modal & `to`≠Modal → `ModalExit`
  5. either endpoint `Gate` → `FadeThrough`
  6. either endpoint `Wizard` → `SharedXForward` if `tier(to) >= tier(from)` else `SharedXBackward`
     (covers wizard↔wizard **and** wizard entry/exit — one rule, no special-case)
  7. else (Push/Pop) → `SharedZForward` if `tier(to) > tier(from)` else `SharedZBackward`

### 2. `NavMotion.kt` — tokens + Compose mapping

- **Tokens:** `object NavMotion { val StandardMs=400; val HeroMs=460; val FastMs=250;
  val ReducedMs=0; val Emphasized; val EmphasizedDecelerate; val EmphasizedAccelerate }`.
  Consolidates the inlined 360/280 and the orphaned `EmphasizedDecelerate` from
  `PredictiveBackMotion.kt` (which is re-exported here or moved).
- `fun NavAnim.toContentTransform(density): ContentTransform` — the ONLY Compose-facing
  builder. Maps each `NavAnim` to enter/exit specs (slide/scale/fade) using the tokens.
  `Snap` → `EnterTransition.None togetherWith ExitTransition.None`.
- Container-transform (hero) durations migrate here too (Feed↔Detail, Hub↔Timeline
  read `NavMotion.HeroMs`), so all motion timing is tokenized in one place.

### 3. `AppNavHost` — one host wraps the top-level router

- A single `AnimatedContent(targetState = route)` around the current `when(route)`;
  `contentKey = { navGroupKey(it) }` where **Feed & Hubs share the key `"tabs"`** (so the
  persistent shell + bar do NOT cross-fade on a tab switch); every other route is its own
  key. `transitionSpec = navAnimFor(initialStateRoute, targetStateRoute, reduceMotion).toContentTransform()`.
- Result: **every top-level route change animates by taxonomy, automatically.**

### Persistent shell (the refactor)

- New `TabShell` composable owns the single `Scaffold` + `DayfoldBottomNav` and hosts a
  content slot. Feed/Hubs screens **drop their own `Scaffold`/`bottomBar`** and become
  content-only.
- Inside `TabShell`, a **nested** `AnimatedContent(targetState = route in {Feed,Hubs})`
  with the **Tab** (shared-axis-X) spec slides only the content area; the bar persists.
- Non-tab routes (Account, wizard, etc.) render outside `TabShell` (no bar), reached via
  the outer host's Modal/Push/Wizard transitions.

### Layering (no conflicts)

Three independent transition layers, each on its own state slice:
`AppNavHost` (route group) → `TabShell` inner (Feed/Hubs) → `ContentHost`/`HubDetailScreen`
container transforms (detailStack / timelineDetail, `SharedTransitionLayout`). Nesting
`AnimatedContent`s is supported; they don't interfere. Hub **list→detail** (currently a
bare `if` cut) gains a **Push** shared-axis-Z inside `HubsHost`.

## Reduced motion

`navAnimFor(...) == Snap` when `rememberReduceMotion()` is true → `EnterTransition.None`
(instant). Applies uniformly to every edge, including the hero container transforms
(already `dur=0` today). One code path, one guarantee.

## Testing

- **`RouteMotionTest` (desktop, pure):** assert `navAnimFor(from,to,reduceMotion)` for a
  representative matrix — Feed→Hubs=`SharedXForward`, Hubs→Feed=`SharedXBackward`,
  Feed→Account=`ModalEnter`, Account→Feed=`ModalExit`, Account→Members=`SharedZForward`,
  Members→Account=`SharedZBackward`, EnterCode→ScanPrimer=`SharedXForward`,
  Loading→Feed=`FadeThrough`, any with `reduceMotion=true`=`Snap`. Also assert
  `routeSpec` is total (exhaustive-when compiles).
- **On-device verification:** the `apps/androidApp` build/observe loop (adb screencap
  bursts) captures mid-transition frames for the tab slide, an Account modal, a push, and
  a back — same method that verified ADR 0050.
- Existing snapshot tests are unaffected (they render single states; nav host no-ops when
  not transitioning).

## Files

New: `RouteMotion.kt`, `NavMotion.kt`, `TabShell.kt` (all `apps/ui/.../client`), plus
`RouteMotionTest.kt` (desktopTest). Changed: `FeedApp.kt` (wrap router in `AppNavHost`;
Feed/Hubs branches render via `TabShell`), `FeedScreen.kt` + `HubScreens.kt` (drop own
Scaffold/bottomBar; hub list→detail push), migrate hero durations to `NavMotion`.
`PredictiveBackMotion.kt` easings fold into `NavMotion`.

## ADR 0051

Records: the taxonomy, the tokens, the exhaustive-`RouteMotion` enforcement rule, the
persistent-shell refactor, and the standing rule **"every Route MUST have a `routeSpec`
entry; new nav picks a `NavKind`, it does not hand-roll a transition."** Refines
0022/0045/0050 (container transforms = the Hero tier within this system). Composes 0047
(`:ui` module).

## Risks / open

- **Shell refactor blast radius** — Feed/Hubs Scaffold ownership moves to `TabShell`;
  insets/edge-to-edge and the two bottom-bar mount sites must be reconciled. Contained to
  `FeedScreen`/`HubScreens`/`FeedApp`; verified on-device + snapshots.
- **Nested-AnimatedContent cost** — three layers; negligible in practice, but watch the
  tab-switch-while-detail-open case (a detail slides as part of the tab content — accepted
  for MVP).
- **Deep-link arrivals** — a deep-link that sets route directly (cold) lands via the Gate
  path (fade-through), not a push; acceptable (no origin to move from).
