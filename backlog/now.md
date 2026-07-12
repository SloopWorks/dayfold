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

- **✅ CI is GREEN on `main`** — re-confirmed live 2026-07-12 (latest `ci.yml`
  run on head `581fbdb`, `success`; all other workflows green or correctly
  idle for their trigger). Was red 2026-07-05→07-07; PR #291 added
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
status bar (#315, 2026-07-09)**.
Deferred by design: G1 content-authoring "brains" loop (interim authoring =
operator + Claude Code via the CLI/curator skill); E2EE (ADR 0017); web
target (`wasmJs`, needs a client DB async migration first).

**2026-07-12 repo-maintenance pass (scheduled, broader mandate — simplify,
optimize-for-agents, doc/CI/values review) — one real `docs/architecture.md`
gap closed, `backlog/next.md` split for context-cost the same way `now.md`
was, one missing `CHANGELOG.md` entry added.** Same no-npm/no-Gradle-registry-
egress sandbox as every prior pass (re-confirmed again: `registry.npmjs.org`
403, `repo.maven.apache.org` CONNECT-tunnel 403) — so, consistent with every
pass since 07-03, **no logic changes were made to `apps/api`/`apps/cli`**; the
still-open `apps/api` code-dedup queue (`requireSession` helper,
`hubs.getVisibleHub`, `app.ts` route-splitting — `backlog/next.md`) stays
deferred to a build-capable environment. **CI: confirmed GREEN** (GitHub
Actions API: latest `ci.yml` run on `main` success, head `581fbdb`; spot-
checked `release-android.yml`/`migrate.yml`/`rebuild-api-bundle.yml`/
`release-cli-edge.yml`/`secret-scan.yml` — all green or correctly no-run for
their trigger; `release-cli.yml` has zero runs because no `cli-v*` tag has
been cut yet, expected per ADR 0031's still-open license gate) — nothing to
fix. **Found + fixed a real `docs/architecture.md` gap:** the latest commit
(581fbdb, PR #325, scoped CLI/device tokens per ADR 0029 — an owner can now
grant a device/CLI login "Full access" or scope it to specific hubs) shipped
2026-07-11, after the doc's "as of 2026-07-10" cutoff, and the doc's Auth
section still described device-grant as always-blanket; also missing
`credential_grants` from the DB component row and the `requireScope` central
gate. Added a scoped-grants paragraph, the missing table row, and (separately,
pre-existing gap) an `apps/swip-wiring` component row + diagram node — that
debug-only bug-reporter module (ADR 0054, PRs #320/#321/#323) had no
architecture-doc mention at all. Bumped the "as of" date. **Verified CLI-help
+ skill docs are NOT stale for the same PR** (dispatched a dedicated audit):
`8f30017` (the *previous* pass, same day as #325) had already added the
scope model to `references/cli.md` and `whoami`'s USAGE text pre-emptively —
confirmed against the shipped API/scope.ts behavior, no gap found; the CLI
has no in-place re-scope path (logout/login only) and that's documented
accurately. **Added the missing `CHANGELOG.md` entry** for PR #325 (a real
API-surface/product-behavior change per this repo's own changelog rule —
had no entry). **Simplified `backlog/next.md`** (951→~715 lines, mirroring
the `now.md`/`now-history.md` split): moved the fully-shipped CONTENT LIBRARY
epic (CL-0…CL-9, CL-PLAT, closed 2026-06-21) and the completed `:client`/`:ui`
module-split narrative to a new `backlog/next-history.md`, leaving short
pointers + the genuinely-still-open items (CL-10 blocked, the `:model`/`:data`
further split) in place; also deleted the "Applied 2026-07-05" code-dedup
sub-list per its own standing instruction ("verify CI landed green, then
delete" — now re-confirmed twice since). **Deliberately left the AUTH section
(S1–S6, ~215 lines) alone** despite it reading as largely stale against
`main` (several "OPEN PR"/"GATED" items it lists look shipped, going by
recent commit subjects like #316-#319) — reclassifying done-vs-open there
needs code verification this pass didn't have budget to do safely; flagged
in `next.md` itself for a follow-up pass. **CLI-flag/skill-doc completeness**
(item 3 of this pass's mandate) re-checked fresh: every `Main.kt` subcommand/
flag has a documented counterpart in `references/cli.md`/`templates/
README.md` — no gap found. **`README.md`/`AGENTS.md`/`CLAUDE.md`** spot-
checked: still current and lean (AGENTS.md 27 lines, README has screenshots +
an accurate repo table) — no changes needed. **Code simplification /
dedup of `apps/api`/`apps/cli` (item 1 of this pass's mandate) was NOT
attempted** for the same compile-verification reason as the last 6 passes —
see the still-open code-dedup queue in `backlog/next.md` for the specific,
already-catalogued opportunities a build-capable session should pick up.
Values/privacy spot-check clean: this pass's diff is docs-only (`docs/
architecture.md`, `CHANGELOG.md`, `backlog/now.md`, `backlog/now-history.md`,
`backlog/next.md`, `backlog/next-history.md`) — no secrets, no PII, no
child-account or restricted-scope-Gmail surface touched, no code logic
changed.

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
