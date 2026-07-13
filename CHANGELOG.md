# Changelog

Product/API/feature changes worth calling out in release notes. Grounded in
`git log`, `adr/decisions-index.md`, and `backlog/now.md`; written for a
reader deciding whether an update matters to them, not a commit-by-commit
diff. Format loosely follows [Keep a Changelog](https://keepachangelog.com/);
dates are when a slice landed on `main`, not necessarily when it shipped to a
device. Pre-1.0 (`0.0.0-M0`) — no version tags yet, so entries are dated.

## 2026-07-12 — SWIP analytics reliability: fixed non-delivery + added durable flush-on-background

### Fixed (internal)
- **Dogfood analytics (2026-07-11 entry below) were never actually reaching PostHog** — three
  stacked bugs, surfaced by the new SWIP inspector panel: a missing PostHog project key on
  non-Infisical local builds, a missing analytics-consent grant (collection-mode and per-scope
  consent are separate switches in the SWIP SDK), and two SWIP SDK bugs — a silently-swallowed
  error from a removed kotlinx-datetime API, and a malformed field that PostHog rejected every
  batch on. All fixed (SWIP core bumped to 0.1.6, then 0.1.8); the inspector now shows a clean
  send-ok path with zero drops.

### Added (internal)
- **Backgrounding the app no longer strands queued analytics events.** Previously events only
  flushed on a 30-second timer or once 30 queued, so backgrounding or a process kill lost
  anything pending. Now flushes once on background and persists the queue to on-device SQLite so
  it survives a process kill and resumes on next launch.

## 2026-07-12 — SWIP debug-drawer inspector panel (debug-only)

### Added (internal)
- **A new "SWIP" panel in the on-device debug drawer** shows a live,
  newest-first timeline of everything the analytics engine enqueues, sends,
  batches, or drops — no more PostHog-dashboard round-trip to see what an
  event carried. Debug builds only; the public release build carries zero
  extra bytes for it (ADR 0057). Every value and identifier is masked
  (`••••`) by default with tap-to-reveal, and revealing a value blanks the
  Android screenshot and dogfood bug-report capture (`FLAG_SECURE`) so
  raw data can't leak through either path. Memory-only — the panel never
  transmits anything itself.

## 2026-07-12 — Leveled, scrubbed on-device logging (debug builds)

### Added (internal)
- A leveled `Log` front-door (debug/info/warn/error) in `:client`, bound to
  the SWIP `SloopLogging` runtime in debug builds only (console + in-app
  devtools drawer); release builds are unaffected — no swip bytes, `println`
  fallback floored at WARN+. The PII scrubber runs ahead of every writer; the
  bug-reporter breadcrumb ring (ADR 0054) is now scrubbed the same way, closing
  a leak where it previously captured raw, pre-scrub text. Seeded modest
  lifecycle logging (sign-in, sync, hub, and Now-surfacing milestones). (ADR 0056)

## 2026-07-11 — Product analytics in dogfood builds (PostHog, debug-only)

### Added (internal)
- **Debug/dogfood Android builds now send basic product analytics to PostHog
  (EU region)** — sign-in, family created, invite redeemed/rejected, hub
  opened, card opened, sync failed, plus app foreground/background and screen
  views. The public release build ships **zero analytics code or keys** (ADR
  0055). Events are **count-only**: no names, no ids, no free-text, no
  location (server-side geoip is disabled), and no account identity is ever
  attached to the analytics id. Scoped to the operator's own dogfooded
  household for now; sending analytics from real users' builds needs its own
  future decision plus a privacy-policy update.

## 2026-07-11 — Scoped CLI/device tokens (per-hub grants)

### Added (API)
- **Device/CLI tokens can now be scoped to specific hubs** instead of only a blanket
  read/write-everything grant — the approval screen lets you pick which hubs a linking device
  gets access to. Existing blanket grants are unaffected. (ADR 0029)

## 2026-07-10 — Account avatars, hub People & per-hub roles

### Added (client)
- **Profile avatars** (a bundled set, replacing the plain monogram) and **hub People
  management** — hub owners can now add people to a hub, set it Family/Restricted, and assign a
  role (**Viewer**, **Contributor**, **Co-owner**) instead of the prior read-only allow-list.
  Contributors get write access; Co-owners can also manage who's in the hub. The hub's author is
  always an implicit, non-removable Co-owner. Existing hubs are unaffected — everyone already on
  the allow-list becomes a Viewer by default. Family owners are still **not** auto-added to a hub
  they didn't create (unchanged from the earlier visibility model). (ADR 0053)
- **You can now edit your display name** from the Account screen.

### Fixed (client)
- The top-bar account avatar now updates immediately when you change your profile avatar,
  instead of requiring a restart.

## 2026-07-09 — Timeline detail no longer draws under the status bar

### Fixed (client)
- **The full-screen timeline ("roadmap" / "All milestones") view now sits below the status bar**
  — its back arrow and title were drawing up into the clock. Root cause: the app is edge-to-edge
  and this screen is a bare full-screen substate hosted inside the Hubs tab branch (which,
  unlike the shared `SafeArea` wrapper other routes use, is intentionally edge-to-edge — each
  surface owns its inset). It was the one such surface that applied neither a `Scaffold` nor an
  explicit inset. Made `TimelineDetail` own its insets like the Feed card detail does
  (`statusBarsPadding` on the header, navigation-bar padding on the list).

## 2026-07-09 — Cold start no longer waits on the network to render (ADR 0052)

### Changed (client)
- **Reopening Dayfold after the OS reclaimed it (cold start) now paints your dashboard from
  the on-device cache immediately, instead of showing the logo + spinner while it waits on a
  network `whoami`.** The top-level route gate used to clear only after a network round-trip
  because your family list lived only in memory; it's now cached in the local database and read
  at launch, so the splash resolves from disk (milliseconds, no network). The session is still
  re-confirmed in the background: if it was revoked you're signed out and the cache cleared; if
  you're just offline you keep the cached dashboard instead of hitting a "Couldn't reach Dayfold"
  error. First launch after install (nothing cached yet) is unchanged. Content data flow was
  already offline-first (ADR 0020) — this extends it to the auth/route gate.

## 2026-07-09 — Feed AND Hubs lists keep their scroll position across tab switches

### Fixed (client)
- **Switching to Hubs and back to Now (and Now→Account→Now) now returns you to the same spot
  in the feed**, not the top — extending the earlier feed↔detail scroll fix. The feed's
  `LazyListState` was owned by `ContentHost`, which the tab-level `AnimatedContent` (no
  `SaveableStateHolder`) discards when it swaps to Hubs. Hoisted the state up to `FeedApp` (always
  composed), so it survives every navigation that recomposes the feed away.
- **The Hubs list now keeps its scroll position too** (same fix): its `LazyListState`, owned by
  `HubListScreen`, is hoisted to `FeedApp`, so a Hubs↔Now tab switch, a Hubs→Account excursion,
  or opening/closing a hub detail returns to the same spot instead of the top.

## 2026-07-09 — Card→detail morph now connects the shared header + Open button

### Changed (client)
- **Tapping a Now card now morphs the elements it shares with the detail** — the accent tile,
  kicker chip, title, and the primary **Open** button visibly *travel* into their detail
  positions (on top of the existing card→detail container transform), instead of cross-fading.
  The Open button is now the **same teal** in the card and the detail, so it reads as one button
  that moved. This is **content-gated**: an element only travels when it's identical in both
  places — so a type without a single shared primary action (invitation, contact), or a
  contact's name-initials tile vs the detail's type glyph, gracefully cross-fades as before,
  and future content types get the shared-element treatment automatically for whatever matches.

## 2026-07-08 — Now feed keeps its scroll position when you come back from a detail

### Fixed (client)
- **Opening a card detail and pressing back now returns you to the same spot in the Now feed**,
  instead of jumping to the top. The feed↔detail `AnimatedContent` has no `SaveableStateHolder`,
  so the feed's list-scroll state was discarded when the detail opened and recreated fresh on
  back. Fixed by hoisting the feed's `LazyListState` up to `ContentHost` (which stays composed
  while a detail is open), so the scroll position survives the swap.

