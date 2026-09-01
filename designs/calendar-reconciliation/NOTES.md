# Calendar Check — design notes (ADR 0063 · ADR 0008 gate)

**Status: operator-approved 2026-08-28; implementation reference.** The full
34-state gallery is the ADR 0008 sign-off for Accepted ADR 0063. All people,
events, calendars and addresses are invented; **no real user data appears
anywhere**.

Open `Index.dc.html` for the full gallery (light + dark for every Dayfold screen; OS-owned
surfaces dash-labelled). User-facing name: **Calendar Check**. Banned vocabulary avoided
throughout: no "two-way sync", "mirror", "sync complete", "fixed automatically",
"monitors all calendars", no absolute no-duplicate promise, no "stays on your phone" claim
after an import (the field-preview screen states the sync consequence explicitly).

## Files

| File | Covers |
|---|---|
| `Calendar-Check-Phone.dc.html` | Now aggregate unit §12–15 · matched summary §28 · notification pair §29 · alert override §30 |
| `Permissions-and-Settings.dc.html` | Off/primer/OS-ask/chooser/denied/empty §1–6 · on/change/off/reset §31–34 |
| `Native-Handoff.dc.html` | Hub action §7 · prefill §8 · Android/iOS editors §9–10 · return states §11 |
| `Review.dc.html` | List §16–17 · suggested §18 · ambiguous §19 · differ §20 · recurring §21 · ignored §22 |
| `Import.dc.html` | Destination §23 · fields §24 · audience §25 · confirm §26 · apply states §27 |

## State coverage matrix (34 required states)

| § | State | Frame (file · view) |
|---|---|---|
| 1 | Settings — off | Permissions-and-Settings · `settings-off` |
| 2 | Read-access primer | Permissions-and-Settings · `primer` |
| 3 | OS permission transition | Permissions-and-Settings · `os-permission-android`, `os-permission-ios` |
| 4 | Calendar chooser | Permissions-and-Settings · `chooser` |
| 5 | Denied / restricted / revoked | Permissions-and-Settings · `denied` |
| 6 | No calendars / provider unavailable / work profile | Permissions-and-Settings · `no-calendars` |
| 7 | Eligible Hub action | Native-Handoff · `hub-action` |
| 8 | Prefill review | Native-Handoff · `prefill` |
| 9 | Android native editor | Native-Handoff · `android-editor` |
| 10 | iOS native editor | Native-Handoff · `ios-editor` |
| 11 | Return states (5) | Native-Handoff · `return-checking/-added/-canceled/-permission-changed/-unconfirmed` |
| 12 | Now — two gaps | Calendar-Check-Phone · `now-two-gaps` |
| 13 | Now — busy horizon | Calendar-Check-Phone · `now-busy` |
| 14 | Now — all clear | Calendar-Check-Phone · `now-all-clear` |
| 15 | Now — offline / unavailable | Calendar-Check-Phone · `now-offline` |
| 16 | Dayfold-only row | Review · `list` (top card) |
| 17 | Calendar-only row | Review · `list` (second card) |
| 18 | Suggested match | Review · `suggested-match` |
| 19 | Ambiguous match | Review · `ambiguous` |
| 20 | Details differ | Review · `details-differ` |
| 21 | Recurring event | Review · `recurring` |
| 22 | Ignored locally | Review · `ignored` |
| 23 | Choose destination | Import · `destination` |
| 24 | Field preview | Import · `fields` |
| 25 | Audience preview | Import · `audience-new`, `audience-existing` |
| 26 | Review and confirm | Import · `confirm` |
| 27 | Apply states (7) | Import · `apply-saving/-saved/-offline/-permission-lost/-role-denied/-source-changed/-conflict` |
| 28 | Matched summary | Calendar-Check-Phone · `matched-summary` |
| 29 | Distinct action nudge (paired example) | Calendar-Check-Phone · `notification-pair` |
| 30 | Per-match alert override | Calendar-Check-Phone · `alert-override` |
| 31 | Settings — on | Permissions-and-Settings · `settings-on` |
| 32 | Change calendars | Permissions-and-Settings · `change-calendars` |
| 33 | Turn off | Permissions-and-Settings · `turn-off` |
| 34 | Reset local matches | Permissions-and-Settings · `reset` |

Every Dayfold frame renders in light + dark in the gallery. OS-owned frames (§3, §9, §10,
§29 shade) follow platform appearance and carry a dashed `OS-OWNED` badge — they are
storyboard markers, never Dayfold components.

## Product truths enforced in the frames

- **Calendar canonical** — matched summary and alert screens say "Calendar handles event
  alerts"; import confirm says "Calendar remains the schedule"; no create/overwrite/delete/
  RSVP/recurrence/audience action exists anywhere in Dayfold chrome.
