# ADR 0038: Two-Way Collaborative Content — Direct Member Mutation Primitive

## Status

**Accepted** 2026-06-29 (operator ratified the two-way build bundle; INB-25 closed:
ADR accepted as written + member write-scope = **global `content:write`** with the
visibility filter as the human boundary). Was **Proposed** 2026-06-27 (agent-drafted
from a 4-agent brainstorm + 2 adversarial review rounds; **operator-gated** —
ADR-class: automation-autonomy boundary + customer-data write path + E2EE posture +
auth scope). Extends/composes ADR 0016
(reserved two-way `intents`), ADR 0020 (offline-first DB-as-SoT + reserved
outbox), ADR 0015/0017 (E2EE), ADR 0029 (scoped grants), ADR 0030 (per-member
visibility), ADR 0025 (abuse limits), ADR 0022 (typed content + fold), ADR 0006
(hubs), ADR 0008 (design-first). Full design + audit trail:
`specs/two-way-collaborative-content-design.md`. Two operator decisions are open
(INB-25).

## Context

Dayfold is a one-way dumb renderer: the client is a read-replica (ADR 0020),
content is server-authored by the CLI/loop, and the lone `checklist` block renders
read-only. Interactive to-do lists — family members ticking items done on their
devices, converging across devices with conflict resolution — are the **first
two-way data-flow feature**, and the first of several (budget `paid`, RSVP, …) that
will reuse the same primitive. The operator's brief is explicit: get the
*primitives* right, because a wrong primitive is inherited by every later
interactive feature and is most expensive to re-model once E2EE (M1) lands.

Two constraints dominate:

1. **A toggle is a direct mutation, not an `intent`.** ADR 0016 reserved a two-way
   channel as `intents` — a member ask that a **key-holding AI loop** pulls,
   reasons over, and answers with a pushed card. A checkbox needs no reasoning;
   routing it through the loop would add latency, require the loop online, and break
   offline. So a todo toggle is a *different* reverse-channel primitive.
2. **E2EE-forward forces conflict resolution off the server.** At M1 content is
   ciphertext with the server zero-knowledge (AAD `(family_id,id,version)`). Any
   conflict-resolution needing the server to read/merge plaintext `done` (server
   field-merge, a per-item `PATCH` that splices ciphertext, promoting `done` to an
   indexed column) is **structurally impossible at M1** — choosing it now is the
   painful re-model the project warns against.

