# Backlog — Now

Current state only — kept short on purpose (start-of-session reading cost).
**Full chronological build history (every dated status update, feature by
feature) → [`backlog/now-history.md`](now-history.md).** This file was split
2026-07-03 (was 570 lines, one append-only log); read the history file when
you need the detailed narrative behind something below, not by default.
**Repo-maintenance passes older than the most recent one live in the history
file too** (moved there 2026-07-10, same reason) — this file keeps only the
latest pass's findings so it doesn't re-grow past its own stated purpose.

## ⚠ Time-sensitive (hard dates — keep pinned at top)

- `main` was RED 2026-07-21 (head `d589193`, the 14th pass's own merge
  commit — root cause: unpinned `quicktype` version drift, see 15th-pass
  entry below); fixed by PR #353, merged, and **re-confirmed green** at head
  `4aa645b` (CI run 29846190029, success) by the 16th pass. Was also red
  2026-07-05→07-07; PR #291 added `.github/workflows/rebuild-api-bundle.yml`
  (`workflow_dispatch`, `contents: write`) as a standing self-heal tool for
  the next time the committed API bundle drifts from source — see
  `backlog/now-history.md` (2026-07-07/07-09 entries) for that incident + fix
  if you need it.
- **Quarterly:** re-check whether Google ships a *free, family-shared*
  Gemini Daily Brief variant (KS-6 / OQ-gemini-family). First check ~2026-09.
- **Quarterly:** re-check whether **Gemini Nano 4 has shipped structured output
  + tool calling** (OQ-ondevice-k2). That is the revisit trigger for an
  on-device "K2" key-holder agent — assessed 2026-07-13 as **NO-GO today**
  (capability-blocked: 4k-in/256-out, no JSON schema, no tools, foreground-only,
  per-device output variance breaks the shared-briefing wedge). First check
  ~2026-10. → `research/2026-07-13-on-device-llm-assessment.md`.
- **P0 viability review is OVERDUE** (was due 2026-07-18; the planning loop
  has logged only its bootstrap iteration, `processes/loop-journal.md`
  Iteration 0) — full narrative + proposed default in
  `backlog/operator-inbox.md` **INB-33**, open, awaiting the operator.

## Current state (as of 2026-07-10)

### Now timestamp correctness — 2026-08-28

Time-triggered derived items now stay on the Now tab for a two-hour grace period
after their actual event time instead of disappearing immediately.
Alert offsets drive surfacing and exact local scheduling without corrupting the
displayed event time; labels are device-timezone-correct and include AM/PM;
authored cards retain their most relevant trigger anchor after it fires (with
explicit `expires_at` still governing removal). The visible Now route samples
the live clock every minute, so its date, bands, and time windows no longer wait
for an unrelated store update. Focused `:client` and `:ui` desktop tests are
green, including before/after boundaries, multiple triggers, offsets, timezone
copy, exact schedules, live clock advancement, and pinned snapshot clocks.

### Calendar Check epic (CAL, ADR 0063 — Accepted and enabled 2026-08-28)

Client-owned Calendar↔Dayfold reconciliation (WI-446 through WI-451) is built:
device-local reconcile + gap review, Android/iOS native add-event handoff, a
reviewed Calendar→Dayfold import wizard, and
Calendar-owned start-alert suppression. WI-451 (CAL-11) closed the epic's
privacy proof — permanent guard tests across `:client`/`:swip-wiring`
desktopTest asserting raw calendar identifiers/fingerprints/observations
never reach sync/outbox payloads, logs, analytics, or the SWIP debug
inspector (ADR 0063 acceptance gate 6). **Full epic detail, remaining
operator gates (mockup sign-off record, horizon-constant ratification,
permission-copy device review, store data-safety disclosures, ADR
acceptance reviews), and the on-device smoke checklist live in the shipyard
epic — this is a pointer, not the record.**

The operator accepted ADR 0063 and signed off the design/defaults on
2026-08-28. Production reachability, Android/iOS adapters, settings hydration,
durable local decisions, reviewed-import recovery, and typed field resolution
are enabled. Calendar Check remains a default-off member opt-in under Account.

### Active — TASK-CLIENT-RUNTIME-HARDENING (started 2026-07-14)

