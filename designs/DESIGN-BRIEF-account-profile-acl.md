# Design Brief / Prompt — Account, Profile Avatars & Hub Roles/ACLs

**Hand this whole file to a fresh Claude Code session to produce the hi-fi
mockups.** Self-contained, but it **extends** two existing briefs rather than
repeating them — read them first:

- `DESIGN-BRIEF.md` — brand, tokens, M3 Expressive design system (authoritative
  visual language; **reuse its tokens, invent none**).
- `DESIGN-BRIEF-settings.md` — Settings/Account/Family IA already designed
  (Profile, Family members roster, owner/adult roles, monogram color picker).
  **This brief adds three deltas to that world — do not redesign the settings
  tree.**

Authoritative decisions: `../adr/0030-per-member-hub-and-card-visibility.md`
(read-visibility allow-list, author-always-permitted, **family-owner NOT
auto-permitted**), `../adr/0011-auth-hardened.md` (roles owner/adult),
`../adr/0036-external-image-url-privacy-allowlist.md` (bundled curated-icon +
host-allowlist pattern — the model for fun avatars), `../adr/0038` / `0039`
(two-way collaborative mutation — the engine a Contributor drives),
`../adr/0029-cli-token-resource-scoped-grants.md` (`hub:<id>:read|write`
vocabulary that per-hub member roles extend), `../specs/account-and-settings-design.md`.

> ⚠ **Companion decision required (not blocking mockups):** per-hub roles
> (Contributor/Co-owner) and **delegated** participant management **extend ADR
> 0030, which is immutable ("supersede, do not edit").** A **new ADR (an 0030
> successor)** must ratify: the three-tier hub role, that Contributor holds
> `hub:<id>:write`, that a Co-owner may manage the allow-list, and that the
> family-owner-not-auto-permitted privacy rule still holds. Flag this in the
> mockup notes; the operator will open the ADR alongside sign-off.

---

## 0. How to run this

> **You are designing the hi-fi UI/UX for Dayfold's account avatars and hub
> people/roles surfaces.** Use the `frontend-design` skill. Produce
> **interactive HTML/CSS prototypes** that faithfully emulate **Material 3
> Expressive**, matching the existing mockups' conventions **exactly** (§7).
> Build target is Compose Multiplatform — name things after M3 components
> (`ModalBottomSheet`, `SegmentedButton`, `ListItem`, `AssistChip`,
> `DropdownMenu`, `FilledTonalButton`, `AlertDialog`, `Badge`) so they map 1:1
> to Compose. Visuals only — no app logic. Light **and** dark for every view.

## 1. What this surface is

Two audiences, deliberately **two-tier — no global permission matrix** (the
operator explicitly wants control **without** an overwhelming RBAC console):

- **Family admin** = the family `owner`. Manages membership and family-wide
  settings. Lives in **Settings → Family** (already designed — this brief only
  adds **avatars** to the roster and sharpens the **role** treatment).
