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
- **Next P0 viability review due 2026-07-18** (or +10 iterations).

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
one runtime graph and expose only stable store/command/platform wrappers to
Compose. The root whole-state subscription and callback wall are gone; active
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

**2026-07-16 repo-maintenance pass** (scheduled — the 10th in this series;
prior passes: `backlog/now-history.md`). Same no-npm/no-Gradle-registry-egress
sandbox as every prior pass (re-confirmed: `npm ping` 403s through the proxy,
`./gradlew --version` can't tunnel to `services.gradle.org`) — no *logic*
changes to `apps/api`/`apps/cli`/`apps/client`; all findings below are
docs/backlog/CI-YAML/ADR-status only. Broader scope than usual this pass (the
operator asked for simplification + agentic-context optimization + skill/doc
completeness + diagrams + changelog + CI + a values pass, not just a spot
audit) — four parallel research agents covered apps/api+cli+skill, the
agent-facing docs, README/architecture/CHANGELOG, and CI, then findings were
applied directly. **Biggest structural change: `backlog/operator-inbox.md`
split** (540 → 142 lines; new `operator-inbox-history.md`, 444 lines) — same
now.md/next.md precedent, applied here for the first time. Of 43 `INB-*`
entries only 7 were genuinely open or had an unconfirmed operator-only
remainder (INB-32/30/27/23/19/15/3); the other 36 were fully resolved and
moved to history verbatim, cutting the mandatory full-routine inbox read by
~74%. **Two other stale-doc bugs found and fixed while classifying those
entries:** `backlog/now.md`'s own "Operator actions pending" list carried a
**stale INB-13** entry (asking to hand the trigger-design v2 fix-list to
Claude Design) that was actually closed 2026-07-01 (PR #260) — removed, and
the "Design-first gate" section's parallel stale claim about the M1 trigger
surface fixed too; both had survived at least two prior maintenance passes
uncaught. **ADR status-accuracy gap (INB-32 pattern) now covers two more
ADRs:** 0059 (API error pillar, PR #336) and 0060 (client crash reporting,
PR #339) are both merged and live but still text-labeled "Proposed ... accept
on merge" — folded into INB-32 rather than opening a new item; ADR 0059's
"blocked on publication" sentence (now false — it shipped) was corrected as a
wording fix only (the status flip itself stays operator-gated). **CODE DEDUP
FINDINGS counts corrected** (`backlog/next.md`): the hub-visibility-fetch
duplication is 8×, not 7× (missed `DELETE .../blocks/:id`); the ad-hoc
validation-error-shape count is ~23 sites, not ~9 — both re-verified with
exact current line numbers. No fixes applied to the queue itself (still
behavior-touching, still needs a real `./gradlew`/`npm test` run this sandbox
can't provide) — the CLI's 3 small dedup items were independently re-assessed
as the safest in the queue (single-file, small enumerable call-site sets) but
still staged as "verify with a build," not applied blind. **docs/architecture.md
gap closed:** ADR 0059/0060 (API + debug-client error reporting → Sentry +
PostHog) were entirely absent from the diagram/components/deploy sections
despite the API half running in production — added (diagram nodes + arrows,
2 Components rows, a data-flow step, Deploy env-var note). **CHANGELOG.md gap
closed:** two shipped, changelog-worthy items had no entry — ADR 0054 (SWIP
bug reporter, PR #320, 2026-07-10) and ADR 0058 (client runtime hardening incl.
two real production deadlock fixes, PR #338, 2026-07-15) — both added in their
chronological slot. **CI: confirmed green** (`ci.yml` run #29475848812 on
`main`, 2026-07-16T06:08:37Z, all 7 jobs pass); the 07-15 pass's workflow
hardening (permissions/concurrency/timeout-minutes, the `debugdrawer-swip`
test job) verified still in place, nothing new broken. **One flake, second
occurrence, not a new defect:** `SessionBoundaryTest` (a client concurrency/
race test, part of the still-unchecked TASK-CLIENT-RUNTIME-HARDENING "PR 2"
race-test items) failed once on run #29452429482 (2026-07-15T21:34:45Z, the
ADR-0059 API-errors commit) and self-healed on the very next push a minute
later — same pattern as the 07-12 flake. Worth watching if it recurs a third
time. **CLAUDE.md "Current stage" section was 12 days stale** (dated
2026-07-04, silent on ADRs 0055–0060) — updated to 2026-07-16 with a one-
sentence summary of the SWIP/error-reporting/runtime-hardening work.
**Flagged but not touched** (bigger restructure or needs operator judgment):
`CLAUDE.md`'s hard-guardrail text is independently restated (not just
pointed-to) in `processes/agent-routing.md` and `processes/build-loop-prompt.md`
— a future edit to one could drift from the others; picking one canonical
location is an operator call given each file is written for at-point-of-use
visibility, not a mechanical fix. **Skills/CLI --help re-verified complete:**
every CLI command and flag in `Main.kt`'s dispatch/flag-parsing is documented
in the in-source `USAGE` string (cross-checked directly against source, not
just the docs); `SKILL.md`/`references/cli.md` confirmed accurate by a
research agent (no undocumented commands/flags, exit-code table still
correct). **README/architecture spot-check:** README screenshots still
resolve to real files, no stale claims found. **Values/privacy spot-check:**
clean — every change this pass was docs/backlog/ADR-status-text/CI-YAML; no
product code, no data-handling change, no scope/pricing/legal decision made
(the two ADR-status questions were added to the existing operator-gated
INB-32, not decided).

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
