# ADR 0067: Canonical Temporal Facts and Behavioral Triggers

## Status

**Accepted** 2026-09-01 (operator approved in-session after timestamp,
data-model, privacy/security, end-to-end system, and simplification reviews).
This changes the authored content contract, temporal semantics, CLI write gate,
API persistence shape, and the inputs to the on-device Now, notification,
timeline, and Calendar Check projections.

Implementation plan:
`docs/superpowers/plans/2026-09-01-canonical-temporal-content.md`.

This proposal composes ADRs 0006 (content contract), 0014 (on-device trigger
matching), 0035 (block payloads), 0043 (derived Now), 0044 (local
notifications), 0045/0046 (authored and derived timelines), and 0063
(client-owned Calendar Check). Where it conflicts with their current behavior,
the changes below remain inactive until the operator accepts this ADR.

If accepted, it narrowly supersedes ADR 0046's “most-specific stated time wins”
for canonical multi-fact content, refines ADR 0044 notification identity without
adding remote notifications, and refines ADR 0063 candidate/binding/write-back
identity plus Calendar-description import. ADR 0045 authored-timeline precedence
and ADR 0063 reviewed native writes remain unchanged.

## Context

The marching-band hub's Friday, August 28 item, **“The Big Night,”** was authored
with operational times in Markdown while the same authoring run independently
wrote some of those facts into `Hub.timeline`. The Markdown block itself had
`payload: null` and no `when` trigger. Two additional times in the prose were
not represented in the timeline either.

The failure was not that the author lacked the date information. The authoring
process held it, rendered it into prose, and then failed to carry it through a
typed representation. The current system allowed that because:

- Markdown has no item-local temporal carrier;
- the curator instructions permit prose-only blocks and do not require a
  temporal claim inventory before JSON is drafted;
- the CLI validates structure, not correspondence between prose and structured
  facts;
- `push` reports HTTP success but does not prove that expected semantic fields
  survived the write/read round trip;
- the API correctly stays content-blind and therefore cannot infer omitted facts
  from prose; and
- mobile consumers intentionally read typed fields only. They cannot use a date
  that exists only in Markdown.

The naive rule “any text containing a date needs a trigger” is also wrong. A
content item may contain several event times, a deadline, a historical date, an
email-sent timestamp, a visibility window, or an incidental reference. A
trigger changes product behavior; a date fact does not necessarily request Now
surfacing or a notification. Multiple dates also cannot safely be collapsed to
the first trigger or the “most specific” one.

## Proposed decision

### 1. Structured temporal coverage is an authoring invariant

Every **material temporal claim** in Dayfold-authored content MUST have a
canonical structured representation. Prose may repeat a structured fact for
readability, but prose must not be its only representation.

A material claim is one whose date or time is necessary to understand, plan,
attend, complete, compare, sequence, reconcile, or accurately render the item.
This includes operational events, deadlines, windows, and historical/reference
dates when the date itself is part of the item's meaning.

Dates that merely identify source metadata remain in their existing typed source
fields, such as an email's sent date or a file's modified date. Verbatim external
source excerpts are opaque evidence, not Dayfold-normalized claims: they stay in
typed excerpt/source fields and are never eligible for temporal behavior. If an
author summarizes or promotes a date from an excerpt into Dayfold prose, the
normal invariant applies. Calendar import currently turns an arbitrary external
description into a Markdown block; V1 must stop doing that until a reviewed
mapping flow exists. Audit timestamps such as `provenance.at`, and presentation
lifecycle fields such as `not_before`/`expires_at`, do not satisfy an event or
deadline claim.

### 2. Facts, behavior, presentation, lifecycle, and audit time are distinct

The content model recognizes five temporal semantics:

| Semantic | Examples | Canonical carrier | May drive behavior by itself? |
|---|---|---|---|
| Content fact | show, rehearsal, deadline, historical date | typed payload or `temporal` occurrence | No |
| Behavioral trigger | surface 30 minutes before show | `triggers[].when` | Yes, explicitly |
| Schedule presentation | hub timeline stop | `Hub.timeline` | No |
| Lifecycle | do not show before / expire after | `not_before`, `expires_at` | Gates visibility only |
| Audit/source metadata | authored at, email sent, file modified | provenance or typed source payload | No |

