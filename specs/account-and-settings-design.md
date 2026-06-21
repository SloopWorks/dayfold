# Account, Profile, Preferences & Settings — Design (Draft)

> **Status: Draft / pre-spec (2026-06-21).** Unified Settings IA that absorbs
> the auth/family/invite/device/export-delete surfaces of
> `specs/auth-and-family-design.md` (ADR 0011) into one settings tree, and
> adds the net-new personal-preferences + profile surfaces. Authored via a
> 4-agent definition pass + 2-round adversarial review (correctness +
> simplification). Design-first input (ADR 0008) for the settings surface —
> hand §10 to Claude Design. **Privacy/storage posture is split out to
> Proposed ADR 0024** (this spec references it; the plain storage-shape choice
> is a HIGH-confidence build-spec decision recorded in §6, not ADR-class).
>
> **Horizon:** full vision, **milestone-tagged**. Tags: **[M1]** ships with the
> auth/family milestone (S5/S6, ADR 0021/0023 — Google+Apple only); **[M-next]**
> fast-follow; **[post-MVP]** deferred / evidence-gated. The **M1-minimal set**
> (§9) is the smallest coherent first shippable; everything else is documented
> here so design-first is satisfied once, but is *not* built at M1.
>
> Settings is **a separate milestone after the M0 render prototype** — it does
> not re-spec or rework hardened auth flows (ADR 0011); it re-routes them.

## Decisions (locked by this spec + operator)

- **Unify everything** into one Settings IA; auth/family/device/export screens
  become detail destinations, not redesigned flows.
- **Three preference scopes** — per-DEVICE / per-USER / per-FAMILY — with a
  crisp assignment rule (§2). **Scope rule:** a setting is per-FAMILY *only if*
  a wrong per-user value would make two members **disagree about reality**
  (timezone is the canonical case).
- **Storage = typed columns** (one row per user, one row per family), **not**
  per-key EAV (§6). Per-row `version` fits the existing sync contract
  (`specs/prototype/02-data-model.md`) exactly; no per-key sync channel, no
  outbox/catalog/tombstone machinery.
- **Device-local prefs never sync** (platform key-value), never reach Postgres
  or the synced SQLDelight DB (§6.3, ADR 0024).
- **Render-time only.** Settings tune *how content is shown*, never *what is
  authored* (§4). No AI tone/verbosity/topic controls.
- **Owner-only family settings are shown read-only** to non-owners with a
  "Family setting — only an owner can change this" label (transparency), but
  **the server is the authority** — owner-gated writes are re-checked per
  request (§3, §5). Pending-approvals is hidden for non-owners.
- **Phone-OTP is designed-not-built** (ADR 0023): sign-in-methods renders
  Google + Apple at MVP; the phone slot stays in mockups, dark at runtime.

---

## 1. Information architecture

### 1.1 Entry point

Settings is reached from a **circular avatar/monogram at the trailing end of
the Now/Hubs top app bar** — *not* a bottom-navigation tab. Now + Hubs own the
`NavigationBar` (ADR 0009/0013); Settings is a low-frequency destination and
does not earn a permanent tab. The avatar reads as the "account" affordance on
both platforms. No overflow (⋮) menu (buries account/family; reads as
developer leftovers; off the calm brand).

### 1.2 Settings home

A single grouped, scrollable list under a **large/collapsing top app bar**
("Settings"). Top of the list is an **account header** (avatar + display name +
identity summary, e.g. "Google · Apple linked") that pushes the Profile screen.
Below it, sectioned groups of M3 list items (leading icon, title, optional
supporting line, trailing chevron or inline control).

**Full-vision grouping (7 groups).** At **M1 these collapse to 3** (§9):

