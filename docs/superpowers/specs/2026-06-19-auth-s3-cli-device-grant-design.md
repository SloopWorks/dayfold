# Auth S3 — CLI Device Grant (RFC 8628) design

**Date:** 2026-06-19 · **Branch:** `auth-s3` · **ADR:** 0021 (S3 slice; order
S1→**S3**→S2→S4→S5/S6). Implements ADR 0011 §6/7 + `auth-and-family-design §Flow 3b`
+ `04-auth`. Builds on the **AUTH-S1 backbone** (credentials, refresh lineage,
EdDSA access-JWT, `authorizeTenant`, dev-token). **Revised 2026-06-19 after a
7-dimension multi-agent review** — finding IDs `[C1,C2,C3,I1–I11,M1–M3]` are folded
in below.

## Goal & scope

`familyai login` obtains a **real, content-only, family-scoped, revocable** CLI
credential via the OAuth 2.0 Device Authorization Grant — eliminating the
hardcoded `HOUSEHOLD_SECRET`/`FAMILY_ID` for the CLI. Approval is performed by a
signed-in **owner** via an access-JWT (dev-token locally; Firebase at S2; app
screen at S6).

**In scope:** `device_authorizations` + `rate_limits` + `audit_log` tables; the
`/device/authorize` + `/device/token` device endpoints and `/families/:fid/device/
{approve,deny}` owner endpoints; protocol-level anti-phishing; the refresh ~20s
grace (correctly specified, the S1-deferred fix); CLI `login`/`logout`/`whoami` +
`push` migration.

**Out of scope (deferred):** geo/ASN enrichment + datacenter-ASN block + the
approval **screen** (S6); QR (S6); OS-keychain storage (later); **removal** of the
legacy household-token branch (gated follow); Firebase (S2); invites (S4).

## Decisions (brainstorm + review)

- **Approval = owner endpoint under the tenant path** `POST /families/:fid/device/
  approve` so `family_id` is **middleware-resolved from the PATH** (ADR 0011 §10
  anti-IDOR) — **[C3]**. The caller's credential must be **owner-role + session/
  app-kind**; a `kind='cli'`/content-only credential is **rejected (403)** so a
  stolen CLI token cannot mint more CLI creds — **[C2]**.
- **Credential minted lazily at `/device/token` redemption**, inside the same
  transaction as the one-time consume — not at approve — **[I4,I8]**. Approve only
  records intent (owner + family bound).
- **Anti-phishing: protocol-level now**; geo/ASN + datacenter-block → S6.
- **Legacy household-token branch KEPT** (removal gated). **CLI = `0600` file**,
  atomic write + cross-process lockfile **[I10]**. **QR deferred.**

## Data model (`apps/api/migrations/0003_device_grant.sql`)

