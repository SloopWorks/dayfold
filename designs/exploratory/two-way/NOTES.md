# Two-way interaction — exploratory design notes

**Status: EXPLORATORY / pre-decision.** These screens explore Dayfold's first
member-writes interactions (ADR 0038 *Proposed*, the W1–W5 engine). Nothing here
implies the feature is committed. Where a call is open, the screen shows 2–3
labelled variants. Visuals + motion only — no app code, schema, or ADR edits.

Open `Index.dc.html` to click through all six screens (each is light + dark via the
in-canvas toggle, M3-Expressive, Compose-named).

## Screens

| File | Problem | What it answers |
|---|---|---|
| `States.dc.html` | **P1** | The optimistic-state vocabulary — one calm language (synced / saving / offline / retrying / couldn't-save) on a checkbox, card, photo tile and delete, plus the offline banner + the single retry affordance. **Reused by every other screen.** |
| `Todo-Interactive.dc.html` | **P2 / P3** | The signature tap → overshoot → strike-wipe → debounce → fold-away (live), the three renderings (Now summary / Hub block / Detail), and the multi-member + conflict storyboard. |
| `Delete-Hide.dc.html` | **P4** | The ACL-aware delete warn-sheet (calm, not red) and the personal/reversible Hide with a show-hidden toggle; three hide-trigger variants. |
| `Author.dc.html` | **P5 (W2)** | Three on-commit editors (markdown / to-do / link) + the deliberate no-author empty state. |
| `Add-Context.dc.html` | **P6 (W3)** | Capture → "being organized" placeholder → AI-authored result with provenance; structured vs free-form capture variants. |
| `Media-Update.dc.html` | **P7 (W1)** | Pick/capture → encrypting + uploading (reuses P1) → enriched hero with the accent ladder + the honest privacy affordance. |

## Which INB-26 decision each variant informs

- **Q1 · How is Hide triggered?** — `Delete-Hide` shows three gestures.
  Variant **A** (swipe + collapsed section) is the on-brand lean (mirrors the fold);
  **B** (overflow + filter chip) is the accessible/explicit fallback; **C**
  (long-press, Hide + Delete together) is flagged a likely **no** — it conflates a
  personal op with a destructive one.
- **Q2 · How bounded is "add context" capture?** — `Add-Context` puts the
  **structured** (destination + kind up front) and **free-form** (one open field)
  sheets side by side. Recommendation: **lean structured for v1** — it keeps the
  "not-an-open-ended-chatbot" line clearly intact; revisit free-form once the
  no-reply / provenance model is proven to read as a tool, not a chat.
- **Q3 · Do members author directly, or only via the AI path?** — `Author`
  argues direct editors are worth it *and* designs the absence (no "+") for members
  who can't, so "no" looks deliberate, not broken.
- **Q4 · Is a delete confirm acceptable at all?** — `Delete-Hide` argues the
  calm-toggle's ban on confirms has exactly one exception: W4 delete (family-wide,
  irreversible) earns one non-alarming sheet.
- **Q5 · How loud should remote-change provenance be?** — `Todo-Interactive`
  lands on **byline-only** ("Mom · just now"), never a toast or notification, and
  defers the layout shift until the touch ends so a row never moves under a finger.

## Honesty (ADR 0022 D4) — claims we did / didn't make

At M0-plaintext the only true claims are **sharing scope** + **sync timing** (plus
enforced client-side boundaries). Used throughout:
- "Shared with your family · synced when online", "You're offline — saved, will sync"
- "Hidden for you · your family still sees these"
- "Processed by Claude on your device" (P6 — on-device processing is the real boundary)
- "Location & EXIF removed before it leaves your phone" (P7 — an enforced client strip)
- "Only your family can see this" (P7 — sharing scope, true)

Deliberately **avoided**: "stored only on your device" as a privacy claim (the server
holds it too at M0), and any trigger-engine claim ("location never leaves") on a
checklist.

## New questions this exploration surfaced (feed back to operator-inbox / open-questions)

1. **Debounce timing vs. rapid multi-check.** When a member checks 4 rows fast,
   should each fold on its own ~1.8s timer (staggered) or batch into one fold once
   the burst settles? Staggered feels busy; batching needs a "burst end" heuristic.
2. **Done-section ordering & cap.** Does "N done" sort by completion time, by author,
   or keep list order? And is there a point (e.g. 20+ done) where it collapses to a
   count with no expand, to stay calm?
3. **Conflict on a *value*, not a boolean.** The byline-reconcile model is clean for
   a checkbox; a two-way *text* edit (two people editing the same note) has no
   "race-loser" equivalent. Is W2 single-writer per block, or do we need a merge story?
4. **Queue-pill placement when offline + authoring.** If a member writes three notes
   offline, does the "3 waiting to sync" pill live in the app bar, per-hub, or both?
   Risk of it reading as a nag if duplicated.
5. **AI-result provenance when the source spans members.** If Dad's note and Mom's
   photo both feed one AI-authored card, whose byline shows? "Added by Claude · from
   the family" vs naming contributors.
6. **Hidden vs. deleted discoverability.** If a member hides a card others rely on
   ("where did the route go?"), there's no family-visible signal — by design. Confirm
   that's acceptable, or design a one-line "you hid this" self-reminder.
