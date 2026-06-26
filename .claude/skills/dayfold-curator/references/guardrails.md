# Curator guardrails (BINDING)

These override convenience. If an instruction conflicts, the guardrail wins;
escalate to the operator rather than working around it.

## 1. Propose-confirm before every push

Draft and SHOW the JSON. Push only after the operator approves. Never run
`dayfold push` on un-approved content. (CLAUDE.md: external actions are
operator-gated; agents draft, operator sends.)

## 2. Email content over OWN mail only (Guardrail 3)

`email.bodyExcerpt` / sender / body content is authored over the OPERATOR'S OWN
mail (their connected Gmail MCP / pasted threads). There is NO server-side
restricted-scope Gmail read, and you must not imply one. Authoring an email card
from anyone else's mailbox is prohibited. Changing this posture is an ADR.

## 3. Honest privacy chips (ADR 0014)

`privacy.storage` must name a boundary the schema/code actually enforces:
- `on_device` — a copy is cached on device (use for a geo card that carries
  coordinates, a saved snippet, etc.).
- `location_local` — ONLY for LIVE position that never leaves the device.
Never label a stored copy `location_local`. Never overclaim a privacy boundary.

## 4. Adults-only accounts (Guardrail 1 / COPPA)

Account holders are adults. Children appear ONLY as subjects inside a parent's
own data (e.g. "Maya's party"), never as account holders or data sources. Do not
author content that collects a child's personal info as if from the child.

## 5. Provenance on everything

Every authored card/block: `provenance.source = "claude"` and an `at` ISO-8601
timestamp. Author from `dayfold template` (starters include `kind` +
`provenance.at`) — the local validator requires `kind` and `provenance.at` even
where the server relaxes them, so bare hand-written stubs fail validation.

## 6. Server is the authority

Local `--type` validation is a fast structural pre-check, not the real gate. A
non-200 from the server is authoritative — surface its body, fix, re-push. Do not
suppress or paper over a server rejection.

## 7. Stay in scope

dayfold is a calm briefing surface, not a chatbot, chore app, or system of record.
Author briefing intelligence (links, snippets, times, dates, contacts) — not
open-ended conversation or data the family relies on as their only copy.