```sql
CREATE TABLE device_authorizations (
  device_code   text PRIMARY KEY,               -- randomBytes(32).base64url
  user_code     text NOT NULL,                  -- 8 chars, unambiguous alphabet, XXXX-XXXX
  client        text,
  status        text NOT NULL DEFAULT 'pending'
                  CHECK (status IN ('pending','approved','denied','expired','consumed')),
  user_id       text REFERENCES users(id),      -- set at approve
  family_id     text REFERENCES families(id),   -- bound at approve (from PATH)
  credential_id text REFERENCES credentials(id),-- set at REDEEM (lazy mint)
  origin_ip     text, origin_ua text,           -- trusted-IP source [I9]; geo/ASN at S6
  interval_s    int NOT NULL DEFAULT 5,         -- fixed; not ratcheted [I6]
  last_polled_at timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  expires_at    timestamptz NOT NULL,
  approved_at   timestamptz
);
CREATE UNIQUE INDEX ON device_authorizations (user_code) WHERE status='pending';

-- [I1] rate-limit/lockout home. Atomic FIXED-window counter — window_start is
-- floored to the REAL window size, NOT date_trunc('minute') [C-2 review-2]:
--   authorize:  window_start = to_timestamp(floor(extract(epoch from now())/600)*600)  (10-min)
--   approve:    window_start = to_timestamp(floor(extract(epoch from now())/900)*900)  (15-min)
-- locked_until makes lockout DURATION independent of the counting window.
CREATE TABLE rate_limits (
  key          text NOT NULL,                   -- 'ip:authorize:<ip>' | 'account:approve:<sub>' | 'ip:token:<ip>'
  window_start timestamptz NOT NULL,
  count        int NOT NULL DEFAULT 0,
  locked_until timestamptz,
  PRIMARY KEY (key, window_start)
);

-- [I7] append-only audit for the device-grant takeover surface. Events:
-- device.authorize | device.approve | device.deny | device.token.redeemed |
-- device.lockout | refresh.reuse_revoked | refresh.grace_reissued  [I-3 review-2]
CREATE TABLE audit_log (
  id        bigserial PRIMARY KEY,
  at        timestamptz NOT NULL DEFAULT now(),
  event     text NOT NULL,
  actor_user_id text, family_id text,
  detail    jsonb NOT NULL DEFAULT '{}'         -- credential_id, reason, origin_ip/ua, etc.
);

-- NOTE [review-2]: NO reverse index on refresh_tokens(superseded_by) — the
-- grace check looks the successor up by token_hash (PK), so a superseded_by
-- index would be pure write cost. (Round-1 M3 dropped.)

-- [m-2] retention: a periodic/opportunistic sweep deletes rate_limits rows
-- older than the longest window, and terminal device_authorizations + audit_log
-- beyond a retention horizon. (Sweep mechanism = a small follow; not load-bearing.)
```
**No `failed_approve_attempts` column** — brute-force of *other* users' `user_code`
returns "not found" with no row to increment, so per-row counting can't see the
attack; the counter lives in `rate_limits` keyed by the authenticated `sub` **[I1]**.

## Endpoints

### `POST /device/authorize` — unauthenticated device endpoint
Body `{client?}` — **[E2EE hook] body validation is PERMISSIVE (ignore unknown
keys)** so an M1 CLI sending `{client_pubkey}` against an older server degrades
gracefully instead of `400` (E2EE key-bootstrap adds a nullable `client_pubkey`
column + reads it here at M1). **[I9]** derive client IP from Vercel's trusted
`x-vercel-forwarded-for` header (fall back to the right-most `x-forwarded-for` hop;
never the left-most/client-controlled hop). **[I1, C-2]** atomic rate-limit with a
**real 10-min window** (`window_start = to_timestamp(floor(extract(epoch from now())/600)*600)`):
`INSERT INTO rate_limits(key,window_start,count) VALUES ('ip:authorize:'||$ip, $window, 1)
ON CONFLICT (key,window_start) DO UPDATE SET count=rate_limits.count+1 RETURNING count`;
if `count > 10` → `429`. (Fixed-window edge-doubling accepted + documented.) Generate `device_code`
(`randomBytes(32).base64url`) + `user_code` (8 chars from `23456789CFGHJMPQRVWX`,
formatted `XXXX-XXXX`); **[M1]** on a `23505` unique-violation against the
pending-unique index, regenerate up to 3× then 500. Insert `pending`,
`expires_at=now()+600s`, `interval_s=5`, `origin_ip`, `origin_ua`. Return:
```
{ device_code, user_code, verification_uri, verification_uri_complete, expires_in: 600, interval: 5 }
```
**[I11] `verification_uri`** at S3 = the constant `"<API>/device" ` (a static
page/printed instruction; the real app deep-link domain is an OQ for S6).
`verification_uri_complete = verification_uri + "?user_code=" + user_code`.

