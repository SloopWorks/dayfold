# Prototype Build-Spec Plan (loop state)

Driven by the `/loop` (every 30 min). **Each iteration: read this tracker →
advance the next waterfall step of the current component → update the tracker
→ commit + push.** Goal: enough detail to implement backend, auth, invite,
DB, storage, mobile client, CLI, API.

## Per-component waterfall

`brainstorm → design → multi-agent review → impl-plan → review → implement
(write the spec/DDL/OpenAPI) → verify (review pass)`. Most design decisions
already live in ADRs 0004–0012 + `specs/auth-and-family-design.md` +
`specs/event-hubs-design.md` — those are the brainstorm/design inputs;
the loop produces **implementation-ready specs** and reviews them. Run the
brainstorming step only where a component has open design questions.

## Sequencing (ADR 0011)

Specs are authored for the whole v1 now (design-first). **Build** order
stays: **M0 prototype** (dumb renderer + content API + single household
token, no auth) → **M1** (Firebase auth, invite, CLI device-grant,
Universal Links). The specs note which milestone each part belongs to.

## Component tracker

| # | Component | Spec file | Status | Next step |
|---|---|---|---|---|
| 01 | Architecture overview | `01-architecture.md` | **done (2-agent review applied)** | — |
| 02 | Data model & DB schema (Postgres DDL) | `02-data-model.md` | **done (review applied)** | — |
| 03 | API design (OpenAPI) | `03-api.md` | **done (2-agent review applied)** | — |
| 04 | Authentication & token service | `04-auth.md` | **done (2-agent review applied)** | — (recovery-floor = hard gate before build) |
| 05 | Invite system | `05-invite.md` | **done (2-agent review applied)** | — |
| 06 | Storage (object storage, docs/large markdown) | `06-storage.md` | **done (review applied)** | — |
| 07 | CLI tool | `07-cli.md` | **done (review applied)** | — |
| 08 | Mobile client (CMP) | `08-mobile-client.md` | **done (2-agent review applied)** | ⚠ pre-build gate: confirm redux-kotlin alpha1 coordinates |
| 09 | Security controls + test/verify plan | `09-security-and-test.md` | **draft → final cross-spec review** | apply capstone review |

## Current

- **Done:** 01 architecture, 02 DB DDL, 03 API, 04 auth (each 1–2-agent
  reviewed + applied). 04 added a `refresh_tokens` lineage table + Apple-token
  storage to the DDL; **recovery-floor procedure is a hard gate before build**.
  05 invite + 06 storage + 07 CLI reviewed/applied. CLI: content-pinned stable
  IDs + edit-stable section anchors so deep-link targets never dangle; full-
  replace opt-in; X25519 keypair at login for E2E wrapped-FCK; AAD-version
  authority = client supplies / server validates monotonic. **Next: 08 Mobile
  client** (the big one — redux-kotlin/SQLDelight/M3E/trigger-matcher), then 09
  security+test plan. Flagged for 04: M0 token = content read+write (CLI diff
  needs read). ⚠ **Pending operator: INB-10 / ADR 0015 (E2EE)** — if accepted,
  02/06/08 adjust for encrypted content (drop server FTS).

## Log

- I1 (2026-06-18): tracker + architecture draft + review dispatched.
- I1b (2026-06-18): applied 2-agent architecture review — added briefing_cards
  entity, tenant-explicit READ path + object-storage IDOR controls,
  constrained M0 household token, complete token/secrets rules, M0=inline-only.
  **Decision: API host = TypeScript/Vercel (CLI stays Kotlin)** → pending-
  ratify, operator-inbox INB-9. Started component 02 (DB DDL).