PR 1 plus the runtime/session, engine-hardening, immutable-command,
platform-lifecycle, stable-Compose-boundary, and route-level render-isolation
work through the bounded portion of Task 14 are implemented and locally
verified, except for the plan's explicitly unchecked PR 2
collector-extraction/race-test items. Production Redux notifications use serial
UI-thread contexts; `ContentStore` owns process-safe writer/snapshot
serialization; auth/family epochs fence stale commits; sync requests conflate;
Hub work is generation-correlated; Auth uses narrow request gates; and Now uses
one ordered actor plus consistent multi-table snapshots. Production hosts retain
one runtime graph and expose stable selection/dispatch, method-only command, and
platform-handoff capabilities to Compose. The duplicate command wrapper has
since been replaced by the compiler-stable `DayfoldCommandPort`; `SelectorStore`
is limited in use to selection and dispatch. The root whole-state subscription
and callback wall are gone; active
routes subscribe to immutable feature projections, while per-entity row
subscriptions remain explicitly open. Cold mobile notification targets now wait
for family restoration and are dropped at identity/tenant boundaries. Adversarial
verification also found and fixed production 401 self-join and ContentBridge
lock-order deadlocks. The serialized gate is green across 666 client, 513 UI,
and 16 SWIP desktop tests; 7 client and 3 UI iOS simulator tests; Android
debug/release; iOS device/simulator framework linking; and 12 Android API-35
connected tests. ADR 0058 is **Accepted** (operator accepted in-session
2026-07-14). Task 14 per-row isolation, Task 15 state-keyed route effects, PR 5
state/reducer slicing, and PR 6 notification/performance/platform closure remain
staged in
`docs/superpowers/plans/2026-07-14-dayfold-runtime-concurrency-render-isolation.md`.

**Stage: M0 render prototype BUILT + cloud-live** — server (TS/Hono/Postgres
on Vercel+Neon) · Kotlin CLI · KMP client (`apps/client` core + `apps/ui`
Compose, ADR 0047) · Android (dogfood, real device) + iOS (sim-verified) —
full CLI→API→DB→sync→render loop works end-to-end in prod. Validation
verdict still stands: **CONDITIONAL — learning-lab GO, business NO-GO**
(commoditized by Gemini Daily Brief/Alexa+; the defensible surface is a
**multi-member family-tenant briefing**) → **building to learn**; the
business unknowns (OQ-wtp / niche / gemini) stay untouched by design.