### `POST /families/:fid/device/approve` — owner endpoint (tenant path) **[C2,C3]**
Runs the S1 `authorizeTenant(c, fid)` (family from PATH, membership re-resolved).
Then: **require `role==='owner'`** (else 403) AND **`cred.kind === 'app'` (reject
`'cli'`/content-only → 403)** — a new owner-action gate; add to the §6 owner-only
set + IDOR/owner-only test matrix. **`cred.kind` is read off the cred row
`authorizeTenant` already returns (`SELECT *`) — no middleware signature change.**
`kind` and `role` are **orthogonal axes** (kind = how-authenticated; role =
membership) — gate on BOTH. **[Firebase invariant]** all *interactive* credentials
mint `kind='app'` (today: dev-token + `mintCredentialFor`; at S2 Firebase sessions
also mint `'app'`); `cli` is the sole non-interactive kind. **If S2 ever introduces
a new interactive kind (e.g. `'session'`), it MUST be added to this allowlist or
device-approve will 403 real owners.** Body `{user_code}` (human re-confirms the
code they see, §5.4). **[I1, C-2]** before lookup: check `locked_until` then atomic
increment `account:approve:<sub>` in the **15-min window** (`floor(epoch/900)*900`);
on the 5th failure set `locked_until = now()+'15 min'` and `429`; audit
`device.lockout`. Look up a `pending`, unexpired row by `user_code`; not
found/expired → **uniform 404** + the failure counts (guessing others' codes is
bounded). On hit: set `status='approved'`, `user_id=sub`, `family_id=fid`,
`approved_at`; **no credential minted yet [I4,I8]**; reset the caller's approve
counter; audit `device.approve` (approver `sub`, `family_id`, origin ip/ua).
Return `204`. **Note:** the minted CLI cred's `user_id` = approver, so its JWT
`sub` = approver → middleware membership re-resolution auto-revokes the device
login if the approver later loses membership (intended; add to S4 member-remove
tests).

### `POST /families/:fid/device/deny` — owner endpoint **[I11]**
Same authz/lookup as approve. Owner→`204` (sets `status='denied'`); already
denied→`204` no-op; non-owner→`403`; not-found/expired→uniform `404`. Audit
`device.deny`.

### `POST /device/token` — unauthenticated token endpoint
Body `{grant_type:"urn:ietf:params:oauth:grant-type:device_code", device_code}`.
**[I-2] per-IP rate-limit** (this endpoint is unauthenticated and polled in a loop):
atomic `ip:token:<trusted-ip>` counter (10-min window, cap well above
`interval`×expected concurrent logins, e.g. 600) → `429` over cap. `device_code` is
256-bit/unguessable so this is anti-DoS/cost, not anti-bypass. Load by
`device_code` (treat `now()>expires_at` as expired regardless of stored status;
**[M2]** opportunistically flip a stale `pending` row to `expired` so its
`user_code` frees from the partial unique index). Branch (RFC 8628 §3.5):
- expired → `{error:"expired_token"}` 400.
- `denied` → `{error:"access_denied"}` 400.
- `consumed` → `{error:"expired_token"}` 400 (one-time).
- `pending`: **[I5,I6]** single conditional statement, no separate read:
  `UPDATE device_authorizations SET last_polled_at=now() WHERE device_code=$1 AND status='pending'
   AND (last_polled_at IS NULL OR last_polled_at < now() - make_interval(secs => interval_s)) RETURNING 1`
  — row returned → `{error:"authorization_pending"}`; **no row returned (still
  pending, polled too soon)** → `{error:"slow_down"}`. `interval_s` is fixed (not
  ratcheted).
