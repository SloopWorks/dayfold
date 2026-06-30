# Design Brief / Prompt — Now: Derived Surfacing + Priority/Ordering

**Hand this whole file to a fresh Claude Design session.** It is self-contained.
Authoritative source: `../adr/0043-now-content-model-derived-plus-authored.md`,
`../specs/now-content-model-design.md`, `../adr/0014-private-trigger-engine.md`,
`../adr/0009-design-system-m3-expressive-adaptive.md`, and the existing
`Design-System`, `Now`, and `triggers/` mockups.

---

## 0. How to run this

> **You are designing the hi-fi UI/UX for the new two-lane "Now" feed** of
> family-ai-dashboard (*Dayfold*). Use the `frontend-design` skill. Produce
> **interactive HTML/CSS prototypes** that faithfully emulate **Material 3
> Expressive** — reuse the tokens/type/shape/motion/components from the existing
> `Design-System.dc.html`; **extend `Now-Phone.dc.html`, do NOT invent a new
> system or a new feed**. Mobile-first (~390–430px), **light + dark** for every
> screen. Map components 1:1 to M3 Compose names. Commit to `designs/now-derived/`.
> **Visuals only — no app code.** This is the ADR 0008 gate for ADR 0043 Phase A.

## 1. Context (what this feature is)

Now is being split into **two lanes that render as ONE calm feed**:

- **Derived items** — computed *on-device* from hub metadata the app already
  has (a countdown, a milestone, a due checklist item, "you're near *Place*").
  Their "why" is generated live from the device clock/location, so it is always
  current. Nothing about them is authored or stored on a server.
- **Authored items** — the irreducible remainder Claude writes because no
  on-device rule could compute it: weather ("rain at soccer 4pm"), a loose email
  RSVP, a bespoke nudge. These keep the existing 6 typed card looks (file / link
  / invite / contact / geo / email).

**One on-device Priority & Ordering Engine** decides what surfaces, in what
order, and how many — for BOTH lanes. The design job is to make a heterogeneous,
ranked, *calm* feed feel like one coherent surface, and to make "why am I seeing
this" obvious and trustworthy.

**Scope = Phase A only: in-feed, foreground.** Do NOT design push/lock-screen
notifications here — that is Phase B and lives in the `triggers/` brief. You DO
design the in-feed geo-active "you're nearby right now" state.

## 2. Brand & tone (inherit ADR 0009)

Vibrant, expressive **visuals**; calm **behavior**. Warm, human, never childish
or robotic. Light is the hero; dark is first-class. Coral `#FF5436` primary,
Teal `#11B5A4` secondary, Violet tertiary. Outfit (display/title), Figtree
(body/label), Material Symbols Rounded. No gamification, no engagement-bait, no
red badges/counts. The "why" text must read like a calm human note, not a system
log.

## 3. The core design problems to solve

**A. One feed, two origins — unified but honest.** Derived and authored items
sit in the same ranked list and must feel like peers, distinguished only by a
small **provenance / "why" chip**, never by a jarring layout split. Design the
chip vocabulary:
- Derived: a computed reason + honesty affordance — e.g. "Party in 2 days",
  "You're near Safeway · matched on your device", "3 left before Saturday".
- Authored: "Added by Claude", "From your email", "Weather".
Show both chip families side by side so the distinction is legible but quiet.

**B. The derived-item anatomy.** Mock at least one of each reason_kind:
1. **countdown** — "Maya starts college · 12 days" (emphasized relative time).
2. **milestone** — a dated hub milestone approaching.
3. **checklist-due** — "3 left before the party" deep-linking into the hub block.
4. **geo-proximity** — "You're near *Place*" with the privacy affordance.
5. **time-window (`when`)** — "Pickup at 3:00".
Each derived item **deep-links into its hub** (reuse the container-transform /
"part of this hub" pattern). The "why" is the hero line; the body is light.

**C. Priority, calm budget & grouping — THE design-impacting part.** The engine
can produce more candidates than a calm feed should show. Design how the feed
expresses *ranking without anxiety*:
- **now / soon / later** grouping (or a single ranked list with subtle time
  dividers — propose both and pick). "Now" = the few things that need attention;
  "later" is reachable but de-emphasized.
- **Calm budget / overflow** — only the top N surface prominently; the tail
  collapses into a quiet "more" affordance, never a wall. Show the overflow.
- **geo-active boost** — when the user is physically near a place, that item
  rises and gets the M3E **emphasized** treatment (pulse/elevation). Show the
  same feed in a normal state and a geo-active state.
- **anti-nag decay** — an item shown repeatedly but not acted on visibly
  *softens* (de-emphasizes) rather than nagging. Show a "softened" item.
- **dedup / collapse** — a derived countdown ("Party in 2 days") and an authored
  nudge ("ordered groceries? [list]") about the *same* event must group into one
  unit, not appear twice. Design the grouped/collapsed treatment.

**D. Authored items keep their typed looks.** Reuse the 6 typed card renderers
from `content/` mockups — a weather authored item, an email RSVP authored item —
so the brief shows derived and authored typed cards coexisting in one feed.

## 4. Screens & states to produce (light + dark each)

1. **The merged feed — normal day.** Mixed derived + authored, now/soon/later
   grouping, calm budget with an overflow affordance.
2. **The merged feed — geo-active.** Same data, but the user is near a place: the
   geo item boosted/emphasized and reordered to the top.
3. **A busy day — overflow.** More candidates than the budget; the tail
   collapsed; nothing feels noisy.
4. **A dedup/collapsed group** — derived + authored about one event, as one unit.
5. **Derived-item detail / deep-link arrival** — tapping a derived item lands in
   its hub block with the existing highlight-pulse.
6. **Caught-up / empty** — reuse and extend the existing caught-up state for the
   case where no item clears the calm budget.
7. **The "why" / provenance chip catalog** — one reference screen showing every
   chip variant (derived reason_kinds + authored provenances) with the
   "matched on device · your location never leaves" honesty affordance.

## 5. Constraints & non-goals

- **Visuals only.** No app code, no real logic. Static + light interactivity
  (tap-to-detail container transform is welcome).
- **Phase A only.** No notifications / lock-screen / permission-request flows
  (those are the `triggers/` brief).
- **Calm guarantee is a hard constraint.** No counts/badges, no urgency-red
  spam, no infinite scroll anxiety. Ranking must feel like gentle triage.
- **Map every component to its M3 Compose name** (the client builds 1:1).
- **Reuse, don't reinvent** — pull tokens/type/shape/motion/components from the
  existing `Design-System.dc.html` and extend `Now-Phone.dc.html`.
- Commit prototypes to `designs/now-derived/` with an `Index.dc.html` that links
  every screen, light + dark.

## 6. What "great" looks like

A family member opens the app and instantly sees the two or three things that
matter *right now*, each with a one-glance human reason, with everything else
calmly within reach — and never once feels nagged, counted, or surveilled. The
derived/authored seam is invisible to them; the privacy promise is visible.
