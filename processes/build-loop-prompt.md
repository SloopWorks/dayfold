# Build-loop prompt

A reusable `/loop` prompt that drives the autonomous build workflow: pick the next
buildable task, work it in a dedicated git worktree, review it across all
dimensions, verify (tests + emulator), and PR → CI → merge-when-green — while
staying clear of the auth/login agent's lane.

**Use it** by pasting the body below into a session prefixed with `/loop`, or run
the short form: `/loop follow processes/build-loop-prompt.md`.

---

SCOPE & GUARDRAILS
- If another agent/worktree is actively building a lane (check `backlog/now.md` and running worktrees before starting), don't touch its files — only integrate around it on merge. The auth epic (ADR 0021 S1-S6) is DONE + merged; this is a generic reminder, not a standing exclusion.
- Never agent-decide scope/pricing/legal/spend (CLAUDE.md guardrails). If the planned build order is exhausted and only deferred/design-blocked/device-gated items remain, STOP and ask which to do (don't auto-pick a deferred item).
- Pick the next BUILDABLE task in the planned order (e.g. content-detail-epic CL-* order, or backlog/next.md TASK-*). Skip items that need a device you can't get, a design mockup that doesn't exist, or operator spend.

WORKTREE DISCIPLINE
- git fetch origin; fast-forward local main to origin/main BEFORE starting anything.
- Do all work in a dedicated git worktree off latest main: git worktree add .claude/worktrees/<slug> -b <branch> origin/main. Never commit on main. One worktree per work-stream; ff-merge each reviewed slice onto that worktree's integration branch.
- Read processes/agent-dev-loop.md for the pinned toolchain versions (JDK, Kotlin, Compose-MP, SQLDelight, redux-kotlin) before editing apps/.

REVIEW DIMENSIONS (every review grades each; cite Critical/Important/Minor + fix)
- Correctness & completeness vs the spec/DoD; gaps and edge cases.
- Performance — no work on hot/recomposition paths; decode/derive off-render; allocation, query, and sync-size discipline.
- Security & privacy — read-only M0 (ADR 0020), tenancy/IDOR, scheme/URL/injection vetting, no SSRF/fetch, privacy-chip honesty (ADR 0014/0015), Guardrail-3.
- redux-kotlin best practices — pure reducers, f(state)->UI, effects in middleware/engines not the UI, stable/remembered handlers, selector scoping.
- Cohesiveness — fits existing patterns/naming/structure; reuses shared chrome; no divergent one-offs.
- Simplification — cut anything over-built; flag dead code/YAGNI; prefer the smallest correct design.
IF UI WORK, also:
- Mobile UI/UX expertise — information hierarchy, touch ergonomics, states (empty/loading/error), nav clarity.
- Jetpack Compose — recomposition skippability/stability, modifier order, state hoisting, no side effects in composition, list keys.
- Material3 expressiveness — proper M3/Expressive components, motion, shape, color roles (not hardcoded hex), light+dark.
- Accessibility (ADR 0009 WCAG-AA) — >=48dp targets, contentDescription, contrast, prefers-reduced-motion.

PER-TASK LOOP (do every step)
1. Write a short spec+plan to docs/superpowers/specs/<date>-<slug>-design.md (scope, files, security/privacy, test plan, DoD, risks).
2. Launch a pre-impl adversarial review subagent (read-only) against the spec, graded across ALL applicable REVIEW DIMENSIONS above; fold its Critical/Important findings before coding. For substantial UI work, run a second reviewer focused on the UI dimensions (Compose + Material3-Expressive + mobile UX + a11y).
3. Implement TDD. Match existing code style. Keep read-only M0 invariants (ADR 0020) and honesty/privacy rules (ADR 0014/0015).
4. Verify: apps/api `npx vitest run` green; from `apps/`, `./gradlew :client:desktopTest :ui:desktopTest` green (ADR 0047 split — logic tests live in `:client`, render/golden-snapshot tests in `:ui`); `npm run codegen` idempotent; android + iOS-sim compile; for UI, write snapshot PNGs and READ them to eyeball (or use the CL-SNAP `--dashboard` gate, `processes/agent-dev-loop.md`), and when an emulator is FREE (check `adb devices` + foreground app — do NOT contend with another agent's app) deploy + drive via adb screencap.
5. Launch a final whole-branch review subagent graded across ALL applicable REVIEW DIMENSIONS (plus a dedicated UI/Compose/Material3-Expressive/UX/a11y reviewer when UI changed); fix Critical/Important findings.
6. Commit (normal prose message; end with: Co-Authored-By: Claude <noreply@anthropic.com>). Update backlog/next.md (TASK-* done + follows). ff-merge onto the integration branch.

INTEGRATION / MERGE
- When a coherent unit (or several slices) is ready: pull latest origin/main, merge it into the integration branch, resolve conflicts (auth⟂content are usually unions; watch for migration-number collisions with the auth agent → renumber content migrations to sit after theirs and update all references), re-run all tests + compile + codegen.
- Push the branch, open a PR against main (gh pr create), then monitor CI (gh pr checks <n> --watch), fix any red, and merge when green (gh pr merge --merge). Delete the merged branch.

PACING
- Dynamic loop: after each slice, report progress and schedule a short wakeup (~60s) to keep momentum. Pause (no wakeup) + PushNotification only when blocked on an operator decision (scope/spend/design-gated/device-contended) or when a milestone PR is merged and nothing buildable remains.

do not complete the auth/login work another agent
