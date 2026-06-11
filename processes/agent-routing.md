# Process — Agent Routing

How work routes to agents and processes. Consult before starting any
multi-step task. Single-fact lookups and trivial edits skip routing.

## Routing table

| Task class | Route | Output lands in | Operator gate |
|---|---|---|---|
| **Planning-loop iteration** | `processes/planning-loop.md` — ORIENT→SELECT→EXECUTE→INTEGRATE→REVIEW→IMPROVE→CLOSE; confidence protocol per ADR 0003 | board item's deliverable + journal + inbox | Sweep cadence; gates; interrupts |
| **P0 viability review** | Adversarial fleet vs current docs + kill-switch data, scored against the confidence bar; cadence + mechanics: `processes/planning-loop.md` | `research/viability-review-YYYY-MM.md` | Verdict review; `kill` interrupts immediately |
| Market / competitor / tech / regulatory research | Multi-agent research fleet per `processes/research-workflow.md` + `processes/fleet-patterns.md` (citations + adversarial verification mandatory) | `research/` dated report + raw-output archive | Accept conclusions → ADR/context |
| Business strategy, pricing pressure-test | Solo-business-strategist-type agent with full inputs (budget, hours, geography, stage) | `research/` | Verdict informs ADRs |
| Plan / spec / PRD authoring | Brainstorm → draft → **two adversarial review rounds** (1: correctness, 2: optimization/simplification) | `specs/`, `roadmap/` | Accept before dependent work |
| Adversarial review of anything | Fresh-context agent(s), never the author; mechanics: `processes/fleet-patterns.md` §3 | Review doc or PR review | Author responds with rigor, not agreement |
| Customer/prospect data work (enrichment, analysis) | Scripted + agent hybrid; **no contact with anyone** | create a working-data dir and add it to the CLAUDE.md directory map first | Before any outreach wave |
| Outreach / sales material | Agent drafts | drafts only | **Operator sends. Always.** |
| Administrative / setup (entity, accounts, insurance, banking) | Agent prepares runbook + comparison; operator executes | `processes/` runbook | All sign-ups/signatures human |
| Infrastructure / provisioning (build phase) | Runbook-driven; agent executes within granted credentials | `processes/` | Credential grants, spend |
| Software build (post-spec) | 8-phase milestone workflow: brainstorm spec → plan → worktree → TDD build → verify → independent fresh-context review → gated merge → close-out | code + `roadmap/` status | Guardrail escalations only |
| Compliance / legal questions | Research fleet for landscape only; **conclusions are not legal advice** | `research/` + open question | Real lawyer before reliance |
| Business operations (steady state) | Scheduled agents: monitoring, periodic business review, support triage, reporting | dashboards/reports | Escalation rules per ADR |

## Escalation guardrails (stop work, surface to operator)

1. ADR-class decision discovered mid-task (scope, pricing, compliance,
   vendor, customer-data, automation boundary, maintenance burden).
2. Anything external-facing: sending messages, creating accounts, accepting
   terms, spending money, signing anything.
3. Legal/compliance ambiguity.
4. Spend beyond agreed thresholds; unknown blocked past ~3 sessions.
5. Conflict between source-of-truth documents (resolve via ADR, not
   silently).

## Review discipline

- Research claims: label `[fact:source]` / `[estimate]` / `[assumption]`;
  every fact carries a URL; primary sources beat blog roundups.
- Adversarial reviewers get fresh context (no author bias) and an explicit
  mandate to kill the work product.
- Verdicts before prose: reviews lead with ship/no-ship + confidence.
