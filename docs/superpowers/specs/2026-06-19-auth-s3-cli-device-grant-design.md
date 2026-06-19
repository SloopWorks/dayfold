# Auth S3 — CLI Device Grant (RFC 8628) design

**Date:** 2026-06-19 · **Branch:** `auth-s3` · **ADR:** 0021 (S3 slice; build order
S1→**S3**→S2→S4→S5/S6). Implements ADR 0011 §6/7 + `auth-and-family-design §Flow 3b`
+ `04-auth`. Builds on the **AUTH-S1 backbone** (credentials, refresh lineage,
EdDSA access-JWT, `authorizeTenant`, dev-token).

## Goal & scope

`familyai login` obtains a **real, content-only, family-scoped, revocable** CLI
credential via the OAuth 2.0 Device Authorization Grant — eliminating the
hardcoded `HOUSEHOLD_SECRET`/`FAMILY_ID` for the CLI and (once deployed) the
device path. Approval is performed by a signed-in **owner** via an access-JWT
(dev-token locally now; Firebase at S2; the app *screen* is S6).

**In scope:** `device_authorizations` table; `POST /device/{authorize,approve,
deny,token}`; protocol-level anti-phishing; the CLI `login`/`logout` + `push`
migration with refresh-on-401; and folding the **S1-deferred refresh ~20s
reuse-grace** into `refresh.ts` (the CLI is the retrying client that needs it).

**Out of scope (deferred):** geo/ASN enrichment + datacenter-ASN warn/block **and**
the approval **screen** (S6); QR rendering (S6); OS-keychain storage (later
hardening); **removal** of the legacy household-token branch (gated follow once
the device-granted CLI is proven + deployed + the operator has migrated);
Firebase identity (S2); invites (S4).

## Decisions (from brainstorm)

- **Approval = backend endpoint** `POST /device/approve` authed by an **owner's
  access-JWT**; family bound at approval. (App screen = S6.)
- **Anti-phishing: protocol-level now** (user_code entropy/alphabet, on-approval
  user_code confirm, one-time `device_code`, `interval`/`slow_down`, rate-limit +
  lockout, record origin IP/UA). **geo/ASN + datacenter-block → S6.**
- **Legacy household-token branch KEPT through S3** (non-breaking); removal is a
  gated follow.
- **CLI credential storage = `0600` file** `~/.config/familyai/credentials.json`
  (OS keychain later). **QR deferred** — CLI prints text URL + `user_code`.

## Data model (`apps/api/migrations/0003_device_grant.sql`)

```
device_authorizations(
  device_code   text PRIMARY KEY,              -- high-entropy opaque (≥32 bytes b64url)
  user_code     text NOT NULL,                 -- ≥8 chars, unambiguous alphabet, XXXX-XXXX
  client        text,                          -- e.g. "familyai-cli"
  scope         text NOT NULL DEFAULT 'content:read content:write',
  status        text NOT NULL DEFAULT 'pending'
                  CHECK (status IN ('pending','approved','denied','expired','consumed')),
  user_id       text REFERENCES users(id),     -- set at approval
  family_id     text REFERENCES families(id),  -- bound at approval
  credential_id text REFERENCES credentials(id),-- the minted CLI credential
  origin_ip     text, origin_ua text,          -- recorded at authorize (S6 displays geo/ASN)
  failed_approve_attempts int NOT NULL DEFAULT 0,
  interval_s    int NOT NULL DEFAULT 5,
  last_polled_at timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  expires_at    timestamptz NOT NULL,
  approved_at   timestamptz
);
CREATE UNIQUE INDEX ON device_authorizations (user_code) WHERE status='pending';
```
`status='consumed'` = the one-time `device_code` was redeemed at `/device/token`
(distinct from `approved` so a second redeem fails). Expiry is enforced by
comparing `expires_at` at every read (a sweep job is out of scope; expired rows
are treated as expired regardless of a stale `status`).

## API endpoints (`apps/api/src/app.ts` + `src/auth/device.ts`)

### `POST /device/authorize` — unauthenticated device endpoint
Body `{client?, scope?}`. Generate `device_code` (`randomBytes(32).base64url`) +
`user_code` (8 chars from the **Crockford-style unambiguous alphabet**
`23456789CFGHJMPQRVWX` — 20 symbols, no 0/O/1/I/L/U/vowels-that-form-words;
formatted `XXXX-XXXX`). Insert `pending`, `expires_at = now()+600s`,
`interval_s=5`, record `origin_ip` (first `x-forwarded-for` hop) + `origin_ua`.
**Per-IP DB-backed rate-limit** (serverless has no shared memory): cap
authorize-creates per IP per window. Return:
```
{ device_code, user_code, verification_uri, verification_uri_complete, expires_in: 600, interval: 5 }
```
`verification_uri` = a documented approval URL (the app deep-link target at S6; a
descriptive constant now); `verification_uri_complete` appends `?user_code=…`.

### `POST /device/approve` — auth: owner access-JWT (self/owner route class)
Body `{user_code, family_id}`. The human **re-confirms the `user_code`** they see
on the device (§5.4 anti-phishing — the request carries it). Steps:
1. Authenticate the access-JWT (S1 `verifyAccess` + credential not revoked).
2. **Per-account + global lockout:** if this user has ≥N (e.g. 5) recent failed
   approve attempts → 429 + lockout window. (DB-backed counter.)
