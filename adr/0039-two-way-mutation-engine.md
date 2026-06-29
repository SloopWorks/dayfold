# ADR 0039: Two-Way Mutation Engine — Typed-Op Spine, Two Channels, Lifecycle Ops

## Status

**Accepted** 2026-06-29 (operator ratified the two-way build bundle; INB-26 closed —
R2 object store, W2 = author into visible hubs only + loop-never-edits-member-blocks +
server-enforced audience-intersection, W5 hide = per-member self-scoped local-only
first; W3/intents + cost constants accepted via ADR 0041/0042). Was **Proposed**
2026-06-28 (agent-drafted from a 6-specialist review panel — system
design · data modeling/transfer · privacy/E2EE · security/ACL · mobile/KMP ·
performance — plus one adversarial round; **operator-gated** — ADR-class:
automation-autonomy boundary + customer-data write path + E2EE posture + auth
scope). **Generalizes** ADR 0038 (the to-do content-delta primitive — its first
instance) into the transport spine + op taxonomy + content-lifecycle ops that all
member-write features ride. Composes ADR 0016 (the reserved `intents` channel),
ADR 0020 (offline-first DB-as-SoT + reserved outbox), ADR 0015/0017 (E2EE),
ADR 0029 (scoped grants), ADR 0030 (per-member visibility), ADR 0025 (abuse
limits), ADR 0033 (tracked migrations), ADR 0022 (typed content), ADR 0008
(design-first). Full design + audit trail:
`specs/two-way-engine-and-content-management-design.md`. Six operator decisions are
open (**INB-26**); this ADR does not pre-empt them.

**Sibling ADRs this spawns** (separate, Proposed-to-follow — kept out of scope here
to keep the engine ADR focused): member-authoring & content-management **authz**
(the permission matrix, `created_by`/`author_kind` columns, `content:delete`
scope); member-uploaded **encrypted media** (ADR 0036 Phase 2); **ADR 0016
amendment** activating `intents` for "add context" + its constitution gate; the
**schema-evolution ruleset**; and amendments to ADR 0015 (encrypted blobs),
ADR 0025 (content-write rate limits), ADR 0020 (outbox activation).

## Context

ADR 0038 proved Dayfold's first two-way write (a to-do toggle): optimistic local
apply → a local **outbox** → whole-block `PUT` with `If-Match`→412 + `op_id`
idempotency → a **zero-knowledge server relay** → keyset `/sync` → client-side LWW
merge. The operator then asked whether the engine generalizes to a family of
member-driven features — updating a hero image (W1), authoring notes/todos/links
(W2), a free-form "add context" that an async AI routine integrates into a hub
(W3), deleting content (W4), and hiding content (W5) — **without** the server ever
reasoning or reading content, and **without** a re-model when E2EE (M1) lands.

