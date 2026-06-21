# Auth, Family Scope & Invite — Design (Draft, hardened)

> **Status: Draft / pre-spec (2026-06-18, hardened after 5-agent review).**
> Decided in **ADR 0011** (supersedes ADR 0010). Review record:
> `research/design-review-auth-2026-06.md`. **Scope note:** this is the real
> multi-user auth story — a **distinct milestone AFTER the ADR 0007
> prototype**. The prototype keeps its single household token (no RFC 8628,
> no Universal Links). Design now (ADR 0008), build later. Hand §Screens to
> Claude Design for UI/UX.

## Decisions (locked, per ADR 0011)

- **Firebase Auth** = authentication (Google, Apple, Phone-OTP); passwordless.
  CMP integration is via the **community GitLive `firebase-kotlin-sdk`** (no
  official KMP SDK) + native `expect/actual` glue for Phone-OTP & Apple.
- **One user = one person**; providers linked **app-driven** (NOT by email
  dedupe). **Backend mints its own** access + rotating refresh tokens
  (Firebase revocation is global-per-UID only, so per-device revoke requires
  our own credential layer).
- **Family = tenant; many-to-many membership**; single-family UI at MVP.
- **CLI/Claude-Code auth = OAuth 2.0 Device Grant (RFC 8628)**, QR
  scan-to-approve **+ in-app `user_code` entry only** (email→push removed).
- **All invites are owner-approved** (pending → approve). **No auto-join.**
- **Content API path is tenant-explicit:** `/families/{fid}/...`.

## Identity (corrected to Firebase reality)

- Multiple providers linked to one `user` via `linkWithCredential`,
  **app-driven on the `account-exists-with-different-credential` error**
  (carry the pending credential; user picks the provider). **Do NOT** probe
  by email (`fetchSignInMethodsForEmail` is dead under Email Enumeration
  Protection, on by default) and **do NOT auto-link**.
- **Never auto-link without proof-of-control:** require sign-in with the
  existing provider before attaching a new one. Match only on
  `email_verified==true` (Google/Apple) or an OTP-proven phone.
- **Join key = provider UID.** Apple: join on `sub`; **never dedupe on the
  private-relay email** (it can change). **Phone is its own identity** (no
  email to dedupe on).
- **Apple account-deletion + `revokeToken`** is required (App Store
  5.1.1(v)) and is part of the account-deletion flow.
- Backend verifies the Firebase ID token at sign-in (Admin SDK) → mints
  access (short) + rotating refresh. **Firebase account disable/delete does
  NOT propagate to our tokens** → sync via blocking functions or periodic
  re-validation.

## Data model

```
users(id, display_name, created_at, updated_at, deleted_at)
user_identities(id, user_id FK, provider, provider_uid, email_verified?, phone_verified?,
    UNIQUE(provider, provider_uid))
families(id, name, created_by FK, created_at, updated_at, deleted_at)
memberships(user_id FK, family_id FK, role, status, joined_at, updated_at,
    PRIMARY KEY(user_id, family_id))                  -- M:N join
invites(id, family_id FK, role, token_hash UNIQUE, mode[qr|link], expires_at,
    max_uses, used_count, status[active|revoked|exhausted|expired], created_by, created_at)
device_authorizations(device_code PK, user_code, user_id?, family_id?, client, scope,
    status[pending|approved|denied|expired], expires_at, interval, approved_at,
    UNIQUE(user_code) WHERE status='pending')
credentials(id, user_id FK, family_scope FK NOT NULL for kind=cli, kind[app|cli],
    scopes, refresh_hash UNIQUE, label, last_used_at, last_used_ip, created_ua, revoked_at)
-- content (relational, family-scoped; see specs/event-hubs-design.md):
hubs(id, family_id FK NOT NULL, type, title, status, start?, end?, countdown_to?,
    version, created_at, updated_at, deleted_at)
sections(id, hub_id FK, title, order, created_at, updated_at, deleted_at)
blocks(id, section_id FK, type, payload jsonb, provenance jsonb, version,
    created_at, updated_at, deleted_at, UNIQUE(hub_id, id))
```

- **Roles:** `owner`, `adult`; `teen` (14+) deferred to ADR 0005. **Default-
  deny**; all content `family_id`-scoped. **Tokens store hashes only.**
- **Invariants:** **≥1 active owner per family** (ownership transfer required
  before last-owner leave/delete); membership state machine
  `pending→active→removed`; invite atomic claim guard
  (`UPDATE … WHERE used_count<max_uses AND status='active' AND expires_at>now()`);
  device codes one-time + swept after expiry.
- **Enums** as CHECK/lookup, not free text. Indexes: `memberships(family_id,
  status)`, `hubs(family_id, status)`, `sections(hub_id, order)`,
  `blocks(section_id)`, `invites(family_id, status)`.

## Flow 1 — First-run sign-in (one-tap)

Google / Apple / phone → Firebase ID token → backend verifies → **app-driven
link on conflict** (proof-of-control) → find-or-create user → issue tokens →
**has active membership → app : onboarding.** (Apple sign-in included per
Apple guideline 4.8.) Offline / OTP-error / link-conflict states are
first-class (see §Screens).

## Flow 2 — Onboarding → create family

