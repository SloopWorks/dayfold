# Hub timeline — design notes

A **timeline** gives a hub an **axis of time** — a flat list of blocks becomes
legible across a day or across months. Shipped as a new **authored, content-blind
hub property** (`Hub.timeline`, ADR 0045): the author writes the stops; the client
lays them out in time on-device. Extends the Hubs detail anatomy and reuses the
Now deep-link container-transform. Phase 1 = authored + render-only; a
derived-from-blocks fallback is Phase 2.

Open **`Index.dc.html`** for the full brief; **`specs/hub-timeline-design.md`** +
**ADR 0045** for the spec.

## Files
| File | What |
|---|---|
| `Index.dc.html` | Concept brief — two scales, the density ladder, the card→detail rule, calls made, Compose map. Embeds the real cards. |
| `Timeline-Card.dc.html` | The in-dossier card (content-only). Props: `mode`, `scale` (`day` \| `hub`). Day = vertical windowed rail; Hub = horizontal month spine + next callout. |
| `Timeline-Detail.dc.html` | The full timeline (content-only, fills its surface). Props: `mode`, `scale`, `onBack`. Live **scope toggle** zooms day ↔ hub; grouped sections, NOW line, rich entries with assignees + link/call/file chips. |
| `Tap-To-Detail.dc.html` | Live prototype. The *Maya starts college* hub with both timelines; tap a card → M3 container transform into the detail. Light/dark. Mounts the two children. |

## The model
- **One timeline, two scales.** `day` (intraday, hours, vertical rail, live NOW) and
  `hub` (lifespan, weeks–months, horizontal spine, month groups). The *layout*
  signals the span — no label needed.
- **One "now" per hub.** A day timeline is live only on its date; otherwise every
  stop is upcoming. The roadmap always bands the current month. The demo hub's
  "today" **is** move-in day, so both scales share one coherent present.
- **Card = window, detail = whole.** The card shows a "N done" cap → NOW line →
  next ~3 rows (T2), attachments as a count. The detail is grouped + complete.

## Density ladder (level of detail)
- **T1 Marker** — rail dot + label. Minor dates.
- **T2 Row** — + title + time. The card default.
- **T3 Rich row** — + assignee avatar + inline chips (Map / Call / file / link). The detail default.
- **T4 Expanded** — bordered card with sub-text + multiple attachments. Only next-up and major milestones; done entries drop a rung.

## Provenance
Authored to the hub via the content API / CLI / Claude skill, then laid out in
time on-device. Quiet **"Added to this hub"** chip. The timeline has **no
notification channel of its own** — a stop notifies only if it independently
surfaces as a Now item under ADR 0044 (Phase 2). A derived-from-blocks fallback
(checklist due dates, milestones, location pickups) is **Phase 2**.

## Compose / a11y mapping
- List + group headers → `LazyColumn` + `stickyHeader`
- Entry / expanded → `ListItem` / `Card`
- Rail dot + connector → `Canvas` / `Box`
- NOW marker → `HorizontalDivider` + `Badge`
- Roadmap spine + progress → `Row` + `LinearProgressIndicator`
- Day/Hub scope toggle → `SingleChoiceSegmentedButtonRow`
- Attachment chip → `AssistChip`; assignee → `Box + Text(label)`
- Card → detail → `SharedTransitionLayout`
- Date / countdown tile → `Card + Text(displaySmall)`
- Reduced motion: NOW pulse and the transform fall back to a cross-fade.
- Talkback: each entry announces title, time, status (done/now/next/upcoming) and attachment count.
