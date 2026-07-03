# AGENTS.md

Dayfold's canonical agent-operating doc is **[`CLAUDE.md`](CLAUDE.md)** — session
protocol, directory map, process rules, and guardrails. Read it first; this file
exists only because some agent tooling looks for `AGENTS.md` by convention
(fulfills the commitment in `adr/0013-client-architecture-kmp-redux-kotlin.md`
§6) and is kept deliberately thin to avoid a second copy of the same rules
drifting out of sync.

## Fast orientation for a cold agent

- **Governance / how to work here:** `CLAUDE.md` (start-of-session routine,
  confidence protocol, hard guardrails — read before substantive work).
- **What Dayfold is:** `README.md`.
- **How the system is wired:** `docs/architecture.md`.
- **Build/test toolchain + cheap feedback loops** (Compose/KMP client+UI,
  Android, iOS, CLI, API): `processes/agent-dev-loop.md` — scoped by area, so
  CLI-only or API-only work doesn't pay for the Compose/KMP section.
- **What's currently in flight:** `backlog/now.md`; **what's queued:**
  `backlog/next.md`; **operator decisions awaiting an answer:**
  `backlog/operator-inbox.md`.
- **Decisions already made (don't re-litigate):** `adr/decisions-index.md`.
- **The `dayfold` CLI + Claude skill** (the content-authoring surface):
  `apps/cli/templates/README.md` and `.claude/skills/dayfold-curator/`.

If anything below conflicts with `CLAUDE.md`, `CLAUDE.md` wins.