Ask **Family name** only (display name prefilled) → create `family` +
`membership(owner, active)` → **prompt to link a 2nd auth method (required
for owners)** → family null state (two CTAs: invite a member, connect a
device/CLI).

## Flow 3a — Member invite (owner-approved, no auto-join)

1. Owner taps **Invite** → backend mints hashed token, role (`adult`
   default), TTL (QR ~15 min / link ~72 h), `max_uses`, `mode`.
2. Encoded as a Universal/App-Link QR `https://<app>/invite/{token}` (link
   mode shares the URL).
3. Invitee scans/taps → app opens → **authenticates as themselves** →
   backend validates token (not expired/exhausted/revoked) → creates
   **pending** membership.
4. **Owner approves** (notification + approval queue) → membership `active`.
   Invitee sees a **"waiting for approval"** state until then. Owner can
   revoke an outstanding invite and remove a member; invitee-side
   **expired/revoked/exhausted/already-member** states are defined.
5. **Role re-checked at approval** (esp. for future teen/14+ per ADR 0005 —
   owner attests age at approval, not at mint).
6. **Deferred deep-linking cut from v1:** cold-install invitee re-taps the
   link after install (72 h TTL covers it).

## Flow 3b — CLI / Claude-Code auth (RFC 8628, hardened)

1. `dayfold login` → device endpoint returns `device_code`, `user_code`
   (≥8 chars, unambiguous ~20-symbol alphabet), `verification_uri_complete`,
   `expires_in` (~10 min), `interval`.
2. CLI shows a **QR** + the `user_code` text.
3. **Approve on the signed-in app** — scan QR **or** enter `user_code`
   in-app (**email→push removed**). The approval screen:
   - requires the user to **confirm the `user_code` matches** (RFC 8628 §5.4
     anti-phishing — QR fills it, human confirms),
   - shows the **request origin geo/ASN** and **warns/blocks datacenter
     origins**,
   - shows scope ("Push & manage content for *<Family>*") + a **family
     selector** (family bound **at approval**, owner-role required),
   - copy: "Only approve if YOU just started this on YOUR computer."
4. CLI **polls** (`interval`/`slow_down`; `device_code` one-time, rate-
   limited, locked-out after N) → on approval gets a **content-only,
   family-scoped, revocable** credential (cannot manage members or read
   minor-profile fields). Deny/expire/timeout states defined both sides.
5. Stored in the OS credential store; **revoke** from Connected devices.

## Cross-cutting (hardened)

- **Tokens:** asymmetric signing (EdDSA/RS256), strict `alg` allowlist, full
  `iss/aud/exp/nbf`, key rotation. **Never trust `family_id`/`scope`/`role`
  from the token** — re-resolve membership + credential-not-revoked **per
  request**. Refresh **reuse-detection → revoke the token family**.
  Revocation effective within one request (member-remove, device-revoke,
  sign-out all immediate).
- **Authorization = one mandatory middleware** every route inherits;
  deny-by-default if a route doesn't declare its `family_id` source.
  **Per-resource IDOR tests** (family A → 403/404 on family B) covering
  invite / device_authorization / credential / membership endpoints.
- **Account lifecycle:** sign-out, leave-family (non-owner), **delete-account
  + data-export** (cascade across users/identities/memberships/credentials;
  honor last-owner invariant; Apple `revokeToken`).
- **SMS abuse:** App Check + reCAPTCHA SMS defense (Pre-GA) + **region
  allowlist** + per-number-prefix velocity + **daily SMS spend cap + alert**.
- **SIM-swap:** owner accounts require ≥2 linked methods + step-up on
  new-device phone-OTP before sensitive actions.
- **Account recovery floor:** explicit identity-proofing procedure + audit
  trail (it's a takeover surface). `OQ-auth-recovery-floor` stays open.
- **Privacy/COPPA:** adults-only at prototype; teen (14+) gated by ADR 0005
  + counsel. CLI tokens cannot read minor-profile fields. Scrub PII/
  provenance pushed into shared cards.
- **Firebase cost:** free 50k MAU; **SMS billed (Blaze + card)**; avoid MFA/
  blocking-functions to stay off metered Identity Platform.

## Screens (hand to Claude Design — M3 Expressive, ADR 0009)

1. Sign-in (Google/Apple/phone) + OTP entry **+ OTP error/resend-limit +
   offline** states.
2. Link-a-second-method (required for owners).
3. Onboarding — create family; invitee variant "You've been invited to
   *<Family>*"; **invitee waiting-for-approval**; invitee **expired/revoked/
   exhausted/already-member** states.
4. Family null state — Invite a member · Connect a device/CLI.
5. Invite — generate QR + share-link; outstanding-invites (revoke).
6. **Authorize device** (RFC 8628) — `user_code` confirm, origin geo/ASN
   warning, scope, family selector, Approve/Deny; `user_code`-entry variant;
   deny/expired states. **[was missing from the mockups — add this]**
7. Family members — list, roles, **pending approvals**, remove, leave.
8. Connected devices & apps — list, last-used, revoke.
9. Provider-link-conflict confirm; account-deletion + data-export.

## Open questions (also in `context/open-questions.md`)

- OQ-auth-recovery-floor; OQ-family-switcher; OQ-invite-roles (default
  owner-only); OQ-deeplink-domain (invite + device-approve links, when built).
