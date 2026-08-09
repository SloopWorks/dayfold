# Dayfold — designs

Hi-fi mockups for **family-ai-dashboard** (working name *Dayfold*). Material 3
Expressive, adaptive — vibrant visuals, calm behavior. Light is the hero;
dark is first-class. Component names map 1:1 to Compose M3.

**Open the [legacy gallery index](Family%20AI%20dashboard%20design%20brief/designs/Index.dc.html)**
for the original product surfaces. Newer area-specific galleries are linked below.

| Area | File | Contents |
|---|---|---|
| Index | `Family AI dashboard design brief/designs/Index.dc.html` | Legacy landing — links the original product surfaces |
| Smart Briefings | [`routine-integration/Index.dc.html`](routine-integration/Index.dc.html) | Imported setup, privacy, provider handoff, status, draft-review, recovery, and revoke flows; source-of-truth preview is explicitly non-live |
| Calendar reconciliation | [`DESIGN-BRIEF-calendar-reconciliation.md`](DESIGN-BRIEF-calendar-reconciliation.md) | Self-contained three-prompt brief for device-local Calendar Check, native event handoff, reviewed Calendar→Dayfold proposals, privacy, conflicts, and notification ownership; hi-fi gallery pending |
| Design system | `Design-System.dc.html` | Color roles + tonal palettes (L+D), type scale, shape, elevation/surface tiers, motion, component inventory (L+D), provenance & accessibility |
| Now (briefing) | `Now.dc.html` | Feed, empty, loading — light + dark |
| Hubs (dossiers) | `Hubs.dc.html` | List, detail (all 8 block types), deep-link arrival (highlight pulse), graceful fallback, empty — light + dark |
| Auth & invite | `Auth.dc.html` | Not-signed-in sign-in/up (Google/Apple/phone + OTP), backup-method nudge, create-family onboarding, member-join, QR / link invite + approvals — light + dark (ADR 0010) |
| Adaptive + Wear | `Adaptive.dc.html` | Tablet list-detail + rail, foldable dual-pane, desktop drawer + grid, Wear tile + complication |
| Content library | `content/Index.dc.html` | 6 typed content types (file/link/invite/contact/geo/email) × Now card / Hub block / Detail; `Content-Library.dc.html` (type catalog), `Detail-Views.dc.html` + `Detail-Phone.dc.html` (per-type detail, L+D), `Tap-To-Detail.dc.html` (live container-transform prototype). Governs ADR 0022; epic `planning/content-detail-epic.md` |
| Content · adaptive | `content/adaptive/Index.dc.html` | Two-pane content detail across breakpoints (`Breakpoints.dc.html`), the detail-in-pane per type (`Detail-Pane.dc.html` / `Detail-Pane-View.dc.html`), pane states (empty/foldable-hinge/loading/offline — `States.dc.html`), and nav continuity bar→rail→drawer with scaffold nesting (`Nav-Continuity.dc.html`). Built from `DESIGN-BRIEF-content-adaptive.md`; governs CL-NAV/CL-10 |
| Triggers | `triggers/Index.dc.html` | Content/place/notification triggers + permission + privacy-affordance surfaces (ADR 0014) |
| Now · derived | `now-derived/Index.dc.html` | Two-lane Now: merged feed (normal / geo-active / busy-overflow / dedup / softened / caught-up), priority & calm budget, deep-link arrival (container transform), why-chip catalog — light + dark. Governs ADR 0043; **signed off 2026-06-30** |
| Responses to smart content | [`content-feedback/Index.dc.html`](content-feedback/Index.dc.html) | The five-verb response vocabulary (not-now / hide / done / mute / fix-it) for machine-added content: the approved exploration board (`Feedback-Options.dc.html`, interactive sheet demo) plus the hi-fi gap set — mute scope + me/family, detail-view respond affordances, hub-block delete pairing, one-time swipe escalation, Settings › Smart content management surface, offline. Decisions + persistence contract in `content-feedback/NOTES.md`. **Approved 2026-08-08 — spec track** (ADR not yet written) |
| Weather-conditional | `weather/Index.dc.html` | Weather that qualifies content instead of reporting itself: the verified card + the unverified (no chip, no glyph) honesty case (`Qualified.dc.html`), the six device-derived glyphs + the authored-icon precedence rule (`Glyphs.dc.html`), the aggregate cause-group — new collapse shape, bands, authored recommendations, forecast link + attribution slot (`Aggregate.dc.html`), and mixed provenance + the hidden-card argument (`Provenance.dc.html`). Light + dark; decisions in `weather/RATIONALE.md`. Built from `DESIGN-BRIEF-weather-conditional.md`; **awaiting sign-off — and sign-off does not authorize build** (weather ADR still unwritten) |

`Now-Phone.dc.html`, `Hubs-Phone.dc.html` and `Auth-Phone.dc.html` are the
parameterized phone components (props: `mode` = light/dark, `view`) the
galleries mount — they map to single Compose screens.

## Seed colors
Coral `#FF5436` (primary) · Teal `#11B5A4` (secondary) · Violet (tertiary).
On Android, **dynamic color** would remap these from the wallpaper.

## Type
**Outfit** — expressive display/headline/title. **Figtree** — body/label.
Material Symbols Rounded for iconography.
