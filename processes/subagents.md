# Process — Subagents (roster, placement, invocation)

Named Claude Code subagents for the recurring work this repo does, and the
rules for adding one. Definitions live in `.claude/agents/*.md` (project) and
`~/.claude/agents/` (user; source copy in `processes/claude-home/`). Why this
set and not another: `research/2026-09-02-subagent-practices-review.md`.

**The project definitions are generated.** They come from the SloopWorks
factory agent pack (org hub `factory/agents/roles/`), installed here as
`.agents/roles/*.md` (harness-neutral, what Codex and Grok Build read via the
generated block in `AGENTS.md`) and `.claude/agents/*.md` (the same contracts
with Claude Code frontmatter). They are parameterized: every path, verify
command and gate resolves through `.agents/profile.yml`, the one file this repo
owns. Repo-specific review knowledge that no generic role can carry — the lane
map, the deterministic guards, the known-failure catalog, the ADR map, the
privacy threat model — lives in `processes/repo-review-contract.md`.

Three agents below are still **repo-local, hand-written** files, because the
pack's `app` role set does not include them: `api-verifier`, `research-verifier`
and `viability-skeptic`. The installer leaves them alone.

## Roster (project: `.claude/agents/`)

| Agent | Invoke when | Model / turns | Writes? | Returns |
|---|---|---|---|---|
| `adversarial-reviewer` | Round-1 correctness on a spec/plan/ADR before build and on the whole branch before a PR. **Always** part of `/two-round-review`. | opus · high effort · 40 | no | verdict + P0/P1/P2 with `path:line` + fix; memory of defect classes (`.claude/agent-memory/adversarial-reviewer/`) |
| `simplification-reviewer` | Round 2, only after round-1 fixes are applied. | opus · high · 30 | no | SHIP-AFTER-TWEAKS / NEEDS-RESTRUCTURE + ordered tweaks |
| `compose-ui-reviewer` | Any diff touching composables, theme, navigation, snapshot scenes; specs that define UI. | opus · high · 40 | snapshot PNGs only | SHIP / FIX-FIRST across UX, Compose, M3, a11y, redux-kotlin binding, reachability, goldens |
| `privacy-security-reviewer` | Any diff touching `apps/api`, auth, sync, migrations, telemetry/error reporting, calendar, content ingestion, or a CLAUDE.md guardrail. | opus · high · 40 | no | SHIP / FIX-FIRST / ESCALATE-TO-OPERATOR; each finding has exploit + fix + proving test |
| `kmp-verifier` | After edits under `apps/client`, `apps/ui`, `apps/swip-wiring`, `apps/androidApp`. | sonnet · 25 | no | GREEN/RED/UNVERIFIED table per lane (desktopTests, migration guard task, expect/actual, console, `:androidApp:assembleDebug`, iOS compile where a Mac exists), failing tests from JUnit XML, guard findings |
| `api-verifier` | After edits under `apps/api`, `packages/`, `specs/domain-model/schemas`. | sonnet · 25 | no (restores tree) | same shape; mirrors CI's `api` job (routine-schema, codegen idempotence, bundle drift, vitest, migrations) |
| `doc-drift-auditor` | The repo-maintenance pass; after changes to CLI commands, toolchain pins, CI, or module layout. | sonnet · 40 | no | table of stale claims with evidence + exact replacement; memory of drift hot spots |
| `ci-doctor` | A PR or `main` goes red. | sonnet · 30 | no | failing job → root cause → fix / justified single re-run, from the known-failure catalog |
| `research-verifier` | Research fleets (one per claim domain); re-verifying research > ~6 months old. | sonnet · 40 · web | no | JSON verdicts per claim with consulted URLs |
| `viability-skeptic` | P0 viability review, gate decisions, pricing pressure-tests, any research synthesis before it becomes an ADR. | opus · high · 40 · web | no | JSON: fatal risks, weak assumptions, inconsistencies, re-run arithmetic, cheap kill checks |

### Added by the factory pack (build/plan/ship stages)

