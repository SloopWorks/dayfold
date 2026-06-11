# Goals and Constraints

North star and decision values live in `context/values-and-direction.md`
(operator-owned). Kill thresholds live in `context/kill-switches.md`. This
file holds the quantitative targets and hard constraints.

## Operator context

{{OPERATOR_CONTEXT — who is building this: skills, day-job status,
geography, existing assets (entities, audiences, prior projects,
playbooks), relevant relationships.}}

## Goals (targets, not facts — label sources)

| Goal | Target | Source status |
|---|---|---|
| First revenue | {{TARGET}} | [estimate] |
| Revenue at month {{N}} | {{TARGET}} | [estimate] |
| Margin | {{TARGET}} | [estimate] |
| {{EXIT_OR_SCALE_GOAL}} | {{TARGET}} | [estimate] |

## Hard constraints

- **Cash:** {{CASH_CEILING}} to first revenue; {{ONGOING_BUDGET}} ongoing.
- **Operator time:** {{BUILD_HOURS}}/wk during build; **{{STEADY_HOURS}}/wk
  steady state** — a first-class product requirement that drives
  automation-first design.
- **Compliance walls:** {{DOMAIN_COMPLIANCE_CONSTRAINTS}}.
- **{{OTHER_HARD_CONSTRAINTS — day-job compatibility, no-SLA rules, etc.}}**

## Validation gates before build

1. Initial validation round complete (claims verified with citations,
   adversarial review passed) — `research/`.
2. {{DOMAIN_GATE — e.g. the legal/regulatory question that gates client
   work}}.
3. {{PRICING_GATE — pricing model stress-tested at the actual customer
   scale}}.
4. {{FIELD_GATE — N real customer conversations confirming the core
   assumptions}}.
5. **Confidence case meets the operator's bar** (values file): affirmative
   evidence per pillar, adversarially reviewed.
