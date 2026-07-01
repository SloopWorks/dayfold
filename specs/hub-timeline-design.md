# Hub Timeline — Authored Hub Property with On-Device Presentation

**Status:** Design (Proposed ADR 0045). Authored 2026-06-30 from the imported
hi-fi mock (`designs/hub-timeline/`) + a 6-agent review (3 correctness/gaps/xplat,
3 completeness/UX-M3/data-systems). Operator decisions captured inline.

## 1. Problem

Hubs hold a flat, unordered list of blocks. Dated content (a checklist due date,
a milestone, a move-in day) has no **axis of time** — a months-long project and a
single frantic day read the same. The imported mock (`designs/hub-timeline/`)
proposes a **timeline**: one model rendered at two scales — a **day** rail
(intraday, vertical, live NOW line) and a **hub roadmap** (weeks–months,
horizontal month spine) — living as a calm card in the dossier that taps open to
a full view.

The mock framed this as "visuals + motion only — no app code/schema/ADR changes."
The review found that **false**: rendering a timeline requires ordering,
grouping, per-stop status, windowing, NOW-line placement, and a time-resolution
rule that the existing `deriveNow` engine does not implement. This is a new
surface and is ADR-class.

## 2. Decision (operator-ratified, 2026-06-30)

**The timeline is an authored, content-blind hub property; the client computes
all time-relative presentation on-device as a pure function.** Phased:

- **Source model: authored-first (C, A-first).** A new authored hub property
  (`Hub.timeline`) is the source of truth, shipped first. A client-side *derived
  fallback* (`deriveTimeline` over existing dated blocks for hubs without an
  authored timeline) is **deferred to Phase 2** and is out of scope here.
- **Division of labor:** the **author** (Claude skill / CLI / content API) writes
  the irreducible **stops** (the content); the **client** computes everything
  time-relative locally — status, NOW line, day-vs-roadmap scale, grouping,
  windowing, collapse — so the **server stays content-blind** (stores + validates
  structure only, never reads stop text).
- **Placement: a hub-level property, not a block.** One-per-hub is enforced for
  free by the hub primary key; it renders as a **hoisted card** in the dossier;
  old clients ignore an unknown hub property cleanly (no unknown-block-type
  hazard).
- **Phase 1 is render-only and Now-invisible.** A timeline stop does **not** feed
  the Now derived engine and fires **no** notification in Phase 1. Provenance copy
  reflects **authored** origin (no "derived on-device" claim until the Phase-2
  derive path ships — honesty guardrail).
- **Timezone: author-stamped.** The timeline carries its own IANA `tz` string
  (content-blind, travels with the stops); the client falls back family-tz →
  device-tz. This protects the multi-member wedge: members in different zones see
  the same NOW line / done-set for the same authored timeline.
- **Platforms:** Android + iOS + Desktop. Web is out (no `wasmJs` target exists).

Seam principle (inherited from ADR 0043): **author the irreducible; derive the
structural.** The stops are irreducible authored content; status/NOW/scale/
grouping are structural functions of stops + clock + tz.

## 3. Data model

A new optional hub-level property. **Not** a new block `type` (the block-type
enum stays frozen-closed per ADR 0035).

```jsonc
// Hub gains an optional property, synced with the hub row (sibling to `places`)
Hub.timeline? = {
  "title": "string?",            // optional display title ("Roadmap" / "Move-in day")
  "tz": "America/New_York",      // IANA, author-stamped, REQUIRED when timeline present
  "stops": [Stop]                // REQUIRED, non-empty
}

Stop = {
  "at": "2026-08-24T11:00:00-04:00", // RFC-3339; a date-only "2026-08-24" = all-day
  "title": "string",                  // REQUIRED
  "sub": "string?",                   // one-line detail
  "major": false,                     // author prominence hint → T4 expanded card
  "done": false,                      // author hint; client may promote by time, never un-done
  "assignee": "string?",              // display-only label (never an identity binding)
  "attachments": [Attachment]         // optional
}

Attachment =
  | { "kind": "call", "label": "string", "tel":   "string" }   // → tel:  via existing seam
  | { "kind": "nav",  "label": "string", "query": "string" }   // → geo:0,0?q=…
  | { "kind": "link", "label": "string", "url":   "https://…" }// → https:
  | { "kind": "open", "label": "string",
      "ref": { "hubId": "string", "sectionId": "string?", "blockId": "string?" } }
      // typed in-app jump dispatched as CardAction.OpenHub — NOT a URI
```

