# Design Brief / Prompt — Two-Way Interaction (EXPLORATORY) — todos, authoring, context, delete, hide

**Hand this whole file to a fresh Claude Code (Claude Design) session.** It is
self-contained. Authoritative sources: `../adr/0009-design-system-m3-expressive-adaptive.md`,
`../adr/0008` (design-first), `../adr/0022-typed-content-library-detail-and-fold-gesture.md`
(the fold gesture + the three renderings), `../adr/0038-two-way-collaborative-content-mutation.md`
(Proposed), `../specs/two-way-collaborative-content-design.md` (interactive to-do),
`../specs/two-way-engine-and-content-management-design.md` (the W1–W5 engine),
`../backlog/operator-inbox.md` (INB-25 / INB-26 — the open decisions), and the
existing system + Now/Hubs/content mockups in `Family AI dashboard design brief/`
and `content/`.

> ⚠️ **EXPLORATORY — read this first.** These features are **pre-decision**: the
> to-do primitive (ADR 0038) and the W1–W5 engine are awaiting operator ratification
> (INB-25/INB-26), and several calls are deliberately **undecided** (W3 free-text vs
> structured; the hide model; whether members author at all). **This pass is to
> explore the design space and pressure-test the calls — not to ship a sign-off-ready
> surface.** Where a decision is open, **show 2–3 variants side by side** and label
> the trade-off. Mark every screen `EXPLORATORY` in-canvas. Nothing here implies the
> feature is committed.

---

## 0. How to run this

> **You are designing the hi-fi UI/UX for Dayfold's first two-way (member-writes)
> interactions.** Use the `frontend-design` skill. Produce **interactive HTML/CSS
> prototypes** that faithfully emulate **Material 3 Expressive** — **reuse the
> tokens/type/shape/motion/components from the existing `Design-System` mockup; do
> NOT invent a new system.** Mobile-first (~390–430px), **light + dark for every
> screen**. Map every component to its **M3 Compose** name (the build targets Compose
> Multiplatform). **Commit to a new exploratory section: `designs/exploratory/two-way/`**
> (its own `Index.dc.html` linking every screen; do not touch the committed galleries).
> Visuals + motion only — **no app code**. Animate the live interactions (tap,
> fold-away, optimistic→synced, offline) as real CSS/JS prototypes where it clarifies
> the behavior — the *behavior* is the point of this brief.

Seed colors: Coral `#FF5436` (primary) · Teal `#11B5A4` (secondary) · Violet
(tertiary). Type: **Outfit** (display/headline/title) · **Figtree** (body/label) ·
Material Symbols Rounded. Light is the hero; dark is first-class.

## 1. Context (what this is)

Today Dayfold is a **one-way dumb renderer**: a calm daily **briefing** (Now cards)
+ **Hubs** (dossiers), all authored by an AI loop / CLI; the app only *renders*.
This is the **first time members write from the device.** A toggle, a hidden card,
an uploaded photo — every one is an **optimistic local action that syncs in the
background** (foreground poll ~45s; no live presence). The brand metaphor is the
**fold** — "content folded away until it matters, then unfolds" — and the
constitution mandate is **calm, not addictive; not a chat/social/chore/gamified
app.** Member writes must feel instant, honest, and quiet — never a spinner farm,
never an error modal, never a notification storm, never a scoreboard.

## 2. Brand & tone (inherit ADR 0009 + the fold)

Vibrant expressive **visuals**; calm **behavior**. A member action is a *local
truth the family later confirms* — the UI commits instantly and reconciles
silently. Provenance, not presence: "✓ Dad · just now" as a quiet byline, never a
notification. Completion is a **fold** (done items fold away), not a deletion.
Honesty is load-bearing: a privacy/sync claim is shown **only where a real boundary
enforces it** (ADR 0022 D4).

## 3. The core design problems to solve (the hard parts — show your answers as designed states)

**P1 — The optimistic-state vocabulary (THE cross-cutting problem; design it first).**
Every two-way action shares one lifecycle: **instant optimistic** → *saving* →
**saved** (silent) / *offline — saved, will sync* / *couldn't save — will retry*.
Design **one calm, consistent visual language** for these states that works on a
checkbox, a card, a photo, a delete — **no per-action spinners, no error dialogs.**
Show the full ladder as a labeled state set (synced / pending / offline / failed-
retrying / failed-final), plus the micro-affordance (a hairline, a dimmed tint, a
tiny `cloud_off`/`schedule` glyph) — never a blocking overlay. This vocabulary is
reused by every screen below.

**P2 — The to-do tap + fold-away (the signature interaction).** A live prototype:
checkbox tap → instant fill + ✓ scale-overshoot + left-to-right strike-through wipe
(≤200ms, emphasized-decelerate) + one haptic tick (represent it). After a ~1.5–2.5s
debounce, completed items **fold into a collapsed "▸ 3 done" section** using the
same container-transform vocabulary as the card→detail fold, at row scale. Show: an
active list (2 live rows + a "3 done" fold line), the fold animation, un-check
(reverse), and the whole-row 48dp hit target. **No confirm dialog ever.**

**P3 — Calm multi-member awareness + conflict, with no modal.** A remote change
arrives ~30s later and flips a row you're looking at: animate it with the *same*
≤200ms transition (a calm self-animating row, not a glitch), with a byline
("Mom · just now") explaining it. **Never move a row out from under a finger** —
apply the *state* immediately but **defer the layout shift/fold** until the touch
ends. The race-loser case (a remote change overrides your just-made edit) reconciles
with a byline, **never** a "your change was discarded" dialog. Design these as a
storyboard.

