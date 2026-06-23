# ADR 0030: Per-Member Hub & Card Visibility — Restricted Resources at MVP

## Status

**Proposed** 2026-06-23 (operator-directed in the schema/scope review; operator
chose "per-hub visibility at MVP" over all-members-see-all). Extends — does not
supersede — **ADR 0011** (Auth & Family-Tenancy, Hardened) and **ADR 0006**
(Event Hubs). Composes with **ADR 0029** (CLI/token resource-scoped grants) and
**ADR 0022** (typed content / `briefing_cards`). Resolves review-gap **G3**
(`context/open-questions.md` "Deferred by design"). Companion spec:
`specs/domain-model/scope-and-access-model.md`.

## Context

The validated wedge (`CLAUDE.md`, validation round 1) is a **multi-member
family-tenant briefing that no native OS ships**. Today the only access boundary
is the family: every `active` member sees every hub and every card in the family
(`family_id` scoping, ADR 0011 §default-deny). G3 ("per-hub / per-member
visibility, esp. sensitive hubs") was deferred at prototype scope (ADR 0007).

The deferral undercuts the wedge. A multi-member family has content one member
should not surface to another within the same tenant: a medical hub, a
divorce/finance hub in a co-parenting household, a surprise-party hub the subject
must not see. Without a visibility primitive, the family is forced to be a single
trust domain — which is exactly the flat model the incumbents already ship. The
differentiator only exists if a family member's "Now" can legitimately differ
from another's.

ADR 0029 already introduced a resource-qualified permission vocabulary
(`hub:<id>:read|write`) for **credential** grants (which CLI/loop token may touch
which resource). That vocabulary must extend to **members** (which human may read
which resource) rather than spawning a second, divergent ACL system. The two are
different subjects over the **same resource identity**.

A decision is needed now because it shapes the content schema (a visibility
column + an allow-list table) and the read path (every content query gains a
visibility filter), and the operator wants it in the MVP boundary — cheap to add
before real multi-member data exists, expensive to retrofit onto a flat store.

## Decision

1. **Two visibility states per resource.** Every hub and every card carries a
   `visibility`:
   - **`family`** (default) — all `active` members of the family may read it.
     Preserves today's behavior for the common case (one tap, no friction).
   - **`restricted`** — readable only by an explicit allow-list of members.

2. **Allow-list = `resource_visibility(family_id, hub_id, user_id)` — hubs only.**
   One row per (restricted hub, permitted member). The hub's **author is always
   implicitly permitted**, resolved from a **stable `hubs.created_by` user id**
   (item 2a), not from child-block provenance; an owner-role member is *not*
   auto-granted (visibility is a privacy control, and the owner managing the
   family ≠ the owner being entitled to read a co-parent's private hub — see
   Consequences). The list names additional adults who may read. **A mutation of
   any `resource_visibility` row (or a hub's `visibility` flip) MUST touch the
   hub's `updated_at`** (DB trigger), so the change re-enters every member's keyset
   `/sync` page and a now-excluded member receives the hub as a **tombstone** —
   otherwise the `(family_id, updated_at, id)` cursor never re-surfaces it and the
   forbidden row persists in that member's local cache (the load-bearing
   revocation mechanic). Single-target trigger (hubs), no polymorphism.

2a. **Hub author = a resolved `hubs.created_by` user id on the hub itself.** Set
   at author time to a **resolved `user_id`** (not a credential id), so "author
   always sees" keys on a stable identity that survives credential
   rotation/deletion and works for hubs with zero or mixed-provenance blocks.
   **NULL author** (the M0 household token, `user_id NULL`) ⇒ **no implicit author
   grant**; such a hub's audience comes only from `resource_visibility` + the
   `family` default. Do not resolve authorship through `blocks.provenance` for the
   visibility check. (Cards get no `created_by` — see item 3.)

3. **Cards carry a flat, author-stamped `audience` — no inheritance, no
   materialization, no fan-out.** Cards are **ephemeral** (`expires_at`) and
   **operator/skill-authored**: the skill that emits a card from a hub *already
   knows that hub's audience*, so it **stamps the matching `audience` directly onto
   the card** (or leaves the card `family`). There is no read-time card→hub join
   (the model forbids server cross-row deref anyway), no write-time materialization
   resolver, and no re-materialization fan-out when a hub's visibility changes —
   the card simply expires and is re-emitted by the next loop with the current
   audience. The card schema is `visibility 'family'|'restricted'` + an
   `audience text[]` (the permitted user ids when restricted). **At MVP the "a card
   must not out-expose its hub" invariant is the trusted author's (skill's)
   responsibility, enforced at author time, not a server-side intersection.** This
   is a deliberate posture choice for a single-operator dogfood (`[pending-ratify]`,
   INB-21); server-enforced intersection re-enters via the Revisit Trigger when
   in-app/multi-author authoring lands.

4. **Enforcement = one read-path filter, default-deny, resolved per request.**
   The tenancy middleware (ADR 0011) is extended: after resolving the caller's
   `active` membership, every content read (`GET /…/cards`, `/…/sync`, future hub
   reads) filters to rows where `visibility='family'` **OR** the caller is
   permitted — for a hub, in its `resource_visibility` allow-list or its
   `created_by` author; for a card, in its `audience[]`. The filter runs
   **inside** the sync query so the keyset cursor and tombstone set stay
   consistent. Restricted resources the caller can't see are **omitted, not
   403'd** — their existence is not disclosed (uniform-absence, mirroring the
   invite/device 404 posture). This composes with ADR 0029's `requireScope`
   write-gate: **visibility gates human reads; scope gates credential writes**;
   both key on `hub:<id>` / the resource identity.