Notes:
- **`at` reuses the schema's RFC-3339 convention** (every existing temporal field
  is RFC-3339; `parseInstantFlexible` already accepts both a full instant and a
  date-only string). No `date`+`HH:mm` split.
- **No `df://`.** In-app targets are the typed `open` kind resolved through the
  store (`CardAction.OpenHub`), never a URI scheme. OS-handoff targets
  (`call`/`nav`/`link`) reuse `cardActionUri` + `vettedOpenUri`
  (`{https,mailto,tel,geo,sms}`). The `file` kind from the mock is dropped — a
  file is an `https` `link`.
- **`done`/`major` are author hints.** The client recomputes `done` monotonically
  (`author.done || at < now` — may promote, never un-done); `major` only raises
  presentation prominence within this hub's detail (it never enters a ranked feed,
  so it does not reopen the ADR-0043 "no author-pinned spam" concern).
- **`assignee` is a display string**, author-supplied; never bound to an account
  (COPPA: subjects only, ADR 0004 §4).

## 4. Client presentation pipeline

A pure function `TimelinePresenter` in `commonMain`, mirroring `NowDerive.kt`
(ephemeral, never synced, snapshot/property-testable). **Inputs:** authored
`Hub.timeline` + injected clock + injected tz (family → device fallback). **It
reads only structure + dates, never interprets stop prose.**

1. **Status per stop.** `done` if `author.done || at < now` (monotonic); `now` =
   the stop whose window contains now (day) / the current-month band (roadmap);
   `next` = first future stop; else `upcoming`. `major` hint → T4 expanded.
2. **Scale auto-selection — one card per hub.** Render **roadmap** if stops span
   > 14 days or there are ≥ 3 date-only stops; render **day** if there are
   intraday-timed stops on the focal day. The hub shows **one** card; the second
   scale is reachable only via the in-detail toggle (ephemeral, resets on open).
   The card carries a small "also a roadmap / also a day" affordance so the
   second scale is discoverable. Focal day = the date with the most intraday
   stops, else today.
3. **NOW line.** Shown only when the focal day == today (day) / the current month
   is in range (roadmap), evaluated in the timeline's `tz`. One canonical marker
   (coral dot + halo + NOW-time pill + fading trail), identical on card + detail.
   **A single settle-pulse on arrival, then static** (not an infinite pulse — the
   brand is calm); halo color is the per-mode `primaryHalo`.
4. **Grouping** — part-of-day (day) / month (roadmap). **Windowing (card)** —
   "N done" collapsed cap → NOW → next ~3 future → "M more" tail. **Roadmap
   collapse** — ≤ 6 spine nodes; a leading done-run > 2 collapses into one `✓N`
   node, keeping the current month + what's ahead legible.

The presenter is deterministic given (stops, clock, tz). Realistic stop counts
are tens; sort/group cost is trivial.

## 5. States (designed, not hand-waved)

| State | Behavior |
|---|---|
| Empty (no `Hub.timeline`) | No card rendered. |
| Single-scale | Render the one scale the content supports; toggle still offered if the other scale has any stops, else hidden. |
| All-done (event passed) | Roadmap fully filled / day all-checked; **no NOW line**; apply the brand empty-ish voice rather than a broken-looking collapsed card. |
| Not-today | Every stop `upcoming`, no NOW line. |
| Archived hub | NOW line suppressed (matches countdown suppression on archived hubs). |
| Loading | Rides hub-tree load; the presenter is instant once the tree is present. |
| Dangling `open` ref | Chip renders; tap is a graceful no-op (target validated at dispatch). |

These states must be added to the mock before ADR-0008 sign-off (the imported
mock only draws the rich both-scales-today case).

## 6. Navigation, transform, chips

- **Nav:** new `Route.TimelineDetail` + `OpenTimelineDetail(hubId)` action +
  reducer clause + `BackNav.kt` back-action + `FeedApp.kt` route branch (pattern
  precedent: `Route.Proximity`). The card→detail morph is a **new call site** for
  the existing shared-element infra (verify, not assume).
