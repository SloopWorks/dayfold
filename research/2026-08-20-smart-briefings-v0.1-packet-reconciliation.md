# Smart Briefings V0.1 Claude Bridge — packet reconciliation report

**Date:** 2026-08-20

**Purpose:** Gate-table row 1 ("Read/reconcile packet") completion evidence for
Phase A of `specs/smart-briefings-v0.1/CLAUDE-HANDOFF.md`. This audits what the
packet **claims** against what the repository **actually contains** at commit
`c4ef5ee3` (both `main` and `codex/v0-1-claude-handoff`). It does not re-run the
recorded two-round adversarial review, does not re-argue architecture, and does
not edit any packet file.

**Files read and cross-checked:**

- `specs/smart-briefings-v0.1/CLAUDE-HANDOFF.md`
- `specs/smart-briefings-v0.1/system-design.md`
- `adr/0071-self-managed-claude-bridge-v0.1.md`, `adr/decisions-index.md`
- `adr/0061-cloud-routine-private-content-boundary.md`,
  `adr/0062-routine-observability-and-recovery-telemetry.md`
- `docs/superpowers/plans/2026-08-20-smart-briefings-v0.1-claude-bridge.md`
- `docs/superpowers/plans/2026-08-07-routine-integration-safe-slice.md`
- `designs/PROMPT-smart-briefings-v0.1-claude-bridge.md`
- `packages/routine-schema/` (`index.mjs`, `package.json`, `test.mjs`),
  `specs/domain-model/schemas/routine-*.schema.json`,
  `specs/domain-model/examples/routines/**`