4a. **Membership-revocation cache wipe (client).** The keyset stream cannot carry
   a "you were removed" signal to a caller who can no longer call (a `removed`
   member 404s at the tenancy gate → never syncs again → no tombstones → full
   stale cache, including a departing co-parent). Therefore the client MUST
   **hard-wipe its local content cache on any tenancy 401/404 for its active
   family** ("you no longer belong" = cache-poison). Server-side revocation alone
   is insufficient; this client behavior is part of the access model, not an
   optimization.

4b. **The M0 household / CLI authoring token is visibility-exempt by design.** It
   has `user_id NULL` and authors restricted content, so it must read it — it
   bypasses the visibility filter. This is acceptable **only** because it is a
   single operator-held secret. The moment any non-operator credential is minted
   it MUST carry a real `user_id` and pass through the visibility filter; the
   NULL-user god-token must then be retired or scoped (Revisit Trigger).

5. **Authoring sets visibility (push/CLI at MVP).** Because in-app hub authoring
   is deferred (OQ-hub-collab, push-only at MVP), visibility is **authored via the
   content API**: the upsert payload for a hub/card accepts
   `visibility: "family" | "restricted"` and, when restricted, an
   `audience: [user_id, …]` allow-list. The Claude skill that authors content sets
   it. No in-app visibility editor at MVP (owner-managed visibility UI is a
   post-MVP slice, revisit trigger below).

6. **Who may set/relax restriction.** Setting or widening a resource's audience
   requires a credential with **write** scope on that resource (ADR 0029) AND the
   acting member must already be permitted on it (author or allow-listed). At MVP,
   authoring is operator/CLI-only, so this collapses to "the operator authors
   visibility"; the rule is stated now so the in-app slice inherits it.

7. **No new role.** Visibility is orthogonal to `role` (owner/adult). Roles govern
   *family management* (approve members, mint invites, transfer ownership);
   visibility governs *content reads*. An owner does not gain read access to a
   restricted resource by virtue of being owner — they gain it by authoring it or
   being allow-listed. (Family-management actions never require reading restricted
   content.)

## Rationale

- **Reuses the ADR 0029 resource vocabulary** instead of inventing a parallel
  ACL. One resource identity (`hub:<id>`), two subjects (member-read,
  credential-write), one mental model.
- **Two states, not arbitrary ACLs.** `family` | `restricted`-with-allow-list
  covers the real cases (sensitive hub, surprise, co-parent privacy) without
  per-field grants, groups, or role hierarchies — all rejected as over-built for a
  household tenant of ≤ a handful of adults.
