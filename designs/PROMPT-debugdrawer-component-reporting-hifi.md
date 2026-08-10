# Claude Design prompt — Debug Drawer component inspection + report handoff

You are the senior product designer responsible for extending the approved
SloopWorks Debug Drawer and SWIP bug reporter. Create implementation-ready hi-fi
mockups and a detailed interaction/visual specification for a developer-only
Compose component inspector.

Do not redesign the existing drawer or bug reporter. Preserve their visual
language, navigation, tokens, density, and established Draw / Blur / Point
annotation flow. Add the smallest coherent component-inspection experience.

## Read these sources first

Treat these as authoritative, in this order:

1. `designs/DESIGN-BRIEF-debugdrawer-component-reporting.md`
2. `docs/superpowers/specs/2026-08-10-debugdrawer-component-reporting-review.md`
3. `designs/debug-drawer/Debug Drawer.dc.html`
4. `designs/debug-drawer/spec.md`
5. `/Users/patrick/workspace/sloopworksinstrumentationplatform/design/bugreporter/Swip Bugreport UI.dc.html`
6. `/Users/patrick/workspace/sloopworksinstrumentationplatform/design/bugreporter/Swip Bugreport Spec.dc.html`

If a reference conflicts with the first two files, the brief and reviewed
architecture win. Do not invent behavior that contradicts the availability,
privacy, build-variant, or report-consent contracts.

## Product outcome

A developer or dogfooder can:

1. open Debug Drawer → Components;
2. see whether component bounds and source information are available in this
   build;
3. select a component directly on the live app without activating the underlying
   control;
4. navigate the selected component's ancestor path;
5. inspect bounds and best-effort composable/source information;
6. add the frozen selection to the existing SWIP Bug annotation flow;
7. reselect a component with Point or drag a manual rectangle;
8. understand exactly what component metadata will be attached and remove it by
   turning off screenshot consent.

This is a compact mobile Layout Inspector, not a general developer console. It
should feel calm, dense, precise, trustworthy, and fast under debugging cognitive
load.

## Fixed architecture and scope

- This is debug/developer UI. Public release contains no Components row, help UI,
  picker, tree capture, or source information.
- Integrating apps choose one UI-tree mode per build: `Disabled`, `BoundsOnly`,
  or `SourceOnDemand`.
- Bounds selection can be ready while source is unavailable. Missing source must
  never disable a working bounds picker.
- `SourceOnDemand` begins as “source collected on first inspection,” then changes
  to source available or source unavailable after the first completed capture.
- One selection model powers both the live picker and SWIP Point annotation.
- V1 supports one selected component/path. Manual rectangles are separate and
  carry no component identity.
- Do not show, copy, or report raw test tags, component text, parameters, state,
  accessibility descriptions, absolute file paths, or mapping-file contents.
- The report handoff is Bug-only unless SWIP later supports real persisted type
  reclassification.
- Failures must preserve manual annotation and report submission.

## Required canvas outputs

Create a new design set under:

`designs/debug-drawer-component-reporting/`

Produce:

1. `Index.dc.html` — journey overview and links to all frames.
2. `Components-Phone.dc.html` — phone drawer states in light and dark.
3. `Selection-and-Detail.dc.html` — live picker, nested targeting, and detail.
4. `Reporting-Handoff.dc.html` — SWIP annotation and consent/review states.
5. `Adaptive-and-States.dc.html` — 420dp side sheet, degraded states, text scale,
   accessibility, and motion notes.
6. `spec.md` — implementation-ready visual, interaction, state, copy,
   accessibility, and responsive specification.

Use the repository's existing `.dc.html` conventions and assets. Reuse established
drawer and SWIP components rather than making visually similar replacements.

## Required frames

Use 390×844 as the baseline phone. Show all primary phone frames in light and dark
unless a state is visually identical; annotate any intentional omission. Include
at least one expanded 420dp right-side-sheet frame.

### A. Components panel — ready and source states

Show:

- bounds + source available;
- bounds available · source collected on first inspection;
- bounds available · source unavailable because `BoundsOnly` is selected;
- bounds available · source unavailable because source was not recorded for this
  build.

The primary **Select on screen** action remains enabled in every bounds-ready
state. Source status is secondary and non-alarming.

Use these concise status concepts:

- `Bounds + source available`
- `Bounds available · source collected on first inspection`
- `Bounds available · source unavailable`
- `Choose SourceOnDemand for file + line`
- `Enable Compose source information for file + line`

Do not show stale component counts or recent selections.

### B. Components panel — bounds unavailable

Design distinct states with tailored recovery:

1. Provider not connected
   - `No component-inspection provider is connected to this build.`
   - Help: `Add debugdrawer-compose-inspector if absent, then pass its provider
     to this build.`
   - Actions: **Copy setup snippet**, docs link, and **Report without component
     details**.
2. Disabled by host
   - `UI-tree tooling is disabled by this build configuration.`
   - Help: `Set this build's UI-tree mode to BoundsOnly or SourceOnDemand.`
   - Do not tell the developer to add a dependency.
   - Include **Report without component details**.
3. Capture failure
   - `The component tree couldn't be captured.`
   - Actions: **Retry** and **Report without component details**.
   - Do not show build-setup instructions.
4. Valid empty screen
   - `No selectable components were found on this screen.`
   - No Retry or setup action.
   - Keep **Report without component details**.

In these states, **Select on screen** is visibly disabled, but the reason is
adjacent, focusable, and announced before the disabled action.

