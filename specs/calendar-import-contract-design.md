# Calendar → Dayfold Import — Proposal & Mutation Contract (Design)

**Date:** 2026-08-09
**Status:** **ADR 0063 gate 4 — satisfied pending operator ratification.** Spec
only; no code, no schema change, no ADR edit is made by this document. It
specifies the contract in enough detail that the implementation WI is
mechanical.
**Authority:** `adr/0063-client-owned-calendar-reconciliation.md` §6 (reviewed
import) + acceptance gate 4. **Rides:** ADR 0039 (typed-op mutation spine) +
`specs/two-way-engine-and-content-management-design.md`. **Bounded by:** ADR
0030 (per-member hub/card visibility), ADR 0053 (per-hub participation roles),
ADR 0015/0017 (content-blind server), ADR 0043 (shared subject keys), ADR 0044
(local notification posture).
**Approved UX this contract serves:** `designs/calendar-reconciliation/NOTES.md`
§23–27 + `designs/calendar-reconciliation/Import.dc.html`
(`destination` · `fields` · `audience-new` · `audience-existing` · `confirm` ·
the 7 `apply-*` states).
**Ratification:** ADR 0063 is **Proposed** (INB-36 open). Nothing here takes
effect before the operator accepts ADR 0063 and the §7 decisions below.

---

## 0. Scope, and what "mechanical" means here

The Calendar → Dayfold direction turns **one member-reviewed calendar event**
into **normalized Dayfold content**, written through the *existing*
authenticated content spine. This document fixes:

1. the **proposal type** (§1) — the only thing that can exist between the
   device calendar and a Dayfold write;
2. the **destinations + audience rules** (§2);
3. the **apply semantics** (§3) — which existing ops compose, how idempotency
   and preconditions work, and the exact client behavior for all 7 approved
   apply states;
4. the **non-goals** (§4) that must be provable, not merely intended;
5. the **reconciliation** with ADR 0030 / 0039 / 0053, rule by rule (§5).

The load-bearing constraint that shapes every decision below: **the server is
content-blind and calendar-blind.** It never learns that a piece of content
came from a specific calendar event; it relays opaque, member-authored content
exactly as it relays any other member write.

**Design invariant — no new spine.** The import introduces **no new op type,
no new endpoint, and no new authorization concept.** It composes
`upsertHub` / `upsertSection` / `upsertBlock` on the existing outbox envelope
and the existing routes. Everything genuinely new is device-local.

---

## 1. The proposal type

### 1.1 Shape

A **`CalendarImportProposal`** is a device-local, `:client` commonMain value
type. It is the *only* representation that crosses from the calendar adapter
into the import flow, and its field set is the whole normalization boundary.

| Field | Type | Notes |
|---|---|---|
| `proposalId` | ULID | Minted from **device randomness** at review start. Idempotency root (§3.3). |
| `title` | `String` | Normalized (trimmed, whitespace-collapsed). Member-editable before apply. |
| `start` | `EventInstant` | `Timed(instantRfc3339WithOffset)` **or** `AllDay(localDateYYYYMMDD)`. |
| `end` | `EventInstant?` | Same union; null when the source has no end. |
| `timezone` | `String?` | IANA zone id (e.g. `America/Los_Angeles`), author-stamped. |
| `location` | `StructuredLocation?` | `{ label: String, address: String? }` — structured only. |
| `description` | `String?` | **Opt-in only**; null unless the member ticks "Add anyway" (§7 OD-1). |
| `destination` | `Destination` | `NewHub(audience)` \| `ExistingHub(hubId)` — §2. |

