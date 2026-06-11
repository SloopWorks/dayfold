# Process — Fleet Patterns

Reusable multi-agent fleet prompt blocks, distilled from live runs. Use
with an orchestration tool (e.g. Workflow) where available; **if the
harness has none, run the same agent roster as parallel subagents** —
everything below except the resume notes still applies. Agents get web
access and read repo files by absolute path. Always: ground-truth block,
citation rules, structured schema, archive raw outputs after.

## Shared blocks (paste into every fleet)

```text
CTX  = files to read first (the docs under test / the decision they feed)
       + 1-paragraph business description + "Today is <date>".
TRUTH = "GROUND TRUTH — what has NOT happened: nothing built, no
       customers/prospects contacted, no entity formed, no money spent
       [edit to fit]. RULES: research only — do NOT contact anyone, no
       records requests, no sign-ups. Mark anything requiring an account
       or live test RESIDUAL for the operator."
CITE = "For EVERY claim include ≥1 source URL actually consulted. Verdicts:
       confirmed / partially-confirmed (explain delta) / refuted (quote the
       contradiction) / unverifiable (state what you searched). Do NOT mark
       confirmed from training memory; search. Primary sources outrank
       roundups. Negative results are findings — report queries run."
SCHEMA (verification): { claims: [{claim, verdict, evidence, sources[]}],
       new_findings: [{finding, why_it_matters, sources[]}] }
SCHEMA (domain dig):   { summary, findings: [{topic, detail, confidence
       (high|medium|low), sources[]}], residual_operator_checks[] }
```

## 1. Validation fleet (bootstrap / periodic re-validation)

6 verification agents + 2 adversarial, one `parallel()` call, phases
'Verify' and 'Adversarial':

- `market-stats` — verify every demand/market statistic; flag stats
  imported from adjacent contexts.
- `competitors-direct` — verify named competitors' existence + CURRENT
  pricing from vendor pages (comparison pages are circular sources).
- `competitors-missed` — independent sweep for competitors the docs never
  mention; misses are the highest-value finding.
- `tech-platform` — verify integration/capability claims from official
  docs, help centers, **shipped SDK source** (beats rotted docs); check
  whether incumbents/platforms already ship the product natively.
- `compliance` — the regulatory landscape incl. the operator's actual
  jurisdiction. (US ventures: the load-bearing rule is usually state-level,
  not federal. Non-US: identify the jurisdiction's equivalent layers
  first.)
- `pricing-structure` — market anchors for the proposed model + do the
  statistics/arithmetic of the billing mechanism actually work at the real
  customer size (power analysis, contribution margin).
- `adversarial:strategist` — solo-business-strategist-type agent, full
  inputs (budget/hours/geography/stage), must re-run the unit-economics
  math itself; verdict block first.
- `adversarial:skeptic` — hostile reviewer, schema {fatal_risks,
  weak_assumptions (where_stated + attack), internal_inconsistencies
  (doc_locations), verdict}; argues from the documents and logic, names
  the CHEAP kill-criteria checks.

## 2. Domain deep-dive fleet (one board item, 3–4 agents)

Decompose the item into 3–4 blind sub-domains (e.g. for a legal question:
statute/license mechanics, structural alternatives + market practice,
other jurisdictions, practitioner prep). Each agent gets CTX + TRUTH +
CITE + domain-dig schema. Synthesize yourself into a dated `research/`
doc; consult-agenda/checklist outputs shrink expensive human steps.

Legal-research source notes (US; non-US ventures find local equivalents):
FindLaw/Justia/Leagle often 403, Casetext is dead; working: state-court
slip PDFs, Google Scholar (`as_sdt=2006` for case law), CourtListener
**search** API (opinions API is auth-gated), circuit-court CDN PDFs,
statutes at official legislature sites, archive.org for dead vendor pages.

## 3. Two-round adversarial review (any plan/spec/playbook)

Sequential, fresh context each round, never the author:

- **Round 1 — correctness.** Reviewer reads the artifact + the constraint
  docs it must honor. Mandate: compliance leaks, factual errors vs the
  research, math/method errors, privacy/data contradictions, internal
  contradictions, silent amendments to operator-owned thresholds.
  Findings: numbered, severity P0/P1/P2, location, concrete fix. Verdict:
  USABLE-AFTER-FIXES / REWRITE.
- **Apply fixes**, then **Round 2 — simplification.** Mandate: usability at
  point of use, redundancy, overengineering relative to the next N uses
  (not N×10), missing-for-practicality (max 3, only ones likely soon),
  metric sanity. Explicitly forbidden from relitigating round-1 decisions.
  Verdict: SHIP-AFTER-TWEAKS / NEEDS-RESTRUCTURE.

Expect round 1 to find P0s even in carefully-authored drafts — that is the
point, not a failure.

## 4. Operational notes

- Workflows run in background; block on TaskOutput (fleets run ~10–25 min).
- Session restarts kill task handles and may zero the output file —
  **resume via `Workflow({scriptPath, resumeFromRunId})`**; completed
  agents return cached.
- Parse the output JSON, split per-agent files, archive under
  `research/<topic>-agent-outputs/`.
- Cost reality: a 6–8-agent validation fleet ≈ 400–700K subagent tokens; a
  3–4-agent deep-dive ≈ 250–450K. Scale fleet size to the decision's
  weight.
- Watch for agents assuming events that never happened (the TRUTH block
  exists because one analyzed exposure from outreach that never occurred)
  and for plausible-but-stale prices (verify on vendor pages, same week).
