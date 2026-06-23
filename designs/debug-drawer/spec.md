# SloopWorks Debug Drawer — Design Spec

> Design-first gate (ADR 0008). Operator sign-off required **before** build.
> This document specifies visuals, interaction, theming, adaptive behavior, and
> accessibility. The architecture (single bubble → host drawer → pluggable
> panels, debug-only, themeable) is fixed and is **not** redesigned here.

Companion hi-fi mockups: `designs/debug-drawer/Debug Drawer.dc.html` (all
screens/states, both skins, light + dark).

---

## 1. What this is

A developer-only instrument that appears **only in debug builds** of a Compose
Multiplatform app (Android / desktop / iOS). A single floating **bubble** is the
entire footprint. Tapping it opens a **host drawer** that lists pluggable
**panels** and navigates list → detail. The core is redux-agnostic and reusable
across apps; one panel (Redux DevTools) is an adapter that embeds an existing
inspector.

Design intent: a **calm, dense, professional instrument** — legible at a glance
under debugging cognitive load. Utilitarian and trustworthy, never playful.

---

## 2. Component inventory

| # | Component | Role | Notes |
|---|-----------|------|-------|
| C1 | **Bubble** | Entry point | 56dp circle, floating, draggable, edge-snapping. States: idle, unread badge (count), dragging, pressed. |
| C2 | **Drawer host / scrim** | Container | Bottom sheet (phone) or right side sheet (wide). Scrim dims app behind. Drag handle on phone. |
| C3 | **Host header** | Chrome | Brand mark + name, build-type chip, close (✕). In detail view: back (‹) + panel title + panel actions. |
| C4 | **Panel list row** | Navigation | Leading icon, title, optional trailing value/status, chevron. ≥48dp tall. |
| C5 | **Key/value row** | Read-only data | Mono value, long-press / tap-to-copy, copy confirmation toast. |
| C6 | **Segmented filter** | Control | Log level V/D/I/W/E. Single-select. |
| C7 | **Selectable list item** | Choice | Radio affordance — environment/backend picker. |
| C8 | **Sticky action bar** | Commit | Holds primary action (e.g. Apply & Restart). Pinned to drawer bottom. |
| C9 | **Confirm sheet** | Guard | Modal confirmation for destructive/restart actions. |
| C10 | **Blocking status overlay** | Process | Restarting / loading spinner with label. |
| C11 | **Log row** | Dense list | Level pill, timestamp (mono), tag, message (1-line ellipsis). |
| C12 | **Log detail** | Inspector | Full message, stack/metadata, share/export. |
| C13 | **Toast / snackbar** | Feedback | Copy / export confirmation. Auto-dismiss. |
| C14 | **Empty state** | Zero-data | Icon + line + optional action. |
| C15 | **Error state** | Failure | Icon + cause + retry. |
| C16 | **Adapter surface** | Embed | Redux DevTools panel mounts a third-party inspector inside C2/C3 chrome. |

**Fixed shapes (not themeable):** drawer radius, row height/density, chip and
pill geometry, spacing scale, icon stroke weight, list→detail nav model.

---

## 3. Theming contract (the customization API surface)

The drawer ships a **SloopWorks default skin** and accepts an optional override
object so a consuming app can blend it in. Overriding is **token-level only** —
layout, density, and component shapes are fixed so every app's drawer behaves
identically.

### Overridable tokens (`DebugDrawerTheme`)

| Token | Type | Default (SloopWorks) | Purpose |
|-------|------|----------------------|---------|
| `brandName` | String | `"SloopWorks"` | Host header label. |
| `brandMark` | Drawable/painter | sail triangle | Header mark + bubble glyph. |
| `bubbleIcon` | Drawable/painter | = brandMark | Override glyph shown in bubble only. |
| `accent` | Color role | `#2A53F0` / `#86A1FF` | Primary action, selection, focus. |
| `onAccent` | Color role | `#FFFFFF` | Content on accent. |
| `accentSoft` | Color role | `#EDF0FE` / `#15182C` | Selected-row + chip wash. |
| `colorScheme` | enum `dark`/`light`/`system` | `system` | Initial theme; user can still toggle. |
| `bubblePosition` | enum corner | `bottom-end` | Initial dock corner (user can drag). |
| `bubbleEdgeSnap` | Boolean | `true` | Snap to nearest edge on release. |

Everything else derives from **Material3 color roles** (`surface`,
`surfaceContainer`, `onSurface`, `outline`, `error`, …) so a consumer only has
to supply an accent + brand identity to get a coherent result. Status colors
(ok / warn / error / log levels) are **fixed** for cross-app legibility — a
warning looks the same in every app.

### Not overridable (fixed)

Layout & nav model · density / row heights · spacing scale (4dp base) ·
component shapes & radii · typography roles (consumer may map fonts via M3
`Typography`, but scale/weights are fixed) · log-level color semantics ·
iconography style (2dp stroke, 24dp grid).

