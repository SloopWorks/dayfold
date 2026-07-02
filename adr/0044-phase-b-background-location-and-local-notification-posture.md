# ADR 0044: Phase B — Background-Location & Local-Notification Surfacing Posture

## Status

**Accepted** 2026-06-30 (operator ratified in-session — "Accept ADR 0044 as
written" → Gate B closed). Was **Proposed** 2026-06-30 (agent-drafted at the
Phase B build-loop gate; **operator-gated — HARD GUARDRAIL tier**: requests a
new background-location permission posture + a customer-disclosure review,
touching guardrails #3 [restricted-scope/location data] and #4
[customer-relationship line]). **Extends ADR 0043 Phase B**
and **concretizes ADR 0014 §4/§5/§6** (the "later milestone" the trigger engine
ADR reserved). Does **not** edit either (both Accepted/immutable) — it adds the
Phase-B posture they deferred.

**Both gates CLOSED → Phase B BUILT + SHIPPED.** Gate B ratified 2026-06-30
(above). **Gate A — ADR 0008 design sign-off — CLEARED** 2026-06-30 (the v2
`designs/triggers/` Phase-B mockups landed with the INB-13 §6b honesty rework —
the false "saved-place coords never leave" claim removed, the honest two-part
promise present — and the operator signed off; INB-13 closed) and
**re-approved as-shipped 2026-07-01** (operator reviewed the built surfaces:
"I approve"). Phase B is **implemented + shipped to `main`** (PR #260): the
headless background pass (`BackgroundNotify`), `AndroidLocalNotifier`,
`GeofencingClient` + `AlarmManager` scheduling, the `NotifConfig` quiet-hours +
daily-cap (device-local, never-synced, default-OFF), the permission ladder, and
the offline/geo-on states — **Android**.

**iOS parity BUILT + sim-verified 2026-07-01** (PR #273): a SwiftUI/xcodegen host
under `apps/iosApp/` embeds the `:client` static framework and renders the shared
Compose `MainViewController`; the 5 device seams are implemented in `iosMain` over
the SAME commonMain core (no engine fork) — `IosLocalNotifier`
(`UNUserNotificationCenter`, `threadIdentifier` grouping, on-device subtitle,
deep-link `userInfo`), `IosExactNotificationScheduler`
(`UNTimeIntervalNotificationTrigger`), `IosGeofenceController` (`CLLocationManager`
region monitoring, 20-region nearest-N cap), `IosLocation`/`IosNotificationPermissionController`,
plus `IosContentStoreHolder` + the process-global `IosNotifGlue` (retained UN/CL
delegates on the main thread) + a reconcile-only `BGTaskScheduler`. Verified on the
iOS Simulator: the time lane fires (`UNTimeIntervalNotificationTrigger`), the
geofence lane fires (a `simctl` location crossing → `didEnterRegion` → the shared
pass → banner with the honest "Matched on your device" subtext), the notification
tap routes to the source hub (`didReceive` → deep-link bus → `openHub`, confirmed
in-console), and the permission ladder prompts (WhenInUse→Always) with the honest
usage copy. **Sim-limited (real-device / lldb only, documented):** background/
killed-app region wake + the opportunistic `BGTask` launch.

**iOS time-lane posture divergence (operator-accepted via the build plan
2026-07-01):** Android's exact-alarm receiver re-runs the full pass at FIRE time, so
quiet-hours/cap/dedup are honored then. iOS delivers a scheduled local notification
directly with no wake to re-run, so those posture filters are applied at SCHEDULE
time (`reconcileExactSchedules`: skip subjects already notified today, skip
non-urgent triggers landing in quiet hours, stop at the daily cap), re-reconciled on
config/content change. Residual: the cap count can be stale by fire time — the only
Phase-B behavioral difference from Android, and within the calm-posture spirit.

**Still open (recorded, not blocking):** the **activity** trigger stays a reserved
schema slot (**DEFERRED** per operator 2026-07-01); and the **public-ship**
Play/App-Store background-location data-safety declaration + disclosure review, plus
the iOS App-Store background-location justification (`UIBackgroundModes` usage), stay
**operator/legal-gated** (pre-public, not pre-dogfood; the dev/sim/TestFlight
Info.plist usage strings ARE in place).

Statuses: Proposed | Accepted | Superseded | Deprecated.

## Context

ADR 0043 Phase A shipped (PR #257): the on-device `deriveNow` + the pure
Priority & Ordering Engine (`rank`) drive a calm, ranked, **foreground**
two-lane Now feed. ADR 0043 §2b is explicit that **the same engine** drives
Phase-A in-feed ordering and Phase-B notification selection — "notifications =
the top-K of the same ranking under the daily cap." Phase A deliberately left
two `RankConfig` knobs deferred (`NowRank.kt:18-19` — "Quiet-hours config is
deferred to Phase B (it gates notifications, not in-feed ordering)") and shipped
no background location, no notifications, and no new permission.

Phase B turns the *already-built* engine into background notifications when the
app is closed. The mechanism is entirely on-device:

- **Background geofence** registration (OS-level) for the nearest-N
  places/triggers, matched **on-device**; the device's **live position never
  leaves the device** (ADR 0014's load-bearing invariant).
- **LOCAL notifications** (Android `NotificationManager` / iOS
  `UNUserNotificationCenter`) — **no FCM/APNs, no server push**. A background
  worker runs the same `deriveNow` + `rank` over the already-synced cache plus
  the geofence-delivered location, and posts the engine's **top-K under a daily
  cap + quiet-hours**.

Two things make this a posture change the operator must ratify, not an
agent-buildable slice:

1. **It requests the "Always" / background location permission.** ADR 0014 §4
   reserves "Always" as an *explicit opt-in upgrade* for background proximity,
   and §6 puts "background geofencing + Always location" in "a later milestone,
   beyond the M0 dumb renderer." Crossing that line is a privacy-posture move
   plus an App-Store-justification obligation (guardrail #3/#4).
2. **It needs a customer-facing disclosure review.** Background location +
   user-visible notifications are exactly the surface a family will judge the
   "your live location never leaves the device" promise against. The honesty of
   the copy is load-bearing (see INB-13 below).

## Decision (proposed — for operator ratification)

Adopt the following Phase-B posture. **All of it is local-only; the server is
untouched** (no FCM/APNs, no server push, no server ranking, no derived-item
persistence — the ADR 0039 §7 dumb-server invariant holds end to end).

1. **Background location = "Always", opt-in, progressive, reversible.** Never
   requested up front. The user reaches it only via an explicit primer after
   when-in-use is already granted (ADR 0014 §4). The primer is honest about the
   tradeoff (background proximity ⇒ "Always"). A settings toggle turns it off;
   turning it off de-registers all geofences. The OS permission state is a
   **NEVER-SYNCS** item (ADR 0024) — device-local only.

2. **Geofence registration = nearest-N, on-device, capped.** The device
   registers the nearest-N places/triggers within platform limits (**iOS ~20
   regions** — honored explicitly; Android ~100) per ADR 0028's nearest-N
   selection. The geofence engine never streams continuous GPS; it reacts to
   OS-delivered region-enter events. Live position is consumed on-device and
   **never leaves** (ADR 0014).

3. **Notifications = LOCAL only; the SAME engine, top-K under a daily cap.** No
   push vendor is introduced. On a geofence/time trigger, a background worker
   runs `deriveNow` + `rank` (unchanged in spirit) and posts the top-K. The
   notification-specific knobs are **injected `RankConfig` config, not an engine
   fork**:
   - **quiet-hours** — suppress non-urgent items inside a device-local window;
   - **daily notification cap** — at most N notifications/day.
   Both are **device-local, never-synced** config (ADR 0024; syncing them would
   be a who-saw-what behavioral leak, same reasoning as the ADR 0039 hide-state
   leak). `rank()` stays pure and deterministic — clock, location, and
   quiet/cap config are injected.

4. **Tapping a notification deep-links into the item** via the existing
   Phase-A `CardAction.OpenHub` transform + arrival pulse — no new deep-link
   surface.

5. **Contentless by construction.** Because notifications are composed
   on-device from already-synced family content, no content crosses a new
   boundary. (Were a server push ever added later, ADR 0015/0017 require it be a
   contentless "family X changed, sync now" signal only — but Phase B adds no
   push at all, so the question does not arise here.)

## Operator-gated questions this ADR records (do not agent-decide)

- **Ratify the background-location posture itself** (the "Always" opt-in +
  geofencing) — guardrail #3/#4, the core of this ADR.
- **Approve the disclosure-review requirement** and its copy posture: every
  Phase-B surface must pass the INB-13 §6b honesty bar before ship (see Gate A
  below). The reusable "matched on your device · your live position never
  leaves" affordance is the brand-load-bearing piece; the **false** "saved place
  coords never leave the device" claim is a P0 bug to kill first.
- **Confirm ADR 0043's Phase-B phasing still holds**: local notifications, **no
  FCM/APNs**, no server changes.
- **Pricing/spend:** none — Phase B introduces no vendor and no recurring cost
  (local notifications + OS geofencing are free). Recorded explicitly so the
  spend guardrail is visibly clear.

## The two gates Phase B is blocked on (status as of 2026-06-30)

**Gate A — ADR 0008 design-first: NOT met.** The Phase-B surface
(background-location "Always" primer, the LOCAL notification + lock-screen
treatment, the offline / geo=on states) lives in the `triggers/` design brief.
Only a **first-pass** set exists (`designs/Family AI dashboard design brief/
designs/triggers/`), and **INB-13 is still open** (also listed under
`backlog/now.md` "Operator actions pending"): the 3-agent review flagged a **P0
HONESTY bug** ("saved place coords never leave the device" is FALSE per ADR
0014 — only *live position* stays local) plus a v2 fix-list
(`designs/DESIGN-BRIEF-triggers.md §6b`: privacy copy, the four M3-Expressive
signatures, the missing offline screen, geo-labeling). The §6b fix-list has
**never been handed back to Claude Design**, and there is **no operator
sign-off** for the Phase-B surface (contrast INB-28, which explicitly signed off
`designs/now-derived/` for Phase A *and* explicitly excluded "background
location / notifications / new permission" as Phase B).

**Gate B — background-location posture: NOT ratified.** No ADR or amendment
ratifies the "Always" permission for Phase B; INB-28 deferred it by name. This
ADR is the proposed vehicle for that ratification.

**Both gates must close before any Phase-B implementation code is written.**

## Rationale

- **No engine fork.** ADR 0043 §2b already committed to one shared engine;
  Phase B adds injected config (quiet-hours + daily cap), not a second ranker.
- **Privacy stays structural.** Live position never leaves the device; the new
  permission is opt-in/reversible; permission + quiet/cap config never sync.
- **Server-blindness tightens further.** Phase B adds zero server surface — no
  push vendor, no ranking, no persistence.
- **Honesty first.** The disclosure review (Gate A / INB-13) is mandatory
  precisely because background location is where a family tests the promise.

**Rejected:** server-side push (FCM/APNs) for notifications — needs a vendor +
credentials (spend/vendor gate) and re-opens the contentless-signal question for
no benefit (the device already has the content); up-front "Always" prompt
(off-brand, App-Store friction); a notification-specific second ranker (violates
the one-engine decision).

## Consequences

Positive: the built engine now earns its keep when the app is closed; calm,
local, contentless notifications; privacy posture intact; no new cost/vendor.

Negative / costs: a new OS permission to request + justify (App-Store review
obligation when shipped); background-worker + geofence platform glue per target
(expect/actual; real battery/lifecycle care — geofence events, not continuous
GPS); the iOS 20-region cap forces nearest-N selection; a new device-local
config surface (quiet-hours/cap) to manage.

## Phasing / sequencing

1. **Close Gate A** — hand `designs/DESIGN-BRIEF-triggers.md §6b` (INB-13) to
   Claude Design; produce the v2 Phase-B mockups (Always primer, local-notif +
   lock-screen, offline/geo=on, the honesty-fixed privacy affordance); operator
   signs off.
2. **Close Gate B** — operator ratifies this ADR (flip Proposed → Accepted).
3. Only then run the Phase-B build loop (the slice plan in the task brief).

**Separable, ungated now:** the small Phase-A carryover — wiring the
render-driven **record-shown EFFECT** (action → effect → DB → `surfacingFlow`
bridge, debounced) so anti-nag decay's `last_shown` clock actually starts — is
**foreground-only, touches no new permission and no background surface**, and
operates over the already-signed-off `designs/now-derived/` feed. It can proceed
independently of both gates if the operator wishes (an explicit user-initiated
*dismiss control*, if it needs a new affordance, would still want a mockup).

## Revisit Trigger

Reconsider if: a platform changes background-location rules; the calm budget
can't tame notification noise in dogfooding (→ engine retune, not a server
deriver); or a future realtime need forces a push vendor (→ new ADR; contentless
signal per ADR 0015/0017).

---

Composes 0043 / 0014 / 0028 / 0024 / 0039 / 0015 / 0017. Design brief:
`designs/DESIGN-BRIEF-triggers.md` (§6b fix-list). Engine: `NowRank.kt`,
`NowDerive.kt` (Phase A, PR #257).
