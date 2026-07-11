# Onboard dayfold → Shipyard — Migration Strategy

**Date:** 2026-07-07
**Status:** Plan (not yet executed). Execution tracked as a Shipyard WI in project `shipyard-core` (P-1), Opus 4.8 tier.
**Author:** brainstorm session (Claude Opus 4.8)

## Goal

Make Shipyard the control plane for dayfold **without losing any current context,
product status, next items, or backlog.** The post-migration process must be **as
good as or better** than dayfold's file-based process. Where Shipyard is currently
*weaker* than dayfold, fix the Shipyard gap **before** cutover for that artifact —
never degrade dayfold to fit the tool.

## Dayfold's current process (inventory)

| Area | Files | Role |
|---|---|---|
| Queue | `backlog/now.md`, `next.md`, `later.md` | Current/near/eventual work (prose-heavy status, not discrete tickets) |
| History | `backlog/now-history.md` | Append-only human narrative of every dated status update |
| Inbox | `backlog/operator-inbox.md` | Operator-decision queue (Shipyard's InboxItem pattern was ported FROM this) |
| Roadmap | `roadmap/README.md` | High-level direction |
| Planning | `planning/` (epics, workstreams) | Mid-level decomposition |
| Process | `processes/` (11 loop docs) | Agent dev-loop, routing, build-automation, release, planning-loop |
| Context | `context/` (business-constitution, goals-and-constraints, kill-switches, open-questions, operating-lessons, values-and-direction) | Governance / guardrails |
| Specs | `specs/` (~18) | Design specs |
| ADRs | `adr/` (~47) | Decision records |
| Designs | `designs/` | Hi-fi mockups |
| Research | `research/` (~11) | Investigation reports |

## Target mapping (clean — no Shipyard change needed)

| Dayfold | → Shipyard primitive |
|---|---|
| `backlog/now.md`, `next.md` items | WorkItems, status `next` |
| `backlog/later.md` items | WorkItems, status `later` |
| `backlog/operator-inbox.md` entries | InboxItems (`inbox add`) |
| `specs/` | Docs, kind `spec` |
| `adr/` | Docs, kind `adr` (remap dir via `.shipyard.yaml`: dayfold uses `adr/`, Shipyard convention is `adrs/`) |
| `processes/` loop + agent docs | Docs, kind `prompt` / `agent` |
| `designs/` | Docs, kind `design_html` |

## Gaps — where Shipyard is currently weaker than dayfold

Shipyard Doc kinds are **hardcoded** to `{spec, adr, prompt, agent, design_html}`
(`usecases/projconfig.py: VALID_KINDS`). Shipyard has **no due-date / hard-date /
time-sensitive** primitive (`pinned` in the code = classifier-tier pin only). These
produce four real gaps:

| # | Gap | Severity | Resolution (HYBRID — recommended) |
|---|---|---|---|
| G1 | No Doc kind for `context/`, `roadmap/`, `planning/`, `research/` → not indexed, never assembled into agent context | **High** for `context/` (guardrails must reach the agent); Med for the rest | **Fix Shipyard first** for `context/`: either add a `context` Doc kind, or wire `context/` files into `.shipyard.yaml` constraints/DO_NOT + context budget so they assemble. `research/` → map to `spec` kind (good enough). `roadmap/`+`planning/` → keep as indexed repo files + one pinned "Roadmap" reference; revisit a `roadmap` kind later. |
| G2 | No hard-date / time-sensitive surfacing (now.md's `⚠ Time-sensitive (hard dates)` pinned block, e.g. "CI RED since 2026-07-05") | **High** — losing this loses operational urgency | **Fix Shipyard first**: add an optional `due_date` (or `pin`/`urgent`) field on WorkItem + Dashboard surfacing. Until shipped, encode as a WI title convention `[DUE 2026-07-05] …` so nothing is silently dropped. |
| G3 | `now-history.md` human narrative ≠ Shipyard's per-mutation event log | Low | Keep `now-history.md` as a repo file; link it from the project. Do **not** try to replay it into the event log. New narrative accrues via Session notes + Events going forward. |
| G4 | `now.md` is prose status, not discrete tickets | Med (lossy if rushed) | **Human-curated decomposition**, not a mechanical parse. Each actionable item → one WI with title + body; ambiguous prose → an InboxItem for triage. Preserve the source `now.md` until parity is verified. |

**Decision left open** (user deferred the gap-strategy pick): the executing agent/human
chooses fix-first vs map-onto-primitives **per gap** using the table above as the
recommended hybrid default. G1-`context/` and G2 are the two that genuinely degrade
dayfold and should be fixed in `shipyard-core` before their cutover step.

## Phased execution plan

**Phase 0 — Register & configure (no loss risk)**
- `shipyard project add dayfold ~/workspace/dayfold`.
- Add `~/workspace/dayfold/.shipyard.yaml`: remap `adr/`→adr kind, `processes/`→prompt/agent, `specs/`→spec, `designs/`→design_html; set constraints/DO_NOT from `context/kill-switches.md` + `context/goals-and-constraints.md`; set context budget.
- `shipyard reindex` — confirm specs/adr/designs/processes appear as Docs, FTS works.

**Phase 1 — Gap fixes in `shipyard-core` (do before dependent cutover steps)**
- G2: add WorkItem `due_date`/urgent field + migration + Dashboard surfacing + CLI/API. (Blocks Phase 3 hard-date items.)
- G1-context: add `context` Doc kind **or** `.shipyard.yaml` guardrail wiring so `context/` assembles. (Blocks trusting agent context on cutover.)
- Each gap fix = its own sub-WI with tests (Shipyard is TDD, hand-SQL migrations, forward-only).

**Phase 2 — Docs cutover (low risk, reversible)**
- Verify every `specs/adr/designs/processes` file is indexed; spot-check assembled context for a sample WI includes the right guardrails.

**Phase 3 — Queue migration (curated, the lossy step)**
- Human/agent walks `now.md` + `next.md` → discrete WIs (`next`), `later.md` → WIs (`later`). Hard-date items carry the new due-date field (or `[DUE …]` fallback).
- Link WIs to their specs/ADRs via `wi link`. Set `depends_on` where prose implied ordering.
- Ambiguous prose → `inbox add` for triage rather than a guessed WI.
- **Keep `backlog/*.md` intact until Phase 5 parity check passes.**

**Phase 4 — Inbox migration**
- `operator-inbox.md` entries → `shipyard inbox add --project dayfold …`, preserving the proposed default each carried.

**Phase 5 — Parallel-run & verify parity**
- Run both for a short window. Checklist: every `now/next/later` item has a WI; every open operator-inbox item has an InboxItem; assembled context for 3 sample WIs contains the expected guardrails/specs; hard-date items surface on the Dashboard.
- Only after parity: retire `now/next/later/operator-inbox.md` (leave a pointer stub → Shipyard). Keep `now-history.md`, `roadmap/`, `planning/`, `research/`, `context/`, `specs/`, `adr/`, `designs/` as-is (repo-of-record).

**Phase 6 — Rollback**
- Files stay authoritative until Phase 5 passes; abort = stop using Shipyard for the queue, `.md` files still current. Post-cutover rollback = regenerate `now/next/later.md` from `shipyard wi list --format json`.

## Acceptance criteria

1. Zero-loss: every current dayfold queue item, inbox item, spec, ADR, design, and governance doc is reachable in Shipyard (as WI/InboxItem/Doc) **or** an explicit, linked repo file.
2. Guardrails from `context/` demonstrably appear in an agent's assembled context (`shipyard context WI-n`).
3. Hard-date/time-sensitive items surface with their date, not buried in prose.
4. A dayfold agent loop can `next --acquire` → work → `wi done` end-to-end.
5. Rollback path verified (can regenerate the `.md` queue from Shipyard).

## Non-goals

- Cleaning up dayfold's ~150 stale branches/worktrees (separate hygiene task).
- Replaying `now-history.md` into the event log.
- Migrating research/roadmap into DB primitives beyond indexed files (revisit post-cutover).
- CI-red remediation itself (that's dayfold product work; the migration only ensures its *status* has a home — G2).
