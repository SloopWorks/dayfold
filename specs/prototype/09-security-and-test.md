# 09 — Security Controls + Test / Verify Plan (capstone)

> Status: **draft → in review**. Consolidates the controls + must-fixes from
> every component review into one matrix, a test plan, and the build gates.
> Sources: 01–08 + ADR 0011 (auth), 0012 (agent build/deploy), 0014 (privacy),
> 0015 (E2E), `research/design-review-auth-2026-06.md`.

## Security controls matrix

| Domain | Control | Where |
|---|---|---|
| **Tenancy** | One mandatory middleware on every route; family from PATH (or resource row, then re-checked); **default-deny**; cross-tenant → **404** | 03/04 |
| **Authz** | Re-resolve membership+role+scope+credential-not-revoked **per request**, fail-closed; owner-only action set = step-up set | 04 |
| **Tokens** | EdDSA, `alg` allowlist, `kid`∈in-memory-allowlist, iss/aud env-distinct, JWKS serve-stale; never trust token claims | 04 |
| **Refresh** | Lineage table, atomic CAS rotate, ~20s reuse-grace, reuse→revoke lineage; absolute lifetime | 02/04 |
| **Sign-in** | Firebase Admin-SDK verify (not decode-only); no email dedupe; join on provider UID; **never auto-link** (proof-of-control) | 04 |
| **Invite** | Owner-approved only (no auto-join); ≥128-bit hashed token; atomic claim (use only on net-new); **identity-bound approval**; cap pending; uniform 404 | 05 |
| **Device grant** | RFC 8628; on-phone `user_code` confirm + origin/ASN warning; email→push cut; one-time `device_code`; bind family at approval (owner-of-that-family) | 04 |
| **Mass-assignment** | Server-managed fields ignored-if-present; `family_id` from path only | 03 |
| **DoS** | gzip compressed+decompressed caps, ratio guard, embedded-children cap, body_md 1 MB; rate-limit device/invite/auth | 03/06 |
| **Storage** | Private ACL, ListBucket denied, tenant-prefixed+nonce keys, key from stored `body_ref` (never reassembled), GET-only ≤60s URLs, least-priv runtime role + delete-only GC role | 06 |
| **Markdown** | No raw HTML, link-scheme allowlist, images-off; sanitize on ingest + render | event-hubs |
| **Privacy (triggers)** | Live position never leaves device; matched on-device; **no location/plaintext in logs/crash/analytics** (detekt rule + egress test) | 08/0014 |
| **E2E (if 0015)** | AEAD AAD=(family_id,id,version); FCK never to server; reasoning only in key-holder; SQLCipher at-rest (`linkSqlite=false`) | 0015/06/08 |
| **Secrets (agent-deploy)** | Per-env keys, secret manager, deploy role binds-not-reads, scrubbed logs, M0 token rotation-overlap + use-anomaly alert | 04/0012 |
| **Minors (0005)** | Adults-only at MVP; CLI tokens can't read minor fields; age = owner attestation, not a hard boundary | 0005 |

## IDOR / tenancy test matrix (ADR 0011 §10)

For **every** resource, a member of family A receives **403/404 (never 200)**
on family B's object — including the non-content routes that are easy to miss:
`hubs · sections · blocks · cards · places · sync · invites · members ·
device_authorizations · credentials · object-storage signed-URL mint/replay ·
intents (when built)`. Automated; one test per resource per verb.

## Test strategy (layers)

- **Unit:** pure redux reducers, validators, the atomic-claim/CAS SQL, token
  signing/verification, markdown sanitizer, trigger active-set selection.
- **Integration:** API ↔ Postgres ↔ object storage; **Firebase Emulator** for
  auth/device-grant/invite; idempotent upsert + parent-exists + version/If-Match.
- **Security (the review must-fixes as tests):** see register below.
- **Contract:** requests/responses validate against the JSON schema (single
  source); OpenAPI generated; client + CLI codegen from it.
- **E2E / browser (Claude-in-Chrome, ADR 0012):** the real flow — auth → push
  (CLI) → sync → render → deep-link → trigger fire.
- **Client:** selector/screenshot tests; `./gradlew build` (compile+test+
  detekt+apiCheck) is the client gate.

## Security test register (must pass before prod)

1. Device-grant phishing: approval requires `user_code` confirm + shows
   origin; datacenter ASN flagged; email→push absent.
2. Invite: forwarded token → only pending (no data) until owner approval;
   no-op redeem never burns a use; removed never auto-resurrects.
3. Provider link: no auto-link; proof-of-control required; unverified email
   rejected.
4. Refresh: concurrent double-submit re-serves (no self-DoS); old token →
   lineage revoke.
5. JWKS/kid: unknown kid → 401 no refetch; iss validated before key select.
6. Mass-assignment: `role/status/scope/family_id` in body ignored.
7. IDOR matrix (above) green.
8. Upload: over-cap / zip-bomb / oversized markdown rejected; signed URL can't
   cross tenant or be replayed past expiry.
9. Privacy egress: no coordinate-shaped / plaintext payload in any log, crash
   report, or non-essential network call.
10. M0 household token: 403 on every non-content route — AND **can read+write
    content** (`GET /sync` + `--diff` function; scope = content:read+write).
11. **Sync tombstone:** a soft-deleted row surfaces as a tombstone past the
    cursor on the next sync page (soft-delete bumps `updated_at`) — the row is
    never silently missed. *(Integration test, M0 read path.)*

## Verify loop & CI gates (ADR 0012)

`test-green-before` (unit+integration+emulator + preview verify) →
**promote to prod** → **post-deploy health/smoke + browser flow check** →
**auto-rollback on failure** → log every prod/cost action. No prod deploy
skips the rails.

## Hard gates before build (blocking)

1. **Recovery-floor procedure** written + audited (04 — operator + counsel).
2. **redux-kotlin `1.0.0-alpha1` coordinates/modules confirmed** (08 pre-build
   gate) — else the render-isolation + state-keyed-deep-link story changes.
3. **Operator decisions:** INB-9 (TS/Vercel host), **INB-10 (ADR 0015 E2EE —
   changes M0 schema)**, ADR 0005/0006/0007 ratifications.
4. **Adults-only confirmed** (COPPA); **no Gmail OAuth at MVP** (CASA avoided).

## Build order (M0 path → first dogfood)

1. DB (02) + content API write/read/sync (03) + **M0 household token** (04 M0
   slice) — the dumb-store spine.
2. CLI push/auth + deterministic-ID authoring + `dayfold push` (07) — the
   operator can author.
3. CMP client render: cache + sync + Now/Hubs + deep-link (08 M0 slice) — the
   operator sees it on device. **← first dogfood (Gate G1a DoD).**
4. Then M1: Firebase auth → invite → device-grant → triggers/geofencing → E2E
   key distribution.

## Residual risks (accepted / tracked)
- Pre-1.0 deps (redux-kotlin, libsodium/SQLCipher if E2E) — pinned.
- Signed-URL ≤60s replay; metadata leakage under E2E (sizes/place-count).
- SIM-swap on phone-only owners (dampened by ≥2-method); manual recovery floor.
- Device-compromise / malicious approved member (out of scope of all controls).

## Open questions
- Which security tests run per-preview vs per-prod (cost vs coverage).
- Pen-test / external review before a paid public launch (post-prototype).
