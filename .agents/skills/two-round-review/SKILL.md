---
name: two-round-review
description: Run the repo's mandatory two-round adversarial review — round 1 correctness (adversarial-reviewer, plus privacy-security-reviewer / compose-ui-reviewer when the change touches those surfaces), apply fixes, round 2 simplification (simplification-reviewer) — on a spec, plan, ADR, research synthesis, or branch diff, and record the outcome. Use before accepting any plan/spec and before opening a PR. Invoke explicitly (/two-round-review <target>); gates must not depend on auto-delegation.
---

# Two-round review

The cheapest defect-removal step in the system (`context/operating-lessons.md`
§5). Mechanics live in `processes/fleet-patterns.md` §3; the reviewer prompts
live in `.claude/agents/` (roster: `processes/subagents.md`). This skill is
the orchestration — it exists so the gate is one command, not a memory test.

## Inputs
- **Target**: a path (`docs/superpowers/specs/…`, `adr/…`, `research/…`) or a
  diff (`git diff origin/main...HEAD`, or "the current branch").
- **Kind**: `spec` (pre-implementation) or `code` (whole branch / PR).
- **Constraint docs**: the ADRs/specs the target cites, plus `CLAUDE.md`
  guardrails. List them; the reviewers read them first.

## Procedure

1. **Round 1 — correctness, in parallel, fresh context each:**
   - `adversarial-reviewer` — always.
   - `privacy-security-reviewer` — if the diff touches `apps/api`, auth, sync,
     migrations, telemetry/error reporting, calendar, content ingestion, or
     anything a CLAUDE.md guardrail names.
   - `compose-ui-reviewer` — if composables, theme, navigation, or snapshot
     scenes changed (for a `spec`, if it specifies UI).
   In Claude Code: `Agent(subagent_type=<name>, prompt=<target, kind,
   constraint docs, the ask>)`, one call per reviewer, same message so they
   run concurrently. In a harness without named subagents (Codex): open a
   fresh context per reviewer and paste the agent file's body as the system
   prompt with the same inputs.
2. **Triage** the merged findings: every P0/Critical gets fixed or escalated
   to the operator (guardrail/ADR-class → `backlog/operator-inbox.md`, never
   silently waived); P1/Important fixed unless you write down why not; P2
   optional. The author responds with rigor, not agreement — but "I disagree"
   needs evidence, and a reviewer's `Verified true` list is not re-checked.
3. **Apply fixes.** Re-run the relevant verifier (`kmp-verifier` /
   `api-verifier`) if code changed.
4. **Round 2 — simplification**: `simplification-reviewer` with the target
   *after* fixes **and** the round-1 findings + what was applied. It may not
   reopen round 1. Apply its tweaks; `NEEDS-RESTRUCTURE` means go back to
   step 1 with the restructured artifact.
5. **Record.** For a spec/plan: a `## Review` section at the bottom with both
   verdicts, the P0/P1 list, and what changed. For a branch: a `Review`
   paragraph in the PR description. For research: the synthesis's
   "adversarial pass" section. Cost note if notable (agent count, tokens).

## Rules
- Never review your own work in-context; the whole point is fresh context.
- Never run round 2 before round 1's fixes are applied.
- Reviewers are read-only; you apply the fixes.
- Expect P0s. A clean round 1 on a non-trivial artifact is a reason to check
  the reviewer got the right inputs, not a reason to celebrate.
