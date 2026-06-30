# ADR 0043: Now Content Model — Derived + Authored Two-Lane Surfacing with an On-Device Priority Engine

## Status

**Accepted** 2026-06-30 (operator ratified in-session — proceed with Phase A
build). Originally **Proposed** 2026-06-29 (agent-drafted from a 5-design-agent
panel + requirements inventory; **operator-gated** — changes the Hub→Now
boundary, softens the dumb-client posture, and is product-scope-shaped). The
ADR 0008 hi-fi mockup gate (`designs/now-derived/`) was **signed off by the
operator 2026-06-30**, clearing build of the Phase A surface.

Statuses: Proposed | Accepted | Superseded | Deprecated.

## Context

"Now" (the briefing feed) is currently a first-class, server-authored, writeable
entity: the `briefing_cards` table, full self-contained content, authored by the
Claude skill via the CLI (`PUT /families/:fid/cards/:id`). Hub→Now emission is
**manual** — when a hub item becomes imminent the skill pulls hubs and authors a
card that deep-links back; "no server cron, no client synthesis at MVP"
(operator decision 2026-06-23, `OQ-now-emission`).

Two forces now press on that model:

1. **On-device contextual surfacing is the right direction** (established this
   session). Time-to-event and geo-proximity surfacing *must* be client-side —
   for **privacy** (live position never leaves the device, ADR 0014), for
   **freshness** (sync is infrequent per ADR 0040, so a server/author-frozen
   "imminent" card drifts), and because imminence/proximity are **dynamic**
   device-context functions that cannot be precomputed at author time.

2. **Content duplication causes drift.** A card minted from a hub item freezes a
   copy of the hub's title/time/body; editing the hub silently contradicts the
   briefing one tap away until the skill re-authors — worst exactly when sync is
   slow.

