# Two-Way Engine & Content Management — Generalizing the Mutation Primitive (Design)

**Date:** 2026-06-28
**Status:** Panel synthesis — **6-specialist review** (system design · data
modeling/transfer · privacy/E2EE · security/ACL · mobile/KMP · performance/
efficiency) **+ 1 adversarial round applied** (corrected a wrong forward-compat
claim; added the reserve-vs-build slice split; tightened media-trust/hide-leak/
authorship-enforcement — see §11/§14). **Pre-decision** — extends `specs/two-way-collaborative-content-design.md`
(the interactive-todo primitive) + **Proposed ADR 0038**. The durable calls are
ADR-class / values-shaped / spend-class, so this drafts the **ADR set in §13** +
operator-inbox **INB-26**; nothing auto-applies. No build before ratification;
client surfaces gated on ADR 0008 hi-fi mockups.
**Surfaces:** content schema, API + Postgres, **object storage (new)**, CLI/
curator skill + the **async AI loop**, Compose/KMP client + sync engine.
**Authoritative refs:** the todo design doc + ADR 0038; `adr/0016` (intents),
`adr/0020` (outbox), `adr/0015`/`0017` (E2EE), `adr/0022` (typed content),
`adr/0029` (scopes), `adr/0030` (visibility), `adr/0036` (media/allowlist),
`adr/0025` (abuse), `adr/0018` (Vercel/Neon), `specs/prototype/02-data-model.md`,
`…/03-api.md`, `…/06-storage.md`, `specs/domain-model/schemas/content.schema.json`,
and the code paths cited inline.

---

## 1. Problem & scope

The todo primitive (ADR 0038) proved the **first** two-way write: a member
toggles a checkbox; an optimistic local apply → outbox → whole-block `PUT` →
zero-knowledge server relay → keyset `/sync` → client-side LWW merge. The
operator now wants the engine to generalize to a family of member-driven
features. This panel pressure-tested five:

- **W1 — update media** (hero image / thumbnail / icon / accent) on a hub/card.
- **W2 — author content** (markdown note, todo list, link) from the device.
- **W3 — "add context"** (free-form text + images + URLs) queued for an **async
  AI routine** that integrates it into a hub intelligently.
- **W4 — delete** content (destructive; warn; ACLs apply).
- **W5 — hide** content (reversible; "show all" / "show hidden").

**The reframe that organizes everything:** these are not one feature type. They
fall into **three write-classes** plus a lifecycle axis, and the engine must
serve all of them through *one* spine without the server ever reasoning or
reading content:

| Class | Features | Server's job | Reasoning |
|---|---|---|---|
| **Direct content-delta** (no AI) | W1, W2, W4, W5, toggle | relay an **opaque** row; bump `version`; tombstone | none |
| **Intent** (async AI) | W3 | **enqueue** an opaque blob for the key-holder loop | in the **key-holder**, never the server |
| **Per-member view-state** | W5 (hide) | store a self-scoped flag (or never sees it) | none |

The panel's central, repeated finding: **build the engine general from the first
commit.** ADR 0038's outbox is drawn too narrow (single-op, `block_id`-shaped).
Shipping a toggle-only outbox and retrofitting W1–W5 is the exact "wrong
primitive inherited by every later feature" the operator's brief warns against.

## 2. The spine: one outbox of typed ops → one `/mutations` endpoint

### 2.1 The op envelope

Generalize ADR 0038's `outbox(block_id, payload_blob)` into a **typed op**:

```jsonc
Op {
  op_id:        ULID,        // idempotency key + echo-suppression key + ordering key + dep ref
  type:         "toggle"|"upsertBlock"|"upsertCard"|"setMedia"|"delete"|"hide"|"submitContext",
  channel:      "delta"|"intent",          // derivable from type; routes server-side
  target:       { kind:"hub"|"section"|"block"|"card"|"context", id:ULID, parent?:{kind,id} },
  base_version: int|null,    // null = create (no If-Match); int = optimistic-concurrency precondition
  depends_on?:  [op_id],     // causal predecessor in the same flush (e.g. create section → add block)
  media_ref?:   upload_id,   // links to the binary upload queue (§9.3) when the op references a blob
  payload_blob: bytes,       // plaintext JSON @ M0, EncryptedEnvelope @ M1 — SERVER-OPAQUE for every type
}
```

The outbox stays **opaque to its own payloads** — `type` + `target.kind` are all
the sender needs to pick a route; the blob is ciphertext at M1 and the sender
never decodes it. This mirrors "the server relays an opaque block" on the client
side.

### 2.2 Endpoint surface

```
POST /families/{fid}/mutations          # the spine: an ordered BATCH of typed ops (delta + intent)
GET  /families/{fid}/sync?since=…        # unchanged read path; now also carries intents + view-state slices
GET  /families/{fid}/intents?status=…    # loop-only (intents:resolve): claim pending intents
POST /families/{fid}/intents/{id}:resolve # loop-only: flip status + stamp result_ref
# Blob upload/download: presigned URLs to object storage (§4-W1, §9.3) — bytes never transit the API
```

`POST /mutations` body = `{ ops:[Op,…] }` (capped per batch) → `200 { results:[
{op_id, status:"ok"|"precondition"|"gone"|"forbidden"|"notfound", version?,
result_ref?}, … ] }`. **Recommendation: one uniform op endpoint, not REST
sprawl** (`DELETE /blocks/{id}` + a media sub-path + `POST /intents` + …). The
only client is Dayfold's own KMP outbox drain — optimize for the batch flush, not
third-party REST ergonomics. The server's per-op handler is a `switch(type)` over
moves it already implements (`upsertBlock`, `upsertCard`, `softDeleteCard`, …),
collapsing the write surface, not expanding it. Keep `PUT /blocks/{id}` as a
deprecated single-op alias for the CLI/loop authoring path during transition.

