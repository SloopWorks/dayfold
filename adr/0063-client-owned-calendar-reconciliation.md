# ADR 0063: Client-Owned Calendar Reconciliation with Native Calendar Handoff

## Status

**Proposed** 2026-08-08 (operator-directed draft after approving the feature
direction). Operator-gated: this adds calendar-data access, a new permission
posture, a member-visible authoring path, and notification-suppression behavior.
The operator's direction authorizes this draft and its design prompt; it does not
accept the ADR or authorize build.

Extends ADRs 0006 (Hubs remain curated dossiers, not the calendar system of
record), 0008 (design first), 0014 (private on-device matching), 0024
(device-local never-sync state), 0030 (restricted visibility), 0039 (typed
member mutation spine), 0043 (shared subject keys and one priority engine), and
0044 (local notification posture). It is compatible with Proposed ADR 0061's
provider-connected Calendar source, but the first slice does not depend on a
cloud routine or provider connector.

Design gate:
`designs/DESIGN-BRIEF-calendar-reconciliation.md`.

## Context

Dayfold already stores structured event-bearing content: Hub start/end dates,
dated milestones, and time triggers. A family member may reasonably expect a
meaningful Dayfold event to appear on their own calendar. The opposite gap also
matters: a significant calendar event may deserve a curated Dayfold Hub, but it
can remain invisible to Dayfold until a person or external author notices it.

Blind bidirectional synchronization would cross Dayfold's product boundary. A
calendar is the canonical schedule; a Dayfold Hub is a selective, derived
dossier. Copying every meeting into Dayfold would create a second calendar,
while automatically pushing Dayfold changes back would make Dayfold responsible
for invitation, recurrence, conflict, and delivery semantics it deliberately
does not own.

The data boundary is also load-bearing. Calendar titles, locations, attendee
lists, notes, and reminder choices can be highly private. Server-side Google
Calendar access would add OAuth-token custody, sensitive-scope verification,
provider-specific synchronization, and a new plaintext-data surface before
dogfood proves the value. Native device calendar stores already aggregate the
calendars a person has chosen to place on that device.

The notification question is separate but adjacent. If Dayfold and Calendar
both post a generic "event starts soon" alert, Dayfold has created noise even if
each notification is individually valid. Dayfold can prevent duplicates for
events it has matched, but it cannot guarantee the behavior of unrelated apps
or calendar-provider settings.

Relevant current platform capabilities:

- On iOS 17+, EventKitUI can present an event editor without granting the app
  calendar access, while reading events requires full calendar access.
  `[fact:https://developer.apple.com/documentation/eventkit/accessing-calendar-using-eventkit-and-eventkitui]`
- Android recommends a prefilled Calendar `ACTION_INSERT` intent for event
  creation; that handoff does not require `WRITE_CALENDAR`, while direct reads
  require `READ_CALENDAR`.
  `[fact:https://developer.android.com/identity/providers/calendar-provider]`
- A future Google Calendar connector could use incremental sync tokens and
  application-private extended properties, but inserting events requires an
  authorized Calendar scope and production sensitive-scope use is subject to
  Google's verification posture.
  `[fact:https://developers.google.com/workspace/calendar/api/guides/sync]`
  `[fact:https://developers.google.com/workspace/calendar/api/guides/extended-properties]`
  `[fact:https://developers.google.com/workspace/calendar/api/v3/reference/events/insert]`
  `[fact:https://support.google.com/cloud/answer/13464321]`

## Proposed decision

### 1. Reconciliation is an opt-in, client-owned feature

The Android and iOS clients own calendar observation, matching, gap detection,
and the native add/edit handoff. The Dayfold API does not receive calendar OAuth
tokens, raw calendar snapshots, calendar/account identifiers, attendee lists,
reminder configuration, or local match decisions in the first slice.

Calendar reconciliation is per member and per device. One member having an
event on their calendar does not imply that another member has it. No
family-global "on calendar" bit is stored or inferred.

The permission ladder is progressive:

1. **Add only:** an eligible Dayfold item can open the platform's native,
   prefilled event editor without first enabling reconciliation where the OS
   supports that posture. The user edits the details, chooses the destination
   calendar, and explicitly saves or cancels.
2. **Reconcile:** only after an explanatory primer does Dayfold request the OS
   access needed to read calendar events. After the OS grant, the user selects
   which device calendars Dayfold may include in its local comparison.

Never request calendar access at sign-in, family creation, app startup, or the
first time a user merely views a dated Hub.

### 2. Only structured Dayfold content becomes an event candidate

The common client derives a `DayfoldEventCandidate` only from explicit typed
fields:

- Hub `start_at` / `end_at` (with `countdown_to` as relevance metadata, not a
  second event);
- block/card `when.at` triggers;
- explicitly dated milestone-like blocks when their semantics are sufficient
  for a calendar entry.

The client never parses Markdown or prose to invent a date, duration, location,
attendee, or recurrence. Missing fields remain visibly missing in the native
editor for the user to complete.

