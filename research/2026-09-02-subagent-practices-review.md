# Subagent practices review — sessions, evidence, and the roster that follows (2026-09-02)

**Verdict: CONDITIONAL-GO on a narrow roster.** Ten project-level, read-only
subagents plus five generic user-level ones, invoked explicitly at the
existing process gates, are justified by the session record. A larger
persona library is not: the record shows a handful of recurring workflows,
and the practice literature is unanimous that many specialists degrade
delegation. Config lands with this report; a calibration pass after ~10 real
invocations is the open item.

Method and limits: the remote container holds only this session's transcript,
so "sessions" were reconstructed from **848 commits** (`git fetch --deepen`),
**300 pull requests** (#102–#403, created 2026-06-26 → 2026-09-02; #1–#101
not sampled), and the repo's own process/backlog docs. Practice claims were
gathered by two research subagents from the official Claude Code docs and
2025–2026 community sources; every claim below is labeled `[fact:source]` /
`[opinion:source]` / `[estimate]`. Raw agent outputs are archived in
`research/2026-09-02-subagent-practices-agent-outputs/`; §3 condenses them.

## 1. What the session record shows

### 1.1 Volume and tooling `[fact:git log, GitHub PR list]`

| Signal | Value |
|---|---|
| Commits reachable after deepen | 848 (2026-06-18 → 2026-09-02) |
| Co-Authored-By trailers | Claude Opus 4.8 (1M) 526 · Sonnet 5 24 · plain "Claude" 20 · Opus 4.8 17 · Sonnet 4.6 11 · Opus 5 / (1M) 18 · Fable 5 7 |
| Other harness markers in bodies | Cursor 64 · Codex 13 |
| PRs sampled | 300 (#102–#403); all authored by the operator account |
| PR branch prefixes | conventional `fix/feat/test/docs/*` 153 · `claude/*` 55 (one web-session slug alone: 14 PRs) · `codex/*` 16 · `iter-*` 16 (all 2026-06-26) · `scan-*` 9 (2026-06-27) · `worktree-*` 6 · `design/*` 4 |
| PR title class | docs 94 (31%) · other 64 (incl. **20 numbered repo-maintenance passes**) · fix 50 · feat 41 · test 41 · ci 4 · research 4 · design 2 |

Three harnesses are in real use (Claude Code web + local, Codex, Cursor) —
which is why the repo already keeps skills harness-neutral under
`.agents/skills/` `[fact:CLAUDE.md]`. Subagent files are Claude-Code-only;
the other harnesses get the same prompts by paste (see `processes/subagents.md`).

### 1.2 Recurring workflows (the things worth an agent) `[fact:repo docs]`

| Workflow | Evidence of recurrence | Existing prompt today |
|---|---|---|
| Two-round adversarial review (correctness → simplification) | 31 "adversarial review" mentions across process/backlog/spec docs; `operating-lessons.md` §5 calls it "the cheapest defect-removal step anywhere in the system" | `fleet-patterns.md` §3 prose; `build-loop-prompt.md` steps 2/5 say "launch a review subagent" with no named prompt |
| Security/privacy review | 21 "security review" mentions; ADR 0063 requires tests that *prove* calendar data never leaves the device; 9 `scan-*` PRs were a one-day security test sweep | none named |
| UI review (Compose / M3 Expressive / a11y) | `build-loop-prompt.md` "IF UI WORK" block; 4 `design/*` PRs; ADR 0008/0009 | none named |
| Verify lane (Gradle + vitest + codegen + bundle) | CI job structure; `now.md` documents a 160 KB log needed to find one failing test; `ci-test-failures.sh` was written for exactly this | none — the main agent runs Gradle inline |
| Repo-maintenance / agentic-docs drift audit | 20 numbered passes; "stale" appears 40+ times in `now-history.md`; toolchain pins live in 4 files | none named |
| CI triage | A catalog of ≥9 distinct known failures in `now.md` (18-min hang, per-OS goldens, codegen/bundle drift, gitleaks false-green, fastlane gem, missing `.sqm`, reachability, silent `runBlocking`, expect/actual) | buried in `now.md` |
| Research fleets (cite-or-die) | `research-workflow.md`, `fleet-patterns.md` §1–2; 8-agent bootstrap fleet ≈ 307K tokens | prompt blocks to paste |
| Viability skeptic | P0 viability review cadence (planning-loop.md) | prompt block |
| Plan → implement via `superpowers:subagent-driven-development` | 31 plans require it (28 also allow `executing-plans`) | the plugin's own agents |

### 1.3 Recurring pain the record attributes to missing structure `[fact:backlog/now.md, now-history.md]`

- Built-but-unwired surfaces shipped **three times** before `ReachabilityGuardTest` (WI-462).
- `.sq` schema changes without `.sqm` stranded upgraded devices; the guard task
  existed but was wired to a Gradle task CI never ran.
- macOS goldens re-recorded, Linux set not → `main` red 2026-08-20→21.
- Committed Vercel bundle drifted from source for ~130 PRs (#180).
- Doc facts (versions, counts, "X is the blocker") found stale in nearly
  every maintenance pass.
- A `check_suite` success event from gitleaks nearly merged unverified PRs
  three times in one pass.

Every one of these is either a **deterministic check** (→ hook or verifier
agent) or **knowledge that lives in one long file** (→ agent body). None is a
reasoning problem the main agent lacks capacity for; the problem is that the
knowledge and the check are not at the point of action.

### 1.4 Harness config before this pass `[fact:repo tree, ~/.claude]`

No `.claude/agents/`, no `.claude/settings.json`, no project hooks, no agent
memory. One skill (`dayfold-curator`, symlinked). User-level config in the
remote container is the platform launcher's (git-identity + stop hook), not
the operator's Mac — the operator's real `~/.claude` was not visible and is
addressed by recommendation only (`processes/claude-home/`).

## 2. Recommendations (what landed with this report)

| # | Recommendation | Where |
|---|---|---|
| R1 | Ten project subagents, all read-only on source, trigger-shaped descriptions, `maxTurns`, verdict-first capped output | `.claude/agents/` |
| R2 | The review gate as one command so it stops depending on memory or auto-delegation | `.agents/skills/two-round-review/` (+ `.claude/skills/` symlink) |
| R3 | Project `settings.json`: allowlist for the verify/inspect commands; `ask` on push/tag/merge/deploy/`dayfold push`; deny on secrets and the operator-owned values file | `.claude/settings.json` |
| R4 | PostToolUse edit-guard hook for the six drift classes in §1.3 | `scripts/claude-hooks/edit-guards.sh` |
| R5 | Five generic user-level agents (same names; project overrides), install script, settings snippet — recommended home for the operator's `~/.claude` | `processes/claude-home/` |
| R6 | Roster/rules doc; pointers from CLAUDE.md, AGENTS.md, routing, build loop, fleet patterns | `processes/subagents.md` |
| R7 | **Not** agents: ADR drafting, session close-out, maintenance pass (→ skills, follow-ups); PR babysitting (→ background session/Routine); implementer/planner (→ plan mode + superpowers) | `backlog/now.md` follow-ups |

Model policy `[opinion:synthesis of §3]`: opus + high effort where the output
is a judgment (review, security, viability); sonnet where the job is
run-and-parse (verify, audit, triage); haiku nowhere yet — the verifiers must
read JUnit XML and diagnose, which is where haiku-tier agents were reported
to under-deliver. Cost lever if needed: `CLAUDE_CODE_SUBAGENT_MODEL=sonnet`
at user level (pinned agents unaffected).

## 3. Practice findings the roster is built on

### 3.1 Official mechanics `[fact:https://code.claude.com/docs/en/sub-agents]`
- Locations/precedence: managed > `--agents` > `.claude/agents/` (project) >
  `~/.claude/agents/` (user) > plugin. Scanned recursively — hence no
  `README.md` inside `.claude/agents/`.
- Frontmatter: `name`, `description` (required); `tools`, `disallowedTools`,
  `model` (`sonnet|opus|haiku|inherit|<id>`), `permissionMode`, `maxTurns`,
  `skills` (preloads full skill content), `mcpServers`, `hooks`, `memory`
  (`user|project|local`), `effort`, `isolation: worktree`, `background`,
  `color`. Combined descriptions warn above 15,000 tokens.
- Custom subagents receive **every level of `CLAUDE.md`** and git status but
  **no conversation history**; built-in Explore/Plan skip CLAUDE.md. Results
  return as a summary. Earlier 2026 issues (#34572, #62944) showed periods
  where subagents did *not* get CLAUDE.md and confabulated that they had
  `[fact:https://github.com/anthropics/claude-code/issues/34572]` — hence
  the canary in `processes/subagents.md`.
- `memory: project` → `.claude/agent-memory/<name>/MEMORY.md`, first 200
  lines / 25 KB loaded; the agent must be told to read it first
  `[fact:https://code.claude.com/docs/en/memory]`.
- Agent files are read at **session start**: a definition added mid-session
  is not callable until restart (observed this session — the new
  `adversarial-reviewer` was run by handing its body to `general-purpose`)
  `[fact:this session]`.
- Skills vs agents: skill = knowledge/procedure injected inline (persists in
  the conversation); subagent = isolated context + tool restriction + own
  model; `context: fork` runs a skill *as* a subagent
  `[fact:https://code.claude.com/docs/en/skills]`. Side-effecting workflows →
  `disable-model-invocation: true` `[fact:https://code.claude.com/docs/en/best-practices]`.

### 3.2 Anthropic guidance on when and how `[fact]`
- Three triggers for multi-agent: context protection, parallelization,
  specialization; "group work by what context it requires, not by what kind
  of work it is"; 3–10× tokens
  `[fact:https://claude.com/blog/building-multi-agent-systems-when-and-how-to-use-them]`.
- Subagents pay off at "ten or more files, or three or more independent
  pieces of work"; don't use for sequential dependent work, same-file edits,
  or with "too many specialist agents" (reduces delegation reliability)
  `[fact:https://claude.com/blog/subagents-in-claude-code]`.
- Every delegation needs an objective, an output format, tool/source
  guidance, and boundaries; subagents should return a 1,000–2,000-token
  distilled summary
  `[fact:https://www.anthropic.com/engineering/multi-agent-research-system]`
  `[fact:https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents]`.
- Anthropic's own `code-reviewer` agent: opus, findings scored 0–100, only
  ≥80 reported, every finding `path:line`, description tells the *caller*
  what input to pass
  `[fact:https://github.com/anthropics/claude-code/blob/main/plugins/pr-review-toolkit/agents/code-reviewer.md]`.
  Its `feature-dev` plugin ships exactly three agents (explorer, architect,
  reviewer) `[fact:https://github.com/anthropics/claude-code/tree/main/plugins/feature-dev/agents]`.
- Boris Cherny runs two custom agents (`code-simplifier`, `verify-app`) and
  treats subagents "as automations for the most common PR workflows"
  `[fact:https://x.com/bcherny/status/2007179850139000872]`.
- A reviewer told to find gaps "will usually report some, even when the work
  is sound" — constrain it to correctness/requirement gaps
  `[fact:https://code.claude.com/docs/en/best-practices]`.

### 3.3 Community failure modes `[opinion unless marked]`
- Over-decomposition: an author who built 100 agents kept 12 — "description
  collisions" make the router pick wrong or not at all
  `[opinion:https://dev.to/suraj_khaitan_f893c243958/i-built-100-claude-code-subagents-these-are-the-12-that-actually-earn-their-context-10nn]`.
- Auto-delegation regressions are a recurring bug class (#47598, #12794) —
  don't rely on it for anything that must happen
  `[fact:https://github.com/anthropics/claude-code/issues/47598]`.
- Returning too much: 7 auditors × 15–37K chars put a parent into a permanent
  "prompt too long" loop (#23463) `[fact:https://github.com/anthropics/claude-code/issues/23463]`.
- Runaway agents: a one-claim verification ran 12 hours; no global timeout
  (#61405) → `maxTurns` on every agent `[fact:https://github.com/anthropics/claude-code/issues/61405]`.
- Fresh-context agents bypass memory-held safety rules (#41356) → put hard
  rules in `CLAUDE.md`/hooks, not memory `[fact:https://github.com/anthropics/claude-code/issues/41356]`.
- Background subagents whose tools are auto-denied may report
  "success-shaped output describing a change that does not exist on disk"
  `[fact:https://www.tembo.io/blog/claude-code-subagents]` → verifiers here
  must say `UNVERIFIED`, never infer green.
- Model tiers converge: opus for review/security/architecture, sonnet
  workhorse, haiku formatting/search `[opinion:https://github.com/wshobson/agents]`
  `[opinion:https://www.cloudzero.com/blog/claude-code-agents/]`.
- User vs project level: user = language-agnostic habits; project = anything
  naming paths/process; sync via dotfiles (chezmoi/git + symlinks)
  `[opinion:https://sionwilliams.com/posts/2026-03-13-dotfiles-agentic-workflows/]`
  `[fact:https://gist.github.com/rymiwe/2e5b940ae1ba981551450d318a2ee6c5]`.
- "Skills as knowledge, agents as workers"; "most slash commands map to
  skills, not subagents" `[opinion:https://alexop.dev/posts/understanding-claude-code-full-stack/]`
  `[opinion:https://theaiarchitects.com/blog/claude-code-subagents-vs-skills]`.
- Agent Teams / Dynamic Workflows: for breadth problems only; "skip agent
  teams for personal projects with tight token budgets"
  `[fact:https://code.claude.com/docs/en/agent-teams]`
  `[opinion:https://github.com/FlorianBruniaux/claude-code-ultimate-guide/blob/main/guide/workflows/agent-teams.md]`.
  The repo's `fleet-patterns.md` already fits this: Workflow when available,
  parallel subagents otherwise.

## 4. Corrections to prior documents

- `processes/build-loop-prompt.md` steps 2/4/5 and the merge paragraph now
  name the agents; wording only, no process change.
- `processes/fleet-patterns.md` §3 now points at the skill; §1 notes which
  fleet roles exist as agents. The `adversarial:strategist` role stays a
  paste-prompt (needs live operator inputs).
- No ADR: this is tooling within existing autonomy boundaries (all agents
  read-only; settings widen no permission the build loop did not already
  exercise; `ask` narrows). A future agent that *edits* or *deploys* would be
  an ADR 0012-class change.

## 5. Open questions (→ `context/open-questions.md` if they persist)

- Do the `description` triggers ever fire on their own in practice, or is
  every invocation explicit? Decides whether descriptions can shrink further.
- Is `memory: project` for two agents worth the new drift surface it adds
  (`.claude/agent-memory/**` committed)? Revisit after 10 runs.
- Should the user-level set move to a dotfiles repo now (the operator has
  ≥6 sibling repos on the same template)?

## Sources

Official: code.claude.com/docs/en/{sub-agents, skills, settings-reference,
memory, hooks-guide, agent-teams, workflows, best-practices}. Anthropic:
anthropic.com/research/building-effective-agents;
anthropic.com/engineering/multi-agent-research-system;
anthropic.com/engineering/effective-context-engineering-for-ai-agents;
claude.com/blog/{building-multi-agent-systems-when-and-how-to-use-them,
subagents-in-claude-code, introducing-dynamic-workflows-in-claude-code,
agent-view-in-claude-code}; github.com/anthropics/claude-code (plugins).
Community: github.com/wshobson/agents; github.com/VoltAgent/awesome-claude-code-subagents;
github.com/hesreallyhim/awesome-claude-code; issues #47598 #12794 #34572
#62944 #23463 #61405 #41356; tembo.io/blog/claude-code-subagents;
dev.to (suraj_khaitan, dotwee); cloudzero.com/blog/claude-code-agents;
sionwilliams.com/posts/2026-03-13-dotfiles-agentic-workflows;
alexop.dev; theaiarchitects.com; software.rajivprab.com/2026/07/13;
howborisusesclaudecode.com; x.com/bcherny.
