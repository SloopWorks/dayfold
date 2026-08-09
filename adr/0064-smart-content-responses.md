# ADR 0064: Smart-Content Responses — Synced Mute Rules and Family Done State on a Content-Blind subjectRef

## Status

**Accepted** 2026-08-08 (operator-accepted in-session, INB-37). Agent-drafted
from the operator-approved design set. This adds a new **synced** preference row,
family-wide completion state, and a server-side suppression path on the content
write boundary — all three sit inside the ADR-class list (customer-data handling,
automation-autonomy boundaries), and acceptance covers all three, including the
three ratification points raised in INB-37: personal mutes strip `audience[]`
rather than rejecting, done tombstones family-wide, and `label`/`sublabel`/`note`
are plaintext at M0 under the block-payload posture.

Design sign-off on `designs/content-feedback/` (2026-08-08) cleared the ADR 0008
*design* gate only. It did not authorize build.

Extends ADRs 0006 (Hubs stay curated dossiers), 0024 (device-local never-sync
state), 0029 (content scope gate), 0030 (flat author-stamped card `audience[]`),
0033 (forward-only migrations), 0038/0039 (typed member-mutation spine, the
outbox, and the deliberate refusal to sync hide-state), 0040 (one keyset +
tombstone cursor), 0043 (the `subjectRef` shared subject key and the on-device
priority engine), 0044 (local-notification posture), 0053 (per-hub roles), and
0055/0056 (debug-only analytics and scrubbed logging). It is compatible with
Proposed ADRs 0061 (routine gateway) and 0062 (run receipts) but does not depend
on either: the enforcement points it defines are the content write API and the
on-device engine, both of which exist today.

Design gate: `designs/content-feedback/Index.dc.html` +
`designs/content-feedback/NOTES.md`.
Implementation plan:
`docs/superpowers/plans/2026-08-08-smart-content-responses.md`.

## Context

Smart content reaches a family from three machines: the ADR 0043 derived lane
(an on-device projection over hub dates and triggers), the curator authoring
through the CLI, and — once ADR 0061 lands — scheduled provider routines.

Against all three, a member has exactly one recourse today: **Hide** (ADR 0038
§W5). Hide is deliberately local-only, and ADR 0039 gives the reason: syncing
hide-state would publish who-saw-what, a behavioral leak Dayfold declined to
ship. That decision was right and this ADR does not disturb it.

But hide cannot stop a pipeline. It removes a card from one member's view on one
device; the next run mints the same card again, because nothing in the authoring
path knows the card was unwanted. The user hides, the machine re-adds, the user
hides again. Whack-a-mole is not a calm surface, and the constitution's calm
guarantee is the product.

The missing verb is not a better dismissal. It is a **preference**: not "remove
this card" but "stop making these." A preference has to outlive the card, reach
the authoring path, and survive a device wipe — which means it has to sync, which
means it has to answer the leak question that hide-state failed.

A second, adjacent gap: some smart content is *wanted* and then *resolved*. A
card extracted from a university email — "verify your emergency contact, this
blocks online-account registration" — is exactly what Dayfold should surface. But
once a parent has done it, the card is noise for every member, and the next run,
reading the same email, will re-extract it. Dismissal is the wrong verb for
completion; the family wants a record, not a hide.

## Decision

### 1. Two tiers, one new synced entity

Tier 0 — snooze, hide, anti-nag decay, and the once-ever swipe-escalation offer —
stays device-local and **never syncs**. This is unchanged from ADR 0043 §2b and
ADR 0024's never-syncs rule, and unchanged from ADR 0039's hide-state reasoning.

Tier 1 introduces one new entity, `content_response`, with
`kind ∈ {mute, done}`. Both kinds share a table because they share a key, a
lifecycle, a sync lane, and a management surface — and because a done row *is* a
suppression for future runs.

Tier 2 — corrections ("wrong hub", "outdated") — is **not decided here**. See
§7.

### 2. Why syncing a mute is consistent with refusing to sync a hide

A hide is passive *behavior*. It is telemetry-shaped: the system observes what a
member did and stores it. Publishing it to the family discloses attention, and
attention is not the member's to have published on their behalf.

A mute is an intentional, user-authored **policy statement**. The member composes
it, names its scope, sees it listed in Settings under their own byline, and can
edit or delete it. It is the same category of object as a saved place or a
trigger — both of which Dayfold already syncs — and it is legible to the family
precisely *because* the member chose to state it.