The local projection carries a stable Dayfold `subjectKey`, title, start/end or
all-day date, timezone, structured location when present, a source
version/fingerprint, and a deep-link target. Attendees are never prefilled:
saving an event with attendees can send invitations, which is an external
action beyond this feature's authority. Dayfold also does not prescribe a
calendar reminder; the selected calendar's defaults and the user's edits remain
authoritative.

### 3. Calendar observation and bindings stay device-local

Each platform adapter returns a bounded, in-memory
`CalendarEventObservation` for events in user-selected calendars and a limited
time horizon. The shared reconciler consumes those observations off-main.

A local SQLDelight `calendar_binding`/decision projection may persist only the
minimum mechanical state needed for stable behavior:

- Dayfold `subjectKey` and source version;
- platform calendar/event identifier;
- deterministic event fingerprint and last-seen time;
- relation state (`matched`, `ignored`, `needs_review`, `missing`);
- notification owner (`calendar` or `dayfold`);
- local dismissal/review state.

Raw event descriptions, attendee identities, event bodies, calendar account
names, and reminder details are not copied into Dayfold storage. Calendar
observations, identifiers, fingerprints, selections, decisions, and permission
state are never synced, logged, attached to bug reports, or emitted to
analytics. Conflict screens re-read the current event rather than retaining a
second raw copy.

The binding state is wiped on sign-out/family removal and preserved across a
content full-resync for the same signed-in family, matching the existing
device-local surfacing/notification posture.

### 4. Matching is deterministic and conservative

The reconciliation order is:

1. a still-valid explicit local binding;
2. a strict deterministic fingerprint over normalized title, start/end,
   all-day state, timezone, recurrence identity, and structured location;
3. a high-confidence suggested match shown for user confirmation;
4. unresolved when multiple or low-confidence candidates remain.

A still-valid binding or one unique strict-fingerprint match may suppress a gap
automatically and persist the local binding. A fuzzy match never causes a
write, link, notification suppression, or dismissal without user confirmation.
Calendar and Dayfold edits never overwrite one another automatically; a
mismatch becomes a compact compare-and-choose review.

The first slice handles non-recurring events and single occurrences. Recurring
series creation, series-level binding, exceptions, attendee responses,
cancellations, and organizer semantics are deferred. The UI must say so rather
than flattening a series into a misleading one-off match.

### 5. Gaps surface as one calm in-app review, never an interruption

Reconciliation emits at most one aggregate in-app "Calendar check" unit into
the existing Now surface, plus a dedicated review screen. It does not create an
OS notification, red badge, card per event, or recurring nag. The comparison
uses a bounded future horizon; the exact dogfood default is a spec/config
constant to ratify after design, not an unbounded scan.

Outcomes:

- **Dayfold-only:** open the native prefilled event editor; after return, rescan
  before claiming success. Android's intent handoff may not return a reliable
  created event identifier, so "Added" is shown only after observation or an
  explicit user confirmation—not merely because the editor opened.
- **Calendar-only:** keep calendar-only, ignore locally, add to an existing Hub,
  or propose a new Event Hub.
- **Matched but different:** compare structured fields and let the user choose
  what to copy. No silent winner.
- **Ambiguous:** confirm a match or leave unresolved.

"Calendar-only" does not mean every meeting is missing from Dayfold. The review
is limited to calendars the user selected and must provide durable local ignore
controls. Dayfold remains selective.

### 6. Calendar to Dayfold is a reviewed import, not calendar sync

A Calendar → Dayfold action creates a reviewable, normalized proposal. The
preview names every field that will enter Dayfold and omits event description,
attendees, conferencing data, and private calendar metadata by default. The user
may explicitly add supported fields before applying.

Applying the proposal uses the existing authenticated content/mutation spine
and author/role/version gates. It sends only the normalized fields the user
reviewed; it never grants the server ongoing calendar access.

Privacy defaults:

- a new imported Hub defaults to **restricted to the importing member**;
- family visibility is an explicit choice with a named audience preview;
- importing into an existing Hub requires contributor/co-owner authority and
  inherits that Hub's visibility, which the confirmation screen states before
  the write;
- the resulting block/Hub shows Calendar provenance while Calendar remains the
  canonical schedule.

The exact typed mutation/proposal contract is deferred until after the ADR 0008
mockup gate. No build may route this through an unrestricted prompt or let a
cloud routine silently apply it.

### 7. Calendar owns generic event-time alerts by default

For a confirmed or unique strict-fingerprint match, the local binding defaults
`notification_owner = calendar`. The Dayfold notification planner suppresses a
generic Dayfold time/start notification for that subject. This is keyed by the
existing stable `subjectKey`, not title matching. Suppression applies to the
event-start/time candidate, not every item in the subject hierarchy; it must not
reuse a whole-subject suppression set that would also discard a distinct
checklist or preparation candidate.

Dayfold may still surface or locally notify a semantically distinct,
action-oriented item—such as an incomplete preparation checklist or a weather
qualification—through the existing ADR 0043/0044 ranker, daily cap, quiet hours,
and same-subject collapse. It must not disguise a duplicate start alert as an
action nudge.

