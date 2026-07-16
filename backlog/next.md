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
  **R3 background sync**: Android `WorkManager` `PeriodicWorkRequest` + iOS
  `BGTaskScheduler` `BGAppRefreshTask` (both call the shared
  `SyncEngine.syncNow`; iOS needs the Xcode iosApp shell first); **push**
  (FCM/APNs/SSE → `syncNow` hook); **iOS sync-config** plumbing
  (api/family/secret, the BuildConfig analogue). No `WorkManager`/
  `PeriodicWorkRequest` wiring found in the tree as of 2026-07-14 — genuinely
  open.
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
  small, do at first run); **TASK-ios-pipeline** (G8, **BLOCKED** on building
  the Xcode/Swift host app first — needs the operator's Mac + an Apple
  Developer account, $99/yr spend).

## Believed-shipped, pending one verification pass (not build-verified this sandbox — no Gradle registry egress)

Archived to `next-history.md` on strong evidence (git log, CHANGELOG.md, file
existence) but not confirmed by an actual build/test run. Don't treat as done
until someone with toolchain access re-checks; if confirmed, these bullets
should just be deleted (no further narrative needed, full history already
in `next-history.md`).

- **TASK-AUTH-S6-D Phase 2** — in-app QR scanner + App/Universal Links for
  device approval. Phase 1 (approval screens, CLI QR, keychain) has shipped
  code; Phase 2's scanner/deep-link status wasn't independently re-confirmed.
- **TASK-AUTH-CONTENT CLI verb parity** — the API's per-hub scoping (ADR
  0029/0030) is confirmed shipped; the CLI's exact originally-scoped verb set
  (`status`, `push --dry-run/--diff`, `hub get|archive|rm`) wasn't
  byte-for-byte diffed against `Main.kt`'s current commands
  (`login`/`logout`/`whoami`/`pull`/`push`/`delete`/`template`/`update`) —
  confirm none of the original scope silently never landed.
- **TASK-KMP** — `apps/client` already has all 4 KMP source sets + ktor +
  its own `apps/iosApp` module, meeting the task's stated DoD; not
  re-verified against a live `./gradlew build` in this pass.

## CODE DEDUP FINDINGS (2026-07-01 audit; re-swept 2026-07-05, counts refreshed 2026-07-16)

Not urgent (CI is green, nothing broken) — surfaced by repo-wide simplify passes.
**Still open — needs a build-capable toolchain to verify; counts below are as of
2026-07-16, re-verify against current `app.ts` line numbers before extracting:**

- **`apps/api`** — auth-route boilerplate (`bearer(c)` + lazy `verifyAccess` +
  the revoked-credential check) is still repeated **11×** (unchanged count,
  shifted by the file's growth) — `app.ts:211,223,241,259,303,324,340,362,385,
  914,1043` (2026-07-16 spot-check). Extract
  a `requireSession(c): {sub,cid} | Err` helper mirroring `authorizeTenant`. Left
  unapplied this pass too — 11 call sites touching every authenticated route is
  more surface than the mechanical extractions already applied; do it with a
  real build to catch a subtle miss.
- **`apps/api`** — "fetch hub, check visible, else 404" is now **8×**, not
  7× (2026-07-16 correction — the prior count missed one site): the 3 original
  GETs (`/hubs/:id`, `/hubs/:id/tree`, `/hubs/:id/audience`), the hub PUT,
  participants PUT/DELETE, the visibility PUT, **and `DELETE
  /families/:fid/blocks/:id`** (`app.ts:568,581,597,624,643,662,708,863`). A
  `hubs.getVisibleHub(fid, id, caller)` helper in `content/hubs.ts` would cover
  the read-only sites, but several call sites now layer extra
  `canManageHub`/scope checks after the visibility check — an extraction has
  to preserve those, not just the fetch. `app.ts` itself is now **1276 lines**
  / 48+ inline route handlers (was 1244 at 2026-07-15) — the route-splitting
  item is more overdue, not less.
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
  `templates/README.md` / `USAGE`: hub timeline, block payload field table,
  visual-enrichment/`media`, auto-linkify, and "local validation is a pre-check
  only" are each explained in 2-4 places. Not inconsistent (the copies agree),
  just redundant. **Confirmed still accurate + no undocumented commands/flags
  as of 2026-07-14** (repo-maintenance pass audit) — the 2026-07-13 fixes (ADR
  0053 role-403, ADR 0049 place_ref posture, visibility/audience `--type`
  gotcha) are all present and correct. **Still open:** hub-timeline field
  table (`content-model.md` vs `templates/README.md`, the latter already has a
  pointer + condensed version — lower priority, arguably intentional since
  `templates/README.md` needs to stand alone for non-Claude CLI users), block
  payload table (`content-model.md`'s is simpler; `templates/README.md`'s adds
  the ADR-0035 "also accepted" alias column — consider merging the alias
  column into `content-model.md` and pointing `templates/README.md` there),
  checklist id-stamping (repeated near-verbatim in `cli.md` + `content-model.md`
  + the `templates/README.md` table note — low priority, each copy is already
  short).
- **`apps/cli`** (new, 2026-07-15 audit) — `Main.kt:26-64`: `postStatus`/
  `putStatus`/`getStatus`/`deleteStatus` are four near-identical ~8-line
  functions differing only by HTTP method/body; `Main.kt:283`: `push`'s PUT
  call re-implements the same "retry once on 401" pattern `authedGet`/
  `authedDelete` already share (no `authedPut` exists, so the retry logic now
  lives in 3 places) — extracting `authedPut` and merging the four `*Status`
  helpers into one method-parameterized function are both mechanical Kotlin
  changes; `Main.kt:197` vs `225`: the device-creds-or-legacy-env `Triple`
  resolution is copy-pasted between `pull` and `delete` (2 sites, minor). Same
  no-build-toolchain caveat as the `apps/api` items above — unverified,
  needs a real Gradle build to land safely.
- **`apps/api`** — the ad-hoc validation-error-shape count was a significant
  undercount: **~23 sites, not ~9** (2026-07-16 correction). 14 inline literal
  `{type:"validation",issues}` returns (`app.ts:492,495,500,631,668,686,689,
  692,757,767,786,803,807,811`), 7 call sites of the shared `idError()` helper
  (`473,531,674,742,751,780,846`), and 2 call sites of `parseVisibilityAudience`'s
  `va.error` (`487,682`) — all bypass `problem()`, the file's own RFC 9457
  helper, which itself is used only 3× in real routes (the `bad-cursor` checks,
  `1180,1185,1192`). An inconsistency, not a clean extraction target; worth
  folding into `problem()` when `app.ts` gets its route-split (see the
  1276-line entry above), not on its own.

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
