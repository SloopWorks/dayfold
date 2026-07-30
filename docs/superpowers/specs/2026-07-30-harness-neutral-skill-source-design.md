# Harness-neutral curator skill — one source of truth

**Date:** 2026-07-30
**Status:** Approved (operator, in-session)
**Scope:** repo layout + skill content. No product/API surface change.

## Problem

`dayfold-curator` existed twice:

- `.claude/skills/dayfold-curator/` — tracked, current.
- `.agents/skills/dayfold-curator/` — untracked, created 2026-07-16 by a Codex
  session, already drifted: it had lost three sections of `references/cli.md`
  (the `--help --json` discovery block, the `content:delete` fourth-scope
  warning, the missing-env exit-2 detail) and had rewritten
  `provenance.source` from `"claude"` to `"Codex"`.

Two copies is one copy plus a silent liar. A Codex session following the stale
fork would have guessed at flags the CLI documents, and mis-diagnosed a
`dayfold delete --block` 403 by re-logging-in per-hub — the exact failure the
dropped section exists to prevent.

The fork was not an accident of process: each harness looks in its own
directory, and the skill had no home that both could read.

## Findings

- **Codex's repo skills root is `.agents/skills`** — confirmed from the codex
  0.144.4 binary, where the literals `.agents` and `skills` sit adjacent to
  `"failed to stat repo skills root"`. Its personal root is `$CODEX_HOME/skills`.
- **Claude Code's roots** are `.claude/skills/` (project) and `~/.claude/skills/`
  (personal).
- Both harnesses read the same `SKILL.md` + YAML-frontmatter format.
- `provenance.source` is a free string; the API allowlists it through
  (`apps/api/src/security.ts`) and the client renders it as a byline chip via
  `sourceLabel()` — `"claude"` → "Added by Claude", unknown → "Added by $source".

## Design

**1. Canonical location — `.agents/skills/dayfold-curator/`.**
Vendor-neutral, and the one root a non-Claude harness already reads without
configuration.

**2. Bridge — `.claude/skills/dayfold-curator` is a committed relative symlink**
(`../../.agents/skills/dayfold-curator`, git mode 120000). Claude Code sees a
skill directory; Codex reads the real one. Drift becomes structurally
impossible rather than merely discouraged — no sync script, no CI gate.

Rejected alternatives: a generated copy plus a CI drift check (duplicated file
set and one more gate, to buy back a guarantee the symlink gives for free);
install-only with no `.claude/` path (a fresh clone would have no project-local
skill until someone ran `install.sh`).

**3. Content merge.** The `.claude/` copy wins wholesale — the fork contributed
nothing but its own provenance string. Then de-harness it:

- `provenance.source` becomes *the agent that actually authored the card*
  (`"claude"`, `"codex"`, …), stated once in `references/guardrails.md` §5 with
  the reason (the chip is a user-visible byline, so a stale value is a
  dishonest chip), and referenced from `SKILL.md` and `content-model.md`.
- `sourceLabel()` gains a `"codex"` case so the chip capitalizes instead of
  falling through to "Added by codex".
- `install.sh` links into `~/.claude/skills/` **and** `${CODEX_HOME:-~/.codex}/skills/`,
  skipping a harness whose parent directory is absent, failing only if none is
  found.

**4. Pointers.** `README.md`, `AGENTS.md`, `CLAUDE.md` (directory map + the
"content API + CLI + agent skill" line), `docs/architecture.md` (mermaid node +
component table), `apps/cli/README.md`, `apps/cli/templates/README.md` all
repoint to `.agents/` and say *agent skill*, not *Claude skill*.

`specs/prototype/*` and `adr/0013` also mention the old path. Both are
historical records — the prototype specs cite a path (`.claude/skills/dayfold/`)
that never shipped under that name, and Accepted ADRs are immutable. Left alone.

## Out of scope

Shims for Cursor / Gemini CLI / opencode. `.agents/skills` is where the
ecosystem is converging; each additional shim is a symlink to maintain for a
harness nobody here runs. Add one when a harness is actually used.

## Verification

- **Codex** — `codex exec --sandbox read-only "list every skill available to you
  in this repo"` in the repo root lists `dayfold-curator` unprefixed, alongside
  the plugin-namespaced ones. Ran 2026-07-30: passes.
- **Symlink** — `git ls-files -s .claude/skills/dayfold-curator` reports mode
  `120000`; `ls .claude/skills/dayfold-curator/` lists the real files through it.
  Passes.
- **Client** — `:ui:desktopTest` (329 tests) green, including the extended
  `sourceLabel` case in `CardRenderTest`.
- **Claude Code** — requires an operator restart to confirm the skill loader
  follows a symlinked skill directory. If it does not, the fallback is the
  rejected generated-copy-plus-CI-check design; nothing else about this change
  moves.

## Why no ADR

Repo layout housekeeping, not scope / vendor / data-handling. The durable rule
that outlives this doc — *edit only the `.agents/` copy* — is recorded in
`CLAUDE.md`'s directory map, which is where an agent will actually be reading.
