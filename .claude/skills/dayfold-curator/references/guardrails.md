# Curator guardrails (BINDING)

These override convenience. If an instruction conflicts, the guardrail wins;
escalate to the operator rather than working around it.

## 1. Propose-confirm before every push

Draft and SHOW the JSON. Push only after the operator approves. Never run
`dayfold push` on un-approved content. (CLAUDE.md: external actions are
operator-gated; agents draft, operator sends.)

## 2. Email content over OWN mail only (mirrors CLAUDE.md guardrail 3)

`email.bodyExcerpt` / sender / body content is authored over the OPERATOR'S OWN
mail (their connected Gmail MCP / pasted threads). There is NO server-side
restricted-scope Gmail read, and you must not imply one. Authoring an email card
from anyone else's mailbox is prohibited. Changing this posture is an ADR.

## 3. Honest privacy chips (ADR 0014)

`privacy.storage` must name a boundary the schema/code actually enforces. Four
values exist (the client renders each as an honest chip — label in
parens) — use exactly one, and only where it's literally true:
- `on_device` ("Cached on your device") — a copy is cached on device (use for
  a geo card that carries coordinates, a saved snippet, etc.).
- `location_local` ("Location stays on device") — ONLY for LIVE position that
  never leaves the device. Never label a stored copy `location_local`.
- `in_browser` ("Kept in your browser") — a web-target (`wasmJs`) scenario
  where the value lives only in browser-local storage, never synced to the
  server. Not applicable to today's Android/iOS/CLI-authored content — don't
  use this until a web target actually exists.
- `matched_on_device` ("Matched on your device") — the value was evaluated
  against on-device state (e.g. a trigger condition matched locally), as
  opposed to being a stored copy (`on_device`).
Never overclaim a privacy boundary — if none of the four is literally true,
omit `privacy.storage` rather than guessing.

## 4. Adults-only accounts (mirrors CLAUDE.md guardrail 1 / COPPA)

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

## 8. Enrichment images: allowlist + surface-to-operator (ADR 0036)

`media` image URLs (`heroUrl` / `thumbnailUrl` / `avatarUrl`) MUST be `https` on the
allowlisted host (`upload.wikimedia.org`) — anything else is rejected at author AND render
time; do not try to work around it. An external image is fetched by the CLIENT at render,
so it leaks the family's viewing context (their IP + that they're viewing this content) to
the image host. Therefore, beyond the JSON (Guardrail 1), ALWAYS surface the *specific
chosen image* (the URL, ideally a preview) to the operator before `push` — they approve
that image, not just "an image". When unsure, prefer `icon` + `accentColor` (no URL, nothing
fetched).

## 9. Deletion is destructive and cascading

`dayfold delete` (`references/cli.md`) removes a hub (cascading its whole
section/block tree) or a card. There is no undo from the CLI. Never run it on
un-approved content: name the exact hub/card and, for a hub, that its sections
and blocks go with it, and get explicit operator confirmation before deleting —
the same propose-confirm bar as Guardrail 1, raised because the action can't be
walked back by re-pushing.
