# Design Brief / Prompt — Dayfold "Now" Feed Empty & Posture States

**Hand this whole file to a fresh Claude Code session to produce the hi-fi
mockups.** Self-contained. Inherits brand, tone, and the M3-Expressive design
system from `DESIGN-BRIEF.md` (§2 Brand & tone, §3 Design system) — read that
first. Companion to the already-built `Now-Phone.dc.html`. Resolves
`context/open-questions.md` → **OQ-feed-empty-state**. Gated by **ADR 0008**
(design-first): produce the comp, then the operator signs off before any build.

---

## 0. How to run this

> **You are designing the hi-fi UI/UX for the "Now" feed's empty & posture
> states.** Use the `frontend-design` skill. Produce **interactive HTML/CSS**
> that faithfully emulates **Material 3 Expressive** (color roles, tonal
> palettes, expressive type/shape/motion). Build target is Compose Multiplatform
> — treat these as a visual spec; name things after M3 components so they map 1:1
> to Compose. **Match `Now-Phone.dc.html` exactly** — same 390×844 phone frame,
> status bar, large top app bar ("SATURDAY · JUN 20" + avatar), bottom nav
> (Now / Hubs), the `.dc.html` token DSL (`{{ c.surface }}`, `{{ c.onSurfaceVar }}`,
> Figtree/Outfit, Material Symbols Rounded). Visuals only — no app logic.

## 1. Context — the gap

The "Now" feed is the daily briefing (short, time-bound cards). Today
`FeedScreen` renders **one** empty state — `FamilyNullState` (onboarding:
"Your family space is ready · Invite a member · connect a device") — for **any**
empty `state.cards`. That misframes an **established** family that's simply
caught up (e.g. a hub authored, zero briefing cards — the operator's own
account) as "nothing set up yet." We need distinct, on-brand posture states.

**Calm = behavioral restraint, vibrant pixels** (inherit `DESIGN-BRIEF.md` §2):
an empty feed is a *good* outcome ("nothing needs you"), never a dead-end or a
nag. No badges, no engagement bait.

## 2. States to design (phone, light **and** dark each)

1. **Caught up** *(established family, no cards right now — the headline new
   state)*. A calm, warm, *positive* state — "You're all caught up" / "Nothing
   needs you right now," on-brand illustration or expressive glyph, generous
   whitespace. Offer a quiet forward path, not a nag: a subtle link to **Hubs**
   ("Your hubs are here →") when the family has hubs, and/or "We'll surface what
   matters as your day fills in." Must feel like rest, not absence.
2. **First-run / onboarding** *(brand-new family, nothing set up yet)*. Keep the
   existing `FamilyNullState` intent (invite a member / connect a device) but
   make it visually distinct from #1 — this is "get started," #1 is "all clear."
3. **Syncing** *(first load, no cached cards)*. A calm skeleton or "Syncing…"
   posture consistent with the loaded feed's rhythm — not a spinner-in-a-void.
4. **Offline / sync error** *(no cached cards + a failure)*. Honest, recoverable:
   the reason + a "Try again" — never a dead-end. (A separate, lighter inline
   banner already exists for the *populated*-but-stale case; design that
   relationship.)

## 3. Decide the distinguisher (spec output, not just visuals)

The feed currently can't cheaply tell **new** from **established**: `state.hubs`
and `state.members` aren't loaded on the Now surface yet. The design must pick the
signal and note it for the build, e.g.: a lightweight "family has any content"
flag on sync; `members.size > 1`; a "has ever synced a card" marker; or load a
cheap hub/member count with the feed. Recommend one; the engineer wires it.

## 4. Conventions (match exactly)

- Inherit **`DESIGN-BRIEF.md` §2 (brand & tone)** + **§3 (M3-Expressive design
  system)** — color roles, tonal palettes, type/shape/motion. Light is the hero;
  ship dark too.
- Reuse the `Now-Phone.dc.html` chrome (status bar, large top app bar, bottom
  nav) so these drop into the same frame.
- Material Symbols Rounded for any glyphs; note weight/fill.

## 5. Output & definition of done (ADR 0008 / board A8)

- Commit `States-Feed.dc.html` (the four states, light + dark) beside the other
  `designs/.../designs/*.dc.html` comps.
- A short notes block: the chosen new-vs-established **signal**, and the M3
  component mapping (so it maps 1:1 to Compose `FeedScreen`).
- Operator signs off → then the build re-routes `FeedScreen`'s single empty
  branch into the four states + wires the signal (resolves OQ-feed-empty-state).
