# Scope & Access Model — Canonical Entity, Tenancy & Visibility Spec

> Status: **draft (authored 2026-06-23, schema/scope review; both adversarial
> rounds applied — R1 correctness, R2 simplification)** — binding on ADR 0030
> acceptance (INB-21).
> Consolidates the data model that today is spread across `specs/prototype/
> 02-data-model.md` (DDL), `specs/event-hubs-design.md`, `specs/auth-and-family-
> design.md`, ADR 0011/0022/0029, and **ADR 0030** (per-member visibility). This
> doc is the single answer to "what is the data, who owns it, who can read/write
> it, what scope is it in, and when does it expire." DDL stays authoritative in
> `02-data-model.md`; this doc is the model + access semantics over it.

---

## 1. The three scoping axes (the core mental model)

Dayfold has **three independent axes** that together decide whether a principal
may touch a piece of content. They were previously described in fragments; this
is the unified statement.

| Axis | Question it answers | Mechanism | Source |
|---|---|---|---|
| **Tenancy** | *Which family does this row belong to?* | `family_id` on every content row; middleware default-deny on non-membership | ADR 0011 |
| **Visibility** (human read) | *Which family **member** may read this row?* | `visibility` (`family`\|`restricted`) + `resource_visibility` allow-list | **ADR 0030** |
| **Scope** (credential write/read) | *Which **credential** (CLI/app token) may read/write this resource?* | `credential_grants` resource-qualified scope strings + `requireScope` gate | ADR 0029 |

**Resolution order on every request** (default-deny at each step):

```
1. Authn       — valid EdDSA access token → credential id (cid), not revoked   [ADR 0011 §8]
2. Tenancy     — caller has an ACTIVE membership in path :fid → else 404       [ADR 0011]
3. Scope       — credential's grants permit (resource, action) → else 403      [ADR 0029]  (writes + reads)
4. Visibility  — for READS, filter rows to those the member may see            [ADR 0030]  (reads only)
```

All four resolve **server-side, per request, from rows — never from token
claims** (ADR 0011 §8). A credential acts *on behalf of* a member (its
`user_id`), so a read is gated by BOTH the credential's scope (axis 3) AND the
member's visibility (axis 4). The M0 household token (`user_id NULL`) is treated
as fully-visible within its one family (no member to restrict against) — a
property that ends when real members exist (see §6 migration note).

---

## 2. Entity catalog (what the data is)

### 2.1 Tenancy & identity (the "who")

| Entity | Table | Role | Key fields |
|---|---|---|---|
| **Family** | `families` | The tenant. One per household. Owns all content + members. | `id`, `created_by`, soft-delete |
| **User** | `users` | A person. Belongs to N families. | `id`, `display_name` |
| **UserIdentity** | `user_identities` | OAuth binding (Google/Apple). 1 user ↔ N identities. | `(provider, provider_uid)` unique |
| **Membership** | `memberships` | M:N user↔family. The unit visibility allow-lists reference. | `(user_id, family_id)`, `role` (owner\|adult), `status` (pending\|active\|removed) |
| **Credential** | `credentials` | A bearer-token granule (app session or CLI). Acts on behalf of a user. | `id`, `user_id`, `family_scope`, `kind`, `revoked_at` |
| **CredentialGrant** | `credential_grants` *(ADR 0029, not yet built)* | What resources a credential may touch. | `(credential_id, scope)` |

### 2.2 Content (the "what") — two surfaces, one store

| Entity | Table | Surface | Notes |
|---|---|---|---|
| **BriefingCard** | `briefing_cards` | **"Now"** feed (live at M0) | Self-contained; own table. Time-windowed. Optional deep-link `target{hubId,…}` + `hub_ref`. |
| **Hub** | `hubs` | **Hubs** / Projects (authorable; render deferred) | Typed dossier; `status` planning\|active\|archived. Root of the content tree. |
| **Section** | `sections` | child of Hub | ordering container |
| **Block** | `blocks` | child of Section | typed (text/link/checklist/…); `payload` jsonb; `provenance` |
| **Place** | `places` | reusable geo (family content) | `lat/lng` ciphertext-at-rest (ADR 0028); never live position |
| **ResourceVisibility** | `resource_visibility` *(ADR 0030, NEW)* | access-control | allow-list rows for `restricted` hubs/cards |