## 2026-07-08 — Navigation transitions (every screen change now animates)

### Added (client)
- **Every navigation now animates with a consistent, meaningful motion**, instead of instant
  cuts. Switching Now↔Hubs slides horizontally over a **persistent bottom bar** (no more
  flicker); opening Account (and other modals) **slides up**; drilling into settings
  (Members / Devices / Proximity / Invite) and hub list→detail **scales forward** (and reverses
  on back); the device-link and sign-in flows slide as a **wizard**; boot/auth screens
  **fade through**; and tapping a card still **morphs** it into its detail (unchanged). Motion is
  **reduced-motion aware** (instant when the OS setting is on). The direction reflects what the
  navigation means — forward vs back vs lateral vs modal — so it aids orientation. Under the hood
  this is a single motion taxonomy applied centrally, so future screens animate correctly by
  default (ADR 0051).

## 2026-07-08 — Card→detail container-transform morph restored (was a flash)

### Fixed (client)
- **Tapping a Now card now morphs smoothly into its detail** (and back), instead of flashing
  straight to the detail with no animation. The container-transform's shared-element bounds
  never rendered: the card→detail `AnimatedContent` was driven by a `SeekableTransitionState`
  (added for predictive-back finger-scrubbing), and driving a shared-element `AnimatedContent`
  from a `SeekableTransitionState` silently drops the `sharedBounds` morph — the detail snaps to
  full size and only a crossfade plays. Confirmed identical on androidx.compose.animation 1.11.2,
  1.11.3, and 1.12.0-alpha03, so it is a Compose-API limitation, not a version bug. Switched both
  the feed↔detail (`ContentHost`) and hub card↔timeline (`HubDetailScreen`) transitions to a plain
  state-driven `AnimatedContent`, which renders the morph correctly. Predictive back keeps its OS
  window "peek" and now plays the reverse morph on release (commit-animated); it no longer scrubs
  the morph 1:1 with the finger (that scrub never actually morphed — it also crossfaded).