- **Hub admin** = a **hub owner** (the hub's author). Manages **who is in this
  hub and at what level**, on the hub itself (net-new — the core of this brief).

And one personalization surface:

- **Profile avatar** — a member personalizing how they appear everywhere.

Calm brand (behavioral restraint, vibrant pixels — inherit from `DESIGN-BRIEF.md`).
Family-warm, never childish, **never gamified** — this matters because fun
avatars must stay *tasteful/warm*, not a kids'-app sticker book.

## 2. Delta A — Profile avatars (monogram → fun avatar)

Today (settings brief): avatar = **monogram (initials) + color tint**; that
**stays the default and the universal fallback**. Photo upload is deferred
(**M-next**, object storage). This delta adds a **bundled fun-avatar set** in
between — no upload, no object storage, no external fetch (reuses ADR 0036's
bundled-asset + allowlist posture → privacy-safe, kid-safe, zero backend work).

Design an **avatar picker** (`ModalBottomSheet`, opened from Profile's account
header "Edit"):

- Top **`SegmentedButton`**: **Monogram** · **Fun avatars** · **Photo (disabled
  — "available later")**.
- **Monogram pane** — the existing color-tint grid over the member's initials
  (large live preview at top). This is `avatar_color`.
- **Fun avatars pane** — a warm, tasteful grid of **bundled illustrated
  avatars/icons** (think calm geometric characters, animals, nature motifs —
  NOT loud cartoon stickers). Selected = ring + check. This sets `avatar_ref`
  to a **bundled avatar id** (e.g. `avatar:fox-01`), **not** an object-storage
  key. ~16–24 options across a couple of visual families; show the grid full.
- **Fallback rule, made visible:** if `avatar_ref` is unset → monogram. Never a
  blank/broken image.
- Live preview updates as you pick; **explicit Save** (free-choice, not
  instant-apply), `AlertDialog`/sheet-confirm consistent with settings save
  semantics.
- Accessibility: each avatar has a name/label (screen readers hear "Fox avatar",
  not nothing); monogram keeps its existing "Account" label behavior.

Milestone tags: **Monogram = M1** (exists) · **Fun avatars = M1** (this delta) ·
**Photo = M-next** (slot present, disabled).

## 3. Delta B — Family members roster (avatars + role clarity)

The Family members list is **already designed** (settings brief §4.4). This
delta only:

- Renders the member's **chosen avatar** (fun/monogram) on each roster row and
  the account header — show the roster with a **mix** of monogram and fun
  avatars so both read well together.
- Sharpens the **family role** treatment: a quiet **`Badge`/`AssistChip`**
  `Owner` / `Adult` per row (read-only; role change = owner-only action, keep
  the existing "transfer ownership" flow — do not add a role-edit dropdown
  here). Keep it calm: role is context, not a control-heavy grid.

Show **owner view** and **adult view** (owner-only actions hidden for adults,
per the existing pattern). No new states beyond avatars + the role chip.

## 4. Delta C — Hub "People" management (the net-new core)

A **"People in this hub"** surface, reached from a hub's overflow/header
(`AssistChip` avatar-stack in the hub header → tap → this sheet). This makes
**ADR 0030's read-only allow-list explicit and delegable**, and adds two roles
above Viewer.

### 4.1 Per-hub roles (three tiers — plain-language, not jargon)

| Role | Can | Maps to |
|---|---|---|
| **Viewer** | See the hub + its cards | ADR 0030 restricted allow-list read grant (**exists, M1**) |
| **Contributor** | See **+ add/edit content** | holds `hub:<id>:write`, drives two-way engine (ADR 0038/0039) (**M-next**) |
| **Co-owner** | See + edit **+ manage people** (add/remove, set roles) | delegated hub admin (**M-next**) |

- The **hub owner (author)** is pinned at top with a distinct **`Owner`** badge,
  **not removable**, role locked. (Author = `hubs.created_by`, ADR 0030 §2a.)
- **Family owner is NOT auto-listed** — preserve ADR 0030's privacy call. A
  family owner who isn't a participant does not appear and cannot self-add;
  design the **"no access"** state for a restricted hub they're not in (see 4.4).
- Include a compact, **inline role explainer** (one line each, tap-to-expand) so
  the admin never faces an unexplained permission — this is the "simplified, not
  overwhelming" requirement made visual. No matrix, no capability checkboxes.

### 4.2 Visibility toggle (lives here, on the hub)

- **`SegmentedButton`: Family · Restricted** at the top of the sheet.
  - **Family** (default) — every active member is an implicit **Viewer**; the
    named list below governs **Contributor/Co-owner** only.
  - **Restricted** — only people on the list (any tier) can see it.
- Flipping **Family → Restricted** = a **confirm sheet** naming **who will lose
  access**, with the ADR 0030 revocation line: *"They'll stop seeing this hub on
  their next sync"* (the tombstone/keyset-revocation mechanic — surface it as
  reassurance, not mechanics). Flipping **Restricted → Family** widens access —
  lighter confirm.

### 4.3 Add / manage flow

- **Add people** → member picker listing family members **not yet on the hub**
  (with their avatars), each defaulting to **Viewer**, role adjustable via
  `DropdownMenu`.
- Each participant row: avatar · name · **role `DropdownMenu`** (Viewer /
  Contributor / Co-owner) · remove (trailing). Owner row excluded from both.
- **Guards:** cannot remove the last owner/co-owner is N/A (author is
  permanent); removing a Co-owner who added others is allowed (author remains).

### 4.4 Views to render (same hub, different viewer)

