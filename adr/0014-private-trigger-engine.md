# ADR 0014: Private On-Device Trigger Engine (geo / time / activity)

## Status

**Accepted** 2026-06-18 (operator-set forks, in-session). Immutable —
supersede, do not edit. Privacy/data-handling + product decision; composes
with the constitution ("privacy by architecture"), ADR 0006 (render-don't-
reason), ADR 0013 (client). Detail lands in `specs/event-hubs-design.md`,
`specs/prototype/03-api.md`, and component 07/08.

## Context

The operator wants the app to leverage device **location, time, and
activity** to highlight/suggest/notify about relevant content — *without the
user's location leaving the device*. Claude (the authoring loop) already
knows the places and times content refers to (the party venue, the school,
the event time) and can attach that as metadata.

## Decision

1. **Triggers are metadata on content; the device matches them locally.**
   Content (cards/blocks) carries `triggers`; the **client** holds the
   device's live location/time/activity and evaluates matches **on-device**.
   The **user's live position/activity NEVER leaves the device**. This
   extends "render, don't reason": **Claude reasons** (sets correct trigger
   metadata from full context), the **client matches** (mechanical).
   - *Privacy boundary (explicit):* the **places content references** (store
     coords, school) ARE authored content that goes to the server (family-
     scoped, encrypted at rest). Only the **device's live position** stays
     local. These are different things.
2. **Three trigger types:**
   - `geo` `{lat,lng,radius_m,label}` or `{place_ref}` → proximity.
   - `when` `{at | window | relative | recurring, alert_offset}` → **local
     scheduled notification** (private, offline; no server push needed).
   - `activity` `{kind: walking|running|biking|driving}` → **schema slot
     reserved, on-device recognition DEFERRED to post-MVP** (lower value,
     extra permission).
3. **Reusable family-scoped `places`** (home/school/store defined once,
   referenced by `place_ref`) — DRY, fewer raw coords, Claude reuses known
   locations.
4. **Progressive location permission:** **when-in-use first** (foreground
   highlights, no heavy prompt); **"Always" is an explicit opt-in upgrade**
   for *background* proximity notifications (iOS region monitoring needs
   Always + an App-Store justification). Time triggers need only the
   notification permission.
5. **Calm matching (constitution):** on-device geofences register the
   **nearest-N / soonest-N** within platform limits (iOS ~20 regions,
   Android ~100); notifications respect quiet hours, dedupe, and a daily cap.
   Few, timely, earn the interruption.
6. **Milestone split:** the **schema + CLI metadata + time-based highlights**
   can land early (cheap, no heavy permission); **background geofencing +
   "Always" location + activity recognition are a later milestone** (beyond
   the M0 dumb renderer).

## Rationale

It delivers the "smart, location-aware" feel while making **privacy a
structural property, not a policy** — a genuine differentiator and fully on
brand. Reuses the content pipeline (triggers ride in `sync`); the only new
server surface is the `places` table. Progressive permission keeps the
calm/trust posture and avoids the App-Store friction of up-front "Always."

**Rejected:** sending live device location to the server to match server-side
(violates the privacy promise, adds liability); up-front "Always" permission
(heavy, off-brand); building activity recognition now (low value per operator).

## Consequences

Positive: location-aware UX with zero live-location egress; privacy-by-
architecture story; reuses content/sync; progressive permission is trust-
forward.
Negative: on-device geofence limits force nearest/soonest-N selection logic;
"Always" upgrade still needs an App-Store justification when added;
background matching + notifications are real client complexity (later
milestone); place coords (home/school) are sensitive family content — must be
encrypted at rest + family-scoped + never logged.

## Revisit Trigger

Geofence-limit selection proves inadequate (need server-assisted ranking —
re-examine the privacy boundary carefully); activity triggers gain a real use
case; or a platform changes background-location rules.
