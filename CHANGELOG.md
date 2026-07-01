# Changelog

Product/API/feature changes worth calling out in release notes. Grounded in
`git log`, `adr/decisions-index.md`, and `backlog/now.md`; written for a
reader deciding whether an update matters to them, not a commit-by-commit
diff. Format loosely follows [Keep a Changelog](https://keepachangelog.com/);
dates are when a slice landed on `main`, not necessarily when it shipped to a
device. Pre-1.0 (`0.0.0-M0`) — no version tags yet, so entries are dated.

## Unreleased

### Added
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

## 2026-06-29 – 2026-06-30 — Now derived surfacing (Phases A + B)

### Added
- A new **"Now"** feed lane: cards can carry `triggers` (a date/time, a
  milestone, a checklist due date, or a saved place) and the on-device engine
  decides when they're relevant right now — countdowns, "starts soon,"
  arrival-based prompts — ranked alongside authored briefing cards with a
  calm daily budget (no infinite-scroll anxiety). (ADR 0043)
- A read-only **Places** list feeds the geofence-based prompts above.
  (CLI/server-authored only at this stage — no in-app "add a place" yet.)

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