| Group | Holds | M1 collapse |
|---|---|---|
| **Account** | Profile, Sign-in methods, Sign out, Sign out all, Delete account | → **Account** |
| **Family** | Family name, Members, Pending approvals, Invite, Anchor time, Timezone, Leave/Transfer ownership | → **Family** |
| **Devices & Connections** | Connected devices & apps (CLI/Claude-Code), Authorize a device | → folded into **Account** |
| **Notifications** | Notification types, Quiet hours | → omitted at M1 (push deferred, ADR 0007) |
| **Preferences** | Appearance, Briefing tuning, Language & region, Accessibility | → theme + time-format only, in **App** |
| **Privacy & Data** | What Dayfold reads, Permissions, Export, Privacy policy | → telemetry + location toggles in **App**; export in Account |
| **About** | Version, Legal, Licenses, Help | → footer line, not a group |

The **only** count badge allowed is **Pending approvals (N)** — an actionable
owner duty, owner-only, cleared on action. No engagement badges.

### 1.3 Full navigation tree (milestone-tagged)

```
Settings  [M1]
│
├─ ⟨account header⟩ → Profile                                          [M1]
│
├─ ACCOUNT
│   ├─ Profile                                                         [M1]
│   │    ├─ Display name (edit)                                        [M1]
│   │    ├─ Avatar / monogram (color M1 · photo M-next)               [M1/M-next]
│   │    └─ My role (read-only badge: Owner / Adult)                   [M1]
│   ├─ Sign-in methods (linked providers)                             [M1]
│   │    ├─ Google   (linked ✓ / link)                                [M1]
│   │    ├─ Apple    (linked ✓ / link)                                [M1]
│   │    ├─ Phone    (slot present, runtime-dark)         [designed-not-built · ADR 0023]
│   │    └─ Provider-link-conflict confirm (proof-of-control)         [M1]
│   ├─ Sign out (this device)                                         [M1]
│   ├─ Sign out all / revoke all sessions                             [M1]
│   └─ Delete account…  (export step · last-owner guard · Apple revokeToken)  [M1]
│
├─ FAMILY
│   ├─ Family name (edit — owner)                                     [M1]
│   ├─ Members                                                        [M1]
│   │    ├─ Member list (name, role, status)                          [M1]
│   │    ├─ Pending approvals (N) — owner only                        [M1]
│   │    ├─ Member detail → change role / remove — owner             [M-next]
│   │    └─ Transfer ownership — owner                                [M1]  ← required by ≥1-owner invariant
│   ├─ Invite a member → QR + share-link, outstanding invites/revoke  [M1]
│   ├─ Anchor time (briefing "today starts at" — owner)               [M1]
│   ├─ Timezone (family — owner)                                      [M1]
│   ├─ Named places (view all · owner edit)                           [M-next]
│   ├─ Leave family (non-owner; owner blocked until transfer)         [M1]
│   └─ Switch family ⟨multi-tenant⟩                       [post-MVP · OQ-family-switcher]
│
├─ DEVICES & CONNECTIONS
│   └─ Connected devices & apps                                       [M1 · ref auth spec]
│        ├─ Device/credential list (label, client, last-used, geo/ASN)
│        ├─ Authorize a device (RFC 8628: user_code entry + origin warning)
│        └─ Revoke (single / all)
│
├─ NOTIFICATIONS                                          [M-next · push deferred ADR 0007]
│   ├─ Notification types (briefing-ready, action-due, invite/approval, device-auth)
│   ├─ Quiet hours (+ allow-security exception)
│   └─ System notification settings (deep-link to OS)
│
├─ PREFERENCES
│   ├─ Appearance → Theme (System/Light/Dark)                        [M1]
│   │    └─ Dynamic color (Material You, Android only)                [M-next]
│   ├─ Briefing tuning (content-type visibility, ordering, focus)    [post-MVP — see §4]
│   ├─ Language & region (language · units · week-start)             [M-next]
│   │    └─ Time format (12/24h)                                      [M1]
│   └─ Accessibility (reduce-motion, contrast, text-scale overrides) [M-next · OS honored at M1]
│
├─ PRIVACY & DATA
│   ├─ What Dayfold reads (honest source list)                       [M-next]
│   ├─ Location-aware highlights (opt-in, default OFF)                [M1]
│   ├─ Permissions overview (→ OS)                                    [M-next]
│   ├─ Telemetry (opt-in, default OFF)                                [M1]
│   ├─ Export my / family data                                       [M1 · ref auth spec]
│   └─ Privacy policy                                                 [M1]
│
└─ ABOUT (footer at M1)
    ├─ Version / build                                               [M1]
    ├─ Terms · Legal                                                  [M1]
    ├─ Open-source licenses                                          [M-next]
    └─ Help / Support                                                [M-next]
```

