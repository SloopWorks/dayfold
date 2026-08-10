# Design brief — debug drawer component picker + report handoff

## Purpose

Extend the approved SloopWorks debug drawer and SWIP bug-report annotation flow
with a developer-only component viewer. A dogfooder can open the drawer, choose
**Components**, tap the live app, inspect the selected Compose component and its
ancestor path, then attach that selection to a bug report.

The interaction should feel like a compact mobile Layout Inspector: precise and
fast, but legible on a phone and safe over arbitrary app content.

Claude Design execution handoff:
`designs/PROMPT-debugdrawer-component-reporting-hifi.md`.

## Existing references to preserve

- `designs/debug-drawer/Debug Drawer.dc.html` and
  `designs/debug-drawer/spec.md`: drawer shell, panel rows, typography, spacing,
  compact/expanded behavior, light/dark tokens.
- SWIP bug reporter approved annotation design: Draw / Blur / Point tool,
  dashed component highlight, review/consent flow.
- Existing implementation vocabulary: App Info, Backend, Logs, Redux, SWIP.

Do not redesign the drawer or reporter. Add the smallest coherent component flow
using their established chrome and tokens.

## Required screens/states

Design light + dark mobile frames (390×844 baseline) and one expanded-width frame
for the existing 420dp side sheet.

### 1. Components panel — idle

- Standard debug-drawer detail header: back + “Components”.
- Short explanation: tap the live app to inspect a component; touch input is
  blocked until selection/cancel, but the app may continue updating.
- Primary action: **Select on screen**.
- Secondary facts use the shared capability state: `Bounds + source available`,
  `Bounds available · source collected on first inspection`, `Bounds available ·
  source unavailable`, `UI-tree tooling disabled`, or `No component-inspection
  provider connected`. Capture only on demand; do not show stale component counts
  or recent selections in v1.
- **Select on screen** is enabled only when bounds capture is ready. Source absence
  does not disable bounds-based selection.
- Source degradation stays in this normal ready panel, never under an unavailable
  title. If `BoundsOnly` was chosen, say `Choose SourceOnDemand for file + line`;
  if source was not recorded for the build, say `Enable Compose source information
  for file + line`.

### 1a. Components panel — tooling unavailable

Keep the Components panel visible in a debug/developer build that registered the
plugin without a ready provider. Use reason-specific, non-alarming copy:

- Title: `Component inspection unavailable`.
- Provider disconnected: `No component-inspection provider is connected to this
  build.`
- Host disabled: `UI-tree tooling is disabled by this build configuration.`
- Capture failure: `The component tree couldn't be captured.` + **Retry**.
- Valid empty screen: `No selectable components were found on this screen.` No
  Retry/setup action; keep **Report without component details**.

Recovery is reason-specific:

- Provider disconnected: `Add debugdrawer-compose-inspector if absent, then pass
  its provider to this build.` Show **Copy setup snippet** and a docs link.
- Host disabled: `Set this build's UI-tree mode to BoundsOnly or SourceOnDemand.`
  Do not tell the developer to add an already-connected dependency.
- Capture failure: show **Retry** and diagnostic status, not setup instructions.

All unavailable states also offer **Report without component details**, which
closes the drawer and opens the Bug annotation flow in manual-drag mode. Setup
instructions remain local UI and are never attached to a report.

### 2. Live selection overlay

- Drawer closes and the app is fully visible.
- A restrained top instruction strip: “Tap a component · Cancel”. It must respect
  status-bar insets and not cover the likely tap target.
- Touch interception is explicit: ordinary app controls do not activate.
- On touch, tap selects immediately. Mouse/desktop hover may preview a candidate.
- Show candidate bounds with a dark/light outer keyline plus inner dashed accent;
  place its label on an opaque high-contrast surface within the viewport.
- Selected state locks the outline and opens component detail.
- Provide explicit `Parent` / `Child` 48dp controls and a `3 of 6` hierarchy
  position when nested components share bounds. Do not require repeated blind
  taps or dense phone-sized breadcrumbs.

### 3. Component detail

- Selected composable name with a clear fallback (`Unknown component`).
- Hierarchy path as a compact read-only summary plus the Parent/Child controls;
  expanded layouts may use selectable breadcrumbs.
- Key/value rows, using existing drawer primitives:
  - bounds;
  - source file;
  - line;
  - group key/build mapping status.
- Values that do not exist read `Unavailable in this build`; never fake them.
- Do not show or copy raw test tags in v1. A future role row requires the
  pinned-version semantics spike and a separate design/privacy update.
- Actions:
  - **Add to report** (primary);
  - Copy details;
  - Select another;
  - Close.
- Phone uses a signed-off 35–45% peek detail sheet that can expand; if it obscures
  the target, include a frozen highlighted screenshot preview. Expanded layout
  uses the existing 420dp side sheet so the selected app region stays visible.
- Back from detail returns to Components. **Select another** explicitly starts a
  new capture; detail never tracks a live node that can disappear.

### 4. Add-to-report handoff

- Reuse the current SWIP report flow, not a new form.
- **Add to report** goes directly to a Bug-only annotation flow. Hide/disable
  Feedback for this preseeded draft unless SWIP makes type reclassification
  update the persisted manifest and proves it end-to-end.