`when` remains behavioral. New content MUST NOT use `when.at` as a generic place
to store an event date. A temporal fact does not notify, wake, rank, or enter Now
unless an explicit trigger or an already-accepted projection says it does.

### 3. Preserve existing typed carriers; add an item-local multi-occurrence facet

Existing typed fields remain canonical for the claims they already model:

- timed `Hub.start_at` / `end_at` values;
- authored `Hub.timeline.stops` as schedule presentation, not as coverage for a
  Block/Card claim;
- checklist item `due`;
- milestone `payload.date` / `end` / `tz`;
- typed card payload dates such as invite start/RSVP and geo leave-by; and
- typed source-metadata dates such as email date and file modified.

Add an optional top-level `temporal` facet to **Block** and **BriefingCard** for
claims that do not fit those carriers, especially Markdown and content with
multiple dates:

```json
{
  "temporal": {
    "occurrences": [
      {
        "id": "01K45ABCDEF0123456789GHJKM",
        "role": "event",
        "label": "Warm-up call",
        "start": "2026-08-28T18:30:00-07:00",
        "zone": "America/Los_Angeles",
        "status": "confirmed"
      },
      {
        "id": "01K45ABCDEF0123456789GHJKN",
        "role": "event",
        "label": "Show",
        "start": "2026-08-28T21:00:00-07:00",
        "end": "2026-08-28T23:00:00-07:00",
        "zone": "America/Los_Angeles",
        "status": "confirmed"
      }
    ]
  }
}
```

V1 rules:

- `occurrences` contains 1–64 entries. Each occurrence label contains 1–256
  characters. Consumers sort by normalized time and stable ID when chronological
  order is required.
- The array is semantically unordered and keyed by `id`; wire order is not
  identity. `id` is an opaque, high-entropy client-minted ULID and is never
  derived from mutable prose. Writers preserve an ID while editing the same
  logical fact and mint a new one for a new fact. The API enforces uniqueness in
  the current parent value; clients cancel schedules and remove bindings when a
  fact disappears. V1 does not add a server-side lifetime ID registry.
- `role` is one of `event | deadline | window | reference`.
- `status` is one of `confirmed | tentative | cancelled`.
- A date-only `start` is a civil interval, not an instant. With no `end` it means
  `[start, start + 1 civil day)`; with a date-only `end`, that end is exclusive.
  Date-only facts carry no timezone and are never converted to midnight in the
  normalized model.
- A timed `start`/`end` uses the shared V1 profile: full seconds, no fractional
  seconds, explicit `Z` or numeric offset; offset-less local time, `-00:00`, leap
  seconds, and invalid civil values are rejected. Each timed occurrence requires
  its own IANA `zone` (`UTC` is valid). The stored RFC-3339 value is the
  authoritative instant; `zone` is civil/display context. The CLI validates
  offset/zone agreement against its authoring-time tzdb, including gaps/folds,
  but a later tzdb change never silently rebases the stored instant.
- A timed occurrence's zone token is at most 128 characters. The API validates
  bounded token syntax; the authoring CLI validates IANA membership and
  offset/zone agreement.
- `end`, when present, uses the same date-only/timed family as `start`, is
  exclusive, and is later by civil-date order or instant order. A timed range may
  cross a DST change and carry different endpoint offsets.
- `window` requires `end`; `deadline` forbids `end`; `event` and `reference` may
  carry an end.
- `tentative` represents one explicitly tentative value. Competing values for one
  fact are not modeled as two apparent events: unresolved conflicts block a V1
  push until a human resolves them.
- Relative phrases are resolved from the source's own reference instant and
  relevant IANA zone. The authoring clock is used only when the phrase explicitly
  refers to authoring time. The local ledger records the base; a missing or
  conflicting base blocks the push.