| Agent | Invoke when | Writes? | Returns |
|---|---|---|---|
| `brief-shaper` | First, on any new ask — before design, planning or code. | docs | a work-item brief + the roles the item must pass |
| `design-director` | A visible surface needs a spec before build (ADR 0008). | docs | design brief → spec + mockup prompt |
| `plan-author` | A shaped brief needs an implementation plan. | docs | an ordered plan with a checkable DoD |
| `kmp-implementer` | Building client/UI work from an accepted plan. | code | the change + the verify evidence |
| `swip-instrumenter` | Adding or changing instrumentation. | code | schema + wiring + the sanitizer leak test |
| `release-shepherd` | Shipping a dev build or preparing a store release. | ops | the release steps actually run, and what is operator-gated |

Two behaviours the pack versions do **not** carry yet, both tracked as hub
feedback: the `memory: project` frontmatter that gave `adversarial-reviewer`
and `doc-drift-auditor` a cumulative defect-class / drift-hot-spot memory under
`.claude/agent-memory/`, and the repo-specific catalogs those agents used to
inline — now in `processes/repo-review-contract.md`, which the profile points
at as `paths.review_contract`.

Every agent is **read-only on source** — a `tools` allowlist, `disallowedTools:
Edit, Write, NotebookEdit`, or both (`ci-doctor`'s allowlist names only the
read-only GitHub MCP tools plus `Bash` for `gh`). The main agent applies fixes — the diff stays human-visible and the
author/reviewer separation the process demands stays real.

Deliberately **not** agents (a skill, a session, or a Routine fits better):
- **ADR drafting, end-of-session close-out, the maintenance pass** — these
  need the conversation's context; make them skills (follow-ups in
  `backlog/now.md`). ADR drafting should carry
  `disable-model-invocation: true` (operator-gated side effect).
- **PR babysitting** — a background session or Routine, not a subagent.
- **Implementer / planner** — plan mode + the `superpowers` plugin's
  `subagent-driven-development` already cover this; the roster above plugs
  into its review slots rather than replacing it.
- **Explore / search** — the built-in `Explore` agent.

## Invocation

- **Explicit beats auto.** Auto-delegation by `description` is a hint, not a
  gate; it has regressed across Claude Code versions. Anything that *must*
  happen (the review gate, the verify step) is invoked by name: the
  `two-round-review` skill, `processes/build-loop-prompt.md` steps 2/4/5, or
  `@"kmp-verifier (agent)"` / "use the kmp-verifier agent".
- **Give every call an objective, the inputs the agent asks for, and the
  output shape** — subagents start with zero conversation context. They do
  receive `CLAUDE.md` (all levels) and git status; do not paste those.
- **Parallel where independent, sequential where not.** Round-1 reviewers run
  in one message; round 2 waits for fixes. Never two agents editing the same
  files (none here edit, by design).
- **Results are summaries.** Each agent caps its return (≤300–700 words) so
  seven agents cannot blow the parent context. Long raw output (fleet
  outputs) is archived under `research/<topic>-agent-outputs/` by the caller.
- Other harnesses (Codex, Cursor) do not load `.claude/agents/`; paste an
  agent file's body as a fresh-context system prompt with the same inputs.

## Placement: project vs user level

Precedence when names collide: managed > `--agents` flag > **project
`.claude/agents/`** > **user `~/.claude/agents/`** > plugin.

- **Project** — anything naming repo paths, pinned commands, ADR numbers, or
  the CI failure catalog. All of the above; the pack-generated ones get that
  specificity from `.agents/profile.yml` plus
  `processes/repo-review-contract.md` rather than from hard-coded text.
- **User** (`processes/claude-home/agents/`, installed by its `install.sh`) —
  generic `adversarial-reviewer`, `simplification-reviewer`,
  `research-verifier`, `doc-drift-auditor`, `ci-doctor` for every other repo
  on the machine. Same names on purpose: inside dayfold the project version
  wins; elsewhere the generic one applies. Keep them small — every loaded
  description costs context in every session and the router degrades when
  descriptions overlap.
