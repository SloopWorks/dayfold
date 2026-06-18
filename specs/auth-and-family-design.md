# Auth, Family Scope & Invite — Design (Draft)

> **Status: Draft / pre-spec (2026-06-18).** Architecture for account, login,
> family tenancy, member invite, and CLI/Claude-Code authorization. Decided
> in `adr/0010`. **Scope note:** this is the real multi-user auth story —
> beyond the ADR 0007 prototype (single token, no login). Design now (per
> design-first ADR 0008); build later; supersedes ADR 0007's "single
> household token / no login" clause when implemented. Hand to Claude Design
> for UI/UX once approved.

## Decisions (locked)

- **Firebase Auth** = authentication foundation (Google, Apple, Phone-OTP);
  passwordless, one-tap.
- **Many-to-many** user↔family membership in the data model; **single-family
  UI** at MVP (future-proofs co-parenting/eldercare niche).
- **CLI/Claude-Code auth = OAuth 2.0 Device Authorization Grant (RFC 8628)**,
  QR scan-to-approve, with email→push and `user_code` entry as fallbacks.
- **Invite policy:** in-person **QR auto-joins** (short TTL, single-use);
  **shared link → pending membership the owner approves.**

## Identity

- **One `user` = one person.** Multiple auth providers (Google / Apple /
  phone) are *linked* to the same user. Dedupe on verified email/phone.
- Handle: Apple **private-relay** email (store relay, link by Apple `sub`);
  Firebase **"account-exists-with-different-credential"** → offer to link
  rather than create a duplicate.
- Backend verifies the **Firebase ID token** at sign-in (Admin SDK / JWKS),
  then **mints its own tokens** so app and CLI hold uniform, scopable,
  revocable credentials:
  - **Access token** — short-lived (~1h), bearer on API calls.
  - **Refresh token** — rotating, revocable, stored in Keychain (iOS) /
    Keystore (Android) / OS credential store (CLI).

## Data model

```
users(id, display_name, created_at)
user_identities(user_id, provider, provider_uid, verified_email?, verified_phone?)
families(id, name, created_by, created_at)
memberships(user_id, family_id, role, status, joined_at)      -- M:N join
invites(id, family_id, role, token_hash, expires_at, max_uses, used_count,
        mode[qr|link], status[active|revoked|exhausted], created_by, created_at)
device_authorizations(device_code, user_code, user_id?, family_id?, client,
        scope, status[pending|approved|denied|expired], expires_at, interval, approved_at)
credentials(id, user_id, family_scope, kind[app|cli], scopes, refresh_hash,
        label, last_used, created_at, revoked_at)              -- powers "Connected devices & apps"
```

- **Roles:** `owner` (creator — manage members/invites/family), `adult`
  (member); `teen` (14+) deferred to ADR 0005 + counsel.
- **Authorization:** every API request resolves requester → active
  `membership` in the target `family_id` → role / (later) per-Hub
  visibility. **Default-deny.** All content (briefing cards, Hubs) is
  `family_id`-scoped.
- **Tokens store only hashes** (`token_hash`, `refresh_hash`); raw secrets
  are shown once and never persisted server-side in clear.

## Flow 1 — First-run sign-in (one-tap, passwordless)

1. App launch, no session → sign-in screen: **Continue with Google /
   Apple / phone**.
   - Google/Apple → Firebase OAuth → ID token.
   - Phone → Firebase phone auth → single SMS OTP.
2. Backend verifies ID token → **find-or-create** `user` (dedupe; offer
   provider-link on conflict) → issues access + refresh.
3. Branch: **has ≥1 active membership → app (Now surface, active family);
   else → onboarding (Flow 2).**

*(Apple sign-in is mandatory on iOS because Google sign-in is offered —
Apple guideline 4.8.)*

## Flow 2 — Onboarding → create family

- Minimal: ask **Family name** (the creator's display name is prefilled from
  the IdP profile).
- Create `family` + `membership(owner, active)` → land on the family **null
  state** (which presents the two next actions: invite a member, connect a
  device/CLI).

## Flow 3a — Member invite (QR / low-friction)

1. Owner taps **Invite** → backend mints invite: high-entropy token (store
   `token_hash` only), role (`adult` default), `expires_at`, `max_uses`,
   `mode`.
   - **QR mode:** TTL ~15 min, `max_uses = 1`.
   - **Link mode** (SMS/AirDrop/copy): TTL ~72 h.
2. Encoded as a **Universal/App Link** QR: `https://<app>/invite/{token}`
   (link mode shares the same URL).
