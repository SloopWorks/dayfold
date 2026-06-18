# 03 — API Design

> Status: **reviewed (2 agents) → fixes applied**. The HTTP contract off `01-architecture.md` +
> `02-data-model.md`. Tenant-explicit, default-deny, idempotent. The machine-
> readable OpenAPI is **generated from the JSON schema** (single source of
> truth); this doc is the human contract. Milestone tags **[M0]** / **[M1]**.

## Conventions

- **Base:** `https://api.<host>/v1`. JSON in/out; **gzip** request + response.
- **Auth:** `Authorization: Bearer <token>`.
  - **[M0]** household token (static secret, content-write, single family).
  - **[M1]** minted access token (short-lived); rotate via refresh.
- **Tenancy:** every family-scoped route is `/families/{fid}/...` and passes
  the one membership/scope middleware. **Cross-tenant access returns `404`**
  (not 403) to avoid existence enumeration. Default-deny.
- **Idempotent writes:** content is `PUT` by client-supplied **stable ID**
  (ULID). Re-PUT = update, never duplicate. **Nested PUT requires the parent
  to exist** → `409` otherwise (no orphans).
- **Concurrency:** responses carry `ETag: "<version>"`; writes may send
  `If-Match`. Mismatch → `412`. M0 is single-writer LWW (If-Match optional).
- **Errors:** `application/problem+json` (RFC 9457): `{type,title,status,
  detail,instance}`. Codes: `400` malformed · `401` no/!valid token · `403`
  authenticated-but-forbidden (in-tenant) · `404` not-found/cross-tenant ·
  `409` parent-missing/conflict · `412` version mismatch · `422` schema-invalid
  · `429` rate-limited (+`Retry-After`).
- **Pagination:** cursor (`?cursor=&limit=`), `next_cursor` in body.
- **Validation + mass-assignment (P0):** bodies validate against the JSON
  schema (`422` on failure). **Server-managed fields are IGNORED if present**
  (never merged): `role, status, scope, scopes, family_id, family_scope,
  used_count, version, provenance, revoked_at, *_at` timestamps. **`family_id`
  comes from the PATH only.** So `:redeem` can't set `role:owner`, self-
  `:approve` can't set `status:active`, a content PUT can't forge
  `provenance`/`family_id`.

## [M0] Content — write (CLI / Claude Code)

| Method · Path | Body | Notes |
|---|---|---|
| `PUT /families/{fid}/hubs/{id}` | Hub (no children, or with) | upsert hub |
| `PUT /families/{fid}/hubs/{hid}/sections/{sid}` | Section | parent hub must exist |
| `PUT /families/{fid}/hubs/{hid}/sections/{sid}/blocks/{bid}` | Block (incl. `body_md`) | parent section must exist; gzip for long markdown |
| `POST /families/{fid}/hubs/{id}:archive` | — | sets `status=archived` |
| `DELETE /families/{fid}/hubs/{id}` | — | **soft-delete** (`deleted_at`); cascades in app |
| `DELETE /families/{fid}/hubs/{hid}/sections/{sid}` | — | soft-delete, cascade |
| `DELETE /families/{fid}/hubs/{hid}/sections/{sid}/blocks/{bid}` | — | soft-delete |
| `PUT /families/{fid}/cards/{id}` | BriefingCard (+`target`) | the "Now" surface |
| `DELETE /families/{fid}/cards/{id}` | — | soft-delete |

- A whole markdown file → one `block` (`type:"markdown"`, `body_md`) via the
  block PUT. **`body_md` limited to 1 MB at M0** (else `413`; M0 has no spill,
  spill is M1/06).
- **Unarchive** = `PUT` the hub with `status` changed (no separate verb).
- **Bulk full-replace (F5):** `PUT /families/{fid}/hubs/{id}` MAY embed
  sections+blocks. Semantics, declared: children **absent from the body are
  soft-deleted** (`deleted_at`), parent+child `version` bumped, and the whole
  op is rejected on `If-Match` mismatch against the hub. **Capped** embedded-
  children count (see size limits). The CLI is the primary user (07).

