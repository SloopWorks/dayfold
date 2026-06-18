# ADR 0007: Prototype Scope — Operator-Driven Dumb Renderer

## Status

**Accepted** 2026-06-18 (operator inbox sweep, INB-8; dependency ADR 0006
accepted in the same sweep). Immutable — supersede, do not edit. Scopes the
first build (the dogfood prototype, Gate G1a in `planning/workstreams.md`).
Refines ADR 0004 (content-API MVP) and ADR 0006 (Event Hubs) into a concrete
buildable prototype; defers a set of features explicitly rather than leaving
them as open gaps. **Build of this prototype is itself gated behind the
hi-fi UI/UX mockup requirement (ADR 0008).**

## Context

Validation round 1 returned CONDITIONAL (learning-lab GO). The operator
fixed the prototype's shape: a **dumb UI**, with the operator acting as the
intelligence via Claude Code, pushing curated content to a cloud-hosted DB
through the content API, viewed on native mobile clients. A bootstrap review
surfaced gaps (push, auth, roles, Hub permissions, native calendar) — most
of which are **out of scope for this prototype by design**, not omissions.
This ADR records the boundary so the planning loop builds exactly the
prototype and nothing more.

## Decision

**The prototype is an operator-driven dumb renderer.**

**In scope:**
1. **Cloud-hosted DB + content API** — the app owns the rendered data
   (ADR 0006). Idempotent upsert for briefing cards and
   Hub/Section/Block (schema is the contract).
2. **Claude Code push skill / CLI** — the operator's authoring loop; the
   primary dogfood + learning artifact. The "intelligence" lives here, not
   in the app.
3. **Two render surfaces** — "Now" (briefing cards) and "Hubs" (Event Hub
   dossiers), co-equal (ADR 0006).
4. **In-app tap-through** — card → hub/block routing via internal
   navigation (NOT Universal/App Links yet).
5. **Native Android + iOS clients via Compose Multiplatform** (one Kotlin
   codebase → both native apps). **Web is out of prototype scope.**
6. **Minimal auth** — a single household API token so the DB isn't open. No
   login UX, no multi-member identity.

**Out of scope (deferred by design, each its own future ADR/spec when
revisited):**
- Push notifications / FCM / APNs (pull-to-view only). [was review gap G1]
- Multi-member login, household tenancy, roles. [G2]
- Per-Hub / per-member visibility & permissions. [G3]
- In-app authoring or any app-side intelligence/curation. [G4 — this is the
  deliberate bet: the operator + Claude Code are the brain]
- Any data-source integration — Google Calendar API, native EventKit/
  CalendarContract, Gmail, weather, Instacart/commerce. Calendar-derived
  content is simply pushed by the operator. [N4]
- Universal Links / App Links + domain-association files. [N1/N2]
- Home-screen widgets. [N5]
- Document upload/storage (links + small refs only, per ADR 0006).
- 14+ minor accounts (ADR 0005) — prototype is operator/adult only.

## Rationale

This is the cheapest path to daily dogfood value and maximal learning
(content-API + Claude-authoring loop + CMP native rendering), and it dodges
every fatal wall validation found: no OAuth, no push cost, no COPPA surface,
no CASA, no integrations, no web. Every deferred item is additive later
without rework, because the content API + render split is the stable spine.
The dumb-renderer choice makes "render, don't reason" literal.

**Rejected:** building any integration, auth, or push into the prototype —
rejected as premature cost/risk before the operator has even confirmed daily
personal value or swept the inbox kill-checks.

## Consequences

Positive: buildable now by a solo dev in weeks; learning-complete regardless
of business outcome; clean deferral list instead of ambiguous gaps.
Negative: the prototype proves operator value + the authoring ergonomics,
NOT willingness-to-pay or multi-user dynamics (consistent with the
learning-lab framing; the skeptic's "dogfood proves enthusiasm not WTP"
point stands and is accepted). iOS device deploy needs an Apple account
(free 7-day re-sign or $99/yr stable) — the one unavoidable native friction.

## Revisit Trigger

Prototype in daily operator use → decide which deferred item earns the next
build (likely: a second household → forces auth G2 + visibility G3; or
integrations → forces N4 + the restricted-scope/COPPA ADRs). Each deferred
item re-enters via its own ADR/spec.
