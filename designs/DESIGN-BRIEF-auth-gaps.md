# Design brief — Auth failure/error screen gaps (extend A8b)

**For:** Claude Design (frontend-design). **Output:** new views added to
`designs/Family AI dashboard design brief/designs/Auth-Phone.dc.html` (and the
gallery `Auth.dc.html`), in the existing Dayfold system. **Then:** operator
sign-off (ADR 0008).

## Context (read first)

The auth mockups already exist as an 18-view phone file:
`Auth-Phone.dc.html`. It is a `.dc.html` design-canvas file: one phone frame
(390×844) whose visible screen is selected by a **`view` enum** in the
`data-props` JSON, with a `mode` enum (`light`/`dark`). Each screen is an
`<sc-if value="{{ isX }}">` block in the template; `renderVals()` (a
`Component extends DCLogic` class) returns the `is*` flags + a `c` color-token
map (light + dark) + any per-screen data. **To add a screen:** add its value to
the `view` enum, add an `isX` flag in `renderVals()`, and add one `<sc-if>`
block. Reuse the existing `c.*` tokens (incl. `c.errorContainer`/`c.onError`),
the **Dayfold turned-corner mark**, `Roboto Mono` for codes, and the
status-bar/home-indicator chrome. **Match the existing screens exactly** —
spacing, type (Outfit headings / Figtree body), 16px button radius, card radii,
light + dark.

**Brand:** Dayfold. **Providers:** Google + Apple only — **no phone/OTP**
(deferred, ADR 0023). **Source of truth for behavior:**
`specs/auth-and-family-design.md` (Flow 3a invitee-join, §Cross-cutting,
§Screens) and the S4 backend (approve/**decline**/revoke, per-account redeem
lockout, ≥1-owner invariant).

Tone: calm, honest, low-blame (the product's voice). Failure screens reassure,
never accuse; destructive screens are explicit about what is lost.

---

## GROUP A — Invitee-join failure states (priority: gates S5 slice-2)

The happy path + expired/revoked/exhausted/already-member already exist
(`invited`, `waiting`, `inviteerror` with reason expired·revoked·exhausted,
`alreadymember`). Add the three missing failure states:

### A1 · Declined by owner — new view `invitedeclined`
- **When:** the invitee was *pending* and the owner tapped **Decline** (distinct
  from *revoked* = invite link killed, and *expired* = TTL lapsed). The `waiting`
  screen can also transition into this if the owner declines while they wait.
- **Layout:** centered, like `inviteerror`. Soft icon (a gentle "person off" /
  closed-door feel, not an alarm). Headline along the lines of *"This didn't go
  through"*. Body: the owner reviews every join and didn't add you this time;
  suggest reaching out to them directly. **No self-retry** (a declined request
  can't be re-submitted from here). One muted CTA: *"Use a different account"* or
  *"Done"*. Light + dark.

### A2 · Too many attempts / locked out — new view `invitelocked`
- **When:** the invitee hit the redeem **rate-limit/lockout** (per-account,
  ~5 / 15 min — confirm constant in spec/S4). 429 from `/invites:redeem`.
- **Layout:** centered. Icon `lock_clock`. Headline *"Take a breather"*. Body:
  you've tried a few times — wait a few minutes, then open the link again.
  Optional subtle cooldown hint. Single muted CTA. Light + dark.

### A3 · Couldn't join — retry — new view `joinerror`
- **When:** the redeem call failed transiently (network drop / 5xx) — NOT a
  specific invite state. The generic "something went wrong, try again" for the
  join flow (the existing `offline` screen is sign-in-only).
- **Layout:** centered. Icon `cloud_off` / `error`. Headline *"Something went
  wrong"*. Body: we couldn't reach Dayfold to finish joining; check your
  connection and try again. **Primary** CTA *"Try again"*. Light + dark.

---

## GROUP B — Management confirmations & error states (gates S6)

The S6 happy screens exist (`invite`, `authorizedevice`, `members`, `devices`,
`account`, `linkconflict`) but their destructive-action confirmations and error
states are missing.

### B4 · Account-deletion confirmation + last-owner transfer
Two linked screens (the `account` screen's Delete button leads here):
- **`deleteconfirm`** — a destructive confirmation. Headline *"Delete your
  account?"*. Spell out what's lost: removed from every family, data erased,
  Sign in with Apple disconnected (Apple `revokeToken`). Require an **explicit**
  confirm gesture (type `DELETE`, or a hold-to-confirm). Buttons: Cancel +
  destructive Delete (error color). Light + dark.
- **`transferowner`** — shown *before* deletion/leave if the user is the **sole
  owner** of a family (≥1-owner invariant). *"Choose a new owner first"* + a
  member picker (radio rows) to promote someone, then continue. If the family
  has **no other members**, instead warn that deleting also **deletes
  `<Family>`** and its content. Light + dark.

### B5 · Remove last owner (409) — reuse `transferowner`
- **When:** an owner tries to remove themselves / the last owner in `members`.
  Surface the same *"Transfer ownership first"* picker (B4's `transferowner`),
  entered from the members context. (No separate new screen needed beyond B4 —
  just note the entry point + an inline 409 toast/message on the members screen.)

### B6 · Authorize-device — deny + expired states
Add to the `authorizedevice` family:
- **`devicedenied`** — brief confirmation after **Deny**: *"Denied"* — that
  device won't get access; reassure if they didn't start it, nothing happened.
- **`deviceexpired`** — the approval was opened after the `user_code` expired, or
  it was already approved/denied elsewhere: *"This request has expired"* /
  *"Already handled"*, with guidance to restart it on the computer. Light + dark.

---

## Deliverable / DoD
- New views added to `Auth-Phone.dc.html` (`view` enum + `is*` flags + `sc-if`
  blocks), light + dark, cohesive with the existing 18.
- `Auth.dc.html` gallery updated with the new frames + a one-line caption each.
- No behavior invented beyond `specs/auth-and-family-design.md` — if a copy
  decision needs a product call (e.g. exact lockout wording), leave a TODO note.
- Verify it renders (the `.dc.html` runtime via `support.js`), then operator
  sign-off per ADR 0008.
