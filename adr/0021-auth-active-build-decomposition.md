# ADR 0021: Auth Pulled Into Active Build — S1–S6 Decomposition + Dev-Token Posture

## Status

**Accepted** 2026-06-19 (operator-directed re-prioritization; approved in-session,
build order S1→S3→S2→S4→S5/S6 confirmed). Immutable — supersede, do not edit.
Extends — does not supersede — **ADR 0011** (Auth & Family-Tenancy, Hardened,
Accepted). Triggered by ADR 0011's own revisit clause: *"or auth moves from
'later milestone' into active build (re-confirm the sequencing boundary then)."*
This ADR records that re-confirmation. Design of
record: `docs/superpowers/specs/2026-06-19-auth-s1-tenancy-token-backbone-design.md`
+ `specs/prototype/04-auth.md` + `specs/auth-and-family-design.md`.

## Context

The operator re-prioritized authentication ahead of further content/render work,
to (a) stop hardcoding `FAMILY_ID`/`HOUSEHOLD_SECRET` for building/testing and
(b) enable real multi-tenancy testing. ADR 0011 designed the full auth epic
(Firebase identity, backend-minted scoped/revocable tokens, RFC 8628 CLI device
grant, M:N membership tenancy, owner-approved invites) but sequenced it AFTER the
ADR 0007 prototype, with the prototype keeping a single household token. The full
epic is multiple independent subsystems — too large for one build slice — and
parts are gated (Firebase = vendor/cost; client UI = ADR 0008 design-first).

## Decision

1. **Auth moves into active build now**, decomposed into six sub-projects, each a
   spec → plan → build cycle:
   - **S1 — Tenancy & token backbone** (backend; Firebase-stubbed; no UI).
     users/identities/memberships/credentials/refresh_tokens, the mandatory
     auth+authz middleware, EdDSA token service + refresh rotation, `POST
     /families`, JWKS, multitenancy/IDOR test matrix.
   - **S2 — Firebase identity** (backend; brings the vendor/cost decision).
   - **S3 — CLI device grant** (RFC 8628 + anti-phishing).
   - **S4 — Invites** (owner-approved; add-member/approvals; ≥1-owner-on-remove).
   - **S5 — Client identity + onboarding UI** (gated on ADR 0008 mockups).
   - **S6 — Member/device management UI** (gated on ADR 0008 mockups).

2. **Dependency order:** S1 → {S2, S3, S4} → {S5, S6}. **Recommended build
   order: S1 → S3 → S2 → S4 → (S5, S6).** Rationale: S1 is the spine; S3 next
   fully eliminates cloud/device hardcoding (S1's dev-token only covers local
   build/test); Firebase (S2) comes after because the stub/dev verifier covers
   identity for backend testing until real human login is needed. *(Order S2/S3
   is the one open sequencing choice — confirm at acceptance.)*

3. **Non-breaking coexistence.** Through S1–S2, tenant routes accept **either** a
   new access-JWT **or** the legacy `HOUSEHOLD_SECRET` (a distinct, content-scope-
   only authz path with no membership, provably unable to reach owner-only/self
   routes). **The legacy household-token branch is removed in S3** once the CLI
   obtains real device-granted tokens (tracked cutover).

4. **Dev-token posture (new auth surface).** S1 ships a `DevVerifier`-backed
   `POST /auth/dev-token` to mint real tokens for local build/test without
   Firebase. It is gated by **all** of: `ENABLE_DEV_AUTH=1` (unset by default;
   never set in prod/preview), hard-refusal when `VERCEL_ENV ∈
   {production, preview}`, a required `DEV_AUTH_SECRET` bearer, dev-distinct
   `iss`/`aud`, and audit logging. Net effect: **local-dev + tests only**; it is
   never a deployed bypass.

5. **Guardrails unchanged.** Adults-only (ADR 0004; teen 14+ stays deferred to
   ADR 0005 + counsel). No Gmail/restricted-scope work → no CASA trigger. Firebase
   adoption (S2) is a vendor + spend decision (Blaze for SMS) — operator-gated at
   S2, not S1. Client UI (S5/S6) is ADR 0008 design-gated.

## Consequences

Positive: unblocks dynamic tokens + real multitenancy testing; S1 is vendor-free,
UI-free, headless-testable, and de-risks the densest security surface first;
coexistence keeps the live deploy + CLI working throughout; the decomposition
lets each slice be reviewed/accepted independently. Negative: a temporary dual
authz model (JWT + legacy) until the S3 cutover; a deliberately-introduced dev
auth surface (mitigated to local-only by item 4); the full epic remains several
slices of work; Firebase vendor/cost + UI design gates still lie ahead at S2/S5.

## Revisit Trigger

The S2/S3 order is changed at acceptance; a security review of an S-slice finds an
exploitable flow; Firebase ships an official KMP SDK (revisit S2/S5 SDK glue); the
teen (ADR 0005) or multi-family UI ships; or the dev-token gate is ever found
reachable in a deployed environment (treat as an incident).