- Unbounded recurrence and material values coarser than a day are unsupported
  hard errors in V1. A finite series may be expanded only when the prose states
  the same finite horizon. Cross-item fact references are also deferred.

`reference` is the explicit answer for historical or incidental dates that are
part of the item's meaning but must never become a calendar candidate, timeline
stop, Now item, or notification merely because they are structured.

### 4. Triggers reference canonical facts instead of duplicating them

Add `when.fact_ref`, scoped to the same parent Block or BriefingCard. One
namespace covers the new facet and existing canonical carriers:

- `temporal:<occurrence-ulid>`;
- `payload:milestone`;
- `checklist:<item-ulid>:due`; and
- closed typed-card keys such as `payload:invite:start`,
  `payload:invite:rsvp`, `payload:link:closes`, and `payload:geo:leave`.

Reserved prefixes prevent authored IDs from colliding with typed carriers.

```json
{
  "when": {
    "fact_ref": "temporal:01K45ABCDEF0123456789GHJKN",
    "alert_offset": "-PT30M"
  }
}
```

For new content, a fact-reference trigger is a closed union: `fact_ref` plus an
optional `alert_offset`, and no `at`, `relative`, `recurring`, or `window`. The
reference must resolve to a **confirmed timed** fact on the same item. Date-only,
tentative, cancelled, and reference facts cannot schedule V1 behavior. The
trigger always anchors to fact start; end-relative behavior is deferred.

V1 allows at most one fact-reference trigger per parent item. Multiple facts are
fully supported, but multiple independent Now/notification schedules require
separate items or a later occurrence-scoped behavior ADR. This matches the
current calm-feed subject model instead of silently dropping later schedules.

`alert_offset` is an elapsed ISO-8601 duration with second precision and absolute
value at most 30 days. Calendar years/months/weeks and fractional units are not
accepted; days mean fixed 24-hour elapsed periods. Negative means before and
positive means after. Malformed or out-of-bound values are rejected, never treated
as zero.

Legacy `when.at` remains readable during migration. It is treated as behavior
metadata and a compatibility temporal source. Upgraded writers use `fact_ref`;
they do not duplicate a canonical timestamp into `when.at`.

For Calendar Check, legacy `when.at` is a fallback only when its parent has no
canonical Calendar-eligible fact. It is not normalized as a second content fact.
This is an explicit compatibility precedence rule, not timestamp/title-based
deduplication.

Fact-reference emission is gated on a supported-client floor or an equivalent
capability-preserving server path. An old app decodes unknown trigger fields and
can otherwise re-encode a partial/empty `when` object, erasing behavior. Until
that floor is active, the server must mechanically preserve an existing
fact-reference trigger on a write from a client that does not advertise
`temporal-v1`; new capable writers preserve raw unknown trigger members. The
capability is stamped on each queued mutation when it is created and sent from
that row, not inferred from the later binary draining the outbox. Pre-upgrade
whole-resource rows are rebuilt after resync by replaying their typed member
intent, or remain explicitly legacy.

### 5. Normalize once on-device; consumers select by declared semantics

Add one pure **per-resource** temporal normalizer in the shared client, plus small
collection indexes for callers that need them. It projects the new facet and
existing typed carriers into a sealed `AllDay` versus `Timed` fact model. Now,
notifications, derived timelines, and Calendar Check consume that projection
instead of independently choosing the first or “most specific” date.

Existing Hub `start_at`/`end_at` remain timed instants in V1 because their wire
schema and PostgreSQL columns are date-time/`timestamptz`. The Hub normalizer does
not manufacture an all-day value from them. A civil all-day item belongs in an
existing date-capable Block/Card carrier or the new facet; a future all-day Hub
extent requires a separate lossless wire/storage decision.

Consumer rules:

- **Now and local notifications:** only an explicit behavioral trigger can
  select a timed fact. The fact supplies event time; `alert_offset`
  supplies the effective behavior time. A device-local desired-schedule registry
  keyed by the derived entity-plus-fact local key reconciles additions, changes,
  and removals and cancels stale platform requests on content update/tombstone,
  disable, sign-out/family
  replacement, and rollback.