- **Transform:** reuse `cardSharedBounds(timelineCardId)` + `ContentHost`
  (`FeedApp.kt`) — the same container-transform the mock cites as the "Now
  deep-link arrival motion". Reduced-motion → the existing `ContentHost` crossfade
  branch.
- **Scope toggle:** `SingleChoiceSegmentedButtonRow` (already in `commonMain`),
  ephemeral state.
- **Chips:** **≥ 48dp** touch targets (`minimumInteractiveComponentSize`). OS
  handoff (`call`/`nav`/`link`) dispatches `CardAction` via `cardActionUri` +
  `LocalUriHandler`; in-app (`open`) dispatches `CardAction.OpenHub`.

## 7. Member-mutation & visibility

- The timeline is **author-authored**; it is **not a worklist** — stops are not
  member-checkable (unlike checklist `done`, which is member-mutable + synced via
  ADR 0038). This is a deliberate scope cut, documented so it is a decision not a
  surprise.
- For consistency with every other dossier element, the timeline card supports
  **"Hide for me"** (local-only, reversible — the ADR 0039 §W5 wrapper).
- Per-member stop visibility is out of scope; the whole timeline inherits the
  hub's ADR-0030 visibility.

## 8. Material 3 & accessibility

- **Status palette:** done = secondary (teal), next = primary (coral), major =
  tertiary (purple), upcoming = hollow/outline. Container/on- pairings per M3.
- **Token fidelity:** use the real `Color.kt` tokens (`outline #8C726B`,
  `outlineVariant #EBD3CB`) and render peer cards on `surfaceContainerHigh` to
  match sibling dossier blocks. (The mock drifted to lighter values — sync before
  sign-off.)
