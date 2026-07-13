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

- **✅ CI is GREEN on `main`** — re-confirmed live 2026-07-10 (latest run,
  #692, `success`). Was red 2026-07-05→07-07; PR #291 added
  `.github/workflows/rebuild-api-bundle.yml` (`workflow_dispatch`,
  `contents: write`) as a standing self-heal tool for the next time the
  committed API bundle drifts from source — see `backlog/now-history.md`
  (2026-07-07/07-09 entries) for the full incident + fix if you need it.
- **Quarterly:** re-check whether Google ships a *free, family-shared*
  Gemini Daily Brief variant (KS-6 / OQ-gemini-family). First check ~2026-09.
- **Next P0 viability review due 2026-07-18** (or +10 iterations).

## Current state (as of 2026-07-10)

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

**2026-07-13 repo-maintenance pass** (scheduled — the 7th in this series;
prior passes: `backlog/now-history.md`). Same no-npm/no-Gradle-registry-
egress sandbox as every prior pass (re-confirmed) — no *logic* changes to
`apps/api`/`apps/cli`/`apps/client`; the still-open `apps/api` code-dedup
queue stays deferred to a build-capable environment, but its counts were
refreshed (auth-guard duplication 9→**11** sites, hub-visibility duplication
"3+1"→**7** sites, `app.ts` ~1000→**1244** lines — see `backlog/next.md`).
**CI: green** (`ci.yml` #734 on `main`); one self-healed transient flake
noted (#725, a `sqldelight` Gradle-plugin resolution hiccup, resolved itself
2 runs later — no code issue, no action taken). **Found + fixed:** this
file's own "in review, not yet on `main`" claim for ADR 0056/0057 was stale
(both had already merged) and three shipped slices (ADR 0053 hub roles/
avatars, the ADR 0029 scoped-token extension, the analytics-reliability fix)
were missing from `CHANGELOG.md` — added above and there. `docs/
architecture.md` was missing ADR 0053 (per-hub roles — live in the API but
undocumented) and ADR 0057/`debugdrawer-swip` — added (component table, data
flow, ADR list). **Trimmed `adr/decisions-index.md`** from ~6,970 words to a
short one-line-per-ADR table (full rationale/composes/rejected-alternatives
still live untouched in each `adr/NNNN-*.md` file this pass never edited) —
the single largest agentic-context-usage win found: every loop/planning
session pays this file's read cost per `CLAUDE.md`'s start-of-session
routine, and it had grown into a near-duplicate of the standalone ADR files
rather than an index of them. `CLAUDE.md`/`AGENTS.md`/`processes/agent-
routing.md`/`processes/agent-dev-loop.md` re-checked fresh: still lean, no
new growth, no changes needed. **CLI/skill-doc gaps found + fixed:** (1) the
ADR 0053 per-hub **role** gate (`write-guard.ts`) is a second, independent
403 source beyond ADR 0029 scope that `references/cli.md` didn't explain —
added; (2) `place_ref`/foreground-vs-background trigger posture (ADR 0049)
was undocumented in `content-model.md`/`SKILL.md` — an agent following the
docs alone would always author foreground-only triggers without knowing it —
added; (3) `templates/README.md` didn't mention the visibility/audience
`--type` gotcha the other 3 docs already cover — added a one-line pointer.
**Not fixed this pass (flagged, not mechanical enough for a no-compile
sandbox):** ADR 0053's per-hub role gate has no CLI-only remedy path and no
`--help`-per-verb exit-0 behavior — both are small *behavior* changes to
`Main.kt`/`app.ts`, deferred to a build-capable pass. **Values/privacy
spot-check:** clean — a dedicated check of the 6 newest SWIP/logging commits
against ADR 0055-0057 found no guardrail #3/#4 violations (debug-only,
count-only, zero release footprint, no secrets). It did surface a real
governance-process gap: ADR 0054/0055/0056/0057 are all still headed
"Proposed (accept on merge)" despite being merged and live — filed as
**INB-32** (operator-inbox) rather than agent-flipped, since ADR acceptance
is never agent-decided.

## Design-first gate (ADR 0008) — status

The **feed-only** M0 slice was built **build-first** (operator-directed) from the
initial Now mockups in `designs/`. ADR 0008 **still governs unbuilt surfaces**:
the **M1 trigger surface** needs its hi-fi mockups (trigger v2 = INB-13) **before**
it's built. **Event Hubs render: design gate CLEARED (INB-22, 2026-06-24)** — the
Hubs phone surface (INB-15/16) + content adaptive two-pane (INB-20) + the ADR-0030
visibility delta (`Hubs-Visibility.dc.html`, signed off) are all in; the content-
API enforcement is built (PRs #34/#35). Hub render is build-ready.

## Operator actions pending

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