- The captured screenshot appears in the existing annotation editor with the
  selected bounds already highlighted.
- Point remains selectable to choose a different component: tap selects a
  component; drag makes a manual rectangle. The component id/path and highlight
  update together; a manual rectangle has no component id.
- If UI-tree tooling is unavailable, the reporter shows `Component snapping
  isn't available in this build. Drag to mark an area instead.` only while Point
  is selected. Put **How to enable** behind a disclosure/docs link; do not place
  Copy setup or multi-step build guidance in the primary annotation layout.
  Reporting and manual annotation remain usable.
- Review shows `Component details attached`; expanding it shows selected
  name/path/source when available, node count, trimmed status, and
  `No text or state included`. The tree remains bundled with screenshot consent.
- Toggling off screenshot visibly removes both highlight and component details.

### 5. Empty/degraded/error states

- No selectable component tree for the current screen (manual report only).
- Component-inspection provider not connected to this build.
- UI-tree mode disabled by host configuration.
- Source file/line unavailable but bounds still available.
- Tap lands on a decorative/unidentified region.
- Component tree exceeded its size budget and distant nodes were trimmed.
- Selection canceled.
- A dialog/popup/separate window makes picking unsupported; exit Picking without
  allowing the underlying control to receive the tap.

Failures should preserve the ability to draw a manual Point rectangle and submit
the report.

## Interaction rules

- Default hit result: attached, visible, nonzero, non-tooling; prefer actionable
  semantics only if the semantics prototype is approved, then the deepest source
  group on that tap's ancestor path.
- When candidates overlap, the user can move up/down the ancestor path without
  retapping.
- The selected rectangle uses the SWIP Point-tool dashed accent treatment.
- No component text, parameter values, state, or accessibility descriptions are
  shown in the metadata panel. Raw test tags are neither shown, copied, nor
  reported in v1. Visible screenshot pixels remain governed by blur + consent.
- Touch targets are at least 48dp; screen-reader labels must describe selection,
  hierarchy position, and missing-source states without relying on color.
- In unavailable states, the focusable status heading/reason precedes the disabled
  Select action; its accessibility state explains why selection is disabled.
  Retry announces busy/success/failure. Copy setup is a labeled 48dp target and
  confirms `Setup snippet copied` through a live region/snackbar. Content
  descriptions say “user interface tree” rather than relying on pronunciation of
  `UI-tree`.
- After selection, TalkBack gets an ordered component-detail/list alternative
  with Parent, Child, Add to report, Select another, and Cancel actions; the
  full-screen pointer layer alone is not the accessibility interface.
- Respect reduced motion. Selection outline may fade/scale subtly but must not
  pulse indefinitely. The shared host must read the OS preference; changes snap
  or crossfade when reduced motion is enabled.
- The overlay must be obviously dismissible and must never strand the app in
  intercepted-tap mode after Back/Escape, rotation, backgrounding, or host
  disposal. Back during Picking cancels selection rather than navigating the app.
- Before capture, keep input blocked but suppress drawer/bubble/edge tab,
  instruction strip, labels, and outlines for the capture frame. None may appear
  in screenshot pixels or the component tree.

## Release representation

This UI is debug/internal only in v1. No Components row, picker, tree capture, or
mapped-source wording exists in the public release build. Design source as
optional within debug/internal builds:

- debug: `ResponseSheet.kt · line 146`;
- unavailable: `Source unavailable in this build`.

An `internalMinified` representation is future work only after that variant and
mapping precision are explicitly approved. Never show raw mappings/absolute paths.

## Host and shell prerequisites

- One host-owned state machine: Closed → Components → Picking → frozen Detail →
  report handoff. Back from Picking returns to Components; Back from Detail
  returns to Components; Select another starts a new Picking capture.
- The shared shell—not plugin-local state—owns input interception and clears it on
  Back/Escape, background, rotation, disposal, and unsupported separate windows.
- Before reusing drawer chrome here, fix back/close to 48dp targets, apply safe
  drawing insets, and wire the OS reduced-motion preference in the shared host.
- On unsupported platforms, do not register a disabled Components row.
- In public release, register neither Components nor the enablement-help UI. In a
  developer build, the app may register `debugdrawer-components` without the
  tooling provider specifically so developers can discover how to enable it.

## Deliverables

1. Components panel: ready, source-on-first-inspection, provider-not-connected,
   host-disabled, capture-error, no-tree, and source-unavailable states.
2. Live selection overlay: candidate, selected, and nested ancestor selection.
3. Component detail sheet/panel, compact and expanded.
4. Existing SWIP annotation/review frames showing preselected component handoff.
5. Light/dark token notes and interaction/motion annotations.
6. A short component/state sheet covering high-contrast outline, source row,
   hierarchy controls, selection strip, consent expansion, and degraded states.
7. Interaction annotations for Back/Escape/background, separate windows,
   200% text/TalkBack, reduced motion, and clean capture-frame suppression.
8. Shared enablement-help component in drawer and reporter contexts, including
   Copy setup snippet, retry, and manual-annotation fallback.

## Questions to surface, not invent

- Should the phone peek sheet begin at 35%, 40%, or 45% height after testing the
  selected-target preview at 200% text?