### Proof

The mockups show the **same build** under two themes:
`brandName:"SloopWorks", accent:#2A53F0` (default) and
`brandName:"Dayfold", accent:#C75C3C, brandMark:fold-glyph` (consumer) — identical
layout, different identity.

---

## 4. Interaction notes

**Bubble → drawer.** Tap bubble → scrim fades in (120ms) + sheet springs up
from dock edge (180ms, ease-out `cubic-bezier(.2,0,0,1)`). Bubble is draggable;
on release it snaps to the nearest screen edge and persists its corner. Drawer
dismiss: tap scrim, swipe handle down (phone), Esc (desktop), or ✕.

**Panel nav (list → detail).** Drawer opens to the **panel list** (host home).
Tapping a row pushes a detail view: header swaps to back (‹) + panel title +
panel-specific actions; content slides in from the end. Back returns to list.
Nav state is retained while the drawer is open in a session.

**Apply & Restart (hero).** Environment panel lists named backends with the
active one marked. Selecting a different one is *staged* (radio moves, sticky
action bar enables "Apply & Restart", current vs. pending shown). Tapping it
raises a **confirm sheet** ("Switch to staging? The app will restart and your
session/state will be cleared."). Confirm → **blocking overlay** ("Switching
environment… Restarting") → app re-inits against the new backend and the drawer
reopens on the Environment panel showing the new active env + a success toast.
Cancel anywhere reverts the staged selection.

**Log filtering.** Segmented V/D/I/W/E filter (single-select; V = show all).
List virtualizes for high volume; newest at bottom with auto-follow that pauses
on manual scroll-up (a "Jump to latest" pill appears). Tap a row → log detail
with full message + metadata and **Share/Export** (system share sheet; export
writes a `.log`). Empty state when no entries match the filter.

**Copy.** Any key/value or log field is tap-to-copy; a toast confirms. Copy
never navigates.

---

## 5. Adaptive behavior (multiplatform, phone-first)

| Form factor | Drawer presentation | Notes |
|-------------|--------------------|-------|
| **Phone (compact width)** | **Bottom sheet**, ~92% height, drag handle, modal scrim. | Default. One column. |
| **Tablet / foldable (medium)** | Bottom sheet capped at ~520dp wide, centered, OR right side sheet in landscape. | Same content, more breathing room. |
| **Desktop / large** | **Right side sheet**, fixed ~420dp wide, full height, no scrim dimming the whole app (non-modal); Esc + ✕ to close. | List and detail can sit side-by-side (list rail + detail pane) when width allows. |

The bubble docks to a corner on every form factor; on desktop it can live in a
window corner and is keyboard-focusable. Content, density, and component shapes
are identical across form factors — only the **container** changes.

---

## 6. Accessibility (WCAG-AA, ADR 0009)

- **Touch targets ≥48dp** for bubble, rows, filter segments, action buttons,
  close/back. Spacing prevents adjacency mis-taps.
- **Contrast AA** in both themes: body text ≥4.5:1, large text/UI ≥3:1.
  Accents and log-level pills are tuned per theme (dark uses lighter accent
  `#86A1FF`, light uses `#2A53F0`) to keep `onAccent` and pill text legible.
- **Content descriptions** on bubble ("Open debug drawer, N unread"), every
  icon-only control (close, back, copy, share, filter segments), and log-level
  pills (level announced, not color-only — letter + label).
- **Color is never the only signal**: log levels carry a letter + label; active
  environment carries a "ACTIVE" text chip + radio, not just accent.
- **Focus order & visible focus**: logical order (header → content → action
  bar); 2dp accent focus ring on desktop/keyboard.
- **Reduced motion**: when the OS requests it, spring/slide transitions become
  instant cross-fades; auto-follow scrolling still works but without smooth
  animation.
- **Text scaling**: layout reflows to OS font scale up to 200%; rows grow,
  values wrap rather than truncate where copy matters.

---

## 7. Architecture-forced UX compromises (flagged, not redesigned)

1. **Single bubble = discoverability vs. occlusion.** One floating bubble can
   cover app UI under it. Mitigation (design-only): edge-snapping, draggable,
   and modest 56dp size; it never expands on hover. *No architecture change.*
2. **Host-listed panels = one nav model for all.** The Redux DevTools adapter
   is a rich third-party surface forced into the host's list→detail chrome and
   density. We give the adapter a full-height detail pane and let it own its
   internal scroll, but its visual language won't be 100% native. Flagged.
3. **Debug-only = zero release footprint.** Nothing here ships in release; that
   means **no in-app fallback** if a developer is on a release build — by
   design. The bubble's absence is the signal.
4. **Themeable but fixed shapes.** A consumer app can match color/identity but
   **cannot** restyle layout/density. This is intentional (cross-app muscle
   memory) but means a highly-branded app's drawer will still read as "the
   SloopWorks drawer wearing your colors," not a bespoke surface. Flagged as a
   deliberate trade.
