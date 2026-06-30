# Now Phase B — Background Proximity + Local Notifications — Build Plan

**Date:** 2026-06-30 · **Governs:** ADR 0043 (two-lane Now + on-device engine) Phase B,
ADR 0044 (Phase B posture, Accepted), ADR 0014/0024/0028.
**Status:** Plan — synthesized from a 6-agent adversarial review (correctness/ADR, gaps/lifecycle,
KMP, redux, simplification, UI/UX). Build gated on operator Gate-A design sign-off (ADR 0008) of
the imported `designs/triggers/` v2 mockups.

## Invariants (non-negotiable)
1. **No engine fork.** `rank()`/`deriveNow()` byte-unchanged. The background path calls the SAME
   `nowFeed(minimalAppState, ...)` the render path uses (incl. the authored lane + not_before gate),
   then a pure `selectNotifications` over the resulting `RankedFeed`. Quiet/cap = sibling
   `NotifConfig`, NEVER added to `RankConfig`.
2. **Dumb server.** Zero server change. No FCM/APNs. LOCAL notifications only. No derived persistence.
3. **Live position never leaves the device** (ADR 0014). Saved places DO sync (encrypted) — copy must
   say *only live position* stays local.
4. **Permission state + quiet-hours + daily-cap are device-local, NEVER synced** (ADR 0024).
5. **Opt-in, progressive, reversible, default-off** (ADR 0044 §1). `enabled` defaults false.

## P0s resolved in this plan (vs the draft)
- **Honesty (incl. shipped code):** kill every "saved place coords never leave" framing. Fix the
  EXISTING Phase-A `WhyChip` GEO label `NowFeedScreen.kt:169` ("· location never leaves") → chip reads
  `"Matched on your device"`; the true "your live position never leaves this phone" line lives only in
  the info-row/sheet body. Foreground-only, ungated.
- **No fork:** background worker reuses `nowFeed(minimalAppState)` — not a hand re-derive.
- **Single-writer SQLite:** one process-shared `SqlDriver`/`ContentStore`; enable WAL on Android (only
  desktop sets it today, DriverFactory.desktop.kt). Worker never opens a 2nd connection mid foreground
  write. Notif-ledger writes tiny + idempotent.
- **Time triggers:** a periodic worker CANNOT fire on time (WorkManager 15-min/Doze; iOS BGTask
  opportunistic). Known future instants (`when.at`, countdown, milestone) → schedule EXACT local
  notifications at author/sync time (`AlarmManager.setExactAndAllowWhileIdle` / iOS
  `UNCalendarNotificationTrigger`). Geofence + an opportunistic worker handle PROXIMITY only.

## Architecture
- **commonMain pure core (all device-agnostic logic lives here; actuals are dumb glue):**
  - `NotifConfig(enabled, quietStart, quietEnd, dailyCap)` (defaults off / 22:00–08:00 / 3).
  - `selectNotifications(feed: RankedFeed, nowIso, zone, notifConfig, log: NotificationLogView): NotifPlan`
    → `{toPost, held, capped}`. Top-K of `feed.now` (+ optional `soon`) in existing order; daily-cap
    via `log` (count notified_at ≥ local-midnight, zone-aware, pure rollover); quiet-hours defers
    non-urgent (urgent = NOW-band/geoActive bypasses quiet ONLY, still counts to cap). Wrap-midnight +
    DST handled in pure tests.
  - `nearestNPlaces(places, location, n)` reuse `haversineMeters`; iOS cap 20, Android 100; farthest-
    first eviction + distance deadband (hysteresis).
  - notification payload `{subjectKey, target}` → existing `CardAction.OpenHub` (pure mapping).
  - foreground-surfaced suppression: an item with recent `surfacing.last_shown` is not also notified.