### 1.4 Adaptive & cross-platform

One Compose Multiplatform implementation; reconcile **behavior, not chrome**
(ADR 0009 accepts a Material look on iOS). The interaction reconciliation:

| Concern | Dayfold rule (one impl, both platforms) |
|---|---|
| Section style | M3 grouped list, subtle section headers + container tint |
| Toggles | Trailing M3 `Switch`, **instant-apply** (§5) |
| Back | Top-bar **Up (←)** everywhere; honor Android predictive-back + iOS edge-swipe |
| Edit text | Bottom sheet with explicit **Save** (not "Done") |
| Destructive | M3 `AlertDialog`, error-tinted confirm; typed-confirmation for account/family delete |

- **M1: single-pane** list→push-detail everywhere (phones-first dogfood).
- **[post-MVP] two-pane** list-detail on tablet/foldable/desktop, reusing the
  existing `nav.listDetail` mechanism (`specs/prototype/08-mobile-client.md`).
  Deferred — not worth the selection-state/back-stack/test-matrix cost for a
  weekly-visited surface until a real wide-screen user exists.

---

## 2. The three scopes

Every setting resolves to exactly one **write scope**, chosen by this rule
(applied in order):

1. **Describes this physical device/OS install, meaningless on another
   device?** → **per-DEVICE** (never synced).
2. **Otherwise — one member's preference?** → **per-USER** (synced to the user
   account, server-authoritative).
3. **Shared family truth?** → **per-FAMILY** (synced, owner-gated writes).
4. **Override:** carries content-grade sensitivity (coordinates, free text a
   person typed)? → it is **content, not a pref** → encrypted content path
   (ADR 0015). In practice this only catches **named places**.

**Tie-break: default to per-DEVICE** when ambiguous (data minimization).

| Scope | Examples | Storage | Synced |
|---|---|---|---|
| **per-DEVICE** | theme, dynamic-color, **location-permission state**, accessibility overrides, telemetry *emission gate* (§7) | platform key-value | **never** |
| **per-USER** | notification categories, quiet hours, locale/format, **telemetry preference**, briefing render-filters (post-MVP) | Postgres `user_prefs` (one row/user) | yes, authoritative |
| **per-FAMILY** | family name, **timezone**, briefing **anchor time**, named places (via content path) | Postgres `family_settings` (one row/family); `places` stays relational | yes, owner-gated |

**Read order** — because each key has exactly one write scope, resolution is a
**2-level lookup**, not a cascade: read the key's scope store; if absent, use
the **catalog/code default**. (The earlier "device > user > family > default"
cascade was incoherent given single-scope keys; corrected here. The only keys
that legitimately exist at two scopes are locale/format — a per-USER override
beats the profile/OS default — handled explicitly in §5.)

---

## 3. Profile & account

### 3.1 Profile screen

**Member-editable (self):** display name; avatar monogram color (photo =
M-next); **manage own sign-in methods** (link Google/Apple — never unlink the
last method; phone slot dark per ADR 0023); **sign out** (this device) /
**sign out all**; **delete own account** (last-owner guard + export + Apple
`revokeToken`); **leave family** (non-owner). Read-only to self: **own role
badge** — role is granted, not self-set.

**Owner-only (acts on others / the tenant):** edit **family name**; **approve/
deny pending members**; **change another member's role**; **remove a member**;
**revoke any device/CLI credential**; **transfer ownership** (mandatory before
an owner can leave/delete — the **≥1-active-owner invariant**, ADR 0011). An
owner viewing another member's profile sees role + remove/role controls; an
adult viewing another member sees name + role only. CLI-scoped credentials can
never reach these screens (content-only token).

### 3.2 Sensitive-action gate (step-up re-auth)

