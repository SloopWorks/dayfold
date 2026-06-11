# Process — Research Workflow

How research is produced so it can be trusted years later.

## Pipeline

1. **Frame.** Write the question(s) and what decision they feed (which ADR
   or gate). Underspecified questions get narrowed before any searching.
2. **Fan out.** Independent agents per domain (market stats, competitors,
   platform/tech, compliance, pricing), each with web access. Domains are
   blind to each other — convergence is signal. Script skeletons:
   `processes/fleet-patterns.md`.
3. **Cite or die.** Every claim gets a verdict — `confirmed` /
   `partially-confirmed` / `refuted` / `unverifiable` — with at least one
   URL actually consulted. Training-memory assertions without a live source
   are marked unverifiable. Primary sources (vendor pricing pages, official
   docs, regulators, statutes, court PDFs, shipped SDK source) outrank
   roundups.
4. **Adversarial pass.** At least one hostile reviewer attacks the
   synthesis: fatal risks, inherited assumptions, internal contradictions.
   Plus a strategist-grade pressure-test when the research feeds a
   go/no-go.
5. **Synthesize.** One dated report in `research/`: verdict first, then
   findings with `[fact:source]` / `[estimate]` / `[assumption]` labels,
   corrections to prior documents listed explicitly, sources at the end.
   **Archive the raw per-agent outputs** alongside the synthesis
   (`research/<topic>-agent-outputs/`) — fleet results land in temp dirs
   and are lost otherwise.
6. **Promote.** Durable conclusions → ADRs/context; new unknowns →
   `context/open-questions.md`; dead claims corrected in the documents that
   carried them (with a changelog note, never silently).

## Rules

- Reports are dated snapshots. Never edit an old report to match new
  findings — write a new round and link back.
- A claim repeated across our own documents counts as ONE source, not
  three. Verification must trace to the outside world.
- Stats imported across contexts (e.g. SaaS benchmarks → brick-and-mortar)
  are `partially-confirmed` at best until verified in the target context.
- Competitor non-existence is a finding worth as much as existence; so is
  an exhaustive negative ("searched X, Y, Z — nothing"), reported with the
  queries run.
- Every fleet prompt carries a **ground-truth block**: what has NOT
  happened yet (nothing built, nobody contacted, no entity, no spend) — and
  hard rules (no contacting anyone, no records requests, no sign-ups).
- Research older than ~6 months feeding a live decision gets re-verified.
