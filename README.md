# Venture Loop Template

A project-agnostic scaffold for taking a business idea from raw concept
through validated, adversarially-reviewed, agent-operated deep planning —
the system extracted from the KeepQR and RevenueCatch projects.

What it gives you:

- **Governance** that survives years of agent sessions: immutable ADRs,
  operator-owned values, repo Markdown as source of truth, memory rules.
- **The planning loop**: an autonomous iteration protocol (orient → select
  → execute → integrate → review → improve → close) over a waterfall
  workstream board, with confidence-gated agent decisions and an operator
  inbox.
- **Validation machinery**: multi-agent research fleets with cite-or-die
  rules, mandatory two-round adversarial review, standing P0 viability
  re-attacks, and measurable kill switches.
- **Self-improvement**: a loop journal with per-iteration improvement
  candidates and periodic meta-reviews.

## How to use

1. Copy this folder: `cp -r venture-loop-template my-new-venture`
2. `cd my-new-venture && git init && git add -A && git commit -m "Template"`
3. Open a Claude Code session there and say:
   **"Bootstrap this project: \<your business idea in 1–3 sentences\>"**
   The agent follows [BOOTSTRAP.md](BOOTSTRAP.md) end to end — it interviews
   you for values and constraints, fills every `{{PLACEHOLDER}}`, seeds the
   ADRs, runs the initial validation fleet, builds the workstream board,
   and arms the loop.
4. Thereafter: "run a loop iteration" (or schedule it hourly). You sweep
   the inbox weekly.

## Orientation (post-bootstrap)

- [CLAUDE.md](CLAUDE.md) — session protocol, governance, directory map
- [context/values-and-direction.md](context/values-and-direction.md) — operator-owned north star
- [processes/planning-loop.md](processes/planning-loop.md) over [planning/workstreams.md](planning/workstreams.md)
- [context/operating-lessons.md](context/operating-lessons.md) — why the template is shaped this way

Files containing `{{PLACEHOLDER}}` tokens are filled at bootstrap; nothing
else needs editing to start.
