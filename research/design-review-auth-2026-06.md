# Design Review — Auth / Family / Invite (5-agent fleet)

**Date:** 2026-06-18 · **Scope:** `specs/auth-and-family-design.md`,
`adr/0010`, the `Auth.dc.html`/`Auth-Phone.dc.html` mockups, and the
`event-hubs-design.md` schema. **Method:** 5 parallel review agents —
completeness/flow, data-schema, Firebase-capabilities (web-cited),
system-design/feasibility (web-cited), security (web-cited). Read-only;
nothing built.

---

## VERDICT

**Architecture is right; NOT build-ready as written.** The standards choices
(Firebase Auth + RFC 8628 device grant + backend-minted scoped/revocable
tokens + M:N default-deny tenancy) are sound and correctly anticipate what
must be custom. The danger is in **under-specified mechanics stated as
assertions** ("in-person", "verified", "default-deny", "rotating",
"least-privilege") rather than enforced controls — plus one real
**sequencing error**, a factual **Firebase correction**, and ~13 missing
states. **Three security P0s map to actively-exploited 2026 attacks and trip
ADR 0010's own revisit trigger** ("a security review of the invite/
device-grant flows finds a flaw").

---

## P0 — block build / must resolve before leaving Draft

### Sequencing & governance
- **P0-SEQ — Auth (ADR 0010) must NOT be built into the ADR 0007 prototype.**
  The prototype = single household token, in-app routing, **no Universal
  Links**. But invite + device-grant *require* Universal Links + verified-
  domain association files. I mislabeled the CLI device-grant the "dogfood
  critical path" — it's critical for the **product**, not the prototype.
  → The prototype keeps the **single household token** for the CLI; the
  entire auth/family/invite story is a **distinct later milestone** that
  ships deep-link infra with it. *(system-design P0-1)*

### Security (3 independent takeover paths — all live 2026 attacks)
- **P0-SEC1 — Device-grant phishing.** Attacker starts `familyai login`,
  sends the QR/link to a victim owner ("re-link your dashboard"); victim
  approves → attacker's CLI gets a content-write family token. CSA: 37×
  surge, 340+ M365 orgs. The **email→push fallback is the worst vector**.
  Fix: mandatory on-phone **`user_code` confirmation** (RFC 8628 §5.4 — QR
  fills it, human still confirms it matches), show **request origin
  geo/ASN** + warn on datacenter origins, and **cut or hard-gate
  email→push**. *(security P0-1)*
- **P0-SEC2 — QR auto-join = full-tenant read on a leaked QR.** "In-person"
  is assumed, never enforced; a screenshotted/forwarded QR auto-joins a
  non-present person to **all** family content (incl. minor data, ADR 0005).
  Fix: **drop auto-join — make all invites owner-approved** (one tap; the
  owner is present anyway), or add a real proximity proof. **Reverses the
  earlier invite-policy decision → operator call.** *(security P0-2,
  completeness logic-issue)*
- **P0-SEC3 — Provider-link account takeover.** Never auto-link on email
  match. Require **proof-of-control of the existing account** (sign in with
  the original provider) before attaching a new one; dedupe only on
  `email_verified==true` / OTP-proven phone. *(security P0-3)*

### Firebase factual correction
- **P0-FB — "Dedupe on verified email/phone" is wrong vs Firebase reality.**
  Email Enumeration Protection is **on by default** (projects ≥2023-09-15) →
  `fetchSignInMethodsForEmail` is dead; Firebase **never auto-links**;
  phone & Apple **private-relay** have no stable dedupe email. Rewrite to:
  **app-driven linking** triggered by `account-exists-with-different-
  credential` (carry the pending credential, user picks provider), **join on
  provider UID** (Apple `sub`), **phone = its own identity**. *(firebase
  item 1)* — this also satisfies P0-SEC3.

### Schema (not modeled enough to implement)
- **P0-SCHEMA1 — Missing uniqueness** → duplicate accounts / double-joins:
  add `UNIQUE(provider, provider_uid)` on `user_identities`,
  `PRIMARY KEY(user_id, family_id)` on `memberships`. *(schema)*
- **P0-SCHEMA2 — Last-owner invariant absent** → orphaned, unmanageable
  family. Enforce ≥1 active owner; require ownership-transfer before
  last-owner leave/delete. *(schema + completeness)*
- **P0-SCHEMA3 — Creator account-deletion disposition undefined** (and no
  account-deletion/export flow at all — breaks the ADR 0005 export+delete
  commitment, and Apple requires account deletion + **Apple token
  revocation**, currently missing). *(schema + completeness + firebase
  item 1)*
- **P0-SCHEMA4 — Content tables are JSON-only, not relational; no
  `family_id` column.** The tenant-scoping the whole authz model depends on
  isn't modeled. Define `hubs(id, family_id FK NOT NULL, …)`,
  `sections(hub_id FK)`, `blocks(section_id FK, payload jsonb, provenance)`.
  *(schema)*

### Mockup gap
- **P0-UI — The Authorize-device screen (RFC 8628 approval) is spec'd but
  absent from the mockups** — the most load-bearing missing screen. Also
  missing: Family-members, Connected-devices-&-apps, account-deletion,
  invitee error states. *(completeness inconsistency)*

---

## P1 — required hardening (correctness/security mechanics)

- **Token layer is a bespoke OAuth server — the real solo-dev risk**,
  mislabeled "reuses standards." Either use a vetted OAuth-server lib for the
  device-grant + token mechanics, **or** verify the Firebase ID token
  per-request for the *app* path and mint **only** the CLI credential.
  Caveat (firebase item 3): Firebase revocation is **global-per-UID only** —
  per-device revoke + "Connected devices" is impossible with native Firebase,
  so the CLI credential layer is genuinely required, not gold-plating.
  *(system-design P1-3 + firebase item 3)*