The distinction is not "one is private and one is not." It is that one is an
observation about a person and the other is a declaration by a person.

### 3. The server stays content-blind

A `content_response` row is keyed by the ADR 0043 `subjectRef`, which hereby
takes a **third job**: dedup key → deep-link key → **suppression key**.

Suppression is decided by exactly three string equalities against columns the
server already stores as opaque identifiers:

| `match_scope` | rule `subject_ref` | a content row matches when |
|---|---|---|
| `subject` | `card:c_123` / `hub:h_9/block:b_4` | `row.subject_ref == rule.subject_ref` |
| `kind` | `kind:weather` | `'kind:' \|\| row.kind == rule.subject_ref` |
| `source` | `source:morning-briefing` | `'source:' \|\| row.provenance->>'source' == rule.subject_ref` |

There is no fourth match scope, and there is no fuzzy match. The row's `label`,
`sublabel`, and `note` are opaque display strings carried for the client's
benefit; the server never branches on them. They are plaintext at M0 exactly as
block payloads are today, and they follow block payloads under the same flip if
ADR 0015/0017 activate.

This is a testable property, not an intention: `responses.ts` and the write-path
gate must not reference `label`, `sublabel`, `note`, `title`, or `body_md` in any
conditional.

### 4. Audience — personal by default, family by explicit choice

`personal` is the default and the only scope the swipe path can ever produce.

A **personal** mute does not reject the write. The routine still mints for
everyone else; the server strips the muting member from the card's ADR 0030
`audience[]`. The design's copy is the contract: "Won't be added for you — your
family's feed is unchanged." If stripping empties the audience, there is no one
left to write for and the write is rejected.

A **family** mute rejects the write outright: "Won't be added for anyone."

Family-wide rules are always attributed, visible to every member in Settings, and
removable by any adult (decided Q2).

**"Adult" is not currently a modeled concept.** Under ADR 0004's adults-only MVP
every account holder is an adult, so no role check is performed and none is
needed. This is correct only while that holds — see Consequences.

### 5. Done is completion, not dismissal

A done row is family-wide, on a concrete subject, and always captures who
completed it and when. An optional note may be attached and is never demanded.

Creating a done row tombstones the subject's content row, so the resolved item
leaves **every** member's Now on the next sync. It should not nag the people who
did not do it. No notification is emitted — the byline is the only signal, per
the two-way remote-change rule.

Future writes to that `subjectRef` are suppressed, so the next run reading the
same source email sees the task as handled rather than re-extracting it.

Hub-linked completions write a durable row into their hub; hubless ones live in a
Done section under Settings › Smart content.

### 6. No expiry; the derived lane enforces the same list on-device

A rule lives until removed (decided Q3). There is no `expires_at` column and no
expiry machinery.

The derived lane reads the same rule list (decided Q4). The server never sees
derived items, so this can only be honored on-device — in the ADR 0043 priority
engine, applied before the calm budget so a muted item does not consume a slot,
and on the ADR 0044 notification path, which selects from the same ranking. A
muted subject must never produce a notification.

### 7. What this ADR does not decide

- **Corrections / "fix it"** (wrong hub, outdated). Marked TEST in the design
  notes, with no persistence contract beyond "structured feedback in the next
  run's input", and dependent on ADR 0062's run receipt, which is Proposed and
  unbuilt. It gets its own ADR.
- **Kid / 14+ member rights.** Personal responses are assumed available to any
  member; family-wide is adult-only. Moot under adults-only MVP; live the moment
  ADR 0005 is accepted.
