# Design Brief / Prompt — Dayfold Settings, Account & Preferences

**Hand this whole file to a fresh Claude Code session to produce the hi-fi
Settings mockups.** Self-contained. Authoritative source:
`../specs/account-and-settings-design.md` (+ ADR 0024 privacy posture, ADR
0009 design system, ADR 0011/0023 auth, ADR 0014 triggers, ADR 0020
offline-first). Companion to `DESIGN-BRIEF.md` / `DESIGN-BRIEF-triggers.md`.

---

## 0. How to run this

> **You are designing the hi-fi UI/UX for Dayfold's Settings surface.** Use the
> `frontend-design` skill. Produce **interactive HTML/CSS prototypes** that
> faithfully emulate **Material 3 Expressive** (color roles, tonal palettes,
> expressive type scale, shape scale, elevation, expressive/spring motion).
> Build target is Compose Multiplatform — treat these as a visual spec: name
> things after M3 components (`LargeTopAppBar`, `ListItem`, `Switch`,
> `SegmentedButton`, `FilledTonalButton`, `AlertDialog`, `ModalBottomSheet`,
> `ListDetailPaneScaffold`) so they map 1:1 to Compose. **Match the existing
> mockups' conventions exactly** (see §7). Visuals only — no app logic.

## 1. Product context (settings-specific)

Dayfold is a calm AI household-briefing app (Now feed + Event Hubs). Settings is
a **low-frequency, leave-quickly** surface — calm = behavioral restraint, not
muted visuals (vibrant pixels, no engagement bait, no badges except one). It is
a **separate milestone after the M0 prototype**; it **re-routes** the already-
designed auth/family/device/export screens (`Auth-Phone.dc.html`) into one
settings tree — **do not redesign those hardened flows**, link to them.

Adults-only; one family = tenant, many adult members; **owner** vs **adult**
roles. Identity = Google + Apple (Firebase); **phone-OTP is designed-not-built**
(ADR 0023) — keep the phone slot in the mockup but render it visibly
disabled/"coming later."

## 2. Brand & tone (inherit from `DESIGN-BRIEF.md`)

- **Seed palette:** Coral `#FF5436` (primary) · Teal `#11B5A4` (secondary) ·
  Violet (tertiary). Full M3 tonal palettes + roles, **light (hero) AND dark**.
  Note where Android **dynamic color** would remap.
- **Type:** **Outfit** (display/headline/title) · **Figtree** (body/label).
  **Material Symbols Rounded** for icons.
- **Shape/elevation/motion:** M3E rounder shapes; surface-container tiers;
  expressive spring + emphasized easing.
- Warm, human, never childish, never gamified. **Honest & trustworthy** is a
  pillar — privacy copy is load-bearing here (location, telemetry).

## 3. Information architecture to render

Render the **full-vision tree** (so design-first is satisfied once) but make the
**M1-minimal set visually distinct** (e.g. a subtle "M1" tag chip in the
gallery, not in the live UI). Entry = **circular avatar/monogram in the Now/Hubs
`TopAppBar` trailing slot** (NOT a bottom-nav tab — bottom nav stays Now+Hubs).

Settings home = grouped scrollable list under a **`LargeTopAppBar`** ("Settings")
with an **account header** row at top (avatar + display name + identity summary
"Google · Apple linked" → Profile).

**Full-vision groups:** Account · Family · Devices & Connections · Notifications
· Preferences · Privacy & Data · About.
**M1 collapses to 3:** **Account**, **Family**, **App** (+ a footer line for
version/legal). Show both — the 7-group full vision AND a "M1-minimal" variant.

The **only count badge** is **Pending approvals (N)** (owner-only, actionable
duty). No other badges.

See `../specs/account-and-settings-design.md` §1.3 for the complete node tree
with milestone tags — render every node; tag M-next/post-MVP nodes in the
gallery.

## 4. Screens to deliver (phone, light + dark each)

1. **Settings home** — full-vision (7 groups) AND M1-minimal (3 groups + footer).
2. **Profile** — display name (editable), monogram color picker, **role badge**
   (read-only Owner/Adult), sign-in methods summary, sign out / sign out all,
   delete account entry.
3. **Sign-in methods** — Google (linked ✓), Apple (linked ✓ / Link), **Phone
   (slot present, visibly disabled — "available later")**, link-conflict
   proof-of-control confirm state.
