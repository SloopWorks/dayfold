# ADR 0012: Agent-Operated Build & Deploy — Autonomy Boundaries

## Status

**Accepted** 2026-06-18 (operator set the boundaries in-session). Immutable —
supersede, do not edit. **Automation-autonomy boundary** = ADR-class + a hard
guardrail (CLAUDE.md). How-to detail: `processes/agent-build-automation.md`.

## Context

The operator wants agents to configure, deploy, and verify the build with
minimal human steps, using authenticated CLIs/MCPs (Vercel, Firebase,
gcloud, gh) and browser automation (Claude-in-Chrome) for consoles that lack
CLIs. This aligns with the automation-first value and the agent-workflow-lab
secondary goal (constitution). Because autonomy boundaries are a hard
guardrail, they are fixed here.

## Decision

**Autonomy line (operator-set):**

1. **Deploys: full autonomy including production.** Agents may configure,
   deploy to preview AND **promote to production**, and verify — without a
   per-deploy human gate. The operator spot-checks after.
2. **Cost-bearing cloud config: allowed within a budget cap.** Agents may
   enable billable APIs, create least-privilege service accounts, and
   provision resources **up to the standing infra ceiling (< ~$50/mo,
   `context/goals-and-constraints.md`) and any single one-time charge ≤
   $20**. Anything above the cap → escalate (operator-gated). Cap is
   operator-adjustable.
3. **Browser-driven consoles: allowed after operator login.** For steps
   without a CLI (Firebase provider toggles, OAuth consent screen, App
   Check, etc.), the operator authenticates the console; the agent executes a
   written runbook (`processes/agent-build-automation.md`). Operator can
   watch/interrupt.

**Non-negotiable safety rails (apply even under full prod autonomy):**

4. **Test-green-before:** no prod deploy unless unit + integration + Firebase
   Emulator tests pass and a preview deploy verified green first.
5. **Verify-and-rollback-after:** every prod deploy runs an automated
   post-deploy health/smoke check (and, where relevant, a browser-driven
   flow check); **failure auto-rolls-back** (or fails the deploy) and
   escalates. Agents never leave prod unverified.
6. **Log every prod/cost action** to an auditable trail (what, when, which
   credential, result) — for the operator's spot-check.
7. **Reversibility bias:** prefer preview → promote and instant-rollback
   platforms; avoid irreversible operations without escalation.

**This ADR does NOT widen the standing guardrails.** Full *technical*
autonomy stops at the constitution's lines, which remain operator-only
regardless: pricing/billing mechanics; legal/compliance filings (incl.
Google OAuth-verification/CASA, Apple enrollment); **App Store / Play
submission**; spend above the cap; new legal entities; signing; and any
**external/customer-facing action** (emails, messages, anything a user/
vendor sees). Agents draft these; the operator executes.

## Rationale

The stack is largely agent-operable (Vercel MCP, Firebase CLI + Emulator,
gcloud, gh) and the highest-leverage automation is exactly configure/deploy/
verify. Full prod autonomy is acceptable *because* the safety rails make it
verify-gated and reversible, not reckless. The budget cap bounds financial
blast radius; the guardrail carve-out keeps legal/customer/spend risk
operator-owned.

**Rejected:** human-gated promotion (operator chose full autonomy);
no-cost-actions (too limiting — emulator-only can't ship); unbounded spend
(removed the cap-escalation — kept).

## Consequences

Positive: agents run the build/deploy/verify loop end to end; fast iteration;
the project doubles as the agent-ops lab the constitution wants.
Negative: prod autonomy has real blast radius — leans entirely on the rails
(tests, verify, rollback, logging) being honored; a rail gap is a P0. The
budget cap needs periodic review as scale grows. Browser automation depends
on live operator-authenticated sessions (headless/cron runs may lack them).

## Revisit Trigger

A prod incident traces to missing/þskipped rails; the budget cap is hit
repeatedly (raise or re-scope); the stack changes such that a deploy target
lacks preview/rollback (tighten autonomy there); or a customer-facing surface
ships (re-confirm the external-action carve-out holds).
