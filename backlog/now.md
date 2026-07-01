# Backlog — Now

## ⚠ Time-sensitive (hard dates — keep pinned at top)

- **Quarterly:** re-check whether Google ships a *free, family-shared*
  Gemini Daily Brief variant (KS-6 / OQ-gemini-family). First check ~2026-09.
- **Next P0 viability review due 2026-07-18** (or +10 iterations).

Stage: **M0 render prototype BUILT + cloud-live (2026-06-19).** server · Kotlin
CLI · KMP/Compose client · feed — on Vercel + Neon, rendering on the Pixel 10.
Validation verdict still stands: **CONDITIONAL — learning-lab GO, business
NO-GO** → **building to learn**; the business unknowns (OQ-wtp / niche / gemini)
are **untouched by design**. The "brains" (G1 authoring loop) is a deliberate
later milestone; interim authoring = operator + Claude Code via the CLI.

**Status update (2026-06-30): Hub Timeline — Phase 1 BUILT (authored content type + on-device
presentation, ADR 0045).** Worktree `derived-now-phase-b`, branch **`feat/hub-timeline`** (off the
phase-B HEAD — the timeline reuses `NowDerive` date helpers, absent on `main`). Imported the
`designs/hub-timeline/` hi-fi mock from Claude Design, 6-agent review (correctness/gaps/xplat +
completeness/UX-M3/data-systems), brainstormed the source model, wrote `specs/hub-timeline-design.md` +
**ADR 0045 (Accepted)**, revised + re-signed-off the mock (Gate A), planned
(`docs/superpowers/plans/2026-06-30-hub-timeline.md`, 2 adversarial rounds), and built it subagent-driven
(16 task-units, TDD, per-task review). **Shipped:** `Hub.timeline` authored property (schema + server +
CLI content-blind structural validation), a pure on-device `TimelinePresenter` (status/scale/NOW/grouping/
windowing, injected clock+tz — the multi-member wedge holds cross-tz), day-rail + hub-roadmap cards, a
scrollable detail, nav substate + BackNav, attachment→CardAction handoff, and the card→detail
shared-element morph. **Phase 1 = authored, render-only, Now-invisible, zero notifications**; provenance
copy is authored-honest (no "derived on-device" claim). Full-stack gate green (codegen · api 345 · CLI ·
client desktopTest).

**Status update (2026-07-01): Hub Timeline — Phase 2 BUILT (branch `feat/hub-timeline-phase2` off
`origin/now-derived-phase-b`).** Six commits, TDD, on-device-verified on emulator vs `designs/hub-timeline/`.
**Shipped:** (S1) tz-aware **AM/PM** stop labels moved into the presenter — killed the raw-`at`
string-parsers + DST/offset risk; robust detail-day NOW index. (S2) roadmap **`✓N` collapse**
(`SpineNode.collapsedCount`; replaces the +M-more tail). (S3) per-member **"Hide for me"** on the hoisted
card (synthetic id, reuses the W5 hide plumbing; swipe + a11y action + recovery row). (S4) in-detail
**day↔hub scope toggle** (`SingleChoiceSegmentedButtonRow` + `hasBothScales`). (S5-fix) **Day scale scopes
to the focal day** (on-device caught roadmap milestones bleeding into "Today's schedule"). (S5) **authoring
enablement** — `dayfold template timeline` + `dayfold-curator` skill teaches timeline authoring. Enriched
the fake college hub to a both-scales demo. **On-device VERIFIED** (day card "2 done"/AM-PM, toggle→roadmap
month groups + tz "Mon D" labels + NOW band). Full gate green (`:client:desktopTest` + `apps/cli` test).
**Operator-gated remainder (loop STOPPED here):** (a) **4b card "also a roadmap/day" hint** — not in any
signed-off mock → design sign-off; (b) **ADR 0046** (client-derived `deriveTimeline` fallback — the
ADR-0043-class second on-device projection) drafted **Proposed** → accept + a derived-states mock;
(c) **dogfood** a real authored timeline onto the operator's own hub → external content; (d) **ship**
`now-derived-phase-b → main` → deploy/spend. Governance (family-tz delivery, NOW-marker calm tuning) revisit
vs real authored content.

**Bugfix (2026-06-30, on-device report) ✅ FIXED + VERIFIED ON PIXEL 10 PRO: Hub link/document blocks
were not tappable.** Root cause (two layers): (1) `LinkRow` (`HubScreens.kt`) rendered the "opens
externally" arrow but had **no click handler** — the installed `LocalUriHandler` (PlatformUriHandler,
used by inline body-links) was never invoked for structured blocks; (2) — found via the device DB —
**`document` blocks keep their URL in `ref`/`docRef`, not `url`** (real data: a Butler PDF at
`https://cdn.butler.edu/...Immunization-Req.pdf` in `ref`), so a first pass that only handled `url` left
the actual tapped blocks inert. Fix: `LinkRow` is `clickable` when ANY of `url`/`docRef`/`ref` vets as
https → `LocalUriHandler.current.openUri(it)` (a non-URL ref stays inert). TDD: `HubLinkTapTest` (link,
document-ref, inert-ref) red→green; **662 desktop tests green**. **On-device: tapped "2026-27
Immunization" on the Pixel → Chrome opened the Butler Health Services PDF.** (Lesson: the unit test first
encoded the wrong assumption — real document `ref`s are https URLs; pulling the device DB found it.)