**P4 — Delete vs Hide (two different gestures, two different feelings).**
- **Delete (W4)** is the one destructive op where a **calm warn is correct** (the
  toggle bans confirms — delete is the opposite). Design a non-alarming confirm
  (sheet, not a red scary alert), ACL-aware ("you authored this" vs disabled/absent
  when you didn't), that names the consequence ("removes it for the whole family").
- **Hide (W5)** is **personal and reversible** — "hidden for *you*", everyone else
  still sees it. Design the hide gesture, the **"show all / show hidden"** toggle,
  and hidden items as a calm collapsed section (echo the fold). Make the **personal
  vs family** distinction unmistakable so hide never reads as deletion or censorship.

**P5 — In-app authoring (W2) — minimal, calm, "on commit".** Members author into
existing hubs (not new ones, this pass). Design three tiny editors: a **markdown
note** (raw text + live preview, the existing renderer), a **todo builder**
(add-row, each row a field), a **link-add**. Save **on commit**, not per keystroke.
Also design the **absence** — where a member *can't* author, there is simply no "+"
affordance, and that emptiness must look deliberate (mirror the enrichment brief's
"a failure is invisible").

**P6 — "Add context" (W3) — the capture → "being organized" → result arc.** This is
the AI-mediated path: a member drops **text + photos + links**; an async Claude
routine integrates it into a hub later. Design: (a) the **capture sheet**;
(b) the **"Dayfold is organizing this…" placeholder** card (a calm pending state,
NOT a chat bubble — this is not a chatbot); (c) the **resulting AI-authored card**
with "added by Claude · from your note" provenance. **Open decision to explore as
variants:** a **structured/template-bounded** capture ("add this to → [hub]") vs a
**free-form** capture — show both and surface the trade (free-form trips the
"not-an-open-ended-chatbot" line). Honesty chip: "Processed by Claude on your device."

**P7 — Update media (W1) — pick/capture a hero, with the privacy truth visible.**
Design choosing a hub hero photo: pick/capture → an **encrypting/uploading pending
state** (reuse P1) → the enriched hero (reuse the cover/contain fit + accent ladder
from `hub-card-enrichment/`). Show the honest affordance that **EXIF/location is
stripped** and the image is private to the family ("Only your family can see this ·
stored privately"). Keep it light — one tap to set, the heavy machinery invisible.

## 4. Screens to produce (each light + dark; mark EXPLORATORY)

1. **`States.dc.html`** — P1 the optimistic-state vocabulary as a labeled set
   (synced/pending/offline/failed-retrying/failed-final) on a checkbox, a card, a
   photo tile, a delete; the offline banner; the retry affordance.
2. **`Todo-Interactive.dc.html`** — P2/P3: the live tap+fold-away prototype; the
   three renderings (Now-card progress summary "3 of 5 packed" → fold-open → Hub-
   block interactive list → Detail); the byline + remote-change cross-fade storyboard.
3. **`Delete-Hide.dc.html`** — P4: the delete warn-sheet (ACL-aware), the hide
   gesture, the "show all / show hidden" toggle, hidden-as-collapsed-section.
4. **`Author.dc.html`** — P5: the three editors (markdown/todo/link) + the
   deliberate no-author empty state.
5. **`Add-Context.dc.html`** — P6: capture sheet (structured **and** free-form
   variants) → "being organized" placeholder → AI-authored result with provenance.
6. **`Media-Update.dc.html`** — P7: pick/capture → encrypting/uploading → enriched
   hero, with the privacy affordance.
7. **`Index.dc.html`** — links all of the above; a short legend of the open
   decisions (INB-26) each screen informs.

## 5. Constraints (honesty, accessibility, calm — non-negotiable)

- **Honest chips only where a boundary enforces it (ADR 0022 D4).** At M0-plaintext
  the only true claims are about **sharing scope** + **sync timing**: "Shared with
  your family · Synced when online"; "You're offline — saved, will sync"; "Hidden for
  you"; "Processed by Claude on your device". **Do not** show "Stored on your device"
  as privacy (the server holds it too at M0) or "Location never leaves" on a checklist
  (that's a trigger-engine claim). Show each chip's ladder: chip → info-row → sheet.
- **Accessibility.** Interactive rows expose `Role.Checkbox` + state ("Pack
  sunscreen, checkbox, not checked, double-tap to toggle"); state never by color
  alone (strike-through + text reinforce); byline in the accessible label; the
  fold-away is a `Role.Button` expanded/collapsed; ≥48dp targets; reduced-motion
  drops the overshoot (cross-fade instead); haptics honor the OS setting. Note these
  on each screen.
- **Calm / constitution.** No streaks, no nags, no per-person scoreboards or
  completion-rates, no gamified haptic patterns (one tick max), no
  member-activity notifications. Assignment ("· Mom") reads as *coordination*
  ("Mom said she'd grab this"), never *chore assignment*. No conflict modals, ever.
- **Reuse, don't reinvent.** Pull color roles, type scale, shape (26dp card radius),
  elevation/surface tiers, and motion from the existing `Design-System` mockup;
  reuse the content `Detail` anatomy (hero → metadata → actions → provenance →
  related) and the `hub-card-enrichment` hero/fit/accent ladder.

## 6. Deliverables

Interactive HTML/CSS in **`designs/exploratory/two-way/`** (the 7 files in §4),
light + dark, M3-Expressive, Compose-named components, every screen badged
`EXPLORATORY`, open decisions shown as labeled variants. Add a short
`designs/exploratory/two-way/NOTES.md` capturing: which INB-26 decision each
variant informs, and any new design question the exploration surfaced (feed those
back to `backlog/operator-inbox.md` / `context/open-questions.md`). **Visuals only —
no app code, no schema, no ADR edits.**
