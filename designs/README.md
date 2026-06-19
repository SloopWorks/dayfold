# Dayfold — designs

Hi-fi mockups for **family-ai-dashboard** (working name *Dayfold*). Material 3
Expressive, adaptive — vibrant visuals, calm behavior. Light is the hero;
dark is first-class. Component names map 1:1 to Compose M3.

**Open [`Index.dc.html`](Index.dc.html)** to click through everything.

| Area | File | Contents |
|---|---|---|
| Index | `Index.dc.html` | Landing — links every surface |
| Design system | `Design-System.dc.html` | Color roles + tonal palettes (L+D), type scale, shape, elevation/surface tiers, motion, component inventory (L+D), provenance & accessibility |
| Now (briefing) | `Now.dc.html` | Feed, empty, loading — light + dark |
| Hubs (dossiers) | `Hubs.dc.html` | List, detail (all 8 block types), deep-link arrival (highlight pulse), graceful fallback, empty — light + dark |
| Auth & invite | `Auth.dc.html` | Not-signed-in sign-in/up (Google/Apple/phone + OTP), backup-method nudge, create-family onboarding, member-join, QR / link invite + approvals — light + dark (ADR 0010) |
| Adaptive + Wear | `Adaptive.dc.html` | Tablet list-detail + rail, foldable dual-pane, desktop drawer + grid, Wear tile + complication |
| Content library | `content/Index.dc.html` | 6 typed content types (file/link/invite/contact/geo/email) × Now card / Hub block / Detail; `Content-Library.dc.html` (type catalog), `Detail-Views.dc.html` + `Detail-Phone.dc.html` (per-type detail, L+D), `Tap-To-Detail.dc.html` (live container-transform prototype). Governs ADR 0022; epic `planning/content-detail-epic.md` |
| Content · adaptive | `content/adaptive/Index.dc.html` | Two-pane content detail across breakpoints (`Breakpoints.dc.html`), the detail-in-pane per type (`Detail-Pane.dc.html` / `Detail-Pane-View.dc.html`), pane states (empty/foldable-hinge/loading/offline — `States.dc.html`), and nav continuity bar→rail→drawer with scaffold nesting (`Nav-Continuity.dc.html`). Built from `DESIGN-BRIEF-content-adaptive.md`; governs CL-NAV/CL-10 |
| Triggers | `triggers/Index.dc.html` | Content/place/notification triggers + permission + privacy-affordance surfaces (ADR 0014) |

`Now-Phone.dc.html`, `Hubs-Phone.dc.html` and `Auth-Phone.dc.html` are the
parameterized phone components (props: `mode` = light/dark, `view`) the
galleries mount — they map to single Compose screens.

## Seed colors
Coral `#FF5436` (primary) · Teal `#11B5A4` (secondary) · Violet (tertiary).
On Android, **dynamic color** would remap these from the wallpaper.

## Type
**Outfit** — expressive display/headline/title. **Figtree** — body/label.
Material Symbols Rounded for iconography.
