# Debug Drawer — Component inspection & report handoff · Design spec

> ADR 0008 design gate. Companion mockups: `Index.dc.html`, `Components-Phone.dc.html`,
> `Selection-and-Detail.dc.html`, `Reporting-Handoff.dc.html`, `Adaptive-and-States.dc.html`,
> `Backend-Traceability.dc.html`.
> Architecture is fixed by `docs/superpowers/specs/2026-08-10-debugdrawer-component-reporting-review.md`;
> this document specifies visuals, interaction, states, copy, accessibility, and responsiveness.
> Debug/developer builds only. Public release registers no Components row, help UI, picker,
> tree capture, or source strings.

---

## 1. Frame inventory & journey map

Flow (host-owned): `Closed → Components → Picking → Frozen detail → Report handoff`,
then backend-side: `Uploaded → Component-source resolution (exact | mapped | best-effort | ambiguous | unavailable)`.

| Frame | File | Shows |
|---|---|---|
| A0 | Components-Phone | Components row in the drawer panel list (dark) |
| A1–A4 | Components-Phone | Ready panel: all four source states (light+dark for A1; token-identical omissions annotated) |
| B1–B4 | Components-Phone | Bounds unavailable: provider missing / host disabled / capture failure / valid empty |
| C1 (skin) | Components-Phone | Dayfold theming proof |
| C1–C5 | Selection-and-Detail | Candidate targeting, locked+detail, nested stepping, edge target, clean capture frame |
| D1–D2 | Selection-and-Detail | Unknown component + Unavailable rows; obscured target with frozen preview |
| E1–E3 | Selection-and-Detail | 35/40/45% peek at 200% text + recommendation |
| F1–F4 | Reporting-Handoff | Preseeded annotation, Point reselect, manual rectangle, snapping-unavailable strip |
| G1–G2 | Reporting-Handoff | Review with expanded component details; consent off |
| H1 | Adaptive-and-States | 420dp side sheet detail |
| K1–K5 | Backend-Traceability | Post-upload component-source card: exact, mapped, best-effort, ambiguous, unavailable (+ 4-reason legend) |
| F1–F3, I1–I3, J | Adaptive-and-States | Feedback, contrast, 200% text, TalkBack, dialog exit, state machine |

## 2. Component inventory — reuse vs. new

Reused unchanged (drawer spec C-numbers): C2 drawer host/scrim, C3 host header, C4 panel
list row (new Components row instance), C5 key/value row, C8 sticky action area pattern,
C13 toast/snackbar, C14 empty state, C15 error state. Reused from SWIP: annotation canvas,
Draw/Blur/Point toolbar, dashed component-highlight + corner ticks, consent row +
expansion, "Not included" dashed zone.

New components (all built from drawer primitives/tokens):

| Id | Component | Notes |
|---|---|---|
| N1 | Capability status card | Bordered 11dp-radius card: 8dp state dot + label + mono sub-line; optional attached source-note row on `--s2` |
| N2 | Enablement help block | `--s2` card: reason help text, optional versioned snippet box (mono 10/1.7 on `--bg`), Copy setup snippet + Docs buttons (48dp) |
| N3 | Selection overlay | Instruction strip, interception layer, candidate/locked outlines, label chip |
| N4 | Hierarchy control row | Parent / Child 48dp buttons + "n of m" position + compact path (phone) or breadcrumb chips (expanded) |
| N5 | Frozen detail sheet | 40% peek (recommended), drag-expandable; pinned action bar; optional frozen-preview thumbnail |
| N6 | Component-details consent expansion | Inside SWIP screenshot row: mini preview, Selected/Path/Source/Tree rows, "No text or state included" |
| N7 | Snapping-unavailable strip | Reporter-side, Point-only, with collapsed "How to enable" disclosure |
| N8 | Component-source card | SWIP report-detail slot: confidence badge, sanitized repo-relative path+line block, immutable rev chip, state-specific actions (K1–K5) |