### 2.3 Batch, ordering, idempotency

- **FIFO within a target**, **explicit `depends_on` across targets.** A backlog
  that built up offline (create a context section → add a block under it) replays
  in causal order; the server processes a batch in array order in one transaction
  so a child op's parent exists when it runs.
- **Partial failure is non-atomic except within a `depends_on` sub-graph.** One
  stale toggle (412) must not roll back an unrelated media update flushed in the
  same drain. A failed create fails its dependents (cascade) and rolls back their
  optimistic rows; independent ops still commit. Per-op results drive it.
- **`op_id` is the spine of idempotency.** Generalize ADR 0038's
  `write_idempotency` → **`op_log(family_id, op_id, result_ref, result_version,
  created_at)`** covering *all* op types — a replayed op returns the cached result
  instead of re-applying (no double version-bump, double-tombstone, double-intent).
  One ULID does four jobs: idempotency, echo-suppression, ordering, dep-reference.
  TTL-swept (extend the existing `/cron/sweep`, today auth-only).

### 2.4 The dumb-server proof (per op)

The invariant: the server touches only **cleartext** envelope/columns
(`family_id`, `id`, `version`, `updated_at`, `deleted_at`, provenance
`credential_id`/`writer_user_id`, visibility keys), **never `payload_blob`**.

| op | server move | reads payload? | reasons? |
|---|---|---|---|
| toggle / upsertBlock / upsertCard | upsert opaque payload, bump `version` | no | no |
| setMedia (W1) | upsert opaque payload **carrying media inside it** | no¹ | no |
| delete (W4) | set `deleted_at`, tombstone (cleartext column) | no | no |
| hide (W5) | store a self-scoped flag (cleartext) or never sees it | no | no |
| submitContext (W3) | INSERT opaque `intents` row, status `pending` | no | no — the **key-holder** reasons |

¹ **The one trap (P0, ADR-class):** media is host-allowlist/SVG/icon-validated
**server-side today** — `validateBlockPayloadMedia(type, payload)` reads *inside*
the block payload (`app.ts:575`), not just a separate column. At M1 (ciphertext) it
is structurally impossible. **Media must stay inside the opaque `payload_blob`, with
validation gated to plaintext-M0 and moved client-side at M1** (the client validates
before encrypting, mirroring client-side id-minting). Promoting media URLs to a
cleartext indexed column "so the server can validate/CDN them" **breaks
zero-knowledge** — do not. **Posture change to flag (F2):** at M1 the ADR-0036
host-allowlist + SVG + decode-bomb defenses become **client-side-only → advisory
against a hostile/modified client.** The only *server*-enforceable M1 boundary is
blob **size cap** (cleartext, via R2 `content-length-range` — the `06-storage.md`
mechanism) + **count** limits; content-type/host allowlist degrade to client-trust.
This materially weakens a guardrail-#3-adjacent control → an explicit **ADR 0036
amendment + operator-visible** decision, not a silent relocation.

### 2.5 Reserve the general primitive; build a minimal first slice (F3/F4/F11)

The adversarial round is right that "build the engine general from the first commit"
must not be read as "build the whole engine now" — that conflates the *cheap*
reservation (envelope shape) with the *expensive* build (batch endpoint, causal DAG,
two queues, R2). The split:

- **Reserve now (cheap, costly to retrofit):** the **typed-op envelope shape** — add
  a `type` discriminator (+ `target_kind`, a nullable `depends_on`) to ADR 0038's
  single-op outbox row so op types grow *additively*; the **generalized `op_log`**
  idempotency table; the cleartext **`created_by`/`author_kind`/`writer_user_id`**
  columns on blocks/cards; the **`content:delete`** scope; the **visibility-on-write
  + 410-on-tombstone** generalization (genuine security must-fixes); the **`x-e2e`**
  per-field rule; **`block_type`/`card_kind` ENUM→`text`**; the Kotlin codegen guard.
- **Build the minimal first slice:** the toggle (ADR 0038) on the reserved shape,
  plus the two *cheapest, highest-value* new features — **W4 delete** (reuses the
  existing soft-delete + tombstone; just add the scope + author gate) and **W5 hide**
  (**local-only**, zero server surface). These prove the engine adds member-write
  breadth without the heavy subsystems.
- **Defer behind their own slices/ADRs:** the **`/mutations` batch endpoint + the
  `depends_on` DAG** (no first-slice feature needs cross-target causality once
  member-created hubs and W3 are deferred — ship single-op `PUT` + a single
  `submitContext` path, reserve the batch shape); **W1 media + R2 + the image
  `expect/actual` subsystem + binary queue + decrypting Coil fetcher** (the largest,
  M1-shaped, highest-risk expansion — sequence it *last*); **W3 intents/LLM loop**
  (blocked on the constitution amendment anyway — reserve the `intents` table shape,
  defer the build); **W5 cross-device hide-sync** (local-only first).

So §9's "general outbox from day one" means the **table/envelope shape**, not the
batch-and-DAG engine. The conceptual spine (dumb-server-per-op, two channels, op_id
idempotency, delete=tombstone, hide=self-scoped, media-in-opaque-payload) is the
*design target*; the *first build* is toggle + delete + local-hide on the reserved
shape.

## 3. The two channels

**Channel A — direct content-delta (W1/W2/W4/W5 + toggle).** Server relays the
opaque row, bumps `version`, tombstones on delete, returns via `/sync`. No
reasoning. This is ADR 0038 generalized.

**Channel B — intent (W3).** The member submits raw material; the server
**enqueues** it opaquely; the **key-holding AI loop** pulls, decrypts, reasons,
and **authors result content via the normal content-write path** → the family
pulls it via `/sync`. Concrete `intents` table (ADR 0016 reserved only the names):