3. Look up a `pending`, unexpired `device_authorization` by `user_code`. Not
   found/expired → **uniform 404-style error** + increment the account's failed
   counter (no enumeration of which codes exist).
4. Require the caller is an **`owner` with `active` membership on `family_id`**
   (re-resolved per request) → else 403.
5. In one transaction: mint a `kind='cli'` credential
   (`scopes={content:read,content:write}`, `user_id`, `family_scope=family_id`) +
   a refresh-lineage seed; set the row `status='approved'`, `user_id`,
   `family_id`, `credential_id`, `approved_at`; reset `failed_approve_attempts`.
Return `204`.

### `POST /device/deny` — auth: owner
Same lookup/authorization as approve; sets `status='denied'`.

### `POST /device/token` — unauthenticated token endpoint
Body `{grant_type:"urn:ietf:params:oauth:grant-type:device_code", device_code}`.
Load by `device_code`; branch (RFC 8628 §3.5):
- expired (`now()>expires_at`) → `{error:"expired_token"}` 400.
- `denied` → `{error:"access_denied"}` 400.
- `pending`:
  - if `last_polled_at` and `now() - last_polled_at < interval_s` → bump
    `interval_s += 5`, set `last_polled_at`, return `{error:"slow_down"}` 400.
  - else set `last_polled_at`, return `{error:"authorization_pending"}` 400.
- `approved`: **atomically** flip `status` `approved→consumed`
  (`UPDATE … WHERE device_code=$1 AND status='approved' RETURNING credential_id`;
  a second redeem sees `consumed` → `{error:"expired_token"}`). Mint
  `{access, refresh}` for `credential_id` (S1 `mintAccess` + `issueRefresh`).
  Return `200 {access_token, refresh_token, token_type:"Bearer", expires_in}`.

### Refresh reuse-grace (fold S1 debt into `apps/api/src/auth/refresh.ts`)
Implement the **~20s grace re-serve**: presenting the **immediately-prior**
(just-consumed, ≤~20s ago, whose `superseded_by` is the current live token) token
**re-serves the same successor pair** instead of revoking; an **older** consumed
token still → revoke the lineage. This stops a CLI timeout+retry from
self-revoking. Keep the atomic-CAS rotate + reuse→revoke for genuine reuse.

## CLI (`apps/cli`, Kotlin/JVM)

- **`familyai login`** (`--api <url>` or `FAMILYAI_API`): `POST /device/authorize`
  → print `"Visit {verification_uri}\nEnter code: {user_code}"` → poll
  `/device/token` every `interval` (honor `slow_down` by raising the wait;
  stop on `expired_token`/`access_denied`/timeout) → on `200` write
  `~/.config/familyai/credentials.json` (mode **0600**):
  `{api, access_token, refresh_token, family_id, obtained_at}`.
- **`familyai logout`**: best-effort revoke (`POST /auth/signout` with the access
  token) + delete the file.
- **`familyai push`** (migration): if a stored credential exists, use its
  `access_token`; on a `401`, **single-flight** `POST /auth/refresh` with the
  stored `refresh_token`, persist the new pair, retry once. **If no stored
  credential**, fall back to env `HOUSEHOLD_SECRET` (legacy path, unchanged).
- **`familyai whoami`**: show stored `family_id`/api (or the legacy env).
- Single-flight refresh (no concurrent refreshes) — required given the ~20s
  grace covers retry, not parallel double-rotate.

## Testing

**API (vitest vs live PG):** user_code charset/length/format + uniqueness;
authorize per-IP rate-limit; approve owner-only (non-owner→403, bad `user_code`→
uniform error + lockout after N, cross-family owner→403); token poll states
(`authorization_pending`, `slow_down` on fast poll, `expired_token`,
`access_denied`); approved→`{access,refresh}` **one-time** (2nd redeem fails);
**the issued access works only on the bound family (other family→404)**; revoked
minted credential → token/refresh dead next request. **Refresh grace:**
prior-token within grace re-serves the SAME pair; older consumed → lineage
revoked. Full existing suite + household regression stay green.

**CLI (Kotlin test or scripted against a local server):** login writes a `0600`
file with the granted tokens; `push` uses the stored token, refreshes on 401
(single-flight), and falls back to env when no file exists; `logout` removes it.

## Security notes (protocol-level, this slice)

- `device_code` high-entropy + one-time (consumed on redeem); `user_code` from a
  20-symbol unambiguous alphabet, **pending-unique**, short-TTL (~10 min).
- Approval is **owner-authed + per-request membership re-resolved**; bad-code
  lockout + uniform errors prevent `user_code` brute-force / enumeration.
- `interval`/`slow_down` enforced server-side; rate-limits are **DB-backed**
  (serverless = no shared memory).
- Minted credential is **content-scope only**, family-scoped, revocable (reuses
  the S1 `authorizeTenant` enforcement + revocation).
- Origin IP/UA recorded for the S6 approval screen (geo/ASN + datacenter-block
  land there).

## Definition of Done

`device_authorizations` migrated; the four `/device/*` endpoints implemented +
fully tested; the refresh ~20s grace implemented + tested; the CLI does
`login`/`logout`/`whoami` + `push` over a device-granted token with refresh-on-401
and legacy fallback; a CLI `login`→`push` round-trip works end-to-end against a
local server with a dev-token-approved authorization; the whole API suite + the
household-token regression stay green; the Vercel bundle regenerated. Legacy
branch retained (removal gated). Geo/ASN, QR, keychain, and the approval screen
explicitly deferred to S6/follow.