## 3. Copy table (exact strings)

Availability / source (drawer panel):

| State | Primary string | Secondary |
|---|---|---|
| Ready(Available) | `Bounds + source available` | `captured on demand · nothing stored yet` |
| Ready(AvailableOnDemand) | `Bounds available · source collected on first inspection` | `The first source-aware inspection turns on Compose source collection for the rest of this session.` |
| Ready(DisabledByMode) | `Bounds available · source unavailable` | sub `this build uses BoundsOnly`; hint `Choose SourceOnDemand for file + line` |
| Ready(NotRecordedForBuild) | `Bounds available · source unavailable` | sub `source was not recorded for this build`; hint `Enable Compose source information for file + line` |
| Unavailable title (all) | `Component inspection unavailable` | — |
| ProviderNotConnected | `No component-inspection provider is connected to this build.` | help `Add debugdrawer-compose-inspector if absent, then pass its provider to this build.` |
| DisabledByHost | `UI-tree tooling is disabled by this build configuration.` | help `Set this build's UI-tree mode to BoundsOnly or SourceOnDemand.` (no dependency instruction) |
| BoundsCaptureFailed | `The component tree couldn't be captured.` | diagnostic `last attempt HH:MM:SS · <cause>`; actions Retry + report-without |
| NoTreeForCurrentScreen | `No selectable components were found on this screen.` | `This can be valid — an empty or fully decorative screen. Navigate the app and reopen Components.` No Retry/setup |

Other surfaces:

| Where | String |
|---|---|
| All unavailable states | button `Report without component details` |
| Instruction strip | kicker `SELECT COMPONENT` · `Tap a component` · `Cancel` |
| Interception note | `app taps blocked · app keeps updating` |
| Detail fallback name | `Unknown component` (+ sub `no name recorded for this node`) |
| Missing detail value | `Unavailable in this build` |
| Debug source row format | `ResponseSheet.kt · 146` (basename only, 1-based line) |
| Detail: group-key row | `Group key` → `Captured` / `Not captured` (pre-upload status, never a raw key dump; mapping availability appears only post-upload) |
| Detail: code-link row | `Code link` → `Resolved after upload` / `Build not indexed` / (row absent when source unavailable — the source rows already say `Unavailable in this build`). Never implies basename + line is an exact link |
| Reporter, Point selected, tree unavailable | `Component snapping isn't available in this build. Drag to mark an area instead.` + collapsed `How to enable` |
| Review row subtitle | `component details attached · <size>` |
| Consent expansion guarantee | `No text or state included. Removed with this toggle.` |
| Consent-off row subtitle | `screenshot, component tree, selected component + highlight all removed` |
| Copy confirmation | `Setup snippet copied` |
| Dialog/window exit toast | `Component picking isn't supported over this window. Selection ended.` |
| Panel footer (ready) | `debug builds only · absent from public release` |
| Panel footer (unavailable) | `setup guidance stays on this device · never attached to a report` |

Setup guidance is display-only local metadata: it never enters capture data, annotations,
manifest, or report bundle. The snippet text is owned and versioned by the shared artifact.
Setup copy and states are product-agnostic shared tooling (Dayfold today, Dinners/PickedPlate
next): no product-specific module, package, or backend assumptions.

Backend resolution (Component source card, `Backend-Traceability.dc.html`):

| State | Badge | Body | Actions |
|---|---|---|---|
| Exact | `Exact build match` (ok) | repo-relative path : line · `rev <short>` · `resolved against the producing build` | **Open source** (primary, commit-pinned) · Copy code reference |
| Mapped | `Exact build match` (ok) | same presentation · provenance note `Resolved from this build's Compose/R8 mapping` | **Open source** · Copy code reference (mapping never exposed/downloadable) |
| Best-effort unique | `One likely match` (warn) | path : line + one-line evidence (e.g. basename+line match; group key not recorded) | **Open best-effort match** (secondary style, never "Open source") · Copy code reference |
| Ambiguous | `Multiple source matches` (warn) | ≥2 candidate path : line rows, per-row copy only | No preselection, no single Open action |
| Unavailable | reason string (muted) | `Build not indexed` / `Source not recorded` / `Mapping unavailable for this build` / `No source match` | Copy code reference kept whenever a safe file/line hint exists |

