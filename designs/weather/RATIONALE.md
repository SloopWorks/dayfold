# Weather-conditional content — design rationale

Design pass for `designs/DESIGN-BRIEF-weather-conditional.md` (ADR 0008
design-first gate). Extends `now-derived/` (signed off 2026-06-30) and the
Design System; invents no new feed and no new system.

**Sign-off here does not authorize build.** The weather ADR is still unwritten
and must clear an MVP-feature-boundary change, a vendor choice, and the
client's first external network egress (spec B.10).

| File | Covers |
|---|---|
| `Index.dc.html` | Landing, the four decisions, constraint checklist, Compose map |
| `Qualified.dc.html` | §3.1 verified, §3.2 unverified, rejected placeholders |
| `Glyphs.dc.html` | §3.3 the six glyphs + **icon precedence** |
| `Aggregate.dc.html` | §3.4 **collapse shape**, bands, recommendations, forecast link |
| `Provenance.dc.html` | §3.5 **mixed provenance**, §3.6 **hidden-card recommendation** |
| `Weather-Phone.dc.html` | The parameterized phone component (`mode`, `scenario`, `attribution`) the galleries mount |

---

## Decision 1 — Icon precedence (§3.3)

**An authored `media.icon` and a device-derived weather glyph never share a
slot, so neither wins.**

The question presupposes a collision. There isn't one once you locate where
`media.icon` already lives: per ADR 0036 and the schema (`BriefingCard.media`),
it renders on the **kind chip** and an optional **leading thumbnail** — the
slots that say *what this card is*. The weather glyph belongs to the **why
chip** — the slot that says *why it is here now*. Every card in the derived feed
already carries both kinds of caption side by side. The rule is therefore about
ownership of slots, not priority between glyphs:

> The device never writes into an authored slot, and no author ever writes a
> weather glyph.

Four consequences, each drawn in `Glyphs.dc.html`:

1. **A missing authored icon does not promote the weather glyph.** A card with
   no `media.icon` simply has no tile. Promoting the glyph would make weather
   the *subject* of the card, which is the failure mode the whole brief is
   written against.
2. **`accentColor` never tints the weather chip.** An author-supplied hex on a
   device-computed claim would let the curator style the device's voice. Weather
   stays on `surfaceContainerHigh` / `onSurfaceVariant` in both themes.
3. **Reading order is kind chip, then why chip** — subject then reason, the same
   left-to-right grammar the derived feed already uses.
4. **One why chip maximum.** If a card carries a weather match *and* another
   derived reason, weather takes the slot: it is why the card is visible at all,
   where the other is only why it is ranked.

**Why not the privacy (teal) family for the weather chip.** It was tempting —
the match genuinely happens on-device, and the teal family already reads
"matched on your device." It would be dishonest. That family's promise is that
nothing left the device, and a forecast fetch is the client's *first external
egress*: a place's rounded coordinates go to a vendor. So the weather chip sits
in the neutral **derived** family, alongside "Pickup at 3:00", and the network
disclosure is made explicitly in the why-you-see-this sheet.

**Family coherence.** The existing 18 authored icons are nouns — school,
luggage, medical — and render **filled** on an accent tile, because they stand
for something the family owns. The six weather glyphs are conditions and render
**unfilled**, at chip scale, on neutral roles. Same typeface, same weight, same
optical size; opposite fill. One axis, and it also encodes who chose the glyph.

Vocabulary and the device-chosen intensity (author writes `rain`; device picks
the glyph and the wording):

| Condition | Glyphs | Chip |
|---|---|---|
| `rain` | `rainy_light` / `rainy` / `rainy_heavy` | "Rain 3–6 pm · Riverside" |
| `snow` | `weather_snowy` / `snowing_heavy` | "Snow 7–10 am" |
| `wind` | `air` / `storm` | "Wind 2–5 pm" |
| `hot` | `thermostat` | "Hot 12–4 pm" |
| `cold` | `ac_unit` | "Cold 6–9 am" |
| `clear` | `sunny` | "Clear 3–6 pm" |

Every window renders at hourly resolution — "3–6 pm", never "3:12 pm"
(constraint 6). Escalation changes the glyph, never the colour: weather is never
rendered in the error role.

---

## Decision 2 — The aggregate collapse shape (§3.4)

**Recessed means "the same thing". The aggregate is raised. It is not a card
with children — it is a well with a header, and each constituent keeps its own
card surface inside it.**

