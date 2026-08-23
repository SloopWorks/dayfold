# Backlog — Next

Queued behind the validation gates (`context/goals-and-constraints.md`).
Populated at bootstrap and by loop close-outs. **Kept short on purpose**
(start-of-session reading cost) — completed/superseded build narrative lives
in [`backlog/next-history.md`](next-history.md) (split 2026-07-14, same
convention as `now.md`/`now-history.md`); this file keeps only what's
actually still queued or genuinely blocked.

> **Tracking convention:** build/work items = `TASK-<slug>` here (`next.md`),
> promoted to `now.md` when active, `later.md` when deferred. Operator decisions
> = `INB-N` in `operator-inbox.md`. High-level phases = `planning/workstreams.md`.
> No issue tracker yet (workstream D2 deferred).

## TASK-SMART-BRIEFINGS-V0.1 — Claude Bridge operator pilot

**Added 2026-08-20 (operator-requested design/implementation handoff). Status:
proposed and blocked on INB-39, Proposed ADR 0071/0062, the live-flow ADR 0008
design sign-off, and a synthetic real-Claude OAuth/MCP compatibility spike.** This
is the recommended first provider proof: the user's Claude account performs
inference and owns Google OAuth; an isolated Dayfold Vercel service exposes a
public, separately authenticated remote MCP that reads one approved Hub and stages
at most one owner-private card proposal. Only a human Dayfold app token may insert
it with an exact restricted audience.

This operator pilot is Claude-only and manually started. It is not the commercial
hosted V0.1 and explicitly excludes pricing/billing/signup, scheduling,
attachments/link crawling, Dayfold Gmail OAuth, K3/K4/E2EE claims, ChatGPT/BYOK,
updates/deletes, source writes, auto-publish, and release telemetry. Gmail outcomes
are reported by Claude rather than independently verified. All work stays
synthetic until an eligible commercial no-training posture or explicit
constitutional amendment; a consumer model-improvement toggle is not sufficient.
Non-operator use also remains blocked on counsel/Terms/export/delete gates.

Claude-ready entry point:
`specs/smart-briefings-v0.1/CLAUDE-HANDOFF.md`; system design:
`specs/smart-briefings-v0.1/system-design.md`; implementation plan:
`docs/superpowers/plans/2026-08-20-smart-briefings-v0.1-claude-bridge.md`; live
hi-fi prompt: `designs/PROMPT-smart-briefings-v0.1-claude-bridge.md`.

## TASK-ROUTINE-INTEGRATION — scheduled connected-source curation through a K1/K3 gateway

**Added 2026-08-07 (operator-requested planning). Status: live provider/gateway
work remains blocked on Proposed ADRs 0061/0062 + operator decisions
(INB-34/INB-35); the explicitly simulated local safe slice is implemented.** Build a
provider-neutral routine flow: trigger → current Dayfold state + connected
email/docs/calendar deltas → `dayfold-curator` analysis → structured changeset →
shadow/staged/bounded update. Reuse the Kotlin CLI, resource-scoped grants,
visibility, and provenance.

Privacy default: long-lived Dayfold credentials and `FCK` stay on the operator Mac
(K1), then an always-on Dayfold-controlled gateway (K3). Claude/OpenAI sessions may
receive the minimum selected plaintext through narrow tools but do not receive the
durable family key. Direct cloud-held decryption keys are reserved K4 and remain
off unless explicitly accepted/disclosed. No Dayfold-owned Gmail OAuth, delete,
audience widening, roles/invites, outbound messages, or W3/member intents in the
first tier.

Sequence: contract fixtures → K1 manual/scheduled shadow → routine principal + K3
gateway → staged transactional apply → bounded auto-upserts → one provider adapter
→ optional separately-gated K4 experiment. Design:
`docs/superpowers/specs/2026-08-07-routine-integration-design.md`; plan:
`docs/superpowers/plans/2026-08-07-routine-integration.md`; Claude Design prompt:
`designs/DESIGN-BRIEF-smart-briefings-subscription-routines.md` (provider-owned
subscription UX; run + operator sign-off required before deeper planning/build).
Recovery/observability uses durable content-free run records, closed error/action
enums, idempotent retries/finish/apply/revoke, message-stripped gateway SWIP/Sentry,
and dogfood-only anonymous analytics per Proposed ADR 0062. No source content,
provider error text, or stable family/resource IDs enter diagnostics.

The reviewed executable slice is
`docs/superpowers/plans/2026-08-07-routine-integration-safe-slice.md`: strict
contracts + sanitized fixtures, GET-only CLI validate/diff, and a dedicated
fake-backend Smart Briefings preview with no provider task, OAuth grant, routine
principal, remote run, content write, or new telemetry. The operator's instruction
to import/review/implement is ADR 0008 sign-off for that simulated preview only.

## TASK-CALENDAR-RECONCILIATION — client-owned Calendar Check + native handoff

**Added 2026-08-08 (operator-requested feature direction). Status: blocked on
Proposed ADR 0063 acceptance + ADR 0008 hi-fi mockups/operator sign-off; no build
authorized.** Compare structured Dayfold event candidates with calendars selected
on the member's Android/iOS device. Raw calendar observations, identifiers,
bindings, permission state, and ignore decisions remain device-local. Dayfold-only
gaps open the OS-native prefilled editor so the user chooses the destination
calendar and confirms. Calendar-only gaps produce a reviewed, normalized proposal
for an existing/new Hub; new imports default restricted to the importing member.