## 2026-07-08 — Deep-link arrival scroll lands on the right section (hidden-block fix)

### Fixed (client)
- **Tapping a deep-link into a hub now scrolls to the exact target section/block** — it was
  overshooting past it. The arrival index helper (`focusedBlockItemIndex`) counted **all**
  blocks, but the hub screen hides W5-hidden blocks (`partitionHidden`), so every hidden block
  before the target inflated the computed index and the scroll landed that many items too far
  (observed: 5 hidden blocks before "Contacts" → landed on the 5th contact, header off-screen).
  The helper now mirrors the render exactly: it applies the same hidden-block filter (a section
  whose blocks are all hidden renders no header) and accounts for the leading offline/queue
  banners. A deep-link to a hidden block, or a section with nothing visible, now no-ops instead
  of mis-scrolling.

## 2026-07-08 — Back from a hub opened via a card returns to that card

### Fixed (client)
- **Tapping "PART OF THIS HUB" on a card detail, then pressing back, now returns to that
  card detail** — previously it fell through to the hub list, or (on some devices) exited
  the app entirely. Two coupled defects: the shell back handler was disabled whenever a feed
  detail was in the stack *regardless of route*, so on the deep-linked hub (route=Hubs, the
  card still in `detailStack`) no handler was active → the OS back exited the app; and the
  cross-surface jump recorded no origin, so even when handled it unwound within Hubs. The
  deep-link now marks its origin (`hubFromDetail`); back from such a hub crosses back to Feed
  (`CloseHubToFeed`) so the originating card detail re-renders. The hub-detail back arrow
  behaves the same. Hubs reached normally (bottom nav → list → hub) still back to the list.

## 2026-07-08 — "Part of this hub" shows the hub's name