```sql
CREATE TYPE intent_status AS ENUM ('pending','processing','done','failed','cancelled');
CREATE TABLE intents (
  id            text NOT NULL,            -- = op_id (idempotency)
  family_id     text NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  created_by    text REFERENCES users(id),-- resolved submitter (cleartext provenance)
  kind          text NOT NULL DEFAULT 'add_context',  -- BOUNDED vocab (ADR 0016 §4 — not free-text chatbot)
  target_hub_id text,                      -- optional cleartext routing hint
  payload       jsonb,                     -- [E2E-ciphertext @ M1] {text, urls[], attachmentRefs[]}
  status        intent_status NOT NULL DEFAULT 'pending',  -- CLEARTEXT lifecycle (loop pulls on it)
  result_ref    jsonb,                     -- {kind,id}[] of authored output (cleartext, loop-stamped)
  error         text, version bigint NOT NULL DEFAULT 1,
  created_at timestamptz, updated_at timestamptz, deleted_at timestamptz,
  PRIMARY KEY (family_id, id)
);
-- images spill to object storage (intent_attachments.body_ref = encrypted blob KEY, never inline JSONB)
```

Loop claims pending via `SELECT … FOR UPDATE SKIP LOCKED` (no double-processing).
Fold the `intents` + per-member view-state streams into the **existing `/sync`
envelope** (like `places` was folded in) — one cursor, one drain; the
"submitted → being organized → done" transition rides the channel the client
already polls. W3 carries **no optimistic content** (the result doesn't exist
yet) — the intent row itself is the syncable "thinking…" object, retired by a
`source_context_id` the loop stamps on derived content (§4-W3).

## 4. Feature-by-feature

**W1 — update media (first binary egress Dayfold has ever had).** Today all media
is render-only external allowlisted URLs (ADR 0036); there is no upload path, no
blob store. Member photos need Dayfold-controlled storage = ADR 0036's reserved
**Phase 2**. Design: **client-side** process (decode → resize → **strip EXIF/GPS**
→ generate a **thumbnail** → encrypt full+thumb as separate blobs) → upload
**opaque ciphertext** to object storage → server issues signed URLs by cleartext
`(family_id, blob_id, version)`, **never decrypting or deriving**. The server
*cannot* generate thumbnails under E2EE — so the client must, at capture time.
**Object store = Cloudflare R2** (zero egress — the single highest-leverage cost
decision; storage is ~$0 at family scale, egress is the term that scales with
families×devices and R2 zeroes it). Reuse the `06-storage.md` upload-first →
confirm → signed-GET → unreferenced-key-GC substrate. Member-supplied URLs (in
W1/W3) reuse ADR 0036's hardened allowlist + parser **identically** (parser-
differential drift across server/client/CLI is the live risk — keep lock-step).
EXIF strip is a **hard guardrail**, not a nicety: a geotagged family photo would
otherwise smuggle a precise home coordinate into ciphertext that the geocoding
ADRs (0014/0028) worked to keep off the wire, into N device caches, with an
erasure footgun.

**W2 — author content (the scope-expanding one).** Today only the loop/CLI
authors; members *interact*. W2 makes members **authors** → re-opens
`created_by`/visibility/curator-propose-confirm questions ADR 0038 deferred by
going toggle-only. Required:
- **Author columns:** add `created_by` (resolved `user_id`, server-stamped) +
  `author_kind ∈ {loop,member}` to `blocks`/`briefing_cards` (today `created_by`
  exists on **hubs only** — there is no per-row author anchor for blocks/cards, so
  W2 edit/delete author-gating has nothing to key on). This is the substrate the
  whole authz matrix depends on.
- **The authorship-merge problem, dissolved structurally** (not with a CRDT):
  **invariant — the loop never edits a `member`-authored block; the member never
  authors-edits a `loop`-authored block (only interacts, e.g. toggles).** Member
  authoring creates *new* member-owned blocks; the loop enriches by adding
  *sibling* blocks. This keeps ADR 0038's clean member-mutable-vs-loop-
  authoritative asymmetry intact and avoids collaborative-rich-text CRDTs (Yjs/
  Automerge — no native KMP binding anyway; reserve only if real collaborative
  prose is ever wanted). **Enforcement (F6 — it must be a server rule, not loop
  discipline):** `author_kind` is **cleartext** (§5 Rule 6), so the server *can* and
  *must* gate it — **reject a loop-credential write to an existing `author_kind=member`
  row, and a member write to a `loop`-authored row's authored fields** (the toggle/
  state-mutation exception aside). Without the server gate it is only a convention a
  loop bug can violate — so it is an enforced invariant, keyed on the cleartext
  `author_kind`, added to the §8 must-fix list.
- **Authz (P0):** W2 **trips ADR 0030's deferred server-enforced audience
  intersection** (the "author-trusted card audience" posture explicitly assumed a
  single operator). A member allow-listed *read* on a restricted hub must not
  author into it or set `audience` beyond their own visibility (privilege
  escalation). Enforce **audience ⊆ caller visibility** + write-entitlement-to-
  author-into-restricted, server-side. **Recommend W2 v1 = author blocks/cards
  into existing visible hubs only; defer member-created *hubs*.**

**W3 — add context (AI-mediated; the gated one).** Channel B. Two hard gates the
panel flagged independently:
- **Constitution gate.** ADR 0016 §4 reserves **free-text conversational prompts**
  behind a future ADR **+ a constitution amendment** ("not an open-ended AI
  chatbot"). Literal free-form text **is** that reserved surface. **Ship W3 as
  structured / template-bounded "add context" (e.g. "add this to hub X"), not
  open free-text**, until the operator amends the constitution. Operator call.
- **Key-holder gate (P0 privacy).** Under E2EE the loop must **decrypt** the
  context → it must be a **key-holder**. A *hosted* Claude routine the server
  invokes **cannot decrypt without breaking E2EE** and trips guardrail #3 (routing
  family content through third-party LLMs needs disclosure). Options: **K1 operator
  machine** (M0/dogfood default, free, must be online), **K3 dedicated controlled
  host** (M1-correct for a real second family), **K4 hosted relaxed-E2EE** (a
  *different* privacy promise — reserved, ADR-gated, disclosure-bearing, never
  default). Recommend **key-holder-only; K4 reserved.**
