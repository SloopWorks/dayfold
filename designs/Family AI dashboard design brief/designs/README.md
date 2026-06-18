# Hearth — designs

Hi-fi mockups for **family-ai-dashboard** (working name *Hearth*). Material 3
Expressive, adaptive — vibrant visuals, calm behavior. Light is the hero;
dark is first-class. Component names map 1:1 to Compose M3.

**Open [`Index.dc.html`](Index.dc.html)** to click through everything.

| Area | File | Contents |
|---|---|---|
| Index | `Index.dc.html` | Landing — links every surface |
| Design system | `Design-System.dc.html` | Color roles + tonal palettes (L+D), type scale, shape, elevation/surface tiers, motion, component inventory (L+D), provenance & accessibility |
| Now (briefing) | `Now.dc.html` | Feed, empty, loading — light + dark |
| Hubs (dossiers) | `Hubs.dc.html` | List, detail (all 8 block types), deep-link arrival (highlight pulse), graceful fallback, empty — light + dark |
| Adaptive + Wear | `Adaptive.dc.html` | Tablet list-detail + rail, foldable dual-pane, desktop drawer + grid, Wear tile + complication |

`Now-Phone.dc.html` and `Hubs-Phone.dc.html` are the parameterized phone
components (props: `mode` = light/dark, `view`) the galleries mount — they
map to single Compose screens.

## Seed colors
Coral `#FF5436` (primary) · Teal `#11B5A4` (secondary) · Violet (tertiary).
On Android, **dynamic color** would remap these from the wallpaper.

## Type
**Outfit** — expressive display/headline/title. **Figtree** — body/label.
Material Symbols Rounded for iconography.
