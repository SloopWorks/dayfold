# Dayfold

A calm, AI-powered household dashboard. One account per family, members log
in (adults at MVP). It reads the family's existing signals — calendar,
email, lists/tasks, weather, location — and renders a single sleek daily
**briefing** plus a short list of **smart recommended actions** with deep
links ("party Saturday — ordered groceries? [list]"; "school email needs an
RSVP Thursday [reply]"; "rain at soccer 4pm — pack jackets").

Built mobile-first on **Compose Multiplatform** (Android/iOS/Web). The MVP
is a **content API + CLI + Claude skill**: external AI loops and scheduled
tasks author/update the cards — the dashboard *renders* intelligence
produced elsewhere; it is not an open-ended chatbot. Dogfooded on the
operator's own household first. Primary purpose: a **learning lab**; durable
side income is a co-goal.

> **Status (2026-06-18):** bootstrapped; entering the planning loop, Phase A.
> Validation round 1 verdict: **CONDITIONAL — learning-lab GO,
> standalone-business NO-GO.** The generic "AI family briefing" is being
> commoditized by Gemini Daily Brief / Alexa+ and funded verticals; the one
> defensible (time-sensitive) surface is a **multi-member family-tenant
> briefing**. See `research/validation-round1-2026-06.md` and `adr/0004`.

## Orientation

- [CLAUDE.md](CLAUDE.md) — session protocol, governance, directory map
- [context/values-and-direction.md](context/values-and-direction.md) — operator-owned north star
- [context/business-constitution.md](context/business-constitution.md) — identity + scope firewall (what it is NOT)
- [adr/0004-product-framing.md](adr/0004-product-framing.md) — what this is, what it isn't, MVP scope
- [research/validation-round1-2026-06.md](research/validation-round1-2026-06.md) — validation verdict + evidence
- [planning/workstreams.md](planning/workstreams.md) — the live waterfall board
- [backlog/operator-inbox.md](backlog/operator-inbox.md) — items awaiting the operator
- [backlog/now.md](backlog/now.md) — current immediates

## Running the loop

- One-shot: open a session here and say **"run a loop iteration"** (follows
  `processes/planning-loop.md`).
- Sweep `backlog/operator-inbox.md` weekly.

## Lineage

Built from the **venture-loop template** (extracted from the KeepQR /
RevenueCatch projects). Process inspiration also drawn from the sibling
`ambient-ai` spec repo ("render, don't reason"; ADR + open-questions
discipline; persona-driven key moments).

## Curator skill (Claude Code)

`.claude/skills/dayfold-curator/` is the authoring wedge — a Claude Code skill
that analyzes your context, runs an onboarding questionnaire, and authors dayfold
Hubs + BriefingCards through the `dayfold` CLI (propose-confirm before every push).

Install globally (all projects on this machine):

```
sh .claude/skills/dayfold-curator/install.sh
```

Or per-project: copy `.claude/skills/dayfold-curator/` into another repo's
`.claude/skills/`. Requires `dayfold` on PATH and `dayfold login` done first.