## 4. State & transition table

| From | Event | To | Notes |
|---|---|---|---|
| Closed | bubble tap → Components row | Components | standard drawer nav |
| Components (ready) | Select on screen | Picking | drawer sheet slides down 180ms; strip fades in 120ms; interception on |
| Picking | tap | Frozen detail | capture coordinator: tree snapshot + PixelCopy under one capture id; chrome suppressed for the capture frame |
| Picking | Cancel / Back / Escape | Components | interception cleared; app not navigated |
| Picking | dialog/popup/separate window | Components | exit toast; underlying control never receives the tap |
| Frozen detail | Parent / Child | Frozen detail | steps only within the retained hit path; deterministic |
| Frozen detail | Add to report | Report handoff | single-use capture token → preseeded SWIP Bug draft |
| Frozen detail | Select another | Picking | explicit **new** capture |
| Frozen detail | Back / Close | Components | capture discarded |
| Any | background / rotation / host disposal | Components or Closed | interception cleared unconditionally |
| Report handoff | consent toggle off | — | PNG + tree + selected id/path + highlight removed together |

Distinctions preserved everywhere: valid empty tree ≠ capture failure; source degradation
(ready) ≠ bounds unavailability; pre-upload source availability ≠ post-upload resolution
confidence; `Ready` always keeps bounds selection enabled. Backend re-resolution may move
unavailable → resolved only against the same immutable build manifest — never a newer
revision, never a latest-branch fallback.

## 5. Selection hit path & Parent/Child

- Candidates: attached, visible, nonzero-area, non-tooling nodes only.
- V1 default target (semantics spike not approved): the **deepest source group** on the
  single ancestor path through the tap point. If the semantics adapter is later approved,
  prefer an actionable semantic node first.
- Ties resolve by stable source-tree order, never area alone.
- Parent/Child move strictly along the retained tap path; position label `n of m` counts
  from the path root. Stepping updates outline, name, path, and rows together.
- Touch: tap selects immediately. Hover-preview of candidates is desktop/mouse only.
- Manual rectangles (reporter Point drag) are separate geometry with `node_id: null`.

## 6. Visual annotations

Tokens: all colors are drawer theme roles (`--bg --surface --s2 --s3 --border --bstrong
--text --muted --faint --accent --onacc --accsoft --ok --warn --err`); SloopWorks and
Dayfold values per drawer spec §3. Fixed shapes/density are unchanged: 4dp spacing rhythm,
row heights, 11dp button radius, 22dp sheet radius, 2dp/24dp iconography.

- **Status card (N1):** 1dp `--border`, radius 11, padding 14; dot 8dp (`--ok` for any
  ready state); label Geist 14/500; sub Mono 11 `--faint`. Source-note row: `--s2`,
  info glyph 14, text 12.5 `--muted`, external-link glyph trailing.
- **Buttons:** primary 48dp accent fill / `--onacc` 14/600; secondary 48dp 1dp `--bstrong`
  border; disabled primary = `--s2` fill + `--faint` text + 1dp `--border` (plus
  programmatic disabled state + reason).
- **Candidate outline:** inner 2dp dashed accent; 2dp white keyline both sides of the
  stroke; 1.5dp dark outer ring (rgba text-color .4–.55). Radius follows target + 2dp.
- **Locked outline:** 2.5dp solid accent + 2dp white keyline + 4dp corner ticks
  (10–11dp legs, SWIP Point treatment).
- **Ancestor ghost (nested stepping):** previous node at 1.5dp dashed accent 40%.
- **Label chip:** Mono 10.5 on rgba(18,19,23,.92) (light content) or rgba(243,243,245,.95)
  (dark content); radius 6; padding 5×9; clamps inside viewport; flips below the bounds
  when the target hugs the top edge.
