# ADR 0050: Card↔Detail Container Transform Uses Plain `AnimatedContent`, Not `SeekableTransitionState`

## Status

**Accepted — 2026-07-08 (operator-directed in-session).** The operator reported
the card→detail animation "just flashes to the detail," directed the debugging,
and — once the root cause was isolated — selected the **commit-animated** fix
option (real morph on open/close; predictive back keeps the OS window peek but no
longer finger-scrubs the morph). ADR-class because it changes the
**predictive-back interaction** documented as a sub-task of ADR 0022 D3.
**Refines ADR 0022 (does not supersede)** — 0022 D3's container-transform promise
is upheld (in fact restored); only the predictive-back *implementation* changes.

## Context

ADR 0022 **D3** shipped the "fold gesture": tap a card → it **morphs into its
detail** via a Material 3 container transform (`SharedTransitionLayout` +
`AnimatedContent`, `sharedBounds` keyed `card-$id`). D3 flagged **predictive-back**
as *"desired but needs a Compose-MP upgrade… a scoped sub-task/risk, not a blocker
for the base transition."*

That sub-task landed later as #237 (`18d0988`, 2026-06-28): it rebuilt the
transition around a `SeekableTransitionState` so a back gesture could scrub the
morph 1:1 with the finger (`rememberTransition(seekable).AnimatedContent { … }`,
`PredictiveBackHandler` calling `seekable.seekTo(progress)`).

**This silently broke the base container transform.** Driving a shared-element
`AnimatedContent` from a `SeekableTransitionState` does **not** render the
`sharedBounds` bounds morph — the entering detail snaps to full-screen bounds on
frame 1 and only a crossfade plays (perceived as "a flash"). The redux nav,
`animateTo` timing, key matching, and `isTransitionActive` all remain correct, so
the failure is invisible in logs; only frame-by-frame capture reveals the snap.

Investigation (on-device build/observe loop, `apps/androidApp` on a physical
device) established:

- **Not** reduced-motion, not the derived-card `OpenHub` path, not an async
  recomposition (a lone `NavToDetail` with no other dispatch still snaps), not a
  missing/short `boundsTransform` (a forced 2000 ms transform had zero effect).
- Swapping the **single** call `transition.AnimatedContent(...)` →
  `AnimatedContent(targetState = …)` restores the morph — proving the
  `SeekableTransitionState` driver is the cause.
- Confirmed **identical snap on androidx.compose.animation 1.11.2, 1.11.3, and
  1.12.0-alpha03** (tested via `resolutionStrategy.force`) → a Compose-API
  limitation, not a patch-fixable version bug. Keeping *both* the morph and the
  finger-scrub via a version bump is therefore **not achievable** today.
- The hub card→timeline morph (ADR 0045 §13b, `HubDetailScreen`) used the same
  seekable pattern and was broken the same way.

## Decision

1. **Drive both container transforms with a plain, state-driven
   `AnimatedContent(targetState = …)`** — `ContentHost` (feed↔detail, keyed on the
   top of the detail stack) and `HubDetailScreen` (hub card↔timeline, keyed on
   `state.timelineDetail`). This restores the ADR 0022 D3 / ADR 0045 morphs.

2. **Predictive back is commit-animated, not finger-scrubbed.** The OS renders its
   window "peek" during the drag; on release the `PredictiveBackHandler` dispatches
   `NavBack`, and the plain `AnimatedContent` plays the **reverse** morph. Cancel is
   a no-op. We do **not** scrub the shared element with the finger — that scrub
   never actually morphed (it also only crossfaded), so nothing working is lost.

3. **Reduced motion** is honored in the transition spec (`dur = 0` → snap), replacing
   the former `seekable.snapTo`.

4. The pure helpers in `PredictiveBackMotion.kt` (`decelerateProgress`,
   `EmphasizedDecelerate`) are now unused by production but **retained** — they are
   exactly what a future live-drag implementation (see Open) would reuse.

## Rationale

- **Restores a signed-off behavior.** The morph is core to the "Dayfold" brand
  metaphor (0022) and was operator-signed-off; a flash is a visible regression.
- **No working capability is sacrificed.** The finger-scrub was already non-functional
  (crossfade only). Commit-animated is strictly better: a real morph on open **and**
  close, plus the OS peek during the drag.
- **Smallest correct change.** −47/+25 across two files, no new dependency, no
  cross-platform version bump (which was proven not to fix it and would carry its
  own KMP/iOS/desktop validation cost).

## Consequences

- Tap-open, hero-arrow-back, deep pops, and predictive-back-commit all flow through
  one `targetState` and render the container transform. Verified on-device (open +
  reverse morph) with `:ui:desktopTest` snapshots green.
- **Lost:** 1:1 finger-tracking of the morph during a back drag. Reintroducing it
  requires a manual `graphicsLayer` gesture path (not a shared-element scrub) — see
  Open.
- Future Compose-MP upgrades should **re-test** whether `SeekableTransitionState` +
  `sharedBounds` renders the morph; if a version fixes it, live-drag becomes
  available without the manual path. Tracked in memory
  (`seekable-transitionstate-breaks-sharedbounds-morph`).

## Composition

Refines **ADR 0022** (D3 fold gesture) and **ADR 0045** (§13b hub-timeline morph);
upholds both morphs, changes only the predictive-back implementation. Composes with
the `apps/ui` module (ADR 0047).

## Open

- **Live-drag predictive back (option B), deferred.** Manually scale/translate the
  detail via `graphicsLayer` from back progress while keeping the plain
  `AnimatedContent` morph for tap-open. Not scheduled — the commit-animated behavior
  is sufficient for MVP dogfooding.