- **Confused-deputy boundary (P0 security).** Member free-text feeds a
  key-holding loop that can take actions. The loop must treat submitted text as
  **data, never instructions**; any content it authors in response is constrained
  to **what the submitter could themselves see/write** (audience ⊆ submitter
  visibility); **no destructive loop side-effects** from member intents at MVP;
  member-supplied URL fetching is SSRF-isolated (the loop, not the device,
  fetching member URLs).
- **Cost (the budget-sensitive feature).** W3 LLM tokens are the only recurring
  per-use cost and scale **linearly with families**. Constants (operator-gated,
  spend-class): **Sonnet default** (Opus only for hard reasoning), **batch all
  pending intents per family per cadence** (amortizes the expensive pulled-context
  tokens — the biggest saver), **per-family daily submission cap**, **down-scale
  images to the thumbnail before the vision call**. ~$2–6/mo/family at dogfood;
  un-batched Opus-by-default is the cap-buster.
- **Correlation:** the loop stamps `source_context_id` on derived content so the
  client retires the "being organized" placeholder; a **TTL/give-up** prevents
  ghost placeholders if the loop never produces output.

**W4 — delete (destructive).** **Keep soft-delete + tombstone** (the existing
mechanism — `deleted_at` is cleartext, so delete is E2EE-clean and tombstones
drive keyset cache-eviction). Distinguish **operational delete** (tombstone +
scheduled purge) from **erasure-on-request** (guardrail #4: actually destroy
ciphertext **+ object-storage blobs** — the easy-to-forget part: a deleted card
with an uploaded image must delete the image + its thumbnails too, or you retained
the content you claim to have erased). Family-wide erasure can use
**crypto-shredding** (destroy the key). Authz: **`content:delete` is a distinct
scope** (carved out of `content:write` so a compromised member-write token can't
mass-delete); **member-authored content deletable by its author**; **loop-authored
content not member-deletable** at MVP; **owner is NOT a delete override** (ADR 0030
§7 — owner isn't even guaranteed read on a restricted hub; a break-glass admin
delete is a separate future ADR). A confirm/warn is a **client affordance** (delete
is the one op where a confirm is right — toggles ban it); the scope gate + `op_id`
idempotency are the real boundary.

**W5 — hide (reversible, per-member).** The most-debated model in the panel;
resolved below (§11). **Hide is per-member UI state, self-scoped (never affects
other members — a shared hide is a censorship vector), orthogonal to the ADR 0030
visibility ACL.** Start **local-only** (a client `hidden(entity_id, hidden_at)`
table, never synced) — zero server surface, zero leak, works offline, trivially
reversible. If cross-device hide-sync is wanted, **promote to a per-member server
channel** whose storage depends on grain: **resource-grain hide** (whole hub/card/
block) → a cleartext per-member `resource_hidden` table (same metadata-leak class
as the existing `resource_visibility`, acceptable); **item-grain hide** (within a
checklist) → **in-ciphertext payload** state (a cleartext `(user_id, item_id,
hidden)` table would be a server-readable who-did-what ledger — a landmine).
Hidden content still syncs as normal *permitted* rows (hide ≠ ACL); "show hidden"
is a client view filter; add **tombstone GC** so hidden/deleted rows don't bloat
the sync window forever.

## 5. Schema evolution & back-compat (the ruleset)

**Correction (adversarial round):** an earlier draft claimed "forward-compat is
broken — the Kotlin reader uses `ignoreUnknownKeys=false`." **That is wrong.** The
client's inbound-decode paths are **already lenient** (`SyncClient.kt:24`,
`ContentStore.kt:28`, `HubClient.kt:19`, `AuthClient.kt:34` all set
`ignoreUnknownKeys=true`; `BlockPayload` is a flat, all-nullable, lenient struct
whose comment already says "ignoreUnknownKeys keeps it forward-safe"). The only
strict reader is `Validate.kt:25`'s CLI **authoring** guard — exactly the
strict-authoring path Rule 2 says to *keep*. So client decode is forward-safe today;
the residual work is narrower than "every additive field breaks." The ruleset:

1. **Additive-only, forever.** New fields optional; old fields never repurposed.
   Removal/retype is a breaking change requiring a format-version bump + deprecation
   window. (Matches how `media` and typed-card `type`/`payload` were already added.)
