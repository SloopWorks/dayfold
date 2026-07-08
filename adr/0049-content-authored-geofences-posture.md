# ADR 0049: Content-Authored Geofences — Posture for Authored Trigger Proximity

## Status

**Proposed** 2026-07-08 (agent-drafted at the issue #299 build gate;
**operator-gated — HARD GUARDRAIL tier**: it decides whether *server-authored
content* [Claude/CLI/API-pushed cards and hub blocks] may arm **background
geofences**, which touches guardrails **#3** [restricted-scope / location-data
posture + customer disclosure] and **#4** [customer-relationship line — no silent
behavior]). **Concretizes ADR 0044 §2 and ADR 0014 §4/§5** for the specific case
of proximity triggers that originate in *authored content* rather than *user-saved
places*. Does **not** edit 0044/0014 (both Accepted/immutable) — it resolves a
sub-question they did not answer.

Statuses: Proposed | Accepted | Superseded | Deprecated.

## Context

Issue #299: an authored `BriefingCard`'s `triggers[]` (`when`, `geo`) are not
consumed by the client — they are dropped at decode. The fix consumes them so an
authored card can surface + notify on time and location. The **time** half is not
posture-sensitive (it arms local exact alarms, already an accepted mechanism). The
**location** half is:

- ADR 0044 §2 accepted background geofences as **"nearest-N places/triggers…
  on-device, capped."** Its worked model throughout is the family's **user-curated
  saved places** — regions the user explicitly created and can see. §1 frames the
  "Always" opt-in as "background proximity for *your saved places*."
- A `geo` trigger on an **authored card** (or hub block) is different in kind: the
  region is chosen by **authored content** (Claude / the CLI / the content API),
  not by the family. Arming an OS background geofence from author-supplied
  coordinates means the app watches for the user entering a place the **author**
  picked and the user never saved.

Two facts bound the risk:

- **No location egress (ADR 0014 holds).** Matching is on-device; the device's live
  position never leaves it. Authored coordinates are already synced content (they
  are cached `on_device`). So this is **not** a data-exfiltration change.
- **It IS a consent + disclosure change.** A user who opted into "background
  proximity for my saved places" did not necessarily opt into "the app geofences
  arbitrary locations my briefing content names." Google Play / App-Store
  background-location declarations must match the *actual, disclosed* feature; ADR
  0044 explicitly **reserved** the public-ship background-location disclosure
  review. Silently widening the geofence source from *user-curated* to
  *content-authored* is the kind of scope move guardrail #3/#4 put off-limits to
  agent decision.

Adjacent asymmetry to resolve in the same stroke: hub **block** `geo` triggers are
already decoded and **foreground**-matched (`deriveNow`), but are **not** registered
as background geofences today. So "authored geo → background geofence" is a new
posture for both cards and blocks, not just cards.

## Decision (proposed — for operator ratification)

Pick a posture for **authored-content geo triggers** (cards and hub blocks). Two
coherent options; the ADR recommends **Option A**.

### Option A (recommended) — Foreground for authored coords; background geofences stay user-curated

1. **Foreground proximity surfacing: allowed for any authored geo trigger.** When
   the app is open and live location is available (while-using permission, which the
   proximity feature already requests), an authored card/block `geo` trigger that the
   user is within radius of surfaces as a NOW item, matched **on-device** (ADR 0014).
   This adds **no new permission and no OS geofence** — it is the same on-device
   match `deriveNow` already does for blocks, extended to cards. Not posture-gated.

2. **Background geofences: only from user-curated places.** The OS background
   geofence set stays exactly what ADR 0044 accepted — `activePlaces()` (the family's
   saved places). An authored trigger participates in **background** proximity **only
   by `place_ref`** — i.e. by *referencing a place the family already saved*. An
   authored trigger with **inline coordinates and no `place_ref`** gets foreground
   surfacing but arms **no** background geofence.

   Rationale: this keeps every OS background geofence a region the **user curated and
   can see** — ADR 0044's posture is unchanged, the disclosure it reserved does not
   widen, and authored content still gains proximity relevance (foreground always;
   background when it points at a saved place). It also happens to be the smaller
   implementation (reuses the existing place→geofence pipeline; no new geofence
   source, no new per-family region cap, no content-driven re-registration churn).

