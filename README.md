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

> **Status:** the planning loop is running (Phase A — validation follow-through)
> **and the M0 prototype is built + live.** The content API runs on Vercel + Neon,
> the `dayfold` CLI authors content, and the Android app renders it on-device —
> the full wedge works end-to-end (Google sign-in → CLI device-login → author a
> hub → it renders on the phone). Validation round 1 verdict: **CONDITIONAL —
> learning-lab GO, standalone-business NO-GO** (the generic "AI family briefing" is
> commoditized by Gemini Daily Brief / Alexa+ and funded verticals; the defensible
> surface is a **multi-member family-tenant briefing**). See
> `research/validation-round1-2026-06.md`, `adr/0004`, and
> `specs/prototype/00-build-spec-plan.md`.

## Orientation

- [CLAUDE.md](CLAUDE.md) — session protocol, governance, directory map
- [context/values-and-direction.md](context/values-and-direction.md) — operator-owned north star
- [context/business-constitution.md](context/business-constitution.md) — identity + scope firewall (what it is NOT)
- [adr/0004-product-framing.md](adr/0004-product-framing.md) — what this is, what it isn't, MVP scope
- [research/validation-round1-2026-06.md](research/validation-round1-2026-06.md) — validation verdict + evidence
- [planning/workstreams.md](planning/workstreams.md) — the live waterfall board
- [backlog/operator-inbox.md](backlog/operator-inbox.md) — items awaiting the operator
- [backlog/now.md](backlog/now.md) — current immediates

## Repository

| Path | What |
|---|---|
| `apps/api` | Content API — TypeScript / Hono / Postgres (Neon), on Vercel. Auth (token mint, device-grant RFC 8628, Firebase verify), hubs + cards, scope + per-hub visibility. |
| `apps/client` | Compose Multiplatform UI (Android/iOS/desktop) — the feed + hub renderer; redux-kotlin store; SQLDelight offline cache. |
| `apps/androidApp` | Android host — the dogfood target. |
| `apps/cli` | The `dayfold` CLI (Kotlin) — `login` · `push` · `pull` · `template` · `validate` · `whoami`; authors content into the API. |
| `packages/schema` | Generated content schema (`content.schema.json` → Kotlin/TS) — the card/hub contract. |

- **Build & run the apps:** `processes/agent-dev-loop.md` (fixed toolchain + the cheap
  feedback loop) and `specs/prototype/00-build-spec-plan.md` (the live M0).
- **Author content (CLI + Claude):** `apps/cli/templates/README.md` — the typed-authoring
  doc for both cards and hub trees, plus the markdown the app renders.

## Running the planning loop

- One-shot: open a session here and say **"run a loop iteration"** (follows
  `processes/planning-loop.md`).
- Sweep `backlog/operator-inbox.md` weekly.

## Lineage

Built from the **venture-loop template** (extracted from the KeepQR /
RevenueCatch projects). Process inspiration also drawn from the sibling
`ambient-ai` spec repo ("render, don't reason"; ADR + open-questions
discipline; persona-driven key moments).
