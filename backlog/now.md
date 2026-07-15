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

- **✅ CI is GREEN on `main`** — re-confirmed live 2026-07-14 (latest run,
  #29286455499 `CI`, `success`, 2026-07-13T21:28:13Z; one older transient
  flake at 2026-07-12T18:34:49Z self-healed on the very next push — no
  action needed). Was red 2026-07-05→07-07; PR #291 added
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

**2026-07-15 repo-maintenance pass** (scheduled — the 9th in this series;
prior passes: `backlog/now-history.md`). Same no-npm/no-Gradle-registry-
egress sandbox as every prior pass (re-confirmed) — no *logic* changes to
`apps/api`/`apps/cli`/`apps/client` (both still deferred to a build-capable
environment). Only one commit had landed since the 07-14 pass (that pass's
own commit, `f671d0a`), so this pass deliberately did NOT re-run the same
ground three prior passes already covered (docs/CLAUDE.md/CLI-doc audits) —
instead it went a layer deeper into areas those passes' own scope didn't
reach. **CI workflow hardening (new — first pass to read the `.github/
workflows/*.yml` files themselves rather than just checking run status):**
`ci.yml` had no `permissions:` block (default token scope, not least-
privilege) and no `concurrency` group (rapid PR pushes ran full heavy Gradle
jobs to completion instead of cancelling superseded ones) — added both, plus
`timeout-minutes` on every job (none had one; GitHub's default is 360m).
`migrate.yml` — the manual `workflow_dispatch` that runs `db:migrate apply`/
`backfill` directly against **prod** — had no `concurrency` group, so two
overlapping manual triggers could race a real migration against the
production DB; added `concurrency: {group: migrate-production,
cancel-in-progress: false}` (the one genuinely prod-safety-relevant fix this
pass made). `rebuild-api-bundle.yml` got a concurrency group + timeout too;
`release-android.yml`/`release-cli.yml`/`release-cli-edge.yml`/
`secret-scan.yml` already had correct permissions/concurrency (per their own
inline security-posture comments) and only needed `timeout-minutes` added.
Also found + fixed: the `debugdrawer` CI job never ran
`:debugdrawer-swip:desktopTest` even though that module (ADR 0057 inspector)
has a real test suite and ships in the Android debug build — added it to the
job. **CLI/skill-doc gap (narrow, missed by the 07-14 pass):**
`references/cli.md`'s exit-code enumeration listed exit `2` as "bad flags, an
unreadable input file, or a keychain-less `login`" but silently dropped the
**missing-env** case the in-source `USAGE` string documents (a reachable path
— `DAYFOLD_API` set without `FAMILY_ID`/`HOUSEHOLD_SECRET` falls through to
the legacy env path and exits 2) — added, with the fix ("run `dayfold
login`") an agent following only the doc wouldn't otherwise infer.
**New apps/api/apps/cli dedup items found** (logged into `backlog/next.md`'s
existing CODE DEDUP FINDINGS queue, same unverified/no-build-toolchain
caveat as the queue's existing entries — not applied): four small Kotlin
`Main.kt` duplications (near-identical `*Status` HTTP helpers, a missing
`authedPut` retry-wrapper, one copy-pasted credential-resolution `Triple`)
and one `apps/api` `app.ts` inconsistency (~9 sites use an ad-hoc validation-
error shape instead of the file's own RFC 9457 `problem()` helper).
**Verified clean, no action needed:** `README.md` screenshot references
still resolve to real files; `CLAUDE.md` (177 lines) / `AGENTS.md` (26
lines) are already lean from the 07-13 context-trim pass, no further cut
warranted; `CHANGELOG.md` is current through 2026-07-12 (this pass's changes
are CI-infra + docs, internal-only, correctly excluded per the changelog's
own "product/API/feature changes" scope). **Values/privacy spot-check:**
clean — this pass touched only CI workflow YAML and two doc/backlog files,
no product code, no data-handling change.

## Design-first gate (ADR 0008) — status

The **feed-only** M0 slice was built **build-first** (operator-directed) from the
initial Now mockups in `designs/`. ADR 0008 **still governs unbuilt surfaces**:
the **M1 trigger surface** needs its hi-fi mockups (trigger v2 = INB-13) **before**
it's built. **Event Hubs render: design gate CLEARED (INB-22, 2026-06-24)** — the
Hubs phone surface (INB-15/16) + content adaptive two-pane (INB-20) + the ADR-0030
visibility delta (`Hubs-Visibility.dc.html`, signed off) are all in; the content-
API enforcement is built (PRs #34/#35). Hub render is build-ready.

## Operator actions pending

- [ ] **API error reporting (ADR 0059) — PR #336, unblocked; set Vercel env before deploy.**
  `feat/api-swip-errors` wires `apps/api` to the SWIP error pillar (PostHog + Sentry,
  joined on `swip.fingerprint`; flush awaited in a Hono `finally` because Vercel freezes
  the container at response time). Verified live against both real vendors. The SWIP npm
  packages are published; the branch pins `swip-js 0.5.1` / `swip-sentry 0.2.3` /
  `swip-schema-dayfold 1.0.3` (the republished set with the `scrubField` fix, SWIP #76).
  Before the next prod deploy: add `SENTRY_NODE_EU_DSN` (the API's project — *not* the mobile
  app's), `SENTRY_RELEASE`, `POSTHOG_PROJECT_KEY`, `POSTHOG_HOST` to Vercel prod
  (`processes/deploy-m0.md` §2), and the `SLOOPWORKS_PACKAGES_TOKEN` repo secret must have
  `read:packages` (it already exists for the Gradle lanes).
- [ ] **Accept ADR 0060** (client crash/error reporting — debug-only Android,
  SWIP error pillar → Sentry KMP project + PostHog). Agent-drafted 2026-07-15;
  Tasks 1–4 wired (error runtime, Sentry crash reporter, `Application` hoist,
  debug trigger).
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
- [ ] **INB-13** hand the trigger-design v2 fix-list (`designs/DESIGN-BRIEF-
  triggers.md §6b`) back to Claude Design before the M1 trigger surface.
- [ ] Counsel confirm for ADR 0005 (14+) — only if/when pursuing teen accounts.
- [ ] **INB-19 remainder** (operator-only): publish `redux-kotlin-snapshot` to
  Maven Central + fix `reduxkotlin/homebrew-tap` symlink (keg `bin/` empty →
  binary at `libexec/Contents/MacOS/rk`). Unblocks the `:client:snapshotUi`
  golden harness → prereq for CL-NAV/CL-10.

Full narrative for all of the above (build order, TDD slices, on-device
verification notes, superseded plans, and older repo-maintenance passes) is in
[`backlog/now-history.md`](now-history.md).