A requirements inventory established the decisive constraint: **~30-40% of Now
items are hubless** (weather, a loose email RSVP, a bespoke reasoned nudge like
"Grandma's flight is delayed — maybe call her"), and even hub-backed cards
routinely add affordances the hub block lacks (the Instacart link on "party
Saturday — ordered groceries?"). So Now can be neither a pure render of authored
cards (drifts; stale) nor a pure client-side view over hubs (cannot express the
irreducible hubless minority).

Five designs were produced and adversarially self-critiqued (status-quo authored;
pure on-device view; reference+context overlay; hybrid-by-hub-existence; and the
synthesis here). The pure poles failed; the overlay re-opened the audience
inheritance ADR 0030 §3 rejected; the hub-existence seam mis-cut the "rain at the
soccer hub" case (hub-backed *and* externally-sourced). Full analysis:
`specs/now-content-model-design.md`.

## Decision

**Split Now into two lanes by the _provenance of its surfacing reason_, and rank
both lanes through one on-device priority engine.**

Seam principle: **author the irreducible; derive the structural.** The cut is
"is the reason this item surfaces computable on-device from already-synced family
content?" — *not* "does a hub exist?".

### Lane 1 — Derived (client-side, no server persistence, no write path)
The client synthesizes Now items on-device from content it has already synced —
`hubs.start_at/countdown_to/end_at`, block `payload` (milestone date, checklist
`due`, location `lat/lng`), and block `triggers[]` (geo/when, ADR 0014) —
evaluated against the live device clock and location. Output is ephemeral (a
render projection, never a table, never synced). Always fresh, fully offline,
deep-links for free, **structurally drift-proof** (no second copy).

### Lane 2 — Authored (slim, writeable API)
For items whose reason/fact is **not** computable from synced family content —
weather, loose email, bespoke nudge, or a hub-linked-but-externally-sourced
alert. Evolves `briefing_cards` (does **not** delete it); keeps the ADR 0022
typed renderers (`file/link/invite/contact/geo/email`), which are themselves
mostly external-content types. Carries an **optional** deep-link `target` (so
"rain at soccer" can point into the soccer hub) — a reference, never a content
copy — plus a `why`, a `trigger`, and an **author importance/weight signal**.

### The arbiter — one on-device Priority & Ordering Engine (spans both lanes)
A single pure function ranks the merged candidate set
`derive(...) ∪ activeAuthored` and decides what surfaces, in what order, and how
many — on-device by necessity (geo + live clock + local quiet-hours/decay state
cannot go server-side). The server **never ranks Now.** Both lanes are merely
candidate generators feeding one ranker. See **Priority & Ordering Engine** below.

### Governance markers
- **Server stays content-blind** (ADR 0039 §7) — the derived lane persists
  nothing for Now; the authored lane stores the same opaque payload + ciphertext
  `why` it does today. The Now surface *shrinks* the server's data footprint.
- **Visibility:** derived items inherit **for free** (a member's cache only holds
  blocks they may read — you cannot derive from a block you didn't sync); this is
  *not* the materialization/fan-out inheritance ADR 0030 §3 rejected. Authored
  items stamp `visibility`+`audience[]` as today.
- **Members:** Now stays display-only; member writes remain a hub-block
  capability (ADR 0038/0039).

## Priority & Ordering Engine (design-load-bearing)

The engine is what makes Now *calm* rather than a firehose, and considering it
**before** implementation changes the two-lane design in three concrete ways.
It runs entirely on-device as a pure function of
`(candidates, clock, location, local surfacing-state, config)`.

**Pipeline:** candidate generation (derived rules + active authored) → score →
dedup/collapse → calm budget → stable ordering → render.

- **Score inputs:** *urgency* (steepening as a trigger/countdown approaches,
  decaying after), *proximity* (boost inside a geo radius, ADR 0014 nearest-N),
  *importance* (the author's weight signal, or a rule weight for derived),
  *reason-kind* weight, and *anti-nag decay* (an item shown-but-not-acted softens
  over time).
- **Calm budget:** soonest-N / nearest-N caps, "now / soon / later" grouping,
  quiet-hours suppression of non-urgent items, and (Phase B) a daily
  notification cap. Candidates may exceed what is shown; the tail collapses, it
  is never silently dropped.
- **Determinism:** clock + location are injected (mirroring today's
  `feedCards(state, nowIso)`); stable tie-break and hysteresis prevent feed
  jitter on every tick. Pure → snapshot/property-testable; fits the redux model.

**Three design impacts (these are decisions, not asides):**

1. **Authored items are rank-able _signals_, not fixed-position content.** The
   author supplies an `importance/weight` hint + trigger window; the device
   decides final position. The authored schema therefore gains a bounded
   `importance` field and drops any notion of author-controlled ordinal. The
   weight is **capped** so an author cannot pin spam to the top — the
   constitution's calm guarantee constrains the scoring function.

2. **Dedup requires a shared subject key across lanes.** A derived "party in 2
   days" and an authored "ordered groceries?" about the same party must collapse.
   Both lanes must expose a `subjectRef` — for authored items this is the
   deep-link `target` (which thus earns a second job as the dedup key,
   reinforcing keeping `target` on the authored row); for derived items it is the
   source hub/section/block node.

3. **The engine, not a concat-sort, is the merge — and it is co-equal with the
   deriver as a first-class on-device component.** It needs small **local-only,
   never-synced** surfacing-state (last-shown, dismissed, quiet-hours config);
   syncing it would be a who-saw-what behavioral leak (same reasoning as the
   ADR 0039 hide-state leak). The **same** engine drives Phase-A in-feed ordering
   and Phase-B notification selection (notifications = the top-K of the same
   ranking under the daily cap), so building it in Phase A directly enables
   Phase B.

## Rationale

- **Answers the operator's question precisely.** Now should *not* be a single
  first-class authored entity (drift, staleness), and should *not* be removed
  from the API either (the irreducible hubless minority needs an author the
  device can't compute). The structural majority becomes a privacy-correct
  client-side view; a slimmer authored lane keeps the remainder.
- **Provenance-of-reason is the only seam that doesn't mis-cut.** It resolves the
  "rain at the soccer hub" case the hub-existence seam could not: external fact ⇒
  authored, even with a hub present (and it may still deep-link).
- **Evolutionary, not destructive.** Keeps the built typed renderers + block
  renderers; the only new build is the on-device deriver + the priority engine +
  wiring `triggers`/`places` through `/sync` (schema/DB-present, client-absent
  today). In-flight two-way slices are on *blocks*, not cards — untouched.
- **Alternatives rejected:** (A) status-quo authored — drift/staleness; (B) pure
  on-device — cannot express hubless/bespoke; (C) reference overlay — re-opens
  ADR 0030 §3 inheritance + still needs a fallback lane; (D) hybrid on
  hub-existence — seam mis-cut.

## Consequences

Positive:
- Drift eliminated for the structural items that drift today.
- On-device freshness + privacy for time/geo; live position never leaves device.
- Server content-blindness *tightens* (derived lane persists nothing).
- One on-device engine serves both in-feed ordering and notifications.
- Resolves `OQ-now-emission` (third answer: on-device derivation) and
  `OQ-notbefore-gating` (the deriver/engine gates `not_before`).

Negative / costs:
- **Two code paths, one surface** — derived + authored must stay pixel-identical
  in chrome and ordering. Mitigation: one `NowItem` projection + one engine;
  origin only drives the "why" chip.
- **Seam adjudication at the margin** — the skill must apply the
  provenance-of-reason rule consistently; document it in the curator skill.
- **On-device engine complexity** — ranking/dedup/budget/decay are real client
  logic, but pure and testable, and the geo/time matching is the ADR 0014 work
  needed regardless.
- New **local-only surfacing-state** to manage (small; never synced).

## Phasing

- **Phase A** — derived in-feed surfacing + the priority engine, **foreground
  only**: decode `triggers`/`places` on the client, build `deriveNow` + the
  engine, render the merged ranked feed with computed "why". No background
  location, no notifications, no new permission. Subsumes `OQ-notbefore-gating`.
  Highest value / lowest risk — ship first.
- **Phase B** — notifications: background geofence + **local** notifications (no
  FCM/APNs) firing the engine's top-K when the app is closed. Gated on the
  "Always" location permission + disclosure review (guardrail #3-4).
  Operator-gated.

## Revisit Trigger

Reconsider if: dogfooding shows the derived lane produces noise the calm budget
can't tame (→ engine retune or a server-assist deriver); the hubless share turns
out far smaller than estimated (→ the overlay model C becomes attractive); or
E2EE (ADR 0015) lands and the authored `why` ciphertext interacts badly with
on-device ranking.

---

Composes 0006 / 0014 / 0015 / 0020 / 0022 / 0030 / 0038 / 0039 / 0040 /
`scope-and-access-model.md` §7. Design: `specs/now-content-model-design.md`.
