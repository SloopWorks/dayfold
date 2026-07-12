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

**In review, not yet on `main` (2026-07-12):** leveled/scrubbed on-device
logging (`Log` front-door, SWIP `swip-logging` bound debug-only, ADR 0056)
and a SWIP debug-drawer **inspector panel** — live analytics timeline,
mask-by-default with `FLAG_SECURE` reveal isolation, debug-only, zero
release footprint (ADR 0057, `feat/swip-inspector-plugin`); regression gate
(`:debugdrawer-swip:desktopTest`, `:androidApp:compile{Debug,Release}Kotlin`,
`:swip-wiring:desktopTest`) green. Mandatory on-device smoke test still
pending (operator, physical device).

**2026-07-10 repo-maintenance pass (scheduled, not a feature slice) — one
real CLI/skill-doc bug found + fixed, one architecture-doc gap closed,
`now.md` itself pruned.** Same no-npm/no-Gradle-registry-egress sandbox as
every prior pass (re-confirmed: `registry.npmjs.org` and
`repo.maven.apache.org` both 403 via the proxy) — so, consistent with every
pass since 07-03, no *logic* changes were made to `apps/api`/`apps/cli` (no
way to compile-verify them here); the **still-open `apps/api` code-dedup
queue** (`requireSession` helper, `hubs.getVisibleHub`, `app.ts`
route-splitting — see `backlog/next.md`) stays deferred to a build-capable
environment for the same reason as the last 5 passes. **CI: confirmed GREEN
live via the GitHub Actions API** (latest run on `main`, #692, `success`;
spot-checked the last 15 runs across `ci.yml`/`release-android.yml`/
`secret-scan.yml`, all green) — nothing to fix. Added one operator action
that had fallen through the cracks: **enable branch protection on `main`
requiring the CI check before merge** (the 07-05 outage landed without
waiting on its own CI result; see Operator actions below).
**Found + fixed a real bug (agent-blocking, not just drift):** a spot-check
diffing `apps/cli`'s `Main.kt` `USAGE`, `.claude/skills/dayfold-curator/`
(`references/cli.md`, `references/content-model.md`) against the generated
schema found that a **card's** `visibility`/`audience` (ADR 0030/0038) — real,
server-accepted fields, documented in all three places as freely settable —
are **not** part of the generated `BriefingCard` schema (they're access
control, read off the raw request body server-side, not content). The CLI's
opt-in `--type` local pre-check strict-decodes a card against that generated
type, so an agent that (a) follows the docs' own recommendation to always use
`--type`, and (b) authors a `restricted`/`audience`-scoped card, gets a local
"unknown field" rejection instead of a working push — the exact "docs read as
correct, following them literally breaks" class of bug the 07-06/07-07 passes
also found and fixed. Documented the real behavior (push a scoped card
*without* `--type`) in all three places — `USAGE`, `cli.md`, `content-model.md`
— including a one-line, string-literal-only `Main.kt` change (no logic
touched). Hub-tree pushes are unaffected (already lenient-structural).
**Found + fixed a real `docs/architecture.md` gap:** no commits touched
`apps/api`/`packages/schema` since the 07-09 pass (verified via `git log`),
but the **DB-first cold-start route gate (ADR 0052)** merged to `main` *after*
that pass's cutoff (2026-07-10T00:47 UTC) and was a real data-flow change (a
new local-only `membership` cache table + a background auth-reconciliation
path) the Data-flow section had no mention of. Added a numbered data-flow
step + updated the Client-core component row + ADR cross-reference list;
bumped the file's "as of" date. `README.md` and `CHANGELOG.md` were already
current (shipping commits update `CHANGELOG.md` themselves; `README.md`'s
repo table/screenshots didn't need a change). **Simplified this file:**
`backlog/now.md` had grown to 283 lines by re-stacking every repo-maintenance
pass's full paragraph under one old header instead of pruning to history,
working against its own stated "kept short on purpose" design
(self-inflicted context-usage cost, on-topic for this pass's "optimize for
agentic development" ask) — moved the 2026-07-03/05/06/07 maintenance-pass
paragraphs into `backlog/now-history.md` (verbatim, nothing lost) and
collapsed the two stacked "Current state" headers into one (now.md:
283→~140 lines). Reviewed `CLAUDE.md`/`AGENTS.md`/`processes/agent-routing.md`/
`processes/agent-dev-loop.md` again with fresh eyes for agentic-context-usage
opportunities beyond the now.md fix above: still lean (each already scopes
itself — e.g. `agent-dev-loop.md`'s Compose/KMP section is skippable for
CLI-/API-only work, `AGENTS.md` is a 27-line pointer) — no further changes
made. Remaining CLI/skill-doc dedup (hub-timeline table, block-payload alias
column, checklist id-stamping note repeated across `SKILL.md`/`references/
cli.md`/`references/content-model.md`/`templates/README.md`/`USAGE`) left
as-is per 07-09's judgment — each copy is short and partly intentional for
`templates/README.md`'s standalone readability. Values/privacy spot-check
clean: this pass's diff is docs + one CLI help-text string (`docs/
architecture.md`, `backlog/now.md`, `backlog/now-history.md`, `.claude/
skills/dayfold-curator/references/{cli,content-model}.md`, `apps/cli/.../
Main.kt` USAGE only) — no secrets, no PII, no child-account or
restricted-scope-Gmail surface touched.

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