2. **Keep strict-authoring; keep lenient-decode (already true client-side).** Keep
   `.strict()` on the **write/authoring** path (CLI `Validate.kt:25` + server PUT
   validation — a typo guard; the author is current-version). The **read/decode**
   path is already tolerant client-side. The residual gaps: (a) the **server's TS
   `.strict()`** (~18 occurrences in `apps/api/src/generated/content.ts`) is correct
   for *validating* authored writes but must **never** be applied when *relaying* an
   opaque payload (post-M1 the server can't parse ciphertext anyway, but at M0 a
   newer client's payload must pass through, not 422); (b) verify the **renderer**
   shows a calm placeholder for an unknown `block.type`/card kind rather than
   dropping the row (decode already survives — `HubBlock.type` is a plain `String`).
3. **Discriminated-union extensibility — unknown `type` → graceful skip, never
   crash.** Codegen must emit an `Unknown(type, raw)` arm so an old client retains
   and re-syncs an unknown block/card type and renders a calm "update to see this"
   placeholder.
4. **Migrate `block_type`/`card_kind` from PG `ENUM` to `text + CHECK`/lookup-FK
   before W2.** A PG enum is a one-way ratchet (`ALTER TYPE ADD VALUE`, non-
   transactional, unremovable); `hubs.type` is already free `text` precisely to
   dodge this — extend that decision.
5. **`version` (row counter) ≠ content-format version.** Reserve a `schema_v`
   (e.g. `payload.v`) now, additively, so a future breaking change can migrate-on-
   read by format version. Cheap now, expensive to retrofit.
6. **Every new field carries an `x-e2e` classification at design time** — content
   (ciphertext, e.g. intent `payload`, member block bodies, media) vs routing/state
   (cleartext, e.g. `hidden_at`, `intent.status`, `author_kind`, `created_by`, blob
   `mime`/`size`, `result_ref`). An unclassified field is a future re-encrypt
   migration — the exact pain ADR 0015 exists to avoid. **Hard rule: no new field
   lands without an `x-e2e` classification.**
7. **Close the CI gap:** TS codegen drift is guarded (`git diff --exit-code` on
   generated TS); **Kotlin (`kotlin-gen/`) is not** — add the guard before W1–W5
   add many fields across both targets. Also lockstep the TS-`z.any()`-payload-stub
   vs the full Kotlin union (they currently disagree on payload strictness).

**Back-compat directions:** *old app + new server* → relies on Rule 2 (skip
unknowns) + Rule 1 (never require a new field of an old client). *New app + old
content* → already safe by additive defaults (`hidden_at` NULL = visible,
`author_kind` DEFAULT `loop`). Client SQLDelight migrations are additive
`ADD COLUMN` (nullable/defaulted — SQLite can't add NOT NULL without default).
Reserve a server `content_format`/min-version header for CLI/skill negotiation
once a second author exists.

## 6. Storage & data transfer

- **Keep whole-block PUT** for content-deltas. Bandwidth waste of re-sending a
  block per toggle is **irrelevant at family scale** (you'd need millions of
  toggles/month to spend $1); the only amplifier is 412-retry storms, bounded by
  outbox **coalescing (latest-wins-per-block)** + the ≤3 retry cap. **Granular
  ops / op-log transport is reserved**, not built — the costly-to-retrofit piece
  (stable per-item ULID identity) is already locked by ADR 0038. Revisit trigger:
  observed 412-retry rate, not list size.
- **Blobs on R2** (§4-W1), opaque ciphertext, content-addressed; within-family
  dedupe only (cross-family dedupe needs convergent encryption — a known leak,
  not worth ~$0 storage savings). Content-addressed immutable blobs get ~1h
  cacheable signed URLs (Coil/CDN cache across polls); mutable refs ≤60s.
  **Refcount before delete (F12):** because two cards can reference the same
  content-addressed blob, erasure (§4-W4) must **decrement a refcount and delete the
  blob only at zero** — naive delete-on-card-erase breaks the other reference.
- **Two client queues:** the JSON outbox (small ops) + a binary `media_upload`
  queue (resumable, content-hash dedupe), linked by `media_ref`/`depends_on`.
  Image bytes are **files, never SQLite rows or redux state** (refs only).

## 7. Privacy / E2EE (cross-cutting)

Zero-knowledge holds for W1/W2/W4/W5 with a known, mostly-acceptable metadata-leak
surface; **W3 is where it can break** (loop placement, §4-W3). What the server
unavoidably learns (cleartext routing keys), and the verdict:

| What the server learns | Acceptable? | Mitigation |
|---|---|---|
| `family_id`, opaque ids, `version`, timestamps | yes (structural) | — |
| op counts / write cadence | mostly | client coalescing; calm cadence |
| `created_by`/`writer_user_id` per version ("who wrote") | yes — *intended* (the "✓ Dad" attestation; honest claim = server knows *that* Dad wrote block X at T, never *what*) | — |
| `visibility` + allow-list ("who-can-see-what") | yes but leaky — server learns private-hub existence + audience shape | unavoidable under ADR 0030's server filter; honest chip must not over-claim |
| blob sizes + derivative count (W1/W3) | partial — leaks "is a photo", rough resolution | **size-bucket/pad** blobs (mirrors ADR 0017's fixed-size rows) |
| ciphertext text length (W2/W3) | partial | pad to buckets |
| W3 pending-AI state, if a cleartext oracle | **minimize** — leaks "family is feeding the AI loop, how much" | keep coarse; let the loop decide unprocessed-ness |
| per-member who-did-what (hide/toggle/read) | **DANGER if naive** | keep **inside ciphertext** at item grain (§11) |

Cross-cutting must-dos: **client-side EXIF/GPS strip before encryption** (W1/W3);
**client-generated thumbnails** (server can't decode); **erasure purges ciphertext
+ blobs**, not just tombstones; **W3 = LLM processing of family content** → key-
holder structural guarantee (preferred) over a hosted policy-claim (guardrail #3,
disclosure-bearing). Honest-claim rule: **"we can't read your content" is per-
field, not blanket** — never drift into "we know nothing." For W3 the *same chip
string flips truth by loop placement* — bind the chip to the K1/K3-vs-K4 decision.

## 8. Security / ACL (cross-cutting)

**Permission posture:** visibility gates human reads/writes; scope gates credential
writes; **owner gets no content override** (ADR 0030 §7); **member-authored is
author-mutable, loop-authored is member-read-only** (toggle is the deliberate
interaction exception). The P0 ship-blockers (generalizing ADR 0038's two
must-fixes to all mutations + the new W2/W3/W4 holes):

1. **Visibility-on-write is missing on *every* content mutation** today (block,
   section, card, hub, delete) — safe only because the lone writer is the
   visibility-exempt household token. Add `hubVisible`/`cardVisible` to **all**
   member-write paths, before parent resolution; **collapse invisible +
   structurally-absent to a uniform 404** (close the existence oracle); 403 only
   for visible-but-scope-denied. Test matrix:
   own/family/restricted-visible/restricted-invisible → 200/200/200/**404**.
2. **No author-identity column on blocks/cards** — add `created_by`/`writer_user_id`
   (server-derived from JWT `sub`) before member authoring/delete ship.
3. **Audience-widening via member authoring (W2)** — enforce **audience ⊆ caller
   visibility** + write-entitlement-to-author-into-restricted (un-defers ADR 0030's
   server-enforced intersection).
4. **Resurrection via stale writes** — generalize ADR 0038's **410-on-tombstone**
   from blocks to sections/hubs (a stale offline W1/W2 write must not un-delete
   loop-removed content).
5. **`content:delete` as a separate scope** (blast-radius isolation) + **no owner
   override**; soft-delete stays.
6. **Confused-deputy via W3** — member input is data not instructions; loop actions
   constrained to submitter entitlement; no destructive loop side-effects;
   structured-not-free-text (constitution gate).

**Scopes:** add `content:delete` (widen the `Action` type `read|write` →
`+delete`); activate ADR 0016's `intents:write` (member) / `intents:resolve`
(loop); **reject** a separate `media:write` (media rides the content payload).
Least-privilege: loop = `content:read+write+delete`; member app =
`content:read+write+intents:write` (no delete by default); CLI = read-only.
**Rate limits (extend ADR 0025, the first content-write limits):** ~60/min write
per credential, ~240/min per family; **tighter ~10/min delete**; media + intent
caps (intent also LLM-cost-guards); 429-retryable so offline flush re-enqueues.
Provenance: server-attested `writer_user_id` (cleartext, audit-grade) vs client-
asserted per-item `doneBy`/labels (display-trust, never an authz input); write a
cleartext **audit row** for delete + visibility-change events.

## 9. Client engine (KMP)

1. **Build the outbox general from day one** (§2.1 typed envelope) — ADR 0038's
   toggle outbox is the *same* table, not a special case to retrofit. There is no
   sender today (`SyncEngine` has only the read drain + poll loop) — design the
   sender state machine now: `pending→blocked→inflight→acked→(echo)→delete`, with
   412→re-merge, 410→drop, 5xx/network→backoff, 401→refresh-on-mutex, cap→`failed`.
   Share the existing `syncMutex` (one ordering: push → immediate drain → echo-
   suppress own `op_id`); persist `attempts`/`next_attempt_at` (survive process
   death like the cursor).
2. **Surface op status via a `local_state` column on content tables** (NULL=synced
   |pending|failed), written by the optimistic apply, cleared by the inbound echo.
   The UI reads content tables only — **the outbox stays egress-only** (preserves
   ADR 0020's one-UI-reader thesis). Optimistic apply per op writes the **DB** (not
   a redux action); rollback on permanent failure (delete the optimistic row /
   un-delete via the soft-delete shadow / revert media to a shadow).
3. **A second binary upload queue** (`media_upload`: content-hash dedupe,
   resumable `bytes_sent`, staged→uploading→uploaded). **Capture/pick via
   `expect/actual` mirroring the proven `QrScanner` seam** (Android Photo Picker /
   iOS PHPicker / desktop FileDialog; gate on `mediaCaptureSupported`).
   **Client-side image processing is a mandatory new `expect/actual` subsystem** —
   no commonMain decode/resize/EXIF lib exists; per-platform decode→resize→strip→
   thumbnail→encrypt, built for M1 from the start. A **custom Coil fetcher decrypts
   `blob_ref` ciphertext in-process** (decrypted-disk-cache is an explicit privacy
   decision). Upload runs in **WorkManager/BGTask** (iOS `URLSession` background
   tasks), not the foreground poll.
4. **In-app editors (W2)** in commonMain: `BasicTextField` raw-markdown + live
   preview (existing renderer); todo builder minting item ULIDs at the edge; link
   add. **Dispatch on commit, not per keystroke** (per-keystroke DB writes thrash
   the reactive query — the #1 perf trap). The authorship-merge problem is
   dissolved by the §4-W2 invariant, not a CRDT.
5. **Testing:** the sender state machine + `merge()` are pure functions →
   commonMain unit tests (convergence, 412-retry, dep-ordering, partial failure);
   extend the MockEngine fake backend so the whole egress path runs headless;
   desktop media actual returns canned bytes so the queue is device-free testable.
   (Mind the `runBlocking<Unit>` JUnit gotcha; verify test counts.)

## 10. Performance & cost (vs the <$50/mo cap)

Dogfood (1 family, 6 members) ≈ **$4–30/mo, inside the cap** (Neon $0–5, Vercel
$0–20, R2 ~$0, Claude/W3 ~$2–6). The two terms that break the cap **at scale**:
(1) **W3 LLM tokens** (linear in families — mitigated by Sonnet-default + batch +
caps); (2) **Vercel poll *invocation count*** (not bytes — ~230k/family/mo;
mitigated by **push (FCM/APNs)**, gated on family count, the documented ADR 0020
upgrade with no dataflow change). Storage/egress is a non-issue on R2. Ranked
efficiency wins: R2 (zero egress) > push-before-scale > W3 batch/Sonnet/caps >
hide-off-the-hot-path + tombstone GC > content-addressed cacheable blob URLs >
client thumbnail reused as W3 vision input > outbox coalescing. **Don't:** ETag
`/sync` (cursor already minimal), granular transport now, cross-family dedupe,
server-side thumbnails.

## 10.5 Freshness spectrum — daily-poll through realtime on one cursor

The system must serve a **range of client cadences simultaneously** — some devices
sync daily (a wall tablet, a rarely-opened phone), some foreground-poll (~45s), some
will eventually want **near-realtime** — and a single family may mix them. The design
supports this **by construction on the read path**, with one load-bearing gap to
close and the realtime end to contract.

**What already works (the foundational property):** the keyset cursor over
`(updated_at, id)` + tombstones makes **cadence a client-side policy the server is
stateless about**. A client resumes from its own cursor after 45s, a day, or a month
— same query, no gaps, no double-pull; pagination (`has_more`/`next_cursor`) bounds
the large change-set a slow client pulls after a long gap. Daily, poll, background
(WorkManager/BGTask), and push-woken clients all use the **identical** read path.

**The cadence ladder — one read path, escalating triggers:**

| Tier | Trigger | Latency | Server infra | Fit |
|---|---|---|---|---|
| **Daily / background** | OS periodic (WorkManager/BGTask) | hours | none | next-open-is-fresh; nearly free (~1 invocation/day) |
| **Foreground poll (M0)** | ~45s timer + sync-on-resume + sync-after-push | ≤45s | none | the M0 default |
| **Push-woken (next)** | a contentless server **change-signal** → client runs the same sync | seconds | a write-time signal + debounce | the documented ADR 0020 upgrade |
| **Held-connection realtime (later)** | managed pub/sub (Ably/Pusher/Supabase Realtime) wake | sub-second | a vendor (doesn't fit Vercel serverless) | vendor-add; cursor sync underneath is unchanged |

**G1 — tombstone retention vs slow clients (must-decide; the real gap).** A daily/
month-offline client must still receive the **tombstone** for anything deleted while
away, or it shows ghost content forever — but tombstone **GC** must keep the sync
window bounded. These collide. **Rule (load-bearing): track a per-credential sync
watermark (last-synced cursor); GC only tombstones older than the *oldest active*
watermark; and if a client's cursor predates the GC horizon, force a full resync
(cursor → `-infinity` / snapshot).** Without this, "daily client" is unsafe. This is
ADR 0020's decision (still Proposed) — it must move from open-question to contract.

**G2 — push is a contentless, E2EE-safe *signal*, not a transport.** A push wake
carries **no content** ("something changed in family X — sync now"): the push service
(FCM/APNs) is an untrusted third party that can't see content under E2EE anyway, and
keeping content out of push preserves the **one read path** + the zero-knowledge
thesis. Realtime never becomes a second, leakier dataflow.

**G3 — the change-signal mechanism + debounce + jitter (net-new for push/realtime).**
Polling needs zero server push infra; push needs the server to know *when* a family
changed and emit a wake — a write-time hook (Postgres `LISTEN/NOTIFY`, or a per-family
`last_changed_at` an emitter reads, or enqueue-on-mutation), **debounced per family**
(the loop authoring 10 cards, or an offline flush, fires **one** wake, not ten) and
**jittered** (don't wake all 6 devices at once → a /sync thundering herd on Vercel).

**G4 — realtime ceiling on Vercel serverless.** Push-driven gives *seconds* and fits
the serverless model cheaply; **held-connection sub-second (WebSocket/SSE) does not
fit Vercel** (request-scoped functions) → needs a managed pub/sub vendor, and even
then it is only a faster *trigger* (the cursor sync is unchanged). "Eventually
realtime" realistically = **push-seconds**, with held-connection a later vendor-add.

**G5 — two-way under slow cadence (correct, with a note).** A daily client's writes
sit in the outbox and propagate on its next sync; its `base_version` can be far behind
→ more 412-merge-retries + a bigger merge surface, but **client-side LWW + `op_id`
idempotency are cadence-agnostic** (stamps converge regardless of *when* exchanged),
so correctness holds. Keep the burst-flush on **retry-friendly (429-retryable)** rate
limits, and be honest in UX that a slow client shows intentionally stale shared state
between syncs.

**Net:** the read-path cursor already supports the full spectrum; the engine is
cadence-agnostic and per-device cadence is independent. The two things that turn
"supports all cadences" from *implicit* into *contracted* are **G1** (tombstone-
retention + stale-cursor full-resync — required for daily clients to be safe) and
**G2/G3/G4** (push as a contentless debounced signal, with held-connection realtime
as a later vendor-add). **Contracted in `adr/0040-freshness-spectrum-and-tombstone-
retention.md` (Proposed)** — extends ADR 0020's R2/R3; M0 ships poll+background + the
stale-cursor-full-resync/retention-floor rule, with watermark-GC, push, and realtime
as deferred drop-ins.

## 11. Resolved cross-agent conflicts

The panel disagreed on three points; the synthesis resolves them:

- **Hide storage (3-way split).** *Engine/data agents* → per-member cleartext
  table; *privacy agent* → item-grain must be ciphertext; *perf agent* → model as
  a visibility-tombstone; *security agent* → must NOT reuse the visibility ACL
  (censorship vector). **Resolution:** hide is per-member, self-scoped, **orthogonal
  to the ACL**; **local-only first** (the dogfood default — zero server surface, zero
  leak). On promotion to cross-device sync, **grain decides storage** — item-grain =
  in-ciphertext (per-member preference). For resource-grain, the adversarial round
  (F5) is right that a cleartext `resource_hidden(user_id, res_id, hidden_at)` is a
  *behavioral* who-chose-to-hide ledger, **not** the same leak class as the static
  who-*can-see* ACL `resource_visibility` — asserting "same class" was wrong.
  **Corrected default: keep hide-sync in-ciphertext at *both* grains** (a per-member
  encrypted hidden-set), since local-only already covers the dogfood and cross-device
  hide-sync is a deferred nicety not worth a new behavioral-metadata leak. Perf's
  "off the hot path" is honored by tombstone GC, not by overloading visibility.
- **Media validation under E2EE.** Resolved in §2.4 / §4-W1: media inside the
  opaque payload; validation plaintext-M0-gated + client-side at M1; never a
  cleartext indexed URL column.
- **Granular vs whole-block transport.** Resolved in §6: whole-block now (bandwidth
  irrelevant; identity already reserved), granular reserved behind a 412-rate
  trigger — no re-model risk either way.

## 12. Decisions the operator must ratify (→ INB-26)

Per the confidence protocol — values-shaped / scope / spend / ADR-class, **never
agent-decided**:

1. **W3 free-text vs structured.** Open free-form "add context" trips ADR 0016 §4's
   reserved free-text line → needs a **constitution amendment**. Ship W3
   **structured/template-bounded** until then? *(Scope + constitution — the biggest
   gate.)*
2. **W3 AI-loop placement.** Key-holder-only (K1 operator → K3 controlled host,
   E2EE intact) vs reserved hosted K4 (relaxed-E2EE + guardrail-#3 disclosure).
   *Recommend key-holder-only; K4 reserved.* *(E2EE posture / guardrail #3.)*
3. **Object storage vendor = Cloudflare R2** (zero egress) vs staying all-Vercel
   (Blob, egress-billed). *Recommend R2.* *(Vendor + customer-data handling +
   spend.)*
4. **W2 member-authoring scope.** Author blocks/cards into existing visible hubs
   only (defer member-created hubs)? And accept the **loop-never-edits-member-
   blocks** invariant + server-enforced audience-intersection (un-defers ADR 0030)?
   *(Scope + ACL posture.)*
5. **W5 hide model.** Local-only first, promote to per-member server channel later
   with grain-dependent storage? *(Values/privacy.)*
6. **W3 cost constants** (Sonnet default, batch-per-family cadence, daily cap).
   *(Spend/pricing-class.)*

## 13. ADRs to write / amend

- **DRAFTED — `adr/0039-two-way-mutation-engine.md` (Proposed):** the typed-op spine
  + `/mutations` batch endpoint; two channels; op_log idempotency; delete=tombstone /
  hide=per-member-self-scoped-in-ciphertext; the visibility-on-write + 410-on-
  tombstone generalization; media-in-opaque-payload; the reserve-shape/build-minimal
  slice. Generalizes ADR 0038 (its first instance), not a supersession.
- **NEW — member authoring & content-management authz**: the permission matrix;
  `created_by`/`author_kind` columns; `content:delete` scope (extend ADR 0029, widen
  the `Action` type); no-owner-override; the loop-never-edits-member-blocks
  invariant; server-enforced audience-intersection (**supersede ADR 0030's author-
  trusted posture — its Revisit Trigger fires**).
- **NEW — member-uploaded encrypted media (ADR 0036 Phase 2)**: R2 object storage;
  client EXIF-strip + thumbnail-gen + encrypt; member-URL allowlist reuse + SSRF
  guard; client-side decode-bomb/SVG defense; blob erasure path.
- **ADR 0016 — activate intents for W3**: concrete `intents` table + endpoints;
  shared-outbox/two-channel design; the **constitution-amendment gate** for
  free-text; the key-holder-placement + confused-deputy boundary.
- **ADR 0015 — amend**: encrypted-blob path as a first-class content class; client
  thumbnail-gen + EXIF-strip; blob size/count in the metadata-leak list; crypto-
  shredding as the erasure primitive.
- **ADR 0025 — extend**: content-write/delete/media/intent rate-limit constants.
- **ADR 0020 — compose**: activate the outbox (general, typed) + the binary upload
  queue + WorkManager/BGTask media scheduling; `local_state` status column.
- **Schema-evolution ruleset (§5)** belongs in a spec + a short ADR (the strict-
  vs-lenient split, enum→text, x-e2e-mandatory, the CI Kotlin drift guard).

## 14. Open questions & P0 risks

**P0 (ship-blockers, in slice order):** (1) Visibility-on-write missing on all
mutations. (2) No author-identity column on **blocks** (cards already carry
`created_by` per `02-data-model.md:178`; blocks have neither `created_by` nor
`author_kind`) — add before member authoring/delete. (3) W2 audience-widening
(un-defer ADR 0030 server intersection). (4) W3 confused-deputy + key-holder
placement + constitution gate. (5) Resurrection-via-stale-write (generalize
410-on-tombstone). (6) Client-side EXIF/GPS strip + **refcount-before-delete**
blob erasure (else content is retained you claim to erase, or a shared
content-addressed blob is deleted out from under another card). (7)
`content:delete` carved from `content:write`. (8) M1 **media-validation degrades to
client-trust** (§4-W1) — define the server-enforceable boundary (size/count cap) +
flag the ADR-0036 weakening.

**Not P0 (corrected):** forward-compat — client decode is already lenient (§5); the
residual is TS-authoring discipline + an unknown-type renderer placeholder + the
Kotlin codegen drift guard. P1/P2, not a ship-blocker.

**Open questions:** R2-vs-Vercel vendor (OQ); W3 free-text constitution amendment
(OQ — blocks free-form W3); W3 key-holder location (OQ); decrypted-image disk-cache
policy (privacy); W3 placeholder TTL/give-up; tombstone-GC safety watermark (force
full-resync if a client cursor predates GC); intent retention TTL (values-shaped);
convergent-encryption dedupe trade (lean: skip); per-item-mode and per-member
individual-state grain (from the prior brainstorm) — confirm in-ciphertext.

---

### Provenance of this design
6-specialist panel (system design · data modeling/transfer · privacy/E2EE ·
security/ACL · mobile/KMP · performance/efficiency), each grounded in the repo +
cited. Strong independent convergence on: build the outbox general (typed ops, one
`/mutations`); two channels (delta vs key-holder intent); delete=tombstone /
hide=per-member-self-scoped / erasure=crypto-shred+blob-purge; member-uploaded
media = R2 + client-side encrypt/thumbnail/EXIF (ADR 0036 Phase 2); the
forward-compat `.strict()` break + the additive/`x-e2e`/enum→text evolution
ruleset; and the security must-fixes (visibility-on-write, audience-intersection,
410-on-tombstone, `content:delete`, confused-deputy). The three cross-agent
conflicts (hide storage, media validation, granular transport) are resolved in
§11. The full per-specialist reports are archived in the session task outputs.