- **expect/actual seams (thin):** `LocationPermissionController` (state Flow + currentState + request
  whenInUse/always + openOsSettings), `NotificationPermissionController` (separate axis: Android 13
  POST_NOTIFICATIONS / iOS UN auth / channel-blocked), `GeofenceController.register(regions: List<GeoRegion>)`
  / `deregisterAll()` (takes PRE-SELECTED regions), `LocalNotifier.ensureChannel/postGroup(specs)/cancelAll`,
  `ExactNotificationScheduler.schedule(at, spec)/cancel`, `ProximityWorker` (geofence-wake handler),
  `reduceMotion(): Boolean`.
  - **android:** GeofencingClient + PendingIntent→Receiver; NotificationCompat (BigText, setGroup/
    GroupSummary, addAction, subtext="Matched on your device"); AlarmManager exact; WorkManager only for
    opportunistic reconcile; BOOT_COMPLETED + MY_PACKAGE_REPLACED re-arm receiver.
  - **ios:** CLLocationManager region monitoring (≤20) + UNUserNotificationCenter (categories + actions
    + threadIdentifier grouping) + UNCalendarNotificationTrigger. **Delegates held in a process-global
    Kotlin `object`, created/called on the main thread.** `BGTaskScheduler.register` wired from the
    Swift `AppDelegate.didFinishLaunching` (NOT Compose/MainViewController) with Info.plist identifiers.
  - **desktop:** `LocalNotifier` = real best-effort (AWT SystemTray); geofence/exact/worker = no-op;
    permission state = constant denied; document "background dies on app close".
- **State / redux (ADR 0013/0020):**
  - Permission states are OS-owned → bridged from the controller Flow to a `*PermissionLoaded` action
    (sole-writer bridge alongside `SurfacingLoaded` in SyncEngine), pure `copy` reducer; **re-read on
    app resume** (Android emits no change broadcast — iOS delivers `didChangeAuthorization`). NOT DB-backed.
  - `NotifConfig` IS device-owned content → new never-synced local SQLDelight table (mirrors the
    `hidden` table); writes go UI→ContentStore→flow→`NotifConfigLoaded` (no optimistic UI→store shortcut).
  - `notification_log(subject_key, notified_at)` local-only table; survives `wipeForResync()`, cleared by
    `wipe()` (parity with `surfacing_state`).
  - Live location stays an injected selector arg (`nowFeed(..., location)`), never an AppState slice.
  - The headless worker holds NO `Store` — it reads a synchronous snapshot bundle (new sync getters for
    hubs+sections+blocks+places+surfacing), builds a minimal `AppState`, runs `nowFeed`+`selectNotifications`,
    posts, writes the log. Its only feedback to UI is the table, projected on resume.

## Slices (TDD)
**Ungated now (pure / foreground-only, no new permission/surface — argued like the Phase-A carryover):**
- **S0 — pure core (commonMain, desktopTest):** `NotifConfig`, `selectNotifications`, `nearestNPlaces`,
  payload→OpenHub, pure daily-rollover, foreground-suppression predicate. rank()/deriveNow untouched.
- **S1 — local state substrate:** never-synced `notif_config` + `notification_log` tables (migration),
  ContentStore getters/flows/writes, `NotifConfigLoaded` bridge + reducer; synchronous snapshot getters.
- **S1b — honesty fix:** correct `NowFeedScreen.kt:169` GEO chip copy + snapshot update.

**Gated on Gate-A sign-off (new surfaces / permission / glue):**
- **S2 — Compose surfaces (commonMain, phone-first; light+dark snapshots):** permission ladder
  (locPrime/alwaysUpgrade button-group with "Not now" as a full peer/notifPrime/limited/denied +
  the downgraded-but-functional state), Settings (toggle + permission row + quiet-hours `TimePicker` +
  daily-cap `SegmentedButton` + device-local banner + async de-registration state), Offline banner,
  the reusable "Matched on your device" affordance (chip/info-row/`ModalBottomSheet`), the two in-app
  notif states (quiet-held card + cap-reached, reuse `CaughtUpHeader`). a11y: `reduceMotion()` → static
  ring, FINITE pulse, ≥48dp hit-slop, privacy chip ≥11sp, content descriptions, focus order. **M3E
  signatures depend on adopting CL-0b (`MaterialExpressiveTheme`/`MotionScheme.expressive()`) first —
  else non-expressive fallbacks.**
