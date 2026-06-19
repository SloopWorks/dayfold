# 04 — Authentication & Token Service

> Status: **reviewed (2 agents) → fixes applied**. Implements ADR 0011 +
> the Firebase fact-check. The 2nd security review tripped ADR 0011's revisit
> trigger (pending_link, refresh-rotation, JWKS) — all three encoded below.
> Mostly **[M1]**; M0 household token is [M0]. Backend = TS/Vercel.

## Token model

- **Access token** — backend-minted **JWT**, **single alg: EdDSA/Ed25519**
  (no padding-oracle class; reject everything else incl. `none`/HS). Header
  carries **`kid`**. Claims: `iss`, `aud`, `sub`=user_id, `cid`=credential_id,
  `exp`, `nbf`, `iat`, `jti`. **Carries NO authorization** — `family_id`/
  `role`/`scope` re-resolved per request.
  - **`iss` and `aud` are env-distinct, server-pinned constants** (prod ≠
    preview); **never derived from request host / deployment URL**. Verifier
    rejects any token whose `(iss, aud, kid)` triple ≠ the running env's.
  - TTL **≤5 min** clock-skew leeway ≤30s.
- **Revocation mechanism (corrected):** revocation is effective **next
  request** because the middleware **loads the credential row every request**
  (step 3) and re-resolves membership (step 5). The ≤5-min TTL is **defense-
  in-depth** (bounds damage if that load is ever skipped/cached) — it is NOT
  the revocation mechanism. No jti denylist on the happy path; `jti` reserved
  for an emergency single-token kill only.
- **Refresh tokens** — opaque, hashed, in `refresh_tokens` (a **lineage**:
  `superseded_by` chain per `credential_id`; absolute lifetime 30–60d).
  **Rotation = atomic CAS:** `UPDATE refresh_tokens SET consumed_at=now()
  WHERE token_hash=$1 AND consumed_at IS NULL RETURNING …` — the loser of a
  concurrent refresh gets nothing (no revoke). **Reuse grace:** presenting the
  **immediately-prior** token within ~20s **re-serves the same new pair**
  (absorbs mobile double-submit); presenting an **older** consumed token =
  real reuse → **revoke the whole lineage** (by `credential_id`) + audit.
  Client uses single-flight.
- **Signing keys** — per-env, in the **secret manager** (deploy role *binds*,
  **cannot read-back** plaintext; CI logs scrubbed). **JWKS hardening:** keys
  served at `/.well-known/jwks.json`; **`kid` resolved ONLY against an
  in-memory allowlist of currently-valid keys** (never fetch-on-miss, never a
  DB/FS lookup → no kid-injection); **validate `iss` BEFORE selecting the
  key**; JWKS refresh rate-limited + **never null the cache on fetch error
  (serve stale)**; rotation overlap ≥ 2× max access TTL.

## Auth + authz middleware (every route, fail-closed)

**Route classes** (each route declares its class):
- **Tenant routes** (`/families/{fid}/...`, `:action`s) → full pipeline below.
- **Non-tenant auth-bootstrap** (`/auth/session`, `/auth/refresh`,
  `/auth/link`) → authenticate the **Firebase ID token** (or refresh token),
  **skip** family/membership (steps 4–6). Firebase verify error/timeout →
  401.
- **Self routes** (`/auth/signout`, `/auth/export`, `/auth/account`,
  `/credentials*`) → authenticate the **access JWT**, operate on `sub` (+
  resource-row tenancy for `/credentials/{id}`).

Tenant pipeline:
```
1. Bearer present? else 401.
2. Access-JWT branch: verify alg=EdDSA + kid∈allowlist + iss=OUR-env-issuer +
   aud + exp/nbf(≤30s leeway); load credential by `cid`.
   — A Firebase ID token (iss=securetoken.google.com) here → 401 (no confusion).
3. credential.revoked_at NOT NULL → 401.  (lookup error/timeout → 401, FAIL CLOSED)
4. Resolve family from PATH {fid} (or, for resource routes, the resource row —
   then re-checked in step 5). family_id resolved ONLY by the middleware.
   Cross-tenant → 404 (identical body).
5. Re-resolve membership(user, family): **status='active' else 403**; load role/scope.
6. Scope/role gate: content routes need content:write; the **owner-only action
   set** (member approve/remove, invite mint/revoke, credential revoke, device
   approve) = the SAME set used for step-up; content-scoped creds → 403 on
   every non-content route. Default-deny.
```

