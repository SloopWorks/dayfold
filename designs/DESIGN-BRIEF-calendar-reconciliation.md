# Design Brief / Prompts — Calendar Reconciliation

**Hand this whole file to a fresh Claude Code (Claude Design) session.** Use the
`frontend-design` skill. This is the ADR 0008 hi-fi design gate for Proposed ADR
0063. Do not build app code or settle backend contracts in the mockups.

Authoritative Dayfold references:

- `adr/0063-client-owned-calendar-reconciliation.md` — product, privacy,
  matching, import, and notification boundary.
- `adr/0006-event-hubs-surface.md` — Hubs are curated dossiers; Calendar remains
  the schedule system of record.
- `adr/0008-design-first-hifi-mockups.md` — mockups + operator sign-off precede
  deep planning/build.
- `adr/0009-design-system-m3-expressive-adaptive.md` and
  `designs/Family AI dashboard design brief/designs/Design-System.dc.html` —
  visual system.
- `adr/0030-per-member-hub-and-card-visibility.md` — restricted visibility and
  audience honesty.
- `adr/0053-per-hub-participation-roles-and-delegated-management.md` — only
  Contributors/Co-owners may import into an existing Hub.
- `adr/0043-now-content-model-derived-plus-authored.md` and
  `designs/now-derived/` — Now grouping, `subjectKey` collapse, why/provenance.
- `adr/0044-phase-b-background-location-and-local-notification-posture.md` and
  `designs/triggers/` — local notification settings, calm caps, platform
  permission ceremony.
- `designs/two-way/` — review/confirm and optimistic-write interaction language.
- `designs/Family AI dashboard design brief/designs/Hubs-Phone.dc.html` and
  `designs/Family AI dashboard design brief/designs/Settings-Phone.dc.html` —
  existing phone chrome and information architecture.

---

## Prompt 1 — Generate the primary end-to-end experience

> **Design the complete hi-fi mobile experience for Dayfold Calendar Check.**
> Dayfold compares structured event-bearing content with calendars already on a
> member's Android or iPhone. Matching and calendar observations happen on that
> device. Dayfold never becomes the calendar system of record and never silently
> writes either direction.
>
> Produce interactive HTML/CSS `.dc.html` prototypes in
> `designs/calendar-reconciliation/`, with an `Index.dc.html` linking every
> screen/state. Match Dayfold's Material 3 Expressive system. Mobile first
> (~390–430 px), **light + dark for every Dayfold screen**. Map components to
> Compose M3 names. Include clearly labelled Android and iOS native-handoff
> frames where platform behavior differs. Visuals only—no real permissions,
> calendar access, provider API calls, or app code.

The product should feel like checking two lists before a busy week, not setting
up a sync engine. Use the user-facing feature name **Calendar Check**. Avoid
"two-way sync," "mirror," "import everything," "AI matched," and backend/OAuth
jargon.

## 1. Product truths every screen must preserve

### 1.1 Calendar remains canonical

- A calendar owns event scheduling and generic start-time alerts.
- Dayfold owns selective preparation, context, and a curated Event Hub.
- A Calendar → Dayfold action means "make or enrich a useful Hub," not "copy my
  calendar into Dayfold."
- No automatic creation, overwrite, deletion, invitation, RSVP, recurrence
  change, or audience widening.

### 1.2 This is personal and device-local

- Calendar Check is per member and per device. Do not show a family-global
  "connected" or "on everyone's calendar" state.
- Raw calendar observations, calendar choices, event identifiers, match
  fingerprints, and ignore decisions stay on this device.
- Only fields explicitly reviewed for a Dayfold import enter family content.
- New imported Hubs default to **Only me**. Sharing with the family is an
  explicit audience choice. Importing into an existing Hub inherits that Hub's
  audience and names it before confirmation.

Use concise, honest copy such as:

- "Compared on this phone"
- "Your calendar details stay on this device"
- "Only the fields you approve are added to Dayfold"
- "Calendar stays in charge of event alerts"

Never claim that a Calendar → Dayfold import remains entirely local; the
reviewed fields become synced Dayfold family content after confirmation.

### 1.3 Progressive permission, not an onboarding tax

There are two distinct modes:

1. **Add to calendar:** from a Dayfold Hub/card, open the platform's native
   prefilled event editor. Where supported, this does not require Calendar Check
   read access. The user chooses the destination calendar and saves/cancels in
   the OS surface.
2. **Calendar Check:** after a value primer, request calendar read access, then
   let the user choose which device calendars participate.

Never request calendar permission at startup, sign-in, family creation, or when
the user only views a dated Hub. "Not now" is a full-strength peer action.

### 1.4 Calm means one review, not one alert per gap

- The Now feed may show **one aggregate Calendar Check unit**, not a card for
  every event.
- Calendar gaps never create an OS notification, red badge, streak, overdue
  state, or recurring nag.
- The dedicated review screen holds the detail. Ignored items stay ignored on
  that device.
