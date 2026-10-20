# Process — Repo review contract (what the generic agent pack cannot know)

The reviewer/verifier agents in `.claude/agents/` and `.agents/roles/` are
**generated** from the SloopWorks factory agent pack (`factory/agents/` in the
org hub) and are deliberately parameterized: they resolve module paths, verify
commands and gates from `.agents/profile.yml` so the same contract works in
every SloopWorks repo.

What a parameterized role *cannot* carry is this repo's accumulated knowledge:
which ADR binds which review dimension, which failure classes have already
shipped to `main`, and which lane to run for which changed path. That is this
file. **Every verify or review role must read it before reporting** — the
profile points here as `paths.review_contract`.

Canonical copies live elsewhere where one already exists; this file points
rather than duplicates. Toolchain, per-area loops and snapshot commands are
`processes/agent-dev-loop.md`. Roster and invocation are `processes/subagents.md`.

## 1. Lane map — pick by changed path

| Changed | Run (from `apps/` unless noted) |
|---|---|
| `apps/client/**` logic, reducers, engines, db | `./gradlew :client:desktopTest` |
| `apps/ui/**` composables, theme, scenes | `./gradlew :ui:desktopTest` (recompiles `:client` first) |
| `apps/swip-wiring/**` or any new state slice | `./gradlew :swip-wiring:desktopTest` — mandatory sanitizer leak test (ADR 0054) |
| any `.sq` / `.sqm` | `./gradlew :client:verifyCommonMainContentDbMigration :client:desktopTest` — the guard is a **separate task**, not part of `desktopTest`, and CI invokes it explicitly |
| any commonMain `expect` | `bash scripts/check-expect-actual.sh` (repo root) |
| any client Kotlin | `bash scripts/check-no-direct-console.sh` |
| `apps/androidApp/**` or shared code it uses | `./gradlew :androidApp:assembleDebug` (CI also runs `:androidApp:bundleRelease`) |
| shared code with iOS `actual`s, `apps/iosApp` | `./gradlew :ui:compileKotlinIosArm64` — macOS + Xcode only; elsewhere report `UNVERIFIED (iOS needs macOS)` |
| `apps/api/**`, `packages/**`, `specs/domain-model/schemas` | the API lane in §2 |

`JAVA_HOME` must be JDK 17. Cap one Gradle invocation at ~15 minutes; a hang
with no failures is its own finding. Read results from **JUnit XML**
(`bash scripts/ci-test-failures.sh apps`), never from the console, and report
the test count per module — a count that did not move means a test did not run.

## 2. API lane — order matters

1. `npm run test:routine-schema` — routine contract schemas + fixtures.
2. Codegen idempotence: `npm run codegen` then
   `git diff --exit-code apps/api/src/generated packages/schema/kotlin-gen/Content.kt`.
3. Bundle drift: `cd apps/api && npm run build:fn && git diff --exit-code api/index.js`
   — the committed esbuild artifact **is** the Vercel function.
4. `cd apps/api && npx vitest run` — needs `DATABASE_URL` with migrations applied.
5. Migrations: new `apps/api/migrations/NNNN_*.sql` sequential, no number
   collision, ADR 0033 runner semantics (idempotent, re-run-safe), no grant widening.

**Before** steps 2–3, record
`git status --porcelain apps/api/src/generated packages/schema/kotlin-gen apps/api/api/index.js`.
Already dirty → the caller has uncommitted regenerated output: do not touch it,
report "pre-existing uncommitted generated changes — re-verify after commit".
Clean before and dirty after → report `DRIFT` with `git diff --stat`, then
`git restore` those paths so the tree is left as it was found.

No Postgres → the vitest lane is `UNVERIFIED`; the codegen and bundle lanes
still run. No npm egress and no `node_modules` → `UNVERIFIED`, rely on CI.
Never report green for a lane that did not run.

## 3. Deterministic guards (no build needed)

- A `.sq` schema change with no companion `.sqm` migration or no `Schema.version`
  bump strands existing devices. `ALTER TABLE` appends only — **new columns go at
  the END** of the table in `Content.sq`.
- `@Test fun x() = runBlocking { … }` whose last expression is not `Unit` is
  silently never run. Demand `runBlocking<Unit>`; check that test counts moved.
- "Built but not wired": a new `Route` / `*Screen` / `*Host` / `*Action` with no
  production dispatcher or call site is a P0 unless `ReachabilityGuardTest` has a
  dated allow-list entry with a reason.
- Goldens re-recorded under `snapshots/macos/` but not `snapshots/linux/` (or the
  reverse) goes red in CI — CI runs Linux.