- **Calendar Check:** confirmed `event`, `deadline`, and `window` occurrences may
  become review candidates. `reference`, `tentative`, and `cancelled` do not.
  Timed matching/fingerprinting compares parsed instants, not lexical offset
  strings; all-day matching compares `LocalDate` intervals. Candidate identity
  includes physical entity plus fact reference, so one item can safely yield
  several events.
- **Derived timeline:** confirmed event/deadline/window occurrences may yield
  separate stops. Date-only/ranged items become done at exclusive end; a timed
  point becomes done after start; an active range remains active. This replaces
  ADR 0046's “most-specific stated time wins” rule only for accepted canonical
  multi-occurrence content; legacy content keeps its compatibility behavior.
- **Rendering:** the first slice adds no new user-facing temporal component.
  Existing prose remains the readable representation. Any later generic
  schedule UI or tentative/conflict UI requires ADR 0008 design sign-off.

The server does not run this projection.

The carrier capability matrix is normative:

| Carrier | Calendar Check | Derived timeline | `fact_ref` behavior |
|---|---:|---:|---:|
| Timed Hub start/end | existing yes | existing yes | no Hub triggers |
| Hub countdown | no | existing yes | no Hub triggers |
| Authored Hub timeline stop | no | rendered directly | no |
| Milestone date/end | existing yes | existing yes | confirmed timed only |
| Checklist due | no new V1 expansion | existing yes | confirmed timed only |
| Invite start/RSVP, link close, geo leave | no new V1 expansion | no | confirmed timed only |
| Email/file/source date | no | no | no |
| Legacy `when.at` | fallback only | existing location-pickup case | legacy only |
| `temporal` event/deadline/window | confirmed only | confirmed only | confirmed timed only |
| `temporal` reference | no | no | no |

Three persisted identities remain deliberately separate:

- `entityRef`: the physical card or hub/block that owns the field;
- `subjectRef`: the existing Now/calm-feed/response-rule semantic subject;
- `factRef`: the stable same-item fact key; its closed grammar also selects the
  exact write-back field.

Calendar binding and notification persistence derive one reversible, collision-
free local key from `(entityRef, factRef)`. That `localFactKey` is not another
wire/content identity and is not stored redundantly alongside both inputs.

No implementation may append a fact suffix to the existing `subjectRef` grammar.
Calendar “Use Calendar” dispatches through the exact `factRef`, not the first
trigger or first date on the item.

### 6. Prevention is layered around a temporal claim ledger

The curator must build a **Temporal Claim Ledger before drafting JSON**. Each
claim records a source reference (verbatim text only when necessary),
classification, normalized value, zone/base where relevant, certainty, chosen
structured carrier/path, and whether explicit behavior is requested. Prose and
JSON are rendered from that ledger, not authored as independent copies.

The authoring boundary then has four checks:

1. **Pre-process:** the agent/curator treats every email/calendar/file/note as
   untrusted data, never as instructions or authorization; it extracts and
   classifies claims before composing content. Unresolved conflicts block rather
   than becoming two apparent tentative events.
2. **Pre-push verification:** a local CLI verifier validates the ledger against
   resource JSON, validates temporal values and references, and emits a coverage
   table. Any uncovered date-like prose emits one review issue and blocks curator
   apply until it maps to a fact or receives a closed reviewed disposition.
   Malformed structured values and references are errors.
3. **Push gate:** normal `dayfold push` performs deterministic resource-local
   temporal validation and refuses structurally invalid or dangling references.
   Curator instructions require the stronger ledger verifier for authored prose.
4. **Post-push verification:** the CLI compares a canonical in-memory projection
   of the returned/pulled resource directly to the verified input and reports any
   field lost or changed. A
   successful HTTP status alone is not completion.

Date extraction is a local safety net, not the source of truth. It must not call
a second hosted model, send ledger/source text to the Dayfold API, or make the
server interpret content. A hosted curator remains inside its provider's existing
disclosure/retention boundary. Ledgers stay owner-readable, out of repo/CI/build
artifacts, and are deleted after a verified receipt unless the operator retains
one explicitly.