### Option B — Content-authored background geofences, with guardrails

Allow authored geo triggers (inline coords included) to arm background geofences,
under **new** guardrails the operator must also ratify:
- a **per-family cap** on authored geo regions, well under the platform limit and
  **below** user-place priority (saved places are registered first; authored regions
  never starve them and never exceed the cap);
- **diff/debounced** registration (never blind re-register on every content sync);
- an **honesty affordance** telling the user these geofences come from briefing
  content (not places they saved), meeting the INB-13 §6b honesty bar;
- a **disclosure-copy update** + the public-ship data-safety declaration explicitly
  covering content-authored proximity.
This is strictly more capability (background geofencing works for coord-only authored
cards) at the cost of a wider disclosure surface and more moving parts.

### Common to both options (not gated)

- **Time triggers (`when` + `alert_offset`)** for authored cards are adopted now:
  banding + local exact-alarm notify, no location, no new permission. (This is the
  non-gated half of #299 and proceeds regardless of A/B.)
- **On-device matching / no egress** (ADR 0014) is invariant under both.
- **Opt-in, reversible, never-synced** permission + config posture (ADR 0044 §1/§3)
  is invariant under both.
- **Honesty about delivery**: any notify (time or geo) is best-effort under the
  existing opt-in-OFF-by-default `NotifConfig`, quiet-hours, daily cap, Doze, and
  (for background geo) the **"Always"** permission — the feature never implies a
  guarantee.

## Operator-gated questions this ADR records (do not agent-decide)

1. **Choose Option A or Option B** (the core posture: are content-authored
   *background* geofences allowed, or does background proximity stay user-curated?).
2. If **Option B**: ratify the per-family authored-region cap value, the honesty
   affordance requirement, and the disclosure-copy / data-safety update as ship
   blockers.
3. **Confirm the foreground authored-geo surfacing** (Option A §1 — on-device match,
   while-using permission, no OS geofence) is acceptable as a plain, ungated code
   change.
4. **Pricing/spend:** none — no vendor, no recurring cost (local notifications + OS
   geofencing are free), same as ADR 0044. Recorded so the spend guardrail is clear.

## Rationale

- **Keeps the strongest privacy line by default.** Option A means every background
  geofence remains a user-curated, user-visible region — the exact thing ADR 0044's
  disclosure was written against — so the accepted posture and its copy do not move.
- **Authored content still earns proximity relevance** (foreground always; background
  via `place_ref`), which covers the realistic dogfood cases (a card that points at a
  saved place) without a new consent surface.
- **On-device matching is invariant** — neither option leaks position; this is a
  consent/disclosure decision, not a data-flow one.
- **Smaller + calmer** (Option A): no new geofence source, no content-driven region
  churn, no new per-family cap to tune — aligns with ADR 0014 §5 "calm matching."

**Rejected:** silently unioning content-authored coord regions into the background
geofence set with no posture decision (the status quo the #299 design first proposed)
— it widens the disclosure surface below the operator's radar, exactly what guardrail
#3/#4 forbid.

## Consequences

- **Option A:** issue #299 ships time (now) + foreground authored-geo surfacing +
  background-via-`place_ref`; no disclosure change; smallest diff. Coord-only authored
  geo cards do not fire in the background (documented limitation) until/unless Option
  B is later ratified.
- **Option B:** full content-authored background geofencing, plus the disclosure /
  honesty / cap work as ship blockers and ongoing battery/churn care.

## Revisit Trigger

Reconsider if: a real dogfood need appears for coord-only authored geo cards to fire
in the background (→ revisit Option B); a platform changes background-location rules;
or the public-ship disclosure review (still reserved by ADR 0044) reopens the
geofence-source question.

---

Concretizes ADR 0044 (background-location & notification posture) and ADR 0014
(location privacy) for authored-content proximity. Issue: SloopWorks/dayfold#299.
Design: `docs/superpowers/specs/2026-07-08-authored-card-triggers-design.md`.