- A schema edit without the `npm run codegen` output committed, or an
  `apps/api/src` edit without the rebuilt `apps/api/api/index.js`.
- A commonMain `expect` with no `iosMain` `actual` — CI does not compile iOS.

## 4. Known-failure catalog (CI triage)

This table is canonical; `backlog/now.md` holds the incident narratives. Add a
row here when a new class lands there.

| Symptom | Cause | Action |
|---|---|---|
| Compose job hits the **18-min step cap**, XML shows 0 failures, tests still executing | runner-side hang; reproduces on docs-only commits | **one** re-run is justified; twice means report, not loop |
| `GoldenSnapshotTest` mismatch only on CI | macOS goldens re-recorded, `snapshots/linux/` not | record the Linux set (docker recipe in `agent-dev-loop.md`) |
| "generated TS types are stale" / "Content.kt stale" | schema changed, `npm run codegen` output not committed | run codegen, commit both outputs |
| "committed Vercel bundle … is stale" | `apps/api/src` changed, `api/index.js` not rebuilt | `cd apps/api && npm run build:fn`, commit |
| `Gem::FilePermissionError` in Play upload | system Ruby on the runner | `GEM_HOME` under `$RUNNER_TEMP` (already fixed — if back, the step regressed) |
| `verifyCommonMainContentDbMigration` fails | `.sq` changed without `.sqm`, or a column added mid-table | add the `.sqm`; new columns at END |
| `ReachabilityGuardTest` fails | new Route/Screen/Action with no production caller | wire it, or add a dated allow-list entry |
| "0 failed" but a new test is missing from XML | `= runBlocking { }` returning non-Unit | `runBlocking<Unit>` |
| `check-expect-actual.sh` fails | commonMain `expect` without `iosMain` `actual` | add the actual |
| `check-no-direct-console.sh` fails | raw `println` / `android.util.Log` | route via `com.sloopworks.dayfold.client.Log` |
| API job fails before tests | `npm ci` needs `NODE_AUTH_TOKEN` (GitHub Packages) | secret/permissions, not code |
| Red on `main` too | not this PR's failure | say so; find the fixing PR/commit to port |

Resolve CI state **per check run**. A `check_suite.completed / success` event on
this repo is usually **gitleaks** (5–9 s), not CI — never call a PR green from it.
"Re-run" is allowed only for the hang above, a job that died before any test body
ran, or a commit that passed earlier — at most once.

## 5. ADR map — which decision binds which review dimension

| Dimension | ADRs |
|---|---|
| Design-first (a mockup exists before build) | 0008 |
| Design system, Material 3 Expressive, a11y/WCAG-AA | 0009 |
| Typed content, detail view, fold gesture | 0022 |
| Auth + family tenancy | 0011 |
| Per-member visibility, restricted resources fail-closed | 0030 |
| Migration management (tracked, idempotent, verified) | 0033 |
| External image URLs — HTTPS host allowlist, hardened parser | 0036 |
| Navigation taxonomy + central route-motion host | 0051 |
| Telemetry / bug reporter / logging / error reporting | 0054, 0056, 0059, 0060 |
| Client runtime and effect ownership (redux-kotlin discipline) | 0058 |
| Calendar data is device-local | 0063 |
| Content-blind server, synced mute/done on a subjectRef | 0064 |

Client-binding specifics a UI review must check (ADR 0058): one `SelectorStore`
per Compose root via `rememberSelectorStore(rawStore)`; `store.selectorState {}` /
`fieldState()`; **never** `StableStore.value`; the keyed selector overload for
projections capturing changing Compose values; hosts pass `DayfoldCommandPort`;
UI dispatches, never mutates. The same rules are injected into Shipyard dispatch
prompts through `.shipyard.yaml` `constraints:` — when one moves, move both.

A fresh clone must initialise the debug-drawer submodule before any Gradle
lane runs: `git submodule update --init third_party/debugdrawer`.

## 6. Privacy threat model and escalation

Review a privacy-touching change as three attackers in order: a member of
**another family**, a member of the **same family with lower visibility**, then a
**compromised author key**. The strictest line is ADR 0063 — raw calendar events,
identifiers, account names, fingerprints, selections and match decisions must
never reach sync payloads, server APIs, logs, analytics, bug reports or crash
payloads, and any calendar-touching change ships a test that proves it.

A change that touches a CLAUDE.md hard guardrail (children's data, pricing,
restricted-scope/LLM data handling, the customer-relationship line, messaging
consent, spend/legal) is a **P0** and the verdict is **ESCALATE-TO-OPERATOR**
regardless of code quality. `gates.human_gate_when` in `.agents/profile.yml`
lists the same set.
