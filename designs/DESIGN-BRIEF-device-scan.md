# Design Brief — device-approval scan + deep-link surfaces (Phase 2)

**For:** a `frontend-design` session producing hi-fi mockups. **Gates:** ADR 0008
(design-first) for AUTH-S6-D Phase 2 (scanner + deep-link). **Target file:**
`designs/Family AI dashboard design brief/designs/Auth-Phone.dc.html` (extend it)
+ mount in the gallery `designs/Family AI dashboard design brief/designs/Auth.dc.html`.
**Design:** `docs/superpowers/specs/2026-06-23-auth-s6d-device-approval-design.md`.

---

You are designing hi-fi UI/UX for Dayfold (family-ai-dashboard), a calm
M3-Expressive family dashboard. Use the `frontend-design` skill. Produce
interactive HTML/CSS that faithfully emulates Material 3 Expressive and maps 1:1
to Compose Multiplatform (name things after M3 components). Visuals only — no app
logic.

## Context: what you're adding
The CLI-login (RFC 8628 device-grant) approval flow already has signed-off phone
mockups in `Auth-Phone.dc.html`: views `authorizedevice` (consent/confirm),
`entercode` (manual 8-char user_code entry), `devicedenied`, `deviceexpired`.
Read that file first and match it exactly.

Phase 2 adds an **in-app QR scanner + deep-link** so a phone can scan the QR shown
in the terminal instead of typing the code. Those camera + handoff surfaces have
NO mockup yet — that's what you're designing. The scan path is a CONVENIENCE that
still funnels into the EXISTING `authorizedevice` confirm screen: scanning only
fills the code; the human still reviews origin/scope/family and taps Approve.
**Never auto-approve from a scan.**

## Format (match the existing file precisely)
- Extend `Auth-Phone.dc.html` — a parameterized 390×844 phone component using the
  `<sc-if value="{{ isX }}">` view-switch pattern, a `view` enum in its `data-props`
  script, and per-mode color tokens `c.*` (c.surface, c.onSurface, c.onSurfaceVar,
  c.primary, c.onPrimary, c.surfaceContainer/High, c.errorContainer,
  c.onErrorContainer, c.tertiaryContainer, c.outline, c.statusBg, …). Reuse tokens;
  do not hardcode hex.
- Fonts: Outfit (display/title), Figtree (body/label), Roboto Mono (codes). Icons:
  Material Symbols Rounded via `.msr`. Status bar at top like every view.
- Add each new view to the `view` enum + an `is<View>` flag, like `isAuthDev`.
- Mount every new view (light AND dark) in `Auth.dc.html` as
  `{ title, mode, view, note, ...L | ...D }` rows, beside the existing device rows.
- Light is the hero; deliver dark for each.

## Screens to design (each light + dark)

1. **`scandevice`** — live camera viewfinder to scan the terminal QR.
   - Full-bleed camera surface (mock a dim photographic backdrop), centered rounded
     **scan reticle** with animated corner brackets, dimmed scrim outside it.
   - Top bar: close (X) + title "Scan device code"; a torch/flashlight toggle.
   - Instruction under reticle: "Point at the QR code on your computer."
   - Persistent secondary action: "Enter code manually" → `entercode`.
   - Calm, not surveillance-y; a subtle "detecting…" affordance.

2. **`scanprimer`** — camera-permission rationale (before the OS prompt).
   - Friendly icon, "Scan from your computer", honest reason: "Dayfold uses the
     camera only to read the code on your screen — nothing is recorded or sent."
   - Primary "Allow camera"; secondary "Enter code instead".

3. **`scandenied`** — camera permission denied (no dead end).
   - Camera is off; primary "Open Settings"; prominent "Enter code instead".
     Reassuring tone.

4. **`deviceresume`** — deep-link cold-start handoff: the owner tapped/scanned the
   QR link but isn't signed in yet. Calm interstitial: brand mark + "Sign in to
   approve this device" ("we'll bring you right back"), continuing into normal
   sign-in. Also show the post-sign-in "Finishing…" loading beat.

5. **Update `entercode`** — add a visible "Scan QR code" affordance (secondary
   button or a Scan/Type segmented toggle at top) so both entry paths are
   discoverable. Keep the existing 8-cell entry intact.

## Constraints / tone
- Family-warm, adults-only, never childish; calm behavior, expressive pixels.
- Honesty + privacy: camera copy must state the camera reads only the on-screen
  code, nothing recorded/uploaded (a trust surface).
- Accessibility: ≥48dp targets, WCAG-AA contrast on every pairing, a reduced-motion
  note for the reticle animation.
- Every path has a no-camera fallback to `entercode` (desktop has no camera).

## Definition of done
- The 4 new views + the updated `entercode`, all light + dark, added to the `view`
  enum and mounted in `Auth.dc.html` (and linked from `Index.dc.html` if it lists
  auth views).
- Visually cohesive with the existing `authorizedevice`/`entercode` screens.
- Operator can open the gallery and approve the look-and-feel.
