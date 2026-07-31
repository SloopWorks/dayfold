---
name: dayfold-curator
description: Use when setting up dayfold for a person/family, deciding what hubs to create, authoring dayfold content from email/calendar/files/notes, or asking "what should be on my dashboard" / "enrich my hubs". Analyzes context, runs an onboarding questionnaire, then authors Hubs + BriefingCards through the dayfold CLI — propose-confirm before every push.
---

# Dayfold Curator

Turn a person's scattered context (email, calendar, files, notes/second-brain)
into **dayfold content** — Hubs and BriefingCards — authored through the
`dayfold` CLI. The dashboard renders intelligence; this skill produces it. It is
not a chatbot.

## What a hub is

A hub is one **area of the family's life** — a trip, a move, a school year, a
party. It is not where the information lives. The source of truth stays where it
already is: the email thread, the Drive doc, the venue's website, the contact in
their phone. A hub is the **collecting point and the jumping-off point** for one
of those areas — what was scattered across five apps, gathered, cut to what's
actually needed, pointed back at the original, and surfaced when it matters:
`triggers` for *when*, links for *where*, and views (`timeline`, `checklist`)
that make the shape of the thing readable at a glance.

Corollary of guardrail 7: never let a hub become the family's only copy of
something. If a fact exists nowhere else, you've made dayfold a system of record,
which it must not be.

Hub `type` comes from a **bounded catalog** (`vacation`, `starting-college`,
`move`, `party-event`, `new-baby`, `medical`, `school-year`) — the CLI rejects an
off-catalog type *locally*, before the server sees it. A life area fitting none
of them maps to the nearest key or is out of scope; adding a key is an ADR, not
an authoring decision.

## The one test — walk their day

> Imagine yourself in the user's position going about their day. What content,
> surfaced in dayfold, would stop them digging through multiple apps or searching
> their notes/second brain?

Walk it beat by beat — waking up, the morning routine, the school or work run,
errands, pickups, the evening, the night before something big. At each beat ask:
*to handle this, would they have to open another app or search their email?*
Every "yes" is a candidate. This is the same pass Phase A clusters from and
Phase C re-runs against content that already exists.

## How content should read

- **Concise and legible.** A card is read in a glance between two other things.
  One idea per card; the detail belongs in the hub it deep-links into.
- **Link first, embed second.** Point at the original — the thread, the doc, the
  site, the map, another piece of dayfold content. Embed a snippet only when no
  direct link exists, or when the info is small and the whole point is skipping
  the click. Never retype what you could link. See `references/content-model.md`
  → **Link-first authoring** for which field carries which link (and the
  `https/mailto/tel/geo/sms` scheme allowlist — anything else renders as dead
  plain text).
- **Early enough to act.** "Address to the party" is worthless on arrival. Timing
  is a field, not a phrasing: `triggers[].when.at` with an `alert_offset`, plus
  `not_before` / `expires_at` for the show/hide window.
- **Contacts carry their context.** A phone number alone makes them remember who
  it was. Give the `contact` its role and why it's here (`related[]` +
  `relatedKicker`, e.g. `"FROM THE SAME EMAIL"`).

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
   Cluster by **life area**, then pick the catalog key that fits — not the other way
   round. For each candidate name: the life-thread, the signals feeding it, why it
   matters now. Walk their day (see **The one test**) to find the threads that
   scattered context is actually costing them.
3. **Onboarding questionnaire — one question at a time.** Confirm: adult family
   members (account holders are adults only), which threads matter, hub priority
   order, privacy comfort (what may be read, what stays on-device).
4. **Output an agreed hub map.** Do NOT push in this phase.

## Phase B — Author (propose → confirm → push)

Everything here is written to **How content should read** above — concise,
link-first, timed to arrive early enough to act on — and reviewed twice (below)
before the operator ever sees it.

For each agreed hub:
- Start from `dayfold template hub` (and `section`, `block`), fill real fields,
  **show the operator the JSON**, push on approval:
  `dayfold push <id> hub.json --hub` (then `--section`, `--block` for children).
- Add lightweight VISUAL enrichment for warmth + scanability: an `icon` + `accentColor`
  on the hub's (or card's) `media` — no image URL needed, so nothing to allowlist (see
  `references/content-model.md` → Visual enrichment). Hero/thumbnail IMAGES are allowlisted
  + operator-surfaced (guardrail 8) — prefer icon+accent unless an image clearly earns it.
- For a **dated** hub (a move, a trip, college move-in, a party day), add a `timeline`
  (ADR 0045) so it gains an axis of time — a live day rail and/or a multi-month roadmap.
  Start from `dayfold template timeline`, author the **stops** only (the client picks the
  scale), push with `--hub`. See `references/content-model.md` → Hub `timeline`.

For each signal worth surfacing **now**:
- Author a BriefingCard of the right `type` from `dayfold template <type>`, set
  `target` to deep-link its hub, add `triggers` for time/place relevance, set an
  honest `privacy.storage` chip, `provenance.source` = the agent authoring it
  (`"claude"`, `"codex"`, … — guardrails §5). Validate + show
  JSON, push on approval: `dayfold push <cardId> card.json --type <type>`.
  A place `trigger` only reaches background notify via `place_ref` to a saved
  place — a plain lat/lng `trigger` is foreground-only (see `references/
  content-model.md` → `triggers[]`); don't imply otherwise to the operator.

Batch a hub's whole tree (or a set of cards) into one approval, but NEVER push an
un-approved batch. If the server returns non-200, surface the body, fix, re-push.

## Review twice before pushing

Drafting is not finishing. Review the JSON twice before the operator sees it, and
say that you did.

**Round 1 — correct and non-duplicative.** Every fact traceable to a real source;
no invented times, addresses, or names. Required fields present, ids valid ULIDs,
checklist item ids reused from `pull` (a fresh id drops a member's checked state).
Run it against `dayfold pull` — is this already here under another title?

**Round 2 — useful and well-formed.** Put it back in the day: does it survive the
walk, or is it merely tidy? Is it concise and scannable? Is anything retyped that
could have been linked? Does it arrive early enough to act on?

Content that fails round 2 gets cut, not shipped. A thin dashboard the family
trusts beats a full one they scroll past.

## Phase C — Enrich (on-demand, over existing state)

1. `dayfold pull` (and `dayfold pull --hub <id>`) to read current hubs + cards.
2. **Empathy pass.** Re-run the day-walk (see **The one test**) against what
   already exists. Each "yes, they'd still have to go digging" is a gap.
3. For each gap, pick the surfacing form per **How content should read** — link
   first, embed only when there's nothing to link or the click isn't worth it.
4. Propose new cards/blocks → confirm → push (same flow as Phase B, same two
   review rounds). Only propose net-new content; do not duplicate what `pull`
   already returned.
5. Stale or superseded content is a `dayfold delete` candidate (a hub/card that no
   longer reflects reality) — propose it explicitly, same confirm bar as a push
   (guardrail 9), never delete silently.

## Always

- Propose-confirm before EVERY push (or delete).
- Two review rounds before every proposal — correctness/dedup, then usefulness/form.
- Honest privacy chips; own-mail-only email; adults-only accounts; `provenance` on
  everything. See `references/guardrails.md`.
