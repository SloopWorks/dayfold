# Prototype Build-Spec Plan (loop state)

> 🔨 **BUILD PHASE (loop `18ff12bf`, started 2026-06-18).** Implementing M0 per
> `10-implementation-plan.md`. **I-build-1:** schema `payload` $defs authored
> (codegen prereq, C4 closed); P-1 operator bootstrap escalated (INB-12).
> **Next:** P0 monorepo skeleton + schema codegen (unblocked — local), while
> INB-12 (Vercel/Neon accounts) is pending.

## Milestone glossary (C6 — bind the vocabularies)

| Plain name | M-number | Gate(s) | Scope |
|---|---|---|---|
| **prototype / dogfood** | **M0** | G1a | single household, household token, operator's own device, plaintext, **feed-first** (Hubs = D2 decision) |
| **multi-member product** | **M1** | G1b→G2→G3 | Firebase auth, invite, device-grant, triggers/geofencing, live E2E |
| **paid launch** | (post-build) | **G-LAUNCH** (post-G4) | first paying family |

"MVP" in ADR 0005 = the **M1** multi-member milestone, NOT the M0 prototype.


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
| 09 | Security controls + test/verify plan | `09-security-and-test.md` | **done (cross-spec review applied)** | — |
| 10 | M0 implementation plan | `10-implementation-plan.md` | **done (review applied)** | redux-kotlin gate RESOLVED (alpha01 exists; default 0.6.2 stable, INB-11) |
| 11 | Content JSON-schema contract | `specs/domain-model/schemas/content.schema.json` | **authored** (P0 task-1, host-agnostic) | content fields = E2E typed-hole (EncryptedEnvelope), resolve at INB-10 before freeze |

> ⛔ **LOOP IS AT THE OPERATOR-DECISION GATE.** Specs (01-09) + impl plan (10)
> + the JSON-schema contract (11) are done — *enough to implement*. Every
> remaining build step (P0 scaffold, P1 DDL freeze, the codegen content-field
> shape) is **blocked on operator decisions: INB-9 (TS/Vercel host) + INB-10
> (E2E — gates the schema)**. Further autonomous spec iterations would either
> rework provisional artifacts (E2E undecided) or begin build (needs host +
> G1a). **Recommend: sweep the inbox (INB-1…11) or stop the loop.**

> ✅ **SUITE COMPLETE (2026-06-18).** Final cross-spec verification:
> **implementation-ready for the M0 dumb-store spine.** The 4 close-items are
> applied (M0 token = content:read+write; places-table fence fixed; sync
> tombstone predicate pinned; version-authority + FTS stated as M0-vs-E2E
> modes). Cross-spec consistency confirmed (path shape, body_md/body_ref XOR,
> ULID IDs, reserved actions/triggers/intents, milestone tags).

## Current

- **🎉 ALL 9 COMPONENTS DONE — M0 spec suite implementation-ready.** Remaining
  before build = the **hard gates** (not spec gaps): redux-kotlin `1.0.0-alpha1`
  coordinate confirm (08); recovery-floor procedure (04, operator+counsel);
  **operator decisions INB-9 (TS host) + INB-10 (ADR 0015 E2EE — changes M0
  schema/version-authority/FTS)**; ADR 0005/0006/0007/0015/0016 ratifications.
  **Loop's next focus:** close those gates + deepen M1, or hand off to build
  (the M0 build order is in `09 §Build order`).

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