- **Instruction strip:** height 88dp incl. status-bar inset, rgba(10,10,12,.82) +
  8dp backdrop blur (SWIP annotation chrome — always dark-translucent); relocates to the
  opposite edge when the locked/candidate target is within 120dp of it.
- **Detail sheet:** surface, top radius 22, top border, shadow `0 -8 28 -10` @25%;
  handle 34×4; name Geist 16/600; source sub Mono 11 `--muted`; k/v rows label 13
  `--muted` / value Mono 12, 1dp separators; pinned action bar with top border.
- **Elevation:** drawer/sheet shadows identical to existing drawer; label chips and
  toasts use the drawer toast shadow.

## 7. Responsive rules

| Width class | Behavior |
|---|---|
| Phone (compact) | Panel = modal bottom sheet (~92%). Detail = 40% peek (recommended), drag-expandable to ~92%; k/v scrolls under pinned actions; frozen-preview thumbnail appears when selection intersects the sheet region |
| Tablet (medium) | Same content; sheet width-capped ~520dp centered (drawer spec §5) |
| Desktop / ≥expanded | 420dp right side sheet, non-modal; list rail + detail pane; breadcrumb chips become selectable; Esc cancels Picking/closes; selection outline remains visible beside the sheet |

## 8. Clean capture / tooling suppression

For the capture frame: drawer, bubble/edge tab, instruction strip, label chips, and all
outlines are suppressed; input interception stays active; PixelCopy and the tree snapshot
are pinned to one main-thread sequence under a single capture id. No tooling appears in
screenshot pixels or as tree nodes (registry excludes tooling composition roots). Chrome
restores immediately after capture (<250ms round trip, no flash animation). Screenshot and
bounds share the Activity-window coordinate space; insets are not manually subtracted.

## 9. Loading, empty, degraded, failure, manual fallback

- Capture-in-progress: Retry/Select buttons show busy spinner state; announce "Retrying capture".
- Valid empty (B4): no Retry, no setup; report-without remains.
- Degraded source (A3/A4): normal ready panel; never under the unavailable title.
- Failure (B3): Retry + diagnostic timestamp; no setup instructions.
- Tree trimmed: surfaced only in the review expansion (`41 nodes · trimmed`); selected
  path is preserved first under the 50 KiB UTF-8 budget.
- Every degraded/failed state preserves manual rectangle annotation and Send.

## 10. Accessibility

- **Targets:** back, close, Cancel, Parent, Child, Add to report, Copy details, Select
  another, Copy setup snippet, Docs, Retry, Report without component details — all ≥48dp.
  Shell back/close corrected to 48dp as a prerequisite (with safe-drawing insets and the
  OS reduced-motion provider).
- **Focus order (unavailable panel):** status heading + reason → recovery actions →
  disabled Select on screen (disabled state repeats the reason) → Report without
  component details. The reason is announced before the disabled action.
- **TalkBack:** exact strings in Adaptive I2. Say "user interface tree", never "UI-tree".
  Selection announces name, hierarchy position, and source presence/absence. After
  selection an ordered detail list exposes Parent, Child, Add to report, Select another,
  Cancel — the pointer layer is never the only interface.
- **Live regions:** `Setup snippet copied`; Retry busy/success/failure.
- **Text scaling:** reflow to 200%: rows grow, values wrap; status card + primary action
  always survive; detail keeps name, hierarchy controls, ≥1 value row, pinned Add to
  report (basis of the 40% recommendation).
- **Contrast:** AA in both themes and both skins; outline uses the white+dark double
  keyline so accent contrast never depends on content; color never the only signal
  (dot + words, letter + label, chip + announcement).
- **Keyboard (expanded):** logical tab order, 2dp accent focus ring, Esc cancels.
- **Reduced motion:** OS preference via shared host; all transitions snap/crossfade
  ≤120ms; no indefinite pulse in any mode.