Proposal and push occur in separate turns. Confirmation shows every fact and any
requested behavior, including effective time, Now effect, and local-notification
effect. Approval of prose/facts never implies approval of a trigger. There is no
general validation bypass; reviewed dispositions use closed reason codes
and cannot bypass malformed values, dangling refs, ACL/version checks, or round-
trip comparison.

### 7. The API remains content-blind

The API stores `temporal` as JSONB on blocks and briefing cards and round-trips it
through list/tree/sync. Its update semantics are tri-state: omitted preserves the
stored value for old/field-level writers, an object replaces it, and explicit
`null` clears it. Upgraded full-replacement CLI bundles must always send object or
`null` with an `If-Match` base version; they may not accidentally preserve stale
facts. Old/partial writers therefore cannot erase an unknown facet.

Omit/preserve/replace/clear resolution, tombstone state, and `If-Match` are one
conditional repository operation or transaction. A route-level read followed by
an independent upsert is not sufficient. Same-item references are checked against
the effective value within that boundary.

The API validates only mechanical properties: allowed keys, enum values, bounds,
timestamp syntax, ordering constraints, unique IDs, and same-item fact-reference
resolution. Authoring-time zone/offset agreement remains a CLI responsibility so
different server/client tzdb releases cannot invalidate accepted instants. Request
bytes and strings are bounded before temporal parsing.

It does not parse Markdown, decide whether a date is material, infer a timezone,
resolve relative language, choose between conflicting sources, or decide which
facts deserve behavior. Those remain authorized authoring/client responsibilities.

### 8. Privacy and security posture

Temporal facts are family content. At M0 they have the same plaintext-at-rest
posture and visibility as their parent item. Proposed ADR 0015 currently keeps
dates/enums/routing metadata clear while encrypting content and triggers. Under
that posture, opaque occurrence ID, role/status, start/end, and zone remain
cleartext schedule metadata; `label` is encrypted like a title/body, and triggers
are encrypted. Inner server validation of encrypted labels/triggers disappears in
favor of CLI/client validation. A later E2EE change to that split requires an ADR;
this proposal does not silently place the whole facet inside or outside one
ciphertext blob.

- no raw body, extracted phrase, occurrence label, or temporal value enters
  analytics/error telemetry;
- expected parser failures return stable content-free 422 codes without throwing;
  unexpected API error reporting sanitizes messages/details before SWIP/Sentry,
  with sentinel leak tests over IDs, labels, values, zones, parent/family IDs, and
  database failures;
- CLI diagnostics print local paths/codes by default, not body excerpts;
- native calendar state remains on-device under ADR 0063;
- live location remains on-device under ADR 0014;
- bounds on occurrence count, label/id length, and parsing cost prevent an
  untrusted author from creating unbounded client/server work; and
- trigger references cannot escape their parent item or broaden access.

Temporal repair has two additional preconditions: card PUT preserves stored
visibility/audience on omission exactly as hub PUT already does, and repairs are
version-conditional. The post-write comparison proves ACLs unchanged. A rollback
never overwrites newer ACL/member edits with an old JSON snapshot.

## Consequences

### Positive

- The Big Night failure becomes representationally impossible in the curated
  path: each resolved breakfast, call, picnic, meeting, performance, and cleanup
  claim maps to its own item-local occurrence before prose is rendered. The
  conflicting 6:30/7:00 meeting sources block repair until reviewed.
- One item can carry several dates without abusing triggers or selecting only the
  first date.
- Historical/reference dates can be structured without accidentally notifying
  or polluting Calendar Check.
- Server content blindness is preserved; semantic work stays at the authoring
  edge and typed client consumers remain prose-blind.
- A shared per-resource normalized projection removes drifting date-selection
  rules from the mobile client without creating a global cross-parent index.

### Costs and risks

- `temporal` adds schema, generated types, two backend columns, client DTO/cache
  fields, and migration work.
- During compatibility there are several accepted carriers. Explicit identities
  and capability tables replace value-based duplicate guessing; double-authoring
  is rejected until an explicit alias model exists.
