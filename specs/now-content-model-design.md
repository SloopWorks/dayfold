# Now Content Model — Strategy & Design (DRAFT)

**Status:** DRAFT — proposed. ADR-class (changes the Hub→Now boundary + the
dumb-client posture); requires a Proposed ADR + operator sign-off, and a
hi-fi mockup per ADR 0008 before any build.
**Date:** 2026-06-29
**Resolves on acceptance:** OQ-now-emission, OQ-notbefore-gating.
**Composes:** ADR 0006 (event hubs), 0014 (private trigger engine), 0015
(E2EE), 0020 (read-from-cache), 0030 (visibility), 0038-0040 (two-way),
`scope-and-access-model.md` §7.

---

## 0. The question

Should "Now" content be a first-class, server-authored, **writeable** entity
(today's `briefing_cards`), or should Now be **only client-side** — a derived
view over Hub content (customized views / hub content + "why showing" context)?

**Answer: neither pole. Split Now by the _provenance of its surfacing reason_.**
Most of Now's *structural* surfacing (countdowns, milestones, due checklist
items, "you're near X", "leave by 3:15") is derivable on-device from hub
metadata the client has already synced — that should become a **client-side
derived view with no server persistence and no write path**. But ~30-40% of
Now is *irreducible*: weather, a loose email RSVP, a bespoke reasoned nudge —
facts that come from outside synced family content and cannot be computed on
the device. Those must stay **authored through the API**. So the API write
path for Now **shrinks but does not disappear**.

Seam principle: **author the irreducible; derive the structural.**

---

## 1. Why the pure options fail (option scoring)

Four options were designed and adversarially self-critiqued.

| Option | One line | Fatal flaw |
|---|---|---|
| **A — keep first-class authored cards (status quo)** | Now stays a writeable `briefing_cards` table of full content | **Drift.** A card minted from a hub item freezes a copy of the hub's title/body/time; edit the hub and the briefing silently contradicts it until the skill re-authors. The drift is worst exactly when sync is infrequent — the freshness gap we set out to close. |
| **B — pure on-device derived view (delete `briefing_cards` + write API)** | Now is a function of (hub cache, clock, location, ruleset) | **Kills the irreducible 30-40%.** Hubless/external/bespoke nudges have no block to derive from. B's escape hatch (a device-local "SystemHub") just smuggles authoring back onto the client and can't carry the skill's reasoned, server-authored intelligence. |
| **C — reference+context overlay (thin authored ref → hub block + why)** | A Now item points at a hub block and adds only the "why" | **Two flaws:** (1) re-introduces audience inheritance that **ADR 0030 §3 explicitly rejected**; (2) still needs a fallback card lane for hubless items — so it converges to a two-lane model anyway, and if most items fall to the fallback the "no duplication" win shrinks to a refactor of the minority. |
| **D — hybrid by seam (derive hub-backed; author hubless)** | Two lanes split on "does a hub exist?" | **Seam cut wrong.** "Rain at the soccer hub" is hub-backed *and* externally-sourced — D's own example breaks its rule. Splitting on *hub existence* forces a per-nudge adjudication at the edges. |

**Convergence:** B, C, and D all independently arrive at *two lanes*. The
inventory confirms why they must: the hubless share is large and real, and
even hub-backed cards routinely add external affordances (the Instacart link
on "party Saturday — ordered groceries?") that the hub block does not hold.

**The fix to D's broken seam:** split on the **provenance of the surfacing
reason**, not on whether a hub exists. "Rain at soccer" is *authored* (its
fact is external weather) even though a soccer hub exists — and it may still
**deep-link** to that hub. This dissolves the edge case D couldn't answer.

This design = **D with C's deref-as-reference, cut on the corrected seam, and
A's proven typed renderers preserved.** It is evolutionary, not destructive.

---

## 2. The model: two lanes, one feed

### Lane 1 — Derived (client-side, no persistence, no write path)

On-device the client synthesizes Now items from content it has **already
synced**: `hubs.start_at / countdown_to / end_at`, block `payload`
(milestone date, checklist `due`, location `lat/lng`), and block `triggers[]`
(geo / when, per ADR 0014), evaluated against the device clock and location.

- **Pure function:** `deriveNow(hubs, blocks, clock, location, ruleset) → List<NowItem>`.
  Recomputed on cache change, clock tick, and significant-location-change.
- **Built-in rules:** countdown-window, milestone-approaching,
  checklist-item-due, geo-proximity (nearest-N), time-window (`when`).
- **Output is ephemeral** — lives in the render projection, never in a table,
  never synced. Always fresh; works fully offline.
- **Deep-link is free** — the item *starts* from the hub/section/block node.
- **No drift possible** — there is no second copy of the hub's content.

