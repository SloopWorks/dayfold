# Business Constitution

## Identity

**family-ai-dashboard** is a calm, AI-powered household dashboard. One
account per family, each member logs in. It reads the family's existing
signals — calendar, email, lists/tasks, weather, location — and renders a
single sleek daily **briefing** plus a short list of **smart recommended
actions** with deep links ("party Saturday — ordered groceries?
[Instacart] [list]"; "school email needs an RSVP by Thursday [reply]";
"rain at soccer 4pm — pack jackets"). It surfaces what matters now and the
one next step; it does not try to be the family's whole digital life. The
differentiator: recommendations are **bounded to a curated template
catalog** and can be **authored by external AI loops via a content API** —
the dashboard renders intelligence produced elsewhere; it is not an
open-ended chatbot.

## What it is not

The scope firewall. Each line is a real, nearby failure mode — the adjacent
business this could profitably-but-wrongly drift into.

- **Not a family chat/messaging or social app.** No DMs, no feed, no
  comments, no presence pings. The moment it competes for attention rather
  than saving it, it has betrayed the "calm" promise. Communication stays
  in the tools families already use.
- **Not a chore-chart / allowance / kids-reward / gamified app.** That is a
  different product (and a different, COPPA-heavier, engagement-maximizing
  posture). We surface upcoming activities and one next step, not points,
  streaks, or behavior management.
- **Not a calendar / email / list replacement.** It **reads** those
  systems and links back into them. It never becomes the system of record
  the family must migrate into; that raises switching cost, lock-in
  expectations, and data-custody burden we explicitly reject.
- **Not an open-ended AI chatbot or general assistant.** The product's
  surface is curated cards with visible provenance, never a conversational
  oracle. Members may issue **bounded, asynchronous, no-reply commands** to
  a key-holding AI loop that authors a result card later (e.g. "research and
  make a card about X") — but there is **no chat, no reply thread, no
  real-time assistant**, a command acts **only within the submitter's own
  visibility and never destructively**, and reasoning runs **only in a
  key-holder** (never a hosted server-side LLM). This keeps cost,
  hallucination, and privacy exposure capped while allowing member-authored
  intelligence. A *conversational* surface (replies, threads, real-time)
  remains out of scope and would need its own superseding ADR.
  (Amended per ADR 0041, 2026-06-29.)
- **Not a venture-scale startup.** Operator-scale economics — high margin,
  near-zero steady-state ops, optionally sellable. No funding-dependent
  growth motion.

## Customer-experience guarantees (product identity, not marketing)

A feature that erodes any of these requires a superseding ADR.

- **Family data is never sold, brokered, or used to train shared models.**
  It is used only to render that family's own dashboard.
- **Never spam, never engagement-bait.** Notifications are few, timely, and
  earn their interruption. Default to quiet.
- **The family owns its relationship and its data.** Clean export and
  delete on request; no dark-pattern retention.
- **Children's data is treated as the highest-sensitivity tier.** Minors'
  profiles follow the strictest applicable consent posture (COPPA / state
  AADC); when in doubt, the parent account is the gatekeeper.
- **AI is honest about its limits.** Recommended actions are suggestions
  with their source visible; the product never fabricates an event or a
  task the family didn't have.

## Agent-workflow goal (secondary purpose)

This project doubles as a laboratory for agentic operation: research
fleets, planning loops, scheduled content authoring, ops automation,
monitoring, reporting — under the routing and escalation rules in
`processes/agent-routing.md`. The content-API-fed MVP is itself the first
experiment: AI loops author the dashboard's cards. Steady-state target:
**≥90% of operational and content-refresh events touch no human.**

## Hard guardrail

Scope, pricing, compliance posture, customer-data handling, and automation
boundaries change only through an ADR. Do not let dogfood convenience,
feature requests, or agent memory drift the product into the
"what it is not" list — especially toward chat, gamification, or becoming
the family's system of record.