`EventInstant` being a **closed union** — not a nullable `allDay: Boolean`
beside a timestamp — is deliberate: it makes "all-day date" and "instant"
non-confusable at the type level, and it is the existing repo convention
(`TimelineStop.at` already accepts "an RFC-3339 instant, or a bare
`YYYY-MM-DD` for an all-day stop").

### 1.2 Fields that are **not representable**

The following are **absent from the type**, not filtered downstream. There is
no field to hold them, so no code path — present or future — can carry them
into a proposal, a preview, an op, a log line, or a bug report:

- attendees / guests and their responses;
- meeting / conferencing links;
- organizer identity;
- calendar **account** identifiers, account names, and provider identifiers;
- reminder / alert configuration;
- the platform calendar id, platform event id, recurrence id, and the
  reconciler's deterministic **fingerprint**.

> **Why omission, not filtering.** A filter is a runtime behavior that a later
> refactor can bypass; an absent field is a compile error. ADR 0063 gate 6
> requires *tests proving* these cannot reach sync/logs/analytics/bug reports —
> a type that cannot express them makes that proof structural instead of
> behavioral. The build WI's test for §4 asserts the type's field set, not a
> redaction function.

The **matching state** the reconciler needs (platform event id, fingerprint,
last-seen, relation, notification owner) lives where ADR 0063 §3 already put
it — the device-local `calendar_binding` projection — and is joined to a
proposal only by `proposalId`, never embedded in it.

### 1.3 Provenance

The applied content carries `provenance = { source: "calendar", at: <apply
time> }`.

- `source` is the **fixed literal `"calendar"`**. The calendar's *display
  name* ("Personal", "Work", …) is **not** sent; the "From your calendar ·
  Personal" chip in `Import.dc.html` is rendered on the importing device from
  the local binding, pre-write.
- `at` is the **apply timestamp from the device clock** — never the calendar
  event's created/updated time, which is a weak per-event correlator.
- `credential_id` is server-stamped (`stampProvenance`), as for every other
  write. Nothing calendar-derived rides it.

**Rationale for the tightening** (ADR 0063 §6 permits "Calendar provenance",
and the WI's wording bounds provenance at "calendar display name only" — this
sits inside that bound): a calendar's display name is member-chosen free text
and can itself be the sensitive fact ("Therapy", "Job search"). On a
family-visible destination it would be published to every member. ADR 0063 §3
already forbids copying calendar **account names** into Dayfold storage; a
display name is the same class of leak with a friendlier label. See §7 OD-2 —
operator-ratifiable.

### 1.4 Materialization

One proposal materializes into exactly one **section** plus **1–3 blocks**,
identically for both destinations:

| # | Block | Condition | Content |
|---|---|---|---|
| 1 | `milestone` | always | `payload = { date: <start>, label: <title>, end?: <end>, tz?: <timezone> }`; `triggers = [{ when: { at: <start> } }]` when `start` is `Timed` |
| 2 | `location` | `location != null` | `payload = { label, address? }` |
| 3 | `markdown` | `description != null` | `body_md = <description>` |

`milestone` is the existing "a dated thing inside a Hub" primitive
(`MilestonePayload = { date, label }`, both required). `end` and `tz` are
**additive optional** fields on that payload (§7 OD-4) — permitted by ADR 0039
§5 Rule 1 (additive-only) and classified per Rule 6 as **content
(ciphertext-at-M1)**, like every other member-authored field. Without them the
existing-Hub destination could not carry the reviewed end time or zone id, and
the field-preview screen would have to show a *different* field list per
destination — a divergence the approved design does not have.

The `when.at` trigger is what makes the imported block a legitimate
`DayfoldEventCandidate` under ADR 0063 §2 (an explicit typed field, never
parsed prose). §3.6 handles the self-match consequence.

The section is titled **"From your calendar"** and carries no other marker.

---

## 2. Destinations, audience, and authority

### 2.1 (a) New Event Hub — restricted to the importer by default

- The import **creates** the Hub; the importing member becomes
  `hubs.created_by` (server-stamped from the JWT) and is therefore the
  permanent implicit **Co-owner** (ADR 0053 item 3). No orphan-manager state
  is reachable.
- Default `visibility = "restricted"`, `audience = [importerUserId]` — the
  approved "Only me · Just you can see this Hub. You can share it later."
- **Family / named-audience is an explicit choice on the `audience-new`
  step**, never a default and never inferred from the source calendar's
  sharing. Choosing it sets either `visibility: "family"` or
  `visibility: "restricted"` with an explicit `audience[]` of family
  `userId`s the member picked by name. Both ride the same single hub op — no
  extra surface, no post-hoc round trip.
- `audience[]` may only contain `userId`s from the family member list the
  device already holds. The server reconciles the allow-list inside
  `upsertHub`'s transaction without resetting survivors' roles.
- Hub `type` = the bounded template-catalog key for an event (§7 OD-3).
- `start_at` / `end_at` are set on the Hub row from the proposal (so Hub-grain
  date surfaces work); they duplicate the milestone block by design — Hub
  dates are the Hub's own typed fields, not a second event.

### 2.2 (b) Existing Hub — role-gated, visibility-inherited

- Only Hubs where the member's ADR 0053 role ∈ **{`contributor`, `co_owner`}**
  or where the member is the **author** may be offered as destinations. The
  destination picker filters to those Hubs client-side (`Import.dc.html`
  `destination` shows the CONTRIBUTOR / CO-OWNER badge per row); the server
  independently enforces the same rule (§3.2), so a stale client list cannot
  become a write.
- The import **never writes the Hub row** and never sends `visibility` or
  `audience` on this path. The added blocks inherit the destination Hub's
  visibility through the ADR 0030 read filter (blocks carry no `audience[]`;
  they are scoped by their Hub).
- **The confirmation must state the audience person-by-person before the
  write** (`audience-existing`, NOTES §25): a named list of every member who
  will be able to read the imported content, resolved from the Hub's live
  audience — not a count, not "shared with the family". Where that audience is
  wider than the source calendar implied, the screen warns before widening.
- The audience shown is a **precondition**, not decoration: §3.5's
  `version-conflict` path exists precisely so a member never applies against a
  named list that has since changed.

### 2.3 What the import may never do to authority

The import **never** grants, changes, or infers a role; never flips an
existing Hub's `visibility`; never adds a member to a Hub's allow-list except
as the `audience[]` of a Hub it is itself creating. Widening an existing Hub
is an ADR 0053 management action on its own surface, reached from the Hub, not
from an import.

---

## 3. Apply semantics over the ADR 0039 spine

### 3.1 Op composition — existing ops only

Enqueued in this order (the outbox drains FIFO by `created_at`, so the order
of enqueue *is* the causal order):

| Step | `target_kind` | `type` | Route | When |
|---|---|---|---|---|
| 1 | `hub` | `upsertHub` | `PUT /families/{fid}/hubs/{hubId}` | new-Hub destination only |
| 2 | `section` | `upsertSection` | `PUT /families/{fid}/sections/{sectionId}` | new-Hub always; existing-Hub only when the Hub has no live section (§7 OD-6) |
| 3..n | `block` | `upsertBlock` | `PUT /families/{fid}/blocks/{blockId}` | one per §1.4 block |

The outbox envelope already reserves `type` + `target_kind` + `depends_on`
(ADR 0039 §8, `Content.sq`). Today's sender dispatches on `targetKind ==
"response"` then falls through to the block path; wiring `hub` and `section`
arms is a **two-branch addition to the existing `when`**, not a new spine.

**`depends_on`** links 2→1 and 3..n→2 (or →1 when step 2 is skipped). The only
behavior the sender must implement for this chain is **cascade-drop**: when an
op reaches `Drop` or `Failed`, its dependents are dropped too. Without it a
403 on the hub op would leave the section op to 409 and a member could be left
with a half-written import. FIFO already supplies the *ordering*; no scheduler
and no DAG solver is needed.

### 3.2 Authorization is server-enforced, and already implemented

The **section and block routes** run `hubWriteGate(fid, hubId, caller)`
(`apps/api/src/content/write-guard.ts`) against the owning Hub, which yields:

| Gate | HTTP | Meaning for the import |
|---|---|---|
| `absent` | 409 | destination Hub deleted → destination gone |
| `invisible` | 404 | restricted Hub the member can no longer see (no existence oracle) |
| `denied` | 403 | credential scope **or** ADR 0053 role denies the write |
| `ok` | 200 | visible + scoped + role-permitted |

A member app credential already holds global `content:read/write/delete`
(`auth/identity.ts`), so for the import the **effective** gate is the ADR 0053
role check — exactly the intent. **No new scope, no new authz code.** The
client-side destination filter (§2.2) is a UX affordance; this gate is the
boundary.

`PUT /hubs/:id` deliberately does **not** run `hubWriteGate` — it runs
`requireScope(cred, hub:<id>, "write")` plus its own visibility / author /
`canManageHub` checks, because on a **create** there is no prior Hub to gate
against. That asymmetry is safe here for two reasons, both of which the build
WI must preserve:

- the import only ever *creates* on this route (a brand-new client-minted
  `hubId`), never rewrites an existing Hub, so the create-time defaults apply
  and nothing can be declassified; and
- `created_by` is stamped from the caller's JWT (`COALESCE`d, so set-once),
  making the importer the author → implicit Co-owner, with the allow-list
  reconciled inside the same transaction.

The existing-Hub destination never touches this route at all (§2.2).

### 3.3 Idempotency — client-minted ids rooted at `proposalId`

- `proposalId` is minted from **device randomness** at review start.
- At **confirm**, all target ids (`hubId`, `sectionId`, `blockId`s) are minted
  **once** and persisted in the local proposal record. Every retry, re-flush,
  process restart, and post-re-review re-apply reuses the **same** ids.
- All three routes are **upserts keyed on client-minted ids**, so a replay
  converges to the reviewed content rather than duplicating it. This is the
  primary idempotency mechanism and it requires **no server change** (note
  that `PUT /hubs/:id` and `PUT /sections/:id` do not honor `Idempotency-Key`
  today; `PUT /blocks/:id` does, via `op_log`).
- Each op additionally sends `Idempotency-Key: <op_id>`; the block route
  short-circuits an exact retry through `findOp`/`recordOp`. The hub and
  section routes ignore the header — harmless, and covered by the id-based
  convergence above.
- **Ids are never derived from calendar data.** A ULID hashed from a platform
  event id would be a stable, server-visible correlator for that event — a
  fingerprint on the wire, banned by §4. Device randomness only.
- Discarding a proposal drops its ids; a later fresh import mints new ones.

This is what makes the approved offline copy ("No duplicate will be created")
true rather than aspirational.

### 3.4 Preconditions — where each one actually lives

| Precondition | Enforced | How |
|---|---|---|
| Member may write the destination Hub | **Server** | `hubWriteGate` → 403/404 (§3.2) |
| Target block not tombstoned | **Server** | 410-on-tombstone (member write) |
| Row-version optimistic concurrency | **Server** | `If-Match` → 412 |
| Destination Hub unchanged since the audience was shown | **Client** | §3.5 `version-conflict` |
| Source calendar event unchanged since review | **Client** | §3.5 `source-changed` |

**`base_version` is `null` on every import op.** All three ops target
client-minted, not-yet-existing rows, so there is no prior version to
precondition on and no 412 is reachable on the happy path. Critically, a
non-null `base_version` is sent by the sender as `If-Match` **against the op's
own target row** — so the *destination Hub's* version cannot ride there (a
block create with `If-Match: 7` would 412 forever against a null live
version). The Hub-grain precondition is therefore recorded in the **local
proposal record**, not in `outbox.base_version`.

**Honest limit.** The destination-Hub precondition is a client-side
re-read-then-enqueue, so a Co-owner widening the audience in the millisecond
between the re-read and the write is not excluded. The residual is **bounded
and is disclosure-scope drift, never privilege escalation**: the security-
relevant facts (may this member write here at all; may this content out-expose
its Hub) are server-enforced above and are re-evaluated at write time. §7 OD-5
records the server-side hardening (an explicit Hub-grain precondition header)
as ratifiable, not assumed.

### 3.5 The 7 apply states

`confirm` mints ids and persists the local proposal record. **Ops are enqueued
only after revalidation passes** — this is what makes "if the event changes
first, you'll get one more look" achievable, since a plain outbox flush cannot
re-review. Revalidation = re-read the source event **and** the destination
Hub, then compare against the snapshot taken at review.

| # | State | Trigger | Contract |
|---|---|---|---|
| 1 | **saving** | ops enqueued, drain in flight | Optimistic local rows written with `local_state='pending'` (ADR 0039 §9); the UI reads the content tables, never the outbox. Copy: "Your calendar event is untouched." |
| 2 | **saved** | every op in the chain `Acked` | Write the `calendar_binding` row **atomically with the ack** (§3.6). Success copy states the *actual* resulting audience ("Hub created — only you can see it"). |
| 3 | **offline-queued** | no connectivity at confirm, or the sender is in `Backoff` before the chain starts | The proposal record persists; **no ops are enqueued yet**. On regaining connectivity: revalidate → enqueue (→ state 1) or re-review (→ state 6 / 7). Ids already minted, so no duplicate is possible. |
| 4 | **permission-lost** | calendar access revoked/restricted before the chain is enqueued | **Hold. Never guess.** The source cannot be revalidated, so nothing may be applied from an unverifiable copy. The proposal record is retained; zero ops exist; nothing was written anywhere. Actions: open OS settings, or discard. |
| 5 | **role-denied** | revalidation finds the member's role on the destination ∉ {contributor, co_owner, author}; **or** a drained op returns 403/404/409 | Cascade-drop the remaining chain (§3.1). **Keep the review** — fields, opt-ins and ids survive; only `destination` is cleared. Re-open destination choice ("Choose a different Hub" / "New private Hub"). Never silently retarget. |
| 6 | **source-changed** | revalidation finds the source event's fingerprint differs from the review-start snapshot | Re-read the event, show the field diff, require **explicit re-confirm**. **Never apply from a stale copy.** Ids are retained, so the re-confirmed content overwrites rather than duplicating. |
| 7 | **version-conflict** | revalidation finds the destination Hub's `version` **or** resolved audience set differs from the confirm-time snapshot | Refresh and re-present the audience person-by-person; require re-confirm against the current version. **Never silent-merge.** Ids retained. |

States 5–7 all end in *the member deciding again*. None of them mutates
Dayfold content, and none of them re-reads or re-writes the calendar.

Server responses map onto the states via the existing `OutboxSender.classify`:
`Acked` → 2; `Backoff` → 3 (or stays in 1 while retries are cheap);
`Drop` on 403/404/409 → 5; `Failed` at the attempt cap → surfaced as 3's calm
"will retry" affordance. `ReMerge` (412) is unreachable per §3.4.

### 3.6 Binding on success — closing the self-match loop

Because the imported milestone block carries a `when.at` trigger, it is itself
a `DayfoldEventCandidate`. Without a binding it would surface on the very next
reconcile as a **Dayfold-only gap** — the member would be invited to add to
their calendar the event they just imported *from* it.

Therefore, on the **`Acked`** of the terminal block op (server-confirmed, not
merely optimistic), the client writes the ADR 0063 §3 `calendar_binding` row
in the same transaction as the ack:

- Dayfold `subjectKey` = the imported block's subject ref
  (`buildBlockSubjectRef(hubId, sectionId, blockId)` — the ADR 0043 shared
  key, not a title match);
- platform event identifier + fingerprint = the source event's, **device-local
  only**;
- `relation = matched`;
- `notification_owner = calendar` (ADR 0063 §7 — Calendar owns the generic
  start alert for a confirmed match; a distinct Dayfold action nudge is
  unaffected).

If the chain never acks, no binding is written and the gap simply persists —
the conservative direction.

---

## 4. Explicit non-goals

These are contract terms, each with a stated proof obligation for the build
WI (ADR 0063 gate 6):

1. **The server never receives calendar or event identifiers, or
   fingerprints.** *Proof:* the proposal type cannot express them (§1.2), and
   the three op bodies are fully enumerated in §1.4 / §2.1. Test: assert the
   serialized body of every import op against an exact allowed key set.
2. **No server-side dedupe.** Duplicate prevention is entirely the
   client-minted-id convergence of §3.3. The server has no notion that two
   Hubs describe the same calendar event, and must not acquire one.
3. **No cloud-routine auto-apply.** A proposal is applied only by an explicit
   member confirmation on-device. No routine, cron, or agent may enqueue an
   import op. (This is also why states 3–7 all return control to the member.)
4. **No unrestricted-prompt path** (ADR 0063 §6, final paragraph). The import
   is a fixed, typed field mapping. There is no free-text instruction surface,
   no LLM in the path, and nothing here rides ADR 0039's Channel B (intents).
5. **No calendar writes.** This direction never creates, edits, deletes, or
   RSVPs an event; Calendar remains the canonical schedule.
6. **No recurring-series import.** ADR 0063 §4 defers series semantics; a
   proposal describes a single occurrence, and the UI says so rather than
   flattening a series.
7. **Calendar-derived state never enters the outbox.** The outbox is an egress
   lane; the proposal record (which holds the platform event id and
   fingerprint) lives in the device-local calendar-binding store. The two are
   joined by `proposalId` only.

---

## 5. Reconciliation with ADR 0030 / 0039 / 0053 (gate 4)

| # | This contract's rule | ADR 0030 | ADR 0039 | ADR 0053 |
|---|---|---|---|---|
| R1 | New imported Hub defaults `visibility='restricted'`, `audience=[importer]` | item 1 (two states), item 5 (authoring sets visibility) | §2 delta channel — one opaque content write | — |
| R2 | Family / named audience is an explicit, person-by-person choice; never inferred | item 1, item 6 (who may set/relax restriction) | — | item 5 (only author/co-owner may set audience — the importer *is* the author here) |
| R3 | Importer becomes `hubs.created_by` → implicit permanent Co-owner | item 2a (resolved author user id) | §6 author columns | item 3 (author = permanent implicit Co-owner; no orphan state) |
| R4 | Existing-Hub import requires role ∈ {contributor, co_owner} or authorship | item 6 | §6 visibility-on-write on **every** mutation | items 2, 4 (role is the source, `requireScope` is the gate) |
| R5 | Existing-Hub blocks inherit the Hub's visibility; the import sends no `audience` | item 3 (blocks/cards are scoped by their Hub; no read-time deref) | §7 dumb-server invariant | item 8 (roles are hubs-only; cards unchanged) |
| R6 | Confirmation names the audience person-by-person before the write | item 4 (omit-don't-403 read filter — the member is shown who *can* read) | — | item 5 (management actions are explicit and attributed) |
| R7 | Import never flips visibility or edits an allow-list on an existing Hub | item 6 | — | item 5 (`canManageHub` gate; management ≠ authoring) |
| R8 | Composes `upsertHub`/`upsertSection`/`upsertBlock`; no new op type or endpoint | — | §1 typed-op envelope, §2 one write surface, §8 reserve-shape/build-slice | item 4 (Contributor writes through the two-way engine) |
| R9 | Idempotency via client-minted ids rooted at `proposalId` (+ `Idempotency-Key` where honored) | — | §3 `op_log` for every op type; §2.3 `op_id` is the idempotency spine | — |
| R10 | `base_version = null`; Hub-grain precondition is client-side and recorded locally | — | §2.1 `base_version: null = create`; §6 410-on-tombstone / §2.3 per-op results | item 5 (a role *decrease* is a revocation event → state 5) |
| R11 | `depends_on` used for hub→section→block, with cascade-drop on parent failure | — | §2.1 `depends_on`, §2.3 "a failed create fails its dependents" | — |
| R12 | Optimistic rows carry `local_state`; the UI never reads the outbox | — | §9 (egress-only lane; `local_state` on content tables) | — |
| R13 | Server sees only cleartext envelope columns + an opaque member-authored payload | item 4 (server-side read filter is the only server logic) | §7 dumb-server invariant (per op) | — |
| R14 | `end` + `tz` added to `MilestonePayload` as optional, classified as content | — | §5 Rule 1 (additive-only), Rule 6 (`x-e2e` mandatory per field) | — |
| R15 | Role loss between review and write → state 5, review retained, destination cleared | item 2 (revocation re-surfaces via `updated_at`/tombstone) | §2.3 per-op results drive partial failure | item 5 (downgrade = write revocation, not read) |

**Gate-4 assertion:** every authorization, visibility, and audience rule this
contract states is either (a) already enforced by shipped code
(`hubWriteGate`, `upsertHub`'s allow-list reconciliation, the ADR 0030 read
filter), or (b) a client-side member-consent affordance explicitly labeled as
such in §3.4. This contract adds **no new authority** and **relaxes none**.

---

## 6. What the implementation WI has to build

Purely mechanical, in dependency order:

1. `CalendarImportProposal` + `EventInstant` + `StructuredLocation` types in
   `:client` commonMain (§1.1), with a test asserting the field set (§4.1).
2. A pure `materialize(proposal, destination, ids) -> List<Op>` function
   (§1.4, §3.1) — pure, so it is unit-testable headlessly.
3. Local proposal record + revalidation snapshot in the device-local
   calendar-binding store (§3.5) — never in `outbox`.
4. Sender dispatch arms for `target_kind ∈ {hub, section}` (§3.1).
5. `depends_on` cascade-drop in the sender (§3.1) + its test matrix.
6. `MilestonePayload.end` / `.tz` (additive, `x-e2e: ciphertext-at-M1`);
   regenerate TS + Kotlin (§7 OD-4).
7. The bounded-catalog hub `type` key (§7 OD-3).
8. Import UI per `Import.dc.html`, snapshot scenes for the 7 apply states.
9. Binding-on-ack (§3.6) + its regression test (an applied import must not
   re-surface as a Dayfold-only gap).
10. The §4 proof tests (op-body key set; no calendar field in sync/logs/
    analytics/bug-report/crash payloads).

---

## 7. Open / deferred decisions (defaults stated; operator-ratifiable)

Each has a working default so the build WI is unblocked; each flips with a
one-line change.

- **OD-1 — Event description (mockup open question 3).** **Default: the field
  is representable, opt-in, and off.** The `fields` screen shows the "Add
  anyway" control unticked; an untouched import carries no description.
  *Rationale:* it is the only unbounded free-prose field and the largest
  privacy surface, so it must never be a default; but the approved design
  already renders the control, and adding the field later is a type + schema +
  UI change rather than a flag flip. *Alternative:* omit description from v1
  entirely (drop the field and the control).
- **OD-2 — Provenance granularity (§1.3).** **Default: `source: "calendar"`
  constant; the calendar display name stays device-local.** *Alternative:*
  send the display name (needs an additive `Provenance` field —
  `additionalProperties: false` today — and accepts publishing a member-chosen
  label to the Hub's audience).
- **OD-3 — Hub template-catalog key for an imported event (§2.1).**
  **Default: a new bounded key `event`.** The catalog is app-validated and
  ADR 0004/0006-owned, so an addition is the operator's call. *Fallback if not
  ratified:* reuse `party-event` (semantically wrong for most events, hence
  not the default).
- **OD-4 — `MilestonePayload.end` / `.tz` (§1.4).** **Default: add both,
  optional, classified content (`x-e2e: ciphertext-at-M1`).** *Alternative:*
  no schema change, and the existing-Hub destination carries **start only** —
  which forces a per-destination field list on the `fields` screen and a
  weaker honesty story.
- **OD-5 — Destination-Hub precondition (§3.4).** **Default: client-side
  re-read immediately before enqueue.** *Ratifiable hardening:* a Hub-grain
  precondition header on the section/block routes (`If-Match-Hub: <version>`
  → 412), which closes the residual race but adds a public API contract
  surface and is therefore ADR-adjacent.
- **OD-6 — Section reuse on the existing-Hub path (§3.1).** **Default: append
  into the destination Hub's last live section by `ord`; create a "From your
  calendar" section only when the Hub has none.** *Rationale:* a
  create-always rule stacks a new identically-titled section per import.
  *Alternative:* always create (simpler, noisier).
- **OD-7 — Bounded comparison horizon, eligible candidate types, and the
  Calendar-owned start-alert default.** Not decided here — these are ADR 0063
  **gate 3** and belong to the operator (NOTES.md open question 1). This
  contract is horizon-agnostic.

---

## 8. Self-review passes

### Pass 1 — adversarial correctness (does any path violate 0030 / 0039 / 0053, or leak calendar identifiers?)

Findings raised and resolved into the text above:

1. **Deterministic ids derived from the calendar event would be a
   fingerprint.** A ULID hashed from a platform event id is a stable,
   server-visible correlator — it would satisfy idempotency while violating
   §4.1. **Resolved:** ids are device-random, rooted at `proposalId` (§3.3),
   and idempotency comes from *reusing* them, not from *deriving* them.
2. **Calendar display name in provenance is a real leak on a family-visible
   destination.** ADR 0063 §3 already bars account names; display names are
   member-chosen free text of the same sensitivity class. **Resolved:** §1.3
   + OD-2.
3. **The Hub-grain version precondition cannot ride `outbox.base_version`.**
   The sender emits it as `If-Match` against the op's *own* target, so a block
   create carrying a Hub version would 412 forever. **Resolved:** §3.4 —
   `base_version = null`, precondition recorded in the local proposal record;
   the residual client-side race is stated rather than papered over.
4. **A naive offline path cannot honor "you'll get one more look."** Enqueuing
   ops at confirm makes the outbox flush them unreviewed on reconnect.
   **Resolved:** §3.5 — ids at confirm, **ops at revalidation**.
5. **Self-match loop.** The imported block carries a `when.at` trigger, so
   without a binding the just-imported event re-surfaces as a Dayfold-only
   gap. **Resolved:** §3.6 — bind on ack, keyed on the ADR 0043 `subjectKey`.
6. **Partial writes on a failed chain.** A 403 on the hub op leaves a section
   op to 409 and a member with half an import. **Resolved:** §3.1 cascade-drop
   on `depends_on`.
7. **Permission-lost must not be treated as "source unchanged".** Losing
   calendar access makes the source unverifiable, not verified. **Resolved:**
   state 4 holds and applies nothing.
8. **Blocks carry no `audience[]`** (ADR 0030 item 3 scopes them through their
   Hub), so the existing-Hub path had no place to widen exposure even if it
   tried — confirmed against the shipped block route, and recorded as R5.
9. **ADR 0053 role enforcement is not new work.** `hubWriteGate` already
   resolves `resource_visibility.role` for non-author members on both the
   section and block routes; the import inherits it. Recorded as R4/§3.2 so
   the build WI does not re-implement it.

### Pass 2 — simplification (can any new concept be replaced by an existing spine op?)

Simplifications applied:

1. **Dropped a proposed `importEvent` op type.** It would have been a new op
   for a write the spine already expresses. Replaced by
   `upsertHub`/`upsertSection`/`upsertBlock` on the existing envelope; the
   only sender change is two `when` arms (§3.1).
2. **Dropped a server-side idempotency addition.** An earlier draft wanted
   `Idempotency-Key` support on the hub and section routes. Client-minted ids
   over upsert routes already converge, so the server is unchanged (§3.3).
3. **Dropped the per-destination materialization split.** An earlier draft put
   the event in `Hub.timeline` for a new Hub and in a `milestone` block for an
   existing one — two materializers, two field lists, two test matrices, and a
   whole-hub read-modify-write PUT (which has neither `If-Match` nor
   `Idempotency-Key`) on the existing-Hub path. Replaced by **one**
   materializer for both destinations (§1.4), at the cost of two additive
   optional payload fields (OD-4).
4. **Dropped the `Hub.timeline` write entirely** — with `tz` on the milestone
   payload it carried nothing the milestone block did not.
5. **Dropped a separate post-hoc "share with family" management round trip.**
   `PUT /hubs/:id` already accepts `visibility` + `audience[]`, so the
   audience choice rides the single hub op the import was making anyway
   (§2.1).
6. **Dropped an `allDay: Boolean` flag beside a nullable timestamp** in favor
   of the `EventInstant` closed union, matching the existing `TimelineStop.at`
   convention (§1.1).
7. **Dropped a general `depends_on` DAG scheduler.** The outbox already drains
   FIFO by `created_at`, so enqueue order supplies causality; only
   cascade-drop is genuinely required (§3.1).
8. **Dropped a "calendar import" marker column on sections.** Reusing the last
   live section (OD-6) achieves the same grouping with no schema surface.

Neither pass found a rule in this contract that widens authority beyond ADR
0030 / 0039 / 0053, nor a path on which a calendar identifier, fingerprint,
account name, attendee, organizer, meeting link, or reminder setting can reach
the server, sync, logs, analytics, or a bug report.