- Date/time correctness includes DST gaps/folds, all-day exclusive ends, timezone
  drift, and uncertainty. These need a larger test matrix than string-format
  validation.
- Local prose heuristics can produce false positives. The ledger is the strong
  guarantee; heuristics only catch omissions and require review rather than
  silently classifying meaning.
- Multiple Calendar Check candidates from one item change an existing projection
  and need snapshot/behavior verification even though no new screen is proposed.

### Explicitly rejected

- **Put every date in `when.at`.** It confuses facts with behavior and can create
  unwanted Now/notification effects.
- **Let the API parse prose.** It breaks the content-blind/E2EE boundary and still
  cannot reliably infer intent or conflict resolution.
- **Use a single `date` on Markdown.** It cannot model the common multi-event case.
- **Treat `Hub.timeline` as sufficient coverage for arbitrary block prose.** It
  recreates the incident's independent copies and cannot prove which item/claim a
  stop covers.
- **Make mobile parse Markdown.** It creates platform drift, false automation,
  privacy risk, and duplicate LLM/NLP behavior in the client.
- **Adopt recurrence in V1.** Correct recurrence requires series identity,
  exceptions, DST policy, edits, and bounded expansion; a partial version is more
  dangerous than explicit occurrences. Unbounded recurring prose is therefore a
  V1 hard error, not silently accepted unstructured content.

## Compatibility and rollout

1. Land authoring instructions and a read-only audit first.
2. Land additive schema/API persistence with preserve-on-omit semantics before
   any writer emits the field.
3. Land CLI temporal facet validation and verified round trips.
4. Land client DTO/cache support dark, then the shared normalizer.
5. Move Calendar Check/timeline consumers to normalized facts.
6. Add `fact_ref` trigger resolution, then stop emitting `when.at` for new
   curator content.
7. Audit and repair existing content only through the normal propose-confirm
   boundary. No migration may silently invent dates or behavior.

Old clients ignore the new JSON/columns but cannot safely re-emit them. The server
therefore preserves the facet on omission, and upgraded clients store raw JSON so
unknown future fields survive. When temporal consumers activate, the client
schema version forces a full resync because an older cache may have advanced its
cursor after discarding the field. Queued mutations carry the writer capability
from their creation time; pre-upgrade whole-resource rows are never upgraded by
mere process restart. Fact-reference triggers do not emit until the old-writer
trigger-loss gate above is closed. The system-wide invariant is not
claimed while an older Calendar-import client can still materialize arbitrary
descriptions. Legacy fields stay supported until local audits and compatibility
tests show they can be deprecated in a later ADR.

## Acceptance gates

Before this ADR can become Accepted:

1. Fresh-context timestamp/timezone review covers offsets, IANA zones, DST
   gaps/folds, all-day ranges, exclusive ends, and relative-time resolution.
2. Fresh-context data-model review covers fact identity, carrier capabilities,
   tri-state updates, partial-writer compatibility, Calendar/write-back identity,
   migration, and generated-type compatibility.
3. Fresh-context privacy/security review covers plaintext/E2EE placement,
   telemetry, authorization, denial-of-service bounds, and calendar/notification
   effects.
4. End-to-end system review covers agent files, CLI, schema/codegen, API/database,
   sync, mobile cache, all four temporal consumers, and rollback boundaries.
5. A second simplification/maintenance review removes avoidable machinery.
6. The operator accepts the semantic taxonomy, V1 facet, trigger-reference rule,
   and rollout order.
7. Any new UI beyond reuse of already-approved repeated Calendar Check/timeline
   rows completes ADR 0008 design/sign-off first.

## Revisit triggers

- Recurring schedules become common enough that bounded explicit expansion is
  no longer calm or maintainable.
- E2EE activation changes which temporal fields the server can structurally
  validate.
- Calendar providers require floating local-time or series semantics that V1
  cannot represent.
- Member-authored temporal editing is added to the mobile client.
- Audits show the generic facet duplicates existing typed carriers more often
  than it fills Markdown/multi-date gaps.
