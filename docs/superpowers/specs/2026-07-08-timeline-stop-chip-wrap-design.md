# Timeline stop — meta layout (chip wrap) fix

**Date:** 2026-07-08
**Status:** Design (approved)
**Related:** ADR 0045 (hub timeline), CL-timeline rendering (`TimelineDetail.kt`)

## Problem

On the hub timeline roadmap, a stop with a wide `assignee` **and** multiple
`attachments` renders broken: the meta area is a single non-wrapping, non-scrolling
`Row` (`TimelineDetail.kt:493`) holding the source tag + assignee + all attachment
`AssistChip`s. When they exceed the card width, Compose squeezes the trailing chip to
a sliver and its label wraps **one character per line**, which (a) balloons the stop
card to a huge height and (b) clips the remaining chips. Observed on-device for the
"Aug 1 deadline cluster" stop (major/focal + assignee "Lillian + Patrick" + 3
attachments: Money & Billing, Health & Forms, Financial Aid). Single-attachment stops
are unaffected.

## Goal

The meta area never clips or over-grows: assignee reads on its own line; attachment
chips wrap onto as many lines as needed. Layout-only — no data/model/behavior change.

## Design

In the stop's Column (`TimelineDetail.kt`, the block currently at ~488–580), replace
the single meta `Row` with two stacked pieces:

1. **Identity row** — a normal `Row` (unchanged treatment) with the (derived) source
   tag + the assignee avatar+name. Rendered when either is present.
2. **Attachments** — a **`FlowRow`** (`androidx.compose.foundation.layout.FlowRow`,
   `@OptIn(ExperimentalLayoutApi::class)`) of the existing `AssistChip`s, with
   `horizontalArrangement = Arrangement.spacedBy(8.dp)` and
   `verticalArrangement = Arrangement.spacedBy(8.dp)` so wrapped rows are evenly
   spaced. Rendered when `stop.attachments` is non-empty. Chip styling, colors, icons,
   `toCardAction()`, and the 48.dp min touch height are all unchanged.

Spacing: the identity row keeps the existing `top = 11.dp`; the attachments FlowRow
gets `top = 8.dp` when an identity row precedes it (else `11.dp`), so the vertical
rhythm matches the current single-line case.

Because nothing overflows anymore, the stop card height collapses back to its content
height (the balloon was purely the vertical char-wrap symptom). Applies uniformly to
major / next / collapsed stops and to both timeline scales (day + roadmap).

## Testing

- **Snapshot coverage:** the `hubTimeline()` fixture's major "Move-in day" stop
  currently has one attachment; bump it to **three** (mirroring the real Aug 1 worst
  case: two `open`/`nav` + one `call`) so the `timeline-detail` golden actually
  exercises the wrap. The golden **will change** → re-record both OS sets (macOS +
  linux docker) and eyeball.
- **Behavior test:** the existing `TimelineDetail`/`TlEntryRow` tests stay green; add a
  semantics/compose assertion (or rely on the snapshot) that all three chip labels are
  present for a multi-attachment stop (previously the third was clipped).
- **On-device (Pixel):** the Aug 1 stop renders compact with all three chips visible,
  no giant empty card, no dark sliver.

## Rollout

No ADR (render-layer fix within ADR 0045's authored timeline). CHANGELOG entry
(client): timeline stop attachment chips wrap instead of clipping.