### Lane 2 — Authored (slim, writeable API)

For items whose reason/fact is **not computable from synced family content**:
weather, a loose email, a bespoke reasoned nudge, or a hub-linked-but-
externally-sourced alert. These keep an authored row and the existing typed
renderers (`file/link/invite/contact/geo/email`, ADR 0022) — which are
*already* mostly external-content types and map cleanly onto this lane.

Slimmer than today's `briefing_cards` (drop nothing forcibly at first; the
schema *may* shed `target_*` duplication over time, but an authored item is
explicitly allowed to carry an optional deep-link `target` so "rain at
soccer" can still point into the soccer hub):

```
authored_now (evolution of briefing_cards):
  id, family_id,
  kind, title, body_md, type, payload,          -- keep typed renderers
  why / reason,                                  -- first-class (was implicit)
  trigger (jsonb, ADR 0014, nullable),
  target {hubId, sectionId?, blockId?} NULL,     -- optional deep-link, NOT a copy source
  not_before, expires_at,
  source  text,   -- weather | email | bespoke | manual
  visibility, audience[],                         -- ADR 0030 stamp (unchanged)
  media, version, updated_at, deleted_at
```

### The merge

One ordered Now feed = `deriveNow(...) ∪ activeAuthored`, sorted by a shared
urgency key (soonest trigger / countdown first, then `not_before`, then id —
mirroring today's `activeCards` ordering). Both honor the existing `hidden`
table. Every item carries `origin ∈ {derived, authored}` and `reason_kind ∈
{countdown, geo, when, milestone, checklist, external, bespoke}` driving one
provenance/"why" chip vocabulary. The UI renders the two origins identically.

---

## 2b. Priority & ordering engine (the arbiter — consider before build)

Both lanes are only **candidate generators**. What actually surfaces, in what
order, and how many is decided by **one on-device priority engine** — and it is
on-device by necessity: geo-proximity ranking needs live location (privacy),
time-to-event urgency needs the live clock (freshness), and quiet-hours / decay /
dismissed-state are device-local. **The server never ranks Now.**

**Pipeline:** candidate generation (derived rules + active authored) → score →
dedup/collapse → calm budget → stable ordering → render.

- **Score** = f(urgency [steepens approaching a trigger/countdown, decays after],
  proximity [boost inside a geo radius — ADR 0014 nearest-N], importance [author
  weight signal, or rule weight], reason-kind weight, anti-nag decay
  [shown-but-not-acted softens]).
- **Calm budget:** soonest-N / nearest-N caps, now/soon/later grouping,
  quiet-hours suppression, daily notification cap (Phase B). The tail collapses
  into a "more" affordance — never silently dropped.
- **Determinism:** clock + location injected (mirrors today's
  `feedCards(state, nowIso)`); stable tie-break + hysteresis prevent jitter;
  pure → snapshot/property-testable; fits redux.

**Three ways this changes the two-lane design (decisions, not asides):**

1. **Authored items are rank-able _signals_, not fixed-position content.** The
   author supplies a **bounded** `importance` hint + trigger window; the device
   decides position. Schema gains `importance`, drops any author-controlled
   ordinal. The weight is capped — an author cannot pin spam to the top (the
   calm guarantee constrains the scoring function).
2. **Dedup needs a shared subject key across lanes.** A derived "party in 2 days"
   and an authored "ordered groceries?" about the same party must collapse →
   both expose a `subjectRef`: the authored item's deep-link `target` (which
   thus earns a second job as dedup key — another reason to keep `target`), and
   the derived item's source node.
3. **The engine is the merge, and a first-class on-device component co-equal
   with the deriver.** It needs small **local-only, never-synced** surfacing
   state (last-shown / dismissed / quiet-hours) — syncing it would be a
   who-saw-what leak (cf. ADR 0039 hide-state). The **same** engine drives
   Phase-A in-feed ordering and Phase-B notifications (top-K under the daily
   cap), so Phase A's engine directly enables Phase B.

## 3. "Why showing"

- **Derived:** computed on-device from the matched condition + hub title, so
  it is always consistent with the live clock/position — "Party in 2 days",
  "You're near Safeway", "Pickup at 3:00". A frozen card body ("in 2 days")
  goes stale; a computed reason never does.
- **Authored:** the `why`/`title` the skill wrote, because no structure could
  compute it — "Rain expected at soccer, 4pm", "RSVP closes today".

The on-device trigger engine may also *augment* an authored item with a live
computed kicker ("you're nearby") layered on top.

---

## 4. Visibility (no ADR 0030 reversal)

