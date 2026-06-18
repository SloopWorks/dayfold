# ADR 0010: Auth & Family-Tenancy Architecture

## Status

**Accepted** 2026-06-18 (operator approved in-session). Immutable —
supersede, do not edit. Platform + architecture decision. Detailed design:
`specs/auth-and-family-design.md`.

## Context

The product needs account, login, family scope, family creation, member
invite, and CLI/Claude-Code authorization. The operator specified:
passwordless one-tap sign-in (OAuth Google/Apple or SMS), mobile app as the
creation point, minimal onboarding (family name), low-friction member invite
(QR), and a CLI authorization where a device is verified via the mobile app.
This is the real multi-user auth story — beyond the ADR 0007 prototype.

## Decision

1. **Firebase Auth** is the authentication foundation: Google, Apple, and
   Phone-OTP; passwordless, one-tap. (Chosen over Supabase/Clerk/roll-your-
   own for best Android/iOS/KMP maturity + built-in phone-OTP + lowest
   solo-dev security burden.)
2. **One user = one person**, multiple providers linked; dedupe on verified
   email/phone. The **backend mints its own access + rotating refresh
   tokens** (seeded by the verified Firebase ID token) so app and CLI hold
   uniform, scopable, revocable credentials.
3. **Family is the tenant. Membership is many-to-many** (`memberships` join
   table) — UI is single-family at MVP, but the model supports multi-family
   (co-parenting/eldercare niche) without rework. Roles: `owner`, `adult`;
   `teen` (14+) deferred to ADR 0005. **Default-deny**, content scoped by
   `family_id`.
4. **Member invite** = signed, hashed, short-TTL, capped, revocable token
   delivered as a Universal/App-Link QR (or shared link). **In-person QR
   auto-joins; a shared link creates a pending member the owner approves.**
   Invite ≠ identity — the invitee still authenticates as themselves.
5. **CLI/Claude-Code authorization = OAuth 2.0 Device Authorization Grant
   (RFC 8628)**: QR scan-to-approve in the signed-in app (with `user_code`
   entry and email→push as fallbacks); CLI receives a **least-privilege,
   content-only, family-scoped, revocable** token. This is the dogfood
   critical path.
6. **Revocation is first-class everywhere** (sign-out, remove member, revoke
   device token, expire invite) via "Family members" and "Connected devices
   & apps" surfaces.

## Rationale

Reuses battle-tested standards (Firebase Auth, RFC 8628) instead of bespoke
auth — the right call for a solo dev where auth bugs are catastrophic. The
M:N model is near-free future-proofing for the one validated niche path.
Backend-minted tokens give uniform scoping/revocation across app and CLI,
and make the CLI authoring loop a first-class, least-privilege client —
coherent with the dumb-renderer model.

**Rejected:** roll-your-own auth (security burden); strictly-one-family
(re-architecture risk for the niche); a non-standard CLI pairing (RFC 8628
exists and is hardened); passing IdP tokens directly to the API (loses
uniform scoping/revocation and CLI least-privilege).

## Consequences

Positive: standards-based, low security-risk, revocable, future-proof
tenancy; the operator's CLI loop is a clean authenticated client.
Negative: **supersedes ADR 0007's "single household token / no login" when
built** — the prototype grows into a multi-user product; invite + device-
approve **pull Universal/App Links forward** (domain-association setup) at
build time; managed-auth introduces a Firebase dependency (vendor); SMS-OTP
adds a toll-fraud surface (mitigated by App Check + rate limiting).

## Revisit Trigger

Firebase Auth limits/cost become binding; the multi-family UI ships (needs a
family switcher); teen (14+) memberships are added (ADR 0005 + counsel); or
a security review of the invite/device-grant flows finds a flaw.
