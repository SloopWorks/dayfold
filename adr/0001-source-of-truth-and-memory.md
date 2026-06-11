# ADR 0001: Repo Markdown as Source of Truth; Memory Systems as Working Memory

## Status

Proposed (template default) — verify and accept at bootstrap (BOOTSTRAP
Phase 3), stamping the project date.

## Context

The project is operated largely by agents across many sessions, possibly
with a persistent memory layer attached. Without explicit governance,
the freshest context wins and direction decays into contradiction.

## Decision

Reviewed Markdown in this repo is the source of truth. Any memory system
holds working memory only: session continuity, retrieval, cross-session
context. When build-phase tracking starts, the issue tracker is the live
working layer; definitions originate in repo Markdown and durable outcomes
are promoted back at close-out. A new requirement must never first appear
in an issue or a memory entry.

Any memory or working note affecting product scope, pricing,
legal/compliance posture, platform or vendor choices, customer-data
handling, automation boundaries, or maintenance burden must be promoted
into an ADR or a source-of-truth Markdown file before acting on it.

Conflict resolution order is defined in `CLAUDE.md`.

## Rationale

Proven across two predecessor projects without decision drift — see
`context/operating-lessons.md` §Governance.

## Consequences

Positive: auditable decision trail; safe agent autonomy; sessions are
disposable.
Negative: promotion discipline costs minutes per session; stale Markdown
must be actively reconciled (close-out routine).

## Revisit Trigger

A memory system gains reviewed/approval semantics that match repo review.
