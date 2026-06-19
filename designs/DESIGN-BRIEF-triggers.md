# Design Brief / Prompt — Triggers, Notifications & Time/Location Content

**Hand this whole file to a fresh Claude Code (Claude Design) session.** It is
self-contained. Authoritative source: `../adr/0014-private-trigger-engine.md`,
`../adr/0009-design-system-m3-expressive-adaptive.md`, and the existing system
+ Now/Hubs mockups in `Family AI dashboard design brief/`.

---

## 0. How to run this

> **You are designing the hi-fi UI/UX for the private trigger engine** of
> family-ai-dashboard. Use the `frontend-design` skill. Produce **interactive
> HTML/CSS prototypes** that faithfully emulate **Material 3 Expressive**
> (reuse the tokens/type/shape/motion/components from the existing
> `Design-System` mockup — do NOT invent a new system). Mobile-first
> (~390–430px), **light + dark** for every screen. Map components to M3
> Compose names. Commit to `designs/triggers/`. Visuals only.

## 1. Context (what this feature is)

The app surfaces content based on **device location, date/time, and (later)
activity** — but **privately**: triggers are metadata Claude attaches to
content; the **device matches them on-device**; the **user's live location/
time never leaves the device**. Progressive permission (when-in-use first;
"Always" is an opt-in upgrade for background proximity). Calm: few, timely
notifications that earn the interruption.

**Make the privacy promise visible** — a subtle, trustworthy "matched on your
device · your location never leaves" affordance is a brand differentiator, not
fine print. Honest, never dark-pattern.

## 2. Brand & tone (inherit ADR 0009)

Vibrant, expressive **visuals**; calm **behavior**. Warm, human, not childish.
Provenance on AI content ("added by Claude"). No gamification, no engagement-
bait, no notification spam.

## 3. Screens & states to design

**A. Trigger affordances ON content (the core)**
1. A **briefing card with a time trigger** — countdown / "tomorrow 4pm" / alert
   chip; show the `when` affordance.
2. A **briefing card with a geo trigger** — a "near *Place*" / location chip;
   and its **active state** (you're currently in proximity → highlight/pulse,
   the M3E emphasized treatment).
3. A **Hub block with triggers** — e.g. a checklist item or doc with a
   time/place chip; how triggers read inside the dossier.
4. The **trigger-active highlight** vs **inactive** states, clearly distinct.