**Shipped and live on `main`:** full AUTH epic (device-grant login, Google
sign-in, roster/devices/account) · owner invite-mint UI — QR + share-link,
outstanding/revoke, cross-platform QR via qrose · Hub & card visual enrichment (ADR 0036) ·
Now-derived surfacing Phase A+B — priority-ranked feed + Android background
geofence/exact-alarm local notifications (ADR 0043/0044) · iOS notification
parity (sim-verified) · two-way member writes — checklist toggle, delete,
local hide (ADR 0038–0042) · Hub Timeline, authored + on-device-derived
fallback (ADR 0045/0046) · CL-SNAP headless golden-snapshot CI gate (131
goldens) · `:client`/`:ui` module split (ADR 0047, faster agent inner loop) ·
card↔detail container-transform morph fix (plain `AnimatedContent`,
predictive-back commit-animated, ADR 0050, #307) ·
navigation transition system — every nav edge animates by a central,
future-proof taxonomy (tab/push/modal/wizard/gate/hero), reduced-motion aware
(ADR 0051, #308) ·
Now-feed + Hubs-list scroll preservation across tab switches and card/hub
detail (#309/#312/#313) · card→detail shared-element morph — accent tile,
kicker, title, and primary button travel into the detail, content-equality-
gated so it's correct across all card types (#310) ·
**DB-first cold-start route gate — reopening Dayfold after the OS reclaims it
now paints from the on-device cache instead of the logo+spinner while
waiting on a network `whoami`; session reconciles in the background (ADR
0052, #314, 2026-07-09)** · **timeline detail no longer draws under the
status bar (#315, 2026-07-09)** · **SWIP product analytics wired — debug-only,
PostHog EU, count-only 8-event slice, geoip-off, never-identify (ADR 0055,
consuming swip-core/schema-dayfold 0.1.2, 2026-07-11)**.
Deferred by design: G1 content-authoring "brains" loop (interim authoring =
operator + Claude Code via the CLI/curator skill); E2EE (ADR 0017); web
target (`wasmJs`, needs a client DB async migration first).

**Shipped since (2026-07-10 → 2026-07-12, not yet folded into the "Shipped
and live" paragraph above):** account avatars + hub **People** management +
per-hub **Viewer/Contributor/Co-owner** roles (ADR 0053, #ae38c3f/#ccd38d6) ·
editable display name · scoped CLI/device tokens — per-hub grants on the
approval screen (ADR 0029 extension, 2026-07-11) · leveled/scrubbed on-device
logging (`Log` front-door, SWIP `swip-logging` bound debug-only, ADR 0056) ·
a SWIP debug-drawer **inspector panel** — live analytics timeline,
mask-by-default with `FLAG_SECURE` reveal isolation, debug-only, zero release
footprint (ADR 0057) · a 3-bug analytics-delivery fix (missing PostHog key +
missing consent grant + two SWIP SDK bugs — dogfood analytics from the
2026-07-11 slice were silently never arriving; now confirmed reaching
PostHog) · analytics events now flush on backgrounding and persist to an
on-device durable queue (SQLite/WAL) instead of being lost on a process kill.
**Mandatory on-device smoke test for the inspector panel (`FLAG_SECURE`
screenshot blanking, chrome insets) is still pending** (operator, physical
device) — the only item from this window not yet operator-verified.

**2026-07-28 repo-maintenance pass (18th)** — scheduled, same six-point scope,
but deeper than usual: three dedicated Explore agents (one per app/dimension)
did a from-scratch audit of `apps/api`, `apps/cli`, and docs/skills instead of
the usual quick pass, since it had been 4 days (17th pass, 2026-07-24) with no
new commits to re-check. This surfaced real findings 11-17 prior passes'
lighter touch had missed:

1. **Dedup/simplification — real findings in both apps this time** (unusual;
   the 17th pass found `apps/api`/`apps/cli` "clean, 11 prior passes already
   worked that queue"):
   - **`apps/api` test-suite migration drift (highest-impact finding of this
     pass).** 32 of ~35 DB-backed Vitest suites each hardcoded their own
     subset of `migrations/*.sql` filenames in `beforeAll` — inconsistent
     across files, most skipping newer migrations (0010/0011/0019 etc.).
     `test/migrations.test.ts`'s own header comment already named this as
     the exact failure class behind a real prod outage (`briefing_cards`
     missing columns no suite's hardcoded list ever applied — fixed
     reactively by `0014_card_columns_repair.sql`). Added
     `apps/api/test/_migrations.ts` (`applyAllMigrations(q)` — applies every
     file in `migrations/`, sorted, matching what `migrations.test.ts`
     already proved safe) and converted all 32 suites to call it instead of
     hand-listing files; preserved each suite's own post-migration seed SQL
     untouched. Hand-verified the full migration chain applies cleanly via a
     local Postgres 16 cluster (`pg_ctlcluster`) + `psql` before rolling
     this out — this sandbox has psql/docker but **no npm registry access at
     all** (org policy 403s both `registry.npmjs.org` and
     `npm.pkg.github.com`, confirmed via the proxy's own status endpoint),
     so `vitest`/`tsc` could not run; left to CI as the verification oracle
     (same posture prior passes used for Kotlin).
   - **`apps/api/src/app.ts`: 35 of 45 dynamic `await import(...)` sites had
     no reason to be lazy.** The file's own comment said auth imports were
     dynamic so `api.test.ts` (no `AUTH_*` env) could load `app.ts` without
     tripping a module-scope throw — but only `tokens.ts` actually throws at
     module scope; `audit.ts`/`ratelimit.ts`/`refresh.ts`/`identity.ts`/
     `invites.ts`/`device.ts`/`origin.ts`/`sweep.ts`'s `sweep` export do not.
     Hoisted all of those to static top-of-file imports (aliasing the two
     colliding `redeem` exports from `device.ts`/`invites.ts` as
     `redeemDevice`/`redeemInvite`); left only `tokens.ts` dynamic, with an
     accurate comment. Net -27 lines in `app.ts`.
   - **Three call sites re-implemented `requireCred()`'s bearer→JWT→live-
     credential prologue inline** instead of calling the already-extracted
     helper — `/auth/signout` and `POST /families` now use `requireCred(c)`
     (closes a real gap: `POST /families` previously never checked whether
     the backing credential was revoked, unlike every other mutating route);
     `/auth/whoami` left alone as the report recommended (it needs an extra
     column from the same query, restructuring would add a second round-
     trip for no benefit). Also extracted `isDeployedEnv()` for 3
     independently-written copies of the same prod/preview gate check
     (debug routes, dev-token, Firebase-emulator bypass).
   - **Two stray unit tests lived in `apps/api/src/` instead of `test/`**
     (`content.timeline.test.ts`, `content-validation.timeline.test.ts`) —
     moved to `test/hub-timeline-schema.test.ts` /
     `test/hub-timeline-validation.test.ts`, import paths updated to match
     every other file in `test/`.
   - **`apps/cli/Main.kt`: the same `Credentials()` + `resolveKeychain()` +
     `loadCreds()` prologue was copy-pasted 4x** (whoami/pull/delete/push) —
     extracted a `Session`/`loadSession()` pair; all four call sites now use
     it. `push`'s two branches (device-creds vs. legacy-env) also had
     identical duplicated `println("push ...")` + error-exit blocks —
     collapsed to one shared block after an `if/else` that only computes
     `(code, body)`. Four CLI test files had a stray-location/missing-
     package inconsistency (`LinkifyTest.kt`/`LinkifyPayloadTest.kt` had no
     `package` declaration at all; those two plus `QrTest.kt`/
     `UpdateVersionTest.kt` lived directly under `src/test/kotlin/` instead
     of the `com/sloopworks/dayfold/cli/` package dir every other CLI test
     uses) — moved all four, added the missing package line to the two that
     lacked one. **This sandbox still has no working path to a Kotlin
     compile check** (JDK 21 only, and this time even the Gradle wrapper's
     own distribution download 403s through the proxy) — same
     verify-by-PR-CI posture as the 14th/17th passes' Kotlin work, applied
     here after careful line-by-line inspection of every call site.
   - `apps/cli`'s `main()`-as-one-150-line-`when`-block, the untested
     network/auth-orchestration layer (401-retry, legacy/device branch
     selection — zero test coverage since it's inlined into `main()`), and
     `apps/api`'s dead `places` table (created by 0001, referenced by the
     generated sync schema, never read/written by any route or test) are
     real but larger findings **left for a future pass** — noted here so
     they aren't rediscovered from scratch: extracting `main()`'s branches
     into named `cmd*()` functions is the prerequisite for testing the auth
     layer; `places` needs an explicit build-it-or-drop-it call, not a
     silent removal.
2. **Agentic-docs accuracy** — two small findings, fixed: `docs/architecture.md`'s
   hardcoded "as of (2026-07-18)" header had gone stale again (the Deploy
   section was edited 2026-07-23 by the 16th pass without bumping the date —
   the exact drift class a prior pass already hit once) — this time removed
   the hardcoded date entirely rather than just re-bumping it, so it can't
   drift the same way a third time; pointed readers at `git log` instead.
   `specs/prototype/07-cli.md` (an early M1 design spec) still described a
   materially different, unbuilt CLI — wrong env var name (`FAMILYAI_TOKEN`
   vs. the real `DAYFOLD_API`/`FAMILY_ID`/`HOUSEHOLD_SECRET`), an X25519/E2EE
   device-login key bootstrap that was never built (E2EE is deferred, ADR
   0017), and a declarative git-backed directory-authoring model with no
   shipped counterpart — added a superseded-status banner pointing to the
   real sources of truth (`dayfold help`, the new `apps/cli/README.md`,
   `templates/README.md`, the curator skill) instead of rewriting/deleting a
   historical design doc. `AGENTS.md`, `CLAUDE.md`, and every `processes/*.md`
   file re-audited clean (no stale facts found) — `processes/deploy-m0.md`
   is archived-but-still-referenced-live (an open operator action item in
   this file's own "pending" section links its §2 for Vercel env setup), so
   it was deliberately NOT relocated despite being noise for a cold agent
   scanning `processes/` — moving it would break that live link for a
   one-pass tidiness win.
3. **CLI --help / skill-doc completeness** — verified clean, line-by-line
   against the live `Help.kt` command registry, the generated content
   schema, and the server-side icon allowlist: every command, flag, enum
   value, and scope-gating rule in `.claude/skills/dayfold-curator/` matches
   the shipped CLI exactly. Added `apps/cli/README.md` (the module had no
   entry point doc — `templates/README.md` existed one level down but
   nothing linked a cold reader to `dayfold help`/examples/the skill from
   `apps/cli/` itself).
4. **README/CHANGELOG/architecture** — all current, no edits needed beyond
   the architecture.md date fix above. Screenshots exist and are correctly
   referenced (`apps/ui/.../snapshots/linux/*.png`, CI-golden-verified); no
   CHANGELOG gap (last entry 2026-07-16 is accurate — every commit since is
   either an internal client-runtime refactor or a maintenance pass, neither
   of which the established convention gives an entry).
5. **CI** — no live break found. Best-evidence check only (no `gh`/GitHub-API
   access from this sandbox): `backlog/now.md`'s own time-sensitive section
   already recorded `main` green as of `715a486` (17th pass), no newer
   red-CI note exists, no unresolved CI-fix commits in `git log`, and all 7
   `.github/workflows/*.yml` files' referenced paths/scripts still resolve
   to real files — cross-checked, none stale.
6. **Values/privacy spot-check** — clean. Diff-scanned for secret-shaped
   strings (API-key/PEM-key patterns) — none found. No PII added, no new
   data collection, no dark patterns; the one behavior change with security
   surface (`POST /families` now checks credential revocation) tightens
   toward the existing pattern every other mutating route already follows,
   doesn't loosen anything.

No CHANGELOG entry — every change this pass is internal (test-suite
correctness hardening, dedup, doc accuracy); no product/API surface, request/
response shape, or feature changed. `backlog/now.md` self-pruned per its own
policy: moved the 17th-pass paragraph to `now-history.md`.

## Design-first gate (ADR 0008) — status

The **feed-only** M0 slice was built **build-first** (operator-directed) from the
initial Now mockups in `designs/`. ADR 0008 **still governs unbuilt surfaces**,
but the trigger surface's design gate is now cleared too: the v2 hi-fi mockups
(trigger v2 = INB-13, the §6b honesty rework) were signed off and **shipped** in
the Phase-B surfaces on `main` (PR #260, 2026-07-01 — see INB-13 CLOSED in
`backlog/operator-inbox-history.md`); this line was stale (still described the
M1 trigger surface as needing its mockups) until this pass corrected it.
**Event Hubs render: design gate CLEARED (INB-22, 2026-06-24)** — the
Hubs phone surface (INB-15/16) + content adaptive two-pane (INB-20) + the ADR-0030
visibility delta (`Hubs-Visibility.dc.html`, signed off) are all in; the content-
API enforcement is built (PRs #34/#35). Hub render is build-ready.
**Responses to smart content: BUILT (2026-08-08, ADR 0064 Accepted)** — designs imported
from `designs/content-feedback/`, ADR 0064 accepted in-session (INB-37), and the build
landed end to end: `subject_ref` persisted as the suppression key; `content_responses`
rows (mute + family Done) with server-side suppression by ID only; `/sync` delivery on
the existing cursor with per-member visibility and a matching tombstone sweep; the client
rule engine, outbox lane, and `ResponseEngine`; the response sheet, scope step, swipe
escalation, and Settings › Smart content; on-device enforcement on the derived lane **and**
the notification path; the CLI pre-flight filter. Verified green: API 461, `:client` 790+,
`:ui` 600+, CLI, iOS simulator + framework link, Android `assembleDebug`.
Deliberately NOT built, and not silently dropped: the **fix-it/corrections** channel
(Tier 2 — deferred to its own ADR, blocked on ADR 0062's run receipt) and the design's
**"saw N marked done"** run-receipt row (omitted rather than shown with a fabricated
count). Golden gates for the new scenes are macOS-only — docker OOMs at 7.65GB compiling
`:ui` here, the same wall the hub-people and authorize-* scenes hit; re-add the gates once
a linux golden is recorded in CI. Open follow-ups are in `context/open-questions.md`
(`OQ-response-subjectref-stability` is the one that can silently break Done).

## Operator actions pending

- [ ] **WI-462 reachability-guard follow-up triage.** The Calendar Check findings
  and `CalendarSettingsLoaded` producer gap were resolved during ADR 0063
  enablement on 2026-08-28. The unrelated pre-existing findings still need their
  own decision: `HubsFailed`/`HubNotFound` (possibly superseded by DB-driven
  sync), `ResponseStepBack`, and six `RoutinePreviewAction` members that the
  closed Smart Briefings fixture does not map. See `ReachabilityGuardTest.kt`.

- [ ] **Restart Claude Code once and confirm `dayfold-curator` still lists as a
  skill** (2026-07-30, harness-neutral skill move). The skill's real files moved
  to `.agents/skills/dayfold-curator/` — Codex's repo skills root, so one copy
  now serves both harnesses — and `.claude/skills/dayfold-curator` is a committed
  symlink to it. Codex discovery is verified (`codex exec` lists it); the one
  thing a test can't prove without a restart is that Claude Code's skill loader
  follows a symlinked skill directory. If it doesn't, the fallback is a generated
  `.claude/` copy plus a CI drift check — see
  `docs/superpowers/specs/2026-07-30-harness-neutral-skill-source-design.md`.
  **Edit only the `.agents/` copy from now on.**
- [ ] **API error reporting (ADR 0059) — PR #336 merged (`c65c0d4`, 2026-07-15);
  set Vercel env before the next prod deploy.**
  `apps/api` is wired to the SWIP error pillar (PostHog + Sentry,
  joined on `swip.fingerprint`; flush awaited in a Hono `finally` because Vercel freezes
  the container at response time). Verified live against both real vendors. The SWIP npm
  packages are published; the merged code pins `swip-js 0.5.1` / `swip-sentry 0.2.3` /
  `swip-schema-dayfold 1.0.3` (the republished set with the `scrubField` fix, SWIP #76).
  Before the next prod deploy: add `SENTRY_NODE_EU_DSN` (the API's project — *not* the mobile
  app's), `SENTRY_RELEASE`, `POSTHOG_PROJECT_KEY`, `POSTHOG_HOST` to Vercel prod
  (`processes/deploy-m0.md` §2), and the `SLOOPWORKS_PACKAGES_TOKEN` repo secret must have
  `read:packages` (it already exists for the Gradle lanes). Also still Proposed, not
  Accepted (same status-accuracy gap as ADR 0054-0057 — see INB-32).
- [ ] **Accept ADR 0060** (client crash/error reporting — debug-only Android,
  SWIP error pillar → Sentry KMP project + PostHog). Agent-drafted 2026-07-15,
  merged as PR #339 (`311c290`); Tasks 1–4 wired (error runtime, Sentry crash
  reporter, `Application` hoist, debug trigger). Same status-accuracy gap as
  ADR 0059 (merged but still Proposed — INB-32).
- [ ] **Run the on-device smoke for ADR 0060 (Task 5, Pixel dogfood
  device)** — the evidence step no unit test substitutes for: trigger the
  debug `wtf()`/`record()` and confirm the Sentry↔PostHog fingerprint join;
  force a real crash, relaunch, and confirm the mirrored `handled:false`
  PostHog event correlates by type/message/time (not by id, per the ADR).
- [ ] **ADR 0060's release-scope follow-up is blocked**, not yet actionable:
  needs the SWIP `consented`-gate gap closed (drafted issue at
  `.superpowers/sdd/swip-consent-gap-issue.md`, not yet filed) plus a
  consent surface wired to `CollectionMode`/`ConsentScope.ERRORS` and a
  privacy-policy disclosure.
- [ ] **Enable branch protection on `main` requiring the CI check before
  merge.** The 2026-07-05 CI outage (PR #289/`cf2898a`) landed without
  waiting on its own CI result; branch protection would prevent a repeat.
  Repo-settings change, operator-only (agents can't self-grant this).
- [ ] **ADR 0031 (CLI Homebrew distribution) — review + accept/reject + setup.**
  Spike (`research/2026-06-25-spike-cli-homebrew-distribution.md`) + Proposed ADR
  recommend a one-line `brew install` via a first-party tap. Operator-gated steps:
  (1) **license / public-vs-private distribution decision** (repo is unlicensed; a
  public tap distributes the CLI publicly); (2) create `SloopWorks/homebrew-tap`;
  (3) add a `HOMEBREW_TAP_TOKEN` secret; (4) accept the ADR → then the inert
  `release-cli.yml` + formula land and `cli-v0.1.0` is cut. The packaging-ready
  build change already merged (#76).
- [ ] **INB-3** kill-checks (~2 hrs): Gemini Daily Brief + Maple+ hands-on;
  note the niche gap → feeds A1. *(Only matters if pursuing the business path.)*
- [ ] Counsel confirm for ADR 0005 (14+) — only if/when pursuing teen accounts.
- [ ] **INB-19 remainder** (operator-only): publish `redux-kotlin-snapshot` to
  Maven Central + fix `reduxkotlin/homebrew-tap` symlink (keg `bin/` empty →
  binary at `libexec/Contents/MacOS/rk`). Unblocks the `:client:snapshotUi`
  golden harness → prereq for CL-NAV/CL-10.

Full narrative for all of the above (build order, TDD slices, on-device
verification notes, superseded plans, and older repo-maintenance passes) is in
[`backlog/now-history.md`](now-history.md).