## Firebase integration

- **Client (GitLive `firebase-auth`)** + native `expect/actual` glue for
  Phone-OTP (iOS APNs/reCAPTCHA, Android Play Integrity) and Apple (nonce/
  `ASAuthorization`). Spike hardest iOS paths first.
- **Server:** verify Firebase **ID token via Admin SDK** (sig+aud+iss+exp),
  never decode-only; **timeout/error → 401**.
- **Disable/delete sync (decided, F8):** re-validate Firebase user state at
  **`/auth/refresh`** (existing backend touchpoint). Propagation latency =
  refresh interval; admin force-logout = revoke our credential (immediate).
  Avoids metered blocking functions.

## Flows

**Sign-in (`POST /auth/session`, body `{id_token}`):** verify → lookup
`user_identities(provider, provider_uid)` (Apple `sub`; **no email dedupe**).
Found→that user; new→create; **conflict→`409 {pending_link:{token}}`**.
Capture the **Apple refresh token (encrypted) on first Apple auth** (returned
only once). Mint access + refresh.

**Provider linking (`POST /auth/link`, F10/P0):** `pending_link` token is
**server-side opaque, single-use, ≤5-min TTL, bound to {initiating_session,
the EXACT existing user_id whose provider must re-auth, the pending new
credential}**. Body `{pending_link, existing_id_token, new_credential}`:
server verifies `existing_id_token` resolves to the **pinned** user_id (not
"any existing provider"), `new_credential` is valid + unclaimed, then
`linkWithCredential`. No client-trusted claims decide the link.

**Refresh (`POST /auth/refresh`):** atomic CAS + reuse-grace (above);
re-validate Firebase state; mint access.

**Signout / `DELETE /credentials/{id}`:** set `revoked_at` on the credential
lineage; effective next request (step 3). No grace window. Member removed →
effective next request via step 5 (no credential revoke needed).

**Delete + export:** export = inline JSON (scope: own identities+memberships+
authored content; owner gets family export). Delete = **single serializable
transaction**: cascade users→identities/memberships/credentials; **reject if
it would orphan a family and no transfer target is given** (last-owner
invariant at runtime); **record relay_email pre-delete**; **Apple
`revokeToken`** with the stored token — **on failure (missing/expired) proceed
with local delete + log the unrevoked state** (Apple accepts manual-revocation
fallback for 5.1.1(v)).

## M0 household token [M0]

A `credentials` row (`kind='cli'`, `user_id NULL`, `family_scope`=one family,
**`scopes={content:read, content:write}`** — read is required for the client's
`GET /sync` render path and the CLI `--diff`; both are *content* scopes so
"403 on every non-content route" still holds). Resolution: middleware computes
`constant_time_eq(presented, $HOUSEHOLD_SECRET)` → loads the row by
`$HOUSEHOLD_CREDENTIAL_ID` → still applies step 3 `revoked_at`. Secret in the
platform secret store (deploy role **cannot read-back**; scrubbed from logs).
**Rotation:** documented cadence + **overlap window** (old+new both valid for
N min, old auto-revoked after). No refresh, no management scope. **Blast
radius = full content-write to one family *including minor data* (ADR 0005)** —
not minimized; **alert on use anomalies** (volume/geo). Single-secret model is
**M0-only** (doesn't scale to multiple households).

## Recovery, SIM-swap, SMS (P1 — recovery is a HARD gate)

- **Owner accounts require ≥2 linked methods** (enforced); **step-up** on
  new-device phone-OTP before the **owner-only action set** (= middleware step
  6 set, single source).
- **Recovery floor is a HARD GATE, not an open question:** the
  "owner-holds-sensitive-content" state is **blocked until the manual
  recovery procedure is written + audited** (operator + counsel). It must
  impose delay + multi-signal identity proofing + notify all existing methods
  (it's the bypass for every control above it).
- **SMS:** App Check + reCAPTCHA SMS defense (Pre-GA) + region allowlist +
  per-number-prefix velocity + daily spend cap + alert.

## Open questions (resolved this pass)
- ✅ Access-TTL vs revocation: per-request credential load is the mechanism;
  TTL is defense-in-depth.
- ✅ Firebase disable/delete sync: re-validate at `/auth/refresh`.
- ⏳ Recovery-floor procedure (operator + counsel) — now a **hard gate**, not
  just `OQ-auth-recovery-floor`.