Two latent defects in today's code become exploitable the moment members write
(both verified): the block/section PUT enforces scope but **not hub visibility**
(`apps/api/src/app.ts:553-579` vs the read routes' `hubVisible` at `:448/:461/:477`)
→ a member could write into / probe a restricted hub (ADR 0030); and `upsertBlock`'s
`ON CONFLICT … deleted_at=NULL` (`apps/api/src/content/hubs.ts:180`) **resurrects a
soft-deleted block on any write** → a stale offline toggle could un-delete content
the loop removed.

## Decision

Adopt a **content-delta primitive** — a direct member-authored content mutation the
server **mechanically relays** (never reasons over, never reads `done`), reflected
via the existing `/sync`. It sits **beside** ADR 0016's `intents`, reuses ADR 0020's
reserved **outbox**, and preserves the dumb-store thesis (there is no reasoning to
relocate — the server relays an opaque block). Scoped, for now, to **interactive
to-do checklists, toggle-only**.

1. **Stable per-item identity (the load-bearing M0 reservation).** Add a
   client-minted **ULID `id`** (+ `doneBy`, `doneAt`, `ord`) to `ChecklistPayload`
   items (today positional/id-less). Ids are minted by the CLI/skill/app, **never
   server-side** (the server can't read ciphertext to mint at M1). This is the one
   thing costly to retrofit; everything else is additive.

2. **Conflict resolution is a deterministic client-side merge over an opaque server
   relay.** Per-item **Last-Writer-Wins** on the member-mutable `done`-triple
   (`done`/`doneBy`/`doneAt`), stamped `(doneAt wall-clock, actorId)` with a
   per-install `actorId` tiebreak; **loop-authoritative fields**
   (`text`/`due`/`assignee`/`ord`) always take the remote value; the item-set is
   loop-owned (members can't add/delete at M0). **Strict LWW — no `done-wins`
   bias** (monotone done-wins would make un-check impossible; un-check is just a
   newer stamp). Wall-clock+actor converges deterministically; it is **not**
   causally guaranteed under large offline clock skew (rare, NTP-disciplined,
   one-tap-recoverable) → **HLC is the reserved drop-in** (forward-compatible field
   shape) if dogfooding shows anomalies. No per-field registers, add-wins OR-set,
   tombstone register, or `rev` counter at M0 — those resolve edit/delete races the
   toggle-only slice cannot produce and are additive when those slices land.

3. **Transport = the existing whole-block PUT** (`PUT /families/{fid}/blocks/{id}`),
   not a per-item PATCH (structurally impossible under the AAD binding) and not an
   op-log (reserved for M2 behind a Revisit Trigger). The client reads its cached
   block, flips the item, re-PUTs the whole (ciphertext at M1) payload. The server
   stores it opaquely, bumps `version`, sets `updated_at`, relays via `/sync`.

4. **Optimistic concurrency + idempotency.** Enforce `If-Match: <version>` →
   **412 Precondition Failed** on mismatch (decided over 409, which the repo
   overloads for parent-missing). The client treats 412 as **merge-and-retry**
   (re-`merge()` against the fresh version, re-PUT; deterministic ⇒ converges;
   capped with backoff). A small server **`write_idempotency(family_id, op_id,
   result_version, …)`** table returns the cached version on a duplicate `op_id`
   (the outbox idempotency + echo key) — **included at M0, required at M1** so
   flaky-network retries don't gratuitously churn `version` (the AAD field). Echo
   suppression is keyed on **`op_id`/merge-idempotency**, never on `version`
   comparison.

5. **The local outbox preserves unidirectionality (ADR 0020).** Tap → optimistic
   local DB write (still the single UI-state writer) + an outbox row, in one
   transaction; a sender loop in `SyncEngine` drains FIFO-per-block (coalescing,
   412-merge-retry, 410-drop). The outbox is a write-only **egress** lane that never
   feeds the UI; inbound `/sync` still writes the DB in one crash-safe transaction,
   with `applyDelta` running `merge()` for two-way block types and blind upsert for
   one-way types.

6. **Two security must-fixes ship with the member-write slice.**
   (a) **Visibility-on-write:** add `hubVisible(cred, hub)` to the block + section
   write paths, run **before** parent resolution, and **collapse invisible-hub +
   structurally-absent to a uniform 404** (no existence oracle; 403 only for a
   visible-but-scope-denied write). (b) **410-on-tombstone:** the member-write path
   refuses (`410 Gone`) on a soft-deleted target block instead of resurrecting it;
   the outbox drops the op on 410.

7. **Provenance is honest about grain.** The server stamps the existing
   un-forgeable `provenance.credential_id` (credential-grain); a credential resolves
   to a `user_id` via a **cleartext table join** (no content read), optionally
   surfaced as a `blocks.writer_user_id` column. The per-item `doneBy`/`doneAt`
   inside the (ciphertext) payload are **client-asserted display** ("✓ Dad") — UX,
   not a security boundary (co-trusting adults).

8. **Scope.** Member app credentials get **global `content:write`** (recommended;
   INB-25 #2), with the **visibility filter as the human boundary** ("visibility
   gates human reads/writes; scope gates credential writes"). Per-hub member scoping
   stays available if a **read-only member** role (e.g. eldercare) is wanted.

9. **First slice = toggle-only.** Check/un-check is the entire first member write
   (no add/edit/delete/reorder/assign); members *interact* with loop-authored
   content, they don't author it. Later slices are each ADR-class. The interactive
   surface is **new UI → ADR 0008 applies** (hi-fi mockup + sign-off before build).
   Member-facing rate limits are **deferred** to the first non-operator member.

## Rationale

The E2EE-forward constraint (#2) is decisive and was reached independently by every
brainstorm lens and confirmed by adversarial review: the only architecture that
survives the M1 ciphertext flip **without a re-model** is server-opaque relay +
client-side merge over stable identity. Whole-block PUT reuses all existing
machinery (upsert, scope, provenance, keyset sync, tombstones) and is the *only*
shape compatible with AAD-bound ciphertext. Toggle-only + loop-authoritative
non-`done` fields collapses the merge to a ~30-line LWW register, deferring CRDT
sophistication (per-field registers, add-wins, HLC) as clean additive upgrades —
"reserve identity, evolve the rest," mirroring ADR 0015's "lock the column split
early."

**Rejected:** whole-block LWW (today — *is* the clobber bug); per-item `PATCH`
(server must splice ciphertext — impossible at M1); server-side field merge
(reads plaintext — impossible at M1); off-the-shelf CRDT (Automerge/Yjs/Loro — no
native KMP/commonMain binding; over-modeled for a family checklist; revisit only
for collaborative rich-text); `done-wins` biased LWW (breaks un-check); HLC at M0
(causality machinery the NTP-disciplined household workload doesn't need yet —
reserved); op-log transport (over-built for ≤6 adults — reserved); 409 for
precondition failure (overloaded with parent-missing); per-hub member scoping as
default (redundant with visibility, adds approval friction); routing toggles
through `intents` (latency, requires loop online, breaks offline).

## Consequences

**Positive:** Dayfold's first two-way primitive, correct-by-construction across the
M0→M1 E2EE cutover (no re-model); reuses the outbox/scope/sync machinery; fixes two
latent security defects (visibility-on-write, block resurrection) before they're
reachable; a calm, offline-first interaction (optimistic taps, silent convergence,
provenance-not-presence, no conflict modals); a reusable substrate for budget/RSVP.

**Negative / cost:** a schema migration (item ids + stamps) + payload codegen; the
client merge engine + outbox + sender loop + convergence tests; server changes
(If-Match/412, visibility-on-write, 410-on-tombstone, op_id idempotency, validator
plaintext-gating); whole-block re-send bandwidth per toggle (acceptable at family
list sizes; op-log is the reserved fix); a documented rare offline-skew stale-state
(one-tap-recoverable; HLC reserved); the interactive surface is gated on an ADR 0008
mockup. New surface area to build/test/review — deliberately minimized to the
toggle-only slice.

## Revisit Trigger

Member add/edit/delete/reorder of items is wanted (per-field registers + add-wins +
its own ADR); whole-block re-send bandwidth or true concurrent-edit contention
demands the op-log/granular transport (M2); offline clock-skew anomalies appear in
dogfooding (adopt the reserved HLC); a second (non-operator) family lands (add the
deferred content-write rate limits); free-text/AI-mediated interaction is wanted
(that is ADR 0016's `intents` path + its constitution gate, not this primitive); or
the operator rejects the global-`content:write` member-scope posture (INB-25 #2).
