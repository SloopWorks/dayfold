# Backlog — Now

Current state only — kept short on purpose (start-of-session reading cost).
**Full chronological build history (every dated status update, feature by
feature) → [`backlog/now-history.md`](now-history.md).** This file was split
2026-07-03 (was 570 lines, one append-only log); read the history file when
you need the detailed narrative behind something below, not by default.

## ⚠ Time-sensitive (hard dates — keep pinned at top)

- **✅ CI is GREEN on `main` again, as of 2026-07-07 (was red since 2026-07-05).**
  PR #291 added `.github/workflows/rebuild-api-bundle.yml` (`workflow_dispatch`,
  `contents: write`) — a job that runs the exact `npm run build:fn` used by the
  CI gate on GitHub's own runners (which have registry access unlike 3
  consecutive agent sandboxes) and commits the regenerated
  `apps/api/api/index.js` back to whichever branch/ref triggered it. Operator
  merged #291, then ran the workflow against `main` (commit `12547e8`). **One
  wrinkle found + worked around:** commits pushed by the default `GITHUB_TOKEN`
  don't trigger other `push`-based workflow runs (GitHub's anti-recursion
  guard), so `ci.yml` never auto-re-ran against `12547e8` — opened a throwaway
  empty-commit PR (#292, `verify-ci-post-bundle-fix` → `main`, closed unmerged)
  to force a real `pull_request`-triggered CI run. Confirmed: `codegen is up to
  date` ✅, `api bundle is up to date` ✅ (the step that had been failing),
  `api tests` ✅ — **the vitest suite finally ran against the `ff5c0fc`
  app.ts/middleware.ts refactor and passed**, closing the "never actually
  tested" gap all three prior maintenance passes flagged. `rebuild-api-bundle.yml`
  stays in the repo as a standing tool for the next time this bundle drifts.
  Still worth doing: the merge that broke this (`cf2898a`, PR #289) went in
  without waiting on its own CI result — branch protection requiring the CI
  check before merge would prevent a repeat.
- **Quarterly:** re-check whether Google ships a *free, family-shared*
  Gemini Daily Brief variant (KS-6 / OQ-gemini-family). First check ~2026-09.
- **Next P0 viability review due 2026-07-18** (or +10 iterations).

## Current state (as of 2026-07-09)

**2026-07-09 repo-maintenance pass (scheduled, not a feature slice) — CI/docs/
values audit, found everything healthy; applied one doc-dedup + doc-bug fix.**
Same no-npm/no-Gradle-registry-egress sandbox as every prior pass (re-confirmed:
`registry.npmjs.org` 403s, `repo.maven.apache.org` proxy-403s) — so, consistent
with 07-03 through 07-07, no functional API/CLI/client code changes were made
(no way to compile-verify them here). **CI: confirmed GREEN** — 30 most-recent
GitHub Actions runs on `main` (ci.yml, release-android.yml, rebuild-api-bundle.yml)
all `success`, nothing to fix. **Docs: confirmed current** — `docs/architecture.md`
(dated 07-06) and `README.md` still match the system (07-08's nav-transition/
feed-scroll work was UI-motion-only, no new components/data-flow); `CHANGELOG.md`
already had entries through the 07-08 shipped work (added by that work's own
commits) — nothing missing. **CLI `--help` (`USAGE` in `Main.kt`): reviewed,
already comprehensive** (all commands, exit codes, legacy auth, visual
enrichment, visibility) — no gap found (no CLI/API/schema files changed since
the 07-07 pass's fixes). **Applied the deferred "CLI/skill-docs duplication"
item from `backlog/next.md`'s CODE DEDUP FINDINGS:** trimmed
`apps/cli/templates/README.md`'s full restatements of the local-validator
mechanics, the email guardrail, and privacy chips down to pointers at the
canonical copies (`references/cli.md`, `references/guardrails.md`); moved the
"known codegen asymmetries" detail (kind/provenance.at required locally) into
`cli.md`'s Push section so it wasn't lost. **Found + fixed in passing (real bug,
not just drift): `templates/README.md`'s privacy-chips section only listed 2 of
the schema's 4 `privacy.storage` values** — stale relative to
`guardrails.md`'s canonical 4-value list; an agent authoring content from that
file alone would have missed `in_browser`/`matched_on_device`. Also clarified
two guardrails.md headings whose "(Guardrail N)" parentheticals referenced
CLAUDE.md's guardrail numbering, not the file's own — read as self-referential
and ambiguous; reworded to "(mirrors CLAUDE.md guardrail N)". Reviewed
`CLAUDE.md`/`AGENTS.md`/`processes/agent-routing.md` for agentic-context-usage
opportunities: already lean from prior passes (scoped `agent-dev-loop.md`,
thin `AGENTS.md` pointer, a 41-line routing table) — no changes made. Remaining
CLI/skill-doc dedup (hub-timeline table, block-payload alias column, checklist
id-stamping note) re-ranked in `backlog/next.md`, lower priority (each is
already short / partly intentional for `templates/README.md`'s standalone
readability). Did **not** re-attempt the `apps/api` code-dedup queue
(`requireSession` helper, `hubs.getVisibleHub`, `app.ts` route-splitting) —
still explicitly flagged in `backlog/next.md` as needing a real build to land
safely, unchanged since 07-05. Values/privacy spot-check clean (diff is
doc-only; no secrets, no PII, no child-account or restricted-scope-Gmail
surface touched).

## Current state (as of 2026-07-08)

**Stage: M0 render prototype BUILT + cloud-live** — server (TS/Hono/Postgres
on Vercel+Neon) · Kotlin CLI · KMP client (`apps/client` core + `apps/ui`
Compose, ADR 0047) · Android (dogfood, real device) + iOS (sim-verified) —
full CLI→API→DB→sync→render loop works end-to-end in prod. Validation
verdict still stands: **CONDITIONAL — learning-lab GO, business NO-GO**
(commoditized by Gemini Daily Brief/Alexa+; the defensible surface is a
**multi-member family-tenant briefing**) → **building to learn**; the
business unknowns (OQ-wtp / niche / gemini) stay untouched by design.

**Shipped and live on `main`:** full AUTH epic (device-grant login, Google
sign-in, roster/devices/account) · **owner invite-mint UI — QR + share-link,
outstanding/revoke, cross-platform QR via qrose (2026-07-07)** · Hub & card visual enrichment (ADR 0036) ·
Now-derived surfacing Phase A+B — priority-ranked feed + Android background
geofence/exact-alarm local notifications (ADR 0043/0044) · iOS notification
parity (sim-verified) · two-way member writes — checklist toggle, delete,
local hide (ADR 0038–0042) · Hub Timeline, authored + on-device-derived
fallback (ADR 0045/0046) · CL-SNAP headless golden-snapshot CI gate (131
goldens) · `:client`/`:ui` module split (ADR 0047, faster agent inner loop) ·
**card↔detail container-transform morph fix — the `SeekableTransitionState`
path silently dropped the `sharedBounds` morph (broken on
androidx.compose.animation 1.11.2/1.11.3/1.12.0-alpha03); switched both
feed↔detail + hub↔timeline to plain `AnimatedContent`, predictive-back now
commit-animated (ADR 0050, #307, 2026-07-08)** ·
**navigation transition system — every nav edge now animates by a central,
future-proof taxonomy (tab=shared-axis-X over a persistent bottom bar,
push=Z, modal=slide-up, wizard=X, gate=fade, hero=container transform);
exhaustive `routeSpec` makes an unclassified route a compile error;
reduced-motion aware (ADR 0051, #308, 2026-07-08)** ·
**Now-feed scroll preservation — returning from a card detail, and switching
Hubs↔Now / Now→Account→Now, keeps the feed scroll position (Compose
`AnimatedContent` has no `SaveableStateHolder`, so the `LazyListState` was
discarded on swap; hoisted to `FeedApp`, always composed) — feed AND Hubs list
(#309/#312/#313, 2026-07-09)** · **card→detail shared-element morph — accent tile, kicker,
title, and the (now same-teal) primary Open button travel into the detail
instead of cross-fading; content-equality-gated (`sharedTransitionKeys`) so it's
correct across all card types + self-scaling to future ones, degrading to
cross-fade where content differs (#310, 2026-07-09)**.
Deferred by design: G1 content-authoring "brains" loop (interim authoring =
operator + Claude Code via the CLI/curator skill); E2EE (ADR 0017); web
target (`wasmJs`, needs a client DB async migration first).

**2026-07-07 repo-maintenance pass (scheduled, not a feature slice) — added a
CI self-heal path, closed 3 real skill/CLI-doc bugs (not doc drift — actively
wrong instructions).** Same no-npm/Gradle-registry-egress sandbox as the prior
three passes (re-confirmed independently: `registry.npmjs.org` 403s here too).
Rather than re-attempt a source-code dedup pass I can't verify (CI is already
red at the bundle-check step regardless, so a new push still wouldn't get a
real test signal), added `.github/workflows/rebuild-api-bundle.yml` — a
`workflow_dispatch` job that runs `npm run build:fn` on GitHub's runners
(which have registry access unlike this sandbox) and commits the result back
to whichever branch triggered it. **Not yet exercised** — GitHub only
lists/dispatches `workflow_dispatch` workflows already on the default branch,
so it can't run until this PR merges; see the pinned CI note above for the
next step. Ran a targeted agent audit (not a full re-sweep — the 07-03/05/06
passes already covered CLI↔skill-doc alignment broadly) specifically diffing
CLI/skill docs against the generated schema + `app.ts`, and found 3 real bugs,
none touched by the earlier passes: (1) `references/content-model.md` and the
CLI's own `--help`/`USAGE` text both claimed a **card** `media` object carries
`heroUrl`/`heroFit` — false; per the generated schema only `HubMedia` has
those, `BriefingCardMedia` has `imageFit` instead (no hero slot at all) — an
agent following the old doc literally would draft a card that 422s at push,
since both are `.strict()` schemas. Fixed in both places, split into explicit
card-vs-hub field lists. (2) The checklist block payload table (in both
`content-model.md` and `apps/cli/templates/README.md`) omitted the
**required** `id` field and the ADR-0038 `doneBy`/`doneAt`/`ord` fields
entirely — an agent hand-authoring a checklist from the documented shape alone
would fail validation (`id` required, `additionalProperties: false`) or silently
drop member-toggle state on re-push. Fixed both tables. (3) `visibility: family
| restricted` + `audience: [userId,...]` (ADR 0030/0038, applies to both cards
and hubs, pre-dates all three prior passes) was undocumented anywhere in the
skill or CLI help — an agent had no way to learn this exists. Added it to
`content-model.md` (framed explicitly as a privacy/consent decision needing
propose-confirm, not just a formatting option) and the CLI `USAGE` string.
Also reviewed README/architecture.md/CHANGELOG for drift: none found beyond
what 07-06 already fixed (no commits landed on `main` between that pass and
this one besides its own merge, so there was no new code to drift against).
Values/privacy spot-check clean (no secrets, no PII-logging patterns, no
direct Gmail OAuth scope, no child-account paths).

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
