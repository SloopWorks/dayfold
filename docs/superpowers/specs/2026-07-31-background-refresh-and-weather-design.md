# Background refresh (ADR 0020 R3) + weather-conditional content

**Date:** 2026-07-31
**Status:** Design approved in-session; Part B gated on a new ADR + a design-first mockup.
**Scope:** `:client` commonMain + Android/iOS glue, content schema, CLI, curator skill.
**Depends on:** ADR 0020 (Accepted 2026-07-31), ADR 0040, ADR 0043, ADR 0044, ADR 0014, ADR 0049.

Two parts, deliberately in one spec because the second is a caller of the first.
**Part A** builds ADR 0020 R3 — the background refresh pass that no platform runs
today. **Part B** adds weather-conditional content, which rides that pass rather
than inventing its own cadence.

Part A needs no new ADR: it is already decided (ADR 0020 R3, reaffirmed by the
Accepted ADR 0040's cadence table) and queued as **TASK-SYNC REMAINING** in
`backlog/next.md`. Part B does need one — it changes the MVP feature boundary,
picks a vendor, and adds the client's first external network egress.

---

## Part A — Background refresh

### A.1 What exists today

| Concern | State |
|---|---|
| Foreground sync | `SyncCoordinator.resume()` starts a 45 s poll; `pause()` stops it. Works. |
| Cursor | `sync_meta(cursor, last_synced_at)` persisted in SQLDelight. Foreground, background, and cold start resume from one keyset cursor. **The hard part is done.** |
| Background sync | **None.** `SyncReason.BACKGROUND` and `SyncReason.PUSH` are declared in the enum and never called. |
| Android background | **Nothing.** No WorkManager dependency. The only wakes are `GeofenceReceiver`, `ExactAlarmReceiver`, `BootReceiver` — each runs the notify pass **over the local cache without syncing first**. |
| iOS background | **Already wired.** `apps/iosApp/project.yml` declares `UIBackgroundModes: [location, fetch, processing]` and registers `BGTaskSchedulerPermittedIdentifiers: com.sloopworks.dayfold.now.refresh`. `App.swift:30` registers the handler before `didFinishLaunching` returns, submits with a 15-minute `earliestBeginDate`, and re-arms on completion and on background entry. It calls `bgReconcile()` — regions and exact schedules only, never the sync engine. |

Two consequences worth naming. Cold start renders the last-synced cache and then
waits a network round-trip for the first foreground pass — R3's stated purpose is
precisely "the OS pulls `/sync` on a schedule so the *next* open is already
fresh." And background **notifications currently fire off content the device may
not have synced in hours**, because the receivers never sync. That is a latent
correctness bug in shipped behavior, not a new concern introduced here.

### A.2 The pass

One bounded, idempotent function in commonMain, called by both platforms —
mirroring the existing `runBackgroundNotificationPass` pattern:

```
backgroundRefreshPass(budget: Duration) : RefreshOutcome
  1. syncNow(SyncReason.BACKGROUND)     // one page, not a full drain
  2. refreshForecasts(staleCells, cap)  // added by slice B1; absent in A1
  3. reconcile()                        // geofence regions + exact schedules
```

Slice A1 ships steps 1 and 3. Step 2 is added by B1 and is a no-op whenever no
active content carries a weather condition — note that this is *not* gated on
`WeatherConfig`, which governs only the aggregate card (B.7): a weather-gated
card must evaluate correctly whether or not the aggregate is enabled.

**Bounded** because iOS grants roughly 30 seconds per wake, and an unbounded
first-page drain can exceed it. Each step checks the remaining budget and returns
early rather than overrunning. **Idempotent and resumable** because the keyset
cursor makes a partial pass safe — the next wake continues from where this one
stopped, with no gap and no double-pull. This is exactly the property ADR 0020 R4
built the cursor for; bounding the pass costs nothing because of it.

`RefreshOutcome` reports what actually happened (pages pulled, cells refreshed,
schedules re-armed, budget exhausted) for logging through the existing `Log`
front door.

### A.3 Android — WorkManager

New dependency (`androidx.work:work-runtime-ktx`), default `androidx.startup`
initialization, no custom `WorkerFactory`.

```
PeriodicWorkRequestBuilder<RefreshWorker>(
    repeatInterval = 30.minutes,   // the floor is 15; 30 is honest about what we get
    flexTimeInterval = 10.minutes)
  .setConstraints(Constraints(requiredNetworkType = CONNECTED, requiresBatteryNotLow = true))
  .setBackoffCriteria(EXPONENTIAL, 30.seconds)
  .build()

WorkManager.enqueueUniquePeriodicWork("dayfold.refresh", KEEP, request)
```

Deliberate choices, each with a reason:

- **`CONNECTED` and `BatteryNotLow` only.** `RequiresCharging` and `DeviceIdle`
  would starve it — the docs are explicit that "even if the defined repeat
  interval passes, the `PeriodicWorkRequest` will not run until this condition is
  met… delayed, or even skipped."
- **`enqueueUniquePeriodicWork` + `KEEP`**, re-enqueued on app start and on
  `BOOT_COMPLETED`. WorkManager persists its own work across reboots, so the
  re-enqueue is belt-and-braces; `KEEP` makes it free rather than duplicative.
  Use `UPDATE` only when the interval or constraints change.
- **Not expedited.** Expedited work is for user-initiated immediate tasks, draws
  on a standby-bucket quota, and would be an abuse of the mechanism for
  best-effort freshness.
- **`Result.retry()` for transient network failure only.** A 401 returns
  `Result.failure()` — the session coordinator owns refresh, and retrying would
  burn quota against a problem the worker cannot fix.
- **Log `WorkInfo.getStopReason()`** (Android 16+) through the existing `Log`
  front door. Without it, "the worker didn't run" is unfalsifiable.

### A.4 iOS — extend the existing BGTask

`bgReconcile()` becomes step 3 of `backgroundRefreshPass`. The registration,
`earliestBeginDate`, and re-arm discipline in `App.swift` are already correct and
stay as they are.

**One existing bug is fixed here.** `App.swift:30` calls
`setTaskCompleted(success: true)` but never sets `task.expirationHandler`. If the
work overruns, iOS kills the app and *reduces how often it schedules the task in
future* — a self-inflicted freshness penalty that is invisible until you go
looking. The handler must cancel the in-flight pass and call
`setTaskCompleted(success: false)`.

### A.5 What triggers a background pass

| Trigger | Runs | Why |
|---|---|---|
| WorkManager periodic (Android) | Full pass | Freshness. |
| `BGAppRefreshTask` (iOS) | Full pass | Freshness. |
| App enters background | Ensure work enqueued / submit BGTask | iOS already does this; Android gains it. |
| `BOOT_COMPLETED` | Re-enqueue (KEEP) + re-register geofences | Geofences genuinely drop on reboot. |
| Sync completion | `reconcile()` only | Content changed → schedules may be wrong. Already true on iOS. |
| Geofence enter / exact alarm | Notify pass only — **no sync** | Time-critical and quota-sensitive. Adding a network round-trip here would delay the notification it exists to deliver. |

### A.6 Honesty: never promise freshness

Neither platform guarantees a cadence, and the failure mode is worse than it
looks for a *calm* dashboard specifically:

- **Android App Standby Buckets.** Android 13+ moves an app to **restricted**
  after 8 days without user interaction, capping it at **one job per day and one
  alarm per day, even while charging**. An app designed not to demand attention
  is unusually exposed to this. Holding `ACCESS_BACKGROUND_LOCATION` exempts an
  app from restricted — dayfold requests it for geofences (ADR 0044), but only
  for users who opted in, so it is a partial mitigation, not a fix.
- **iOS.** Best-effort within roughly two days, and only if the app was used in
  the past week.
- **Force-stop** (Settings → Force Stop) cancels all WorkManager work until the
  next launch.

Therefore: the worker is an *opportunity* to refresh, never a guarantee. Every
freshness claim in the UI must derive from `last_synced_at` and the forecast TTL,
never from "the worker should have run." We document the buckets rather than
designing around them; the timeliness-critical path stays the exact alarm.

### A.7 Testing

`backgroundRefreshPass` is pure orchestration over injected seams — fake sync
engine, fake weather provider, fake reconciler — so budget exhaustion, partial
passes, and resumption are unit-testable with no platform involvement. Android
gets `TestListenableWorkerBuilder` coverage for constraint and retry behavior.
Real scheduling is verified on-device per the operator-driven pattern: agent
builds and reads logcat, operator drives the device.

---

## Part B — Weather-conditional content

### B.1 Position

Weather is already a token in the codebase and nowhere near a feature: `kind ∈
{action, info, weather, countdown}`, `ReasonKind.WEATHER`, and a "Weather" chip
in `sourceLabel()`. ADR 0043 answers weather today with **authored** cards —
Lane 2, "items whose reason/fact is not computable from synced family content."
The authored fact then goes stale on its own.

This design keeps ADR 0043's division and sharpens it: the agent still reasons
and still authors, but authors a *condition and its response* rather than a fact,
and the device evaluates the condition live. That is ADR 0014's contract applied
to a fourth signal — **the agent reasons, the client matches.**

Dayfold is not becoming a weather app. Weather never produces a weather report;
it qualifies content the family already cares about.

### B.2 Decisions taken

| Decision | Choice | Why |
|---|---|---|
| Query anchor | **Content-referenced places only** (`place_ref`, hub location block, authored lat/lng), rounded to a ~0.1° grid | The live device position is never sent to a weather vendor, so ADR 0014's core promise is untouched and the ADR is uncontroversial. Rounding costs nothing — weather is a km-scale phenomenon — and the vendor sees a town, not a school's address. |
| Item creation | **Never**, by default | Weather gates, boosts, and decorates authored content. No authored card about the game means rain produces nothing. In slices B1–B2 the derived lane therefore keeps ADR 0043's "synthesis from synced family content only" property intact. **B3's aggregate card breaks it** — it is a derived item with a network input, so the ADR must amend ADR 0043 explicitly rather than let the change happen by omission. That is a further reason it ships last and behind a flag. |
| Aggregate card | Behind a flag, **off by default** | See B.6. |
| Condition model | **Closed vocabulary + optional threshold** | Readable, testable, forward-safe (unknown condition ignored, never fatal). Matches how `kind`, `type`, and `icon` already work. |
| Provider | **Keyless HTTP behind a `WeatherProvider` seam** | No secret ships in the binary, no server proxy, and no server involvement — which keeps a future E2EE flip (ADR 0015, still Proposed) possible. Vendor swap is one class. |

### B.3 Content model — two mechanisms, because they mean different things

`triggers[]` is a **disjunction of boosters**: any trigger firing surfaces the
item. "Only show when raining" is a **conjunction over visibility**. Folding the
second into the first would silently invert the array's meaning, so they stay
separate.

**Trigger** — a fourth variant of the existing `oneOf`, exactly where `activity`
sits as a reserved slot:

```json
{ "weather": { "condition": "rain", "min_mm": 1.0,
               "place_ref": "pl_field",
               "window": { "from": "14:00", "to": "18:00" } } }
```

**Gate** — a new top-level field on cards and blocks:

```json
"show_when": { "weather": { "condition": "rain", "place_ref": "pl_field" } }
```

`show_when` rather than `weather_gate` so a later condition type needs no new
field name. It is applied in `nowFeed`'s existing `visible` filter, immediately
beside `notBeforeReached` — same place, same shape, no new layer.

Vocabulary: `rain | snow | wind | hot | cold | clear`, each with an optional
threshold (`min_mm`, `min_kph`, `min_c`, `max_c`). An unknown condition is
ignored, so the vocabulary can grow without invalidating authored content.

### B.4 Evaluation

Three new units:

```
WeatherProvider  (commonMain interface)  — suspend fun forecast(cell): Forecast?
WeatherCache     (SQLDelight table)      — cell → hourly forecast + fetchedAt, TTL'd
weatherMatches() (pure fn)               — condition × forecast × window → Boolean
```

`nowFeed(state, nowIso, location, **weather**, …)` gains a fourth injected
ambient input: a `WeatherSnapshot` of already-fetched forecasts keyed by grid
cell. It stays pure — no IO enters the selector, mirroring how location is
resolved outside `deriveNow`. `NotifSnapshot` gains the same field so the
background pass reads forecasts synchronously from cache, exactly as it reads
places. `BackgroundNotify.kt`'s **"NO ENGINE FORK"** invariant is preserved:
foreground and background still call one `nowFeed`.

Fetching lives in `refreshForecasts` (Part A step 2): the distinct grid cells
referenced by weather conditions in *active* content, typically under ten per
family, refreshed when older than a 1–3 h TTL. Roughly 40 calls/day/family — both
candidate providers' free tiers are orders of magnitude clear of that.

### B.5 Scheduling — a forecast converts weather into a known instant

This is what makes the feature cheap. After each forecast refresh, for every
weather-conditioned item, compute the first instant the condition turns true
inside the forecast horizon. That is a `triggerAtIso`, which flows into the
**existing** `planExactSchedules` → `ExactNotificationScheduler`. No new wake
mechanism, no polling loop for the notify path.

**iOS needs one extra step, and it is in scope.** Android re-runs the full pass
at fire time, so a forecast that changed self-corrects. iOS pre-bakes the spec at
schedule time (`IosBackgroundNotify.kt:16`) and fires it as-authored — a 3 pm rain
alert armed at 8 am would fire even if the rain cleared at 2. So every forecast
refresh must call the existing `reconcileExactSchedules`, which already runs on
config and content change; weather becomes its third reason.

### B.6 Failure is fail-open, and silent about it

House precedent is unambiguous: `notBeforeReached` documents that "an unparseable
`not_before` never hides the card," and `feedCards`' expiry filter fails open the
same way. The harm asymmetry agrees — hiding "pack jackets" when it *is* raining
is worse than showing it when it isn't.

- No forecast, a stale forecast, or a provider error → **the gate does not
  hide**. Content degrades to exactly today's behavior.
- But the card must not *claim* weather it never verified: the weather chip and
  icon render **only** on a fresh confirmed match. Unverified means no chip, not
  a guessed one. This keeps ADR 0014's honesty posture intact.
- Stale beyond TTL is treated as absent rather than trusted.

### B.7 The aggregate card (flagged, default off)

`WeatherConfig(enabled = false)` mirroring `NotifConfig` — device-local, off by
default, flippable from the debug drawer for dogfood.

When enabled, and when two or more weather-matched items share an overlapping
window and grid cell, one derived aggregate surfaces: *"Rain 3–6 pm — affects
soccer pickup and the party setup."* Constituents **collapse under it** rather
than being replaced; silently removing operator-approved content is the wrong
default.

Two hard constraints:

- **Recommendations are authored, never computed.** The device cannot reason —
  on-device LLM was assessed **NO-GO 2026-07-13** (4 k context, no structured
  output, no tool calling). "Move the setup indoors" is written by the curator in
  advance, attached to the condition, and merely *selected* when it holds.
  Practically the curator authors both branches of a weather-sensitive plan. This
  is also the better product: a specific instruction beats a generic hedge.
- **Icons are device-derived, not authored.** The author writes `rain`; the device
  knows it is heavy rain at 4 pm and picks the glyph. Hand-authored weather icons
  would eventually contradict live conditions.

The forecast link is a plain `https` link (already an allowlisted scheme in
`packages/linkrules/Schemes.kt`).

### B.8 Provider — the vendor is the reversible part

Both candidates are keyless-capable and swappable behind the seam. Neither is
chosen here; this is a vendor decision for the ADR.

- **Open-Meteo** — no key, no account, no attribution. Free tier is **non-commercial
  only** (10 k calls/day, 5 k/hour), and "commercial" explicitly includes apps with
  subscriptions or ads. Fine for a learning lab and dogfood; a licensing problem
  the day dayfold charges, resolvable via their paid tier.
- **NWS / weather.gov** — free, US-only, US-government public domain, no commercial
  restriction, requires a `User-Agent`. Sufficient for dogfood and a likely MVP
  market.
- **Apple WeatherKit** — 500 k calls/month free on the $99/yr Apple Developer
  Program already required for iOS distribution, commercially licensed, and its
  mandatory attribution link doubles as the forecast link. **Rejected as the first
  impl** because REST requires ES256-signed JWTs: a private key cannot ship in an
  app binary and Android has no native path, so it forces a server proxy. That
  proxy is harmless today (E2EE is Proposed; the server already holds place
  coordinates) but would block a future E2EE flip by requiring plaintext
  coordinates server-side purely for weather.

### B.9 Testing

The matcher is pure, so the bulk is fixture forecasts × conditions → matched/not,
with no network. A fake `WeatherProvider` keeps `:client` and `:ui` suites
offline. Aggregate-card and icon rendering go through the existing per-OS golden
snapshot suite (macOS + Linux sets; see `processes/agent-dev-loop.md`).

### B.10 Gates before Part B ships

1. **New ADR** — changes `specs/mvp-feature-boundary.md:77` ("Weather/commerce APIs:
   OUT"), picks a vendor, and introduces the client's first external network
   egress. Not agent-decidable.
2. **ADR 0008 design-first** — the aggregate card and the weather chip need a
   `designs/` mockup with operator sign-off before build.
3. **Curator skill + CLI** — new authoring section, a `dayfold template` starter,
   and the rule that weather-sensitive content authors both branches.

---

## Sequencing

| Slice | Contents | Gate |
|---|---|---|
| **A1** | `backgroundRefreshPass` + Android WorkManager + iOS BGTask sync + expiration-handler fix | None — ADR 0020 R3 Accepted |
| **B1** | `WeatherProvider` seam, cache, pure matcher, fake provider | New ADR |
| **B2** | `show_when` gate + `weather` trigger through `nowFeed`, schema, CLI template, curator skill | New ADR |
| **B3** | Aggregate card + iconography, behind `WeatherConfig` | New ADR + design mockup |

A1 is independently valuable and ships alone: it makes cold start fresh, and it
fixes background notifications firing off unsynced content.

## Open questions

- **OQ-weather-vendor** — Open-Meteo (licensing cliff at monetization) vs NWS
  (US-only). Operator + ADR.
- **OQ-weather-tz** — a condition's `window` (`"14:00"–"18:00"`) is wall-clock;
  resolve it in the hub's timeline `tz` where one exists, else the device zone.
  Needs confirming against how `DeriveTimeline` already handles `tz`.
- **OQ-refresh-interval** — 30 min is a starting point, not a measurement. Revisit
  against real battery and bucket data from dogfood.