**Status update (2026-06-30 PM): Now derived surfacing — PHASE B BUILD STARTED (both gates closed).**
Worktree `derived-now-phase-b` (branch `now-derived-phase-b`, off origin/main). Operator resolved all
Phase-B gates in-session:
- **Gate A (ADR 0008): SIGNED OFF.** The v2 `designs/triggers/` mockup set (INB-13 §6b honesty rework +
  the opt-in ladder + closed-app notif/lock-screen + offline + reversible settings + the "Matched on your
  device" affordance) was imported from Claude Design (15 files) and operator-approved as-is. P0 "saved
  coords never leave" claim confirmed absent; honest two-part promise present. **Closes INB-13.**
- **Scope: FULL build incl. device glue** (Android device connected + iOS sim available → on-device
  verification IS possible; the "no-device" deferral is dropped).
- **Places: CLI/server-authored** (Add-place UI out of scope; list read-only; place-egress lane = future
  slice + own ADR).
- **Notif defaults RATIFIED:** cap 3/day, quiet 22:00–08:00, urgent (NOW/geo) bypasses quiet but counts
  to the cap. (Resolves the ADR 0044 `[pending-ratify]`.)
Plan (6-agent adversarial review: correctness/gaps/KMP/redux/simplification/UI-UX) →
`docs/superpowers/specs/2026-06-30-now-phase-b-plan.md`. **commonMain foundation BUILT + GREEN (604
client desktop tests, 0 fail):**
- **S0** pure notification-selection core (`NowNotify.kt`): `selectNotifications` over the SAME
  `RankedFeed` (NO engine fork); sibling `NotifConfig` (never `RankConfig`); quiet-hours wrap,
  daily-cap, dedup, foreground-suppression, `nearestNPlaces`, `notificationActionFor→OpenHub`, pure
  `postedTodayCount` rollover; `LocationPermission`/`NotificationPermission` enums — 13 tests.
- **S1** device-local NEVER-synced state: `notif_config` + `notification_log` tables (migration
  `8.sqm`, v8→v9, verifies clean), ContentStore `notifConfig`/`setNotifConfig`/`logNotification`/
  `notifLedger` + sync snapshot getter, `NotifConfigLoaded`/`*PermissionLoaded` reducer bridges +
  AppState slices, SyncEngine DB→store config bridge. wipe()=reset, wipeForResync()=preserve — 12 tests.
- **S1b** honesty copy fix (shipped Phase-A): geo `WhyChip` → "Matched on your device" (killed the false
  "· location never leaves"; ADR 0044 §3 P0).
- **S2 ✅ COMPLETE — Compose surfaces, snapshot-verified light+dark, 636 client desktop tests green
  (+29).** All map designs/trigger/* 1:1, M3-stable fallbacks (no M3E/CL-0b block):
  - `PrivacyAffordance.kt` (keystone) — chip/info-row/detail-sheet, honest two-part promise.
  - `PermissionLadder.kt` (9 tests) — opt-in ladder: locPrime/alwaysUpgrade(honest battery+closed-app
    trade)/notifPrime/limited/denied/downgraded; "Not now"/secondary is a full-color OutlinedButton PEER
    (never disabled); on-device promise rides every priming screen.
  - `ProximitySettings.kt` (7) — reversible toggle (Switch), off→"Geofences removed" + async
    "Removing…", permission row + device-local privacy line, quiet-hours editor (pure
    `formatMinuteOfDay`), daily-cap `SingleChoiceSegmentedButtonRow` (1/3/5, not a slider), dimmed-when-off.
  - `OfflineBanner.kt` (3) — "Offline · still matched on your device" (privacy teal, strength-not-error).
  - `NotifStates.kt` (6) — quiet-held card + cap-reached "You're all caught up · N of N" (no badge/count-urgency).
  - `PlacesList.kt` (4) — READ-ONLY list (no edit pencil/FAB), family-privacy row, "Added by Claude" provenance.
  - a11y `rememberReduceMotion()` already shipped + matches plan (Android ANIMATOR_DURATION_SCALE==0 /
    iOS UIAccessibilityIsReduceMotionEnabled / desktop false); Shimmer infinite anim already gated. ≥48dp
    hit-slop + content-descriptions + labelSmall(≥11sp) privacy chip applied.
- **S3 (commonMain core ✅ COMPLETE + GREEN — 649 client desktop tests, +13; iOS links; Android client
  compiles):** the testable HEART of the background path, no engine fork:
  - `BackgroundNotify.kt` (7 tests) — `planBackgroundNotifications(snapshot, nowIso, location, zone)`:
    builds a minimal `AppState` from a synchronous `NotifSnapshot` (cards+hubs+sections+blocks+places+
    surfacing+config+log) → calls the SAME `nowFeed()` + `selectNotifications`. Daily-cap rollover by
    local date from the log, within-day dedup, foreground-shown suppression (`FOREGROUND_SUPPRESSION_
    WINDOW`=30m via `surfacing.lastShown`). Live position injected, never persisted.
  - `NotifSeams.kt` (6 tests) — device-glue as **commonMain interfaces** (deliberate deviation from
    expect/actual: keeps logic in commonMain + unit-testable with fakes, every target green without
    half-built actuals; the real impls are the on-device remainder): `LocalNotifier`/`GeofenceController`/
    `ExactNotificationScheduler`/`LocationPermissionController`/`NotificationPermissionController` +
    `GeoRegion`/`NotificationSpec` + pure mappers (`notificationSubtext` honest provenance, `toNotification
    Spec`, `geoRegionsFor` nearest-N capped iOS-20/Android-100 w/ radius fallback). `BackgroundNotification
    Runner` (plan→post→log once) + `cancelForegroundVisible`, both faked-tested.
  - **WAL on the Android driver** (`DriverFactory.android.kt`) — single-writer/many-reader parity with
    desktop so the background pass reads the SAME process-shared cache; no 2nd connection.
  - **ContentStore SYNC snapshot getters** (3 tests) — `activeHubs/allSections/allBlocks/activePlaces/
    surfacing/notificationLog` + `notifSnapshot()` assembler (one read from the shared connection); proven
    end-to-end (`planBackgroundNotifications(store.notifSnapshot())`) over a real in-memory store.
  - **Android device-glue ✅ BUILT + ON-EMULATOR-VERIFIED (assembleDebug green; 2 instrumented tests pass
    on emulator-5558 API 35):**
    - `AndroidLocalNotifier.kt` — NotificationCompat BigText + group/group-summary digest + deep-link
      "Open" PendingIntent (extras → cold-start OpenHub) + honest on-device subtext; `POST_NOTIFICATIONS`
      (runtime; denial = no-op not crash). Instrumented test posts + asserts it lands in the system set.
    - `AndroidBackgroundNotify.kt` — `runBackgroundNotificationPass` (the receivers' shared entry: holder
      store → `notifSnapshot()` → `BackgroundNotificationRunner` → notifier + log), `AndroidGeofenceController`
      (GeofencingClient, MUTABLE PendingIntent, nearest-N regions, NEVER_EXPIRE/ENTER), `AndroidExact
      NotificationScheduler` (`setExactAndAllowWhileIdle`), `onGeofenceEnter`/`reRegisterGeofences`.
    - `AndroidPermissionControllers.kt` — Location (Denied/WhenInUse/Always from FINE+BACKGROUND) +
      Notification (Granted/Denied/Blocked) state truth + refresh() + OS-settings deep-link.
    - `AndroidContentStoreHolder` — ONE process-shared store/driver for fg+bg (single-writer); MainActivity
      now uses it. **WAL** enabled via the open-helper `onConfigure` callback (the `PRAGMA` returns a row →
      can't go through `driver.execute`; that was a real bug, fixed).
    - 3 manifest receivers (`GeofenceReceiver`/`ExactAlarmReceiver`/`BootReceiver`) + Play Services
      Location dep + FINE/BACKGROUND_LOCATION/BOOT/EXACT_ALARM perms. **`AndroidBackgroundPassTest` PROVES
      the whole headless chain on-device:** seed shared store → enable config → `runBackgroundNotification
      Pass` posts a real notification → 2nd pass dedups. (BootReceiver also observed firing on install.)
  - **Android FOREGROUND wiring ✅ (MainActivity; assembleDebug green; app installs + launches + runs on
    emulator-5558 with NO crash):** OS-permission bridge — controllers' Flow → `LocationPermissionLoaded`/
    `NotificationPermissionLoaded` (initial dispatch + collect; **refreshed every foreground** since Android
    emits no permission-change broadcast). Config reaction — `notifConfigFlow` enable → register geofences
    for saved places (capped); disable → `deregisterAll`. Notification-tap → `hubEngine.openHub(hubId,
    blockId)` from the notifier extras (cold-start in onCreate + warm in onNewIntent; extras consumed;
    dangling-target tolerant). Shared store via the holder.
  - **Exact-alarm scheduling pipeline ✅** (4 tests) — pure `planExactSchedules(snapshot, nowIso, horizon)`
    reads BOTH raw lanes directly (`deriveNow` + `cardToNowItem`, NOT `nowFeed` whose not_before gate would
    hide the very future authored items we wake for); keeps each subject's soonest future trigger within a
    48h horizon; fire-time receiver re-runs the full pass (cap/quiet/dedup honored then). Wired on Android
    (`reconcileExactSchedules` → `AndroidExactNotificationScheduler.setExactAndAllowWhileIdle`), armed on
    enable alongside geofences. So BOTH halves now fire closed-app: proximity (geofence) + time (exact alarm).
  - **Settings UI on-ramp ✅** (3 host tests) — `ProximitySettingsHost` (top bar + quiet-hours `TimePicker`
    dialogs + privacy `ModalBottomSheet`) is now NAVIGABLE: Account → "Background proximity" (`Route.Proximity`
    + `OpenProximity`/`CloseProximity` + reducer + BackNav). Toggle/cap/quiet → `onSetNotifConfig` →
    (shell) `ContentStore.setNotifConfig` → flow → `NotifConfigLoaded` → the geofence/exact-alarm reaction
    arms. `onSetNotifConfig` threaded through `FeedApp` (defaulted → other shells/tests unaffected); wired in
    MainActivity (IO dispatcher). Interaction-tested: toggle/cap emit the right config write, back closes.
**State: 659 desktop tests green · iOS links · assembleDebug green · 2 Android instrumented tests green on
emulator · app installs+launches+runs clean WITH the new nav. The Android notification ENGINE (background
pass + proximity + time) + foreground integration (permission reflection, geofence/alarm-on-enable,
tap→deep-link) + the in-app Settings on-ramp are on-device-proven. NO engine fork; live position never
persisted; config never synced; single-writer WAL.**
  - **In-app permission PROMPTS ✅ + content-change re-registration ✅** (MainActivity; assembleDebug green;
    app runs clean): enabling requests POST_NOTIFICATIONS + while-using location via `registerForActivity
    Result` (background "Always" correctly stays a reversible Settings trip — Android forbids an in-app
    dialog for it); each result refreshes OS truth into the store. Geofences + exact alarms also re-register
    on `nowContentFlow` change (place added/removed, new timed items), not just on enable.

  - **ON-DEVICE DRIVE on the Pixel 10 Pro (API 36, real Google session + synced content) ✅:** Account →
    "Background proximity" → Settings renders design-faithful → toggle On (config write + un-dimmed
    controls) → **in-app notification prompt → Allow** → **in-app location prompt → While using the app** →
    permission row live-updates to "While using the app" (the bridge + refresh-on-result works). **Bug
    found + fixed on-device:** two back-to-back permission `launch()` calls dropped the location prompt
    (Android shows one dialog at a time) → switched to a single `RequestMultiplePermissions` flow;
    reinstalled + re-verified. (Geofence ENTER → notification still wants a seeded place + arrival to fully
    close; the engine + the whole permission/settings on-ramp are device-proven.)

**Android side of Phase B is FEATURE-COMPLETE + on-device-verified (drive on the Pixel 10 Pro).** Remaining:
(1) PRIMING UX polish — show the built `PermissionLadderScreen` priming screens before the OS prompt on
first enable (today we jump straight to the system dialog; the state/flow all work). (2) geofence runtime
drive via emulator mock-location (flaky/hard to assert). (3) **iOS actuals** (UN/CL/UNCalendarTrigger +
BGTask via Swift AppDelegate, process-global delegate object on main thread, 20-region eviction) + iOS-sim
smoke — entirely UNBUILT; no iOS sim booted in this env, so build-only verification at best.

**Status update (2026-06-30): Now derived surfacing — PHASE B gate resolved by the operator;
build proceeds only on the ungated carryover (Gate A still blocks the notification surface).**
The loop stopped at the Phase-B gate (background geofence + LOCAL notifications, ADR 0043 §Phasing)
and surfaced both gates as **INB-29**; operator answered in-session:
- **Gate B — background-location posture: RATIFIED.** Operator "Accept ADR 0044 as written" →
  **ADR 0044 Accepted** (the "Always" opt-in/reversible posture; LOCAL notifications only — no
  FCM/APNs, no server change, dumb-server invariant intact; geofence nearest-N, iOS 20-region cap;
  quiet-hours + daily-cap as device-local never-synced `RankConfig` knobs; `rank()` stays pure;
  live position never leaves device).
- **Gate A — ADR 0008 design-first: STILL OPEN (mockups + sign-off pending).** Operator asked for
  a Claude Design prompt framing the feature as **opt-in**; delivered as
  `designs/DESIGN-BRIEF-triggers-v2-phase-b.md` (self-contained; folds in INB-13 §6b + opt-in
  ladder). **Phase-B implementation (geofence / local-notif / permission surfaces) stays BLOCKED
  on signed-off mockups** — so the build loop does NOT proceed on those surfaces yet.
- **Ungated carryover — ✅ BUILT + GREEN (operator "build it now"):** the render-driven record-shown
  EFFECT now starts the anti-nag clock (foreground-only over the signed-off `now-derived/` feed; no
  new permission/surface). TDD slice on branch `claude/now-derived-phase-b-*`:
  - **`NowEngine`** (commonMain, HubEngine-style, debounced) is the sole surfacing writer:
    render reports visible subjects → `noteShown` (coalesced) → `recordShownIfNew` → `surfacingFlow`
    bridge → `SurfacingLoaded` → `state.surfacing` → next `nowFeed()` recompute. Unidirectional; the
    render path NEVER writes surfacing. `dismiss` → `recordDismissed` → `rank()` omits the subject.
  - **Write-once clock fix:** new SQL `recordShownIfNew` (`ON CONFLICT DO NOTHING`) so continuous
    visibility STARTS the decay clock once and never RESETS it (overwriting `last_shown` each tick
    would defeat softening). Decay/soften (dormant since Phase A — nothing wrote `last_shown`) now
    engage. No schema change (query-only; `surfacing_state` unchanged).
  - **Render wiring:** `FeedScreen` reports `RankedFeed.visibleSubjectKeys()` (prominent bands +
    dedup peers, overflow excluded) via `LaunchedEffect(set)` → `onNowShown`, threaded through all 3
    shells (desktop/iOS verified compile; Android = verbatim mirror, CI-verified — no SDK in build env).
  - **579 client desktop tests green** (572 Phase-A baseline + 5 `NowEngineTest` + 2 `visibleSubjectKeys`);
    `compileKotlinIosArm64` clean; `verifyMigrations` drift is the pre-existing ADR 0036 `media`
    ordinal (CI-skipped) — my query-only addition verifies clean. **No visible dismiss CONTROL was
    added** (it would need its own Phase-B/trigger mockup); the dismiss DATA path + omission are
    built + tested. Grounding: `RankConfig` (`NowRank.kt:45`) + the "Quiet-hours deferred to Phase B"
    note (`NowRank.kt:18-19`).

**Status update (2026-06-30): Now derived surfacing — Phase A built (ADR 0043).** Operator
ratified both gates in-session (INB-28): **ADR 0043 → Accepted** + `designs/now-derived/` **signed
off**. Built as a TDD slice loop → **PR #257** (branch `claude/now-derived-surfacing-phase-a-*`):
- **Slice 1** — decode block `triggers[]` + `Place`; `places` flow through `/sync` to cache
  (migration `6.sqm`: `hub_block.triggers`, `place`, local-only `surfacing_state`).
- **Slice 2** — pure `deriveNow` over all 5 reason_kinds (countdown/milestone/checklist/geo/`when`)
  each with a computed "why"; `parseInstantFlexible` (date-only + instant); geo via haversine.
- **Slice 3** — pure **Priority & Ordering Engine** `rank`: score (urgency/proximity/importance/
  decay) → prefix-containment dedup → calm budget (now/soon/later bands + overflow never-drops) →
  score-snap hysteresis; local-only surfacing state (last-shown/dismissed, never synced).
- **Slice 4a/6** — `nowFeed` merge selector (both lanes, one engine, render-time clock+location);
  authored bounded `importance` (the one schema touch — `BriefingCard` + regen + `7.sqm`) the engine
  ranks; `not_before` gated on-device (closes **OQ-notbefore-gating**).
- **Slice 4b/5** — merged-feed render (`NowFeedList`: bands, why-chips, geo-active ring + "Nearby",
  count-free overflow, caught-up keeps horizon); deep-link tap → shipped hub-arrival transform+pulse.
**571 client tests green** (+37 from a 534 baseline); `verifyMigrations` + iOS compile clean; merged
feed **visually verified light+dark** via headless Compose-Desktop snapshot (no Android device in the
build env). Server stays content-blind (derived items client-only). 3-agent adversarial design review
applied before coding. **Phase B deferred** (background geofence + local notifications; the
render-driven record-shown effect; quiet-hours). ADR 0043 reviewed by a 3-agent design panel +
per-slice TDD + a final code-review pass.

**Status update (2026-06-29): two-way (member-writes) build STARTED.** Operator
ratified the two-way bundle in-session — ADRs **0038/0039/0040/0041/0042 → Accepted**
(0041 = the bounded-member-AI-command **constitution amendment**, applied; W3 ships
EXPERIMENTAL/flagged; member scope = global `content:write`; R2 confirmed; W2 = visible
hubs only; W5 hide = local-only first; INB-25/26 closed). Ratification merged via
**PR #238**.
- **Slice 1 (schema + reserved shape) — ✅ MERGED (PR #247)**: checklist item
  `id`/`doneBy`/`doneAt`/`ord` + codegen + Kotlin CI drift guard; CLI ULID
  stamp-on-push; migration 0015 reserves `op_log` + `created_by`/`author_kind`/
  `writer_user_id` on blocks+cards + `block_type`/`card_kind` ENUM→text + `content:delete`.
- **Slice 2 (server must-fixes / member-write security gate) — ✅ MERGED (PR #248)**:
  If-Match→412 (block+section), visibility-on-write (restricted→404, no oracle; 403 only
  visible-but-scope-denied; matrix 200/200/200/404), 410-on-tombstone (no member
  resurrection), op_log idempotency + 7-day TTL sweep, tolerant validator gated to
  plaintext-M0. **Refines ADR 0030 §6**: a hub-rewrite the caller can't see is now 404.
  320 API tests green.
- **Slice 3 (client sync engine / egress lane) — ✅ COMPLETE (PR open from
  `two-way-slice3-client-sync`)**: `ChecklistMerge` (per-item done-triple LWW,
  convergent + idempotent) + `OutboxSender` (412/410/backoff/cap FSM) + the egress
  wiring — `outbox` SQLDelight table + `version`/`local_state` columns (migration
  `4.sqm`), `ContentStore.enqueueBlockToggle` (optimistic apply), per-block-type merge
  dispatch in `applyDelta` + echo-suppress, `SyncClient.putBlock` (If-Match +
  Idempotency-Key), `SyncEngine.drainOutbox` under the sync mutex. **481 client tests
  green** incl. 2 headless egress integration tests (happy path + 412 re-merge converge).
- **Slice 4 (toggle UI) — ✅ COMPLETE (branch `two-way-slice4-toggle-ui`)**: the
  tappable `ChecklistRow` (whole-row 48dp, `Role.Checkbox` + state desc, coral check
  scale-overshoot + left→right strike wipe, one haptic tick, reduced-motion aware) →
  `HubEngine.toggleItem` → `ContentStore.enqueueBlockToggle` → `SyncEngine.drainOutbox`.
  Pure `ChecklistFold` burst machine (one shared ~2s debounce → batch fold into "N done",
  newest-first, count-only >20) + client `Ulid` minter, both TDD. Five-rung optimistic
  vocabulary off `local_state` (saving hairline / calm inline Retry, never a modal) +
  offline banner + "N saving · Sync now" queue pill; honesty chip "Shared with your
  family · synced when online". Interactivity gated on item ids (display-only lists stay
  static — the synced claim is only honest where a member-write boundary exists, D4).
  **503 client tests green** (+22); **on-device verified on the Pixel**: real
  tap → optimistic flip + strike → burst-fold "2 done" → pending pill → whole-block PUT
  → server `blk_chk` v1→v2 (`done:true`) → /sync echo clears pending. Threaded through all
  three shells (android/desktop/ios — compile-clean). Dev-infra fixes folded in:
  `ondevice-demo.sh` migration glob (`000[1-9]`→all, was skipping 0015 op_log → 500s),
  JAVA17 path (stable brew symlink, 17.0.18→17.0.19 drift), seed + fake checklist ids.
- **Slice 5a (W4 delete — server) — ✅ PR #253 (CI pending)**: `DELETE /blocks/:id`
  soft-delete + tombstone, no-oracle authz (absent/can't-see→404, no-scope→403,
  non-author→403 incl. owner-no-override, idempotent re-delete→204). **Operator
  decision (2026-06-29): members get a `content:delete` grant, author-gated to their
  own content** (distinct scope, carved out of `content:write`; granted to member app
  creds + CLI/loop). `upsertBlock` now stamps `created_by` set-once (INSERT only) =
  the author-gate substrate (minimal W2 stamp). 326 API tests green. *Implements
  accepted ADR 0038 §W4; the member `content:delete` grant is a new member-authority
  fact — fold into an ADR 0038 amendment or a short ADR if the operator wants it recorded.*
- **Slice 5b (W4 delete client + W5 hide) — ✅ COMPLETE (branch
  `two-way-slice5b-delete-hide-client`)**: **W4 delete client** — `created_by` propagated
  over /sync (`HubBlock.createdBy` + `hub_block.created_by` col + migration `5.sqm` +
  `upsertBlock`/SELECTs/`rowToBlock`); author-only delete sheet (`ModalBottomSheet`, calm
  warn — *absent* not disabled for non-authors; option gated on `createdBy == session.userId`);
  egress `SyncClient.deleteBlock` (DELETE + Idempotency-Key, no body/If-Match),
  `OutboxSender` 204→Acked, `SyncEngine.drainOutbox` dispatch on `op.type`,
  `ContentStore.enqueueBlockDelete` + `HubEngine.deleteBlock`. **Optimistic = honest
  "Removing…"** (mark `pending` + keep the row visible until the /sync tombstone confirms;
  reuses the five-rung vocab + survives offline) — a deliberate **deviation from the mockup's
  optimistic-remove + undo** (chosen for reuse + offline-correctness). **W5 hide** — LOCAL-ONLY
  `hidden` table (never synced, wiped on `wipe()`), `ContentStore.hide/unhide/hiddenIdsFlow`
  + pure `partitionHidden`, DB→store `hiddenBridge` → `state.hiddenIds`, swipe-to-hide
  (`SwipeToDismissBox`) + overflow `DropdownMenu` (the a11y path) both reach Hide,
  collapsed "Hidden for you · N" + "Show hidden" toggle + "You hid this" + Unhide.
  **Enabling fix**: the client now decodes its own user id from the access-token JWT `sub`
  (`jwtSub`, decode-only) — `AuthClient` had been minting `Session.userId = null`, which would
  have left the author-gate permanently closed on the real sign-in path. Threaded
  `onDeleteBlock`/`onHideBlock`/`onUnhideBlock` through FeedApp→HubsHost→HubDetailScreen + all
  3 shells; `Show hidden` is pure store state (dispatched in HubsHost, no shell seam).
  **509 client tests green** (+TDD: 204-ack, `created_by` round-trip, delete egress E2E,
  hide model/partition, reducer, JWT-sub, 7 delete/hide compose tests). **On-device verified
  on the Pixel**: author overflow → Delete → warn sheet ("Delete "12 guests…"?") →
  optimistic pill → DELETE → server `deleted_at` set → /sync tombstone removes the card +
  empty section; swipe → "Hidden for you" → Show hidden → "You hid this" → Unhide → restored.
  Dev-infra: seed `ondevice-seed.sql` now stamps `created_by` (blk_ov=u_dev author / blk_link=u_sam
  non-author) so the author-gate is exercisable on-device. *Pre-existing card/hub-ordinal
  `verifyMigrations` drift is unrelated + CI-skipped (confirmed identical on main); my
  `created_by`/`hidden` additions verify clean.*
- **Slice 6 (Freshness contract, ADR 0040 §3) — ✅ COMPLETE (branch
  `two-way-slice6-freshness`)**: **stale-cursor full-resync directive** — `/sync` flags
  `full_resync:true` + resets the scan to -∞ when the caller's 3-part cursor is older than
  the tombstone-retention floor; the client `wipeForResync()` (clears synced content + cursor,
  **preserves the outbox + local hidden set** — a staleness reset, NOT the tenancy-revocation
  `wipe()`) then rebuilds from the page. **Content-tombstone GC** — a new arm of `/cron/sweep`
  hard-purges soft-deleted rows (cards/hubs/sections/blocks) older than the floor (kept below
  it so any client synced within the floor never misses a delete). **One shared constant**
  `CONTENT_TOMBSTONE_RETENTION_DAYS` (default 90, env-overridable) gates both halves —
  **operator-gated value, shipped `[pending-ratify]` → INB-27**. Watermark-GC / push / realtime
  remain deferred drop-ins on the same cursor. TDD: +1 sweep GC test, +3 stale-cursor /sync
  tests (**API 335 green**), +1 client full-resync test (preserve outbox+hidden). No schema
  migration (reuses `deleted_at`); no new cron (existing sweep). Bundle `api/index.js` rebuilt.
- **Next**: deferred + gated last: W2 authoring (Author screen — `created_by`/`author_kind`
  enforcement, loop-never-edits-member-blocks, audience ⊆ caller, single-writer-per-block),
  W1 media (R2 — external/spend gate, confirm before provisioning), W3 add-context
  (EXPERIMENTAL/flagged — recurring AI-spend gate, confirm before first real run).

**Status update (2026-06-26): first real on-device sign-in is LIVE on prod.** Real
Google sign-in + foreground sync now work end-to-end on the Pixel against
`family-ai-dashboard.vercel.app`, after fixing a two-part prod-config gap (DB schema
behind the entire AUTH epic + missing token-signing env) and two in-app bugs
(sync token-refresh-on-401 #104, debug-drawer Logs bridge #106). See the DONE entry
under *Operator actions* for the full account + the `npm run preflight` recurrence
guard. Next real validation: operator drives sign-in → create-family → `dayfold
login` (device grant now works on prod) → CLI authoring → on-device render.

**Status update (2026-06-23):** TASK-KMP + TASK-SYNC done+merged. The **AUTH epic**
(ADR 0021; S1·S3·S4·S5·S6) and the **CL/Dayfold content epic** (CL-0…CL-9) all
merged to `main` (PRs #2–#21). **AUTH-S2 *real Google path* ✅ DONE + MERGED**
(PR #25, `main` 8c0ccec) — Firebase ID-token verify (JWKS, ADR 0027),
`/auth/firebase`, client `firebaseToken` seam, CI Firebase emulator job, S2 design
spec + operator runbook; brought the branch up to Gradle 9.4.1 + the debugdrawer
modules (PR #26). **Entire AUTH epic S1–S6 now shipped.** **Open inbox:** INB-3,
INB-13. **INB-19 PARTIAL (2026-06-22):**
rk ratified + pinned alpha02 (ADR-0019-realized); publishing `redux-kotlin-snapshot`
+ Homebrew-tap symlink fix still pending (operator-only) — **golden harness not yet
wired**, so hold CL-NAV/CL-10 build until it lands.
**ADR 0020 (TASK-SYNC) still *Proposed*** though built — operator may flip to
*Accepted*.

## Design-first gate (ADR 0008) — status

The **feed-only** M0 slice was built **build-first** (operator-directed) from the
initial Now mockups in `designs/`. ADR 0008 **still governs unbuilt surfaces**:
the **M1 trigger surface** needs its hi-fi mockups (trigger v2 = INB-13) **before**
it's built. **Event Hubs render: design gate CLEARED (INB-22, 2026-06-24)** — the
Hubs phone surface (INB-15/16) + content adaptive two-pane (INB-20) + the ADR-0030
visibility delta (`Hubs-Visibility.dc.html`, signed off) are all in; the content-
API enforcement is built (PRs #34/#35). Hub render is build-ready.

## Hub & card visual enrichment (ADR 0036) — MERGED to `main` (#177)

**Status: MERGED to `main` via #177 (2026-06-26).** Since shipped, the render kit gained
snapshot coverage (hub hero #191 + card thumbnail #192), accent-math memoization (#193), and
the curator skill was brought current (#212–214). Original delivery, implemented + green:
Hi-fi design imported to `designs/hub-card-enrichment/` (operator signed off as-is,
ADR 0008) + **ADR 0036 accepted** (Wikimedia-only image allowlist, hardened shared
validator). Delivered end-to-end:
- **Schema+codegen:** `Hub.media`/`BriefingCard.media` + block link/document
  `thumbnailUrl`(+alt) + contact `avatarUrl`/`accentColor`; Zod + Kotlin regen.
- **Migration `0013_visual_enrichment.sql`** (media jsonb + typeof CHECK on hubs+cards);
  client SQLDelight v3→v4 (`3.sqm`, media column on card+hub; block media rides payload).
- **Shared hardened validator** (https-only, exact-host allowlist, reject
  userinfo/punycode/alt-port/SVG, curated-icon enum, #RRGGBB) in **3 lock-step copies**:
  API `media-validation.ts` (Zod-refine layer on the PUT path), CLI `MediaValidation.kt`
  (wired into `Validate`), client `MediaValidation.kt` (Coil load guard).
- **Coil3 render** (HubRow leading tile, collapsing-capped hero banner, card icon+accent
  kind-chip + thumb, contact avatar→initials, link/doc thumb) with the
  **image→icon+accent-tile→default fallback ladder**; accent harmonized (decorative only).
- **Tests green:** API 244 (media round-trip + 422 evasion + sync carries media);
  CLI + client kotlin-test (URL/hex/icon accept-reject incl. evasion vectors); 7 Compose
  snapshots (fallback rung × light/dark × hero). Desktop + Android targets compile.
- **Deferred (noted):** structured *block* media round-trips server-side only after
  **ADR 0035** (block-payload reconciliation; live content is body_md-only today);
  typed-card (`TypedCardItem`) media + full scroll-collapse hero + ETag disk-cache-key +
  RTL snapshot = follow-ups; **Phase 2** (self-host/CDN, SSRF-guarded ingest, full HCT) deferred.

## Operator actions pending

- [x] **DONE 2026-06-26 — prod DB schema synced + auth env configured (agent, under
  ADR 0012 rails, operator-supplied the Neon string).** Diagnosing the first real
  on-device Google sign-in (`firebase HTTP 500`) revealed prod had only ever run the
  **legacy `HOUSEHOLD_SECRET` content path** — the prior "0006–0010 applied" note was
  inaccurate; the entire **AUTH epic (0002/0003/0008) + 0004/0005 + 0009 + the
  0010/0011 fanout** were never applied (incl. the subtree-revocation 0011 this item
  tracked). Applied all missing migrations → `npm run db:check` = in sync (17/17).
  Also: prod had **no `AUTH_SIGNING_KEY`/`AUTH_ISS`/`AUTH_AUD`** (token minting threw
  → `/auth/firebase` 500) — generated an Ed25519 key, set the three vars, redeployed;
  all auth endpoints non-500. **Sign-in + foreground sync now work on the Pixel.**
  In-app fixes shipped same day: sync token-refresh-on-401 (#104, the "Couldn't
  refresh" wedge) + debug-drawer Logs bridge (#106). Recurrence guards: `npm run
  preflight` (`env:check` #103 + `db:check` #93) + the migration drift-guard (#91).
  Durable fix for the manual-apply process = **ADR 0033 (Proposed)** — accept to
  build the tracked runner.
- [ ] **ADR 0031 (CLI Homebrew distribution) — review + accept/reject + setup.**
  Spike (`research/2026-06-25-spike-cli-homebrew-distribution.md`) + Proposed ADR
  recommend a one-line `brew install` via a first-party tap. Operator-gated steps:
  (1) **license / public-vs-private distribution decision** (repo is unlicensed; a
  public tap distributes the CLI publicly); (2) create `SloopWorks/homebrew-tap`;
  (3) add a `HOMEBREW_TAP_TOKEN` secret; (4) accept the ADR → then the inert
  `release-cli.yml` + formula land and `cli-v0.1.0` is cut. The packaging-ready
  build change already merged (#76).
- [ ] **INB-3** kill-checks (~2 hrs): Gemini Daily Brief + Maple+ hands-on;
  note the niche gap → feeds A1. *(Only matters if pursuing the business path.)*
- [ ] **INB-13** hand the trigger-design v2 fix-list (`designs/DESIGN-BRIEF-
  triggers.md §6b`) back to Claude Design before the M1 trigger surface.
- [ ] Counsel confirm for ADR 0005 (14+) — only if/when pursuing teen accounts.
- [ ] **INB-19 remainder** (operator-only): publish `redux-kotlin-snapshot` to
  Maven Central + fix `reduxkotlin/homebrew-tap` symlink (keg `bin/` empty →
  binary at `libexec/Contents/MacOS/rk`). Unblocks the `:client:snapshotUi`
  golden harness → prereq for CL-NAV/CL-10.

## State (2026-06-18 — post 6-agent review)

- **Spec-build loop SUSPENDED** (cron stopped) — process right-sized for the
  solo M0 build (kept: spec-gate + inbox + multi-agent reviews).
- **Decisions:** M0 = **plaintext** (live E2EE → M1, ADR 0017 gate); M0 surface
  = **briefing-feed only** (Hubs → next slice); redux **1.0.0-alpha01** (INB-11
  superseded 2026-06-19 — operator owns reduxkotlin; `f(store.state)→UI`).
- **Spec suite + impl plan + JSON-schema contract = done; schema freeze
  unblocked.** Ready to build the M0 spine.
- **INB-9 RESOLVED → TypeScript on Vercel (ADR 0018).**
- **✅ M0 PROTOTYPE BUILT (12 build-loop iterations; build loop now STOPPED).**
  server (TS/Hono/Postgres) · CLI (Kotlin) · client core (redux-kotlin 0.6.2) ·
  feed UI (Compose, headless-render-tested) — all green, CI-enforced on GitHub,
  every component multi-agent-reviewed + fixes applied. Full CLI→API→DB→/sync
  round-trip verified live; the feed renders. Deploy config ready (Vercel + Neon
  pooler). Tracker: `specs/prototype/00-build-spec-plan.md`.
- **Blocked only on operator gates for literal G1a:** **INB-12** (create Vercel +
  Neon → deploy, recipe in inbox) · **INB-14** (Android SDK/device; iOS = Mac).
- **✅ DONE + MERGED — `TASK-KMP` (apps/client → true KMP module).** Merged to
  `main` 2026-06-19 (merge `0d621e5`, pushed to origin; feature branch deleted).
  Single Gradle build at `apps/` (8.11.1 + AGP 8.7.2); `:client` = KMP —
  commonMain holds all shared logic+UI+SQLDelight+ktor sync; **android + desktop
  + iOS** targets all build from it. `:androidApp` = thin app dep on `:client`
  (srcDir borrow + ContentStore/Main excludes GONE → TASK-SYNC step 2 unblocked).
  SyncClient → ktor `suspend`; driver via expect/actual `DriverFactory`. 17
  desktop tests + snapshots green; Android APK assembles; **both iOS targets
  compile + debug framework links** (iosX64/intel-sim dropped — granular alpha01
  lacks that publication). **Operator-gated remainder (DoD "run gated on Mac"):**
  Xcode iosApp project (Swift @main + signing + sim run) + iOS sync-config
  plumbing → folds into **TASK-SYNC**.
- **✅ DONE + MERGED — `TASK-SYNC` (offline-first DB-as-SoT, ADR 0020).** Merged to
  `main` 2026-06-19 (merge `13db28b`, pushed; branch deleted). Delivered **R1**
  (instant offline cold-start), **R2** (foreground poll ~45s + sync-on-resume),
  **R4** (unidirectional `network→DB→store→UI`, crash-safe cursor in `sync_meta`).
  `SyncClient`→transport (`fetchPage`); new **`SyncEngine`** owns the mutex-guarded
  drain loop + DB→store bridge (`activeCardsFlow`→`CardsLoaded`) + poll lifecycle
  (`start`/`resume`/`pause`/`stop`, public `syncNow` = future push hook). Store is a
  pure DB projection (no network→store path); cursor removed from `AppState`. Desktop
  file DB + WAL; iOS native driver. **24 desktop tests green, Android APK assembles,
  iOS framework links.** Built subagent-driven (spec+plan in `docs/superpowers/`,
  3 review rounds + final whole-branch review). kotlinx-datetime bumped 0.6.1→0.7.1
  (`Clock`→`kotlin.time`). **OUT (deferred):** R3 background (WorkManager/BGTask),
  push (FCM/APNs/SSE), E2EE (ADR 0017), 2-way/outbox (ADR 0016), iOS sync-config,
  the `payload`/`$defs` richer card fields. **ADR 0020 still marked *Proposed* —
  operator may flip to *Accepted* now that it's built.**
- **Deferred by design (operator, 2026-06-19): G1 content-authoring loop ("the
  brains") = much-later milestone; interim authoring = operator + Claude Code via
  the CLI.**
- **Still-queued gaps: G2 usefulness signal, the `payload` $defs, the
  Claude-Design triggers v2 (INB-13) + M3-Expressive upgrades.**

## Done this period

- Bootstrap (2026-06-18): scaffold, ADRs 0001-0004, validation fleet, board.
- Event Hubs design + block-level deep-linking (ADR 0006).
- Prototype scope locked (ADR 0007); design-first gate added (ADR 0008).
- Inbox swept: INB-1/2/4/5/6/7/8 answered; INB-3 pending operator.
- Design system = M3 Expressive, adaptive (ADR 0009); design brief shipped;
  initial Now/Hubs/Auth mockups added; repo public on GitHub.
- Auth/family/invite architected (ADR 0010) → **5-agent review**
  (`research/design-review-auth-2026-06.md`) → **hardened (ADR 0011
  supersedes 0010)**: all-invites-owner-approved, email→push cut, device-
  grant anti-phishing, no-auto-link, per-request revocation, Firebase dedupe
  corrected, relational content tables. Spec + Hub schema hardened.

## Auth is now in ACTIVE BUILD (ADR 0021, supersedes the "later milestone" note)

Operator pulled auth forward 2026-06-19 (ADR 0021, extends 0011): build order
**S1→S3→S2→S4→S5/S6**. **AUTH-S1 (tenancy & token backbone) ✅ DONE + MERGED**
(`main` 5753b32): EdDSA tokens + refresh lineage + `authorizeTenant` (JWT + legacy
household path, default-deny, fail-closed, cross-tenant 404) + `/auth/{refresh,
signout}` + `POST /families` + JWKS + gated local-only dev-token. 51 tests + final
security review passed; legacy household token still works (cutover at S3). Details
+ carried debt (S3 refresh-grace, S4 per-family cred binding) in `backlog/next.md`.
**AUTH-S3 ✅ DONE + MERGED** (PR #2, `main` 1fc1918): RFC 8628 CLI device grant
(`/device/*` + owner approve + lazy-mint) + refresh ~20s grace + Kotlin CLI
`login`/`push`. Twice multi-agent-reviewed; CI green. Legacy household token still
works (removal gated). **UPDATE 2026-06-23:** S4 (owner-approved invites), S5
(sign-in/out + account flow), S6 (member roster, connected-devices, profile,
data-export, account soft-delete) all **DONE + MERGED** (PRs #4–#20); A8b auth
mockups imported (design gate cleared). **AUTH-S2 real Google path ✅ DONE +
MERGED** (PR #25). Full S1–S6 epic shipped. Prod deploy of the auth surface =
operator-gated (set `AUTH_*` env in Vercel).