- **Cards stamp their own audience instead of inheriting hub visibility.** Cards
  are ephemeral and authored by the same trusted skill that reads the hub, so the
  skill stamps the right audience at emit time. This removes the read-time
  card→hub join (which the model forbids anyway), the write-time materialization
  resolver, the re-materialization fan-out, and the card↔hub intersection
  edge-cases — all of which existed only to police an inheritance the author can
  perform directly. The cost (server no longer *guarantees* a card can't name a
  hidden hub) is acceptable for a single-operator dogfood and is the documented
  Revisit Trigger.
- **Omit-don't-403** matches the existing uniform-absence security posture and
  avoids confirming a restricted hub exists.
- **Owner-not-auto-permitted** is the values-shaped call: in a co-parenting or
  eldercare household the account "owner" is a logistics role, not an entitlement
  to every member's private content. Auto-granting the owner would reintroduce the
  flat model for the one member most likely to be the counter-party. (Flagged for
  operator confirmation — see Revisit Trigger; flip to "owner always sees all" is a
  one-line change if the operator wants a transparent-household model instead.)

Alternatives rejected: (a) **defer to post-MVP** (the chosen-against option) —
ships the wedge without its differentiator; (b) **full per-field/RLS ACL** —
Postgres row-level-security policies per member; over-built, hard to reason about,
and the read-path app filter is sufficient at household scale; (c) **role-based
visibility** (e.g. "owner-only" tier) — too coarse; doesn't cover co-parent
privacy where two adults are both non-owners or both owners.

## Consequences

Positive:
- The multi-member wedge gets its actual differentiator: a member's "Now" can
  legitimately differ from another's.
- Sensitive content (medical, finance, surprises) has a real home; supports the
  co-parenting / eldercare niches named in OQ-niche.
- One unified resource-permission model across human-read and credential-write.
- Cheap to land now (one column + one table + one filter) before multi-member
  data exists.

Negative:
- Every content read path gains a visibility filter (small; indexed on
  `(family_id, resource_type, resource_id)` and `(family_id, user_id)`).
- **The revocation mechanic is the load-bearing risk and has two parts** (round-1
  review found both fail if naive): (i) `resource_visibility` mutations and hub
  `visibility` flips MUST touch the hub's `updated_at` (item 2) so the keyset
  cursor re-surfaces the row as a tombstone to newly-excluded members — an
  allow-list delete on a *separate table* would otherwise never advance the hub's
  cursor; (ii) full membership removal needs the client cache-wipe (item 4a)
  because a removed member never syncs again. Both must have explicit test
  matrices.
- Requires a resolved `hubs.created_by` user id — a small schema add, the correct
  fix for author-identity surviving credential rotation.
- **Posture choice (round-2):** the "a card can't out-expose its hub" guarantee is
  author-trusted at MVP, not server-enforced. Correct for a single-operator
  dogfood; re-adding server enforcement when multi-author authoring lands is the
  documented Revisit Trigger (`[pending-ratify]`, INB-21).
- Authoring complexity: the content API and Claude skill must carry
  `visibility`/`audience`. Default `family` keeps the common path unchanged.
- `audience` references `user_id`s, which the author (operator via CLI) must know
  — at MVP the operator authors for their own household, so this is acceptable; an
  in-app picker is the post-MVP ergonomic fix.

Neutral:
- Token shape unchanged (visibility resolved server-side per request, like ADR
  0029 scopes — never in the token).
- No new role; `role` enum untouched.

## Revisit Trigger

- **Operator confirms the owner-visibility default** (owner-NOT-auto-permitted vs
  owner-sees-all transparent-household model) — this ADR proposes the former;
  flipping is a one-line filter change and does not require superseding if caught
  before Accepted.
- Dogfooding shows two states are insufficient (need groups, or per-section
  visibility within a hub) → a new ADR adds granularity.
- In-app visibility management ships → the "who may set restriction" rule (item 6)
  gets a UI and may need a member-facing audit ("who can see this?").
- A security review finds the read-path filter bypassable, or the
  family→restricted tombstone mechanic leaks prior content.
- E2EE (ADR 0015) lands → restricted content's key distribution must match the
  allow-list (the per-member content-key wrap maps onto `resource_visibility`).