### Changed (client)
- **The card-detail "PART OF THIS HUB" link now names the hub** (e.g. "Lillian → Butler ·
  Fall 2026", ellipsized to one line) instead of a generic "Open the hub", so you can tell
  where the deep-link goes. The name is the target hub's title, resolved from already-synced
  state; it falls back to "Open the hub" when the hub isn't cached. No schema change.

## 2026-07-08 — Card detail no longer states the same facts twice

### Fixed (client)
- **The card detail screen showed its facts twice** — a label-less hero panel restated the
  labeled DETAILS list below (e.g. an invite's date + place appeared in both). DETAILS is now
  the single labeled source of facts; the hero keeps only its real affordance (the map strip,
  the RSVP status/reply, the Call/Text row) and file/link/email drop the hero entirely
  (title → actions → DETAILS). The few hero-only fields are folded into DETAILS so nothing is
  lost: invite **Host**, geo **Place**, email **Preview**, link **Title**/**About**, file
  **Type**, contact **Company**/**Role**. Less noise, everything labeled and stated once.

## 2026-07-08 — Invite RSVP is now honest (reply-handoff or read-only status)

### Fixed (client)
- **An invite card's Yes/No pills no longer look tappable and do nothing.** They were a
  static reflection of the authored RSVP with no write path (dayfold is read-only +
  content-blind and not the RSVP system of record — ADR 0020/0016), so they read as two
  dead buttons. Replaced with an honest affordance: when the invite carries a reply target
  (`invite.rsvpUrl` — a web RSVP / calendar link / `mailto:` / thread URL), a single
  **"Reply / RSVP"** button hands off to that source system; otherwise a **read-only status
  chip** reflects the state and names its origin ("Not replied yet · from The Garcias",
  "You're going · from your email"). dayfold never writes its own backend — the reply is
  made where the invite lives.

## 2026-07-08 — Card detail survives leaving for a 3rd-party app (Android)

### Fixed (Android)
- **Opening a card's detail, tapping a handoff (Navigate → Maps, Call → dialer, a
  link), then returning now lands back on that detail — not the Now feed.** The
  Android shell rebuilds the redux store fresh in every `onCreate`, and the detail
  nav (`detailStack`) lived only in that in-memory store, so any Activity recreation
  — returning from a memory-heavy 3P app, a screen rotation, process death, or the
  "Don't keep activities" developer setting — reset the app to the feed. The shell now
  persists the detail stack in `onSaveInstanceState` and re-dispatches it
  (`RestoreDetailStack`) after the store is rebuilt; dangling ids self-clean when the
  DB→store bridge repopulates (`CardsLoaded` already filters to present cards). Read on
  the main thread off the immutable `AppState`, so no dispatch race. (Hub-detail /
  account nav across recreation is a separate follow-up.)

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
- **Card detail date/time fields now read as friendly labels instead of raw ISO.** A
  DETAILS row (and the invite hero panel) rendered timestamps verbatim —
  `2026-07-08T09:25:00-07:00`. They now format to the **authored wall-clock** —
  `Jul 8, 9:25 AM` (leave-by / when / RSVP-by / closes / modified / email date); a
  date-only value → `Jun 18`. Shown in the value's own zone (an appointment reads in
  its local time, not the viewer's). Unparseable values pass through unchanged.

## 2026-07-08 — Timeline stop attachment chips wrap instead of clipping

### Fixed (client)
- **A timeline stop with a wide assignee and multiple attachments no longer renders as a
  giant half-empty card with cut-off chips.** On the hub roadmap, a stop's meta area
  (source tag + assignee + attachment chips) was a single non-wrapping row; when it
  overflowed, the trailing chip was squeezed to a sliver whose label wrapped one character
  per line — ballooning the card height and clipping the other chips (seen on the "Aug 1
  deadline cluster" stop: assignee "Lillian + Patrick" + 3 chips). The assignee now sits on
  its own line and the attachment chips flow into a wrapping row (`FlowRow`), so every chip
  stays visible and tappable and the card is only as tall as its content. Layout-only.

## 2026-07-08 — Authored card triggers now drive surfacing + notifications (#299)

### Added (client)
- **A Now-card authored with a `when` and/or `geo` trigger now acts on it.** Previously
  the client never even decoded a `BriefingCard`'s `triggers[]` — an authored time/location
  trigger did nothing. Now: a `when` trigger (with `alert_offset`) bands the card into
  NOW/SOON/LATER by its lead time and arms a local notification at `at + alert_offset`
  (background-notify opt-in required); a `geo` trigger surfaces the card as NOW when the
  device is within radius, matched **on-device** (ADR 0014 — position never leaves). Per
  **ADR 0049 (Option A)**, background geofencing stays user-curated — an authored geo
  trigger fires in the background only via `place_ref` to a saved place; a coord-only
  authored geo trigger surfaces in the foreground only. `not_before`/`expires_at` keep
  their meaning (visibility window). Existing devices self-heal via `CLIENT_SCHEMA_VERSION`
  2→3 (one forced resync backfills the new trigger field).

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