### C. Live selection overlay

Show:

- candidate targeting;
- selected/locked targeting;
- nested components sharing bounds;
- Parent / Child navigation with a hierarchy position such as `3 of 6`;
- Cancel and Android Back behavior;
- a target close to viewport edges/status/navigation bars.

Requirements:

- Drawer closes so the app is visible.
- Instruction strip: `Tap a component · Cancel`.
- Underlying app controls do not activate.
- Touch selects immediately; hover preview is desktop-only.
- Selection uses an inner accent stroke plus contrasting outer keyline so it
  remains visible over light, dark, and noisy imagery.
- Candidate label sits on an opaque, high-contrast surface and stays inside the
  viewport.
- Parent/Child are explicit 48dp actions; do not rely on repeated tapping or dense
  breadcrumbs.
- Show how tooling chrome disappears for the clean screenshot capture frame.

### D. Component detail

Show a phone peek sheet and expanded side-sheet version.

- Explore 35%, 40%, and 45% phone peek heights at 200% text, then recommend one
  with a short rationale; do not silently lock the choice.
- If the sheet obscures the target, include a frozen highlighted screenshot
  preview.
- Show selected composable name with `Unknown component` fallback.
- Show hierarchy summary plus Parent / Child.
- Key/value rows: bounds, source file, line, and group-key/build mapping status.
- Missing values use `Unavailable in this build`.
- Actions: **Add to report**, Copy details, Select another, Close.
- Back returns to Components. Select another begins a new capture.

Do not show role or test-tag rows in v1.

### E. SWIP handoff and annotation

Reuse the existing SWIP reporter rather than designing a new form.

Show:

- Bug annotation opened with the frozen screenshot and selected component already
  highlighted;
- Point reselecting a different component, updating identity/path/highlight
  together;
- a manual drag rectangle with no component id;
- UI-tree unavailable while Point is selected;
- screenshot consent on and off;
- expanded review details for the attached component.

When component snapping is unavailable, keep the primary annotation surface
quiet:

`Component snapping isn't available in this build. Drag to mark an area instead.`

Show it only while Point is selected. Put **How to enable** behind a collapsed
disclosure/docs link. Do not place the setup snippet or multi-step build guidance
in the primary annotation layout.

The review expansion should show selected name/path/source when present, node
count, trimmed status, and `No text or state included`. Turning off screenshot
consent removes screenshot, component tree, selected identity, and highlight
together.

### F. Feedback and accessibility states

Show/annotate:

- `Setup snippet copied` confirmation through snackbar/live region;
- Retry busy, success, and failure feedback;
- logical focus order;
- TalkBack wording that says “user interface tree,” not just `UI-tree`;
- 48dp minimum targets, including back, close, copy, Parent, Child, Retry, and
  Report without component details;
- 200% text reflow without clipping or lost actions;
- visible keyboard focus for expanded/desktop layout;
- reduced-motion behavior: snap/crossfade, never an indefinite pulse;
- high-contrast selection over light, dark, and visually noisy content.

## State and transition specification

Document this host-owned flow:

`Closed → Components → Picking → Frozen detail → Report handoff`

Specify:

- Back from Picking returns to Components without navigating the app.
- Back from Detail returns to Components.
- Select another returns to Picking for a new capture.
- Backgrounding, rotation, host disposal, or unsupported separate windows clear
  touch interception.
- A valid empty tree is different from capture failure.
- Source degradation is different from bounds unavailability.
- Public release registers none of these states or help surfaces.

## Visual direction

- Preserve the current Debug Drawer SloopWorks and Dayfold skins, token roles,
  density, typography, 4dp spacing rhythm, fixed shapes, and list→detail model.
- Preserve SWIP's Draw / Blur / Point toolbar and dashed component-highlight
  language.
- The result should look native to the existing tools, not like a new product
  inserted inside them.
- Use synthetic non-personal app content in screenshots.
- Color must never be the only status signal.
- Correct existing shell primitives where required by this feature: 48dp
  back/close targets, safe-drawing insets, and OS reduced-motion behavior.

## Spec requirements

In `spec.md`, include:

- frame inventory and journey map;
- component inventory and reuse/new-component mapping;
- exact copy table for every availability/source state;
- interaction/state-transition table;
- selection hit-path and Parent/Child behavior;
- phone/expanded responsive rules;
- spacing, sizing, typography, color, stroke, and elevation annotations;
- clean-capture/tooling-suppression behavior;
- loading, empty, degraded, failure, and manual-fallback behavior;
- accessibility semantics, focus order, announcements, text scaling, contrast,
  keyboard, and reduced-motion behavior;
- privacy/consent notes and explicit forbidden metadata;
- implementation acceptance checklist.

## Quality bar and handoff

Before finishing:

1. Compare every new frame against the existing drawer and SWIP references.
2. Confirm bounds-ready/source-unavailable never disables selection.
3. Confirm every bounds-unavailable state still permits manual reporting.
4. Confirm setup guidance is reason-specific and never appears in submitted data.
5. Confirm public release has no Components/help state.
6. Confirm light/dark, 200% text, focus, contrast, and reduced-motion annotations.
7. Confirm no raw test tags, text/state/parameters, absolute paths, or mapping data
   appear in the UI or spec.

Do not implement application code. Deliver the hi-fi `.dc.html` files and
`spec.md`, then summarize the recommended phone detail-sheet height and any
remaining operator decision that cannot be resolved through design evidence.