- **Hub-owner / Co-owner view** — full controls (toggle, add, role dropdowns,
  remove).
- **Contributor view** — sees the people list **read-only** (knows who's here)
  but no add/role/remove controls; a quiet "Only the hub owner can manage people"
  line.
- **Viewer view** — either a minimal read-only participant avatar-stack or the
  same read-only list; no controls.
- **No-access state** — a family member (incl. the family owner) opening a
  restricted hub they're not in: a calm "You don't have access to this hub"
  card, **no request-access CTA at MVP** (keep it simple; owner adds them).
- **First-restricted / empty** — a hub just flipped to Restricted with only the
  author on it: warm empty state prompting "Add people."

## 5. Delta D — Avatars across surfaces (small gallery)

Show the avatar treatment in situ so it's proven beyond Settings:

- **Hub header** — participant **avatar-stack** (`AssistChip`/overlapping
  circles, "+3") that opens the People sheet.
- **Two-way attribution** — the existing `Dad · just now` contributor line
  (spec `two-way-collaborative-content-design.md`) now with the contributor's
  avatar.
- **Roster + member picker** — mixed monogram/fun avatars.
- Confirm every surface degrades to monogram cleanly when `avatar_ref` is unset.

## 6. Load-bearing states (must all appear)

- **Family-owner-not-auto-permitted** — restricted-hub no-access state, incl.
  when the viewer *is* the family owner (the privacy differentiator; label it in
  the gallery note).
- **Role capability boundaries** — Contributor sees a read-only people list;
  only owner/co-owner get management controls. Make the difference visually
  unmistakable.
- **Author is permanent** — owner row locked, non-removable, distinct badge vs
  a delegated Co-owner.
- **Family → Restricted confirm** — names who loses access + reassurance copy.
- **Offline** (ADR 0020) — membership/visibility mutations are security-class:
  render **disabled with "You're offline — reconnect to change this"**; the
  people list still shows with "Updated · Nh ago". Avatar *picking* is local and
  works offline; **avatar Save** that syncs shows the same offline posture.
- **Fun-avatar tasteful bar** — the grid must read warm/adult-friendly, not a
  kids' sticker sheet (this is a brand gate, call it out).

## 7. Output files & conventions (match the repo exactly)

- **`designs/Account-ACL-Phone.dc.html`** — one parameterized phone component
  (props: `mode` = light/dark; `view` = `avatar-monogram` / `avatar-fun` /
  `profile` / `roster-owner` / `roster-adult` / `hub-people-owner` /
  `hub-people-contributor` / `hub-people-viewer` / `visibility-confirm` /
  `add-people` / `role-explainer` / `no-access` / `first-restricted` /
  `offline`). Mirror the `Settings-Phone.dc.html` / `Now-Phone.dc.html` pattern
  (one component, many views → one Compose screen).
- **`designs/Account-ACL.dc.html`** — gallery mounting every `mode × view` combo,
  light + dark, with milestone tags (M1 / M-next) per view.
- **Token parity** with `Design-System.dc.html` — reuse its CSS custom
  properties / color roles; **invent no new tokens**. Fun avatars use the
  existing tonal palette (avatar tints keyed to `avatar_color`).
- Add a row to `designs/README.md` and link the gallery from
  `designs/Index.dc.html`.
- `.dc.html` self-contained (inline CSS, shared `support.js` pattern if used
  elsewhere). Verify tag-balance and that **every** `mode × view` combo paints
  in both light and dark.

## 8. Definition of done

- All §2–§5 screens + all §6 states, light + dark, in `Account-ACL-Phone.dc.html`,
  mounted in `Account-ACL.dc.html` with M1/M-next tags.
- Avatar picker: monogram + fun panes complete, photo pane visibly disabled,
  fallback-to-monogram proven; ~16–24 tasteful fun avatars.
- Hub People sheet: three roles with inline explainer, visibility toggle +
  confirm, add/role/remove controls, and the owner/contributor/viewer/no-access
  views all distinct.
- Family-owner-not-auto-permitted no-access state present and labeled.
- Component names map 1:1 to Compose M3; token parity with the design system.
- README + Index updated. **Companion ADR flagged** in the gallery notes.
- Operator sign-off (ADR 0008) gates any build.