- **Post-sync undo semantics** beyond removing the rule from the list.
- **The deferred Q5 pause-suggestion** ("after N mutes, suggest pausing the
  routine?").

## Rationale

**Why a stored rule rather than a smarter dismissal.** Any dismissal-shaped
answer — a longer-lived hide, a per-device suppression list, a decay curve — fails
the same way: it lives downstream of the machine that mints the card. Only a rule
the authoring path reads *before* it writes can stop the loop, and the authoring
path is server-side or CLI-side, so the rule has to be too.

**Why `subjectRef` and not a new key.** ADR 0043 already required a shared subject
key across lanes for dedup, and the authored lane already carries it as the
deep-link target. Introducing a second key for suppression would mean two keys
that must agree, and the failure mode of disagreement is silent. One key with
three jobs is a real cost — it becomes load-bearing — but it is a smaller cost
than two keys with one job each.

**Why suppression lives at the write boundary rather than in the routine.** A
routine that politely checks the rule list is a routine that can forget to. The
write boundary is the one place every authoring path passes through — curator CLI
today, gateway tomorrow — and rejecting there is mechanical enough to stay
content-blind. The gateway should *also* pre-filter (so its run receipt can report
"skipped N muted subjects" rather than "N writes failed"), but that is an
optimization on top of an enforced floor, not the enforcement itself.

**Why binary and never "show fewer."** Dayfold has no frequency model and will not
pretend to have one. A control that promises less of something without a rule
behind it is a lie the user cannot audit. The design notes reject reason surveys
after every dismiss (nag), fuzzy "see fewer" (dishonest), and "we'll tune your
feed" claims (promise only what the rule enforces). This ADR encodes that: the
only promises made are ones a string equality can keep.

**Why no thumbs-up.** Keeping a card *is* the positive signal. A positive-signal
affordance is engagement farming, which the constitution bans.

**Alternatives rejected:**

- **(A) Keep hide, add a longer TTL.** Does not reach the authoring path; the
  next run re-mints regardless.
- **(B) Suppress client-side only.** Works for the derived lane, does nothing for
  authored or routine content, and burns sync bandwidth carrying cards the user
  has already refused.
- **(C) A typed content block for rules.** Rejected at design time (decided Q1) —
  rules are not content, and modeling them as content would put them in the
  hub tree, the visibility system, and the render path for no benefit.
- **(D) Per-routine controls only** ("pause Morning briefing"). Too coarse: a
  member who wants no traffic cards does not want to lose the whole briefing. The
  design keeps this as the third scope rung, deep-linked to the control that
  already exists in Smart Briefings rather than duplicated.

## Consequences

Positive:

- The whack-a-mole loop closes: a stated preference outlives the card, the
  device, and the run.
- Server content-blindness is preserved and, unusually, *tested* — the gate is
  simple enough that "reads no content" is a property a reviewer can check by
  grep.
- Completion becomes a first-class family fact with a byline, replacing a
  per-member dismissal that told the rest of the household nothing.
- The rule list is a legible, editable, attributed surface — the opposite of an
  opaque personalization model.
- The derived lane, the authored lane, and the future gateway all obey one list.

Negative / costs:

- **`subjectRef` becomes load-bearing.** If extraction mints a different key for
  the same source across runs, Done silently stops working and the card returns.
  The only enforcement is a convention in the authoring skill — a real, unclosed
  risk.
- **A personal mute makes writes partially succeed.** One write may be accepted
  for four members and stripped for one, and the 200 tells the author nothing
  about the strip. Author-side visibility, if wanted, is a later addition.
- **A new tombstone-retention surface** on the ADR 0040 cursor. Response
  tombstones need the same sweep floor as content tombstones, or a device offline
  longer than the floor resurrects rules the member deleted.
- **Labels and notes are new plaintext content on the server.** They are small
  and opaque, but they are content, and the note in particular can be personal
  ("used Grandma's new number"). They must never be journaled into debug bug
  reports, logged, or sent as analytics properties.
- **"Any adult" has no enforcement code.** Accepting ADR 0005 (14+ minor
  accounts) makes family-wide mute rights a real gate that does not exist yet;
  this ADR's §4 is the code that would have to change.
- **One more thing that must stay byte-identical across three languages.** The
  subject-ref grammar is built in TypeScript, Kotlin, and consumed by the CLI. A
  divergence does not error — it silently stops suppressing.

## Revisit Trigger

Reconsider when any of:

- ADR 0005 (minor accounts) is accepted — family-wide rights need a real gate.
- ADR 0061's gateway ships — the pre-run filter should become the primary path,
  with the write boundary as the enforced floor behind it.
- ADR 0015/0017 activate E2EE — `label`/`sublabel`/`note` follow block payloads
  into ciphertext, and the ID-only match must be re-verified as still mechanical.
- A member accumulates enough rules that the list stops being legible, which
  would reopen the deferred Q5 pause-suggestion.
- Evidence that `subjectRef` is unstable across runs in practice — that would
  make Done unreliable and demand a stronger key contract than convention.