4. **Family** — members list (name/role/status, owner badge), **pending
   approvals (N)** owner-only, anchor-time row, timezone row, invite, transfer
   ownership, leave. Show **owner view** AND **adult view** (owner-only actions
   hidden; shared values **read-only with "Family setting — only an owner can
   change this"** label).
5. **Anchor time** + **Timezone** editors — owner picker + the **confirm sheet**
   ("changes how today, anchor & countdowns compute for everyone").
6. **App / Preferences** — Theme `SegmentedButton` (System/Light/Dark), Time
   format (12/24h), and (tagged M-next/post-MVP) dynamic-color, language/region,
   accessibility.
7. **Notifications** (M-next) — type toggles + quiet hours; show a "push coming
   later" affordance.
8. **Privacy & Data** — **Location-aware highlights** (opt-in, default OFF, with
   the ADR-0014 privacy line "matched on your device — your live location never
   leaves it"), **Telemetry** (opt-in, default OFF), What Dayfold reads, Export,
   Privacy policy.
9. **Connected devices & apps** — link to the existing auth device screen
   (`Auth-Phone.dc.html` authorize-device/connected-devices); render the entry
   row + a thumbnail of the destination, don't redesign it.

## 5. Load-bearing states (must all appear)

- **Owner read-only rows** — adult sees shared family values read-only + the
  explanatory label; owner-only *actions* hidden for adults; pending-approvals
  not rendered for adults.
- **Step-up re-auth** — sensitive-action gate (change sign-in methods, delete
  account, transfer ownership) → "confirm it's you" OIDC re-auth sheet.
- **Last-owner delete guard** — delete-account when sole owner → "transfer
  ownership first" blocking state → export-offered → typed-confirm.
- **Offline** (ADR 0020) — structure renders instantly; server-truth lists
  (members, devices, pending) show "Updated · 2h ago" + skeleton on refresh;
  security/membership mutations show "You're offline — reconnect to change this"
  with the confirm disabled.
- **Location-permission blocked** — synced "highlights ON" + missing OS grant →
  "blocked — fix permission" CTA row.
- **Designed-not-built phone slot** — visibly disabled in sign-in methods.
- **Save semantics** — instant-apply toggles (theme/format/notif) vs
  **explicit-confirm** for free text (name) and **per-family/destructive**
  (timezone, anchor, delete) via `ModalBottomSheet` Save / `AlertDialog`.

## 6. Adaptive (separate, tagged post-MVP)

M1 is **single-pane** (list → push-detail). Provide ONE **post-MVP**
`ListDetailPaneScaffold` two-pane settings mock (tablet/foldable) tagged as
deferred — do not make it the primary deliverable.

## 7. Output files & conventions (match the repo exactly)

- **`designs/Settings-Phone.dc.html`** — the parameterized phone component
  (props: `mode` = light/dark, `view` = home / home-m1 / profile / signin /
  family-owner / family-adult / anchor / timezone-confirm / app / notifications
  / privacy / devices / reauth / delete-last-owner / offline / location-blocked).
  Mirrors the `Auth-Phone.dc.html` / `Now-Phone.dc.html` pattern (one component,
  many views → one Compose screen).
- **`designs/Settings.dc.html`** — gallery mounting every `mode × view` combo,
  light + dark, with milestone tags (M1 / M-next / post-MVP) per view.
- Add a row to `designs/README.md` ("Settings | `Settings.dc.html` | …").
- Update `designs/Index.dc.html` to link the Settings gallery.
- **Token parity** with the existing design system (`Design-System.dc.html`):
  reuse its CSS custom properties / color roles; do not invent new tokens.
- `.dc.html` self-contained (inline CSS, the shared `support.js` pattern if used
  elsewhere). Verify tag-balance + that every mode×view render combination
  paints in both light and dark.

## 8. Definition of done

- All §4 screens + all §5 states, light + dark, in `Settings-Phone.dc.html`,
  mounted in `Settings.dc.html`.
- M1-minimal (3-group) variant clearly distinguished from the full-vision
  (7-group) tree.
- Component names map 1:1 to Compose M3; token parity with the design system.
- Privacy copy present verbatim on location + telemetry rows.
- Phone slot rendered designed-not-built; owner vs adult family views both shown.
- README + Index updated. Operator sign-off (ADR 0008) gates the build.
