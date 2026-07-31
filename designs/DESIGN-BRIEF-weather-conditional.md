# Design Brief / Prompt — Weather-Conditional Content

**Hand this whole file to a fresh Claude Design session.** It is self-contained.
Authoritative sources: `../docs/superpowers/specs/2026-07-31-background-refresh-and-weather-design.md`
(Part B), `../adr/0043-now-content-model-derived-plus-authored.md`,
`../adr/0014-private-trigger-engine.md`, `../adr/0036-*` (media/enrichment),
`../adr/0009-design-system-m3-expressive-adaptive.md`, and the existing
`Design-System.dc.html`, `Now-Phone.dc.html`, and `now-derived/` mockups.

**Status:** this is the ADR 0008 design-first gate for a weather ADR that is **not
yet written**. Design sign-off here does **not** authorize build — the ADR still
has to clear an MVP-feature-boundary change, a vendor choice, and the client's
first external network egress.

---

## 0. How to run this

> **You are designing the hi-fi UI/UX for weather-conditional content** in
> family-ai-dashboard (*Dayfold*). Use the `frontend-design` skill. Produce
> **interactive HTML/CSS prototypes** faithfully emulating **Material 3
> Expressive** — reuse tokens, type, shape, motion, and components from
> `Design-System.dc.html`, and **extend `Now-Phone.dc.html` and the `now-derived/`
> feed; do NOT invent a new feed or a new system.** Mobile-first (~390–430px),
> **light + dark for every screen**. Map components 1:1 to M3 Compose names.
> Commit to `designs/weather/`. **Visuals only — no app code.**

## 1. What this feature is, in one paragraph

Dayfold is not becoming a weather app. Weather never produces a forecast
readout; it **qualifies content the family already cares about**. The curator
authors a card and attaches a condition ("rain, at the soccer field, 2–6 pm")
plus what to do about it. The device fetches a forecast for that *place* — never
for the user's live position — and decides whether the condition holds. If it
does, the card surfaces, ranked up, with a weather chip. If it doesn't, the card
does not exist for the user that day.

The test for every pixel here: **would a family glance at this and act, or would
they feel like they've been handed a weather report?** The second one is failure.

## 2. Brand & tone (inherit ADR 0009)

Material 3 Expressive, adaptive. Light is the hero, dark is first-class. Seed
colors: Coral `#FF5436` (primary), Teal `#11B5A4` (secondary), Violet (tertiary).
Type: **Outfit** display/headline/title, **Figtree** body/label, Material Symbols
Rounded. Calm behavior, vibrant visuals.

Weather is the most tempting surface in this product to over-decorate. Resist
it. No animated rain, no gradient skies, no hero weather art.

## 3. Screens to design

### 3.1 A weather-qualified card, in the feed (the core case)

An ordinary authored card that surfaced *because* its condition matched. Design
the **verified** state: card + weather chip + device-derived glyph.

Design it alongside the same card in a normal, non-weather state so the
difference is legible at a glance without being loud.

### 3.2 The unverified state — the honesty case

**Read this carefully; it is the constraint most likely to be designed wrong.**

When there is no forecast, a stale one, or the provider failed, the gate **fails
open**: the card still shows. But the card must not *claim* weather it never
verified — so the chip and glyph are **absent**, not greyed, not "—", not
"weather unavailable".

So the same card has two looks: with chip (verified) and without (unverified),
and **the without-chip version must look completely normal**, not broken or
degraded. A user must never see a placeholder implying the app knows something
it doesn't. Show both side by side and prove the second reads as an ordinary
card.

### 3.3 The weather glyph set

New iconography, **device-derived** — the author writes `rain`; the device knows
it's heavy rain at 4 pm and picks the glyph. Cover the closed vocabulary:
`rain · snow · wind · hot · cold · clear`.

The existing `media.icon` set (`school | luggage | medical | move | party | baby
| calendar | location | link | document | contact | budget | travel | car | food
| pet | sport | list`) has no weather glyphs, so this is a genuine addition —
design it as a coherent family with the existing set, not a bolt-on.

**Open question you must answer:** a card can already carry an authored
`media.icon` + `accentColor` (ADR 0036). Now it can also have a device-derived
weather glyph. **Which wins, and where does the loser go?** Show your resolution
— side-by-side, chip-plus-icon, or a rule that one suppresses the other. This is
a real gap in the spec; your answer becomes the spec.

