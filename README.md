# Dayfold

A calm, AI-powered household dashboard. One account per family, adults log in. It reads
the family's existing signals — calendar, email, lists, weather, location — and renders
a daily **briefing** plus smart **recommended actions** with deep links ("party Saturday
— ordered groceries? [list]"; "school email needs an RSVP Thursday [reply]").

Built mobile-first on **Compose Multiplatform** (Android/iOS/Web). The MVP wedge is a
**content API + CLI + Claude skill**: external AI loops author/update cards and hubs —
the dashboard *renders* intelligence produced elsewhere. It is not a chatbot.

> **Status (2026-06-26):** M0 prototype **built + live.** Google sign-in, CLI device
> login, hub + card authoring, and the Android feed all work end-to-end. Validation
> round 1 verdict: **CONDITIONAL — learning-lab GO, standalone-business NO-GO**
> (commoditized by Gemini Daily Brief / Alexa+; the defensible wedge is multi-member
> family-tenant briefing, which no native OS ships). See
> `research/validation-round1-2026-06.md`.

## Architecture

```
  Claude Code skill / AI agents / scheduled tasks
            │
            ▼
  ┌─────────────────────┐        ┌───────────────────────────────┐
  │  dayfold CLI        │        │  Content API                  │
  │  (Kotlin / JVM)     │ ──PUT─►│  (TypeScript · Hono · Vercel) │
  │                     │◄─GET── │  Postgres (Neon)              │
  │  login  push  pull  │        │  Firebase Auth                │
  │  whoami template    │        └───────────┬───────────────────┘
  └─────────────────────┘                    │ /sync
                                             ▼
                                  ┌──────────────────────┐
                                  │  Client app           │
                                  │  (Compose Multiplatform│
                                  │   Android · iOS · Web)│
                                  │                       │
                                  │  Now feed  ·  Hubs    │
                                  │  Offline SQLDelight   │
                                  │  Google Sign-In / QR  │
                                  └──────────────────────┘
```

## Quick start

**Author content (AI agent or terminal):**

```bash
# Install
brew install sloopworks/tap/dayfold

# Sign in (owner approves on their phone via QR or code)
dayfold login

# Author a hub
dayfold template hub > hub.json
# edit hub.json, then:
dayfold push my-college-hub hub.json --hub

# Author a briefing card (with local validation)
dayfold template invite > card.json
dayfold push 01J... card.json --type invite

# Read back what's on the server
dayfold pull
dayfold pull --hub my-college-hub

# Or push ready-made examples
cd apps/cli/examples && bash push-all.sh
```

**Claude Code authoring skill:**

```bash
# Install the dayfold-curator skill globally
sh .claude/skills/dayfold-curator/install.sh
# Then in any Claude Code session: /dayfold-curator
```

## Repository map

| Path | What |
|---|---|
| `apps/api` | Content API — TypeScript · Hono · Postgres (Neon) · Vercel. Auth, cards, hubs, sync. |
| `apps/client` | Compose Multiplatform UI — Now feed, Hubs, offline SQLDelight, redux-kotlin. |
| `apps/androidApp` | Android host — the dogfood target. |
| `apps/cli` | The `dayfold` CLI — `login · logout · whoami · push · pull · template`. |
| `apps/cli/examples` | Ready-to-push sample hub + feed cards. `bash push-all.sh` populates a test account. |
| `apps/cli/templates` | Authoring reference — all card types, hub tree, markdown rendering, block payloads. |
| `packages/schema` | `content.schema.json` → generated Kotlin/TS types (source of truth for payloads). |
| `.claude/skills/dayfold-curator` | Claude Code skill — context → hubs + cards, propose-confirm before every push. |
| `designs/` | Hi-fi mockups for all surfaces (signed off before build). |
| `specs/` | PRD, auth design, event-hubs design, account/settings design. |
| `processes/` | Planning loop, agent routing, dev loop, build automation, CI/CD runbooks. |
| `adr/` | 35 Architecture Decision Records. `decisions-index.md` is the index. |
| `context/` | Values + direction (operator-owned), constitution, goals, kill switches. |
| `backlog/` | `now.md` · `next.md` · `later.md` · `operator-inbox.md` |

## Running the planning loop

One-shot: open a session here and say **"run a loop iteration"** (follows
`processes/planning-loop.md`). Sweep `backlog/operator-inbox.md` weekly.

## Building the apps

Read `processes/agent-dev-loop.md` first — fixed toolchain (JDK17, Kotlin 2.3.20,
redux-kotlin alpha01 gotchas) + the cheap feedback loop (action log, snapshot PNGs,
devtools, cloud URL). Build spec: `specs/prototype/00-build-spec-plan.md`.

## Governance orientation

- [CLAUDE.md](CLAUDE.md) — session protocol, governance, directory map
- [context/values-and-direction.md](context/values-and-direction.md) — operator-owned north star
- [context/business-constitution.md](context/business-constitution.md) — what it IS and IS NOT
- [adr/0004-product-framing.md](adr/0004-product-framing.md) — MVP scope
- [planning/workstreams.md](planning/workstreams.md) — live waterfall board
- [backlog/operator-inbox.md](backlog/operator-inbox.md) — items awaiting the operator

## Lineage

Built from the **venture-loop template** (extracted from the KeepQR / RevenueCatch
projects). Process inspiration from the sibling `ambient-ai` spec repo ("render, don't
reason"; ADR + open-questions discipline; persona-driven key moments).