Changing sign-in methods, deleting the account, and transferring ownership are
**sensitive actions** and require a **fresh re-authentication** (re-run the
OIDC sign-in for a linked provider) within a short window before the action
commits. (ADR 0011 specified phone-OTP step-up; with phone deferred per ADR
0023, the step-up factor at MVP is **OIDC re-auth**.) This is a first-class
state in the flow, not an afterthought.

### 3.3 Account deletion flow (Settings entry → auth spec owns mechanics)

Settings is the entry point; the mechanics live in `auth-and-family-design.md`.
The flow MUST, in order: (1) detect **last-owner** → force ownership transfer
or block; (2) offer **export** first (guardrail #4, no friction); (3) **Apple
`revokeToken`** using the stored `apple_refresh_token_enc` (App Store
5.1.1(v)); (4) **soft-delete cascade** in-app (sets `deleted_at`; not raw row
deletion — matches the soft-delete-authoritative model in `02-data-model.md`);
(5) revoke all credentials/sessions. Never a dark-pattern cancel/retention flow.

---

## 4. Render-time vs authoring (hard line)

Content is authored **server-side** (CLI / Claude skill / future G1 loop). A
user **cannot** tune *what gets written* — relevance, tone, which cards exist.
Settings tune **render-time only**: theme, format, when/whether to be
interrupted, and (post-MVP, evidence-gated) filtering/ordering of already-
authored cards. The UI states this once: *"Briefing content is curated for your
family; these settings control how it's shown to you."*

**Explicitly NOT settings (route to the content loop, never a toggle):** card
tone/verbosity, "make briefings shorter", topic preferences ("more sports"),
summarization style.

**Briefing-tuning controls are demoted to [post-MVP], evidence-gated.** The
product thesis is *curated calm* — the user was promised they would not need a
mixing board. Shipping content-type visibility toggles, smart-vs-actions-first
ordering, and a per-member "focus" filter at M1 contradicts that and multiplies
render/test/support surface ("why is my card missing?"). If dogfooding shows a
**specific** card type is genuinely noise, kill it **server-side** or add **one**
evidence-backed toggle then. The per-member **focus** filter is additionally
gated on an **authored audience-metadata field** that does not yet exist —
building UI for an absent data model. All deferred.

---

## 5. Interaction & state model

- **Save semantics: instant-apply for per-DEVICE + per-USER toggles/selects;
  explicit confirm for free text and destructive.** Theme, time-format,
  quiet-hours, notification switches apply immediately with optimistic UI +
  rollback-on-reject snackbar. Free-text edits (display/family name) and
  membership/role/delete changes use an explicit Save sheet or typed-confirm
  dialog (server round-trip + validation; delete is irreversible).
- **Per-FAMILY (owner-gated) writes use explicit-confirm, not instant-apply.**
  A wrong optimistic flip of a shared family setting is high-blast-radius and
  the rollback UX is ugly; a confirm sheet ("This changes today/anchor/
  countdowns for everyone") is calmer and safer.
- **Server is the authority on owner-gating.** The client shows owner-only
  family rows **read-only** to non-owners with an explanatory label, but the
  **server re-resolves `role=owner` per request** and returns **403** on any
  non-owner write to `family_settings` / membership / family name — independent
  of the UI (defends against a modded client or a demote-between-render-and-
  write race). A rejected optimistic write reverts with an inline "You no
  longer have permission" notice.
- **Role-gated visibility:** owner-only **actions** hidden for adults; shared
  **values** (timezone, anchor, family name) shown **read-only** to adults
  (transparency). Pending-approvals does not render for adults at all.
- **Offline (ADR 0020 offline-first):** settings **structure renders instantly**
  from the local DB. Server-truth fields (member list, pending count, connected
  devices, linked providers) show last-synced values with a quiet "Updated · 2h
  ago" caption and refresh on open. **Security/membership mutations require
  connectivity** — offline shows "You're offline — reconnect to change this"
  and disables the confirm (no optimistic queue for security actions). Loading
  = M3 skeleton rows for server-truth lists only; static preference rows never
  skeleton.
- **Search-in-settings: [post-MVP].** The M1/M-next tree is shallow (≤2 levels,
  3 collapsed groups) — search adds chrome against the calm aesthetic. Revisit
  when briefing-tuning / per-type notifications deepen the tree.

---

## 6. Data model

> Matches `specs/prototype/02-data-model.md` conventions: `text` IDs, `version
> bigint` per-row optimistic concurrency, `created_at`/`updated_at`,
> `deleted_at` soft-delete, family-scoping, enums-as-CHECK. **Storage shape =
> typed columns, one row per scope** (HIGH-confidence build decision; the
> *privacy* posture is ADR 0024). EAV/per-key-version was rejected (§6.4).

### 6.1 Profile — extend `users`

Profile fields are 1:1 with the user, always loaded with it — **columns on
`users`**, not a side table:

```sql
ALTER TABLE users ADD COLUMN avatar_color text;        -- monogram tint key (M1); photo = avatar_ref (M-next)
ALTER TABLE users ADD COLUMN avatar_ref   text;        -- [M-next] object-storage key (06-storage), opaque
ALTER TABLE users ADD COLUMN version      bigint NOT NULL DEFAULT 1;  -- per-row optimistic concurrency (If-Match)
-- display_name, created_at, updated_at, deleted_at already exist (02-data-model.md).
-- locale lives ONLY in user_prefs (§6.2) — single source of truth, no duplication.
```

### 6.2 per-USER preferences — `user_prefs` (one row per user, typed columns)

```sql
CREATE TABLE user_prefs (
  user_id          text PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  -- appearance/format
  time_format      text,                       -- '12h' | '24h' | NULL=system  (CHECK)
  locale           text,                        -- BCP-47 override; NULL=system
  -- notifications [M-next]
  notif_briefing   boolean NOT NULL DEFAULT true,
  notif_action_due boolean NOT NULL DEFAULT true,
  notif_invite     boolean NOT NULL DEFAULT true,
  notif_device     boolean NOT NULL DEFAULT true,   -- security signal; UI warns on disable
  quiet_start      text,                        -- 'HH:MM' | NULL=off
  quiet_end        text,
  quiet_allow_security boolean NOT NULL DEFAULT true,
  -- privacy
  telemetry_pref   boolean NOT NULL DEFAULT false,   -- preference of record; EMISSION gated device-local (§7)
  version          bigint NOT NULL DEFAULT 1,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT user_prefs_time_format_ck CHECK (time_format IN ('12h','24h'))
);
```

> per-USER prefs sync on a **user-scoped** stream keyed on `user_id`, distinct
> from the family content stream. One row per user → one `version` → fits the
> existing per-row keyset-sync + If-Match contract directly (§6.3).

### 6.3 per-FAMILY settings — `family_settings` (one row per family)

```sql
CREATE TABLE family_settings (
  family_id        text PRIMARY KEY REFERENCES families(id) ON DELETE CASCADE,
  timezone         text,                        -- IANA tz; NULL=creator tz at creation
  anchor_time      text NOT NULL DEFAULT '06:30',-- 'HH:MM' family briefing anchor
  version          bigint NOT NULL DEFAULT 1,
  updated_by       text REFERENCES users(id),    -- audit: which owner last wrote
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now()
);
-- family_name stays on families.name (already exists).
-- named places stay in the existing relational `places` table (02-data-model.md) — a LIST
-- with geo fields + icon kind, content-grade (encrypted per ADR 0015 at M1), NOT folded here.
```

**Write authorization (BLOCKER fix):** `family_settings`, `families.name`, and
membership writes go through the **default-deny tenant middleware** (per
`auth-and-family-design.md`); writes additionally require **`role=owner`
re-resolved per request → 403** otherwise. Both `user_prefs` and
`family_settings` are added to the **IDOR test matrix** (family A → 403/404 on
family B's row).

### 6.4 Sync, conflict & why typed columns

- **One row per scope → one `version` → no new sync machinery.** `user_prefs`
  rides a user-scoped keyset stream; `family_settings` rides the family stream
  alongside content. Both use the **existing** per-row `version` + `updated_at`
  keyset cursor and **If-Match** optimistic concurrency from
  `02-data-model.md` — *no per-key versions, no per-key sync channel, no
  parallel concurrency model*.
- **Conflict = whole-row last-writer-wins via If-Match.** Settings are the
  first genuine client-write surface (M0 content is server-authored). A stale
  writer gets **409 → re-pull → re-apply**. At ~6 members / 2–3 devices editing
  a handful of scalars, concurrent edits to the *same* row are rare;
  whole-row LWW is sufficient. **No outbox, no CRDT, no tombstones.** Offline
  pref edits use **optimistic local echo + retry-on-reconnect**; settings
  writes otherwise require connectivity (this is an online briefing app).
- **Defaults = column defaults / code constants.** Adding a setting = **one
  migration + one field + one UI row** — cheap for a solo operator, and
  type-safe end to end. (EAV's forward-compat "unknown keys preserved" buys
  nothing here and costs an indirection layer + a per-key sync fork that
  contradicts the data-model contract — rejected.)
- **Validation:** Postgres CHECK on enum/format columns; the TS server
  validates ranges (`HH:MM`, IANA tz) before write (422 on fail); the Kotlin
  client's typed model generates the pickers so out-of-range values can't be
  authored.

### 6.5 per-DEVICE storage (never Postgres)

Device prefs live in **platform key-value via `multiplatform-settings`**
(DataStore on Android / `NSUserDefaults` on iOS / `localStorage` on Web) — read
at startup before any DB/network, single-writer, no `version`/sync. They
**never** enter Postgres or the synced SQLDelight DB (so a sync sweep can't pick
them up). Client SQLDelight mirrors only the synced `user_prefs` /
`family_settings` rows, reconciled exactly like content rows (ADR 0020 R4: one
transaction, upsert where `version` advances).

---

## 7. Privacy & storage posture → ADR 0024 (Proposed)

Full posture, never-syncs list, location special-case, telemetry consent
ordering, and the E2EE line are specified in **ADR 0024 (Proposed —
operator-gated, guardrail-class)**. Summary of what this spec depends on:

- **Never leaves the device (hard rule):** live location/activity/geofence
  state (ADR 0014, absolute); **location-permission state**; theme +
  dynamic-color; accessibility overrides; DevTools/debug state (ADR 0019).
- **Telemetry consent ordering (BLOCKER fix):** `telemetry_pref` is the synced
  *preference of record*, but **emission is gated on a device-local flag that
  defaults OFF**; the app **never emits telemetry on the strength of a
  not-yet-synced row**. Opt-OUT takes effect locally immediately; opt-IN
  requires a positive local read. Default OFF everywhere (guardrail #4, ADR
  0019).
- **Location-permission state stays device-local**, but a **synced per-USER
  intent** ("location-aware highlights ON") combined with a **missing OS grant**
  on a given device resolves to a derived **"blocked — fix permission"** state
  with a CTA (cross-tier reconciliation; not a server round-trip).
- **E2EE line (ADR 0015):** only **named places** cross into encrypted content
  (coordinates). All other prefs are routing-grade scalars/enums → **plaintext**
  (encrypting quiet-hours times buys near-zero privacy and forfeits future
  server-side quiet-hours-aware push). **M0 caveat:** "encrypted at rest +
  family-scoped + never logged" (02/0014) is *at-rest* encryption; the **E2EE
  FCK-wrap is M1, gated on ADR 0017** — places follow the M0 plaintext posture
  until then.
- **Export/delete:** `user_prefs` + `family_settings` rows are covered by the
  member/family deletion cascade; per-DEVICE prefs hold nothing the server is
  responsible for (lost on reinstall — intended).

---

## 8. Representative userflows

**A — Switch theme (any member, per-DEVICE).** Settings → App → Theme → Dark.
Applies instantly; persisted on this device only; no sync, no family effect.

**B — Set my quiet hours (any member, per-USER).** Settings → Notifications →
Quiet hours → 10:00 PM–6:30 AM; confirm "allow security" stays on. Saved to my
account; syncs to my other devices; other members unaffected. *(M-next — push.)*

**C — Owner changes family timezone (owner-gated, per-FAMILY).** Settings →
Family. *Non-owner sees timezone read-only with "only an owner can change this"
→ ends.* Owner taps Timezone → picker → confirm sheet ("changes how today,
anchor, and countdowns compute for everyone") → applies family-wide on next
sync; members on a different device tz see a "family tz / your device tz"
annotation.

**D — Opt into location-aware highlights (any member, per-DEVICE gate).**
Settings → Privacy → Location-aware highlights ON → OS when-in-use prompt.
Foreground proximity highlights matched **on-device**. Inline copy: "matched on
your device — your live location never leaves it" (ADR 0014). *(Background
"Always" upgrade = M-next.)*

**E — Delete account (sensitive; last-owner path).** Settings → Account →
Delete account → **step-up re-auth (OIDC)** → if last owner, **transfer
ownership** first → **export offered** → confirm (typed) → Apple `revokeToken`
+ soft-delete cascade + revoke all sessions.

**F — Authorize the CLI/Claude-Code (any member, RFC 8628).** Settings →
Devices → Authorize a device → enter `user_code` from the CLI → review origin
geo/ASN warning + scope → approve. *(Reuses the shipped auth screen.)*

---

## 9. M1-minimal set (smallest coherent first shippable)

**Storage:** `user_prefs` (1 row/user) + `family_settings` (1 row/family) typed
tables + device KV for cached theme. No EAV, catalog, outbox, conflict engine.

**IA:** avatar entry → single-pane list, **3 groups**:

- **Account** — Profile (name + monogram), Sign-in methods (Google+Apple),
  Connected devices (link), Sign out / Sign out all, **Export / Delete**.
- **Family** — Members (owner badge; read-only to non-owners), **Anchor time**
  (owner), **Timezone** (owner), Invite, Transfer ownership, Leave.
- **App** — **Theme** (system/light/dark), **Time format** (12/24h),
  **Location highlights** (opt-in, default OFF), **Telemetry** (opt-in, default
  OFF).

Footer: version + legal links.

**Explicitly NOT in M1:** briefing content-type toggles / ordering / focus,
two-pane adaptive, all notification toggles (push deferred), named-places UI,
calm-window, anchor-override, dynamic-color, density, accent color,
accessibility overrides, search, language/units/week-start. ~10 settings across
3 screens, tunable in under a minute.

---

## 10. Handoff to Claude Design

See `designs/DESIGN-BRIEF-settings.md` (this spec's companion) for the
paste-ready hi-fi mockup brief. Targets: a `Settings-Phone.dc.html`
parameterized component (props: `mode` = light/dark, `view`) + a `Settings.dc.html`
gallery, matching the existing `Auth-Phone.dc.html` convention, M3 Expressive,
Dayfold seed palette (Coral `#FF5436` / Teal `#11B5A4` / Violet), Outfit +
Figtree, Material Symbols Rounded. Mockups cover the **full-vision tree** (so
design-first is satisfied once) with **M1-minimal clearly distinguished**, plus
the load-bearing states: owner read-only rows, step-up re-auth, offline,
last-owner delete guard, location-permission-blocked, designed-not-built phone
slot.

---

## 11. Open questions

- **OQ-family-switcher** — multi-tenant "Switch family" placement (post-MVP).
- **OQ-auth-recovery-floor** (from ADR 0011/0023) — where account recovery
  surfaces in Settings; under E2EE (ADR 0015) an owner recovery phrase may be
  needed.
- **OQ-settings-search-trigger** — tree-depth threshold that flips search on.
- **Avatar photo (M-next)** — monogram-only at M1; photo upload re-opens
  storage/PII handling (adults-only, so not COPPA, but a privacy call).
- **Notification-types granularity** — channel count before a sub-list is
  warranted (drives M-next vs post-MVP split); maps to Android notification
  channels (OS stays source of truth).