- **NOW-language coherence:** the Now tab renders "now" as a quiet band (no coral,
  no pulse). The in-timeline NOW marker is louder by design (a "you-are-here on
  this axis" marker); tone it toward the calm Now-tab treatment where possible and
  **document the deliberate divergence**.
- **A11y:** per-stop semantics announce title · time · status
  (done/now/next/upcoming) · attachment count; NOW exposes
  `liveRegion = Polite` ("current time, N stops remaining"); the horizontal
  roadmap is linearized (`clearAndSetSemantics`) to match the vertical detail
  reading order; ≥ 48dp targets; reduced-motion falls back to static + crossfade.
- **Truncation:** title/sub/assignee get `maxLines` + `TextOverflow.Ellipsis`; the
  time column gets a min-width floor.
- **Icons:** ~14 missing Material-Symbols glyphs added to `DayfoldIcons.kt`
  (mechanical `ImageVector` ports).

## 9. Honest change-set

| Layer | Change |
|---|---|
| `specs/domain-model/schemas/content.schema.json` | Add the `Hub.timeline` property + `Stop` + `Attachment` definitions. **Not** the block-type enum. |
| `packages/schema` codegen | Regenerate TS types; quicktype Kotlin (CLI-consumed). |
| API (`apps/api`) | zod `HubSchema` gains `timeline?`; **structural validation** (`tz` present, `stops` non-empty, each stop has `at`, attachment `kind` ∈ enum). Store verbatim (content-blind). |
| CLI (`apps/cli` `Validate.kt`) | Hub-level timeline validation (mirror the server). |
| Hub sync | `timeline` travels with the hub row (like `places`). |
| Client (`apps/client` `Model.kt`, hand-written) | `Hub.timeline` + `Stop` + `Attachment` types. |
| Client | `TimelinePresenter` (commonMain), timeline card + detail composables, `Route.TimelineDetail` + action + reducer + `BackNav` + `FeedApp` branch, ~14 `DayfoldIcons` glyphs. |
| Hub detail | Hoisted timeline card render path above sections (does not disturb `focusedBlockItemIndex` block-in-section math). |

Old clients ignore an unknown hub property (`ignoreUnknownKeys`) cleanly — no
unknown-block-type placeholder needed (which a block path would have required as
an ADR-0039 prerequisite).

## 10. Governance

- **Proposed ADR 0045** records this as ADR-class (a new authored content
  representation over hub content + a new on-device presentation projection;
  composes 0043/0035/0006/0030/0038/0039/0014/0015/0044).
- **Imported-mock corrections** (carried here; mock to be revised before
  ADR-0008 sign-off): (a) provenance copy → authored origin, no "derived
  on-device" until Phase 2; (b) one-card IA (kill "show both"); (c) add empty /
  all-done / not-today state mocks; (d) citation fixes — the write model is **ADR
  0038/0039**, the author/visibility posture **ADR 0030** (not 0008, which is the
  design-first gate); (e) soften "never a notification" → *the timeline has no
  notification channel of its own; a stop notifies only if it independently
  surfaces as a Now item under ADR 0044* (Phase 2).
- **ADR 0044 untouched.** Phase-1 timeline emits no notifications.
- **COPPA clear** — subjects only, no child account holders (ADR 0004 §4).

## 11. Testing

TDD throughout.

- **`TimelinePresenter`** — exhaustive `commonTest`: today / not-today /
  all-done / sparse / single-scale / > 6 nodes / done-run collapse / ties /
  cross-tz (a member in another zone sees the same result for an author-stamped
  tz). Property tests on monotonic `done` + deterministic order.
- **Validators** — server + CLI: `tz` required, `stops` non-empty, each stop has
  `at`, attachment `kind` enum, one-per-hub (hub-field cardinality).
- **Renderer** — `rk snapshot` scenes: day / roadmap × light / dark ×
  rich / empty / all-done / not-today.
- **Nav / transform** — existing harness patterns for `Route` + shared-bounds.

## 12. Open items (carry to plan)

1. **Mock revision pass** — honest copy, one-card IA, missing-state mocks (P0
   before ADR-0008 sign-off).
2. **Scale-selection thresholds** (> 14 days / ≥ 3 date-only) — confirm against
   real authored examples during implementation; presenter-internal, cheap to
   tune.
3. **NOW-marker calm tuning** — exact treatment vs the Now-tab band (UX detail,
   resolve in the mock revision).
4. **Family-tz delivery** — Phase-1 falls back device-tz when family-tz is
   unbuilt; revisit when M1 `family_settings.timezone` lands.
5. **Phase-1 scope cuts (operator-approved 2026-06-30, from the plan review).**
   Deferred to Phase 2: the in-detail day↔hub **scope toggle** + the second-scale
   affordance (§4.2 / §5) — Phase 1 renders the one auto-selected scale per hub;
   the roadmap **`✓N` collapse** (§4) — Phase 1 renders ≤6 nodes, else a "+M more"
   tail; and per-member **"Hide for me"** on the card (§7) — Phase-1 timeline
   ships without it.
6. **Nav-model refinement.** §6 named `Route.TimelineDetail`; the implementation
   uses an `AppState.timelineDetail: TimelineScale?` substate inside a Hubs-surface
   `SharedTransitionLayout` instead — this matches the existing `ContentHost`
   card→detail morph precedent (a substate, not a Route switch) and is the
   morph-compatible choice. A deliberate refinement, not a decision reversal.

## 13. Review audit trail

Six review agents (read-only), 2026-06-30:
- **Correctness** — "no app code/schema/ADR" claim false; ADR-0008 miscitation;
  "never a notification" contradicts ADR 0044; COPPA clear.
- **Gaps** — no `deriveTimeline`; status/attachment/scale derivation absent;
  card-action handoff exists at the card layer (reuse).
- **Cross-platform** — Android/iOS/Desktop buildable (transform/toggle/
  reduce-motion/fonts already ship); **Web target does not exist**.
- **Completeness/integration** — `df://` is not reuse; block-type enum
  frozen-closed; authored stops invisible to `deriveNow`; author-only collides
  with "Hide for me".
- **UI/UX + M3** — provenance copy dishonest for Phase 1; two-card IA ambiguous;
  states unmocked; chips < 44dp; NOW-language clash; token drift.
- **Data/systems** — hub timezone is phantom; one-per-hub unenforceable as a
  block → **hub-field**; `date`+`HH:mm` breaks RFC-3339 convention; validators
  don't check `stops`; unknown-block forward-compat gap.

All BLOCKER/MAJOR findings are resolved in §2–§10 above.