- When there is nothing to review, show a warm, compact all-clear state—do not
  turn correctness into a celebration loop.

### 1.5 Notification ownership is legible

For a matched event, Calendar owns generic "starts soon" alerts by default.
Dayfold may still produce a distinct preparation/action nudge through its
existing calm notification system.

Show the difference with one paired example:

- Calendar: "Soccer · 4:00 PM"
- Dayfold: "Soccer · two packing-list items left"

Do not promise global cross-app deduplication. Copy may say: "Dayfold won't send
a second start-time alert for this match. Other calendar apps keep their own
settings."

## 2. Information architecture and entry points

Design both entry points without creating a new top-level tab:

1. **Now:** an aggregate Calendar Check unit appears only when reviewable gaps
   exist. Example: "Calendar check · 2 things to review" with at most three
   quiet preview rows and one "Review" action.
2. **Settings → Calendar Check:** off/on state, permission state, selected
   calendars, local-only explanation, event-alert ownership, and reset controls.

Eligible Hub/detail surfaces also gain a quiet **Add to calendar** action. Do
not put it on content that lacks an explicit structured date/time.

## 3. Screens and states to produce

Produce every Dayfold-owned screen in light + dark. Native Android/iOS frames
may use their own platform appearance and should be labelled as OS-owned.

### A. Setup and calendar selection

1. **Settings — off:** feature summary, "Compared on this device," and Enable
   action. Add-to-calendar remains available independently.
2. **Read-access primer:** what Dayfold compares, what stays local, selected
   calendars only, and equal-weight "Not now."
3. **OS permission transition:** Android and iOS handoff markers; never fake an
   in-app permission grant.
4. **Calendar chooser:** device calendars grouped by account/provider but with
   account addresses masked. Each row shows name/color and included/off state.
   Default to none selected; provide "Continue" only after a deliberate choice.
5. **Denied / restricted / revoked:** useful fallback, OS-settings route, and
   no nagging. Add-to-calendar can still work where the platform allows it.
6. **No calendars / provider unavailable / work-profile restriction:** honest
   empty state with no broken primary action.

### B. Dayfold → Calendar native handoff

7. **Eligible Hub/card action:** "Add to calendar" with a short detail preview.
8. **Prefill review:** title, date/time or all-day state, timezone, structured
   location, and concise notes. Missing end/time is visibly incomplete. No
   attendee field and no Dayfold-authored reminder field.
9. **Android native event editor handoff:** prefilled form; the Calendar app owns
   destination-calendar choice and final save/cancel.
10. **iOS native event editor handoff:** same product truth, platform-accurate
    visual language.
11. **Return states:** checking calendar, matched/added, canceled, permission
    changed, and "We couldn't confirm it—check again". Never claim success just
    because the editor opened.

### C. Aggregate Now state

12. **Two gaps:** one Dayfold-only and one calendar-only preview in one calm
    unit.
13. **Busy horizon:** many gaps collapsed behind "Review 5" without urgency-red
    or a wall of cards.
14. **All clear:** no prominent card; show the compact Settings state and an
    optional quiet feed footer treatment.
15. **Offline / calendar temporarily unavailable:** previously known bindings
    remain, but no stale "all clear" claim.

### D. Dedicated review list

16. **Dayfold-only row:** "Not on this calendar" with Add to calendar, Ignore,
    and Open Hub.
17. **Calendar-only row:** "Calendar only" with Keep calendar-only, Add to an
    existing Hub, or Propose new Event Hub.
18. **Suggested match:** side-by-side title/time/location evidence with Confirm
    match and Keep separate. Never auto-select the primary action.
19. **Ambiguous match:** two plausible calendar events; make uncertainty
    obvious without error-red.
20. **Details differ:** compare Dayfold and Calendar by field; each changed
    field can keep Dayfold, use Calendar, or remain separate. No global silent
    winner.
21. **Recurring event:** honest Phase-1 limitation—review this occurrence or
    keep the series calendar-only; do not imply series synchronization.
22. **Ignored locally:** undo affordance and explanation that the choice applies
    only on this device.

### E. Calendar → Dayfold reviewed proposal

23. **Choose destination:** existing Hub (only Hubs the member may contribute
    to) or new Event Hub.
24. **Field preview:** checked list of exactly what enters Dayfold. Default on:
    title, start/end or all-day date, timezone, and structured location. Default
    off/omitted: event description, attendees, conference details, organizer,
    calendar/account metadata, and reminder rules.
25. **Audience preview:** new Hub defaults to Only me; explicit Family choice.
    Existing Hub names its current audience and warns before content becomes
    visible more broadly than the source calendar implied.
26. **Review and confirm:** no auto-apply. Show Calendar provenance and "Calendar
    remains the schedule."
27. **Apply states:** saving, saved with Open Hub, offline queued, permission
    lost, role denied, source changed during review, and version conflict.

