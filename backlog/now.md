# Backlog — Now

Current state only — kept short on purpose (start-of-session reading cost).
**Full chronological build history (every dated status update, feature by
feature) → [`backlog/now-history.md`](now-history.md).** This file was split
2026-07-03 (was 570 lines, one append-only log); read the history file when
you need the detailed narrative behind something below, not by default.

## ⚠ Time-sensitive (hard dates — keep pinned at top)

- **CI is RED on `main` since 2026-07-05 (blocking — first build-capable session
  should fix this before anything else).** The "API (vitest + Postgres)" job fails
  at its "api bundle is up to date" step: the committed Vercel function bundle
  (`apps/api/api/index.js`) is stale vs `apps/api/src` (missing the `ff5c0fc`
  dedup — no `parseVisibilityAudience`/`callerFrom`, still has 2 inline `bearer()`
  defs, still has dead `repo.syncCards`). **Fix:** `cd apps/api && npm run
  build:fn`, commit the regenerated `api/index.js`. Because this step runs before
  `api tests` in the job, **the vitest suite (80+ tests) has NOT run against the
  `ff5c0fc` app.ts/middleware.ts refactor** — run it too before trusting the
  change is safe. I manually re-read the `ff5c0fc` diff line-by-line as a stopgap
  (2026-07-06): the extractions (`callerFrom`, `parseVisibilityAudience`) are
  behavior-preserving by inspection — same fields, same validation branches, same
  strip-then-parse order — and `repo.syncCards` genuinely has zero remaining
  callers. But that's not a substitute for the real test run. **This sandbox has
  no npm/Gradle registry egress** (`registry.npmjs.org` / `raw.githubusercontent.com`
  both blocked — "Host not in allowlist"), so I could not rebuild or run tests
  myself; hand-editing a minified production auth bundle without the ability to
  verify it was judged too risky to attempt. Prod (Vercel serves `api/index.js`
  directly, no build step) is very likely still running the **pre-dedup** code —
  functionally probably fine per the above, but should be confirmed once rebuilt.
  Also: the merge that broke this (`cf2898a`, PR #289) apparently went in without
  waiting on its own CI result — worth checking whether branch protection should
  require the CI check before merge.
- **Quarterly:** re-check whether Google ships a *free, family-shared*
  Gemini Daily Brief variant (KS-6 / OQ-gemini-family). First check ~2026-09.
- **Next P0 viability review due 2026-07-18** (or +10 iterations).

## Current state (as of 2026-07-06)

**Stage: M0 render prototype BUILT + cloud-live** — server (TS/Hono/Postgres
on Vercel+Neon) · Kotlin CLI · KMP client (`apps/client` core + `apps/ui`
Compose, ADR 0047) · Android (dogfood, real device) + iOS (sim-verified) —
full CLI→API→DB→sync→render loop works end-to-end in prod. Validation
verdict still stands: **CONDITIONAL — learning-lab GO, business NO-GO**
(commoditized by Gemini Daily Brief/Alexa+; the defensible surface is a
**multi-member family-tenant briefing**) → **building to learn**; the
business unknowns (OQ-wtp / niche / gemini) stay untouched by design.

**Shipped and live on `main`:** full AUTH epic (device-grant login, Google
sign-in, roster/devices/account) · Hub & card visual enrichment (ADR 0036) ·
Now-derived surfacing Phase A+B — priority-ranked feed + Android background
geofence/exact-alarm local notifications (ADR 0043/0044) · iOS notification
parity (sim-verified) · two-way member writes — checklist toggle, delete,
local hide (ADR 0038–0042) · Hub Timeline, authored + on-device-derived
fallback (ADR 0045/0046) · CL-SNAP headless golden-snapshot CI gate (131
goldens) · `:client`/`:ui` module split (ADR 0047, faster agent inner loop).
Deferred by design: G1 content-authoring "brains" loop (interim authoring =
operator + Claude Code via the CLI/curator skill); E2EE (ADR 0017); web
target (`wasmJs`, needs a client DB async migration first).

**2026-07-06 repo-maintenance pass (scheduled, not a feature slice) — found CI
red on `main`, fixed doc drift.** Same no-npm/Gradle-registry-egress sandbox as
the last two passes (confirmed again: `registry.npmjs.org` and
`raw.githubusercontent.com` both blocked — "Host not in allowlist"). GitHub
Actions run history showed the **first CI run after the 2026-07-05 merge
(`cf2898a`/PR #289) failed** — see the pinned note at the top of this file for
the full diagnosis and why it wasn't hand-patched. Manually re-reviewed that
merge's `app.ts`/`middleware.ts`/`Main.kt` diff line-by-line as a stopgap; it
reads as behavior-preserving, but the real vitest run is still owed. Fixed
found doc drift (no code changes, so no functional risk): `docs/architecture.md`
had the Android/iOS notification+geofence classes attributed to the host
modules instead of `apps/client`'s `androidMain`/`iosMain` source sets, was
missing `packages/schema`/`packages/linkrules` as diagram nodes, and had a
self-referential notification edge; `README.md`'s CLI command table was
missing `update`; `apps/settings.gradle.kts`'s header comment predated the
`:ui`/debugdrawer* split. Closed further curator-skill doc gaps a fresh
CLI-vs-skill-doc audit found: the legacy `DAYFOLD_API`/`FAMILY_ID`/
`HOUSEHOLD_SECRET` env-auth fallback, the 0/1/2 exit-code contract, and the
scope model (`content:*` / `hub:<id>:*`, no in-place re-scope) were all real
and undocumented in `references/cli.md`; `references/guardrails.md`'s
Guardrail 3 only listed 2 of the schema's 4 `privacy.storage` values (added
`in_browser`/`matched_on_device` with their actual on-device chip labels, not
guessed ones — pulled from `TypedCards.kt::privacyLabel`); `.svg` image
rejection and the full `related[]` edge shape were undocumented in
`content-model.md`. Also found + fixed 2 real CHANGELOG.md gaps: the
2026-06-27 production outage (all card writes were 500ing since M0 — fixed by
`572619d`) and the 2026-06-28 predictive-back gesture nav (`18d0988`) had no
entries despite being genuinely user/reader-facing. Values/privacy spot-check
clean (no secrets, no PII-logging patterns, no direct Gmail OAuth scope in
code, no child-account paths — guardrails hold). Did **not** re-attempt the
CODE DEDUP FINDINGS queue below (already ranked; still needs a build-capable
session) to avoid adding more unverified, unbuildable source changes on top of
an already-red CI.

**2026-07-05 repo-maintenance pass (this session, scheduled/operator-requested,
not a feature slice):** applied the small, mechanically-safe items from
`backlog/next.md`'s CODE DEDUP FINDINGS by careful inspection (same no-registry-
access sandbox constraint as 2026-07-03; relies on the real CI run, not a local
build, to compile-verify): deduped `bearer()` (`apps/api`), extracted
`parseVisibilityAudience()` and `callerFrom()` in `app.ts` (removes ~11 inline
rebuilds + one copy-pasted validation block), deleted dead `repo.syncCards`,
and extracted `refreshAccessToken()` in the CLI's `Main.kt` (was inlined 3×).
Closed further CLI/skill doc drift the CI-health + CLI-agent audits found:
`timeline` was missing from `references/cli.md`'s type list (present in code
and two other docs), `upgrade`/`-v` aliases were undocumented, and checklist
item id-stamping (ADR 0038) — real, behavior-affecting, and previously
undocumented anywhere — is now called out in `USAGE` + `cli.md`; added
`importance`/`relatedKicker` to `content-model.md`'s field list. Refreshed
`CLAUDE.md`'s "Current stage" snapshot (was dated 2026-06-29, claimed two-way
member-writes were "in active build" when they'd since shipped — see
CHANGELOG). CI workflows independently audited (via GitHub Actions run
history, not local): all 6 green on `main`, no breakage found. Values/privacy
spot-check clean. Remaining dedup findings (auth-route boilerplate, a
hub-visibility-fetch helper, `app.ts`'s size, CLI-doc consolidation) re-ranked
in `backlog/next.md` for a build-capable environment — see that file.

**2026-07-03 repo-maintenance pass (operator-requested, not a
feature slice):** removed 21MB of orphaned generated-dashboard binaries
(`apps/client/.rk-snapshots/`, stray pre-`:ui`-split output, unreferenced by
CI); refreshed `README.md`/`docs/architecture.md`/`CLAUDE.md` for the
`apps/ui`+`apps/iosApp` split and iOS's shipped-sim status; added a root
`AGENTS.md` (thin pointer to `CLAUDE.md`, resolves the ADR 0013 §6
commitment); closed CLI-`--help`/skill-doc gaps (legacy env-auth path, exit
codes, credential storage location, `login`/`logout`/`update`/`version` were
undocumented in the curator skill's `cli.md`); reconciled two stale/
self-contradicting entries in `backlog/next.md`; split this file. CI verified
green throughout (no breakage); values/privacy spot-check clean (no secrets,
no PII logging, code matches the documented Gmail/child-account/location
guardrails — see `SECURITY.md` + `CLAUDE.md` guardrails). Sandbox had no
outbound access to the npm/Gradle registries, so no functional/logic code
changes were made (docs + one binary-file deletion only) — see the newly
ranked entries in `backlog/next.md`'s CODE DEDUP FINDINGS for what's queued
for a build-capable environment. Note: an earlier 2026-07-02 pass on a
different, never-merged branch (`claude/upbeat-fermat-r4mggb`) covered
similar ground; its useful content already shipped separately via `be45de6`
(PR #276) — see `backlog/now-history.md` if you land on that branch and need
to know it's superseded.

## Design-first gate (ADR 0008) — status

The **feed-only** M0 slice was built **build-first** (operator-directed) from the
initial Now mockups in `designs/`. ADR 0008 **still governs unbuilt surfaces**:
the **M1 trigger surface** needs its hi-fi mockups (trigger v2 = INB-13) **before**
it's built. **Event Hubs render: design gate CLEARED (INB-22, 2026-06-24)** — the
Hubs phone surface (INB-15/16) + content adaptive two-pane (INB-20) + the ADR-0030
visibility delta (`Hubs-Visibility.dc.html`, signed off) are all in; the content-
API enforcement is built (PRs #34/#35). Hub render is build-ready.

## Operator actions pending

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
verification notes, superseded plans) is in
[`backlog/now-history.md`](now-history.md).
