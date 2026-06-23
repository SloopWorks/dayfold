# SloopWorks — Brand System (extracted for implementation)

> Source: Claude Design project "SloopWorks visual identity"
> (`SloopWorks Styleguide / Logos / Lockups .dc.html`). This is the compact,
> implementation-facing extract; the full hi-fi brand mockups live in that
> Design project and can be mirrored into this folder on request.
> Tagline: *"A toolkit for software that ships."* · SloopWorks LLC, Washington USA.

## Voice
Calm, technical, precise. Near-black on off-white, **one decisive accent**,
hairline borders, a **monospace voice** for labels/code/metadata. "A studio that
builds." Motion confirms, never performs.

## Color tokens

**Light** (`:root`, default):
```
bg #FBFBFC · surface #FFFFFF · surface-2 #F4F4F6
border #E8E8EB · border-strong #D6D6DB
text #121317 · muted #56575E · faint #8A8B93
accent #2A53F0 · accent-fill #2A53F0 · accent-press #1E40D6
accent-soft #EDF0FE · on-accent #FFFFFF
ok #1A9E55 · warn #B5740C
shadow-sm 0 1px 2px rgba(18,19,23,.05) · shadow-md 0 8px 28px -10px rgba(18,19,23,.14)
```
**Dark** (`[data-theme=dark]`):
```
bg #0A0A0C · surface #101013 · surface-2 #17171B
border #232329 · border-strong #33333B
text #F3F3F5 · muted #9B9CA6 · faint #67686F
accent #86A1FF · accent-fill #3358F2 · accent-press #5B7BFF
accent-soft #15182C · on-accent #FFFFFF
ok #46C97E · warn #E0A33A
shadow-sm 0 1px 2px rgba(0,0,0,.5) · shadow-md 0 10px 34px -12px rgba(0,0,0,.7)
```
One accent hue does the heavy lifting; everything else restrained neutrals.
All text pairings meet WCAG AA.

## Typography
- **Geist** — sans, everything you read. Weights 400/500/600/700.
- **Geist Mono** — labels, code, metadata, accents (the "writes software" signal).
- Scale: Display 48/1.05/600 · Heading 30/1.15/600 · Subhead 21/1.3/500 ·
  Body 16/1.6/400 · Small 14/1.55/400. Tracking −2% on display/headings.

## Space / radius / elevation / motion
- **Spacing:** 4px base (4·xs, 8·sm, 16·md, 24·lg, 48·xl, 96·2xl).
- **Radius:** 6·sm, 10·md, 14·lg, full (~30). Modest.
- **Elevation:** hairline borders over shadow — depth implied, not announced.
- **Motion:** 120ms fast (hover/toggle/focus), 180ms base (cards/menus/state).
  One ease-out: `cubic-bezier(.2,0,0,1)`. Movement of only a few pixels.

## The mark — "a sail, reduced to geometry"
A small fast craft moving forward; the boat is subtext, never clip-art.
Monochrome-safe, favicon-ready. SVG paths (viewBox `0 0 48 48`):

- **Rig** (header lockup mark, mainsail + jib — used across the brand site):
  `M26 7 L26 38 L41 38 Z` (mainsail, accent) + `M22 16 L22 38 L8 38 Z` (jib, accent)
  · in 24-grid header: `M13 3 L13 19 L3.5 19 Z` (accent) + `M15 13 L15 19 L20.5 19 Z` (text)
- **Sail** (simplest triangle): `M18 7 L18 38 L40 38 Z`
- **Battens** (recommended **primary** standalone/wordmark — technical, precise):
  `M18 8 L18 38 L40 38 Z` (sail) + battens `M18 21 L31 21` + `M18 30 L36 30`,
  stroke = text/accent, width 3, round joins/caps.
- **App icon** (recommended favicon/avatar): rounded square + knocked-out sail —
  `rect x8 y8 w32 h32 rx9` fill accent + `M21 15 L21 33 L32 33 Z` fill surface.

Wordmark: **Geist 600, −2% tracking**. Lockups: mark-alone · horizontal ·
stacked · favicon 32/16. Accent + ink = monochrome-safe.

## Debug-drawer default skin (from the drawer mockup palettes)
The drawer's **SloopWorks** skin uses the tokens above; the mockup also defines a
**Dayfold consumer skin** to prove theming (`accent #C75C3C` light / `#E89070`
dark, warm neutrals). Fixed cross-app status colors:
`ok #46C97E/#1A9E55 · warn #E0A33A/#B5740C · err #F2685E/#C5392B`, and log levels
`V=muted D=accent I=ok W=warn E=err`. See `designs/debug-drawer/spec.md` §3 for
the full overridable-token contract.
