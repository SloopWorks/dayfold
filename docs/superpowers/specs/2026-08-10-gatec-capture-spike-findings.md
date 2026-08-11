# Gate C — capture feasibility spike findings

**Date:** 2026-08-10 · **Vehicle:** `apps/androidApp/src/androidTest/.../spike/UiTreeCaptureSpikeTest.kt`
run on `fad_atd35` (API 35 aosp_atd emulator; API-37 devices break the Compose test rule —
see the comment block in `apps/androidApp/build.gradle.kts`). 4/4 tests green.
Parent review: `2026-08-10-debugdrawer-component-reporting-review.md` §6 Gate C.

## Results against the gate's five proofs

1. **BoundsOnly without source activation — PROVEN.** Recording
   `currentComposer.compositionData` into a weak set and walking `asTree()` yields nonzero
   window-relative bounds for content nodes with **zero** `SourceLocation`s present before
   activation. No reflection needed; `ui-tooling-data:1.11.2` (`@UiToolingDataApi`).
2. **SourceOnDemand yields name/file/line — PROVEN.** Calling
   `currentComposer.collectParameterInformation()` and forcing a completed recomposition
   (spike used `key(collect)` to model "first source-aware inspection") produced
   file (`.kt`) + 1-based line on 9/36 nodes and the expected composable function name
   (`SpikeProduct`). Coarse one-way heap delta ≈ **64 KiB** for a trivial tree — real cost
   scales with tree size; acceptable for a debug session, matches the review's
   "accepts the additional allocations" framing. Activation is one-way per composition,
   as designed.
3. **Product-only registry excludes separate windows — PROVEN.** A `Popup` (separate
   window + separate composition) whose `CompositionData` is deliberately not recorded
   never appears in the captured tree while sibling product content does. Implication for
   Gate F: the recorder wraps ONLY the product content slot; same-window descendant
   subcompositions that should be included must register via `LocalInspectionTables`.
4. **Capture-consistent tree+pixels pair — PROVEN via synchronous decor draw.** Over 12
   frames of a moving target, snapshotting the tree and drawing
   `window.decorView` into a bitmap **inside one main-thread runnable** aligned 12/12
   (asserted). Async `PixelCopy` after the tree snapshot also aligned 12/12 in this
   harness, but the test clock quiesces frames between iterations — treat PixelCopy
   alignment as unconfirmed under continuous real-device animation. **Coordinator design:
   pin tree snapshot + synchronous window draw in one main-thread sequence** (the review
   §4.5 "synchronous app/decor draw mechanism"); PixelCopy may be used opportunistically
   only if the mandatory physical-device animated-target smoke shows it holds.
5. **Semantics adapter — SKIPPED, allowed.** v1 ships without semantics (review §6 Gate C
   item 5, "or v1 explicitly ships without semantics"). Target selection uses deepest
   source group; no role/test-tag capture.

## Carry-forwards into Gate F

- Provider = weak-set recorder + `asTree()` walk; allowlist copy of
  (name, group key when `Int`, bounds, source file/line/offset/packageHash) only.
  Never touch `Group.data` / `parameters`.
- `SourceOnDemand` first capture: activate → await one completed recomposition/frame →
  capture; then emit `Ready(Available)` or `Ready(NotRecordedForBuild)`.
- Paired capture coordinator: main-thread sequence {tree snapshot → decorView.draw()} under
  one capture id; suppress tooling chrome for that frame.
- The API-37 test-rule breakage constrains instrumented tests, not the product: shipping
  capture code has no espresso dependency. Emulator lane for CI stays API ≤36.

## Status

Gate C **passes** for the v1 scope (bounds + on-demand source, sync-draw pairing, no
semantics). The physical-device animated-target smoke in review §7 "Mandatory device
smoke" remains operator-assisted before DoD sign-off.