- **S3 — Android LocalNotifier + notification fidelity demo:** real NotificationCompat output (copy +
  "Added by Claude"/"On device" subtext + grouped digest + actions) behind a debug-drawer trigger so the
  operator can SEE one fire in the no-device env. Channel-blocked detection.

**Deferred → Phase B.2 (device-only; not exercisable in this build env):** iOS background actuals +
AppDelegate wiring, geofence registration lifecycle matrix (boot/app-update/permission-downgrade/
place-edit/Play-Services/tenancy-wipe re-arm), the opportunistic proximity worker wiring + background
location acquisition (region→coord), exact-alarm time scheduling end-to-end, notification cold-start
deep-link hydration ordering + dangling-target fallback, iOS 20-region eviction on-device, adaptive
(tablet/desktop-Now/Wear) surfaces, battery dogfood. Each documented; nothing silently dropped.

## Operator decisions (resolved 2026-06-30, in-session)
- **Gate A (ADR 0008): SIGNED OFF** — imported `designs/triggers/` v2 mockups approved as-is.
  Both Phase-B gates now closed (Gate B = ADR 0044 Accepted) → full build unblocked.
- **Scope: FULL build incl. device glue.** Operator confirms an **Android device is connected and an
  iOS simulator can be made available** → on-device/sim verification IS possible; the "no-device" B.2
  deferral is dropped. iOS actuals, geofence lifecycle, proximity worker, and exact-alarm scheduling
  are IN scope and get real-device/sim verification.
- **Places: CLI/server-authored.** Add-place/Edit screens are OUT of Phase B; render the places list
  read-only. A place-egress lane is a future slice + its own ADR.
- **Notification defaults: RATIFIED** — dailyCap=3, quiet 22:00–08:00, urgent (NOW-band/geo-active)
  bypasses quiet but still counts against the cap. Device-local + user-editable.
- **CL-0b adoption** (MaterialExpressiveTheme/MotionScheme.expressive()) is a prerequisite for the §5
  M3E signatures — adopt first, else non-expressive fallbacks.

(Build-scope note: with the device available, the former "Phase B.2 deferred" items are folded back
into this build and verified on-device; the slice ORDER below still holds — pure core → state → UI →
platform glue → on-device verify.)

## Build progress (2026-06-30, branch `now-derived-phase-b`)
- **S0/S1/S1b ✅** (prior): pure selection core + device-local state + honesty chip fix.
- **S2 ✅ COMPLETE** — Compose surfaces, all map `designs/triggers/` 1:1 (M3-stable fallbacks, no CL-0b
  block): `PermissionLadder` (6 states, full-peer "Not now"), `ProximitySettings` (reversible toggle +
  segmented cap + quiet-hours + de-register states), `OfflineBanner`, `NotifStates` (quiet-held +
  cap-reached), `PlacesList` (read-only) + the `PrivacyAffordance` keystone. a11y `rememberReduceMotion`
  already shipped. Light+dark snapshots eyeballed.
- **S3 commonMain core ✅ COMPLETE** — `BackgroundNotify` (`planBackgroundNotifications` +
  `BackgroundNotificationRunner` + foreground-cancel), `NotifSeams` (device-glue as commonMain
  **interfaces** — a deliberate deviation from expect/actual: logic stays in commonMain + faked-tested;
  real impls verified on-device) + pure mappers, WAL on the Android driver. **NO engine fork** (reuses
  `nowFeed`+`selectNotifications`). **649 client desktop tests green; iOS links; Android client compiles.**
- **Remaining (one device-integration slice):** platform impls of the seam interfaces + ContentStore sync
  snapshot getters + worker/receiver/manifest + permission bridge + iOS BGTask AppDelegate; then S4
  on-device (Android emulators available) + iOS-sim smoke.

## Verify (per slice)
commonMain pure tests (selectNotifications/quiet-wrap/DST/cap/nearest-N/suppression) + Compose snapshots
(light+dark) + `verifyMigrations` + desktop green + iOS compile + Android CI. No on-device claims (no
device/sim in env) — those are B.2 acceptance.
