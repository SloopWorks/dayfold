# ADR 0053: Per-Hub Participation Roles (Viewer / Contributor / Co-owner) & Delegated Hub Management

## Status

**Proposed** 2026-07-10 (agent-drafted; operator-gated, not built). Companion to
the hi-fi design signed off under **ADR 0008** (`designs/Account-ACL.dc.html`,
`designs/DESIGN-BRIEF-account-profile-acl.md`). **Extends — does not supersede —
ADR 0030** (Per-Member Hub & Card Visibility, immutable). Composes with **ADR
0029** (CLI/token resource-scoped grants — `hub:<id>:read|write`), **ADR 0011**
(tenancy/roles owner|adult), **ADR 0038/0039** (two-way collaborative content
mutation — the write path a Contributor drives). Companion spec to update on
acceptance: `specs/domain-model/scope-and-access-model.md`.

## Context

ADR 0030 gave hubs a two-state visibility (`family` | `restricted`) plus a
read-only allow-list (`resource_visibility(family_id, hub_id, user_id)`), where
the **hub author is always implicitly permitted** and a **family owner is NOT
auto-permitted** (the privacy differentiator for co-parenting/eldercare). It
deliberately shipped **read-only** grants, **push/CLI authoring only**, and **no
in-app visibility editor** (item 5), and it explicitly declined a new role
(item 7): *"visibility is orthogonal to `role`; roles govern family management,
visibility governs content reads."*

Two things have since been asked for (operator, 2026-07-10 brainstorm) and
designed under ADR 0008:

1. **Hub owners should be able to let others *participate*, not just read** — i.e.
   contribute content to a hub, and delegate management of who is in it. ADR 0030
   only models *read* membership; "participate" needs a **write** capability and a
   **delegated-management** capability, neither of which today's flat allow-list
   expresses.
2. A **simplified admin** surface — the operator explicitly warned against an
   "overwhelming" permission matrix.