## [M0] Content — read / sync (mobile client)

| Method · Path | Notes |
|---|---|
| `GET /families/{fid}/cards?active=true` | Now feed (filters `not_before`/`expires_at`, `deleted_at IS NULL`) |
| `GET /families/{fid}/hubs?status=` | Hub list (Projects) |
| `GET /families/{fid}/hubs/{id}` | full dossier (sections+blocks) |
| `GET /families/{fid}/sync?since=<cursor>&limit=` | **delta pull** for the local cache (see schema below) |

**Sync contract (F4):** cursor = opaque keyset `(updated_at, id)` (no change-log
table at M0). Response:
```
{ "changes": { "hubs":[…], "sections":[…], "blocks":[…], "cards":[…] },
  "tombstones": [ {"type":"block","id":"…"}, … ],   // rows with deleted_at > since
  "next_cursor": "…", "has_more": true }
```
Soft-deletes surface as tombstones; the client applies changes+tombstones to
its SQLDelight cache, then renders. Deep-link `target` resolves against the
cache, nearest-ancestor fallback.

The client renders only from its local cache, populated by `sync`. Deep-link
`target` resolves against the cache (nearest-ancestor fallback) — never a
server round-trip per tap.

## [M1] Auth & account

| Method · Path | Notes |
|---|---|
| `POST /auth/session` | body `{id_token}` → **verify via Admin SDK (sig + `aud`=this project + `iss` + `exp`), reject decode-only** → find-or-create user → mint access+refresh (alg allowlist EdDSA/RS256, reject `none`/confusion). Provider conflict → `409 {pending_link:{token}}` (echo to `/auth/link`) |
| `POST /auth/refresh` | rotating refresh; **reuse-detection → revoke family** |
| `POST /auth/signout` | revoke this credential (effective immediately) |
| `POST /auth/link` | app-driven provider link — **requires proof-of-control** (re-auth with existing provider) before attach |
| `GET /auth/export` · `DELETE /auth/account` | data export; delete (cascade + Apple `revokeToken`; honor last-owner) |

## [M1] Families, members, invites

| Method · Path | Notes |
|---|---|
| `POST /families` · `GET /families` | create (→ owner membership) · list mine |
| `GET /families/{fid}/members` · `DELETE …/members/{uid}` | list · remove (last-owner guarded) |
| `POST …/members/{uid}:approve` · `:decline` | owner approves a pending join |
| `POST /families/{fid}/invites` | mint (role, mode qr/link, ttl, max_uses) → returns raw token **once** + QR URL |
| `GET …/invites` · `DELETE …/invites/{id}` | outstanding · revoke |
| `POST /invites:redeem` | token in **body/header** (not path — keeps it out of logs), raw token ≥128 bits. Invitee (authenticated) → creates **pending** membership only (server-set status; never `active` even if body says so). `200 {family_id, role, status:"pending"}`. Unknown/revoked/expired/exhausted → uniform **`404`** (no enumeration). Already-member → `409` with distinct `problem.type` |

All invites create a **pending** membership; owner approval activates (ADR 0011).

## [M1] CLI / Claude-Code device grant (RFC 8628)