3. Invitee taps/scans → app opens (deferred deep link if it must install
   first) → **authenticates as themselves** (invite ≠ identity) → backend
   validates token (not expired/exhausted/revoked).
4. **Join by policy:**
   - **QR (in-person):** membership created **active immediately**.
   - **Shared link:** membership created **pending → owner approves**
     (owner gets a notification with Approve/Decline).
5. **Security:** single-use (QR) / capped (link); short TTL; owner can
   **revoke** an outstanding invite and **remove** a member; owner is
   notified on join. Already-a-member → friendly no-op.

## Flow 3b — CLI / Claude-Code authorization (RFC 8628 device grant)

1. `familyai login` (or the Claude skill's auth) → backend **device
   authorization endpoint** returns: `device_code`, short `user_code`
   (e.g. `WXYZ-4321`), `verification_uri`, `verification_uri_complete`
   (URL embedding the code), `expires_in` (~10 min), `interval`.
2. CLI displays a **QR** encoding `verification_uri_complete` (a
   Universal/App Link) **+ the `user_code`** as text fallback.
3. **Approve on the phone (signed-in app):**
   - *Primary:* scan QR → app opens **"Authorize device?"** showing the
     **scope** ("Push & manage content for *<Family>*"), a device label, and
     the family selector → **Approve / Deny**.
   - *Fallbacks (operator's described path):* in-app "Link a device" → enter
     `user_code`; or CLI takes the user's email → **push notification** to
     that account's app → approve.
4. CLI **polls** the token endpoint
   (`grant_type=urn:ietf:params:oauth:grant-type:device_code`) honoring
   `interval` + `slow_down` → on approval receives a **scoped, revocable**
   credential: **content read/write for the chosen family only** (no member
   management, no auth scope), with a human `label`.
5. CLI stores it in the OS credential store; **revoke** anytime from
   **Connected devices & apps**. Access token rotates via refresh.

> This is the **critical path for dogfood** — the operator's Claude-Code
> authoring loop is a first-class authenticated client pushing content to a
> family it's scoped to. Consistent with the dumb-renderer model (ADR 0007).

## Cross-cutting (security/UX)

- **Account recovery:** passwordless → recover by re-auth via a *linked*
  provider. **Nudge linking ≥2 methods** at onboarding (phone-only + lost
  SIM = lockout). Last-resort recovery = operator/support, manual.
- **SMS-OTP abuse:** Firebase phone auth + **App Check** + per-number/per-IP
  rate limiting to curb SMS-pumping toll fraud and slow SIM-swap abuse.
- **Least privilege + revoke everywhere:** sign-out, remove member, revoke
  CLI/device token, expire/cancel invite — all first-class. Default-deny
  authorization.
- **Deep links:** invite + device-approve depend on Universal/App Links →
  requires `assetlinks.json` + `apple-app-site-association` on a verified
  domain (`OQ-deeplink-domain`). Pulled forward **when this is built**.
- **Privacy/COPPA:** adults-only at prototype (ADR 0007); teen (14+)
  memberships gated by ADR 0005 + counsel. Collect only display name +
  the auth identifier.

## Screens needed (hand to Claude Design — M3 Expressive, ADR 0009)

1. **Sign-in** — three one-tap options (Google / Apple / phone) + the
   phone-OTP entry step.
2. **Link-a-second-method** nudge (post-sign-in, skippable).
3. **Onboarding** — create family (name) ; (member join variant: "You've
   been invited to *<Family>*").
4. **Family null state** — two CTAs: Invite a member · Connect a device/CLI.
5. **Invite** — generate QR + share-link sheet; outstanding-invites list
   (revoke).
6. **Authorize device** — the RFC 8628 approval screen (scope, label, family
   selector, Approve/Deny) + the `user_code` entry variant.
7. **Family members** — list, roles, pending approvals (link-invite), remove.
8. **Connected devices & apps** — list CLI/app credentials, last-used,
   revoke.
9. Pending-approval notification/sheet (owner).

## Open questions (also in `context/open-questions.md`)

- OQ-auth-recovery-floor: the manual last-resort recovery path + its abuse
  surface.
- OQ-family-switcher: when multi-family UI arrives, the switcher UX (deferred
  with the M:N→single-family-UI decision).
- OQ-invite-roles: can a non-owner adult invite? (Default: owner-only at MVP.)
- OQ-deeplink-domain (existing): domain-association files for invite +
  device-approve links.
