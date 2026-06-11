# Process — The Planning Loop

The autonomous engine that deepens every planning domain in waterfall order
(`planning/workstreams.md`) while the operator sets values and direction
(`context/values-and-direction.md`). One iteration = one focused session.
Governed by ADR 0003.

## Preconditions

Values file ratified · kill-switch register armed · workstream board exists ·
operator engagement model chosen. (All established at BOOTSTRAP.)

## The iteration

### 1. ORIENT
Load context in the CLAUDE.md order (values file is step 2). Read the
workstream board, `backlog/operator-inbox.md` (apply any operator answers
first — they may unblock or redirect), and the kill-switch register.

### 2. SELECT — strict priority order
1. **Overdue P0 viability review** (blocks everything else).
2. Kill-switch status refresh when new data invalidates a status cell.
3. Items unblocked by fresh operator answers (or operator-directed items).
4. Lowest-numbered `todo`/`active` item on the **gate-critical path** of the
   current phase.
5. Gap/staleness items raised by prior REVIEW steps.
Pick ONE primary item. Log the selection + why in the journal entry.

### 3. EXECUTE
Route by task class per `processes/agent-routing.md`: research fleets
(cite-or-die + adversarial verify; fleet prompt blocks in
`processes/fleet-patterns.md`), strategist pressure-tests, spec authoring
with two-round adversarial review. **Confidence protocol governs every
decision made along the way (this table is canonical — other files point
here):**

| Confidence | Criteria | Action |
|---|---|---|
| **HIGH** | ≥2 independent primary sources, or verified arithmetic, or established precedent — AND reversible, non-guardrail | Agent decides, records `[decided: agent — rationale]` inline. Implicitly ratified at the next operator sweep |
| **MEDIUM** | Single source, judgment call, modest stakes | Agent recommends, marks `[pending-ratify]`, files an inbox item with a proposed default, proceeds with non-dependent work |
| **LOW / values-shaped** | Ambiguous, preference-dependent, or contested | Inbox question; dependent work pauses, other work continues |
| **Never agent-decided** | Legal conclusions, pricing constants, scope changes, kill/pivot, anything in CLAUDE.md hard guardrails, external actions, spend | Operator only — escalate |

### 4. INTEGRATE
Update the deliverable docs. ADR-class outcomes → Proposed ADRs. Dated
research stays immutable (new rounds link back). Citation labeling per
`processes/research-workflow.md`.

### 5. REVIEW — completeness & gaps
Check the item against its board DoD. Scan adjacent docs for contradictions
the new work created (documents drift apart silently). New gaps → new board
items, never silently absorbed.

### 6. IMPROVE — self-improvement (mandatory, ~5 min)
Append to `processes/loop-journal.md`: iteration #, item, outcome, friction
encountered, rework caused/avoided, and **one process-improvement
candidate** (or explicitly "none"). Improvement rules:
- Small, evidenced tweak (a checklist line, a prompt fix, a routing change)
  → apply to the process doc immediately, note in journal. Adopt a rule
  only on its **second** occurrence — avoid premature process.
- Structural change (new gate, changed cadence, autonomy shift) → Proposed
  ADR + inbox.
- Every 15 iterations: a **meta-review** iteration — read the whole
  journal, measure (iterations per deliverable, rework count, inbox
  round-trips, stale-board incidents), propose consolidated changes.

### 7. CLOSE
CLAUDE.md end-of-session routine + update the board (status/next), backlog,
inbox (batch new questions with proposed defaults), memory. Reconcile board
vs reality every close-out.

## P0 viability review (standing, overrides all)

Every **10 iterations or 30 days or at any gate** — whichever first:
adversarial fleet (minimum: business-strategist pressure-test + hostile red
team, fresh contexts) re-attacks feasibility AND viability against the
*current* docs and kill-switch data, **scored against the operator's
confidence bar** (values file). Output:
`research/viability-review-YYYY-MM.md` with verdict (ship / conditional /
pivot / kill) + confidence + what changed since last review. Kill-switch
register refreshed with evidence. Verdict `kill` or any tripped switch →
**loop halts, immediate operator escalation** (not batched).

## Operator interface

- **`backlog/operator-inbox.md`** — questions/ratifications, each with: ID,
  date, context link, proposed default, urgency. Operator sweeps on the
  cadence in the values file. Nothing auto-applies — stale items re-surface
  in the next digest; aging >2 sweeps escalates.
- **Digest** — at the operator's cadence: one short summary (work done,
  HIGH-confidence decisions made, inbox highlights, kill-switch
  near-misses, next selection forecast).
- **Immediate interrupts** — kill-switch trips, gate decisions, compliance
  flags, viability `kill` verdicts.
- **Gate-only engagement mode:** sweep semantics collapse onto gates —
  HIGH decisions ratify implicitly at the next gate; inbox aging is
  measured in gates, not sweeps. Choose this only once the loop has earned
  trust.

## Halt conditions

Tripped kill switch · `kill` viability verdict · counsel red flag ·
operator instruction · two consecutive iterations failing their own DoD
(process is broken — meta-review before continuing).

## Running it

Single iteration: operator says "run a loop iteration". Recurring: a local
hourly cron ("run a loop iteration every 1hour" — note: session-bound,
dies on restart; re-arm or use a cloud schedule for durability). Long
fleets survive session restarts via workflow resume; see fleet-patterns.
