# ADR 0023: S2 Firebase Identity — Google + Apple at launch, Phone-OTP deferred

**Status:** Accepted 2026-06-20 (operator-directed). Extends ADR 0011 (auth
architecture) and ADR 0021 (S1–S6 build decomposition); **narrows the S2
provider set**. Does not supersede either — the ADR 0011 architecture stays
intact. Immutable — supersede, do not edit.

## Context

ADR 0011 specified Firebase Auth with **Google, Apple, and Phone-OTP** as the
launch providers. Phone/SMS is the expensive, high-risk leg:

- **Spend ceiling.** Phone-OTP requires Firebase **Blaze** (metered) + a billing
  card; SMS is billed per message and is the only auth cost that scales with
  abuse, not with real users. It forced a daily SMS spend-cap + alert into MVP.
- **Fraud surface.** SMS brings SMS-pumping/toll-fraud, per-number velocity
  limits, a region allowlist, App Check + reCAPTCHA SMS defense, and **SIM-swap**
  account-takeover — the largest takeover vector in the hardened spec
  (`specs/auth-and-family-design.md` §Cross-cutting).

Google and Apple are OIDC providers: **free** on Firebase's Spark tier within the
50k-MAU free allowance, with no SMS billing and no SMS fraud surface.

## Decision

S2 ships Firebase Auth with **Google + Apple only**. **Phone-OTP is deferred**
(re-openable later behind its own spend + fraud controls). This clears the S2
vendor/cost gate that was blocking the build.

Everything else in the ADR 0011 architecture is unchanged: the backend verifies
the Firebase ID token and **mints its own** EdDSA access + rotating refresh
tokens; providers are linked **app-driven** on the
`account-exists-with-different-credential` error (no email-dedupe, no auto-link,
proof-of-control required); **one user = one person**; per-device revoke via our
own credential layer; Apple join on `sub` (never the private-relay email).

## Consequences

**Removed from MVP scope** (return with Phone-OTP):
- Firebase **Blaze** requirement for SMS / the daily SMS spend-cap + alert.
- SMS-abuse defenses: App Check + reCAPTCHA SMS, per-number-prefix velocity,
  region allowlist, SMS-pumping monitoring.
- **SIM-swap** as a takeover vector. The ADR 0011 owner rule "≥2 linked methods"
  is now satisfied by **Google + Apple** (link both), not phone — owners are
  prompted to link the second OIDC provider (the existing link-a-2nd-method
  screen, minus the phone option).

**Still required:**
- **Apple** `revokeToken` + account-deletion (App Store 5.1.1(v)) — Apple is a
  launch provider, so this stays in the account-deletion flow.
- Firebase ID-token verify (Admin SDK) at sign-in; Firebase disable/delete does
  not propagate to our tokens → periodic re-validation (unchanged).
- `OQ-auth-recovery-floor` stays open, but the recovery/takeover surface is
  smaller without phone.

**Guardrails (Hard guardrail #3 — unchanged):** no Gmail/restricted scopes → no
CASA. Calendar read stays *sensitive*-only. Firebase project stays on the **free
tier** for auth (Google/Apple); adopting any metered Firebase feature is a fresh
spend decision.

**S5/S6 UI:** the sign-in "Continue with phone" button, the **OTP entry**, and
the **OTP error / resend-limit** screens in the A8b mockups are **deferred
providers** — designed, not built at MVP. The mockups keep them (the full
vision); the S5 build renders **Google + Apple** only. No mockup change needed.

## Revisit Trigger

Adding Phone-OTP (re-opens the Blaze/SMS spend + SMS-fraud + SIM-swap scope); a
Firebase pricing change that meters Google/Apple sign-in; or a recovery-floor
decision that needs a phone factor.
