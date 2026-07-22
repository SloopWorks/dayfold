# Backlog — Now, history

Append-only chronological archive of build/status-update entries moved out of
`backlog/now.md` (2026-07-03 repo-maintenance pass) to keep that file short for
the start-of-session routine. **Not loaded by default** — read `backlog/now.md`
first; come here only when you need the detailed narrative behind a shipped
feature or an older decision. Entries are in the order they were written
(newest-first for the main log, with a few older bootstrap-era sections at the
end) — each is self-dated, so use the dates to orient rather than position.
**Repo-maintenance-pass paragraphs for 2026-07-03/05/06/07 were moved here
from `backlog/now.md` on 2026-07-10** (verbatim, that file only keeps the
most recent pass going forward — see its 2026-07-10 entry for why).

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
built for (2026-07-07/09 incident, see below). Triggered
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

**2026-07-17 repo-maintenance pass** (scheduled — the 11th in this series;
same no-npm/no-Gradle-registry-egress sandbox as every prior pass (re-confirmed:
`npm ping` 403s, `./gradlew --version` can't tunnel, and — new check this pass —
even local `npx tsc --noEmit` in `apps/api` fails on a missing `@types/node`,
meaning nothing in `apps/api`/`apps/cli` can be compile- or test-verified here at
all) — no *logic* changes to `apps/api`/`apps/cli`/`apps/client`; everything
below is docs/backlog only. Confirmed **CI green** on `main` at head `6e867f4`
(#346, run #29532422005, success) before starting. Four parallel research
agents re-covered apps/api dedup, apps/cli + skill-doc completeness,
agent-facing doc duplication, and README/architecture/CHANGELOG gaps. **Real
gap closed:** PR #347 (same-day, per-command `--help` + machine-readable
`--json` via a new `Help.kt` registry) shipped with no mention of `--json`
anywhere in the `dayfold-curator` skill docs — an agent following `SKILL.md`
as written had no path to discovering it existed. Added a "Discovering
capabilities" section to `references/cli.md` (example invocations + the
`HelpModel`/`HelpCommand` field shapes), plus one-line mentions in
`README.md`'s and `docs/architecture.md`'s CLI rows. **Agentic-context
fixes:** `CLAUDE.md`'s toolchain-version teaser (`redux-kotlin alpha01`) and
`processes/build-loop-prompt.md`'s worktree-discipline line (`redux-kotlin
1.0.0-alpha01`, `SQLDelight 2.3.2`) had already drifted stale against
`processes/agent-dev-loop.md`'s canonical `1.0.0-alpha05` — both now point to
that file instead of restating a version; also fixed the Light-task
exception's ambiguous step-9 boundary (said "5–8 may be skipped," omitting
whether memory-system loading is skippable — now "5–9") and a slightly
inaccurate `AGENTS.md` directory-map description. `processes/agent-routing.md`'s
own restated guardrail list was re-checked against CLAUDE.md's — not drifted,
deliberately left as a stand-alone restatement (read mid-task without
CLAUDE.md loaded). **CODE DEDUP FINDINGS refreshed** (`backlog/next.md`): all
prior counts re-verified line-for-line (unchanged — `app.ts` untouched since
07-15); found 2 new duplication sites (`ownerGate` boilerplate 7×,
`hubWriteGate` status-mapping 2×); corrected the validation-error-shape count
again, this time upward, ~23 → **~70 sites** (the prior count only tallied
validation/id-error literals, not the full `c.json({type...})` footprint).
**Deliberately still not applied**, even the ones a dedicated review called
"mechanically safe to hand-verify by diff-read": this is live-production
auth/visibility-gate code, this sandbox cannot compile or test it at all (not
even locally), and an unverified refactor of auth code is the wrong place to
spend that risk — same judgment every prior pass reached, now with an
explicit reason (no local typecheck either) rather than just "needs a real
build." **CI/values:** re-confirmed green, nothing new broken; diff this pass
is docs/backlog/skill-reference only — no secrets, no PII, no data-handling
or scope/pricing/legal decision made.

**2026-07-16 repo-maintenance pass** (scheduled — the 10th in this series;
prior passes: below). Same no-npm/no-Gradle-registry-egress sandbox as every
prior pass (re-confirmed: `npm ping` 403s through the proxy,
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

**2026-07-15 repo-maintenance pass** (scheduled — the 9th in this series;
prior passes: below). Same no-npm/no-Gradle-registry-egress sandbox as every
prior pass (re-confirmed) — no *logic* changes to
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

**2026-07-14 repo-maintenance pass** (scheduled — the 8th in this series;
prior passes: below). Same no-npm/no-Gradle-registry-egress sandbox as every
prior pass (re-confirmed: `registry.npmjs.org` and `repo1.maven.org` both 403
through the proxy) — no *logic* changes to `apps/api`/`apps/client`; the
`apps/api` code-dedup queue stays deferred to a build-capable environment
(unchanged counts, see `backlog/next.md`). **CI: green** (`ci.yml` run
#29286455499 on `main`, confirmed via the GitHub API; one older transient
flake at 2026-07-12T18:34:49Z self-healed on the next push — no action
needed). **Biggest find: `backlog/next.md` (1015 lines) had never had the
pruning pass `now.md` got on 2026-07-03** — it was ~75% completed/superseded
build narrative (the whole Content-Library CL-0…CL-PLAT epic, the full
AUTH S1–S6 build log, etc.) sitting in a file whose own header says "queued
work only." Split it the same way, into a new **`backlog/next-history.md`**:
`next.md` is now 301 lines (was 1015, a ~71% cut) holding only what's
genuinely still queued/blocked; full narrative preserved verbatim in the
history file. Three sections (TASK-AUTH-S6-D, TASK-AUTH-CONTENT, TASK-KMP)
read as shipped from git log/CHANGELOG evidence but weren't build-verified in
this sandbox — archived with a flagged "believed done, needs one verification
pass" stub in `next.md` rather than silently asserted done. **Skill/CLI-doc
pass:** the `dayfold-curator` skill docs (`cli.md`/`content-model.md`/
`guardrails.md`/`templates/README.md`) were re-audited command-by-command
against `Main.kt` — no undocumented commands/flags, no stale references, the
2026-07-13 fixes all confirmed present. Found the inline `dayfold --help`
text itself was thin/misleading in two spots and fixed both (source-only,
not build-verified — plain Kotlin string-literal edits): the exit-code-1
remediation said "re-run `dayfold login`" for every failure, which is wrong
for an ADR 0053 per-hub-role 403 (re-login doesn't fix it — only the hub
owner/co-owner promoting you in-app does); and `whoami`'s scope line never
explained the ADR 0029 grant vocabulary. **Docs:** `docs/architecture.md`'s
mermaid diagram was stale relative to its own Components table/prose — it
never showed the SWIP analytics/logging stack (ADR 0054–0057, debug-only),
the Firebase/IdP sign-in path, or the per-hub-role/device-auth DB detail;
added all three. `README.md`'s screenshots section apologized for not having
"current polished state" shots — swapped in real CI-verified golden
snapshots (`apps/ui/.../snapshots/linux/*.png`, light+dark, Now feed + Hub
detail) instead of the older raw dev-proof shots. Also fixed a real
duplication: `README.md`'s opening paragraph was a near-verbatim copy of
`CLAUDE.md`'s (edit-one-forget-the-other risk) — shortened to a distinct
landing-page framing that links to `CLAUDE.md` instead of restating it.
**Values/privacy spot-check:** clean — the only two commits since the last
pass (`a34a987` on-device-LLM research, no code; `cbe4acb` the 2026-07-13
maintenance pass itself) touch no product code. No new guardrail-#3/#4
exposure from today's changes (docs + one Kotlin help-text edit only).

**2026-07-13 repo-maintenance pass** (scheduled — the 7th in this series).
Same no-npm/no-Gradle-registry-egress sandbox as every prior pass
(re-confirmed) — no *logic* changes to `apps/api`/`apps/cli`/`apps/client`;
the still-open `apps/api` code-dedup queue stays deferred to a build-capable
environment, but its counts were refreshed (auth-guard duplication 9→**11**
sites, hub-visibility duplication "3+1"→**7** sites, `app.ts` ~1000→**1244**
lines — see `backlog/next.md`). **CI: green** (`ci.yml` #734 on `main`); one
self-healed transient flake noted (#725, a `sqldelight` Gradle-plugin
resolution hiccup, resolved itself 2 runs later — no code issue, no action
taken). **Found + fixed:** this file's own "in review, not yet on `main`"
claim for ADR 0056/0057 was stale (both had already merged) and three
shipped slices (ADR 0053 hub roles/avatars, the ADR 0029 scoped-token
extension, the analytics-reliability fix) were missing from `CHANGELOG.md` —
added. `docs/architecture.md` was missing ADR 0053 (per-hub roles — live in
the API but undocumented) and ADR 0057/`debugdrawer-swip` — added. **Trimmed
`adr/decisions-index.md`** from ~6,970 words to a short one-line-per-ADR
table (full rationale/composes/rejected-alternatives still live untouched in
each `adr/NNNN-*.md` file) — the single largest agentic-context-usage win
found at the time. **CLI/skill-doc gaps found + fixed:** (1) the ADR 0053
per-hub **role** gate (`write-guard.ts`) is a second, independent 403 source
beyond ADR 0029 scope that `references/cli.md` didn't explain — added; (2)
`place_ref`/foreground-vs-background trigger posture (ADR 0049) was
undocumented in `content-model.md`/`SKILL.md` — added; (3)
`templates/README.md` didn't mention the visibility/audience `--type`
gotcha the other 3 docs already cover — added a one-line pointer. **Not
fixed this pass (flagged, not mechanical enough for a no-compile sandbox):**
ADR 0053's per-hub role gate had no CLI-only remedy path and no
`--help`-per-verb exit-0 behavior — both fixed in the 2026-07-14 pass
(inline `USAGE` text). **Values/privacy spot-check:** clean. Surfaced a
governance-process gap: ADR 0054/0055/0056/0057 were all still headed
"Proposed (accept on merge)" despite being merged and live — filed as
**INB-32** (operator-inbox) rather than agent-flipped, since ADR acceptance
is never agent-decided.

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

**Status update (2026-07-02): repo maintenance pass** (simplification +
agentic-dev optimization, operator-requested, not a feature slice). CI
verified green (no breakage). Changes: deduped `Ulid.kt`/`MediaValidation.kt`
(CLI+client hand-maintained copies → moved into `packages/linkrules`, the
existing shared-source pattern, -178 lines); added `dayfold delete <id>
--block` (the API route existed, CLI/skill docs wrongly said it didn't);
scoped `processes/agent-dev-loop.md` so CLI-/API-only agent work doesn't pay
for the Compose/KMP toolchain section; refreshed `docs/architecture.md` +
`README.md` for the shipped Hub Timeline + fixed a stale CLI command list in
README; added the devtools-gating perf fix + the new `--block` flag to
CHANGELOG. Values spot-check: the inert AccountScreen delete button and the
Claude-processing disclosure gap were both already correctly tracked
(`backlog/next.md`, `context/open-questions.md`) — no new items opened.
Pushed to `claude/upbeat-fermat-r4mggb`; not yet a PR (none requested).
**Superseded note (2026-07-03): that branch was never merged** — equivalent
work landed separately via `be45de6` (PR #276, merged same day); this entry
is kept only as an accurate record of what ran in that session.

Stage: **M0 render prototype BUILT + cloud-live (2026-06-19).** server · Kotlin
CLI · KMP/Compose client · feed — on Vercel + Neon, rendering on the Pixel 10.
Validation verdict still stands: **CONDITIONAL — learning-lab GO, business
NO-GO** → **building to learn**; the business unknowns (OQ-wtp / niche / gemini)
are **untouched by design**. The "brains" (G1 authoring loop) is a deliberate
later milestone; interim authoring = operator + Claude Code via the CLI.

**Status update (2026-07-01): Hub Timeline — COMPLETE + SHIPPED TO `main` (ADR 0045 + 0046).**
The full feature is on `main` (PRs #269 timeline + #270 minSdk) and prod-deploying — `Hub.timeline`
is authorable in production. **Everything in the Phase-2 plan is done:** authoring enablement
(`dayfold template timeline` + curator skill), the client-derived fallback (`deriveTimeline`, **ADR 0046
Accepted** — a hub with no authored timeline projects its own dated blocks → honest "From this hub's
dates" provenance + per-stop source tags + render-only), the day↔hub scope toggle + the "also a
roadmap/day" discoverability row, the `✓N` roadmap collapse (+ a ≤6-node cap from review), per-member
Hide-for-me, tz-aware AM/PM labels, focal-day scoping, and the polish/fidelity gaps. Design mock imported
(`designs/derived-timeline/`); whole-branch adversarial review done + all findings fixed; on-device
verified (authored + **derived** timelines, every state) on the Pixel 10 Pro + emulators. Also dropped
**minSdk 34→33** (Android 13+ / Pixel 4a; pure config, zero source change). **Still open — not blocking:**
(a) **dogfood** a real authored timeline onto the operator's own prod hub (external content → operator
sends; needs their hub data); (b) **tuning** the scale thresholds + NOW-marker calm against real authored
content (pairs with dogfood); (c) **family-tz delivery** — deferred to M1 `family_settings.timezone`.
Minor deferred: an on-card *visible* Hide overflow (swipe + screen-reader action ship; the signed-off card
has no overflow chrome — placement TBD) and a pre-existing `HubScreens.kt` indent nit (no formatter).

**Status update (2026-06-30): Hub Timeline — Phase 1 BUILT (authored content type + on-device
presentation, ADR 0045).** Worktree `derived-now-phase-b`, branch **`feat/hub-timeline`** (off the
phase-B HEAD — the timeline reuses `NowDerive` date helpers, absent on `main`). Imported the
`designs/hub-timeline/` hi-fi mock from Claude Design, 6-agent review (correctness/gaps/xplat +
completeness/UX-M3/data-systems), brainstormed the source model, wrote `specs/hub-timeline-design.md` +
**ADR 0045 (Accepted)**, revised + re-signed-off the mock (Gate A), planned
(`docs/superpowers/plans/2026-06-30-hub-timeline.md`, 2 adversarial rounds), and built it subagent-driven
(16 task-units, TDD, per-task review). **Shipped:** `Hub.timeline` authored property (schema + server +
CLI content-blind structural validation), a pure on-device `TimelinePresenter` (status/scale/NOW/grouping/
windowing, injected clock+tz — the multi-member wedge holds cross-tz), day-rail + hub-roadmap cards, a
scrollable detail, nav substate + BackNav, attachment→CardAction handoff, and the card→detail
shared-element morph. **Phase 1 = authored, render-only, Now-invisible, zero notifications**; provenance
copy is authored-honest (no "derived on-device" claim). Full-stack gate green (codegen · api 345 · CLI ·
client desktopTest).

**Status update (2026-07-01): Hub Timeline — Phase 2 BUILT (branch `feat/hub-timeline-phase2` off
`origin/now-derived-phase-b`).** Six commits, TDD, on-device-verified on emulator vs `designs/hub-timeline/`.
**Shipped:** (S1) tz-aware **AM/PM** stop labels moved into the presenter — killed the raw-`at`
string-parsers + DST/offset risk; robust detail-day NOW index. (S2) roadmap **`✓N` collapse**
(`SpineNode.collapsedCount`; replaces the +M-more tail). (S3) per-member **"Hide for me"** on the hoisted
card (synthetic id, reuses the W5 hide plumbing; swipe + a11y action + recovery row). (S4) in-detail
**day↔hub scope toggle** (`SingleChoiceSegmentedButtonRow` + `hasBothScales`). (S5-fix) **Day scale scopes
to the focal day** (on-device caught roadmap milestones bleeding into "Today's schedule"). (S5) **authoring
enablement** — `dayfold template timeline` + `dayfold-curator` skill teaches timeline authoring. Enriched
the fake college hub to a both-scales demo. **On-device VERIFIED** (day card "2 done"/AM-PM, toggle→roadmap
month groups + tz "Mon D" labels + NOW band). Full gate green (`:client:desktopTest` + `apps/cli` test).
**Operator-gated remainder (loop STOPPED here):** (a) **4b card "also a roadmap/day" hint** — not in any
signed-off mock → design sign-off; (b) **ADR 0046** (client-derived `deriveTimeline` fallback — the
ADR-0043-class second on-device projection) drafted **Proposed** → accept + a derived-states mock;
(c) **dogfood** a real authored timeline onto the operator's own hub → external content; (d) **ship**
`now-derived-phase-b → main` → deploy/spend. Governance (family-tz delivery, NOW-marker calm tuning) revisit
vs real authored content.

**Bugfix (2026-06-30, on-device report) ✅ FIXED + VERIFIED ON PIXEL 10 PRO: Hub link/document blocks
were not tappable.** Root cause (two layers): (1) `LinkRow` (`HubScreens.kt`) rendered the "opens
externally" arrow but had **no click handler** — the installed `LocalUriHandler` (PlatformUriHandler,
used by inline body-links) was never invoked for structured blocks; (2) — found via the device DB —
**`document` blocks keep their URL in `ref`/`docRef`, not `url`** (real data: a Butler PDF at
`https://cdn.butler.edu/...Immunization-Req.pdf` in `ref`), so a first pass that only handled `url` left
the actual tapped blocks inert. Fix: `LinkRow` is `clickable` when ANY of `url`/`docRef`/`ref` vets as
https → `LocalUriHandler.current.openUri(it)` (a non-URL ref stays inert). TDD: `HubLinkTapTest` (link,
document-ref, inert-ref) red→green; **662 desktop tests green**. **On-device: tapped "2026-27
Immunization" on the Pixel → Chrome opened the Butler Health Services PDF.** (Lesson: the unit test first
encoded the wrong assumption — real document `ref`s are https URLs; pulling the device DB found it.)

**Status update (2026-07-01): Now derived surfacing — PHASE B BUILT + SHIPPED to `main` (PR #260).**
Local-only Android background notifications are live on `main` (background pass, `AndroidLocalNotifier`,
`GeofencingClient` + `AlarmManager` scheduling, `NotifConfig` quiet-hours + daily-cap, permission ladder,
offline states; **default-OFF / opt-in**). Gate A re-approved as-shipped by the operator ("I approve",
INB-29 RESOLVED). **Open (not blocking):** the **activity** trigger stays a reserved schema slot (matching
DEFERRED per operator); the **public-ship** Play/App-Store background-location data-safety declaration +
disclosure review (pre-public). Detail of the build below.

**Status update (2026-07-01): iOS PARITY BUILT + sim-verified (PR #273, branch `ios-notif-phase-b`).**
Brings iOS to parity with the shipped Android Phase B (ADR 0044). A SwiftUI/xcodegen host under
`apps/iosApp/` embeds the `:client` static framework + renders the shared Compose UI; the 5 device seams
are implemented in `iosMain` over the SAME commonMain core (no engine fork): `IosLocalNotifier`
(`UNUserNotificationCenter`), `IosExactNotificationScheduler` (`UNTimeIntervalNotificationTrigger`),
`IosGeofenceController` (`CLLocationManager` region monitoring, 20-region nearest-N), the two permission
controllers, `IosContentStoreHolder`, and process-global `IosNotifGlue` (retained UN/CL delegates on the
main thread) + a reconcile-only `BGTaskScheduler`. **Verified on the iOS Simulator:** time lane fires;
geofence lane fires (`simctl` location crossing → `didEnterRegion` → shared pass → banner); tap →
`openHub` (console-confirmed); permission ladder prompts. **Sim-limited (real-device/lldb, documented):**
background/killed region wake + BGTask launch. **iOS time-lane divergence** (operator-accepted via the
build plan): quiet/cap/dedup applied at SCHEDULE time (iOS can't re-run the pass at fire) — see ADR 0044
Status. Public App-Store background-location justification stays operator/legal-gated. 659 desktop tests
green; both iOS targets link; commonMain reused-verbatim untouched.

**Status update (2026-06-30 PM): Now derived surfacing — PHASE B BUILD STARTED (both gates closed).**
Worktree `derived-now-phase-b` (branch `now-derived-phase-b`, off origin/main). Operator resolved all
Phase-B gates in-session:
- **Gate A (ADR 0008): SIGNED OFF.** The v2 `designs/triggers/` mockup set (INB-13 §6b honesty rework +
  the opt-in ladder + closed-app notif/lock-screen + offline + reversible settings + the "Matched on your
  device" affordance) was imported from Claude Design (15 files) and operator-approved as-is. P0 "saved
  coords never leave" claim confirmed absent; honest two-part promise present. **Closes INB-13.**
- **Scope: FULL build incl. device glue** (Android device connected + iOS sim available → on-device
  verification IS possible; the "no-device" deferral is dropped).
- **Places: CLI/server-authored** (Add-place UI out of scope; list read-only; place-egress lane = future
  slice + own ADR).
- **Notif defaults RATIFIED:** cap 3/day, quiet 22:00–08:00, urgent (NOW/geo) bypasses quiet but counts
  to the cap. (Resolves the ADR 0044 `[pending-ratify]`.)
Plan (6-agent adversarial review: correctness/gaps/KMP/redux/simplification/UI-UX) →
`docs/superpowers/specs/2026-06-30-now-phase-b-plan.md`. **commonMain foundation BUILT + GREEN (604
client desktop tests, 0 fail):**
- **S0** pure notification-selection core (`NowNotify.kt`): `selectNotifications` over the SAME
  `RankedFeed` (NO engine fork); sibling `NotifConfig` (never `RankConfig`); quiet-hours wrap,
  daily-cap, dedup, foreground-suppression, `nearestNPlaces`, `notificationActionFor→OpenHub`, pure
  `postedTodayCount` rollover; `LocationPermission`/`NotificationPermission` enums — 13 tests.
- **S1** device-local NEVER-synced state: `notif_config` + `notification_log` tables (migration
  `8.sqm`, v8→v9, verifies clean), ContentStore `notifConfig`/`setNotifConfig`/`logNotification`/
  `notifLedger` + sync snapshot getter, `NotifConfigLoaded`/`*PermissionLoaded` reducer bridges +
  AppState slices, SyncEngine DB→store config bridge. wipe()=reset, wipeForResync()=preserve — 12 tests.
- **S1b** honesty copy fix (shipped Phase-A): geo `WhyChip` → "Matched on your device" (killed the false
  "· location never leaves"; ADR 0044 §3 P0).
- **S2 ✅ COMPLETE — Compose surfaces, snapshot-verified light+dark, 636 client desktop tests green
  (+29).** All map designs/trigger/* 1:1, M3-stable fallbacks (no M3E/CL-0b block):
  - `PrivacyAffordance.kt` (keystone) — chip/info-row/detail-sheet, honest two-part promise.
  - `PermissionLadder.kt` (9 tests) — opt-in ladder: locPrime/alwaysUpgrade(honest battery+closed-app
    trade)/notifPrime/limited/denied/downgraded; "Not now"/secondary is a full-color OutlinedButton PEER
    (never disabled); on-device promise rides every priming screen.
  - `ProximitySettings.kt` (7) — reversible toggle (Switch), off→"Geofences removed" + async
    "Removing…", permission row + device-local privacy line, quiet-hours editor (pure
    `formatMinuteOfDay`), daily-cap `SingleChoiceSegmentedButtonRow` (1/3/5, not a slider), dimmed-when-off.
  - `OfflineBanner.kt` (3) — "Offline · still matched on your device" (privacy teal, strength-not-error).
  - `NotifStates.kt` (6) — quiet-held card + cap-reached "You're all caught up · N of N" (no badge/count-urgency).
  - `PlacesList.kt` (4) — READ-ONLY list (no edit pencil/FAB), family-privacy row, "Added by Claude" provenance.
  - a11y `rememberReduceMotion()` already shipped + matches plan (Android ANIMATOR_DURATION_SCALE==0 /
    iOS UIAccessibilityIsReduceMotionEnabled / desktop false); Shimmer infinite anim already gated. ≥48dp
    hit-slop + content-descriptions + labelSmall(≥11sp) privacy chip applied.
- **S3 (commonMain core ✅ COMPLETE + GREEN — 649 client desktop tests, +13; iOS links; Android client
  compiles):** the testable HEART of the background path, no engine fork:
  - `BackgroundNotify.kt` (7 tests) — `planBackgroundNotifications(snapshot, nowIso, location, zone)`:
    builds a minimal `AppState` from a synchronous `NotifSnapshot` (cards+hubs+sections+blocks+places+
    surfacing+config+log) → calls the SAME `nowFeed()` + `selectNotifications`. Daily-cap rollover by
    local date from the log, within-day dedup, foreground-shown suppression (`FOREGROUND_SUPPRESSION_
    WINDOW`=30m via `surfacing.lastShown`). Live position injected, never persisted.
  - `NotifSeams.kt` (6 tests) — device-glue as **commonMain interfaces** (deliberate deviation from
    expect/actual: keeps logic in commonMain + unit-testable with fakes, every target green without
    half-built actuals; the real impls are the on-device remainder): `LocalNotifier`/`GeofenceController`/
    `ExactNotificationScheduler`/`LocationPermissionController`/`NotificationPermissionController` +
    `GeoRegion`/`NotificationSpec` + pure mappers (`notificationSubtext` honest provenance, `toNotification
    Spec`, `geoRegionsFor` nearest-N capped iOS-20/Android-100 w/ radius fallback). `BackgroundNotification
    Runner` (plan→post→log once) + `cancelForegroundVisible`, both faked-tested.
  - **WAL on the Android driver** (`DriverFactory.android.kt`) — single-writer/many-reader parity with
    desktop so the background pass reads the SAME process-shared cache; no 2nd connection.
  - **ContentStore SYNC snapshot getters** (3 tests) — `activeHubs/allSections/allBlocks/activePlaces/
    surfacing/notificationLog` + `notifSnapshot()` assembler (one read from the shared connection); proven
    end-to-end (`planBackgroundNotifications(store.notifSnapshot())`) over a real in-memory store.
  - **Android device-glue ✅ BUILT + ON-EMULATOR-VERIFIED (assembleDebug green; 2 instrumented tests pass
    on emulator-5558 API 35):**
    - `AndroidLocalNotifier.kt` — NotificationCompat BigText + group/group-summary digest + deep-link
      "Open" PendingIntent (extras → cold-start OpenHub) + honest on-device subtext; `POST_NOTIFICATIONS`
      (runtime; denial = no-op not crash). Instrumented test posts + asserts it lands in the system set.
    - `AndroidBackgroundNotify.kt` — `runBackgroundNotificationPass` (the receivers' shared entry: holder
      store → `notifSnapshot()` → `BackgroundNotificationRunner` → notifier + log), `AndroidGeofenceController`
      (GeofencingClient, MUTABLE PendingIntent, nearest-N regions, NEVER_EXPIRE/ENTER), `AndroidExact
      NotificationScheduler` (`setExactAndAllowWhileIdle`), `onGeofenceEnter`/`reRegisterGeofences`.
    - `AndroidPermissionControllers.kt` — Location (Denied/WhenInUse/Always from FINE+BACKGROUND) +
      Notification (Granted/Denied/Blocked) state truth + refresh() + OS-settings deep-link.
    - `AndroidContentStoreHolder` — ONE process-shared store/driver for fg+bg (single-writer); MainActivity
      now uses it. **WAL** enabled via the open-helper `onConfigure` callback (the `PRAGMA` returns a row →
      can't go through `driver.execute`; that was a real bug, fixed).
    - 3 manifest receivers (`GeofenceReceiver`/`ExactAlarmReceiver`/`BootReceiver`) + Play Services
      Location dep + FINE/BACKGROUND_LOCATION/BOOT/EXACT_ALARM perms. **`AndroidBackgroundPassTest` PROVES
      the whole headless chain on-device:** seed shared store → enable config → `runBackgroundNotification
      Pass` posts a real notification → 2nd pass dedups. (BootReceiver also observed firing on install.)
  - **Android FOREGROUND wiring ✅ (MainActivity; assembleDebug green; app installs + launches + runs on
    emulator-5558 with NO crash):** OS-permission bridge — controllers' Flow → `LocationPermissionLoaded`/
    `NotificationPermissionLoaded` (initial dispatch + collect; **refreshed every foreground** since Android
    emits no permission-change broadcast). Config reaction — `notifConfigFlow` enable → register geofences
    for saved places (capped); disable → `deregisterAll`. Notification-tap → `hubEngine.openHub(hubId,
    blockId)` from the notifier extras (cold-start in onCreate + warm in onNewIntent; extras consumed;
    dangling-target tolerant). Shared store via the holder.
  - **Exact-alarm scheduling pipeline ✅** (4 tests) — pure `planExactSchedules(snapshot, nowIso, horizon)`
    reads BOTH raw lanes directly (`deriveNow` + `cardToNowItem`, NOT `nowFeed` whose not_before gate would
    hide the very future authored items we wake for); keeps each subject's soonest future trigger within a
    48h horizon; fire-time receiver re-runs the full pass (cap/quiet/dedup honored then). Wired on Android
    (`reconcileExactSchedules` → `AndroidExactNotificationScheduler.setExactAndAllowWhileIdle`), armed on
    enable alongside geofences. So BOTH halves now fire closed-app: proximity (geofence) + time (exact alarm).
  - **Settings UI on-ramp ✅** (3 host tests) — `ProximitySettingsHost` (top bar + quiet-hours `TimePicker`
    dialogs + privacy `ModalBottomSheet`) is now NAVIGABLE: Account → "Background proximity" (`Route.Proximity`
    + `OpenProximity`/`CloseProximity` + reducer + BackNav). Toggle/cap/quiet → `onSetNotifConfig` →
    (shell) `ContentStore.setNotifConfig` → flow → `NotifConfigLoaded` → the geofence/exact-alarm reaction
    arms. `onSetNotifConfig` threaded through `FeedApp` (defaulted → other shells/tests unaffected); wired in
    MainActivity (IO dispatcher). Interaction-tested: toggle/cap emit the right config write, back closes.
**State: 659 desktop tests green · iOS links · assembleDebug green · 2 Android instrumented tests green on
emulator · app installs+launches+runs clean WITH the new nav. The Android notification ENGINE (background
pass + proximity + time) + foreground integration (permission reflection, geofence/alarm-on-enable,
tap→deep-link) + the in-app Settings on-ramp are on-device-proven. NO engine fork; live position never
persisted; config never synced; single-writer WAL.**
  - **In-app permission PROMPTS ✅ + content-change re-registration ✅** (MainActivity; assembleDebug green;
    app runs clean): enabling requests POST_NOTIFICATIONS + while-using location via `registerForActivity
    Result` (background "Always" correctly stays a reversible Settings trip — Android forbids an in-app
    dialog for it); each result refreshes OS truth into the store. Geofences + exact alarms also re-register
    on `nowContentFlow` change (place added/removed, new timed items), not just on enable.

  - **ON-DEVICE DRIVE on the Pixel 10 Pro (API 36, real Google session + synced content) ✅:** Account →
    "Background proximity" → Settings renders design-faithful → toggle On (config write + un-dimmed
    controls) → **in-app notification prompt → Allow** → **in-app location prompt → While using the app** →
    permission row live-updates to "While using the app" (the bridge + refresh-on-result works). **Bug
    found + fixed on-device:** two back-to-back permission `launch()` calls dropped the location prompt
    (Android shows one dialog at a time) → switched to a single `RequestMultiplePermissions` flow;
    reinstalled + re-verified. (Geofence ENTER → notification still wants a seeded place + arrival to fully
    close; the engine + the whole permission/settings on-ramp are device-proven.)

**Android side of Phase B is FEATURE-COMPLETE + on-device-verified (drive on the Pixel 10 Pro).** Remaining:
(1) PRIMING UX polish — show the built `PermissionLadderScreen` priming screens before the OS prompt on
first enable (today we jump straight to the system dialog; the state/flow all work). (2) geofence runtime
drive via emulator mock-location (flaky/hard to assert). (3) **iOS actuals** (UN/CL/UNCalendarTrigger +
BGTask via Swift AppDelegate, process-global delegate object on main thread, 20-region eviction) + iOS-sim
smoke — entirely UNBUILT; no iOS sim booted in this env, so build-only verification at best.

**Status update (2026-06-30): Now derived surfacing — PHASE B gate resolved by the operator;
build proceeds only on the ungated carryover (Gate A still blocks the notification surface).**
The loop stopped at the Phase-B gate (background geofence + LOCAL notifications, ADR 0043 §Phasing)
and surfaced both gates as **INB-29**; operator answered in-session:
- **Gate B — background-location posture: RATIFIED.** Operator "Accept ADR 0044 as written" →
  **ADR 0044 Accepted** (the "Always" opt-in/reversible posture; LOCAL notifications only — no
  FCM/APNs, no server change, dumb-server invariant intact; geofence nearest-N, iOS 20-region cap;
  quiet-hours + daily-cap as device-local never-synced `RankConfig` knobs; `rank()` stays pure;
  live position never leaves device).
- **Gate A — ADR 0008 design-first: STILL OPEN (mockups + sign-off pending).** Operator asked for
  a Claude Design prompt framing the feature as **opt-in**; delivered as
  `designs/DESIGN-BRIEF-triggers-v2-phase-b.md` (self-contained; folds in INB-13 §6b + opt-in
  ladder). **Phase-B implementation (geofence / local-notif / permission surfaces) stays BLOCKED
  on signed-off mockups** — so the build loop does NOT proceed on those surfaces yet.
- **Ungated carryover — ✅ BUILT + GREEN (operator "build it now"):** the render-driven record-shown
  EFFECT now starts the anti-nag clock (foreground-only over the signed-off `now-derived/` feed; no
  new permission/surface). TDD slice on branch `claude/now-derived-phase-b-*`:
  - **`NowEngine`** (commonMain, HubEngine-style, debounced) is the sole surfacing writer:
    render reports visible subjects → `noteShown` (coalesced) → `recordShownIfNew` → `surfacingFlow`
    bridge → `SurfacingLoaded` → `state.surfacing` → next `nowFeed()` recompute. Unidirectional; the
    render path NEVER writes surfacing. `dismiss` → `recordDismissed` → `rank()` omits the subject.
  - **Write-once clock fix:** new SQL `recordShownIfNew` (`ON CONFLICT DO NOTHING`) so continuous
    visibility STARTS the decay clock once and never RESETS it (overwriting `last_shown` each tick
    would defeat softening). Decay/soften (dormant since Phase A — nothing wrote `last_shown`) now
    engage. No schema change (query-only; `surfacing_state` unchanged).
  - **Render wiring:** `FeedScreen` reports `RankedFeed.visibleSubjectKeys()` (prominent bands +
    dedup peers, overflow excluded) via `LaunchedEffect(set)` → `onNowShown`, threaded through all 3
    shells (desktop/iOS verified compile; Android = verbatim mirror, CI-verified — no SDK in build env).
  - **579 client desktop tests green** (572 Phase-A baseline + 5 `NowEngineTest` + 2 `visibleSubjectKeys`);
    `compileKotlinIosArm64` clean; `verifyMigrations` drift is the pre-existing ADR 0036 `media`
    ordinal (CI-skipped) — my query-only addition verifies clean. **No visible dismiss CONTROL was
    added** (it would need its own Phase-B/trigger mockup); the dismiss DATA path + omission are
    built + tested. Grounding: `RankConfig` (`NowRank.kt:45`) + the "Quiet-hours deferred to Phase B"
    note (`NowRank.kt:18-19`).

**Status update (2026-06-30): Now derived surfacing — Phase A built (ADR 0043).** Operator
ratified both gates in-session (INB-28): **ADR 0043 → Accepted** + `designs/now-derived/` **signed
off**. Built as a TDD slice loop → **PR #257** (branch `claude/now-derived-surfacing-phase-a-*`):
- **Slice 1** — decode block `triggers[]` + `Place`; `places` flow through `/sync` to cache
  (migration `6.sqm`: `hub_block.triggers`, `place`, local-only `surfacing_state`).
- **Slice 2** — pure `deriveNow` over all 5 reason_kinds (countdown/milestone/checklist/geo/`when`)
  each with a computed "why"; `parseInstantFlexible` (date-only + instant); geo via haversine.
- **Slice 3** — pure **Priority & Ordering Engine** `rank`: score (urgency/proximity/importance/
  decay) → prefix-containment dedup → calm budget (now/soon/later bands + overflow never-drops) →
  score-snap hysteresis; local-only surfacing state (last-shown/dismissed, never synced).
- **Slice 4a/6** — `nowFeed` merge selector (both lanes, one engine, render-time clock+location);
  authored bounded `importance` (the one schema touch — `BriefingCard` + regen + `7.sqm`) the engine
  ranks; `not_before` gated on-device (closes **OQ-notbefore-gating**).
- **Slice 4b/5** — merged-feed render (`NowFeedList`: bands, why-chips, geo-active ring + "Nearby",
  count-free overflow, caught-up keeps horizon); deep-link tap → shipped hub-arrival transform+pulse.
**571 client tests green** (+37 from a 534 baseline); `verifyMigrations` + iOS compile clean; merged
feed **visually verified light+dark** via headless Compose-Desktop snapshot (no Android device in the
build env). Server stays content-blind (derived items client-only). 3-agent adversarial design review
applied before coding. **Phase B deferred** (background geofence + local notifications; the
render-driven record-shown effect; quiet-hours). ADR 0043 reviewed by a 3-agent design panel +
per-slice TDD + a final code-review pass.

**Status update (2026-06-29): two-way (member-writes) build STARTED.** Operator
ratified the two-way bundle in-session — ADRs **0038/0039/0040/0041/0042 → Accepted**
(0041 = the bounded-member-AI-command **constitution amendment**, applied; W3 ships
EXPERIMENTAL/flagged; member scope = global `content:write`; R2 confirmed; W2 = visible
hubs only; W5 hide = local-only first; INB-25/26 closed). Ratification merged via
**PR #238**.
- **Slice 1 (schema + reserved shape) — ✅ MERGED (PR #247)**: checklist item
  `id`/`doneBy`/`doneAt`/`ord` + codegen + Kotlin CI drift guard; CLI ULID
  stamp-on-push; migration 0015 reserves `op_log` + `created_by`/`author_kind`/
  `writer_user_id` on blocks+cards + `block_type`/`card_kind` ENUM→text + `content:delete`.
- **Slice 2 (server must-fixes / member-write security gate) — ✅ MERGED (PR #248)**:
  If-Match→412 (block+section), visibility-on-write (restricted→404, no oracle; 403 only
  visible-but-scope-denied; matrix 200/200/200/404), 410-on-tombstone (no member
  resurrection), op_log idempotency + 7-day TTL sweep, tolerant validator gated to
  plaintext-M0. **Refines ADR 0030 §6**: a hub-rewrite the caller can't see is now 404.
  320 API tests green.
- **Slice 3 (client sync engine / egress lane) — ✅ COMPLETE (PR open from
  `two-way-slice3-client-sync`)**: `ChecklistMerge` (per-item done-triple LWW,
  convergent + idempotent) + `OutboxSender` (412/410/backoff/cap FSM) + the egress
  wiring — `outbox` SQLDelight table + `version`/`local_state` columns (migration
  `4.sqm`), `ContentStore.enqueueBlockToggle` (optimistic apply), per-block-type merge
  dispatch in `applyDelta` + echo-suppress, `SyncClient.putBlock` (If-Match +
  Idempotency-Key), `SyncEngine.drainOutbox` under the sync mutex. **481 client tests
  green** incl. 2 headless egress integration tests (happy path + 412 re-merge converge).
- **Slice 4 (toggle UI) — ✅ COMPLETE (branch `two-way-slice4-toggle-ui`)**: the
  tappable `ChecklistRow` (whole-row 48dp, `Role.Checkbox` + state desc, coral check
  scale-overshoot + left→right strike wipe, one haptic tick, reduced-motion aware) →
  `HubEngine.toggleItem` → `ContentStore.enqueueBlockToggle` → `SyncEngine.drainOutbox`.
  Pure `ChecklistFold` burst machine (one shared ~2s debounce → batch fold into "N done",
  newest-first, count-only >20) + client `Ulid` minter, both TDD. Five-rung optimistic
  vocabulary off `local_state` (saving hairline / calm inline Retry, never a modal) +
  offline banner + "N saving · Sync now" queue pill; honesty chip "Shared with your
  family · synced when online". Interactivity gated on item ids (display-only lists stay
  static — the synced claim is only honest where a member-write boundary exists, D4).
  **503 client tests green** (+22); **on-device verified on the Pixel**: real
  tap → optimistic flip + strike → burst-fold "2 done" → pending pill → whole-block PUT
  → server `blk_chk` v1→v2 (`done:true`) → /sync echo clears pending. Threaded through all
  three shells (android/desktop/ios — compile-clean). Dev-infra fixes folded in:
  `ondevice-demo.sh` migration glob (`000[1-9]`→all, was skipping 0015 op_log → 500s),
  JAVA17 path (stable brew symlink, 17.0.18→17.0.19 drift), seed + fake checklist ids.
- **Slice 5a (W4 delete — server) — ✅ PR #253 (CI pending)**: `DELETE /blocks/:id`
  soft-delete + tombstone, no-oracle authz (absent/can't-see→404, no-scope→403,
  non-author→403 incl. owner-no-override, idempotent re-delete→204). **Operator
  decision (2026-06-29): members get a `content:delete` grant, author-gated to their
  own content** (distinct scope, carved out of `content:write`; granted to member app
  creds + CLI/loop). `upsertBlock` now stamps `created_by` set-once (INSERT only) =
  the author-gate substrate (minimal W2 stamp). 326 API tests green. *Implements
  accepted ADR 0038 §W4; the member `content:delete` grant is a new member-authority
  fact — fold into an ADR 0038 amendment or a short ADR if the operator wants it recorded.*
- **Slice 5b (W4 delete client + W5 hide) — ✅ COMPLETE (branch
  `two-way-slice5b-delete-hide-client`)**: **W4 delete client** — `created_by` propagated
  over /sync (`HubBlock.createdBy` + `hub_block.created_by` col + migration `5.sqm` +
  `upsertBlock`/SELECTs/`rowToBlock`); author-only delete sheet (`ModalBottomSheet`, calm
  warn — *absent* not disabled for non-authors; option gated on `createdBy == session.userId`);
  egress `SyncClient.deleteBlock` (DELETE + Idempotency-Key, no body/If-Match),
  `OutboxSender` 204→Acked, `SyncEngine.drainOutbox` dispatch on `op.type`,
  `ContentStore.enqueueBlockDelete` + `HubEngine.deleteBlock`. **Optimistic = honest
  "Removing…"** (mark `pending` + keep the row visible until the /sync tombstone confirms;
  reuses the five-rung vocab + survives offline) — a deliberate **deviation from the mockup's
  optimistic-remove + undo** (chosen for reuse + offline-correctness). **W5 hide** — LOCAL-ONLY
  `hidden` table (never synced, wiped on `wipe()`), `ContentStore.hide/unhide/hiddenIdsFlow`
  + pure `partitionHidden`, DB→store `hiddenBridge` → `state.hiddenIds`, swipe-to-hide
  (`SwipeToDismissBox`) + overflow `DropdownMenu` (the a11y path) both reach Hide,
  collapsed "Hidden for you · N" + "Show hidden" toggle + "You hid this" + Unhide.
  **Enabling fix**: the client now decodes its own user id from the access-token JWT `sub`
  (`jwtSub`, decode-only) — `AuthClient` had been minting `Session.userId = null`, which would
  have left the author-gate permanently closed on the real sign-in path. Threaded
  `onDeleteBlock`/`onHideBlock`/`onUnhideBlock` through FeedApp→HubsHost→HubDetailScreen + all
  3 shells; `Show hidden` is pure store state (dispatched in HubsHost, no shell seam).
  **509 client tests green** (+TDD: 204-ack, `created_by` round-trip, delete egress E2E,
  hide model/partition, reducer, JWT-sub, 7 delete/hide compose tests). **On-device verified
  on the Pixel**: author overflow → Delete → warn sheet ("Delete "12 guests…"?") →
  optimistic pill → DELETE → server `deleted_at` set → /sync tombstone removes the card +
  empty section; swipe → "Hidden for you" → Show hidden → "You hid this" → Unhide → restored.
  Dev-infra: seed `ondevice-seed.sql` now stamps `created_by` (blk_ov=u_dev author / blk_link=u_sam
  non-author) so the author-gate is exercisable on-device. *Pre-existing card/hub-ordinal
  `verifyMigrations` drift is unrelated + CI-skipped (confirmed identical on main); my
  `created_by`/`hidden` additions verify clean.*
- **Slice 6 (Freshness contract, ADR 0040 §3) — ✅ COMPLETE (branch
  `two-way-slice6-freshness`)**: **stale-cursor full-resync directive** — `/sync` flags
  `full_resync:true` + resets the scan to -∞ when the caller's 3-part cursor is older than
  the tombstone-retention floor; the client `wipeForResync()` (clears synced content + cursor,
  **preserves the outbox + local hidden set** — a staleness reset, NOT the tenancy-revocation
  `wipe()`) then rebuilds from the page. **Content-tombstone GC** — a new arm of `/cron/sweep`
  hard-purges soft-deleted rows (cards/hubs/sections/blocks) older than the floor (kept below
  it so any client synced within the floor never misses a delete). **One shared constant**
  `CONTENT_TOMBSTONE_RETENTION_DAYS` (default 90, env-overridable) gates both halves —
  **operator-gated value, shipped `[pending-ratify]` → INB-27**. Watermark-GC / push / realtime
  remain deferred drop-ins on the same cursor. TDD: +1 sweep GC test, +3 stale-cursor /sync
  tests (**API 335 green**), +1 client full-resync test (preserve outbox+hidden). No schema
  migration (reuses `deleted_at`); no new cron (existing sweep). Bundle `api/index.js` rebuilt.
- **Next**: deferred + gated last: W2 authoring (Author screen — `created_by`/`author_kind`
  enforcement, loop-never-edits-member-blocks, audience ⊆ caller, single-writer-per-block),
  W1 media (R2 — external/spend gate, confirm before provisioning), W3 add-context
  (EXPERIMENTAL/flagged — recurring AI-spend gate, confirm before first real run).

**Status update (2026-06-26): first real on-device sign-in is LIVE on prod.** Real
Google sign-in + foreground sync now work end-to-end on the Pixel against
`family-ai-dashboard.vercel.app`, after fixing a two-part prod-config gap (DB schema
behind the entire AUTH epic + missing token-signing env) and two in-app bugs
(sync token-refresh-on-401 #104, debug-drawer Logs bridge #106). See the DONE entry
under *Operator actions* for the full account + the `npm run preflight` recurrence
guard. Next real validation: operator drives sign-in → create-family → `dayfold
login` (device grant now works on prod) → CLI authoring → on-device render.

**Status update (2026-06-23):** TASK-KMP + TASK-SYNC done+merged. The **AUTH epic**
(ADR 0021; S1·S3·S4·S5·S6) and the **CL/Dayfold content epic** (CL-0…CL-9) all
merged to `main` (PRs #2–#21). **AUTH-S2 *real Google path* ✅ DONE + MERGED**
(PR #25, `main` 8c0ccec) — Firebase ID-token verify (JWKS, ADR 0027),
`/auth/firebase`, client `firebaseToken` seam, CI Firebase emulator job, S2 design
spec + operator runbook; brought the branch up to Gradle 9.4.1 + the debugdrawer
modules (PR #26). **Entire AUTH epic S1–S6 now shipped.** **Open inbox:** INB-3,
INB-13. **INB-19 PARTIAL (2026-06-22):**
rk ratified + pinned alpha02 (ADR-0019-realized); publishing `redux-kotlin-snapshot`
+ Homebrew-tap symlink fix still pending (operator-only) — **golden harness not yet
wired**, so hold CL-NAV/CL-10 build until it lands.
**ADR 0020 (TASK-SYNC) still *Proposed*** though built — operator may flip to
*Accepted*.


## Hub & card visual enrichment (ADR 0036) — MERGED to `main` (#177)

**Status: MERGED to `main` via #177 (2026-06-26).** Since shipped, the render kit gained
snapshot coverage (hub hero #191 + card thumbnail #192), accent-math memoization (#193), and
the curator skill was brought current (#212–214). Original delivery, implemented + green:
Hi-fi design imported to `designs/hub-card-enrichment/` (operator signed off as-is,
ADR 0008) + **ADR 0036 accepted** (Wikimedia-only image allowlist, hardened shared
validator). Delivered end-to-end:
- **Schema+codegen:** `Hub.media`/`BriefingCard.media` + block link/document
  `thumbnailUrl`(+alt) + contact `avatarUrl`/`accentColor`; Zod + Kotlin regen.
- **Migration `0013_visual_enrichment.sql`** (media jsonb + typeof CHECK on hubs+cards);
  client SQLDelight v3→v4 (`3.sqm`, media column on card+hub; block media rides payload).
- **Shared hardened validator** (https-only, exact-host allowlist, reject
  userinfo/punycode/alt-port/SVG, curated-icon enum, #RRGGBB) in **3 lock-step copies**:
  API `media-validation.ts` (Zod-refine layer on the PUT path), CLI `MediaValidation.kt`
  (wired into `Validate`), client `MediaValidation.kt` (Coil load guard).
- **Coil3 render** (HubRow leading tile, collapsing-capped hero banner, card icon+accent
  kind-chip + thumb, contact avatar→initials, link/doc thumb) with the
  **image→icon+accent-tile→default fallback ladder**; accent harmonized (decorative only).
- **Tests green:** API 244 (media round-trip + 422 evasion + sync carries media);
  CLI + client kotlin-test (URL/hex/icon accept-reject incl. evasion vectors); 7 Compose
  snapshots (fallback rung × light/dark × hero). Desktop + Android targets compile.
- **Deferred (noted):** structured *block* media round-trips server-side only after
  **ADR 0035** (block-payload reconciliation; live content is body_md-only today);
  typed-card (`TypedCardItem`) media + full scroll-collapse hero + ETag disk-cache-key +
  RTL snapshot = follow-ups; **Phase 2** (self-host/CDN, SSRF-guarded ingest, full HCT) deferred.


## State (2026-06-18 — post 6-agent review)

- **Spec-build loop SUSPENDED** (cron stopped) — process right-sized for the
  solo M0 build (kept: spec-gate + inbox + multi-agent reviews).
- **Decisions:** M0 = **plaintext** (live E2EE → M1, ADR 0017 gate); M0 surface
  = **briefing-feed only** (Hubs → next slice); redux **1.0.0-alpha01** (INB-11
  superseded 2026-06-19 — operator owns reduxkotlin; `f(store.state)→UI`).
- **Spec suite + impl plan + JSON-schema contract = done; schema freeze
  unblocked.** Ready to build the M0 spine.
- **INB-9 RESOLVED → TypeScript on Vercel (ADR 0018).**
- **✅ M0 PROTOTYPE BUILT (12 build-loop iterations; build loop now STOPPED).**
  server (TS/Hono/Postgres) · CLI (Kotlin) · client core (redux-kotlin 0.6.2) ·
  feed UI (Compose, headless-render-tested) — all green, CI-enforced on GitHub,
  every component multi-agent-reviewed + fixes applied. Full CLI→API→DB→/sync
  round-trip verified live; the feed renders. Deploy config ready (Vercel + Neon
  pooler). Tracker: `specs/prototype/00-build-spec-plan.md`.
- **Blocked only on operator gates for literal G1a:** **INB-12** (create Vercel +
  Neon → deploy, recipe in inbox) · **INB-14** (Android SDK/device; iOS = Mac).
- **✅ DONE + MERGED — `TASK-KMP` (apps/client → true KMP module).** Merged to
  `main` 2026-06-19 (merge `0d621e5`, pushed to origin; feature branch deleted).
  Single Gradle build at `apps/` (8.11.1 + AGP 8.7.2); `:client` = KMP —
  commonMain holds all shared logic+UI+SQLDelight+ktor sync; **android + desktop
  + iOS** targets all build from it. `:androidApp` = thin app dep on `:client`
  (srcDir borrow + ContentStore/Main excludes GONE → TASK-SYNC step 2 unblocked).
  SyncClient → ktor `suspend`; driver via expect/actual `DriverFactory`. 17
  desktop tests + snapshots green; Android APK assembles; **both iOS targets
  compile + debug framework links** (iosX64/intel-sim dropped — granular alpha01
  lacks that publication). **Operator-gated remainder (DoD "run gated on Mac"):**
  Xcode iosApp project (Swift @main + signing + sim run) + iOS sync-config
  plumbing → folds into **TASK-SYNC**.
- **✅ DONE + MERGED — `TASK-SYNC` (offline-first DB-as-SoT, ADR 0020).** Merged to
  `main` 2026-06-19 (merge `13db28b`, pushed; branch deleted). Delivered **R1**
  (instant offline cold-start), **R2** (foreground poll ~45s + sync-on-resume),
  **R4** (unidirectional `network→DB→store→UI`, crash-safe cursor in `sync_meta`).
  `SyncClient`→transport (`fetchPage`); new **`SyncEngine`** owns the mutex-guarded
  drain loop + DB→store bridge (`activeCardsFlow`→`CardsLoaded`) + poll lifecycle
  (`start`/`resume`/`pause`/`stop`, public `syncNow` = future push hook). Store is a
  pure DB projection (no network→store path); cursor removed from `AppState`. Desktop
  file DB + WAL; iOS native driver. **24 desktop tests green, Android APK assembles,
  iOS framework links.** Built subagent-driven (spec+plan in `docs/superpowers/`,
  3 review rounds + final whole-branch review). kotlinx-datetime bumped 0.6.1→0.7.1
  (`Clock`→`kotlin.time`). **OUT (deferred):** R3 background (WorkManager/BGTask),
  push (FCM/APNs/SSE), E2EE (ADR 0017), 2-way/outbox (ADR 0016), iOS sync-config,
  the `payload`/`$defs` richer card fields. **ADR 0020 still marked *Proposed* —
  operator may flip to *Accepted* now that it's built.**
- **Deferred by design (operator, 2026-06-19): G1 content-authoring loop ("the
  brains") = much-later milestone; interim authoring = operator + Claude Code via
  the CLI.**
- **Still-queued gaps: G2 usefulness signal, the `payload` $defs, the
  Claude-Design triggers v2 (INB-13) + M3-Expressive upgrades.**

## Done this period

- Bootstrap (2026-06-18): scaffold, ADRs 0001-0004, validation fleet, board.
- Event Hubs design + block-level deep-linking (ADR 0006).
- Prototype scope locked (ADR 0007); design-first gate added (ADR 0008).
- Inbox swept: INB-1/2/4/5/6/7/8 answered; INB-3 pending operator.
- Design system = M3 Expressive, adaptive (ADR 0009); design brief shipped;
  initial Now/Hubs/Auth mockups added; repo public on GitHub.
- Auth/family/invite architected (ADR 0010) → **5-agent review**
  (`research/design-review-auth-2026-06.md`) → **hardened (ADR 0011
  supersedes 0010)**: all-invites-owner-approved, email→push cut, device-
  grant anti-phishing, no-auto-link, per-request revocation, Firebase dedupe
  corrected, relational content tables. Spec + Hub schema hardened.

## Auth is now in ACTIVE BUILD (ADR 0021, supersedes the "later milestone" note)

Operator pulled auth forward 2026-06-19 (ADR 0021, extends 0011): build order
**S1→S3→S2→S4→S5/S6**. **AUTH-S1 (tenancy & token backbone) ✅ DONE + MERGED**
(`main` 5753b32): EdDSA tokens + refresh lineage + `authorizeTenant` (JWT + legacy
household path, default-deny, fail-closed, cross-tenant 404) + `/auth/{refresh,
signout}` + `POST /families` + JWKS + gated local-only dev-token. 51 tests + final
security review passed; legacy household token still works (cutover at S3). Details
+ carried debt (S3 refresh-grace, S4 per-family cred binding) in `backlog/next.md`.
**AUTH-S3 ✅ DONE + MERGED** (PR #2, `main` 1fc1918): RFC 8628 CLI device grant
(`/device/*` + owner approve + lazy-mint) + refresh ~20s grace + Kotlin CLI
`login`/`push`. Twice multi-agent-reviewed; CI green. Legacy household token still
works (removal gated). **UPDATE 2026-06-23:** S4 (owner-approved invites), S5
(sign-in/out + account flow), S6 (member roster, connected-devices, profile,
data-export, account soft-delete) all **DONE + MERGED** (PRs #4–#20); A8b auth
mockups imported (design gate cleared). **AUTH-S2 real Google path ✅ DONE +
MERGED** (PR #25). Full S1–S6 epic shipped. Prod deploy of the auth surface =
operator-gated (set `AUTH_*` env in Vercel).

## 2026-07-10 repo-maintenance pass

Scheduled, not a feature slice — one real CLI/skill-doc bug found + fixed, one
architecture-doc gap closed, `now.md` itself pruned. Same no-npm/no-Gradle-
registry-egress sandbox as every prior pass (re-confirmed:
`registry.npmjs.org` and `repo.maven.apache.org` both 403 via the proxy) —
so, consistent with every pass since 07-03, no *logic* changes were made to
`apps/api`/`apps/cli` (no way to compile-verify them here); the **still-open
`apps/api` code-dedup queue** (`requireSession` helper, `hubs.getVisibleHub`,
`app.ts` route-splitting — see `backlog/next.md`) stays deferred to a
build-capable environment for the same reason as the last 5 passes. **CI:
confirmed GREEN live via the GitHub Actions API** (latest run on `main`, #692,
`success`; spot-checked the last 15 runs across `ci.yml`/`release-android.yml`/
`secret-scan.yml`, all green) — nothing to fix. Added one operator action
that had fallen through the cracks: **enable branch protection on `main`
requiring the CI check before merge** (the 07-05 outage landed without
waiting on its own CI result; see Operator actions in `now.md`).
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
repo table/screenshots didn't need a change). **Simplified `now.md`:**
`backlog/now.md` had grown to 283 lines by re-stacking every repo-maintenance
pass's full paragraph under one old header instead of pruning to history,
working against its own stated "kept short on purpose" design
(self-inflicted context-usage cost, on-topic for this pass's "optimize for
agentic development" ask) — moved the 2026-07-03/05/06/07 maintenance-pass
paragraphs into this file (verbatim, nothing lost) and collapsed the two
stacked "Current state" headers into one (now.md: 283→~140 lines). Reviewed
`CLAUDE.md`/`AGENTS.md`/`processes/agent-routing.md`/`processes/agent-dev-
loop.md` again with fresh eyes for agentic-context-usage opportunities beyond
the now.md fix above: still lean (each already scopes itself — e.g.
`agent-dev-loop.md`'s Compose/KMP section is skippable for CLI-/API-only
work, `AGENTS.md` is a 27-line pointer) — no further changes made. Remaining
CLI/skill-doc dedup (hub-timeline table, block-payload alias column,
checklist id-stamping note repeated across `SKILL.md`/`references/
cli.md`/`references/content-model.md`/`templates/README.md`/`USAGE`) left
as-is per 07-09's judgment — each copy is short and partly intentional for
`templates/README.md`'s standalone readability. Values/privacy spot-check
clean: this pass's diff is docs + one CLI help-text string (`docs/
architecture.md`, `backlog/now.md`, `backlog/now-history.md`, `.claude/
skills/dayfold-curator/references/{cli,content-model}.md`, `apps/cli/.../
Main.kt` USAGE only) — no secrets, no PII, no child-account or
restricted-scope-Gmail surface touched.

**2026-07-18 repo-maintenance pass** (scheduled — the 12th in this series;
prior passes above). Same no-npm/no-Gradle-registry-egress
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