### 3.4 The aggregate impact card (behind a flag, default off)

When two or more weather-matched items share an overlapping window and place,
one aggregate surfaces:

> **Rain 3–6 pm — affects soccer pickup and the party setup**

Its constituents **collapse under it** rather than being replaced — silently
removing content the operator approved is the wrong default.

**This is a new collapse shape and it is the hardest part of the brief.** The
existing feed already collapses *dedup peers* under a head via `collapsedWith` —
but those peers are the **same subject** seen two ways. An aggregate's children
are **different subjects** (a pickup, a party setup) grouped by a shared cause.
Reusing the dedup inset treatment unchanged may read as "these are duplicates,"
which is wrong. Decide whether the existing inset works, needs a variant, or
needs its own shape — and show why.

Also design:
- **Where it sits in the bands.** The feed ranks into now / soon / later with a
  calm visible budget and an overflow tail. Does an aggregate outrank its own
  children, and what happens when they'd have landed in different bands?
- **Expanded vs collapsed**, and the transition between them.
- **Recommendations.** "Move the setup indoors" is **authored in advance** by the
  curator and merely *selected* when the condition holds — the device cannot
  reason (on-device LLM assessed NO-GO 2026-07-13). Design where an authored
  recommendation sits relative to the impact line, and what the card looks like
  when the curator wrote a condition but no recommendation.
- **The forecast link** — a plain outbound `https` link. One affordance, not a
  hero button. If Apple WeatherKit is ever the provider, its mandatory
  attribution occupies exactly this slot, so leave room for a short attribution
  line without redesigning.

### 3.5 Provenance under mixed origins

**Another real gap — resolve it visually.** The aggregate is a *derived* item, so
by ADR 0043/0014 its honest chip is **"Matched on your device."** Its children are
*authored*, so theirs read **"Added by Claude"** / "Added by Codex". A single
collapsed group therefore contains **two different provenance claims**, and the
honesty posture forbids flattening them into one.

Design how a group carries mixed provenance without turning into a wall of
chips.

### 3.6 What a hidden card looks like

When a condition does **not** match, the card simply isn't there. Design the
**absence**: is it truly silent, or does the family get any affordance that
weather-conditional content exists at all?

Argue for one. Silence is calm and is the current spec assumption, but a family
that never sees the rainy-day plan may not know their curator wrote one. If you
propose an affordance, it must not become a second feed of things-not-shown.

## 4. Hard constraints — violating these fails the review

1. **Never render weather that wasn't verified.** No chip, no glyph, no
   temperature, no "unknown" placeholder on an unverified card.
2. **No forecast readouts.** No hourly strips, no 7-day rows, no current-conditions
   widget. If a screen would be useful with the family's content removed, it's a
   weather app and it's out.
3. **Weather glyphs are device-derived.** Never present them as something the
   author picked.
4. **Recommendations are authored, never generated.** Nothing in the design may
   imply the device composed advice.
5. **The aggregate never replaces its constituents** — it collapses them.
6. **Timing is ±1 hour at best** (hourly forecast resolution). Never render
   minute-precision weather times. "3–6 pm", not "3:12 pm".
7. **Light + dark for every screen.** Not an afterthought pass.

## 5. What is out of scope

- Notifications / lock screen (that's the `triggers/` brief's territory).
- Any settings UI for choosing a weather provider — the vendor is an ADR
  decision, not a user-facing choice.
- The background-refresh mechanics (shipped; ADR 0020 R3).
- Weather on the hub timeline or hub detail — **feed only** for this pass. Note
  it if you see an obvious opportunity, but don't design it.

## 6. Deliverables

- `designs/weather/Index.dc.html` — landing, links every surface.
- Screens from §3, **light + dark**, at ~390–430px.
- A short **rationale note** for each of the three open questions you resolved:
  icon precedence (§3.3), aggregate collapse shape (§3.4), mixed provenance
  (§3.5), plus your recommendation on hidden-card affordance (§3.6). These feed
  straight back into the spec, so state them as decisions with reasons, not as
  options.
- A line in `designs/README.md`'s table, matching the existing rows.

## 7. How this gets judged

The operator signs off, or doesn't. The bar: **a family with a rained-out soccer
game opens Dayfold and immediately knows what changed and what to do** — without
reading a forecast, without hunting, and without ever being shown weather the app
didn't actually confirm.
