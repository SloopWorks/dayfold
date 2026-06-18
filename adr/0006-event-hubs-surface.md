# ADR 0006: Event Hubs — A Co-Equal Curated-Dossier Surface, App-Owned, Push-Curated

## Status

**Accepted** 2026-06-18 (operator inbox sweep, INB-7). Immutable —
supersede, do not edit. Extends ADR 0004 (Product Framing). The Event Hubs
design (`specs/event-hubs-design.md`) is now cleared to become a binding
spec at board item C1b.

## Context

ADR 0004 framed the product as a calm briefing surface that renders
ephemeral, template-bounded cards produced by external AI loops. Validation
round 1 found that briefing surface commoditized (Gemini Daily Brief,
Alexa+, Maple, Ohai) and named the absence of a defensible wedge as the core
weakness.

The operator proposed **Event Hubs**: persistent, AI-curated dossiers for
family events (vacation, starting college, move, party, medical) holding all
info, links, documents, and checklists in one place — authored/curated by
Claude Code and AI loops, pushed into the platform. No briefing incumbent
ships this. It is the strongest candidate for the defensible surface ADR
0004's revisit-trigger (a) anticipated.

This expands scope beyond "render ephemeral cards" to "own and render
persistent curated dossiers," which touches the business-constitution
"not a system of record" line and therefore requires an ADR.

## Decision

1. **Add Event Hubs as a co-equal surface** alongside the daily briefing
   ("Now" and "Projects" peers, one data model, two views). Briefing cards
   deep-link into Hubs; imminent Hub items emit briefing cards.
2. **The app owns and stores the rendered content** — the hosted store is
   the single source of truth for what users see. Power users may keep their
   own upstream source (git repo, laptop, scraped data) and **instruct
   Claude to push** updates via the content API (idempotent upsert). The app
   does **not** read users' repos; curation is push-only.
3. **Curation stays external and push-based** ("render, don't reason"): AI
   assembles blocks outside the app and pushes them. App-side source
   ingestion (auto-pull from Calendar/email) remains out of MVP scope and
   ADR-gated, preserving ADR 0004/0005 restricted-scope and COPPA avoidance.
4. **Hub types are a bounded template catalog** (per ADR 0004), not open-
   ended inference. Provenance is shown on every block.
5. **A Hub is a derived, curated dossier the app owns — not a replacement
   for the family's calendar/email/lists.** Hubs point into those systems;
   they do not become the system of record for them. This reconciles the
   constitution "not a system of record" line: the prohibition is on
   *replacing the family's existing systems*, not on owning a curated
   artifact derived from them.
6. **Documents at MVP = links + small file refs only.** Full upload/storage
   and its privacy tier are post-MVP (C4 security model).

## Rationale

Event Hubs move the venture's bet onto an un-commoditized surface while
reusing the exact content-API/push-curation machinery ADR 0004 already
committed to — low marginal architecture cost, high differentiation upside.
Keeping the app as the single render source (vs git-backed rendering) keeps
non-technical family members first-class and sync simple, while the push
model still delivers the power-user "author anywhere, Claude integrates"
flow the operator wanted.

**Rejected alternatives:** (a) **Git-backed / app-renders-from-repo** —
rejected: complicates sync and shuts out non-technical members; the operator
chose app-owned-store with push. (b) **Hubs as a minor sub-feature of the
briefing** — rejected: under-uses the one differentiated surface. (c) **App
runs open-ended curation/ingestion at MVP** — rejected: re-arms restricted-
scope/COPPA risk and breaks "render, don't reason."

## Consequences

Positive: a defensible, incumbent-free surface; reuses content-API +
push-curation; strengthens the ADR 0004 flip-condition path; great dogfood
value (vacation/college dossiers are real operator needs).
Negative: scope + maintenance grows (a second surface, Hub schema, archival);
documents raise sensitivity even as refs; "app owns curated dossiers" edges
nearer the system-of-record line and must be policed by §Decision 5; another
surface to keep calm and not bloat.

## Revisit Trigger

Any of: Hubs are not used in dogfood after a fair trial (drop the surface);
demand to auto-ingest sources into Hubs (new ADR — re-opens restricted-scope
risk); document upload/storage is added (privacy-tier ADR); or the surface
starts drifting toward replacing the family's calendar/email/lists
(§Decision 5 breach → re-scope).