**B. Notifications**
5. **System notification** appearance (Android + iOS): a proximity notification
   ("Near the store — party list?") and a time notification ("Soccer 4pm — pack
   jackets"). Calm copy, source attribution.
6. **Tapping a notification → deep-links** into the exact card/Hub block
   (reuse the Now/Hubs deep-link highlight state).
7. **Notification grouping / quiet-hours / daily-cap** treatment — how "few,
   timely" looks; a digest-style group rather than a stream.
8. **In-app surfacing** of a just-fired trigger (a gentle banner/section in
   Now), as an alternative to a system notification.

**C. Permission flows (privacy-forward)**
9. **Location priming** screen (before the OS prompt): why we ask, the
   when-in-use ask, the on-device promise. Then the **"Always" upgrade** prompt
   (explicit opt-in for background proximity) — honest about the tradeoff.
10. **Notification priming** screen.
11. **Permission-limited states:** when-in-use only → an honest "open the app
    to see what's nearby" explainer (no nagging); denied → graceful fallback.

**D. Places management**
12. **Places** screen — define/edit home / school / store (label + map pin +
    radius). Family-scoped. The "your places stay private" affordance.
13. Add-a-place flow (map pin + radius slider).

**E. Cross-cutting**
14. The **"matched on your device"** privacy affordance — design the reusable
    component (a chip / info row / sheet) used across the above.
15. Offline / no-signal states for trigger surfacing.

## 4. Adaptive (specs + one frame each)

Phone gets full hi-fi. For tablet/desktop give a short note + one frame
(places management benefits from a larger map; notifications surface in the
Now pane). **Wear OS:** a proximity/time trigger is the *ideal* glanceable
tile — include one Wear tile concept ("Near store — list"). Activity triggers
are **out of scope** (schema slot only).

## 5. Constraints (honor or call out)

- Calm: notifications are few; design the cap/quiet-hours, not a feed.
- Honest privacy: never imply we track the user; "on-device" must be true in
  the visuals (no server-side-tracking iconography).
- Provenance on AI-authored triggers/content.
- Progressive permission — never request "Always" up front; the upgrade is
  opt-in and reversible.
- Reuse the existing M3E system + Now/Hubs deep-link states; don't fork them.

## 6. Output structure (commit here)

```
designs/triggers/
  index.html              (click-through index — update it)
  content-triggers/       (A: time/geo chips + active-highlight, L+D)
  notifications/          (B: system notifs, grouping, deep-link, in-app)
  permissions/            (C: location/notif priming + limited states)
  places/                 (D: places mgmt + add-place)
  privacy-affordance/     (E: the "matched on your device" component)
  adaptive/               (tablet/desktop frames + a Wear trigger tile)
```

## 6b. REVISION v2 — required fixes (3-agent review, 2026-06-18)

The first pass shipped (14.5/15 screens, token-consistent, calm). Fix these:

**P0 — privacy copy is dishonest (contradicts ADR 0014):**
- `Places.dc.html` ("They live on your devices", "Stored on-device; live
  location never recorded", `cloud_off`) and `Privacy-Affordance.dc.html`
  step 3 ("Nothing is sent") **overclaim**. Place coords (home/school) ARE
  **server-side family content, encrypted** — only the **live position** stays
  local. Rewrite to: *"Saved places sync to your family, encrypted. Only your
  live position — where you are right now — stays on this phone."* Add a
  one-time consent beat when first saving home/school ("shared with your
  family, stored encrypted"). Keep the true chip ("Location never leaves" =
  live position) but never imply saved places don't reach the server. Same fix
  in the Always-upgrade `promiseBody` and notification chips ("Matched on your
  device", not "Location never leaves").

**P1 — permission honesty:** `Permission-Phone.dc.html` `locPrime` over-promises
background proximity that when-in-use can't deliver. Reserve "the moment you
walk in / without opening the app" language for the **Always-upgrade** screen;
when-in-use surfaces nearby places *while the app is open*.

**P1 — geo = M1:** tag all geo-proximity + background-notification frames as
**M1** (M0 = time triggers + feed only; ADR 0014 §6 / 08-mobile-client). Mirror
the `activity`-deferred labeling so a builder doesn't ship M0 geofencing.

**P0 — offline state (#15) MISSING:** add a `Content-Phone` `offline` screen —
time triggers still fire + geo still matches on-device offline; only deep-link
content/map tiles degrade. "Offline · still matched on your device" tonal
banner (privacy teal). The on-device promise is *strongest* offline.

**Modern M3 Expressive (still missing the four May-2025 signatures):**
- **Physics motion:** replace the CSS keyframe pulses (`ct-halo`/`nt-pulse`)
  with **`MotionScheme.expressive()`** — **spatial spring** (overshoot) on the
  active-card container/elevation lift; **effects spring** (high-damp) on the
  teal color crossfade + halo. Collapse to the 4 Design-System motion tokens.
- **New components:** Places add = a **FAB Menu** expanding to the 4 place
  kinds (home/school/store/other — now in the schema); add the **Loading
  indicator** (waveform) on save + map-resolve + deep-link fetch; render the
  priming primary/secondary CTAs as a **button group / split button**.
- **Shape morph:** on proximity-active, **morph the card shape** (large→XL or a
  scalloped live-dot) synced to the spatial spring — the Design-System claims
  morph but no trigger screen shows it.
- **Emphasized type:** apply the M3E **emphasized** role to countdowns
  ("12 days", "In 1 hour") + imminent-alert chips; add the emphasized variant
  to the Design-System type ramp.

**P2 — a11y:** wrap all halo/pulse keyframes in `@media (prefers-reduced-
motion)` → static ring; bump the privacy chip to ≥11px; 48dp invisible hit-slop
on the radius slider thumb. **Softer glyph** (`my_location`/`pin_drop`) for the
Always-upgrade hero (the `radar` icon leans tracking-creepy).

**Schema note (already applied):** `Place.kind` (home|school|store|other) is now
in the schema/DDL — the add-place category chips are backed; render the per-
place icon from `kind`.

## 7. Definition of done
- All §3 phone screens, light + dark, clickable from `index.html`.
- The privacy affordance designed as a reusable component and shown in context.
- Notification designs for both Android + iOS, with the deep-link-on-tap state.
- Progressive-permission flow (when-in-use → Always opt-in) + limited states.
- Places management + add-place. One Wear trigger tile. Adaptive notes.
- Calm/honest/provenance constraints visibly satisfied; operator can approve.
