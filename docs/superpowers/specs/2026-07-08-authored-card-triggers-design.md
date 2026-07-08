# Authored BriefingCard triggers — client consumption (issue #299)

**Date:** 2026-07-08
**Status:** Design v2 (post 3-agent review; ADR 0049 Accepted / Option A)
**Issue:** SloopWorks/dayfold#299
**Related:** ADR 0043 (priority & ordering), ADR 0044 (background notify), ADR 0014
(location privacy), **ADR 0049 (content-authored geofences — Option A)**

## Problem (corrected after review)

An authored `BriefingCard`'s `triggers[]` (`when.at` + `alert_offset`, `geo`) are
**not even decoded** client-side, let alone consumed:
- The client `Card` model (`Model.kt`) has **no `triggers` field**; the `card` SQL
  table has **no `triggers` column**; `upsertCard`/`rowToCard` never touch triggers.
  So triggers are dropped at decode (`ignoreUnknownKeys`). (Only `HubBlock` carries +
  consumes triggers today, via `deriveNow`.)
- Even once decoded, the authored lane (`NowFeed.cardToNowItem`) bands/schedules off
  `not_before`; `alert_offset` is read nowhere.

So an authored card with a time and/or location trigger neither surfaces nor notifies
on those triggers.

## Goal & scope

Consume an authored card's `when` and `geo` triggers for Now-feed
surfacing/banding and notifications, **without** changing what `not_before` /
`expires_at` mean (visibility window).

**Per ADR 0049 (Option A):**
- **Time (`when`)** — adopted fully (banding + local exact-alarm notify + `alert_offset`).
- **Geo (`geo`)** — **foreground on-device surfacing** for any authored geo trigger;
  **background** geofencing stays **user-curated** — an authored trigger reaches the
  background only via `place_ref` to a saved place (already geofenced via
  `activePlaces()`). **No new geofence source, no `MainActivity` change, no per-family
  region cap.** A coord-only authored geo trigger surfaces in the foreground only.

Non-goals: hub-block derived lane (unchanged); server/API changes; content-authored
background geofences (ADR 0049 Option B — not adopted); iOS/desktop notify parity
(unchanged — see platform matrix).

## Core principle

- `not_before` / `expires_at` = **visibility window** (unchanged).
- `triggers[]` = **relevance signal** (banding + notify wake + foreground geo),
  decoupled from visibility.

## Design

### 0. Decode card triggers (the missing chain — from review F1)

Cards must carry triggers before anything can consume them. Mirror the existing
`HubBlock` trigger plumbing:
- **`Model.kt`** — `Card` gains `val triggers: List<BlockTrigger>? = null` (reuse
  `BlockTrigger`/`TriggerWhen`/`TriggerGeo`; the authored card wire shape is identical
  — `when.at`/`alert_offset`, `geo.lat/lng/radius_m/label/place_ref`).
