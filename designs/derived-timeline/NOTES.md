# Derived timeline — design notes (delta on `../hub-timeline/`)

A **derived timeline** gives a hub an axis of time **when no one authored one**
(ADR 0046, Phase 2). It is a mechanical, on-device layout of the hub's *own*
already-dated blocks — checklist due-dates, milestones, location pickups, and the
hub's own countdown/start/end. Nothing is authored; nothing is invented by AI.

It renders through the **identical** card + detail as the authored timeline
(ADR 0045). This delta designs only the three things that make it read as
**derived, honestly** — plus the thin-content states.

Open **`Index.dc.html`** for the delta brief. Read `../hub-timeline/` first — the
two scales, density ladder, NOW line, grouping, ✓N collapse, transform, fonts and
M3 token palettes are all reused verbatim.

## Files
| File | What |
|---|---|
| `Index.dc.html` | The delta brief. What's unchanged, the three new pieces, thin-content states, render-only posture, and ADR 0046's open questions with recommended answers. Embeds the derived cards. |
| `Timeline-Card-Derived.dc.html` | The authored card, verbatim, with **one change**: the purple `auto_awesome` "Added to this hub" chip → a neutral outline `event` **"From this hub's dates"** chip. Props: `mode`, `scale`. |
| `Timeline-Detail-Derived.dc.html` | The authored detail, verbatim, plus **per-stop source tags** in the T3/T4 rows and a **neutral, honest footnote**. New prop `sourceDetail` (`minimal` \| `label` \| `verbose`) sets how loud the source hint is. The `alarm`/"Reminder" attachment was removed — render-only carries no notification affordance. |
| `Provenance.dc.html` | ADR 0046 decision 3. Authored vs derived chip head-to-head (light + dark) + five honest copy candidates rendered as the real chip + the footnote comparison. **Where the operator picks the wording.** |
| `Sparse.dc.html` | Thin-content states: a **sparse** two-date timeline (light + dark) and the **not-enough** empty state — a gentle nudge (Option A) vs show-nothing (Option B). Contrasted with the rich case. |
| `Tap-To-Detail.dc.html` | Live prototype. The same hub, now with **no authored timeline** — derived card → container-transform → derived detail. Light/dark + a live **source-detail** toggle. |

## The three new pieces

### 1. Provenance — mechanics, not magic (the crux)
- **Authored** = purple provenance tokens, filled tint, `auto_awesome` sparkle,
  "Added to this hub". It signals *someone placed this on purpose*.
- **Derived** = a **neutral outline chip** (`onSurfaceVar` text, `outlineVar`
  border, no fill, **no accent hue**) with a plain `event` calendar glyph and
  **"From this hub's dates"**. It reads as a quiet system fact — visually
  unmistakable from authored, and it never says or implies "AI".
- The detail's footnote is neutral too: *"Nothing was authored here. This
  [day/roadmap] is laid out from dates already in the hub … arranged on your
  device. It doesn't notify; it just shows what's already here, in time order."*
- Copy is a **decision** — five candidates on `Provenance.dc.html`; default is
  the shortest, most literal one.

### 2. Per-stop source — a hint, not a badge wall
- Each derived stop traces to one real block. A **quiet ghost tag** in the
  detail's T3/T4 rows names the source, visually distinct from the *filled*
  attachment chips beside it (ghost text + tiny outline-colored icon vs a solid
  pill).
- Four sources: `checklist` (checklist), `flag` (milestone), `location_on`
  (pickup), `event` (hub date).
- **Depth is a decision** — `minimal` (icon only) · `label` (icon + word,
  default) · `verbose` (icon + phrase). Live-toggleable in the detail and the
  prototype.

### 3. Thin content
- **Sparse (2 dates):** renders honestly — real span, no NOW line off-day, same
  chip. Never faked fuller.
- **Not enough (≤1 date):** a single date isn't a timeline. Option A = a gentle
  **"No timeline yet"** nudge that teaches the mechanic; Option B = show nothing
  (matches the authored empty state). Recommend the nudge for derived.

## Render-only posture (ADR 0046 decision 2)
A derived stop **does not notify** and is **never a Now item**. There is no bell,
no reminder chip, no "notify me" anywhere in the derived card or detail, and the
copy is kept consistent with that.

## Constraints held
Content-blind (no server reasoning) · ≥48dp targets (whole card opens; toggles,
rows and chips clear the minimum) · reduced motion falls back to cross-fade
(inherited) · Android / iOS / Desktop only.
