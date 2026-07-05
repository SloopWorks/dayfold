# Backlog — Now

Current state only — kept short on purpose (start-of-session reading cost).
**Full chronological build history (every dated status update, feature by
feature) → [`backlog/now-history.md`](now-history.md).** This file was split
2026-07-03 (was 570 lines, one append-only log); read the history file when
you need the detailed narrative behind something below, not by default.

## ⚠ Time-sensitive (hard dates — keep pinned at top)

- **Quarterly:** re-check whether Google ships a *free, family-shared*
  Gemini Daily Brief variant (KS-6 / OQ-gemini-family). First check ~2026-09.
- **Next P0 viability review due 2026-07-18** (or +10 iterations).

## Current state (as of 2026-07-03)

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
