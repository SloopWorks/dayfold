# ADR 0051: Navigation Transition System — Taxonomy, Tokens, and a Central Route-Motion Host

## Status

**Accepted — 2026-07-08 (operator-directed in-session).** The operator asked to
"audit navigation and add transition animations… and create a systematic way of
applying transitions consistently on existing and future navigation," then in
brainstorming chose **expressive / spatial** motion and the **full system +
persistent-shell** scope. ADR-class (app-wide UX motion + a standing engineering
rule). **Refines ADR 0022 (D3 fold gesture), ADR 0045 (§13b hub-timeline morph),
and ADR 0050 (container transform)** — it does not supersede them; the container
transforms become the "hero" tier inside this system. Design record:
`docs/superpowers/specs/2026-07-08-navigation-transition-system-design.md`.
Immutable from here — supersede, don't edit.

## Context

Navigation is a single redux `route: Route` gated by one `when (state.route)` in
`FeedApp.kt` (no nav library, ADR 0013). An audit found **only 2 of ~20 nav edges
animated** — Feed↔Detail and Hub↔Timeline (container transforms). Every other edge
was an instant cut: the Feed↔Hubs tab switch, Feed→Account, all Account→settings
pushes (Members/Devices/Proximity/Invite), Hub list→Hub detail, the device-link
wizard, sign-in/create-family, and every back. There was no shared motion
vocabulary, no tokens (durations were inlined; `EmphasizedDecelerate` was defined
but unused), and nothing that made a *future* route animate correctly by default.

## Decision

### 1. A motion taxonomy — nav semantics → Material 3 Expressive pattern

| `NavKind` | Pattern | Edges |
|---|---|---|
| **Tab** | Shared-axis **X** (±30dp slide + fade), by tab index | Feed ↔ Hubs |
| **Push / Pop** | Shared-axis **Z** (in scale 0.85→1 + fade; reversed on pop) | Account→Members/Devices/Proximity, Members→Invite, Hub list→detail, all backs |
| **Modal** | Slide-up + fade (enter) / slide-down (exit) | Feed→Account, JoinInvite |
| **Wizard** | Shared-axis **X** forward/back | device-link (EnterCode→ScanPrimer→Scan*/AuthorizeDevice); CreateFamily |
| **Gate** | Fade-through | Loading→SignIn→Feed, AuthError |
| **Hero** | Container transform (unchanged, ADR 0022/0045/0050) | Feed↔Detail, Hub↔Timeline — intra-surface, not a top-level route edge |

### 2. The mechanism — three isolated units

- **`RouteMotion.kt`** (pure, no Compose): `routeSpec(route): RouteSpec(tier, kind,
  tabIndex)` via an **exhaustive `when` over `Route` with no `else`**, and
  `navAnimFor(from, to, reduceMotion): NavAnim` (a pure, unit-tested resolver: tabs
  by index; modal enter/exit **tier-gated** so a push deeper from a modal isn't read
  as an exit; wizard/gate; else push/pop by tier). Reduced-motion and self-transition
  → `Snap`.
- **`NavMotion.kt`**: the single home for duration tokens (`StandardMs 400`, `HeroMs
  460`, `FastMs 250`, `ReducedMs 0`) and the Expressive easings (`Emphasized`,
  `EmphasizedDecelerate`, `EmphasizedAccelerate`), plus the one Compose builder
  `NavAnim.toContentTransform(slidePx)`. The hero container transforms now read
  `HeroMs`/`StandardMs`/`ReducedMs` from here too (open 460 / close 400 / reduced 0).
- **`AppNavHost`** (in `FeedApp.kt`): ONE `AnimatedContent(targetState = state.route)`
  wraps the router; `transitionSpec = navAnimFor(...).toContentTransform(slidePx)`.
  Feed & Hubs share the content key `"tabs"` (via `navGroupKey`) so the persistent
  shell + bar don't cross-fade on a tab switch; a same-group transition resolves to
  `Snap`. The route-selecting reads inside the wrapper use the AnimatedContent's frozen
  `route` param (so the *exiting* screen keeps rendering itself mid-transition); all
  other state reads stay live.

### 3. Persistent shell

`TabShell` is a `Column` (not a nested Scaffold — avoids double-counting the bottom
inset): a weighted inner `AnimatedContent` slides the Feed/Hubs content (shared-axis-X)
while `DayfoldBottomNav` sits below as a sibling and owns the nav-bar inset. Feed/Hub-list/
Hub-detail dropped their own bottom bars and exclude the nav-bar inset. The bar hides
(animated) for **full-screen details** — a card detail (Feed) or the hub timeline overlay
(Hubs) — via a **route-scoped** `barVisible` (`Route.Feed → currentDetailCard == null`,
else `timelineDetail == null`), so those keep ADR 0050's bar-less full-screen morph while
the hub list and a card-deep-linked hub detail keep the bar.

### 4. The standing rule (the consistency guarantee)

**Every `Route` MUST have a `routeSpec` entry; new navigation picks a `NavKind`, it
does not hand-roll a transition.** Because `routeSpec` is an exhaustive `when` over
`Route`, adding a route without classifying its motion is a **compile error**.

## Rationale

- Expressive/spatial motion (operator's choice) gives orientation cues — back vs forward
  vs lateral vs modal look different — which a fade-only system cannot.
- A pure resolver + tokens + one host keeps the surface tiny: ~all edges animate with no
  per-call-site code, and the exhaustive `when` makes drift a build failure, not a review
  miss.
- Keeping the container transforms as a "hero" tier preserves ADR 0022/0045/0050 and their
  on-device-verified morphs.

## Consequences

- Every top-level route change and the Feed↔Hubs tab switch now animate by taxonomy;
  verified on-device (tab slide + persistent bar, account modal slide-up).
- Hero morph timing shifts to the tokenized 460/400ms (aligns with ADR 0022's "~460ms
  open / ~420ms back").
- New routes must add a `routeSpec` row — a deliberate, cheap tax that guarantees
  consistency.
- Predictive-back remains commit-animated (ADR 0050); this system does not add
  gesture-scrubbed transitions.

## Composition

Refines 0022 (D3), 0045 (§13b), 0050 (hero container transforms = the Hero tier).
Composes 0047 (`:ui` module). No vendor/spend/legal impact; navigation stays
redux-`route`-driven (no nav library).