- `apps/cli/src/main/kotlin/RoutineContract.kt`, `RoutineDiff.kt`
- `apps/api/migrations/` (0001–0021), `apps/api/package.json`, root `package.json`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/routines/`,
  `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/routines/`
- `processes/agent-dev-loop.md`, `CLAUDE.md`, `backlog/now.md`,
  `backlog/next.md`, `backlog/operator-inbox.md`
- `research/2026-08-20-smart-briefings-v0.1-adversarial-review.md` (read only to
  confirm the review-completion rule; not re-reviewed)

## Verdicts on the nine named items

1. **Migration numbers 0022/0023/0024 vs. highest on disk.** Correct, no
   conflict. `apps/api/migrations/` tops out at `0021_unique_done_subject.sql`
   (verified by directory listing). The plan's target layout
   (`docs/superpowers/plans/2026-08-20-smart-briefings-v0.1-claude-bridge.md:115-117`,
   repeated at `:246,:260,:274`) reserving `0022_connector_oauth.sql`,
   `0023_routine_installations.sql`, `0024_routine_runs_proposals.sql` is the
   correct next sequence today. No other repository file reserves those numbers
   for something else. Not a finding.

2. **`packages/routine-schema/schemas/` + `fixtures/v2/` layout vs. where the
   package actually loads schemas from today.** Mismatch — see **F1** below.

3. **ADR index numbering 0066–0070 and whether ADR 0071's number is safe.**
   The narrow question is safe, but the broader "unused headroom" framing this
   report originally gave it is not — see **F6**. `git rev-list --all --objects
   -- 'adr/0071*'` confirms `adr/0071-self-managed-claude-bridge-v0.1.md` is a
   single, unique blob across every ref in the repository, referenced only from
   the current commit tree (`c4ef5ee3`) — no other branch has a colliding
   `adr/0071*` file, so ADR 0071's own number is safe to use. `adr/decisions-index.md`
   jumps directly from row 0065 to row 0071 (`adr/decisions-index.md:81-82`) and
   no committed `.md` file in the current checkout claims 0066–0070. But
   `ls adr/`, `decisions-index.md`, and a grep of the current checkout only see
   what's on this one branch — ADR numbers are not centrally coordinated across
   the repository's 437 local/remote branches (`git branch -a | wc -l`), and the
   number immediately below 0071 has concrete, verified evidence of exactly this
   risk. Not a finding on ADR 0071 itself; **is** a finding on the numbering
   process — see F6.

4. **Whether ADR 0061 / ADR 0062 statements conflict with ADR 0071, and which
   exact clauses the "narrow/replace" instruction has to touch.** Real,
   unresolved conflicts exist and are not itemized anywhere in the packet —
   see **F3** below.

5. **Whether `docs/superpowers/plans/2026-08-07-routine-integration-safe-slice.md`
   is superseded, partially live, or already built.** Already built, and
   correctly slated for preservation, not superseded. That plan's own
   "Implementation result" section
   (`docs/superpowers/plans/2026-08-07-routine-integration-safe-slice.md:511-528`)
   states it shipped 2026-08-07: schema/CLI contract package, pure KMP state
   machine, adaptive Compose preview, SWIP exclusion canary, and macOS/Linux
   golden baselines, verified, with named device-level follow-ups (installed
   Android golden path, TalkBack/RTL, no reachable iOS routine screen) left
   open. It is an in-app, no-network, "nothing connects or saves" preview —
   never a live provider/OAuth/credential/write path
   (`...safe-slice.md:518-520`). The 2026-08-20 parent plan correctly treats it
   as V1 shadow history to preserve, not delete
   (`docs/superpowers/plans/2026-08-20-smart-briefings-v0.1-claude-bridge.md:74-79`).
   Not a finding.

6. **JDK/toolchain: parent plan §14 vs. `processes/agent-dev-loop.md`
   pin.** Consistent. The plan's verification block uses the placeholder
   `JAVA_HOME=<jdk17>`
   (`docs/superpowers/plans/2026-08-20-smart-briefings-v0.1-claude-bridge.md:623-625`),
   and `processes/agent-dev-loop.md:23-26` pins JDK 17 via the version-independent
   Homebrew symlink and uses the identical `<jdk17>` placeholder convention
   elsewhere in the same file (e.g. `:182-184`, `:315`, `:327`). No mismatch.
   Not a finding.

7. **Whether `backlog/now.md`, `CLAUDE.md`'s directory map, and
   `adr/decisions-index.md` reflect this work at all.** Partially, and
   correctly so given ADR 0071's Proposed/operator-gated status.
   `adr/decisions-index.md:82` carries the ADR 0071 row. `backlog/next.md:15-39`
   carries `TASK-SMART-BRIEFINGS-V0.1`, and `backlog/operator-inbox.md:19-40`
   carries `INB-39` (ratify the pilot), both pointing at the same packet files.
   `backlog/now.md` — the "current active work" file — has no mention of it
   (grepped, zero hits), which is the correct placement discipline for gated,
   not-yet-authorized work (it belongs in `next.md`/`operator-inbox.md`, not
   `now.md`). `CLAUDE.md`'s "Current stage" narrative and directory map do not
   mention ADR 0071, `specs/smart-briefings-v0.1/`, or `apps/mcp-bridge` —
   expected, since `CLAUDE.md`'s own end-of-session routine only requires
   updates for durable/Accepted decisions, and ADR 0071 is still Proposed. Not
   a finding.

8. **V1 routine artifacts already in the repo, and exactly which of them
   WP1's V2 schema freeze would shadow rather than replace.**
   - `packages/routine-schema/` + `specs/domain-model/schemas/routine-*.schema.json`
     (V1 `routine-manifest`/`routine-changeset`/`routine-run-finish`): correctly
     shadowed. The parent plan explicitly names these "V1 shadow history" to
     preserve (`docs/superpowers/plans/2026-08-20-smart-briefings-v0.1-claude-bridge.md:74-75`).
     The layout claim for *where the new V2 files land* is wrong — see **F1**.
   - `apps/cli/src/main/kotlin/RoutineContract.kt` + `RoutineDiff.kt` (the CLI
     `changeset validate`/`diff` commands over the V1 multi-operation model):
     relationship to V2 is **undocumented** — see **F2**.
   - `apps/client/.../features/routines/{RoutineModel,RoutineReducer,RoutineActions,RoutineSelectors}.kt`
     and `apps/ui/.../features/routines/SmartBriefingsPreviewScreen.kt` (the
     synthetic preview/development gallery): correctly shadowed. WP7 explicitly
     says to keep the synthetic preview and "extend the routine feature with
     live, tenant-fenced models" alongside it, never letting "fake actions/state
     implement live authority"
     (`docs/superpowers/plans/2026-08-20-smart-briefings-v0.1-claude-bridge.md:502-505`).
     Not a finding.

9. **"No new review opened."** Confirmed — see closing statement below.

## Findings

| ID | Severity | Packet claim (file:line) | Repository reality (file:line) | Recommended reconciliation | Who decides |
|---|---|---|---|---|---|
| F1 | material | `docs/superpowers/plans/2026-08-20-smart-briefings-v0.1-claude-bridge.md:74` ("Preserve: `packages/routine-schema/` fixture/test patterns") and `:119-122` (target layout: `packages/routine-schema/schemas/routine-proposal-v2.schema.json`, `schemas/routine-run-finish-v2.schema.json`, `fixtures/v2/{valid,invalid}/`) | `packages/routine-schema/` today contains only `index.mjs`, `package.json`, `test.mjs` (no `schemas/` or `fixtures/` subdirectory). V1 schemas load from `specs/domain-model/schemas/*.schema.json` via `packages/routine-schema/index.mjs:6-7,25` (`ROOT`/`SCHEMA_FILES` resolution); V1 fixtures ("examples") live at `specs/domain-model/examples/routines/{valid,invalid}/` (`EXAMPLES` const, `index.mjs:7`), not under `packages/routine-schema/` at all. | Correct the plan's "Preserve"/target-layout wording: V1 schemas/examples resolve from `specs/domain-model/`, not `packages/routine-schema/`. Note that a new `packages/routine-schema/{schemas,fixtures}/v2/` tree is additive and needs its own loader — `index.mjs`'s `compileContracts()` is hardcoded to the three V1 `SCHEMA_FILES` and won't pick up V2 files automatically. | agent (factual documentation correction; no scope/pricing/legal implication) |
| F2 | material | `specs/smart-briefings-v0.1/CLAUDE-HANDOFF.md:33` lists `apps/cli/src/main/kotlin/RoutineContract.kt` as an "Existing implementation reference" to read; no other packet file (`system-design.md`, `adr/0071-*.md`, the 2026-08-20 plan, the hi-fi prompt) mentions it again | `RoutineContract.kt` (+ `RoutineDiff.kt`) backs the CLI's `dayfold changeset validate/diff` commands over the V1 multi-operation `RoutineManifest`/`RoutineChangeset` model (`RoutineContract.kt:48-76`), including `CARD_KINDS = {action, info, weather, countdown}` (`RoutineContract.kt:17`) — a different shape from V0.1's single-card V2 proposal, whose `kind` is `action\|info\|countdown` with `weather` dropped (`specs/smart-briefings-v0.1/system-design.md:326`, plan `:221`). No CLI work appears anywhere in WP0–WP9 of the 2026-08-20 plan. | State explicitly that `RoutineContract.kt`/`RoutineDiff.kt` and the CLI changeset commands are orthogonal V1 tooling, untouched by this pilot, so a WP1 implementer doesn't have to guess whether the CLI needs a V2 counterpart. | agent (documentation clarification); CLI V2 parity, if ever wanted, is a scope call for the operator |
| F3 | material | `adr/0071-self-managed-claude-bridge-v0.1.md:15` ("this narrows/replaces the V0.1 portions of Proposed ADR 0061") and its acceptance gate #5 (`adr/0071-self-managed-claude-bridge-v0.1.md:235`, "Proposed ADR 0062 is narrowed/replaced where incompatible") | `adr/0061-cloud-routine-private-content-boundary.md` never uses the string "V0.1" (zero grep hits) — there is no ADR-internal "V0.1 portion" to point at. Concretely: ADR 0061 §4 mints a 5-minute access token from an enrolled Ed25519 client assertion and states "There is no refresh token" (`adr/0061-cloud-routine-private-content-boundary.md:96`); ADR 0071 §3 plus `system-design.md` §10 instead specify OAuth Authorization-Code+PKCE with an "opaque rotating refresh token" (`specs/smart-briefings-v0.1/system-design.md:388`) and a `connector_refresh_tokens` table, 45-day max (`system-design.md:412,427`) — an incompatible credential model for the same "external AI provider ↔ Dayfold automation" seam. Separately, ADR 0062 §4 is written entirely in terms of "the K3 gateway" needing its own generated SWIP source (`adr/0062-routine-observability-and-recovery-telemetry.md:82`), a component ADR 0071 never builds; ADR 0071 §11 makes the analogous content-blind requirement for its differently-named "MCP Bridge" (`adr/0071-self-managed-claude-bridge-v0.1.md:65,178`), with no clause mapping one onto the other. | WP1 §5.1 ("Reconcile provider facts") should enumerate which ADR 0061 §4 and ADR 0062 §4 clauses are dead or renamed for the pilot, instead of leaving "V0.1 portions" and "where incompatible" as open-ended pointers. | operator (ADR-class architecture/credential-model reconciliation — automation-autonomy boundary per `CLAUDE.md`; agents may draft the reconciling text, operator accepts it) |
| F4 | minor | `docs/superpowers/plans/2026-08-20-smart-briefings-v0.1-claude-bridge.md:96-104` (target layout), `:350` ("Create `apps/mcp-bridge` as a separate build/deploy unit"), `:620` (`cd apps/mcp-bridge && npm test`) treat `apps/mcp-bridge` as a new package alongside `apps/api` | Root `package.json:14-17` `"workspaces": ["packages/*", "apps/api"]` — `apps/api` is a workspace member; `apps/mcp-bridge` (which doesn't exist yet) is never added and the plan never says to add it. | WP4 should state explicitly whether `apps/mcp-bridge` joins the root npm workspace (like `apps/api`) or stays a fully standalone npm project with its own lockfile. | agent (build-tooling detail, no ADR-class implication) |
| F5 | material | `specs/smart-briefings-v0.1/CLAUDE-HANDOFF.md:66` gate-table row: "Implement schemas/server/client with synthetic data \| no \| ADR 0071 + constants/hosting accepted \| WP tests and security review" — groups schema-writing together with server/client implementation, both gated on ADR 0071 acceptance | `docs/superpowers/plans/2026-08-20-smart-briefings-v0.1-claude-bridge.md:639-642` sequencing diagram places "WP1 final hi-fi + schemas" (the V2 schema freeze, plan §5.3) **before** "STOP for ADR 0008 + ADR 0071 decisions," and only places "WP2-6 server/bridge/publication implementation" **after** that same gate. The two packet documents disagree on whether writing the frozen V2 JSON schema files requires ADR 0071 to already be accepted. | Reconcile one of two ways: (a) move WP1 §5.3's schema freeze to occur after the ADR 0071 decision, matching the gate table; or (b) split "schemas" out of the gate table's bundled row and give it the same "after spike report / provider facts reconciled" gating as the adjacent "Generate final hi-fi" row (`CLAUDE-HANDOFF.md:65`), matching the plan's own sequencing diagram. | operator (this changes the sequencing/gating of a Work Package, an ADR-class scope decision per `CLAUDE.md`; agent may propose the fix, not decide it) |
| F6 | minor | This report's own item-3 verdict (prior revision) framed ADR numbers 0066–0070 as "unused headroom, not a defect," based only on `ls adr/`, `adr/decisions-index.md:81-82`, and a grep of committed `.md` files in the current checkout | `git rev-list --all --objects -- 'adr/006*'` shows `adr/0066-claude-cloud-session-scoped-cli-access.md` (blob `86384b79ab4e7fbb69757795d243928e47701e62`) really is committed, as `ca9b1daa` — "Design Claude cloud session CLI access (Proposed ADR 0066)", authored 2026-08-12 — and is reachable **only** from the real, unmerged remote branch `remotes/origin/claude/dayfold-cloud-integration-4m7av9` (`git branch -a --contains ca9b1daa`), one of 437 local/remote branches in this repo (`git branch -a \| wc -l`) that `ls adr/`/`decisions-index.md` never see. A second, differently-titled `adr/0066-mobile-release-channels-and-ios-distribution.md` blob (`69303c765195bd3b12e5955c8415639642da90e0`) also exists in the object database, but closer verification (`git rev-list --all` over commits only, then `git ls-tree -r` on each) shows it is **not** part of any real commit or branch — it lives only inside three local `refs/codex/turn-diffs/{captures,checkpoints}/...` tree-snapshot refs (confirmed `tree`-typed, not `commit`-typed, via `git for-each-ref "refs/codex/"`), i.e. Codex CLI's own local turn-diff/checkpoint bookkeeping for uncommitted work on this machine, not a second branch. | Before allocating any new ADR number, check `git branch -a` and `git log --all --oneline -- 'adr/00NN*'` across local *and* remote branches (`git fetch --all` first if remotes may be stale) — not just the current checkout's `adr/` directory and `decisions-index.md`. Treat local tool-generated ref namespaces (e.g. `refs/codex/**`) as a secondary, machine-local signal that uncommitted drafts can exist even outside real branches, not as a substitute for the branch check. | agent (a verification-discipline/process note for future ADR authors, not an ADR-class decision itself) |

No finding above is rated `blocking`: none of them affect Phase A (the only
work currently allowed), because Phase A is confined to building the isolated
local synthetic spike under `spikes/claude-mcp-v0.1/`, which per
`global-constraints.md` items 2 and 4 may not import anything from `apps/`,
`packages/`, or `specs/domain-model/` in the first place. F1-F5 matter starting
at WP1/Phase C-D, once implementation against `packages/routine-schema/`,
`apps/cli`, and ADR 0071/0061/0062 text actually
begins. F6 is a standing process caveat rather than a phase-scoped one: it
applies whenever any future session — in this plan or any other — next needs
to allocate a fresh ADR number, which could happen before, during, or after
this packet's own phases.

## No new review opened

This report performed textual/repository cross-checking only. It did not
re-run, extend, or second-guess the recorded two-round adversarial review in
`research/2026-08-20-smart-briefings-v0.1-adversarial-review.md` (see that
file's "Review completion rule," lines 121-126, which this report treats as
binding and does not revisit). No architecture, security, or product decision
was re-argued. No packet file (`adr/`, `specs/`, `docs/superpowers/plans/`,
`designs/`) was edited by this task.

## Items that are operator decisions, not agent decisions

1. **F3** — reconciling ADR 0071's credential model against ADR 0061 §4's
   "no refresh token" routine principal, and against ADR 0062 §4's "K3 gateway"
   diagnostics clause, is an automation-autonomy/architecture decision. The
   operator accepts or replaces ADR 0071 per its own acceptance gates
   (`adr/0071-self-managed-claude-bridge-v0.1.md:222-238`); an agent may draft
   the reconciling text but not decide it.
2. **F5** — whether the V2 schema freeze (WP1 §5.3) may proceed before or only
   after ADR 0071 is accepted changes the pilot's execution sequencing and is
   therefore an ADR-class scope/sequencing call, not an agent judgment call.
3. All five "Operator decisions" already enumerated in
   `specs/smart-briefings-v0.1/system-design.md` §18 (lines 568-576) remain
   unaffected and outstanding: accept/replace ADR 0071; authorize the external
   Claude compatibility test/preview deployment; ratify the source preset,
   retention table, value thresholds, separate Vercel bridge, and Gmail
   write no-go rule; select the private-data dogfood authority; keep
   non-operator use blocked until legal/privacy/deletion gates close. This
   report does not add to or narrow that list — it is restated here only so a
   fresh session doesn't mistake the findings above (F1-F6 — documentation,
   sequencing, and numbering-process corrections) for new items on that list.