**Canonical statement of the question the operator asked:**

> *Is "Now" a query of subscribed Hubs, or self-contained data with its own table?*

**Self-contained, own table (`briefing_cards`).** "Now" is **not** a subscription
query over hubs. The two surfaces are independent rows in independent tables. They
are coupled by **one-way reference edges only**, never a live join:

```
  briefing_cards.target_hub_id / hub_ref  ──(deep-link, resolved client-side)──▶  hubs
  hubs (imminent item: countdown/checklist due)  ──(operator/CLI authors a NEW card)──▶  briefing_cards
```

- **Now → Hub:** a card may carry `target{hubId,sectionId?,blockId?}` (tap-through
  navigation) and/or `hub_ref` (the adaptive "PART OF THIS HUB" pane). These are
  **unvalidated text**, resolved only against the caller's own tenant-scoped cache
  (no server cross-row dereference → no IDOR). See `02-data-model.md:109`.
- **Hub → Now:** there is **no automatic emission** at MVP. When a hub item
  becomes imminent, the **Claude skill pulls the family's hubs and authors a new
  `briefing_cards` row** that deep-links back (operator decision, schema review
  2026-06-23). No server cron, no client synthesis at MVP. (Server-derived or
  hybrid emission is a future option — see §7.)

So: "Now" has its own table, its own lifecycle, its own expiry. Hubs are a
separate store it points into.

---

## 3. Ownership & authorship (the "who owns / who wrote")

| Concept | Where it lives | Notes |
|---|---|---|
| **Family ownership** | `memberships.role = 'owner'`; `families.created_by` | ≥1 active owner invariant; governs family *management*, not content reads (ADR 0030 §7) |
| **Hub authorship** | `hubs.created_by` (NEW, ADR 0030 §2a) | Resolved **user id** (not credential), set at author time; the stable input to "author always sees." NULL = M0 token ⇒ no implicit grant. Round-1 review showed resolving authorship through child-block `provenance` fails closed on credential deletion / hub-with-no-blocks — hence a dedicated column. |
| **Block authorship** | `blocks.provenance.credential_id` (+ `source`, `at`) | jsonb audit at MVP; promote to typed FK only if audit queries get hot (`02-data-model.md:312`) |
| **Card authorship** | `briefing_cards.provenance` | same shape |
| **Write authority** | `credential_grants` scope (ADR 0029) | At MVP: operator/CLI push only. In-app authoring deferred (OQ-hub-collab). |

> *"Who are the owners? Who can write?"* — **Family** has owners (role). **Hubs**
> have authors (provenance), not owners, at MVP. **Writes** require a credential
> with `content:write` or `hub:<id>:write` scope — today that is the operator's
> CLI/Claude loop only; family members cannot write content in-app (read-only
> render).

---

## 4. Visibility & sharing (the "who can see / can it be shared")

### 4.1 Within a family (ADR 0030)