- **`Content.sq`** — add `triggers TEXT` to the `card` table + `insertCard`/`selectCards`
  (mirror `hub_block`'s column + `TRIGGERS_SER` usage; "synced-from-server, never written").
- **`migrations/11.sqm`** — `ALTER TABLE card ADD COLUMN triggers TEXT;`
- **`ContentStore.kt`** — `upsertCard` encodes `card.triggers` with the existing
  `TRIGGERS_SER`; `rowToCard` (both card projections) decodes it.
- **`CLIENT_SCHEMA_VERSION` 2 → 3** — a behavior-affecting synced field on already-cached
  rows; `reconcileSchemaVersion` forces one resync so existing devices backfill triggers
  (the #283 discipline; preserves outbox/hidden/session).

### 1. Time (`when`) — banding + notify (folded anchor)

The `alert_offset` is **folded into the anchor** — no new `NowItem` field, no
`planExactSchedules` change (review simplification; the 3-bucket band makes the
band-by-event vs band-by-alert difference sub-perceptible, and folding avoids the
horizon/`≤now`-filter bugs of a split offset).

- New pure helper `applyOffset(atIso, offsetIso, zone): Instant?` — parse the RFC-3339
  instant + the ISO-8601 duration via **`kotlin.time.Duration.parseIsoString`** (stdlib,
  all KMP targets), add them. Wrapped in `runCatching` → malformed offset falls open to
  the raw `at`. (`-PT1H` → `at − 1h`.)
- **`cardToNowItem`** gains `nowIso` + `zone` params (both callers — `nowFeed` and
  `planExactSchedules` — already have them). Anchor resolution:
  - effective(t) = `applyOffset(t.whenTrigger.at, t.whenTrigger.alertOffset, zone)` for
    each `when` trigger;
  - `triggerAtIso` = the **soonest future** effective instant (> now), as ISO; **else**
    `card.notBefore` (current behavior for trigger-less cards). *(Soonest-**future** — not
    soonest-overall — fixes review F3: a past + future trigger must not anchor on the past
    one.)*
- **Banding (`NowRank`)** — unchanged; already bands off `triggerAtIso`.
- **Notify wake (`planExactSchedules`)** — unchanged; it already arms `triggerAtIso` and
  filters `at <= now` (a folded wake in the past is naturally **dropped**, not
  fire-immediately — resolves the past-wake concern) and `at − now > horizon`.

### 2. Geo (`geo`) — foreground surfacing (Option A)

- **Foreground lane** — new pure function in `NowFeed` (e.g. `authoredGeoItems(cards,
  places, location, nowIso, zone, config)`): for each **visible** card (passes the
  `feedCards` not_before/expires gate) with a `geo` trigger and a live `DeviceLocation`
  within radius, emit a NOW `NowItem` — reusing the existing `deriveNow` geo math:
  `haversineMeters` (already `internal`), `radiusM ?: default`, and `placeRef` resolution
  against `places` (same precedence as `NowDerive.kt:149-153`). The item gets a **distinct
  id** `"authored:geo:${card.id}:${index}"` (review F6 — must not collide with the card's
  time item `"authored:${card.id}"`) and the card's `subjectKey`, so `NowRank`'s
  same-subject dedup merges the geo-NOW item with the time item (geo-active outranks a
  far time band). Shared geo-resolve logic is factored so it isn't duplicated from
  `deriveNow` (review simplification #4).
- **Background** — **no code.** A `place_ref` authored trigger references a saved place
  already in `activePlaces()` → already geofenced + already woken by the background pass,
  which now surfaces the card via the foreground lane above (the background pass runs the
  same `nowFeed`). A coord-only authored geo trigger arms no geofence (ADR 0049 Option A).

### 3. Privacy (ADR 0014 / 0049)

On-device match, live location injected + never persisted. No new network, no coord
egress, no new permission (foreground geo uses the while-using permission the proximity
feature already requests). The privacy chip stays **author-declared** (`card.privacy`);
matching honesty rests on the architecture (position never leaves), not the chip — the
spec does not claim the chip is match-coupled (review F3-privacy). Coord-only authored
cards do **not** background-fire (ADR 0049 Option A) — a documented limitation, not a
false promise.

### 4. Edge cases

- Both `when` + `geo`: time anchor from `when` (soonest future); geo lane can surface it
  NOW when physically near — same `subjectKey` → dedup keeps the stronger.
- Malformed trigger (missing `at`/coords, bad offset): skipped / fail-open, never crashes
  the pure pass.
- Visibility still gates surfacing; the exact-schedule wake bypasses the not_before gate
  as it already does (must arm a future timed item before it becomes visible) — unchanged.

## Components touched

| File | Change |
|---|---|
| `client/Model.kt` | `Card.triggers: List<BlockTrigger>? = null` |
| `client/.../db/Content.sq` | `card` table + `insertCard`/`selectCards`: `triggers TEXT` |
| `client/.../db/migrations/11.sqm` | `ALTER TABLE card ADD COLUMN triggers TEXT;` |
| `client/ContentStore.kt` | `upsertCard` encode + `rowToCard` decode of `card.triggers`; `CLIENT_SCHEMA_VERSION` 2→3 |
| `client/NowDerive.kt` | `applyOffset` helper; extract a shared geo-resolve used by both lanes (no `NowItem` field change) |
| `client/NowFeed.kt` | `cardToNowItem`: `nowIso`/`zone` + folded when-anchor; new `authoredGeoItems` lane wired into `nowFeed` |
| tests | `NowFeedTest`, `DeriveNowTest`, `BackgroundNotifyTest`, `ContentStoreTest`, schema-version resync test |
| **not touched** | `MainActivity` geofence registration; no `cardGeoRegions`; no `NowItem.alertOffsetIso`; no `planExactSchedules` signature |

## Platform matrix

- **Android:** foreground surfacing + background notify (time exact-alarm; geo via saved-place geofence).
- **iOS:** foreground geo lane + time banding run over the shared `:client` core; background notify parity is per ADR 0044's iOS state (unchanged here).
- **Desktop/web:** foreground surfacing only (`DeviceLocation` null → geo lane emits nothing; no notifier). All degrade cleanly — the new code is pure commonMain; no Android type leaks.

## Testing strategy

- **Pure unit (`:client`)**: `applyOffset` (valid `-PT1H`, positive, malformed→fail-open);
  `cardToNowItem` anchor (when-trigger vs not_before; soonest-**future** of N; folded
  offset); banding of a when-triggered card; `planExactSchedules` wakes at at+offset and
  drops a past folded wake; `authoredGeoItems` (inside/outside radius, no location,
  place_ref vs inline coords, distinct id, dedup merge with time item); `ContentStore`
  decodes `card.triggers`; schema-version 2→3 forces resync.
- **On-device (Pixel)**: author a card with a near-future `when` (+offset) → bands NOW at
  the offset instant + fires a notification (notifications enabled); author a `place_ref`
  geo trigger to a saved place → region-enter surfaces it.
- Golden snapshots: none expected to change (no fixture carries authored triggers); if one
  does, re-record both OS sets.

## Rollout / ADR

Realizes ADR 0049 (Accepted, Option A) + the trigger semantics ADR 0043/0044 imply;
within ADR 0014 (on-device, no egress). Ships behind the existing background-notify
opt-in (`notifConfig.enabled`, default OFF) + while-using location for foreground geo.
Preconditions stated honestly (opt-in OFF by default, permissions, Doze, quiet-hours,
daily cap) — no delivery guarantee implied.
