# Changelog

Product/API/feature changes worth calling out in release notes. Grounded in
`git log`, `adr/decisions-index.md`, and `backlog/now.md`; written for a
reader deciding whether an update matters to them, not a commit-by-commit
diff. Format loosely follows [Keep a Changelog](https://keepachangelog.com/);
dates are when a slice landed on `main`, not necessarily when it shipped to a
device. Pre-1.0 (`0.0.0-M0`) — no version tags yet, so entries are dated.

## 2026-07-08 — Typed cards render their body_md (was raw text / dropped)

### Fixed (client)
- **A typed Now-card (geo / invite / contact / file / link / email) with an authored
  `body_md` now renders it properly.** Before, the typed-card layouts showed `body_md`
  verbatim as a plain one-line summary — `**bold**` printed its asterisks, `[label](url)`
  printed as un-tappable literal text, and multi-line bodies ran together. The working
  markdown renderer (`rememberRenderedMarkdown`: bold/italic/lists/tables + vetted tappable
  links) was only wired to the generic untyped `CardItem` fallback, which real (typed) cards
  never reach. Now: the **feed row** shows a calm one-line PLAIN summary (first line of the
  body, markdown stripped), and the **detail screen** renders the full formatted `body_md`
  with tappable links — the detail view previously dropped `body_md` entirely. Untyped
  briefing cards are unchanged. (No ADR — render-layer bugfix.)

## 2026-07-08 — Checklist "done by" shows a member's name, not a user ID

### Fixed (client)
- **A ticked checklist item's byline now reads "✓ Patrick" / "✓ You"** instead of the raw
  authenticated user ID ("✓ usr_9c200a3ccfefa0752f"). The toggler's `doneBy` userId stays
  in the (content-blind) payload; the UI resolves it to a first name **render-side** from the
  family roster — the name never enters content, so the E2EE/content-blind posture is
  unchanged (ADR 0015/0017). The roster is now eager-loaded so names resolve anywhere a
  checklist renders, not just on the Members screen. An unknown/departed member falls back to
  "a family member". `assignee` and email are untouched. Verified on-device (Pixel 4a).

## 2026-07-07 — Owners can invite family members (QR + share link)

### Added (client)
- **"Invite a member" screen.** An owner opens Account → Family → Invite to mint an
  invite as a **QR code** (in-person scan, one-time, ~15-min TTL) or a **shareable
  link** (~72-h, up to 5 uses), with a live expiry countdown and copy-to-clipboard.
  Outstanding invites list with **Revoke**; pending joiners approve/decline inline.
  Backed by the existing owner-approved invite API (ADR 0011); the raw token is
  display-only (never persisted or logged). Recipient still redeems by pasting the
  link into Join — in-app QR scan + deep-link remain deferred (spec §96/§121). New
  cross-platform QR rendering via qrose (KMP; zxing is JVM-only and unusable on iOS).
  Closes the one missing surface in the invite flow (API + specs + hi-fi designs
  already existed). ADR 0008 sign-off for the invite mockups recorded 2026-07-07.

## 2026-07-04 — Debug sample cards no longer leak into the Now feed

### Fixed (client)
- **Fake seed content (`s_briefing`, "Maya's party", "Jake's Rentals", …) is gone from
  the Now tab.** Android debug builds seeded `SampleData.cards` into the same persistent
  DB a real signed-in family syncs into (the seed gate keyed off the empty build-time
  `FAMILY_ID` const, not the interactive `activeFamilyId`), so sample cards rendered
  permanently beside real content — the server never emits those ids, so incremental
  sync (upsert + tombstone, ADR 0040) could never prune them. The standalone seed is
  removed (on-device sample viewing stays available via the `fake://` showcase backend,
  which wipes on entry). Existing devices self-heal on upgrade: `CLIENT_SCHEMA_VERSION`
  bumps 1→2, so `reconcileSchemaVersion` wipes synced content+cursor once and the next
  sync full-rehydrates from the server (outbox / hidden / session preserved). Verified
  on-device: 7 seed cards purged, 9 real cards re-synced, no re-sign-in.

## 2026-07-03 — Hub timeline now persists (ADR 0045 write-path fix)

### Fixed (API)
- **Authored Hub `timeline` is now stored and returned.** A hub pushed with a
  `timeline` ({ tz, stops[] }) previously validated and returned `200`, then was
  silently dropped — pull showed `timeline: false`. ADR 0045 shipped the CLI
  template, server validation, and client rendering, but `upsertHub` had no
  column to write into and no migration ever added one. Migration
  `0016_hub_timeline.sql` adds `hubs.timeline jsonb` (+ object-shape CHECK,
  mirroring `media`), and `upsertHub` now persists it (`EXCLUDED` semantics: a
  re-push without a timeline clears it). Round-trips on PUT / GET / sync.
## 2026-07-03 — Client heals a stale cache on a behavior-affecting model change (#283)

### Fixed (client)
- **Checklists (and any list) written by an older build become interactive after upgrade.**
  A behavior-affecting field added to a synced model (e.g. `ChecklistItem.id`, the ADR 0038
  interactivity/merge key) was never backfilled into rows an older build had already cached: the
  old model dropped the unknown field (`ignoreUnknownKeys`) and advanced the incremental cursor
  past those rows, so they stayed stale forever — rendering read-only lists that couldn't be
  checked. The client now tracks a hand-bumped `CLIENT_SCHEMA_VERSION` in the local DB (stamped by
  the sync write path); on startup, if the cache was written under an older version it forces a
  full resync (wipe synced content + cursor, preserving queued member writes + local hides), so
  the current model re-fetches every row. Verified end-to-end on a real device upgrade.
## 2026-07-03 — Hub contact & location blocks are now actionable

### Added (client)
- **Tap a contact in a hub to Call / Text / Email; tap a location for Directions.**
  Contact and location blocks inside a Hub were display-only — the round Call/Text
  affordances had no tap handler and the block was given no action channel. They now
  hand off to the OS through the same `CardAction` router the Now feed cards use
  (`tel:` / `sms:` / `mailto:` / `geo:`): a contact shows Call + Text when it has a
  phone and Email when it has an address (48dp touch targets), and a location's map
  tile + "Directions" open the maps app (an address/place query, never a raw
  position — ADR 0014). No new infra — `HubBlockCard` now threads the existing
  hub-screen action channel (renamed `onTimelineAction` → `onCardAction`).
## 2026-07-03 — Timeline "This day" now means today

### Fixed (client)
- **The timeline "This day" scale shows today's schedule, not a future day's.** The
  Day scale keyed off the "focal day" = the day with the most intraday-timed stops,
  which only *preferred* today — so when today had no timed stops it fell to the
  busiest upcoming day and rendered that future day's items under a "Today" /
  "Today's schedule" label (e.g. a July view showing an August-14 7 PM webinar).
  `focalDay` is now literally today; the Day scale is offered only when today itself
  has an intraday-timed stop, otherwise the hub shows the roadmap. The "Today" labels
  are now always accurate.

## 2026-07-03 — Deep-links locate to the right section

### Fixed (client)
- **Tapping a deep-link (timeline chip, Now card, or card detail) now scrolls to the
  target section, not the top of the hub.** Every deep-link in practice targets a
  *section* (`sectionId` / `target_section_id`), but the client only ever passed the
  (usually null) *block* id — so `CardAction.OpenHub` opened the hub at the top and the
  section was never located. All three deep-link builders now fall back to the section
  id, and the arrival-scroll resolves a section id to its header row (blk-* / sec-* are
  distinct id namespaces, so one focus id handles either). E.g. a timeline's "Money &
  Billing" chip now jumps to the Money & Billing section.

## 2026-07-02 — Headless snapshot render + committed-golden CI gate (CL-SNAP)

### Added (dev tooling)
- **Headless snapshot render** for the client UI: `./gradlew :ui:snapshotUi
  -PsnapshotArgs="--scene <scene> --preset <preset> --out <file>.png"` renders
  any of 3 scenes (feed / hub-detail / detail) from pre-built `AppState` fixtures
  off-screen in milliseconds — no device, no emulator. Powered by
  `org.reduxkotlin:redux-kotlin-snapshot:1.0.0-alpha04` (Maven Central,
  `desktopTest` scope). Use `--list` to enumerate all scenes and presets (JSON).
- **Semantic node inspection** (`--semantics` flag, Tier-0 loop, zero vision
  tokens): prints the Compose semantic tree (roles, text, descriptions, selected
  state) to stdout. Preferred first-pass for content and refactor changes.
- **Committed-golden regression gate**: `GoldenSnapshotTest` (`:client:desktopTest`,
  CI = ubuntu-latest) verifies 131 committed PNGs (20 scenes — feed, hub, detail,
  auth/onboarding, members/devices, device approval, scan, notification states,
  privacy, places, proximity, permission ladder, loading kit, timelines) in
  `apps/ui/src/desktopTest/resources/snapshots/` at `maxDiffPercent = 4.0`
  (measured cross-OS bold-glyph drift is 2.2–2.9%; snapshot clock pinned so
  the feed header date can't go stale).
  Re-record after intentional visual changes with `-Dsnapshot.record=true`, then
  eyeball the diff before committing. Delivers ADR 0019 "Remaining" #4 and #6
  (replaces the deferred Roborazzi DIY plan).

## Unreleased

### Internal (build)
- `apps/client` split into two KMP modules: **`:client`** (Compose-free core —
  reducers, engines, data, store) and **`:ui`** (Compose layer + iOS framework).
  A logic edit to `:client` now recompiles ~7,348 lines (~2.4s) instead of ~15,570
  (~4.2s); a UI edit to `:ui` is similarly scoped. No behavior change; developer
  and agent inner-loop speed improvement. (ADR 0047)
- **CL-SNAP snapshot loop relocated `:client` → `:ui`** as part of the split: the
  scene registry, 12 goldens, `redux-kotlin-snapshot` dep, and the `snapshotUi`
  Gradle task now live in `:ui` (they render Compose, which is now in `:ui`).
  Invocation is now `./gradlew :ui:snapshotUi …`; goldens are in
  `apps/ui/src/desktopTest/resources/snapshots/`. The dated CL-SNAP entry above
  describes the original `:client` landing on `main`; this is the post-split home.

### Added
- **iOS local notifications (Phase B parity)** — iOS now reaches parity with the
  shipped Android Phase-B notifications (ADR 0044): on-device, local-only background
  proximity + local notifications, **default-OFF / opt-in**. A runnable iOS host
  (`apps/iosApp/`) embeds the shared `:ui` framework; the device glue lives in
  `iosMain` over the **same** decision core as Android (no engine fork). Both lanes
  fire on-device: **time/date** reminders (`UNTimeIntervalNotificationTrigger`) and
  **place/geofence** reminders (`CLLocationManager` region monitoring, honoring the
  iOS 20-region cap via nearest-N), each carrying the honest on-device provenance
  line ("Matched on your device" / "Added by Claude") and deep-linking to the source
  hub on tap. Quiet-hours + daily-cap + permission state stay device-local and
  never sync; the live position never leaves the device. No FCM/APNs, no server
  change. iOS delivers scheduled notifications directly (no fire-time re-run), so
  quiet-hours/cap/dedup are applied when the reminder is scheduled — the one small
  behavioral difference from Android (ADR 0044 Status). Public App-Store
  background-location disclosure remains gated for a future public release.
- **Hub timelines** — a hub can now show an **axis of time**: a live intraday
  **day rail** (with a NOW line) or a multi-month **roadmap**, rendered on-device
  from an authored, content-blind `Hub.timeline` (author the stops; the client
  lays them out — status, scale, grouping, `✓N` collapse, tz-aware AM/PM). Opens
  from a hoisted dossier card into a full detail with a **day↔hub scope toggle**,
  attachment chips (call/nav/link/in-app), assignees, and per-member **Hide for
  me**. Authorable via `dayfold template timeline` + the `dayfold-curator` skill.
  When a hub has **no authored timeline**, one is **derived on-device** from its
  own dated blocks (checklist due-dates, milestones, pickups, the hub's countdown)
  — honestly labelled "From this hub's dates" (render-only, never notifies).
  (ADR 0045, 0046)
- Android now supports **Android 13+** (minSdk lowered 34 → 33) — installs on
  more devices (e.g. Pixel 4a) with no behaviour change.
- Background proximity + local notifications (Android): the Now feed's
  priority engine now also drives **closed-app** notifications — a geofence
  "arrived near a saved place" or a scheduled "starts soon" alert — entirely
  on-device (no push service, live location never leaves the phone, quiet
  hours + a daily cap you control in Settings → Background proximity).
  (ADR 0044)
- Hub link/document blocks are now tappable end-to-end (previously rendered
  as a link but did nothing when tapped).
- `dayfold delete <id> --block` — the CLI can now remove a single stray block
  directly (previously you had to delete and re-push the whole hub tree; the
  server route already supported it).

### Fixed
- Release builds no longer run the Redux DevTools recorder or the action-log
  middleware (both were accidentally on in production, serializing the full
  app state on every dispatch) — debug-only now, as intended. No behavior
  change; reduces main-thread work under dispatch bursts on release builds.

## 2026-06-29 – 2026-06-30 — Now derived surfacing (Phases A + B)

### Added
- A new **"Now"** feed lane: cards can carry `triggers` (a date/time, a
  milestone, a checklist due date, or a saved place) and the on-device engine
  decides when they're relevant right now — countdowns, "starts soon,"
  arrival-based prompts — ranked alongside authored briefing cards with a
  calm daily budget (no infinite-scroll anxiety). (ADR 0043)
- A read-only **Places** list feeds the geofence-based prompts above.
  (CLI/server-authored only at this stage — no in-app "add a place" yet.)

## 2026-06-28 — Predictive back: gesture-driven card↔detail navigation

### Added (client)
- **The system back gesture now animates the card↔detail transition** (shared-element
  morph, matching the existing tap-to-open animation) instead of a plain screen pop,
  and **always goes up exactly one level** (detail → feed), even from a deep
  RELATED-card chain. Android predictive-back preview (swipe-and-hold) scrubs the
  same transition live.

## 2026-06-27 — Fixed a production outage: every card write had been failing

### Fixed (API)
- **Card `PUT`s had been 500ing in prod since the M0 deploy** — a migration gap
  left `briefing_cards` missing columns the write path already assumed existed.
  Backfilled via `0014_card_columns_repair.sql`; also fixed `codegen.mjs` silently
  emitting `z.any()` for structured block payloads instead of the real `oneOf`
  validation, and rebuilt the Vercel bundle (which had drifted from source and was
  serving stale code — the "bundle is up to date" CI gate exists because of this
  incident).
- `dayfold delete <id> --card` — the CLI can now remove a card directly (the
  `--block` variant landed later, 2026-06-26–06-29 above).

## 2026-06-26 – 2026-06-29 — Two-way: members can act on shared content

### Added
- Signed-in family members can now **check off checklist items**,
  **delete** content they authored, and **hide** items they don't want to
  see — changes sync back to the family, converge across devices, and work
  offline (queued and sent when back online). (ADR 0038–0042)
- `dayfold delete` / `DELETE /families/:fid/{cards,hubs,blocks}/:id` — the
  content API and CLI can now remove content (soft-delete + tombstone), not
  just create/update it.

### Changed
- Stale device caches now get a signaled full resync instead of silently
  missing deletes that happened while they were disconnected too long
  (tombstone-retention floor, `CONTENT_TOMBSTONE_RETENTION_DAYS`).

## 2026-06-26 — First real sign-in live on prod; visual enrichment

### Added
- Real Google sign-in → family creation → CLI device-login → on-device
  render now works end-to-end against the production deploy
  (`family-ai-dashboard.vercel.app`).
- Hubs and cards can carry a hero image, thumbnail, curated icon, and accent
  color (Wikimedia-only image allowlist, validated on both author and
  render). (ADR 0036)

### Fixed
- Sync no longer wedges on an expired session ("Couldn't refresh") — it
  refreshes the token and retries.
- Debug-drawer log capture bridge.

## 2026-06-19 – 2026-06-25 — Auth epic + M0 content types

### Added
- Full account system: Google/Apple sign-in, family creation, owner-approved
  invites, member roster, connected-devices list, profile, data export, and
  account deletion. (ADR 0011, ADR 0021, epic S1–S6)
- CLI device login (`dayfold login`) via RFC 8628 device-authorization grant
  — approve a CLI sign-in from your phone, no copy-pasted secrets.
- Six authored content types ship in the M0 feed: file, link, invite,
  contact, geo, and email cards, plus Event Hubs (multi-block dossiers:
  text, checklist, document, milestone, contact, location, budget).
- `apps/client` became a true Kotlin Multiplatform module — one shared
  codebase renders to Android, desktop, and (compiling, not yet shipped) iOS.
- Offline-first sync: instant cold start from the on-device cache, ~45s
  foreground polling, resume-on-foreground.

## 2026-06-18 – 2026-06-19 — M0 prototype

### Added
- Initial build-out: content API (TypeScript/Hono/Postgres), the `dayfold`
  CLI, and the Compose Multiplatform feed renderer, deployed end-to-end on
  Vercel + Neon. First on-device render on a Pixel.
- Project bootstrap: ADRs 0001–0009 (source-of-truth model, execution model,
  product framing, Event Hubs, prototype scope, design-first gate, design
  system), validation round 1 (`research/validation-round1-2026-06.md`).