## 11. Privacy & consent

- Report carries only: capture-local node ids, parent ids, bounds, integer Compose group
  keys, best-effort name, source file **basename** + 1-based line, viewport, selected id +
  ancestor path, trimmed flag. Schema `swip:bugreport:ui-tree:1` (SWIP-owned).
- **Forbidden in UI and transport:** component text, parameter values, state,
  accessibility descriptions, raw test tags, absolute paths, mapping-file contents,
  stringified non-integer group keys, enablement/setup guidance, stale counts.
- Component details ride the screenshot consent toggle: switching it off removes PNG,
  UI tree, selected identity, and highlight together (G2).
- Handoff is Bug-only; Feedback is absent for preseeded drafts until SWIP proves persisted
  type reclassification end-to-end.
- Frozen full tree lives only in draft memory for hit testing; persistence keeps the
  selected path + geometry needed by annotations.

## 12. Build-provenance presentation rules

- Paths are sanitized repository-relative; never absolute, never module-internal noise.
- Revision shown as a short immutable id chip; Open links are commit-pinned to the
  producing build's revision. No latest-branch fallback exists anywhere.
- Confidence is honest and load-bearing: mapped presents as exact (with provenance note);
  best-effort is labeled `One likely match` with its evidence and an action that says
  best-effort; ambiguous never preselects or offers a single Open.
- Never shown: source text, credentials, raw group keys, mapping contents, digests as
  user-facing noise, repository controls.
- The report/backend authorization boundary sits outside the card (report-detail access
  implies card access; Open source defers to repository auth).

## 13. Implementation acceptance checklist

- [ ] Ready(any source state) keeps Select on screen enabled; only ProviderNotConnected /
      DisabledByHost / BoundsCaptureFailed / NoTreeForCurrentScreen disable it.
- [ ] Every bounds-unavailable state shows Report without component details and it opens
      the Bug flow in manual-drag mode.
- [ ] Recovery is reason-specific: snippet only for provider-missing; mode-change only for
      host-disabled; Retry only for capture failure; nothing for valid empty.
- [ ] Setup guidance never appears in capture, annotations, manifest, or bundle.
- [ ] Back/Escape from Picking → Components without app navigation; interception cleared
      on background/rotation/disposal/separate-window.
- [ ] Tap = component, drag = manual rectangle (`node_id: null`); Point reselection swaps
      id + path + highlight atomically; v1 = one component selection.
- [ ] Capture frame contains no tooling pixels or tooling nodes; tree+PNG form one
      capture-consistent pair (animated-target device test).
- [ ] Review expansion shows name/path/source when present, node count, trimmed status,
      "No text or state included"; consent toggle removes all four artifacts together.
- [ ] Detail shows `Unknown component` / `Unavailable in this build` fallbacks; no role or
      test-tag rows.
- [ ] 48dp targets, focus order, TalkBack strings ("user interface tree"), 200% reflow,
      AA contrast, reduced-motion snap/crossfade all verified on device.
- [ ] Public release build: no Components row, no help UI, dependency graph and APK scan
      clean per review §5.
- [ ] Detail shows `Group key · Captured/Not captured` and `Code link · Resolved after
      upload / Build not indexed`; basename + line is never presented as an exact link.
- [ ] Backend card: mapped ≠ downgraded, best-effort never labeled "Open source",
      ambiguous has no preselected candidate and no single Open action, unavailable keeps
      Copy code reference when a safe hint exists, re-resolution pins to the same build
      manifest.
- [ ] Setup copy contains no product-specific module/package/backend assumptions.

## 14. Open decisions for the operator

1. **Peek height:** 40% recommended (E1–E3 evidence: 35% clips the primary action at 200%
   text; 45% over-covers mid-screen targets). Parameterized either way.
2. **Retry success announcement copy** ("Capture ready") — confirm in a11y review.