| Method · Path | Notes |
|---|---|
| `POST /device/authorize` | CLI (unauthenticated) → `{device_code,user_code,verification_uri,verification_uri_complete,expires_in,interval}`. Records origin **IP/geo/ASN**. **OAuth2 error format** (`{error,error_description}`, 400), not problem+json. Per-IP+global rate-limit; cap concurrent pending |
| `GET /device/authorizations/{user_code}` | app (signed-in): returns origin **IP/geo/ASN**, requested **scope**, and the device label for the approve screen. `404` if no pending row |
| `POST /device/approve` | app (signed-in): body echoes the **confirmed `user_code`** + **selected `family_id`**. Server: resolve the single `pending` row by `user_code`; **bind credential `family_scope` := selected family; assert approver is `active` `owner` of THAT family (re-resolved); record approver**. Reject if not `pending`. **Confirm screen shows the target family NAME** + origin warning (datacenter-ASN flagged). Rate-limited + lockout (≤5 wrong codes / code lifetime) |
| `POST /device/token` | CLI poll: full `grant_type=urn:ietf:params:oauth:grant-type:device_code` → **OAuth2 error JSON** `authorization_pending`/`slow_down`(+5s)/`expired_token`/`access_denied` (400) or `200 {access,refresh}` (content-only, family-scoped). `device_code` **one-time, rejected once status≠pending**; interval≥5s, hard poll cap |
| `POST /device/deny` | app |
| `GET /credentials` · `DELETE /credentials/{id}` | Connected devices & apps · revoke (effective within one request) |

## Cross-cutting controls (in every handler)

- Re-resolve membership + scope + credential-not-revoked **per request**;
  never trust token claims (ADR 0011 §8).
- Rate-limit: `/device/authorize`, `/device/token` (poll interval+`slow_down`),
  `/auth/session` (per-IP), invite redeem, `/auth/refresh`. SMS handled in
  Firebase (region allowlist + App Check).
- Audit-log every write + every prod/cost-relevant action (ADR 0012).
- Object-storage signed URLs (M1) minted only post-authz, tenant-prefixed
  keys, ≤60s expiry — never embedded in stored markdown.

## Security & contract hardening (2-agent review applied)

- **Request-size / zip-bomb caps (P0):** at the edge/middleware — max
  compressed body 1 MB; **decompressed cap enforced during streaming inflate**
  (abort+`413`); compression-ratio guard; embedded-children cap on bulk hub
  PUT; `body_md` ≤ 1 MB. Direct cost-DoS defense for the <$50/mo cap.
- **Per-route authz, fail-closed (P1):** the membership/scope/credential-not-
  revoked re-resolution runs on **every** route including `:action`
  sub-resources and the **non-`/families`-prefixed** routes (`/credentials/{id}`,
  `/invites:redeem`, `/device/*`, `/auth/*`) — tenancy derived from the
  resource row, cross-tenant → `404`. **Privileged actions**
  (member approve/decline/remove, invite mint/revoke, credential revoke,
  device approve) require `role='owner'` on **that** family. The revocation
  lookup **fails closed** (error/timeout → `401`, never fail-open).
- **M0 household token confinement (P1):** content-write-scoped credentials
  get **`403` on every non-content route** (scope check in the mandatory
  middleware). Household token has no refresh, no management scope, single
  `family_scope`, server-revocable. Test: household token → `403` on
  `POST …/invites` and `POST /device/approve`.
- **Refresh rotation (P1):** token-family = the credential row + its rotation
  lineage; reuse-detection → revoke that lineage (+audit). Signout revokes the
  presenting lineage with **no grace window**.
- **BREACH (P1):** do **not** gzip responses carrying one-time secrets (invite
  raw token, any code); never echo submitted tokens/`user_code`/identifiers in
  `problem.detail`.
- **Error hygiene (P2):** generic, constant `401/403/404` bodies; cross-tenant
  always identical `404` regardless of existence; no DB errors/stack in detail.
- **Status rules:** parent in *another* tenant → `404`; parent absent in *this*
  tenant → `409` (F7). GETs return `ETag`; single-resource GET supports
  `If-None-Match → 304` (F8). `:action` sub-resources are idempotent
  (re-approve active → `200` no-op).
- **Export (F13):** `/auth/export` returns inline JSON at MVP (small tenants);
  becomes a job later.

## Open questions (resolved this pass)
- ✅ Sync cursor = opaque `(updated_at,id)` keyset + envelope (above).
- ✅ Bulk = full-replace soft-delete of omitted children, version-bumped,
  If-Match-gated (above); CLI-owned (07).
- ✅ `body_md` / request-size limits set (1 MB; caps above).