The panel's central finding: these are **not one feature type** but **three
write-classes plus a lifecycle axis**, and ADR 0038's outbox is drawn too narrow
(single-op, `block_id`-shaped). Shipping a toggle-only outbox and retrofitting
W1–W5 is the exact "wrong primitive inherited by every later feature" the operator
warned against. The fix is to fix the **shape** now (cheap) while building only a
minimal slice (delete + local hide on the toggle's spine).

Today's engine is one-way: `SyncEngine` has only a read-drain + poll loop; **there
is no sender, no outbox table** — the egress lane is greenfield. ADR 0038 already
identified two latent defects that become exploitable the moment members write
(visibility-not-enforced-on-write; soft-delete resurrection on upsert); this ADR
must generalize both fixes to *every* mutation, not just blocks.

## Decision

Adopt a **two-way mutation engine**: one client outbox of **idempotent typed ops**
draining to **one `/mutations` endpoint**, routed server-side into **two channels**
that both preserve "the server never reasons / zero-knowledge store."

### 1. The typed-op envelope (generalize ADR 0038's outbox)

Every member write is an op:

```
Op { op_id:ULID,                         // idempotency + echo-suppression + ordering + dep key
     type, channel:"delta"|"intent",
     target:{kind,id,parent?}, base_version:int|null,
     depends_on?:[op_id], media_ref?,
     payload_blob }                       // plaintext JSON @M0, EncryptedEnvelope @M1 — SERVER-OPAQUE
```

The outbox stays **opaque to its own payloads** (`type` + `target.kind` pick the
route; the blob is ciphertext at M1 and is never decoded by the sender). ADR 0038's
single-op outbox is this row with `type="toggle"`.

### 2. One `/mutations` batch endpoint; two channels

`POST /families/{fid}/mutations` takes an ordered batch of ops → per-op results
(`ok|precondition|gone|forbidden|notfound`, with `version`/`result_ref`). The
existing typed `PUT`/`/sync` read surface is unchanged; `PUT /blocks/{id}` remains a
**deprecated single-op alias** for the CLI/loop authoring path. Two channels share
the one queue and diverge at a single server dispatch:

- **Channel A — content-delta (W1/W2/W4/W5 + toggle):** the server relays the
  opaque row, bumps `version`, tombstones on delete, returns via `/sync`. **No
  reasoning.**
- **Channel B — intent (W3):** the server **enqueues** an opaque `intents` row
  (status `pending`); the **key-holding AI loop** pulls, decrypts, reasons, and
  authors result content via the normal content-write path → the family pulls it via
  `/sync`. Reasoning lives in the key-holder, **never the server** (ADR 0016 §2).
  The `intents` table + its lifecycle and the **free-text-vs-structured + loop-
  placement** decisions are defined/gated in the ADR 0016 amendment + INB-26 — **this
  ADR commits only to the channel's *structural* existence and its opaque-enqueue
  shape**, not to building W3.

### 3. Idempotency: `op_log` for every op type

Generalize ADR 0038's `write_idempotency` to **`op_log(family_id, op_id,
result_ref, result_version, created_at)`** covering *all* op types — a replayed
`op_id` returns the cached result (no double version-bump, double-tombstone,
double-intent). TTL-swept (extend the existing `/cron/sweep`, today auth-only).

### 4. Lifecycle ops — delete and hide are distinct, with distinct homes

- **Delete (W4)** = **soft-delete + tombstone** (the existing mechanism; `deleted_at`
  is cleartext, so it is E2EE-clean and drives keyset cache-eviction). Distinguish
  **operational delete** (tombstone + scheduled purge) from **erasure-on-request**
  (guardrail #4: actually destroy ciphertext **+ object-storage blobs** —
  **refcount-before-delete** a content-addressed blob shared by another card; family-
  wide erasure may **crypto-shred** the key). Authz (detail → the authz sibling
  ADR): a **`content:delete` scope** carved out of `content:write` (blast-radius
  isolation); **no owner override** (ADR 0030 §7).
- **Hide (W5)** = **per-member, self-scoped, reversible UI state, orthogonal to the
  ADR 0030 visibility ACL** (a *shared* hide would be a censorship vector). **Local-
  only first** (a client table, never synced — zero server surface, zero leak, works
  offline). On promotion to cross-device sync, the hidden-set is **in-ciphertext**
  (a per-member encrypted preference) at **both** grains — a cleartext per-member
  `(user_id, res_id, hidden_at)` table is a *behavioral* who-chose-to-hide ledger,
  not the static who-can-see ACL, so it is **not** an acceptable cleartext leak.
  Hidden content still syncs as a normal *permitted* row; "show hidden" is a client
  view filter; tombstone GC keeps the sync window bounded.

### 5. Media stays inside the opaque payload (W1)

Member media rides **inside `payload_blob`**, never a cleartext indexed URL column.
Host-allowlist/SVG/icon validation (ADR 0036, `validateBlockPayloadMedia`,
`apps/api/src/content/hubs.ts`/`app.ts:575`) **reads the payload** → it is gated to
**plaintext-M0 and moved client-side at M1**. **Posture change to ratify:** at M1
ADR 0036's host/SVG/decode-bomb defenses become **client-side-only → advisory
against a modified client**; the only *server*-enforceable M1 boundary is blob
**size/count caps** (cleartext). The encrypted-blob path + client-side thumbnail/
EXIF-strip is the **member-media sibling ADR** (ADR 0036 Phase 2).

### 6. The two security must-fixes generalize to *every* mutation

Carry ADR 0038's fixes from blocks to **all** content mutations (card, hub,
section, delete, hide):

- **Visibility-on-write.** Add `hubVisible`/`cardVisible` to every member-write
  path, **before** parent resolution; **collapse invisible + structurally-absent to
  a uniform 404** (close the existence oracle); 403 only for visible-but-scope-
  denied. Test matrix per op: own / family / restricted-visible / restricted-
  invisible → 200/200/200/**404**.
- **410-on-tombstone.** A stale member write to a soft-deleted target returns **410
  Gone**, never resurrects (`deleted_at=NULL` resurrection is gated to the loop/CLI
  authoring path only).

### 7. The dumb-server invariant (per op)

The server touches only **cleartext** envelope/columns (`family_id`, `id`,
`version`, `updated_at`, `deleted_at`, provenance `credential_id`/`writer_user_id`,
visibility keys), **never `payload_blob`**. It computes version bumps, an integer
`If-Match` comparison, tombstones, a scope/visibility check, and an opaque enqueue —
no content read, no field-merge, no reasoning. Conflict resolution is **client-side
LWW** (ADR 0038). This holds for every op type (the per-op proof is in the design
doc §2.4); the one watch-item is W1 media validation (§5).

### 8. Reserve the general shape; build a minimal first slice

- **Reserve now (cheap, costly to retrofit):** the typed-op envelope shape (`type`
  discriminator + `target_kind` + nullable `depends_on` on the outbox row); the
  generalized `op_log`; the cleartext author columns the authz ADR needs; the
  `content:delete` scope; the visibility-on-write + 410-on-tombstone generalization;
  `block_type`/`card_kind` **ENUM→`text`**; the `x-e2e` per-field rule; the Kotlin
  codegen drift guard.
- **Build (first slice):** the toggle (ADR 0038) **+ W4 delete** (reuses soft-delete
  + tombstone) **+ W5 hide (local-only)** on the reserved shape.
- **Defer behind their own slices/ADRs:** the `/mutations` **batch + `depends_on`
  DAG** (no first-slice feature needs cross-target causality); **W1 media + object
  storage + the image subsystem**; **W2 member authoring** (author into existing
  visible hubs only when it lands; member-created hubs deferred); **W3 intents/AI
  loop** (blocked on the constitution gate anyway).

### 9. Client engine preserves the unidirectional thesis (ADR 0020)

The outbox is an **egress-only** lane (`DB → outbox → network`); it is **never a
second UI reader**. Pending/failed status surfaces via a **`local_state` column on
the content tables** (written by the optimistic apply, cleared by the inbound echo),
so the UI still reads only the content tables — `network → DB → store → UI` stands.
A sender loop in `SyncEngine` (sharing the existing sync mutex) drains the outbox:
`pending→inflight→acked→(echo-suppress own op_id)→delete`, with 412→re-merge-retry,
410→drop, 5xx/network→backoff, cap→`failed`.

## Rationale

- **One op spine over REST sprawl** — the only client is Dayfold's own KMP outbox
  drain; optimize for an idempotent, ordered batch flush, not third-party REST
  ergonomics. The cross-cutting machinery (idempotency, echo-suppression, 412/410
  handling) is written once, not re-derived per verb.
- **Two channels, one queue** — a checkbox needs no AI; an "add context" does. Both
  preserve the dumb-store thesis: Channel A has no reasoning to relocate (opaque
  relay); Channel B relocates reasoning to a **key-holder** (the only place that can
  decrypt under E2EE). Routing a checkbox through the loop would break offline and
  add latency.
- **Client-side merge / server-opaque relay** — the only design that survives the M1
  E2EE flip with **no re-model** (server-side field-merge is structurally impossible
  on ciphertext). This is the load-bearing constraint, inherited from ADR 0038.
- **Delete=tombstone, hide=per-member-self-scoped** — delete must propagate to evict
  every device's cache (tombstone); hide is a *personal* declutter that must not
  affect others (a shared hide is censorship) and must not leak a behavioral ledger
  to the server (in-ciphertext on sync).
- **Reserve-vs-build** — the costly-to-retrofit pieces are *shapes* (envelope,
  identity, columns), not *engines*; the batch/DAG, media, and intent builds are
  deferrable without a re-model, so the dogfood ships a minimal correct slice.

**Rejected:** per-resource REST verbs as the spine (re-derives idempotency/echo five
times; no atomic batch for offline flush); a granular per-item `PATCH`
(structurally impossible under the AAD-bound ciphertext — splicing one item server-
side); server-side conflict merge (breaks zero-knowledge at M1); routing W3 through
the content-delta channel (it needs reasoning → the intent channel); a cleartext
per-member hide table (a behavioral metadata leak beyond the visibility ACL);
media in a cleartext indexed column (breaks zero-knowledge); building the full
batch/DAG/media/intent engine at the dogfood (premature — reserve the shape, build
the slice).

## Consequences

**Positive:** a single, E2EE-proof, dumb-server write engine that all five features
(and future ones) ride; the dumb-store/zero-knowledge thesis preserved per op; the
two latent security defects fixed for every mutation; a clean reserve-vs-build path
so the dogfood ships a minimal correct slice (toggle + delete + local hide) without
foreclosing W1/W2/W3.

**Negative / cost:** the egress lane is greenfield (no outbox/sender today); a real
client subsystem (outbox + sender state machine + `local_state` + echo-suppression);
several **prerequisites must land before any W1–W5 field ships** — the forward-compat
residuals (TS strict only on authoring/validate, not relay; an unknown-`type`
renderer placeholder; the missing Kotlin codegen drift guard), the cleartext author
columns on blocks, `ENUM→text` for `block_type`/`card_kind`, and the `x-e2e`-per-
field rule. W1 (media/E2EE) and W3 (intents/AI loop) remain genuine multi-week,
M1-shaped subsystems with their own ADRs and operator gates. The intent channel's
free-text scope is constitution-gated (ADR 0016 §4 + INB-26) and is **not** decided
here.

**Operator-gated / open (INB-26):** W3 free-text-vs-structured (constitution
amendment); W3 AI-loop placement (key-holder vs hosted — guardrail #3); object-store
vendor (Cloudflare R2 vs Vercel Blob — spend); W2 authoring scope; W5 hide model;
W3 cost constants. This ADR is **Proposed**; it does not flip until the operator
directs, and client surfaces remain gated on ADR 0008 mockups.

## Revisit Trigger

Whole-block transport bandwidth or 412-retry contention forces granular/op-log
*transport* (the merge function is unchanged — a transport swap, not a re-model);
W2 member authoring lands (re-opens `created_by`/visibility — the authz sibling ADR
+ ADR 0030's deferred server-enforced intersection); W3 is scheduled (the ADR 0016
constitution gate fires); a hosted AI loop is wanted (re-examine the E2EE boundary,
ADR 0017); or member-uploaded media lands (the ADR 0036 Phase-2 sibling ADR).