- **Default `family`:** all `active` members read it. (Today's only behavior.)
- **`restricted`:** readable only by the resource's `created_by` author + the
  `resource_visibility` allow-list. Covers medical / finance / co-parent-private /
  surprise hubs.
- **Cards carry a flat author-stamped `audience`** — no inheritance, no
  materialization, no fan-out (round-2 R2-1). The skill that emits a card from a
  hub already knows the hub's audience and stamps it directly; the card expires and
  is re-emitted. "A card can't out-expose its hub" is **author-trusted at MVP**
  (single operator), not server-enforced — `[pending-ratify]` (INB-21).
- **Enforcement:** read-path filter, omit-don't-403 (restricted rows the caller
  can't see are absent, not forbidden — existence undisclosed). The M0/CLI
  authoring token (`user_id NULL`) is **visibility-exempt by design** (it authors
  restricted content so must read it) — safe only while operator-held.

### 4.2 Across families (cross-tenant sharing)

> *"Can hubs be shared with anyone?"*

**No cross-family sharing at MVP, by design.** A hub belongs to exactly one
`family_id` and is never readable by another tenant. There is no "share this hub
with another family" primitive, and adding one is an ADR-class scope change
(it would breach the one-tenant-per-row model and the business-constitution
firewall). The only multi-party reach is **inviting a person into the family**
(they become a member and then see `family`-visibility content) — sharing is at
the *family-membership* grain, not the *hub* grain.

### 4.3 Sharing summary table

| "Share with…" | Supported? | Mechanism |
|---|---|---|
| Another **member of the same family** | Yes (default) | `visibility='family'` |
| A **subset** of family members | Yes (ADR 0030) | `visibility='restricted'` + allow-list |
| A person **not yet in the family** | Only by inviting them in | Invite → membership → family-visibility |
| **Another family / external party** | **No** | Out of scope; ADR-gated |
| **Public / unauthenticated** | **No** | Out of scope |

---

## 5. Lifecycle & auto-expiry (the "is data auto-expired")

| Data | Expiry mechanism | Hard-deleted? |
|---|---|---|
| **BriefingCard** | `expires_at` → dropped from active feed (server filter + client re-window). `not_before` gates surfacing. | **No** — row remains (soft-delete `deleted_at` on explicit delete) |
| **Hub / Section / Block** | `status` `active → archived` is **manual** (operator marks it); no auto-transition | No — soft-delete |
| **Invite** | `expires_at` + `max_uses`; sweep sets `status='expired'` | No (status flip) |
| **Device authorization** | `expires_at` ~10 min; reaper sweeps | **Yes** — auth ephemera hard-purged |
| **Refresh token** | absolute `expires_at` (30–60d) → forces re-auth; reuse-detect revokes lineage | rotation lineage |
| **ResourceVisibility** | cascades on hub hard-purge | with hub |

**Posture decision (operator, 2026-06-23): soft-delete-authoritative.** No
automatic TTL hard-delete of content at MVP. Guardrail 4 (honor delete-on-request)
is satisfied **at a single-operator dogfood by the operator's own DB/SQL access**
— a productized purge tool (CLI verb / admin endpoint + audit-log) is **deferred
until a non-operator family's data is held** (round-2 R2-7), the point at which the
obligation actually bites. Archived-hub retention/export policy stays open
(OQ-hub-archival); auto-retention is a later refinement.

---

## 6. Schema deltas this model requires

**DDL is authoritative in `02-data-model.md`** (CLAUDE.md priority rule — one home,
no drift). Semantic intent of the ADR 0030 additions (to be added there):

- `hubs.visibility` `'family'|'restricted'` (default `family`) + `hubs.created_by`
  (resolved `user_id`, nullable; NULL = M0 token → no implicit author grant).
- `briefing_cards.visibility` `'family'|'restricted'` (default `family`) +
  `briefing_cards.audience text[]` (permitted user ids when restricted). **No card
  `created_by`, no inheritance, no materialization** — the author stamps `audience`
  (round-2 R2-1/R2-3).
- `resource_visibility(family_id, hub_id, user_id)` — **hubs only**, no
  `resource_type` polymorphism (round-2 R2-2). Indexes
  `(family_id, user_id)` ["what can this member see"] and `(family_id, hub_id)`
  ["who can see this hub"].
- **Load-bearing trigger:** `AFTER INSERT/DELETE ON resource_visibility →
  UPDATE hubs SET updated_at=now() WHERE id=hub_id` (single target). Without it the
  `(family_id, updated_at, id)` cursor never re-surfaces a member-drop as a
  tombstone (round-1 P0-1).

ADR 0029 (designed, not built) adds `credential_grants`. These hub columns +
`resource_visibility` + the trigger are the only schema additions.

**Sync interaction (the load-bearing mechanic — round-1 review found the naive
version broken):**

- **Hub visibility flip / allow-list mutation → tombstone.** `/sync` is per-caller;
  the visibility filter runs **inside** the keyset query. A hub `family →
  restricted` flip advances `hubs.updated_at` (column write fires the `02` touch
  trigger) → the hub re-enters every member's page → the per-caller filter emits it
  as a **tombstone** to the now-excluded and a no-op to the still-permitted. **But
  an allow-list-member *drop* edits a *different table* (`resource_visibility`) and
  would NOT advance `hubs.updated_at`** → the excluded member's cursor never
  re-surfaces the hub → forbidden row persists forever. **Fix (mandatory):** the
  trigger above touches the hub on every `resource_visibility` mutation. (Cards
  need none of this: a card's `audience` only changes by re-authoring the card row,
  which advances its own `updated_at` naturally — round-2 R2-1.)
- **Full membership removal → client cache-wipe.** A `removed` member 404s at the
  tenancy gate and never syncs again, so no tombstone can reach them; their last
  cache (all family-visible content, incl. a departing co-parent's view) is stale.
  The **client MUST hard-wipe local cache on any tenancy 401/404 for its active
  family.** This is part of the access model, not an optimization.
- Both need explicit build-time test matrices (§8).

**M0 → M1 visibility migration note:** the M0 household token (`user_id NULL`)
sees everything. The moment real members exist, every pre-existing hub/card is
`visibility='family'` by the column default — correct (nothing becomes secret
retroactively). Restricting a resource is an explicit authored act thereafter.

---

## 7. The "Now ⟷ Hub" boundary (explicit, since it was the central question)

| | "Now" (`briefing_cards`) | "Hubs" (`hubs/sections/blocks`) |
|---|---|---|
| Own table? | **Yes** | Yes |
| Rendered at M0? | **Yes** (live) | No (authorable, render deferred) |
| Lifecycle | time-windowed (`not_before`/`expires_at`), ephemeral | persistent until archived |
| Populated by | operator/CLI authoring; **incl. cards derived from imminent hub items** | operator/CLI authoring |
| Reads from the other? | points *into* hubs (deep-link, client-side only) | does not read cards |
| Subscription/live-join? | **No** — never queries hubs at read time | — |
| Visibility | own (`family`, or `restricted`+`audience[]` stamped by author) | own (`family`\|`restricted`+allow-list) |

**MVP emission rule (operator-chosen):** the Claude skill pulls the family's
hubs, decides what's imminent, and **authors briefing cards** that deep-link
back. Stateless from the server's view; the server just stores what's pushed.
Future: a server job or hybrid may auto-derive cards (§ open item OQ-now-emission)
— deferred, not built.

---

## 8. New requirements / open items this spec surfaces

1. **Hub `visibility`/`created_by` columns + `resource_visibility` (hubs-only) +
   `briefing_cards.visibility`/`audience[]` + read-path filter** (ADR 0030) — build
   with the content-API slice, alongside ADR 0029's `credential_grants`/
   `requireScope` (they share the resource model; build together, not twice).
2. **`resource_visibility`-touch trigger → `hubs.updated_at`** (round-1 **P0-1**) —
   every allow-list mutation advances the hub's cursor; without it, member-drop
   never tombstones. Mandatory, with a test matrix.
3. **Visibility-aware `/sync` tombstone matrix** (round-1 P0-1) + **client
   cache-wipe on tenancy 401/404** (round-1 **P0-2**, membership removal) — both
   are correctness-critical revocation paths, each needs an explicit test.
4. **Content-API authoring carries visibility** — hub upsert: `visibility` +
   `audience[]`; card upsert: `visibility` + `audience[]` stamped by the skill
   (default `family`). **No materialization/inheritance/fan-out** (round-2 R2-1) —
   the skill stamps the card's audience directly. **Posture `[pending-ratify]`:**
   "card can't out-expose its hub" is author-trusted at MVP (INB-21).
5. **Hard-purge for delete-on-request** (Guardrail 4) — at a single-operator
   dogfood, the operator's own DB/SQL access satisfies the obligation; a
   productized purge tool (CLI verb / admin endpoint + audit-log entry) is
   **deferred until a non-operator family's data is held** (round-2 R2-7). Note it,
   don't build it in the first slice.
6. **Open: owner-visibility default** (ADR 0030 Revisit Trigger) — owner NOT
   auto-permitted on restricted resources (proposed) vs owner-sees-all. **Operator
   call** before ADR 0030 → Accepted (INB-21).
7. **Open: OQ-now-emission** — does Hub→Now stay manual (CLI-authored) or gain a
   server/hybrid deriver post-MVP? Logged; MVP = manual.

---

## 9. Cross-references

- DDL: `specs/prototype/02-data-model.md`
- API surface: `specs/prototype/03-api.md`
- Hubs design: `specs/event-hubs-design.md`
- Auth/tenancy: `specs/auth-and-family-design.md`, ADR 0011
- Credential scopes: ADR 0029
- Visibility: **ADR 0030**
- Typed content: ADR 0022, `specs/domain-model/schemas/content.schema.json`
- MVP boundary: `specs/mvp-feature-boundary.md`