The existing dedup collapse nests its child *inside* the head card as a
recessed, translucent inset with no card shape of its own. That is correct there
and only there: the child is not another thing, it is another view of one thing,
so it has no independent existence to express and no separate provenance to
carry. Give an aggregate that same inset and it reads "these two are duplicates
of the rain" — wrong, because they are a soccer pickup and a party setup, and
they will still be two separate things tomorrow.

| | Dedup (existing) | Cause group (new) |
|---|---|---|
| Means | one subject, two reasons | different subjects, one cause |
| Container | a Card | a **well** — lower surface tier, not a card |
| Child | recessed inset, no card shape | **raised** card, one tier above the well |
| Child count | exactly one nested nudge | two or more peers, listed |
| Header claims | the subject | the cause |
| Provenance | one — the child is absorbed | two lanes (see Decision 3) |
| Tapping a child | deep-links into the same event | opens a different hub than its sibling |

"Raised" is carried by surface tier plus a level-1 shadow, which reads correctly
in both themes (in light the child is a *darker* container tier; in dark it is
*lighter* — M3's own tier logic in both directions). The group also states its
relationship in words — a `WHAT IT CHANGES` label row between header and
children. A dedup group would never say that sentence, so the structural device
carries real information rather than decoration.

**Bands and the calm budget.** The spec is silent; these are the decisions:

- The group sits in **min(band)** of its constituents. A `now` item is never
  demoted into a `later` group.
- Constituents travel with the group and do not also appear in their original
  band. One rain, one place.
- **The group counts as one item against the calm budget.** Turning the
  aggregate on must never make the feed longer than leaving it off, or the
  feature is a regression against ADR 0043's calm guarantee.
- Over budget, the whole group folds into the overflow tail as a **single** row
  — "Rain 3–6 pm — 2 things" — never two.
- Below two constituents there is no group. A lone matched card is just a
  matched card.

**Where an authored recommendation sits.** Inside the constituent, under its own
title, above its own "Added by Claude" chip. **Never in the header.** The header
is derived; putting "move the setup indoors" beneath a device-computed line
would imply the device reasoned its way there, which constraint 4 forbids and
which the on-device-LLM NO-GO (2026-07-13) makes false. When the curator wrote a
condition but no recommendation, the card is simply itself with the match named
— no empty slot, no generic hedge, no "check the forecast".

**Collapsed ↔ expanded.** The well is the shared container across both states,
so the transition is a resize, not a replacement: `animateContentSize` on the
well with the emphasized curve, `AnimatedVisibility` per child for row → card,
chevron rotating 180°. Reduced motion drops the height spring and keeps the
cross-fade. Group state is per-session; a group never re-collapses under a
reader.

**Forecast link.** One `TextButton`-weight affordance on the header's honesty
row, right-aligned, at the same rank as "Open hub" — never a filled button. The
attribution slot is the **last element in the header**, so rendering it grows
the group downward and reflows nothing above it. Drawn both with and without.

---

## Decision 3 — Mixed provenance (§3.5)

**Provenance attaches to the claim, never to the container. The wall of chips is
avoided by weight, not by omission.**

The group header makes exactly one claim — *this condition matched* — and
carries exactly one chip for it ("Matched on your device"). It never speaks for
its children. Each child makes its own claim and carries its own chip. There is
no group-level provenance, because a group is not a source.

The collapsed state is where a naive design produces the wall. The resolution:
while a child is a row, its origin renders as **the chip's glyph alone** — the
same `auto_awesome` mark, in the same violet provenance role, without the pill
and the words. On expand, the mark **grows into** the full chip rather than
cross-fading out and a chip in, so the claim is continuous rather than
re-asserted.

- Collapsed group: one pill (the header's) + two marks.
- Expanded group: three pills, but each attached to its own visible card, so no
  surface carries a claim that is not about itself.
- Invariant: **never more than one pill per visible surface.** The wall is
  impossible by construction rather than by editing.

Tapping any chip or mark opens the existing honesty `ModalBottomSheet`, extended
with a weather section that spells out three separate things: the device made
the match; a forecast was fetched for the *field's* rounded coordinates (not the
user's position); and Claude wrote both plans in advance. That third row is what
keeps constraint 4 true in words as well as in layout, and the second is the
disclosure the first external egress requires.

---

## Decision 4 — The hidden card (§3.6)

**Recommendation: silence in Now, on every day. No row, no count, no "1 plan is
waiting on the weather".** The family's worry is real; the feed is the wrong
place to answer it, and there is a better one.

1. **A not-shown list is a second feed.** The calm budget exists to bound how
   much a family has to read. An inverse feed unbounds it again, and it grows
   with every condition the curator ever wrote.
2. **The affordance is itself a weather claim.** "Your rainy-day plan is
   waiting" tells the family it is not raining. That is a forecast readout with
   extra steps — constraint 2, via the side door.
3. **It contradicts the fail-open gate.** On an unverified day the conditional
   card *is* showing. A "hidden" list would have to either lie about it or
   expose the gate's internal state. Both are worse than saying nothing.
4. **Absence is the feature working.** A day where nothing changed should look
   like a day where nothing changed.

The genuine need is answered in three places that are not the feed:

- **The hub** — hubs are the browse surface, Now is the triage surface. A
  weather-conditional card should stay visible in its hub with a quiet condition
  line ("shows if it rains at Riverside"). §5 puts hub surfaces out of scope for
  this pass; **filing this as the obvious follow-on brief** is the note the
  brief asked for.
- **The debug drawer** — "did my condition fire?" is the curator's question, not
  the family's. The SWIP inspector (ADR 0057) is where an evaluated-gates panel
  belongs: debug-only, never shipped to a family.
- **The curator skill** — the real fix is upstream. A weather-sensitive plan is
  authored in *both* branches, so one of them always shows. A family that never
  sees a rainy-day plan is a plan that was written with only one branch.

---

## What I'd push back on, and what the brief left underspecified

1. **The gate can surface unverified weather prose, and no constraint stops
   it.** Constraint 1 governs what the *app* renders. It says nothing about the
   curator's own copy, which renders regardless because the gate fails open. If
   a card's body says "showers start right at kickoff" and no forecast was ever
   confirmed, the app has displayed an unverified weather claim — attributed to
   Claude, but displayed. **The design's honesty guarantee is only as strong as
   the authoring rule behind it.** Every card in these mockups is written
   condition-neutral (true on a dry day), so the chip only ever *adds* evidence
   and removing it leaves an ordinary card rather than a hole. That has to
   become an explicit rule in the curator skill and the `dayfold template`
   starter (spec B.10 item 3), not a convention. **I'd treat this as a blocking
   gate on B2, not a nicety.**

2. **The weather glyph names must be excluded from the server-validated
   `media.icon` enum.** ADR 0036 validates `icon` against a curated set. If
   `rainy` is added to that set for any reason, a curator can author a weather
   glyph as a card's identity icon — which is precisely constraint 3 ("never
   present them as something the author picked") and would eventually contradict
   live conditions. **The two sets must be disjoint and the server should
   reject weather names in `media.icon`.** Worth stating in the ADR.

3. **Attribution is not aggregate-only.** §3.4 places the attribution slot on
   the aggregate. If the vendor is ever Apple WeatherKit, its terms require
   attribution wherever weather data is displayed — and a qualified card
   displaying a weather chip is displaying weather data. So the slot is designed
   on the qualified card too (`Weather-Phone`, `attribution` prop). The
   consequence is worth weighing in the vendor decision: WeatherKit means an
   attribution line on **every** qualified card, which is a real calm cost that
   Open-Meteo and NWS do not impose.

4. **The existing `Now-Phone.dc.html` app-bar subtitle violates constraint 2.**
   It reads "3 things to glance at · 68°F, rain later" — a standing
   current-conditions readout, and unverifiable copy on every other day. It is
   retired in this pass (subtitle is now "3 things to glance at"). Flagging it
   so the build does not inherit it from the older prototype.

5. **Grouping is designed for the feed, but scheduling isn't grouped.** Spec B.5
   computes a `triggerAtIso` per weather-conditioned item. Two constituents of
   one cause would therefore schedule two exact notifications for one rain, and
   the family gets pinged twice for something the feed deliberately shows once.
   Notifications are the `triggers/` brief's territory (§5), so this is not
   designed here — but **the aggregate's grouping predicate should be shared
   with `planExactSchedules`, or the group is a feed-only fiction.** Worth an
   explicit line in the ADR.

6. **Brief path drift (minor).** The brief cites `designs/Design-System.dc.html`
   and `designs/Now-Phone.dc.html`; both actually live under
   `designs/Family AI dashboard design brief/designs/`. Tokens were taken from
   the real files.

7. **Not verified on screen.** Bindings and scenario data are linted
   programmatically (every `{{ }}` reference resolves in every scenario × theme),
   but the `.dc.html` viewer was not opened — that is the operator's step, per
   the operator-driven verification pattern.
