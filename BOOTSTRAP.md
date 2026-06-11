# BOOTSTRAP — Turning the Template into a Live Project

Agent protocol. Run when the operator says "bootstrap this project: <idea>".
Work top to bottom; do not skip the interview; do not start the loop until
every phase checks off. Check off boxes as you go; when bootstrap completes,
replace this file's contents with a short "bootstrapped on <date>" note
(the protocol lives in the template repo).

## Phase 0 — Mechanics

- [ ] Confirm you are in a fresh copy (not the template itself): the folder
      name is project-specific and git history is clean.
- [ ] Read, in order: `CLAUDE.md`, `context/operating-lessons.md`,
      `processes/planning-loop.md`, `processes/agent-routing.md`,
      `processes/fleet-patterns.md`, `context/kill-switches.md` (interview
      Q2 presents its bundles), and the three context skeletons you will
      fill: `context/values-and-direction.md`,
      `context/business-constitution.md`,
      `context/goals-and-constraints.md`.

## Phase 1 — Operator interview (AskUserQuestion; batch into ≤2 rounds)

Ask, capturing answers verbatim for the values file:

1. **North star** — what should drive prioritization? (durable side income /
   sellable asset / income replacement / learning-lab / other — multi-select
   with primary marked)
2. **Kill-switch bundle** — conservative / moderate / patient (present
   concrete thresholds: months-to-first-paying-customer, cumulative cash
   ceiling, sustained hours/week ceiling, domain red-flag hard stops).
3. **Confidence bar** — how much affirmative evidence before build/spend?
   (e.g. "very high: recurring revenue + unit profitability + growth
   potential each affirmatively evidenced" vs "moderate: no fatal flaws
   found"). This calibrates gates and viability reviews.
4. **Engagement model** — inbox + weekly review / per-iteration digest /
   gate-only check-ins.
5. **Constraints** — cash ceiling to first revenue, hours/week (build and
   steady-state), geography, existing assets (entities, audiences, skills,
   prior projects), hard no-gos (legal posture, brand lines, channels).
6. **The idea itself** — restate it back; ask what the operator already
   believes vs wants tested; ask who the customer is and what they pay
   today.

## Phase 2 — Fill the scaffold

- [ ] `context/values-and-direction.md` — replace all placeholders from the
      interview. Operator-owned from this moment; agents propose changes
      via inbox only.
- [ ] `context/business-constitution.md` — identity, what-it-is-NOT (drift
      risks specific to this idea), customer-experience guarantees, hard
      guardrails. Derive the NOT-list from the idea's nearest failure
      modes; this file is the scope firewall.
- [ ] `context/goals-and-constraints.md` — targets table + hard constraints
      + validation gates (include the confidence-bar gate).
- [ ] `context/kill-switches.md` — instantiate the chosen bundle as
      measurable rows with clocks and current status.
- [ ] `CLAUDE.md` — fill project name/description placeholders; in the
      hard-guardrails list fill slots 1/3/4 from the domain's real legal
      and customer-relationship lines, keep 2/5/6 verbatim.
- [ ] `README.md` — replace with a project README (orientation links;
      lineage note pointing at the template).
- [ ] `backlog/now.md` — stage = bootstrap; operator inbox seeded (Phase 5).

## Phase 3 — Seed decisions

- [ ] Verify generic ADRs 0001–0003 (status "Proposed (template default)")
      read true for this project. Edit any that need adaptation, THEN mark
      Accepted with today's date — they become immutable at that moment.
- [ ] Write ADR 0004 — Product Framing — from the interview: what this is,
      what it is not, rejected adjacent framings. Status: Accepted if the
      operator's idea statement was unambiguous; Proposed otherwise.
- [ ] Update `adr/decisions-index.md`.

## Phase 4 — Initial validation round (the round-3 pattern)

Run the **validation fleet** from `processes/fleet-patterns.md` §1 against
the idea: market/demand stats, competitors (incl. an independent
missed-competitor sweep), technology/platform reality, pricing structure,
regulatory/compliance landscape — plus the strategist pressure-test and the
hostile red team. Rules: citations mandatory, ground-truth block in every
prompt ("nothing has been built, no customers contacted…"), archive raw
agent outputs under `research/<name>-agent-outputs/`. No Workflow tool in
the harness? Run the same agent roster as parallel subagents.

- [ ] Synthesize `research/validation-round1-<YYYY-MM>.md`: verdict first;
      refuted/confirmed claims; missed competitors; unit-economics first
      pass (the strategist runs the math itself from the interview's
      pricing facts); kill-criteria checks (the red team must name the
      cheap checks that would kill the idea).
- [ ] Update kill-switch register statuses with evidence.
- [ ] Anything values-shaped or ADR-class → inbox / Proposed ADRs.

## Phase 5 — Build the board + arm the loop

- [ ] `planning/workstreams.md` — instantiate Phase A from the validation
      round's open questions and gates (each blocker becomes an item with
      a DoD; mark which parts are agent-desk-researchable vs
      operator-only). Phases B–E stay as templated structure.
- [ ] `context/open-questions.md` — seed from validation findings.
- [ ] `backlog/operator-inbox.md` — seed with: validation verdict summary
      (decision items), any spend/contact authorizations needed, and the
      loop-start note.
- [ ] `processes/loop-journal.md` — write Iteration 0 (bootstrap entry:
      what was done, tokens spent, anything that fought back).
- [ ] Arm the viability clock: the bootstrap validation round counts as
      P0 viability review #1 — set the workstreams standing-track row to
      Last = today, Next due = today + 30 days (or 10 iterations,
      whichever first).
- [ ] Memory: store project facts in the session memory system per
      `CLAUDE.md` memory governance.
- [ ] Tell the operator: inbox items awaiting them; how to start the loop
      ("run a loop iteration" / schedule); what iteration 1 will select.

## Bootstrap quality bar

The bootstrap is itself subject to the two-round adversarial rule. Round 1
(correctness, fresh context): (a) every placeholder actually replaced,
(b) constitution NOT-list matches the idea's real drift risks, (c) kill
switches measurable not aspirational, (d) validation synthesis labels
evidence honestly, (e) viability clock armed. Apply fixes. Round 2
(simplification, fresh context): board items right-sized for the next five
iterations, no duplicated content drifting apart, interview answers
faithfully reflected not embellished. Fix, then close.