- **Device-local & personal** — every compare surface carries "Compared on this phone /
  on this device"; no family-global connected state exists; ignore/reset copy names
  "this phone only". The import field screen states honestly that approved fields become
  synced Dayfold family content.
- **Android "added" honesty** — `return-added` appears only after observation
  (`return-checking` → rescan); `return-unconfirmed` exists precisely because
  `ACTION_INSERT` returns no reliable ID. Opening the editor never claims success.
- **No fuzzy auto-anything** — §18/§19 never pre-select; §20 has no global winner;
  "Leave unresolved" and "Keep separate" are calm peers.
- **Privacy ladder** — new imported Hubs default Only me; the existing-Hub path names its
  audience person-by-person and warns before widening beyond what the source calendar
  implied; only Contributor/Co-owner Hubs are offered (ADR 0030/0053).
- **Calm** — one aggregate Now unit max; no OS notification, badge, streak or overdue state
  for gaps; all-clear is a footer line; offline never claims a fresh all-clear.

## M3 → Compose mapping

| Surface | Compose M3 |
|---|---|
| Aggregate Now unit | `ElevatedCard` + `AssistChip` (provenance) + `FilledTonalButton` (Review) |
| Review rows | `OutlinedCard` + `FilledTonalButton` / `OutlinedButton` / `TextButton` triplet |
| Suggested-match pair | two `Surface(tonal)` + center `Icon` bridge; evidence `ListItem`s |
| Ambiguous candidates | `RadioButton` rows in `OutlinedCard`; disabled `Button` until choice |
| Field-diff chooser | per-field `SegmentedButton`-like chip row (never a global toggle) |
| Chooser / settings lists | `ListItem` in `Surface(surfaceContainer)`, `Checkbox`/`Switch`, masked `supportingContent` |
| Primer / denied / empty | hero `Icon` in tonal container + reason `ListItem`s + `Button` pair (peer weight) |
| Confirm / remove / reset sheets | `ModalBottomSheet`, destructive uses `errorContainer` only on the destructive action |
| Import field list | `Checkbox` `ListItem`s + dashed "stays in calendar" `Surface` (no controls on never-fields) |
| Apply / return status | `Card` + status icon + `FilledTonalButton`/`OutlinedButton`; saving uses two-way P1 vocabulary |
| Prefill sheet | `ModalBottomSheet` + read-only `ListItem`s; incomplete field flagged with tertiary badge |
| OS surfaces | not Compose — `Intent(ACTION_INSERT)` / `EventKitUI` handoff, plus runtime-permission APIs |

## Motion & accessibility

- Container transform (M3 emphasized, ~350ms) aggregate card → review list → detail;
  native-editor handoff is an explicit full-surface transition (no shared element —
  the surface visibly changes owner). `prefers-reduced-motion` drops shimmer + transforms.
- ≥48dp targets on every action; dynamic type tolerated by single-column layouts;
  bottom sheets keep actions above the nav bar and work in landscape (content scrolls).
- State is never color-alone: gap kinds carry text tags; uncertainty uses tertiary +
  wording; calendar colors appear only as small dots beside a text label.
- TalkBack/VoiceOver: review rows announce kind + title + time ("Not on this calendar,
  Maya's dance recital, Saturday 3 PM"); Confirm/Keep-separate are separate focusable
  buttons; ignored items announce "ignored on this phone, undo available".
- No urgency red anywhere in review; `error`/`errorContainer` appears once — the Reset
  destructive confirm.

## Copy inventory (guardrail check)

Used: "Review", "Compared on this phone", "Add to calendar", "Keep calendar-only",
"Use Calendar's", "Calendar handles event alerts", "Only the fields you approve".
Never used: "Sync complete", "Two-way sync", "Mirror", "Fixed automatically",
"Dayfold monitors all calendars", any post-import "never leaves your phone" claim,
any absolute cross-app dedup promise, any unnamed "shared with everyone".

## Ratified design decisions (2026-08-28)

1. The initial bounded comparison horizon is **14 days**.
2. Chooser Back is the explicit exit; enabling requires at least one deliberate
   calendar selection.
3. Description remains representable, opt-in, and off by default.
4. Per-match alert override remains in the matched-summary flow.
5. Ambiguous-match confirmation stays disabled until the member chooses an event.
6. The native editor remains available without reconciliation where the platform
   supports it; read-access copy and behavior follow each supported OS version.

## Verdict

The operator approved the gallery and its product decisions on 2026-08-28. The
gallery is an implementation reference; runtime verification and public-store
privacy declarations remain separate release checks.
