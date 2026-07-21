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

- **✅ CI is GREEN on `main`** — re-confirmed live 2026-07-16 (latest run,
  #29475848812 `CI`, `success`, 2026-07-16T06:08:37Z; one transient flake on
  `SessionBoundaryTest` at 2026-07-15T21:34:45Z self-healed on the very next
  push — second occurrence of this specific flake, worth watching if it
  recurs a third time, no action needed yet). Was red 2026-07-05→07-07; PR #291 added
  `.github/workflows/rebuild-api-bundle.yml` (`workflow_dispatch`,
  `contents: write`) as a standing self-heal tool for the next time the
  committed API bundle drifts from source — see `backlog/now-history.md`
  (2026-07-07/07-09 entries) for the full incident + fix if you need it.
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

**2026-07-18 repo-maintenance pass** (scheduled — the 12th in this series;
prior passes: `backlog/now-history.md`). Same no-npm/no-Gradle-registry-egress
sandbox as every prior pass (re-confirmed: `npm ping` 403s, no `gradlew`
present at the sandbox's expected path, `npx tsc --noEmit` in `apps/api`
still fails on missing `@types/node`) — no logic changes to
`apps/api`/`apps/cli`/`apps/client`. Confirmed **CI green** on `main` at head
`53799cb` (#349, success) before starting; still green after this pass's push.
Four parallel read-only audits covered agentic-doc duplication, CLI/skill-doc
completeness vs. `Help.kt`/`apps/api` source, README/architecture/CHANGELOG
accuracy, and code-dedup-queue soundness + a values/privacy spot-check on the
last 48h of commits — **skill docs and CLI `--help` came back clean** (no gaps;
the `cli.md` "defer to `dayfold help --json`" pattern from pass #11 is holding),
as did the **values/privacy check** (no secrets, no new PII logging, no
ADR-uncovered data collection, no dark patterns in the last 48h of commits).
**Doc fixes applied:** `processes/deploy-m0.md` retitled/trimmed to
`ARCHIVED` (its one-time Neon/Vercel setup finished 2026-06-19; it still ended
with a "what I need from you to start" section asking the operator to create
accounts that already exist — removed, section kept for its still-useful
`.ts`-import bundling gotcha); `processes/agent-dev-loop.md`'s "Now available"
section deleted (100% restated the Toolchain block two screens up, zero new
info) and its Gradle-version restatement pointed at Toolchain instead;
`processes/build-loop-prompt.md`'s commit-trailer template had a hardcoded
`Claude Opus 4.8 (1M context)` model name (this session runs Sonnet 5) that
would silently misattribute every commit and drift every model release —
genericized to `Claude <noreply@anthropic.com>`; `processes/agent-routing.md`'s
"Software build (post-spec)" row paraphrased an "8-phase workflow" that didn't
match `build-loop-prompt.md`'s actual 6-step-per-task structure — replaced the
paraphrase with a direct pointer (the row's own established pattern for every
other entry). `docs/architecture.md`'s ADR 0058 status paragraph was stale
against `backlog/now.md`'s own narrative — it said only "UI-notification and
database-serialization" were built and listed the runtime/session
coordinator + narrow Compose subscriptions as still-staged, when `now.md`
already described those as implemented+verified after commits e562835/
6e867f4/53799cb; corrected to name what's actually staged (per-row isolation,
Task 15, PR 5/6) instead. Also added the 2026-07-11 per-hub scoped
CLI/device-token grant (missing from the Auth section's device-login bullet)
and bumped the doc's self-declared "as of" date. **CHANGELOG.md: no entry
added for e562835/6e867f4/53799cb** (client-runtime reducer-decomposition/
state-slice/Compose-boundary refactors) despite one audit flagging it as a
gap — reversed that call on review: all three are internal-architecture-only
(no product/API/behavior change), which is exactly what this file's own header
and CLAUDE.md's end-of-session routine say does NOT need an entry; the
existing 2026-07-15 "fixed two production deadlocks" entry already covers the
one user-visible fact from this same ADR 0058 thread. **New safe dedup found
and applied (the first applied dedup in 12 passes):** `.github/workflows/ci.yml`
repeated an identical `actions/checkout@v4` + `actions/setup-java@v4`
(temurin 17) pair across 5 jobs — pure YAML with no compiler/type-checker
dependency, unlike the `apps/api` findings, so verifiable by the PR's own CI
run rather than blocked on this sandbox's missing toolchain. Extracted to
`.github/actions/setup-jvm/`; `release-*.yml`'s deliberately-SHA-pinned inline
checkout/setup-java (see that file's own comment) was left untouched — folding
it into the same composite would trade its stated auditability rationale for
DRY-ness, not a trade this pass should make silently. **`apps/api` dedup queue
(`backlog/next.md`): reasoning re-confirmed sound, still not applied** —
spot-checked `ownerGate` (7×, confirmed) and the validation-error shape (68×
via grep, matches the ~70 estimate) directly in `apps/api/src/app.ts`; same
call as all 11 prior passes (live auth-gate code, zero compile/test capability
in this sandbox). **The verify-by-PR-CI bet on the composite action paid
off immediately:** the first push (`8ad5db7`, PR #350) failed the
`firebase-emulator` job — "Can't find 'action.yml' ... Did you forget to run
actions/checkout" — because a local action (`uses: ./path`) can't be
resolved until the repo containing it is already checked out in that job;
the composite action's own internal `checkout` step ran too late to help its
caller. Fixed (`addbdbe`) by moving `checkout` back into each job and having
the composite action own only `setup-java`; re-verified job-by-job on the
second run (all 7 `ci.yml` jobs, incl. `firebase-emulator`, completed clean).
Left as a live example of why this queue insists on real verification before
calling anything "safe," even changes judged safe going in.

**2026-07-20 repo-maintenance pass (14th)** — the first to land `apps/api`/
`apps/cli` **code** changes, not just docs. Confirmed CI green on `main` at
`0df8f76` (13th pass) before starting. This session has PR+CI access (unlike
the prior 13 passes' docs-only sandbox), which closes the "needs a build-
capable session" blocker that had deferred `backlog/next.md`'s well-vetted
`apps/api`/`apps/cli` dedup queue since 2026-07-01: verified via **this PR's
own CI run** instead of a local `tsc`/`gradle`. Landed `requireCred`,
`resolveVisibleHub`, and `hubWriteGateResponse` in `apps/api/src/app.ts`
(re-reading the source directly found the prior "11× auth-boilerplate" count
included 4 sites with a genuinely different shape — corrected to the 7 that
are actually byte-identical); collapsed the CLI's four `*Status` HTTP
functions into one + extracted `authedPut` + deduped a `Triple` auth-
resolution snippet in `apps/cli/.../Main.kt`; moved a hand-written test out
of the codegen `generated/` dir it was mistakenly sitting in. All three
independently confirmed behavior-preserving by two audit agents (CLI `--help`
/ skill-doc cross-check; README/architecture/CHANGELOG cross-check — no
CHANGELOG entry needed, internal-only). Three doc-drift fixes from a fresh
agentic-docs audit: a second stale copy of the "iOS host app is the blocker"
claim the 13th pass had already corrected once (`backlog/next.md` +
`processes/mobile-release.md`'s own "Known follow-ups" bullet); `processes/
agent-dev-loop.md`'s `## API` line-count estimate (~20) never updated to
match CLAUDE.md's 13th-pass correction (~60); `backlog/now.md` (this file)
carrying a ~15-line duplicate of `operator-inbox.md`'s INB-33 narrative,
against this file's own "kept short on purpose" convention — trimmed to a
pointer. **CI hiccup + self-heal, live example #2:** the first push's CI
failed the "api bundle is up to date" gate (`apps/api/api/index.js`, the
committed Vercel function bundle, drifted from source after the `app.ts`
edit) — exactly the scenario `.github/workflows/rebuild-api-bundle.yml` was
built for (2026-07-07/09 incident, see `backlog/now-history.md`). Triggered
it against this branch; it rebuilt and pushed the fix in ~15s. **One CI flake,
confirmed not caused by this pass:** the "Client core + feed UI" job's golden-
snapshot gate mismatched 15 screenshots (`account-*`, `detail-*`,
`members-roster*`, `scan-denied*`) on the first run — none touched by this
pass's `apps/api`/`apps/cli`-only diff, and the same job was green on `main`
at `0df8f76` twelve hours earlier. `rerun_failed_jobs` came back 100% clean
(golden-dashboard step skipped — zero mismatches) on retry, confirming
environment nondeterminism rather than a real regression. **PR #352: all 7 CI
jobs green** (final head `2228ca4`). Values/privacy spot-check: no secrets, no
new data collection, no behavior change in either code commit.

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

## Operator actions pending

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