- Cross-machine sync is a dotfiles concern (chezmoi or plain git + symlinks);
  `processes/claude-home/README.md`.

## Conventions for adding or editing an agent

0. **Never hand-edit a generated role file.** For a pack role, change
   `factory/agents/roles/<role>.md` in the org hub, run
   `python3 factory/agents/install.py --reindex`, then re-run
   `factory/agents/install.sh --target . --kind app` here and commit the result.
   `install.py --target . --kind app --check` reports drift and belongs in CI.
   If the change is repo-specific rather than role-specific, it belongs in
   `.agents/profile.yml` or `processes/repo-review-contract.md` instead.
1. One responsibility; the `description` states the **trigger** ("Use
   PROACTIVELY when …", "Use AFTER …"), the inputs it needs, and what it
   returns — a router rule, not a job posting. Keep it under ~4 lines.
2. Read-only unless there is a reason; `maxTurns` always set (runaway
   research agents are a documented failure); `model:` pinned by role —
   opus for judgment (review, security, viability), sonnet for run-and-parse
   (verify, audit, triage), haiku only for pure formatting.
3. Body ≤ ~750 words: mandate, inputs, method, repo-specific traps, **exact
   output shape** with a word cap, rules. Reference rules in `CLAUDE.md` /
   ADRs by name — never restate them (double-loads the context).
4. Verdict first; every finding carries `path:line`; never claim a lane green
   that did not run (`UNVERIFIED` is an answer).
5. `memory: project` only where accumulated repo knowledge pays
   (`adversarial-reviewer`, `doc-drift-auditor`); the agent must be told to
   read `MEMORY.md` first and keep it short. Memory is working memory —
   repo Markdown wins on conflict (CLAUDE.md §Memory governance).
6. Add the row here. No `README.md` inside `.claude/agents/` — the directory
   is scanned recursively and every `.md` is treated as an agent.
   `doc-drift-auditor` audits this file too.

## The rest of the harness config

- **`.claude/settings.json`** (the file is the list; this is the rationale) —
  `allow` pre-approves the read-only inspect/verify commands the agents and
  build loop run, so fewer prompts without widening anything; `ask` marks
  the irreversible or outward-facing commands (force-push, tags — they
  trigger releases —, releases, deploys, prod migrations, `dayfold` writes).
  Deliberately **not** on `git push` or `gh pr merge`: the build loop pushes
  and merges-when-green on its own (`processes/build-loop-prompt.md`, ADR
  0012) and this pass does not change that autonomy. `deny` covers secrets
  files and edits to the operator-owned values file.
  `.claude/settings.local.json` is the per-machine override (gitignored).
- **`scripts/claude-hooks/edit-guards.sh`** (PostToolUse on Edit/Write) —
  one-line reminders for the drift classes that have shipped to `main`; each
  `case` arm in the script is one class and the script is the canonical
  list. It only prints (exit 2 feeds the text back to Claude); it never
  blocks or edits. Self-test:
  `printf '{"tool_input":{"file_path":"'"$PWD"'/apps/api/src/app.ts"}}' | bash scripts/claude-hooks/edit-guards.sh`
  (should print the bundle reminder and exit 2).
- **Skills** — `.agents/skills/` is the harness-neutral source;
  `.claude/skills/*` are symlinks. `two-round-review` orchestrates the
  reviewers; `dayfold-curator` authors content.

## Cost and calibration

Reviewer calls on opus at high effort cost real money; a full
`/two-round-review` on a branch is typically 2–4 agents. The verifiers exist
to *save* context (a Gradle log is ~160 KB) and run on sonnet. First-run
calibration is a follow-up: after ~10 real invocations, revisit `maxTurns`,
model, and whether any agent's description ever fired on its own.

**Canary (run once per Claude Code upgrade):** ask a project agent "quote
the first heading of CLAUDE.md" — it must answer from the file, not
paraphrase. Subagents skipping `CLAUDE.md` has happened before and the agents
here rely on it for the guardrails.