This forces a decision ADR 0030 postponed to its Revisit Trigger ("in-app
authoring / owner-managed visibility is a post-MVP slice"): **what participation
levels exist on a hub, and who may manage them.** It must be reconciled with ADR
0030 item 7 (no new *family* role) and with the three-axis access model (tenancy
/ visibility / scope) in `scope-and-access-model.md`.

## Decision

1. **A per-hub participation role — a new value on `resource_visibility`, NOT a
   new family role.** ADR 0030 item 7 stands unchanged: the `memberships.role`
   enum (`owner` | `adult`) is untouched and still governs *family management
   only*. The new role is a **per-resource attribute** on the existing allow-list
   table, a fourth column:
   `resource_visibility(family_id, hub_id, user_id, role)` where
   `role ∈ {'viewer','contributor','co_owner'}`. This is a **new attribute on an
   existing axis (Visibility, ADR 0030)**, not a new axis and not a family-role
   tier — the mental model stays "one resource identity, subjects grant-listed
   against it."

2. **Three levels, plain-language, capability-cumulative:**
   - **Viewer** — may *read* the hub and its cards. Exactly ADR 0030's existing
     read grant (the migration default — see item 7).
   - **Contributor** — Viewer **+ may add/edit content** in the hub via the
     two-way engine (ADR 0038/0039). "May write" = the member is entitled to a
     credential bearing `hub:<id>:write` (ADR 0029). See item 4.
   - **Co-owner** — Contributor **+ may manage people**: add/remove members on
     this hub, change their role, and flip the hub's `visibility`
     (`family`↔`restricted`). Delegated hub admin. See item 5.

3. **The hub author is a permanent, implicit Co-owner.** Resolved from
   `hubs.created_by` (ADR 0030 item 2a), the author is treated as `co_owner`
   without needing a `resource_visibility` row, is **non-removable**, and their
   level is **not editable**. UI shows a distinct **Owner** badge, separate from a
   delegated Co-owner. **NULL-author hubs** (the M0 household token, `user_id
   NULL`, ADR 0030 item 2a/4b) get **no implicit owner** — management comes only
   from explicit `co_owner` rows. Because the author is always a Co-owner, there
   is always ≥1 manager: **no "last-manager" orphan state** is possible on an
   authored hub.

4. **Contributor write still resolves through ADR 0029 scope — the role is the
   *source*, `requireScope` is the *gate*.** The three-axis model is preserved:
   visibility (axis 4) gates human *reads*; credential scope (axis 3) gates
   *writes*. A Contributor/Co-owner's app session is minted with (or has added)
   `hub:<id>:write`; the write path enforces `requireScope('hub:<id>:write')` as
   today, AND the server independently confirms the acting member's
   `resource_visibility.role ∈ {contributor, co_owner}` (or authorship) as the
   durable source of truth (belt-and-suspenders — a credential must not out-grant
   its member's role). A **Viewer never receives `hub:<id>:write`.**

5. **Only a Co-owner or the author may manage participants or flip visibility.**
   Setting/relaxing restriction (ADR 0030 item 6) and any `resource_visibility`
   mutation (add / remove / role-change) require the actor be `co_owner` or the
   author. **The ADR 0030 revocation mechanic is preserved verbatim:** every such
   mutation, and every `visibility` flip, **MUST touch the hub's `updated_at`**
   (the existing DB trigger) so the keyset `/sync` cursor re-surfaces the hub as a
   **tombstone** to a now-excluded or now-downgraded member. A **downgrade
   contributor→viewer** is a revocation of write, not read, so it need not tombstone
   the hub, but MUST invalidate/re-mint that member's `hub:<id>:write` scope
   (ADR 0029) — treat a role *decrease* as a credential-grant revocation event.

6. **Family-owner-not-auto-permitted is preserved (ADR 0030's values call).** A
   family `owner` gains neither read, write, nor management on a hub by virtue of
   being owner — only by being the author or holding a `resource_visibility` row.
   The designed **"no access" state explicitly covers the family owner** opening a
   restricted hub they are not on (omit-don't-403, no request-access CTA at MVP).

7. **Roles compose with — do not replace — the two visibility states.**
   `visibility` still means what ADR 0030 says for *reads*:
   - **`family`** — every `active` member is an implicit **Viewer** (read). A
     `resource_visibility` row on a `family` hub therefore only ever *elevates* a
     named member to `contributor`/`co_owner` (grants write/manage); it never
     restricts reads.
   - **`restricted`** — only the author + allow-listed members (any role) may
     read; role additionally grants write/manage as above.
   Contribution is **never** implicitly granted by `family` visibility: writing
   requires an explicit `contributor`/`co_owner` grant (or authorship), even on a
   family-visible hub. **Migration:** every existing ADR 0030 allow-list row
   becomes `role='viewer'` — byte-for-byte the current read-only semantics; no
   behavior change on upgrade.

8. **Cards are unchanged — roles are hubs-only.** Like the ADR 0030 allow-list,
   participation roles apply to **hubs only**. Cards keep their flat,
   author-stamped `audience` (ADR 0030 item 3, ephemeral, author-trusted); a
   Contributor emitting a card stamps its audience through the same trusted
   author path. No card-level roles, no fan-out.

9. **Milestone posture (design-first now, build gated).** **Viewer** = ADR 0030's
   MVP read allow-list, already in the boundary. **Contributor + Co-owner +
   in-app People management** = the post-MVP in-app-authoring slice ADR 0030
   deferred, now *designed* (ADR 0008) but **not built** — it lands with in-app
   two-way authoring (ADR 0038/0039). The mockup tags this M1 vs M-next
   accordingly.

## Rationale

- **Reuses ADR 0030's table + ADR 0029's vocabulary instead of a parallel ACL.**
  A fourth column on `resource_visibility` and the existing `hub:<id>:write`
  scope express "participate" with no new entity, no group system, no
  per-field grants, and no RLS — matching 0030's "two states, not arbitrary ACLs"
  restraint at household scale (≤ a handful of adults).
- **Keeps ADR 0030 item 7 true.** The family `role` enum is genuinely untouched;
  the new role is a per-resource participation attribute, a different thing at a
  different granularity. This avoids the "owner sees all" flat model 0030
  deliberately rejected.
- **Three tiers, not a matrix.** Viewer/Contributor/Co-owner is the smallest set
  that expresses read / contribute / delegate — the operator's "control without
  overwhelm." An inline one-line-per-role explainer (designed) keeps it legible
  without a capability grid.
- **Author-permanent-Co-owner removes the orphan-hub failure mode** and gives the
  delegation a stable root of trust surviving credential rotation (ADR 0030 2a).
- **Write-through-scope, not through-visibility,** keeps the three-axis model
  intact and lets the existing `requireScope` gate do the enforcement — the role
  is where the entitlement is *authored*, scope is where it is *checked*.

**Alternatives rejected:** (a) **read-only allow-list only, no contribute tier** —
the literal ADR-0030-status-quo; fails the "participate" ask. (b) **a new family
role (e.g. `contributor` in `memberships.role`)** — violates 0030 item 7,
conflates family-management with per-hub participation, and a member's level
should differ per hub. (c) **unified capability matrix (members × hubs ×
capabilities)** — the "overwhelming" option the operator warned against;
over-built for the tenant size. (d) **grant write via the visibility filter
(axis 4)** — collapses the read/write axis separation the access model depends on.

## Consequences

Positive:
- Hub owners can delegate participation and management without the family owner
  becoming a universal admin — the co-parent/eldercare privacy posture holds
  while collaboration becomes possible.
- One column + the existing scope string; cheap, and the migration is a no-op on
  current data (all rows → `viewer`).
- The design (ADR 0008) and the access model gain a single coherent story for
  "who may read / contribute / manage a hub."

Negative:
- The write path now has **two sources that must agree** — the credential's
  `hub:<id>:write` scope (axis 3) and the member's `resource_visibility.role`
  (source of truth). Minting/revoking the scope on role change is a new
  invariant with its own test matrix (esp. the **contributor→viewer downgrade =
  write-scope revocation** edge, item 5).
- The ADR 0030 revocation/tombstone mechanic now also fires on **role changes**,
  not just add/remove/flip — the `updated_at`-touch trigger's coverage must
  extend to `role` UPDATEs (test matrix item).
- In-app authoring (ADR 0038/0039) becomes a hard dependency for Contributor/
  Co-owner to be *usable*; Viewer ships independently.

Neutral:
- `memberships.role` enum and token shape unchanged (role resolved server-side
  per request from rows, like ADR 0029 scopes and ADR 0030 visibility).
- Cards untouched.

## Revisit Trigger

- **Server-enforced write intersection** (a Contributor's write cannot out-expose
  a hub) becomes required at the same point ADR 0030's card posture flips — when
  multi-author, non-operator authoring is trusted less than the single dogfood
  operator.
- If households routinely exceed a handful of adults, or need finer than three
  tiers (e.g. per-section or time-boxed grants), reconsider the flat three-role
  model.
- If the operator elects a **transparent-household** model ("family owner sees
  all"), items 3/6 flip — a small change, but it reverses ADR 0030's values call
  and must be an explicit decision.
- If the two-source write authorization (scope + role) proves error-prone in
  practice, collapse to a single derivation (role → scope, computed) rather than
  two stored facts.