### F. Matched-event and notification state

28. **Matched summary:** quiet Calendar color/destination, last checked, Open in
    Calendar, Unlink, and "Calendar handles start-time alerts."
29. **Distinct action nudge:** the paired Soccer example from §1.5.
30. **Per-match override:** a low-prominence control for "Event-time alerts:
    Calendar / Dayfold". Calendar is selected by default. Explain that this
    controls Dayfold only.

### G. Settings and reset

31. **On state:** selected calendars, last local check, read-permission state,
    event-alert default, and local-only privacy explanation.
32. **Change calendars:** removing one says its local matches will be reviewed,
    not that calendar events will be deleted.
33. **Turn off:** stops comparisons and removes local Calendar Check state; no
    calendar or Dayfold content is deleted.
34. **Reset local matches:** destructive confirmation names exactly what is
    cleared and that reviews may reappear; nothing external is deleted.

## 4. Visual and interaction direction

- Inherit Dayfold's coral/teal/violet M3 Expressive tokens, Outfit/Figtree type,
  rounded shapes, tonal surfaces, and motion scheme. Do not create a blue
  enterprise-sync sub-brand.
- Use a restrained `event_available` / `difference` / `calendar_month` icon
  vocabulary; avoid rotating sync arrows as the hero metaphor because this is
  review, not continuous bidirectional sync.
- Treat matching as a compare surface: paired tonal containers, a quiet bridge
  or checkmark, and field-level differences. Avoid spreadsheet density.
- Color from a user's calendar may appear only as a small accent dot/rail; it
  must not become semantic success/error color.
- Motion: small container transitions from aggregate card → review → detail;
  native editor handoff is an explicit surface transition. Honor reduced motion.
- Minimum 48dp targets, dynamic type, TalkBack/VoiceOver labels, color-independent
  state, and landscape-safe bottom sheets.

## 5. Copy guardrails

Prefer:

- "Review"
- "Compared on this phone"
- "Add to calendar"
- "Keep calendar-only"
- "Use Calendar details"
- "Calendar handles event alerts"
- "Only the fields you approve"

Never use:

- "Sync complete"
- "Two-way sync"
- "Mirror calendar"
- "Fixed automatically"
- "Dayfold monitors all calendars"
- "No data leaves your phone" after a Dayfold import
- "No duplicate notifications" as an absolute promise
- "Shared with everyone" without naming the actual audience

## 6. Prototype output and definition of done

Create:

- `designs/calendar-reconciliation/Index.dc.html` — gallery/navigation.
- `Calendar-Check-Phone.dc.html` — reusable Dayfold phone frame and primary
  states.
- `Review.dc.html` — gap, match, ambiguity, conflict, and ignore states.
- `Import.dc.html` — Calendar → Dayfold field/audience review and apply states.
- `Permissions-and-Settings.dc.html` — primer, selection, denial, on/off/reset.
- `Native-Handoff.dc.html` — clearly labelled Android/iOS native editor journey.
- `NOTES.md` — state map, M3→Compose component mapping, motion/a11y notes,
  unresolved design questions, and confirmation that no real user data appears.

The gallery must make these review questions answerable:

1. Does the feature feel like a calm gap check rather than a synchronization
   product?
2. Is it always clear whether Calendar or Dayfold is canonical?
3. Can a person tell what stays local and what becomes shared Dayfold content?
4. Is every external/calendar write user-confirmed in the native surface?
5. Can private calendar data accidentally become family-visible?
6. Does notification ownership prevent Dayfold-created duplicates without an
   impossible global promise?
7. Are denied, offline, ambiguous, recurring, and conflict states honest?

---

## Prompt 2 — Complete platform and edge-state coverage

Run this after Prompt 1 has produced the first gallery:

> Review the Calendar Check gallery against Proposed ADR 0063 and the full state
> inventory in this brief. Do not redesign the happy path. Add or correct every
> missing Android/iOS permission, native-editor return, offline, revoked,
> ambiguous, recurrence, version-conflict, role/visibility, and reset state.
> Verify that Android never reports "added" merely because `ACTION_INSERT`
> opened, and that iOS/Android OS-owned controls are visually labelled rather
> than imitated as Dayfold components. Produce a state-coverage matrix in
> `NOTES.md`, with rows for all 34 required states and links to their frames.

## Prompt 3 — Adversarial design refinement

Run this after Prompt 2:

> Act as a hostile privacy, calm-product, and accessibility reviewer. Try to
> prove the Calendar Check designs turn Dayfold into a calendar replacement,
> leak private calendar context into family content, overclaim cross-app
> notification deduplication, use dark permission patterns, or hide uncertainty.
> Then simplify the interaction count and visual hierarchy without removing an
> honesty or consent beat. Record findings and resolutions in `NOTES.md`, update
> the prototypes, and finish with a concise SHIP / DO-NOT-SHIP verdict for the
> operator's ADR 0008 sign-off.
