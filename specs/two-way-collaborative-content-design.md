# Two-Way Collaborative Content — Interactive To-Do Lists (Design)

**Date:** 2026-06-27
**Status:** Design synthesis — **reviewed** (4-agent brainstorm + 2 adversarial
rounds: correctness, then simplification; findings applied — see §13).
**Pre-decision**: the durable calls are ADR-class / values-shaped, so this drafts
**Proposed ADR 0038** + operator-inbox **INB-25**; nothing auto-applies. No build
before operator ratification, and the client surfaces are gated on an ADR 0008
hi-fi mockup + sign-off.
**Surfaces:** content schema, API + Postgres, CLI/curator skill, Compose
Multiplatform client + sync engine.
> **Generalized (2026-06-28):** a 6-specialist panel extended this primitive into
> the full two-way engine (member media-update, authoring, "add context"/intents,
> delete, hide) — see `specs/two-way-engine-and-content-management-design.md` +
> **INB-26**. Two carry-backs into *this* slice: (1) **build the outbox general from
> the first commit** — typed `op_type`/`target_kind`/`depends_on`, not a toggle-only
> table to retrofit; the toggle is op `type:"toggle"` on that shared spine. (2) Surface
> pending/failed via a `local_state` column on content tables so the outbox stays
> egress-only (preserves ADR 0020's one-UI-reader thesis).
**Authoritative refs:** `adr/0016` (two-way pull-loop / intents), `adr/0020`
(offline-first DB-as-SoT + reserved outbox), `adr/0015`/`adr/0017` (E2EE),
`adr/0022` (typed content + fold gesture), `adr/0029` (scoped grants),
`adr/0030` (per-member visibility), `adr/0025` (abuse limits), `adr/0006` (hubs),
`adr/0014` (trigger engine), `specs/prototype/02-data-model.md`,
`specs/prototype/03-api.md`, `specs/domain-model/schemas/content.schema.json`,
and the code paths cited inline.

---

## 1. Problem & why it matters now

To-do lists are natural, high-frequency family content, and they are the first
content members want to **change from the device** — tick "packed the tent," check
off groceries. Today Dayfold is a **one-way dumb renderer**: the client is a pure
read-replica (ADR 0020), content is server-authored by the CLI/loop, and the only
checklist that exists (`block_type='checklist'`) renders **read-only**
(`apps/client/.../HubScreens.kt:475`, `ChecklistRow` — drawn, not tappable).

Making a checkbox tappable is deceptively deep: it is Dayfold's **first two-way
data-flow primitive**, and the operator's brief is explicit that the *primitives*
must be right because this is the first of a family of mini-features (budget
`paid`, RSVP, …) that will reuse them. Get the primitive wrong and every later
interactive feature inherits the mistake — or forces a re-model exactly when E2EE
(M1) makes such a re-model most expensive.

This document synthesizes four independent brainstorms (content model; sync &
conflict resolution; API/server contract; UX/collaboration) and two adversarial
review rounds into one coherent, **deliberately minimal** design + the decisions
the operator must ratify.

## 2. The two load-bearing insights

**Insight 1 — A toggle is a *direct collaborative mutation*, not an `intent`.**
ADR 0016 reserved a two-way channel as **`intents`**: a member submits an ask →
a **key-holding AI loop** pulls it, reasons, and pushes a result card. A checkbox
needs *no reasoning*. Routing a tick through the AI loop would add latency ("your
checkmark is thinking…"), require the loop online to apply a tick, and break
offline entirely. So a todo toggle is a **new reverse-channel primitive — a
"content delta"** that sits *beside* `intents`, not inside it. The dumb-store
thesis survives: for `intents` the reasoning lives in the key-holder; for
content-deltas **there is no reasoning** — the server mechanically relays an opaque
block. "Server never reasons" holds for both (ADR 0016 §2).

**Insight 2 — E2EE-forward forces conflict resolution to the client.** M0 is
plaintext, but ADR 0015/0017 make content **ciphertext at M1** with the server a
**zero-knowledge store** (AEAD AAD = `(family_id, id, version)`). Therefore any
conflict-resolution that needs the **server to read or merge plaintext** todo
state (server-side field merge, server LWW on `done`, a per-item `PATCH` that
splices one item, promoting `done` to an indexed column) is **structurally
impossible at M1** — and choosing it now is the "painful re-model later" the
project repeatedly warns against. This single constraint decides most of the
architecture:

> **The server orders and relays opaque blocks by row; clients converge.
> Conflict resolution is a deterministic client-side merge over stable per-item
> identity. The server never reads `done`.**

All four lenses converged on these two independently.

## 3. The primitive: stable item identity (everything else stays minimal)

The root cause of "two members clobber each other" is that the current
`ChecklistPayload` items are **positional and id-less** (`content.schema.json:46`
— `{ text, done, due, assignee }`; `BudgetPayload` `:51` is identical). You cannot
merge concurrent toggles keyed on array index (a re-authored list shifts indices)
or on text (duplicate/edited text breaks it). **Stable per-item identity is the
one non-negotiable, must-land-at-M0 reservation** — the checklist analogue of ADR
0015's "lock the column split early." Without `id`, neither the merge (§5) nor the
calm "Dad checked it" byline (§7) is possible.

**Scope discipline (from the simplification round): reserve identity, defer
sophistication.** The first slice is **toggle-only** (§7), so the *only*
member-mutable fields are `done` / `doneBy` / `doneAt`. `text` / `due` /
`assignee` / `ord` stay **loop-authoritative** (only the CLI/loop writes them).
That single fact collapses almost all the distributed-systems machinery the
brainstorm reached for: with one member-mutable field there are no per-field
registers, no `rev` counter, no add-wins OR-set, and no item-tombstone register to
build — those resolve edit/delete races the M0 UI **cannot produce**. They are
clean *additive* refinements when the edit/delete/reorder slices land later (each
its own ADR-class look, §7) — adding them then is a field/format change inside the
same ciphertext payload, **not a re-model**.

### 3.1 Item schema (extends `ChecklistPayload`)

```jsonc
"ChecklistPayload": {
  "type": "object", "required": ["items"],
  "properties": {
    "items": { "type": "array", "items": {
      "type": "object",
      "required": ["id", "text"],
      "properties": {
        "id":     { "$ref": "#/$defs/ulid" },        // NEW — stable per-item id (LOAD-BEARING)
        "text":   { "type": "string" },              // loop-authoritative at M0
        "done":   { "type": "boolean", "default": false }, // member-mutable
        "doneBy": { "type": "string" },              // NEW — user id who toggled (display byline)
        "doneAt": { "$ref": "#/$defs/timestamp" },   // NEW — LWW stamp for `done` + "packed 4pm"
        "ord":    { "type": "integer", "default": 0 }, // NEW — order; loop-authoritative at M0 (§5.3)
        "due":    { "$ref": "#/$defs/timestamp" },    // loop-authoritative at M0
        "assignee": { "type": "string" }              // loop-authoritative at M0
      },
      "additionalProperties": false
    } }
  }
}
```

Deliberately **not** added at M0 (deferred, additive later): `rev` (a per-item
edit counter — it would order only the frozen loop-authoritative edit fields, so
it orders nothing while toggle-only; the correctness round also found it was never
well-defined). Per-field LWW registers, an add-wins membership set, and item-level
tombstone registers are likewise deferred with the edit/delete slices.

The whole `payload` carries the `x-e2e` annotation (ADR 0022 D2), so it becomes
**one ciphertext envelope at M1** — the server stores it opaquely; `id`/`ord`/
`doneAt` ride *inside* it and the server sees none of them.

### 3.2 Markdown ⇄ structured duality

The operator wants todos embeddable in markdown **and** interactive. Resolve it by
*direction of flow*, never by storing markdown as the source of truth:

- **Storage is always structured** (`payload.items[]`) — addressable, mergeable,
  E2EE-clean.
- **Rendering to markdown is a projection** — a checklist renders trivially as a
  GitHub task list (`- [ ]` / `- [x] ~~…~~`); the client already draws the visual
  equivalent.
- **Authoring markdown → structured is a stamp step in the CLI/curator skill.** An
  author writes `- [ ] Pack jackets`; on `dayfold push` the CLI converts it to
  `{ id:<fresh ULID>, text, done:false, ord }`, extending the skill's existing
  ULID rule ("new content → new id; update → reuse the id from `pull`"). **Item
  ids are minted client-side (CLI/skill/app), never server-side** — the server
  can't read ciphertext to mint at M1.
- **Interactive checkboxes inside prose:** do **not** parse `- [ ]` out of
  `body_md` (server-side markdown parsing is an E2EE violation; positional parsing
  reintroduces the id problem). If ever wanted, a `markdown` block carries a
  reference token (e.g. `{{todo:<blockId>}}`) the renderer replaces with the live
  widget from a sibling `checklist` block. **Deferred** — needs new renderer
  syntax; the plain structured block needs none.

## 4. The three renderings & the fold (UX)

One content item, three renderings (ADR 0022), but **not symmetric** — a checklist
lives as a *hub block*:

- **Now card — glance + deep-link, NOT directly checkable at M0** *(and the Now-
  card integration is itself deferred out of the first slice — §9).* A
  `BriefingCard` the loop emits pointing at the list (`target.blockId`):
  title = progress ("Packing — 3 of 5 done"), body = top 1–2 undone items, one
  **Open list ↗** button, provenance + privacy chip. The "3 of 5" is an
  **author-denormalized snapshot** the loop stamps — the Now surface never reads
  item internals (keeps "render, don't reason"). Tap = the **fold gesture**
  (container-transform card→detail).
- **Hub block — the canonical interactive surface** (the first slice's home). The
  existing `ChecklistRow` list inside a hub dossier, made tappable. Each row:
  filled-coral-✓ when done / warm-ring when not, strike-through + `onSurfaceVariant`
  when done, `due · assignee · ✓ Dad` subline.
- **Detail — full interactive list.** Constant Detail skeleton (hero → metadata →
  actions → provenance+privacy → related); for a checklist the **hero is the list
  itself**.

**The check *is* a fold (the on-brand move).** Completed items don't vanish
(jarring; you may want to un-tap) and don't clutter — after a brief dwell they
**fold into a collapsed "▸ 3 done" foldaway** at the bottom, using the same
container/`AnimatedContent` vocabulary as the card→detail fold, at row scale. An
active list of 5 with 3 done reads as **2 live rows + one "3 done" line** — calm,
glanceable. "Content folded away until it matters," enacted.

**Tap micro-interaction (≤200ms, M3 Expressive emphasized-decelerate):** box fills
coral with a quick scale-overshoot, ✓ draws in, strike-through wipes left-to-right
(animate between `ChecklistRow`'s two states, never snap); **one** light haptic
tick (never a pattern — reads as a game/notification; honor OS haptics-off); settle
to de-emphasized; **debounce the fold ~1.5–2.5s** after the last toggle so rapid
checking doesn't make rows leap mid-tap (this also batches the outbox flush, §5.4).
Whole-row 48dp hit target. Un-check is symmetric; **no confirmation dialog, ever.**

## 5. Sync & conflict resolution

### 5.1 The merge primitive: per-item LWW on the member-mutable fields

For a **family** (n ≤ ~6, ADR 0015), **rare-but-real** concurrency, offline-first
(ADR 0020), zero-knowledge server (M1), and a **toggle-only** first slice, the
minimal correct primitive is a **per-item Last-Writer-Wins register over the
member-mutable `done`-triple (`done`,`doneBy`,`doneAt`), layered on
"loop-authoritative fields always take the remote value."** Items are keyed by
stable `id`; the id-set only grows/shrinks via the loop (member can't add/delete at
M0). A pure client function `merge(local, remote) → state`, ~30 lines of KMP
`commonMain`:

```
for each item id in union(local.ids, remote.ids):
    base = remote.item(id)               // loop-authoritative fields: text, due, assignee, ord
    if id not in remote.ids:             // loop deleted it (or tombstoned its block) → it's gone
        drop; continue                   // member never deletes at M0 → no add-wins needed
    if local has a *pending* done-toggle for id with stamp newer than remote's:
        base.done   = local.done         // LWW by (doneAt, actorId) on the done-triple ONLY
        base.doneBy = local.doneBy
        base.doneAt = local.doneAt
    result += base
```

This *is* a CRDT (a LWW-register per item, restricted to the one mutable field),
converges deterministically, is idempotent/commutative for that field, runs
client-side on the decrypted payload (E2EE-clean), and reserves the substrate for
later sophistication without building it now.

**Rejected:** (a) whole-block LWW (today) — *is the bug*; one tick re-PUTs the
whole array and the last writer erases concurrent changes. (b) full off-the-shelf
CRDT (Automerge/Yjs/Loro) — **no native KMP/commonMain binding exists** (JS/WASM/
Rust/Swift; JVM bindings are Rust-JNI, not commonMain → hand-rolled FFI across 3
targets, a large unaudited surface) **and over-modeled** (sequence-CRDT machinery a
family checklist never needs) — *revisit* only if collaborative rich-text
(`body_md`) is ever wanted. (c) per-field registers / add-wins / tombstone
registers **at M0** — dead code for a toggle-only slice (no edit/delete races
exist to resolve); deferred, not rejected. (d) **`done-wins` biased LWW —
rejected outright** (correctness round, P0): a monotone "true beats false" register
makes **un-check impossible to propagate** (every merge re-elects `true`), which
breaks the symmetric un-check §4 requires; a tie-only bias almost never fires, so
the "checked wins the same-minute race" promise would be false. **M0 uses strict
LWW by stamp**; un-check is simply a newer stamp.

### 5.2 The stamp / ordering — wall-clock + actor tiebreak (HLC reserved)

Each `done` write carries `doneAt` (wall-clock ms) + an `actorId` tiebreak.
`actorId` = a per-installation client id minted on first run (device-grain: one
user's two devices can edit offline independently). Compare by `doneAt`, then
`actorId` lexically. This is a valid LWW-register and **converges deterministically
to a single value**.

**Honest limit (correctness round, P0-3):** it converges to the *highest-stamp*
value, **not** a causally-guaranteed one. Under large offline clock skew a later
real-world un-check can carry an earlier wall-clock stamp and lose to a stale
check. Why this is acceptable at M0 — and why we do **not** adopt HLC up front:
(1) OS clocks are NTP-disciplined even across offline gaps, so household skew is
sub-second in practice; (2) the *only* consequence is a rare stale `done`/`undone`
state that **any member fixes with one tap** — not data loss or corruption; (3) the
field shape is forward-compatible, so swapping in a **Hybrid Logical Clock**
(`(wall, counter, actorId)`, the textbook causality-correct local-first stamp) is a
~30-line drop-in *inside the same ciphertext payload* if dogfooding ever surfaces a
real skew anomaly. We therefore **document the limit and reserve HLC**, rather than
claim unqualified convergence or build HLC speculatively. (The earlier "clamp
far-future stamps at merge time" idea is dropped — it is not convergent under E2EE
since clients share no trusted reference.)

Two clocks, two jobs: the **in-payload `doneAt`/`actorId` = the merge clock**
(client-only, never server-read); the **row `version`/`updated_at` = the relay
clock** (server-assigned; drives /sync order + tombstones).

### 5.3 `ord` is loop-authoritative at M0 (resolves the reorder-race hole)

The correctness round flagged `ord` as a mutable field with no merge rule
(divergent render order). Resolution: at M0 **`ord` is written only by the
loop/CLI; members never reorder.** `merge()` always takes the **remote** `ord` (and
the other loop-authoritative fields). A member's whole-block toggle PUT that
happens to carry a stale `ord` is reconciled by the If-Match/merge-retry loop
(§6.2) — the member re-merges (taking remote `ord`), re-applies only their
`done`-triple, and re-PUTs. When a member-reorder slice lands later, `ord` gets its
own register (fractional indexing + stamp) — additive.

### 5.4 The outbox lifecycle (preserves unidirectionality)

ADR 0020 reserved exactly this. New client-side SQLDelight table:

```
outbox(op_id PK,            -- ULID = idempotency key (also the echo key, §5.5)
       family_id, block_id,
       payload_blob,        -- the new merged checklist payload (cipher at M1)
       base_version,        -- server version the op was computed against
       state,               -- pending | inflight | acked
       created_at)
```

Lifecycle: **(1) optimistic local apply** — the tap writes the local DB (still the
single writer of UI state) with a fresh `doneAt`/`actorId` stamp → reactive query →
store → UI flips instantly; the same transaction inserts the outbox row.
**(2) push** — a sender loop (sibling to the poll loop in `SyncEngine`) drains the
outbox FIFO per block: `PUT …/blocks/{id}` with `If-Match: base_version` +
`Idempotency-Key: op_id`. **(3) ack** — store the returned `version`, mark `acked`,
drop on echo (§5.5). **(4) precondition failure (412)** — refetch, re-run `merge()`
against the new base, re-enqueue; deterministic merge ⇒ **converges** — a benign
"merge & retry," never user-facing. **(5) `410 Gone`** — the target block was
tombstoned (loop deleted it); the sender **drops the op** and does not retry (§6.3).

Offline backlog: the sender **coalesces per block** (collapse N queued toggles on
one block into one PUT carrying the final payload; ties to the §4 fold debounce).

**Why unidirectionality holds:** the **render path is unchanged** (`DB → reactive
query → store → UI`; DB is still the only writer of displayed state). The outbox is
a strictly **write-only egress lane** (`DB → outbox → network`) that never feeds
the UI. Inbound, /sync still writes the DB in its one crash-safe transaction; the
only change is `applyDelta` runs `merge()` *instead of blind upsert* **for two-way
block types only** (checklist; later budget) — one-way blocks keep blind upsert.

### 5.5 Echo suppression (anti-flicker) — keyed on `op_id`, not version

Your own write returns via /sync. Suppress by **op identity**, not version
arithmetic. The server records each processed `op_id → result_version` (§6.5) and
echoes nothing extra; the client recognizes its own change two ways: (i) the
`merge()` is idempotent — re-applying your already-applied `done`-stamp is a no-op,
so even an unsuppressed echo can't flicker the value; (ii) when an outbox row is
`acked`, the client drops it once /sync delivers a block at ≥ the row's
`result_version` **and** the local state already matches — i.e. the drop is keyed on
*the op having been confirmed*, not on a bare `version ≤ acked` comparison.

> **Correctness fix (P1-2):** the earlier "drop any inbound block with
> `version ≤ acked version`" rule is **removed** — `version` is a per-row counter
> bumped by *every* writer, so it cannot tell your echo from a concurrent member's
> write that happens to share/undercut the number. Suppression must key on `op_id`
> / merge-idempotency, never on version comparison.

### 5.6 Family-friendly concurrency semantics

- **`done` → strict LWW by `(doneAt, actorId)`.** Latest tap wins; un-check is just
  a newer stamp. (No `done-wins` bias — see §5.1(d).)
- **Loop-authoritative fields (`text`/`due`/`assignee`/`ord`) → take remote.**
  Members don't write them at M0.
- **Delete:** members can't delete items at M0 → no concurrent item-delete race
  exists. Block-level deletes stay remove-authoritative (§6.3 closes the
  resurrection hole). Item-level add-wins is deferred with the delete slice.

### 5.7 Freshness: keep ~45s poll at M0

A checkbox is calm, not chat; ≤45s for a co-present parent is fine and the product
is explicitly not real-time collaboration. Keep the foreground poll; add
**sync-after-push** (a successful PUT triggers an immediate /sync on the held
mutex — near-free latency win). A server "something changed" nudge (FCM/SSE) is the
**first real justification** for the push channel ADR 0020 deferred — flag as the
*next* revisit trigger, not M0 work.

## 6. Server contract (zero-knowledge preserved)

### 6.1 Endpoint: reuse the whole-block PUT — no per-item PATCH, no op-log at M0

The member app toggles by reading its cached block, flipping the item in the
(decrypted at M1) payload, and re-PUTting the **whole block** via the existing
`PUT /families/{fid}/blocks/{id}`. A granular `PATCH …/items/{itemId}` is
**rejected** — it requires the server to read+splice the ciphertext payload,
*structurally impossible* under the AAD binding. An append-only **`POST …/ops` log
is reserved** for M2 (Revisit Trigger: whole-block re-send bandwidth on long lists,
or contention the client LWW-on-retry can't resolve calmly).

### 6.2 What the server does vs must NOT — and optimistic concurrency

**Does (all cleartext, no payload introspection):** bump `version=version+1`; set
`updated_at` (DB trigger → surfaces the row past every member's keyset cursor);
**enforce `If-Match`** (a single integer comparison); stamp server-owned provenance
(§6.4); **scope + tenancy + visibility** checks on cleartext keys (`family_id`, hub
`id`/`created_by`/`visibility`); record `op_id` idempotency (§6.5); emit tombstones
on soft-delete. **Must NOT:** read `payload.items[]`, any `done`/`text`/stamp; diff
old-vs-new to "merge"; validate item *structure* at M1 (the tolerant M0
`arr("items")` check `content-validation.ts:75` must be **gated to plaintext-M0
only**, else it forces a decrypt the server can't do).

**Optimistic concurrency.** Today `version` is bumped but `If-Match` is **not
enforced** (verified: no handler reads `If-Match`; `upsertBlock` blind-bumps
`version=blocks.version+1`, `hubs.ts:180`) → silent lost-update (Dad's stale-v5 PUT
erases Mom's v6 toggle). **Enforce `If-Match`:** mismatch → **412 Precondition
Failed** (decided, not 409 — the repo already uses **409 for parent-missing
conflicts** at `app.ts:550,564,579`, and the outbox must distinguish "stale version
→ re-merge" (412) from "parent gone → give up" (409/410); overloading 409 would
break that). The server does **one** thing: compare two integers. The client
converts row-level LWW (lossy) into item-level LWW resolved on the client
(lossless) via merge-and-retry (§5.4 step 4), capped ~3 attempts with jittered
backoff; beyond the cap, a calm "couldn't save — will retry on next sync."

### 6.3 Block resurrection hole — member write must 410 on a tombstoned target

> **Correctness must-fix (P1-4).** `upsertBlock`'s `ON CONFLICT DO UPDATE SET …
> deleted_at=NULL` (`hubs.ts:180`) means **any PUT resurrects a soft-deleted
> block.** So a member's stale offline toggle draining after the loop deleted a
> block would **un-delete it** → zombie content for the whole family, contradicting
> "block deletes are remove-authoritative." Fix: the **member-write path** must
> refuse with **410 Gone** when the target block is tombstoned (gate the
> `deleted_at=NULL` resurrection off the member path; the CLI/loop authoring path
> keeps re-create-by-PUT). The outbox sender treats 410 as "drop the op." Ship with
> slice 2 alongside the visibility check.

### 6.4 Provenance — be honest about what the server can attest

> **Correctness fix (P0-1).** The server does **not** stamp a `writer_user_id`
> today; `stampProvenance` writes `provenance.credential_id = a.cred.id`
> (`security.ts:45-56`) — **credential-grain, not user-grain**. The earlier claim
> ("stamp `writer_user_id` the same way `credential_id` is stamped") was false.

Corrected design:
- **Server-attested (cleartext, un-forgeable):** the existing
  `provenance.credential_id`. A credential resolves to a `user_id` via the
  `credentials.user_id` column — a **cleartext table join, no content read** — so
  the server *can* attest "credential C (→ user U) wrote block version V at time T"
  without decrypting anything. If a queryable per-row writer is wanted, add a
  cleartext `blocks.writer_user_id` derived from the verified JWT `sub` (a small,
  real addition — *not* "the same as today").
- **Client-asserted (display only):** per-item `doneBy`/`doneAt` inside the
  (M1-ciphertext) payload — the "✓ Dad" byline. Forgeable in principle, but these
  are co-trusting adults and the byline is UX, not a security boundary. In the
  common case (one member toggles one item per write) the attested credential→user
  equals the item's `doneBy`, so the badge is trustworthy.
- **Note the grain mismatch:** `credential`/`actorId` are per-install; "Dad" is a
  user. The attestation is at credential→user grain; the per-device `actorId` is a
  merge tiebreak only. Honest E2EE claim: the server knows *that* Dad's credential
  wrote block X at T, never *what* it says.

### 6.5 Idempotency, scopes, abuse, item-tombstones

- **Idempotency (op_id) — included at M0, required at M1.** A small
  `write_idempotency(family_id, op_id, result_version, created_at)` table (+ TTL
  sweep): a duplicate `op_id` returns the cached `result_version` instead of
  re-bumping `version`. *Why M0, not "reserved":* an offline outbox draining over a
  flaky network **will** retry, and a naive retry double-bumps `version` (the field
  that becomes M1 AAD), churning the exact seam §8 flags as delicate. It is also
  the correct echo key (§5.5). Cheap (one table, one lookup); ship it. (Correctness
  P1-1.)
- **Member write scope (ratify — §11):** recommend member app credentials get
  **global `content:write`**, with the **visibility filter as the human boundary**
  ("visibility gates human reads/writes; scope gates credential writes"). Per-hub
  credential scoping (ADR 0029) was designed for least-privilege *automation*
  tokens; per-hub for a member's own phone is redundant with visibility and adds
  approval friction. **Open sub-question:** do we ever want a read-only member
  (e.g. an eldercare hub a member may see but not edit)? If yes, keep per-hub
  member scoping available.
- **Abuse:** **defer** server-side content-write rate limits to the first
  non-operator-member milestone — for a single-operator dogfood there is no
  amplification vector to guard (you don't rate-limit yourself), and it would add
  new ADR-0025 constants. Keep the client-side **coalescing** (§5.4). When a second
  family's credentials exist, add ~60/min per credential + ~240/min per family via
  the existing `ratelimit.hit` (extends ADR 0025).
- **Item tombstones:** **none needed at M0.** An item lives in the block payload;
  deleting it = a new block version with a shorter `items[]`; clients replace the
  whole payload. Item-level wire tombstones only matter for the reserved op-log.

### 6.6 The visibility-on-write security gap — must-fix, and close the existence oracle

> **Correctness must-fix (P1-5, verified in code).** Read routes call
> `hubs.hubVisible(...)` (`app.ts:448,461,477`); the **block PUT
> (`app.ts:553-579`) and section PUT (`app.ts:536-551`) call only `requireScope`,
> no `hubVisible`** — safe only because *only the visibility-exempt household token
> writes today*. The moment members write, a member with global `content:write`
> could PUT into / probe a **restricted hub they can't read** (ADR 0030). Add a
> `hubVisible(cred, hub)` check to the block + section write paths.

Run the visibility check **before** section/parent resolution and **collapse all
non-visible / structurally-absent cases to a uniform 404**, so the write path does
not become a partial existence oracle (today `liveHubOfSection` returns **409
"parent missing"** for a gone section while an invisible hub would 404 — a prober
could distinguish them; the read path 404s uniformly). Test matrix (member writes
to):

| Target hub state | Expected |
|---|---|
| Own / family-visible hub, live section | 200 |
| Restricted-visible (on allow-list), live section | 200 |
| Restricted-**invisible** hub | **404** (uniform absence — never 403, no existence leak) |
| Visible hub, **deleted/missing section** | **404** (collapsed with the invisible case on the write path) |
| Visible hub, scope-denied (read-only member, if that role exists) | 403 |

## 7. Multi-member awareness, conflict UX, assignment, a11y

- **Awareness = provenance, not presence.** No live cursors (none exist; ADR 0020)
  and don't fake them. "Dad checked the tent" shows as a **quiet byline on the row**
  (`Dad · just now` / a small avatar) — discovered on next open, never a toast/
  banner/"Dad is active." Reuses the "added by Claude" provenance pattern with a
  human actor. **Notification line (constitution-bound):** member activity is
  **never itself** a notification; the only legitimate push is the **trigger
  engine** (ADR 0014) firing *to spare effort* ("Groceries done — skip the store"),
  opt-in and on-device, **never to report activity** ("Mom checked an item" =
  naggy, forbidden). Push is deferred anyway → M0 has **no remote-toggle
  notifications** — the correct calm floor; keep it even after push lands.
- **Conflict UX = resolve silently, explain with a byline, never a modal.** A
  family list is not a code merge; if both parents check "tent" they *agree*. A
  late remote change animates between states with the **same ≤200ms transition** as
  a local tap (a calm self-animating row), byline updating to explain it. **Never
  move a row out from under a finger** — apply the *state* immediately (honest), but
  **defer the layout shift/fold** until interaction ends. The one race-loser case
  (a remote change overwrites your just-made edit) is **not** modaled — the row
  reconciles with a byline so you see *why*; re-tap if you disagree. **No "your
  change was discarded" dialog, ever.**
- **Assignment = display-only, AI-authored, coordination-not-chores (M0).** Render
  `assignee` as a quiet subline/avatar ("Theo's meds · Mom"); it must read as *"Mom
  said she'd grab this,"* never *"Mom's chore — is she done?"* (the chore/allowance/
  gamification NOT-line). Concretely: **no assign UI, no member-picker, no reassign**
  (the loop/curator stamps `assignee`); **no per-person progress/scoreboards/
  completion-rates** (progress is list-level "3 of 5," never person-level); **no
  "overdue, assigned to Dad" nag.** Any member may check any item — assignment
  suggests, never gates.
- **First member-write slice = TOGGLE-ONLY** (check/un-check is the entire first
  write: no add/edit/delete/reorder/assign). Smallest surface that proves the whole
  outbox→sync→reconcile→provenance loop with real value, and it honors "render
  intelligence authored elsewhere" — members *interact*, they don't *author*. A
  "+ add item" button makes members content-authors → re-opens `created_by`/
  visibility/curator propose-confirm questions; **defer deliberately** (the absence
  of a creation affordance is *calm by design*). It is also what makes the §5
  merge simplifications safe (no edit ⇒ no field-registers; no delete ⇒ no
  add-wins). Each later slice (add/edit/delete → reorder → in-app assignment) gets
  its own ADR-class look.
- **A11y + honesty chips.** The interactive row must expose `Role.Checkbox` + state
  (`stateDescription`/`toggleableState`) — TalkBack/VoiceOver announces *"Pack
  sunscreen, checkbox, not checked. Double-tap to toggle."* (today's `ChecklistRow`
  ships **no semantics** — close that gap *the moment* it becomes interactive). State
  is never color-alone (strike-through + text reinforce); byline in the accessible
  label. Foldaway is a `Role.Button` with expanded/collapsed state. Reduced-motion
  drops the overshoot; haptics honor the OS setting. **Honesty-chip wording (ADR
  0022 D4 — a claim only where a real boundary enforces it):** M0-plaintext, the
  only true claims are **sharing scope** + **sync timing** — "**Shared with your
  family · Synced when online**" (not "Stored on your device" — the server holds it
  too at M0); restricted list → "**Shared with N people**" + the ADR 0030 "who can
  see this" sheet; offline → "**You're offline — saved, will sync**" (honest about
  the outbox). **Never** "Location never leaves"/"Matched on your device" on a
  checklist — those are trigger-engine claims.

  > **Correctness fix (P1-6): the "Shared with N people" chip is a false claim
  > until visibility-on-write (§6.6) ships** — until then a member outside the N
  > can write into the list. The chip (a slice-4b surface) is therefore **blocked
  > on** the `hubVisible`-on-write check (slice 2), not merely adjacent to it.

## 8. M0 → M1 migration: proof of no re-model

The invariant: **the merge is client-side over a server-opaque payload in *both*
milestones.** The only M0→M1 change is whether `payload_blob` is plaintext JSON or
an `EncryptedEnvelope` ciphertext; the server's job (order rows by
`(updated_at,id)`, relay via /sync, never inspect) is **identical**.

- **M0 (ship):** whole-block PUT carrying full `items[]` with **stable `id` +
  `doneAt`/`doneBy`**; client merges on inbound /sync; server blind-upserts + bumps
  `version` (and records `op_id`); payload plaintext. The **one schema reservation
  that must land now** is item `id` (+ `doneAt`/`doneBy`/`ord`).
- **M1 (no re-model):** `payload` field-type flips plaintext→`EncryptedEnvelope`
  (a drop-in field swap already designed); AAD `(family_id,id,version)`; stamps ride
  inside ciphertext; **merge code unchanged** (runs on decrypted payload in
  `commonMain`). The one real seam: a whole-block re-PUT bumps `version`, and the
  412-merge-retry loop re-encrypts with the new `version` in AAD on retry — the
  `op_id` idempotency table (§6.5) keeps retries from *gratuitously* churning
  `version`, which is why it is **M1-required**, not optional.
- **Later (transport optimization, still no re-model):** upgrade to granular ops
  (`{item_id, field, value, stamp}`) when whole-block re-send gets wasteful — each
  op is already an independent idempotent stamped register write; the server relays
  it as one more opaque ordered row; the **merge function is identical.**

## 9. Build slices (post-ratify; client surfaces post ADR-0008 mockup)

1. **Schema + codegen:** add item `id`/`doneBy`/`doneAt`/`ord` to `ChecklistPayload`
   (todo-only — *not* mirrored to budget yet, §11.2); regen TS/Kotlin; CLI/skill
   stamp-on-push + ULID minting + idempotent re-push (preserve ids from `pull`).
2. **Server (incl. the two must-fixes):** enforce `If-Match`→412; **add
   `hubVisible` on the block+section write paths, before parent resolution, uniform
   404** (§6.6) + its test matrix; **member-write 410 on tombstoned target**
   (§6.3); `op_id` idempotency table (§6.5); gate the item-structure validator to
   plaintext-M0; (optional) `blocks.writer_user_id` cleartext stamp (§6.4).
3. **Client sync:** `outbox` SQLDelight table + sender loop in `SyncEngine`
   (FIFO-per-block, coalescing, 412-merge-retry, 410-drop); per-block-type dispatch
   in `applyDelta` (merge for checklist, blind upsert for one-way types); the
   `merge()` pure function + convergence tests; op_id echo suppression; per-install
   `actorId`; sync-after-push.
4a. **Client UI — interactive core (ADR 0008 mockup first):** make `ChecklistRow`
   tappable (tap, haptic, ≤200ms animation, **`Role.Checkbox` a11y semantics**);
   strike-through; offline "saved, will sync" affordance. *Proves the write loop.*
4b. **Client UI — calm finish:** completed-items **foldaway** + deferred-layout-
   shift; conflict-byline; the **"Shared with N" honesty chip (blocked on slice-2
   visibility-on-write)**. The **Now-card progress summary is deferred** out of the
   first slice (it adds a loop-stamping contract + a staleness call that contribute
   nothing to validating the primitive — §12.3).

The interactive checklist is a **new interactive surface → ADR 0008 applies**: a
hi-fi mockup (tap states, foldaway, byline, offline affordance, restricted-list
chip) authored in `designs/` and operator-signed-off **before** the UI build.

## 10. ADRs to write / amend

- **NEW — ADR 0038 (Proposed): "Two-Way Collaborative Content — Direct Member
  Mutation Primitive."** Decides: content-delta channel **distinct from** `intents`;
  client-side per-item LWW over an opaque server relay (server never reads `done`);
  whole-block-PUT transport + `If-Match`→**412** + merge-and-retry + `op_id`
  idempotency; **visibility-on-write** + uniform-404 + the **410-on-tombstone**
  rule; provenance honesty (credential-grain attestation + client display byline);
  strict-LWW semantics (no done-wins); wall-clock+actor stamp with **HLC reserved**;
  the M0 **stable-id reservation**; member `content:write` scope; **toggle-only**
  first slice; reserved op-log/granular-transport upgrade. ADR-class (automation-
  autonomy boundary + customer-data write path + E2EE posture + scope).
- **Amend/compose ADR 0020** (still *Proposed*): activate the reserved outbox;
  record the egress lane preserves unidirectionality.
- **Cross-reference ADR 0016** (still *Proposed*): one line — content-deltas are a
  sibling reverse-channel to `intents`.
- **(deferred) ADR 0025** content-write rate-limit constants — only when a second
  family exists.
- **Spec deltas:** `content.schema.json` (item id + `doneBy`/`doneAt`/`ord`);
  `03-api.md` (`If-Match`→412, `Idempotency-Key`/`op_id`, visibility-on-write +
  uniform-404 + 410-on-tombstone, the op_id echo contract, member `content:write`);
  `02-data-model.md` (note the **client** `outbox` + the server `write_idempotency`
  table; member-path `deleted_at=NULL` gating).

## 11. Decisions the operator must ratify (→ operator-inbox INB-25)

Trimmed to the calls that are genuinely ADR-class or values-shaped (the
simplification round cut three agent-decidable items off the gate):

1. **Accept Proposed ADR 0038** — the primitive + architecture above. *(ADR-class:
   scope + automation-autonomy + E2EE posture.)*
2. **Member write scope** — member app credentials get **global `content:write`**
   with visibility as the human boundary (recommended), **or** keep per-hub member
   scoping to allow a future **read-only member** (eldercare). *(Values/scope.)*

Recorded as agent-leaning, **not** gated (decided-with-record unless the operator
objects): **todo-only first** (mirror to `BudgetPayload` when budget goes two-way —
a cheap additive copy, no re-model); **strict-LWW, no done-wins** (the calm reading
*and* the correct one); **wall-clock+actor stamp, HLC reserved** (documented
limit, one-tap-recoverable). Any of these can be pulled onto the gate if the
operator wants a say.

## 12. Open questions & risks

1. **[high] Stable per-item `id` is the gate** for both merge correctness and the
   byline. Land it at M0 or the feature is unsafe to ship. Server **cannot** mint at
   M1 (ciphertext) — CLI/skill/app own minting. *(ULID collision across two offline
   devices minting in the same list → astronomically unlikely; treated as the same
   item if it ever happened; accepted risk.)*
2. **[high — security] Two write-path must-fixes** ship with slice 2:
   visibility-on-write + uniform-404 (§6.6) and 410-on-tombstoned-block (§6.3).
   Member writes are unsafe without both.
3. **[med] Now-card snapshot staleness** (deferred surface). The card's "3 of 5" is
   loop-stamped; a member toggle updates the block but not the card until the loop
   re-emits. Deferring the Now-card integration (§9) sidesteps this for the first
   slice; revisit with real dogfood data — *lean: accept brief staleness, don't
   live-recompute (keeps "card is denormalized").*
4. **[med] Offline clock skew** can let a stale `done` outlive a later un-check
   (§5.2). Accepted at M0 (NTP-disciplined clocks; one-tap recovery); **HLC is the
   reserved drop-in** if dogfooding shows anomalies.
5. **[med] `version`/AAD seam at M1** — concurrent writers contend `version`; the
   412-merge-retry loop re-encrypts with the new `version` in AAD; the `op_id`
   idempotency table bounds gratuitous churn. Confirm with whoever owns the E2EE
   build — the one real seam to ADR 0015/0017.
6. **[principle] Provenance-not-presence** should be written down as a durable
   principle (candidate for `context/operating-lessons` or the ADR) so a future
   push-enabled milestone doesn't drift into "Dad is viewing" presence.
7. **[discipline] One-haptic-tick / no person-level scoreboards** are easy to
   regress into gamification — guard in review.

## 13. Review findings applied (audit trail)

**Round 1 — correctness** (verified every load-bearing citation in code; spine
"server orders opaque rows, clients converge, server never reads `done`" confirmed
sound). Applied: **P0-1** provenance — server stamps `credential_id` (credential-
grain) not `writer_user_id`; corrected §6.4 (attest via credential→user cleartext
join; optional real `writer_user_id` column; client `doneBy` = display). **P0-2**
dropped `done-wins` (monotone breaks un-check; tie-only is vacuous) → strict LWW,
§5.1(d)/§5.6. **P0-3** removed the unqualified "always converges"; documented the
offline-skew limit + one-tap recovery + HLC-reserved, §5.2. **P1-1** `op_id`
idempotency table promoted from "reserved" to **M0-included / M1-required**, §6.5.
**P1-2** echo suppression re-keyed on `op_id`/merge-idempotency (removed the buggy
`version ≤ acked` rule), §5.5. **P1-3** `ord` made loop-authoritative (take-remote)
at M0, §5.3. **P1-4** member-write **410 on tombstoned block** (closes the
`deleted_at=NULL` resurrection hole), §6.3. **P1-5** visibility-on-write runs
**before** parent resolution; **uniform 404** collapses invisible-hub + missing-
section (closes the existence oracle); test matrix expanded, §6.6. **P1-6** the
"Shared with N" chip is **blocked on** visibility-on-write, §7. **P2-4** decided
**412** (409 is overloaded for parent-missing), §6.2. **P2-1** `rev` cut (also a
simplification win). **P2-2** ULID-collision noted (§12.1). **P2-3** the
"no KMP CRDT binding" claim confirmed defensible.

**Round 2 — simplification** (lens: one-family dogfood; operator time is the
scarce resource). Applied: **merge collapsed to per-item LWW on the `done`-triple +
take-remote for loop-authored fields** (per-field registers, add-wins OR-set,
tombstone register, `rev` all cut as dead code for a toggle-only slice — additive
later, no re-model), §3/§5.1. **Generalized `AddressableItem` deferred** to
todo-only (mirror to budget when budget goes two-way), §11. **Now-card summary
deferred** out of the first slice, §9/§4. **Content-write rate limits deferred** to
the first non-operator member, §6.5. **UI slice split** 4a (tappable + a11y =
proves the loop) / 4b (foldaway + byline + chip = calm finish), §9. **Ratify gate
trimmed 5→2**, §11. Kept (cutting would force a re-model or lose real security):
**stable `id`**, **client-side merge over opaque relay**, **`doneAt`+`actorId`
stamp**, **whole-block PUT**, **`If-Match`→412**, **visibility-on-write**,
**`writer_user_id`/credential attestation**, **sync-after-push**, **toggle-only**.

---

### Provenance of this design
4-agent brainstorm (content primitive · sync/conflict · API/server · UX/collab),
each grounded in the repo + cited, then two adversarial rounds (correctness, then
simplification) whose findings are applied above (§13) — they reinforced rather
than fought: adopting the simplification dissolved most correctness defects (they
lived in the cut machinery), and the survivors became concrete slice-2 must-fixes.
Convergence across the four lenses was strong and independent on the load-bearing
calls (stable item id, whole-block PUT, server-opaque client-side merge,
new-primitive-beside-intents, toggle-only first slice).
