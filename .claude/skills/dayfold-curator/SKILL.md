---
name: dayfold-curator
description: Use when setting up dayfold for a person/family, deciding what hubs to create, authoring dayfold content from email/calendar/files/notes, or asking "what should be on my dashboard" / "enrich my hubs". Analyzes context, runs an onboarding questionnaire, then authors Hubs + BriefingCards through the dayfold CLI — propose-confirm before every push.
---

# Dayfold Curator

Turn a person's scattered context (email, calendar, files, notes/second-brain)
into **dayfold content** — Hubs and BriefingCards — authored through the
`dayfold` CLI. The dashboard renders intelligence; this skill produces it. It is
not a chatbot.

## The one test for every piece of content

> Imagine yourself in the user's position going about their day. What content,
> surfaced in dayfold, would stop them digging through multiple apps or searching
> their notes/second brain? **Link directly** to the app/content when possible;
> otherwise **embed** a snippet or the info itself.

## Before anything: read the references

- `references/cli.md` — the exact `dayfold` commands (the ONLY ones that exist).
- `references/content-model.md` — the card/hub/block shapes you author.
- `references/guardrails.md` — privacy/consent/provenance rules. **Binding.**

## Prereq gate (always first)

Run `dayfold whoami`. If it prints `(legacy)` with empty family, or errors, STOP
and tell the operator to run `dayfold login` first. Do not author without a
resolved family.

## Phase A — Onboard (first run per family)

1. **Ingest context.** Use what the operator pastes/points at, plus actively read
   their connected MCPs when available: Gmail (their OWN mail — see guardrails),
   Calendar (events, recurring commitments), Drive (documents/links they keep).
   If an MCP isn't connected, say so and continue with what you have.
2. **Deep-analyze → cluster** signals into candidate Hubs from the bounded catalog
   (`vacation, starting-college, move, party-event, new-baby, medical, school-year`).
   For each candidate name: the life-thread, the signals feeding it, why it matters
   now.
3. **Onboarding questionnaire — one question at a time.** Confirm: adult family
   members (account holders are adults only), which threads matter, hub priority
   order, privacy comfort (what may be read, what stays on-device).
4. **Output an agreed hub map.** Do NOT push in this phase.

## Phase B — Author (propose → confirm → push)

For each agreed hub:
- Start from `dayfold template hub` (and `section`, `block`), fill real fields,
  **show the operator the JSON**, push on approval:
  `dayfold push <id> hub.json --hub` (then `--section`, `--block` for children).

For each signal worth surfacing **now**:
- Author a BriefingCard of the right `type` from `dayfold template <type>`, set
  `target` to deep-link its hub, add `triggers` for time/place relevance, set an
  honest `privacy.storage` chip, `provenance.source = "claude"`. Validate + show
  JSON, push on approval: `dayfold push <cardId> card.json --type <type>`.

Batch a hub's whole tree (or a set of cards) into one approval, but NEVER push an
un-approved batch. If the server returns non-200, surface the body, fix, re-push.

## Phase C — Enrich (on-demand, over existing state)

1. `dayfold pull` (and `dayfold pull --hub <id>`) to read current hubs + cards.
2. **Empathy pass.** Walk the user's day against what exists. For each moment ask:
   *would they have to open another app or search notes to handle this?* Each "yes"
   is a gap.
3. For each gap, pick the surfacing form, in priority order:
   - **Link directly** — deep link / `location.mapUrl` / source email thread URL /
     document ref. Always preferred.
   - **Embed** — `body_md` snippet, `contact`/`checklist`/`milestone`/`budget`
     payload — when no direct link exists or the info is small and the point is to
     skip the click.
4. Propose new cards/blocks → confirm → push (same flow as Phase B). Only propose
   net-new content; do not duplicate what `pull` already returned.

## Always

- Propose-confirm before EVERY push.
- Honest privacy chips; own-mail-only email; adults-only accounts; `provenance` on
  everything. See `references/guardrails.md`.