- **Derived items inherit for free — and this is NOT the inheritance ADR 0030
  rejected.** A member's cache only contains blocks they may read (sync
  already applied hub `visibility` + `resource_visibility`, ADR 0030). You
  cannot derive an item from a block you don't have. No new visibility surface,
  no materialization, no fan-out — the very tangle ADR 0030 §3 killed is
  avoided, not re-introduced.
- **Authored items stamp** their own `visibility` + `audience[]`, exactly as
  cards do today. A hubless nudge carries its own audience (impossible to
  inherit — there is no parent).

---

## 5. Server stays content-blind (E2EE intact)

- Derived lane: server stores **nothing** for Now — strictly less than today.
- Authored lane: server stores the same opaque `payload` + cleartext routing
  columns it does now; `why` is ciphertext under E2EE (ADR 0015). No reasoning,
  no parse — ADR 0039 §7 holds unchanged.

Net: the Now surface **shrinks** the server's plaintext/ciphertext footprint
and tightens content-blindness.

---

## 6. What members can do

Unchanged: Now items are **display-only** OS handoffs (open, share, call,
navigate, reply, copy). Member writes remain a **hub-block** capability
(toggle/delete/hide, ADR 0038) — derived Now items that surface a checklist
deep-link into the block, where the existing two-way write path already lives.
No new member-write surface on Now.

---

## 7. Migration & in-flight work

- **In-flight two-way slices (1-6) are on _blocks_, not cards** — untouched.
  Cards are display-only today, so there is no member-write data to migrate.
- **Build, don't break:** the derived lane is **additive** — ship it alongside
  today's authored cards. Then move the *structural* surfacing (countdown /
  milestone / due / geo / when) off authoring onto derivation, and let the
  authored lane keep the irreducible remainder.
- **Reuse:** the 6 typed renderers + detail screens stay (they serve the
  authored lane); block renderers stay (they serve the derived lane). The new
  build is the **on-device deriver** + wiring `block.triggers` and `places`
  through `/sync` (both are schema/DB-present but client-absent today) +
  resolving the `not_before` client gate.

---

## 8. Phasing (composes with the on-device-surfacing proposal)

- **Phase A — derived in-feed surfacing (foreground only).** Decode
  `triggers`/`places` on the client; build `deriveNow`; render derived items
  in the feed with computed "why". **No background location, no notifications,
  no scary permission.** Subsumes OQ-notbefore-gating (the deriver gates
  `not_before` naturally). Highest value / lowest risk — ship first.
- **Phase B — notifications.** Background geofence + local notifications (no
  FCM/APNs needed — local only) firing the same derived items when the app is
  closed. Gated on the "Always" location permission + disclosure review
  (guardrail #3-4). Operator-gated.

---

## 9. Open questions resolved

- **OQ-now-emission:** Hub→Now structural surfacing becomes **on-device
  derived** (a third answer the OQ never listed — and the only privacy-correct
  one for geo). Irreducible/external items stay **manual skill-authored**.
  Server-cron is rejected (can't do geo without leaking location).
- **OQ-notbefore-gating:** resolved by the deriver's on-device time gate;
  authored lane also gains a proper `not_before <= now` feed gate.

---

## 10. Risks / honest residue

- **Two code paths, one surface.** Derived + authored must stay pixel-identical
  in ordering and chrome. Mitigation: one `NowItem` projection type + one
  merge/sort; origin only drives the "why" chip.
- **Seam adjudication at the margin.** The provenance-of-reason cut is crisp
  ("is the why computable from synced family content?") but the skill must
  follow it consistently. Document the decision rule in the curator skill.
- **Deriver complexity on-device.** Ranking, dedup, and the rule set are real
  client logic — but it is pure, testable (fits the redux/pure-fn pattern),
  and the geo/time matching is the ADR 0014 work that is needed regardless.
- **Dangling deep-links** from authored items into deleted/unsynced blocks —
  reuse the existing graceful nearest-ancestor fallback (already shipped).

---

## 11. Recommendation

Adopt the two-lane model. Concretely:

1. **Keep the API write path for Now, but scope it to the authored lane**
   (external / hubless / bespoke). Do **not** delete `briefing_cards`; evolve
   it into `authored_now`.
2. **Add a client-side derived lane** as a pure on-device view over hub
   metadata + triggers — Phase A first.
3. **Migrate structural surfacing** (countdown / milestone / due / geo / when)
   from skill-authored cards to derivation, eliminating drift for the items
   that drift today.
4. Gate behind a **Proposed ADR** + a **hi-fi mockup** of a derived Now item
   and the merged feed (ADR 0008) before build.

This answers the operator's question precisely: Now content should **not** be
a single first-class authored entity, and should **not** be removed from the
API either — the structural majority becomes a privacy-correct client-side
view, while an irreducible authored minority keeps a (slimmer) write path.