This boundary is reversible per binding. Dayfold is responsible for duplicates
it creates or can identify. The user remains responsible for notification
behavior in unrelated apps and provider settings Dayfold cannot observe; the UI
must not promise cross-app global deduplication.

### 8. Mobile first; server Calendar integration remains separate

The first implementation targets the Android and iOS device calendar stores.
Web/desktop reconciliation, CalDAV, direct Exchange/iCloud integration, and a
Dayfold-owned Google Calendar OAuth connection are out of scope.

A provider-connected Smart Briefing may independently read Calendar under
Proposed ADR 0061, but it does not become the source of truth for this local
binding/notification feature. Any server-side Calendar connector requires a new
or superseding ADR covering OAuth verification, token storage, provider
coverage, deletion/export, observability, E2EE interaction, and maintenance.

## Rationale

Client-owned reconciliation is the narrowest authority that can answer the
product question: the device already sees the calendars its member chose to
sync, while Dayfold already has the structured event candidates. A pure shared
reconciler keeps matching testable and consistent; thin platform adapters own
permission and native-editor differences. The result adds no calendar
credential or raw-snapshot custody to the server.

Native, user-confirmed event creation also preserves Calendar's role. Dayfold
can prefill useful structured details without silently choosing a calendar,
sending invitations, inventing recurrence, or becoming responsible for event
delivery. A reviewed import in the reverse direction gives Dayfold only the
selective dossier content the user actually wants, under existing audience and
author gates.

## Consequences

### Positive

- Delivers useful Dayfold ↔ Calendar gap detection without server-side calendar
  custody or a Google-only architecture.
- The native editor gives the user final control over destination calendar,
  details, and save/cancel behavior.
- Per-device matching reflects the real per-person nature of calendars.
- Reuses `subjectKey`, the local SQLDelight posture, and the existing one-engine
  notification cap/dedup machinery.
- Makes duplicate start-time alerts Dayfold's responsibility where Dayfold has
  enough information to act.

### Negative

- Android/iOS adapters and permission behavior differ and need separate native
  testing.
- Local-only bindings do not synchronize dismissals or match state across a
  person's devices; the same calm in-app review may reappear elsewhere.
- Native event identifiers and recurring-event behavior are imperfect, so
  conservative matching produces some manual review.
- There is no web story and no guaranteed background cadence for noticing
  calendar changes.
- Calendar → Dayfold adds a reviewed member-authoring surface that needs a
  carefully scoped mutation contract and visibility UX.

## Explicitly rejected

- **Backend-first Google Calendar synchronization.** Too much credential,
  verification, provider-lock-in, and data-custody surface before value is
  proven; does not cover Apple/Exchange calendars visible on device.
- **Automatic bidirectional sync.** Turns Dayfold into a calendar replacement
  and creates recurrence, invitation, conflict, and deletion obligations.
- **Put every calendar event in Dayfold.** Violates the curated-Hub model and
  creates a noisy second calendar.
- **Parse prose for events.** A plausible date is not authoritative event data;
  false event creation violates Dayfold's honesty guarantee.
- **Direct background writes to a selected calendar.** Requires broader write
  authority and removes the user's final native confirmation/calendar choice.
- **Ask the user to solve all duplicate alerts.** Dayfold must suppress the
  redundant start alert when it has an explicit match; it simply cannot promise
  control over unknown apps.
- **Inspect calendar reminders and attempt perfect cross-app notification
  prediction.** Delivery depends on provider/app/device state; the reliable
  product boundary is ownership of generic start alerts, not a false global
  guarantee.

## Acceptance gates

Before this ADR can be Accepted:

1. Two fresh-context adversarial reviews: correctness/privacy/platform
   behavior, then simplification/maintenance.
2. Hi-fi Android/iOS mobile flows produced from
   `designs/DESIGN-BRIEF-calendar-reconciliation.md` and explicitly signed off
   by the operator (ADR 0008).
3. Operator ratifies the initial bounded horizon, eligible candidate types,
   and the Calendar-owned start-alert default.
4. The Calendar → Dayfold proposal/mutation authorization and visibility
   contract is specified and reconciled with ADRs 0030/0039/0053.
5. Permission/disclosure copy is reviewed on supported Android/iOS versions;
   public-store data-safety/privacy disclosures remain a pre-public gate.
6. Tests prove raw calendar fields, identifiers, selections, and fingerprints
   cannot enter sync, logs, analytics, bug reports, or crash/error payloads.

## Revisit triggers

- Per-device state produces enough duplicate reviews or stale matches to harm
  dogfood value.
- A web/desktop calendar experience becomes necessary.
- Families demand automatic calendar edits rather than reviewed native handoff.
- Recurring series are common enough that single-occurrence handling is
  misleading.
- A server connector's cross-device value outweighs its OAuth, privacy,
  provider-coverage, and steady-state maintenance cost.
- Calendar-owned start alerts cause missed timing alerts often enough to justify
  a per-subject Dayfold override or reminder-aware refinement.
