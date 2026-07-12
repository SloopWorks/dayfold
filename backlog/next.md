# Backlog — Next

Queued behind the validation gates (`context/goals-and-constraints.md`).
Populated at bootstrap and by loop close-outs. **Fully-shipped epic narratives
(task-by-task DoD, review notes, spec links) move to
[`backlog/next-history.md`](next-history.md) once closed** — same split
`backlog/now.md`/`now-history.md` uses. First pass 2026-07-12 moved the
closed-out CONTENT LIBRARY epic (CL-0…CL-9, CL-PLAT) and the completed
`:client`/`:ui` module-split narrative (951→~715 lines); read the history file
for the detailed record, not by default. **The AUTH section below (S1–S6) is
large and much of it reads as done-but-unverified against current `main`** —
left as-is this pass rather than reclassified on inference; a build-capable
session should re-audit it against shipped PRs and split it the same way.

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

**Current state (verified 2026-07-07):**
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
PII-handling call distinct from name/email (the device-grant flow shows `origin_ip`/
`origin_kind` for a *device*, but a *person's* location is more sensitive). **Recommend:
ship name/email/time/provenance first; treat location/IP as a separate gated INB**
(what's shown, coarseness, disclosure to the joiner) rather than bundling it.

Relates: ADR 0011 §Invites, spec `05-invite.md` §65–73, the shipped invite-mint UI
(`feat/owner-invite-mint-ui`) + deep-link (ADR 0048).

## TASK-CLIENT-MODULARIZE — split `:client` into `:model` / `:ui` / `:data`

**Status: PARTIALLY SUPERSEDED — see the `TASK-CLIENT-MODULARIZE ✅ DONE 2026-07-02`
entry further down this file.** The `:ui` extraction this section proposed shipped
(ADR 0047) as a 2-module split (`:client` core + `:ui` Compose), not the original
3-module `:model`/`:ui`/`:data` shape. The further `:model`/`:data` split described
below is **still queued** (ADR 0047 §Remaining) if the DONE entry's measured payoff
isn't enough on its own. Kept for that follow-up context; read the DONE entry
first for current state.

**Why (measured, not theorized):** `:client` is one monolithic KMP module —
79 `commonMain` files (UI + state/logic + data/sync + fake backend) + 24
generated SQLDelight files, with sqldelight/ktor/coil on the compile classpath.
In the CL-SNAP session the inner loop measured **~5s recompile + ~2s
fork/render**, and the recompile was **the same ~5s whether editing a UI file
(`FeedScreen`) or an unrelated data file (`SyncClient`)** → the whole module is
one compilation unit. A 1-line body change pays the full ~5s floor (Kotlin
incremental analysis + Compose compiler plugin over the module + test-compile
depends on main). The render itself is already fast (~150ms/shot in-process).

**Goal:** shrink the compile unit a UI edit touches.
- `:model` — `Model`/`AppState`, `Reducer`, `Selectors`, `*Engine`, pure logic
  (kotlinx-serialization + datetime; **no** Compose, sqldelight, or ktor).
- `:ui` — Compose composables (`FeedScreen`, `cards/`, `theme/`, screens) →
  depends on `:model` + Compose only. **The snapshot registry renders from here.**
- `:data` — `SyncClient`, `*Client`, `ContentStore`, `OutboxSender`, SQLDelight,
  ktor.
- `:client` (app) — wires them + `expect/actual` drivers + fake backend.

**Payoff:** a UI edit recompiles `:ui` only (the 24 generated SQLDelight files +
ktor/sync leave the UI compile unit) → est. **~5s → ~2–3s** (unverified — needs
the actual split to confirm), plus per-module build-cache + parallelism. Beyond
speed: it isolates the deferred **web-target (wasmJs) + async-DB migration**
(`OQ-web-target`) to `:data`, and makes `:model`/`:ui` independently testable.
The CL-SNAP snapshot loop is a direct beneficiary.

**Caveats:** the Compose compiler plugin is a per-changed-UI-file floor — the
split shrinks the analyzed set + kills cross-concern recompiles, it doesn't
remove Compose's cost. For tight *visual* iteration, Compose Hot Reload is the
complementary tool (snapshots stay the verification gate). Cheaper adjacent win
to bank first: enable `org.gradle.configuration-cache` (the `snapshotUi` task is
already config-cache-safe) — attacks the ~2s fork/config overhead.

**Risks / scope:** large refactor — the redux store wiring, `expect/actual`
driver boundaries, and the debug-only fake backend all cross the new module
lines; android/desktop/iOS targets must all still build; snapshots + the full
`:client:desktopTest` suite must stay green. **Module architecture is ADR-class**
(structure/platform) → short Proposed ADR or a design-first pass before the split.

**DoD:** editing a `:ui` composable recompiles `:ui` (+ snapshot test) only, not
`:data`/sqldelight; the measured UI-edit recompile improvement is recorded; all
targets compile; snapshots + tests green. **Reference:** measured in the CL-SNAP
session (PR #277); `specs/cl-snap-agent-snapshot-loop-design.md`.

## TASK-HEADLESS-RENDER-DAEMON — persistent headless Compose render engine (PNG + layout tree, code-reload) (NEXT — spike)

**Status: NEXT (queued 2026-07-02, from the CL-SNAP session).** Supersedes the
earlier "add Compose Hot Reload" framing — hot-reload was a *means*; a headless
render daemon is the *end*. The agent-optimal render engine: a warm long-lived
JVM exposing `render(scene | stateJson) → {png, semanticsTree, bounds}`, that also
absorbs *code* edits without a JVM restart or full Gradle build. Collapses three
follow-ups into one engine: persistent render + layout-info (the inspector) +
code-reload (escapes the ~5s recompile for composable edits too). Ideal backend
for **AI design-matching** (render → diff vs mockup → region → owning composable
→ edit → re-render, ~1s, mostly text).

**Why this shape (measured):** CL-SNAP's inner loop is ~5s recompile + ~2s
fork/render; batch-in-one-process already gets **~150ms/shot** warm. Two axes:
- **State iteration (same code, new state)** — EASY, no hot-reload needed. Extend
  CL-SNAP's batch to a daemon (stdin/socket loop) holding a warm JVM. Sub-second
  headless renders today.
- **Code iteration (edit a composable)** — the frontier (below).

**Key insight — hot-reload's Compose half is UNUSED headlessly.** `ImageComposeScene`
renders a **fresh composition per shot** (create → render → close). So there is no
long-lived composition to invalidate → hot-reload's `DevelopmentEntryPoint`
recompose-in-place hooks don't apply. The daemon needs only "the next `render()`
runs the new code." Two routes to get recompiled classes into the live process:
- **Route B — classloader swap (LOWER RISK, evaluate FIRST; no plugin, no JBR):**
  a watching/incremental compile of the changed module → render each shot through a
  fresh child `URLClassLoader` that loads the new classes (each shot is a throwaway
  composition anyway → drop the old loader). No experimental dependency. Risk =
  Compose runtime *global* state resisting reload across classloaders — the real
  thing to prove.
- **Route A — JBR hotswap (FASTER, riskier, fallback):** reuse `org.jetbrains.
  compose.hot-reload`'s JBR enhanced class-redefinition to swap classes in place →
  next render uses them. Fastest (everything stays warm) but off hot-reload's paved
  path (built to drive a *window*, not a headless server → likely reaching under
  the plugin API) + needs the JBR (~200MB) and a CMP 1.9.3 / Kotlin 2.3.20-compatible
  plugin version (tight matrix — verify first).

**Layout-info output** = the inspector Level-1 work: `SemanticsNode.boundsInRoot`
(+ role/text/testTag, unmerged) as JSON per render; optionally the LayoutNode tree
for every composable (see the `later.md` pixel↔composable inspector task — this
daemon is its natural host).

**Spike steps:**
1. **Persistent render daemon (low-risk core):** batch → long-lived process,
   `render(scene|stateJson) → {png, layout}` on stdin/socket, seeded from the
   existing `SnapshotStates`/`FakeScenarios` fixtures. Confirm ~150ms/shot warm.
2. **Add layout-info output:** bounds + semantics tree as text per render (needs
   the reduxkotlin dump to carry bounds — coordinate with the inspector task).
3. **Code-reload experiment — Route B first:** watching compile + fresh-classloader
   render; prove Compose global state survives a reload. If it fights, evaluate
   **Route A** (hot-reload/JBR) — after the CMP/Kotlin version-matrix check.
4. **Measure** edit→(headless re-render + layout dump) latency vs the ~7s baseline.

**Relation to siblings:** `TASK-CLIENT-MODULARIZE` shrinks the ~5s recompile for
*every* consumer (also feeds this daemon's Route B); this daemon is the
**agent/CI-facing** engine (headless, text-first). Live *human* visual iteration
(watch-a-window, state-preserved) remains hot-reload's actual sweet spot — a
smaller, separate dev-convenience if wanted, not this task.

**Caveats / risk:** Route B's classloader hygiene vs Compose runtime globals is the
core unknown; Route A depends on undocumented hot-reload internals + JBR. This is
R&D — the persistent-render + layout-output core is low-risk/high-value on its own;
the code-hot-reload half is the experiment. All dev-only → zero prod/CI blast radius.

**DoD:** a warm daemon rendering `{png, layout-tree}` sub-second per state; a
recorded verdict on Route B (classloader swap) — works / Compose-globals-block-it /
needs Route A; and the measured edit→render+layout latency vs ~7s. **Reference:**
CL-SNAP session (PR #277); pairs with the `later.md` pixel↔composable inspector.

## CONTENT LIBRARY + DETAIL + FOLD GESTURE (ADR 0022 — Accepted 2026-06-19)

**Status: M0 build order EXHAUSTED + MERGED TO MAIN** (PR #7, 2026-06-21) — CL-0
through CL-9 and CL-PLAT all shipped; full per-task narrative (DoD, review notes,
spec links) moved to `backlog/next-history.md` to keep this file short. **Only
CL-10 is still open:**

- **TASK-CL-10** — Adaptive two-pane detail — **BLOCKED** on a Claude-Design
  expanded-detail pass (design gap; phone-only designed).

## AUTH (ADR 0021 — S1→S3→S2→S4→S5/S6)

### TASK-AUTH-S6-D — CLI device-approval UI + scan/deep-link (building)
**Status:** Phase-1 backend + CLI QR ✅ built/tested/pushed 2026-06-23 (branch
`claude/cli-login-flow-review-aq9lp0`): step 1 `GET /device/pending` + datacenter
classifier (`1ff5f5e`), step 1b central `requireScope` read-gate (api 153 green),
step 7 CLI login QR (`76c42c8`, 13 CLI tests). **Steps 2–6 (Compose approval UI),
7b (keychain), 8 (E2E) deferred to a full-mobile-toolchain session** (no Android
SDK in the remote env). **NEXT = client AuthClient→reducer→AuthEngine→screens
against the shipped `/device/pending` + reused approve/deny.** ADR 0029 Accepted. Closes the **CLI login loop** — S3 shipped
the API grant + a text CLI, but the **mobile approval UI never existed**
(`DevicesScreen` only lists/revokes). Spec:
`docs/superpowers/specs/2026-06-23-auth-s6d-device-approval-design.md`;
plan: `docs/superpowers/plans/2026-06-23-auth-s6d.md`.
- **Phase 1** (platform-agnostic, closes loop on desktop): `GET /device/pending`
  lookup (+ **no-vendor datacenter-origin classifier**, ADR 0011 §7 intent),
  central `requireScope` gate (fixes read-enforcement gap), the 4 approval screens,
  CLI terminal QR, **CLI refresh-token → OS keychain** (closes the plaintext
  long-lived-secret gap).
- **Phase 2** (scanner + deep-link): in-app QR scanner (`expect/actual`) + App/
  Universal Links on the existing API origin. **Gated on** the scan/viewfinder
  mockups (`designs/DESIGN-BRIEF-device-scan.md`, ADR 0008) + operator sign-off.
- **Review findings folded (2026-06-23):** scope is display-only today (→ ADR 0029);
  geo/ASN was deferred but ADR 0011 §7 mandates it (→ datacenter heuristic now);
  plaintext refresh token (→ keychain); read scope unenforced (→ `requireScope`).

### TASK-AUTH-CONTENT — content-API + CLI content verbs + per-hub scoping (ACTIVE)
**Status:** **gates cleared — in build** (worktree `claude/auth-content-slice`, off
`main` 2026-06-24). ADR 0029 **Accepted** + operator re-approved 2026-06-24; ADR 0030
(per-member visibility) **Accepted** 2026-06-23 — this slice now carries both. The
CLI today can only `PUT` one card — **no hub endpoints, no `pull`/`status`/`diff`,
no content read**. This slice makes the CLI a real content read+write client and
lands **per-hub/resource scope selection** (ADR 0029) + **per-member hub visibility**
(ADR 0030).
- API: hub/section/block read+write endpoints, each behind `requireScope`.
- ADR 0029: `credential_grants` table + resource-qualified scope resolution.
- ADR 0030: hub `visibility`/`created_by` + hubs-only `resource_visibility` +
  `→hubs.updated_at` touch-trigger + card `visibility`/`audience[]` + read-path
  filter + visibility-aware `/sync` + client cache-wipe on tenancy 401/404.
- CLI: `pull` / `hub get|archive|rm` / `status` / `push --dry-run|--diff`; `whoami`
  shows family + scope + label (07-cli.md).
- Approval UI: per-hub read/write picker on `AuthorizeDevice` (replaces the interim
  informational scope row). **Design/toolchain-gated** (Compose UI; not in the
  first agent slice).
- Hub **render** surface = **design-gated** (ADR 0008 hi-fi Hubs mockups + sign-off)
  — out of this slice; this slice is API + data + CLI only.
- Specs: `specs/domain-model/scope-and-access-model.md`, ADR 0029, ADR 0030. Own
  spec → plan → build cycle.


**AUTH-S4 (owner-approved invites + family-agnostic cred fix) — ✅ DONE (branch
`auth-s4`, pending merge) 2026-06-19.** `invites` table; app creds family-agnostic
(`family_scope=NULL`, membership-gated) — **clears the S1 two-family limit** (that
test un-skipped); `/auth/whoami`→`{family_id, families}` (S3 CLI compat kept);
mint / redeem (atomic single-use FOR-UPDATE claim) / approve / decline / revoke /
remove (≥1-owner **row-lock**) / list-queue (invitee identity for the approver);
owner+`kind='app'` gate; uniform-404 + per-account lockout; never-owner role.
Spec twice-reviewed (5-dim multi-agent) + 7 TDD tasks each task-reviewed + clean
final whole-branch security review (no Critical/Important, no fail-open seam). 96
API tests / 0 skips. Legacy household token still works.
- **AUTH-S4 follow tickets (deferred, non-blocking):** (1) **S6-facing:** dedupe the
  approval-queue `user_identities` LEFT JOIN (a multi-identity user fans out to N
  rows — surfaces at Firebase S2) — note on the S6 task; (2) cleanup: drop dead
  `clientIp` import (mint) + dead `RETURNING role` (approve); mint `expires_at` via
  `RETURNING`; (3) soft pending-cap is racy across distinct invites of one family
  (anti-abuse, non-security); (4) the expiry **sweep** (shared with the S3 m-2
  follow) for `invites`/`rate_limits`/terminal rows.
- **AUTH-S4 ✅ MERGED** to `main` 2026-06-20 (PR #4, `66c783d`). Branch `auth-s4`
  == origin/main (no diff).
- **A8b auth/family/invite mockups — ✅ DELIVERED 2026-06-20 (pending operator
  sign-off, ADR 0008).** `designs/Family AI dashboard design brief/designs/
  Auth-Phone.dc.html` extended 6→18 views — all 9 spec screen-groups incl. the
  previously-missing **authorize-device (RFC 8628)**, **enter-code**, **members +
  pending approvals**, **connected devices**, **provider-link-conflict**,
  **account export/delete**, plus offline / OTP-error+resend-limit / waiting-for-
  approval / invite expired·revoked·exhausted / already-member. Light+dark;
  rebranded **HEARTH→Dayfold** (turned-corner mark, per Brand.dc.html). `Auth.dc.html`
  gallery refreshed (23 frames; header ADR 0010→0011, "auto-join" removed); stale
  Index footer "(no auth)" fixed. Verified outside the dc runtime (extension was
  offline): tag-balance, 36 render-combos through `renderVals()`, all 32 `c.*`
  tokens defined w/ light/dark parity, all frame views ∈ enum. **GATE: operator
  opens the dc files + signs off → unblocks S5/S6.** A8b merged to `main`
  2026-06-20 (PR #5, `f399583`); operator merged = sign-off. **✅ ADR 0008 sign-off
  explicitly recorded 2026-07-07 (resolves the merge-vs-signoff ambiguity) — the
  owner invite-mint UI (code + QR share) is cleared to build.**
- **S2 vendor/cost gate CLEARED — ADR 0023 (operator-directed 2026-06-20):**
  Firebase **Google + Apple only, Phone-OTP deferred** → no Blaze, no SMS spend
  ceiling, no SMS-fraud/SIM-swap surface; ADR 0011 architecture intact. S2 is now
  buildable (recovery-floor counsel gate smaller without phone). **S5/S6 sign-in
  renders Google + Apple only** — the phone button + OTP/OTP-error screens stay
  designed-not-built (A8b mockups unchanged).
- **AUTH-S5 slice-1 (authenticated session + onboarding gate) — ✅ DONE 2026-06-20
  (branch `auth-s5`, PR pending).** Firebase-stubbed via dev-token (operator-chosen).
  Introduced the app's **first navigation** (pure `when(route)` gate, ADR 0013) +
  the **session/token layer**. T1 route gate · T2 `AuthClient` (ktor) · T3
  `TokenStore` (desktop 0600 / Android prefs / iOS NSUserDefaults) · T4 `AuthEngine`
  (mutex orchestrator + 401 refresh-and-retry) · T5 Dayfold screens (sign-in
  Google/Apple, create-family, family-null) + 9 snapshots vs mockups · T6 wired
  all 3 shells + `SyncClient`→token/family providers. **Verified:** 74 desktopTest
  green, android compiles, iOS framework links, **LIVE ROUND-TRIP PASS**
  (`apps/api/scripts/s5-roundtrip.mjs`: dev-token→whoami→create-family→push→sync).
  No `HOUSEHOLD_SECRET` on the JWT path. Spec/plan in `docs/superpowers/{specs,
  plans}/2026-06-20-auth-s5*`.
  - **S5 slice-1 follows (non-blocking):** (1) `SyncEngine` 401→`AuthEngine.refresh`
    hook (mid-session access-expiry mid-poll; restore already refreshes); (2) secure
    token stores (EncryptedSharedPreferences / Keychain); (3) immediate post-create
    sync polish; (4) a Feed sign-out affordance.
  - **NEXT: AUTH-S5 slice-2** (invitee-join: invited/waiting/invite-error/
    already-member + provider-link-conflict) · **S6** (invite gen, authorize-device,
    members+approvals, devices, account) · **S2** (real Firebase Google/Apple behind
    the same buttons — gate cleared by ADR 0023).

### AUTH-S5/S6 — full status as of 2026-06-21 (post slice-1)

Built across a /loop run; **the client auth/account/family surface is
comprehensive and e2e-tested on a real emulator** (`fad_atd35`, API-35 AOSP ATD
— provisioned because the on-hand emulators were API 37, which espresso can't
drive). **4 instrumented `AuthFlowE2ETest` cases pass on-device:** sign-in →
create-family → feed → account → **sign-out (confirm)** · **join-by-invite** →
waiting · owner **approve + remove** · **connected-device revoke**. Mirror desktop
`AuthFlowUiTest` (runComposeUiTest) is the default-loop e2e.

**MERGED to `main`:**
- S5 slice-1 (PR #6); A8b gap designs (#8); ADR 0025 auth rate-limit constants (#10);
  members/approvals 3c+4a+4b backend (#12); **data-export `GET /auth/me/export`**
  + **connected-devices backend** `GET`/`DELETE /auth/me/credentials` (#13).
- **Slice A** AccountScreen + sign-out · **B** e2e harness + fixed inert AuthButton
  bug · **C** sign-out confirm · **2a-2c** invitee-join (transport/UI/e2e) ·
  **3a-3c** owner approvals (queue + approve/decline + screen) · **4a-4c** member
  roster (GET /members + render + remove).

**OPEN PRs (awaiting operator review/merge):**
- **#15** connected-devices client (`DevicesScreen` + revoke, e2e on emulator).
- **#16** profile endpoints (`GET`/`PATCH /auth/me` display name).
- **#17** retention sweep (`sweep()` expired rate_limits/device-codes/orphan invites;
  resolves the S3/S4 sweep follow).

**GATED — needs the operator (not agent-decidable):**
- **Account-delete** — the inert AccountScreen button + designed `deleteconfirm`/
  `transferowner`. Permanent data deletion + the schema needs a policy call:
  `credentials`/`family_scope` have no ON-DELETE cascade; sole-owner = block-and-
  transfer vs auto-delete-family; soft (`users.deleted_at`) vs hard. **Escalated;
  not built pending the approach decision.**
- **AUTH-S2 Firebase** — real Google/Apple behind the stubbed dev-token buttons
  (ADR 0023 cleared the vendor scope; needs the Firebase project/console step).
  Editable-name client (#16's UI) + provider display names land with S2.

**Resolved earlier follows:** sign-out affordance (Slice A/C); invitelocked
constant (ADR 0025); the retention sweep (#17). **Still open:** `SyncEngine`
401→refresh hook (mid-session); secure token stores (EncryptedSharedPreferences/
Keychain); the instrumented e2e needs a ≤API-36 emulator (CI note).
- **A8b failure/destructive design gaps — ✅ CLOSED + IMPORTED 2026-06-21** (Claude
  Design pass from `designs/DESIGN-BRIEF-auth-gaps.md`, pulled via the claude_design
  MCP). `Auth-Phone.dc.html` now **25 views** (18 → +7): **slice-2 invitee
  failures** `invitedeclined` / `invitelocked` (429) / `joinerror` (transient) and
  **S6 destructive** `deleteconfirm` (type-DELETE + Apple-disconnect) /
  `transferowner` (≥1-owner member picker; also the members-409 path) /
  `devicedenied` / `deviceexpired`. Gallery = 37 frames (light+dark). Verified
  render-valid (tags 29/29·7/7·277/277, all views through `renderVals()`, token
  parity, frames ∈ enum). **Slice-2 now has full happy+failure design coverage;
  S6 destructive-action screens designed.** **✅ Operator signed off (ADR 0008)
  2026-06-21 — design gate CLEARED for slice-2 + the S6 screens; build may
  proceed.** Invitelocked cooldown constant resolved: **5 fails / 15 min →
  15-min lock** (matches S4 `app.ts:286`), recorded in **ADR 0025** (auth
  abuse-control constants) + the screen copy says "~15 min".

**AUTH-S3 (CLI device grant, RFC 8628) — ✅ DONE + MERGED** to `main` 2026-06-19
(PR #2, all CI green). `/device/{authorize,token}` + `/families/:fid/device/{approve,deny}`
+ `/auth/whoami` + the refresh ~20s reuse-grace (resolves the S1 carried debt) +
Kotlin CLI `login`/`logout`/`whoami` + device-granted `push` (0600 file,
cross-process refresh lockfile, legacy env fallback). Owner+`kind='app'` approve
gate (stolen-CLI + legacy both 403), PATH-resolved tenancy (anti-IDOR), lazy-mint
at redeem (one-time, atomic), DB-backed rate-limit + per-account lockout, audit
log. Spec twice-reviewed (7-dim + 4-dim multi-agent) + 7 TDD tasks each
task-reviewed + a clean final whole-branch security review (no Critical/Important,
no fail-open seam). 67 API tests + CLI CredentialsTest + live round-trip green.
- **AUTH-S3 follow tickets (deferred, non-blocking):** (1) retention sweep for
  `rate_limits` / `audit_log` / terminal `device_authorizations` (unbounded growth
  — land before non-dogfood traffic); (2) drop the vestigial `genuineReuse` var in
  refresh.ts; (3) align `/device/deny` already-denied → 204 (vs current 404) +
  tighten the lockout test to the exact 6th-attempt 429; (4) `genUserCode` modulo
  bias (cosmetic; device_code is the secret); (5) `slow_down` interval cap (CLI is
  wall-clock-bounded already).
- **⚠ Governance note:** ADR 0021 §3 says the legacy household-token branch is
  "removed in S3." This slice **deliberately KEPT it** (the S3 brainstorm chose
  non-breaking coexistence; removal gated to a follow once the device-granted CLI
  is deployed + the operator migrates). Intentional spec-over-ADR narrowing — the
  legacy-removal cutover remains a tracked follow (the `TODO(S3-cutover)` in
  `middleware.ts`). ADR 0021's "removed in S3" should not be read as done.
- **NEXT after S3 merge: AUTH-S2** (Firebase identity) or **S4** (invites) per ADR
  0021. S3 fully kills CLI hardcoding once deployed (operator-gated prod deploy +
  `AUTH_*` env in Vercel).

**AUTH-S1 (Tenancy & token backbone) — ✅ DONE + MERGED** to `main` 2026-06-19
(branch `auth-s1`). Backend-only, Firebase-stubbed, non-breaking. EdDSA token
service + refresh lineage + `authorizeTenant` middleware (JWT + legacy household
path, default-deny, fail-closed, per-request membership re-resolution, cross-tenant
404) + `/auth/{refresh,signout}` + `POST /families` + JWKS + **gated local-only
dev-token** (kills LOCAL build/test hardcoding) + content routes migrated. 51 tests
+ 1 skipped, vs live PG; final whole-branch security review passed (no Critical,
no fail-open seam). Spec/plan in `docs/superpowers/{specs,plans}/2026-06-19-auth-s1*`.
- **Carried debt (from the final review):**
  - **→ S3:** refresh **~20s reuse-grace not implemented** — a client that retries a
    refresh (timeout+retry) presents the same token twice → loser hits reuse-detect →
    **revokes its own credential**. Fails closed; harmless at S1 (single test client),
    but a real CLI/mobile client at S3 will need the grace re-serve. (Spec §token model.)
  - **→ S4:** `POST /families` binds only the user's first null-`family_scope`
    credential → one user creating a **2nd family** gets fail-closed 404s on it
    (documented by a skipped E2E test). S4 (invites/multi-family) redesigns
    cred→family binding (per-family creds).
  - Cleanup (S3 cutover / pass): `:any` typing on the middleware boundary; dev-token
    `Math.random` cred id → crypto + reuse `mintCredentialFor`; dup `content:*` scope
    literal; lazy-import = first-request (not boot) detection of missing `AUTH_*`.
- **NEXT: AUTH-S3** (CLI device grant, RFC 8628) — fully kills cloud/device
  hardcoding + triggers the legacy household-token cutover. Then S2 (Firebase), S4
  (invites), S5/S6 (UI, ADR 0008 design-gated).
- **Deploy note:** the live API still runs the household token until a prod deploy of
  this branch (operator-gated); the regenerated `api/index.js` carries the auth surface.

## TASK-KMP — Restructure apps/client into a true KMP module (prerequisite)

**Status:** ready (next session). **Blocks:** TASK-SYNC step 2+ (Android offline
DB) and the **iOS** shell. **Why:** today `apps/androidApp` borrows `apps/client`
source via `srcDir` — which **can't carry SQLDelight's per-variant generated
code** (proven in TASK-SYNC step 1), and there's no iOS target. The fix is to
make `apps/client` a real Compose-Multiplatform module: `commonMain` (shared
logic + UI) + `androidTarget` / `jvm("desktop")` / iOS targets.

**Scope:**
1. Convert `apps/client` to `kotlin("multiplatform")` + `com.android.library` +
   `org.jetbrains.compose` + `kotlin.plugin.compose`. Source sets: `commonMain`
   (Model, Reducer, Selectors, CardRender, FeedScreen, FeedApp, ContentStore,
   SyncClient), `androidMain` (driver + WorkManager), `desktopMain` (Main.kt +
   JdbcSqliteDriver), `iosMain` (NativeSqliteDriver + BGTaskScheduler glue).
2. **SQLDelight in commonMain** (`generateAsync`? no — sync drivers); remove the
   `srcDir` borrow + the `ContentStore`/`Main.kt` excludes in `apps/androidApp`
   (which becomes a thin `:androidApp` depending on `:client`, or fold the
   Android entry into `androidMain` + an `application` module).
3. **HTTP cross-platform:** `SyncClient` currently uses `java.net.HttpURLConnection`
   (works on desktop+Android, **NOT iOS**). Swap to **ktor-client** (`cio`/`okhttp`
   desktop+android, `darwin` iOS) in commonMain — or keep an `expect/actual`
   HTTP fn. ktor is the clean call.
4. iOS app target (needs the operator's Mac/Xcode — escalate that part).

**Gotchas already solved (don't re-derive — see `processes/agent-dev-loop.md`):**
redux-kotlin alpha01 on Kotlin **2.3.20**; `store.selectorState{}` is an
**extension**; `redux-kotlin-granular` added explicitly; SQLDelight **2.3.2** +
**sqlite-3-38 dialect** (UPSERT); devtools `debugImplementation` inapp /
`releaseImplementation` inapp-noop; JDK 17; compose-MP **1.9.3** (watch the
AGP↔Kotlin↔compose-MP matrix when it becomes a KMP+android-library build).

**DoD:** one `:client` KMP module; `commonMain` holds all shared code incl.
SQLDelight + sync; android/desktop build from it (no srcDir, no excludes); tests
+ snapshots still green; iOS target compiles (run gated on Mac).

## TASK-SYNC — Persistence & Sync (offline-first client) · ADR 0020

**Status:** ✅ DONE + MERGED to `main` 2026-06-19 (merge `13db28b`). Steps 1–4 +
foreground poll shipped: SQLDelight DB-as-SoT, `SyncClient`→transport, `SyncEngine`
(mutex drain + `activeCardsFlow`→`CardsLoaded` bridge + start/resume/pause/poll),
instant offline cold-start, unidirectional `network→DB→store→UI`, crash-safe cursor.
24 desktop tests green, Android APK assembles, iOS framework links. Spec+plan in
`docs/superpowers/{specs,plans}/2026-06-19-task-sync*`. **REMAINING (deferred,
new slices):** **R3 background** — Android `WorkManager` `PeriodicWorkRequest` +
iOS `BGTaskScheduler` `BGAppRefreshTask` (both call the shared `SyncEngine.syncNow`;
iOS needs the Xcode iosApp shell first); **push** (FCM/APNs/SSE → `syncNow` hook);
**iOS sync-config** plumbing (api/family/secret, the BuildConfig analogue);
`payload`/`$defs` richer card fields. **Why it mattered:** the M0 client was
in-memory (network round-trip every open, no offline/cursor) — now fixed.

**Scope (build slice):**
1. **SQLDelight (KMP)** as source of truth — drivers per platform
   (`AndroidSqliteDriver` / `NativeSqliteDriver` iOS / `JdbcSqliteDriver` desktop);
   tables = content (cards at M0) + `sync_meta(cursor, last_synced_at)`; WAL.
2. **Sync engine** (`commonMain`) — rewrite `SyncClient` to write the DB in ONE
   transaction (upsert + tombstones + advance cursor); drain `has_more`
   (network → DB, not network → store).
3. **DB→store bridge** — SQLDelight reactive `Flow` → hydrate the redux store;
   `selectorState`/`FeedApp` unchanged (store = projection of DB).
4. **Cold-start** — hydrate store from DB first (instant, offline), then sync.
5. **Foreground poll loop** (~30–60 s, paused on background) + **Android
   `WorkManager`** + **iOS `BGTaskScheduler`** glue — all calling the shared engine.
6. **Tests** — offline-open (DB only), sync→DB→UI, background-sync writes DB,
   cursor survives restart. Verify via the snapshot/test loop + on-device.

**DoD:** opens instantly offline from cache; a foreground push reflects within one
poll interval; background sync keeps the next open fresh; `network→DB→store→UI`
holds. **Push (FCM/APNs/SSE) out of scope** (later milestone; same dataflow).
**Milestone:** next build slice after the M0 render.

## DEFERRED (from hub-sync PR2 / migration 0010)

- **hub-visibility-flip child fan-out trigger** — add with the visibility-toggle authoring slice (no M0 actor flips hub visibility; authoring is ADR-0016/0029-deferred).

## TASK-E2E — Investigate end-to-end encryption (privacy differentiator)

**Why now:** the server is a **dumb store that never processes content** (ADR
0004/0007), so E2E is structurally feasible: **CLI encrypts → server stores
blind ciphertext → device decrypts**. Privacy is a top selling point and this
would make it architectural, not policy. Investigation kicked off
2026-06-18 → `research/e2e-encryption-investigation.md` (agent in progress).

**Scope of the investigation:**
- What can be E2E (body_md, payload, titles, triggers, place coords) vs what
  must stay cleartext for routing (family_id, IDs, versions, timestamps).
- **Key management/distribution across the multi-member family + owner-approved
  invite + RFC 8628 device-grant flows** — how a family content key reaches
  each member device + each CLI credential **without the server seeing it**
  (passphrase-derived vs per-member public-key-wrapped vs sealed-sender).
- **Features sacrificed:** server-side `tsvector` FTS (→ client-side search),
  any server validation. Quantify the loss.
- **Recovery / key-loss** (E2E = lost key → lost data): recovery-phrase /
  key-backup UX + escrow tradeoffs.
- **Perf:** decrypt-each-time vs store-decrypted in the SQLDelight cache
  (on-device cache security).
- **KMP libraries** (libsodium/lazysodium, Tink, age) + maturity.
- **Threat model:** protects server breach; not device compromise; metadata
  leakage (sizes/timing/which-family).
- **Milestone:** likely **M0 E2E is easy** (single household, operator-only
  key); the hard part (multi-member key distribution) is M1. Recommend split.
- **ADR recommendation** (this is ADR-class — privacy posture + architecture).

DoD: a feasibility report the operator can decide go/no-go + milestone from;
if go, a Proposed ADR.

## TASK-license-strategy — Licensing & open-source strategy (research) · ADR-class

**✅ RESEARCH DONE 2026-06-25 → Proposed ADR 0032** (awaiting operator + [pending-counsel]). Report: `research/2026-06-25-licensing-open-source-strategy.md`. Verdict: open-source-for-showcase GO; Apache client/CLI/schema + AGPL server + closed G1; hosted-SaaS monetization. Original brief below.

Deep research + brainstorming on **how to license & publish Dayfold (apps + CLI)**:
can it be open source **safely** (security + business-strategy), which license, can it
still be **monetized** if OSS, and the business-strategy tradeoffs. Ideal = OSS (for
showcase / resume / free tooling) **and** a monetization path. Triggered by ADR 0031's
deferred license gate. **Brief:** `research/licensing-open-source-strategy-brief-2026-06.md`
(scope, the 5 questions, method, the AGPL-server + Apache-client + closed-brains
hypothesis). Method: deep research (cited) + the `solo-business-strategist` agent +
a security open/closed-split analysis + two adversarial-review rounds. **DoD:** a
research report (A–D answered + recommendation) + a Proposed ADR (per-component license
+ closed surface + monetization model) that closes ADR 0031's gate and `OQ-license`.
**Final license is legal/business → operator-gated + `[pending-counsel]`; research
informs, operator decides.**

## MOBILE RELEASE PIPELINE (ADR 0034 — Proposed 2026-06-25)

**✅ PIPELINE BUILT + locally-verified** (signed `bundleRelease`, versionCode/Name from
env, unsigned-without-secrets all confirmed on a local SDK). `release-android.yml`
(merge→`internal`, `android-beta-v*`→`beta`, `android-v*`→`production` draft) +
`:androidApp` signing/versioning + a PR `assembleDebug` smoke job in `ci.yml`. Inert
until the operator gates (**INB-23** / ADR 0034 G1–G5). Follow-on tasks:

- **TASK-mobile-promote-artifact** (ADR 0034 G6) — switch beta/prod from rebuild-from-tag
  to **promote the exact alpha-tested artifact** (Play track-to-track promotion via
  fastlane `supply --track-promote` or the edits API), so "what was tested is what
  ships." Needs Play set up (G3) to design/verify. Medium.
- **TASK-mobile-r8** (ADR 0034 G7) — enable R8 minify + resource-shrink for the release
  variant with **vetted keep-rules** (redux-kotlin, Firebase, Compose, kotlinx-
  serialization, ktor). Currently `isMinifyEnabled=false`. Verify a signed AAB still runs
  on-device + the fake-backend/debug paths are unaffected. Medium.
- **TASK-mobile-sdk-firstrun** (ADR 0034 G9) — validate the GitHub-runner Android-SDK
  setup on the first real CI run (the `platforms;android-37` vs `android-37.0` package
  name + build-tools), tighten the install step once observed. Small; do at first run.
- **TASK-ios-pipeline** (ADR 0034 G8) — **BLOCKED on building the Xcode/Swift host app**
  first (only the KMP framework compiles today). Then: TestFlight-internal as the iOS
  "alpha" (no merge-time auto-publish — Apple processing/review), fastlane `match`
  (signing) + `pilot`/`deliver` via an **App Store Connect API key** on a **macOS runner**
  (~10× minute cost), driven by the same `android-*`-parallel tags. Needs the operator's
  Mac + an Apple Developer account ($99/yr — **spend**). Large; sequenced after the iOS
  host shell (contends with the iOS-shell task in TASK-KMP).

## TASK-CLIENT-MODULARIZE — `:client`/`:ui` module split (ADR 0047) ✅ DONE 2026-07-02

**Status:** Slice 1 (`:ui` Compose extraction) COMPLETE — **−53% lines / ~−43% incremental-build
time** on a `:client` logic edit. Full shipped narrative (phases P0–P2.3, branch/commits, measured
numbers) moved to `backlog/next-history.md` to keep this file short; read it there if you need the
detail. What's still open from this slice:

**Deferred / still open:**
- `:androidApp:assembleDebug` / `assembleRelease` — **BLOCKED** by missing `google-services.json` (no secret in worktree). Required follow-up in CI/secret-bearing env before declaring full-build DoD.
- Android CC compatibility (`:androidApp:assembleDebug` + `com.google.gms.google-services`) — untested, same blocker.
- Android + full-graph incremental compile measurements — deferred for same reason.
- **`:model`/`:data` further split** (second slice, ADR 0047 §Remaining) — would shrink each module further; not in this slice.
- KT-62686 escape investigation — not Compose-specific (fires on all KMP modules); may need Kotlin 2.4.x or `enableUnsafeIncrementalCompilationForMultiplatform=true` (risky); deferred.

**ADR 0047 shape delta:** iOS framework now in `:ui` (not `:client`). Header: `apps/ui/build/bin/iosSimulatorArm64/debugFramework/client.framework/Headers/client.h`. Immutable ADR unchanged; delta recorded in `specs/client-modularize-measurements.md` P2 section.

---

## CODE DEDUP FINDINGS (2026-07-01 audit; re-swept + partly applied 2026-07-05)

Not urgent (CI is green, nothing broken) — surfaced by repo-wide simplify passes.
The 2026-07-05 pass applied the small, mechanically-safe items by careful inspection
(no local Gradle/npm registry access in that sandbox either — same constraint as
every pass since, this one included: `registry.npmjs.org`/`repo.maven.apache.org`
still 403 via the proxy as of 2026-07-12). CI landed green on those (re-confirmed
2026-07-12, latest `main` run + the last 15 all green) — per that entry's own
instruction, the applied sub-list is now deleted (the commits are the durable
record, in `git log`/`CHANGELOG.md`).

**Still open — not applied, still needs a build-capable toolchain to verify:**
- **`apps/api`** — auth-route boilerplate (`bearer(c)` + lazy `verifyAccess` +
  the revoked-credential check) is repeated ~9× across `/auth/signout`,
  `/auth/whoami`, `GET`/`PATCH /auth/me`, `/auth/me/export`, `/auth/me/credentials`
  (GET+DELETE), `DELETE /auth/me`, `POST /families`, `GET /device/pending`
  (2026-07-05 audit: `app.ts` ~lines 158/177/189/207/228/244/266/290/720). Extract
  a `requireSession(c): {sub,cid} | Err` helper mirroring `authorizeTenant`. Left
  unapplied this pass — 9 call sites is more surface than the mechanical
  extractions above; do it with a real build to catch a subtle miss.
- **`apps/api`** — "fetch hub, check visible, else 404" repeated verbatim 3×
  (`GET /hubs/:id`, `/hubs/:id/tree`, `/hubs/:id/audience`) + once more (with an
  extra author-gate check) inside the hub PUT. A `hubs.getVisibleHub(fid, id,
  caller)` helper in `content/hubs.ts` would cover the 3 GET routes.
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
- **`apps/api`** — `app.ts` is ~1000 lines holding all ~45 routes. Splitting into
  per-resource route modules is still the biggest win but the biggest risk;
  needs a real build to land safely.
- **CLI/skill docs** — moderate (3-4x) duplication of the same explanations
  across `SKILL.md` / `references/cli.md` / `references/content-model.md` /
  `templates/README.md` / `USAGE`: hub timeline, block payload field table,
  visual-enrichment/`media`, auto-linkify, and "local validation is a pre-check
  only" are each explained in 2-4 places. Not inconsistent (the copies agree),
  just redundant. **Partly applied 2026-07-09:** `templates/README.md`'s
  "What the local validator checks" and its full restatements of Guardrail 3
  (email) and privacy chips were trimmed to pointers at `references/cli.md` /
  `references/guardrails.md` (the canonical copies) — the "known asymmetries"
  detail moved into `cli.md`'s Push section rather than being lost. **Found +
  fixed in passing: `templates/README.md`'s privacy-chips section was stale**
  (only listed 2 of the schema's 4 `privacy.storage` values — a real doc bug,
  not just duplication). **Still open:** hub-timeline field table
  (`content-model.md` vs `templates/README.md`, the latter already has a
  pointer + condensed version — lower priority, arguably intentional since
  `templates/README.md` needs to stand alone for non-Claude CLI users), block
  payload table (`content-model.md`'s is simpler; `templates/README.md`'s adds
  the ADR-0035 "also accepted" alias column — consider merging the alias
  column into `content-model.md` and pointing `templates/README.md` there),
  checklist id-stamping (repeated near-verbatim in `cli.md` + `content-model.md`
  + the `templates/README.md` table note — low priority, each copy is already
  short).
