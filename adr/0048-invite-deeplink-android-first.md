# ADR 0048: Invite Deep-Links — Android App Links now, iOS Universal Links deferred

## Status

**Accepted** 2026-07-07 (operator-directed in-session: "review the ADR gated
decisions… lets unblock this" → chose *Apple account: none yet* + *ADR + build
Android now*). Supersedes the invite-scope portion of **ADR 0011 §1**'s
"no Universal/App Links" deferral (that deferral was written for the ADR 0007
*prototype*; App Links have since shipped for the S6-D device-grant flow, so the
capability is already accepted and live — this ADR extends it to invites).

## Context

The owner invite-mint UI (`feat/owner-invite-mint-ui`) mints an invite whose URL
is `https://family-ai-dashboard.vercel.app/invite/{token}`. Tapping/opening that
URL today **404s** — no route or deep-link handler consumes it. The functional
redeem path is paste-only: copy the link → Dayfold → "Join a family" → paste
(the app strips `/invite/<token>` → token → `POST /invites:redeem`).

Crucially, the App Links machinery is **already built and shipping** for the
device-grant (RFC 8628) flow on the same domain:

- `GET /.well-known/assetlinks.json` — Android App Links, `handle_all_urls`
  (domain-scoped → **already covers `/invite`**), real cert fingerprints
  (`ANDROID_CERT_SHA256`, release + debug fallback).
- A verified `autoVerify="true"` intent-filter in `AndroidManifest` (host =
  the vercel domain, path `/device`).
- `MainActivity.handleDeepLink()` (cold-start + `onNewIntent`) → `AuthEngine.
  openDeviceLink()` with a stash-and-consume-after-sign-in pattern.
- `GET /device` — a browser landing page for the no-app / desktop case.
- `GET /.well-known/apple-app-site-association` (AASA) — exists, but `appID`
  is the placeholder `TEAMID.com.sloopworks.dayfold` and lists only `/device`.

So the only genuinely gated dependency is **iOS Universal Links**, which need a
real Apple **Team ID** (`APPLE_APP_ID` env) → an **Apple Developer Program
membership ($99/yr)**. The operator confirms none is provisioned yet (it is the
same account Apple Sign-In, ADR 0023, needs). The **domain is not a blocker** —
`family-ai-dashboard.vercel.app` already serves App Links for device-grant; a
branded consumer domain is a later branding decision (A6), not a functional one.

## Decision

1. **Android invite deep-links are in scope NOW.** Extend the already-accepted,
   already-shipped device-grant App Link pattern to `/invite`:
   - **API:** add `GET /invite/:token` — a browser landing page mirroring
     `GET /device` (tells the human to open Dayfold / offers the paste path);
     kills the 404 and is the fallback for the no-app / desktop case. Add
     `/invite` to the AASA `paths` (harmless while iOS is deferred; ready for
     when it ships).
   - **Manifest:** add `path="/invite"` (a `<data>` element) to the verified
     App Link intent-filter — same host, **same `assetlinks.json`** (no
     change; it is `handle_all_urls`), same signing cert.
   - **Client:** `MainActivity.handleDeepLink()` recognizes `/invite/<token>`
     → `AuthEngine.openInviteLink()`; a **stash-and-consume-after-sign-in**
     mirror of `openDeviceLink` (redeem requires an authenticated invitee —
     spec §41). Reuses `redeemInvite` + the existing `JoinInviteScreen` outcome
     states. No new capability, no new spend, no new domain.

2. **iOS Universal Links remain DEFERRED** until an Apple Developer account is
   provisioned. When it is: set `APPLE_APP_ID` env to the real `TeamID.bundleId`,
   the `/invite` AASA path (added in §1) activates, add the iOS
   `associated-domains` entitlement + a `handleDeepLink` mirror. This is a
   **spend decision (guardrail #6, ~$99/yr)** — shared with Apple Sign-In, so it
   lands with that provisioning, not as separate spend.

3. **Domain stays `family-ai-dashboard.vercel.app`** for App Links. A branded
   consumer domain (if/when chosen, A6) re-points the manifest host + AASA/
   assetlinks origin — a mechanical follow-up, not a blocker.

## Consequences

- The invite QR/link becomes **tap-to-join on Android** (verified App Link →
  opens Dayfold → sign-in → redeem → waiting-for-approval), and stops 404-ing in
  a browser (landing page). The macOS/desktop and iOS cases keep the paste path
  until iOS ships.
- Guardrails untouched: no new customer-data handling, no LLM path, no external
  messaging, no spend (Android reuses the paid-nothing vercel domain + existing
  cert). The **only** future spend is the Apple account, explicitly gated in §2.
- Security posture unchanged: the deep-link only *transports* the token into the
  same authenticated `POST /invites:redeem` (uniform-404, per-account
  rate-limit/lockout, owner-approved-pending — ADR 0011 / spec 05). Possession of
  a link still yields only a pending membership.

Composes 0011 / 0023 (Apple account) / spec `05-invite.md` §41–45 / the shipped
S6-D device-grant App Link plumbing.