- **Backend token signing rules:** asymmetric (EdDSA/RS256), strict `alg`
  allowlist (reject `none`/confusion), full `iss/aud/exp/nbf`, key rotation.
  **Never trust `family_id`/`scope`/`role` from the token** — re-resolve
  membership + credential-not-revoked **per request**. *(security P1-7,
  P1-6)*
- **Refresh reuse-detection → revoke the whole token family** on replay
  (OAuth 2.1 BCP). *(security P1-6)*
- **Revocation must be effective within one request, not one hour** — fold
  credential-revoked + membership-active into the per-request check (don't
  rely on 1h JWT expiry). *(security P1-6)*
- **`user_code`**: ≥8 chars, ~20-symbol unambiguous alphabet (drop
  0/O/1/I/L), **rate-limit + lockout** on the verify endpoint, single pending
  auth per code. **Polling**: enforce `interval`+`slow_down`, **one-time
  `device_code`** invalidated on issue/deny/expiry. *(security P1-4, P1-5)*
- **IDOR / default-deny as a mechanism, not a claim:** one mandatory
  membership/scope middleware every route inherits; **per-resource tests**
  that family A gets 403/404 on family B — *including* invite,
  device_authorization, credential, membership endpoints. *(security P1-8)*
- **Content-API authz mismatch** between the two specs (per-household token
  vs per-request membership). Make the path **tenant-explicit**:
  `PUT /families/{fid}/hubs/{id}` — prototype uses the household token,
  product uses the minted credential, **same path shape** (protects ADR
  0007's "additive without rework"). *(system-design P1-4)*
- **Idempotent upsert needs versioning:** declare single-writer LWW for the
  prototype; add `If-Match`/`updatedAt` to the block schema **now** (cheap to
  carry, costly to retrofit); require parent-exists on nested upsert (no
  orphans). *(system-design P1-5 + schema)*
- **Offline read-cache undesigned** — specify a local store (SQLDelight, CMP-
  native); render always reads local; deep-link "nearest ancestor"
  resolution must work on the **local** cache. *(system-design P1-6)*
- **SIM-swap:** for **owner** role require ≥2 linked methods (not just a
  nudge) and step-up on new-device phone-OTP before sensitive actions.
  *(security P1-9)*
- **Firebase post-sign-in:** your minted tokens are independent of Firebase —
  a later account disable/delete won't propagate; sync via blocking
  functions or periodic re-validation. *(firebase item 3)*
- **CMP reality:** Firebase-on-KMP is **community-only** (GitLive
  `firebase-auth:2.5.0`, 2026-05; ~80% coverage) — ADR 0010's "best KMP
  maturity" is wrong; budget **native `expect/actual` glue** for Phone-OTP +
  Apple; spike the hardest iOS paths first. *(system-design P0-2 + firebase
  item 4)*
- **Add `updated_at` to all mutable tables; declare membership/invite/device
  state machines** (invite needs an `expired` state distinct from
  `exhausted`; atomic claim guard against double-redeem). *(schema)*
- **SMS region allowlist** — cheapest highest-leverage toll-fraud control;
  add to the App-Check + rate-limit set. *(firebase item 2)*

---

## P2 — fix before ship / track

- ~13 missing states (sign-out, leave-family, invitee expired/declined/
  pending-wait, already-member, OTP errors, provider-link-conflict UX,
  re-auth, offline). *(completeness)*
- Cut **iOS deferred deep-linking** from v1 (no first-party support; needs a
  Branch-style SDK) — cold-install invitee re-taps the link (72h TTL covers
  it). *(system-design P2-7 + security P2-12)*
- **Stale Index.dc.html footer** still says "no auth/onboarding/settings" —
  contradicts the Auth mockup + ADR 0010. *(completeness)*
- Minor-data is a **stakes-multiplier** on every tenant-isolation bug; CLI
  content tokens must not read minor-profile fields; scrub PII/provenance in
  shared cards. *(security P2-11)*
- SMS-pumping caps (per-number-prefix velocity, daily spend cap+alert);
  enums as CHECK/lookup; provenance `credential_id` for audit; indexes on hot
  paths; promote Hub `dates` to typed columns. *(security P2-10, schema)*
- App Check + reCAPTCHA-SMS-defense are **Pre-GA** — acceptable, flag
  maturity. Firebase free 50k MAU; **SMS billed (needs Blaze + card)**; avoid
  MFA/blocking-functions to stay off metered Identity Platform. *(firebase
  items 2, 5)*

---

## Where agents converged (highest confidence)

1. **Don't build auth into the prototype** (system-design) — reconcile ADR
   0007 ↔ 0010 sequencing.
2. **Tenant isolation / `family_id` scoping** is asserted, not architected
   (schema + system-design + security).
3. **The bespoke token + device-grant server is the concentrated risk**
   (system-design + security + firebase).
4. **Invite auto-join is unsafe** (security P0 + completeness logic).
5. **Revocation/identity-linking specifics are the exploitable gaps**
   (security + firebase agree on the mechanics).

## Net

Feasible and standards-grounded; **phase it** (prototype single-token first,
auth as a deliberate second milestone), **harden the mechanics into the spec
before build**, and **fix the Firebase dedupe model + the three security
P0s**. Re-review after the spec hardens (ADR 0010 revisit trigger fired).

### Key sources
RFC 8628 §5; CSA OAuth device-code phishing (2026-04, 37× / 340+ orgs);
Firebase docs — account-linking, email-enumeration-protection, SMS regions,
verify-id-tokens/custom-claims/manage-sessions, limits, pricing; GitLive
firebase-kotlin-sdk 2.5.0; Apple App Store 5.1.1(v) account-deletion.
Raw agent outputs available on request.