- `approved`: **lazy mint in ONE transaction on a SINGLE client [I3,I4,C-1]:**
  `const client = await pool.connect()` → `BEGIN` → atomic CAS
  `UPDATE … SET status='consumed' WHERE device_code=$1 AND status='approved' RETURNING user_id, family_id`
  (loser/second redeem sees not-`approved` → `expired_token`) → INSERT the
  credential **on `client`** with an app-generated id (`'cred_'+randomBytes`, per
  `identity.ts`): `kind='cli'`, `family_scope=family_id` (non-null, satisfies the
  `0001` CHECK), **`scopes='{content:read,content:write}'` (array literal — never
  the device-row `scope` string [I2])**, `user_id`,
  `label = 'familyai-cli '||left(coalesce(origin_ua,''),64)` (**`coalesce` — bare
  `||NULL` nulls the whole label [m-1]**) → **`issueRefresh(credentialId, client)`**
  (the C-1 overload: runs its INSERT on the SAME client, NOT the pool — `q()` would
  deadlock on Vercel's `max:1` pool and break atomicity) → set
  `device_authorizations.credential_id` → `COMMIT` (ROLLBACK on throw,
  `client.release()` in finally). After commit, `mintAccess` (pure) and return
  `200 {access_token, refresh_token, token_type:"Bearer", expires_in}` — **[E2EE
  hook] this flat object is additively extensible: M1 adds `wrapped_fck`; S3
  clients ignore unknown keys.** Audit `device.token.redeemed`. **Bounded
  residual:** a crash after commit but before the HTTP response loses the one-shot
  delivery → the row is `consumed` → user re-logins (documented, rare; the
  dangerous window — consume without a matching credential — is closed by the
  single txn).

### Refresh ~20s grace — rewrite `apps/api/src/auth/refresh.ts` + `/auth/refresh` **[C1]**
The S1 `rotate()` flatly revokes on any consumed-token reuse and stores only the
successor *hash*, so "re-serve the same pair" is impossible. Redefine grace as
**idempotent re-rotation** with a **distinct return variant** so it isn't caught by
the existing `if ('reuse' in out) → 401`:
- `rotate()`'s **consumed-token branch** (before the genuine-reuse revoke): take a
  **per-lineage serialize lock** — `pg_advisory_xact_lock(hashtext(credential_id))`
  in a txn — so two requests presenting the SAME consumed token can't both reach
  the revoke path **[I-1 race fix]**. Inside the lock, re-serve ONLY when:
  `consumed_at > now() - interval '20s'` **AND** `superseded_by IS NOT NULL` **AND**
  the successor row (`WHERE token_hash = <this>.superseded_by`) has
  `consumed_at IS NULL` (chain tip still live) → **rotate from the successor** and
  return **`{refresh, graced:true}`** (new pair; client overwrites local storage).
- Else (older consumed token, or successor already consumed → chain advanced) →
  **genuine reuse → revoke the lineage** + audit **`refresh.reuse_revoked`**
  (credential_id, reason) **[I-3]** → return `{reuse:true}`.
- **`/auth/refresh`:** check **`'refresh' in out` BEFORE `'reuse' in out`** so the
  grace variant returns `200` (the existing access re-mint by `hash(out.refresh)`→
  credential join already works for the grace row — no separate mint branch). Audit
  **`refresh.grace_reissued`**.
- Tests: (a) prior token within 20s → new working pair, lineage NOT revoked;
  (b) **replay after TWO rotations within 20s → lineage revoked** (successor
  consumed → boundary holds); (c) older token → revoked; **(d) CONCURRENT
  double-present of the SAME token within 20s → both succeed-or-idempotent, lineage
  NOT revoked** (the serialize-lock test; round-1 tests were sequential-only).
  Restate to stop claiming the identical opaque is returned.

## CLI (`apps/cli`, Kotlin/JVM)

- **`familyai login`** (`--api`/`FAMILYAI_API`): `POST /device/authorize` → print
  `verification_uri` + `user_code` → poll `/device/token` every `interval`,
  **branching on the JSON `error` value, not the HTTP 400** (`authorization_pending`
  /`slow_down` → keep polling, raising the wait on `slow_down`; `expired_token`/
  `access_denied` → stop). **[I11]** hard wall-clock safety timeout = `expires_in +
  30s`. On `200` → **[I10]** atomic write (temp file → fsync → `rename`, mode
  `0600`) of `~/.config/familyai/credentials.json`
  `{"v":1, api, access_token, refresh_token, family_id, obtained_at}` — **[E2EE
  hook] the `"v":1` envelope** makes the M1 upgrade (adding the X25519 **private
  key** + `wrapped_fck`) a clean version bump, not a presence-probe. Document now:
  this `0600` file WILL hold private key material at M1 → it is **never logged,
  never sent in telemetry, never printed** (set that secrecy bar from S3).
- **`familyai push <cardId> <file.json>`** (migration): **[I11]** if a credentials
  file exists, use its `access_token` and read **`family_id` from the file** (no
  env); on `401`, **single-flight refresh under a cross-process lockfile [I10]**
  (`~/.config/familyai/refresh.lock`): `POST /auth/refresh` with the stored
  refresh, atomic-persist the new pair, retry once. If the persisted refresh is
  already consumed (reuse → 401) → instruct re-login. **No credentials file → env
  `HOUSEHOLD_SECRET`/`FAMILY_ID` legacy path, unchanged.**
- **`familyai logout`**: best-effort `POST /auth/signout`; **always delete the file
  regardless of revoke outcome**; exit 0 whether or not a file/revoke succeeded
  (idempotent) **[I11]**.
- **`familyai whoami`**: show file `family_id`/api, else the legacy env.

## Testing

**API (vitest vs live PG):** user_code charset/length/format + pending-uniqueness +
[M1] collision-retry; authorize per-IP atomic rate-limit (429 over cap) + trusted-
IP source [I9]; approve **owner+app-kind only** (non-owner→403, **`kind='cli'`
token→403 [C2]**, cross-family owner→404 via path [C3], bad `user_code`→uniform 404
+ **lockout after 5 [I1]**); deny states [I11]; token poll states
(`authorization_pending`, `slow_down` on fast poll [I5], `expired_token`,
`access_denied`); approved→`{access,refresh}` **one-time** (2nd redeem→`expired_token`);
**lazy-mint produces a `kind='cli'` cred whose `push` write actually SUCCEEDS [I2]**
and works **only on the bound family (other family→404)**; **mint+consume atomic —
inject failure, row stays approved, retry succeeds [I4]**; revoked minted cred →
dead next request. **Refresh grace [C1]:** prior re-serves new pair (no revoke);
two-rotations-then-replay → lineage revoked; older → revoked. Audit rows written
[I7]. Full existing suite + household regression stay green.

**CLI:** login writes a `0600` atomic file; `push` reads `family_id` from it,
refreshes on 401 under the lockfile (single-flight), falls back to env when no
file; `logout` deletes regardless; an end-to-end `login`(dev-token-approved)→`push`
round-trip against a local server succeeds.

## Forward-compat (verified by review-2; no S3 code, decisions to honor)

- **E2EE key bootstrap (M1, ADR 0015) slots in additively:** `client_pubkey` →
  permissive authorize body + nullable column; `wrapped_fck` → additive token
  response key; CLI key material → the `"v":1` `0600` file. The
  **approve(intent)/token(deliver) split is the right seam** — at M1 the owner
  client seals the FCK to the row's `client_pubkey` at *approve*, the server
  stashes it, the CLI receives it at *token*. One device = one family = one
  FCK-wrap is the correct shape.
- **The one real E2EE constraint (ADR 0017 — write it down):** `user_code` is the
  random anti-phishing code (8 chars ≈ 34.6 bits — the right anti-guess size) and
  is **NOT** widened to carry a key fingerprint. At M1 the CLI pubkey fingerprint
  rides the **QR payload / a separate short-authentication-string (SAS) displayed
  next to `user_code`** (ADR 0017's confirmed-string-on-both-sides), never
  `user_code` itself. Do not bake a pubkey-derived code into S3's server-minted
  `user_code`.
- **Firebase (S2):** the `kind='app'` allowlist on device-approve is forward-correct
  (enum is `('app','cli')`; interactive creds all mint `'app'`). The invariant note
  in §approve governs: a new S2 interactive kind must join the allowlist. Step-up /
  owner-≥2-methods (ADR 0011 §9) is a clean S2 add to the owner-action authz list —
  no S3 change.

## Definition of Done

The three tables migrated; the four `/device/*` + two `/families/:fid/device/*`
endpoints implemented + fully tested; the refresh grace rewritten + tested; audit
records emitted; the CLI does `login`/`logout`/`whoami` + device-granted `push`
with lockfile refresh + legacy fallback; a `login`→`push` round-trip works
end-to-end; whole API suite + household regression green; Vercel bundle
regenerated. Legacy branch retained (removal gated). Geo/ASN, QR, keychain,
approval screen, the real `verification_uri` deep-link domain (OQ) deferred to
S6/follow.
