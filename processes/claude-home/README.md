# `~/.claude` — the operator's user-level Claude Code config (source copy)

Source copy of the user-level pieces so they are versioned somewhere. The
placement rule (what goes in `~/.claude/agents/` vs a repo's
`.claude/agents/`), the precedence chain, and why the names deliberately
collide with the project set are in **`processes/subagents.md` §Placement** —
not restated here.

## Contents

- `agents/*.md` — generic `adversarial-reviewer`, `simplification-reviewer`,
  `research-verifier`, `doc-drift-auditor`, `ci-doctor`. Bodies name no repo
  paths, ADR numbers, or toolchain pins, and stay short — every loaded
  description costs context in every session.
- `settings.snippet.json` — permission/allowlist lines worth **merging** into
  `~/.claude/settings.json` (do not overwrite your file with it). Its `env`
  sets `CLAUDE_CODE_SUBAGENT_MODEL=sonnet` as a cost lever: every subagent
  that does not pin a model runs on sonnet; the reviewers here pin opus and
  are unaffected. Drop that key if you want session-model inheritance.
- `install.sh` — symlinks `agents/*.md` into `~/.claude/agents/` (skips a
  live existing entry, replaces a dangling one). Symlinks mean a `git pull`
  here updates the live agents.

## Longer term

Move this directory into a personal dotfiles repo (chezmoi or plain git) and
symlink from there; that is the community-standard way to sync
`~/.claude/agents`, `~/.claude/skills`, and `~/.claude/settings.json` across
machines (exclude `credentials.json`, `projects/`, `history.jsonl`). This
copy lives in dayfold only because dayfold is where the venture-loop
template currently originates.
