# Authored BriefingCard triggers — client consumption (issue #299)

**Date:** 2026-07-08
**Status:** Design (pending review)
**Issue:** SloopWorks/dayfold#299
**Related:** ADR 0043 (priority & ordering), ADR 0044 (background notify), ADR 0014 (location privacy)

## Problem

An authored `BriefingCard`'s `triggers[]` (`when.at` + `alert_offset`, `geo`) are
stored and decoded but **never consumed** by the client. The authored lane
(`NowFeed.cardToNowItem`) bands + schedules off `not_before` / `expires_at` /
`importance`; `deriveNow` evaluates triggers on **hub blocks only**; geofences are
registered only from `Place` rows; `alert_offset` is read nowhere. So a card
authored with a time and/or location trigger neither surfaces nor notifies on those
triggers.

## Goal

Make an authored card's `when` and `geo` triggers drive Now-feed surfacing/banding
and background notifications — **without** changing what `not_before`/`expires_at`
mean (they remain the visibility window).

Non-goals: changing the hub-block derived lane; server/API changes; new content
schema (triggers already exist in the model); iOS geofencing (Android is the notify
target today — iOS proximity is a later platform actual, called out below).

## Core principle

- `not_before` / `expires_at` = **visibility window** (unchanged).
- `triggers[]` = **relevance signal** (banding + notify wake + geofence), decoupled
  from visibility. A card can be visible now yet band/notify off its own trigger,
  and vice-versa.

## Design

### 1. Time (`when`) — banding + notify

**`NowItem`** gains one optional field: `alertOffsetIso: String? = null` (an
ISO-8601 duration like `-PT1H`). Default null → derived items and trigger-less
authored cards are byte-identical to today.

**`cardToNowItem` (`NowFeed.kt`)** — resolve the time anchor:
- If the card has one or more `when` triggers → `triggerAtIso = the soonest
  when.at` (across all `when` triggers), and `alertOffsetIso = that trigger's
  alert_offset`.
- Else → `triggerAtIso = card.notBefore` (current behavior).

`weight` (importance) unchanged.

**Banding (`NowRank`)** — no change: it already bands off `triggerAtIso`. So a
`when`-triggered card bands NOW/SOON/LATER by its **event instant** (`when.at`),
exactly like a derived item.

**Notify wake (`BackgroundNotify.planExactSchedules`)** — apply the offset:
the wake instant for an item is `triggerAtIso + alertOffsetIso` when an offset is
present, else `triggerAtIso`. Example: `at=10:00`, `alert_offset=-PT1H` → wake
`09:00`. The horizon/dedup/soonest-per-subject logic is unchanged; only the instant
each item resolves to is offset. At fire time the receiver re-runs the full pass, so
cap/quiet/dedup still apply.

**Offset parsing** — a small pure helper `applyOffset(atIso, offsetIso, zone):
Instant?` parses the RFC-3339 instant and the ISO-8601 duration and adds them.
Malformed offset → treat as no offset (fail-open to `at`), never crash.

### 2. Location (`geo`) — surfacing + geofence

**Surfacing (foreground, pure).** Add an authored-card geo lane to the pure feed
build: for each visible card with a `geo` trigger and a live `DeviceLocation`, if
within the trigger radius, emit a NOW `NowItem` (`reasonKind = geo`, `geoActive =
true`, `distanceM` set) — mirroring `deriveNow`'s block-geo branch (haversine,
`radiusM ?: default`). Injected live location never persists (ADR 0014). This lives
alongside `cardToNowItem` in the authored lane (`NowFeed`), so the existing
cross-lane dedup (`subjectKey`) merges it with the card's time/authored item.

**Geofence (background).** The Android geofence set is today `activePlaces()`. Add a
second source: authored-card geo triggers as regions.
- New `ContentStore` reader `cardGeoRegions(): List<GeoRegion>` — reads the cached
  cards, extracts each `geo` trigger's `{lat,lng,radiusM,label}`, keyed by a stable
  region id (`card:<cardId>:geo:<index>`).
- `MainActivity` unions `activePlaces()`-derived regions with `cardGeoRegions()` in
  the geofence registration (both the `notifConfigFlow` arm and the
  `nowContentFlow` re-register arm), still capped by `ANDROID_REGION_CAP` (places
  first, then card regions, so a place is never starved by cards).
- `BackgroundNotify` region-enter already re-runs the pass; the pass's authored geo
  lane (above) then surfaces the matching card, and `selectNotifications` posts it.
  No change to the notify selection itself.

**Privacy (ADR 0014).** Card `geo` coords are authored `on_device`; matching is
on-device (`matched_on_device` chip stays honest). No network, no coord egress, no
new permission beyond the existing while-using location the proximity feature
already requests.

### 3. Precedence & edge cases

- A card with **both** `when` and `geo`: time anchor from `when` (soonest), plus the
  geo lane can surface it as NOW when physically near — both map to the same
  `subjectKey` (`card:<id>` or its hub target), so dedup keeps the stronger (geo-now
  outranks a far-future time band via existing rank).
- **Visibility still gates**: a card hidden by `not_before`/`expires_at` (`feedCards`
  gate) does not surface even if a trigger matches — triggers drive *relevance*, not
  *visibility*. (Exact-schedule wake is the one deliberate exception that already
  bypasses the not_before gate, per the existing `planExactSchedules` doc — a future
  timed item must be armed before it becomes visible; unchanged.)
- **Multiple `when` triggers**: soonest future one wins (matches
  `planExactSchedules`' soonest-per-subject).
- **Malformed trigger** (missing at/lat/lng, bad offset): skipped, fail-open, never
  crashes the pure pass.

## Components touched

| File | Change |
|---|---|
| `client/NowDerive.kt` (`NowItem`) | add `alertOffsetIso: String? = null` |
| `client/NowFeed.kt` | `cardToNowItem`: when-trigger anchor + offset; new authored geo-proximity lane in the feed build |
| `client/BackgroundNotify.kt` | `planExactSchedules`: apply `alertOffsetIso` to the wake instant; new `applyOffset` helper |
| `client/ContentStore.kt` | new `cardGeoRegions()` reader |
| `androidApp/MainActivity.kt` | union `cardGeoRegions()` into geofence registration (both arms), cap-aware |
| tests | `NowFeedTest`/`DeriveNowTest`/`BackgroundNotifyTest`/`ContentStoreTest` (+ snapshot if a fixture changes) |

## Testing strategy

- **Pure unit (`:client`)**: time anchor selection (when vs not_before, soonest-of-N);
  `applyOffset` (valid/negative/malformed); geo lane (inside/outside radius, no
  location, missing coords); banding of a when-triggered card; exact-schedule wake =
  at+offset; `cardGeoRegions` extraction + cap ordering.
- **On-device (Pixel)**: author a card with a near-future `when` (+offset) → verify it
  bands NOW at the offset instant and fires a notification (notifications enabled);
  author a `geo` trigger → verify geofence registration (logcat) and region-enter
  surfacing. "Don't keep activities" not relevant here.
- No golden snapshot expected to change (no fixture carries authored triggers); if one
  does, re-record both OS sets.

## Rollout / ADR

No ADR change required — this realizes the trigger semantics ADR 0043/0044 already
imply and stays within ADR 0014 (on-device matching, no egress). A one-line note may
be added to ADR 0043's surfacing description. Ships behind the existing background-
notify opt-in (`notifConfig.enabled`); foreground geo surfacing needs live-location
permission the proximity feature already gates.
