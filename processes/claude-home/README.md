# `~/.claude` — the operator's user-level Claude Code config (source copy)

What belongs in the **user-level** (`~/.claude/`) config versus this repo's
`.claude/`, and a source copy of the user-level pieces so they are versioned
somewhere. Rationale and the full roster: `processes/subagents.md`.

## Placement rule

| Put it in | When |
|---|---|
| `~/.claude/agents/` (user) | The agent is **language- and repo-agnostic** and useful in every venture-template repo (dayfold, keepqr, revenuecatch, ambient-ai, shipyard, swip, debugdrawer): the two-round reviewers, cite-or-die verifier, doc-drift auditor, CI triage. Bodies name **no repo paths, ADR numbers, or toolchain pins**. |
| `<repo>/.claude/agents/` (project) | Anything that names repo paths, pinned commands, ADRs, or a known-failure catalog. Dayfold's live set is `.claude/agents/`. |

Same-name collision is **intentional**: Claude Code loads the project file
over the user file (managed > CLI `--agents` > project > user > plugin), so
`adversarial-reviewer` resolves to the Dayfold-specific version inside this
repo and to the generic version everywhere else. Keep the *names* aligned and
the *bodies* different.

The user-level files stay small on purpose — every loaded agent description
costs context in every session, and the router degrades when descriptions
collide (community finding, see the research doc). Five is the set.

## Contents

- `agents/*.md` — generic `adversarial-reviewer`, `simplification-reviewer`,
  `research-verifier`, `doc-drift-auditor`, `ci-doctor`.
- `settings.snippet.json` — permission/allowlist lines worth **merging** into
  `~/.claude/settings.json` (do not overwrite your file with it).
- `install.sh` — symlinks `agents/*.md` into `~/.claude/agents/` (refuses to
  overwrite). Symlinks mean a `git pull` here updates the live agents.

## Longer term

Move this directory into a personal dotfiles repo (chezmoi or plain git) and
symlink from there; that is the community-standard way to sync
`~/.claude/agents`, `~/.claude/skills`, and `~/.claude/settings.json` across
machines (exclude `credentials.json`, `projects/`, `history.jsonl`). This
copy lives in dayfold only because dayfold is where the venture-loop
template currently originates.
