# Prompt — continue the subagent analysis on the local machine

Paste everything below the rule into a **local** Claude Code session started in
the directory that holds your repos (e.g. `~/workspace`). The remote session
that produced PR #404 could not see `~/.claude` on your Mac, the local session
transcripts, or the sibling repos; this prompt picks up exactly there.

---

Continue the subagent-practices analysis from SloopWorks/dayfold PR #404
(merged 2026-09-03). Read first, in this order:
`dayfold/processes/subagents.md`,
`dayfold/research/2026-09-02-subagent-practices-review.md` (§1.4 "limits" and
§5 "open questions" are the gaps you are closing),
`dayfold/processes/claude-home/README.md`.

Ground rules for this session:
- Propose, show the diff, and wait for my confirmation before writing anything
  under `~/.claude/` or into any repo other than a dayfold feature branch.
- Never overwrite `~/.claude/settings.json` — merge into it, arrays unioned.
- Read-only across sibling repos unless I say otherwise; never commit to any
  repo's `main`; never edit `context/values-and-direction.md` anywhere.
- Transcripts under `~/.claude/projects/` may contain private family data:
  extract tool-call metadata only (tool names, Bash command prefixes,
  `subagent_type`, skill names) — never quote message content.

## Phase 1 — inventory this machine (read-only)

1. `~/.claude`: list `settings.json` (permissions, hooks, env), `agents/`,
   `skills/`, `CLAUDE.md`, installed plugins (is `superpowers` present?), and
   `projects/*` (slug, size, last-modified — do not read transcripts yet).
2. Sibling repos — adjust to what actually exists: dayfold, keepqr,
   revenuecatch, ambient-ai, shipyard, swip, debugdrawer, redux-kotlin / rk.
   For each: has `CLAUDE.md` / `AGENTS.md` / `.claude/{agents,skills,settings.json}`
   / `.agents/skills` / `.cursor` / `.codex`? Is it a venture-template repo
   (`context/values-and-direction.md` + `adr/` + `processes/` present)? KMP or
   redux-kotlin? TS/Node? One table, one row per repo.
3. Session history, per project slug, last 60 days or last 50 transcripts
   (whichever is smaller): top 30 Bash command prefixes; how often the `Agent`
   tool ran and with which `subagent_type`; which skills were invoked; how
   often permission prompts fired. The `fewer-permission-prompts` skill does
   the Bash part — run it in read-only mode first, apply nothing yet.
   Output: per-repo table + a cross-repo table of the workflows that recur in
   ≥2 repos (those are the user-level candidates).

## Phase 2 — install and verify (propose-confirm each step)

1. `cd dayfold && git pull origin main`. Review
   `processes/claude-home/install.sh`, then run it. Confirm with `/agents`
   that the five user-level agents load, and that inside dayfold the project
   versions of the same names win.
2. Merge `processes/claude-home/settings.snippet.json` into
   `~/.claude/settings.json` — object merge, arrays unioned and deduped, my
   existing hooks/env untouched. Show the diff before writing.
3. Canary: inside dayfold ask `adversarial-reviewer` to quote the first
   heading of `CLAUDE.md`. If it paraphrases or guesses, subagents are not
   receiving `CLAUDE.md` on this version — record it and stop relying on the
   guardrail inheritance until fixed.
4. Calibration: run `kmp-verifier` and `api-verifier` once each against
   current `main` (no-op). Record wall time, turns used, whether any lane came
   back `UNVERIFIED` locally (it should not, unlike the remote sandbox), and
   whether the JUnit-XML test counts match the Gradle summary. Propose
   `maxTurns` / model changes only from that evidence.

## Phase 3 — extend placement (one dayfold PR + per-repo recommendations)

1. Pending from the remote session, apply only if I confirm:
   (a) CLI lane — `kmp-verifier` gets an `apps/cli/**` row (`cd apps/cli &&
   ./gradlew test`, own Gradle 9.5.1 wrapper, JUnit XML at
   `apps/cli/build/test-results`, `scripts/ci-test-failures.sh apps/cli`);
   `privacy-security-reviewer` gets a CLI-credentials block (`SecretStore.kt`,
   `Credentials.kt`, keychain, refresh rotation, `--dry-run` writes nothing,
   `--json` leaks no secrets, ACL/base-version checks per ADR 0067).
   (b) `install.sh` — `--force`, `--uninstall` (remove only links pointing
   into this dir), a name-collision warning when run inside a repo with
   `.claude/agents/`, and a `merge-settings.sh` that unions arrays.
2. Promote to user-level only if Phase 1 shows ≥2 repos that would use them:
   `viability-skeptic` (generic body: "if `context/` files are absent, ask for
   the constraints inline") and a generic `compose-ui-reviewer` (Compose
   skippability, M3 color roles, a11y ≥48dp, redux-kotlin binding rules — no
   ADR numbers, no reachability/golden sections).
3. Venture-template repos (keepqr, revenuecatch, ambient-ai): propose
   symlinking the `two-round-review` skill and name which project agents each
   needs. Write nothing into them until confirmed.
4. KMP repos (swip, debugdrawer, redux-kotlin): propose which of
   `kmp-verifier` / `compose-ui-reviewer` generalize; list the module-name
   differences that block a shared copy.
5. Dotfiles home: propose moving `processes/claude-home/` to a dotfiles repo
   (chezmoi or plain git + symlinks). List the exact files to track and the
   exclusions (`credentials.json`, `projects/`, `history.jsonl`, caches).

## Phase 4 — report

Write `dayfold/research/2026-09-DD-subagent-practices-local-followup.md`
(dated; `[fact:local]` for machine observations, `[fact:url]` /
`[estimate]` / `[assumption]` for everything else). Update the placement
section of `processes/subagents.md` if the tiers changed. Add follow-ups to
`backlog/now.md`. Run `/two-round-review` on the report, then open the PR
from a feature branch — not `main`.
