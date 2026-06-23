# ADR 0029: CLI / Token Resource-Scoped Grants — Per-Hub Read/Write Scoping

## Status

**Accepted** 2026-06-23 (operator-directed in the S6-D auth review; operator chose
per-hub/resource scoping over a read/write-only toggle, and accepted this ADR in the
same review). Immutable — supersede, do not edit. Extends — does not supersede —
**ADR 0011** (Auth & Family-Tenancy, Hardened) and **ADR 0021** (S1–S6
decomposition). Composes with **ADR 0022** (typed content / hubs) and **ADR 0025**
(abuse-control). Review source:
`docs/superpowers/specs/2026-06-23-auth-s6d-device-approval-design.md`.

## Context

The S6-D review found that the device-grant credential is minted with a **hardcoded,
all-or-nothing** scope set (`scopes='{content:read,content:write}'`,
`apps/api/src/auth/device.ts`), and the approve endpoint accepts only `{user_code}`
— the approver **cannot scope a grant** (`POST /families/:fid/device/approve`,
`apps/api/src/app.ts`). The `authorizedevice` mockup shows a scope row, but it is
cosmetic. Scope enforcement is also partial: `content:write` is checked on
PUT/DELETE cards, but read endpoints (`GET /…/cards`, `/…/sync`) check nothing —
so a read-only credential is not meaningfully restricted and future content routes
have no central gate to inherit. The operator's requirement: *a user can grant,
scope, and deny CLI/token access from the mobile QR flow*, at **per-hub / per-
resource** granularity, low-friction.

This binds to ADR 0011 §8 (**never trust scope from the token** — re-resolve per
request) and to the **content-API + CLI-verbs slice** that is being planned next:
per-hub scoping is meaningless until hubs are first-class API resources, so this
ADR's mechanism **lands with that content slice**, not in S6-D.

## Decision

1. **Grant model = resource-qualified scope strings, resolved per request.** A
   credential's authority is a set of grant rows, each a scope string that is either
   **global** (`content:read`, `content:write`) or **resource-qualified**
   (`hub:<hub_id>:read`, `hub:<hub_id>:write`; extensible to other resource types).
   Stored in a new `credential_grants(credential_id, scope, created_at)` table
   (not the flat `credentials.scopes[]`), so grants are queryable, auditable, and
   per-resource. **Grants are never encoded in the access token** (ADR 0011 §8) —
   resolved from the rows on each request.

2. **Central scope gate.** A single `requireScope(resource, action)` helper that
   every content route declares (e.g. a hub write route requires
   `content:write` OR `hub:<that id>:write`). This **closes the read-enforcement
   gap** (read routes must require `content:read` or a `hub:*:read`) and makes new
   routes inherit enforcement rather than ad-hoc `scopes.includes()` checks.

3. **Approval UX (low-friction, least-privilege-capable).** The `authorizedevice`
   screen lists the family's hubs + an "All content" row, each with a read / read+
   write control. **Default selection = "All content, read & write"** so the common
   case stays one tap (parity with today), while the owner can narrow to specific
   hubs and/or read-only. `approve` carries the chosen grant set; `redeem`
   (`/device/token`) writes the `credential_grants` rows instead of the hardcoded
   constant. `whoami` shows the resolved grants.

4. **Re-scoping = revoke + re-grant (MVP).** A credential's grants are fixed for its
   life; changing scope means revoking the credential (existing
   `DELETE /auth/me/credentials/:id`) and re-running login. No in-place grant editing
   at MVP (keeps the surface small; revisit if dogfooding demands it).

5. **Sequencing / interim posture.** Per-hub scoping **ships with the content-API +
   CLI-verbs slice** (it requires the hub resource model). **Until then, S6-D ships
   the interim single global `content:read+write` grant** (today's behavior), and
   the approve screen shows scope **honestly as informational** (no per-hub picker
   rendered before the model exists). This ADR being Accepted is the gate to build
   item 1–3.

6. **Token invariants unchanged.** EdDSA access (5m) + rotating refresh, family-
   scoped CLI credentials (`family_scope NOT NULL`), per-request membership +
   `revoked_at` re-resolution, revocation effective on next request — all retained.

## Consequences

Positive: meets the operator's per-resource grant/scope/deny requirement; least-
privilege CLI/loop tokens become possible (e.g. a read-only CI token, or a token
scoped to one hub); the central gate fixes the read-enforcement gap and hardens all
future content routes; grants are auditable per resource. Negative: a new table +
a richer approve UI + a gate refactor; per-hub grant selection adds approval-screen
complexity (mitigated by the all-content default); re-scoping requires re-login at
MVP; the mechanism is coupled to the content-slice schedule. Neutral: token shape
unchanged (grants stay server-side per ADR 0011).

## Revisit Trigger

Dogfooding shows the all-content default is over-broad (flip default to least-
privilege) or that in-place re-scoping is needed; a security review finds the gate
bypassable; resource types beyond hubs need scoping (cards/places/triggers); or
multi-family CLI grants are introduced (each family its own grant set).