The Now surface gets at most one aggregate in-app Calendar Check unit and no gap
notifications. For a confirmed or unique strict-fingerprint match, Calendar owns
the generic start-time alert by default; Dayfold may still surface a distinct
preparation/action nudge through the existing ADR 0043/0044 engine. First slice:
mobile native stores,
non-recurring/single-occurrence matching, conservative deterministic fingerprints,
no prose parsing, no attendees/invitations, no automatic writes, and no
Dayfold-owned Google Calendar OAuth. ADR:
`adr/0063-client-owned-calendar-reconciliation.md`; design prompts:
`designs/DESIGN-BRIEF-calendar-reconciliation.md`.

## TASK-SWIP-BUGREPORT-FOLLOWUPS — dayfold ↔ swip integration improvements

**Added 2026-07-11 (operator, after the first on-device smoke).** The swip bug
reporter is wired into debug builds (ADR 0054, PRs #320 + #321, swip 0.1.1):
shake/edge-tab → capture → annotate → review → on-device lane, with the redux
timeline recorder, the allowlist slice registry + sanitizer, and the mandatory
leak test. Release carries zero swip bytes. These are the gaps found by actually
using it — none block the dogfood loop.

**1. Scrubber replays state, not pixels (the operator-visible one).**
The C10 time-travel viewport renders a **slice inspector** (`route`, `syncing`,
`cardsCount`, `detailStack`, `hubFilter` as text), not the real Compose UI. Two
stacked reasons, and the second is a hard limit:
- Dayfold supplies the `scrubberContent` lambda; today it renders `ReplayedState`
  (`androidApp/src/debug/.../BugReporterGlue.kt`). Swip renders whatever the host
  passes — its own demo scene passes a real screen — so this part is a choice.
- **The journal cannot rebuild the screen.** The registry deliberately never
  records card/hub content (privacy floor, ADR 0054 / swip docs 12 §6), so a
  replayed `AppState` has `cards = []`. Rendering `FeedApp` over it would paint an
  empty feed at every seek — a *lie* about what the user saw. `cardsCount` exists
  precisely so a report can say "there were 14 cards" without shipping the 14.

  **Options (operator picked: punt):**
  - **A. Inspector** — today. Honest, cheap, no fidelity.
  - **B. Route-level UI replay (~30 lines).** Render the real screen for each
    `route`/`detailStack`/`hubFilter` at each seek with empty content. `:ui`
    already has pure state-driven screens (the snapshot suite renders
    `HubListScreen(AppState(hubs = …))` etc.), so the pure-presentation rule swip
    requires is satisfiable. Honest but hollow.
  - **C. Full-fidelity replay.** Requires registering `cards` in the slice
    registry → card content lands in every bug report → **blows the privacy floor
    and the leak test; needs a superseding ADR.** *Operator direction: prove C on
    a throwaway host first — the **redux-kotlin task-flow sample app** is the right
    testing ground (no real user data, so the privacy floor is a non-issue there).
    Do NOT prototype C against dayfold state.*
  - **D. Screenshot-anchored** — already shipped: the SCREENSHOT part *is* the
    rendered UI; the scrubber is the state track beside it.

**2. Inset fix is unverified on-device.** swip 0.1.1's
`windowInsetsPadding(WindowInsets.safeDrawing)` fix (CANCEL/DONE were under the
status bar, untappable) is verified only by compile + desktop goldens —
`safeDrawing` is **zero on desktop**, so the goldens prove nothing about the phone.
Confirm on the Pixel; if a surface is still clipped, the remaining offenders are
the sheets' own edges, not the overlay layer.

**3. No upload path.** Reports sit in the on-device lane
(`noBackupFilesDir/swip-reports`, 3 pending / 15 MB / 7-day TTL) — the swip
gateway is its Phase 1. Until then, pull them with
`adb shell run-as com.sloopworks.dayfold ls no_backup/swip-reports`. Revisit when
swip ships the gateway (ADR 0054 Revisit Trigger).

**4. Anonymous identity + no dogfood channel.** `identity = (null, null)`,
`channel = "debug"`, `internalChannel = { true }` — there's no swip identity stack
in dayfold and no non-debug internal build. A real dogfood channel (quick-fire
surface for internal-but-release builds) needs channel wiring; also gated on the
ADR 0054 Revisit Trigger.

**5. iOS is unwired.** `iosApp` consumes the `:ui` framework; swip's iOS bug
reporter (`IosShake`/`IosScreenshot`/`IosReportDir` exist) lands with dayfold's iOS
reporter pass.

**6. `:swip-wiring` needs GH Packages creds to build.** Any `./gradlew` run that
touches it needs `gpr.user`/`gpr.token` in `~/.gradle/gradle.properties` (or
`SLOOPWORKS_PACKAGES_TOKEN` env). CI has the secret. Documented in
`processes/agent-dev-loop.md`.

**7. Shared drawer migration + component-aware reports (reviewed 2026-08-10).**
Dayfold's host wiring already uses the reusable `DebugDrawerHost`/plugin contract.
PR #379 now pins `SloopWorks/debugdrawer` commit `92fec3b` as a private submodule,
substitutes its four Gradle projects behind the stable 0.1.0 coordinates, and
removes the embedded `apps/debugdrawer*` modules. This follows the operator's
repository-sharing fallback after the first operator-only Maven publish failed
with an empty package secret/HTTP 401. Publication is no longer on Dayfold's
critical path; it remains useful for consumers that prefer binary distribution.
Separately, SWIP already has
`UiTreeProvider`/`UI_TREE` and a Point tool that snaps to supplied bounds, but
Dayfold supplies no tree, so Point currently degrades to a manual rectangle.
Even with a tree, Point currently discards node identity/parents/source and SWIP
has no preseeded frozen-capture handoff from the drawer.

**Operator direction 2026-08-10:** shared drawer/reporting plugins own the UI-tree
capability/status UX, while integrating apps choose per build between `Disabled`,
`BoundsOnly`, and `SourceOnDemand`. Keep a tooling-free `debugdrawer-components`
artifact for the panel/help UI and an optional Android
`debugdrawer-compose-inspector` provider as the only artifact that pulls Compose
tooling data. In developer builds where the plugin is present but tooling is not,
show the reason, a short enablement recipe/Copy setup snippet, and manual report
annotation fallback. Public release registers neither surface.

**Sequence:** verify and merge Dayfold PR #379 against the pinned shared source →
run/sign off the Components panel + live picker design brief (ADR 0008) → spike
source activation, semantics feasibility, and a clean PixelCopy/tree pair → add
typed selection + preseeded capture handoff to SWIP → implement the tooling-free
Components plugin + optional Android Compose provider in the shared repo → wire
its privacy-filtered frozen capture into Dayfold developer builds with an explicit
mode. Source attribution requires
`SourceOnDemand` + completed recomposition and remains optional. Public release
has no viewer; a future explicitly approved,
never-published `internalMinified` variant with the inspector enabled may test
group-key/R8 mapping. Never retain Compose source strings with release keep rules.
Full review/spec:
`docs/superpowers/specs/2026-08-10-debugdrawer-component-reporting-review.md`;
design brief: `designs/DESIGN-BRIEF-debugdrawer-component-reporting.md`;
Claude Design prompt: `designs/PROMPT-debugdrawer-component-reporting-hifi.md`.

## TASK-INVITE-APPROVAL-IDENTITY — show who's actually joining (name/email/time/provenance)

**Added 2026-07-07 (operator).** When a new user redeems an invite they land in the
owner's approval queue showing only **`displayName ?: "Someone"`** + role — no email,
no join time, no invite provenance. This is a **security gap, not just cosmetic**:
spec `05-invite.md` §65–73 makes approval **identity-bound** ("decline is the low-
friction default; approve requires the identity to match") — which only works if the
owner can actually see who's joining. Rubber-stamping "Someone" is the device-grant
phishing class the owner-approve model exists to prevent (§68).

**Current state (verified 2026-07-07, not re-verified since):**
- API `GET /families/{fid}/invites` `pending[]` already returns `display_name`,
  `provider`, `provider_uid`, `email_verified`, `role`, `invite_id`, `requested_at`
  (`app.ts` LATERAL query) — but **not the email string**.
- Client `PendingMember` (`AuthClient.kt`) carries `displayName/role/provider/
  requestedAt` — **no email, no inviteId**.
- UI renders only `displayName ?: "Someone"` in `MembersScreen.PendingRow` +
  `InviteScreen.InvitePendingRow`; no time, provider, or provenance.

**Scope (spec §69–73):**
1. **API** — add the invitee's **email** to `pending[]` (from `user_identities`,
   gated on `email_verified` — show verified email; else show provider + "email
   unverified"). Keep `invite_id` (already returned) flowing through.
2. **Client** — `PendingMember += email, inviteId`; the reducer/engine already
   thread the queue.
3. **UI** — the pending row shows **name → email → "via Google/Apple" → relative
   join time** ("2 min ago"), and **mint provenance**: "you created this invite N
   min ago" (join `invite_id` → the outstanding invite's `created_at`). Handle a
   null `display_name` gracefully (fall back to **email/provider**, never a bare
   "Someone"). Decline stays the visual default (§72).
4. **Design-first (ADR 0008)** — this changes the approval-row surface → needs a
   hi-fi mock of the richer pending row (extend the `Auth-Phone` invite/members
   views) + operator sign-off before build.

**Open decision → `operator-inbox.md` (guardrail #3/#4, customer-data):** the operator
asked for **location** too. Showing a *joiner's* IP/approx-location to the inviter is a
PII-handling call distinct from name/email. **Recommend: ship name/email/time/provenance
first; treat location/IP as a separate gated INB.**

Relates: ADR 0011 §Invites, spec `05-invite.md` §65–73, the shipped invite-mint UI
(`feat/owner-invite-mint-ui`) + deep-link (ADR 0048).

## TASK-HEADLESS-RENDER-DAEMON — persistent headless Compose render engine (PNG + layout tree, code-reload) (NEXT — spike)

**Status: NEXT (queued 2026-07-02, from the CL-SNAP session).** The agent-optimal
render engine: a warm long-lived JVM exposing `render(scene | stateJson) →
{png, semanticsTree, bounds}`, that also absorbs *code* edits without a JVM
restart or full Gradle build. Collapses three follow-ups into one engine:
persistent render + layout-info (the inspector) + code-reload. Ideal backend
for **AI design-matching** (render → diff vs mockup → region → owning composable
→ edit → re-render, ~1s, mostly text).

**Why this shape (measured):** CL-SNAP's inner loop is ~5s recompile + ~2s
fork/render; batch-in-one-process already gets **~150ms/shot** warm. Two axes:
- **State iteration (same code, new state)** — EASY, no hot-reload needed. Extend
  CL-SNAP's batch to a daemon (stdin/socket loop) holding a warm JVM.
- **Code iteration (edit a composable)** — the frontier. `ImageComposeScene`
  renders a fresh composition per shot, so there's no long-lived composition to
  invalidate — hot-reload's recompose-in-place hooks don't apply. Two routes to
  get recompiled classes into the live process: **Route B — classloader swap**
  (lower risk, evaluate first: watching compile → render through a fresh child
  `URLClassLoader`); **Route A — JBR hotswap** (faster, riskier fallback: reuse
  `org.jetbrains.compose.hot-reload`'s enhanced class-redefinition; needs the JBR
  + a tight CMP/Kotlin version match).

**Spike steps:** (1) persistent render daemon — batch → long-lived process,
`render(scene|stateJson) → {png, layout}`; confirm ~150ms/shot warm. (2) add
layout-info output (bounds + semantics tree as text per render). (3) code-reload
experiment, Route B first; fall back to Route A if Compose global state resists
a classloader swap. (4) measure edit→re-render latency vs the ~7s baseline.

**Relation to siblings:** the `:model`/`:data` further split (below) shrinks the
~5s recompile for every consumer (also feeds this daemon's Route B); this
daemon is the agent/CI-facing engine (headless, text-first).

**DoD:** a warm daemon rendering `{png, layout-tree}` sub-second per state; a
recorded verdict on Route B; the measured edit→render+layout latency vs ~7s.
**Reference:** CL-SNAP session (PR #277); pairs with the `later.md`
pixel↔composable inspector.

## Small queued/blocked items

- **CL-9b — real static-map images** (deferred to M1, ADR-gated). M0 keeps the
  stylized `MapStrip()` placeholder; a real author-time-stamped map image needs
  a new ADR (third-party map-provider disclosure + provider-logging exposure).
  Full spike record: `next-history.md`.
- **CL-10 — adaptive two-pane detail** — **BLOCKED** on a Claude-Design
  expanded-detail pass (design gap; phone-only designed today).
- **`:model`/`:data` further client split** (ADR 0047 §Remaining) — the
  `:ui`/`:client` split shipped (2026-07-02, see `next-history.md`); this
  further split is still queued if the measured payoff isn't enough on its own.
- **TASK-SYNC REMAINING** (core shipped 2026-06-19, see `next-history.md`) —
  ~~**R3 background sync**~~ landed 2026-07-31 (ADR 0020 R3) **on Android**:
  `WorkManager` `PeriodicWorkRequest` calls the shared `backgroundRefreshPass`,
  which drains `/sync` headlessly and reconciles geofences/exact schedules
  (or delegates to the live app runtime if one is already running, to avoid
  racing refresh-token rotation). The iOS `BGTaskScheduler` `BGAppRefreshTask`
  side is also built, scheduled, and cancellation-safe, but it is **not
  functioning yet** — `IosBackgroundNotify.kt`'s `IOS_API_BASE` is a
  compile-time `""`, and so is `MainViewController`'s `api`. With the app
  installed, a wake takes the DELEGATE branch (the controller registers a
  runtime handle on construction) and logs `delegated=true`; the explicit
  `skippedReason = "no-ios-api-base"` skip is only reached when no runtime is
  retained in the process. Either way the result is the same: local reminders
  are reconciled, no network pull happens. iOS R3 stays blocked until the
  **iOS sync-config** plumbing below lands — do not read iOS background sync
  as working. Still open:
  **push** (FCM/APNs/SSE → `syncNow` hook); **iOS sync-config** plumbing
  (api/family/secret, the BuildConfig analogue).
- **hub-visibility-flip child fan-out trigger** (from hub-sync PR2 / migration
  0010) — add with the visibility-toggle authoring slice (no M0 actor flips
  hub visibility; authoring is ADR-0016/0029-deferred).
- **TASK-E2E — end-to-end encryption build** — investigation is DONE
  (`research/e2e-encryption-investigation.md`, full scope + recommendation in
  `next-history.md`); the actual M1 build (likely single-household-key first,
  multi-member key distribution as a harder follow) is still queued, ADR-class.
- **TASK-license-strategy — final license decision** — research is DONE
  (Proposed ADR 0032, `research/2026-06-25-licensing-open-source-strategy.md`);
  still awaiting **operator + `[pending-counsel]`** acceptance (see
  `backlog/operator-inbox.md`).
- **MOBILE RELEASE PIPELINE follow-ons** (pipeline itself shipped, ADR 0034 —
  see `next-history.md`): **TASK-mobile-promote-artifact** (G6, promote the
  exact alpha-tested artifact via Play track-to-track, needs Play set up
  first); **TASK-mobile-r8** (G7, enable R8 minify + resource-shrink with
  vetted keep-rules — redux-kotlin/Firebase/Compose/kotlinx-serialization/
  ktor; currently `isMinifyEnabled=false`); **TASK-mobile-sdk-firstrun** (G9,
  validate the GitHub-runner Android-SDK setup on the first real CI run —
  small, do at first run); **TASK-ios-pipeline** (G8 — the Xcode/Swift host app
  itself already exists and is sim-verified (`apps/iosApp`, since 2026-07-01);
  what's **BLOCKED** is the release *pipeline* (fastlane `match`+`pilot` →
  TestFlight) — needs the operator's Mac + an Apple Developer account, $99/yr
  spend. `processes/mobile-release.md` §iOS.).

## Verified 2026-08-21 (was: "believed-shipped, pending one verification pass")

The three bullets here were archived on strong evidence but never build-verified,
because no sandbox had Gradle registry egress. One now does (setup recipe in
`backlog/now.md`), so they were re-checked against a live build and by reading the
code. **None of the three could simply be deleted — every one had a real gap.**

- **TASK-AUTH-S6-D Phase 2 — scanner ✅, Android App Links ✅, iOS Universal Links ❌.**
  `QrScanner` has all four source sets (commonMain `expect` + android/ios/desktop
  `actual`). Android is complete and correctly wired end to end: an `autoVerify`
  intent-filter for `/device` + `/invite/` on `family-ai-dashboard.vercel.app`, and
  the API serves `/.well-known/assetlinks.json` (`apps/api/src/app.ts:408`) with the
  release/debug fingerprints. **iOS does not work:** `apps/iosApp/project.yml`
  declares no `associated-domains` entitlement, so iOS never fetches the
  association file at all, and the served `apple-app-site-association`
  (`app.ts:420`) still returns the placeholder `TEAMID.com.sloopworks.dayfold`
  (its own comment says "placeholder until the iOS host ships"). Same shape as the
  other known iOS gaps (`IOS_API_BASE` is a compile-time `""`). Needs the Apple
  Developer team ID, so it is operator-gated like the rest of the iOS lane.

- **TASK-AUTH-CONTENT CLI verb parity — part of the original scope never landed.**
  The CLI's command registry (`Help.kt` `COMMANDS`, the single source of truth) is
  exactly: `login logout whoami pull responses changeset push delete template
  update version help`. Against the originally-scoped set:
  - `status` — **never landed**.
  - `push --dry-run` — **landed 2026-08-22.** Runs the whole pipeline (linkify,
    checklist-id stamping, validation, ADR 0064 pre-flight) and prints the target
    URL + the final payload instead of writing. It prints `stamped`, the same value
    the PUT carries, so preview and write cannot drift.
  - `push --diff` — **still open, and it is NOT a verb-sized gap.** It needs the
    server's current state for the id being pushed, and the per-id GET routes do not
    exist uniformly: `GET /families/:fid/hubs/:id` does (`app.ts:612`), cards have
    only the collection (`app.ts:525`, so a client-side find), and **sections and
    blocks have no GET at all** — they are reachable only inside
    `/hubs/:id/tree`, which the caller cannot address without already knowing the
    hub. So `--diff` is either uneven across the four resource types or it needs new
    server routes; either way it is a design decision, not a CLI verb. Left for the
    operator.
  - `status` — **still open, and bigger than it looks.** `specs/prototype/07-cli.md`
    defines it as "local manifest vs server (drift)", where the manifest is
    `~/.config/dayfold/<family>.manifest` mapping `path ↔ id ↔ server version`. That
    manifest does not exist, and neither does the markdown-frontmatter push flow it
    belongs to — today's `push` takes `<id> <file.json>`, not a path. Inventing the
    manifest format and write points is a design decision, so `status` is NOT the
    same class of gap as `archive`/`--dry-run` were.
  - `hub get` / `hub rm` — no `hub` command, but functional equivalents exist
    (`pull --hub <id>`, and `delete <id>` whose default resource is hubs).
  - `hub archive` — **never landed, and its API route is orphaned.** The server
    has `POST /families/:fid/hubs/:id/archive` (`apps/api/src/app.ts:772` →
    `hubs.archiveHub()`, `hubs.ts:190`, sets `status='archived'`) and **nothing in
    the CLI can reach it.** `delete` is not a substitute — it soft-deletes rather
    than archiving, and `archived` is a distinct Hub status in the schema.
    Smallest real gap of the three; the route already exists, so this is a CLI
    verb + help entry, not new server work. **Landed 2026-08-22** as
    `dayfold archive <id>` — a bare verb mirroring `delete <id>`, not a `hub <verb>`
    namespace, because the latter is a shape the CLI never adopted (`pull --hub <id>`,
    `delete <id>` defaulting to hubs). Rename it cheaply if you disagree; nothing
    depends on the token.

- **The archive route is the one hub-id route with no `idErrorResponse` guard —
  ready to apply, blocked only on registry access.** Found while wiring the CLI verb
  above. `app.post("/families/:fid/hubs/:id/archive")` (`apps/api/src/app.ts:772`)
  builds the `hub:${id}` scope key and calls `archiveHub` with an unvalidated id; the
  `app.delete` route immediately below guards first, and its comment says why
  ("validate BEFORE building the `hub:${id}` scope string"). Nine other call sites do
  the same.
  **Not an auth bypass** — `scopeAllows` (`apps/api/src/auth/scope.ts:22`) matches by
  exact string equality and explicitly never `split(':')`, so a ':' in an id cannot
  forge a grant match in either direction (both checked by hand). The real effect is
  narrower: an unbounded, unvalidated id reaches the scope key and the DB, and a
  malformed id answers 403/404 where every sibling answers 422. It went unnoticed
  because nothing could call the route until the CLI verb landed.
  Fix is one line, identical to the sibling's, after the `fid`/`id` destructure:
  `{ const e = idErrorResponse(c, id); if (e) return e; }`
  **Why it is not in the CLI PR:** `apps/api/api/index.js` is a committed esbuild
  bundle that CI regenerates and diffs byte-for-byte, and the bundle inlines the
  private `@sloopworks/*` TypeScript sources. Regenerating it needs a `read:packages`
  token (`npm ci` here 401s on `@sloopworks/swip-sentry`), so an `app.ts` edit made
  without one lands the PR red on the "api bundle is up to date" step. It was written,
  tested for shape, then deliberately reverted rather than pushed knowingly-red.
  With `NODE_AUTH_TOKEN` set this is minutes: apply the line, add the test below, run
  `npm run build:fn` in `apps/api`, commit the regenerated bundle.
  The test written alongside it, for `apps/api/test/hub-api.test.ts`:
  ```ts
  it("archive rejects a malformed id with 422, like every other hub-id route", async () => {
    const o = await ownerOf("hub-o4b");
    for (const id of ["a:write", "x".repeat(129), "a b"]) {
      const r = await app.request(`/families/${o.familyId}/hubs/${encodeURIComponent(id)}/archive`,
        { method: "POST", headers: authH(o.token) });
      expect(r.status, `archive should 422 on id ${JSON.stringify(id)}`).toBe(422);
    }
    const missing = await app.request(`/families/${o.familyId}/hubs/does-not-exist/archive`,
      { method: "POST", headers: authH(o.token) });
    expect(missing.status).toBe(404);   // a valid-but-absent hub is unchanged
  });
  ```

- **A network failure on `push` crashes with a raw Java stack trace.** Observed while
  proving `--dry-run` writes nothing: pointing `DAYFOLD_API` at a dead port makes a real
  `push` die with an unhandled `java.net.ConnectException` and a JDK stack trace, exit 1
  — no message about which host was unreachable, and nothing actionable. `httpStatus`
  (`Main.kt:28`) lets the exception escape rather than mapping it to the CLI's usual
  `System.err.println(...) + exitProcess` shape, so this affects every networked verb,
  not just push. Cheap fix, but it is user-visible error-message design (what to say,
  which exit code, whether to hint at `DAYFOLD_API`), so it is worth deciding rather
  than picking silently. Not urgent — offline authoring is not an MVP flow.

- **TASK-KMP — DoD is NOT met by a live build. `./gradlew :client:build` fails,
  for three independent reasons.** CI never catches any of them because CI runs
  `:client:desktopTest`, never `:client:build`.
  1. **`kotlin("test")` is missing from `commonTest` and `androidUnitTest`.** It is
     declared only on `desktopTest` / `iosArm64Test` / `iosSimulatorArm64Test`
     (`apps/client/build.gradle.kts`), so `commonTest` sources compile for desktop
     and iOS but fail `compileDebugUnitTestKotlinAndroid` with `Unresolved
     reference 'test'`. Fix is one line — `commonTest.dependencies {
     implementation(kotlin("test")) }`, which also makes the three per-platform
     declarations redundant. **Deliberately not applied**: on its own it only moves
     the failure from compile-time to (2), trading one clear error for five
     confusing ones. Fix 1+2 together or neither.
  2. **`redux-kotlin` ships Java 21 bytecode against a JDK 17 toolchain.**
     Once (1) is fixed the Android unit tests run and 5 of 37 fail:
     `UnsupportedClassVersionError: org/reduxkotlin/TypedStore has been compiled by
     a more recent version of the Java Runtime (class file version 65.0), this
     version of the Java Runtime only recognizes class file versions up to 61.0`
     (65 = Java 21, 61 = Java 17; every module pins `jvmToolchain(17)`). The other
     four failures are `NoClassDefFoundError: Could not initialize class ReducerKt`
     cascading from the same load failure. Reproducible, not sandbox-specific — CI
     also uses Temurin 17. **Operator-relevant: redux-kotlin is the operator's own
     library** (see INB-15), so this is upstream feedback, not a Dayfold fix.
  3. `verifyCommonMainContentDbMigration` failed — **fixed 2026-08-22**, see the
     next entry. That one is resolved; (1) and (2) above remain.

## ✅ SQLDelight migration chain — FIXED 2026-08-22 (was: does not reproduce the fresh schema)

Found by running `verifyMigrations` (enabled in `apps/client/build.gradle.kts` but
wired to `:client:build`, which CI never invokes — so it had never once run).

**Two instances of one root cause: schema objects reaching `Content.sq` without a
companion `.sqm`.**

1. **Missing objects.** `membership`, `calendar_import` and
   `card.target_{hub,section,block}_id` were in the fresh schema and in no migration.
   Because no `.sqm` was added, `Schema.version` never moved off 16 — so on an
   existing device `migrate()` **never ran at all**, and the device had no path to
   ever acquiring them. Fresh installs were correct. Fixed by **`16.sqm`** (three
   `ALTER`s + both `CREATE TABLE`s), bumping the version to 17.
2. **Ordinal drift.** `card.media` (3.sqm), `hub.media` (3.sqm), `hub.timeline`
   (9.sqm) and `content_response.created_at` (15.sqm) were `ALTER`-appended but
   written mid-table in `Content.sq`. SQLite can only append, so fresh and migrated
   schemas disagreed on column ORDER. All four moved to the end, matching the
   convention `importance`/`triggers` already document.
   **Severity, stated precisely: this one was latent, not live.** Column order would
   only corrupt reads if a query mapped results positionally, and it never did —
   `Content.sq` contains **zero `SELECT *`** and zero column-list-free `INSERT`
   (checked); every query names its columns, so SQLite resolves by name and physical
   order is irrelevant to correctness. It mattered because it kept the guard red, and
   because it is one `SELECT *` away from becoming a live data-mixing bug. Instance
   1 is the one that actually broke upgraded devices.

No `CLIENT_SCHEMA_VERSION` bump: unlike `11.sqm`'s `triggers` (which needed a resync
so the server could backfill a since-added field into cached cards), both tables
refill unaided — AuthEngine re-saves memberships after every auth resolve, and
`calendar_import` is transient local proposal state.

Verified: `verifyCommonMainContentDbMigration` passes; `:client:desktopTest` 1047
tests and `:ui:desktopTest` 649 tests, 0 failures — the regression that mattered,
since reordering columns regenerates every SQLDelight mapper.

**The guard now runs in CI** (added to the Compose job's gradle line, ~9s), which is
what stops this class of bug recurring. The rule it enforces: **a column or table
added to `Content.sq` needs a `.sqm`, and appended columns go at the END.**

## CODE DEDUP FINDINGS (2026-07-01 audit; re-swept 2026-07-05, counts refreshed 2026-07-16,
re-verified 2026-07-17, applied 2026-07-20)

Not urgent (CI is green, nothing broken) — surfaced by repo-wide simplify passes.

**2026-07-20 update — the auth-boilerplate and hub-visibility items below are APPLIED,
not just assessed.** This session had PR+CI access (unlike prior passes, which only had
a sandbox with no npm/Gradle registry egress) — GitHub Actions CI runs `npm test`/`tsc`
for real, so the "needs a build-capable session" blocker that deferred these 12 times is
closed by verifying via the PR's own CI run instead of locally. See PR for the actual
diff; this entry records what shipped and one correction to the prior counts.

- **`apps/api` auth-route boilerplate — APPLIED as `requireCred(c)`.** Reading the
  source directly (not just the queue's prior count) found the "11× byte-identical"
  claim was slightly overstated: only **7 of the 11** sites
  (`app.ts` `/auth/me` GET/PATCH, `/auth/me/export`, `/auth/me/credentials` GET/DELETE,
  `/auth/me` DELETE, `/device/pending`) share the exact bearer→verifyAccess→
  credential-exists-check shape and were folded into `requireCred(c)`. The other 4
  (`/auth/signout` — cid only, no exists-check; `/auth/whoami` — folds the exists-check
  into its own `family_scope` query; `POST /families` and `/invites:redeem` — sub only,
  no cid/exists-check) are genuinely different shapes and were correctly left alone.
- **`apps/api` hub-visibility gate — APPLIED as `resolveVisibleHub(fid, hubId, caller)`.**
  All 7 of the previously-identified safe sites (`GET /hubs/:id`, `GET .../tree`,
  `GET .../audience`, participants PUT/DELETE, visibility PUT, `DELETE
  /families/:fid/blocks/:id`) now use the helper. The hub PUT route (the 8th site) was
  deliberately left untouched, per the prior assessment — it interleaves the same
  fetch+visibility check with default-from-existing logic and reuses the `allow`/
  `permitted` closure for more state afterward.
- **`apps/api` `hubWriteGate` mapping — APPLIED as `hubWriteGateResponse(c, gate,
  missingDetail)`.** Both sites (section PUT, block PUT) now call it; `missingDetail`
  stays a parameter since the two sites' 409 messages differ ("parent hub" vs "parent
  section missing or deleted").
- **`apps/api` `ownerGate` boilerplate (7×) — ASSESSED, NOT further extracted.**
  `ownerGate` already IS the extraction (added before this queue existed); the residual
  `const g = await ownerGate(c, fid); if ("status" in g) return c.body(null, g.status);`
  two-liner is the same idiom `authorizeTenant` uses at every other route in the file
  (also ~2 lines, also not flagged as duplication) — folding it further would need a
  route-wrapper/middleware restructure, a bigger and riskier change for a 2-line save.
  Leaving as-is; remove this bullet if a future pass agrees, or make the case for the
  wrapper if someone still wants it.
- **`apps/api` misplaced test — APPLIED.** `src/generated/content.timeline.test.ts`
  (hand-written, sitting inside the codegen-output `generated/` dir) moved to
  `src/content.timeline.test.ts`; import path updated. Confirmed the codegen script
  (`packages/schema/codegen.mjs`) only writes specific files into `generated/`, never
  clears the directory — so this wasn't at risk of the *current* generator, but was
  still a latent trap for a future `rm -rf generated && regen` cleanup, per the
  original finding.
- **`apps/cli`** — the `postStatus`/`putStatus`/`getStatus`/`deleteStatus` /
  `authedPut` / device-creds-or-legacy-env `Triple` items below (next bullet) — APPLIED.
- **`apps/api`** — credential-minting (`INSERT INTO credentials` + `grantScopes`
  with the same 3 default scopes) is near-duplicated across `/auth/dev-token`,
  `auth/identity.ts:mintCredentialFor`, `auth/device.ts:redeem`. Lower priority —
  the `kind`/columns differ slightly per path.
- **`apps/api`/`packages/linkrules`** — `media-validation.ts` / `MediaValidation.kt`
  two-copy duplication is **intentional** (ADR 0036 Phase 2 plans codegen-from-one-
  source) — leave as-is; if picked up before Phase 2, the lower-risk interim step
  is a CI parity guard, not a shared implementation.
- **`apps/api`** — `src/generated/content.timeline.test.ts` is hand-written inside
  the codegen-output `generated/` dir. Move next to (or merge with)
  `src/content-validation.timeline.test.ts` before someone deletes it as stale
  generated output. Also: the ~46 other API tests all live under `apps/api/test/`
  — these two are the only ones beside their source; normalize the convention.
- **`apps/api`** — `app.ts` is ~1244 lines holding all ~48 routes. Splitting into
  per-resource route modules is still the biggest win but the biggest risk;
  needs a real build to land safely.
- **CLI/skill docs** — moderate (2-4x) duplication of the same explanations
  across `SKILL.md` / `references/cli.md` / `references/content-model.md` /
  `templates/README.md`: hub timeline, block payload field table,
  visual-enrichment/`media`, auto-linkify, and "local validation is a pre-check
  only" are each explained in 2-4 places. Not inconsistent (the copies agree),
  just redundant, and low priority to consolidate. **The one real gap found
  this series is now CLOSED (2026-07-17):** PR #347 replaced the old monolithic
  `USAGE` string with a `Help.kt` registry driving `--help` and a new `--json`
  machine-readable mode, but nothing in the skill docs told an agent that
  `--json` existed — added a "Discovering capabilities" section to
  `references/cli.md` with example invocations and the `HelpModel`/
  `HelpCommand` field shapes. **Still open, low priority:** hub-timeline field
  table (`content-model.md` vs `templates/README.md`, the latter already has a
  pointer + condensed version — arguably intentional since `templates/README.md`
  needs to stand alone for non-Claude CLI users), block payload table
  (`content-model.md`'s is simpler; `templates/README.md`'s adds the ADR-0035
  "also accepted" alias column — consider merging the alias column into
  `content-model.md` and pointing `templates/README.md` there), checklist
  id-stamping (repeated near-verbatim in `cli.md` + `content-model.md` + the
  `templates/README.md` table note).
- **`apps/cli`** (2026-07-15 audit, re-verified 2026-07-17, APPLIED 2026-07-20)
  — `postStatus`/`putStatus`/`getStatus`/`deleteStatus` collapsed into one
  `httpStatus(method, url, token, body?)` + four one-line wrappers (call sites
  unchanged); `authedPut` extracted, mirroring `authedGet`/`authedDelete`, and
  `push`'s inline 401-retry replaced with a call to it; the device-creds-or-
  legacy-env `Triple` resolution in `pull`/`delete` replaced with a shared
  `resolveAuth(creds)` (`push` keeps its own two-branch shape — its else-branch
  also re-resolves a `secret` var the shared helper doesn't need). Verified via
  this PR's own CI run (real Gradle/JVM), not local build — this sandbox still
  has no Gradle registry egress.
- **`apps/api`** — the ad-hoc validation-error-shape footprint is broader than
  every prior count: **2026-07-17 correction — ~70 sites, not ~23.** The
  2026-07-16 count (~23) only tallied the validation/id-error literal shapes;
  a fuller sweep finds **68** `c.json({type: ...}, status)` call sites plus 2
  `c.json({error: ...}, status)` sites, against only **4** real uses of
  `problem()` (the RFC 9457 helper: the `bad-cursor` checks at
  `1180,1185,1192` plus the 413 handler at line 77). An inconsistency, not a
  clean extraction target — many of these 68 sites differ in response
  `Content-Type` semantics from `problem()`'s `application/problem+json`, and
  client code may already depend on today's plain-JSON shape, so a blanket
  swap needs a real build/test run, not a docs-only pass. Worth folding into
  `problem()` when `app.ts` gets its route-split (see the 1275-line entry
  above), not on its own.

## SWIP platform — `SwipAnalytics.track()` swallows Throwable silently (found 2026-07-12)

`track()` wraps its whole body in `catch (_: Throwable) { }` (INVARIANT-13,
"instrumentation never crashes the product") with **no counter and no debug
record**. But it emits `DebugRecord.Enqueued` *before* constructing
`PipelineEvent`. So when construction throws, the event is reported as
**Enqueued** in the debug inspector and then **destroyed** — no drop, no
flush-failure, no log.

This turned a 100%-data-loss bug (the kotlinx-datetime `Instant` skew) into an
invisible one: the inspector actively *lied* (`Enqueued` for every event) while
`queued 0 / fail 0 / drop 0`. Not crashing the product is right; being silent
is not.

**Do (SWIP-side):** on catch, increment a counter surfaced in
`HealthSnapshot` and emit a `DebugRecord` (e.g. `TrackFailed`/`Dropped` with an
`INTERNAL_ERROR` reason) so the failure is visible in the inspector.
